// Petit fichier JSON dans le dossier de l'application : comptes serveur et réglages.
const fs = require("fs");
const path = require("path");

class Store {
  constructor(dir, crypt) {
    this.file = path.join(dir, "freetune-windows.json");
    this.crypt = crypt || { enc: (s) => Buffer.from(s, "utf8").toString("base64"), dec: (s) => Buffer.from(s, "base64").toString("utf8") };
    this.data = { settings: {}, accounts: [] };
    try { this.data = Object.assign(this.data, JSON.parse(fs.readFileSync(this.file, "utf8"))); } catch (e) { /* premier lancement */ }
    this.data.settings = Object.assign({ enrollKey: "", dlDir: "", maxJobs: 3, autostart: true }, this.data.settings);
  }
  save() {
    try { fs.mkdirSync(path.dirname(this.file), { recursive: true }); fs.writeFileSync(this.file + ".tmp", JSON.stringify(this.data)); fs.renameSync(this.file + ".tmp", this.file); } catch (e) { /* disque plein : on réessaiera */ }
  }
  get settings() { return this.data.settings; }
  accounts() { return this.data.accounts.map((a) => ({ username: a.username, code: this.crypt.dec(a.code), share: a.share !== false, session: a.session || null })); }
  addAccount(username, code) {
    this.data.accounts = this.data.accounts.filter((a) => a.username !== username);
    this.data.accounts.push({ username, code: this.crypt.enc(code), share: true, session: null });
    this.save();
  }
  removeAccount(username) { this.data.accounts = this.data.accounts.filter((a) => a.username !== username); this.save(); }
  setShare(username, on) { const a = this.data.accounts.find((x) => x.username === username); if (a) { a.share = !!on; this.save(); } }
  setSession(username, s) { const a = this.data.accounts.find((x) => x.username === username); if (a) { a.session = s; this.save(); } }
}
module.exports = { Store };
