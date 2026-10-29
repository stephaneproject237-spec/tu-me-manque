-- Free tune : sauvegarde des médias dans le compte (pour les restaurer sur un autre téléphone).
-- À lancer une seule fois dans Supabase (SQL Editor).

create table if not exists public.c_media (
  user_id uuid not null references public.c_profiles(id) on delete cascade,
  mkey text not null,                       -- identifiant YouTube + format, ex. « abc123XYZ00:MP3_HQ »
  yid text not null, ti text, ch text, d int default 0, kind text not null, size bigint default 0,
  added_at timestamptz not null default now(),
  primary key (user_id, mkey)
);
create table if not exists public.c_media_pl (
  user_id uuid not null references public.c_profiles(id) on delete cascade,
  name text not null,
  items jsonb not null default '[]'::jsonb,  -- liste de mkey
  updated_at timestamptz not null default now(),
  primary key (user_id, name)
);
alter table public.c_media enable row level security;
alter table public.c_media_pl enable row level security;
revoke all on public.c_media, public.c_media_pl from anon, authenticated;

-- J'enregistre une série de médias téléchargés (un tableau JSON).
create or replace function public.c_media_add(p_items jsonb)
returns json language plpgsql security definer set search_path = public as $$
declare me uuid := auth.uid(); n int := 0; o jsonb;
begin
  if me is null then return json_build_object('status', 'unauthenticated'); end if;
  for o in select * from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) loop
    if coalesce(o->>'yid', '') = '' or coalesce(o->>'kind', '') = '' then continue; end if;
    insert into public.c_media (user_id, mkey, yid, ti, ch, d, kind, size)
      values (me, (o->>'yid') || ':' || (o->>'kind'), left(o->>'yid', 20), left(o->>'ti', 200), left(o->>'ch', 120),
              coalesce((o->>'d')::int, 0), left(o->>'kind', 12), coalesce((o->>'size')::bigint, 0))
      on conflict (user_id, mkey) do update set size = greatest(public.c_media.size, excluded.size), ti = excluded.ti, ch = excluded.ch;
    n := n + 1;
  end loop;
  return json_build_object('status', 'ok', 'n', n);
end $$;

-- Combien de médias et combien ça pèse.
create or replace function public.c_media_summary()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('count', 0, 'bytes', 0, 'audio', 0, 'video', 0); end if;
  return (select json_build_object('count', count(*), 'bytes', coalesce(sum(size), 0),
                 'audio', count(*) filter (where kind not like 'MP4%'), 'video', count(*) filter (where kind like 'MP4%'))
          from public.c_media where user_id = auth.uid());
end $$;

-- La liste complète (du plus ancien au plus récent).
create or replace function public.c_media_list()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((select json_agg(json_build_object('yid', yid, 'ti', ti, 'ch', ch, 'd', d, 'kind', kind, 'size', size) order by added_at)
                   from public.c_media where user_id = auth.uid()), '[]'::json);
end $$;

-- Mes playlists (nom + liste de médias).
create or replace function public.c_media_pl_set(p_name text, p_items jsonb)
returns json language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if coalesce(trim(p_name), '') = '' then return json_build_object('status', 'error'); end if;
  insert into public.c_media_pl (user_id, name, items) values (auth.uid(), left(trim(p_name), 80), coalesce(p_items, '[]'::jsonb))
    on conflict (user_id, name) do update set items = excluded.items, updated_at = now();
  return json_build_object('status', 'ok');
end $$;

create or replace function public.c_media_pl_list()
returns json language plpgsql stable security definer set search_path = public as $$
begin
  if auth.uid() is null then return '[]'::json; end if;
  return coalesce((select json_agg(json_build_object('name', name, 'items', items) order by updated_at)
                   from public.c_media_pl where user_id = auth.uid()), '[]'::json);
end $$;

revoke all on function public.c_media_add(jsonb), public.c_media_summary(), public.c_media_list(), public.c_media_pl_set(text, jsonb), public.c_media_pl_list() from public, anon;
grant execute on function public.c_media_add(jsonb), public.c_media_summary(), public.c_media_list(), public.c_media_pl_set(text, jsonb), public.c_media_pl_list() to authenticated;
