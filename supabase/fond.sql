-- Fond d'écran de la conversation, partagé entre les deux partenaires.
alter table public.profiles add column if not exists wall text;

create or replace function public.set_wallpaper(p_value text)
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
  select partner_id into pid from public.profiles where id = me;
  if pid is null then return json_build_object('status', 'no_partner'); end if;
  update public.profiles set wall = p_value where id in (me, pid);
  return json_build_object('status', 'ok');
end $$;

-- Quand le lien entre deux personnes change (ajout ou retrait), le fond revient à celui d'origine.
create or replace function public.reset_wall() returns trigger language plpgsql as $$
begin
  new.wall := null;
  return new;
end $$;

drop trigger if exists profiles_reset_wall on public.profiles;
create trigger profiles_reset_wall
  before update of partner_id on public.profiles
  for each row when (old.partner_id is distinct from new.partner_id)
  execute function public.reset_wall();

revoke all on function public.set_wallpaper(text) from public, anon, authenticated;
grant execute on function public.set_wallpaper(text) to authenticated;
