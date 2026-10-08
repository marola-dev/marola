# MIP-0081: External LLM evaluation — standard benchmarks run by any provider, behind one seam, with every score kept in git and checked locally

| | |
|---|---|
| **Status** | Accepted — `Tasks: docs/MIPs/MIP-0081.tasks.md` ([`MIP-0081.tasks.md`](./MIP-0081.tasks.md)) |
| **Author** | Claude, for M. Hoffmann, from marola-dev/marola-ml#22 and an evaluation provider's offer of a partnership and a free first run (2026-10-08) |
| **Created** | 2026-10-08 |
| **Phase** | None: offline ML tooling, off the app's request path. A provider-backed run is paid compute, so `AGENTS.md`'s cost rule applies per run (a person starts it, the cost goes in `Cost:`); no Phase 2 cloud backend is involved |
| **Related** | marola-dev/marola-ml#22 (the sketch this formalises), MIP-0025 (marola-sea, the model being scored), MIP-0032 (the model × strategy matrix on marola's own questions; this is its standard-benchmark half), MIP-0048 (which base to scale to, the question these numbers answer), MIP-0050 (Portuguese bases, and why English scores say little), MIP-0010 (the MLflow ledger, which stays the place for marola's own runs) |
| **Effort** | M — one stdlib Python seam with a local backend over lm-evaluation-harness, a JSON schema and a renderer, one harness task exported from the app's question set, one provider backend, a change to `publish_hf.py`'s card. No Scala, no new service |
| **Gain** | `community/outreach` — marola-sea gets scores comparable with every model card, and a provider's credits become a partnership instead of a one-off; `infra/dev-loop` — the base-model choice MIP-0048 and MIP-0050 defer gets numbers, in Portuguese as well as English |
| **Effort vs Gain** | `cheap win` for tasks 1–3 (local, free, and they make the first provider run publishable); `do when X lands` for task 5, X = a provider whose answers to the brief's five questions meet §5.8 |
| **Depends on** | Nothing merges first. Task 5 depends on a provider agreement, which is a person's act. No Phase 1 gate |
| **Blocked by** | none |
| **Risk** | A provider's number is published without the local check because the check is slow or inconvenient, and a misconfigured run (a generation-scored ARC, a missing chat template) ends up on the model card; §5.3 makes the check a gate on publishing, not a guideline |
| **Cost so far** | — |

### Readiness

| | |
|---|---|
| **Manually reviewed** | no |
| **Written by** | Hoffmann, with Claude Code |
| **Tasks** | [`MIP-0081.tasks.md`](./MIP-0081.tasks.md) |
| **Tests** | `backend.py --self-test` (`fake_backend_round_trips_model_ref`), `check.py --self-test` (`eval_check_refuses_out_of_stderr`, `eval_check_accepts_within_stderr`), `publish_hf.py --self-test` (`model_index_from_results`), `marola_ocean/utils.py --self-test` (`coverage_matches_ocean_benchmark`), `ci.yml`'s `runners` job (§7) |
| **Spec-kit** | none |
| **Issues** | marola-dev/marola-ml#26, #27, #28, #29, #30; marola-dev/marola-site#94; #706 |

## 1. Summary

marola-sea is published to Hugging Face with no standard benchmark score, because running a
harness needs GPUs marola does not have. Evaluation providers offer to run standard benchmarks on
public Hugging Face models, and some offer credits to open projects in exchange for credit. This
MIP adopts them **vendor-neutrally**: an `EvalBackend` seam with a local implementation and one per
provider, chosen by config; one results schema in git; a local re-run that must agree with a
provider before its numbers are published; marola's own ocean questions exported as a portable
harness task; and a model card built from the results instead of free text. No provider is in the
critical path, and every number stays reproducible without them.

## 2. Motivation

- **No comparable numbers.** The only scores marola has are its 22-question keyword-coverage
  benchmark (marola-ml's `docs/benchmarks/`, enforced on `llama3.2` by the benchmark gate) and
  training `eval_loss`. Neither says whether fine-tuning SmolLM2-360M made it forget general
  ability, and the card's `--eval-note` is free text.
- **The base-model decision is open.** MIP-0048 wants a bigger base and MIP-0050 a Portuguese one;
  both defer the choice for lack of measurements. A matrix of five bases on English and
  Portuguese tasks is a few GPU-hours, which a provider can give and marola cannot.
- **A provider asked first.** One offered a partnership and a free `arc_challenge` run on
  marola-sea-tiny, and asked which benchmarks on which models marola needs so it can estimate
  costs. That question needs an answer that is not tied to that provider: marola-ml's
  external evaluation page (`docs/3-development_external-eval.md`, marola-dev/marola-ml#25) is it.

## 3. User-visible change

None in the app. On Hugging Face, each marola-sea card gains a `model-index` block and a results
table naming the backend behind every number. In marola-ml:

```console
# in a marola-ml checkout
$ just eval tiny arc_challenge                  # local backend, CPU is enough for tiny
eval: arc_challenge on h0ffmann/marola-sea-tiny-GGUF@e6d8b16 (Q8_0): acc_norm 0.xxxx ± 0.0xxx
wrote docs/benchmarks/results/2026-10-xx-marola-sea-tiny-arc_challenge-local.json
$ just eval-render                              # docs/benchmarks/results.md from every results file
$ just eval-check <provider-results.json>       # refuses one whose local twin disagrees beyond stderr
```

## 4. Data sources and dependencies reviewed

- **marola-ml** at `main` (2026-10-08): `finetune/train_lora.py`'s `PRESETS` (`tiny` SmolLM2-360M,
  `small`/`base` Llama 3.2 1B/3B, `qwen-4b` Qwen3-4B-Instruct-2507, `qwen-7b`/`qwen-14b`
  Qwen2.5, `qwen-27b`); `finetune/publish_hf.py` (card from `--eval-note`, `CHECKSUMS` sha256 per
  GGUF); `Dockerfile.local` (`llama3.2` is what the app answers with); `scripts/benchmark_gate.py`.
- **Hugging Face** (2026-10-08): `h0ffmann` publishes one model, `marola-sea-tiny-GGUF`, at
  `e6d8b16`, with a single `marola-sea-tiny-Q8_0.gguf` (386 MB), `CHECKSUMS` and the card. There
  is no `Q4_K_M` file and no full-precision marola-sea checkpoint, so "Q4_K_M vs full precision"
  for the released model cannot be measured until one is published (§11).
- **lm-evaluation-harness** at `d6de816` (2026-09-14): `arc_challenge`, `hellaswag`, `mmlu`,
  `truthfulqa_mc2`, `gsm8k`, `ifeval`; Portuguese `arc_pt`, `hellaswag_pt`, `m_mmlu_pt`,
  `truthfulqa_pt_mc1/2` (Okapi, machine-translated), `global_mmlu_pt`, `belebele_por_Latn`.
- **lm-evaluation-harness-pt** (eduagarcia, `ab24923`, the Open Portuguese LLM Leaderboard's
  harness): `enem_challenge`, `bluex`, `oab_exams` (3-shot, acc), `assin2_rte`, `assin2_sts`,
  `faquad_nli` (15-shot, F1 macro), plus hate-speech and sentiment tasks that are not marola's
  job.
- **The provider's offer**: one `arc_challenge` run on marola-sea-tiny-GGUF at no cost. Its
  product, harness and GGUF path were not public enough to check from here; the brief's
  questions (§5.8) are how they get answered.

## 5. Design

### 5.1 The `EvalBackend` seam

`scripts/eval/backend.py` (stdlib): `submit(model: ModelRef, tasks: list[TaskSpec]) -> list[Result]`,
where `ModelRef` is a Hugging Face repo, a revision SHA and, for a GGUF, the file and its sha256
from `CHECKSUMS`. `scripts/eval/local.py` shells out to `lm_eval` (a pinned version in
`eval/requirements.txt`, outside the standard-library rule like `finetune/`'s training
dependencies); GGUF runs go through llama.cpp's server with the `gguf` model type. Each provider
is one more module, picked by `--backend`; nothing outside `scripts/eval/` knows which one ran.

### 5.2 One results schema, in git

`docs/benchmarks/results/<date>-<model>-<task>-<backend>.json`, validated by
`scripts/eval/results.schema.json`: model repo, revision SHA, GGUF file and sha256 (or dtype),
quantisation, task, task version, harness name, version and commit, few-shot count, chat template
on or off, metric, value, stderr, sample count, backend, date, the person who started it, and the
raw per-sample output, committed beside it (gzip) or linked from a release asset when large.
`scripts/eval/render.py` writes `docs/benchmarks/results.md`. A score that lives only on a
provider's dashboard is never cited.

### 5.3 The reproducibility check

Before any of a backend's results are published, at least one of its tasks on `tiny` is re-run by
the local backend; `just eval-check` refuses a file whose value differs from its local twin by
more than the larger of the two stderrs. A refusal blocks publishing that backend's numbers, not
the relationship: the next step is comparing configs.

### 5.4 The domain task

The app's question set (`benchmark_questions.json`, shipped in `ml-resources-<tag>.tar.gz`) is
exported to an lm-evaluation-harness task, `marola_ocean`: a YAML with the questions as
`generate_until` prompts and a `utils.py` scoring keyword coverage the way `OceanBenchmark` does.
It measures the model alone, without retrieval; the app-image gate in `docker-local.yml`, which
measures RAG, stays as it is.

### 5.5 Model card from data

`publish_hf.py` gains `--results <glob>`: the card's `model-index` metadata and results table come
from the results store, each row naming its backend and linking its file. `--eval-note` stays for
prose.

### 5.6 Gating stays local

External results inform. They never gate `docker-local.yml` or a publish: a provider's outage or
policy change must not block a release. Revisit after a backend has a track record.

### 5.7 Cost and safety

A provider-backed run is a `workflow_dispatch` (`eval-external.yml`), started by a person, never
reachable from a pull request (`ci.yml`'s `runners` job enforces it, as for
`marola-sea-publish.yml`). The provider's key is a repository secret or a local environment
variable, never a file; a coupon or a free-run link is treated the same. Credits spent go in the
commit's `Cost:` line. Only public models are sent.

### 5.8 Provider policy

The terms on marola-ml's external evaluation page, written once: configs and harness versions
disclosed with every score; raw outputs exportable; no exclusivity; no claim on marola's data,
models or results; credit on marola.dev's partners page, in the model card beside each score, and
in the results file. The page also carries the five questions a provider answers before its first
paid run (harness and config, how a GGUF is scored, custom and forked tasks, exportable outputs,
gated models).

## 6. Scoring / safety impact

None: nothing here touches the swim score, the safety veto or what the app says.

## 7. Verification plan

The named test: `eval_check_refuses_out_of_stderr` in `scripts/eval/check.py --self-test`, where a
fake backend returns `arc_challenge` on `tiny` at the local run's value plus three stderrs and the
check refuses it, while the local run's own file validates against the schema and renders into a
model card (`publish_hf.py --dry-run --results`). Then, once: a real local `arc_challenge` run on
`tiny` committed as the first results file, and the provider's free run on the same model checked
against it.

## 8. Risks, limitations, and honest caveats

- A 0.36B model scores near chance on MMLU-class tasks; its useful signals are ARC, HellaSwag,
  TruthfulQA and the domain task, and the fine-tune delta on those.
- Okapi's Portuguese tasks are machine-translated; the pt fork's tasks are native but run on a
  harness fork that lags upstream. Both are listed so neither is the only Portuguese number.
- `marola_ocean` is 22 questions scored by keyword coverage, with ±0.1 run-to-run noise measured
  in `2026-09-05.md`; it is a sanity check, not a ranking.
- A GGUF scored by generation instead of log-likelihood gives a different number for the same
  task name; the results schema records how, and §5.3 catches the difference.

## 9. Alternatives considered

- **Take one provider's scores as they come.** Fastest, but the numbers would be unreproducible and
  the relationship exclusive in practice. Rejected by #22's own premise.
- **Rent GPUs and run everything locally.** Reproducible, but paid every run; the local backend
  covers `tiny` on CPU and the seam lets rented GPUs be one more backend later.
- **The Hugging Face Open LLM Leaderboard.** Retired in 2025; it is no longer a submission target.

## 10. Phase and cost

Tasks 1–4 are free (CPU, standard library plus `lm_eval`). Task 5 spends a provider's credits or
money per run, each started by a person with the cost stated first.

## 11. Open questions

- Publish a full-precision `marola-sea-tiny` checkpoint and a `Q4_K_M` GGUF, so the quantisation
  rows of the matrix exist? **Default:** no; the matrix sends the one published `Q8_0` file and
  the bf16 base. Hoffmann decides, since a Hugging Face upload cannot be taken back.
- Which partners page shape on marola.dev? **Default:** one static page listing each partner, what
  it gave and a link to its results, linked from the about page (marola-dev/marola-site#94).
  Hoffmann decides on that issue.
- Does the first provider run the pt fork, or must its tasks be ported upstream first?
  **Default:** the provider is asked (question 3 of the brief); until it answers, the pt-fork
  tasks run locally only and the provider gets the upstream ones.
