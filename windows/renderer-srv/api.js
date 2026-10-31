// Compte personnel (identifiant + mot de passe) : connexion, appels au serveur, paiement. Identique sur Mac, Windows et Android.
(function () {
  "use strict";
  const C = window.FT_CFG;
  let sess = null;
  try { sess = JSON.parse(localStorage.getItem("ft_sess") || "null"); } catch (e) { sess = null; }
  const save = (s) => { sess = s; try { if (s) localStorage.setItem("ft_sess", JSON.stringify(s)); else localStorage.removeItem("ft_sess"); } catch (e) { /* ignoré */ } };
  const hdr = (tok) => ({ apikey: C.key, "Content-Type": "application/json", Authorization: "Bearer " + (tok || C.key) });
  async function post(path, body, tok) {
    const r = await fetch(C.url + path, { method: "POST", headers: hdr(tok), body: JSON.stringify(body || {}) });
    const t = await r.text(); let j = null; try { j = t ? JSON.parse(t) : null; } catch (e) { j = null; }
    return { ok: r.ok, code: r.status, j };
  }
  async function grant(kind, body) {
    const r = await fetch(C.url + "/auth/v1/token?grant_type=" + kind, { method: "POST", headers: { apikey: C.key, "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const j = await r.json().catch(() => ({}));
    return { ok: r.ok, code: r.status, j };
  }
  const keep = (j, id) => save({ access: j.access_token, refresh: j.refresh_token, exp: Date.now() + (j.expires_in || 3600) * 1000 - 90000, uid: j.user && j.user.id, id: id || (sess && sess.id) || "" });
  async function refresh() {
    if (!sess || !sess.refresh) throw new Error("session");
    const r = await grant("refresh_token", { refresh_token: sess.refresh });
    if (!r.ok) { if (r.code >= 400 && r.code < 500) { save(null); throw new Error("session"); } throw new Error("réseau"); }
    keep(r.j);
  }
  async function token() { if (!sess) throw new Error("session"); if (Date.now() > sess.exp) await refresh(); return sess.access; }
  async function rpc(name, args) {
    let r = await post("/rest/v1/rpc/c_" + name, args, await token());
    if (r.code === 401) { await refresh(); r = await post("/rest/v1/rpc/c_" + name, args, sess.access); }
    return r.j;
  }
  async function fn(body) {
    let r = await post("/functions/v1/abonnement", body, await token());
    if (r.code === 401) { await refresh(); r = await post("/functions/v1/abonnement", body, sess.access); }
    return r.j;
  }
  const norm = (s) => String(s || "").toLowerCase().trim();
  async function login(id, pw) {
    id = norm(id);
    let r;
    try { r = await grant("password", { email: id + "@" + C.domain, password: pw }); } catch (e) { return { ok: false, msg: "Pas de connexion internet." }; }
    if (!r.ok) return { ok: false, msg: r.code === 400 ? "Identifiant ou mot de passe incorrect." : "Connexion impossible pour le moment (" + r.code + ")." };
    keep(r.j, id); return { ok: true };
  }
  async function register(id, pw, app, sponsor) {
    id = norm(id);
    let r;
    try { r = await post("/rest/v1/rpc/usr_register", { p_username: id, p_password: pw, p_app: app || "pcwin", p_sponsor: sponsor || null }); } catch (e) { return { ok: false, msg: "Pas de connexion internet." }; }
    const st = r.j && r.j.status;
    if (st === "ok") return login(id, pw);
    const M = { invalid: "Identifiant : 3 à 20 caractères (lettres minuscules, chiffres ou _).", bad_code: "Mot de passe : 6 caractères minimum.", taken: "Cet identifiant est déjà pris, choisis-en un autre.", rate_limited: "Trop de créations de comptes : réessaie dans quelques minutes." };
    return { ok: false, msg: M[st] || "Création impossible pour le moment." };
  }
  window.FTAPI = { has: () => !!sess, id: () => (sess && sess.id) || "", uid: () => (sess && sess.uid) || "", login, register, rpc, fn, refresh, clear: () => save(null) };
})();
