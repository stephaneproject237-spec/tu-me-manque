-- Admin > Clients Free tune : nombre de téléchargements effectués par chaque compte.
-- À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.
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
        'dl', greatest((select count(*) from public.c_media m where m.user_id = p.id), (select count(*) from public.c_relay r where r.from_id = p.id and r.status = 'done')),
        'dl_helped', (select count(*) from public.c_relay r where r.taker_id = p.id and r.status = 'done'),
        'jobs', (select count(*) from public.c_relay r where (r.status = 'taken' and r.taker_id = p.id) or (r.status = 'open' and r.target_id = p.id)),
        'friends', coalesce((select json_agg(f.friend_id) from public.c_friends f where f.user_id = p.id), '[]'::json)
      ) order by lower(p.username))
      from public.c_profiles p left join auth.users u on u.id = p.id where p.username is not null), '[]'::json));
end $$;
