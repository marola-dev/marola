# MIP-0037: A PWA for marola's map — offline-tolerant, installable, no build step

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-07: "review the mips in roadmap given by Kimi to check if they are still valid, if yes, create the MIP" — K8 in `docs/ROADMAP.md` §7) |
| **Created** | 2026-09-07 |
| **Phase** | 1 (site/static only — no bot, no Azure, no earlier-phase prerequisite missing) |
| **Related** | ROADMAP.md §7 K8 ("PWA for the map... cheap win after MIP-0009, same `app.js`, avoid two hands in one file"); MIP-0009 (its blocker — Accepted, done, unblocking this); MIP-0005 §9 ("plain files, no build" — a service worker is plain JS, still no build, K8's own note); MIP-0033 §5.2 (a second, unrelated reason `app.js`/`index.html` are getting busy this session — sequencing note in §11) |
| **Effort** | S — one new file (`sw.js`), one `manifest.json`, ~15 lines in `index.html`, a cache-versioning line in `site.yml`. No new dependency, no build step, no server |
| **Gain** | `user value` (the board keeps working — stale but visible — on a flaky beach connection, exactly where marola is used); `infra/dev-loop` (an install prompt is free distribution K8's own text names) |
| **Effort vs Gain** | `cheap win` — K8's own blocker (MIP-0009) is done; nothing else gates this |
| **Depends on** | Nothing blocking. MIP-0009 (done) was the only named blocker |
| **Blocked by** | none |
| **Risk** | A cached-but-stale board silently shown as current would contradict `site/static/app.js`'s own "computed once, no tracking" honesty — the design must make staleness visible, not hide it (§5, §8) |
| **Cost so far** | — |

## 1. Summary

A service worker (`site/static/sw.js`) caches the app shell (`index.html`, `app.js`, `style.css`,
`vendor/leaflet.*`) and the last successfully fetched board JSON, so a visitor who loses signal
mid-session — a real scenario at a beach — keeps a working, if stale, map instead of a blank
error. A `manifest.json` makes the site installable (add-to-home-screen) on Android/desktop; iOS
Safari gets the same caching benefit without a real install prompt (§8). No build step, no new
dependency — the same "plain files" constraint MIP-0005 §9 already commits to.

## 2. Motivation

marola's own `docs/RUN-LOCALLY.md`/`app.js` comments already treat "no server, no tracking,
computed once" as a selling point; today that promise has a gap: `fetchJson` throws a bare error
on any network failure (`site/static/app.js`'s own `fail()` handler), and a visitor on a beach with
one bar of signal sees "could not load the board" with no fallback, even though the exact same
board was fetched successfully thirty seconds ago in the same tab. A PWA's offline cache is a
direct fix for the one failure mode most likely for this product's actual use case.

## 3. User-visible change

- First visit online: nothing changes, except a browser install prompt may appear (Android Chrome/
  desktop; not iOS Safari — §8) and the app registers a service worker silently in the background.
- A later visit with no signal: the map loads from cache instead of a blank error page, with a new
  banner in the existing `#footer`/`#status` line: `"offline — showing the last board from <time>"`
  — reusing the footer's existing `status()`/`el.status` plumbing, not a new UI element.
- A later visit *with* signal after being offline: the fetch succeeds normally, the banner clears,
  no special-casing needed beyond the existing `fail()`/`status()` calls already in `app.js`.

## 4. Data sources and dependencies reviewed

### 4.1 GitHub Pages serves over HTTPS — required for a service worker

Service workers only register on a secure origin (`https:` or `localhost`) per the Service Worker
spec (well-established web-platform constraint, not independently re-fetched this session — the
same "check before trusting" rule flags this as **not checked live**, §11). `marola.dev` is
already HTTPS via GitHub Pages (`.github/workflows/site.yml`'s own comments: custom domain,
"Enforce HTTPS" ticked in Settings → Pages, MIP-0005) — the precondition already holds.

### 4.2 Cache-busting against Pages' CDN — verified against this repo's own file

**Read directly, 2026-09-07:** `.github/workflows/site.yml` line ~101 already documents Pages' CDN
caching each file independently at `cache-control: max-age=600` — a fact this repo already knows
and has hit before (the file's own comment cites `fix/site-smoke-panel-null` as a real incident
from this exact caching behavior). A service worker cache is a *second*, longer-lived cache layer
on top of that 600s CDN cache — the service worker's own cache must be explicitly versioned and
invalidated by `site.yml`'s deploy step (§5), or a stale service-worker cache could outlive even a
real site update, worse than the 600s CDN case this repo has already been burned by once.

### 4.3 iOS Safari PWA behaviour — not independently verified this session

ROADMAP.md §7's own checklist already named this as unverified ("iOS Safari install-prompt
behaviour"). Widely known, not re-fetched here: iOS Safari supports the offline cache (service
workers work) but not the `beforeinstallprompt` install-banner Android/Chrome uses — an iOS user
adds to home screen manually via the Share sheet, same as any web page. **Flagged as not checked
live** — §11.

## 5. Design

**`site/static/sw.js`** (new, plain JS, no build):

```js
const CACHE = 'marola-v{{VERSION}}';         // {{VERSION}} stamped by site.yml at deploy, §5.4
const SHELL = ['index.html', 'app.js', 'style.css', 'vendor/leaflet.js', 'vendor/leaflet.css'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});
self.addEventListener('activate', (e) => {
  // Drop every cache from a previous VERSION — the "must not outlive a real deploy" rule from §4.2.
  e.waitUntil(caches.keys().then((keys) =>
    Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  const isBoard = url.pathname.includes('/data/');
  e.respondWith(
    isBoard
      // Board JSON: network-first (a visitor online should always see the freshest board — this
      // is a *fallback* cache, not a cache the app prefers over a live fetch), cache on success,
      // fall back to cache only when the network fails.
      ? fetch(e.request).then((r) => {
          const copy = r.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy));
          return r;
        }).catch(() => caches.match(e.request))
      // App shell: cache-first (already versioned/invalidated on deploy by `activate` above).
      : caches.match(e.request).then((cached) => cached || fetch(e.request))
  );
});
```

**`site/static/manifest.json`** (new):

```json
{
  "name": "marola — the ocean intelligence layer",
  "short_name": "marola",
  "start_url": "/",
  "display": "standalone",
  "background_color": "#f6f8fa",
  "theme_color": "#ffffff",
  "icons": [{ "src": "icon-192.png", "sizes": "192x192", "type": "image/png" }]
}
```

(An `icon-192.png` needs to exist — reuse the existing wave-and-swell `<svg>` already inline in
`index.html`'s `<h1>`, rendered to a PNG once, checked in; not designed further here.)

**`site/static/index.html`**: `<link rel="manifest" href="manifest.json">`, plus a small
registration script (inline is fine, matching this file's existing no-external-script-for-trivia
style, or a few lines appended to `app.js`):

```js
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js');
```

**Offline banner**: `app.js`'s existing `fail(err)` function already sets `status(...)` on a fetch
failure; extend it to check `!navigator.onLine` (or that the failed fetch resolved from the service
worker's cache fallback) and print the "offline — showing the last board from `<time>`" text
instead of the current raw error message — reusing the existing `#status` element, not a new one.

**`.github/workflows/site.yml`**: the deploy step stamps `sw.js`'s `{{VERSION}}` placeholder with
the deploy's commit SHA (`sed`, the exact pattern this file already uses for the `?v=<sha>` cache
buster on `app.js`/`style.css` tags per `docs/RUN-LOCALLY.md`'s own description of that existing
mechanism) — so every real deploy forces the `activate` handler to drop the previous cache,
directly closing the §4.2 risk.

## 6. Scoring / safety impact

None. A cached board is, by construction, the same board `Swimability`/`Recommender` already
computed and served — nothing about scoring changes. The one safety-adjacent property is
**staleness must be visible, not silent** (§3's banner) — an offline visitor seeing yesterday's
water-quality verdict without knowing it's yesterday's would be a real regression from today's
honest "computed once" framing.

## 7. Verification plan

- Extend `scripts/site_check.js` (already runs in `just quality`) minimally: a fixture asserting
  `sw.js`/`manifest.json` are syntactically valid (parse-check, `sw.js` isn't runnable in that
  harness's stub DOM — no `ServiceWorkerRegistration` stub exists there, and adding one is out of
  scope for a first PR) and that `index.html` links the manifest.
- A live check before merge: `just site-build`, serve `site/dist/` locally over HTTPS (or
  `localhost`, which the Service Worker spec exempts from the HTTPS requirement — usable for local
  testing without a cert), load once online, go offline (devtools "Offline" throttle), reload —
  confirm the map still renders with the offline banner, and that reload does *not* show the
  before-any-registration blank-error state MIP-0037 exists to fix.
- "Done" = an installed/added-to-home-screen icon works (Android/desktop at minimum, per §4.3's
  iOS caveat), a real offline reload shows the last board with the banner, and a real redeploy
  (new commit SHA) is confirmed to evict the previous service-worker cache, not just the 600s CDN
  one.

## 8. Risks, limitations, and honest caveats

- **iOS Safari has no install-prompt UI** (§4.3) — the offline-cache benefit still applies there,
  only the "installable" half of this MIP's title doesn't, on that one platform.
- **A service worker is a second cache layer this repo has already been burned by the first layer
  of** (§4.2's `fix/site-smoke-panel-null` precedent) — the version-stamped cache name plus
  `activate`'s cache-eviction loop is the mitigation; skipping either would recreate that bug one
  layer deeper and harder to debug (a visitor's own browser cache, not just Pages' CDN).
- **Board-JSON is network-first by design** (§5), not cache-first — an online visitor must always
  see marola's actual live computation, never a stale cache preferred over a fresh fetch; this is
  the one place "PWA" and "always show the freshest board" could conflict, and network-first
  resolves it in the direction MIP-0005's own honesty framing requires.
- **Two other branches this same session also touch `app.js`/`index.html`** (MIP-0030's trail
  layer, MIP-0033's chat widget, MIP-0035's plugin API) — exactly the "avoid two hands in one
  file" collision K8's own ROADMAP entry warned about. This MIP should be sequenced *after* those
  land, not merged into the same busy window, to keep each diff reviewable on its own (§11).

## 9. Alternatives considered

- **Do nothing.** The site already works when online; a flaky-signal visitor is a real but not
  catastrophic gap. Rejected only because the fix is genuinely cheap (§Effort) and the exact
  failure mode (beach = bad signal) is this product's own primary use case, not a hypothetical.
- **A full PWA framework (Workbox, etc.)** — pulls in a build step this repo's `site/static/`
  explicitly avoids (MIP-0005 §9); a ~30-line hand-written service worker covers this MIP's actual
  scope (two file types, one JSON endpoint) without one.

## 10. Exam-coverage mapping

None directly. (Offline-tolerant client design is a general web-platform practice, not an
AI-103/AI-500 domain row.)

## 11. Open questions

- **Sequencing**: land after MIP-0030/MIP-0033/MIP-0035 merge, per §8's "two hands in one file"
  caveat — not a technical blocker, a review-hygiene one.
- iOS Safari's exact current PWA capability set (§4.3) — not independently verified live this
  session, carried over from `ROADMAP.md`'s own unchecked item; worth a direct fetch of Apple's
  current WebKit/Safari PWA docs before the implementing PR, not assumed stable since ROADMAP was
  written.
- Whether the app-shell cache-first strategy needs a "new version available, reload?" prompt (the
  classic PWA UX question) — not designed here; `activate`'s forced cache-drop on redeploy means a
  visitor with the tab already open keeps the *old* shell until they navigate again, a smaller gap
  than not caching at all but worth a follow-up if it proves confusing in practice.

## Appendix

### Checked live
- `.github/workflows/site.yml` — read directly, 2026-09-07: confirms Pages CDN's
  `cache-control: max-age=600` per file (§4.2) and the existing `?v=<sha>` cache-buster mechanism
  this MIP's `sw.js` versioning reuses; confirms HTTPS via the custom-domain/Enforce-HTTPS setup
  (§4.1).
- `docs/ROADMAP.md` §7 (K8's own row and the provider-query checklist's K8 line) — read directly,
  2026-09-07: the source of this MIP's scope and its own flagged-unverified items (§4.3, §11).

### Not checked
- The Service Worker spec's HTTPS requirement (§4.1) — well-established web-platform behavior,
  not independently re-fetched from a spec/MDN page this session.
- iOS Safari's current PWA support matrix (§4.3, §11) — carried over from `ROADMAP.md`'s own
  unchecked item, not independently verified.
