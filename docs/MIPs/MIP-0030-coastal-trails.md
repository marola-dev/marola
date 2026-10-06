# MIP-0030: Coastal and lakeside trails on the map — named OSM paths and hiking routes near a beach or a lake

| | |
|---|---|
| **Status** | Partially implemented (`TrailFinder` and the board's `trails` array are on marola-app `main`; the site's trails layer is built but shown as "em breve". Still missing: hiking-route relations, the read from MIP-0078's lake export, and switching the toggle on — marola-dev/marola#678) |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: "create a MIP for adding all trails info to the rendered map (only trails nearby the ocean or a lake), decide if the fetch mechanism should be the same as IMA/SC"); revised by Claude for M. Hoffmann (2026-10-06: "use wikiloc or other free provider to draw trails in marola-site") |
| **Created** | 2026-09-06 (revised 2026-10-06: provider review, route relations, the lake read path, Mapbox) |
| **Phase** | 0 for the board field and the map layer. The lake read path is MIP-0078's and carries its Phase 2 question |
| **Related** | `BeachFinder` (`core/src/main/scala/marola/beaches/BeachFinder.scala`, whose Overpass client this reuses); MIP-0021 (beach accessibility, the precedent: an OSM layer anchored on `BeachFinder`'s results, with "no data" never shown as "none"); MIP-0005/MIP-0009 (the map); MIP-0075 (the B2 lake and its `trail` table); MIP-0078 (open trail providers in that lake, per state); `docs/2-Building-marola/ARCHITECTURE.md` §7 (Overpass fair use) |
| **Effort** | S — one Overpass client in `core/trails`, one top-level array on the board, one GeoJSON line layer on the map; this revision adds one relation clause to the query and a snapshot read |
| **Gain** | user value: a beach's own OSM page rarely says "there's a lakeside trail 400 m north". marola already answers "is the water clean", and "can I walk somewhere from here" is the same kind of fact |
| **Effort vs Gain** | cheap win — the board already carries trails, so the map can show them the day the toggle turns on |
| **Depends on** | Nothing for the board and the layer. The lake read path depends on MIP-0078 |
| **Risk** | OSM's trail coverage varies with tagging, not geography. Short local trails are named `highway=path`/`track` ways, and long waymarked trails are `route=hiking` relations. A query for only one of the two misses the other (§4.1) |
| **Cost so far** | n/a |

## 1. Summary

marola shows named trails from OpenStreetMap near any beach or lake it already found for an area:
named `highway=path`/`track` ways within 500 m of an anchor, and `route=hiking`/`foot` relations
with a member within that distance. Each trail keeps its OSM id, length, the `sac_scale` and
`surface` tags when they are present, and its geometry, and the board carries them as a `trails`
array. The map draws them as a line layer. OSM (ODbL) stays the only source: Wikiloc and AllTrails
offer no licence to reuse their tracks (§4.3). Once MIP-0078 lands, a build reads trails from the
lake's per-area export and stops querying Overpass for them.

## 2. Motivation

Someone at Praia do Pântano do Sul today gets a swim score and nothing else. OSM already knows the
beach is the start of `Trilha da Lagoinha do Leste`, and that `Trilha Lagoa do Peri - Sul` runs
along a lake 3 km away. In Rio, OSM maps the Transcarioca as one 180 km hiking route
(relation 6906578), which the first version of this MIP never asked for. The board has carried a
`trails` array since 2026-09-07. The site still shows the trilhas toggle as "em breve"
(marola-site `site/static/index.html`), so a visitor sees none of it.

## 3. User-visible change

CLI (`--brief`/`--summarize`), one line per beach that has a trail within 500 m:

```
  Praia do Pântano do Sul   58/100 at 09:00  · trails nearby: Trilha da Lagoinha do Leste (2.1km, moderate)
  Praia da Joaquina         62/100 at 10:00  · trails: no data
```

On the map, the trilhas toggle is on. Each trail is a line along its real OSM geometry, coloured
by `sac_scale` (green for `hiking`, amber for `mountain_hiking` and above, grey when the tag is
missing). Its tooltip gives the name, the length and "ver no OpenStreetMap", a link to the way or
relation. A route relation is drawn slightly wider than a single way. The board's array:

```json
"trails": [
  { "name": "Trilha da Lagoinha do Leste", "osm": "way/123456789", "kind": "path",
    "length_km": 2.1, "difficulty": null, "surface": null,
    "near": [{"beach": "Praia do Pântano do Sul", "distance_km": 0.1}],
    "geometry": [[-27.7910, -48.4890], [-27.7925, -48.4871]] },
  { "name": "Trilha Transcarioca", "osm": "relation/6906578", "kind": "route",
    "length_km": 68.1, "difficulty": null, "surface": null,
    "near": [{"beach": "Praia de Grumari", "distance_km": 0.3}],
    "geometry": [[[-23.04, -43.52], [-23.05, -43.51]], [[-22.95, -43.28], [-22.96, -43.27]]] }
]
```

`osm` and `kind` are new and optional, and the schema stays 1. A route's `geometry` is a list of
lines, because a relation's members do not join into one line. `length_km` is the mapped length,
never a length the route's tags claim. `difficulty` and `surface` are `null` when OSM has no tag,
never a guess.

## 4. Data sources and dependencies reviewed

### 4.1 OpenStreetMap via Overpass — verified live, 2026-09-06 and 2026-10-06

This is the endpoint `BeachFinder` already uses (`https://overpass-api.de/api/interpreter`). It is
free, needs no key and is licensed ODbL. The map already carries the attribution (MIP-0005).

- **Named ways (2026-09-06).** Named `highway=path`/`track` ways within 15 km of Pântano do Sul
  returned 45 results. They included `Trilha da Lagoinha do Leste`, `Trilha de Naufragados`
  (`trail_visibility: excellent`) and `Trilha Praia do Maço-Guarda` (`sac_scale: mountain_hiking`).
- **Route relations (2026-09-06 and 2026-10-06).** The same 15 km search for `route=hiking` found
  none, and the first version of this MIP dropped relations on that result. That was wrong for Rio.
  Waymarked Trails' API (`hiking.waymarkedtrails.org/api/v1/details/relation/6906578`, 2026-10-06)
  returns `Trilha Transcarioca` with `route=hiking`, `network=rwn`, `official_length_m` 180000 and
  `mapped_length_m` 68121. Its search lists seven more relations named "Acesso a Trilha
  Transcarioca". Relations are rare near Florianópolis and real in Rio, so the query asks for both.

The query, one request per area:

```
[out:json][timeout:60];
way["natural"="beach"]["name"](around:RADIUS,LAT,LON)->.beaches;
( way["natural"="water"]["water"~"^(lake|pond)$"](around:RADIUS,LAT,LON);
  relation["natural"="water"]["water"~"^(lake|pond)$"](around:RADIUS,LAT,LON); )->.lakes;
( way(around.beaches:500)["highway"~"^(path|track)$"]["name"];
  way(around.lakes:500)["highway"~"^(path|track)$"]["name"]; )->.ways;
( way(around.beaches:500)["highway"]; way(around.lakes:500)["highway"]; )->.near;
relation(bw.near)["type"="route"]["route"~"^(hiking|foot)$"]->.routes;
.ways out tags geom;
.routes out tags geom;
.lakes out center;
```

`out tags geom` returns each line's real path. A point at a multi-kilometre trail's bounding-box
centre can sit nowhere near the water. The relation clause has not been run against Overpass yet
(the sandbox cannot reach it): it is checked in §7 before the client changes.

### 4.2 Why not the IMA/SC pattern

`ImaScWaterQualityClient` scrapes one state agency's undocumented endpoint, because bathing water
is measured and published by each state separately. Trails have no such split. OSM covers
Florianópolis, Rio and Salvador through one API, so the model here is `BeachFinder` and
`OverpassAccessibilityClient`, not a per-state scraper. MIP-0078 does run per state, but that is
because each state is one slice of the same OSM data, not a separate publisher.

### 4.3 Other trail providers — checked 2026-10-06

| Provider | Data | Terms | Verdict |
|---|---|---|---|
| **OpenStreetMap** (Overpass; Geofabrik extracts in MIP-0078) | ways and route relations, global | ODbL 1.0, attribution "© OpenStreetMap contributors" | **the source** |
| **Waymarked Trails** | renders OSM's route relations and has a JSON API | the data is OSM's (ODbL); the API has no published usage policy | a cross-check and a link target, not a second source |
| **Wikiloc** | tracks uploaded by users | no public API found. In 2010 its founder declined blanket reuse unless three attributions were shown (wikiloc.com, the trail page and the author's page) ([OSM-talk](https://lists.openstreetmap.org/pipermail/talk/2010-May/050017.html)). The terms page refused automated fetches (403), so it was not read | **rejected** as a data source: copying tracks would need each author's permission |
| **AllTrails** | curated trails | proprietary. Its affiliate programme declined marola on 2026-10-06 | rejected |
| **Trilha Transcarioca** (official site) | an app, PDF guides, GPX "tracklogs" named in the page metadata | no reuse terms on the downloads page | not used. OSM's relation already maps 68 of its 180 km |

## 5. Design

`core/src/main/scala/marola/trails/TrailFinder.scala` exists on marola-app `main`. This revision
changes it as follows:

```scala
enum TrailKind:
  case Path, Route

final case class Trail(
  name: String,
  osm: String,                        // "way/123" | "relation/6906578", for the map's link
  kind: TrailKind,
  lengthKm: Double,                   // the mapped geometry's length, never a tag's claim
  difficulty: Option[String],         // OSM sac_scale, verbatim
  surface: Option[String],
  geometry: List[List[Coordinates]],  // one line for a way, one per member run for a route
  nearBeach: Option[(String, Double)],
  nearLake: Option[(String, Double)]
)
```

- **The query** gains §4.1's relation clause, which keeps it one request per area. Same-named ways
  are still merged into one `Trail` (§8). A way that is also a member of a returned relation is
  drawn only as part of that relation.
- **The snapshot read.** `TrailFinder.nearby` first reads `MAROLA_TRAILS_DIR/<BeachSnapshot.key>.json`
  (MIP-0078's per-area export, in this array's shape) and asks Overpass only when that file is
  missing. This is the same contract `BeachFinder` already has with `MAROLA_BEACHES_DIR`. A build
  then calls Overpass for trails only in an area the lake does not cover yet.
- **The map** (marola-site `site/static/app.js`): the layer drawn by `addTrailLayer()` takes the
  multi-line geometry and the link, and `index.html` drops `disabled`/`soon` from the toggle. The
  about page names OSM as the trail source under its licence, following the
  `citizen-science-site` skill.

Nothing here passes through an LLM. The name, length, difficulty and surface are OSM tags or a
computed length, shown as they are or as "no data".

## 6. Scoring / safety impact

None. Trails never change `Swimability.score` or any safety text. A trail's `sac_scale` is
shown as OSM's tag, not as advice that the walk is safe.

## 7. Verification plan

- Run §4.1's query live for floripa, rio and salvador at their `site/areas.json` radius. Record
  the ways, relations, response time and size in the Appendix. Rio must return the Transcarioca
  relation.
- `TrailFinderSpec`: the captured Rio answer parses into one `Route` trail with several lines and
  `osm = "relation/6906578"`. A way that belongs to that relation is not drawn twice. A file in
  `MAROLA_TRAILS_DIR` is read without any request (through `Http.withTransport`).
- `scripts/site_check.js` (marola-site): a fixture trail with multi-line geometry draws one
  feature per line, and its tooltip has the OSM link. With the toggle enabled, `?trails=0` hides
  the layer. A board with no `trails` key still renders every beach.
- Done means the toggle is on at marola.dev and Praia do Pântano do Sul shows Trilha da Lagoinha
  do Leste. In Rio, the Transcarioca's mapped sections show as one route.

## 8. Risks, limitations, and honest caveats

- **Overpass fair use** (`ARCHITECTURE.md` §7): this is a second query per area per build. Once
  MIP-0078's exports exist, it runs only where an area has no export.
- **Coverage follows the mappers.** `sac_scale` is present on some segments and missing on others
  of the same trail (Appendix A.1). Only 68 of the Transcarioca's 180 km are mapped. The map shows
  what OSM has and never fills a gap.
- **Ways split into segments.** OSM often stores one named trail as several ways, and they are
  merged by name within an area. Two different trails with the same name in one area would be
  drawn as one line.
- **500 m is a guess.** It matches MIP-0021's radius. A trailhead can sit a kilometre inland from
  the beach the trail is named after.

## 9. Alternatives considered

- **Do nothing.** Free, but it leaves a fact that is already mapped off a map that exists to show
  what is near a beach.
- **A per-state scraper, IMA/SC style** (§4.2), **ways only** (misses the Transcarioca), and
  **relations only** (misses almost every local trail).
- **Wikiloc or AllTrails tracks** (§4.3): no licence to reuse them.

## 10. Exam-coverage mapping

None.

## 11. Open questions

- **A "procurar no Wikiloc" link** in the tooltip: a search URL with the trail's name, with no data
  copied and no request from the page. It adds a link to a commercial site, which the about page
  would have to explain. Maintainer's call. Not in §5 until he says so.
- **The radius**: measure 500 m to the trail's nearest point (what Overpass's `around` does) or
  to its trailhead?

## Appendix

The raw Overpass answers are not committed. They can be fetched again with the query text above,
and that text is the reproducible artifact.

### A.1 Ways across the three areas (2026-09-07)

The query without the relation clause, at each area's `site/areas.json` radius:

| Area | Origin | Radius | Trail ways | Unique names | Same-named groups | Named lakes | Query time |
|---|---|---|---|---|---|---|---|
| floripa | -27.60,-48.48 | 30km | 21 | 11 | 2 | 14 | ~22.5s |
| rio | -22.9878,-43.1913 | 20km | 100 | 63 | 19 | 67 | well under `[timeout:60]` |
| salvador | -12.9777,-38.5016 | 25km | 43 | 36 | 5 | 129 | ~18.6s |

Rio carried `sac_scale` on many trails (`hiking`, `mountain_hiking`,
`demanding_mountain_hiking`). The Florianópolis and Salvador answers carried none.

### A.2 `TrailFinderSpec`'s fixture

`core/src/test/resources/fixtures/overpass-trails-floripa.json` is the ways query captured live on
2026-09-07 (20 km around -27.6733,-48.4700): 20 ways, 10 names, including
`Caminho da Costa da Lagoa ao Canto dos Araçás` (8 segments) and `Trilha Parque Estadual do Rio
Vermelho` (4 segments). The relation case needs a new Rio capture (§7).
