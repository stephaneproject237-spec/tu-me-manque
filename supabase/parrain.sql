-- Free tune : parrainage. Chaque utilisateur peut avoir un « parrain » (celui qui lui a envoyé l'application).
-- À lancer une seule fois dans Supabase (SQL Editor).

alter table public.c_profiles add column if not exists sponsor_id uuid references public.c_profiles(id) on delete set null;

-- Mon parrain (son pseudo), ou null.
create or replace function public.c_sponsor_get()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('name', null); end if;
  return json_build_object('name', (select s.username from public.c_profiles m join public.c_profiles s on s.id = m.sponsor_id where m.id = auth.uid()));
end $$;

-- Je renseigne mon parrain (une seule fois, pas moi-même).
create or replace function public.c_sponsor_set(p_name text)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); sid uuid; sname text; cur uuid;
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  select sponsor_id into cur from public.c_profiles where id = me;
  if cur is not null then return json_build_object('status', 'already', 'name', (select username from public.c_profiles where id = cur)); end if;
  select id, username into sid, sname from public.c_profiles
    where lower(username) = lower(regexp_replace(coalesce(p_name, ''), '^[@\s]+|\s+$', '', 'g')) limit 1;
  if sid is null then return json_build_object('status', 'unknown'); end if;
  if sid = me then return json_build_object('status', 'self'); end if;
  update public.c_profiles set sponsor_id = sid where id = me and sponsor_id is null;
  return json_build_object('status', 'ok', 'name', sname);
end $$;

revoke all on function public.c_sponsor_get(), public.c_sponsor_set(text) from public, anon;
grant execute on function public.c_sponsor_get(), public.c_sponsor_set(text) to authenticated;
