// Test du lecteur intégré (lecture par morceaux) et de la pause / reprise des téléchargements.
const http = require("http"), fs = require("fs"), os = require("os"), path = require("path"), assert = require("assert");
process.env.FT_YTDLP = path.join(__dirname, "fake-ytdlp.sh");
const { Media } = require("../src/media");
const { Tools } = require("../src/ytdlp");
const { Downloads } = require("../src/downloads");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ftp-"));
  const f = path.join(dir, "a.m4a"); const data = Buffer.alloc(1000); for (let i = 0; i < 1000; i++) data[i] = i % 251; fs.writeFileSync(f, data);
  const m = new Media(); const t = m.add({ file: f }); const u = "ftmedia://m/" + t;
  let r = await m.handle(u, null); assert.equal(r.status, 200); assert.equal(r.headers.get("content-type"), "audio/mp4"); assert.equal((await r.arrayBuffer()).byteLength, 1000);
  r = await m.handle(u, "bytes=100-199"); assert.equal(r.status, 206); assert.equal(r.headers.get("content-range"), "bytes 100-199/1000"); assert.deepEqual(Buffer.from(await r.arrayBuffer()), data.subarray(100, 200));
  r = await m.handle(u, "bytes=900-"); assert.equal(r.status, 206); assert.equal((await r.arrayBuffer()).byteLength, 100);
  r = await m.handle(u, "bytes=-50"); assert.equal(r.status, 206); assert.deepEqual(Buffer.from(await r.arrayBuffer()), data.subarray(950));
  r = await m.handle(u, "bytes=5000-6000"); assert.equal(r.status, 416);
  r = await m.handle("ftmedia://m/inconnu", null); assert.equal(r.status, 404);
  console.log("✓ lecteur : fichier servi entier, par morceaux (Range), 404 / 416");

  const srv = http.createServer((q, s) => { const g = /bytes=(\d+)-(\d+)?/.exec(q.headers.range || ""); const a = g ? +g[1] : 0, z = g && g[2] ? +g[2] : 499; s.writeHead(g ? 206 : 200, { "Content-Type": "video/mp4", "Content-Range": "bytes " + a + "-" + z + "/500", "Content-Length": z - a + 1 }); s.end(Buffer.alloc(z - a + 1, 3)); }).listen(0);
  await new Promise((ok) => srv.on("listening", ok));
  const t2 = m.add({ url: "http://127.0.0.1:" + srv.address().port + "/v", headers: { "User-Agent": "x", Host: "bad" } });
  r = await m.handle("ftmedia://m/" + t2, "bytes=10-19"); assert.equal(r.status, 206); assert.equal(r.headers.get("content-range"), "bytes 10-19/500"); assert.equal((await r.arrayBuffer()).byteLength, 10);
  srv.close();
  console.log("✓ lecteur : flux distant relayé avec Range");

  // pause / reprise / annulation
  process.env.FAKE_SLOW = path.join(dir, "slow");
  const tools = new Tools(path.join(dir, "tools"));
  const d = new Downloads(tools, () => path.join(dir, "dl"));
  const id = d.start({ yid: "abcdefghijk", title: "Titre lent", kind: "audio" });
  await sleep(1500); assert.equal(d.list()[0].state, "en cours"); assert.ok(d.list()[0].pct >= 0.3);
  assert.ok(d.pause(id)); await sleep(1200);
  assert.equal(d.list()[0].state, "en pause"); assert.equal(d.running, 0);
  assert.ok(d.resume(id)); await sleep(2500);
  assert.equal(d.list()[0].state, "terminé"); assert.ok(fs.existsSync(d.list()[0].file));
  console.log("✓ téléchargement : pause, reprise, terminé");
  fs.unlinkSync(process.env.FAKE_SLOW + ".go");
  const id2 = d.start({ yid: "abcdefghijk", title: "Autre", kind: "audio" });
  await sleep(300); assert.ok(d.pause(id2)); assert.ok(d.cancel(id2)); await sleep(1000);
  assert.equal(d.find(id2).state, "annulé"); assert.ok(d.resume(id2)); await sleep(2500);
  assert.equal(d.find(id2).state, "terminé");
  d.clear(); assert.equal(d.list().length, 0);
  console.log("✓ téléchargement : annulation, nouvel essai, liste vidée");
  process.exit(0);
})().catch((e) => { console.error("ÉCHEC", e); process.exit(1); });
