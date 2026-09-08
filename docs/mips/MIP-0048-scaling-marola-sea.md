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
| **Depends on** | MIP-0025 (the pipeline this scales — merged tasks 1-5, publish tooling in place). No hardware prerequisite — the host's RTX 4090 is healthy (driver 595.84, CUDA 13.2). Coordinates with MIP-0032, which would benchmark whatever this produces; neither blocks the other. No Phase 1 gate, no paid Azure resource, so `AGENTS.md`'s cost rule does not apply |
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
| GPU | RTX 4090, 24 GB — **healthy**: driver 595.84, CUDA 13.2, 34 °C idle, 933 MiB used (host `nvidia-smi`, 2026-09-07) | no |
| RAM | 188 GB total, ~158 GB available | no |
| CPU | 32 threads, 50 °C at load 14 (high = 80, crit = 100) | no |
| disk | **7,030 GB** free on the repo's filesystem | no |

**A methodological note that earned its place by being learned twice.** Both of the machine facts
above were first measured from inside a sandboxed agent session, and both were wrong about the
machine:

- `df /home` in the sandbox reported **95 GB**; `os.statvfs` on the repo reported **7,030 GB**. The
  first number produced a confident "27B cannot finish here" that survived two messages.
- `nvidia-smi` in the sandbox reports "couldn't communicate with the NVIDIA driver" because the jail
  maps no `/dev/nvidia*` nodes. On the host the same command reports **driver 595.84, CUDA 13.2, a
  healthy RTX 4090**. An earlier draft of this MIP recorded the GPU as broken and built
  recommendations on it.

The rule this MIP adopts: **a measurement taken inside the sandbox describes the sandbox, not the
machine.** Anything about hardware gets confirmed on the host before it is written down, and
`finetune/preflight.py` is designed to be run there rather than by an agent.

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

**`qwen-7b` (Qwen2.5-7B-Instruct) for the next real run**, `qwen-14b` next — the 4090 is healthy,
so both are a matter of hours, not hardware; **`qwen-27b` parked** pending general instruction data that is out of scope. And the
corpus work in §4.5 outranks all of them.


## 4.7 Growing the corpus with quality, not volume

§4.5 says the ceiling is 168 unique facts. Raising it badly is easy and would make the model
worse, so this records how the field says to do it well, and what marola already has lying around.

**How much.** Published 2026 guidance: 500-1,000 examples move the needle on formatting or
classification; **3,000-10,000 high-quality examples** are the range for adapting a model to a new
domain. marola has 168 unique facts behind 2,774 rows — an order of magnitude short on the axis
that matters, and already past the point where more paraphrases help.

**Real data first.** The consistent advice is to prioritise real domain text and, when generating,
generate *from real seeds* rather than from generic prompts. marola already ingests real sources it
does not use for training:

- **The agency bulletins it already parses.** `IneaPdfParser`/`InemaPdfParser` read real INEA/RJ and
  INEMA/BA bathing-water PDFs. Every historical bulletin is real, sourced, dated domain text.
- **`scripts/arxiv_digest.py`** already fetches and caches ocean-forecasting papers. Abstracts are
  citable domain prose.
- **Open-Meteo and OSM documentation** — the vocabulary of the tool-call layer.
- **Tide tables and the existing `sea_lore.json`** — small, but curated and sourced.
- **Lifeguard and civil-defence safety material** (SALVAMAR/Bombeiros for SC, and the equivalents in
  RJ/BA) — exactly the register the safety footer answers in.

**Synthetic, done properly.** The 2026 Self-Instruct shape is: 150-200 human-written seed tasks, a
stronger teacher model to expand them with a diversity-promoting prompt, then **a judge model that
re-scores generated rows and discards those below a threshold** — commonly sampling 5-10% for
audit. CRAFT (arXiv 2409.02098) is the retrieval-flavoured variant: pull real corpus passages, then
augment around them, which fits marola better than free generation because every row stays anchored
to a citable source.

Two constraints marola must add to that recipe, from its own rules:

1. **`AGENTS.md`'s "sourced or clearly labelled, never invented"** already forbids unsourced facts
   reaching a user. Training data deserves the same bar — `build_dataset.py --self-test` already
   asserts every generated fact appears verbatim in the `knowledge/*.md` it cites, and any synthetic
   expansion must keep that property or the assertion becomes theatre.
2. **The teacher's licence follows the output.** This is the same clause that made a Llama teacher a
   problem for a SmolLM2 student (see MIP-0025's licence note): Llama 3.2 §1.b.i reaches "any
   outputs or results of the Llama Materials" used to train a model. An Apache-2.0 teacher (a larger
   Qwen) has no such term; a commercial API's terms of service need reading before its output enters
   a published model's training set.

**Capability collapse** is the failure mode to watch: narrow domain fine-tuning on a small set can
destroy general ability (Dial-insight, arXiv 2403.09167). The defence is mixing in general
instruction data — which is also §4.4's argument, arriving from the other direction.

**Precedents from other domains** worth copying rather than inventing: REx86 (arXiv 2510.20975)
builds a local domain model for x86 reverse engineering from a modest curated corpus; both it and
CRAFT are the same shape marola needs — narrow domain, small real corpus, careful augmentation, a
local model at the end.

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

1. `just finetune-preflight preset=qwen-7b` on the host — confirm the numbers where the GPU is
   actually visible.
2. Grow `knowledge/` per §4.5 and §4.7. This is the highest-value item and needs no GPU.
3. Train `qwen-7b` with `--rank 32` (and `use_dora=True`, Appendix A), benchmark against the
   untuned base per MIP-0025 §7.
4. Only then consider 14B.


### Multi-stage and resumable training

A long run should not be an all-or-nothing bet on the machine staying up. Three separable
mechanisms, in increasing order of how much they change the design:

**1. Checkpoint and resume — the direct answer.** HuggingFace `Trainer` (which `SFTTrainer`
extends) writes a full checkpoint: model/adapter weights, optimizer state, LR-scheduler state, RNG
state and the step counter. `trainer.train(resume_from_checkpoint=True)` picks up mid-epoch, not
just at an epoch boundary, so a run can be stopped and restarted across days, reboots or a moved
GPU. What marola needs to change to use it:

```python
# today, in train_lora.py's SFTConfig
save_strategy="epoch",          # a checkpoint only every epoch
save_total_limit=1,             # keeps the newest only

# for a multi-day run
save_strategy="steps",
save_steps=200,                 # tune so a crash costs minutes, not hours
save_total_limit=2,             # one to resume from, one as a fallback
```

plus a `--resume` flag passing `resume_from_checkpoint` through. Note the interaction with
`save_total_limit=1`, added in PR #277 to stop a 27B run writing ~100 GB of unread checkpoints:
that is still resumable, but it leaves no fallback if the newest checkpoint is truncated by the
crash that stopped the run. For long runs, 2 is the safer number.

**2. Staged training — marola already does this.** SFT and DPO are separate scripts producing
separate adapters (`out/adapter`, `out/dpo-adapter`), where DPO continues from the SFT adapter. That
is already a two-stage pipeline with a durable artifact between stages, and each stage can be run on
a different day. The natural third stage, if §4.7's corpus grows enough, is **continued
pre-training** on raw ocean text *before* the SFT stage — domain knowledge first, instruction
format second, preferences last.

**3. Batched corpus work.** The expensive part of this MIP is not GPU time, it is §4.7's corpus
growth, which is inherently incremental: every `knowledge/*.md` file added raises the ceiling a
little, `build_dataset.py` is deterministic and re-runnable, and its self-test asserts provenance on
every regeneration. There is no reason to wait for a "complete" corpus before training on the
current one — train, benchmark, add documents, retrain, and keep the benchmark rows to see whether
the corpus is actually helping.

The practical shape for a multi-day 14B run: `save_steps=200`, `save_total_limit=2`, run under
`tmux` or a systemd unit so an SSH drop does not kill it, and resume after any interruption. The
GPU only needs to be free while a stage is running, not for the whole calendar span.

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
- **Live check**: `just finetune-preflight preset=qwen-7b` **on the host**, where the GPU is
  visible, then the full chain to `ollama run hf.co/<user>/<repo>`.
- **Done looks like**: a Qwen-based marola-sea published, and a benchmark row showing it beats both
  the untuned base *and* the 360M tuned model on marola's own questions. If it does not beat them,
  that is a finding to record, not to hide.

## 8. Risks, limitations, and honest caveats

- **The corpus ceiling makes a bigger model look pointless.** 168 unique facts is the real limit;
  a 14B model may benchmark barely above the 360M one and the effort will look wasted. It will not
  be — but expectations should be set now, not after the run.
- **Nothing in PR #277 has been run.** The Qwen presets are wired and preflighted, never trained.
  Not for want of hardware — the host's RTX 4090 is healthy — but because the agent session that
  wrote them has no GPU access. The first real run is the maintainer's, on the host.
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

### Appendix A — State-of-the-art training techniques worth adopting

Researched 2026-09-07. The user's constraint was explicit: a longer run is acceptable if the result
is better. These are ordered by confidence, and none has been run here.

**Adopt now, near-free:**

- **DoRA** (`use_dora=True` in `peft`). Decomposes the weight update into magnitude and direction
  and LoRA-fits only the direction; reported to converge faster and match full fine-tuning at the
  same rank. 2026 guides describe it as a default-on free upgrade. One flag in `train_lora.py`.
- **All-linear target modules.** Current 2026 benchmarks say including q, k, v, o, gate, up and down
  consistently beats attention-only for minimal VRAM cost. **marola already does this** — worth
  recording as validated rather than changed.
- **rsLoRA** (rank-stabilised LoRA) — a scaling factor that makes the output scale invariant to
  rank. Recommended once r ≥ 32, which is exactly the rank §5 proposes.

**Adopt if the tooling proves out:**

- **Unsloth.** Reported ~2× faster training at ~50% less VRAM through fused kernels and PEFT
  optimisations, plus MoE support since Feb 2026 with a claimed 7-12× speedup there. Packaging is
  **not** the obstacle it looked like: `nix eval nixpkgs#python3Packages.unsloth` resolves to
  `python3.14-unsloth-2026.4.5` (checked 2026-09-07), so it is one line in
  `.github/nix-ml-env.nix` and needs no pip path or overlay. That makes it cheap to try; the speed
  and VRAM claims are still vendor figures and unmeasured here.

**Deliberately not proposed:**

- **Longer runs / more epochs.** The user offered more hours, but with 168 unique facts more epochs
  buys memorisation, not knowledge. Spend the hours on §4.7's corpus instead — this is the one place
  where "it can take longer" does not convert into quality.
- **Model merging.** See Appendix B: it is a legitimate technique, and it is precisely what the Rio
  project was criticised for presenting as training.

### Appendix B — Comparison with Rio de Janeiro's municipal LLM

In April 2026 Rio de Janeiro's city government, through IplanRio, launched a family of six models
("Rio 3"), including **Rio 3.0 Open at 235 billion parameters** and **Rio 3.0 Open Mini at 44
billion**, described as open source, with a total project cost of **R$ 500 mil** — claimed as "30
vezes menor" than an off-the-shelf system. A larger **Rio 3.5 Open at 397 billion parameters** was
published with strong benchmark numbers.

Within hours the benchmark claims were contested. The developer of N2 Pro analysed the release and
found that the published artifact combined the weights of **Qwen and Nex** — a *raw merge*, a
mathematical mixture of two existing models **with no additional training**, rather than the
distillation the project described, and without disclosing the use of N2 Pro. The prefecture
acknowledged that existing models had been reused, and said the release had been premature and
represented an unfinished intermediate version. Its director had earlier said of the Qwen base: *"o
modelo não tem nada a ver com o Qwen original, mas usamos a estrutura e o treinamento."*

Three things are worth stating fairly before the comparison. Merging is a **legitimate** technique
when disclosed — the criticism was about description, not method. A municipal government building
public AI capability is a good thing. And R$ 500 mil is genuinely small for the ambition.

|  | **marola-sea today** | **marola-sea proposed** (§4 pick) | **Rio 3.5 Open** |
|---|---|---|---|
| Parameters | **360 M** | 7.6 B (Qwen2.5-7B-Instruct) | **397 B** (claimed) |
| Base | SmolLM2-360M-Instruct | Qwen2.5-7B-Instruct | Qwen + Nex (merged) |
| Method | QLoRA SFT + tool-call SFT + **DPO** | same, larger base, r=32 | **raw weight merge, no training** |
| Evidence of training | eval loss 3.032 → 2.866 → 2.799 over 3 epochs; DPO to 0.2594 | to be measured | none — no training performed |
| Weights published | tooling ready, not yet uploaded | same | "open source"; location not confirmed in reporting |
| Licence | Apache-2.0 end to end, deliberately | Apache-2.0 | Qwen is Apache-2.0; Nex component unclear |
| Attribution | base model named in the model card by `publish_hf.py` | same | N2 Pro use undisclosed |
| Independent verification | GGUF loaded in Ollama and answered in trained format | benchmark planned (MIP-0025 §7) | claims contested within hours |
| Cost | ~$0 (local CPU) | one GPU-day | R$ 500.000 |

**The useful lesson is not the parameter count.** marola-sea is roughly a thousandth the size of
Rio's headline number and does strictly more actual training than a merge does. What separates the
two is not scale but **provenance**: which base, which licence, what was run, and what the numbers
were. This repo already has that discipline written down — `AGENTS.md`'s "sourced or clearly
labelled, never invented", the `Cost:` trailer on every PR, `finetune/README.md`'s "run, `tiny`
preset verified" versus "written, not run" vocabulary — and MIP-0025 §7 requires a benchmark against
the untuned base *before* claiming the tuned model is better.

The concrete practice to keep, stated as a rule this MIP adopts: **the model card must say what was
actually done.** `publish_hf.py` already generates the base model, the training-data description and
the eval numbers. If a future marola-sea is ever a merge rather than a fine-tune, the card says
merge. That is the whole difference between the two columns above.


### Appendix C — Curated lists worth watching

The fine-tuning landscape moves faster than a MIP can be revised, so these are the maintained
indexes to re-read before acting on anything in Appendix A rather than trusting this document's
snapshot of 2026-09-07. Listed as pointers, not endorsements — none was audited here beyond
confirming it exists and is on topic.

| List | Why it is relevant to marola |
|---|---|
| [Hannibal046/Awesome-LLM](https://github.com/Hannibal046/Awesome-LLM) | The general index — models, papers, tooling. The first place a new base model shows up. |
| [Curated-Awesome-Lists/awesome-llms-fine-tuning](https://github.com/Curated-Awesome-Lists/awesome-llms-fine-tuning) | Tutorials, papers and tools specifically for fine-tuning; the closest match to §5's decisions. |
| [pdaicode/awesome-LLMs-finetuning](https://github.com/pdaicode/awesome-LLMs-finetuning) | Second fine-tuning collection; useful as a cross-check when two lists disagree. |
| [horseee/Awesome-Efficient-LLM](https://github.com/horseee/Awesome-Efficient-LLM) | Efficiency-focused, with a dedicated [tuning.md](https://github.com/horseee/Awesome-Efficient-LLM/blob/main/tuning.md) — the right index for DoRA/rsLoRA-class techniques on one GPU. |
| [rafska/Awesome-local-LLM](https://github.com/rafska/Awesome-local-LLM) | Running models locally: the axis marola actually cares about, since Ollama is the deployment target. |
| [ethicals7s/awesome-local-ai](https://github.com/ethicals7s/awesome-local-ai) | Local-only tooling, no cloud or API keys — the same constraint as `ARCHITECTURE.md` §5's local default. |
| [mlabonne/llm-datasets](https://github.com/mlabonne/llm-datasets) | Post-training datasets. Directly relevant to §4.4's "general instruction data marola does not have" and §4.7's corpus work. |
| [onejune2018/Awesome-LLM-Eval](https://github.com/onejune2018/Awesome-LLM-Eval) | Evaluation tooling and benchmarks — the gap MIP-0025 §7 and MIP-0032 both point at. |

Two frameworks surfaced repeatedly across these lists and are worth naming next to Unsloth in
Appendix A: **Axolotl** (LoRA/QLoRA/DeepSpeed/PEFT, multi-GPU) and **xtuner** (explicitly supports
Qwen among others). Neither was evaluated here; both are alternatives to hand-rolling
`train_lora.py` further if its flag surface keeps growing.

### Checked live

- `https://huggingface.co/api/models?author=Qwen&sort=downloads&limit=60` — 2026-09-07. Returned 60
  models; sizes and licence fields in §4.1 are from this response. Confirmed `Qwen2.5-3B-Instruct`
  reports `other` while its siblings report `apache-2.0`, and that `Qwen3.8-27B` appears with no
  Instruct variant.
- `https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct` — 2026-09-07. Licence `apache-2.0`;
  no naming requirement on derivatives.
- `https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct` — 2026-09-07. Llama 3.2 Community
  Licence §1.b.i quoted verbatim in §4.1.
- Reference machine, 2026-09-07, **on the host**: `nvidia-smi` reports driver 595.84, CUDA 13.2,
  `NVIDIA GeForce RTX 4090`, 933 MiB / 24564 MiB used, 34 °C, P8. Inside the sandboxed agent
  session the same command fails and `/dev/nvidia*` does not exist — see §4.2. `free -g` 188 GB;
  `nproc` 32; `sensors` Package id 0 +50.0 °C (high +80, crit +100); `os.statvfs` on
  `finetune/` 7,030 GB free against `df /home` 95 GB.
- Built dataset, 2026-09-07: 2,774 rows across `train.jsonl`/`eval.jsonl`, 168 unique assistant
  answers; `knowledge/` 7 files, 2,535 words.
- `https://www.mobiletime.com.br/noticias/02/04/2026/prefeitura-do-rio-3-llms/` — 2026-09-07.
  Rio 3.0 Open 235B, Mini 44B, six models, R$ 500 mil, "30 vezes menor", Qwen-derived, described as
  open source; the article does not say where the weights are published. Director quote in
  Appendix B is from this page.
- Web search on the Rio controversy — 2026-09-07, results from Canaltech, Tecnoblog, Baguete, TMC:
  Rio 3.5 Open at 397B, contested benchmarks, the N2 Pro developer's finding that the artifact was a
  raw Qwen+Nex merge rather than the claimed distillation, and the prefecture's response that the
  release was premature and intermediate.
- Web search on 2026 fine-tuning practice — 2026-09-07: DoRA as a default-on upgrade, rsLoRA for
  r ≥ 32, all-linear target modules beating attention-only, Unsloth's ~2×/50% claims and Feb 2026
  MoE support.
- Web search for curated GitHub lists (Appendix C) — 2026-09-07, restricted to github.com. Returned
  the eight repositories linked there plus mentions of Axolotl and xtuner. Existence and topic
  confirmed from the search result titles/descriptions; none of the repositories was opened,
  audited, or its recommendations verified.
- Host `nvidia-smi`, 2026-09-07, pasted by the maintainer: driver 595.84, CUDA 13.2, RTX 4090,
  933 MiB / 24564 MiB, 34 °C. This corrected an earlier draft of this MIP that recorded the GPU as
  unusable based on a sandbox-side failure — see §4.2.
- Web search on domain dataset practice — 2026-09-07: 500-1,000 examples for formatting tasks,
  3,000-10,000 for domain adaptation; Self-Instruct with a judge filtering 5-10%; prefer real domain
  data and generate synthetic from real seeds. Papers surfaced: CRAFT (2409.02098), Dial-insight
  (2403.09167), REx86 (2510.20975).
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
- **Rio's published weights.** Not located or inspected. The merge finding in Appendix B is reported
  by the N2 Pro developer via the press coverage above, not independently reproduced here — the
  comparison table's "Rio 3.5 Open" column is therefore *as reported*, not *as verified*. The Nex
  component's licence in particular was not established.
- **Every SOTA claim in Appendix A** — DoRA/rsLoRA/Unsloth figures are from 2026 guides and vendor
  claims found in search, not benchmarked here and not read from the primary papers.
- **Unsloth's runtime behaviour** — the package is present in nixpkgs (verified), but it has not
  been imported, run, or benchmarked here; the ~2×/50% figures remain vendor claims.
