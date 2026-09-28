# MIP-0032: The model × strategy benchmark matrix — closed API vs. open local vs. marola-RAG vs. marola-tuned, on the same questions, with latency and cost as first-class columns

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Fable 5.1, for M. Hoffmann (request of 2026-09-06, from a WhatsApp exchange: "is a RAG agent just an executor over retrieval?" → "marola needs its own head-to-head: GPT-raw vs marola-RAG vs marola-fine-tuned vs from-scratch; closed models (Claude Sonnet, 'Llama') vs open vs marola layer 1 / layer 2") |
| **Created** | 2026-09-06 |
| **Phase** | 0 (developer tooling; no swimmer-visible change). The local arms are Phase 0 today. A paid arm is not gated on Phase 1, but it *is* gated on `AGENTS.md`'s cost rule (a human go-ahead per run, stated cost) — the same reading MIP-0025 §11 item 5 applies to RunPod |
| **Related** | `cli/bench/OceanBenchmark.scala` + `BenchmarkLedger.scala` (the harness this generalises — one `LlmClient`, three fixed `Arms`), `scripts/benchmark_gate.py` + `.github/workflows/docker-local.yml` (the unattended gate that must stay free), `docs/benchmarks/2026-09-05.md`, MIP-0010 (the ledger the matrix writes to), MIP-0025 §7 (the tuned model's verification plan — a *row* of this matrix), MIP-0012 §5 (`Evaluate` — the summariser side, not this), `FUTURE-WORK.md` §4.1 (this is its benchmark half; the reviewer half and LLM-as-judge stay open) and §10 (Promptfoo), `ARCHITECTURE.md` §5a |
| **Effort** | M — one small `LlmClient` implementation (OpenAI-compatible remote endpoint with a bearer key, ~30 lines beside `LocalLlmClient`), `OceanBenchmark` refactored from "three arms on one client" to "three arms × N models", three new report columns, a `just benchmark-matrix` recipe, an env-var paid gate; `benchmark_gate.py` and the kept-run format stay backward compatible by construction (§5.4). No new module, no new store |
| **Gain** | infra/dev-loop (the question "is the local 3B + RAG good enough, or does a frontier model change the answer?" gets a number instead of an opinion; MIP-0025's own verification plan gets its comparison table for free); cost/ops (a per-arm cost and latency column is the evidence behind marola's local-first default) |
| **Effort vs Gain** | `do next` for the free half (§5.1–§5.4: any Ollama model as a row, latency percentiles, token counts — no spend, reuses everything); `do when X lands` for the paid arm, where X = the maintainer's explicit go-ahead with a key in hand (§5.5) — cents per run (§4.6), but a spend nonetheless |
| **Depends on** | MIP-0010 (Implemented — the ledger; the matrix adds params/metrics, changes nothing there). MIP-0025 is *not* a dependency: the matrix runs without a tuned model and gains a row when one exists. No Phase 1 gate. Paid arms: `AGENTS.md` cost rule (human go-ahead per run) |
| **Risk** | The 22-question keyword-coverage metric is too small and too crude to rank frontier models against a 3B model honestly — `docs/benchmarks/2026-09-05.md` already measured ±0.1 run-to-run noise on the *same* model. A matrix that prints "Claude 0.91 vs llama3.2 0.84" invites reading noise as a verdict. §5.6 (repeats, ranges) and §8 mitigate; growing the question set (§11) is the real fix, and it is not this MIP's to do alone |
| **Cost so far** | — |

## 1. Summary

`just benchmark` today answers one question, "is marola's RAG better than the plain prompt?", for
one model (`llama3.2`, local). This MIP turns it into a matrix: the same 22 questions and the same
three prompting strategies (`baseline`, `rag-strict`, `rag-general`), run against a **list of
models**: local open models through Ollama (the free default, the only thing the unattended gate
ever runs), a domain-tuned local model when MIP-0025 produces one, and, opt-in and human-confirmed
per run, hosted closed models (Claude via Anthropic's OpenAI-compatible endpoint; GPT via OpenAI),
with **latency percentiles, token counts and estimated cost** as columns next to coverage. The
output is the head-to-head the maintainer asked for, in marola's own terms: a "closed model, raw"
arm is `(claude-sonnet-5, baseline)`; "marola-RAG" is `(llama3.2, rag-general)`; "marola-tuned" is
`(marola-sea-1.0, rag-general)` and `(marola-sea-1.0, baseline)` side by side; "from scratch" stays
an explicit non-arm (§9).

## 2. Motivation

- **The harness compares strategies, not models.** `OceanBenchmark.run(store, llm, minScore)`
  takes exactly one `LlmClient`; `Arms` is a fixed `List("baseline", "rag-strict", "rag-general")`;
  `verdict` hardcodes those three names; `Main.runBenchmark` refuses to run without a *local* LLM
  ("`--benchmark` needs a configured local LLM", `Main.scala:251`). The report's summary table has
  no model column at all: `docs/benchmarks/2026-09-05.md` says "same local `llama3.2`" in prose.
- **MIP-0025 §7 needs a comparison it cannot make today.** Its plan is "`just benchmark` against
  `marola-sea-1.0` compared to the best kept run and to the plain `llama3.2`/`marola-llama3.2`
  baselines": three separate runs, three Markdown files, compared by eye. With `MAROLA_BENCH_MODELS`
  it is one run and one table.
- **Latency is a product question, not a footnote.** The maintainer's operational point (a slow RAG
  hop is a UX problem for a chatbot; a model that already knows the domain answers without it) is
  measurable: `rag-general` on `llama3.2` took 608 ms mean vs 514 ms baseline on 2026-09-05. The
  retrieval hop cost ~100 ms, ~20%. Whether a tuned model without retrieval closes that gap *and*
  keeps coverage is exactly the tuned-vs-RAG row pair. Today only `mean ms` exists; p95 and tokens
  do not.
- **The local-first default deserves a number, and so does the debate.** `ARCHITECTURE.md` §5a
  argues for local Ollama in prose; "RAG is just an executor over retrieval" and
  "fine-tuning moves knowledge from context into weights" are both claims about *where the domain
  layer lives*. One table settles both, with the caveat that the axis is a continuum (prompt →
  few-shot → RAG → adapter → full SFT → pretraining) and the matrix samples four points on it.

## 3. User-visible change

None for the swimmer. For the developer:

```
$ just benchmark                       # unchanged: 22 q × 3 arms on MAROLA_LOCAL_LLM_MODEL, free, what CI runs

$ MAROLA_BENCH_MODELS="local:llama3.2,local:marola-llama3.2,local:qwen3:8b" just benchmark-matrix
marola :: benchmark matrix — 22 questions × 3 arms × 3 models (all local, $0)
| model | arm | coverage (in-corpus) | coverage (general) | coverage (all) | cited | abstained | p50 ms | p95 ms | tok in/out | est. $ |
|---|---|---|---|---|---|---|---|---|---|---|
| llama3.2 | baseline | 0.55 | 0.92 | 0.75 | 0% | 0% | 480 | 910 | 1.5k/2.1k | 0 |
| llama3.2 | rag-general | 0.92 | 0.78 | 0.84 | 41% | 0% | 590 | 1200 | 36k/4.8k | 0 |
| qwen3:8b | baseline | … | … | … | … | … | … | … | … | 0 |
…

$ MAROLA_BENCH_MODELS="local:llama3.2,anthropic:claude-sonnet-5" just benchmark-matrix
marola :: benchmark matrix — 1 paid arm: anthropic:claude-sonnet-5, est. ≤ 50k in / 10k out tokens ≈ $0.20–0.30 per run
(paid arm skipped: set MAROLA_ALLOW_PAID_LLM=1 and ANTHROPIC_API_KEY after a human go-ahead)
# with both set, the paid rows appear in the same table with real `tok in/out` and `est. $`, and
# the footer reads: measured: 2 paid arms, 51,300 in / 7,400 out tokens, $0.18 at the §4 list prices (2026-09-06)
```

The first table of the report keeps today's exact shape (the local default model, three arms, the
same seven columns) so `benchmark_gate.py` and every kept run under `docs/benchmarks/` remain
valid; the matrix table follows it.

## 4. Data sources and dependencies reviewed

Every price and API shape below was fetched on **2026-09-06**; "not checked" is stated where it
applies. Prices change; the harness prints the *price table it was built with* and its date, and
reports measured tokens separately from estimated dollars, so a stale table degrades to a labelled
estimate rather than a wrong fact.

### 4.1 Anthropic (Claude) — OpenAI-compatible endpoint, first-party API

- **Prices** (`platform.claude.com/docs/en/about-claude/pricing`, fetched 2026-09-06): Claude
  Sonnet 5 **$2 / MTok in, $10 / MTok out** (the page notes the introductory price became the
  standard price; the scheduled rise to $3/$15 on 2026-09-01 "will not occur"); Haiku 4.5 $1 / $5;
  Opus 5 $5 / $25; Sonnet 4.6 and 4.5 $3 / $15. Batch API is 50% off (not usable here — the
  benchmark measures latency, and batch is asynchronous). Models from Claude 4.7 on use a
  tokenizer producing "approximately 30% more tokens for the same text"; the estimate in §4.6
  carries that factor.
- **Endpoint shape** (`platform.claude.com/docs/en/api/openai-sdk`, fetched 2026-09-06): base URL
  `https://api.anthropic.com/v1/`, `POST chat/completions`, `Authorization` header "fully
  supported", response `choices[0].message.content` and `usage.prompt_tokens`/`completion_tokens`
  "fully supported". Anthropic's own framing: the layer "is primarily intended to test and compare
  model capabilities," i.e. this MIP's use. Limitations that matter here: `n` must be 1 (fine);
  `seed` is ignored (repeatability comes from repeats, §5.6, not seeds); system messages are hoisted
  and concatenated (marola sends one system message per call, no effect); `temperature` capped at
  1. **Not checked:** rate limits at a fresh account's tier for ~90 calls in a row.
- **Auth is an API key** (`ANTHROPIC_API_KEY`): `AGENTS.md`'s no-hardcoded-keys rule applies (env
  var, `.env.example` placeholder, masked by ai-jail).

### 4.3 OpenAI (GPT) — first-party API

Prices (`developers.openai.com/api/docs/pricing`, fetched 2026-09-06; `openai.com/api/pricing`
returned HTTP 403 to a plain fetch): `gpt-5-mini` **$0.25 / $2.00**, `gpt-5-nano` $0.05 / $0.40,
`gpt-5` $1.25 / $10, `gpt-5.4` $2.50 / $15, `gpt-5.4-mini` $0.75 / $4.50, `gpt-5.6-terra` $2 / $12,
`gpt-5.6-sol` $4 / $20; Batch 50% off. Endpoint: the standard `/v1/chat/completions` with a bearer
key, the same shape as §4.1, so one client (§5.2) serves both. **Not checked:** OpenAI's terms on
publishing benchmark comparisons; a search result claims `gpt-5-mini` is scheduled for shutdown on
2026-12-11 (third-party page, not confirmed on OpenAI's site); pin whichever model runs, print it.

### 4.5 Open local models — Ollama, free

`llama3.2` (default `3b`, 2.0 GB, 128K context; Ollama library page fetched 2026-09-06 — the page
states no licence; the Llama 3.2 Community License, fetched from `meta-llama/llama-models` the
same day, is the governing text: 700M-MAU threshold, "Llama" prefix on derived model names.
MIP-0025 §5.1 already handles the naming rule for the tuned row. `qwen3` ships 0.6b–235b tags,
`qwen3:8b` = 5.2 GB (library page fetched 2026-09-06), a plausible "bigger open model" row. Ollama's
`/v1/chat/completions` supports `seed`, `temperature` and `usage` via `stream_options.include_usage`
(`docs.ollama.com/api/openai-compatibility`, fetched 2026-09-06); whether `usage` is present on a
non-streaming reply without that option was **not checked** (§11). **Hardware:** the CI gate runs
a 3B model on 4 vCPUs in 20–40 min (`docker-local.yml`'s own comment); an 8B row is 2–3× that;
local-machine or on-demand only, never added to the unattended gate.

### 4.6 What one closed-model run costs — the estimate the gate prints

Per question: `baseline` = 1 call (~70 tokens in, ≤ ~120 out); `rag-strict` = 1 grounded call
(4 passages × ≤ 700 chars ≈ 700 tokens + ~90 of prompt ≈ 800 in, ≤ ~120 out); `rag-general` = the
same grounded call plus, on the `NO_ANSWER_IN_PASSAGES` sentinel (expected on most of the 12
off-corpus questions), one general call. 22 questions → ~56 calls, **≤ ~50k input, ≤ ~10k output
tokens** per model per run (the 4.7+ tokenizer factor already applied on the input side). At the
§4.1/§4.3 list prices: Sonnet 5 ≈ **$0.20**, Haiku 4.5 ≈ $0.10, Opus 5 ≈ $0.50, `gpt-5-mini` ≈
$0.03, `gpt-5.4` ≈ $0.28, **cents per run**. The risk is not one run; it is a loop (`--repeats 5`
× five models × a growing question set) or a CI job that nobody meant to make paid. Hence §5.5.

**Pick:** v1 = local Ollama rows (default, free, gated) + one OpenAI-compatible remote client for
Anthropic and OpenAI (opt-in, key from env, human go-ahead per run).

## 5. Design

### 5.1 The matrix — `OceanBenchmark` generalised, nothing renamed

```scala
// cli/src/main/scala/marola/bench/OceanBenchmark.scala
enum Provider derives CanEqual { case Local, OpenAiCompat }                    // §5.2 picks the client
final case class ModelSpec(provider: Provider, model: String, paid: Boolean)   // "local:llama3.2"
final case class Arm(model: ModelSpec, strategy: String)                       // strategy ∈ Arms (unchanged)
// Result gains tokensIn/tokensOut: Option[Int]; ArmSummary gains p50Ms, p95Ms, tokensIn, tokensOut,
// estimatedUsd: Option[Double] and is keyed by Arm instead of a String.
def run(store: KnowledgeStore, models: List[(ModelSpec, LlmClient)], minScore: Double, repeats: Int): Report < Sync
```

`Arms` stays `List("baseline", "rag-strict", "rag-general")` and the three strategies stay the
existing calls (`OceanQa.generalMessages`, `OceanQa.answer` strict/general): retrieval is local
regardless of which model answers, so "Claude + marola-RAG" is a real row, not a special case.
`verdict` keeps its three-arm logic *per model* and adds one cross-model paragraph: best
`coverage_all` per strategy, the cheapest model within 0.05 of it, and the fastest p95 within 0.05
of it, deterministic, no model in the loop. Existing `just benchmark` = `run` with
`models = List(local default)`, `repeats = 1`.

### 5.2 One new client, beside `LocalLlmClient`

```scala
// core/src/main/scala/marola/llm/OpenAiCompatLlmClient.scala — same 25 lines as LocalLlmClient plus
// a bearer header and `usage` capture; no SDK, `Http.postJson`'s existing `headers` parameter.
final class OpenAiCompatLlmClient(baseUrl: String, model: String, apiKey: String) extends LlmClient
```

Prefixes in `MAROLA_BENCH_MODELS`: `local:<model>` → `LocalLlmClient`; `anthropic:<model>` →
`OpenAiCompatLlmClient("https://api.anthropic.com/v1", m, ANTHROPIC_API_KEY)`; `openai:<model>` →
the same against `https://api.openai.com/v1` with `OPENAI_API_KEY`. `AppConfig.benchModels` parses
the list; an unknown prefix or a missing key is an `enum` error printed per row, never an exception.
Token counts need the `usage` object `complete` discards, so both clients gain `completeWithUsage:
Completion < Sync` (the field `TracedLlmClient` already reads for spans, MIP-0010 task 6).

### 5.3 Cost and latency columns

`p50`/`p95` from the per-call `ms` list (pure, unit-tested). `est. $` = tokens × a price table in
`cli/src/main/resources/llm_prices.json` `{model, usdPerMTokIn, usdPerMTokOut, checked: "2026-09-06",
source}`, a curated, dated resource like `sea_lore.json`, never fetched at runtime; a model absent
from it prints `?` and the tokens, not a guess. Local rows print `0` and, in the footer, the
wall-clock and hardware line (`nproc`, model tag) so "free" is not read as "fast".

### 5.4 Report, gate and ledger — backward compatible

- **Report:** the first summary table is the local default model's three arms in today's seven
  columns, byte-compatible with `benchmark_gate.py`'s `HEADER`/`ROW` regexes; the matrix table
  (eleven columns, `model` first) follows under `## Matrix`. `docs/benchmarks/` keeps both.
- **Gate:** `benchmark_gate.py` is unchanged in v1 and keeps reading the first table: it still
  compares `rag-general` of the *local* model with the best kept local run. A `--model` flag to gate
  a different row is v2. `docker-local.yml` never sets `MAROLA_BENCH_MODELS`; a paid prefix there is
  a bug, and `just quality`'s hook self-test (§5.5) asserts the workflow file contains none.
- **Ledger (MIP-0010):** experiment `marola/benchmark` unchanged for `just benchmark`;
  `just benchmark-matrix` logs to `marola/benchmark-matrix` with params `models`, `repeats`,
  `prices_checked`, and metrics `<model>.<arm>.<column>` (`BenchmarkLedger.metrics` keyed by
  `Arm`, 250-char key cap respected). Cost so far of paid arms becomes a metric, so the ledger is
  also the spend log.

### 5.5 The paid gate — three layers

1. **The CLI refuses by default.** A row with `paid = true` runs only when
   `MAROLA_ALLOW_PAID_LLM=1` *and* its key env var are set; otherwise it prints the row as
   `skipped (paid, not confirmed)` with the §4.6 estimate, and the run continues with the free rows.
2. **A hook.** `.claude/hooks/guard-paid-llm.sh` (`PreToolUse` on `Bash`):
   exit 2 on any command text that sets `MAROLA_ALLOW_PAID_LLM=1` or names a paid prefix in
   `MAROLA_BENCH_MODELS`, unless the human exported the variable in *their* shell (the hook checks
   the inherited environment). `--self-test` in `just quality`.
3. **Never unattended.** No workflow sets the variable; paid prefixes are grep-asserted absent from
   `.github/workflows/*.yml`; a scheduled paid run is out of scope (it would need a GitHub
   Environment approval, MIP-0020's pattern).

Before a paid run the CLI prints the estimate and, in a terminal, waits for `y`; after it, the
measured tokens and dollars. A PR that ran a paid arm adds the API spend to its `Cost:` trailer.

### 5.6 Repeats and noise

`--repeats N` (default 1; `just benchmark-matrix` defaults to 3 for local rows) runs each arm N
times and reports the mean with the min–max range in the cell (`0.84 (0.79–0.88)`); the verdict
calls two arms "tied" when their ranges overlap. `seed` is passed to Ollama and OpenAI rows and
documented as ignored by Anthropic (§4.1). This is the one change that makes the cross-model
comparison honest at 22 questions; it does not replace a bigger question set (§11).

### 5.7 What is deterministic and what is not

Everything in this MIP that produces a number (coverage, citation, abstention, percentiles,
cost) is pure Scala, unit-tested. The only model output is the answers being scored. No
LLM-as-judge (`FUTURE-WORK.md` §4.1 keeps that; note that a closed model as *judge* would itself be
a paid arm and needs the same gate).

## 6. Scoring / safety impact

None. `Swimability` and the recommendation pipeline are untouched; the benchmark's questions are
the existing 22 (no personal data, no locations; the *summariser* path that carries a user's
location is not part of this benchmark and is not sent to any third-party API by it).

## 7. Verification plan

- `OceanBenchmarkSpec` (extend): `summarise` yields p50/p95 from a known `ms` list; `estimatedUsd`
  from a fixture price table, `None` for an unlisted model; `verdict`'s cross-model paragraph on a
  hand-built two-model summary (ties on overlapping ranges).
- `BenchModelsSpec` (new): `"local:llama3.2,anthropic:claude-sonnet-5"` parses to two specs with
  `paid` correct; unknown prefix → error value; missing key → `skipped` row, not an exception.
- `OpenAiCompatLlmClientSpec` (new): exact request JSON and `Authorization: Bearer` header via a
  scripted `Http.Transport`; `usage` extracted; a reply without `usage` yields `None` tokens.
- `BenchmarkLedgerSpec` (extend): matrix metric keys `<model>.<arm>.<column>` under 250 chars.
- `scripts/benchmark_gate.py --self-test` (extend): a report with a matrix table *after* the first
  table still gates on the first; the workflow-file paid-prefix assertion.
- `guard-paid-llm.sh --self-test`: blocks `MAROLA_ALLOW_PAID_LLM=1 just benchmark-matrix` in the
  command text; passes when the variable is inherited from the shell.
- Live, free: `MAROLA_BENCH_MODELS="local:llama3.2,local:marola-llama3.2" just benchmark-matrix`
  produces a two-model table and the first table still passes `benchmark_gate.py` against
  `docs/benchmarks/`. Live, paid, once, after a go-ahead: `anthropic:claude-haiku-4-5` (the
  cheapest Claude row, ≈ $0.10): confirm the endpoint, the `usage` field and the measured-vs-
  estimated dollars; keep that run under `docs/benchmarks/` with the spend in its header.
- **Done:** `docs/benchmarks/` holds one matrix run with ≥ 2 local models and repeats = 3; the gate
  is green and still free; `ARCHITECTURE.md` §5a cites a row of it.

## 8. Risks, limitations, and honest caveats

- **22 keyword-coverage questions cannot rank frontier models.** The metric saturates (baseline
  general coverage was 0.92–1.00 on 2026-09-05 already). Expect closed models to tie at the top on
  the off-corpus half; the informative cells are in-corpus coverage, citation rate, latency and
  cost. Say so in the report footer.
- **Latency across arms is not apples to apples:** local rows measure a CPU on the maintainer's
  machine or a 4-vCPU runner; hosted rows measure a network round trip to a data centre. The table
  prints the hardware line; the comparison the maintainer cares about (retrieval hop vs. no hop)
  is *within* a model row, which is fair.
- **A strong model may answer well despite a poor retrieval**, masking the embedder's weakness;
  cited % and the sentinel rate expose this; note it in the verdict.
- **Prices drift.** The dated `llm_prices.json` and the measured-token column keep the estimate
  labelled; the number is not a bill.
- **Terms of use:** Anthropic's compat layer is explicitly for testing and comparison (§4.1); the
  equivalent for OpenAI and for *publishing* results was not checked. Kept runs are developer
  evidence, not marketing, until it is.

## 9. Alternatives considered

- **(a) An addendum to MIP-0010.** The ledger records runs; it does not define what a run compares.
  A model axis, three columns, a new client, a paid gate and a matrix recipe are harness changes
  MIP-0010's §5 never sketched, and MIP-0010 is Implemented; reopening it for new scope would
  break the index's status vocabulary. Rejected; the matrix *writes to* MIP-0010 (§5.4).
- **(b) An addendum to MIP-0025.** Its §7 is the verification of one candidate model; it consumes
  a comparison, it does not own the comparator. Closed-API rows, cost/latency columns and the paid
  gate have nothing to do with fine-tuning. Rejected; MIP-0025's §7 becomes "one row of MIP-0032".
- **Promptfoo** (`FUTURE-WORK.md` §10): a YAML matrix runner over providers, exactly this shape,
  but marola's RAG arms are Scala code (`OceanQa`), not prompts, and it adds a Node toolchain for
  what is ~150 lines of Scala on an existing harness. Rejected for v1; revisit if the matrix grows
  beyond marola's own strategies.
- **A "from-scratch" arm.** Pretraining is off the table for cost and corpus reasons (the
  maintainer's own conclusion); the honest stand-in is the smallest untuned open model as a floor
  row (`local:llama3.2:1b`), so the tuned row's gain has a lower bound as well as an upper one.
- **Do nothing.** MIP-0025 would compare three Markdown files by hand and the local-vs-hosted
  argument would stay prose. Loses because the free half is a `do next`-sized change.

## 11. Open questions

1. **"marola layer 1 / layer 2" — which layers?** Three readings fit the maintainer's words and
   the repo, and the matrix accommodates all of them as rows, but the *names in the report* need a
   decision: (i) layer 1 = RAG (context), layer 2 = a fine-tuned model (weights), the "changes
   context vs. changes parameters" spectrum from the chat; (ii) MIP-0025's own Layer 1 (marine-
   corpus QLoRA) / Layer 2 (MCP tool-call SFT) / Layer 3 (safety DPO), which the maintainer wrote;
   (iii) `finetune/README.md`'s Tier 1 (Modelfile persona, `marola-llama3.2`) / Tier 2 (QLoRA
   adapter). Not resolved here on purpose.
2. **"Llama" in the closed-model list.** Transcribed oddly. Llama is an open-weights family
   (§4.5, a local row); if a *hosted* Llama was meant, no provider was named and none is verified.
3. Not checked: whether Ollama's non-streaming reply carries `usage` without
   `stream_options.include_usage`; Anthropic rate limits at the account's tier for ~60 sequential
   calls; OpenAI's terms on publishing comparisons.
5. Growing the question set past 22 (and past keyword coverage): who writes the next 50, and from
   where (MIP-0002's real user questions, per `docs/benchmarks/2026-09-05.md` item 4)?
6. Housekeeping: `.env.example` is an empty file today (0 bytes). The
   `ANTHROPIC_API_KEY`/`OPENAI_API_KEY` placeholders would be its first content. Confirm that is
   the intended home.

## Appendix

Fetched 2026-09-06 (all via WebFetch/WebSearch, none assumed):

- `https://platform.claude.com/docs/en/about-claude/pricing` — model table (Sonnet 5 $2/$10,
  Haiku 4.5 $1/$5, Opus 5 $5/$25), the Sonnet 5 "introductory price is now standard" note, the
  Claude 4.7+ "~30% more tokens" note, Batch 50%.
- `https://platform.claude.com/docs/en/api/openai-sdk` (redirect from `docs.anthropic.com`) —
  base URL `https://api.anthropic.com/v1/`, "intended to test and compare model capabilities",
  `Authorization` supported, `usage.prompt_tokens`/`completion_tokens` supported, `seed` ignored,
  `n` = 1, system messages hoisted.
- `https://developers.openai.com/api/docs/pricing` — `gpt-5-mini` $0.25/$2, `gpt-5-nano`
  $0.05/$0.40, `gpt-5` $1.25/$10, `gpt-5.4` $2.50/$15, Batch 50%. `https://openai.com/api/pricing/`
  → HTTP 403.
- `https://ollama.com/library/llama3.2` — tags `1b`, `3b` (default, 2.0 GB, 128K context), no
  licence stated on the page. `https://ollama.com/library/qwen3` — 0.6b–235b, `8b` = 5.2 GB.
- `https://github.com/meta-llama/llama-models/blob/main/models/llama3_2/LICENSE` — Llama 3.2
  Community License, 700M-MAU clause, "include 'Llama' at the beginning of any such AI model name".
- `https://docs.ollama.com/api/openai-compatibility` — `seed`, `temperature`,
  `stream_options.include_usage` listed as supported request fields.
- Repo facts read, not fetched: `OceanBenchmark.scala` (`Arms`, one `LlmClient`, `mean ms` only),
  `Main.scala:251` (local-only guard), `benchmark_gate.py` (first table, `rag-general` vs `baseline`),
  `docker-local.yml` (20–40 min on 4 vCPUs, `main`-only triggers), `OceanQa.scala` (k = 4,
  sentinel, two-stage general fallback), `Corpus.MaxChunkChars = 700`, `Http.postJson(headers)`,
  `benchmark_questions.json` (22 questions, 10 in-corpus), `knowledge/` (6 documents, ~15 KB).
