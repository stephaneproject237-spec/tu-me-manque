// Lecteur intégré : sert au lecteur de la fenêtre (protocole ftmedia://) soit un fichier du disque, soit un flux YouTube,
// avec la prise en charge de « Range » pour pouvoir avancer / reculer dans la lecture.
const fs = require("fs");
const crypto = require("crypto");
const { Readable } = require("stream");

const MIME = { ".mp4": "video/mp4", ".m4v": "video/mp4", ".m4a": "audio/mp4", ".mp3": "audio/mpeg", ".webm": "video/webm", ".ogg": "audio/ogg", ".wav": "audio/wav", ".flac": "audio/flac" };

class Media {
  constructor() { this.map = new Map(); }
  add(entry) {
    const t = crypto.randomBytes(12).toString("hex");
    this.map.set(t, entry);
    while (this.map.size > 60) this.map.delete(this.map.keys().next().value);
    return t;
  }
  /** req : { url, range } -> Response */
  async handle(url, range) {
    let t = "";
    try { t = new URL(url).pathname.replace(/^\/+/, ""); } catch (e) { /* ignoré */ }
    const m = this.map.get(t);
    if (!m) return new Response("", { status: 404 });
    if (m.file) return this.file(m.file, range);
    return this.stream(m, range);
  }
  async file(f, range) {
    let st;
    try { st = fs.statSync(f); } catch (e) { return new Response("", { status: 404 }); }
    const ext = f.slice(f.lastIndexOf(".")).toLowerCase();
    const h = { "Content-Type": MIME[ext] || "application/octet-stream", "Accept-Ranges": "bytes" };
    const r = /^bytes=(\d*)-(\d*)$/.exec(range || "");
    let a = 0, b = st.size - 1, code = 200;
    if (r && (r[1] !== "" || r[2] !== "")) {
      if (r[1] === "") { a = Math.max(0, st.size - +r[2]); } else { a = +r[1]; if (r[2] !== "") b = Math.min(b, +r[2]); }
      if (a > b || a >= st.size) return new Response("", { status: 416, headers: { "Content-Range": "bytes */" + st.size } });
      code = 206; h["Content-Range"] = "bytes " + a + "-" + b + "/" + st.size;
    }
    h["Content-Length"] = String(b - a + 1);
    if (st.size === 0) return new Response("", { status: 200, headers: h });
    return new Response(Readable.toWeb(fs.createReadStream(f, { start: a, end: b })), { status: code, headers: h });
  }
  async stream(m, range) {
    const headers = {};
    for (const k of Object.keys(m.headers || {})) if (!/^(range|host)$/i.test(k)) headers[k] = m.headers[k];
    if (range) headers.Range = range;
    const res = await fetch(m.url, { headers, signal: AbortSignal.timeout(30000) });
    const h = {};
    for (const k of ["content-type", "content-length", "content-range", "accept-ranges"]) { const v = res.headers.get(k); if (v) h[k] = v; }
    if (!h["accept-ranges"]) h["accept-ranges"] = "bytes";
    return new Response(res.body, { status: res.status, headers: h });
  }
}
module.exports = { Media, MIME };
