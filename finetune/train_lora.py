"""QLoRA fine-tune of one preset base model on marola's dataset — Tier 2 in README.md.

STATUS: the `tiny` preset has been run end to end (see finetune/README.md); every larger preset is
written against the documented peft/transformers/trl APIs and NOT RUN here. Treat a first run on a
new preset as a debugging session, not a build step.

Usage:
    pip install -r requirements.txt
    huggingface-cli login                      # only the gated `base` preset needs this
    python build_dataset.py                    # or: just finetune-dataset
    python train_lora.py --preset qwen-7b --epochs 3
    # then convert out/<preset>/adapter with llama.cpp's convert_lora_to_gguf.py and
    #   just finetune-adapter-model preset=qwen-7b

Presets (see PRESETS): `tiny` (SmolLM2-360M, ungated, Apache-2.0, trains on CPU in minutes — **the
default**), `small`/`base` (Llama 3.2 1B/3B; the 3B is gated), `qwen-4b`/`qwen-7b`/`qwen-14b`/
`qwen-27b` (Apache-2.0 throughout). tiny is the default rather than small so an unqualified run
never silently produces a *Llama derivative*: the Llama 3.2 Community Licence requires such a
model's name to begin with "Llama" and to ship the agreement plus a "Built with Llama" notice (see
finetune/README.md). Both Llama presets remain available and are fine to use — they just have to
be chosen, and their obligations met, on purpose.

Switching base is safe by construction: each preset trains into its own `out/<preset>/` directory,
and a run refuses to start if the directory it is about to write already holds another base
model's work. On CPU pass --no-4bit (bitsandbytes needs CUDA).
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

# Iterate fast on a tiny model, then re-run the same script on the real one: the dataset, the LoRA
# config and the GGUF/Ollama steps are identical, only `--preset` changes.
#
# Every field has exactly one consumer, so a new preset cannot half-exist:
#   hf/ollama      the HF id to train from, and the Ollama model an adapter trained on it attaches
#                  to — `ollama create` fails loudly on a tag that does not exist, but attaching to
#                  the WRONG one of a family loads fine and then answers nonsense, so both the tag
#                  and its variant (instruct vs thinking) are part of the identity.
#   params_b       preflight's VRAM/RAM/disk estimator, and the batch/accum split below.
#   gated          whether a Hugging Face login is needed before the weights download.
#   licence        an HF licence *id*, passed through to publish_hf.py --base-license.
#   name_prefix    Meta's Community Licence requires a Llama derivative's name to start with
#                  "Llama-" (MIP-0025 §5.1(3)); merge_export.py reads this instead of guessing
#                  from the id.
#   thinking       the base's chat template emits reasoning blocks, which changes what an SFT run
#                  teaches and what the Ollama model answers with.
#   target_modules which projections LoRA attaches to. peft matches by suffix and silently skips
#                  what it does not find, so a model whose attention is not q/k/v/o_proj needs its
#                  own list rather than the standard one.
ATTENTION_AND_MLP = (
    "q_proj",
    "k_proj",
    "v_proj",
    "o_proj",
    "gate_proj",
    "up_proj",
    "down_proj",
)

PRESETS = {
    "tiny": {
        "hf": "HuggingFaceTB/SmolLM2-360M-Instruct",
        "ollama": "smollm2:360m",
        "gated": False,
        "params_b": 0.36,
        "licence": "apache-2.0",
        "name_prefix": "",
        "thinking": False,
        "target_modules": ATTENTION_AND_MLP,
    },
    "small": {
        "hf": "unsloth/Llama-3.2-1B-Instruct",
        "ollama": "llama3.2:1b",
        "gated": False,
        "params_b": 1.24,
        "licence": "llama3.2",
        "name_prefix": "Llama-",
        "thinking": False,
        "target_modules": ATTENTION_AND_MLP,
    },
    "base": {
        "hf": "meta-llama/Llama-3.2-3B-Instruct",
        "ollama": "llama3.2",
        "gated": True,
        "params_b": 3.2,
        "licence": "llama3.2",
        "name_prefix": "Llama-",
        "thinking": False,
        "target_modules": ATTENTION_AND_MLP,
    },
    # Qwen: Apache-2.0 throughout, so no naming obligation of any kind — the reason these are the
    # recommended step up from `tiny` rather than the Llama presets above. Sizes and licences
    # verified against huggingface.co/api/models/<id>, Ollama tags against ollama.com/library,
    # both on 2026-09-12. Qwen2.5-3B-Instruct is deliberately absent: its card says `other`, not
    # apache-2.0, unlike every other size in the family.
    "qwen-4b": {
        "hf": "Qwen/Qwen3-4B-Instruct-2507",
        # NOT `qwen3:4b` — that tag is 4b-thinking-2507 (digest 359d7dd4bcda), a different
        # checkpoint from the Instruct-2507 weights trained here (0edcdef34593).
        "ollama": "qwen3:4b-instruct",
        "gated": False,
        "params_b": 4.02,
        "licence": "apache-2.0",
        "name_prefix": "",
        "thinking": False,
        "target_modules": ATTENTION_AND_MLP,
    },
    "qwen-7b": {
        "hf": "Qwen/Qwen2.5-7B-Instruct",
        "ollama": "qwen2.5:7b",
        "gated": False,
        "params_b": 7.62,
        "licence": "apache-2.0",
        "name_prefix": "",
        "thinking": False,
        "target_modules": ATTENTION_AND_MLP,
    },
    "qwen-14b": {
        "hf": "Qwen/Qwen2.5-14B-Instruct",
        "ollama": "qwen2.5:14b",
        "gated": False,
        "params_b": 14.77,
        "licence": "apache-2.0",
        "name_prefix": "",
        "thinking": False,
        "target_modules": ATTENTION_AND_MLP,
    },
    # The odd one out, and the reason `target_modules`/`thinking` are preset fields at all.
    # Qwen3.8-27B is post-trained (not a base checkpoint), multimodal
    # (`Qwen3_5ForConditionalGeneration`, pipeline image-text-to-text — the merge drops the vision
    # tower) and hybrid-attention: only 16 of its 64 layers use q/k/v/o_proj, the other 48 are
    # linear-attention layers with different names, so the standard target list would quietly
    # train a quarter of the attention stack. Its template also wraps every assistant turn in an
    # empty <think></think> block. NOT RUN here — treat a first run as an experiment, not a build.
    "qwen-27b": {
        "hf": "Qwen/Qwen3.8-27B",
        "ollama": "qwen3.8:27b",
        "gated": False,
        "params_b": 27.78,
        "licence": "apache-2.0",
        "name_prefix": "",
        "thinking": True,
        "target_modules": "all-linear",
    },
}

PRESET_FIELDS = (
    "hf",
    "ollama",
    "gated",
    "params_b",
    "licence",
    "name_prefix",
    "thinking",
    "target_modules",
)

BASE_MARKER = ".marola-base"


def preset_for(hf_id: str) -> str | None:
    """The preset name that owns this HF id, or None for a `--base` outside the table."""
    for name, p in PRESETS.items():
        if p["hf"] == hf_id:
            return name
    return None


def ollama_base_for(hf_id: str) -> str:
    """The `FROM` line Modelfile.adapter needs — the adapter only fits the family it was trained on."""
    name = preset_for(hf_id)
    return PRESETS[name]["ollama"] if name else "<the Ollama model matching " + hf_id + ">"


def run_slug(hf_id: str) -> str:
    """The per-run directory name: the preset when there is one, else a slug of the model id.

    Artifacts are namespaced by this rather than shared, because everything downstream of training
    is model-shaped: an adapter, a merged checkpoint and a GGUF built from SmolLM2 mean nothing to
    a Qwen run, and `save_total_limit` deletes by step number without looking at whose checkpoint
    it is — the two bases' step counts differ, so a shared directory loses whichever run wrote
    fewer steps.
    """
    name = preset_for(hf_id)
    if name:
        return name
    return re.sub(r"[^a-z0-9]+", "-", hf_id.rsplit("/", 1)[-1].lower()).strip("-") or "custom"


def run_dir(hf_id: str) -> Path:
    """`finetune/out/<preset>` — every artifact of one base's run lives under here."""
    return Path(__file__).parent / "out" / run_slug(hf_id)


def default_out(hf_id: str, kind: str = "adapter") -> Path:
    """`finetune/out/<preset>/adapter` — see run_slug for why this is not `finetune/out/adapter`."""
    return run_dir(hf_id) / kind


def has_artifacts(out: Path) -> bool:
    """Does this directory already hold a training run's output?"""
    return bool(list(out.glob("checkpoint-*"))) or (out / "adapter_config.json").exists()


def base_conflict(marker_text: str | None, base: str) -> bool:
    """True when a directory's recorded base model is not the one about to be trained."""
    return bool(marker_text) and marker_text.strip() != base


def read_marker(out: Path) -> str | None:
    marker = out / BASE_MARKER
    return marker.read_text() if marker.exists() else None


def check_base(out: Path, base: str) -> None:
    """Refuse to write a second base model's run into another's directory.

    Optimizer, scheduler and adapter shapes are all model-specific: resuming a SmolLM2 checkpoint
    into a Qwen run either explodes with a shape error or, worse, trains something meaningless.
    This runs whether or not `--resume` was passed, because an unguarded run does not resume the
    other base's work — it overwrites it, and `save_total_limit` can delete it mid-run.
    """
    if not out.exists():
        return
    previous = read_marker(out)
    if base_conflict(previous, base) and has_artifacts(out):
        raise SystemExit(
            f"train_lora: {out} holds a run trained from {previous.strip()!r}, but this run uses "
            f"{base!r}. Refusing to mix base models in one directory — delete {out}, or pass "
            "--out for this base (the default is already per-preset)."
        )


def adapter_base(adapter: Path) -> str | None:
    """Which base model an existing LoRA adapter was trained on, per its own adapter_config.json."""
    cfg = Path(adapter) / "adapter_config.json"
    if not cfg.exists():
        return None
    try:
        return json.loads(cfg.read_text()).get("base_model_name_or_path")
    except json.JSONDecodeError:
        return None


def check_adapter_base(adapter: Path, base: str) -> None:
    """Refuse a mismatched adapter *before* the base model loads.

    peft raises on its own eventually, but only after transformers has pulled a full checkpoint
    into RAM — up to tens of GB for the larger presets. This is one JSON read.
    """
    found = adapter_base(adapter)
    if found and found != base:
        raise SystemExit(
            f"the adapter at {adapter} was trained on {found!r}, not {base!r}. A LoRA adapter only "
            "fits the family it was trained on — point --sft-adapter/--adapter at this base's run "
            "(finetune/out/<preset>/), or retrain."
        )


def latest_checkpoint(out: Path) -> str | None:
    """The newest `checkpoint-N` under --out, or None to train from scratch.

    Decided from what is on disk rather than from the flag alone: `--resume` on a clean machine
    must start from scratch, not fail, or the first run of any CI job breaks.
    """
    ckpts = sorted(
        (d for d in out.glob("checkpoint-*") if d.is_dir()),
        key=lambda d: int(d.name.rsplit("-", 1)[1]),
    )
    return str(ckpts[-1]) if ckpts else None


def mark_base(out: Path, base: str) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / BASE_MARKER).write_text(base + "\n")


def self_test() -> int:
    """Everything above is pure or filesystem-only — no torch, no network, seconds to run."""
    import tempfile

    fails = 0

    def ok(got, want, label):
        nonlocal fails
        if got == want:
            print(f"  ok   {label}")
        else:
            fails += 1
            print(f"  FAIL {label} — got {got!r}, want {want!r}")

    for name, p in PRESETS.items():
        ok(sorted(p), sorted(PRESET_FIELDS), f"{name} declares every preset field")
        ok(
            p["licence"] in ("apache-2.0", "llama3.2"),
            True,
            f"{name}'s licence is an HF id, not prose (publish_hf --base-license takes it)",
        )
        ok(
            p["name_prefix"] == ("Llama-" if "llama" in p["hf"].lower() else ""),
            True,
            f"{name}'s name_prefix matches Meta's obligation for its base",
        )

    ok(preset_for("Qwen/Qwen2.5-7B-Instruct"), "qwen-7b", "a base id resolves back to its preset")
    ok(preset_for("mistralai/Whatever"), None, "an unknown base id resolves to no preset")
    ok(ollama_base_for(PRESETS["qwen-27b"]["hf"]), "qwen3.8:27b", "27B's Ollama tag is qwen3.8")
    ok(run_slug("Qwen/Qwen2.5-7B-Instruct"), "qwen-7b", "the run directory is the preset name")
    ok(run_slug("acme/Some_Model-v2"), "some-model-v2", "an off-table base still gets a slug")
    ok(
        default_out("Qwen/Qwen2.5-7B-Instruct").parts[-2:],
        ("qwen-7b", "adapter"),
        "the default --out is namespaced by preset",
    )
    ok(
        default_out(PRESETS["tiny"]["hf"]) != default_out(PRESETS["qwen-7b"]["hf"]),
        True,
        "tiny and qwen-7b cannot land in the same directory",
    )

    ok(base_conflict("a\n", "b"), True, "a different recorded base is a conflict")
    ok(base_conflict("a\n", "a"), False, "the same base is not a conflict")

    with tempfile.TemporaryDirectory() as tmp:
        ad = Path(tmp) / "adapter"
        ad.mkdir()
        smol, qwen = PRESETS["tiny"]["hf"], PRESETS["qwen-7b"]["hf"]
        check_adapter_base(ad, qwen)  # no adapter_config.json yet — nothing to contradict
        (ad / "adapter_config.json").write_text(json.dumps({"base_model_name_or_path": smol}))
        ok(adapter_base(ad), smol, "an adapter reports the base it was trained on")
        check_adapter_base(ad, smol)
        try:
            check_adapter_base(ad, qwen)
            ok("no refusal", "SystemExit", "a foreign adapter must be refused before loading")
        except SystemExit as exc:
            ok(smol in str(exc), True, "the refusal names the adapter's real base")
    ok(base_conflict(None, "a"), False, "an unmarked directory is not a conflict")

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "adapter"
        smol, qwen = PRESETS["tiny"]["hf"], PRESETS["qwen-7b"]["hf"]
        check_base(out, smol)  # nothing on disk yet
        mark_base(out, smol)
        (out / "checkpoint-40").mkdir()
        ok(latest_checkpoint(out), str(out / "checkpoint-40"), "the newest checkpoint is found")
        try:
            check_base(out, qwen)
            ok("no refusal", "SystemExit", "switching base in a used directory must be refused")
        except SystemExit as exc:
            ok(smol in str(exc) and qwen in str(exc), True, "the refusal names both base models")
        check_base(out, smol)  # the same base is still fine
        (out / "checkpoint-40").rmdir()
        ok(latest_checkpoint(out), None, "an empty directory resumes from scratch")

    print("train_lora self-test:", "ok" if not fails else f"{fails} FAILED")
    return 1 if fails else 0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--preset",
        choices=sorted(PRESETS),
        default=None,
        help="tiny = SmolLM2-360M (CPU, minutes, ungated — for iterating on the dataset); "
        "small/base = Llama 3.2 1B/3B (the 3B is gated, and both carry Meta's naming obligation); "
        "qwen-4b/7b/14b/27b = Apache-2.0 all the way down, the recommended step up from tiny. "
        "Each preset trains into its own out/<preset>/ directory",
    )
    ap.add_argument(
        "--base",
        default=None,
        help="explicit HF model id; overrides --preset. An id in the preset table still gets that "
        "preset's directory, Ollama tag, licence and LoRA targets",
    )
    ap.add_argument(
        "--self-test",
        action="store_true",
        help="check the preset table and the base-switching guards; no torch, no network",
    )
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
    ap.add_argument(
        "--out",
        default=None,
        help="where the adapter and checkpoints go (default: finetune/out/<preset>/adapter)",
    )
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
    if args.self_test:
        raise SystemExit(self_test())

    if not args.base:
        args.base = PRESETS[args.preset or "tiny"]["hf"]
    # An explicit --base that happens to be a preset's model still gets that preset's settings:
    # the table is keyed by what the model IS, not by how it was named on the command line.
    preset_name = args.preset or preset_for(args.base)
    preset = PRESETS.get(preset_name or "", {})
    if not args.out:
        args.out = str(default_out(args.base))
    out = Path(args.out)
    check_base(out, args.base)
    print(
        f"base model: {args.base} ({preset_name or 'off-table'}) — Ollama FROM for "
        f"Modelfile.adapter: {ollama_base_for(args.base)}; adapter -> {out}"
    )
    if preset.get("thinking"):
        print(
            "NOTE: this base's chat template wraps every assistant turn in a reasoning block, so "
            "the fine-tune teaches that shape too — check one sample before publishing."
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

    # peft matches target modules by suffix and skips what it cannot find, so a hybrid attention
    # stack needs "all-linear" rather than the q/k/v/o list every other preset uses.
    targets = preset.get("target_modules", ATTENTION_AND_MLP)
    lora = LoraConfig(
        r=args.rank,
        lora_alpha=2 * args.rank,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
        target_modules="all-linear" if targets == "all-linear" else list(targets),
    )
    # Effective batch stays ~8 regardless of size; only how it is split changes. Bigger models get
    # a smaller per-device batch and more accumulation, because activation memory scales with both
    # batch and model width.
    params_b = preset.get("params_b", 1.0)
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
    resume = latest_checkpoint(out) if args.resume else None
    print(f"resuming from {resume}" if resume else "training from scratch (no checkpoint found)")
    mark_base(out, args.base)
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
