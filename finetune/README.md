# marola fine-tuning — local only (Ollama + llama3.2)

Two tiers, honestly labelled. Both produce a model named `marola-llama3.2` that the Scala side
uses with nothing more than `MAROLA_LOCAL_LLM_MODEL=marola-llama3.2` — no code change.

| Tier | What it is | Cost | Status |
|---|---|---|---|
| **1. Modelfile variant** (`Modelfile`) | `llama3.2` with marola's system prompt, tone and decoding parameters baked in. No weights change. | seconds, CPU | **run and verified** (see below) |
| **2. QLoRA adapter** (`train_lora.py` + `Modelfile.adapter`) | A real LoRA fine-tune on marola's own examples, converted to GGUF and attached to the base model via Ollama's `ADAPTER`. | `tiny`: minutes on CPU; `base` (gated Llama 3.2 3B): hours on CPU, minutes on a GPU; ~6GB download | **run, `tiny` preset verified** (SmolLM2-360M, 2026-09-06: `finetune-dataset` → `finetune-train preset=tiny --no-4bit --epochs 3` → `convert_lora_to_gguf.py` → `ollama create` → `just run -- --summarize` produced a real reply from the tuned model, end to end. Eval loss fell across all 3 epochs: 3.032 → 2.866 → 2.799 — real learning, not noise. Quality itself is rough at this scale, expected per the ladder below; `small`/`base` are still not run — no GPU here, and `base`'s weights need a Hugging Face login) |

Tier 1 is not fine-tuning in the weights sense and this README does not pretend it is. It exists
because it is the cheapest way to get a consistently marola-flavoured model *today*, and because
the dataset builder for Tier 2 is useful on its own.

## Tier 1 — build and use the Modelfile variant

```bash
just finetune-model            # ollama create marola-llama3.2 -f finetune/Modelfile
export MAROLA_LOCAL_LLM_MODEL=marola-llama3.2
just run -- --summarize
```

## Tier 2 — QLoRA adapter (written, not run)

1. Build the dataset (chat-format JSONL) from what the repo already has: the DSPy-compiled demos
   (`core/src/main/resources/*.json`), the sea-lore entries, and question/answer pairs derived
   from the knowledge corpus:

   ```bash
   just finetune-dataset        # writes finetune/data/train.jsonl and finetune/data/eval.jsonl
   ```

   Expect a few thousand examples (2026-09-07: 2,497 train + 277 eval), combining two new layers
   from MIP-0025 §4.3:
   - **Layer 1 "Marine Corpus Domain"**: every real chunk and sentence in `knowledge/` asked
     several paraphrased ways, so the fine-tune sees real domain facts, not just format/tone —
     each synthetic example is verified (`build_dataset.py --self-test`) to be a verbatim excerpt
     of the `knowledge/*.md` source it cites, no invented facts.
   - **Layer 2 "MCP Tool-Call SFT"**: synthetic questions mapped to a single JSON tool call for
     one of marola's four real MCP tools (`find_nearby_beaches`, `get_swim_recommendation`,
     `get_water_quality`, `ask_ocean_question` — names and schemas read straight from
     `SwimConditionsMcpServer.scala`, not guessed). The same self-test checks every generated
     call is syntactically valid JSON naming a real tool with arguments matching its real schema,
     and that all four tools are covered.

   Both self-tests are wired into `just quality-other`. The DSPy demos and sea-lore entries
   remain the *format and tone* teachers `FUTURE-WORK.md` §9.1 step 4 describes.

2. Train the adapter (needs `pip install -r requirements.txt`, a `huggingface-cli login` for the
   gated Llama weights, and ideally a GPU with 8GB+):

   ```bash
   cd finetune && python train_lora.py --base meta-llama/Llama-3.2-3B-Instruct --epochs 3
   ```

3. Convert the adapter to GGUF with llama.cpp's `convert_lora_to_gguf.py` and register it:

   ```bash
   python /path/to/llama.cpp/convert_lora_to_gguf.py out/adapter --outfile out/marola-adapter.gguf
   ollama create marola-llama3.2 -f Modelfile.adapter
   ```

4. Evaluate before trusting it: run `just e2e` and `just run -- --summarize` with the new model,
   and — the real test — compare reviewer scores over a held-out set (`FUTURE-WORK.md` §4.1).

## Layer 3 — DPO preference data + training (MIP-0025 §4.3 — **run, `tiny` preset verified**
2026-09-07)

`core/llm/Reviewer.scala`'s own reject/revise decisions are the preference signal: wherever the
compiled `review_prompt.json` demos show a verdict other than `"approve"`, the reviewer's real
`final_summary` is a correction of a real flawed draft — a (chosen, rejected) pair with no
invented text on either side. Approved drafts carry no signal and produce no pair.

```bash
just finetune-dpo-dataset      # writes finetune/data/dpo_pairs.jsonl
```

`build_dpo_dataset.py --self-test` (wired into `just quality-other`) checks this against both a
small fixture (asserting exactly one pair per reject/revise event, zero pairs for an all-approve
fixture) and the real `review_prompt.json` demos (every generated pair's text matches a real
demo's text verbatim).

```bash
just finetune-train-dpo preset=tiny -- --no-4bit --epochs 1   # continues from out/adapter (SFT)
```

`train_dpo.py` (`trl`'s `DPOTrainer`) continues training from the SFT adapter (`train_lora.py`'s
`out/adapter`) rather than training DPO from scratch — real run, 2026-09-07: `tiny` preset
(SmolLM2-360M), 2 real preference pairs, 1 epoch, chained after a real SFT run on the scaled-up
Layer 1+2 dataset (eval loss 3.032 → **0.2594**, mean token accuracy **0.939** — real learning).
The combined adapter was converted to GGUF and benchmarked
(`docs/benchmarks/mip-0025/2026-09-07-task5-sft-dpo.md` — a subdirectory `benchmark_gate.py`'s
promotion-gate `newest()` deliberately doesn't glob into, since this MIP-0025 experiment record is
not the marola-local Docker image's real promotion-gate reference) — honestly, the tiny tuned model scores
*below* the untuned `llama3.2` baseline on `just benchmark`'s RAG-grounding task, which that write-up
explains is expected (nothing in tasks 2-5's training data targets RAG-abstention discipline) and
not evidence the recipe is broken, not a favorable number picked to declare success.

## As an image: `ghcr.io/h0ffmann/marola:local` (MIP-0008)

The Tier 1 model, versioned like the code. `Dockerfile.local` is Ollama with `marola-llama3.2`
already created from this directory's `Modelfile`; `.github/workflows/docker-local.yml` builds it
from `main` whenever the Modelfile, the corpus, the prompts or the gate change, pushes it as
`:local-<sha>`, runs `just benchmark` against that container and lets
`scripts/benchmark_gate.py` decide: the moving `:local` tag advances only if `rag-general`
coverage (all) is within 0.05 of the best run kept in `docs/benchmarks/` and above the plain
prompt's — otherwise `:local` stays where it was and the report is on the run. Use it with
`docker compose --profile local run --rm marola-local --summarize …` (`docker-compose.yml`) —
no pull, no `ollama create`. Tier 2 is not in the image until something has trained the
adapter; the README above is still the honest status.

## Iterating on a local machine: the small-model ladder

Retraining "from the ground" should cost minutes, not an afternoon. The same script, dataset and
Modelfile steps work at three sizes — change only `--preset`:

| preset | base model | gated? | CPU training time (41 examples, 3 epochs, rough) | Ollama `FROM` for the adapter |
|---|---|---|---|---|
| `tiny` | SmolLM2-360M-Instruct | no | minutes | `smollm2:360m` |
| `small` (default) | Llama-3.2-1B-Instruct (unsloth mirror) | no | tens of minutes | `llama3.2:1b` |
| `base` | Llama-3.2-3B-Instruct | yes (HF login) | hours; use a GPU | `llama3.2` |

Loop: `just finetune-dataset` → `just finetune-train preset=tiny` → convert → `ollama create` →
`just benchmark` / `just run -- --summarize` → edit the dataset → repeat. Only when the tiny model
shows the format/tone you want is it worth paying for `small` or `base`. Tier 1 has the same knob:
`just finetune-model base=llama3.2:1b` builds the persona variant on the 1B model.

The same ladder applies to the RAG embedder (`knowledge/README.md`): `all-minilm` (45MB) re-indexes
the corpus in seconds, `nomic-embed-text` (274MB) is the quality option, `llama3.2` itself needs no
extra download.

## Publishing to Hugging Face (MIP-0025 §5.1, MIP-0033 §5.3)

Once a `.gguf` file exists (`ollama create` already needs one via `Modelfile.adapter` for local
use — the same file publishes), `finetune/publish_hf.py` uploads it to a Hugging Face model repo
with a generated model card and a `CHECKSUMS` file:

```bash
pip install -r finetune/requirements.txt   # adds huggingface_hub
huggingface-cli login                       # one-time, needs a HF account + write token

just finetune-publish \
  repo=<you>/marola-sea-tiny-GGUF \
  gguf=finetune/out/marola-tiny-adapter.gguf \
  base=HuggingFaceTB/SmolLM2-360M-Instruct

# then, from any machine with Ollama:
ollama run hf.co/<you>/marola-sea-tiny-GGUF
```

Pass `--dry-run` (append after the `just` recipe's own args) to write `CHECKSUMS`/`README.md`
locally without uploading, to review the model card first. **Check the base model's licence
before publishing** (`--base-license`, default `apache-2.0` — correct for SmolLM2, wrong for a
Llama-based checkpoint): a model fine-tuned from Llama weights must have "Llama" at the start of
its published name per Meta's Community License (MIP-0025 §5.1(3)) — this script does not enforce
that, it is a human check before the repo goes up.

## First-release readiness (marola-sea, MIP-0025/MIP-0033)

What's real today vs. what's still missing before "marola-sea-1.0" is a real, published release:

| Step | Status |
|---|---|
| A real training run on real hardware | **done** — `tiny` preset (SmolLM2-360M), CPU, eval loss 3.032→2.866→2.799 over 3 epochs |
| LoRA → GGUF conversion | **done** — `finetune/out/marola-tiny-adapter.gguf` exists locally (gitignored, not in git) |
| Runs end-to-end through marola | **done** — `ollama create` + `Modelfile.adapter`, then `just run -- --summarize` |
| HF publish tooling | **done this session** — `finetune/publish_hf.py` / `just finetune-publish`, not yet run against a real HF account |
| Actual HF publish | **not done** — needs the maintainer's own `huggingface-cli login` and a real upload; nothing here can do that unattended |
| `just benchmark` numbers for this checkpoint | **not done** — `docs/benchmarks/` has no `tiny`-preset run yet; do this before trusting it over the plain base model (§7 of MIP-0025) |
| A `small`/`base`-preset run (better quality) | **not started** — `tiny` is a pipeline proof, explicitly not a quality bar (this README's own framing, top of file) |
| Ollama-registry push (optional 2nd channel) | **not started** — needs `ollama signin`, a human step (MIP-0025 §5.1(2)) |

The `tiny` run's job was to validate the pipeline end to end on hardware anyone has, which it did.
The remaining gap to a real "release" is compute (a `small`/`base` run) and the human steps above
(HF login, an actual upload, a benchmark run) — no more design work is needed, per MIP-0025 §5.1's
already-verified plan.

## What is deliberately not here

- No cloud training. Azure ML / Foundry fine-tuning is the Phase 2 opt-in (`AGENTS.md` cost rule).
- No attempt to fine-tune facts in. A 3B model with 40 examples will not learn marine biology; it
  will learn to sound like it did. Facts come from `knowledge/` via RAG, with citations.
