"""DPO fine-tune on top of marola's SFT adapter — Layer 3 in README.md (MIP-0025 §4.3).

Continues training from an SFT LoRA adapter (train_lora.py's output) using the preference pairs
build_dpo_dataset.py wrote (finetune/data/dpo_pairs.jsonl: {"prompt", "chosen", "rejected"}, one
pair per real Reviewer.scala reject/revise decision). Same preset ladder as train_lora.py — the
adapter must be trained on the same base model it continues from.

STATUS: written against the documented peft/trl DPOTrainer API. See finetune/README.md's Layer 3
section for whether a real run's evidence has landed yet.

Usage:
    python build_dataset.py                                  # SFT dataset (Layers 1+2)
    python train_lora.py --preset tiny --no-4bit --epochs 3   # SFT adapter -> out/adapter
    python build_dpo_dataset.py                               # DPO pairs (Layer 3)
    python train_dpo.py --preset tiny --no-4bit --epochs 1    # DPO adapter -> out/dpo-adapter
"""

from __future__ import annotations

import argparse
from pathlib import Path

from train_lora import PRESETS, ollama_base_for


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--preset",
        choices=sorted(PRESETS),
        default=None,
        help="must match the SFT adapter's preset — tiny/small/base, see train_lora.py --help",
    )
    ap.add_argument("--base", default=None, help="explicit HF model id; overrides --preset")
    ap.add_argument(
        "--sft-adapter",
        default=str(Path(__file__).parent / "out" / "adapter"),
        help="the SFT LoRA adapter to continue training from (train_lora.py's --out)",
    )
    ap.add_argument("--data", default=str(Path(__file__).parent / "data" / "dpo_pairs.jsonl"))
    ap.add_argument("--out", default=str(Path(__file__).parent / "out" / "dpo-adapter"))
    ap.add_argument("--epochs", type=int, default=1)
    ap.add_argument("--lr", type=float, default=5e-6)
    ap.add_argument("--beta", type=float, default=0.1, help="DPO KL-penalty strength")
    ap.add_argument("--max-length", type=int, default=1024)
    ap.add_argument(
        "--no-4bit", action="store_true", help="skip bitsandbytes 4-bit (CPU / no CUDA)"
    )
    args = ap.parse_args()
    if not args.base:
        args.base = PRESETS[args.preset or "tiny"]["hf"]
    if not Path(args.sft_adapter).exists():
        raise SystemExit(
            f"no SFT adapter at {args.sft_adapter} — run train_lora.py first: MIP-0025 task 5 is "
            "SFT (Layers 1+2) then DPO (Layer 3) continuing from it, not DPO trained from scratch"
        )
    print(
        f"base model: {args.base} — Ollama FROM for Modelfile.adapter: {ollama_base_for(args.base)}"
        f" — continuing DPO from SFT adapter {args.sft_adapter}"
    )

    import torch
    from datasets import load_dataset
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from trl import DPOConfig, DPOTrainer

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
    base_model = AutoModelForCausalLM.from_pretrained(args.base, **model_kwargs)
    model = PeftModel.from_pretrained(base_model, args.sft_adapter, is_trainable=True)

    data = load_dataset("json", data_files={"train": args.data})["train"]

    cfg = DPOConfig(
        output_dir=args.out,
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        beta=args.beta,
        per_device_train_batch_size=1,
        gradient_accumulation_steps=2,
        logging_steps=1,
        max_length=args.max_length,
        save_strategy="epoch",
        bf16=torch.cuda.is_available(),
        report_to=[],
    )
    trainer = DPOTrainer(model=model, args=cfg, train_dataset=data, processing_class=tok)
    trainer.train()
    trainer.save_model(args.out)
    print(
        f"DPO adapter saved to {args.out} — convert with llama.cpp's convert_lora_to_gguf.py, "
        "then see Modelfile.adapter"
    )


if __name__ == "__main__":
    main()
