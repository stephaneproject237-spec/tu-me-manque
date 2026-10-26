-- Tu me manques : supprimer un message (pour moi, ou pour tout le monde).
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS chat.sql. Peut être relancé sans danger.

alter table public.messages
  add column if not exists del_sender boolean not null default false,
  add column if not exists del_recipient boolean not null default false,
  add column if not exists deleted_at timestamptz;

-- Chacun voit les messages, sauf ceux qu'il a supprimés « pour lui ».
drop policy if exists "messages visibles par les deux personnes" on public.messages;
create policy "messages visibles par les deux personnes" on public.messages
  for select to authenticated using (
    (sender_id = auth.uid() and not del_sender) or (recipient_id = auth.uid() and not del_recipient)
  );

create or replace function public.delete_message(p_id bigint, p_all boolean default false)
returns json language plpgsql security definer set search_path = public as $$
declare m public.messages;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into m from public.messages
    where id = p_id and (sender_id = auth.uid() or recipient_id = auth.uid());
  if not found then return json_build_object('status', 'not_found'); end if;

  if coalesce(p_all, false) then
    -- Pour tout le monde : seul l'auteur peut le faire. Le contenu est effacé.
    if m.sender_id <> auth.uid() then return json_build_object('status', 'forbidden'); end if;
    update public.messages set deleted_at = now(), body = null, media = null, meta = null where id = m.id;
    if m.media is not null then
      begin
        delete from storage.objects where bucket_id = 'chat' and name = m.media;
      exception when others then null;
      end;
    end if;
  elsif m.sender_id = auth.uid() then
    update public.messages set del_sender = true where id = m.id;
  else
    update public.messages set del_recipient = true where id = m.id;
  end if;
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.delete_message(bigint, boolean) from public, anon;
grant execute on function public.delete_message(bigint, boolean) to authenticated;
