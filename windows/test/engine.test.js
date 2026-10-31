// Test du moteur avec un faux Supabase et un faux yt-dlp (aucun accès internet).
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const assert = require("assert");
process.env.FT_YTDLP = path.join(__dirname, "fake-ytdlp.sh");
const { Store } = require("../src/store");
const { Tools } = require("../src/ytdlp");
const { Engine } = require("../src/engine");

const calls = []; const uploads = []; let opened = false; let doneArgs = null;
const SIZE = 3 * 1024 * 1024;
const server = http.createServer((req, res) => {
  let body = []; req.on("data", (d) => body.push(d));
  req.on("end", () => {
    const b = Buffer.concat(body); const u = req.url;
    const json = (o, code) => { res.writeHead(code || 200, { "Content-Type": "application/json" }); res.end(JSON.stringify(o)); };
    if (u.startsWith("/stream")) {                        // faux flux audio avec Range
      const m = /bytes=(\d+)-(\d+)/.exec(req.headers.range || "");
      if (!m) { res.writeHead(200); return res.end(); }
      const a = +m[1], z = +m[2]; res.writeHead(206, { "Content-Length": z - a + 1 }); return res.end(Buffer.alloc(z - a + 1, 7));
    }
    if (u.startsWith("/auth/v1/token")) return json({ access_token: "t", refresh_token: "r", expires_in: 3600, user: { id: "11111111-1111-1111-1111-111111111111" } });
    if (u.startsWith("/storage/v1/object/chat/")) { uploads.push({ path: u.split("/chat/")[1], size: b.length }); return json({ Key: "ok" }); }
    const name = u.split("/rpc/c_")[1]; const args = b.length ? JSON.parse(b.toString()) : {};
    calls.push([name, args]);
    if (name === "relay_open") { if (opened) return json([]); opened = true; return json([{ id: 5, yid: "abcdefghijk", ti: "Titre test", fmt: "a", p2p: false }]); }
    if (name === "relay_take") return json({ status: "ok" });
    if (name === "relay_done") { doneArgs = args; return json({ status: "ok" }); }
    if (name === "relay_p2p_jobs") return json([]);
    return json({ status: "ok" });
  });
});
server.listen(0, async () => {
  const port = server.address().port;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ftw-"));
  const store = new Store(dir); store.addAccount("srv_demo", "codecode123");
  const tools = new Tools(path.join(dir, "tools"));
  const eng = new Engine(store, tools, { url: "http://127.0.0.1:" + port, anon: "x", version: "test", tmpDir: path.join(dir, "tmp") });
  await eng.startAll();
  for (let i = 0; i < 40 && !doneArgs; i++) await new Promise((r) => setTimeout(r, 250));
  assert(doneArgs, "relay_done jamais appelé");
  assert.strictEqual(doneArgs.p_id, 5); assert.strictEqual(doneArgs.p_size, 5000);
  assert(/^11111111-1111-1111-1111-111111111111\/[0-9a-f]{32}\.m4a$/.test(doneArgs.p_media), "chemin d'envoi : " + doneArgs.p_media);
  assert(calls.some((c) => c[0] === "heartbeat" && c[1].p_platform === "windows"), "heartbeat");
  assert(calls.some((c) => c[0] === "relay_progress" && c[1].p_prog === 33), "progression 33");
  assert(!fs.existsSync(path.join(dir, "tmp", "r5")), "fichier temporaire supprimé");
  console.log("✓ demande iPhone : prise, téléchargée, envoyée, terminée");

  // p2p : un autre travailleur aide un téléchargement p2p (morceaux pris, téléchargés par Range, envoyés)
  const w = [...eng.workers.values()][0];
  process.env.FAKE_STREAM = "http://127.0.0.1:" + port + "/stream?clen=" + SIZE; process.env.FAKE_SIZE = String(SIZE);
  // le faux flux est en http : on contourne le contrôle https uniquement pour ce test
  const Y = require("../src/ytdlp"); const orig = Y.resolveStream;
  let chunks = [{ idx: 0, a: 0, b: 1048575 }, { idx: 1, a: 1048576, b: 2097151 }, { idx: 2, a: 2097152, b: SIZE - 1 }];
  const origRpc = w.client.rpc.bind(w.client); const done = [];
  w.client.rpc = async (n, a) => {
    if (n === "relay_chunk_take") { const c = chunks.shift(); return c ? Object.assign({ status: "ok" }, c) : { status: "done" }; }
    if (n === "relay_chunk_done") { done.push(a); return { status: "ok" }; }
    return origRpc(n, a);
  };
  const Yfake = { url: process.env.FAKE_STREAM, headers: {}, size: SIZE, fid: "140" };
  await w.p2pWork(9, "abcdefghijk", false, "140", SIZE, Yfake);
  assert.strictEqual(done.length, 3, "3 morceaux envoyés");
  assert.deepStrictEqual(done.map((d) => d.p_size), [1048576, 1048576, SIZE - 2097152]);
  console.log("✓ p2p : 3 morceaux téléchargés par Range et envoyés");
  eng.stopAll(); server.close(); process.exit(0);
});
