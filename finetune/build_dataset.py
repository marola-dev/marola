"""Build marola's fine-tuning dataset from what the repo already has — no new labelling.

Sources (all local files, no network, no model calls):
  1. core/src/main/resources/recommendation_prompt.json — the DSPy-compiled summarizer demos:
     structured conditions in, one-to-two-sentence summary out. The core "shape" marola wants.
  2. core/src/main/resources/review_prompt.json — the reviewer demos: conditions + draft in,
     compact JSON verdict out. Teaches the JSON-only discipline small models break most.
  3. core/src/main/resources/sea_lore.json — "tell me something about X" → the sourced paragraph,
     with the source URL kept in the answer so the habit of citing survives.
  4. knowledge/*.md (recursively) — one Q/A per chunk and per sentence, each asked through several
     question templates (MIP-0025 §4.3 Layer 1). Every answer is verbatim text from the file it
     cites; no model call generates anything.
  5. Tool-call SFT (MIP-0025 §4.3 Layer 2): questions → one JSON call to the four MCP tools in
     cli/src/main/scala/marola/agent/SwimConditionsMcpServer.scala. Teaches when to call, not facts.

Output: finetune/data/train.jsonl and eval.jsonl in the chat format most trainers accept:
  {"messages": [{"role": "system", ...}, {"role": "user", ...}, {"role": "assistant", ...}]}

Run:  python build_dataset.py            (or `just finetune-dataset`)
Self-test:  python build_dataset.py --self-test   (or `just quality-other`)

`--resources DIR` / `--knowledge DIR` override where 1-3 and 4-5 above are read from — the app ->
ml contract (MIP-0070 §5.4): once marola-ml is a separate repo, `--resources` points at the
unpacked resources tarball ci.yml publishes, not `../core`. `--knowledge` defaults to
$MAROLA_KNOWLEDGE_DIR if set, else this repo's `.tmp/knowledge` from `just corpus-fetch` (anchored
like `--resources`, not the cwd — this file is documented to run from `finetune/`).
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import subprocess
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RESOURCES = REPO / "core" / "src" / "main" / "resources"
OUT = Path(__file__).resolve().parent / "data"


def default_knowledge_dir() -> Path:
    env = os.environ.get("MAROLA_KNOWLEDGE_DIR")
    return Path(env) if env is not None else REPO / ".tmp" / "knowledge"


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


def render_inputs(demo: dict, fields: tuple[str, ...]) -> str:
    return "\n".join(f"{f.replace('_', ' ').title()}: {demo[f]}" for f in fields if f in demo)


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


# --- Layer 2: tool-call SFT (MIP-0025 §4.3) --------------------------------------------------
# Transcribed from SwimConditionsMcpServer.scala's tool schemas; change both together.
TOOL_CALL_SYSTEM = (
    "You are marola, a swim-conditions assistant for open-water swimmers in Brazil. When a "
    "question needs live data you don't have, reply with exactly one JSON object of the shape "
    '{"tool": "<name>", "arguments": {...}} and nothing else — never invent the result yourself.'
)

TOOL_SCHEMAS: dict[str, dict[str, tuple[str, ...]]] = {
    "find_nearby_beaches": {"required": ("lat", "lon"), "optional": ("radius_km",)},
    "get_swim_recommendation": {"required": ("lat", "lon"), "optional": ("radius_km",)},
    "get_water_quality": {"required": ("lat", "lon"), "optional": ("radius_km",)},
    "ask_ocean_question": {"required": ("question",), "optional": ()},
}

# cli/src/test/resources/site/board.json's beaches.
LOCATIONS: tuple[tuple[float, float], ...] = ((-27.6296, -48.4487), (-27.4021, -48.4157))
RADII: tuple[float | None, ...] = (None, 10.0, 20.0)

FIND_BEACHES_TEMPLATES = (
    "Find swim beaches near {lat}, {lon}.",
    "What open-water beaches are close to {lat}, {lon}?",
    "Search for beaches around latitude {lat}, longitude {lon}.",
    "Are there any beaches near {lat}, {lon}?",
    "List the beaches close to {lat}, {lon}.",
    "I'm at {lat}, {lon} — any beaches nearby?",
    "Beaches near {lat}, {lon}, please.",
    "Which beaches are around {lat}, {lon}?",
    "Show me swim spots close to {lat}, {lon}.",
    "Look up beaches near coordinate {lat}, {lon}.",
)

RECOMMENDATION_TEMPLATES = (
    "What's the best hour to swim tomorrow near {lat}, {lon}?",
    "Give me tomorrow's swim conditions near {lat}, {lon}.",
    "When should I swim tomorrow around {lat}, {lon}?",
    "Best swim window tomorrow near {lat}, {lon}?",
    "What are tomorrow's conditions like near {lat}, {lon}?",
    "Recommend a swim time near {lat}, {lon} for tomorrow.",
    "I want to swim tomorrow near {lat}, {lon} — when's best?",
    "Tell me tomorrow's swimability near {lat}, {lon}.",
    "Forecast tomorrow's swim conditions for {lat}, {lon}.",
    "What hour has the best score near {lat}, {lon} tomorrow?",
)

WATER_QUALITY_TEMPLATES = (
    "Is the water clean near {lat}, {lon}?",
    "What's the bathing-water quality near {lat}, {lon}?",
    "Check water quality around {lat}, {lon}.",
    "Any water quality warnings near {lat}, {lon}?",
    "Is it safe to swim near {lat}, {lon} — water quality-wise?",
    "Give me the enterococci readings near {lat}, {lon}.",
    "PRÓPRIA or IMPRÓPRIA near {lat}, {lon}?",
    "What do the sampling points say near {lat}, {lon}?",
    "Water quality check for {lat}, {lon}.",
    "Has water near {lat}, {lon} been tested recently?",
)

ASK_QUESTION_TEMPLATES = (
    "{question}",
    "Hey marola, {question}",
    "Quick question: {question}",
    "{question} Please look it up.",
    "I want to know: {question}",
    "Can you answer this: {question}",
    "{question} (from a swimmer prepping a trip)",
    "Ocean question: {question}",
    "{question} What does your knowledge base say?",
    "Before I go for a swim, {question}",
)


def _tool_call_example(user: str, tool: str, arguments: dict) -> dict:
    assistant = json.dumps({"tool": tool, "arguments": arguments})
    return {
        "messages": [
            {"role": "system", "content": TOOL_CALL_SYSTEM},
            {"role": "user", "content": user},
            {"role": "assistant", "content": assistant},
        ]
    }


def _latlon_tool_examples(tool: str, templates: tuple[str, ...]) -> list[dict]:
    rows = []
    for lat, lon in LOCATIONS:
        for radius in RADII:
            arguments: dict[str, float] = {"lat": lat, "lon": lon}
            if radius is not None:
                arguments["radius_km"] = radius
            for t in templates:
                user = t.format(lat=lat, lon=lon)
                if radius is not None:
                    user += f" Search within {radius:g} km."
                rows.append(_tool_call_example(user, tool, arguments))
    return rows


def _ask_tool_examples(knowledge_dir: Path, sea_lore_path: Path) -> list[dict]:
    questions = []
    for path in sorted(knowledge_dir.rglob("*.md")):
        title, source, _ = chunk_markdown(path.read_text(encoding="utf-8"))
        if source:
            questions.append(f"What do you know about {title.lower()}?")
    if sea_lore_path.exists():
        for e in json.loads(sea_lore_path.read_text(encoding="utf-8")):
            questions.append(f"Tell me about {e['id'].replace('-', ' ')}.")
    rows = []
    for question in questions:
        for t in ASK_QUESTION_TEMPLATES:
            user = t.format(question=question)
            rows.append(_tool_call_example(user, "ask_ocean_question", {"question": question}))
    return rows


def from_tool_calls(knowledge_dir: Path, sea_lore_path: Path) -> list[dict]:
    rows = []
    rows += _latlon_tool_examples("find_nearby_beaches", FIND_BEACHES_TEMPLATES)
    rows += _latlon_tool_examples("get_swim_recommendation", RECOMMENDATION_TEMPLATES)
    rows += _latlon_tool_examples("get_water_quality", WATER_QUALITY_TEMPLATES)
    rows += _ask_tool_examples(knowledge_dir, sea_lore_path)
    return rows


def _self_test(knowledge: Path) -> None:
    # A missing or empty corpus must stop main() before it writes a dataset without Layer 1.
    global OUT
    real_out = OUT
    with tempfile.TemporaryDirectory() as tmp:
        OUT = Path(tmp) / "out"
        (Path(tmp) / "empty").mkdir()
        for bad in (Path(tmp) / "missing", Path(tmp) / "empty"):
            try:
                main(RESOURCES, bad)
            except SystemExit as e:
                assert e.code not in (None, 0), f"main() exited 0 on corpus {bad}"
            else:
                raise AssertionError(f"main() built a dataset from corpus {bad}")
        assert not OUT.exists(), "main() wrote a dataset before checking the corpus"
    OUT = real_out

    rows = from_knowledge(knowledge)
    assert len(rows) >= KNOWLEDGE_EXAMPLE_FLOOR, (
        f"knowledge-derived example count {len(rows)} below floor {KNOWLEDGE_EXAMPLE_FLOOR} "
        "— MIP-0025 task 2 requires thousands, not dozens"
    )
    sources = _load_knowledge_sources(knowledge)
    assert sources, "no knowledge sources found under " + str(knowledge)
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

    # The real app -> ml contract artifact (scripts/build-resources-tarball.sh), unpacked into a
    # dir with no core/ sibling at all: proves --resources isn't secretly hardcoded to
    # REPO/core/src/main/resources, and exercises the real sea_lore.json branch of
    # _ask_tool_examples (MIP-0070 §5.4, task 3's own acceptance check).
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        tar_path = tmp_path / "resources.tar.gz"
        subprocess.run(
            [str(REPO / "scripts" / "build-resources-tarball.sh"), str(tar_path)],
            cwd=REPO,
            check=True,
            capture_output=True,
        )
        unpacked = tmp_path / "unpacked"
        unpacked.mkdir()
        subprocess.run(["tar", "-xzf", str(tar_path), "-C", str(unpacked)], check=True)
        sea_lore_path = unpacked / "sea_lore.json"
        assert sea_lore_path.exists(), (
            "build-resources-tarball.sh's output isn't flat at its root — "
            "build_dataset.py --resources can't read it (MIP-0070 §5.4)"
        )
        no_lore_count = len(from_tool_calls(knowledge, tmp_path / "does-not-exist.json"))
        tool_rows = from_tool_calls(knowledge, sea_lore_path)
    assert len(tool_rows) > no_lore_count, (
        f"the real sea_lore.json added no tool-call examples: {no_lore_count} without it, "
        f"{len(tool_rows)} with it"
    )
    assert tool_rows, "no tool-call examples generated"
    seen_tools: set[str] = set()
    for row in tool_rows:
        assistant = row["messages"][2]["content"]
        parsed = json.loads(assistant)  # raises if not syntactically valid JSON
        assert set(parsed.keys()) == {"tool", "arguments"}, f"unexpected shape: {parsed!r}"
        tool = parsed["tool"]
        assert tool in TOOL_SCHEMAS, f"not a real MCP tool name: {tool!r}"
        seen_tools.add(tool)
        schema = TOOL_SCHEMAS[tool]
        args = parsed["arguments"]
        assert isinstance(args, dict), f"{tool} arguments must be an object: {args!r}"
        for req in schema["required"]:
            assert req in args, f"{tool} call is missing required argument {req!r}: {args!r}"
        allowed = set(schema["required"]) | set(schema["optional"])
        for key in args:
            assert key in allowed, f"{tool} call has an argument not in its real schema: {key!r}"
    assert seen_tools == set(TOOL_SCHEMAS), (
        f"missing tool coverage: {set(TOOL_SCHEMAS) - seen_tools}"
    )
    print(
        f"self-test OK: {len(rows)} knowledge-derived examples "
        f"(floor {KNOWLEDGE_EXAMPLE_FLOOR}), every fact verified verbatim against its cited "
        f"source; {len(tool_rows)} tool-call examples ({no_lore_count} without sea_lore.json, "
        f"{len(tool_rows)} with it) covering all {len(TOOL_SCHEMAS)} real MCP tools with "
        f"syntactically valid call shapes"
    )


def main(resources: Path, knowledge: Path) -> None:
    # from_knowledge() yields nothing for a missing dir: fail rather than train without Layer 1.
    if not knowledge.is_dir() or not any(knowledge.rglob("*.md")):
        raise SystemExit(
            f"build_dataset: no knowledge/*.md under {knowledge} — run `just corpus-fetch`, "
            "or point --knowledge / MAROLA_KNOWLEDGE_DIR at a corpus checkout"
        )
    rows: list[dict] = []
    rows += from_compiled_prompt(resources / "recommendation_prompt.json", "summary")
    rows += from_compiled_prompt(
        resources / "review_prompt.json", "review_json", extra_inputs=("summary",)
    )
    rows += from_sea_lore(resources / "sea_lore.json")
    rows += from_knowledge(knowledge)
    rows += from_tool_calls(knowledge, resources / "sea_lore.json")

    random.Random(42).shuffle(rows)
    n_eval = max(2, len(rows) // 10)
    eval_rows, train_rows = rows[:n_eval], rows[n_eval:]

    OUT.mkdir(parents=True, exist_ok=True)
    for name, data in (("train.jsonl", train_rows), ("eval.jsonl", eval_rows)):
        with (OUT / name).open("w", encoding="utf-8") as f:
            for r in data:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"wrote {len(train_rows)} train + {len(eval_rows)} eval examples to {OUT}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "--resources",
        type=Path,
        default=RESOURCES,
        help="dir with recommendation_prompt.json / review_prompt.json / sea_lore.json "
        "(default: core/src/main/resources)",
    )
    ap.add_argument(
        "--knowledge",
        type=Path,
        default=default_knowledge_dir(),
        help="knowledge/*.md corpus dir (default: $MAROLA_KNOWLEDGE_DIR if set, else this "
        "repo's .tmp/knowledge, anchored like --resources)",
    )
    ap.add_argument("--self-test", action="store_true")
    return ap.parse_args(argv)


if __name__ == "__main__":
    args = parse_args()
    if args.self_test:
        _self_test(args.knowledge)
    else:
        main(args.resources, args.knowledge)
