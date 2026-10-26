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

  async function fetchLatest() {
    return (await sb("GET", "/rest/v1/messages?select=" + COLS + "&order=id.desc&limit=" + PAGE)) || [];
  }
  async function fetchOlder(beforeId) {
    return (await sb("GET", "/rest/v1/messages?select=" + COLS + "&id=lt." + beforeId + "&order=id.desc&limit=" + PAGE)) || [];
  }
  async function fetchNew(afterId) {
    return (await sb("GET", "/rest/v1/messages?select=" + COLS + "&id=gt." + afterId + "&order=id.asc&limit=100")) || [];
  }

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

  function stkClass(id) {
    const n = parseInt(String(id || "").slice(1, 3), 10) || 0;
    return "stk" + (n % 5);
  }

  // ---------- Fond d'écran de la conversation (local à cet appareil) ----------
  const WALL_PRESETS = [
    "linear-gradient(160deg,#FFD6E0,#FFB3C7)", "linear-gradient(160deg,#E0D4FF,#B9A6FF)",
    "linear-gradient(160deg,#D4F1FF,#A6DCFF)", "linear-gradient(160deg,#D8F5E4,#A9E5C4)",
    "linear-gradient(160deg,#FFE9C7,#FFC98A)", "linear-gradient(160deg,#2B2140,#4B2A5E)",
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
      const img = await resizeImage(f, 1080, 0.78);
      const path = await uploadFile(img.blob, "jpg", "image/jpeg");
      await chooseWall("photo:" + path);
    } catch (e) {
      toast("Impossible de lire cette photo.");
    }
  }

  // ---------- Construction de l'écran ----------
  function build() {
    if (built) return;
    built = true;
    host.innerHTML = `
      <div class="ch-head">
        <button class="ch-icon" data-c="back" aria-label="Retour">‹</button>
        <div class="ch-avatar">💗</div>
        <div class="ch-title"><b id="ch-name"></b><small>Ton partenaire</small></div>
        <button class="ch-icon ch-menu" data-c="wpOpen" aria-label="Fond d'écran">⋮</button>
      </div>
      <div class="ch-list" id="ch-list"></div>
      <button class="ch-down" id="ch-down" data-c="down" hidden>⌄<span id="ch-downn"></span></button>
      <div class="ch-toast" id="ch-toast" hidden></div>
      <div class="ch-panel" id="ch-panel" hidden></div>
      <div class="ch-bar" id="ch-bar">
        <button class="ch-icon" data-c="emoji" id="ch-emojibtn" aria-label="Emojis">😊</button>
        <textarea id="ch-input" rows="1" placeholder="Message" autocomplete="off" enterkeyhint="send"></textarea>
        <button class="ch-icon" data-c="attach" aria-label="Photo">📷</button>
        <button class="ch-send" data-c="send" id="ch-send" aria-label="Envoyer">🎤</button>
      </div>
      <div class="ch-rec" id="ch-rec" hidden>
        <button class="ch-icon" data-c="recCancel" aria-label="Annuler">🗑</button>
        <span class="ch-dot"></span><span id="ch-rect">0:00</span>
        <span class="ch-recgrow">Enregistrement…</span>
        <button class="ch-send on" data-c="recSend" aria-label="Envoyer le vocal">➤</button>
      </div>
      <div class="ch-pv" id="ch-pv" hidden>
        <img id="ch-pvimg" alt="">
        <div class="ch-pvbar">
          <input id="ch-pvcap" placeholder="Ajouter une légende…" maxlength="300">
          <button class="ch-send on" data-c="pvSend" aria-label="Envoyer la photo">➤</button>
        </div>
        <button class="ch-pvx" data-c="pvCancel" aria-label="Fermer">✕</button>
      </div>
      <div class="ch-view" id="ch-view" hidden data-c="viewClose"><img id="ch-viewimg" alt=""></div>
      <input type="file" id="ch-file" accept="image/*" hidden>
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
    $("ch-input").addEventListener("focus", () => { if (panelTab) closePanel(); setTimeout(() => scrollBottom(true), 250); });
    $("ch-file").addEventListener("change", onFile);
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
    markRead();
    startPolling();
  }

  function close() {
    if (!opened) return;
    opened = false;
    if (rec) stopRec(false);
    closePanel();
    pauseAllAudio();
    host.hidden = true;
    document.body.classList.remove("chat-open");
    clearInterval(pollTimer); pollTimer = null;
  }

  function reset() {
    msgs = []; byId.clear(); unreadOwn.clear(); pendings = []; noMore = false;
  }

  function startPolling() {
    clearInterval(pollTimer);
    pollTimer = setInterval(poll, 2500);
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
          if (m.sender_id !== me) incoming = true;
          else dropMatchingPending(m);
        }
      });
      if (added.length) appendMessages(added, incoming);
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
    const t = '<span class="ch-meta">' + hhmm(m.created_at) + tickHtml(m) + "</span>";
    switch (m.kind) {
      case "text": {
        if (isBigEmoji(m.body)) return { cls: "big", html: '<div class="ch-bigemoji">' + esc(m.body) + "</div>" + t };
        return { cls: "", html: '<div class="ch-text">' + linkify(m.body || "") + "</div>" + t };
      }
      case "sticker": {
        const ok = stickers.some((s) => s.id === m.body) || /^s\d\d_/.test(m.body || "");
        return {
          cls: "sticker",
          html: ok ? '<img class="ch-sticker ' + stkClass(m.body) + '" src="stickers/' + esc(m.body) + '.png" alt="sticker" onerror="this.replaceWith(document.createTextNode(\'💗\'))">' + t
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
    const mine = m.sender_id === me;
    const b = bubbleInner(m);
    const row = document.createElement("div");
    row.className = "ch-row " + (mine ? "mine" : "theirs") + (m.pending ? " pending" : "");
    row.dataset.day = dayKey(m.created_at);
    if (m.pending) row.dataset.tmp = m.tmp; else row.dataset.id = m.id;
    row.innerHTML = '<div class="ch-bubble ' + b.cls + '">' + b.html + "</div>";
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

  // ---------- Envoi ----------
  function addPending(p) {
    p.pending = true;
    p.tmp = "t" + (++tmpCount);
    p.sender_id = me;
    p.created_at = new Date().toISOString();
    pendings.push(p);
    appendMessages([p], false);
    scrollBottom(true);
    return p;
  }
  function removePending(p) {
    pendings = pendings.filter((x) => x !== p);
    const el = host.querySelector('.ch-row[data-tmp="' + p.tmp + '"]');
    if (el) el.remove();
  }

  async function deliver(p, payload) {
    try {
      const j = await A().authed("POST", "/rest/v1/rpc/send_message", payload);
      if (!j || j.status !== "ok") {
        removePending(p);
        if (j && j.status === "no_partner") { toast("Vous n'êtes plus partenaires."); A().reload(); close(); }
        else toast("Message non envoyé.");
        return false;
      }
      if (byId.has(j.id)) { removePending(p); return true; }
      // Le message a bien été enregistré : on le remplace par la version du serveur.
      const m = { id: j.id, sender_id: me, kind: payload.p_kind, body: payload.p_body || null, media: payload.p_media || null,
        meta: payload.p_meta || null, created_at: p.created_at, read_at: null, localUrl: p.localUrl };
      pendings = pendings.filter((x) => x !== p);
      byId.set(m.id, m);
      msgs.push(m);
      msgs.sort((a, b) => a.id - b.id);
      unreadOwn.add(m.id);
      const el = host.querySelector('.ch-row[data-tmp="' + p.tmp + '"]');
      if (el) {
        const fresh = rowEl(m);
        el.replaceWith(fresh);
      }
      return true;
    } catch (e) {
      removePending(p);
      toast("Message non envoyé. Vérifie ta connexion.");
      return false;
    }
  }

  async function sendText() {
    const inp = $("ch-input");
    const text = inp.value.trim();
    if (!text) return;
    inp.value = "";
    onInput();
    const p = addPending({ kind: "text", body: text });
    const ok = await deliver(p, { p_kind: "text", p_body: text, p_media: null, p_meta: null });
    if (!ok && !inp.value) { inp.value = text; onInput(); }
  }

  async function sendSticker(id) {
    closePanel();
    const p = addPending({ kind: "sticker", body: id });
    await deliver(p, { p_kind: "sticker", p_body: id, p_media: null, p_meta: null });
  }

  async function sendGif(g) {
    closePanel();
    const p = addPending({ kind: "gif", media: g.url, meta: { w: g.w, h: g.h } });
    await deliver(p, { p_kind: "gif", p_body: null, p_media: g.url, p_meta: { w: g.w, h: g.h } });
  }

  async function sendImage(blob, w, h, caption) {
    const local = URL.createObjectURL(blob);
    const p = addPending({ kind: "image", body: caption || null, meta: { w, h }, localUrl: local });
    try {
      const path = await uploadFile(blob, "jpg", "image/jpeg");
      await deliver(p, { p_kind: "image", p_body: caption || null, p_media: path, p_meta: { w, h } });
    } catch (e) {
      removePending(p);
      toast("Photo non envoyée. Vérifie ta connexion.");
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
    btn.textContent = has ? "➤" : "🎤";
    btn.classList.toggle("on", has);
    btn.dataset.c = has ? "send" : "mic";
  }

  // ---------- Photos ----------
  let pendingPhoto = null;
  async function onFile(ev) {
    const f = ev.target.files && ev.target.files[0];
    ev.target.value = "";
    if (!f) return;
    try {
      pendingPhoto = await resizeImage(f);
      $("ch-pvimg").src = URL.createObjectURL(pendingPhoto.blob);
      $("ch-pvcap").value = "";
      $("ch-pv").hidden = false;
    } catch (e) {
      toast("Impossible de lire cette photo.");
    }
  }

  async function resizeImage(file, max = 1280, q = 0.82) {
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
    $("ch-emojibtn").textContent = "⌨️";
    p.innerHTML = '<div class="ch-pbody" id="ch-pbody"></div><div class="ch-ptabs">' +
      [["emoji", "😊 Emojis"], ["sticker", "💗 Stickers"], ["gif", "GIF"]].map(([k, l]) =>
        '<button data-c="tab" data-t="' + k + '" class="' + (k === tab ? "on" : "") + '">' + l + "</button>").join("") + "</div>";
    if (tab === "emoji") fillEmoji();
    else if (tab === "sticker") fillStickers();
    else fillGif();
    if (host._fit) host._fit();
    setTimeout(() => { if (nearBottomFlag) scrollBottom(true); }, 50);
  }
  function closePanel() {
    if (!panelTab && $("ch-panel").hidden) return;
    panelTab = null;
    $("ch-panel").hidden = true;
    $("ch-emojibtn").textContent = "😊";
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
      '<button data-c="stk" data-id="' + esc(s.id) + '" title="' + esc(s.label) + '"><img class="' + stkClass(s.id) + '" src="stickers/' + esc(s.id) + '.png" alt="' + esc(s.label) + '" loading="lazy"></button>').join("") + "</div>";
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
    const t = ev.target.closest("[data-c]");
    if (!t) return;
    const c = t.dataset.c;
    const row = t.closest(".ch-row");
    switch (c) {
      case "back": close(); break;
      case "down": scrollBottom(true); missedCount = 0; $("ch-downn").textContent = ""; break;
      case "emoji": panelTab ? (closePanel(), $("ch-input").focus()) : openPanel("emoji"); break;
      case "tab": openPanel(t.dataset.t); break;
      case "cat": fillEmoji(Number(t.dataset.i)); break;
      case "emo": insertEmoji(t.dataset.e); break;
      case "stk": sendSticker(t.dataset.id); break;
      case "gif": sendGif({ url: t.dataset.url, w: Number(t.dataset.w) || 200, h: Number(t.dataset.h) || 200 }); break;
      case "send": sendText(); break;
      case "mic": startRec(); break;
      case "recCancel": stopRec(false); break;
      case "recSend": stopRec(true); break;
      case "attach": $("ch-file").click(); break;
      case "wpOpen": $("ch-wp").hidden = false; break;
      case "wpClose": if (ev.target === t || t.classList.contains("ch-wpclose")) $("ch-wp").hidden = true; break;
      case "wpPreset": chooseWall("preset:" + Number(t.dataset.i)); ev.stopPropagation(); break;
      case "wpGallery": $("ch-wpfile").click(); ev.stopPropagation(); break;
      case "wpReset": chooseWall(null); ev.stopPropagation(); break;
      case "pvCancel": $("ch-pv").hidden = true; pendingPhoto = null; break;
      case "pvSend": {
        if (!pendingPhoto) break;
        const ph = pendingPhoto; pendingPhoto = null;
        const cap = $("ch-pvcap").value.trim();
        $("ch-pv").hidden = true;
        sendImage(ph.blob, ph.w, ph.h, cap);
        break;
      }
      case "view": $("ch-viewimg").src = t.src; $("ch-view").hidden = false; break;
      case "viewClose": $("ch-view").hidden = true; break;
      case "play": if (row) togglePlay(row); break;
      case "seek": if (row) seek(row, ev); break;
      default: break;
    }
  }

  window.TMM_CHAT = {
    open,
    close,
    isOpen: () => opened,
    setWall,
  };
})();
