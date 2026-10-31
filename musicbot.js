// Bot @music (version iPhone / web) : recherche dans la musique libre d'Audius, écoute, téléchargement si l'artiste l'autorise.
(function () {
  const APP = "TuMeManques";
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  let host = null, open = false, busy = false, msgs = [], audio = null, playing = null;
  const files = {}; // id -> Blob
  const prog = {}; // id -> 0..1

  async function base() {
    if (host) return host;
    try {
      const r = await fetch("https://api.audius.co");
      const j = await r.json();
      const l = (j && j.data) || [];
      host = l.length ? l[Math.floor(Math.random() * l.length)] : "https://api.audius.co";
    } catch (e) { host = "https://api.audius.co"; }
    return host;
  }
  const mmss = (s) => (s > 0 ? Math.floor(s / 60) + ":" + String(Math.floor(s % 60)).padStart(2, "0") : "");

  async function search(q) {
    const b = await base();
    const r = await fetch(b + "/v1/tracks/search?query=" + encodeURIComponent(q) + "&app_name=" + APP);
    if (!r.ok) throw new Error("http " + r.status);
    const j = await r.json();
    return (j.data || []).filter((t) => t && t.id && t.is_streamable !== false).slice(0, 6);
  }
  const art = (t) => (t.artwork && (t.artwork["480x480"] || t.artwork["150x150"])) || "";
  const streamUrl = (t) => host + "/v1/tracks/" + encodeURIComponent(t.id) + "/stream?app_name=" + APP;

  function ensureDom() {
    if (!$("mb")) {
      const d = document.createElement("div");
      d.id = "mb"; d.className = "gm mb"; d.hidden = true;
      d.innerHTML = `<div class="gm-head"><button class="ch-icon gm-x" data-m="close" aria-label="Fermer">✕</button>
        <div class="mb-title"><b>🎵 @music</b><small>Ton bot musique</small></div><span></span></div>
        <div class="mb-list" id="mb-list"></div>
        <form class="mb-bar" id="mb-form"><input id="mb-q" type="search" autocomplete="off" placeholder="Artiste et titre…" enterkeyhint="search"><button class="mb-go" id="mb-go" type="submit">🔍</button></form>`;
      d.addEventListener("click", onClick);
      document.body.appendChild(d);
      $("mb-form").addEventListener("submit", (e) => { e.preventDefault(); ask($("mb-q").value); });
    }
  }

  function say(m) { msgs.push(m); render(); }
  function render() {
    const l = $("mb-list"); if (!l) return;
    let h = "";
    msgs.forEach((m, i) => {
      if (m.t === "bot") h += `<div class="mb-row mb-pop"><i class="mb-ava">🎵</i><div class="mb-bot">${esc(m.text)}</div></div>`;
      else if (m.t === "me") h += `<div class="mb-row me mb-pop"><div class="mb-me">${esc(m.text)}</div></div>`;
      else if (m.t === "res") {
        h += `<div class="mb-res mb-pop">`;
        m.list.forEach((t, k) => {
          const done = !!files[t.id], ld = prog[t.id] != null;
          h += `<button class="mb-card${k === 0 ? " best" : ""}" data-m="pick" data-i="${i}" data-k="${k}">
            <img src="${esc(art(t))}" alt="" loading="lazy"><span class="mb-ct">${k === 0 ? "<em>MEILLEURE CORRESPONDANCE</em>" : ""}<b>${esc(t.title)}</b>
            <small>${esc((t.user && t.user.name) || "")}${t.duration ? " · " + mmss(t.duration) : ""}${t.is_downloadable ? " · téléchargeable" : ""}</small></span>
            <i>${done ? "✓" : ld ? "…" : t.is_downloadable ? "⬇" : "▶"}</i></button>`;
        });
        h += `</div>`;
      } else if (m.t === "song") {
        const t = m.song, f = files[t.id], p = prog[t.id];
        h += `<div class="mb-song mb-pop"><div class="mb-sh"><img src="${esc(art(t))}" alt=""><span><b>${esc(t.title)}</b>
          <small>${f ? "MP3 · " + Math.round(f.size / 1048576) + " Mo" : p != null ? "Téléchargement… " + Math.round(p * 100) + " %" : m.stream ? "Écoute en ligne" : "Échec"}</small></span></div>`;
        if (p != null && !f) h += `<div class="mb-prog"><i style="width:${Math.max(4, Math.round(p * 100))}%"></i></div>`;
        if (f || m.stream) {
          h += `<div class="mb-acts"><button class="mb-btn main" data-m="play" data-i="${i}">${playing === t.id ? "⏸ Pause" : "▶ Écouter"}</button>`;
          if (f) h += `<button class="mb-btn" data-m="save" data-i="${i}">💾 Enregistrer</button><button class="mb-btn" data-m="share" data-i="${i}">📤 Envoyer</button>`;
          h += `</div>`;
        }
        h += `</div>`;
      }
    });
    l.innerHTML = h;
    l.scrollTop = l.scrollHeight;
  }

  async function ask(q) {
    q = (q || "").trim();
    if (!q || busy) return;
    $("mb-q").value = "";
    say({ t: "me", text: q });
    busy = true; $("mb-go").textContent = "…";
    say({ t: "bot", text: "Je cherche « " + q + " »…" });
    try {
      const r = await search(q);
      msgs.pop();
      if (!r.length) say({ t: "bot", text: "Je n'ai rien trouvé pour « " + q + " ». Sur iPhone, je cherche dans la musique libre (artistes indépendants). Pour tous les artistes, utilise l'appli Android." });
      else { say({ t: "bot", text: "Voilà ce que j'ai trouvé. Touche une chanson :" }); say({ t: "res", list: r }); }
    } catch (e) {
      msgs.pop();
      say({ t: "bot", text: "Oups, la recherche n'a pas marché. Vérifie ta connexion et réessaie." });
    } finally { busy = false; $("mb-go").textContent = "🔍"; }
  }

  async function download(t, msg) {
    prog[t.id] = 0; render();
    try {
      const r = await fetch(host + "/v1/tracks/" + encodeURIComponent(t.id) + "/download?app_name=" + APP);
      if (!r.ok || !r.body) throw new Error("http " + r.status);
      const total = Number(r.headers.get("content-length")) || 0;
      const reader = r.body.getReader(); const parts = []; let got = 0, last = 0;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        parts.push(value); got += value.length;
        if (total) { prog[t.id] = got / total; if (Date.now() - last > 300) { last = Date.now(); render(); } }
      }
      files[t.id] = new Blob(parts, { type: r.headers.get("content-type") || "audio/mpeg" });
      delete prog[t.id];
      say({ t: "bot", text: "C'est prêt ! Tu peux l'écouter, l'enregistrer dans ton iPhone ou l'envoyer à quelqu'un." });
    } catch (e) {
      delete prog[t.id];
      msg.stream = true;
      say({ t: "bot", text: "Je n'ai pas pu la télécharger, mais tu peux l'écouter en ligne." });
    }
  }

  function pick(t) {
    const m = { t: "song", song: t, stream: !t.is_downloadable };
    say(m);
    if (t.is_downloadable) download(t, m);
    else say({ t: "bot", text: "L'artiste n'autorise pas le téléchargement de ce titre, mais tu peux l'écouter ici." });
  }

  function play(m) {
    const t = m.song;
    if (playing === t.id && audio) { audio.pause(); playing = null; render(); return; }
    if (audio) audio.pause();
    const src = files[t.id] ? URL.createObjectURL(files[t.id]) : streamUrl(t);
    audio = new Audio(src);
    audio.onended = () => { playing = null; render(); };
    audio.play().catch(() => {});
    playing = t.id; render();
  }

  const fname = (t) => (t.title || "chanson").replace(/[\\/:*?"<>|]/g, " ").trim().slice(0, 80) + ".mp3";
  function save(m) {
    const f = files[m.song.id]; if (!f) return;
    const a = document.createElement("a");
    a.href = URL.createObjectURL(f); a.download = fname(m.song);
    document.body.appendChild(a); a.click(); a.remove();
    say({ t: "bot", text: "Enregistrement lancé 💾 Sur iPhone, choisis « Télécharger » : la chanson va dans l'app Fichiers › Téléchargements." });
  }
  async function share(m) {
    const f = files[m.song.id]; if (!f) return;
    const file = new File([f], fname(m.song), { type: "audio/mpeg" });
    try {
      if (navigator.canShare && navigator.canShare({ files: [file] })) await navigator.share({ files: [file], title: m.song.title });
      else save(m);
    } catch (e) { /* annulé */ }
  }

  function onClick(ev) {
    const b = ev.target.closest("[data-m]"); if (!b) return;
    const a = b.dataset.m, m = msgs[Number(b.dataset.i)];
    if (a === "close") return close();
    if (a === "pick" && m) { const t = m.list[Number(b.dataset.k)]; if (t && !files[t.id] && prog[t.id] == null) pick(t); }
    if (a === "play" && m) play(m);
    if (a === "save" && m) save(m);
    if (a === "share" && m) share(m);
  }

  function openBot(q) {
    ensureDom();
    open = true; $("mb").hidden = false;
    if (!msgs.length) say({ t: "bot", text: "Salut, je suis @music 🎵 Dis-moi l'artiste et le titre. Sur iPhone je cherche dans la musique libre d'Audius ; pour tous les artistes, utilise l'appli Android." });
    else render();
    if (q) ask(q); else setTimeout(() => { try { $("mb-q").focus(); } catch (e) { /* ignoré */ } }, 250);
  }
  function close() { open = false; if (audio) audio.pause(); playing = null; const d = $("mb"); if (d) d.hidden = true; }

  // Suggestion « @music » quand on commence à taper « @ » dans le chat.
  document.addEventListener("input", (e) => {
    if (!e.target || e.target.id !== "ch-input") return;
    const v = e.target.value.trim().toLowerCase();
    let s = $("mb-sug");
    const show = v.startsWith("@") && "@music".startsWith(v.slice(0, 6));
    if (show && !s && $("ch-bottom")) {
      s = document.createElement("button");
      s.id = "mb-sug"; s.className = "mb-sug"; s.type = "button";
      s.innerHTML = "🎵 <b>@music</b><span> · chercher et télécharger une chanson</span>";
      s.addEventListener("click", () => {
        const inp = $("ch-input"); const t = inp.value.trim();
        const q = /^@music/i.test(t) ? t.replace(/^@music/i, "").trim() : "";
        inp.value = "@music " + q; inp.dispatchEvent(new Event("input", { bubbles: true })); inp.focus();
      });
      $("ch-bottom").insertBefore(s, $("ch-bottom").firstChild);
    } else if (!show && s) s.remove();
  });

  async function dlUrl(t) { const b = await base(); return b + "/v1/tracks/" + encodeURIComponent(t.id) + "/download?app_name=" + APP; }
  window.TMM_MUSIC = { open: openBot, close, isOpen: () => open, search: async (q) => { await base(); return search(q); }, art, dlUrl };
})();
