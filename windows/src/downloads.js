// Téléchargements faits par la personne elle-même sur ce PC (page Recherche) : enregistrés dans un dossier.
// Chaque téléchargement peut être mis en pause (yt-dlp est arrêté, le début du fichier est gardé), repris ou annulé.
const fs = require("fs");
const path = require("path");
const { EventEmitter } = require("events");
const Y = require("./ytdlp");

class Downloads extends EventEmitter {
  constructor(tools, getDir) {
    super();
    this.tools = tools; this.getDir = getDir;
    this.items = []; this.seq = 0; this.running = 0;
  }
  list() { return this.items.slice(-80).map((x) => ({ id: x.id, title: x.title, artist: x.artist, kind: x.kind, state: x.state, pct: x.pct, file: x.file, err: x.err, yid: x.rid || x.yid, tag: x.tag })); }
  start(o) {
    const it = { id: ++this.seq, yid: o.yid || "", rid: "", title: String(o.title || "titre").slice(0, 120), artist: String(o.artist || "").slice(0, 80), dur: +o.dur || 0, tag: +o.tag || 0, kind: o.kind === "video" ? "video" : "audio", height: +o.height || 720, state: "attente", pct: 0, file: "", err: "", stop: "", ctrl: null };
    this.items.push(it); this.emit("update"); this.pump();
    return it.id;
  }
  /** Ce titre est-il déjà dans le dossier de téléchargement ? (pour l'historique du compte) */
  has(artist, title, kind) {
    const name = Y.safeName(artist ? artist + " - " + title : title), dir = this.getDir();
    return (kind === "video" ? [".mp4"] : [".m4a", ".mp3"]).some((e) => fs.existsSync(path.join(dir, name + e)));
  }
  find(id) { return this.items.find((x) => x.id === +id); }
  /** Pause : arrête yt-dlp en gardant ce qui est déjà téléchargé. */
  pause(id) {
    const it = this.find(id); if (!it) return false;
    if (it.state === "en cours" && it.ctrl) { it.stop = "en pause"; it.ctrl.abort(); }
    else if (it.state === "attente") it.state = "en pause";
    else return false;
    this.emit("update"); return true;
  }
  /** Reprise (ou nouvel essai après un échec) : yt-dlp repart là où il s'était arrêté. */
  resume(id) {
    const it = this.find(id); if (!it || !(it.state === "en pause" || it.state === "échec" || it.state === "annulé")) return false;
    it.state = "attente"; it.err = ""; it.stop = ""; this.emit("update"); this.pump(); return true;
  }
  cancel(id) {
    const it = this.find(id); if (!it) return false;
    if (it.state === "en cours" && it.ctrl) { it.stop = "annulé"; it.ctrl.abort(); }
    else if (it.state === "attente" || it.state === "en pause" || it.state === "échec") it.state = "annulé";
    else return false;
    this.emit("update"); return true;
  }
  /** Retire de la liste les téléchargements terminés, annulés ou en échec (les fichiers restent sur le disque). */
  clear() { this.items = this.items.filter((x) => x.state === "attente" || x.state === "en cours" || x.state === "en pause"); this.emit("update"); }
  pump() {
    for (const it of this.items) {
      if (this.running >= 2) return;
      if (it.state !== "attente") continue;
      this.running++; it.state = "en cours"; it.stop = ""; it.ctrl = new AbortController(); this.emit("update");
      this.run(it).then(() => { it.state = "terminé"; it.pct = 1; }).catch((e) => {
        if (it.stop) it.state = it.stop; else { it.state = "échec"; it.err = String(e.message || e).slice(0, 140); }
      }).finally(() => { it.ctrl = null; this.running--; this.emit("update"); this.pump(); });
    }
  }
  async run(it) {
    let yid = it.rid || it.yid;
    if (!yid) {   // titre venu d'un album : on cherche la meilleure vidéo correspondante (durée proche)
      const r = await this.tools.search((it.artist + " " + it.title).trim(), 8);
      if (!r.length) throw new Error("introuvable sur YouTube");
      const near = it.dur ? r.find((x) => Math.abs(x.duration - it.dur) <= 12) : null;
      yid = (near || r[0]).id;
    }
    it.rid = yid;    // la reprise repart du même fichier
    const name = it.artist ? it.artist + " - " + it.title : it.title;
    const onProgress = (p) => { it.pct = Math.max(it.pct, p); this.emit("update"); };
    const dir = this.getDir(), signal = it.ctrl.signal;
    it.file = it.kind === "video" ? await Y.downloadVideo(this.tools, yid, dir, name, it.height, { onProgress, signal }) : await Y.downloadAudio(this.tools, yid, dir, name, { onProgress, signal });
  }
}
module.exports = { Downloads };
