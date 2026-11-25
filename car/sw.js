// Keeps the driving display working when the car screen loses its internet connection.
const CACHE = 'car-v1';
// the road clip (full or light version) is saved the first time it plays
const CORE = ['./', 'index.html', 'poster.jpg'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(CORE)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

// Video players ask for byte ranges; answer them from the full cached file.
async function ranged(req, res) {
  const m = /bytes=(\d+)-(\d*)/.exec(req.headers.get('range') || '');
  if (!m || res.status !== 200) return res;
  const buf = await res.arrayBuffer();
  const start = Number(m[1]), end = m[2] ? Math.min(Number(m[2]), buf.byteLength - 1) : buf.byteLength - 1;
  return new Response(buf.slice(start, end + 1), {
    status: 206,
    headers: {
      'Content-Type': res.headers.get('Content-Type') || 'video/mp4',
      'Content-Range': 'bytes ' + start + '-' + end + '/' + buf.byteLength,
      'Content-Length': String(end - start + 1),
      'Accept-Ranges': 'bytes'
    }
  });
}

async function handle(req) {
  const cache = await caches.open(CACHE);
  const url = new URL(req.url);
  const isPage = req.mode === 'navigate' || url.pathname.endsWith('.html');
  const cached = await cache.match(url.href, { ignoreSearch: true });
  if (cached && !isPage) return ranged(req, cached);          // media and fonts: cache first
  try {
    // pages skip the browser's own cache so a new version shows on the next reload
    const fresh = await fetch(url.href, { mode: url.origin === location.origin ? 'same-origin' : 'cors', credentials: 'omit', cache: isPage ? 'no-store' : 'default' });
    if (fresh.status === 200) cache.put(url.href, fresh.clone());
    return ranged(req, fresh);
  } catch (err) {
    if (cached) return cached;                                 // offline: last saved page
    throw err;
  }
}

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  const ours = url.origin === location.origin && url.pathname.startsWith(new URL('./', location.href).pathname);
  const fonts = url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com';
  if (ours || fonts) e.respondWith(handle(e.request));
});
