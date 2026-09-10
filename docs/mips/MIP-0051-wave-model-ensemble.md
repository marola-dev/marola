# MIP-0051: Which wave model is marola quoting? — a labelled multi-model ensemble, and the road to our own grid

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5), for M. Hoffmann (request of 2026-09-09: "create a MIP about adding WW3 and WRF to marola. Is it possible to already plug in the website with public data?") |
| **Created** | 2026-09-10 |
| **Phase** | 0/3 — `core` and the static site only. No Azure, no paid resource, no new dependency, no extra HTTP call per beach |
| **Related** | MIP-0001 (added the marine fields this MIP re-sources, and set the "show the agency's classification verbatim, don't let a model blend a category" principle §6 reuses); MIP-0005 (the static site and its "no server" decision, which §5.3's deployment shape preserves); MIP-0038/0039/0040 (the honesty layer — a labelled model and a shown disagreement is exactly its subject matter); MIP-0042 (`/v2`'s live scorer, which would consume the spread client-side); MIP-0048 (scaling marola-sea — unrelated model, same "measure before you scale" discipline); **MIP-0052** (the sibling: porting a wave model's compute core to a GPU, which only becomes relevant at §5.3's rung C) |
| **Effort** | M for §5.1/§5.2 (one query-string change, one `enum`, one case class, a median, a site label — no new call, no new dependency); M for §5.4 (a buoy-fetching job and an append-only ledger); L for §5.3 (running our own nearshore grid — designed here, deliberately not built) |
| **Gain** | `user value` — marola currently shows one model's wave height as fact, and at Jurerê that number ranges from 0.20 m to 1.34 m depending on which model you ask (§2); `infra/dev-loop` — a buoy-scored ledger turns "is our forecast good?" from an opinion into a weekly number, and it is the gate that decides whether §5.3 is worth months |
| **Effort vs Gain** | `do next` for §5.1/§5.2 — the disagreement is measured, it crosses live scoring thresholds, and the fix is one query parameter plus a median; `do next` for §5.4, which costs little and runs itself thereafter; `do when §5.4 has a season of data` for §5.3 — building a nearshore grid before knowing the public models' error at our beaches is building against an unmeasured bar |
| **Depends on** | Nothing blocks §5.1/§5.2 — no Phase 1 gate, no Azure, no paid resource, and the marine call already exists. §5.3 depends on §5.4 producing a season of buoy scores, and on a human decision about running a model on a home machine. MIP-0052 is a sibling, not a prerequisite: it matters only if §5.3 is adopted *and* the CPU cost of running it proves to be the binding constraint |
| **Blocked by** | none |
| **Risk** | The honest outcome of §5.4 may be that MFWAM at 8 km is already good enough at our beaches, and §5.3 is never worth building. That is a *successful* result of this MIP, but it means the WW3/WRF ambition that prompted it dies on the evidence — this MIP is written to make that outcome cheap to reach and easy to accept |
| **Cost so far** | — |

## 1. Summary

marola quotes a wave height for every beach and never says where it came from. It comes from
Météo-France's MFWAM — verified today by matching values, not by any code that records it — and
three other wave models are available from the same endpoint, in the same request, for free.
They disagree enough to move marola's own scoring thresholds. This MIP labels the number, shows
the disagreement instead of hiding it, scores every model against a real buoy, and uses that score
— not enthusiasm — to decide whether marola should ever run a wave model of its own.

## 2. Motivation

**marola is already running WAVEWATCH III, and doesn't know it.** `OpenMeteoClient.scala:25` calls
`marine-api.open-meteo.com`. That endpoint is fed by several wave models, one of which — NCEP
GFS-Wave — *is* WAVEWATCH III. marola sends no `models=` parameter, takes the default, and stores
the result in `HourlyConditions.waveHeightM` with no record of its origin. The question that
prompted this MIP ("is it possible to already plug in the website with public data?") is therefore
already answered in the affirmative, blindly, and has been since MIP-0001.

**The models disagree, and the disagreement crosses marola's own thresholds.** Measured live on
2026-09-10 across eight Florianópolis beaches × 48 forecast hours (384 beach-hours), comparing
`meteofrance_wave`, `ecmwf_wam` and `dwd_gwam`:

| | Disagreement |
|---|---|
| Hours where the three models fall in **different `Swimability` penalty buckets** (`RoughWaveHeightM = 1.5` → −40, `CalmWaveHeightM = 0.6` → −15, else 0) | **110/384 — 29%** |
| Hours where they disagree on the **whale-calm flag** (`WhaleCalmWaveHeightM = 1.0`, `Swimability.scala:39`) | **372/384 — 97%** |

The single worst case is physical, not random. At **Jurerê** — a sheltered, north-facing bay —
MFWAM reports **0.20 m** and DWD GWAM reports **1.34 m** for the same hour, a factor of 6.7, and
the models disagree on the bucket in **48 of 48 hours**. MFWAM runs at 0.08° (~8 km) and partially
resolves the shelter; GWAM and GFS-Wave run at 0.25° (~25 km) and cannot see the bay at all. Every
sheltered beach marola covers has this problem, and marola currently reports one side of it as a
fact.

**One model returns a masked cell as a flat calm.** `ncep_gfswave025` returns `0.0` — not `null` —
for `wave_height` *and* `wave_period` at Campeche, Joaquina, Barra da Lagoa and Armação, because the
0.25° cell containing them is masked as land. It returns a genuine 1.48 m at Pântano do Sul. A 0.0 m
wave height fed to `Swimability.waveNote` scores as a perfect swim day. marola does not hit this
today (it is on MFWAM), but any future "let's add more models" change walks straight into it. This
is the invisible-degradation failure class the repo has spent the month removing, and §5.2 refuses
it explicitly rather than leaving it to be rediscovered.

## 3. User-visible change

Today, the detailed block states a wave height as fact:

```
Campeche — tomorrow 09:00
  waves 1.0 m · period 8.1 s · sea 18.5 °C
```

After §5.1/§5.2, the number is attributed and the disagreement is visible when it is real:

```
Campeche — tomorrow 09:00
  waves 1.3 m · period 8.4 s · sea 18.5 °C
  wave height: 1.0-1.4 m across 3 models (MFWAM 8 km, ECMWF WAM 9 km, DWD GWAM 25 km) - they disagree
```

At a sheltered beach the disagreement is the headline, and marola says which model to believe and
why:

```
Jurere - tomorrow 09:00
  waves 0.8 m · period 7.9 s · sea 19.1 °C
  wave height: 0.2-1.3 m across 3 models - large disagreement. Jurere is a sheltered bay;
  the 25 km models cannot resolve it. MFWAM (8 km) reports 0.2 m.
```

When the models agree, nothing new is printed — the line appears only above the threshold in §5.2.
The site's beach panel gains the same one-line treatment; the map marker itself is unchanged.

## 4. Data sources and dependencies reviewed

### 4.1 Open-Meteo Marine API, `models=` parameter — **picked**

Same endpoint marola already calls, same one request per beach. Verified live 2026-09-10 (see
Appendix): valid identifiers are `meteofrance_wave`, `ecmwf_wam`, `ecmwf_wam025`,
`ncep_gfswave025`, `dwd_gwam`, `gwam`, `era5_ocean`, `meteofrance_currents`. The identifiers
`gfs_wave`, `ewam`, `dwd_ewam`, `ncep_gfswave016` — which appear in Open-Meteo's own blog post and
in search-result summaries — are **rejected by the API** with
`Cannot initialize MultiDomains from invalid String value`. Do not copy model names from the docs
prose; they were wrong when checked.

With multiple models requested, response keys are suffixed `<variable>_<model>`
(`wave_height_meteofrance_wave`). Coverage is per-coordinate and must not be assumed:
`ecmwf_wam025` returned all-null at Campeche; `ncep_gfswave025` returned all-zero at four of five
beaches. Terms unchanged from MIP-0001: free for non-commercial use, no key
(https://open-meteo.com/en/pricing).

**Picked set: `meteofrance_wave`, `ecmwf_wam`, `dwd_gwam`.** All three returned 48/48 non-null,
non-zero hours at every beach tested. `ncep_gfswave025` is *excluded by default* despite being the
WAVEWATCH III one, because it is land-masked at most of marola's beaches; §5.2's zero-rule means
including it later is safe, but it would add nothing at four of five.

### 4.2 PNBOIA / Marinha do Brasil (CHM) — **picked for §5.4, endpoint not yet verified**

Brazil's national buoy programme, run by the Marinha do Brasil's Centro de Hidrografia (CHM),
publishing wave height, period, direction, wind and SST. Buoys have been deployed at nine points;
four were reported in operation (Itajaí, Santos, Cabo Frio, Fortaleza), with **Itajaí** ~90 km north
of Florianópolis and in the same swell window. Data is distributed via CHM and the BNDO.

**What was not verified:** the actual download endpoint, its format, its update cadence, its
licence, and whether the Itajaí buoy is transmitting *today*. This is the single largest unknown in
the MIP and is carried as an open question (§11), not as a design assumption. §5.4 is written so
that a different truth source (a second model as pseudo-truth, or a manual observation log) slots
into the same ledger if PNBOIA turns out to be unusable.

### 4.3 Running WW3 ourselves — reviewed, deferred to §5.3

WAVEWATCH III is public (NOAA-EMC/WW3). Running a nearshore nest for the SC coast is the only way
to get wave heights that resolve Jurerê properly. It is months of work and is gated on §5.4.

### 4.4 Running WRF ourselves — reviewed, and the GPU premise does not hold

The request paired WRF with high-performance GPU languages. That pairing is not supported by the
evidence: NCAR's own support forum states GPU work in WRF is "decentralized and difficult to track
down, and is not officially supported, unlike MPAS which does have official GPU support built in."
WRF would run on the host's **CPU** cores. If atmospheric downscaling is ever adopted, **MPAS** is
the model with an official GPU path, and it is the same E3SM lineage as the Omega work MIP-0052
covers. Deferred entirely; not part of this MIP's design.

### 4.5 ECMWF AIFS — reviewed, parked as a §5.3 input option

Open weights under CC BY 4.0 on Hugging Face, operational at ECMWF since 25 February 2025; a 10-day
global forecast runs in roughly 2.5 minutes on a single A100. It is the one option on this list that
uses a consumer GPU well. But it is a ~0.25° global model: it would be a cheap *driver* for a
nearshore wave nest, not a substitute for kilometre-scale downscaling. Parked; revisit inside §5.3.

## 5. Design

### 5.1 Ask for the models by name (`core`, do next)

`OpenMeteoClient.forecastFor` appends `&models=meteofrance_wave,ecmwf_wam,dwd_gwam` to the **marine**
URL only. This remains **one HTTP call per beach** — the call-count discipline from #333 is
untouched, and Open-Meteo's non-commercial terms are unaffected. The weather URL is unchanged.

```scala
enum WaveModel(val id: String, val label: String, val resolutionKm: Double) derives CanEqual:
  case Mfwam    extends WaveModel("meteofrance_wave", "MFWAM", 8)
  case EcmwfWam extends WaveModel("ecmwf_wam", "ECMWF WAM", 9)
  case DwdGwam  extends WaveModel("dwd_gwam", "DWD GWAM", 25)

final case class ModelSpread(perModel: Map[WaveModel, Double]):
  def values: Vector[Double]   = perModel.values.toVector.sorted
  def consensusM: Option[Double] // median of `values`
  def minM: Option[Double]
  def maxM: Option[Double]
  def rangeM: Option[Double]     // maxM - minM
  def disagrees: Boolean         // see 5.2
```

`HourlyConditions` gains one field and changes no existing one:

```scala
    waveModelSpread: Option[ModelSpread] = None
```

`waveHeightM` keeps its type and its meaning and is populated with `spread.consensusM` — the
**median** across available models. `Swimability`, `Recommender`, `Board`, the MCP tools and every
other consumer are untouched by §5.1. Using the median rather than a mean means one model dropping
out shifts the number as little as possible, and no averaging artefact can produce a value no model
actually reported.

### 5.2 Two rules that keep bad data out

**The zero rule.** A model reporting `wave_height == 0.0` **and** `wave_period == 0.0` at the same
hour is a masked land cell, not a calm sea. Such a reading is dropped before the median, exactly as
if the API had returned `null`. It never reaches `Swimability`. This gets a named test with the
measured Campeche fixture.

**The disagreement rule.** `ModelSpread.disagrees` is true when `rangeM >= 0.4` m **or** when the
models straddle any `Swimability` threshold (0.6, 1.0, 1.5). The second clause is the important one:
a 0.30 m range from 0.9 m to 1.2 m is small in absolute terms and still flips the whale-calm flag.
Only when `disagrees` is true does §3's extra line get printed.

**Site house style is a trap here.** `site_check.js` enforces lowercase across the page and
exempts provider names only where `app.js` explicitly tags them (it already does this for
"Open-Meteo" and "IMA/SC"). `MFWAM`, `ECMWF WAM` and `DWD GWAM` are acronyms that must be tagged the
same way or the site will render them as `mfwam`, and the quality gate will pass while the page
reads wrong. Add them to the same mechanism, and assert it in `site_check.js`.

`Board.scala:117`/`:131` gain `"wave_models"` (an object of label → metres) and `"wave_spread"`
alongside the existing `"wave_m"`, so the static site and MIP-0042's `/v2` scorer both get the
spread without a schema break for anything reading `wave_m` today.

### 5.3 Our own nearshore grid — designed, not built (rung C)

Deployment shape, so that adopting it later does not reopen MIP-0005's "no server" decision: the
always-on machine runs the model on a timer, post-processes to a compact artefact, and **pushes**
it (to this repo or an object store). `marola.dev` stays a static site that serves no traffic from
anyone's house; no inbound network, no tunnel, no Cloudflare dependency, and a missed cycle
degrades to the §5.1 ensemble rather than to an error page.

The first rung is a **nearshore WW3 nest driven by public winds**, not WRF — §4.4's evidence removed
the GPU argument for WRF, and §2's Jurerê case is a *wave-resolution* problem before it is a
wind-resolution one. Atmospheric downscaling (MPAS, per §4.4) is a later, separate decision.
None of this is built by this MIP.

### 5.4 The buoy ledger (do next, gates §5.3)

A small job records, once per cycle: each model's forecast at the buoy's coordinates, at each lead
time, plus the buoy's observation for that hour. Append-only, one row per (model, lead time, hour),
in the shape `docs/benchmarks/` already uses for kept runs. After a season it yields RMSE, bias and
scatter index per model per lead time.

That table is the gate: §5.3 proceeds only if the best public model's error at our beaches is large
enough to be worth months of work. It also, immediately and for free, tells §5.1 which model
deserves to be named first in §3's line.

## 6. Scoring / safety impact

`Swimability.score` is **unchanged**. It reads `waveHeightM`, which is now a median instead of one
model's value — a different number from the same field, with the same type and the same thresholds
(`CalmWaveHeightM = 0.6`, `RoughWaveHeightM = 1.5`, `WhaleCalmWaveHeightM = 1.0`).

Disagreement affects the **confidence text, not the numeric score**. This is deliberate and follows
MIP-0001 §6/§9: marola shows the agency's `PRÓPRIA`/`IMPRÓPRIA` verbatim because the classification
belongs to CONAMA, not to marola. The same reasoning applies here — a spread is evidence about how
much to trust the number, and folding it into the score would manufacture a false precision while
hiding the very uncertainty it represents. The safety footer stays deterministic and stays plain
Scala.

One consequence must be stated plainly: because `waveHeightM` becomes a median, **some hours will
change score** relative to today — the −15/−40 penalties will land differently at sheltered
beaches. That is the point (today's number is one arbitrary model), but it is a user-visible change
in ranking and the implementation PR should show a before/after board for at least one beach.

## 7. Verification plan

Unit tests (`core`):
- `OpenMeteoClientSpec`: parses suffixed multi-model keys; a model absent entirely; a model all-null;
  a mix where only one model has data.
- `ModelSpreadSpec`: median across 1, 2, 3 and 4 values; `disagrees` false for a tight cluster; true
  on a 0.4 m range; **true when straddling 1.0 m with a 0.3 m range** (the whale-flag case).
- `ModelSpreadSpec`: the zero rule — `(0.0, 0.0)` dropped; a genuine `(0.0, 7.5)` **kept**, since a
  real calm has a period.
- `BoardSpec`: `wave_m` unchanged in shape; `wave_models`/`wave_spread` present and omitted cleanly
  when only one model is available.

Live (`just e2e`, excluded from `just test` per `AGENTS.md`):
- One marine call at a Florianópolis coordinate asserting ≥2 named models return non-null,
  non-zero data. Asserts availability, never specific values.

Done looks like: `just build && just test && just quality` green; a before/after board diff for
Jurerê and Campeche in the PR body; the §3 line rendering on the static site.

## 8. Risks, limitations, and honest caveats

- **The buoy is at Itajaí, not at our beaches.** It sits ~90 km north and offshore. It can validate
  *swell*, and it cannot say anything about nearshore transformation inside Jurerê bay — which is
  precisely where §2 shows the models diverge most. §5.4 therefore bounds the public models' *open
  ocean* skill, and the case for §5.3 rests on that plus physics, not on a direct measurement of
  the thing being fixed. Anyone reading the ledger as proof about sheltered beaches is misreading it.
- **Three models are not an ensemble in the formal sense.** They share observational inputs and some
  physics lineage; their spread understates true uncertainty. The site says "they disagree", never
  "there is a 90% chance the wave is between X and Y".
- **A median of three can hide a 2-1 split.** When one model is right and two are wrong together,
  the median is wrong. This is why §5.4 exists and why the per-model values are kept in the JSON
  rather than discarded after taking the median.
- **Open-Meteo's model list is not stable.** Identifiers already documented incorrectly in the
  provider's own blog (§4.1) may also be renamed or retired. A model that stops resolving must
  degrade to the remaining ones, never fail the request — covered by §5.2 and tested in §7.
- **Response size grows ~3× for the marine call.** Call count is unchanged; the board build fetches
  ~80 beaches, so this is bandwidth, not rate limit.

## 9. Alternatives considered

- **Do nothing.** Rejected on §2's numbers: 29% of hours the model choice moves a scoring bucket and
  97% the whale flag, with no user-visible indication that a choice was made at all.
- **Pick the single best model and hard-code it.** Cheaper, and it is what marola accidentally does
  today. Rejected because we have no evidence for which is best (that is §5.4's job), and because
  at Jurerê the honest answer is "the coarse models cannot see this bay" — a fact worth showing, not
  resolving silently.
- **Average instead of median.** Rejected: the mean can report a height no model produced, and it is
  more sensitive to the land-mask zeros that §5.2 exists to catch.
- **Fold disagreement into the score.** Rejected per §6 and MIP-0001's precedent.
- **Go straight to running WW3/WRF (rung C first).** Rejected as the sequencing: it is months of
  work against a bar nobody has measured, and §4.4 showed the WRF-on-GPU premise underlying the
  original request does not hold. §5.4 makes the decision on evidence and costs almost nothing.
- **Copernicus Marine (CMEMS) direct.** A richer source than Open-Meteo, and MFWAM's actual origin.
  Rejected for now: it requires registration and credentials, which breaks the keyless, zero-setup
  default `ARCHITECTURE.md` §5 requires. Worth revisiting if Open-Meteo's terms change.

## 10. Exam-coverage mapping

**AI-103, "Responsible AI: transparency, content safety"** (`docs/AI-103-MAPPING.md:26`) — that row
is currently satisfied by documented limitations plus MIP-0001's deterministic veto. Naming the model
behind a number and showing forecast disagreement is transparency implemented in the product rather
than described in a doc, and the row's Status can gain "proposed: MIP-0051". No AI-500 row applies:
nothing here is agentic, and no human-confirmation gate is removed.

## 11. Open questions

- **PNBOIA's actual endpoint, format, cadence and licence are unverified** (§4.2), as is whether the
  Itajaí buoy is transmitting today. §5.4 cannot be scheduled until someone fetches it once. This is
  the first task of the implementation PR, not a design assumption.
- **Which model leads the §3 line before the ledger has data?** MFWAM is the highest-resolution and
  is what marola already (accidentally) reports; proposed as the interim default, revisited by §5.4.
- **Is 0.4 m the right disagreement threshold?** Chosen because it is two thirds of the gap between
  `CalmWaveHeightM` and `RoughWaveHeightM`; not tuned against anything. Cheap to change once the
  site shows it.
- **Follow-up MIP:** the same `models=` treatment applies to the *weather* call — wind speed and
  direction drive `Swimability` too, Open-Meteo exposes ICON/GFS/IFS/GEM there, and no equivalent
  disagreement measurement has been done. That is a separate proposal and needs its own number.
- **Follow-up MIP:** §5.3's deployment shape (a home machine pushing artefacts consumed by a static
  site) is a repo-wide pattern that would also serve MIP-0048's model training and MIP-0042's `/v2`.
  If §5.3 is ever adopted, that pattern deserves designing once, under its own number.

## Appendix

### Checked live

All fetched 2026-09-10 unless stated.

- `https://marine-api.open-meteo.com/v1/marine?...&models=<id>` probed for twelve candidate
  identifiers. **Valid:** `meteofrance_wave`, `ecmwf_wam`, `ecmwf_wam025`, `ncep_gfswave025`,
  `dwd_gwam`, `gwam`, `era5_ocean`, `meteofrance_currents`. **Rejected with
  `Cannot initialize MultiDomains from invalid String value`:** `gfs_wave`, `ncep_gfswave016`,
  `gfswave025`, `dwd_ewam`, `ewam`.
- Multi-model request at −27.68/−48.48 (Campeche), `hourly=wave_height,wave_period`: response keys
  are `<variable>_<model>`. Values at hour 0 — `meteofrance_wave` 0.98 m / 8.1 s, `ecmwf_wam`
  1.30 m / 8.35 s, `dwd_gwam` 1.34 m / 8.5 s, `ecmwf_wam025` **all null (0/24 hours)**,
  `ncep_gfswave025` **0.0 for both height and period, all 24 hours**.
- `ncep_gfswave025` at five beaches: `0.00` at Campeche, Joaquina, Barra da Lagoa, Armação;
  `1.48 m` at Pântano do Sul.
- marola's **exact current** marine URL (no `models=`, 8 hourly variables, `forecast_days=2`) at
  Campeche returned 48/48 non-null for every field, `wave_height` `[0.98, 0.96, 0.96, ...]` —
  **identical to the `meteofrance_wave` column**, which is the evidence for §2's claim that marola
  is on MFWAM today. No code records this; it is inferred from matching values.
- Eight beaches × 48 hours × 3 models (384 beach-hours), buckets computed against
  `Swimability.scala`'s own constants: **110/384 (29%)** bucket disagreements, **372/384 (97%)**
  whale-flag disagreements. Per-beach: Jurerê 48/48 bucket disagreements (range 0.20–1.34 m at
  hour 0), Armação and Pântano do Sul 31/48 each, the other five 0/48.
- `Swimability.scala:27,28,39,75-77` — thresholds and penalties quoted in §2/§6 read from the file.
- `OpenMeteoClient.scala:24,25,33-37` — the two base URLs and the marine query string.
- `Board.scala:117,131` — the two `wave_m` emission sites.
- NCAR WRF & MPAS-A support forum, "GPU support for WRF/MPAS"
  (https://forum.mmm.ucar.edu/threads/gpu-support-for-wrf-mpas.12381/) — WRF GPU support
  "decentralized... not officially supported, unlike MPAS".
- ECMWF AIFS open weights, CC BY 4.0 (https://huggingface.co/ecmwf/aifs-single-1.0); operational
  25 February 2025; ~2.5 min for a 10-day forecast on one A100
  (https://www.ecmwf.int/en/about/media-centre/aifs-blog/2024/first-aifs-model-weights-are-now-open).
- Open-Meteo Marine docs (https://open-meteo.com/en/docs/marine-weather-api) — model table with
  MFWAM 0.08°, ECMWF WAM 9 km, ECMWF WAM 0.25°, NCEP GFS Wave 0.25° and 0.16°, DWD EWAM 0.05°,
  DWD GWAM 0.25°, ERA5-Ocean 0.5°, and the confirmation that NCEP GFS Wave is a NOAA product.

### Not checked

- **PNBOIA in every respect** — the CHM page was read only as a search-result summary, not fetched.
  Buoy count, which buoys are live, the download URL, the format and the licence are all unverified
  (§4.2, §11).
- Whether NCEP GFS-Wave's `0.0` returns are Open-Meteo's land-mask handling or NOAA's own output;
  only the symptom was observed. The design (§5.2) is safe either way.
- That MFWAM is *why* marola's values match it — the match is exact across 48 hours at one
  coordinate, which is strong but is not the same as Open-Meteo documenting its `best_match` choice.
- Whether ECMWF's 9 km WAM and the 0.25° variant differ in coverage for reasons other than the grid
  (`ecmwf_wam025` was simply null here).
- Open-Meteo's rate limits under a 3-model marine call across ~80 beaches; call *count* is unchanged,
  response size is not, and no throttling was observed in this session's ~20 requests.
- WAVEWATCH III's own licence terms and NOAA-EMC/WW3's clone requirements (MIP-0052's subject).
