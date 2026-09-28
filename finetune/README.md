# marola fine-tuning — local only (Ollama + llama3.2)

Two tiers, honestly labelled. Both produce a model named `marola-llama3.2` that the Scala side
uses with nothing more than `MAROLA_LOCAL_LLM_MODEL=marola-llama3.2`, no code change.

| Tier | What it is | Cost | Status |
|---|---|---|---|
| **1. Modelfile variant** (`Modelfile`) | `llama3.2` with marola's system prompt, tone and decoding parameters baked in. No weights change. | seconds, CPU | **run and verified** (see below) |
| **2. QLoRA adapter** (`train_lora.py` + `Modelfile.adapter`) | A real LoRA fine-tune on marola's own examples, converted to GGUF and attached to the base model via Ollama's `ADAPTER`. | `tiny`: minutes on CPU; `base` (gated Llama 3.2 3B): hours on CPU, minutes on a GPU; ~6GB download | **run, `tiny` preset verified** (SmolLM2-360M, 2026-09-06: `finetune-dataset` → `finetune-train preset=tiny --no-4bit --epochs 3` → `convert_lora_to_gguf.py` → `ollama create` → `just run -- --summarize` produced a real reply from the tuned model, end to end. Eval loss fell across all 3 epochs: 3.032 → 2.866 → 2.799: real learning, not noise. Quality itself is rough at this scale, expected per the ladder below; `small`/`base` are still not run: no GPU here, and `base`'s weights need a Hugging Face login) |

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
     several paraphrased ways, so the fine-tune sees real domain facts, not just format/tone;
     each synthetic example is verified (`build_dataset.py --self-test`) to be a verbatim excerpt
     of the `knowledge/*.md` source it cites, no invented facts.
   - **Layer 2 "MCP Tool-Call SFT"**: synthetic questions mapped to a single JSON tool call for
     one of marola's four real MCP tools (`find_nearby_beaches`, `get_swim_recommendation`,
     `get_water_quality`, `ask_ocean_question`; names and schemas read straight from
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
   python /path/to/llama.cpp/convert_lora_to_gguf.py out/tiny/adapter --outfile out/tiny/adapter.gguf
   ollama create marola-llama3.2 -f Modelfile.adapter
   ```

4. Evaluate before trusting it: run `just e2e` and `just run -- --summarize` with the new model,
   and, the real test, compare reviewer scores over a held-out set (`FUTURE-WORK.md` §4.1).

## Layer 3 — DPO preference data + training (MIP-0025 §4.3 — **run, `tiny` preset verified**
2026-09-07)

`core/llm/Reviewer.scala`'s own reject/revise decisions are the preference signal: wherever the
compiled `review_prompt.json` demos show a verdict other than `"approve"`, the reviewer's real
`final_summary` is a correction of a real flawed draft: a (chosen, rejected) pair with no
invented text on either side. Approved drafts carry no signal and produce no pair.

```bash
just finetune-dpo-dataset      # writes finetune/data/dpo_pairs.jsonl
```

`build_dpo_dataset.py --self-test` (wired into `just quality-other`) checks this against both a
small fixture (asserting exactly one pair per reject/revise event, zero pairs for an all-approve
fixture) and the real `review_prompt.json` demos (every generated pair's text matches a real
demo's text verbatim).

```bash
just finetune-train-dpo preset=tiny -- --no-4bit --epochs 1   # continues from out/tiny/adapter
```

`train_dpo.py` (`trl`'s `DPOTrainer`) continues training from the SFT adapter (`train_lora.py`'s
`out/tiny/adapter`) rather than training DPO from scratch. Real run, 2026-09-07: `tiny` preset
(SmolLM2-360M), 2 real preference pairs, 1 epoch, chained after a real SFT run on the scaled-up
Layer 1+2 dataset (eval loss 3.032 → **0.2594**, mean token accuracy **0.939**: real learning).
The combined adapter was converted to GGUF and benchmarked
(`docs/benchmarks/mip-0025/2026-09-07-task5-sft-dpo.md`, a subdirectory `benchmark_gate.py`'s
promotion-gate `newest()` deliberately doesn't glob into, since this MIP-0025 experiment record is
not the marola-local Docker image's real promotion-gate reference); honestly, the tiny tuned model
scores *below* the untuned `llama3.2` baseline on `just benchmark`'s RAG-grounding task, which
that write-up explains is expected (nothing in tasks 2-5's training data targets RAG-abstention
discipline) and not evidence the recipe is broken, not a favorable number picked to declare
success.

## As an image: `ghcr.io/marola-dev/marola:local` (MIP-0008)

The Tier 1 model, versioned like the code. `Dockerfile.local` is Ollama with `marola-llama3.2`
already created from this directory's `Modelfile`; `.github/workflows/docker-local.yml` builds it
from `main` whenever the Modelfile, the corpus, the prompts or the gate change, pushes it as
`:local-<sha>`, runs `just benchmark` against that container and lets
`scripts/benchmark_gate.py` decide: the moving `:local` tag advances only if `rag-general`
coverage (all) is within 0.05 of the best run kept in `docs/benchmarks/` and above the plain
prompt's; otherwise `:local` stays where it was and the report is on the run. Use it with
`docker compose --profile local run --rm marola-local --summarize …` (`docker-compose.yml`), with
no pull, no `ollama create`. Tier 2 is not in the image until something has trained the
adapter; the README above is still the honest status.

## Iterating on a local machine: the small-model ladder

Retraining "from the ground" should cost minutes, not an afternoon. The same script, dataset and
Modelfile steps work at every size; change only `--preset`:

| preset | base model | gated? | CPU training time (41 examples, 3 epochs, rough) | Ollama `FROM` for the adapter |
|---|---|---|---|---|
| `tiny` (default) | SmolLM2-360M-Instruct | no | minutes | `smollm2:360m` |
| `small` | Llama-3.2-1B-Instruct (unsloth mirror) | no | tens of minutes | `llama3.2:1b` |
| `base` | Llama-3.2-3B-Instruct | yes (HF login) | hours; use a GPU | `llama3.2` |
| `qwen-4b` | Qwen3-4B-Instruct-2507 | no | hours; use a GPU | `qwen3:4b-instruct` |
| `qwen-7b` | Qwen2.5-7B-Instruct | no | GPU only in practice | `qwen2.5:7b` |
| `qwen-14b` | Qwen2.5-14B-Instruct | no | GPU only in practice | `qwen2.5:14b` |
| `qwen-27b` | Qwen3.8-27B | no | GPU only, never run here | `qwen3.8:27b` |

The `FROM` column is part of the identity, not a convenience: `qwen3:4b` is the *thinking*-2507
checkpoint and `qwen3:4b-instruct` is the Instruct-2507 one that `qwen-4b` trains on, and attaching
an adapter to the wrong sibling loads cleanly and then answers nonsense. `just
finetune-adapter-model preset=<preset>` fills both lines in from the preset table and creates
`marola-sea-<preset>`, so each base gets its own Ollama model.

**Switching base is safe by construction.** Every preset trains into its own
`finetune/out/<preset>/` (adapter, DPO adapter, merged model, GGUFs), and each script refuses to
start when the directory it is about to write, or the adapter it is about to load, belongs to a
different base model. This is checked from a marker file and `adapter_config.json` before torch
is even imported. Nothing is shared between a SmolLM2 run and a Qwen one.

Loop: `just finetune-dataset` → `just finetune-train preset=tiny` → convert → `ollama create` →
`just benchmark` / `just run -- --summarize` → edit the dataset → repeat. Only when the tiny model
shows the format/tone you want is it worth paying for `small` or `base`. Tier 1 has the same knob:
`just finetune-model base=llama3.2:1b` builds the persona variant on the 1B model.

The same ladder applies to the RAG embedder (`knowledge/README.md`): `all-minilm` (45MB) re-indexes
the corpus in seconds, `nomic-embed-text` (274MB) is the quality option, `llama3.2` itself needs no
extra download.

## Presets, and what each costs on your machine

`just finetune-preflight preset=<name>` answers the only question that matters before starting a
run (does it fit, and how long) by measuring VRAM, RAM and the real filesystem rather than
guessing:

```
$ just finetune-preflight preset=qwen-27b
preset      : qwen-27b  (Qwen/Qwen3.8-27B, 27.78B, apache-2.0)
run dir     : finetune/out/qwen-27b
              NOTE: this base's template emits reasoning blocks, so the fine-tune teaches that shape too
device      : cpu  (no usable CUDA device found)

  VRAM (train)  n/a on cpu
  RAM  (merge)  need    59.6 GB   have   202.4 GB   OK
  disk (peak)   need   158.3 GB   have  7009.0 GB   OK
estimated wall clock: ~29.8 h for 3 SFT + 1 DPO epoch (rough)
  WARNING: CPU training above ~3B is measured in days. Use a GPU or a smaller preset.
```

Measure the filesystem the artifacts actually land on. On 2026-09-07 `df /home` in a sandboxed
shell reported 95 GB while `os.statvfs` on the repo reported 7 TB: the difference between "27B is
impossible here" and "27B is fine".

| preset | base | licence | notes |
|---|---|---|---|
| `tiny` (default) | SmolLM2-360M-Instruct | apache-2.0 | CPU-viable, the pipeline proof |
| `small` | Llama-3.2-1B-Instruct | **llama-3.2** | name must start with `Llama-` |
| `base` | Llama-3.2-3B-Instruct (gated) | **llama-3.2** | same, plus an HF login |
| `qwen-4b` | Qwen3-4B-Instruct-2507 | apache-2.0 | best quality-per-hour step up |
| `qwen-7b` | Qwen2.5-7B-Instruct | apache-2.0 | ~20x `tiny`, under an hour on a 4090 |
| `qwen-14b` | Qwen2.5-14B-Instruct | apache-2.0 | comfortable QLoRA on 24 GB |
| `qwen-27b` | Qwen3.8-27B | apache-2.0 | post-trained and **multimodal** (`Qwen3_5ForConditionalGeneration`); thinking template, hybrid attention; see below |

Qwen2.5-3B-Instruct is deliberately absent: its card says `other`, not apache-2.0, unlike every
other size in that family.

`qwen-27b` is the odd one out and has never been run. Three things make it unlike the rest, all of
them verified against its model card and config on 2026-09-12: it is post-trained rather than a
base checkpoint; it is multimodal (`pipeline_tag: image-text-to-text`, so the merge drops the
vision tower); and only 16 of its 64 layers use `q/k/v/o_proj` (the other 48 are linear-attention
layers under different names, which is why its preset carries `target_modules: "all-linear"`
instead of the standard list). Its chat template also wraps every assistant turn in an empty
`<think></think>` block, so an SFT run teaches that shape too. Treat it as an experiment.

### Device modes

`--device auto` (default) uses CUDA when it is there. `--device cpu` forces CPU: fine at `tiny`,
measured in days above ~3B. `--device hybrid` fills the GPU to a ceiling and spills the remainder
into CPU RAM via accelerate's `max_memory`; slower per step because offloaded layers cross PCIe
twice, but it is the difference between running and an OOM when a model does not fit in VRAM
alone.

A present card with a broken driver looks exactly like no card at all to torch, so `--device cuda`
fails loudly with a pointer at `nvidia-smi` rather than silently training on CPU for a day.

### Throughput

Defaults now include: example **packing** (marola's ~2.8k rows are mostly far shorter than the
2048-token window, so without it most of every batch is padding, the single biggest win here),
**gradient checkpointing** (~20% slower per step, large drop in activation memory, which is what
makes the bigger presets fit), **SDPA attention**, **TF32** matmuls, a **fused AdamW** on CUDA,
**double quantization** in the 4-bit config, and `save_total_limit=1` so a 27B run does not write
~100 GB of unread checkpoints per epoch. Per-device batch and gradient accumulation are chosen by
model size to keep the effective batch at ~8; override with `--batch`/`--grad-accum`.

## Publishing to Hugging Face (MIP-0025 §5.1, MIP-0033 §5.3)

**Publish the merged model, never the adapter.** `train_lora.py`/`train_dpo.py` produce a LoRA
*adapter*, and `convert_lora_to_gguf.py` turns that into an adapter-GGUF (`out/tiny/adapter.gguf`,
~17 MB). That file works locally only because Ollama already holds the base weights and
`Modelfile.adapter` names them (`FROM llama3.2:1b` + `ADAPTER ...`). It is not a model, and
`ollama run hf.co/<you>/<repo>` (which pulls a repo and expects standalone model GGUFs) has no
base to attach it to. Publishing the adapter produces a repo that looks right and cannot run.

So the chain is merge → convert → quantize → publish (MIP-0025 §5.1), with `merge_export.py`
covering the first three:

```bash
pip install -r finetune/requirements.txt   # torch/transformers/peft, plus huggingface_hub
huggingface-cli login                       # one-time; a fine-grained token scoped to this repo
                                            # is enough — publish_hf.py only calls create_repo
                                            # and upload_file

just finetune-merge preset=tiny                            # dry run: prints the plan, writes nothing
just finetune-merge preset=tiny llama_cpp=~/src/llama.cpp   # merge + f16 GGUF + Q4_K_M/Q8_0

just finetune-publish \
  repo=<you>/marola-sea-tiny-GGUF \
  gguf=finetune/out/marola-sea-tiny-Q4_K_M.gguf \
  base=HuggingFaceTB/SmolLM2-360M-Instruct

# then, from any machine with Ollama — MIP-0025 §7's acceptance test:
ollama run hf.co/<you>/marola-sea-tiny-GGUF
```

With both adapters present, `merge_export.py` merges `out/<preset>/dpo-adapter` by default: DPO continues
training *from* the SFT adapter, so the DPO output already contains the SFT weights and is the
better checkpoint. `--adapter out/<preset>/adapter` publishes the SFT-only one instead.

Pass `--dry-run` (append after the `just` recipe's own args) to write `CHECKSUMS`/`README.md`
locally without uploading, to review the model card first. **Check the base model's licence
before publishing** (`--base-license`, default `apache-2.0`: correct for SmolLM2, wrong for a
Llama-based checkpoint): a model fine-tuned from Llama weights must have "Llama" at the start of
its published name per Meta's Community License (MIP-0025 §5.1(3)); this script does not enforce
that; it is a human check before the repo goes up.

## Is it OK to publish a model built on someone else's open model?

Yes: that is what fine-tuning is, and both bases here permit it. But **the base model's licence
follows the derivative**, and the obligations differ sharply between presets, so the answer is
not the same for `tiny` as for `small`/`base`. Checked 2026-09-07 against the model cards:

**`tiny`: [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct),
Apache-2.0.** The permissive case. No naming requirement of any kind, and a fine-tune may be
released under a licence of your choosing. Apache-2.0 still asks that a copy of the licence and
the copyright notice travel with the distribution, and that modifications be stated; a fine-tune
is a modification, so say so in the model card. `publish_hf.py --base-license apache-2.0` (its
default) is correct here.

**`small`/`base`: [Llama-3.2](https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct), Llama 3.2
Community License.** Not Apache, and two obligations bite on any published derivative. §1.b.i:

> "If you use the Llama Materials or any outputs or results of the Llama Materials to create,
> train, fine tune, or otherwise improve an AI model, which is distributed or made available, you
> shall also include 'Llama' at the beginning of any such AI model name."

and, in the same section, you must "(A) provide a copy of this Agreement with any such Llama
Materials; and (B) prominently display 'Built with Llama' on a related website, user interface,
blogpost, about page, or product documentation."

So a Llama-derived marola-sea must be named `Llama-marola-sea-*`, ship the agreement, and carry a
"Built with Llama" notice, and must **not** be published as `apache-2.0`. `unsloth/Llama-3.2-1B-Instruct`
is a mirror of Meta's weights, so the `small` preset inherits exactly the same terms as `base`.

`merge_export.py` enforces the naming half automatically (`llama_prefix()`, self-tested in both
directions), because a wrong name is the one mistake you cannot fix after publishing without
breaking every pull. The "Built with Llama" notice, the bundled agreement and the `--base-license`
value are still human steps before the upload. The Llama licence also carries further terms
(acceptable-use, and a threshold clause for very large deployments) that are worth reading in full
rather than summarising here.

## First-release readiness (marola-sea, MIP-0025/MIP-0033)

What's real today vs. what's still missing before "marola-sea-1.0" is a real, published release:

| Step | Status |
|---|---|
| A real training run on real hardware | **done**: `tiny` preset (SmolLM2-360M), CPU, eval loss 3.032→2.866→2.799 over 3 epochs |
| LoRA → adapter-GGUF conversion | **done**: `finetune/out/tiny/adapter.gguf` exists locally (gitignored, not in git). Enough for local Ollama use via `Modelfile.adapter`; **not** enough to publish |
| Adapter → merged model → quantized GGUF | **tooling done, run not done**: `finetune/merge_export.py` / `just finetune-merge`. Needs `pip install -r finetune/requirements.txt` and a llama.cpp checkout; this is the step that makes `ollama run hf.co/...` possible at all |
| Runs end-to-end through marola | **done**: `ollama create` + `Modelfile.adapter`, then `just run -- --summarize` |
| HF publish tooling | **done this session**: `finetune/publish_hf.py` / `just finetune-publish`, not yet run against a real HF account |
| Actual HF publish | **not done**: needs the maintainer's own `huggingface-cli login` and a real upload; nothing here can do that unattended. Publish the merged `marola-sea-tiny-Q4_K_M.gguf`, not the adapter |
| `just benchmark` numbers for this checkpoint | **not done**: `docs/benchmarks/` has no `tiny`-preset run yet; do this before trusting it over the plain base model (§7 of MIP-0025) |
| A `small`/`base`-preset run (better quality) | **not started**: `tiny` is a pipeline proof, explicitly not a quality bar (this README's own framing, top of file) |
| Ollama-registry push (optional 2nd channel) | **not started**: needs `ollama signin`, a human step (MIP-0025 §5.1(2)) |

The `tiny` run's job was to validate the pipeline end to end on hardware anyone has, which it did.
The remaining gap to a real "release" is compute (a `small`/`base` run) and the human steps above
(HF login, an actual upload, a benchmark run); no more design work is needed, per MIP-0025 §5.1's
already-verified plan.

## What is deliberately not here

- No cloud training. Paid cloud fine-tuning would fall under `AGENTS.md`'s cost rule.
- No attempt to fine-tune facts in. A 3B model with 40 examples will not learn marine biology; it
  will learn to sound like it did. Facts come from `knowledge/` via RAG, with citations.
</content>
