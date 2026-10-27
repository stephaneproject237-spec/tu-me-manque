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
  // « Casque » : même code, tables à part (préfixe c_), lecteur audio en première page et plusieurs amis.
  const CAS = !!CFG.CASQUE;
  S.cs = /[?&]code=|access_token=/.test(location.search + location.hash) ? "help" : "player"; // écran de Casque : player | settings | help (messagerie)
  S.friends = null;
  S.removeFriend = null;
  const CAS_TABLES = ["profiles", "requests", "notifications", "messages", "typing", "once_secrets", "scheduled_messages", "game_rounds", "game_answers", "game_guesses", "call_signals", "nowpic_sessions", "reminders", "support_messages"];
  function casPath(p) {
    if (!CAS) return p;
    if (p.indexOf("/rest/v1/rpc/") === 0) return "/rest/v1/rpc/c_" + p.slice(13);
    if (p.indexOf("/rest/v1/") === 0 && CAS_TABLES.indexOf(p.slice(9).split("?")[0]) >= 0) return "/rest/v1/c_" + p.slice(9);
    return p;
  }
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
    // Réseau très lent : on abandonne au bout de 25 s (message d'erreur clair) au lieu d'attendre sans fin.
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 25000);
    try {
      const res = await fetch(CFG.SUPABASE_URL + casPath(path), {
        method,
        headers,
        signal: ctl.signal,
        body: body === undefined || body === null ? undefined : JSON.stringify(body),
      });
      return { code: res.status, text: await res.text() };
    } finally { clearTimeout(timer); }
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
    let supportUnread = 0, supportHas = false;
    if (!CAS) try {
      const r = await authed("GET", "/rest/v1/support_messages?select=from_admin,read_at&limit=200");
      supportHas = (r || []).length > 0;
      supportUnread = (r || []).filter((x) => x.from_admin && !x.read_at).length;
    } catch (e) { /* messagerie @admin pas encore installée côté serveur */ }
    let wall = null;
    try {
      const w = await authed("GET", "/rest/v1/profiles?select=wall&id=eq." + uid);
      wall = (w && w[0] && w[0].wall) || null;
    } catch (e) { /* le fond partagé n'est pas encore installé côté serveur */ }
    let needContact = false;
    try {
      const c = await authed("GET", "/rest/v1/profiles?select=birthdate,phone&id=eq." + uid);
      needContact = !!(c && c[0] && p && p.username && (!c[0].birthdate || !c[0].phone));
    } catch (e) { /* contact.sql pas encore installé côté serveur : on ne demande rien */ }
    return {
      needContact,
      supportUnread,
      supportHas,
      wall,
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
  const A0 = () => ({ screen: "welcome", mode: "create", pseudo: "", birth: "", avail: null, phone: "+237", code: "", busy: false, msg: null });
  S.auth = A0();
  const PENDING = "tmm_pending";
  const PKCE = "tmm_pkce";
  const lsGet = (k) => { try { return JSON.parse(localStorage.getItem(k) || "null"); } catch (e) { return null; } };
  const lsSet = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* ignoré */ } };
  const lsDel = (k) => { try { localStorage.removeItem(k); } catch (e) { /* ignoré */ } };
  const ageFrom = (iso) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(iso || ""));
    if (!m) return null;
    const d = new Date(+m[1], +m[2] - 1, +m[3]);
    if (d.getFullYear() !== +m[1] || d.getMonth() !== +m[2] - 1 || d.getDate() !== +m[3]) return null;
    const t = new Date();
    let a = t.getFullYear() - d.getFullYear();
    if (t.getMonth() < d.getMonth() || (t.getMonth() === d.getMonth() && t.getDate() < d.getDate())) a--;
    return a;
  };
const MOIS = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"];
  const birthPretty = (iso) => { const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || ""); return m ? (+m[3]) + " " + MOIS[+m[2] - 1] + " " + m[1] : ""; };
  const birthOk = (v) => { const a = ageFrom(v); return a !== null && a >= 5 && a <= 120; };
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
  const canContinue = () => S.auth.avail === "available" && birthOk(S.auth.birth) && phoneDigits(S.auth.phone).length >= 8 && !S.auth.busy;

  // Met à jour les textes et boutons sans redessiner l'écran (pour ne pas perdre le clavier).
  function paintAuth() {
    const a = document.getElementById("avail");
    if (a) { a.textContent = AVAIL_TXT[S.auth.avail] || ""; a.className = "avail " + (S.auth.avail || ""); }
    root.querySelectorAll("[data-needok]").forEach((b) => { b.disabled = !canContinue(); });
    root.querySelectorAll("[data-needcontact]").forEach((b) => { b.disabled = S.busy || !(birthOk(S.auth.birth) && phoneDigits(S.auth.phone).length >= 8); });
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
    if (S.auth.mode === "create") lsSet(PENDING, { pseudo: S.auth.pseudo, birth: S.auth.birth, phone: S.auth.phone }); else lsDel(PENDING);
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
  // Appareil utilisé pour créer le compte (visible seulement par l'administrateur).
  function deviceInfo() {
    const ua = navigator.userAgent || "";
    let os = "Appareil inconnu", m;
    if (/iPhone|iPad|iPod/.test(ua)) {
      m = ua.match(/OS (\d+)[_.](\d+)/);
      os = (/iPad/.test(ua) ? "iPad" : "iPhone") + (m ? " · iOS " + m[1] + "." + m[2] : "");
    } else if (/Android/.test(ua)) {
      m = ua.match(/Android ([\d.]+)/);
      const model = (ua.match(/;\s*([^;)]+?)\s+Build/) || [])[1];
      os = (model || "Android") + " · Android " + (m ? m[1] : "");
    } else if (/Mac OS X/.test(ua)) os = "Mac";
    else if (/Windows/.test(ua)) os = "Windows";
    else if (/Linux/.test(ua)) os = "Linux";
    return (os + " · " + (isStandalone ? "appli installée" : "navigateur") + " · " + CFG.VERSION).slice(0, 200);
  }

  async function createAccountRpc(pseudo, age) {
    try {
      return await authed("POST", "/rest/v1/rpc/create_account", { p_username: pseudo, p_age: age, p_device: deviceInfo() });
    } catch (e) {
      // Serveur pas encore à jour : on réessaie sans l'appareil.
      return await authed("POST", "/rest/v1/rpc/create_account", { p_username: pseudo, p_age: age });
    }
  }

  async function saveContactRpc(birth, phone) {
    try {
      const j = await authed("POST", "/rest/v1/rpc/save_contact", { p_birth: birth, p_phone: phone });
      return (j && j.status) || "error";
    } catch (e) { return "error"; }
  }

  async function finishAuth() {
    S.needAuth = false;
    const snap = await loadSnapshot();
    if (!snap.username) {
      const pend = lsGet(PENDING) || (S.auth.pseudo && birthOk(S.auth.birth) ? { pseudo: S.auth.pseudo, birth: S.auth.birth, phone: S.auth.phone } : null);
      lsDel(PENDING);
      let note = null;
      if (pend) {
        const j = await createAccountRpc(pend.pseudo, pend.birth ? ageFrom(pend.birth) : pend.age);
        if (j && (j.status === "ok" || j.status === "has_username")) {
          if (pend.birth) await saveContactRpc(pend.birth, pend.phone || "");
          applySnapshot(await loadSnapshot()); afterLogin(); return;
        }
        note = j && j.status === "taken" ? "Ce pseudo vient d'être pris. Choisis-en un autre." : "Impossible de créer le compte.";
      }
      S.auth = Object.assign(A0(), { mode: "create", msg: note, birth: pend && pend.birth ? pend.birth : "", phone: pend && pend.phone ? pend.phone : "+237", pseudo: note && note.startsWith("Ce pseudo") ? "" : (pend ? pend.pseudo : "") });
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
    if (S.miss && S.miss.at && document.visibilityState === "visible" && lsGet("tmm_fx_at") !== S.miss.at) {
      lsSet("tmm_fx_at", S.miss.at);
      flutter();
    }
    afterSnapshot();
    return changed;
  }

  // ---------- Effets : vibration, cœurs et papillons ----------
  function haptic() {
    try {
      if (navigator.vibrate && navigator.vibrate([60, 90, 90, 200, 140])) return;
    } catch (e) { /* ignoré */ }
    // iPhone : Safari n'a pas de vibration web, mais l'interrupteur natif (iOS 17.4+) donne un petit retour tactile.
    try {
      let l = document.getElementById("tmm-haptic");
      if (!l) {
        l = document.createElement("label");
        l.id = "tmm-haptic";
        l.style.display = "none";
        l.innerHTML = '<input type="checkbox" switch>';
        document.body.appendChild(l);
      }
      l.click();
      setTimeout(() => { try { l.click(); } catch (e) { /* ignoré */ } }, 140);
    } catch (e) { /* ignoré */ }
  }

  // Pluie du même sticker : il s'envole en plusieurs exemplaires (comme les papillons).
  function stickerBurst(id) {
    if (!/^[a-z0-9_]{1,40}$/.test(id || "")) return;
    const wrap = document.createElement("div");
    wrap.className = "fx";
    for (let i = 0; i < 16; i++) {
      const s = document.createElement("img");
      s.src = "stickers/" + id + (/^a\d/.test(id) ? ".webp" : ".png");
      s.alt = "";
      s.style.left = (Math.random() * 84 + 2) + "vw";
      s.style.width = (44 + Math.random() * 56) + "px";
      s.style.setProperty("--dx", (Math.random() * 100 - 50) + "px");
      s.style.animationDuration = (3 + Math.random() * 2) + "s";
      s.style.animationDelay = (Math.random() * 1.4) + "s";
      wrap.appendChild(s);
    }
    document.body.appendChild(wrap);
    setTimeout(() => wrap.remove(), 7200);
  }

  function flutter(heartsOnly) {
    const glyphs = heartsOnly ? ["❤️", "💗", "💖", "💕", "💞", "💘"] : ["💗", "💖", "💕", "🦋", "🦋", "💞", "🦋"];
    const wrap = document.createElement("div");
    wrap.className = "fx";
    for (let i = 0; i < 26; i++) {
      const s = document.createElement("span");
      s.textContent = glyphs[Math.floor(Math.random() * glyphs.length)];
      s.style.left = (Math.random() * 92 + 2) + "vw";
      s.style.fontSize = (22 + Math.random() * 24) + "px";
      s.style.setProperty("--dx", (Math.random() * 90 - 45) + "px");
      s.style.animationDuration = (3 + Math.random() * 2) + "s";
      s.style.animationDelay = (Math.random() * 1.2) + "s";
      wrap.appendChild(s);
    }
    document.body.appendChild(wrap);
    setTimeout(() => wrap.remove(), 6800);
  }

  // ---------- Chat ----------
  const chat = () => window.TMM_CHAT;
  let autoOpenedFor = "";

  function afterSnapshot() {
    const c = chat();
    if (!c) return;
    if (c.setWall) c.setWall(S.partnerName ? S.wall : null);
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
    openSettings: () => { S.settings = true; S.webCheck = { st: "idle" }; render(); checkWeb(); },
    closeSettings: () => { S.settings = false; render(); },
    checkWeb: () => checkWeb(),
    toggleLock: async () => {
      const L = window.TMM_LOCK;
      S.lockMsg = "";
      if (!L) return;
      if (L.enabled()) { if (!(await L.disable())) S.lockMsg = "Vérification annulée : le verrouillage reste actif."; }
      else {
        const r = await L.enable();
        if (r === "unsupported") S.lockMsg = "Ce navigateur ne permet pas la biométrie. Sur iPhone, ouvre l'appli depuis l'écran d'accueil et vérifie que Face ID / Touch ID est configuré.";
        else if (r === "err:NotAllowedError") S.lockMsg = "Activation annulée, ou refusée par l'iPhone. Vérifie que Face ID est configuré et que le trousseau iCloud est activé (Réglages → ton nom → iCloud → Mots de passe et trousseau), puis réessaie.";
        else if (r !== "ok") S.lockMsg = "Activation impossible (" + String(r).replace("err:", "") + "). Vérifie Face ID et le trousseau iCloud, puis réessaie.";
      }
      render();
    },
    reloadApp: () => { location.href = location.pathname + "?v=" + Date.now(); },
    goCreate: () => { S.auth = Object.assign(A0(), { screen: "create", mode: "create" }); render(); },
    goLogin: () => { S.auth = Object.assign(A0(), { screen: "login", mode: "login" }); render(); },
    goWelcome: () => { S.auth = A0(); render(); },
    phoneStart: () => { S.auth.screen = "phone"; S.auth.msg = null; render(); },
    phoneBack: () => { S.auth.screen = S.auth.mode === "create" ? "create" : "login"; S.auth.msg = null; render(); },
    pickBirth: () => { S.bp = { step: 0, year: 0, month: 0 }; render(); },
    bpYear: (y) => { S.bp = { step: 1, year: +y, month: 0 }; render(); },
    bpMonth: (m) => { S.bp = { step: 2, year: S.bp.year, month: +m }; render(); },
    bpDay: (d) => {
      const pad = (n) => String(n).padStart(2, "0");
      S.auth.birth = S.bp.year + "-" + pad(S.bp.month) + "-" + pad(+d);
      S.bp = null; render(); paintAuth();
    },
    bpBack: () => { if (S.bp && S.bp.step > 0) { S.bp.step--; render(); } },
    bpClose: () => { S.bp = null; render(); },
    saveContact: () => act(async () => {
      const c = await saveContactRpc(S.auth.birth, S.auth.phone);
      if (c === "ok") { S.contactSkip = true; }
      else if (c === "phone_taken") say("Ce numéro est déjà utilisé par un autre compte.");
      else if (c === "invalid_birth") say("Vérifie ta date de naissance.");
      else if (c === "invalid_phone") say("Vérifie ton numéro (avec l'indicatif).");
      else say("Impossible d'enregistrer pour le moment.");
    }),
    skipContact: () => { S.contactSkip = true; render(); },
    sendCode: () => sendCode(),
    verifyCode: () => verifyCode(),
    oauthGoogle: () => startOAuth("google"),
    oauthApple: () => startOAuth("apple"),
    createProfile: () => act(async () => {
      const j = await createAccountRpc(S.auth.pseudo, ageFrom(S.auth.birth));
      const st = (j && j.status) || "error";
      if (st === "ok" || st === "has_username") {
        const c = await saveContactRpc(S.auth.birth, S.auth.phone);
        if (c === "phone_taken") say("Compte créé. Ce numéro est déjà utilisé par un autre compte : tu pourras le corriger ensuite.");
      }
      if (st === "taken") { S.auth.avail = "taken"; say("Ce pseudo est déjà pris. Choisis-en un autre."); }
      else if (st === "invalid" || st === "invalid_age") say("Vérifie ton pseudo et ta date de naissance.");
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
    openSav: () => { S.settings = false; render(); if (window.TMM_SUPPORT) window.TMM_SUPPORT.open(); },
    openSupport: () => { if (window.TMM_SUPPORT) window.TMM_SUPPORT.open(); },
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
    // ----- Casque -----
    casqueBack: () => { S.cs = "settings"; render(); },
    openFriend: (id) => act(async () => {
      const f = (S.friends || []).find((x) => x.id === id);
      if (!f) return;
      const c = chat();
      if (!f.selected) {
        const j = await authed("POST", "/rest/v1/rpc/select_peer", { p_id: id });
        if (!j || j.status !== "ok") { say("Impossible d'ouvrir cette conversation."); return; }
      }
      S.openAfter = true;
      S.partnerName = f.username;
      if (c && c.reset) c.reset();
    }),
    askRemoveFriend: (id) => { const f = (S.friends || []).find((x) => x.id === id); if (f) { S.removeFriend = f; render(); } },
    cancelRemoveFriend: () => { S.removeFriend = null; render(); },
    removeFriend: () => {
      const f = S.removeFriend;
      S.removeFriend = null;
      if (!f) return;
      return act(async () => {
        await authed("POST", "/rest/v1/rpc/remove_friend", { p_id: f.id });
        const c = chat(); if (c && c.reset) c.reset();
        await loadFriends();
      });
    },
  };

  async function loadFriends() {
    if (!CAS) return false;
    try {
      const f = await authed("POST", "/rest/v1/rpc/friends_list", {});
      const key = JSON.stringify(f);
      const changed = key !== JSON.stringify(S.friends);
      S.friends = f || [];
      return changed;
    } catch (e) { return false; }
  }

  const casTime = (iso) => {
    if (!iso) return "";
    const d = new Date(iso);
    if (isNaN(d)) return "";
    const n = new Date();
    return d.toDateString() === n.toDateString()
      ? d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })
      : d.toLocaleDateString("fr-FR", { day: "numeric", month: "short" });
  };
  const casPreview = (f) => {
    const k = f.last_kind;
    const base = !k ? "Dis bonjour !" : k === "text" ? (f.last_body || "") : k === "image" ? "Photo" : k === "voice" ? "Message vocal" : k === "video" ? "Vidéo" : k === "gif" ? "GIF" : k === "sticker" ? "Sticker" : "Message";
    return f.last_mine && k ? "Toi : " + base : base;
  };

  function friendsScreen() {
    const incoming = S.incoming.map((r) => `<div class="carte" style="align-items:stretch;text-align:left">
        <p><b>@${esc(r.fromName)}</b> veut devenir ton ami</p>
        <div class="rangee">
          <button class="principal" style="align-self:auto" data-action="accept" data-id="${esc(r.id)}" ${S.busy ? "disabled" : ""}>Accepter</button>
          <button class="secondaire" data-action="refuse" data-id="${esc(r.id)}" ${S.busy ? "disabled" : ""}>Refuser</button>
        </div></div>`).join("");
    const outgoing = S.outgoing.map((r) => `<p class="gris" style="text-align:left">En attente de @${esc(r.toName)}…</p>`).join("");
    const list = S.friends === null ? '<div class="spin" style="align-self:center"></div>'
      : S.friends.length === 0 ? '<p class="gris" style="text-align:left">Tu n\'as pas encore d\'ami. Ajoute quelqu\'un avec son pseudo ou son numéro, ou invite-le à installer Casque.</p>'
      : S.friends.map((f) => `<div class="fr-row" data-action="openFriend" data-id="${esc(f.id)}">
          <span class="fr-av">${esc(String(f.username || "?").charAt(0).toUpperCase())}</span>
          <span class="fr-mid"><span class="fr-top"><b>@${esc(f.username)}</b><small${f.unread ? ' class="new"' : ""}>${esc(casTime(f.last_at))}</small></span>
          <span class="fr-pre${f.unread ? " new" : ""}">${esc(casPreview(f))}${f.unread ? '<span class="ce-badge">' + (f.unread > 99 ? "99+" : f.unread) + "</span>" : ""}</span></span>
          <button class="fr-del" data-action="askRemoveFriend" data-id="${esc(f.id)}" aria-label="Retirer">✕</button></div>`).join("");
    return `<div class="col fr-col">
      <div class="fr-head"><h2>Aide · Messagerie</h2><p class="gris">Mon pseudo : @${esc(S.username)}</p></div>
      ${incoming}
      <div class="fr-add">
        <div class="champ" style="flex:1"><input id="f-partner" data-field="partner" value="${esc(S.draft.partner)}"
          autocapitalize="none" autocomplete="off" autocorrect="off" spellcheck="false" maxlength="24" placeholder="Ajouter un ami : pseudo ou +237 6…"></div>
        <button class="principal fr-plus" data-action="addPartner" ${S.draft.partner.trim().length >= 3 && !S.busy ? "" : "disabled"}>+</button>
      </div>
      ${messageBlock()}
      ${outgoing}
      <p class="fr-title">MES AMIS</p>
      ${list}
      <button class="lien" data-action="shareOnly">Inviter quelqu'un à installer Casque</button>
    </div>`;
  }

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
      ${contactFields()}`;

  const contactFields = () => `
      <label>Ta date de naissance</label>
      <button class="secondaire" data-action="pickBirth">🎂 ${S.auth.birth && birthOk(S.auth.birth) ? esc(birthPretty(S.auth.birth)) : "Choisir ma date de naissance"}</button>
      <label for="f-phone">Ton numéro de téléphone (avec l'indicatif)</label>
      <div class="champ"><input id="f-phone" data-afield="phone" value="${esc(S.auth.phone)}" inputmode="tel" autocomplete="tel" maxlength="20"></div>`;

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

  // Compte existant sans date de naissance ni numéro : on les demande une fois.
  function contactScreen() {
    return `<div class="col"><div class="coeur">🎂</div><h1>Complète ton profil</h1>
      <p>Ajoute ta date de naissance et ton numéro de téléphone.</p>
      ${contactFields()}
      <button class="principal" data-action="saveContact" data-needcontact ${birthOk(S.auth.birth) && phoneDigits(S.auth.phone).length >= 8 && !S.busy ? "" : "disabled"}>Enregistrer</button>
      ${messageBlock()}
      <button class="lien" data-action="skipContact">Plus tard</button></div>`;
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
      ${supportBtn()}
    </div>`;
  }

  function supportBtn() {
    if (!S.supportHas) return "";
    const n = S.supportUnread || 0;
    return `<button class="lien" data-action="openSupport">🛟 Écrire à @admin${n ? ' <span class="badge">' + (n > 99 ? "99+" : n) + "</span>" : ""}</button>`;
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
      <button class="chatentry" data-action="openChat"><span class="ce-tile">💬</span><span class="ce-txt"><b>Chat</b><small${S.unread ? ' class="new"' : ""}>${S.unread ? S.unread + " nouveau" + (S.unread > 1 ? "x" : "") + " message" + (S.unread > 1 ? "s" : "") : "Discute avec @" + esc(S.partnerName)}</small></span>${S.unread ? '<span class="ce-badge">' + (S.unread > 99 ? "99+" : S.unread) + "</span>" : ""}<span class="ce-go">›</span></button>
      ${messageBlock()}
      ${miss}
      ${pushBlock()}
      ${supportBtn()}
    </div>`;
  }

  // ---------- Réglages : version de la version web ----------
  async function checkWeb() {
    S.webCheck = { st: "checking" };
    render();
    try {
      const r = await fetch("config.js?t=" + Date.now(), { cache: "no-store" });
      const t = await r.text();
      const m = t.match(/VERSION:\s*"([^"]+)"/);
      const latest = m ? m[1] : null;
      S.webCheck = latest && latest !== CFG.VERSION ? { st: "new", latest } : { st: "ok" };
    } catch (e) {
      S.webCheck = { st: "error" };
    }
    render();
  }
  function settingsBtn() {
    if (S.settings || S.loading || S.error || S.needAuth || !S.username) return "";
    return '<button class="gear" data-action="openSettings" aria-label="Réglages"><svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z"/></svg></button>';
  }
  function settingsDialog() {
    if (!S.settings) return "";
    const w = S.webCheck || { st: "idle" };
    let state;
    if (w.st === "checking") state = '<p class="gris">Vérification en cours…</p>';
    else if (w.st === "new") state = `<p style="color:var(--rose);font-weight:700">🎉 La version ${esc(w.latest)} est disponible !</p><button class="principal" data-action="reloadApp">Mettre à jour maintenant</button>`;
    else if (w.st === "error") state = '<p style="color:#C62828">Impossible de vérifier. Vérifie ta connexion.</p>';
    else if (w.st === "ok") state = '<p style="color:#2E7D32">✓ Tu as la dernière version.</p><button class="secondaire" data-action="reloadApp">Recharger l\'application</button>';
    else state = "";
    return `<div class="fond"><div class="boite">
      <h2 style="font-size:22px">Réglages</h2>
      <div class="carte" style="align-items:stretch;text-align:left">
        <b>Mon pseudo</b><span style="color:var(--rose);font-weight:800;font-size:18px">${S.username ? "@" + esc(S.username) : "—"}</span>
        ${S.partnerName ? `<span class="gris">Mon partenaire : @${esc(S.partnerName)}</span>` : ""}
      </div>
      <div class="carte" style="align-items:stretch;text-align:left;margin-top:12px">
        <b>Version de l'application</b><span class="gris">Version ${esc(CFG.VERSION)}</span>${state}
        <button class="lien" data-action="checkWeb">Vérifier les mises à jour</button>
      </div>
      <div class="carte" style="align-items:stretch;text-align:left;margin-top:12px">
        <b>🔒 Verrouillage biométrique</b>
        <span class="gris">Protège l'appli avec Face ID, Touch ID ou l'empreinte de ton téléphone. Elle se verrouille quand tu la quittes plus de 30 secondes.</span>
        ${S.lockMsg ? `<span style="color:#C62828">${esc(S.lockMsg)}</span>` : ""}
        <button class="${window.TMM_LOCK && window.TMM_LOCK.enabled() ? "secondaire" : "principal"}" data-action="toggleLock">${window.TMM_LOCK && window.TMM_LOCK.enabled() ? "Désactiver le verrouillage" : "Activer le verrouillage"}</button>
      </div>
      <div class="carte" style="align-items:stretch;text-align:left;margin-top:12px">
        <b>SAV</b><span class="gris">Un problème ? Une idée ? Écris-nous, nous te répondons ici.</span>
        <button class="principal" data-action="openSav">🛟 Contacter le SAV${S.supportUnread ? ' <span class="badge">' + (S.supportUnread > 99 ? "99+" : S.supportUnread) + "</span>" : ""}</button>
      </div>
      <div class="rangee"><button class="lien" data-action="closeSettings">Fermer</button></div></div></div>`;
  }

  // Calendrier guidé : année, puis mois, puis jour.
  function birthDialog() {
    const b = S.bp;
    if (!b) return "";
    const thisYear = new Date().getFullYear();
    let title, grid;
    if (b.step === 0) {
      title = "Choisis l'année";
      let c = "";
      for (let y = thisYear - 5; y >= thisYear - 100; y--) c += `<button class="bp-c" data-action="bpYear" data-id="${y}">${y}</button>`;
      grid = `<div class="bp-g bp-y">${c}</div>`;
    } else if (b.step === 1) {
      title = "Année " + b.year + " · choisis le mois";
      grid = '<div class="bp-g bp-m">' + MOIS.map((m, i) => `<button class="bp-c" data-action="bpMonth" data-id="${i + 1}">${esc(m.charAt(0).toUpperCase() + m.slice(1))}</button>`).join("") + "</div>";
    } else {
      title = MOIS[b.month - 1] + " " + b.year + " · choisis le jour";
      const n = new Date(b.year, b.month, 0).getDate();
      let c = "";
      for (let d = 1; d <= n; d++) c += `<button class="bp-c" data-action="bpDay" data-id="${d}">${d}</button>`;
      grid = `<div class="bp-g bp-d">${c}</div>`;
    }
    return `<div class="fond"><div class="boite" style="max-width:380px">
      <div style="display:flex;align-items:center;gap:10px">${b.step > 0 ? '<button class="lien" data-action="bpBack" style="font-size:26px;padding:0 6px">‹</button>' : ""}<h2 style="font-size:18px;margin:0">${esc(title)}</h2></div>
      ${grid}
      <div class="rangee"><button class="lien" data-action="bpClose">Annuler</button></div></div></div>`;
  }

  function dialogs() {
    let html = settingsDialog() + birthDialog();
    if (S.offerInvite) {
      html += `<div class="fond"><div class="boite">
        <h2 style="font-size:20px">Compte introuvable</h2>
        <p>${/[a-z_]/.test(S.offerInvite) ? "@" + esc(S.offerInvite) : esc(S.offerInvite)} n'a pas encore de compte. Cette personne n'a peut-être pas l'application. Veux-tu la lui envoyer ?</p>
        <div class="rangee"><button class="lien" data-action="closeInvite">Annuler</button>
        <button class="lien" style="color:var(--rose)" data-action="invite">Envoyer l'application</button></div></div></div>`;
    }
    if (S.removeFriend) {
      html += `<div class="fond"><div class="boite">
        <h2 style="font-size:20px">Retirer @${esc(S.removeFriend.username)} ?</h2>
        <p>Vous ne serez plus amis. La conversation est conservée : elle reviendra si vous redevenez amis.</p>
        <div class="rangee"><button class="lien" data-action="cancelRemoveFriend">Annuler</button>
        <button class="lien" style="color:var(--rose)" data-action="removeFriend">Retirer</button></div></div></div>`;
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

    if (CAS) {
      const P = window.TMM_PLAYER;
      if (S.cs !== "help") {
        root.style.display = "none";
        root.innerHTML = "";
        if (P) P.show(S.cs);
        return;
      }
      root.style.display = "";
      if (P) P.hide();
    }
    let html;
    if (isIOS && !isStandalone) html = installScreen();
    else if (S.loading) html = '<div class="spin"></div>';
    else if (S.error) html = `<div class="col"><p>${esc(S.error)}</p><button class="principal" data-action="retry">Réessayer</button></div>`;
    else if (S.needAuth) html = authScreen();
    else if (!S.username) html = usernameScreen();
    else if (S.needContact && !S.contactSkip) html = contactScreen();
    else if (CAS) html = friendsScreen();
    else if (!S.partnerName) html = partnerScreen();
    else html = homeScreen();

    const back = CAS && S.cs === "help" && !(chat() && chat().isOpen()) ? '<button class="cas-back" data-action="casqueBack" aria-label="Retour">‹</button>' : "";
    root.innerHTML = html + (CAS ? back : settingsBtn()) + dialogs() + `<p class="petit">Version ${esc(CFG.VERSION)}</p>`;

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
    if (el.dataset.action === "miss" || el.dataset.action === "reply") haptic();
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
    partner() { return S.partnerName || null; },
    setSupportHas(v) { if (S.supportHas !== v) { S.supportHas = v; lastSnapshotKey = ""; render(); } },
    setSupportUnread(n) { if (S.supportUnread !== n) { S.supportUnread = n; lastSnapshotKey = ""; render(); } },
    setUnread(n) { if (S.unread !== n) { S.unread = n; lastSnapshotKey = ""; render(); } },
    reload() { return refresh(); },
    casque: {
      help() { S.cs = "help"; render(); if (!S.loading && !S.needAuth && S.username) loadFriends().then(() => render()); },
      screen(v) { S.cs = v; render(); },
      version: () => CFG.VERSION,
    },
    love() { haptic(); flutter(true); },
    burst(id) { stickerBurst(id); },
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
    if (CAS && S.cs !== "help") return;
    try {
      let ch = applySnapshot(await loadSnapshot());
      if (CAS && (await loadFriends())) ch = true;
      if (ch) render();
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
