-- Free tune : distingue désormais deux types d'« amitiés » dans public.c_friends :
--  - amitié normale  : faite à la main, par demande d'ajout (pseudo / numéro), acceptée par l'autre. Seule celle-ci
--    apparaît dans le tchat (rubrique « MES AMIS », propositions d'ami…).
--  - amitié serveur  : créée automatiquement par le système (réglage Admin « Amis automatiques », et le lien manuel
--    « admin_ft_link » dans l'appli Admin) pour qu'un Android/Windows/Mac serve de relais à un iPhone. Elle reste
--    utile en coulisses (présence, notifications) mais ne doit plus jamais s'afficher à l'utilisateur.
-- Une fois qu'une paire est devenue amie « pour de vrai » (demande acceptée), elle le reste même si elle avait
-- d'abord été créée par le système.
-- À lancer une seule fois, APRÈS casque-amis.sql (ou casque.sql) et APRÈS clients4.sql / admin-share.sql
-- (peut être relancé sans risque).

alter table public.c_friends add column if not exists server boolean not null default false;
comment on column public.c_friends.server is 'true = amitié créée automatiquement pour servir de relais (jamais montrée dans « Mes amis »)';

-- ===== c__befriend : nouveau paramètre p_server (par défaut false = amitié normale, inchangé pour les appels existants) =====
-- (on retire l'ancienne version à 2 arguments pour éviter toute ambiguïté avec celle-ci, qui a un argument par défaut)
drop function if exists public.c__befriend(uuid, uuid);
create or replace function public.c__befriend(a uuid, b uuid, p_server boolean default false)
returns void language plpgsql security definer set search_path = public as $$
declare na text; nb text; pa text; pb text;
begin
  select username, phone into na, pa from public.c_profiles where id = a;
  select username, phone into nb, pb from public.c_profiles where id = b;
  insert into public.c_friends (user_id, friend_id, server) values (a, b, p_server), (b, a, p_server)
    on conflict (user_id, friend_id) do update set server = public.c_friends.server and excluded.server;
  update public.c_profiles set partner_id = b, partner_name = nb, partner_phone = pb where id = a and partner_id is null;
  update public.c_profiles set partner_id = a, partner_name = na, partner_phone = pa where id = b and partner_id is null;
  delete from public.c_requests where (from_id = a and to_id = b) or (from_id = b and to_id = a);
end $$;

-- ===== Les créations automatiques passent désormais p_server := true =====

-- Réglage Admin « Amis automatiques » (tous les comptes Android en ligne <-> tous les iPhone en ligne).
create or replace function public.c__auto_friend_all()
returns int language plpgsql security definer set search_path = public as $$
declare n int := 0; r record;
begin
  for r in
    select a.id as aid, b.id as bid
    from public.c_profiles a join public.c_profiles b on public.c__platform(a) = 'android' and public.c__platform(b) = 'ios'
    where a.username is not null and b.username is not null
      and a.last_seen > now() - interval '2 minutes' and b.last_seen > now() - interval '2 minutes'
      and not exists (select 1 from public.c_friends f where f.user_id = a.id and f.friend_id = b.id)
    limit 500
  loop
    perform public.c__befriend(r.aid, r.bid, true);
    n := n + 1;
  end loop;
  return n;
end $$;

-- Battement (présence) : amis automatiques créés à la volée si le réglage est actif. Version actuellement utilisée
-- par l'appli (définie dans admin-share.sql) ; redéfinie ici seulement pour le paramètre p_server := true.
create or replace function public.c__heartbeat(p_platform text, p_share boolean, p_in_app boolean, p_ping_ok boolean)
returns json language plpgsql security definer set search_path = public as $$
declare me public.c_profiles; r record; n int := 0;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_profiles set last_seen = now(), in_app = coalesce(p_in_app, true),
      platform = coalesce(nullif(p_platform, ''), platform),
      ping_ok = case when p_ping_ok and coalesce(nullif(p_platform, ''), platform, '') = 'android' then true else ping_ok end
    where id = auth.uid() returning * into me;
  if me.id is null or me.username is null then return json_build_object('status', 'ok'); end if;
  if public.c__setting('auto_friends', false) then
    for r in
      select o.id from public.c_profiles o
      where o.id <> me.id and o.username is not null and o.last_seen > now() - interval '2 minutes'
        and public.c__platform(o) <> public.c__platform(me)
        and not exists (select 1 from public.c_friends f where f.user_id = me.id and f.friend_id = o.id)
      limit 50
    loop
      perform public.c__befriend(me.id, r.id, true);
      n := n + 1;
    end loop;
  end if;
  return json_build_object('status', 'ok', 'linked', n);
end $$;

-- Lien manuel depuis l'appli Admin (sélection de comptes Android/iPhone à relier) : c'est aussi une amitié serveur.
create or replace function public.admin_ft_link(p_a uuid[], p_b uuid[])
returns int language plpgsql security definer set search_path = public as $$
declare x uuid; y uuid; n int := 0;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if coalesce(array_length(p_a, 1), 0) * coalesce(array_length(p_b, 1), 0) > 1000 then raise exception 'trop de liens'; end if;
  foreach x in array coalesce(p_a, '{}') loop
    foreach y in array coalesce(p_b, '{}') loop
      if x <> y and exists (select 1 from public.c_profiles where id = x) and exists (select 1 from public.c_profiles where id = y) then
        perform public.c__befriend(x, y, true); n := n + 1;
      end if;
    end loop;
  end loop;
  return n;
end $$;

-- ===== Liste des amis affichée dans le tchat : uniquement les amitiés normales =====
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
       where f.user_id = me and not f.server
    ) x), '[]'::json);
end $$;

revoke all on function public.c__befriend(uuid, uuid, boolean), public.c__auto_friend_all(), public.admin_ft_link(uuid[], uuid[]),
  public.c_friends_list() from public, anon, authenticated;
grant execute on function public.c_friends_list(), public.admin_ft_link(uuid[], uuid[]) to authenticated;
