(() => {
  "use strict";
  // Appels audio et vidéo (WebRTC) entre les deux partenaires. Supabase ne sert qu'à « sonner » et à échanger
  // les infos de connexion (table call_signals) ; le son et l'image passent directement d'un téléphone à l'autre.
  const A = () => window.TMM_API;
  const rpc = (name, body) => A().authed("POST", "/rest/v1/rpc/" + name, body || {});
  const $ = (id) => document.getElementById(id);
  const native = () => !!window.TMM_NATIVE;

  // Serveurs de connexion : STUN gratuit de Google + relais public de secours.
  // On peut les remplacer en ajoutant ICE_SERVERS dans config.js (par ex. un relais TURN Cloudflare).
  const DEFAULT_ICE = [
    { urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] },
    { urls: ["turn:openrelay.metered.ca:80", "turn:openrelay.metered.ca:443", "turn:openrelay.metered.ca:443?transport=tcp"], username: "openrelayproject", credential: "openrelayproject" },
  ];
  const iceServers = () => {
    try { const c = A() && A().CFG && A().CFG.ICE_SERVERS; if (Array.isArray(c) && c.length) return c; } catch (e) { /* ignoré */ }
    return DEFAULT_ICE;
  };

  // st : idle | out (ça sonne chez l'autre) | in (ça sonne ici) | connecting | active
  const S = {
    st: "idle", cid: "", video: false, dir: "", name: "", pc: null, local: null, remote: null, ready: null, pendingIce: [],
    lastId: 0, handled: new Set(), t0: 0, timer: null, ringTimer: null, tone: null, muted: false, camOff: false, facing: "user",
    sendChain: Promise.resolve(), pollTimer: null, polling: false, discTimer: null, hide: null, remoteSet: false,
  };

  const pad = (n) => String(n).padStart(2, "0");
  const mmss = (s) => pad(Math.floor(s / 60)) + ":" + pad(s % 60);
  const rid = () => (crypto.randomUUID ? crypto.randomUUID().replace(/-/g, "").slice(0, 16) : String(Date.now()) + Math.random().toString(36).slice(2, 8));
  const partnerName = () => { try { return (A() && A().partner && A().partner()) || ""; } catch (e) { return ""; } };

  // ---------- Sons : sonnerie d'appel douce ----------
  let ac = null;
  function unlock() {
    try { ac = ac || new (window.AudioContext || window.webkitAudioContext)(); if (ac.state === "suspended") ac.resume(); } catch (e) { /* pas de son */ }
  }
  ["click", "touchstart", "keydown"].forEach((ev) => document.addEventListener(ev, unlock, { passive: true }));
  function note(at, f, vol, dur) {
    const g = ac.createGain();
    g.gain.setValueAtTime(0.0001, at); g.gain.linearRampToValueAtTime(vol, at + 0.03); g.gain.exponentialRampToValueAtTime(0.0001, at + dur);
    g.connect(ac.destination);
    [[1, 1], [2, 0.22]].forEach(([m, a2]) => {
      const o = ac.createOscillator(), h = ac.createGain();
      o.type = "sine"; o.frequency.value = f * m; h.gain.value = a2;
      o.connect(h); h.connect(g); o.start(at); o.stop(at + dur + 0.05);
    });
  }
  function ringIn() {
    try { if (ac) { const t = ac.currentTime; [659.25, 783.99, 987.77, 783.99].forEach((f, k) => note(t + k * 0.2, f, 0.32, 0.7)); } } catch (e) { /* ignoré */ }
    try { if (navigator.vibrate) navigator.vibrate([300, 200, 300]); } catch (e) { /* ignoré */ }
  }
  function ringOut() {
    try { if (ac) { const t = ac.currentTime; note(t, 440, 0.18, 0.9); note(t + 0.5, 440, 0.14, 0.9); } } catch (e) { /* ignoré */ }
  }
  function startTone(kind) {
    stopTone();
    const f = kind === "in" ? ringIn : ringOut;
    f();
    S.tone = setInterval(f, kind === "in" ? 3200 : 3000);
  }
  function stopTone() { if (S.tone) { clearInterval(S.tone); S.tone = null; } try { if (navigator.vibrate) navigator.vibrate(0); } catch (e) { /* ignoré */ } }
  function endBeep() { try { if (ac) { const t = ac.currentTime; note(t, 523.25, 0.2, 0.4); note(t + 0.18, 392, 0.2, 0.6); } } catch (e) { /* ignoré */ } }

  // ---------- Signaux ----------
  function send(kind, payload) {
    const body = { p_kind: kind, p_payload: Object.assign({ cid: S.cid }, payload || {}) };
    S.sendChain = S.sendChain.then(() => rpc("call_send", body)).catch(() => {});
    return S.sendChain;
  }

  function stale(list, i) {
    const r = list[i], p = r.payload || {};
    for (let j = i + 1; j < list.length; j++) {
      const q = list[j], qp = q.payload || {};
      if (qp.cid === p.cid && (q.kind === "hangup" || q.kind === "reject" || q.kind === "accept")) return true;
    }
    return false;
  }

  async function poll() {
    if (S.polling || !A()) return;
    if (native() ? S.st === "idle" : !partnerName()) return;
    S.polling = true;
    S.fail = false;
    try {
      const rows = await rpc("call_poll", { p_after: S.lastId });
      if (Array.isArray(rows)) {
        for (let i = 0; i < rows.length; i++) {
          const r = rows[i];
          if (r.id > S.lastId) S.lastId = r.id;
          if (r.kind === "ring" && stale(rows, i)) { const c = r.payload && r.payload.cid; if (c) S.handled.add(c); continue; }
          try { await handle(r); } catch (e) { /* un signal abîmé ne casse pas l'appel */ }
        }
      }
    } catch (e) { S.fail = true; /* hors-ligne, ou appels.sql pas encore installé */ }
    S.polling = false;
  }
  function loop() {
    clearTimeout(S.pollTimer);
    poll().finally(() => { S.pollTimer = setTimeout(loop, S.st !== "idle" ? 800 : S.fail ? 20000 : 2500); });
  }

  async function handle(sig) {
    const p = sig.payload || {};
    if (sig.kind === "ring") {
      if (S.handled.has(p.cid)) return;
      if (S.st !== "idle") { S.handled.add(p.cid); const keep = S.cid; S.cid = p.cid; await send("reject", { busy: true }); S.cid = keep; return; }
      S.handled.add(p.cid);
      incoming(p.cid, !!p.video);
      return;
    }
    if (p.cid !== S.cid || S.st === "idle") return;
    switch (sig.kind) {
      case "accept": if (S.dir === "out" && S.st === "out") await makeOffer(); break;
      case "offer": if (S.dir === "in") await onOffer(p.sdp); break;
      case "answer": if (S.dir === "out" && S.pc && !S.remoteSet) await setRemote(p.sdp); break;
      case "ice": await addIce(p.c); break;
      case "reject": finish(p.busy ? "Occupé pour le moment" : "Appel refusé", false); break;
      case "hangup": finish(S.st === "active" || S.st === "connecting" ? "Appel terminé" : "Appel manqué", false); break;
      default: break;
    }
  }

  // ---------- Média et connexion ----------
  async function getMedia(video) {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) throw new Error("nomedia");
    return navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      video: video ? { facingMode: S.facing, width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 24 } } : false,
    });
  }
  function createPc() {
    const pc = new RTCPeerConnection({ iceServers: iceServers() });
    S.pc = pc; S.remoteSet = false; S.pendingIce = [];
    S.local.getTracks().forEach((t) => pc.addTrack(t, S.local));
    pc.onicecandidate = (e) => { if (e.candidate) send("ice", { c: e.candidate.toJSON ? e.candidate.toJSON() : e.candidate }); };
    pc.ontrack = (e) => {
      S.remote = e.streams && e.streams[0] ? e.streams[0] : S.remote || new MediaStream();
      if (!(e.streams && e.streams[0])) S.remote.addTrack(e.track);
      const v = $("cl-remote");
      if (v) { v.srcObject = S.remote; const pl = v.play(); if (pl && pl.catch) pl.catch(() => {}); }
      paint();
    };
    const onState = () => {
      const st = pc.connectionState || pc.iceConnectionState;
      if (st === "connected" || st === "completed") {
        clearTimeout(S.discTimer); S.discTimer = null;
        if (S.st !== "active") { S.st = "active"; S.t0 = Date.now(); stopTone(); clearTimeout(S.ringTimer); startTimer(); paint(); }
      } else if (st === "disconnected") {
        setStatus("Connexion instable…");
        clearTimeout(S.discTimer);
        S.discTimer = setTimeout(() => finish("Connexion perdue", true), 9000);
      } else if (st === "failed") finish("Connexion impossible. Vérifie ta connexion.", true);
    };
    pc.onconnectionstatechange = onState;
    pc.oniceconnectionstatechange = onState;
    return pc;
  }
  async function setRemote(sdp) {
    await S.pc.setRemoteDescription(sdp);
    S.remoteSet = true;
    const q = S.pendingIce; S.pendingIce = [];
    for (const c of q) { try { await S.pc.addIceCandidate(c); } catch (e) { /* ignoré */ } }
  }
  async function addIce(c) {
    if (!c) return;
    if (!S.pc || !S.remoteSet) { S.pendingIce.push(c); return; }
    try { await S.pc.addIceCandidate(c); } catch (e) { /* ignoré */ }
  }
  async function makeOffer() {
    S.st = "connecting"; paint();
    createPc();
    const o = await S.pc.createOffer();
    await S.pc.setLocalDescription(o);
    await send("offer", { sdp: { type: o.type, sdp: o.sdp } });
  }
  async function onOffer(sdp) {
    if (S.ready) await S.ready;
    if (!S.pc || S.remoteSet) return;
    await setRemote(sdp);
    const a = await S.pc.createAnswer();
    await S.pc.setLocalDescription(a);
    await send("answer", { sdp: { type: a.type, sdp: a.sdp } });
  }

  // ---------- Démarrer / répondre / raccrocher ----------
  async function start(video) {
    unlock();
    if (S.st !== "idle") return;
    if (!A() || !partnerName()) { say("Il faut d'abord avoir un partenaire."); return; }
    if (!window.RTCPeerConnection) { say("Ton navigateur ne gère pas les appels."); return; }
    S.cid = rid(); S.handled.add(S.cid); S.video = !!video; S.dir = "out"; S.name = partnerName();
    S.st = "out"; S.muted = false; S.camOff = false;
    paint();
    try { S.local = await getMedia(S.video); }
    catch (e) { S.st = "idle"; hideUi(); say(S.video ? "Autorise le micro et la caméra pour appeler." : "Autorise le micro pour appeler."); return; }
    if (S.st !== "out") { stopLocal(); return; }
    paint();
    if (!native()) loop();
    const r = await send("ring", { video: S.video });
    startTone("out");
    S.ringTimer = setTimeout(() => { if (S.st === "out") finish("Pas de réponse", true); }, 45000);
    return r;
  }
  function incoming(cid, video) {
    S.cid = cid; S.video = video; S.dir = "in"; S.name = partnerName() || S.name || ""; S.st = "in"; S.muted = false; S.camOff = false;
    paint();
    startTone("in");
    clearTimeout(S.ringTimer);
    S.ringTimer = setTimeout(() => { if (S.st === "in") finish("Appel manqué", false); }, 50000);
  }
  async function accept() {
    unlock();
    if (S.st !== "in") return;
    stopTone(); clearTimeout(S.ringTimer);
    S.st = "connecting"; paint();
    S.ready = (async () => {
      S.local = await getMedia(S.video);
      createPc();
    })();
    try { await S.ready; }
    catch (e) { S.ready = null; finish(S.video ? "Micro ou caméra refusés" : "Micro refusé", true, "reject"); return; }
    paint();
    await send("accept");
  }
  function reject() { if (S.st === "in") finish("", true, "reject"); }
  function hangup() { if (S.st === "in") reject(); else if (S.st !== "idle") finish("", true); }

  function stopLocal() {
    if (S.local) { S.local.getTracks().forEach((t) => { try { t.stop(); } catch (e) { /* ignoré */ } }); S.local = null; }
  }
  function finish(msg, notify, kind) {
    if (S.st === "idle") return;
    const wasActive = S.st === "active";
    const dur = S.t0 ? Math.round((Date.now() - S.t0) / 1000) : 0;
    const wasOut = S.dir === "out", video = S.video;
    if (notify) send(kind || "hangup");
    stopTone(); clearTimeout(S.ringTimer); clearTimeout(S.discTimer); clearInterval(S.timer);
    if (S.pc) { try { S.pc.close(); } catch (e) { /* ignoré */ } S.pc = null; }
    stopLocal();
    const rv = $("cl-remote"); if (rv) rv.srcObject = null;
    S.remote = null; S.ready = null; S.t0 = 0; S.remoteSet = false; S.pendingIce = [];
    S.handled.add(S.cid);
    S.st = "idle";
    // Seul celui qui a appelé écrit la trace dans le chat (ainsi elle n'apparaît qu'une fois).
    if (wasOut) {
      let line = "";
      if (wasActive) line = (video ? "📹 Appel vidéo terminé · " : "📞 Appel terminé · ") + mmss(dur);
      else if (msg === "Pas de réponse" || msg === "Appel manqué" || (!msg && notify)) line = video ? "📹 Appel vidéo manqué" : "📞 Appel manqué";
      if (line) rpc("send_message", { p_kind: "text", p_body: line, p_media: null, p_meta: null }).then(() => { try { if (window.TMM_CHAT && window.TMM_CHAT.poll) window.TMM_CHAT.poll(); } catch (e) { /* ignoré */ } }).catch(() => {});
    }
    if (msg) { endBeep(); showEnd(msg); } else hideUi();
    if (native() && window.TMM_NATIVE_END) { setTimeout(() => { try { window.TMM_NATIVE_END(); } catch (e) { /* ignoré */ } }, msg ? 1600 : 50); }
  }

  // ---------- Boutons en cours d'appel ----------
  function toggleMute() {
    S.muted = !S.muted;
    if (S.local) S.local.getAudioTracks().forEach((t) => { t.enabled = !S.muted; });
    paint();
  }
  function toggleCam() {
    S.camOff = !S.camOff;
    if (S.local) S.local.getVideoTracks().forEach((t) => { t.enabled = !S.camOff; });
    paint();
  }
  async function flip() {
    if (!S.local || !S.video) return;
    const next = S.facing === "user" ? "environment" : "user";
    try {
      const ns = await navigator.mediaDevices.getUserMedia({ video: { facingMode: next, width: { ideal: 640 }, height: { ideal: 480 } }, audio: false });
      const nt = ns.getVideoTracks()[0];
      const old = S.local.getVideoTracks()[0];
      const sender = S.pc && S.pc.getSenders().find((x) => x.track && x.track.kind === "video");
      if (sender) await sender.replaceTrack(nt);
      if (old) { S.local.removeTrack(old); old.stop(); }
      nt.enabled = !S.camOff;
      S.local.addTrack(nt);
      S.facing = next;
      const lv = $("cl-local"); if (lv) { lv.srcObject = S.local; }
      paint();
    } catch (e) { say("Impossible de changer de caméra."); }
  }

  // ---------- Écran d'appel ----------
  function say(t) {
    if (window.TMM_CHAT && window.TMM_CHAT.toast) { window.TMM_CHAT.toast(t); return; }
    let el = $("cl-toast");
    if (!el) { el = document.createElement("div"); el.id = "cl-toast"; el.className = "cl-toast"; document.body.appendChild(el); }
    el.textContent = t; el.hidden = false;
    clearTimeout(say.t); say.t = setTimeout(() => { el.hidden = true; }, 3200);
  }
  function setStatus(t) { const el = $("cl-status"); if (el) el.textContent = t; }
  function startTimer() {
    clearInterval(S.timer);
    S.timer = setInterval(() => { if (S.st === "active") setStatus(mmss(Math.round((Date.now() - S.t0) / 1000))); }, 500);
  }
  function build() {
    let el = $("tmm-call");
    if (el) return el;
    el = document.createElement("div");
    el.id = "tmm-call"; el.className = "cl"; el.hidden = true;
    el.innerHTML = `<video id="cl-remote" class="cl-remote" autoplay playsinline></video>
      <div class="cl-top"><div class="cl-avatar">💗</div><b id="cl-name"></b><small id="cl-status"></small></div>
      <video id="cl-local" class="cl-local" autoplay playsinline muted hidden></video>
      <div class="cl-bar" id="cl-bar"></div>`;
    el.addEventListener("click", (ev) => {
      const b = ev.target.closest("[data-cl]");
      if (!b) return;
      const a = b.dataset.cl;
      if (a === "accept") accept(); else if (a === "reject") reject(); else if (a === "hang") hangup();
      else if (a === "mute") toggleMute(); else if (a === "cam") toggleCam(); else if (a === "flip") flip();
    });
    document.body.appendChild(el);
    return el;
  }
  const ICO = {
    phone: '<svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.6 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.6 3.6a1 1 0 0 1-.25 1z"/></svg>',
    video: '<svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M15 8a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2v-2.6l5 3.4V7.2l-5 3.4z"/></svg>',
    mic: '<svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><path d="M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.9V22h2v-3.1A7 7 0 0 0 19 12z"/></svg>',
    micoff: '<svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><path d="M12 15a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.9V22h2v-3.1A7 7 0 0 0 19 12zM3.3 3.3 2 4.6l18.4 18.4 1.3-1.3z"/></svg>',
    flip: '<svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><path d="M20 4h-3.2L15 2H9L7.2 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm-5 11.5V13H9v2.5L5.5 12 9 8.5V11h6V8.5l3.5 3.5z"/></svg>',
  };
  function btn(act, cls, ico, label) { return `<button class="cl-btn ${cls}" data-cl="${act}" aria-label="${label}">${ico}<span>${label}</span></button>`; }
  function paint() {
    if (S.st === "idle") return;
    const el = build();
    el.hidden = false; el.classList.remove("ended");
    el.classList.toggle("audio", !S.video);
    el.classList.toggle("live", S.video && S.st === "active");
    $("cl-name").textContent = S.label || ("@" + (S.name || partnerName() || "…"));
    const lv = $("cl-local");
    if (lv) { lv.hidden = !(S.video && S.local); if (S.local && lv.srcObject !== S.local) { lv.srcObject = S.local; const pl = lv.play(); if (pl && pl.catch) pl.catch(() => {}); } lv.style.transform = S.facing === "user" ? "scaleX(-1)" : "none"; }
    const rv = $("cl-remote"); if (rv && S.remote && rv.srcObject !== S.remote) { rv.srcObject = S.remote; const pl = rv.play(); if (pl && pl.catch) pl.catch(() => {}); }
    const label = S.video ? "Appel vidéo" : "Appel audio";
    let status = "";
    if (S.st === "out") status = S.local ? "Ça sonne…" : "Préparation…";
    else if (S.st === "in") status = label + " entrant…";
    else if (S.st === "connecting") status = "Connexion…";
    else if (S.st === "active") status = mmss(Math.round((Date.now() - S.t0) / 1000));
    setStatus(status);
    let bar = "";
    if (S.st === "in") bar = btn("reject", "red", ICO.phone, "Refuser") + btn("accept", "green", S.video ? ICO.video : ICO.phone, "Répondre");
    else {
      bar = btn("mute", S.muted ? "on" : "", S.muted ? ICO.micoff : ICO.mic, S.muted ? "Micro coupé" : "Micro");
      if (S.video) bar += btn("flip", "", ICO.flip, "Caméra");
      bar += btn("hang", "red", ICO.phone, "Raccrocher");
    }
    const b = $("cl-bar");
    if (b.dataset.k !== bar) { b.innerHTML = bar; b.dataset.k = bar; }
  }
  function showEnd(msg) {
    const el = build();
    el.hidden = false; el.classList.add("ended");
    $("cl-name").textContent = S.label || ("@" + (S.name || partnerName() || "…"));
    setStatus(msg);
    $("cl-bar").innerHTML = ""; $("cl-bar").dataset.k = "";
    const lv = $("cl-local"); if (lv) { lv.hidden = true; lv.srcObject = null; }
    clearTimeout(S.hide);
    S.hide = setTimeout(hideUi, 1500);
  }
  function hideUi() { const el = $("tmm-call"); if (el && S.st === "idle") el.hidden = true; }

  // ---------- Démarrage ----------
  function init() {
    if (native()) return;
    loop();
  }
  // Appli Android : l'écran d'appel est une page dédiée ouverte par l'appli (appel sortant, ou sonnerie déjà détectée).
  function nativeStart(o) {
    window.TMM_NATIVE = true;
    S.name = o.name || "";
    S.label = o.label || "";
    S.lastId = Number(o.after) || 0;
    loop();
    if (o.mode === "in") { S.handled.add(o.cid); incoming(o.cid, !!o.video); }
    else start(!!o.video);
  }

  window.TMM_CALL = { init, start, accept, reject, hangup, nativeStart, state: () => S.st };
  if (!native()) {
    const tryInit = () => { if (A()) init(); else setTimeout(tryInit, 500); };
    tryInit();
  }
})();
