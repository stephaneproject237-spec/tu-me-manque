(() => {
  "use strict";
  // Messages éphémères (vue unique) et messages programmés.
  const host = document.getElementById("chat");
  const A = () => window.TMM_API;
  const C = () => window.TMM_CHAT;
  const $ = (id) => document.getElementById(id);
  const esc = (v) => A().esc(v);
  const rpc = (name, body) => A().authed("POST", "/rest/v1/rpc/" + name, body || {});
  const pad = (n) => String(n).padStart(2, "0");

  // ---------- Choix d'heure ----------
  function localInput(d) { return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()) + "T" + pad(d.getHours()) + ":" + pad(d.getMinutes()); }
  function whenLabel(d) {
    const now = new Date();
    const same = d.toDateString() === now.toDateString();
    const tom = new Date(now.getTime() + 86400000).toDateString() === d.toDateString();
    const hm = pad(d.getHours()) + ":" + pad(d.getMinutes());
    if (same) return "aujourd'hui à " + hm;
    if (tom) return "demain à " + hm;
    return d.toLocaleDateString("fr-FR", { weekday: "short", day: "numeric", month: "short" }) + " à " + hm;
  }
  function presets() {
    const now = new Date();
    const at = (h, addDay) => { const d = new Date(now); d.setDate(d.getDate() + addDay); d.setHours(h, 0, 0, 0); return d; };
    const p = [{ t: "Dans 1 heure", d: new Date(now.getTime() + 3600000) }];
    if (now.getHours() < 20) p.push({ t: "Ce soir 20:00", d: at(20, 0) });
    p.push({ t: "Demain 08:00", d: at(8, 1) });
    p.push({ t: "Demain 20:00", d: at(20, 1) });
    return p;
  }

  // ---------- Fenêtre « comment envoyer ? » ----------
  // Renvoie {once, at} (at = Date ou null) ou null si on annule.
  // opts.canOnce : proposer la vue unique ; opts.once / opts.at : valeurs de départ.
  function choose(opts) {
    opts = opts || {};
    return new Promise((resolve) => {
      const w = document.createElement("div");
      w.className = "ch-wp";
      w.style.zIndex = 60;
      const done = (v) => { w.remove(); resolve(v); };
      const main = () => {
        w.innerHTML = '<div class="ch-wpbox"><b>' + esc(opts.title || "Envoyer") + "</b>" +
          '<button class="ch-wpbtn ch-opt" data-x="now">➤ Envoyer maintenant</button>' +
          (opts.canOnce ? '<button class="ch-wpbtn ch-opt" data-x="once">🔥 Éphémère · vue unique<small>Il le voit une fois, puis ça disparaît.</small></button>' : "") +
          '<button class="ch-wpbtn ch-opt" data-x="sched">🕐 Programmer l\'envoi…</button>' +
          '<button class="ch-wpbtn ch-wpclose" data-x="cancel">Annuler</button></div>';
      };
      const sched = () => {
        const ps = presets();
        const min = localInput(new Date(Date.now() + 60000));
        const def = localInput(ps[0].d);
        w.innerHTML = '<div class="ch-wpbox"><b>Quand l\'envoyer ?</b>' +
          ps.map((p, i) => '<button class="ch-wpbtn ch-opt" data-x="p' + i + '">' + esc(p.t) + "</button>").join("") +
          '<small>Ou choisis une date et une heure :</small>' +
          '<input type="datetime-local" class="x-dt" min="' + min + '" value="' + def + '">' +
          (opts.canOnce ? '<label class="x-chk"><input type="checkbox" class="x-onceck"' + (opts.once ? " checked" : "") + '> 🔥 En vue unique</label>' : "") +
          '<button class="ch-wpbtn ch-opt x-go" data-x="go">🕐 Programmer</button>' +
          '<button class="ch-wpbtn ch-wpclose" data-x="back">Retour</button></div>';
      };
      const onceFlag = () => { const c = w.querySelector(".x-onceck"); return !!(c && c.checked) || !!opts.once; };
      w.addEventListener("click", (ev) => {
        const t = ev.target.closest("[data-x]");
        if (!t) { if (ev.target === w) done(null); return; }
        ev.stopPropagation();
        const x = t.dataset.x;
        if (x === "now") done({ once: !!opts.once, at: null });
        else if (x === "once") done({ once: true, at: null });
        else if (x === "sched") sched();
        else if (x === "back") main();
        else if (x === "cancel") done(null);
        else if (x === "go" || /^p\d+$/.test(x)) {
          let d;
          if (x === "go") d = new Date(w.querySelector(".x-dt").value);
          else d = presets()[Number(x.slice(1))].d;
          if (!d || isNaN(d) || d.getTime() < Date.now() + 5000) { C().toast("Choisis une heure à venir."); return; }
          done({ once: onceFlag(), at: d });
        }
      });
      main();
      host.appendChild(w);
    });
  }

  // ---------- Programmer ----------
  // payload : {p_kind, p_body, p_media, p_meta}
  async function schedule(payload, o) {
    try {
      const j = await rpc("schedule_message", {
        p_kind: payload.p_kind, p_body: payload.p_body || null, p_media: payload.p_media || null,
        p_meta: payload.p_meta || null, p_once: !!o.once, p_at: o.at.toISOString(),
      });
      if (j && j.status === "ok") { C().toast("🕐 Programmé pour " + whenLabel(o.at) + "."); return true; }
      if (j && j.status === "too_many") C().toast("Trop de messages programmés (100 maximum).");
      else if (j && j.status === "bad_time") C().toast("Choisis une heure à venir.");
      else if (j && j.status === "no_partner") C().toast("Vous n'êtes plus partenaires.");
      else C().toast("Impossible de programmer ce message.");
    } catch (e) { C().toast("Connexion impossible. Réessaie."); }
    return false;
  }

  // ---------- Liste des messages programmés ----------
  const kindIcon = (k) => ({ text: "💬", image: "📷", video: "🎥", voice: "🎤", gif: "🎞️", sticker: "💗" }[k] || "💬");
  async function openList() {
    const w = document.createElement("div");
    w.className = "ch-wp"; w.style.zIndex = 60;
    w.innerHTML = '<div class="ch-wpbox"><b>🕐 Messages programmés</b><div class="x-list"><small>Chargement…</small></div><button class="ch-wpbtn ch-wpclose" data-x="close">Fermer</button></div>';
    host.appendChild(w);
    const fill = async () => {
      let rows = [];
      try { rows = (await rpc("scheduled_list")) || []; } catch (e) { w.querySelector(".x-list").innerHTML = "<small>Connexion impossible.</small>"; return; }
      w.querySelector(".x-list").innerHTML = rows.length ? rows.map((r) =>
        '<div class="x-item"><span class="x-ic">' + kindIcon(r.kind) + (r.once ? "🔥" : "") + '</span><span class="x-tx"><b>' + esc(r.kind === "text" ? (r.body || "").slice(0, 60) : ({ image: "Photo", video: "Vidéo", voice: "Vocal", gif: "GIF", sticker: "Sticker" }[r.kind] || "Message")) +
        "</b><small>" + esc(whenLabel(new Date(r.send_at))) + '</small></span><button class="x-del" data-x="del" data-id="' + r.id + '" aria-label="Annuler l\'envoi">✕</button></div>').join("")
        : "<small>Aucun message programmé. Maintiens le bouton d'envoi enfoncé pour en programmer un.</small>";
    };
    w.addEventListener("click", async (ev) => {
      const t = ev.target.closest("[data-x]");
      if (!t) { if (ev.target === w) w.remove(); return; }
      ev.stopPropagation();
      if (t.dataset.x === "close") w.remove();
      else if (t.dataset.x === "del") {
        try { await rpc("scheduled_cancel", { p_id: Number(t.dataset.id) }); } catch (e) { C().toast("Connexion impossible."); }
        fill();
      }
    });
    fill();
  }

  // Envoie ce qui est arrivé à l'heure (au cas où l'horloge du serveur n'est pas installée).
  let lastDeliver = 0;
  function tick() {
    if (Date.now() - lastDeliver < 25000) return;
    lastDeliver = Date.now();
    rpc("deliver_scheduled").catch(() => {});
  }

  // ---------- Éphémère : ouverture ----------
  const OPEN = { busy: false };
  async function openOnce(id) {
    if (OPEN.busy) return;
    OPEN.busy = true;
    try {
      const j = await rpc("once_open", { p_id: id });
      if (!j || j.status !== "ok") {
        C().toast(j && j.status === "gone" ? "Ce message a déjà disparu." : "Impossible d'ouvrir ce message.");
        C()._x.markOpened(id);
        return;
      }
      await show(id, j);
    } catch (e) { C().toast("Connexion impossible. Réessaie."); }
    finally { OPEN.busy = false; }
  }

  function show(id, j) {
    return new Promise(async (resolve) => {
      const v = document.createElement("div");
      v.className = "x-once";
      let blobUrl = null;
      const close = async () => {
        v.remove();
        if (blobUrl) URL.revokeObjectURL(blobUrl);
        C()._x.markOpened(id);
        rpc("once_done", { p_id: id }).catch(() => {});
        resolve();
      };
      let inner = "";
      if (j.kind === "text") inner = '<div class="x-otext">' + esc(j.body || "") + "</div>";
      else {
        try {
          const r = await fetch(C()._x.mediaUrl(j.media), { cache: "no-store" });
          if (!r.ok) throw new Error("dl");
          blobUrl = URL.createObjectURL(await r.blob());
          inner = j.kind === "video" ? '<video src="' + blobUrl + '" controls autoplay playsinline></video>' : '<img src="' + blobUrl + '" alt="">';
        } catch (e) { inner = '<div class="x-otext">Impossible de charger ce média.</div>'; }
      }
      v.innerHTML = '<div class="x-otop"><span>🔥 Vue unique — ça disparaît après</span><button data-x="close" aria-label="Fermer">✕</button></div><div class="x-obody">' + inner + "</div>" +
        '<div class="x-ofoot">Ne fais pas de capture d\'écran : @' + esc(C()._x.partner()) + " le saura peut-être 😉</div>";
      v.addEventListener("click", (ev) => { if (ev.target.closest("[data-x=close]")) close(); });
      host.appendChild(v);
    });
  }

  // ---------- Pastille d'un message éphémère ----------
  function bubble(m, mine) {
    const what = { text: "Message", image: "Photo", video: "Vidéo" }[m.kind] || "Message";
    const opened = !!(m.meta && m.meta.opened);
    if (mine) {
      return '<div class="x-once-b ' + (opened ? "done" : "") + '"><span class="x-flame">🔥</span><span><b>' + what + " éphémère</b><small>" + (opened ? "Ouvert · disparu" : "Envoyé · pas encore ouvert") + "</small></span></div>";
    }
    if (opened) return '<div class="x-once-b done"><span class="x-flame">🔥</span><span><b>' + what + " éphémère</b><small>Ouvert · disparu</small></span></div>";
    return '<button class="x-once-b open" data-c="onceOpen"><span class="x-flame">🔥</span><span><b>' + what + " éphémère</b><small>Appuie pour voir (une seule fois)</small></span></button>";
  }

  window.TMM_X = { choose, schedule, openList, openOnce, bubble, tick, whenLabel };
})();
