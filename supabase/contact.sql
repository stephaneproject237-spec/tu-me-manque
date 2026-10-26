-- ===== ÉTAPE 1 =====
alter table public.profiles add column if not exists birthdate date;

-- ===== ÉTAPE 2 =====
create or replace function public.save_contact(p_birth date, p_phone text)
returns json language plpgsql security definer set search_path = public as $$
declare
  ph text := public.norm_phone(p_phone);
  cur text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_birth is null or p_birth > current_date - interval '5 years' or p_birth < current_date - interval '120 years' then
    return json_build_object('status', 'invalid_birth');
  end if;
  if ph is null or length(ph) < 8 then return json_build_object('status', 'invalid_phone'); end if;
  select phone into cur from public.profiles where id = auth.uid();
  begin
    update public.profiles
      set birthdate = p_birth, age = extract(year from age(p_birth))::smallint, phone = coalesce(cur, ph)
      where id = auth.uid();
  exception when unique_violation then
    return json_build_object('status', 'phone_taken');
  end;
  update public.profiles set partner_phone = coalesce(cur, ph) where partner_id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

-- ===== ÉTAPE 3 =====
revoke all on function public.save_contact(date, text) from public, anon;
grant execute on function public.save_contact(date, text) to authenticated;
