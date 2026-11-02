-- Free tune : l'appli Admin peut supprimer un compte (identifiant + ses serveurs automatiques), un compte iPhone,
-- un compte Android ou un serveur automatique seul.
-- À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.
create or replace function public.admin_ft_delete(p_id uuid)
returns json language plpgsql security definer set search_path = public as $$
declare t uuid; n int := 0;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if not exists (select 1 from public.c_profiles where id = p_id) then return json_build_object('status', 'not_found'); end if;
  if exists (select 1 from auth.users u join public.admin_emails a on a.email = lower(u.email) where u.id = p_id) then
    return json_build_object('status', 'protected');
  end if;
  -- Les iPhone qui attendaient ces comptes reçoivent un échec au lieu de rester bloqués.
  perform public.admin_ft_cancel(p_id);
  for t in
    select id from public.c_profiles
    where id = p_id or (owner_id = p_id and platform in ('windows', 'mac', 'srvdroid'))
  loop
    update public.c_profiles set partner_id = null, partner_name = null where partner_id = t;
    if exists (select 1 from public.profiles where id = t) then
      -- Même compte de connexion utilisé aussi dans « Tu me manques » : on n'efface que la partie Free tune.
      delete from public.c_subs where user_id = t;
      delete from public.c_payments where user_id = t;
      delete from public.c_desk_dl where user_id = t;
      delete from public.c_desk_session where user_id = t;
      delete from public.c_profiles where id = t;
    else
      delete from auth.users where id = t;   -- supprime aussi le profil, les amis, les messages et l'abonnement
    end if;
    n := n + 1;
  end loop;
  return json_build_object('status', 'ok', 'deleted', n);
end $$;
revoke all on function public.admin_ft_delete(uuid) from public, anon;
grant execute on function public.admin_ft_delete(uuid) to authenticated;
