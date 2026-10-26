-- Tu me manques : vidéos dans le chat.
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS chat.sql. Peut être relancé sans danger.

-- 1) Le type de message « video » est accepté.
do $$
declare c text;
begin
  for c in
    select conname from pg_constraint
    where conrelid = 'public.messages'::regclass and contype = 'c' and pg_get_constraintdef(oid) ilike '%kind%'
  loop
    execute format('alter table public.messages drop constraint %I', c);
  end loop;
  alter table public.messages add constraint messages_kind_check
    check (kind in ('text', 'gif', 'sticker', 'image', 'voice', 'video'));
end $$;

-- 2) Le dossier de stockage accepte les vidéos (jusqu'à 50 Mo).
update storage.buckets
  set file_size_limit = 52428800,
      allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp',
        'audio/mp4', 'audio/x-m4a', 'audio/m4a', 'audio/aac', 'audio/mpeg', 'audio/webm', 'audio/ogg',
        'video/mp4', 'video/quicktime', 'video/webm', 'video/3gpp', 'video/x-m4v']
  where id = 'chat';

-- 3) L'envoi accepte les vidéos.
create or replace function public.send_message(
  p_kind text, p_body text default null, p_media text default null, p_meta jsonb default null
) returns json language plpgsql security definer set search_path = public as $$
declare
  me public.profiles;
  b text := nullif(btrim(coalesce(p_body, '')), '');
  mid bigint;
  preview text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;

  if p_meta is not null and pg_column_size(p_meta) > 2000 then return json_build_object('status', 'invalid'); end if;
  if b is not null and length(b) > 4000 then return json_build_object('status', 'invalid'); end if;

  if p_kind = 'text' then
    if b is null then return json_build_object('status', 'invalid'); end if;
  elsif p_kind = 'sticker' then
    if b is null or b !~ '^[a-z0-9_]{1,40}$' then return json_build_object('status', 'invalid'); end if;
  elsif p_kind = 'gif' then
    if p_media is null or length(p_media) > 480 or p_media !~ '^https://[^[:space:]]+$' then return json_build_object('status', 'invalid'); end if;
  elsif p_kind in ('image', 'voice', 'video') then
    if p_media is null or p_media !~ ('^' || me.id::text || '/[A-Za-z0-9_.-]{1,80}$') then
      return json_build_object('status', 'invalid');
    end if;
  else
    return json_build_object('status', 'invalid');
  end if;

  insert into public.messages (sender_id, recipient_id, kind, body, media, meta)
    values (me.id, me.partner_id, p_kind, b, p_media, p_meta)
    returning id into mid;

  preview := case p_kind
    when 'text' then left(b, 140)
    when 'gif' then '🎞️ GIF'
    when 'sticker' then '💗 Sticker'
    when 'image' then case when b is null then '📷 Photo' else '📷 ' || left(b, 120) end
    when 'video' then '🎥 Vidéo'
    else '🎤 Message vocal'
  end;
  insert into public.notifications (to_id, title, body) values (me.partner_id, '@' || me.username, preview);

  return json_build_object('status', 'ok', 'id', mid);
end $$;

