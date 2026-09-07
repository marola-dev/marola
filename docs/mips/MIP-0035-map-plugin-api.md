# MIP-0035: A plugin API for marola's map — third-party layers, Windy-style

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-07: "create mip to add plugin possibility like as in windycom/windy-plugin-template, does it require marola.dev move out from GitHub pages? it should be first requirement of release 1 (not 0)") |
| **Created** | 2026-09-07 |
| **Phase** | 0 (site/static only — no bot, no Azure, no earlier-phase prerequisite missing) |
| **Related** | MIP-0005 (the static site and its "no server" decision, `§9`), MIP-0009 (the Leaflet marker/tooltip layer this plugin API sits alongside), MIP-0030 (the trails `L.polyline` layer — the nearest precedent for "a new map layer with its own styling/tooltip", useful as a worked example of what a plugin would look like internally), MIP-0033 (Release 0 and the Milestones/`RELEASES.md` mechanism — this MIP is explicitly scoped as a **Release 1** item, not Release 0, per the request) |
| **Effort** | M — one small, stable global API surface in `app.js` (no rewrite of its existing rendering), one committed manifest file, one `<script>`-injection loader, a CSP meta tag, docs and an example plugin. No new backend, no new dependency |
| **Gain** | user value (a community-contributed layer — a custom hazard report, a webcam overlay, a personal trail log — without a marola PR per layer); community/outreach (the same leverage Windy's plugin ecosystem gets from letting anyone extend the map); infra/dev-loop (a template repo becomes the "start here" for a contributor who isn't ready to touch marola's own Scala pipeline) |
| **Effort vs Gain** | `do next` once Release 0 (MIP-0033) ships — real value, bounded effort, but deliberately sequenced *after* Release 0 per the request, not competing with it |
| **Depends on** | Nothing blocking technically; sequenced as a Release 1 item after MIP-0033's Release 0 lands (a human/product ordering choice, not a technical one — §11) |
| **Blocked by** | none |
| **Risk** | A loaded plugin runs with full page access (same origin as `marola.dev`) — this is not a sandboxed capability model, it is "arbitrary third-party JS in the page", the same trust shape Windy's own plugin system has. The mitigation is a curated, PR-reviewed manifest (§5), not a technical sandbox — stated plainly, not glossed over |
| **Cost so far** | — |

## 1. Summary

A small, stable JavaScript hook (`window.marola.registerPlugin`) exposed by `site/static/app.js`,
plus a committed `site/static/plugins.json` manifest of known plugin script URLs that `app.js`
loads via `<script>` injection on page load — the same shape Windy's own plugin model uses (a
JS bundle, loaded by URL, given access to the map instance), adapted to marola's plain Leaflet map
(not Windy's LeafletGL) and to a **static site with no backend**, which changes how a plugin gets
*registered* (a PR to a JSON file, not an upload API) but not how it gets *loaded and run* (still
just a `<script src>`).

## 2. Motivation

marola's map already has one first-party extension point per feature (MIP-0009's wave markers,
MIP-0030's trail lines) — each one is a marola PR. Windy's plugin ecosystem
(`windycom/windy-plugin-template`) shows the alternative: let anyone build and host a small JS
bundle that adds a layer, without touching Windy's own codebase or waiting on a Windy PR. marola's
map (Leaflet, a real map instance, real per-beach data) has the same shape of value waiting for
the same kind of extension point — a personal trail log, a live webcam overlay, a hazard-report
layer someone built for their own beach community.

## 3. User-visible change

A plugin author writes a small JS bundle (their own repo, their own build tooling — a
`marola-plugin-template` companion repo, modeled on `windycom/windy-plugin-template`'s Rollup
setup, is a natural but separate follow-up, not built in this MIP) that calls:

```js
window.marola.registerPlugin({
  name: "my-hazard-layer",
  init(ctx) {
    // ctx.map: the live Leaflet map instance (L.Map)
    // ctx.L: the Leaflet global, already loaded
    // ctx.board: the current board (beaches, scores, trails — whatever this area's latest.json has)
    // ctx.onBoardUpdate(fn): fn(board) called whenever the user changes area/day
    const marker = ctx.L.marker([-27.60, -48.51]).addTo(ctx.map);
    marker.bindTooltip("Reported: strong current");
  }
});
```

To try it locally (mirrors Windy's dev-mode `windy-plugin-url` pattern):

```
https://marola.dev/?plugin=https://localhost:9999/plugin.js
```

To ship it for every visitor: a PR adding one entry to `site/static/plugins.json`:

```json
{ "name": "my-hazard-layer", "url": "https://cdn.jsdelivr.net/gh/user/repo@v1/plugin.js",
  "author": "…", "description": "…" }
```

`app.js` fetches this manifest once on load and injects a `<script>` per listed URL, after the
board itself has loaded — a plugin never blocks or slows the core map.

## 4. Data sources and dependencies reviewed

### 4.1 `windycom/windy-plugin-template` — fetched 2026-09-07

The template's own README/`rollup.config.js`/CHANGELOG (WebFetch, 2026-09-07) show: a plugin is a
compiled JS bundle (Rollup + TypeScript), loaded in Windy's own **developer mode** by pointing it
at a URL (`https://www.windy.com/developer-mode` → load from `https://localhost:9999/plugin.js`)
— i.e. the plugin is fetched and executed client-side, not compiled into Windy's own app. The
CHANGELOG's "updated plugin upload URL" (v4.1.0) implies Windy also has a **separate submission/
publish step** once a plugin is ready for real users — not detailed in the fetched content, and
**not independently verified further** (no account, no attempt to actually publish one). The
`@windycom/plugin-devtools` dependency and `LeafletGL` references confirm plugins get map/data
access, matching the general shape assumed above; the exact API surface (which Windy globals a
plugin can call) was **not fetched** — out of scope, since marola's own API (§5) is designed fresh
for Leaflet, not copied from Windy's.

### 4.2 GitHub Pages' actual constraints — the request's explicit question

**Verified against this repo's own `.github/workflows/site.yml`** (already deploys via
`actions/deploy-pages`, custom domain `marola.dev`, static artifact only — no server, confirmed by
that file's own comments): GitHub Pages serves static files with **no server-side execution and no
custom HTTP response headers** (no `_headers`-file mechanism the way Netlify/Cloudflare Pages
have) — the only header control available is a `<meta http-equiv="...">` tag in the served HTML
itself, which **can** set `Content-Security-Policy`'s `script-src` (§5), just not response-level
headers like `frame-ancestors` or a nonce rotated per-request.

**The answer to the request's question: no, this does not require moving off GitHub Pages.** A
plugin, in this design, is a client-side `<script src>` load — the browser fetches and executes
JS from wherever the manifest points it, and GitHub Pages' role is unchanged: it serves marola's
own `app.js`/`plugins.json`/`index.html` exactly as it does today. Nothing about *loading and
running* a plugin needs server-side code on marola's own domain. What Windy's "plugin upload URL"
hints at — a submission/review *service*, not just execution — is the one piece that would need a
server if built the Windy way (§9's rejected alternative); this MIP's manifest-as-PR design (§5)
gets the same curation gate through GitHub's own PR review instead, needing nothing beyond what
Pages already serves.

## 5. Design

**`site/static/app.js`** (additive — the existing IIFE's internals are otherwise untouched):

```js
window.marola = window.marola || { plugins: [] };
window.marola.registerPlugin = function (plugin) {
  window.marola.plugins.push(plugin);
  if (window.marola._ctx) plugin.init(window.marola._ctx); // late registration after board load
};
```

After the board loads and the Leaflet map exists (`state.map` today, promoted to a small exported
`ctx` object — `{ map, L, board, onBoardUpdate }`), `app.js`:
1. Calls `init(ctx)` on every plugin already registered (a `?plugin=` dev-mode script loaded
   *before* the board, per §3).
2. Fetches `plugins.json` (same-origin, no CORS issue) and injects one `<script src>` per entry —
   each plugin's own `registerPlugin` call then fires against the now-ready `ctx`.
3. `onBoardUpdate` is called on every area/day change so a plugin can redraw against fresh data —
   the same event MIP-0030's trail layer and MIP-0009's markers already redraw on internally; this
   MIP just exposes that one hook, doesn't invent a new update mechanism.

**`site/static/plugins.json`** (new, committed, empty array by default):

```json
[]
```

Ships empty — a fresh clone/fork shows no third-party plugins until someone opts in via a PR to
this file (mirrors `site/static/chatbot-config.js`'s "empty by default, human opts in" precedent
from MIP-0033 §5.2).

**`Content-Security-Policy` meta tag**, added to `site/static/index.html`'s `<head>`:

```html
<meta http-equiv="Content-Security-Policy" content="script-src 'self' https:; object-src 'none'">
```

`script-src 'self' https:` allows any HTTPS-hosted script — the manifest's PR review is the real
trust gate (§8), not CSP; CSP here is defense-in-depth against an unrelated XSS in `app.js` itself
reaching for `javascript:`/inline-eval paths, which `object-src 'none'` and the absence of
`'unsafe-inline'`/`'unsafe-eval'` already block.

**What is deliberately not in this MIP:** a `marola-plugin-template` companion repo (Windy's own
Rollup starter has a natural analogue, but it's a separate repo/deliverable, not this site's
code); a plugin marketplace/search UI; sandboxing (iframes with `postMessage`, a real capability
boundary) — noted as a real gap in §8, not solved here.

## 6. Scoring / safety impact

None to `Swimability.score` — a plugin can draw on the map but has no path back into the scoring
pipeline, which lives entirely server-side in the Scala build. A plugin **could** visually
misrepresent something (a fabricated hazard marker, say) — this is the same trust boundary as any
browser extension or injected script; the manifest-PR review (§5/§8) is the only control, and this
MIP does not claim otherwise.

## 7. Verification plan

- Extend `scripts/site_check.js` (already part of `just quality`): a fixture plugin script that
  calls `registerPlugin` and asserts `init(ctx)` fires with a real `ctx.map`/`ctx.board`; assert a
  `plugins.json` with one entry results in exactly one injected `<script>` tag; assert an empty
  `plugins.json` (the default) injects none and the page still renders unchanged.
- A live check before merge: a hand-written plugin bundle, loaded via `?plugin=<localhost URL>`
  against a real `just site-build` output, confirmed to add a real marker/layer to a real running
  map.
- "Done" = `registerPlugin` documented (a short `site/static/PLUGINS.md`, the API surface from §5
  plus the `?plugin=` dev-mode instructions), the CSP meta tag present, `plugins.json` committed
  empty, and `site_check`'s new assertions green.

## 8. Risks, limitations, and honest caveats

- **No sandbox.** A registered plugin runs with full page access — it can read whatever's in
  `localStorage`, make its own network calls, and modify the DOM outside the map. This mirrors
  Windy's own model (a JS bundle in the page, not an iframe) and is a real, stated trade — the
  mitigation is curation (a human reviews every `plugins.json` PR), not a technical boundary. A
  future MIP could move to an `<iframe>` + `postMessage` capability model for real isolation, at
  the cost of a much smaller/harder API surface (a plugin can't just call Leaflet directly anymore)
  — not attempted here.
- **CSP's `script-src 'self' https:` is permissive by design**, not maximally locked down — a
  tighter policy (an explicit host allowlist, rotated as `plugins.json` changes) is possible but
  adds real maintenance friction for a marginal gain over "any HTTPS host, but only URLs a human
  approved in `plugins.json`"; flagged as a §11 open question, not decided here.
- **A slow or broken third-party plugin script** could error in the browser console or draw
  something ugly, but per §5's ordering (plugins load *after* the board, `<script>` injection is
  async and non-blocking) it cannot prevent the core map/board from rendering — the same "an
  optional layer degrades gracefully, the core page never depends on it" rule MIP-0033's chatbot
  widget already follows for its own optional feature.
- **Windy's actual plugin-submission review process was not independently verified** (§4.1) — this
  MIP's PR-review gate is marola's own invention, not a copy of whatever moderation Windy does on
  its "plugin upload URL".

## 9. Alternatives considered

- **A Windy-style upload/marketplace service.** Rejected for v1: needs a real backend (storage, a
  submission API, moderation tooling) — the exact thing GitHub Pages can't do and the exact thing
  §4.2 confirms this MIP doesn't need to build. Revisit only if the PR-based manifest genuinely
  can't keep up with contribution volume — not a problem that exists yet.
- **Sandboxed `<iframe>` plugins with a `postMessage` API.** Real isolation, but a much smaller and
  harder-to-design capability surface (no direct Leaflet access) for a repo with, today, zero
  plugin authors lined up — over-engineering ahead of any real demand. Noted in §8 as the natural
  next step if the trust model ever needs to tighten.
- **Do nothing — every new layer stays a marola PR.** Keeps the current, simpler trust model
  (every layer is reviewed marola code) at the cost of the leverage §2 describes. Reasonable if
  Release 1 doesn't end up needing outside contributors; this MIP exists because the request asked
  for the alternative to be designed, not because "do nothing" is wrong.

## 10. Exam-coverage mapping

None directly — this is a site-extensibility feature, not an AI/agent capability. (If a future
plugin itself called an LLM client-side, that plugin's own author would be the one making an
AI-103/AI-500-relevant design choice, not this MIP.)

## 11. Open questions

- **Release sequencing is a product choice, not a technical one.** The request explicitly places
  this as "first requirement of release 1, not release 0" — this MIP is written and ready
  independent of Release 0 (MIP-0033)'s own status, but should not be pulled into `RELEASES.md`'s
  Release 0 section once that file exists (MIP-0033 §5.4); it belongs in a future Release 1 entry.
- Whether `plugins.json`'s CSP allowlist should stay `https:` (any host) or tighten to an explicit
  per-entry host list, kept in sync with the manifest by a small script/CI check rather than by
  hand — a real design call once a first real third-party plugin exists to test the friction
  against, not before.
- Whether a `marola-plugin-template` companion repo (this MIP's §5 explicitly excludes) is worth
  building before or after the first real community plugin request — a chicken-and-egg call the
  maintainer is better placed to make than this MIP.
- Windy's actual plugin-review/moderation process (§4.1, "updated plugin upload URL") was not
  independently verified — if marola's PR-based gate turns out to need the same kind of policy
  Windy settled on, that's worth a direct look at Windy's own contributor docs, not assumed here.

### What else Windy does — a survey, not a design (2026-09-07)

The plugin API is one piece of a broader "marola as the Windy of the sea" framing raised
alongside this MIP. Windy's actual product (`windy.com`, `api.windy.com`, and its sibling marine
app `windy.app` — all fetched live 2026-09-07, §Checked live below) is wider than a plugin system;
surveying it honestly, most of it either doesn't fit marola's local-first/no-accounts/deterministic
shape, or marola already has a direct equivalent under a different name. Each item below is either
tagged **Follow-up MIP:** (a real, undesigned gap worth its own MIP) or marked as already covered.

- **Point Forecast API** (`api.windy.com`: coordinates in, 20+ weather parameters out). marola
  already computes almost exactly this per beach/hour internally (`Recommender`/`Swimability`) and
  already has a local HTTP precedent (MIP-0033 §5.2's `ChatServer`, `com.sun.net.httpserver`, no
  new dependency). **Follow-up MIP:** a `GET /forecast?lat=&lon=` endpoint returning the same JSON
  the board already computes, reusing MIP-0033's tunnel path — the most directly "copy this"
  feature in the whole survey.
- **Map Forecast API / switchable layers.** marola's map already *has* several layers
  (MIP-0009 waves, MIP-0016 water quality, MIP-0021 accessibility, MIP-0030 trails, this MIP's own
  plugin layers) — what it lacks is a visibility toggle UI, not a new data integration. Smaller
  than a MIP: a `site/static` UI backlog item (a layer-picker control in `app.js`), not proposed
  here.
- **Webcams API/network.** marola's own version of this idea is already drafted:
  `docs/mips/MIP-0006-live-look-user-cameras.md` ("How does it look right now?", Draft, XL,
  "do when X lands"). No new MIP needed — this survey just confirms the connection explicitly.
- **Saved locations / favorites.** Windy.app lets a user save and compare spots; marola's site has
  a "near me" button (client-side, MIP-0005 §9) but nothing persistent across visits.
  **Follow-up MIP:** a `localStorage`-only favorites list (no server, no accounts — fits the
  static-site default exactly) — a genuine, currently-undesigned gap, and a small one.
- **Spot discovery at scale (Windy.app: 130,000+ spots).** marola already discovers beaches per
  configured area via Overpass (`BeachFinder`); scaling this is adding areas to `site/areas.json`,
  an operational task, not an integration — no MIP needed.
- **Community reports / "ask locals" chat.** A real product idea, but a genuine shape change
  (user accounts, generated content, moderation) that nothing else in marola has taken on —
  rated here as a real but *much* bigger undertaking than a "copy this feature" item; if ever
  pursued it needs its own careful MIP weighing the moderation/accounts cost, not a quick add.
- **Meteorological education content.** marola already has this, just not named after Windy's
  version: the curated `knowledge/` corpus plus `OceanQa`/`--ask` (MIP-0001, MIP-0022's safety
  footer on top). Already built.
- **An embeddable widget** (the inverse of this MIP's plugin API — someone else embeds marola's
  board on *their* site, e.g. an `<iframe>`). **Follow-up MIP:** closely related to this one
  (same static-hosting question applies — confirmed no server needed, §4.2's reasoning transfers
  directly), genuinely worth designing alongside a first real plugin request rather than blocking
  on it.
- **PRO subscription / business model.** Out of scope for any engineering MIP — a product/pricing
  decision, not a design question. Noted, not designed.

## Appendix

### Checked live
- `https://github.com/windycom/windy-plugin-template` — WebFetch, 2026-09-07: README describes
  Rollup/TypeScript tooling, dev-mode loading via `https://localhost:9999/plugin.js`, a
  `@windycom/plugin-devtools` dependency, and a CHANGELOG line ("updated plugin upload URL",
  v4.1.0) implying a separate publish step. Full plugin API surface and the upload/review process
  itself were not present in the fetched content and were not further pursued (§4.1).
- `marola`'s own `.github/workflows/site.yml` — read directly, 2026-09-07: confirms
  `actions/deploy-pages`, custom domain `marola.dev`, static artifact only, no server-side
  component — the basis for §4.2's "no, GitHub Pages is fine" conclusion.
- `site/static/app.js` — read directly, 2026-09-07: confirmed no existing global API (`state` and
  the map instance are local to the file's IIFE) — the gap this MIP's §5 fills.
- `https://en.wikipedia.org/wiki/Windy.com` — WebFetch, 2026-09-07: global models (GFS, ECMWF,
  ICON, meteoblue AI Global Model), regional models (NEMS, NAM, HRDPS, AROME), layers (wind, temp,
  precipitation, pressure, radar, satellite), 50M+ Google Play downloads (2025), 2024 majority
  stake in meteoblue. No product-feature detail beyond this (flagged as a limitation in the
  fetched summary itself).
- `https://api.windy.com/` — WebFetch, 2026-09-07: three real products — Point Forecast API
  ("wind, temperature, precipitation, air quality and other 20 parameters"), Map Forecast API
  ("choose from weather models, layers and isolines"), Webcams API ("largest repository of
  webcams worldwide", ad-free, unrestricted access). Pricing tiers linked but not fetched.
- `https://windy.app/` — WebFetch, 2026-09-07 (Windy's marine/sports-specific sibling app): live
  wind map, 10-day forecast, sea temperature, 130,000+ indexed spots, spot comparison, in-app
  "ask locals" chat, meteorological lessons/activity guides, Apple Watch widget, PRO subscription
  tier. Tide predictions/route planning/offline maps were not mentioned despite being common for
  marine apps — noted as an omission, not confirmed absent.

### Not checked
- Windy's actual plugin API surface (which of its internal objects a real Windy plugin can call)
  — not needed, since marola's API is designed fresh against its own Leaflet setup, not copied.
- Windy's plugin submission/review process in any detail beyond the one CHANGELOG line naming it.
- Whether any other static-site plugin ecosystem (VS Code extensions via a marketplace, Obsidian
  community plugins via a committed JSON manifest — Obsidian's own model is in fact close to this
  MIP's `plugins.json` design) was reviewed for comparison — not done, flagged as a possibly useful
  future cross-check, not blocking this MIP.
