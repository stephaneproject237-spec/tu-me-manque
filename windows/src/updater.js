// Mises à jour automatiques : télécharge la nouvelle version, remplace l'application puis la relance.
// Windows : l'exécutable portable est remplacé par un petit script qui attend la fermeture. Mac : le .app est remplacé de la même façon.
// (Les fichiers téléchargés par le logiciel lui-même ne reçoivent pas la « quarantaine » de macOS : aucun avertissement au relancement.)
const fs = require("fs");
const path = require("path");
const { spawn, execFileSync } = require("child_process");

/** Cherche la dernière publication « win-N » / « mac-N » plus récente que la version en cours. */
async function findUpdate(repo, mac, cur, arch, fetchFn) {
  const f = fetchFn || fetch;
  const r = await f("https://api.github.com/repos/" + repo + "/releases?per_page=30", { headers: { Accept: "application/vnd.github+json" }, signal: AbortSignal.timeout(20000) });
  const l = await r.json();
  let best = null;
  for (const x of Array.isArray(l) ? l : []) {
    const m = new RegExp("^" + (mac ? "mac" : "win") + "-(\\d+)$").exec(x.tag_name || "");
    if (!m || x.draft || +m[1] <= cur || (best && +m[1] <= best.n)) continue;
    const a = (x.assets || []).find((y) => (mac ? /\.zip$/ : /\.exe$/).test(y.name) && (!mac || y.name.includes(arch === "arm64" ? "arm64" : "x64")));
    if (a) best = { n: +m[1], url: a.browser_download_url, size: a.size || 0 };
  }
  return best;
}

async function download(url, dest, fetchFn) {
  const res = await (fetchFn || fetch)(url, { redirect: "follow" });
  if (!res.ok) throw new Error("téléchargement refusé (" + res.status + ")");
  const buf = Buffer.from(await res.arrayBuffer());
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, buf);
  return buf.length;
}

const q = (s) => '"' + String(s).replace(/"/g, "") + '"';
function winScript(neu, cur) {
  return ["@echo off", "ping 127.0.0.1 -n 4 >nul", "move /y " + q(neu) + " " + q(cur) + " >nul", "if errorlevel 1 (ping 127.0.0.1 -n 5 >nul & move /y " + q(neu) + " " + q(cur) + " >nul)", "start \"\" " + q(cur), "del \"%~f0\""].join("\r\n") + "\r\n";
}
function macScript(neu, cur) {
  return ["#!/bin/bash", "sleep 3", "rm -rf " + q(cur), "mv " + q(neu) + " " + q(cur), "xattr -cr " + q(cur) + " 2>/dev/null", "open " + q(cur), 'rm -- "$0"'].join("\n") + "\n";
}

/** Installe la mise à jour. Renvoie { ok } ; en cas de succès l'appelant doit quitter l'application. */
async function install(info, o) {
  const tmp = o.tmpDir;
  fs.mkdirSync(tmp, { recursive: true });
  if (o.mac) {
    const cur = path.resolve(o.execPath, "../../..");
    if (!/\.app$/.test(cur)) return { ok: false, msg: "application introuvable" };
    const zip = path.join(tmp, "update.zip"), out = path.join(tmp, "update-app");
    fs.rmSync(out, { recursive: true, force: true }); fs.mkdirSync(out, { recursive: true });
    await download(info.url, zip, o.fetch);
    execFileSync("/usr/bin/ditto", ["-x", "-k", zip, out]);
    const app = fs.readdirSync(out).find((x) => x.endsWith(".app"));
    if (!app) return { ok: false, msg: "archive invalide" };
    try { fs.accessSync(path.dirname(cur), fs.constants.W_OK); } catch (e) { return { ok: false, msg: "dossier protégé" }; }
    const sh = path.join(tmp, "update.sh");
    fs.writeFileSync(sh, macScript(path.join(out, app), cur), { mode: 0o755 });
    spawn("/bin/bash", [sh], { detached: true, stdio: "ignore" }).unref();
    return { ok: true };
  }
  const cur = o.portablePath;
  if (!cur) return { ok: false, msg: "version non portable" };
  const neu = path.join(path.dirname(cur), "FreeTune-update.exe");
  const n = await download(info.url, neu, o.fetch);
  const head = fs.readFileSync(neu).subarray(0, 2).toString();
  if (n < 20 * 1024 * 1024 || head !== "MZ") { try { fs.unlinkSync(neu); } catch (e) { /* ignoré */ } return { ok: false, msg: "fichier invalide" }; }
  const bat = path.join(tmp, "update.bat");
  fs.writeFileSync(bat, winScript(neu, cur));
  spawn("cmd.exe", ["/c", bat], { detached: true, stdio: "ignore", windowsHide: true }).unref();
  return { ok: true };
}
module.exports = { findUpdate, install, winScript, macScript, download };
