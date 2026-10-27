// Casque (iPhone / web) : lecteur audio. Les fichiers choisis sont gardés dans le téléphone (IndexedDB).
(function () {
  "use strict";
  const CFG = window.TMM_CONFIG || {};
  const host = document.getElementById("player");
  if (!host) return;

  // ---------- Petites fonctions ----------
  const esc = (v) => String(v == null ? "" : v).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const fmt = (s) => {
    s = Math.max(0, Math.floor(s || 0));
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    return h ? h + ":" + String(m).padStart(2, "0") + ":" + String(x).padStart(2, "0") : m + ":" + String(x).padStart(2, "0");
  };
  const fmtTotal = (s) => { const m = Math.round(s / 60); return m >= 60 ? Math.floor(m / 60) + " h " + String(m % 60).padStart(2, "0") + " min" : m + " min"; };
  const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  const lsGet = (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } };
  const lsSet = (k, v) => { try { localStorage.setItem(k, v); } catch (e) { /* ignoré */ } };

  // ---------- Stockage ----------
  let db = null;
  function openDb() {
    return new Promise((res, rej) => {
      const to = setTimeout(() => rej(new Error("idb-timeout")), 4000); // Safari bloque parfois l'ouverture
      const r = indexedDB.open("casque", 1);
      const ok = res;
      res = (v) => { clearTimeout(to); ok(v); };
      r.onupgradeneeded = () => {
        const d = r.result;
        d.createObjectStore("tracks", { keyPath: "id" });
        d.createObjectStore("blobs", { keyPath: "id" });
        d.createObjectStore("kv", { keyPath: "k" });
      };
      r.onsuccess = () => res(r.result);
      r.onerror = () => rej(r.error);
    });
  }
  const tx = (store, mode, fn) => new Promise((res, rej) => {
    const t = db.transaction(store, mode);
    const out = fn(t);
    t.oncomplete = () => res(out && out.result !== undefined ? out.result : undefined);
    t.onerror = t.onabort = () => rej(t.error);
  });
  const getAll = (store) => new Promise((res, rej) => { const r = db.transaction(store).objectStore(store).getAll(); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  const getOne = (store, k) => new Promise((res, rej) => { const r = db.transaction(store).objectStore(store).get(k); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  const putKv = (k, value) => tx("kv", "readwrite", (t) => t.objectStore("kv").put({ k, value }));

  // ---------- État ----------
  const L = { tracks: [], playlists: [], favs: new Set(), ready: false, error: null };
  const U = { tab: "titres", q: "", sort: "titre", sel: null, detail: null, np: false, sheet: null, toast: "", importing: null, view: "player", upd: "" };
  const Q = { order: [], pos: -1, shuffle: false, repeat: "off", speed: 1, sleepAt: 0, sleepT: null };
  const audio = new Audio();
  audio.preload = "auto";
  audio.setAttribute("playsinline", "");
  let curUrl = null, seeking = false;
  const covers = new Map();

  const byId = (id) => L.tracks.find((t) => t.id === id);
  const cur = () => (Q.pos >= 0 ? byId(Q.order[Q.pos]) : null);
  const coverUrl = (t) => {
    if (!t || !t.cover) return "";
    if (!covers.has(t.id)) covers.set(t.id, URL.createObjectURL(t.cover));
    return covers.get(t.id);
  };

  async function loadAll() {
    try {
      db = await openDb();
      L.tracks = await getAll("tracks");
      const pl = await getOne("kv", "playlists");
      L.playlists = (pl && pl.value) || [];
      const fv = await getOne("kv", "favs");
      L.favs = new Set((fv && fv.value) || []);
    } catch (e) {
      L.error = "Le stockage du téléphone n'est pas disponible (navigation privée ?).";
    }
    L.ready = true;
    draw();
  }
  const savePl = () => db && putKv("playlists", L.playlists);
  const saveFav = () => db && putKv("favs", [...L.favs]);

  // ---------- Lecture des étiquettes (ID3) ----------
  function dec(buf, enc) {
    try {
      if (enc === 1) {
        const bom = buf[0] === 0xff && buf[1] === 0xfe;
        return new TextDecoder(bom ? "utf-16le" : "utf-16be").decode(buf.subarray(2));
      }
      if (enc === 2) return new TextDecoder("utf-16be").decode(buf);
      if (enc === 3) return new TextDecoder("utf-8").decode(buf);
      return new TextDecoder("iso-8859-1").decode(buf);
    } catch (e) { return ""; }
  }
  async function readTags(file) {
    const out = {};
    try {
      const head = new Uint8Array(await file.slice(0, 10).arrayBuffer());
      if (String.fromCharCode(head[0], head[1], head[2]) !== "ID3") return out;
      const ver = head[3];
      const size = (head[6] << 21) | (head[7] << 14) | (head[8] << 7) | head[9];
      const b = new Uint8Array(await file.slice(10, 10 + Math.min(size, 3 * 1024 * 1024)).arrayBuffer());
      let p = 0;
      while (p + 10 < b.length) {
        const id = String.fromCharCode(b[p], b[p + 1], b[p + 2], b[p + 3]);
        if (!/^[A-Z0-9]{4}$/.test(id)) break;
        let fs = ver === 4 ? (b[p + 4] << 21) | (b[p + 5] << 14) | (b[p + 6] << 7) | b[p + 7]
          : ((b[p + 4] << 24) | (b[p + 5] << 16) | (b[p + 6] << 8) | b[p + 7]) >>> 0;
        const d = b.subarray(p + 10, p + 10 + fs);
        if (id === "TIT2" || id === "TPE1" || id === "TALB") {
          const v = dec(d.subarray(1), d[0]).replace(/\0+$/g, "").trim();
          if (v) out[id === "TIT2" ? "title" : id === "TPE1" ? "artist" : "album"] = v;
        } else if (id === "APIC" && !out.cover) {
          const enc = d[0];
          let i = 1;
          while (i < d.length && d[i] !== 0) i++;
          const mime = String.fromCharCode.apply(null, d.subarray(1, i));
          i += 2; // fin du type MIME + type d'image
          if (enc === 1 || enc === 2) { while (i + 1 < d.length && !(d[i] === 0 && d[i + 1] === 0)) i += 2; i += 2; }
          else { while (i < d.length && d[i] !== 0) i++; i += 1; }
          if (i < d.length && d.length - i > 200) out.cover = new Blob([d.slice(i)], { type: /png/i.test(mime) ? "image/png" : "image/jpeg" });
        }
        p += 10 + fs;
        if (fs <= 0) break;
      }
    } catch (e) { /* étiquettes illisibles : on utilise le nom du fichier */ }
    return out;
  }
  function duration(file) {
    return new Promise((res) => {
      const a = new Audio();
      const u = URL.createObjectURL(file);
      const done = (v) => { clearTimeout(t); a.removeAttribute("src"); URL.revokeObjectURL(u); res(v); };
      const t = setTimeout(() => done(0), 5000);
      a.preload = "metadata";
      a.onloadedmetadata = () => done(isFinite(a.duration) ? a.duration : 0);
      a.onerror = () => done(0);
      a.src = u;
    });
  }
  const AUDIO_RE = /\.(mp3|m4a|aac|wav|flac|ogg|oga|opus|caf|aif|aiff|wma|mp4)$/i;

  async function importFiles(list) {
    const files = [...list].filter((f) => (f.type && f.type.indexOf("audio") === 0) || AUDIO_RE.test(f.name));
    if (!files.length) { toast("Aucun fichier audio trouvé."); return; }
    if (!db) { toast(L.error || "Stockage indisponible."); return; }
    let added = 0, dup = 0;
    for (let i = 0; i < files.length; i++) {
      const f = files[i];
      U.importing = { i: i + 1, n: files.length, name: f.name };
      draw();
      if (L.tracks.some((t) => t.size === f.size && t.file === f.name)) { dup++; continue; }
      const base = f.name.replace(/\.[^.]+$/, "");
      const tg = await readTags(f);
      let title = tg.title || base, artist = tg.artist || "";
      if (!tg.title && /^(.+?)\s+-\s+(.+)$/.test(base)) { artist = artist || RegExp.$1; title = RegExp.$2; }
      const rel = f.webkitRelativePath || "";
      const folder = rel ? rel.split("/").slice(-2, -1)[0] || "" : "";
      const t = {
        id: uid(), file: f.name, size: f.size, mime: f.type || "audio/mpeg", title, artist: artist || "Artiste inconnu",
        album: tg.album || folder || "Album inconnu", folder: folder || "Importés", dur: await duration(f), added: Date.now(), cover: tg.cover || null,
      };
      try {
        await tx("blobs", "readwrite", (x) => x.objectStore("blobs").put({ id: t.id, blob: f }));
        await tx("tracks", "readwrite", (x) => x.objectStore("tracks").put(t));
        L.tracks.push(t);
        added++;
      } catch (e) { toast("Espace insuffisant sur le téléphone."); break; }
    }
    U.importing = null;
    toast(added + " titre" + (added > 1 ? "s" : "") + " ajouté" + (added > 1 ? "s" : "") + (dup ? " · " + dup + " déjà présent" + (dup > 1 ? "s" : "") : ""));
    draw();
  }

  async function removeTracks(ids) {
    for (const id of ids) {
      await tx("tracks", "readwrite", (x) => x.objectStore("tracks").delete(id));
      await tx("blobs", "readwrite", (x) => x.objectStore("blobs").delete(id));
      const i = L.tracks.findIndex((t) => t.id === id);
      if (i >= 0) L.tracks.splice(i, 1);
      L.favs.delete(id);
      L.playlists.forEach((p) => { p.ids = p.ids.filter((x) => x !== id); });
      if (covers.has(id)) { URL.revokeObjectURL(covers.get(id)); covers.delete(id); }
    }
    const c = cur();
    if (!c && Q.pos >= 0) { audio.pause(); Q.order = Q.order.filter((x) => byId(x)); Q.pos = -1; }
    savePl(); saveFav();
  }

  // ---------- Lecture ----------
  async function loadTrack(t, autoplay) {
    const rec = await getOne("blobs", t.id);
    if (!rec) { toast("Fichier introuvable."); return false; }
    if (curUrl) URL.revokeObjectURL(curUrl);
    curUrl = URL.createObjectURL(rec.blob);
    audio.src = curUrl;
    audio.playbackRate = Q.speed;
    if (autoplay) { try { await audio.play(); } catch (e) { /* le téléphone demande un geste */ } }
    updateSession();
    return true;
  }
  async function playList(ids, start, opts) {
    if (!ids.length) return;
    const first = ids[start || 0];
    if (Q.shuffle || (opts && opts.shuffle)) {
      Q.shuffle = true;
      const rest = ids.filter((x) => x !== first);
      for (let i = rest.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [rest[i], rest[j]] = [rest[j], rest[i]]; }
      Q.order = [first].concat(rest); Q.pos = 0;
    } else { Q.order = ids.slice(); Q.pos = start || 0; }
    await loadTrack(byId(Q.order[Q.pos]), true);
    draw();
  }
  async function step(d, auto) {
    if (!Q.order.length) return;
    let n = Q.pos + d;
    if (n >= Q.order.length) { if (Q.repeat === "all" || !auto) n = 0; else { audio.pause(); audio.currentTime = 0; draw(); return; } }
    if (n < 0) n = Q.order.length - 1;
    Q.pos = n;
    const t = cur();
    if (!t) return;
    await loadTrack(t, true);
    draw();
  }
  function toggle() {
    if (!cur()) { if (L.tracks.length) playList(visibleTracks().map((t) => t.id), 0); return; }
    if (audio.paused) audio.play().catch(() => {}); else audio.pause();
  }
  function toggleShuffle() {
    Q.shuffle = !Q.shuffle;
    const c = cur();
    if (c && Q.order.length > 1) {
      const rest = Q.order.filter((x) => x !== c.id);
      if (Q.shuffle) for (let i = rest.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [rest[i], rest[j]] = [rest[j], rest[i]]; }
      else rest.sort((a, b) => L.tracks.findIndex((t) => t.id === a) - L.tracks.findIndex((t) => t.id === b));
      Q.order = [c.id].concat(rest); Q.pos = 0;
    }
    draw();
  }
  const enqueue = (ids, next) => {
    if (!cur()) { playList(ids, 0); return; }
    if (next) Q.order.splice(Q.pos + 1, 0, ...ids); else Q.order.push(...ids);
    toast(next ? "Lu ensuite" : "Ajouté à la file d'attente");
  };
  function setSleep(min) {
    clearTimeout(Q.sleepT); Q.sleepT = null; Q.sleepAt = 0;
    if (min) {
      Q.sleepAt = Date.now() + min * 60000;
      Q.sleepT = setTimeout(() => { audio.pause(); Q.sleepAt = 0; draw(); }, min * 60000);
      toast("Minuterie : " + min + " min");
    } else toast("Minuterie désactivée");
  }

  audio.addEventListener("ended", () => { if (Q.repeat === "one") { audio.currentTime = 0; audio.play().catch(() => {}); } else step(1, true); });
  audio.addEventListener("play", () => { document.body.classList.add("cq-playing"); refreshPlayState(); });
  audio.addEventListener("pause", () => { document.body.classList.remove("cq-playing"); refreshPlayState(); });
  audio.addEventListener("timeupdate", tick);
  audio.addEventListener("loadedmetadata", () => { tick(); const c = cur(); if (c && !c.dur && isFinite(audio.duration)) { c.dur = audio.duration; tx("tracks", "readwrite", (x) => x.objectStore("tracks").put(c)); } });
  audio.addEventListener("error", () => { if (cur()) toast("Ce fichier ne peut pas être lu sur ce téléphone."); });

  function updateSession() {
    if (!("mediaSession" in navigator)) return;
    const t = cur();
    if (!t) return;
    try {
      const art = t.cover ? [{ src: coverUrl(t), sizes: "512x512", type: t.cover.type }] : [{ src: "icons/icon-512.png", sizes: "512x512", type: "image/png" }];
      navigator.mediaSession.metadata = new MediaMetadata({ title: t.title, artist: t.artist, album: t.album, artwork: art });
    } catch (e) { /* ignoré */ }
  }
  if ("mediaSession" in navigator) {
    const set = (a, f) => { try { navigator.mediaSession.setActionHandler(a, f); } catch (e) { /* ignoré */ } };
    set("play", () => audio.play().catch(() => {}));
    set("pause", () => audio.pause());
    set("previoustrack", () => (audio.currentTime > 4 ? (audio.currentTime = 0) : step(-1)));
    set("nexttrack", () => step(1));
    set("seekto", (d) => { audio.currentTime = d.seekTime; });
    set("seekbackward", () => { audio.currentTime = Math.max(0, audio.currentTime - 10); });
    set("seekforward", () => { audio.currentTime = audio.currentTime + 10; });
  }

  // ---------- Mises à jour légères de l'affichage ----------
  function tick() {
    const d = isFinite(audio.duration) ? audio.duration : (cur() ? cur().dur : 0);
    const p = d ? (audio.currentTime / d) * 100 : 0;
    const q = (id) => host.querySelector(id);
    const a = q("#np-cur"), b = q("#np-tot"), s = q("#np-seek"), m = q(".mini-fill");
    if (a) a.textContent = fmt(audio.currentTime);
    if (b) b.textContent = fmt(d);
    if (s && !seeking) { s.value = p * 10; s.style.setProperty("--p", p + "%"); }
    if (m) m.style.width = p + "%";
    try { if ("mediaSession" in navigator && d) navigator.mediaSession.setPositionState({ duration: d, playbackRate: audio.playbackRate, position: Math.min(audio.currentTime, d) }); } catch (e) { /* ignoré */ }
  }
  function refreshPlayState() {
    host.querySelectorAll("[data-pp]").forEach((b) => { b.innerHTML = audio.paused ? I.play : I.pause; });
    const v = host.querySelector(".vinyl");
    if (v) v.classList.toggle("on", !audio.paused);
    const arm = host.querySelector(".arm");
    if (arm) arm.classList.toggle("on", !audio.paused);
    host.querySelectorAll(".bars").forEach((b) => b.classList.toggle("off", audio.paused));
  }

  // ---------- Icônes ----------
  const sv = (d, s) => '<svg viewBox="0 0 24 24" width="' + (s || 24) + '" height="' + (s || 24) + '" fill="currentColor" aria-hidden="true">' + d + "</svg>";
  const I = {
    play: sv('<path d="M8 5v14l11-7z"/>'),
    pause: sv('<path d="M6 5h4v14H6zM14 5h4v14h-4z"/>'),
    next: sv('<path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/>'),
    prev: sv('<path d="M6 6h2v12H6zM9.5 12l8.5 6V6l-8.5 6z"/>'),
    shuffle: sv('<path d="M10.6 9.2L5.4 4 4 5.4l5.2 5.2 1.4-1.4zM14.5 4l2.1 2.1L4 18.6 5.4 20 18 7.5l2 2V4h-5.5zM14.8 13.4l-1.4 1.4 3.2 3.2-2.1 2.1H20v-5.5l-2 2-3.2-3.2z"/>'),
    repeat: sv('<path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"/>'),
    repeat1: sv('<path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z"/>'),
    gear: sv('<path d="M19.4 13a7.7 7.7 0 000-2l2.1-1.6a.5.5 0 00.1-.6l-2-3.5a.5.5 0 00-.6-.2l-2.5 1a7.3 7.3 0 00-1.7-1l-.4-2.6a.5.5 0 00-.5-.4h-4a.5.5 0 00-.5.4l-.4 2.6a7.3 7.3 0 00-1.7 1l-2.5-1a.5.5 0 00-.6.2l-2 3.5a.5.5 0 00.1.6L4.6 11a7.7 7.7 0 000 2l-2.1 1.6a.5.5 0 00-.1.6l2 3.5c.1.2.4.3.6.2l2.5-1c.5.4 1.1.7 1.7 1l.4 2.6c0 .2.2.4.5.4h4c.3 0 .5-.2.5-.4l.4-2.6c.6-.3 1.2-.6 1.7-1l2.5 1c.2.1.5 0 .6-.2l2-3.5a.5.5 0 00-.1-.6L19.4 13zM12 15.5a3.5 3.5 0 110-7 3.5 3.5 0 010 7z"/>'),
    plus: sv('<path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z"/>'),
    search: sv('<path d="M15.5 14h-.8l-.3-.3A6.5 6.5 0 109.5 16a6.5 6.5 0 004.2-1.6l.3.3v.8l5 5 1.5-1.5-5-5zm-6 0a4.5 4.5 0 110-9 4.5 4.5 0 010 9z"/>'),
    more: sv('<path d="M12 8a2 2 0 100-4 2 2 0 000 4zm0 2a2 2 0 100 4 2 2 0 000-4zm0 6a2 2 0 100 4 2 2 0 000-4z"/>'),
    star: sv('<path d="M12 17.3l6.2 3.7-1.6-7L22 9.2l-7.2-.6L12 2 9.2 8.6 2 9.2l5.5 4.8-1.6 7z"/>'),
    starO: sv('<path d="M22 9.2l-7.2-.6L12 2 9.2 8.6 2 9.2l5.5 4.8-1.6 7 6.1-3.7 6.2 3.7-1.6-7L22 9.2zM12 15.4l-3.8 2.3 1-4.3-3.3-2.9 4.4-.4L12 6.1l1.7 4 4.4.4-3.3 2.9 1 4.3-3.8-2.3z"/>'),
    back: sv('<path d="M15.4 7.4L14 6l-6 6 6 6 1.4-1.4L10.8 12z"/>'),
    down: sv('<path d="M7.4 8.6L12 13.2l4.6-4.6L18 10l-6 6-6-6z"/>'),
    note: sv('<path d="M12 3v10.6A4 4 0 1014 17V7h4V3z"/>'),
    list: sv('<path d="M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z"/>'),
    moon: sv('<path d="M12.3 3a9 9 0 109.7 11.9A7 7 0 0112.3 3z"/>'),
    check: sv('<path d="M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"/>'),
    close: sv('<path d="M19 6.4L17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z"/>'),
    folder: sv('<path d="M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8l-2-2z"/>'),
    disc: sv('<path d="M12 2a10 10 0 100 20 10 10 0 000-20zm0 14a4 4 0 110-8 4 4 0 010 8zm0-5.5a1.5 1.5 0 100 3 1.5 1.5 0 000-3z"/>'),
    head: sv('<path d="M12 3a9 9 0 00-9 9v7a3 3 0 003 3h1a2 2 0 002-2v-5a2 2 0 00-2-2H5v-1a7 7 0 0114 0v1h-2a2 2 0 00-2 2v5a2 2 0 002 2h1a3 3 0 003-3v-7a9 9 0 00-9-9z"/>', 28),
    chat: sv('<path d="M20 2H4a2 2 0 00-2 2v18l4-4h14a2 2 0 002-2V4a2 2 0 00-2-2z"/>'),
  };

  // ---------- Affichage ----------
  const visibleTracks = () => {
    let l = L.tracks.slice();
    if (U.detail) {
      if (U.detail.kind === "album") l = l.filter((t) => t.album === U.detail.id);
      else if (U.detail.kind === "playlist") { const p = L.playlists.find((x) => x.id === U.detail.id); l = p ? p.ids.map(byId).filter(Boolean) : []; }
    } else if (U.tab === "favoris") l = l.filter((t) => L.favs.has(t.id));
    const q = U.q.trim().toLowerCase();
    if (q) l = l.filter((t) => (t.title + " " + t.artist + " " + t.album).toLowerCase().indexOf(q) >= 0);
    if (!(U.detail && U.detail.kind === "playlist")) {
      const k = U.sort;
      l.sort((a, b) => k === "artiste" ? a.artist.localeCompare(b.artist, "fr") || a.title.localeCompare(b.title, "fr")
        : k === "recent" ? b.added - a.added : k === "duree" ? b.dur - a.dur : a.title.localeCompare(b.title, "fr"));
    }
    return l;
  };
  function cover(t, cls) {
    const u = coverUrl(t);
    return u ? '<div class="cv ' + (cls || "") + '" style="background-image:url(' + u + ')"></div>'
      : '<div class="cv ph ' + (cls || "") + ' h' + ((t ? t.album : "").length % 4) + '">' + I.note + "</div>";
  }
  function row(t, list, i) {
    const c = cur(), on = c && c.id === t.id, sel = U.sel && U.sel.has(t.id);
    return '<div class="tr' + (sel ? " sel" : "") + '" data-a="row" data-i="' + i + '" data-id="' + t.id + '">' +
      '<div class="cvw">' + cover(t) + (sel ? '<div class="cvo">' + I.check + "</div>" : on ? '<div class="cvo"><span class="bars' + (audio.paused ? " off" : "") + '"><i></i><i></i><i></i></span></div>' : "") + "</div>" +
      '<div class="tm"><b class="' + (on ? "on" : "") + '">' + esc(t.title) + "</b><small>" + esc(t.artist) + " · " + fmt(t.dur) + "</small></div>" +
      (L.favs.has(t.id) ? '<span class="fv">' + I.star + "</span>" : "") +
      (U.sel ? "" : '<button class="ib" data-a="menu" data-id="' + t.id + '" aria-label="Options">' + I.more + "</button>") + "</div>";
  }
  function emptyLib() {
    return '<div class="empty"><div class="eh">' + I.head + "</div><h3>Ta bibliothèque est vide</h3>" +
      "<p>Ajoute les fichiers audio (MP3, M4A, WAV…) de ton téléphone pour les écouter ici, avec playlists et lecture en arrière-plan.</p>" +
      '<button class="bt" data-a="pick">' + I.plus + " Ajouter des fichiers</button>" +
      (/iPhone|iPad/.test(navigator.userAgent) ? "" : '<button class="bt out" data-a="pickdir">' + I.folder + " Choisir un dossier</button>") + "</div>";
  }
  function libView() {
    const tabs = ["titres", "albums", "playlists", "favoris"];
    const names = { titres: "Titres", albums: "Albums", playlists: "Playlists", favoris: "Favoris" };
    let body = "";
    if (U.detail) {
      const l = visibleTracks();
      const p = U.detail.kind === "playlist" ? L.playlists.find((x) => x.id === U.detail.id) : null;
      const title = p ? p.name : U.detail.id;
      const tot = l.reduce((s, t) => s + t.dur, 0);
      body = '<div class="dh"><button class="ib" data-a="detailback">' + I.back + "</button><div><h2>" + esc(title) + "</h2><small>" + l.length + " titre" + (l.length > 1 ? "s" : "") + " · " + fmtTotal(tot) + "</small></div>" +
        (p ? '<button class="ib" data-a="plmenu" data-id="' + p.id + '">' + I.more + "</button>" : "") + "</div>" +
        '<div class="act"><button class="bt" data-a="playall">' + I.play + ' Lire</button><button class="bt out" data-a="shuffleall">' + I.shuffle + ' Aléatoire</button></div>' +
        (l.length ? l.map((t, i) => row(t, l, i)).join("") : '<p class="note">Aucun titre ici.</p>');
    } else if (U.tab === "albums") {
      const m = new Map();
      L.tracks.forEach((t) => { if (!m.has(t.album)) m.set(t.album, []); m.get(t.album).push(t); });
      const q = U.q.trim().toLowerCase();
      const albums = [...m.entries()].filter(([a, l]) => !q || (a + " " + l.map((t) => t.artist).join(" ")).toLowerCase().indexOf(q) >= 0).sort((x, y) => x[0].localeCompare(y[0], "fr"));
      body = albums.length ? '<div class="cgrid">' + albums.map(([a, l]) => '<div class="al" data-a="album" data-id="' + esc(a) + '">' + cover(l[0], "big") + "<b>" + esc(a) + "</b><small>" + esc(l[0].artist) + " · " + l.length + "</small></div>").join("") + "</div>" : L.tracks.length ? '<p class="note">Aucun résultat.</p>' : emptyLib();
    } else if (U.tab === "playlists") {
      body = '<button class="bt out wide" data-a="newpl">' + I.plus + " Nouvelle playlist</button>" +
        (L.playlists.length ? L.playlists.map((p) => {
          const t0 = byId(p.ids[0]);
          return '<div class="tr" data-a="playlist" data-id="' + p.id + '"><div class="cvw">' + cover(t0) + '</div><div class="tm"><b>' + esc(p.name) + "</b><small>" + p.ids.length + " titre" + (p.ids.length > 1 ? "s" : "") + "</small></div>" +
            '<button class="ib" data-a="plmenu" data-id="' + p.id + '">' + I.more + "</button></div>";
        }).join("") : '<p class="note">Crée une playlist, puis ajoute-y des titres avec le menu ⋮ d\'un morceau.</p>');
    } else {
      const l = visibleTracks();
      body = l.length ? '<div class="act"><button class="bt" data-a="playall">' + I.play + " Lire</button><button class=\"bt out\" data-a=\"shuffleall\">" + I.shuffle + " Aléatoire</button>" +
        '<button class="bt out sm" data-a="sort">' + esc({ titre: "A–Z", artiste: "Artiste", recent: "Récents", duree: "Durée" }[U.sort]) + "</button></div>" + l.map((t, i) => row(t, l, i)).join("")
        : L.tracks.length ? '<p class="note">' + (U.tab === "favoris" ? "Aucun favori pour l'instant." : "Aucun résultat.") + "</p>" : emptyLib();
    }
    const tot = L.tracks.reduce((s, t) => s + t.dur, 0);
    return '<header class="top">' + (U.sel ? '<button class="ib" data-a="selcancel">' + I.close + "</button><h1>" + U.sel.size + " sélectionné" + (U.sel.size > 1 ? "s" : "") + "</h1>" +
      '<button class="ib" data-a="selplay">' + I.play + '</button><button class="ib" data-a="seladd">' + I.list + '</button><button class="ib" data-a="seldel">' + I.close + "</button>"
      : '<div class="logo">' + I.head + "</div><h1>Casque</h1><button class=\"ib\" data-a=\"pick\" aria-label=\"Ajouter\">" + I.plus + '</button><button class="ib" data-a="settings" aria-label="Paramètres">' + I.gear + "</button>") + "</header>" +
      (U.detail ? "" : '<div class="sr"><span>' + I.search + '</span><input id="q" type="search" placeholder="Rechercher un titre, un artiste…" value="' + esc(U.q) + '" autocomplete="off" autocapitalize="none"></div>' +
        '<nav class="tabs">' + tabs.map((t) => '<button class="' + (U.tab === t ? "on" : "") + '" data-a="tab" data-id="' + t + '">' + names[t] + "</button>").join("") + "</nav>" +
        (L.tracks.length && U.tab === "titres" ? '<p class="sum">' + L.tracks.length + " titre" + (L.tracks.length > 1 ? "s" : "") + " · " + fmtTotal(tot) + "</p>" : "")) +
      '<main class="list">' + body + "</main>";
  }
  function miniView() {
    const t = cur();
    if (!t) return "";
    return '<div class="mini" data-a="np"><div class="mini-fill"></div>' + cover(t, "sm") + '<div class="tm"><b>' + esc(t.title) + "</b><small>" + esc(t.artist) + "</small></div>" +
      '<button class="ib" data-a="prev">' + I.prev + '</button><button class="ib pp" data-a="pp" data-pp>' + (audio.paused ? I.play : I.pause) + '</button><button class="ib" data-a="next">' + I.next + "</button></div>";
  }
  function npView() {
    const t = cur();
    if (!t) return "";
    const d = isFinite(audio.duration) ? audio.duration : t.dur;
    const p = d ? (audio.currentTime / d) * 100 : 0;
    const u = coverUrl(t);
    return '<div class="cqnp"><header class="top"><button class="ib" data-a="npclose">' + I.down + '</button><h1 class="c">Lecture en cours</h1><button class="ib" data-a="queue">' + I.list + "</button></header>" +
      '<div class="deck"><div class="vinyl' + (audio.paused ? "" : " on") + '"><div class="grooves"></div><div class="lab" style="' + (u ? "background-image:url(" + u + ")" : "") + '">' + (u ? "" : I.note) + '</div><div class="hole"></div></div>' +
      '<div class="arm' + (audio.paused ? "" : " on") + '"><i></i></div></div>' +
      '<div class="ti"><div><b>' + esc(t.title) + "</b><small>" + esc(t.artist) + " · " + esc(t.album) + '</small></div><button class="ib" data-a="fav" data-id="' + t.id + '">' + (L.favs.has(t.id) ? '<span class="fvo">' + I.star + "</span>" : I.starO) + "</button></div>" +
      '<input id="np-seek" class="seek" type="range" min="0" max="1000" value="' + p * 10 + '" style="--p:' + p + '%"><div class="tt"><span id="np-cur">' + fmt(audio.currentTime) + '</span><span id="np-tot">' + fmt(d) + "</span></div>" +
      '<div class="ctl"><button class="ib' + (Q.shuffle ? " act" : "") + '" data-a="shuf">' + I.shuffle + '</button><button class="ib big" data-a="prev">' + I.prev + '</button><button class="ib pp big" data-a="pp" data-pp>' + (audio.paused ? I.play : I.pause) +
      '</button><button class="ib big" data-a="next">' + I.next + '</button><button class="ib' + (Q.repeat !== "off" ? " act" : "") + '" data-a="rep">' + (Q.repeat === "one" ? I.repeat1 : I.repeat) + "</button></div>" +
      '<div class="opt"><button class="cpill" data-a="speed">' + Q.speed + '×</button><button class="cpill' + (Q.sleepAt ? " on" : "") + '" data-a="sleep">' + I.moon + (Q.sleepAt ? " " + Math.max(1, Math.round((Q.sleepAt - Date.now()) / 60000)) + " min" : " Minuterie") + '</button><button class="cpill" data-a="addpl" data-id="' + t.id + '">' + I.plus + " Playlist</button></div></div>";
  }
  function settingsView() {
    const size = L.tracks.reduce((s, t) => s + t.size, 0);
    return '<header class="top"><button class="ib" data-a="settingsback">' + I.back + "</button><h1>Paramètres</h1></header><main class=\"list set\">" +
      '<div class="card"><div class="logo big">' + I.head + "</div><h2>Casque</h2><p>Version " + esc((CFG.VERSION || "").replace(/^web /, "")) + " (iPhone / web)</p></div>" +
      '<p class="sec">BIBLIOTHÈQUE</p><div class="card l"><p>' + L.tracks.length + " titre" + (L.tracks.length > 1 ? "s" : "") + " · " + (size / 1048576).toFixed(1) + " Mo</p>" +
      '<button class="bt out wide" data-a="pick">' + I.plus + " Ajouter des fichiers audio</button>" +
      (/iPhone|iPad/.test(navigator.userAgent) ? "" : '<button class="bt out wide" data-a="pickdir">' + I.folder + " Choisir un dossier source</button>") +
      '<button class="bt out wide" data-a="clear">Vider la bibliothèque</button></div>' +
      '<p class="sec">APPLICATION</p><div class="card l"><button class="bt out wide" data-a="update">Rechercher une mise à jour</button>' + (U.upd ? '<p class="note">' + esc(U.upd) + "</p>" : "") + "</div>" +
      '<p class="sec">À PROPOS</p><div class="card l"><button class="bt wide" data-a="help">' + I.chat + " À propos</button></div>" +
      "</main>";
  }
  function sheetView() {
    const s = U.sheet;
    if (!s) return "";
    let h = "";
    if (s.kind === "addpl") {
      h = "<h3>Ajouter à une playlist</h3>" + L.playlists.map((p) => '<button class="sh" data-a="addto" data-id="' + p.id + '">' + esc(p.name) + "<small>" + p.ids.length + "</small></button>").join("") + '<button class="sh acc" data-a="newplAdd">' + I.plus + " Nouvelle playlist</button>";
    } else if (s.kind === "menu") {
      const t = byId(s.id); if (!t) return "";
      h = "<h3>" + esc(t.title) + "</h3>" + [["next", "Lire ensuite"], ["queue1", "Ajouter à la file d'attente"], ["addpl", "Ajouter à une playlist…"], ["fav", L.favs.has(t.id) ? "Retirer des favoris" : "Ajouter aux favoris"], ...(s.pl ? [["rmpl", "Retirer de cette playlist"]] : []), ["info", "Informations"], ["del", "Supprimer de la bibliothèque"]]
        .map(([a, l]) => '<button class="sh' + (a === "del" ? " red" : "") + '" data-a="m_' + a + '" data-id="' + t.id + '">' + l + "</button>").join("");
    } else if (s.kind === "plmenu") {
      h = "<h3>Playlist</h3>" + [["plplay", "Lire"], ["plren", "Renommer"], ["pldel", "Supprimer"]].map(([a, l]) => '<button class="sh' + (a === "pldel" ? " red" : "") + '" data-a="' + a + '" data-id="' + s.id + '">' + l + "</button>").join("");
    } else if (s.kind === "sort") {
      h = "<h3>Trier par</h3>" + [["titre", "Titre (A–Z)"], ["artiste", "Artiste"], ["recent", "Ajoutés récemment"], ["duree", "Durée"]].map(([k, l]) => '<button class="sh' + (U.sort === k ? " acc" : "") + '" data-a="setsort" data-id="' + k + '">' + l + "</button>").join("");
    } else if (s.kind === "speed") {
      h = "<h3>Vitesse de lecture</h3>" + [0.75, 1, 1.25, 1.5, 2].map((k) => '<button class="sh' + (Q.speed === k ? " acc" : "") + '" data-a="setspeed" data-id="' + k + '">' + k + "×</button>").join("");
    } else if (s.kind === "sleep") {
      h = "<h3>Minuterie de sommeil</h3>" + [0, 10, 20, 30, 45, 60].map((k) => '<button class="sh" data-a="setsleep" data-id="' + k + '">' + (k ? k + " minutes" : "Désactivée") + "</button>").join("");
    } else if (s.kind === "queue") {
      h = "<h3>File d'attente</h3>" + Q.order.map((id, i) => { const t = byId(id); return t ? '<button class="sh q' + (i === Q.pos ? " acc" : "") + '" data-a="qjump" data-id="' + i + '">' + esc(t.title) + "<small>" + esc(t.artist) + "</small></button>" : ""; }).join("");
    } else if (s.kind === "info") {
      const t = byId(s.id); if (!t) return "";
      h = "<h3>Informations</h3><dl><dt>Titre</dt><dd>" + esc(t.title) + "</dd><dt>Artiste</dt><dd>" + esc(t.artist) + "</dd><dt>Album</dt><dd>" + esc(t.album) + "</dd><dt>Durée</dt><dd>" + fmt(t.dur) + "</dd><dt>Fichier</dt><dd>" + esc(t.file) + " (" + (t.size / 1048576).toFixed(1) + " Mo)</dd></dl>";
    } else if (s.kind === "name") {
      h = "<h3>" + esc(s.title) + '</h3><input id="nm" class="ti2" maxlength="40" placeholder="Nom de la playlist" value="' + esc(s.value || "") + '"><button class="bt wide" data-a="namego">OK</button>';
    } else if (s.kind === "confirm") {
      h = "<h3>" + esc(s.title) + "</h3><p class=\"note\">" + esc(s.text) + '</p><button class="sh red" data-a="confirmgo">' + esc(s.ok) + '</button><button class="sh" data-a="sheetclose">Annuler</button>';
    }
    return '<div class="sheetbg" data-a="sheetclose"></div><div class="sheet">' + h + "</div>";
  }

  let toastT = null;
  function toast(t) { U.toast = t; clearTimeout(toastT); toastT = setTimeout(() => { U.toast = ""; const e = host.querySelector(".toast"); if (e) e.remove(); }, 2600); const e = host.querySelector(".toast"); if (e) e.textContent = t; else draw(); }

  function draw() {
    if (host.hidden) return;
    const keepScroll = host.querySelector(".list");
    const sc = keepScroll ? keepScroll.scrollTop : 0;
    const focusQ = document.activeElement && document.activeElement.id === "q";
    const caret = focusQ ? document.activeElement.selectionStart : 0;
    let h;
    if (!L.ready) h = '<div class="empty"><div class="spin"></div></div>';
    else if (U.view === "settings") h = settingsView();
    else h = libView() + (U.np ? "" : miniView()) + (U.np ? npView() : "");
    h += (U.importing ? '<div class="imp"><div class="spin"></div><p>Ajout de la musique…</p><small>' + U.importing.i + " / " + U.importing.n + " · " + esc(U.importing.name) + "</small></div>" : "") +
      sheetView() + (U.toast ? '<div class="toast">' + esc(U.toast) + "</div>" : "") + (L.error ? '<div class="toast">' + esc(L.error) + "</div>" : "");
    host.innerHTML = h;
    const l = host.querySelector(".list");
    if (l) l.scrollTop = sc;
    if (focusQ) { const q = host.querySelector("#q"); if (q) { q.focus(); try { q.setSelectionRange(caret, caret); } catch (e) { /* ignoré */ } } }
    const nm = host.querySelector("#nm");
    if (nm) { nm.focus(); nm.select(); }
    bindSeek();
  }
  function bindSeek() {
    const s = host.querySelector("#np-seek");
    if (!s) return;
    s.addEventListener("input", () => { seeking = true; const d = isFinite(audio.duration) ? audio.duration : 0; s.style.setProperty("--p", s.value / 10 + "%"); const c = host.querySelector("#np-cur"); if (c) c.textContent = fmt((s.value / 1000) * d); });
    s.addEventListener("change", () => { const d = isFinite(audio.duration) ? audio.duration : 0; if (d) audio.currentTime = (s.value / 1000) * d; seeking = false; });
  }

  // ---------- Sélecteurs de fichiers ----------
  function pickFiles(dir) {
    const i = document.createElement("input");
    i.type = "file";
    i.multiple = true;
    if (dir) { i.setAttribute("webkitdirectory", ""); i.setAttribute("directory", ""); }
    else i.accept = "audio/*,.mp3,.m4a,.aac,.wav,.flac,.ogg,.opus";
    i.style.display = "none";
    i.onchange = () => { const fl = [...i.files]; i.remove(); importFiles(fl); };
    document.body.appendChild(i);
    i.click();
  }

  // ---------- Actions ----------
  const sheet = (s) => { U.sheet = s; draw(); };
  function listOfRow() { return visibleTracks().map((t) => t.id); }
  function selToggle(id) { if (U.sel.has(id)) U.sel.delete(id); else U.sel.add(id); if (!U.sel.size) U.sel = null; draw(); }
  function newPlaylist(name, ids) {
    const p = { id: uid(), name: name.trim() || "Playlist", ids: ids || [] };
    L.playlists.push(p); savePl();
    return p;
  }
  let pending = null; // action en attente (confirmation, nom de playlist)

  host.addEventListener("click", (ev) => {
    const el = ev.target.closest("[data-a]");
    if (!el) return;
    const a = el.dataset.a, id = el.dataset.id;
    if (a === "menu" || a === "pp" || a === "prev" || a === "next" || a === "fav" || a === "plmenu") ev.stopPropagation();
    switch (a) {
      case "pick": pickFiles(false); break;
      case "pickdir": pickFiles(true); break;
      case "tab": U.tab = id; U.detail = null; U.q = ""; draw(); break;
      case "row": {
        if (U.sel) { selToggle(el.dataset.id); break; }
        const ids = listOfRow();
        playList(ids, ids.indexOf(el.dataset.id));
        break;
      }
      case "album": U.detail = { kind: "album", id }; draw(); break;
      case "playlist": U.detail = { kind: "playlist", id }; draw(); break;
      case "detailback": U.detail = null; draw(); break;
      case "playall": playList(listOfRow(), 0); break;
      case "shuffleall": playList(listOfRow(), 0, { shuffle: true }); break;
      case "sort": sheet({ kind: "sort" }); break;
      case "setsort": U.sort = id; U.sheet = null; draw(); break;
      case "menu": sheet({ kind: "menu", id, pl: U.detail && U.detail.kind === "playlist" ? U.detail.id : null }); break;
      case "plmenu": sheet({ kind: "plmenu", id }); break;
      case "sheetclose": U.sheet = null; draw(); break;
      case "np": U.np = true; draw(); break;
      case "npclose": U.np = false; draw(); break;
      case "pp": toggle(); break;
      case "prev": if (audio.currentTime > 4) audio.currentTime = 0; else step(-1); break;
      case "next": step(1); break;
      case "shuf": toggleShuffle(); break;
      case "rep": Q.repeat = Q.repeat === "off" ? "all" : Q.repeat === "all" ? "one" : "off"; draw(); break;
      case "speed": sheet({ kind: "speed" }); break;
      case "setspeed": Q.speed = parseFloat(id); audio.playbackRate = Q.speed; U.sheet = null; draw(); break;
      case "sleep": sheet({ kind: "sleep" }); break;
      case "setsleep": setSleep(parseInt(id, 10)); U.sheet = null; draw(); break;
      case "queue": sheet({ kind: "queue" }); break;
      case "qjump": Q.pos = parseInt(id, 10); loadTrack(cur(), true).then(() => { U.sheet = null; draw(); }); break;
      case "fav": if (L.favs.has(id)) L.favs.delete(id); else L.favs.add(id); saveFav(); draw(); break;
      case "m_next": enqueue([id], true); U.sheet = null; draw(); break;
      case "m_queue1": enqueue([id], false); U.sheet = null; draw(); break;
      case "m_addpl": case "addpl": sheet({ kind: "addpl", ids: [id] }); break;
      case "m_fav": if (L.favs.has(id)) L.favs.delete(id); else L.favs.add(id); saveFav(); U.sheet = null; draw(); break;
      case "m_rmpl": { const p = L.playlists.find((x) => x.id === U.sheet.pl); if (p) { p.ids = p.ids.filter((x) => x !== id); savePl(); } U.sheet = null; draw(); break; }
      case "m_info": sheet({ kind: "info", id }); break;
      case "m_del": pending = () => removeTracks([id]).then(() => { U.sheet = null; draw(); }); sheet({ kind: "confirm", title: "Supprimer ce titre ?", text: "Il sera retiré de la bibliothèque Casque (le fichier d'origine reste sur ton téléphone).", ok: "Supprimer" }); break;
      case "addto": {
        const p = L.playlists.find((x) => x.id === id);
        const ids = (U.sheet.ids || []).filter((x) => p && p.ids.indexOf(x) < 0);
        if (p) { p.ids.push(...ids); savePl(); toast(ids.length + " ajouté" + (ids.length > 1 ? "s" : "") + " à « " + p.name + " »"); }
        U.sheet = null; U.sel = null; draw(); break;
      }
      case "newpl": sheet({ kind: "name", title: "Nouvelle playlist" }); pending = (n) => { newPlaylist(n); U.sheet = null; draw(); }; break;
      case "newplAdd": { const ids = U.sheet.ids || []; sheet({ kind: "name", title: "Nouvelle playlist" }); pending = (n) => { newPlaylist(n, ids.slice()); U.sheet = null; U.sel = null; toast("Playlist créée"); draw(); }; break; }
      case "plplay": { const p = L.playlists.find((x) => x.id === id); U.sheet = null; if (p) playList(p.ids.filter(byId), 0); break; }
      case "plren": { const p = L.playlists.find((x) => x.id === id); if (p) { sheet({ kind: "name", title: "Renommer", value: p.name }); pending = (n) => { p.name = n.trim() || p.name; savePl(); U.sheet = null; draw(); }; } break; }
      case "pldel": { const p = L.playlists.find((x) => x.id === id); pending = () => { L.playlists = L.playlists.filter((x) => x.id !== id); savePl(); U.detail = null; U.sheet = null; draw(); }; sheet({ kind: "confirm", title: "Supprimer « " + (p ? p.name : "") + " » ?", text: "Les titres restent dans ta bibliothèque.", ok: "Supprimer" }); break; }
      case "namego": { const n = host.querySelector("#nm"); if (pending) pending(n ? n.value : ""); break; }
      case "confirmgo": if (pending) pending(); break;
      case "selcancel": U.sel = null; draw(); break;
      case "selplay": playList([...U.sel], 0); U.sel = null; draw(); break;
      case "seladd": sheet({ kind: "addpl", ids: [...U.sel] }); break;
      case "seldel": { const ids = [...U.sel]; pending = () => removeTracks(ids).then(() => { U.sel = null; U.sheet = null; draw(); }); sheet({ kind: "confirm", title: "Supprimer " + ids.length + " titre(s) ?", text: "Ils seront retirés de la bibliothèque Casque.", ok: "Supprimer" }); break; }
      case "settings": window.TMM_API ? window.TMM_API.casque.screen("settings") : (U.view = "settings", draw()); break;
      case "settingsback": window.TMM_API ? window.TMM_API.casque.screen("player") : (U.view = "player", draw()); break;
      case "help": if (window.TMM_API) window.TMM_API.casque.help(); break;
      case "clear": pending = () => removeTracks(L.tracks.map((t) => t.id)).then(() => { L.playlists = []; savePl(); U.sheet = null; toast("Bibliothèque vidée"); draw(); }); sheet({ kind: "confirm", title: "Vider la bibliothèque ?", text: "Tous les titres et playlists de Casque seront supprimés de cet appareil.", ok: "Tout supprimer" }); break;
      case "update": checkUpdate(); break;
      default: break;
    }
  });
  // Appui long sur un titre : sélection multiple.
  let lpT = null, lpDone = false;
  host.addEventListener("touchstart", (ev) => {
    const r = ev.target.closest(".tr[data-a=row]");
    lpDone = false;
    if (!r || U.sel) return;
    lpT = setTimeout(() => { lpDone = true; U.sel = new Set([r.dataset.id]); if (navigator.vibrate) navigator.vibrate(20); draw(); }, 520);
  }, { passive: true });
  ["touchend", "touchmove", "touchcancel"].forEach((e) => host.addEventListener(e, () => clearTimeout(lpT), { passive: true }));
  host.addEventListener("click", (ev) => { if (lpDone) { ev.stopPropagation(); ev.preventDefault(); lpDone = false; } }, true);
  host.addEventListener("input", (ev) => {
    if (ev.target.id === "q") { U.q = ev.target.value; draw(); }
  });
  host.addEventListener("keydown", (ev) => { if (ev.key === "Enter" && ev.target.id === "nm" && pending) pending(ev.target.value); });

  async function checkUpdate() {
    U.upd = "Recherche…"; draw();
    try {
      const r = await fetch("update.json?t=" + Date.now(), { cache: "no-store" });
      const j = await r.json();
      if (j.version && j.version !== CFG.VERSION) {
        U.upd = "Nouvelle version disponible : " + j.version + (j.notes ? " — " + j.notes : "") + ". Mise à jour…";
        draw();
        const ks = await caches.keys();
        await Promise.all(ks.map((k) => caches.delete(k)));
        const regs = await navigator.serviceWorker.getRegistrations();
        await Promise.all(regs.map((x) => x.unregister()));
        setTimeout(() => location.reload(), 900);
      } else { U.upd = "Casque est à jour (" + CFG.VERSION.replace(/^web /, "") + ")."; draw(); }
    } catch (e) { U.upd = "Impossible de vérifier : pas de connexion."; draw(); }
  }

  // ---------- Interface avec app.js ----------
  window.TMM_PLAYER = {
    show(screen) {
      const first = host.hidden;
      host.hidden = false;
      document.body.classList.add("cq-on");
      U.view = screen === "settings" ? "settings" : "player";
      if (first || true) draw();
    },
    hide() { host.hidden = true; document.body.classList.remove("cq-on"); },
  };
  loadAll();
})();
