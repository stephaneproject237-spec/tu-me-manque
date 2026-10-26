-- Le client peut écrire le premier message au SAV.
create or replace function public.support_send(p_body text)
returns json language plpgsql security definer set search_path = public as $$
declare b text := left(trim(coalesce(p_body, '')), 2000);
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if b = '' then return json_build_object('status', 'empty'); end if;
  if not exists (select 1 from public.profiles where id = auth.uid() and username is not null) then
    return json_build_object('status', 'no_profile');
  end if;
  if (select count(*) from public.support_messages
        where user_id = auth.uid() and not from_admin and created_at > now() - interval '1 minute') >= 15 then
    return json_build_object('status', 'too_fast');
  end if;
  insert into public.support_messages (user_id, from_admin, body) values (auth.uid(), false, b);
  return json_build_object('status', 'ok');
end $$;
