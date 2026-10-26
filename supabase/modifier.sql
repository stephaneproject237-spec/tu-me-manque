-- Tu me manques : modifier un message texte déjà envoyé (la mention « modifié » s'affiche sous le message).
-- À exécuter une fois dans Supabase → SQL Editor. Peut être relancé sans danger.
create or replace function public.edit_message(p_id bigint, p_body text)
returns json language plpgsql security definer set search_path = public as $$
declare m public.messages; b text := trim(coalesce(p_body, ''));
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if b = '' or length(b) > 4000 then return json_build_object('status', 'invalid'); end if;
  select * into m from public.messages where id = p_id and sender_id = auth.uid() and kind = 'text' and deleted_at is null;
  if not found then return json_build_object('status', 'not_found'); end if;
  update public.messages set body = b, meta = coalesce(meta, '{}'::jsonb) || '{"edited":true}'::jsonb where id = m.id;
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.edit_message(bigint, text) from public, anon;
grant execute on function public.edit_message(bigint, text) to authenticated;
