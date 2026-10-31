// Connexion à Supabase pour un compte serveur (pseudo + code) : session, appels de fonctions, envoi de fichiers.
const crypto = require("crypto");
const cfg = require("./config");

class Client {
  constructor(account, store, opts) {
    this.acc = account;                       // { username, code }
    this.store = store;
    this.url = (opts && opts.url) || cfg.SUPABASE_URL;
    this.anon = (opts && opts.anon) || cfg.SUPABASE_ANON_KEY;
    this.session = account.session || null;   // { access, refresh, expires, uid }
    this.loginP = null;
  }
  get email() { return this.acc.username + "@" + cfg.EMAIL_DOMAIN; }

  async _http(method, path, body, token, extra) {
    const headers = Object.assign({ apikey: this.anon, "Content-Type": "application/json", Accept: "application/json" }, extra || {});
    if (token) headers.Authorization = "Bearer " + token;
    const res = await fetch(this.url + path, { method, headers, body: body == null ? undefined : (Buffer.isBuffer(body) ? body : JSON.stringify(body)), signal: AbortSignal.timeout(60000) });
    return { code: res.status, text: await res.text() };
  }
  _save(j) {
    const now = Math.floor(Date.now() / 1000);
    this.session = { access: j.access_token, refresh: j.refresh_token, expires: j.expires_at || now + (j.expires_in || 3600), uid: j.user && j.user.id };
    if (this.store) this.store.setSession(this.acc.username, this.session);
  }
  async login() {
    const r = await this._http("POST", "/auth/v1/token?grant_type=password", { email: this.email, password: this.acc.code });
    if (r.code < 200 || r.code >= 300) { let m = r.text; try { m = JSON.parse(r.text).error_description || JSON.parse(r.text).msg || m; } catch (e) { /* ignoré */ } const e = new Error(m); e.code = r.code; throw e; }
    this._save(JSON.parse(r.text));
  }
  async ensure(force) {
    if (this.loginP) return this.loginP;
    this.loginP = (async () => {
      try {
        const now = Math.floor(Date.now() / 1000);
        if (!force && this.session && this.session.expires > now + 60) return;
        if (this.session && this.session.refresh) {
          const r = await this._http("POST", "/auth/v1/token?grant_type=refresh_token", { refresh_token: this.session.refresh });
          if (r.code >= 200 && r.code < 300) { this._save(JSON.parse(r.text)); return; }
        }
        await this.login();
      } finally { this.loginP = null; }
    })();
    return this.loginP;
  }
  get uid() { return this.session && this.session.uid; }

  async authed(method, path, body, extra) {
    await this.ensure(false);
    let r = await this._http(method, path, body, this.session.access, extra);
    if (r.code === 401) { await this.ensure(true); r = await this._http(method, path, body, this.session.access, extra); }
    if (r.code < 200 || r.code >= 300) throw new Error("HTTP " + r.code + " : " + r.text.slice(0, 200));
    return r.text ? JSON.parse(r.text) : null;
  }
  // Les fonctions de Free tune portent le préfixe c_ dans la base.
  rpc(name, args) { return this.authed("POST", "/rest/v1/rpc/c_" + name, args || {}); }

  /** Envoie un fichier (Buffer) dans le stockage public « chat », dans le dossier du compte. Renvoie le chemin. */
  async upload(buf, ext, mime) {
    await this.ensure(false);
    const path = this.uid + "/" + crypto.randomBytes(16).toString("hex") + "." + ext;
    const go = () => this._http("POST", "/storage/v1/object/chat/" + path, buf, this.session.access, { "Content-Type": mime, "x-upsert": "false" });
    let r = await go();
    if (r.code === 401) { await this.ensure(true); r = await go(); }
    if (r.code < 200 || r.code >= 300) throw new Error("envoi refusé (" + r.code + ")");
    return path;
  }
}

/** Création d'un compte serveur (sans clé : l'installation est identifiée par un numéro, 30 serveurs au maximum). */
async function registerAuto(device, username, code, opts, platform) {
  const url = (opts && opts.url) || cfg.SUPABASE_URL, anon = (opts && opts.anon) || cfg.SUPABASE_ANON_KEY;
  const res = await fetch(url + "/rest/v1/rpc/srv_auto", {
    method: "POST", headers: { apikey: anon, "Content-Type": "application/json" },
    body: JSON.stringify({ p_device: device, p_username: username, p_code: code, p_platform: platform || "windows" }), signal: AbortSignal.timeout(30000),
  });
  const t = await res.text();
  if (!res.ok) throw new Error("HTTP " + res.status + " : " + t.slice(0, 160));
  return JSON.parse(t).status;
}
module.exports = { Client, registerAuto };
