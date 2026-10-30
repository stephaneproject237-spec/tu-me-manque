-- Protection contre les abus : limite le nombre de vérifications de pseudo par adresse IP
-- (empêche de « scanner » tous les pseudos existants et de saturer la base).
-- Colle tout dans Supabase → SQL Editor → Run. Peut être relancé sans danger.

create table if not exists public.rate_hits (k text not null, t timestamptz not null default now());
create index if not exists rate_hits_k_t on public.rate_hits (k, t);
alter table public.rate_hits enable row level security;
revoke all on public.rate_hits from anon, authenticated;

-- Vrai si la personne (clé k) n'a pas dépassé n appels sur les secs dernières secondes.
create or replace function public._rate_ok(k text, n int, secs int)
returns boolean language plpgsql security definer set search_path = public as $$
declare c int;
begin
  if random() < 0.02 then delete from public.rate_hits where t < now() - interval '15 minutes'; end if;
  select count(*) into c from public.rate_hits where rate_hits.k = _rate_ok.k and t > now() - make_interval(secs => secs);
  if c >= n then return false; end if;
  insert into public.rate_hits (k) values (_rate_ok.k);
  return true;
end $$;
revoke all on function public._rate_ok(text, int, int) from public, anon, authenticated;

-- Adresse IP de la personne (donnée par Supabase dans l'en-tête de la requête).
create or replace function public._client_key()
returns text language plpgsql stable security definer set search_path = public as $$
declare h text;
begin
  begin h := current_setting('request.headers', true)::json ->> 'x-forwarded-for'; exception when others then h := null; end;
  return coalesce(nullif(trim(split_part(coalesce(h, ''), ',', 1)), ''), 'inconnu');
end $$;
revoke all on function public._client_key() from public, anon, authenticated;

-- Même fonctions qu'avant, avec une limite de 40 vérifications par minute et par adresse IP.
create or replace function public.username_available(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
begin
  if not public._rate_ok('ua:' || public._client_key(), 40, 60) then return json_build_object('status', 'error'); end if;
  if n !~ '^[a-z][a-z0-9_]{2,19}$' then return json_build_object('status', 'invalid'); end if;
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
  return json_build_object('status', 'available');
end $$;

create or replace function public.c_username_available(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare n text := lower(regexp_replace(trim(coalesce(p_username, '')), '^@', ''));
begin
  if not public._rate_ok('ua:' || public._client_key(), 40, 60) then return json_build_object('status', 'error'); end if;
  if n !~ '^[a-z][a-z0-9_]{2,19}$' then return json_build_object('status', 'invalid'); end if;
  if public.pseudo_taken(n) then return json_build_object('status', 'taken'); end if;
  return json_build_object('status', 'available');
end $$;

grant execute on function public.username_available(text), public.c_username_available(text) to anon, authenticated;
