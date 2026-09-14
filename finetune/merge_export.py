#!/usr/bin/env python3
"""Merge a marola LoRA adapter into its base model and export runnable GGUFs (MIP-0025 §5.1).

This is the step between training and publishing, and the one that was missing: `train_lora.py`
and `train_dpo.py` produce a LoRA *adapter*, and `convert_lora_to_gguf.py` turns that adapter into
an adapter-GGUF. An adapter is not a model. It runs locally only because Ollama already holds the
base weights and `Modelfile.adapter` names them (`FROM llama3.2:1b` + `ADAPTER ...`).

That distinction is what breaks publishing. `ollama run hf.co/<user>/<repo>` pulls a repo and
expects standalone model GGUFs; handed a 17 MB adapter it has no base to attach it to. MIP-0025
§5.1's chain therefore starts with a `peft` merge — fold the adapter's weights into the base, then
convert, then quantize — which is what this script does:

    adapter + base  ->  merged HF model  ->  f16 GGUF  ->  Q4_K_M + Q8_0 GGUF

Then `publish_hf.py` uploads the quantized files and `ollama run hf.co/...` actually works.

    python finetune/merge_export.py --preset tiny --dry-run     # print the plan, touch nothing
    python finetune/merge_export.py --preset tiny --llama-cpp ~/src/llama.cpp

`--dry-run` and `--self-test` deliberately need neither torch nor peft nor llama.cpp: the planning
half is pure and testable on any machine, and only the merge itself needs the heavy dependencies
(`pip install -r finetune/requirements.txt`). Same lazy-import discipline as publish_hf.py.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from train_lora import (  # noqa: E402  — same directory, shares the preset table
    PRESETS,
    check_adapter_base,
    preset_for,
    run_dir,
    run_slug,
)

HERE = Path(__file__).parent
QUANTIZATIONS = ("Q4_K_M", "Q8_0")


def default_adapter(out_dir: Path) -> Path:
    """The best adapter present: DPO continues training *from* the SFT adapter, so when both
    exist `out/<preset>/dpo-adapter` already contains the SFT weights and is the one to publish."""
    dpo, sft = out_dir / "dpo-adapter", out_dir / "adapter"
    return dpo if dpo.exists() else sft


def model_name(preset: str) -> str:
    """`marola-sea-tiny` — the published model's stem, per MIP-0025 §5.1(3)."""
    return f"marola-sea-{preset}"


def prefixed_name(base_hf_id: str, name: str) -> str:
    """Meta's Community Licence requires a Llama-derived model's name to START with `Llama-`
    (MIP-0025 §5.1(3)). The obligation is a property of the base, so it is declared once in
    train_lora.PRESETS (`name_prefix`) rather than re-guessed from the id here."""
    preset = PRESETS.get(preset_for(base_hf_id) or "", {})
    return preset.get("name_prefix", "") + name


def gguf_paths(out_dir: Path, name: str) -> dict[str, Path]:
    paths = {"f16": out_dir / f"{name}-f16.gguf"}
    for q in QUANTIZATIONS:
        paths[q] = out_dir / f"{name}-{q}.gguf"
    return paths


def convert_argv(llama_cpp: Path, merged: Path, out_f16: Path) -> list[str]:
    return [
        sys.executable,
        str(llama_cpp / "convert_hf_to_gguf.py"),
        str(merged),
        "--outfile",
        str(out_f16),
        "--outtype",
        "f16",
    ]


def quantize_argv(llama_cpp: Path, out_f16: Path, out_q: Path, quant: str) -> list[str]:
    """llama.cpp's quantizer moved from `./quantize` to `llama-quantize` and into build/bin; take
    whichever exists so a user's checkout layout doesn't matter."""
    for candidate in (
        llama_cpp / "llama-quantize",
        llama_cpp / "build" / "bin" / "llama-quantize",
        llama_cpp / "quantize",
    ):
        if candidate.exists():
            return [str(candidate), str(out_f16), str(out_q), quant]
    return ["llama-quantize", str(out_f16), str(out_q), quant]


def plan(args) -> dict:
    base = args.base or PRESETS[args.preset]["hf"]
    # One directory per base — the same `out/<preset>/` layout train_lora.py writes into, so a
    # Qwen merge can never pick up a SmolLM2 adapter or overwrite its GGUFs.
    out_dir = Path(args.out) if args.out else run_dir(base)
    name = prefixed_name(base, model_name(run_slug(base)))
    return {
        "base": base,
        "adapter": Path(args.adapter) if args.adapter else default_adapter(out_dir),
        "merged": out_dir / "merged",
        "name": name,
        "licence": PRESETS.get(preset_for(base) or "", {}).get("licence", "apache-2.0"),
        "gguf": gguf_paths(out_dir, name),
    }


def plan_json(p: dict) -> dict:
    """The plan as JSON — what to publish, under which name and licence.

    The publish step used to rebuild these strings in bash (`marola-sea-${PRESET#qwen-}-GGUF`,
    a hardcoded apache-2.0), which silently disagreed with what this script had actually written:
    a different repo name, the wrong file path for a preset whose name carries the Llama- prefix,
    and the wrong licence for the two Llama presets. One producer, one consumer, no second guess.
    """
    return {
        "base": p["base"],
        "name": p["name"],
        "licence": p["licence"],
        "adapter": str(p["adapter"]),
        "merged": str(p["merged"]),
        "gguf": {k: str(v) for k, v in p["gguf"].items()},
    }


def merge(base_id: str, adapter: Path, merged_out: Path) -> None:
    """The only part that needs the heavy dependencies — imported here so --dry-run/--self-test
    stay runnable on a machine with neither."""
    try:
        import torch
        from peft import PeftModel
        from transformers import AutoModelForCausalLM, AutoTokenizer
    except ImportError as exc:  # pragma: no cover - environment-dependent
        raise SystemExit(
            f"merge_export: {exc} — run `pip install -r finetune/requirements.txt` first "
            "(torch/transformers/peft). --dry-run needs none of them."
        ) from exc

    print(f"loading base {base_id} ...")
    model = AutoModelForCausalLM.from_pretrained(base_id, torch_dtype=torch.float16)
    print(f"applying adapter {adapter} ...")
    model = PeftModel.from_pretrained(model, str(adapter))
    print("merging adapter weights into the base ...")
    model = model.merge_and_unload()
    merged_out.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(merged_out))
    AutoTokenizer.from_pretrained(base_id).save_pretrained(str(merged_out))
    print(f"merged model written to {merged_out}")


def self_test() -> int:
    fails = 0

    def ok(got, want, label):
        nonlocal fails
        if got == want:
            print(f"  ok   {label}")
        else:
            fails += 1
            print(f"  FAIL {label} — got {got!r}, want {want!r}")

    ok(model_name("tiny"), "marola-sea-tiny", "model_name follows MIP-0025 §5.1(3)'s stem")
    ok(
        prefixed_name("HuggingFaceTB/SmolLM2-360M-Instruct", "marola-sea-tiny"),
        "marola-sea-tiny",
        "a SmolLM2-derived model needs no Llama- prefix",
    )
    ok(
        prefixed_name("unsloth/Llama-3.2-1B-Instruct", "marola-sea-small"),
        "Llama-marola-sea-small",
        "a Llama-derived model MUST start with Llama- (Meta Community Licence)",
    )
    ok(
        prefixed_name("meta-llama/Llama-3.2-3B-Instruct", "marola-sea-base"),
        "Llama-marola-sea-base",
        "the gated base preset is Llama-derived too",
    )
    ok(
        prefixed_name("Qwen/Qwen2.5-7B-Instruct", "marola-sea-qwen-7b"),
        "marola-sea-qwen-7b",
        "an Apache-2.0 Qwen base carries no naming obligation",
    )

    class _Args:
        preset, base, adapter, out = "qwen-7b", None, None, None

    j = plan_json(plan(_Args()))
    ok(j["name"], "marola-sea-qwen-7b", "the plan JSON carries the published name")
    ok(j["licence"], "apache-2.0", "the plan JSON carries the base's licence id")
    ok(
        j["gguf"]["Q4_K_M"].endswith("out/qwen-7b/marola-sea-qwen-7b-Q4_K_M.gguf"),
        True,
        "the plan JSON's GGUF path is the one merge_export actually writes",
    )
    ok(all(isinstance(v, str) for v in j["gguf"].values()), True, "the plan JSON is serialisable")

    names = sorted(p.name for p in gguf_paths(Path("/o"), "marola-sea-tiny").values())
    ok(
        names,
        ["marola-sea-tiny-Q4_K_M.gguf", "marola-sea-tiny-Q8_0.gguf", "marola-sea-tiny-f16.gguf"],
        "exports f16 plus both quantizations MIP-0025 §5.1 asks for",
    )
    argv = convert_argv(Path("/llama"), Path("/o/merged"), Path("/o/m-f16.gguf"))
    ok(
        argv[1],
        "/llama/convert_hf_to_gguf.py",
        "converts with convert_hf_to_gguf.py, not convert_lora_to_gguf.py",
    )
    ok(argv[-1], "f16", "converts at f16 before quantizing")
    ok(
        quantize_argv(Path("/nope"), Path("/o/a.gguf"), Path("/o/b.gguf"), "Q4_K_M")[-1],
        "Q4_K_M",
        "quantize_argv passes the quantization type through",
    )
    ok(
        default_adapter(Path("/definitely/missing")).name,
        "adapter",
        "with no DPO adapter present, the SFT adapter is the one merged",
    )
    # merge_export shells out to llama.cpp's convert_hf_to_gguf.py, whose vocab probe catches only
    # FileNotFoundError: a missing sentencepiece surfaces as ModuleNotFoundError and kills the run.
    # setup-ml-venv (labs/cuda) installs the requirements file as given and knows nothing of this.
    req = (Path(__file__).parent / "requirements.txt").read_text()
    for dep in ("gguf", "sentencepiece", "protobuf"):
        ok(
            dep in req,
            True,
            f"{dep} is in finetune/requirements.txt — convert_hf_to_gguf.py needs it",
        )

    if fails:
        print(f"merge_export self-test: {fails} failure(s)", file=sys.stderr)
        return 1
    print("merge_export self-test: ok")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--preset", choices=sorted(PRESETS), default="tiny")
    ap.add_argument("--base", default=None, help="explicit HF model id; overrides --preset")
    ap.add_argument(
        "--adapter",
        default=None,
        help="LoRA adapter dir (default: out/<preset>/dpo-adapter, else out/<preset>/adapter)",
    )
    ap.add_argument(
        "--out",
        default=None,
        help="the run directory holding the adapter and the GGUFs "
        "(default: finetune/out/<preset>, matching train_lora.py)",
    )
    ap.add_argument(
        "--llama-cpp", default=None, help="path to a llama.cpp checkout (for convert + quantize)"
    )
    ap.add_argument(
        "--skip-convert", action="store_true", help="merge only; leave GGUF conversion to you"
    )
    ap.add_argument(
        "--dry-run", action="store_true", help="print the plan and the exact commands, run nothing"
    )
    ap.add_argument(
        "--plan-json",
        default=None,
        help="also write the plan (name, licence, base, GGUF paths) here, for the publish step to "
        "consume instead of rebuilding those strings itself",
    )
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    p = plan(args)
    if args.plan_json:
        dest = Path(args.plan_json)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(json.dumps(plan_json(p), indent=2) + "\n")
        print(f"plan    : {dest}")
    llama_cpp = Path(args.llama_cpp) if args.llama_cpp else None
    print(f"base    : {p['base']} ({p['licence']})")
    print(f"adapter : {p['adapter']}")
    print(f"merged  : {p['merged']}")
    for k, v in p["gguf"].items():
        print(f"gguf    : {k:7} {v}")

    if args.dry_run:
        print("\n--- commands this would run ---")
        print(f"# merge: peft merge_and_unload({p['base']} + {p['adapter']}) -> {p['merged']}")
        if not args.skip_convert:
            cc = llama_cpp or Path("<--llama-cpp>")
            print(" ".join(convert_argv(cc, p["merged"], p["gguf"]["f16"])))
            for q in QUANTIZATIONS:
                print(" ".join(quantize_argv(cc, p["gguf"]["f16"], p["gguf"][q], q)))
        print("\n# then publish the QUANTIZED files (never the adapter):")
        print(
            f"just finetune-publish repo=<you>/{p['name']}-GGUF "
            f"gguf={p['gguf']['Q4_K_M']} base={p['base']} --base-license {p['licence']}"
        )
        print("dry run: nothing was written")
        return 0

    if not p["adapter"].exists():
        raise SystemExit(
            f"merge_export: no adapter at {p['adapter']} — run `just finetune-train` (and "
            "optionally `just finetune-train-dpo`) first"
        )
    check_adapter_base(p["adapter"], p["base"])
    merge(p["base"], p["adapter"], p["merged"])

    if args.skip_convert:
        print("--skip-convert: merged model only, no GGUF written")
        return 0
    if llama_cpp is None or not (llama_cpp / "convert_hf_to_gguf.py").exists():
        print(
            f"\nmerged model is at {p['merged']}. Pass --llama-cpp <path to a llama.cpp checkout> "
            "to convert and quantize it here, or run these yourself:",
            file=sys.stderr,
        )
        cc = llama_cpp or Path("<llama.cpp>")
        print(" ".join(convert_argv(cc, p["merged"], p["gguf"]["f16"])), file=sys.stderr)
        return 1
    subprocess.run(convert_argv(llama_cpp, p["merged"], p["gguf"]["f16"]), check=True)
    for q in QUANTIZATIONS:
        subprocess.run(quantize_argv(llama_cpp, p["gguf"]["f16"], p["gguf"][q], q), check=True)
        print(f"quantized {p['gguf'][q].name}")
    print(
        f"\nnext: just finetune-publish repo=<you>/{p['name']}-GGUF gguf={p['gguf']['Q4_K_M']} "
        f"base={p['base']} --base-license {p['licence']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
