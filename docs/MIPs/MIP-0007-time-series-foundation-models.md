# MIP-0007: Time-series foundation models for marola's own series — local open models first

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Fable 5.1, for M. Hoffmann; prompted by Nixtla's TimeGPT ("a game changer nobody talks about") |
| **Created** | 2026-09-05 |
| **Phase** | 4 (Harden & calibrate) — needs marola's own accumulated data first |
| **Related** | `ARCHITECTURE.md` §8 (calibrating the heuristics on real reports), MIP-0001 (water-quality cadence), MIP-0004 (subscriptions produce usage series), MIP-0006 (looks produce observation series), `dspy/` (the existing offline-Python-produces-an-artifact pattern) |
| **Effort** | L — a new Python offline step plus a Scala loader; needs weeks of accumulated series before any backtest |
| **Gain** | infra/dev-loop (calibrates the jellyfish/whale heuristics against real reports) |
| **Effort vs Gain** | park — Phase 4, explicitly needs marola's own accumulated data first; only accumulation is worth starting now |
| **Depends on** | MIP-0001 (water cadence), MIP-0004 (usage series), MIP-0006 (observation series); Phase 4 |
| **Risk** | zero-shot foundation models may simply lose to "last result persists" on marola's tiny, noisy series |
| **Cost so far** | ~$0.7 shared with MIP-0006 (same drafting commit 5abeecc, not split further) |

## 1. Summary

A pretrained time-series transformer (Nixtla's TimeGPT, or the open-weight models Chronos,
TimesFM, Moirai) forecasts a numeric series from its history alone, zero-shot. That is **not**
useful for the sea forecast: Open-Meteo's physics models already beat any generic model on
waves and wind, but it is useful for the series marola will *own* and nobody forecasts: bathing-
water quality between the agency's weekly (off-season monthly) samples, jellyfish and man-o'-war
strandings from sighting reports, sea-temperature anomalies at a beach, and the request/usage
patterns MIP-0003 counts. This MIP scopes those uses, picks the open models that run locally,
and keeps Python offline like `dspy/`.

## 2. Motivation

- **The gap in the data marola shows.** IMA samples weekly in season and monthly off-season
  (MIP-0001 §4.1). A user sees "PRÓPRIA, 25 Aug" on 5 Sep after a week of rain. Rainfall (which
  Open-Meteo gives hourly, past and future) is the known driver of contamination
  (`knowledge/bathing-water-quality.md`); a model over (rain, past results) per point would give a
  daily *estimate* between samples, labelled as an estimate.
- **The heuristics have no ground truth.** `Swimability.jellyfishRisk` is four correlates and a
  count (`ARCHITECTURE.md` §8). Sightings (MIP-0001 `Pollution`, MIP-0006 looks) will accumulate a
  labelled series per beach; forecasting strandings from sea temp, wind direction and recent
  reports is the calibration the doc promises.
- **The idea's provenance.** The author saw a data-science team decline these models for reasons
  that sounded like job protection. This MIP is the honest test: where do they beat what marola
  has, measured, on marola's data.

## 3. User-visible change

Only where a forecast adds information, always labelled:

```
water: PRÓPRIA (25 Aug) · est. today: likely fit (rain 0 mm last 48 h)   ← between samples
water: PRÓPRIA (25 Aug) · est. today: uncertain — 38 mm rain since Wed, next sample due Mon
jellyfish: Moderate (heuristic) · reports trend: rising this week at this beach
```

The agency's classification and the deterministic verdict stay authoritative; the estimate is a
note, never the veto.

## 4. Data sources and dependencies reviewed

### 4.1 Models

| Model | Author | Weights | Runs where | Notes |
|---|---|---|---|---|
| **Chronos** (Chronos-Bolt) | Amazon | open (Apache-2.0), Hugging Face | local CPU (`chronos-forecasting` Python) | Tokenises values into a T5-style LM; zero-shot; Bolt variants are small and fast |
| **TimesFM** | Google Research | open weights, Hugging Face | local (`timesfm` Python) | Decoder-only, up to 200M params; zero-shot point forecasts |
| **Moirai** (Uni2TS) | Salesforce | open (Apache-2.0) | local | Handles covariates and multivariate series — relevant for "rain → contamination" |
| **Lag-Llama** | open | open | local | Probabilistic; smaller community |
| **TimeGPT / TimeGEN-1** | Nixtla | closed, API | Nixtla API (paid, free trial) | The best-known; not local |

*Verification status:* the open models' existence, licences and Python packages are well
documented as of 2026; **not run here**. Model quality on marola's series is unknown until §7.

### 4.2 marola's series (what exists today, what is missing)

| Series | Source | Exists? |
|---|---|---|
| Water-quality results per point, weekly/monthly | IMA feed (last 5 samples per point) | Yes, but only 5 points of history per point per fetch; needs **accumulation** (store every fetch) |
| Hourly rainfall, past and forecast, per beach | Open-Meteo (past_days + forecast) | Available, not fetched yet |
| Sea temperature, wind, waves per beach hourly | Open-Meteo | Yes (forecast); past needs the archive/`past_days` |
| Sightings (jellyfish, whale, pollution) | `SightingStore` | Store exists; series empty |
| Looks (observations) | MIP-0006 | Not built |
| Requests per area/tile | MIP-0003 counters | Not built |

The first deliverable is therefore boring and essential: **persist every IMA fetch and the past
48 h of rain per point**, so a series exists to forecast.

### 4.3 Runtime shape

Python offline, like `dspy/`: `forecast/` with a script that reads the accumulated series
(`data/series/*.jsonl`), runs the chosen open model, and writes `data/estimates/<day>.json`;
the Scala side (`core/estimates/`) loads that artifact and renders the labelled note. No Python in
the request path, no model call per user, and the artifact is one more input to the MIP-0005
board.

## 5. Design

- `core/series/SeriesStore` (trait) — append-only per-series JSON lines: `water/<pointId>`,
  `rain/<beach>`, `sightings/<beach>/<kind>`; `LocalFileSeriesStore` default.
  `Recommender` appends what it fetched (water results, rain) as a side effect of a normal run.
- `forecast/estimate_water.py`: per point, features = last N results + rainfall sums (24/48/72 h);
  model = Chronos-Bolt zero-shot as the baseline, **and** a plain logistic/GBM on the same features
  as the control (the honest comparison the DS team never showed). Output: P(unfit today) with a
  one-line rationale (rain mm).
- `forecast/estimate_strandings.py`: per beach, sighting counts + sea temp + wind direction →
  next-7-day expected reports; Moirai for the covariate version.
- `core/estimates/Estimates.scala`: loads the artifacts, exposes `waterEstimate(pointId, today)`,
  `strandingTrend(beach)`; `Report`/board add the labelled notes. Stale artifact (> 24 h) → no note.

## 6. Scoring / safety impact

**None in v1, by rule.** Estimates are notes. Promotion to a score input requires a measured
precision/recall on held-out weeks (§7) and its own MIP; a wrong "likely fit" is a safety
failure, so the bar is the same as for the water veto.

## 7. Verification plan

- Backtest before anything is shown: accumulate ≥ 12 weeks of IMA results + rain for all 260 SC
  points (the feed carries 5 past samples per point, so ~5 weeks exist on day one), hold out the
  last 4 weeks, compare: naive "last result persists", logistic on rain, Chronos-Bolt zero-shot,
  Moirai with rain covariate. Metrics: Brier score and recall on IMPRÓPRIA. Publish the table
  under `docs/benchmarks/` like the answer benchmark.
- Only if a model beats "last result persists" by a margin worth a sentence does the note ship.
- Unit: `SeriesStore` append/read; `Estimates` staleness and rendering from a fixture artifact.

## 8. Risks, limitations, and honest caveats

- **Tiny, noisy series.** Weekly samples, 5 to 50 points per series: foundation models were
  pretrained on millions of series but zero-shot on 20 points is a coin toss; the control model may
  win. That is a fine outcome and gets recorded.
- **It is not a sea forecast.** Never present an estimate as a wave/wind forecast; Open-Meteo is
  the forecast.
- **Python in the loop again** (offline). Same tradeoff as `dspy/`, same mitigation: artifact in,
  no runtime dependency.

## 9. Alternatives considered

- **Nixtla API from day one.** Fast to try, closed, paid, cloud — fails local-first.
- **Hand-written rules only** ("rain > 30 mm in 48 h → warn"). Cheaper, explainable, and probably
  the control that wins early; the MIP keeps it as the baseline rather than the alternative.
- **Do nothing until the bot exists.** The accumulation part (§4.2) must start now or there is no
  series when the models are ready; the modelling can wait.

## 11. Open questions

1. Start accumulating IMA + rain now (a 20-line change in `Recommender`) ahead of the rest?
   (Proposal: yes, it is the only time-critical part.)
2. Which open model first: Chronos-Bolt (simplest) or Moirai (covariates)? (Proposal: both in the
   backtest; ship one.)
3. Where do estimates appear first: CLI note, bot, or the MIP-0005 map? (Proposal: map + CLI, same
   artifact.)
