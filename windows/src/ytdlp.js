// yt-dlp et ffmpeg : téléchargés au premier lancement (et yt-dlp mis à jour chaque jour), puis lancés en arrière-plan.
const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");

const IS_WIN = process.platform === "win32";
const YT_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
const FF_URL = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

class Tools {
  constructor(dir, log) {
    this.dir = dir;
    this.log = log || (() => {});
    this.ytdlp = process.env.FT_YTDLP || path.join(dir, IS_WIN ? "yt-dlp.exe" : "yt-dlp");
    this.ffmpegDir = dir;
    this.ready = null;
    this.status = "";
  }
  async _download(url, dest, label) {
    this.status = label + "…";
    this.log(label);
    const res = await fetch(url, { redirect: "follow" });
    if (!res.ok) throw new Error(label + " : HTTP " + res.status);
    const buf = Buffer.from(await res.arrayBuffer());
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest + ".part", buf);
    fs.renameSync(dest + ".part", dest);
    return buf.length;
  }
  /** Prépare yt-dlp (et le met à jour s'il date de plus d'un jour). */
  ensureYtdlp() {
    if (this.ready) return this.ready;
    this.ready = (async () => {
      fs.mkdirSync(this.dir, { recursive: true });
      const have = fs.existsSync(this.ytdlp);
      const age = have ? Date.now() - fs.statSync(this.ytdlp).mtimeMs : Infinity;
      if (!process.env.FT_YTDLP && (!have || age > 86400000)) {
        try { await this._download(YT_URL, this.ytdlp, "Téléchargement de yt-dlp"); } catch (e) { if (!have) { this.ready = null; throw e; } }
      }
      this.status = "";
    })();
    this.ready.catch(() => { this.ready = null; });
    return this.ready;
  }
  hasFfmpeg() { return fs.existsSync(path.join(this.ffmpegDir, IS_WIN ? "ffmpeg.exe" : "ffmpeg")); }
  /** ffmpeg sert à fusionner image + son (vidéo) et à convertir en MP3. Téléchargé seulement si on en a besoin. */
  async ensureFfmpeg() {
    if (this.hasFfmpeg()) return;
    if (this._ff) return this._ff;
    this._ff = (async () => {
      const AdmZip = require("adm-zip");
      const zip = path.join(this.dir, "ffmpeg.zip");
      await this._download(FF_URL, zip, "Téléchargement de ffmpeg (une seule fois)");
      const z = new AdmZip(zip);
      const e = z.getEntries().find((x) => /(^|\/)bin\/ffmpeg\.exe$/.test(x.entryName));
      if (!e) throw new Error("ffmpeg introuvable dans l'archive");
      fs.writeFileSync(path.join(this.ffmpegDir, "ffmpeg.exe"), e.getData());
      try { fs.unlinkSync(zip); } catch (err) { /* ignoré */ }
      this.status = "";
    })();
    this._ff.catch(() => { this._ff = null; });
    return this._ff;
  }

  /** Lance yt-dlp. onLine reçoit chaque ligne de sortie. Renvoie { code, out, err }. */
  run(args, opts) {
    const o = opts || {};
    return new Promise((resolve, reject) => {
      const full = ["--no-warnings", "--socket-timeout", "20", "--no-colors"].concat(this.hasFfmpeg() ? ["--ffmpeg-location", this.ffmpegDir] : [], args);
      const p = spawn(this.ytdlp, full, { windowsHide: true, cwd: o.cwd });
      let out = "", err = "", buf = "";
      const feed = (d) => { buf += d.toString(); let i; while ((i = buf.indexOf("\n")) >= 0) { const l = buf.slice(0, i).replace(/\r$/, ""); buf = buf.slice(i + 1); if (o.onLine) o.onLine(l); } };
      p.stdout.on("data", (d) => { out += d; feed(d); });
      p.stderr.on("data", (d) => { err += d; feed(d); });
      p.on("error", reject);
      const t = o.timeout ? setTimeout(() => { try { p.kill(); } catch (e) { /* ignoré */ } }, o.timeout) : null;
      if (o.signal) o.signal.addEventListener("abort", () => { try { p.kill(); } catch (e) { /* ignoré */ } });
      p.on("close", (code) => { if (t) clearTimeout(t); code === 0 ? resolve({ code, out, err }) : reject(Object.assign(new Error((err || out).trim().split("\n").pop() || "yt-dlp a échoué"), { code, out, err })); });
    });
  }

  /** Recherche YouTube : titres, chaînes, durées. */
  async search(q, n) {
    await this.ensureYtdlp();
    const clean = String(q || "").replace(/[\u0000-\u001f]/g, " ").trim().slice(0, 120);
    if (!clean) return [];
    const r = await this.run(["ytsearch" + (n || 15) + ":" + clean, "--flat-playlist", "--dump-single-json"], { timeout: 45000 });
    const o = JSON.parse(r.out);
    return (o.entries || []).filter((x) => x && x.id && String(x.id).length === 11 && x.live_status !== "is_live").map((x) => ({
      id: x.id, title: x.title || "", channel: (x.channel || x.uploader || "").replace(/ - Topic$/, ""), duration: Math.round(x.duration || 0), views: x.view_count || 0,
      thumb: "https://i.ytimg.com/vi/" + x.id + "/mqdefault.jpg",
    }));
  }
}

const safeName = (s) => String(s || "titre").replace(/[\\/:*?"<>|\u0000-\u001f]/g, " ").replace(/\s+/g, " ").trim().slice(0, 100) || "titre";
const PROG = /\[download\]\s+([\d.]+)%/;

/** Audio : on garde le m4a de YouTube tel quel (sans conversion) ; sinon repli en MP3 (ffmpeg). Renvoie le chemin du fichier. */
async function downloadAudio(tools, id, dir, name, opts) {
  const o = opts || {};
  await tools.ensureYtdlp();
  fs.mkdirSync(dir, { recursive: true });
  const base = path.join(dir, safeName(name) + (o.tmp ? "." + o.tmp : ""));
  const fmt = o.light ? "bestaudio[ext=m4a][abr<=70]/bestaudio[ext=m4a]" : "bestaudio[ext=m4a]";
  const onLine = (l) => { const m = PROG.exec(l); if (m && o.onProgress) o.onProgress(Math.min(1, +m[1] / 100)); };
  const url = "https://www.youtube.com/watch?v=" + id;
  try {
    await tools.run(["--no-playlist", "--no-mtime", "--newline", "-f", fmt, "-o", base + ".%(ext)s", url], { onLine, timeout: 600000 });
    const f = base + ".m4a";
    if (fs.existsSync(f) && fs.statSync(f).size > 1000) return f;
  } catch (e) { if (o.noMp3) throw e; }
  if (o.noMp3) throw new Error("pas de flux m4a");
  await tools.ensureFfmpeg();
  await tools.run(["--no-playlist", "--no-mtime", "--newline", "-f", "bestaudio[ext=m4a]/bestaudio/best", "-x", "--audio-format", "mp3", "--audio-quality", (o.kbps || 192) + "K", "-o", base + ".%(ext)s", url], { onLine, timeout: 900000 });
  const f = base + ".mp3";
  if (!fs.existsSync(f)) throw new Error("fichier introuvable");
  return f;
}

/** Vidéo MP4 (hauteur maximale choisie). maxBytes : pour l'envoi dans le chat (48 Mo) on retente en plus petit si trop lourd. */
async function downloadVideo(tools, id, dir, name, height, opts) {
  const o = opts || {};
  await tools.ensureYtdlp();
  await tools.ensureFfmpeg();
  fs.mkdirSync(dir, { recursive: true });
  const base = path.join(dir, safeName(name) + (o.tmp ? "." + o.tmp : ""));
  const onLine = (l) => { const m = PROG.exec(l); if (m && o.onProgress) o.onProgress(Math.min(1, +m[1] / 100)); };
  const heights = [720, 480, 360].filter((h) => h <= height);
  for (const h of heights.length ? heights : [360]) {
    try { fs.unlinkSync(base + ".mp4"); } catch (e) { /* ignoré */ }
    const args = ["--no-playlist", "--no-mtime", "--newline", "-f", "bv*[height<=" + h + "]+ba/b[height<=" + h + "]/bv*+ba/b", "-S", "res:" + h + ",vcodec:h264,acodec:m4a,ext:mp4:m4a", "--merge-output-format", "mp4"];
    if (o.maxBytes) args.push("--max-filesize", String(o.maxBytes));
    args.push("-o", base + ".%(ext)s", "https://www.youtube.com/watch?v=" + id);
    try { await tools.run(args, { onLine, timeout: 1800000 }); } catch (e) { if (!o.maxBytes) throw e; }
    if (fs.existsSync(base + ".mp4")) return base + ".mp4";
  }
  throw new Error("vidéo trop lourde ou introuvable");
}

/** Mode p2p : flux m4a direct + taille exacte (même règles que l'application Android). */
async function resolveStream(tools, yid, light, fid) {
  await tools.ensureYtdlp();
  const sel = fid || (light ? "bestaudio[ext=m4a][abr<=70]/bestaudio[ext=m4a]" : "bestaudio[ext=m4a]");
  const r = await tools.run(["--no-playlist", "-f", sel, "--dump-json", "https://www.youtube.com/watch?v=" + yid], { timeout: 60000 });
  const o = JSON.parse(r.out.trim().split("\n").pop());
  const url = o.url || "";
  if (!url.startsWith("https://")) return null;
  if (String(o.ext).toLowerCase() !== "m4a" || !String(o.protocol || "").startsWith("http")) return null;
  const id = String(o.format_id || "");
  if (!/^[A-Za-z0-9_.-]{1,24}$/.test(id)) return null;
  const m = /[?&]clen=(\d+)/.exec(url);
  const size = m ? +m[1] : (o.filesize > 0 ? o.filesize : 0);
  if (!size) return null;
  return { fid: id, url, headers: o.http_headers || {}, size };
}

/** Télécharge les octets a..b (inclus) ; refuse toute réponse qui n'est pas exactement le morceau demandé. */
async function fetchRange(st, a, b) {
  const headers = {};
  for (const k of Object.keys(st.headers || {})) if (!/^(range|host)$/i.test(k)) headers[k] = st.headers[k];
  headers.Range = "bytes=" + a + "-" + b;
  const res = await fetch(st.url, { headers, signal: AbortSignal.timeout(60000) });
  if (res.status !== 206) throw new Error("HTTP " + res.status);
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length !== b - a + 1) throw new Error("morceau incomplet (" + buf.length + "/" + (b - a + 1) + ")");
  return buf;
}

module.exports = { Tools, downloadAudio, downloadVideo, resolveStream, fetchRange, safeName };
