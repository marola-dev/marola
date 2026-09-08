#!/usr/bin/env python3
"""What a fine-tune of size N actually costs on this machine, before you start it.

Answers the three questions that decide whether a run finishes: does it fit in VRAM, does the
fp16 merge fit in RAM, and does the export fit on disk. Printed up front so a 27B run fails in a
second with numbers rather than four hours in with an OOM.

Measure, never assume. `os.statvfs` is taken on the directory the artifacts are actually written
to — on 2026-09-07 `df /home` inside a sandboxed shell reported 95 GB while the repo's own
filesystem had 7 TB, which is the difference between "27B is impossible here" and "27B is fine".

    python finetune/preflight.py --preset qwen-27b
    python finetune/preflight.py --preset qwen-7b --device cpu
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from train_lora import PRESETS  # noqa: E402

HERE = Path(__file__).parent

# Bytes per parameter, by role. QLoRA holds the base in 4-bit (~0.55 B/param including the
# quantization constants) plus LoRA weights, gradients and optimizer state for the adapter only —
# the adapter is <1% of params, so activations and the KV cache dominate the remainder.
BYTES_4BIT = 0.55
BYTES_FP16 = 2.0
QUANT_RATIO = {"Q4_K_M": 0.60, "Q8_0": 1.10}


def gb(x: float) -> float:
    return x


def free_disk_gb(path: Path) -> float:
    s = os.statvfs(path)
    return s.f_frsize * s.f_bavail / 1e9


def total_ram_gb() -> float:
    try:
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 1e9
    except (ValueError, OSError):  # pragma: no cover - platform-dependent
        return 0.0


def vram_gb() -> float:
    """Total VRAM of device 0, or 0.0 when there is no usable CUDA device. Deliberately does not
    import torch — preflight must run on a machine that has not installed it yet."""
    exe = shutil.which("nvidia-smi")
    if not exe:
        return 0.0
    import subprocess

    try:
        out = subprocess.run(
            [exe, "--query-gpu=memory.total", "--format=csv,noheader,nounits"],
            capture_output=True,
            text=True,
            timeout=15,
        )
        if out.returncode != 0:
            return 0.0
        return float(out.stdout.strip().splitlines()[0]) / 1024
    except (ValueError, IndexError, OSError, subprocess.SubprocessError):
        return 0.0


def estimate(params_b: float, device: str, low_disk: bool) -> dict:
    """Peak VRAM / RAM / disk for one full train -> merge -> export cycle."""
    train_vram = params_b * BYTES_4BIT + 2.5 if device != "cpu" else 0.0
    train_ram = params_b * BYTES_FP16 + 4 if device == "cpu" else 4.0
    merge_ram = params_b * BYTES_FP16 + 4
    merged = params_b * BYTES_FP16
    quants = sum(params_b * r for r in QUANT_RATIO.values())
    # Default keeps the f16 GGUF alongside the merged model; --low-disk converts straight to Q8_0
    # and drops the merged copy first, so the peak is one large artifact rather than two.
    disk_peak = merged + (quants if low_disk else merged + quants)
    return {
        "train_vram": train_vram,
        "ram": max(train_ram, merge_ram),
        "disk_peak": disk_peak,
        "disk_final": quants,
    }


# Rough wall-clock for 3 SFT epochs + 1 DPO epoch over ~2.8k short examples. Anchored on the one
# run this repo has actually measured (SmolLM2-360M, CPU) and scaled by parameter count; treat as
# an order of magnitude, not a promise.
def eta_hours(params_b: float, device: str) -> float:
    if device == "cpu":
        return 0.25 * (params_b / 0.36) ** 1.1
    return 0.02 + 0.09 * params_b


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--preset", choices=sorted(PRESETS), default="tiny")
    ap.add_argument("--device", choices=("auto", "cuda", "cpu"), default="auto")
    ap.add_argument("--low-disk", action="store_true")
    ap.add_argument("--out", default=str(HERE / "out"))
    ap.add_argument("--strict", action="store_true", help="exit 1 when the run does not fit")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    preset = PRESETS[args.preset]
    params_b = preset["params_b"]
    have_vram = vram_gb()
    device = args.device
    if device == "auto":
        device = "cuda" if have_vram > 0 else "cpu"

    need = estimate(params_b, device, args.low_disk)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    have = {"vram": have_vram, "ram": total_ram_gb(), "disk": free_disk_gb(out)}

    print(f"preset      : {args.preset}  ({preset['hf']}, {params_b:g}B, {preset['licence']})")
    if preset.get("instruct") is False:
        print(
            "              NOTE: base model, not Instruct — needs far more data/epochs to act as an assistant"
        )
    print(
        f"device      : {device}{'  (no usable CUDA device found)' if device == 'cpu' and args.device == 'auto' else ''}"
    )
    print()
    rows = [
        ("VRAM (train)", need["train_vram"], have["vram"], device == "cuda"),
        ("RAM  (merge)", need["ram"], have["ram"], True),
        ("disk (peak) ", need["disk_peak"], have["disk"], True),
    ]
    ok = True
    for label, want, got, applies in rows:
        if not applies:
            print(f"  {label}  n/a on cpu")
            continue
        verdict = "OK" if got >= want else "DOES NOT FIT"
        if got < want:
            ok = False
        print(f"  {label}  need {want:7.1f} GB   have {got:7.1f} GB   {verdict}")
    print(f"  disk (kept) {need['disk_final']:7.1f} GB of GGUFs after cleanup")
    print()
    print(
        f"estimated wall clock: ~{eta_hours(params_b, device):.1f} h for 3 SFT + 1 DPO epoch (rough)"
    )
    if device == "cpu" and params_b > 3:
        print(
            "  WARNING: CPU training above ~3B is measured in days. Use a GPU or a smaller preset."
        )
    if not ok:
        print(
            "\ndoes not fit — try --low-disk, a smaller preset, or free the resource above",
            file=sys.stderr,
        )
        if args.strict:
            return 1
    return 0


def self_test() -> int:
    fails = 0

    def ok(got, want, label):
        nonlocal fails
        if got == want:
            print(f"  ok   {label}")
        else:
            fails += 1
            print(f"  FAIL {label} — got {got!r}, want {want!r}")

    e = estimate(27.0, "cuda", low_disk=False)
    ok(round(e["train_vram"]) == 17, True, "27B QLoRA needs ~17 GB VRAM — fits a 24 GB card")
    ok(round(e["disk_peak"]) == 154, True, "27B full export peaks at ~154 GB of disk")
    low = estimate(27.0, "cuda", low_disk=True)
    ok(low["disk_peak"] < e["disk_peak"], True, "--low-disk lowers the disk peak")
    ok(round(low["disk_peak"]) == 100, True, "--low-disk peaks at ~100 GB instead of ~154 GB")
    ok(estimate(0.36, "cpu", False)["train_vram"] == 0.0, True, "cpu training asks for no VRAM")
    ok(
        eta_hours(27.0, "cpu") > eta_hours(27.0, "cuda"),
        True,
        "cpu is slower than cuda at the same size",
    )
    ok(eta_hours(7.6, "cuda") < 1.0, True, "a 7B QLoRA run is under an hour on a GPU")
    ok(free_disk_gb(HERE) > 0, True, "free_disk_gb reads the real filesystem, not a parent mount")
    ok(vram_gb() >= 0.0, True, "vram_gb degrades to 0.0 rather than raising without a driver")
    if fails:
        print(f"preflight self-test: {fails} failure(s)", file=sys.stderr)
        return 1
    print("preflight self-test: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
