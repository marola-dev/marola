# MIP-0048: Scaling marola-sea — which model, which checkpoint, which hardware, and the data ceiling

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Opus 5, for M. Hoffmann (session of 2026-09-07, consolidating findings from PR #277) |
| **Created** | 2026-09-07 |
| **Phase** | 0 (local-only; no Azure, no earlier-phase prerequisite missing) |
| **Related** | MIP-0025 (marola-sea itself), MIP-0032 (model × strategy benchmark matrix), MIP-0007 (a different kind of model entirely), `FUTURE-WORK.md` §9.1, `AI-103-MAPPING.md` row "Fine-tuning a model" |
| **Effort** | M — no new module: preset tables, training flags and a preflight estimator already exist on `experiment/qwen-presets-and-training-perf` (PR #277). The work left is corpus growth and one real GPU run, not new machinery |
| **Gain** | user value (a domain model that answers ocean questions usefully rather than proving a pipeline); exam coverage (AI-103 "Fine-tuning a model" — today "Recipe written, not run (no GPU)"); infra/dev-loop (a preflight that says whether a run fits before four hours are spent finding out) |
| **Effort vs Gain** | do next for the 4B/7B step; **park 27B** — §4.4 shows it needs instruction data marola does not have and should not write |
| **Depends on** | MIP-0025 (the pipeline this scales — merged tasks 1-5, publish tooling in place). Needs a working NVIDIA driver on the reference machine, which is a host fix and not a MIP. Coordinates with MIP-0032, which would benchmark whatever this produces; neither blocks the other. No Phase 1 gate, no paid Azure resource, so `AGENTS.md`'s cost rule does not apply |
| **Blocked by** | 0025 |
| **Risk** | Spending a GPU day on a bigger model that answers no better, because the ceiling is the corpus (168 unique facts) and not the parameter count — §4.5 |
| **Cost so far** | — |

## 1. Summary

marola-sea today is SmolLM2-360M fine-tuned on marola's corpus: a proof that the pipeline works
end to end, explicitly not a quality bar. This MIP records what it would take to make it good,
and — more usefully — which of the obvious upgrades are worth it. The short answer is that
changing the base model is cheap and helps a little; growing the corpus is the only thing that
raises the ceiling; and the 27B model that prompted this research is the wrong choice for reasons
that have nothing to do with hardware.

## 2. Motivation

`finetune/README.md` is honest that the `tiny` preset "was to validate the pipeline end to end on
hardware anyone has, which it did", and that "quality itself is rough at this scale". MIP-0025 §7
asks for a benchmark against the plain base model before trusting the tuned one, and
`docs/benchmarks/` still has no `tiny` run. So the state is: a working pipeline, an unmeasured
model, and no written basis for choosing what to run next.

The concrete trigger was a request to train Qwen3.8-27B on an RTX 4090. Researching that turned up
three findings that are worth more than the answer to the original question, and are recorded in
§4 rather than lost in a chat log.

## 3. User-visible change

None directly — this MIP chooses a model, it does not ship a feature. The downstream effect is on
`--ask` and the chat widget, where today a 360M model produces answers like this real output from
the merged Q4_K_M GGUF through Ollama (2026-09-07):

```
$ ollama run marola-sea:test "In one sentence: is it safe to swim when jellyfish risk is high?"
Most stings are harmless, but a sting in any part of the body can cause redness, itching, and pain.

Source: en.wikipedia.org/wiki/Jellyfish_(marine_animal)
```

The trained format is right — it cites a source, which is exactly what `build_dataset.py` teaches
— and the content dodges the question that was asked. That gap between *format learned* and
*question answered* is what a bigger model and a bigger corpus each address differently, and §4.5
argues only one of them is the binding constraint.

## 4. Data sources and dependencies reviewed

### 4.1 The Qwen family (candidate bases)

Fetched from `huggingface.co/api/models?author=Qwen` on 2026-09-07 — 60 models by download count.
Sizes and licences below are from that response, not from memory.

| model | params | licence | note |
|---|---|---|---|
| Qwen3.5-0.8B / Qwen3-1.7B / Qwen3.5-2B | 0.8-2B | apache-2.0 | CPU-viable |
| Qwen2.5-1.5B-Instruct | 1.5B | apache-2.0 | Instruct |
| **Qwen2.5-3B-Instruct** | 3B | **`other`** | the one exception in the family — excluded |
| **Qwen3-4B-Instruct-2507** | 4B | apache-2.0 | best quality-per-hour candidate |
| **Qwen2.5-7B-Instruct** | 7.6B | apache-2.0 | the safe default |
| **Qwen2.5-14B-Instruct** | 14.8B | apache-2.0 | comfortable QLoRA on 24 GB |
| Qwen2.5-32B-Instruct / Qwen3-32B / Qwen3.6-27B | 27-32B | apache-2.0 | VRAM-tight, see §4.3 |
| **Qwen3.8-27B** | 27B | apache-2.0 | **base, not Instruct** — §4.4 |
| Qwen3.5-35B-A3B, Qwen3.5-122B-A10B | 35-122B | apache-2.0 | MoE — all experts must be resident; out of reach |
| Qwen3-Embedding-0.6B / 4B / 8B, Qwen3-Reranker-4B | 0.6-8B | apache-2.0 | a different lever — §4.6 |

Apache-2.0 throughout (bar the two noted) is the decisive property: unlike the `small`/`base`
Llama presets, a Qwen derivative carries **no naming obligation**. Llama 3.2's Community Licence
§1.b.i requires `"Llama" at the beginning of any such AI model name` plus a bundled agreement and a
"Built with Llama" notice — verified against the model card on 2026-09-07 and quoted in
`finetune/README.md`.

### 4.2 The reference machine

Measured on 2026-09-07, not assumed:

| resource | measured | binding? |
|---|---|---|
| GPU | RTX 4090, 24 GB — **driver down** (`nvidia-smi` fails; `lspci` sees the card) | yes, until fixed |
| RAM | 188 GB total, ~158 GB available | no |
| CPU | 32 threads, 50 °C at load 14 (high = 80, crit = 100) | no |
| disk | **7,030 GB** free on the repo's filesystem | no |

One methodological note worth keeping: `df /home` inside a sandboxed shell reported **95 GB** while
`os.statvfs` on the repo directory reported **7,030 GB**. Acting on the first number produced a
wrong conclusion ("27B cannot finish here") that survived two messages before being caught. The
preflight tool in PR #277 measures the directory artifacts are actually written to, for this
reason.

### 4.3 What 24 GB of VRAM actually allows

QLoRA holds the base in 4-bit (~0.55 GB per billion parameters with double quantization) plus
adapter weights, gradients and optimizer state — the adapter is under 1% of parameters, so
activations dominate the rest.

| params | 4-bit weights | QLoRA train | full export disk | verdict on this machine |
|---|---|---|---|---|
| 4B | ~2.2 GB | ~4.7 GB | ~23 GB | comfortable |
| 7.6B | ~4.2 GB | ~6.7 GB | ~40 GB | comfortable |
| 14.8B | ~8.1 GB | ~11 GB | ~80 GB | comfortable |
| 27B | ~15 GB | ~17 GB | ~154 GB | fits — VRAM tight, disk irrelevant at 7 TB |
| 35B MoE | all experts resident | — | — | does not fit |

So hardware does **not** rule out 27B on this machine. §4.4 does.

### 4.4 Instruct vs base — the finding that decides it

`Qwen3.8-27B` is a **base** model. The HF listing shows no Instruct variant for it, unlike
Qwen2.5-7B-Instruct or Qwen2.5-14B-Instruct.

That matters more than parameter count. A base model has never been taught to follow an
instruction, respect a system prompt, refuse, or hold a turn. Fine-tuning one on marola's 2,774
domain rows produces a model that completes marola-shaped prompts and does nothing else — no
assistant behaviour, because nothing in the data teaches it.

Closing that gap means general instruction data, mixed with the domain rows. Published reference
points: LIMA reported ~1,000 carefully curated, highly diverse examples sufficient to align a 65B
base; Dolly used 15k; Alpaca 52k. Marola would need somewhere in that range of **general**
instruction data it does not have and has no business authoring.

An Instruct checkpoint has already had that work done by its publisher, for free. **This is the
argument for `qwen-14b` over `qwen-27b`: same 24 GB card, already aligned, Apache-2.0.**

### 4.5 The data ceiling — the finding that matters most

Measured from the built dataset on 2026-09-07:

```
dataset rows:     2,774
unique answers:     168
corpus:           7 files, 2,535 words
```

The 2,774 rows are **168 distinct facts asked roughly sixteen ways each**. `build_dataset.py`'s
augmentation — every chunk and sentence asked with several paraphrased templates — inflates the row
count, not the information. The corpus is about five pages of text.

This reframes every other question in this MIP. A 14B model trained on 168 facts still knows 168
facts. Raising the augmentation multiplier changes nothing: the model sees the same fact reworded.
The ceiling is `knowledge/`, and no choice of base model raises it.

Rough shape of the fix: 10-20× more corpus (25k-50k words) would yield ~1,700-3,400 unique facts.
The `corpus-doc` skill exists to add sourced entries to `knowledge/` and is the correct tool.

Tool-call coverage has a smaller version of the same problem: 320 rows across the four real MCP
tools, generated from only **2 coordinate pairs and 3 radii**. More real locations would generalise
better at near-zero cost.

### 4.6 The lever nobody asked about

`--ask` answers are produced by retrieval *then* generation. The retriever is `nomic-embed-text`.
If the right chunk is not retrieved, no amount of model quality recovers it — and marola has never
measured retrieval quality separately from answer quality. Qwen3-Embedding-0.6B/4B and
Qwen3-Reranker-4B (both apache-2.0) are candidates. Not proposed here; noted in §11 as a follow-up.

### Pick

**`qwen-7b` (Qwen2.5-7B-Instruct) for the next real run**, `qwen-14b` if the 4090 is healthy and
time allows; **`qwen-27b` parked** pending general instruction data that is out of scope. And the
corpus work in §4.5 outranks all of them.

## 5. Design

Nothing new is required — the machinery landed in PR #277 on
`experiment/qwen-presets-and-training-perf`:

- `finetune/train_lora.py` — `PRESETS` gains `qwen-4b`, `qwen-7b`, `qwen-14b`, `qwen-27b`, each
  carrying `params_b` and `licence`; `--device auto|cuda|cpu|hybrid`; `--batch`/`--grad-accum`;
  `--no-packing`/`--no-grad-checkpointing`.
- `finetune/preflight.py` — VRAM/RAM/disk/ETA before a run, measuring the real filesystem.
- `finetune/merge_export.py` — unchanged; already merges any adapter into any base.

Deterministic vs LLM is unchanged and worth restating, since this MIP is about a model: **nothing
here touches the map.** `SiteBuilder` contains no LLM reference and `Swimability.score` is a pure
function (`ARCHITECTURE.md` §3b). A better marola-sea improves `--ask` and the chat widget only.

**LoRA rank and target layers.** Today r=16, α=32, targeting all seven projections
(`q,k,v,o,gate,up,down`). Target modules should stay as they are — attention-only LoRA is the common
economy and it costs quality on domain adaptation. Rank is worth raising to 32 at 7B and above:
adapter memory is negligible next to a 4-bit base, and rank is the main capacity knob. DoRA is a
one-flag `peft` change usually worth a small quality gain at the same rank; untested here.

**What to change, in order:**

1. Fix the NVIDIA driver (host, not this repo).
2. `just finetune-preflight preset=qwen-7b` — confirm the numbers on the real machine.
3. Grow `knowledge/` per §4.5. This is the highest-value item and needs no GPU.
4. Train `qwen-7b` with `--rank 32`, benchmark against the base model per MIP-0025 §7.
5. Only then consider 14B.

## 6. Scoring / safety impact

None. Swimability scoring, the water verdict and the safety footer are deterministic Scala and are
not touched by any model change. A larger model does not gain authority over whether marola tells
someone to swim — that boundary is the point of `ARCHITECTURE.md` §3b.

## 7. Verification plan

- **Already passing** (PR #277): `python3 finetune/preflight.py --self-test`, 9 assertions, wired
  into `just quality-other`.
- **Add** `docs/benchmarks/` entries for `tiny` and for whichever Qwen preset is run — MIP-0025 §7
  asks for the comparison against the untuned base and it has never been done. Without it, "the
  tuned model is better" is an assumption.
- **Live check**: `just finetune-preflight preset=qwen-7b` on the reference machine with a working
  driver, then the full chain to `ollama run hf.co/<user>/<repo>`.
- **Done looks like**: a Qwen-based marola-sea published, and a benchmark row showing it beats both
  the untuned base *and* the 360M tuned model on marola's own questions. If it does not beat them,
  that is a finding to record, not to hide.

## 8. Risks, limitations, and honest caveats

- **The corpus ceiling makes a bigger model look pointless.** 168 unique facts is the real limit;
  a 14B model may benchmark barely above the 360M one and the effort will look wasted. It will not
  be — but expectations should be set now, not after the run.
- **Nothing in PR #277 has been run.** The Qwen presets are wired and preflighted, never trained —
  there is no working CUDA device on the reference machine at the time of writing.
- **The ETA estimator is calibrated on one data point** (SmolLM2-360M on CPU) and scaled by
  parameter count. Treat its numbers as order-of-magnitude.
- **CPU training above ~3B is days.** The estimator warns; the warning is real.
- **A tuned small model can be worse than an untuned larger one.** That is exactly what MIP-0032's
  benchmark matrix exists to find out, and why §7 insists on the comparison.

## 9. Alternatives considered

- **Do nothing — keep `tiny`.** Legitimate: the pipeline is proven and the corpus is the real
  ceiling. Loses because "rough at this scale" is not a model anyone should publish as v1.
- **Train Qwen3.8-27B as originally asked.** Hardware allows it (§4.3). Loses on §4.4: it is a base
  model, and aligning it needs instruction data marola should not author.
- **Llama-3.2-3B (`base` preset).** Works, but carries the naming obligation, the bundled agreement
  and the "Built with Llama" notice — for no quality advantage over an Apache-2.0 Qwen of similar
  size.
- **Grow the corpus and keep the 360M model.** Genuinely competitive, and cheaper. Rejected only as
  an *exclusive* choice: §4.5's corpus work is proposed alongside, not instead.
- **Skip fine-tuning; rely on RAG with a stock model.** Arguably the best answer for factual
  accuracy. It loses the tone and citation format the fine-tune teaches, and MIP-0032 is the right
  place to settle it with numbers.

## 10. Exam-coverage mapping

`AI-103-MAPPING.md` row **"Fine-tuning a model"** currently reads "Recipe written, not run (no
GPU); Tier 1 built and used live". A completed Qwen run on the 4090 would move it to run-and-
verified, closing one of the two gaps that section names explicitly. No AI-500 row is affected —
this is a single-model change, not a multi-agent one.

## 11. Open questions

- **Does a 7B tuned model actually beat the untuned 7B on marola's questions?** Unknown, and the
  whole justification rests on it. MIP-0032's matrix is the instrument.
- **Is DoRA worth the flag?** Reported to beat LoRA at equal rank; not tested here.
- **What is the right corpus size?** §4.5's 25k-50k words is arithmetic from the current
  augmentation ratio, not an empirical finding.
- **Follow-up MIP:** *retrieval quality as a separate, measured axis* — marola has never measured
  whether `--ask` failures are retrieval misses or generation misses, and Qwen3-Embedding /
  Qwen3-Reranker are candidates to improve the former. It needs the next MIP number; it is out of
  this MIP's scope, which is the generator.
- **Human decision:** whether to publish a Qwen-based marola-sea as v1 and retire the 360M one, or
  publish both and let the benchmark decide.

## Appendix

### Checked live

- `https://huggingface.co/api/models?author=Qwen&sort=downloads&limit=60` — 2026-09-07. Returned 60
  models; sizes and licence fields in §4.1 are from this response. Confirmed `Qwen2.5-3B-Instruct`
  reports `other` while its siblings report `apache-2.0`, and that `Qwen3.8-27B` appears with no
  Instruct variant.
- `https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct` — 2026-09-07. Licence `apache-2.0`;
  no naming requirement on derivatives.
- `https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct` — 2026-09-07. Llama 3.2 Community
  Licence §1.b.i quoted verbatim in §4.1.
- Reference machine, 2026-09-07: `nvidia-smi` fails with "couldn't communicate with the NVIDIA
  driver" while `lspci` reports `NVIDIA Corporation AD102 [GeForce RTX 4090]`; `free -g` 188 GB;
  `nproc` 32; `sensors` Package id 0 +50.0 °C (high +80, crit +100); `os.statvfs` on
  `finetune/` 7,030 GB free against `df /home` 95 GB.
- Built dataset, 2026-09-07: 2,774 rows across `train.jsonl`/`eval.jsonl`, 168 unique assistant
  answers; `knowledge/` 7 files, 2,535 words.
- Merged model artifacts produced this session: `merged/model.safetensors` 723 MB,
  `marola-sea-tiny-f16.gguf` 725 MB, `Q4_K_M` 270 MB, `Q8_0` 386 MB; the Q4_K_M loaded into Ollama
  and answered in the trained "Source: <url>" format (§3).

### Not checked

- **Every ETA in §4.3 and the estimator.** Scaled from one measured run (SmolLM2-360M on CPU); no
  Qwen model has been trained on this machine.
- **VRAM figures** are arithmetic from ~0.55 GB/B for 4-bit plus an activation allowance, not
  measured with `nvidia-smi` during a real run.
- **LIMA / Dolly / Alpaca dataset sizes** in §4.4 are recalled from the literature, not fetched from
  the papers this session. The qualitative claim (a base model needs general instruction data) is
  not in doubt; the specific counts should be re-checked before anyone plans against them.
- **DoRA's reported quality advantage** — from memory, not verified.
- **Qwen3-Embedding / Qwen3-Reranker quality** — listed in the HF response, never evaluated here.
