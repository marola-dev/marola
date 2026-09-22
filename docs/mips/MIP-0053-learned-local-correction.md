# MIP-0053: A learned local correction, not another model — statistical downscaling of the public wave forecasts

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5), for M. Hoffmann (request of 2026-09-10: "existe algum modelo que rode em GPU que compete com WW3 para forecast? XGBoost, DNN, transformer etc") |
| **Created** | 2026-09-10 |
| **Phase** | 0/3 — an offline Python training step plus a JSON artefact the Scala side loads, exactly the shape `dspy/` already has. No Azure, no paid resource, no runtime Python, no GPU |
| **Related** | MIP-0051 (this consumes both of its outputs: the multi-model ensemble is the feature vector, the buoy ledger is the training set); MIP-0052 (the physical alternative — this MIP is the cheap end of the same problem, and undercuts it by two orders of magnitude in effort); MIP-0025/MIP-0048 (marola-sea — a different model for a different job, but the precedent for an offline training chain in this repo); MIP-0045 (choosing the simplest method that works over the fashionable one — the same argument arrives here from the literature rather than from taste); MIP-0001 (the verbatim-classification principle §6 extends) |
| **Effort** | M — one offline trainer under a new `downscale/` directory, one JSON artefact, one deterministic Scala applier of a handful of coefficients, and tests. No new runtime dependency; the model form (§5.2) is deliberately small enough to apply in plain Scala |
| **Gain** | `user value` — MIP-0051 §2 measured a 6.7× disagreement at Jurerê caused by 25 km grids that cannot see a 3 km bay; a learned correction is the only one of the three available fixes that costs days rather than months; `infra/dev-loop` — it turns MIP-0051's buoy ledger from a decision gate into a training set, so the same data earns twice |
| **Effort vs Gain** | `do next` for the **offshore** correction, which can be trained the moment MIP-0051's ledger has data; **`park` for the sheltered-bay correction that actually motivated this MIP** — §4.3 establishes there is no local observation to train it on, and no model form fixes a missing dataset |
| **Depends on** | MIP-0051 must land first: without the `models=` ensemble there is no feature vector, and without the buoy ledger there is no target. No Phase 1 gate, no Azure, no paid resource. Nothing in MIP-0052 is needed — this is explicitly the alternative to it |
| **Blocked by** | 0051 |
| **Risk** | That a correction trained on an offshore buoy is quietly applied to sheltered beaches it was never validated at, making a confident wrong number out of an honest uncertain one. §5.3 and §6 exist to make that structurally impossible rather than merely discouraged |
| **Cost so far** | — |

## 1. Summary

The question that prompted this MIP was whether a GPU model (XGBoost, a DNN, a transformer) can
compete with WAVEWATCH III. The honest answer is that the good ones do not compete with it, they
*depend* on it, and the ones that appear to beat it mostly rediscovered persistence. But the question
has a better answer hiding behind it: marola does not need a wave model at all. It needs wave height
at eighty fixed points, and the right shape for that is a **learned correction on top of the
forecasts it already fetches**, trained offline, exported as coefficients, applied deterministically
in Scala, in milliseconds, on a CPU.

## 2. Motivation

MIP-0051 §2 measured the problem: at Jurerê, a sheltered north-facing bay about 3 km wide, MFWAM
reports 0.20 m and DWD GWAM 1.34 m for the same hour, and the three models disagree on the
`Swimability` bucket in 48 of 48 forecast hours. The cause is not randomness; it is that a 25 km
grid cell cannot contain a 3 km bay.

There are exactly three ways to fix that, and they differ in cost by two orders of magnitude:

| Fix | What it is | Cost |
|---|---|---|
| Ask a finer public model | There isn't one; MFWAM's 8 km is the finest available (MIP-0051 §4.1) | n/a |
| Run our own nest at 1.1 km | MIP-0051 §5.3 / MIP-0052 | ~87 core-hours per cycle, months of setup |
| **Learn the correction** | This MIP | days, and the training data is a by-product of work already proposed |

The third is not a worse version of the second. A 25 km model's error inside a sheltered bay is
**systematic**: the same shelter, the same orientation, the same swell windows, every time. Systematic
error is exactly what a regression removes, and it is exactly what more resolution would also remove.
The difference is that one costs a workstation-quarter and the other costs a JSON file.

## 3. User-visible change

MIP-0051 leaves a sheltered beach honest but unhelpful:

```
Jurere - tomorrow 09:00
  waves 0.8 m · period 7.9 s · sea 19.1 °C
  wave height: 0.2-1.3 m across 3 models - large disagreement. Jurere is a sheltered bay;
  the 25 km models cannot resolve it. MFWAM (8 km) reports 0.2 m.
```

With a validated correction, marola can say which number to act on, and why it is entitled to:

```
Jurere - tomorrow 09:00
  waves 0.4 m · period 7.9 s · sea 19.1 °C
  wave height: 0.2-1.3 m across 3 models, corrected to 0.4 m for local shelter
  (learned from 214 days of observation here; typical error +/- 0.15 m)
```

Where no correction has been validated, **nothing changes**: the MIP-0051 line is shown unmodified.
The corrected number never appears without the sample count and the error bar that earn it.

## 4. Data sources and dependencies reviewed

### 4.1 Aurora 0.25° Wave — reviewed, rejected for this purpose

Microsoft's Aurora has a wave variant, and it is genuinely open: `aurora-0.25-wave.ckpt` in the
`microsoft/aurora` Hugging Face repository, **MIT-licensed** (verified 2026-09-10, a far better
licence position than WW3's, MIP-0052 §4.2). It predicts `swh`, `mwp`, `pp1d`, `mwd` and swell
partitions, and Microsoft reports it outperforming operational forecasts on ocean waves "at orders of
magnitude lower computational cost". It is the strongest AI wave model available today.

**Rejected on two grounds, both structural:**

1. **It requires HRES-WAM as input.** Its own documentation states it needs ocean-wave variables from
   HRES-WAM (ECMWF's operational wave model) plus HRES atmospheric fields. It does not replace a
   wave model; it accelerates one. The dependency marola would acquire is on ECMWF operational wave
   output, which is not the free, keyless path `ARCHITECTURE.md` §5 requires.
2. **It runs at 0.25°.** That is the same ~25 km that MIP-0051 §2 showed cannot see Jurerê. Aurora is
   a *faster* WAM, not a *finer* one. It solves the cost problem of institutions that run wave models,
   which marola does not have.

### 4.2 Station-level ML, and the finding that disciplines this whole MIP

The literature reports ML beating physical models at buoys; one Lake Erie study gives XGBoost a mean
absolute error of 0.11–0.18 m against WW3's 0.12–0.48 m. Taken alone that would argue for a large
model.

It should not be taken alone. A 2024 comment paper, *"Comment on papers using machine learning for
significant wave height time series prediction: **Complex models do not outperform auto-regression**"*
compared AutoRegressive, XGBoost, ANN, LSTM and WaveNet across 16 buoy locations and found the
differences **negligible**, concluding that the complex models "have only 'learned' the linear
auto-regression from the data".

The mechanism is worth stating plainly, because it is a trap this MIP could otherwise walk into:
**significant wave height is strongly autocorrelated.** Predicting it three hours ahead from its own
recent history is nearly free, and a model doing only that will post excellent short-lead scores while
having learned nothing about forecasting. Much of "ML beats WW3" is persistence beating WW3 at short
lead, which says nothing about the 24–48 h horizon marola actually serves.

**Two consequences for §5, both restrictive:**

- The model form should start **linear**, and a more complex one must earn its place against that
  baseline rather than be assumed better. The critique above is the argument.
- The evaluation must include a **persistence baseline and a raw-ensemble baseline**, at marola's real
  lead times. A correction that cannot beat "use the ensemble median unchanged" at 24 h is not worth
  shipping regardless of its RMSE at 3 h.

### 4.3 The training data marola would actually have — and the gap that matters

The features are free and already fetched: MIP-0051's three models at the beach, wind speed and
direction, tide (`sea_level_height_msl`), period and swell partitions, plus static per-beach
descriptors (coastline orientation, exposure) derivable from the OSM data marola already has.

The **target** is the problem, and it splits:

- **Offshore / exposed beaches**: PNBOIA's Itajaí buoy gives a real target. A correction can be
  trained here as soon as MIP-0051 §5.4's ledger accumulates. This part is buildable.
- **Sheltered bays, the case that motivated this MIP, have no target at all.** The Itajaí buoy is
  ~90 km north and offshore; it cannot observe Jurerê. There is no public observation inside these
  bays. **No model form fixes this**: XGBoost, a transformer and a linear fit are equally helpless
  without a label. This is why §5.3 refuses to extrapolate a correction to beaches it was not trained
  at, and why the metadata's verdict parks that half.

Candidate local-truth sources, none verified and all carried as open questions (§11): a low-cost
pressure sensor or a small buoy; satellite altimetry (Sentinel-3/CMEMS), which gives along-track `Hs`
but not inside bays; and dated user reports through the existing `SightingStore` mechanism, which is
the only one that costs nothing and the only one whose quality is unknown.

### 4.4 Runtime constraint — the model form is decided by where it must run

`AGENTS.md` and `dspy/README.md` set the pattern: **offline Python produces an artefact; the Scala
runtime loads it as plain data and never imports Python.** `marola.llm.CompiledPrompt` is the working
precedent. Anything shipped here must therefore be small enough to apply in plain Scala. A ridge
regression is a handful of coefficients in JSON. A gradient-boosted forest is thousands of tree nodes,
and a neural network is a runtime dependency marola does not have. §4.2's evidence says the small
option is also very likely the accurate one, so the constraint and the evidence agree.

## 5. Design

### 5.1 Features

Per beach, per forecast hour, all already available after MIP-0051:

```
wave_height_mfwam, wave_height_ecmwf_wam, wave_height_dwd_gwam    (the ensemble, per model)
ensemble_median, ensemble_range                                    (MIP-0051's ModelSpread)
wave_period, swell_wave_height, swell_wave_period, wave_direction
wind_speed, wind_direction, sea_level_msl
beach_orientation_deg, lead_time_h                                 (static / derived)
```

`wave_direction` relative to `beach_orientation_deg` is the physically important one: shelter is a
function of whether the swell can reach the bay at all, which is why a single scalar correction per
beach would be wrong and an orientation-aware one has a chance.

### 5.2 A per-beach linear correction, exported as JSON

```scala
final case class LocalCorrection(
    beachId: String,
    coefficients: Map[String, Double],
    intercept: Double,
    trainedOnSamples: Int,
    validationMaeM: Double,
    trainedThrough: LocalDate
):
  def apply(hour: HourlyConditions, beach: Beach): Option[Double]
```

Trained offline under a new `downscale/` directory (ridge regression, scikit-learn), exported to
`core/src/main/resources/local_corrections.json`, loaded by a `LocalCorrections` object in `core`.
The applier is plain arithmetic: deterministic, unit-testable, and inspectable by a human reading the
JSON. No Python at runtime, no new dependency, no GPU, and inference is microseconds.

### 5.3 Three rules that make a bad correction structurally impossible

1. **No correction without validation at that beach.** A beach absent from the artefact gets the
   MIP-0051 ensemble median unchanged. There is no default correction, no nearest-neighbour fallback
   and no regional average; those are exactly how an Itajaí-trained correction would leak into
   Jurerê.
2. **Clamped magnitude.** A correction may move the value by at most ±0.5 m or ±50%, whichever is
   smaller. This bounds the blast radius of a bad fit or a corrupt artefact; it cannot produce 0.0 m
   from 1.3 m.
3. **Asymmetric safety** (§6). A correction may raise the reported wave height freely. It may
   **not**, on its own, lower it across a `Swimability` threshold.

### 5.4 What is not proposed

Not a wave model. Not a GPU. Not a neural network. Not Aurora. Not a correction for temperature,
current, jellyfish risk or the water-quality verdict; this MIP touches wave height only, and
MIP-0001's rule that the agency's classification is shown verbatim is untouched and unextendable by
anything here.

## 6. Scoring / safety impact

This is the section that matters, because unlike MIP-0051 this MIP **changes the number the scorer
reads** rather than just re-sourcing it.

`Swimability.score` is unchanged in code. Its input `waveHeightM` becomes the corrected value where a
validated correction exists, and the ensemble median everywhere else. The thresholds stay
`CalmWaveHeightM = 0.6`, `RoughWaveHeightM = 1.5`, `WhaleCalmWaveHeightM = 1.0`.

**The asymmetric rule, stated precisely.** Let `raw` be the ensemble median and `corr` the corrected
value:

- If `corr > raw`, conditions look **worse** than the public models said: apply `corr` in full. A
  learned correction warning of more wave than the coarse grid saw is exactly the case worth trusting,
  and being wrong costs a cancelled swim.
- If `corr < raw`, conditions look **safer**: apply `corr`, but **never let it cross a threshold
  the raw value did not**. Concretely, if `raw >= RoughWaveHeightM` then the scored value is
  `max(corr, RoughWaveHeightM)`; the same for the `CalmWaveHeightM` and `WhaleCalmWaveHeightM`
  boundaries. Clearing a danger classification requires the physical models to agree, not a
  regression.

The reasoning is MIP-0001 §6/§9's, extended one step. marola shows CONAMA's `PRÓPRIA`/`IMPRÓPRIA`
verbatim because the classification belongs to the agency. Here the classification belongs to the
physics: a learned fit may inform how rough it is, and may not be the sole authority that declares it
safe. The safety footer stays deterministic and stays plain Scala.

**The user-visible consequence must be shown, not hidden.** §3's line always carries the sample count
and validation error. A number the model corrected and a number it did not must never look identical.

## 7. Verification plan

Unit tests (`core`):
- `LocalCorrectionsSpec`: a beach absent from the artefact returns the raw median unchanged; a beach
  present returns the corrected value; a malformed artefact fails loudly at load, not silently at
  request time.
- `LocalCorrectionsSpec`: the ±0.5 m / ±50% clamp binds in both directions.
- `LocalCorrectionsSpec`: **the asymmetric rule**: `raw = 1.6`, `corr = 0.9` scores as 1.5, not 0.9;
  `raw = 0.9`, `corr = 1.6` scores as 1.6 in full. One named test per threshold.
- `SwimabilitySpec`: an existing scoring test re-run with a correction present, asserting the bucket
  only moves in the permitted direction.

Offline (`downscale/`, with a self-test in `quality-other` like every other `scripts/*.py`):
- Backtest against held-out buoy data with **three baselines reported side by side**: persistence,
  raw ensemble median, and the correction. Reported per lead time (3 h, 12 h, 24 h, 48 h), because
  §4.2 says a short-lead win is not evidence.
- **Done means the correction beats the raw ensemble median at 24 h and 48 h.** Beating persistence
  at 3 h is not a result and does not ship.

### 7.1 The one experiment that settles the sheltered-bay half

Everything above validates the offshore case. The case that motivated the MIP needs a target, and the
cheapest honest test is to obtain one for **a single bay** (one sensor, one season, one beach) and
ask whether a correction trained there beats the ensemble. If it does, the method generalises to the
other sheltered beaches as a data-collection problem. If it does not, this MIP is closed and MIP-0052's
physical route is the only remaining answer. That experiment is worth more than any model-selection
work.

## 8. Risks, limitations, and honest caveats

- **The correction is trained where the problem isn't.** Itajaí is offshore; Jurerê is a bay. This is
  the central limitation, it is not fixable by choosing a better algorithm, and §5.3's first rule
  exists so that the gap produces *no correction* rather than a confident wrong one.
- **A learned correction is only valid while the source models are unchanged.** Open-Meteo can update
  MFWAM's version, or change which model backs a field; the correction was fitted to the old one.
  Coefficients must carry `trainedThrough`, and drift needs monitoring; the ledger already collects
  what is needed to notice it.
- **Autocorrelation flatters everything** (§4.2). Every reported result must name its baseline. A
  number without a baseline is not evidence of skill.
- **Climate is not stationary and neither is a beach.** Sandbars move, storms redraw a bay's
  bathymetry in a night. A correction fitted last season may be wrong this one, in a way no in-sample
  metric will reveal.
- **This adds a fitted quantity to a safety-relevant path.** Mitigated by §5.3's clamp, §6's
  asymmetry, and the artefact being human-readable, but the honest statement is that MIP-0051's
  output is measurement plus a median, and this MIP's output is measurement plus a model.
- **Sample counts will be small.** A season is a few hundred usable observations per beach, against a
  dozen features. This is a strong argument for ridge over anything larger, and a reason to expect
  modest gains rather than the numbers in §4.2's optimistic literature.

## 9. Alternatives considered

- **Do nothing: ship MIP-0051 and stop.** A real option, and the fallback if §7.1 fails. marola would
  keep showing the spread honestly and never resolve it. Rejected as the *end state* only because the
  spread at sheltered beaches is large enough to make the forecast unhelpful there, not merely uncertain.
- **Run our own 1.1 km nest (MIP-0051 §5.3 / MIP-0052).** The physically correct fix, and the one that
  needs no local observation because it computes the shelter instead of learning it. Not rejected,
  deferred, on the two-orders-of-magnitude cost difference. If §7.1's experiment fails for want of
  data, this becomes the answer by elimination.
- **Aurora 0.25° Wave**: §4.1. Rejected: needs HRES-WAM, and shares the resolution limit.
- **XGBoost or an LSTM instead of a linear fit**: §4.2 and §4.4. Rejected *as the starting point*, not
  forever: they must beat ridge on held-out data at 24–48 h before they earn a runtime cost that plain
  Scala cannot pay.
- **Fine-tune marola-sea (MIP-0025/0048) to predict wave height.** Rejected: a language model is the
  wrong instrument for a regression on twelve numeric features, and it would move a safety-relevant
  quantity into an LLM, which `AGENTS.md` forbids.
- **Buy a commercial nearshore forecast.** Still uncosted, still the honest alternative to all of the
  above, and still deserving its own investigation, the same note MIP-0052 §9 carries.

## 10. Exam-coverage mapping

**None directly.** No Azure service, no agent behaviour, no LLM surface. The nearest adjacency is
AI-103's "Responsible AI: transparency" row that MIP-0051 already claims — §3's requirement that a
corrected number always displays its sample count and error bar is the same principle applied to a
fitted quantity rather than a sourced one. Not claimed as coverage.

## 11. Open questions

- **Where does local truth come from for a sheltered bay?** §4.3 lists three unverified candidates:
  a cheap sensor, satellite altimetry, dated user reports via `SightingStore`. None was investigated.
  This is the question the MIP lives or dies by, and §7.1 is the cheapest way to answer it.
- **Is Itajaí's buoy transmitting, and in what format?** Inherited unresolved from MIP-0051 §11. Both
  MIPs are blocked on the same unfetched endpoint.
- **How many samples before a correction may ship?** §5.3 requires validation but names no minimum.
  A few hundred hours against a dozen features suggests a floor in the low hundreds, but this should
  be set by the backtest's variance, not by a guess written here.
- **Does an orientation-aware feature actually capture shelter**, or does a bay need a per-swell-window
  term? Settled by §7.1's data, not by argument.
- **Follow-up MIP:** the same correction machinery would apply to **wind**: `Swimability` reads wind
  speed too, and coarse models miss the sea-breeze onset that decides a Florianópolis afternoon. It is
  a different target with a different truth source (INMET stations, which are on land and plentiful,
  unlike buoys) and needs its own number.

## Appendix

### Checked live

All 2026-09-10.

- **`microsoft/aurora` on Hugging Face**: the repository contains **`aurora-0.25-wave.ckpt`**
  (alongside `aurora-0.25-wave-static.nc` / `.pickle`), among nine checkpoints including
  `aurora-0.25-v1.5.ckpt` and `aurora-0.4-air-pollution.ckpt`. Card metadata reports **`license: mit`**.
  Queried via the Hugging Face API.
- **`microsoft/aurora` on GitHub**: 1,013 stars, last pushed 2026-08-19; GitHub reports the repository
  licence as `NOASSERTION` (unparsed), which is why the MIT statement above cites the **weights'** card
  metadata and not the repository.
- **Aurora 0.25° Wave documentation** (https://microsoft.github.io/aurora/example_wave.html): predicts
  `swh`, `mwp`, `pp1d`, `mwd` and swell partitions (`shts`, `mdts`, `mpts`, `swh1`, `swh2`); **requires
  ocean-wave variables from HRES-WAM plus HRES T0 atmospheric fields**; 0.25° resolution; the worked
  example runs two 6-hour rollout steps on a CUDA GPU.
- **Microsoft Research / Aurora** (https://www.microsoft.com/en-us/research/project/aurora-forecasting/)
  Aurora "outperforms operational forecasts in predicting air quality, ocean waves, tropical cyclone
  tracks and high-resolution weather, all at orders of magnitude lower computational cost".
- **"Complex models do not outperform auto-regression"**
  (https://www.sciencedirect.com/science/article/abs/pii/S1463500324000519, 2024): AR, XGBoost, ANN,
  LSTM and WaveNet compared at **16 buoy locations**; performance differences **negligible**; the
  models "have only 'learned' the linear auto-regression from the data".
- **XGBoost/LSTM at buoys, Lake Erie**
  (https://www.sciencedirect.com/science/article/pii/S1463500321000846): XGBoost wave-height MAE
  ~**0.11–0.18 m** against WW3's ~**0.12–0.48 m**.
- **The repo's offline-artefact pattern**: `dspy/README.md`: Python "run **offline only**, this never
  runs in production and marola's Scala/Kyo runtime never imports Python", producing JSON loaded by
  `marola.llm.CompiledPrompt`. §4.4/§5.2 follow it exactly.
- `Swimability.scala:27,28,39`: the three thresholds §6's asymmetric rule protects.

### Not checked

- **PNBOIA in every respect**: inherited from MIP-0051 §4.2 and still unfetched: endpoint, format,
  cadence, licence, and whether the Itajaí buoy transmits today.
- **Every claim in §4.2's literature was read from search-result summaries, not from the papers.** The
  Lake Erie MAE figures and the comment paper's conclusion are quoted as reported, not verified against
  the PDFs. The comment paper's *conclusion* drives §5.2's model choice, so it is the one most worth
  reading properly before building.
- Aurora's actual VRAM requirement, its inference time on a consumer GPU, and whether HRES-WAM
  initial conditions are obtainable free in near-real-time; the last determines whether §4.1's
  rejection is structural or merely inconvenient.
- Whether `microsoft/aurora`'s repository `LICENSE` file is also MIT; only the weights' card metadata
  was read.
- Any Brazilian or South Atlantic study of ML wave downscaling. The literature reviewed is from the
  Great Lakes, the South China Sea and offshore China; none of it establishes that these results
  transfer to a subtropical swell-dominated coast.
- Whether a per-beach ridge fit on a few hundred samples actually beats the ensemble median at 24–48 h
  anywhere. **No model was trained; no backtest was run.** §7 exists because nothing here is measured.
