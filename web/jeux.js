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
  let boltOpen = false;
  function chipHtml() {
    const remN = window.TMM_RAPPELS ? window.TMM_RAPPELS.count() : 0;
    const gameActive = !!R && (R.state === "invite" || R.state === "playing");
    const remActive = remN > 0;
    let h = "";
    // L'éclair ouvre le menu des fonctions, qui sort vers le haut ; il disparaît si tout est déjà en cours.
    {
      let menu = "";
      if (boltOpen) {
        let i = 0;
        const it = (g, label) => '<button class="gm-chip gm-pop" style="animation-delay:' + (i++) * 70 + 'ms" data-g="' + g + '">' + label + "</button>";
        if (!gameActive) menu += it("open", "🎮 Jeux");
        if (!remActive) menu += it("rp", "⏰ Rappels");
        menu += it("sched", "🕐 Programmés");
        menu += it("hot", "🔥 Hot pic");
        if (window.TMM_NOW && window.TMM_NOW.isAuto()) menu += it("nowoff", "⛔ Couper Now pic auto");
        menu += it("now", '<svg class="nowic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="15" height="14" rx="2.5"/><circle cx="8" cy="9.5" r="1.4"/><path d="M2 15.5l4.5-4 3.5 3 2.5-2L17 15"/><circle cx="18.5" cy="16.5" r="4.6" fill="#281C24"/><path d="M18.5 14v2.7l1.7 1"/></svg> Now pic');
      }
      h += '<div class="gm-boltwrap"><button class="gm-bolt' + (boltOpen ? " open" : "") + '" data-g="bolt" aria-label="Fonctions">⚡</button>' + (menu ? '<div class="gm-menu">' + menu + "</div>" : "") + "</div>";
    }
    // Une fonction reste dehors uniquement si elle est en cours.
    if (R && R.state === "invite" && !R.by_me) {
      h += `<div class="gm-invite"><span>🎮 <b>@${esc(partner)}</b> te propose « Me connais-tu ? »</span>
        <button class="gm-yes" data-g="yes">Oui</button><button class="gm-no" data-g="no">Non</button></div>`;
    } else if (R && R.state === "invite") h += '<button class="gm-chip" data-g="open"><span class="hg">⏳</span> Invitation envoyée…</button>';
    else if (R && R.state === "playing") h += '<button class="gm-chip on" data-g="open">🎮 Partie en cours — continuer</button>';
    if (remActive) h += window.TMM_RAPPELS.chipHtml();
    return h;
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
    if (prev && R && overlayOpen && !showMenu && R.state === "playing" && R.my_answers >= 10 && R.their_answers >= 10 && (prev.my_answers < 10 || prev.their_answers < 10)) {
      closeOverlay(); chatPoll();
    }
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
    if (a === "pick") { pick(t); }
    else if (a === "qtoggle") {
      const k = t.dataset.k, rowEl = t.closest(".ch-row");
      const fold = rowEl && rowEl.querySelector(".gm-fold");
      if (!fold) return;
      const o = !fold.classList.contains("open");
      if (o) openCards.add(k); else openCards.delete(k);
      fold.classList.toggle("open", o);
      const ch = rowEl.querySelector(".gm-chev"); if (ch) ch.textContent = o ? "▴" : "▾";
    }
    else if (a === "bolt") { boltOpen = !boltOpen; if (boltOpen) chime(); paintChip(); }
    else if (a === "open") { boltOpen = false; paintChip(); openOverlay(); }
    else if (a === "hot") { boltOpen = false; paintChip(); if (window.TMM_HOT && window.TMM_CHAT) window.TMM_HOT.start({ title: "🔥 Hot pic", onResult: (r) => window.TMM_CHAT._x.sendImage(r.blob, r.w, r.h, "", { once: r.once }) }); }
    else if (a === "now") { boltOpen = false; paintChip(); if (window.TMM_NOW) window.TMM_NOW.request(); }
    else if (a === "nowoff") { boltOpen = false; paintChip(); if (window.TMM_NOW) window.TMM_NOW.autoOff(); }
    else if (a === "sched") { boltOpen = false; paintChip(); if (window.TMM_X) window.TMM_X.openList(); }
    else if (a === "rp") { boltOpen = false; paintChip(); if (window.TMM_RAPPELS) window.TMM_RAPPELS.open(); }
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
      return `<div class="gm-center"><div class="gm-big"><span class="hg">⏳</span></div><h3>Tes réponses sont prêtes</h3><p>On attend que @${esc(partner)} réponde à ses 10 questions (${theirs}/10).</p>
        <button class="gm-btn ghost" data-g="editAns">Modifier mes réponses</button></div>`;
    }
    return `<div class="gm-center"><div class="gm-big pulse">💬</div><h3>La partie continue dans le chat !</h3>
      <p>Cupidon 💘 pose les questions directement dans la conversation.<br>Toi : <b>${R.my_guesses}/10</b> · @${esc(partner)} : <b>${R.their_guesses}/10</b></p>
      <div class="gm-two"><button class="gm-btn" data-g="close">Retour au chat</button><button class="gm-btn ghost" data-g="cancel">Arrêter la partie</button></div></div>`;
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

  // ---------- Petits bruits : joie / tristesse ----------
  let actx = null;
  function audio() {
    try {
      if (!actx) { const AC = window.AudioContext || window.webkitAudioContext; if (AC) actx = new AC(); }
      if (actx && actx.state === "suspended") actx.resume();
    } catch (e) { /* ignoré */ }
    return actx;
  }
  ["pointerdown", "keydown", "touchstart"].forEach((ev) => document.addEventListener(ev, audio, { passive: true }));
  function tone(freq, t0, dur, type, vol, to) {
    const o = actx.createOscillator(), g = actx.createGain();
    o.type = type || "sine";
    o.frequency.setValueAtTime(freq, t0);
    if (to) o.frequency.exponentialRampToValueAtTime(to, t0 + dur);
    g.gain.setValueAtTime(0.0001, t0);
    g.gain.linearRampToValueAtTime(vol || 0.3, t0 + 0.012);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    o.connect(g); g.connect(actx.destination);
    o.start(t0); o.stop(t0 + dur + 0.02);
  }
  // Petit « ding » doux et affectueux quand les fonctions sortent de l'éclair.
  function chime() {
    try {
      if (!audio()) return;
      const t = actx.currentTime;
      tone(659.25, t, 0.28, "sine", 0.16);
      tone(880, t + 0.08, 0.32, "sine", 0.13);
      tone(1318.5, t + 0.16, 0.45, "sine", 0.08);
    } catch (e) { /* pas de son */ }
  }
  function sfx(ok) {
    try {
      if (!audio()) return;
      const t = actx.currentTime + 0.02;
      if (ok) {          // petit arpège joyeux qui monte, avec des étincelles
        [523.25, 659.25, 783.99, 1046.5].forEach((f, k) => { tone(f, t + k * 0.09, 0.28, "triangle", 0.28); tone(f * 2, t + k * 0.09, 0.2, "sine", 0.08); });
        tone(1568, t + 0.42, 0.35, "sine", 0.14); tone(2093, t + 0.5, 0.3, "sine", 0.08);
      } else {           // « wah-wah » triste qui descend
        tone(392, t, 0.32, "sawtooth", 0.14, 330);
        tone(349, t + 0.3, 0.55, "sawtooth", 0.14, 247);
        tone(196, t + 0.3, 0.55, "sine", 0.1, 150);
      }
    } catch (e) { /* ignoré */ }
  }
  const chatPoll = () => { try { window.TMM_CHAT && window.TMM_CHAT.poll && window.TMM_CHAT.poll(); } catch (e) { /* ignoré */ } };

  // ---------- Cupidon : les messages du jeu dans le fil du chat ----------
  let envLast = null;
  const localRes = {}, heard = new Set(), picking = {};
  const PRAISE = ["Bravo", "Excellent", "Bien joué", "Dans le mille", "Parfait", "Quel flair"];
  const OUPS = ["Aïe", "Oups", "Raté", "Pas de chance", "Presque", "Dommage"];
  const gkey = (mt) => mt.round + ":" + mt.idx + ":" + mt.who;
  const optOf = (qid, k) => { const q = bank && bank.get(qid); return q && q.o && q.o[Number(k)] != null ? q.o[Number(k)] : "…"; };
  function resultFor(key, env) {
    if (localRes[key]) return localRes[key];
    const list = env.msgs();
    for (let i = list.length - 1; i >= 0; i--) {
      const x = list[i];
      if (x.kind === "game" && x.meta && x.meta.t === "result" && gkey(x.meta) === key) return { ok: x.meta.ok, guess: x.meta.guess, answer: x.meta.answer };
    }
    return null;
  }

  const openCards = new Set();

  function botHtml(m, env) {
    const mt = m.meta || {};
    const pn = "@" + esc(env.partner);
    const time = '<span class="ch-meta">' + env.hhmm(m.created_at) + "</span>";
    const head = (t) => '<div class="gm-botname">💘 Cupidon' + (t ? " · " + t : "") + "</div>";
    switch (mt.t) {
      case "intro":
        return { cls: "gm-b", html: head("") + `<div class="ch-text">🎉 Vous avez choisi vos réponses !<br>Passons au jeu : chacun va <b>deviner ce que l'autre a choisi</b>. Je pose les questions ici, répondez quand vous voulez, et n'hésitez pas à commenter. 😏</div>` + time };
      case "card": {
        const q = bank && bank.get(mt.qid);
        const key = gkey(mt), res = resultFor(key, env);
        const mineTurn = mt.who === env.me;
        let h = head("Question " + (mt.idx + 1) + "/10");
        if (!q) return { cls: "gm-b", html: h + '<div class="ch-text">…</div>' + time };
        h += mineTurn ? `<div class="gm-bq" ${res ? 'data-g="qtoggle" data-k="'+key+'" style="cursor:pointer"' : ''}><small>Qu'a choisi ${pn} ?</small><b>« ${esc(q.q)} »</b></div>`
          : `<div class="gm-bq" ${res ? 'data-g="qtoggle" data-k="'+key+'" style="cursor:pointer"' : ''}><small>${pn} doit deviner ce que <u>tu</u> as choisi :</small><b>« ${esc(q.q)} »</b></div>`;
        const isOpen = !res || openCards.has(key);
        h += '<div class="gm-fold' + (isOpen ? " open" : "") + '"><div class="gm-foldin"><div class="gm-bopts">';
        q.o.forEach((t, k) => {
          let c = "gm-bo";
          if (res) { if (String(k) === String(res.answer)) c += " right"; else if (String(k) === String(res.guess)) c += " wrong"; else c += " dim"; }
          const can = !res && mineTurn;
          h += `<button type="button" class="${c}" ${can ? `data-g="pick" data-round="${mt.round}" data-idx="${mt.idx}" data-o="${k}" data-who="${esc(mt.who)}" data-qid="${mt.qid}"` : "disabled"}><i>${LET[k]}</i><span>${esc(t)}</span>${res && String(k) === String(res.answer) ? "<em>✓</em>" : res && String(k) === String(res.guess) ? "<em>✗</em>" : ""}</button>`;
        });
        h += "</div></div></div>";
        if (res) {
          h += `<div class="gm-bres ${res.ok ? "ok" : "ko"}">${res.ok ? "✅ Bonne réponse !" : "❌ Raté…"} <span class="gm-chev">${isOpen ? "▴" : "▾"}</span></div>`;
          const all = envLast ? envLast.msgs() : [];
          const other = all.find((x) => x.kind === "game" && x.meta && x.meta.t === "card" && x.meta.round === mt.round && x.meta.idx === mt.idx && x.meta.who !== mt.who);
          if (other && !resultFor(gkey(other.meta), env)) h += `<div class="gm-bwait"><span class="hg">⏳</span> En attente de ${pn} pour passer à la suite…</div>`;
        } else if (!mineTurn) h += `<div class="gm-bwait"><span class="hg">⏳</span> ${pn} réfléchit…</div>`;
        return { cls: "gm-b gm-card" + (res ? (res.ok ? " done ok" : " done ko") : ""), html: h + time };
      }
      case "result": {
        const ans = esc(optOf(mt.qid, mt.answer));
        const me = mt.who === env.me;
        const n = Number(m.id) || 0;
        let t;
        if (mt.ok) t = me ? `🎉 ${PRAISE[n % PRAISE.length]} ! Tu connais bien ${pn}.` : `🥰 ${pn} a trouvé exactement ce que tu avais choisi !`;
        else t = me ? `😅 ${OUPS[n % OUPS.length]}… ${pn} avait choisi « ${ans} ».` : `🙈 ${pn} s'est trompé(e) : tu avais choisi « ${ans} ».`;
        return { cls: "gm-b gm-react " + (mt.ok ? "ok" : "ko"), html: head("") + '<div class="ch-text">' + t + "</div>" + time };
      }
      case "finish": {
        const me = mt.who === env.me;
        return { cls: "gm-b", html: head("") + `<div class="ch-text">🏁 ${me ? "Tu as terminé" : pn + " a terminé"} ses 10 questions : <b>${mt.score}/10</b> !${me ? " On attend " + pn + "…" : ""}</div>` + time };
      }
      case "end": {
        const iAmA = mt.ua === env.me;
        const mine = iAmA ? mt.sa : mt.sb, theirs = iAmA ? mt.sb : mt.sa;
        const verdict = mine > theirs ? `Tu connais ${pn} mieux qu'il/elle ne te connaît 😏` : mine < theirs ? `${pn} te connaît mieux 💗` : "Égalité parfaite, vous êtes faits l'un pour l'autre 💞";
        return { cls: "gm-b gm-end", html: head("Résultats") + `<div class="gm-endscore"><div><b>${mine}</b><small>Toi</small></div><span>/10</span><div><b>${theirs}</b><small>${pn}</small></div></div><div class="ch-text">🏆 ${verdict}</div>` +
          '<div class="gm-endbtns"><button class="gm-btn" data-g="again">Rejouer 🔁</button><button class="gm-btn ghost" data-g="open">Détails</button></div>' + time };
      }
      default: return { cls: "gm-b", html: head("") + '<div class="ch-text">' + esc(m.body || "🎮") + "</div>" + time };
    }
  }

  function row(m, env) {
    envLast = env;
    const b = botHtml(m, env);
    const el = document.createElement("div");
    el.className = "ch-row bot";
    el.dataset.day = env.dayKey(m.created_at);
    el.dataset.id = m.id;
    if (m.meta && m.meta.t === "card") el.dataset.gk = gkey(m.meta);
    if (m.meta && m.meta.t === "card") el.classList.add(m.meta.who === env.me ? "gm-r" : "gm-l");
    el.innerHTML = '<div class="ch-bubble ' + b.cls + '">' + b.html + "</div>";
    el.addEventListener("click", onClick);
    return el;
  }

  function refreshCard(key) {
    if (!envLast) return;
    const host = document.getElementById("chat");
    if (!host) return;
    const pre = key.split(":").slice(0, 2).join(":") + ":";
    host.querySelectorAll(".ch-row[data-gk]").forEach((el) => {
      if (el.dataset.gk.indexOf(pre) !== 0) return;
      const k = el.dataset.gk;
      const m = envLast.msgs().find((x) => x.kind === "game" && x.meta && x.meta.t === "card" && gkey(x.meta) === k);
      if (m) el.replaceWith(row(m, envLast));
    });
  }

  // Nouveaux messages reçus : on met à jour la carte concernée, et on joue le petit bruit si c'est l'autre qui a répondu.
  function onMessages(list) {
    list.forEach((m) => {
      if (m.kind !== "game" || !m.meta) return;
      const mt = m.meta;
      if (mt.t === "result") {
        const key = gkey(mt);
        refreshCard(key);
        if (mt.who !== (envLast && envLast.me) && !heard.has(key)) { heard.add(key); sfx(!!mt.ok); }
      } else if (mt.t === "end") sfx(true);
    });
  }

  async function pick(t) {
    const key = t.dataset.round + ":" + t.dataset.idx + ":" + t.dataset.who;
    if (picking[key]) return;
    picking[key] = true;
    const card = t.closest(".gm-bopts");
    if (card) card.querySelectorAll(".gm-bo").forEach((b) => { b.disabled = true; if (b === t) b.classList.add("sel"); });
    try {
      const j = await rpc("game_guess", { p_round: Number(t.dataset.round), p_idx: Number(t.dataset.idx), p_guess: t.dataset.o });
      if (j && j.status === "ok") {
        localRes[key] = { ok: !!j.ok, guess: t.dataset.o, answer: j.answer };
        heard.add(key);
        sfx(!!j.ok);
        try { if (navigator.vibrate) navigator.vibrate(j.ok ? [20, 30, 20] : 60); } catch (e) { /* ignoré */ }
        refreshCard(key);
        chatPoll();
        setTimeout(chatPoll, 900);
        refresh();
      } else {
        picking[key] = false;
        if (card) card.querySelectorAll(".gm-bo").forEach((b) => { b.disabled = false; b.classList.remove("sel"); });
        refreshCard(key);
      }
    } catch (e) {
      picking[key] = false;
      if (card) card.querySelectorAll(".gm-bo").forEach((b) => { b.disabled = false; b.classList.remove("sel"); });
    }
  }

  function snippet(m) {
    const mt = m.meta || {};
    const q = bank && mt.qid ? bank.get(mt.qid) : null;
    if (mt.t === "card") return "🎮 " + (q ? q.q : "Question " + ((mt.idx || 0) + 1));
    if (mt.t === "result") return mt.ok ? "✅ Bonne réponse !" : "❌ Raté…";
    return (m.body || "🎮 Cupidon").slice(0, 80);
  }

  window.TMM_GAMES = { paintChip, attach, detach, refresh, row, onMessages, snippet };
})();
