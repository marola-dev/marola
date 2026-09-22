# MIP-0031: Water quality for Rio de Janeiro (INEA) and Bahia (INEMA) — closing the "no data" gap

| | |
|---|---|
| **Status** | Partially implemented (tasks 1-4 of 6, `docs/mips/MIP-0031.tasks.md`) — both §11 research gaps resolved 2026-09-07; INEMA parser + Salvador coordinate table (tasks 1/3, PR #203) and INEA parser + Rio coordinate table (tasks 2/4, PR #200) merged to main; tasks 5/6 (`InemaBaWaterQualityClient`/`IneaRjWaterQualityClient` wiring into `AppConfig`) not yet built |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: "Fix water quality to add new institutes" — Rio and Bahia render correctly but show no water-quality verdict, unlike Florianópolis) |
| **Created** | 2026-09-06 |
| **Phase** | 0 (CLI + board field only) — no earlier-phase prerequisite is missing |
| **Related** | `WaterQuality.scala`'s `WaterQualityClient` trait, whose own doc comment already names this exact gap ("INEA/RJ, CETESB/SP would be siblings"); `ImaScWaterQualityClient` (the pattern this cannot copy — see §4.2); `AppConfig.waterQualityClient`'s `Auto` case (`cli/src/main/scala/marola/AppConfig.scala:159`, the only place that needs to learn about new providers); MIP-0030 §4.3 (the investigation that surfaced this as a separate, real gap rather than a rendering bug); `knowledge/*.md`'s sourced-corpus pattern (the closest existing precedent for a hand-curated, sourced JSON resource, reused here for §5's coordinate table) |
| **Effort** | **M**, not IMA/SC's S — re-assessed after live verification in §4: two new region-specific clients, a new PDF-text-extraction dependency, and (the real driver) a hand-curated point→coordinate lookup table per state, because neither institute's bulletin carries coordinates (§4.3) |
| **Gain** | user value (Rio and Bahia currently show "no data" for every beach's water quality — the single biggest missing fact those two areas have relative to Florianópolis); exam coverage (AI-103 §1 transparency — same "no data, never guessed" discipline already applied elsewhere) |
| **Effort vs Gain** | `do next` — real, bounded, buildable work; not a cheap win like IMA/SC was, but not "expensive, defer" either: the hard parts (PDF structure, point universe size) are now measured, not guessed |
| **Depends on** | Nothing blocking; no Phase 1 gate, no paid resource. Does depend on this MIP's own §5 design (the coordinate table) existing before either client can produce a correctly-matched result — sequencing note, not an external blocker |
| **Risk** | The point→coordinate table is hand-curated and **will drift** as INEA/INEMA add or retire sampling points — unlike IMA/SC's JSON feed (which carries its own coordinates and self-updates), this needs a human to notice and fix drift, or points silently stop matching and quietly degrade to "no data" (safe failure mode, but a real maintenance cost this MIP should not undersell) |
| **Cost so far** | — |

## 1. Summary

Two new `WaterQualityClient` implementations, `IneaRjWaterQualityClient` (Rio de Janeiro) and
`InemaBaWaterQualityClient` (Bahia), extend `AppConfig`'s `Auto` provider selection so beaches in
those states get a real PRÓPRIA/IMPRÓPRIA verdict instead of "no data", matching what Santa
Catarina already gets from `ImaScWaterQualityClient`. Unlike IMA/SC, neither state publishes a
JSON feed: both publish PDF bulletins with no coordinates, so this MIP also introduces a small,
sourced, hand-curated `sampling-points-{rj,ba}.json` resource (same shape as `sea_lore.json`'s
curated-entries pattern) mapping each bulletin's point code to a real coordinate, verified once
and kept current by whoever notices drift.

## 2. Motivation

MIP-0030's investigation into "why doesn't the site show water quality for Bahia/Rio" found the
real boards are correct and complete in every other respect (56/63 real beaches, zero rendering
errors). The one thing genuinely missing is water quality, because `AppConfig.waterQualityClient`'s
`Auto` case (`cli/src/main/scala/marola/AppConfig.scala:159-165`) only knows how to check whether
an origin is inside Santa Catarina; anywhere else it returns `None`, honestly, by design. That
design already anticipated growth: `WaterQuality.scala:71-76`'s own doc comment on
`WaterQualityClient` says *"One implementation per agency/portal... INEA/RJ, CETESB/SP would be
siblings"*: this MIP is that sibling-building, for the two states marola already has configured
areas in (`site/areas.json`: rio, salvador).

## 3. User-visible change

CLI (`--brief`/`--summarize`), a beach in Rio or Salvador gains the same water line Florianópolis
beaches already have:

```
  Praia de Ipanema        58/100 at 09:00  |  water: PRÓPRIA (1/1 pts, 04 Sep)  |  ...
  Praia de Botafogo       20/100 at 09:00  |  water: 1/1 IMPRÓPRIA — Botafogo (04 Sep)  |  ...
```

A beach whose nearest bulletin point isn't in the curated coordinate table yet (§8) keeps saying
"no data", never a guess, and never silently wrong.

## 4. Data sources and dependencies reviewed

Every claim below was checked live, 2026-09-06, not assumed from search-result summaries alone.

### 4.1 Rejected: Brasil.IO's Bahia balneability dataset

Initially the leading candidate (structured, no auth, per earlier search results). **Verified
live and rejected**: `curl https://brasil.io/api/v1/dataset/balneabilidade-bahia/` returns
**HTTP 401**: Brasil.IO's API has required an auth token since a documented October 2020 policy
change. Worse: the underlying scraper feeding that dataset,
[turicas/balneabilidade-brasil](https://github.com/turicas/balneabilidade-brasil) (checked via
GitHub's API), has not been pushed to since **2020-03-14**: the dataset is a frozen historical
snapshot, not a live feed. Unusable for "is the water safe today" regardless of the auth question.

### 4.2 Rejected as a mechanism: an IMA/SC-style undocumented JSON endpoint — none found

IMA/SC's client works because Santa Catarina's bathing-water portal happens to expose its own
map's data as an undocumented `POST /relatorio/mapa` JSON endpoint. The equivalent hunt for
Bahia: INEMA has its own dedicated portal at `balneabilidade.inema.ba.gov.br`, the same
dedicated-subdomain shape as IMA/SC's `balneabilidade.ima.sc.gov.br`, including a live interactive
Leaflet map. But its map-loading JS (`mapa.min.js`, `script.js`, both fetched and inspected live)
references only a static coastline shape file (`costas-simplify.json`) and a PDF-generation
controller, `index.php/relatoriodebalneabilidade/geraBoletim?idcampanha=N` (confirmed live: `curl`
against `idcampanha=83453` returns a real 77KB PDF, HTTP 200). No JSON data endpoint was found in
either script. Rio's INEA has no equivalent dedicated portal found at all; only bulletin pages.
**Not fully ruled out**: INEMA's search form (referenced by `script.js`'s `.campanha`/
`.alert-resultado` selectors) might POST to an endpoint returning structured HTML rather than a
PDF, not verified; the form was not actually submitted this session. Flagged as an open question
(§11), not assumed.

### 4.3 The pick: parse the PDF bulletins directly — and the real blocker this surfaces

**Decision: fetch each institute's live bulletin PDF and parse its table**, using INEMA's own
`geraBoletim?idcampanha=N` endpoint (Bahia) and INEA's published bulletin URLs (Rio, pattern
`ba.gov.br`/`inea.rj.gov.br` file paths, e.g. the confirmed-current
`https://www.ba.gov.br/inema/sites/site-inema/files/2026-05/Boletim_Balneabilidade_Salvador_2026_05_08.pdf`
for Bahia). **Verified live**: `curl` + `pdftotext -layout` against the real `idcampanha=83453`
bulletin (Salvador, Bulletin N°13/2025, issued 04/04/2025) produces a clean, genuinely
machine-parseable fixed-column table, not a scanned image:

```
Ponto - Código                    Local da Coleta                                          Categoria
São Tomé de Paripe - SSA IN 100   Em frente à casa Vila Maria, ao lado da rampa...           Própria
Tubarão - SSA PR 200              Em frente ao conjunto habitacional abandonado...           Imprópria
...
```

**The real blocker this reveals: no coordinates anywhere in the bulletin.** Each row has a point
name, a state-assigned code (`SSA IN 100`), and a free-text street/landmark description, never a
latitude/longitude. `SamplingPoint.coordinates` (`WaterQuality.scala:37`) is a **mandatory** field.
`WaterQualityMatcher` (`core/src/main/scala/marola/water/WaterQualityMatcher.scala`) assigns
points to beaches by geographic distance, not by name-string matching, because IMA/SC's own feed
already carries real coordinates per point. Neither INEA nor INEMA's bulletin does. This is the
actual reason this MIP is Effort M rather than S: not "PDF vs. JSON" but "no coordinates at all."

**Pick, therefore**: parse the PDF for point code, description, and category (Própria/Imprópria/
Indisponível), and resolve each point's coordinate from a small hand-curated lookup resource
(§5) rather than from the bulletin itself. `Apache PDFBox` (Apache-2.0, pure JVM, no native binary
dependency, unlike shelling out to `pdftotext`, which this session used only for verification, not
as a runtime dependency a Docker image would need to bundle) is the concrete library pick for text
extraction; not yet added to `build.sbt`, done in the implementation PR.

### 4.4 Point universe size — verified counts, not guessed

Rio: **291 sampling points across 201 beaches, 22 municipalities** (INEA, verified via its own
published monitoring-programme description). Bahia: **134 points along the whole coast** (INEMA,
same). Both numbers matter for §5's curation effort estimate and for whether a subset (only points
near marola's configured `rio`/`salvador` areas) is sufficient rather than the whole state.

## 5. Design

New module-local resources, `local/src/main/resources/sampling_points_rj.json` and
`_ba.json`, same curated-and-sourced shape `sea_lore.json` already establishes (MIP-0001's corpus
rules: every entry has a source, nothing invented):

```json
[
  { "code": "SSA IN 100", "beach_hint": "São Tomé de Paripe",
    "lat": -12.xxxx, "lon": -38.xxxx,
    "source": "manually geocoded from INEMA bulletin's 'Local da Coleta' description, 2026-MM-DD" }
]
```

Populated only for points near marola's configured areas (`site/areas.json`'s `rio`/`salvador`
radii), not the full 291/134, bounds the curation effort to what's actually rendered, expanded
later if more areas are added.

```scala
final class InemaBaWaterQualityClient(pdfEndpoint: String = InemaBaWaterQualityClient.DefaultEndpoint)
    extends WaterQualityClient:
  def name: String = "INEMA/BA"
  def samplingPoints: List[SamplingPoint] < Sync =
    Http.getBytes(pdfEndpoint, timeoutSeconds = 30).map { pdfBytes =>
      val rows = InemaPdfParser.parseTable(pdfBytes)  // PDFBox extraction + fixed-column split
      rows.flatMap(row => SamplingPointCoordinates.lookup(row.code).map(coord =>
        SamplingPoint(row.code, row.beachName, row.pointName, row.location, coord, row.samples)))
    }

object InemaBaWaterQualityClient:
  def coversOrigin(origin: Coordinates): Boolean = /* Bahia coastal bounding box, same shape as ImaSc's */
```

`IneaRjWaterQualityClient` mirrors this shape with one addition confirmed in §11: since INEA has
no `idcampanha`-style stable parameter (it publishes dated, per-zone PDFs as static uploads, not a
generate-on-demand endpoint), the client first fetches INEA's bulletin-listing page
(`inea.rj.gov.br/ar-agua-e-solo/balneabilidade-das-praias/`), finds the newest PDF link whose
filename matches the zone(s) marola's `rio` area needs, then parses it exactly like INEMA's:
same `PdfBulletinParser`, a different column layout and point-code convention.

`AppConfig.waterQualityClient`'s `Auto` case (`AppConfig.scala:159`) gains two more `if` branches,
same order-independent bounding-box-check pattern `ImaScWaterQualityClient.coversOrigin` already
uses; `WaterProvider` enum gains `IneaRj`/`InemaBa` cases alongside `ImaSc` for explicit override
via `MAROLA_WATER_QUALITY_PROVIDER`.

A row whose point code isn't in the curated table is **dropped, not defaulted to a guessed
coordinate**: same tolerant-parsing discipline `ImaScWaterQualityClient`'s own doc comment
states ("a malformed point or sample is dropped, never fatal").

## 6. Scoring / safety impact

None to the scoring *function*: `Swimability.waterVerdict` already handles `Option[WaterQualityClient]`
and a populated-vs-empty `samplingPoints` list identically to how it treats IMA/SC today. The
*safety-relevant* change is that more beaches will now show a real IMPRÓPRIA veto instead of no
data, strictly an improvement in coverage, using the same deterministic veto logic already
reviewed for IMA/SC (MIP-0001 §6).

## 7. Verification plan

- `InemaPdfParserSpec`: parses a fixture PDF (the real Salvador bulletin captured in §4.3, checked
  into `local/src/test/resources/`) into rows; asserts point code, category, and multi-line
  description wrapping (the real bulletin wraps long descriptions across two lines, per §4.3's
  captured output) are all handled.
- `SamplingPointCoordinatesSpec`: a point code present in the curated table resolves; one absent
  from it is dropped, not defaulted.
- `InemaBaWaterQualityClientSpec` / `IneaRjWaterQualityClientSpec`: end-to-end from a fixture PDF
  to `List[SamplingPoint]`, mirroring `ImaScWaterQualityClientSpec`'s existing shape.
- A live check before merge: run the real client against `salvador`'s configured origin and
  confirm at least one real beach picks up a real verdict (not just "parses without throwing").
- "Done" = `just run -- --brief --lat -12.9777 --lon -38.5016` (Salvador) shows a real water-quality
  line for at least one beach, and the same for a real Rio origin once INEA's client exists.

## 8. Risks, limitations, and honest caveats

- **The curated coordinate table is the single point of failure and the ongoing cost.** IMA/SC's
  feed self-updates when the state adds/retires a point; this table does not. A retired point
  silently stops matching (safe: falls back to "no data" for that point, never wrong) but a *new*
  point near a configured beach won't be picked up until someone notices and adds it. This MIP
  should ship with a stated re-check cadence (e.g. "check when re-running `just benchmark`"), not
  left implicit.
- **Only Bahia's endpoint was verified live this session.** Rio's INEA client (§5) is designed by
  analogy, not verified against a real current bulletin URL the way §4.3 verified INEMA's: a real
  risk that INEA's actual bulletin table layout differs enough that the same parser doesn't
  transfer cleanly. Flagged, not assumed away.
- **PDF layout is not a contract.** Unlike IMA/SC's (undocumented but structured) JSON, a
  government PDF template can change without notice; `benchmark_gate.py`'s "re-run and compare"
  discipline should extend to periodically checking the parser still produces sane output, not
  just that it doesn't throw.
- **This does not fix Rio/Bahia's water quality *today*.** It is a design for the next
  implementation PR(s), per the `mip` skill's own rule not to build in the same change as the MIP.

## 9. Alternatives considered

- **Do nothing.** Leaves the honest "no data" state MIP-0030 confirmed is not a bug, a legitimate
  choice, but leaves real, available public data unused for two of marola's three configured areas.
- **Brasil.IO's dataset anyway, ignoring staleness.** Rejected outright (§4.1): a 2020 snapshot
  presented as "water quality" would be actively misleading, the opposite of "sourced or clearly
  labelled, never invented."
- **Geocode automatically** (a geocoding API against each point's free-text description) instead
  of hand-curating coordinates. Considered and rejected for v1: introduces a third external
  dependency (a geocoder) with its own accuracy risk for landmark-style Portuguese addresses
  ("Em frente à casa Vila Maria..."), for a one-time task (§4.4: a bounded ~50-100 points near
  marola's actual configured areas, not the full 291/134) that a human can do once, accurately,
  and cite. Revisit if a fourth area's point count makes hand-curation impractical.

## 10. Exam-coverage mapping

AI-103 §1, responsible-AI transparency: identical "no data, never guessed" pattern already mapped
for MIP-0021; extends it to a second data-availability axis (per-region source coverage, not just
per-amenity).

## 11. Open questions

- ~~Confirm INEA's (Rio) actual current-bulletin URL pattern and table layout live~~ **Resolved
  2026-09-07.** Fetched and `pdftotext`-verified a real, current INEA bulletin: `https://www.inea
  .rj.gov.br/wp-content/uploads/2026/06/Zona-sudoeste-e-Zona-sul-17-06-26.pdf` (Boletim N°24,
  17/06/2026, found via WebSearch for a recent `inea.rj.gov.br/wp-content/uploads` PDF). Same
  category of table as INEMA's: clean, text-based, four columns (`PRAIAS`, `LOCALIZAÇÃO (*)`,
  `Ponto Coleta`, `CONAMA 274/2000` classification), point codes in INEA's own convention (e.g.
  `BG00`, `GM00`, `PS01`) instead of INEMA's `SSA IN 100` style, and, the important part, **no
  coordinates here either**, confirming §4.3's blocker (a curated coordinate table, not just a
  parser) applies identically to both institutes. One real difference from INEMA:
  **INEA has no `idcampanha`-style stable "get the current bulletin" parameter.** It publishes
  dated, per-zone PDFs (this one covers "Zonas Sudoeste e Sul" only; Rio's other zones get their
  own PDFs) directly as static uploads, discovered by checking INEA's own bulletin-listing page
  (`inea.rj.gov.br/ar-agua-e-solo/balneabilidade-das-praias/`) rather than by parameterizing a URL.
  `IneaRjWaterQualityClient` needs a "find the latest PDF for the zone(s) marola's `rio` area
  covers" step INEMA's client doesn't, not just a different table parser.
- ~~Submit INEMA's search form~~ **Resolved 2026-09-07.** Fetched the search form
  (`balneabilidade.inema.ba.gov.br/index.php/relatoriodebalneabilidade/boletim`) and read its
  actual JS handler: `$("#btnGeraBoletim").click(...)` builds a plain `GET` form submit straight
  to `.../geraBoletim` with only `idcampanha` as a parameter, i.e. the form is a UI wrapper
  around the exact same PDF endpoint §4.3 already found, not a separate structured-data path.
  **No HTML alternative exists for INEMA.** PDF parsing is confirmed as the only viable mechanism,
  not just the pragmatic pick.
- **Curate the actual coordinate tables** (§5), a real data-entry task against OSM/Google Maps
  for each point's free-text description, scoped to points near `site/areas.json`'s `rio`/
  `salvador` radii; not started, and the single largest remaining unknown for how much work §5
  really is. Still open: this is implementation labor, not a research question, and is scoped
  as its own task in `docs/mips/MIP-0031.tasks.md`.
- Whether Niterói's municipal ArcGIS "Pontos de Balneabilidade" map
  (`sigeo.niteroi.rj.gov.br`) exposes a real queryable feature service, found via search, not
  fetched or verified this session, and would only cover Niterói, not all of Rio de Janeiro city.
  Lower priority now that INEA's own bulletin PDF is confirmed workable directly.

## Appendix

Raw verification commands run this session (re-runnable, not committed as fixtures until the
implementation PR):

```bash
curl -s https://brasil.io/api/v1/dataset/balneabilidade-bahia/          # -> 401, auth required
curl -s https://api.github.com/repos/turicas/balneabilidade-brasil      # -> pushed_at 2020-03-14
curl -s http://balneabilidade.inema.ba.gov.br/index.php/relatoriodebalneabilidade/geraBoletim?idcampanha=83453 \
  | pdftotext -layout - -                                                # -> real, parseable table, no coordinates
```
