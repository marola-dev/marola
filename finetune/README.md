# marola fine-tuning — local only (Ollama + llama3.2)

Two tiers, honestly labelled. Both produce a model named `marola-llama3.2` that the Scala side
uses with nothing more than `MAROLA_LOCAL_LLM_MODEL=marola-llama3.2` — no code change.

| Tier | What it is | Cost | Status |
|---|---|---|---|
| **1. Modelfile variant** (`Modelfile`) | `llama3.2` with marola's system prompt, tone and decoding parameters baked in. No weights change. | seconds, CPU | **run and verified** (see below) |
| **2. QLoRA adapter** (`train_lora.py` + `Modelfile.adapter`) | A real LoRA fine-tune of Llama 3.2 on marola's own examples, converted to GGUF and attached to `llama3.2` via Ollama's `ADAPTER`. | hours on CPU, minutes on a GPU; ~6GB download | **written, not run** — no GPU here, and the base weights need a Hugging Face login |

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

   Expect a few dozen examples. That is enough to teach *format and tone*, not facts — which is
   the point: facts stay in the RAG corpus (`knowledge/`) and in live data, the fine-tune only
   makes the model better at marola's shape of answer. `FUTURE-WORK.md` §9.1 step 4 argues the same.

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

## What is deliberately not here

- No cloud training. Azure ML / Foundry fine-tuning is the Phase 2 opt-in (`AGENTS.md` cost rule).
- No attempt to fine-tune facts in. A 3B model with 40 examples will not learn marine biology; it
  will learn to sound like it did. Facts come from `knowledge/` via RAG, with citations.
