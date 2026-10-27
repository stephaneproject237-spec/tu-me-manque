-- Tu me manques : l'historique du chat est conservé quand une amitié prend fin, et revient si elle est rétablie.
-- À exécuter dans Supabase → SQL Editor, un bloc à la fois (séparés par les lignes « -- ===== BLOC n ===== »). Peut être relancé sans danger.

-- ===== BLOC 1 : on ne voit que la conversation avec son partenaire actuel =====
create or replace function public.my_partner()
returns uuid language sql stable security definer set search_path = public as $$
  select partner_id from public.profiles where id = auth.uid()
$$;
revoke all on function public.my_partner() from public, anon;
grant execute on function public.my_partner() to authenticated;

drop policy if exists "messages visibles par les deux personnes" on public.messages;
create policy "messages visibles par les deux personnes" on public.messages
  for select to authenticated using (
    (sender_id = auth.uid() and not del_sender and recipient_id = public.my_partner())
    or (recipient_id = auth.uid() and not del_recipient and sender_id = public.my_partner())
  );

-- ===== BLOC 2 : « tout lu » ne touche que la conversation en cours =====
create or replace function public.mark_read()
returns json language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.messages set read_at = now()
    where recipient_id = auth.uid() and read_at is null and sender_id = public.my_partner();
  get diagnostics n = row_count;
  return json_build_object('status', 'ok', 'count', n);
end $$;

-- ===== BLOC 3 : retirer son partenaire ne supprime plus la conversation =====
create or replace function public.remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare me public.profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  update public.game_rounds set status = 'cancelled'
    where status in ('invite', 'playing') and user_a in (me.id, me.partner_id) and user_b in (me.id, me.partner_id);
  update public.friendship_log set ended_at = now(), ended_by = '@' || me.username
    where ended_at is null and ((a_id = me.id and b_id = me.partner_id) or (a_id = me.partner_id and b_id = me.id));
  update public.profiles
    set partner_id = null, partner_name = null, partner_phone = null, linked_at = null,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id in (me.id, me.partner_id);
  insert into public.notifications (to_id, title, body)
    values (me.partner_id, 'Partenaire retiré', '@' || me.username || ' a mis fin au lien. Tu peux ajouter quelqu''un d''autre.');
  return json_build_object('status', 'ok');
end $$;

-- ===== BLOC 4 : l'admin relie deux comptes sans effacer les conversations ni prévenir l'ancien partenaire =====
create or replace function public.admin_link(p_user uuid, p_to uuid)
returns json language plpgsql security definer set search_path = public as $$
declare x uuid; px uuid;
begin
  if not public.is_admin() then return json_build_object('status', 'forbidden'); end if;
  if p_user is null or p_to is null or p_user = p_to
     or not exists (select 1 from public.profiles where id = p_user)
     or not exists (select 1 from public.profiles where id = p_to) then
    return json_build_object('status', 'invalid');
  end if;
  -- On libère d'abord les anciens partenaires (les conversations sont conservées : elles reviendront si ces deux comptes sont reliés de nouveau ; les jeux en cours et les rappels communs sont arrêtés).
  foreach x in array array[p_user, p_to] loop
    select partner_id into px from public.profiles where id = x;
    if px is not null and px <> (case when x = p_user then p_to else p_user end) then
      update public.game_rounds set status = 'cancelled'
        where status in ('invite', 'playing') and user_a in (x, px) and user_b in (x, px);
      delete from public.reminders where creator in (x, px) and other in (x, px);
      update public.friendship_log set ended_at = now(), ended_by = '@admin'
        where ended_at is null and ((a_id = x and b_id = px) or (a_id = px and b_id = x));
      update public.profiles
        set partner_id = null, partner_name = null, partner_phone = null, linked_at = null,
            last_miss_from = null, last_miss_reply = null, last_miss_at = null
        where id in (x, px);
    end if;
  end loop;
  -- Déjà reliés l'un à l'autre : rien à refaire.
  if exists (select 1 from public.profiles where id = p_user and partner_id = p_to) then
    return json_build_object('status', 'ok');
  end if;
  perform public._link(p_user, p_to);
  return json_build_object('status', 'ok');
end $$;

revoke all on function public.admin_link(uuid, uuid) from public, anon;
grant execute on function public.admin_link(uuid, uuid) to authenticated;


-- ===== BLOC 5 : le jeu ne reprend que la partie avec le partenaire actuel =====
create or replace function public.game_state()
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); r public.game_rounds; pid uuid; mine int; theirs int; mg int; tg int; res json;
begin
  if me is null then return json_build_object('status','unauthenticated'); end if;
  select * into r from public.game_rounds
    where (user_a = me or user_b = me) and status in ('invite','playing','done')
      and (case when user_a = me then user_b else user_a end) = (select partner_id from public.profiles where id = me)
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
