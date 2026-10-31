-- Free tune pour Mac, Windows et Android « Free tune server » : comptes personnels (identifiant + mot de passe),
-- historique des téléchargements, une seule machine à la fois, abonnement (mêmes règles que sur Android),
-- et création automatique des 6 serveurs de chaque logiciel (plus de clé d'activation).
-- Supabase > SQL Editor > + New query > collez tout > Run. Peut être relancé sans danger.

-- ---------------------------------------------------------------- tables
-- Un serveur automatique appartient au compte personnel qui l'utilise (visible dans l'application Admin).
alter table public.c_profiles add column if not exists owner_id uuid references auth.users(id) on delete set null;

create table if not exists public.c_desk_dl (
  id bigserial primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  yid text not null default '',
  title text not null default '',
  artist text not null default '',
  kind text not null default 'audio',          -- audio | video
  state text not null default 'pending',       -- pending | done
  created_at timestamptz not null default now(),
  done_at timestamptz
);
create index if not exists c_desk_dl_user on public.c_desk_dl (user_id, created_at desc);
alter table public.c_desk_dl enable row level security;
revoke all on public.c_desk_dl from anon, authenticated;

create table if not exists public.c_desk_session (
  user_id uuid primary key references auth.users(id) on delete cascade,
  device text not null,
  platform text,
  beat_at timestamptz not null default now()
);
alter table public.c_desk_session enable row level security;
revoke all on public.c_desk_session from anon, authenticated;

create table if not exists public.c_srv_device (
  device text primary key,
  n int not null default 0,
  created_at timestamptz not null default now()
);
alter table public.c_srv_device enable row level security;
revoke all on public.c_srv_device from anon, authenticated;

-- ---------------------------------------------------------------- création de comptes (sans être connecté)
create or replace function public._make_auth_user(p_name text, p_password text, p_domain text, p_platform text, p_share boolean)
returns uuid language plpgsql security definer set search_path = public, auth, extensions as $$
declare uid uuid := gen_random_uuid(); em text := p_name || '@' || p_domain;
begin
  insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data,
                          created_at, updated_at, confirmation_token, email_change, email_change_token_new, recovery_token)
    values ('00000000-0000-0000-0000-000000000000', uid, 'authenticated', 'authenticated', em, crypt(p_password, gen_salt('bf')), now(),
            '{"provider":"email","providers":["email"]}'::jsonb, '{}'::jsonb, now(), now(), '', '', '', '');
  begin
    update auth.users set email_change_token_current = '', phone_change = '', phone_change_token = '', reauthentication_token = '' where id = uid;
  exception when undefined_column then null; end;
  insert into auth.identities (id, user_id, provider_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
    values (gen_random_uuid(), uid, uid::text, jsonb_build_object('sub', uid::text, 'email', em), 'email', now(), now(), now());
  insert into public.c_profiles (id, username, platform, relay_share) values (uid, p_name, p_platform, p_share);
  return uid;
end $$;
revoke all on function public._make_auth_user(text, text, text, text, boolean) from public, anon, authenticated;

-- Compte personnel : l'identifiant et le mot de passe choisis par la personne.
-- p_app : pcwin (logiciel Windows), pcmac (logiciel Mac) ou pcsrv (application Android « Free tune server »). p_sponsor : pseudo du parrain (facultatif).
create or replace function public.usr_register(p_username text, p_password text, p_app text default 'pcwin', p_sponsor text default null)
returns json language plpgsql security definer set search_path = public, auth, extensions as $$
declare n text := lower(btrim(coalesce(p_username, ''))); uid uuid; sid uuid; app text := case when p_app in ('pcwin', 'pcmac', 'pcsrv') then p_app else 'pcwin' end;
begin
  if not public._rate_ok('usr_register', 200, 3600) then return json_build_object('status', 'rate_limited'); end if;
  if n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  if length(coalesce(p_password, '')) < 6 or length(p_password) > 72 then return json_build_object('status', 'bad_code'); end if;
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
  uid := public._make_auth_user(n, p_password, 'pc.freetune.app', app, false);
  insert into public.c_subs (user_id) values (uid) on conflict (user_id) do nothing;
  select id into sid from public.c_profiles
    where lower(username) = lower(regexp_replace(coalesce(p_sponsor, ''), '^[@\s]+|\s+$', '', 'g')) and id <> uid limit 1;
  if sid is not null then update public.c_profiles set sponsor_id = sid where id = uid; end if;
  return json_build_object('status', 'ok', 'email', n || '@pc.freetune.app', 'sponsor', case when sid is null then null else (select username from public.c_profiles where id = sid) end);
end $$;

-- Serveur automatique : chaque logiciel en crée 6 au premier lancement (30 au maximum par installation, 6 créés automatiquement).
create or replace function public.srv_auto(p_device text, p_username text, p_code text, p_platform text default 'windows')
returns json language plpgsql security definer set search_path = public, auth, extensions as $$
declare n text := lower(btrim(coalesce(p_username, ''))); k int;
begin
  if length(coalesce(p_device, '')) < 16 or length(p_device) > 80 then return json_build_object('status', 'bad_request'); end if;
  if not public._rate_ok('srv_auto', 900, 3600) then return json_build_object('status', 'rate_limited'); end if;
  if n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  if length(coalesce(p_code, '')) < 8 or length(p_code) > 72 then return json_build_object('status', 'bad_code'); end if;
  if coalesce(p_platform, '') not in ('windows', 'mac', 'srvdroid') then p_platform := 'windows'; end if;
  insert into public.c_srv_device (device, n) values (p_device, 0) on conflict (device) do nothing;
  select c.n into k from public.c_srv_device c where c.device = p_device for update;
  if k >= 30 then return json_build_object('status', 'limit'); end if;
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
  perform public._make_auth_user(n, p_code, 'srv.freetune.app', p_platform, true);
  update public.c_srv_device c set n = c.n + 1 where c.device = p_device;
  return json_build_object('status', 'ok', 'email', n || '@srv.freetune.app');
end $$;
-- Le serveur Android « Free tune server » n'a pas de notification Google : il interroge le serveur comme Windows et Mac.
create or replace function public.c__helpers(p_from uuid)
returns setof uuid language sql stable security definer set search_path = public as $$
  select p.id from public.c_profiles p
  where p.id <> p_from and p.relay_share
    and ((p.fcm_token is not null and public.c__platform(p) = 'android') or public.c__platform(p) in ('windows', 'mac', 'srvdroid'))
    and (public.c__setting('random_dispatch', true)
         or exists (select 1 from public.c_friends f where f.user_id = p_from and f.friend_id = p.id))
$$;
revoke all on function public.c__helpers(uuid) from public, anon;

-- ---------------------------------------------------------------- une seule machine à la fois
-- Appelée à la connexion puis toutes les 25 secondes. « busy » : le compte est utilisé ailleurs.
create or replace function public.c_desk_claim(p_dev text, p_platform text default null)
returns json language plpgsql security definer set search_path = public as $$
declare s public.c_desk_session;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if length(coalesce(p_dev, '')) < 8 or length(p_dev) > 80 then return json_build_object('status', 'bad_request'); end if;
  select * into s from public.c_desk_session where user_id = auth.uid() for update;
  if found and s.device <> p_dev and s.beat_at > now() - interval '75 seconds' then
    return json_build_object('status', 'busy', 'platform', s.platform);
  end if;
  insert into public.c_desk_session (user_id, device, platform, beat_at) values (auth.uid(), p_dev, left(p_platform, 12), now())
    on conflict (user_id) do update set device = excluded.device, platform = excluded.platform, beat_at = now();
  return json_build_object('status', 'ok');
end $$;
create or replace function public.c_desk_release(p_dev text)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  delete from public.c_desk_session where user_id = auth.uid() and device = p_dev;
  return json_build_object('status', 'ok');
end $$;

-- ---------------------------------------------------------------- téléchargements : abonnement et historique
-- Début d'un téléchargement : abonné, ou essai (24 h, 3 téléchargements TERMINÉS). Sinon « pay ».
-- p_restore : retéléchargement d'un ancien fichier (permis tant que l'essai ou l'abonnement est valable, ne compte pas dans l'essai).
create or replace function public.c_desk_begin(p_yid text, p_title text, p_artist text, p_kind text, p_restore boolean default false)
returns json language plpgsql security definer set search_path = public as $$
declare sb public.c_subs; inflight int; rid bigint; k text := case when p_kind = 'video' then 'video' else 'audio' end;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not public._rate_ok('desk:' || auth.uid()::text, 120, 60) then return json_build_object('status', 'rate_limited'); end if;
  if not public.is_admin() then
    insert into public.c_subs (user_id) values (auth.uid()) on conflict (user_id) do nothing;
    select * into sb from public.c_subs where user_id = auth.uid();
    if not (sb.paid_until is not null and sb.paid_until > now()) then
      select count(*) into inflight from public.c_desk_dl where user_id = auth.uid() and state = 'pending' and created_at > now() - interval '30 minutes';
      if p_restore then
        if not (now() < sb.trial_start + interval '1 day' and sb.trial_dl < 3) then return json_build_object('status', 'pay'); end if;
      elsif not (now() < sb.trial_start + interval '1 day' and sb.trial_dl + inflight < 3) then
        return json_build_object('status', 'pay');
      end if;
    end if;
  end if;
  if p_restore then return json_build_object('status', 'ok', 'id', null); end if;
  insert into public.c_desk_dl (user_id, yid, title, artist, kind)
    values (auth.uid(), case when coalesce(p_yid, '') ~ '^[A-Za-z0-9_-]{11}$' then p_yid else '' end,
            left(regexp_replace(coalesce(p_title, ''), '[[:cntrl:]]', ' ', 'g'), 150), left(regexp_replace(coalesce(p_artist, ''), '[[:cntrl:]]', ' ', 'g'), 100), k)
    returning id into rid;
  return json_build_object('status', 'ok', 'id', rid);
end $$;

-- Fichier bien reçu : il entre dans l'historique et (pendant l'essai) dans le décompte des 3 téléchargements.
create or replace function public.c_desk_done(p_id bigint, p_yid text default null)
returns json language plpgsql security definer set search_path = public as $$
declare d public.c_desk_dl;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_desk_dl set state = 'done', done_at = now(),
      yid = case when coalesce(p_yid, '') ~ '^[A-Za-z0-9_-]{11}$' then p_yid else yid end
    where id = p_id and user_id = auth.uid() and state = 'pending' returning * into d;
  if not found then return json_build_object('status', 'gone'); end if;
  update public.c_subs set trial_dl = least(trial_dl + 1, 3), updated_at = now()
    where user_id = auth.uid() and not (paid_until is not null and paid_until > now()) and now() < trial_start + interval '1 day';
  return json_build_object('status', 'ok');
end $$;
create or replace function public.c_desk_fail(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  delete from public.c_desk_dl where id = p_id and user_id = auth.uid() and state = 'pending';
  return json_build_object('status', 'ok');
end $$;

-- Historique du compte (le plus récent d'abord, sans doublon).
create or replace function public.c_desk_list()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((
    select json_agg(json_build_object('id', x.id, 'yid', x.yid, 'title', x.title, 'artist', x.artist, 'kind', x.kind, 'at', x.done_at) order by x.done_at desc)
    from (select * from (select distinct on (lower(artist), lower(title), kind) * from public.c_desk_dl
                         where user_id = auth.uid() and state = 'done' and (yid <> '' or title <> '')
                         order by lower(artist), lower(title), kind, done_at desc) d
          order by done_at desc limit 500) x), '[]'::json);
end $$;

-- Ce compte personnel prend possession de ses serveurs (pour que l'application Admin les rattache à leur propriétaire).
create or replace function public.c_desk_own(p_names text[])
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles set owner_id = auth.uid()
    where username = any (select lower(x) from unnest((coalesce(p_names, '{}'::text[]))[1:40]) x)
      and platform in ('windows', 'mac', 'srvdroid') and owner_id is null;
  get diagnostics k = row_count;
  return json_build_object('status', 'ok', 'n', k);
end $$;

-- ---------------------------------------------------------------- application Admin : types de clients, parrain, propriétaire des serveurs
create or replace function public.admin_ft_clients()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return json_build_object(
    'settings', json_build_object('auto_friends', public.c__setting('auto_friends', false), 'random_dispatch', public.c__setting('random_dispatch', true), 'p2p', public.c__setting('p2p', false)),
    'users', coalesce((
      select json_agg(json_build_object(
        'id', p.id, 'username', p.username, 'email', u.email, 'platform', public.c__platform(p),
        'state', public.c__avail(p), 'online', public.c__avail(p) <> 'off', 'last_seen', p.last_seen,
        'share', p.relay_share, 'app_ver', p.app_ver,
        'sponsor', (select s.username from public.c_profiles s where s.id = p.sponsor_id),
        'owner', (select o.username from public.c_profiles o where o.id = p.owner_id),
        'dl', greatest((select count(*) from public.c_media m where m.user_id = p.id), (select count(*) from public.c_relay r where r.from_id = p.id and r.status = 'done'),
                       (select count(*) from public.c_desk_dl d where d.user_id = p.id and d.state = 'done')),
        'dl_helped', (select count(*) from public.c_relay r where r.taker_id = p.id and r.status = 'done'),
        'jobs', (select count(*) from public.c_relay r where (r.status = 'taken' and r.taker_id = p.id) or (r.status = 'open' and r.target_id = p.id))
                 + (select count(distinct k.relay_id) from public.c_relay_chunk k join public.c_relay r on r.id = k.relay_id
                    where k.taker_id = p.id and k.status = 'taken' and k.beat_at > now() - interval '15 seconds' and r.status in ('open', 'taken') and not (r.status = 'taken' and r.taker_id = p.id)),
        'friends', coalesce((select json_agg(f.friend_id) from public.c_friends f where f.user_id = p.id), '[]'::json)
      ) order by lower(p.username))
      from public.c_profiles p left join auth.users u on u.id = p.id where p.username is not null), '[]'::json));
end $$;

revoke all on function public.usr_register(text, text, text, text), public.srv_auto(text, text, text, text) from public;
grant execute on function public.usr_register(text, text, text, text), public.srv_auto(text, text, text, text) to anon, authenticated;
revoke all on function public.c_desk_own(text[]), public.admin_ft_clients() from public, anon;
grant execute on function public.c_desk_own(text[]), public.admin_ft_clients() to authenticated;
drop function if exists public.usr_register(text, text);
revoke all on function public.c_desk_claim(text, text), public.c_desk_release(text), public.c_desk_begin(text, text, text, text, boolean),
  public.c_desk_done(bigint, text), public.c_desk_fail(bigint), public.c_desk_list() from public, anon;
grant execute on function public.c_desk_claim(text, text), public.c_desk_release(text), public.c_desk_begin(text, text, text, text, boolean),
  public.c_desk_done(bigint, text), public.c_desk_fail(bigint), public.c_desk_list() to authenticated;
