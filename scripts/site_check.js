#!/usr/bin/env node
/** site_check — runs site/static/app.js against site/fixtures/board.json in a stub DOM and a stub
 * Leaflet (`L`), no browser, no network, no dependencies (MIP-0009 task 2; §5 said this harness
 * existed since MIP-0008 — it did not, so here it is). */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const APP = fs.readFileSync(path.join(ROOT, 'site/static/app.js'), 'utf8');
const SCHEMA = JSON.parse(fs.readFileSync(path.join(ROOT, 'site/board.schema.json'), 'utf8'));
const BOARD = JSON.parse(fs.readFileSync(path.join(ROOT, 'site/fixtures/board.json'), 'utf8'));
const INDEX = fs.readFileSync(path.join(ROOT, 'site/static/index.html'), 'utf8');
const ABOUT = fs.readFileSync(path.join(ROOT, 'site/static/about.html'), 'utf8');
const I18N_JS = fs.readFileSync(path.join(ROOT, 'site/static/i18n.js'), 'utf8');
const UI = fs.readFileSync(path.join(ROOT, 'site/static/ui.js'), 'utf8');
const CATALOGS = Object.fromEntries(['pt-BR', 'en'].map(l => [l, JSON.parse(fs.readFileSync(path.join(ROOT, 'site/i18n', l + '.json'), 'utf8'))]));

let fails = 0;
function ok(cond, label, detail) {
  if (cond) console.log('  ok   ' + label);
  else { console.log('  FAIL ' + label + (detail ? ' — ' + detail : '')); fails++; }
}

// --- the JSON-Schema subset the board contract uses (mirror of BoardSpec.SchemaCheck)
// ----------.
function typeName(v) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  if (typeof v === 'number') return Number.isInteger(v) ? 'integer' : 'number';
  return typeof v; // object, string, boolean
}
function validate(schema, value, p, root) {
  root = root || schema; p = p || '$';
  if (schema.$ref) {
    if (!schema.$ref.startsWith('#/$defs/')) return [p + ': unsupported $ref ' + schema.$ref];
    return validate(root.$defs[schema.$ref.slice(8)], value, p, root);
  }
  const errs = [];
  const actual = typeName(value);
  const types = schema.type === undefined ? [] : [].concat(schema.type);
  if (types.length && !types.some(t => t === actual || (t === 'number' && actual === 'integer')))
    errs.push(p + ': expected ' + types.join('|') + ', got ' + actual);
  if (schema.enum && !schema.enum.some(e => JSON.stringify(e) === JSON.stringify(value)))
    errs.push(p + ': ' + JSON.stringify(value) + ' not in enum');
  if (actual === 'object') {
    for (const k of schema.required || []) if (!(k in value)) errs.push(p + ': missing ' + k);
    for (const [k, v] of Object.entries(value)) {
      const sub = (schema.properties || {})[k];
      if (sub) errs.push(...validate(sub, v, p + '.' + k, root));
      else if (schema.additionalProperties === false) errs.push(p + '.' + k + ': not allowed');
    }
  }
  if (actual === 'array' && schema.items)
    value.forEach((v, i) => errs.push(...validate(schema.items, v, p + '[' + i + ']', root)));
  return errs;
}

// --- a DOM just big enough for app.js
// -----------------------------------------------------------.
class El {
  constructor(id) {
    this.id = id; this.innerHTML = ''; this.textContent = ''; this.hidden = false; this.value = '';
    this.max = 0; this.dataset = {}; this.attrs = {}; this.children = []; this.listeners = {};
    this.classList = { toggle() {}, add() {}, remove() {}, contains() { return false; } };
  }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  setAttribute(k, v) { this.attrs[k] = String(v); }
  getAttribute(k) { return this.attrs[k]; }
  querySelector() { return new El('anon'); }
}
const IDS = ['area', 'days', 'near', 'sound', 'toggle-list', 'hourbar', 'hour', 'hour-label', 'list', 'card', 'footer', 'status'];
// 'smoke' is deliberately absent: the page must tolerate a build without the panel (app.js
// header).

// --- a Leaflet just big enough for app.js
// --------------------------------------------------------.
function makeLeaflet() {
  const created = []; // every marker/circleMarker, in creation order
  const layer = (kind, latlng, opts) => {
    const l = { kind, latlng, opts, tooltip: null, tooltipOpts: null, handlers: {}, added: false };
    l.addTo = function (m) { this.added = true; m.layers.push(this); return this; };
    // Leaflet hands back the marker's DOM node once it is on the map; app.js names it for a
    // screen reader through this (it dropped `title`, which drew a second, native tooltip over
    // Leaflet's).
    l.element = { attrs: {}, setAttribute(k, v) { this.attrs[k] = String(v); } };
    l.getElement = function () { return this.added ? this.element : null; };
    l.bindTooltip = function (c, o) { this.tooltip = c; this.tooltipOpts = o; return this; };
    l.on = function (ev, fn) { this.handlers[ev] = fn; return this; };
    created.push(l);
    return l;
  };
  const L = {
    created,
    map() {
      const m = { layers: [], handlers: {} };
      m.on = (ev, fn) => { m.handlers[ev] = fn; return m; };
      m.removeLayer = (l) => { m.layers = m.layers.filter(x => x !== l); if (l) l.added = false; };
      m.setView = () => m; m.fitBounds = () => m; m.panTo = () => m;
      return m;
    },
    tileLayer() { return { addTo(m) { m.layers.push(this); return this; } }; },
    circleMarker: (ll, o) => layer('circleMarker', ll, o),
    marker: (ll, o) => layer('marker', ll, o),
    polyline: (latlngs, o) => layer('polyline', latlngs, o),
    divIcon: (o) => ({ divIcon: true, options: o }),
    DomEvent: { stopPropagation() {} }
  };
  return L;
}

// --- an AudioContext just big enough for app.js's wave-sound synth (no real audio, records the
// node graph so the test can assert it was actually built)
// ---------------------------------------.
function makeAudioContext() {
  makeAudioContext.gains = [];
  function node(kind) {
    const n = { kind };
    // Connecting a node to an AudioParam does NOT replace the param's value — the Web Audio spec
    // ADDS the connected signal to the intrinsic value.
    n.connect = (target) => {
      if (target && typeof target.setTargetAtTime === 'function') target.modulators.push(n);
      return n;
    };
    if (kind === 'gain') {
      n.gain = {
        value: 0, modulators: [],
        cancelScheduledValues() {},
        setTargetAtTime(v) { n.gain.value = v; }
      };
    }
    if (kind === 'bufferSource' || kind === 'oscillator') n.start = () => {};
    if (kind === 'biquadFilter' || kind === 'oscillator') n.frequency = { value: 0 };
    return n;
  }
  // Peak amplitude a gain node can actually produce: its own value plus the full depth of every
  // signal connected to its gain param.
  node.peak = (g) => g.gain.value + g.gain.modulators.reduce((s, m) => s + Math.abs(m.gain ? m.gain.value : 0), 0);
  return {
    state: 'running', currentTime: 0, destination: {}, sampleRate: 44100,
    createBuffer: (ch, len) => ({ getChannelData: () => new Float32Array(len) }),
    createBufferSource: () => node('bufferSource'),
    createBiquadFilter: () => node('biquadFilter'),
    createGain: () => { const g = node('gain'); makeAudioContext.gains.push(g); return g; },
    createOscillator: () => node('oscillator'),
    peak: node.peak,
    resume() { this.state = 'running'; }
  };
}

// --- run the page once against a board
// -----------------------------------------------------------.
async function runPage(board) {
  const els = {}; IDS.forEach(id => { els[id] = new El(id); });
  const files = {
    'data/areas.json': { areas: [{ id: 'fixture', name: 'Fixture Bay', lat: -27.6, lon: -48.5, tiles: 'https://tiles.example/{z}/{x}/{y}.png', tiles_attribution: 'test' }] },
    'data/fixture/latest.json': { days: [{ day: board.day, file: board.day + '.json' }] }
  };
  files['data/fixture/' + board.day + '.json'] = board;
  const L = makeLeaflet();
  const colours = { '--c70': '#2a9d4b', '--c40': '#e0a800', '--c1': '#e07a00', '--c0': '#c0392b', '--cna': '#999999' };
  const errors = [];
  const sandbox = {
    console: { error: (...a) => errors.push(a.map(String).join(' ')), log() {} },
    document: { getElementById: id => els[id] || null, querySelectorAll: () => [], documentElement: {} },
    getComputedStyle: () => ({ getPropertyValue: n => colours[n] || '' }),
    fetch: p => Promise.resolve(p in files
      ? { ok: true, status: 200, json: () => Promise.resolve(JSON.parse(JSON.stringify(files[p]))) }
      : { ok: false, status: 404, json: () => Promise.reject(new Error('404')) }),
    location: { href: 'https://example.test/', search: '' },
    history: { replaceState() {} },
    navigator: {}, alert() {},
    AudioContext: function () { return makeAudioContext(); },
    URL, URLSearchParams, Promise, Math, String, Array, Object, Number, Error, parseInt, setTimeout, JSON,
    L
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(I18N_JS, sandbox, { filename: 'site/static/i18n.js' });
  vm.runInContext(UI, sandbox, { filename: 'site/static/ui.js' });
  vm.runInContext(APP, sandbox, { filename: 'site/static/app.js' });
  for (let i = 0; i < 200 && !els.list.innerHTML; i++) await new Promise(r => setImmediate(r));
  return { els, L, errors };
}

// --- ui.js against a page's real markup: every start tag becomes an element with its attributes.
function pageDom(html) {
  const nodes = [];
  const tagRe = /<([a-z][a-z0-9]*)\b([^>]*)>/g;
  let m;
  while ((m = tagRe.exec(html))) {
    const attrs = {};
    m[2].replace(/([\w:-]+)="([^"]*)"/g, (_, k, v) => { attrs[k] = v; });
    const node = {
      tag: m[1], attrs, textContent: null, listeners: {},
      getAttribute(k) { return k in this.attrs ? this.attrs[k] : null; },
      setAttribute(k, v) { this.attrs[k] = String(v); },
      addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
      closest(sel) { return sel === 'button[data-lang]' && 'data-lang' in this.attrs ? this : null; }
    };
    nodes.push(node);
  }
  const documentElement = { lang: (/<html lang="([^"]*)"/.exec(html) || [])[1] };
  return {
    nodes, documentElement,
    getElementById: id => nodes.find(n => n.attrs.id === id) || null,
    querySelectorAll(sel) {
      if (sel === '#lang button[data-lang]') return nodes.filter(n => 'data-lang' in n.attrs);
      const attr = (/^\[([\w-]+)\]$/.exec(sel) || [])[1];
      if (!attr) throw new Error('pageDom: unsupported selector ' + sel);
      return nodes.filter(n => attr in n.attrs);
    }
  };
}

function runUi(html, opts) {
  opts = opts || {};
  const document = pageDom(html);
  const store = opts.store || {};
  const urls = [];
  const sandbox = {
    document,
    location: { href: 'https://example.test/' + (opts.search || ''), search: opts.search || '' },
    history: { replaceState(_s, _t, u) { urls.push(String(u)); } },
    navigator: { languages: opts.languages || [] },
    URL, URLSearchParams
  };
  if (opts.storeThrows) Object.defineProperty(sandbox, 'localStorage', { get() { throw new Error('SecurityError'); } });
  else sandbox.localStorage = { getItem: k => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); } };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  if (opts.catalog) sandbox.MAROLA_I18N = opts.catalog;
  else vm.runInContext(I18N_JS, sandbox, { filename: 'site/static/i18n.js' });
  vm.runInContext(UI, sandbox, { filename: 'site/static/ui.js' });
  const langBtn = code => document.nodes.find(n => n.attrs['data-lang'] === code);
  const click = code => document.getElementById('lang').listeners.click.forEach(fn => fn({ target: langBtn(code) }));
  return { api: sandbox.marolaI18n, document, store, urls, langBtn, click };
}

(async () => {
  console.log('site_check:');
  // 1. the fixture is a valid board, and the checker bites.
  const errs = validate(SCHEMA, BOARD);
  ok(errs.length === 0, 'site/fixtures/board.json conforms to site/board.schema.json', errs.slice(0, 3).join('; '));
  const broken = JSON.parse(JSON.stringify(BOARD)); delete broken.beaches;
  ok(validate(SCHEMA, broken).some(e => e.includes('beaches')), 'the checker reports a missing required field');
  const badEnum = JSON.parse(JSON.stringify(BOARD)); badEnum.beaches[0].hours[0].wind_level = 'gale';
  ok(validate(SCHEMA, badEnum).some(e => e.includes('not in enum')), 'the checker rejects an unknown wind_level');

  // 2. the page renders the fixture: one marker per beach, tooltips, list, card.
  const { els, L, errors } = await runPage(BOARD);
  ok(errors.length === 0, 'app.js logged no errors while loading', errors.join(' | '));
  const isWave = l => l.kind === 'marker' && l.opts && l.opts.icon && l.opts.icon.divIcon && /\bwave\b/.test(l.opts.icon.options.className);
  const markers = L.created.filter(l => l.added && isWave(l));
  ok(markers.length === BOARD.beaches.length, 'exactly one wave divIcon marker per beach (' + markers.length + ')');
  ok(L.created.filter(l => l.added && l.kind === 'circleMarker').length === 0, 'no circleMarker is left for beaches');
  const joaq = markers.find(m => String(m.tooltip).includes('Praia da Joaquina'));
  ok(!!joaq, 'a wave carries a tooltip naming Praia da Joaquina');
  const tip = joaq ? String(joaq.tooltip) : '';
  ok(/55\/100 at 10:00/.test(tip), 'Joaquina\'s tooltip head shows score/100 and the hour', tip);
  ok(joaq && joaq.tooltipOpts && joaq.tooltipOpts.sticky === true && joaq.tooltipOpts.className === 'aspects', 'the tooltip is sticky with the aspects class');
  // MIP-0009 §3: six aspects, the fixture's own numbers, every emoji followed by its word — the
  // water cell now carries a colour dot instead of an emoji (2026-09-07: a dot scans by colour at
  // a glance the way the score markers already do; a repeated 💧 doesn't distinguish
  // PRÓPRIA/IMPRÓPRIA/no-data).
  [['🌬️ breezy, 27 km/h S', 'wind band + km/h + direction'], ['🌡️ water 19.0 °C', 'water temperature'],
   ['〰️ waves 1.3 m every 6 s', 'waves + period'], ['🪼 jellyfish Low', 'jellyfish'],
   ['🐋 whales Low (best 07:00)', "whales with the day's best hour"],
   ['<i class="wdot c70"></i> 1/1 PRÓPRIA (25 Aug)', 'water verdict, a colour dot not an emoji'],
   ['🅿️ parking 3 · 🚻 toilets 1', 'facilities, only the counts the board actually has']]
    .forEach(([needle, label]) => ok(tip.includes(needle), 'tooltip cell: ' + label + ' → "' + needle + '"', tip));
  ok((tip.match(/<span/g) || []).length === 7, 'the tooltip grid has six aspect cells plus facilities (7) when the board has facility data');
  ok(joaq && joaq.opts.icon.options.html.includes('#e0a800'), "Joaquina's wave is filled with the 40-69 colour", joaq && joaq.opts.icon.options.html);
  const brava = markers.find(m => String(m.tooltip).includes('Praia Brava'));
  ok(brava && brava.opts.icon.options.html.includes('#c0392b'), "the unfit beach's wave is the red (score-0) colour", brava && brava.opts.icon.options.html);
  ok(brava && /class="wide unfit"><i class="wdot c0"><\/i> 0\/1 IMPRÓPRIA/.test(String(brava.tooltip)), "the unfit beach's water cell carries the unfit class and the red dot", brava && String(brava.tooltip));
  ok(!String(brava.tooltip).includes('facilities'), 'Brava has no facilities data on the board, so no facilities cell renders (absent, not zeroed)', String(brava.tooltip));
  // the water verdict is a sentence and gets the full width (CSS: .aspects .grid .wide spans both
  // columns and wraps) — nowrap in one column ran it past the 21 rem tooltip and clipped the
  // card.
  ok(/<span class="wide (water|unfit)"><i class="wdot/.test(tip), 'the water cell is the spanning, wrapping one', tip);
  ok((tip.match(/class="wide /g) || []).length === 2, 'the water cell and the facilities cell both span both columns', tip);
  // one filled path, not two thin ribbons and a halo: the score colour needs area at area zoom.
  ok(joaq && (joaq.opts.icon.options.html.match(/<path /g) || []).length === 1, 'the wave is a single filled path', joaq && joaq.opts.icon.options.html);
  ok(joaq && !/opacity=|drop-shadow|transform=/.test(joaq.opts.icon.options.html), 'no per-path opacity, halo transform or drop-shadow in the marker SVG', joaq && joaq.opts.icon.options.html);
  ok(joaq && joaq.opts.title === undefined && joaq.opts.keyboard === true,
    'the marker has no `title` (no native tooltip over Leaflet\'s) but stays keyboard-reachable');
  ok(joaq && joaq.element.attrs['aria-label'] === 'Praia da Joaquina',
    'the marker element is named for a screen reader with aria-label', joaq && JSON.stringify(joaq.element.attrs));
  // the legend key is the same glyph, or the key stops meaning "this shape on the map is a
  // beach".
  const keyPath = (/<span class="wave-key">.*?<path d="([^"]+)"/.exec(INDEX) || [])[1];
  const iconPath = (/<path d="([^"]+)"/.exec((joaq && joaq.opts.icon.options.html) || '') || [])[1];
  ok(!!keyPath && keyPath === iconPath, 'index.html\'s legend key draws the same path as the marker', keyPath + ' vs ' + iconPath);
  ok((els.list.innerHTML.match(/<li /g) || []).length === 2 && els.list.innerHTML.indexOf('Joaquina') < els.list.innerHTML.indexOf('Brava'),
    'the list has two entries, best score first');
  // #460: index.html's CSP has no style-src, so a `style=` chip renders white on white. The
  // score chip must carry a band class (style.css .c70/.c40/.c1/.c0/.cna) and no inline style.
  ok(/<span class="score c40">55<\/span>/.test(els.list.innerHTML) && /<span class="score c0">0<\/span>/.test(els.list.innerHTML),
    'score chip carries a band class and no inline style (list: c40 for Joaquina, c0 for unfit Brava)', els.list.innerHTML);
  ok(!/style=/.test(els.list.innerHTML), 'the list HTML has no style= attribute (blocked by the CSP)', els.list.innerHTML);
  ok(els.card.hidden === true || els.card.innerHTML === '', 'the card starts closed');
  ok(els['hour-label'].textContent === 'best hour per beach', 'the slider label starts at "best hour per beach"');
  ok(els.sound.attrs['aria-pressed'] !== 'true', 'the sound toggle does not start pressed=true');
  if (els.sound.listeners.click && els.sound.listeners.click[0]) {
    els.sound.listeners.click[0]({});
    ok(els.sound.attrs['aria-pressed'] === 'true', 'clicking the sound toggle flips aria-pressed to true');
    // Guard the other direction too: a "fix" that silenced the synth outright would satisfy the
    // silence assertion below while breaking the feature.
    const onGains = (makeAudioContext.gains || []).filter(g => (g.gain.modulators || []).length > 0);
    if (onGains[0]) {
      const onPeak = onGains[0].gain.value + onGains[0].gain.modulators.reduce((s, m) => s + Math.abs(m.gain ? m.gain.value : 0), 0);
      ok(onPeak > 0.01, 'after the first click the sound is actually audible (peak ' + onPeak.toFixed(4) + ')');
    }
    els.sound.listeners.click[0]({});
    ok(els.sound.attrs['aria-pressed'] === 'false', 'clicking it again flips aria-pressed back to false — no exception either time');
    // The bug this guards: aria-pressed flipping is not the same as the sound stopping.
    const gains = makeAudioContext.gains || [];
    const out = gains.filter(g => (g.gain.modulators || []).length > 0)[0];
    ok(!!out, 'the wave synth has an output gain with an LFO connected to its gain param');
    if (out) {
      const peak = out.gain.value + out.gain.modulators.reduce((s, m) => s + Math.abs(m.gain ? m.gain.value : 0), 0);
      ok(peak < 0.001, 'after the second click the sound is actually silent — LFO depth included (peak ' + peak.toFixed(4) + ')');
    }
  } else ok(false, 'the sound toggle has a click handler');
  if (joaq && joaq.handlers.click) {
    joaq.handlers.click({});
    ok(els.card.hidden === false && els.card.innerHTML.includes('Praia da Joaquina') && els.card.innerHTML.includes('55/100'),
      'clicking Joaquina\'s wave opens its card with the score');
    ok(/<span class="score c40">55\/100<\/span>/.test(els.card.innerHTML) && !/style=/.test(els.card.innerHTML),
      'score chip carries a band class and no inline style (card headline)', els.card.innerHTML.slice(0, 300));
    // task 4: the card's first block is the very same aspect row the tooltip shows (touch
    // parity).
    const card = els.card.innerHTML;
    const rowAt = card.indexOf('<div class="aspects">' + tip + '</div>');
    ok(rowAt >= 0 && rowAt < card.indexOf('<dl>') && rowAt > card.indexOf('</h2>'),
      'the card starts (after the h2) with the tooltip\'s exact aspect row, before the details', card.slice(0, 300));
    const sel = L.created.filter(l => l.added && isWave(l)).find(m => String(m.tooltip).includes('Praia da Joaquina'));
    ok(sel && /\bselected\b/.test(sel.opts.icon.options.className) && sel.opts.icon.options.iconSize[0] === 32 && sel.opts.zIndexOffset === 1000,
      'after selection the wave is re-drawn larger (32 px), marked selected, on top', sel && JSON.stringify(sel.opts.icon.options.iconSize));
    // "point by point" water quality (2026-09-07): opening a beach's card also plots its real
    // sampling points as their own circleMarkers — not just the one-line aggregate the card/
    // tooltip text already shows.
    const waterPts = L.created.filter(l => l.added && l.kind === 'circleMarker' && String(l.tooltip).includes('Ponto 33'));
    ok(waterPts.length === 1 && waterPts[0].latlng[0] === -27.6301 && waterPts[0].latlng[1] === -48.4479,
      "opening Joaquina's card plots its one real water-sampling point as a circleMarker at its real coordinates",
      JSON.stringify(waterPts.map(p => p.latlng)));
    ok(String(waterPts[0].tooltip).includes('PRÓPRIA'), "the point marker's own tooltip carries its real condition", String(waterPts[0].tooltip));
    // Selecting a different beach swaps the plotted points, rather than accumulating them — the
    // stub DOM's querySelector can't re-find renderCard's own close-button listener (it returns a
    // fresh element each call), so this exercises the same clear-and-replot path
    // (renderWaterPoints) a real close would, via select() on Brava instead.
    const bravaMarker = L.created.find(l => l.added && isWave(l) && String(l.tooltip).includes('Praia Brava'));
    if (bravaMarker && bravaMarker.handlers.click) bravaMarker.handlers.click({});
    const joaquinaPointsAfter = L.created.filter(l => l.added && l.kind === 'circleMarker' && String(l.tooltip).includes('Ponto 33'));
    const bravaPointsAfter = L.created.filter(l => l.added && l.kind === 'circleMarker' && String(l.tooltip).includes('Ponto 12'));
    ok(joaquinaPointsAfter.length === 0, "selecting Brava removes Joaquina's own water-point marker, not left stacked on the map");
    ok(bravaPointsAfter.length === 1 && bravaPointsAfter[0].opts.fillColor !== waterPts[0].opts.fillColor,
      "Brava's own (IMPRÓPRIA) point plots instead, in a different colour than Joaquina's PRÓPRIA one",
      JSON.stringify({ brava: bravaPointsAfter[0] && bravaPointsAfter[0].opts, joaquina: waterPts[0].opts }));
  } else ok(false, 'Joaquina\'s wave has a click handler');

  // 3. an older board without wind_level (task 1 made it optional) renders: number, no band word.
  const stripped = JSON.parse(JSON.stringify(BOARD));
  stripped.beaches.forEach(b => b.hours.forEach(h => { delete h.wind_level; }));
  const r2 = await runPage(stripped);
  const markers2 = r2.L.created.filter(l => l.added && isWave(l));
  ok(r2.errors.length === 0 && markers2.length === BOARD.beaches.length, 'a board without wind_level still renders every beach as a wave');
  const tip2 = String((markers2.find(m => String(m.tooltip).includes('Praia da Joaquina')) || {}).tooltip || '');
  ok(!/breezy|calm|strong/.test(tip2) && tip2.includes('🌬️ wind 27 km/h') && (tip2.match(/<span/g) || []).length === 7,
    'without wind_level the wind cell keeps the number and drops the band word; seven cells remain (Joaquina has facilities data)', tip2);

  // 4.
  ok(!('trails' in BOARD), 'the fixture board has no trails key yet — this is the "older board" case');
  const isPolyline = l => l.kind === 'polyline';
  ok(L.created.filter(l => l.added && isPolyline(l)).length === 0,
    'a board with no trails key draws no polyline, and (from section 2 above) still renders every beach');

  const trailed = JSON.parse(JSON.stringify(BOARD));
  trailed.trails = [
    { name: 'Trilha da Lagoinha do Leste', length_km: 2.1, difficulty: null, surface: null,
      geometry: [[-27.79, -48.49], [-27.792, -48.487], [-27.793, -48.485]],
      near_beach: { name: 'Praia da Joaquina', distance_km: 0.4 }, near_lake: null },
    { name: 'Trilha Praia do Maço-Guarda', length_km: 1.4, difficulty: 'mountain_hiking', surface: 'ground',
      geometry: [[-27.40, -48.42], [-27.401, -48.415]],
      near_beach: null, near_lake: { name: 'Lagoa do Peri', distance_km: 0.2 } }
  ];
  const r3 = await runPage(trailed);
  const trails3 = r3.L.created.filter(l => l.added && isPolyline(l));
  ok(r3.errors.length === 0, 'app.js logged no errors with a trails array present', r3.errors.join(' | '));
  ok(trails3.length === trailed.trails.length, 'exactly one polyline per trail (' + trails3.length + ')');
  const lagoinha = trails3.find(l => String(l.tooltip).includes('Trilha da Lagoinha do Leste'));
  ok(!!lagoinha && /2\.1\s*km/.test(String(lagoinha.tooltip)), 'a trail polyline\'s tooltip names it and shows its length', lagoinha && String(lagoinha.tooltip));
  ok(lagoinha && lagoinha.latlng.length === 3, 'the polyline carries the trail\'s full geometry, not just endpoints');
  ok(lagoinha && lagoinha.opts.color === '#999999', 'a trail with no difficulty tag draws grey (no-data colour)', lagoinha && lagoinha.opts.color);
  const macoGuarda = trails3.find(l => String(l.tooltip).includes('Maço-Guarda'));
  ok(macoGuarda && macoGuarda.opts.color === '#e0a800', 'a mountain_hiking trail draws the amber colour', macoGuarda && macoGuarda.opts.color);

  // --- section nav ---------------------------------------------------------------------------
  const nav = (/<nav class="sitenav"[\s\S]*?<\/nav>/.exec(INDEX) || [''])[0];
  ok(nav.length > 0, 'the page has a section nav — /docs/ is reachable without typing the URL');
  ok(/<a href="\/docs\/" data-i18n="nav\.docs">docs<\/a>/.test(nav), 'Docs is a real link to the published docs');
  ok(/<a href="about\.html" data-i18n="nav\.about">sobre<\/a>/.test(nav), 'About is a real link to the about page');
  ok((nav.match(/<span aria-disabled="true">/g) || []).length === 4,
    'the four sections with no page yet are spans, not links');
  ok(!/<a[^>]+href="#"/.test(nav), 'no href="#" — a link that goes nowhere is worse than "soon"');
  ok(INDEX.indexOf('<nav class="sitenav"') < INDEX.indexOf('<header class="bar"'),
    'the nav is the first thing on the page, above the header');
  ok(INDEX.indexOf('<nav class="sitenav"') < INDEX.indexOf('<footer id="footer"'),
    'and outside #footer, which renderFooter() overwrites on every board load');
  ok(/el\.footer\.innerHTML\s*=/.test(APP),
    'renderFooter still replaces #footer wholesale — the reason for the assertion above');

  // --- the repo link on every page (#498) ------------------------------------------------------
  const REPO_URL = 'https://github.com/marola-dev/marola';
  for (const page of ['index.html', 'about.html']) {
    const html = fs.readFileSync(path.join(ROOT, 'site/static', page), 'utf8');
    const pageNav = (/<nav class="sitenav"[\s\S]*?<\/nav>/.exec(html) || [''])[0];
    ok(pageNav.includes('<a class="gh" href="' + REPO_URL + '"'), page + ': the section nav links the GitHub repo');
    ok(/<a class="gh"[^>]*aria-label="[^"]+"/.test(pageNav), page + ': the repo link keeps a name when the phone layout hides its text');
    ok(!html.includes('h0ffmann/marola'), page + ': no pre-rename repo URL');
  }
  ok(APP.includes("var REPO = '" + REPO_URL + "'"), "app.js's footer link names the current repo");

  // --- lowercase house style, and what it must not touch ---------------------------------------
  const CSS = fs.readFileSync(path.join(ROOT, 'site/static/style.css'), 'utf8');
  ok(/body\s*\{\s*text-transform:\s*lowercase/.test(CSS), 'the site is lowercase as a house style');
  const exempt = (/\.src,[\s\S]*?\{\s*text-transform:\s*none;?\s*\}/.exec(CSS) || [''])[0];
  for (const sel of ['.src', '.water', '.list li', '.card h2', '.leaflet-tooltip .head']) {
    ok(exempt.includes(sel), 'keeps its own case: ' + sel);
  }
  ok(/\.bar h1\s*\{\s*text-transform:\s*lowercase/.test(CSS),
    "marola's own name stays lowercase, even inside an exempted container");
  ok(/class="src"/.test(APP), 'app.js tags provider names so Open-Meteo and IMA/SC survive the style');

  // --- MIP-0054 task 1: the language toggle, resolveLang, t() and the catalogs -------------------
  const PAGES = { 'index.html': INDEX, 'about.html': ABOUT };
  for (const [page, html] of Object.entries(PAGES)) {
    ok(/<html lang="pt-BR">/.test(html), page + ': <html lang="pt-BR"> in the source, so the first paint is Portuguese');
    const pageNav = (/<nav class="sitenav"[\s\S]*?<\/nav>/.exec(html) || [''])[0];
    const toggle = (/<div id="lang"[\s\S]*?<\/div>/.exec(pageNav) || [''])[0];
    const buttons = toggle.match(/<button [^>]*>/g) || [];
    ok(buttons.length === 2 && buttons.every(b => /type="button"/.test(b) && /aria-pressed="(true|false)"/.test(b)),
      page + ': #lang is two <button type="button">s with aria-pressed', toggle);
    ok(/lang="pt-BR" aria-label="português \(Brasil\)"/.test(buttons[0] || '') && /lang="en" aria-label="English"/.test(buttons[1] || ''),
      page + ': each button carries its own lang and an endonym aria-label', buttons.join(' '));
    ok(/<div id="lang"[\s\S]*?<\/div>\s*<a class="gh"/.test(pageNav), page + ': #lang sits inside .sitenav, immediately before a.gh');
    const order = ['i18n.js', 'ui.js'].map(f => html.indexOf('<script src="' + f + '"'));
    const others = (html.match(/<script src="([^"]+)"/g) || []).filter(t => !/"(i18n|ui)\.js"/.test(t)).map(t => html.indexOf(t));
    ok(order[0] > 0 && order[0] < order[1] && others.every(i => i > order[1]),
      page + ': i18n.js, then ui.js, load before every other script');
    const keys = [];
    html.replace(/data-i18n(?:-[a-z-]+)?="([^"]+)"/g, (_, k) => { keys.push(k); });
    const missing = keys.filter(k => !(k in CATALOGS['pt-BR']) || !(k in CATALOGS.en));
    ok(keys.length > 5 && missing.length === 0, page + ': every data-i18n* key (' + keys.length + ') exists in pt-BR and en', missing.join(', '));
    const runs = runUi(html);
    ok(runs.api.lang() === 'pt-BR' && runs.document.documentElement.lang === 'pt-BR', page + ': no input renders pt-BR');
    const en = runUi(html, { search: '?lang=en' });
    const title = en.document.nodes.find(n => n.tag === 'title');
    ok(en.document.documentElement.lang === 'en' && title.textContent === CATALOGS.en[title.attrs['data-i18n']],
      page + ': ?lang=en sets <html lang> and the document title from the en catalog', title.textContent);
    ok(en.langBtn('en').attrs['aria-pressed'] === 'true' && en.langBtn('pt-BR').attrs['aria-pressed'] === 'false',
      page + ': the en button starts pressed under ?lang=en');
    const gh = en.document.nodes.find(n => n.attrs.class === 'gh');
    ok(gh.attrs['aria-label'] === CATALOGS.en['gh.label'] && gh.attrs.title === CATALOGS.en['gh.title'],
      page + ': applyLang rewrites aria-label and title attributes');
    const r = runUi(html);
    r.click('en');
    ok(r.langBtn('en').attrs['aria-pressed'] === 'true' && r.langBtn('pt-BR').attrs['aria-pressed'] === 'false' &&
      r.store['marola.lang'] === 'en' && /[?&]lang=en\b/.test(r.urls[r.urls.length - 1] || '') && r.api.lang() === 'en',
      page + ': clicking en flips aria-pressed, stores marola.lang and writes ?lang=en', JSON.stringify({ store: r.store, urls: r.urls }));
    let heard = null; r.api.onLang(l => { heard = l; }); r.click('pt-BR');
    ok(heard === 'pt-BR' && r.document.documentElement.lang === 'pt-BR', page + ': clicking back fires onLang and restores <html lang>');
    let threw = null, t2 = null;
    try { t2 = runUi(html, { storeThrows: true, languages: ['en-US'] }); t2.click('pt-BR'); } catch (e) { threw = e; }
    ok(!threw && t2 && t2.api.lang() === 'pt-BR', page + ': a localStorage that throws still resolves (en-US) and still toggles', threw && threw.message);
  }
  const probe = runUi(INDEX).api;
  const S = ['pt-BR', 'en'];
  const rl = o => probe.resolveLang(Object.assign({ supported: S }, o));
  ok(rl({}) === 'pt-BR', 'resolveLang: no input → pt-BR');
  ok(rl({ param: 'en' }) === 'en', 'resolveLang: ?lang=en → en');
  ok(rl({ param: 'pt-BR', stored: 'en', languages: ['en-US'] }) === 'pt-BR', 'resolveLang: ?lang=pt-BR beats a stored en');
  ok(rl({ stored: 'en', languages: ['pt-BR'] }) === 'en', 'resolveLang: a stored choice beats the browser languages');
  ok(rl({ languages: ['en-US'] }) === 'en', "resolveLang: languages ['en-US'] → en");
  ok(rl({ languages: ['fr-FR'] }) === 'pt-BR', "resolveLang: languages ['fr-FR'] → pt-BR");
  ok(rl({ languages: ['fr', 'pt-PT', 'en'] }) === 'pt-BR' && rl({ languages: ['fr', 'en-GB'] }) === 'en', 'resolveLang: the first shipped primary subtag wins');
  ok(rl({ param: 'x-pseudo' }) === 'x-pseudo', 'resolveLang: x-pseudo from the param');
  ok(rl({ stored: 'x-pseudo', languages: ['x-pseudo'] }) === 'pt-BR', 'resolveLang: x-pseudo never from the store or the browser');
  ok(rl({ param: 'de' }) === 'pt-BR', 'resolveLang: an unshipped ?lang= falls through');
  const syn = runUi(ABOUT, { catalog: {
    'pt-BR': { n: '{n, plural, one {# praia} other {# praias}}', x: 'ondas {x} m', only: 'só pt', s: '{w, select, yes {sim} other {não}}' },
    en: { n: '{n, plural, one {# beach} other {# beaches}}', x: 'waves {x} m', s: '{w, select, yes {yes} other {no}}' }
  } }).api;
  ok(syn.t('n', { n: 1 }) === '1 praia' && syn.t('n', { n: 2 }) === '2 praias', 't pluralises {n, plural, one {# praia} other {# praias}} for 1 and 2', syn.t('n', { n: 1 }) + ' / ' + syn.t('n', { n: 2 }));
  ok(syn.t('x', { x: 1.3 }) === 'ondas 1,3 m', 't formats 1.3 as 1,3 in pt-BR', syn.t('x', { x: 1.3 }));
  ok(syn.t('s', { w: 'yes' }) === 'sim' && syn.t('s', { w: 'maybe' }) === 'não', 't selects, falling to other');
  syn.setLang('en');
  ok(syn.t('n', { n: 1 }) === '1 beach' && syn.t('x', { x: 1.3 }) === 'waves 1.3 m', 't in en: plural and 1.3');
  ok(syn.t('only') === 'só pt' && syn.t('nope') === 'nope', 't falls back to pt-BR, then to the key');
  ok(probe.t('footer.generated', { when: 'w', area: 'a', day: 'd', n: 2, sources: 's' }).includes('· 2 praias ·'),
    'the real catalog pluralises footer.generated');
  // Lowercase house style in the catalogs; these are names marola did not choose.
  const CASE_OK = ['°C', 'UV', 'km/h', 'PRÓPRIA', 'IMPRÓPRIA', 'GitHub', 'Open-Meteo', 'IMA/SC', 'OpenStreetMap'];
  for (const [lang, cat] of Object.entries(CATALOGS)) {
    const shouty = Object.entries(cat).filter(([, v]) => {
      const rest = CASE_OK.reduce((acc, w) => acc.split(w).join(''), v);
      return rest !== rest.toLowerCase();
    }).map(([k]) => k);
    ok(shouty.length === 0, lang + '.json: every value is lowercase outside the allowlist', shouty.join(', '));
  }

  if (fails === 0) { console.log('site_check: ok'); process.exit(0); }
  console.error('site_check: ' + fails + ' failure(s)'); process.exit(1);
})().catch(e => { console.error('site_check: crashed —', e); process.exit(1); });
