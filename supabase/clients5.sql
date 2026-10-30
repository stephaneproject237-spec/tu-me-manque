-- Free tune : un iPhone peut être servi par N'IMPORTE QUEL Android en ligne (amis ou non), avec le réglage « Envoi aléatoire » activé.
-- Choix : d'abord un Android sans tâche ; sinon celui dont le travail finit le plus vite. Aucune amitié nécessaire.
-- À lancer une seule fois, APRÈS clients, clients2, clients3 et clients4 (peut être relancé sans risque).

-- Les Android qui peuvent servir un compte : tous (réglage « Envoi aléatoire » activé) ou seulement ses amis (réglage désactivé).
create or replace function public.c__helpers(p_from uuid)
returns setof uuid language sql stable security definer set search_path = public as $$
  select p.id from public.c_profiles p
  where p.id <> p_from and p.fcm_token is not null and p.relay_share and public.c__platform(p) = 'android'
    and (public.c__setting('random_dispatch', true)
         or exists (select 1 from public.c_friends f where f.user_id = p_from and f.friend_id = p.id))
$$;

-- « me » peut-il servir la demande de « from » ?
create or replace function public.c__can(p_me uuid, p_from uuid)
returns boolean language sql stable security definer set search_path = public as $$
  select public.c__setting('random_dispatch', true) or exists (select 1 from public.c_friends f where f.user_id = p_me and f.friend_id = p_from)
$$;

create or replace function public.c__pick_server(p_from uuid, p_skip uuid[])
returns uuid language sql stable security definer set search_path = public as $$
  select p.id from public.c_profiles p
  where p.id in (select public.c__helpers(p_from))
    and p.id <> all (coalesce(p_skip, '{}'))
    and public.c__avail(p) in ('app', 'net')
  order by coalesce((select sum(greatest(0, 67 - r.prog)) from public.c_relay r
                     where (r.status = 'taken' and r.taker_id = p.id) or (r.status = 'open' and r.target_id = p.id)), 0), random()
  limit 1
$$;
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
      where public.c__can(auth.uid(), r.from_id)
        and r.created_at > now() - interval '1 day'
        and not (auth.uid() = any (r.skip))
        and (r.target_id is null or r.target_id = auth.uid() or r.assigned_at < now() - interval '30 seconds')
        and (r.status = 'open' or (r.status = 'taken' and r.taken_at < now() - interval '4 minutes' and r.beat_at is null))
      order by r.id limit 8
    ) x), '[]'::json);
end $$;
create or replace function public.c_relay_take(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not coalesce((select relay_share from public.c_profiles where id = auth.uid()), true) then return json_build_object('status', 'gone'); end if;
  update public.c_relay set status = 'taken', taker_id = auth.uid(), taken_at = now(), tries = tries + 1, beat_at = null, lost_at = null
    where id = p_id and public.c__can(auth.uid(), from_id)
      and not (auth.uid() = any (skip))
      and (target_id is null or target_id = auth.uid() or assigned_at < now() - interval '30 seconds')
      and (status = 'open' or (status = 'taken' and taken_at < now() - interval '4 minutes' and beat_at is null));
  get diagnostics k = row_count;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;
create or replace function public.c_relay_create(p_yid text, p_q text, p_ti text, p_ch text, p_d int, p_art text, p_fmt text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare me uuid := auth.uid(); n int; rid bigint; notified boolean; tgt uuid; ids uuid[];
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  select count(*) into n from public.c__helpers(me);
  if n = 0 then return json_build_object('status', 'no_helper'); end if;
  select id into rid from public.c_relay
    where from_id = me and status in ('open', 'taken') and fmt = coalesce(p_fmt, 'a')
      and ((p_yid is not null and p_yid <> '' and yid = p_yid) or (coalesce(p_yid, '') = '' and q = left(p_q, 120)))
    order by id desc limit 1;
  if rid is not null then return json_build_object('status', 'ok', 'id', rid, 'helpers', n); end if;
  if public.c__setting('random_dispatch', true) then tgt := public.c__pick_server(me, '{}'); end if;
  notified := exists (select 1 from public.c_relay where from_id = me and status in ('open', 'taken') and created_at > now() - interval '40 seconds' and target_id is null);
  insert into public.c_relay (from_id, yid, q, ti, ch, d, art, fmt, target_id, assigned_at)
    values (me, nullif(left(p_yid, 20), ''), left(p_q, 120), left(p_ti, 120), left(p_ch, 60), greatest(coalesce(p_d, 0), 0), left(p_art, 300), coalesce(nullif(left(p_fmt, 3), ''), 'a'),
            tgt, case when tgt is null then null else now() end)
    returning id into rid;
  if tgt is not null then
    perform public.c__fcm(array[tgt], 'relay');
  elsif not notified then
    select array_agg(h) into ids from public.c__helpers(me) h;
    perform public.c__fcm(ids, 'relay');
  end if;
  perform public.c__ping_stale(me);
  return json_build_object('status', 'ok', 'id', rid, 'helpers', n);
end $$;
create or replace function public.c_relay_status(p_ids bigint[])
returns json language plpgsql security definer set search_path = public as $$
declare w record; nt uuid; wake uuid[];
begin
  if auth.uid() is null then return '[]'::json; end if;
  for w in
    select * from public.c_relay
    where id = any(p_ids) and from_id = auth.uid()
      and ((status = 'taken' and beat_at is not null and beat_at < now() - interval '6 seconds')
        or (status = 'open' and target_id is not null and assigned_at < now() - interval '15 seconds'))
    for update
  loop
    if w.tries >= 4 then
      update public.c_relay set status = 'fail' where id = w.id;
      continue;
    end if;
    nt := public.c__pick_server(w.from_id, array_append(w.skip, coalesce(w.taker_id, w.target_id)));
    update public.c_relay set status = 'open', taker_id = null, taken_at = null, beat_at = null, prog = 0,
        skip = array_append(skip, coalesce(w.taker_id, w.target_id)), target_id = nt, assigned_at = now(),
        lost_at = case when w.status = 'taken' then now() else lost_at end
      where id = w.id;
    if nt is not null then
      wake := array[nt];
    else
      select array_agg(h) into wake from public.c__helpers(w.from_id) h
        where h <> all (array_append(w.skip, coalesce(w.taker_id, w.target_id)));
    end if;
    perform public.c__fcm(wake, 'relay');
  end loop;
  return coalesce((
    select json_agg(json_build_object('id', r.id, 'status', r.status, 'media', r.media, 'size', r.size, 'prog', r.prog, 'tries', r.tries,
        'lost', (r.status = 'open' and r.lost_at is not null),
        'quiet', case when r.status = 'taken' and r.beat_at is not null then floor(extract(epoch from now() - r.beat_at))::int else 0 end,
        'by', (select username from public.c_profiles where id = r.taker_id)))
    from public.c_relay r where r.id = any(p_ids) and r.from_id = auth.uid()), '[]'::json);
end $$;
create or replace function public.c__ping_stale(p_from uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  perform public.c__ping(array(select public.c__helpers(p_from)));
end $$;

revoke all on function public.c__helpers(uuid), public.c__can(uuid, uuid), public.c__pick_server(uuid, uuid[]), public.c__ping_stale(uuid) from public, anon;
grant execute on function public.c_relay_open(), public.c_relay_take(bigint), public.c_relay_create(text,text,text,text,int,text,text), public.c_relay_status(bigint[]) to authenticated;
