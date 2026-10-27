(() => {
  "use strict";
  // Éditeur de photo : filtres, texte, emojis, autocollants, déco, tape (washi).
  // TMM_EDIT.open(source, {title}) -> Promise<{blob, w, h, once} | null>
  // source : Blob, File, canvas ou adresse d'image. Partagé entre le web et l'appli Android (WebView).

  const MAXPX = 4096;      // plus grand côté de la photo finale (≈ 12 millions de pixels)
  const PREVIEW = 1280;    // côté du calcul à l'écran

  // ---------- Filtres ----------
  // br : luminosité, ct : contraste, sat : saturation, sepia : 0..1, add : [r,g,b], fade : 0..1, vig : vignette 0..1
  const FILTERS = [
    { n: "Original", f: {} },
    { n: "Éclat", f: { br: 1.06, ct: 1.08, sat: 1.28 } },
    { n: "Chaud", f: { br: 1.03, sat: 1.12, add: [16, 5, -14] } },
    { n: "Froid", f: { sat: .95, add: [-12, 1, 18] } },
    { n: "Rosé", f: { br: 1.04, sat: 1.15, add: [20, -4, 8] } },
    { n: "Doré", f: { br: 1.05, sat: 1.2, sepia: .25, add: [22, 12, -10] } },
    { n: "Vintage", f: { ct: .92, sepia: .45, fade: .08, add: [10, 4, -8], vig: .28 } },
    { n: "Fondu", f: { br: 1.08, ct: .85, sat: .85, fade: .12 } },
    { n: "Drama", f: { br: .96, ct: 1.3, sat: 1.15, vig: .32 } },
    { n: "Noir & blanc", f: { sat: 0, ct: 1.15 } },
    { n: "Nuit", f: { br: .86, sat: 1.05, add: [-10, -4, 16], vig: .2 } },
  ];

  function filterBands(ctx, w, h, f) {
    const hasPix = f.br || f.ct || f.sat !== undefined || f.sepia || f.add || f.fade;
    if (hasPix) {
      const br = f.br || 1, ct = f.ct || 1, sat = f.sat === undefined ? 1 : f.sat, sp = f.sepia || 0, fd = f.fade || 0;
      const ad = f.add || [0, 0, 0];
      const BAND = 256;
      for (let y0 = 0; y0 < h; y0 += BAND) {
        const hh = Math.min(BAND, h - y0);
        const id = ctx.getImageData(0, y0, w, hh), d = id.data;
        for (let i = 0; i < d.length; i += 4) {
          let r = d[i] * br, g = d[i + 1] * br, b = d[i + 2] * br;
          if (ct !== 1) { r = (r - 128) * ct + 128; g = (g - 128) * ct + 128; b = (b - 128) * ct + 128; }
          if (sat !== 1) { const l = .299 * r + .587 * g + .114 * b; r = l + (r - l) * sat; g = l + (g - l) * sat; b = l + (b - l) * sat; }
          if (sp) {
            const sr = .393 * r + .769 * g + .189 * b, sg = .349 * r + .686 * g + .168 * b, sb = .272 * r + .534 * g + .131 * b;
            r += (sr - r) * sp; g += (sg - g) * sp; b += (sb - b) * sp;
          }
          if (fd) { r = r * (1 - fd) + 255 * fd * .55; g = g * (1 - fd) + 255 * fd * .55; b = b * (1 - fd) + 255 * fd * .55; }
          r += ad[0]; g += ad[1]; b += ad[2];
          d[i] = r < 0 ? 0 : r > 255 ? 255 : r; d[i + 1] = g < 0 ? 0 : g > 255 ? 255 : g; d[i + 2] = b < 0 ? 0 : b > 255 ? 255 : b;
        }
        ctx.putImageData(id, 0, y0);
      }
    }
    if (f.vig) {
      const gr = ctx.createRadialGradient(w / 2, h / 2, Math.min(w, h) * .3, w / 2, h / 2, Math.hypot(w, h) * .55);
      gr.addColorStop(0, "rgba(0,0,0,0)"); gr.addColorStop(1, "rgba(0,0,0," + f.vig + ")");
      ctx.fillStyle = gr; ctx.fillRect(0, 0, w, h);
    }
  }

  // ---------- Éléments dessinés sur la photo ----------
  const BADGES = [
    ["TU ME MANQUES", "#E91E63", "#fff"], ["JE T'AIME", "#FF5252", "#fff"], ["BISOUS 💋", "#FFB300", "#2b1b00"], ["MISS YOU", "#7C4DFF", "#fff"],
    ["MON AMOUR", "#00BFA5", "#fff"], ["HOT 🔥", "#FF6D00", "#fff"], ["NOW 📸", "#212121", "#FFEB3B"], ["CÂLIN 🤗", "#29B6F6", "#fff"],
    ["♥ ♥ ♥", "#ffffff", "#E91E63"], ["COUCOU !", "#FFEE58", "#333"], ["TROP MIGNON", "#F48FB1", "#fff"], ["BONNE NUIT 🌙", "#283593", "#fff"],
  ];
  const DECOS = ["sparkle:#FFD54F", "sparkle:#FF80AB", "sparkle:#FFFFFF", "heart:#FF1744", "heart:#FF80AB", "heart:#FFFFFF", "star:#FFEB3B", "star:#FF9100",
    "bolt:#FFEA00", "arrow:#FFFFFF", "arrow:#FF4081", "squiggle:#FFFFFF", "squiggle:#FFEB3B", "confetti:#FF4081", "ring:#FFFFFF", "burst:#FF4081"];
  const TAPES = [
    { p: "plain", c: "#FFB3C7" }, { p: "dots", c: "#FFD54F" }, { p: "stripes", c: "#FF80AB" }, { p: "gingham", c: "#EF5350" },
    { p: "hearts", c: "#F8BBD0" }, { p: "kraft", c: "#D7B98E" }, { p: "stripes", c: "#4DD0E1" }, { p: "dots", c: "#B39DDB" },
  ];
  const TEXT_COLORS = ["#FFFFFF", "#111111", "#FF4081", "#FFEB3B", "#FF5252", "#69F0AE", "#40C4FF", "#B388FF"];

  function pathSparkle(c, s) { c.beginPath(); c.moveTo(0, -s); c.quadraticCurveTo(s * .12, -s * .12, s, 0); c.quadraticCurveTo(s * .12, s * .12, 0, s); c.quadraticCurveTo(-s * .12, s * .12, -s, 0); c.quadraticCurveTo(-s * .12, -s * .12, 0, -s); c.closePath(); }
  function pathHeart(c, s) { c.beginPath(); c.moveTo(0, s * .85); c.bezierCurveTo(-s * 1.3, s * .1, -s * .75, -s * .9, 0, -s * .35); c.bezierCurveTo(s * .75, -s * .9, s * 1.3, s * .1, 0, s * .85); c.closePath(); }
  function pathStar(c, s) { c.beginPath(); for (let i = 0; i < 10; i++) { const a = -Math.PI / 2 + i * Math.PI / 5, r = i % 2 ? s * .45 : s; c.lineTo(Math.cos(a) * r, Math.sin(a) * r); } c.closePath(); }
  function pathBolt(c, s) { c.beginPath(); c.moveTo(s * .25, -s); c.lineTo(-s * .6, s * .15); c.lineTo(-s * .05, s * .15); c.lineTo(-s * .3, s); c.lineTo(s * .65, -s * .2); c.lineTo(s * .05, -s * .2); c.closePath(); }

  function drawDeco(c, kind, col, s) {
    c.fillStyle = col; c.strokeStyle = col; c.lineJoin = "round"; c.lineCap = "round";
    c.shadowColor = "rgba(0,0,0,.35)"; c.shadowBlur = s * .12; c.shadowOffsetY = s * .04;
    if (kind === "sparkle") { pathSparkle(c, s); c.fill(); c.save(); c.scale(.45, .45); c.rotate(Math.PI / 4); pathSparkle(c, s * .9); c.fill(); c.restore(); }
    else if (kind === "heart") { pathHeart(c, s); c.fill(); c.shadowColor = "transparent"; c.fillStyle = "rgba(255,255,255,.45)"; c.beginPath(); c.ellipse(-s * .42, -s * .2, s * .16, s * .09, -.7, 0, 7); c.fill(); }
    else if (kind === "star") { pathStar(c, s); c.fill(); }
    else if (kind === "bolt") { pathBolt(c, s); c.fill(); }
    else if (kind === "arrow") { c.lineWidth = s * .16; c.beginPath(); c.moveTo(-s, s * .5); c.quadraticCurveTo(-s * .2, -s * .9, s * .8, -s * .15); c.stroke(); c.beginPath(); c.moveTo(s * .35, -s * .35); c.lineTo(s * .85, -s * .12); c.lineTo(s * .45, s * .25); c.stroke(); }
    else if (kind === "squiggle") { c.lineWidth = s * .14; c.beginPath(); c.moveTo(-s, 0); for (let i = 0; i < 4; i++) c.quadraticCurveTo(-s + s * .5 * (2 * i + 1), (i % 2 ? 1 : -1) * s * .35, -s + s * .5 * (2 * i + 2), 0); c.stroke(); }
    else if (kind === "confetti") { const cols = ["#FF4081", "#FFEB3B", "#40C4FF", "#69F0AE", "#B388FF", "#FF9100"]; for (let i = 0; i < 14; i++) { const a = i * 2.4, r = s * (.25 + (i % 5) * .15); c.save(); c.translate(Math.cos(a) * r, Math.sin(a) * r); c.rotate(i); c.fillStyle = cols[i % 6]; c.fillRect(-s * .06, -s * .13, s * .12, s * .26); c.restore(); } }
    else if (kind === "ring") { c.lineWidth = s * .1; c.setLineDash([s * .18, s * .16]); c.beginPath(); c.arc(0, 0, s * .9, 0, 7); c.stroke(); c.setLineDash([]); }
    else if (kind === "burst") { c.beginPath(); for (let i = 0; i < 24; i++) { const a = i * Math.PI / 12, r = i % 2 ? s * .62 : s; c.lineTo(Math.cos(a) * r, Math.sin(a) * r); } c.closePath(); c.fill(); }
  }

  function drawTape(c, t, w, h) {
    c.save();
    c.shadowColor = "rgba(0,0,0,.25)"; c.shadowBlur = h * .25; c.shadowOffsetY = h * .08;
    c.beginPath();
    const z = h * .16, n = 6, x0 = -w / 2, y0 = -h / 2;
    c.moveTo(x0, y0);
    c.lineTo(x0 + w, y0);
    for (let i = 0; i <= n; i++) c.lineTo(x0 + w + (i % 2 ? 0 : z), y0 + h * i / n);
    c.lineTo(x0, y0 + h);
    for (let i = n; i >= 0; i--) c.lineTo(x0 + (i % 2 ? 0 : z), y0 + h * i / n);
    c.closePath();
    c.fillStyle = t.c; c.globalAlpha = .88; c.fill();
    c.shadowColor = "transparent"; c.clip(); c.globalAlpha = .9;
    if (t.p === "dots") { c.fillStyle = "rgba(255,255,255,.8)"; for (let x = x0 + h * .3; x < x0 + w; x += h * .5) for (let y = y0 + h * .25, k = 0; y < y0 + h; y += h * .5, k++) { c.beginPath(); c.arc(x + (k % 2 ? h * .25 : 0), y, h * .09, 0, 7); c.fill(); } }
    else if (t.p === "stripes") { c.fillStyle = "rgba(255,255,255,.55)"; for (let x = x0 - h; x < x0 + w + h; x += h * .5) { c.beginPath(); c.moveTo(x, y0 + h); c.lineTo(x + h * .25, y0 + h); c.lineTo(x + h * .25 + h, y0); c.lineTo(x + h, y0); c.fill(); } }
    else if (t.p === "gingham") { c.fillStyle = "rgba(255,255,255,.5)"; const q = h / 3; for (let i = 0; i * q < w + q; i++) for (let j = 0; j < 3; j++) if ((i + j) % 2 === 0) c.fillRect(x0 + i * q, y0 + j * q, q, q); }
    else if (t.p === "hearts") { c.fillStyle = "rgba(233,30,99,.7)"; for (let x = x0 + h * .5; x < x0 + w; x += h * .9) { c.save(); c.translate(x, 0); pathHeart(c, h * .2); c.fill(); c.restore(); } }
    else if (t.p === "kraft") { c.strokeStyle = "rgba(120,80,40,.25)"; c.lineWidth = Math.max(1, h * .02); for (let i = 0; i < 40; i++) { const x = x0 + (i * 37 % 100) / 100 * w, y = y0 + (i * 53 % 100) / 100 * h; c.beginPath(); c.moveTo(x, y); c.lineTo(x + h * .35, y + (i % 3 - 1) * h * .03); c.stroke(); } }
    c.restore();
  }

  // Taille (demi-largeur / demi-hauteur) d'un élément, en unité k (= largeur de l'image × échelle).
  const measureCtx = document.createElement("canvas").getContext("2d");
  function layout(it, k) {
    if (it.t === "text") {
      const fs = it.size * k; measureCtx.font = "800 " + fs + "px -apple-system, Helvetica, Arial, sans-serif";
      const lines = it.text.split("\n"); let w = 0; lines.forEach((l) => { w = Math.max(w, measureCtx.measureText(l).width); });
      const pad = it.style === "box" ? fs * .35 : fs * .1;
      return { fs, lines, w: w + pad * 2, h: lines.length * fs * 1.2 + pad * 1.2, pad };
    }
    if (it.t === "badge") {
      const fs = it.size * k; measureCtx.font = "900 " + fs + "px -apple-system, Helvetica, Arial, sans-serif";
      return { fs, w: measureCtx.measureText(it.text).width + fs * 1.1, h: fs * 1.9 };
    }
    if (it.t === "tape") return { w: it.size * k, h: it.size * k * .17 };
    const s = it.size * k; return { s, w: s * 2, h: s * 2 };
  }

  function drawItem(c, it, W, H) {
    const k = (it.scl || 1) * W, L = layout(it, k);
    c.save();
    c.translate(it.x * W, it.y * H); c.rotate(it.rot || 0);
    if (it.t === "text") {
      c.font = "800 " + L.fs + "px -apple-system, Helvetica, Arial, sans-serif"; c.textAlign = "center"; c.textBaseline = "middle"; c.lineJoin = "round";
      if (it.style === "box") {
        c.fillStyle = it.color === "#111111" ? "rgba(255,255,255,.92)" : "rgba(0,0,0,.62)";
        const r = L.fs * .3; c.beginPath(); c.roundRect ? c.roundRect(-L.w / 2, -L.h / 2, L.w, L.h, r) : c.rect(-L.w / 2, -L.h / 2, L.w, L.h); c.fill();
      }
      L.lines.forEach((ln, i) => {
        const y = (i - (L.lines.length - 1) / 2) * L.fs * 1.2;
        if (it.style === "outline") { c.strokeStyle = it.color === "#111111" ? "#fff" : "#000"; c.lineWidth = L.fs * .16; c.strokeText(ln, 0, y); }
        else if (it.style !== "box") { c.shadowColor = "rgba(0,0,0,.55)"; c.shadowBlur = L.fs * .2; c.shadowOffsetY = L.fs * .04; }
        c.fillStyle = it.color; c.fillText(ln, 0, y); c.shadowColor = "transparent";
      });
    } else if (it.t === "emoji") {
      c.font = L.s * 1.6 + "px -apple-system, 'Apple Color Emoji', 'Segoe UI Emoji', 'Noto Color Emoji', sans-serif"; c.textAlign = "center"; c.textBaseline = "middle";
      c.shadowColor = "rgba(0,0,0,.3)"; c.shadowBlur = L.s * .15; c.fillText(it.ch, 0, L.s * .08);
    } else if (it.t === "badge") {
      const r = L.h / 2;
      c.shadowColor = "rgba(0,0,0,.4)"; c.shadowBlur = L.fs * .25; c.shadowOffsetY = L.fs * .08;
      c.fillStyle = "#fff"; c.beginPath(); c.roundRect ? c.roundRect(-L.w / 2, -L.h / 2, L.w, L.h, r) : c.rect(-L.w / 2, -L.h / 2, L.w, L.h); c.fill();
      c.shadowColor = "transparent";
      const b = L.fs * .16; c.fillStyle = it.bg; c.beginPath(); c.roundRect ? c.roundRect(-L.w / 2 + b, -L.h / 2 + b, L.w - 2 * b, L.h - 2 * b, r - b) : c.rect(-L.w / 2 + b, -L.h / 2 + b, L.w - 2 * b, L.h - 2 * b); c.fill();
      c.font = "900 " + L.fs + "px -apple-system, Helvetica, Arial, sans-serif"; c.textAlign = "center"; c.textBaseline = "middle"; c.fillStyle = it.fg; c.fillText(it.text, 0, L.fs * .04);
    } else if (it.t === "tape") drawTape(c, it.tape, L.w, L.h);
    else if (it.t === "deco") { const [kind, col] = it.d.split(":"); drawDeco(c, kind, col, L.s); }
    c.restore();
  }

  // ---------- Fenêtre ----------
  const $e = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html !== undefined) e.innerHTML = html; return e; };
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  async function loadBase(src) {
    let bmp;
    if (src instanceof HTMLCanvasElement) bmp = src;
    else {
      const blob = src instanceof Blob ? src : await (await fetch(src)).blob();
      try { bmp = await createImageBitmap(blob, { imageOrientation: "from-image" }); }
      catch (e) {
        bmp = await new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = rej; i.src = URL.createObjectURL(blob); });
      }
    }
    const w0 = bmp.width || bmp.naturalWidth, h0 = bmp.height || bmp.naturalHeight;
    const s = Math.min(1, MAXPX / Math.max(w0, h0));
    const B = document.createElement("canvas"); B.width = Math.max(1, Math.round(w0 * s)); B.height = Math.max(1, Math.round(h0 * s));
    B.getContext("2d").drawImage(bmp, 0, 0, B.width, B.height);
    if (bmp.close) try { bmp.close(); } catch (e) { /* ignoré */ }
    return B;
  }

  function open(source, opts) {
    opts = opts || {};
    return new Promise(async (resolve) => {
      let B;
      try { B = await loadBase(source); } catch (e) { resolve(null); return; }
      const ps = Math.min(1, PREVIEW / Math.max(B.width, B.height));
      const PW = Math.round(B.width * ps), PH = Math.round(B.height * ps);
      const P = document.createElement("canvas"); P.width = PW; P.height = PH; P.getContext("2d").drawImage(B, 0, 0, PW, PH);
      const PF = document.createElement("canvas"); PF.width = PW; PF.height = PH;
      let fi = 0, items = [], sel = null, tab = "filtres", textStyle = "plain", textColor = "#FFFFFF", stkTab = 0;

      const root = $e("div", "pe");
      root.innerHTML = '<div class="pe-top"><button class="pe-x" data-p="cancel" aria-label="Fermer">✕</button><b class="pe-title">' + esc(opts.title || "Retouche") + '</b><button class="pe-del" data-p="del" hidden>🗑 Supprimer</button></div>' +
        '<div class="pe-stage"><canvas class="pe-cv"></canvas></div>' +
        '<div class="pe-tray" id="pe-tray"></div>' +
        '<div class="pe-tabs"><button data-p="tab" data-t="filtres" class="on">🎞 Filtres</button><button data-p="tab" data-t="texte">Aa Texte</button><button data-p="tab" data-t="stickers">😀 Stickers</button><button data-p="tab" data-t="tape">🎀 Tape</button></div>' +
        '<div class="pe-send"><button class="pe-btn" data-p="send">➤ Envoyer</button><button class="pe-btn hot" data-p="once">🔥 Éphémère</button></div>';
      if (opts.fixed) root.classList.add("fixed");
      (opts.parent || document.body).appendChild(root);
      const cv = root.querySelector(".pe-cv"); cv.width = PW; cv.height = PH;
      const cx = cv.getContext("2d");
      const tray = root.querySelector("#pe-tray");

      const applyFilterPreview = () => {
        const c = PF.getContext("2d"); c.clearRect(0, 0, PW, PH); c.drawImage(P, 0, 0); filterBands(c, PW, PH, FILTERS[fi].f);
      };
      const render = () => {
        cx.clearRect(0, 0, PW, PH); cx.drawImage(PF, 0, 0);
        items.forEach((it) => drawItem(cx, it, PW, PH));
        if (sel) { // cadre de sélection
          const k = (sel.scl || 1) * PW, L = layout(sel, k);
          cx.save(); cx.translate(sel.x * PW, sel.y * PH); cx.rotate(sel.rot || 0);
          cx.strokeStyle = "rgba(255,255,255,.95)"; cx.lineWidth = Math.max(2, PW / 400); cx.setLineDash([PW / 60, PW / 90]);
          cx.strokeRect(-L.w / 2 - 6, -L.h / 2 - 6, L.w + 12, L.h + 12); cx.restore();
        }
        root.querySelector(".pe-del").hidden = !sel;
      };

      // ----- Barre du bas -----
      const thumbs = [];
      const fillTray = () => {
        if (tab === "filtres") {
          tray.innerHTML = '<div class="pe-row">' + FILTERS.map((f, i) => '<button class="pe-f' + (i === fi ? " on" : "") + '" data-p="filter" data-i="' + i + '"><canvas width="64" height="64"></canvas><span>' + esc(f.n) + "</span></button>").join("") + "</div>";
          tray.querySelectorAll(".pe-f canvas").forEach((c, i) => {
            const t = c.getContext("2d"), s = Math.max(64 / PW, 64 / PH);
            t.drawImage(P, (64 - PW * s) / 2, (64 - PH * s) / 2, PW * s, PH * s); filterBands(t, 64, 64, FILTERS[i].f);
          });
        } else if (tab === "texte") {
          const cur = sel && sel.t === "text" ? sel : null;
          tray.innerHTML = '<div class="pe-text"><textarea id="pe-ti" rows="1" maxlength="120" placeholder="Écris ton texte…">' + esc(cur ? cur.text : "") + '</textarea><button class="pe-add" data-p="addtext">' + (cur ? "✓ OK" : "+ Ajouter") + "</button></div>" +
            '<div class="pe-row pe-small">' + TEXT_COLORS.map((c) => '<button class="pe-col' + (c === textColor ? " on" : "") + '" data-p="tcol" data-c="' + c + '" style="background:' + c + '"></button>').join("") +
            ["plain:Aa", "box:▣", "outline:Ⓐ"].map((s) => { const [k, l] = s.split(":"); return '<button class="pe-st' + (k === textStyle ? " on" : "") + '" data-p="tstyle" data-s="' + k + '">' + l + "</button>"; }).join("") + "</div>";
        } else if (tab === "stickers") {
          const cats = window.EMOJI_CATS || [];
          let h = '<div class="pe-seg"><button data-p="stab" data-i="0" class="' + (stkTab === 0 ? "on" : "") + '">Emojis</button><button data-p="stab" data-i="1" class="' + (stkTab === 1 ? "on" : "") + '">Autocollants</button><button data-p="stab" data-i="2" class="' + (stkTab === 2 ? "on" : "") + '">Déco</button></div><div class="pe-grid">';
          if (stkTab === 0) h += cats.map((c) => c.emojis.map((e) => '<button class="pe-e" data-p="emoji" data-e="' + esc(e) + '">' + e + "</button>").join("")).join("");
          else if (stkTab === 1) h += BADGES.map((b, i) => '<button class="pe-b" data-p="badge" data-i="' + i + '" style="background:' + b[1] + ";color:" + b[2] + '">' + esc(b[0]) + "</button>").join("");
          else h += DECOS.map((d, i) => '<button class="pe-d" data-p="deco" data-i="' + i + '"><canvas width="56" height="56"></canvas></button>').join("");
          tray.innerHTML = h + "</div>";
          if (stkTab === 2) tray.querySelectorAll(".pe-d canvas").forEach((c, i) => { const t = c.getContext("2d"); t.translate(28, 28); const [k, col] = DECOS[i].split(":"); drawDeco(t, k, col, 20); });
        } else {
          tray.innerHTML = '<div class="pe-hint">Touche un ruban pour le coller sur la photo.</div><div class="pe-row">' + TAPES.map((t, i) => '<button class="pe-t" data-p="tape" data-i="' + i + '"><canvas width="120" height="34"></canvas></button>').join("") + "</div>";
          tray.querySelectorAll(".pe-t canvas").forEach((c, i) => { const t = c.getContext("2d"); t.translate(60, 17); drawTape(t, TAPES[i], 100, 24); });
        }
      };
      const setTab = (t) => {
        tab = t; root.querySelectorAll(".pe-tabs button").forEach((b) => b.classList.toggle("on", b.dataset.t === t)); fillTray();
      };

      // ----- Ajouts -----
      const add = (it) => { it.x = .5; it.y = .45; it.rot = it.rot || 0; it.scl = it.scl || 1; items.push(it); sel = it; render(); };
      const addText = () => {
        const v = (tray.querySelector("#pe-ti").value || "").trim();
        if (!v) return;
        if (sel && sel.t === "text") { sel.text = v; sel.color = textColor; sel.style = textStyle; render(); }
        else add({ t: "text", text: v, color: textColor, style: textStyle, size: .075 });
        const ti = tray.querySelector("#pe-ti"); if (ti) ti.blur();
      };

      // ----- Toucher / glisser / pincer -----
      const ptrs = new Map(); let drag = null, pinch = null;
      const toLocal = (e) => { const r = cv.getBoundingClientRect(); return { x: (e.clientX - r.left) / r.width * PW, y: (e.clientY - r.top) / r.height * PH }; };
      const hit = (p) => {
        for (let i = items.length - 1; i >= 0; i--) {
          const it = items[i], L = layout(it, (it.scl || 1) * PW);
          const dx = p.x - it.x * PW, dy = p.y - it.y * PH, a = -(it.rot || 0);
          const lx = dx * Math.cos(a) - dy * Math.sin(a), ly = dx * Math.sin(a) + dy * Math.cos(a), pad = PW * .02;
          if (Math.abs(lx) <= L.w / 2 + pad && Math.abs(ly) <= L.h / 2 + pad) return it;
        }
        return null;
      };
      cv.addEventListener("pointerdown", (e) => {
        cv.setPointerCapture(e.pointerId);
        const p = toLocal(e); ptrs.set(e.pointerId, p);
        if (ptrs.size === 1) {
          const it = hit(p);
          if (it !== sel) { sel = it; render(); if (tab === "texte") fillTray(); }
          if (it) { drag = { it, ox: p.x - it.x * PW, oy: p.y - it.y * PH, moved: false }; items.splice(items.indexOf(it), 1); items.push(it); }
        } else if (ptrs.size === 2 && sel) {
          const [a, b] = [...ptrs.values()];
          pinch = { d: Math.hypot(a.x - b.x, a.y - b.y) || 1, ang: Math.atan2(b.y - a.y, b.x - a.x), scl: sel.scl || 1, rot: sel.rot || 0 };
          drag = null;
        }
      });
      cv.addEventListener("pointermove", (e) => {
        if (!ptrs.has(e.pointerId)) return;
        const p = toLocal(e); ptrs.set(e.pointerId, p);
        if (pinch && ptrs.size >= 2 && sel) {
          const [a, b] = [...ptrs.values()];
          sel.scl = Math.max(.15, Math.min(6, pinch.scl * Math.hypot(a.x - b.x, a.y - b.y) / pinch.d));
          sel.rot = pinch.rot + Math.atan2(b.y - a.y, b.x - a.x) - pinch.ang;
          render();
        } else if (drag) {
          drag.it.x = Math.max(0, Math.min(1, (p.x - drag.ox) / PW)); drag.it.y = Math.max(0, Math.min(1, (p.y - drag.oy) / PH)); drag.moved = true; render();
        }
      });
      const up = (e) => { ptrs.delete(e.pointerId); if (ptrs.size < 2) pinch = null; if (!ptrs.size) drag = null; };
      cv.addEventListener("pointerup", up); cv.addEventListener("pointercancel", up);
      cv.addEventListener("wheel", (e) => { if (sel) { e.preventDefault(); sel.scl = Math.max(.15, Math.min(6, (sel.scl || 1) * (e.deltaY < 0 ? 1.08 : .92))); render(); } }, { passive: false });

      // ----- Export -----
      const finish = async (once) => {
        const W = B.width, H = B.height;
        const out = document.createElement("canvas"); out.width = W; out.height = H;
        const c = out.getContext("2d"); c.drawImage(B, 0, 0); filterBands(c, W, H, FILTERS[fi].f);
        items.forEach((it) => drawItem(c, it, W, H));
        const blob = await new Promise((r) => out.toBlob(r, "image/jpeg", .93));
        done(blob ? { blob, w: W, h: H, once: !!once } : null);
      };
      const done = (v) => { root.remove(); resolve(v); };

      root.addEventListener("click", (ev) => {
        const t = ev.target.closest("[data-p]");
        if (!t) return;
        const a = t.dataset.p;
        if (a === "cancel") done(null);
        else if (a === "send") { t.disabled = true; finish(false); }
        else if (a === "once") { t.disabled = true; finish(true); }
        else if (a === "tab") setTab(t.dataset.t);
        else if (a === "filter") { fi = Number(t.dataset.i); applyFilterPreview(); render(); tray.querySelectorAll(".pe-f").forEach((b, i) => b.classList.toggle("on", i === fi)); }
        else if (a === "del") { if (sel) { items = items.filter((x) => x !== sel); sel = null; render(); if (tab === "texte") fillTray(); } }
        else if (a === "addtext") addText();
        else if (a === "tcol") { textColor = t.dataset.c; if (sel && sel.t === "text") { sel.color = textColor; render(); } fillTray(); }
        else if (a === "tstyle") { textStyle = t.dataset.s; if (sel && sel.t === "text") { sel.style = textStyle; render(); } fillTray(); }
        else if (a === "stab") { stkTab = Number(t.dataset.i); fillTray(); }
        else if (a === "emoji") add({ t: "emoji", ch: t.dataset.e, size: .12 });
        else if (a === "badge") { const b = BADGES[Number(t.dataset.i)]; add({ t: "badge", text: b[0], bg: b[1], fg: b[2], size: .05, rot: (Math.random() - .5) * .2 }); }
        else if (a === "deco") add({ t: "deco", d: DECOS[Number(t.dataset.i)], size: .12, rot: (Math.random() - .5) * .5 });
        else if (a === "tape") add({ t: "tape", tape: TAPES[Number(t.dataset.i)], size: .42, rot: (Math.random() - .5) * .5 });
      });

      applyFilterPreview(); render(); fillTray();
      root._test = { items: () => items, setFilter: (i) => { fi = i; applyFilterPreview(); render(); } };
    });
  }

  window.TMM_EDIT = { open, FILTERS };
})();
