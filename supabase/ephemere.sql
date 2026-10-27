-- Tu me manques : messages éphémères (vue unique) + messages programmés.
-- À exécuter dans Supabase → SQL Editor, APRÈS chat.sql, video.sql et historique.sql. Peut être relancé sans danger.

-- ===== BLOC 1 : tables =====
-- Le contenu d'un message éphémère n'est PAS dans la table des messages : il est gardé ici,
-- illisible directement, et ne sort qu'une seule fois par once_open().
create table if not exists public.once_secrets (
  msg_id bigint primary key references public.messages(id) on delete cascade,
  body text,
  media text
);
alter table public.once_secrets enable row level security;
revoke all on public.once_secrets from anon, authenticated;

create table if not exists public.scheduled_messages (
  id bigint generated always as identity primary key,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  kind text not null,
  body text,
  media text,
  meta jsonb,
  once boolean not null default false,
  send_at timestamptz not null,
  created_at timestamptz not null default now()
);
create index if not exists scheduled_due_idx on public.scheduled_messages (send_at);
alter table public.scheduled_messages enable row level security;
revoke all on public.scheduled_messages from anon, authenticated;

-- Les photos peuvent être grosses (pleine définition du capteur) : 50 Mo par fichier.
update storage.buckets set file_size_limit = 52428800 where id = 'chat';

-- ===== BLOC 2 : fonctions internes =====
create or replace function public._msg_ok(p_uid uuid, p_kind text, p_body text, p_media text, p_meta jsonb)
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

create or replace function public._insert_msg(p_uid uuid, p_kind text, p_body text, p_media text, p_meta jsonb, p_once boolean)
returns bigint language plpgsql security definer set search_path = public as $$
declare me public.profiles; b text := nullif(btrim(coalesce(p_body, '')), ''); mid bigint; preview text;
begin
  select * into me from public.profiles where id = p_uid;
  if not found or me.partner_id is null then return null; end if;
  if p_once then
    insert into public.messages (sender_id, recipient_id, kind, body, media, meta)
      values (me.id, me.partner_id, p_kind, null, null, coalesce(p_meta, '{}'::jsonb) || '{"once":true}'::jsonb)
      returning id into mid;
    insert into public.once_secrets (msg_id, body, media) values (mid, b, p_media);
    preview := '🔥 Message éphémère';
  else
    insert into public.messages (sender_id, recipient_id, kind, body, media, meta)
      values (me.id, me.partner_id, p_kind, b, p_media, p_meta) returning id into mid;
    preview := case p_kind
      when 'text' then left(b, 140) when 'gif' then '🎞️ GIF' when 'sticker' then '💗 Sticker'
      when 'image' then case when b is null then '📷 Photo' else '📷 ' || left(b, 120) end
      when 'video' then '🎥 Vidéo' else '🎤 Message vocal' end;
  end if;
  insert into public.notifications (to_id, title, body) values (me.partner_id, '@' || me.username, preview);
  return mid;
end $$;
revoke all on function public._msg_ok(uuid, text, text, text, jsonb), public._insert_msg(uuid, text, text, text, jsonb, boolean) from public, anon, authenticated;

-- ===== BLOC 3 : envoyer / ouvrir un message éphémère =====
create or replace function public.send_once(p_kind text, p_body text default null, p_media text default null, p_meta jsonb default null)
returns json language plpgsql security definer set search_path = public as $$
declare mid bigint;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_kind not in ('text', 'image', 'video') then return json_build_object('status', 'invalid'); end if;
  if not public._msg_ok(auth.uid(), p_kind, p_body, p_media, p_meta) then return json_build_object('status', 'invalid'); end if;
  mid := public._insert_msg(auth.uid(), p_kind, p_body, p_media, p_meta, true);
  if mid is null then return json_build_object('status', 'no_partner'); end if;
  return json_build_object('status', 'ok', 'id', mid);
end $$;

-- Le destinataire ouvre : le contenu sort UNE fois, puis plus jamais.
create or replace function public.once_open(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
declare m public.messages; s public.once_secrets;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into m from public.messages where id = p_id and recipient_id = auth.uid() and (meta->>'once') = 'true' and deleted_at is null for update;
  if not found then return json_build_object('status', 'not_found'); end if;
  if (m.meta->>'opened') = 'true' then return json_build_object('status', 'gone'); end if;
  select * into s from public.once_secrets where msg_id = m.id;
  if not found then return json_build_object('status', 'gone'); end if;
  update public.messages set meta = m.meta || jsonb_build_object('opened', true, 'opened_at', now()) where id = m.id;
  return json_build_object('status', 'ok', 'kind', m.kind, 'body', s.body, 'media', s.media, 'meta', m.meta);
end $$;

-- Le destinataire a fini de regarder : on efface le fichier et le secret.
create or replace function public.once_done(p_id bigint)
returns json language plpgsql security definer set search_path = public, storage as $$
declare s public.once_secrets;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select o.* into s from public.once_secrets o join public.messages m on m.id = o.msg_id
    where o.msg_id = p_id and m.recipient_id = auth.uid() and (m.meta->>'opened') = 'true';
  if not found then return json_build_object('status', 'ok'); end if;
  if s.media is not null then delete from storage.objects where bucket_id = 'chat' and name = s.media; end if;
  delete from public.once_secrets where msg_id = p_id;
  return json_build_object('status', 'ok');
end $$;

-- Un message éphémère ne se modifie pas.
create or replace function public.edit_message(p_id bigint, p_body text)
returns json language plpgsql security definer set search_path = public as $$
declare m public.messages; b text := trim(coalesce(p_body, ''));
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if b = '' or length(b) > 4000 then return json_build_object('status', 'invalid'); end if;
  select * into m from public.messages where id = p_id and sender_id = auth.uid() and kind = 'text' and deleted_at is null and coalesce(meta->>'once', '') <> 'true';
  if not found then return json_build_object('status', 'not_found'); end if;
  update public.messages set body = b, meta = coalesce(meta, '{}'::jsonb) || '{"edited":true}'::jsonb where id = m.id;
  return json_build_object('status', 'ok');
end $$;

-- ===== BLOC 4 : messages programmés =====
create or replace function public.schedule_message(p_kind text, p_body text, p_media text, p_meta jsonb, p_once boolean, p_at timestamptz)
returns json language plpgsql security definer set search_path = public as $$
declare me public.profiles; id_ bigint;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  if p_at is null or p_at < now() + interval '5 seconds' or p_at > now() + interval '1 year' then return json_build_object('status', 'bad_time'); end if;
  if p_once and p_kind not in ('text', 'image', 'video') then return json_build_object('status', 'invalid'); end if;
  if not public._msg_ok(me.id, p_kind, p_body, p_media, p_meta) then return json_build_object('status', 'invalid'); end if;
  if (select count(*) from public.scheduled_messages where sender_id = me.id) >= 100 then return json_build_object('status', 'too_many'); end if;
  insert into public.scheduled_messages (sender_id, kind, body, media, meta, once, send_at)
    values (me.id, p_kind, nullif(btrim(coalesce(p_body, '')), ''), p_media, p_meta, coalesce(p_once, false), p_at) returning id into id_;
  return json_build_object('status', 'ok', 'id', id_);
end $$;

create or replace function public.scheduled_list()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((select json_agg(json_build_object('id', id, 'kind', kind, 'body', body, 'meta', meta, 'once', once, 'send_at', send_at) order by send_at)
    from public.scheduled_messages where sender_id = auth.uid()), '[]'::json);
end $$;

create or replace function public.scheduled_cancel(p_id bigint)
returns json language plpgsql security definer set search_path = public, storage as $$
declare s public.scheduled_messages;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  delete from public.scheduled_messages where id = p_id and sender_id = auth.uid() returning * into s;
  if s.media is not null and s.kind in ('image', 'video', 'voice') then
    delete from storage.objects where bucket_id = 'chat' and name = s.media;
  end if;
  return json_build_object('status', 'ok');
end $$;

-- Envoie ce qui est arrivé à l'heure. Appelée chaque minute par pg_cron, et aussi par les applis ouvertes.
create or replace function public.deliver_scheduled()
returns int language plpgsql security definer set search_path = public as $$
declare r public.scheduled_messages; n int := 0;
begin
  for r in select * from public.scheduled_messages where send_at <= now() order by send_at for update skip locked limit 200 loop
    perform public._insert_msg(r.sender_id, r.kind, r.body, r.media, r.meta, r.once);
    delete from public.scheduled_messages where id = r.id;
    n := n + 1;
  end loop;
  return n;
end $$;

revoke all on function public.send_once(text, text, text, jsonb), public.once_open(bigint), public.once_done(bigint),
  public.schedule_message(text, text, text, jsonb, boolean, timestamptz), public.scheduled_list(),
  public.scheduled_cancel(bigint), public.deliver_scheduled() from public, anon;
grant execute on function public.send_once(text, text, text, jsonb), public.once_open(bigint), public.once_done(bigint),
  public.schedule_message(text, text, text, jsonb, boolean, timestamptz), public.scheduled_list(),
  public.scheduled_cancel(bigint), public.deliver_scheduled() to authenticated;

-- ===== BLOC 5 : horloge (envoi à l'heure même si personne n'ouvre l'appli) =====
do $$
begin
  create extension if not exists pg_cron;
  perform cron.unschedule('tmm-programmes') from cron.job where jobname = 'tmm-programmes';
  perform cron.schedule('tmm-programmes', '* * * * *', 'select public.deliver_scheduled()');
exception when others then
  raise notice 'pg_cron indisponible : les messages programmés partiront à l''ouverture de l''appli.';
end $$;
