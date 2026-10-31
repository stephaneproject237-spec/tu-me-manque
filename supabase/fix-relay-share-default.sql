-- Correctif : depuis que l'activation/désactivation d'un serveur se fait UNIQUEMENT depuis l'appli Admin
-- (voir admin-share.sql), les battements des appareils ne remettent plus ce réglage à « actif » tout seuls.
-- Les comptes restés à « non actif » depuis avant ce changement (donc jamais désactivés depuis l'Admin)
-- sont donc bloqués : ils ne reçoivent plus aucune tâche. On les remet tous à « actif », comme demandé
-- (« activé par défaut »). À coller dans Supabase > SQL Editor > + New query > Run. Peut être relancé sans danger.

update public.c_profiles set relay_share = true where relay_share is distinct from true;
