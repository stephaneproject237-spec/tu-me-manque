// Service worker : notifications + mémoire locale (l'appli s'ouvre vite même avec un réseau lent).
const CACHE = "casque-cdd5d43b";
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(
  caches.keys().then((ks) => Promise.all(ks.filter((k) => k.startsWith("casque-") && k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())
));

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;
  if (url.pathname.indexOf("/casque/") >= 0) return; // « Casque » a son propre service worker
  if (url.pathname.indexOf("/telecharger/") >= 0) return;
  const isPage = req.mode === "navigate" || url.pathname.endsWith("/") || url.pathname.endsWith("index.html");
  if (isPage) {
    // Page d'accueil : le réseau d'abord (3 s max), sinon la copie gardée.
    event.respondWith((async () => {
      const cache = await caches.open(CACHE);
      try {
        const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 3000);
        const r = await fetch(req, { signal: ctl.signal }); clearTimeout(t);
        if (r.ok) cache.put("index", r.clone());
        return r;
      } catch (e) {
        return (await cache.match("index")) || (await cache.match(req)) || Response.error();
      }
    })());
    return;
  }
  if (/\/(dist|icons)\//.test(url.pathname) || /\.(png|jpg|svg|json|webmanifest)$/.test(url.pathname)) {
    // Fichiers de l'appli : la copie gardée d'abord, mise à jour en arrière-plan.
    event.respondWith((async () => {
      const cache = await caches.open(CACHE);
      const hit = await cache.match(req);
      const net = fetch(req).then((r) => { if (r.ok) cache.put(req, r.clone()); return r; }).catch(() => null);
      return hit || (await net) || Response.error();
    })());
  }
});

self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = { body: event.data ? event.data.text() : "" };
  }
  const title = data.title || "Message";
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || "",
      icon: "icons/icon-192.png",
      badge: "icons/icon-192.png",
      vibrate: [300, 120, 300, 120, 600],
    })
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
      for (const client of list) {
        if ("focus" in client) return client.focus();
      }
      return self.clients.openWindow("./");
    })
  );
});
