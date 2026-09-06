#!/usr/bin/env node
/* site_check — runs site/static/app.js against site/fixtures/board.json in a stub DOM and a stub
 * Leaflet (`L`), no browser, no network, no dependencies (MIP-0009 task 2; §5 said this harness
 * existed since MIP-0008 — it did not, so here it is). It validates the fixture against
 * site/board.schema.json with the same JSON-Schema subset BoardSpec's SchemaCheck uses, then asserts
 * what the page does with it. Tasks 3-4 extend the assertions (wave markers, the aspects tooltip,
 * the card row); this file's job today is the baseline those tasks turn red then green.
 *
 *   node scripts/site_check.js        # run by `just quality-other` and ci.yml's quality-other job
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const APP = fs.readFileSync(path.join(ROOT, 'site/static/app.js'), 'utf8');
const SCHEMA = JSON.parse(fs.readFileSync(path.join(ROOT, 'site/board.schema.json'), 'utf8'));
const BOARD = JSON.parse(fs.readFileSync(path.join(ROOT, 'site/fixtures/board.json'), 'utf8'));
const INDEX = fs.readFileSync(path.join(ROOT, 'site/static/index.html'), 'utf8');

let fails = 0;
function ok(cond, label, detail) {
  if (cond) console.log('  ok   ' + label);
  else { console.log('  FAIL ' + label + (detail ? ' — ' + detail : '')); fails++; }
}

// --- the JSON-Schema subset the board contract uses (mirror of BoardSpec.SchemaCheck) ----------
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

// --- a DOM just big enough for app.js -----------------------------------------------------------
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
const IDS = ['area', 'days', 'near', 'toggle-list', 'hourbar', 'hour', 'hour-label', 'list', 'card', 'footer', 'status'];
// 'smoke' is deliberately absent: the page must tolerate a build without the panel (app.js header).

// --- a Leaflet just big enough for app.js --------------------------------------------------------
function makeLeaflet() {
  const created = []; // every marker/circleMarker, in creation order
  const layer = (kind, latlng, opts) => {
    const l = { kind, latlng, opts, tooltip: null, tooltipOpts: null, handlers: {}, added: false };
    l.addTo = function (m) { this.added = true; m.layers.push(this); return this; };
    // Leaflet hands back the marker's DOM node once it is on the map; app.js names it for a screen
    // reader through this (it dropped `title`, which drew a second, native tooltip over Leaflet's).
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
    divIcon: (o) => ({ divIcon: true, options: o }),
    DomEvent: { stopPropagation() {} }
  };
  return L;
}

// --- run the page once against a board -----------------------------------------------------------
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
    document: { getElementById: id => els[id] || null, documentElement: {} },
    getComputedStyle: () => ({ getPropertyValue: n => colours[n] || '' }),
    fetch: p => Promise.resolve(p in files
      ? { ok: true, status: 200, json: () => Promise.resolve(JSON.parse(JSON.stringify(files[p]))) }
      : { ok: false, status: 404, json: () => Promise.reject(new Error('404')) }),
    location: { href: 'https://example.test/', search: '' },
    history: { replaceState() {} },
    navigator: {}, alert() {},
    URL, URLSearchParams, Promise, Math, String, Array, Object, Number, Error, parseInt, setTimeout, JSON,
    L
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(APP, sandbox, { filename: 'site/static/app.js' });
  for (let i = 0; i < 200 && !els.list.innerHTML; i++) await new Promise(r => setImmediate(r));
  return { els, L, errors };
}

(async () => {
  console.log('site_check:');
  // 1. the fixture is a valid board, and the checker bites
  const errs = validate(SCHEMA, BOARD);
  ok(errs.length === 0, 'site/fixtures/board.json conforms to site/board.schema.json', errs.slice(0, 3).join('; '));
  const broken = JSON.parse(JSON.stringify(BOARD)); delete broken.beaches;
  ok(validate(SCHEMA, broken).some(e => e.includes('beaches')), 'the checker reports a missing required field');
  const badEnum = JSON.parse(JSON.stringify(BOARD)); badEnum.beaches[0].hours[0].wind_level = 'gale';
  ok(validate(SCHEMA, badEnum).some(e => e.includes('not in enum')), 'the checker rejects an unknown wind_level');

  // 2. the page renders the fixture: one marker per beach, tooltips, list, card
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
  // MIP-0009 §3: six aspects, the fixture's own numbers, every emoji followed by its word
  [['🌬️ breezy, 27 km/h S', 'wind band + km/h + direction'], ['🌡️ water 19.0 °C', 'water temperature'],
   ['〰️ waves 1.3 m every 6 s', 'waves + period'], ['🪼 jellyfish Low', 'jellyfish'],
   ['🐋 whales Low (best 07:00)', 'whales with the day\'s best hour'], ['💧 1/1 PRÓPRIA (25 Aug)', 'water verdict']]
    .forEach(([needle, label]) => ok(tip.includes(needle), 'tooltip cell: ' + label + ' → "' + needle + '"', tip));
  ok((tip.match(/<span/g) || []).length === 6, 'the tooltip grid has exactly six cells');
  ok(joaq && joaq.opts.icon.options.html.includes('#e0a800'), 'Joaquina\'s wave is filled with the 40-69 colour', joaq && joaq.opts.icon.options.html);
  const brava = markers.find(m => String(m.tooltip).includes('Praia Brava'));
  ok(brava && brava.opts.icon.options.html.includes('#c0392b'), 'the unfit beach\'s wave is the red (score-0) colour', brava && brava.opts.icon.options.html);
  ok(brava && /class="wide unfit">💧 0\/1 IMPRÓPRIA/.test(String(brava.tooltip)), 'the unfit beach\'s water cell carries the unfit class', brava && String(brava.tooltip));
  // the water verdict is a sentence and gets the full width (CSS: .aspects .grid .wide spans both
  // columns and wraps) — nowrap in one column ran it past the 21 rem tooltip and clipped the card
  ok(/<span class="wide (water|unfit)">💧/.test(tip), 'the water cell is the spanning, wrapping one', tip);
  ok((tip.match(/class="wide /g) || []).length === 1, 'only the water cell spans both columns', tip);
  // one filled path, not two thin ribbons and a halo: the score colour needs area at area zoom
  ok(joaq && (joaq.opts.icon.options.html.match(/<path /g) || []).length === 1, 'the wave is a single filled path', joaq && joaq.opts.icon.options.html);
  ok(joaq && !/opacity=|drop-shadow|transform=/.test(joaq.opts.icon.options.html), 'no per-path opacity, halo transform or drop-shadow in the marker SVG', joaq && joaq.opts.icon.options.html);
  ok(joaq && joaq.opts.title === undefined && joaq.opts.keyboard === true,
    'the marker has no `title` (no native tooltip over Leaflet\'s) but stays keyboard-reachable');
  ok(joaq && joaq.element.attrs['aria-label'] === 'Praia da Joaquina',
    'the marker element is named for a screen reader with aria-label', joaq && JSON.stringify(joaq.element.attrs));
  // the legend key is the same glyph, or the key stops meaning "this shape on the map is a beach"
  const keyPath = (/<span class="wave-key">.*?<path d="([^"]+)"/.exec(INDEX) || [])[1];
  const iconPath = (/<path d="([^"]+)"/.exec((joaq && joaq.opts.icon.options.html) || '') || [])[1];
  ok(!!keyPath && keyPath === iconPath, 'index.html\'s legend key draws the same path as the marker', keyPath + ' vs ' + iconPath);
  ok((els.list.innerHTML.match(/<li /g) || []).length === 2 && els.list.innerHTML.indexOf('Joaquina') < els.list.innerHTML.indexOf('Brava'),
    'the list has two entries, best score first');
  ok(els.card.hidden === true || els.card.innerHTML === '', 'the card starts closed');
  ok(els['hour-label'].textContent === 'best hour per beach', 'the slider label starts at "best hour per beach"');
  if (joaq && joaq.handlers.click) {
    joaq.handlers.click({});
    ok(els.card.hidden === false && els.card.innerHTML.includes('Praia da Joaquina') && els.card.innerHTML.includes('55/100'),
      'clicking Joaquina\'s wave opens its card with the score');
    const sel = L.created.filter(l => l.added && isWave(l)).find(m => String(m.tooltip).includes('Praia da Joaquina'));
    ok(sel && /\bselected\b/.test(sel.opts.icon.options.className) && sel.opts.icon.options.iconSize[0] === 32 && sel.opts.zIndexOffset === 1000,
      'after selection the wave is re-drawn larger (32 px), marked selected, on top', sel && JSON.stringify(sel.opts.icon.options.iconSize));
  } else ok(false, 'Joaquina\'s wave has a click handler');

  // 3. an older board without wind_level (task 1 made it optional) renders: number, no band word
  const stripped = JSON.parse(JSON.stringify(BOARD));
  stripped.beaches.forEach(b => b.hours.forEach(h => { delete h.wind_level; }));
  const r2 = await runPage(stripped);
  const markers2 = r2.L.created.filter(l => l.added && isWave(l));
  ok(r2.errors.length === 0 && markers2.length === BOARD.beaches.length, 'a board without wind_level still renders every beach as a wave');
  const tip2 = String((markers2.find(m => String(m.tooltip).includes('Praia da Joaquina')) || {}).tooltip || '');
  ok(!/breezy|calm|strong/.test(tip2) && tip2.includes('🌬️ wind 27 km/h') && (tip2.match(/<span/g) || []).length === 6,
    'without wind_level the wind cell keeps the number and drops the band word; six cells remain', tip2);

  if (fails === 0) { console.log('site_check: ok'); process.exit(0); }
  console.error('site_check: ' + fails + ' failure(s)'); process.exit(1);
})().catch(e => { console.error('site_check: crashed —', e); process.exit(1); });
