-- Tu me manques : « est en train d'écrire… ».
-- À exécuter une fois dans Supabase → SQL Editor. Peut être relancé sans danger.

create table if not exists public.typing (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  until_at timestamptz not null
);
alter table public.typing enable row level security;
revoke all on public.typing from anon, authenticated;

create or replace function public.set_typing()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  insert into public.typing (user_id, until_at) values (auth.uid(), now() + interval '5 seconds')
    on conflict (user_id) do update set until_at = excluded.until_at;
  return json_build_object('status', 'ok');
end $$;

create or replace function public.stop_typing()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.typing set until_at = now() where user_id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

create or replace function public.partner_typing()
returns json language plpgsql security definer set search_path = public as $$
declare t boolean;
begin
  if auth.uid() is null then return json_build_object('typing', false); end if;
  select exists (
    select 1 from public.profiles me
    join public.typing ty on ty.user_id = me.partner_id
    where me.id = auth.uid() and ty.until_at > now()
  ) into t;
  return json_build_object('typing', coalesce(t, false));
end $$;

revoke all on function public.set_typing() from public, anon;
revoke all on function public.stop_typing() from public, anon;
revoke all on function public.partner_typing() from public, anon;
grant execute on function public.set_typing() to authenticated;
grant execute on function public.stop_typing() to authenticated;
grant execute on function public.partner_typing() to authenticated;
