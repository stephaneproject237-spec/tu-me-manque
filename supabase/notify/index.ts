// Fonction « notify » : envoie une notification à un téléphone.
//  - Android : via Firebase Cloud Messaging (FCM)
//  - iPhone / navigateur : via Web Push (chiffrement standard RFC 8291, signature VAPID)
// Secrets à définir dans Supabase :
//   WEBHOOK_SECRET      mot de passe partagé avec la base de données
//   FCM_SERVICE_ACCOUNT contenu du fichier JSON de clé privée Firebase
//   VAPID_PRIVATE_JWK   clé privée Web Push (JSON)
const WEBHOOK_SECRET = Deno.env.get("WEBHOOK_SECRET") ?? "";
const SA = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT") ?? "{}");
const VAPID_PRIVATE = JSON.parse(Deno.env.get("VAPID_PRIVATE_JWK") ?? "{}");
const VAPID_SUBJECT = Deno.env.get("VAPID_SUBJECT") ?? "https://stephaneproject237-spec.github.io/tu-me-manque/";

const te = new TextEncoder();

function b64url(input: ArrayBuffer | Uint8Array | string): string {
  const bytes = typeof input === "string" ? te.encode(input) : new Uint8Array(input);
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlDecode(text: string): Uint8Array {
  const pad = "=".repeat((4 - (text.length % 4)) % 4);
  const bin = atob((text + pad).replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const p of parts) {
    out.set(p, offset);
    offset += p.length;
  }
  return out;
}

const bs = (u: Uint8Array) => u as unknown as BufferSource;

// ====================== Android (FCM) ======================

function pemToDer(pem: string): ArrayBuffer {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

let cached: { token: string; exp: number } | null = null;

async function accessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp > now + 60) return cached.token;
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: SA.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(SA.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, te.encode(`${header}.${claim}`));
  const jwt = `${header}.${claim}.${b64url(signature)}`;
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt }),
  });
  const data = await res.json();
  if (!data.access_token) throw new Error("Jeton Google refusé : " + JSON.stringify(data));
  cached = { token: data.access_token, exp: now + (data.expires_in ?? 3600) };
  return cached.token;
}

async function sendFcm(token: string, title: string, body: string): Promise<number> {
  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${SA.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${await accessToken()}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      message: {
        token,
        notification: { title, body },
        android: {
          priority: "HIGH",
          notification: {
            channel_id: "miss_v2",
            sound: "tu_me_manques",
            default_sound: false,
            vibrate_timings: ["0s", "0.3s", "0.12s", "0.3s", "0.12s", "0.6s"],
            default_vibrate_timings: false,
          },
        },
      },
    }),
  });
  return res.status;
}

// ====================== iPhone / navigateur (Web Push) ======================

async function hkdf(salt: Uint8Array, ikm: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", bs(ikm), "HKDF", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: bs(salt), info: bs(info) }, key, length * 8);
  return new Uint8Array(bits);
}

/** Chiffrement « aes128gcm » des messages Web Push (RFC 8291 et RFC 8188). */
export async function encryptWebPush(
  keys: { p256dh: string; auth: string },
  plaintext: Uint8Array,
  options?: { salt?: Uint8Array; ephemeral?: CryptoKeyPair },
): Promise<Uint8Array> {
  const uaPublic = b64urlDecode(keys.p256dh);
  const authSecret = b64urlDecode(keys.auth);
  const ephemeral = options?.ephemeral ??
    (await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]));
  const asPublic = new Uint8Array(await crypto.subtle.exportKey("raw", ephemeral.publicKey));
  const uaKey = await crypto.subtle.importKey("raw", bs(uaPublic), { name: "ECDH", namedCurve: "P-256" }, false, []);
  const shared = new Uint8Array(
    await crypto.subtle.deriveBits({ name: "ECDH", public: uaKey }, ephemeral.privateKey, 256),
  );
  const salt = options?.salt ?? crypto.getRandomValues(new Uint8Array(16));

  const ikm = await hkdf(authSecret, shared, concat(te.encode("WebPush: info\0"), uaPublic, asPublic), 32);
  const cek = await hkdf(salt, ikm, te.encode("Content-Encoding: aes128gcm\0"), 16);
  const nonce = await hkdf(salt, ikm, te.encode("Content-Encoding: nonce\0"), 12);

  const aesKey = await crypto.subtle.importKey("raw", bs(cek), "AES-GCM", false, ["encrypt"]);
  const record = concat(plaintext, new Uint8Array([2])); // 0x02 = dernier (et seul) bloc
  const ciphertext = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: bs(nonce) }, aesKey, bs(record)));

  const header = new Uint8Array(16 + 4 + 1 + asPublic.length);
  header.set(salt, 0);
  new DataView(header.buffer).setUint32(16, 4096, false);
  header[20] = asPublic.length;
  header.set(asPublic, 21);
  return concat(header, ciphertext);
}

function vapidPublicKey(): string {
  return b64url(concat(new Uint8Array([4]), b64urlDecode(VAPID_PRIVATE.x), b64urlDecode(VAPID_PRIVATE.y)));
}

/** En-tête d'autorisation VAPID (RFC 8292) : prouve au service de notifications d'où vient l'envoi. */
export async function vapidAuthorization(endpoint: string): Promise<string> {
  const header = b64url(JSON.stringify({ typ: "JWT", alg: "ES256" }));
  const payload = b64url(JSON.stringify({
    aud: new URL(endpoint).origin,
    exp: Math.floor(Date.now() / 1000) + 12 * 3600,
    sub: VAPID_SUBJECT,
  }));
  const key = await crypto.subtle.importKey("jwk", VAPID_PRIVATE, { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, bs(te.encode(`${header}.${payload}`)));
  return `vapid t=${header}.${payload}.${b64url(signature)}, k=${vapidPublicKey()}`;
}

async function sendWebPush(
  sub: { endpoint: string; keys: { p256dh: string; auth: string } },
  title: string,
  body: string,
): Promise<number> {
  const message = await encryptWebPush(sub.keys, te.encode(JSON.stringify({ title, body })));
  const res = await fetch(sub.endpoint, {
    method: "POST",
    headers: {
      "Content-Encoding": "aes128gcm",
      "Content-Type": "application/octet-stream",
      TTL: "86400",
      Urgency: "high",
      Authorization: await vapidAuthorization(sub.endpoint),
    },
    body: bs(message),
  });
  return res.status;
}

// ====================== Point d'entrée ======================

async function handler(req: Request): Promise<Response> {
  if (!WEBHOOK_SECRET || req.headers.get("x-webhook-secret") !== WEBHOOK_SECRET) {
    return new Response("forbidden", { status: 403 });
  }
  const { token, web_push, title, body } = await req.json();
  const results: Record<string, unknown> = {};
  if (token) {
    try {
      results.fcm = await sendFcm(token, title, body);
    } catch (err) {
      results.fcm = String(err);
    }
  }
  if (web_push) {
    try {
      results.web = await sendWebPush(web_push, title, body);
    } catch (err) {
      results.web = String(err);
    }
  }
  return new Response(JSON.stringify(results), { status: 200, headers: { "Content-Type": "application/json" } });
}

Deno.serve(handler);
