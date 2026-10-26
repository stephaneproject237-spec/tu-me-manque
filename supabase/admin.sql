-- Tu me manques : application admin + messagerie « @admin ».
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS schema.sql, chat.sql, compte.sql et fond.sql. Peut être relancé sans danger.

-- ========== COLONNES ==========
alter table public.profiles add column if not exists device text;       -- appareil utilisé à la création du compte
alter table public.profiles add column if not exists linked_at timestamptz;

-- ========== ADMINISTRATEUR ==========
create table if not exists public.admin_emails (email text primary key);
alter table public.admin_emails enable row level security;
revoke all on public.admin_emails from anon, authenticated;
insert into public.admin_emails (email) values ('stephaneproject237@gmail.com') on conflict do nothing;

create or replace function public.is_admin()
returns boolean language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from auth.users u
    join public.admin_emails a on a.email = lower(u.email)
    where u.id = auth.uid() and u.email_confirmed_at is not null
  )
$$;

-- ========== HISTORIQUE DES AMITIÉS ==========
create table if not exists public.friendship_log (
  id bigint generated always as identity primary key,
  a_id uuid, b_id uuid,
  a_name text, b_name text,
  a_phone text, b_phone text,
  started_at timestamptz default now(),   -- vide : date inconnue (amitié antérieure à l'application admin)
  ended_at timestamptz,
  ended_by text
);
alter table public.friendship_log enable row level security;
revoke all on public.friendship_log from anon, authenticated;

-- Amitiés déjà en cours : on les ajoute à l'historique (date de début inconnue).
insert into public.friendship_log (a_id, b_id, a_name, b_name, a_phone, b_phone, started_at)
select a.id, b.id, a.username, b.username, a.phone, b.phone, null
from public.profiles a join public.profiles b on a.partner_id = b.id and a.id < b.id
where not exists (
  select 1 from public.friendship_log l
  where l.ended_at is null and ((l.a_id = a.id and l.b_id = b.id) or (l.a_id = b.id and l.b_id = a.id))
);

create or replace function public._link(a uuid, b uuid)
returns void language plpgsql security definer set search_path = public as $$
declare na text; nb text; pa text; pb text;
begin
  select username, phone into na, pa from public.profiles where id = a;
  select username, phone into nb, pb from public.profiles where id = b;
  update public.profiles set partner_id = b, partner_name = nb, partner_phone = pb, linked_at = now() where id = a;
  update public.profiles set partner_id = a, partner_name = na, partner_phone = pa, linked_at = now() where id = b;
  delete from public.requests where from_id in (a, b) or to_id in (a, b);
  insert into public.friendship_log (a_id, b_id, a_name, b_name, a_phone, b_phone) values (a, b, na, nb, pa, pb);
end $$;

create or replace function public.remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare me public.profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  delete from public.messages
    where sender_id in (me.id, me.partner_id) and recipient_id in (me.id, me.partner_id);
  update public.friendship_log set ended_at = now(), ended_by = '@' || me.username
    where ended_at is null and ((a_id = me.id and b_id = me.partner_id) or (a_id = me.partner_id and b_id = me.id));
  update public.profiles
    set partner_id = null, partner_name = null, partner_phone = null, linked_at = null,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id in (me.id, me.partner_id);
  insert into public.notifications (to_id, title, body)
    values (me.partner_id, 'Partenaire retiré', '@' || me.username || ' a mis fin au lien. Tu peux ajouter quelqu''un d''autre.');
  return json_build_object('status', 'ok');
end $$;

-- ========== CRÉATION DE COMPTE : on mémorise l'appareil ==========
drop function if exists public.create_account(text, int);
create or replace function public.create_account(p_username text, p_age int, p_device text default null)
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
  insert into public.profiles (id) values (auth.uid()) on conflict (id) do nothing;
  select username into existing from public.profiles where id = auth.uid();
  if existing is not null then return json_build_object('status', 'has_username'); end if;
  select public.norm_phone(phone) into ph from auth.users where id = auth.uid();
  begin
    update public.profiles set username = n, age = p_age::smallint, phone = ph, device = left(p_device, 200)
      where id = auth.uid();
  exception when unique_violation then
    return json_build_object('status', 'taken');
  end;
  return json_build_object('status', 'ok');
end $$;

-- ========== MESSAGERIE AVEC @admin ==========
create table if not exists public.support_messages (
  id bigint generated always as identity primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  from_admin boolean not null default false,
  body text not null check (char_length(body) between 1 and 2000),
  created_at timestamptz not null default now(),
  read_at timestamptz,                       -- lu par l'utilisateur (messages de l'admin)
  admin_read boolean not null default false  -- lu par l'admin (messages de l'utilisateur)
);
create index if not exists support_messages_user_idx on public.support_messages (user_id, id);
alter table public.support_messages enable row level security;
drop policy if exists "support : lecture par la personne" on public.support_messages;
create policy "support : lecture par la personne" on public.support_messages
  for select to authenticated using (user_id = auth.uid());
revoke all on public.support_messages from anon, authenticated;
grant select on public.support_messages to authenticated;

create or replace function public.support_send(p_body text)
returns json language plpgsql security definer set search_path = public as $$
declare b text := left(trim(coalesce(p_body, '')), 2000);
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if b = '' then return json_build_object('status', 'empty'); end if;
  if not exists (select 1 from public.profiles where id = auth.uid()) then return json_build_object('status', 'no_profile'); end if;
  if (select count(*) from public.support_messages
        where user_id = auth.uid() and not from_admin and created_at > now() - interval '1 minute') >= 15 then
    return json_build_object('status', 'too_fast');
  end if;
  insert into public.support_messages (user_id, from_admin, body) values (auth.uid(), false, b);
  return json_build_object('status', 'ok');
end $$;

create or replace function public.support_mark_read()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.support_messages set read_at = now()
    where user_id = auth.uid() and from_admin and read_at is null;
  return json_build_object('status', 'ok');
end $$;

-- ========== FONCTIONS RÉSERVÉES À L'ADMIN ==========
create or replace function public.admin_whoami()
returns json language sql stable security definer set search_path = public as $$
  select json_build_object('admin', public.is_admin())
$$;

create or replace function public.admin_users()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return coalesce((
    select json_agg(json_build_object(
      'id', p.id, 'username', p.username, 'email', u.email, 'phone', p.phone, 'age', p.age,
      'device', p.device, 'provider', u.raw_app_meta_data ->> 'provider',
      'created_at', u.created_at, 'last_sign_in_at', u.last_sign_in_at,
      'partner_id', p.partner_id, 'partner_name', p.partner_name, 'linked_at', p.linked_at,
      'unread', (select count(*) from public.support_messages s where s.user_id = p.id and not s.from_admin and not s.admin_read)
    ) order by u.created_at desc)
    from public.profiles p join auth.users u on u.id = p.id
  ), '[]'::json);
end $$;

create or replace function public.admin_friendships()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return json_build_object(
    'current', coalesce((
      select json_agg(json_build_object(
        'id', l.id, 'a_id', l.a_id, 'b_id', l.b_id, 'a_name', l.a_name, 'b_name', l.b_name,
        'a_phone', l.a_phone, 'b_phone', l.b_phone, 'started_at', l.started_at,
        'a_device', pa.device, 'b_device', pb.device
      ) order by l.started_at desc nulls last)
      from public.friendship_log l
      left join public.profiles pa on pa.id = l.a_id
      left join public.profiles pb on pb.id = l.b_id
      where l.ended_at is null
    ), '[]'::json),
    'history', coalesce((
      select json_agg(x) from (
        select json_build_object(
          'id', l.id, 'a_id', l.a_id, 'b_id', l.b_id, 'a_name', l.a_name, 'b_name', l.b_name,
          'a_phone', l.a_phone, 'b_phone', l.b_phone, 'started_at', l.started_at,
          'ended_at', l.ended_at, 'ended_by', l.ended_by) as x
        from public.friendship_log l where l.ended_at is not null
        order by l.ended_at desc limit 1000
      ) h
    ), '[]'::json)
  );
end $$;

-- Supprime le compte ET le pseudo : la personne pourra se réinscrire comme un nouvel utilisateur.
create or replace function public.admin_delete_user(p_id uuid)
returns json language plpgsql security definer set search_path = public as $$
declare me public.profiles;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if p_id = auth.uid() then return json_build_object('status', 'self'); end if;
  select * into me from public.profiles where id = p_id;
  if found and me.partner_id is not null then
    delete from public.messages
      where sender_id in (me.id, me.partner_id) and recipient_id in (me.id, me.partner_id);
    update public.friendship_log set ended_at = now(), ended_by = 'admin (compte supprimé)'
      where ended_at is null and ((a_id = me.id and b_id = me.partner_id) or (a_id = me.partner_id and b_id = me.id));
    update public.profiles
      set partner_id = null, partner_name = null, partner_phone = null, linked_at = null,
          last_miss_from = null, last_miss_reply = null, last_miss_at = null
      where id = me.partner_id;
    insert into public.notifications (to_id, title, body)
      values (me.partner_id, 'Partenaire retiré', 'Le compte de ton partenaire a été supprimé. Tu peux ajouter quelqu''un d''autre.');
  end if;
  delete from auth.users where id = p_id;   -- supprime aussi le profil, le pseudo, les demandes et les messages
  return json_build_object('status', 'ok');
end $$;

create or replace function public.admin_threads()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return coalesce((
    select json_agg(t order by t.last_at desc) from (
      select p.id as user_id, p.username,
        max(s.created_at) as last_at,
        (array_agg(s.body order by s.id desc))[1] as last_body,
        (array_agg(s.from_admin order by s.id desc))[1] as last_from_admin,
        count(*) filter (where not s.from_admin and not s.admin_read) as unread
      from public.support_messages s join public.profiles p on p.id = s.user_id
      group by p.id, p.username
    ) t
  ), '[]'::json);
end $$;

create or replace function public.admin_thread(p_user uuid)
returns json language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  update public.support_messages set admin_read = true where user_id = p_user and not from_admin and not admin_read;
  return coalesce((
    select json_agg(m order by m.id) from (
      select id, from_admin, body, created_at, read_at
      from public.support_messages where user_id = p_user order by id desc limit 300
    ) m
  ), '[]'::json);
end $$;

create or replace function public.admin_send(p_user uuid, p_body text)
returns json language plpgsql security definer set search_path = public as $$
declare b text := left(trim(coalesce(p_body, '')), 2000);
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if b = '' then return json_build_object('status', 'empty'); end if;
  if not exists (select 1 from public.profiles where id = p_user) then return json_build_object('status', 'not_found'); end if;
  insert into public.support_messages (user_id, from_admin, body) values (p_user, true, b);
  insert into public.notifications (to_id, title, body) values (p_user, '@admin', left(b, 200));
  return json_build_object('status', 'ok');
end $$;

-- ========== DROITS ==========
revoke all on function public.is_admin(), public.support_send(text), public.support_mark_read(),
  public.admin_whoami(), public.admin_users(), public.admin_friendships(), public.admin_delete_user(uuid),
  public.admin_threads(), public.admin_thread(uuid), public.admin_send(uuid, text),
  public._link(uuid, uuid), public.remove_partner(), public.create_account(text, int, text)
  from public, anon, authenticated;
grant execute on function public.support_send(text), public.support_mark_read(),
  public.admin_whoami(), public.admin_users(), public.admin_friendships(), public.admin_delete_user(uuid),
  public.admin_threads(), public.admin_thread(uuid), public.admin_send(uuid, text),
  public.remove_partner(), public.create_account(text, int, text) to authenticated;
