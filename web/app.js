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
    miss: null, // { from, isReply, at }
    unread: 0,
    incoming: [],
    outgoing: [],
    busy: false,
    message: null,
    offerInvite: null,
    confirmRemove: false,
    draft: { partner: "" },
    needAuth: false,
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
          store.clear(); // session perdue : il faudra se reconnecter
        }
        throw new Error("NO_SESSION");
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
      "/rest/v1/profiles?select=username,partner_name,last_miss_from,last_miss_reply,last_miss_at&id=eq." + uid
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
    let unread = 0;
    if (p && p.partner_name) {
      try {
        const rows = await authed("GET", "/rest/v1/messages?select=id&recipient_id=eq." + uid + "&read_at=is.null&limit=100");
        unread = (rows || []).length;
      } catch (e) { /* le chat n'est peut-être pas encore installé côté serveur */ }
    }
    return {
      username: p ? p.username || null : null,
      partnerName: p ? p.partner_name || null : null,
      miss: p && p.last_miss_from ? { from: p.last_miss_from, isReply: !!p.last_miss_reply, at: p.last_miss_at || "" } : null,
      unread,
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

  // ---------- Comptes (Google / Apple / SMS) ----------
  const A0 = () => ({ screen: "welcome", mode: "create", pseudo: "", age: "", avail: null, phone: "+237", code: "", busy: false, msg: null });
  S.auth = A0();
  const PENDING = "tmm_pending";
  const PKCE = "tmm_pkce";
  const lsGet = (k) => { try { return JSON.parse(localStorage.getItem(k) || "null"); } catch (e) { return null; } };
  const lsSet = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* ignoré */ } };
  const lsDel = (k) => { try { localStorage.removeItem(k); } catch (e) { /* ignoré */ } };
  const ageOk = (v) => { const n = Number(v); return /^\d{1,3}$/.test(String(v)) && n >= 1 && n <= 120; };
  const pseudoOk = (v) => /^[a-z][a-z0-9_]{2,19}$/.test(v);
  const phoneDigits = (v) => String(v || "").replace(/\D/g, "");
  const e164 = (v) => {
    let d = phoneDigits(v);
    if (/^\s*00/.test(String(v))) d = d.slice(2);
    if (d.length === 9 && d[0] === "6") d = "237" + d;
    return "+" + d;
  };

  const AVAIL_TXT = {
    checking: "Vérification…",
    available: "✓ Ce pseudo est disponible",
    taken: "✗ Ce pseudo est déjà pris",
    invalid: "3 à 20 caractères, commence par une lettre (a-z, 0-9 ou _)",
    error: "Impossible de vérifier pour le moment",
  };
  const canContinue = () => S.auth.avail === "available" && ageOk(S.auth.age) && !S.auth.busy;

  // Met à jour les textes et boutons sans redessiner l'écran (pour ne pas perdre le clavier).
  function paintAuth() {
    const a = document.getElementById("avail");
    if (a) { a.textContent = AVAIL_TXT[S.auth.avail] || ""; a.className = "avail " + (S.auth.avail || ""); }
    root.querySelectorAll("[data-needok]").forEach((b) => { b.disabled = !canContinue(); });
    const sc = root.querySelector('[data-action="sendCode"]');
    if (sc) sc.disabled = S.auth.busy || phoneDigits(S.auth.phone).length < 8;
    const vc = root.querySelector('[data-action="verifyCode"]');
    if (vc) vc.disabled = S.auth.busy || S.auth.code.length < 6;
  }

  let availTimer = null;
  let availSeq = 0;
  function checkPseudo() {
    clearTimeout(availTimer);
    const p = S.auth.pseudo;
    const seq = ++availSeq;
    if (!pseudoOk(p)) { S.auth.avail = p ? "invalid" : null; paintAuth(); return; }
    S.auth.avail = "checking";
    paintAuth();
    availTimer = setTimeout(async () => {
      let st = "error";
      try {
        const r = await http("POST", "/rest/v1/rpc/username_available", { p_username: p });
        if (r.code === 200) st = JSON.parse(r.text).status || "error";
      } catch (e) { /* hors-ligne */ }
      if (seq !== availSeq) return;
      S.auth.avail = st;
      paintAuth();
    }, 400);
  }

  function smsError(r) {
    const t = String((r && r.text) || "").toLowerCase();
    if (r && (r.code === 429 || /rate limit|too many|only request this after/.test(t))) return "Trop d'essais. Attends une minute puis réessaie.";
    if (/provider|unsupported|sms_send_failed|not enabled|disabled|twilio/.test(t)) return "La connexion par SMS n'est pas encore activée. Utilise Google pour le moment.";
    return "Impossible d'envoyer le SMS pour le moment.";
  }

  async function sendCode() {
    S.auth.busy = true; S.auth.msg = null; render();
    try {
      const r = await http("POST", "/auth/v1/otp", { phone: e164(S.auth.phone), create_user: true });
      if (r.code >= 200 && r.code < 300) S.auth.screen = "code"; else S.auth.msg = smsError(r);
    } catch (e) { S.auth.msg = "Connexion impossible. Vérifie ta connexion internet."; }
    S.auth.busy = false; render();
  }

  async function verifyCode() {
    S.auth.busy = true; S.auth.msg = null; render();
    try {
      const r = await http("POST", "/auth/v1/verify", { type: "sms", phone: e164(S.auth.phone), token: S.auth.code });
      if (r.code >= 200 && r.code < 300) {
        saveSession(JSON.parse(r.text));
        await finishAuth();
      } else { S.auth.msg = "Code incorrect ou expiré."; }
    } catch (e) { S.auth.msg = "Connexion impossible. Vérifie ta connexion internet."; }
    S.auth.busy = false; render();
  }

  const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

  async function startOAuth(provider) {
    const verifier = b64url(crypto.getRandomValues(new Uint8Array(48)));
    const challenge = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
    lsSet(PKCE, verifier);
    if (S.auth.mode === "create") lsSet(PENDING, { pseudo: S.auth.pseudo, age: Number(S.auth.age) }); else lsDel(PENDING);
    location.href = CFG.SUPABASE_URL + "/auth/v1/authorize?provider=" + provider +
      "&redirect_to=" + encodeURIComponent(CFG.WEB_URL) +
      "&code_challenge=" + challenge + "&code_challenge_method=s256";
  }

  // Retour de Google / Apple : l'adresse contient « ?code=… ». Renvoie true si on a traité un retour.
  async function handleOAuthReturn() {
    const q = new URLSearchParams(location.search);
    const code = q.get("code");
    const err = q.get("error_description");
    if (!code && !err) return false;
    try { history.replaceState(null, "", location.pathname); } catch (e) { /* ignoré */ }
    if (err) { S.auth.msg = "Connexion refusée : " + err; return false; }
    const verifier = lsGet(PKCE);
    lsDel(PKCE);
    if (!verifier) return false;
    const r = await http("POST", "/auth/v1/token?grant_type=pkce", { auth_code: code, code_verifier: verifier });
    if (r.code >= 200 && r.code < 300) { saveSession(JSON.parse(r.text)); return true; }
    S.auth.msg = "Connexion impossible. Réessaie.";
    return false;
  }

  // Après une connexion réussie : on retrouve le profil, ou on le crée (pseudo + âge).
  async function finishAuth() {
    S.needAuth = false;
    const snap = await loadSnapshot();
    if (!snap.username) {
      const pend = lsGet(PENDING) || (S.auth.pseudo && ageOk(S.auth.age) ? { pseudo: S.auth.pseudo, age: Number(S.auth.age) } : null);
      lsDel(PENDING);
      let note = null;
      if (pend) {
        const j = await authed("POST", "/rest/v1/rpc/create_account", { p_username: pend.pseudo, p_age: pend.age });
        if (j && j.status === "ok") { applySnapshot(await loadSnapshot()); afterLogin(); return; }
        note = j && j.status === "taken" ? "Ce pseudo vient d'être pris. Choisis-en un autre." : "Impossible de créer le compte.";
      }
      S.auth = Object.assign(A0(), { mode: "create", msg: note, age: pend ? String(pend.age) : "", pseudo: note && note.startsWith("Ce pseudo") ? "" : (pend ? pend.pseudo : "") });
      if (S.auth.pseudo) checkPseudo();
    } else {
      lsDel(PENDING);
    }
    applySnapshot(snap);
    afterLogin();
  }

  function afterLogin() {
    if (pushSupported && Notification.permission === "granted") subscribeAndSave().catch(() => {});
    else registerWorker();
  }

  // ---------- Actions ----------
  const clean = (v) => String(v || "").trim().replace(/^@/, "").toLowerCase();
  const filterName = (v) => String(v || "").toLowerCase().replace(/[^a-z0-9_]/g, "").slice(0, 20);
  const filterTarget = (v) => String(v || "").toLowerCase().replace(/[^a-z0-9_+ ()-]/g, "").slice(0, 24);
  const esc = (v) => String(v).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  function applySnapshot(snap) {
    const key = JSON.stringify(snap);
    const changed = key !== lastSnapshotKey || S.loading || S.error;
    lastSnapshotKey = key;
    Object.assign(S, snap, { loading: false, error: null });
    afterSnapshot();
    return changed;
  }

  // ---------- Chat ----------
  const chat = () => window.TMM_CHAT;
  let autoOpenedFor = "";

  function afterSnapshot() {
    const c = chat();
    if (!c) return;
    if (!S.partnerName) { if (c.isOpen()) c.close(); return; }
    // Après « Tu me manques » → « Tu me manques aussi », le chat s'ouvre tout seul (une seule fois).
    if (S.miss && S.miss.isReply && !c.isOpen() && autoOpenedFor !== S.miss.at) {
      autoOpenedFor = S.miss.at;
      openChat(true);
    }
  }

  function openChat(clearMiss) {
    const c = chat();
    if (!c || !S.partnerName) return;
    c.open(S.partnerName);
    if (clearMiss) {
      S.miss = null;
      rpc("clear_miss").catch(() => {});
      render();
    }
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
    if (S.openAfter) { S.openAfter = false; openChat(false); }
  }

  const say = (t) => { S.message = t; };

  const actions = {
    retry: () => start(),
    goCreate: () => { S.auth = Object.assign(A0(), { screen: "create", mode: "create" }); render(); },
    goLogin: () => { S.auth = Object.assign(A0(), { screen: "login", mode: "login" }); render(); },
    goWelcome: () => { S.auth = A0(); render(); },
    phoneStart: () => { S.auth.screen = "phone"; S.auth.msg = null; render(); },
    phoneBack: () => { S.auth.screen = S.auth.mode === "create" ? "create" : "login"; S.auth.msg = null; render(); },
    sendCode: () => sendCode(),
    verifyCode: () => verifyCode(),
    oauthGoogle: () => startOAuth("google"),
    oauthApple: () => startOAuth("apple"),
    createProfile: () => act(async () => {
      const j = await authed("POST", "/rest/v1/rpc/create_account", { p_username: S.auth.pseudo, p_age: Number(S.auth.age) });
      const st = (j && j.status) || "error";
      if (st === "taken") { S.auth.avail = "taken"; say("Ce pseudo est déjà pris. Choisis-en un autre."); }
      else if (st === "invalid" || st === "invalid_age") say("Vérifie ton pseudo et ton âge.");
      else if (st === "anonymous") { store.clear(); S.needAuth = true; S.auth = A0(); }
      else if (st !== "ok" && st !== "has_username") say("Impossible de créer le compte.");
    }),
    addPartner: () => act(async () => {
      const name = clean(S.draft.partner);
      const j = await authed("POST", "/rest/v1/rpc/send_request", { p_username: name });
      const st = (j && j.status) || "error";
      if (st === "sent") { say("Demande envoyée à @" + (j.name || name) + ". Il/elle va recevoir une notification."); S.draft.partner = ""; }
      else if (st === "linked") S.draft.partner = "";
      else if (st === "not_found") S.offerInvite = name;
      else if (st === "self") say("C'est toi !");
      else if (st === "already_has_partner") say("Tu as déjà un partenaire.");
      else if (st === "partner_busy") say("@" + name + " a déjà un partenaire.");
      else if (st === "already_sent") say("Tu as déjà envoyé une demande à @" + name + ".");
      else if (st === "invalid") say("Pseudo ou numéro invalide.");
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
      if (st === "ok") { say("Envoyé : Tu me manques aussi 💗"); S.miss = null; S.openAfter = true; }
      else say("Impossible d'envoyer pour le moment.");
    }),
    openChat: () => openChat(false),
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

  const methodButtons = (needOk) => {
    const flag = needOk ? "data-needok" : "";
    const dis = needOk && !canContinue() ? "disabled" : "";
    return `<button class="principal" data-action="oauthGoogle" ${flag} ${dis}>Continuer avec Google</button>` +
      (CFG.APPLE_LOGIN ? `<button class="sombre" data-action="oauthApple" ${flag} ${dis}>Continuer avec Apple</button>` : "") +
      `<button class="secondaire" data-action="phoneStart" ${flag} ${dis}>Recevoir un code par SMS</button>`;
  };

  const pseudoAgeFields = () => `
      <label for="f-pseudo">Ton pseudo</label>
      <div class="champ"><input id="f-pseudo" data-afield="pseudo" value="${esc(S.auth.pseudo)}"
        autocapitalize="none" autocomplete="off" autocorrect="off" spellcheck="false" maxlength="20" placeholder="par exemple stephane"></div>
      <p id="avail" class="avail ${esc(S.auth.avail || "")}">${esc(AVAIL_TXT[S.auth.avail] || "")}</p>
      <label for="f-age">Ton âge</label>
      <div class="champ"><input id="f-age" data-afield="age" value="${esc(S.auth.age)}" inputmode="numeric" maxlength="3" autocomplete="off" placeholder="par exemple 25"></div>`;

  function authScreen() {
    const a = S.auth;
    const msg = a.msg ? '<p class="msg">' + esc(a.msg) + "</p>" : "";
    if (a.screen === "create") {
      return `<div class="col"><div class="coeur">💗</div><h1>Créer mon compte</h1>
        <p>Choisis ton pseudo : ton partenaire s'en servira pour te retrouver.</p>
        ${pseudoAgeFields()}
        <p class="gris">Puis choisis comment te connecter :</p>
        ${methodButtons(true)}${msg}
        <button class="lien" data-action="goWelcome">Retour</button></div>`;
    }
    if (a.screen === "login") {
      return `<div class="col"><div class="coeur">💗</div><h1>Me connecter</h1>
        <p>Utilise la même méthode que lors de la création de ton compte pour retrouver ton pseudo et tes conversations.</p>
        ${methodButtons(false)}${msg}
        <button class="lien" data-action="goWelcome">Retour</button></div>`;
    }
    if (a.screen === "phone") {
      return `<div class="col"><h1>Ton numéro</h1>
        <p>Nous t'envoyons un code par SMS pour confirmer ton numéro.</p>
        <label for="f-phone">Numéro de téléphone (avec l'indicatif)</label>
        <div class="champ"><input id="f-phone" data-afield="phone" value="${esc(a.phone)}" inputmode="tel" autocomplete="tel" maxlength="20"></div>
        <button class="principal" data-action="sendCode" ${a.busy || phoneDigits(a.phone).length < 8 ? "disabled" : ""}>Envoyer le code</button>
        ${msg}<button class="lien" data-action="phoneBack">Retour</button></div>`;
    }
    if (a.screen === "code") {
      return `<div class="col"><h1>Entre le code</h1>
        <p>Code envoyé par SMS au ${esc(e164(a.phone))}.</p>
        <div class="champ"><input id="f-code" data-afield="code" value="${esc(a.code)}" inputmode="numeric" autocomplete="one-time-code" maxlength="8" placeholder="123456"></div>
        <button class="principal" data-action="verifyCode" ${a.busy || a.code.length < 6 ? "disabled" : ""}>Valider</button>
        ${msg}<button class="lien" data-action="sendCode">Renvoyer le code</button>
        <button class="lien" data-action="phoneStart">Changer de numéro</button></div>`;
    }
    return `<div class="col"><div class="coeur">💗</div><h1>Tu me manques</h1>
      <p>Un petit mot, un bouton, et la personne que tu aimes sait que tu penses à elle.</p>
      <button class="principal" data-action="goCreate">Créer un compte</button>
      <button class="secondaire" data-action="goLogin">J'ai déjà un compte</button>${msg}</div>`;
  }

  // Connecté mais sans pseudo : on demande le pseudo et l'âge.
  function usernameScreen() {
    return `<div class="col"><div class="coeur">💗</div><h1>Bienvenue</h1>
      <p>Choisis ton pseudo : ton partenaire s'en servira pour te retrouver.</p>
      ${pseudoAgeFields()}
      <button class="principal" data-action="createProfile" data-needok ${canContinue() && !S.busy ? "" : "disabled"}>Créer mon compte</button>
      ${messageBlock()}${S.auth.msg ? '<p class="msg">' + esc(S.auth.msg) + "</p>" : ""}</div>`;
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
      <h2>Salut 👋</h2>
      <p>Ajoute ton partenaire avec son pseudo ou son numéro de téléphone.</p>
      ${incoming}
      <label for="f-partner">Pseudo ou numéro de ton partenaire</label>
      <div class="champ"><input id="f-partner" data-field="partner" value="${esc(S.draft.partner)}"
        autocapitalize="none" autocomplete="off" autocorrect="off" spellcheck="false" maxlength="24" placeholder="pseudo ou +237 6…"></div>
      <button class="principal" data-action="addPartner" ${S.draft.partner.trim().length >= 3 && !S.busy ? "" : "disabled"}>Envoyer la demande</button>
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
      <div><span class="gris" style="font-size:18px">moi 💗 </span><span class="pseudo" data-action="askRemove">@${esc(S.partnerName)}</span></div>
      <p class="petit" style="margin-top:-8px">Touche son pseudo pour retirer ton partenaire</p>
      <button class="principal gros" data-action="miss" ${S.busy ? "disabled" : ""}>Tu me manques 💗</button>
      <button class="secondaire chatbtn" data-action="openChat">💬 Chat${S.unread ? ' <span class="badge">' + (S.unread > 99 ? "99+" : S.unread) + "</span>" : ""}</button>
      ${messageBlock()}
      ${miss}
      ${pushBlock()}
    </div>`;
  }

  function dialogs() {
    let html = "";
    if (S.offerInvite) {
      html += `<div class="fond"><div class="boite">
        <h2 style="font-size:20px">Compte introuvable</h2>
        <p>${/[a-z_]/.test(S.offerInvite) ? "@" + esc(S.offerInvite) : esc(S.offerInvite)} n'a pas encore de compte. Cette personne n'a peut-être pas l'application. Veux-tu la lui envoyer ?</p>
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
    else if (S.needAuth) html = authScreen();
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
    if (!el.dataset) return;
    if (el.dataset.afield) {
      const f = el.dataset.afield;
      let v = el.value;
      if (f === "pseudo") v = filterName(v);
      else if (f === "age") v = v.replace(/\D/g, "").slice(0, 3);
      else if (f === "phone") v = v.replace(/[^0-9+ ()-]/g, "").slice(0, 20);
      else if (f === "code") v = v.replace(/\D/g, "").slice(0, 8);
      if (el.value !== v) el.value = v;
      S.auth[f] = v;
      if (f === "pseudo") checkPseudo(); else paintAuth();
      return;
    }
    if (!el.dataset.field) return;
    const value = filterTarget(el.value);
    if (el.value !== value) el.value = value;
    S.draft[el.dataset.field] = value;
    const btn = root.querySelector('[data-action="addPartner"]');
    if (btn) btn.disabled = value.trim().length < 3 || S.busy;
  });

  window.TMM_API = {
    CFG, session, authed, esc,
    setUnread(n) { if (S.unread !== n) { S.unread = n; lastSnapshotKey = ""; render(); } },
    reload() { return refresh(); },
  };

  // ---------- Démarrage ----------
  async function start() {
    S.loading = true;
    S.error = null;
    render();
    try {
      const returned = await handleOAuthReturn();
      if (!store.get()) {
        S.loading = false;
        S.needAuth = true;
        render();
        return;
      }
      await session(false);
      if (returned) await finishAuth();
      else {
        applySnapshot(await loadSnapshot());
        afterLogin();
      }
    } catch (e) {
      S.loading = false;
      if (e && e.message === "NO_SESSION") S.needAuth = true;
      else S.error = "Connexion impossible. Vérifie ta connexion internet puis réessaie.";
    }
    render();
  }

  async function refresh() {
    if (S.loading || S.error || S.busy || S.needAuth || !S.username || document.visibilityState !== "visible") return;
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
