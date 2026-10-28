-- Admin : prévenir tous les utilisateurs d'une application (Android ou iPhone) qu'une mise à jour est disponible.
-- À copier dans Supabase > SQL Editor > Run (une seule fois).

-- Combien de personnes seront prévenues, pour chaque application et chaque appareil
create or replace function public.admin_update_targets()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  return json_build_object(
    'tmm_android',    (select count(*) from public.profiles   where fcm_token is not null),
    'tmm_ios',        (select count(*) from public.profiles   where web_push is not null and fcm_token is null),
    'casque_android', (select count(*) from public.c_profiles where fcm_token is not null),
    'casque_ios',     (select count(*) from public.c_profiles where web_push is not null and fcm_token is null)
  );
end $$;

-- Envoie la notification (p_app : 'tmm' ou 'casque' ; p_platform : 'android' ou 'ios') et renvoie le nombre de personnes prévenues
create or replace function public.admin_notify_update(p_app text, p_platform text, p_title text, p_body text)
returns int language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if not public.is_admin() then raise exception 'forbidden'; end if;
  if p_app not in ('tmm', 'casque') or p_platform not in ('android', 'ios') then raise exception 'invalid'; end if;
  if coalesce(trim(p_title), '') = '' or coalesce(trim(p_body), '') = '' then raise exception 'empty'; end if;
  if p_app = 'tmm' then
    insert into public.notifications (to_id, title, body)
      select id, left(p_title, 80), left(p_body, 600) from public.profiles
      where case when p_platform = 'android' then fcm_token is not null else (web_push is not null and fcm_token is null) end;
  else
    insert into public.c_notifications (to_id, title, body)
      select id, left(p_title, 80), left(p_body, 600) from public.c_profiles
      where case when p_platform = 'android' then fcm_token is not null else (web_push is not null and fcm_token is null) end;
  end if;
  get diagnostics n = row_count;
  return n;
end $$;

revoke all on function public.admin_update_targets(), public.admin_notify_update(text, text, text, text) from public, anon;
grant execute on function public.admin_update_targets(), public.admin_notify_update(text, text, text, text) to authenticated;
