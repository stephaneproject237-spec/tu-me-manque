(() => {
  "use strict";
  // Chat « à la Telegram » : texte, emojis, stickers, GIF, photos, messages vocaux.
  const host = document.getElementById("chat");
  const A = () => window.TMM_API;
  const esc = (v) => A().esc(v);
  const fine = window.matchMedia && window.matchMedia("(pointer: fine)").matches;

  const PAGE = 40;
  let built = false;
  let opened = false;
  let me = null;
  let partner = "";
  let msgs = [];            // messages confirmés, triés par id
  const byId = new Map();
  const unreadOwn = new Set();
  let pendings = [];        // messages en cours d'envoi
  let noMore = false;
  let loadingOlder = false;
  let pollTimer = null;
  let polling = false;
  let tmpCount = 0;
  let replyTo = null;       // message auquel on répond : {id, by_me, kind, text}
  let typTimer = null, lastTypSent = 0;
  let panelTab = null;      // null | "emoji" | "sticker" | "gif"
  let stickers = [];

  const $ = (id) => document.getElementById(id);

  // ---------- Outils ----------
  const mediaUrl = (path) => A().CFG.SUPABASE_URL + "/storage/v1/object/public/chat/" + path;
  const hhmm = (d) => new Date(d).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
  const dayKey = (d) => { const x = new Date(d); return x.getFullYear() + "-" + x.getMonth() + "-" + x.getDate(); };
  function dayLabel(d) {
    const x = new Date(d);
    const now = new Date();
    if (dayKey(x) === dayKey(now)) return "Aujourd'hui";
    const y = new Date(now); y.setDate(now.getDate() - 1);
    if (dayKey(x) === dayKey(y)) return "Hier";
    return x.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long" });
  }
  const mmss = (s) => { s = Math.max(0, Math.round(s || 0)); return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0"); };

  let toastTimer = null;
  function toast(t) {
    const el = $("ch-toast");
    if (!el) { const e = document.createElement("div"); e.className = "np-toast"; e.textContent = t; document.body.appendChild(e); setTimeout(() => e.remove(), 3200); return; }
    el.textContent = t; el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 3500);
  }

  function linkify(text) {
    return esc(text).replace(/(https?:\/\/[^\s<]+[^\s<.,;:!?)])/g, '<a href="$1" target="_blank" rel="noopener noreferrer">$1</a>');
  }

  function isBigEmoji(text) {
    const t = String(text || "").trim();
    if (!t || t.length > 40) return false;
    if (!/^(?:\p{Extended_Pictographic}|\p{Emoji_Modifier}|\p{Emoji_Component}|‍|️|\s)+$/u.test(t)) return false;
    if (/^[\d\s#*]+$/.test(t)) return false;
    const n = window.Intl && Intl.Segmenter
      ? [...new Intl.Segmenter("fr", { granularity: "grapheme" }).segment(t.replace(/\s/g, ""))].length
      : [...t.replace(/\s/g, "")].length;
    return n >= 1 && n <= 3;
  }

  // ---------- Réseau ----------
  async function sb(method, path, body) { return A().authed(method, path, body); }

  const COLS = "id,sender_id,kind,body,media,meta,created_at,read_at";

  // La colonne deleted_at n'existe qu'après supprimer.sql : sans elle, on lit les messages comme avant.
  let colsFull = true;
  async function qm(rest) {
    try {
      return (await sb("GET", "/rest/v1/messages?select=" + COLS + (colsFull ? ",deleted_at" : "") + rest)) || [];
    } catch (e) {
      if (colsFull && /HTTP 400/.test(String(e && e.message))) {
        colsFull = false;
        return (await sb("GET", "/rest/v1/messages?select=" + COLS + rest)) || [];
      }
      throw e;
    }
  }
  const fetchLatest = () => qm("&order=id.desc&limit=" + PAGE);
  const fetchOlder = (beforeId) => qm("&id=lt." + beforeId + "&order=id.desc&limit=" + PAGE);
  const fetchNew = (afterId) => qm("&id=gt." + afterId + "&order=id.asc&limit=100");

  async function uploadFile(blob, ext, mime) {
    const name = (crypto.randomUUID ? crypto.randomUUID().replace(/-/g, "") : String(Date.now()) + Math.random().toString(36).slice(2)) + "." + ext;
    const path = me + "/" + name;
    const cfg = A().CFG;
    const attempt = async (force) => {
      const s = await A().session(force);
      return fetch(cfg.SUPABASE_URL + "/storage/v1/object/chat/" + path, {
        method: "POST",
        headers: { apikey: cfg.SUPABASE_ANON_KEY, Authorization: "Bearer " + s.access, "Content-Type": mime, "x-upsert": "false" },
        body: blob,
      });
    };
    let res = await attempt(false);
    if (res.status === 401) res = await attempt(true);
    if (!res.ok) throw new Error("upload " + res.status);
    return path;
  }

  // Les stickers animés (a01_…) sont des images WebP qui bougent d'elles-mêmes ; les anciens (s01_…) sont des PNG animés par le code.
  const stkAnim = (id) => /^a\d/.test(String(id || ""));
  const stkSrc = (id) => "stickers/" + id + (stkAnim(id) ? ".webp" : ".png");
  function stkClass(id) {
    if (stkAnim(id)) return "";
    const n = parseInt(String(id || "").slice(1, 3), 10) || 0;
    return "stk" + (n % 5);
  }

  // ---------- Fond d'écran de la conversation (local à cet appareil) ----------
  const WALL_PRESETS = [
    "linear-gradient(160deg,#3A1424,#6B1E3F)", "linear-gradient(160deg,#241B45,#3F2A7A)",
    "linear-gradient(160deg,#10263A,#1D4A6B)", "linear-gradient(160deg,#10301F,#1F5A3A)",
    "linear-gradient(160deg,#3A2410,#6B4218)", "linear-gradient(160deg,#0E0E12,#25252E)",
  ];
  let curWall = null, wallLock = 0;
  function applyWall() {
    const w = curWall || "";
    if (w.startsWith("photo:")) host.style.background = 'center / cover no-repeat url("' + mediaUrl(w.slice(6)) + '")';
    else if (w.startsWith("preset:") && WALL_PRESETS[Number(w.slice(7))]) host.style.background = WALL_PRESETS[Number(w.slice(7))];
    else host.style.background = "";
  }
  // Valeur reçue du serveur (le fond est commun aux deux partenaires).
  function setWall(v) {
    if (Date.now() < wallLock) return;
    v = v || null;
    if (v === curWall) return;
    curWall = v;
    if (built) applyWall();
  }
  // Changement fait ici : on l'applique tout de suite, puis on l'envoie au serveur pour l'autre personne.
  async function chooseWall(v) {
    const prev = curWall;
    curWall = v; wallLock = Date.now() + 6000; applyWall();
    $("ch-wp").hidden = true;
    try {
      const r = await A().authed("POST", "/rest/v1/rpc/set_wallpaper", { p_value: v });
      if (!r || r.status !== "ok") throw new Error("wall");
    } catch (e) {
      curWall = prev; wallLock = 0; applyWall();
      toast("Impossible de changer le fond pour le moment.");
    }
  }
  async function onWpFile(ev) {
    const f = ev.target.files && ev.target.files[0];
    ev.target.value = "";
    if (!f) return;
    try {
      const img = await resizeImage(f, 2000, 0.92);
      const path = await uploadFile(img.blob, "jpg", "image/jpeg");
      await chooseWall("photo:" + path);
    } catch (e) {
      toast("Impossible de lire cette photo.");
    }
  }

  const ICONS = {"camera": ["M12,8.8a3.2,3.2 0 1,0 0.001,0z", "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z"], "mic": ["M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"], "send": ["M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z"], "settings": ["M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94 0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6 3.6,1.62 3.6,3.6 -1.62,3.6 -3.6,3.6z"], "add": ["M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"], "close": ["M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"], "trash": ["M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z"], "play": ["M8,5v14l11,-7z"], "videocam": ["M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z"], "image": ["M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"], "chevron": ["M15.41,7.41L14,6l-6,6 6,6 1.41,-1.41L10.83,12z"]};
  const ic = (n, s) => '<svg class="ic" viewBox="0 0 24 24" width="' + (s || 22) + '" height="' + (s || 22) + '" fill="currentColor" aria-hidden="true">' + ICONS[n].map((d) => '<path d="' + d + '"/>').join("") + "</svg>";

  // ---------- Construction de l'écran ----------
  function build() {
    if (built) return;
    built = true;
    host.innerHTML = `
      <div class="ch-head">
        <button class="ch-icon ch-back" data-c="back" aria-label="Retour">${ic("chevron", 30)}</button>
        <div class="ch-who">
          <div class="ch-avatar">💗</div>
          <div class="ch-title"><b id="ch-name"></b><small id="ch-sub">Ton partenaire ›</small></div>
        </div>
        <div class="ch-right">
          <button class="ch-icon ch-callbtn" data-c="callAudio" aria-label="Appel audio"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/></svg></button>
          <button class="ch-icon ch-callbtn" data-c="callVideo" aria-label="Appel vidéo"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg></button>
          <button class="ch-icon ch-menu" data-c="wpOpen" aria-label="Réglages">${ic("settings", 22)}</button>
        </div>
      </div>
      <div class="ch-selbar" id="ch-selbar" hidden>
        <button class="ch-icon" data-c="selClose" aria-label="Annuler la sélection">${ic("close", 22)}</button>
        <b id="ch-selcount"></b>
        <button class="ch-icon ch-seldel" data-c="selDel" aria-label="Supprimer">${ic("trash", 22)}</button>
      </div>
      <div class="ch-list" id="ch-list"></div>
      <button class="ch-down" id="ch-down" data-c="down" hidden>⌄<span id="ch-downn"></span></button>
      <div class="ch-toast" id="ch-toast" hidden></div>
      <div class="ch-bottom" id="ch-bottom">
      <div class="ch-typing" id="ch-typing" hidden><span><i></i><i></i><i></i></span></div>
      <div class="ch-replybar" id="ch-replybar" hidden>
        <div class="ch-rbody"><b id="ch-rname"></b><span id="ch-rtext"></span></div>
        <button class="ch-icon" data-c="replyCancel" aria-label="Annuler la réponse">${ic("close", 20)}</button>
      </div>
      <div class="ch-panel" id="ch-panel" hidden></div>
      <div class="ch-bar" id="ch-bar">
        <button class="ch-plus" data-c="emoji" id="ch-emojibtn" aria-label="Stickers et GIF">${ic("add", 28)}</button>
        <div class="ch-pill">
          <textarea id="ch-input" rows="1" placeholder="Message" autocomplete="on" autocapitalize="sentences" spellcheck="true" enterkeyhint="send"></textarea>
          <button class="ch-cam" data-c="attach" aria-label="Appareil photo">${ic("camera", 24)}</button>
          <button class="ch-send" data-c="send" id="ch-send" aria-label="Envoyer">${ic("mic", 22)}</button>
        </div>
      </div>
      <div class="ch-rec" id="ch-rec" hidden>
        <button class="ch-icon" data-c="recCancel" aria-label="Annuler">${ic("trash", 24)}</button>
        <span class="ch-dot"></span><span id="ch-rect">0:00</span>
        <span class="ch-recgrow">Enregistrement…</span>
        <button class="ch-send on" data-c="recSend" aria-label="Envoyer le vocal">${ic("send", 22)}</button>
      </div>
      </div>
      <div class="ch-pv" id="ch-pv" hidden>
        <img id="ch-pvimg" alt="">
        <div class="ch-pvbar">
          <input id="ch-pvcap" placeholder="Ajouter une légende…" maxlength="300">
          <button class="ch-send on" data-c="pvSend" aria-label="Envoyer la photo">${ic("send", 22)}</button>
        </div>
        <div class="ch-pvopts">
          <button class="ch-pvopt" id="ch-pvonce" data-c="pvOnce">🔥 Vue unique</button>
          <button class="ch-pvopt" id="ch-pvsched" data-c="pvSched">🕐 Programmer</button>
        </div>
        <div class="ch-pvact">
          <button class="ch-pvbtn" data-c="pvRetake">↻ Reprendre</button>
          <button class="ch-pvbtn ok" data-c="pvSend">✓ Utiliser cette photo</button>
        </div>
        <button class="ch-pvx" data-c="pvCancel" aria-label="Fermer">${ic("close", 22)}</button>
      </div>
      <div class="ch-view" id="ch-view" hidden data-c="viewClose"><img id="ch-viewimg" alt=""></div>
      <input type="file" id="ch-file" accept="image/*,video/*" hidden>
      <input type="file" id="ch-filep" accept="image/*" capture="environment" hidden>
      <input type="file" id="ch-filev" accept="video/*" capture="environment" hidden>
      <div class="ch-wp" id="ch-mm" hidden data-c="mClose">
        <div class="ch-wpbox" id="ch-mmbox"></div>
      </div>
      <div class="ch-wp" id="ch-cam" hidden data-c="camClose">
        <div class="ch-wpbox">
          <b>Appareil photo</b>
          <button class="ch-wpbtn ch-opt" data-c="camPhoto">${ic("camera", 22)} Prendre une photo</button>
          <button class="ch-wpbtn ch-opt" data-c="camVideo">${ic("videocam", 22)} Filmer une vidéo</button>
          <button class="ch-wpbtn ch-opt" data-c="camGallery">${ic("image", 22)} Choisir dans la galerie</button>
          <button class="ch-wpbtn ch-wpclose" data-c="camClose">Annuler</button>
        </div>
      </div>
      <input type="file" id="ch-wpfile" accept="image/*" hidden>
      <div class="ch-wp" id="ch-wp" hidden data-c="wpClose">
        <div class="ch-wpbox">
          <b>Fond d'écran</b>
          <small>Il change chez vous deux. Couleurs :</small>
          <div class="ch-sws">${WALL_PRESETS.map((g, i) => '<button class="ch-sw" data-c="wpPreset" data-i="' + i + '" style="background:' + g + '" aria-label="Couleur ' + (i + 1) + '"></button>').join("")}</div>
          <button class="ch-wpbtn" data-c="wpGallery">📷 Choisir une photo dans la galerie</button>
          <button class="ch-wpbtn" data-c="wpReset">↺ Fond d'origine</button>
          <button class="ch-wpbtn ch-wpclose" data-c="wpClose">Fermer</button>
        </div>
      </div>`;
    $("ch-wpfile").addEventListener("change", onWpFile);
    applyWall();

    host.addEventListener("click", onClick);
    $("ch-input").addEventListener("input", onInput);
    $("ch-input").addEventListener("keydown", (e) => {
      if (fine && e.key === "Enter" && !e.shiftKey) { e.preventDefault(); sendText(); }
    });
    (() => {
      const b = $("ch-send");
      let t = null, fired = false;
      const clear = () => { clearTimeout(t); t = null; };
      b.addEventListener("pointerdown", () => { fired = false; clear(); if (b.dataset.c !== "send") return; t = setTimeout(() => { fired = true; sendOptions(); }, 450); });
      ["pointerup", "pointerleave", "pointercancel"].forEach((e) => b.addEventListener(e, clear));
      b.addEventListener("click", (ev) => { if (fired) { ev.stopPropagation(); ev.preventDefault(); fired = false; } }, true);
      b.addEventListener("contextmenu", (ev) => ev.preventDefault());
    })();
    $("ch-input").addEventListener("focus", () => { if (panelTab) closePanel(); setTimeout(() => scrollBottom(true), 250); });
    $("ch-file").addEventListener("change", onFile);
    $("ch-filep").addEventListener("change", onFile);
    $("ch-filev").addEventListener("change", onFile);
    // La liste de messages occupe tout l'écran : en haut et en bas, elle passe sous l'en-tête et sous la barre de saisie.
    if (window.ResizeObserver) {
      new ResizeObserver(() => {
        const h = $("ch-bottom").offsetHeight;
        const near = nearBottomFlag;
        host.style.setProperty("--bot", h + "px");
        if (near && opened) scrollBottom(true);
      }).observe($("ch-bottom"));
    }
    wireSwipe($("ch-list"));
    wireMenu($("ch-list"));
    ["touchstart", "click"].forEach((ev) => document.addEventListener(ev, unlockAudio, { once: true, passive: true }));
    $("ch-list").addEventListener("scroll", onScroll, { passive: true });
    const vv = window.visualViewport;
    if (vv) {
      const fit = () => {
        if (!opened) return;
        host.style.height = vv.height + "px";
        host.style.top = vv.offsetTop + "px";
        if (nearBottomFlag) scrollBottom(true);
      };
      vv.addEventListener("resize", fit);
      vv.addEventListener("scroll", fit);
      host._fit = fit;
    }
    document.addEventListener("visibilitychange", () => { if (opened && document.visibilityState === "visible") poll(); });
  }

  // ---------- Ouverture / fermeture ----------
  async function open(name) {
    build();
    partner = name || partner;
    $("ch-name").textContent = "@" + partner;
    if (opened) return;
    opened = true;
    host.hidden = false;
    document.body.classList.add("chat-open");
    if (host._fit) host._fit(); else host.style.height = "100dvh";
    const s = await A().session(false);
    if (me !== s.uid) { me = s.uid; reset(); }
    if (!msgs.length) {
      $("ch-list").innerHTML = '<div class="ch-empty"><div class="spin"></div></div>';
      try {
        const latest = await fetchLatest();
        latest.reverse().forEach(addConfirmed);
        noMore = latest.length < PAGE;
        renderAll();
        scrollBottom(true);
      } catch (e) {
        $("ch-list").innerHTML = '<div class="ch-empty">Impossible de charger la conversation.<br>Vérifie que le chat est bien installé côté serveur.</div>';
      }
    } else {
      renderAll();
      scrollBottom(true);
    }
    restoreQ();
    markRead();
    startPolling();
    if (window.TMM_GAMES) window.TMM_GAMES.attach(partner);
  }

  function close() {
    if (!opened) return;
    opened = false;
    if (rec) stopRec(false);
    closePanel();
    pauseAllAudio();
    host.hidden = true;
    if (window.TMM_GAMES) window.TMM_GAMES.detach();
    document.body.classList.remove("chat-open");
    clearInterval(pollTimer); pollTimer = null;
    clearInterval(typTimer); typTimer = null;
    setTyping(false);
  }

  function reset() {
    msgs = []; byId.clear(); unreadOwn.clear(); pendings = []; noMore = false; clearReply();
  }

  function startPolling() {
    clearInterval(pollTimer);
    pollTimer = setInterval(poll, 2500);
    clearInterval(typTimer);
    typTimer = setInterval(pollTyping, 1500);
  }

  // ---------- Données ----------
  function addConfirmed(m) {
    if (byId.has(m.id)) return false;
    byId.set(m.id, m);
    msgs.push(m);
    if (m.sender_id === me && !m.read_at) unreadOwn.add(m.id);
    return true;
  }

  async function poll() {
    if (!opened || polling || document.visibilityState !== "visible") return;
    polling = true;
    try {
      const last = msgs.length ? msgs[msgs.length - 1].id : 0;
      const fresh = await fetchNew(last);
      let incoming = false;
      const added = [];
      fresh.forEach((m) => {
        if (addConfirmed(m)) {
          added.push(m);
          if (m.sender_id !== me) {
            incoming = true; setTyping(false); if (m.kind !== "game") bubbleSound(false);
            if (isLove(m)) love();
            if (m.kind === "sticker") burst(m.body);
          }
          else dropMatchingPending(m);
        }
      });
      if (added.length) { appendMessages(added, incoming); if (window.TMM_GAMES) window.TMM_GAMES.onMessages(added); }
      await syncDeleted();
      await syncEdited();
      await syncOnce();
      if (window.TMM_X) window.TMM_X.tick();
      if (unreadOwn.size) await refreshReadStatus();
      if (incoming || hasUnreadIncoming()) markRead();
    } catch (e) { /* hors-ligne : on réessaie au prochain tour */ }
    polling = false;
  }

  const hasUnreadIncoming = () => msgs.some((m) => m.sender_id !== me && !m.read_at);

  async function markRead() {
    try {
      await A().authed("POST", "/rest/v1/rpc/mark_read", {});
      msgs.forEach((m) => { if (m.sender_id !== me && !m.read_at) m.read_at = new Date().toISOString(); });
      A().setUnread(0);
    } catch (e) { /* ignoré */ }
  }

  async function refreshReadStatus() {
    const ids = [...unreadOwn].slice(0, 100);
    if (!ids.length) return;
    const rows = await sb("GET", "/rest/v1/messages?select=id&read_at=not.is.null&id=in.(" + ids.join(",") + ")");
    (rows || []).forEach((r) => {
      unreadOwn.delete(r.id);
      const m = byId.get(r.id);
      if (m) m.read_at = m.read_at || new Date().toISOString();
      const row = host.querySelector('.ch-row[data-id="' + r.id + '"] .ch-tick');
      if (row) { row.textContent = "✓✓"; row.classList.add("read"); }
    });
  }

  function dropMatchingPending(m) {
    const i = pendings.findIndex((p) => p.kind === m.kind && (p.body || null) === (m.body || null) &&
      (p.kind === "gif" ? p.media === m.media : true));
    if (i >= 0) {
      const p = pendings.splice(i, 1)[0];
      const el = host.querySelector('.ch-row[data-tmp="' + p.tmp + '"]');
      if (el) el.remove();
    }
  }

  // ---------- Affichage ----------
  const list = () => $("ch-list");
  let nearBottomFlag = true;

  function nearBottom() {
    const l = list();
    return l.scrollHeight - l.scrollTop - l.clientHeight < 140;
  }
  function scrollBottom(force) {
    const l = list();
    if (force || nearBottomFlag) l.scrollTop = l.scrollHeight;
    nearBottomFlag = true;
    $("ch-down").hidden = true;
  }
  let missedCount = 0;
  function onScroll() {
    const l = list();
    nearBottomFlag = nearBottom();
    if (nearBottomFlag) { $("ch-down").hidden = true; missedCount = 0; $("ch-downn").textContent = ""; }
    else $("ch-down").hidden = false;
    if (l.scrollTop < 80 && !noMore && !loadingOlder && msgs.length) loadOlder();
  }

  async function loadOlder() {
    loadingOlder = true;
    try {
      const older = await fetchOlder(msgs[0].id);
      if (older.length < PAGE) noMore = true;
      const l = list();
      const before = l.scrollHeight;
      older.forEach((m) => {
        if (!byId.has(m.id)) {
          byId.set(m.id, m);
          msgs.unshift(m);
          if (m.sender_id === me && !m.read_at) unreadOwn.add(m.id);
        }
      });
      msgs.sort((a, b) => a.id - b.id);
      renderAll();
      l.scrollTop = l.scrollHeight - before;
    } catch (e) { /* ignoré */ }
    loadingOlder = false;
  }

  function renderAll() {
    const l = list();
    pauseAllAudio();
    l.innerHTML = "";
    let prev = null;
    const frag = document.createDocumentFragment();
    msgs.concat(pendings).forEach((m) => {
      if (!prev || dayKey(prev.created_at) !== dayKey(m.created_at)) frag.appendChild(daySep(m.created_at));
      frag.appendChild(rowEl(m));
      prev = m;
    });
    if (!msgs.length && !pendings.length) {
      const e = document.createElement("div");
      e.className = "ch-empty";
      e.innerHTML = "Dis quelque chose de gentil à @" + esc(partner) + " 💗";
      frag.appendChild(e);
    }
    l.appendChild(frag);
  }

  function daySep(d) {
    const s = document.createElement("div");
    s.className = "ch-day";
    s.dataset.day = dayKey(d);
    s.innerHTML = "<span>" + esc(dayLabel(d)) + "</span>";
    return s;
  }

  function lastShownDay() {
    const rows = list().querySelectorAll(".ch-row");
    const last = rows[rows.length - 1];
    return last ? last.dataset.day : null;
  }

  function appendMessages(arr, incoming) {
    const l = list();
    const stick = nearBottomFlag || arr.some((m) => m.sender_id === me);
    const empty = l.querySelector(".ch-empty");
    if (empty) empty.remove();
    arr.forEach((m) => {
      if (lastShownDay() !== dayKey(m.created_at)) l.appendChild(daySep(m.created_at));
      l.appendChild(rowEl(m));
    });
    if (stick) scrollBottom(true);
    else if (incoming) {
      missedCount += arr.filter((m) => m.sender_id !== me).length;
      $("ch-downn").textContent = missedCount ? " " + missedCount : "";
      $("ch-down").hidden = false;
    }
  }

  function bubbleInner(m) {
    const t = '<span class="ch-meta">' + (m.kind === "text" && m.meta && m.meta.edited && !m.deleted_at ? '<i class="ch-edited">modifié</i> · ' : "") + hhmm(m.created_at) + tickHtml(m) + "</span>";
    if (m.deleted_at) return { cls: "deleted", html: '<div class="ch-text ch-del">🚫 Message supprimé</div>' + t };
    if (m.meta && m.meta.once && window.TMM_X) return { cls: "once", html: window.TMM_X.bubble(m, m.sender_id === me) + t };
    switch (m.kind) {
      case "text": {
        if (isBigEmoji(m.body)) return { cls: "big", html: '<div class="ch-bigemoji">' + esc(m.body) + "</div>" + t };
        return { cls: "", html: '<div class="ch-text">' + linkify(m.body || "") + "</div>" + t };
      }
      case "sticker": {
        const ok = stickers.some((s) => s.id === m.body) || /^s\d\d_/.test(m.body || "");
        return {
          cls: "sticker",
          html: ok ? '<img class="ch-sticker ' + stkClass(m.body) + '" src="' + esc(stkSrc(m.body)) + '" alt="sticker" onerror="this.replaceWith(document.createTextNode(\'💗\'))">' + t
            : '<div class="ch-bigemoji">💗</div>' + t,
        };
      }
      case "gif": {
        const w = (m.meta && m.meta.w) || 200, h = (m.meta && m.meta.h) || 200;
        return { cls: "media", html: '<img class="ch-gif" style="aspect-ratio:' + w + "/" + h + '" src="' + esc(m.media) + '" alt="GIF" loading="lazy"><span class="ch-gifbadge">GIF</span>' + t };
      }
      case "image": {
        const w = (m.meta && m.meta.w) || 4, h = (m.meta && m.meta.h) || 3;
        const src = m.localUrl || mediaUrl(m.media);
        return {
          cls: "media",
          html: '<img class="ch-photo" data-c="view" style="aspect-ratio:' + w + "/" + h + '" src="' + esc(src) + '" alt="photo" loading="lazy">' +
            (m.body ? '<div class="ch-text cap">' + linkify(m.body) + "</div>" : "") + t,
        };
      }
      case "video": {
        const w = (m.meta && m.meta.w) || 16, h = (m.meta && m.meta.h) || 9;
        const src = m.localUrl || mediaUrl(m.media);
        return {
          cls: "media",
          html: '<video class="ch-photo ch-video" style="aspect-ratio:' + w + "/" + h + '" src="' + esc(src) + '#t=0.1" controls playsinline preload="metadata"></video>' + t,
        };
      }
      case "voice": {
        const src = m.localUrl || mediaUrl(m.media);
        const d = (m.meta && m.meta.d) || 0;
        return {
          cls: "voice",
          html: '<div class="ch-voice"><button class="ch-play" data-c="play" aria-label="Lire">▶</button>' +
            '<div class="ch-wave" data-c="seek">' + waveHtml(m.media || m.tmp || "x") + "</div>" +
            '<span class="ch-dur">' + mmss(d) + '</span><audio preload="none" src="' + esc(src) + '" data-d="' + d + '"></audio></div>' + t,
        };
      }
      default: return { cls: "", html: "" };
    }
  }

  function waveHtml(seed) {
    let h = 0;
    for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) >>> 0;
    let out = "";
    for (let i = 0; i < 30; i++) {
      h = (h * 1103515245 + 12345) >>> 0;
      const v = 22 + ((h >> 8) % 78);
      out += '<i style="height:' + v + '%"></i>';
    }
    return out;
  }

  function tickHtml(m) {
    if (m.sender_id !== me) return "";
    if (m.pending) return ' <span class="ch-tick">🕓</span>';
    return ' <span class="ch-tick' + (m.read_at ? " read" : "") + '">' + (m.read_at ? "✓✓" : "✓") + "</span>";
  }

  function rowEl(m) {
    if (m.kind === "game" && window.TMM_GAMES) {
      return window.TMM_GAMES.row(m, { me, partner, msgs: () => msgs, dayKey, hhmm });
    }
    const mine = m.sender_id === me;
    const b = bubbleInner(m);
    const row = document.createElement("div");
    row.className = "ch-row " + (mine ? "mine" : "theirs") + (m.pending ? " pending" : "");
    row.dataset.day = dayKey(m.created_at);
    if (m.pending) row.dataset.tmp = m.tmp; else row.dataset.id = m.id;
    row.innerHTML = '<div class="ch-bubble ' + b.cls + '">' + (m.deleted_at ? "" : quoteHtml(m)) + b.html + "</div>";
    wireAudio(row);
    row.querySelectorAll("img").forEach((img) => img.addEventListener("load", () => { if (nearBottomFlag) scrollBottom(true); }));
    return row;
  }

  // ---------- Vocaux : lecture ----------
  let playing = null;
  function pauseAllAudio() {
    host.querySelectorAll("audio").forEach((a) => { try { a.pause(); } catch (e) { /* ignoré */ } });
    host.querySelectorAll(".ch-play").forEach((b) => { b.textContent = "▶"; });
    playing = null;
  }
  function paint(row, ratio) {
    const bars = row.querySelectorAll(".ch-wave i");
    const n = Math.round(bars.length * ratio);
    bars.forEach((b, i) => b.classList.toggle("on", i < n));
  }
  function wireAudio(row) {
    const a = row.querySelector("audio");
    if (!a) return;
    const btn = row.querySelector(".ch-play");
    const dur = row.querySelector(".ch-dur");
    const total = () => (isFinite(a.duration) && a.duration > 0 ? a.duration : Number(a.dataset.d) || 0);
    a.addEventListener("timeupdate", () => {
      const t = total();
      paint(row, t ? a.currentTime / t : 0);
      dur.textContent = mmss(a.paused && a.currentTime === 0 ? t : a.currentTime);
    });
    a.addEventListener("ended", () => { btn.textContent = "▶"; paint(row, 0); dur.textContent = mmss(total()); playing = null; });
    a.addEventListener("pause", () => { btn.textContent = "▶"; });
    a.addEventListener("play", () => { btn.textContent = "❚❚"; });
    a.addEventListener("error", () => { btn.textContent = "⚠"; });
  }
  function togglePlay(row) {
    const a = row.querySelector("audio");
    if (!a) return;
    if (a.paused) {
      if (playing && playing !== a) playing.pause();
      playing = a;
      a.play().catch(() => toast("Impossible de lire ce vocal sur cet appareil."));
    } else a.pause();
  }
  function seek(row, ev) {
    const a = row.querySelector("audio");
    const w = row.querySelector(".ch-wave");
    if (!a || !w) return;
    const r = w.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width));
    const t = isFinite(a.duration) && a.duration > 0 ? a.duration : Number(a.dataset.d) || 0;
    if (!t) { togglePlay(row); return; }
    const go = () => { try { a.currentTime = ratio * t; } catch (e) { /* ignoré */ } };
    if (a.readyState >= 1) go(); else { a.addEventListener("loadedmetadata", go, { once: true }); a.load(); }
    if (a.paused) togglePlay(row);
  }

  // ---------- Petit son à l'envoi (généré, aucun fichier) ----------
  let actx = null;
  function unlockAudio() {
    try {
      if (!actx) { const AC = window.AudioContext || window.webkitAudioContext; if (AC) actx = new AC(); }
      if (actx && actx.state === "suspended") actx.resume();
    } catch (e) { /* ignoré */ }
  }
  // Petite « bulle » : glissando court et rond. up = monte (envoi), sinon descend (réception).
  function bubbleSound(up) {
    try {
      unlockAudio();
      if (!actx) return;
      const t0 = actx.currentTime;
      const o = actx.createOscillator(), g = actx.createGain();
      o.type = "sine";
      o.frequency.setValueAtTime(up ? 500 : 1400, t0);
      o.frequency.setTargetAtTime(up ? 1400 : 500, t0, 1 / 30);
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.linearRampToValueAtTime(0.35, t0 + 0.006);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.22);
      o.connect(g); g.connect(actx.destination);
      o.start(t0); o.stop(t0 + 0.24);
    } catch (e) { /* ignoré */ }
  }
  // Son d'envoi : « pop » doux et grave (≈ 0,3 s) qui monte légèrement, avec un petit souffle au départ.
  function sentSound() {
    try {
      unlockAudio();
      if (!actx) return;
      const t0 = actx.currentTime + 0.01, D = 0.34;
      const env = new Float32Array(96);
      for (let i = 0; i < env.length; i++) { const x = i / (env.length - 1); env[i] = Math.pow(Math.sin(Math.PI * Math.pow(x, 0.62)), 1.6); }
      const out = actx.createGain(); out.gain.value = 0.55; out.connect(actx.destination);
      const tone = (mul, vol) => {
        const o = actx.createOscillator(), g = actx.createGain();
        o.type = "sine";
        o.frequency.setValueAtTime(330 * mul, t0); o.frequency.linearRampToValueAtTime(480 * mul, t0 + 0.24);
        g.gain.setValueAtTime(0, t0); g.gain.setValueCurveAtTime(env, t0, D);
        const v = actx.createGain(); v.gain.value = vol;
        o.connect(g); g.connect(v); v.connect(out); o.start(t0); o.stop(t0 + D + 0.02);
      };
      tone(1, 1); tone(2, 0.12);
      const nb = actx.createBuffer(1, Math.floor(actx.sampleRate * 0.14), actx.sampleRate), d = nb.getChannelData(0);
      for (let i = 0; i < d.length; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / d.length, 2);
      const ns = actx.createBufferSource(); ns.buffer = nb;
      const lp = actx.createBiquadFilter(); lp.type = "lowpass"; lp.frequency.value = 1600;
      const ng = actx.createGain(); ng.gain.value = 0.12;
      ns.connect(lp); lp.connect(ng); ng.connect(out); ns.start(t0);
    } catch (e) { /* ignoré */ }
  }
  const popSound = () => {};

  // ---------- Envoi ----------
  function addPending(p) {
    p.pending = true;
    if (replyTo) { p.meta = Object.assign({}, p.meta || {}, { reply: replyTo }); clearReply(); }
    p.tmp = "t" + (++tmpCount);
    p.sender_id = me;
    p.created_at = new Date().toISOString();
    pendings.push(p);
    // Les messages déjà à l'écran : on mémorise leur place pour les faire monter en douceur.
    const l = list(), lr = l.getBoundingClientRect();
    const before = [...l.querySelectorAll(".ch-row")].map((r) => [r, r.getBoundingClientRect().top]).filter((x) => x[1] > lr.top - 40 && x[1] < lr.bottom);
    const inp = $("ch-input"), ir = inp ? inp.getBoundingClientRect() : null;
    appendMessages([p], false);
    scrollBottom(true);
    sentSound();
    try { sentAnim(p, before, ir); } catch (e) { /* ignoré */ }
    return p;
  }
  // Comme sur iOS : la bulle part du champ de texte, petite, et rejoint sa place en grossissant avec un léger rebond.
  function sentAnim(p, before, ir) {
    if (window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
    const EASE = "cubic-bezier(.2,1.2,.35,1)", T = 420;
    const el = host.querySelector('.ch-row[data-tmp="' + p.tmp + '"]'), b = el && el.querySelector(".ch-bubble");
    if (b && ir) {
      const br = b.getBoundingClientRect();
      const dx = (ir.left + 28) - (br.left + br.width / 2), dy = (ir.top + ir.height / 2) - (br.top + br.height / 2);
      b.animate([{ transform: "translate(" + dx + "px," + dy + "px) scale(.35)", opacity: .2, transformOrigin: "50% 50%" }, { transform: "translate(0,0) scale(1)", opacity: 1, transformOrigin: "50% 50%" }], { duration: T, easing: EASE });
    }
    before.forEach(([r, top]) => {
      if (r === el) return;
      const d = top - r.getBoundingClientRect().top;
      if (Math.abs(d) > 1) r.animate([{ transform: "translateY(" + d + "px)" }, { transform: "translateY(0)" }], { duration: T, easing: EASE });
    });
    const bar = $("ch-bar");
    if (bar) { bar.classList.remove("sendflash"); void bar.offsetWidth; bar.classList.add("sendflash"); setTimeout(() => bar.classList.remove("sendflash"), 520); }
  }
  function removePending(p) {
    pendings = pendings.filter((x) => x !== p);
    const el = host.querySelector('.ch-row[data-tmp="' + p.tmp + '"]');
    if (el) el.remove();
  }

  // Messages écrits sans réseau : gardés sur l'appareil, envoyés dès que la connexion revient.
  const QKEY = "tmm_outbox";
  const loadQ = () => { try { return JSON.parse(localStorage.getItem(QKEY) || "[]"); } catch (e) { return []; } };
  const saveQ = (q) => { try { localStorage.setItem(QKEY, JSON.stringify(q)); } catch (e) { /* ignoré */ } };
  const dropQ = (qid) => saveQ(loadQ().filter((x) => x.qid !== qid));
  const queueable = (payload) => payload.p_kind === "text" || payload.p_kind === "sticker" || payload.p_kind === "gif";

  function queueOffline(p, payload) {
    if (!queueable(payload)) {
      removePending(p);
      toast("Message non envoyé. Vérifie ta connexion.");
      return false;
    }
    p.qid = p.qid || ("q" + Date.now() + Math.random().toString(36).slice(2, 6));
    const q = loadQ();
    if (!q.some((x) => x.qid === p.qid)) {
      q.push({ qid: p.qid, payload, kind: p.kind, body: p.body || null, media: p.media || null, meta: p.meta || null, created_at: p.created_at });
      saveQ(q);
    }
    toast("Pas de réseau : ton message partira dès que la connexion reviendra. 🕓");
    return true;
  }

  // Remet dans le fil les messages en attente (appli fermée puis rouverte).
  function restoreQ() {
    loadQ().forEach((e) => {
      if (pendings.some((x) => x.qid === e.qid)) return;
      const p = { kind: e.kind, body: e.body, media: e.media, meta: e.meta, qid: e.qid, pending: true, sender_id: me,
        created_at: e.created_at, tmp: "t" + (++tmpCount) };
      pendings.push(p);
      appendMessages([p], false);
    });
  }

  let flushing = false;
  async function flushOutbox() {
    if (flushing) return;
    const q = loadQ();
    if (!q.length) return;
    flushing = true;
    try {
      for (const e of q) {
        let j;
        try { j = await A().authed("POST", "/rest/v1/rpc/send_message", e.payload); } catch (err) { break; }
        const p = pendings.find((x) => x.qid === e.qid);
        dropQ(e.qid);
        if (!j || j.status !== "ok") { if (p) removePending(p); continue; }
        if (p) settle(p, e.payload, j);
      }
    } finally { flushing = false; }
  }
  window.addEventListener("online", () => setTimeout(flushOutbox, 500));
  document.addEventListener("visibilitychange", () => { if (!document.hidden) flushOutbox(); });
  setInterval(flushOutbox, 6000);
  setTimeout(flushOutbox, 3000);

  // Le serveur a enregistré le message : on remplace la bulle « en cours » par la vraie.
  function settle(p, payload, j) {
    if (byId.has(j.id)) { removePending(p); return; }
    const m = { id: j.id, sender_id: me, kind: payload.p_kind, body: payload.p_body || null, media: payload.p_media || null,
      meta: payload.p_meta || null, created_at: p.created_at, read_at: null, localUrl: p.localUrl };
    if (p.once) { m.body = null; m.media = null; m.localUrl = null; m.meta = Object.assign({}, m.meta || {}, { once: true }); }
    pendings = pendings.filter((x) => x !== p);
    byId.set(m.id, m);
    msgs.push(m);
    msgs.sort((x, y) => x.id - y.id);
    unreadOwn.add(m.id);
    const el = host.querySelector('.ch-row[data-tmp="' + p.tmp + '"]');
    if (el) el.replaceWith(rowEl(m));
  }

  async function deliver(p, payload, opts) {
    const once = !!(opts && opts.once);
    stopTyping();
    if (p.meta && p.meta.reply) payload = Object.assign({}, payload, { p_meta: Object.assign({}, payload.p_meta || {}, { reply: p.meta.reply }) });
    try {
      const j = await A().authed("POST", "/rest/v1/rpc/" + (once ? "send_once" : "send_message"), payload);
      if (!j || j.status !== "ok") {
        removePending(p);
        if (j && j.status === "no_partner") { toast("Vous n'êtes plus partenaires."); A().reload(); close(); }
        else toast("Message non envoyé.");
        return false;
      }
      settle(p, payload, j);
      return true;
    } catch (e) {
      if (once) { removePending(p); toast("Message non envoyé. Vérifie ta connexion."); return false; }
      return queueOffline(p, payload);
    }
  }

  // ---------- « Est en train d'écrire… » ----------
  function setTyping(on) {
    const t = $("ch-typing"), s = $("ch-sub");
    if (!t || !s) return;
    const was = !t.hidden;
    t.hidden = !on;
    s.textContent = on ? "est en train d'écrire…" : "Ton partenaire ›";
    if (on && !was && nearBottomFlag) scrollBottom(true);
  }
  async function pollTyping() {
    if (!opened || document.visibilityState !== "visible") return;
    try {
      const r = await A().authed("POST", "/rest/v1/rpc/partner_typing", {});
      if (opened) setTyping(!!(r && r.typing));
    } catch (e) { /* le serveur n'a pas encore cette fonction : on ignore */ }
  }
  function sendTyping() {
    const now = Date.now();
    if (now - lastTypSent < 2500) return;
    lastTypSent = now;
    A().authed("POST", "/rest/v1/rpc/set_typing", {}).catch(() => {});
  }
  function stopTyping() {
    if (!lastTypSent) return;
    lastTypSent = 0;
    A().authed("POST", "/rest/v1/rpc/stop_typing", {}).catch(() => {});
  }

  // ---------- Répondre à un message ----------
  function snippet(m) {
    if (m.meta && m.meta.once) return "🔥 Message éphémère";
    switch (m.kind) {
      case "text": return (m.body || "").replace(/\s+/g, " ").slice(0, 80);
      case "sticker": return "💗 Sticker";
      case "gif": return "🎞️ GIF";
      case "image": return "📷 " + (m.body ? m.body.slice(0, 60) : "Photo");
      case "voice": return "🎤 Message vocal";
      case "video": return "🎥 Vidéo";
      case "game": return window.TMM_GAMES ? window.TMM_GAMES.snippet(m) : "🎮 Jeu";
      default: return "";
    }
  }
  // ---------- Modifier un message envoyé ----------
  let editingId = null;
  function startEdit(m) {
    if (!m || !m.id || m.kind !== "text" || (m.meta && m.meta.once)) return;
    clearReply();
    editingId = m.id;
    $("ch-rname").textContent = "✏️ Modifier le message";
    $("ch-rtext").textContent = snippet(m);
    $("ch-replybar").hidden = false;
    const inp = $("ch-input");
    inp.value = m.body || "";
    onInput();
    inp.focus();
  }
  function cancelEdit() {
    if (editingId === null) return;
    editingId = null;
    const inp = $("ch-input");
    inp.value = "";
    onInput();
    $("ch-replybar").hidden = true;
  }
  function applyEdit(m, body) {
    m.body = body;
    m.meta = Object.assign({}, m.meta || {}, { edited: true });
    const el = host.querySelector('.ch-row[data-id="' + m.id + '"]');
    if (el) el.replaceWith(rowEl(m));
  }
  async function submitEdit() {
    const inp = $("ch-input");
    const text = inp.value.trim();
    const m = byId.get(editingId);
    if (!m || !text) return;
    if (text === (m.body || "")) { cancelEdit(); return; }
    try {
      const r = await A().authed("POST", "/rest/v1/rpc/edit_message", { p_id: m.id, p_body: text });
      if (!r || r.status !== "ok") throw new Error(r && r.status);
      cancelEdit();
      applyEdit(m, text);
    } catch (e) {
      toast("Impossible de modifier. Vérifie ta connexion.");
    }
  }
  // La personne a modifié un message : on voit le nouveau texte ici aussi.
  // Le destinataire a ouvert un message éphémère : on le voit ici aussi.
  async function syncOnce() {
    if (!msgs.length) return;
    const waiting = msgs.filter((m) => m.meta && m.meta.once && !m.meta.opened && !m.deleted_at);
    if (!waiting.length) return;
    try {
      const rows = await sb("GET", "/rest/v1/messages?select=id&meta->>opened=eq.true&id=in.(" + waiting.slice(-100).map((m) => m.id).join(",") + ")");
      (rows || []).forEach((r) => markOpened(r.id));
    } catch (e) { /* ignoré */ }
  }
  function markOpened(id) {
    const m = byId.get(id);
    if (!m || (m.meta && m.meta.opened)) return;
    m.meta = Object.assign({}, m.meta || {}, { opened: true });
    const el = host.querySelector('.ch-row[data-id="' + id + '"]');
    if (el) el.replaceWith(rowEl(m));
  }
  async function syncEdited() {
    if (!msgs.length) return;
    try {
      const rows = await sb("GET", "/rest/v1/messages?select=id,body,meta&meta->>edited=eq.true&id=gte." + msgs[0].id);
      (rows || []).forEach((r) => {
        const m = byId.get(r.id);
        if (m && !m.deleted_at && (m.body !== r.body || !(m.meta && m.meta.edited))) applyEdit(m, r.body);
      });
    } catch (e) { /* ignoré */ }
  }
  function setReply(m) {
    if (!m || !m.id) return;
    if (editingId !== null) cancelEdit();
    replyTo = { id: m.id, by_me: m.sender_id === me, kind: m.kind, text: snippet(m) };
    $("ch-rname").textContent = replyTo.by_me ? "Toi" : "@" + partner;
    $("ch-rtext").textContent = replyTo.text;
    $("ch-replybar").hidden = false;
    $("ch-input").focus();
  }
  function clearReply() {
    replyTo = null;
    const b = $("ch-replybar");
    if (b) b.hidden = true;
  }
  function quoteHtml(m) {
    const r = m.meta && m.meta.reply;
    if (!r) return "";
    const byMe = m.sender_id === me ? !!r.by_me : !r.by_me;
    return '<div class="ch-quote" data-c="quote" data-id="' + esc(String(r.id)) + '"><b>' + (byMe ? "Toi" : "@" + esc(partner)) + "</b><span>" + esc(r.text || "") + "</span></div>";
  }
  function jumpTo(id) {
    const el = list().querySelector('.ch-row[data-id="' + id + '"]');
    if (!el) return;
    el.scrollIntoView({ block: "center", behavior: "smooth" });
    el.classList.add("flash");
    setTimeout(() => el.classList.remove("flash"), 1400);
  }
  // Glisser un message vers la droite = répondre (double-clic sur ordinateur).
  function wireSwipe(l) {
    let sx = 0, sy = 0, row = null, bub = null, dx = 0, lock = false;
    l.addEventListener("touchstart", (e) => {
      const t = e.touches[0];
      row = e.target.closest(".ch-row[data-id]");
      bub = row && row.querySelector(".ch-bubble");
      sx = t.clientX; sy = t.clientY; dx = 0; lock = false;
    }, { passive: true });
    l.addEventListener("touchmove", (e) => {
      if (!bub) return;
      const t = e.touches[0];
      const x = t.clientX - sx, y = t.clientY - sy;
      if (!lock && Math.abs(y) > Math.abs(x) && Math.abs(y) > 8) { bub = null; return; }
      if (x > 8) lock = true;
      if (!lock) return;
      dx = Math.max(0, Math.min(x, 90));
      bub.style.transition = "none";
      bub.style.transform = "translateX(" + dx + "px)";
    }, { passive: true });
    const end = () => {
      if (!bub) return;
      bub.style.transition = "transform .2s";
      bub.style.transform = "";
      if (dx > 55 && row) setReply(byId.get(Number(row.dataset.id)));
      bub = null; row = null; dx = 0;
    };
    l.addEventListener("touchend", end);
    l.addEventListener("touchcancel", end);
    l.addEventListener("dblclick", (e) => {
      const r = e.target.closest(".ch-row[data-id]");
      if (r) setReply(byId.get(Number(r.dataset.id)));
    });
  }
  // ---------- Menu d'un message : répondre, copier, supprimer ----------
  let menuId = null;
  function openMsgMenu(id) {
    const m = byId.get(id);
    if (!m) return;
    menuId = id;
    const mine = m.sender_id === me, gone = !!m.deleted_at;
    let h = "";
    if (!gone) h += '<button class="ch-wpbtn ch-opt" data-c="mReply">↩ Répondre</button>';
    if (m.kind === "game") { h += '<button class="ch-wpbtn ch-wpclose" data-c="mClose">Annuler</button>'; $("ch-mmbox").innerHTML = h; $("ch-mm").hidden = false; return; }
    if (!gone && m.kind === "text" && m.body) h += '<button class="ch-wpbtn ch-opt" data-c="mCopy">⧉ Copier</button>';
    if (mine && !gone && m.kind === "text" && !m.pending && !(m.meta && m.meta.once)) h += '<button class="ch-wpbtn ch-opt" data-c="mEdit">✏️ Modifier</button>';
    h += '<button class="ch-wpbtn ch-opt" data-c="mSel">☑ Sélectionner</button>';
    h += '<button class="ch-wpbtn ch-opt" data-c="mDelMe">' + ic("trash", 20) + " Supprimer pour moi</button>";
    if (mine && !gone) h += '<button class="ch-wpbtn ch-opt ch-danger" data-c="mDelAll">' + ic("trash", 20) + " Supprimer pour tout le monde</button>";
    h += '<button class="ch-wpbtn ch-wpclose" data-c="mClose">Annuler</button>';
    $("ch-mmbox").innerHTML = h;
    $("ch-mm").hidden = false;
  }
  function closeMsgMenu() { $("ch-mm").hidden = true; menuId = null; }

  // ---------- Sélection de plusieurs messages ----------
  const sel = new Set();
  function updateSel() {
    host.querySelectorAll(".ch-row.sel").forEach((r) => { if (!sel.has(Number(r.dataset.id))) r.classList.remove("sel"); });
    sel.forEach((id) => { const r = host.querySelector('.ch-row[data-id="' + id + '"]'); if (r) r.classList.add("sel"); });
    host.classList.toggle("selecting", sel.size > 0);
    const bar = $("ch-selbar");
    bar.hidden = sel.size === 0;
    $("ch-selcount").textContent = sel.size + " sélectionné" + (sel.size > 1 ? "s" : "");
  }
  function toggleSel(id) { if (sel.has(id)) sel.delete(id); else sel.add(id); updateSel(); }
  function clearSel() { sel.clear(); updateSel(); }
  function openSelDelete() {
    const chosen = [...sel].map((id) => byId.get(id)).filter(Boolean);
    if (!chosen.length) return;
    const canAll = chosen.every((m) => m.sender_id === me && !m.deleted_at);
    let h = "<b>Supprimer " + chosen.length + " message" + (chosen.length > 1 ? "s" : "") + " ?</b>";
    h += '<button class="ch-wpbtn ch-opt" data-c="sDelMe">' + ic("trash", 20) + " Supprimer pour moi</button>";
    if (canAll) h += '<button class="ch-wpbtn ch-opt ch-danger" data-c="sDelAll">' + ic("trash", 20) + " Supprimer pour tout le monde</button>";
    h += '<button class="ch-wpbtn ch-wpclose" data-c="mClose">Annuler</button>';
    $("ch-mmbox").innerHTML = h;
    $("ch-mm").hidden = false;
  }
  async function deleteSelected(all) {
    const ids = [...sel];
    clearSel();
    for (const id of ids) await deleteMsg(id, all);
  }
  function applyDeleted(m) {
    m.deleted_at = m.deleted_at || new Date().toISOString();
    m.body = null; m.media = null; m.meta = null; m.localUrl = null;
    unreadOwn.delete(m.id);
    const el = host.querySelector('.ch-row[data-id="' + m.id + '"]');
    if (el) el.replaceWith(rowEl(m));
  }
  async function deleteMsg(id, all) {
    const m = byId.get(id);
    if (!m) return;
    try {
      const r = await A().authed("POST", "/rest/v1/rpc/delete_message", { p_id: id, p_all: !!all });
      if (!r || r.status !== "ok") throw new Error(r && r.status);
      if (all) applyDeleted(m);
      else {
        msgs = msgs.filter((x) => x.id !== id);
        unreadOwn.delete(id);
        const el = host.querySelector('.ch-row[data-id="' + id + '"]');
        if (el) el.remove();
        if (!msgs.length && !pendings.length) renderAll();
      }
    } catch (e) {
      toast("Impossible de supprimer. Vérifie ta connexion.");
    }
  }
  // La personne a supprimé un message « pour tout le monde » : on le voit disparaître ici aussi.
  async function syncDeleted() {
    if (!colsFull || !msgs.length) return;
    try {
      const rows = await sb("GET", "/rest/v1/messages?select=id&deleted_at=not.is.null&id=gte." + msgs[0].id);
      (rows || []).forEach((r) => { const m = byId.get(r.id); if (m && !m.deleted_at) applyDeleted(m); });
    } catch (e) { /* ignoré */ }
  }
  // Appui long (téléphone) ou clic droit (ordinateur) sur un message.
  function wireMenu(l) {
    let timer = null, sx = 0, sy = 0, fired = false;
    const cancel = () => { clearTimeout(timer); timer = null; };
    l.addEventListener("touchstart", (e) => {
      const row = e.target.closest(".ch-row[data-id]");
      fired = false;
      if (!row) return;
      const t = e.touches[0]; sx = t.clientX; sy = t.clientY;
      cancel();
      timer = setTimeout(() => { fired = true; try { navigator.vibrate && navigator.vibrate(15); } catch (x) { /* ignoré */ } openMsgMenu(Number(row.dataset.id)); }, 450);
    }, { passive: true });
    l.addEventListener("touchmove", (e) => {
      const t = e.touches[0];
      if (Math.abs(t.clientX - sx) > 8 || Math.abs(t.clientY - sy) > 8) cancel();
    }, { passive: true });
    l.addEventListener("touchend", (e) => { cancel(); if (fired) { e.preventDefault(); fired = false; } });
    l.addEventListener("touchcancel", cancel);
    l.addEventListener("contextmenu", (e) => {
      const row = e.target.closest(".ch-row[data-id]");
      if (!row) return;
      e.preventDefault();
      openMsgMenu(Number(row.dataset.id));
    });
  }

  function burst(id) { try { window.TMM_API && window.TMM_API.burst && window.TMM_API.burst(id); } catch (e) { /* ignoré */ } }

  const LOVE_RE = /\bje\s*t['’ ]?\s*aime\b|\bjtm\b/i;
  const isLove = (m) => m.kind === "text" && m.body && LOVE_RE.test(m.body);
  function love() { try { window.TMM_API && window.TMM_API.love && window.TMM_API.love(); } catch (e) { /* ignoré */ } }

  async function sendText(o) {
    if (editingId !== null) { submitEdit(); return; }
    const inp = $("ch-input");
    const text = inp.value.trim();
    if (!text) return;
    inp.value = "";
    onInput();
    if (o && o.at) {
      const ok = await window.TMM_X.schedule({ p_kind: "text", p_body: text }, o);
      if (!ok && !inp.value) { inp.value = text; onInput(); }
      return;
    }
    if (o && o.once) {
      const p = addPending({ kind: "text", body: null, meta: { once: true } });
      p.once = true;
      const ok = await deliver(p, { p_kind: "text", p_body: text, p_media: null, p_meta: null }, { once: true });
      if (!ok && !inp.value) { inp.value = text; onInput(); }
      return;
    }
    if (LOVE_RE.test(text)) love();
    const p = addPending({ kind: "text", body: text });
    const ok = await deliver(p, { p_kind: "text", p_body: text, p_media: null, p_meta: null });
    if (!ok && !inp.value) { inp.value = text; onInput(); }
  }
  // Appui long sur « envoyer » : éphémère ou programmé.
  async function sendOptions() {
    if (editingId !== null || !$("ch-input").value.trim()) return;
    const o = await window.TMM_X.choose({ title: "Envoyer ce message", canOnce: true });
    if (o) sendText(o);
  }

  async function sendSticker(id) {
    closePanel();
    burst(id);
    const p = addPending({ kind: "sticker", body: id });
    await deliver(p, { p_kind: "sticker", p_body: id, p_media: null, p_meta: null });
  }

  async function sendGif(g) {
    closePanel();
    const p = addPending({ kind: "gif", media: g.url, meta: { w: g.w, h: g.h } });
    await deliver(p, { p_kind: "gif", p_body: null, p_media: g.url, p_meta: { w: g.w, h: g.h } });
  }

  async function sendImage(blob, w, h, caption, o) {
    if (o && o.at) {
      toast("Préparation…");
      try {
        const path = await uploadFile(blob, "jpg", "image/jpeg");
        await window.TMM_X.schedule({ p_kind: "image", p_body: caption || null, p_media: path, p_meta: { w, h } }, o);
      } catch (e) { toast("Photo non programmée. Vérifie ta connexion."); }
      return;
    }
    const once = !!(o && o.once);
    const local = URL.createObjectURL(blob);
    const p = once ? addPending({ kind: "image", body: null, meta: { once: true } }) : addPending({ kind: "image", body: caption || null, meta: { w, h }, localUrl: local });
    if (once) p.once = true;
    try {
      const path = await uploadFile(blob, "jpg", "image/jpeg");
      await deliver(p, { p_kind: "image", p_body: caption || null, p_media: path, p_meta: o && o.nowpic ? { w, h, nowpic: true } : { w, h } }, { once });
    } catch (e) {
      removePending(p);
      toast("Photo non envoyée. Vérifie ta connexion.");
    }
  }

  const MAX_VIDEO = 50 * 1024 * 1024;
  function videoMeta(file) {
    return new Promise((res) => {
      const v = document.createElement("video");
      const url = URL.createObjectURL(file);
      const done = (o) => { URL.revokeObjectURL(url); res(o); };
      const t = setTimeout(() => done({ w: 0, h: 0, d: 0 }), 4000);
      v.preload = "metadata"; v.muted = true; v.playsInline = true;
      v.onloadedmetadata = () => { clearTimeout(t); done({ w: v.videoWidth || 0, h: v.videoHeight || 0, d: Math.round(v.duration) || 0 }); };
      v.onerror = () => { clearTimeout(t); done({ w: 0, h: 0, d: 0 }); };
      v.src = url;
    });
  }
  async function sendVideo(file, o) {
    if (file.size > MAX_VIDEO) { toast("Vidéo trop lourde (50 Mo maximum)."); return; }
    const type = /quicktime/.test(file.type) ? "video/quicktime" : /webm/.test(file.type) ? "video/webm" : /3gpp/.test(file.type) ? "video/3gpp" : "video/mp4";
    const ext = type === "video/quicktime" ? "mov" : type === "video/webm" ? "webm" : type === "video/3gpp" ? "3gp" : "mp4";
    const m = await videoMeta(file);
    const meta = {};
    if (m.w && m.h) { meta.w = m.w; meta.h = m.h; }
    if (m.d) meta.d = m.d;
    if (o && o.at) {
      toast("Préparation…");
      try {
        const path = await uploadFile(file, ext, type);
        await window.TMM_X.schedule({ p_kind: "video", p_body: null, p_media: path, p_meta: Object.keys(meta).length ? meta : null }, o);
      } catch (e) { toast("Vidéo non programmée. Vérifie ta connexion."); }
      return;
    }
    const once = !!(o && o.once);
    const local = URL.createObjectURL(file);
    const p = once ? addPending({ kind: "video", meta: { once: true } }) : addPending({ kind: "video", meta, localUrl: local });
    if (once) p.once = true;
    try {
      const path = await uploadFile(file, ext, type);
      await deliver(p, { p_kind: "video", p_body: null, p_media: path, p_meta: Object.keys(meta).length ? meta : null }, { once });
    } catch (e) {
      removePending(p);
      toast("Vidéo non envoyée. Vérifie ta connexion.");
    }
  }

  async function sendVoice(blob, mime, seconds) {
    const ext = /mp4|m4a|aac/.test(mime) ? "m4a" : /ogg/.test(mime) ? "ogg" : "webm";
    const type = ext === "m4a" ? "audio/mp4" : ext === "ogg" ? "audio/ogg" : "audio/webm";
    const local = URL.createObjectURL(blob);
    const p = addPending({ kind: "voice", meta: { d: seconds }, localUrl: local });
    try {
      const path = await uploadFile(blob, ext, type);
      await deliver(p, { p_kind: "voice", p_body: null, p_media: path, p_meta: { d: seconds } });
    } catch (e) {
      removePending(p);
      toast("Vocal non envoyé. Vérifie ta connexion.");
    }
  }

  // ---------- Saisie ----------
  function onInput() {
    const inp = $("ch-input");
    inp.style.height = "auto";
    inp.style.height = Math.min(120, inp.scrollHeight) + "px";
    const has = inp.value.trim().length > 0;
    const btn = $("ch-send");
    btn.innerHTML = has ? ic("send", 20) : ic("mic", 22);
    btn.classList.toggle("on", has);
    btn.dataset.c = has ? "send" : "mic";
    if (has) sendTyping();
  }

  // ---------- Photos ----------
  let pendingPhoto = null, pvOnce = false, pvAt = null;
  function refreshPvOpts() {
    const a = $("ch-pvonce"), b = $("ch-pvsched");
    if (!a) return;
    a.classList.toggle("on", pvOnce);
    b.classList.toggle("on", !!pvAt);
    b.textContent = pvAt ? "🕐 " + window.TMM_X.whenLabel(pvAt) : "🕐 Programmer";
  }
  async function onFile(ev) {
    const f = ev.target.files && ev.target.files[0];
    ev.target.value = "";
    if (!f) return;
    if ((f.type || "").startsWith("video/")) {
      const o = await window.TMM_X.choose({ title: "Envoyer cette vidéo", canOnce: true });
      if (o) sendVideo(f, o);
      return;
    }
    try {
      pendingPhoto = await resizeImage(f);
      $("ch-pvimg").src = URL.createObjectURL(pendingPhoto.blob);
      $("ch-pvcap").value = "";
      $("ch-pv").hidden = false;
    } catch (e) {
      toast("Impossible de lire cette photo.");
    }
  }

  async function resizeImage(file, max = 4096, q = 0.92) {
    let src, w, h;
    try {
      src = await createImageBitmap(file, { imageOrientation: "from-image" });
      w = src.width; h = src.height;
    } catch (e) {
      src = await new Promise((res, rej) => {
        const img = new Image();
        img.onload = () => res(img);
        img.onerror = rej;
        img.src = URL.createObjectURL(file);
      });
      w = src.naturalWidth; h = src.naturalHeight;
    }
    const s = Math.min(1, max / Math.max(w, h));
    const cw = Math.max(1, Math.round(w * s)), ch = Math.max(1, Math.round(h * s));
    const c = document.createElement("canvas");
    c.width = cw; c.height = ch;
    const ctx = c.getContext("2d");
    ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, cw, ch);
    ctx.drawImage(src, 0, 0, cw, ch);
    const blob = await new Promise((res) => c.toBlob(res, "image/jpeg", q));
    if (!blob) throw new Error("canvas");
    return { blob, w: cw, h: ch };
  }

  // ---------- Vocaux : enregistrement ----------
  let rec = null, recStream = null, recChunks = [], recT0 = 0, recTimer = null, recMime = "";
  async function startRec() {
    if (rec) return;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.MediaRecorder) {
      toast("Les messages vocaux ne sont pas pris en charge ici.");
      return;
    }
    try {
      recStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
      toast("Micro refusé. Autorise-le dans les réglages pour envoyer des vocaux.");
      return;
    }
    const types = ["audio/mp4", "audio/webm;codecs=opus", "audio/webm", "audio/ogg;codecs=opus"];
    const mime = types.find((t) => MediaRecorder.isTypeSupported(t)) || "";
    try {
      rec = new MediaRecorder(recStream, mime ? { mimeType: mime } : {});
    } catch (e) {
      recStream.getTracks().forEach((t) => t.stop());
      toast("Les messages vocaux ne sont pas pris en charge ici.");
      return;
    }
    recMime = (rec.mimeType || mime || "audio/mp4").split(";")[0];
    recChunks = [];
    rec.ondataavailable = (e) => { if (e.data && e.data.size) recChunks.push(e.data); };
    rec.start();
    recT0 = Date.now();
    $("ch-rect").textContent = "0:00";
    $("ch-rec").hidden = false;
    $("ch-bar").hidden = true;
    recTimer = setInterval(() => {
      const s = (Date.now() - recT0) / 1000;
      $("ch-rect").textContent = mmss(s);
      if (s >= 300) stopRec(true);
    }, 250);
  }

  function stopRec(send) {
    if (!rec) return;
    const r = rec, stream = recStream, chunks = recChunks, mime = recMime;
    const seconds = Math.round((Date.now() - recT0) / 1000);
    rec = null; recStream = null;
    clearInterval(recTimer);
    $("ch-rec").hidden = true;
    $("ch-bar").hidden = false;
    r.onstop = () => {
      stream.getTracks().forEach((t) => t.stop());
      if (!send) return;
      if (seconds < 1 || !chunks.length) { toast("Vocal trop court."); return; }
      sendVoice(new Blob(chunks, { type: mime }), mime, seconds);
    };
    try { r.stop(); } catch (e) { stream.getTracks().forEach((t) => t.stop()); }
  }

  // ---------- Panneau emojis / stickers / GIF ----------
  function openPanel(tab) {
    panelTab = tab;
    const p = $("ch-panel");
    p.hidden = false;
    $("ch-input").blur();
    $("ch-emojibtn").classList.add("open");
    p.innerHTML = '<div class="ch-pbody" id="ch-pbody"></div><div class="ch-ptabs">' +
      [["sticker", "💗 Stickers"], ["gif", "GIF"]].map(([k, l]) =>
        '<button data-c="tab" data-t="' + k + '" class="' + (k === tab ? "on" : "") + '">' + l + "</button>").join("") + "</div>";
    if (tab === "sticker") fillStickers();
    else fillGif();
    if (host._fit) host._fit();
    setTimeout(() => { if (nearBottomFlag) scrollBottom(true); }, 50);
  }
  function closePanel() {
    if (!panelTab && $("ch-panel").hidden) return;
    panelTab = null;
    $("ch-panel").hidden = true;
    $("ch-emojibtn").classList.remove("open");
  }

  function fillEmoji(catIndex) {
    const cats = window.EMOJI_CATS || [];
    const ci = catIndex || 0;
    const body = $("ch-pbody");
    body.innerHTML = '<div class="ch-cats">' + cats.map((c, i) =>
      '<button data-c="cat" data-i="' + i + '" class="' + (i === ci ? "on" : "") + '" title="' + esc(c.name) + '">' + c.icon + "</button>").join("") +
      '</div><div class="ch-grid emoji">' + (cats[ci] ? cats[ci].emojis.map((e) => '<button data-c="emo" data-e="' + e + '">' + e + "</button>").join("") : "") + "</div>";
  }

  async function fillStickers() {
    const body = $("ch-pbody");
    if (!stickers.length) {
      try {
        const r = await fetch("stickers/stickers.json");
        stickers = await r.json();
      } catch (e) { stickers = []; }
    }
    if (panelTab !== "sticker") return;
    body.innerHTML = '<div class="ch-grid stick">' + stickers.map((s) =>
      '<button data-c="stk" data-id="' + esc(s.id) + '" title="' + esc(s.label) + '"><img class="' + stkClass(s.id) + '" src="' + esc(stkSrc(s.id)) + '" alt="' + esc(s.label) + '" loading="lazy"></button>').join("") + "</div>";
  }

  let gifQ = "", gifOffset = 0, gifBusy = false, gifDone = false, gifTimer = null, gifSeq = 0;
  function fillGif() {
    const body = $("ch-pbody");
    body.innerHTML = '<div class="ch-gifsearch"><input id="ch-gifq" placeholder="Rechercher un GIF…" value="' + esc(gifQ) + '" autocomplete="off"><small>Powered by GIPHY</small></div>' +
      '<div class="ch-gifs" id="ch-gifs"></div>';
    $("ch-gifq").addEventListener("input", (e) => {
      clearTimeout(gifTimer);
      gifTimer = setTimeout(() => { gifQ = e.target.value.trim(); gifLoad(true); }, 400);
    });
    $("ch-gifs").addEventListener("scroll", (e) => {
      const g = e.target;
      if (g.scrollHeight - g.scrollTop - g.clientHeight < 200) gifLoad(false);
    }, { passive: true });
    gifLoad(true);
  }

  async function gifLoad(reset) {
    const box = $("ch-gifs");
    if (!box) return;
    if (reset) { gifOffset = 0; gifDone = false; box.innerHTML = ""; gifBusy = false; gifSeq++; }
    if (gifBusy || gifDone) return;
    gifBusy = true;
    const seq = gifSeq;
    try {
      const cfg = A().CFG;
      const s = await A().session(false);
      const res = await fetch(cfg.SUPABASE_URL + "/functions/v1/gifs?q=" + encodeURIComponent(gifQ) + "&offset=" + gifOffset, {
        headers: { apikey: cfg.SUPABASE_ANON_KEY, Authorization: "Bearer " + s.access },
      });
      if (seq !== gifSeq) { gifBusy = false; return; }
      if (!res.ok) throw new Error("gifs " + res.status);
      const j = await res.json();
      const items = j.results || [];
      if (!items.length) {
        gifDone = true;
        if (!box.children.length) box.innerHTML = '<p class="ch-gifmsg">Aucun GIF trouvé.</p>';
      } else {
        const col = box.querySelector(".ch-gifcols") || (() => {
          const c = document.createElement("div"); c.className = "ch-gifcols"; c.innerHTML = "<div></div><div></div>"; box.appendChild(c); return c;
        })();
        items.forEach((g, i) => {
          const b = document.createElement("button");
          b.dataset.c = "gif";
          b.dataset.url = g.url; b.dataset.w = g.w; b.dataset.h = g.h;
          b.innerHTML = '<img src="' + esc(g.preview) + '" alt="GIF" loading="lazy" style="aspect-ratio:' + (g.w || 1) + "/" + (g.h || 1) + '">';
          col.children[(gifOffset + i) % 2 === 0 ? 0 : 1].appendChild(b);
        });
        gifOffset = typeof j.next === "number" ? j.next : gifOffset + items.length;
      }
    } catch (e) {
      if (seq === gifSeq && !box.children.length) {
        box.innerHTML = '<p class="ch-gifmsg">Les GIF ne sont pas disponibles pour le moment.</p>';
      }
    }
    gifBusy = false;
  }

  // ---------- Clics ----------
  function insertEmoji(e) {
    const inp = $("ch-input");
    const a = inp.selectionStart == null ? inp.value.length : inp.selectionStart;
    const b = inp.selectionEnd == null ? a : inp.selectionEnd;
    inp.value = inp.value.slice(0, a) + e + inp.value.slice(b);
    const pos = a + e.length;
    try { inp.setSelectionRange(pos, pos); } catch (x) { /* ignoré */ }
    onInput();
  }

  function onClick(ev) {
    unlockAudio();
    if (sel.size) {
      const r = ev.target.closest(".ch-row[data-id]");
      if (r && !ev.target.closest('.ch-selbar, .ch-wp')) {
        const mm = byId.get(Number(r.dataset.id));
        if (mm && mm.kind !== "game") toggleSel(mm.id);
        ev.preventDefault(); ev.stopPropagation();
        return;
      }
    }
    const t = ev.target.closest("[data-c]");
    if (!t) return;
    const c = t.dataset.c;
    const row = t.closest(".ch-row");
    switch (c) {
      case "back": close(); break;
      case "down": scrollBottom(true); missedCount = 0; $("ch-downn").textContent = ""; break;
      case "emoji": panelTab ? (closePanel(), $("ch-input").focus()) : openPanel("sticker"); break;
      case "tab": openPanel(t.dataset.t); break;
      case "cat": fillEmoji(Number(t.dataset.i)); break;
      case "emo": insertEmoji(t.dataset.e); break;
      case "stk": sendSticker(t.dataset.id); break;
      case "gif": sendGif({ url: t.dataset.url, w: Number(t.dataset.w) || 200, h: Number(t.dataset.h) || 200 }); break;
      case "send": sendText(); break;
      case "mic": startRec(); break;
      case "recCancel": stopRec(false); break;
      case "recSend": stopRec(true); break;
      case "attach": $("ch-cam").hidden = false; break;
      case "camClose": if (ev.target === t || t.classList.contains("ch-wpclose")) $("ch-cam").hidden = true; break;
      case "camPhoto": $("ch-cam").hidden = true; $("ch-filep").click(); ev.stopPropagation(); break;
      case "camVideo": $("ch-cam").hidden = true; $("ch-filev").click(); ev.stopPropagation(); break;
      case "camGallery": $("ch-cam").hidden = true; $("ch-file").click(); ev.stopPropagation(); break;
      case "wpOpen": $("ch-wp").hidden = false; break;
      case "wpClose": if (ev.target === t || t.classList.contains("ch-wpclose")) $("ch-wp").hidden = true; break;
      case "wpPreset": chooseWall("preset:" + Number(t.dataset.i)); ev.stopPropagation(); break;
      case "wpGallery": $("ch-wpfile").click(); ev.stopPropagation(); break;
      case "wpReset": chooseWall(null); ev.stopPropagation(); break;
      case "pvRetake": $("ch-pv").hidden = true; pendingPhoto = null; $("ch-cam").hidden = false; break;
      case "pvCancel": $("ch-pv").hidden = true; pendingPhoto = null; pvOnce = false; pvAt = null; refreshPvOpts(); break;
      case "pvOnce": pvOnce = !pvOnce; refreshPvOpts(); break;
      case "pvSched": {
        if (pvAt) { pvAt = null; refreshPvOpts(); break; }
        window.TMM_X.choose({ title: "Programmer la photo", canOnce: false }).then((o) => { if (o && o.at) { pvAt = o.at; refreshPvOpts(); } });
        break;
      }
      case "onceOpen": { const r = t.closest(".ch-row"); if (r && r.dataset.id) window.TMM_X.openOnce(Number(r.dataset.id)); break; }
      case "pvSend": {
        if (!pendingPhoto) break;
        const ph = pendingPhoto; pendingPhoto = null;
        const cap = $("ch-pvcap").value.trim();
        $("ch-pv").hidden = true;
        const o = { once: pvOnce, at: pvAt };
        pvOnce = false; pvAt = null; refreshPvOpts();
        sendImage(ph.blob, ph.w, ph.h, cap, o);
        break;
      }
      case "view": $("ch-viewimg").src = t.src; $("ch-view").hidden = false; break;
      case "viewClose": $("ch-view").hidden = true; break;
      case "quote": jumpTo(t.dataset.id); break;
      case "mClose": if (ev.target === t || t.classList.contains("ch-wpclose")) closeMsgMenu(); break;
      case "mSel": { const id = menuId; closeMsgMenu(); if (id) { sel.clear(); sel.add(id); updateSel(); } ev.stopPropagation(); break; }
      case "selClose": clearSel(); break;
      case "selDel": openSelDelete(); break;
      case "sDelMe": closeMsgMenu(); deleteSelected(false); ev.stopPropagation(); break;
      case "sDelAll": closeMsgMenu(); deleteSelected(true); ev.stopPropagation(); break;
      case "mReply": { const m = byId.get(menuId); closeMsgMenu(); if (m) setReply(m); ev.stopPropagation(); break; }
      case "mCopy": {
        const m = byId.get(menuId); closeMsgMenu();
        if (m && m.body) { try { navigator.clipboard.writeText(m.body); toast("Copié."); } catch (e) { toast("Copie impossible."); } }
        ev.stopPropagation(); break;
      }
      case "mDelMe": { const id = menuId; closeMsgMenu(); deleteMsg(id, false); ev.stopPropagation(); break; }
      case "mDelAll": { const id = menuId; closeMsgMenu(); deleteMsg(id, true); ev.stopPropagation(); break; }
      case "callAudio": if (window.TMM_CALL) window.TMM_CALL.start(false); break;
      case "callVideo": if (window.TMM_CALL) window.TMM_CALL.start(true); break;
      case "mEdit": { const m = byId.get(menuId); closeMsgMenu(); if (m) startEdit(m); ev.stopPropagation(); break; }
      case "replyCancel": if (editingId) cancelEdit(); else clearReply(); break;
      case "play": if (row) togglePlay(row); break;
      case "seek": if (row) seek(row, ev); break;
      default: break;
    }
  }

  async function sendRaw(text) {
    const p = addPending({ kind: "text", body: text });
    return deliver(p, { p_kind: "text", p_body: text, p_media: null, p_meta: null });
  }

  // Photo envoyée depuis n'importe où (ex. Now pic quand le chat n'est pas ouvert).
  async function sendPhotoAny(blob, w, h, once, nowpic) {
    if (opened) { sendImage(blob, w, h, "", { once: !!once, nowpic: !!nowpic }); return; }
    try {
      const s = await A().session(false);
      if (!me) me = s.uid;
      const path = await uploadFile(blob, "jpg", "image/jpeg");
      const meta = { w, h }; if (nowpic) meta.nowpic = true;
      const j = await A().authed("POST", "/rest/v1/rpc/" + (once ? "send_once" : "send_message"), { p_kind: "image", p_body: null, p_media: path, p_meta: meta });
      if (!j || j.status !== "ok") throw new Error("send");
      toast("📸 Photo envoyée !");
    } catch (e) { toast("Photo non envoyée. Vérifie ta connexion."); }
  }

  window.TMM_CHAT = {
    sendPhotoAny,
    sendRaw,
    poll,
    toast,
    open,
    close,
    isOpen: () => opened,
    setWall,
    _x: { markOpened, mediaUrl, partner: () => partner, uploadFile, addPending, removePending, deliver, sendImage, sendVideo, toast },
  };
})();
