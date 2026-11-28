/* Glow Studio service worker — installable PWA, fresh-when-online */
const CACHE = "glow-v1";
const ASSETS = ["./glow-studio.html", "./icon-192.png", "./icon-512.png", "./apple-touch-icon.png", "./manifest.webmanifest"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()).catch(() => {}));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  if (e.request.method !== "GET") return;
  const url = new URL(e.request.url);
  // Network-first for the app page so updates always load when online; cache fallback offline.
  if (url.pathname.endsWith("/glow-studio.html") || url.pathname.endsWith("glow-studio.html")) {
    e.respondWith(
      fetch(e.request).then(r => { const cp = r.clone(); caches.open(CACHE).then(c => c.put(e.request, cp)); return r; })
        .catch(() => caches.match(e.request))
    );
    return;
  }
  // Cache-first for our own static assets only.
  if (ASSETS.some(a => url.pathname.endsWith(a.replace("./", "/")))) {
    e.respondWith(caches.match(e.request).then(r => r || fetch(e.request)));
  }
});
