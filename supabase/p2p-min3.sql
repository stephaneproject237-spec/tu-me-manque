-- Free tune : au-delà de 15 Mo, le p2p partage le fichier entre AU MOINS 3 serveurs en ligne.
-- S'il y en a moins de 3 en ligne, un seul serveur télécharge le fichier (comme avant), pas de découpage.
-- À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.

create or replace function public.c_relay_create(p_yid text, p_q text, p_ti text, p_ch text, p_d int, p_art text, p_fmt text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare me uuid := auth.uid(); n int; on_n int; rid bigint; notified boolean; tgt uuid; ids uuid[]; sb public.c_subs; inflight int; v_p2p boolean := false;
        v_yid text := nullif(btrim(coalesce(p_yid, '')), '');
        v_q text := left(btrim(coalesce(p_q, '')), 120);
        v_fmt text := coalesce(nullif(btrim(coalesce(p_fmt, '')), ''), 'a');
        v_art text := left(btrim(coalesce(p_art, '')), 300);
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  -- format : seulement les qualités connues
  if v_fmt not in ('a', 'al', 'ah', 'v', 'vl', 'vh') then return json_build_object('status', 'bad_request'); end if;
  -- identifiant YouTube : exactement 11 caractères autorisés (lettres, chiffres, _ et -)
  if v_yid is not null and v_yid !~ '^[A-Za-z0-9_-]{11}$' then return json_build_object('status', 'bad_request'); end if;
  -- il faut soit un identifiant, soit une recherche (sans retour à la ligne ni caractère de contrôle)
  if v_yid is null and (v_q = '' or v_q ~ '[[:cntrl:]]') then return json_build_object('status', 'bad_request'); end if;
  -- l'image doit être une adresse https (ou vide)
  if v_art <> '' and v_art !~* '^https://[^[:space:]]+$' then v_art := ''; end if;
  -- limite : 20 demandes par minute et par compte, et 8 demandes en cours maximum
  if not public._rate_ok('rc:' || me::text, 20, 60) then return json_build_object('status', 'rate_limited'); end if;
  if (select count(*) from public.c_relay where from_id = me and status in ('open', 'taken')) >= 8 then
    return json_build_object('status', 'rate_limited');
  end if;
  select count(*), count(*) filter (where public.c__avail(p) <> 'off') into n, on_n
    from public.c_profiles p where p.id in (select h from public.c__helpers(me) h);
  if n = 0 then return json_build_object('status', 'no_helper'); end if;
  select id into rid from public.c_relay
    where from_id = me and status in ('open', 'taken') and fmt = v_fmt
      and ((v_yid is not null and yid = v_yid) or (v_yid is null and q = v_q))
    order by id desc limit 1;
  if rid is not null then return json_build_object('status', 'ok', 'id', rid, 'helpers', n); end if;
  -- Abonnement : actif, ou essai gratuit (24 h, 3 téléchargements TERMINÉS). L'essai n'est décompté qu'à la fin d'un téléchargement réussi (voir c_relay_done).
  if not public.is_admin() then
    insert into public.c_subs (user_id) values (me) on conflict (user_id) do nothing;
    select * into sb from public.c_subs where user_id = me;
    if not (sb.paid_until is not null and sb.paid_until > now()) then
      select count(*) into inflight from public.c_relay where from_id = me and status in ('open', 'taken');
      if not (now() < sb.trial_start + interval '1 day' and sb.trial_dl + inflight < 3) then
        return json_build_object('status', 'pay');
      end if;
    end if;
  end if;
  -- Mode p2p (réglage de l'administrateur) : audio m4a (formats a / al) et au moins 3 serveurs EN LIGNE -> la demande est ouverte à tous,
  -- qui se partagent les morceaux (le fichier n'est réellement découpé qu'au-dessus de 15 Mo, sinon un seul le télécharge en entier).
  -- Moins de 3 en ligne : pas de découpage, un seul serveur s'en charge comme avant.
  v_p2p := public.c__setting('p2p', false) and v_fmt in ('a', 'al') and on_n >= 3;
  if not v_p2p and public.c__setting('random_dispatch', true) then tgt := public.c__pick_server(me, '{}'); end if;
  notified := exists (select 1 from public.c_relay where from_id = me and status in ('open', 'taken') and created_at > now() - interval '40 seconds' and target_id is null);
  insert into public.c_relay (from_id, yid, q, ti, ch, d, art, fmt, target_id, assigned_at, p2p)
    values (me, v_yid, v_q, left(regexp_replace(coalesce(p_ti, ''), '[[:cntrl:]]', ' ', 'g'), 120), left(regexp_replace(coalesce(p_ch, ''), '[[:cntrl:]]', ' ', 'g'), 60),
            least(greatest(coalesce(p_d, 0), 0), 43200), v_art, v_fmt,
            tgt, case when tgt is null then null else now() end, v_p2p)
    returning id into rid;
  if tgt is not null then
    perform public.c__fcm(array[tgt], 'relay');
  elsif v_p2p or not notified then
    select array_agg(h) into ids from public.c__helpers(me) h;
    perform public.c__fcm(ids, 'relay');
  end if;
  perform public.c__ping_stale(me);
  return json_build_object('status', 'ok', 'id', rid, 'helpers', n);
end $$;

revoke all on function public.c_relay_create(text, text, text, text, int, text, text) from public, anon;
grant execute on function public.c_relay_create(text, text, text, text, int, text, text) to authenticated;
