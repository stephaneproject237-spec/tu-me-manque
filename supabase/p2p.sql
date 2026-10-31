-- Free tune : mode « p2p » (réglage dans l'appli Admin > Clients Free tune).
-- Quand il est activé, un iPhone qui télécharge de l'audio (formats MP3 léger et standard = fichier m4a) voit son fichier
-- découpé en morceaux ; chaque Android en ligne télécharge et envoie des morceaux, l'iPhone les recolle.
-- Si un Android s'arrête, ses morceaux sont repris par les autres. Vidéos et audio haute qualité : méthode habituelle.
-- À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.

alter table public.c_relay add column if not exists p2p boolean not null default false;
alter table public.c_relay add column if not exists p2p_size bigint;
alter table public.c_relay add column if not exists p2p_fid text;
alter table public.c_relay add column if not exists p2p_yid text;
alter table public.c_relay add column if not exists p2p_n int;

create table if not exists public.c_relay_chunk (
  relay_id bigint not null references public.c_relay(id) on delete cascade,
  idx int not null,
  a bigint not null, b bigint not null,
  status text not null default 'open',      -- open | taken | done
  taker_id uuid, beat_at timestamptz,
  media text, size bigint,
  primary key (relay_id, idx)
);
alter table public.c_relay_chunk enable row level security;
revoke all on public.c_relay_chunk from anon, authenticated;

-- L'Android qui a pris la demande annonce le format choisi et la taille exacte : le serveur découpe en morceaux.
create or replace function public.c_relay_p2p_init(p_id bigint, p_yid text, p_fid text, p_size bigint)
returns json language plpgsql security definer set search_path = public as $$
declare r public.c_relay; cs bigint; n int; i int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into r from public.c_relay where id = p_id and p2p and taker_id = auth.uid() and status = 'taken' for update;
  if not found then return json_build_object('status', 'gone'); end if;
  if r.p2p_size is not null then
    return json_build_object('status', 'ok', 'fid', r.p2p_fid, 'size', r.p2p_size, 'n', r.p2p_n, 'yid', r.p2p_yid);
  end if;
  if p_yid is null or p_yid !~ '^[A-Za-z0-9_-]{11}$' or p_fid is null or p_fid !~ '^[A-Za-z0-9_.-]{1,24}$'
     or coalesce(p_size, 0) < 50000 or p_size > 62914560 then
    return json_build_object('status', 'bad_request');
  end if;
  cs := greatest(524288, ceil(p_size::numeric / 24)::bigint);
  n := ceil(p_size::numeric / cs)::int;
  for i in 0 .. n - 1 loop
    insert into public.c_relay_chunk (relay_id, idx, a, b) values (p_id, i, i * cs, least((i + 1) * cs, p_size) - 1);
  end loop;
  update public.c_relay set p2p_yid = p_yid, p2p_fid = p_fid, p2p_size = p_size, p2p_n = n where id = p_id;
  return json_build_object('status', 'ok', 'fid', p_fid, 'size', p_size, 'n', n, 'yid', p_yid);
end $$;

-- Les téléchargements p2p en cours auxquels ce téléphone peut aider.
create or replace function public.c_relay_p2p_jobs()
returns json language plpgsql volatile security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  if not coalesce((select relay_share from public.c_profiles where id = auth.uid()), true) then return '[]'::json; end if;
  return coalesce((
    select json_agg(json_build_object('id', r.id, 'yid', r.p2p_yid, 'fmt', r.fmt, 'p2p_fid', r.p2p_fid, 'p2p_size', r.p2p_size))
    from public.c_relay r
    where r.p2p and r.p2p_size is not null and r.status in ('open', 'taken') and r.created_at > now() - interval '1 hour'
      and public.c__can(auth.uid(), r.from_id)
      and exists (select 1 from public.c_relay_chunk c where c.relay_id = r.id and c.status <> 'done')), '[]'::json);
end $$;

-- Prend le prochain morceau libre (un morceau abandonné depuis plus de 8 secondes est repris par un autre).
create or replace function public.c_relay_chunk_take(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
declare c public.c_relay_chunk; left_n int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not coalesce((select relay_share from public.c_profiles where id = auth.uid()), true) then return json_build_object('status', 'gone'); end if;
  if not exists (select 1 from public.c_relay r where r.id = p_id and r.p2p and r.p2p_size is not null and public.c__can(auth.uid(), r.from_id)) then
    return json_build_object('status', 'gone');
  end if;
  update public.c_relay_chunk set status = 'open', taker_id = null, beat_at = null
    where relay_id = p_id and status = 'taken' and coalesce(beat_at, '-infinity') < now() - interval '8 seconds';
  select * into c from public.c_relay_chunk where relay_id = p_id and status = 'open' order by idx limit 1 for update skip locked;
  if found then
    update public.c_relay_chunk set status = 'taken', taker_id = auth.uid(), beat_at = now() where relay_id = p_id and idx = c.idx;
    return json_build_object('status', 'ok', 'idx', c.idx, 'a', c.a, 'b', c.b);
  end if;
  select count(*) into left_n from public.c_relay_chunk where relay_id = p_id and status <> 'done';
  return json_build_object('status', case when left_n > 0 then 'wait' else 'done' end);
end $$;

create or replace function public.c_relay_chunk_beat(p_id bigint, p_idx int)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay_chunk set beat_at = now() where relay_id = p_id and idx = p_idx and taker_id = auth.uid() and status = 'taken';
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_relay_chunk_fail(p_id bigint, p_idx int)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay_chunk set status = 'open', taker_id = null, beat_at = null where relay_id = p_id and idx = p_idx and taker_id = auth.uid() and status = 'taken';
  return json_build_object('status', 'ok');
end $$;

-- Un morceau est arrivé sur le serveur. Quand tous sont là, la demande est terminée (et l'essai gratuit de l'iPhone décompté).
create or replace function public.c_relay_chunk_done(p_id bigint, p_idx int, p_media text, p_size bigint)
returns json language plpgsql security definer set search_path = public as $$
declare c public.c_relay_chunk; r public.c_relay; done_n int; tot int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_media is null or p_media !~ ('^' || auth.uid()::text || '/[0-9a-f]{32}\.m4a$') then return json_build_object('status', 'bad_request'); end if;
  select * into c from public.c_relay_chunk where relay_id = p_id and idx = p_idx for update;
  if not found or c.status = 'done' then return json_build_object('status', 'gone'); end if;
  if coalesce(p_size, 0) <> c.b - c.a + 1 then return json_build_object('status', 'bad_size'); end if;
  update public.c_relay_chunk set status = 'done', media = p_media, size = p_size, taker_id = auth.uid() where relay_id = p_id and idx = p_idx;
  select count(*) filter (where status = 'done'), count(*) into done_n, tot from public.c_relay_chunk where relay_id = p_id;
  update public.c_relay set prog = least(67, floor(67.0 * done_n / greatest(tot, 1))::int) where id = p_id;
  if done_n = tot then
    select * into r from public.c_relay where id = p_id;
    update public.c_relay set status = 'done', size = p2p_size, prog = 67 where id = p_id and status in ('open', 'taken');
    update public.c_subs set trial_dl = least(trial_dl + 1, 3), updated_at = now()
      where user_id = r.from_id and not (paid_until is not null and paid_until > now()) and now() < trial_start + interval '1 day';
  end if;
  return json_build_object('status', 'ok', 'all', done_n = tot);
end $$;

-- L'iPhone lit la liste des morceaux prêts, pour les télécharger au fur et à mesure.
create or replace function public.c_relay_chunks(p_id bigint)
returns json language plpgsql stable security definer set search_path = public as $$
declare r public.c_relay;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into r from public.c_relay where id = p_id and from_id = auth.uid();
  if not found then return json_build_object('status', 'gone'); end if;
  return json_build_object('status', r.status, 'n', coalesce(r.p2p_n, 0), 'size', r.p2p_size,
    'chunks', coalesce((select json_agg(json_build_object('idx', c.idx, 'media', c.media, 'size', c.size) order by c.idx)
                        from public.c_relay_chunk c where c.relay_id = p_id and c.status = 'done' and c.media is not null), '[]'::json));
end $$;

revoke all on function public.c_relay_p2p_init(bigint, text, text, bigint), public.c_relay_p2p_jobs(), public.c_relay_chunk_take(bigint),
  public.c_relay_chunk_beat(bigint, int), public.c_relay_chunk_fail(bigint, int), public.c_relay_chunk_done(bigint, int, text, bigint),
  public.c_relay_chunks(bigint) from public, anon;
grant execute on function public.c_relay_p2p_init(bigint, text, text, bigint), public.c_relay_p2p_jobs(), public.c_relay_chunk_take(bigint),
  public.c_relay_chunk_beat(bigint, int), public.c_relay_chunk_fail(bigint, int), public.c_relay_chunk_done(bigint, int, text, bigint),
  public.c_relay_chunks(bigint) to authenticated;

create or replace function public.c_relay_open()
returns json language plpgsql volatile security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  perform public.c__touch();
  if not coalesce((select relay_share from public.c_profiles where id = auth.uid()), true) then return '[]'::json; end if;
  return coalesce((
    select json_agg(x) from (
      select r.id, r.yid, r.q, r.ti, r.ch, r.d, r.art, r.fmt, r.p2p
      from public.c_relay r
      where public.c__can(auth.uid(), r.from_id)
        and r.created_at > now() - interval '1 day'
        and not (auth.uid() = any (r.skip))
        and (r.target_id is null or r.target_id = auth.uid() or r.assigned_at < now() - interval '30 seconds')
        and (r.status = 'open' or (r.status = 'taken' and r.taken_at < now() - interval '4 minutes' and r.beat_at is null))
      order by r.id limit 8
    ) x), '[]'::json);
end $$;

create or replace function public.c_relay_create(p_yid text, p_q text, p_ti text, p_ch text, p_d int, p_art text, p_fmt text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare me uuid := auth.uid(); n int; rid bigint; notified boolean; tgt uuid; ids uuid[]; sb public.c_subs; inflight int; v_p2p boolean := false;
        v_yid text := nullif(btrim(coalesce(p_yid, '')), '');
        v_q text := left(btrim(coalesce(p_q, '')), 120);
        v_fmt text := coalesce(nullif(btrim(coalesce(p_fmt, '')), ''), 'a');
        v_art text := left(btrim(coalesce(p_art, '')), 300);
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  -- format : seulement les qualités connues
  if v_fmt not in ('a', 'al', 'ah', 'v', 'vl', 'vh') then return json_build_object('status', 'bad_request'); end if;
  -- identifiant YouTube : exactement 11 caractères autorisés (lettres, chiffres, _ et -)
  if v_yid is not null and v_yid !~ '^[A-Za-z0-9_-]{11}$' then return json_build_object('status', 'bad_request'); end if;
  -- il faut soit un identifiant, soit une recherche (sans retour à la ligne ni caractère de contrôle)
  if v_yid is null and (v_q = '' or v_q ~ '[[:cntrl:]]') then return json_build_object('status', 'bad_request'); end if;
  -- l'image doit être une adresse https (ou vide)
  if v_art <> '' and v_art !~* '^https://[^[:space:]]+$' then v_art := ''; end if;
  -- limite : 20 demandes par minute et par compte, et 8 demandes en cours maximum
  if not public._rate_ok('rc:' || me::text, 20, 60) then return json_build_object('status', 'rate_limited'); end if;
  if (select count(*) from public.c_relay where from_id = me and status in ('open', 'taken')) >= 8 then
    return json_build_object('status', 'rate_limited');
  end if;
  select count(*) into n from public.c__helpers(me);
  if n = 0 then return json_build_object('status', 'no_helper'); end if;
  select id into rid from public.c_relay
    where from_id = me and status in ('open', 'taken') and fmt = v_fmt
      and ((v_yid is not null and yid = v_yid) or (v_yid is null and q = v_q))
    order by id desc limit 1;
  if rid is not null then return json_build_object('status', 'ok', 'id', rid, 'helpers', n); end if;
  -- Abonnement : actif, ou essai gratuit (24 h, 3 téléchargements TERMINÉS). L'essai n'est décompté qu'à la fin d'un téléchargement réussi (voir c_relay_done).
  if not public.is_admin() then
    insert into public.c_subs (user_id) values (me) on conflict (user_id) do nothing;
    select * into sb from public.c_subs where user_id = me;
    if not (sb.paid_until is not null and sb.paid_until > now()) then
      select count(*) into inflight from public.c_relay where from_id = me and status in ('open', 'taken');
      if not (now() < sb.trial_start + interval '1 day' and sb.trial_dl + inflight < 3) then
        return json_build_object('status', 'pay');
      end if;
    end if;
  end if;
  -- Mode p2p (réglage de l'administrateur) : audio m4a (formats a / al) et au moins 2 Android en ligne -> la demande est ouverte à tous, qui se partagent les morceaux.
  v_p2p := public.c__setting('p2p', false) and v_fmt in ('a', 'al') and n >= 2;
  if not v_p2p and public.c__setting('random_dispatch', true) then tgt := public.c__pick_server(me, '{}'); end if;
  notified := exists (select 1 from public.c_relay where from_id = me and status in ('open', 'taken') and created_at > now() - interval '40 seconds' and target_id is null);
  insert into public.c_relay (from_id, yid, q, ti, ch, d, art, fmt, target_id, assigned_at, p2p)
    values (me, v_yid, v_q, left(regexp_replace(coalesce(p_ti, ''), '[[:cntrl:]]', ' ', 'g'), 120), left(regexp_replace(coalesce(p_ch, ''), '[[:cntrl:]]', ' ', 'g'), 60),
            least(greatest(coalesce(p_d, 0), 0), 43200), v_art, v_fmt,
            tgt, case when tgt is null then null else now() end, v_p2p)
    returning id into rid;
  if tgt is not null then
    perform public.c__fcm(array[tgt], 'relay');
  elsif v_p2p or not notified then
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
        'by', (select username from public.c_profiles where id = r.taker_id),
        'p2p_n', case when r.p2p then coalesce(r.p2p_n, 0) else 0 end))
    from public.c_relay r where r.id = any(p_ids) and r.from_id = auth.uid()), '[]'::json);
end $$;

create or replace function public.c_relay_ack(p_id bigint)
returns json language plpgsql security definer set search_path = public, storage as $$
declare m text;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select media into m from public.c_relay where id = p_id and from_id = auth.uid();
  if m is not null then
    delete from storage.objects where bucket_id = 'chat' and name = m;
    update public.c_relay set media = null where id = p_id;
  end if;
  -- Mode p2p : on efface aussi les morceaux une fois le fichier reconstitué sur l'iPhone.
  delete from storage.objects where bucket_id = 'chat' and name in (
    select c.media from public.c_relay_chunk c join public.c_relay r on r.id = c.relay_id
    where c.relay_id = p_id and r.from_id = auth.uid() and c.media is not null);
  update public.c_relay_chunk set media = null where relay_id = p_id and exists (select 1 from public.c_relay r where r.id = p_id and r.from_id = auth.uid());
  return json_build_object('status', 'ok');
end $$;

create or replace function public.admin_ft_setting(p_key text, p_val boolean)
returns json language plpgsql security definer set search_path = public as $$
declare n int := 0;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if p_key not in ('auto_friends', 'random_dispatch', 'p2p') then raise exception 'invalid'; end if;
  insert into public.c_settings (key, value) values (p_key, to_jsonb(coalesce(p_val, false)))
    on conflict (key) do update set value = excluded.value;
  if p_key = 'auto_friends' and p_val then n := public.c__auto_friend_all(); end if;
  return json_build_object('status', 'ok', 'linked', n);
end $$;

create or replace function public.admin_ft_clients()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return json_build_object(
    'settings', json_build_object('auto_friends', public.c__setting('auto_friends', false), 'random_dispatch', public.c__setting('random_dispatch', true), 'p2p', public.c__setting('p2p', false)),
    'users', coalesce((
      select json_agg(json_build_object(
        'id', p.id, 'username', p.username, 'email', u.email, 'platform', public.c__platform(p),
        'state', public.c__avail(p), 'online', public.c__avail(p) <> 'off', 'last_seen', p.last_seen,
        'share', p.relay_share, 'app_ver', p.app_ver,
        'dl', greatest((select count(*) from public.c_media m where m.user_id = p.id), (select count(*) from public.c_relay r where r.from_id = p.id and r.status = 'done')),
        'dl_helped', (select count(*) from public.c_relay r where r.taker_id = p.id and r.status = 'done'),
        'jobs', (select count(*) from public.c_relay r where (r.status = 'taken' and r.taker_id = p.id) or (r.status = 'open' and r.target_id = p.id)),
        'friends', coalesce((select json_agg(f.friend_id) from public.c_friends f where f.user_id = p.id), '[]'::json)
      ) order by lower(p.username))
      from public.c_profiles p left join auth.users u on u.id = p.id where p.username is not null), '[]'::json));
end $$;

revoke all on function public.c_relay_open(), public.c_relay_create(text, text, text, text, int, text, text), public.c_relay_status(bigint[]), public.c_relay_ack(bigint),
  public.admin_ft_setting(text, boolean), public.admin_ft_clients() from public, anon;
grant execute on function public.c_relay_open(), public.c_relay_create(text, text, text, text, int, text, text), public.c_relay_status(bigint[]), public.c_relay_ack(bigint),
  public.admin_ft_setting(text, boolean), public.admin_ft_clients() to authenticated;
