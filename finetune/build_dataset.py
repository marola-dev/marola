"""Build marola's fine-tuning dataset from what the repo already has — no new labelling.

Sources (all local files, no network, no model calls):
  1. core/src/main/resources/recommendation_prompt.json — the DSPy-compiled summarizer demos:
     structured conditions in, one-to-two-sentence summary out. The core "shape" marola wants.
  2. core/src/main/resources/review_prompt.json — the reviewer demos: conditions + draft in,
     compact JSON verdict out. Teaches the JSON-only discipline small models break most.
  3. core/src/main/resources/sea_lore.json — "tell me something about X" → the sourced paragraph,
     with the source URL kept in the answer so the habit of citing survives.
  4. knowledge/*.md — one Q/A per chunk: "What do your notes say about <title>?" → the chunk text
     plus "Source: <url>". Same reason as 3: teach *format with citation*, not facts.

Output: finetune/data/train.jsonl and eval.jsonl in the chat format most trainers accept:
  {"messages": [{"role": "system", ...}, {"role": "user", ...}, {"role": "assistant", ...}]}

Run:  python build_dataset.py            (or `just finetune-dataset`)
"""

from __future__ import annotations

import json
import random
import re
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESOURCES = REPO / "core" / "src" / "main" / "resources"
KNOWLEDGE = REPO / "knowledge"
OUT = Path(__file__).resolve().parent / "data"

SYSTEM = (
    "You are marola, a swim-conditions assistant for open-water swimmers in Brazil. Answer in one "
    "or two plain sentences from the facts given; never invent conditions; cite a source URL when "
    "you quote your notes; return compact JSON and nothing else when asked for JSON."
)

INPUT_FIELDS = (
    "beach_name", "hour_local", "sea_temp_c", "wind_kmh", "wave_height_m",
    "jellyfish_risk", "whale_sighting_likelihood", "score",
)


def label(field: str) -> str:
    return " ".join(w.capitalize() for w in field.split("_"))


def render_inputs(demo: dict, fields: tuple[str, ...]) -> str:
    return "\n".join(f"{label(f)}: {demo[f]}" for f in fields if f in demo)


def example(user: str, assistant: str) -> dict:
    return {"messages": [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": user},
        {"role": "assistant", "content": assistant},
    ]}


def from_compiled_prompt(path: Path, output_field: str, extra_inputs: tuple[str, ...] = ()) -> list[dict]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    instructions = doc.get("signature", {}).get("instructions", "").strip()
    rows = []
    for demo in doc.get("demos", []):
        if output_field not in demo:
            continue
        user = (instructions + "\n\n" if instructions else "") + render_inputs(demo, INPUT_FIELDS + extra_inputs)
        rows.append(example(user, demo[output_field]))
    return rows


def from_sea_lore(path: Path) -> list[dict]:
    rows = []
    for e in json.loads(path.read_text(encoding="utf-8")):
        topic = e["id"].replace("-", " ")
        user = f"Tell me something about the sea: {topic}."
        rows.append(example(user, f"{e['text']} [source: {e['source']}]"))
    return rows


def chunk_markdown(md: str, max_chars: int = 700) -> tuple[str, str, list[str]]:
    """Mirror of marola.knowledge.Corpus.chunkDocument: title, source, merged paragraphs."""
    lines = md.splitlines()
    title = next((l[2:].strip() for l in lines if l.startswith("# ")), "")
    source = next((l[7:].strip() for l in lines if l.lower().startswith("source:")), "")
    body = "\n".join(l for l in lines if not (l.startswith("# ") or l.lower().startswith("source:")))
    paras = [p.strip() for p in re.split(r"\n\s*\n", body) if p.strip()]
    chunks: list[str] = []
    for p in paras:
        if chunks and len(chunks[-1]) + len(p) + 2 <= max_chars:
            chunks[-1] = chunks[-1] + "\n\n" + p
        else:
            chunks.append(p)
    return title, source, chunks


def from_knowledge(dir_: Path) -> list[dict]:
    rows = []
    for path in sorted(dir_.glob("*.md")):
        title, source, chunks = chunk_markdown(path.read_text(encoding="utf-8"))
        if not source:
            continue  # README.md and anything else without a citable source
        for i, chunk in enumerate(chunks):
            user = f"What do your notes say about {title.lower()}? (part {i + 1})"
            rows.append(example(user, f"{chunk}\n\nSource: {source}"))
    return rows


def main() -> None:
    rows: list[dict] = []
    rows += from_compiled_prompt(RESOURCES / "recommendation_prompt.json", "summary")
    rows += from_compiled_prompt(RESOURCES / "review_prompt.json", "review_json", extra_inputs=("summary",))
    rows += from_sea_lore(RESOURCES / "sea_lore.json")
    rows += from_knowledge(KNOWLEDGE)

    random.Random(42).shuffle(rows)
    n_eval = max(2, len(rows) // 10)
    eval_rows, train_rows = rows[:n_eval], rows[n_eval:]

    OUT.mkdir(parents=True, exist_ok=True)
    for name, data in (("train.jsonl", train_rows), ("eval.jsonl", eval_rows)):
        with (OUT / name).open("w", encoding="utf-8") as f:
            for r in data:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"wrote {len(train_rows)} train + {len(eval_rows)} eval examples to {OUT}")


if __name__ == "__main__":
    main()
