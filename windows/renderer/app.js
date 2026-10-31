(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const mmss = (s) => (s > 0 ? Math.floor(s / 60) + ":" + String(Math.floor(s % 60)).padStart(2, "0") : "");
  const num = (n) => (n >= 1e6 ? (n / 1e6).toFixed(1).replace(".0", "") + " M" : n >= 1e3 ? Math.round(n / 1e3) + " k" : String(n || 0));
  let S = null;                      // état envoyé par le programme
  let tab = "search";
  const R = { q: "", busy: false, albums: [], yt: [], artist: null, album: null, albumTracks: null, msg: "", done: false, newAcc: null, bulk: null };

  function toast(t) { const e = $("toast"); e.textContent = t; e.hidden = false; clearTimeout(toast.t); toast.t = setTimeout(() => { e.hidden = true; }, 2600); }

  // ---------- onglets ----------
  document.querySelectorAll("#nav button").forEach((b) => b.addEventListener("click", () => {
    if (b.dataset.t === "set" && S && !S.unlocked) { askPw(); return; }
    tab = b.dataset.t; draw();
  }));
  function closeModal() { $("modal").hidden = true; $("modal").innerHTML = ""; }
  function askPw() {
    const m = $("modal");
    m.hidden = false;
    m.innerHTML = "<div class='box'><h3>Réglages verrouillés</h3><p class='sub small'>Entre le mot de passe pour accéder aux réglages.</p>" +
      "<input class='in' id='m-pw' type='password' placeholder='Mot de passe' autocomplete='off'><div class='err' id='m-er'></div>" +
      "<div class='row'><button class='btn sm out' id='m-cancel'>Annuler</button><button class='btn sm' id='m-go'>Valider</button></div></div>";
    $("m-cancel").onclick = closeModal;
    const go = async () => {
      const r = await window.ft.invoke("adm:unlock", { pw: $("m-pw").value });
      if (r.ok) { closeModal(); S.unlocked = true; tab = "set"; draw(); } else { $("m-er").textContent = "Mot de passe incorrect."; $("m-pw").select(); }
    };
    $("m-go").onclick = go;
    $("m-pw").addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });
    $("m-pw").focus();
  }
  function draw() {
    document.querySelectorAll("#nav button").forEach((b) => b.classList.toggle("on", b.dataset.t === tab));
    ["search", "acc", "dl", "set"].forEach((t) => { $("t-" + t).hidden = t !== tab; });
    if (!S) return;
    $("ver").textContent = S.version || ""; $("plat").textContent = S.platform === "mac" ? "Mac" : "Windows";
    const on = S.accounts.filter((a) => a.state === "en ligne").length;
    $("nacc").textContent = S.accounts.length ? on + "/" + S.accounts.length : "";
    const act = S.downloads.filter((d) => d.state === "en cours" || d.state === "attente").length;
    $("ndl").textContent = act ? String(act) : "";
    $("side-state").innerHTML = (S.tool ? "⏳ " + esc(S.tool) + "<br>" : "") + "Demandes en cours : <b>" + S.running + "</b> / " + S.maxJobs + "<br>Les comptes restent en ligne même fenêtre fermée (icône dans la barre d'état).";
    const u = $("upd");
    if (S.update || S.updMsg) {
      u.hidden = false;
      u.innerHTML = "<span>🎉 " + esc(S.updMsg || "Une nouvelle version de Free tune est disponible : elle s'installe automatiquement dès que plus rien n'est en cours.") + "</span>" + (S.update ? "<button class='btn sm' id='upd-go'>Installer maintenant</button>" : "");
      if (S.update) $("upd-go").onclick = () => window.ft.invoke("upd:install");
    } else u.hidden = true;
    if (tab === "set" && !S.unlocked) { tab = "search"; closeModal(); }
    if (tab === "search") drawSearch(); else if (tab === "acc") drawAcc(); else if (tab === "dl") drawDl(); else drawSet();
  }
  // les champs de saisie ne doivent pas être effacés quand l'état se met à jour
  const focused = () => { const a = document.activeElement; return a && (a.tagName === "INPUT" || a.tagName === "SELECT"); };
  window.ft.onState((s) => { S = s; if (focused() && tab !== "dl") { $("nacc").textContent = s.accounts.length ? s.accounts.filter((a) => a.state === "en ligne").length + "/" + s.accounts.length : ""; return; } draw(); });
  window.ft.invoke("state").then((s) => { S = s; draw(); });

  // ---------- recherche ----------
  const it = (u) => fetch(u).then((r) => r.json());
  const art = (x, n) => String(x || "").replace("100x100", n + "x" + n);
  const dnorm = (s) => String(s || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9]/g, "");
  async function doSearch(q) {
    q = q.trim(); if (!q) return;
    Object.assign(R, { q, busy: true, albums: [], yt: [], artist: null, album: null, albumTracks: null, msg: "", done: false });
    drawSearch();
    const nq = dnorm(q);
    const [ar, al, yt] = await Promise.all([
      it("https://itunes.apple.com/search?media=music&entity=musicArtist&limit=5&term=" + encodeURIComponent(q)).catch(() => null),
      it("https://itunes.apple.com/search?media=music&entity=album&limit=18&term=" + encodeURIComponent(q)).catch(() => null),
      window.ft.invoke("search:yt", q),
    ]);
    if (R.q !== q) return;
    const a = ((ar && ar.results) || []).find((o) => { const n = dnorm(o.artistName); return o.artistId && (n === nq || (nq.length >= 4 && n.indexOf(nq) === 0)); });
    let albums = ((al && al.results) || []).filter((x) => x.collectionId);
    if (a) {   // un artiste : tous ses albums
      const j = await it("https://itunes.apple.com/lookup?id=" + a.artistId + "&entity=album&limit=40").catch(() => null);
      const own = ((j && j.results) || []).filter((x) => x.wrapperType === "collection");
      if (own.length) { albums = own; R.artist = a.artistName; }
    }
    const seen = new Set();
    R.albums = albums.filter((x) => (seen.has(x.collectionId) ? false : seen.add(x.collectionId)));
    if (yt && yt.ok) R.yt = yt.list; else R.msg = yt ? yt.msg : "";
    R.busy = false; R.done = true;
    drawSearch();
  }
  async function openAlbum(id) {
    const a = R.albums.find((x) => String(x.collectionId) === String(id)); if (!a) return;
    R.album = a; R.albumTracks = null; drawSearch();
    const j = await it("https://itunes.apple.com/lookup?id=" + id + "&entity=song").catch(() => null);
    R.albumTracks = ((j && j.results) || []).filter((x) => x.wrapperType === "track" && x.kind === "song");
    drawSearch();
  }
  function dl(o) { window.ft.invoke("dl:start", o).then(() => toast("Ajouté aux téléchargements")); }

  function drawSearch() {
    const h = [];
    h.push("<h1>Recherche</h1>");
    h.push('<form class="sr" id="sf"><input class="in" id="sq" placeholder="Artiste, album, titre…" value="' + esc(R.q) + '" autocomplete="off"><button class="btn" type="submit">Rechercher</button></form>');
    if (R.album) {
      const a = R.album;
      h.push('<button class="btn out sm back" id="aback">‹ Retour aux résultats</button>');
      h.push('<div class="hero"><img src="' + esc(art(a.artworkUrl100, 400)) + '" alt=""><div class="grow"><h1 style="margin:0">' + esc(a.collectionName) + "</h1><div class='sub'>" + esc(a.artistName) + " · " + esc((a.releaseDate || "").slice(0, 4)) + " · " + (a.trackCount || "") + " titres</div>" +
        '<div style="margin-top:12px"><button class="btn" id="aall"' + (R.albumTracks ? "" : " disabled") + ">⬇ Tout télécharger (audio)</button></div></div></div>");
      if (!R.albumTracks) h.push('<div class="spin"></div>');
      else h.push(R.albumTracks.map((t, i) => '<div class="trk"><span class="n">' + (t.trackNumber || i + 1) + '</span><div class="grow"><b>' + esc(t.trackName) + '</b><span class="sub small">' + esc(t.artistName) + " · " + mmss(Math.round((t.trackTimeMillis || 0) / 1000)) + '</span></div><button class="btn sm out" data-tp="' + i + '" title="Écouter sans télécharger">▶ Écouter</button><button class="btn sm" data-trk="' + i + '">⬇ Audio</button><button class="btn sm out" data-trv="' + i + '">⬇ Vidéo</button></div>').join(""));
    } else if (R.busy) h.push('<div class="spin"></div>');
    else if (!R.done) h.push('<div class="empty">Cherche un artiste pour voir ses albums, ou un titre.<br>Les résultats viennent de YouTube et d\'iTunes ; les fichiers sont enregistrés dans <b>' + esc(S ? S.settings.dlDirShown : "") + "</b>.</div>");
    else {
      if (R.albums.length) {
        h.push("<h2>" + (R.artist ? "Albums de " + esc(R.artist) : "Albums") + "</h2><div class='albums'>" + R.albums.map((a) => '<button class="alb" data-alb="' + a.collectionId + '"><img loading="lazy" src="' + esc(art(a.artworkUrl100, 300)) + '" alt=""><b>' + esc(a.collectionName) + "</b><small>" + esc(a.artistName) + " · " + esc((a.releaseDate || "").slice(0, 4)) + "</small></button>").join("") + "</div>");
      }
      if (R.yt.length) {
        h.push("<h2>Titres et vidéos</h2>" + R.yt.map((v, i) => '<div class="trk"><img loading="lazy" src="' + esc(v.thumb) + '" alt=""><div class="grow"><b>' + esc(v.title) + '</b><span class="sub small">' + esc(v.channel) + (v.duration ? " · " + mmss(v.duration) : "") + (v.views ? " · " + num(v.views) + " vues" : "") + '</span></div><button class="btn sm out" data-pa="' + i + '" title="Écouter sans télécharger">▶ Écouter</button><button class="btn sm out" data-pv="' + i + '" title="Regarder sans télécharger">📺 Regarder</button><button class="btn sm" data-ya="' + i + '">⬇ Audio</button><button class="btn sm out" data-yv="' + i + '">⬇ Vidéo</button></div>').join(""));
      }
      if (!R.albums.length && !R.yt.length) h.push('<div class="empty">' + (R.msg ? "Recherche impossible : " + esc(R.msg) : "Aucun résultat.") + "</div>");
    }
    const el = $("t-search"); el.innerHTML = h.join("");
    $("sf").onsubmit = (e) => { e.preventDefault(); doSearch($("sq").value); };
    el.querySelectorAll("[data-alb]").forEach((b) => (b.onclick = () => openAlbum(b.dataset.alb)));
    el.querySelectorAll("[data-ya]").forEach((b) => (b.onclick = () => { const v = R.yt[+b.dataset.ya]; dl({ yid: v.id, title: v.title, artist: "", kind: "audio" }); }));
    el.querySelectorAll("[data-yv]").forEach((b) => (b.onclick = () => { const v = R.yt[+b.dataset.yv]; dl({ yid: v.id, title: v.title, artist: "", kind: "video", height: 720 }); }));
    const ytItem = (v, video) => ({ yid: v.id, title: v.title, sub: v.channel, video: !!video, thumb: v.thumb });
    el.querySelectorAll("[data-pa]").forEach((b) => (b.onclick = () => playList(R.yt.map((v) => ytItem(v, false)), +b.dataset.pa)));
    el.querySelectorAll("[data-pv]").forEach((b) => (b.onclick = () => playList([ytItem(R.yt[+b.dataset.pv], true)], 0)));
    const trkItem = (t) => ({ yid: "", title: t.trackName, artist: t.artistName, sub: t.artistName, dur: Math.round((t.trackTimeMillis || 0) / 1000), video: false });
    el.querySelectorAll("[data-tp]").forEach((b) => (b.onclick = () => playList(R.albumTracks.map(trkItem), +b.dataset.tp)));
    el.querySelectorAll("[data-trv]").forEach((b) => (b.onclick = () => { const t = R.albumTracks[+b.dataset.trv]; dl({ title: t.trackName, artist: t.artistName, dur: Math.round((t.trackTimeMillis || 0) / 1000), kind: "video", height: 720 }); }));
    const trkDl = (t) => dl({ title: t.trackName, artist: t.artistName, dur: Math.round((t.trackTimeMillis || 0) / 1000), kind: "audio" });
    el.querySelectorAll("[data-trk]").forEach((b) => (b.onclick = () => trkDl(R.albumTracks[+b.dataset.trk])));
    if ($("aback")) $("aback").onclick = () => { R.album = null; drawSearch(); };
    if ($("aall")) $("aall").onclick = () => { R.albumTracks.forEach(trkDl); toast(R.albumTracks.length + " titres ajoutés"); };
  }

  // ---------- comptes serveur ----------
  const rnd = () => { const a = new Uint8Array(9); crypto.getRandomValues(a); return Array.from(a, (b) => "abcdefghjkmnpqrstuvwxyz23456789"[b % 31]).join("") + "x7"; };
  function drawAcc() {
    if (!R.newAcc) R.newAcc = rnd();
    const h = ["<h1>Comptes serveur</h1>",
      "<p class='sub'>Chaque compte reste en ligne sur ce PC et sert les iPhone comme un Android : il reçoit leurs demandes, télécharge la musique et la renvoie. Ils aident aussi au partage p2p.</p>"];
    h.push("<div class='card'><b>Ajouter un compte</b><div class='row' style='margin-top:10px;gap:10px;flex-wrap:wrap'><div class='field grow' style='margin:0'><label>Pseudo (3-20 : a-z 0-9 _)</label><input class='in' id='na-u' maxlength='20'></div><div class='field grow' style='margin:0'><label>Code (8 caractères minimum)</label><div class='row'><input class='in' id='na-c' maxlength='40' value='" + esc(R.newAcc) + "'></div></div><button class='btn' id='na-go' style='align-self:flex-end'>Créer</button></div>" +
      "<div class='sub small' style='margin-top:8px'>Si le pseudo existe déjà et que le code est le bon, le compte est simplement ajouté à ce PC.</div></div>");
    h.push("<div class='card'><b>Créer plusieurs comptes d'un coup</b><div class='row' style='margin-top:10px;gap:10px;flex-wrap:wrap'><div class='field grow' style='margin:0'><label>Début du pseudo (ex : serveur → serveur01, serveur02…)</label><input class='in' id='nb-p' maxlength='14' value='serveur'></div><div class='field' style='margin:0;width:110px'><label>Nombre (1-30)</label><input class='in' id='nb-n' type='number' min='1' max='30' value='5'></div><button class='btn' id='nb-go' style='align-self:flex-end'>Créer</button></div>" + (R.bulk ? "<div class='small' style='margin-top:10px'>" + R.bulk.map((r) => (r.ok ? "✅ " : "❌ ") + esc(r.username) + (r.ok ? "" : " : " + esc(r.msg))).join("<br>") + "</div>" : "") + "</div>");
    h.push("<h2>Comptes sur ce PC (" + S.accounts.length + ")</h2>");
    if (!S.accounts.length) h.push("<div class='empty'>Aucun compte pour l'instant.</div>");
    S.accounts.forEach((a) => {
      const cls = a.state === "en ligne" ? "on" : a.state === "erreur" ? "err" : "wait";
      h.push("<div class='card row'><span class='dot " + cls + "'></span><div class='grow'><b>@" + esc(a.username) + "</b> <span class='pill'>" + esc(a.state) + "</span><div class='small sub'>" + (a.error ? "<span class='warn'>" + esc(a.error) + "</span> · " : "") + (a.jobs ? "⏳ " + a.jobs + " tâche(s) en cours · " : "") + a.helped + " titre(s) envoyé(s) depuis le lancement</div></div>" +
        "<button class='btn sm out' data-cp='" + esc(a.username) + "'>Copier le code</button><button class='btn sm red' data-rm='" + esc(a.username) + "'>Retirer</button></div>");
    });
    const el = $("t-acc"); el.innerHTML = h.join("");
    $("na-go").onclick = async () => {
      const u = $("na-u").value, c = $("na-c").value; $("na-go").disabled = true;
      const r = await window.ft.invoke("acc:add", { username: u, code: c });
      if (r.ok) { R.newAcc = rnd(); toast("Compte @" + r.username + " créé et en ligne"); } else toast(r.msg);
      draw();
    };
    $("nb-go").onclick = async () => {
      $("nb-go").disabled = true; toast("Création en cours…");
      R.bulk = await window.ft.invoke("acc:bulk", { prefix: $("nb-p").value, count: $("nb-n").value }); draw();
    };
    el.querySelectorAll("[data-cp]").forEach((b) => (b.onclick = async () => { const c = await window.ft.invoke("acc:code", b.dataset.cp); try { await navigator.clipboard.writeText(c); toast("Code copié"); } catch (e) { toast("Code : " + c); } }));
    el.querySelectorAll("[data-rm]").forEach((b) => (b.onclick = async () => { if (b.dataset.sure !== "1") { b.dataset.sure = "1"; b.textContent = "Confirmer ?"; setTimeout(() => { b.dataset.sure = ""; b.textContent = "Retirer"; }, 3000); return; } await window.ft.invoke("acc:remove", b.dataset.rm); toast("Compte retiré de ce PC"); }));
  }

  // ---------- téléchargements ----------
  function drawDl() {
    const h = ["<h1>Téléchargements</h1><div class='row' style='margin-bottom:12px;gap:8px'><span class='sub grow'>Dossier : " + esc(S.settings.dlDirShown) + "</span>" + (S.downloads.some((d) => d.state !== "en cours" && d.state !== "attente" && d.state !== "en pause") ? "<button class='btn out sm' id='dl-clear'>Vider la liste</button>" : "") + "<button class='btn out sm' id='dl-open'>Ouvrir le dossier</button></div>"];
    if (!S.downloads.length) h.push("<div class='empty'>Rien pour l'instant. Utilise la page Recherche.</div>");
    S.downloads.slice().reverse().forEach((d) => {
      const live = d.state === "en cours" || d.state === "attente" || d.state === "en pause";
      h.push("<div class='card'><div class='row'><div class='grow'><b>" + esc(d.artist ? d.artist + " - " : "") + esc(d.title) + "</b><div class='small sub'>" + (d.kind === "video" ? "🎬 Vidéo" : "🎧 Audio") + " · " + esc(d.state) + (live && d.pct ? " · " + Math.round(d.pct * 100) + " %" : "") + (d.err ? " · <span class='warn'>" + esc(d.err) + "</span>" : "") + "</div></div>" +
        (d.state === "terminé" && d.file ? "<button class='btn sm' data-pl='" + d.id + "'>▶ Lire</button><button class='btn sm out' data-sf='" + esc(d.file) + "'>Afficher</button>" : "") +
        (d.state === "en cours" || d.state === "attente" ? "<button class='btn sm pa' data-ctl='pause' data-id='" + d.id + "'>⏸ Pause</button>" : "") +
        (d.state === "en pause" ? "<button class='btn sm' data-ctl='resume' data-id='" + d.id + "'>▶ Reprendre</button>" : "") +
        (d.state === "échec" || d.state === "annulé" ? "<button class='btn sm' data-ctl='resume' data-id='" + d.id + "'>↻ Réessayer</button>" : "") +
        (live ? "<button class='btn sm red' data-ctl='cancel' data-id='" + d.id + "' title='Annuler'>✕</button>" : "") +
        "</div>" + (live ? "<div class='bar'><i style='width:" + Math.round(d.pct * 100) + "%'></i></div>" : "") + "</div>");
    });
    const el = $("t-dl"); el.innerHTML = h.join("");
    $("dl-open").onclick = () => window.ft.invoke("dl:show", "");
    if ($("dl-clear")) $("dl-clear").onclick = () => window.ft.invoke("dl:ctl", { op: "clear" });
    el.querySelectorAll("[data-sf]").forEach((b) => (b.onclick = () => window.ft.invoke("dl:show", b.dataset.sf)));
    el.querySelectorAll("[data-ctl]").forEach((b) => (b.onclick = () => window.ft.invoke("dl:ctl", { op: b.dataset.ctl, id: +b.dataset.id })));
    el.querySelectorAll("[data-pl]").forEach((b) => (b.onclick = () => {
      const done = S.downloads.filter((d) => d.state === "terminé" && d.file).reverse();
      const list = done.map((d) => ({ file: d.file, title: d.title, sub: d.artist, video: d.kind === "video" }));
      playList(list, Math.max(0, done.findIndex((d) => String(d.id) === b.dataset.pl)));
    }));
  }


  // ---------- lecteur intégré ----------
  const P = { q: [], i: -1, seq: 0, drag: false, showVid: true };
  const pv = $("pv");
  function playerOpen(on) { $("player").hidden = !on; document.body.classList.toggle("hasplayer", !!on); }
  function vidShow(video) { $("pv-wrap").hidden = !(video && P.showVid); $("p-vid").hidden = !video || P.showVid; }
  async function playList(list, i) {
    P.q = list.slice(0, 200); P.i = Math.max(0, Math.min(i, P.q.length - 1)); P.showVid = true;
    await loadCur();
  }
  async function loadCur() {
    const it = P.q[P.i]; if (!it) return;
    const my = ++P.seq;
    playerOpen(true);
    $("p-title").textContent = (it.artist ? it.artist + " - " : "") + it.title;
    $("p-sub").textContent = "Chargement…";
    $("p-dl").hidden = !!it.file;
    pv.pause(); pv.removeAttribute("src"); pv.load(); $("p-seek").value = 0; $("p-cur").textContent = "0:00"; $("p-dur").textContent = "0:00"; $("p-play").textContent = "▶";
    const r = it.file ? await window.ft.invoke("play:file", it.file) : await window.ft.invoke("play:yt", { yid: it.yid, title: it.title, artist: it.artist, dur: it.dur, video: it.video });
    if (my !== P.seq) return;
    if (!r.ok) { $("p-sub").textContent = r.msg || "Lecture impossible"; vidShow(false); return; }
    it.yid = it.yid || r.yid || ""; it.isVideo = !!r.video;
    $("p-sub").textContent = it.sub || "";
    vidShow(r.video);
    pv.src = r.src;
    pv.play().catch(() => {});
  }
  function step(d) { const n = P.i + d; if (n < 0 || n >= P.q.length) return false; P.i = n; loadCur(); return true; }
  const tfmt = (s) => (isFinite(s) && s > 0 ? Math.floor(s / 60) + ":" + String(Math.floor(s % 60)).padStart(2, "0") : "0:00");
  pv.addEventListener("play", () => { $("p-play").textContent = "⏸"; });
  pv.addEventListener("pause", () => { $("p-play").textContent = "▶"; });
  pv.addEventListener("loadedmetadata", () => { $("p-dur").textContent = tfmt(pv.duration); });
  pv.addEventListener("timeupdate", () => {
    $("p-cur").textContent = tfmt(pv.currentTime);
    if (!P.drag && isFinite(pv.duration) && pv.duration > 0) $("p-seek").value = Math.round(pv.currentTime / pv.duration * 1000);
  });
  pv.addEventListener("ended", () => { if (!step(1)) { $("p-play").textContent = "▶"; $("p-seek").value = 0; } });
  pv.addEventListener("error", () => { if (pv.getAttribute("src")) $("p-sub").textContent = "Lecture impossible pour ce titre"; });
  $("p-play").onclick = () => { if (!pv.getAttribute("src")) { loadCur(); return; } if (pv.paused) pv.play().catch(() => {}); else pv.pause(); };
  $("p-prev").onclick = () => { if (pv.currentTime > 4 || !step(-1)) pv.currentTime = 0; };
  $("p-next").onclick = () => { step(1); };
  $("p-seek").addEventListener("input", () => { P.drag = true; });
  $("p-seek").addEventListener("change", () => { if (isFinite(pv.duration)) pv.currentTime = $("p-seek").value / 1000 * pv.duration; P.drag = false; });
  $("p-vol").oninput = () => { pv.volume = $("p-vol").value / 100; pv.muted = false; $("p-mute").textContent = pv.volume === 0 ? "🔇" : "🔊"; };
  $("p-mute").onclick = () => { pv.muted = !pv.muted; $("p-mute").textContent = pv.muted ? "🔇" : "🔊"; };
  $("pv-fs").onclick = () => { const w = $("pv-wrap"); if (document.fullscreenElement) document.exitFullscreen(); else w.requestFullscreen().catch(() => {}); };
  $("pv-min").onclick = () => { P.showVid = false; vidShow(true); };
  $("p-vid").onclick = () => { P.showVid = true; vidShow(true); };
  pv.addEventListener("dblclick", () => $("pv-fs").onclick());
  pv.addEventListener("click", () => $("p-play").onclick());
  $("p-x").onclick = () => { P.seq++; pv.pause(); pv.removeAttribute("src"); pv.load(); P.q = []; P.i = -1; playerOpen(false); };
  $("p-dl").onclick = () => {
    const it = P.q[P.i]; if (!it) return;
    dl({ yid: it.yid, title: it.title, artist: it.artist || "", dur: it.dur, kind: it.video ? "video" : "audio", height: 720 });
  };
  document.addEventListener("keydown", (e) => { if (e.code === "Space" && !focused() && !$("player").hidden && !(e.target && e.target.tagName === "BUTTON")) { e.preventDefault(); $("p-play").onclick(); } });

  // ---------- réglages ----------
  function drawSet() {
    const s = S.settings;
    const h = ["<h1>Réglages</h1>",
      "<div class='card'><div class='field'><label>Dossier des téléchargements</label><div class='row'><span class='grow'>" + esc(s.dlDirShown) + "</span><button class='btn sm out' id='s-dir'>Changer</button></div></div></div>",
      "<div class='card'><div class='field'><label>Demandes d'iPhone traitées en même temps (1 à 12)</label><input class='in' id='s-max' type='number' min='1' max='12' value='" + esc(s.maxJobs) + "' style='width:120px'></div>" +
      "<div class='row' style='margin-top:6px'><span class='grow'>Démarrer avec " + (S.platform === "mac" ? "le Mac" : "Windows") + " (comptes en ligne dès l'allumage)</span><button class='sw " + (s.autostart ? "on" : "") + "' id='s-auto' aria-label='Démarrage automatique'></button></div></div>",
      "<div class='card'><div class='row'><div class='grow'><b>Mises à jour automatiques</b><div class='small sub'>La nouvelle version s'installe toute seule dès que plus rien n'est en cours, puis Free tune redémarre.</div></div><button class='sw " + (s.autoUpdate ? "on" : "") + "' id='s-autoupd' aria-label='Mises à jour automatiques'></button></div>" +
      "<div class='row' style='margin-top:10px'><button class='btn sm out' id='s-updcheck'>Vérifier maintenant</button></div></div>",
      "<div class='card'><b>Mot de passe des réglages</b><div class='row' style='margin-top:10px;gap:10px;flex-wrap:wrap'>" +
      "<input class='in' id='s-pwold' type='password' placeholder='Mot de passe actuel' style='flex:1'><input class='in' id='s-pwnew' type='password' placeholder='Nouveau mot de passe' style='flex:1'><button class='btn sm' id='s-pwgo'>Changer</button></div>" +
      "<div class='row' style='margin-top:10px'><button class='btn sm out' id='s-lock'>Verrouiller les réglages</button></div></div>",
      "<div class='card'><b>Outils</b><div class='small sub' style='margin-top:6px'>yt-dlp : téléchargé et mis à jour automatiquement.<br>ffmpeg : " + (S.hasFfmpeg ? "installé" : S.platform === "mac" ? "indisponible" : "sera téléchargé la première fois qu'une vidéo ou un MP3 est demandé (environ 130 Mo)") + ".</div></div>",
      "<h2>Journal</h2><pre class='log'>" + (S.logs.length ? esc(S.logs.join("\n")) : "Rien à signaler.") + "</pre>"];
    const el = $("t-set"); el.innerHTML = h.join("");
    $("s-dir").onclick = () => window.ft.invoke("settings:dir");
    $("s-max").onchange = () => window.ft.invoke("settings:set", { maxJobs: $("s-max").value });
    $("s-auto").onclick = () => window.ft.invoke("settings:set", { autostart: !s.autostart });
    $("s-autoupd").onclick = () => window.ft.invoke("settings:set", { autoUpdate: !s.autoUpdate });
    $("s-updcheck").onclick = () => window.ft.invoke("upd:check").then((has) => toast(has ? "Mise à jour disponible." : "Free tune est à jour."));
    $("s-pwgo").onclick = () => window.ft.invoke("adm:setpw", { old: $("s-pwold").value, pw: $("s-pwnew").value }).then((r) => { if (r.ok) { toast("Mot de passe changé"); $("s-pwold").value = ""; $("s-pwnew").value = ""; } else toast(r.msg || "Échec"); });
    $("s-lock").onclick = () => window.ft.invoke("adm:lock").then(() => { S.unlocked = false; tab = "search"; draw(); });
  }
})();
