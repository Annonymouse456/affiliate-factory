// Runs after the 3D map page's own scripts. Lets the app drive the page: follow the car, start a
// route for the co-pilot, and report how smoothly the map is drawing.
(function () {
  var H = window.Host;
  try { if (typeof map === 'undefined' || !map) throw 0; } catch (e) { if (H) H.fail('map page did not start'); return; }
  window.__up = true;
  var freeUntil = 0, first = true;
  ['dragstart', 'zoomstart', 'pitchstart', 'rotatestart'].forEach(function (ev) {
    map.on(ev, function (e) { if (e && e.originalEvent) freeUntil = Date.now() + 20000; });       // the driver is looking around: hold still for a while
  });

  function answer(o) { H.result(JSON.stringify(o)); }

  window.__dash = {
    config: function () { },
    fix: function (lat, lon, dir, kmh) {
      window.__gps(lat, lon, dir, kmh);
      try {
        if (navOn) return;                                    // while guiding, the page follows the car by itself
        var p = [lon, lat], moving = kmh > 5 && dir >= 0;
        showMe(p, moving ? dir : null);
        if (Date.now() < freeUntil) return;
        var o = { center: p, duration: first ? 0 : 900, easing: function (t) { return t; } };
        if (first) { o.zoom = 16.5; o.pitch = 60; }
        if (moving) o.bearing = dir;
        map.easeTo(o);
        first = false;
      } catch (e) { }
    },
    clear: function () { try { closeNav(); } catch (e) { } },
    go: function (q) {
      var c = map.getCenter();
      fetch('https://photon.komoot.io/api/?q=' + encodeURIComponent(q) + '&limit=1&lang=default&lat=' + c.lat + '&lon=' + c.lng)
        .then(function (r) { return r.json(); })
        .then(function (j) {
          var f = (j.features || [])[0];
          if (!f) return answer({ ok: false, why: 'place: not found' });
          var pr = f.properties || {}, name = pr.name || pr.street || q;
          if (navOn) closeNav();
          pick(f.geometry.coordinates, name, '', false);
          return onGo().then(function () {                    // first press plans the route, second starts guiding
            if (!route) return answer({ ok: false, why: 'route: none', name: name });
            return onGo().then(function () {
              answer({ ok: true, name: name, km: Math.round(route.dist / 100) / 10, minutes: Math.round(route.dur / 60), traffic: !!route.traffic });
            });
          });
        })
        .catch(function (e) { answer({ ok: false, why: 'place: ' + e }); });
    }
  };

  // How smoothly is it drawing? Time the gaps between frames while the map moves, and report every so often.
  var gaps = [], lastFrame = 0, slowRuns = 0, eased = false, told = 0;
  map.on('render', function () {
    var now = performance.now();
    if (lastFrame && now - lastFrame < 500) gaps.push(now - lastFrame);
    lastFrame = now;
    if (gaps.length < 60) return;
    var sum = 0;
    for (var i = 0; i < gaps.length; i++) sum += gaps[i];
    var fps = Math.round(1000 / (sum / gaps.length));
    gaps = [];
    if (Date.now() - told > 30000) { told = Date.now(); H.status('fps ' + fps); }
    slowRuns = fps < 12 ? slowRuns + 1 : 0;
    if (slowRuns >= 3 && !eased) {                             // clearly struggling: drop the 3D buildings first, the costliest part
      eased = true;
      try { var b = document.getElementById('b3d'); if (b && b.checked) { b.click(); H.status('eased buildings off at fps ' + fps); } } catch (e) { }
    }
  });

  H.result(JSON.stringify({ ready: true }));
})();
