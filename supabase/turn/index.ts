// Fonction « turn » : donne aux téléphones un relais TURN Cloudflare (gratuit) pour que les appels passent partout.
// Secrets à définir dans Supabase : CF_TURN_KEY_ID et CF_TURN_API_TOKEN (créés dans Cloudflare → Realtime → TURN Server).
// Appel : GET /functions/v1/turn   (il faut être connecté)
const KEY_ID = Deno.env.get("CF_TURN_KEY_ID") ?? "";
const API_TOKEN = Deno.env.get("CF_TURN_API_TOKEN") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

function reply(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

async function isSignedIn(req: Request): Promise<boolean> {
  const auth = req.headers.get("authorization");
  const apikey = req.headers.get("apikey");
  if (!auth || !apikey) return false;
  const res = await fetch(`${SUPABASE_URL}/auth/v1/user`, { headers: { Authorization: auth, apikey } });
  return res.ok;
}

// Les identifiants valent 24 h ; on en garde un en mémoire 1 h pour ne pas solliciter Cloudflare à chaque appel.
let cache: { at: number; data: unknown } | null = null;

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (!KEY_ID || !API_TOKEN) return reply({ error: "not_configured" }, 503);
  if (!(await isSignedIn(req))) return reply({ error: "unauthorized" }, 401);
  if (cache && Date.now() - cache.at < 3600_000) return reply(cache.data);
  const res = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${KEY_ID}/credentials/generate-ice-servers`, {
    method: "POST",
    headers: { Authorization: `Bearer ${API_TOKEN}`, "Content-Type": "application/json" },
    body: JSON.stringify({ ttl: 86400 }),
  });
  if (!res.ok) return reply({ error: "cloudflare_" + res.status }, 502);
  const data = await res.json();
  cache = { at: Date.now(), data };
  return reply(data);
});
