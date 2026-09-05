# MIP-0005: The map — every beach's daily recommendation on a static site the pipeline feeds

| | |
|---|---|
| **Status** | Implemented — stack `mip-0005/1-board-json` → `2-site-build` → `3-map-page` → `4-scheduling` (tasks and v1 decisions: [`MIP-0005.tasks.md`](./MIP-0005.tasks.md)); cost per PR in each PR's Cost section |
| **Author** | Claude Fable 5.1, for M. Hoffmann; idea from a chat with a friend (5 Sep 2026): "use the bot to feed the site and have the data there, instead of answering everyone" |
| **Created** | 2026-09-05 |
| **Phase** | 1 (local build + a manual deploy), 3 for the scheduled deploy (`ARCHITECTURE.md` §11) |
| **Tasks** | `docs/mips/MIP-0005.tasks.md` — 4 stacked PRs: board-json, site-build, map-page, scheduling |
| **Related** | MIP-0003 (precomputed boards — this is their first consumer), MIP-0002 (the bot links here), MIP-0001 (what a beach card shows), `ARCHITECTURE.md` §7 (Overpass fair use), `FUTURE-WORK.md` §1 (other activities become layers on the same map) |

## 1. Summary

Today every answer costs a live pipeline run per person. Most people asking about tomorrow in
Florianópolis want the same thing: which of these thirty beaches, at what hour. This MIP computes
that **once per area, on a schedule**, writes it as JSON, and renders it as a **static map site**:
a marker per beach coloured by score, a card with the best hour, water quality per sampling point,
tides, jellyfish and whale odds, and the day's sea-lore paragraph. No server, no LLM call per
visitor, no login, no tracking. Cost scales with beaches, not with users, and the free upstreams
marola depends on are called once per area per run instead of once per question. The bot (MIP-0002)
links to it; the CLI keeps working as the workbench.

## 2. Motivation

- A surfer friend of the author pays for two apps just to see how the sea looks; both are lists
  and cameras, neither is "all my beaches, ranked, on a map". The map is the product a person
  glances at on the sand.
- `ARCHITECTURE.md` §9 and MIP-0003: a cold answer is ~35-45 s and Overpass allows two slots per
  IP. Ten simultaneous bot users would be throttled. Precomputing for an area removes the per-user
  fan-out entirely: one Overpass query per area per month (cached), one Open-Meteo pair per beach
  per run, one IMA download per run.
- The static site is the cheapest possible surface to share: a URL forwarded in a WhatsApp group.
  It is also the honest MVP of "product adoption" — if nobody opens the map, the bot won't be
  opened either.

## 3. User-visible change

```
https://<site>/                         → map of the configured area (Florianópolis by default)
   • one marker per beach, colour = score (grey = dark/no data, red = water unfit)
   • tap → card: best hour tomorrow, score + notes, water quality per point (PRÓPRIA/IMPRÓPRIA,
     date, count), tides, sea temp/waves/wind, jellyfish, whales; "why" = the same notes the CLI prints
   • list view (ranked), day picker (today / tomorrow), hour slider (score of every hour, not only the best)
   • footer: generated at HH:MM, data sources with links, the day's 🌊 lore paragraph, a link to the bot
https://<site>/data/2026-09-06.json      → the machine-readable board the map renders (also for MCP/bot)
https://<site>/data/latest.json          → pointer to the newest board
```

Local: `just site-build` writes `site/dist/`; `just site-serve` opens it at `localhost:8000`. No
change to the CLI output.

## 4. Data sources and dependencies reviewed

- **No new upstream data.** The board is the existing pipeline (`Recommender.bestHoursTomorrow`,
  all hours, not just the best) run for a whole area.
- **Map tiles.** OpenStreetMap's public tile servers have a usage policy that forbids heavy or
  commercial use; fine for a private link among friends, not for a public launch. Free
  alternatives with a key-free or generous tier: Protomaps (self-hosted PMTiles on the same static
  host — fully static, no third party at runtime, the local-first answer), MapTiler/Stadia free
  tiers (key, quotas). Decision in §11.
- **Map library.** Leaflet (small, no build step, vendored as static files) for markers/popups;
  MapLibre GL if Protomaps vector tiles are chosen. Both are plain JS files copied into `site/`;
  no Node build, consistent with the repo's dependency stance.
- **Hosting.** Cloudflare Pages (free tier, `wrangler pages deploy site/dist`, generous bandwidth)
  or GitHub Pages (free for the repo). Both serve static files only — which is the point. The
  author's friend runs projects on Cloudflare; either works, nothing in the design depends on the
  choice.
- **Scheduling.** Locally: a cron/systemd timer running `just site-build && just site-deploy`. In
  CI: a scheduled GitHub Actions workflow — no Ollama needed (the LLM summary is optional and off
  by default for the site), so a run is ~2-3 minutes of the free minutes; every 3 hours ≈ 8 runs/day
  ≈ 20 min/day. Verified cost model, not verified execution (no scheduled workflow exists yet).
- **Not needed:** Supabase or any database — the site is read-only. It becomes relevant only if
  visitors *write* (sightings from the map), which is MIP-0002/MIP-0004 territory and would slot
  in as another `SightingStore` provider.

## 5. Design

### 5.1 Areas, not origins

`site/areas.json`: named areas the scheduler computes — `{ "id": "floripa", "name":
"Florianópolis", "lat": -27.60, "lon": -48.48, "radius_km": 30, "beach_limit": 80 }`. The
pipeline already takes `radiusKm`/`beachLimit`; `BeachFinder`'s 500-element cap covers an island.
Distances on the map are not "from you" (there is no you) — the card shows the beach's own
coordinates and the list is ranked by score, with a "near me" sort only if the browser grants
location, client-side, never sent anywhere.

### 5.2 Board JSON (`core/site/Board.scala`, pure rendering of `BestHour`s)

```json
{ "area": "floripa", "day": "2026-09-06", "generated_at": "2026-09-05T18:00-03:00",
  "sources": {"beaches": "OpenStreetMap/Overpass", "forecast": "Open-Meteo", "water": "IMA/SC bulletin 43"},
  "lore": {"text": "...", "source": "https://..."},
  "beaches": [
    { "name": "Praia do Campeche", "lat": -27.6893, "lon": -48.4776,
      "best": {"hour": "08:00", "score": 35, "notes": ["choppy (1.4m waves)", "4/5 points PRÓPRIA — avoid ..."]},
      "hours": [{"h": "06:00", "score": 35}, {"h": "07:00", "score": 35}, ...],   // every daylight hour
      "water": {"summary": "4/5 PRÓPRIA — avoid Ponto 73 (25 Aug)", "points": [ ... ]},
      "tides": [{"time": "05:00", "m": -0.1, "high": false}, ...],
      "sea": {"temp_c": 18.8, "wave_m": 1.4, "period_s": 6, "wind_kmh": 28, "current_kmh": 0.9},
      "jellyfish": "Low", "whales": {"now": "Low", "peak": "07:00"} } ] }
```

One file per area per day; `latest.json` points at the newest. `Report` and the MCP server already
compute every field; `Board` is a serializer, unit-tested against the golden fixtures.

### 5.3 Generator and site

- `just site-build [area]` → `cli/run -- --site area` → `Board` JSON under `site/dist/data/`, plus
  `site/static/` (index.html, app.js, leaflet, css) copied over. No templating engine: the page is
  static and reads JSON at load. `--site` runs the pipeline with `waterQuality` auto-selected by
  the area's origin, LLM summary off unless `--summarize` (it's one call per beach — on by
  default only when a GPU host does the build).
- `site/static/app.js`: fetch `latest.json`, draw markers (score → colour scale: ≥70 green,
  40-69 amber, 1-39 orange, 0 red, no-data grey), popup card, list, hour slider re-colouring
  markers from `hours[]`. Plain JS, ~300 lines, no framework.
- `just site-serve` → `python3 -m http.server -d site/dist 8000` (python is in the flake).
- `just site-deploy` → `wrangler pages deploy site/dist` or `gh-pages`; deploy target is a
  justfile variable, both documented.

### 5.4 Scheduling

Local first: a `systemd --user` timer or cron every 3 hours (`docs/RUN-LOCALLY.md` §9). Then a
`.github/workflows/site.yml` on `schedule` + `workflow_dispatch`, building and deploying with no
Ollama. Uses MIP-0003's cache when it lands; until then it is still one Overpass call per run.

### 5.5 Bot and CLI integration

MIP-0002's bot replies end with the map link for the user's area; `/mapa` returns it. The MCP
`get_swim_recommendation` can read a board instead of recomputing when one is fresh (< 3 h) —
that is MIP-0003's "board as cache" and stays there.

## 6. Scoring / safety impact

None to scoring. Presentation rules carried over: unfit water is red and says why; dark hours
are never a "best hour"; the generated-at time is on every page so a stale board is visible; the
lore paragraph is the verbatim sourced text; no visitor data is collected (no analytics script,
no cookies).

## 7. Verification plan

- `BoardSpec`: build the board from `PipelineGoldenSpec`'s fixture transport; assert every beach
  has daylight-only hours, water/tide fields match `Report`, JSON round-trips through `JsonValue`.
- A schema file (`site/board.schema.json`) validated in the same test so the JS and any future
  consumer have a contract.
- Static site smoke: `just site-build` from fixtures (no network) produces `dist/data/latest.json`
  and `index.html`; a headless-browser check is optional and not in CI.
- Live: `just site-build floripa && just site-serve`, open the map, tap Campeche, see Ponto 73.
- Deploy once by hand to Cloudflare Pages or GitHub Pages; then the scheduled workflow.

## 8. Risks, limitations, and honest caveats

- **Tiles.** Launching publicly on OSM's tile servers violates their policy; Protomaps on the same
  static host is the clean fix and adds a one-time ~100 MB regional tile extract to the repo's
  release assets (not to git).
- **Staleness.** A 3-hourly board is up to 3 h old; the forecast changes hourly. The page shows
  generated-at; the bot can always recompute live for one user.
- **Overpass per run.** Until MIP-0003's cache exists, each build is one heavy Overpass query per
  area. Keep areas few and runs ≤ 8/day.
- **Scope creep.** A map invites "add surf/dive layers" (`FUTURE-WORK.md` §1) — those are new
  scoring functions writing extra fields into the same board, not new pages. Keep v1 to swimming.
- **No summary text on the site by default.** Without a GPU host, per-beach LLM summaries make the
  build slow; the deterministic notes carry the "why". Documented as a choice, not a limitation.

## 9. Alternatives considered

- **Dynamic site (server + API).** Needs hosting, auth, rate limiting — everything the static
  board avoids. Rejected for v1.
- **Map inside Telegram** (`sendLocation`/venue messages). Useful for one beach, useless for thirty.
- **A native app** on a friend's developer account. Store review, two codebases, and nobody
  installs an app for a beach. The map URL forwards in a group; that is the growth loop.
- **Supabase as the data store.** Not needed for read-only data; revisit with map-side sightings.

## 10. Exam-coverage mapping

AI-103 §1 "plan resources / manage costs": compute-once-serve-many is the cost model; the
scheduled workflow is the first deploy artefact. AI-103 §1 "Responsible AI": no tracking, visible
data provenance and freshness on every page.

## 11. Open questions

Resolved for v1 in [`MIP-0005.tasks.md`](./MIP-0005.tasks.md) (decisions 1-5): OSM raster tiles
for the friends-only phase with the Protomaps switch still ahead of any public link; floripa + rio;
hour slider yes; no LLM summary in the board; GitHub Pages first. Kept here as the record of what
was open when the MIP was written.

1. Tiles: OSM public tiles for the friends-only phase and Protomaps before any public link, or
   Protomaps from day one? (Proposal: Protomaps from day one; it is also the local-first answer.)
2. Areas: Florianópolis only, or also Rio (Arpoador is the E2E default) to prove multi-area?
3. Hour slider vs. just the best hour in v1. (Proposal: slider — the hours are already computed.)
4. Should the board include the LLM summary when built on a machine with a GPU, behind
   `--summarize`? (Proposal: yes, as an optional field the page shows if present.)
5. Cloudflare Pages vs. GitHub Pages for the first deploy. Either; the friend's Cloudflare habit
   tips it there.
