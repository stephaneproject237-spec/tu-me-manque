-- Permet d'annuler, depuis l'appli Admin, la ou les tâches en cours d'un compte (et de ses serveurs
-- automatiques), quand ça reste bloqué trop longtemps. À coller dans Supabase > SQL Editor > + New query > Run.
create or replace function public.admin_ft_cancel(p_id uuid)
returns json language plpgsql security definer set search_path = public as $$
declare n int := 0; m int := 0;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  with target as (select id from public.c_profiles where id = p_id or owner_id = p_id)
  update public.c_relay set status = 'fail', taker_id = null, taken_at = null, beat_at = null
    where (status = 'taken' and taker_id in (select id from target))
       or (status = 'open' and target_id in (select id from target));
  get diagnostics n = row_count;
  with target as (select id from public.c_profiles where id = p_id or owner_id = p_id)
  update public.c_relay_chunk set status = 'open', taker_id = null, beat_at = null
    where status = 'taken' and taker_id in (select id from target);
  get diagnostics m = row_count;
  return json_build_object('status', 'ok', 'cancelled', n + m);
end $$;
revoke all on function public.admin_ft_cancel(uuid) from public, anon;
grant execute on function public.admin_ft_cancel(uuid) to authenticated;
