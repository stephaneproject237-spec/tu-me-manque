(function () {
  "use strict";
  const CFG = window.TMM_CONFIG;
  const root = document.getElementById("app");
  // Petit client Supabase sans bibliothèque externe (connexion Google avec PKCE).
  const KEY = "tmm_admin_session", PKCE = "tmm_admin_pkce";
  const store = {
    get() { try { return JSON.parse(localStorage.getItem(KEY) || "null"); } catch (e) { return null; } },
    set(v) { try { localStorage.setItem(KEY, JSON.stringify(v)); } catch (e) { /* ignoré */ } },
    clear() { try { localStorage.removeItem(KEY); } catch (e) { /* ignoré */ } },
  };
  async function http(method, path, body, token) {
    const headers = { apikey: CFG.SUPABASE_ANON_KEY, "Content-Type": "application/json", Accept: "application/json" };
    if (token) headers.Authorization = "Bearer " + token;
    const res = await fetch(CFG.SUPABASE_URL + path, { method, headers, body: body == null ? undefined : JSON.stringify(body) });
    return { code: res.status, text: await res.text() };
  }
  function saveSession(j) {
    const now = Math.floor(Date.now() / 1000);
    store.set({ access: j.access_token, refresh: j.refresh_token, email: (j.user && j.user.email) || "", expires: j.expires_at || now + (j.expires_in || 3600) });
  }
  let pending = null;
  function session(force) {
    if (pending) return pending;
    pending = (async () => {
      try {
        const now = Math.floor(Date.now() / 1000);
        const s = store.get();
        if (!force && s && s.access && s.expires > now + 60) return s;
        if (s && s.refresh) {
          const r = await http("POST", "/auth/v1/token?grant_type=refresh_token", { refresh_token: s.refresh });
          if (r.code >= 200 && r.code < 300) { saveSession(JSON.parse(r.text)); return store.get(); }
          store.clear();
        }
        throw new Error("NO_SESSION");
      } finally { pending = null; }
    })();
    return pending;
  }
  async function authed(method, path, body) {
    let s = await session(false);
    let r = await http(method, path, body, s.access);
    if (r.code === 401) { s = await session(true); r = await http(method, path, body, s.access); }
    if (r.code < 200 || r.code >= 300) { let m = r.text; try { m = JSON.parse(r.text).message || m; } catch (e) { /* ignoré */ } throw new Error(m); }
    return r.text ? JSON.parse(r.text) : null;
  }
  const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const fdate = (t) => (t ? new Date(t).toLocaleString("fr-FR", { dateStyle: "medium", timeStyle: "short" }) : "date inconnue");
  const fbirth = (d) => { const x = new Date(String(d).slice(0, 10) + "T12:00:00"); return isNaN(x) ? String(d) : x.toLocaleDateString("fr-FR", { dateStyle: "long" }); };
  const phone = (p) => (p ? "+" + p : "—");

  const S = {
    stage: "loading",      // loading | login | denied | app
    email: "",
    tab: "users",
    users: [], fr: { current: [], history: [] }, threads: [],
    q: "", sort: (function(){try{return localStorage.getItem("tmm_sort")||"created"}catch(e){return "created"}})(), loaded: false, err: null,
    thread: null,          // { id, name, msgs }
    modal: null,           // { type: "delete" | "history" | "update", user }
    targets: null,         // nombre de personnes à prévenir par application et appareil
    busy: false,
    subsMode: false, subs: null, subsView: "main", ref: null, proof: {}, ftMode: false, ft: null, sel: {}, exp: {}, fq: "",   // page « Clients Free tune »
  };

  const call = (name, args) => authed("POST", "/rest/v1/rpc/" + name, args || {});

  // ---------- Connexion ----------
  async function init() {
    const q = new URLSearchParams(location.search);
    const pg = q.get("p");
    try { if (pg) localStorage.setItem("tmm_admin_page", pg); const pp = pg || localStorage.getItem("tmm_admin_page"); S.ftMode = pp === "ft"; S.subsMode = pp === "subs"; } catch (e) { S.ftMode = pg === "ft"; S.subsMode = pg === "subs"; }
    const code = q.get("code"), err = q.get("error_description");
    if (code || err) {
      try { history.replaceState(null, "", location.pathname); } catch (e) { /* ignoré */ }
      if (err) { S.stage = "login"; S.err = "Connexion refusée : " + err; return render(); }
      const verifier = localStorage.getItem(PKCE);
      localStorage.removeItem(PKCE);
      if (verifier) {
        const r = await http("POST", "/auth/v1/token?grant_type=pkce", { auth_code: code, code_verifier: verifier });
        if (r.code >= 200 && r.code < 300) saveSession(JSON.parse(r.text));
        else { S.stage = "login"; S.err = "Connexion impossible. Réessaie."; return render(); }
      }
    }
    const s = store.get();
    if (!s) { S.stage = "login"; return render(); }
    await checkAdmin(s);
  }

  async function checkAdmin(s) {
    S.email = s.email || "";
    try {
      const r = await call("admin_whoami");
      if (r && r.admin) { S.stage = "app"; S.err = null; render(); if (S.subsMode) await loadSubs(); else if (S.ftMode) await loadFt(); else await loadAll(); startPolling(); }
      else { S.stage = "denied"; render(); }
    } catch (e) {
      if (e.message === "NO_SESSION") { S.stage = "login"; S.err = null; return render(); }
      S.stage = "denied"; S.err = e.message || "Erreur";
      render();
    }
  }

  async function login() {
    const verifier = b64url(crypto.getRandomValues(new Uint8Array(48)));
    const challenge = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
    localStorage.setItem(PKCE, verifier);
    const back = location.origin + location.pathname.replace(/index\.html$/, "");
    location.href = CFG.SUPABASE_URL + "/auth/v1/authorize?provider=google&redirect_to=" + encodeURIComponent(back) +
      "&code_challenge=" + challenge + "&code_challenge_method=s256";
  }

  // ---------- Données ----------
  async function loadAll() {
    try {
      const [u, f, t] = await Promise.all([call("admin_users"), call("admin_friendships"), call("admin_threads")]);
      S.users = u || []; S.fr = f || { current: [], history: [] }; S.threads = t || [];
      try { S.targets = await call("admin_update_targets"); } catch (e) { S.targets = null; }
      S.loaded = true; S.err = null;
    } catch (e) {
      S.err = "Chargement impossible : " + (e.message || e);
    }
    renderContent();
  }

  let pollT = null;
  function startPolling() {
    clearInterval(pollT);
    pollT = setInterval(async () => {
      if (document.hidden || S.stage !== "app" || S.modal) return;
      if (S.subsMode) { if (S.subsView === "ref") await loadRef(); else await loadSubs(); }
      else if (S.ftMode) await loadFt();
      else if (S.thread) await openThread(S.thread.id, S.thread.name, true);
      else await loadAll();
    }, S.subsMode ? 15000 : S.ftMode ? 2000 : 5000);
  }

  // ---------- Messagerie ----------
  async function openThread(id, name, silent) {
    try {
      const msgs = await call("admin_thread", { p_user: id });
      const first = !S.thread || S.thread.id !== id;
      S.thread = { id, name, msgs: msgs || [] };
      renderThread(first);
      if (first) loadAll();
    } catch (e) { if (!silent) alert("Impossible d'ouvrir la conversation."); }
  }

  async function sendThread() {
    const inp = document.getElementById("t-in");
    const body = inp.value.trim();
    if (!body || S.busy || !S.thread) return;
    S.busy = true;
    try {
      const r = await call("admin_send", { p_user: S.thread.id, p_body: body });
      if (r && r.status === "ok") { inp.value = ""; await openThread(S.thread.id, S.thread.name, true); document.getElementById("t-list").scrollTop = 1e9; }
      else alert("Message non envoyé.");
    } catch (e) { alert("Message non envoyé."); }
    S.busy = false;
  }

  // ---------- Suppression ----------
  async function deleteUser() {
    const u = S.modal && S.modal.user;
    if (!u || S.busy) return;
    S.busy = true; renderModal();
    try {
      const r = await call("admin_delete_user", { p_id: u.id });
      if (!r || r.status !== "ok") throw new Error(r && r.status);
      S.modal = null; S.busy = false;
      await loadAll(); renderModal();
    } catch (e) {
      S.busy = false; renderModal();
      alert("Suppression impossible : " + (e.message || e));
    }
  }

  async function linkUser() {
    const m = S.modal, u = m && m.user;
    if (!u || S.busy) return;
    const q = (document.getElementById("m-in").value || "").trim().replace(/^@/, "").toLowerCase();
    const t = S.users.find((x) => x.username && x.username.toLowerCase() === q);
    if (!t) { alert("Aucun compte avec ce pseudo."); return; }
    if (t.id === u.id) { alert("Choisis un autre compte."); return; }
    S.busy = true; renderModal();
    try {
      const r = await call("admin_link", { p_user: u.id, p_to: t.id });
      if (!r || r.status !== "ok") throw new Error(r && r.status);
      S.modal = null; S.busy = false;
      await loadAll(); renderModal();
    } catch (e) {
      S.busy = false; renderModal();
      alert("Impossible de relier : " + (e.message || e) + "\n(As-tu collé admin4.sql dans Supabase ?)");
    }
  }




  // Boîte de confirmation dans la page : le WebView d'Android ignore confirm() et alert(), ils ne s'affichaient jamais.
  function ask(msg, okLabel) {
    return new Promise((res) => {
      const o = document.createElement("div");
      o.style.cssText = "position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,.62);display:flex;align-items:center;justify-content:center;padding:24px";
      o.innerHTML = '<div class="card glass" style="max-width:340px;width:100%;padding:20px;margin:0"><p style="margin:0 0 16px;white-space:pre-line;line-height:1.4">' + esc(msg) + '</p><div class="row" style="gap:10px"><button data-k="0" class="grow" style="background:rgba(255,255,255,.14);color:inherit">Annuler</button><button data-k="1" class="grow btn small">' + esc(okLabel || "Confirmer") + "</button></div></div>";
      o.addEventListener("click", (e) => { const b = e.target.closest("[data-k]"); if (!b) return; o.remove(); res(b.dataset.k === "1"); });
      document.body.appendChild(o);
    });
  }
  // ---------- Suivi des abonnements ----------
  async function loadSubs(manual) {
    try { S.subs = await call("admin_subs_overview"); S.err = null; }
    catch (e) { S.err = "Chargement impossible : " + (e.message || e) + " (as-tu collé le code SQL « suivi des abonnements » dans Supabase ?)"; }
    renderContent();
  }
  async function loadRef() {
    try { S.ref = await call("admin_ref_overview"); S.err = null; }
    catch (e) { S.err = "Chargement impossible : " + (e.message || e) + " (as-tu collé le code SQL « cagnotte » dans Supabase ?)"; }
    renderContent();
  }
  // Réduit l'image (preuve de paiement) puis l'envoie dans le dossier de l'admin du stockage public « chat ».
  async function uploadProof(file) {
    const bmp = await createImageBitmap(file);
    const k = Math.min(1, 1280 / Math.max(bmp.width, bmp.height));
    const cv = document.createElement("canvas"); cv.width = Math.round(bmp.width * k); cv.height = Math.round(bmp.height * k);
    cv.getContext("2d").drawImage(bmp, 0, 0, cv.width, cv.height);
    const blob = await new Promise((r) => cv.toBlob(r, "image/jpeg", 0.85));
    const s = await session(false);
    const uid = JSON.parse(atob(s.access.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))).sub;
    const hex = Array.from(crypto.getRandomValues(new Uint8Array(12))).map((b) => b.toString(16).padStart(2, "0")).join("");
    const path = uid + "/preuve" + hex + ".jpg";
    const res = await fetch(CFG.SUPABASE_URL + "/storage/v1/object/chat/" + path, { method: "POST", headers: { apikey: CFG.SUPABASE_ANON_KEY, Authorization: "Bearer " + s.access, "Content-Type": "image/jpeg" }, body: blob });
    if (!res.ok) throw new Error("envoi de l'image refusé (" + res.status + ")");
    return path;
  }
  function refHtml() {
    const d = S.ref;
    if (!d) return S.err ? '<p class="muted err">' + esc(S.err) + "</p>" : '<div class="center" style="min-height:50vh"><div class="spin"></div></div>';
    const pendBox = (u) => `<div class="card" style="margin-top:8px;padding:10px;background:rgba(255,193,7,.14)">
          <div><b>💸 @${esc(u.username)} : ${fcfa(u.pending_amount)}</b></div>
          <div class="k">Numéro : <b>${esc(u.pending_phone)}</b></div><div class="k">Nom sur la transaction : <b>${esc(u.pending_name)}</b></div><div class="k">Demandé le ${fdate(u.pending_at)}</div>
          <div class="row" style="margin-top:8px;gap:8px"><button class="btn small" data-a="refproof" data-id="${u.pending_id}">📷 ${S.proof[u.pending_id] ? "Changer l'image" : "Joindre la preuve"}</button>
            <button class="btn small" data-a="refdone" data-id="${u.pending_id}" data-n="${esc(u.username)}">✅ Dépôt effectué</button></div>
          ${S.proof[u.pending_id] ? '<div class="k" style="margin-top:6px">✅ Preuve jointe</div>' : ""}</div>`;
    const pend = d.filter((u) => u.pending_id).sort((a, b) => new Date(a.pending_at) - new Date(b.pending_at));
    const total = pend.reduce((n, u) => n + (Number(u.pending_amount) || 0), 0);
    const recap = `<h2>💸 Retraits en cours (${pend.length})</h2>` + (pend.length ? `<div class="k">Total à envoyer : <b>${fcfa(total)}</b> · du plus ancien au plus récent. Chaque dépôt se valide à la main.</div>${pend.map(pendBox).join("")}` : '<p class="muted">Aucune demande de retrait en cours.</p>');
    const rows = d.map((u) => `<div class="card glass srow"><div class="row"><span class="name grow">@${esc(u.username || "?")}</span><b>${fcfa(u.wallet)}</b>${u.pending_id ? '<span class="chip">💸 retrait demandé</span>' : ""}</div>
        <div class="k" style="margin-top:4px">👥 ${u.filleuls} filleul${u.filleuls > 1 ? "s" : ""} · 💳 ${u.payes} ont payé le premier mois${Number(u.paid_out) ? " · déjà versé : " + fcfa(u.paid_out) : ""}</div></div>`).join("") || '<p class="muted">Personne n\'a encore de filleul.</p>';
    return (S.err ? '<p class="err muted">' + esc(S.err) + "</p>" : "") + recap + `<h2>🎁 Parrainage</h2>${rows}<input type="file" id="proofin" accept="image/*" style="display:none">`;
  }
  const MOIS = ["janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc."];
  const fcfa = (n) => new Intl.NumberFormat("fr-FR").format(Math.round(Number(n) || 0)).replace(/[\u202f\u00a0]/g, " ") + " FCFA";
  const kfmt = (n) => { n = Number(n) || 0; return n >= 1e6 ? (n / 1e6).toFixed(1).replace(".0", "") + " M" : n >= 1e3 ? (n / 1e3).toFixed(1).replace(".0", "") + " k" : String(n); };
  const mlabel = (m) => { const a = String(m).split("-"); return MOIS[(+a[1] || 1) - 1]; };
  const mlong = (m) => { const a = String(m).split("-"); return MOIS[(+a[1] || 1) - 1] + " " + a[0]; };
  const dshort = (t) => t ? new Date(t).toLocaleDateString("fr-FR", { day: "numeric", month: "short" }) : "—";
  const inDays = (t) => { const d = Math.ceil((new Date(t) - Date.now()) / 86400000); return d <= 0 ? "aujourd'hui" : d === 1 ? "demain" : "dans " + d + " j"; };
  function subsHtml() {
    if (S.subsView === "ref") return '<div class="row" style="gap:8px;margin-bottom:8px"><button class="btn small" data-a="subsview" data-id="main">‹ Abonnements</button></div>' + refHtml();
    const d = S.subs;
    if (!d) return S.err ? '<p class="muted err">' + esc(S.err) + "</p>" : '<div class="center" style="min-height:50vh"><div class="spin"></div></div>';
    const mo = d.month || {}, tot = d.total || {};
    // Les 6 derniers mois, même ceux sans paiement.
    const byM = {}; (d.months || []).forEach((x) => { byM[x.m] = x; });
    const keys = []; { const [y, m] = String(mo.key || "").split("-").map(Number); for (let i = 5; i >= 0; i--) { const dt = new Date(y, m - 1 - i, 1); keys.push(dt.getFullYear() + "-" + String(dt.getMonth() + 1).padStart(2, "0")); } }
    const max = Math.max(1, ...keys.map((k) => Number((byM[k] || {}).rev) || 0));
    const delta = Number(mo.prev_revenue) > 0 ? Math.round(((Number(mo.revenue) - Number(mo.prev_revenue)) / Number(mo.prev_revenue)) * 100) : null;
    const dtxt = delta == null ? (Number(mo.revenue) > 0 ? "premier mois avec des ventes" : "pas encore de vente") : (delta >= 0 ? "▲ +" : "▼ ") + delta + " % vs mois dernier";
    const bars = keys.map((k) => { const x = byM[k] || {}, v = Number(x.rev) || 0, h = Math.max(v ? 8 : 3, Math.round((v / max) * 100));
      return `<div class="bcol${k === mo.key ? " now" : ""}"><span class="bv">${v ? kfmt(v) : ""}</span><div class="bar" style="height:${h}px"></div><span class="bl">${mlabel(k)}</span></div>`; }).join("");
    const person = (u) => "@" + esc(u.username || "?");
    const cur = (d.current || []).map((u) => `<div class="card glass srow"><div class="row"><span class="name grow">${person(u)}</span>
        <span class="chip" style="${u.active ? "background:rgba(46,204,113,.18);color:#2E9E5B" : "background:rgba(255,138,128,.2);color:#C62828"}">${u.active ? "✅ actif jusqu'au " + dshort(u.paid_until) : "⛔ expiré"}</span></div>
        <div class="row" style="margin-top:6px"><span class="k grow">${esc(u.phone || u.email || "—")} · payé le ${dshort(u.at)}${Number(u.n_month) > 1 ? " (" + u.n_month + " fois)" : ""}</span><b>${fcfa(u.amount)}</b></div>
        ${Number(u.times) > 1 ? `<div class="k" style="margin-top:2px">🔁 fidèle : ${u.times} abonnements depuis le début</div>` : ""}</div>`).join("");
    const soon = (d.soon || []).map((u) => `<div class="card glass srow"><div class="row"><span class="name grow">${person(u)}</span><span class="chip">⏳ se termine ${inDays(u.paid_until)}</span></div><div class="k" style="margin-top:4px">${esc(u.email || "")}</div></div>`).join("");
    const exp = (d.expired || []).map((u) => `<div class="card glass srow"><div class="row"><span class="name grow">${person(u)}</span><span class="chip" style="background:rgba(255,138,128,.2);color:#C62828">terminé le ${dshort(u.paid_until)}</span></div><div class="k" style="margin-top:4px">${esc(u.email || "")}</div></div>`).join("");
    const tests = (d.tests || []).filter((u) => u.state === "active").map((u) => { const st = u.state === "active" ? "✅ abonnement actif jusqu'au " + dshort(u.paid_until) : u.state === "trial" ? "🎁 essai gratuit (" + (3 - (Number(u.trial_dl) || 0)) + " téléchargement(s) restant(s))" : "⛔ abonnement ou essai terminé";
      return `<div class="card glass srow"><div class="row"><span class="name grow">${person(u)}</span><button class="btn small" data-a="subsreset" data-id="${esc(u.id)}" data-n="${esc(u.username || "")}">↺ Remettre à 0</button></div>
        <div class="k" style="margin-top:6px">${st}</div><div class="k">Numéro de test ${esc(u.phone || "")} · ${esc(u.email || "")}</div></div>`; }).join("") || '<p class="muted">Aucun compte n\'a actuellement un abonnement payé avec un numéro de test.</p>';
    const months = (d.months || []).slice().reverse().map((x) => `<div class="mrow"><span class="grow">${mlong(x.m)}</span><span class="k">${x.n} abonnement${x.n > 1 ? "s" : ""}</span><b>${fcfa(x.rev)}</b></div>`).join("");
    return '<div class="row" style="gap:8px;margin-bottom:8px"><button class="btn small" data-a="subsview" data-id="ref">🎁 Parrainage</button></div>' + (S.err ? '<p class="err muted">' + esc(S.err) + "</p>" : "") + `
      <div class="shero"><div class="sk">Chiffre d'affaires total</div><div class="sbig">${fcfa(tot.revenue)}</div>
        <div class="sk">${tot.payments || 0} abonnement${tot.payments > 1 ? "s" : ""} · ${tot.payers || 0} client${tot.payers > 1 ? "s" : ""}${tot.first ? " · depuis le " + dshort(tot.first) : ""}</div></div>
      <div class="sgrid">
        <div class="stile glass"><span>Ce mois-ci (${mlabel(mo.key)})</span><b>${fcfa(mo.revenue)}</b><small>${esc(dtxt)}</small></div>
        <div class="stile glass"><span>Abonnés actifs</span><b>${d.active_count || 0}</b><small>${d.trial_count || 0} en essai gratuit</small></div>
        <div class="stile glass"><span>Abonnements ce mois</span><b>${mo.payments || 0}</b><small>${mo.payers || 0} client${mo.payers > 1 ? "s" : ""} (mois dernier : ${mo.prev_payments || 0})</small></div>
        <div class="stile glass"><span>À renouveler (7 j)</span><b>${(d.soon || []).length}</b><small>${(d.expired || []).length} terminé${(d.expired || []).length > 1 ? "s" : ""} ces 30 jours</small></div></div>
      <div class="card glass"><div class="name" style="font-size:15px">Chiffre d'affaires par mois</div><div class="bars">${bars}</div></div>
      <h2>👥 Abonnés de ${mlong(mo.key)} (${(d.current || []).length})</h2>${cur || '<p class="muted">Aucun abonnement ce mois-ci pour le moment.</p>'}
      ${(d.soon || []).length ? `<h2>⏳ À relancer : fin dans moins de 7 jours</h2>${soon}` : ""}
      ${(d.expired || []).length ? `<h2>💤 Terminés récemment</h2>${exp}` : ""}
      <h2>🧪 Comptes de test (paiement avec un numéro de test)</h2>${tests}
      <h2>📅 Récap mois par mois</h2><div class="card glass">${months || '<p class="k">Aucun paiement pour l\'instant.</p>'}</div>`;
  }
  // ---------- Clients Free tune ----------
  async function loadFt() {
    // Demande aux Android silencieux de se signaler (au plus une fois par minute) : ils apparaissent « sur internet » s'ils sont joignables.
    if (Date.now() - (S.pingAt || 0) > 8000) { S.pingAt = Date.now(); call("admin_ft_ping").catch(() => {}); }
    try {
      S.ft = await call("admin_ft_clients"); S.err = null; S.ftAt = Date.now();
    } catch (e) {
      S.err = "Chargement impossible : " + (e.message || e) + " (as-tu collé le code SQL « clients » dans Supabase ?)";
    }
    if (S.modal && S.modal.type === "ftfriends") renderModal();
    renderContent();
  }
  function ftAgeText() {
    if (S.ftBusy) return "Vérification des téléphones en cours…";
    if (S.ftNote) return S.ftNote;
    if (!S.ftAt) return "Chargement…";
    const sec = Math.max(0, Math.round((Date.now() - S.ftAt) / 1000));
    return "Mis à jour " + (sec < 2 ? "à l'instant" : "il y a " + sec + " s");
  }
  // Met à jour la barre d'état sans redessiner la liste (garde le clavier et la sélection).
  function paintFtBar() {
    const a = document.getElementById("ftage"); if (a) a.textContent = ftAgeText();
    document.querySelectorAll(".ftref").forEach((b) => {
      b.classList.toggle("busy", !!S.ftBusy); b.disabled = !!S.ftBusy;
      const sp = b.querySelector("span"); if (sp) sp.textContent = S.ftBusy ? "Vérification…" : "Actualiser";
    });
  }
  setInterval(() => { if (S.ftMode && !document.hidden) paintFtBar(); }, 1000);
  // Actualisation manuelle : on demande tout de suite aux Android de se signaler, puis on relit les statuts.
  async function ftRefresh() {
    if (S.ftBusy) return;
    S.ftBusy = true; S.ftNote = ""; paintFtBar();
    let n = 0;
    try { n = Number(await call("admin_ft_ping")) || 0; } catch (e) { /* le SQL de présence n'est peut-être pas collé */ }
    S.pingAt = Date.now();
    try { await loadFt(); } catch (e) { /* loadFt gère ses erreurs */ }
    S.ftBusy = false;
    S.ftNote = S.err ? "⚠️ Échec de l'actualisation" : n ? "✓ À jour · " + n + " sollicité" + (n > 1 ? "s" : "") : "✓ Statuts à jour";
    paintFtBar();
    setTimeout(() => { if (S.ftMode) loadFt(); }, 3000); // les réponses des téléphones sollicités arrivent en quelques secondes
    setTimeout(() => { S.ftNote = ""; paintFtBar(); }, 5000);
  }
  const ago = (t) => {
    if (!t) return "jamais vu";
    const m = Math.max(0, Math.round((Date.now() - new Date(t)) / 60000));
    return m < 1 ? "à l'instant" : m < 60 ? "vu il y a " + m + " min" : m < 1440 ? "vu il y a " + Math.round(m / 60) + " h" : "vu il y a " + Math.round(m / 1440) + " j";
  };
  // Types de comptes : iPhone, Android (Free tune), PC / Mac / Free tune server (clients avec identifiant + mot de passe), et les serveurs automatiques de chaque logiciel.
  const isHelper = (u) => u.platform === "android" || u.platform === "windows" || u.platform === "mac" || u.platform === "srvdroid";
  const isPc = (u) => u.platform === "pcwin" || u.platform === "pcmac" || u.platform === "pcsrv";
  const isIos = (u) => !isHelper(u) && !isPc(u);
  const ICON = { ios: "🍎", android: "🤖", pcwin: "🪟", pcmac: "🍏", pcsrv: "📲", windows: "🖥️", mac: "🖥️", srvdroid: "🖥️" };
  const KIND = { ios: "iPhone", android: "Android", pcwin: "PC Windows", pcmac: "Mac", pcsrv: "Free tune server", windows: "Serveur PC", mac: "Serveur Mac", srvdroid: "Serveur Android" };
  const pcOn = (u) => !!u.last_seen && Date.now() - new Date(u.last_seen).getTime() < 90000;
  function ftCard(u, owned) {
    const pc = isPc(u);
    const exp = pc && !!S.exp[u.id];
    const sel = !pc && !!S.sel[u.id];
    const nf = (u.friends || []).length;
    const st = u.state || (u.online ? "app" : "off");
    const android = isHelper(u);
    const lights = android
      ? `<div class="row" style="gap:6px;margin-top:6px">
          <span class="lt ${st === "app" ? "a" : ""}">Dans Free tune</span><span class="lt ${st === "net" ? "a" : ""}">Sur internet</span><span class="lt ${st === "off" ? "a" : ""}">Hors ligne</span></div>`
      : "";
    const share = android
      ? `<span class="chip" style="${u.share ? "background:rgba(46,204,113,.18);color:#58D68D" : "background:rgba(255,138,128,.18);color:#FF8A80"}">${u.share ? "✅ Partage accepté" : "⛔ Partage refusé"}</span>` : "";
    const jobs = Number(u.jobs) ? `<span class="chip">⏳ ${u.jobs} en cours</span>` : "";
    const dlN = Number(u.dl) || 0, helpN = Number(u.dl_helped) || 0;
    const ver = `<span class="chip">${u.app_ver ? "📱 " + esc(u.app_ver) : "📱 version inconnue"}</span> <span class="chip">⬇️ ${dlN} téléchargement${dlN > 1 ? "s" : ""}</span>` +
      (helpN ? ` <span class="chip">🤝 ${helpN} pour des amis</span>` : "");
    const where = isPc(u) ? (pcOn(u) ? "connecté maintenant" : ago(u.last_seen)) : st === "app" ? "dans Free tune" : st === "net" ? "sur internet (hors de Free tune)" : ago(u.last_seen);
    const kind = `<span class="chip">${ICON[u.platform] || "🍎"} ${KIND[u.platform] || "iPhone"}</span>` + (u.sponsor ? ` <span class="chip">🎁 parrain : @${esc(u.sponsor)}</span>` : "") + (u.owner ? ` <span class="chip">👤 serveur de @${esc(u.owner)}</span>` : "");
    // Clients PC / Mac / Free tune server : un clic déplie ses serveurs automatiques (en bas de la carte).
    const srvList = pc
      ? `<div class="row" style="margin-top:6px"><span class="k grow">🖥️ ${(owned || []).length} serveur${(owned || []).length > 1 ? "s" : ""} automatique${(owned || []).length > 1 ? "s" : ""}</span><span class="k">${exp ? "▲" : "▼"}</span></div>`
        + (exp ? `<div class="ftsub" style="margin-top:6px;display:flex;flex-direction:column;gap:6px">${
            (owned || []).length
              ? owned.map((s) => `<div class="row" style="background:rgba(255,255,255,.04);border-radius:10px;padding:8px 10px">
                  <span class="ond ${s.online ? "on" : ""}"></span><span class="name grow">@${esc(s.username)}</span>
                  <span class="chip">${ICON[s.platform] || "🖥️"} ${KIND[s.platform] || "Serveur"}</span>
                  <span class="k">${esc(s.online ? "en ligne" : ago(s.last_seen))}</span></div>`).join("")
              : `<p class="muted" style="margin:0">Aucun serveur rattaché pour l'instant.</p>`
          }</div>` : "")
      : "";
    return `<div class="card glass ftc${sel ? " sel" : ""}" data-a="${pc ? "ftexp" : "ftsel"}" data-id="${esc(u.id)}">
      <div class="row"><span class="ck">${sel ? "✓" : ""}</span><span class="ond ${isPc(u) ? (pcOn(u) ? "on" : "") : st === "app" ? "on" : st === "net" ? "net" : ""}"></span>
        <span class="name grow">@${esc(u.username)}</span>${jobs}${share}</div>
      <div class="row" style="margin-top:6px;flex-wrap:wrap;gap:6px">${kind}</div>
      <div class="row" style="margin-top:6px;flex-wrap:wrap;gap:6px">${ver}</div>
      ${lights}
      <div class="row" style="margin-top:6px"><span class="k grow">${esc(u.email || "—")} · ${esc(where)}</span>
        <button class="s" data-a="ftfr" data-id="${esc(u.id)}" style="padding:6px 12px">👥 ${nf} ami${nf > 1 ? "s" : ""}</button></div>
      ${srvList}</div>`;
  }
  function ftHtml() {
    if (!S.ft) return S.err ? '<p class="muted err">' + esc(S.err) + "</p>" : '<div class="center" style="min-height:50vh"><div class="spin"></div></div>';
    const q = S.fq.trim().toLowerCase().replace(/^@/, "");
    const all = S.ft.users || [];
    const list = q ? all.filter((u) => [u.username, u.email].some((x) => x && String(x).toLowerCase().includes(q))) : all;
    const sortU = (a, b) => (b.online - a.online) || String(a.username).localeCompare(String(b.username), "fr");
    const sortP = (a, b) => (pcOn(b) - pcOn(a)) || String(a.username).localeCompare(String(b.username), "fr");
    const ios = list.filter(isIos).sort(sortU), and = list.filter((u) => u.platform === "android").sort(sortU), srvs = list.filter((u) => u.platform === "windows" || u.platform === "mac" || u.platform === "srvdroid").sort(sortU);
    const pcw = list.filter((u) => u.platform === "pcwin").sort(sortP), pcm = list.filter((u) => u.platform === "pcmac").sort(sortP), pcs = list.filter((u) => u.platform === "pcsrv").sort(sortP);
    const nI = all.filter((u) => isIos(u) && S.sel[u.id]).length, nA = all.filter((u) => isHelper(u) && S.sel[u.id]).length;
    const st = S.ft.settings || {};
    const sw = (k, on, t, d) => `<div class="row" style="margin:10px 0"><div class="grow"><div class="name" style="font-size:15px">${t}</div><div class="k">${d}</div></div>
      <button class="swt ${on ? "on" : ""}" data-a="ftset" data-k="${k}" data-v="${on ? 0 : 1}" aria-label="${t}"><i></i></button></div>`;
    const cApp = all.filter((u) => u.state === "app").length, cNet = all.filter((u) => u.state === "net").length, cOff = all.filter((u) => u.state === "off").length;
    return (S.err ? '<p class="err muted">' + esc(S.err) + "</p>" : "") +
      `<div class="card glass ftbar"><div class="row"><div class="grow"><div class="name" style="font-size:16px">Statuts en direct</div><div class="k" id="ftage">${esc(ftAgeText())}</div></div>
        <button class="p ftref${S.ftBusy ? " busy" : ""}" data-a="ftrefresh" ${S.ftBusy ? "disabled" : ""}><i>↻</i><span>${S.ftBusy ? "Vérification…" : "Actualiser"}</span></button></div>
        <div class="ftstats"><div class="ftst"><b><span class="ond on"></span>${cApp}</b><span>Dans Free tune</span></div><div class="ftst"><b><span class="ond net"></span>${cNet}</b><span>Sur internet</span></div><div class="ftst"><b><span class="ond"></span>${cOff}</b><span>Hors ligne</span></div></div>
        <div class="k" style="margin-top:8px">${all.length} compte${all.length > 1 ? "s" : ""} · mise à jour automatique toutes les 2 secondes</div></div>
      <div class="card glass">
        ${sw("auto_friends", !!st.auto_friends, "🤝 Amis automatiques", "Tous les comptes Android en ligne deviennent amis avec tous les iPhone en ligne.")}
        ${sw("random_dispatch", st.random_dispatch !== false, "🎲 Répartition au hasard", "Chaque demande d'un iPhone part vers un seul Android en ligne qui accepte de partager.")}
        ${sw("p2p", !!st.p2p, "🧩 p2p", "Le fichier audio d'un iPhone est découpé en morceaux : chaque Android en ligne en télécharge et en envoie, l'iPhone les recolle. Si un Android s'arrête, les autres reprennent ses morceaux. (Audio MP3 léger et standard ; vidéos et haute qualité : méthode habituelle.)")}</div>
      <div class="card glass"><p class="k" style="margin:0 0 8px">Touche des comptes pour les sélectionner, puis relie-les ou sépare-les. Un iPhone et un Android sélectionnés : ils sont reliés entre eux. Plusieurs comptes du même type (par exemple des Android) : ils sont tous reliés entre eux.</p>
        <div class="row"><span class="chip">🍎 ${nI} iPhone</span><span class="chip">🤖 ${nA} Android</span>
        <span class="grow"></span><button class="s" data-a="ftclear" ${nI + nA ? "" : "disabled"}>Effacer</button></div>
        <div class="row" style="margin-top:8px"><button class="p grow" data-a="ftlink" ${(nI && nA) || nI > 1 || nA > 1 ? (S.busy ? "disabled" : "") : "disabled"}>🔗 Lier la sélection</button><button class="d grow" data-a="ftunlink" ${(nI && nA) || nI > 1 || nA > 1 ? (S.busy ? "disabled" : "") : "disabled"}>✂️ Délier</button></div></div>
      <input class="q" id="fq" placeholder="Rechercher un pseudo ou un e-mail…" value="${esc(S.fq)}" autocomplete="off">
      <h2>🍎 Comptes iPhone (${ios.length})</h2>${ios.map((u) => ftCard(u)).join("") || '<p class="muted">Aucun compte iPhone.</p>'}
      <h2>🤖 Comptes Android (${and.length})</h2>${and.map((u) => ftCard(u)).join("") || '<p class="muted">Aucun compte Android.</p>'}
      <h2>🪟 Clients PC (${pcw.length})</h2>${pcw.map((u) => ftCard(u, srvs.filter((s) => s.owner === u.username))).join("") || '<p class="muted">Aucun client PC.</p>'}
      <h2>🍏 Clients Mac (${pcm.length})</h2>${pcm.map((u) => ftCard(u, srvs.filter((s) => s.owner === u.username))).join("") || '<p class="muted">Aucun client Mac.</p>'}
      <h2>📲 Clients Free tune server (${pcs.length})</h2>${pcs.map((u) => ftCard(u, srvs.filter((s) => s.owner === u.username))).join("") || '<p class="muted">Aucun client Free tune server.</p>'}
      <h2>🖥️ Serveurs automatiques (${srvs.length})</h2>${srvs.map((u) => ftCard(u)).join("") || '<p class="muted">Aucun serveur.</p>'}`;
  }
  async function ftDo(fn, a, b, ok) {
    if (S.busy) return;
    S.busy = true; renderContent();
    try { const n = await call(fn, { p_a: a, p_b: b }); S.busy = false; await loadFt(); return n; }
    catch (e) { S.busy = false; renderContent(); alert("Impossible : " + (e.message || e)); }
  }
  const ftIds = (plat) => (S.ft ? S.ft.users : []).filter((u) => S.sel[u.id] && (plat === "android" ? isHelper(u) : isIos(u))).map((u) => u.id);

  // ---------- Affichage ----------
  function render() {
    if (S.stage === "loading") { root.innerHTML = '<div class="center"><div class="spin"></div></div>'; return; }
    if (S.stage === "login") {
      root.innerHTML = `<div class="center"><div style="font-size:56px">🎧</div><h1 style="margin:0">Free tune Admin</h1>
        <p class="muted" style="padding:0">Réservé à l'administrateur.</p>
        ${S.err ? '<p class="err">' + esc(S.err) + "</p>" : ""}
        <button class="p" data-a="login" style="font-size:17px;padding:14px 24px">Continuer avec Google</button></div>`;
      return;
    }
    if (S.stage === "denied") {
      root.innerHTML = `<div class="center"><div style="font-size:56px">⛔</div><h2 style="margin:0">Accès refusé</h2>
        <p class="muted" style="padding:0">${esc(S.email)} n'est pas autorisé.${S.err ? "<br>" + esc(S.err) : ""}</p>
        <button class="s" data-a="logout">Changer de compte</button></div>`;
      return;
    }
    if (S.subsMode) {
      root.innerHTML = `<header><button data-a="ftback" aria-label="Retour">‹</button><h1>💰 Suivi des abonnements</h1><button data-a="subsrefresh" aria-label="Actualiser">↻</button></header>
        <main id="content"></main><div id="modal"></div>`;
      renderContent();
      return;
    }
    if (S.ftMode) {
      root.innerHTML = `<header><button data-a="ftback" aria-label="Retour">‹</button><h1>🎧 Clients Free tune</h1><button data-a="ftrefresh" aria-label="Actualiser les statuts" class="ftref"><i>↻</i></button></header>
        <main id="content"></main><div id="modal"></div>`;
      renderContent();
      return;
    }
    root.innerHTML = `<header><h1>🛠️ Admin</h1><button data-a="refresh" aria-label="Actualiser">↻</button><button data-a="logout">Quitter</button></header>
      <main id="content"></main>
      <nav><button data-a="tab" data-t="users" id="n-users"></button><button data-a="tab" data-t="friends" id="n-friends"></button><button data-a="tab" data-t="msgs" id="n-msgs"></button><button data-a="tab" data-t="upd" id="n-upd"></button></nav>
      <div id="modal"></div><div id="thread"></div>`;
    renderContent();
  }

  function unreadTotal() { return S.threads.reduce((n, t) => n + (Number(t.unread) || 0), 0); }

  function renderNav() {
    const set = (id, label, on) => { const b = document.getElementById(id); if (b) { b.innerHTML = label; b.classList.toggle("on", on); } };
    set("n-users", "👥 Comptes <small>(" + S.users.length + ")</small>", S.tab === "users");
    set("n-friends", "💗 Amitiés <small>(" + S.fr.current.length + ")</small>", S.tab === "friends");
    const u = unreadTotal();
    set("n-msgs", "💬 Messages" + (u ? ' <span class="dot">' + u + "</span>" : ""), S.tab === "msgs");
    set("n-upd", "🔔 Mise à jour", S.tab === "upd");
  }

  function renderContent() {
    if (S.stage !== "app") return;
    if (S.subsMode) { const c1 = document.getElementById("content"); if (c1) c1.innerHTML = subsHtml(); return; }
    if (S.ftMode) {
      const c0 = document.getElementById("content");
      if (!c0) return;
      const keep = document.activeElement && document.activeElement.id === "fq";
      c0.innerHTML = ftHtml();
      if (keep) { const i = document.getElementById("fq"); if (i) { i.focus(); i.setSelectionRange(i.value.length, i.value.length); } }
      return;
    }
    renderNav();
    const c = document.getElementById("content");
    if (!c) return;
    if (!S.loaded) { c.innerHTML = S.err ? '<p class="muted err">' + esc(S.err) + "</p>" : '<div class="center" style="min-height:50vh"><div class="spin"></div></div>'; return; }
    const err = S.err ? '<p class="err muted">' + esc(S.err) + "</p>" : "";
    if (S.tab === "users") c.innerHTML = err + usersHtml();
    else if (S.tab === "friends") c.innerHTML = err + friendsHtml();
    else if (S.tab === "upd") c.innerHTML = err + updateHtml();
    else c.innerHTML = err + threadsHtml();
    const q = document.getElementById("q");
    if (q && S.focusQ) { q.focus(); q.setSelectionRange(q.value.length, q.value.length); }
  }

  // ---------- Prévenir d'une mise à jour ----------
  const UPD = {
    tmm_android: { app: "tmm", pf: "android", name: "Tu me manques", dev: "Android", ico: "🤖💗" },
    tmm_ios: { app: "tmm", pf: "ios", name: "Tu me manques", dev: "iPhone", ico: "🍎💗" },
    casque_android: { app: "casque", pf: "android", name: "Free tune", dev: "Android", ico: "🤖🎧" },
    casque_ios: { app: "casque", pf: "ios", name: "Free tune", dev: "iPhone", ico: "🍎🎧" },
  };
  function updText(k) {
    const u = UPD[k];
    if (u.pf === "android") return {
      title: "Mise à jour de " + u.name + " disponible 🎉",
      body: "Une nouvelle version de " + u.name + " est disponible. Ouvre l'appli, va dans Paramètres, touche « Mettre à jour maintenant » puis « Installer ». Si Android le demande, autorise l'installation.",
    };
    return {
      title: "Mise à jour de " + u.name + " disponible 🎉",
      body: "Une nouvelle version de " + u.name + " est disponible. Ferme complètement l'appli (glisse-la vers le haut) puis rouvre-la. Si l'ancienne version s'affiche encore, supprime l'icône de l'écran d'accueil et ajoute-la de nouveau depuis Safari (Partager, puis « Sur l'écran d'accueil »).",
    };
  }
  function updateHtml() {
    const tg = S.targets;
    return `<p class="muted" style="padding:0 0 8px">Choisis l'application et l'appareil : une notification « mise à jour disponible » avec la marche à suivre part chez tous ses utilisateurs.</p>` +
      Object.keys(UPD).map((k) => {
        const u = UPD[k], n = tg ? tg[k] : null;
        return `<div class="card glass"><div class="row"><span class="name grow">${u.ico} ${esc(u.name)} · ${esc(u.dev)}</span>
          <span class="chip">${n == null ? "?" : n + " personne" + (n > 1 ? "s" : "")}</span></div>
          <div class="row" style="margin-top:8px"><button class="p grow" data-a="updopen" data-k="${k}">🔔 Notifier</button></div></div>`;
      }).join("") + (tg ? "" : '<p class="err muted">Compteurs indisponibles : lance d\'abord le SQL « admin6 » dans Supabase.</p>');
  }
  function openUpdate(k) {
    const t = updText(k);
    S.modal = { type: "update", key: k, title: t.title, body: t.body, user: {} };
    renderModal();
  }
  async function sendUpdate() {
    const m = S.modal; if (!m || S.busy) return;
    const u = UPD[m.key];
    const title = document.getElementById("u-t").value.trim(), body = document.getElementById("u-b").value.trim();
    if (!title || !body) { alert("Le titre et le message ne peuvent pas être vides."); return; }
    S.busy = true; renderModal();
    try {
      const n = await call("admin_notify_update", { p_app: u.app, p_platform: u.pf, p_title: title, p_body: body });
      S.busy = false; m.done = "✓ Notification envoyée à " + n + " personne" + (n > 1 ? "s" : "") + ".";
    } catch (e) { S.busy = false; m.done = "⚠️ Envoi impossible : " + (e.message || e) + ". As-tu lancé le SQL « admin6 » ?"; }
    renderModal(); loadAll();
  }

  function filteredUsers() {
    const q = S.q.trim().toLowerCase().replace(/^@/, "");
    const base = S.users.slice().sort((a, b) => S.sort === "alpha"
      ? String(a.username || "~").toLowerCase().localeCompare(String(b.username || "~").toLowerCase(), "fr")
      : S.sort === "login" ? (new Date(b.last_sign_in_at || 0) - new Date(a.last_sign_in_at || 0))
      : (new Date(b.created_at || 0) - new Date(a.created_at || 0)));
    if (!q) return base;
    return base.filter((u) => [u.username, u.email, u.phone, u.device, u.partner_name].some((x) => x && String(x).toLowerCase().includes(q)));
  }

  function usersHtml() {
    const list = filteredUsers();
    return `<input class="q" id="q" placeholder="Rechercher (pseudo, e-mail, numéro, appareil)…" value="${esc(S.q)}" autocomplete="off">
      <div class="row" style="margin:8px 0"><span class="k grow">${list.length} compte${list.length > 1 ? "s" : ""}</span>
        ${[["created", "🕒 Création"], ["alpha", "🔤 A→Z"], ["login", "🔑 Activité"]].map(([k, l]) => `<button class="${S.sort === k ? "p" : "s"}" data-a="sort" data-k="${k}">${l}</button>`).join("")}</div>` +
      (list.map(userCard).join("") || '<p class="muted">Aucun compte.</p>');
  }

  function userCard(u) {
    const name = u.username ? "@" + esc(u.username) : "(sans pseudo)";
    return `<div class="card glass">
      <div class="row"><span class="name grow">${name}</span>${u.provider ? '<span class="chip">' + esc(u.provider) + "</span>" : ""}
        ${u.unread ? '<span class="dot">' + u.unread + "</span>" : ""}</div>
      <div class="grid">
        <span class="k">E-mail</span><span class="v">${esc(u.email || "—")}</span>
        <span class="k">Téléphone</span><span class="v">${esc(phone(u.phone))}</span>
        <span class="k">Date de naissance</span><span class="v">${u.birthdate ? esc(fbirth(u.birthdate)) : "—"}</span>
        <span class="k">Âge</span><span class="v">${u.age != null ? esc(u.age) + " ans" : "—"}</span>
        <span class="k">Appareil</span><span class="v">${esc(u.device || "inconnu (compte ancien)")}</span>
        <span class="k">Dernière activité</span><span class="v">${u.last_sign_in_at ? esc(fdate(u.last_sign_in_at)) : "jamais"}</span>
        <span class="k">Créé le</span><span class="v">${esc(fdate(u.created_at))}</span>
        <span class="k">Partenaire</span><span class="v">${u.partner_name ? "💗 @" + esc(u.partner_name) + (u.linked_at ? " · depuis " + esc(fdate(u.linked_at)) : "") : "aucun"}</span>
      </div>
      <div class="row">
        <button class="p" data-a="chat" data-id="${esc(u.id)}">💬 Chat</button>
        <button class="s" data-a="hist" data-id="${esc(u.id)}">📜 Amitiés</button>
        <button class="s" data-a="link" data-id="${esc(u.id)}">🔗 Relier à…</button>
        <button class="d" data-a="del" data-id="${esc(u.id)}">🗑 Supprimer</button>
      </div></div>`;
  }

  function pairCard(f, ended) {
    const side = (n, p, d) => `<div class="grow"><div class="name">@${esc(n || "?")}</div><div class="k">${esc(phone(p))}</div>${d !== undefined ? '<div class="k">' + esc(d || "appareil inconnu") + "</div>" : ""}</div>`;
    return `<div class="card glass"><div class="row">${side(f.a_name, f.a_phone, ended ? undefined : f.a_device)}<span style="font-size:24px">💗</span>${side(f.b_name, f.b_phone, ended ? undefined : f.b_device)}</div>
      <p class="k" style="margin:8px 0 0">${ended ? "Du " + esc(fdate(f.started_at)) + " au " + esc(fdate(f.ended_at)) + (f.ended_by ? " · terminé par " + esc(f.ended_by) : "") : "Depuis le " + esc(fdate(f.started_at))}</p></div>`;
  }

  function friendsHtml() {
    return `<h2>En cours (${S.fr.current.length})</h2>` + (S.fr.current.map((f) => pairCard(f, false)).join("") || '<p class="muted">Aucune amitié en cours.</p>') +
      `<h2>Historique (${S.fr.history.length})</h2>` + (S.fr.history.map((f) => pairCard(f, true)).join("") || '<p class="muted">Aucune amitié terminée.</p>');
  }

  function threadsHtml() {
    return `<h2>Conversations avec les comptes</h2><p class="k" style="margin:0 4px 6px">Pour écrire à quelqu'un qui n'a pas encore écrit : onglet Comptes → 💬 Chat.</p>` +
      (S.threads.map((t) => `<div class="card glass" data-a="chat" data-id="${esc(t.user_id)}" style="cursor:pointer">
        <div class="row"><span class="name grow">@${esc(t.username || "?")}</span>${Number(t.unread) ? '<span class="dot">' + t.unread + "</span>" : ""}</div>
        <div class="v">${t.last_from_admin ? "Vous : " : ""}${esc((t.last_body || "").slice(0, 120))}</div>
        <div class="k">${esc(fdate(t.last_at))}</div></div>`).join("") || '<p class="muted">Aucun message pour le moment.</p>');
  }

  // Clavier : le chat suit la zone réellement visible (sinon le clavier cache ce qu'on écrit).
  function fitThread() {
    const t = document.querySelector(".thread"), vv = window.visualViewport;
    if (!t || !vv) return;
    t.style.height = vv.height + "px";
    t.style.bottom = "auto";
    t.style.top = vv.offsetTop + "px";
    const l = document.getElementById("t-list");
    if (l && document.activeElement && document.activeElement.id === "t-in") l.scrollTop = 1e9;
  }
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", fitThread);
    window.visualViewport.addEventListener("scroll", fitThread);
  }

  function renderThread(scroll) {
    const el = document.getElementById("thread");
    if (!el) return;
    if (!S.thread) { el.innerHTML = ""; return; }
    const prev = document.getElementById("t-in");
    const draft = prev ? prev.value : "";
    const list = document.getElementById("t-list");
    const nearBottom = !list || list.scrollHeight - list.scrollTop - list.clientHeight < 100;
    const hh = (t) => new Date(t).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
    const body = S.thread.msgs.map((m) => `<div class="b ${m.from_admin ? "me" : "them"}">${esc(m.body)}<small>${hh(m.created_at)}${m.from_admin && m.read_at ? " ✓✓" : ""}</small></div>`).join("") ||
      '<p class="muted">Aucun message. Écris le premier 💗</p>';
    if (!prev) {
      el.innerHTML = `<div class="thread"><header><button data-a="tback">‹</button><h1>@${esc(S.thread.name)}</h1><button data-a="tdel">🗑 Supprimer</button></header>
        <div class="tl" id="t-list">${body}</div>
        <div class="bar"><textarea id="t-in" rows="1" placeholder="Écrire en tant que @admin…" maxlength="2000"></textarea><button class="p" data-a="tsend">➤</button></div></div>`;
      document.getElementById("t-list").scrollTop = 1e9;
      fitThread();
    } else {
      document.getElementById("t-list").innerHTML = body;
      if (scroll || nearBottom) document.getElementById("t-list").scrollTop = 1e9;
      document.getElementById("t-in").value = draft;
    }
  }

  function renderModal() {
    const el = document.getElementById("modal");
    if (!el) return;
    const m = S.modal;
    if (!m) { el.innerHTML = ""; return; }
    if (m.type === "ftfriends") {
      const me = S.ft && S.ft.users.find((x) => x.id === m.id);
      if (!me) { el.innerHTML = ""; return; }
      const fr = (me.friends || []).map((id) => S.ft.users.find((x) => x.id === id)).filter(Boolean);
      el.innerHTML = `<div class="fond"><div class="box"><h2 style="margin:0">Amis de @${esc(me.username)}</h2>
        ${fr.map((f) => `<div class="row card glass" style="margin:4px 0"><span class="ond ${f.state === "app" ? "on" : f.state === "net" ? "net" : ""}"></span><span class="grow"><b>${ICON[f.platform] || "🍎"} @${esc(f.username)}</b><div class="k">${f.state === "app" ? "dans Free tune" : f.state === "net" ? "sur internet" : esc(ago(f.last_seen))}</div></span>
          <button class="d" data-a="ftrm" data-id="${esc(f.id)}" style="padding:6px 12px">Retirer</button></div>`).join("") || '<p class="muted">Aucun ami.</p>'}
        <div class="row" style="justify-content:flex-end"><button class="s" data-a="mclose">Fermer</button></div></div></div>`;
      return;
    }
    const u = m.user;
    const name = u.username ? "@" + u.username : "ce compte";
    if (m.type === "update") {
      const x = UPD[m.key], n = S.targets ? S.targets[m.key] : null;
      el.innerHTML = m.done
        ? `<div class="fond"><div class="box"><h2 style="margin:0">${esc(x.name)} · ${esc(x.dev)}</h2><p class="v">${esc(m.done)}</p>
            <div class="row" style="justify-content:flex-end"><button class="p" data-a="mclose">Fermer</button></div></div></div>`
        : `<div class="fond"><div class="box"><h2 style="margin:0">Prévenir ${esc(x.name)} · ${esc(x.dev)}</h2>
            <p class="v">Cette notification va partir chez ${n == null ? "tous les utilisateurs" : "<b>" + n + " personne" + (n > 1 ? "s" : "") + "</b>"}. Tu peux modifier le texte.</p>
            <p class="k">Titre</p><input class="mi" id="u-t" maxlength="80" value="${esc(m.title)}">
            <p class="k">Message (marche à suivre)</p><textarea class="mi" id="u-b" rows="6" maxlength="600" style="width:100%;box-sizing:border-box">${esc(m.body)}</textarea>
            <div class="row" style="justify-content:flex-end"><button class="s" data-a="mclose">Annuler</button><button class="p" data-a="updsend">${S.busy ? "Envoi…" : "Envoyer"}</button></div></div></div>`;
      return;
    }
    if (m.type === "delete") {
      const word = u.username || "SUPPRIMER";
      el.innerHTML = `<div class="fond"><div class="box">
        <h2 style="margin:0">Supprimer ${esc(name)} ?</h2>
        <p class="v">Le compte, le pseudo, ses messages et ses demandes seront effacés. ${u.partner_name ? "Son partenaire @" + esc(u.partner_name) + " sera libéré (conversation effacée). " : ""}La personne pourra se réinscrire comme un nouvel utilisateur (même pseudo possible).</p>
        <p class="k">Pour confirmer, écris <b>${esc(word)}</b> :</p>
        <input class="mi" id="m-in" autocomplete="off" autocapitalize="none" placeholder="${esc(word)}">
        <div class="row" style="justify-content:flex-end"><button class="s" data-a="mclose">Annuler</button><button class="d" id="m-ok" data-a="mdel" disabled>${S.busy ? "Suppression…" : "Supprimer"}</button></div></div></div>`;
      const inp = document.getElementById("m-in");
      inp.addEventListener("input", () => { document.getElementById("m-ok").disabled = S.busy || inp.value.trim().toLowerCase() !== word.toLowerCase(); });
    } else if (m.type === "link") {
      el.innerHTML = `<div class="fond"><div class="box">
        <h2 style="margin:0">Relier ${esc(name)} à…</h2>
        <p class="v">${u.partner_name ? "Son partenaire actuel @" + esc(u.partner_name) + " sera libéré. " : ""}Si l'autre compte a déjà un partenaire, il sera libéré aussi. Personne n'est prévenu et les conversations sont conservées : elles reviennent si ces deux comptes sont reliés de nouveau. Les jeux en cours et les rappels communs sont arrêtés.</p>
        <p class="k">Pseudo du compte à relier :</p>
        <input class="mi" id="m-in" autocomplete="off" autocapitalize="none" placeholder="pseudo">
        <div class="row" style="justify-content:flex-end"><button class="s" data-a="mclose">Annuler</button><button class="p" data-a="mlink">${S.busy ? "…" : "Relier"}</button></div></div></div>`;
    } else {
      const rel = S.fr.current.concat(S.fr.history).filter((f) => f.a_id === u.id || f.b_id === u.id);
      el.innerHTML = `<div class="fond"><div class="box"><h2 style="margin:0">Amitiés de ${esc(name)}</h2>
        ${rel.map((f) => pairCard(f, !!f.ended_at)).join("") || '<p class="muted">Aucune amitié enregistrée.</p>'}
        <div class="row" style="justify-content:flex-end"><button class="s" data-a="mclose">Fermer</button></div></div></div>`;
    }
  }

  // ---------- Événements ----------
  root.addEventListener("input", (ev) => {
    if (ev.target.id === "q") { S.q = ev.target.value; S.focusQ = true; renderContent(); }
    if (ev.target.id === "fq") { S.fq = ev.target.value; renderContent(); }
  });
  root.addEventListener("keydown", (ev) => {
    if (ev.target.id === "t-in" && ev.key === "Enter" && !ev.shiftKey && window.matchMedia("(pointer: fine)").matches) { ev.preventDefault(); sendThread(); }
  });
  root.addEventListener("click", async (ev) => {
    const t = ev.target.closest("[data-a]");
    if (!t) return;
    const a = t.dataset.a, id = t.dataset.id;
    const user = () => S.users.find((u) => u.id === id);
    S.focusQ = false;
    switch (a) {
      case "login": login(); break;
      case "subsreset": {
        const id = t.dataset.id, nm = t.dataset.n || "ce compte";
        if (!await ask("Remettre @" + nm + " à zéro ? Son abonnement de test est retiré, ses téléchargements et ses jours reviennent à 0 (nouvel essai gratuit).")) break;
        try { await call("admin_test_reset", { p_user: id }); } catch (e) { await ask("Remise à zéro impossible : " + (e.message || e) + "\n(As-tu collé le dernier code SQL « suivi des abonnements » dans Supabase ?)", "OK"); }
        await loadSubs(true); break;
      }
      case "subsrefresh": if (S.subsView === "ref") await loadRef(); else await loadSubs(true); break;
      case "subsview": S.subsView = id; if (id === "ref") { S.ref = null; renderContent(); await loadRef(); } else renderContent(); break;
      case "refproof": {
        const inp = document.getElementById("proofin"); if (!inp) break;
        inp.value = "";
        inp.onchange = async () => {
          const f = inp.files && inp.files[0]; if (!f) return;
          try { S.proof[id] = await uploadProof(f); } catch (e) { await ask("Image non envoyée : " + (e.message || e), "OK"); }
          renderContent();
        };
        inp.click(); break;
      }
      case "refdone": {
        const nm = t.dataset.n || "ce compte";
        if (!await ask("Confirmer que le dépôt a été fait à @" + nm + " ?\nSon solde diminuera du montant versé et il verra « Dépôt effectué »" + (S.proof[id] ? " avec ta preuve de paiement." : " (sans image de preuve)."), "Dépôt effectué")) break;
        try { const r = await call("admin_payout_done", { p_id: Number(id), p_proof: S.proof[id] || null }); if (r && r.status !== "ok") await ask("Impossible : " + r.status, "OK"); else delete S.proof[id]; }
        catch (e) { await ask("Impossible : " + (e.message || e), "OK"); }
        await loadRef(); break;
      }
      case "ftback": if (window.NativeAdmin) window.NativeAdmin.back(); break;
      case "ftsel": if (ev.target.closest("[data-a=ftfr]")) break; if (S.sel[id]) delete S.sel[id]; else S.sel[id] = 1; renderContent(); break;
      case "ftexp": if (ev.target.closest("[data-a=ftfr]")) break; if (S.exp[id]) delete S.exp[id]; else S.exp[id] = 1; renderContent(); break;
      case "ftfr": S.modal = { type: "ftfriends", id }; renderModal(); break;
      case "ftclear": S.sel = {}; renderContent(); break;
      case "ftlink": { const A = ftIds("android"), I = ftIds("ios"); const pairs = A.length && I.length ? [I, A] : A.length > 1 ? [A, A] : I.length > 1 ? [I, I] : null; if (!pairs) break; const n = await ftDo("admin_ft_link", pairs[0], pairs[1]); if (n != null) { S.sel = {}; renderContent(); } break; }
      case "ftunlink": { const A = ftIds("android"), I = ftIds("ios"); const pairs = A.length && I.length ? [I, A] : A.length > 1 ? [A, A] : I.length > 1 ? [I, I] : null; if (!pairs || !await ask("Retirer ces amitiés ?\nLes conversations sont conservées.")) break; const n = await ftDo("admin_ft_unlink", pairs[0], pairs[1]); if (n != null) { S.sel = {}; renderContent(); } break; }
      case "ftrm": { const m = S.modal; if (!m || !await ask("Retirer cette amitié ?\nLa conversation est conservée.")) break; await ftDo("admin_ft_unlink", [m.id], [id]); break; }
      case "ftset": {
        try { const r = await call("admin_ft_setting", { p_key: t.dataset.k, p_val: t.dataset.v === "1" }); await loadFt(); if (r && r.linked) alert(r.linked + " amitié(s) créée(s)."); }
        catch (e) { alert("Impossible : " + (e.message || e)); }
        break;
      }
      case "logout": store.clear(); clearInterval(pollT); S.stage = "login"; S.thread = null; S.loaded = false; render(); break;
      case "refresh": if (S.ftMode) ftRefresh(); else loadAll(); break;
      case "ftrefresh": ftRefresh(); break;
      case "sort": S.sort = t.dataset.k; try { localStorage.setItem("tmm_sort", S.sort); } catch (e) {} renderContent(); break;
      case "tab": S.tab = t.dataset.t; renderContent(); break;
      case "chat": { const u = user(); const th = S.threads.find((x) => x.user_id === id); openThread(id, (u && u.username) || (th && th.username) || "?"); break; }
      case "tback": S.thread = null; renderThread(); loadAll(); break;
      case "tsend": sendThread(); break;
      case "tdel": {
        if (!S.thread || !await ask("Supprimer cette conversation ?\nElle disparaîtra aussi chez @" + S.thread.name + ".")) break;
        try { await call("admin_delete_thread", { p_user: S.thread.id }); S.thread = null; renderThread(); await loadAll(); }
        catch (e) { alert("Suppression impossible : " + (e.message || e)); }
        break;
      }
      case "hist": S.modal = { type: "history", user: user() }; renderModal(); break;
      case "del": S.modal = { type: "delete", user: user() }; renderModal(); document.getElementById("m-in").focus(); break;
      case "link": S.modal = { type: "link", user: user() }; renderModal(); document.getElementById("m-in").focus(); break;
      case "mlink": linkUser(); break;
      case "updopen": openUpdate(t.dataset.k); break;
      case "updsend": sendUpdate(); break;
      case "mclose": S.modal = null; S.busy = false; renderModal(); break;
      case "mdel": deleteUser(); break;
    }
  });

  window.addEventListener("error", (e) => { root.innerHTML = '<div class="center"><div style="font-size:48px">⚠️</div><p class="err">Erreur : ' + esc(e.message) + "</p></div>"; });
  window.addEventListener("unhandledrejection", (e) => { if (S.stage === "loading") { S.stage = "login"; S.err = "Erreur : " + ((e.reason && e.reason.message) || e.reason); render(); } });
  init().catch((e) => { S.stage = "login"; S.err = "Erreur : " + (e.message || e); render(); });
})();
