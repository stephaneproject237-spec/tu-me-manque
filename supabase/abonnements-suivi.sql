-- Suivi des abonnements pour l'application Admin (réservé à l'administrateur).
-- À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.
create or replace function public.admin_subs_overview()
returns json language plpgsql stable security definer set search_path = public as $$
declare
  tz text := 'Africa/Douala';
  m0 timestamptz := date_trunc('month', now() at time zone tz) at time zone tz;
  res json;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  with ok as (
    select p.user_id, p.amount, p.phone, coalesce(p.done_at, p.created_at) as at
    from public.c_payments p
    where p.status = 'successful'
      -- numéros de test du mode démo Campay : le paiement passe, mais n'est pas compté dans le chiffre d'affaires
      and coalesce(p.phone, '') not in ('699999999', '677777770', '677777777', '699999990')
  ), who as (
    select s.user_id, s.paid_until, pr.username, u.email
    from public.c_subs s
    left join public.c_profiles pr on pr.id = s.user_id
    left join auth.users u on u.id = s.user_id
  )
  select json_build_object(
    'now', now(),
    'total', json_build_object(
      'revenue', coalesce((select sum(amount) from ok), 0),
      'payments', (select count(*) from ok),
      'payers', (select count(distinct user_id) from ok),
      'first', (select min(at) from ok)),
    'month', json_build_object(
      'key', to_char(now() at time zone tz, 'YYYY-MM'),
      'revenue', coalesce((select sum(amount) from ok where at >= m0), 0),
      'payments', (select count(*) from ok where at >= m0),
      'payers', (select count(distinct user_id) from ok where at >= m0),
      'prev_revenue', coalesce((select sum(amount) from ok where at >= m0 - interval '1 month' and at < m0), 0),
      'prev_payments', (select count(*) from ok where at >= m0 - interval '1 month' and at < m0)),
    'months', coalesce((
      select json_agg(x order by x.m) from (
        select to_char(at at time zone tz, 'YYYY-MM') as m, count(*) as n, sum(amount) as rev, count(distinct user_id) as payers
        from ok group by 1 order by 1 desc limit 12) x), '[]'::json),
    'current', coalesce((
      select json_agg(y order by y.at desc) from (
        select o.user_id as id, w.username, w.email, max(o.phone) as phone, sum(o.amount) as amount, count(*) as n_month, max(o.at) as at,
               max(w.paid_until) as paid_until, coalesce(max(w.paid_until) > now(), false) as active,
               (select count(*) from ok k where k.user_id = o.user_id) as times
        from ok o left join who w on w.user_id = o.user_id
        where o.at >= m0
        group by o.user_id, w.username, w.email) y), '[]'::json),
    'soon', coalesce((
      select json_agg(z order by z.paid_until) from (
        select w.user_id as id, w.username, w.email, w.paid_until
        from who w where w.paid_until > now() and w.paid_until < now() + interval '7 days') z), '[]'::json),
    'expired', coalesce((
      select json_agg(e order by e.paid_until desc) from (
        select w.user_id as id, w.username, w.email, w.paid_until
        from who w where w.paid_until is not null and w.paid_until <= now() and w.paid_until > now() - interval '30 days' limit 30) e), '[]'::json),
    'active_count', (select count(*) from public.c_subs where paid_until > now()),
    'trial_count', (select count(*) from public.c_subs where (paid_until is null or paid_until <= now()) and now() < trial_start + interval '1 day'),
    'accounts', (select count(*) from public.c_profiles where username is not null)
  ) into res;
  return res;
end $$;
revoke all on function public.admin_subs_overview() from public, anon;
grant execute on function public.admin_subs_overview() to authenticated, service_role;
