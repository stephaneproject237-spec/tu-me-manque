-- Plusieurs amis, plusieurs conversations.
-- La conversation « ouverte » est simplement l'ami sélectionné (partner_id) : tout le reste (chat, photos,
-- appels, jeux…) fonctionne avec cet ami-là, et la sécurité ne montre que la conversation ouverte.

create table if not exists public.c_friends (
  user_id uuid not null references public.c_profiles(id) on delete cascade,
  friend_id uuid not null references public.c_profiles(id) on delete cascade,
  since timestamptz not null default now(),
  primary key (user_id, friend_id)
);
alter table public.c_friends enable row level security;
revoke all on public.c_friends from anon, authenticated;

-- Devenir amis (usage interne) : on garde la conversation ouverte actuelle, sauf si on n'en avait aucune.
create or replace function public.c__befriend(a uuid, b uuid)
returns void language plpgsql security definer set search_path = public as $$
declare na text; nb text; pa text; pb text;
begin
  select username, phone into na, pa from public.c_profiles where id = a;
  select username, phone into nb, pb from public.c_profiles where id = b;
  insert into public.c_friends (user_id, friend_id) values (a, b), (b, a) on conflict do nothing;
  update public.c_profiles set partner_id = b, partner_name = nb, partner_phone = pb where id = a and partner_id is null;
  update public.c_profiles set partner_id = a, partner_name = na, partner_phone = pa where id = b and partner_id is null;
  delete from public.c_requests where (from_id = a and to_id = b) or (from_id = b and to_id = a);
end $$;

-- Ajout d'un ami par pseudo ou par numéro de téléphone.
create or replace function public.c_send_request(p_username text)
returns json language plpgsql security definer set search_path = public as $$
declare
  q text := lower(trim(coalesce(p_username, '')));
  n text := regexp_replace(q, '^@', '');
  by_phone boolean := (q !~ '[a-z_]' and length(regexp_replace(q, '\D', '', 'g')) >= 8);
  me public.c_profiles;
  tgt public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not by_phone and n !~ '^[a-z0-9_]{3,20}$' then return json_build_object('status', 'invalid'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  if not found or me.username is null then return json_build_object('status', 'no_username'); end if;
  if by_phone then
    select * into tgt from public.c_profiles where phone = public.c_norm_phone(q);
  else
    select * into tgt from public.c_profiles where username = n;
  end if;
  if not found then return json_build_object('status', 'not_found'); end if;
  if tgt.id = me.id then return json_build_object('status', 'self'); end if;
  if exists (select 1 from public.c_friends where user_id = me.id and friend_id = tgt.id) then
    return json_build_object('status', 'already_friends', 'name', tgt.username);
  end if;
  if exists (select 1 from public.c_requests where from_id = tgt.id and to_id = me.id) then
    perform public.c__befriend(me.id, tgt.id);
    insert into public.c_notifications (to_id, title, body)
      values (tgt.id, 'Nouvel ami', '@' || me.username || ' et toi êtes maintenant amis');
    return json_build_object('status', 'linked', 'name', tgt.username);
  end if;
  if exists (select 1 from public.c_requests where from_id = me.id and to_id = tgt.id) then
    return json_build_object('status', 'already_sent', 'name', tgt.username);
  end if;
  insert into public.c_requests (from_id, to_id, from_name, to_name) values (me.id, tgt.id, me.username, tgt.username);
  insert into public.c_notifications (to_id, title, body)
    values (tgt.id, 'Nouvelle demande', '@' || me.username || ' veut devenir ton ami');
  return json_build_object('status', 'sent', 'name', tgt.username);
end $$;

create or replace function public.c_respond_request(p_request_id bigint, p_accept boolean)
returns json language plpgsql security definer set search_path = public as $$
declare r public.c_requests;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into r from public.c_requests where id = p_request_id and to_id = auth.uid();
  if not found then return json_build_object('status', 'not_found'); end if;
  if not p_accept then
    delete from public.c_requests where id = r.id;
    return json_build_object('status', 'ok');
  end if;
  perform public.c__befriend(r.from_id, r.to_id);
  insert into public.c_notifications (to_id, title, body)
    values (r.from_id, 'Demande acceptée', '@' || r.to_name || ' a accepté ta demande');
  return json_build_object('status', 'ok');
end $$;

-- Ouvrir la conversation avec un ami.
create or replace function public.c_select_peer(p_id uuid)
returns json language plpgsql security definer set search_path = public as $$
declare f public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if not exists (select 1 from public.c_friends where user_id = auth.uid() and friend_id = p_id) then
    return json_build_object('status', 'not_friends');
  end if;
  select * into f from public.c_profiles where id = p_id;
  update public.c_profiles
    set partner_id = f.id, partner_name = f.username, partner_phone = f.phone,
        last_miss_from = null, last_miss_reply = null, last_miss_at = null
    where id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

-- Fermer la conversation ouverte (retour à la liste d'amis).
create or replace function public.c_close_peer()
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles set partner_id = null, partner_name = null, partner_phone = null where id = auth.uid();
  return json_build_object('status', 'ok');
end $$;

-- Retirer un ami : la conversation est conservée et reviendra si vous redevenez amis.
create or replace function public.c_remove_friend(p_id uuid)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select * into me from public.c_profiles where id = auth.uid();
  delete from public.c_friends where (user_id = me.id and friend_id = p_id) or (user_id = p_id and friend_id = me.id);
  update public.c_profiles set partner_id = null, partner_name = null, partner_phone = null
    where id in (me.id, p_id) and partner_id in (me.id, p_id);
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_remove_partner()
returns json language plpgsql security definer set search_path = public as $$
declare pid uuid;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  select partner_id into pid from public.c_profiles where id = auth.uid();
  if pid is null then return json_build_object('status', 'no_partner'); end if;
  return public.c_remove_friend(pid);
end $$;

-- Liste des amis, avec le dernier message et le nombre de messages non lus.
create or replace function public.c_friends_list()
returns json language plpgsql stable security definer set search_path = public as $$
declare me uuid := auth.uid();
begin
  if me is null then return '[]'::json; end if;
  return coalesce((
    select json_agg(x order by x.last_at desc nulls last, x.username) from (
      select p.id, p.username, p.phone,
             (p.id = (select partner_id from public.c_profiles where id = me)) as selected,
             (select count(*) from public.c_messages m
                where m.sender_id = p.id and m.recipient_id = me and m.read_at is null and not m.del_recipient and m.deleted_at is null) as unread,
             lm.kind as last_kind, lm.body as last_body, lm.created_at as last_at, (lm.sender_id = me) as last_mine
        from public.c_friends f
        join public.c_profiles p on p.id = f.friend_id
        left join lateral (
          select m.kind, m.body, m.created_at, m.sender_id from public.c_messages m
           where ((m.sender_id = me and m.recipient_id = p.id and not m.del_sender)
               or (m.sender_id = p.id and m.recipient_id = me and not m.del_recipient))
             and m.deleted_at is null
           order by m.id desc limit 1) lm on true
       where f.user_id = me
    ) x), '[]'::json);
end $$;

revoke all on function public.c__befriend(uuid, uuid), public.c_send_request(text), public.c_respond_request(bigint, boolean),
  public.c_select_peer(uuid), public.c_close_peer(), public.c_remove_friend(uuid), public.c_remove_partner(),
  public.c_friends_list() from public, anon, authenticated;
grant execute on function public.c_send_request(text), public.c_respond_request(bigint, boolean),
  public.c_select_peer(uuid), public.c_close_peer(), public.c_remove_friend(uuid), public.c_remove_partner(),
  public.c_friends_list() to authenticated;
