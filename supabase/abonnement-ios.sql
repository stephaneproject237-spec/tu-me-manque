-- Free tune iPhone : abonnement (essai gratuit puis 500 FCFA / 30 jours) contrôlé côté serveur à chaque demande de téléchargement.
-- À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.
create or replace function public.c_relay_create(p_yid text, p_q text, p_ti text, p_ch text, p_d int, p_art text, p_fmt text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare me uuid := auth.uid(); n int; rid bigint; notified boolean; tgt uuid; ids uuid[]; sb public.c_subs; v_state text := 'active'; v_left int := 0;
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
  select count(*) into n from public.c__helpers(me);
  if n = 0 then return json_build_object('status', 'no_helper'); end if;
  select id into rid from public.c_relay
    where from_id = me and status in ('open', 'taken') and fmt = v_fmt
      and ((v_yid is not null and yid = v_yid) or (v_yid is null and q = v_q))
    order by id desc limit 1;
  if rid is not null then return json_build_object('status', 'ok', 'id', rid, 'helpers', n); end if;
  -- Abonnement : actif, ou essai gratuit (24 h, 3 téléchargements) ; sinon il faut payer. Compté ici, côté serveur : l'iPhone ne peut pas le contourner.
  insert into public.c_subs (user_id) values (me) on conflict (user_id) do nothing;
  select * into sb from public.c_subs where user_id = me for update;
  if sb.paid_until is not null and sb.paid_until > now() then
    v_state := 'active';
  elsif now() < sb.trial_start + interval '1 day' and sb.trial_dl < 3 then
    update public.c_subs set trial_dl = trial_dl + 1, updated_at = now() where user_id = me;
    v_state := 'trial'; v_left := 2 - sb.trial_dl;
  else
    return json_build_object('status', 'pay');
  end if;
  if public.c__setting('random_dispatch', true) then tgt := public.c__pick_server(me, '{}'); end if;
  notified := exists (select 1 from public.c_relay where from_id = me and status in ('open', 'taken') and created_at > now() - interval '40 seconds' and target_id is null);
  insert into public.c_relay (from_id, yid, q, ti, ch, d, art, fmt, target_id, assigned_at)
    values (me, v_yid, v_q, left(regexp_replace(coalesce(p_ti, ''), '[[:cntrl:]]', ' ', 'g'), 120), left(regexp_replace(coalesce(p_ch, ''), '[[:cntrl:]]', ' ', 'g'), 60),
            least(greatest(coalesce(p_d, 0), 0), 43200), v_art, v_fmt,
            tgt, case when tgt is null then null else now() end)
    returning id into rid;
  if tgt is not null then
    perform public.c__fcm(array[tgt], 'relay');
  elsif not notified then
    select array_agg(h) into ids from public.c__helpers(me) h;
    perform public.c__fcm(ids, 'relay');
  end if;
  perform public.c__ping_stale(me);
  return json_build_object('status', 'ok', 'id', rid, 'helpers', n, 'state', v_state, 'trial_left', v_left);
end $$;
