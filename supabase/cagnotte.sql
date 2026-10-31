-- Free tune : cagnotte de parrainage.
-- +100 FCFA pour le parrain à chaque FIRST paiement accepté d'un filleul (une seule fois par filleul).
-- Retrait possible dès 500 FCFA ; la demande arrive dans l'appli Admin (Suivi des abonnements > Parrainage).
-- Supabase > SQL Editor > + New query > collez tout > Run. Peut être relancé sans danger.

alter table public.c_profiles add column if not exists wallet int not null default 0;

-- Une ligne par filleul récompensé (la clé primaire garantit « une seule fois »).
create table if not exists public.c_referral_reward (
  filleul_id uuid primary key references public.c_profiles(id) on delete cascade,
  sponsor_id uuid not null references public.c_profiles(id) on delete cascade,
  amount int not null,
  created_at timestamptz not null default now()
);
alter table public.c_referral_reward enable row level security;
revoke all on public.c_referral_reward from anon, authenticated;

-- Demandes de retrait.
create table if not exists public.c_payout (
  id bigserial primary key,
  user_id uuid not null references public.c_profiles(id) on delete cascade,
  amount int not null,
  phone text not null,
  full_name text not null,
  status text not null default 'pending',          -- pending | done
  proof text,                                      -- chemin de l'image de preuve (bucket chat)
  created_at timestamptz not null default now(),
  done_at timestamptz
);
create unique index if not exists c_payout_one_pending on public.c_payout (user_id) where status = 'pending';
alter table public.c_payout enable row level security;
revoke all on public.c_payout from anon, authenticated;

-- Premier paiement accepté d'un filleul : +100 FCFA pour son parrain + notification.
create or replace function public._ref_reward()
returns trigger language plpgsql security definer set search_path = public as $$
declare sid uuid; fname text; n int;
begin
  if new.status <> 'successful' then return new; end if;
  if tg_op = 'UPDATE' and old.status = 'successful' then return new; end if;
  select sponsor_id, username into sid, fname from public.c_profiles where id = new.user_id;
  if sid is null then return new; end if;
  -- premier paiement réussi de ce filleul seulement
  select count(*) into n from public.c_payments where user_id = new.user_id and status = 'successful' and id <> new.id;
  if n > 0 then return new; end if;
  insert into public.c_referral_reward (filleul_id, sponsor_id, amount) values (new.user_id, sid, 100) on conflict do nothing;
  if not found then return new; end if;
  update public.c_profiles set wallet = wallet + 100 where id = sid;
  insert into public.c_notifications (to_id, title, body)
    values (sid, 'Parrainage 🎉', 'Ton filleul @' || coalesce(fname, '?') || ' a payé son abonnement : +100 FCFA dans ta cagnotte.');
  return new;
end $$;

drop trigger if exists c_ref_reward on public.c_payments;
create trigger c_ref_reward after insert or update of status on public.c_payments
  for each row execute function public._ref_reward();

-- Ma cagnotte (pour les Paramètres).
create or replace function public.c_ref_get()
returns json language plpgsql stable security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  return json_build_object(
    'status', 'ok',
    'wallet', coalesce((select wallet from public.c_profiles where id = me), 0),
    'filleuls', (select count(*) from public.c_profiles where sponsor_id = me),
    'payes', (select count(*) from public.c_referral_reward where sponsor_id = me),
    'min', 500,
    'pending', (select json_build_object('amount', amount, 'phone', phone, 'name', full_name, 'at', created_at)
                from public.c_payout where user_id = me and status = 'pending'),
    'last', (select json_build_object('amount', amount, 'phone', phone, 'name', full_name, 'at', done_at, 'proof', proof)
             from public.c_payout where user_id = me and status = 'done' order by id desc limit 1));
end $$;

-- Je demande un retrait (500 FCFA minimum) : numéro de dépôt + nom qui doit apparaître sur la transaction.
create or replace function public.c_payout_request(p_phone text, p_name text)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); w int; ph text; nm text;
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  ph := regexp_replace(coalesce(p_phone, ''), '[^0-9]', '', 'g');
  if ph like '237%' and length(ph) = 12 then ph := substr(ph, 4); end if;
  nm := left(btrim(regexp_replace(coalesce(p_name, ''), '[[:cntrl:]]', ' ', 'g')), 60);
  if ph !~ '^6[0-9]{8}$' then return json_build_object('status', 'bad_phone'); end if;
  if length(nm) < 2 then return json_build_object('status', 'bad_name'); end if;
  if not public._rate_ok('po:' || me::text, 5, 600) then return json_build_object('status', 'rate_limited'); end if;
  select wallet into w from public.c_profiles where id = me for update;
  if coalesce(w, 0) < 500 then return json_build_object('status', 'too_low'); end if;
  if exists (select 1 from public.c_payout where user_id = me and status = 'pending') then return json_build_object('status', 'already'); end if;
  insert into public.c_payout (user_id, amount, phone, full_name) values (me, w, ph, nm);
  return json_build_object('status', 'ok', 'amount', w);
end $$;

-- Admin : tous les parrains avec leurs filleuls, leur solde et leur éventuelle demande de retrait.
create or replace function public.admin_ref_overview()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return coalesce((
    select json_agg(x order by x.pending_id is null, x.wallet desc, x.filleuls desc) from (
      select p.id, p.username, p.wallet,
        (select count(*) from public.c_profiles f where f.sponsor_id = p.id) as filleuls,
        (select count(*) from public.c_referral_reward r where r.sponsor_id = p.id) as payes,
        (select o.id from public.c_payout o where o.user_id = p.id and o.status = 'pending') as pending_id,
        (select o.amount from public.c_payout o where o.user_id = p.id and o.status = 'pending') as pending_amount,
        (select o.phone from public.c_payout o where o.user_id = p.id and o.status = 'pending') as pending_phone,
        (select o.full_name from public.c_payout o where o.user_id = p.id and o.status = 'pending') as pending_name,
        (select o.created_at from public.c_payout o where o.user_id = p.id and o.status = 'pending') as pending_at,
        (select coalesce(sum(o.amount), 0) from public.c_payout o where o.user_id = p.id and o.status = 'done') as paid_out
      from public.c_profiles p
      where exists (select 1 from public.c_profiles f where f.sponsor_id = p.id)
    ) x), '[]'::json);
end $$;

-- Admin : « Dépôt effectué » (avec la preuve en image, facultative). Le solde diminue du montant versé.
create or replace function public.admin_payout_done(p_id bigint, p_proof text)
returns json language plpgsql security definer set search_path = public as $$
declare o public.c_payout; pr text := nullif(btrim(coalesce(p_proof, '')), '');
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if pr is not null and pr !~ '^[0-9a-f-]{36}/[A-Za-z0-9_.-]{1,80}$' then return json_build_object('status', 'bad_proof'); end if;
  select * into o from public.c_payout where id = p_id and status = 'pending' for update;
  if not found then return json_build_object('status', 'gone'); end if;
  update public.c_payout set status = 'done', done_at = now(), proof = pr where id = p_id;
  update public.c_profiles set wallet = greatest(0, wallet - o.amount) where id = o.user_id;
  insert into public.c_notifications (to_id, title, body)
    values (o.user_id, 'Dépôt effectué 💸', o.amount || ' FCFA ont été envoyés au ' || o.phone || ' (' || o.full_name || ').');
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.c_ref_get(), public.c_payout_request(text, text), public.admin_ref_overview(), public.admin_payout_done(bigint, text) from public, anon;
grant execute on function public.c_ref_get(), public.c_payout_request(text, text), public.admin_ref_overview(), public.admin_payout_done(bigint, text) to authenticated;
