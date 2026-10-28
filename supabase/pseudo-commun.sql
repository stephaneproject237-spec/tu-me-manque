-- Pseudos communs à « Tu me manques » et « Casque ».
-- Un pseudo appartient à une seule personne dans les deux applis.
-- Si tu as déjà un pseudo dans une appli, l'autre le reprend automatiquement.
-- (Dans « Tu me manques » un pseudo a un seul partenaire, dans « Casque » plusieurs amis : ça ne change pas.)
-- Colle tout dans Supabase → SQL Editor → Run. Peut être relancé sans danger.

-- À qui appartient ce pseudo (dans l'une ou l'autre appli) ?
create or replace function public.pseudo_owner(n text)
returns uuid language sql stable security definer set search_path = public as $$
  select coalesce(
    (select id from public.profiles where username = n limit 1),
    (select id from public.c_profiles where username = n limit 1)
  );
$$;

create or replace function public.pseudo_taken(n text)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.profiles where username = n and id is distinct from auth.uid())
      or exists (select 1 from public.c_profiles where username = n and id is distinct from auth.uid());
$$;

-- ---------------------------------------------------------------- Tu me manques
create or replace function public.username_available(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
begin
  if n !~ '^[a-z][a-z0-9_]{2,19}$' then return json_build_object('status', 'invalid'); end if;
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
  return json_build_object('status', 'available');
end $$;

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
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
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

create or replace function public.claim_username(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
  existing text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
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

-- Reprend le pseudo de « Casque » (même compte Google) quand on arrive dans « Tu me manques ».
create or replace function public.adopt_username()
returns json language plpgsql security definer set search_path = public as $$
declare mine text; other record;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select username into mine from public.profiles where id = auth.uid();
  if mine is not null then return json_build_object('status', 'ok', 'username', mine); end if;
  select username, age, phone into other from public.c_profiles where id = auth.uid();
  if other.username is null then return json_build_object('status', 'none'); end if;
  if exists (select 1 from public.profiles where username = other.username and id <> auth.uid()) then
    return json_build_object('status', 'none');
  end if;
  insert into public.profiles (id) values (auth.uid()) on conflict (id) do nothing;
  update public.profiles set username = other.username, age = coalesce(age, other.age), phone = coalesce(phone, other.phone) where id = auth.uid();
  return json_build_object('status', 'ok', 'username', other.username);
end $$;

-- ---------------------------------------------------------------- Casque
create or replace function public.c_username_available(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
begin
  if n !~ '^[a-z][a-z0-9_]{2,19}$' then return json_build_object('status', 'invalid'); end if;
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
  return json_build_object('status', 'available');
end $$;

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
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
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

create or replace function public.c_claim_username(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
  existing text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
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

-- Reprend le pseudo de « Tu me manques » (même compte Google) quand on arrive dans « Casque ».
create or replace function public.c_adopt_username()
returns json language plpgsql security definer set search_path = public as $$
declare mine text; other record;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select username into mine from public.c_profiles where id = auth.uid();
  if mine is not null then return json_build_object('status', 'ok', 'username', mine); end if;
  select username, age, phone into other from public.profiles where id = auth.uid();
  if other.username is null then return json_build_object('status', 'none'); end if;
  if exists (select 1 from public.c_profiles where username = other.username and id <> auth.uid()) then
    return json_build_object('status', 'none');
  end if;
  insert into public.c_profiles (id) values (auth.uid()) on conflict (id) do nothing;
  update public.c_profiles set username = other.username, age = coalesce(age, other.age), phone = coalesce(phone, other.phone) where id = auth.uid();
  return json_build_object('status', 'ok', 'username', other.username);
end $$;

grant execute on function public.adopt_username() to authenticated;
grant execute on function public.c_adopt_username() to authenticated;
grant execute on function public.username_available(text) to anon, authenticated;
grant execute on function public.c_username_available(text) to anon, authenticated;
