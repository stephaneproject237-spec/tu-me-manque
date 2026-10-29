-- Free tune : 3 états de présence des Android (dans Free tune / sur internet / hors ligne), choix du serveur le moins chargé,
-- et reprise automatique si un Android perd internet pendant un téléchargement.
-- À lancer une seule fois, APRÈS les codes « relais » et « clients » (peut être relancé sans risque).

alter table public.c_profiles add column if not exists in_app boolean not null default false;
alter table public.c_profiles add column if not exists last_ping timestamptz;
alter table public.c_relay add column if not exists beat_at timestamptz;
alter table public.c_relay add column if not exists lost_at timestamptz;
alter table public.c_relay add column if not exists skip uuid[] not null default '{}';

-- État d'un compte : app = dans Free tune, net = en ligne sur internet (hors de Free tune), off = hors ligne.
create or replace function public.c__avail(p public.c_profiles)
returns text language sql stable as $$
  select case when p.last_seen > now() - interval '2 minutes' and p.in_app then 'app'
              when p.last_seen > now() - interval '5 minutes' then 'net'
              else 'off' end
$$;

-- Notification silencieuse vers des comptes (p_type : 'relay' = télécharge, 'ping' = signale-toi).
create or replace function public.c__fcm(p_ids uuid[], p_type text)
returns void language plpgsql security definer set search_path = public, extensions as $$
declare nurl text; nsecret text;
begin
  if p_ids is null or cardinality(p_ids) = 0 then return; end if;
  select (regexp_match(prosrc, $re$url := '([^']+)'$re$))[1], (regexp_match(prosrc, $re$x-webhook-secret', '([^']+)'$re$))[1]
    into nurl, nsecret from pg_proc where proname = 'c_push_on_notification' limit 1;
  if nurl is null or nsecret is null then return; end if;
  perform net.http_post(
    url := nurl,
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-webhook-secret', nsecret),
    body := jsonb_build_object('token', p.fcm_token, 'silent', true, 'data', jsonb_build_object('type', p_type))
  ) from public.c_profiles p where p.id = any(p_ids) and p.fcm_token is not null;
end $$;

-- Le meilleur serveur pour un iPhone : parmi ses amis Android en ligne qui partagent, celui qui n'a rien en cours,
-- sinon celui dont le travail est le plus proche de la fin (au hasard en cas d'égalité).
create or replace function public.c__pick_server(p_from uuid, p_skip uuid[])
returns uuid language sql stable security definer set search_path = public as $$
  select p.id from public.c_friends f join public.c_profiles p on p.id = f.friend_id
  where f.user_id = p_from and p.fcm_token is not null and p.relay_share
    and p.id <> all (coalesce(p_skip, '{}'))
    and public.c__avail(p) in ('app', 'net')
  order by coalesce((select sum(greatest(0, 67 - r.prog)) from public.c_relay r
                     where (r.status = 'taken' and r.taker_id = p.id) or (r.status = 'open' and r.target_id = p.id)), 0), random()
  limit 1
$$;

-- Demande aux amis Android qui n'ont pas donné signe de vie de se signaler (au plus toutes les 2 minutes chacun).
create or replace function public.c__ping_stale(p_from uuid)
returns void language plpgsql security definer set search_path = public as $$
declare ids uuid[];
begin
  select array_agg(p.id) into ids from public.c_friends f join public.c_profiles p on p.id = f.friend_id
    where f.user_id = p_from and p.fcm_token is not null and (p.last_seen is null or p.last_seen < now() - interval '90 seconds')
      and (p.last_ping is null or p.last_ping < now() - interval '2 minutes');
  if ids is null then return; end if;
  update public.c_profiles set last_ping = now() where id = any(ids);
  perform public.c__fcm(ids, 'ping');
end $$;

-- Présence : « dans Free tune » ou « sur internet » (appli fermée mais téléphone joignable).
create or replace function public.c_heartbeat(p_platform text, p_share boolean, p_in_app boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles; r record; n int := 0;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles set last_seen = now(), in_app = coalesce(p_in_app, true),
      platform = coalesce(nullif(p_platform, ''), platform),
      relay_share = coalesce(p_share, relay_share)
    where id = auth.uid() returning * into me;
  if me.id is null or me.username is null then return json_build_object('status', 'ok'); end if;
  if public.c__setting('auto_friends', false) then
    for r in
      select o.id from public.c_profiles o
      where o.id <> me.id and o.username is not null and o.last_seen > now() - interval '2 minutes'
        and public.c__platform(o) <> public.c__platform(me)
        and not exists (select 1 from public.c_friends f where f.user_id = me.id and f.friend_id = o.id)
      limit 50
    loop
      perform public.c__befriend(me.id, r.id);
      n := n + 1;
    end loop;
  end if;
  return json_build_object('status', 'ok', 'linked', n);
end $$;
-- Anciennes versions de l'appli : elles n'envoient que deux valeurs (considérées « dans Free tune »).
create or replace function public.c_heartbeat(p_platform text, p_share boolean)
returns json language sql security definer set search_path = public as $$
  select public.c_heartbeat(p_platform, p_share, true)
$$;

create or replace function public.c_relay_create(p_yid text, p_q text, p_ti text, p_ch text, p_d int, p_art text, p_fmt text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare me uuid := auth.uid(); n int; rid bigint; notified boolean; tgt uuid; ids uuid[];
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  select count(*) into n from public.c_friends f join public.c_profiles p on p.id = f.friend_id
    where f.user_id = me and p.fcm_token is not null and p.relay_share;
  if n = 0 then return json_build_object('status', 'no_helper'); end if;
  select id into rid from public.c_relay
    where from_id = me and status in ('open', 'taken') and fmt = coalesce(p_fmt, 'a')
      and ((p_yid is not null and p_yid <> '' and yid = p_yid) or (coalesce(p_yid, '') = '' and q = left(p_q, 120)))
    order by id desc limit 1;
  if rid is not null then return json_build_object('status', 'ok', 'id', rid, 'helpers', n); end if;
  if public.c__setting('random_dispatch', true) then tgt := public.c__pick_server(me, '{}'); end if;
  notified := exists (select 1 from public.c_relay where from_id = me and status in ('open', 'taken') and created_at > now() - interval '40 seconds' and target_id is null);
  insert into public.c_relay (from_id, yid, q, ti, ch, d, art, fmt, target_id, assigned_at)
    values (me, nullif(left(p_yid, 20), ''), left(p_q, 120), left(p_ti, 120), left(p_ch, 60), greatest(coalesce(p_d, 0), 0), left(p_art, 300), coalesce(nullif(left(p_fmt, 3), ''), 'a'),
            tgt, case when tgt is null then null else now() end)
    returning id into rid;
  if tgt is not null then
    perform public.c__fcm(array[tgt], 'relay');
  elsif not notified then
    select array_agg(p.id) into ids from public.c_friends f join public.c_profiles p on p.id = f.friend_id
      where f.user_id = me and p.fcm_token is not null and p.relay_share;
    perform public.c__fcm(ids, 'relay');
  end if;
  perform public.c__ping_stale(me);
  return json_build_object('status', 'ok', 'id', rid, 'helpers', n);
end $$;

create or replace function public.c_relay_open()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  if not coalesce((select relay_share from public.c_profiles where id = auth.uid()), true) then return '[]'::json; end if;
  return coalesce((
    select json_agg(x) from (
      select r.id, r.yid, r.q, r.ti, r.ch, r.d, r.art, r.fmt
      from public.c_relay r
      where r.from_id in (select friend_id from public.c_friends where user_id = auth.uid())
        and r.created_at > now() - interval '1 day'
        and not (auth.uid() = any (r.skip))
        and (r.target_id is null or r.target_id = auth.uid() or r.assigned_at < now() - interval '30 seconds')
        and (r.status = 'open' or (r.status = 'taken' and r.taken_at < now() - interval '4 minutes' and r.beat_at is null))
      order by r.id limit 8
    ) x), '[]'::json);
end $$;

create or replace function public.c_relay_take(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not coalesce((select relay_share from public.c_profiles where id = auth.uid()), true) then return json_build_object('status', 'gone'); end if;
  update public.c_relay set status = 'taken', taker_id = auth.uid(), taken_at = now(), tries = tries + 1, beat_at = null, lost_at = null
    where id = p_id and from_id in (select friend_id from public.c_friends where user_id = auth.uid())
      and not (auth.uid() = any (skip))
      and (target_id is null or target_id = auth.uid() or assigned_at < now() - interval '30 seconds')
      and (status = 'open' or (status = 'taken' and taken_at < now() - interval '4 minutes' and beat_at is null));
  get diagnostics k = row_count;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;

-- Le téléphone qui télécharge se signale toutes les ~1,5 seconde.
create or replace function public.c_relay_beat(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay set beat_at = now() where id = p_id and taker_id = auth.uid() and status = 'taken';
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_relay_progress(p_id bigint, p_prog int)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay set prog = greatest(prog, least(greatest(coalesce(p_prog, 0), 0), 100)),
      beat_at = case when beat_at is not null then now() else beat_at end
    where id = p_id and taker_id = auth.uid() and status = 'taken';
  return json_build_object('status', 'ok');
end $$;

-- L'iPhone suit ses demandes. Si le téléphone qui télécharge se tait plus de 6 secondes (ou si le serveur choisi ne répond pas),
-- la demande est confiée à un autre serveur.
create or replace function public.c_relay_status(p_ids bigint[])
returns json language plpgsql security definer set search_path = public as $$
declare w record; nt uuid; wake uuid[];
begin
  if auth.uid() is null then return '[]'::json; end if;
  for w in
    select * from public.c_relay
    where id = any(p_ids) and from_id = auth.uid()
      and ((status = 'taken' and beat_at is not null and beat_at < now() - interval '6 seconds')
        or (status = 'open' and target_id is not null and assigned_at < now() - interval '15 seconds'))
    for update
  loop
    if w.tries >= 4 then
      update public.c_relay set status = 'fail' where id = w.id;
      continue;
    end if;
    nt := public.c__pick_server(w.from_id, array_append(w.skip, coalesce(w.taker_id, w.target_id)));
    update public.c_relay set status = 'open', taker_id = null, taken_at = null, beat_at = null, prog = 0,
        skip = array_append(skip, coalesce(w.taker_id, w.target_id)), target_id = nt, assigned_at = now(),
        lost_at = case when w.status = 'taken' then now() else lost_at end
      where id = w.id;
    if nt is not null then
      wake := array[nt];
    else
      select array_agg(p.id) into wake from public.c_friends f join public.c_profiles p on p.id = f.friend_id
        where f.user_id = w.from_id and p.fcm_token is not null and p.relay_share and p.id <> all (array_append(w.skip, coalesce(w.taker_id, w.target_id)));
    end if;
    perform public.c__fcm(wake, 'relay');
  end loop;
  return coalesce((
    select json_agg(json_build_object('id', r.id, 'status', r.status, 'media', r.media, 'size', r.size, 'prog', r.prog, 'tries', r.tries,
        'lost', (r.status = 'open' and r.lost_at is not null),
        'quiet', case when r.status = 'taken' and r.beat_at is not null then floor(extract(epoch from now() - r.beat_at))::int else 0 end,
        'by', (select username from public.c_profiles where id = r.taker_id)))
    from public.c_relay r where r.id = any(p_ids) and r.from_id = auth.uid()), '[]'::json);
end $$;

-- Admin : états, charge en cours, et demande de signal aux Android silencieux.
create or replace function public.admin_ft_clients()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return json_build_object(
    'settings', json_build_object('auto_friends', public.c__setting('auto_friends', false), 'random_dispatch', public.c__setting('random_dispatch', true)),
    'users', coalesce((
      select json_agg(json_build_object(
        'id', p.id, 'username', p.username, 'email', u.email, 'platform', public.c__platform(p),
        'state', public.c__avail(p), 'online', public.c__avail(p) <> 'off', 'last_seen', p.last_seen,
        'share', p.relay_share,
        'jobs', (select count(*) from public.c_relay r where (r.status = 'taken' and r.taker_id = p.id) or (r.status = 'open' and r.target_id = p.id)),
        'friends', coalesce((select json_agg(f.friend_id) from public.c_friends f where f.user_id = p.id), '[]'::json)
      ) order by lower(p.username))
      from public.c_profiles p left join auth.users u on u.id = p.id where p.username is not null), '[]'::json));
end $$;

create or replace function public.admin_ft_ping()
returns int language plpgsql security definer set search_path = public as $$
declare ids uuid[];
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  select array_agg(p.id) into ids from public.c_profiles p
    where p.fcm_token is not null and (p.last_seen is null or p.last_seen < now() - interval '90 seconds')
      and (p.last_ping is null or p.last_ping < now() - interval '2 minutes');
  if ids is null then return 0; end if;
  update public.c_profiles set last_ping = now() where id = any(ids);
  perform public.c__fcm(ids, 'ping');
  return cardinality(ids);
end $$;

revoke all on function public.c__fcm(uuid[], text), public.c__pick_server(uuid, uuid[]), public.c__ping_stale(uuid),
  public.c_heartbeat(text, boolean, boolean), public.c_heartbeat(text, boolean), public.c_relay_create(text, text, text, text, int, text, text),
  public.c_relay_open(), public.c_relay_take(bigint), public.c_relay_beat(bigint), public.c_relay_progress(bigint, int), public.c_relay_status(bigint[]),
  public.admin_ft_clients(), public.admin_ft_ping() from public, anon;
grant execute on function public.c_heartbeat(text, boolean, boolean), public.c_heartbeat(text, boolean), public.c_relay_create(text, text, text, text, int, text, text),
  public.c_relay_open(), public.c_relay_take(bigint), public.c_relay_beat(bigint), public.c_relay_progress(bigint, int), public.c_relay_status(bigint[]),
  public.admin_ft_clients(), public.admin_ft_ping() to authenticated;
