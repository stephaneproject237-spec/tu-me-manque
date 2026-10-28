-- Free tune : essai gratuit (24 h, 3 téléchargements) puis abonnement 500 FCFA / 30 jours payé par Mobile Money (Campay).
-- Colle tout dans Supabase → SQL Editor → Run. Peut être relancé sans danger.

create table if not exists public.c_subs (
  user_id uuid primary key references auth.users(id) on delete cascade,
  trial_start timestamptz not null default now(),
  trial_dl int not null default 0,
  paid_until timestamptz,
  updated_at timestamptz not null default now()
);
alter table public.c_subs enable row level security;
drop policy if exists "abonnement lisible par son propriétaire" on public.c_subs;
create policy "abonnement lisible par son propriétaire" on public.c_subs for select to authenticated using (user_id = auth.uid());
revoke all on public.c_subs from anon, authenticated;
grant select on public.c_subs to authenticated;

create table if not exists public.c_payments (
  id bigserial primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  reference text unique,
  ext_ref text unique not null,
  phone text,
  amount int not null,
  status text not null default 'pending',   -- pending | successful | failed
  created_at timestamptz not null default now(),
  done_at timestamptz
);
alter table public.c_payments enable row level security;
drop policy if exists "paiements lisibles par leur propriétaire" on public.c_payments;
create policy "paiements lisibles par leur propriétaire" on public.c_payments for select to authenticated using (user_id = auth.uid());
revoke all on public.c_payments from anon, authenticated;
grant select on public.c_payments to authenticated;

-- État de l'abonnement (l'essai commence au premier appel, juste après la création du compte).
create or replace function public.c_sub_status()
returns json language plpgsql security definer set search_path = public as $$
declare s public.c_subs;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  insert into public.c_subs (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  select * into s from public.c_subs where user_id = auth.uid();
  return json_build_object(
    'status', 'ok',
    'state', case when s.paid_until is not null and s.paid_until > now() then 'active'
                  when now() < s.trial_start + interval '1 day' and s.trial_dl < 3 then 'trial'
                  else 'expired' end,
    'paid_until', s.paid_until,
    'trial_end', s.trial_start + interval '1 day',
    'trial_left', greatest(0, 3 - s.trial_dl),
    'now', now()
  );
end $$;

-- Un téléchargement : autorisé si abonné, ou pendant l'essai (3 maximum). Sinon : il faut payer.
create or replace function public.c_use_download()
returns json language plpgsql security definer set search_path = public as $$
declare s public.c_subs;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  insert into public.c_subs (user_id) values (auth.uid()) on conflict (user_id) do nothing;
  select * into s from public.c_subs where user_id = auth.uid() for update;
  if s.paid_until is not null and s.paid_until > now() then return json_build_object('status', 'ok', 'state', 'active'); end if;
  if now() < s.trial_start + interval '1 day' and s.trial_dl < 3 then
    update public.c_subs set trial_dl = trial_dl + 1, updated_at = now() where user_id = auth.uid();
    return json_build_object('status', 'ok', 'state', 'trial', 'trial_left', 2 - s.trial_dl);
  end if;
  return json_build_object('status', 'pay');
end $$;

revoke all on function public.c_sub_status(), public.c_use_download() from public, anon;
grant execute on function public.c_sub_status(), public.c_use_download() to authenticated;
