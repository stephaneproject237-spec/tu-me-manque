const { app, BrowserWindow, ipcMain, dialog, shell, Tray, Menu, nativeImage, safeStorage, protocol } = require("electron");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { Store } = require("./src/store");
const { Tools } = require("./src/ytdlp");
const { Engine } = require("./src/engine");
const { Downloads } = require("./src/downloads");
const { Media, MIME } = require("./src/media");
const Y = require("./src/ytdlp");
const { register, Client } = require("./src/supa");
const Upd = require("./src/updater");
const cfg = require("./src/config");
const MAC = process.platform === "darwin";
const PLAT = MAC ? "mac" : "windows";

if (!app.requestSingleInstanceLock()) { app.quit(); }
protocol.registerSchemesAsPrivileged([{ scheme: "ftmedia", privileges: { standard: true, secure: true, stream: true, supportFetchAPI: true } }]);

let win = null, tray = null, quitting = false, upd = null, updMsg = "";
const crypt = {
  enc: (s) => (safeStorage.isEncryptionAvailable() ? "e:" + safeStorage.encryptString(s).toString("base64") : "p:" + Buffer.from(s, "utf8").toString("base64")),
  dec: (s) => (s.startsWith("e:") ? safeStorage.decryptString(Buffer.from(s.slice(2), "base64")) : Buffer.from(s.replace(/^p:/, ""), "base64").toString("utf8")),
};

app.whenReady().then(() => {
  const ud = app.getPath("userData");
  const store = new Store(ud, crypt);
  const tools = new Tools(path.join(ud, "tools"), null, MAC ? path.join(process.resourcesPath, "bin") : undefined);
  const engine = new Engine(store, tools, { version: app.getVersion(), platform: PLAT, tmpDir: path.join(app.getPath("temp"), "freetune-windows") });
  const dlDir = () => store.settings.dlDir || path.join(app.getPath("music"), "Free tune");
  const downloads = new Downloads(tools, dlDir);
  const media = new Media();
  protocol.handle("ftmedia", (req) => media.handle(req.url, req.headers.get("range")).catch(() => new Response("", { status: 502 })));

  const snapshot = () => Object.assign(engine.snapshot(), { unlocked: unlocked(), settings: Object.assign({}, store.settings, { dlDirShown: dlDir() }), downloads: downloads.list(), version: app.getVersion(), update: upd, updMsg, hasFfmpeg: tools.hasFfmpeg(), platform: PLAT });
  let t = null;
  const push = () => { if (t || !win || win.isDestroyed()) return; t = setTimeout(() => { t = null; if (win && !win.isDestroyed()) win.webContents.send("state", snapshot()); }, 400); };
  engine.on("update", push); downloads.on("update", push);

  const applyAutostart = () => { try { app.setLoginItemSettings({ openAtLogin: !!store.settings.autostart, args: ["--hidden"] }); } catch (e) { /* ignoré */ } };
  applyAutostart();

  const show = () => { if (!win) return; if (win.isMinimized()) win.restore(); win.show(); win.focus(); };
  win = new BrowserWindow({
    width: 1100, height: 780, minWidth: 820, minHeight: 600, show: false, backgroundColor: "#0A0D13", title: "Free tune Windows", autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, "preload.js"), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, "renderer", "index.html"));
  if (!process.argv.includes("--hidden")) win.once("ready-to-show", show);
  win.on("close", (e) => { if (!quitting) { e.preventDefault(); win.hide(); } });   // reste en ligne en arrière-plan
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https:\/\//.test(url)) shell.openExternal(url); return { action: "deny" }; });
  win.webContents.on("will-navigate", (e) => e.preventDefault());

  try {
    tray = new Tray(nativeImage.createFromPath(path.join(__dirname, "icon.png")).resize({ width: 16, height: 16 }));
    tray.setToolTip("Free tune : comptes en ligne");
    tray.setContextMenu(Menu.buildFromTemplate([{ label: "Ouvrir", click: show }, { label: "Quitter (les comptes passent hors ligne)", click: () => { quitting = true; app.quit(); } }]));
    tray.on("click", show);
  } catch (e) { /* pas d'icône dans la zone de notification */ }
  app.on("second-instance", show);
  app.on("before-quit", () => { quitting = true; engine.stopAll(); });

  const rndCode = () => crypto.randomBytes(9).toString("base64").replace(/[+/=]/g, "x").slice(0, 12);
  // La clé d'activation est installée automatiquement avec le logiciel : rien à saisir.
  const MSG = { bad_key: "Clé d'activation incorrecte (contacte le support).", rate_limited: "Trop de créations d'un coup : attends quelques minutes.", invalid: "Pseudo invalide : 3 à 20 caractères (a-z, 0-9, _).", bad_code: "Code trop court (8 caractères minimum).", taken: "Ce pseudo existe déjà." };
  async function createAccount(username, code) {
    const u = String(username || "").toLowerCase().trim();
    let st;
    try { st = await register(cfg.ENROLL_KEY, u, code, undefined, PLAT); } catch (e) { return { ok: false, msg: "Impossible de joindre le serveur : " + e.message }; }
    if (st === "taken") {   // compte déjà créé auparavant : on l'ajoute si le code est le bon
      try { await new Client({ username: u, code }, null).login(); } catch (e) { return { ok: false, msg: MSG.taken + " Si c'est un de tes comptes, vérifie son code." }; }
    } else if (st !== "ok") return { ok: false, msg: MSG[st] || "Création refusée (" + st + ")." };
    store.addAccount(u, code);
    engine.add({ username: u, code, share: true, session: null });
    return { ok: true, username: u, code };
  }

  // Réglages : protégés par un mot de passe (par défaut 4580), valable 15 minutes.
  const hashPw = (pw) => crypto.createHash("sha256").update("ft-adm:" + String(pw)).digest("hex");
  const DEFAULT_HASH = hashPw("4580");
  let unlockedUntil = 0;
  const unlocked = () => Date.now() < unlockedUntil;
  const need = () => { if (!unlocked()) throw new Error("verrouillé"); unlockedUntil = Date.now() + 15 * 60000; };

  ipcMain.handle("state", () => snapshot());
  ipcMain.handle("adm:unlock", (_e, a) => { const ok = hashPw(a && a.pw) === (store.settings.admHash || DEFAULT_HASH); unlockedUntil = ok ? Date.now() + 15 * 60000 : 0; push(); return { ok }; });
  ipcMain.handle("adm:lock", () => { unlockedUntil = 0; push(); return true; });
  ipcMain.handle("adm:setpw", (_e, a) => {
    if (!unlocked()) return { ok: false, msg: "Verrouillé" };
    if (hashPw(a && a.old) !== (store.settings.admHash || DEFAULT_HASH)) return { ok: false, msg: "Ancien mot de passe incorrect" };
    const n = String((a && a.pw) || ""); if (n.length < 4 || n.length > 60) return { ok: false, msg: "Nouveau mot de passe : 4 caractères minimum" };
    store.settings.admHash = hashPw(n); store.save(); return { ok: true };
  });
  ipcMain.handle("acc:add", (_e, a) => createAccount(a && a.username, a && a.code));
  ipcMain.handle("acc:bulk", async (_e, a) => {
    const prefix = String((a && a.prefix) || "").toLowerCase().replace(/[^a-z0-9_]/g, "").slice(0, 14);
    const n = Math.max(1, Math.min(30, +(a && a.count) || 1));
    const out = [];
    for (let i = 1; i <= n; i++) {
      const r = await createAccount(prefix + String(i).padStart(2, "0"), rndCode());
      out.push(Object.assign({ username: prefix + String(i).padStart(2, "0") }, r));
      if (!r.ok && /Clé|Impossible|Trop/.test(r.msg || "")) break;
    }
    return out;
  });
  ipcMain.handle("acc:remove", (_e, u) => { engine.remove(String(u)); return true; });
  ipcMain.handle("acc:share", (_e, a) => { engine.setShare(String(a.username), !!a.on); return true; });
  ipcMain.handle("acc:code", (_e, u) => { const a = store.accounts().find((x) => x.username === u); return a ? a.code : ""; });
  ipcMain.handle("settings:set", (_e, s) => {
    need();
    const p = store.settings;
    if (s.maxJobs != null) p.maxJobs = Math.max(1, Math.min(12, +s.maxJobs || 3));
    if (s.autostart != null) { p.autostart = !!s.autostart; applyAutostart(); }
    if (s.autoUpdate != null) { p.autoUpdate = !!s.autoUpdate; if (p.autoUpdate) setTimeout(autoInstall, 1000); }
    store.save(); push(); return true;
  });
  ipcMain.handle("settings:dir", async () => {
    need();
    const r = await dialog.showOpenDialog(win, { properties: ["openDirectory", "createDirectory"] });
    if (!r.canceled && r.filePaths[0]) { store.settings.dlDir = r.filePaths[0]; store.save(); push(); }
    return true;
  });
  ipcMain.handle("search:yt", async (_e, q) => { try { return { ok: true, list: await tools.search(String(q || ""), 15) }; } catch (e) { return { ok: false, msg: String(e.message || e).slice(0, 160) }; } });
  ipcMain.handle("dl:start", (_e, o) => downloads.start(o || {}));
  ipcMain.handle("dl:ctl", (_e, a) => {
    const id = a && a.id;
    if (a && a.op === "pause") return downloads.pause(id);
    if (a && a.op === "resume") return downloads.resume(id);
    if (a && a.op === "cancel") return downloads.cancel(id);
    if (a && a.op === "clear") { downloads.clear(); return true; }
    return false;
  });
  // Lecteur intégré : fichier déjà téléchargé (dossier de téléchargement uniquement) ou lecture directe depuis YouTube.
  ipcMain.handle("play:file", (_e, f) => {
    try {
      const file = path.resolve(String(f || "")), root = path.resolve(dlDir());
      const known = downloads.list().some((x) => x.file && path.resolve(x.file) === file);
      if (!(known || file.startsWith(root + path.sep)) || !MIME[path.extname(file).toLowerCase()] || !fs.existsSync(file)) return { ok: false, msg: "Fichier introuvable (déplacé ou supprimé ?)" };
      const video = /^video\//.test(MIME[path.extname(file).toLowerCase()]);
      return { ok: true, src: "ftmedia://m/" + media.add({ file }), video };
    } catch (e) { return { ok: false, msg: String(e.message || e).slice(0, 140) }; }
  });
  ipcMain.handle("play:yt", async (_e, o) => {
    try {
      let yid = String((o && o.yid) || "");
      if (!/^[A-Za-z0-9_-]{11}$/.test(yid)) {   // titre d'album : on cherche la meilleure vidéo correspondante
        const r = await tools.search((String((o && o.artist) || "") + " " + String((o && o.title) || "")).trim(), 8);
        if (!r.length) return { ok: false, msg: "Introuvable sur YouTube" };
        const dur = +(o && o.dur) || 0;
        yid = ((dur ? r.find((x) => Math.abs(x.duration - dur) <= 12) : null) || r[0]).id;
      }
      const video = !!(o && o.video);
      const m = await Y.resolveMedia(tools, yid, video);
      return { ok: true, src: "ftmedia://m/" + media.add({ url: m.url, headers: m.headers }), video, yid, duration: m.duration };
    } catch (e) { return { ok: false, msg: String(e.message || e).split("\n").pop().slice(0, 160) }; }
  });
  ipcMain.handle("dl:show", (_e, f) => { if (f) shell.showItemInFolder(String(f)); else shell.openPath(dlDir()); return true; });
  ipcMain.handle("open:url", (_e, u) => { if (/^https:\/\//.test(String(u))) shell.openExternal(String(u)); return true; });

  engine.startAll();

  // Nouvelle version ? (on regarde les publications GitHub « win-<numéro> » / « mac-<numéro> »), et on l'installe toute seule.
  const check = async () => {
    try {
      const cur = +String(app.getVersion()).split(".")[1] || 0;
      upd = await Upd.findUpdate(cfg.REPO, MAC, cur, process.arch);
      push();
      if (upd) autoInstall();
    } catch (e) { /* hors ligne */ }
    return upd;
  };
  let installing = false;
  async function doInstall() {
    if (!upd || installing) return false;
    installing = true; updMsg = "Téléchargement de la mise à jour…"; push();
    try {
      const r = await Upd.install(upd, { mac: MAC, execPath: process.execPath, portablePath: process.env.PORTABLE_EXECUTABLE_FILE, tmpDir: path.join(app.getPath("temp"), "freetune-update") });
      if (!r.ok) { updMsg = "Installation automatique impossible (" + r.msg + "). Télécharge la nouvelle version depuis le bouton."; installing = false; push(); return false; }
      updMsg = "Redémarrage…"; push();
      setTimeout(() => { quitting = true; engine.stopAll(); app.quit(); }, 600);
      return true;
    } catch (e) { updMsg = "Mise à jour impossible : " + String(e.message || e).slice(0, 100); installing = false; push(); return false; }
  }
  // Mise à jour automatique : on attend qu'aucune demande ni téléchargement ne soit en cours avant de redémarrer.
  const idle = () => engine.running === 0 && !downloads.list().some((d) => d.state === "en cours" || d.state === "attente");
  function autoInstall() {
    if (!upd || store.settings.autoUpdate === false || installing) return;
    if (idle()) doInstall(); else setTimeout(autoInstall, 60000);
  }
  ipcMain.handle("upd:check", async () => { updMsg = ""; const u = await check(); if (!u) { updMsg = "Free tune est à jour."; push(); } return !!u; });
  ipcMain.handle("upd:install", () => doInstall());
  check(); setInterval(check, 2 * 3600 * 1000);
});
app.on("window-all-closed", () => { /* on reste en arrière-plan */ });
app.on("activate", () => { const w = BrowserWindow.getAllWindows()[0]; if (w) { w.show(); w.focus(); } });
