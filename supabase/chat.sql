-- Tu me manques : chat (messages, photos, vocaux, GIF, stickers).
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS schema.sql. Peut être relancé sans danger.

-- ========== TABLE DES MESSAGES ==========
create table if not exists public.messages (
  id bigint generated always as identity primary key,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  kind text not null check (kind in ('text', 'gif', 'sticker', 'image', 'voice')),
  body text,            -- texte, légende d'une photo, ou nom du sticker
  media text,           -- adresse du GIF, ou chemin du fichier (photo / vocal)
  meta jsonb,           -- largeur, hauteur, durée…
  created_at timestamptz not null default now(),
  read_at timestamptz
);

create index if not exists messages_recipient_idx on public.messages (recipient_id, id);
create index if not exists messages_sender_idx on public.messages (sender_id, id);

alter table public.messages enable row level security;

drop policy if exists "messages visibles par les deux personnes" on public.messages;
create policy "messages visibles par les deux personnes" on public.messages
  for select to authenticated using (sender_id = auth.uid() or recipient_id = auth.uid());

revoke all on public.messages from anon, authenticated;
grant select on public.messages to authenticated;

-- ========== STOCKAGE DES PHOTOS ET VOCAUX ==========
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'chat', 'chat', true, 8388608,
  array['image/jpeg', 'image/png', 'image/webp',
        'audio/mp4', 'audio/x-m4a', 'audio/m4a', 'audio/aac', 'audio/mpeg', 'audio/webm', 'audio/ogg']
)
on conflict (id) do update
  set public = true,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

-- Chacun peut envoyer des fichiers uniquement dans son propre dossier.
drop policy if exists "chat : envoi dans son dossier" on storage.objects;
create policy "chat : envoi dans son dossier" on storage.objects
  for insert to authenticated
  with check (bucket_id = 'chat' and (storage.foldername(name))[1] = auth.uid()::text);

-- ========== FONCTIONS ==========
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
  elsif p_kind in ('image', 'voice') then
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
    else '🎤 Message vocal'
  end;
  insert into public.notifications (to_id, title, body) values (me.partner_id, '@' || me.username, preview);

  return json_build_object('status', 'ok', 'id', mid);
end $$;

create or replace function public.mark_read()
returns json language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.messages set read_at = now() where recipient_id = auth.uid() and read_at is null;
  get diagnostics n = row_count;
  return json_build_object('status', 'ok', 'count', n);
end $$;

-- Le « Tu me manques » reçu a été vu (le chat s'est ouvert) : on l'efface de l'écran d'accueil.
create or replace function public.clear_miss()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.profiles
    set last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

-- Retirer le partenaire efface aussi la conversation entre les deux.
create or replace function public.remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare me public.profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  delete from public.messages
    where sender_id in (me.id, me.partner_id) and recipient_id in (me.id, me.partner_id);
  update public.profiles
    set partner_id = null, partner_name = null,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id in (me.id, me.partner_id);
  insert into public.notifications (to_id, title, body)
    values (me.partner_id, 'Partenaire retiré', '@' || me.username || ' a mis fin au lien. Tu peux ajouter quelqu''un d''autre.');
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.send_message(text, text, text, jsonb), public.mark_read(),
  public.clear_miss(), public.remove_partner() from public, anon, authenticated;
grant execute on function public.send_message(text, text, text, jsonb), public.mark_read(),
  public.clear_miss(), public.remove_partner() to authenticated;
