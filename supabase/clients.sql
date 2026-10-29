-- Free tune : « Clients Free tune » dans l'appli Admin (comptes iPhone / Android, présence en ligne, partage, amitiés),
-- amis automatiques et répartition au hasard des demandes de l'iPhone vers un Android en ligne.
-- À lancer une seule fois, APRÈS le code « relais » (peut être relancé sans risque).

-- ===== Colonnes =====
alter table public.c_profiles add column if not exists last_seen timestamptz;
alter table public.c_profiles add column if not exists platform text;                       -- 'android' ou 'ios'
alter table public.c_profiles add column if not exists relay_share boolean not null default true; -- accepte d'envoyer des chansons aux iPhone
alter table public.c_relay add column if not exists target_id uuid references public.c_profiles(id) on delete set null;
alter table public.c_relay add column if not exists assigned_at timestamptz;

-- ===== Réglages de l'administrateur =====
create table if not exists public.c_settings (key text primary key, value jsonb not null);
alter table public.c_settings enable row level security;
revoke all on public.c_settings from anon, authenticated;

create or replace function public.c__setting(p_key text, p_default boolean)
returns boolean language sql stable security definer set search_path = public as $$
  select coalesce((select (value)::text::boolean from public.c_settings where key = p_key), p_default)
$$;

-- Plateforme d'un compte : celle annoncée, sinon Android s'il a un jeton FCM, sinon iPhone.
create or replace function public.c__platform(p public.c_profiles)
returns text language sql immutable as $$
  select coalesce(p.platform, case when p.fcm_token is not null then 'android' else 'ios' end)
$$;

-- ===== Amis automatiques : tous les comptes Android en ligne <-> tous les iPhone en ligne =====
create or replace function public.c__auto_friend_all()
returns int language plpgsql security definer set search_path = public as $$
declare n int := 0; r record;
begin
  for r in
    select a.id as aid, b.id as bid
    from public.c_profiles a join public.c_profiles b on public.c__platform(a) = 'android' and public.c__platform(b) = 'ios'
    where a.username is not null and b.username is not null
      and a.last_seen > now() - interval '2 minutes' and b.last_seen > now() - interval '2 minutes'
      and not exists (select 1 from public.c_friends f where f.user_id = a.id and f.friend_id = b.id)
    limit 500
  loop
    perform public.c__befriend(r.aid, r.bid);
    n := n + 1;
  end loop;
  return n;
end $$;

-- ===== Présence : l'appli (Android ou iPhone) se signale toutes les ~40 secondes =====
create or replace function public.c_heartbeat(p_platform text, p_share boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles; r record; n int := 0;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles set last_seen = now(),
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

-- ===== Relais : chaque demande va au hasard vers UN Android en ligne qui accepte de partager =====
create or replace function public.c_relay_create(p_yid text, p_q text, p_ti text, p_ch text, p_d int, p_art text, p_fmt text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare me uuid := auth.uid(); n int; rid bigint; notified boolean; nurl text; nsecret text; tgt uuid;
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
  if public.c__setting('random_dispatch', true) then
    select p.id into tgt from public.c_friends f join public.c_profiles p on p.id = f.friend_id
      where f.user_id = me and p.fcm_token is not null and p.relay_share and p.last_seen > now() - interval '2 minutes'
      order by random() limit 1;
  end if;
  notified := exists (select 1 from public.c_relay where from_id = me and status in ('open', 'taken') and created_at > now() - interval '40 seconds'
                        and target_id is not distinct from tgt);
  insert into public.c_relay (from_id, yid, q, ti, ch, d, art, fmt, target_id, assigned_at)
    values (me, nullif(left(p_yid, 20), ''), left(p_q, 120), left(p_ti, 120), left(p_ch, 60), greatest(coalesce(p_d, 0), 0), left(p_art, 300), coalesce(nullif(left(p_fmt, 3), ''), 'a'),
            tgt, case when tgt is null then null else now() end)
    returning id into rid;
  -- Notification silencieuse (réveille un téléphone Android même si Free tune est fermé).
  if not notified then
    select (regexp_match(prosrc, $re$url := '([^']+)'$re$))[1], (regexp_match(prosrc, $re$x-webhook-secret', '([^']+)'$re$))[1]
      into nurl, nsecret from pg_proc where proname = 'c_push_on_notification' limit 1;
    if nurl is not null and nsecret is not null then
      perform net.http_post(
        url := nurl,
        headers := jsonb_build_object('Content-Type', 'application/json', 'x-webhook-secret', nsecret),
        body := jsonb_build_object('token', p.fcm_token, 'silent', true, 'data', jsonb_build_object('type', 'relay'))
      ) from public.c_friends f join public.c_profiles p on p.id = f.friend_id
        where f.user_id = me and p.fcm_token is not null and p.relay_share and (tgt is null or p.id = tgt);
    end if;
  end if;
  return json_build_object('status', 'ok', 'id', rid, 'helpers', n);
end $$;

-- L'Android regarde ce qu'on lui a confié (ou, après 30 secondes sans réponse, ce que les autres amis attendent).
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
        and (r.target_id is null or r.target_id = auth.uid() or r.assigned_at < now() - interval '30 seconds')
        and (r.status = 'open' or (r.status = 'taken' and r.taken_at < now() - interval '4 minutes'))
      order by r.id limit 8
    ) x), '[]'::json);
end $$;

create or replace function public.c_relay_take(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not coalesce((select relay_share from public.c_profiles where id = auth.uid()), true) then return json_build_object('status', 'gone'); end if;
  update public.c_relay set status = 'taken', taker_id = auth.uid(), taken_at = now(), tries = tries + 1
    where id = p_id and from_id in (select friend_id from public.c_friends where user_id = auth.uid())
      and (target_id is null or target_id = auth.uid() or assigned_at < now() - interval '30 seconds')
      and (status = 'open' or (status = 'taken' and taken_at < now() - interval '4 minutes'));
  get diagnostics k = row_count;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;

-- ===== Fonctions réservées à l'administrateur =====
create or replace function public.admin_ft_clients()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return json_build_object(
    'settings', json_build_object('auto_friends', public.c__setting('auto_friends', false), 'random_dispatch', public.c__setting('random_dispatch', true)),
    'users', coalesce((
      select json_agg(json_build_object(
        'id', p.id, 'username', p.username, 'email', u.email, 'platform', public.c__platform(p),
        'online', coalesce(p.last_seen > now() - interval '2 minutes', false), 'last_seen', p.last_seen,
        'share', p.relay_share,
        'friends', coalesce((select json_agg(f.friend_id) from public.c_friends f where f.user_id = p.id), '[]'::json)
      ) order by lower(p.username))
      from public.c_profiles p left join auth.users u on u.id = p.id where p.username is not null), '[]'::json));
end $$;

create or replace function public.admin_ft_link(p_a uuid[], p_b uuid[])
returns int language plpgsql security definer set search_path = public as $$
declare x uuid; y uuid; n int := 0;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if coalesce(array_length(p_a, 1), 0) * coalesce(array_length(p_b, 1), 0) > 1000 then raise exception 'trop de liens'; end if;
  foreach x in array coalesce(p_a, '{}') loop
    foreach y in array coalesce(p_b, '{}') loop
      if x <> y and exists (select 1 from public.c_profiles where id = x) and exists (select 1 from public.c_profiles where id = y) then
        perform public.c__befriend(x, y); n := n + 1;
      end if;
    end loop;
  end loop;
  return n;
end $$;

-- Retire des amitiés (les conversations restent : elles reviennent si les deux comptes sont reliés de nouveau).
create or replace function public.admin_ft_unlink(p_a uuid[], p_b uuid[])
returns int language plpgsql security definer set search_path = public as $$
declare x uuid; y uuid; n int := 0;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  foreach x in array coalesce(p_a, '{}') loop
    foreach y in array coalesce(p_b, '{}') loop
      delete from public.c_friends where (user_id = x and friend_id = y) or (user_id = y and friend_id = x);
      update public.c_profiles set partner_id = null, partner_name = null, partner_phone = null where id = x and partner_id = y;
      update public.c_profiles set partner_id = null, partner_name = null, partner_phone = null where id = y and partner_id = x;
      n := n + 1;
    end loop;
  end loop;
  return n;
end $$;

create or replace function public.admin_ft_setting(p_key text, p_val boolean)
returns json language plpgsql security definer set search_path = public as $$
declare n int := 0;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if p_key not in ('auto_friends', 'random_dispatch') then raise exception 'invalid'; end if;
  insert into public.c_settings (key, value) values (p_key, to_jsonb(coalesce(p_val, false)))
    on conflict (key) do update set value = excluded.value;
  if p_key = 'auto_friends' and p_val then n := public.c__auto_friend_all(); end if;
  return json_build_object('status', 'ok', 'linked', n);
end $$;

revoke all on function public.c__setting(text, boolean), public.c__auto_friend_all(), public.c_heartbeat(text, boolean),
  public.c_relay_create(text, text, text, text, int, text, text), public.c_relay_open(), public.c_relay_take(bigint),
  public.admin_ft_clients(), public.admin_ft_link(uuid[], uuid[]), public.admin_ft_unlink(uuid[], uuid[]), public.admin_ft_setting(text, boolean)
  from public, anon;
grant execute on function public.c_heartbeat(text, boolean), public.c_relay_create(text, text, text, text, int, text, text), public.c_relay_open(), public.c_relay_take(bigint),
  public.admin_ft_clients(), public.admin_ft_link(uuid[], uuid[]), public.admin_ft_unlink(uuid[], uuid[]), public.admin_ft_setting(text, boolean) to authenticated;
