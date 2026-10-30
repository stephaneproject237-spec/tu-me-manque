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
  const U = { tab: "titres", q: "", sort: "titre", sel: null, selDirs: null, detail: null, np: false, sheet: null, toast: "", importing: null, view: "player", upd: "",
    sec: lsGet("casque_sec") || "audio", miniHide: false, sr: { q: "", items: [], loading: false, src: "" } };
  try { const o = JSON.parse(lsGet("casque_sr") || "null"); if (o && o.items) U.sr = Object.assign(U.sr, o, { loading: false }); } catch (e) { /* ignoré */ }
  // ---------- Parrainage : lien d'invitation, parrain ----------
  try { const pm = /[?&]p=([A-Za-z0-9_]{2,30})/.exec(location.search); if (pm) lsSet("casque_p", pm[1]); } catch (e) { /* ignoré */ }
  const SP = { name: null, loaded: false, msg: "", busy: false };
  async function syncSponsor() {
    const A = API();
    if (!A || !A.casque.ready() || SP.busy) return;
    SP.busy = true;
    try {
      let r = await A.authed("POST", "/rest/v1/rpc/sponsor_get", {});
      SP.name = (r && r.name) || null;
      const want = lsGet("casque_p");
      if (!SP.name && want) {
        r = await A.authed("POST", "/rest/v1/rpc/sponsor_set", { p_name: want });
        if (r && (r.status === "ok" || r.status === "already")) SP.name = r.name || null;
        if (r && r.status !== "error") lsSet("casque_p", "");
      }
      SP.loaded = true;
    } catch (e) { /* hors ligne : on réessaiera */ }
    SP.busy = false;
    if (U.view === "settings") draw();
  }
  async function setSponsor() {
    const el = host.querySelector("#spin");
    const v = el ? el.value.trim().replace(/^@+/, "") : "";
    if (!v) return;
    const A = API();
    try {
      const r = await A.authed("POST", "/rest/v1/rpc/sponsor_set", { p_name: v });
      const st = r && r.status;
      if (st === "ok") { SP.name = r.name; SP.msg = "Ton parrain est @" + r.name + "."; }
      else if (st === "already") { SP.name = r.name; SP.msg = "Tu as déjà un parrain : @" + r.name + "."; }
      else if (st === "unknown") SP.msg = "Ce pseudo n'existe pas.";
      else if (st === "self") SP.msg = "Tu ne peux pas être ton propre parrain.";
      else SP.msg = "Impossible pour le moment.";
    } catch (e) { SP.msg = "Impossible pour le moment. Vérifie ta connexion."; }
    draw();
  }
  async function sendApp() {
    const A = API();
    const me = A && A.casque.username && A.casque.username();
    if (!me) return;
    const url = CFG.WEB_URL + "telecharger/?p=" + encodeURIComponent(me);
    const text = "Je t'invite sur Free tune 🎧 Musique, vidéos et messages entre amis. Télécharge l'application ici :\n" + url;
    try {
      if (navigator.share) { await navigator.share({ text }); return; }
      await navigator.clipboard.writeText(text);
      toast("Lien copié : colle-le dans un message.");
    } catch (e) { /* partage annulé */ }
  }
  // Demandes de téléchargement envoyées à un ami Android : {mid, ti, ch, th, f, fr, at, st, p, tid}
  let DL = []; try { DL = JSON.parse(lsGet("casque_dlreqs") || "[]") || []; } catch (e) { DL = []; }
  const saveDl = () => lsSet("casque_dlreqs", JSON.stringify(DL.slice(0, 60)));
  const AUD = () => L.tracks.filter((t) => !t.video);
  const Q = { order: [], pos: -1, shuffle: false, repeat: "off", speed: 1, sleepAt: 0, sleepT: null };
  const audio = new Audio();
  audio.preload = "auto";
  audio.setAttribute("playsinline", "");
  let curUrl = null, seeking = false;
  const covers = new Map();

  const byId = (id) => L.tracks.find((t) => t.id === id);
  const cur = () => (Q.pos >= 0 ? byId(Q.order[Q.pos]) : null);
  const coverUrl = (t) => {
    if (!t) return "";
    if (!t.cover) return t.art || "";
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
    pollSoon(2000);
    setTimeout(() => { if (U.sec === "search") loadPartner(); }, 1500);
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
  // Safari ajoute parfois « .download » (ou .crdownload, .part…) à un fichier téléchargé : « chanson.mp3.download » est bien un MP3.
  const EXT_MIME = { mp3: "audio/mpeg", m4a: "audio/mp4", mp4: "audio/mp4", aac: "audio/aac", wav: "audio/wav", flac: "audio/flac", ogg: "audio/ogg", oga: "audio/ogg", opus: "audio/ogg", caf: "audio/x-caf", aif: "audio/aiff", aiff: "audio/aiff" };
  const cleanName = (n) => String(n || "").replace(/(\.(download|crdownload|part|partial|tmp))+$/i, "");
  // Type du fichier : d'après son nom nettoyé, sinon d'après son contenu (ID3, trame MP3, M4A, WAV, FLAC, OGG).
  async function mimeOf(f) {
    const ext = (cleanName(f.name).match(/\.([a-z0-9]+)$/i) || [])[1];
    if (ext && EXT_MIME[ext.toLowerCase()]) return EXT_MIME[ext.toLowerCase()];
    if (f.type && f.type.indexOf("audio") === 0) return f.type;
    try {
      const b = new Uint8Array(await f.slice(0, 12).arrayBuffer());
      const a4 = String.fromCharCode(b[0], b[1], b[2], b[3]);
      if (a4.slice(0, 3) === "ID3" || (b[0] === 0xff && (b[1] & 0xe0) === 0xe0)) return "audio/mpeg";
      if (String.fromCharCode(b[4], b[5], b[6], b[7]) === "ftyp") return "audio/mp4";
      if (a4 === "RIFF") return "audio/wav";
      if (a4 === "fLaC") return "audio/flac";
      if (a4 === "OggS") return "audio/ogg";
    } catch (e) { /* ignoré */ }
    return "";
  }

  async function importFiles(list) {
    const files = [];
    for (const f of list) { const m = await mimeOf(f); if (m) files.push({ f, m }); }
    if (!files.length) { toast("Aucun fichier audio trouvé."); return; }
    if (!db) { toast(L.error || "Stockage indisponible."); return; }
    let added = 0, dup = 0;
    for (let i = 0; i < files.length; i++) {
      const name = cleanName(files[i].f.name);
      const mime = files[i].m;
      const f = files[i].f.slice(0, files[i].f.size, mime); // le même fichier, avec son vrai type
      f.name2 = name;
      U.importing = { i: i + 1, n: files.length, name };
      draw();
      if (L.tracks.some((t) => t.size === f.size && t.file === name)) { dup++; continue; }
      const base = name.replace(/\.[^.]+$/, "");
      const tg = await readTags(f);
      let title = tg.title || base, artist = tg.artist || "";
      if (!tg.title && /^(.+?)\s+-\s+(.+)$/.test(base)) { artist = artist || RegExp.$1; title = RegExp.$2; }
      const rel = files[i].f.webkitRelativePath || "";
      const folder = rel ? rel.split("/").slice(-2, -1)[0] || "" : "";
      const t = {
        id: uid(), file: name, size: f.size, mime, title, artist: artist || "Artiste inconnu",
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
    if (Q.pos >= 0) {
      const wasId = Q.order[Q.pos];
      const stillThere = !!byId(wasId);
      Q.order = Q.order.filter((x) => byId(x));
      if (stillThere) Q.pos = Q.order.indexOf(wasId);
      else { audio.pause(); audio.removeAttribute("src"); Q.pos = -1; Q.order = []; }
    }
    savePl(); saveFav();
  }
  // Supprimer un dossier : ses morceaux disparaissent de la bibliothèque, des playlists et des favoris.
  const removeFolders = (names) => removeTracks(L.tracks.filter((t) => names.indexOf(t.folder) >= 0).map((t) => t.id));

  // ---------- Lecture ----------
  async function loadTrack(t, autoplay) {
    const rec = await getOne("blobs", t.id);
    if (!rec) { toast("Fichier introuvable."); return false; }
    if (curUrl) URL.revokeObjectURL(curUrl);
    curUrl = URL.createObjectURL(t.mime && rec.blob.type !== t.mime ? new Blob([rec.blob], { type: t.mime }) : rec.blob);
    audio.src = curUrl;
    audio.playbackRate = Q.speed;
    if (autoplay) { try { await audio.play(); } catch (e) { /* le téléphone demande un geste */ } }
    updateSession();
    return true;
  }
  async function playList(ids, start, opts) {
    feelTick(true);
    dlRelease();
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
    if (DLP.active && cur() && cur().id === DLP.cur) { dlStep(d); return; }
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
  audio.addEventListener("play", () => {
    document.body.classList.add("cq-playing"); refreshPlayState();
    if (pvAudio) { pvAudio.pause(); pvAudio = null; U.pv = null; }
    if (V.el && !V.el.paused) V.el.pause();
    if (U.miniHide) { U.miniHide = false; draw(); }
  });
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
  // Boutons de l'écran verrouillé et du centre de contrôle : lecture, pause, précédent, suivant.
  function audioHandlers() {
    if (!("mediaSession" in navigator)) return;
    const set = (a, f) => { try { navigator.mediaSession.setActionHandler(a, f); } catch (e) { /* ignoré */ } };
    set("play", () => audio.play().catch(() => {}));
    set("pause", () => audio.pause());
    set("previoustrack", () => (audio.currentTime > 4 ? (audio.currentTime = 0) : step(-1)));
    set("nexttrack", () => step(1));
    set("seekto", (d) => { audio.currentTime = d.seekTime; });
    set("seekbackward", () => { audio.currentTime = Math.max(0, audio.currentTime - 10); });
    set("seekforward", () => { audio.currentTime = audio.currentTime + 10; });
    try { navigator.mediaSession.playbackState = audio.paused ? "paused" : "playing"; } catch (e) { /* ignoré */ }
  }
  audioHandlers();

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
    sort: sv('<path d="M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z"/>'),
    chev: sv('<path d="M10 6L8.6 7.4 13.2 12l-4.6 4.6L10 18l6-6z"/>'),
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
    trash: sv('<path d="M6 19a2 2 0 002 2h8a2 2 0 002-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/>'),
    close: sv('<path d="M19 6.4L17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z"/>'),
    folder: sv('<path d="M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8l-2-2z"/>'),
    disc: sv('<path d="M12 2a10 10 0 100 20 10 10 0 000-20zm0 14a4 4 0 110-8 4 4 0 010 8zm0-5.5a1.5 1.5 0 100 3 1.5 1.5 0 000-3z"/>'),
    head: sv('<path d="M12 3a9 9 0 00-9 9v7a3 3 0 003 3h1a2 2 0 002-2v-5a2 2 0 00-2-2H5v-1a7 7 0 0114 0v1h-2a2 2 0 00-2 2v5a2 2 0 002 2h1a3 3 0 003-3v-7a9 9 0 00-9-9z"/>', 28),
    chat: sv('<path d="M20 2H4a2 2 0 00-2 2v18l4-4h14a2 2 0 002-2V4a2 2 0 00-2-2z"/>'),
  };

  // ---------- Affichage ----------
  const visibleTracks = () => {
    let l = AUD();
    const d = U.detail;
    const byT = (x, y) => x.title.localeCompare(y.title, "fr");
    if (d) {
      if (d.kind === "album") l = l.filter((t) => t.album === d.id).sort(byT);
      else if (d.kind === "artist") l = l.filter((t) => t.artist === d.id).sort((x, y) => x.album.localeCompare(y.album, "fr") || byT(x, y));
      else if (d.kind === "folder") l = l.filter((t) => t.folder === d.id).sort(byT);
      else if (d.kind === "fav") l = [...L.favs].map(byId).filter((t) => t && !t.video);
      else if (d.kind === "recent") l = l.slice().sort((x, y) => y.added - x.added).slice(0, 50);
      else if (d.kind === "playlist") { const p = L.playlists.find((x) => x.id === d.id); l = p ? p.ids.map(byId).filter(Boolean) : []; }
      return l;
    }
    const q = U.q.trim().toLowerCase();
    if (q) l = l.filter((t) => (t.title + " " + t.artist + " " + t.album).toLowerCase().indexOf(q) >= 0);
    const k = U.sort;
    l = l.slice().sort((a, b) => k === "artiste" ? a.artist.localeCompare(b.artist, "fr") || a.album.localeCompare(b.album, "fr") || byT(a, b)
      : k === "album" ? a.album.localeCompare(b.album, "fr") || byT(a, b)
      : k === "recent" ? b.added - a.added : k === "duree" ? b.dur - a.dur : byT(a, b));
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
    return '<div class="es"><div class="esi hero">' + I.head + "</div><h3>Ta musique, partout</h3>" +
      "<p>Ajoute les fichiers audio (MP3, M4A, WAV…) de ton téléphone pour les écouter ici, avec playlists et lecture en arrière-plan.</p>" +
      '<button class="bt" data-a="pick">' + I.plus + " Ajouter des fichiers</button>" +
      (/iPhone|iPad/.test(navigator.userAgent) ? "" : '<button class="bt out" data-a="pickdir">' + I.folder + " Choisir un dossier source</button>") + "</div>";
  }
  function libView() {
    const tabs = ["titres", "albums", "artistes", "playlists", "dossiers"];
    const names = { titres: "Titres", albums: "Albums", artistes: "Artistes", playlists: "Playlists", dossiers: "Dossiers" };
    if (tabs.indexOf(U.tab) < 0) U.tab = "playlists";
    const SORTS = { titre: "Titre (A → Z)", artiste: "Artiste", album: "Album", recent: "Ajoutés récemment", duree: "Durée" };
    const pl = (n) => n + " morceau(x)";
    const act = (b1, b2) => '<div class="act2">' + b1 + b2 + "</div>";
    const btn = (a, ic, label, out) => '<button class="bt' + (out ? " out" : "") + '" data-a="' + a + '">' + ic + " " + label + "</button>";
    const chev = '<span class="chv">' + I.chev + "</span>";
    const prow = (attrs, icon, name, sub, cls) => '<div class="pr' + (cls || "") + '" ' + attrs + '><div class="pi">' + icon + '</div><div class="tm"><b>' + esc(name) + "</b><small>" + esc(sub) + "</small></div>" + chev + "</div>";
    const q = U.q.trim();
    let body = "";
    const d = U.detail;
    if (!AUD().length && !d) {
      body = emptyLib();
    } else if (d) {
      const l = visibleTracks();
      const tot = l.reduce((s2, t) => s2 + t.dur, 0);
      const p = d.kind === "playlist" ? L.playlists.find((x) => x.id === d.id) : null;
      body = '<div class="dhd"><div class="dcv">' + cover(l[0], "xl") + '</div><div class="dhi"><b>' + pl(l.length) + "</b><small>" + fmtTotal(tot) + "</small></div></div>" +
        act(btn("playall", I.play, "Lecture"), btn("shuffleall", I.shuffle, "Aléatoire", true)) +
        (p ? '<div class="act2"><button class="bt out" data-a="plren" data-id="' + p.id + '">Renommer</button><button class="bt out" data-a="pldel" data-id="' + p.id + '">Supprimer</button></div>' : "") +
        (l.length ? l.map((t, i) => row(t, l, i)).join("") : '<p class="note c">Rien ici pour l\'instant</p>');
    } else if (q) {
      const l = visibleTracks();
      body = l.length ? l.map((t, i) => row(t, l, i)).join("") : '<p class="note c">Aucun résultat pour « ' + esc(q) + " »</p>";
    } else if (U.tab === "albums") {
      const m = new Map();
      AUD().forEach((t) => { if (!m.has(t.album)) m.set(t.album, []); m.get(t.album).push(t); });
      const albums = [...m.entries()].sort((x, y) => x[0].toLowerCase().localeCompare(y[0].toLowerCase(), "fr"));
      body = albums.length ? '<div class="cgrid">' + albums.map(([a, l]) => '<div class="al" data-a="album" data-id="' + esc(a) + '">' + cover(l[0], "big") + "<b>" + esc(a) + "</b><small>" + esc(l[0].artist) + " · " + l.length + "</small></div>").join("") + "</div>"
        : '<p class="note c">Aucun album</p>';
    } else if (U.tab === "artistes") {
      const m = new Map();
      AUD().forEach((t) => { if (!m.has(t.artist)) m.set(t.artist, []); m.get(t.artist).push(t); });
      const arts = [...m.entries()].sort((x, y) => x[0].toLowerCase().localeCompare(y[0].toLowerCase(), "fr"));
      body = arts.length ? arts.map(([a, l]) => '<div class="pr" data-a="artist" data-id="' + esc(a) + '"><div class="pi round">' + esc(String(a).slice(0, 1).toUpperCase()) + '</div><div class="tm"><b>' + esc(a) + "</b><small>" + pl(l.length) + " · " + new Set(l.map((t) => t.album)).size + " album(s)</small></div>" + chev + "</div>").join("")
        : '<p class="note c">Aucun artiste</p>';
    } else if (U.tab === "playlists") {
      const favN = [...L.favs].filter((id) => { const t = byId(id); return t && !t.video; }).length;
      body = '<div class="pr" data-a="newpl"><div class="pi blue">' + I.plus + '</div><div class="tm"><b class="acc">Nouvelle playlist</b></div></div>' +
        prow('data-a="favs"', I.star, "Favoris", pl(favN)) +
        prow('data-a="recent"', I2.refresh, "Ajoutés récemment", "Les 50 derniers morceaux") +
        L.playlists.map((p) => prow('data-a="playlist" data-id="' + p.id + '"', I.list, p.name, pl(p.ids.length))).join("");
    } else if (U.tab === "dossiers") {
      const m = new Map();
      AUD().forEach((t) => { if (!m.has(t.folder)) m.set(t.folder, []); m.get(t.folder).push(t); });
      const dirs = [...m.entries()].sort((x, y) => x[0].toLowerCase().localeCompare(y[0].toLowerCase(), "fr"));
      body = '<div class="act2"><button class="bt out" data-a="pick">' + I2.dl + " Importer des fichiers</button></div>" +
        '<p class="sec2">TOUS LES DOSSIERS</p>' +
        (dirs.length ? dirs.map(([f, l]) => {
          const sel = U.selDirs && U.selDirs.has(f);
          return '<div class="pr' + (sel ? " sel" : "") + '" data-a="folder" data-id="' + esc(f) + '"><div class="pi' + (sel ? " blue" : "") + '">' + (sel ? I.check : I.folder) + '</div><div class="tm"><b>' + esc(String(f).split("/").pop()) + "</b><small>" + pl(l.length) + " · " + esc(f) + "</small></div>" + (U.selDirs ? "" : chev) + "</div>";
        }).join("") : '<p class="note">Aucun dossier pour l\'instant</p>');
    } else {
      const l = visibleTracks();
      const tot = AUD().reduce((s2, t) => s2 + t.dur, 0);
      body = act(btn("shuffleall", I.shuffle, "Aléatoire"), btn("playall", I.play, "Tout lire", true)) +
        '<div class="srow"><span>' + AUD().length + " morceaux · " + fmtTotal(tot) + '</span><button class="sortb" data-a="sort">' + I.sort + " " + esc(SORTS[U.sort] || SORTS.titre) + "</button></div>" +
        l.map((t, i) => row(t, l, i)).join("");
    }
    let head;
    if (U.selDirs) head = '<header class="top"><button class="ib gl" data-a="dircancel">' + I.close + "</button><h1>" + U.selDirs.size + " dossier" + (U.selDirs.size > 1 ? "s" : "") + '</h1><button class="ib gl del" data-a="dirdel">' + I.trash + "</button></header>";
    else if (U.sel) head = '<header class="top"><button class="ib gl" data-a="selcancel">' + I.close + "</button><h1>" + U.sel.size + " sélectionné" + (U.sel.size > 1 ? "s" : "") + "</h1>" +
      '<button class="ib gl" data-a="selplay">' + I.play + '</button><button class="ib gl" data-a="seladd">' + I.list + '</button><button class="ib gl del" data-a="seldel">' + I.trash + "</button></header>";
    else if (d) {
      const p = d.kind === "playlist" ? L.playlists.find((x) => x.id === d.id) : null;
      const title = p ? p.name : d.kind === "fav" ? "Favoris" : d.kind === "recent" ? "Ajoutés récemment" : d.kind === "folder" ? String(d.id).split("/").pop() : d.id;
      head = '<header class="top"><button class="ib gl" data-a="detailback" aria-label="Retour">' + I.back + '</button><h1 class="dt">' + esc(title) + "</h1></header>";
    } else head = big("Audios", '<button class="ib gl' + (U.searching ? " on" : "") + '" data-a="tsearch" aria-label="Rechercher">' + I.search + '</button><button class="ib gl" data-a="pick" aria-label="Ajouter des fichiers">' + I.plus + "</button>" + gear);
    const showTabs = !d && !U.sel && !U.selDirs;
    return head +
      (showTabs && U.searching ? '<div class="sr"><input id="q" type="search" placeholder="Chercher dans tes audios…" value="' + esc(U.q) + '" autocomplete="off" autocapitalize="none"></div>' : "") +
      (showTabs ? '<nav class="tabs">' + tabs.map((t) => '<button class="' + (U.tab === t && !q ? "on" : "") + '" data-a="tab" data-id="' + t + '">' + names[t] + "</button>").join("") + "</nav>" : "") +
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
      ('<div class="deck"><div class="vinyl' + (audio.paused ? "" : " on") + '"><div class="grooves"></div><div class="lab" style="' + (u ? "background-image:url(" + u + ")" : "") + '">' + (u ? "" : I.note) + '</div><div class="hole"></div></div>' +
      '<div class="arm' + (audio.paused ? "" : " on") + '"><i></i></div></div>') +
      '<div class="ti"><div><b>' + esc(t.title) + "</b><small>" + esc(t.artist) + " · " + esc(t.album) + '</small></div><button class="ib" data-a="fav" data-id="' + t.id + '">' + (L.favs.has(t.id) ? '<span class="fvo">' + I.star + "</span>" : I.starO) + "</button></div>" +
      '<input id="np-seek" class="seek" type="range" min="0" max="1000" value="' + p * 10 + '" style="--p:' + p + '%"><div class="tt"><span id="np-cur">' + fmt(audio.currentTime) + '</span><span id="np-tot">' + fmt(d) + "</span></div>" +
      '<div class="ctl"><button class="ib' + (Q.shuffle ? " act" : "") + '" data-a="shuf">' + I.shuffle + '</button><button class="ib big" data-a="prev">' + I.prev + '</button><button class="ib pp big" data-a="pp" data-pp>' + (audio.paused ? I.play : I.pause) +
      '</button><button class="ib big" data-a="next">' + I.next + '</button><button class="ib' + (Q.repeat !== "off" ? " act" : "") + '" data-a="rep">' + (Q.repeat === "one" ? I.repeat1 : I.repeat) + "</button></div>" +
      '<div class="opt"><button class="cpill" data-a="speed">' + Q.speed + '×</button><button class="cpill' + (Q.sleepAt ? " on" : "") + '" data-a="sleep">' + I.moon + (Q.sleepAt ? " " + Math.max(1, Math.round((Q.sleepAt - Date.now()) / 60000)) + " min" : " Minuterie") + '</button><button class="cpill" data-a="addpl" data-id="' + t.id + '">' + I.plus + " Playlist</button></div></div>";
  }
  function settingsView() {
    const size = L.tracks.reduce((s, t) => s + t.size, 0);
    return '<header class="top"><button class="ib" data-a="settingsback">' + I.back + "</button><h1>Paramètres</h1></header><main class=\"list set\">" +
      '<div class="card"><div class="logo big">' + I.head + "</div><h2>Free tune</h2><p>Version " + esc((CFG.VERSION || "").replace(/^web /, "")) + " (iPhone / web)</p></div>" +
      '<p class="sec">BIBLIOTHÈQUE</p><div class="card l"><p>' + L.tracks.length + " titre" + (L.tracks.length > 1 ? "s" : "") + " · " + (size / 1048576).toFixed(1) + " Mo</p>" +
      '<button class="bt out wide" data-a="pick">' + I.plus + " Ajouter des fichiers audio</button>" +
      (/iPhone|iPad/.test(navigator.userAgent) ? "" : '<button class="bt out wide" data-a="pickdir">' + I.folder + " Choisir un dossier source</button>") +
      '<button class="bt out wide" data-a="clear">Vider la bibliothèque</button></div>' +
      '<p class="sec">DÉFILEMENT</p><div class="card l"><p><b>Vibrations au défilement</b></p><p class="note">De petits clics, comme un engrenage, quand tu fais défiler les résultats (selon ton appareil).</p><button class="bt out wide" data-a="tickstog">' + (ticksOn() ? "Activées · touche pour couper" : "Désactivées · touche pour activer") + "</button></div>" +
      '<p class="sec">MES MÉDIAS</p><div class="card l"><p>Tout ce que tu télécharges est noté dans ton compte. Sur un nouvel appareil, retrouve tes musiques, tes vidéos et tes playlists.</p>' + (RS.msg ? '<p class="note">' + esc(RS.msg) + "</p>" : "") + '<button class="bt wide" data-a="restore">' + (RS.busy ? "Un instant…" : "Restaurer mes médias") + "</button></div>" +
      '<p class="sec">PARRAINAGE</p><div class="card l"><p>Envoie à tes amis un lien pour télécharger Free tune (toujours la dernière version), sur Android ou iPhone. Ils sauront que c\'est toi qui les as invités.</p><button class="bt wide" data-a="sendapp">Envoyer l\'application</button>' +
      '<p class="note" style="margin-top:14px"><b>Parrain</b></p>' +
      (SP.name ? '<p><b>@' + esc(SP.name) + "</b></p>" : SP.loaded ? '<p class="note">Tu n\'as pas de parrain. Si quelqu\'un t\'a fait découvrir Free tune, écris son pseudo :</p><input id="spin" class="ti2" maxlength="30" placeholder="Pseudo du parrain" autocapitalize="none" autocomplete="off"><button class="bt out wide" data-a="sponsorset">Valider</button>' : '<p class="note">…</p>') +
      (SP.msg ? '<p class="note">' + esc(SP.msg) + "</p>" : "") + "</div>" +
      '<p class="sec">APPLICATION</p><div class="card l"><button class="bt out wide" data-a="update">Rechercher une mise à jour</button>' + (U.upd ? '<p class="note">' + esc(U.upd) + "</p>" : "") + "</div>" +
      '<p class="sec">TCHAT</p><div class="card l"><button class="bt wide" data-a="help">' + I.chat + " Tchat</button></div>" +
      "</main>";
  }
  function sheetView() {
    const s = U.sheet;
    if (!s) return "";
    let h = "";
    const ps = plusSheet(s);
    if (ps != null) h = ps;
    else if (s.kind === "addpl") {
      h = "<h3>Ajouter à une playlist</h3>" + L.playlists.map((p) => '<button class="sh" data-a="addto" data-id="' + p.id + '">' + esc(p.name) + "<small>" + p.ids.length + "</small></button>").join("") + '<button class="sh acc" data-a="newplAdd">' + I.plus + " Nouvelle playlist</button>";
    } else if (s.kind === "menu") {
      const t = byId(s.id); if (!t) return "";
      h = "<h3>" + esc(t.title) + "</h3>" + [...(t.video ? [["vplay", "Regarder la vidéo"]] : []), ["next", "Lire ensuite"], ["queue1", "Ajouter à la file d'attente"], ["addpl", "Ajouter à une playlist…"], ["fav", L.favs.has(t.id) ? "Retirer des favoris" : "Ajouter aux favoris"], ...(s.pl ? [["rmpl", "Retirer de la playlist"], ["up", "Monter"], ["down", "Descendre"]] : []), ["share", "Partager / Enregistrer dans Fichiers…"], ["info", "Informations"], ["del", "Supprimer de la bibliothèque"]]
        .map(([a, l]) => '<button class="sh' + (a === "del" ? " red" : "") + '" data-a="m_' + a + '" data-id="' + t.id + '">' + l + "</button>").join("");
    } else if (s.kind === "plmenu") {
      h = "<h3>Playlist</h3>" + [["plplay", "Lire"], ["plren", "Renommer"], ["pldel", "Supprimer"]].map(([a, l]) => '<button class="sh' + (a === "pldel" ? " red" : "") + '" data-a="' + a + '" data-id="' + s.id + '">' + l + "</button>").join("");
    } else if (s.kind === "sort") {
      h = "<h3>Trier par</h3>" + [["titre", "Titre (A → Z)"], ["artiste", "Artiste"], ["album", "Album"], ["recent", "Ajoutés récemment"], ["duree", "Durée"]].map(([k, l]) => '<button class="sh' + (U.sort === k ? " acc" : "") + '" data-a="setsort" data-id="' + k + '">' + l + "</button>").join("");
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

  // Pendant un défilement on n'efface pas la page : on attend la fin du geste (sinon la liste saute et l'élan s'arrête).
  let lastScrollAt = 0, drawT = null, chRO = null;
  function draw() {
    if (U.sec === "search" && !host.hidden && L.ready && Date.now() - lastScrollAt < 220) { clearTimeout(drawT); drawT = setTimeout(draw, 240); return; }
    drawNow();
  }
  function drawNow() {
    if (host.hidden) return;
    const keepScroll = host.querySelector(".list");
    const sc = keepScroll ? keepScroll.scrollTop : 0;
    const fid = document.activeElement && document.activeElement.id;
    const focusQ = fid === "q" || fid === "ysq";
    const caret = focusQ ? document.activeElement.selectionStart : 0;
    let h;
    if (!L.ready) h = '<div class="empty"><div class="spin"></div></div>';
    else if (U.view === "settings") h = settingsView();
    else h = (U.sec === "video" ? videoView() : U.sec === "dl" ? dlView() : U.sec === "search" ? searchView() : libView()) + (U.np ? npView() : U.miniHide ? "" : miniView()) + dockView();
    h += (U.importing ? '<div class="imp"><div class="spin"></div><p>Ajout de la musique…</p><small>' + U.importing.i + " / " + U.importing.n + " · " + esc(U.importing.name) + "</small></div>" : "") +
      sheetView() + (U.toast ? '<div class="toast">' + esc(U.toast) + "</div>" : "") + (L.error ? '<div class="toast">' + esc(L.error) + "</div>" : "");
    host.innerHTML = h;
    const l = host.querySelector(".list");
    if (l) {
      // Tout ce qui est au-dessus de la liste (titre, recherche, Titres/Albums…) devient un en-tête en verre flottant :
      // la liste remplit tout l'écran et défile dessous, jusqu'en haut et jusqu'en bas.
      const top = [];
      for (let e = l.previousElementSibling; e; e = e.previousElementSibling) top.unshift(e);
      if (top.length) {
        const ch = document.createElement("div");
        ch.className = "chrome";
        top.forEach((e) => ch.appendChild(e));
        l.parentNode.insertBefore(ch, l);
        l.style.paddingTop = ch.offsetHeight + 6 + "px";
        if (window.ResizeObserver) {
          if (chRO) chRO.disconnect();
          chRO = new ResizeObserver(() => {
            const c = host.querySelector(".chrome"), ll = host.querySelector(".list");
            if (c && ll && Math.abs((parseFloat(ll.style.paddingTop) || 0) - (c.offsetHeight + 6)) > 1) ll.style.paddingTop = c.offsetHeight + 6 + "px";
          });
          chRO.observe(ch);
        }
        placeSgo();
      }
      l.scrollTop = sc;
      chromeShade(l);
    }
    if (focusQ) { const q = host.querySelector("#" + fid); if (q) { q.focus(); try { q.setSelectionRange(caret, caret); } catch (e) { /* ignoré */ } } }
    const nm = host.querySelector("#nm");
    if (nm) { nm.focus(); nm.select(); }
    bindSeek();
    bindMini();
    placeVideo();
  }
  // L'en-tête devient plus opaque dès que le contenu passe dessous.
  function chromeShade(l) {
    const ch = host.querySelector(".chrome");
    if (ch && l) ch.classList.toggle("sc", l.scrollTop > 4);
  }
  host.addEventListener("scroll", (e) => { if (e.target.classList && e.target.classList.contains("list")) chromeShade(e.target); }, true);
  // Barre de lecture glissée vers le bas : elle disparaît, et revient dès qu'on relance un titre.
  function bindMini() {
    const m = host.querySelector(".mini");
    if (!m) return;
    let y0 = null, dy = 0;
    m.addEventListener("touchstart", (e) => { y0 = e.touches[0].clientY; dy = 0; m.style.transition = "none"; }, { passive: true });
    m.addEventListener("touchmove", (e) => { if (y0 == null) return; dy = Math.max(0, e.touches[0].clientY - y0); m.style.transform = "translateY(" + dy + "px)"; m.style.opacity = String(Math.max(0.2, 1 - dy / 160)); }, { passive: true });
    m.addEventListener("touchend", () => {
      m.style.transition = "transform .2s, opacity .2s";
      if (dy > 50) { m.style.transform = "translateY(140px)"; m.style.opacity = "0"; setTimeout(() => { U.miniHide = true; audio.pause(); draw(); }, 180); }
      else { m.style.transform = ""; m.style.opacity = ""; }
      y0 = null;
    }, { passive: true });
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
    // iPhone : pas de filtre, sinon les fichiers « .mp3.download » restent grisés dans le sélecteur.
    else if (!/iPhone|iPad/.test(navigator.userAgent)) i.accept = "audio/*,.mp3,.m4a,.aac,.wav,.flac,.ogg,.opus,.download,.crdownload";
    i.style.display = "none";
    i.onchange = () => { const fl = [...i.files]; i.remove(); importFiles(fl); };
    document.body.appendChild(i);
    i.click();
  }

  // ---------- Actions ----------
  const sheet = (s) => { U.sheet = s; draw(); };
  function listOfRow() {
    if (U.sec === "dl") return L.tracks.filter((t) => t.dl && !t.video).sort((a, b) => b.added - a.added).map((t) => t.id);
    return visibleTracks().map((t) => t.id);
  }
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
    if (a === "ysel" || a === "dcvid" || a === "dctrack" || a === "lplay" || a === "row" || a === "yplay" || a === "dcalb" || a === "dcgen" || a === "dcseason" || a === "yget") { feelTick(true); el.classList.remove("tapg"); void el.offsetWidth; el.classList.add("tapg"); }
    if (a === "menu" || a === "pp" || a === "prev" || a === "next" || a === "fav" || a === "plmenu" || a === "dlmenu" || a === "dlrm" || a === "yfill" || a === "dlretry") ev.stopPropagation();
    if (plusClick(a, id, el)) return;
    switch (a) {
      case "pick": pickFiles(false); break;
      case "pickdir": pickFiles(true); break;
      case "tab": U.tab = id; U.detail = null; U.q = ""; U.sel = null; U.selDirs = null; draw(); break;
      case "row": {
        if (U.sel) { selToggle(el.dataset.id); break; }
        const ids = listOfRow();
        playList(ids, ids.indexOf(el.dataset.id));
        break;
      }
      case "album": U.detail = { kind: "album", id }; draw(); break;
      case "artist": U.detail = { kind: "artist", id }; draw(); break;
      case "favs": U.detail = { kind: "fav", id: "fav" }; draw(); break;
      case "recent": U.detail = { kind: "recent", id: "recent" }; draw(); break;
      case "tsearch": {
        U.searching = !U.searching;
        if (!U.searching) U.q = ""; else U.tab = "titres";
        drawNow();
        if (U.searching) { const qi = host.querySelector("#q"); if (qi) qi.focus(); }
        break;
      }
      case "folder": {
        if (U.selDirs) { if (U.selDirs.has(id)) U.selDirs.delete(id); else U.selDirs.add(id); if (!U.selDirs.size) U.selDirs = null; draw(); }
        else { U.detail = { kind: "folder", id }; draw(); }
        break;
      }
      case "dircancel": U.selDirs = null; draw(); break;
      case "dirdel": {
        const names = [...U.selDirs];
        const n = L.tracks.filter((t) => names.indexOf(t.folder) >= 0).length;
        pending = () => removeFolders(names).then(() => { U.selDirs = null; U.sheet = null; draw(); });
        sheet({ kind: "confirm", title: names.length > 1 ? "Supprimer " + names.length + " dossiers ?" : "Supprimer ce dossier ?", text: "Les " + n + " titre(s) qu'il contient n'apparaîtront plus dans la bibliothèque ni dans les playlists. Les fichiers d'origine restent sur ton téléphone.", ok: "Supprimer" });
        break;
      }
      case "playlist": U.detail = { kind: "playlist", id }; draw(); break;
      case "detailback": U.detail = null; draw(); break;
      case "playall": playList(listOfRow(), 0); break;
      case "shuffleall": playList(listOfRow(), 0, { shuffle: true }); break;
      case "sort": sheet({ kind: "sort" }); break;
      case "setsort": U.sort = id; U.sheet = null; draw(); break;
      case "menu": prepShare(id); sheet({ kind: "menu", id, pl: U.detail && U.detail.kind === "playlist" ? U.detail.id : null }); break;
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
      case "m_up": case "m_down": { const p = L.playlists.find((x) => x.id === U.sheet.pl); if (p) { const i = p.ids.indexOf(id), j = a === "m_up" ? i - 1 : i + 1; if (i >= 0 && j >= 0 && j < p.ids.length) { const x = p.ids[i]; p.ids[i] = p.ids[j]; p.ids[j] = x; savePl(); } } U.sheet = null; draw(); break; }
      case "m_rmpl": { const p = L.playlists.find((x) => x.id === U.sheet.pl); if (p) { p.ids = p.ids.filter((x) => x !== id); savePl(); } U.sheet = null; draw(); break; }
      case "m_info": sheet({ kind: "info", id }); break;
      case "m_del": pending = () => removeTracks([id]).then(() => { U.sheet = null; draw(); }); sheet({ kind: "confirm", title: "Supprimer ce titre ?", text: "Il sera retiré de la bibliothèque Free tune (le fichier d'origine reste sur ton téléphone).", ok: "Supprimer" }); break;
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
      case "seldel": { const ids = [...U.sel]; pending = () => removeTracks(ids).then(() => { U.sel = null; U.sheet = null; draw(); }); sheet({ kind: "confirm", title: "Supprimer " + ids.length + " titre(s) ?", text: "Ils seront retirés de la bibliothèque Free tune.", ok: "Supprimer" }); break; }
      case "settings": window.TMM_API ? window.TMM_API.casque.screen("settings") : (U.view = "settings", draw()); break;
      case "settingsback": window.TMM_API ? window.TMM_API.casque.screen("player") : (U.view = "player", draw()); break;
      case "help": if (window.TMM_API) window.TMM_API.casque.help(); break;
      case "clear": pending = () => removeTracks(L.tracks.map((t) => t.id)).then(() => { L.playlists = []; savePl(); U.sheet = null; toast("Bibliothèque vidée"); draw(); }); sheet({ kind: "confirm", title: "Vider la bibliothèque ?", text: "Tous les titres et playlists de Free tune seront supprimés de cet appareil.", ok: "Tout supprimer" }); break;
      case "update": checkUpdate(); break;
      case "sendapp": sendApp(); break;
      case "tickstog": lsSet("casque_ticks", ticksOn() ? "0" : "1"); if (ticksOn()) feelTick(true); draw(); break;
      case "restore": if (!RS.busy) mediaSummary(); break;
      case "sponsorset": setSponsor(); break;
      default: break;
    }
  });
  // Appui long sur un titre : sélection multiple.
  let lpT = null, lpDone = false;
  host.addEventListener("touchstart", (ev) => {
    const r = ev.target.closest(".tr[data-a=row], .tr[data-a=folder]");
    lpDone = false;
    if (!r) return;
    const dir = r.dataset.a === "folder";
    if (dir ? U.selDirs : U.sel) return;
    lpT = setTimeout(() => {
      lpDone = true;
      if (dir) U.selDirs = new Set([r.dataset.id]); else U.sel = new Set([r.dataset.id]);
      if (navigator.vibrate) navigator.vibrate(20);
      draw();
    }, 520);
  }, { passive: true });
  ["touchend", "touchmove", "touchcancel"].forEach((e) => host.addEventListener(e, () => clearTimeout(lpT), { passive: true }));
  host.addEventListener("click", (ev) => { if (lpDone) { ev.stopPropagation(); ev.preventDefault(); lpDone = false; } }, true);
  host.addEventListener("input", (ev) => {
    if (ev.target.id === "q") { U.q = ev.target.value; draw(); }
    else if (ev.target.id === "ysq") {
      U.sr.q = ev.target.value; U.sr.focus = true;
      if (U.sr.q.trim() && U.sr.q.trim() !== U.sr.done) { suggest(U.sr.q); drawSug(); }
    }
  });
  host.addEventListener("submit", (ev) => { if (ev.target.id === "ysf") { ev.preventDefault(); const i = host.querySelector("#ysq"); doSearch(i ? i.value : U.sr.q); } });
  host.addEventListener("focusin", (ev) => { if (ev.target.id === "ysq") U.sr.focus = true; });
  host.addEventListener("focusout", (ev) => {
    if (ev.target.id !== "ysq") return;
    setTimeout(() => { if (U.sr.focus && !(document.activeElement && document.activeElement.id === "ysq")) { U.sr.focus = false; draw(); } }, 350);
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
      } else { U.upd = "Free tune est à jour (" + CFG.VERSION.replace(/^web /, "") + ")."; draw(); }
    } catch (e) { U.upd = "Impossible de vérifier : pas de connexion."; draw(); }
  }

  // =====================================================================
  // Fonctions de Casque Android portées sur iPhone (même mise en page que l'appli Android) :
  // Rechercher (lecteur en haut, formats, autres résultats), Vidéos, Téléchargements,
  // vidéo flottante, pochettes, paroles karaoké.
  // L'iPhone ne télécharge pas lui-même : la demande part dans « Tu me manques »,
  // et le téléphone Android du partenaire télécharge puis renvoie le fichier.
  // =====================================================================
  const API = () => window.TMM_API;
  const I2 = {
    film: sv('<path d="M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V4h-4z"/>'),
    dl: sv('<path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/>'),
    share: sv('<path d="M16 5l-1.4 1.4-1.6-1.6V16h-2V4.8L9.4 6.4 8 5l4-4 4 4zm4 5v11a2 2 0 01-2 2H6a2 2 0 01-2-2V10a2 2 0 012-2h3v2H6v11h12V10h-3V8h3a2 2 0 012 2z"/>'),
    pip: sv('<path d="M19 11h-8v6h8v-6zm4 8V5a2 2 0 00-2-2H3a2 2 0 00-2 2v14a2 2 0 002 2h18a2 2 0 002-2zm-2 0H3V5h18v14z"/>'),
    mic: sv('<path d="M12 14a3 3 0 003-3V5a3 3 0 00-6 0v6a3 3 0 003 3zm5-3a5 5 0 01-10 0H5a7 7 0 006 6.9V21h2v-3.1A7 7 0 0019 11h-2z"/>'),
    full: sv('<path d="M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z"/>'),
    hist: sv('<path d="M13 3a9 9 0 00-9 9H1l3.9 3.9L9 12H6a7 7 0 112.1 5l-1.4 1.4A9 9 0 1013 3zm-1 5v5l4.3 2.5.7-1.2-3.5-2.1V8z"/>'),
    nw: sv('<path d="M9 5v2h6.6L4 18.6 5.4 20 17 8.4V15h2V5z" transform="rotate(-90 12 12)"/>'),
    refresh: sv('<path d="M17.7 6.3A8 8 0 104 12h2a6 6 0 111.8 4.2L10 14H4v6l2.3-2.3A8 8 0 1017.7 6.3z"/>'),
  };
  const mediaUrl = (path) => (CFG.SUPABASE_URL || "") + "/storage/v1/object/public/chat/" + path;
  const safe = (n) => String(n || "fichier").replace(/[\\/:*?"<>|\n\r\t]/g, " ").replace(/\s+/g, " ").trim().slice(0, 80) || "fichier";
  const putTrack = (t) => db && tx("tracks", "readwrite", (x) => x.objectStore("tracks").put(t)).catch(() => {});
  const fmtViews = (n) => !n ? "" : n >= 1e9 ? (n / 1e9).toFixed(1).replace(".0", "") + " Md de vues" : n >= 1e6 ? (n / 1e6).toFixed(1).replace(".0", "") + " M de vues" : n >= 1e3 ? Math.round(n / 1e3) + " k vues" : n + " vues";

  // ---------- Ajout d'un fichier reçu dans la bibliothèque ----------
  async function importBlob(blob, o) {
    if (!db) throw new Error("db");
    // Un audio peut arriver en MP3 ou en AAC (.m4a, plus rapide à préparer) : on reconnaît le format au contenu du fichier.
    let aac = false;
    if (!o.video) { try { const h = new Uint8Array(await blob.slice(0, 12).arrayBuffer()); aac = h[4] === 0x66 && h[5] === 0x74 && h[6] === 0x79 && h[7] === 0x70; } catch (e) { aac = false; } }
    const mime = o.video ? "video/mp4" : aac ? "audio/mp4" : "audio/mpeg";
    const f = blob.type === mime ? blob : new Blob([blob], { type: mime });
    let cv = null;
    if (o.art) { try { const r = await fetch(o.art, { mode: "cors" }); if (r.ok) { const b = await r.blob(); if (/^image\//.test(b.type) && b.size > 500) cv = b; } } catch (e) { /* pochette en ligne seulement */ } }
    const t = {
      id: uid(), file: safe(o.title) + (o.video ? ".mp4" : aac ? ".m4a" : ".mp3"), size: f.size, mime, title: o.title || "Sans titre", artist: o.artist || "Artiste inconnu",
      album: o.video ? "Vidéos" : "Téléchargements", folder: "Téléchargements", dur: o.d || (o.video ? 0 : await duration(f)), added: Date.now(),
      cover: cv, art: o.art || "", video: !!o.video, dl: true, yid: o.yid || "", fk: o.fk || "",
    };
    await tx("blobs", "readwrite", (x) => x.objectStore("blobs").put({ id: t.id, blob: f }));
    await tx("tracks", "readwrite", (x) => x.objectStore("tracks").put(t));
    L.tracks.push(t);
    if (!o.video) {
      let p = L.playlists.find((x) => x.name === "Téléchargements");
      if (!p) p = newPlaylist("Téléchargements");
      if (p.ids.indexOf(t.id) < 0) p.ids.unshift(t.id);
      savePl();
    }
    return t;
  }

  // ---------- Lecteur vidéo unique : dans la page Rechercher, en bulle flottante, ou en plein écran ----------
  // L'élément reste le même (la vidéo ne s'arrête pas) : on le déplace seulement à l'écran.
  const vbox = document.createElement("div");
  vbox.id = "cq-video";
  vbox.hidden = true;
  document.body.appendChild(vbox);
  const V = { key: null, yid: null, title: "", el: null, full: false, url: null, dx: 0, dy: 0, hid: false, bub: false, auto: lsGet("casque_auto") === "1", played: [], hist: [], dl: false, back: false };
  // Lecture depuis Téléchargements (comme Android) : un fichier lance le suivant (ordre de téléchargement) quand il se termine.
  const DLP = { active: false, cur: null, internal: false };
  function dlPlayable() {
    return DL.filter((d) => d.st === "done" && d.tid && byId(d.tid)).sort((a, b) => (a.at || 0) - (b.at || 0)).map((d) => byId(d.tid));
  }
  function dlStart(t, row) { DLP.active = true; dlGo(t, row); }
  function dlGo(t, row) {
    DLP.cur = t.id; DLP.internal = true;
    try {
      if (t.video) { audio.pause(); playFile(t, { dl: true, row }); }
      else { if (V.key) stopVideo(); playList([t.id], 0); }
    } finally { DLP.internal = false; }
  }
  function dlStep(d) {
    const l = dlPlayable();
    const i = l.findIndex((x) => x.id === DLP.cur);
    const n = i < 0 ? 0 : i + d;
    if (n >= 0 && n < l.length) dlGo(l[n]); else if (d > 0) { DLP.active = false; if (!V.key) { audio.pause(); draw(); } }
  }
  function dlRelease() { if (!DLP.internal) DLP.active = false; }
  // Suivant / précédent existent pour une vidéo YouTube, ou un fichier lancé depuis Téléchargements.
  // File des vidéos de l'écran Vidéos (même ordre que la grille) pour les boutons précédent / suivant.
  const VL = { on: false };
  const vlList = () => L.tracks.filter((t) => t.video).sort((a, b) => b.added - a.added);
  function vlStep(d) {
    const l = vlList(), i = l.findIndex((t) => "file:" + t.id === V.key), n = i + d;
    if (i < 0 || n < 0 || n >= l.length) { if (d < 0 && V.el) V.el.currentTime = 0; return; }
    const wasFull = V.full; playFile(l[n], { vl: true }); V.full = wasFull; placeVideo();
  }
  // Précédent / suivant sur la page Rechercher : pendant une vidéo, on enchaîne comme d'habitude ; sinon on passe au résultat voisin et il se lance seul.
  function selStep(d) {
    if (V.yid) {
      if (d > 0) { vNext(); return; }
      const e = V.el;
      if ((e && e.currentTime > 3) || V.hist.length) { vPrev(); return; }
    }
    const l = SR.items.filter((x) => x.src === "yt"), i = l.findIndex((x) => x.id === SR.sel), n = i < 0 ? 0 : i + d;
    if (n < 0 || n >= l.length) { toast(d > 0 ? "C'est le dernier résultat." : "C'est le premier résultat."); return; }
    const o = l[n]; SR.sel = o.id; saveSr(); draw(); loadRelated(o); playYt(o); draw();
  }
  const canStep = () => !!V.key && (!!V.yid || DLP.active || VL.on);
  function vNext() { if (V.yid) nextRelated(true); else if (VL.on) vlStep(1); else if (DLP.active) dlStep(1); }
  function vPrev() {
    const e = bgOn ? bgA : V.el;
    if (e && e.currentTime > 3) { e.currentTime = 0; return; }
    if (!V.yid) { if (VL.on) vlStep(-1); else if (DLP.active) dlStep(-1); else if (e) e.currentTime = 0; return; }
    const p = V.hist.pop();
    if (!p) { if (e) e.currentTime = 0; return; }
    V.back = true; const wasFull = V.full;
    playYt(p).then(() => { V.full = wasFull; placeVideo(); if (U.sec === "search") draw(); });
  }
  // L'écran reste allumé pendant qu'une vidéo joue.
  let wake = null;
  function wakeOn() { try { if (!wake && navigator.wakeLock) navigator.wakeLock.request("screen").then((w) => { wake = w; w.addEventListener("release", () => { wake = null; }); }).catch(() => {}); } catch (e) { /* ignoré */ } }
  function wakeOff() { try { if (wake) wake.release(); } catch (e) { /* ignoré */ } wake = null; }
  // Languette sur le bord gauche : la bulle poussée à gauche est cachée, le son continue.
  const vtab = document.createElement("button");
  vtab.id = "cq-vtab"; vtab.hidden = true; vtab.setAttribute("aria-label", "Rappeler la vidéo");
  document.body.appendChild(vtab);
  vtab.addEventListener("click", () => { V.hid = false; V.dx = 0; placeVideo(); });
  const PIPED = ["https://pipedapi.kavin.rocks", "https://pipedapi.adminforge.de", "https://api.piped.private.coffee"];
  const INV = ["https://inv.nadeko.net", "https://invidious.nerdvpn.de", "https://yewtu.be", "https://invidious.privacyredirect.com"];
  const withTimeout = (p, ms) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error("timeout")), ms))]);
  const anyOf = (ps) => new Promise((ok, ko) => { let n = ps.length; if (!n) ko(new Error("vide")); ps.forEach((p) => p.then(ok, () => { if (--n === 0) ko(new Error("aucun")); })); });
  // Flux MP4 direct (lu par le lecteur de l'iPhone : image dans l'image possible). Sinon : lecteur YouTube intégré.
  async function ytStream(id) {
    const tries = [];
    PIPED.forEach((b) => tries.push(withTimeout(fetch(b + "/streams/" + id).then((r) => r.json()), 8000).then((j) => {
      const s = ((j && j.videoStreams) || []).filter((x) => !x.videoOnly && x.url && /mp4/i.test(x.mimeType || x.format || ""));
      s.sort((a, b) => (parseInt(b.quality, 10) || 0) - (parseInt(a.quality, 10) || 0));
      if (!s.length) throw new Error("pas de flux");
      return s[0].url;
    })));
    INV.forEach((b) => tries.push(withTimeout(fetch(b + "/api/v1/videos/" + id + "?local=true&fields=formatStreams").then((r) => r.json()), 8000).then((j) => {
      const f = ((j && j.formatStreams) || []).filter((x) => x.url);
      const best = f.find((x) => String(x.itag) === "22") || f.find((x) => String(x.itag) === "18") || f[0];
      if (!best) throw new Error("pas de flux");
      return /^https?:/.test(best.url) ? best.url : b + best.url;
    })));
    return anyOf(tries);
  }
  function videoShell(inner) {
    vbox.innerHTML = '<div class="vb-media">' + inner + "</div>" +
      '<div class="vb-ctl"><button data-v="close" class="tl" aria-label="Fermer">' + I.close + '</button><button data-v="full" class="tr" aria-label="Plein écran">' + I2.full + "</button>" +
      '<button data-v="pp" class="mid" aria-label="Lecture">' + I.pause + "</button>" +
      (canStep() ? '<button data-v="prev" class="pv" aria-label="Précédent">' + I.prev + '</button><button data-v="next" class="nx" aria-label="Suivant">' + I.next + "</button>" : "") + "</div>" +
      '<div class="vb-bar"><button data-v="min" aria-label="Réduire">' + I.down + "</button><b>" + esc(V.title) + '</b>' + (canStep() ? '<button data-v="prev" aria-label="Précédent">' + I.prev + '</button><button data-v="next" aria-label="Suivant">' + I.next + "</button>" : "") + '<button data-v="auto" class="' + (V.auto ? "on" : "") + '" aria-label="Lecture continue">' + I.repeat + '</button><button data-v="pip" aria-label="Image dans l\'image">' + I2.pip + '</button><button data-v="close" aria-label="Fermer">' + I.close + "</button></div>" +
      '<div class="vb-grip" aria-hidden="true"></div>' +
      '<button data-v="bub" class="vb-min" aria-label="Réduire en bulle">' + I.down + '</button>' +
      '<button data-v="hide" class="vb-hide" aria-label="Cacher la bulle">' + I.down + "</button>";
    vbox.hidden = false;
  }
  // Un seul lecteur vidéo réutilisé : débloqué une fois par un geste (toucher), il peut ensuite lancer chaque nouvelle vidéo tout seul
  // (suivant, précédent, résultat touché, fin de vidéo) sans que l'iPhone demande de retoucher l'écran.
  const POOL = document.createElement("video");
  POOL.setAttribute("playsinline", ""); POOL.setAttribute("x-webkit-airplay", "allow"); POOL.controls = true; POOL.autoplay = true;
  let poolOk = false;
  function unlockPool() {
    if (poolOk || V.key) return;
    try {
      POOL.muted = false; POOL.src = "data:video/mp4;base64,AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAXdbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAAfQAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwAAAnJ0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAAfQAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAEAAAABAAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAH0AAAAAAABAAAAAAHqbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAoAAAAFABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABlW1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAVVzdGJsAAAAuXN0c2QAAAAAAAAAAQAAAKlhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAQABIAAAASAAAAAAAAAABFUxhdmM2MC4zMS4xMDIgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAAL2F2Y0MBQsAK/+EAF2dCwArZBCbARAAAAwAEAAADAFA8SJkgAQAFaMuDyyAAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAArcAAAK3AAAAAYc3R0cwAAAAAAAAABAAAABQAABAAAAAAUc3RzcwAAAAAAAAABAAAAAQAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEAAAAoc3RzegAAAAAAAAAAAAAABQAAAo8AAAAKAAAACgAAAAoAAAAKAAAAJHN0Y28AAAAAAAAABQAABiIAAAi9AAAIzwAACOEAAAjzAAAClXRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAIAAAAAAAAB9AAAAAAAAAAAAAAAAQEAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQAAAfQAAAQAAAEAAAAAAg1tZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAFYiAAAvEVXEAAAAAAAtaGRscgAAAAAAAAAAc291bgAAAAAAAAAAAAAAAFNvdW5kSGFuZGxlcgAAAAG4bWluZgAAABBzbWhkAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJsIAAAAAEAAAF8c3RibAAAAH5zdHNkAAAAAAAAAAEAAABubXA0YQAAAAAAAAABAAAAAAAAAAAAAQAQAAAAAFYiAAAAAAA2ZXNkcwAAAAADgICAJQACAASAgIAXQBUAAAAAAB9AAAADtwWAgIAFE4hW5QAGgICAAQIAAAAUYnRydAAAAAAAAB9AAAADtwAAACBzdHRzAAAAAAAAAAIAAAALAAAEAAAAAAEAAAMRAAAANHN0c2MAAAAAAAAAAwAAAAEAAAABAAAAAQAAAAIAAAADAAAAAQAAAAMAAAACAAAAAQAAAERzdHN6AAAAAAAAAAAAAAAMAAAAFQAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAAKHN0Y28AAAAAAAAABgAABg0AAAixAAAIxwAACNkAAAjrAAAI/QAAABpzZ3BkAQAAAHJvbGwAAAACAAAAAf//AAAAHHNiZ3AAAAAAcm9sbAAAAAEAAAAMAAAAAQAAAGJ1ZHRhAAAAWm1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALWlsc3QAAAAlqXRvbwAAAB1kYXRhAAAAAQAAAABMYXZmNjAuMTYuMTAwAAAACGZyZWUAAAMAbWRhdN4CAExhdmM2MC4zMS4xMDIAAjBADgAAAnEGBf//bdxF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjQgcjMxMDggMzFlMTlmOSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjMgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0wIHJlZj0zIGRlYmxvY2s9MTowOjAgYW5hbHlzZT0weDE6MHgxMTEgbWU9aGV4IHN1Ym1lPTcgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MSBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9MSB0cmVsbGlzPTEgOHg4ZGN0PTAgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9LTIgdGhyZWFkcz0yIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MCB3ZWlnaHRwPTAga2V5aW50PTI1MCBrZXlpbnRfbWluPTEwIHNjZW5lY3V0PTQwIGludHJhX3JlZnJlc2g9MCByY19sb29rYWhlYWQ9NDAgcmM9Y3JmIG1idHJlZT0xIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAAAFmWIhA/yYoAAw+ycnJ1111111111114BGCAHARggBwEYIAcAAAAGQZo4H+EYARggBwEYIAcAAAAGQZpUB/hGARggBwEYIAcAAAAGQZpgO8IwARggBwEYIAcAAAAGQZqAN8IwARggBwEYIAc=";
      const pr = POOL.play(); if (pr && pr.then) pr.then(() => { poolOk = true; }).catch(() => {});
    } catch (e) { /* ignoré */ }
  }
  function takeVideo() {
    try { POOL.pause(); } catch (e) { /* ignoré */ }
    POOL.removeAttribute("src"); POOL.muted = false; POOL.onerror = null;
    vbox.querySelector(".vb-media").appendChild(POOL);
    return POOL;
  }
  // Si l'iPhone refuse quand même le son, la vidéo démarre sans son et le son revient au premier toucher.
  function startPlay(v) {
    const pr = v.play();
    if (pr && pr.catch) pr.catch(() => {
      v.muted = true;
      v.play().then(() => { const un = () => { v.muted = false; document.removeEventListener("touchstart", un, true); }; document.addEventListener("touchstart", un, true); }).catch(() => {});
    });
  }
  async function playYt(o) {
    feelTick(true);
    unlockPool();
    if (V.key === "yt:" + o.id) { V.full = false; V.bub = false; placeVideo(); return; }
    dlRelease();
    const prevYt = V.yid ? { id: V.yid, ti: V.title, src: "yt" } : null, back = V.back, hist = V.hist;
    V.back = false;
    stopVideo();
    V.hist = hist;
    if (!back && prevYt && prevYt.id !== o.id) { V.hist.push(prevYt); if (V.hist.length > 30) V.hist.shift(); }
    audio.pause();
    V.key = "yt:" + o.id; V.yid = o.id; V.title = o.ti; V.full = false; V.hid = false;
    if (V.played.indexOf(o.id) < 0) V.played.push(o.id);
    videoShell('<div class="vb-load"><div class="spin"></div></div>');
    placeVideo();
    let url = null;
    try { url = await ytStream(o.id); } catch (e) { url = null; }
    if (V.key !== "yt:" + o.id) return;
    if (url) {
      vbox.querySelector(".vb-media").innerHTML = "";
      V.el = takeVideo();
      V.el.src = url;
      V.el.onerror = () => { if (V.key === "yt:" + o.id) embedYt(o.id); };
      bindVideoEl();
      startPlay(V.el);
    } else embedYt(o.id);
  }
  function embedYt(id) {
    vbox.querySelector(".vb-media").innerHTML = '<iframe src="https://www.youtube-nocookie.com/embed/' + encodeURIComponent(id) + '?autoplay=1&playsinline=1&rel=0&modestbranding=1&enablejsapi=1&origin=' + encodeURIComponent(location.origin) + '" allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe>';
    V.el = null;
    vbox.classList.add("ifr");
    // Le lecteur YouTube intégré prévient quand la vidéo se termine (lecture continue).
    const fr = vbox.querySelector("iframe");
    const listen = () => { try { fr.contentWindow.postMessage(JSON.stringify({ event: "listening", id: 1, channel: "widget" }), "*"); } catch (e) { /* ignoré */ } };
    fr.addEventListener("load", () => { listen(); setTimeout(listen, 800); setTimeout(listen, 2500); });
  }
  window.addEventListener("message", (ev) => {
    if (!V.yid || !/youtube/.test(ev.origin || "")) return;
    let d = ev.data;
    try { if (typeof d === "string") d = JSON.parse(d); } catch (e) { return; }
    if (!d) return;
    const st = d.event === "onStateChange" ? d.info : d.event === "infoDelivery" && d.info ? d.info.playerState : undefined;
    if (st === 0 && V.auto) nextRelated(false);
  });
  function playFile(t, o) {
    unlockPool();
    VL.on = !!(o && o.vl);
    if (!(o && o.dl)) dlRelease();
    stopVideo();
    audio.pause();
    const dl = !!(o && o.dl) && U.sec === "dl";
    V.key = "file:" + t.id; V.yid = null; V.title = t.title; V.full = !dl; V.dl = dl; V.hid = false;
    videoShell("");
    V.el = takeVideo();
    bindVideoEl();
    if (dl) {
      draw();   // la page fait de la place en haut pour le lecteur
      placeVideo();
      const slot = host.querySelector(".pslot.dlslot");
      const rr = o.row && o.row.getBoundingClientRect ? o.row.getBoundingClientRect() : null;
      if (slot && rr && vbox.animate) {
        const sr = slot.getBoundingClientRect();
        vbox.animate([{ transform: "translateY(" + (rr.top - sr.top) + "px) scale(.82)", opacity: 0, transformOrigin: "50% 0" },
          { transform: "none", opacity: 1, transformOrigin: "50% 0" }], { duration: 460, easing: "cubic-bezier(.2,.8,.2,1)" });
      }
    } else placeVideo();
    getOne("blobs", t.id).then((r) => {
      if (!r || !V.el || V.key !== "file:" + t.id) { if (!r) toast("Fichier introuvable."); return; }
      V.url = URL.createObjectURL(r.blob.type === "video/mp4" ? r.blob : new Blob([r.blob], { type: "video/mp4" }));
      V.el.src = V.url;
      startPlay(V.el);
    });
  }
  function bindVideoEl() {
    const v = V.el;
    if (!v || v._bound) return;
    v._bound = true;
    // iPhone : demande la bulle flottante automatique quand on quitte l'appli pendant la lecture.
    try { v.autoPictureInPicture = true; v.disablePictureInPicture = false; v.setAttribute("autopictureinpicture", ""); } catch (e) { /* ignoré */ }
    const upd = () => { const b = vbox.querySelector('[data-v="pp"]'); if (b) b.innerHTML = v.paused ? I.play : I.pause; };
    v.addEventListener("play", () => { lastPlay = Date.now(); audio.pause(); upd(); bgPrime(); videoSession(); });
    v.addEventListener("pause", () => { upd(); if (document.hidden && V.key) toBackground(true); });
    v.addEventListener("timeupdate", () => { if (!v.paused) lastPlay = Date.now(); });
    v.addEventListener("ended", () => { wakeOff(); if (V.yid) { if (V.auto) nextRelated(false); } else if (VL.on) vlStep(1); else if (DLP.active) dlStep(1); });
    v.addEventListener("playing", wakeOn);
    v.addEventListener("pause", wakeOff);
  }
  // Vidéo suivante du même thème : la suivante des résultats, sinon une recherche sur le titre.
  let relBusy = false;
  async function nextRelated(manual) {
    if (relBusy || !V.yid) return;
    relBusy = true;
    try {
      const cur = V.yid, title = V.title;
      const idx = SR.items.findIndex((x) => x.id === cur);
      let n = SR.items.slice(idx + 1).find((x) => x.src === "yt" && V.played.indexOf(x.id) < 0) || SR.items.find((x) => x.src === "yt" && V.played.indexOf(x.id) < 0);
      if (!n) {
        const l = await ytSearch(title).catch(() => []);
        n = l.find((x) => V.played.indexOf(x.id) < 0 && x.id !== cur);
        if (n) { SR.items = SR.items.concat([n]); }
      }
      if (!n) { if (manual) toast("Pas d'autre vidéo trouvée."); return; }
      SR.sel = n.id; saveSr();
      // On garde le même lecteur : iOS refuse qu'un nouveau lecteur démarre seul, mais laisse celui qui a déjà été lancé par un geste.
      const fr = vbox.querySelector("iframe"), tag = "yt:" + n.id;
      if (fr || V.el) {
        V.hist.push({ id: cur, ti: title, src: "yt" }); if (V.hist.length > 30) V.hist.shift();
        V.key = tag; V.yid = n.id; V.title = n.ti; if (V.played.indexOf(n.id) < 0) V.played.push(n.id);
        const tl = vbox.querySelector(".vb-bar b"); if (tl) tl.textContent = n.ti;
        if (fr) {
          try { fr.contentWindow.postMessage(JSON.stringify({ event: "command", func: "loadVideoById", args: [n.id] }), "*"); } catch (e) { /* ignoré */ }
        } else {
          let url = null;
          try { url = await ytStream(n.id); } catch (e) { url = null; }
          if (V.key !== tag) return;
          if (!url) { embedYt(n.id); }
          else {
            const v = V.el;
            v.onerror = () => { if (V.key === tag) embedYt(n.id); };
            v.src = url;
            v.play().catch(() => {});
            if (bgOn) { bgA.src = url; bgA.play().catch(() => {}); }
          }
        }
        videoSession(); placeVideo();
        if (U.sec === "search") draw();
        return;
      }
      const wasFull = V.full;
      await playYt(n);
      V.full = wasFull; placeVideo();
      if (U.sec === "search") draw();
    } finally { relBusy = false; }
  }
  function stopVideo() {
    bgOn = false; try { bgA.pause(); bgA.removeAttribute("src"); } catch (e) { /* ignoré */ }
    if (V.el) { try { V.el.pause(); V.el.removeAttribute("src"); V.el.load(); } catch (e) { /* ignoré */ } }
    if (V.url) { URL.revokeObjectURL(V.url); V.url = null; }
    const wasDl = V.dl;
    wakeOff();
    V.key = null; V.yid = null; V.el = null; V.full = false; V.dx = 0; V.dy = 0; V.hid = false; V.bub = false; V.dl = false; V.hist = [];
    if (wasDl && U.sec === "dl" && !DLP.internal) setTimeout(draw, 0);
    vbox.innerHTML = ""; vbox.hidden = true; vbox.className = ""; vbox.removeAttribute("style");
    vtab.hidden = true;
    audioHandlers();
  }
  // Où afficher la vidéo : en plein écran, dans la carte de la page Rechercher, sinon en bulle flottante.
  function placeVideo() {
    if (!V.key) { vbox.hidden = true; vtab.hidden = true; return; }
    vbox.hidden = host.hidden;
    vtab.hidden = true;
    const ifr = vbox.classList.contains("ifr");
    vbox.className = ifr ? "ifr" : "";
    vbox.removeAttribute("style");
    const back = () => { if (bgOn && !document.hidden) toForeground(); };
    if (V.full) { back(); vbox.classList.add("full"); if (vbox.dataset.noctl) vbox.classList.add("noctl"); return; }
    delete vbox.dataset.noctl;
    const slot = V.bub || U.np ? null : U.sec === "search" && V.yid ? host.querySelector('.pslot[data-id="' + V.yid + '"]')
      : U.sec === "dl" && V.dl ? host.querySelector(".pslot.dlslot") : null;
    if (slot) {
      back();
      const r = slot.getBoundingClientRect();
      const chr = host.querySelector(".chrome");
      const lr = { top: chr ? chr.getBoundingClientRect().bottom : 0 };
      const dock = host.querySelector(".dock");
      const bottomLimit = dock ? dock.getBoundingClientRect().top - 6 : window.innerHeight;
      const inChrome = !!slot.closest(".chrome");
      const ct = inChrome ? 0 : Math.max(0, lr.top - r.top), cb = Math.max(0, r.bottom - bottomLimit);
      vbox.classList.add("inline");
      vbox.style.cssText = "top:" + r.top + "px;left:" + r.left + "px;width:" + r.width + "px;height:" + r.height + "px;" +
        (ct || cb ? "clip-path:inset(" + ct + "px 0 " + cb + "px 0 round 14px);" : "") + (ct + cb >= r.height ? "visibility:hidden;" : "");
      return;
    }
    // Dans l'appli, aucune bulle : hors de la page Rechercher la vidéo continue (le son aussi), sans fenêtre.
    // La bulle (image dans l'image d'iOS) n'apparaît que lorsqu'on QUITTE l'appli.
    if (!V.bub) { vbox.classList.add("gone"); return; }
    back();
    vbox.classList.add("mini");
    const miniUp = host.querySelector(".mini") ? 66 : 0;
    const bot = "calc(" + (92 + miniUp) + "px + env(safe-area-inset-bottom))";
    vtab.hidden = !V.hid || host.hidden;
    if (V.hid) { vtab.style.bottom = "calc(" + (100 + miniUp) + "px + env(safe-area-inset-bottom))"; vbox.classList.add("away"); }
    vbox.style.cssText = "bottom:" + bot + ";transform:translate(" + (V.hid ? -(window.innerWidth + 20) : V.dx) + "px," + V.dy + "px)";
  }
  window.addEventListener("resize", () => placeVideo());
  // Retour dans l'appli : le lecteur « absorbe » la vidéo qui revient et elle continue au même endroit.
  document.addEventListener("visibilitychange", () => {
    if (document.hidden || !V.key || host.hidden) return;
    placeVideo();
    if (vbox.classList.contains("inline") && vbox.animate) vbox.animate([{ transform: "scale(.5)", opacity: 0.3, transformOrigin: "90% 10%" }, { transform: "none", opacity: 1, transformOrigin: "90% 10%" }], { duration: 380, easing: "cubic-bezier(.2,.8,.2,1)" });
  });
  host.addEventListener("scroll", (e) => { if (e.target.classList && e.target.classList.contains("list") && V.key) placeVideo(); }, true);
  vbox.addEventListener("click", (ev) => {
    const b = ev.target.closest("[data-v]");
    if (!b) {
      if (vbox.classList.contains("mini")) vbox.classList.toggle("ctl");
      else if (V.full) { const on = !vbox.classList.contains("noctl"); vbox.classList.toggle("noctl", on); if (on) vbox.dataset.noctl = "1"; else delete vbox.dataset.noctl; }
      return;
    }
    ev.stopPropagation();
    const k = b.dataset.v, v = V.el;
    if (k === "close") stopVideo();
    else if (k === "prev") vPrev();
    else if (k === "next") vNext();
    else if (k === "min") { V.full = false; placeVideo(); }
    else if (k === "full") { V.full = true; V.bub = false; vbox.classList.remove("ctl"); placeVideo(); }
    else if (k === "pp" && v) { if (v.paused) v.play().catch(() => {}); else v.pause(); }
    else if (k === "pip") pip(true);
    else if (k === "bub") { V.bub = true; V.full = false; V.hid = false; placeVideo(); }
    else if (k === "hide") { V.hid = true; placeVideo(); }
    else if (k === "auto") toggleAuto();
  });
  function toggleAuto() {
    V.auto = !V.auto; lsSet("casque_auto", V.auto ? "1" : "0");
    vbox.querySelectorAll('[data-v="auto"]').forEach((b) => b.classList.toggle("on", V.auto));
    toast(V.auto ? "Lecture continue activée : une vidéo du même thème suivra." : "Lecture continue désactivée.");
    host.querySelectorAll('[data-a="yauto"]').forEach((b) => b.classList.toggle("on", V.auto));
  }
  function pip(ask) {
    const v = V.el;
    if (!v) { if (ask) toast("Touche le bouton plein écran de la vidéo, puis l'icône « image dans l'image »."); return false; }
    try {
      if (v.webkitSupportsPresentationMode && v.webkitSupportsPresentationMode("picture-in-picture")) { v.webkitSetPresentationMode("picture-in-picture"); return true; }
      if (document.pictureInPictureEnabled) { v.requestPictureInPicture().catch(() => {}); return true; }
    } catch (e) { /* ignoré */ }
    if (ask) toast("L'image dans l'image n'est pas disponible ici.");
    return false;
  }
  // En quittant l'appli pendant une vidéo, elle ne doit pas se couper :
  // 1) bulle flottante (image dans l'image) si l'iPhone l'accepte ;
  // 2) sinon le son continue en arrière-plan (lecteur audio préparé au moment où on a lancé la vidéo),
  //    et l'image reprend au même endroit quand on revient dans l'appli.
  const bgA = new Audio();
  bgA.setAttribute("playsinline", "");
  bgA.preload = "auto";
  let bgOn = false;
  bgA.addEventListener("ended", () => { if (bgOn && V.auto && V.yid) nextRelated(false); });
  function bgPrime() {
    // Appelé pendant le geste qui lance la vidéo : l'iPhone autorise ensuite ce lecteur à jouer en arrière-plan.
    const v = V.el;
    if (!v || !v.currentSrc && !v.src) return;
    try {
      if (bgA.src !== (v.currentSrc || v.src)) bgA.src = v.currentSrc || v.src;
      bgA.muted = true;
      const p = bgA.play();
      if (p) p.then(() => { if (!bgOn) { bgA.pause(); bgA.muted = false; } }).catch(() => { bgA.muted = false; });
    } catch (e) { /* ignoré */ }
  }
  function videoSession() {
    if (!("mediaSession" in navigator) || !V.key) return;
    try {
      const art = V.yid ? [{ src: "https://i.ytimg.com/vi/" + V.yid + "/hqdefault.jpg", sizes: "480x360", type: "image/jpeg" }] : [{ src: "icons/icon-512.png", sizes: "512x512", type: "image/png" }];
      navigator.mediaSession.metadata = new MediaMetadata({ title: V.title || "Vidéo", artist: "Free tune", artwork: art });
      const set = (a, f) => { try { navigator.mediaSession.setActionHandler(a, f); } catch (e) { /* ignoré */ } };
      set("play", () => (bgOn ? bgA : V.el) && (bgOn ? bgA : V.el).play().catch(() => {}));
      set("pause", () => { if (bgOn) bgA.pause(); else if (V.el) V.el.pause(); });
      set("previoustrack", () => vPrev());
      set("nexttrack", canStep() ? () => vNext() : null);
      set("seekto", (d) => { if (bgOn) bgA.currentTime = d.seekTime; else if (V.el) V.el.currentTime = d.seekTime; });
      set("seekbackward", () => { const e = bgOn ? bgA : V.el; if (e) e.currentTime = Math.max(0, e.currentTime - 10); });
      set("seekforward", () => { const e = bgOn ? bgA : V.el; if (e) e.currentTime = e.currentTime + 10; });
      navigator.mediaSession.playbackState = "playing";
    } catch (e) { /* ignoré */ }
  }
  let lastPlay = 0;
  function toBackground(force, inApp) {
    const v = V.el;
    if (!v || v.ended || bgOn) return;
    // iOS peut mettre la vidéo en pause juste avant : on reprend alors en son seul (force).
    if (v.paused && !(force && Date.now() - lastPlay < 1500)) return;
    const inPip = () => v.webkitPresentationMode === "picture-in-picture" || document.pictureInPictureElement === v;
    if (inPip()) return;
    // 1) le son continue tout de suite (comme un audio) ; 2) on demande aussi la bulle flottante d'iOS : si elle apparaît, c'est elle qui joue la vidéo.
    if (!inApp && !v.paused) {
      audioOnly(v, true);
      pip(false);
      setTimeout(() => { if (V.el !== v) return; if (inPip()) { try { bgA.pause(); } catch (e) { /* ignoré */ } bgOn = false; } else { try { v.pause(); } catch (e) { /* ignoré */ } } }, 400);
      return;
    }
    audioOnly(v);
  }
  function audioOnly(v, keep) {
    try {
      const src = v.currentSrc || v.src;
      if (!src) return;
      if (bgA.src !== src) bgA.src = src;
      bgA.muted = false;
      const t0 = v.currentTime, seek = () => { try { bgA.currentTime = t0; } catch (e) { /* ignoré */ } };
      if (bgA.readyState < 1) bgA.addEventListener("loadedmetadata", seek, { once: true }); else seek();
      bgOn = true;
      const p = bgA.play();
      if (p) p.catch(() => { bgOn = false; });
      if (!keep) v.pause();
    } catch (e) { bgOn = false; }
  }
  function toForeground() {
    if (!bgOn) return;
    bgOn = false;
    const v = V.el, t = bgA.currentTime, wasPlaying = !bgA.paused;
    bgA.pause();
    if (v) { try { v.currentTime = t; } catch (e) { /* ignoré */ } if (wasPlaying) v.play().catch(() => {}); }
    videoSession(); updateSession();
  }
  document.addEventListener("visibilitychange", () => { if (document.hidden) toBackground(); else { exitPip(); toForeground(); } });
  // Retour dans Free tune : la petite fenêtre flottante (image dans l'image) disparaît et la vidéo continue dans l'appli, sans coupure.
  function exitPip() {
    const v = V.el; if (!v) return;
    try {
      if (v.webkitPresentationMode === "picture-in-picture") v.webkitSetPresentationMode("inline");
      else if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    } catch (e) { /* ignoré */ }
  }
  window.addEventListener("pagehide", toBackground);
  // Glisser la bulle flottante ; glisser le plein écran vers le bas pour le réduire.
  let drag = null;
  vbox.addEventListener("touchstart", (e) => {
    const t = e.touches[0];
    if (vbox.classList.contains("mini")) drag = { x: t.clientX - V.dx, y: t.clientY - V.dy, moved: false, mini: true };
    else if (V.full && e.target.closest(".vb-bar")) drag = { y0: t.clientY, mini: false };
  }, { passive: true });
  vbox.addEventListener("touchmove", (e) => {
    if (!drag || !drag.mini) return;
    const t = e.touches[0];
    V.dx = Math.min(0, Math.max(-(window.innerWidth - 230), t.clientX - drag.x));
    V.dy = Math.min(0, Math.max(-(window.innerHeight - 340), t.clientY - drag.y));
    drag.moved = true;
    vbox.style.transform = "translate(" + V.dx + "px," + V.dy + "px)";
  }, { passive: true });
  vbox.addEventListener("touchend", (e) => {
    if (drag && !drag.mini && e.changedTouches[0].clientY - drag.y0 > 60) { V.full = false; placeVideo(); }
    // Dans l'appli la bulle ne se cache pas : posée devant l'écran du lecteur, elle y entre et la vidéo continue.
    else if (drag && drag.mini && drag.moved) {
      const slot = !U.np && U.sec === "search" && V.yid ? host.querySelector('.pslot[data-id="' + V.yid + '"]') : null;
      const br = vbox.getBoundingClientRect();
      if (slot) {
        const sr = slot.getBoundingClientRect();
        const cx = br.left + br.width / 2, cy = br.top + br.height / 2;
        if (cx > sr.left && cx < sr.right && cy > sr.top && cy < sr.bottom) {
          const dx = sr.left + sr.width / 2 - cx, dy = sr.top + sr.height / 2 - cy;
          vbox.style.transition = "transform .28s cubic-bezier(.4,0,.2,1),opacity .28s ease";
          vbox.style.transform = "translate(" + (V.dx + dx) + "px," + (V.dy + dy) + "px) scale(.45)";
          vbox.style.opacity = "0";
          setTimeout(() => { V.bub = false; V.dx = 0; V.dy = 0; placeVideo(); }, 290);
          drag = null; return;
        }
      }
      if (V.dx < -(window.innerWidth - 230)) { V.dx = -(window.innerWidth - 230); placeVideo(); }
    }
    drag = null;
  }, { passive: true });


  // ---------- Trace des téléchargements dans le compte + restauration ----------
  const FK = { al: "MP3_LITE", a: "MP3_HQ", ah: "M4A", vl: "MP4_360", v: "MP4_720", vh: "MP4_1080" };
  const KF = {}; Object.keys(FK).forEach((k) => { KF[FK[k]] = k; });
  function mediaAdd(d) {
    const A = API();
    if (!A || !d || !d.yid || !FK[d.f]) return;
    A.authed("POST", "/rest/v1/rpc/media_add", { p_items: [{ yid: d.yid, ti: d.ti, ch: d.ch, d: d.d || 0, kind: FK[d.f], size: d.size || 0 }] }).catch(() => {});
    mediaPl(); applyRestorePl();
  }
  function mediaPl() {
    const A = API();
    if (!A) return;
    L.playlists.forEach((p) => {
      const keys = p.ids.map(byId).filter((t) => t && t.yid && t.fk && FK[t.fk]).map((t) => t.yid + ":" + FK[t.fk]);
      if (keys.length) A.authed("POST", "/rest/v1/rpc/media_pl_set", { p_name: p.name, p_items: keys }).catch(() => {});
    });
  }
  // Playlists à recréer quand leurs titres sont revenus.
  function applyRestorePl() {
    let pend = []; try { pend = JSON.parse(lsGet("casque_rpl") || "[]") || []; } catch (e) { pend = []; }
    if (!pend.length) return;
    const left = [];
    pend.forEach((pl) => {
      const ids = pl.items.map((k) => { const i = k.lastIndexOf(":"); const y = k.slice(0, i), f = KF[k.slice(i + 1)]; const t = L.tracks.find((x) => x.yid === y && (x.fk === f || (!x.fk && isV(f) === !!x.video))); return t ? t.id : null; });
      const ok = ids.filter(Boolean);
      if (ok.length === pl.items.length) { if (!L.playlists.some((x) => x.name === pl.name)) newPlaylist(pl.name, ok); } else left.push(pl);
    });
    lsSet("casque_rpl", JSON.stringify(left));
    if (left.length !== pend.length) draw();
  }
  const RS = { busy: false, sum: null, msg: "" };
  async function mediaSummary() {
    const A = API();
    if (!A || !A.casque.ready()) { RS.msg = "Connexion en cours, réessaie dans un instant."; draw(); return; }
    RS.busy = true; RS.msg = ""; draw();
    try {
      const r = await A.authed("POST", "/rest/v1/rpc/media_summary", {});
      RS.busy = false;
      if (!r || !r.count) { RS.msg = "Aucun média dans ton compte pour le moment."; draw(); return; }
      RS.sum = r;
      sheet({ kind: "confirm", title: "Restaurer mes médias", text: r.count + " médias (" + r.audio + " audio, " + r.video + " vidéo) · environ " + (r.bytes / 1048576 >= 1024 ? (r.bytes / 1073741824).toFixed(1) + " Go" : (r.bytes / 1048576).toFixed(0) + " Mo") + ". Les télécharger de nouveau sur cet appareil (via tes amis Android) et restaurer tes playlists ?", ok: "Oui, télécharger" });
      pending = () => { U.sheet = null; draw(); mediaRestore(); };
    } catch (e) { RS.busy = false; RS.msg = "Impossible de joindre ton compte pour l'instant."; draw(); }
  }
  async function mediaRestore() {
    const A = API();
    try {
      const list = (await A.authed("POST", "/rest/v1/rpc/media_list", {})) || [];
      const pls = (await A.authed("POST", "/rest/v1/rpc/media_pl_list", {})) || [];
      lsSet("casque_rpl", JSON.stringify(pls.filter((x) => x && x.items && x.items.length)));
      let n = 0;
      for (const m of list) {
        const f = KF[m.kind]; if (!f) continue;
        if (L.tracks.some((t) => t.yid === m.yid && (t.fk === f || (!t.fk && isV(f) === !!t.video)))) continue;
        sendDl({ src: "yt", id: m.yid, ti: m.ti, ch: m.ch, d: m.d || 0, th: "https://i.ytimg.com/vi/" + m.yid + "/mqdefault.jpg" }, f);
        n++;
        await new Promise((r) => setTimeout(r, 700));
      }
      applyRestorePl();
      RS.msg = n ? "Restauration lancée : " + n + " média(s) demandé(s). Suis-la dans Téléchargements." : "Tout est déjà sur cet appareil.";
      draw();
    } catch (e) { RS.msg = "La restauration n'a pas pu démarrer. Réessaie."; draw(); }
  }

  // ---------- Petits clics au défilement (iPhone : interrupteur tactile ; Android : vibration) ----------
  const ticksOn = () => lsGet("casque_ticks") !== "0";
  let tickEl = null, lastTick = 0, lastTop = 0;
  function feelTick(force) {
    if (!ticksOn()) return;
    const now = Date.now();
    if (!force && now - lastTick < 45) return;
    lastTick = now;
    try {
      if (navigator.vibrate && !/iPhone|iPad/.test(navigator.userAgent)) { navigator.vibrate(8); return; }
      if (!tickEl) {
        tickEl = document.createElement("label");
        tickEl.style.cssText = "position:fixed;left:-99px;top:-99px;opacity:0;pointer-events:none";
        tickEl.innerHTML = '<input type="checkbox" switch tabindex="-1" aria-hidden="true">';
        document.body.appendChild(tickEl);
      }
      tickEl.click();
    } catch (e) { /* ignoré */ }
  }
  // Petits clics partout où une liste défile (Rechercher, Audios, Vidéos, Téléchargements, Favoris…), comme sur Android.
  const tickTops = new WeakMap();
  function scroller(t) {
    for (let e = t && t.nodeType === 1 ? t : t && t.parentElement; e && e !== host && e !== document.body; e = e.parentElement) {
      if (e.scrollHeight > e.clientHeight + 4) { const o = getComputedStyle(e).overflowY; if (o === "auto" || o === "scroll") return e; }
    }
    return null;
  }
  function scrollFeel(el, step) {
    if (!el) return;
    if (el.classList.contains("list") && U.sec === "search") lastScrollAt = Date.now();
    const top = el.scrollTop, last = tickTops.has(el) ? tickTops.get(el) : top;
    if (!tickTops.has(el)) tickTops.set(el, top);
    if (Math.abs(top - last) >= step) { tickTops.set(el, top); feelTick(); }
  }
  host.addEventListener("scroll", (ev) => {
    const el = ev.target;
    if (!el || !el.classList || el === document) return;
    if (el.scrollHeight > el.clientHeight + 4) scrollFeel(el, 70);
  }, true);
  // Sur iPhone, le « clic » n'est autorisé que pendant un geste du doigt : on le déclenche donc aussi pendant le glissement.
  host.addEventListener("touchstart", (ev) => { const el = scroller(ev.target); if (el) tickTops.set(el, el.scrollTop); }, { passive: true, capture: true });
  host.addEventListener("touchmove", (ev) => { scrollFeel(scroller(ev.target), 60); }, { passive: true, capture: true });

  // ---------- Séries : saisons (playlists YouTube) ----------
  const seasonNum = (t) => { const m = /(saison|season)\s*0?(\d{1,2})/i.exec(t || ""); return m ? +m[2] : 999; };
  const seasonLabel = (t) => { const m = /(saison|season|s)\s*0?(\d{1,2})/i.exec(t || ""); return m ? "Saison " + m[2] : String(t || "").slice(0, 24); };
  const fmtAgo = (s) => { if (!(s > 0)) return ""; const d = Math.max(0, Math.floor((Date.now() / 1000 - s) / 86400)); return d < 1 ? "aujourd'hui" : d < 2 ? "hier" : d < 30 ? "il y a " + d + " jours" : d < 60 ? "il y a 1 mois" : d < 365 ? "il y a " + Math.floor(d / 30) + " mois" : d < 730 ? "il y a 1 an" : "il y a " + Math.floor(d / 365) + " ans"; };
  const metaOf = (o) => [fmtViews(o.v), fmtAgo(o.pu)].filter(Boolean).join(" · ");
  async function ytPlaylists(q) {
    const tries = [];
    PIPED.forEach((b) => tries.push(withTimeout(fetch(b + "/search?filter=playlists&q=" + encodeURIComponent(q)).then((r) => r.json()), 7000).then((j) => {
      const l = ((j && j.items) || []).map((x) => ({ id: String(x.url || "").split("list=")[1], ti: x.name || "", n: x.videos || 0 })).filter((x) => x.id);
      if (!l.length) throw new Error("vide");
      return l;
    })));
    INV.forEach((b) => tries.push(withTimeout(fetch(b + "/api/v1/search?type=playlist&q=" + encodeURIComponent(q)).then((r) => r.json()), 7000).then((j) => {
      const l = (Array.isArray(j) ? j : []).filter((x) => x.playlistId).map((x) => ({ id: x.playlistId, ti: x.title || "", n: x.videoCount || 0 }));
      if (!l.length) throw new Error("vide");
      return l;
    })));
    return anyOf(tries);
  }
  async function ytPlVideos(id) {
    const tries = [];
    PIPED.forEach((b) => tries.push(withTimeout(fetch(b + "/playlists/" + encodeURIComponent(id)).then((r) => r.json()), 8000).then((j) => {
      const l = ((j && j.relatedStreams) || []).filter((x) => x.url && /watch\?v=/.test(x.url)).map((x) => ({ id: x.url.split("v=")[1].split("&")[0], ti: x.title, ch: (x.uploaderName || "").replace(/ - Topic$/, ""), d: x.duration > 0 ? x.duration : 0, v: x.views || 0, th: x.thumbnail || "", pu: x.uploaded > 0 ? Math.round(x.uploaded / 1000) : 0 }));
      if (!l.length) throw new Error("vide");
      return l;
    })));
    INV.forEach((b) => tries.push(withTimeout(fetch(b + "/api/v1/playlists/" + encodeURIComponent(id)).then((r) => r.json()), 8000).then((j) => {
      const l = ((j && j.videos) || []).filter((x) => x.videoId).map((x) => ({ id: x.videoId, ti: x.title, ch: (x.author || "").replace(/ - Topic$/, ""), d: x.lengthSeconds || 0, v: 0, th: "" }));
      if (!l.length) throw new Error("vide");
      return l;
    })));
    const l = await anyOf(tries);
    return l.slice(0, 80).map((x) => Object.assign(x, { src: "yt", th0: x.th || "", th: "https://i.ytimg.com/vi/" + x.id + "/mqdefault.jpg", hd: "https://i.ytimg.com/vi/" + x.id + "/hqdefault.jpg" }));
  }
  async function loadSeasons(d, q) {
    try {
      const words = String(q).toLowerCase().split(/\s+/).filter((w) => w.length > 2);
      const l = (await ytPlaylists(q + " saison")).filter((x) => seasonNum(x.ti) < 999 && words.every((w) => x.ti.toLowerCase().indexOf(w) >= 0));
      const seen = {}, out = [];
      l.sort((a, b) => seasonNum(a.ti) - seasonNum(b.ti)).forEach((x) => { const n = seasonNum(x.ti); if (!seen[n]) { seen[n] = 1; out.push(x); } });
      if (out.length >= 2 && SR.dc === d) { d.seasons = out; if (U.sec === "search" && !host.hidden) draw(); }
    } catch (e) { /* pas de saisons */ }
  }

  // ---------- Recherche ----------
  const SR = U.sr;
  if (!SR.sel && SR.items.length) SR.sel = SR.items[0].id;
  if (SR.items.length && !SR.done) SR.done = SR.q;
  let hist = []; try { hist = JSON.parse(lsGet("casque_hist") || "[]") || []; } catch (e) { hist = []; }
  const saveSr = () => lsSet("casque_sr", JSON.stringify({ q: SR.done || SR.q, done: SR.done, items: SR.items, src: SR.src, sel: SR.sel }));
  async function ytSearch(q, recent) {
    if (recent) {
      try {
        const l = await anyOf(INV.map((b) => withTimeout(fetch(b + "/api/v1/search?type=video&sort_by=upload_date&q=" + encodeURIComponent(q)).then((r) => r.json()), 6000).then((j) => {
          const l = (Array.isArray(j) ? j : []).filter((x) => x.videoId).map((x) => ({ id: x.videoId, ti: x.title, ch: (x.author || "").replace(/ - Topic$/, ""), d: x.lengthSeconds || 0, v: x.viewCount || 0, th: "", pu: x.published || 0 }));
          if (l.length < 4) throw new Error("vide");
          return l;
        })));
        return l.slice(0, 25).map((x) => Object.assign(x, { src: "yt", th0: "", th: "https://i.ytimg.com/vi/" + x.id + "/mqdefault.jpg", hd: "https://i.ytimg.com/vi/" + x.id + "/hqdefault.jpg" }));
      } catch (e) { /* repli : recherche normale */ }
    }
    const tries = [];
    PIPED.forEach((b) => tries.push(withTimeout(fetch(b + "/search?filter=videos&q=" + encodeURIComponent(q)).then((r) => r.json()), 7000).then((j) => {
      const l = ((j && j.items) || []).filter((x) => x.url && /watch\?v=/.test(x.url) && !x.isShort).map((x) => ({
        id: x.url.split("v=")[1].split("&")[0], ti: x.title, ch: (x.uploaderName || "").replace(/ - Topic$/, ""), d: x.duration > 0 ? x.duration : 0, v: x.views || 0, th: x.thumbnail || "", pu: x.uploaded > 0 ? Math.round(x.uploaded / 1000) : 0,
      }));
      if (!l.length) throw new Error("vide");
      return l;
    })));
    INV.forEach((b) => tries.push(withTimeout(fetch(b + "/api/v1/search?type=video&q=" + encodeURIComponent(q)).then((r) => r.json()), 7000).then((j) => {
      const l = (Array.isArray(j) ? j : []).filter((x) => x.videoId).map((x) => ({ id: x.videoId, ti: x.title, ch: (x.author || "").replace(/ - Topic$/, ""), d: x.lengthSeconds || 0, v: x.viewCount || 0, th: "", pu: x.published || 0 }));
      if (!l.length) throw new Error("vide");
      return l;
    })));
    const l = await anyOf(tries);
    return l.slice(0, 25).map((x) => Object.assign(x, { src: "yt", th0: x.th || "", th: "https://i.ytimg.com/vi/" + x.id + "/mqdefault.jpg", hd: "https://i.ytimg.com/vi/" + x.id + "/hqdefault.jpg" }));
  }
  async function itSearch(q) {
    const r = await fetch("https://itunes.apple.com/search?media=music&entity=song&limit=25&term=" + encodeURIComponent(q));
    const j = await r.json();
    return ((j && j.results) || []).map((x) => ({
      id: "it" + x.trackId, q: x.artistName + " " + x.trackName, ti: x.trackName, ch: x.artistName, d: Math.round((x.trackTimeMillis || 0) / 1000), v: 0,
      th: (x.artworkUrl100 || "").replace("100x100", "400x400"), hd: (x.artworkUrl100 || "").replace("100x100", "600x600"), pv: x.previewUrl || "", src: "it",
    }));
  }
  // Propositions du même thème que la vidéo choisie (même titre, même chaîne, mix) : elles s'ajoutent à la liste.
  let relTok = 0;
  const theme = (v) => String(v.ti || "").replace(/[\(\[].*?[\)\]]/g, " ").replace(/official|video|music|lyrics?|audio|clip|officiel|\bhd\b|\b4k\b|\|.*$/gi, " ").replace(/\s+/g, " ").trim();
  async function loadRelated(sel) {
    if (!sel || sel.src !== "yt" || SR.relFor === sel.id) return;
    SR.relFor = sel.id;
    const my = ++relTok;
    const base = theme(sel) || sel.ti;
    const qs = [base, sel.ch, (sel.ch || base) + " mix", base + " similaire"].filter(Boolean);
    const res = await Promise.all(qs.map((q) => ytSearch(q).catch(() => [])));
    if (my !== relTok || SR.sel !== sel.id) return;
    const keep = SR.items.filter((x) => !x.rel || x.id === sel.id);
    const seen = new Set(keep.map((x) => x.id));
    const add = [];
    for (let i = 0; i < 25; i++) res.forEach((l) => { const x = l[i]; if (x && !seen.has(x.id)) { seen.add(x.id); add.push(Object.assign(x, { rel: true })); } });
    if (!add.length) return;
    SR.items = keep.concat(add.slice(0, 70));
    saveSr();
    if (U.sec === "search" && !host.hidden) draw();
  }
  // ---------- Découverte : un artiste → ses albums ; un film → des genres ----------
  // @rubric-icons:begin
  const RIC = {"bolt": {"d": "M13 3L5 13.5h6L10 21l8-10.5h-6z", "c": "#FF9F0A"}, "compass": {"d": "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M15.5 8.5l-2 5-5 2 2-5z", "c": "#40C8E0"}, "palette": {"d": "M12 3a9 9 0 0 0 0 18c1.2 0 2-.8 2-1.800 0-1.200-1-1.500-1-2.500 0-1 .8-1.700 1.800-1.700H17a4 4 0 0 0 4-4C21 6.500 17 3 12 3z M7.500 11h.01 M10 7.500h.01 M14.500 7.500h.01", "c": "#FF375F"}, "smile": {"d": "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M8.500 14a4 4 0 0 0 7 0 M9 9.500h.01 M15 9.500h.01", "c": "#FFD60A"}, "drama": {"d": "M5 4h14v7a7 7 0 0 1-14 0z M9 9.500h.01 M15 9.500h.01 M9.500 14c1.500 1 3.500 1 5 0", "c": "#BF5AF2"}, "ghost": {"d": "M5 20V11a7 7 0 0 1 14 0v9l-2.500-2-2 2-2.500-2-2.500 2-2-2z M9.500 11h.01 M14.500 11h.01", "c": "#5E5CE6"}, "rocket": {"d": "M12 3c4 2 6 6 5 11l-3 3h-4l-3-3C6 9 8 5 12 3z M9 17l-3 3 M15 17l3 3 M12 9.500h.01", "c": "#0A84FF"}, "eye": {"d": "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z", "c": "#5E5CE6"}, "heart": {"d": "M12 20s-7-4.500-7-10a4 4 0 0 1 7-2.500A4 4 0 0 1 19 10c0 5.500-7 10-7 10z", "c": "#FF375F"}, "search": {"d": "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z M20 20l-4-4", "c": "#40C8E0"}, "wand": {"d": "M5 19L15 9 M15 4l1 2 2 1-2 1-1 2-1-2-2-1 2-1z M19 14l.6 1.400L21 16l-1.400.6L19 18l-.6-1.400L17 16l1.400-.6z", "c": "#BF5AF2"}, "shield": {"d": "M12 3l8 3v6c0 5-3.500 8-8 9-4.500-1-8-4-8-9V6z", "c": "#FF453A"}, "star": {"d": "M12 3.500l2.600 5.400 5.900.8-4.300 4.100 1 5.900L12 16.900l-5.200 2.800 1-5.900L3.500 9.700l5.900-.8z", "c": "#FFD60A"}, "film": {"d": "M4 5h16v14H4z M8 5v14 M16 5v14 M4 9.500h4 M4 14.500h4 M16 9.500h4 M16 14.500h4", "c": "#FF9F0A"}, "globe": {"d": "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M3 12h18 M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18", "c": "#30D158"}, "cross": {"d": "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M12 8v8 M8 12h8", "c": "#FF453A"}, "scale": {"d": "M12 3v18 M6 21h12 M5 7h14 M5 7l-3 7a3 3 0 0 0 6 0z M19 7l-3 7a3 3 0 0 0 6 0z", "c": "#5E5CE6"}, "tv": {"d": "M4 7h16v11H4z M9 3l3 4 3-4", "c": "#0A84FF"}, "clock": {"d": "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 7v5l3 2", "c": "#40C8E0"}, "note": {"d": "M9 18V5l11-2v13 M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z", "c": "#FF375F"}, "flame": {"d": "M12 3c1 3.500 5 5.500 5 10a5 5 0 0 1-10 0c0-2 1-3 2-4 .3 1.500 1 2 2 2 0-2-.5-5 1-8z", "c": "#FF9F0A"}, "doc": {"d": "M7 3h7l5 5v13H7z M14 3v5h5 M9.500 13h6 M9.500 17h6", "c": "#0A84FF"}, "ball": {"d": "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 8l3.500 2.500-1.300 4h-4.400l-1.300-4z M12 3v5 M15.500 10.500L20 9 M14.200 14.500l2.800 3.500 M9.800 14.500L7 18 M8.500 10.500L4 9", "c": "#30D158"}, "mic": {"d": "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3z M5 11a7 7 0 0 0 14 0 M12 18v3", "c": "#FF375F"}, "shuffle": {"d": "M16 3h5v5 M4 20L21 3 M21 16v5h-5 M15 15l6 6 M4 4l5 5", "c": "#BF5AF2"}, "headphones": {"d": "M4 14v-2a8 8 0 0 1 16 0v2 M4 14h3v6H5a1 1 0 0 1-1-1z M20 14h-3v6h2a1 1 0 0 0 1-1z", "c": "#5E5CE6"}, "users": {"d": "M9 11a3.500 3.500 0 1 0 0-7 3.500 3.500 0 0 0 0 7z M2.500 20a6.500 6.500 0 0 1 13 0 M16 4.300a3.500 3.500 0 0 1 0 6.400 M18 14a6 6 0 0 1 3.500 6", "c": "#40C8E0"}, "landmark": {"d": "M3 10l9-6 9 6 M5 10v8 M9.500 10v8 M14.500 10v8 M19 10v8 M3 20h18", "c": "#FF9F0A"}, "leaf": {"d": "M5 19c0-9 5-14 15-14 0 10-5 15-14 14 M5 19c3-5 6-8 10-10", "c": "#30D158"}, "flask": {"d": "M9 3h6 M10 3v6l-5 10a1.500 1.500 0 0 0 1.400 2h11.200a1.500 1.500 0 0 0 1.400-2L14 9V3 M7.500 15h9", "c": "#40C8E0"}, "plane": {"d": "M21 3L3 11l7 3 3 7z M10 14L21 3", "c": "#0A84FF"}, "coin": {"d": "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 7v10 M15 9.500c-.5-1-1.600-1.500-3-1.500-1.700 0-3 .8-3 2s1.300 1.700 3 2 3 .8 3 2-1.300 2-3 2c-1.400 0-2.500-.5-3-1.500", "c": "#FFD60A"}, "help": {"d": "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M9.500 9.500a2.500 2.500 0 1 1 3.500 2.300c-.7.400-1 1-1 1.700 M12 16.500h.01", "c": "#BF5AF2"}, "target": {"d": "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M12 12h.01", "c": "#FF453A"}, "trophy": {"d": "M8 4h8v5a4 4 0 0 1-8 0z M8 6H5v2a3 3 0 0 0 3 3 M16 6h3v2a3 3 0 0 1-3 3 M12 13v4 M8.500 20h7 M10 17h4", "c": "#FFD60A"}, "news": {"d": "M4 5h13v14H6a2 2 0 0 1-2-2z M17 9h3v8a2 2 0 0 1-2 2 M7.500 9h6 M7.500 13h6", "c": "#0A84FF"}, "cook": {"d": "M6 11a4 4 0 0 1 2-7 4 4 0 0 1 8 0 4 4 0 0 1 2 7z M7 11v9h10v-9", "c": "#FF9F0A"}, "gamepad": {"d": "M7 8h10a4 4 0 0 1 4 4v3a3 3 0 0 1-5 2l-1-2H9l-1 2a3 3 0 0 1-5-2v-3a4 4 0 0 1 4-4z M8 11v3 M6.500 12.500h3 M16 11.500h.01 M18 13.500h.01", "c": "#5E5CE6"}, "bulb": {"d": "M9 18h6 M10 21h4 M12 3a6 6 0 0 0-4 10.500c.7.600 1 1.300 1 2h6c0-.7.300-1.400 1-2A6 6 0 0 0 12 3z", "c": "#FFD60A"}, "book": {"d": "M4 5a2 2 0 0 1 2-2h5v17H6a2 2 0 0 0-2 2z M20 5a2 2 0 0 0-2-2h-5v17h5a2 2 0 0 1 2 2z", "c": "#FF9F0A"}, "sparkles": {"d": "M12 3l1.800 5.200L19 10l-5.200 1.800L12 17l-1.800-5.200L5 10l5.200-1.800z M19 16l.7 1.800L21.500 18.500l-1.800.7L19 21l-.7-1.800-1.800-.7 1.800-.7z", "c": "#FFD60A"}, "cap": {"d": "M2 9l10-5 10 5-10 5z M6 11v5c0 1.500 2.700 3 6 3s6-1.500 6-3v-5", "c": "#0A84FF"}, "pencil": {"d": "M4 20l1-4L16 5l3 3L8 19z M14 7l3 3", "c": "#FF9F0A"}, "wave": {"d": "M3 12h2 M7 8v8 M11 4v16 M15 8v8 M19 10v4", "c": "#FF375F"}, "n1": {"d": "#1", "c": "#0A84FF"}, "n2": {"d": "#2", "c": "#BF5AF2"}, "n3": {"d": "#3", "c": "#FF375F"}};
  const REMO = {"💥": "bolt", "🧭": "compass", "🎨": "palette", "😂": "smile", "🎭": "drama", "👻": "ghost", "🚀": "rocket", "🕵️": "eye", "💕": "heart", "🔍": "search", "🧙": "wand", "⚔️": "shield", "🤠": "star", "🎥": "film", "🌍": "globe", "🏥": "cross", "⚖️": "scale", "📺": "tv", "🕰️": "clock", "1️⃣": "n1", "2️⃣": "n2", "3️⃣": "n3", "🎵": "note", "🎶": "note", "🔥": "flame", "🎬": "film", "🎼": "note", "📝": "doc", "🏀": "ball", "🧒": "smile", "🎞️": "film", "🎤": "mic", "🍿": "film", "🎙️": "mic", "🔁": "shuffle", "🎧": "headphones", "🤝": "users", "🏛️": "landmark", "🌿": "leaf", "🐾": "leaf", "🔬": "flask", "✈️": "plane", "💰": "coin", "🧠": "help", "⚽": "ball", "🎯": "target", "🏆": "trophy", "📰": "news", "🥗": "leaf", "🍲": "cook", "🍰": "cook", "⚡": "bolt", "🥐": "cook", "🥬": "leaf", "👨‍🍳": "cook", "🎮": "gamepad", "💡": "bulb", "📘": "book", "🏁": "bolt", "🆕": "sparkles", "🧩": "help", "🌱": "leaf", "📚": "book", "✍️": "pencil", "🎓": "cap", "🕺": "wave", "🥁": "wave", "🎸": "note", "💖": "heart", "🙏": "heart", "🌴": "leaf", "🎷": "note", "🎹": "note", "📖": "book", "🔤": "book", "⭐": "star"};
  // @rubric-icons:end
  function rbi(e, bare) {
    const k = RIC[REMO[e] || "star"] || RIC.star, h = k.c.replace("#", ""), n = [0, 2, 4].map((i) => parseInt(h.substr(i, 2), 16)).join(",");
    const sv = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="' + k.d + '"/></svg>';
    if (bare) return sv;
    return '<span class="ti' + (k.d.charAt(0) === "#" ? " n" : "") + '" style="--c:' + k.c + ";--c1:rgba(" + n + ",.62);--c2:rgba(" + n + ",.20);--c3:rgba(" + n + ',.32)">' + (k.d.charAt(0) === "#" ? k.d.slice(1) : sv) + "</span>";
  }
  const cl = (x) => x.replace(/\s+/g, " ").trim();
  const RB = (s, l) => l.map((x) => [x[0], x[1], cl(s + " " + x[2])]);
  const pairs = (l, f) => l.map((x) => [x[0], x[1], cl(f(x[1]))]);
  const FILMS = pairs([["💥", "Action"], ["🧭", "Aventure"], ["🎨", "Animation"], ["😂", "Comédie"], ["🎭", "Drame"], ["👻", "Horreur"], ["🚀", "Science-fiction"], ["🕵️", "Thriller"], ["💕", "Romance"], ["🔍", "Policier"], ["🧙", "Fantastique"], ["⚔️", "Guerre"], ["🤠", "Western"], ["🎥", "Documentaire"], ["🌍", "Africain"]], (n) => (n === "Africain" ? "film africain nollywood complet" : "film " + n.toLowerCase() + " complet en français"));
  const SERIES = pairs([["💥", "Action"], ["🔍", "Policière"], ["🎭", "Drame"], ["😂", "Comédie"], ["🚀", "Science-fiction"], ["👻", "Horreur / Fantastique"], ["💕", "Romance"], ["🏥", "Médicale"], ["⚖️", "Judiciaire"], ["🌍", "Africaine"], ["📺", "Télénovela"], ["🕰️", "Historique"]], (n) => "série " + n.toLowerCase() + " épisodes complets en français");
  const animeR = (s) => RB(s, [["1️⃣", "Saison 1", "saison 1 VF"], ["2️⃣", "Saison 2", "saison 2 VF"], ["3️⃣", "Saison 3", "saison 3 VF"], ["📺", "Épisodes complets", "épisodes complets VF"], ["🎵", "Openings", "opening"], ["🎶", "Endings", "ending"], ["🔥", "Meilleurs moments", "meilleurs moments"], ["🎬", "Films", "film complet"], ["🎼", "Musiques (OST)", "OST soundtrack"], ["📝", "Résumé", "résumé histoire"], ["⚔️", "Meilleurs combats", "meilleurs combats"]]);
  const animeGen = () => pairs([["⚔️", "Shonen"], ["💕", "Romance"], ["🧙", "Fantastique"], ["🚀", "Science-fiction"], ["😂", "Comédie"], ["👻", "Horreur"], ["🏀", "Sport"], ["🎵", "Openings"], ["🎶", "Endings"], ["🧒", "Pour enfants"]], (n) => "anime " + n.toLowerCase() + " VF épisodes");
  const seriesR = (s) => RB(s, [["1️⃣", "Saison 1", "saison 1 épisodes"], ["2️⃣", "Saison 2", "saison 2 épisodes"], ["3️⃣", "Saison 3", "saison 3 épisodes"], ["🎞️", "Bande-annonce", "bande annonce"], ["📺", "Épisodes complets", "épisodes complets VF"], ["🔥", "Scènes cultes", "scènes cultes"], ["🎵", "Générique & musiques", "générique OST"], ["🎤", "Interviews du casting", "interview casting"], ["📝", "Résumé", "résumé"]]);
  const movieR = (s) => RB(s, [["🎞️", "Bande-annonce", "bande annonce"], ["🍿", "Film complet", "film complet VF"], ["🔥", "Scènes cultes", "scènes cultes"], ["🎬", "Making of", "making of coulisses"], ["🎼", "Bande originale", "bande originale OST"], ["🎤", "Interviews", "interview acteurs"], ["📝", "Critique", "critique avis"], ["🎥", "Suites & films similaires", "suite films similaires"]]);
  const artistR = (a) => RB(a, [["🎬", "Clips officiels", "clip officiel"], ["🔥", "Meilleurs titres", "meilleures chansons hits"], ["🎤", "Concerts live", "concert live"], ["🎙️", "Interviews", "interview"], ["🔁", "Remixes", "remix"], ["🎧", "Playlist / Mix", "mix playlist"], ["🤝", "Duos & featurings", "feat duo"]]);
  const docR = (s) => pairs([["🏛️", "Histoire"], ["🌿", "Nature"], ["🐾", "Animaux"], ["🔬", "Science"], ["🚀", "Espace"], ["🕵️", "Crime & enquêtes"], ["🌍", "Société"], ["⚔️", "Guerre"], ["✈️", "Voyage"], ["💰", "Économie"], ["🏥", "Santé"], ["🎨", "Culture & art"], ["🧠", "Mystères"]], (n) => "documentaire " + n + " " + s + " complet");
  const sportR = (s) => RB(s, [["⚽", "Buts", "meilleurs buts"], ["📺", "Résumés de matchs", "résumé match"], ["🔥", "Meilleurs moments", "meilleurs moments"], ["🎯", "Dribbles & gestes", "dribbles skills"], ["🎤", "Interviews", "interview"], ["🎬", "Documentaires", "documentaire"], ["🏆", "Finales & grands matchs", "finale match complet"], ["📰", "Actualités", "actualité aujourd'hui"]]);
  const foodR = (s) => RB(s, [["🥗", "Entrées", "recette entrée"], ["🍲", "Plats", "recette plat"], ["🍰", "Desserts", "recette dessert"], ["⚡", "Rapide (15 min)", "recette rapide facile"], ["🥐", "Pâtisserie", "recette pâtisserie"], ["🌍", "Cuisine africaine", "recette cuisine africaine"], ["🥬", "Végétarien", "recette végétarienne"], ["👨‍🍳", "Astuces de chef", "astuces de chef"]]);
  const gameR = (s) => RB(s, [["🎮", "Gameplay", "gameplay"], ["💡", "Astuces & conseils", "astuces conseils"], ["📘", "Tutoriels", "tuto débutant"], ["🔥", "Meilleurs moments", "meilleurs moments"], ["🏁", "Speedrun", "speedrun"], ["🆕", "Nouveautés", "nouveautés mise à jour"], ["🏆", "Compétition / e-sport", "tournoi esport"], ["🧩", "Le jeu en entier", "walkthrough complet"]]);
  const humourR = (s) => RB(s, [["🎭", "Sketchs", "sketch"], ["🎤", "One-man-show", "one man show complet"], ["😂", "Meilleurs moments", "meilleurs moments drôles"], ["🎙️", "Interviews", "interview"], ["🎬", "Spectacles complets", "spectacle complet"], ["🌍", "Humour africain", "humour africain"]]);
  const courseR = (s) => RB(s, [["🌱", "Pour débutants", "pour débutants"], ["🚀", "Niveau avancé", "niveau avancé"], ["📚", "Cours complet", "cours complet"], ["✍️", "Exercices", "exercices corrigés"], ["💡", "Astuces", "astuces"], ["🎓", "Formation gratuite", "formation gratuite"]]);
  const talkR = (s) => RB(s, [["🔥", "Épisodes populaires", "épisode populaire"], ["🆕", "Les plus récents", "dernier épisode"], ["🎙️", "Entretiens", "entretien complet"], ["💡", "Moments forts", "moments forts"], ["🌍", "Afrique", "afrique"]]);
  const newsR = (s) => RB(s, [["📰", "À la une", "à la une aujourd'hui"], ["🌍", "Afrique", "afrique actualité"], ["🏛️", "Politique", "politique"], ["💰", "Économie", "économie"], ["⚽", "Sport", "sport actualité"], ["🎭", "Culture", "culture"], ["🔬", "Science & tech", "science technologie"]]);
  const musicR = (s) => pairs([["🎤", "Afrobeat"], ["🎶", "Rumba congolaise"], ["🕺", "Coupé-décalé"], ["🥁", "Makossa"], ["🎸", "Rock"], ["🎧", "Rap / Hip-hop"], ["💖", "R&B"], ["🙏", "Gospel & louange"], ["🌴", "Zouk"], ["🎷", "Jazz"], ["🌿", "Reggae"], ["🎹", "Pop"], ["🔥", "Amapiano"], ["🌍", "Musique camerounaise"]], (n) => n + " " + s + " meilleures chansons");
  const kidsR = (s) => RB(s, [["🎵", "Comptines", "comptines"], ["📺", "Épisodes complets", "épisodes complets"], ["📖", "Histoires", "histoires pour enfants"], ["🎨", "Apprendre en s'amusant", "apprendre enfants"], ["🔤", "Alphabet & chiffres", "alphabet chiffres"], ["🎬", "Films", "film complet"]]);
  const genericR = (s) => RB(s, [["🔥", "Les plus populaires", "les plus vus"], ["🆕", "Les plus récents", "nouveau 2026"], ["🎞️", "Version complète", "complet"], ["⭐", "Meilleurs moments", "meilleurs moments"], ["🎧", "Mix / Compilation", "compilation mix"], ["🎤", "Live", "live"], ["🎙️", "Interviews", "interview"], ["📺", "Documentaires", "documentaire"]]);
  const ANIME_NAMES = /\b(naruto|one piece|dragon ball|bleach|death note|attack on titan|l'attaque des titans|my hero academia|demon slayer|jujutsu kaisen|hunter x hunter|fairy tail|tokyo ghoul|fullmetal alchemist|sword art online|one punch man|black clover|boruto|pokemon|pokémon|digimon|yu-?gi-?oh|saint seiya|les chevaliers du zodiaque|detective conan|conan|doraemon|shin chan|tom et jerry|tom and jerry|scooby-?doo|bob l'éponge|spongebob|gintama|haikyuu|blue lock|chainsaw man|spy x family|dr\.? stone|fire force|seven deadly sins|nanatsu|vinland saga|cowboy bebop|evangelion|code geass|mob psycho|ben ?10|totally spies|miraculous|ladybug|dora|peppa pig|pat patrouille|mickey|simpsons?|south park|family guy|rick and morty)\b/i;
  const hasW = (t, p) => new RegExp("\\b(" + p + ")\\b", "i").test(t);
  const stripW = (t, p) => cl(t.replace(new RegExp("\\b(" + p + ")\\b", "ig"), " "));
  function byKeywords(t) {
    if (hasW(t, "films?|movies?|cin[eé]ma") && stripW(t, "films?|movies?|cin[eé]ma|complets?|en fran[cç]ais|vf|streaming").length < 3) return { kind: "topic", title: "Films · choisis un genre", rub: FILMS };
    if (hasW(t, "s[eé]ries?") && stripW(t, "s[eé]ries?|complet(e)?s?|en fran[cç]ais|vf|streaming").length < 3) return { kind: "topic", title: "Séries · choisis un genre", rub: SERIES };
    let s;
    if (hasW(t, "documentaires?|docu|reportages?")) { s = stripW(t, "documentaires?|docu|reportages?|complets?"); return { kind: "topic", title: s ? "Documentaires · " + s : "Documentaires · choisis un thème", rub: docR(s) }; }
    const AN = "animes?|anim[eé]s?|mangas?|dessins? anim[eé]s?|cartoons?";
    if (hasW(t, AN) || ANIME_NAMES.test(t)) { s = stripW(t, AN); return { kind: "topic", title: "Dessin animé · " + (s || "rubriques"), rub: s ? animeR(s) : animeGen() }; }
    const FD = "recettes?|cuisine|cuisiner|cooking";
    if (hasW(t, FD + "|p[aâ]tisserie|g[aâ]teaux?|plats?")) { s = stripW(t, FD); return { kind: "topic", title: "Cuisine · " + (s || "idées"), rub: foodR(s) }; }
    if (hasW(t, "foot|football|basket|nba|ligue des champions|champions league|tennis|mma|ufc|boxe|rugby|cyclisme|athl[eé]tisme|premier league|liga|ligue 1|serie a|mondial|coupe du monde|lions indomptables|sport")) return { kind: "topic", title: "Sport · " + t, rub: sportR(t) };
    if (hasW(t, "gameplay|jeux? vid[eé]o|fortnite|minecraft|fifa|ea fc|efootball|gta|pubg|free fire|call of duty|cod|roblox|warzone|valorant|league of legends|clash of clans|among us|gaming|let'?s play")) return { kind: "topic", title: "Jeu vidéo · " + t, rub: gameR(t) };
    const HU = "humour|sketchs?|stand-?up|blagues?";
    if (hasW(t, HU + "|one[- ]?man[- ]?show|com[eé]dien(ne)?|dr[oô]le|rire")) { s = stripW(t, HU); return { kind: "topic", title: "Humour · " + (s || "rubriques"), rub: humourR(s) }; }
    if (hasW(t, "tutos?|tutoriels?|cours|apprendre|formation|leçons?|lecons?|exercices?")) return { kind: "topic", title: "Apprendre · " + t, rub: courseR(t) };
    const TK = "podcasts?|interviews?|d[eé]bats?|conf[eé]rences?";
    if (hasW(t, TK + "|talk")) { s = stripW(t, TK); return { kind: "topic", title: "Podcasts & entretiens · " + (s || "rubriques"), rub: talkR(s) }; }
    if (hasW(t, "actualit[eé]s?|news|infos?|journal|jt")) return { kind: "topic", title: "Actualité · " + t, rub: newsR(stripW(t, "actualit[eé]s?|news|infos?|journal|jt")) };
    if (hasW(t, "comptines?|b[eé]b[eé]|enfants?|kids")) return { kind: "topic", title: "Enfants · " + t, rub: kidsR(stripW(t, "comptines?|enfants?|kids")) };
    const MU = "musiques?|chansons?|playlist|mix|clips?|hits?";
    if (hasW(t, MU)) return { kind: "topic", title: "Musique · choisis un style", rub: musicR(stripW(t, MU)) };
    return null;
  }
  const dnorm = (s) => String(s || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]/g, "");
  function lev(a, b) {
    if (a === b) return 0;
    let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
    for (let i = 1; i <= a.length; i++) {
      const cur = [i];
      for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = cur;
    }
    return prev[b.length];
  }
  const itj = (u) => withTimeout(fetch(u).then((r) => r.json()), 9000);
  async function detectKind(q) {
    const t = String(q || "").trim(), nq = dnorm(t);
    const kw = byKeywords(t);
    if (kw) return kw;
    if (nq.length >= 3) {
      try {
        const j = await itj("https://itunes.apple.com/search?term=" + encodeURIComponent(t) + "&entity=musicArtist&limit=5");
        for (const o of (j && j.results) || []) {
          const nn = dnorm(o.artistName);
          if (o.artistId && (nn === nq || (nq.length >= 5 && lev(nn, nq) <= 1) || (nq.length >= 4 && nn.indexOf(nq) === 0 && nn.length - nq.length <= 3))) return { kind: "artist", id: o.artistId, name: o.artistName, rub: artistR(o.artistName) };
        }
      } catch (e) { /* ignoré */ }
      try {
        const j = await itj("https://itunes.apple.com/search?term=" + encodeURIComponent(t) + "&entity=tvShow&limit=4");
        for (const o of (j && j.results) || []) {
          const nm = dnorm(o.artistName || o.collectionName);
          if (nm === nq || (nq.length >= 4 && nm.indexOf(nq) === 0)) {
            return /anim|kids|enfant|famil/i.test(o.primaryGenreName || "") ? { kind: "topic", title: "Dessin animé · " + t, rub: animeR(t) } : { kind: "topic", title: "Série · " + t, rub: seriesR(t) };
          }
        }
      } catch (e) { /* ignoré */ }
      try {
        const j = await itj("https://itunes.apple.com/search?term=" + encodeURIComponent(t) + "&entity=movie&limit=3");
        for (const o of (j && j.results) || []) if (dnorm(o.trackName) === nq) return { kind: "topic", title: "Film · " + t, rub: movieR(t) };
      } catch (e) { /* ignoré */ }
    }
    return { kind: "topic", title: "Plus d'idées · " + t, rub: genericR(t) };
  }
  async function dcAlbums(d) {
    try {
      const j = await itj("https://itunes.apple.com/lookup?id=" + d.id + "&entity=album&limit=80");
      const seen = new Set(), out = [];
      for (const o of ((j && j.results) || []).filter((x) => x.wrapperType === "collection")) {
        const name = o.collectionName || "";
        if (!name || / - Single$/.test(name)) continue;
        const key = dnorm(name.replace(/\(.*?\)|\[.*?\]/g, ""));
        if (seen.has(key)) continue;
        seen.add(key);
        out.push({ id: o.collectionId, name, art: String(o.artworkUrl100 || "").replace("100x100", "400x400"), year: String(o.releaseDate || "").slice(0, 4), rd: o.releaseDate || "" });
      }
      d.albums = out.sort((a, b) => (a.rd < b.rd ? 1 : -1));
    } catch (e) { d.albums = []; }
    if (SR.dc === d) draw();
  }
  async function dcTracks(d, a) {
    try {
      const j = await itj("https://itunes.apple.com/lookup?id=" + a.id + "&entity=song&limit=200");
      d.tracks = ((j && j.results) || []).filter((x) => x.wrapperType === "track").sort((x, y) => (x.discNumber || 1) - (y.discNumber || 1) || (x.trackNumber || 0) - (y.trackNumber || 0))
        .map((x) => ({ n: x.trackNumber || 0, ti: x.trackName, ms: x.trackTimeMillis || 0 }));
    } catch (e) { d.tracks = []; }
    if (SR.dc === d && d.sa === a.id) draw();
  }
  // Touche un album : le premier titre démarre seul et la lecture continue suit l'ordre de l'album.
  async function dcAlbumPlay(d, a) {
    const tr = d.tracks || [];
    if (!tr.length) return;
    const found = tr.map((t) => ytSearch(d.name + " " + t.ti).then((l) => l[0] || null).catch(() => null));
    const first = await found[0];
    if (!first || SR.dc !== d || d.sa !== a.id) return;
    const mark = (o) => Object.assign(o, { rel: true });
    if (!SR.items.some((x) => x.id === first.id)) SR.items.push(mark(first));
    plusClick("ysel", first.id); playYt(first); draw();
    const list = (await Promise.all(found)).filter(Boolean);
    if (SR.dc !== d || d.sa !== a.id) return;
    const ids = {}; const album = [];
    list.forEach((o) => { if (!ids[o.id]) { ids[o.id] = 1; const ex = SR.items.find((x) => x.id === o.id); album.push(ex || mark(o)); } });
    SR.items = album.concat(SR.items.filter((x) => !ids[x.id]));
    saveSr(); draw();
  }
  function dcBanner() {
    const d = SR.dc;
    if (!d || d.stage === 2) return "";
    const art = d.kind === "artist";
    const rub = '<div class="dsr">' + d.rub.map((g, i) => '<div class="dsg' + (d.sg === i ? " on" : "") + '" data-a="dcgen" data-id="' + i + '">' + rbi(g[0]) + "<b>" + esc(g[1]) + "</b></div>").join("") + "</div>";
    let row = "";
    if (art) {
      if (!d.albums) row = '<div class="dsl"><i class="spin s"></i></div>';
      else if (!d.albums.length) row = '<p class="dsn">Aucun album trouvé pour cet artiste.</p>';
      else row = '<div class="dsr">' + d.albums.map((a) => '<div class="dsa' + (d.sa === a.id ? " on" : "") + '" data-a="dcalb" data-id="' + a.id + '"><img src="' + esc(a.art) + '" loading="lazy" alt=""><b>' + esc(a.name) + "</b><small>" + esc(a.year) + "</small></div>").join("") + "</div>";
    } else row = rub;
    const seas = d.seasons && d.seasons.length ? '<p class="dss">Saisons</p><div class="dsr dpl">' + d.seasons.map((x) => '<div class="dsp' + (d.ss === x.id ? " on" : "") + '" data-a="dcseason" data-id="' + esc(x.id) + '">' + esc(seasonLabel(x.ti)) + "</div>").join("") + "</div>" : "";
    const ttl = art ? ["Albums", d.name] : String(d.title).split(" · ");
    const lab = art ? "Voir tous les albums de " + d.name : "Voir toutes les rubriques · " + (ttl[1] || ttl[0]);
    const bg = art && d.albums && d.albums.length ? '<div class="dbg">' + d.albums.slice(0, 5).map((a) => '<img src="' + esc(a.art) + '" alt="">').join("") + "</div>" : "";
    return '<div class="dsc' + (d.col ? " col" : "") + (bg ? " hasbg" : "") + '">' + bg + '<div class="dsh" data-a="dctog"><b class="t1">' + esc(ttl[0]) + (ttl[1] ? "<small>" + esc(ttl.slice(1).join(" · ")) + "</small>" : "") + '</b><b class="t2">' + esc(lab) + '</b><button class="ib chv" data-a="dctog" aria-label="Réduire ou agrandir">' + I.down + '</button></div><div class="dsw"><div class="dsi">' + seas + row + "</div></div></div>";
  }
  function vidRow(o, act) {
    return '<div class="rr" data-a="' + act + '" data-id="' + esc(o.id) + '"><div class="rth">' + thumbImg(o) + (o.d ? '<span class="vd">' + fmt(o.d) + "</span>" : "") + "</div>" +
      '<div class="tm"><b>' + esc(o.ti) + "</b><small>" + esc(o.ch) + "</small>" + (metaOf(o) ? "<small>" + esc(metaOf(o)) + "</small>" : "") + "</div></div>";
  }
  function dcLists() {
    const d = SR.dc;
    if (!d || d.stage !== 1) return "";
    let h = "";
    if (d.kind === "artist" && d.sa != null) {
      const a = (d.albums || []).find((x) => x.id === d.sa);
      h += '<p class="sec2">ALBUM · ' + esc(String((a && a.name) || "").toUpperCase()) + "</p>";
      if (!d.tracks) h += '<div class="dsl"><i class="spin s"></i></div>';
      else h += d.tracks.map((t, i) => '<div class="tkr" data-a="dctrack" data-id="' + i + '"><span>' + t.n + "</span><b>" + esc(t.ti) + "</b>" + (t.ms ? "<small>" + fmt(Math.round(t.ms / 1000)) + "</small>" : "") + I.play + "</div>").join("");
    }
    if (d.ss != null && d.seasons) {
      const se = d.seasons.find((x) => x.id === d.ss);
      h += '<p class="sec2">' + esc(se ? seasonLabel(se.ti).toUpperCase() : "SAISON") + "</p>";
      if (!d.gv) h += '<div class="dsl"><i class="spin s"></i></div>';
      else h += d.gv.map((o) => vidRow(o, "dcvid")).join("");
    }
    if (d.sg != null && d.rub[d.sg]) {
      h += '<p class="sec2">' + esc(d.rub[d.sg][1].toUpperCase()) + "</p>";
      if (!d.gv) h += '<div class="dsl"><i class="spin s"></i></div>';
      else h += d.gv.map((o) => vidRow(o, "dcvid")).join("");
    }
    return h;
  }
  let srTok = 0;
  async function doSearch(q) {
    q = String(q || SR.q).trim();
    if (!q) return;
    const inp = host.querySelector("#ysq"); if (inp) inp.blur();
    SR.q = q; SR.done = q; SR.sug = []; SR.focus = false;
    hist = [q].concat(hist.filter((h) => h.toLowerCase() !== q.toLowerCase())).slice(0, 12);
    lsSet("casque_hist", JSON.stringify(hist));
    const my = ++srTok;
    SR.loading = true; SR.err = ""; SR.items = []; draw();
    let items = [], src = "";
    try { items = await ytSearch(q, true); src = "yt"; } catch (e) {
      try { items = await itSearch(q); src = "it"; } catch (e2) { items = []; }
    }
    if (my !== srTok) return;
    SR.loading = false; SR.items = items; SR.src = src; SR.sel = items[0] ? items[0].id : null;
    if (!items.length) SR.err = navigator.onLine ? "Aucun résultat pour « " + q + " »." : "Recherche impossible. Vérifie ta connexion internet.";
    saveSr();
    const l = host.querySelector(".list"); if (l) l.scrollTop = 0;
    draw();
    SR.relFor = null; if (items[0]) loadRelated(items[0]);
    SR.dc = null;
    detectKind(q).then((k) => { if (k && my === srTok) { SR.dc = Object.assign({ stage: 1, col: true }, k); if (k.kind === "artist") dcAlbums(SR.dc); else if (/^(Série|Dessin animé)/.test(String(k.title))) loadSeasons(SR.dc, q); if (U.sec === "search" && !host.hidden) draw(); } }).catch(() => {});
  }
  // Suggestions pendant la frappe (comme sur Android).
  let sugT = null;
  // L'artiste le plus proche d'abord, puis les titres qui y ressemblent, puis les suggestions YouTube.
  function suggest(q) {
    clearTimeout(sugT);
    if (!q.trim()) { SR.sug = []; drawSug(); return; }
    sugT = setTimeout(() => {
      const t = q.trim(), nq = dnorm(t), P = { art: null, songs: [], yt: [] };
      const merge = () => {
        if (SR.q !== q) return;
        const out = [], seen = new Set();
        if (P.art && seen.add(dnorm(P.art))) out.push({ t: P.art, l: P.art, s: "Artiste", a: 1 });
        P.songs.forEach((x) => { if (out.length < 8 && seen.add(dnorm(x.a + " " + x.ti))) out.push({ t: x.a + " " + x.ti, l: x.ti, s: x.a }); });
        P.yt.forEach((y) => { if (out.length < 10 && seen.add(dnorm(y))) out.push({ t: y, l: y }); });
        SR.sug = out;
        drawSug();
      };
      itj("https://itunes.apple.com/search?term=" + encodeURIComponent(t) + "&entity=musicArtist&limit=6").then((j) => {
        let best = null, bs = 1e9;
        ((j && j.results) || []).forEach((o) => {
          const nn = dnorm(o.artistName); if (!nn || !nq) return;
          const sc = nn === nq ? 0 : nn.indexOf(nq) === 0 ? 1 + nn.length - nq.length : nq.length >= 3 && nn.indexOf(nq) >= 0 ? 40 + nn.length - nq.length : lev(nn, nq) <= Math.max(2, Math.floor(nq.length / 3)) ? 100 + lev(nn, nq) : 1e9;
          if (sc < bs) { bs = sc; best = o.artistName; }
        });
        P.art = best; merge();
      }).catch(() => {});
      itj("https://itunes.apple.com/search?term=" + encodeURIComponent(t) + "&entity=song&limit=12").then((j) => {
        P.songs = ((j && j.results) || []).filter((o) => o.wrapperType === "track" && o.trackName).slice(0, 6).map((o) => ({ ti: o.trackName, a: o.artistName })); merge();
      }).catch(() => {});
      const cb = "cqSug" + Date.now();
      const s = document.createElement("script");
      window[cb] = (j) => { window[cb] = () => {}; s.remove(); P.yt = ((j && j[1]) || []).map((x) => (Array.isArray(x) ? x[0] : x)).slice(0, 8); merge(); };
      s.onerror = () => { window[cb] = () => {}; s.remove(); };
      s.src = "https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&hl=fr&q=" + encodeURIComponent(q) + "&callback=" + cb;
      document.head.appendChild(s);
    }, 220);
  }
  function sugHtml() {
    const q = SR.q.trim();
    return (SR.sug || []).map((x) => {
      const o = typeof x === "string" ? { t: x, l: x } : x;
      return '<div class="sg' + (o.a ? " art" : "") + '" data-a="ysug" data-id="' + esc(o.t) + '">' + (o.a ? rbi("🎤", true) : I.search) + "<span>" + esc(o.l) + (o.s ? "<small>" + esc(o.s) + "</small>" : "") + '</span><button data-a="yfill" data-id="' + esc(o.t) + '">' + I2.nw + "</button></div>";
    }).join("") +
      (!(SR.sug || []).length && q ? '<div class="sg go" data-a="ysug" data-id="' + esc(q) + '">' + I.search + "<span>Rechercher « " + esc(q) + " »</span></div>" : "");
  }
  // Propositions : le texte commence à la hauteur de la fenêtre vidéo (devant elle, sans la flouter) ; le bas de l'écran reste flouté.
  function placeSgo() {
    const o = host.querySelector(".sgo"), b = host.querySelector(".sgb");
    if (!o || !b) return;
    const ch = host.querySelector(".chrome"), pst = host.querySelector(".pst");
    const chB = ch ? ch.getBoundingClientRect().bottom : 130;
    let top, bl;
    if (pst && pst.offsetHeight > 0) { const rc = pst.getBoundingClientRect(); top = rc.top + 6; bl = rc.bottom - 14; } else { top = chB + 8; bl = chB; }
    o.style.top = top + "px"; b.style.top = bl + "px";
  }
  function drawSug() {
    const l = host.querySelector(".list");
    if (!l || U.sec !== "search" || !SR.focus) return;
    let o = host.querySelector(".sgo");
    if (!(SR.q.trim() && SR.q.trim() !== SR.done)) { if (o) o.remove(); const b0 = host.querySelector(".sgb"); if (b0) b0.remove(); return; }
    if (!o) {
      const b = document.createElement("div"); b.className = "sgb"; l.parentNode.appendChild(b);
      o = document.createElement("div"); o.className = "sgo"; l.parentNode.appendChild(o);
      placeSgo();
    }
    o.innerHTML = sugHtml();
  }
  let pvAudio = null;
  function preview(o) {
    if (pvAudio) { pvAudio.pause(); const same = U.pv === o.pv; pvAudio = null; U.pv = null; draw(); if (same) return; }
    if (!o.pv) { toast("Pas d'extrait pour ce titre."); return; }
    stopVideo(); audio.pause();
    pvAudio = new Audio(o.pv);
    pvAudio.play().catch(() => {});
    pvAudio.onended = () => { U.pv = null; pvAudio = null; draw(); };
    U.pv = o.pv; draw();
  }

  // ---------- Téléchargement fait par le partenaire Android de « Tu me manques » ----------
  let TP = { st: "?", p: null, at: 0 }; // partenaire : st = ? | ok | none
  async function loadPartner(force) {
    const A = API();
    if (!A || !A.casque.tmmPartner || (!force && Date.now() - TP.at < 60000)) return;
    TP.at = Date.now();
    const p = await A.casque.tmmPartner();
    TP = { st: p ? "ok" : "none", p, at: TP.at };
    if (U.sec === "search") draw();
  }
  const dlFor = (o, f) => DL.find((d) => d.f === f && ((o.src === "yt" && d.yid === o.id) || (o.src !== "yt" && d.ti === o.ti && d.ch === o.ch)));
  async function sendDl(o, f) {
    const A = API();
    if (!A || !A.casque.ready || !A.casque.ready()) { toast("Connexion en cours, réessaie dans un instant."); return; }
    const old = dlFor(o, f);
    if (old && (old.st === "sent" || old.st === "wait" || old.st === "recv")) { U.sec = "dl"; lsSet("casque_sec", "dl"); draw(); return; }
    if (old) DL = DL.filter((d) => d !== old);
    // 1) Le premier ami Android disponible télécharge (rien à demander à personne en particulier).
    let rel = null;
    try {
      rel = await A.authed("POST", "/rest/v1/rpc/relay_create", { p_yid: o.src === "yt" ? o.id : "", p_q: o.src === "yt" ? "" : String(o.q || o.ti).slice(0, 120), p_ti: String(o.ti).slice(0, 120), p_ch: String(o.ch || "").slice(0, 60), p_d: o.d || 0, p_art: String(o.th || "").slice(0, 300), p_fmt: f });
    } catch (e) { rel = null; }
    if (rel && rel.status === "ok") {
      DL.unshift({ mid: rel.id, app: "rel", yid: o.src === "yt" ? o.id : "", ti: o.ti, ch: o.ch || "", th: o.th || "", d: o.d || 0, f, fr: "tes amis", n: rel.helpers || 0, at: Date.now(), st: "sent", p: 0 });
      saveDl(); draw(); pollSoon(2500);
      return;
    }
    // 2) Sinon : le partenaire Android de « Tu me manques ».
    if (TP.st !== "ok") { await loadPartner(true); if (TP.st !== "ok") { toast("Ajoute d'abord un ami qui a Free tune sur Android."); draw(); return; } }
    const body = "📥 @music : peux-tu télécharger " + (isV(f) ? "la vidéo" : "la chanson") + " « " + o.ti + " »" + (o.ch ? " (" + o.ch + ")" : "") + " pour mon Free tune ? Ouvre cette conversation sur ton Android.";
    const bot = { t: "dlreq", id: o.src === "yt" ? o.id : "", q: o.src === "yt" ? "" : (o.q || o.ti).slice(0, 120), ti: String(o.ti).slice(0, 90), ch: String(o.ch || "").slice(0, 40), d: o.d || 0, art: String(o.th || "").slice(0, 300), f, dev: "w" };
    const d = { mid: 0, app: "tmm", yid: o.src === "yt" ? o.id : "", ti: o.ti, ch: o.ch || "", th: o.th || "", f, fr: TP.p.username, at: Date.now(), st: "sending", p: 0 };
    DL.unshift(d); draw();
    try {
      d.mid = await A.casque.sendToTmm(body, { bot });
      d.st = "sent"; saveDl(); draw();
      pollSoon();
    } catch (e) { DL = DL.filter((x) => x !== d); toast("Demande non envoyée. Vérifie ta connexion."); draw(); }
  }
  // Présence en ligne (vue par l'administrateur, et pour les amis automatiques) : toutes les 40 secondes quand l'appli est ouverte.
  function beat() {
    const A = API();
    if (document.hidden || !A || !A.casque || !A.casque.ready || !A.casque.ready()) return;
    A.authed("POST", "/rest/v1/rpc/heartbeat", { p_platform: "ios", p_share: null }).catch(() => {});
  }
  setInterval(beat, 20000); setTimeout(beat, 2000);

  // =====================================================================
  // Mise à jour automatique : dès qu'une nouvelle version est publiée, ses fichiers sont téléchargés en arrière-plan.
  // Quand c'est prêt, une fenêtre propose une seule option : « Installer la mise à jour » (la page se relance toute seule).
  // =====================================================================
  const UP = { busy: false, info: null };
  function showUpdatePrompt() {
    if (!UP.info || document.getElementById("upd-pop")) return;
    if (!document.getElementById("upd-css")) {
      const st = document.createElement("style"); st.id = "upd-css";
      st.textContent = "#upd-pop{position:fixed;inset:0;z-index:100000;display:flex;align-items:center;justify-content:center;padding:24px;background:rgba(0,0,0,.62);-webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px)}" +
        "#upd-pop .c{width:100%;max-width:340px;background:#171a21;border:1px solid rgba(255,255,255,.1);border-radius:24px;padding:24px 20px 20px;text-align:center;color:#eef1f6;font-family:inherit}" +
        "#upd-pop h3{margin:0 0 8px;font-size:20px}#upd-pop p{margin:0 0 6px;font-size:15px;color:#aab2c0;line-height:1.4}" +
        "#upd-pop button{margin-top:16px;width:100%;border:0;border-radius:999px;padding:15px;font:inherit;font-weight:700;font-size:16px;color:#fff;background:linear-gradient(135deg,#5ea0ff,#1f6fe0)}";
      document.head.appendChild(st);
    }
    const d = document.createElement("div"); d.id = "upd-pop";
    d.innerHTML = '<div class="c"><h3>Mise à jour prête</h3><p>La version ' + esc(String(UP.info.version || "").replace(/^web /, "")) + " est téléchargée.</p>" +
      (UP.info.notes ? "<p>" + esc(UP.info.notes) + "</p>" : "") + '<button id="upd-go">Installer la mise à jour</button></div>';
    document.body.appendChild(d);
    d.querySelector("#upd-go").onclick = async () => {
      d.querySelector("#upd-go").textContent = "Installation…";
      try {
        const ks = await caches.keys(); await Promise.all(ks.map((k) => caches.delete(k)));
        const regs = await navigator.serviceWorker.getRegistrations(); await Promise.all(regs.map((x) => x.unregister()));
      } catch (e) { /* ignoré */ }
      location.reload();
    };
  }
  async function autoUpdate() {
    if (UP.busy) return;
    if (UP.info) { showUpdatePrompt(); return; }
    UP.busy = true;
    try {
      const r = await fetch("update.json?t=" + Date.now(), { cache: "no-store" });
      const j = await r.json();
      if (j && j.version && j.version !== CFG.VERSION) {
        // Téléchargement silencieux des nouveaux fichiers : au moment de relancer, tout est déjà prêt.
        const h = await (await fetch("./?t=" + Date.now(), { cache: "no-store" })).text();
        const m = h.match(/dist\/app\.min\.js\?v=[0-9a-f]+/);
        if (m) await (await fetch(m[0], { cache: "reload" })).arrayBuffer();
        UP.info = j;
        showUpdatePrompt();
      }
    } catch (e) { /* pas de réseau : on réessaiera */ }
    UP.busy = false;
  }
  setTimeout(autoUpdate, 3000); setInterval(autoUpdate, 300000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) autoUpdate(); });
  document.addEventListener("visibilitychange", () => { if (!document.hidden) beat(); });
  let pollT = null, polling = false;
  const pendingDl = () => DL.filter((d) => d.st === "sent" || d.st === "wait");
  const activeDl = () => DL.filter((d) => d.st === "sent" || d.st === "wait" || d.st === "recv" || d.st === "sending");
  function pollSoon(ms) { clearTimeout(pollT); if (pendingDl().length) pollT = setTimeout(pollDl, ms || 4000); }
  // Réception d'un fichier envoyé par un ami Android : en parallèle, sans bloquer les autres demandes.
  async function recvRel(d, r, A) {
    d.st = "recv"; d.p = 0; draw();
    try {
      const blob = await fetchProg(mediaUrl(r.media), (p) => { d.p = p; });
      const t = await importBlob(blob, { title: d.ti, artist: d.ch, art: d.th, video: isV(d.f), d: d.d || 0, yid: d.yid, fk: d.f });
      d.st = "done"; d.tid = t.id; d.size = blob.size; mediaAdd(d);
      A.authed("POST", "/rest/v1/rpc/relay_ack", { p_id: r.id }).catch(() => {});
      toast((t.video ? "Vidéo" : "Chanson") + " ajoutée : « " + t.title + " »");
      if (navigator.vibrate) navigator.vibrate(30);
    } catch (e) { d.st = "wait"; }
    saveDl(); draw(); pollSoon(1500);
  }
  async function pollDl() {
    const A = API();
    const list = pendingDl();
    if (!list.length || polling) return;
    if (!A || !A.casque.ready || !A.casque.ready() || document.hidden) { pollSoon(15000); return; }
    polling = true;
    try {
      const rel = list.filter((d) => d.app === "rel" && d.mid);
      if (rel.length) {
        const rows = (await A.authed("POST", "/rest/v1/rpc/relay_status", { p_ids: rel.map((d) => Number(d.mid)) })) || [];
        for (const r of rows) {
          const d = rel.find((x) => Number(x.mid) === r.id);
          if (!d) continue;
          if (r.by) d.fr = r.by;
          // Le téléphone qui télécharge a perdu internet : la demande repart chez un autre téléphone, la barre recommence.
          d.lost = !!r.lost; d.quiet = Number(r.quiet) || 0;
          if (r.lost || (r.tries > (d.tries || 0) && (d.tries || 0) > 0)) { d.pr = 0; d.vp = 0; d.p = 0; }
          if (r.tries != null) d.tries = r.tries;
          if (r.status === "open" && d.st === "wait") d.st = "sent";
          if (r.prog > (d.pr || 0)) d.pr = r.prog;
          if (r.status === "taken" && d.st === "sent") d.st = "wait";
          else if (r.status === "fail") d.st = "fail";
          else if (r.status === "done" && r.media && d.st !== "recv" && d.st !== "done") recvRel(d, r, A);
        }
        saveDl(); draw();
      }
      for (const app of ["cas", "tmm"]) {
        const mine = list.filter((d) => (d.app || "cas") === app && d.mid);
        if (!mine.length) continue;
        const pre = app === "tmm" ? "/tmm" : "";
        const of = (r) => DL.find((x) => (x.app || "cas") === app && String(x.mid) === String(r));
        const ids = mine.map((d) => d.mid).join(",");
        const bots = (await A.authed("GET", pre + "/rest/v1/messages?select=id,meta&meta->bot->>r=in.(" + ids + ")")) || [];
        bots.forEach((m) => {
          const b = m.meta && m.meta.bot, d = b && of(b.r);
          if (!d) return;
          if (b.t === "fail" && d.st !== "done") d.st = "fail";
          else if (b.t === "wait" && d.st === "sent") d.st = "wait";
        });
        const songs = (await A.authed("GET", pre + "/rest/v1/messages?select=id,kind,media,meta&meta->song->>r=in.(" + ids + ")")) || [];
        saveDl(); draw();
        for (const m of songs) {
          const sg = m.meta && m.meta.song, d = sg && of(sg.r);
          if (!d || !m.media || d.st === "done" || d.st === "recv") continue;
          d.st = "recv"; d.p = 0; draw();
          (async () => { try {
            const blob = await fetchProg(mediaUrl(m.media), (p) => {
              d.p = p;
              host.querySelectorAll('[data-dl="' + d.mid + '"]').forEach((row) => {
                const i = row.querySelector(".dbar i"); if (i) i.style.width = Math.round(p * 100) + "%";
                const s2 = row.querySelector("small"); if (s2) s2.textContent = dlStatus(d);
              });
            });
            const t = await importBlob(blob, { title: sg.t || d.ti, artist: sg.a || d.ch, art: sg.th || d.th, video: m.kind === "video", d: (m.meta && m.meta.d) || 0, yid: d.yid });
            d.st = "done"; d.tid = t.id; d.size = blob.size; mediaAdd(d);
            toast((t.video ? "Vidéo" : "Chanson") + " ajoutée : « " + t.title + " »");
            if (navigator.vibrate) navigator.vibrate(30);
          } catch (e) { d.st = "wait"; }
          saveDl(); draw(); pollSoon(1500); })();
        }
      }
    } catch (e) { /* réessai plus tard */ }
    polling = false;
    pollSoon(pendingDl().length ? 2000 : 12000);
  }
  function fetchProg(url, onProg) {
    return new Promise((ok, ko) => {
      const x = new XMLHttpRequest();
      x.open("GET", url);
      x.responseType = "blob";
      x.onprogress = (e) => { if (e.lengthComputable && e.total) onProg(e.loaded / e.total); };
      x.onload = () => (x.status >= 200 && x.status < 300 ? ok(x.response) : ko(new Error("HTTP " + x.status)));
      x.onerror = () => ko(new Error("réseau"));
      x.send();
    });
  }
  document.addEventListener("visibilitychange", () => { if (!document.hidden) pollSoon(500); });
  window.addEventListener("online", () => { pollSoon(500); });

  // ---------- Partager / enregistrer un titre hors de l'appli ----------
  let shareBlob = null;
  function prepShare(id) { shareBlob = null; getOne("blobs", id).then((r) => { if (r) shareBlob = { id, blob: r.blob }; }).catch(() => {}); }
  function shareTrack(id) {
    const t = byId(id);
    if (!t) return;
    if (!shareBlob || shareBlob.id !== id) { toast("Préparation… touche encore une fois."); prepShare(id); return; }
    const ext = t.video ? "mp4" : (/\.([a-z0-9]+)$/i.exec(t.file) || [])[1] || "mp3";
    const f = new File([shareBlob.blob], safe(t.title) + "." + ext, { type: t.mime || shareBlob.blob.type });
    if (navigator.canShare && navigator.canShare({ files: [f] })) navigator.share({ files: [f], title: t.title }).catch(() => {});
    else {
      const a = document.createElement("a"); a.href = URL.createObjectURL(f); a.download = f.name; document.body.appendChild(a); a.click();
      setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 3000);
    }
  }

  // ---------- Miniatures : image + adresses de secours (certaines ne se chargent pas sur iPhone) ----------
  function thumbAlts(o) {
    const l = [];
    const yid = o.src === "yt" ? o.id : o.yid;
    if (o.th) l.push(o.th);
    if (yid) l.push("https://i.ytimg.com/vi/" + yid + "/mqdefault.jpg", "https://i.ytimg.com/vi/" + yid + "/hqdefault.jpg", "https://img.youtube.com/vi/" + yid + "/mqdefault.jpg", "https://i.ytimg.com/vi_webp/" + yid + "/mqdefault.webp");
    if (o.th0) l.push(o.th0);
    return l.filter((x, i) => x && l.indexOf(x) === i);
  }
  const thumbImg = (o, hd) => {
    const l = thumbAlts(o);
    if (hd && o.hd) l.unshift(o.hd);
    return l.length ? '<img class="thi" src="' + esc(l[0]) + '" data-alt="' + esc(l.slice(1).join("|")) + '" data-q="' + esc((o.ch || "") + " " + (o.ti || "")) + '" alt="" referrerpolicy="no-referrer" decoding="async">'
      : '<img class="thi" alt="" data-alt="" data-q="' + esc((o.ch || "") + " " + (o.ti || "")) + '" referrerpolicy="no-referrer">';
  };
  const artCache = {};
  host.addEventListener("error", (e) => {
    const im = e.target;
    if (!im || im.tagName !== "IMG" || !im.classList.contains("thi")) return;
    const alts = (im.dataset.alt || "").split("|").filter(Boolean);
    if (alts.length) { im.dataset.alt = alts.slice(1).join("|"); im.src = alts[0]; return; }
    // En dernier recours : la pochette trouvée sur Apple Music.
    const q = (im.dataset.q || "").replace(/\((official|officiel|clip|lyrics?|audio|video|vidéo)[^)]*\)/gi, "").trim();
    if (!q || im.dataset.it) { im.classList.add("gone"); return; }
    im.dataset.it = "1";
    const use = (u) => { if (u) im.src = u; else im.classList.add("gone"); };
    if (artCache[q] !== undefined) { use(artCache[q]); return; }
    fetch("https://itunes.apple.com/search?media=music&entity=song&limit=1&term=" + encodeURIComponent(q)).then((r) => r.json()).then((j) => {
      const x = j && j.results && j.results[0];
      artCache[q] = x && x.artworkUrl100 ? x.artworkUrl100.replace("100x100", "400x400") : "";
      use(artCache[q]);
    }).catch(() => use(""));
  }, true);
  host.addEventListener("load", (e) => { if (e.target && e.target.classList && e.target.classList.contains("thi")) e.target.classList.add("ok"); }, true);

  // ---------- Vues (mise en page de l'appli Android) ----------
  const big = (title, trailing) => '<header class="lt"><h1>' + title + "</h1>" + (trailing || "") + "</header>";
  const gear = '<button class="ib gl" data-a="settings" aria-label="Paramètres">' + I.gear + "</button>";
  const emptyState = (icon, title, text, action) => '<div class="es"><div class="esi">' + icon + "</div><h3>" + title + "</h3><p>" + text + "</p>" +
    (action ? '<button class="bt" data-a="sec" data-id="search">' + I.search + " " + action + "</button>" : "") + "</div>";
  function dockView() {
    const it = [["audio", I.note, "Audios"], ["video", I2.film, "Vidéos"], ["dl", I2.dl, "Téléchargements"]];
    const busy = activeDl().length;
    return '<nav class="dock"><div class="dk">' + it.map(([k, ic, l]) => '<button class="' + (U.sec === k ? "on" : "") + '" data-a="sec" data-id="' + k + '"><i>' + ic +
      (k === "dl" && busy ? '<b class="bd">' + busy + "</b>" : "") + "</i><span>" + l + "</span></button>").join("") + "</div>" +
      '<button class="dks' + (U.sec === "search" ? " on" : "") + '" data-a="sec" data-id="search" aria-label="Rechercher">' + I.search + "</button></nav>";
  }
  function videoView() {
    const l = L.tracks.filter((t) => t.video).sort((a, b) => b.added - a.added);
    if (!l.length) return big("Vidéos", gear) + '<main class="list">' + emptyState(I2.film, "Pas encore de vidéo", "Les clips que tu télécharges en MP4 s'affichent ici. Tu peux les regarder sans connexion.", "Trouver une vidéo") + "</main>";
    const size = l.reduce((s, t) => s + (t.size || 0), 0);
    return big("Vidéos", gear) + '<main class="list"><p class="cnt">' + l.length + " vidéo(s) · " + (size / 1048576).toFixed(1) + " Mo</p>" +
      '<div class="vgrid">' + l.map((t) => {
        const u = coverUrl(t);
        return '<div class="vt"><div class="vth" data-a="vrow" data-id="' + t.id + '" style="' + (u ? "background-image:url(" + u + ")" : "") + '"><span class="gp">' + I.play + '</span><span class="vd">' + fmt(t.dur) + "</span></div>" +
          '<div class="vm"><div><b>' + esc(t.title) + "</b><small>" + esc(t.artist) + ' · MP4</small></div><button class="ib sm2" data-a="menu" data-id="' + t.id + '">' + I.more + "</button></div></div>";
      }).join("") + "</div></main>";
  }
  // Avancement total (0 à 100) d'une demande faite à un ami Android : chez lui (jusqu'à 90 %), puis réception sur l'iPhone ; -1 = on ne sait pas encore.
  function dlPct(d) {
    if (d.app === "rel") {
      if (d.st === "recv") return Math.round(d.vp > 0 ? d.vp : 67 + (d.p || 0) * 33);
      if (d.st === "wait") { const v = Math.round(Math.max(d.vp || 0, d.pr || 0)); return v > 0 ? Math.min(67, v) : -1; }
      return -1;
    }
    return d.st === "recv" ? Math.round((d.p || 0) * 100) : -1;
  }
  // Barre fluide : elle avance toujours un peu devant ce que l'Android a annoncé (pour ne jamais sauter),
  // puis, dès que le fichier est prêt, elle rejoint 67 % et se remplit au rythme de la réception sur l'iPhone.
  function dlAnim() {
    DL.forEach((d) => {
      if (d.app !== "rel" || (d.st !== "wait" && d.st !== "recv")) return;
      const v = d.vp || 0; let n = v;
      if (d.st === "wait" && (d.lost || d.quiet >= 3)) { n = v; }
      else if (d.st === "wait") {
        const pr = Math.min(67, d.pr || 0), cap = pr >= 67 ? 67 : Math.min(66, pr + 24);
        if (v < pr) n = Math.min(pr, v + (pr - v) * 0.45 + 0.3);
        else n = Math.min(cap, v + (cap - v) * 0.08 + 0.05);
      } else {
        const t = 67 + (d.p || 0) * 33;
        n = v < t ? Math.min(t, v + Math.max((t - v) * 0.3, 0.6)) : v;
      }
      if (n > v) d.vp = n;
      const pc = dlPct(d);
      host.querySelectorAll('[data-dl="' + d.mid + '"]').forEach((row) => {
        const b = row.querySelector(".dbar"), i = b && b.querySelector("i");
        if (i) { i.style.width = (pc >= 0 ? pc : 30) + "%"; b.classList.toggle("ind", pc < 0); }
        const s2 = row.querySelector("small"); if (s2) s2.textContent = dlStatus(d);
      });
    });
  }
  setInterval(() => { if (!document.hidden && DL.some((d) => d.app === "rel" && (d.st === "wait" || d.st === "recv"))) dlAnim(); }, 250);
  function dlStatus(d) {
    const kind = isV(d.f) ? "MP4" : "MP3";
    if (d.app === "rel") {
      if (d.st === "done") return kind + (d.size ? " · " + (d.size / 1048576).toFixed(1) + " Mo" : "") + " · dans " + (isV(d.f) ? "Vidéos" : "Audios");
      if (d.st === "fail") return "Téléchargement impossible";
      if (d.lost && (d.st === "sent" || d.st === "wait")) return "Le serveur n'est plus en ligne, recherche d'un autre serveur…";
      if (d.quiet >= 3 && d.st === "wait") return "Connexion avec le serveur perdue, reconnexion…";
      if (d.st === "recv") return kind + " · " + dlPct(d) + " %";
      if (d.st === "wait") return dlPct(d) > 0 ? kind + " · " + dlPct(d) + " %" : kind + " · en préparation…";
      if (d.st === "sending") return "";
      return "";
    }
    if (d.st === "done") return kind + (d.size ? " · " + (d.size / 1048576).toFixed(1) + " Mo" : "") + " · dans " + (isV(d.f) ? "Vidéos" : "Audios");
    if (d.st === "fail") return "Échec du téléchargement sur le téléphone de @" + d.fr;
    if (d.st === "recv") return Math.round((d.p || 0) * 100) + " %";
    if (d.st === "wait") return "";
    if (d.st === "sending") return "";
    return kind + " · " + (Date.now() - d.at > 180000 ? "en attente : @" + d.fr + " doit ouvrir votre conversation dans Tu me manques" : "demande envoyée à @" + d.fr);
  }
  function dlRow(d) {
    const act = d.st === "sent" || d.st === "wait" || d.st === "recv" || d.st === "sending";
    const ok = d.st === "done" && d.tid && byId(d.tid);
    const btn = act ? '<button class="ib rb" data-a="dlrm" data-id="' + d.mid + '" aria-label="Annuler">' + I.close + "</button>"
      : d.st === "fail" ? '<button class="ib rb bl" data-a="dlretry" data-id="' + d.mid + '" aria-label="Réessayer">' + I2.refresh + '</button><button class="ib rb t" data-a="dlrm" data-id="' + d.mid + '">' + I.close + "</button>"
        : ok ? '<button class="ib rb fill" data-a="dlopen" data-id="' + d.tid + '" aria-label="Lire">' + I.play + '</button><button class="ib rb t" data-a="dlmenu" data-id="' + d.tid + '">' + I.more + "</button>"
          : '<button class="ib rb t" data-a="dlrm" data-id="' + d.mid + '">' + I.trash + "</button>";
    return '<div class="dq" data-dl="' + d.mid + '"' + (ok ? ' data-a="dlopen" data-id="' + d.tid + '"' : "") + '><div class="dr"><div class="dth">' + thumbImg({ th: d.th, yid: d.yid, ti: d.ti, ch: d.ch }) + '<span class="' + (isV(d.f) ? "v" : "") + '">' + (d.f === "v" ? "MP4" : "MP3") + "</span></div>" +
      '<div class="tm"><b>' + esc(d.ti) + '</b><small class="' + d.st + '">' + esc(dlStatus(d)) + "</small></div>" + btn + "</div>" +
      (act ? '<div class="dbar' + (dlPct(d) >= 0 ? "" : " ind") + '"><i style="width:' + (dlPct(d) >= 0 ? dlPct(d) : 30) + '%"></i></div>' : "") + "</div>";
  }
  function dlView() {
    const act = DL.filter((d) => ["sent", "wait", "recv", "sending"].indexOf(d.st) >= 0);
    const done = DL.filter((d) => d.st === "done" || d.st === "fail");
    let body = "";
    if (!DL.length) body = emptyState(I2.dl, "Aucun téléchargement", "Cherche une chanson dans « Rechercher », puis choisis MP3 ou vidéo. Un ami qui a Free tune sur Android la télécharge pour toi, puis elle apparaît ici, dans Audios ou Vidéos.", "Rechercher");
    if (act.length) body += '<p class="sec2">EN COURS · ' + act.length + "</p>" + act.map(dlRow).join("");
    if (done.length) body += '<p class="sec2">TERMINÉS</p>' + done.map(dlRow).join("");
    const win = V.key && V.dl ? '<div class="dlwin"><div class="pslot dlslot"></div>' +
      '<div class="dlstep"><button class="ib" data-a="dlprev" aria-label="Précédent">' + I.prev + '</button><b>' + esc(V.title) + '</b><button class="ib" data-a="dlnext" aria-label="Suivant">' + I.next + "</button></div></div>" : "";
    return big("Téléchargements", done.length ? '<button class="chip" data-a="dlclear">Effacer</button>' : "") + '<main class="list">' + win + body + "</main>";
  }
  // Formats : audio « al » léger, « a » standard, « ah » haute qualité ; vidéo « vl » 360p, « v » 480p, « vh » 720p.
  const isV = (f) => String(f || "").charAt(0) === "v";
  const FMT = {
    al: ["MP3 léger", "96 kbit/s · prend peu de place", 12000], a: ["MP3", "Bonne qualité · 192 kbit/s", 24000], ah: ["MP3 haute qualité", "320 kbit/s", 40000],
    vl: ["MP4 360p", "Petit fichier, idéal hors connexion", 70000], v: ["MP4 480p", "Qualité moyenne", 110000], vh: ["MP4 720p", "HD · plus lourd", 220000],
  };
  function fmtRow(o, f) {
    const d = dlFor(o, f);
    const act = d && ["sent", "wait", "recv", "sending"].indexOf(d.st) >= 0;
    const done = d && d.st === "done" && d.tid && byId(d.tid);
    const sz = o.d ? "≈ " + ((o.d * FMT[f][2]) / 1048576).toFixed(1) + " Mo" : "";
    const sub = !d ? FMT[f][1] : done ? (isV(f) ? "Téléchargé · dans Vidéos" : "Téléchargé · dans Audios") : d.st === "fail" ? "Échec · touche pour réessayer" : dlStatus(d).replace(/^MP[34] · /, "");
    const right = act ? '<span class="ring' + (dlPct(d) >= 0 ? "" : " ind") + '" style="--p:' + Math.max(0, dlPct(d)) + '"><i></i></span>'
      : done ? '<span class="okb">' + I.play + "</span>" : '<span class="dlb">' + I2.dl + "</span>";
    return '<div class="fr" data-a="yget" data-id="' + f + '"><span class="fi' + (isV(f) ? " v" : "") + '">' + (isV(f) ? I2.film : I.note) + "</span>" +
      '<div class="tm"><b>' + FMT[f][0] + (sz ? " <em>" + sz + "</em>" : "") + '</b><small class="' + (done ? "done" : d && d.st === "fail" ? "fail" : "") + '">' + esc(sub) + "</small></div>" + right + "</div>";
  }
  function searchView() {
    const q = SR.q || "";
    const bar = '<form class="gsr" id="ysf">' + I.search + '<input id="ysq" type="search" enterkeyhint="search" placeholder="Titre, artiste, clip…" value="' + esc(q) + '" autocomplete="off" autocorrect="off">' +
      (q ? '<button type="button" class="clr" data-a="yclear" aria-label="Effacer">' + I.close + "</button>" : "") + "</form>";
    let body = "", pin = "";
    const showSg = !!(SR.focus && q.trim() && q.trim() !== SR.done);
    if (!SR.done && !SR.items.length) {
      if (hist.length) body += '<div class="hh"><h2>Recherches récentes</h2><button data-a="yhclear">Effacer</button></div><div class="chips">' + hist.map((h) => '<button class="chip" data-a="ysug" data-id="' + esc(h) + '">' + I2.hist + " " + esc(h) + "</button>").join("") + "</div>";
      body += emptyState(I.search, "Trouve une chanson", "Tape le titre d'une chanson ou le nom d'un artiste. Tu pourras regarder la vidéo, puis la télécharger en MP3 ou en vidéo.", "");
    } else if (SR.loading) {
      body = '<div class="skp"><i class="sk16"></i><i class="skl"></i><i class="sks"></i></div>' + '<div class="skr"><i></i><span><b></b><b></b></span></div>'.repeat(5);
    } else {
      if (SR.err) body += '<div class="err"><p>' + esc(SR.err) + '</p><button class="chip" data-a="ysug" data-id="' + esc(SR.done || q) + '">' + I2.refresh + " Réessayer</button></div>";
      const sel = SR.items.find((x) => x.id === SR.sel) || SR.items[0];
      if (sel) {
        const playing = V.yid === sel.id;
        pin = '<div class="pst"><div class="pslot" data-id="' + esc(sel.id) + '">' + thumbImg(sel, true) +
          (playing ? "" : '<button class="gplay" data-a="yplay">' + (sel.src === "it" && U.pv === sel.pv ? I.pause : I.play) + "</button>" + (sel.d ? '<span class="vd">' + fmt(sel.d) + "</span>" : "")) + "</div>" +
          '<div class="pc pcf"><div class="pct"><h2>' + esc(sel.ti) + "</h2><small>" + esc([sel.ch, fmtViews(sel.v), fmtAgo(sel.pu)].filter(Boolean).join(" · ")) + (sel.src === "it" ? " · extrait de 30 s" : "") + "</small></div>" +
          (sel.src === "yt" ? '<button class="pcb" data-a="vprev" aria-label="Précédent">' + I.prev + '</button><button class="pcb" data-a="vnext" aria-label="Suivant">' + I.next + "</button>" : "") +
          (sel.src === "yt" ? '<button class="aps' + (V.auto ? " on" : "") + '" data-a="yauto" aria-label="Lecture automatique"><i>' + I.play + "</i></button>" : "") + "</div></div>";
        body += dcBanner();
        body += dcLists();
        const fpo = U.fpo === sel.id, mineD = DL.filter((d) => (sel.src === "yt" ? d.yid === sel.id : d.ti === sel.ti));
        const fsub = mineD.some((d) => d.st !== "done" && d.st !== "fail") ? "Téléchargement en cours…" : mineD.some((d) => d.st === "done") ? "Téléchargé · touche pour d'autres formats" : "Audio · Vidéo · touche pour choisir";
        body += '<div class="fp' + (fpo ? " open" : "") + '"><div class="fh" data-a="fpt">' + I2.dl + "<span><b>Télécharger</b><small>" + esc(fsub) + "</small></span>" + (fpo && TP.st === "?" ? '<i class="spin s"></i>' : "") + '<em class="fch">' + I.down + "</em></div>" + (fpo ? "" : "") +
          '<div class="fpb"><p class="via">Un ami qui a Free tune sur Android télécharge pour toi : le premier disponible prend ta demande, et le fichier arrive ici tout seul.</p>' +
          '<p class="fk">AUDIO</p>' + fmtRow(sel, "al") + fmtRow(sel, "a") + fmtRow(sel, "ah") + '<p class="fk">VIDÉO</p>' + fmtRow(sel, "vl") + fmtRow(sel, "v") + fmtRow(sel, "vh") + "</div></div>";
        const rowOf = (o) => {
          const got = DL.some((d) => d.st === "done" && ((o.src === "yt" && d.yid === o.id) || d.ti === o.ti));
          return '<div class="rr" data-a="ysel" data-id="' + esc(o.id) + '"><div class="rth">' + thumbImg(o) + (o.d ? '<span class="vd">' + fmt(o.d) + "</span>" : "") + "</div>" +
            '<div class="tm"><b>' + esc(o.ti) + "</b><small>" + esc(o.ch) + "</small>" + (metaOf(o) ? "<small>" + esc(metaOf(o)) + "</small>" : "") + (got ? '<small class="done">Téléchargé</small>' : "") + "</div></div>";
        };
        const others = SR.items.filter((x) => x !== sel && !x.rel), rels = SR.items.filter((x) => x !== sel && x.rel);
        if (others.length) body += '<p class="sec2">AUTRES RÉSULTATS</p>' + others.map(rowOf).join("");
        if (rels.length) body += '<p class="sec2">DANS LA MÊME THÉMATIQUE</p>' + rels.map(rowOf).join("");
      }
    }
    return big("Rechercher") + bar + pin + '<main class="list">' + body + "</main>" + (showSg ? '<div class="sgb"></div><div class="sgo">' + sugHtml() + "</div>" : "");
  }
  function plusSheet(s) {
    if (s.kind === "dlmenu") {
      const t = byId(s.id); if (!t) return "";
      return "<h3>" + esc(t.title) + "</h3>" + [["dlopen", t.video ? "Regarder" : "Écouter"], ["m_share", "Envoyer hors de Free tune…"], ...(t.video ? [] : [["m_addpl", "Ajouter à une playlist…"]]), ["dlrmt", "Retirer de la liste"], ["m_del", "Supprimer le fichier"]]
        .map(([a, l]) => '<button class="sh' + (a === "m_del" ? " red" : "") + '" data-a="' + a + '" data-id="' + t.id + '">' + l + "</button>").join("");
    }
    return null;
  }
  // Actions propres à ces écrans (renvoie true si l'action est traitée).
  function plusClick(a, id, el) {
    switch (a) {
      case "sec":
        U.sec = id; lsSet("casque_sec", id); U.sheet = null; U.sel = null; U.selDirs = null; U.detail = null; SR.focus = false;
        if (id === "search") loadPartner();
        draw();
        if (id === "dl") pollSoon(300);
        return true;
      case "vrow": { const t = byId(id); if (t) playFile(t, { vl: true }); return true; }
      case "m_vplay": { const t = byId(id); U.sheet = null; draw(); if (t) playFile(t, { vl: true }); return true; }
      case "m_share": shareTrack(id); return true;
      case "dlopen": {
        const t = byId(id); U.sheet = null; if (!t) { draw(); return true; }
        const row = el && el.closest ? el.closest(".dq") : null;
        feelTick(true);
        if (row && row.animate) row.animate([{ background: "rgba(10,132,255,.22)" }, { background: "transparent" }], { duration: 420 });
        if (!t.video) draw();
        dlStart(t, row);
        return true;
      }
      case "dlprev": vPrev(); return true;
      case "dlnext": vNext(); return true;
      case "dlmenu": prepShare(id); sheet({ kind: "dlmenu", id }); return true;
      case "dlrmt": DL = DL.filter((d) => d.tid !== id); saveDl(); U.sheet = null; draw(); return true;
      case "dlrm": DL = DL.filter((d) => String(d.mid) !== String(id)); saveDl(); draw(); return true;
      case "dlclear": DL = DL.filter((d) => d.st !== "done" && d.st !== "fail"); saveDl(); draw(); return true;
      case "dlretry": { const d = DL.find((x) => String(x.mid) === String(id)); if (d) { DL = DL.filter((x) => x !== d); sendDl({ src: d.yid ? "yt" : "it", id: d.yid, q: d.ch + " " + d.ti, ti: d.ti, ch: d.ch, th: d.th }, d.f); } return true; }
      case "yauto": toggleAuto(); return true;
      case "vprev": selStep(-1); return true;
      case "vnext": selStep(1); return true;
      case "fpt": { const sl = SR.items.find((x) => x.id === SR.sel); if (sl) { U.fpo = U.fpo === sl.id ? null : sl.id; const e = host.querySelector(".fp"); if (e) e.classList.toggle("open", U.fpo === sl.id); else draw(); } return true; }
      case "dcyes": { const d = SR.dc; if (!d) return true; d.stage = 1; if (d.kind === "artist" && !d.albums) dcAlbums(d); draw(); return true; }
      case "dcno": { const d = SR.dc; if (d) { d.stage = 2; } draw(); return true; }
      case "dctog": { const d = SR.dc; if (!d) return true; d.col = !d.col; const e = host.querySelector(".dsc"); if (e) e.classList.toggle("col", d.col); return true; }
      case "dcseason": {
        const d = SR.dc; if (!d) return true;
        d.ss = id; d.sg = null; d.sa = null; d.tracks = null; d.gv = null; draw();
        ytPlVideos(id).catch(() => []).then((l) => { if (SR.dc === d && d.ss === id) { d.gv = l; draw(); } });
        return true;
      }
      case "dcalb": { const d = SR.dc; if (!d) return true; const a = (d.albums || []).find((x) => String(x.id) === String(id)); if (!a) return true; d.sa = a.id; d.sg = null; d.ss = null; d.gv = null; d.tracks = null; draw(); dcTracks(d, a); return true; }
      case "dcgen": {
        const d = SR.dc; if (!d) return true;
        d.sg = Number(id); d.ss = null; d.gv = null; d.sa = null; d.tracks = null; draw();
        const mine = d.sg;
        ytSearch(d.rub[mine][2]).catch(() => []).then((l) => { if (SR.dc === d && d.sg === mine) { d.gv = l.slice(0, 25); draw(); } });
        return true;
      }
      case "dctrack": {
        const d = SR.dc; const t = d && d.tracks && d.tracks[Number(id)]; if (!t) return true;
        ytSearch(d.name + " " + t.ti).catch(() => []).then((l) => {
          const o = l[0]; if (!o) { toast("Titre introuvable pour le moment."); return; }
          if (!SR.items.some((x) => x.id === o.id)) SR.items.push(Object.assign(o, { rel: true }));
          plusClick("ysel", o.id); playYt(o); draw();
        });
        return true;
      }
      case "dcvid": {
        const d = SR.dc; const o = d && d.gv && d.gv.find((x) => x.id === id); if (!o) return true;
        if (!SR.items.some((x) => x.id === o.id)) SR.items.push(Object.assign(o, { rel: true }));
        plusClick("ysel", o.id);
        return true;
      }
      case "yplay": {
        const o = SR.items.find((x) => x.id === SR.sel) || SR.items[0];
        if (!o) return true;
        if (o.src === "yt") { playYt(o); draw(); } else preview(o);
        return true;
      }
      case "ysel": {
        if (V.yid && V.yid !== id) stopVideo();
        if (pvAudio) { pvAudio.pause(); pvAudio = null; U.pv = null; }
        SR.sel = id; saveSr();
        const l = host.querySelector(".list"); if (l) l.scrollTop = 0;
        draw();
        const it = SR.items.find((x) => x.id === id);
        loadRelated(it);
        if (it && it.src === "yt") { playYt(it); draw(); }
        return true;
      }
      case "yget": {
        const o = SR.items.find((x) => x.id === SR.sel) || SR.items[0];
        if (!o) return true;
        const d = dlFor(o, id);
        if (d && d.st === "done" && d.tid && byId(d.tid)) return plusClick("dlopen", d.tid, el);
        if (d && d.st !== "fail") { U.sec = "dl"; lsSet("casque_sec", "dl"); draw(); return true; }
        sendDl(o, id);
        return true;
      }
      case "ysug": SR.q = id; doSearch(id); return true;
      case "yfill": { SR.q = id + " "; const i = host.querySelector("#ysq"); if (i) { i.value = SR.q; i.focus(); } suggest(SR.q); return true; }
      case "yclear": { SR.q = ""; SR.sug = []; SR.focus = true; draw(); const i = host.querySelector("#ysq"); if (i) i.focus(); return true; }
      case "yhclear": hist = []; lsSet("casque_hist", "[]"); draw(); return true;
      case "lplay": { const ids = [id]; playList(ids, 0); return true; }
      default: return false;
    }
  }

  // ---------- Interface avec app.js ----------
  window.TMM_PLAYER = {
    show(screen) {
      const first = host.hidden;
      host.hidden = false;
      document.body.classList.add("cq-on");
      U.view = screen === "settings" ? "settings" : "player";
      if (first || true) draw();
      syncSponsor();
      pollSoon(800);
    },
    hide() { host.hidden = true; document.body.classList.remove("cq-on"); placeVideo(); },
  };
  loadAll();
})();
