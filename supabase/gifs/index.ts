// Fonction « gifs » : recherche de GIF (GIPHY) pour le chat, sans exposer la clé aux téléphones.
// Secret à définir dans Supabase : GIPHY_API_KEY (clé gratuite sur developers.giphy.com).
// Appel : GET /functions/v1/gifs?q=chat&offset=0   (sans q : GIF du moment)
const GIPHY_KEY = Deno.env.get("GIPHY_API_KEY") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
};

function reply(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

// Petite mémoire pour économiser les appels gratuits (quota limité chez GIPHY).
const cache = new Map<string, { at: number; data: unknown }>();
const TTL_MS = 10 * 60 * 1000;

async function isSignedIn(req: Request): Promise<boolean> {
  const auth = req.headers.get("authorization");
  const apikey = req.headers.get("apikey");
  if (!auth || !apikey) return false;
  const res = await fetch(`${SUPABASE_URL}/auth/v1/user`, { headers: { Authorization: auth, apikey } });
  return res.ok;
}

// deno-lint-ignore no-explicit-any
function simplify(item: any) {
  const full = item?.images?.fixed_width;
  const small = item?.images?.fixed_width_small ?? full;
  if (!full?.url) return null;
  return {
    id: String(item.id),
    url: String(full.url),
    preview: String(small?.url ?? full.url),
    w: Number(full.width) || 200,
    h: Number(full.height) || 200,
  };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (!GIPHY_KEY) return reply({ error: "GIPHY_API_KEY manquante" }, 500);
  if (!(await isSignedIn(req))) return reply({ error: "non autorisé" }, 401);

  const url = new URL(req.url);
  const q = (url.searchParams.get("q") ?? "").trim().slice(0, 60);
  const offset = Math.max(0, Math.min(500, Number(url.searchParams.get("offset") ?? "0") || 0));
  const limit = 24;

  const cacheKey = `${q}|${offset}`;
  const hit = cache.get(cacheKey);
  if (hit && Date.now() - hit.at < TTL_MS) return reply(hit.data);

  const endpoint = q ? "search" : "trending";
  const params = new URLSearchParams({
    api_key: GIPHY_KEY,
    limit: String(limit),
    offset: String(offset),
    rating: "pg-13",
    lang: "fr",
  });
  if (q) params.set("q", q);

  try {
    const res = await fetch(`https://api.giphy.com/v1/gifs/${endpoint}?${params}`);
    if (!res.ok) return reply({ error: "GIPHY a répondu " + res.status }, 502);
    const json = await res.json();
    const results = (json.data ?? []).map(simplify).filter(Boolean);
    const data = { results, next: results.length === limit ? offset + limit : null };
    cache.set(cacheKey, { at: Date.now(), data });
    if (cache.size > 200) cache.delete(cache.keys().next().value as string);
    return reply(data);
  } catch (err) {
    return reply({ error: String(err) }, 502);
  }
});
