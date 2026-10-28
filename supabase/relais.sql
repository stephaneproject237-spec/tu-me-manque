-- Free tune : relais « le premier ami Android disponible télécharge ».
-- Un utilisateur iPhone demande une chanson ou une vidéo : la demande est visible par tous ses amis qui ont Free tune
-- sur Android. Le premier dont le téléphone est libre la prend, la télécharge et la renvoie. À lancer une seule fois.

create table if not exists public.c_relay (
  id bigint generated always as identity primary key,
  from_id uuid not null references public.c_profiles(id) on delete cascade,
  yid text, q text, ti text, ch text, d int default 0, art text, fmt text default 'a',
  status text not null default 'open',            -- open | taken | done | fail
  taker_id uuid references public.c_profiles(id) on delete set null,
  taken_at timestamptz,
  tries int not null default 0,
  media text, size bigint,
  created_at timestamptz not null default now()
);
alter table public.c_relay enable row level security;
revoke all on public.c_relay from anon, authenticated;
create index if not exists c_relay_open_idx on public.c_relay (status, created_at);

-- L'iPhone demande : combien d'amis Android peuvent l'aider ?
create or replace function public.c_relay_create(p_yid text, p_q text, p_ti text, p_ch text, p_d int, p_art text, p_fmt text)
returns json language plpgsql security definer set search_path = public, extensions as $$
declare me uuid := auth.uid(); n int; rid bigint; notified boolean; nurl text; nsecret text;
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  select count(*) into n from public.c_friends f join public.c_profiles p on p.id = f.friend_id
    where f.user_id = me and p.fcm_token is not null;
  if n = 0 then return json_build_object('status', 'no_helper'); end if;
  select id into rid from public.c_relay
    where from_id = me and status in ('open', 'taken') and fmt = coalesce(p_fmt, 'a')
      and ((p_yid is not null and p_yid <> '' and yid = p_yid) or (coalesce(p_yid, '') = '' and q = left(p_q, 120)))
    order by id desc limit 1;
  if rid is not null then return json_build_object('status', 'ok', 'id', rid, 'helpers', n); end if;
  notified := exists (select 1 from public.c_relay where from_id = me and status in ('open', 'taken') and created_at > now() - interval '40 seconds');
  insert into public.c_relay (from_id, yid, q, ti, ch, d, art, fmt)
    values (me, nullif(left(p_yid, 20), ''), left(p_q, 120), left(p_ti, 120), left(p_ch, 60), greatest(coalesce(p_d, 0), 0), left(p_art, 300), coalesce(nullif(left(p_fmt, 3), ''), 'a'))
    returning id into rid;
  -- Notification silencieuse : le téléphone de chaque ami Android se réveille, même si Free tune est fermé,
  -- et télécharge pour l'iPhone. L'adresse et le mot de passe de la fonction « notify » sont ceux déjà utilisés
  -- par les notifications de Free tune (on les relit dans la fonction c_push_on_notification).
  if not notified then
    select (regexp_match(prosrc, $re$url := '([^']+)'$re$))[1], (regexp_match(prosrc, $re$x-webhook-secret', '([^']+)'$re$))[1]
      into nurl, nsecret from pg_proc where proname = 'c_push_on_notification' limit 1;
    if nurl is not null and nsecret is not null then
      perform net.http_post(
        url := nurl,
        headers := jsonb_build_object('Content-Type', 'application/json', 'x-webhook-secret', nsecret),
        body := jsonb_build_object('token', p.fcm_token, 'silent', true, 'data', jsonb_build_object('type', 'relay'))
      ) from public.c_friends f join public.c_profiles p on p.id = f.friend_id
        where f.user_id = me and p.fcm_token is not null;
    end if;
  end if;
  return json_build_object('status', 'ok', 'id', rid, 'helpers', n);
end $$;

-- L'Android regarde ce que ses amis attendent.
create or replace function public.c_relay_open()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((
    select json_agg(x) from (
      select r.id, r.yid, r.q, r.ti, r.ch, r.d, r.art, r.fmt
      from public.c_relay r
      where r.from_id in (select friend_id from public.c_friends where user_id = auth.uid())
        and r.created_at > now() - interval '1 day'
        and (r.status = 'open' or (r.status = 'taken' and r.taken_at < now() - interval '4 minutes'))
      order by r.id limit 3
    ) x), '[]'::json);
end $$;

-- Le premier qui la prend la garde (les autres reçoivent « gone »).
create or replace function public.c_relay_take(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay set status = 'taken', taker_id = auth.uid(), taken_at = now(), tries = tries + 1
    where id = p_id and from_id in (select friend_id from public.c_friends where user_id = auth.uid())
      and (status = 'open' or (status = 'taken' and taken_at < now() - interval '4 minutes'));
  get diagnostics k = row_count;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;

create or replace function public.c_relay_done(p_id bigint, p_media text, p_size bigint)
returns json language plpgsql security definer set search_path = public as $$
declare k int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay set status = 'done', media = p_media, size = p_size
    where id = p_id and taker_id = auth.uid() and status = 'taken';
  get diagnostics k = row_count;
  return json_build_object('status', case when k = 1 then 'ok' else 'gone' end);
end $$;

-- Échec : la demande repasse à un autre ami (3 essais au maximum).
create or replace function public.c_relay_fail(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay set status = case when tries >= 3 then 'fail' else 'open' end, taker_id = null, taken_at = null
    where id = p_id and taker_id = auth.uid() and status = 'taken';
  return json_build_object('status', 'ok');
end $$;

-- L'iPhone suit ses demandes.
create or replace function public.c_relay_status(p_ids bigint[])
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((
    select json_agg(json_build_object('id', r.id, 'status', r.status, 'media', r.media, 'size', r.size,
                                      'by', (select username from public.c_profiles where id = r.taker_id)))
    from public.c_relay r where r.id = any(p_ids) and r.from_id = auth.uid()), '[]'::json);
end $$;

-- Le fichier a été récupéré : on le supprime du stockage.
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
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.c_relay_create(text, text, text, text, int, text, text), public.c_relay_open(), public.c_relay_take(bigint),
  public.c_relay_done(bigint, text, bigint), public.c_relay_fail(bigint), public.c_relay_status(bigint[]), public.c_relay_ack(bigint) from public, anon;
grant execute on function public.c_relay_create(text, text, text, text, int, text, text), public.c_relay_open(), public.c_relay_take(bigint),
  public.c_relay_done(bigint, text, bigint), public.c_relay_fail(bigint), public.c_relay_status(bigint[]), public.c_relay_ack(bigint) to authenticated;
