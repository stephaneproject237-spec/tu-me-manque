-- Remplace la clé d'activation des logiciels Windows / Mac par une version plus courte.
-- Supabase > SQL Editor > + New query > collez tout > Run.
insert into public.c_srv_cfg (key, val) values ('enroll_key', '93mnjovssb4m3com')
on conflict (key) do update set val = excluded.val;
