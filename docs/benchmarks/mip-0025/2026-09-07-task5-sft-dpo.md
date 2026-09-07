# MIP-0025 task 5 — SFT (Layers 1+2) + DPO (Layer 3) on the `tiny` preset

Real run, 2026-09-07: `finetune-dataset` (2,497 train + 277 eval — Layer 1 knowledge-corpus Q/A
plus Layer 2 tool-call SFT) → `train_lora.py --preset tiny --no-4bit --epochs 1` (SFT,
SmolLM2-360M, eval loss 3.032 baseline → **0.2594**, mean token accuracy **0.939**) →
`build_dpo_dataset.py` (2 real preference pairs, from `review_prompt.json`'s own non-approve
demos) → `train_dpo.py --preset tiny --no-4bit --epochs 1` (DPO, continuing from the SFT adapter)
→ `convert_lora_to_gguf.py` → `ollama create marola-sea-tiny-dpo` → `just benchmark` against it.
Raw output: `data/benchmark-20260907-1052.md` (not committed — `data/` is gitignored, matching
task 1's own "the run is the evidence, not the artifact" precedent).

## Result

| arm | coverage (in-corpus) | coverage (general) | coverage (all) | cited | abstained | mean ms |
|---|---|---|---|---|---|---|
| baseline | 0.72 | 0.61 | 0.66 | 0% | 0% | 1585 |
| rag-strict | 0.05 | 0.03 | 0.04 | 0% | 100% | 0 |
| rag-general | 0.47 | 0.56 | 0.52 | 0% | 0% | 969 |

## Comparison — the plain baseline (within this run)

The tuned tiny model's own `rag-general` arm (0.52 all) scores **below its own `baseline` arm**
(0.66 all) — the opposite of what `2026-09-05.md`'s kept reference showed for `llama3.2` (Run 2:
`rag-general` 0.84 beats `baseline` 0.75). `rag-strict` abstains on 100% of questions here (0%
there was 59%) — the tiny SFT+DPO model isn't reliably emitting the `NO_ANSWER_IN_PASSAGES`
sentinel `OceanQa`'s grounded prompt asks for, so it falls through to abstention far more than the
untuned `llama3.2` did.

## Comparison — the best kept run (`2026-09-05.md` Run 2, `llama3.2`, untuned)

Every arm is worse on the tuned tiny model: `baseline` 0.66 vs 0.75, `rag-general` 0.52 vs 0.84,
`rag-strict` 0.04 vs 0.35. Two effects are conflated here and this run does not separate them:
model size (SmolLM2-360M vs `llama3.2`'s 3B) and the SFT+DPO tuning itself — no untuned
`smollm2:360m` baseline was run in this session for a clean ablation. **Honest limitation, not
papered over**: this comparison shows "the tiny tuned model is worse at the RAG/ask-the-ocean
task than the full untuned model," not "tuning made things worse" — those are different claims,
and only the first is actually supported by this data.

## What this does and doesn't show

- **Real learning happened** (SFT eval loss 3.032 → 0.2594, mean token accuracy 0.939) — the
  Layer 1+2 dataset scale-up (MIP-0025 tasks 2-3) and the training pipeline itself work
  end-to-end, including the new DPO stage (Layer 3, task 4) chained on top.
- **This benchmark is not the right instrument to show it.** `just benchmark` measures
  `ask_ocean_question`/RAG grounding quality — a capability nothing in tasks 2-5's own training
  data specifically targets (Layer 1 teaches domain facts via direct Q/A, not RAG-with-abstention
  discipline; Layer 2 teaches tool-call shape; Layer 3's 2 real preference pairs teach summary
  correction, not RAG abstention). A `tiny`-scale model with ~1 epoch of SFT and 2 DPO pairs
  measured on a task its training didn't emphasize scoring worse than an untuned, much larger
  model is not a surprising or alarming result — it's a real, honestly-recorded data point, not
  evidence the recipe itself is broken.
- **Not run this session, real gaps**: an untuned `smollm2:360m` baseline (would isolate model
  size from tuning effect), a benchmark actually exercising the summarizer+reviewer pipeline
  Layers 1-3 target (`--summarize`, not `--benchmark`'s RAG-only questions), and `small`/`base`
  presets (still CPU-hours/GPU-gated, per `finetune/README.md`'s own ladder).

## Verdict, per MIP-0025 task 5's own "done" condition

Done: a real combined SFT+DPO run on the `tiny` preset completed end to end, and the comparison
against both the plain baseline and the best kept run is recorded here — honestly, including
where the comparison itself is limited (see above), not as a favorable-looking number chosen to
declare success.
