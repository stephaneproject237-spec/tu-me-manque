(function () {
  "use strict";
  // Messagerie avec @admin (texte seulement).
  const host = document.getElementById("support");
  const A = () => window.TMM_API;
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  let built = false, opened = false, timer = null, msgs = [], lastKey = "", busy = false;

  function build() {
    if (built) return;
    built = true;
    host.innerHTML = `
      <div class="ch-head">
        <button class="ch-icon" data-s="back" aria-label="Retour">‹</button>
        <div class="ch-avatar">🛟</div>
        <div class="ch-title"><b>@admin</b><small>Aide et questions</small></div>
      </div>
      <div class="ch-list" id="sp-list"></div>
      <div class="ch-bar">
        <textarea id="sp-input" rows="1" placeholder="Écris à @admin…" maxlength="2000" autocomplete="off"></textarea>
        <button class="ch-send on" data-s="send" aria-label="Envoyer">➤</button>
      </div>`;
    host.addEventListener("click", (ev) => {
      const t = ev.target.closest("[data-s]");
      if (!t) return;
      if (t.dataset.s === "back") close();
      else if (t.dataset.s === "send") send();
    });
    const vv = window.visualViewport;
    if (vv) {
      const fit = () => { if (opened) { host.style.height = vv.height + "px"; host.style.top = vv.offsetTop + "px"; } };
      vv.addEventListener("resize", fit); vv.addEventListener("scroll", fit);
      host._fit = fit;
    }
  }

  const hhmm = (t) => { const d = new Date(t); return String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0"); };

  function paint(force) {
    const key = msgs.map((m) => m.id).join(",");
    if (!force && key === lastKey) return;
    lastKey = key;
    const list = document.getElementById("sp-list");
    const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 80;
    list.innerHTML = msgs.length
      ? msgs.map((m) => `<div class="ch-row ${m.from_admin ? "theirs" : "mine"}"><div class="ch-bubble"><span class="ch-text">${esc(m.body)}</span> <span class="ch-meta">${hhmm(m.created_at)}</span></div></div>`).join("")
      : '<div class="ch-empty">Pose ta question à @admin 💗<br>Tu recevras sa réponse ici et par notification.</div>';
    if (force || atBottom) list.scrollTop = list.scrollHeight;
  }

  async function load() {
    try {
      const rows = await A().authed("GET", "/rest/v1/support_messages?select=id,from_admin,body,created_at&order=id.asc&limit=300");
      if (!rows || !rows.length) { close(); if (A().setSupportHas) A().setSupportHas(false); return; }
      msgs = rows;
      paint(false);
      if (msgs.some((m) => m.from_admin)) {
        await A().authed("POST", "/rest/v1/rpc/support_mark_read", {});
        A().setSupportUnread(0);
      }
    } catch (e) {
      if (!msgs.length) document.getElementById("sp-list").innerHTML = '<div class="ch-empty">Messagerie indisponible pour le moment.</div>';
    }
  }

  async function send() {
    const inp = document.getElementById("sp-input");
    const body = inp.value.trim();
    if (!body || busy) return;
    busy = true;
    try {
      const j = await A().authed("POST", "/rest/v1/rpc/support_send", { p_body: body });
      if (j && j.status === "ok") { inp.value = ""; await load(); paint(true); }
      else if (j && j.status === "no_thread") { alert("Cette conversation est terminée."); close(); if (A().setSupportHas) A().setSupportHas(false); }
      else if (j && j.status === "too_fast") alert("Doucement ! Réessaie dans une minute.");
      else alert("Message non envoyé.");
    } catch (e) { alert("Message non envoyé."); }
    busy = false;
  }

  function open() {
    build();
    if (opened) return;
    opened = true;
    host.hidden = false;
    document.body.classList.add("chat-open");
    if (host._fit) host._fit(); else host.style.height = "100dvh";
    lastKey = "";
    load().then(() => paint(true));
    timer = setInterval(load, 3500);
  }

  function close() {
    if (!opened) return;
    opened = false;
    host.hidden = true;
    document.body.classList.remove("chat-open");
    clearInterval(timer); timer = null;
  }

  window.TMM_SUPPORT = { open, close, isOpen: () => opened };
})();
