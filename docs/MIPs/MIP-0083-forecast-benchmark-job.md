# MIP-0083: A background forecast benchmark — every 4 hours, archive MONAN, WeatherNext and IFS at fixed coastal points and score them side by side against what was measured

| | |
|---|---|
| **Status** | Accepted (Hoffmann, 2026-10-09) — `Tasks: docs/MIPs/MIP-0083.tasks.md` ([`MIP-0083.tasks.md`](./MIP-0083.tasks.md)) |
| **Author** | Hoffmann, from #724 and the design decisions recorded in #723 (2026-10-09) |
| **Created** | 2026-10-09 |
| **Phase** | None: R&D outside the phase list (#724). It provisions a GCS bucket, which is cloud infrastructure ahead of Phase 2; the owner decided it in #723 inside GCP's free tier, and the first `pulumi up` still waits for their confirmation with §5.9's cost table. Phase 1 (the Telegram bot) is not done, and nothing here moves it |
| **Related** | #724 (the job, this MIP's source), #723 (the study it serves: points, protocol, paper), marola-dev/marola-site#99 (the page that draws §5.8's export), MIP-0010 (MLflow and `RunLedger`), MIP-0008 (the GraalVM native-image path, if start-up ever matters), MIP-0057 (an always-on MLflow, Phase 2), MIP-0079 (Zenodo DOIs), [GEMINI-CODE-ASSIST](../4-Research-and-plans/GEMINI-CODE-ASSIST.md) §4 (the Besom layout reused here), `CANDIDATES.md` (marola's first IaC) |
| **Effort** | L — a new sbt module with three new clients, a matcher and a scorer; a scheduled workflow with WIF and a state protocol on GCS; a Besom program; an export schema. No new library dependency in Scala |
| **Gain** | `user value` — marola.dev can say which forecast has been right lately at a given beach; `community/outreach` — an open, continuously updated comparison of a national model against a free ML model on the Brazilian coast, through a strong El Niño |
| **Effort vs Gain** | `do next` for tasks 1 and 3–6 (local, free, and the archive of WeatherNext members is lost for every week it doesn't run); `do when X lands` for tasks 7–8, X = the owner's go-ahead on the cost table; MONAN when #723's step 1 resolves |
| **Depends on** | #723's step 1 for MONAN (data access, unknown today); the owner's confirmation before the first `pulumi up`; a GCP project with a billing account (a person's act) |
| **Blocked by** | none |
| **Risk** | A model's run is silently skipped or fetched twice and the archive drifts from what was actually published, so the scores answer a different question than they claim; §5.4 makes every run an explicit `archived`, `backfilled` or `missing` row, keyed by model and run time |
| **Cost so far** | — |

### Readiness

| | |
|---|---|
| **Manually reviewed** | yes — Hoffmann, 2026-10-09 (accepted in the project thread, with the ground-truth points moved to task 1) |
| **Written by** | Hoffmann, with Claude Code |
| **Tasks** | [`MIP-0083.tasks.md`](./MIP-0083.tasks.md) |
| **Tests** | `GroundTruthSpec`, `ScorerSpec`, `MatcherSpec`, `ForecastClientSpec`, `ObservationClientSpec`, `CycleSpec`, `RescoreSpec`, `ExportSpec` in marola-app's `verify` module (§7) |
| **Spec-kit** | none |
| **Issues** | marola-dev/marola-app#63–#71 (rows 1–9), marola-dev/marola-site#99 (row 10), #726 (row 11) |

## 1. Summary

A new marola-app module, `verify`, runs every 4 hours from a GitHub Actions cron. Each cycle saves
every model run it has not seen yet at a fixed list of coastal points (WeatherNext 2's 64 members
and ECMWF IFS HRES now, MONAN once #723 finds its data), lines up every saved forecast whose valid
time has passed against the anemometer at that point, and keeps rolling bias, RMSE and CRPS per
model, point, lead and observed wind bin. Results go to MLflow, whose SQLite file lives in a GCS
bucket between runs, and to a small JSON export that marola.dev's wind page (marola-site#99)
reads from the `site-data` branch. The bucket, its Workload Identity Federation and its budget
alert are a Besom program, marola's first infrastructure as code.

## 2. Motivation

#723 asks whether INPE's MONAN is worth its cost next to Google's free WeatherNext, and a one-off
answer goes stale with every model upgrade. Two facts make the job urgent rather than nice to
have:

- **Open-Meteo serves only the latest WeatherNext run.** It has no single-run archive for
  `google_weathernext2_ensemble` (v single-runs API page, 2026-10-09: the model is not listed),
  so a member not saved by the cycle that saw it is gone.
- **A strong El Niño is under way** (#723, Motivation, citing NOAA CPC's 2026-10-08 advisory). Its
  peak, October 2026 to March 2027, is the most informative period of the study, and it is
  passing now.

marola-app has the pieces already: an `Http` with fixture replay, a hand-rolled `JsonValue`, and
`RunLedger` with an MLflow REST implementation (MIP-0010). What it lacks is a forecast client that
takes `models=`, any observation client, and anything that runs on a schedule.

## 3. User-visible change

Nothing changes in the CLI, the bot or the board. The new surfaces are:

- **MLflow**, for whoever downloads `mlflow.db`: experiment `forecast-benchmark`, one run per
  cycle, named `cycle-2026-10-09T04:17Z`, status `FINISHED` or `FAILED`.
- **The export** on marola-site's `site-data` branch, `forecast-benchmark/latest.json`
  (shape in §5.8), which marola-site#99 draws.
- **A command** in a marola-app checkout that recomputes every score from the archive:

```text
# in a marola-app checkout
$ just verify-rescore gs://<bucket> --until 2026-10-09T04:17Z
rescored 1,284 matched pairs from 37 cycles; scores identical to cycle-2026-10-09T04:17Z
```

## 4. Data sources and dependencies reviewed

### 4.1 Open-Meteo, ECMWF IFS HRES 9 km

- `https://api.open-meteo.com/v1/forecast?models=ecmwf_ifs` (v Open-Meteo ECMWF docs,
  2026-10-09: the example request uses `ecmwf_ifs`; 9 km, 4 runs a day, up to 15 days).
- **Backfill exists:** `https://single-runs-api.open-meteo.com/v1/forecast?run=YYYY-MM-DDTHH:MM`
  returns one named run; IFS HRES 9 km runs are kept from 2024-03-14 (v single-runs API page,
  2026-10-09). A missed IFS cycle is therefore recovered, not lost.
- Free for non-commercial use, about 10,000 calls a day (#723's cost table); no key.

### 4.2 Open-Meteo, Google WeatherNext 2 ensemble

- `https://ensemble-api.open-meteo.com/v1/ensemble?models=google_weathernext2_ensemble`, 64
  members, 0.25°, **6-hourly native** (hourly output is interpolated; `temporal_resolution=native`
  returns the 6-hour steps), 15 days (v Open-Meteo WeatherNext docs, 2026-10-09).
- **Open-Meteo processes only the 00 and 12 UTC runs**; Google's 06 and 18 UTC runs are not used
  (same page). 10 m wind speed and direction are available; gusts are not listed.
- No single-run archive (§2). Licence for this model not stated on Open-Meteo's page ⚠; Google
  states CC BY 4.0 for data older than 1 h (#723, v developers.google.com/weathernext).

### 4.3 Open-Meteo metadata: which run is being served

Each model has a metadata JSON whose `last_run_initialisation_time` is the run time (Unix
seconds) and `last_run_availability_time` the time it reached the API; Open-Meteo advises waiting
10 minutes after the latter (v model-updates page, 2026-10-09). The URL pattern was not in the
fetched page ⚠: `https://api.open-meteo.com/data/<model>/static/meta.json` is the expected form,
to confirm in task 2.

### 4.4 Grid cell selection

The forecast API's `cell_selection` takes `land` (default: a land cell at similar elevation),
`sea` or `nearest`, and the response's `latitude`/`longitude` are the centre of the cell actually
used, which "might be a few kilometres away" (v Open-Meteo forecast docs, 2026-10-09). For coastal
points the default would pick different cells per model for reasons unrelated to skill.

### 4.5 METAR, aviationweather.gov

`https://aviationweather.gov/api/data/metar?ids=SBFL&format=json`; no key; 100 requests a minute,
at most one a minute per thread, a custom User-Agent requested; **the database holds only the
last 15 days** (v aviationweather.gov data API page, 2026-10-09). Enough for the 4-hourly cycle;
not enough for #723's 5-year station screen, which needs another archive (§11).

### 4.6 INMET automatic stations

Hourly 10 m wind at catalogued stations; the API at `apitempo.inmet.gov.br` may need a token
⚠ (not reachable from this session). Whether its wind is a 10-minute or hourly mean ⚠.

### 4.7 NOAA CPC ENSO state

The monthly Niño-3.4 anomaly and CPC's ENSO alert status, stored with every cycle (#723, ENSO).
The data file (`https://www.cpc.ncep.noaa.gov/data/indices/sstoi.indices`) ⚠ not fetched this
session.

### 4.8 MONAN

Unknown (#723 step 1). The module has a seam for it (§5.2) and no client until the format is known.

### 4.9 MLflow server with GCS artifacts

marola pins `ghcr.io/mlflow/mlflow:v3.16.0` (marola-app `docker-compose.yml`). Proxied artifact
access to `gs://` needs `google-cloud-storage` installed beside MLflow, which MLflow does not
declare itself (v MLflow artifact-store docs, 2026-10-09). `MlflowRunLedger.artifact` already
uploads through the server's `mlflow-artifacts` proxy (marola-app
`local/src/main/scala/marola/ledger/MlflowRunLedger.scala:38`), so the Scala side needs no GCS
client.

**Pick:** all of the above, with `cell_selection=nearest` for every Open-Meteo call (§5.3).

## 5. Design

### 5.1 Where the code lives

marola-app, a fourth sbt project beside `core`, `local` and `cli` (its ADR 0001 records the
three-module split; this MIP adds an ADR row for the fourth):

```scala
lazy val verify = (project in file("verify"))
  .dependsOn(core, local)
  .settings(baseSettings)
  .settings(name := "marola-verify", assembly / mainClass := Some("marola.verify.Main"))
```

`root` aggregates it. No new library dependency (#723's reuse table holds: `Http`, `JsonValue`,
`RunLedger`, `MlflowRunLedger`, logback, munit). JVM only; Scala Native is rejected (#723).
Retries and fan-out are hand-rolled from `map`/`flatMap`, as `Recommender.scala` does, because
`kyo-combinators` is not verified against the pinned RC.

| File (`verify/src/main/scala/marola/verify/`) | What |
|---|---|
| `GroundTruth.scala` | reads and validates `ground-truth.json` (§5.6): the instruments, their status, the thresholds, the deviation log |
| `Protocol.scala` | reads `protocol.json` (§5.6): models, leads, bins, windows, `min_n` |
| `ForecastSource.scala` | `trait ForecastSource { def latestRun: Option[Instant] < Sync; def fetch(run: Instant, points: Chunk[Point]): RunResult < Sync }` |
| `OpenMeteoForecasts.scala` | `ecmwf_ifs` (forecast and single-run APIs) and `google_weathernext2_ensemble` (ensemble API, native steps, all members) |
| `Observations.scala` | `MetarClient`, `InmetClient`, both returning `Obs(pointId, validTime, speedMs, dirDeg, gustMs, source)` |
| `Enso.scala` | CPC's Niño-3.4 anomaly and status |
| `Archive.scala` | gzipped JSON Lines rows (§5.4), read and written |
| `Matcher.scala` | forecast row × observation at the same point and valid time |
| `Scorer.scala` | bias, RMSE, CRPS, direction error, `n`, per window, bin and lead |
| `Cycle.scala` | one cycle, end to end, against a `RunLedger` |
| `Export.scala` | §5.8's JSON |
| `Main.scala` | `cycle`, `rescore`, `screen` |

### 5.2 Models

An enum, so a new source is a compile error until every match handles it:

```scala
enum ModelId(val openMeteo: Option[String]):
  case IfsHres extends ModelId(Some("ecmwf_ifs"))
  case WeatherNext2 extends ModelId(Some("google_weathernext2_ensemble"))
  case Monan extends ModelId(None) // #723 step 1
```

`protocol.json` lists which are active; MONAN is absent until it has a `ForecastSource`. WeatherNext
3 is added the same way if Google grants access.

### 5.3 What is compared, and how it is put at the point

- **One grid rule for every model:** the nearest grid cell (`cell_selection=nearest` on
  Open-Meteo; great-circle nearest on MONAN's native grid). No interpolation between cells. Each
  row records the cell centre the source used, so the distance to the station and the grid spacing
  (9 km, 0.25°, about 10 km) are reported beside every score, never hidden (#724 acceptance).
- **One time grid:** valid times 00, 06, 12 and 18 UTC only, because WeatherNext 2's native step is
  6 h; scoring IFS on its hourly steps would give it pairs the others cannot have.
- **Leads:** every 6 h from 6 to 240 h are stored and scored; 24, 72, 120 and 240 h are the
  headline leads (#723 step 4) that MLflow and the export carry.
- **Variables:** 10 m wind speed (m/s, `wind_speed_unit=ms`) and direction. Direction is scored
  only when the observed speed is at least 2 m/s, as the smaller circular difference. Gusts, 2 m
  temperature and precipitation are archived where offered, not scored in v1.
- **Observation tolerance:** a METAR counts for a valid time if issued within ±10 min of it; an
  INMET hourly record if its hour is the valid time. Anything else is a missing observation and the
  pair is not formed.

### 5.4 The archive, and missing runs

Every row is one model run at one point. Fields: `model`, `openmeteo_model`, `run_time`,
`fetch_time`, `point`, `cell_lat`, `cell_lon`, `status` (`archived`, `backfilled`, `missing`),
`reason` (for `missing`), and for archived rows `steps` (`valid_time`, `lead_h`, `speed`, `dir`,
`members`). A cycle:

1. reads each active model's `last_run_initialisation_time` (§4.3), waiting the 10 minutes;
2. archives that run if `(model, run_time)` is not in the run index yet;
3. walks the expected run times since the last cycle (IFS 00/06/12/18, WeatherNext 2 00/12) and,
   for any not archived, tries the single-run API (IFS only, `status=backfilled`) or writes a
   `missing` row with `reason` (`not_served`, `fetch_failed`, `superseded_before_fetch`).

A missing run is a row, never a gap filled from a neighbouring run (#724 acceptance).

### 5.5 Matching and scoring

`Matcher` pairs an archived step with an observation at the same `point` and `valid_time`, and
keeps `lead_h`. `Scorer` reads pairs and writes one row per (window, point, model, lead, bin):

- `bias = mean(f − o)`, `rmse = sqrt(mean((f − o)²))` on speed; for WeatherNext 2 on the ensemble
  mean.
- `crps`: for the ensemble, `mean|xᵢ − o| − ½·mean|xᵢ − xⱼ|` over the 64 members (the standard
  kernel form, not the "fair" one: §11). For a deterministic model CRPS equals the absolute error,
  so the column exists for every model and is comparable.
- `dir_mae` in degrees, `n` (matched pairs), `days` (distinct valid dates, the closer measure of
  independent samples, #723 step 5), and `low_sample = n < min_n`.
- **Windows:** 7, 30 and 90 days ending at the cycle. **Bins** by observed speed: `all`, calm
  (< 5.5 m/s), moderate (5.5–10.8), strong (≥ 10.8) (#723 step 3).

The paired Diebold-Mariano test, skill against IFS by ENSO phase, and the figures are the study's
(#723), computed from the same pairs by its own script; the job does not publish them.

### 5.6 Ground-truth points and the protocol file

**Ground truth first (owner, 2026-10-09).** Every score is a forecast minus what an instrument
measured, so the instruments are task 1, documented before any client exists.
`verify/src/main/resources/forecast-benchmark/ground-truth.json` holds:

- `thresholds`: #723's strong-wind rule (≥ 200 h a year at ≥ 10.8 m/s, ≥ 5 days a year at
  ≥ 17.2 m/s, over 5 years), with `frozen` (a date, or `null` while they are placeholders ⚠);
- `points`: one per instrument (`id`, `state`, `kind` `metar` or `inmet`, `station`, `lat`, `lon`,
  `elevation_m`, `anemometer_m`, `exposure`, `status`, `checked`: what confirmed the coordinates,
  with URL and date, or `null`);
- `deviations`: dated entries, append-only once a point is `scored`.

A point moves `candidate` → `qualified` or `rejected` (the screen, task 2) → `scored`, and
`retired` if its anemometer is lost. The loader refuses a file that breaks #723's rules: a
`qualified` or `scored` point without checked coordinates, any `qualified` or `scored` point while
the thresholds are not frozen, more than one scored METAR or INMET station per state, a station
code in the wrong format, a coordinate outside Brazil. marola-app's
`docs/4-reference_ground-truth.md` documents the rules, every field and every candidate, with what
was checked and what was not.

Candidates are archived as soon as they are listed, including the at-risk ones (#723 step 3's
table), and scored only once `scored`. Archiving a superset costs nothing and keeps the El Niño
months for whichever points the screen picks; the choice still comes from observed history only,
because no score exists for a point until it is chosen.

`verify/src/main/resources/forecast-benchmark/protocol.json` holds models, leads, bins, windows
and `min_n` (placeholder 30 ⚠, calibrated in #723). The git blob shas of both files are the
`protocol` and `ground_truth` params of every MLflow run, so a change is visible in the record.

### 5.7 One cycle on GitHub Actions

`.github/workflows/forecast-benchmark.yml` in marola-app:

```yaml
on:
  schedule: [{ cron: "17 */4 * * *" }]  # off minute 0: scheduled runs can be delayed or dropped
  workflow_dispatch:
concurrency: { group: forecast-benchmark, cancel-in-progress: false }
permissions: { contents: read, id-token: write }
```

Steps: checkout; JDK 25; `sbt verify/assembly` (coursier cache); `google-github-actions/auth` with
the repo variables `GCP_WIF_PROVIDER` and `GCP_SERVICE_ACCOUNT` (variables, not secrets: neither
is a credential); then:

1. `gcloud storage cp gs://$BUCKET/state/* .` and record each object's generation;
2. `pip install mlflow==3.16.0 google-cloud-storage==<pin>`, then
   `mlflow server --backend-store-uri sqlite:///mlflow.db --artifacts-destination gs://$BUCKET/artifacts --host 127.0.0.1 --port 5000 &`;
3. `java -jar marola-verify.jar cycle --state . --tracking http://127.0.0.1:5000`;
4. stop the server; upload `state/runs.jsonl.gz`, `state/pairs-*.jsonl.gz` and then `state/mlflow.db`,
   each with `--if-generation-match=<recorded>` (a new object with `0`);
5. push `export.json` to marola-site's `site-data` branch as `forecast-benchmark/latest.json`
   with the token and the retrying push `ci.yml` already uses for `coverage/`, then send
   `site-data-updated` so marola-site rebuilds.

**State.** The only mutable objects are `mlflow.db`, the run index and the matched pairs (the
last 100 days, monthly files). Raw forecasts and observations are MLflow artifacts under new names
every cycle and are never rewritten. If an upload is refused or a step fails after the cycle
started, the run ends `FAILED` and the next cycle compares the run index with the artifacts and
re-matches what is missing; `rescore` rebuilds the pairs from the artifacts alone.

**MLflow volume.** Logging every key every cycle would be about 40,000 metric rows a cycle and
too big for a file downloaded six times a day. So every cycle logs params (`protocol`, `ground_truth`, `app_sha`,
`nino34`, `enso_status`, `archived`, `backfilled`, `missing`) and its artifacts
(`forecasts-<model>-<run>.jsonl.gz`, `observations.jsonl.gz`, `scores.json`, `export.json`); the
first cycle after 00 UTC also logs the 30-day `all`-bin metrics at the four headline leads, keyed
`<metric>.30d.<point>.<model>` with `step` = lead hours (about 300 rows a day, under 20 MB a year
⚠ to measure). `scores.json` keeps every window, bin and lead.

**Failure is visible.** A thrown cycle ends its run `FAILED` when MLflow is up, the workflow fails
(GitHub emails the owner), and the export's `last_cycle` lets the page show staleness
(marola-site#99). A dropped schedule shows as a gap in the run names. A public repo's schedule is
disabled after 60 days without repository activity (GitHub docs, #723); marola-app has commits
weekly, and the gap would show on the page.

### 5.8 The export

```json
{
  "schema": 1,
  "last_cycle": "2026-10-09T04:17Z",
  "protocol": "<git blob sha>",
  "enso": { "nino34": 2.1, "status": "El Niño Advisory" },
  "points": [{ "id": "SBFL", "name": "Florianópolis", "lat": -27.67, "lon": -48.55 }],
  "models": [{ "id": "ifs_hres", "label": "ECMWF IFS HRES 9 km", "grid_km": 9 }],
  "runs": [{ "model": "weathernext2", "run_time": "2026-10-09T00:00Z", "status": "archived" }],
  "scores": [{ "window": "30d", "point": "SBFL", "model": "ifs_hres", "lead_h": 72,
               "bin": "strong", "bias": 0.4, "rmse": 2.1, "crps": 1.5, "n": 48, "days": 12,
               "low_sample": false, "cell_km": 3.1 }],
  "recent": [{ "point": "SBFL", "valid_time": "2026-10-08T18:00Z", "lead_h": 72,
               "obs": 11.2, "forecasts": { "ifs_hres": 9.8, "weathernext2": 8.9 } }]
}
```

The schema is `verify/src/main/resources/forecast-benchmark.schema.json`; marola-site vendors it
the way it vendors `board.schema.json`, and #99's fixture validates against it. Only headline
leads, and `recent` holds the last 7 days. The page computes nothing (#99).

### 5.9 Infrastructure: Besom under `infra/forecast-benchmark/`

A standalone scala-cli project (GEMINI-CODE-ASSIST §4's layout), not an sbt module, so
`besom-gcp` never loads in the build. Pulumi state in a GCS bucket created once by hand. Resources:

- the bucket in `us-central1`, versioned, with a lifecycle rule deleting noncurrent versions after
  30 days;
- a Workload Identity pool and an OIDC provider whose attribute condition admits only
  `repository == "marola-dev/marola-app"` and `ref == "refs/heads/main"` (a PR cannot write the
  bucket);
- a service account with `roles/storage.objectAdmin` on that bucket only;
- a billing budget alerting at US$1/month (needs billing-account permissions ⚠).

**Bootstrap.** The program creates the identity that Actions would use to run it, so the first
`pulumi up` is the owner's, locally, with their own `gcloud` login and §5.9's cost table attached
(AGENTS.md cost rule). Afterwards `infra.yml` runs `pulumi preview` on PRs touching `infra/` and
`pulumi up` only on `workflow_dispatch` behind a GitHub environment whose required reviewer is the
owner. Versions `besom-core` 0.5.2, `besom-gcp` 9.0.0-core.0.5 ⚠ (re-check in task 5).

**Cost.** #723's weekly table holds (≈ US$0.00/week inside the free tier, ≈ US$0.12/week without
it) with one change: the per-run download is `mlflow.db` plus the state, about 35 MB ⚠, so
egress is about 1.5 GB a week, inside the 100 GB/month free tier and about US$0.18/week without
it. Raw artifacts per cycle are smaller than #723 assumed, because only runs not seen before are
saved (a WeatherNext run at six points is about 0.15 MB compressed ⚠). The table is recomputed in
task 5's PR and before the first `pulumi up`.

### 5.10 What is deterministic

Everything. No LLM touches the job, its scores or the export.

## 6. Scoring / safety impact

None. `Swimability.score` and its notes do not read anything this job produces. Using the winning
model in the score is a later decision, made with this data (#724, out of scope).

## 7. Verification plan

Tests in marola-app's `verify` module, munit, fixtures replayed through `Http.withTransport`:

- `GroundTruthSpec`: `bundled_file_is_valid`, `duplicate_id_rejected`,
  `station_code_format_per_kind`, `coordinates_outside_brazil_rejected`,
  `qualified_needs_checked_coordinates`, `qualified_needs_frozen_thresholds`,
  `one_scored_station_per_kind_and_state`.

- `ScorerSpec`: `bias_and_rmse_match_hand_computed`, `crps_of_ensemble_matches_hand_computed`,
  `crps_of_deterministic_is_abs_error`, `bins_split_by_observed_speed`,
  `low_sample_flagged_below_min_n`, `direction_skipped_below_2ms` — a fixture of six pairs with the
  values worked by hand in the spec's comment.
- `MatcherSpec`: `pairs_only_same_point_and_valid_time`, `metar_outside_10_min_is_missing`,
  `missing_run_forms_no_pair`.
- `ForecastClientSpec`: `weathernext_native_steps_and_64_members_parsed`,
  `ifs_run_time_from_metadata`, `cell_selection_nearest_sent_for_every_model`,
  `ifs_gap_backfilled_from_single_run`, `weathernext_gap_recorded_missing`.
- `ObservationClientSpec`: `metar_knots_to_ms`, `inmet_hour_parsed`.
- `CycleSpec`, with a recording `RunLedger` stub: `cycle_logs_params_and_artifacts`,
  `daily_metrics_only_on_first_cycle_after_00utc`, `already_archived_run_not_refetched`,
  `failed_fetch_ends_run_failed`.
- `RescoreSpec`: `rescore_from_artifacts_equals_logged_scores`.
- `ExportSpec`: `export_matches_schema`.

Workflow and infra: `actionlint`; `pulumi preview` green in `infra.yml` on the task 5 PR.

Live checks, after the owner's `pulumi up`:

```bash
# in a marola-app checkout
gh workflow run forecast-benchmark.yml   # twice, back to back: the second waits (concurrency)
gcloud storage objects describe gs://$BUCKET/state/mlflow.db --format='value(generation)'
just verify-rescore gs://$BUCKET         # identical to the last cycle's scores.json
```

Plus one forced conflict: upload a copy of `mlflow.db` by hand between a cycle's download and
upload, and see the upload refused and the run marked `FAILED`.

**Done** when the job has run unattended for 7 days with every cycle visible in MLflow, the run
index has no unexplained gap, the export is on `site-data`, and every #724 acceptance box is ticked.

## 8. Risks, limitations, and honest caveats

- **Open-Meteo is a redistributor.** It re-grids the originals; #723 step 2's one-off cross-check
  against ECMWF open data and Google's store stays a task of the study.
- **Point verification of gridded models favours the finer grid** near a coast. Nearest-cell plus
  the reported cell distance makes this visible; it does not remove it.
- **Observations measure different things.** A METAR wind is a 10-minute mean; INMET's hourly wind
  ⚠ may not be. Scores are reported per station source as well as per point.
- **GitHub drops scheduled runs under load.** A dropped cycle loses only WeatherNext runs that are
  replaced before the next cycle (12 h apart, so one dropped cycle loses none; three in a row can).
- **One writer is enforced twice,** by `concurrency` and by generation preconditions; a manual
  `gcloud storage cp` by a person bypasses the first and is caught by the second.
- **The ensemble mean is not a forecast anyone issued.** Bias and RMSE on it favour it against a
  single deterministic run; CRPS is the fair comparison and the export shows both.

## 9. Alternatives considered

- **Do nothing / a one-off study (#723 alone):** stale at the next model upgrade, and the El Niño
  WeatherNext members are lost.
- **An always-on MLflow (Cloud Run plus Cloud SQL):** about US$7.70/month for the database
  (#723); Phase 2, MIP-0057.
- **Keep everything in git (like marola-oods):** a 2.6 GB archive in git history over 24 months,
  and MLflow's UI is what the study's figures are explored with.
- **One MLflow run per point and model per cycle:** 24 runs a cycle, about 52,000 a year in
  SQLite, for nothing a metric key cannot carry.
- **Interpolating bilinearly to the station:** a different rule per grid type (MONAN's native grid
  is not a lat-lon raster ⚠), against #724's "the same for every model".
- **Scala Native / scala-cli for the job:** rejected by the owner (#723).

## 11. Open questions

- **Which 5-year archive does the strong-wind screen read, given METAR keeps 15 days?**
  **Default:** the Iowa Environmental Mesonet METAR archive ⚠ and INMET's historical files ⚠, both
  checked in task 7; the screen's thresholds are #723's to freeze.
- **Does INMET's API need a token?** **Default:** if yes, a GitHub Actions secret
  `INMET_TOKEN`, requested by the owner; METAR-only points run meanwhile.
- **Fair CRPS or standard?** **Default:** standard, as in §5.5; the fair form changes it by
  `mean|xᵢ − xⱼ|/(2m)`, under 1 % at 64 members, and the study may report both.
- **Is `min_n = 30` right?** **Default:** 30 matched pairs per row, calibrated in #723.
- **Does the job add `verify` to the app image?** **Default:** no; the workflow builds the jar
  from the checkout, since nothing else runs it.
- **Does marola-app need its own Zenodo DOI before the first cycle?** **Default:** no; #723 Goal 5
  and MIP-0079 handle it, and `app_sha` in every run ties the data to the code meanwhile.

## Appendix

### Checked live

- https://open-meteo.com/en/docs/ecmwf-api, 2026-10-09: `models=ecmwf_ifs` in the example; 9 km,
  every 6 h, up to 15 days.
- https://open-meteo.com/en/docs/google-weathernext-api, 2026-10-09: `google_weathernext2_ensemble`,
  64 members, 0.25°, 6-hourly native, 00/12 UTC runs only, 10 m speed and direction, no gusts.
- https://open-meteo.com/en/docs/model-updates, 2026-10-09: `last_run_initialisation_time`,
  `last_run_availability_time`, wait 10 minutes.
- https://open-meteo.com/en/docs/single-runs-api, 2026-10-09: `run=` parameter; IFS HRES 9 km
  from 2024-03-14; WeatherNext not listed.
- https://open-meteo.com/en/docs, 2026-10-09: `cell_selection` = `land` (default) / `sea` /
  `nearest`; response coordinates are the cell centre used.
- https://aviationweather.gov/data/api/, 2026-10-09: METAR endpoint, no key, rate limits, 15 days
  of history.
- https://mlflow.org/docs/latest/self-hosting/architecture/artifact-store/, 2026-10-09:
  `google-cloud-storage` needed on client and server for GCS.
- marola-app `main` at 0c1e050: `build.sbt` modules and `baseSettings`; `RunLedger.scala`;
  `MlflowRunLedger.scala:38` (artifact upload through the proxy); `docker-compose.yml:89`
  (MLflow 3.16.0); `ci.yml` (`site-data` push of `coverage/`).

### Not checked

- The Open-Meteo metadata URL pattern; Open-Meteo's licence for WeatherNext 2.
- INMET's API, token and the meaning of its wind field; CPC's index file; MONAN's format.
- The network calls themselves: this session's egress proxy refused Open-Meteo, aviationweather.gov
  and INMET, so no payload was fetched; task 2 records the first fixtures.
- Besom and `besom-gcp` versions; whether MLflow's GCS proxy picks up the credentials file that
  `google-github-actions/auth` writes.
- The size of `mlflow.db` and of the state after a year.
