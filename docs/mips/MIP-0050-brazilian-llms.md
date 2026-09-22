# MIP-0050: Manacá-1B and the Brazilian-Portuguese models — a base to fine-tune, not a model to drop in

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5), for M. Hoffmann |
| **Created** | 2026-09-09 |
| **Phase** | 1 — a local model swap; no cloud, no paid resource, no Phase 2 gate |
| **Related** | MIP-0025 (the marola-sea training chain this would change the base of), MIP-0048 (scaling marola-sea — this is the "which base model" question it defers), MIP-0001 (the agency verdict shown verbatim, which §6 collides with), #273 (keeping Llama out of the derivation path — §4.1 establishes it does not apply here), #307 (the `sentencepiece` dependency, which Manacá would actually exercise) |
| **Effort** | M for §5.1 (a preset row, a GGUF pull, a benchmark arm — no new code path); L for §5.2 (fine-tuning marola-sea on a Manacá base: a new tokenizer path through `convert_hf_to_gguf.py`, and a corpus that is currently English) |
| **Gain** | `user value` — marola's users are Brazilian swimmers and the product answers them in English today; `infra/dev-loop` — a 1.7B Portuguese-native base is a better starting point for marola-sea than SmolLM2-360M, which is the honest reason the current model invents things |
| **Effort vs Gain** | `do next` for §5.1's evaluation arm (cheap, and it is the measurement MIP-0048 needs anyway); `do when the corpus is Portuguese` for §5.2 — fine-tuning a PT-native base on English text throws away the thing being adopted; **reject** Sabiá-7B on licensing (§4.3) |
| **Depends on** | Nothing blocks the evaluation. §5.2 depends on the language decision in §3, which is a product call, not a technical one. No Phase 1 gate, no cloud resource |
| **Blocked by** | none |
| **Risk** | Manacá is lowercase by construction (§4.1). marola shows the agency's `PRÓPRIA`/`IMPRÓPRIA` verdict verbatim because it is CONAMA's classification and not marola's (MIP-0001 §6/§9) — a model that cannot emit uppercase cannot quote it. That is a correctness constraint, not a styling one |
| **Cost so far** | — |

## 1. Summary

marola is a Brazilian product that answers in English, running a 360M English-centric model that
demonstrably invents facts. Brazil now has Portuguese-native open models. This evaluates them, and
concludes that the useful one, Manacá-1B, is worth adopting as a **fine-tuning base** for
marola-sea rather than as a drop-in chat model, and that two of its properties (a NonCommercial
instruct licence and a lowercase-only tokenizer) decide the shape of any adoption.

## 2. Motivation

Two facts sit badly together.

**The current model is not good enough, and the reason is size and language.** On a real
`--summarize` for Praia da Saudade (jellyfish High, whales High, 20.0 °C, 15:00), marola-sea
(SmolLM2-360M) produced: *"Swim out to the beach when you see a sign and keep your eyes open for an
uncontested point on the sand, that's where they usually are. If at night, after dark: check
conditions before you go."* Not one given fact appears; it breaks marola's own rule that jellyfish
risk is always mentioned when High; and its reviewer scored it 74/100. `marola-llama3.2` (3B) on the
same input answered correctly. `finetune/README.md` has always called the `tiny` preset a pipeline
proof rather than a quality bar, and this is what that means in practice.

**The audience is Brazilian and the output is English.** Beach names, the IMA/INEA/INEMA bulletins,
CONAMA's verdicts and the users are all Portuguese; the summary, the corpus and the prompts are
English. That is a translation layer nobody asked for, and it is the strongest argument for a
Portuguese-native model, stronger than any benchmark number.

## 3. User-visible change

None for §5.1: an evaluation arm produces a number in `docs/benchmarks/`, not a behaviour change.

For §5.2, the change is the product's language, and it should be decided deliberately rather than
arrived at by swapping a model:

```
today  Caution advised: despite relatively calm conditions and great whale sighting chance,
       the high jellyfish risk makes swimming here risky — consider postponing your swim.

pt-BR  atenção: mar calmo e boa chance de avistar baleias, mas o risco de água-viva está alto —
       melhor deixar o mergulho para outro dia.
```

Note the second is lowercase throughout. That is not a style choice; see §4.1.

## 4. Data sources and dependencies reviewed

All resolved live from the Hugging Face API on 2026-09-09; see the Appendix.

**4.1 Manacá-1B: the pick, as a base.**
`menezesbruno/manaca-1b-base`, **CC-BY-4.0**, 1,315 downloads, updated 2026-09-01. ~1.72B
parameters, decoder-only, Llama-3-*style* architecture but **trained from scratch** for Brazilian
Portuguese ("treinado do zero"), on Megatron-LM. Actively maintained; the instruct variant moved
on 2026-09-04.

Three findings that decide how it can be used:

- **The instruct variant is `cc-by-nc-4.0`.** `menezesbruno/manaca-1b-instruct` is NonCommercial;
  the *base* is CC-BY-4.0 and unrestricted. Since marola is MIT and public (MIP-0033), building on
  the base keeps the licence story simple, and building on the instruct model does not.
- **Trained from scratch, so #273 does not apply.** It is Llama-*architecture*, not Llama-*derived*,
  so the Community Licence's `Llama-` naming requirement, which `merge_export.py`'s `llama_prefix`
  enforces for the `small`/`base` presets, is not triggered. Verified from the model card, not
  inferred from the `llama` tag.
- **Lowercase by construction.** The tokenizer is a 64k SentencePiece *unigram* with `nmt_nfkc_cf`
  normalisation: input is lowercased before segmentation. The card is explicit that a tokenizer
  without that normaliser "degrada os resultados de forma invisível", invisible degradation, which
  is the failure class marola has spent this month removing. §6 covers what it means for output.

GGUF quants exist, which is what makes this runnable at all here:
`mradermacher/manaca-1b-base-GGUF` (CC-BY-4.0) ships `Q2_K`, `Q3_K_M`, `Q3_K_L`, `IQ4_XS` and more;
`sulfierry/manaca-1b-instruct-GGUF` ships F16 only, named `...-F16-UGM.gguf` for the unigram
tokenizer. That unigram path is why `convert_hf_to_gguf.py` needs `sentencepiece`: the dependency
added in #307 for a different reason is the one Manacá would genuinely exercise, where SmolLM2 falls
through to the GPT-2 vocab path instead.

**4.2 Gervásio-7B PT-BR: permissive, but unusable here today.**
`PORTULAN/gervasio-7b-portuguese-ptbr-decoder`, **MIT**, updated 2025-06-12, 73 downloads. The best
licence of the set. No official GGUF, and 7B is beyond what the `tiny`/`small` presets target;
`RichardErkhov`'s community GGUF exists (526 downloads) but is a third-party conversion of a model
whose own repo publishes none. Worth revisiting if marola ever runs a 7B locally.

**4.3 Sabiá-7B: rejected on licensing.**
`maritaca-ai/sabia-7b`, 146 likes and the best-known name here, but **no licence declared** in its
card metadata, and last updated 2024-04-04. An undeclared licence is not a permissive one. marola
does not ship models it cannot state the terms of, and #273 is the precedent for taking that
seriously.

**4.4 The small models: TeenyTinyLlama (`nicholasKluge/TeenyTinyLlama-160m`, Apache-2.0,
2025-01-15) and Tucano-160m (`cnmoro/Tucano-160m-Portuguese-Instruct-v2`, community GGUF via
mradermacher).** Both permissive, both ~160M, *smaller* than the SmolLM2-360M that is already too
small to be trusted with marola's facts. They are interesting as fine-tuning targets for a narrow
classification task, not as summarisers.

**4.5 The status quo: SmolLM2-360M (`tiny`) and Llama-3.2 (`small`/`base`).** Unchanged and
already documented in `finetune/train_lora.py`'s `PRESETS`. `marola-llama3.2` is the thing to beat,
because it demonstrably produces a correct summary today.

## 5. Design

### 5.1 A Manacá preset and an evaluation arm (do next)
Add `manaca` to `finetune/train_lora.py`'s `PRESETS` (`menezesbruno/manaca-1b-base`, ungated,
CC-BY-4.0) and a benchmark arm so the claim "a Portuguese-native 1.7B beats an English 360M on
marola's own questions" is measured rather than asserted. `just marola-sea-pull` already handles
GGUF from Hugging Face, so running it needs no new code path: `ollama pull
hf.co/mradermacher/manaca-1b-base-GGUF:Q4_K_M` and a `MAROLA_LOCAL_LLM_MODEL`.

This is also the measurement MIP-0048 §"which model" needs, so it is not a detour.

### 5.2 Fine-tuning marola-sea on a Manacá base (do when the corpus is Portuguese)
Same chain as MIP-0025 (dataset → SFT → DPO → merge → GGUF) with the base swapped. Two things
change:

- **The conversion path.** Manacá's unigram SentencePiece means `convert_hf_to_gguf.py` takes
  `_set_vocab_sentencepiece()` for real rather than falling through to GPT-2. #307 already put
  `sentencepiece` in the venv; this is the case that needs it to work, not merely to be importable.
- **The corpus.** `knowledge/` and the DSPy demos are English. Fine-tuning a Portuguese-native base
  on English text discards the reason for choosing it, so §5.2 should follow a decision to make
  marola's output Portuguese, not precede it.

### 5.3 What is not proposed
Adopting `manaca-1b-instruct` as the runtime chat model. Its CC-BY-NC-4.0 terms would attach a
NonCommercial condition to a repo that is MIT and public, for a model marola would then want to
redistribute in a Docker image. The base model has no such condition and is the better foundation.

## 6. Scoring / safety impact

`Swimability.score` is untouched: the model never computes a score, and this MIP does not change
that.

One real interaction, and it is the Risk field. MIP-0001 §6/§9 shows the agency's bathing-water
verdict verbatim, `PRÓPRIA` / `IMPRÓPRIA`, because it is CONAMA 274/2000's classification, applied
by IMA, not re-derived by marola. `site/static/style.css` exempts it from the site's lowercase house
style for exactly that reason. **A model that is lowercase by construction cannot reproduce that
string.** Options, none free: keep the verdict out of the model's output entirely and render it
deterministically alongside (which is what the CLI already does), or accept `imprópria` in generated
prose and rely on the deterministic line beside it. The first is the safer default and is what §5.1
assumes.

## 7. Verification plan

- A benchmark run with `MAROLA_LOCAL_LLM_MODEL` set to a Manacá GGUF, kept in `docs/benchmarks/`
  next to the existing arms: the same `just benchmark` shape, no new harness.
- The specific regression that started this: the Praia da Saudade prompt from §2, asserted to
  mention jellyfish when the risk is High. That is a rule marola states and the 360M model breaks;
  it is the cheapest single measure of whether a candidate is usable.
- If §5.2 proceeds: `convert_hf_to_gguf.py` on a merged Manacá adapter, confirming the unigram
  vocab path completes: the failure mode #307 fixed for a different tokenizer.
- **Done** for this MIP = the numbers exist and the licence position is written down; not a model
  adopted.

## 8. Risks, limitations, and honest caveats

- **Invisible tokenizer degradation** (§4.1). The card warns that the wrong normaliser silently
  degrades output. Any adoption must use the tokenizer from the model's own repository, and the
  benchmark is the only thing that would catch getting it wrong.
- **1.7B is still small.** Better than 360M and Portuguese-native, but the honest comparison in §2
  is against a 3B model that already works. Manacá may lose that comparison; the MIP is written so
  that outcome is a result, not a failure.
- **A one-maintainer model.** Manacá is a personal Hugging Face account, actively maintained but
  without an institution behind it. That is not a reason to reject it; it is a reason to pin a
  revision rather than track `main`.
- **Switching output language is a product decision** wearing technical clothes, and it affects the
  corpus, the DSPy prompts, the site copy and the safety footer. §5.2 should not be the vehicle for
  making it by accident.

## 9. Alternatives considered

- **Do nothing.** Defensible: `marola-llama3.2` produces correct summaries today. This MIP's value
  is mostly in §4's licence findings, which are worth having written down either way.
- **A bigger general model (Llama-3.2-3B, the `base` preset).** Already available, already works,
  no new licence question, but English-centric and subject to the Llama naming rules #273 exists
  for. The right fallback if Manacá underperforms.
- **Translate at the edges**: keep an English model, translate its output to Portuguese. Adds a
  second model call and a second place to invent facts, on the safety-relevant path. Rejected.
- **Sabiá-7B.** §4.3.

## 11. Open questions

- **Does marola answer in Portuguese?** Everything in §5.2 waits on it, and it is not a decision
  this MIP should make alone.
- **Is Manacá's lowercase output acceptable in generated prose**, given the deterministic lines
  beside it already carry the agency's casing? §6 proposes the conservative reading.
- Which Manacá quant to pin. `mradermacher/manaca-1b-base-GGUF` ships several; none was benchmarked
  here, and Q2_K on a 1.7B model is a different proposition from Q4.
- **Follow-up MIP:** marola's corpus, prompts and site copy are English for a Brazilian audience.
  That is a product-wide question larger than a model swap and deserves its own number.

## Appendix

### Checked live
Hugging Face API, 2026-09-09.

- `menezesbruno/manaca-1b-base`: **cc-by-4.0**, lang `pt`, tags include `llama`, `megatron-lm`,
  `brazilian-portuguese`; updated 2026-09-01; 1,315 downloads; `model.safetensors`.
- `menezesbruno/manaca-1b-instruct`: **cc-by-nc-4.0**, base_model `manaca-1b-base`, tags include
  `instruction-tuned`, `safety-alignment`; updated 2026-09-04; 822 downloads.
- Model card of `manaca-1b-base` (raw README, 12,802 bytes): "~1.72B", "treinado do zero"/"trained
  from scratch", Megatron-LM (LLM-jp fork), and the tokenizer section headed "Tokenizador (leia
  isto)" stating the model is "lowercase por construção" with `nmt_nfkc_cf`, and that a tokenizer
  lacking the normaliser "degrada os resultados de forma invisível".
- `mradermacher/manaca-1b-base-GGUF`: cc-by-4.0, updated 2026-08-31, 545 downloads; files include
  `IQ4_XS`, `Q2_K`, `Q3_K_L`, `Q3_K_M`.
- `sulfierry/manaca-1b-instruct-GGUF`: cc-by-nc-4.0, updated 2026-09-04, 100 downloads; one file,
  `manaca-1b-instruct-F16-UGM.gguf`.
- `maritaca-ai/sabia-7b`: **no `license` in card metadata**; updated 2024-04-04; 804 downloads;
  146 likes.
- `PORTULAN/gervasio-7b-portuguese-ptbr-decoder`: **mit**; updated 2025-06-12; 73 downloads; no
  GGUF in the repo.
- `nicholasKluge/TeenyTinyLlama-160m`: **apache-2.0**; updated 2025-01-15; 194 downloads; no GGUF.
- `cnmoro/Tucano-160m-Portuguese-Instruct-v2`: found via search (53 downloads); GGUF via
  `mradermacher` (294 downloads). Licence **not** fetched.

### Not checked
- **No model was downloaded, run or benchmarked.** Every quality claim in §2 about the *current*
  model comes from marola's own runs; every claim about Manacá is about its metadata and card, not
  its output. §7 exists because nothing here measures it.
- Sabiá-7B may state a licence in its README even though the card metadata has none; only the API
  metadata was read.
- Tucano's licence, and whether the community GGUFs for Gervásio/Sabiá are faithful conversions.
- Whether `convert_hf_to_gguf.py` completes on a Manacá-derived adapter. §5.2 names it as the risk
  precisely because it is untested here.
- Manacá's training-data provenance beyond the card's own "Dados de treino" section, which was not
  read in full.
