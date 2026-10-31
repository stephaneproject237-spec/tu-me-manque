// Moteur : un « travailleur » par compte serveur. Chaque compte reste en ligne, prend les demandes des iPhone
// (même système que les Android : relay_*), télécharge avec yt-dlp, envoie le fichier, et aide au partage p2p.
const fs = require("fs");
const os = require("os");
const path = require("path");
const { EventEmitter } = require("events");
const { Client } = require("./supa");
const Y = require("./ytdlp");

const P2P_MIN = 15 * 1024 * 1024;     // en dessous : méthode habituelle (un seul téléphone)
const POLL_MS = 3000;
const BEAT_MS = 20000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const audioKbps = (f) => (f === "al" ? 96 : f === "ah" ? 320 : 192);
const videoHeight = (f) => (f === "vl" ? 360 : f === "vh" ? 720 : 480);

class Worker {
  constructor(engine, acc) {
    this.eng = engine;
    this.username = acc.username;
    this.share = acc.share !== false;
    this.client = new Client(acc, engine.store, engine.opts);
    this.state = "connexion";        // connexion | en ligne | erreur
    this.error = "";
    this.jobs = new Map();           // id de demande -> texte
    this.p2p = new Set();            // demandes p2p auxquelles on aide
    this.helped = 0;
    this.stopped = false;
    this.lastBeat = 0;
  }
  info() { return { username: this.username, state: this.state, error: this.error, share: this.share, jobs: this.jobs.size + this.p2p.size, helped: this.helped }; }
  touch() { this.eng.emit("update"); }

  async start() {
    this.stopped = false;
    while (!this.stopped) {
      try {
        if (this.state !== "en ligne") { await this.client.ensure(false); this.state = "en ligne"; this.error = ""; this.touch(); this.client.rpc("set_version", { p_ver: this.eng.platform + " " + this.eng.version }).catch(() => {}); }
        if (Date.now() - this.lastBeat > BEAT_MS) { this.lastBeat = Date.now(); this.client.rpc("heartbeat", { p_platform: this.eng.platform, p_share: this.share, p_in_app: true }).catch(() => {}); }
        if (this.share) { await this.step(); await this.p2pStep(); }
      } catch (e) {
        const bad = /invalid|credentials|400|401|403/i.test(String(e.message));
        this.state = "erreur"; this.error = bad ? "connexion refusée (code incorrect ?)" : String(e.message || e).slice(0, 120); this.touch();
        await sleep(bad ? 30000 : 8000);
        continue;
      }
      await sleep(POLL_MS);
    }
  }
  stop() { this.stopped = true; }
  setShare(on) { this.share = !!on; this.lastBeat = 0; this.touch(); }

  // ---------- demandes d'un iPhone ----------
  async step() {
    const jobs = await this.client.rpc("relay_open", {});
    for (const j of Array.isArray(jobs) ? jobs : []) {
      if (this.eng.running >= this.eng.maxJobs()) break;
      if (this.jobs.has(j.id)) continue;
      const t = await this.client.rpc("relay_take", { p_id: j.id }).catch(() => null);
      if (!t || t.status !== "ok") continue;
      this.jobs.set(j.id, (j.ti || j.q || j.yid || "") + "");
      this.eng.running++;
      this.touch();
      this.handle(j).then(() => { this.helped++; }).catch(async (e) => {
        this.eng.log("[" + this.username + "] échec : " + e.message);
        await this.client.rpc("relay_fail", { p_id: j.id }).catch(() => {});
      }).finally(() => { this.jobs.delete(j.id); this.eng.running--; this.touch(); });
    }
  }

  async handle(j) {
    const id = j.id, fmt = j.fmt || "a", video = fmt.startsWith("v");
    let yid = j.yid, title = j.ti || "titre";
    if (!yid) {
      const r = await this.eng.tools.search(j.q || j.ti, 5);
      if (!r.length) throw new Error("introuvable");
      yid = r[0].id; title = r[0].title;
    }
    if (j.p2p && (fmt === "a" || fmt === "al")) { if (await this.p2pLead(id, yid, fmt === "al")) return; }
    const beat = setInterval(() => this.client.rpc("relay_beat", { p_id: id }).catch(() => {}), 1500);
    try {
      let last = -10, lastAt = 0;
      const push = (pct) => { const n = Date.now(); if (pct - last >= 3 && n - lastAt > 1500) { last = pct; lastAt = n; this.client.rpc("relay_progress", { p_id: id, p_prog: pct }).catch(() => {}); } };
      const dir = path.join(this.eng.tmpDir, "r" + id);
      let f;
      try {
        if (video) f = await Y.downloadVideo(this.eng.tools, yid, dir, title, videoHeight(fmt), { maxBytes: 48 * 1024 * 1024, onProgress: (p) => push(Math.floor(p * 33)) });
        else f = await Y.downloadAudio(this.eng.tools, yid, dir, title, { light: fmt === "al", kbps: audioKbps(fmt), onProgress: (p) => push(Math.floor(p * 33)) });
        await this.client.rpc("relay_progress", { p_id: id, p_prog: 33 }).catch(() => {});
        const buf = fs.readFileSync(f);
        const ext = path.extname(f).slice(1).toLowerCase();
        const media = await this.client.upload(buf, ext, video ? "video/mp4" : ext === "m4a" ? "audio/mp4" : "audio/mpeg");
        const d = await this.client.rpc("relay_done", { p_id: id, p_media: media, p_size: buf.length });
        if (!d || d.status !== "ok") throw new Error("déjà traitée");
      } finally { fs.rmSync(dir, { recursive: true, force: true }); }
    } finally { clearInterval(beat); }
  }

  // ---------- mode p2p ----------
  async p2pLead(id, yid, light) {
    const beat = setInterval(() => this.client.rpc("relay_beat", { p_id: id }).catch(() => {}), 1500);
    try {
      const st = await Y.resolveStream(this.eng.tools, yid, light, null).catch(() => null);
      if (!st || st.size < P2P_MIN) return false;
      const init = await this.client.rpc("relay_p2p_init", { p_id: id, p_yid: yid, p_fid: st.fid, p_size: st.size }).catch(() => null);
      if (!init || init.status !== "ok") return false;
      this.p2p.add(id); this.touch();
      await sleep(7000);        // laisse aux autres le temps de se réveiller avant de commencer
      try { await this.p2pWork(id, yid, light, init.fid, init.size, st.fid === init.fid && st.size === init.size ? st : null); }
      catch (e) { /* le travail s'arrête, la demande est reprise par les autres */ }
      finally { this.p2p.delete(id); this.touch(); }
      return true;
    } finally { clearInterval(beat); }
  }
  async p2pStep() {
    const jobs = await this.client.rpc("relay_p2p_jobs", {}).catch(() => []);
    for (const j of Array.isArray(jobs) ? jobs : []) {
      if (this.jobs.has(j.id) || this.p2p.has(j.id) || this.p2p.size >= 3) continue;
      this.p2p.add(j.id); this.touch();
      this.p2pWork(j.id, j.yid, j.fmt === "al", j.p2p_fid, +j.p2p_size, null).catch(() => {}).finally(() => { this.p2p.delete(j.id); this.touch(); });
    }
  }
  async p2pWork(id, yid, light, fid, size, known) {
    if (!yid || yid.length !== 11 || !fid || !(size > 0)) return;
    const st = known || await Y.resolveStream(this.eng.tools, yid, light, fid);
    if (!st || st.size !== size) return;
    let idle = 0, fails = 0;
    while (idle < 40 && fails < 3 && this.share && !this.stopped) {
      const c = await this.client.rpc("relay_chunk_take", { p_id: id }).catch(() => ({ status: "error" }));
      if (c.status === "ok") {
        idle = 0;
        const beat = setInterval(() => this.client.rpc("relay_chunk_beat", { p_id: id, p_idx: c.idx }).catch(() => {}), 1500);
        try {
          const buf = await Y.fetchRange(st, +c.a, +c.b);
          const media = await this.client.upload(buf, "m4a", "audio/mp4");
          await this.client.rpc("relay_chunk_done", { p_id: id, p_idx: c.idx, p_media: media, p_size: buf.length });
          fails = 0;
        } catch (e) { fails++; await this.client.rpc("relay_chunk_fail", { p_id: id, p_idx: c.idx }).catch(() => {}); }
        finally { clearInterval(beat); }
      } else if (c.status === "wait" || c.status === "error") { idle++; await sleep(1500); }
      else return;
    }
  }
}

class Engine extends EventEmitter {
  constructor(store, tools, opts) {
    super();
    this.store = store; this.tools = tools; this.opts = opts || {};
    this.version = (opts && opts.version) || "1.0";
    this.platform = (opts && opts.platform) || "windows";   // windows | mac (annoncé au serveur)
    this.tmpDir = (opts && opts.tmpDir) || path.join(os.tmpdir(), "freetune-windows");
    this.workers = new Map();
    this.running = 0;
    this.logs = [];
  }
  maxJobs() { return Math.max(1, Math.min(12, +this.store.settings.maxJobs || 3)); }
  log(m) { this.logs.push(new Date().toLocaleTimeString("fr-FR") + " " + m); if (this.logs.length > 200) this.logs.shift(); this.emit("update"); }
  async startAll() {
    this.tools.ensureYtdlp().catch((e) => this.log("yt-dlp : " + e.message));
    for (const a of this.store.accounts()) this.add(a);
  }
  add(acc) {
    if (this.workers.has(acc.username)) return;
    const w = new Worker(this, acc);
    this.workers.set(acc.username, w);
    w.start();
    this.emit("update");
  }
  remove(username) {
    const w = this.workers.get(username);
    if (w) { w.stop(); this.workers.delete(username); }
    this.store.removeAccount(username);
    this.emit("update");
  }
  setShare(username, on) { this.store.setShare(username, on); const w = this.workers.get(username); if (w) w.setShare(on); }
  stopAll() { for (const w of this.workers.values()) w.stop(); }
  snapshot() { return { accounts: [...this.workers.values()].map((w) => w.info()), running: this.running, maxJobs: this.maxJobs(), tool: this.tools.status, logs: this.logs.slice(-40) }; }
}
module.exports = { Engine, Worker };
