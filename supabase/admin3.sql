-- 1
alter table public.profiles add column if not exists last_seen timestamptz;

-- 2
create or replace function public.partner_typing()
returns json language plpgsql security definer set search_path = public as $$
declare t boolean;
begin
  if auth.uid() is null then return json_build_object('typing', false); end if;
  update public.profiles set last_seen = now()
    where id = auth.uid() and (last_seen is null or last_seen < now() - interval '20 seconds');
  select exists (
    select 1 from public.profiles me
    join public.typing ty on ty.user_id = me.partner_id
    where me.id = auth.uid() and ty.until_at > now()
  ) into t;
  return json_build_object('typing', coalesce(t, false));
end $$;

-- 3
create or replace function public.admin_users()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return coalesce((
    select json_agg(json_build_object(
      'id', p.id, 'username', p.username, 'email', u.email, 'phone', p.phone, 'age', p.age,
      'device', p.device, 'provider', u.raw_app_meta_data ->> 'provider',
      'created_at', u.created_at, 'last_sign_in_at', greatest(p.last_seen, u.last_sign_in_at),
      'partner_id', p.partner_id, 'partner_name', p.partner_name, 'linked_at', p.linked_at,
      'unread', (select count(*) from public.support_messages s where s.user_id = p.id and not s.from_admin and not s.admin_read)
    ) order by u.created_at desc)
    from public.profiles p join auth.users u on u.id = p.id
  ), '[]'::json);
end $$;
