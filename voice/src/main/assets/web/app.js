/* Take my voice — interface (WebView). Le travail lourd est fait par l'appli Android + Kaggle. */
"use strict";

const T = window.TMV || null;          // pont Android
const $ = (s, r = document) => r.querySelector(s);
const vue = $("#vue");
let S = {};                            // état complet reçu d'Android
let pile = [{ v: "accueil" }];         // navigation
let renduEnAttente = false;
const brouillons = {};                 // textes en cours de saisie
const ui = { onglet: {}, hauteur: {}, qualite: {}, phraseIdx: Math.floor(Math.random() * PHRASES.length), ameliorer: {} };

const ICONES = {
  play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>',
  pause: '<svg viewBox="0 0 24 24"><path d="M7 5h4v14H7zM13 5h4v14h-4z"/></svg>',
  poubelle: '<svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>',
  partager: '<svg viewBox="0 0 24 24"><path d="M12 3v13M7 8l5-5 5 5M5 14v6h14v-6"/></svg>',
  dl: '<svg viewBox="0 0 24 24"><path d="M12 3v13M7 11l5 5 5-5M5 20h14"/></svg>',
  plus: '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>',
  dossier: '<svg viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>',
  refaire: '<svg viewBox="0 0 24 24"><path d="M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5"/></svg>',
  suivant: '<svg viewBox="0 0 24 24"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
  yt: '<svg viewBox="0 0 24 24"><rect x="2.5" y="5.5" width="19" height="13" rx="4"/><path d="M10 9.2v5.6l4.8-2.8z" fill="currentColor"/></svg>',
  points: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="19" cy="12" r="1.3"/></svg>',
};

// ---------------------------------------------------------------- outils
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const minutes = (ms) => {
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, "0")}`;
};
const depuis = (t) => {
  if (!t) return "";
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")}`;
};
const quand = (t) => new Date(t).toLocaleString("fr-FR", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
const toast = (m) => (T ? T.toast(m) : alert(m));
const appel = (nom, ...args) => {
  if (!T) return {};
  try { const r = T[nom](...args); return typeof r === "string" && r.startsWith("{") ? JSON.parse(r) : r; }
  catch (e) { toast("Erreur : " + e.message); return { ok: false }; }
};
const voixDe = (id) => (S.voix || []).find((v) => v.id === id);
const tachesDe = (id) => (S.taches || []).filter((t) => t.voix === id).sort((a, b) => b.cree - a.cree);
const actif = (t) => ["attente", "envoi", "lance"].includes(t.etat);

function versBase64(blob) {
  return new Promise((ok, ko) => {
    const r = new FileReader();
    r.onload = () => ok(String(r.result).split(",")[1] || "");
    r.onerror = () => ko(r.error);
    r.readAsDataURL(blob);
  });
}
function dureeFichier(file) {
  return new Promise((ok) => {
    const a = new Audio();
    const fin = (v) => { URL.revokeObjectURL(a.src); ok(v); };
    a.preload = "metadata";
    a.onloadedmetadata = () => fin(isFinite(a.duration) ? Math.round(a.duration * 1000) : 0);
    a.onerror = () => fin(0);
    a.src = URL.createObjectURL(file);
    setTimeout(() => fin(0), 6000);
  });
}

// ---------------------------------------------------------------- enregistrement
const Rec = {
  actif: false, media: null, flux: null, morceaux: [], debut: 0, ctx: null, anim: 0, minuteur: 0, maxMs: 0,
  async demarrer({ debit = 96000, maxMs = 10 * 60 * 1000, canvas, chrono, fini }) {
    try {
      this.flux = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
      });
    } catch (e) { toast("Micro indisponible. Autorisez le micro dans les réglages du téléphone."); return false; }
    const type = ["audio/webm;codecs=opus", "audio/ogg;codecs=opus", "audio/webm", "audio/mp4"].find((t) => window.MediaRecorder && MediaRecorder.isTypeSupported(t)) || "";
    this.media = new MediaRecorder(this.flux, type ? { mimeType: type, audioBitsPerSecond: debit } : {});
    this.morceaux = [];
    this.media.ondataavailable = (e) => e.data.size && this.morceaux.push(e.data);
    this.media.onstop = () => {
      const ms = Date.now() - this.debut;
      const mime = this.media.mimeType || type || "audio/webm";
      const ext = mime.includes("ogg") ? "ogg" : mime.includes("mp4") ? "m4a" : "webm";
      const blob = new Blob(this.morceaux, { type: mime });
      this.nettoyer();
      fini && fini({ blob, ms, ext });
    };
    this.media.start(1000);
    this.debut = Date.now();
    this.actif = true;
    this.maxMs = maxMs;
    // niveau sonore
    try {
      this.ctx = new (window.AudioContext || window.webkitAudioContext)();
      const an = this.ctx.createAnalyser();
      an.fftSize = 512;
      this.ctx.createMediaStreamSource(this.flux).connect(an);
      const data = new Uint8Array(an.fftSize);
      const hist = [];
      const dessiner = () => {
        if (!this.actif) return;
        an.getByteTimeDomainData(data);
        let m = 0;
        for (const x of data) m = Math.max(m, Math.abs(x - 128));
        hist.push(m / 128);
        if (canvas && canvas.isConnected) {
          const w = (canvas.width = canvas.clientWidth * 2), h = (canvas.height = canvas.clientHeight * 2);
          const g = canvas.getContext("2d");
          const n = Math.floor(w / 10);
          while (hist.length > n) hist.shift();
          const grad = g.createLinearGradient(0, 0, w, 0);
          grad.addColorStop(0, "#ff7ac8"); grad.addColorStop(1, "#8b5cff");
          g.fillStyle = grad;
          hist.forEach((v, i) => {
            const bh = Math.max(4, Math.min(1, v * 2.2) * h);
            g.beginPath(); g.roundRect ? g.roundRect(i * 10, (h - bh) / 2, 6, bh, 3) : g.rect(i * 10, (h - bh) / 2, 6, bh); g.fill();
          });
        }
        this.anim = requestAnimationFrame(dessiner);
      };
      dessiner();
    } catch (_) {}
    this.minuteur = setInterval(() => {
      const ms = Date.now() - this.debut;
      if (chrono && chrono.isConnected) chrono.textContent = "● " + minutes(ms);
      if (ms >= this.maxMs) this.arreter();
    }, 250);
    return true;
  },
  arreter() { if (this.media && this.media.state !== "inactive") this.media.stop(); },
  nettoyer() {
    this.actif = false;
    clearInterval(this.minuteur);
    cancelAnimationFrame(this.anim);
    try { this.flux && this.flux.getTracks().forEach((t) => t.stop()); } catch (_) {}
    try { this.ctx && this.ctx.close(); } catch (_) {}
    this.flux = null; this.ctx = null;
    if (renduEnAttente) rendu();
  },
};

// ---------------------------------------------------------------- rendu
function occupe() {
  if (Rec.actif || enAttenteConv) return true;
  if (pile[pile.length - 1].v === "youtube") return true;
  if ([...document.querySelectorAll("audio")].some((a) => !a.paused && !a.ended)) return true;
  const f = document.activeElement;
  return f && (f.tagName === "TEXTAREA" || f.tagName === "INPUT") && f.type !== "checkbox" && f.type !== "range";
}

function charger() {
  if (T) { try { S = JSON.parse(T.etat()); } catch (_) {} }
  else S = S.voix ? S : { voix: [], taches: [], kaggle: { configure: false }, version: "démo" };
}

window.tmvMaj = function () {
  charger();
  if (occupe()) { renduEnAttente = true; majChronos(); return; }
  rendu();
};

function rendu() {
  renduEnAttente = false;
  const haut = window.scrollY;
  const courant = pile[pile.length - 1];
  $("#retour").hidden = pile.length < 2;
  $("#reglages").hidden = courant.v !== "accueil";
  const vues = { accueil: vueAccueil, voix: vueVoix, reglages: vueReglages, youtube: vueYoutube };
  vue.innerHTML = (vues[courant.v] || vueAccueil)(courant);
  if (courant.anime) { vue.classList.remove("vue-entree"); void vue.offsetWidth; vue.classList.add("vue-entree"); courant.anime = false; }
  window.scrollTo(0, courant.v === pile[pile.length - 1].v ? haut : 0);
  apresRendu(courant);
}

function aller(v, extra = {}) { pile.push({ v, anime: true, ...extra }); window.scrollTo(0, 0); rendu(); }
window.tmvRetour = function () {
  if (!$("#feuille").hidden) { fermerFeuille(); return true; }
  if (Rec.actif) { Rec.arreter(); return true; }
  if (pile[pile.length - 1].v === "youtube" && YTV.extraction) { toast("Extraction en cours, patientez ou annulez."); return true; }
  if (pile[pile.length - 1].v === "youtube" && YTV.choisie) { YTV.fermerChoix(); return true; }
  if (pile.length > 1) { pile.pop(); pile[pile.length - 1].anime = true; rendu(); return true; }
  return false;
};
$("#retour").onclick = () => window.tmvRetour();
$("#reglages").onclick = () => aller("reglages");

// rafraîchit les « il y a … » sans tout redessiner
function majChronos() {
  document.querySelectorAll("[data-depuis]").forEach((e) => { e.textContent = depuis(+e.dataset.depuis); });
}
setInterval(majChronos, 1000);
setInterval(() => { if ((S.taches || []).some(actif) && T) T.actualiser(); }, 60000);
document.addEventListener("pause", () => renduEnAttente && !occupe() && rendu(), true);
document.addEventListener("ended", () => renduEnAttente && !occupe() && rendu(), true);
document.addEventListener("focusout", () => setTimeout(() => renduEnAttente && !occupe() && rendu(), 300), true);

// ---------------------------------------------------------------- accueil
function etatVoix(v) {
  const ts = tachesDe(v.id);
  const train = ts.find((t) => t.type === "train" && actif(t));
  if (train) return { cls: "cours", txt: "Entraînement…" };
  if (v.entrainee) {
    const conv = ts.find((t) => t.type === "convert" && actif(t));
    return conv ? { cls: "cours", txt: "Transformation…" } : { cls: "prete", txt: "Prête" };
  }
  const err = ts.find((t) => t.type === "train" && t.etat === "erreur");
  if (err) return { cls: "erreur", txt: "À réessayer" };
  return { cls: "", txt: v.totalMs ? `${minutes(v.totalMs)} enregistrées` : "À enregistrer" };
}

function vueAccueil() {
  $("#titre").textContent = "Take my voice";
  let h = "";
  if (!S.kaggle || !S.kaggle.configure) {
    h += `<div class="carte" style="border-color:#8b5cff66">
      <h2>Première étape : connecter Kaggle</h2>
      <p>Le clonage de voix tourne gratuitement sur les GPU de Kaggle. Il faut un compte gratuit, une seule fois.</p>
      <button class="btn principal plein" data-act="reglages">Connecter mon compte ${ICONES.suivant}</button></div>`;
  }
  const voix = (S.voix || []).slice().sort((a, b) => b.cree - a.cree);
  if (!voix.length) {
    h += `<div class="vide">
      <svg class="onde" viewBox="0 0 120 60">${[8, 22, 40, 26, 52, 34, 46, 20, 30, 12].map((v, i) =>
        `<rect x="${i * 12 + 2}" y="${30 - v / 2}" width="7" height="${v}" rx="3.5" fill="url(#g)"/>`).join("")}
        <defs><linearGradient id="g"><stop offset="0" stop-color="#ff7ac8"/><stop offset="1" stop-color="#8b5cff"/></linearGradient></defs></svg>
      <h2 style="margin:0 0 6px">Créez votre première voix</h2>
      <p class="petit" style="font-size:14.5px;color:var(--doux)">Enregistrez la personne (avec son accord), l'appli apprend sa voix, puis vous parlez avec sa voix.</p></div>`;
  } else {
    h += `<div class="titre-section">Mes voix</div>`;
    h += voix.map((v) => {
      const e = etatVoix(v);
      return `<button class="voix" data-act="ouvrir" data-id="${v.id}">
        <div class="avatar ${v.genre}">${esc((v.nom || "?").trim().charAt(0).toUpperCase())}</div>
        <div class="espace"><div class="nom">${esc(v.nom)}</div>
        <div class="info"><span class="pastille ${e.cls}"><span class="point"></span>${e.txt}</span></div></div>
        <svg viewBox="0 0 24 24" style="width:20px;height:20px;fill:none;stroke:var(--faible);stroke-width:2"><path d="M9 5l7 7-7 7"/></svg>
      </button>`;
    }).join("");
  }
  h += `<button class="btn principal plein" style="margin-top:10px" data-act="nouvelle">${ICONES.plus} Nouvelle voix</button>`;
  h += `<p class="petit" style="text-align:center;margin-top:18px">N'imitez jamais quelqu'un sans son accord.<br>Une fausse voix ne doit pas servir à tromper.</p>`;
  return h;
}

function feuilleNouvelle() {
  ouvrirFeuille(`<h2>Nouvelle voix</h2>
    <label class="etiquette">Nom de la personne</label>
    <input class="champ" id="nv-nom" maxlength="40" placeholder="Ex. : Paul">
    <label class="etiquette">Sa voix est plutôt…</label>
    <div class="segments" id="nv-genre"><button class="actif" data-g="homme">Masculine</button><button data-g="femme">Féminine</button></div>
    <button class="btn principal plein" id="nv-ok">Créer</button>`);
  let g = "homme";
  $("#nv-genre").onclick = (e) => {
    const b = e.target.closest("button"); if (!b) return;
    g = b.dataset.g;
    $("#nv-genre").querySelectorAll("button").forEach((x) => x.classList.toggle("actif", x === b));
  };
  $("#nv-ok").onclick = () => {
    const nom = $("#nv-nom").value.trim();
    if (!nom) { $("#nv-nom").focus(); return; }
    const r = appel("creerVoix", nom, g);
    fermerFeuille();
    charger();
    if (r && r.id) aller("voix", { id: r.id });
  };
  setTimeout(() => $("#nv-nom").focus(), 250);
}

// ---------------------------------------------------------------- une voix
function carteTache(t, v) {
  const etiquettes = { attente: "En attente", envoi: "Envoi", lance: "Sur Kaggle", fini: "Terminé", erreur: "Échec" };
  const cls = t.etat === "fini" ? "prete" : t.etat === "erreur" ? "erreur" : "cours";
  const quoi = t.type === "train" ? "Entraînement de la voix"
    : t.params && t.params.mode === "texte" ? "« " + esc((t.texte || "").slice(0, 60)) + ((t.texte || "").length > 60 ? "…" : "") + " »"
    : "Enregistrement transformé";
  let h = `<div class="tache"><div class="haut"><div class="quoi">${quoi}</div>
    <span class="pastille ${cls}"><span class="point"></span>${etiquettes[t.etat] || t.etat}</span></div>`;
  if (actif(t)) {
    h += `<div class="etape">${esc(t.etape || "")}</div>`;
    const debut = t.lance || t.cree;
    h += `<div class="petit">Depuis <span data-depuis="${debut}">${depuis(debut)}</span>`;
    h += t.type === "train" ? " · compte en général 40 min à 1 h 30" : " · compte en général 5 à 8 min";
    h += `</div>`;
    const p = t.etat === "envoi" && t.progression ? t.progression : 0;
    h += `<div class="barre-prog ${p ? "" : "indet"}"><div style="width:${p}%"></div></div>`;
    if (t.type === "train" && t.etat === "lance") h += `<p class="petit" style="margin:10px 0 0">Vous pouvez fermer l'appli : une notification vous préviendra.</p>`;
  } else if (t.etat === "erreur") {
    h += `<div class="msg">${esc(t.message || "Erreur")}</div>
      <div class="actions"><button class="btn mini" data-act="reessayer" data-id="${t.id}">${ICONES.refaire} Réessayer</button>
      <button class="btn mini danger" data-act="supprTache" data-id="${t.id}">${ICONES.poubelle} Effacer</button></div>`;
  } else if (t.etat === "fini" && t.type === "convert") {
    h += `<div class="petit">${quand(t.fin || t.cree)}</div>`;
    if (t.resultat) h += `<audio controls preload="metadata" src="${esc(t.resultat)}"></audio>`;
    if (t.entreeUrl) h += `<div class="originale">Original :<audio controls preload="none" src="${esc(t.entreeUrl)}"></audio></div>`;
    h += `<div class="actions">
      <button class="btn mini" data-act="partager" data-url="${esc(t.resultat)}" data-nom="${esc(v.nom)}">${ICONES.partager} Envoyer</button>
      <button class="btn mini" data-act="sauver" data-url="${esc(t.resultat)}" data-nom="${esc(v.nom)}">${ICONES.dl} Enregistrer</button>
      <button class="btn mini danger" data-act="supprTache" data-id="${t.id}">${ICONES.poubelle}</button></div>`;
  }
  return h + `</div>`;
}

function studio(v) {
  const prises = v.enregistrements || [];
  const total = v.totalMs || 0;
  const objectif = 10 * 60 * 1000;
  const pct = Math.min(100, (total / objectif) * 100);
  const conseil = total < 60000 ? "Il faut au moins 1 minute. 10 minutes donnent un bien meilleur résultat."
    : total < 5 * 60000 ? "Ça marche déjà, mais plus vous enregistrez (5 à 15 min), plus l'imitation sera fidèle."
    : total < objectif ? "Très bien ! Encore un peu pour atteindre 10 minutes." : "Parfait, vous avez assez d'audio. 🎉";
  const ph = PHRASES[ui.phraseIdx % PHRASES.length];
  let h = `<div class="carte">
    <h2>Enregistrer ${esc(v.nom)}</h2>
    <p>Faites lire ces phrases à ${esc(v.nom)} dans une pièce calme, téléphone à 20 cm. Ou importez des messages vocaux où l'on n'entend que sa voix.</p>
    <div class="phrase" id="phrase">${esc(ph)}</div>
    <div class="ligne" style="margin-bottom:6px"><button class="btn mini" data-act="phraseSuivante">Autre phrase ${ICONES.suivant}</button></div>
    <canvas class="niveau" id="niveau"></canvas>
    <div class="studio">
      <button class="rec" id="rec" aria-label="Enregistrer"><div class="coeur"></div></button>
      <div class="chrono" id="chrono">Touchez pour enregistrer</div>
    </div>
    <div class="ligne" style="margin:14px 0 6px"><span class="etiquette">Total : ${minutes(total)}</span><span class="espace"></span><span class="petit">objectif 10 min</span></div>
    <div class="jauge ${pct >= 100 ? "verte" : ""}"><div style="width:${Math.max(2, pct)}%"></div></div>
    <p class="petit" style="margin:8px 0 12px">${conseil}</p>
    <button class="btn plein" data-act="youtube" style="margin-bottom:8px">${ICONES.yt} Prendre sa voix sur YouTube</button>
    <button class="btn plein" data-act="importer">${ICONES.dossier} Importer des fichiers audio</button>
  </div>`;
  if (prises.length) {
    h += `<div class="carte"><h2>Enregistrements (${prises.length})</h2>`;
    h += prises.map((p, i) => `<div class="prise"><span class="num">${i + 1}</span>
        <button class="lire" data-act="lirePrise" data-url="${esc(p.url)}">${ICONES.play}</button>
        <span class="espace">${p.ms ? minutes(p.ms) : "Durée inconnue"}${p.nom.includes("_yt") ? ' <span class="pastille">YouTube</span>' : ""}</span>
        <button class="suppr" data-act="supprPrise" data-nom="${esc(p.nom)}">${ICONES.poubelle}</button></div>`).join("");
    h += `</div>`;
  }
  const q = ui.qualite[v.id] || 300;
  const opts = [
    [150, "Rapide", "≈ 40 min sur Kaggle"],
    [300, "Équilibré (conseillé)", "≈ 1 h, bon résultat"],
    [600, "Maximum", "≈ 1 h 30 à 2 h, utilise plus de quota"],
  ];
  h += `<div class="carte"><h2>${v.entrainee ? "Réentraîner la voix" : "Entraîner la voix"}</h2>
    <p>Kaggle apprend la voix sur son GPU gratuit. Vous pouvez fermer l'appli pendant ce temps.</p>
    <div class="choix" id="qualite">${opts.map(([n, t, d]) => `<button class="${q === n ? "actif" : ""}" data-q="${n}"><b>${t}</b><span>${d}</span></button>`).join("")}</div>
    <label class="case"><input type="checkbox" id="accord" ${v.accord ? "checked" : ""}>
      <span>${esc(v.nom)} est d'accord pour que sa voix soit imitée, et je ne l'utiliserai pas pour tromper quelqu'un.</span></label>
    <button class="btn principal plein" data-act="entrainer" ${total < 30000 || !v.accord ? "disabled" : ""}>Lancer l'entraînement</button>
    ${total < 30000 ? `<p class="petit" style="margin-top:8px">Enregistrez au moins 1 minute pour commencer.</p>` : ""}
  </div>`;
  return h;
}

function panneauTransformer(v) {
  const onglet = ui.onglet[v.id] || "parler";
  const haut = ui.hauteur[v.id] ?? 0;
  let h = `<div class="carte"><h2>Parler avec la voix de ${esc(v.nom)}</h2>
    <div class="segments" id="onglets"><button class="${onglet === "parler" ? "actif" : ""}" data-o="parler">🎙️ Ma voix</button>
    <button class="${onglet === "texte" ? "actif" : ""}" data-o="texte">✍️ Un texte</button></div>`;
  if (onglet === "parler") {
    h += `<p>Parlez normalement : vos mots et votre intonation sont gardés, seul le timbre change. Imitez son rythme pour plus de réalisme.</p>
      <canvas class="niveau" id="niveau"></canvas>
      <div class="studio"><button class="rec" id="rec-conv" aria-label="Enregistrer"><div class="coeur"></div></button>
      <div class="chrono" id="chrono">Touchez pour parler (3 min max)</div></div>
      <div id="apercu-conv"></div>
      <button class="btn plein" style="margin-top:10px" data-act="importerConv">${ICONES.dossier} Ou choisir un fichier audio</button>`;
  } else {
    h += `<p>Écrivez ce que ${esc(v.nom)} doit dire. Une voix de synthèse le lit, puis elle est transformée avec sa voix.</p>
      <textarea class="champ" id="texte" maxlength="2000" placeholder="Bonjour, c'est moi !">${esc(brouillons[v.id] || "")}</textarea>
      <button class="btn principal plein" data-act="direTexte">Faire dire ce texte</button>`;
  }
  h += `<div style="margin-top:16px"><label class="etiquette">Hauteur de la voix : <b id="haut-val">${haut > 0 ? "+" : ""}${haut}</b></label>
    <input type="range" id="hauteur" min="-12" max="12" step="1" value="${haut}">
    <p class="petit" style="margin:2px 0 0">Homme qui imite une femme : +8 à +12 · Femme qui imite un homme : −8 à −12 · Même genre : 0.</p></div>`;
  return h + `</div>`;
}

function vueVoix({ id }) {
  const v = voixDe(id);
  if (!v) { pile = [{ v: "accueil" }]; return vueAccueil(); }
  $("#titre").textContent = v.nom;
  const ts = tachesDe(id);
  const train = ts.find((t) => t.type === "train" && (actif(t) || t.etat === "erreur"));
  const e = etatVoix(v);
  let h = `<div class="carte ligne">
    <div class="avatar ${v.genre}">${esc(v.nom.charAt(0).toUpperCase())}</div>
    <div class="espace"><div style="font-weight:700;font-size:17px">${esc(v.nom)}</div>
    <span class="pastille ${e.cls}"><span class="point"></span>${e.txt}</span></div>
    <button class="icone" data-act="menuVoix">${ICONES.points}</button></div>`;

  if (train && actif(train)) {
    h += carteTache(train, v);
    h += `<div class="carte"><h2>Que se passe-t-il ?</h2><ol class="etapes">
      <li><b>Envoi</b> des enregistrements vers votre espace Kaggle privé.</li>
      <li><b>Installation</b> d'Applio sur un GPU gratuit (5 à 10 min).</li>
      <li><b>Apprentissage</b> de la voix, puis création d'un aperçu.</li></ol></div>`;
    return h;
  }
  if (train && train.etat === "erreur" && !v.entrainee) h += carteTache(train, v);

  if (v.entrainee) {
    if (v.apercu) h += `<div class="carte"><h2>Aperçu de la voix</h2><audio controls preload="metadata" src="${esc(v.apercu)}" style="width:100%"></audio></div>`;
    h += panneauTransformer(v);
    const convs = ts.filter((t) => t.type === "convert");
    if (convs.length) h += `<div class="titre-section">Mes audios</div>` + convs.map((t) => carteTache(t, v)).join("");
    h += `<button class="btn plein" style="margin-top:14px" data-act="ameliorer">${ui.ameliorer[id] ? "Masquer" : "Améliorer la voix (plus d'enregistrements)"}</button>`;
    if (ui.ameliorer[id]) h += `<div style="margin-top:14px">${studio(v)}</div>`;
  } else {
    h += studio(v);
  }
  return h;
}

// ---------------------------------------------------------------- réglages
function vueReglages() {
  $("#titre").textContent = "Réglages";
  const k = S.kaggle || {};
  const q = k.quota || {};
  let h = `<div class="carte"><h2>Compte Kaggle ${k.configure ? '<span class="pastille prete" style="margin-left:6px"><span class="point"></span>Connecté</span>' : ""}</h2>`;
  if (k.configure) {
    h += `<p>Utilisateur : <b style="color:var(--texte)">${esc(k.user)}</b> · clé ${esc(k.cleMasquee)}</p>`;
    if (q.total_h) {
      const pct = Math.min(100, (q.utilise_h / q.total_h) * 100);
      h += `<div class="ligne" style="margin-bottom:6px"><span class="etiquette">GPU gratuit cette semaine</span><span class="espace"></span>
        <span class="petit">${q.utilise_h.toFixed(1)} h / ${Math.round(q.total_h)} h</span></div>
        <div class="jauge"><div style="width:${Math.max(2, pct)}%"></div></div>
        <p class="petit" style="margin:8px 0 12px">Un entraînement utilise environ 1 h, une transformation environ 6 min.</p>`;
    }
    h += `<div class="ligne"><button class="btn mini" data-act="tester">Tester la connexion</button>
      <button class="btn mini danger" data-act="deconnecter">Changer de compte</button></div>
      <div id="test-res" class="petit" style="margin-top:10px"></div></div>`;
  } else {
    h += `<p>À faire une seule fois (5 minutes), tout est gratuit :</p>
      <ol class="etapes">
        <li>Créez un compte sur <a class="lien" data-lien="https://www.kaggle.com/account/login?phase=startRegisterTab">kaggle.com</a> (avec Google, c'est le plus rapide).</li>
        <li>Ouvrez <a class="lien" data-lien="https://www.kaggle.com/settings">Settings</a> et faites <b>Phone verification</b> : sans ça, Kaggle refuse le GPU.</li>
        <li>Sur la même page, partie <b>API</b>, touchez <b>Create New Token</b> (ou « Generate New Token ») et copiez la clé affichée.</li>
        <li>Collez ci-dessous votre <b>nom d'utilisateur Kaggle</b> (visible dans l'adresse de votre profil) et la clé.</li>
      </ol>
      <label class="etiquette">Nom d'utilisateur Kaggle</label>
      <input class="champ" id="k-user" autocapitalize="off" autocomplete="off" spellcheck="false" placeholder="ex. : stephane237">
      <label class="etiquette">Clé API (ou contenu de kaggle.json)</label>
      <input class="champ" id="k-cle" autocapitalize="off" autocomplete="off" spellcheck="false" placeholder="KGAT_… ou 32 caractères">
      <button class="btn principal plein" data-act="connecter">Connecter</button>
      <div id="test-res" class="petit" style="margin-top:10px"></div></div>`;
  }
  h += `<div class="carte"><h2>Application</h2>
    <div class="ligne"><span>Version ${esc(S.version || "")}</span><span class="espace"></span>
    <button class="btn mini" data-act="maj" id="btn-maj">Vérifier les mises à jour</button></div>
    <div id="maj-res" class="petit" style="margin-top:10px"></div></div>
    <div class="carte"><h2>Bon usage</h2>
    <p>Take my voice utilise Applio (logiciel libre) sur votre propre compte Kaggle. Vos enregistrements restent privés dans votre espace Kaggle.</p>
    <p style="margin:0">Utilisez uniquement des voix de personnes d'accord. Imiter quelqu'un pour tromper, escroquer ou nuire est interdit et peut être puni par la loi.</p></div>`;
  return h;
}

// ---------------------------------------------------------------- actions
function apresRendu(c) {
  if (c.v === "voix") {
    const v = voixDe(c.id); if (!v) return;
    const rec = $("#rec");
    if (rec) rec.onclick = () => basculerRec(v, rec);
    const recConv = $("#rec-conv");
    if (recConv) recConv.onclick = () => basculerRecConv(v, recConv);
    const acc = $("#accord");
    if (acc) acc.onchange = () => { appel("modifierVoix", v.id, JSON.stringify({ accord: acc.checked })); };
    const qual = $("#qualite");
    if (qual) qual.onclick = (e) => { const b = e.target.closest("button"); if (!b) return; ui.qualite[v.id] = +b.dataset.q; rendu(); };
    const ong = $("#onglets");
    if (ong) ong.onclick = (e) => { const b = e.target.closest("button"); if (!b) return; ui.onglet[v.id] = b.dataset.o; rendu(); };
    const tx = $("#texte");
    if (tx) tx.oninput = () => { brouillons[v.id] = tx.value; };
    const hr = $("#hauteur");
    if (hr) hr.oninput = () => { ui.hauteur[v.id] = +hr.value; $("#haut-val").textContent = (hr.value > 0 ? "+" : "") + hr.value; };
  }
}

vue.addEventListener("click", (e) => {
  const lien = e.target.closest("[data-lien]");
  if (lien) { e.preventDefault(); T ? T.ouvrir(lien.dataset.lien) : window.open(lien.dataset.lien); return; }
  const b = e.target.closest("[data-act]");
  if (!b) return;
  const c = pile[pile.length - 1];
  const v = c.id ? voixDe(c.id) : null;
  const d = b.dataset;
  switch (d.act) {
    case "reglages": aller("reglages"); break;
    case "nouvelle": feuilleNouvelle(); break;
    case "ouvrir": aller("voix", { id: d.id }); break;
    case "phraseSuivante":
      ui.phraseIdx++;
      $("#phrase").textContent = PHRASES[ui.phraseIdx % PHRASES.length];
      break;
    case "youtube": aller("youtube", { id: v.id }); break;
    case "importer": importer((f) => ajouterFichiers(v, f)); break;
    case "importerConv": importer((f) => f[0] && transformerFichier(v, f[0]), false); break;
    case "lirePrise": lirePrise(b, d.url); break;
    case "supprPrise":
      if (confirm("Supprimer cet enregistrement ?")) appel("supprimerEnregistrement", v.id, d.nom);
      break;
    case "entrainer": {
      const r = appel("entrainer", v.id, ui.qualite[v.id] || 300);
      if (r && r.ok === false) toast(r.erreur); else { ui.ameliorer[v.id] = false; window.scrollTo(0, 0); }
      break;
    }
    case "direTexte": {
      const t = ($("#texte") || {}).value || "";
      if (!t.trim()) { toast("Écrivez d'abord un texte."); break; }
      const r = appel("direTexte", v.id, t, ui.hauteur[v.id] ?? 0, 0);
      if (r && r.ok === false) toast(r.erreur); else { brouillons[v.id] = ""; document.activeElement && document.activeElement.blur(); toast("C'est parti ! Comptez 5 à 8 minutes."); }
      break;
    }
    case "reessayer": appel("reessayer", d.id); break;
    case "supprTache": if (confirm("Effacer cet élément ?")) appel("supprimerTache", d.id); break;
    case "partager": appel("partager", d.url, "Voix de " + d.nom); break;
    case "sauver": appel("sauvegarder", d.url, "Voix de " + d.nom); break;
    case "ameliorer": ui.ameliorer[v.id] = !ui.ameliorer[v.id]; rendu(); break;
    case "menuVoix": menuVoix(v); break;
    case "connecter": {
      const u = $("#k-user").value.trim(), k = $("#k-cle").value.trim();
      if (!k || (!u && !k.startsWith("{"))) { toast("Remplissez le nom d'utilisateur et la clé."); break; }
      $("#test-res").textContent = "Vérification…";
      appel("enregistrerKaggle", u, k);
      break;
    }
    case "tester": $("#test-res").textContent = "Vérification…"; appel("testerKaggle"); break;
    case "deconnecter": if (confirm("Déconnecter ce compte Kaggle ?")) appel("deconnecterKaggle"); break;
    case "maj": $("#maj-res").textContent = "Recherche…"; appel("verifierMaj"); break;
    case "installerMaj": $("#maj-res").textContent = "Téléchargement… 0 %"; appel("installerMaj"); break;
  }
});

let audioPrise = null;
function lirePrise(btn, url) {
  if (audioPrise && !audioPrise.paused) {
    audioPrise.pause();
    const ancien = audioPrise._btn; ancien && (ancien.innerHTML = ICONES.play);
    if (audioPrise._url === url) { audioPrise = null; return; }
  }
  audioPrise = new Audio(url);
  audioPrise._url = url; audioPrise._btn = btn;
  btn.innerHTML = ICONES.pause;
  audioPrise.onended = audioPrise.onpause = () => { btn.innerHTML = ICONES.play; if (renduEnAttente && !occupe()) rendu(); };
  audioPrise.play().catch(() => toast("Lecture impossible"));
}

function menuVoix(v) {
  ouvrirFeuille(`<h2>${esc(v.nom)}</h2>
    <label class="etiquette">Renommer</label><input class="champ" id="mv-nom" value="${esc(v.nom)}" maxlength="40">
    <button class="btn plein" id="mv-ok" style="margin-bottom:10px">Enregistrer le nom</button>
    <button class="btn plein danger" id="mv-suppr">${ICONES.poubelle} Supprimer cette voix</button>
    <p class="petit" style="margin-top:10px">La suppression efface aussi les enregistrements et le modèle sur Kaggle.</p>`);
  $("#mv-ok").onclick = () => { appel("modifierVoix", v.id, JSON.stringify({ nom: $("#mv-nom").value })); fermerFeuille(); };
  $("#mv-suppr").onclick = () => {
    if (!confirm(`Supprimer définitivement la voix « ${v.nom} » ?`)) return;
    fermerFeuille();
    appel("supprimerVoix", v.id);
    pile = [{ v: "accueil", anime: true }];
    charger(); rendu();
  };
}

// --- enregistrement pour l'entraînement
async function basculerRec(v, btn) {
  if (Rec.actif) { Rec.arreter(); return; }
  const ok = await Rec.demarrer({
    debit: 96000, maxMs: 10 * 60 * 1000, canvas: $("#niveau"), chrono: $("#chrono"),
    fini: async ({ blob, ms, ext }) => {
      btn.classList.remove("actif");
      if (ms < 2500) { toast("Trop court, gardez le bouton actif plus longtemps."); renduEnAttente = true; rendu(); return; }
      const b64 = await versBase64(blob);
      const r = appel("ajouterEnregistrement", v.id, b64, ext, ms);
      if (r && r.ok === false) toast(r.erreur);
      ui.phraseIdx++;
      charger(); rendu();
    },
  });
  if (ok) btn.classList.add("actif");
}

// --- enregistrement à transformer
let enAttenteConv = null;
async function basculerRecConv(v, btn) {
  if (Rec.actif) { Rec.arreter(); return; }
  const ok = await Rec.demarrer({
    debit: 40000, maxMs: 3 * 60 * 1000, canvas: $("#niveau"), chrono: $("#chrono"),
    fini: ({ blob, ms, ext }) => {
      btn.classList.remove("actif");
      if (ms < 1000) { toast("Trop court."); return; }
      enAttenteConv = { blob, ext };
      const url = URL.createObjectURL(blob);
      const zone = $("#apercu-conv");
      if (!zone) return;
      $("#chrono").textContent = minutes(ms) + " enregistrées";
      zone.innerHTML = `<audio controls src="${url}" style="width:100%;margin:6px 0 10px"></audio>
        <div class="ligne"><button class="btn" id="conv-refaire">${ICONES.refaire} Refaire</button>
        <button class="btn principal espace" id="conv-go">Transformer avec sa voix</button></div>`;
      $("#conv-refaire").onclick = () => { enAttenteConv = null; zone.innerHTML = ""; $("#chrono").textContent = "Touchez pour parler (3 min max)"; };
      $("#conv-go").onclick = async () => {
        const c = enAttenteConv; if (!c) return;
        enAttenteConv = null; zone.innerHTML = "";
        await envoyerConversion(v, c.blob, c.ext);
      };
    },
  });
  if (ok) btn.classList.add("actif");
}

async function envoyerConversion(v, blob, ext) {
  if (blob.size > 25 * 1024 * 1024) { toast("Fichier trop gros (25 Mo max)."); return; }
  const b64 = await versBase64(blob);
  const r = appel("transformer", v.id, b64, ext, ui.hauteur[v.id] ?? 0);
  if (r && r.ok === false) toast(r.erreur);
  else toast("C'est parti ! Comptez 5 à 8 minutes, une notification vous préviendra.");
  charger(); rendu();
}

async function transformerFichier(v, f) {
  const ext = (f.name.split(".").pop() || "m4a").toLowerCase();
  await envoyerConversion(v, f, ext);
}

// --- import de fichiers
let rappelImport = null;
function importer(fn, multiple = true) {
  const inp = $("#import");
  inp.multiple = multiple;
  inp.value = "";
  rappelImport = fn;
  inp.click();
}
$("#import").onchange = (e) => {
  const fichiers = [...e.target.files];
  if (fichiers.length && rappelImport) rappelImport(fichiers);
};
async function ajouterFichiers(v, fichiers) {
  let n = 0;
  for (const f of fichiers) {
    if (f.size > 40 * 1024 * 1024) { toast(`${f.name} est trop gros (40 Mo max).`); continue; }
    const ms = await dureeFichier(f);
    const ext = (f.name.split(".").pop() || "audio").toLowerCase();
    const b64 = await versBase64(f);
    const r = appel("ajouterEnregistrement", v.id, b64, ext, ms);
    if (!(r && r.ok === false)) n++;
  }
  if (n) toast(n > 1 ? `${n} fichiers ajoutés` : "Fichier ajouté");
  charger(); rendu();
}

// ---------------------------------------------------------------- feuille du bas
function ouvrirFeuille(html) {
  $("#feuille-carte").innerHTML = html;
  $("#feuille").hidden = false;
}
function fermerFeuille() { $("#feuille").hidden = true; $("#feuille-carte").innerHTML = ""; }
$(".feuille-fond").onclick = fermerFeuille;

// ---------------------------------------------------------------- événements d'Android
window.tmvEvenement = function (nom, d) {
  if (nom === "kaggle") {
    const z = $("#test-res");
    if (z) z.innerHTML = d.ok
      ? `<span class="ok">✓ Connexion réussie.</span>` + (d.quota && d.quota.total_h ? ` ${Math.round(d.quota.total_h - d.quota.utilise_h)} h de GPU gratuites disponibles cette semaine.` : "")
      : `<span class="ko">✗ ${esc(d.erreur)}</span>`;
    if (d.ok) setTimeout(() => { charger(); if (!occupe()) rendu(); }, 1800);
  } else if (nom.startsWith("yt")) {
    YTV.evenement(nom, d);
  } else if (nom === "maj") {
    const z = $("#maj-res"); if (!z) return;
    if (!d.ok) z.innerHTML = `<span class="ko">Impossible de vérifier : ${esc(d.erreur)}</span>`;
    else if (!d.dispo) z.innerHTML = `<span class="ok">Vous avez la dernière version.</span>`;
    else z.innerHTML = `Version ${esc(d.version)} disponible. <button class="btn mini principal" data-act="installerMaj" style="margin-left:6px">Installer</button>`;
  } else if (nom === "majProgression") {
    const z = $("#maj-res"); if (!z) return;
    z.textContent = d.erreur ? "Échec : " + d.erreur : d.fini ? "Installation…" : `Téléchargement… ${d.p} %`;
  }
};

// ---------------------------------------------------------------- démarrage
charger();
rendu();
