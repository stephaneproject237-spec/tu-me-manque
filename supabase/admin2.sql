-- Tu me manques : la messagerie @admin n'apparaît que si l'admin écrit en premier,
-- et l'admin peut supprimer une conversation (elle disparaît aussi chez la personne).
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS admin.sql. Peut être relancé sans danger.

-- Une personne ne peut répondre que s'il existe déjà un message de l'admin.
create or replace function public.support_send(p_body text)
returns json language plpgsql security definer set search_path = public as $$
declare b text := left(trim(coalesce(p_body, '')), 2000);
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if b = '' then return json_build_object('status', 'empty'); end if;
  if not exists (select 1 from public.support_messages where user_id = auth.uid() and from_admin) then
    return json_build_object('status', 'no_thread');
  end if;
  if (select count(*) from public.support_messages
        where user_id = auth.uid() and not from_admin and created_at > now() - interval '1 minute') >= 15 then
    return json_build_object('status', 'too_fast');
  end if;
  insert into public.support_messages (user_id, from_admin, body) values (auth.uid(), false, b);
  return json_build_object('status', 'ok');
end $$;

-- Supprime toute la conversation avec une personne (chez l'admin et chez elle).
create or replace function public.admin_delete_thread(p_user uuid)
returns json language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  delete from public.support_messages where user_id = p_user;
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.support_send(text), public.admin_delete_thread(uuid) from public, anon, authenticated;
grant execute on function public.support_send(text), public.admin_delete_thread(uuid) to authenticated;
