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
  const phone = (p) => (p ? "+" + p : "—");

  const S = {
    stage: "loading",      // loading | login | denied | app
    email: "",
    tab: "users",
    users: [], fr: { current: [], history: [] }, threads: [],
    q: "", loaded: false, err: null,
    thread: null,          // { id, name, msgs }
    modal: null,           // { type: "delete" | "history", user }
    busy: false,
  };

  const call = (name, args) => authed("POST", "/rest/v1/rpc/" + name, args || {});

  // ---------- Connexion ----------
  async function init() {
    const q = new URLSearchParams(location.search);
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
      if (r && r.admin) { S.stage = "app"; S.err = null; render(); await loadAll(); startPolling(); }
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
      if (S.thread) await openThread(S.thread.id, S.thread.name, true);
      else await loadAll();
    }, 5000);
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

  // ---------- Affichage ----------
  function render() {
    if (S.stage === "loading") { root.innerHTML = '<div class="center"><div class="spin"></div></div>'; return; }
    if (S.stage === "login") {
      root.innerHTML = `<div class="center"><div style="font-size:56px">🛠️💗</div><h1 style="margin:0">Tu me manques – Admin</h1>
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
    root.innerHTML = `<header><h1>🛠️ Admin</h1><button data-a="refresh" aria-label="Actualiser">↻</button><button data-a="logout">Quitter</button></header>
      <main id="content"></main>
      <nav><button data-a="tab" data-t="users" id="n-users"></button><button data-a="tab" data-t="friends" id="n-friends"></button><button data-a="tab" data-t="msgs" id="n-msgs"></button></nav>
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
  }

  function renderContent() {
    if (S.stage !== "app") return;
    renderNav();
    const c = document.getElementById("content");
    if (!c) return;
    if (!S.loaded) { c.innerHTML = S.err ? '<p class="muted err">' + esc(S.err) + "</p>" : '<div class="center" style="min-height:50vh"><div class="spin"></div></div>'; return; }
    const err = S.err ? '<p class="err muted">' + esc(S.err) + "</p>" : "";
    if (S.tab === "users") c.innerHTML = err + usersHtml();
    else if (S.tab === "friends") c.innerHTML = err + friendsHtml();
    else c.innerHTML = err + threadsHtml();
    const q = document.getElementById("q");
    if (q && S.focusQ) { q.focus(); q.setSelectionRange(q.value.length, q.value.length); }
  }

  function filteredUsers() {
    const q = S.q.trim().toLowerCase().replace(/^@/, "");
    if (!q) return S.users;
    return S.users.filter((u) => [u.username, u.email, u.phone, u.device, u.partner_name].some((x) => x && String(x).toLowerCase().includes(q)));
  }

  function usersHtml() {
    const list = filteredUsers();
    return `<input class="q" id="q" placeholder="Rechercher (pseudo, e-mail, numéro, appareil)…" value="${esc(S.q)}" autocomplete="off">
      <p class="k" style="margin:8px 4px">${list.length} compte${list.length > 1 ? "s" : ""}</p>` +
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
        <span class="k">Âge</span><span class="v">${u.age != null ? esc(u.age) + " ans" : "—"}</span>
        <span class="k">Appareil</span><span class="v">${esc(u.device || "inconnu (compte ancien)")}</span>
        <span class="k">Créé le</span><span class="v">${esc(fdate(u.created_at))}</span>
        <span class="k">Partenaire</span><span class="v">${u.partner_name ? "💗 @" + esc(u.partner_name) + (u.linked_at ? " · depuis " + esc(fdate(u.linked_at)) : "") : "aucun"}</span>
      </div>
      <div class="row">
        <button class="p" data-a="chat" data-id="${esc(u.id)}">💬 Chat</button>
        <button class="s" data-a="hist" data-id="${esc(u.id)}">📜 Amitiés</button>
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
    const u = m.user;
    const name = u.username ? "@" + u.username : "ce compte";
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
      case "logout": store.clear(); clearInterval(pollT); S.stage = "login"; S.thread = null; S.loaded = false; render(); break;
      case "refresh": loadAll(); break;
      case "tab": S.tab = t.dataset.t; renderContent(); break;
      case "chat": { const u = user(); const th = S.threads.find((x) => x.user_id === id); openThread(id, (u && u.username) || (th && th.username) || "?"); break; }
      case "tback": S.thread = null; renderThread(); loadAll(); break;
      case "tsend": sendThread(); break;
      case "tdel": {
        if (!S.thread || !confirm("Supprimer cette conversation ?\nElle disparaîtra aussi chez @" + S.thread.name + ".")) break;
        try { await call("admin_delete_thread", { p_user: S.thread.id }); S.thread = null; renderThread(); await loadAll(); }
        catch (e) { alert("Suppression impossible : " + (e.message || e)); }
        break;
      }
      case "hist": S.modal = { type: "history", user: user() }; renderModal(); break;
      case "del": S.modal = { type: "delete", user: user() }; renderModal(); document.getElementById("m-in").focus(); break;
      case "mclose": S.modal = null; renderModal(); break;
      case "mdel": deleteUser(); break;
    }
  });

  window.addEventListener("error", (e) => { root.innerHTML = '<div class="center"><div style="font-size:48px">⚠️</div><p class="err">Erreur : ' + esc(e.message) + "</p></div>"; });
  window.addEventListener("unhandledrejection", (e) => { if (S.stage === "loading") { S.stage = "login"; S.err = "Erreur : " + ((e.reason && e.reason.message) || e.reason); render(); } });
  init().catch((e) => { S.stage = "login"; S.err = "Erreur : " + (e.message || e); render(); });
})();
