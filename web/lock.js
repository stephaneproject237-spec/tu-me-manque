// Verrouillage de l'appli par Face ID / Touch ID / empreinte (WebAuthn, local à l'appareil).
(function () {
  const KEY = "tmm_lock";
  const GRACE = 30000;
  const b64 = (buf) => btoa(String.fromCharCode.apply(null, new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const unb64 = (s) => { s = s.replace(/-/g, "+").replace(/_/g, "/"); while (s.length % 4) s += "="; return Uint8Array.from(atob(s), (c) => c.charCodeAt(0)); };
  const rnd = (n) => crypto.getRandomValues(new Uint8Array(n));
  const get = () => { try { return localStorage.getItem(KEY); } catch (e) { return null; } };
  const set = (v) => { try { if (v) localStorage.setItem(KEY, v); else localStorage.removeItem(KEY); } catch (e) { /* ignoré */ } };

  async function supported() {
    try {
      return !!(window.PublicKeyCredential && navigator.credentials && await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable());
    } catch (e) { return false; }
  }

  async function verify() {
    const id = get();
    if (!id) return true;
    try {
      const r = await navigator.credentials.get({ publicKey: {
        challenge: rnd(32), timeout: 60000, userVerification: "required",
        allowCredentials: [{ type: "public-key", id: unb64(id), transports: ["internal"] }],
      } });
      return !!r;
    } catch (e) { return false; }
  }

  async function enable() {
    if (!(await supported())) return "unsupported";
    try {
      const c = await navigator.credentials.create({ publicKey: {
        rp: { name: "Tu me manques" },
        user: { id: rnd(16), name: "tumemanque", displayName: "Tu me manques" },
        challenge: rnd(32), timeout: 60000,
        pubKeyCredParams: [{ type: "public-key", alg: -7 }, { type: "public-key", alg: -257 }],
        authenticatorSelection: { authenticatorAttachment: "platform", userVerification: "required", residentKey: "discouraged" },
      } });
      if (!c) return "cancel";
      set(b64(c.rawId));
      return "ok";
    } catch (e) { return "cancel"; }
  }

  async function disable() {
    if (!(await verify())) return false;
    set(null);
    return true;
  }

  // ----- Écran de verrouillage -----
  let el = null, busy = false;
  function build() {
    if (el) return el;
    el = document.createElement("div");
    el.id = "tmm-lock";
    el.innerHTML = '<div class="lk-box"><div class="lk-heart">💗</div><h2>Tu me manques est verrouillée</h2>' +
      '<p>Déverrouille avec Face ID, Touch ID ou l\'empreinte de ton téléphone.</p>' +
      '<button class="principal" id="tmm-unlock">🔓 Déverrouiller</button></div>';
    document.body.appendChild(el);
    el.querySelector("#tmm-unlock").addEventListener("click", ask);
    return el;
  }
  async function ask() {
    if (busy) return;
    busy = true;
    const ok = await verify();
    busy = false;
    if (ok && el) { el.remove(); el = null; document.documentElement.classList.remove("tmm-locked"); }
  }
  function lock() {
    if (!get()) return;
    document.documentElement.classList.add("tmm-locked");
    const go = () => { build(); ask(); };
    if (document.body) go(); else document.addEventListener("DOMContentLoaded", go);
  }

  let hiddenAt = 0;
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) hiddenAt = Date.now();
    else if (hiddenAt && get() && Date.now() - hiddenAt > GRACE && !el) lock();
  });

  window.TMM_LOCK = { supported, enabled: () => !!get(), enable, disable, lock };
  lock();
})();
