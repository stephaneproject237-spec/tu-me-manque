(() => {
  "use strict";

  const CFG = window.TMM_CONFIG;
  const root = document.getElementById("app");

  // ---------- Environnement ----------
  const ua = navigator.userAgent || "";
  const isIOS = /iphone|ipad|ipod/i.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const isStandalone =
    navigator.standalone === true ||
    (window.matchMedia && window.matchMedia("(display-mode: standalone)").matches);
  const pushSupported = "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;

  // ---------- État ----------
  const S = {
    loading: true,
    error: null,
    username: null,
    partnerName: null,
    miss: null, // { from, isReply }
    incoming: [],
    outgoing: [],
    busy: false,
    message: null,
    offerInvite: null,
    confirmRemove: false,
    draft: { username: "", partner: "" },
  };
  let lastSnapshotKey = "";

  // ---------- Connexion à Supabase ----------
  const store = {
    get() {
      try { return JSON.parse(localStorage.getItem("tmm_session") || "null"); } catch (e) { return null; }
    },
    set(v) { try { localStorage.setItem("tmm_session", JSON.stringify(v)); } catch (e) { /* ignoré */ } },
    clear() { try { localStorage.removeItem("tmm_session"); } catch (e) { /* ignoré */ } },
  };

  async function http(method, path, body, token) {
    const headers = { apikey: CFG.SUPABASE_ANON_KEY, "Content-Type": "application/json", Accept: "application/json" };
    if (token) headers.Authorization = "Bearer " + token;
    const res = await fetch(CFG.SUPABASE_URL + path, {
      method,
      headers,
      body: body === undefined || body === null ? undefined : JSON.stringify(body),
    });
    return { code: res.status, text: await res.text() };
  }

  function saveSession(j) {
    const now = Math.floor(Date.now() / 1000);
    store.set({
      access: j.access_token,
      refresh: j.refresh_token,
      uid: j.user.id,
      expires: j.expires_at || now + (j.expires_in || 3600),
    });
  }

  let pendingSession = null;
  function session(force) {
    if (pendingSession) return pendingSession;
    pendingSession = (async () => {
      try {
        const now = Math.floor(Date.now() / 1000);
        const s = store.get();
        if (!force && s && s.access && s.expires > now + 60) return s;
        if (s && s.refresh) {
          const r = await http("POST", "/auth/v1/token?grant_type=refresh_token", { refresh_token: s.refresh });
          if (r.code >= 200 && r.code < 300) { saveSession(JSON.parse(r.text)); return store.get(); }
          if (r.code < 400 || r.code > 403) throw new Error("Renouvellement impossible (" + r.code + ")");
          store.clear(); // session perdue : nouvelle identité
        }
        const r = await http("POST", "/auth/v1/signup", {});
        if (r.code < 200 || r.code >= 300) throw new Error("Connexion impossible (" + r.code + ") " + r.text);
        saveSession(JSON.parse(r.text));
        return store.get();
      } finally {
        pendingSession = null;
      }
    })();
    return pendingSession;
  }

  async function authed(method, path, body) {
    let s = await session(false);
    let r = await http(method, path, body, s.access);
    if (r.code === 401) {
      s = await session(true);
      r = await http(method, path, body, s.access);
    }
    if (r.code < 200 || r.code >= 300) throw new Error("HTTP " + r.code + " : " + r.text);
    return r.text ? JSON.parse(r.text) : null;
  }

  async function rpc(name, args) {
    const j = await authed("POST", "/rest/v1/rpc/" + name, args || {});
    return (j && j.status) || "error";
  }

  async function loadSnapshot() {
    const s = await session(false);
    const uid = s.uid;
    const profiles = await authed(
      "GET",
      "/rest/v1/profiles?select=username,partner_name,last_miss_from,last_miss_reply&id=eq." + uid
    );
    const requests = await authed(
      "GET",
      "/rest/v1/requests?select=id,from_id,from_name,to_name&or=(from_id.eq." + uid + ",to_id.eq." + uid + ")"
    );
    const p = (profiles && profiles[0]) || null;
    const incoming = [];
    const outgoing = [];
    (requests || []).forEach((o) => {
      const req = { id: String(o.id), fromName: o.from_name || "", toName: o.to_name || "" };
      if (o.from_id === uid) outgoing.push(req); else incoming.push(req);
    });
    return {
      username: p ? p.username || null : null,
      partnerName: p ? p.partner_name || null : null,
      miss: p && p.last_miss_from ? { from: p.last_miss_from, isReply: !!p.last_miss_reply } : null,
      incoming,
      outgoing,
    };
  }

  // ---------- Notifications ----------
  function urlB64ToUint8Array(b64) {
    const pad = "=".repeat((4 - (b64.length % 4)) % 4);
    const raw = atob((b64 + pad).replace(/-/g, "+").replace(/_/g, "/"));
    return Uint8Array.from(raw, (c) => c.charCodeAt(0));
  }

  let swRegistration = null;
  async function registerWorker() {
    if (!("serviceWorker" in navigator)) return null;
    try {
      swRegistration = await navigator.serviceWorker.register("sw.js");
    } catch (e) { swRegistration = null; }
    return swRegistration;
  }

  async function subscribeAndSave() {
    const reg = (await registerWorker()) && (await navigator.serviceWorker.ready);
    if (!reg) throw new Error("Service worker indisponible");
    let sub = await reg.pushManager.getSubscription();
    if (!sub) {
      sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlB64ToUint8Array(CFG.VAPID_PUBLIC_KEY),
      });
    }
    await rpc("save_web_push", { p_sub: sub.toJSON() });
  }

  async function enableNotifications() {
    if (!pushSupported) {
      S.message = "Ce navigateur ne gère pas les notifications.";
      return render();
    }
    const permission = await Notification.requestPermission();
    if (permission !== "granted") {
      S.message = "Notifications refusées. Tu peux les autoriser dans les réglages de ton téléphone.";
      return render();
    }
    try {
      await subscribeAndSave();
      S.message = "Notifications activées 💗";
    } catch (e) {
      S.message = "Impossible d'activer les notifications pour le moment.";
    }
    render();
  }

  // ---------- Actions ----------
  const clean = (v) => String(v || "").trim().replace(/^@/, "").toLowerCase();
  const filterName = (v) => String(v || "").toLowerCase().replace(/[^a-z0-9_]/g, "").slice(0, 20);
  const esc = (v) => String(v).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  function applySnapshot(snap) {
    const key = JSON.stringify(snap);
    const changed = key !== lastSnapshotKey || S.loading || S.error;
    lastSnapshotKey = key;
    Object.assign(S, snap, { loading: false, error: null });
    return changed;
  }

  async function act(fn) {
    S.busy = true;
    S.message = null;
    render();
    try {
      await fn();
      applySnapshot(await loadSnapshot());
    } catch (e) {
      S.message = "Une erreur est survenue. Vérifie ta connexion et réessaie.";
    }
    S.busy = false;
    render();
  }

  const say = (t) => { S.message = t; };

  const actions = {
    retry: () => start(),
    claim: () => act(async () => {
      const st = await rpc("claim_username", { p_username: clean(S.draft.username) });
      if (st === "taken") say("Cet identifiant est déjà pris. Essaie-en un autre.");
      else if (st === "invalid") say("3 à 20 caractères : lettres minuscules, chiffres ou _.");
      else if (st !== "ok") say("Impossible de valider cet identifiant.");
      else {
        // Dès que l'identifiant est pris, on demande l'autorisation des notifications si possible.
        S.draft.username = "";
      }
    }),
    addPartner: () => act(async () => {
      const name = clean(S.draft.partner);
      const st = await rpc("send_request", { p_username: name });
      if (st === "sent") { say("Demande envoyée à @" + name + ". Il/elle va recevoir une notification."); S.draft.partner = ""; }
      else if (st === "linked") S.draft.partner = "";
      else if (st === "not_found") S.offerInvite = name;
      else if (st === "self") say("C'est ton propre identifiant !");
      else if (st === "already_has_partner") say("Tu as déjà un partenaire.");
      else if (st === "partner_busy") say("@" + name + " a déjà un partenaire.");
      else if (st === "already_sent") say("Tu as déjà envoyé une demande à @" + name + ".");
      else if (st === "invalid") say("Identifiant invalide.");
      else say("Impossible d'envoyer la demande.");
    }),
    accept: (id) => act(async () => {
      const st = await rpc("respond_request", { p_request_id: Number(id), p_accept: true });
      if (st === "partner_busy") say("Cette personne a déjà un partenaire.");
      else if (st !== "ok") say("Cette demande n'existe plus.");
    }),
    refuse: (id) => act(async () => {
      const st = await rpc("respond_request", { p_request_id: Number(id), p_accept: false });
      if (st !== "ok") say("Cette demande n'existe plus.");
    }),
    miss: () => act(async () => {
      const st = await rpc("send_miss", { p_reply: false });
      say(st === "ok" ? "Envoyé 💗" : "Impossible d'envoyer pour le moment.");
    }),
    reply: () => act(async () => {
      const st = await rpc("send_miss", { p_reply: true });
      say(st === "ok" ? "Envoyé : Tu me manques aussi 💗" : "Impossible d'envoyer pour le moment.");
    }),
    askRemove: () => { S.confirmRemove = true; render(); },
    cancelRemove: () => { S.confirmRemove = false; render(); },
    remove: () => {
      S.confirmRemove = false;
      return act(async () => {
        const st = await rpc("remove_partner");
        say(st === "ok" ? "Partenaire retiré. Tu peux en ajouter un autre." : "Impossible de retirer le partenaire pour le moment.");
      });
    },
    closeInvite: () => { S.offerInvite = null; render(); },
    invite: () => { S.offerInvite = null; shareApp(); render(); },
    shareOnly: () => shareApp(),
    enablePush: () => enableNotifications(),
  };

  async function shareApp() {
    const text =
      "Je t'invite sur « Tu me manques » 💗\n" +
      "Android : " + CFG.APK_URL + "\n" +
      "iPhone : " + CFG.WEB_URL + " (ouvre-le dans Safari puis « Sur l'écran d'accueil »)" +
      (S.username ? "\nMon identifiant : @" + S.username : "");
    try {
      if (navigator.share) { await navigator.share({ text }); return; }
      await navigator.clipboard.writeText(text);
      S.message = "Message d'invitation copié. Colle-le dans WhatsApp.";
    } catch (e) { /* partage annulé */ }
    render();
  }

  // ---------- Écrans ----------
  function pushBlock() {
    if (!pushSupported) return "";
    if (Notification.permission === "granted") return "";
    if (Notification.permission === "denied") {
      return '<p class="petit">Notifications bloquées : autorise-les dans les réglages du téléphone.</p>';
    }
    return '<button class="secondaire" data-action="enablePush">🔔 Activer les notifications</button>';
  }

  function messageBlock() {
    return S.message ? '<p class="msg">' + esc(S.message) + "</p>" : "";
  }

  function installScreen() {
    return `<div class="col">
      <div class="coeur">💗</div>
      <h1>Tu me manques</h1>
      <p>Pour utiliser l'application sur iPhone, ajoute-la d'abord à ton écran d'accueil :</p>
      <ol class="etapes">
        <li>Ouvre cette page dans <b>Safari</b>.</li>
        <li>Touche l'icône <b>Partager</b> (le carré avec une flèche vers le haut).</li>
        <li>Choisis <b>« Sur l'écran d'accueil »</b>, puis <b>Ajouter</b>.</li>
        <li>Ferme Safari et ouvre <b>« Tu me manques »</b> depuis ton écran d'accueil.</li>
      </ol>
      <p class="gris">C'est indispensable pour recevoir les notifications.</p>
    </div>`;
  }

  function usernameScreen() {
    return `<div class="col">
      <div class="coeur">💗</div>
      <h1>Bienvenue</h1>
      <p>Choisis ton identifiant. Ton partenaire s'en servira pour te retrouver.</p>
      <label for="f-username">Ton identifiant</label>
      <div class="champ"><span>@</span><input id="f-username" data-field="username" value="${esc(S.draft.username)}"
        autocapitalize="none" autocomplete="off" autocorrect="off" spellcheck="false" maxlength="20"></div>
      <button class="principal" data-action="claim" ${S.draft.username.length >= 3 && !S.busy ? "" : "disabled"}>Valider</button>
      ${messageBlock()}
    </div>`;
  }

  function partnerScreen() {
    const incoming = S.incoming.map((r) => `<div class="carte">
        <p><b>@${esc(r.fromName)}</b> veut être ton partenaire 💗</p>
        <div class="rangee">
          <button class="principal" data-action="accept" data-id="${esc(r.id)}" ${S.busy ? "disabled" : ""}>Accepter</button>
          <button class="secondaire" data-action="refuse" data-id="${esc(r.id)}" ${S.busy ? "disabled" : ""}>Refuser</button>
        </div></div>`).join("");
    const outgoing = S.outgoing.map((r) => `<p class="gris">En attente de @${esc(r.toName)}…</p>`).join("");
    return `<div class="col">
      <h2>Salut @${esc(S.username)} 👋</h2>
      <p>Ajoute ton partenaire avec son identifiant.</p>
      ${incoming}
      <label for="f-partner">Identifiant de ton partenaire</label>
      <div class="champ"><span>@</span><input id="f-partner" data-field="partner" value="${esc(S.draft.partner)}"
        autocapitalize="none" autocomplete="off" autocorrect="off" spellcheck="false" maxlength="20"></div>
      <button class="principal" data-action="addPartner" ${S.draft.partner.length >= 3 && !S.busy ? "" : "disabled"}>Envoyer la demande</button>
      ${outgoing}
      ${messageBlock()}
      ${pushBlock()}
      <button class="lien" data-action="shareOnly">Inviter quelqu'un à installer l'appli</button>
    </div>`;
  }

  function homeScreen() {
    let miss = "";
    if (S.miss) {
      miss = `<div class="carte">
        <p class="gris">@${esc(S.miss.from)}</p>
        <p style="font-size:20px;font-weight:600">${S.miss.isReply ? "Tu me manques aussi 💗" : "Tu me manques 💗"}</p>
        ${S.miss.isReply ? "" : `<button class="secondaire" data-action="reply" ${S.busy ? "disabled" : ""}>Tu me manques aussi</button>`}
      </div>`;
    }
    return `<div class="col">
      <div><span class="gris" style="font-size:18px">@${esc(S.username)} 💗 </span><span class="pseudo" data-action="askRemove">@${esc(S.partnerName)}</span></div>
      <p class="petit" style="margin-top:-8px">Touche son pseudo pour retirer ton partenaire</p>
      <button class="principal gros" data-action="miss" ${S.busy ? "disabled" : ""}>Tu me manques 💗</button>
      ${messageBlock()}
      ${miss}
      ${pushBlock()}
    </div>`;
  }

  function dialogs() {
    let html = "";
    if (S.offerInvite) {
      html += `<div class="fond"><div class="boite">
        <h2 style="font-size:20px">Identifiant introuvable</h2>
        <p>@${esc(S.offerInvite)} n'existe pas encore. Cette personne n'a peut-être pas l'application. Veux-tu la lui envoyer ?</p>
        <div class="rangee"><button class="lien" data-action="closeInvite">Annuler</button>
        <button class="lien" style="color:var(--rose)" data-action="invite">Envoyer l'application</button></div></div></div>`;
    }
    if (S.confirmRemove) {
      html += `<div class="fond"><div class="boite">
        <h2 style="font-size:20px">Retirer @${esc(S.partnerName)} ?</h2>
        <p>Vous ne serez plus liés. Vous pourrez chacun ajouter quelqu'un d'autre.</p>
        <div class="rangee"><button class="lien" data-action="cancelRemove">Annuler</button>
        <button class="lien" style="color:var(--rose)" data-action="remove">Retirer</button></div></div></div>`;
    }
    return html;
  }

  function render() {
    const active = document.activeElement;
    const activeId = active && active.id ? active.id : null;
    const selStart = active && typeof active.selectionStart === "number" ? active.selectionStart : null;

    let html;
    if (isIOS && !isStandalone) html = installScreen();
    else if (S.loading) html = '<div class="spin"></div>';
    else if (S.error) html = `<div class="col"><p>${esc(S.error)}</p><button class="principal" data-action="retry">Réessayer</button></div>`;
    else if (!S.username) html = usernameScreen();
    else if (!S.partnerName) html = partnerScreen();
    else html = homeScreen();

    root.innerHTML = html + dialogs() + `<p class="petit">Version ${esc(CFG.VERSION)}</p>`;

    if (activeId) {
      const el = document.getElementById(activeId);
      if (el) {
        el.focus();
        if (selStart !== null && el.setSelectionRange) { try { el.setSelectionRange(selStart, selStart); } catch (e) { /* ignoré */ } }
      }
    }
  }

  // ---------- Événements ----------
  root.addEventListener("click", (ev) => {
    const el = ev.target.closest("[data-action]");
    if (!el || el.disabled) return;
    const fn = actions[el.dataset.action];
    if (fn) fn(el.dataset.id);
  });

  root.addEventListener("input", (ev) => {
    const el = ev.target;
    if (!el.dataset || !el.dataset.field) return;
    const value = filterName(el.value);
    if (el.value !== value) el.value = value;
    S.draft[el.dataset.field] = value;
    // On met seulement à jour l'état du bouton, sans tout redessiner pendant la saisie.
    const btn = root.querySelector('[data-action="' + (el.dataset.field === "username" ? "claim" : "addPartner") + '"]');
    if (btn) btn.disabled = value.length < 3 || S.busy;
  });

  // ---------- Démarrage ----------
  async function start() {
    S.loading = true;
    S.error = null;
    render();
    try {
      await session(false);
      applySnapshot(await loadSnapshot());
      // Si les notifications sont déjà autorisées, on s'assure que l'abonnement est enregistré.
      if (pushSupported && Notification.permission === "granted") {
        subscribeAndSave().catch(() => {});
      } else {
        registerWorker();
      }
    } catch (e) {
      S.loading = false;
      S.error = "Connexion impossible. Vérifie ta connexion internet puis réessaie.";
    }
    render();
  }

  async function refresh() {
    if (S.loading || S.error || S.busy || document.visibilityState !== "visible") return;
    try {
      if (applySnapshot(await loadSnapshot())) render();
    } catch (e) { /* pas de réseau : on garde l'écran */ }
  }

  if (isIOS && !isStandalone) {
    render(); // Sur iPhone, on n'ouvre aucune session tant que l'appli n'est pas installée.
  } else {
    start();
    setInterval(refresh, 4000);
    document.addEventListener("visibilitychange", refresh);
  }
})();
