(() => {
  "use strict";
  // Rappels : une tâche pour soi, pour l'autre ou pour les deux, avec une alarme à l'heure dite chez les deux.
  const A = () => window.TMM_API;
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const rpc = (name, body) => A().authed("POST", "/rest/v1/rpc/" + name, body || {});
  const $ = (id) => document.getElementById(id);
  let items = [], open = false, who = "me", busy = false, toast = "", lastKey = "", alarmKey = "", alarmTimer = null, ac = null;
  let draftTitle = "", draftDate = "", draftTime = "";

  const pad = (n) => String(n).padStart(2, "0");
  function defaults() {
    const d = new Date(Date.now() + 3600000); d.setMinutes(0, 0, 0);
    draftDate = d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
    draftTime = pad(d.getHours()) + ":" + pad(d.getMinutes());
  }
  defaults();
  const partner = () => { try { return A().partner(); } catch (e) { return null; } };
  const when = (t) => new Date(t).toLocaleString("fr-FR", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
  const target = (r) => r.target === "me" ? "Pour toi" : r.target === "both" ? "Pour vous deux" : "Pour @" + (partner() || "…");

  // ---------- Bouton à côté du jeu ----------
  function chipHtml() {
    return `<button class="gm-chip rp-chip on-green" data-g="rp">⏰ Rappels (${items.length})</button>`;
  }
  const count = () => items.length;
  const repaintChip = () => { if (window.TMM_GAMES && window.TMM_GAMES.paintChip) window.TMM_GAMES.paintChip(); };

  // ---------- Données ----------
  async function refresh() {
    if (!A() || !partner()) { if (items.length) { items = []; changed(); } return; }
    try {
      const rows = await rpc("reminder_list", {});
      if (Array.isArray(rows)) {
        const key = JSON.stringify(rows);
        if (key !== lastKey) { lastKey = key; items = rows; changed(); }
      }
    } catch (e) { /* rappels.sql pas encore installé côté serveur */ }
  }
  function changed() { repaintChip(); if (open) paint(); checkAlarm(); }

  // ---------- Alarme ----------
  function unlock() {
    try {
      ac = ac || new (window.AudioContext || window.webkitAudioContext)();
      if (ac.state === "suspended") ac.resume();
    } catch (e) { /* pas de son */ }
  }
  ["click", "touchstart", "keydown"].forEach((ev) => document.addEventListener(ev, unlock, { passive: true }));
  function bell(at, f, vol) {
    const g = ac.createGain();
    g.gain.setValueAtTime(0.0001, at); g.gain.linearRampToValueAtTime(vol, at + 0.03); g.gain.exponentialRampToValueAtTime(0.0001, at + 1.1);
    g.connect(ac.destination);
    [[1, 1], [2, 0.25], [3, 0.08]].forEach(([m, a2]) => {
      const o = ac.createOscillator(), h = ac.createGain();
      o.type = "sine"; o.frequency.value = f * m; h.gain.value = a2;
      o.connect(h); h.connect(g); o.start(at); o.stop(at + 1.15);
    });
  }
  function ring() {
    try {
      if (ac) { const t = ac.currentTime; [523.25, 659.25, 783.99, 1046.5].forEach((f, k) => bell(t + k * 0.22, f, 0.35)); bell(t + 1.0, 783.99, 0.2); }
    } catch (e) { /* ignoré */ }
    try { if (navigator.vibrate) navigator.vibrate([200, 150, 200]); } catch (e) { /* ignoré */ }
  }
  function dueList() { const n = Date.now(); return items.filter((r) => new Date(r.due_at).getTime() <= n); }
  function checkAlarm() {
    const due = dueList();
    const key = due.map((r) => r.id).join(",");
    let el = $("rp-alarm");
    if (!due.length) {
      if (el) el.remove();
      clearInterval(alarmTimer); alarmTimer = null; alarmKey = "";
      return;
    }
    if (key === alarmKey && el) return;
    alarmKey = key;
    if (!el) {
      el = document.createElement("div");
      el.id = "rp-alarm"; el.className = "rp-alarm";
      el.addEventListener("click", (ev) => { if (ev.target.closest("[data-rp='ok']")) ack(dueList()); });
      document.body.appendChild(el);
    }
    el.innerHTML = `<div class="rp-box"><div style="font-size:54px">⏰</div><h2>Rappel !</h2>` +
      due.map((r) => `<div class="rp-task"><b>${esc(r.title)}</b><small>${esc(target(r))}${r.by_me ? "" : " · de @" + esc(r.by_name)}</small></div>`).join("") +
      `<button class="rp-ok" data-rp="ok">OK</button></div>`;
    if (!alarmTimer) { ring(); alarmTimer = setInterval(ring, 3600); }
  }
  async function ack(list) {
    const ids = list.map((r) => r.id);
    items = items.filter((r) => ids.indexOf(r.id) < 0);
    lastKey = JSON.stringify(items); changed();
    for (const id of ids) { try { await rpc("reminder_ack", { p_id: id }); } catch (e) { /* ignoré */ } }
  }
  setInterval(checkAlarm, 1000);
  setInterval(refresh, 12000);
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") refresh(); });
  setTimeout(refresh, 1500);

  // ---------- Écran des rappels ----------
  function build() {
    if ($("rp")) return;
    const d = document.createElement("div");
    d.id = "rp"; d.className = "rp"; d.hidden = true;
    d.addEventListener("click", onClick);
    d.addEventListener("input", (ev) => {
      const t = ev.target;
      if (t.id === "rp-title") draftTitle = t.value;
      else if (t.id === "rp-date") draftDate = t.value;
      else if (t.id === "rp-time") draftTime = t.value;
      const b = $("rp-create"); if (b) b.disabled = !draftTitle.trim() || busy;
    });
    document.body.appendChild(d);
  }
  function paint() {
    const p = partner() || "…";
    const el = $("rp");
    const keep = document.activeElement && el.contains(document.activeElement) ? document.activeElement.id : null;
    el.innerHTML = `
      <div class="rp-head"><button class="ch-icon" data-rp="close" aria-label="Fermer">✕</button><b>⏰ Rappels</b><span style="width:44px"></span></div>
      <div class="rp-body">
        <h3>Nouveau rappel</h3>
        <input id="rp-title" class="rp-in" maxlength="200" placeholder="La tâche à faire" value="${esc(draftTitle)}" autocomplete="off">
        <p class="rp-lbl">Qui doit le faire ?</p>
        <div class="rp-row">
          <button class="rp-who${who === "me" ? " on" : ""}" data-rp="who" data-w="me">Moi</button>
          <button class="rp-who${who === "partner" ? " on" : ""}" data-rp="who" data-w="partner">@${esc(p)}</button>
          <button class="rp-who${who === "both" ? " on" : ""}" data-rp="who" data-w="both">Nous deux</button>
        </div>
        <p class="rp-lbl">Quand ?</p>
        <div class="rp-row">
          <input id="rp-date" type="date" class="rp-in" value="${esc(draftDate)}" min="${new Date().toISOString().slice(0, 10)}">
          <input id="rp-time" type="time" class="rp-in" value="${esc(draftTime)}">
        </div>
        <button id="rp-create" class="rp-go" data-rp="create" ${!draftTitle.trim() || busy ? "disabled" : ""}>Créer le rappel</button>
        <h3 style="margin-top:22px">Rappels en cours</h3>
        ${items.length ? items.map((r) => `<div class="rp-card"><div style="flex:1;min-width:0"><b>${esc(r.title)}</b>
          <div class="rp-when">⏰ ${esc(when(r.due_at))}</div><small>${esc(target(r))}${r.by_me ? "" : " · ajouté par @" + esc(r.by_name)}</small></div>
          <button class="rp-del" data-rp="del" data-id="${r.id}" aria-label="Supprimer">🗑</button></div>`).join("") : '<p class="rp-lbl">Aucun rappel pour le moment.</p>'}
      </div>${toast ? '<div class="rp-toast">' + esc(toast) + "</div>" : ""}`;
    if (keep) { const k = $(keep); if (k) { k.focus(); try { k.setSelectionRange(k.value.length, k.value.length); } catch (e) { /* ignoré */ } } }
  }
  const say = (t) => { toast = t; if (open) paint(); setTimeout(() => { if (toast === t) { toast = ""; if (open) paint(); } }, 2600); };

  async function create() {
    if (busy || !draftTitle.trim()) return;
    const t = new Date(draftDate + "T" + draftTime);
    if (isNaN(t.getTime())) { say("Choisis une date et une heure."); return; }
    busy = true; paint();
    try {
      const j = await rpc("reminder_create", { p_title: draftTitle.trim(), p_who: who, p_due: t.toISOString() });
      const st = (j && j.status) || "error";
      if (st === "ok") { draftTitle = ""; defaults(); say("Rappel enregistré ⏰"); lastKey = ""; await refresh(); }
      else if (st === "no_partner") say("Ajoute d'abord ton partenaire.");
      else if (st === "too_fast") say("Doucement ! Réessaie dans une minute.");
      else say("Rappel non enregistré.");
    } catch (e) { say("Rappel non enregistré. Le serveur n'est peut-être pas à jour."); }
    busy = false; paint();
  }
  function onClick(ev) {
    const t = ev.target.closest("[data-rp]");
    if (!t) return;
    const a = t.dataset.rp;
    if (a === "close") close();
    else if (a === "who") { who = t.dataset.w; paint(); }
    else if (a === "create") create();
    else if (a === "del") {
      const id = Number(t.dataset.id);
      items = items.filter((r) => r.id !== id); lastKey = JSON.stringify(items); changed();
      rpc("reminder_cancel", { p_id: id }).catch(() => {});
    }
  }
  function openScreen() { build(); open = true; $("rp").hidden = false; paint(); refresh(); }
  function close() { open = false; const e = $("rp"); if (e) e.hidden = true; }

  window.TMM_RAPPELS = { count, chipHtml, open: openScreen, close, refresh };
})();
