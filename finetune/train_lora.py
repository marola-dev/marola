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
# `params_b` drives the preflight estimator: VRAM for QLoRA training, RAM for the fp16 merge, and
# disk for the export, are all functions of parameter count. Measure the real filesystem rather
# than trusting `df` on a parent path — inside a sandboxed shell `df /home` reported 95 GB while
# `os.statvfs` on the repo itself reported 7 TB, which is the difference between "27B is
# impossible here" and "27B is fine".
PRESETS = {
    "tiny": {
        "hf": "HuggingFaceTB/SmolLM2-360M-Instruct",
        "ollama": "smollm2:360m",
        "gated": False,
        "params_b": 0.36,
        "licence": "apache-2.0",
    },
    "small": {
        "hf": "unsloth/Llama-3.2-1B-Instruct",
        "ollama": "llama3.2:1b",
        "gated": False,
        "params_b": 1.24,
        "licence": "llama-3.2 (name must start with 'Llama-', ship the agreement + notice)",
    },
    "base": {
        "hf": "meta-llama/Llama-3.2-3B-Instruct",
        "ollama": "llama3.2",
        "gated": True,
        "params_b": 3.2,
        "licence": "llama-3.2 (name must start with 'Llama-', ship the agreement + notice)",
    },
    # Qwen: Apache-2.0 throughout, so no naming obligation of any kind — the reason these are the
    # recommended step up from `tiny` rather than the Llama presets above. Sizes verified against
    # huggingface.co/api/models?author=Qwen on 2026-09-07. Qwen2.5-3B-Instruct is deliberately
    # absent: its card says `other`, not apache-2.0, unlike every other size in the family.
    "qwen-4b": {
        "hf": "Qwen/Qwen3-4B-Instruct-2507",
        "ollama": "qwen3:4b",
        "gated": False,
        "params_b": 4.0,
        "licence": "apache-2.0",
    },
    "qwen-7b": {
        "hf": "Qwen/Qwen2.5-7B-Instruct",
        "ollama": "qwen2.5:7b",
        "gated": False,
        "params_b": 7.6,
        "licence": "apache-2.0",
    },
    "qwen-14b": {
        "hf": "Qwen/Qwen2.5-14B-Instruct",
        "ollama": "qwen2.5:14b",
        "gated": False,
        "params_b": 14.8,
        "licence": "apache-2.0",
    },
    # 27B fits on the reference machine: ~154 GB of export against ~7 TB free, ~54 GB of RAM for
    # the fp16 merge against 188 GB, and ~16 GB of VRAM for QLoRA 4-bit against 24 GB. The real
    # caveats are elsewhere — this checkpoint is a BASE model, not Instruct, so expect to need far
    # more data and epochs before it behaves like an assistant; and CPU-only training at this size
    # is measured in weeks, so it is a GPU-only preset in practice.
    "qwen-27b": {
        "hf": "Qwen/Qwen3.8-27B",
        "ollama": "qwen3:27b",
        "gated": False,
        "params_b": 27.0,
        "licence": "apache-2.0",
        "instruct": False,
    },
}


def ollama_base_for(hf_id: str) -> str:
    """The `FROM` line Modelfile.adapter needs — the adapter only fits the family it was trained on."""
    for p in PRESETS.values():
        if p["hf"] == hf_id:
            return p["ollama"]
    return "<the Ollama model matching " + hf_id + ">"


def _resume_target(args) -> str | None:
    """The newest `checkpoint-N` under --out, or None to train from scratch.

    This is the "resume or start over?" decision, and it is deliberately made from what is on disk
    rather than from a flag alone: `--resume` on a clean machine must start from scratch, not fail,
    or the first run of any CI job breaks.

    Refuses to resume a checkpoint trained from a different base model. Optimizer and scheduler
    state are shaped by the model; loading a Qwen-7B checkpoint into a SmolLM2 run either explodes
    with a shape error or, worse, silently produces nonsense. The marker file is written next to
    the checkpoints on every run.
    """
    out = Path(args.out)
    marker = out / ".marola-base"
    ckpts = sorted(
        (d for d in out.glob("checkpoint-*") if d.is_dir()),
        key=lambda d: int(d.name.rsplit("-", 1)[1]),
    )
    if not ckpts:
        return None
    if marker.exists():
        previous = marker.read_text().strip()
        if previous != args.base:
            raise SystemExit(
                f"train_lora: {out} holds checkpoints trained from {previous!r}, but this run uses "
                f"{args.base!r}. Refusing to resume across base models — delete {out} or pass a "
                "different --out."
            )
    return str(ckpts[-1])


def _mark_base(args) -> None:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / ".marola-base").write_text(args.base + "\n")


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
    ap.add_argument(
        "--resume",
        action="store_true",
        help="continue from the newest checkpoint in --out if one exists. Trainer checkpoints "
        "carry optimizer, scheduler, RNG and step state, so this resumes mid-epoch rather than "
        "restarting the epoch — what makes a multi-day or interrupted run survivable",
    )
    ap.add_argument(
        "--save-steps",
        type=int,
        default=200,
        help="checkpoint every N steps; with --resume this bounds what a crash costs",
    )
    ap.add_argument("--data", default=str(Path(__file__).parent / "data"))
    ap.add_argument("--out", default=str(Path(__file__).parent / "out" / "adapter"))
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--max-len", type=int, default=2048)
    ap.add_argument(
        "--no-4bit", action="store_true", help="skip bitsandbytes 4-bit (CPU / no CUDA)"
    )
    ap.add_argument(
        "--device",
        choices=("auto", "cuda", "cpu", "hybrid"),
        default="auto",
        help="auto: cuda when available. hybrid: fill the GPU and spill the rest to CPU RAM "
        "(accelerate device_map='auto' + max_memory), for a model too large for VRAM alone",
    )
    ap.add_argument(
        "--gpu-mem-gb",
        type=float,
        default=None,
        help="VRAM ceiling for --device hybrid; default is 90%% of the card, leaving headroom for "
        "activations",
    )
    ap.add_argument(
        "--batch", type=int, default=None, help="per-device batch size (default: picked by size)"
    )
    ap.add_argument(
        "--grad-accum",
        type=int,
        default=None,
        help="gradient accumulation steps (default: by size)",
    )
    ap.add_argument(
        "--no-packing",
        action="store_true",
        help="disable example packing; packing is the single biggest throughput win on a corpus of "
        "short examples like marola's (~2.8k rows, most far below max-len)",
    )
    ap.add_argument(
        "--no-grad-checkpointing",
        action="store_true",
        help="disable gradient checkpointing; it trades ~20%% speed for a large drop in activation "
        "memory, which is what makes the bigger presets fit at all",
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

    cuda = torch.cuda.is_available()
    device = args.device
    if device == "auto":
        device = "cuda" if cuda else "cpu"
    if device in ("cuda", "hybrid") and not cuda:
        raise SystemExit(
            f"train_lora: --device {device} needs a working CUDA device; torch.cuda.is_available() "
            "is False. Check `nvidia-smi` — a present card with a broken driver looks exactly like "
            "no card at all here. Use --device cpu to run anyway."
        )

    # TF32 costs nothing on Ampere and later and speeds up every matmul that is not already bf16.
    if cuda:
        torch.backends.cuda.matmul.allow_tf32 = True
        torch.backends.cudnn.allow_tf32 = True

    tok = AutoTokenizer.from_pretrained(args.base)
    tok.pad_token = tok.pad_token or tok.eos_token

    model_kwargs: dict = {"torch_dtype": torch.bfloat16 if cuda else torch.float32}
    use_4bit = not args.no_4bit and device in ("cuda", "hybrid")
    if use_4bit:
        from transformers import BitsAndBytesConfig

        model_kwargs["quantization_config"] = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16,
            # Quantizing the quantization constants too — ~0.4 bits/param less memory for no
            # measurable quality cost, which is free headroom on a 24 GB card at 27B.
            bnb_4bit_use_double_quant=True,
        )
    if device == "cuda":
        model_kwargs["device_map"] = "auto"
    elif device == "hybrid":
        # Fill the GPU to a ceiling, spill the remainder to CPU RAM. Slower per step than pure GPU
        # — every offloaded layer crosses PCIe twice — but it is the difference between running and
        # an OOM when the model does not fit in VRAM alone. This machine has 188 GB of RAM, so the
        # CPU side is effectively unbounded.
        ceiling = args.gpu_mem_gb or (torch.cuda.get_device_properties(0).total_memory / 1e9 * 0.90)
        model_kwargs["device_map"] = "auto"
        model_kwargs["max_memory"] = {0: f"{ceiling:.0f}GiB", "cpu": "160GiB"}
        print(f"hybrid: up to {ceiling:.0f} GiB on the GPU, the rest offloaded to CPU RAM")

    # SDPA is the fastest attention available without a flash-attn build, and unlike flash-attn it
    # needs no extra wheel — worth asking for explicitly rather than taking the eager default.
    model_kwargs["attn_implementation"] = "sdpa"
    model = AutoModelForCausalLM.from_pretrained(args.base, **model_kwargs)
    if use_4bit:
        from peft import prepare_model_for_kbit_training

        model = prepare_model_for_kbit_training(
            model, use_gradient_checkpointing=not args.no_grad_checkpointing
        )

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
    # Effective batch stays ~8 regardless of size; only how it is split changes. Bigger models get
    # a smaller per-device batch and more accumulation, because activation memory scales with both
    # batch and model width.
    params_b = PRESETS.get(args.preset or "", {}).get("params_b", 1.0)
    if args.batch is not None:
        batch = args.batch
    elif device == "cpu":
        batch = 1
    else:
        batch = 4 if params_b <= 2 else 2 if params_b <= 9 else 1
    accum = args.grad_accum if args.grad_accum is not None else max(1, 8 // batch)

    cfg = SFTConfig(
        output_dir=args.out,
        num_train_epochs=args.epochs,
        learning_rate=args.lr,
        per_device_train_batch_size=batch,
        gradient_accumulation_steps=accum,
        logging_steps=5,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=1,  # a 27B checkpoint per epoch is ~100 GB of churn nobody reads
        max_length=args.max_len,
        bf16=cuda,
        # marola's corpus is ~2.8k mostly-short rows against a 2048-token window, so without
        # packing most of every batch is padding. Packing concatenates examples up to max_length
        # and is the single biggest throughput win available here.
        packing=not args.no_packing,
        gradient_checkpointing=not args.no_grad_checkpointing,
        gradient_checkpointing_kwargs={"use_reentrant": False},
        # Fused optimizer when CUDA is present: fewer kernel launches per step, no accuracy cost.
        optim="adamw_torch_fused" if cuda else "adamw_torch",
        # Only useful when packing is off: it batches similar-length examples so a batch is
        # mostly content rather than padding. With packing on it is redundant.
        group_by_length=args.no_packing,
        dataloader_num_workers=4,
        report_to=[],
    )
    print(
        f"device={device} batch={batch} accum={accum} (effective {batch * accum}) "
        f"packing={not args.no_packing} grad_checkpointing={not args.no_grad_checkpointing} "
        f"4bit={use_4bit}"
    )
    _mark_base(args)
    resume = _resume_target(args) if args.resume else None
    print(f"resuming from {resume}" if resume else "training from scratch (no checkpoint found)")
    trainer = SFTTrainer(
        model=model,
        processing_class=tok,
        peft_config=lora,
        args=cfg,
        train_dataset=data["train"],
        eval_dataset=data["eval"],
    )
    trainer.train(resume_from_checkpoint=resume)
    trainer.save_model(args.out)
    print(
        f"adapter saved to {args.out} — convert with llama.cpp convert_lora_to_gguf.py, then see Modelfile.adapter"
    )


if __name__ == "__main__":
    main()
