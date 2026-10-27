-- Tu me manques : l'admin peut relier un compte à un autre en un clic (utile pour les comptes de test comme @alpha1).
-- À exécuter dans Supabase → SQL Editor. Peut être relancé sans danger.
create or replace function public.admin_link(p_user uuid, p_to uuid)
returns json language plpgsql security definer set search_path = public as $$
declare x uuid; px uuid;
begin
  if not public.is_admin() then return json_build_object('status', 'forbidden'); end if;
  if p_user is null or p_to is null or p_user = p_to
     or not exists (select 1 from public.profiles where id = p_user)
     or not exists (select 1 from public.profiles where id = p_to) then
    return json_build_object('status', 'invalid');
  end if;
  -- On libère d'abord les anciens partenaires (les conversations sont conservées : elles reviendront si ces deux comptes sont reliés de nouveau ; les jeux en cours et les rappels communs sont arrêtés).
  foreach x in array array[p_user, p_to] loop
    select partner_id into px from public.profiles where id = x;
    if px is not null and px <> (case when x = p_user then p_to else p_user end) then
      update public.game_rounds set status = 'cancelled'
        where status in ('invite', 'playing') and user_a in (x, px) and user_b in (x, px);
      delete from public.reminders where creator in (x, px) and other in (x, px);
      update public.friendship_log set ended_at = now(), ended_by = '@admin'
        where ended_at is null and ((a_id = x and b_id = px) or (a_id = px and b_id = x));
      update public.profiles
        set partner_id = null, partner_name = null, partner_phone = null, linked_at = null,
            last_miss_from = null, last_miss_reply = null, last_miss_at = null
        where id in (x, px);
    end if;
  end loop;
  -- Déjà reliés l'un à l'autre : rien à refaire.
  if exists (select 1 from public.profiles where id = p_user and partner_id = p_to) then
    return json_build_object('status', 'ok');
  end if;
  perform public._link(p_user, p_to);
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.admin_link(uuid, uuid) from public, anon;
grant execute on function public.admin_link(uuid, uuid) to authenticated;
