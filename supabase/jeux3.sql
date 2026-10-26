-- Tu me manques : « Me connais-tu ? » — la question suivante n'arrive que quand les DEUX ont répondu à la question en cours.
-- À exécuter dans Supabase → SQL Editor, APRÈS jeux.sql et jeux2.sql, un bloc à la fois. Peut être relancé sans danger.

-- 1) Publie la question suivante pour les deux joueurs (sans doublon).
create or replace function public.game_next_cards(p_round bigint, p_idx int)
returns void language plpgsql security definer set search_path = public as $$
declare r public.game_rounds; u uuid; o uuid;
begin
  select * into r from public.game_rounds where id = p_round;
  if not found or p_idx < 0 or p_idx > 9 then return; end if;
  foreach u in array array[r.user_a, r.user_b] loop
    o := case when u = r.user_a then r.user_b else r.user_a end;
    if not exists (
      select 1 from public.messages
      where kind = 'game' and meta ->> 't' = 'card' and (meta ->> 'round')::bigint = r.id
        and (meta ->> 'idx')::int = p_idx and meta ->> 'who' = u::text
    ) then
      perform public.game_post(o, u, '🎮 Question ' || (p_idx + 1) || '/10',
        jsonb_build_object('t','card','round',r.id,'idx',p_idx,'qid',r.qids[p_idx + 1],'who',u));
    end if;
  end loop;
end $$;

-- 2) Publie les résultats finaux.
create or replace function public.game_end(p_round bigint, p_by uuid, p_to uuid)
returns void language plpgsql security definer set search_path = public as $$
declare r public.game_rounds; sa int; sb int;
begin
  select * into r from public.game_rounds where id = p_round;
  update public.game_rounds set status = 'done' where id = r.id;
  select count(*) into sa from public.game_guesses where round_id = r.id and guesser = r.user_a and ok;
  select count(*) into sb from public.game_guesses where round_id = r.id and guesser = r.user_b and ok;
  perform public.game_post(p_by, p_to, '🏆 Résultats : ' || sa || ' – ' || sb,
    jsonb_build_object('t','end','round',r.id,'ua',r.user_a,'ub',r.user_b,'sa',sa,'sb',sb,
      'na',(select username from public.profiles where id = r.user_a),
      'nb',(select username from public.profiles where id = r.user_b)));
end $$;

-- 3) Deviner : la question suivante attend que l'autre ait répondu aussi.
create or replace function public.game_guess(p_round bigint, p_idx int, p_guess text)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.game_rounds; pid uuid; ans text; good boolean; n1 int; n2 int;
        nme text; sme int; g text := trim(coalesce(p_guess, ''));
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
  select count(*) into n2 from public.game_guesses where round_id = r.id and guesser = pid;
  if n1 >= 10 then
    select count(*) into sme from public.game_guesses where round_id = r.id and guesser = me and ok;
    perform public.game_post(me, pid, '🏁 Terminé : ' || sme || '/10',
      jsonb_build_object('t','finish','round',r.id,'who',me,'score',sme));
  elsif n2 >= n1 then
    perform public.game_next_cards(r.id, n1);
  end if;
  if n1 >= 10 and n2 >= 10 then perform public.game_end(r.id, me, pid); end if;
  return json_build_object('status','ok','ok',good,'answer',ans,'idx',p_idx);
end $$;

revoke all on function public.game_next_cards(bigint, int), public.game_end(bigint, uuid, uuid) from public, anon, authenticated;
revoke all on function public.game_guess(bigint, int, text) from public, anon;
grant execute on function public.game_guess(bigint, int, text) to authenticated;
