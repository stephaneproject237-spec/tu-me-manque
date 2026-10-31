-- Remplace la clé d'activation des logiciels Windows / Mac (version « Free tune mac 1 », avec clé automatique).
-- Supabase > SQL Editor > + New query > collez tout > Run.
insert into public.c_srv_cfg (key, val) values ('enroll_key', '1a6519f6fe94b2faa5222eee319f8d4dd0e47d42f7abd539')
on conflict (key) do update set val = excluded.val;
