-- Free tune : l'avancement du téléchargement chez l'ami Android s'affiche sur la barre de l'iPhone.
-- À lancer une seule fois (peut être relancé sans risque).

alter table public.c_relay add column if not exists prog int not null default 0;

-- Le téléphone Android qui télécharge donne son avancement (0 à 100).
create or replace function public.c_relay_progress(p_id bigint, p_prog int)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  update public.c_relay set prog = greatest(prog, least(greatest(coalesce(p_prog, 0), 0), 100))
    where id = p_id and taker_id = auth.uid() and status = 'taken';
  return json_build_object('status', 'ok');
end $$;

-- L'iPhone suit ses demandes (avec l'avancement).
create or replace function public.c_relay_status(p_ids bigint[])
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((
    select json_agg(json_build_object('id', r.id, 'status', r.status, 'media', r.media, 'size', r.size, 'prog', r.prog,
                                      'by', (select username from public.c_profiles where id = r.taker_id)))
    from public.c_relay r where r.id = any(p_ids) and r.from_id = auth.uid()), '[]'::json);
end $$;

revoke all on function public.c_relay_progress(bigint, int) from public, anon;
grant execute on function public.c_relay_progress(bigint, int) to authenticated;
