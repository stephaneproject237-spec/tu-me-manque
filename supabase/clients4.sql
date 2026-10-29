-- Free tune : présence en ligne QUELLE QUE SOIT LA VERSION de l'appli (Android, iPhone, et vue Admin).
-- Avant : seul le « battement » des dernières versions comptait. Un téléphone avec une ou plusieurs mises à jour
-- de retard paraissait hors ligne, même ouvert et prêt à télécharger.
-- Maintenant : n'importe quel échange avec le serveur, quelle que soit la version, compte comme un signe de vie.
-- À lancer une seule fois, APRÈS « clients », « clients2 » et « clients3 » (peut être relancé sans risque).

-- Ce compte comprend-il le signal « signale-toi » ? (versions récentes seulement ; les autres l'ignorent)
alter table public.c_profiles add column if not exists ping_ok boolean not null default false;

-- ===== Signe de vie : marque le compte connecté comme vu maintenant (au plus toutes les 10 s) =====
create or replace function public.c__touch()
returns void language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return; end if;
  if coalesce(current_setting('transaction_read_only', true), 'off') = 'on' then return; end if;
  update public.c_profiles set last_seen = now()
    where id = auth.uid() and (last_seen is null or last_seen < now() - interval '10 seconds');
exception when others then
  null; -- ne jamais bloquer une requête de l'appli pour la présence
end $$;

-- Exécuté avant CHAQUE requête des applis (toutes versions) : c'est ce qui rend la présence indépendante des mises à jour.
create or replace function public.c__pre_request()
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.c__touch();
exception when others then
  null;
end $$;
grant execute on function public.c__pre_request() to anon, authenticated, service_role;

-- ===== État d'un compte =====
-- iPhone : dans Free tune s'il a parlé au serveur depuis moins de 90 s (une page web ne tourne que si elle est ouverte).
-- Android récent : comme avant (dans Free tune / sur internet / hors ligne, avec le signal « signale-toi »).
-- Android ancien (ne connaît pas le signal) : jugé seulement sur ses échanges, jamais déclaré hors ligne pour un signal ignoré.
create or replace function public.c__avail(p public.c_profiles)
returns text language sql stable as $$
  select case
    when public.c__platform(p) = 'ios' then
      case when p.last_seen > now() - interval '90 seconds' then 'app' else 'off' end
    when p.last_seen > now() - interval '45 seconds' and (p.in_app or not p.ping_ok) then 'app'
    when p.ping_ok and p.last_ping is not null and (p.last_seen is null or p.last_seen < p.last_ping)
         and p.last_ping < now() - interval '25 seconds' then 'off'
    when p.last_seen > now() - interval '3 minutes' then 'net'
    else 'off' end
$$;

-- ===== Battement =====
-- Cœur commun ; p_ping_ok = true seulement pour les versions qui envoient trois valeurs (elles comprennent le signal).
create or replace function public.c__heartbeat(p_platform text, p_share boolean, p_in_app boolean, p_ping_ok boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles; r record; n int := 0;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles set last_seen = now(), in_app = coalesce(p_in_app, true),
      platform = coalesce(nullif(p_platform, ''), platform),
      relay_share = coalesce(p_share, relay_share),
      ping_ok = case when p_ping_ok and coalesce(nullif(p_platform, ''), platform, '') = 'android' then true else ping_ok end
    where id = auth.uid() returning * into me;
  if me.id is null or me.username is null then return json_build_object('status', 'ok'); end if;
  if public.c__setting('auto_friends', false) then
    for r in
      select o.id from public.c_profiles o
      where o.id <> me.id and o.username is not null and o.last_seen > now() - interval '2 minutes'
        and public.c__platform(o) <> public.c__platform(me)
        and not exists (select 1 from public.c_friends f where f.user_id = me.id and f.friend_id = o.id)
      limit 50
    loop
      perform public.c__befriend(me.id, r.id);
      n := n + 1;
    end loop;
  end if;
  return json_build_object('status', 'ok', 'linked', n);
end $$;

-- Versions récentes (trois valeurs).
create or replace function public.c_heartbeat(p_platform text, p_share boolean, p_in_app boolean)
returns json language sql security definer set search_path = public as $$
  select public.c__heartbeat(p_platform, p_share, p_in_app, true)
$$;
-- Versions plus anciennes (deux valeurs) : elles ne comprennent pas le signal « signale-toi ».
create or replace function public.c_heartbeat(p_platform text, p_share boolean)
returns json language sql security definer set search_path = public as $$
  select public.c__heartbeat(p_platform, p_share, true, false)
$$;

-- ===== Le signal « signale-toi » ne part qu'aux téléphones qui le comprennent =====
create or replace function public.c__ping(p_ids uuid[])
returns int language plpgsql security definer set search_path = public as $$
declare todo uuid[];
begin
  select array_agg(id) into todo from public.c_profiles
    where id = any(coalesce(p_ids, '{}')) and fcm_token is not null and ping_ok
      and (last_seen is null or last_seen < now() - interval '15 seconds')
      and (ping_try is null or ping_try < now() - interval '10 seconds');
  if todo is null then return 0; end if;
  update public.c_profiles set ping_try = now(),
      last_ping = case when last_ping is null or (last_seen is not null and last_seen >= last_ping) then now() else last_ping end
    where id = any(todo);
  perform public.c__fcm(todo, 'ping');
  return cardinality(todo);
end $$;

-- ===== Les anciens Android interrogent sans cesse cette liste : elle compte maintenant comme signe de vie =====
-- (même contenu qu'avant ; elle n'est plus « en lecture seule » pour pouvoir noter la présence)
create or replace function public.c_relay_open()
returns json language plpgsql volatile security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  perform public.c__touch();
  if not coalesce((select relay_share from public.c_profiles where id = auth.uid()), true) then return '[]'::json; end if;
  return coalesce((
    select json_agg(x) from (
      select r.id, r.yid, r.q, r.ti, r.ch, r.d, r.art, r.fmt
      from public.c_relay r
      where r.from_id in (select friend_id from public.c_friends where user_id = auth.uid())
        and r.created_at > now() - interval '1 day'
        and not (auth.uid() = any (r.skip))
        and (r.target_id is null or r.target_id = auth.uid() or r.assigned_at < now() - interval '30 seconds')
        and (r.status = 'open' or (r.status = 'taken' and r.taken_at < now() - interval '4 minutes' and r.beat_at is null))
      order by r.id limit 8
    ) x), '[]'::json);
end $$;

-- Les comptes déjà marqués « hors ligne » à tort pour un signal ignoré sont remis à zéro.
update public.c_profiles set last_ping = null, ping_try = null where not ping_ok;

revoke all on function public.c__touch(), public.c__ping(uuid[]), public.c__heartbeat(text, boolean, boolean, boolean) from public, anon;
grant execute on function public.c_heartbeat(text, boolean, boolean), public.c_heartbeat(text, boolean), public.c_relay_open() to authenticated;

-- ===== Brancher le signe de vie sur toutes les requêtes (en dernier : si Supabase refuse, le reste est déjà en place) =====
do $$
begin
  execute 'alter role authenticator set pgrst.db_pre_request = ''public.c__pre_request''';
exception when others then
  raise notice 'Branchement automatique impossible (%). La présence via la liste des demandes fonctionne quand même.', sqlerrm;
end $$;
notify pgrst, 'reload config';
