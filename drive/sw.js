// The driving display moved to ../car/. This worker replaces the old offline cache, which kept
// serving an outdated page, and then removes itself.
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (ks) {
    return Promise.all(ks.filter(function (k) { return k.indexOf('drive-') === 0; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.registration.unregister(); }).then(function () {
    return self.clients.matchAll({ type: 'window' });
  }).then(function (cs) { cs.forEach(function (c) { if (c.navigate) c.navigate(c.url); }); }));
});
