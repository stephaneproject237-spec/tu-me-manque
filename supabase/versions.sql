-- Version de Free tune de chaque compte (affichée dans l'application Admin).
-- À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.
alter table public.c_profiles add column if not exists app_ver text;

create or replace function public.c_set_version(p_ver text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return; end if;
  update public.c_profiles set app_ver = left(btrim(coalesce(p_ver, '')), 24)
    where id = auth.uid() and app_ver is distinct from left(btrim(coalesce(p_ver, '')), 24);
end $$;
revoke all on function public.c_set_version(text) from public, anon;
grant execute on function public.c_set_version(text) to authenticated, service_role;

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
        'share', p.relay_share, 'app_ver', p.app_ver,
        'jobs', (select count(*) from public.c_relay r where (r.status = 'taken' and r.taker_id = p.id) or (r.status = 'open' and r.target_id = p.id)),
        'friends', coalesce((select json_agg(f.friend_id) from public.c_friends f where f.user_id = p.id), '[]'::json)
      ) order by lower(p.username))
      from public.c_profiles p left join auth.users u on u.id = p.id where p.username is not null), '[]'::json));
end $$;
