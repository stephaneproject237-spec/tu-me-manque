-- Tu me manques : jeux à deux (« Me connais-tu ? »).
-- À exécuter une fois dans Supabase → SQL Editor. Peut être relancé sans danger.

drop function if exists public.game_invite();
create table if not exists public.game_rounds (
  id bigserial primary key,
  game text not null default 'connais',
  user_a uuid not null references public.profiles(id) on delete cascade,   -- celui qui propose
  user_b uuid not null references public.profiles(id) on delete cascade,
  status text not null check (status in ('invite','declined','playing','done','cancelled')),
  qids int[] not null,
  created_at timestamptz not null default now()
);
create index if not exists game_rounds_a_idx on public.game_rounds (user_a, id desc);
create index if not exists game_rounds_b_idx on public.game_rounds (user_b, id desc);

create table if not exists public.game_answers (
  round_id bigint not null references public.game_rounds(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  idx smallint not null,
  answer text not null,
  primary key (round_id, user_id, idx)
);

create table if not exists public.game_guesses (
  round_id bigint not null references public.game_rounds(id) on delete cascade,
  guesser uuid not null references public.profiles(id) on delete cascade,
  idx smallint not null,
  guess text not null,
  ok boolean not null,
  primary key (round_id, guesser, idx)
);

alter table public.game_rounds enable row level security;
alter table public.game_answers enable row level security;
alter table public.game_guesses enable row level security;
revoke all on public.game_rounds, public.game_answers, public.game_guesses from anon, authenticated;

-- ---------- Comparaison tolérante des réponses ----------
create or replace function public.game_norm(t text)
returns text language sql immutable as $$
  select trim(regexp_replace(
    regexp_replace(
      translate(lower(coalesce(t,'')), 'àâäáãåçéèêëíìîïñóòôöõúùûüýÿœæ', 'aaaaaaceeeeiiiinooooouuuuyyoa'),
      '[^a-z0-9 ]', ' ', 'g'),
    '\s+', ' ', 'g'))
$$;

create or replace function public.game_lev(a text, b text)
returns int language plpgsql immutable as $$
declare la int := length(a); lb int := length(b); prev int[]; cur int[]; i int; j int; c int;
begin
  if la = 0 then return lb; end if;
  if lb = 0 then return la; end if;
  prev := array(select generate_series(0, lb));
  for i in 1..la loop
    cur := array[i];
    for j in 1..lb loop
      c := case when substr(a,i,1) = substr(b,j,1) then 0 else 1 end;
      cur[j+1] := least(prev[j+1] + 1, cur[j] + 1, prev[j] + c);
    end loop;
    prev := cur;
  end loop;
  return prev[lb+1];
end $$;

create or replace function public.game_match(a text, b text)
returns boolean language plpgsql immutable as $$
declare x text; y text; sx text; sy text; n int; tol int;
begin
  x := public.game_norm(a); y := public.game_norm(b);
  if x = '' or y = '' then return false; end if;
  if x = y then return true; end if;
  -- on retire les petits mots (le, la, de, mon…)
  sx := trim(regexp_replace(' ' || x || ' ', ' (le|la|les|l|un|une|des|du|de|d|mon|ma|mes|son|sa|ses|au|aux|en) ', ' ', 'g'));
  sy := trim(regexp_replace(' ' || y || ' ', ' (le|la|les|l|un|une|des|du|de|d|mon|ma|mes|son|sa|ses|au|aux|en) ', ' ', 'g'));
  sx := trim(regexp_replace(sx, ' (le|la|les|l|un|une|des|du|de|d) ', ' ', 'g'));
  sy := trim(regexp_replace(sy, ' (le|la|les|l|un|une|des|du|de|d) ', ' ', 'g'));
  if sx = '' then sx := x; end if;
  if sy = '' then sy := y; end if;
  if sx = sy then return true; end if;
  if replace(sx,' ','') = replace(sy,' ','') then return true; end if;
  -- l'un contient l'autre (mots entiers, 4 lettres minimum) : « bleu » / « bleu ciel »
  if length(sx) >= 4 and (' ' || sy || ' ') like '% ' || sx || ' %' then return true; end if;
  if length(sy) >= 4 and (' ' || sx || ' ') like '% ' || sy || ' %' then return true; end if;
  -- fautes de frappe
  n := greatest(length(sx), length(sy));
  tol := case when n <= 4 then 0 when n <= 8 then 1 when n <= 13 then 2 else 3 end;
  if x ~ '^[0-9 ]+$' then tol := 0; end if;  -- les nombres et les dates doivent être exacts
  return public.game_lev(sx, sy) <= tol;
end $$;

-- ---------- État du jeu ----------
create or replace function public.game_state()
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.game_rounds; pid uuid; mine int; theirs int; mg int; tg int; res json;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.game_rounds
    where (user_a = me or user_b = me) and status in ('invite','playing','done')
    order by id desc limit 1;
  if not found then return json_build_object('status','ok','round',null); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  select count(*) into mine from public.game_answers where round_id = r.id and user_id = me;
  select count(*) into theirs from public.game_answers where round_id = r.id and user_id = pid;
  select count(*) into mg from public.game_guesses where round_id = r.id and guesser = me;
  select count(*) into tg from public.game_guesses where round_id = r.id and guesser = pid;
  select json_build_object(
    'status','ok',
    'round', json_build_object(
      'id', r.id, 'state', r.status, 'by_me', r.user_a = me, 'qids', r.qids,
      'my_answers', mine, 'their_answers', theirs, 'my_guesses', mg, 'their_guesses', tg,
      'my_answer_list', (select coalesce(json_agg(json_build_object('idx', idx, 'answer', answer) order by idx), '[]'::json)
                         from public.game_answers where round_id = r.id and user_id = me),
      'my_results', (select coalesce(json_agg(json_build_object(
                        'idx', g.idx, 'guess', g.guess, 'ok', g.ok,
                        'answer', (select a.answer from public.game_answers a where a.round_id = r.id and a.user_id = pid and a.idx = g.idx)
                      ) order by g.idx), '[]'::json)
                     from public.game_guesses g where g.round_id = r.id and g.guesser = me),
      'their_results', (select coalesce(json_agg(json_build_object(
                        'idx', g.idx, 'guess', g.guess, 'ok', g.ok,
                        'answer', (select a.answer from public.game_answers a where a.round_id = r.id and a.user_id = me and a.idx = g.idx)
                      ) order by g.idx), '[]'::json)
                     from public.game_guesses g where g.round_id = r.id and g.guesser = pid)
    )) into res;
  return res;
end $$;

-- ---------- Proposer une partie ----------
create or replace function public.game_invite(p_total int default 664)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); pid uuid; ids int[]; used int[]; total int := least(greatest(coalesce(p_total, 664), 10), 5000);
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select partner_id into pid from public.profiles where id = me;
  if pid is null then return json_build_object('status','no_partner'); end if;
  update public.game_rounds set status = 'cancelled'
    where status in ('invite','playing') and (user_a in (me,pid) or user_b in (me,pid));
  select coalesce(array_agg(distinct q), '{}') into used
    from public.game_rounds r, unnest(r.qids) q
    where (r.user_a = me and r.user_b = pid) or (r.user_a = pid and r.user_b = me);
  select array_agg(x) into ids from (
    select x from generate_series(1, total) x where not (x = any(used)) order by random() limit 10) s;
  if ids is null or array_length(ids,1) < 10 then
    select array_agg(x) into ids from (select x from generate_series(1, total) x order by random() limit 10) s;
  end if;
  insert into public.game_rounds (user_a, user_b, status, qids) values (me, pid, 'invite', ids);
  return public.game_state();
end $$;

-- ---------- Répondre à l'invitation ----------
create or replace function public.game_respond(p_round bigint, p_accept boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  update public.game_rounds set status = case when p_accept then 'playing' else 'declined' end
    where id = p_round and user_b = me and status = 'invite';
  return public.game_state();
end $$;

create or replace function public.game_cancel(p_round bigint)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  update public.game_rounds set status = 'cancelled'
    where id = p_round and (user_a = me or user_b = me) and status in ('invite','playing','done');
  return public.game_state();
end $$;

-- ---------- Enregistrer mes réponses (à l'avance) ----------
create or replace function public.game_save_answers(p_round bigint, p_answers jsonb)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.game_rounds; i int; v text;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.game_rounds where id = p_round and (user_a = me or user_b = me) and status = 'playing';
  if not found then return json_build_object('status','closed'); end if;
  if exists (select 1 from public.game_guesses g where g.round_id = r.id
             and g.guesser = case when r.user_a = me then r.user_b else r.user_a end) then
    return json_build_object('status','locked');   -- l'autre a déjà commencé à deviner
  end if;
  for i in 0..9 loop
    v := left(trim(coalesce(p_answers ->> i, '')), 120);
    if v <> '' then
      insert into public.game_answers (round_id, user_id, idx, answer) values (r.id, me, i, v)
        on conflict (round_id, user_id, idx) do update set answer = excluded.answer;
    end if;
  end loop;
  return public.game_state();
end $$;

-- ---------- Deviner ----------
create or replace function public.game_guess(p_round bigint, p_idx int, p_guess text)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.game_rounds; pid uuid; ans text; good boolean; n1 int; n2 int;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.game_rounds where id = p_round and (user_a = me or user_b = me) and status = 'playing';
  if not found then return json_build_object('status','closed'); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  if p_idx < 0 or p_idx > 9 then return json_build_object('status','bad'); end if;
  if exists (select 1 from public.game_guesses where round_id = r.id and guesser = me and idx = p_idx) then
    return json_build_object('status','already');
  end if;
  select answer into ans from public.game_answers where round_id = r.id and user_id = pid and idx = p_idx;
  if ans is null then return json_build_object('status','not_ready'); end if;
  good := public.game_match(p_guess, ans);
  insert into public.game_guesses (round_id, guesser, idx, guess, ok) values (r.id, me, p_idx, left(trim(p_guess),120), good);
  select count(*) into n1 from public.game_guesses where round_id = r.id and guesser = me;
  select count(*) into n2 from public.game_guesses where round_id = r.id and guesser = pid;
  if n1 >= 10 and n2 >= 10 then update public.game_rounds set status = 'done' where id = r.id; end if;
  return json_build_object('status','ok','ok',good,'answer',ans,'idx',p_idx);
end $$;

revoke all on function public.game_state() from public, anon;
revoke all on function public.game_invite(int) from public, anon;
revoke all on function public.game_respond(bigint, boolean) from public, anon;
revoke all on function public.game_cancel(bigint) from public, anon;
revoke all on function public.game_save_answers(bigint, jsonb) from public, anon;
revoke all on function public.game_guess(bigint, int, text) from public, anon;
grant execute on function public.game_state() to authenticated;
grant execute on function public.game_invite(int) to authenticated;
grant execute on function public.game_respond(bigint, boolean) to authenticated;
grant execute on function public.game_cancel(bigint) to authenticated;
grant execute on function public.game_save_answers(bigint, jsonb) to authenticated;
grant execute on function public.game_guess(bigint, int, text) to authenticated;
