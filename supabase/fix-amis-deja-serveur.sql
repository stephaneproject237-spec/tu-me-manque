-- Corrige un bug introduit par amis-vs-serveur.sql (1er novembre) : une amitié « serveur »
-- (créée automatiquement en coulisses pour servir de relais iPhone <-> Android) empêchait
-- désormais d'envoyer une VRAIE demande d'ami à cette même personne — la fonction renvoyait
-- "already_friends", un statut que l'appli ne savait pas gérer, donc elle affichait
-- « Impossible d'envoyer la demande. »
-- Cette version ne bloque plus que sur une amitié RÉELLE (server = false) ; une amitié
-- serveur n'empêche plus de devenir vraiment amis (la demande continue normalement et,
-- une fois acceptée, c__befriend transforme l'amitié serveur en vraie amitié).
-- À coller dans Supabase > SQL Editor > + New query > Run (peut être relancé sans risque).

do $$
declare target text := case when to_regprocedure('public.c__send_request_impl(text)') is not null
                             then 'c__send_request_impl' else 'c_send_request' end;
begin
  execute format($f$
    create or replace function public.%I(p_username text)
    returns json language plpgsql security definer set search_path = public as $body$
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
      if exists (select 1 from public.c_friends where user_id = me.id and friend_id = tgt.id and not server) then
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
    end;
    $body$;
  $f$, target);
end $$;
