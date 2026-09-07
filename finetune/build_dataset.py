"""Build marola's fine-tuning dataset from what the repo already has — no new labelling.

Sources (all local files, no network, no model calls):
  1. core/src/main/resources/recommendation_prompt.json — the DSPy-compiled summarizer demos:
     structured conditions in, one-to-two-sentence summary out. The core "shape" marola wants.
  2. core/src/main/resources/review_prompt.json — the reviewer demos: conditions + draft in,
     compact JSON verdict out. Teaches the JSON-only discipline small models break most.
  3. core/src/main/resources/sea_lore.json — "tell me something about X" → the sourced paragraph,
     with the source URL kept in the answer so the habit of citing survives.
  4. knowledge/*.md (recursively, e.g. knowledge/safety/) — one Q/A per chunk, *and* one Q/A per
     individual sentence within that chunk, each asked with several paraphrased question templates
     (MIP-0025 §4.3, Layer 1 "Marine Corpus Domain"). This is data augmentation, not fact
     invention: every answer is still either a real chunk or a real sentence copied verbatim from
     the knowledge file it cites, just asked more than one way — enough to move from "a few dozen
     examples" (format/tone only) to thousands (real domain facts too), without a live model call
     to generate anything. Deliberately deterministic and reproducible: MIP-0025 §4.3 notes that
     LLM-based synthetic generation (e.g. Reviewer-filtered) was "not run or estimated for real
     cost" — that stays future work, not something this script does silently.

Output: finetune/data/train.jsonl and eval.jsonl in the chat format most trainers accept:
  {"messages": [{"role": "system", ...}, {"role": "user", ...}, {"role": "assistant", ...}]}

Run:  python build_dataset.py            (or `just finetune-dataset`)
Self-test:  python build_dataset.py --self-test   (or `just quality-other`)
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
    "beach_name",
    "hour_local",
    "sea_temp_c",
    "wind_kmh",
    "wave_height_m",
    "jellyfish_risk",
    "whale_sighting_likelihood",
    "score",
)


def label(field: str) -> str:
    return " ".join(w.capitalize() for w in field.split("_"))


def render_inputs(demo: dict, fields: tuple[str, ...]) -> str:
    return "\n".join(f"{label(f)}: {demo[f]}" for f in fields if f in demo)


def example(user: str, assistant: str) -> dict:
    return {
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": user},
            {"role": "assistant", "content": assistant},
        ]
    }


def from_compiled_prompt(
    path: Path, output_field: str, extra_inputs: tuple[str, ...] = ()
) -> list[dict]:
    doc = json.loads(path.read_text(encoding="utf-8"))
    instructions = doc.get("signature", {}).get("instructions", "").strip()
    rows = []
    for demo in doc.get("demos", []):
        if output_field not in demo:
            continue
        user = (instructions + "\n\n" if instructions else "") + render_inputs(
            demo, INPUT_FIELDS + extra_inputs
        )
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
    title = next((ln[2:].strip() for ln in lines if ln.startswith("# ")), "")
    source = next((ln[7:].strip() for ln in lines if ln.lower().startswith("source:")), "")
    body = "\n".join(
        ln for ln in lines if not (ln.startswith("# ") or ln.lower().startswith("source:"))
    )
    paras = [p.strip() for p in re.split(r"\n\s*\n", body) if p.strip()]
    chunks: list[str] = []
    for p in paras:
        if chunks and len(chunks[-1]) + len(p) + 2 <= max_chars:
            chunks[-1] = chunks[-1] + "\n\n" + p
        else:
            chunks.append(p)
    return title, source, chunks


# Chunk-level: the question is asked several ways, the answer is always the full real chunk.
CHUNK_QUESTION_TEMPLATES = (
    "What do your notes say about {title}? (part {part})",
    "Tell me what you know about {title}, part {part}.",
    "Summarize what your notes cover on {title} (part {part}).",
    "Give me the part {part} details from your notes on {title}.",
    "What's in section {part} of your {title} notes?",
    "Explain {title} using what your notes say (part {part}).",
    "I'm curious about {title} — what does part {part} of your notes cover?",
    "Recap part {part} of your knowledge on {title}.",
    "What have you got written down about {title}, specifically part {part}?",
    "Walk me through part {part} of your {title} notes.",
    "What's the part {part} summary of {title} in your notes?",
    "According to your notes, what's true about {title} (part {part})?",
    "Pull up part {part} of what you know about {title}.",
    "What can you tell a swimmer about {title}? (part {part})",
    "Notes check: {title}, part {part}.",
    "Brief me on {title}, part {part}, from your sources.",
    "What's documented about {title} in part {part} of your notes?",
    "Give a plain-language rundown of {title}, part {part}.",
    "What should I know about {title}? (see part {part} of your notes)",
    "Show me part {part} of your {title} material.",
)

# Sentence-level: same idea, one real sentence at a time — finer-grained facts, still verbatim.
SENTENCE_QUESTION_TEMPLATES = (
    "Give me one specific fact from your notes on {title} (part {part}).",
    "What's a detail from your {title} notes, part {part}?",
    "Pick one fact from part {part} of your {title} notes.",
    "What's something true about {title} from part {part} of your notes?",
    "Quote one fact from your notes on {title} (part {part}).",
    "Name a specific point from your {title} notes, part {part}.",
    "What's one thing your notes say about {title}? (part {part})",
    "Give a short, specific fact about {title} (part {part}).",
    "What detail from part {part} of your {title} notes stands out?",
    "Tell me a single fact from your {title} notes (part {part}).",
    "What's one takeaway from part {part} of your {title} notes?",
    "Share one specific fact about {title} (part {part}).",
    "What's a concrete detail about {title}? (from part {part} of your notes)",
    "Give me a fact, not a summary, about {title} (part {part}).",
    "What does part {part} of your {title} notes say, in one fact?",
    "Point to one fact in your {title} notes (part {part}).",
    "What's a specific claim in part {part} of your {title} notes?",
    "Give one sentence of fact about {title} (part {part}).",
    "What's a single detail worth knowing about {title}? (part {part})",
    "Extract one fact from part {part} of your {title} notes.",
)


def _split_sentences(text: str) -> list[str]:
    """Real sentences only (>20 chars) so a stray abbreviation dot doesn't yield a fragment."""
    return [s.strip() for s in re.split(r"(?<=[.!?])\s+", text) if len(s.strip()) > 20]


def _chunk_examples(title: str, source: str, chunk: str, part: int) -> list[dict]:
    answer = f"{chunk}\n\nSource: {source}"
    return [
        example(t.format(title=title.lower(), part=part), answer) for t in CHUNK_QUESTION_TEMPLATES
    ]


def _sentence_examples(title: str, source: str, chunk: str, part: int) -> list[dict]:
    rows = []
    for sentence in _split_sentences(chunk):
        answer = f"{sentence}\n\nSource: {source}"
        rows += [
            example(t.format(title=title.lower(), part=part), answer)
            for t in SENTENCE_QUESTION_TEMPLATES
        ]
    return rows


def from_knowledge(dir_: Path) -> list[dict]:
    rows = []
    for path in sorted(dir_.rglob("*.md")):
        title, source, chunks = chunk_markdown(path.read_text(encoding="utf-8"))
        if not source:
            continue  # README.md and anything else without a citable source
        for i, chunk in enumerate(chunks):
            part = i + 1
            rows += _chunk_examples(title, source, chunk, part)
            rows += _sentence_examples(title, source, chunk, part)
    return rows


def _load_knowledge_sources(dir_: Path) -> dict[str, str]:
    """Map each knowledge doc's cited source URL to its raw file text, for provenance checks."""
    out: dict[str, str] = {}
    for path in sorted(dir_.rglob("*.md")):
        raw = path.read_text(encoding="utf-8")
        _, source, _ = chunk_markdown(raw)
        if source:
            out[source] = raw
    return out


KNOWLEDGE_EXAMPLE_FLOOR = 2000


def _self_test() -> None:
    rows = from_knowledge(KNOWLEDGE)
    assert len(rows) >= KNOWLEDGE_EXAMPLE_FLOOR, (
        f"knowledge-derived example count {len(rows)} below floor {KNOWLEDGE_EXAMPLE_FLOOR} "
        "— MIP-0025 task 2 requires thousands, not dozens"
    )
    sources = _load_knowledge_sources(KNOWLEDGE)
    assert sources, "no knowledge sources found under " + str(KNOWLEDGE)
    for row in rows:
        answer = row["messages"][2]["content"]
        assert "\n\nSource: " in answer, f"synthetic example missing a Source line: {answer!r}"
        fact, _, url = answer.rpartition("\n\nSource: ")
        raw = sources.get(url)
        assert raw is not None, f"cited source is not a real knowledge/*.md source: {url!r}"
        assert fact in raw, (
            f"synthetic fact not found verbatim in the file that cites {url} — looks invented: "
            f"{fact[:80]!r}"
        )
    print(
        f"self-test OK: {len(rows)} knowledge-derived examples "
        f"(floor {KNOWLEDGE_EXAMPLE_FLOOR}), every fact verified verbatim against its cited source"
    )


def main() -> None:
    rows: list[dict] = []
    rows += from_compiled_prompt(RESOURCES / "recommendation_prompt.json", "summary")
    rows += from_compiled_prompt(
        RESOURCES / "review_prompt.json", "review_json", extra_inputs=("summary",)
    )
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
    import sys

    if "--self-test" in sys.argv:
        _self_test()
    else:
        main()
