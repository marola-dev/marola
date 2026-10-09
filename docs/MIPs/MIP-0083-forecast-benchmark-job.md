# MIP-0083: A forecast experiment in marola-app — IFS, GFS and WeatherNext sampled every 4 hours at registered points, scored against ground truth, with runs in MLflow and samples in the OODS lake

| | |
|---|---|
| **Status** | Draft — redefined from scratch by the owner on 2026-10-09 (the earlier Accepted text, its two amendments and the marola-eval move are superseded; `git log` keeps them) |
| **Author** | Hoffmann, from #724 and #723, redefined in the project thread on 2026-10-09 |
| **Created** | 2026-10-09 |
| **Phase** | None: R&D outside the phase list (#724). It writes to MIP-0075's B2 bucket, which already exists and is free; it provisions nothing. Phase 1 (the Telegram bot) is not done, and nothing here moves it |
| **Related** | #724 (the source issue), #723 (the study: points, protocol, paper), MIP-0075 (the OODS lake on B2 and its DuckDB store), MIP-0010 (MLflow and `RunLedger`), marola-dev/marola-site#99 (the page), h0ffmann/ww3-gpu#92 (the wind chapter), marola-dev/marola-app#72 (task 1, written before this redefinition) |
| **Effort** | XL — the OWB-South scope of §4.10 on top of: an sbt module with four forecast clients, two observation clients, a registry, a scorer, an MLflow ledger on B2 and lake tables; a scheduled workflow. Two new dependencies (`kyo-config`, `kyo-schema-json`, §4.9) plus MIP-0075's `duckdb_jdbc` |
| **Gain** | `user value` — marola.dev can say which forecast has been right lately at a given coast; `community/outreach` — an open, continuously updated comparison of physics and AI models on the Brazilian coast through a strong El Niño |
| **Effort vs Gain** | `do next` for v0, tasks 1–9 (schemas, two providers, stations, scorer, lake, MLflow, the cycle: local and free); `do later` for the OWB-South rows 10–16; `do when X lands` for 19 (X = WeatherNext 3 access) and 20 (X = MONAN output) |
| **Depends on** | MIP-0075's bucket and key (exist since 2026-10-05) and its `OodsStore` (task 5 there, reused here); Google's approval for WeatherNext 3 (task 17, a person's act; not needed for v0); #723 freezing the strong-wind thresholds before any score is published |
| **Blocked by** | none |
| **Risk** | A run is skipped or fetched twice and the record drifts from what each publisher actually issued, so every score answers a different question than it claims; §5.5 makes every expected run an explicit `sampled`, `backfilled` or `missing` row keyed by provider and run time |
| **Cost so far** | — |

### Readiness

| | |
|---|---|
| **Manually reviewed** | no |
| **Written by** | Hoffmann, with Claude Code |
| **Tasks** | [`MIP-0083.tasks.md`](./MIP-0083.tasks.md) |
| **Tests** | `SchemaSpec`, `GroundTruthSpec`, `SamplingRegistrySpec`, `ProtocolSpec`, `ForecastClientSpec`, `ObservationClientSpec`, `ScoreMonoidSpec`, `MatcherSpec`, `AnalogSpec`, `LakeSamplesSpec`, `CycleSpec`, `ExportSpec` in marola-app's `experiment` module (§7) |
| **Spec-kit** | none |
| **Issues** | not filed — Draft. marola-dev/marola-app#63–#71, filed for the superseded text, are closed or rewritten once this is Accepted |

## 1. Summary

marola-app gains an `experiment` sbt module, written in Kyo, that every 4 hours (adjustable)
samples 10 m wind and 2 m temperature forecasts at a registry of sampling points. v0 runs two
providers, ECMWF IFS and NOAA GFS, and adding one is a row in `providers.json`, not code
(§4.4). WeatherNext 2, WeatherNext 3 and the rest come after. It pairs every
matured forecast with what an instrument measured at that point, and keeps bias, RMSE and CRPS
over a 90-day window. Each cycle is an MLflow run (params, metrics, artifacts); every forecast and
observation sample is a row in MIP-0075's OODS lake on Backblaze B2. Nothing is published until
the ground-truth step has frozen the points it scores against. marola also issues its own
forecast, an analog ensemble built from the coast's own observations (§5.11), chosen so its errors
track the providers' as little as a skilful forecast can; it is scored as one more provider, and
every pair of providers' error correlation is measured.

## 2. Motivation

#723 asks whether a free AI model matches a physics model on this coast, and a one-off answer goes
stale with every model upgrade. Two facts make it urgent:

- **Open-Meteo serves only the latest WeatherNext 2 run**, with no single-run archive (v Open-Meteo
  single-runs page, 2026-10-09), so a run not sampled by the cycle that saw it is gone.
- **A strong El Niño is under way** (#723, citing NOAA CPC's 2026-10-08 advisory); its peak,
  October 2026 to March 2027, is the most informative window of the study.

marola-app already has an `Http` with fixture replay, a `JsonValue`, `RunLedger` with an MLflow
REST implementation (MIP-0010), and, with MIP-0075, a DuckDB store on B2. What it lacks is a
forecast client that takes `models=`, any observation client, and anything that runs on a schedule.

## 3. User-visible change

Nothing changes in the CLI, the bot or the board. New surfaces:

```bash
# in a marola-app checkout
just experiment-cycle      # one cycle against a local MLflow and a local lake directory
just experiment-rescore    # rebuild every score from the lake's samples alone
```

and, once points are `scored` (§5.8), `forecast-benchmark/latest.json` on marola-site's
`site-data` for the wind page (marola-site#99).

## 4. Data sources and dependencies reviewed

### 4.1 How wind forecasts are fetched (point 1)

| Route | Providers | Fit |
|---|---|---|
| **Open-Meteo APIs** (forecast, ensemble, single-run) | IFS (`ecmwf_ifs`), GFS (`ncep_gfs013`), WeatherNext 2 (`google_weathernext2_ensemble`) | **taken**: one JSON client for every Open-Meteo provider, `cell_selection=nearest` for every model, no key for non-commercial use, ~10,000 calls a day |
| Google's WeatherNext 3 channels (BigQuery, Earth Engine, GCS Zarr) | WeatherNext 3 | **taken for WN3 only**: not on Open-Meteo (§4.3) |
| The originals (ECMWF open data, NOAA NOMADS or AWS) | IFS, GFS | rejected as the main route: GRIB2 decoding on the JVM and a grid rule per provider; kept as #723 step 2's one-off cross-check of Open-Meteo's re-gridding |

Open-Meteo facts (v its docs pages, 2026-10-09):

- **IFS HRES 9 km**: 4 runs a day, hourly to 90 h, up to 15 days; single runs kept from 2024-03-14,
  so a missed run is backfilled.
- **GFS**: 0.11° for surface fields, hourly to 120 h then 3-hourly, 16 days, 4 runs a day; single
  runs kept from 2026-04-02. The id `ncep_gfs013` is from Open-Meteo's website source ⚠ until a
  live call records it.
- **WeatherNext 2**: 64 members, 0.25°, 6-hourly native (`temporal_resolution=native`), 15 days;
  Open-Meteo processes only the 00 and 12 UTC runs; no single-run archive.
- Each model's metadata JSON gives `last_run_initialisation_time` and `last_run_availability_time`;
  wait 10 minutes after the latter. The URL pattern is ⚠ until task 4 records it.

### 4.2 How forecast temperature is fetched (point 2)

The same requests carry `temperature_2m`: IFS, GFS and WeatherNext 2 all list it (v Open-Meteo
docs, 2026-10-09; WeatherNext 2 also lists its ensemble spread). No second client is needed.
WeatherNext 3's station-trained 2 m temperature comes at 0.05° (§4.3). Observed temperature comes
from the same METAR and INMET records as the wind (§4.5).

### 4.3 WeatherNext 3 (point 3)

Announced 2026-09-03: 64 members, a run every UTC hour, 15 days for the 00/06/12/18 cycles and
48 h for the others; 2 m temperature and dew point at 0.05°, surface wind at 0.1° (v WinBuzzer,
2026-09-05). Access (v developers.google.com/weathernext access guide, 2026-10-09): a Data Request
Form with a Google account, usually approved in 5–7 business days, one approval for Cloud
Storage, BigQuery and Earth Engine; researchers and students qualify; no paid contract needed.
BigQuery and Earth Engine carry the surface mean and percentiles, GCS the full ensemble as Zarr
(Requester Pays). Data older than 1 h is CC BY 4.0; real-time data is under Google's experimental
terms.

**Outreach (TODO, a person's act):** apply through the form, preferably as UFRJ (Hoffmann's Poli
account) and UFSC (LabECO, the ww3-gpu co-advisors), naming #723 and this MIP; record the answer
here. Until then WeatherNext 3 has no `providers.json` row. The client reads BigQuery's
statistics (mean and percentiles) at the registry's points: CRPS needs members, so WN3 is scored
on its mean until the Zarr route is costed ⚠.

### 4.4 Providers: two in v0, the rest added without code (point 5, amended 2026-10-09)

The owner set v0 at the minimum, two providers, with later ones arriving without friction. The
two are the ones that cost least and lose nothing when a cycle is missed:

| Provider | Publisher | Kind | Members | Runs sampled | Backfill | In |
|---|---|---|---|---|---|---|
| IFS HRES 9 km | ECMWF | physics | 1 | 00/06/12/18 | single-run API | **v0** |
| GFS | NOAA/NCEP | physics | 1 | 00/06/12/18 | single-run API | **v0** |
| WeatherNext 2 | Google DeepMind | AI | 64 | 00/12 | none: runs before it is added are lost | a `providers.json` row |
| AIFS, AIGFS, ICON, IFS ENS, GEFS | ECMWF, NOAA, DWD | AI and physics | 1–51 | per model | per model ⚠ | a `providers.json` row each |
| WeatherNext 3 | Google DeepMind | AI | 64 (BigQuery mean and percentiles) | 00/06/12/18 | Google's archive ⚠ | after access (§4.3): a new route |
| MONAN | INPE | physics | 1 | 00/12 | ⚠ | once INPE publishes output: a new route |

**What makes adding one frictionless.** A provider is data, not an enum case. A `providers.json`
row gives `id`, `route`, the route's model id (`ecmwf_ifs`, `ncep_gfs013`,
`google_weathernext2_ensemble`, …), the runs, members, lead range and `added_on`. `route` is the
one closed enum (`open_meteo`, `weathernext3_bigquery`, `monan`, `marola_analog`), with one
`ForecastSource` per route:

- **A model Open-Meteo serves** is one reviewed JSON row. No Scala change, no new spec beyond
  `ProtocolSpec` reading the row, and a recorded fixture for its first live call.
- **A model behind a new channel** (WeatherNext 3, MONAN) adds one route and one source.
- **Every score, the scorecard and MLflow** key on the provider id, so nothing downstream changes.
- **A provider starts on its `added_on` date.** Its window fills from there, and the scorecard
  shows it as `low_sample` until `min_n`.

The cost of starting with two is WeatherNext 2: Open-Meteo keeps only its latest run, so the El
Niño months before its row lands are not recoverable. Adding it is one row on the day the owner
wants it.

UKMO and others follow the same rule. The 2025
NHC verification (GFS's 120 h track error 362.3 n mi, the largest; DeepMind's ensemble best to
72 h) and Hurricane Isaias's split between AI and physics guidance (outcome pending) are context,
not evidence for this coast (v NHC Verification_2025.pdf; M. Lowry, 2026-10-07).

### 4.5 Ground truth

- **METAR** (`https://aviationweather.gov/api/data/metar?ids=SBFL&format=json`): no key, 100
  requests a minute, a custom User-Agent; **only the last 15 days** are kept (v aviationweather.gov
  data API page, 2026-10-09). Wind in knots, temperature in °C.
- **INMET automatic stations**: hourly 10 m wind and 2 m temperature; the API at
  `apitempo.inmet.gov.br` may need a token ⚠; whether its wind is a 10-minute or hourly mean ⚠.
- **NOAA CPC's Niño-3.4** anomaly and ENSO status, stored with every cycle (#723) ⚠ file not
  fetched.

### 4.6 MLflow on B2 (point 6)

MLflow stores artifacts on Backblaze B2 natively: `b2://<bucket>@s3.<region>.backblazeb2.com/<path>`
with `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` from a B2 **application** key (the master key
does not work with the S3 API), `AWS_DEFAULT_REGION=us-east-005`, and boto3 beside MLflow (v
MLflow artifact-store docs, 2026-10-09). The tracking store stays a SQLite file, downloaded and
uploaded per cycle as MIP-0075 does its catalog. marola pins MLflow 3.16.0.

### 4.7 B2 from Scala (point 7)

MIP-0075 §4.5 already settled it: `org.duckdb:duckdb_jdbc` 1.5.6.0 with the `httpfs` and
`ducklake` extensions, an `OodsStore` trait, wrapped in Kyo at the boundary (`Sync.defer` per
call, `Scope` for the connection), B2 reached as S3 at `s3.us-east-005.backblazeb2.com`, the secret
built from the environment and never `PERSISTENT`. This MIP adds tables to that lake (§5.6) and no
second S3 client. Compared: the AWS SDK v2 S3 client with an endpoint override (works, ~10 MB of
jars, but writes objects, not tables); `kyo-sql` (no DuckDB dialect at RC7, MIP-0075 §4.5).

### 4.8 Window, cadence and registry (point 9)

The experiment window is **90 days**, the cadence **every 4 hours**; both are values in
`protocol.json`, not code, so a change is a reviewed commit whose git sha every MLflow run
records. The cron line is the one place the cadence also lives (§5.9).

### 4.9 Kyo modules (point 8)

Kyo 1.0.0-RC7, marola-app's pin. Its repository at tag `v1.0.0-RC7` (1d54c02, 2026-09-28) has 70
modules; 62 are on Maven Central as `io.getkyo:<m>_3:1.0.0-RC7` (each `.pom` fetched 2026-10-09;
`kyo-bench`, `kyo-compat`, `kyo-examples`, `kyo-scheduler-finagle`, `kyo-test`, two test kits and
the website are not published).

| Module | Use here | |
|---|---|---|
| `kyo-core`, `kyo-direct`, `kyo-combinators` | effects at the I/O boundary, direct style | already in marola-app |
| `kyo-prelude`, `kyo-data` | `Abort`, `Env`, `Var`, `Emit`; `Chunk`, `Maybe`, `Result` | pulled in by `kyo-core` |
| `kyo-config` | `StaticFlag` for the lake path, MLflow URI and dry-run switch | **add**; the protocol stays a file (§4.8) |
| `kyo-schema`, `kyo-schema-json` | `derives Schema` codecs for the registry, protocol and export | **add**, replacing hand-written readers like task 1's; checked against the jar before use (`.claude/agents/jar-verifier.md`) |
| `kyo-stats-registry`, `kyo-stats-otlp` | counters per cycle | later, if MLflow's metrics are not enough |
| `kyo-http` | HTTP client | not now: marola's `Http` has the fixture seam every spec uses, and RC7's client has no proxy class (MIP-0075 §4.6) |
| `kyo-flow` | durable workflows | rejected: a cycle is idempotent and re-run from the lake (§5.5), so a workflow engine's persistence buys nothing |
| `kyo-sql`, `kyo-sql-sqlite` | SQL | not usable: no DuckDB dialect; MLflow's SQLite is MLflow's, read only through its REST API |
| `kyo-stm`, `kyo-actor`, `kyo-reactive-streams` | concurrency | not needed: one cycle, one writer |
| the rest (`kyo-ai`, `kyo-browser`, `kyo-mcp`, `kyo-pod`, `kyo-ui`, `kyo-zio`, …) | — | unrelated to this job |

### 4.10 Operational WeatherBench for the South: what Brazilian open data can and cannot replicate (amended 2026-10-09)

The long-term goal (owner, 2026-10-09) is a South-region counterpart of Brightband's
[Operational WeatherBench](https://owb.brightband.com/methodology) (OWB). What OWB does (v its
methodology page, 2026-10-09):

- It scores IFS ENS and HRES, AIFS ENS, GFS, GEFS, AIGFS, AIGEFS, WeatherNext 2 and 3 and
  Brightband's own runs of GraphCast, Aurora 1.5 and Atlas, against an ERA5 climatology baseline.
- It runs every 00/06/12/18 UTC cycle at leads every 6 h to 360 h, with a scorecard at days 1, 3,
  5, 7, 10 and 15 and a 12Z-only ranking.
- Truth is each model's **own analysis** on a 0.25° global grid, with no regridding and areas
  weighted by cos(latitude). IFS analysis is the truth for WeatherNext and the climatology, ERA5 for
  precipitation, and no station, buoy or radiosonde is used.
- Its metrics are RMSE, ACC for Z500, 850 hPa vector-wind RMSE and 10 m wind speed. For 24 h
  precipitation it uses SEEPS. For ensembles it uses fair CRPS, spread, debiased ensemble-mean
  RMSE and spread-skill ratio, from K = 16 strided members.
- Windows are aggregated by component: squares are averaged then rooted, and the spread-skill ratio
  is rebuilt from window components.
- The scoring library is WeatherBench-X (Python), and the dashboard is CC BY 4.0.

The Brazilian sources it can draw on:

| Source | What | Access (checked 2026-10-09) |
|---|---|---|
| INMET automatic stations | hourly 10 m wind, gust, 2 m temperature, pressure, precipitation | `apitempo.inmet.gov.br` ⚠ token, §4.5 |
| METAR (DECEA airports, via aviationweather.gov; IEM archive) | 10 m wind, temperature, pressure | §4.5; DECEA's own REDEMET API needs a key ⚠ |
| **PNBOIA** (Marinha/CHM) moored and drifting buoys | wind, pressure, waves, SST; offshore SC, RS (three new Pelotas Basin stations in 2026), Santos Basin | free download through the BNDO, also sent to the GTS; ~16 million qualified observations 2002–2026 (v marinha.mil.br/chm/node/70352 and /chm/bndo/acesso). File format, wind averaging and latency ⚠ |
| SIMCOSTA (FURG) coastal buoys | wind, waves, met | ⚠ not reached; access unverified |
| **CHM METAREA V warnings** (Navy) | gale and near-gale warnings by area (force 7–9), issue time and validity in UTC, pt and en text | a web page with no archive (v marinha.mil.br/chm/node/1046602); must be archived by us from day one |
| BNDO meteorological stations and radiosondes | station and upper-air records | free download or the request form; a Termo de Compromisso may restrict redistribution (v /chm/bndo/acesso) |
| Radiosondes Florianópolis and Porto Alegre | 850 hPa wind and temperature, 00/12 UTC | IGRA or Wyoming ⚠ station ids not checked |
| **MERGE** (INPE/CPTEC) | daily gauge-plus-GPM precipitation, 0.1°, South America, from 2000-06-01 | GRIB2, CC BY 4.0, BDC STAC `prec_merge_daily-1` (v data.inpe.br, 2026-10-09) |
| **MONAN** (INPE) | Brazil's own global model, 10 km, two runs a day, 11 days, operational since September 2026 | public output ⚠ not found (v acessa.com, 2026-09-03) |
| ERA5 | climatology baseline | Open-Meteo's historical API (point) or ARCO-ERA5 ⚠ |

**What this experiment can replicate**

| OWB element | Here | How |
|---|---|---|
| Models IFS HRES, GFS, AIGFS, AIFS (deterministic), WeatherNext 2, WeatherNext 3 | yes | Open-Meteo (§4.1) and Google (§4.3); each a `providers.json` row (§4.4) |
| IFS ENS, GEFS, AIFS ENS, AIGEFS members | partly | Open-Meteo's ensemble API carries IFS ENS, GEFS and AIFS ENS ⚠ per model, at points |
| Brazil's own model (MONAN), which OWB lacks | yes, once INPE publishes output | task 20 |
| 00/06/12/18 cycles, 6-hourly leads to 360 h, scorecard days 1/3/5/7/10/15, a 12Z ranking | yes | §5.5, lead range raised to 360 h |
| RMSE, bias, fair CRPS, spread, debiased ensemble-mean RMSE, spread-skill ratio, K = 16 strided members | yes | all are sums, so all are score-monoid cells (§5.2), and window aggregation matches OWB's by construction |
| 10 m wind speed as a scalar, ensemble mean of member speeds | yes | OWB's rule, adopted |
| 850 hPa vector-wind RMSE | at the radiosonde points only | task 13 |
| 24 h precipitation SEEPS | yes, against MERGE at stations and over the South box | a 2000–2025 MERGE climatology gives SEEPS's dry probability and thresholds; task 15 |
| Climatology baseline | yes, per point | ERA5 point series, or the station's own record, as the skill floor |
| CC BY 4.0 scorecard, open method | yes | §5.8, with each source's licence carried through |

**What it adds that OWB does not have**

- Verification against **observations**: stations, buoys, radiosondes and MERGE, not only each
  model's analysis.
- **Offshore wind truth from PNBOIA.**
- **The Navy's warnings as a forecast to verify.** Hit rate, false-alarm ratio and lead of CHM
  force ≥ 7 warnings per area, against PNBOIA and coastal stations, with each model's probability
  of force ≥ 7 scored alongside.

**What it cannot do, and why**

- **Run GraphCast, Aurora or Atlas.** That needs GPUs on every cycle: Phase 2 money (MIP-0057),
  with no free path.
- **Score each model against its own analysis on a grid,** which is OWB's default. Open-Meteo serves
  points. Grids mean GRIB2 from ECMWF, NOMADS or AWS and WeatherNext through Earth Engine, which is
  GRIB decoding and gigabytes per cycle. Not in the Kyo module. A later marola-ml job could run
  WeatherBench-X on a South crop (about 20–35°S, 40–58°W) if the owner wants grid scores; that is
  task 21, a decision, not a default.
- **ACC for Z500, global and hemispheric regions:** these need the grid path above.
- **Compare numbers with OWB's directly.** Station truth includes representativeness error that
  analysis truth does not. A score here answers "how close to what was measured at this
  coast", not "how close to the model's own best estimate". Every page says so.
- **Reconstruct the Navy warning history.** No archive is published, so the record starts on the
  day task 14 lands. Area polygons for the verification are ⚠ until CHM's chart is digitised.
- **Guarantee buoy coverage.** PNBOIA has run up to nine buoys at once (2016, per CHM). A missing
  buoy is a `missing` row, never a gap filled from a model.
- **Precipitation for WeatherNext 3**, as at OWB, and hourly precipitation truth: MERGE is daily
  (13 UTC to 12 UTC).
- **Redistribute BNDO data covered by a Termo de Compromisso.** Such rows stay out of the public
  export and are scored privately.

## 5. Design

### 5.1 The flow

```mermaid
flowchart LR
  reg[Sampling registry<br/>and protocol] --> fc[Forecast clients<br/>IFS · GFS · WN2 · WN3]
  reg --> oc[Observation clients<br/>METAR · INMET]
  fc --> fs[(forecast_sample<br/>OODS lake on B2)]
  oc --> os[(observation_sample<br/>OODS lake on B2)]
  fs --> m[Matcher<br/>same point, same valid time]
  os --> m
  m --> sc[Scorer<br/>monoid accumulators]
  sc --> ml[(MLflow run<br/>per cycle)]
  os --> an[marola analogs<br/>past observations]
  fs --> an
  an --> fs
  sc --> gate{Points scored?<br/>ground truth frozen}
  gate -- yes --> ex[Export to site-data]
  gate -- no --> hold[Archive only]
```

The registry decides where to sample; clients write samples to the lake; the matcher and scorer
read only the lake, so a score is always recomputable from stored samples (§5.5); MLflow records
each cycle; the export waits for the ground-truth gate (§5.8). marola's analog model (§5.11) reads
the observation history and today's large-scale forecast and writes its own forecast back to the
lake as one more provider.

### 5.2 Category-theory principles, in Kyo

The module is written so that its laws are testable, not as theory for its own sake:

- **Scores are commutative monoids.** A score cell (provider, point, lead, bin) keeps `n`, `Σe`,
  `Σe²`, `Σ|e|` and the CRPS sums; `combine` adds them and `empty` is zeros. Bias, RMSE and mean
  CRPS are read off at the end. Per-day partials then combine into any window in any order, so the
  90-day score is a fold over 90 daily cells, `rescore` equals the logged score by construction,
  and `ScoreMonoidSpec` checks associativity, identity and commutativity on fixed cases.
- **Providers are natural transformations into one sample type.** Each client maps its native
  payload into `ForecastSample` (provider, run, point, valid time, lead, variable, value, members);
  unit conversion is a `map` on the value, and the laws `map(identity) == identity` and
  `map(f andThen g) == map(f) andThen map(g)` hold by being plain case-class copies.
- **Matching is a pullback.** Forecasts and observations are joined over the shared key
  (point, valid time); nothing else may pair two samples.
- **The cycle is Kleisli composition in Kyo.** `fetch: Point => Chunk[ForecastSample] < (Sync & Abort[FetchError])`,
  `store`, `match`, `score` and `log` compose with `map`/`flatMap`; the effect row in each
  signature is the only place I/O may happen. Pure parts (registry, matcher, scorer) carry no Kyo
  effect at all (marola-app's `scala.md`).

### 5.3 Where the code lives

`experiment/` in marola-app, `dependsOn(core, local)` for `Http`, `JsonValue`, `RunLedger` and
`OodsStore`. marola-app#72's `verify` project is renamed `experiment` and its `GroundTruth` becomes
part of the registry (task 2).

| File (`experiment/src/main/scala/marola/experiment/`) | What |
|---|---|
| `GroundTruth.scala` | the instruments and their lifecycle (from #72) |
| `SamplingRegistry.scala` | sampling points (§5.4) |
| `Protocol.scala` | providers, variables, leads, bins, window, cadence, `min_n` |
| `Provider.scala` | `Provider` (a `providers.json` row) and `enum Route(label)`: `OpenMeteo`, `WeatherNext3BigQuery`, `Monan`, `MarolaAnalog` (§4.4, §5.11) |
| `Analogs.scala` | §5.11: the analog library, the search and the analog members |
| `ForecastSource.scala` | `trait ForecastSource { def latestRun: Maybe[Instant] < (Sync & Abort[FetchError]); def fetch(run: Instant, points: Chunk[SamplingPoint]): Chunk[ForecastSample] < (Sync & Abort[FetchError]) }` |
| `OpenMeteoForecasts.scala` | the v0 route; `WeatherNext3Forecasts.scala` and `MonanForecasts.scala` later |
| `Observations.scala` | `MetarClient`, `InmetClient` → `ObservationSample` |
| `Score.scala` | the monoid (§5.2) |
| `Matcher.scala`, `Scorer.scala` | §5.5 |
| `LakeSamples.scala` | the lake tables over `OodsStore` (§5.6) |
| `Cycle.scala`, `Export.scala`, `Main.scala` | `cycle`, `rescore`, `screen`, `export` |

### 5.4 The sampling-point registry (point 9)

`experiment/src/main/resources/experiment/sampling-points.json`, checked by `SamplingRegistrySpec`:
every point has `id`, `lat`, `lon`, `kind` (`station`: tied to a ground-truth instrument and
scorable; `coast`: a beach or offshore point, sampled and never scored), `ground_truth` (the
instrument id, for `station`), `added_on` and `retired_on`. A point is sampled from `added_on`; a
retired point keeps its rows. The ground-truth file (#72) stays the source of the instruments'
status; the registry only says where forecasts are sampled.

### 5.5 Sampling, matching and scoring

- **Grid rule:** the nearest cell for every provider (`cell_selection=nearest`; nearest on WN3's
  grid); the cell centre is stored with every sample, and the station-to-cell distance and grid
  spacing are reported beside every score.
- **Time grid:** valid times 00/06/12/18 UTC (WeatherNext 2's native step); leads every 6 h from 6
  to 360 h, as OWB scores; scorecard days 1, 3, 5, 7, 10 and 15 (§4.10), and a 12Z-only ranking.
- **Ensembles:** K = 16 members drawn at strided positions, as OWB does; scored with fair CRPS,
  spread, debiased ensemble-mean RMSE (MSE − s²/K) and the spread-skill ratio rebuilt from window
  sums.
- **Runs:** each cycle reads every provider's latest run time, samples a run not yet in the run
  index, then walks the expected runs since the last cycle: a missed IFS or GFS run is
  `backfilled` from the single-run API, a missed WeatherNext 2 run is `missing` with a reason.
  A missing run is a row, never a gap filled from a neighbour.
- **Pairs:** a METAR within ±10 min of the valid time, or the INMET record for that hour. Wind
  speed (m/s) and direction (scored only when observed speed ≥ 2 m/s), 2 m temperature (°C).
- **Scores:** bias, RMSE, CRPS (standard kernel form over members; equal to absolute error for a
  single run), direction MAE, `n`, `days`, `low_sample = n < min_n`; bins by observed wind (calm
  < 5.5, moderate 5.5–10.8, strong ≥ 10.8 m/s); windows 7, 30 and 90 days.
- **Error correlation:** each cell also keeps `Σeᵢeⱼ` for every pair of providers on shared
  pairs, so the error correlation matrix is read off any window like the other scores (§5.11).

### 5.6 What goes where: MLflow and the lake (points 6 and 7)

| Store | Holds | Why there |
|---|---|---|
| OODS lake on B2, tables `experiment_point`, `forecast_sample`, `observation_sample`, `run_index` | every sample, partitioned by provider and month | the record the paper and `rescore` read; queryable with DuckDB by anyone with read access |
| MLflow (SQLite file and artifacts under `b2://…/mlflow/`) | one run per cycle: params, the cycle's daily score cells as metrics, the export | the comparison UI and lineage of each cycle |

The lake writes go through MIP-0075's `OodsStore` and share its `oods-lake` concurrency group, so
there is one writer for the lake and MLflow's file alike.

```mermaid
flowchart TB
  exp[Experiment: forecast-benchmark-v1<br/>tags: protocol sha, registry sha] --> r1[Run: cycle 2026-10-09T04:17Z]
  exp --> r2[Run: cycle 2026-10-09T08:17Z]
  r1 --> p[Params<br/>providers, cadence, window,<br/>app_sha, nino34, enso_status,<br/>sampled, backfilled, missing]
  r1 --> mt[Metrics<br/>rmse.wind.90d.SBFL.gfs, step = lead h<br/>first cycle after 00 UTC only]
  r1 --> a[Artifacts<br/>scores.json, export.json,<br/>run_index delta]
```

One experiment per protocol version: a change to the protocol or the registry starts
`forecast-benchmark-v2`, so no run mixes two protocols. Metrics are logged once a day (about 900
rows a day per provider at two variables and four headline leads ⚠ to measure); `scores.json`
keeps every window, bin and lead.

### 5.7 Ground truth first

marola-app#72's `ground-truth.json` and `docs/4-reference_ground-truth.md` stay as written: the
candidate instruments, #723's strong-wind thresholds (placeholders until frozen) and the rules the
loader enforces. Every candidate is sampled from day one; none is scored until `scored`.

### 5.8 Publishing (point 4)

The export (`forecast-benchmark/latest.json` on marola-site's `site-data`, the schema vendored by
marola-site#99) is written only when at least one point is `scored`, which needs #723's frozen
thresholds and the screen (task 6). Before that, cycles sample, store and log, and the export
step is skipped with a logged reason. Each later protocol version's results are also published as
a dated Zenodo dataset by the study (#723, MIP-0079), not by this job.

### 5.9 One cycle on GitHub Actions

`.github/workflows/experiment.yml` in marola-app: `schedule: cron "17 */4 * * *"` (off minute 0),
`workflow_dispatch`, `concurrency: oods-lake` (MIP-0075's group), secrets `B2_KEY_ID` and
`B2_APPLICATION_KEY` (MIP-0075's ETL key, or a key scoped to the experiment's prefixes ⚠). Steps:
JDK 25, `sbt experiment/assembly`, download `mlflow.db` and the lake catalog with their versions,
`pip install mlflow==3.16.0 boto3`, start `mlflow server` on localhost with
`--artifacts-destination b2://…`, run `cycle`, upload `mlflow.db` and the catalog back, then the
export (§5.8). No cloud resource is created; no IaC is needed.

### 5.10 What is deterministic

Everything, marola's analog model included. No LLM touches the experiment, its scores or the export.

### 5.11 marola's own forecast: an analog ensemble of local observations (amended 2026-10-09)

The owner asked for marola's own wind forecast with errors that are not highly correlated with
the providers'. A blend of IFS, GFS and WeatherNext (the first version of this section) fails that
by construction: it is a weighted average of the very errors it should avoid. Any skilful
forecast correlates with the truth, so what can be lowered is the **error** correlation, and only
with information the global models lack. At a coastal station that is the local wind climate:
sea breeze, terrain channelling and the instrument's exposure, at scales a 9–25 km grid cannot
resolve. The pick is an **analog ensemble** (AnEn; Delle Monache et al. 2013,
doi:10.1175/MWR-D-12-00281.1 ⚠ not re-read for this amendment), `marola_analog`.

- **How it forecasts.** For a station, lead and issue time, find the k = 20 past forecasts most
  similar to today's in a few large-scale predictors, and issue the 20 wind and temperature
  values **observed** at those analogs' valid times. The output is drawn from what the
  instrument actually measured, so a bias, a missed sea breeze or a smoothed peak in the model
  does not pass through.
- **Predictors chosen to stay away from the providers' surface wind:** IFS mean sea-level
  pressure gradient across the point, 850 hPa wind, 2 m land-sea temperature contrast, hour
  of day and day of year; plus, up to 24 h lead, the latest observation. The 10 m wind is left out
  on purpose: it is the field the providers get wrong locally, and matching on it would copy
  their errors. Whether Open-Meteo's IFS single-run API serves 850 hPa wind and MSL pressure is ⚠
  until task 16 records it.
- **The library:** IFS single runs from 2024-03-14 (Open-Meteo's archive, §4.1) at each station,
  paired with the Iowa Environmental Mesonet METAR archive (§11): about 2.5 years, roughly 3,600
  runs per station and lead. The backfill is about 3,600 calls per station, spread over days to
  stay inside Open-Meteo's daily limit.
- **Search:** distance is the weighted sum of standardised predictor differences in a ±3 h window
  around the lead (Delle Monache's metric), with weights fixed by #723 on the first year and then
  frozen. With a few thousand candidates the search is a plain sort in Scala, with no index and
  no ML library.
- **Strictly causal.** The library holds only runs whose valid time is before the issue time, so
  every member is something a forecaster could have known then. `AnalogSpec` checks that a later
  observation changes nothing.
- **An ensemble by construction:** 20 members, scored with CRPS against WeatherNext's 64, so
  its spread is tested, not assumed.
- **Measured, not claimed.** §5.5's error-correlation cells (`Σeᵢeⱼ` per pair of providers,
  another monoid) give the correlation of `marola_analog`'s errors with each provider's per
  station and lead, logged to MLflow and shown beside every score.
- **What to expect.** Lower error correlation and a real chance to win at 0–48 h, where local
  effects dominate. At 5–10 days its skill comes from IFS's large-scale fields, so the
  correlation with IFS rises with lead; the export shows both curves.
- **Wind direction** is the circular mean of the members' directions, never computed from
  averaged u and v.
- **Coast points** have no observations and get no analog forecast.
- **MLflow:** each run logs `analog_k`, `analog_predictors`, `analog_weights_sha` and the library
  size as params. A change to any of them starts a new experiment version (§5.6).

### 5.12 The schemas come first (amended 2026-10-09)

Every later task writes or reads one of these, so they land first as task 1, with no client and
no scorer. Each is a Scala 3 case class or enum in `experiment/.../schema/` with `derives Schema`
(`kyo-schema`). The JSON form goes through `kyo-schema-json`'s `Json.encode` and `Json.decode`,
and the lake form is a DuckLake table whose DDL is generated from the same `Schema` and checked
in. A schema change is a new version, never an edit.

| Schema | Fields (abridged) | Stored as |
|---|---|---|
| `Provider`, `Route`, `Variable`, `Unit` | a provider row (id, route, model id, runs, members, leads, added_on); enums with `label` and `fromLabel` | `providers.json`; codes in every row |
| `Instrument` | id, network (`inmet`, `metar`, `pnboia`, `simcosta`, `radiosonde`, `merge`), lat, lon, height, averaging, licence, status | `ground-truth.json` |
| `SamplingPoint` | id, lat, lon, kind (`station`, `buoy`, `coast`, `upper_air`), instrument id, added_on, retired_on | `sampling-points.json` |
| `Protocol` | version, variables, leads, scorecard days, window, cadence, K, `min_n`, bins | `protocol.json` |
| `ForecastSample` | provider, run, point, valid time, lead, variable, member (or none), value, cell lat/lon, source URL | `forecast_sample` |
| `ObservationSample` | instrument, valid time, variable, value, averaging, QC flag | `observation_sample` |
| `RunIndexRow` | provider, run, state (`sampled`, `backfilled`, `missing`), reason, fetched at | `run_index` |
| `ScoreCell` | provider, point, variable, lead, bin, day, n, Σe, Σe², Σ\|e\|, fair-CRPS sum, spread sum, Σeᵢeⱼ | `score_cell` |
| `NavyWarning` | number, area, force, gust, valid from/to, issued at, raw text sha | `navy_warning` |
| `Scorecard` | protocol version, window, rows per provider × variable × day, licences | `forecast-benchmark/latest.json` and its JSON Schema for marola-site |

`SchemaSpec` round-trips one fixture of each and checks the generated JSON Schema and DDL
against the checked-in files.

### 5.13 Kyo at its current release

Kyo 1.0.0-RC7 is the newest release: Maven Central's `kyo-core_3` metadata lists it as `latest` on
2026-09-28 (checked 2026-10-09). The experiment uses it the way its current API intends. Names
below were checked in the RC7 source and are re-checked against the jar before use.

- **`Async.foreach(points, concurrency = n)`** fetches points in parallel, bounded per provider.
- **`Meter.initRateLimiter`** keeps aviationweather.gov at 100 requests a minute and Open-Meteo
  inside its daily limit. `Retry(Schedule…)` (kyo-data's `Schedule`) backs off on a 429 or a 5xx.
- **`Abort[FetchError]`** gives typed failures, so a missing run becomes a `RunIndexRow`, never an
  exception. Results stay `Result`, values that may be absent stay `Maybe`, and samples travel as
  `Chunk`.
- **`Scope`** owns the DuckDB connection and the MLflow subprocess. `Clock` is the only source of
  "now", so tests fix the time.
- **`Layer` and `Env`** wire the sources, the store and the ledger. A test swaps in fixture layers,
  with no mocks.
- **`kyo-config`'s `StaticFlag`** carries the lake path, MLflow URI and dry-run switch. `Log`
  carries per-cycle lines.
- **`Stream`** pages lake reads for `rescore`, so a 90-day window is never loaded at once.
- **Outside Kyo:** the registry, protocol, matcher, score monoid, analogs and scorecard are pure
  and carry no Kyo effect.

## 6. Scoring / safety impact

None. `Swimability.score` reads nothing this produces; using the winning model there is a later
decision made with this data (#724).

## 7. Verification plan

munit specs in `experiment/`, fixtures replayed through `Http.withTransport`, the lake on a local
directory as MIP-0075 tests it:

- `SchemaSpec`: `every_schema_round_trips`, `json_schema_matches_checked_in`,
  `ddl_matches_checked_in`, `unknown_label_is_malformed`.
- `GroundTruthSpec` (#72's eight cases).
- `SamplingRegistrySpec`: `station_point_needs_ground_truth`, `coast_point_never_scored`,
  `retired_point_keeps_rows`.
- `ProtocolSpec`: `cadence_and_window_read_from_file`, `unknown_provider_is_malformed`.
- `ForecastClientSpec`: `ifs_gfs_wind_and_temperature_parsed`, `weathernext2_native_steps_and_64_members`,
  `cell_selection_nearest_sent`, `gfs_gap_backfilled_from_single_run`, `weathernext2_gap_recorded_missing`.
- `ObservationClientSpec`: `metar_knots_to_ms_and_temperature`, `inmet_hour_parsed`.
- `ScoreMonoidSpec`: `combine_is_associative`, `empty_is_identity`, `combine_is_commutative`,
  `ninety_daily_cells_equal_one_window`, `crps_of_single_run_is_abs_error` — values worked by hand.
- `MatcherSpec`: `pairs_only_same_point_and_valid_time`, `metar_outside_10_min_is_missing`.
- `LakeSamplesSpec`: `samples_round_trip_through_local_lake`, `rerun_cycle_writes_no_duplicates`.
- `CycleSpec` with a recording `RunLedger`: `cycle_logs_params_and_artifacts`,
  `export_skipped_until_a_point_is_scored`, `failed_fetch_ends_run_failed`.
- `ExportSpec`: `export_matches_schema`.
- `AnalogSpec`: `twenty_nearest_by_weighted_distance`, `members_are_observed_values`,
  `later_observation_does_not_change_forecast`, `ten_m_wind_not_a_predictor`,
  `direction_is_circular_mean` — on a hand-built library of 30 runs.
- `ScoreMonoidSpec` also: `error_correlation_of_identical_errors_is_one`,
  `fair_crps_matches_weatherbenchx_case`, `debiased_ens_mean_rmse`, `spread_skill_rebuilt_from_window`,
  `seeps_uninformed_is_one_perfect_is_zero`, `strided_k16_members`.
- `MarineObservationSpec` (PNBOIA, SIMCOSTA), `UpperAirSpec`, `NavyWarningSpec`
  (`metarea_v_text_parsed_pt_and_en`, `warning_hit_and_false_alarm_counted`), `MergePrecipSpec`.

Live, after the workflow lands: two dispatches back to back (the second waits on `oods-lake`), and
`just experiment-rescore` equal to the last cycle's `scores.json`. **Done** when 7 days of
unattended cycles show in MLflow with no unexplained gap in `run_index`.

## 8. Risks, limitations, and honest caveats

- **Open-Meteo re-grids the originals**; #723 step 2's cross-check stays a task of the study.
- **Point verification favours the finer grid** near a coast; the cell distance is reported, not
  removed.
- **METAR and INMET measure differently** (10-minute mean vs possibly hourly ⚠); scores are split
  by instrument kind.
- **GitHub drops scheduled runs under load**; a dropped cycle loses no WeatherNext 2 run unless
  three in a row are dropped (runs are 12 h apart).
- **marola's analog model can look better than it is.** A library that peeks at the future, or
  predictor weights tuned on the scored window, would flatter it. The library is strictly causal
  and the weights are frozen on a year outside the window (§5.11).
- **Its library is short.** About 2.5 years
  holds few analogs for rare states; strong-wind members are sparse, and the strong bin's score
  will say so.
- **It is conditioned on IFS.** If IFS's archive or grid changes (a cycle upgrade), old analogs
  stop matching new forecasts. The library restarts from the upgrade date, which the run index
  records.
- **The ensemble mean is not a forecast anyone issued**; CRPS is the fair comparison and the
  export shows both.
- **WeatherNext 3 may never be granted**, or only its statistics; the experiment does not wait
  for it (§4.4).
- **v0's two providers are both physics models,** so v0 answers "IFS or GFS here", not "AI or
  physics". The AI question starts on the day WeatherNext 2's row lands, and its pre-row runs are
  gone.
- **Sharing the lake's writer group** makes the experiment wait behind a long ETL; at 4-hour
  cadence that costs minutes.

## 9. Alternatives considered

- **Do nothing / #723 alone:** stale at the next model upgrade, and the El Niño WeatherNext 2
  members are lost.
- **A new repo (marola-eval) in Scala, Rust or Haskell:** proposed earlier on 2026-10-09 and
  withdrawn by the owner; the app keeps its HTTP, JSON and ledger code by staying here.
- **GCS for MLflow, with WIF and Besom:** a second cloud for the one bucket B2 already provides.
- **An always-on MLflow:** Phase 2 (MIP-0057).
- **GRIB from the originals:** §4.1.
- **`kyo-flow` for durability:** §4.9.
- **A stacked blend of the providers for marola's forecast** (the first version of §5.11): its
  errors are a weighted average of the providers', the opposite of what the owner asked for.
- **A regional model of marola's own (WRF):** paid compute every cycle, and its boundary
  conditions come from GFS or IFS, so its errors inherit theirs.
- **A learned post-processor (gradient boosting, a neural net) on the providers' output:** more
  parameters than 2.5 years of 6-hourly pairs support, and correlated with its inputs; marola-ml
  if the analog model leaves skill on the table.

## 11. Open questions

- **Which 5-year archive does the strong-wind screen read, given METAR keeps 15 days?**
  **Default:** the Iowa Environmental Mesonet METAR archive and INMET's historical files ⚠,
  checked in task 6; #723 freezes the thresholds.
- **Does INMET's API need a token?** **Default:** if yes, an Actions secret `INMET_TOKEN` the owner
  requests; METAR-only points run meanwhile.
- **WeatherNext 3: statistics or full ensemble?** **Default:** BigQuery statistics (mean and
  percentiles) until the Zarr route's Requester Pays cost is stated and confirmed by the owner.
- **A B2 key scoped to the experiment, or MIP-0075's ETL key?** **Default:** a scoped application
  key, created by the owner.
- **Is `min_n = 30` right?** **Default:** yes until #723 calibrates it.
- **Is marola's analog forecast shown on marola.dev, and under what name?** **Default:** not
  before it beats the best single provider's 30-day CRPS at a `scored` point for some lead. Then
  it is shown as "marola (from this station's own record)", marked experimental, with its error
  correlation beside it.
- **Which predictor weights?** **Default:** equal weights until #723 fits them on the first
  library year, then frozen.

## Appendix

### Checked live

- https://open-meteo.com/en/docs and its ecmwf, gfs, google-weathernext, single-runs and licence
  pages, 2026-10-09: ids, grids, steps, runs, `temperature_2m`, backfill dates; ids also read from
  github.com/open-meteo/open-meteo-website at its 2026-10-09 tip.
- https://developers.google.com/weathernext/guides/access-forecast, 2026-10-09: WeatherNext 3
  access, channels, licence. https://winbuzzer.com/2026/09/05/google-weathernext-3-hourly-runs-finer-local-forecasts-xcxwbn/,
  2026-10-09: WN3's runs and grids.
- https://mlflow.org/docs/latest/self-hosting/architecture/artifact-store/, 2026-10-09: `b2://`
  URIs, application key, `AWS_DEFAULT_REGION`.
- github.com/getkyo/kyo at `v1.0.0-RC7` (1d54c02) and each module's `.pom` on Maven Central,
  2026-10-09: the module list of §4.9.
- https://aviationweather.gov/data/api/, 2026-10-09: METAR endpoint, limits, 15 days.
- https://www.nhc.noaa.gov/verification/pdfs/Verification_2025.pdf and M. Lowry's 2026-10-07
  post, 2026-10-09: §4.4's context.
- MIP-0075 §4.3–§4.5 (B2, DuckLake, DuckDB from Scala) at marola `main`, 2026-10-09.

- https://owb.brightband.com/methodology, 2026-10-09: OWB's models, truth, metrics, leads,
  aggregation and caveats (§4.10).
- https://www.marinha.mil.br/chm/bndo/acesso, /chm/node/70352 and /chm/node/1046602, 2026-10-09:
  BNDO access and terms, PNBOIA, METAREA V warnings.
- https://data.inpe.br/bdc/stac/v1/collections/prec_merge_daily-1, 2026-10-09: MERGE.
- Maven Central `io/getkyo/kyo-core_3/maven-metadata.xml`, 2026-10-09: RC7 is `latest`; §5.13's
  names in the RC7 source (`Async.foreach`, `Meter.initRateLimiter`, `Retry`, `Schedule`,
  `Scope`, `Clock`, `Layer`, `Stream`, `StaticFlag`, `Schema`, `Json.encode/decode`).

### Not checked

- Any live call to Open-Meteo, aviationweather.gov or INMET (this session's proxy refused them);
  task 4 records the first fixtures.
- `kyo-config` and `kyo-schema-json` against their jars; whether MLflow 3.16.0's server needs
  `boto3` alone for `b2://`.
- INMET's token and wind averaging; CPC's file; the Open-Meteo metadata URL.
- WeatherNext 3's BigQuery schema and cost per query.
- PNBOIA's file format, wind averaging and latency; SIMCOSTA access; radiosonde station ids;
  MONAN's public output; Open-Meteo's ensemble coverage per model; CHM's area polygons.
