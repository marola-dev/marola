# MIP-0009: A richer map — a wave marker per beach and, on hover, every aspect at that point

| | |
|---|---|
| **Status** | Implemented — all four stacked PRs merged: #144 (wind-level), #145 (site-check-harness), #146 (wave-marker-tooltip), #147 (card-aspect-row); tasks and v1 decisions: [`MIP-0009.tasks.md`](./MIP-0009.tasks.md) |
| **Author** | Claude Fable 5.1, for M. Hoffmann (request of 5 Sep 2026: "for now it just displays dots… hovering the dot should display each aspect: wind level with emoji, whale probability, jellyfish probability, water temperature — a wave icon, not a heat map") |
| **Created** | 2026-09-05 |
| **Tasks** | `docs/MIPs/MIP-0009.tasks.md` — four stacked PRs, one per task; note its decision #1: the §5 test harness (`scripts/site_check.js`) did not exist on 2026-09-06 and is task 2 |
| **Phase** | 1 — the map exists (MIP-0005) and this only changes what it draws; no earlier-phase prerequisite is missing for it |
| **Related** | MIP-0005 (the map and its board JSON), MIP-0008 task 6 (the footer panel; same `app.js`), `FUTURE-WORK.md` §1 (other activities as layers on the same map). **See also [MIP-0046](./MIP-0046-remove-ai-slop-ui.md)** (Draft) — it replaces this MIP's emoji map (§5) and the `〰️`-vs-`WAVE_PATH` split with one inline-SVG chart-mark set, and closes §8's "emoji fonts differ per platform — hence the words" caveat; the words, numbers and score colours are unchanged. **See also [MIP-0047](./MIP-0047-desmos-equation-art.md)** (Draft) — equation-drawn illustration motifs sharing the same inline-SVG sprite; its §5.5 deliberately leaves this MIP's `WAVE_PATH` marker and legend key untouched, because a hand-tuned filled path beats a sampled curve at 24 px |
| **Effort** | S — no new data; one pure Scala function (`windLevel`) plus a JS/CSS-only marker and tooltip change |
| **Gain** | user value (answers "where's it calm now" without 40 clicks) |
| **Effort vs Gain** | cheap win — small, self-contained, no dependency on any other Draft MIP |
| **Depends on** | MIP-0005 (the map and board this changes); no phase or cloud gate |
| **Risk** | fixed-pixel wave `divIcon`s can overlap at 300m beach spacing (Ingleses/Santinho) — a legibility risk, not a data one |
| **Cost so far** | ~$3.18 shared bucket with MIP-0008's wrap-up (commit 44b848c, "not split further" per its own `Cost:` line) |

## 1. Summary

Every beach on the map becomes a **wave marker** coloured by its score instead of a plain dot,
and **hovering** it (tapping, on a phone) shows every aspect marola already knows for that beach
at the selected hour: wind level with an emoji, whale-sighting likelihood, jellyfish risk, water
temperature, waves, water-quality verdict: the numbers the CLI prints, without opening the card.
No new data: every value is in the board JSON today; the one addition is a deterministic wind
band computed in Scala so the page never re-implements a scoring threshold.

## 2. Motivation

Today `app.js` draws `L.circleMarker` per beach and its tooltip says only `Praia da Joaquina ·
55 at 10:00` (`site/static/app.js`, `render()`); wind, whales, jellyfish and water temperature are
all one click away in the card. On a map with 40 beaches the question "where is it calm and warm
right now?" means 40 clicks. The board already carries, per beach and per daylight hour,
`wind_kmh`, `sea_temp_c`, `wave_m`, `jellyfish`, `whales` and `score` (checked 2026-09-05 against
`site/dist/data/floripa/2026-09-06.json`, appendix A), but the page does not show them.

The marker itself is a dot because MIP-0005 §5.3 chose the cheapest thing that works; a wave
glyph is what the product is about and, unlike an emoji marker, can still carry the score colour.

## 3. User-visible change

Hover (desktop) or tap (touch) on a beach:

```
🌊 Praia da Joaquina · 55/100 at 10:00
🌬️ breezy, 27 km/h S        🌡️ water 19.0 °C
〰️ waves 1.3 m every 6 s     🪼 jellyfish Low
🐋 whales Low (best 07:00)   💧 PRÓPRIA (1/1 pts, 25 Aug)
```

- The marker is a wave shape filled with the score colour (green ≥ 70, amber ≥ 40, orange ≥ 1,
  red = 0 or unfit water, grey = dark/no data, the same scale as today's legend). The selected
  beach's wave is larger; the hour slider changes both the colour and the tooltip, as it changes
  the dots today.
- The tooltip follows the mouse (Leaflet `sticky`), one at a time, and closes on leave. On touch
  there is no hover: a tap opens the card as today, and the **same aspect row is the first block
  of the card**, so both inputs see the same thing.
- Every emoji is followed by its word ("🪼 jellyfish Low"), so a platform without the glyph still
  reads correctly; the legend gains a wave key, the same path the markers draw, at 18 px in
  `--ink`, labelled "hover a wave". It is hidden under 640 px: a phone has no hover to offer and
  the hour bar has no room for the line (it clipped at 390 px).
- The card, the list, the day picker and the footer panel (MIP-0008) are unchanged.

## 4. Data sources and dependencies reviewed

**No new data source.** Everything shown comes from the board JSON MIP-0005 already publishes:

| Aspect | Field (per beach / per hour) | Verified 2026-09-05 |
|---|---|---|
| Score, hour | `hours[].score`, `hours[].h`, `best.*` | real board, appendix A |
| Wind | `hours[].wind_kmh` (per hour), `sea.wind_dir_deg` (best hour) | real board |
| Water temperature | `hours[].sea_temp_c` | real board |
| Waves | `hours[].wave_m`, `sea.period_s`, `sea.swell_m` | real board |
| Jellyfish | `hours[].jellyfish` — `Low`/`Moderate`/`High` from `Swimability` | real board, enum from `core/scoring` |
| Whales | `hours[].whales` (per hour), `whales.peak`, `whales.season` | real board |
| Water quality | `water.summary`, `water.unfit` | real board |

**Wind band.** The CLI's notes say "breezy (27km/h)" / "strong wind (…)" from
`Swimability.windDelta` (`CalmWindKmh`, `StrongWindKmh`, `core/scoring/Swimability.scala:92`),
which is private. The band shown on the map must be the same one, so it is computed in Scala
and written to the board as `wind_level` (§5), not re-derived in JavaScript from a copied number.

**Leaflet 1.9.4** (vendored, `site/static/vendor/leaflet.js`): `L.divIcon` (HTML/SVG markers)
and tooltips with `sticky`/`permanent`/`direction` are present (confirmed by grepping the vendored
file on 2026-09-05, not from docs). No new library, no build step (MIP-0005's constraint holds).

**Emoji.** 🌊 U+1F30A, 🌬️ U+1F32C, 🌡️ U+1F321, 🐋 U+1F40B, 💧 U+1F4A7, 🍃 U+1F343, 💨 U+1F4A8
are Emoji 1.0-era; 🪼 jellyfish is U+1FABC, **Emoji 14.0 (2021)**: older Android/Windows fonts
show a box, which is why every emoji is followed by its word. Rendered by the system font
(Noto Color Emoji, Apple Color Emoji, Segoe UI Emoji); nothing is downloaded. Not checked: how
the wave SVG looks on a Retina display at 26 px; the manual check in §7.

## 5. Design

**Scala (deterministic, tested).**

- `Swimability.windLevel(kmh: Option[Double]): WindLevel`: `enum WindLevel { Calm, Breezy,
  Strong }` (plus `None` when the forecast lacks wind), using the same `CalmWindKmh`/
  `StrongWindKmh` constants `windDelta` uses; `windDelta` calls it, so there is one threshold.
- `Board`: each `hours[]` entry gains `"wind_level": "calm" | "breezy" | "strong" | null`.
  Additive, optional → **schema stays 1** (`site/board.schema.json` lists it as optional;
  the page tolerates its absence: an old board still renders, without the band).

**JavaScript (`site/static/app.js`, plain, no framework).**

- `waveIcon(colour, selected)` → `L.divIcon({ html: '<svg viewBox="0 0 24 24">…wave path…</svg>',
  className: 'wave', iconSize: selected ? [32, 32] : [24, 24], iconAnchor: centre })`. One inline
  SVG path (own artwork, MIT with the repo), `fill` = the score colour, a white stroke for contrast
  on tiles. `render()` uses `L.marker([lat, lon], { icon })` instead of `L.circleMarker`.
- `aspectsHtml(beach, shown)` builds the six-cell grid from the `shown(beach)` hour entry (the
  slider's hour, or the best hour) plus the best-hour `sea` block; used by the tooltip
  (`bindTooltip(html, { sticky: true, direction: 'top', className: 'aspects', opacity: 0.97 })`)
  and prepended to `renderCard()`. Emoji map: wind `calm 🍃 / breezy 🌬️ / strong 💨`, whales
  `🐋`, jellyfish `🪼`, water temperature `🌡️`, waves `〰️`, water quality `💧` (red when unfit).
- `style.css`: `.wave svg { … }`, `.leaflet-tooltip.aspects { grid, 2 columns, 0.85rem }`, the
  selected-wave size, the legend line.
- `scripts/site_check.js` (Node, stdlib): the stub-DOM harness written for MIP-0008 task 6,
  checked in. It loads `app.js` against `site/fixtures/` (a two-beach board committed as a
  fixture, validated against `board.schema.json` by the harness) and asserts: one wave marker per
  beach, the tooltip HTML holds the six aspects with the fixture's numbers, the card starts with
  the same row, a board without `wind_level` still renders. Run by `just quality` (Node is in the
  flake) and by ci.yml's quality job when `site/**` changes.

**What goes through the LLM: nothing.** Every string on the tooltip is a number or an enum from
`scoring/`; the words are fixed labels.

## 6. Scoring / safety impact

None to `Swimability.score` or the notes. `windLevel` is a pure function over the existing
thresholds, and `windDelta` is refactored to use it; `SwimabilitySpec` gains three cases (below
calm, between, at/above strong) proving the band and the delta agree. Unfit water stays red on
the marker and says why in the tooltip (`water.summary`), exactly as the card does.

## 7. Verification plan

- `SwimabilitySpec`: `windLevel` bands and their agreement with `windDelta`'s note text.
- `BoardSpec`: every hour entry carries `wind_level`, consistent with its `wind_kmh`; the schema
  validator accepts it and accepts a board without it.
- `scripts/site_check.js` in `just quality` (assertions above); `node --check app.js`.
- Manual: `just site-build floripa && just site-serve`: hover Joaquina on a desktop, tap it on a
  phone (Safari + Chrome), zoom out to the whole area and check the waves stay legible over the
  OSM tiles; screenshot into the PR.
- "Done": the live map after the merge shows waves and hover aspects; the footer panel and the
  list still work.

## 8. Risks, limitations, and honest caveats

- **Hover does not exist on touch.** The card's aspect row is the touch equivalent; no long-press
  gesture is invented (it fights the map's pan).
- **Emoji fonts differ** per platform and 🪼 is missing on older ones, hence the words.
- **Clutter:** tooltips are one at a time and only on hover, so 40 beaches stay readable; the
  waves are fixed-pixel `divIcon`s and do not scale with zoom; at the area zoom they may overlap
  where beaches are 300 m apart (Ingleses/Santinho); the selected one is drawn on top.
- **Colour is not the only signal:** the score number is in the tooltip and the card; unfit water
  says "IMPRÓPRIA" in words.
- The wind band shown is the *forecast* wind at that hour; the tooltip says the hour.

## 9. Alternatives considered

- **An emoji as the marker** (🌊 in a `divIcon`): cannot take the score colour, renders
  differently everywhere, and overlaps badly. Rejected; emoji only in the text.
- **A heat/density layer** (leaflet.heat): explicitly not wanted; it also hides the per-beach
  truth behind a blur. Rejected.
- **Permanent labels on every marker**: unreadable at area zoom. Rejected in favour of hover.
- **Popups on hover**: Leaflet popups close on mouse-out and steal the map's focus; tooltips are
  the hover primitive. Rejected.
- **MapLibre with data-driven symbols**: richer, but a build step and a bigger vendor blob for
  one tooltip. Rejected while MIP-0005's "plain files, no build" holds.
- **Do nothing**: the card works, but the map answers "where?" only after a click per beach.

## 11. Open questions

1. Wind bands: keep exactly `Swimability`'s two thresholds (calm/breezy/strong), or add a fourth
   "gale" band for the `strong` tail? Proposal: the two thresholds, so the map and the CLI agree.
2. Whales per hour (`hours[].whales`) or the day's peak (`whales.peak`) on the tooltip? Proposal:
   the hour's value plus "best HH:MM" when it differs.
3. Should the same aspect row become the first line of the Telegram reply (MIP-0002)? It is the
   compact form of `Report.line`. Out of scope here; noted for MIP-0002.
4. Wave glyph: one shape for all, or a rougher wave for `strong wind`/`wave_m ≥ 1.5`? Proposal:
   one shape in v1: the colour and the text already say it.

## Appendix

**A. One beach from the live board, 2026-09-05** (`site/dist/data/floripa/2026-09-06.json`):
`jellyfish: "Moderate"`, `whales: {now: "High", peak: "07:00", season: true}`, `sea: {temp_c:
19.6, wave_m: 0.58, period_s: 5.3, wind_kmh: 16.3, wind_dir_deg: 187, uv: 0.1, …}`, `hours[0]:
{h: "07:00", score: 60, wind_kmh: 16.3, sea_temp_c: 19.6, wave_m: 0.58, jellyfish: "Moderate",
whales: "High", notes: ["breezy (16km/h)", "cold water (19.6°C)", "some jellyfish likelihood"]}`.

**B. Leaflet checks** (grep of the vendored 1.9.4 file, 2026-09-05): `divIcon` present,
`bindTooltip` present, tooltip options `sticky` and `permanent` present.

**C. Emoji versions**: 🪼 U+1FABC Emoji 14.0 (2021); all others ≤ Emoji 5.0.
