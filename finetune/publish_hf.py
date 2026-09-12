#!/usr/bin/env python3
"""Publish a marola-sea GGUF to a Hugging Face model repo (MIP-0025 §5.1, MIP-0033 §5.3).

Uploads one or more `.gguf` files plus a generated model card and a `CHECKSUMS` file (sha256 per
file, so a Modelfile can pin an exact blob rather than a mutable filename — the same discipline
MIP-0008's image gate applies to `marola-local`). Needs `pip install huggingface_hub` and a prior
`huggingface-cli login` (or `HF_TOKEN` in the environment) — neither is run here, both are the
human's own one-time setup per `AGENTS.md`'s "never hardcode a key" rule.

This is the export chain's *last* step only. Get the `.gguf` file(s) first:
  1. `just finetune-train preset=<preset>` (LoRA adapter, finetune/out/<preset>/adapter/)
  2. merge + convert with llama.cpp's `convert_hf_to_gguf.py` (base) or use `ollama create` +
     `Modelfile.adapter` for local-only use without merging (see finetune/README.md)
  3. this script, pointed at the resulting .gguf file(s)

Deliberately does not shell out to `ollama push` (MIP-0025 §5.1(2)'s optional second channel) —
that needs a separate `ollama signin` and a private key this script has no business touching.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def checksums_file(gguf_paths: list[Path]) -> str:
    """`sha256sum`-compatible output — one line per file, sorted by name for a stable diff."""
    lines = [f"{sha256_of(p)}  {p.name}" for p in sorted(gguf_paths, key=lambda p: p.name)]
    return "\n".join(lines) + "\n"


def model_card(
    repo_id: str,
    base_model: str,
    base_license: str,
    gguf_paths: list[Path],
    eval_note: str,
) -> str:
    """Pure — no network, no filesystem beyond the already-read `gguf_paths` names/sizes, so this
    is what `PublishHfSpec`-style tests would exercise if this script grows Python tests; today it
    is checked by hand against MIP-0025 §5.1(2)'s required sections (base model, training-data
    description, eval numbers, intended use, the IMPRÓPRIA/safety caveat) before every real
    publish, since there is no Python test harness in this repo (`just quality-other` lints
    `scripts/*.py`, not `finetune/*.py`, which stays a maintainer-run tool, same as `train_lora.py`).
    """
    files = "\n".join(f"- `{p.name}` ({p.stat().st_size / 1e6:.0f} MB)" for p in gguf_paths)
    return f"""---
base_model: {base_model}
license: {base_license}
tags:
- gguf
- marola
- ocean
pipeline_tag: text-generation
---

# {repo_id.split("/")[-1]}

A GGUF release of a marola-sea checkpoint — a small model tuned on marola's own
question/answer shape (open-water swim conditions, safety, sea life), served locally through
[Ollama](https://ollama.com) alongside marola's own RAG corpus and deterministic scoring
(`Recommender`/`Swimability`, never the model itself).

**Honest framing (MIP-0025 §6, §8):** tuning changes tone and format reliability, not factual
grounding. This model does not replace marola's RAG corpus (`knowledge/`, cited answers only) or
its `Reviewer` pass, and it is **not a standalone safety authority** — treat any first-aid or
hazard answer as a starting point, not a substitute for a lifeguard or emergency services (marola's
own answers carry this caveat automatically via the MIP-0022 safety footer; this raw checkpoint,
used outside marola, does not).

## Files

{files}

`CHECKSUMS` (sha256) ships alongside these files — pin a specific hash in your own `Modelfile`
rather than a bare filename, so re-quantizing upstream can't silently change what you run.

## Use with Ollama

```bash
ollama run hf.co/{repo_id}
# or a specific quant tag, e.g.:
ollama run hf.co/{repo_id}:Q4_K_M
```

## Base model and training

Fine-tuned from [{base_model}](https://huggingface.co/{base_model}) via LoRA (`finetune/train_lora.py`
in [h0ffmann/marola](https://github.com/h0ffmann/marola)) on a small, hand-built dataset derived
from marola's own DSPy-compiled demos, sea-lore entries, and knowledge-corpus Q&A
(`finetune/build_dataset.py`) — a few dozen examples, enough to teach format and tone, not facts.

{eval_note}

## Licence

Base model licence: {base_license}. See the base model's own repo for the full licence text.
"""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="e.g. yourname/marola-sea-tiny-GGUF")
    parser.add_argument(
        "--gguf", nargs="+", required=True, type=Path, help="one or more .gguf files"
    )
    parser.add_argument(
        "--base-model", required=True, help="e.g. HuggingFaceTB/SmolLM2-360M-Instruct"
    )
    parser.add_argument(
        "--base-license",
        default="apache-2.0",
        help="the BASE model's licence id (not a generic default — check it per MIP-0025 §5.1(3): "
        "a Llama-derived model has a different name requirement entirely, see finetune/README.md)",
    )
    parser.add_argument(
        "--eval-note",
        default="No `just benchmark` numbers recorded yet for this checkpoint — see "
        "docs/benchmarks/ in the source repo before trusting this over a plain base model.",
    )
    parser.add_argument("--private", action="store_true", help="create the repo private")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="write CHECKSUMS + the model card locally, upload nothing",
    )
    args = parser.parse_args(argv)

    missing = [p for p in args.gguf if not p.is_file()]
    if missing:
        print(f"error: not found: {', '.join(str(p) for p in missing)}", file=sys.stderr)
        return 1

    checksums = checksums_file(args.gguf)
    card = model_card(args.repo, args.base_model, args.base_license, args.gguf, args.eval_note)

    if args.dry_run:
        out_dir = args.gguf[0].parent
        (out_dir / "CHECKSUMS").write_text(checksums)
        (out_dir / "README.md").write_text(card)
        print(
            f"dry run: wrote {out_dir / 'CHECKSUMS'} and {out_dir / 'README.md'}, uploaded nothing"
        )
        return 0

    try:
        from huggingface_hub import HfApi
    except ImportError:
        print("error: pip install huggingface_hub (see finetune/requirements.txt)", file=sys.stderr)
        return 1

    api = HfApi()
    api.create_repo(args.repo, repo_type="model", private=args.private, exist_ok=True)
    api.upload_file(
        path_or_fileobj=checksums.encode("utf-8"), path_in_repo="CHECKSUMS", repo_id=args.repo
    )
    api.upload_file(
        path_or_fileobj=card.encode("utf-8"), path_in_repo="README.md", repo_id=args.repo
    )
    for p in args.gguf:
        api.upload_file(path_or_fileobj=str(p), path_in_repo=p.name, repo_id=args.repo)
        print(f"uploaded {p.name}")

    print(f"done: https://huggingface.co/{args.repo}")
    print(f"try it: ollama run hf.co/{args.repo}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
