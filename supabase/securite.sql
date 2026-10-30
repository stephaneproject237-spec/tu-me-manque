-- Contrôle strict des demandes de téléchargement (relais iPhone -> Android)
-- À coller dans Supabase > SQL Editor > + New query > Run.
create or replace function public.c_relay_create(p_yid text, p_q text, p_ti text, p_ch text, p_d int, p_art text, p_fmt text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare me uuid := auth.uid(); n int; rid bigint; notified boolean; tgt uuid; ids uuid[];
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

-- Le fichier rendu par le téléphone qui télécharge doit être un vrai fichier envoyé par LUI dans SON dossier du stockage.
create or replace function public.c_relay_done(p_id bigint, p_media text, p_size bigint)
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_media is null or p_media !~ ('^' || auth.uid()::text || '/[0-9a-f]{32}\.(mp3|m4a|mp4)$') or coalesce(p_size, 0) < 0 or coalesce(p_size, 0) > 52428800 then
    return json_build_object('status', 'bad_request');
  end if;
  update public.c_relay set status = 'done', media = p_media, size = p_size
    where id = p_id and taker_id = auth.uid() and status = 'taken';
  get diagnostics k = row_count;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;

-- Fonctions internes : réservées au serveur, plus appelables depuis un compte connecté.
-- (Avant : n'importe quel compte pouvait forcer des amitiés en masse ou envoyer des notifications à d'autres.)
do $$
declare r record;
begin
  for r in
    select p.oid::regprocedure as f from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.prokind = 'f'
      and p.proname in ('c__auto_friend_all', 'c__fcm', 'c__ping', 'c__ping_stale', 'c__pick_server', 'c__helpers', 'c__can', 'c__touch', 'c__heartbeat',
                        'auto_friend_all', '_link', '_nowpic_expire', 'c__befriend')
  loop
    execute format('revoke all on function %s from public, anon, authenticated', r.f);
    execute format('grant execute on function %s to service_role', r.f);
  end loop;
end $$;

-- Demandes d'ami : 15 par minute et par compte (empêche de deviner les pseudos / numéros ou d'inonder les gens de demandes).
do $$
begin
  if to_regprocedure('public.c__send_request_impl(text)') is null then
    alter function public.c_send_request(text) rename to c__send_request_impl;
  end if;
end $$;
create or replace function public.c_send_request(p_username text)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is not null and not public._rate_ok('rq:' || auth.uid()::text, 15, 60) then
    return json_build_object('status', 'error');
  end if;
  return public.c__send_request_impl(p_username);
end $$;
revoke all on function public.c__send_request_impl(text) from public, anon, authenticated;
grant execute on function public.c__send_request_impl(text) to service_role;
revoke all on function public.c_send_request(text) from public, anon;
grant execute on function public.c_send_request(text) to authenticated, service_role;
