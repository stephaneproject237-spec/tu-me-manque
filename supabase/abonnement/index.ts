// Fonction « abonnement » de Free tune : paiement Mobile Money (Orange Money / MTN MoMo) avec Campay.
// - POST {action:"pay", phone}      : demande le paiement (le client confirme sur son téléphone)
// - POST {action:"check", reference}: vérifie le paiement et active 30 jours d'abonnement
// - GET  (webhook Campay)           : Campay prévient ici quand un paiement change d'état
// On ne fait jamais confiance au message reçu : on redemande toujours l'état du paiement à Campay.
//
// Secrets à créer (Edge Functions → Secrets) : CAMPAY_USERNAME, CAMPAY_PASSWORD, CAMPAY_MODE (demo | live)
// Facultatif : CAMPAY_AMOUNT (500 par défaut ; en démo Campay n'accepte parfois que de petits montants, ex. 25).
// IMPORTANT : désactiver « Verify JWT » / « Enforce JWT verification » pour cette fonction (Campay n'a pas de compte).

const SB_URL = Deno.env.get("SUPABASE_URL")!;
const SB_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const MODE = (Deno.env.get("CAMPAY_MODE") || "demo").toLowerCase();
const CP = MODE === "live" ? "https://www.campay.net/api" : "https://demo.campay.net/api";
const AMOUNT = String(Deno.env.get("CAMPAY_AMOUNT") || "500");
const DAYS = 30;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};
const json = (o: unknown, status = 200) => new Response(JSON.stringify(o), { status, headers: { ...cors, "Content-Type": "application/json" } });

async function db(path: string, init: RequestInit = {}) {
  const r = await fetch(SB_URL + "/rest/v1/" + path, {
    ...init,
    headers: { apikey: SB_KEY, Authorization: "Bearer " + SB_KEY, "Content-Type": "application/json", Prefer: "return=representation", ...(init.headers || {}) },
  });
  const t = await r.text();
  if (!r.ok) throw new Error("db " + r.status + " " + t);
  return t ? JSON.parse(t) : null;
}

async function userOf(req: Request): Promise<string | null> {
  const auth = req.headers.get("authorization") || "";
  if (!auth.startsWith("Bearer ")) return null;
  const r = await fetch(SB_URL + "/auth/v1/user", { headers: { apikey: SB_KEY, Authorization: auth } });
  if (!r.ok) return null;
  const u = await r.json();
  return u && u.id ? u.id : null;
}

let tok: { v: string; at: number } | null = null;
async function campayToken(): Promise<string> {
  if (tok && Date.now() - tok.at < 30 * 60 * 1000) return tok.v;
  const r = await fetch(CP + "/token/", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: Deno.env.get("CAMPAY_USERNAME"), password: Deno.env.get("CAMPAY_PASSWORD") }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.token) throw new Error("Identifiants Campay refusés");
  tok = { v: j.token, at: Date.now() };
  return j.token;
}

async function campay(path: string, body?: unknown) {
  const r = await fetch(CP + path, {
    method: body ? "POST" : "GET",
    headers: { Authorization: "Token " + (await campayToken()), "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const j = await r.json().catch(() => ({}));
  return { ok: r.ok, j };
}

// Vérifie le paiement auprès de Campay ; s'il est réussi, ajoute 30 jours (une seule fois par paiement).
async function settle(reference: string) {
  const rows = await db("c_payments?select=*&reference=eq." + encodeURIComponent(reference));
  const p = rows && rows[0];
  if (!p) return { status: "unknown" };
  if (p.status !== "pending") return { status: p.status };
  const { j } = await campay("/transaction/" + encodeURIComponent(reference) + "/");
  const st = String(j.status || "").toUpperCase();
  if (st === "SUCCESSFUL") {
    // Passe le paiement à « successful » seulement s'il était encore « pending » (pas de double activation).
    const upd = await db("c_payments?id=eq." + p.id + "&status=eq.pending", { method: "PATCH", body: JSON.stringify({ status: "successful", done_at: new Date().toISOString() }) });
    if (upd && upd.length) {
      const subs = await db("c_subs?select=paid_until&user_id=eq." + p.user_id);
      const cur = subs && subs[0] && subs[0].paid_until ? new Date(subs[0].paid_until).getTime() : 0;
      const from = Math.max(cur, Date.now());
      const until = new Date(from + DAYS * 86400000).toISOString();
      await db("c_subs", { method: "POST", headers: { Prefer: "resolution=merge-duplicates,return=representation" }, body: JSON.stringify({ user_id: p.user_id, paid_until: until, updated_at: new Date().toISOString() }) });
      return { status: "successful", paid_until: until };
    }
    return { status: "successful" };
  }
  if (st === "FAILED") {
    await db("c_payments?id=eq." + p.id + "&status=eq.pending", { method: "PATCH", body: JSON.stringify({ status: "failed", done_at: new Date().toISOString() }) });
    return { status: "failed" };
  }
  return { status: "pending" };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  try {
    // Webhook de Campay : ?reference=…&status=…
    if (req.method === "GET") {
      const ref = new URL(req.url).searchParams.get("reference");
      if (ref) await settle(ref);
      return json({ ok: true });
    }
    const body = await req.json().catch(() => ({}));
    if (!body.action && body.reference) { await settle(String(body.reference)); return json({ ok: true }); } // webhook en POST

    const uid = await userOf(req);
    if (!uid) return json({ status: "unauthenticated" }, 401);

    if (body.action === "pay") {
      const digits = String(body.phone || "").replace(/\D/g, "").replace(/^00/, "").replace(/^237/, "");
      if (!/^6\d{8}$/.test(digits)) return json({ status: "bad_phone" });
      const ext = crypto.randomUUID();
      const { ok, j } = await campay("/collect/", { amount: AMOUNT, currency: "XAF", from: "237" + digits, description: "Abonnement Free tune " + DAYS + " jours", external_reference: ext });
      if (!ok || !j.reference) return json({ status: "refused", message: j.message || j.detail || "Paiement refusé par Campay" });
      await db("c_payments", { method: "POST", body: JSON.stringify({ user_id: uid, reference: j.reference, ext_ref: ext, phone: digits, amount: Number(AMOUNT) }) });
      return json({ status: "pending", reference: j.reference, ussd_code: j.ussd_code || "", operator: j.operator || "" });
    }
    if (body.action === "check") {
      const rows = await db("c_payments?select=user_id&reference=eq." + encodeURIComponent(String(body.reference || "")));
      if (!rows || !rows[0] || rows[0].user_id !== uid) return json({ status: "unknown" });
      return json(await settle(String(body.reference)));
    }
    return json({ status: "bad_request" }, 400);
  } catch (e) {
    return json({ status: "error", message: String((e as Error).message || e) }, 500);
  }
});
