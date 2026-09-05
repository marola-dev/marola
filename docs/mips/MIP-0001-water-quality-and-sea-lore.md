# MIP-0001: Bathing-water quality in the ranking, and a sea-lore paragraph in every reply

| | |
|---|---|
| **Status** | Implemented — `ARCHITECTURE.md` §5g/§5h; branch `feat/mip-0001-water-quality-rag-finetune` |
| **Author** | Claude Fable 5.1, for M. Hoffmann |
| **Created** | 2026-09-05 |
| **Phase** | 0 (CLI) now; the reply format is designed for Phase 1 (Telegram) |
| **Related** | `ARCHITECTURE.md` §5 (integration pattern), §8 (heuristics honesty); `FUTURE-WORK.md` §1.3 (wave period), §9.1 (ocean-knowledge grounding — this is its first concrete step); `AI-103-MAPPING.md` rows "Responsible AI" and "Text analysis" |

## 1. Summary

marola ranks beaches by waves, wind, temperature and a jellyfish heuristic, but says nothing about
whether the water is fit to swim in — and in Santa Catarina that is published, per sampling point,
by the state environment agency (IMA). This proposal adds a `WaterQualityClient` integration with
IMA's feed as the local default, folds PRÓPRIA/IMPRÓPRIA into the score deterministically, makes
the top pick's output a detailed block (water quality, tide, wave period) instead of one line, and
ends every reply with one short, sourced paragraph of sea lore — either a "thing most people don't
know about the sea" or a portrait of one sea creature — rotated daily so it doesn't repeat.

## 2. Motivation

The live run from Campeche on 2026-09-05 (`RUN-LOCALLY.md` §4) ranked six beaches at 55/100 with
identical notes. Meanwhile IMA's bulletin of 2026-09-03 has five sampling points on Praia do
Campeche: four PRÓPRIO, and Ponto 73 at the mouth of the Riozinho do Campeche IMPRÓPRIO with
749 enterococci/100mL (limit: 100). marola would have sent someone into that stream mouth with a
"choppy, cold water" note and nothing else. That is exactly the "safety-relevant, deterministic,
outside the LLM" category `AGENTS.md` says marola must get right.

The second half — the lore paragraph — is product, not safety: the reply is currently a number
and a sentence. One well-sourced paragraph about the sea in front of the reader is the cheapest
way to make it something people open on purpose, and it seeds the marine-knowledge corpus
`FUTURE-WORK.md` §9.1 needs anyway.

## 3. User-visible change

Today (top pick, one line):

```
 1. [ 55/100] Praia da Armação       (7.9km away)  best at Sun 6 Sep, 00:00  |  19.3°C sea, 19km/h wind  |  jellyfish: Low  |  choppy (0.7m waves), breezy (19km/h), cold water (19.3°C)
```

Proposed. The ranked list gains one column; the top pick gets a block; the reply ends with lore:

```
 1. [ 55/100] Praia da Joaquina      (4.6km)  Sun 6 Sep, 00:00  |  water: PRÓPRIA (1/1 pts, 25 Aug)  |  19.0°C, 20km/h, 0.9m  |  jellyfish: Low
 2. [ 35/100] Praia do Campeche      (2.1km)  Sun 6 Sep, 00:00  |  water: 4/5 PRÓPRIA — avoid Riozinho mouth (25 Aug)  |  18.9°C, 20km/h, 1.0m  |  jellyfish: Low
 3. [ 55/100] Praia do Rio Tavares   (2.1km)  Sun 6 Sep, 00:00  |  water: no data  |  ...

Top pick — Praia da Joaquina, Sun 6 Sep 00:00-01:00
  Water quality   PRÓPRIA at Ponto 33 (Av. Pref. Acácio Garibaldi São Thiago, 1416), sampled 25 Aug,
                  10 enterococci/100mL, no rain, water 16°C. Source: IMA/SC bulletin 43, 2026-09-03.
  Sea             19.0°C, waves 0.9m every 9s from the SE (swell 0.7m), current 0.4 km/h
  Tide            low 02:10 (-0.3m), high 08:40 (+0.9m) — tomorrow morning is a rising tide
  Air             18°C, wind 20 km/h from the S, UV 0 at this hour, 5% chance of rain
  Jellyfish       Low — cold water and wind, none of the four warm-calm signals
  Whales          Low at this hour (dark); 06:00-10:00 rate High — humpback season

Draft summary: ...
Reviewer (score 80/100, verdict: approve): ...

Did you know? The "caravela-portuguesa" that strands on Santa Catarina beaches in winter isn't a
jellyfish at all — it's a colony of four kinds of animal living as one, and its sting stays active
for hours after it's dead on the sand. Never touch one, even dry. [source: <url>]
```

Telegram (Phase 1) gets the same block, the lore paragraph last, under a `🌊` line.

## 4. Data sources and dependencies reviewed

### 4.1 IMA/SC balneabilidade feed — **the pick**

- **What:** Instituto do Meio Ambiente de Santa Catarina's bathing-water programme, 260 sampling
  points across 28 coastal municipalities, classified PRÓPRIO/IMPRÓPRIO per CONAMA 274/2000
  (enterococci ≤ 100/100mL in 80% of the last five samples).
- **Format, verified 2026-09-05:** `POST https://balneabilidade.ima.sc.gov.br/relatorio/mapa`
  with no body returns a JSON array of every point: `CODIGO`, `MUNICIPIO`, `MUNICIPIO_COD_IBGE`,
  `BALNEARIO`, `PONTO_NOME` ("Ponto 89"), `LOCALIZACAO` (street-level), `LATITUDE`, `LONGITUDE`
  (strings), and `ANALISES` — the last five samples, each `DATA` (dd/mm/yyyy), `CONDICAO`
  (`PRÓPRIO`/`IMPRÓPRIO`), `CHUVA` (Ausente/Fraca/Intensa), `RESULTADO` (enterococci/100mL),
  `TEMP_AGUA`. 207KB, 1.2s, no key, no auth, no cookie. This is the endpoint the portal's own
  OpenLayers map calls; it is not documented anywhere. Sibling endpoints (`municipio/getMunicipios`,
  `local/getLocaisByMunicipio`, `pontoColeta/getPontosByLocal`, `registro/anosAnalisados`) also
  answer, but `relatorio/mapa` alone gives everything marola needs in one call.
- **Cadence, from bulletin 43's own "Considerações técnicas":** weekly in season; from April to
  September only Balneário Camboriú's central beach is weekly (court order) and the full 260 points
  are sampled **once a month, in the last week**. So off-season a result can be up to ~5 weeks old.
- **Coverage near the author:** five points on Praia do Campeche, 1.0-3.3km from the origin used in
  `RUN-LOCALLY.md`; Joaquina, Mole, Armação, Morro das Pedras, Pântano do Sul, Matadeiro all have
  points. Lagoa da Conceição (a lagoon, not sea) has eight, several IMPRÓPRIO — relevant because
  Overpass tags some lagoon shores `natural=beach`.
- **Terms:** public agency, public data, no terms page found on the portal. Treat as: one request
  per run, identify with marola's `User-Agent`, cache, degrade to "no data" on any failure.
- **Not verified:** whether the endpoint is rate-limited; how it behaves during the summer weekly
  cadence (only the off-season shape was observed); whether `CONDICAO` ever takes the third value
  "Indeterminado" the portal's legend mentions (only PRÓPRIO/IMPRÓPRIO seen in the 2026-09-05 feed).
- **Also verified as the fallback:** the weekly PDF bulletins
  (`/relatorio/downloadPDF/YYYY-MM-DD`, 18 pages) carry the same rows minus coordinates and
  counts. If the JSON endpoint disappears, this is the parse target — and the one place an Azure
  service (Document Intelligence) would genuinely earn its place. Not needed today.

### 4.2 Open-Meteo Marine API — already used; three variables not yet fetched

Verified 2026-09-05 against the docs: `wave_period`, `wave_direction`, `swell_wave_height`/
`swell_wave_period`/`swell_wave_direction`, `ocean_current_direction`, and **`sea_level_height`
(tides, above global mean)** are all available hourly at the same endpoint `OpenMeteoClient`
already calls. **No water-quality, turbidity or chlorophyll variables exist** — Open-Meteo cannot
replace §4.1. Adding the wave/tide fields is free (query-string change) and is what the detailed
block in §3 uses. `FUTURE-WORK.md` §1.3 wanted the same fields for surf scoring.

### 4.3 Reviewed and not picked

- **Copernicus Marine Service** (chlorophyll/turbidity layers): global, free registration, but
  gridded NetCDF and a proxy for *clarity*, not sanitary fitness. `FUTURE-WORK.md` §1.4 already
  scopes it as a "next-tier integration" for dive visibility. Not a substitute for IMA.
- **Other Brazilian states** (INEA/RJ, CETESB/SP): same CONAMA classification, each its own portal
  and format. Out of scope here; the trait in §5 is shaped so each becomes one more
  `WaterQualityClient` without touching `Recommender`.
- **Crowd reports:** `SightingStore` exists. Adding `SightingKind.Pollution` is a five-line
  complement (people see oil, foam, sewage before any bulletin does), included in §5 because it's
  nearly free — but it is *not* a data source for the score.

### 4.4 Sea lore — no third party; a curated, sourced file in the repo

No API serves "interesting true facts about the sea". The honest way to ship this without an LLM
inventing marine biology is a hand-curated resource with a source URL per entry, shown verbatim.
Seed list and selection rules in §5.4.

## 5. Design

### 5.1 New trait and model (`core/`)

```scala
// core/src/main/scala/marola/water/WaterQuality.scala
enum BathingCondition derives CanEqual:
  case Proper, Improper, Unknown

final case class SamplingPoint(
    id: String, beachName: String, pointName: String, location: String,
    coordinates: Coordinates, latest: Option[WaterSample])

final case class WaterSample(
    sampledOn: LocalDate, condition: BathingCondition, rain: Option[String],
    enterococciPer100ml: Option[Int], waterTempC: Option[Double])

/** Per-beach verdict: every sampling point matched to the beach, worst-first. */
final case class WaterQuality(
    points: List[SamplingPoint], source: String, bulletinDate: Option[LocalDate]):
  def properCount: Int; def improperPoints: List[SamplingPoint]; def isStale(today: LocalDate): Boolean

trait WaterQualityClient:
  /** All points in the provider's region — one call per run, cached in memory for that run. */
  def samplingPoints: List[SamplingPoint] < Sync
```

Matching (pure, in `water/WaterQualityMatcher`): a point belongs to a beach if its normalised
`BALNEARIO` (accents stripped, "PRAIA DO/DA/DE" dropped, case-folded) equals the normalised OSM
name, **or** it lies within 2.5km of the beach centroid and no other beach's name matches it. Name
match wins; distance is the fallback for OSM/IMA naming drift ("Praia da Armação" vs "PRAIA DA
ARMAÇÃO DO PÂNTANO DO SUL"). Unit-tested against a checked-in fixture trimmed from the real feed.

### 5.2 Providers

- **`local/`: `ImaScWaterQualityClient`** — `Http.postForm(mapaUrl, Map.empty)` (the endpoint
  takes an empty POST), `JsonValue` parse, strings → numbers with `toDoubleOption`/`toIntOption`,
  dates via `DateTimeFormatter.ofPattern("dd/MM/yyyy")`. A parse failure of one point drops that
  point, not the feed. Zero new dependencies.
- **Azure opt-in:** none. There is no Azure water-quality service, and this MIP does not invent a
  use for one. Stated per the `mip` skill rule. (If the JSON endpoint vanishes, Document
  Intelligence over the PDF bulletin is the natural Azure-flagged fallback — §4.1.)
- **Selection (`AppConfig`)**: `MAROLA_WATER_QUALITY_PROVIDER=ima-sc|none`, default `ima-sc` **only
  when the origin lies inside Santa Catarina's bounding box** (lat −29.4…−25.9, lon −53.9…−48.3),
  otherwise `none` with the note "no water-quality source for this region yet (MIP-0001 §11)". A
  future `inea-rj` slots in the same way.

### 5.3 Wiring

- `Recommender.bestPerBeachTomorrow` gains `waterQuality: Option[WaterQualityClient]`; it fetches
  the points once, runs the matcher per beach, and passes the per-beach `WaterQuality` into scoring.
  `BestHour` gains `waterQuality: Option[WaterQuality]`.
- `OpenMeteoClient` fetches `wave_period`, `wave_direction`, `swell_wave_height`,
  `swell_wave_period`, `sea_level_height`; `HourlyConditions` gains the matching `Option[Double]`s.
  Tide "low/high" times for the block are the local minima/maxima of `sea_level_height` over
  tomorrow's 24 hours — no tide-table API needed.
- `Main`: the column and the detailed block in §3; `--brief` keeps today's one-line format.
- MCP: `get_swim_recommendation` output gains a `water_quality` object per beach; new tool
  `get_water_quality(lat, lon, radius_km)` returning matched points — useful to an agent on its own.
- `SightingKind.Pollution` added; `--report-sighting pollution <beach> [note]` just works.

### 5.4 Sea lore

`core/src/main/resources/sea_lore.json`: a list of entries
`{ id, kind: "secret" | "creature", text, source, region: ["BR-S", "global"], months: [..] | null }`.
Selection is pure (`lore/SeaLore.pick(today, beachName, regionTags)`): filter by region and month,
then choose with a seeded shuffle (`seed = today.toEpochDay * 31 + beachName.hashCode`) so the same
beach shows the same entry all day and a different one tomorrow, and neighbouring beaches differ.
The text is appended **verbatim** — it never passes through the LLM, so it can't be paraphrased into
something the source doesn't say. `Reviewer` receives it as a `lore` input field only so it can
flag a summary that contradicts it.

Seed entries (sources to be attached and checked one by one before merge — that check is part of
the implementation PR, not assumed here):

- *creature* — caravela-portuguesa (*Physalia physalis*) is a siphonophore colony, not a jellyfish;
  strands on SC beaches in winter/spring with onshore winds; sting active after death. Region BR-S,
  months 6-10.
- *creature* — baleia-franca (*Eubalaena australis*) calves in the shallows off Santa Catarina's
  south coast June-November; the APA da Baleia Franca protects the stretch from Florianópolis to
  Balneário Rincão. Region BR-S, months 6-11.
- *creature* — humpbacks (baleia-jubarte) migrate past the island July-November — the same fact
  `Swimability.whaleSightingLikelihood` already uses. Region BR-S, months 7-11.
- *creature* — green turtles (*Chelonia mydas*) graze year-round on the island's rocky points.
  Region BR-S.
- *secret* — sea foam is whipped-up dissolved organic matter (algal proteins, lipids), not
  pollution by itself — but persistent brown foam at a stream mouth is worth reporting. Global.
- *secret* — summer water in Santa Catarina can be *colder* than winter's on a NE-wind day:
  wind-driven upwelling brings South Atlantic Central Water to the surface. Region BR-S, months 11-3.
- *secret* — the ocean has absorbed roughly a quarter of the CO₂ humans emitted, and it is
  measurably more acidic for it. Global.
- *secret* — most of the light in the deep sea is made by animals: bioluminescence is the norm
  below 200m, not the exception. Global.

Eight is enough to rotate for a week without repeats; the file is meant to grow. Every entry needs
a URL a human checked. An entry without one does not ship.

## 6. Scoring / safety impact

Deterministic, in `Swimability`, alongside the existing deltas:

| Matched points for the beach | Delta | Note |
|---|---|---|
| ≥ 1 point, **all** IMPRÓPRIO (fresh) | score forced to **0** | `water unfit for bathing — IMA <bulletin date>, Ponto NN (<location>), <n> enterococci/100mL` |
| mixed | **−20** | `<k>/<n> points PRÓPRIA — avoid <location of each IMPRÓPRIO point>` |
| all PRÓPRIO (fresh) | 0 | `water PRÓPRIA (<n> pts, <sample date>)` |
| no match, or provider `none` | 0 | `no water quality data` (no penalty — absence of data is not evidence of pollution) |
| any match but latest sample older than **45 days** | treated as *no match* | `water quality data stale (<date>)` |

45 days covers the off-season monthly cadence (§4.1) with margin; in season it will never trigger.
The veto uses IMA's own `CONDICAO`, which already encodes CONAMA 274's five-sample rule; marola
does not re-derive it from `RESULTADO`. `RESULTADO` is shown, not scored.

## 7. Verification plan

Unit tests (all pure, no network):

- `WaterQualityMatcherSpec`: fixture of the 12 Florianópolis points nearest Campeche from the real
  2026-09-05 feed (checked in, trimmed); asserts Campeche gets its five points and none of Lagoa da
  Conceição's, Armação matches "PRAIA DA ARMAÇÃO DO PÂNTANO DO SUL" by name, a beach with no
  points gets `None`.
- `ImaScWaterQualityClientSpec`: parses the fixture JSON; a point with a malformed date is dropped,
  the rest survive.
- `SwimabilitySpec` additions: the four rows of §6, including the 45-day staleness boundary.
- `SeaLoreSpec`: same day + beach → same entry; next day → different entry; month filter excludes
  out-of-season creatures; every entry has a non-empty `source`.
- `OpenMeteoClientSpec` (new, first test for that module): tide extrema from a fixture series.

Live checks (`just run`, `just e2e`):

- From Campeche: Ponto 73 appears as the IMPRÓPRIO note, score drops by 20, block shows tide
  times that agree with the Marinha do Brasil tide table for Florianópolis within ±30 min.
- From Rio (`--lat -22.9878 --lon -43.1913`): provider auto-selects `none`, output says so, nothing
  else changes.
- MCP: `get_water_quality` over stdio returns the five Campeche points.

Done means: all of the above green, `ARCHITECTURE.md` §5 table gains row 5g, `RUN-LOCALLY.md`'s
expected output replaced with a run that shows the block, this MIP flipped to Implemented.

## 8. Risks, limitations, and honest caveats

- **Undocumented endpoint.** It can change or vanish without notice. Mitigation: everything
  degrades to `Unknown`/"no data"; the PDF path is the documented fallback; a checked-in fixture
  keeps the parser tested even if the live feed breaks.
- **Off-season staleness.** A monthly sample says little about today after a storm. The block
  shows the sample date and the `CHUVA` flag; the 45-day rule turns very old data off entirely.
  This must be printed, not hidden.
- **A point is not a beach.** Ponto 73 is a stream mouth; 300m away the water is fine. The mixed
  rule (−20 + named locations) exists precisely so one bad point doesn't erase a 4km beach, and so
  the reader knows *where* not to swim. Full veto only when every point agrees.
- **Lagoon shores tagged as beaches.** Lagoa da Conceição's IMPRÓPRIO points will match "beaches"
  Overpass returns on the lagoon. That's correct behaviour — those are the spots people actually
  swim — but the name-vs-distance matcher must not attach lagoon points to sea beaches.
- **Lore correctness is a human job.** The seeded shuffle and verbatim display are the technical
  guard; the source check is the real one. No entry without a checked URL.
- **Regional.** SC only, by design; everywhere else the feature is invisible except for one note.

## 9. Alternatives considered

- **Do nothing.** Leaves the Ponto 73 case unaddressed; rejected on safety grounds.
- **Parse the PDF bulletins.** Works, no coordinates, brittle layout, needs a PDF library or an
  Azure service. Kept as the fallback only.
- **Let the LLM write the lore.** Cheapest to build, and exactly what §9.1 and the `mip` skill
  forbid: unsourced marine "facts" reaching users. Rejected.
- **Score `RESULTADO` directly** (e.g. penalise 100-800 enterococci as "borderline"). Tempting, but
  it second-guesses the agency's own five-sample classification with a single number. Rejected;
  show the number, score the classification.

## 10. Exam-coverage mapping

- `AI-103-MAPPING.md` §1 "Responsible AI: transparency" — a safety-relevant signal handled
  deterministically with the sample date and location printed; a concrete artefact for that row.
- `AI-103-MAPPING.md` §5 "Text analysis" partial gap — the lore corpus is the seed of the
  marine-knowledge corpus `FUTURE-WORK.md` §9.1 proposes for RAG; not RAG yet, and this MIP does
  not claim it is.
- Document Intelligence: *not* used, and this MIP records why (a JSON feed exists). If the fallback
  is ever needed, that row becomes real.
- AI-500: none.

## 11. Open questions

1. **Mixed-beach penalty size.** −20 is a guess at "one bad stream mouth on a good beach"; is a
   flat −20 right, or should it scale with the share of IMPRÓPRIO points?
2. **Auto-select by bounding box vs. always-on.** Bounding-box selection is invisible magic; an
   explicit `MAROLA_WATER_QUALITY_PROVIDER=ima-sc` default with a clear "not your region" note may
   be more honest. Decide before implementation.
3. **Cache the feed to disk?** 207KB, weekly-changing data, fetched on every run today. A
   `./data/` cache with a 24h TTL is trivial but is the first on-disk cache in the pipeline
   (Phase 4 territory). Proposal: in-run cache only for now; disk cache as a follow-up.
4. **Lore language.** Replies are English today; the lore about *this* coast reads more naturally
   in Portuguese. Ship English entries first, add `lang` to the schema now so Portuguese can follow
   without a migration.
5. **Contact IMA?** Using an undocumented endpoint politely is defensible; asking whether they mind
   is better. Worth one email before this goes into a public bot (Phase 1).

## Appendix

Real feed excerpt (2026-09-05), the five Campeche points nearest the `RUN-LOCALLY.md` origin,
distance from origin, latest sample:

```
 1.0km PRAIA DO CAMPECHE (Ponto 89) Av. Jerônimo Venâncio Chagas, 113   25/08/2026 PRÓPRIO   ent=10   chuva=Ausente T=16
 1.8km PRAIA DO CAMPECHE (Ponto 75) Av. Campeche, 300, no mar            25/08/2026 PRÓPRIO   ent=10   chuva=Ausente T=16
 1.8km PRAIA DO CAMPECHE (Ponto 73) Av. Campeche, 300, no Riozinho       25/08/2026 IMPRÓPRIO ent=749  chuva=Ausente T=16
 2.2km PRAIA DO CAMPECHE (Ponto 35) Av. Pequeno Príncipe, 3348           25/08/2026 PRÓPRIO   ent=10   chuva=Ausente T=16
 3.3km PRAIA DO CAMPECHE (Ponto 90) Rua Campos Limpos, 281               25/08/2026 PRÓPRIO   ent=10   chuva=Ausente T=16
 5.3km LAGOA DA CONCEIÇÃO (Ponto 72) Rua Canto da Amizade                25/08/2026 IMPRÓPRIO ent=573  chuva=Ausente T=16
```

One raw point, verbatim:

```json
{"CODIGO":"415","MUNICIPIO_COD_IBGE":"4205407","MUNICIPIO":"FLORIANÓPOLIS","PONTO_NOME":"Ponto 98",
 "BALNEARIO":"PRAIA DOS INGLESES","LOCALIZACAO":"Rua das Gaivotas, n°1380, na foz do Rio Capivari",
 "LATITUDE":"-27.4261029","LONGITUDE":"-48.3996518",
 "ANALISES":[{"DATA":"24/08/2026","CONDICAO":"IMPRÓPRIO","CHUVA":"Ausente","RESULTADO":"197","TEMP_AGUA":"16"},
             {"DATA":"27/07/2026","CONDICAO":"IMPRÓPRIO","CHUVA":"Ausente","RESULTADO":"2098","TEMP_AGUA":"22"}, ...]}
```

Feed-wide, 2026-09-05: 260 points, 28 municipalities, `ANALISES` holds the last 5 samples (monthly
dates 28/04 → 25/08 off-season), `CONDICAO` ∈ {PRÓPRIO, IMPRÓPRIO}. Bulletin PDFs:
`https://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/2026-09-03` (18 pages, no coordinates).
Open-Meteo Marine variable list checked at `https://open-meteo.com/en/docs/marine-weather-api`.
