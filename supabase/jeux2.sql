-- Tu me manques : le jeu « Me connais-tu ? » se joue maintenant dans le fil du chat.
-- À exécuter une fois dans Supabase → SQL Editor, APRÈS jeux.sql. Peut être relancé sans danger.

-- 1) Le chat accepte les messages du jeu (type « game »).
do $$
declare c text;
begin
  for c in
    select conname from pg_constraint
    where conrelid = 'public.messages'::regclass and contype = 'c' and pg_get_constraintdef(oid) ilike '%kind%'
  loop
    execute format('alter table public.messages drop constraint %I', c);
  end loop;
  alter table public.messages add constraint messages_kind_check
    check (kind in ('text', 'gif', 'sticker', 'image', 'voice', 'video', 'game'));
end $$;

alter table public.game_rounds add column if not exists started boolean not null default false;

-- 2) Publier un message du jeu dans le chat.
create or replace function public.game_post(p_from uuid, p_to uuid, p_body text, p_meta jsonb)
returns bigint language plpgsql security definer set search_path = public as $$
declare mid bigint;
begin
  insert into public.messages (sender_id, recipient_id, kind, body, meta)
    values (p_from, p_to, 'game', p_body, p_meta) returning id into mid;
  return mid;
end $$;

-- 3) Lancer la partie dans le chat quand les deux ont choisi leurs réponses.
create or replace function public.game_start(p_round bigint, p_by uuid)
returns void language plpgsql security definer set search_path = public as $$
declare r public.game_rounds; na text; nb text; other uuid;
begin
  select * into r from public.game_rounds where id = p_round for update;
  if not found or r.started or r.status <> 'playing' then return; end if;
  update public.game_rounds set started = true where id = r.id;
  select username into na from public.profiles where id = r.user_a;
  select username into nb from public.profiles where id = r.user_b;
  other := case when r.user_a = p_by then r.user_b else r.user_a end;
  perform public.game_post(p_by, other, '🎮 Vous avez choisi vos réponses ! Passons au jeu…',
    jsonb_build_object('t','intro','round',r.id,'ua',r.user_a,'ub',r.user_b,'na',na,'nb',nb));
  perform public.game_post(r.user_b, r.user_a, '🎮 Question 1/10',
    jsonb_build_object('t','card','round',r.id,'idx',0,'qid',r.qids[1],'who',r.user_a));
  perform public.game_post(r.user_a, r.user_b, '🎮 Question 1/10',
    jsonb_build_object('t','card','round',r.id,'idx',0,'qid',r.qids[1],'who',r.user_b));
  insert into public.notifications (to_id, title, body) values (other, '🎮 Me connais-tu ?', 'C''est parti, viens jouer !');
end $$;

-- 4) Enregistrer ses réponses (un choix de 0 à 3 par question).
create or replace function public.game_save_answers(p_round bigint, p_answers jsonb)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.game_rounds; i int; v text; n1 int; n2 int; pid uuid;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.game_rounds where id = p_round and (user_a = me or user_b = me) and status = 'playing';
  if not found then return json_build_object('status','closed'); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  if r.started or exists (select 1 from public.game_guesses g where g.round_id = r.id and g.guesser = pid) then
    return json_build_object('status','locked');
  end if;
  for i in 0..9 loop
    v := trim(coalesce(p_answers ->> i, ''));
    if v ~ '^[0-3]$' then
      insert into public.game_answers (round_id, user_id, idx, answer) values (r.id, me, i, v)
        on conflict (round_id, user_id, idx) do update set answer = excluded.answer;
    end if;
  end loop;
  select count(*) into n1 from public.game_answers where round_id = r.id and user_id = me;
  select count(*) into n2 from public.game_answers where round_id = r.id and user_id = pid;
  if n1 >= 10 and n2 >= 10 then perform public.game_start(r.id, me); end if;
  return public.game_state();
end $$;

-- 5) Deviner : une question à la fois, dans l'ordre. Le résultat et la question suivante arrivent dans le chat.
create or replace function public.game_guess(p_round bigint, p_idx int, p_guess text)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.game_rounds; pid uuid; ans text; good boolean; n1 int; n2 int;
        nme text; sme int; sa int; sb int; g text := trim(coalesce(p_guess, ''));
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.game_rounds where id = p_round and (user_a = me or user_b = me) and status = 'playing' for update;
  if not found then return json_build_object('status','closed'); end if;
  if not r.started then return json_build_object('status','not_ready'); end if;
  pid := case when r.user_a = me then r.user_b else r.user_a end;
  select count(*) into n1 from public.game_guesses where round_id = r.id and guesser = me;
  if p_idx <> n1 or p_idx < 0 or p_idx > 9 or g !~ '^[0-3]$' then return json_build_object('status','already'); end if;
  select answer into ans from public.game_answers where round_id = r.id and user_id = pid and idx = p_idx;
  if ans is null then return json_build_object('status','not_ready'); end if;
  good := (g = ans);
  insert into public.game_guesses (round_id, guesser, idx, guess, ok) values (r.id, me, p_idx, g, good);
  n1 := n1 + 1;
  select username into nme from public.profiles where id = me;

  perform public.game_post(me, pid, case when good then '✅ Bonne réponse !' else '❌ Raté…' end,
    jsonb_build_object('t','result','round',r.id,'idx',p_idx,'qid',r.qids[p_idx+1],'who',me,'ok',good,'guess',g,'answer',ans));
  insert into public.notifications (to_id, title, body)
    values (pid, '🎮 Me connais-tu ?', '@' || nme || case when good then ' a trouvé ✓' else ' s''est trompé(e) ✗' end);

  if n1 < 10 then
    perform public.game_post(pid, me, '🎮 Question ' || (n1+1) || '/10',
      jsonb_build_object('t','card','round',r.id,'idx',n1,'qid',r.qids[n1+1],'who',me));
  else
    select count(*) into sme from public.game_guesses where round_id = r.id and guesser = me and ok;
    perform public.game_post(me, pid, '🏁 Terminé : ' || sme || '/10',
      jsonb_build_object('t','finish','round',r.id,'who',me,'score',sme));
  end if;

  select count(*) into n2 from public.game_guesses where round_id = r.id and guesser = pid;
  if n1 >= 10 and n2 >= 10 then
    update public.game_rounds set status = 'done' where id = r.id;
    select count(*) into sa from public.game_guesses where round_id = r.id and guesser = r.user_a and ok;
    select count(*) into sb from public.game_guesses where round_id = r.id and guesser = r.user_b and ok;
    perform public.game_post(me, pid, '🏆 Résultats : ' || sa || ' – ' || sb,
      jsonb_build_object('t','end','round',r.id,'ua',r.user_a,'ub',r.user_b,'sa',sa,'sb',sb,
        'na',(select username from public.profiles where id = r.user_a),
        'nb',(select username from public.profiles where id = r.user_b)));
  end if;
  return json_build_object('status','ok','ok',good,'answer',ans,'idx',p_idx);
end $$;

revoke all on function public.game_post(uuid, uuid, text, jsonb), public.game_start(bigint, uuid) from public, anon, authenticated;
revoke all on function public.game_save_answers(bigint, jsonb), public.game_guess(bigint, int, text) from public, anon;
grant execute on function public.game_save_answers(bigint, jsonb), public.game_guess(bigint, int, text) to authenticated;
