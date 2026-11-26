// Runs before the 3D map page's own scripts. Gives the page the car's position from the app (the
// web engine in a head unit often cannot ask for location itself), a working place to keep its
// settings, the traffic key, and a way to tell the app when the page cannot start.
(function () {
  var H = window.Host, last = null, watchers = {}, n = 0;
  function pos() {
    return { coords: { latitude: last[0], longitude: last[1], heading: last[2] < 0 ? null : last[2], speed: Math.max(0, last[3]) / 3.6,
      accuracy: 5, altitude: null, altitudeAccuracy: null }, timestamp: Date.now() };
  }
  var geo = {
    getCurrentPosition: function (ok, fail) {
      var tries = 0, t = setInterval(function () {
        if (last) { clearInterval(t); ok(pos()); }
        else if (++tries > 30) { clearInterval(t); if (fail) fail({ code: 3, message: 'no gps yet' }); }
      }, 500);
      if (last) { clearInterval(t); setTimeout(function () { ok(pos()); }, 0); }
    },
    watchPosition: function (ok) { var id = ++n; watchers[id] = ok; if (last) setTimeout(function () { ok(pos()); }, 0); return id; },
    clearWatch: function (id) { delete watchers[id]; }
  };
  try { Object.defineProperty(navigator, 'geolocation', { value: geo, configurable: true }); } catch (e) { }
  window.__gps = function (lat, lon, dir, kmh) {
    last = [lat, lon, dir, kmh];
    for (var k in watchers) { try { watchers[k](pos()); } catch (e) { } }
  };

  var works = false, mem = {};
  try { localStorage.setItem('__probe', '1'); works = localStorage.getItem('__probe') === '1'; localStorage.removeItem('__probe'); } catch (e) { }
  if (!works) {
    try {
      Object.defineProperty(window, 'localStorage', { configurable: true, value: {
        getItem: function (k) { return k in mem ? mem[k] : null; }, setItem: function (k, v) { mem[k] = String(v); }, removeItem: function (k) { delete mem[k]; } } });
    } catch (e) { }
  }
  try { if (window.__key) localStorage.setItem('tomtomKey', window.__key); } catch (e) { }

  window.addEventListener('error', function (e) {            // a page that fails before it is up will never draw: say so, and the app shows the light map instead
    if (!window.__up && H) H.fail(String((e && e.message) || 'error').slice(0, 140));
  });
})();
