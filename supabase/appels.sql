-- Tu me manques : appels audio et vidéo (WebRTC). À exécuter dans Supabase → SQL Editor, un bloc à la fois.

-- 1) Les petits messages échangés pour établir l'appel (sonnerie, accepter, raccrocher, infos de connexion).
create table if not exists public.call_signals (
  id bigserial primary key,
  from_id uuid not null,
  to_id uuid not null,
  kind text not null,
  payload jsonb,
  created_at timestamptz not null default now()
);
create index if not exists call_signals_to_idx on public.call_signals (to_id, id);
alter table public.call_signals enable row level security;

-- 2) Envoyer un signal à mon partenaire.
create or replace function public.call_send(p_kind text, p_payload jsonb default null)
returns json language plpgsql security definer set search_path = public as $$
declare me public.profiles; sid bigint;
begin
  if auth.uid() is null then return json_build_object('status', 'unauthenticated'); end if;
  if p_kind not in ('ring', 'accept', 'reject', 'hangup', 'offer', 'answer', 'ice') then return json_build_object('status', 'invalid'); end if;
  if p_payload is not null and pg_column_size(p_payload) > 12000 then return json_build_object('status', 'invalid'); end if;
  select * into me from public.profiles where id = auth.uid();
  if not found or me.partner_id is null then return json_build_object('status', 'no_partner'); end if;
  insert into public.call_signals (from_id, to_id, kind, payload) values (me.id, me.partner_id, p_kind, p_payload) returning id into sid;
  if p_kind = 'ring' then
    insert into public.notifications (to_id, title, body)
      values (me.partner_id, '📞 @' || me.username, case when coalesce((p_payload ->> 'video')::boolean, false) then 'Appel vidéo entrant…' else 'Appel entrant…' end);
  end if;
  delete from public.call_signals where created_at < now() - interval '1 hour';
  return json_build_object('status', 'ok', 'id', sid);
end $$;

-- 3) Lire les signaux reçus après l'identifiant donné (et pas plus vieux de 90 secondes).
create or replace function public.call_poll(p_after bigint default 0)
returns json language sql security definer set search_path = public as $$
  select coalesce(json_agg(json_build_object('id', id, 'kind', kind, 'payload', payload) order by id), '[]'::json)
  from public.call_signals
  where to_id = auth.uid() and id > coalesce(p_after, 0) and created_at > now() - interval '90 seconds';
$$;

revoke all on function public.call_send(text, jsonb), public.call_poll(bigint) from public, anon;
grant execute on function public.call_send(text, jsonb), public.call_poll(bigint) to authenticated;
