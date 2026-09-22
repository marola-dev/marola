# MIP-0010: MLflow as marola's experiment ledger — benchmark runs, prompt compiles and LLM traces, local server first, Azure ML as the opt-in

| | |
|---|---|
| **Status** | Implemented — v1 (local server, Phase 0), all 7 tasks merged: PRs #44 → #51 → #52 → #49 → #48 → #47 → #50 (`docs/mips/MIP-0010.tasks.md`); cost ~$16.52 across the 7 PRs (summed `Cost:` trailers, below). The Azure ML tracking-server path (§4.5) stays Draft/unbuilt, blocked on Phase 1 (MIP-0002) and the cost-confirmation gate — not a task here by design |
| **Author** | Claude Fable 5.1, for M. Hoffmann (request of 5 Sep 2026: "MIP for adding MLflow, and how to deploy it local/azure") |
| **Created** | 2026-09-05 |
| **Tasks** | `docs/mips/MIP-0010.tasks.md` — stacked PRs, one per task |
| **Phase** | 0 for the local server and the Scala/Python logging (developer tooling, no user-visible change); the Azure ML path is Phase 2 and waits on Phase 1 (MIP-0002) like every other Azure opt-in |
| **Related** | `FUTURE-WORK.md` §10 (the Langfuse-shaped tracing gap this closes for the JVM side), `FUTURE-WORK.md` §4.1 (evaluation harness — the ledger this MIP adds is what a harness writes to), `ARCHITECTURE.md` §5f (`Telemetry.scala`, the existing OpenTelemetry plumbing), `docs/benchmarks/` and `scripts/benchmark_gate.py` (today's Markdown ledger), `dspy/` and `finetune/` (the offline Python steps), `AI-103-MAPPING.md` "Monitor an AI solution", `AI-500-MAPPING.md` §3 |
| **Effort** | L — 7 stacked PRs across three lanes (ledger, tracing, dspy), a new REST client, an OTel split |
| **Gain** | infra/dev-loop (replaces a hand-pasted Markdown ledger with a queryable one); exam coverage (AI-103 "Monitor an AI solution", AI-500 §3) |
| **Effort vs Gain** | cheap win, delivered — developer-only, no user-facing risk, closes a named exam-mapping gap |
| **Depends on** | none for v1 (local only, delivered); the Azure ML path (§4.5) explicitly waits on Phase 1 (MIP-0002) and the cost-confirmation gate |
| **Risk** | MLflow's OTLP ingest and REST surface are both young (server 3.16.0 vs. a lagging Java client) — a version bump could break the REST contract silently |
| **Cost so far** | ~$16.52 across the 7 implementation PRs (#44, #51, #52, #49, #48, #47, #50); the MIP's own drafting cost is bundled into a shared ~$9.65 session total with MIP-0011 and other PRs (commit 3fdcd05), not separately split |

## 1. Summary

marola already measures itself: `just benchmark` scores three answer modes on 22 questions,
the DSPy compile step optimises two prompts against a metric, and the fine-tune tiers produce model
variants. But every result is a Markdown file under `data/` or `docs/benchmarks/`, compared by
hand and parsed back by a regex (`scripts/benchmark_gate.py`). This MIP adds **MLflow** as the
ledger those steps write to: one **experiment** per kind of run, **params** (model, embedder,
corpus hash, git SHA), **metrics** (coverage per arm, cited %, latency; the DSPy metric scores),
**artifacts** (the Markdown report, the compiled prompt JSONs), and, because the MLflow server
accepts OpenTelemetry traces over OTLP/HTTP from any language, **LLM-call traces** from the Scala
pipeline itself (summariser + reviewer spans with model, latency and token counts). Local default:
`mlflow server` on SQLite via a compose profile or `just mlflow-up`, no account. Azure opt-in: an
Azure Machine Learning workspace is an MLflow-compatible tracking server; its limits for a JVM
client are stated below, not glossed.

## 2. Motivation

- **The benchmark ledger is Markdown.** `docs/benchmarks/2026-09-05.md` holds three runs as
  hand-pasted tables; `benchmark_gate.py` finds "the result that matters" (`rag-general` coverage,
  0.84) by regex over `| arm | coverage ... |` rows. It works, and it is exactly what an experiment
  tracker exists to replace: runs with params, comparable in a UI, queryable by the gate.
- **Prompt compiles leave no record but their output.** `compile_recommendation_prompt.py` writes
  `recommendation_prompt.json`/`review_prompt.json`; which model compiled them, against which
  trainset, with what metric score, is in nobody's notes. Langfuse tracing is optional there today
  (`_init_langfuse_tracing`), Python-only, and needs a hosted account or a second server.
- **The JVM side has OpenTelemetry but nothing LLM-shaped.** `Telemetry.scala` wraps one span
  around the pipeline and exports only to Application Insights (`ARCHITECTURE.md` §5f, "unverified
  against a live resource"). `FUTURE-WORK.md` §10 names "Langfuse-shaped tracing … a scoped, real
  improvement" as the actionable gap; Langfuse has no JVM SDK. MLflow's OTLP endpoint makes the
  existing OpenTelemetry plumbing enough.
- **AI-103/AI-500 ask for it.** "Monitor an AI solution" is the one AI-103 row still at "no-op by
  default"; AI-500 §3 (evaluate, optimise, monitor) has no evaluation ledger at all.

## 3. User-visible change

None for the swimmer. For the developer:

```
$ just mlflow-up                       # http://127.0.0.1:5000, SQLite + local artifacts, no account
$ MAROLA_MLFLOW_TRACKING_URI=http://127.0.0.1:5000 just benchmark
marola :: benchmark — 22 questions × 3 arms on llama3.2
...
mlflow: experiment "marola/benchmark", run 3f9c… — params model=llama3.2 embed=llama3.2 min_score=0
        corpus=knowledge@a1b2c3 git=abe1ba4; metrics rag_general.coverage_all=0.83 …; artifact
        data/benchmark-20260905-1550.md   → http://127.0.0.1:5000/#/experiments/1/runs/3f9c…

$ MAROLA_MLFLOW_TRACKING_URI=http://127.0.0.1:5000 MAROLA_TRACES=mlflow just run -- --summarize
...  (unchanged output)
traces: 1 trace, 3 spans (marola.recommend, llm.summarize 2.1 s, llm.review 1.8 s) → experiment "marola/traces"
```

Without `MAROLA_MLFLOW_TRACKING_URI` nothing changes: the Markdown report is still written, the
gate still reads it, no network call is made.

## 4. Data sources and dependencies reviewed

### 4.1 MLflow (the server and the concept)

Apache-2.0; latest release v3.16.0 on 2026-09-04 (GitHub API, fetched 2026-09-05). Container
image `ghcr.io/mlflow/mlflow` exists (GHCR tag list fetched 2026-09-05; the first page reached
`v3.9.0rc0`; the newest tag was not confirmed, pin whatever `just mlflow-up` first uses). Local
server: `mlflow server --backend-store-uri sqlite:///… --artifacts-destination … --serve-artifacts
--host 127.0.0.1 --port 5000`; `--app-name basic-auth` adds basic authentication (CLI reference,
raw page fetched 2026-09-05; `--host` default 127.0.0.1, `--port` default 5000).

### 4.2 Writing runs from Scala — REST, not the Java client

- **REST API** (fetched 2026-09-05): `POST /api/2.0/mlflow/experiments/create`, `runs/create`,
  `runs/update`, `runs/search`, `runs/log-batch` (the last capped at 1000 items per call, ≤ 1000
  metrics, ≤ 100 params, ≤ 100 tags, 1 MB payload, 250-character keys and param/tag values).
  Artifact upload goes through `artifacts/presigned-upload-url` or the server's artifact proxy
  when started with `--serve-artifacts`.
- **Java client** `org.mlflow:mlflow-client`: latest 3.11.1, Maven Central metadata last updated
  2026-04-08 (fetched 2026-09-05), five minor versions behind the server; `MlflowClient` offers
  `createExperiment`, `createRun`, `logParam/logMetric/setTag/logBatch`, `logArtifact(s)`,
  `searchRuns`, `setTerminated` (Javadoc, fetched 2026-09-05). No tracing API.
- **Pick: REST via the existing `Http`/`Json` helpers.** Four endpoints, the same shape as every
  other client in `core/`, no new dependency tree, no version lag. The Java client is the fallback
  if artifact upload over REST proves awkward (§11).

### 4.3 Traces from the JVM — OTLP into the MLflow server

MLflow ≥ 3.6.0 exposes `POST /v1/traces` (OTLP/**HTTP** only, no gRPC), requires the header
`x-mlflow-experiment-id: <id>`, requires a **SQL backend store** (SQLite is fine), supports gzip
from 3.7.0; the documented SDK env vars are `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:5000/v1/traces`
and `OTEL_EXPORTER_OTLP_TRACES_HEADERS=x-mlflow-experiment-id=123` (ingest page, fetched
2026-09-05). MLflow states GenAI semantic-convention support (`gen_ai.request.model`,
`gen_ai.usage.input_tokens`, …) for ingestion. On the JVM side `io.opentelemetry:opentelemetry-exporter-otlp`
is at 1.65.0 (Maven Central, 2026-08-07); the repo pins `opentelemetry-sdk-extension-autoconfigure`
1.49.0 alongside `azure-monitor-opentelemetry-autoconfigure` 1.4.0; bump to one consistent
OpenTelemetry BOM in the implementation.

### 4.4 Writing runs from the Python steps

`mlflow` (Python) is the reference client: `mlflow.start_run`, `log_params/log_metrics/
log_artifact`. MLflow's GenAI evaluation (`mlflow.genai.evaluate`, scorers, LLM judges) and the
Prompt Registry (`mlflow.genai.register_prompt`) are **Python-only APIs** (docs fetched
2026-09-05; the prompt registry's REST surface for non-Python clients was not documented on the
page fetched); usable in `dspy/` and `finetune/`, not from Scala. Whether `mlflow.dspy.autolog()`
exists at the pinned DSPy/MLflow versions was **not checked** (§11).

### 4.5 Azure paths

- **Azure Machine Learning workspace as the tracking server** (Learn, `ms.date` 2025-10-06,
  fetched 2026-09-05): "Azure Machine Learning workspaces are MLflow-compatible … use an Azure
  Machine Learning workspace the same way you use an MLflow server." **Java limitation, quoted:**
  "MLflow tracking is limited to tracking experiment metrics and parameters on Azure Machine
  Learning jobs. Artifacts and models can't be tracked." Cost: "there is no additional charge to use
  Azure Machine Learning" beyond compute and the companion resources it creates — Blob Storage,
  Key Vault, Container Registry, Application Insights (pricing page, fetched 2026-09-05) — i.e.
  cents per month idle, but a real resource group; **needs the human go-ahead `AGENTS.md`
  requires.** How a Scala process *outside* an Azure ML job authenticates to the workspace's
  tracking endpoint (the Python route is the `azureml-mlflow` plugin and `azureml://` URIs) was
  **not verified** (§11).
- **Azure AI Foundry tracing** stores traces in Application Insights via OpenTelemetry (Foundry
  classic doc, updated 2026-06-26, fetched 2026-09-05); MLflow is not mentioned there. This is
  `Telemetry.scala`'s existing path, unchanged by this MIP.
- **Self-hosting the MLflow server on Azure** (Container App + Azure Database for PostgreSQL +
  Blob artifacts) is the third option; it is the most complete for the JVM (OTLP ingest works, no
  Java limitation) and the most expensive (a database). Not proposed for v1.

**Pick:** local `mlflow server` as the default for everything; for Azure, in Phase 2, the Python
offline steps log to an Azure ML workspace (fully supported), and the Scala side keeps logging
metrics/params there only if the auth question resolves — otherwise the Scala ledger stays local
or self-hosted. Stated as such in `ARCHITECTURE.md` §5's table when built.

## 5. Design

**A new pluggable integration, same shape as the six others** (`ARCHITECTURE.md` §5): a trait in
`core/`, a no-op default, an implementation in `local/` (MLflow is not Azure; `local/` keeps its
zero-Azure-SDK guarantee since this is plain REST), env-var selection in `AppConfig`.

```scala
// core/src/main/scala/marola/ledger/RunLedger.scala
trait RunLedger:
  def start(experiment: String, name: String, params: Map[String, String]): RunHandle < Sync
  def metrics(run: RunHandle, values: Map[String, Double], step: Int = 0): Unit < Sync
  def artifact(run: RunHandle, path: java.nio.file.Path): Unit < Sync
  def end(run: RunHandle, ok: Boolean): Unit < Sync

object RunLedger:
  val Noop: RunLedger                  // every method returns unit; the default
  final case class RunHandle(experimentId: String, runId: String, url: Option[String])

// local/src/main/scala/marola/ledger/MlflowRunLedger.scala — REST over Http/Json:
//   experiments/get-by-name → create; runs/create; runs/log-batch (chunked to the §4.2 caps,
//   keys truncated to 250 with a warning); artifact upload via the server's artifact proxy;
//   runs/update RUNNING→FINISHED/FAILED.
```

Where runs are written:

- `OceanBenchmark.run` → experiment `marola/benchmark`: params `model`, `embed_model`,
  `min_score`, `corpus_sha` (hash of `knowledge/*.md`), `git_sha`, `questions`; metrics per arm
  (`<arm>.coverage_in_corpus`, `.coverage_general`, `.coverage_all`, `.cited_pct`,
  `.abstained_pct`, `.mean_ms`); artifact: the Markdown report. **The Markdown report stays the
  canonical gate input in v1**: `benchmark_gate.py` is unchanged; reading the best kept run from
  MLflow is v2.
- `dspy/compile_recommendation_prompt.py` → experiment `marola/prompt-compile`: params (model,
  optimiser, trainset size), metrics (the metric's score per compiled program), artifacts (both
  JSONs). Replaces the optional Langfuse hook or sits beside it (§11).
- `finetune/train_lora.py` → experiment `marola/finetune` (later task; "written, not run" today).
- **Traces:** `Telemetry` splits into a `core` trait (`Tracing.withSpan`, `Tracing.llmSpan`) with
  two backends: `local/MlflowTracing` (OTLP/HTTP exporter to `<tracking uri>/v1/traces`, header
  from `MAROLA_MLFLOW_EXPERIMENT`) and `azure/AzureMonitorTracing` (today's code). A
  `TracedLlmClient(inner, tracing)` decorator wraps `LocalLlmClient`/`AzureFoundryLlmClient` and
  emits one span per `complete` with `gen_ai.request.model`, latency, and token counts when the
  response carries `usage`; prompt and completion text are attached **only** when
  `MAROLA_TRACE_CONTENT=1` (locations are personal data).

Env vars (`AppConfig`): `MAROLA_MLFLOW_TRACKING_URI` (unset = `Noop`), `MAROLA_MLFLOW_EXPERIMENT`
(default `marola`), `MAROLA_TRACES=off|mlflow|azure` (today's implicit switch on
`APPLICATIONINSIGHTS_CONNECTION_STRING` keeps working as `azure`). Recipes: `just mlflow-up`
(compose profile `mlflow` on `ghcr.io/mlflow/mlflow`, SQLite + `.tmp/mlflow/` artifacts, bound to
127.0.0.1) and `just mlflow-ui`. Deterministic: everything above. Through the LLM: nothing.

## 6. Scoring / safety impact

None. No scoring code is touched; `Swimability` is unchanged.

## 7. Verification plan

Unit tests (all offline, munit): `MlflowRunLedgerSpec`, the exact JSON of `runs/create` and
`runs/log-batch` for a `Report`, chunking at 1000 metrics / 100 params, 250-character key
truncation, FAILED status on `end(ok = false)`, via a scripted `Http.Transport`;
`NoopRunLedgerSpec` (no transport call ever); `TracedLlmClientSpec` (span name, `gen_ai.*`
attributes and the content-off default, using `opentelemetry-sdk-testing`'s in-memory exporter);
`BenchmarkLedgerSpec` (the params/metrics map derived from an `OceanBenchmark.Report`).
Live checks: `just mlflow-up && MAROLA_MLFLOW_TRACKING_URI=… just benchmark` shows the run and
its artifact at `127.0.0.1:5000`; `MAROLA_TRACES=mlflow just run -- --summarize` shows one trace
with the summariser and reviewer spans; `dspy` compile logs a run with two artifacts. **Done:** the
next benchmark PR's comparison paragraph links a run URL instead of pasting a table.

## 8. Risks, limitations, and honest caveats

- MLflow 3 moves fast (server 3.16.0 vs Java client 3.11.1); REST is the stable surface, and OTLP
  ingest only exists from 3.6.0, so pin the image tag and say so in `RUN-LOCALLY.md`.
- Traces can carry prompts, which carry a user's location. Content off by default; the local
  server binds to loopback; the MLflow UI has no auth unless `--app-name basic-auth`.
- MLflow is a heavy Python dependency: it lives in a compose profile / `uvx`, never in the runtime
  image (`Dockerfile`) or in `flake.nix`'s default shell unless the nixpkgs package proves light
  (§11).
- The Azure ML Java limitation (metrics and params only, inside jobs) means the Scala side may
  never log artifacts there; the design keeps artifacts optional for that backend.
- Two ledgers during the transition (Markdown + MLflow). Mitigated by keeping Markdown canonical
  for the gate until the MLflow query path is tested.

## 9. Alternatives considered

- **Do nothing**: the Markdown ledger and regex gate work today; they do not scale past one
  benchmark and record nothing about prompt compiles or traces.
- **Langfuse**: already optional in `dspy/`; no JVM SDK (`FUTURE-WORK.md` §10); whether its
  server accepts OTLP from other languages was **not checked**. One server for runs *and* traces
  favoured MLflow.
- **Application Insights only**: Azure-only, no local default; violates the local-first rule.
- **Weights & Biases / hosted trackers**: an account and a key for a personal project's ledger.
- **Java client instead of REST**: see §4.2; kept as the fallback.

## 10. Exam-coverage mapping

AI-103 §1 "Monitor an AI solution" — from "no-op by default" to a local, verified ledger plus
traces (mark "proposed: MIP-0010"). AI-500 §3 "Evaluate, optimize, and monitor" — the ledger a
future eval harness (`FUTURE-WORK.md` §4.1) writes to; per-agent spans are the first step toward
the cross-agent tracing that section calls out as missing.

## 11. Open questions

1. Azure ML from the JVM outside a job: which tracking URI and credential (`DefaultAzureCredential`
   bearer token?), verify against a real workspace before writing `ARCHITECTURE.md` §5's row.
2. Is `python3Packages.mlflow` in nixpkgs light enough for the dev shell, or is the compose
   profile / `uvx mlflow` the only sane local path?
3. `mlflow.dspy.autolog()` at the pinned DSPy version: exists? worth it, or log the two compile
   metrics by hand?
4. Keep the Langfuse hook in `dspy/` alongside MLflow, or remove it once MLflow logs the compile?
5. Artifact upload over REST vs the Java client's `logArtifact`: decide after the first spike.
6. Experiment naming: one `marola` experiment with tags, or `marola/<kind>` as sketched here.

## Appendix

- MLflow release: `https://api.github.com/repos/mlflow/mlflow/releases/latest` → `v3.16.0`,
  2026-09-04; licence Apache-2.0 (2026-09-05).
- Java client: `https://repo1.maven.org/maven2/org/mlflow/mlflow-client/maven-metadata.xml` →
  3.11.1, lastUpdated 20260408 (2026-09-05).
- OTLP ingest: `https://mlflow.org/docs/latest/genai/tracing/opentelemetry/ingest/`: `/v1/traces`,
  OTLP/HTTP only, `x-mlflow-experiment-id`, MLflow ≥ 3.6.0, SQL store required (2026-09-05).
- REST: `https://mlflow.org/docs/latest/api_reference/rest-api.html`: log-batch caps (2026-09-05).
- Azure ML: `https://learn.microsoft.com/en-us/azure/machine-learning/concept-mlflow` (ms.date
  2025-10-06) and `https://azure.microsoft.com/en-us/pricing/details/machine-learning/` (2026-09-05).
- Foundry tracing: `https://learn.microsoft.com/en-us/azure/ai-foundry/concepts/trace` → redirected
  to the Foundry (classic) "trace-application" page, updated 2026-06-26 (2026-09-05).
- OpenTelemetry Java: `io.opentelemetry:opentelemetry-exporter-otlp` 1.65.0 (Maven Central,
  2026-08-07).
