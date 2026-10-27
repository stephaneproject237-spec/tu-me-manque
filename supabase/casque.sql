-- Casque : base de données (copie séparée de « Tu me manques » + plusieurs amis).
-- Colle tout dans Supabase → SQL Editor → Run. Peut être relancé sans danger.
create extension if not exists pg_net;

-- ============================== schema ==============================
-- Casque : base de données Supabase.
-- Remplace ahwxssgcwqzkxitpfvxy et WEBHOOK_SECRET (voir plus bas), puis colle tout dans « SQL Editor » → Run.

create extension if not exists pg_net;

-- ========== TABLES ==========
create table if not exists public.c_profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique,
  partner_id uuid references public.c_profiles(id) on delete set null,
  partner_name text,
  fcm_token text,
  web_push jsonb,
  last_miss_from text,
  last_miss_reply boolean,
  last_miss_at timestamptz
);

alter table public.c_profiles add column if not exists web_push jsonb;

create table if not exists public.c_requests (
  id bigint generated always as identity primary key,
  from_id uuid not null references public.c_profiles(id) on delete cascade,
  to_id uuid not null references public.c_profiles(id) on delete cascade,
  from_name text not null,
  to_name text not null,
  created_at timestamptz not null default now(),
  unique (from_id, to_id)
);

create table if not exists public.c_notifications (
  id bigint generated always as identity primary key,
  to_id uuid not null references public.c_profiles(id) on delete cascade,
  title text not null,
  body text not null,
  created_at timestamptz not null default now()
);

-- ========== SÉCURITÉ ==========
-- Chacun lit uniquement ses propres données. Toutes les écritures passent par les fonctions ci-dessous.
alter table public.c_profiles enable row level security;
alter table public.c_requests enable row level security;
alter table public.c_notifications enable row level security;

drop policy if exists "profil lisible par son propriétaire" on public.c_profiles;
create policy "profil lisible par son propriétaire" on public.c_profiles
  for select to authenticated using (id = auth.uid());

drop policy if exists "demandes visibles par les deux personnes" on public.c_requests;
create policy "demandes visibles par les deux personnes" on public.c_requests
  for select to authenticated using (from_id = auth.uid() or to_id = auth.uid());

revoke all on public.c_profiles, public.c_requests, public.c_notifications from anon, authenticated;
grant select on public.c_profiles, public.c_requests to authenticated;

-- ========== FONCTIONS ==========
create or replace function public.c_save_web_push(p_sub jsonb)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_sub is null or p_sub->>'endpoint' is null or p_sub->'keys'->>'p256dh' is null or p_sub->'keys'->>'auth' is null then
    return json_build_object('status', 'invalid');
  end if;
  insert into public.c_profiles (id, web_push) values (auth.uid(), p_sub)
    on conflict (id) do update set web_push = excluded.web_push, fcm_token = null;
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_save_token(p_token text)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles set fcm_token = null where fcm_token = p_token and id <> auth.uid();
  insert into public.c_profiles (id, fcm_token) values (auth.uid(), p_token)
    on conflict (id) do update set fcm_token = excluded.fcm_token;
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_claim_username(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
  existing text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  insert into public.c_profiles (id) values (auth.uid()) on conflict (id) do nothing;
  select username into existing from public.c_profiles where id = auth.uid();
  if existing is not null then return json_build_object('status', 'has_username'); end if;
  begin
    update public.c_profiles set username = n where id = auth.uid();
  exception when unique_violation then
    return json_build_object('status', 'taken');
  end;
  return json_build_object('status', 'ok');
end $$;

-- Relie deux personnes (usage interne uniquement).
create or replace function public.c__link(a uuid, b uuid)
returns void language plpgsql security definer set search_path = public as $$
declare na text; nb text;
begin
  select username into na from public.c_profiles where id = a;
  select username into nb from public.c_profiles where id = b;
  update public.c_profiles set partner_id = b, partner_name = nb where id = a;
  update public.c_profiles set partner_id = a, partner_name = na where id = b;
  delete from public.c_requests where from_id in (a, b) or to_id in (a, b);
end $$;

create or replace function public.c_send_request(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
  me public.c_profiles;
  tgt public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.username is null then return json_build_object('status', 'no_username'); end if;
  if me.partner_id is not null then return json_build_object('status', 'already_has_partner'); end if;
  if me.username = n then return json_build_object('status', 'self'); end if;
  select * into tgt from public.c_profiles where username = n;
  if not found then return json_build_object('status', 'not_found'); end if;
  if tgt.partner_id is not null then return json_build_object('status', 'partner_busy'); end if;

  -- L'autre personne m'avait déjà ajouté : on relie directement.
  if exists (select 1 from public.c_requests where from_id = tgt.id and to_id = me.id) then
    perform public.c__link(me.id, tgt.id);
    insert into public.c_notifications (to_id, title, body)
      values (tgt.id, 'Vous êtes partenaires 🎧', '@' || me.username || ' et toi êtes maintenant partenaires');
    return json_build_object('status', 'linked');
  end if;

  if exists (select 1 from public.c_requests where from_id = me.id and to_id = tgt.id) then
    return json_build_object('status', 'already_sent');
  end if;

  insert into public.c_requests (from_id, to_id, from_name, to_name) values (me.id, tgt.id, me.username, tgt.username);
  insert into public.c_notifications (to_id, title, body)
    values (tgt.id, 'Nouvelle demande 🎧', '@' || me.username || ' veut être ton partenaire');
  return json_build_object('status', 'sent');
end $$;

create or replace function public.c_respond_request(p_request_id bigint, p_accept boolean)
returns json language plpgsql security definer set search_path = public as $$
declare r public.c_requests;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into r from public.c_requests where id = p_request_id and to_id = auth.uid();
  if not found then return json_build_object('status', 'not_found'); end if;
  if not p_accept then
    delete from public.c_requests where id = r.id;
    return json_build_object('status', 'ok');
  end if;
  if exists (select 1 from public.c_profiles where id in (r.from_id, r.to_id) and partner_id is not null) then
    delete from public.c_requests where id = r.id;
    return json_build_object('status', 'partner_busy');
  end if;
  perform public.c__link(r.from_id, r.to_id);
  insert into public.c_notifications (to_id, title, body)
    values (r.from_id, 'Demande acceptée 🎧', '@' || r.to_name || ' a accepté d''être ton partenaire');
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_send_miss(p_reply boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  update public.c_profiles
    set last_miss_from = me.username, last_miss_reply = p_reply, last_miss_at = now()
    where id = me.partner_id;
  if p_reply then
    update public.c_profiles
      set last_miss_from = null, last_miss_reply = null, last_miss_at = null
      where id = me.id;
  end if;
  insert into public.c_notifications (to_id, title, body)
    values (me.partner_id, '@' || me.username,
            case when p_reply then 'Casque aussi 🎧' else 'Casque 🎧' end);
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  update public.c_profiles
    set partner_id = null, partner_name = null,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id in (me.id, me.partner_id);
  insert into public.c_notifications (to_id, title, body)
    values (me.partner_id, 'Partenaire retiré', '@' || me.username || ' a mis fin au lien. Tu peux ajouter quelqu''un d''autre.');
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.c_remove_partner() from public, anon, authenticated;
grant execute on function public.c_remove_partner() to authenticated;

-- Seuls les utilisateurs connectés (même anonymes) peuvent appeler ces fonctions.
revoke all on function public.c_save_web_push(jsonb), public.c_save_token(text), public.c_claim_username(text), public.c_send_request(text),
  public.c_respond_request(bigint, boolean), public.c_send_miss(boolean), public.c__link(uuid, uuid)
  from public, anon, authenticated;
grant execute on function public.c_save_web_push(jsonb), public.c_save_token(text), public.c_claim_username(text), public.c_send_request(text),
  public.c_respond_request(bigint, boolean), public.c_send_miss(boolean) to authenticated;

-- ========== ENVOI DES NOTIFICATIONS ==========
-- À chaque nouvelle ligne dans « notifications », on appelle la fonction « notify » (voir supabase/notify/index.ts).
create or replace function public.c_push_on_notification()
returns trigger language plpgsql security definer set search_path = public, extensions as $$
declare t text; w jsonb;
begin
  select fcm_token, web_push into t, w from public.c_profiles where id = new.to_id;
  if t is not null or w is not null then
    perform net.http_post(
      url := 'https://ahwxssgcwqzkxitpfvxy.supabase.co/functions/v1/notify',
      headers := jsonb_build_object('Content-Type', 'application/json', 'x-webhook-secret', 'WEBHOOK_SECRET'),
      body := jsonb_build_object('token', t, 'web_push', w, 'title', new.title, 'body', new.body)
    );
  end if;
  return new;
end $$;

revoke all on function public.c_push_on_notification() from public, anon, authenticated;

drop trigger if exists c_on_notification_insert on public.c_notifications;
create trigger c_on_notification_insert
  after insert on public.c_notifications
  for each row execute function public.c_push_on_notification();

-- ============================== compte ==============================
-- Casque : comptes (pseudo + âge + connexion Google / Apple / SMS).
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS schema.sql et chat.sql. Peut être relancé sans danger.

-- ========== COLONNES ==========
alter table public.c_profiles add column if not exists age smallint;
alter table public.c_profiles add column if not exists phone text;          -- numéro vérifié par SMS (chiffres seulement, avec indicatif)
alter table public.c_profiles add column if not exists partner_phone text;  -- numéro du partenaire (pour afficher son nom de contact)
create unique index if not exists c_profiles_phone_key on public.c_profiles (phone) where phone is not null;

-- ========== OUTILS ==========
-- « +237 6 12 34 56 78 », « 237612345678 », « 00237612345678 » et « 612345678 » donnent tous 237612345678.
create or replace function public.c_norm_phone(p text)
returns text language sql immutable as $$
  select case
    when d = '' then null
    when left(d, 2) = '00' then substr(d, 3)
    when length(d) = 9 and left(d, 1) = '6' then '237' || d
    else d
  end
  from (select regexp_replace(coalesce(p, ''), '\D', '', 'g') as d) s
$$;

-- Le pseudo est-il libre ? (appelable avant la connexion)
create or replace function public.c_username_available(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
begin
  if n !~ '^[a-z][a-z0-9_]{2,19}$' then return json_build_object('status', 'invalid'); end if;
  if exists (select 1 from public.c_profiles where username = n) then return json_build_object('status', 'taken'); end if;
  return json_build_object('status', 'available');
end $$;

-- Crée le profil d'une personne connectée (Google, Apple ou SMS) : pseudo + âge.
create or replace function public.c_create_account(p_username text, p_age int)
returns json language plpgsql security definer set search_path = public as $$
declare
  n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
  existing text;
  ph text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then return json_build_object('status', 'anonymous'); end if;
  if n !~ '^[a-z][a-z0-9_]{2,19}$' then return json_build_object('status', 'invalid'); end if;
  if p_age is null or p_age < 1 or p_age > 120 then return json_build_object('status', 'invalid_age'); end if;
  insert into public.c_profiles (id) values (auth.uid()) on conflict (id) do nothing;
  select username into existing from public.c_profiles where id = auth.uid();
  if existing is not null then return json_build_object('status', 'has_username'); end if;
  select public.c_norm_phone(phone) into ph from auth.users where id = auth.uid();
  begin
    update public.c_profiles set username = n, age = p_age::smallint, phone = ph where id = auth.uid();
  exception when unique_violation then
    return json_build_object('status', 'taken');
  end;
  return json_build_object('status', 'ok');
end $$;

-- ========== LIEN ENTRE PARTENAIRES (avec les numéros) ==========
create or replace function public.c__link(a uuid, b uuid)
returns void language plpgsql security definer set search_path = public as $$
declare na text; nb text; pa text; pb text;
begin
  select username, phone into na, pa from public.c_profiles where id = a;
  select username, phone into nb, pb from public.c_profiles where id = b;
  update public.c_profiles set partner_id = b, partner_name = nb, partner_phone = pb where id = a;
  update public.c_profiles set partner_id = a, partner_name = na, partner_phone = pa where id = b;
  delete from public.c_requests where from_id in (a, b) or to_id in (a, b);
end $$;

-- Ajout par pseudo OU par numéro de téléphone.
create or replace function public.c_send_request(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  q text := lower(trim(coalesce(p_username, '')));
  n text := regexp_replace(q, '^@', '');
  by_phone boolean := (q !~ '[a-z_]' and length(regexp_replace(q, '\D', '', 'g')) >= 8);
  me public.c_profiles;
  tgt public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not by_phone and n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.username is null then return json_build_object('status', 'no_username'); end if;
  if me.partner_id is not null then return json_build_object('status', 'already_has_partner'); end if;

  if by_phone then
    select * into tgt from public.c_profiles where phone = public.c_norm_phone(q);
  else
    select * into tgt from public.c_profiles where username = n;
  end if;
  if not found then return json_build_object('status', 'not_found'); end if;
  if tgt.id = me.id then return json_build_object('status', 'self'); end if;
  if tgt.partner_id is not null then return json_build_object('status', 'partner_busy'); end if;

  if exists (select 1 from public.c_requests where from_id = tgt.id and to_id = me.id) then
    perform public.c__link(me.id, tgt.id);
    insert into public.c_notifications (to_id, title, body)
      values (tgt.id, 'Vous êtes partenaires 🎧', '@' || me.username || ' et toi êtes maintenant partenaires');
    return json_build_object('status', 'linked');
  end if;

  if exists (select 1 from public.c_requests where from_id = me.id and to_id = tgt.id) then
    return json_build_object('status', 'already_sent');
  end if;

  insert into public.c_requests (from_id, to_id, from_name, to_name) values (me.id, tgt.id, me.username, tgt.username);
  insert into public.c_notifications (to_id, title, body)
    values (tgt.id, 'Nouvelle demande 🎧', '@' || me.username || ' veut être ton partenaire');
  return json_build_object('status', 'sent', 'name', tgt.username);
end $$;

-- Retirer le partenaire : efface aussi la conversation et le numéro mémorisé.
create or replace function public.c_remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  delete from public.c_messages
    where sender_id in (me.id, me.partner_id) and recipient_id in (me.id, me.partner_id);
  update public.c_profiles
    set partner_id = null, partner_name = null, partner_phone = null,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id in (me.id, me.partner_id);
  insert into public.c_notifications (to_id, title, body)
    values (me.partner_id, 'Partenaire retiré', '@' || me.username || ' a mis fin au lien. Tu peux ajouter quelqu''un d''autre.');
  return json_build_object('status', 'ok');
end $$;

-- Mise à jour pour les comptes déjà liés : on mémorise le numéro du partenaire.
update public.c_profiles p
  set partner_phone = q.phone
  from public.c_profiles q
  where p.partner_id = q.id and p.partner_phone is distinct from q.phone;

-- ========== DROITS ==========
-- Plus de pseudo « anonyme » : il faut passer par create_account (Google, Apple ou SMS).
revoke all on function public.c_claim_username(text) from public, anon, authenticated;
revoke all on function public.c_norm_phone(text), public.c_username_available(text), public.c_create_account(text, int),
  public.c__link(uuid, uuid), public.c_send_request(text), public.c_remove_partner() from public, anon, authenticated;
grant execute on function public.c_username_available(text) to anon, authenticated;
grant execute on function public.c_norm_phone(text), public.c_create_account(text, int),
  public.c_send_request(text), public.c_remove_partner() to authenticated;

-- ============================== chat ==============================
-- Casque : chat (messages, photos, vocaux, GIF, stickers).
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS schema.sql. Peut être relancé sans danger.

-- ========== TABLE DES MESSAGES ==========
create table if not exists public.c_messages (
  id bigint generated always as identity primary key,
  sender_id uuid not null references public.c_profiles(id) on delete cascade,
  recipient_id uuid not null references public.c_profiles(id) on delete cascade,
  kind text not null check (kind in ('text', 'gif', 'sticker', 'image', 'voice')),
  body text,            -- texte, légende d'une photo, ou nom du sticker
  media text,           -- adresse du GIF, ou chemin du fichier (photo / vocal)
  meta jsonb,           -- largeur, hauteur, durée…
  created_at timestamptz not null default now(),
  read_at timestamptz
);

create index if not exists c_messages_recipient_idx on public.c_messages (recipient_id, id);
create index if not exists c_messages_sender_idx on public.c_messages (sender_id, id);

alter table public.c_messages enable row level security;

drop policy if exists "messages visibles par les deux personnes" on public.c_messages;
create policy "messages visibles par les deux personnes" on public.c_messages
  for select to authenticated using (sender_id = auth.uid() or recipient_id = auth.uid());

revoke all on public.c_messages from anon, authenticated;
grant select on public.c_messages to authenticated;

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
create or replace function public.c_send_message(
  p_kind text, p_body text default null, p_media text default null, p_meta jsonb default null
) returns json language plpgsql security definer set search_path = public as $$
declare
  me public.c_profiles;
  b text := nullif(btrim(coalesce(p_body, '')), '');
  mid bigint;
  preview text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
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

  insert into public.c_messages (sender_id, recipient_id, kind, body, media, meta)
    values (me.id, me.partner_id, p_kind, b, p_media, p_meta)
    returning id into mid;

  preview := case p_kind
    when 'text' then left(b, 140)
    when 'gif' then '🎞️ GIF'
    when 'sticker' then '🎧 Sticker'
    when 'image' then case when b is null then '📷 Photo' else '📷 ' || left(b, 120) end
    else '🎤 Message vocal'
  end;
  insert into public.c_notifications (to_id, title, body) values (me.partner_id, '@' || me.username, preview);

  return json_build_object('status', 'ok', 'id', mid);
end $$;

create or replace function public.c_mark_read()
returns json language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_messages set read_at = now() where recipient_id = auth.uid() and read_at is null;
  get diagnostics n = row_count;
  return json_build_object('status', 'ok', 'count', n);
end $$;

-- Le « Casque » reçu a été vu (le chat s'est ouvert) : on l'efface de l'écran d'accueil.
create or replace function public.c_clear_miss()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles
    set last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

-- Retirer le partenaire efface aussi la conversation entre les deux.
create or replace function public.c_remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  delete from public.c_messages
    where sender_id in (me.id, me.partner_id) and recipient_id in (me.id, me.partner_id);
  update public.c_profiles
    set partner_id = null, partner_name = null,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id in (me.id, me.partner_id);
  insert into public.c_notifications (to_id, title, body)
    values (me.partner_id, 'Partenaire retiré', '@' || me.username || ' a mis fin au lien. Tu peux ajouter quelqu''un d''autre.');
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.c_send_message(text, text, text, jsonb), public.c_mark_read(),
  public.c_clear_miss(), public.c_remove_partner() from public, anon, authenticated;
grant execute on function public.c_send_message(text, text, text, jsonb), public.c_mark_read(),
  public.c_clear_miss(), public.c_remove_partner() to authenticated;

-- ============================== contact ==============================
-- ===== ÉTAPE 1 =====
alter table public.c_profiles add column if not exists birthdate date;

-- ===== ÉTAPE 2 =====
create or replace function public.c_save_contact(p_birth date, p_phone text)
returns json language plpgsql security definer set search_path = public as $$
declare
  ph text := public.c_norm_phone(p_phone);
  cur text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_birth is null or p_birth > current_date - interval '5 years' or p_birth < current_date - interval '120 years' then
    return json_build_object('status', 'invalid_birth');
  end if;
  if ph is null or length(ph) < 8 then return json_build_object('status', 'invalid_phone'); end if;
  select phone into cur from public.c_profiles where id = auth.uid();
  begin
    update public.c_profiles
      set birthdate = p_birth, age = extract(year from age(p_birth))::smallint, phone = coalesce(cur, ph)
      where id = auth.uid();
  exception when unique_violation then
    return json_build_object('status', 'phone_taken');
  end;
  update public.c_profiles set partner_phone = coalesce(cur, ph) where partner_id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

-- ===== ÉTAPE 3 =====
revoke all on function public.c_save_contact(date, text) from public, anon;
grant execute on function public.c_save_contact(date, text) to authenticated;

-- ============================== fond ==============================
-- Fond d'écran de la conversation, partagé entre les deux partenaires.
alter table public.c_profiles add column if not exists wall text;

create or replace function public.c_set_wallpaper(p_value text)
returns json language plpgsql security definer set search_path = public as $$
declare
  me uuid := auth.uid();
  pid uuid;
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_value is not null
     and p_value !~ '^preset:[0-5]$'
     and p_value !~ ('^photo:' || me::text || '/[0-9a-f]{32}\.jpg$') then
    return json_build_object('status', 'invalid');
  end if;
  select partner_id into pid from public.c_profiles where id = me;
  if pid is null then return json_build_object('status', 'no_partner'); end if;
  update public.c_profiles set wall = p_value where id in (me, pid);
  return json_build_object('status', 'ok');
end $$;

-- Quand le lien entre deux personnes change (ajout ou retrait), le fond revient à celui d'origine.
create or replace function public.c_reset_wall() returns trigger language plpgsql as $$
begin
  new.wall := null;
  return new;
end $$;

drop trigger if exists c_profiles_reset_wall on public.c_profiles;
create trigger c_profiles_reset_wall
  before update of partner_id on public.c_profiles
  for each row when (old.partner_id is distinct from new.partner_id)
  execute function public.c_reset_wall();

revoke all on function public.c_set_wallpaper(text) from public, anon, authenticated;
grant execute on function public.c_set_wallpaper(text) to authenticated;

-- ============================== typing ==============================
-- Casque : « est en train d'écrire… ».
-- À exécuter une fois dans Supabase → SQL Editor. Peut être relancé sans danger.

create table if not exists public.c_typing (
  user_id uuid primary key references public.c_profiles(id) on delete cascade,
  until_at timestamptz not null
);
alter table public.c_typing enable row level security;
revoke all on public.c_typing from anon, authenticated;

create or replace function public.c_set_typing()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  insert into public.c_typing (user_id, until_at) values (auth.uid(), now() + interval '5 seconds')
    on conflict (user_id) do update set until_at = excluded.until_at;
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_stop_typing()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_typing set until_at = now() where user_id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_partner_typing()
returns json language plpgsql security definer set search_path = public as $$
declare t boolean;
begin
  if auth.uid() is null then return json_build_object('typing', false); end if;
  select exists (
    select 1 from public.c_profiles me
    join public.c_typing ty on ty.user_id = me.partner_id
    where me.id = auth.uid() and ty.until_at > now()
  ) into t;
  return json_build_object('typing', coalesce(t, false));
end $$;

revoke all on function public.c_set_typing() from public, anon;
revoke all on function public.c_stop_typing() from public, anon;
revoke all on function public.c_partner_typing() from public, anon;
grant execute on function public.c_set_typing() to authenticated;
grant execute on function public.c_stop_typing() to authenticated;
grant execute on function public.c_partner_typing() to authenticated;

-- ============================== video ==============================
-- Casque : vidéos dans le chat.
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS chat.sql. Peut être relancé sans danger.

-- 1) Le type de message « video » est accepté.
do $$
declare c text;
begin
  for c in
    select conname from pg_constraint
    where conrelid = 'public.c_messages'::regclass and contype = 'c' and pg_get_constraintdef(oid) ilike '%kind%'
  loop
    execute format('alter table public.c_messages drop constraint %I', c);
  end loop;
  alter table public.c_messages add constraint messages_kind_check
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
create or replace function public.c_send_message(
  p_kind text, p_body text default null, p_media text default null, p_meta jsonb default null
) returns json language plpgsql security definer set search_path = public as $$
declare
  me public.c_profiles;
  b text := nullif(btrim(coalesce(p_body, '')), '');
  mid bigint;
  preview text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
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

  insert into public.c_messages (sender_id, recipient_id, kind, body, media, meta)
    values (me.id, me.partner_id, p_kind, b, p_media, p_meta)
    returning id into mid;

  preview := case p_kind
    when 'text' then left(b, 140)
    when 'gif' then '🎞️ GIF'
    when 'sticker' then '🎧 Sticker'
    when 'image' then case when b is null then '📷 Photo' else '📷 ' || left(b, 120) end
    when 'video' then '🎥 Vidéo'
    else '🎤 Message vocal'
  end;
  insert into public.c_notifications (to_id, title, body) values (me.partner_id, '@' || me.username, preview);

  return json_build_object('status', 'ok', 'id', mid);
end $$;


-- ============================== supprimer ==============================
-- Casque : supprimer un message (pour moi, ou pour tout le monde).
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS chat.sql. Peut être relancé sans danger.

alter table public.c_messages
  add column if not exists del_sender boolean not null default false,
  add column if not exists del_recipient boolean not null default false,
  add column if not exists deleted_at timestamptz;

-- Chacun voit les messages, sauf ceux qu'il a supprimés « pour lui ».
drop policy if exists "messages visibles par les deux personnes" on public.c_messages;
create policy "messages visibles par les deux personnes" on public.c_messages
  for select to authenticated using (
    (sender_id = auth.uid() and not del_sender) or (recipient_id = auth.uid() and not del_recipient)
  );

create or replace function public.c_delete_message(p_id bigint, p_all boolean default false)
returns json language plpgsql security definer set search_path = public as $$
declare m public.c_messages;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into m from public.c_messages
    where id = p_id and (sender_id = auth.uid() or recipient_id = auth.uid());
  if not found then return json_build_object('status', 'not_found'); end if;

  if coalesce(p_all, false) then
    -- Pour tout le monde : seul l'auteur peut le faire. Le contenu est effacé.
    if m.sender_id <> auth.uid() then return json_build_object('status', 'forbidden'); end if;
    update public.c_messages set deleted_at = now(), body = null, media = null, meta = null where id = m.id;
    if m.media is not null then
      begin
        delete from storage.objects where bucket_id = 'chat' and name = m.media;
      exception when others then null;
      end;
    end if;
  elsif m.sender_id = auth.uid() then
    update public.c_messages set del_sender = true where id = m.id;
  else
    update public.c_messages set del_recipient = true where id = m.id;
  end if;
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.c_delete_message(bigint, boolean) from public, anon;
grant execute on function public.c_delete_message(bigint, boolean) to authenticated;

-- ============================== modifier ==============================
-- Casque : modifier un message texte déjà envoyé (la mention « modifié » s'affiche sous le message).
-- À exécuter une fois dans Supabase → SQL Editor. Peut être relancé sans danger.
create or replace function public.c_edit_message(p_id bigint, p_body text)
returns json language plpgsql security definer set search_path = public as $$
declare m public.c_messages; b text := trim(coalesce(p_body, ''));
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if b = '' or length(b) > 4000 then return json_build_object('status', 'invalid'); end if;
  select * into m from public.c_messages where id = p_id and sender_id = auth.uid() and kind = 'text' and deleted_at is null;
  if not found then return json_build_object('status', 'not_found'); end if;
  update public.c_messages set body = b, meta = coalesce(meta, '{}'::jsonb) || '{"edited":true}'::jsonb where id = m.id;
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.c_edit_message(bigint, text) from public, anon;
grant execute on function public.c_edit_message(bigint, text) to authenticated;

-- ============================== jeux ==============================
-- Casque : jeux à deux (« Me connais-tu ? »).
-- À exécuter une fois dans Supabase → SQL Editor. Peut être relancé sans danger.

drop function if exists public.c_game_invite();
create table if not exists public.c_game_rounds (
  id bigserial primary key,
  game text not null default 'connais',
  user_a uuid not null references public.c_profiles(id) on delete cascade,   -- celui qui propose
  user_b uuid not null references public.c_profiles(id) on delete cascade,
  status text not null check (status in ('invite','declined','playing','done','cancelled')),
  qids int[] not null,
  created_at timestamptz not null default now()
);
create index if not exists c_game_rounds_a_idx on public.c_game_rounds (user_a, id desc);
create index if not exists c_game_rounds_b_idx on public.c_game_rounds (user_b, id desc);

create table if not exists public.c_game_answers (
  round_id bigint not null references public.c_game_rounds(id) on delete cascade,
  user_id uuid not null references public.c_profiles(id) on delete cascade,
  idx smallint not null,
  answer text not null,
  primary key (round_id, user_id, idx)
);

create table if not exists public.c_game_guesses (
  round_id bigint not null references public.c_game_rounds(id) on delete cascade,
  guesser uuid not null references public.c_profiles(id) on delete cascade,
  idx smallint not null,
  guess text not null,
  ok boolean not null,
  primary key (round_id, guesser, idx)
);

alter table public.c_game_rounds enable row level security;
alter table public.c_game_answers enable row level security;
alter table public.c_game_guesses enable row level security;
revoke all on public.c_game_rounds, public.c_game_answers, public.c_game_guesses from anon, authenticated;

-- ---------- Comparaison tolérante des réponses ----------
create or replace function public.c_game_norm(t text)
returns text language sql immutable as $$
  select trim(regexp_replace(
    regexp_replace(
      translate(lower(coalesce(t,'')), 'àâäáãåçéèêëíìîïñóòôöõúùûüýÿœæ', 'aaaaaaceeeeiiiinooooouuuuyyoa'),
      '[^a-z0-9 ]', ' ', 'g'),
    '\s+', ' ', 'g'))
$$;

create or replace function public.c_game_lev(a text, b text)
returns int language plpgsql immutable as $$
declare la int := length(a); lb int := length(b); prev int[]; cur int[]; i int; j int; c int;
begin
  if la = 0 then return lb; end if;
  if lb = 0 then return la; end if;
  prev := array(select generate_series(0, lb));
  for i in 1..la loop
    cur := array[i];
    for j in 1..lb loop
      c := case when substr(a,i,1) = substr(b,j,1) then 0 else 1 end;
      cur[j+1] := least(prev[j+1] + 1, cur[j] + 1, prev[j] + c);
    end loop;
    prev := cur;
  end loop;
  return prev[lb+1];
end $$;

create or replace function public.c_game_match(a text, b text)
returns boolean language plpgsql immutable as $$
declare x text; y text; sx text; sy text; n int; tol int;
begin
  x := public.c_game_norm(a); y := public.c_game_norm(b);
  if x = '' or y = '' then return false; end if;
  if x = y then return true; end if;
  -- on retire les petits mots (le, la, de, mon…)
  sx := trim(regexp_replace(' ' || x || ' ', ' (le|la|les|l|un|une|des|du|de|d|mon|ma|mes|son|sa|ses|au|aux|en) ', ' ', 'g'));
  sy := trim(regexp_replace(' ' || y || ' ', ' (le|la|les|l|un|une|des|du|de|d|mon|ma|mes|son|sa|ses|au|aux|en) ', ' ', 'g'));
  sx := trim(regexp_replace(sx, ' (le|la|les|l|un|une|des|du|de|d) ', ' ', 'g'));
  sy := trim(regexp_replace(sy, ' (le|la|les|l|un|une|des|du|de|d) ', ' ', 'g'));
  if sx = '' then sx := x; end if;
  if sy = '' then sy := y; end if;
  if sx = sy then return true; end if;
  if replace(sx,' ','') = replace(sy,' ','') then return true; end if;
  -- l'un contient l'autre (mots entiers, 4 lettres minimum) : « bleu » / « bleu ciel »
  if length(sx) >= 4 and (' ' || sy || ' ') like '% ' || sx || ' %' then return true; end if;
  if length(sy) >= 4 and (' ' || sx || ' ') like '% ' || sy || ' %' then return true; end if;
  -- fautes de frappe
  n := greatest(length(sx), length(sy));
  tol := case when n <= 4 then 0 when n <= 8 then 1 when n <= 13 then 2 else 3 end;
  if x ~ '^[0-9 ]+$' then tol := 0; end if;  -- les nombres et les dates doivent être exacts
  return public.c_game_lev(sx, sy) <= tol;
end $$;

-- ---------- État du jeu ----------
create or replace function public.c_game_state()
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.c_game_rounds; pid uuid; mine int; theirs int; mg int; tg int; res json;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.c_game_rounds
    where (user_a = me or user_b = me) and status in ('invite','playing','done')
    order by id desc limit 1;
  if not found then return json_build_object('status','ok','round',null); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  select count(*) into mine from public.c_game_answers where round_id = r.id and user_id = me;
  select count(*) into theirs from public.c_game_answers where round_id = r.id and user_id = pid;
  select count(*) into mg from public.c_game_guesses where round_id = r.id and guesser = me;
  select count(*) into tg from public.c_game_guesses where round_id = r.id and guesser = pid;
  select json_build_object(
    'status','ok',
    'round', json_build_object(
      'id', r.id, 'state', r.status, 'by_me', r.user_a = me, 'qids', r.qids,
      'my_answers', mine, 'their_answers', theirs, 'my_guesses', mg, 'their_guesses', tg,
      'my_answer_list', (select coalesce(json_agg(json_build_object('idx', idx, 'answer', answer) order by idx), '[]'::json)
                         from public.c_game_answers where round_id = r.id and user_id = me),
      'my_results', (select coalesce(json_agg(json_build_object(
                        'idx', g.idx, 'guess', g.guess, 'ok', g.ok,
                        'answer', (select a.answer from public.c_game_answers a where a.round_id = r.id and a.user_id = pid and a.idx = g.idx)
                      ) order by g.idx), '[]'::json)
                     from public.c_game_guesses g where g.round_id = r.id and g.guesser = me),
      'their_results', (select coalesce(json_agg(json_build_object(
                        'idx', g.idx, 'guess', g.guess, 'ok', g.ok,
                        'answer', (select a.answer from public.c_game_answers a where a.round_id = r.id and a.user_id = me and a.idx = g.idx)
                      ) order by g.idx), '[]'::json)
                     from public.c_game_guesses g where g.round_id = r.id and g.guesser = pid)
    )) into res;
  return res;
end $$;

-- ---------- Proposer une partie ----------
create or replace function public.c_game_invite(p_total int default 664)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); pid uuid; ids int[]; used int[]; total int := least(greatest(coalesce(p_total, 664), 10), 5000);
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select partner_id into pid from public.c_profiles where id = me;
  if pid is null then return json_build_object('status','no_partner'); end if;
  update public.c_game_rounds set status = 'cancelled'
    where status in ('invite','playing') and (user_a in (me,pid) or user_b in (me,pid));
  select coalesce(array_agg(distinct q), '{}') into used
    from public.c_game_rounds r, unnest(r.qids) q
    where (r.user_a = me and r.user_b = pid) or (r.user_a = pid and r.user_b = me);
  select array_agg(x) into ids from (
    select x from generate_series(1, total) x where not (x = any(used)) order by random() limit 10) s;
  if ids is null or array_length(ids,1) < 10 then
    select array_agg(x) into ids from (select x from generate_series(1, total) x order by random() limit 10) s;
  end if;
  insert into public.c_game_rounds (user_a, user_b, status, qids) values (me, pid, 'invite', ids);
  return public.c_game_state();
end $$;

-- ---------- Répondre à l'invitation ----------
create or replace function public.c_game_respond(p_round bigint, p_accept boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  update public.c_game_rounds set status = case when p_accept then 'playing' else 'declined' end
    where id = p_round and user_b = me and status = 'invite';
  return public.c_game_state();
end $$;

create or replace function public.c_game_cancel(p_round bigint)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  update public.c_game_rounds set status = 'cancelled'
    where id = p_round and (user_a = me or user_b = me) and status in ('invite','playing','done');
  return public.c_game_state();
end $$;

-- ---------- Enregistrer mes réponses (à l'avance) ----------
create or replace function public.c_game_save_answers(p_round bigint, p_answers jsonb)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.c_game_rounds; i int; v text;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.c_game_rounds where id = p_round and (user_a = me or user_b = me) and status = 'playing';
  if not found then return json_build_object('status','closed'); end if;
  if exists (select 1 from public.c_game_guesses g where g.round_id = r.id
             and g.guesser = case when r.user_a = me then r.user_b else r.user_a end) then
    return json_build_object('status','locked');   -- l'autre a déjà commencé à deviner
  end if;
  for i in 0..9 loop
    v := left(trim(coalesce(p_answers ->> i, '')), 120);
    if v <> '' then
      insert into public.c_game_answers (round_id, user_id, idx, answer) values (r.id, me, i, v)
        on conflict (round_id, user_id, idx) do update set answer = excluded.answer;
    end if;
  end loop;
  return public.c_game_state();
end $$;

-- ---------- Deviner ----------
create or replace function public.c_game_guess(p_round bigint, p_idx int, p_guess text)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.c_game_rounds; pid uuid; ans text; good boolean; n1 int; n2 int;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.c_game_rounds where id = p_round and (user_a = me or user_b = me) and status = 'playing';
  if not found then return json_build_object('status','closed'); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  if p_idx < 0 or p_idx > 9 then return json_build_object('status','bad'); end if;
  if exists (select 1 from public.c_game_guesses where round_id = r.id and guesser = me and idx = p_idx) then
    return json_build_object('status','already');
  end if;
  select answer into ans from public.c_game_answers where round_id = r.id and user_id = pid and idx = p_idx;
  if ans is null then return json_build_object('status','not_ready'); end if;
  good := public.c_game_match(p_guess, ans);
  insert into public.c_game_guesses (round_id, guesser, idx, guess, ok) values (r.id, me, p_idx, left(trim(p_guess),120), good);
  select count(*) into n1 from public.c_game_guesses where round_id = r.id and guesser = me;
  select count(*) into n2 from public.c_game_guesses where round_id = r.id and guesser = pid;
  if n1 >= 10 and n2 >= 10 then update public.c_game_rounds set status = 'done' where id = r.id; end if;
  return json_build_object('status','ok','ok',good,'answer',ans,'idx',p_idx);
end $$;

revoke all on function public.c_game_state() from public, anon;
revoke all on function public.c_game_invite(int) from public, anon;
revoke all on function public.c_game_respond(bigint, boolean) from public, anon;
revoke all on function public.c_game_cancel(bigint) from public, anon;
revoke all on function public.c_game_save_answers(bigint, jsonb) from public, anon;
revoke all on function public.c_game_guess(bigint, int, text) from public, anon;
grant execute on function public.c_game_state() to authenticated;
grant execute on function public.c_game_invite(int) to authenticated;
grant execute on function public.c_game_respond(bigint, boolean) to authenticated;
grant execute on function public.c_game_cancel(bigint) to authenticated;
grant execute on function public.c_game_save_answers(bigint, jsonb) to authenticated;
grant execute on function public.c_game_guess(bigint, int, text) to authenticated;

-- ============================== jeux2 ==============================
-- Casque : le jeu « Me connais-tu ? » se joue maintenant dans le fil du chat.
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS jeux.sql. Peut être relancé sans danger.

-- 1) Le chat accepte les messages du jeu (type « game »).
do $$
declare c text;
begin
  for c in
    select conname from pg_constraint
    where conrelid = 'public.c_messages'::regclass and contype = 'c' and pg_get_constraintdef(oid) ilike '%kind%'
  loop
    execute format('alter table public.c_messages drop constraint %I', c);
  end loop;
  alter table public.c_messages add constraint messages_kind_check
    check (kind in ('text', 'gif', 'sticker', 'image', 'voice', 'video', 'game'));
end $$;

alter table public.c_game_rounds add column if not exists started boolean not null default false;

-- 2) Publier un message du jeu dans le chat.
create or replace function public.c_game_post(p_from uuid, p_to uuid, p_body text, p_meta jsonb)
returns bigint language plpgsql security definer set search_path = public as $$
declare mid bigint;
begin
  insert into public.c_messages (sender_id, recipient_id, kind, body, meta)
    values (p_from, p_to, 'game', p_body, p_meta) returning id into mid;
  return mid;
end $$;

-- 3) Lancer la partie dans le chat quand les deux ont choisi leurs réponses.
create or replace function public.c_game_start(p_round bigint, p_by uuid)
returns void language plpgsql security definer set search_path = public as $$
declare r public.c_game_rounds; na text; nb text; other uuid;
begin
  select * into r from public.c_game_rounds where id = p_round for update;
  if not found or r.started or r.status <> 'playing' then return; end if;
  update public.c_game_rounds set started = true where id = r.id;
  select username into na from public.c_profiles where id = r.user_a;
  select username into nb from public.c_profiles where id = r.user_b;
  other := case when r.user_a = p_by then r.user_b else r.user_a end;
  perform public.c_game_post(p_by, other, '🎮 Vous avez choisi vos réponses ! Passons au jeu…',
    jsonb_build_object('t','intro','round',r.id,'ua',r.user_a,'ub',r.user_b,'na',na,'nb',nb));
  perform public.c_game_post(r.user_b, r.user_a, '🎮 Question 1/10',
    jsonb_build_object('t','card','round',r.id,'idx',0,'qid',r.qids[1],'who',r.user_a));
  perform public.c_game_post(r.user_a, r.user_b, '🎮 Question 1/10',
    jsonb_build_object('t','card','round',r.id,'idx',0,'qid',r.qids[1],'who',r.user_b));
  insert into public.c_notifications (to_id, title, body) values (other, '🎮 Me connais-tu ?', 'C''est parti, viens jouer !');
end $$;

-- 4) Enregistrer ses réponses (un choix de 0 à 3 par question).
create or replace function public.c_game_save_answers(p_round bigint, p_answers jsonb)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.c_game_rounds; i int; v text; n1 int; n2 int; pid uuid;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.c_game_rounds where id = p_round and (user_a = me or user_b = me) and status = 'playing';
  if not found then return json_build_object('status','closed'); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  if r.started or exists (select 1 from public.c_game_guesses g where g.round_id = r.id and g.guesser = pid) then
    return json_build_object('status','locked');
  end if;
  for i in 0..9 loop
    v := trim(coalesce(p_answers ->> i, ''));
    if v ~ '^[0-3]$' then
      insert into public.c_game_answers (round_id, user_id, idx, answer) values (r.id, me, i, v)
        on conflict (round_id, user_id, idx) do update set answer = excluded.answer;
    end if;
  end loop;
  select count(*) into n1 from public.c_game_answers where round_id = r.id and user_id = me;
  select count(*) into n2 from public.c_game_answers where round_id = r.id and user_id = pid;
  if n1 >= 10 and n2 >= 10 then perform public.c_game_start(r.id, me); end if;
  return public.c_game_state();
end $$;

-- 5) Deviner : une question à la fois, dans l'ordre. Le résultat et la question suivante arrivent dans le chat.
create or replace function public.c_game_guess(p_round bigint, p_idx int, p_guess text)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.c_game_rounds; pid uuid; ans text; good boolean; n1 int; n2 int;
        nme text; sme int; sa int; sb int; g text := trim(coalesce(p_guess, ''));
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.c_game_rounds where id = p_round and (user_a = me or user_b = me) and status = 'playing' for update;
  if not found then return json_build_object('status','closed'); end if;
  if not r.started then return json_build_object('status','not_ready'); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  select count(*) into n1 from public.c_game_guesses where round_id = r.id and guesser = me;
  if p_idx <> n1 or p_idx < 0 or p_idx > 9 or g !~ '^[0-3]$' then return json_build_object('status','already'); end if;
  select answer into ans from public.c_game_answers where round_id = r.id and user_id = pid and idx = p_idx;
  if ans is null then return json_build_object('status','not_ready'); end if;
  good := (g = ans);
  insert into public.c_game_guesses (round_id, guesser, idx, guess, ok) values (r.id, me, p_idx, g, good);
  n1 := n1 + 1;
  select username into nme from public.c_profiles where id = me;

  perform public.c_game_post(me, pid, case when good then '✅ Bonne réponse !' else '❌ Raté…' end,
    jsonb_build_object('t','result','round',r.id,'idx',p_idx,'qid',r.qids[p_idx+1],'who',me,'ok',good,'guess',g,'answer',ans));
  insert into public.c_notifications (to_id, title, body)
    values (pid, '🎮 Me connais-tu ?', '@' || nme || case when good then ' a trouvé ✓' else ' s''est trompé(e) ✗' end);

  if n1 < 10 then
    perform public.c_game_post(pid, me, '🎮 Question ' || (n1+1) || '/10',
      jsonb_build_object('t','card','round',r.id,'idx',n1,'qid',r.qids[n1+1],'who',me));
  else
    select count(*) into sme from public.c_game_guesses where round_id = r.id and guesser = me and ok;
    perform public.c_game_post(me, pid, '🏁 Terminé : ' || sme || '/10',
      jsonb_build_object('t','finish','round',r.id,'who',me,'score',sme));
  end if;

  select count(*) into n2 from public.c_game_guesses where round_id = r.id and guesser = pid;
  if n1 >= 10 and n2 >= 10 then
    update public.c_game_rounds set status = 'done' where id = r.id;
    select count(*) into sa from public.c_game_guesses where round_id = r.id and guesser = r.user_a and ok;
    select count(*) into sb from public.c_game_guesses where round_id = r.id and guesser = r.user_b and ok;
    perform public.c_game_post(me, pid, '🏆 Résultats : ' || sa || ' – ' || sb,
      jsonb_build_object('t','end','round',r.id,'ua',r.user_a,'ub',r.user_b,'sa',sa,'sb',sb,
        'na',(select username from public.c_profiles where id = r.user_a),
        'nb',(select username from public.c_profiles where id = r.user_b)));
  end if;
  return json_build_object('status','ok','ok',good,'answer',ans,'idx',p_idx);
end $$;

revoke all on function public.c_game_post(uuid, uuid, text, jsonb), public.c_game_start(bigint, uuid) from public, anon, authenticated;
revoke all on function public.c_game_save_answers(bigint, jsonb), public.c_game_guess(bigint, int, text) from public, anon;
grant execute on function public.c_game_save_answers(bigint, jsonb), public.c_game_guess(bigint, int, text) to authenticated;

-- ============================== jeux3 ==============================
-- Casque : « Me connais-tu ? » — la question suivante n'arrive que quand les DEUX ont répondu à la question en cours.
-- À exécuter dans Supabase → SQL Editor, APRÈS jeux.sql et jeux2.sql, un bloc à la fois. Peut être relancé sans danger.

-- 1) Publie la question suivante pour les deux joueurs (sans doublon).
create or replace function public.c_game_next_cards(p_round bigint, p_idx int)
returns void language plpgsql security definer set search_path = public as $$
declare r public.c_game_rounds; u uuid; o uuid;
begin
  select * into r from public.c_game_rounds where id = p_round;
  if not found or p_idx < 0 or p_idx > 9 then return; end if;
  foreach u in array array[r.user_a, r.user_b] loop
    o := case when u = r.user_a then r.user_b else r.user_a end;
    if not exists (
      select 1 from public.c_messages
      where kind = 'game' and meta ->> 't' = 'card' and (meta ->> 'round')::bigint = r.id
        and (meta ->> 'idx')::int = p_idx and meta ->> 'who' = u::text
    ) then
      perform public.c_game_post(o, u, '🎮 Question ' || (p_idx + 1) || '/10',
        jsonb_build_object('t','card','round',r.id,'idx',p_idx,'qid',r.qids[p_idx + 1],'who',u));
    end if;
  end loop;
end $$;

-- 2) Publie les résultats finaux.
create or replace function public.c_game_end(p_round bigint, p_by uuid, p_to uuid)
returns void language plpgsql security definer set search_path = public as $$
declare r public.c_game_rounds; sa int; sb int;
begin
  select * into r from public.c_game_rounds where id = p_round;
  update public.c_game_rounds set status = 'done' where id = r.id;
  select count(*) into sa from public.c_game_guesses where round_id = r.id and guesser = r.user_a and ok;
  select count(*) into sb from public.c_game_guesses where round_id = r.id and guesser = r.user_b and ok;
  perform public.c_game_post(p_by, p_to, '🏆 Résultats : ' || sa || ' – ' || sb,
    jsonb_build_object('t','end','round',r.id,'ua',r.user_a,'ub',r.user_b,'sa',sa,'sb',sb,
      'na',(select username from public.c_profiles where id = r.user_a),
      'nb',(select username from public.c_profiles where id = r.user_b)));
end $$;

-- 3) Deviner : la question suivante attend que l'autre ait répondu aussi.
create or replace function public.c_game_guess(p_round bigint, p_idx int, p_guess text)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.c_game_rounds; pid uuid; ans text; good boolean; n1 int; n2 int;
        nme text; sme int; g text := trim(coalesce(p_guess, ''));
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.c_game_rounds where id = p_round and (user_a = me or user_b = me) and status = 'playing' for update;
  if not found then return json_build_object('status','closed'); end if;
  if not r.started then return json_build_object('status','not_ready'); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  select count(*) into n1 from public.c_game_guesses where round_id = r.id and guesser = me;
  if p_idx <> n1 or p_idx < 0 or p_idx > 9 or g !~ '^[0-3]$' then return json_build_object('status','already'); end if;
  select answer into ans from public.c_game_answers where round_id = r.id and user_id = pid and idx = p_idx;
  if ans is null then return json_build_object('status','not_ready'); end if;
  good := (g = ans);
  insert into public.c_game_guesses (round_id, guesser, idx, guess, ok) values (r.id, me, p_idx, g, good);
  n1 := n1 + 1;
  select username into nme from public.c_profiles where id = me;
  perform public.c_game_post(me, pid, case when good then '✅ Bonne réponse !' else '❌ Raté…' end,
    jsonb_build_object('t','result','round',r.id,'idx',p_idx,'qid',r.qids[p_idx+1],'who',me,'ok',good,'guess',g,'answer',ans));
  insert into public.c_notifications (to_id, title, body)
    values (pid, '🎮 Me connais-tu ?', '@' || nme || case when good then ' a trouvé ✓' else ' s''est trompé(e) ✗' end);
  select count(*) into n2 from public.c_game_guesses where round_id = r.id and guesser = pid;
  if n1 >= 10 then
    select count(*) into sme from public.c_game_guesses where round_id = r.id and guesser = me and ok;
    perform public.c_game_post(me, pid, '🏁 Terminé : ' || sme || '/10',
      jsonb_build_object('t','finish','round',r.id,'who',me,'score',sme));
  elsif n2 >= n1 then
    perform public.c_game_next_cards(r.id, n1);
  end if;
  if n1 >= 10 and n2 >= 10 then perform public.c_game_end(r.id, me, pid); end if;
  return json_build_object('status','ok','ok',good,'answer',ans,'idx',p_idx);
end $$;

revoke all on function public.c_game_next_cards(bigint, int), public.c_game_end(bigint, uuid, uuid) from public, anon, authenticated;
revoke all on function public.c_game_guess(bigint, int, text) from public, anon;
grant execute on function public.c_game_guess(bigint, int, text) to authenticated;

-- ============================== historique ==============================
-- Casque : l'historique du chat est conservé quand une amitié prend fin, et revient si elle est rétablie.
-- À exécuter dans Supabase → SQL Editor, un bloc à la fois (séparés par les lignes « -- ===== BLOC n ===== »). Peut être relancé sans danger.

-- ===== BLOC 1 : on ne voit que la conversation avec son partenaire actuel =====
create or replace function public.c_my_partner()
returns uuid language sql stable security definer set search_path = public as $$
  select partner_id from public.c_profiles where id = auth.uid()
$$;
revoke all on function public.c_my_partner() from public, anon;
grant execute on function public.c_my_partner() to authenticated;

drop policy if exists "messages visibles par les deux personnes" on public.c_messages;
create policy "messages visibles par les deux personnes" on public.c_messages
  for select to authenticated using (
    (sender_id = auth.uid() and not del_sender and recipient_id = public.c_my_partner())
    or (recipient_id = auth.uid() and not del_recipient and sender_id = public.c_my_partner())
  );

-- ===== BLOC 2 : « tout lu » ne touche que la conversation en cours =====
create or replace function public.c_mark_read()
returns json language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_messages set read_at = now()
    where recipient_id = auth.uid() and read_at is null and sender_id = public.c_my_partner();
  get diagnostics n = row_count;
  return json_build_object('status', 'ok', 'count', n);
end $$;

-- ===== BLOC 3 : retirer son partenaire ne supprime plus la conversation =====
create or replace function public.c_remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  update public.c_game_rounds set status = 'cancelled'
    where status in ('invite', 'playing') and user_a in (me.id, me.partner_id) and user_b in (me.id, me.partner_id);
  update public.c_friendship_log set ended_at = now(), ended_by = '@' || me.username
    where ended_at is null and ((a_id = me.id and b_id = me.partner_id) or (a_id = me.partner_id and b_id = me.id));
  update public.c_profiles
    set partner_id = null, partner_name = null, partner_phone = null, linked_at = null,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id in (me.id, me.partner_id);
  insert into public.c_notifications (to_id, title, body)
    values (me.partner_id, 'Partenaire retiré', '@' || me.username || ' a mis fin au lien. Tu peux ajouter quelqu''un d''autre.');
  return json_build_object('status', 'ok');
end $$;

-- ===== BLOC 5 : le jeu ne reprend que la partie avec le partenaire actuel =====
create or replace function public.c_game_state()
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.c_game_rounds; pid uuid; mine int; theirs int; mg int; tg int; res json;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.c_game_rounds
    where (user_a = me or user_b = me) and status in ('invite','playing','done')
      and (case when user_a = me then user_b else user_a end) = (select partner_id from public.c_profiles where id = me)
    order by id desc limit 1;
  if not found then return json_build_object('status','ok','round',null); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  select count(*) into mine from public.c_game_answers where round_id = r.id and user_id = me;
  select count(*) into theirs from public.c_game_answers where round_id = r.id and user_id = pid;
  select count(*) into mg from public.c_game_guesses where round_id = r.id and guesser = me;
  select count(*) into tg from public.c_game_guesses where round_id = r.id and guesser = pid;
  select json_build_object(
    'status','ok',
    'round', json_build_object(
      'id', r.id, 'state', r.status, 'by_me', r.user_a = me, 'qids', r.qids,
      'my_answers', mine, 'their_answers', theirs, 'my_guesses', mg, 'their_guesses', tg,
      'my_answer_list', (select coalesce(json_agg(json_build_object('idx', idx, 'answer', answer) order by idx), '[]'::json)
                         from public.c_game_answers where round_id = r.id and user_id = me),
      'my_results', (select coalesce(json_agg(json_build_object(
                        'idx', g.idx, 'guess', g.guess, 'ok', g.ok,
                        'answer', (select a.answer from public.c_game_answers a where a.round_id = r.id and a.user_id = pid and a.idx = g.idx)
                      ) order by g.idx), '[]'::json)
                     from public.c_game_guesses g where g.round_id = r.id and g.guesser = me),
      'their_results', (select coalesce(json_agg(json_build_object(
                        'idx', g.idx, 'guess', g.guess, 'ok', g.ok,
                        'answer', (select a.answer from public.c_game_answers a where a.round_id = r.id and a.user_id = me and a.idx = g.idx)
                      ) order by g.idx), '[]'::json)
                     from public.c_game_guesses g where g.round_id = r.id and g.guesser = pid)
    )) into res;
  return res;
end $$;

-- ============================== ephemere ==============================
-- Casque : messages éphémères (vue unique) + messages programmés.
-- À exécuter dans Supabase → SQL Editor, APRÈS chat.sql, video.sql et historique.sql. Peut être relancé sans danger.

-- ===== BLOC 1 : tables =====
-- Le contenu d'un message éphémère n'est PAS dans la table des messages : il est gardé ici,
-- illisible directement, et ne sort qu'une seule fois par once_open().
create table if not exists public.c_once_secrets (
  msg_id bigint primary key references public.c_messages(id) on delete cascade,
  body text,
  media text
);
alter table public.c_once_secrets enable row level security;
revoke all on public.c_once_secrets from anon, authenticated;

create table if not exists public.c_scheduled_messages (
  id bigint generated always as identity primary key,
  sender_id uuid not null references public.c_profiles(id) on delete cascade,
  kind text not null,
  body text,
  media text,
  meta jsonb,
  once boolean not null default false,
  send_at timestamptz not null,
  created_at timestamptz not null default now()
);
create index if not exists c_scheduled_due_idx on public.c_scheduled_messages (send_at);
alter table public.c_scheduled_messages enable row level security;
revoke all on public.c_scheduled_messages from anon, authenticated;

-- Les photos peuvent être grosses (pleine définition du capteur) : 50 Mo par fichier.
update storage.buckets set file_size_limit = 52428800 where id = 'chat';

-- ===== BLOC 2 : fonctions internes =====
create or replace function public.c__msg_ok(p_uid uuid, p_kind text, p_body text, p_media text, p_meta jsonb)
returns boolean language plpgsql immutable as $$
declare b text := nullif(btrim(coalesce(p_body, '')), '');
begin
  if p_meta is not null and pg_column_size(p_meta) > 2000 then return false; end if;
  if b is not null and length(b) > 4000 then return false; end if;
  if p_kind = 'text' then return b is not null;
  elsif p_kind = 'sticker' then return b is not null and b ~ '^[a-z0-9_]{1,40}$';
  elsif p_kind = 'gif' then return p_media is not null and length(p_media) <= 480 and p_media ~ '^https://[^[:space:]]+$';
  elsif p_kind in ('image', 'voice', 'video') then return p_media is not null and p_media ~ ('^' || p_uid::text || '/[A-Za-z0-9_.-]{1,80}$');
  end if;
  return false;
end $$;

create or replace function public.c__insert_msg(p_uid uuid, p_kind text, p_body text, p_media text, p_meta jsonb, p_once boolean)
returns bigint language plpgsql security definer set search_path = public as $$
declare me public.c_profiles; b text := nullif(btrim(coalesce(p_body, '')), ''); mid bigint; preview text;
begin
  select * into me from public.c_profiles where id = p_uid;
  if not found or me.partner_id is null then return null; end if;
  if p_once then
    insert into public.c_messages (sender_id, recipient_id, kind, body, media, meta)
      values (me.id, me.partner_id, p_kind, null, null, coalesce(p_meta, '{}'::jsonb) || '{"once":true}'::jsonb)
      returning id into mid;
    insert into public.c_once_secrets (msg_id, body, media) values (mid, b, p_media);
    preview := '🔥 Message éphémère';
  else
    insert into public.c_messages (sender_id, recipient_id, kind, body, media, meta)
      values (me.id, me.partner_id, p_kind, b, p_media, p_meta) returning id into mid;
    preview := case p_kind
      when 'text' then left(b, 140) when 'gif' then '🎞️ GIF' when 'sticker' then '🎧 Sticker'
      when 'image' then case when b is null then '📷 Photo' else '📷 ' || left(b, 120) end
      when 'video' then '🎥 Vidéo' else '🎤 Message vocal' end;
  end if;
  insert into public.c_notifications (to_id, title, body) values (me.partner_id, '@' || me.username, preview);
  return mid;
end $$;
revoke all on function public.c__msg_ok(uuid, text, text, text, jsonb), public.c__insert_msg(uuid, text, text, text, jsonb, boolean) from public, anon, authenticated;

-- ===== BLOC 3 : envoyer / ouvrir un message éphémère =====
create or replace function public.c_send_once(p_kind text, p_body text default null, p_media text default null, p_meta jsonb default null)
returns json language plpgsql security definer set search_path = public as $$
declare mid bigint;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_kind not in ('text', 'image', 'video') then return json_build_object('status', 'invalid'); end if;
  if not public.c__msg_ok(auth.uid(), p_kind, p_body, p_media, p_meta) then return json_build_object('status', 'invalid'); end if;
  mid := public.c__insert_msg(auth.uid(), p_kind, p_body, p_media, p_meta, true);
  if mid is null then return json_build_object('status', 'no_partner'); end if;
  return json_build_object('status', 'ok', 'id', mid);
end $$;

-- Le destinataire ouvre : le contenu sort UNE fois, puis plus jamais.
create or replace function public.c_once_open(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
declare m public.c_messages; s public.c_once_secrets;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into m from public.c_messages where id = p_id and recipient_id = auth.uid() and (meta->>'once') = 'true' and deleted_at is null for update;
  if not found then return json_build_object('status', 'not_found'); end if;
  if (m.meta->>'opened') = 'true' then return json_build_object('status', 'gone'); end if;
  select * into s from public.c_once_secrets where msg_id = m.id;
  if not found then return json_build_object('status', 'gone'); end if;
  update public.c_messages set meta = m.meta || jsonb_build_object('opened', true, 'opened_at', now()) where id = m.id;
  return json_build_object('status', 'ok', 'kind', m.kind, 'body', s.body, 'media', s.media, 'meta', m.meta);
end $$;

-- Le destinataire a fini de regarder : on efface le fichier et le secret.
create or replace function public.c_once_done(p_id bigint)
returns json language plpgsql security definer set search_path = public, storage as $$
declare s public.c_once_secrets;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select o.* into s from public.c_once_secrets o join public.c_messages m on m.id = o.msg_id
    where o.msg_id = p_id and m.recipient_id = auth.uid() and (m.meta->>'opened') = 'true';
  if not found then return json_build_object('status', 'ok'); end if;
  if s.media is not null then delete from storage.objects where bucket_id = 'chat' and name = s.media; end if;
  delete from public.c_once_secrets where msg_id = p_id;
  return json_build_object('status', 'ok');
end $$;

-- Un message éphémère ne se modifie pas.
create or replace function public.c_edit_message(p_id bigint, p_body text)
returns json language plpgsql security definer set search_path = public as $$
declare m public.c_messages; b text := trim(coalesce(p_body, ''));
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if b = '' or length(b) > 4000 then return json_build_object('status', 'invalid'); end if;
  select * into m from public.c_messages where id = p_id and sender_id = auth.uid() and kind = 'text' and deleted_at is null and coalesce(meta->>'once', '') <> 'true';
  if not found then return json_build_object('status', 'not_found'); end if;
  update public.c_messages set body = b, meta = coalesce(meta, '{}'::jsonb) || '{"edited":true}'::jsonb where id = m.id;
  return json_build_object('status', 'ok');
end $$;

-- ===== BLOC 4 : messages programmés =====
create or replace function public.c_schedule_message(p_kind text, p_body text, p_media text, p_meta jsonb, p_once boolean, p_at timestamptz)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles; id_ bigint;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  if p_at is null or p_at < now() + interval '5 seconds' or p_at > now() + interval '1 year' then return json_build_object('status', 'bad_time'); end if;
  if p_once and p_kind not in ('text', 'image', 'video') then return json_build_object('status', 'invalid'); end if;
  if not public.c__msg_ok(me.id, p_kind, p_body, p_media, p_meta) then return json_build_object('status', 'invalid'); end if;
  if (select count(*) from public.c_scheduled_messages where sender_id = me.id) >= 100 then return json_build_object('status', 'too_many'); end if;
  insert into public.c_scheduled_messages (sender_id, kind, body, media, meta, once, send_at)
    values (me.id, p_kind, nullif(btrim(coalesce(p_body, '')), ''), p_media, p_meta, coalesce(p_once, false), p_at) returning id into id_;
  return json_build_object('status', 'ok', 'id', id_);
end $$;

create or replace function public.c_scheduled_list()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((select json_agg(json_build_object('id', id, 'kind', kind, 'body', body, 'meta', meta, 'once', once, 'send_at', send_at) order by send_at)
    from public.c_scheduled_messages where sender_id = auth.uid()), '[]'::json);
end $$;

create or replace function public.c_scheduled_cancel(p_id bigint)
returns json language plpgsql security definer set search_path = public, storage as $$
declare s public.c_scheduled_messages;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  delete from public.c_scheduled_messages where id = p_id and sender_id = auth.uid() returning * into s;
  if s.media is not null and s.kind in ('image', 'video', 'voice') then
    delete from storage.objects where bucket_id = 'chat' and name = s.media;
  end if;
  return json_build_object('status', 'ok');
end $$;

-- Envoie ce qui est arrivé à l'heure. Appelée chaque minute par pg_cron, et aussi par les applis ouvertes.
create or replace function public.c_deliver_scheduled()
returns int language plpgsql security definer set search_path = public as $$
declare r public.c_scheduled_messages; n int := 0;
begin
  for r in select * from public.c_scheduled_messages where send_at <= now() order by send_at for update skip locked limit 200 loop
    perform public.c__insert_msg(r.sender_id, r.kind, r.body, r.media, r.meta, r.once);
    delete from public.c_scheduled_messages where id = r.id;
    n := n + 1;
  end loop;
  return n;
end $$;

revoke all on function public.c_send_once(text, text, text, jsonb), public.c_once_open(bigint), public.c_once_done(bigint),
  public.c_schedule_message(text, text, text, jsonb, boolean, timestamptz), public.c_scheduled_list(),
  public.c_scheduled_cancel(bigint), public.c_deliver_scheduled() from public, anon;
grant execute on function public.c_send_once(text, text, text, jsonb), public.c_once_open(bigint), public.c_once_done(bigint),
  public.c_schedule_message(text, text, text, jsonb, boolean, timestamptz), public.c_scheduled_list(),
  public.c_scheduled_cancel(bigint), public.c_deliver_scheduled() to authenticated;

-- ===== BLOC 5 : horloge (envoi à l'heure même si personne n'ouvre l'appli) =====
do $$
begin
  create extension if not exists pg_cron;
  perform cron.unschedule('casque-programmes') from cron.job where jobname = 'casque-programmes';
  perform cron.schedule('casque-programmes', '* * * * *', 'select public.c_deliver_scheduled()');
exception when others then
  raise notice 'pg_cron indisponible : les messages programmés partiront à l''ouverture de l''appli.';
end $$;

-- ============================== appels ==============================
-- Casque : appels audio et vidéo (WebRTC). À exécuter dans Supabase → SQL Editor, un bloc à la fois.

-- 1) Les petits messages échangés pour établir l'appel (sonnerie, accepter, raccrocher, infos de connexion).
create table if not exists public.c_call_signals (
  id bigserial primary key,
  from_id uuid not null,
  to_id uuid not null,
  kind text not null,
  payload jsonb,
  created_at timestamptz not null default now()
);
create index if not exists c_call_signals_to_idx on public.c_call_signals (to_id, id);
alter table public.c_call_signals enable row level security;

-- 2) Envoyer un signal à mon partenaire.
create or replace function public.c_call_send(p_kind text, p_payload jsonb default null)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles; sid bigint;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_kind not in ('ring', 'accept', 'reject', 'hangup', 'offer', 'answer', 'ice') then return json_build_object('status', 'invalid'); end if;
  if p_payload is not null and pg_column_size(p_payload) > 12000 then return json_build_object('status', 'invalid'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  insert into public.c_call_signals (from_id, to_id, kind, payload) values (me.id, me.partner_id, p_kind, p_payload) returning id into sid;
  if p_kind = 'ring' then
    insert into public.c_notifications (to_id, title, body)
      values (me.partner_id, '📞 @' || me.username, case when coalesce((p_payload ->> 'video')::boolean, false) then 'Appel vidéo entrant…' else 'Appel entrant…' end);
  end if;
  delete from public.c_call_signals where created_at < now() - interval '1 hour';
  return json_build_object('status', 'ok', 'id', sid);
end $$;

-- 3) Lire les signaux reçus après l'identifiant donné (et pas plus vieux de 90 secondes).
create or replace function public.c_call_poll(p_after bigint default 0)
returns json language sql security definer set search_path = public as $$
  select coalesce(json_agg(json_build_object('id', id, 'kind', kind, 'payload', payload) order by id), '[]'::json)
  from public.c_call_signals
  where to_id = auth.uid() and id > coalesce(p_after, 0) and created_at > now() - interval '90 seconds';
$$;

revoke all on function public.c_call_send(text, jsonb), public.c_call_poll(bigint) from public, anon;
grant execute on function public.c_call_send(text, jsonb), public.c_call_poll(bigint) to authenticated;

-- ============================== nowpic ==============================
-- Casque : Now pic (une photo en même temps, quand l'autre est d'accord).
-- À exécuter dans Supabase → SQL Editor. Peut être relancé sans danger.

create table if not exists public.c_nowpic_sessions (
  id bigint generated always as identity primary key,
  requester uuid not null references public.c_profiles(id) on delete cascade,
  partner uuid not null references public.c_profiles(id) on delete cascade,
  state text not null default 'pending' check (state in ('pending', 'go', 'declined', 'expired')),
  created_at timestamptz not null default now(),
  go_at timestamptz,
  answered_at timestamptz
);
alter table public.c_nowpic_sessions enable row level security;
revoke all on public.c_nowpic_sessions from anon, authenticated;

create or replace function public.c__nowpic_expire() returns void language sql security definer set search_path = public as $$
  update public.c_nowpic_sessions set state = 'expired'
   where (state = 'pending' and created_at < now() - interval '2 minutes')
      or (state = 'go' and go_at < now() - interval '4 minutes');
$$;
revoke all on function public.c__nowpic_expire() from public, anon, authenticated;

create or replace function public.c_nowpic_request()
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles; s public.c_nowpic_sessions;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  perform public.c__nowpic_expire();
  select * into s from public.c_nowpic_sessions
    where state in ('pending', 'go') and ((requester = me.id and partner = me.partner_id) or (requester = me.partner_id and partner = me.id))
    order by id desc limit 1;
  if found then return json_build_object('status', 'ok', 'id', s.id); end if;
  insert into public.c_nowpic_sessions (requester, partner) values (me.id, me.partner_id) returning * into s;
  insert into public.c_notifications (to_id, title, body)
    values (me.partner_id, '@' || me.username, '📸 Now Pic ! Réponds Oui pour prendre une photo en même temps.');
  return json_build_object('status', 'ok', 'id', s.id);
end $$;

create or replace function public.c_nowpic_state()
returns json language plpgsql security definer set search_path = public as $$
declare s public.c_nowpic_sessions;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  perform public.c__nowpic_expire();
  select * into s from public.c_nowpic_sessions
    where (requester = auth.uid() or partner = auth.uid())
      and (state in ('pending', 'go') or (state = 'declined' and requester = auth.uid() and answered_at > now() - interval '30 seconds'))
    order by id desc limit 1;
  if not found then return json_build_object('status', 'ok', 'session', null); end if;
  return json_build_object('status', 'ok', 'session', json_build_object(
    'id', s.id, 'state', s.state, 'by_me', s.requester = auth.uid(),
    'secs', floor(extract(epoch from now() - coalesce(s.go_at, s.created_at)))));
end $$;

create or replace function public.c_nowpic_respond(p_id bigint, p_accept boolean)
returns json language plpgsql security definer set search_path = public as $$
declare s public.c_nowpic_sessions; me public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  select * into s from public.c_nowpic_sessions where id = p_id and partner = auth.uid() and state = 'pending' for update;
  if not found then return json_build_object('status', 'not_found'); end if;
  if p_accept then
    update public.c_nowpic_sessions set state = 'go', go_at = now(), answered_at = now() where id = s.id;
    insert into public.c_notifications (to_id, title, body) values (s.requester, '@' || me.username, '📸 C''est parti ! Ouvre l''appli pour ton Now Pic.');
  else
    update public.c_nowpic_sessions set state = 'declined', answered_at = now() where id = s.id;
  end if;
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_nowpic_cancel(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_nowpic_sessions set state = 'expired' where id = p_id and requester = auth.uid() and state = 'pending';
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.c_nowpic_request(), public.c_nowpic_state(), public.c_nowpic_respond(bigint, boolean), public.c_nowpic_cancel(bigint) from public, anon;
grant execute on function public.c_nowpic_request(), public.c_nowpic_state(), public.c_nowpic_respond(bigint, boolean), public.c_nowpic_cancel(bigint) to authenticated;

-- ============================== rappels ==============================
-- ===== ÉTAPE 1 : la table =====
create table if not exists public.c_reminders (
  id bigint generated always as identity primary key,
  creator uuid not null references public.c_profiles(id) on delete cascade,
  other uuid not null references public.c_profiles(id) on delete cascade,
  title text not null,
  who text not null check (who in ('creator', 'other', 'both')),
  due_at timestamptz not null,
  fired boolean not null default false,
  ack_creator boolean not null default false,
  ack_other boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.c_reminders enable row level security;
revoke all on public.c_reminders from anon, authenticated;

-- ===== ÉTAPE 2 : créer et lister =====
create or replace function public.c_reminder_create(p_title text, p_who text, p_due timestamptz)
returns json language plpgsql security definer set search_path = public as $$
declare t text := left(trim(coalesce(p_title, '')), 200); pid uuid; w text; n int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if t = '' then return json_build_object('status', 'empty'); end if;
  select partner_id into pid from public.c_profiles where id = auth.uid();
  if pid is null then return json_build_object('status', 'no_partner'); end if;
  w := case p_who when 'me' then 'creator' when 'partner' then 'other' when 'both' then 'both' else null end;
  if w is null or p_due is null then return json_build_object('status', 'invalid'); end if;
  select count(*) into n from public.c_reminders where creator = auth.uid() and created_at > now() - interval '1 minute';
  if n >= 20 then return json_build_object('status', 'too_fast'); end if;
  insert into public.c_reminders (creator, other, title, who, due_at) values (auth.uid(), pid, t, w, p_due);
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_reminder_list()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((
    select json_agg(x order by x.due_at) from (
      select r.id, r.title, r.due_at, (r.due_at <= now()) as due, (r.creator = auth.uid()) as by_me,
        (select username from public.c_profiles where id = r.creator) as by_name,
        case when r.who = 'both' then 'both'
             when (r.who = 'creator') = (r.creator = auth.uid()) then 'me' else 'partner' end as target
      from public.c_reminders r
      where (r.creator = auth.uid() or r.other = auth.uid())
        and not (case when r.creator = auth.uid() then r.ack_creator else r.ack_other end)
        and exists (select 1 from public.c_profiles p
                    where p.id = auth.uid() and p.partner_id = case when r.creator = auth.uid() then r.other else r.creator end)
    ) x
  ), '[]'::json);
end $$;

-- ===== ÉTAPE 3 : valider, annuler, déclencher =====
create or replace function public.c_reminder_ack(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  update public.c_reminders set ack_creator = ack_creator or (creator = auth.uid()), ack_other = ack_other or (other = auth.uid())
    where id = p_id and (creator = auth.uid() or other = auth.uid());
  delete from public.c_reminders where ack_creator and ack_other;
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_reminder_cancel(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  delete from public.c_reminders where id = p_id and (creator = auth.uid() or other = auth.uid());
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_reminder_fire()
returns void language plpgsql security definer set search_path = public as $$
declare r record;
begin
  for r in update public.c_reminders set fired = true where not fired and due_at <= now() returning * loop
    insert into public.c_notifications (to_id, title, body) values (r.creator, '⏰ Rappel', r.title), (r.other, '⏰ Rappel', r.title);
  end loop;
end $$;

revoke all on function public.c_reminder_create(text, text, timestamptz), public.c_reminder_list(), public.c_reminder_ack(bigint),
  public.c_reminder_cancel(bigint), public.c_reminder_fire() from public, anon, authenticated;
grant execute on function public.c_reminder_create(text, text, timestamptz), public.c_reminder_list(), public.c_reminder_ack(bigint),
  public.c_reminder_cancel(bigint) to authenticated;

-- ===== ÉTAPE 4 : le réveil automatique (chaque minute) =====
create extension if not exists pg_cron;
select cron.schedule('reminders-fire', '* * * * *', 'select public.c_reminder_fire()');

-- ============================== plusieurs amis ==============================
-- Plusieurs amis, plusieurs conversations.
-- La conversation « ouverte » est simplement l'ami sélectionné (partner_id) : tout le reste (chat, photos,
-- appels, jeux…) fonctionne avec cet ami-là, et la sécurité ne montre que la conversation ouverte.

create table if not exists public.c_friends (
  user_id uuid not null references public.c_profiles(id) on delete cascade,
  friend_id uuid not null references public.c_profiles(id) on delete cascade,
  since timestamptz not null default now(),
  primary key (user_id, friend_id)
);
alter table public.c_friends enable row level security;
revoke all on public.c_friends from anon, authenticated;

-- Devenir amis (usage interne) : on garde la conversation ouverte actuelle, sauf si on n'en avait aucune.
create or replace function public.c__befriend(a uuid, b uuid)
returns void language plpgsql security definer set search_path = public as $$
declare na text; nb text; pa text; pb text;
begin
  select username, phone into na, pa from public.c_profiles where id = a;
  select username, phone into nb, pb from public.c_profiles where id = b;
  insert into public.c_friends (user_id, friend_id) values (a, b), (b, a) on conflict do nothing;
  update public.c_profiles set partner_id = b, partner_name = nb, partner_phone = pb where id = a and partner_id is null;
  update public.c_profiles set partner_id = a, partner_name = na, partner_phone = pa where id = b and partner_id is null;
  delete from public.c_requests where (from_id = a and to_id = b) or (from_id = b and to_id = a);
end $$;

-- Ajout d'un ami par pseudo ou par numéro de téléphone.
create or replace function public.c_send_request(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  q text := lower(trim(coalesce(p_username, '')));
  n text := regexp_replace(q, '^@', '');
  by_phone boolean := (q !~ '[a-z_]' and length(regexp_replace(q, '\D', '', 'g')) >= 8);
  me public.c_profiles;
  tgt public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not by_phone and n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.username is null then return json_build_object('status', 'no_username'); end if;
  if by_phone then
    select * into tgt from public.c_profiles where phone = public.c_norm_phone(q);
  else
    select * into tgt from public.c_profiles where username = n;
  end if;
  if not found then return json_build_object('status', 'not_found'); end if;
  if tgt.id = me.id then return json_build_object('status', 'self'); end if;
  if exists (select 1 from public.c_friends where user_id = me.id and friend_id = tgt.id) then
    return json_build_object('status', 'already_friends', 'name', tgt.username);
  end if;
  if exists (select 1 from public.c_requests where from_id = tgt.id and to_id = me.id) then
    perform public.c__befriend(me.id, tgt.id);
    insert into public.c_notifications (to_id, title, body)
      values (tgt.id, 'Nouvel ami', '@' || me.username || ' et toi êtes maintenant amis');
    return json_build_object('status', 'linked', 'name', tgt.username);
  end if;
  if exists (select 1 from public.c_requests where from_id = me.id and to_id = tgt.id) then
    return json_build_object('status', 'already_sent', 'name', tgt.username);
  end if;
  insert into public.c_requests (from_id, to_id, from_name, to_name) values (me.id, tgt.id, me.username, tgt.username);
  insert into public.c_notifications (to_id, title, body)
    values (tgt.id, 'Nouvelle demande', '@' || me.username || ' veut devenir ton ami');
  return json_build_object('status', 'sent', 'name', tgt.username);
end $$;

create or replace function public.c_respond_request(p_request_id bigint, p_accept boolean)
returns json language plpgsql security definer set search_path = public as $$
declare r public.c_requests;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into r from public.c_requests where id = p_request_id and to_id = auth.uid();
  if not found then return json_build_object('status', 'not_found'); end if;
  if not p_accept then
    delete from public.c_requests where id = r.id;
    return json_build_object('status', 'ok');
  end if;
  perform public.c__befriend(r.from_id, r.to_id);
  insert into public.c_notifications (to_id, title, body)
    values (r.from_id, 'Demande acceptée', '@' || r.to_name || ' a accepté ta demande');
  return json_build_object('status', 'ok');
end $$;

-- Ouvrir la conversation avec un ami.
create or replace function public.c_select_peer(p_id uuid)
returns json language plpgsql security definer set search_path = public as $$
declare f public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not exists (select 1 from public.c_friends where user_id = auth.uid() and friend_id = p_id) then
    return json_build_object('status', 'not_friends');
  end if;
  select * into f from public.c_profiles where id = p_id;
  update public.c_profiles
    set partner_id = f.id, partner_name = f.username, partner_phone = f.phone,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

-- Fermer la conversation ouverte (retour à la liste d'amis).
create or replace function public.c_close_peer()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles set partner_id = null, partner_name = null, partner_phone = null where id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

-- Retirer un ami : la conversation est conservée et reviendra si vous redevenez amis.
create or replace function public.c_remove_friend(p_id uuid)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  delete from public.c_friends where (user_id = me.id and friend_id = p_id) or (user_id = p_id and friend_id = me.id);
  update public.c_profiles set partner_id = null, partner_name = null, partner_phone = null
    where id in (me.id, p_id) and partner_id in (me.id, p_id);
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare pid uuid;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select partner_id into pid from public.c_profiles where id = auth.uid();
  if pid is null then return json_build_object('status', 'no_partner'); end if;
  return public.c_remove_friend(pid);
end $$;

-- Liste des amis, avec le dernier message et le nombre de messages non lus.
create or replace function public.c_friends_list()
returns json language plpgsql stable security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return '[]'::json; end if;
  return coalesce((
    select json_agg(x order by x.last_at desc nulls last, x.username) from (
      select p.id, p.username, p.phone,
             (p.id = (select partner_id from public.c_profiles where id = me)) as selected,
             (select count(*) from public.c_messages m
                where m.sender_id = p.id and m.recipient_id = me and m.read_at is null and not m.del_recipient and m.deleted_at is null) as unread,
             lm.kind as last_kind, lm.body as last_body, lm.created_at as last_at, (lm.sender_id = me) as last_mine
        from public.c_friends f
        join public.c_profiles p on p.id = f.friend_id
        left join lateral (
          select m.kind, m.body, m.created_at, m.sender_id from public.c_messages m
           where ((m.sender_id = me and m.recipient_id = p.id and not m.del_sender)
               or (m.sender_id = p.id and m.recipient_id = me and not m.del_recipient))
             and m.deleted_at is null
           order by m.id desc limit 1) lm on true
       where f.user_id = me
    ) x), '[]'::json);
end $$;

revoke all on function public.c__befriend(uuid, uuid), public.c_send_request(text), public.c_respond_request(bigint, boolean),
  public.c_select_peer(uuid), public.c_close_peer(), public.c_remove_friend(uuid), public.c_remove_partner(),
  public.c_friends_list() from public, anon, authenticated;
grant execute on function public.c_send_request(text), public.c_respond_request(bigint, boolean),
  public.c_select_peer(uuid), public.c_close_peer(), public.c_remove_friend(uuid), public.c_remove_partner(),
  public.c_friends_list() to authenticated;
