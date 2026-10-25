-- Tu me manques : comptes (pseudo + âge + connexion Google / Apple / SMS).
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS schema.sql et chat.sql. Peut être relancé sans danger.

-- ========== COLONNES ==========
alter table public.profiles add column if not exists age smallint;
alter table public.profiles add column if not exists phone text;          -- numéro vérifié par SMS (chiffres seulement, avec indicatif)
alter table public.profiles add column if not exists partner_phone text;  -- numéro du partenaire (pour afficher son nom de contact)
create unique index if not exists profiles_phone_key on public.profiles (phone) where phone is not null;

-- ========== OUTILS ==========
-- « +237 6 12 34 56 78 », « 237612345678 », « 00237612345678 » et « 612345678 » donnent tous 237612345678.
create or replace function public.norm_phone(p text)
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
create or replace function public.username_available(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
begin
  if n !~ '^[a-z][a-z0-9_]{2,19}$' then return json_build_object('status', 'invalid'); end if;
  if exists (select 1 from public.profiles where username = n) then return json_build_object('status', 'taken'); end if;
  return json_build_object('status', 'available');
end $$;

-- Crée le profil d'une personne connectée (Google, Apple ou SMS) : pseudo + âge.
create or replace function public.create_account(p_username text, p_age int)
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
    update public.profiles set username = n, age = p_age::smallint, phone = ph where id = auth.uid();
  exception when unique_violation then
    return json_build_object('status', 'taken');
  end;
  return json_build_object('status', 'ok');
end $$;

-- ========== LIEN ENTRE PARTENAIRES (avec les numéros) ==========
create or replace function public._link(a uuid, b uuid)
returns void language plpgsql security definer set search_path = public as $$
declare na text; nb text; pa text; pb text;
begin
  select username, phone into na, pa from public.profiles where id = a;
  select username, phone into nb, pb from public.profiles where id = b;
  update public.profiles set partner_id = b, partner_name = nb, partner_phone = pb where id = a;
  update public.profiles set partner_id = a, partner_name = na, partner_phone = pa where id = b;
  delete from public.requests where from_id in (a, b) or to_id in (a, b);
end $$;

-- Ajout par pseudo OU par numéro de téléphone.
create or replace function public.send_request(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  q text := lower(trim(coalesce(p_username, '')));
  n text := regexp_replace(q, '^@', '');
  by_phone boolean := (q !~ '[a-z_]' and length(regexp_replace(q, '\D', '', 'g')) >= 8);
  me public.profiles;
  tgt public.profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not by_phone and n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.username is null then return json_build_object('status', 'no_username'); end if;
  if me.partner_id is not null then return json_build_object('status', 'already_has_partner'); end if;

  if by_phone then
    select * into tgt from public.profiles where phone = public.norm_phone(q);
  else
    select * into tgt from public.profiles where username = n;
  end if;
  if not found then return json_build_object('status', 'not_found'); end if;
  if tgt.id = me.id then return json_build_object('status', 'self'); end if;
  if tgt.partner_id is not null then return json_build_object('status', 'partner_busy'); end if;

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
  return json_build_object('status', 'sent', 'name', tgt.username);
end $$;

-- Retirer le partenaire : efface aussi la conversation et le numéro mémorisé.
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
    set partner_id = null, partner_name = null, partner_phone = null,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id in (me.id, me.partner_id);
  insert into public.notifications (to_id, title, body)
    values (me.partner_id, 'Partenaire retiré', '@' || me.username || ' a mis fin au lien. Tu peux ajouter quelqu''un d''autre.');
  return json_build_object('status', 'ok');
end $$;

-- Mise à jour pour les comptes déjà liés : on mémorise le numéro du partenaire.
update public.profiles p
  set partner_phone = q.phone
  from public.profiles q
  where p.partner_id = q.id and p.partner_phone is distinct from q.phone;

-- ========== DROITS ==========
-- Plus de pseudo « anonyme » : il faut passer par create_account (Google, Apple ou SMS).
revoke all on function public.claim_username(text) from public, anon, authenticated;
revoke all on function public.norm_phone(text), public.username_available(text), public.create_account(text, int),
  public._link(uuid, uuid), public.send_request(text), public.remove_partner() from public, anon, authenticated;
grant execute on function public.username_available(text) to anon, authenticated;
grant execute on function public.norm_phone(text), public.create_account(text, int),
  public.send_request(text), public.remove_partner() to authenticated;
