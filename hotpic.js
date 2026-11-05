(() => {
  "use strict";
  // Appareil photo intégré (selfie / caméra arrière, pleine définition) + retouche. Sert à « Hot pic » et « Now pic ».
  const $e = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html !== undefined) e.innerHTML = html; return e; };

  // opts : {title, onResult({blob,w,h,once}), onClose()}
  function start(opts) {
    opts = opts || {};
    const root = $e("div", "hp");
    const ic = (n, z) => (window.IOS ? window.IOS.icon(n, z) : "");
    root.innerHTML = '<video class="hp-v" autoplay playsinline muted></video>' +
      '<div class="hp-top"><button class="glass round" data-h="close" aria-label="Fermer">' + ic("xmark", 20) + '</button>' +
      (opts.title ? '<div class="hp-title glass pill">' + (opts.icon ? ic(opts.icon, 18) : "") + "<span>" + opts.title + "</span></div>" : "<span></span>") + "<span></span></div>" +
      (opts.allowVideo ? '<div class="pe-seg hp-mode" data-i="0" style="--n:2"><i class="lens"></i><button data-h="mphoto" class="on">Photo</button><button data-h="mvideo">Vidéo</button></div>' : "") +
      '<div class="hp-msg glass" hidden></div>' +
      '<div class="hp-bar"><button class="glass round gal" data-h="gal" aria-label="Galerie">' + ic("photos", 24) + '</button><button class="hp-shutter" data-h="shot" aria-label="Prendre la photo"><i></i></button><button class="glass round flip" data-h="flip" aria-label="Changer de caméra">' + ic("flip", 24) + "</button></div>" +
      '<input type="file" accept="' + (opts.allowVideo ? "image/*,video/*" : "image/*") + '" hidden>';
    const chatEl = document.getElementById("chat");
    const inChat = !!(chatEl && window.TMM_CHAT && window.TMM_CHAT.isOpen());
    const parent = inChat ? chatEl : document.body;
    if (!inChat) root.classList.add("fixed");
    parent.appendChild(root);
    const vid = root.querySelector("video"), msg = root.querySelector(".hp-msg"), file = root.querySelector("input");
    let stream = null, facing = "user", busy = false, closed = false, videoMode = false;

    const stop = () => { if (stream) { stream.getTracks().forEach((t) => t.stop()); stream = null; } };
    const say = (t) => { msg.textContent = t || ""; msg.hidden = !t; };
    async function go() {
      stop();
      try {
        stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: facing }, width: { ideal: 4096 }, height: { ideal: 3072 } } });
      } catch (e) {
        try { stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: true }); }
        catch (e2) { say("Impossible d'ouvrir la caméra. Autorise-la dans les réglages, ou choisis une photo dans la galerie 🖼."); return; }
      }
      if (closed) { stop(); return; }
      say("");
      vid.srcObject = stream;
      const s = stream.getVideoTracks()[0].getSettings ? stream.getVideoTracks()[0].getSettings() : {};
      if (s.facingMode) facing = s.facingMode === "environment" ? "environment" : "user";
      vid.classList.toggle("mirror", facing === "user");
      try { await vid.play(); } catch (e) { /* ignoré */ }
    }
    const close = () => { closed = true; stop(); root.remove(); if (opts.onClose) opts.onClose(); };

    async function grab() {
      const track = stream && stream.getVideoTracks()[0];
      // Vraie photo du capteur quand le navigateur le permet (Chrome) ; sinon une image de la vidéo en pleine résolution.
      if (track && window.ImageCapture) {
        try { const b = await new ImageCapture(track).takePhoto(); if (b && b.size > 1000) return b; } catch (e) { /* on prend l'image vidéo */ }
      }
      const w = vid.videoWidth, h = vid.videoHeight;
      if (!w || !h) return null;
      const c = document.createElement("canvas"); c.width = w; c.height = h; c.getContext("2d").drawImage(vid, 0, 0, w, h);
      return c;
    }
    async function edit(src) {
      if (!window.TMM_EDIT) return;
      const r = await window.TMM_EDIT.open(src, { title: opts.title || "", parent: parent, fixed: !inChat, sched: opts.sched !== false });
      if (r) { close(); if (opts.onResult) opts.onResult(r); }
    }
    root.addEventListener("click", async (ev) => {
      const t = ev.target.closest("[data-h]");
      if (!t) return;
      const a = t.dataset.h;
      if (a === "close") close();
      else if (a === "flip") { facing = facing === "user" ? "environment" : "user"; go(); }
      else if (a === "gal") file.click();
      else if (a === "mphoto" || a === "mvideo") {
        videoMode = a === "mvideo";
        const sg = root.querySelector(".hp-mode"); sg.dataset.i = videoMode ? 1 : 0;
        sg.querySelectorAll("button").forEach((b) => b.classList.toggle("on", b === t));
        root.classList.toggle("vid", videoMode);
      }
      else if (a === "shot") {
        if (videoMode) { close(); if (opts.onVideoCapture) opts.onVideoCapture(); return; }
        if (busy) return; busy = true; root.classList.add("flash");
        try { const src = await grab(); if (src) await edit(src); else say("La caméra n'est pas prête. Réessaie."); } finally { busy = false; root.classList.remove("flash"); }
      }
    });
    file.addEventListener("change", async () => { const f = file.files && file.files[0]; file.value = "";
      if (!f) return;
      if ((f.type || "").startsWith("video/")) { close(); if (opts.onVideo) opts.onVideo(f); return; }
      await edit(f); });
    go();
    root._test = { shot: () => root.querySelector("[data-h=shot]").click() };
    return root;
  }

  window.TMM_HOT = { start };
})();
