-- Free tune : l'activation / désactivation de l'utilisation d'un serveur (« aider les iPhone ») se fait désormais
-- UNIQUEMENT depuis l'appli Admin. Les applis (Android, Windows, Mac, Free tune server) ne peuvent plus le changer
-- elles-mêmes : leur « battement » n'écrase plus ce réglage.
-- À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.

-- Le battement ne touche plus relay_share (seul un administrateur peut le changer, via admin_ft_share ci-dessous).
create or replace function public.c__heartbeat(p_platform text, p_share boolean, p_in_app boolean, p_ping_ok boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles; r record; n int := 0;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles set last_seen = now(), in_app = coalesce(p_in_app, true),
      platform = coalesce(nullif(p_platform, ''), platform),
      ping_ok = case when p_ping_ok and coalesce(nullif(p_platform, ''), platform, '') = 'android' then true else ping_ok end
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

-- Active ou désactive l'utilisation d'un serveur (Android, Windows, Mac, Free tune server) depuis l'appli Admin.
create or replace function public.admin_ft_share(p_id uuid, p_on boolean)
returns json language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  update public.c_profiles set relay_share = coalesce(p_on, true) where id = p_id;
  return json_build_object('status', 'ok');
end $$;
revoke all on function public.admin_ft_share(uuid, boolean) from public, anon;
grant execute on function public.admin_ft_share(uuid, boolean) to authenticated;
