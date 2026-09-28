# MIP-0021: Beach accessibility — parking, toilets, showers and lifeguard posts from OSM, shown only where the data exists

| | |
|---|---|
| **Status** | Implemented — merged to main via PR #202 (`c775ebb`); the `mip-0021/1-beach-accessibility` branch is now stale/superseded by that squash-merge |
| **Author** | Claude Fable 5.1, for M. Hoffmann (candidate K4 of the 2026-09-06 external consolidation, `docs/4-Research-and-plans/ROADMAP.md` §7) |
| **Created** | 2026-09-06 |
| **Phase** | 0 (CLI notes) → 1 (bot reply, map card); no cloud, no earlier-phase prerequisite for the CLI half |
| **Related** | `BeachFinder` (the Overpass path this reuses), MIP-0005 (map card), MIP-0002 (bot reply), `docs/2-Building-marola/ARCHITECTURE.md` §5 (pluggable-integration pattern), `docs/4-Research-and-plans/ROADMAP.md` §7 provider-query checklist (first box, answered in §4). **See also [MIP-0046](./MIP-0046-remove-ai-slop-ui.md)** (Draft) — the map's facilities line (`FACILITY_LABEL` in `site/static/app.js`, where this MIP's counts reached the map in PR #233) loses its 🅿️🚻🚿🛟 for SVG pictograms; the "no data, never none" rule this MIP set is untouched |
| **Effort** | S — one new trait + Overpass client in `core/beaches`, one extra query per run, a fixture and a spec; no new dependency, no LLM |
| **Gain** | user value (where can I park / is there a lifeguard post — safety-relevant facts today's reply lacks) |
| **Effort vs Gain** | cheap win — buildable today with no new provider; the only risk is the sparse data, which the design treats as a fact to show, not a gap to paper over |
| **Depends on** | nothing blocking; the bot/map surfaces land with MIP-0002/MIP-0005's card, the CLI notes need neither |
| **Risk** | OSM coverage is thin (§4: 4 showers, 5 lifeguard posts, 8 toilets near 40 beaches) — a feature that says "no lifeguard" when it means "no one mapped one" would be worse than nothing; hence the absence rule in §5 |
| **Cost so far** | — |

## 1. Summary

An `AccessibilityClient` fetches, in one extra Overpass query per run, the amenities OpenStreetMap
knows within 300 m of the beaches `BeachFinder` already found (parking, toilets, showers,
lifeguard posts) and marola prints them as facts with counts ("parking nearby: 3, lifeguard post:
yes"). Where OSM has nothing, marola says **"no data"**, never "none": the coverage measured in §4
is far too thin to read absence as evidence. Deterministic, no model text, same trait/backend
pattern as every other integration.

## 2. Motivation

The reply already says whether the water is fit and the sea is calm; it says nothing about
whether you can get there with a car, whether there is a shower, or whether a lifeguard post
exists. These are the questions that decide between two beaches with the same score, and the one
(lifeguard) that is safety-relevant. OSM has this data around Florianópolis, unevenly (§4), and
`BeachFinder` already talks to Overpass with retries and a generous element budget; a second
query in the same shape costs one HTTP call.

`wheelchair` is deliberately **not** in v1: the beach elements around Florianópolis carry zero
`wheelchair` tags (§4). A wheelchair verdict derived from nothing would be an accessibility claim
with no data behind it, the exact failure this MIP's absence rule exists to prevent. It returns
as an open question once someone has tagged the beaches.

## 3. User-visible change

CLI (`--brief`/`--summarize`), one line per beach when at least one amenity has data:

```
  Praia da Joaquina  62/100 at 10:00  · parking nearby: 3 · toilets: 1 · lifeguard post: yes
  Praia do Matadeiro 58/100 at 09:00  · facilities: no data
```

- Only amenities OSM returned are named; the rest are folded into "no data", never "no
  parking" / "no lifeguard".
- Later (Phase 1): the same line as a `facilities` block in the board JSON (MIP-0005 card) and a
  clause in the Telegram reply (MIP-0002). This MIP ships the CLI line and the board field; the
  bot clause rides with MIP-0002.

## 4. Data sources and dependencies reviewed

**Overpass API** (`https://overpass-api.de/api/interpreter`, the endpoint `BeachFinder` already
uses; free, no key, ODbL, attribution already carried by the map). Queried 2026-09-06 with the
exact `BeachFinder` shape (node/way/relation `natural=beach` + `name`, `around:20000` of Campeche
`-27.6733,-48.4700`) plus `nwr[...](./around.b:300)` for amenities near those beaches:

| Measured | Count |
|---|---|
| Beach elements / distinct names found | 41 / 40 |
| Beach elements carrying `wheelchair`, `shower`, `toilets`, `lifeguard`, `parking`, `supervised`, `lit`, `fee`, `access`, `surveillance` (any of them) | **0** |
| `amenity=parking` within 300 m of any found beach | 36 elements |
| `amenity=toilets` within 300 m | 8 |
| `amenity=shower` within 300 m | 4 |
| `emergency=lifeguard` within 300 m | 5 |
| `emergency=lifeguard_base`, `amenity=lifeguard` | 0 |

Two consequences the design takes from these numbers: (1) the useful data is on **nearby
amenity elements**, not on the beach tags, so the query is an `around` from the beach set, not a
tag read; (2) 36 parking elements is real coverage, 4–8 for the rest is anecdotal; per-beach
attribution (which of the 40 beaches the 5 lifeguard posts belong to) is the first thing the
implementing PR must measure and commit as the fixture, not assume.

**Not checked:** how many of the 53 nearby elements are duplicates (a parking mapped as node and
area), seasonal lifeguard posts vs permanent ones (OSM has no season tag in this data), and
coverage outside the Florianópolis 20 km circle (Bahia/Rio areas from `site/areas.json`).

## 5. Design

**Scala, `core/beaches` (deterministic, tested).**

- `enum Facility { Parking, Toilets, Shower, Lifeguard }` (`derives CanEqual`).
- `final case class Facilities(counts: Map[Facility, Int])`: a facility absent from the map
  means *no data*; a present key with `0` is not produced (OSM cannot say "there is none").
  `Facilities.NoData = Facilities(Map.empty)`.
- `trait AccessibilityClient { def near(beaches: List[Beach], radiusM: Int = 300): Map[String, Facilities] < Sync }`
  keyed by beach name (the key `BeachFinder` dedupes on).
- `OverpassAccessibilityClient`: one query, the found beaches' coordinates as an `around`
  union, `Http.postForm` with `BeachFinder`'s timeout/retry constants; attributes each returned
  element to the nearest found beach within 300 m (haversine, `Coordinates.distanceKm`), counts
  per `Facility`. Dedup by OSM id (node/way/relation types can repeat one real place, §4's open
  point; if the fixture shows heavy duplication, dedupe by rounded coordinate instead, decided
  in the implementing PR).
- `NoopAccessibilityClient` returns `NoData` for every beach: the default when Overpass fails
  (`Http` errors map to it; facilities never fail a run, same stance as the water provider).
- `Recommender`/`Report`: `facilitiesLine(f: Facilities): Option[String]`: `None` when no data,
  else the counts in a fixed order; `Board`'s per-beach JSON gains an optional `facilities`
  object (`{"parking": 3, "toilets": 1, "lifeguard": 1}`; absent keys = no data; schema stays 1,
  `additionalProperties` handled the way MIP-0009 task 1 does it).
- `AppConfig`: `MAROLA_FACILITIES=off|overpass` (default `overpass`), so a run can skip the
  extra query; no cloud backend, there is no cloud source for this data.

**What goes through the LLM: nothing.** Counts and fixed labels only; the summary prompt is not
told about facilities in v1 (a later MIP may let the reviewer pass mention "lifeguard post: yes").

## 6. Scoring / safety impact

`Swimability.score` is untouched: a lifeguard post does not make water safer to enter, and the
data is too sparse to weigh. Safety text changes in one way: a new fact can appear ("lifeguard
post: yes") and it is only ever printed from a returned OSM element, never inferred. The
absence rule ("no data", never "none") is the safety property; the spec pins it.

## 7. Verification plan

- Fixture: the real Overpass response for the Campeche short-list query, recorded live on
  2026-09-07 (`core/src/test/resources/fixtures/overpass-facilities-campeche.json`, same
  convention as the golden fixtures), with the per-beach attribution the implementing PR measured.
  **Measured, not assumed.** The design's `around`-per-beach query (§5) is a 300m radius around
  the *pipeline's actual 6 nearest beaches*, not the §4 survey's 40-beach/20km sweep, and the real
  result is sparser than §4's numbers might suggest: only 2 elements total, one `amenity=parking`
  ~100m from Praia do Campeche, one `emergency=lifeguard` ~150m from Praia do Rio Tavares. Joaquina,
  Morro das Pedras, Gravatá and Armação all get `Facilities.NoData`. This is itself the absence
  rule (§5) working as designed, not a bug: OSM's facility coverage this close to these particular
  six beaches is genuinely thin.
- `AccessibilitySpec` (golden style, like `PipelineGoldenSpec`): Campeche's parking and Rio
  Tavares's lifeguard post counts equal the fixture's; the other four beaches yield `NoData` and
  `facilitiesLine` is `None`; a node/way pair for one real place (different OSM ids, near-identical
  coordinates; dedup by rounded coordinate, not by OSM id, since Overpass's own union already
  drops literal duplicate elements) counts once; an Overpass failure yields `NoData` for all and
  the run still completes; the board's `facilities` object omits absent facilities.
- `BoardSpec`: schema accepts a board with and without `facilities`.
- Live: `just run -- --brief --lat -27.6733 --lon -48.4700` (via `sbt cli/run`) printed
  `· parking nearby: 1` for Praia do Campeche, `· lifeguard post: yes` for Praia do Rio Tavares,
  and `· facilities: no data` for the other four beaches, confirmed 2026-09-07, matching the
  fixture exactly.

## 8. Risks, limitations, and honest caveats

- The data is thin and uneven (§4). The line will read "no data" for most beaches in v1; that
  is the truthful output, and the map's contributors (OSM) are the fix, not marola.
- A 300 m radius is a guess: too small misses a car park across the road, too large attributes
  one park to two neighbouring beaches (Ingleses/Santinho). The fixture decides; §11.
- Lifeguard posts in OSM carry no season; "lifeguard post: yes" in July may be an empty tower.
  The label says "post", not "lifeguard on duty"; keep it that way.
- One more Overpass call per run against a shared public endpoint; `BeachFinder`'s retry/timeout
  discipline applies, and `MAROLA_FACILITIES=off` exists for benchmarks.

## 9. Alternatives considered

- **Read tags on the beach elements** (`wheelchair=*`, `lifeguard=*`). §4 measured zero; a
  design built on those tags would print "no data" for every beach forever. Rejected.
- **Google Places / a paid POI API.** Richer, costs money, and against the local-first rule.
  Rejected.
- **Fold it into `BeachFinder`'s query.** One round trip instead of two, but couples beach
  discovery to an optional feature and makes the facilities toggle impossible. Rejected for v1;
  revisit if the second call shows up in `just benchmark`.
- **Ask the LLM to describe facilities.** Unsourced text about safety-relevant facts; forbidden
  by the `mip` skill's own rule. Rejected.
- **Do nothing.** The reply keeps answering "when" and not "how do I get there and is anyone
  watching". Not chosen; the cost is one small module.

## 11. Open questions

1. ~~Radius: 300 m (this survey) or the beach's own extent (way/relation geometry, which Overpass
   can return with `out geom`)?~~ **Resolved for v1:** fixed 300m, `radiusM` on the trait. Beach
   geometry (`out geom`) is a real future refinement for large beaches but adds a second query
   shape; not worth it until the fixed radius is shown to misattribute in practice.
2. ~~Dedup key for one real place mapped twice: OSM id types or rounded coordinates?~~
   **Resolved:** rounded coordinates (~11m, `OverpassAccessibilityClient.DedupCoordDecimals`); an
   id-based dedup can't catch this case at all, since a node and its enclosing way live in
   different OSM id spaces (§7).
3. Should `wheelchair` return once *any* SC beach carries the tag, or only when a threshold of
   beaches do? Proposal: show it per beach as soon as it exists, since it is per-beach data.
4. Should the bot reply (MIP-0002) include the line by default or on request (`/estrutura`)?

## Appendix

**A. Query used (2026-09-06):** `BeachFinder`'s beach union → `.b`, then
`nwr["amenity"="parking"](./around.b:300)`, `amenity=shower`, `amenity=toilets`,
`emergency=lifeguard`, `emergency=lifeguard_base`, `amenity=lifeguard`, `out tags center`.
Results in §4; raw counts: 41 beach elements, 53 nearby amenity elements
(36/8/4/5/0/0).
