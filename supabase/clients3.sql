-- Free tune : vérification de présence beaucoup plus rapide (l'Admin sait en quelques secondes qui est dans Free tune, sur internet ou hors ligne).
-- À lancer une seule fois, APRÈS les codes « clients » et « clients2 » (peut être relancé sans risque).

alter table public.c_profiles add column if not exists ping_try timestamptz;

-- État d'un compte : app = dans Free tune (signe de vie < 45 s), net = joignable hors de Free tune, off = hors ligne
-- (un iPhone n'est jamais « sur internet » ; un Android qui n'a pas répondu à un signal depuis 25 s est hors ligne).
create or replace function public.c__avail(p public.c_profiles)
returns text language sql stable as $$
  select case
    when p.last_seen > now() - interval '45 seconds' and p.in_app then 'app'
    when public.c__platform(p) = 'ios' then 'off'
    when p.last_ping is not null and (p.last_seen is null or p.last_seen < p.last_ping) and p.last_ping < now() - interval '25 seconds' then 'off'
    when p.last_seen > now() - interval '3 minutes' then 'net'
    else 'off' end
$$;

-- Envoie un signal « signale-toi » aux comptes Android silencieux (au plus toutes les 10 secondes chacun).
create or replace function public.c__ping(p_ids uuid[])
returns int language plpgsql security definer set search_path = public as $$
declare todo uuid[];
begin
  select array_agg(id) into todo from public.c_profiles
    where id = any(coalesce(p_ids, '{}')) and fcm_token is not null
      and (last_seen is null or last_seen < now() - interval '15 seconds')
      and (ping_try is null or ping_try < now() - interval '10 seconds');
  if todo is null then return 0; end if;
  -- last_ping garde l'heure du plus ancien signal resté sans réponse.
  update public.c_profiles set ping_try = now(),
      last_ping = case when last_ping is null or (last_seen is not null and last_seen >= last_ping) then now() else last_ping end
    where id = any(todo);
  perform public.c__fcm(todo, 'ping');
  return cardinality(todo);
end $$;

create or replace function public.c__ping_stale(p_from uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.c__ping(array(select p.id from public.c_friends f join public.c_profiles p on p.id = f.friend_id where f.user_id = p_from and p.fcm_token is not null));
end $$;

create or replace function public.admin_ft_ping()
returns int language plpgsql security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return public.c__ping(array(select id from public.c_profiles where fcm_token is not null));
end $$;

revoke all on function public.c__ping(uuid[]), public.c__ping_stale(uuid), public.admin_ft_ping() from public, anon;
grant execute on function public.admin_ft_ping() to authenticated;
