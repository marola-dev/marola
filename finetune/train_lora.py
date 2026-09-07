"""QLoRA fine-tune of Llama 3.2 on marola's dataset — Tier 2 in README.md.

STATUS: written against the documented peft/transformers/trl APIs, NOT RUN in this repo (no GPU
on the machine that wrote it, and the base weights are gated behind a Hugging Face login). Treat
the first run as a debugging session, not a build step.

Usage:
    pip install -r requirements.txt
    huggingface-cli login                      # Llama 3.2 weights are gated
    python build_dataset.py                    # or: just finetune-dataset
    python train_lora.py --base meta-llama/Llama-3.2-3B-Instruct --epochs 3
    # then convert out/adapter with llama.cpp's convert_lora_to_gguf.py and
    #   ollama create marola-llama3.2 -f Modelfile.adapter

Presets (see PRESETS): `--preset tiny` (SmolLM2-360M, ungated, Apache-2.0, trains on CPU in
minutes — **the default**), `--preset small` (Llama-3.2-1B, ungated mirror, CPU-feasible),
`--preset base` (Llama-3.2-3B, gated, GPU). tiny is the default rather than small so an
unqualified run never silently produces a *Llama derivative*: the Llama 3.2 Community Licence
requires such a model's name to begin with "Llama" and to ship the agreement plus a "Built with
Llama" notice (see finetune/README.md). Both Llama presets remain available and are fine to use —
they just have to be chosen, and their obligations met, on purpose. The adapter must be
attached to the matching Ollama model: smollm2:360m / llama3.2:1b / llama3.2 — the script prints it.
On CPU pass --no-4bit (bitsandbytes needs CUDA).
"""

from __future__ import annotations

import argparse
from pathlib import Path

# Iterate fast on a tiny model, then re-run the same script on the real one: the dataset, the LoRA
# config and the GGUF/Ollama steps are identical, only `--preset` changes.
PRESETS = {
    "tiny": {"hf": "HuggingFaceTB/SmolLM2-360M-Instruct", "ollama": "smollm2:360m", "gated": False},
    "small": {"hf": "unsloth/Llama-3.2-1B-Instruct", "ollama": "llama3.2:1b", "gated": False},
    "base": {"hf": "meta-llama/Llama-3.2-3B-Instruct", "ollama": "llama3.2", "gated": True},
}


def ollama_base_for(hf_id: str) -> str:
    """The `FROM` line Modelfile.adapter needs — the adapter only fits the family it was trained on."""
    for p in PRESETS.values():
        if p["hf"] == hf_id:
            return p["ollama"]
    return "<the Ollama model matching " + hf_id + ">"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--preset",
        choices=sorted(PRESETS),
        default=None,
        help="tiny = SmolLM2-360M (CPU, minutes, ungated — for iterating on the dataset); "
        "small = Llama-3.2-1B (ungated mirror, CPU-feasible, matches `llama3.2:1b`); "
        "base = Llama-3.2-3B (gated, GPU recommended, matches `llama3.2`)",
    )
    ap.add_argument("--base", default=None, help="explicit HF model id; overrides --preset")
    ap.add_argument("--data", default=str(Path(__file__).parent / "data"))
    ap.add_argument("--out", default=str(Path(__file__).parent / "out" / "adapter"))
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--max-len", type=int, default=2048)
    ap.add_argument(
        "--no-4bit", action="store_true", help="skip bitsandbytes 4-bit (CPU / no CUDA)"
    )
    args = ap.parse_args()
    if not args.base:
        args.base = PRESETS[args.preset or "tiny"]["hf"]
    print(
        f"base model: {args.base} — Ollama FROM for Modelfile.adapter: {ollama_base_for(args.base)}"
    )

    import torch
    from datasets import load_dataset
    from peft import LoraConfig
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from trl import SFTConfig, SFTTrainer

    tok = AutoTokenizer.from_pretrained(args.base)
    tok.pad_token = tok.pad_token or tok.eos_token

    model_kwargs: dict = {
        "torch_dtype": torch.bfloat16 if torch.cuda.is_available() else torch.float32
    }
    if not args.no_4bit and torch.cuda.is_available():
        from transformers import BitsAndBytesConfig

        model_kwargs["quantization_config"] = BitsAndBytesConfig(
            load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.bfloat16
        )
        model_kwargs["device_map"] = "auto"
    model = AutoModelForCausalLM.from_pretrained(args.base, **model_kwargs)

    data = load_dataset(
        "json", data_files={"train": f"{args.data}/train.jsonl", "eval": f"{args.data}/eval.jsonl"}
    )

    lora = LoraConfig(
        r=args.rank,
        lora_alpha=2 * args.rank,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules=[
            "q_proj",
            "k_proj",
            "v_proj",
            "o_proj",
            "gate_proj",
            "up_proj",
            "down_proj",
        ],
    )
    cfg = SFTConfig(
        output_dir=args.out,
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        per_device_train_batch_size=2,
        gradient_accumulation_steps=4,
        logging_steps=5,
        eval_strategy="epoch",
        save_strategy="epoch",
        max_length=args.max_len,
        bf16=torch.cuda.is_available(),
        report_to=[],
    )
    trainer = SFTTrainer(
        model=model,
        processing_class=tok,
        peft_config=lora,
        args=cfg,
        train_dataset=data["train"],
        eval_dataset=data["eval"],
    )
    trainer.train()
    trainer.save_model(args.out)
    print(
        f"adapter saved to {args.out} — convert with llama.cpp convert_lora_to_gguf.py, then see Modelfile.adapter"
    )


if __name__ == "__main__":
    main()
