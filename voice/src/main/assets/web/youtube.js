/* Take my voice — fenêtre YouTube : trouver un discours de la personne et en garder un passage pour l'entraînement. */
"use strict";

const fmtT = (s) => {
  s = Math.max(0, Math.round(s));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(x).padStart(2, "0");
};
const lireT = (t) => {
  const p = String(t || "").trim().split(":").map((x) => parseInt(x, 10));
  if (!p.length || p.some(isNaN)) return NaN;
  return p.reduce((a, b) => a * 60 + b, 0);
};
const MAX_PASSAGE = 40 * 60;

const YTV = {
  voixId: null, pret: false, choisie: null, lecteur: null, extraction: false, ajouts: [], dernierQ: "", resultats: [],

  vue(c) {
    this.voixId = c.id;
    this.choisie = null;
    const v = voixDe(c.id) || { nom: "la personne" };
    $("#titre").textContent = "YouTube";
    setTimeout(() => this.monter(), 0);
    return `<div class="carte">
      <h2>Trouver ${esc(v.nom)} sur YouTube</h2>
      <p>Cherchez une vidéo où ${esc(v.nom)} parle (discours, interview, vlog…) ou collez son lien. Vous choisirez ensuite le passage à garder.</p>
      <div class="ligne"><input class="champ espace" id="yt-q" style="margin:0" placeholder="Nom, sujet ou lien YouTube" value="${esc(this.dernierQ)}" enterkeyhint="search">
      <button class="btn principal" id="yt-go" style="min-width:56px" aria-label="Chercher"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/></svg></button></div>
      <div id="yt-etat" class="petit" style="margin-top:10px"></div>
    </div>
    <div id="yt-zone"></div>`;
  },

  monter() {
    const q = $("#yt-q"), go = $("#yt-go");
    if (!q) return;
    const lancer = () => {
      const t = q.value.trim();
      if (!t) { q.focus(); return; }
      this.dernierQ = t;
      q.blur();
      this.etat(this.pret ? "Recherche…" : "Préparation du moteur YouTube (la première fois : environ 15 s)…");
      $("#yt-zone").innerHTML = `<div class="barre-prog indet"><div></div></div>`;
      appel("ytChercher", t);
    };
    go.onclick = lancer;
    q.onkeydown = (e) => { if (e.key === "Enter") lancer(); };
    if (this.resultats.length) this.afficherListe();
    if (!this.pret) appel("ytPreparer");
    chargerApiYoutube();
  },

  etat(t, cls = "") { const z = $("#yt-etat"); if (z) z.innerHTML = cls ? `<span class="${cls}">${t}</span>` : t; },

  afficherListe() {
    const z = $("#yt-zone"); if (!z) return;
    if (!this.resultats.length) { z.innerHTML = `<p class="petit" style="text-align:center">Aucune vidéo trouvée.</p>`; return; }
    z.innerHTML = this.resultats.map((r, i) => `<button class="yt-video" data-i="${i}">
        <span class="yt-mini"><img src="${esc(r.miniature)}" alt="" loading="lazy">${r.duree ? `<span class="yt-duree">${fmtT(r.duree)}</span>` : ""}</span>
        <span class="espace"><span class="yt-titre">${esc(r.titre)}</span><span class="petit">${esc(r.chaine)}</span></span>
      </button>`).join("");
    z.onclick = (e) => { const b = e.target.closest(".yt-video"); if (b) this.choisir(this.resultats[+b.dataset.i]); };
  },

  choisir(r) {
    this.choisie = r;
    const v = voixDe(this.voixId) || { nom: "la personne" };
    const fin = r.duree ? Math.min(r.duree, 20 * 60) : 0;
    $("#yt-zone").innerHTML = `<div class="carte">
      <div class="yt-cadre"><div id="yt-lecteur"><img src="${esc(r.miniature.replace("mqdefault", "hqdefault"))}" alt=""></div></div>
      <div class="yt-titre" style="margin:12px 0 2px">${esc(r.titre)}</div>
      <div class="petit">${esc(r.chaine)}${r.duree ? " · " + fmtT(r.duree) : ""}</div>
      <div class="titre-section" style="margin:18px 0 8px">Passage à garder</div>
      <div class="yt-temps">
        <label class="etiquette">Début</label><input class="champ" id="yt-debut" value="0:00" inputmode="numeric">
        <button class="btn mini" data-ici="debut">⏱ Ici</button>
        <label class="etiquette">Fin</label><input class="champ" id="yt-fin" value="${fin ? fmtT(fin) : ""}" inputmode="numeric" placeholder="${r.duree ? "" : "ex. 5:00"}">
        <button class="btn mini" data-ici="fin">⏱ Ici</button>
      </div>
      <div class="ligne" style="margin:2px 0 8px"><button class="btn mini" id="yt-tout">Toute la vidéo</button><span class="espace"></span><b id="yt-longueur"></b></div>
      <p class="petit" style="margin:0 0 12px">Lisez la vidéo et touchez « Ici » quand ${esc(v.nom)} commence et arrête de parler. Gardez des passages où <b>seule sa voix</b> s'entend (sans autre personne). La musique de fond est retirée automatiquement sur Kaggle.</p>
      <div id="yt-action"><button class="btn principal plein" id="yt-ajouter">Ajouter à l'entraînement</button></div>
      <button class="btn plein" style="margin-top:8px" id="yt-autre">Choisir une autre vidéo</button>
      <div id="yt-ajouts" style="margin-top:12px"></div>
    </div>`;
    window.scrollTo(0, 0);
    const maj = () => {
      const d = lireT($("#yt-debut").value), f = lireT($("#yt-fin").value);
      const L = $("#yt-longueur");
      if (isNaN(d) || isNaN(f) || f <= d) { L.textContent = ""; L.className = ""; return; }
      L.textContent = fmtT(f - d) + (f - d > MAX_PASSAGE ? " (40 min max)" : "");
      L.className = f - d > MAX_PASSAGE ? "ko" : "";
    };
    $("#yt-debut").oninput = maj; $("#yt-fin").oninput = maj;
    $("#yt-tout").onclick = () => { $("#yt-debut").value = "0:00"; if (r.duree) $("#yt-fin").value = fmtT(Math.min(r.duree, MAX_PASSAGE)); maj(); };
    $("#yt-zone").querySelectorAll("[data-ici]").forEach((b) => b.onclick = () => {
      const t = this.lecteur && this.lecteur.getCurrentTime ? this.lecteur.getCurrentTime() : NaN;
      if (isNaN(t)) { toast("Lancez d'abord la vidéo, ou tapez le temps à la main (ex. 1:30)."); return; }
      $("#yt-" + b.dataset.ici).value = fmtT(t); maj();
    });
    $("#yt-autre").onclick = () => this.fermerChoix();
    $("#yt-ajouter").onclick = () => this.ajouter();
    maj();
    this.afficherAjouts();
    this.creerLecteur(r.id);
  },

  creerLecteur(id) {
    this.lecteur = null;
    if (!(window.YT && window.YT.Player)) { window.__ytAttente = () => this.choisie && this.choisie.id === id && this.creerLecteur(id); return; }
    try {
      this.lecteur = new YT.Player("yt-lecteur", {
        videoId: id, host: "https://www.youtube-nocookie.com",
        playerVars: { playsinline: 1, rel: 0, modestbranding: 1, origin: location.origin },
        events: { onError: () => { this.etat("Aperçu indisponible pour cette vidéo : tapez les temps à la main.", ""); } },
      });
    } catch (_) {}
  },

  fermerChoix() {
    try { this.lecteur && this.lecteur.destroy && this.lecteur.destroy(); } catch (_) {}
    this.lecteur = null; this.choisie = null;
    this.afficherListe();
  },

  ajouter() {
    const r = this.choisie; if (!r) return;
    const d = lireT($("#yt-debut").value), f = lireT($("#yt-fin").value);
    if (isNaN(d) || isNaN(f) || f <= d) { toast("Indiquez un début et une fin valides (ex. 0:30 et 6:00)."); return; }
    if (f - d < 10) { toast("Passage trop court : 10 secondes minimum."); return; }
    if (f - d > MAX_PASSAGE) { toast("Passage trop long : 40 minutes maximum par ajout."); return; }
    if (r.duree && f > r.duree + 2) { toast("La fin dépasse la durée de la vidéo."); return; }
    try { this.lecteur && this.lecteur.pauseVideo && this.lecteur.pauseVideo(); } catch (_) {}
    this.extraction = { d, f };
    $("#yt-action").innerHTML = `<div class="ligne"><span class="espace" id="yt-pc">Extraction de l'audio… 0 %</span>
      <button class="btn mini danger" id="yt-stop">Annuler</button></div><div class="barre-prog"><div id="yt-barre" style="width:0%"></div></div>`;
    $("#yt-stop").onclick = () => appel("ytAnnuler");
    appel("ytExtraire", this.voixId, r.id, Math.floor(d), Math.ceil(f));
  },

  afficherAjouts() {
    const z = $("#yt-ajouts"); if (!z) return;
    const miens = this.ajouts.filter((a) => a.voix === this.voixId);
    z.innerHTML = miens.length ? `<div class="petit ok">✓ Ajouté${miens.length > 1 ? "s" : ""} : ${miens.map((a) => fmtT(a.duree)).join(", ")}</div>` : "";
  },

  evenement(nom, d) {
    if (nom === "ytPret") {
      this.pret = d.ok;
      if (!d.ok) this.etat("Moteur YouTube indisponible : " + esc(d.erreur), "ko");
    } else if (nom === "ytResultats") {
      this.pret = true;
      if (!d.ok) { this.etat("✗ " + esc(d.erreur || "Recherche impossible"), "ko"); $("#yt-zone") && ($("#yt-zone").innerHTML = ""); return; }
      this.resultats = d.videos || [];
      this.etat(this.resultats.length ? "Touchez la vidéo où la personne parle." : "");
      if (this.resultats.length === 1 && /youtu/.test(this.dernierQ)) this.choisir(this.resultats[0]);
      else this.afficherListe();
    } else if (nom === "ytProgression") {
      const b = $("#yt-barre"), t = $("#yt-pc");
      if (b) b.style.width = d.p + "%";
      if (t) t.textContent = `Extraction de l'audio… ${d.p} %`;
    } else if (nom === "ytFini") {
      const ex = this.extraction; this.extraction = false;
      const z = $("#yt-action");
      if (z) z.innerHTML = `<button class="btn principal plein" id="yt-ajouter">Ajouter un autre passage</button>`;
      const btn = $("#yt-ajouter"); if (btn) btn.onclick = () => this.ajouter();
      if (d.ok && ex) {
        this.ajouts.push({ voix: this.voixId, duree: ex.f - ex.d });
        this.afficherAjouts();
        toast(`Passage de ${fmtT(ex.f - ex.d)} ajouté aux enregistrements.`);
        charger();
      } else if (!d.ok) toast(d.erreur || "Extraction impossible");
    }
  },
};

function vueYoutube(c) { return YTV.vue(c); }

// API officielle du lecteur YouTube (pour lire la vidéo et récupérer le temps « Ici »)
function chargerApiYoutube() {
  if (window.YT && window.YT.Player) return;
  if (document.getElementById("yt-api")) return;
  window.onYouTubeIframeAPIReady = () => { const f = window.__ytAttente; window.__ytAttente = null; f && f(); };
  const s = document.createElement("script");
  s.id = "yt-api";
  s.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(s);
}
