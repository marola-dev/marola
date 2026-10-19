#!/usr/bin/env python3
"""routing_bundle — assemble the files ci.yml's graph job publishes to `routing-bundle` (MIP-0084 §5.3).

    scripts/routing_bundle.py --graph-dir DIR --out OUT --bench BENCH_OUTPUT \
        [--questions routing-bench.yaml] [--previous build.json] [--graphify VERSION] \
        [--repos-md docs/2-Building-marola/REPOS.md]
    scripts/routing_bundle.py --self-test

DIR is `graph build`'s output with `wiring.json` beside it. OUT gets graph.json, wiring.json,
build.json (DIR's, plus the graphify version and the bench score, in total and per question type),
manifest.json and routing-digest.md. Exit 1, writing nothing, when any type scores below --previous's
(MIP-0085 §5.1), or the total does when --previous has no types: the job then keeps the last bundle.
"""

import argparse
import json
import re
import shutil
import sys
import tempfile
from collections import defaultdict
from pathlib import Path

SCHEMA = 1
MAX_BYTES = 20 * 2**20  # route refuses a bundle file over this
WARN_BYTES = 10 * 2**20  # §5.3: past this the large files move out of refs/heads/
DIGEST_CHARS = 100_000  # §5.3's 25k-token cap, at about 4 characters a token
START, END = "<!-- wiring:start -->", "<!-- wiring:end -->"


def scores(bench: str, questions: Path) -> tuple[int, int, dict[str, dict[str, int]]]:
    """`route bench`'s output: the total and, by each question's type, right first of total."""
    if not re.search(r"^right first: \d+/\d+$", bench, re.M):
        raise SystemExit("routing_bundle: no 'right first: N/T' line in the bench output")
    qs = json.loads(questions.read_text(encoding="utf-8")) if questions.is_file() else []
    qs = qs.get("questions", []) if isinstance(qs, dict) else qs
    # A question without a type is one of MIP-0084's eight: wiring, or a symbol query.
    kind = {
        str(q.get("id")): q.get("type") or ("wiring" if q.get("via") == "wiring" else "symbol")
        for q in qs
    }
    by: dict[str, dict[str, int]] = {}
    for verdict, qid in re.findall(r"^(right|MISS)\s+(?!first:)(\S+)\s", bench, re.M):
        t = by.setdefault(kind.get(qid, "symbol"), {"right": 0, "total": 0})
        t["total"] += 1
        t["right"] += verdict == "right"
    return sum(t["right"] for t in by.values()), sum(t["total"] for t in by.values()), by


def drops(current: dict, previous: dict) -> list[str]:
    if "by_type" not in previous:
        return [] if current["right"] >= previous.get("right", 0) else ["total"]
    return [
        f"{t} {current['by_type'].get(t, {}).get('right', 0)} < {p['right']}"
        for t, p in sorted(previous["by_type"].items())
        if current["by_type"].get(t, {}).get("right", 0) < p["right"]
    ]


def repo_of(source_file: str) -> str:
    top = source_file.split("/", 1)[0]
    return top if top.startswith("marola-") and "/" in source_file else "marola"


def digest(graph: dict, wiring_block: str) -> str:
    files: dict[str, dict[str, list[tuple[int, str]]]] = defaultdict(lambda: defaultdict(list))
    for n in graph.get("nodes", []):
        src, label = n.get("source_file") or "", n.get("label") or ""
        if not src or n.get("file_type", "code") != "code" or label == Path(src).name:
            continue
        line = int(re.sub(r"\D", "", str(n.get("source_location") or "")) or 0)
        files[repo_of(src)][src].append((line, label))
    out = ["# marola routing digest", "", "## Wiring", "", wiring_block.strip(), ""]
    for repo in sorted(files):
        out += [f"## {repo}", ""]
        for src in sorted(files[repo]):
            names = ", ".join(dict.fromkeys(label for _, label in sorted(files[repo][src])))
            out.append(f"- `{src}`: {names}")
        out.append("")
    text = "\n".join(out)
    if len(text) > DIGEST_CHARS:
        cut = text.rfind("\n", 0, DIGEST_CHARS)
        text = text[:cut] + "\n\n_Cut at the 25k-token cap: `route query` has the rest._"
    return text + "\n"


def wiring_block(repos_md: Path) -> str:
    text = repos_md.read_text(encoding="utf-8") if repos_md.is_file() else ""
    _, _, rest = text.partition(START)
    block, found, _ = rest.partition(END)
    return block if found else "_No wiring block found._"


def assemble(args) -> int:
    src, out = Path(args.graph_dir), Path(args.out)
    right, total, by_type = scores(Path(args.bench).read_text(), Path(args.questions))
    bench = {"right": right, "total": total, "by_type": by_type}
    if args.previous and Path(args.previous).is_file():
        prev = json.loads(Path(args.previous).read_text()).get("bench") or {}
        if lower := drops(bench, prev):
            print(f"routing_bundle: bench below the published one ({', '.join(lower)}); keeping it")
            return 1
    for name in ("graph.json", "wiring.json", "build.json"):
        p = src / name
        if not p.is_file():
            raise SystemExit(f"routing_bundle: {p} is missing")
        if p.stat().st_size > MAX_BYTES:
            raise SystemExit(
                f"routing_bundle: {name} is over {MAX_BYTES // 2**20} MB; route would refuse it"
            )
        if p.stat().st_size > WARN_BYTES:
            print(
                f"::warning::{name} is over {WARN_BYTES // 2**20} MB: time to move it out of refs/heads/ (§5.3)"
            )
    out.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src / "graph.json", out / "graph.json")
    shutil.copyfile(src / "wiring.json", out / "wiring.json")
    build = json.loads((src / "build.json").read_text())
    build |= {"graphify": args.graphify, "bench": bench}
    (out / "build.json").write_text(json.dumps(build, indent=1) + "\n")
    graph = json.loads((src / "graph.json").read_text())
    (out / "routing-digest.md").write_text(digest(graph, wiring_block(Path(args.repos_md))))
    sizes = {
        p.name: p.stat().st_size
        for p in sorted(out.iterdir())
        if p.is_file() and p.name != "manifest.json"
    }
    (out / "manifest.json").write_text(
        json.dumps({"schema": SCHEMA, "files": sizes}, indent=1) + "\n"
    )
    print(f"routing_bundle: bench {right}/{total}, {len(sizes) + 1} files in {out}")
    return 0


def self_test() -> None:
    graph = {
        "nodes": [
            {
                "label": "Swimability",
                "source_file": "marola-app/core/Swimability.scala",
                "source_location": "L12",
                "file_type": "code",
            },
            {
                "label": "score",
                "source_file": "marola-app/core/Swimability.scala",
                "source_location": "L30",
                "file_type": "code",
            },
            {
                "label": "Swimability.scala",
                "source_file": "marola-app/core/Swimability.scala",
                "file_type": "code",
            },
            {
                "label": "release",
                "source_file": "scripts/release.sh",
                "source_location": "L4",
                "file_type": "code",
            },
            {"label": "Overview", "source_file": "docs/README.md", "file_type": "document"},
        ],
        "links": [],
    }
    build = {"head": "a" * 40, "submodules": f" {'b' * 40} marola-app (heads/main)\n"}
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        src, out = d / "graph", d / "out"
        src.mkdir()
        (src / "graph.json").write_text(json.dumps(graph))
        (src / "wiring.json").write_text("{}")
        (src / "build.json").write_text(json.dumps(build))
        (d / "REPOS.md").write_text(f"intro\n{START}\n| artifact | by |\n{END}\nrest\n")
        prev = d / "prev.json"
        prev.write_text(json.dumps({"bench": {"right": 3, "total": 4}}))
        questions = d / "bench.yaml"
        questions.write_text(
            json.dumps(
                {
                    "questions": [
                        {"id": 1, "via": "query", "ask": "A", "expect": ["a"]},
                        {"id": 2, "via": "query", "ask": "B", "expect": ["b"]},
                        {"id": 3, "via": "wiring", "ask": "C", "expect": ["c"]},
                        {"id": 4, "type": "decision", "via": "why", "ask": "D", "expect": ["d"]},
                    ]
                }
            )
        )

        def bench_out(*results: str) -> Path:
            lines = ["answers from main@abc (bundle)"]
            lines += [f"{r:<5}  {i}  q 'x'" for i, r in enumerate(results, 1)]
            right = sum(r == "right" for r in results)
            lines.append(f"right first: {right}/{len(results)}")
            f = d / "bench.txt"
            f.write_text("\n".join(lines) + "\n")
            return f

        def run(bench: Path, previous: Path | None = None) -> int:
            ns = argparse.Namespace(
                graph_dir=str(src),
                out=str(out),
                bench=str(bench),
                questions=str(questions),
                graphify="0.9.84",
                previous=str(previous) if previous else None,
                repos_md=str(d / "REPOS.md"),
            )
            return assemble(ns)

        r, t, by = scores(bench_out("right", "MISS", "right", "right").read_text(), questions)
        assert (r, t) == (3, 4) and by == {
            "symbol": {"right": 1, "total": 2},
            "wiring": {"right": 1, "total": 1},
            "decision": {"right": 1, "total": 1},
        }, f"scores_per_type: {by}"
        assert run(bench_out("right", "MISS", "MISS", "right"), prev) == 1 and not out.exists(), (
            "lower_score_keeps_previous"
        )
        assert run(bench_out("right", "MISS", "right", "right"), prev) == 0, "equal_score_publishes"
        b = json.loads((out / "build.json").read_text())
        assert b["head"] == "a" * 40 and b["bench"]["right"] == 3, f"build_json: {b}"
        assert b["bench"]["by_type"]["symbol"] == {"right": 1, "total": 2}, "build_json_by_type"
        prev.write_text((out / "build.json").read_text())
        assert run(bench_out("MISS", "right", "right", "right"), prev) == 0, (
            "same_per_type_publishes"
        )
        # The total holds at 3, symbol rises to 2 and wiring falls to 0: one type cannot pay for another.
        assert run(bench_out("right", "right", "MISS", "right"), prev) == 1, "a_type_drop_fails"
        assert b["graphify"] == "0.9.84", "build_json_graphify"
        # session-check.sh reads build.json with grep, not a JSON parser.
        raw = (out / "build.json").read_text()
        assert f'"head": "{"a" * 40}"' in raw and f"{'b' * 40} marola-app " in raw, (
            "build_json_greppable"
        )
        m = json.loads((out / "manifest.json").read_text())
        assert m["schema"] == SCHEMA and set(m["files"]) == {
            "graph.json",
            "wiring.json",
            "build.json",
            "routing-digest.md",
        }, f"manifest: {m}"
        dg = (out / "routing-digest.md").read_text()
        assert "| artifact | by |" in dg, "digest_has_wiring"
        assert (
            "## marola-app" in dg
            and "`marola-app/core/Swimability.scala`: Swimability, score" in dg
        ), dg
        assert "## marola\n" in dg and "scripts/release.sh" in dg, "digest_umbrella_files"
        assert "Overview" not in dg and "Swimability.scala`: Swimability.scala" not in dg, (
            "digest_code_only"
        )
        assert run(bench_out("right", "right", "right", "right"), d / "absent.json") == 0, (
            "first_publish_has_no_previous"
        )
        m = json.loads((out / "manifest.json").read_text())
        assert "manifest.json" not in m["files"], f"manifest_never_lists_itself: {m}"
        big = {
            "nodes": [
                {"label": f"s{i}", "source_file": f"f{i}.py", "file_type": "code"}
                for i in range(9000)
            ]
        }
        assert len(digest(big, "")) <= DIGEST_CHARS + 100, "digest_capped"
        try:
            scores("no score\n", questions)
        except SystemExit:
            pass
        else:
            raise AssertionError("unparsable_bench_fails")
    print("routing_bundle self-test: ok")


def main(argv: list[str]) -> int:
    if argv[:1] == ["--self-test"]:
        self_test()
        return 0
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--graph-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--bench", required=True, help="route bench's output, a file")
    ap.add_argument("--questions", default="routing-bench.yaml")
    ap.add_argument("--previous")
    ap.add_argument("--graphify", default="")
    ap.add_argument("--repos-md", default="docs/2-Building-marola/REPOS.md")
    return assemble(ap.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
