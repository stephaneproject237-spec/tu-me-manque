-- ===== ÉTAPE 1 : la table =====
create table if not exists public.reminders (
  id bigint generated always as identity primary key,
  creator uuid not null references public.profiles(id) on delete cascade,
  other uuid not null references public.profiles(id) on delete cascade,
  title text not null,
  who text not null check (who in ('creator', 'other', 'both')),
  due_at timestamptz not null,
  fired boolean not null default false,
  ack_creator boolean not null default false,
  ack_other boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.reminders enable row level security;
revoke all on public.reminders from anon, authenticated;

-- ===== ÉTAPE 2 : créer et lister =====
create or replace function public.reminder_create(p_title text, p_who text, p_due timestamptz)
returns json language plpgsql security definer set search_path = public as $$
declare t text := left(trim(coalesce(p_title, '')), 200); pid uuid; w text; n int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if t = '' then return json_build_object('status', 'empty'); end if;
  select partner_id into pid from public.profiles where id = auth.uid();
  if pid is null then return json_build_object('status', 'no_partner'); end if;
  w := case p_who when 'me' then 'creator' when 'partner' then 'other' when 'both' then 'both' else null end;
  if w is null or p_due is null then return json_build_object('status', 'invalid'); end if;
  select count(*) into n from public.reminders where creator = auth.uid() and created_at > now() - interval '1 minute';
  if n >= 20 then return json_build_object('status', 'too_fast'); end if;
  insert into public.reminders (creator, other, title, who, due_at) values (auth.uid(), pid, t, w, p_due);
  return json_build_object('status', 'ok');
end $$;

create or replace function public.reminder_list()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((
    select json_agg(x order by x.due_at) from (
      select r.id, r.title, r.due_at, (r.due_at <= now()) as due, (r.creator = auth.uid()) as by_me,
        (select username from public.profiles where id = r.creator) as by_name,
        case when r.who = 'both' then 'both'
             when (r.who = 'creator') = (r.creator = auth.uid()) then 'me' else 'partner' end as target
      from public.reminders r
      where (r.creator = auth.uid() or r.other = auth.uid())
        and not (case when r.creator = auth.uid() then r.ack_creator else r.ack_other end)
        and exists (select 1 from public.profiles p
                    where p.id = auth.uid() and p.partner_id = case when r.creator = auth.uid() then r.other else r.creator end)
    ) x
  ), '[]'::json);
end $$;

-- ===== ÉTAPE 3 : valider, annuler, déclencher =====
create or replace function public.reminder_ack(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  update public.reminders set ack_creator = ack_creator or (creator = auth.uid()), ack_other = ack_other or (other = auth.uid())
    where id = p_id and (creator = auth.uid() or other = auth.uid());
  delete from public.reminders where ack_creator and ack_other;
  return json_build_object('status', 'ok');
end $$;

create or replace function public.reminder_cancel(p_id bigint)
returns json language plpgsql security definer set search_path = public as $$
begin
  delete from public.reminders where id = p_id and (creator = auth.uid() or other = auth.uid());
  return json_build_object('status', 'ok');
end $$;

create or replace function public.reminder_fire()
returns void language plpgsql security definer set search_path = public as $$
declare r record;
begin
  for r in update public.reminders set fired = true where not fired and due_at <= now() returning * loop
    insert into public.notifications (to_id, title, body) values (r.creator, '⏰ Rappel', r.title), (r.other, '⏰ Rappel', r.title);
  end loop;
end $$;

revoke all on function public.reminder_create(text, text, timestamptz), public.reminder_list(), public.reminder_ack(bigint),
  public.reminder_cancel(bigint), public.reminder_fire() from public, anon, authenticated;
grant execute on function public.reminder_create(text, text, timestamptz), public.reminder_list(), public.reminder_ack(bigint),
  public.reminder_cancel(bigint) to authenticated;

-- ===== ÉTAPE 4 : le réveil automatique (chaque minute) =====
create extension if not exists pg_cron;
select cron.schedule('reminders-fire', '* * * * *', 'select public.reminder_fire()');
