// Téléchargements faits par la personne elle-même sur ce PC (page Recherche) : enregistrés dans un dossier.
const path = require("path");
const { EventEmitter } = require("events");
const Y = require("./ytdlp");

class Downloads extends EventEmitter {
  constructor(tools, getDir) {
    super();
    this.tools = tools; this.getDir = getDir;
    this.items = []; this.seq = 0; this.running = 0;
  }
  list() { return this.items.slice(-80).map((x) => ({ id: x.id, title: x.title, artist: x.artist, kind: x.kind, state: x.state, pct: x.pct, file: x.file, err: x.err })); }
  start(o) {
    const it = { id: ++this.seq, yid: o.yid || "", title: String(o.title || "titre").slice(0, 120), artist: String(o.artist || "").slice(0, 80), dur: +o.dur || 0, kind: o.kind === "video" ? "video" : "audio", height: +o.height || 720, state: "attente", pct: 0, file: "", err: "" };
    this.items.push(it); this.emit("update"); this.pump();
    return it.id;
  }
  pump() {
    for (const it of this.items) {
      if (this.running >= 2) return;
      if (it.state !== "attente") continue;
      this.running++; it.state = "en cours"; this.emit("update");
      this.run(it).then(() => { it.state = "terminé"; it.pct = 1; }).catch((e) => { it.state = "échec"; it.err = String(e.message || e).slice(0, 140); })
        .finally(() => { this.running--; this.emit("update"); this.pump(); });
    }
  }
  async run(it) {
    let yid = it.yid;
    if (!yid) {   // titre venu d'un album : on cherche la meilleure vidéo correspondante (durée proche)
      const r = await this.tools.search((it.artist + " " + it.title).trim(), 8);
      if (!r.length) throw new Error("introuvable sur YouTube");
      const near = it.dur ? r.find((x) => Math.abs(x.duration - it.dur) <= 12) : null;
      yid = (near || r[0]).id;
    }
    const name = it.artist ? it.artist + " - " + it.title : it.title;
    const onProgress = (p) => { it.pct = p; this.emit("update"); };
    const dir = this.getDir();
    it.file = it.kind === "video" ? await Y.downloadVideo(this.tools, yid, dir, name, it.height, { onProgress }) : await Y.downloadAudio(this.tools, yid, dir, name, { onProgress });
  }
}
module.exports = { Downloads };
