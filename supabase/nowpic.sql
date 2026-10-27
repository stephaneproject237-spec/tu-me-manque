-- Tu me manques : Now pic (une photo en même temps, quand l'autre est d'accord).
-- À exécuter dans Supabase → SQL Editor. Peut être relancé sans danger.

create table if not exists public.nowpic_sessions (
  id bigint generated always as identity primary key,
  requester uuid not null references public.profiles(id) on delete cascade,
  partner uuid not null references public.profiles(id) on delete cascade,
  state text not null default 'pending' check (state in ('pending', 'go', 'declined', 'expired')),
  created_at timestamptz not null default now(),
  go_at timestamptz,
  answered_at timestamptz
);
alter table public.nowpic_sessions enable row level security;
revoke all on public.nowpic_sessions from anon, authenticated;

create or replace function public._nowpic_expire() returns void language sql security definer set search_path = public as $$
  update public.nowpic_sessions set state = 'expired'
   where (state = 'pending' and created_at < now() - interval '2 minutes')
      or (state = 'go' and go_at < now() - interval '4 minutes');
$$;
revoke all on function public._nowpic_expire() from public, anon, authenticated;

create or replace function public.nowpic_request()
returns json language plpgsql security definer set search_path = public as $$
declare me public.profiles; s public.nowpic_sessions;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  perform public._nowpic_expire();
  select * into s from public.nowpic_sessions
    where state in ('pending', 'go') and ((requester = me.id and partner = me.partner_id) or (requester = me.partner_id and partner = me.id))
    order by id desc limit 1;
  if found then return json_build_object('status', 'ok', 'id', s.id); end if;
  insert into public.nowpic_sessions (requester, partner) values (me.id, me.partner_id) returning * into s;
  insert into public.notifications (to_id, title, body)
    values (me.partner_id, '@' || me.username, '📸 Now Pic ! Réponds Oui pour prendre une photo en même temps.');
  return json_build_object('status', 'ok', 'id', s.id);
end $$;

create or replace function public.nowpic_state()
returns json language plpgsql security definer set search_path = public as $$
declare s public.nowpic_sessions;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  perform public._nowpic_expire();
  select * into s from public.nowpic_sessions
    where (requester = auth.uid() or partner = auth.uid())
      and (state in ('pending', 'go') or (state = 'declined' and requester = auth.uid() and answered_at > now() - interval '30 seconds'))
    order by id desc limit 1;
  if not found then return json_build_object('status', 'ok', 'session', null); end if;
  return json_build_object('status', 'ok', 'session', json_build_object(
    'id', s.id, 'state', s.state, 'by_me', s.requester = auth.uid(),
    'secs', floor(extract(epoch from now() - coalesce(s.go_at, s.created_at)))));
end $$;

create or replace function public.nowpic_respond(p_id bigint, p_accept boolean)
returns json language plpgsql security definer set search_path = public as $$
declare s public.nowpic_sessions; me public.profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.profiles where id = auth.uid();
  select * into s from public.nowpic_sessions where id = p_id and partner = auth.uid() and state = 'pending' for update;
  if not found then return json_build_object('status', 'not_found'); end if;
  if p_accept then
    update public.nowpic_sessions set state = 'go', go_at = now(), answered_at = now() where id = s.id;
    insert into public.notifications (to_id, title, body) values (s.requester, '@' || me.username, '📸 C''est parti ! Ouvre l''appli pour ton Now Pic.');
  else
    update public.nowpic_sessions set state = 'declined', answered_at = now() where id = s.id;
  end if;
  return json_build_object('status', 'ok');
end $$;

create or replace function public.nowpic_cancel(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.nowpic_sessions set state = 'expired' where id = p_id and requester = auth.uid() and state = 'pending';
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.nowpic_request(), public.nowpic_state(), public.nowpic_respond(bigint, boolean), public.nowpic_cancel(bigint) from public, anon;
grant execute on function public.nowpic_request(), public.nowpic_state(), public.nowpic_respond(bigint, boolean), public.nowpic_cancel(bigint) to authenticated;
