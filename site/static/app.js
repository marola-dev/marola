/* marola map — MIP-0005 §5.3. Plain JS, no build step.
 *
 * Reads data/areas.json → data/<area>/latest.json → data/<area>/<day>.json (schema:
 * site/board.schema.json, written by marola.site.Board) and draws one marker per beach coloured by
 * score. Nothing leaves the browser: no analytics, no cookies; "near me" uses the Geolocation API
 * client-side only, when you press the button. The page refuses a board with an unknown schema
 * version rather than guessing.
 */
(function () {
  'use strict';

  var SCHEMA = 1;
  var SOURCE_LINKS = {
    'OpenStreetMap/Overpass': 'https://www.openstreetmap.org/copyright',
    'Open-Meteo': 'https://open-meteo.com/',
    'IMA/SC': 'https://balneabilidade.ima.sc.gov.br/'
  };
  var REPO = 'https://github.com/h0ffmann/marola';

  var $ = function (id) { return document.getElementById(id); };
  var el = {
    area: $('area'), days: $('days'), near: $('near'), toggleList: $('toggle-list'),
    hourbar: $('hourbar'), hour: $('hour'), hourLabel: $('hour-label'),
    list: $('list'), card: $('card'), footer: $('footer'), status: $('status')
  };

  var state = {
    areas: [], area: null, latest: null, board: null,
    hours: [],          // union of "HH:00" across beaches, sorted — the slider's stops
    hourIndex: -1,      // -1 = each beach at its own best hour
    selected: null,     // beach name
    here: null,         // {lat, lon} after "near me"
    markers: {}, tiles: null, map: null
  };

  // --- helpers -------------------------------------------------------------------------------
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function fmt(n, unit, digits) {
    if (n === null || n === undefined) return 'n/a';
    return n.toFixed(digits === undefined ? 1 : digits) + (unit || '');
  }
  function colour(score, unfit) {
    if (score === null || score === undefined) return getCss('--cna');
    if (unfit || score <= 0) return getCss('--c0');
    if (score >= 70) return getCss('--c70');
    if (score >= 40) return getCss('--c40');
    return getCss('--c1');
  }
  function getCss(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }
  function fetchJson(path) {
    return fetch(path, { cache: 'no-cache' }).then(function (r) {
      if (!r.ok) throw new Error(r.status + ' ' + path);
      return r.json();
    });
  }
  function haversineKm(a, b) {
    var R = 6371, dLat = (b.lat - a.lat) * Math.PI / 180, dLon = (b.lon - a.lon) * Math.PI / 180;
    var la1 = a.lat * Math.PI / 180, la2 = b.lat * Math.PI / 180;
    var h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.sqrt(h));
  }
  function param(name) { return new URLSearchParams(location.search).get(name); }
  function setParam(name, value) {
    var u = new URL(location.href); u.searchParams.set(name, value); history.replaceState(null, '', u);
  }

  /** The hour entry a beach shows for the current slider position (its best when hourIndex < 0). */
  function shown(beach) {
    if (state.hourIndex < 0) return { h: beach.best.hour, score: beach.best.score, notes: beach.best.notes, best: true };
    var h = state.hours[state.hourIndex];
    for (var i = 0; i < beach.hours.length; i++) if (beach.hours[i].h === h) return beach.hours[i];
    return null; // dark at that hour for this beach
  }
  function isPast(h) {
    if (!state.board || state.board.day !== state.board.today) return false;
    var gen = state.board.generated_at.slice(11, 16);
    return h < gen;
  }

  // --- loading -------------------------------------------------------------------------------
  function status(text) { el.status.textContent = text; }

  function loadAreas() {
    return fetchJson('data/areas.json').then(function (j) {
      state.areas = j.areas || [];
      if (!state.areas.length) throw new Error('no areas in data/areas.json');
      el.area.innerHTML = state.areas.map(function (a) {
        return '<option value="' + esc(a.id) + '">' + esc(a.name) + '</option>';
      }).join('');
      var wanted = param('area');
      var area = state.areas.filter(function (a) { return a.id === wanted; })[0] || state.areas[0];
      el.area.value = area.id;
      return selectArea(area);
    });
  }

  function selectArea(area) {
    state.area = area; setParam('area', area.id);
    ensureMap(area);
    status('loading ' + area.name + '…');
    return fetchJson('data/' + area.id + '/latest.json').then(function (latest) {
      state.latest = latest;
      el.days.innerHTML = latest.days.map(function (d, i) {
        var label = i === 0 ? 'today' : i === 1 ? 'tomorrow' : d.day;
        return '<button type="button" data-day="' + esc(d.day) + '" data-file="' + esc(d.file) + '">' + label + ' <small>' + esc(d.day.slice(5)) + '</small></button>';
      }).join('');
      var wanted = param('day');
      var pick = latest.days.filter(function (d) { return d.day === wanted; })[0] || latest.days[latest.days.length - 1];
      return selectDay(pick);
    });
  }

  function selectDay(d) {
    setParam('day', d.day);
    Array.prototype.forEach.call(el.days.children, function (b) { b.classList.toggle('on', b.dataset.day === d.day); });
    status('loading ' + d.day + '…');
    return fetchJson('data/' + state.area.id + '/' + d.file).then(function (board) {
      if (board.schema !== SCHEMA) throw new Error('board schema ' + board.schema + ', this page understands ' + SCHEMA);
      state.board = board;
      state.hours = [];
      board.beaches.forEach(function (b) { b.hours.forEach(function (h) { if (state.hours.indexOf(h.h) < 0) state.hours.push(h.h); }); });
      state.hours.sort();
      el.hour.max = state.hours.length - 1;
      el.hour.value = -1; state.hourIndex = -1;
      el.hourbar.hidden = state.hours.length === 0;
      // a shared link can open straight on one beach: ?beach=Praia%20do%20Campeche
      var beach = param('beach');
      if (beach && !state.selected && beachByName(beach)) state.selected = beach;
      render();
    });
  }

  // --- map -----------------------------------------------------------------------------------
  function ensureMap(area) {
    if (!state.map) {
      state.map = L.map('map', { zoomControl: true, attributionControl: true });
      state.map.on('click', function () { closeCard(); });
    }
    if (state.tiles) state.map.removeLayer(state.tiles);
    state.tiles = L.tileLayer(area.tiles, { maxZoom: 18, attribution: esc(area.tiles_attribution || '') }).addTo(state.map);
    state.map.setView([area.lat, area.lon], 11);
  }

  function render() {
    var board = state.board;
    Object.keys(state.markers).forEach(function (k) { state.map.removeLayer(state.markers[k]); });
    state.markers = {};
    var bounds = [];
    board.beaches.forEach(function (beach) {
      var s = shown(beach);
      var c = colour(s ? s.score : null, beach.water.unfit);
      var m = L.circleMarker([beach.lat, beach.lon], {
        radius: state.selected === beach.name ? 13 : 10, color: '#fff', weight: 2, fillColor: c, fillOpacity: s && isPast(s.h) ? 0.45 : 0.9
      }).addTo(state.map);
      m.bindTooltip(beach.name + (s ? ' · ' + s.score + ' at ' + s.h : ' · dark'), { direction: 'top', offset: [0, -8] });
      m.on('click', function (e) { L.DomEvent.stopPropagation(e); select(beach.name, false); });
      state.markers[beach.name] = m;
      bounds.push([beach.lat, beach.lon]);
    });
    if (bounds.length && !state.fitted) { state.map.fitBounds(bounds, { padding: [30, 30] }); state.fitted = true; }
    renderHourLabel();
    renderList();
    renderFooter();
    if (state.selected) renderCard();
  }

  function renderHourLabel() {
    if (state.hourIndex < 0) { el.hourLabel.textContent = 'best hour per beach'; return; }
    var h = state.hours[state.hourIndex];
    el.hourLabel.textContent = 'at ' + h + (isPast(h) ? ' (already past)' : '');
  }

  // --- list ----------------------------------------------------------------------------------
  function ranked() {
    var list = state.board.beaches.slice();
    if (state.here) {
      list.forEach(function (b) { b._km = haversineKm(state.here, b); });
      list.sort(function (a, b) { return a._km - b._km; });
    } else {
      list.sort(function (a, b) { var sa = shown(a), sb = shown(b); return (sb ? sb.score : -1) - (sa ? sa.score : -1); });
    }
    return list;
  }

  function renderList() {
    var items = ranked().map(function (b) {
      var s = shown(b);
      var score = s ? s.score : '–';
      var water = b.water.unfit ? '<span class="water unfit">' + esc(b.water.summary) + '</span>'
        : (b.water.points.length ? '<span class="water">' + esc(b.water.summary) + '</span>' : '');
      var dist = state.here ? ' <span class="dist">' + b._km.toFixed(1) + ' km</span>' : '';
      return '<li data-name="' + esc(b.name) + '"><span class="score" style="background:' + colour(s ? s.score : null, b.water.unfit) + '">' + score + '</span>' +
        esc(b.name) + (s ? ' <span class="dist">' + esc(s.h) + '</span>' : ' <span class="dist">dark</span>') + dist + water + '</li>';
    });
    el.list.innerHTML = '<ol>' + items.join('') + '</ol>';
  }

  // --- card ----------------------------------------------------------------------------------
  function select(name, pan) {
    state.selected = name; setParam('beach', name);
    var b = beachByName(name);
    if (b && pan !== false) state.map.panTo([b.lat, b.lon]);
    render();
  }
  function closeCard() {
    state.selected = null; el.card.hidden = true;
    var u = new URL(location.href); u.searchParams.delete('beach'); history.replaceState(null, '', u);
    render();
  }
  function beachByName(name) {
    return state.board.beaches.filter(function (b) { return b.name === name; })[0] || null;
  }

  function renderCard() {
    var b = beachByName(state.selected);
    if (!b) { el.card.hidden = true; return; }
    var s = shown(b);
    var head = s
      ? '<p class="headline"><span class="score" style="background:' + colour(s.score, b.water.unfit) + '">' + s.score + '/100</span> ' +
        (s.best ? 'best at <b>' + esc(s.h) + '</b>' : 'at <b>' + esc(s.h) + '</b> (best ' + esc(b.best.hour) + ', ' + b.best.score + ')') +
        (isPast(s.h) ? ' <span class="past">already past</span>' : '') + '</p>'
      : '<p class="headline past">dark at this hour</p>';
    var notes = s && s.notes.length ? '<ul>' + s.notes.map(function (n) { return '<li>' + esc(n) + '</li>'; }).join('') + '</ul>' : 'good conditions';
    var points = b.water.points.map(function (p) {
      var cls = p.condition === 'improper' ? 'improper' : p.condition === 'proper' ? 'proper' : '';
      var count = p.enterococci_per_100ml === null ? '' : ', ' + p.enterococci_per_100ml + ' enterococci/100mL';
      var cond = p.condition === 'proper' ? 'PRÓPRIA' : p.condition === 'improper' ? 'IMPRÓPRIA' : 'unclassified';
      return '<li><span class="' + cls + '">' + esc(p.point) + ' (' + esc(p.location) + '): ' + cond + '</span>, ' + esc(p.sampled_on) + count + '</li>';
    });
    var water = '<span class="' + (b.water.unfit ? 'unfit' : '') + '">' + esc(b.water.summary) + '</span>' +
      (points.length ? '<ul>' + points.join('') + '</ul>' : '') +
      (b.water.source ? '<small>Source: ' + esc(b.water.source) + '</small>' : '');
    var tides = b.tides.length ? b.tides.map(function (t) {
      return (t.high ? 'high ' : 'low ') + esc(t.time) + ' (' + (t.m >= 0 ? '+' : '') + t.m.toFixed(1) + ' m)';
    }).join(', ') + ' <small>(hourly, ±30 min)</small>' : 'no sea-level data';
    var sea = b.sea;
    var seaText = fmt(sea.temp_c, '°C') + ', waves ' + fmt(sea.wave_m, ' m') + (sea.period_s !== null ? ' every ' + fmt(sea.period_s, ' s', 0) : '') +
      (sea.swell_m !== null ? ', swell ' + fmt(sea.swell_m, ' m') : '') + (sea.current_kmh !== null ? ', current ' + fmt(sea.current_kmh, ' km/h') : '');
    var air = fmt(sea.air_temp_c, '°C', 0) + ', wind ' + fmt(sea.wind_kmh, ' km/h', 0) + (sea.uv !== null ? ', UV ' + fmt(sea.uv, '', 0) : '') +
      (sea.rain_pct !== null ? ', ' + fmt(sea.rain_pct, '% rain', 0) : '');
    var whales = b.whales.now + (b.whales.peak ? ', best daylight odds at ' + esc(b.whales.peak) : '') + (b.whales.season ? ' — humpback season' : ' — outside July-November');
    el.card.innerHTML =
      '<button class="close" type="button" aria-label="Close">×</button>' +
      '<h2>' + esc(b.name) + '</h2>' + head +
      '<dl>' +
      '<dt>Why</dt><dd>' + notes + '</dd>' +
      '<dt>Water quality</dt><dd>' + water + '</dd>' +
      '<dt>Sea</dt><dd>' + seaText + ' <small>(at ' + esc(b.best.hour) + ')</small></dd>' +
      '<dt>Tide</dt><dd>' + tides + '</dd>' +
      '<dt>Air</dt><dd>' + air + '</dd>' +
      '<dt>Jellyfish</dt><dd>' + esc(b.jellyfish) + '</dd>' +
      '<dt>Whales</dt><dd>' + esc(whales) + '</dd>' +
      '<dt>Where</dt><dd><a href="https://www.openstreetmap.org/?mlat=' + b.lat + '&mlon=' + b.lon + '#map=15/' + b.lat + '/' + b.lon + '" target="_blank" rel="noopener">' + b.lat.toFixed(4) + ', ' + b.lon.toFixed(4) + '</a></dd>' +
      '</dl>';
    el.card.hidden = false;
    el.card.querySelector('.close').addEventListener('click', closeCard);
  }

  // --- footer --------------------------------------------------------------------------------
  function renderFooter() {
    var b = state.board;
    var srcs = [b.sources.beaches, b.sources.forecast, b.sources.water].filter(Boolean).map(function (s) {
      var href = SOURCE_LINKS[s.split(' ')[0]] || SOURCE_LINKS[s];
      return href ? '<a href="' + href + '" target="_blank" rel="noopener">' + esc(s) + '</a>' : esc(s);
    }).join(' · ');
    var lore = b.lore ? '<p class="lore">' + (b.lore.kind === 'creature' ? '🐋 Sea life: ' : '🌊 Did you know? ') + esc(b.lore.text) +
      ' <a href="' + esc(b.lore.source) + '" target="_blank" rel="noopener">[source]</a></p>' : '';
    el.footer.innerHTML =
      '<p id="status">Generated ' + esc(b.generated_at.replace('T', ' ').slice(0, 16)) + ' for ' + esc(state.area.name) + ', ' + esc(b.day) +
      ' · ' + b.beaches.length + ' beaches · data: ' + srcs + '</p>' + lore +
      '<p>Scores are a heuristic (0-100) from Open-Meteo\'s forecast and the agency\'s bathing-water classification; unfit water is red and says why. ' +
      'No cookies, no tracking. The same pipeline answers one beach at a time on the command line and, soon, in the Telegram bot — ' +
      '<a href="' + REPO + '" target="_blank" rel="noopener">marola on GitHub</a>.</p>';
    el.status = document.getElementById('status');
  }

  // --- events --------------------------------------------------------------------------------
  el.area.addEventListener('change', function () {
    var area = state.areas.filter(function (a) { return a.id === el.area.value; })[0];
    state.fitted = false; state.selected = null; el.card.hidden = true;
    selectArea(area).catch(fail);
  });
  el.days.addEventListener('click', function (e) {
    var btn = e.target.closest('button'); if (!btn) return;
    selectDay({ day: btn.dataset.day, file: btn.dataset.file }).catch(fail);
  });
  el.hour.addEventListener('input', function () { state.hourIndex = parseInt(el.hour.value, 10); render(); });
  el.toggleList.addEventListener('click', function () {
    el.list.hidden = !el.list.hidden;
    el.toggleList.setAttribute('aria-expanded', String(!el.list.hidden));
  });
  el.list.addEventListener('click', function (e) {
    var li = e.target.closest('li'); if (li) select(li.dataset.name, true);
  });
  el.near.addEventListener('click', function () {
    if (state.here) { state.here = null; el.near.setAttribute('aria-pressed', 'false'); render(); return; }
    if (!navigator.geolocation) { alert('No geolocation in this browser.'); return; }
    navigator.geolocation.getCurrentPosition(function (pos) {
      state.here = { lat: pos.coords.latitude, lon: pos.coords.longitude };
      el.near.setAttribute('aria-pressed', 'true');
      el.list.hidden = false; el.toggleList.setAttribute('aria-expanded', 'true');
      L.circleMarker([state.here.lat, state.here.lon], { radius: 6, color: '#0b6e99', fillColor: '#0b6e99', fillOpacity: 1 }).addTo(state.map).bindTooltip('you');
      render();
    }, function () { alert('Location not granted — the list stays ranked by score.'); });
  });

  function fail(err) {
    status('could not load the board: ' + err.message + ' — run `just site-build` first?');
    console.error(err);
  }

  loadAreas().catch(fail);
})();
