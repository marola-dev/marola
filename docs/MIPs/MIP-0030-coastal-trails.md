# MIP-0030: Coastal and lakeside trails on the map — named OSM paths near a beach or a lake

| | |
|---|---|
| **Status** | Accepted — implemented, pending merge on `mip-0030/1-coastal-trails` |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: "create a MIP for adding all trails info to the rendered map (only trails nearby the ocean or a lake), decide if the fetch mechanism should be the same as IMA/SC") |
| **Created** | 2026-09-06 |
| **Phase** | 0 (CLI/board field only, no bot text) — no earlier-phase prerequisite is missing |
| **Related** | `BeachFinder` (`core/src/main/scala/marola/beaches/BeachFinder.scala`, the Overpass client this reuses verbatim); MIP-0021 (beach accessibility — the closest existing precedent: an OSM amenity layer anchored on `BeachFinder`'s results, "no data" never "none"); MIP-0005/MIP-0009 (the map and its marker layer this adds a sibling to); `docs/2-Building-marola/ARCHITECTURE.md` §5 (pluggable-integration pattern) and §7 (Overpass fair use). **Not related to, and does not touch**, the water-quality provider gap this MIP's investigation also turned up — see §11 |
| **Effort** | S — one new Overpass client in `core/beaches` (same package, same HTTP/retry helper `BeachFinder` already uses), one extra query per area per run, one new top-level array in the board JSON, a map layer reusing MIP-0009's `divIcon`/tooltip machinery for a new icon shape. No new dependency, no LLM |
| **Gain** | user value (a beach's own OSM page rarely says "there's a lakeside trail 400m north"; marola already answers "is the water clean" — "can I walk somewhere from here" is the same kind of fact) |
| **Effort vs Gain** | cheap win — the exact same one-extra-Overpass-query shape MIP-0021 already validated, verified live against real coordinates in §4 below, not just described |
| **Depends on** | Nothing blocking. Doesn't need Phase 1 (the bot) or a paid resource |
| **Risk** | OSM's hiking-trail coverage is **inconsistent by tagging convention, not by geography** — proper `route=hiking` relations are essentially absent near marola's three areas (§4: zero found), while named `highway=path`/`track` ways are well-populated. A query written against `route=hiking` (the "correct" OSM way to tag a trail) would return nothing and look like a bug. §4 verifies the actual tag shape to query before committing to it |
| **Cost so far** | — |

## 1. Summary

A `TrailFinder` client queries Overpass, the same free, keyless API `BeachFinder` already calls,
for named hiking paths and tracks within 500 m of any beach or lake `marola` already found for
that area. It returns as a new `trails` array in the board JSON, rendered as a small line/marker
layer on the map, with whatever OSM tags exist (`sac_scale` difficulty, `trail_visibility`,
surface) shown as facts, and nothing invented where OSM has nothing. One extra Overpass query per
area per run, same cost shape as MIP-0021's amenity query.

## 2. Motivation

Someone standing at Praia da Lagoinha do Leste today gets a swim score and nothing else; OSM
already knows this beach is the end of a named 90-minute trail from Pântano do Sul
(`Trilha da Lagoinha do Leste`), and that a second trail (`Trilha Lagoa do Peri - Sul`) runs along
a freshwater lake 3 km away. marola computes "is this beach good right now" from live data; "is
there somewhere to walk from here" is a fact the same map already has room to show and currently
throws away. Overpass returns it in the same response shape `BeachFinder` already parses.

## 3. User-visible change

CLI (`--brief`/`--summarize`), one line per beach when at least one named trail is within 500 m
(same style as MIP-0021's accessibility line):

```
  Praia do Pântano do Sul   58/100 at 09:00  · trails nearby: Trilha da Lagoinha do Leste (2.1km, moderate)
  Praia da Joaquina         62/100 at 10:00  · trails: no data
```

On the map (MIP-0005/MIP-0009): a thin dashed line along each trail's actual OSM geometry (not
just a point), coloured by `sac_scale` when present (green = easy/hiking, amber = mountain_hiking,
grey = unclassified/no data), with a hover tooltip naming the trail and its length. Board JSON gets
a new top-level array, sibling to `beaches`:

```json
"trails": [
  { "name": "Trilha da Lagoinha do Leste", "length_km": 3.8,
    "difficulty": "hiking", "surface": "ground",
    "near": [{"beach": "Praia do Pântano do Sul", "distance_km": 0.1}],
    "geometry": [[-27.7910, -48.4890], [-27.7925, -48.4871], ...] }
]
```

`difficulty`/`surface` are `null`, never guessed, when OSM carries no `sac_scale`/`surface` tag;
the map draws that trail in the "no data" grey, matching MIP-0021's absence rule.

## 4. Data sources and dependencies reviewed

### 4.1 OpenStreetMap via Overpass — verified live, 2026-09-06

Same endpoint `BeachFinder` already uses (`https://overpass-api.de/api/interpreter`, free, no key,
ODbL, attribution already carried by the map per MIP-0005). Two real queries run against the
production Overpass API while writing this MIP:

**First attempt, `route=hiking` relations (the "textbook correct" OSM tag for a hiking trail),
15 km around a known trailhead (Pântano do Sul, Florianópolis, `-27.7907,-48.4880`): zero
results.** OSM's `route=hiking` relation convention exists for long-distance waymarked routes
(the Camino de Santiago, the Appalachian Trail); it is essentially unused for short, local
coastal/lake trails in this region.

**Second query, named `highway=path`/`track` ways, same radius: 45 results**, including real,
verifiable trails: `Trilha da Lagoinha do Leste` (`alt_name: Trekking Pântano do Sul para Lagoinha
do Leste`, `sac_scale` absent, `trail_visibility` absent on this segment), `Trilha de Naufragados`
(`trail_visibility: excellent`), `Trilha do Farol`, `Trilha Lagoa do Peri - Sul`, `Trilha Praia do
Maço-Guarda` (`sac_scale: mountain_hiking`, `trail_visibility: intermediate`), a real difficulty
tag, confirming the field is worth carrying when present, not just theoretical.

**Third query, the actual shape this MIP proposes**: beaches (`BeachFinder`'s own query) and
named lakes (`natural=water`, `water` in `lake`/`pond`) as two anchor sets, then named
`highway=path`/`track` ways within 500 m of either set, one combined Overpass request, 20 km
around Florianópolis (`-27.6733,-48.4700`, the CLI's own default test origin): **20 named trail
segments**, correctly finding both a beach-anchored trail (`Trilha da Lagoinha do Leste`) and a
lake-anchored one (`Trilha Lagoa do Peri - Sul`) in a single query. Full Overpass QL:

```
[out:json][timeout:60];
way["natural"="beach"]["name"](./around:RADIUS,LAT,LON)->.beaches;
(
  way["natural"="water"]["water"~"^(lake|pond)$"](./around:RADIUS,LAT,LON);
  relation["natural"="water"]["water"~"^(lake|pond)$"](./around:RADIUS,LAT,LON);
)->.lakes;
(
  way(around.beaches:500)["highway"~"^(path|track)$"]["name"];
  way(around.lakes:500)["highway"~"^(path|track)$"]["name"];
);
out tags geom;
```

`out tags geom` (not `out center`, which `BeachFinder`/MIP-0021 use for point amenities) is
needed here because a trail is a line, not a point: the map draws its actual path, not a marker
at its bounding-box centre, which for a multi-kilometre trail can sit nowhere near the water.

**Verified at implementation time (2026-09-07, see Appendix)**: both open items above. All three
configured areas return real trail segments at their own `site/areas.json` radius, and the widest
radius (Florianópolis, 30km) completed in ~22s, inside Overpass's `[timeout:60]` and this repo's
own HTTP timeout; see the Appendix for exact counts and timings.

### 4.2 Why not the IMA/SC pattern — the actual design decision this MIP was asked to make

**Decision: Overpass/OSM (§4.1), the same mechanism `BeachFinder` and (once built) MIP-0021's
`AccessibilityClient` already use, explicitly *not* the `ImaScWaterQualityClient` pattern.**

`ImaScWaterQualityClient` (`local/src/main/scala/marola/water/ImaScWaterQualityClient.scala`)
works by calling Santa Catarina's *own* bathing-water portal's undocumented internal JSON endpoint
(`POST https://balneabilidade.ima.sc.gov.br/relatorio/mapa`), a scrape of one Brazilian state
agency's own website, with no equivalent for any other state or country. `WaterQuality.scala`'s
own doc comment already names this as a per-region pattern: *"One implementation per
agency/portal... INEA/RJ, CETESB/SP would be siblings."* That per-region-scraper shape exists
because bathing-water sampling is measured and published independently by each region's own
environmental agency, in whatever format that agency chose; there is no global standard or
open dataset for it.

Trails have no such constraint: OpenStreetMap already has global, uniformly-tagged coverage
(`highway=path`/`track` plus whatever `name`/`sac_scale`/`surface` a mapper added), queried through
one API that already works identically for Florianópolis, Rio, and Salvador (confirmed: `BeachFinder`
already returns real named beaches for all three areas, verified live this session, 80/56/63
beaches respectively). Building a `TrailFinder` as an IMA/SC-style per-region scraper would mean
inventing a new integration per state for a fact OSM already has everywhere marola runs. The
right analogy is `BeachFinder` and MIP-0021's `AccessibilityClient`, not `ImaScWaterQualityClient`.

### 4.3 A related, separate finding: the water-quality "layers" gap the same investigation surfaced

Requested alongside this MIP was "check why the website is not rendering info about Bahia and Rio
de Janeiro." Investigated live this session: **it is not a bug.** Both areas' boards
(`https://marola.dev/data/rio/2026-09-06.json`, `.../salvador/...`) build correctly, 56 and 63
beaches respectively, verified by replaying the real board through `site/static/app.js` in
`scripts/site_check.js`'s existing headless-DOM harness: zero console errors, correct marker/list
counts for both. **What's actually missing is water-quality data for those two states**:
`AppConfig.waterQualityClient`'s `Auto` case (`cli/src/main/scala/marola/AppConfig.scala:159`)
only checks `ImaScWaterQualityClient.coversOrigin`, so any origin outside Santa Catarina correctly
falls through to `None`, and every Rio/Salvador beach's `water.summary` is honestly `"no data"`,
by design (§4.2's per-region pattern), not by error.

This *is* fixable, and real public sources exist for both states (WebSearch, 2026-09-06, not yet
verified to API/scrapeable-endpoint level the way §4.1 verified IMA/SC in MIP-0001): Rio de
Janeiro's **INEA** (Instituto Estadual do Ambiente) publishes a weekly balneability bulletin
covering 291 sampling points/201 beaches ([inea.rj.gov.br](https://www.inea.rj.gov.br/ar-agua-e-solo/balneabilidade-das-praias/)),
apparently as PDF/HTML bulletins rather than a JSON endpoint like IMA/SC's, harder, not
necessarily impossible (IMA/SC's own undocumented endpoint was only found by inspecting its portal's
own network requests, per MIP-0001 §4.1; INEA may have an equivalent not yet checked). Bahia's
**INEMA** publishes the same kind of weekly bulletin, but its data also appears as a structured
open dataset on [Brasil.IO](https://brasil.io/dataset/balneabilidade-bahia/balneabilidade/),
plausibly the easier integration of the two. **This is exactly the "new layer per region" case
MIP-0021's `WaterQualityClient` trait doc comment already anticipated** ("INEA/RJ, CETESB/SP would
be siblings"), but it is a water-quality MIP, not a trails one, and is **out of scope here**
per `AGENTS.md`'s one-feature-one-MIP discipline. Flagged as a strong follow-up candidate
(next MIP number after this one) in §11, not built or designed further in this document.

## 5. Design

New file `core/src/main/scala/marola/trails/TrailFinder.scala`, same package shape as
`marola.beaches`:

```scala
final case class Trail(
  name: String,
  lengthKm: Double,
  difficulty: Option[String],   // OSM sac_scale, verbatim, e.g. "hiking", "mountain_hiking"
  surface: Option[String],      // OSM surface, verbatim, e.g. "dirt", "ground", "paving_stones"
  geometry: List[Coordinates],  // the way's actual node sequence, for map rendering
  nearBeach: Option[(String, Double)],  // (beach name, distanceKm) — the nearer of beach/lake anchor
  nearLake: Option[(String, Double)]
)

object TrailFinder:
  def nearby(origin: Coordinates, radiusKm: Double, beaches: List[Beach]): List[Trail] < Sync
```

Takes `BeachFinder`'s own already-fetched `beaches` list as one anchor set (no second beach query)
plus a new lake sub-query, combined into the single Overpass request in §4.1's third query shape.
Reuses `Http.postForm` with the same retry/timeout constants `BeachFinder` defines (or promotes
them to a shared `OverpassConfig` if a second near-identical `private val OverpassRetries = 2`
starts to smell like duplication; a call for whoever implements this, not decided here).

`Board.scala` gains a sibling `trailJson`/`"trails" -> JsonValue.arr(...)` alongside the existing
`"beaches"` array (`Board.scala:72`). `site/board.schema.json` gains a new top-level `trails`
array, additive, same "schema stays 1" precedent MIP-0009 §5 already set for an optional field,
here extended to a whole optional top-level key (an older client that doesn't know about `trails`
still renders every existing field unchanged).

`site/static/app.js` gains one new Leaflet layer: an `L.polyline` per trail (not a `divIcon`;
trails are lines, beaches are points), styled by `difficulty` per §3, with a `bindTooltip` naming
the trail, the same tooltip mechanism MIP-0009 already added for wave markers, applied to a line
instead of a point.

Nothing here is LLM-generated: trail name, length, difficulty and surface are all OSM tags or a
computed geometry length, shown verbatim or "no data", same rule as every other integration.

**Implementation note (2026-09-07):** §4.1's literal query text never outputs the `.beaches`/
`.lakes` anchor sets themselves, only the trail ways filtered by them, fine for `nearBeach`
(the caller already has named, located beaches from `BeachFinder`) but not for `nearLake`, which
needs the lake's own name/position. The shipped query adds one line, `.lakes out center;`, to the
same single request (still one Overpass call, not two) so a trail found only via a lake anchor can
still be labelled. Same-named-segment merging (§8, §11) concatenates each segment's geometry and
sums each segment's *own* length (not the length of the concatenation, which would add a spurious
jump between two ways that don't share an endpoint); confirmed against two real duplicate-name
groups in the Appendix's fixture capture.

## 6. Scoring / safety impact

None. Trails do not affect `Swimability.score` or any safety-relevant text; this is a purely
informational map/CLI layer, same category as MIP-0021's accessibility facts.

## 7. Verification plan

- `TrailFinderSpec`: parses a fixture Overpass response (captured from §4.1's real third query)
  into `Trail`s: name, length, difficulty/surface presence and absence both covered (a way with
  no `sac_scale` yields `None`, never a guessed value).
- A live check before merge: run the real §4.1 query against all three configured areas
  (`site/areas.json`: floripa, rio, salvador) and record actual counts in this MIP's Appendix.
  §4.1 only verified Florianópolis.
- `scripts/site_check.js` (already runs in `just quality`): extend the fixture board with a
  `trails` array, assert one `L.polyline` per trail is created and that a board with no `trails`
  key (an older/incomplete area) still renders every other layer unchanged.
- "Done" = the CLI line renders for a real beach with a known nearby trail (Praia do Pântano do
  Sul → Trilha da Lagoinha do Leste), the map draws its line, and a beach with zero nearby trails
  says "no data", never "none" or a blank space.

## 8. Risks, limitations, and honest caveats

- **Overpass fair use** (`ARCHITECTURE.md` §7): this is a second query per area per run, on top of
  `BeachFinder`'s existing one. Both already share the same public instance's retry/backoff
  behaviour; two queries per run for three areas every 3 hours is still well inside the informal
  fair-use expectation the existing comment describes, but it is a real doubling of this
  integration's Overpass load and should be watched if a fourth area is ever added.
- **Trail data quality varies with who mapped it.** `trail_visibility`/`sac_scale` are present on
  some segments and absent on others of the *same named trail* (confirmed in §4.1's second query:
  `Trilha da Lagoinha do Leste` itself carries neither tag, while a `Trilha Praia do Maço-Guarda`
  segment carries both). A trail's difficulty badge may need to be "unknown" even when its name
  and length are known, and that's a fact to show, not smooth over.
- **A named way can be split into many small OSM segments** (visible in §4.1's results: multiple
  identical-named `Caminho da Costa da Lagoa ao Canto dos Araçás` entries). Naive per-segment
  rendering would draw the "same" trail as several disconnected lines with duplicate tooltips.
  §5's implementation needs to merge same-named segments into one `Trail` before this ships;
  flagged here so it isn't discovered as a bug later.
- **500 m is a guess, not verified against user expectation.** It matches MIP-0021's amenity
  radius for consistency, but a trail's *trailhead* can be a kilometre inland from the beach it's
  named for (e.g. `Trilha da Lagoinha do Leste`'s Pântano do Sul trailhead vs. the beach it ends
  at). The radius may need to be larger, or measured to the nearest point of the trail's full
  geometry rather than its Overpass-reported bounding position. Open question, §11.

## 9. Alternatives considered

- **Do nothing.** Zero cost, but leaves a real, freely-available fact (OSM already has these
  trails mapped) unused on a map whose whole premise is showing what's actually near a beach.
- **A per-region trail-agency scraper, IMA/SC-style.** Rejected in §4.2: no such per-region
  source exists for trails the way it does for bathing-water sampling; OSM already has global
  coverage through one API.
- **`route=hiking` relations only** (the "correct" OSM tagging convention). Rejected in §4.1:
  verified zero results near a known real trail; would ship a feature that silently finds nothing.

## 11. Open questions

- Verify the real Overpass query (§4.1's third form) against Rio and Salvador specifically, not
  only Florianópolis, before implementation: different coastline shapes and lake density could
  change the anchor-radius choice.
- Confirm `out tags geom`'s response size/time at the largest configured radius (30 km,
  Florianópolis) stays inside Overpass's budget the way `BeachFinder`'s point-only `out center`
  query does: line geometry is heavier than a point per element.
- Same-named-segment merging (§8) needs a concrete algorithm (merge by name within a run? by
  shared endpoint nodes?); not designed here, left for the implementation PR.
- **Follow-up MIP, not this one** (§4.3): a `WaterQualityClient` sibling for Rio (INEA) and/or
  Bahia (INEMA, plausibly the easier of the two via its Brasil.IO dataset), needs its own
  data-source verification pass (MIP-0001/MIP-0021's discipline: fetch the actual page/endpoint,
  confirm format and update cadence, before naming a pick) before it's designable, let alone
  buildable. Take the next MIP number after this one if picked up.

## Appendix

Raw Overpass responses from §4.1's verification queries are not committed (ephemeral live data,
re-fetchable from the exact query text in §4.1); the query text itself is the reproducible
artifact and is quoted verbatim above.

### A.1 Live verification across all three configured areas (2026-09-07)

§4.1's third query (as extended in §5's implementation note, `.lakes out center;` included), run
live against `https://overpass-api.de/api/interpreter` at each area's own `site/areas.json`
radius (not the 20km used for §4.1's original Florianópolis-only check):

| Area | Origin | Radius | Trail ways | Unique trail names | Same-named-segment groups | Named lakes | Query time |
|---|---|---|---|---|---|---|---|
| floripa | -27.60,-48.48 | 30km | 21 | 11 | 2 | 14 | ~22.5s |
| rio | -22.9878,-43.1913 | 20km | 100 | 63 | 19 | 67 | (not separately timed; well under `[timeout:60]`) |
| salvador | -12.9777,-38.5016 | 25km | 43 | 36 | 5 | 129 | ~18.6s |

All three areas return real, non-trivial trail coverage; §4.1's Florianópolis-only result was not
an outlier. The widest configured radius (floripa, 30km) answers in ~22.5s, comfortably inside
Overpass's own `[timeout:60]` and this repo's `HttpTimeoutSeconds = 60` client-side timeout.
§11's "does the widest radius stay inside budget" question is answered yes. Rio has by far the
richest trail data of the three (100 ways, 63 names, 19 real duplicate-name groups, Pão de Açúcar/
Corcovado's dense hiking-trail network), and is also the only one of the three live captures whose
`sac_scale` tag is populated on many trails (`hiking`, `mountain_hiking`, `demanding_mountain_hiking`
all observed). floripa and salvador's captures carried no `sac_scale` at all in this run,
consistent with §8's "trail data quality varies with who mapped it," not a bug in the query.

### A.2 `TrailFinderSpec`'s fixture

`core/src/test/resources/fixtures/overpass-trails-floripa.json` is §4.1's exact third query
(`[out:json][timeout:60]; ... around:20000,-27.6733,-48.4700 ...`, plus `.lakes out center;`),
captured live against the real endpoint on 2026-09-07, not fabricated. It returns 20 named trail
`way`s / 10 unique names, including both of the real same-named-segment merge cases named in §8
(`Caminho da Costa da Lagoa ao Canto dos Araçás` ×8 segments, `Trilha Parque Estadual do Rio
Vermelho` ×4 segments) and the exact trail named in §7's "Done" example
(`Trilha da Lagoinha do Leste`, single segment, 2.11km). This particular capture carries no
`sac_scale` tag on any trail (difficulty absence is exercised; presence is not, in the live
fixture) but does carry one real `surface=paving_stones` tag; `TrailFinderSpec` covers the
difficulty-presence case with a small hand-written synthetic Overpass response instead, kept
clearly separate from the live-fixture-based tests.
