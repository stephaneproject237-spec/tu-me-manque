-- Free tune pour Windows et Mac : comptes « serveur » (pseudo + code, sans Google) qui servent les iPhone comme des Android.
-- Supabase > SQL Editor > + New query > collez tout > Run. Peut être relancé sans danger.

-- Clé d'activation : à saisir dans le logiciel Windows (Réglages) pour pouvoir créer des comptes serveur.
create table if not exists public.c_srv_cfg (key text primary key, val text not null);
alter table public.c_srv_cfg enable row level security;
revoke all on public.c_srv_cfg from anon, authenticated;
insert into public.c_srv_cfg (key, val) values ('enroll_key', 'd9fb3bqmnufwnqkmi1qc') on conflict (key) do nothing;

-- Création d'un compte serveur (appelable sans être connecté, mais seulement avec la clé d'activation).
drop function if exists public.srv_register(text, text, text);
create or replace function public.srv_register(p_key text, p_username text, p_code text, p_platform text default 'windows')
returns json language plpgsql security definer set search_path = public, auth, extensions as $$
declare n text := lower(btrim(coalesce(p_username, ''))); uid uuid := gen_random_uuid(); em text; k text;
begin
  select val into k from public.c_srv_cfg where key = 'enroll_key';
  if k is null or p_key is distinct from k then return json_build_object('status', 'bad_key'); end if;
  if not public._rate_ok('srv_register', 60, 600) then return json_build_object('status', 'rate_limited'); end if;
  if n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  if length(coalesce(p_code, '')) < 8 or length(p_code) > 72 then return json_build_object('status', 'bad_code'); end if;
  if coalesce(p_platform, '') not in ('windows', 'mac') then p_platform := 'windows'; end if;
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
  em := n || '@srv.freetune.app';
  insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at, raw_app_meta_data, raw_user_meta_data,
                          created_at, updated_at, confirmation_token, email_change, email_change_token_new, recovery_token)
    values ('00000000-0000-0000-0000-000000000000', uid, 'authenticated', 'authenticated', em, crypt(p_code, gen_salt('bf')), now(),
            '{"provider":"email","providers":["email"]}'::jsonb, '{}'::jsonb, now(), now(), '', '', '', '');
  begin
    update auth.users set email_change_token_current = '', phone_change = '', phone_change_token = '', reauthentication_token = '' where id = uid;
  exception when undefined_column then null; end;
  insert into auth.identities (id, user_id, provider_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
    values (gen_random_uuid(), uid, uid::text, jsonb_build_object('sub', uid::text, 'email', em), 'email', now(), now(), now());
  insert into public.c_profiles (id, username, platform, relay_share) values (uid, n, p_platform, true);
  return json_build_object('status', 'ok', 'email', em);
end $$;
revoke all on function public.srv_register(text, text, text, text) from public;
grant execute on function public.srv_register(text, text, text, text) to anon, authenticated;

-- Les comptes Windows peuvent servir les iPhone (ils n'ont pas de notification Google : ils interrogent le serveur toutes les quelques secondes).
create or replace function public.c__helpers(p_from uuid)
returns setof uuid language sql stable security definer set search_path = public as $$
  select p.id from public.c_profiles p
  where p.id <> p_from and p.relay_share
    and ((p.fcm_token is not null and public.c__platform(p) = 'android') or public.c__platform(p) in ('windows', 'mac'))
    and (public.c__setting('random_dispatch', true)
         or exists (select 1 from public.c_friends f where f.user_id = p_from and f.friend_id = p.id))
$$;
revoke all on function public.c__helpers(uuid) from public, anon;
