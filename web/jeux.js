(() => {
  "use strict";
  // Jeux à deux dans le chat. Premier jeu : « Me connais-tu ? ».
  const A = () => window.TMM_API;
  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const MOIS = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"];
  let bank = null, bankP = null;        // questions : id → {q,t}
  let partner = "", R = null, timer = null, busy = false, lastKey = "", attached = false;
  let draft = [];                        // mes réponses en cours de saisie
  let gIdx = null, verdict = null;       // devinette en cours / résultat affiché
  let showMenu = false, overlayOpen = false, editing = false;

  const $ = (id) => document.getElementById(id);
  const rpc = (name, body) => A().authed("POST", "/rest/v1/rpc/" + name, body || {});

  function loadBank() {
    if (bank) return Promise.resolve(bank);
    if (!bankP) bankP = fetch("questions.json?v=2").then((r) => r.json()).then((j) => {
      bank = new Map(j.map((x) => [x.id, x])); bank.size_ = j.length; return bank;
    }).catch(() => { bankP = null; return null; });
    return bankP;
  }
  const qOf = (i) => (bank && R && bank.get(R.qids[i])) || { q: "…", o: ["", "", "", ""] };

  // ---------- Pastille dans le chat ----------
  function chipHtml() {
    if (!R) return '<button class="gm-chip" data-g="open">🎮 Veux-tu jouer ?</button>';
    if (R.state === "invite") {
      if (R.by_me) return '<button class="gm-chip" data-g="open">⏳ Invitation envoyée…</button>';
      return `<div class="gm-invite"><span>🎮 <b>@${esc(partner)}</b> te propose « Me connais-tu ? »</span>
        <button class="gm-yes" data-g="yes">Oui</button><button class="gm-no" data-g="no">Non</button></div>`;
    }
    if (R.state === "playing") return '<button class="gm-chip on" data-g="open">🎮 Partie en cours — continuer</button>';
    return '<button class="gm-chip" data-g="open">🎮 Voir les résultats</button>';
  }
  function paintChip() {
    const el = $("gm-chip");
    if (!el) return;
    const h = chipHtml();
    if (el._h !== h) { el.innerHTML = h; el._h = h; }
  }

  // ---------- Mise en place ----------
  function ensureDom() {
    const bottom = $("ch-bottom");
    if (bottom && !$("gm-chip")) {
      const d = document.createElement("div");
      d.id = "gm-chip"; d.className = "gm-chiprow";
      d.addEventListener("click", onClick);
      bottom.insertBefore(d, bottom.firstChild);
    }
    if (!$("gm")) {
      const g = document.createElement("div");
      g.id = "gm"; g.className = "gm"; g.hidden = true;
      g.addEventListener("click", onClick);
      document.body.appendChild(g);
    }
  }

  function attach(name) {
    partner = name || partner;
    ensureDom();
    attached = true;
    loadBank();
    refresh();
    clearInterval(timer);
    timer = setInterval(refresh, 2500);
  }
  function detach() {
    attached = false;
    clearInterval(timer); timer = null;
    closeOverlay();
  }

  async function refresh() {
    if (!attached || busy) return;
    try {
      const j = await rpc("game_state");
      if (!j || j.status !== "ok") return;
      apply(j.round);
    } catch (e) { /* hors ligne : on garde l'état */ }
  }

  const keyOf = () => JSON.stringify(R ? [R.id, R.state, R.my_answers, R.their_answers, R.my_guesses, R.their_guesses] : null) + "|" + showMenu + "|" + overlayOpen;

  function apply(r) {
    const prev = R;
    R = r || null;
    if (R && (!prev || prev.id !== R.id)) { draft = []; gIdx = null; verdict = null; editing = false; }
    if (prev && prev.state === "invite" && prev.by_me && !R && overlayOpen && !showMenu) { toast("@" + partner + " ne souhaite pas jouer pour l'instant."); showMenu = true; }
    // L'invité vient d'accepter, ou l'invitation vient d'arriver
    const key = keyOf();
    paintChip();
    if (key !== lastKey) { lastKey = key; if (overlayOpen) render(); }
  }

  // ---------- Actions ----------
  async function act(fn) {
    if (busy) return;
    busy = true;
    try { const j = await fn(); if (j && j.status === "ok" && "round" in j) apply(j.round); else if (j && j.status === "no_partner") toast("Vous n'êtes plus partenaires."); return j; }
    catch (e) { toast("Connexion impossible. Réessaie."); }
    finally { busy = false; }
  }
  function toast(t) {
    const el = $("gm-toast");
    if (!el) return;
    el.textContent = t; el.hidden = false;
    clearTimeout(el._t); el._t = setTimeout(() => { el.hidden = true; }, 2600);
  }

  async function invite() {
    await loadBank();
    const j = await act(() => rpc("game_invite", { p_total: bank ? bank.size_ : 664 }));
    if (j && j.status === "ok") {
      showMenu = false;
      try { window.TMM_CHAT && window.TMM_CHAT.sendRaw("🎮 Je te propose de jouer à « Me connais-tu ? » — réponds Oui ou Non juste au-dessus du message !"); } catch (e) { /* ignoré */ }
      lastKey = ""; render();
    }
  }

  function onClick(ev) {
    const t = ev.target.closest("[data-g]");
    if (!t) return;
    ev.stopPropagation();
    const a = t.dataset.g;
    if (a === "open") { openOverlay(); }
    else if (a === "close") { closeOverlay(); }
    else if (a === "yes") { act(() => rpc("game_respond", { p_round: R.id, p_accept: true })).then(openOverlay); }
    else if (a === "no") { act(() => rpc("game_respond", { p_round: R.id, p_accept: false })); }
    else if (a === "invite") { invite(); }
    else if (a === "cancel") { act(() => rpc("game_cancel", { p_round: R.id })).then(() => { showMenu = true; lastKey = ""; render(); }); }
    else if (a === "again") { invite(); }
    else if (a === "menu") { showMenu = true; lastKey = ""; render(); }
    else if (a === "saveAns") { saveAnswers(); }
    else if (a === "editAns") { editing = true; draft = []; (R.my_answer_list || []).forEach((x) => { draft[x.idx] = x.answer; }); lastKey = ""; render(); }
    else if (a === "guess") { sendGuess(); }
    else if (a === "next") { verdict = null; gIdx = null; render(); }
    else if (a === "opt") {
      const w = t.parentNode;
      w.querySelectorAll(".gm-opt").forEach((b) => b.classList.remove("sel"));
      t.classList.add("sel");
      const ref = w.dataset.ref || "";
      if (ref.indexOf("gma") === 0) draft[Number(ref.slice(3))] = t.dataset.o;
    }
  }

  function openOverlay() {
    overlayOpen = true;
    showMenu = !R || R.state === "declined";
    $("gm").hidden = false;
    document.body.classList.add("gm-open");
    lastKey = ""; render();
  }
  function closeOverlay() {
    overlayOpen = false; showMenu = false;
    const g = $("gm");
    if (g) g.hidden = true;
    document.body.classList.remove("gm-open");
  }

  // ---------- Choix parmi 4 réponses ----------
  const LET = ["A", "B", "C", "D"];
  const optText = (i, v) => { const o = qOf(i).o; const k = Number(v); return o && o[k] != null ? o[k] : String(v == null ? "" : v); };
  function inputHtml(i, val, pre) {
    const o = qOf(i).o || [];
    let h = `<div class="gm-opts" data-ref="${pre}${i}">`;
    o.forEach((t, k) => { h += `<button type="button" class="gm-opt${String(k) === String(val) ? " sel" : ""}" data-g="opt" data-o="${k}"><i>${LET[k]}</i><span>${esc(t)}</span></button>`; });
    return h + "</div>";
  }
  function readInput(i, pre) {
    const w = document.querySelector('[data-ref="' + pre + i + '"]');
    const b = w && w.querySelector(".gm-opt.sel");
    return b ? b.dataset.o : "";
  }

  async function saveAnswers() {
    const list = [];
    for (let i = 0; i < 10; i++) list.push(readInput(i, "gma"));
    draft = list;
    if (list.some((x) => x === "")) { toast("Choisis une réponse à chaque question."); return; }
    const j = await act(() => rpc("game_save_answers", { p_round: R.id, p_answers: list }));
    if (j && j.status === "locked") toast("@" + partner + " a déjà commencé à deviner : tes réponses sont figées.");
    else if (j && j.status === "ok") { editing = false; toast("Tes réponses sont enregistrées 💗"); lastKey = ""; render(); }
  }

  async function sendGuess() {
    const i = R.my_guesses;
    const v = readInput(i, "gmg");
    if (v === "") { toast("Choisis une réponse."); return; }
    const j = await act(() => rpc("game_guess", { p_round: R.id, p_idx: i, p_guess: v }));
    if (j && j.status === "ok") {
      verdict = { ok: j.ok, answer: j.answer, guess: v, idx: i };
      R = Object.assign({}, R, { my_guesses: i + 1 });
      lastKey = keyOf(); render();
      try { if (navigator.vibrate) navigator.vibrate(j.ok ? [20, 30, 20] : 60); } catch (e) { /* ignoré */ }
      fx(j.ok);
      refresh();
    }
  }

  // ---------- Affichage ----------
  const head = (title) => `<div class="gm-head"><button class="ch-icon gm-x" data-g="close" aria-label="Fermer">✕</button><b>${title}</b><span></span></div>`;
  function bar(n, of) { return `<div class="gm-bar"><i style="width:${Math.round((n / of) * 100)}%"></i></div>`; }

  function render() {
    const g = $("gm");
    if (!g || !overlayOpen) return;
    const keepScroll = g.querySelector(".gm-body") ? g.querySelector(".gm-body").scrollTop : 0;
    let body = "";
    if (!bank) { loadBank().then(() => { if (overlayOpen) { lastKey = ""; render(); } }); body = '<div class="gm-wait"><div class="spin"></div></div>'; }
    else if (verdict) body = verdictHtml(verdict.idx);
    else if (showMenu || !R || R.state === "cancelled") body = menuHtml();
    else if (R.state === "declined") body = `<div class="gm-center"><div class="gm-big">🙈</div><p>@${esc(partner)} n'a pas envie de jouer pour l'instant.</p><button class="gm-btn" data-g="menu">Retour</button></div>`;
    else if (R.state === "cancelled") body = menuHtml();
    else if (R.state === "invite") body = inviteHtml();
    else if (R.state === "playing") body = playHtml();
    else body = doneHtml();
    g.innerHTML = head("🎮 Jeux") + `<div class="gm-body">${body}</div><div class="gm-toast" id="gm-toast" hidden></div>`;
    const b = g.querySelector(".gm-body");
    if (b && !verdict) b.scrollTop = keepScroll;
    bindForm();
    if (R && R.state === "done" && !g._celebrated) { g._celebrated = R.id; celebrate(); }
  }

  function bindForm() {
    const g = $("gm");
    g.querySelectorAll(".gm-in").forEach((el) => {
      el.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); const b = g.querySelector("[data-g=guess]"); if (b) b.click(); } });
    });
  }

  function menuHtml() {
    return `<div class="gm-intro">Choisis un jeu à lancer avec <b>@${esc(partner)}</b>. De nouveaux jeux arrivent bientôt !</div>
      <div class="gm-card">
        <div class="gm-emo">💞</div>
        <div class="gm-cardtxt"><b>Me connais-tu ?</b><span>10 questions sur toi, 10 sur lui/elle. Vous répondez chacun de votre côté, puis vous devinez les réponses de l'autre.</span></div>
        <button class="gm-btn" data-g="invite">Jouer</button>
      </div>
      <div class="gm-card soon"><div class="gm-emo">🎲</div><div class="gm-cardtxt"><b>Prochain jeu</b><span>Bientôt…</span></div></div>`;
  }

  function inviteHtml() {
    if (R.by_me) return `<div class="gm-center"><div class="gm-big pulse">💌</div><h3>Invitation envoyée</h3><p>On attend que @${esc(partner)} accepte de jouer à « Me connais-tu ? ».</p><button class="gm-btn ghost" data-g="cancel">Annuler</button></div>`;
    return `<div class="gm-center"><div class="gm-big pulse">🎮</div><h3>@${esc(partner)} veut jouer !</h3><p>« Me connais-tu ? » — 10 questions pour voir à quel point vous vous connaissez.</p>
      <div class="gm-two"><button class="gm-btn" data-g="yes">Oui, on joue !</button><button class="gm-btn ghost" data-g="no">Non merci</button></div></div>`;
  }

  function playHtml() {
    const mine = R.my_answers, theirs = R.their_answers;
    if (mine < 10 || editing) {
      let h = `<div class="gm-step">Étape 1 sur 2</div><h3>Tes réponses</h3><p class="gm-sub">Choisis la réponse qui te correspond. @${esc(partner)} devra deviner laquelle tu as choisie, il/elle ne la verra pas avant.</p>`;
      const saved = {};
      (R.my_answer_list || []).forEach((a) => { saved[a.idx] = a.answer; });
      for (let i = 0; i < 10; i++) {
        h += `<div class="gm-q"><span class="gm-n">${i + 1}</span><div><label>${esc(qOf(i).q)}</label>${inputHtml(i, draft[i] || saved[i], "gma")}</div></div>`;
      }
      return h + '<button class="gm-btn wide" data-g="saveAns">Valider mes réponses</button>';
    }
    if (theirs < 10) {
      return `<div class="gm-center"><div class="gm-big pulse">⏳</div><h3>Tes réponses sont prêtes</h3><p>On attend que @${esc(partner)} réponde à ses 10 questions (${theirs}/10).</p>
        <button class="gm-btn ghost" data-g="editAns">Modifier mes réponses</button></div>`;
    }
    const i = R.my_guesses;
    if (i >= 10) {
      const sc = (R.my_results || []).filter((x) => x.ok).length;
      return `<div class="gm-center"><div class="gm-big">${sc >= 7 ? "🥰" : sc >= 4 ? "🙂" : "😅"}</div><h3>Tu as trouvé ${sc} / 10</h3><p>On attend que @${esc(partner)} devine tes réponses (${R.their_guesses}/10).</p></div>`;
    }
    return `<div class="gm-step">Étape 2 sur 2 · Question ${i + 1} / 10</div>${bar(i, 10)}
      <div class="gm-guesscard"><div class="gm-from">@${esc(partner)} a répondu à :</div><div class="gm-qt">« ${esc(qOf(i).q)} »</div>
      <div class="gm-from">Que penses-tu qu'il/elle a choisi ?</div>${inputHtml(i, "", "gmg")}</div>
      <button class="gm-btn wide" data-g="guess">Valider</button>`;
  }

  function verdictHtml(i) {
    const v = verdict, last = i + 1 >= 10;
    return `<div class="gm-step">Question ${i + 1} / 10</div>${bar(i + 1, 10)}
      <div class="gm-verdict ${v.ok ? "ok" : "ko"}"><div class="gm-mark">${v.ok ? "✓" : "✗"}</div>
      <h3>${v.ok ? "Bravo, c'est vrai !" : "Raté… c'est faux"}</h3>
      <div class="gm-qt small">« ${esc(qOf(i).q)} »</div>
      <div class="gm-cmp"><span>Ta réponse</span><b>${esc(optText(i, v.guess))}</b></div>
      ${v.ok ? "" : `<div class="gm-cmp good"><span>La bonne réponse</span><b>${esc(optText(i, v.answer))}</b></div>`}</div>
      <button class="gm-btn wide" data-g="next">${last ? "Voir mon score" : "Question suivante"}</button>`;
  }

  function doneHtml() {
    const mine = R.my_results || [], their = R.their_results || [];
    const ms = mine.filter((x) => x.ok).length, ts = their.filter((x) => x.ok).length;
    const msg = ms > ts ? "Tu connais @" + esc(partner) + " mieux qu'il/elle ne te connaît 😏" : ms < ts ? "@" + esc(partner) + " te connaît mieux 💗" : "Égalité parfaite, vous êtes faits l'un pour l'autre 💞";
    const rows = (list, title) => `<h4>${title}</h4>` + list.map((x) => `<div class="gm-row ${x.ok ? "ok" : "ko"}"><span class="gm-ic">${x.ok ? "✓" : "✗"}</span>
        <div><div class="gm-rq">${esc(qOf(x.idx).q)}</div><div class="gm-ra">${x.ok ? esc(optText(x.idx, x.answer)) : `<s>${esc(optText(x.idx, x.guess))}</s> → <b>${esc(optText(x.idx, x.answer))}</b>`}</div></div></div>`).join("");
    return `<div class="gm-center"><div class="gm-trophy">🏆</div><h3>Résultats</h3>
      <div class="gm-score"><div><b>${ms}</b><span>/10</span><small>Toi</small></div><div><b>${ts}</b><span>/10</span><small>@${esc(partner)}</small></div></div>
      <p>${msg}</p></div>
      <div class="gm-two"><button class="gm-btn" data-g="again">Rejouer 🔁</button><button class="gm-btn ghost" data-g="menu">Autres jeux</button></div>
      ${rows(mine, "Ce que tu as deviné sur @" + esc(partner))}${rows(their, "Ce que @" + esc(partner) + " a deviné sur toi")}`;
  }

  // ---------- Animations ----------
  function fx(ok) {
    const g = $("gm");
    if (!g) return;
    const n = ok ? 26 : 10;
    const em = ok ? ["💗", "✨", "💖", "🎉", "💕"] : ["💔", "😅"];
    for (let k = 0; k < n; k++) {
      const s = document.createElement("span");
      s.className = "gm-p " + (ok ? "up" : "down");
      s.textContent = em[k % em.length];
      s.style.left = (10 + Math.random() * 80) + "%";
      s.style.animationDelay = (Math.random() * 0.35) + "s";
      s.style.fontSize = (18 + Math.random() * 22) + "px";
      g.appendChild(s);
      setTimeout(() => s.remove(), 2200);
    }
  }
  function celebrate() {
    const g = $("gm");
    setTimeout(() => {
      if (!g) return;
      fx(true);
    }, 250);
  }

  window.TMM_GAMES = { attach, detach, refresh };
})();
