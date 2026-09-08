/** marola map — MIP-0005 §5.3. */
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
    area: $('area'), days: $('days'), near: $('near'), sound: $('sound'), toggleList: $('toggle-list'),
    hourbar: $('hourbar'), hour: $('hour'), hourLabel: $('hour-label'),
    list: $('list'), card: $('card'), footer: $('footer'), status: $('status'), smoke: $('smoke')
  };

  var state = {
    areas: [], area: null, latest: null, board: null,
    hours: [],          // union of "HH:00" across beaches, sorted — the slider's stops
    hourIndex: -1,      // -1 = each beach at its own best hour
    selected: null,     // beach name
    here: null,         // {lat, lon} after "near me"
    markers: {}, trailLayers: [], tiles: null, map: null,
    smoke: null, smokeHistory: null, smokeMarker: null,   // the last live run (MIP-0008)
    waterPointMarkers: []   // the selected beach's own sampling points, real markers, not text
  };

  // --- helpers -------------------------------------------------------------------------------.
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

  // --- loading -------------------------------------------------------------------------------.
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
      // a shared link can open straight on one beach: ?beach=Praia%20do%20Campeche.
      var beach = param('beach');
      if (beach && !state.selected && beachByName(beach)) state.selected = beach;
      // Re-plot the selected beach's water points against *this* board (a shared-link initial
      // load, or a day/area switch while a beach card was already open) — same board this render
      // is about to use, never a stale one from before the fetch.
      renderWaterPoints(beachByName(state.selected));
      render();
    });
  }

  // --- map -----------------------------------------------------------------------------------.
  function ensureMap(area) {
    if (!state.map) {
      state.map = L.map('map', { zoomControl: true, attributionControl: true });
      state.map.on('click', function () { closeCard(); });
    }
    if (state.tiles) state.map.removeLayer(state.tiles);
    state.tiles = L.tileLayer(area.tiles, { maxZoom: 18, attribution: esc(area.tiles_attribution || '') }).addTo(state.map);
    state.map.setView([area.lat, area.lon], 11);
  }

  // --- wave markers + hover aspects (MIP-0009)
  // -------------------------------------------------.
  var WIND_EMOJI = { calm: '🍃', breezy: '🌬️', strong: '💨' };
  var COMPASS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
  function compass(deg) { return deg === null || deg === undefined ? '' : COMPASS[Math.round(deg / 45) % 8]; }

  /** One wave, filled with the score colour. */
  var WAVE_PATH = 'M3 10c2.6-7 6.4-7 9 0s6.4 5 9 0v10H3z';
  function waveIcon(fill, selected, past) {
    var size = selected ? 32 : 24;
    return L.divIcon({
      className: 'wave' + (selected ? ' selected' : '') + (past ? ' past' : ''),
      iconSize: [size, size], iconAnchor: [size / 2, size / 2], tooltipAnchor: [0, -size / 2],
      html: '<svg viewBox="0 0 24 24" width="' + size + '" height="' + size + '" aria-hidden="true">' +
        '<path d="' + WAVE_PATH + '" fill="' + esc(fill) + '" stroke="#fff" stroke-width="1" stroke-linejoin="round"/></svg>'
    });
  }

  /** The full hours[] entry behind what `shown()` returns (the best-hour shape carries no
   * numbers). */
  function hourEntry(beach, s) {
    if (!s) return null;
    for (var i = 0; i < beach.hours.length; i++) if (beach.hours[i].h === s.h) return beach.hours[i];
    return null;
  }

  /** A small colour-only dot for a beach's water verdict — green/red/grey, the same tokens the
   * score legend already uses (--c70/--c0/--cna) — instead of the 💧 emoji, which reads the same
   * regardless of PRÓPRIA/IMPRÓPRIA/no-data and doesn't scan at a glance the way the score
   * markers' colour already does. */
  function waterDotClass(beach) {
    if (beach.water.unfit) return 'wdot c0';
    if (beach.water.summary === 'no data') return 'wdot cna';
    return 'wdot c70';
  }

  /** The six aspects at the shown hour — every value a number or an enum from the board, every
   * emoji followed by its word (older fonts lack 🪼). */
  function aspectsHtml(beach, s) {
    var head = '<div class="head">🌊 ' + esc(beach.name) + (s ? ' · ' + s.score + '/100 at ' + esc(s.h) : ' · dark at this hour') + '</div>';
    var e = hourEntry(beach, s);
    if (!e) return head;
    var level = e.wind_level || null; // an older board has no band: number only, no word
    var wind = (level ? WIND_EMOJI[level] + ' ' + level + ', ' : '🌬️ wind ') + fmt(e.wind_kmh, ' km/h', 0) +
      (s.best && beach.sea.wind_dir_deg !== null ? ' ' + compass(beach.sea.wind_dir_deg) : '');
    var waves = '〰️ waves ' + fmt(e.wave_m, ' m') + (s.best && beach.sea.period_s !== null ? ' every ' + fmt(beach.sea.period_s, ' s', 0) : '');
    var whales = '🐋 whales ' + esc(e.whales) + (beach.whales.peak && beach.whales.peak !== e.h ? ' (best ' + esc(beach.whales.peak) + ')' : '');
    // The verdict is a sentence ("8/9 PRÓPRIA — avoid Ponto 98 (25 Aug)"), not a reading: it gets
    // the full width and wraps (.wide), while the short cells stay on one line each.
    var water = '<span class="wide ' + (beach.water.unfit ? 'unfit' : 'water') + '"><i class="' + waterDotClass(beach) + '"></i> ' + esc(beach.water.summary) + '</span>';
    // Accessibility (MIP-0021): OSM amenity counts within 300m, already in every board — only
    // rendered when the board actually has at least one count for this beach (an older board, or
    // a beach with no matched amenities at all, has no `facilities` key: absent, not zeroed).
    var facilities = facilitiesHtml(beach.facilities);
    return head + '<div class="grid">' +
      '<span>' + wind + '</span>' +
      '<span>🌡️ water ' + fmt(e.sea_temp_c, ' °C') + '</span>' +
      '<span>' + waves + '</span>' +
      '<span>🪼 jellyfish ' + esc(e.jellyfish) + '</span>' +
      '<span>' + whales + '</span>' +
      water +
      (facilities ? '<span class="wide facilities">' + facilities + '</span>' : '') +
      '</div>';
  }

  // --- trails (MIP-0030)
  // ------------------------------------------------------------------------ green = hiking,
  // amber = mountain_hiking, grey = unclassified or no sac_scale tag at all — §3.
  function trailColour(difficulty) {
    if (difficulty === 'hiking') return getCss('--c70');
    if (difficulty === 'mountain_hiking') return getCss('--c40');
    return getCss('--cna');
  }
  function trailTooltipHtml(trail) {
    return '🥾 ' + esc(trail.name) + ' · ' + fmt(trail.length_km, ' km');
  }

  function renderTrails() {
    state.trailLayers.forEach(function (l) { state.map.removeLayer(l); });
    state.trailLayers = [];
    (state.board.trails || []).forEach(function (trail) {
      var latlngs = (trail.geometry || []).map(function (p) { return [p[0], p[1]]; });
      if (latlngs.length < 2) return;
      var line = L.polyline(latlngs, {
        color: trailColour(trail.difficulty), weight: 3, opacity: 0.8, dashArray: '4,4'
      }).addTo(state.map);
      line.bindTooltip(trailTooltipHtml(trail), { sticky: true, direction: 'top' });
      state.trailLayers.push(line);
    });
  }

  var FACILITY_LABEL = { parking: '🅿️ parking', toilets: '🚻 toilets', shower: '🚿 shower', lifeguard: '🛟 lifeguard' };

  /** One short line, only the facilities the board actually has a count for, in a fixed order. */
  function facilitiesHtml(f) {
    if (!f) return '';
    var parts = ['parking', 'toilets', 'shower', 'lifeguard']
      .filter(function (k) { return f[k] !== undefined && f[k] !== null; })
      .map(function (k) { return FACILITY_LABEL[k] + ' ' + f[k]; });
    return parts.length ? parts.join(' · ') : '';
  }

  function render() {
    var board = state.board;
    Object.keys(state.markers).forEach(function (k) { state.map.removeLayer(state.markers[k]); });
    state.markers = {};
    renderTrails();
    var bounds = [];
    board.beaches.forEach(function (beach) {
      var s = shown(beach);
      var c = colour(s ? s.score : null, beach.water.unfit);
      var selected = state.selected === beach.name;
      var m = L.marker([beach.lat, beach.lon], {
        icon: waveIcon(c, selected, !!(s && isPast(s.h))), zIndexOffset: selected ? 1000 : 0, keyboard: true
      }).addTo(state.map);
      // No `title`: the browser would draw its own tooltip on top of Leaflet's after ~1 s.
      // keyboard: true already makes the icon focusable (tabindex + role=button); name it for a
      // screen reader directly instead.
      if (m.getElement) { var mel = m.getElement(); if (mel) mel.setAttribute('aria-label', beach.name); }
      m.bindTooltip(aspectsHtml(beach, s), { sticky: true, direction: 'top', className: 'aspects', opacity: 0.97 });
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

  // --- list ----------------------------------------------------------------------------------.
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

  // --- card ----------------------------------------------------------------------------------.
  function select(name, pan) {
    state.selected = name; setParam('beach', name);
    var b = beachByName(name);
    if (b && pan !== false) state.map.panTo([b.lat, b.lon]);
    renderWaterPoints(b);
    render();
  }
  function closeCard() {
    state.selected = null; el.card.hidden = true;
    var u = new URL(location.href); u.searchParams.delete('beach'); history.replaceState(null, '', u);
    renderWaterPoints(null);
    render();
  }
  function beachByName(name) {
    return state.board.beaches.filter(function (b) { return b.name === name; })[0] || null;
  }

  /** The selected beach's own water-sampling points as real markers — "point by point", not just
   * the one-line aggregate summary the tooltip/card text already shows. */
  function renderWaterPoints(beach) {
    state.waterPointMarkers.forEach(function (m) { state.map.removeLayer(m); });
    state.waterPointMarkers = [];
    if (!beach || !beach.water || !beach.water.points) return;
    beach.water.points.forEach(function (p) {
      var color = p.condition === 'improper' ? getCss('--c0') : p.condition === 'proper' ? getCss('--c70') : getCss('--cna');
      var cond = p.condition === 'proper' ? 'PRÓPRIA' : p.condition === 'improper' ? 'IMPRÓPRIA' : 'unclassified';
      var m = L.circleMarker([p.lat, p.lon], { radius: 6, color: '#fff', weight: 2, fillColor: color, fillOpacity: 1 })
        .addTo(state.map)
        .bindTooltip(esc(p.point) + ' (' + esc(p.location) + '): ' + cond + ', ' + esc(p.sampled_on), { direction: 'top', offset: [0, -6] });
      state.waterPointMarkers.push(m);
    });
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
      '<h2>' + esc(b.name) + '</h2>' +
      // Touch has no hover: the same aspect row the tooltip shows, first, so a tap sees what a
      // mouse sees (MIP-0009 §3).
      '<div class="aspects">' + aspectsHtml(b, s) + '</div>' + head +
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

  // --- footer --------------------------------------------------------------------------------.
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

  // --- last live run (MIP-0008 §5.5)
  // -----------------------------------------------------------.
  var SMOKE_SCHEMA = 1;
  function loadSmoke() {
    // This build's index.html may not have the panel at all (an older/newer deploy than app.js —
    // GitHub Pages caches each file independently, `cache-control: max-age=600`).
    if (!el.smoke) return Promise.resolve();
    return fetchJson('smoke/latest.json').then(function (run) {
      if (run.schema !== SMOKE_SCHEMA) throw new Error('smoke schema ' + run.schema);
      state.smoke = run;
      return fetchJson('smoke/history.json').catch(function () { return null; });
    }).then(function (h) {
      state.smokeHistory = h && h.schema === SMOKE_SCHEMA ? h : null;
      renderSmoke();
    }).catch(function (err) {
      if (el.smoke) el.smoke.hidden = true; // no run yet, or unreadable: no panel
      console.error('smoke panel:', err);
    });
  }

  function renderSmoke() {
    var r = state.smoke; if (!r || !el.smoke) return;
    var when = String(r.when).replace('T', ' ').slice(0, 16) + ' UTC';
    var top = r.top_pick, rev = r.review;
    var verdict = rev ? String(rev.verdict).toLowerCase() : '';
    var where = r.lat !== null && r.lat !== undefined
      ? '<a href="#" id="smoke-goto">' + r.lat.toFixed(4) + ', ' + r.lon.toFixed(4) + '</a>' : 'unknown location';
    var image = String(r.image || '').replace(/^ghcr\.io\//, '');
    var html = '<h3>Last live run <small>— ' + esc(r.model) + ' in ' + esc(image) + ', ' + esc(when) +
      ' · <a href="' + esc(r.run_url) + '" target="_blank" rel="noopener">log</a></small></h3>';
    if (!r.ok) {
      html += '<p class="bad">This run did not complete: ' + esc((r.errors && r.errors[0]) || ('exit code ' + r.exit_code)) + '.</p>';
    }
    if (top) {
      html += '<p>From ' + where + ' the pipeline picked <b>' + esc(top.name) + '</b>: score ' + top.score + '/100 at ' + esc(top.when) +
        ', ' + top.distance_km + ' km away' + (r.ranked && r.ranked.length > 1 ? ' (' + r.ranked.length + ' beaches ranked)' : '') + '.</p>';
    }
    // MIP-0008 §6/§8: the sentence is model text, shown only with the reviewer's verdict next to
    // it, and not at all when the reviewer rejected it — the numbers above are the source of
    // truth.
    if (r.ok && rev && verdict !== 'reject' && rev.summary) {
      html += '<p class="llm">“' + esc(rev.summary) + '”</p>' +
        '<p><small>Model text (' + esc(r.model) + '), reviewed by a second pass: <span class="verdict ' + esc(verdict) + '">' +
        esc(verdict) + ' · ' + rev.score + '/100</span>. The ranked numbers are the source of truth.</small></p>';
    } else if (rev && verdict === 'reject') {
      html += '<p><small>The reviewer <span class="verdict reject">rejected</span> the model\'s sentence (' + rev.score + '/100) — numbers only.</small></p>';
    }
    var runs = state.smokeHistory && state.smokeHistory.runs ? state.smokeHistory.runs.slice(0, 10) : [];
    if (runs.length > 1) {
      html += '<details><summary>last ' + runs.length + ' runs</summary><ul>' + runs.map(function (e) {
        var w = String(e.when).replace('T', ' ').slice(0, 16);
        var what = e.ok && e.top_pick
          ? esc(e.top_pick.name) + ' ' + e.top_pick.score + (e.review ? ', reviewer ' + e.review.score + ' <span class="verdict ' + esc(e.review.verdict) + '">' + esc(e.review.verdict) + '</span>' : '')
          : '<span class="bad">failed</span>';
        return '<li><a href="' + esc(e.run_url) + '" target="_blank" rel="noopener">' + esc(w) + '</a> · ' + esc(e.model) + ' · ' + what + '</li>';
      }).join('') + '</ul></details>';
    }
    el.smoke.innerHTML = html;
    el.smoke.hidden = false;
    if (r.lat !== null && r.lat !== undefined && state.map) {
      if (state.smokeMarker) state.map.removeLayer(state.smokeMarker);
      state.smokeMarker = L.circleMarker([r.lat, r.lon], { radius: 7, color: '#0b6e99', dashArray: '3 3', weight: 2, fillColor: '#fff', fillOpacity: .9 })
        .addTo(state.map).bindTooltip('last live run: ' + (top ? top.name + ' ' + top.score : 'failed') + ', ' + when, { direction: 'top', offset: [0, -6] });
      var go = document.getElementById('smoke-goto');
      if (go) go.addEventListener('click', function (e) { e.preventDefault(); state.map.setView([r.lat, r.lon], 12); });
    }
  }

  // --- ambient wave sound (Web Audio API, synthesized — no audio file, nothing to fetch)
  // --------.
  var sound = { ctx: null, gain: null, lfoDepth: null, on: false };
  var SWELL_DEPTH = 0.05;   // how far the LFO swings the output gain when the sound is on
  var SOUND_LEVEL = 0.06;   // the output gain's own level when on
  var SILENT = 0.0001;
  // Brown noise (integrated white noise, ~ -6dB/octave) through a low-pass filter reads as surf
  // wash; a slow LFO on the gain (~0.15 Hz, one swell every ~6.7s) gives it the rise-and-fall of
  // real waves instead of a flat hiss.
  function startWaveSound(ctx) {
    var seconds = 2, bufferSize = seconds * ctx.sampleRate;
    var buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
    var data = buffer.getChannelData(0);
    var last = 0;
    for (var i = 0; i < bufferSize; i++) {
      var white = Math.random() * 2 - 1;
      last = (last + 0.02 * white) / 1.02;
      data[i] = last * 3.5;
    }
    var noise = ctx.createBufferSource();
    noise.buffer = buffer; noise.loop = true;
    var filter = ctx.createBiquadFilter();
    filter.type = 'lowpass'; filter.frequency.value = 700;
    var gainNode = ctx.createGain();
    gainNode.gain.value = 0.0001; // starts silent; the click handler ramps it up
    var lfo = ctx.createOscillator();
    lfo.frequency.value = 0.15;
    var lfoGain = ctx.createGain();
    lfoGain.gain.value = SWELL_DEPTH;
    lfo.connect(lfoGain);
    lfoGain.connect(gainNode.gain);
    noise.connect(filter);
    filter.connect(gainNode);
    gainNode.connect(ctx.destination);
    noise.start(); lfo.start();
    // The LFO's depth is returned alongside the output gain because BOTH have to be silenced to
    // stop the sound: a signal connected to an AudioParam is added to that param's intrinsic
    // value, so ramping gainNode.gain to ~0 on its own leaves the LFO still swinging it by
    // ±SWELL_DEPTH — audible, and the reason the toggle used to never turn the sound off.
    return { gain: gainNode, lfoDepth: lfoGain };
  }

  // --- events --------------------------------------------------------------------------------.
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
  el.sound.addEventListener('click', function () {
    try {
      if (!sound.ctx) {
        var Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return;
        sound.ctx = new Ctx();
        var synth = startWaveSound(sound.ctx);
        sound.gain = synth.gain;
        sound.lfoDepth = synth.lfoDepth;
      }
      if (sound.ctx.state === 'suspended') sound.ctx.resume();
      sound.on = !sound.on;
      el.sound.setAttribute('aria-pressed', String(sound.on));
      var now = sound.ctx.currentTime;
      sound.gain.gain.cancelScheduledValues(now);
      sound.gain.gain.setTargetAtTime(sound.on ? SOUND_LEVEL : SILENT, now, 0.5);
      // Silence the swell too, or the sound never actually stops — see startWaveSound.
      sound.lfoDepth.gain.cancelScheduledValues(now);
      sound.lfoDepth.gain.setTargetAtTime(sound.on ? SWELL_DEPTH : 0, now, 0.5);
    } catch (e) { console.error(e); }
  });

  // The hint is only right when the board JSON itself is missing (a fresh checkout, or a build
  // that never ran) — fetchJson's own error is "<status> <path>", e.g. "404 data/areas.json".
  function isMissingBoardJson(err) {
    return /^404 data\/.*\.json$/.test((err && err.message) || '');
  }
  function fail(err) {
    var hint = isMissingBoardJson(err) ? ' — run `just site-build` first?' : '';
    status('could not load the board: ' + err.message + hint);
    console.error(err);
  }

  loadAreas().then(loadSmoke).catch(fail);
})();
