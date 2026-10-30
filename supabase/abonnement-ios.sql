-- Free tune : abonnement (essai gratuit 24 h / 3 téléchargements terminés, puis 500 FCFA / 30 jours), Android ET iPhone.
-- Un téléchargement ne compte que lorsqu'il est TERMINÉ avec succès. Les comptes administrateur ne sont jamais bloqués.
-- À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.

create or replace function public.c_sub_status()
returns json language plpgsql security definer set search_path = public as $$
declare s public.c_subs;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  insert into public.c_subs (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  select * into s from public.c_subs where user_id = auth.uid();
  if public.is_admin() then
    return json_build_object('status', 'ok', 'state', 'active', 'paid_until', s.paid_until, 'trial_end', s.trial_start + interval '1 day', 'trial_left', 3, 'now', now());
  end if;
  return json_build_object(
    'status', 'ok',
    'state', case when s.paid_until is not null and s.paid_until > now() then 'active'
                  when now() < s.trial_start + interval '1 day' and s.trial_dl < 3 then 'trial'
                  else 'expired' end,
    'paid_until', s.paid_until,
    'trial_end', s.trial_start + interval '1 day',
    'trial_left', greatest(0, 3 - s.trial_dl),
    'now', now()
  );
end $$;

-- Avant un téléchargement : a-t-on le droit ? (ne décompte rien)
create or replace function public.c_sub_can()
returns json language plpgsql security definer set search_path = public as $$
declare s public.c_subs;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if public.is_admin() then return json_build_object('status', 'ok', 'state', 'active'); end if;
  insert into public.c_subs (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  select * into s from public.c_subs where user_id = auth.uid();
  if s.paid_until is not null and s.paid_until > now() then return json_build_object('status', 'ok', 'state', 'active'); end if;
  if now() < s.trial_start + interval '1 day' and s.trial_dl < 3 then return json_build_object('status', 'ok', 'state', 'trial', 'trial_left', 3 - s.trial_dl); end if;
  return json_build_object('status', 'pay');
end $$;

-- Un téléchargement vient de se terminer avec succès : décompte l'essai gratuit.
create or replace function public.c_sub_done()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_subs set trial_dl = least(trial_dl + 1, 3), updated_at = now()
    where user_id = auth.uid() and not (paid_until is not null and paid_until > now()) and now() < trial_start + interval '1 day';
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.c_sub_status(), public.c_sub_can(), public.c_sub_done() from public, anon;
grant execute on function public.c_sub_status(), public.c_sub_can(), public.c_sub_done() to authenticated;

create or replace function public.c_relay_create(p_yid text, p_q text, p_ti text, p_ch text, p_d int, p_art text, p_fmt text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare me uuid := auth.uid(); n int; rid bigint; notified boolean; tgt uuid; ids uuid[]; sb public.c_subs; inflight int;
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
  return json_build_object('status', 'ok', 'id', rid, 'helpers', n);
end $$;

create or replace function public.c_relay_done(p_id bigint, p_media text, p_size bigint)
returns json language plpgsql security definer set search_path = public as $$
declare k int; v_from uuid;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_media is null or p_media !~ ('^' || auth.uid()::text || '/[0-9a-f]{32}\.(mp3|m4a|mp4)$') or coalesce(p_size, 0) < 0 or coalesce(p_size, 0) > 52428800 then
    return json_build_object('status', 'bad_request');
  end if;
  update public.c_relay set status = 'done', media = p_media, size = p_size
    where id = p_id and taker_id = auth.uid() and status = 'taken'
    returning from_id into v_from;
  get diagnostics k = row_count;
  -- Téléchargement réussi : on décompte l'essai gratuit de celui qui l'a demandé (jamais en cas d'échec).
  if k = 1 and v_from is not null then
    update public.c_subs set trial_dl = least(trial_dl + 1, 3), updated_at = now()
      where user_id = v_from and not (paid_until is not null and paid_until > now()) and now() < trial_start + interval '1 day';
  end if;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;
