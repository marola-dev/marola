# marola's DSPy compile step

Python, run **offline only**: this never runs in production and marola's Scala/Kyo runtime
never imports Python. It exists to produce two artifacts, both loaded and replayed at request time
by `marola.llm.CompiledPrompt`/`marola.llm.Reviewer` via a plain OpenAI-compatible chat-completions
call, with no Python in the runtime path:

- `core/src/main/resources/recommendation_prompt.json`: the summarizer (turns a `BestHour` into
  a sentence).
- `core/src/main/resources/review_prompt.json`: the reviewer/critic pass that grades the
  summarizer's own output and can replace it (`marola.llm.Reviewer`).

See [`../docs/2-Building-marola/ARCHITECTURE.md`](../docs/2-Building-marola/ARCHITECTURE.md) §5a for why this exists and
[`compile_recommendation_prompt.py`](./compile_recommendation_prompt.py) for the actual program
(both `dspy.Signature`s, both trainsets, both compile calls); this file is just the "how to run
it" instructions.

DSPy is Python-only ("**D**eclarative **S**elf-improving **Py**thon"; no JVM port exists; see
[`FUTURE-WORK.md`](../docs/4-Research-and-plans/FUTURE-WORK.md) §10 for the Scala-ecosystem gap this leaves and the
proposed `ds4s` port), and its optimizer is a compile-time step, not a runtime dependency, so it
doesn't need to run in the deployed service.

## Why this needs a real LLM, and costs a little money

DSPy's optimizers (`BootstrapFewShot`, `MIPROv2`, ...) work by actually *calling* a language model
repeatedly: running the draft program against training examples, checking outputs against a
metric, and keeping/refining what worked. There's no way to "compile" a prompt without a real model
in the loop. Budget a few cents to a few dollars depending on the optimizer and trainset size
(`BootstrapFewShot` with 3 examples, as configured here, is cheap; `MIPROv2` costs more). Per
`AGENTS.md`'s cost-safety rule: don't run this against a paid endpoint without knowing that going
in. It's a deliberate, small, one-time spend, not something to run repeatedly or in CI.

## Setup

```bash
cd dspy
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Running it

Point `MAROLA_DSPY_MODEL` (a [LiteLLM](https://docs.litellm.ai/docs/providers) model string,
since that's what DSPy uses under the hood) and the matching provider credentials at whichever
model marola will actually run at request time, so the optimized prompt matches the model
that'll replay it:

```bash
export OPENAI_API_KEY=sk-...
python compile_recommendation_prompt.py   # defaults to openai/gpt-4o-mini
```

This writes both `recommendation_prompt.json` and `review_prompt.json` in one run. Re-run it
whenever `TRAINSET`/`REVIEW_TRAINSET` grow (these double as hand-labeled eval sets; see
`../docs/4-Research-and-plans/FUTURE-WORK.md` §4.1 for the gap between "doubles as an eval set" and an actual held-out
`dspy.Evaluate` loop, which doesn't exist yet) or the target model changes. There's no watch mode,
it's a manual step you re-run and commit both resulting JSON files, same as any other compiled
artifact.

## Optional: tracing the compile run with Langfuse

`BootstrapFewShot`/`MIPROv2` call the model many times per run (once per training example per
bootstrap attempt, more for `MIPROv2`'s Bayesian search) to figure out which few-shot demos and
instructions actually work; that process is otherwise a black box. Setting these three env vars
turns on [Langfuse](https://langfuse.com) tracing for the whole run, via the OTEL-based Python SDK
v3 and [Langfuse's own DSPy integration](https://langfuse.com/integrations/frameworks/dspy):

```bash
export MAROLA_LANGFUSE_PUBLIC_KEY=pk-lf-...
export MAROLA_LANGFUSE_SECRET_KEY=sk-lf-...
export MAROLA_LANGFUSE_BASE_URL=https://cloud.langfuse.com   # or your self-hosted instance
python compile_recommendation_prompt.py
```

Get a free key pair at [cloud.langfuse.com](https://cloud.langfuse.com) (generous free tier) or
self-host (`LANGFUSE_BASE_URL` → `http://localhost:3000` per Langfuse's own docs). Omit these three
vars entirely to skip tracing. `_init_langfuse_tracing()` checks for
`MAROLA_LANGFUSE_PUBLIC_KEY` first and no-ops silently if it's unset, and degrades to a warning
(never a crash) if the credentials are set but unreachable/invalid, so a broken Langfuse setup
never blocks the actual compile step.

Uses the `MAROLA_LANGFUSE_*` prefix (this repo's env var convention; see `AppConfig.scala`,
root `.env.example`) rather than Langfuse's own bare `LANGFUSE_*` names directly, so
`_init_langfuse_tracing()` bridges one to the other internally.

## Optional: logging compile runs to MLflow

Additive to the Langfuse tracing above: both can run at the same time
([MIP-0010](../docs/MIPs/MIP-0010-mlflow-experiment-tracking.md) §11 OQ4). Where Langfuse traces
every individual LLM call made while compiling, this logs one **MLflow run per
`dspy.teleprompt.Teleprompter.compile()` call** (two per script invocation: "summarize" and
"review") to the `marola/prompt-compile` experiment: params (`model`, `optimizer`,
`trainset_size`), the compiled program's own metric score (a fresh `dspy.Evaluate()` pass over
its trainset, using the same metric it was compiled against), and the artifact JSON it wrote
(`recommendation_prompt.json` or `review_prompt.json`).

```bash
export MAROLA_MLFLOW_TRACKING_URI=http://127.0.0.1:5000   # e.g. from `just mlflow-up`
export MAROLA_MLFLOW_EXPERIMENT=marola/prompt-compile      # optional, this is the default
python compile_recommendation_prompt.py
```

Omit `MAROLA_MLFLOW_TRACKING_URI` entirely to skip this: no `mlflow` import, no extra LLM calls
for the evaluation pass, no network, same degrade-silently shape as the Langfuse hook (a stopped
`mlflow server` prints a warning and continues rather than failing the compile step).

**`mlflow.dspy.autolog()` (MIP-0010 §11 OQ3) exists at the pinned versions but isn't used.**
Confirmed live against a real `mlflow==3.16.0` + `dspy==3.3.1` install (this repo's own pins):
`autolog(log_compiles=True)` patches `Teleprompter.compile` to open its own run and log the
optimizer's hyperparameters plus a `best_model.json`/`trainset.json` artifact pair, but it never
computes an aggregate metric score, and its artifact names don't match the actual
`recommendation_prompt.json`/`review_prompt.json` files this step needs on record. Narrower than
what this script needs on both counts, so it hand-logs instead; see
`_log_compile_run_to_mlflow()`'s docstring in `compile_recommendation_prompt.py` for the full
reasoning.

Run `python compile_recommendation_prompt.py --self-test` to check the params/metrics dict this
logging builds, offline: no LLM call, no MLflow server, no `mlflow` import (only the
"unconfigured" path is exercised; see the function's own docstring).

## Status

**Both compile steps have actually been run against a real LLM**, end to end, more than once, a
local Ollama model, not a paid API, so this didn't cost anything: `ollama_chat/dolphin-mixtral:8x7b`
(26GB, `MAROLA_DSPY_API_BASE=http://localhost:11434`) and, separately while writing `RUN-LOCALLY.md`,
the much smaller `llama3.2:1b` (1.3GB). Both produced real compiled artifacts with genuine
LLM-bootstrapped demos (each demo carries `"augmented": true`; inspect the JSON directly to see
this). Along the way this also surfaced and fixed a real, unrelated environment issue: `tokenizers`'
Rust extension needs `libstdc++.so.6`, which a Nix-based Python environment doesn't put on the
default linker path:

```bash
export LD_LIBRARY_PATH="$(ls -d /nix/store/*-gcc-*-lib 2>/dev/null | sort -V | tail -1)/lib:$LD_LIBRARY_PATH"
```

(picks the newest `gcc-*-lib` derivation on your store if more than one is present; verified this
resolves correctly on the machine this was written on, which had both a 15.2.0 and 15.3.0 copy)
needed once per shell before running `python compile_recommendation_prompt.py`, or DSPy fails with
`ImportError: libstdc++.so.6: cannot open shared object file`.

The Scala-side loader (`marola.llm.CompiledPrompt`, `marola.llm.Reviewer`) exists and was run live
against both compiled artifacts (`just run -- --summarize`); see `../docs/2-Building-marola/ARCHITECTURE.md` §5a's
own Status notes for the full detail, including one case where the reviewer correctly caught a
deliberately-planted flaw in a draft summary.

`langfuse==4.15.1` + `openinference-instrumentation-dspy` are confirmed importable and the
graceful-degradation path (credentials set, endpoint unreachable) was exercised directly, but the
actual Langfuse happy path (a trace landing in a real project) remains unverified: no Langfuse
account was set up in this environment.
</content>
