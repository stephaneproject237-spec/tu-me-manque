-- Free tune : un Android qui perd internet puis revient REPREND sa tâche si personne d'autre ne l'a prise entre-temps
-- (avant : la demande restait bloquée, la barre de l'iPhone ne bougeait plus).
-- À lancer une seule fois, APRÈS clients, clients2, clients3, clients4 et clients5 (peut être relancé sans risque).

alter table public.c_relay add column if not exists held_by uuid;

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
        held_by = case when w.status = 'taken' then w.taker_id else held_by end,
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

-- Signe de vie : l'Android qui détenait la tâche la reprend s'il revient avant qu'un autre l'ait prise.
create or replace function public.c_relay_beat(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay set beat_at = now() where id = p_id and taker_id = auth.uid() and status = 'taken';
  get diagnostics k = row_count;
  if k = 0 then
    update public.c_relay set status = 'taken', taker_id = auth.uid(), taken_at = now(), beat_at = now(), lost_at = null,
        skip = array_remove(skip, auth.uid())
      where id = p_id and status = 'open' and taker_id is null and held_by = auth.uid();
    get diagnostics k = row_count;
  end if;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;

create or replace function public.c_relay_progress(p_id bigint, p_prog int)
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay set prog = greatest(prog, least(greatest(coalesce(p_prog, 0), 0), 100)),
      beat_at = case when beat_at is not null then now() else beat_at end
    where id = p_id and taker_id = auth.uid() and status = 'taken';
  get diagnostics k = row_count;
  if k = 0 then
    update public.c_relay set status = 'taken', taker_id = auth.uid(), taken_at = now(), beat_at = now(), lost_at = null,
        skip = array_remove(skip, auth.uid()), prog = least(greatest(coalesce(p_prog, 0), 0), 100)
      where id = p_id and status = 'open' and taker_id is null and held_by = auth.uid();
    get diagnostics k = row_count;
  end if;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;

create or replace function public.c_relay_done(p_id bigint, p_media text, p_size bigint)
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay set status = 'done', media = p_media, size = p_size, prog = 100
    where id = p_id and status in ('taken', 'open') and (taker_id = auth.uid() or (taker_id is null and held_by = auth.uid()));
  get diagnostics k = row_count;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;

grant execute on function public.c_relay_status(bigint[]), public.c_relay_beat(bigint), public.c_relay_progress(bigint, int), public.c_relay_done(bigint, text, bigint) to authenticated;
