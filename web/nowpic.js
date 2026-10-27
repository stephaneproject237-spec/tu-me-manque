(() => {
  "use strict";
  // Now pic : on propose à l'autre une photo « maintenant » ; s'il dit oui, l'appareil photo s'ouvre chez les deux.
  const A = () => window.TMM_API;
  const rpc = (n, b) => A().authed("POST", "/rest/v1/rpc/" + n, b || {});
  const handled = new Set(), toasted = new Set();
  let box = null, cur = null, timer = null, busy = false;

  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  const partner = () => (A() && A().partner && A().partner()) || "";
  function toast(t) {
    if (window.TMM_CHAT && window.TMM_CHAT.isOpen()) { window.TMM_CHAT.toast(t); return; }
    const e = document.createElement("div"); e.className = "np-toast"; e.textContent = t; document.body.appendChild(e); setTimeout(() => e.remove(), 3200);
  }
  function ui(html) {
    if (!box) { box = document.createElement("div"); box.className = "np"; box.addEventListener("click", onClick); document.body.appendChild(box); }
    if (box._h !== html) { box.innerHTML = html; box._h = html; }
    box.hidden = !html;
  }
  function paint() {
    const s = cur;
    if (!s) { ui(""); return; }
    if (s.state === "pending" && !s.by_me) {
      ui('<div class="np-card"><div class="np-ic">📸</div><b>@' + esc(partner()) + ' veut faire un Now Pic !</b><span>Une photo prise en même temps, tout de suite. On y va ?</span>' +
        '<button class="np-yes" data-n="yes">Oui, allons-y 📸</button><button class="np-no" data-n="no">Pas maintenant</button></div>');
    } else if (s.state === "pending" && s.by_me) {
      ui('<div class="np-wait">⏳ Now Pic envoyé à @' + esc(partner()) + '… <button data-n="cancel">Annuler</button></div>');
    } else if (s.state === "declined" && s.by_me) {
      ui("");
      if (!toasted.has(s.id)) { toasted.add(s.id); toast("@" + partner() + " ne veut pas faire de Now Pic pour l'instant."); }
    } else ui("");
    if (s.state === "go" && !handled.has(s.id)) { handled.add(s.id); ui(""); camera(s); }
  }
  function camera(s) {
    if (!window.TMM_HOT || !window.TMM_CHAT) return;
    window.TMM_HOT.start({
      title: "📸 Now Pic — maintenant !",
      onResult: (r) => window.TMM_CHAT.sendPhotoAny(r.blob, r.w, r.h, r.once, true),
    });
  }
  async function poll() {
    if (busy || document.visibilityState !== "visible" || !A() || !partner()) return;
    busy = true;
    try {
      const j = await rpc("nowpic_state");
      if (j && j.status === "ok") { cur = j.session || null; paint(); }
    } catch (e) { /* hors ligne */ }
    busy = false;
  }
  async function onClick(ev) {
    const t = ev.target.closest("[data-n]");
    if (!t || !cur) return;
    const a = t.dataset.n;
    try {
      if (a === "yes") await rpc("nowpic_respond", { p_id: cur.id, p_accept: true });
      else if (a === "no") await rpc("nowpic_respond", { p_id: cur.id, p_accept: false });
      else if (a === "cancel") await rpc("nowpic_cancel", { p_id: cur.id });
    } catch (e) { toast("Connexion impossible. Réessaie."); }
    if (a !== "yes") cur = null; paint(); poll();
  }
  async function request() {
    try {
      const j = await rpc("nowpic_request");
      if (j && j.status === "ok") { toast("📸 Now Pic envoyé à @" + partner() + "."); poll(); }
      else if (j && j.status === "no_partner") toast("Vous n'êtes plus partenaires.");
      else toast("Impossible d'envoyer le Now Pic.");
    } catch (e) { toast("Connexion impossible. Réessaie."); }
  }
  function init() { clearInterval(timer); timer = setInterval(poll, 2500); poll(); }
  window.TMM_NOW = { request, init, poll };
  const tryInit = () => { if (A()) init(); else setTimeout(tryInit, 500); };
  tryInit();
})();
