#!/usr/bin/env python3
"""routing_bundle — assemble the files ci.yml's graph job publishes to `routing-bundle` (MIP-0084 §5.3).

    scripts/routing_bundle.py --graph-dir DIR --out OUT --bench "right first: 8/8" \
        [--previous build.json] [--graphify VERSION] [--repos-md docs/2-Building-marola/REPOS.md]
    scripts/routing_bundle.py --self-test

DIR is `graph build`'s output with `wiring.json` beside it. OUT gets graph.json, wiring.json,
build.json (DIR's, plus the graphify version and the bench score), manifest.json and
routing-digest.md. Exit 1, writing nothing, when the score is below --previous's: the job then keeps
the last bundle.
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


def score(line: str) -> tuple[int, int]:
    m = re.search(r"right first: (\d+)/(\d+)", line)
    if not m:
        raise SystemExit(f"routing_bundle: no 'right first: N/T' in {line!r}")
    return int(m.group(1)), int(m.group(2))


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
    right, total = score(args.bench)
    if args.previous and Path(args.previous).is_file():
        prev = (json.loads(Path(args.previous).read_text()).get("bench") or {}).get("right", 0)
        if right < prev:
            print(
                f"routing_bundle: bench {right}/{total} is below the published {prev}; keeping it"
            )
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
    build |= {"graphify": args.graphify, "bench": {"right": right, "total": total}}
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
        prev.write_text(json.dumps({"bench": {"right": 8, "total": 8}}))

        def run(bench: str, previous: Path | None = None) -> int:
            ns = argparse.Namespace(
                graph_dir=str(src),
                out=str(out),
                bench=bench,
                graphify="0.9.84",
                previous=str(previous) if previous else None,
                repos_md=str(d / "REPOS.md"),
            )
            return assemble(ns)

        assert run("right first: 7/8", prev) == 1 and not out.exists(), "lower_score_keeps_previous"
        assert run("right first: 8/8", prev) == 0, "equal_score_publishes"
        b = json.loads((out / "build.json").read_text())
        assert b["head"] == "a" * 40 and b["bench"] == {"right": 8, "total": 8}, f"build_json: {b}"
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
        assert run("right first: 8/8", d / "absent.json") == 0, "first_publish_has_no_previous"
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
            score("no score")
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
    ap.add_argument("--bench", required=True)
    ap.add_argument("--previous")
    ap.add_argument("--graphify", default="")
    ap.add_argument("--repos-md", default="docs/2-Building-marola/REPOS.md")
    return assemble(ap.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
