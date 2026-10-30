-- Free tune : un paiement fait avec le faux numéro de test (677777777) est toujours « failed » :
-- jamais comptabilisé, aucun jour ajouté. À coller dans Supabase > SQL Editor > + New query > Run (relançable sans danger).
create or replace function public._pay_fake_failed()
returns trigger language plpgsql set search_path = public as $$
begin
  if new.phone = '677777777' then
    new.status := 'failed';
    if new.done_at is null then new.done_at := now(); end if;
  end if;
  return new;
end $$;
drop trigger if exists pay_fake_failed on public.c_payments;
create trigger pay_fake_failed before insert or update on public.c_payments
  for each row execute function public._pay_fake_failed();
revoke all on function public._pay_fake_failed() from public, anon, authenticated;
