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

   Expect a few thousand examples (2026-09-07: 2,209 train + 245 eval) — most of them Layer 1
   "Marine Corpus Domain" examples (MIP-0025 §4.3): every real chunk and sentence in `knowledge/`
   asked several paraphrased ways, so the fine-tune now sees real domain facts, not just
   format/tone. Each synthetic example is verified (`build_dataset.py --self-test`) to be a
   verbatim excerpt of the `knowledge/*.md` source it cites — no invented facts. The DSPy demos
   and sea-lore entries remain the format/tone teachers `FUTURE-WORK.md` §9.1 step 4 describes.

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

## Layer 3 — DPO preference data (MIP-0025 §4.3, dataset only — training is `train_dpo.py`, not
built yet)

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

## What is deliberately not here

- No cloud training. Azure ML / Foundry fine-tuning is the Phase 2 opt-in (`AGENTS.md` cost rule).
- No attempt to fine-tune facts in. A 3B model with 40 examples will not learn marine biology; it
  will learn to sound like it did. Facts come from `knowledge/` via RAG, with citations.
