(function () {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const mmss = (s) => (s > 0 ? Math.floor(s / 60) + ":" + String(Math.floor(s % 60)).padStart(2, "0") : "");
  const num = (n) => (n >= 1e6 ? (n / 1e6).toFixed(1).replace(".0", "") + " M" : n >= 1e3 ? Math.round(n / 1e3) + " k" : String(n || 0));
  let S = null;                      // état envoyé par le programme
  let tab = "search";
  const A = window.FTAPI;
  const AU = { ready: false, sub: null, subAt: 0, dev: "", err: "", mode: "login", busy: false };
  const R = { q: "", busy: false, albums: [], yt: [], artist: null, album: null, albumTracks: null, msg: "", done: false, newAcc: null, bulk: null };

  function toast(t) { const e = $("toast"); e.textContent = t; e.hidden = false; clearTimeout(toast.t); toast.t = setTimeout(() => { e.hidden = true; }, 2600); }

  // ---------- onglets ----------
  document.querySelectorAll("#nav button").forEach((b) => b.addEventListener("click", () => { tab = b.dataset.t; draw(); }));
  $("lock-btn").onclick = () => askPw();
  function draw() {
    document.querySelectorAll("#nav button").forEach((b) => b.classList.toggle("on", b.dataset.t === tab));
    if (!S) return;
    if (tab === "acc" && !S.unlocked) tab = "search";
    document.querySelectorAll("#nav button").forEach((b) => b.classList.toggle("on", b.dataset.t === tab));
    ["search", "acc", "dl", "me", "set"].forEach((t) => { $("t-" + t).hidden = t !== tab; });
    $("nav-srv").hidden = !S.unlocked; $("lock-btn").hidden = !!S.unlocked;
    $("ver").textContent = S.version || ""; $("plat").textContent = S.platform === "mac" ? "Mac" : S.platform === "srv" ? "Android" : "Windows";
    const on = S.accounts.filter((a) => a.state === "en ligne").length;
    $("nacc").textContent = S.accounts.length ? on + "/" + S.accounts.length : "";
    const act = S.downloads.filter((d) => d.state === "en cours" || d.state === "attente").length;
    $("ndl").textContent = act ? String(act) : "";
    $("side-state").innerHTML = (S.tool ? "⏳ " + esc(S.tool) + "<br>" : "") + "Demandes en cours : <b>" + S.running + "</b> / " + S.maxJobs + "<br>Les comptes restent en ligne même fenêtre fermée (icône dans la barre d'état).";
    const u = $("upd");
    if (S.update) {
      u.hidden = false;
      const auto = S.settings.autoUpdate && S.platform !== "srv";
      u.innerHTML = "<span>🎉 " + esc(S.updMsg || (auto ? "Une nouvelle version de Free tune est disponible : elle s'installe automatiquement dès que plus rien n'est en cours." : "Une nouvelle version de Free tune est disponible.")) + "</span><button class='btn sm' id='upd-go'>" + (S.platform === "srv" ? "Télécharger" : "Installer maintenant") + "</button>";
      $("upd-go").onclick = () => (S.platform === "srv" ? window.ft.invoke("open:url", S.update.url) : window.ft.invoke("upd:install"));
    } else u.hidden = true;
    if (!AU.ready) return;
    if (tab === "search") drawSearch(); else if (tab === "acc") drawAcc(); else if (tab === "dl") drawDl(); else if (tab === "me") drawMe(); else drawSet();
  }
  // les champs de saisie ne doivent pas être effacés quand l'état se met à jour
  const focused = () => { const a = document.activeElement; return a && (a.tagName === "INPUT" || a.tagName === "SELECT"); };
  window.ft.onState((s) => { S = s; reportDl(); if (AU.ready && s.accounts.length !== AU.nsrv) { AU.nsrv = s.accounts.length; ownServers(); } if (focused() && tab !== "dl") { $("nacc").textContent = s.accounts.length ? s.accounts.filter((a) => a.state === "en ligne").length + "/" + s.accounts.length : ""; return; } draw(); });
  window.ft.invoke("state").then((s) => { S = s; draw(); boot(); });

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
  // Chaque téléchargement est d'abord autorisé par le serveur (essai gratuit ou abonnement) ; il compte quand le fichier est bien reçu.
  async function dl(o, quiet) {
    if (!AU.ready) return false;
    let b = null;
    try { b = await A.rpc("desk_begin", { p_yid: o.yid || "", p_title: o.title || "", p_artist: o.artist || "", p_kind: o.kind === "video" ? "video" : "audio" }); } catch (e) { b = null; }
    if (b && b.status === "pay") { openPaywall("dl", null, true); return false; }
    if (!b || b.status !== "ok") { toast(b && b.status === "rate_limited" ? "Trop de demandes d'un coup, patiente un instant." : "Connexion au serveur impossible, réessaie."); return false; }
    await window.ft.invoke("dl:start", Object.assign({}, o, { tag: b.id }));
    if (!quiet) toast("Ajouté aux téléchargements");
    return true;
  }
  const reported = new Set();
  function reportDl() {
    if (!AU.ready || !S) return;
    for (const d of S.downloads) {
      if (!d.tag || reported.has(d.tag)) continue;
      if (d.state === "terminé") { reported.add(d.tag); A.rpc("desk_done", { p_id: d.tag, p_yid: d.yid || null }).then(() => { AU.subAt = 0; refreshSub(); histLoad(true); }).catch(() => reported.delete(d.tag)); }
      else if (d.state === "annulé") { reported.add(d.tag); A.rpc("desk_fail", { p_id: d.tag }).catch(() => {}); }
    }
  }

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
    const trkDl = (t, quiet) => dl({ title: t.trackName, artist: t.artistName, dur: Math.round((t.trackTimeMillis || 0) / 1000), kind: "audio" }, quiet);
    el.querySelectorAll("[data-trk]").forEach((b) => (b.onclick = () => trkDl(R.albumTracks[+b.dataset.trk])));
    if ($("aback")) $("aback").onclick = () => { R.album = null; drawSearch(); };
    if ($("aall")) $("aall").onclick = async () => { let n = 0; for (const t of R.albumTracks) { if (!(await trkDl(t, true))) break; n++; } if (n) toast(n + " titre(s) ajouté(s)"); };
  }

  // ---------- comptes serveur ----------
  const rnd = () => { const a = new Uint8Array(9); crypto.getRandomValues(a); return Array.from(a, (b) => "abcdefghjkmnpqrstuvwxyz23456789"[b % 31]).join("") + "x7"; };
  function drawAcc() {
    if (!R.newAcc) R.newAcc = rnd();
    const h = ["<div class='row'><h1 class='grow'>Gestion des serveurs</h1><button class='btn out sm' id='srv-lock'>🔒 Verrouiller</button></div>",
      "<p class='sub'>Les serveurs restent en ligne sur cet appareil et servent les iPhone comme un Android : ils reçoivent leurs demandes, téléchargent la musique et la renvoient. Ils aident aussi au partage p2p. Six serveurs sont créés automatiquement à l'installation.</p>"];
    h.push("<div class='card'><b>Ajouter un compte</b><div class='row' style='margin-top:10px;gap:10px;flex-wrap:wrap'><div class='field grow' style='margin:0'><label>Pseudo (3-20 : a-z 0-9 _)</label><input class='in' id='na-u' maxlength='20'></div><div class='field grow' style='margin:0'><label>Code (8 caractères minimum)</label><div class='row'><input class='in' id='na-c' maxlength='40' value='" + esc(R.newAcc) + "'></div></div><button class='btn' id='na-go' style='align-self:flex-end'>Créer</button></div>" +
      "<div class='sub small' style='margin-top:8px'>Si le pseudo existe déjà et que le code est le bon, le compte est simplement ajouté à cet appareil.</div></div>");
    h.push("<div class='card'><b>Créer plusieurs comptes d'un coup</b><div class='row' style='margin-top:10px;gap:10px;flex-wrap:wrap'><div class='field grow' style='margin:0'><label>Début du pseudo (ex : serveur → serveur01, serveur02…)</label><input class='in' id='nb-p' maxlength='14' value='serveur'></div><div class='field' style='margin:0;width:110px'><label>Nombre (1-30)</label><input class='in' id='nb-n' type='number' min='1' max='30' value='5'></div><button class='btn' id='nb-go' style='align-self:flex-end'>Créer</button></div>" + (R.bulk ? "<div class='small' style='margin-top:10px'>" + R.bulk.map((r) => (r.ok ? "✅ " : "❌ ") + esc(r.username) + (r.ok ? "" : " : " + esc(r.msg))).join("<br>") + "</div>" : "") + "</div>");
    h.push("<div class='card'><b>Mot de passe de la gestion des serveurs</b><div class='row' style='margin-top:10px;gap:10px;flex-wrap:wrap'><div class='field grow' style='margin:0'><label>Ancien mot de passe</label><input class='in' id='pw-old' type='password' autocomplete='off'></div><div class='field grow' style='margin:0'><label>Nouveau (6 caractères minimum)</label><input class='in' id='pw-new' type='password' autocomplete='off'></div><button class='btn' id='pw-save' style='align-self:flex-end'>Changer</button></div></div>");
    h.push("<h2>Serveurs sur cet appareil (" + S.accounts.length + ")</h2>");
    if (!S.accounts.length) h.push("<div class='empty'>Aucun compte pour l'instant.</div>");
    S.accounts.forEach((a) => {
      const cls = a.state === "en ligne" ? "on" : a.state === "erreur" ? "err" : "wait";
      h.push("<div class='card row'><span class='dot " + cls + "'></span><div class='grow'><b>@" + esc(a.username) + "</b> <span class='pill'>" + esc(a.state) + "</span><div class='small sub'>" + (a.error ? "<span class='warn'>" + esc(a.error) + "</span> · " : "") + (a.jobs ? "⏳ " + a.jobs + " tâche(s) en cours · " : "") + a.helped + " titre(s) envoyé(s) depuis le lancement</div></div>" +
        "<span class='small sub'>Aider les iPhone</span><button class='sw " + (a.share ? "on" : "") + "' data-sh='" + esc(a.username) + "' aria-label='Aider les iPhone'></button>" +
        "<button class='btn sm out' data-cp='" + esc(a.username) + "'>Copier le code</button><button class='btn sm red' data-rm='" + esc(a.username) + "'>Retirer</button></div>");
    });
    const el = $("t-acc"); el.innerHTML = h.join("");
    $("srv-lock").onclick = () => { window.ft.invoke("adm:lock").then(() => { tab = "search"; }); };
    $("pw-save").onclick = async () => { const r = await window.ft.invoke("adm:setpw", { old: $("pw-old").value, pw: $("pw-new").value }); toast(r.ok ? "Mot de passe modifié" : r.msg); if (r.ok) { $("pw-old").value = ""; $("pw-new").value = ""; } };
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
    el.querySelectorAll("[data-sh]").forEach((b) => (b.onclick = () => window.ft.invoke("acc:share", { username: b.dataset.sh, on: !b.classList.contains("on") })));
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

  // ---------- fenêtre (mot de passe, anciens fichiers) ----------
  function modal(html, mount) { const m = $("modal"); m.innerHTML = '<div class="mbox">' + html + "</div>"; m.hidden = false; if (mount) mount(m); }
  function closeModal() { $("modal").hidden = true; $("modal").innerHTML = ""; }
  function askPw() {
    modal("<h2>🔒 Gestion des serveurs</h2><p class='sub small'>Entre le mot de passe pour gérer les serveurs de cet appareil.</p><input class='in' id='m-pw' type='password' autocomplete='off' placeholder='Mot de passe'><p class='er' id='m-er' style='margin-top:8px'></p><div class='row'><button class='btn out sm' id='m-no'>Annuler</button><button class='btn sm' id='m-ok'>Ouvrir</button></div>", (m) => {
      const go = async () => { const r = await window.ft.invoke("adm:unlock", { pw: $("m-pw").value }); if (r.ok) { closeModal(); S.unlocked = true; tab = "acc"; draw(); } else { $("m-er").textContent = "Mot de passe incorrect."; $("m-pw").select(); } };
      $("m-ok").onclick = go; $("m-no").onclick = closeModal; $("m-pw").onkeydown = (e) => { if (e.key === "Enter") go(); }; $("m-pw").focus();
    });
  }

  // ---------- compte : connexion, une seule machine à la fois ----------
  const APPC = () => (S && S.platform === "mac" ? "pcmac" : S && S.platform === "srv" ? "pcsrv" : "pcwin");
  const WHERE = { windows: "un PC Windows", mac: "un Mac", srv: "un téléphone Android" };
  function gate(msg) {
    const reg = AU.mode === "reg", g = $("gate"); g.hidden = false;
    g.innerHTML = '<div class="gbox"><div class="lg">🎧</div><h1>Free tune</h1><p class="sub">' + (reg ? "Crée ton compte : choisis un identifiant et un mot de passe." : "Connecte-toi avec ton identifiant et ton mot de passe.") + "</p>" +
      (msg ? '<p class="er">' + esc(msg) + "</p>" : "") +
      '<div class="field"><label>Identifiant</label><input class="in" id="g-id" maxlength="20" autocomplete="off" autocapitalize="none"></div>' +
      '<div class="field"><label>Mot de passe</label><input class="in" id="g-pw" type="password" maxlength="72" autocomplete="off"></div>' +
      (reg ? '<div class="field"><label>Identifiant de ton parrain (facultatif)</label><input class="in" id="g-sp" maxlength="30" autocomplete="off" autocapitalize="none"></div>' : "") +
      '<button class="btn wide" id="g-go">' + (reg ? "Créer mon compte" : "Me connecter") + '</button><button class="lnk" id="g-sw">' + (reg ? "J'ai déjà un compte" : "Nouvel utilisateur ? Créer un compte") + "</button>" +
      '<p class="small sub" style="margin-top:14px">Ton compte garde la trace de tes téléchargements et fonctionne sur un seul appareil à la fois.' + (reg ? " Essai gratuit : 24 heures ou 3 téléchargements." : "") + "</p></div>";
    $("g-sw").onclick = () => { AU.mode = reg ? "login" : "reg"; gate(""); };
    const go = async () => {
      const id = $("g-id").value, pw = $("g-pw").value; if (!id || !pw) { gate("Écris ton identifiant et ton mot de passe."); $("g-id").value = id; return; }
      $("g-go").disabled = true; $("g-go").textContent = "Un instant…";
      const r = reg ? await A.register(id, pw, APPC(), $("g-sp").value) : await A.login(id, pw);
      if (!r.ok) { gate(r.msg); $("g-id").value = id; return; }
      AU.fresh = true; enter();
    };
    $("g-go").onclick = go; $("g-pw").onkeydown = (e) => { if (e.key === "Enter") go(); };
    (reg ? $("g-id") : $("g-id")).focus();
  }
  async function claim() { return A.rpc("desk_claim", { p_dev: S.deviceId, p_platform: S.platform }).catch(() => null); }
  async function enter() {
    const c = await claim();
    if (!c) { A.has() ? gate("Pas de connexion internet. Vérifie ta connexion puis réessaie.") : gate(""); return; }
    if (c.status === "busy") { A.clear(); gate("Ce compte est déjà connecté sur " + (WHERE[c.platform] || "une autre machine") + ". Un identifiant ne peut être utilisé que sur une machine à la fois : déconnecte-le là-bas (Mon compte › Se déconnecter) ou attends une minute ou deux si cette machine est éteinte."); return; }
    if (c.status !== "ok") { A.clear(); gate("Connexion expirée : reconnecte-toi."); return; }
    AU.ready = true; $("gate").hidden = true; AU.sub = null;
    clearInterval(AU.beat); AU.beat = setInterval(heartbeat, 25000);
    refreshSub(); histLoad(AU.fresh ? false : false); ownServers(); sponsorLoad(); walletLoad();
    AU.fresh = false; draw();
  }
  async function heartbeat() {
    if (!AU.ready) return;
    const c = await claim();
    if (c && c.status === "busy") logout("Ce compte vient d'être connecté sur " + (WHERE[c.platform] || "une autre machine") + ".");
    else if (c && c.status === "unauthenticated") logout("Connexion expirée : reconnecte-toi.");
  }
  async function logout(msg, release) {
    clearInterval(AU.beat); AU.ready = false; closePaywall(); closeModal();
    try { document.getElementById("pv").pause(); } catch (e) { /* ignoré */ }
    try { if (release) await A.rpc("desk_release", { p_dev: S.deviceId }); } catch (e) { /* ignoré */ }
    A.clear(); AU.sub = null; AU.hist = []; AU.missing = []; AU.sp = null; AU.wl = null; reported.clear();
    const x = $("p-x"); if (x) x.onclick();
    AU.mode = "login"; gate(msg || ""); draw();
  }
  async function ownServers() { if (S && S.accounts.length) A.rpc("desk_own", { p_names: S.accounts.map((a) => a.username) }).catch(() => {}); }
  function boot() { if (AU.booted) return; AU.booted = true; if (A.has()) enter(); else gate(""); }

  // ---------- abonnement : essai gratuit puis 500 FCFA / 30 jours ----------
  const PW = { el: null, step: "form", msg: "", ref: null, url: "", ussd: "", until: null, retry: null, t: null };
  const pwOp = (d) => (/^6(7\d|5[0-4]|8[0-4])/.test(d) ? "MTN Mobile Money" : /^6(9\d|5[5-9]|8[5-9])/.test(d) ? "Orange Money" : "");
  const fdate = (t) => { try { return new Date(t).toLocaleDateString("fr-FR", { day: "numeric", month: "long", year: "numeric" }); } catch (e) { return ""; } };
  async function refreshSub() {
    if (!AU.ready) return;
    try { const j = await A.rpc("sub_status", {}); if (j && j.status === "ok") { AU.sub = j; AU.subAt = Date.now(); } } catch (e) { return; }
    subGuard(); if (tab === "me") drawMe();
  }
  // Essai fini ou abonnement terminé : l'écran de paiement bloque tout et ne se ferme qu'en payant.
  function subGuard() {
    const j = AU.sub; if (!j) return;
    if (PW.el && PW.el.dataset.b === "1" && j.state !== "expired" && PW.step !== "ok" && PW.step !== "wait") { closePaywall(); return; }
    if (j.state === "expired" && !(PW.el && PW.el.dataset.b === "1")) {
      try { document.getElementById("pv").pause(); } catch (e) { /* ignoré */ }
      openPaywall(Number(j.trial_left) <= 0 && !j.paid_until ? "dl" : "expired", null, true);
    }
  }
  setInterval(refreshSub, 60000);
  function pwDraw() {
    const d = PW.el; if (!d) return;
    const reason = d.dataset.r;
    const sub = reason === "dl" ? "Tu as utilisé tes téléchargements gratuits." : reason === "expired" ? "Ton abonnement est terminé." : "Continue à profiter de Free tune.";
    let body = "";
    if (PW.step === "form" || PW.step === "fail") {
      const v = (d.querySelector("#pw-ph") || {}).value || "";
      body = (PW.step === "fail" ? '<p class="er">' + esc(PW.msg) + "</p>" : "") +
        '<label class="small sub">Ton numéro Orange Money ou MTN MoMo</label><div class="ph"><span>+237</span><input id="pw-ph" type="tel" inputmode="numeric" maxlength="9" placeholder="6XX XX XX XX" value="' + esc(v) + '"></div>' +
        '<div class="op" id="pw-op">' + esc(pwOp(v)) + '</div><button class="bt" id="pw-go">' + (v.length === 9 ? "Payer 500 FCFA" : "Entre ton numéro") + "</button>";
    } else if (PW.step === "wait") {
      body = '<div class="box c"><div class="sp"></div><b>Confirme le paiement sur ton téléphone</b><p>' +
        (PW.url ? "Termine le paiement sur la page sécurisée (Orange Money ou MTN MoMo), puis reviens ici : ton abonnement s'activera tout seul." :
          "Une demande de 500 FCFA arrive sur ton téléphone : valide-la avec ton code secret." + (PW.ussd ? "<br>Si rien n'apparaît, compose " + esc(PW.ussd) + "." : "")) + "</p>" +
        (PW.url ? '<button class="bt o" id="pw-open">Ouvrir la page de paiement</button>' : "") + "</div>";
    } else if (PW.step === "ok") {
      body = '<div class="box c"><div style="font-size:42px">✅</div><b>Merci ! Ton abonnement est actif</b><p>jusqu\'au ' + esc(fdate(PW.until)) + '</p><button class="bt" id="pw-done">Continuer</button></div>';
    }
    const blocking = d.dataset.b === "1" && PW.step !== "ok";
    d.innerHTML = '<div class="in2">' + (blocking || PW.step === "ok" ? '<div style="height:38px"></div>' : '<button class="x" id="pw-x" aria-label="Fermer">✕</button>') + '<div class="lg">🎧</div><h2>Free tune Premium</h2><p class="sub">' + sub + "</p>" +
      '<div class="box"><div class="pr">500<small> FCFA / mois</small></div><ul><li>Téléchargements illimités, en audio et en vidéo</li><li>Lecture directe, historique et retéléchargement sur ton compte</li><li>Valable 30 jours sur ton compte (Android, iPhone, Mac et PC)</li><li>Sans renouvellement automatique : tu paies quand tu veux</li></ul></div>' +
      body + '<div class="ft">Paiement sécurisé par Mobile Money.</div></div>';
    const x = d.querySelector("#pw-x"); if (x) x.onclick = closePaywall;
    const ph = d.querySelector("#pw-ph");
    if (ph) {
      ph.oninput = () => { ph.value = ph.value.replace(/\D/g, "").slice(0, 9); d.querySelector("#pw-op").textContent = pwOp(ph.value); d.querySelector("#pw-go").textContent = ph.value.length === 9 ? "Payer 500 FCFA" : "Entre ton numéro"; };
      d.querySelector("#pw-go").onclick = () => payGo(ph.value);
      ph.onkeydown = (e) => { if (e.key === "Enter") payGo(ph.value); };
    }
    const op = d.querySelector("#pw-open"); if (op) op.onclick = () => window.ft.invoke("open:url", PW.url);
    const dn = d.querySelector("#pw-done"); if (dn) dn.onclick = () => { const r = PW.retry; closePaywall(); refreshSub(); if (r) r(); };
  }
  function closePaywall() { clearTimeout(PW.t); PW.ref = null; if (PW.el) { PW.el.remove(); PW.el = null; } }
  function openPaywall(reason, retry, blocking) {
    closePaywall();
    PW.step = "form"; PW.msg = ""; PW.url = ""; PW.ussd = ""; PW.retry = retry || null;
    const d = document.createElement("div"); d.id = "pw"; d.dataset.r = reason || "dl"; d.dataset.b = blocking ? "1" : "";
    document.body.appendChild(d); PW.el = d; pwDraw();
  }
  async function payGo(phone) {
    if (phone.length !== 9 || phone[0] !== "6") { PW.step = "fail"; PW.msg = "Numéro invalide : 9 chiffres, commençant par 6."; pwDraw(); return; }
    PW.step = "wait"; PW.url = ""; PW.ussd = ""; pwDraw();
    let j = null;
    try { j = await A.fn({ action: "pay", phone }); } catch (e) { j = null; }
    if (!PW.el) return;
    if (j && j.status === "pending" && j.reference) {
      PW.ref = j.reference; PW.url = j.pay_url || ""; PW.ussd = j.ussd_code || ""; pwDraw();
      if (PW.url) window.ft.invoke("open:url", PW.url);
      pwPoll(0);
    } else if (j && j.status === "bad_phone") { PW.step = "fail"; PW.msg = "Numéro invalide : 9 chiffres, commençant par 6."; pwDraw(); }
    else { PW.step = "fail"; PW.msg = "Paiement impossible pour le moment. " + String((j && j.message) || "").slice(0, 120); pwDraw(); }
  }
  async function pwPoll(n) {
    clearTimeout(PW.t);
    const ref = PW.ref; if (!ref || !PW.el) return;
    if (n >= 150) { PW.step = "fail"; PW.msg = "Pas de confirmation reçue. Si tu as été débité, ton abonnement s'activera dans quelques minutes."; PW.ref = null; pwDraw(); return; }
    let j = null;
    try { j = await A.fn({ action: "check", reference: ref }); } catch (e) { j = null; }
    if (!PW.el || PW.ref !== ref) return;
    if (j && j.status === "successful") {
      let until = j.paid_until || null;
      try { const st = await A.rpc("sub_status", {}); if (st && st.paid_until) { until = st.paid_until; AU.sub = st; } } catch (e) { /* ignoré */ }
      PW.until = until; PW.step = "ok"; PW.ref = null; pwDraw(); return;
    }
    if (j && j.status === "failed") { PW.step = "fail"; PW.msg = "Le paiement a été refusé ou annulé. Vérifie ton solde et réessaie."; PW.ref = null; pwDraw(); return; }
    PW.t = setTimeout(() => pwPoll(n + 1), 4000);
  }

  // ---------- historique du compte : retélécharger sur une nouvelle machine ----------
  async function histLoad(quiet) {
    if (!AU.ready) return;
    let l = null;
    try { l = await A.rpc("desk_list", {}); } catch (e) { return; }
    AU.hist = Array.isArray(l) ? l : [];
    let here = [];
    try { here = await window.ft.invoke("hist:missing", AU.hist.map((x) => ({ artist: x.artist, title: x.title, kind: x.kind }))); } catch (e) { here = []; }
    AU.missing = AU.hist.filter((x, i) => !here[i]);
    if (tab === "me") drawMe();
    if (quiet || !AU.missing.length) return;
    const key = "ft_hd_" + A.uid(); let seen = 0;
    try { seen = +localStorage.getItem(key) || 0; } catch (e) { seen = 0; }
    if (AU.missing.length > seen) askRestore();
  }
  function askRestore() {
    const n = AU.missing.length;
    modal("<h2>📥 Retrouver tes anciens fichiers ?</h2><p>Ton compte contient <b>" + n + " titre" + (n > 1 ? "s" : "") + "</b> déjà téléchargé" + (n > 1 ? "s" : "") + " sur une autre machine, mais absent de cet ordinateur.</p><p class='small sub'>Les retélécharger ne compte pas dans ton essai gratuit.</p><div class='row'><button class='btn out sm' id='m-no'>Plus tard</button><button class='btn sm' id='m-ok'>Tout retélécharger</button></div>", () => {
      $("m-no").onclick = () => { try { localStorage.setItem("ft_hd_" + A.uid(), String(n)); } catch (e) { /* ignoré */ } closeModal(); };
      $("m-ok").onclick = () => { closeModal(); restoreAll(); };
    });
  }
  async function restoreAll() {
    const todo = AU.missing.slice(); if (!todo.length) { toast("Tout est déjà sur cet ordinateur."); return; }
    let b = null;
    try { b = await A.rpc("desk_begin", { p_yid: "", p_title: "", p_artist: "", p_kind: "audio", p_restore: true }); } catch (e) { b = null; }
    if (b && b.status === "pay") { openPaywall("dl", () => restoreAll(), true); return; }
    if (!b || b.status !== "ok") { toast("Connexion au serveur impossible, réessaie."); return; }
    for (const m of todo) await window.ft.invoke("dl:start", { yid: m.yid || "", title: m.title, artist: m.artist, kind: m.kind, height: 720 });
    toast(todo.length + " titre(s) en cours de retéléchargement"); tab = "dl"; AU.missing = []; draw();
  }

  // ---------- parrainage et cagnotte ----------
  async function sponsorLoad() { try { const r = await A.rpc("sponsor_get", {}); AU.sp = { name: (r && r.name) || null, msg: "" }; } catch (e) { /* hors ligne */ } if (tab === "me") drawMe(); }
  async function walletLoad() { try { const r = await A.rpc("ref_get", {}); if (r && r.status === "ok") AU.wl = Object.assign({ form: false, msg: "" }, AU.wl && { form: AU.wl.form, msg: AU.wl.msg }, r); } catch (e) { /* hors ligne */ } if (tab === "me" && !(AU.wl && AU.wl.form)) drawMe(); }
  const shareText = () => "Je t'invite sur Free tune 🎧 Musique et vidéos à télécharger, sur iPhone, Android, Mac et PC. Télécharge l'application ici :\nhttps://stephaneproject237-spec.github.io/tu-me-manque/casque/telecharger/?p=" + encodeURIComponent(A.id()) + "\nÀ l'inscription, indique mon identifiant comme parrain : " + A.id();

  // ---------- page « Mon compte » ----------
  function drawMe() {
    const el = $("t-me"); if (!el) return;
    const sub = AU.sub, wl = AU.wl, sp = AU.sp, h = ["<h1>Mon compte</h1>"];
    h.push("<div class='card'><div class='row'><div class='grow'><b>@" + esc(A.id()) + "</b><div class='small sub'>Connecté sur cet appareil (" + (S.platform === "mac" ? "Mac" : S.platform === "srv" ? "Android" : "Windows") + "). Un identifiant ne peut être utilisé que sur une machine à la fois.</div></div><button class='btn out sm' id='me-out'>Se déconnecter</button></div></div>");
    let st = "<span class='sub'>…</span>", action = "";
    if (sub) {
      if (sub.state === "active") { st = "<span class='chip ok'>Abonnement actif</span> jusqu'au " + esc(fdate(sub.paid_until)); action = "<button class='btn sm' id='me-pay'>Renouveler (500 FCFA)</button>"; }
      else if (sub.state === "trial") { st = "<span class='chip'>Essai gratuit</span> " + esc(String(sub.trial_left)) + " téléchargement(s) restant(s) · jusqu'au " + esc(fdate(sub.trial_end)); action = "<button class='btn sm' id='me-pay'>S'abonner (500 FCFA / mois)</button>"; }
      else st = "<span class='chip bad'>Terminé</span> Abonne-toi pour continuer.";
    }
    h.push("<div class='card'><b>Abonnement</b><div class='row' style='margin-top:8px'><div class='grow stat'>" + st + "</div>" + action + "</div></div>");
    const miss = AU.missing ? AU.missing.length : 0;
    h.push("<div class='card'><b>Historique</b><div class='row' style='margin-top:8px'><div class='grow small sub'>" + (AU.hist ? AU.hist.length : 0) + " titre(s) téléchargés avec ce compte" + (miss ? " · <b>" + miss + " absent(s) de cet ordinateur</b>" : " · tout est présent sur cet ordinateur") + "</div>" + (miss ? "<button class='btn sm' id='me-rest'>Retélécharger</button>" : "") + "</div></div>");
    // parrainage
    let p = "<b>Parrainage et cagnotte</b>";
    if (wl) {
      const w = Number(wl.wallet) || 0;
      p += "<div style='font-size:26px;margin:8px 0'><b>" + w + " FCFA</b></div><div class='small sub'>Tu gagnes 100 FCFA quand une personne que tu as invitée paie son premier abonnement.<br>" + wl.filleuls + " filleul(s) · " + wl.payes + " ont payé</div>";
      if (wl.pending) p += "<p>⏳ Retrait de " + Number(wl.pending.amount) + " FCFA demandé pour le " + esc(wl.pending.phone) + " (" + esc(wl.pending.name) + "). Il sera envoyé bientôt.</p>";
      else if (w >= 500) {
        if (!wl.form) p += "<button class='btn sm' id='wl-form' style='margin-top:10px'>Retirer ma cagnotte</button>";
        else p += "<div class='field' style='margin-top:10px'><label>Numéro qui recevra l'argent</label><input class='in' id='wl-ph' inputmode='numeric' maxlength='12' placeholder='6XXXXXXXX' autocomplete='off'></div><div class='field'><label>Nom qui doit apparaître lors de la transaction</label><input class='in' id='wl-nm' maxlength='60' placeholder='Nom et prénom' autocomplete='off'></div><button class='btn sm' id='wl-send'>Valider la demande</button>";
        if (wl.msg) p += "<p class='warn small'>" + esc(wl.msg) + "</p>";
      } else p += "<div class='small sub' style='margin-top:6px'>Retrait possible à partir de 500 FCFA.</div>";
      if (wl.last && !wl.pending) { p += "<p>✅ Dépôt effectué : " + Number(wl.last.amount) + " FCFA envoyés au " + esc(wl.last.phone) + ".</p>"; if (wl.last.proof) p += "<button class='btn out sm' id='wl-proof'>Voir la preuve de paiement</button>"; }
    }
    p += "<div style='margin-top:14px'><b class='small'>Inviter</b><div class='small sub' style='margin:4px 0 8px'>Ton identifiant de parrain : <b>@" + esc(A.id()) + "</b></div><button class='btn out sm' id='sp-copy'>Copier le message d'invitation</button></div>";
    p += "<div style='margin-top:14px'><b class='small'>Mon parrain</b>" + (sp && sp.name ? "<div style='margin-top:4px'>@" + esc(sp.name) + "</div>" : sp ? "<div class='small sub' style='margin:4px 0 8px'>Si quelqu'un t'a fait découvrir Free tune, écris son identifiant :</div><div class='row'><input class='in grow' id='sp-in' maxlength='30' placeholder='Identifiant du parrain' autocomplete='off'><button class='btn sm' id='sp-set'>Valider</button></div>" : "<div class='small sub'>…</div>") + (sp && sp.msg ? "<div class='small warn' style='margin-top:6px'>" + esc(sp.msg) + "</div>" : "") + "</div>";
    h.push("<div class='card'>" + p + "</div>");
    el.innerHTML = h.join("");
    const on = (id, fn) => { const b = $(id); if (b) b.onclick = fn; };
    on("me-out", () => logout("", true));
    on("me-pay", () => openPaywall("renew", null, false));
    on("me-rest", () => { askRestore(); });
    on("wl-form", () => { AU.wl.form = true; AU.wl.msg = ""; drawMe(); });
    on("wl-send", async () => {
      const ph = $("wl-ph").value, nm = $("wl-nm").value; let r = null;
      try { r = await A.rpc("payout_request", { p_phone: ph, p_name: nm }); } catch (e) { r = null; }
      const s2 = r && r.status;
      if (s2 === "ok") { AU.wl.form = false; AU.wl.msg = ""; await walletLoad(); toast("Demande de retrait envoyée"); }
      else { AU.wl.msg = s2 === "bad_phone" ? "Numéro invalide : 9 chiffres, qui commencent par 6." : s2 === "bad_name" ? "Écris le nom qui apparaît lors de la transaction." : s2 === "too_low" ? "Il faut au moins 500 FCFA." : s2 === "already" ? "Une demande est déjà en attente." : "Impossible pour le moment."; }
      drawMe();
    });
    on("wl-proof", () => window.ft.invoke("open:url", window.FT_CFG.url + "/storage/v1/object/public/chat/" + AU.wl.last.proof));
    on("sp-copy", async () => { try { await navigator.clipboard.writeText(shareText()); toast("Message copié : colle-le dans WhatsApp, SMS…"); } catch (e) { toast("Copie impossible"); } });
    on("sp-set", async () => {
      const v = $("sp-in").value.trim().replace(/^@+/, ""); if (!v) return; let r = null;
      try { r = await A.rpc("sponsor_set", { p_name: v }); } catch (e) { r = null; }
      const s2 = r && r.status;
      if (s2 === "ok" || s2 === "already") AU.sp = { name: r.name, msg: "" };
      else AU.sp.msg = s2 === "unknown" ? "Cet identifiant n'existe pas." : s2 === "self" ? "Tu ne peux pas être ton propre parrain." : "Impossible pour le moment.";
      drawMe();
    });
  }

  // ---------- réglages ----------
  function drawSet() {
    const s = S.settings;
    const h = ["<h1>Réglages</h1>",
      "<div class='card'><div class='field'><label>Dossier des téléchargements</label><div class='row'><span class='grow'>" + esc(s.dlDirShown) + "</span><button class='btn sm out' id='s-dir'>Changer</button></div></div></div>",
      "<div class='card'><div class='field'><label>Demandes d'iPhone traitées en même temps (1 à 12)</label><input class='in' id='s-max' type='number' min='1' max='12' value='" + esc(s.maxJobs) + "' style='width:120px'></div>" +
      "<div class='row' style='margin-top:6px'><span class='grow'>Démarrer avec l'ordinateur (serveurs en ligne dès l'allumage)</span><button class='sw " + (s.autostart ? "on" : "") + "' id='s-auto' aria-label='Démarrage automatique'></button></div></div>",
      "<div class='card'><div class='row'><div class='grow'><b>Mises à jour automatiques</b><div class='small sub'>" + (S.platform === "srv" ? "Une notification apparaît quand une nouvelle version est prête." : "La nouvelle version s'installe toute seule dès que plus rien n'est en cours, puis Free tune redémarre.") + "</div></div><button class='sw " + (s.autoUpdate ? "on" : "") + "' id='s-autoupd' aria-label='Mises à jour automatiques'></button></div>" +
        "<div class='row' style='margin-top:10px'><span class='small sub grow'>Version " + esc(S.version) + (S.updMsg ? " · " + esc(S.updMsg) : "") + "</span><button class='btn out sm' id='s-chk'>Vérifier maintenant</button></div></div>",
      "<div class='card'><b>Outils</b><div class='small sub' style='margin-top:6px'>yt-dlp : téléchargé et mis à jour automatiquement.<br>ffmpeg : " + (S.hasFfmpeg ? "installé" : S.platform === "mac" ? "indisponible" : "sera téléchargé la première fois qu'une vidéo ou un MP3 est demandé (environ 130 Mo)") + ".</div></div>",
      "<h2>Journal</h2><pre class='log'>" + (S.logs.length ? esc(S.logs.join("\n")) : "Rien à signaler.") + "</pre>"];
    const el = $("t-set"); el.innerHTML = h.join("");
    $("s-dir").onclick = () => window.ft.invoke("settings:dir");
    $("s-max").onchange = () => window.ft.invoke("settings:set", { maxJobs: $("s-max").value });
    $("s-auto").onclick = () => window.ft.invoke("settings:set", { autostart: !s.autostart });
    $("s-autoupd").onclick = () => window.ft.invoke("settings:set", { autoUpdate: !s.autoUpdate });
    $("s-chk").onclick = async () => { $("s-chk").disabled = true; const f = await window.ft.invoke("upd:check"); toast(f ? "Une nouvelle version est disponible." : "Free tune est à jour."); };
  }
})();
