-- Tu me manques : base de données Supabase.
-- Remplace PROJECT_REF et WEBHOOK_SECRET (voir plus bas), puis colle tout dans « SQL Editor » → Run.

create extension if not exists pg_net;

-- ========== TABLES ==========
create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique,
  partner_id uuid references public.profiles(id) on delete set null,
  partner_name text,
  fcm_token text,
  last_miss_from text,
  last_miss_reply boolean,
  last_miss_at timestamptz
);

create table if not exists public.requests (
  id bigint generated always as identity primary key,
  from_id uuid not null references public.profiles(id) on delete cascade,
  to_id uuid not null references public.profiles(id) on delete cascade,
  from_name text not null,
  to_name text not null,
  created_at timestamptz not null default now(),
  unique (from_id, to_id)
);

create table if not exists public.notifications (
  id bigint generated always as identity primary key,
  to_id uuid not null references public.profiles(id) on delete cascade,
  title text not null,
  body text not null,
  created_at timestamptz not null default now()
);

-- ========== SÉCURITÉ ==========
-- Chacun lit uniquement ses propres données. Toutes les écritures passent par les fonctions ci-dessous.
alter table public.profiles enable row level security;
alter table public.requests enable row level security;
alter table public.notifications enable row level security;

drop policy if exists "profil lisible par son propriétaire" on public.profiles;
create policy "profil lisible par son propriétaire" on public.profiles
  for select to authenticated using (id = auth.uid());

drop policy if exists "demandes visibles par les deux personnes" on public.requests;
create policy "demandes visibles par les deux personnes" on public.requests
  for select to authenticated using (from_id = auth.uid() or to_id = auth.uid());

revoke all on public.profiles, public.requests, public.notifications from anon, authenticated;
grant select on public.profiles, public.requests to authenticated;

-- ========== FONCTIONS ==========
create or replace function public.save_token(p_token text)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.profiles set fcm_token = null where fcm_token = p_token and id <> auth.uid();
  insert into public.profiles (id, fcm_token) values (auth.uid(), p_token)
    on conflict (id) do update set fcm_token = excluded.fcm_token;
  return json_build_object('status', 'ok');
end $$;

create or replace function public.claim_username(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
  existing text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  insert into public.profiles (id) values (auth.uid()) on conflict (id) do nothing;
  select username into existing from public.profiles where id = auth.uid();
  if existing is not null then return json_build_object('status', 'has_username'); end if;
  begin
    update public.profiles set username = n where id = auth.uid();
  exception when unique_violation then
    return json_build_object('status', 'taken');
  end;
  return json_build_object('status', 'ok');
end $$;

-- Relie deux personnes (usage interne uniquement).
create or replace function public._link(a uuid, b uuid)
returns void language plpgsql security definer set search_path = public as $$
declare na text; nb text;
begin
  select username into na from public.profiles where id = a;
  select username into nb from public.profiles where id = b;
  update public.profiles set partner_id = b, partner_name = nb where id = a;
  update public.profiles set partner_id = a, partner_name = na where id = b;
  delete from public.requests where from_id in (a, b) or to_id in (a, b);
end $$;

create or replace function public.send_request(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
  me public.profiles;
  tgt public.profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.username is null then return json_build_object('status', 'no_username'); end if;
  if me.partner_id is not null then return json_build_object('status', 'already_has_partner'); end if;
  if me.username = n then return json_build_object('status', 'self'); end if;
  select * into tgt from public.profiles where username = n;
  if not found then return json_build_object('status', 'not_found'); end if;
  if tgt.partner_id is not null then return json_build_object('status', 'partner_busy'); end if;

  -- L'autre personne m'avait déjà ajouté : on relie directement.
  if exists (select 1 from public.requests where from_id = tgt.id and to_id = me.id) then
    perform public._link(me.id, tgt.id);
    insert into public.notifications (to_id, title, body)
      values (tgt.id, 'Vous êtes partenaires 💗', '@' || me.username || ' et toi êtes maintenant partenaires');
    return json_build_object('status', 'linked');
  end if;

  if exists (select 1 from public.requests where from_id = me.id and to_id = tgt.id) then
    return json_build_object('status', 'already_sent');
  end if;

  insert into public.requests (from_id, to_id, from_name, to_name) values (me.id, tgt.id, me.username, tgt.username);
  insert into public.notifications (to_id, title, body)
    values (tgt.id, 'Nouvelle demande 💗', '@' || me.username || ' veut être ton partenaire');
  return json_build_object('status', 'sent');
end $$;

create or replace function public.respond_request(p_request_id bigint, p_accept boolean)
returns json language plpgsql security definer set search_path = public as $$
declare r public.requests;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into r from public.requests where id = p_request_id and to_id = auth.uid();
  if not found then return json_build_object('status', 'not_found'); end if;
  if not p_accept then
    delete from public.requests where id = r.id;
    return json_build_object('status', 'ok');
  end if;
  if exists (select 1 from public.profiles where id in (r.from_id, r.to_id) and partner_id is not null) then
    delete from public.requests where id = r.id;
    return json_build_object('status', 'partner_busy');
  end if;
  perform public._link(r.from_id, r.to_id);
  insert into public.notifications (to_id, title, body)
    values (r.from_id, 'Demande acceptée 💗', '@' || r.to_name || ' a accepté d''être ton partenaire');
  return json_build_object('status', 'ok');
end $$;

create or replace function public.send_miss(p_reply boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me public.profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  update public.profiles
    set last_miss_from = me.username, last_miss_reply = p_reply, last_miss_at = now()
    where id = me.partner_id;
  if p_reply then
    update public.profiles
      set last_miss_from = null, last_miss_reply = null, last_miss_at = null
      where id = me.id;
  end if;
  insert into public.notifications (to_id, title, body)
    values (me.partner_id, '@' || me.username,
            case when p_reply then 'Tu me manques aussi 💗' else 'Tu me manques 💗' end);
  return json_build_object('status', 'ok');
end $$;

create or replace function public.remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare me public.profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  update public.profiles
    set partner_id = null, partner_name = null,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id in (me.id, me.partner_id);
  insert into public.notifications (to_id, title, body)
    values (me.partner_id, 'Partenaire retiré', '@' || me.username || ' a mis fin au lien. Tu peux ajouter quelqu''un d''autre.');
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.remove_partner() from public, anon, authenticated;
grant execute on function public.remove_partner() to authenticated;

-- Seuls les utilisateurs connectés (même anonymes) peuvent appeler ces fonctions.
revoke all on function public.save_token(text), public.claim_username(text), public.send_request(text),
  public.respond_request(bigint, boolean), public.send_miss(boolean), public._link(uuid, uuid)
  from public, anon, authenticated;
grant execute on function public.save_token(text), public.claim_username(text), public.send_request(text),
  public.respond_request(bigint, boolean), public.send_miss(boolean) to authenticated;

-- ========== ENVOI DES NOTIFICATIONS ==========
-- À chaque nouvelle ligne dans « notifications », on appelle la fonction « notify » (voir supabase/notify/index.ts).
create or replace function public.push_on_notification()
returns trigger language plpgsql security definer set search_path = public, extensions as $$
declare t text;
begin
  select fcm_token into t from public.profiles where id = new.to_id;
  if t is not null then
    perform net.http_post(
      url := 'https://PROJECT_REF.supabase.co/functions/v1/notify',
      headers := jsonb_build_object('Content-Type', 'application/json', 'x-webhook-secret', 'WEBHOOK_SECRET'),
      body := jsonb_build_object('token', t, 'title', new.title, 'body', new.body)
    );
  end if;
  return new;
end $$;

revoke all on function public.push_on_notification() from public, anon, authenticated;

drop trigger if exists on_notification_insert on public.notifications;
create trigger on_notification_insert
  after insert on public.notifications
  for each row execute function public.push_on_notification();
