// site_check — run site/static/app.js in a stub DOM against a built site/dist and print what it
// rendered: markers, list rows, footer, the "Last live run" panel (MIP-0008) when smoke/ exists.
const fs = require('fs'), path = require('path');
const dist = process.argv[2] || 'site/dist';
if (!fs.existsSync(path.join(dist, 'data', 'areas.json'))) { console.error('no ' + dist + '/data/areas.json — build the site first'); process.exit(1); }
function El(id) {
  return { id, innerHTML: '', textContent: '', hidden: false, value: '', max: 0, children: [], dataset: {}, _cls: new Set(),
    classList: { toggle(c, on) { on ? this._s.add(c) : this._s.delete(c); }, _s: new Set() },
    addEventListener() {}, setAttribute() {}, getAttribute() { return null; },
    querySelector() { return { addEventListener() {} }; } };
}
const els = {};
global.document = {
  getElementById(id) { return els[id] || (els[id] = El(id)); },
  documentElement: {}, addEventListener() {}
};
global.getComputedStyle = () => ({ getPropertyValue: () => '#000' });
global.location = { search: '', href: 'http://localhost/' };
global.history = { replaceState() {} };
global.navigator = {};
global.URLSearchParams = URLSearchParams; global.URL = URL;
const layer = () => ({ addTo() { return this; }, bindTooltip() { return this; }, on() { return this; } });
const calls = { markers: [], setView: [] };
global.L = {
  map: () => ({ on() {}, removeLayer() {}, setView(c, z) { calls.setView.push([c, z]); }, fitBounds() {}, panTo() {} }),
  tileLayer: () => layer(),
  circleMarker: (ll, opts) => { calls.markers.push({ ll, opts }); return layer(); },
  DomEvent: { stopPropagation() {} }
};
global.fetch = async (p) => {
  const f = path.join(dist, p);
  if (!fs.existsSync(f)) return { ok: false, status: 404 };
  return { ok: true, status: 200, json: async () => JSON.parse(fs.readFileSync(f, 'utf8')) };
};
require(path.join(process.cwd(), 'site/static/app.js'));
process.on('unhandledRejection', (e) => { console.error('app.js rejected:', e); process.exit(1); });
setTimeout(() => {
  const smoke = els.smoke || {}, footer = els.footer || {}, list = els.list || {};
  const dashed = calls.markers.filter(m => m.opts.dashArray);
  const beaches = calls.markers.length - dashed.length;
  console.log('beach markers:', beaches, '| run marker:', dashed.length);
  console.log('list rows:', (list.innerHTML || '').split('<li').length - 1);
  console.log('footer:', (footer.innerHTML || '').replace(/<[^>]+>/g, '').slice(0, 140));
  if (smoke.innerHTML) console.log('smoke panel:', smoke.hidden ? 'hidden' : smoke.innerHTML.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').slice(0, 220));
  if (!beaches) { console.log('SITE CHECK FAILED: no beach markers drawn'); process.exit(1); }
  console.log('SITE CHECK OK');
}, 400);
