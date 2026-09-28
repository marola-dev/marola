# MIP-0038: Model spread — when the wave models disagree, marola says so instead of picking one

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Opus 5, for M. Hoffmann (deep review of every MIP + a live LLMOps/competitor research pass, 2026-09-07) |
| **Created** | 2026-09-07 |
| **Phase** | 0 (CLI notes and the board JSON) → 1 (the map card and the bot reply reuse the same field). No earlier-phase prerequisite is missing |
| **Related** | `ARCHITECTURE.md` §9 ("nearby beaches often show near-identical numbers" — the *other* honest limitation of one forecast cell) and §5 (the pluggable pattern this does **not** need, since it's the same upstream); MIP-0001 (the detailed block this adds one line to); MIP-0005/MIP-0009 (the board JSON and the hover tooltip that render it); MIP-0007 (foundation models over marola's *own* series — explicitly not a sea forecast, so this MIP is not its overlap); `ROADMAP.md` §7 K5 (forecast verification — the parked work that would eventually say *which* model to trust, and the reason this MIP refuses to pick one today) |
| **Effort** | M — one query-string change in `OpenMeteoClient` (no extra HTTP request, §4.1), one pure `ModelSpread` module in `core/conditions`, two additive board fields, one CLI line and one tooltip row. No new dependency, no new module, no new upstream |
| **Gain** | `user value` (the swimmer learns that "1.3 m" is one model's answer and another says 2.1 m, at the hour they were about to swim) |
| **Effort vs Gain** | `do next` — the data is one query parameter away in the request marola already makes, and §2's disagreement is measured, not hypothesised |
| **Depends on** | Nothing blocking. No Phase 1 gate, no paid resource, no new API key. Coordinates with MIP-0009 (the hover tooltip gains one row) and MIP-0016 (same `render()`); neither must land first |
| **Blocked by** | none |
| **Risk** | Replacing one honest number with four confusing ones. A swimmer wants a decision, not an ensemble; if the confidence label reads as hedging, the page gets less useful, not more. §5's "one word, never four numbers" rule and §8 exist for exactly this |
| **Cost so far** | — |

## 1. Summary

marola shows one wave height per beach-hour, from Open-Meteo's `best_match` model, and never says
that other wave models disagree, sometimes by enough to move the hour across `Swimability`'s own
`rough seas` threshold. This MIP adds `models=` to the marine request already being made (**the
same single HTTP call**, §4.1), computes a deterministic **spread** across the models that answered,
and shows one word (`agreed` / `mixed` / `split`) next to the wave figure, with the per-model
numbers in the detailed block. **The score does not change**: `best_match` stays the number
`Swimability` scores, and the spread is a note beside it, never a new input to the veto.

## 2. Motivation

Verified live on 2026-09-07 against the Marine API (Appendix, "Checked live"), at Praia da Joaquina
(`-27.6283, -48.4494`), the coordinates marola's own Florianópolis area already covers:

```
2026-09-08T09:00   gwam 1.28 m   meteofrance_wave 0.66 m   ecmwf_wam025 null   ncep_gfswave025 0.0
2026-09-07T00:00   gwam 2.06 m   meteofrance_wave 1.16 m        ← 0.90 m apart, the run's widest
```

Those two hours are not cherry-picked: across the 48 forecast hours returned in that one request,
the two models that answered land in **different `Swimability` score bands on 11 of them**. One
model says `rough seas ($h%.1fm waves)` (−40, `Swimability.scala:87`), the other says
`choppy` (−15, `:88`). That is a 25-point swing in a 0–100 score, on 23% of the hours, and marola's
output today contains no trace of it. The same 0.6 m threshold is also one of the four signals
`jellyfishRisk` counts (`:58`), so the model choice can flip a hazard label too.

marola's existing honesty about forecasts is about *resolution*: `ARCHITECTURE.md` §9, "beaches a
few km apart genuinely get the same or near-same forecast cell". This is the complementary
limitation nothing in the repo records: the *same* cell has several models behind it and they do
not agree. Windy's most-used feature is showing ECMWF/GFS/ICON side by side and letting the reader
judge (§4.3); a product whose whole positioning is "sourced or clearly labelled, never invented"
should not be the one that hides the disagreement.

## 3. User-visible change

CLI, the MIP-0001 detailed block gains one line, and the ranked row gains one word:

```
 1. [ 55/100] Praia da Joaquina      (4.6km)  Tue 8 Sep, 09:00  |  water: PRÓPRIA (1/1 pts, 25 Aug)
              19.0°C, 20km/h, 1.3m (split)  |  jellyfish: Low

Top pick — Praia da Joaquina, Tue 8 Sep 09:00-10:00
  Sea             19.0°C, waves 1.3m every 9s from the SE (swell 0.7m), current 0.4 km/h
  Model spread    split — the wave models disagree across a scoring threshold at this hour
                  GWAM 1.3m · MeteoFrance Wave 0.7m · (ECMWF WAM 0.25°: no data here)
                  Scored on Open-Meteo's best_match (1.3m). Source: Open-Meteo Marine API.
```

Board JSON (`site/board.schema.json`, both fields optional, an old board still renders):

```json
"sea": { "wave_m": 1.3, "wave_spread": "split",
         "wave_models": [{"model": "gwam", "wave_m": 1.28},
                         {"model": "meteofrance_wave", "wave_m": 0.66}] }
```

Map (MIP-0009's tooltip): one extra row, `〰️ waves 1.3 m · models split`, and the same block inside
the card. When every model that answered agrees (`agreed`), **nothing new is shown anywhere**. The
line only appears when it carries information. `--brief` is unchanged.

## 4. Data sources and dependencies reviewed

### 4.1 Open-Meteo Marine API `models=` — **the pick**, and it costs zero extra requests

- **What:** the same endpoint `OpenMeteoClient` already calls
  (`https://marine-api.open-meteo.com/v1/marine`, `OpenMeteoClient.scala:33`) accepts a
  comma-separated `models=` parameter and returns one suffixed column per model in the *same*
  response: `wave_height_gwam`, `wave_height_meteofrance_wave`, and so on.
- **Verified live 2026-09-07** (Appendix): a single request with
  `models=ecmwf_wam025,gwam,ncep_gfswave025` returned `hourly_units` keyed
  `wave_height_ecmwf_wam025` / `wave_height_gwam` / `wave_height_ncep_gfswave025`. This matters more
  than it sounds: marola's site build is ~2 calls × 80 beaches (`OpenMeteoClient.scala:30`'s own
  comment), and **this change adds no call at all**: only columns to a request already in flight.
  Against the free tier's documented ceilings (fetched 2026-09-07: "600 calls / min", "5.000 calls /
  hour", "10.000 calls / day", CC-BY 4.0, non-commercial only, no key), the call budget is untouched.
- **Models the docs list** for this endpoint (fetched 2026-09-07): `best_match`, MeteoFrance Wave,
  MeteoFrance Ocean Currents, DWD EWAM, DWD GWAM, ECMWF WAM, ECMWF WAM 0.25, GFS Wave 0.25°,
  GFS Wave 0.16°, ERA5-Ocean. The docs describe `best_match` as "the best forecast for any given
  location worldwide" but **do not document the `models=` parameter for this endpoint in prose**.
  The live probe is what confirms it, and that is stated here rather than glossed.
- **Two traps found by the same probe, and the design turns on them:**
  1. `ecmwf_wam025` returned **`null`** for every hour at both Florianópolis points. The model's
     grid does not resolve these coastal cells. A null is *absence*, not agreement.
  2. `ncep_gfswave025` returned **`0.0` for every hour** at both points, a land-masked cell, not a
     forecast of a flat sea. **A constant 0.0 must never be counted as a model opinion**; §5's
     filter drops it, and §8 states why that filter is itself a guess.
  3. `ewam` was requested at Joaquina and came back with no column at all. DWD's EWAM is a European
     regional model. A requested model that does not answer is normal, not an error.
- **Terms:** unchanged from what marola already relies on (`ARCHITECTURE.md` §7): same endpoint,
  same free non-commercial CC-BY 4.0 terms, still no key.

### 4.2 Open-Meteo Ensemble API — reviewed, **not** the pick

Fetched 2026-09-07: `https://ensemble-api.open-meteo.com/v1/ensemble`, no key, free for
non-commercial use, 14 ensemble systems (ICON EPS 40 members, ECMWF IFS 0.25° 51, GFS Ensemble 31,
Google WeatherNext 2 64, …). It is the textbook way to get a probabilistic forecast, and its
variable list is atmospheric only: **marine/wave variables are not available at that endpoint**
(stated on the page, confirmed 2026-09-07). It could give a *wind* ensemble, which is a second,
separate feature; waves are the field that actually moves marola's score, so the multi-model
approach in §4.1 is what this MIP builds. Named here so a later MIP doesn't re-derive the finding.

### 4.3 What the competition does with the same problem — reviewed, not copied

- **Windy.com** puts ECMWF, GFS, ICON and others side by side and lets the reader compare; it is one
  of its most-cited features. Its own *Point Forecast API* sells this per-model access at €990/year
  for 10,000 requests/day, and its free "Testing" tier "returns randomly shuffled and slightly
  modified data" (both fetched 2026-09-07 from `api.windy.com/point-forecast/pricing`). marola gets
  the same multi-model signal from Open-Meteo for free, without a key.
- **Windy makes the reader do the comparison.** marola's whole shape is the opposite, a
  deterministic score and one sentence. So this MIP does *not* copy the layered-models UI; it
  reduces the comparison to one deterministic word and keeps the numbers one level down. That is the
  actual design decision here, and §9 records the rejected alternative.
- **Safeswim (NZ)** and **NSW Beachwatch**, the two services that genuinely forecast *water*
  quality rather than reporting a sample, both publish a modelled prediction with an accuracy claim
  attached. Neither is a wave forecaster, and neither is copyable here (their models run on
  agency-held rainfall and sewer telemetry). Noted as the standard of honesty about a modelled
  number, not as a data source.

**Pick:** §4.1, with `models=gwam,meteofrance_wave,ecmwf_wam025,ncep_gfswave025` as the default set
and `MAROLA_WAVE_MODELS` to override, plus the `best_match` column exactly as fetched today.

## 5. Design

**`core/conditions/ModelSpread.scala`: pure, deterministic, unit-tested.**

```scala
enum SpreadLevel derives CanEqual:
  case Agreed    // every answering model lands in the same Swimability wave band
  case Mixed     // same band, but the widest pair differs by ≥ MixedSpreadM
  case Split     // the answering models straddle a band boundary (0.6 m or 1.5 m)
  case Single    // fewer than two models answered — nothing to compare, say nothing

final case class ModelReading(model: String, waveHeightM: Double)

object ModelSpread:
  private val MixedSpreadM = 0.3

  /** Drops nulls, and drops any model whose whole series is a constant 0.0 (a land-masked cell —
    * §4.1 trap 2). Bands come from `Swimability`, never re-derived here. */
  def of(readings: List[ModelReading]): SpreadLevel
  def label(level: SpreadLevel): Option[String]   // None for Agreed/Single — nothing is printed
```

The band boundaries are **not** copied into this file. `Swimability.windLevel`'s precedent
(MIP-0009 §5: "computed in Scala so the page never re-implements a scoring threshold") applies
verbatim: `Swimability` exposes a `waveBand(h: Double): WaveBand` that `waveDelta` itself calls, so
one threshold serves the score, the note and the spread. A `SwimabilitySpec` case proves band and
delta agree, exactly as MIP-0009 did for wind.

**`core/conditions/OpenMeteoClient.scala`**: the marine query string gains `&models=…` (the
`best_match` values keep arriving as the unsuffixed columns; the per-model ones arrive alongside).
`merge` reads the suffixed columns into a new `HourlyConditions.waveByModel: List[ModelReading]`
(empty when the models are not requested). Nothing else in the merge changes. The land-mask filter
is applied once per beach over the whole series, not per hour: a single 0.0 at slack water is real,
a series of 48 of them is a mask.

**`core/site/Board.scala`**: `sea` gains optional `wave_spread` and `wave_models`; schema stays
version 1 (additive and optional, MIP-0009 §5's precedent). `Report` prints the §3 block.
`site/static/app.js` renders the tooltip row and the card block; absence of the fields renders
exactly today's page.

**MCP:** `get_swim_recommendation`'s per-beach object gains the same two fields, so an agent client
sees the disagreement rather than a bare number.

**What goes through the LLM: nothing.** The spread word is an enum label. The summariser's
`factInputs` (`Main.factInputsFor`) is **deliberately left unchanged in v1**. Adding a spread field
there would invite the model to editorialise about uncertainty, which is exactly the class of claim
MIP-0039 exists to catch. The block in §3 is rendered by `Report`, after the model, like MIP-0022's
footer.

## 6. Scoring / safety impact

**`Swimability.score` is unchanged, deliberately and by rule.** The score, the notes, the water
veto and the jellyfish signals keep reading `best_match`'s `waveHeightM` exactly as they do today:
the same field, the same thresholds, the same numbers. Nothing in this MIP can raise or lower a
score.

The one structural change is a refactor with no behaviour change: `waveDelta`'s two thresholds move
behind a `Swimability.waveBand` the delta itself calls, so the spread computation cannot drift from
the scoring. `SwimabilitySpec` gains the boundary cases (just below 0.6, at 0.6, just below 1.5, at
1.5) proving band and delta still agree.

Promotion of the spread to a *scoring* input, a conservative "score the roughest model" rule,
is explicitly **out of scope and needs its own MIP**, on the same reasoning MIP-0007 §6 uses for its
estimates: marola has no evidence which model is right for this coast, and picking the pessimistic
one would silently lower every score on 23% of hours with nothing to justify it. `ROADMAP.md` §7 K5
(forecast verification against observations, parked) is the work that would earn that decision.

## 7. Verification plan

- `ModelSpreadSpec` (pure): `Agreed` when two models sit in one band; `Split` when they straddle
  0.6 and again when they straddle 1.5; `Mixed` at a 0.35 m gap inside one band and `Agreed` at
  0.2 m; `Single` for one reading and for zero; a model whose 48-hour series is all `0.0` is dropped
  while a model with one `0.0` hour among real values is kept; nulls never count as agreement.
- `SwimabilitySpec` (extend): `waveBand` boundary cases, and band-vs-`waveDelta` agreement.
- `OpenMeteoClientSpec` (extend): a checked-in fixture trimmed from the **real** 2026-09-07 Joaquina
  response (the one in §2, nulls and the constant-0.0 column included) parses into
  `waveByModel` with two live readings, one dropped mask and one dropped null.
- `BoardSpec` (extend): the two new fields round-trip; a board without them still validates against
  `site/board.schema.json`.
- `scripts/site_check.js` (extend, MIP-0009 task 2's harness): the tooltip shows the spread row for
  a `split` fixture beach and shows **no** row for an `agreed` one.
- Live: `just run -- --lat -27.6283 --lon -48.4494` prints the §3 block; `just site-build floripa &&
  just site-serve` shows the row on the hours §2 named. Re-run `just e2e`.
- **Done** = the above green, `ARCHITECTURE.md` §9 gains the multi-model limitation next to the
  grid-resolution one, and one real board on `marola.dev` shows a `split` hour.

## 8. Risks, limitations, and honest caveats

- **A hedge is not information.** Four numbers where there was one makes the page worse. The
  mitigations are structural: one word on the ranked row, the numbers only in the detailed block,
  and **nothing at all** when the models agree, which, on the 2026-09-07 run, was 37 of 48 hours.
- **The land-mask filter is a heuristic about a heuristic.** "A whole series of 0.0 means a masked
  cell" is inferred from two points on one coast on one day, not from Open-Meteo documentation.
  A model that genuinely forecasts a flat 48 hours would be dropped. Stated in the code comment and
  here; the alternative (trusting 0.0) is strictly worse, since it would report `agreed` between a
  real 1.3 m and a fake 0.0.
- **Disagreement is not the same as error.** A wide spread says the models differ, not that the sea
  will be rough. The wording says "the models disagree", never "the forecast is unreliable", and
  never assigns a probability marola has not measured.
- **Which model is right is unknown, and this MIP does not pretend otherwise.** GWAM ran high at
  both points on both days probed; that is two points and two days, not a bias measurement.
- **Coverage is uneven by design.** ECMWF WAM 0.25° answers nowhere near these beaches and EWAM is
  Europe-only, so on this coast the "ensemble" is realistically two models. Where only one answers,
  `Single` shows nothing, and the page looks exactly as it does today, the honest outcome.
- **`best_match` may itself be one of the compared models.** At Campeche on 2026-09-07 `best_match`
  returned 0.80 m, equal to MeteoFrance Wave's 0.80 m for that hour, so the "spread" is partly
  between the scored model and its rivals, not around it. Worth saying in the block's wording
  ("scored on best_match"), which §3 does.

## 9. Alternatives considered

- **Do nothing.** Keeps one clean number, and keeps a 25-point score swing invisible on 23% of
  hours. Lost on the repo's own "sourced or clearly labelled" rule.
- **Show every model as a layer, Windy-style.** Honest and free, and it turns a five-second read
  into a comparison exercise. Rejected: marola's product is the decision, not the data.
- **Average the models into one number.** Tempting and wrong. It invents a forecast no model made,
  and it would silently change every score. Rejected on §6's rule.
- **Score the roughest model (conservative).** Defensible for a safety product, and unjustifiable
  today: no evidence exists that the roughest model is the right one here, and it would lower scores
  everywhere. Deferred to a MIP that can cite K5 verification data.
- **Use the Ensemble API instead.** No marine variables (§4.2). It is the right tool for a *wind*
  confidence signal later, not for this.
- **A numeric confidence percentage** ("72% confident"). A number marola cannot compute honestly
  from two models with no verification history. Rejected in favour of three words.

## 11. Open questions

1. **The default model set.** `gwam,meteofrance_wave,ecmwf_wam025,ncep_gfswave025` is what was
   probed; `ncep_gfswave016` (the finer GFS Wave grid) was not tried and might resolve these coastal
   cells where the 0.25° one is masked. Probe it before fixing the default.
2. **`MixedSpreadM = 0.3`** is a guess at "far enough apart to mention inside one band". Tune it
   against a week of boards before merging, or drop `Mixed` entirely and ship only `Split`.
3. **Should the spread appear on the *hour slider* colours** (MIP-0009), e.g. a hatched marker for
   split hours, or only in text? Proposal: text only in v1; a second visual channel on the same
   markers risks the clutter MIP-0009 §8 already flags.
4. **Sea-surface temperature and wind spread.** The same `models=` mechanism returns per-model SST,
   and the Forecast API takes `models=` for wind. Both are score inputs too. Proposal: waves only in
   v1, one signal, one column, one word, and revisit once the wave version has been read by real
   users.
5. **Follow-up MIP: a normalised, openly licensed Brazilian bathing-water dataset.** Found while
   researching this MIP's competitor set, out of scope here, and genuinely undesigned: the only
   prior attempt at aggregating IMA/INEA/INEMA/CETESB balneabilidade into machine-readable form
   (`turicas/balneabilidade-brasil`, LGPL3 code / CC-BY-SA data, 4 stars, 24 commits) covers two
   agencies and appears stale, CETESB's own open-data catalogue has listed balneabilidade as
   "coming soon" since 2024, and INEMA suspended and only resumed publishing in March 2026.
   MIP-0031 builds the *ingestion* for RJ/BA and MIP-0034 §5.6 builds an *outbound Atom feed*;
   nothing yet proposes publishing the normalised, source-traceable dataset itself, including
   whether to adopt the existing `swimdrinkfish/opendata` exchange schema rather than inventing one.
   Needs the next free MIP number and its own provider verification pass.

## Appendix

### Checked live

All fetched or executed by the author on **2026-09-07**.

- `https://marine-api.open-meteo.com/v1/marine?latitude=-27.6893&longitude=-48.4776&hourly=wave_height&models=ecmwf_wam025,gwam,ncep_gfswave025&forecast_days=2&timezone=auto`
  → HTTP 200; `hourly_units` keyed `wave_height_ecmwf_wam025`, `wave_height_gwam`,
  `wave_height_ncep_gfswave025`. Confirms `models=` takes a comma-separated list and returns every
  model in **one** response.
- Same endpoint, Praia da Joaquina (`-27.6283, -48.4494`),
  `models=ecmwf_wam025,gwam,ewam,ncep_gfswave025,meteofrance_wave`, 48 hours →
  `ecmwf_wam025` all `null`; `ncep_gfswave025` all `0.0`; `ewam` returned no column at all;
  `gwam` and `meteofrance_wave` real. At `2026-09-08T09:00`: gwam **1.28**, meteofrance **0.66**.
  Widest pair `2026-09-07T00:00`: gwam **2.06**, meteofrance **1.16** (0.90 m apart). Counting hours
  where the two land in different `Swimability` wave bands (≥1.5 vs ≥0.6): **11 of 48**.
- Same endpoint, Praia do Campeche (`-27.6893, -48.4776`), **no** `models=` → `best_match`
  `wave_height` **0.80 m**, `sea_surface_temperature` **17.8 °C** at `2026-09-08T09:00`; with
  `models=` the same hour gives gwam **1.28**, meteofrance **0.80**.
- `https://open-meteo.com/en/docs/marine-weather-api` → model dropdown lists `best_match`,
  MeteoFrance Wave, MeteoFrance Ocean Currents, DWD EWAM, DWD GWAM, ECMWF WAM, ECMWF WAM 0.25,
  GFS Wave 0.25°, GFS Wave 0.16°, ERA5-Ocean; full hourly variable list; "No API key is required"
  for non-commercial use. **The page does not document `models=` in prose**. The probes above are
  the evidence.
- `https://open-meteo.com/en/terms` → free tier "600 calls / min", "5.000 calls / hour",
  "10.000 calls / day"; "You accept to the CC-BY 4.0 licence"; "You may only use the free API
  services for non-commercial purposes."
- `https://open-meteo.com/en/docs/ensemble-api` → `https://ensemble-api.open-meteo.com/v1/ensemble`,
  no key for non-commercial use, 14 ensemble systems with member counts (ECMWF IFS 0.25° 51,
  ICON EPS Seamless 40, GFS Ensemble 0.25° 31, Google WeatherNext 2 64, …); **"Marine/wave
  variables: Not available in this endpoint."**
- `https://api.windy.com/point-forecast/pricing` and `/docs` → free "Testing" tier 500 requests/day
  "returns randomly shuffled and slightly modified data"; Professional **€990/year**, 10,000
  requests/day; ECMWF excluded from the point-forecast API for licensing reasons; sea models
  offered: `gfsWave`, `iconWave`, `iconEuWave`, `canRdwpsWave`, `cmems`.
- Repo files read directly (not fetched): `core/src/main/scala/marola/conditions/OpenMeteoClient.scala`
  (lines 30, 33, 41-47, 54-104), `core/src/main/scala/marola/scoring/Swimability.scala`
  (lines 35-38, 58, 87-88), `docs/2-Building-marola/ARCHITECTURE.md` §9.

### Not checked

- `ncep_gfswave016`, `ecmwf_wam` (the coarser grid) and `era5_ocean` were never requested. The
  claim that a finer GFS grid *would* resolve these cells is a hypothesis (§11.1), not a finding.
- Whether Open-Meteo's `models=` behaviour on the marine endpoint is contractual or incidental: it
  is undocumented in prose there, so it could change without notice. The degradation path (no
  suffixed columns → `Single` → today's page) is designed for that, but the risk is not eliminated.
- Whether the `0.0` columns are truly land-masking. Inferred from the constant series, not confirmed
  by Open-Meteo docs or support.
- Windy.com's consumer subscription price and Windy.app's API price, reported by a research pass
  from search summaries, not fetched from a primary page, and therefore not cited in §4.3.
- Safeswim's and NSW Beachwatch's stated model accuracy figures, reported from search summaries
  only; §4.3 cites them for their *approach*, not for any number.
