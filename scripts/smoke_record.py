#!/usr/bin/env python3
"""smoke_record — one `marola --summarize` run, as the JSON the map shows (MIP-0008 §5.4/§5.5).

    scripts/smoke_record.py record --stdout out.txt --exit-code 0 --out-dir site-data/smoke \
        --run-id 123 --run-url https://github.com/…/actions/runs/123 \
        --model llama3.2:1b --image ghcr.io/h0ffmann/marola:jvm
    scripts/smoke_record.py --self-test        # parses scripts/fixtures/smoke-stdout-*.txt (in `just quality`)

Reads the CLI's own output — the `origin ->` line, the ranked list (`Report.line`), the
`Draft summary:` and `Reviewer (score N/100, verdict: v):` lines — and writes three files under
`--out-dir`: `runs/<run-id>.json` (everything, stdout included), `latest.json` (the same, what
app.js renders) and `history.json` (the last 50 runs, newest first, one compact entry each).
The deterministic list is the source of truth; the summary is model text and is labelled so in
the panel. A run is `ok` only when the pipeline found beaches, the model produced a draft and
the reviewer answered — anything else is recorded with its `errors`, so a broken run shows up
as broken instead of as an old panel. Standard library only.
"""

import argparse
import datetime as dt
import json
import re
import sys
import tempfile
from pathlib import Path

SCHEMA = 1
HISTORY_CAP = 50
STDOUT_CAP = 20_000

ORIGIN = re.compile(r"^origin -> lat=(-?[\d.]+), lon=(-?[\d.]+) \(radius (\d+)km, source: (.*)\)$")
RANKED = re.compile(r"^\s*(\d+)\. \[\s*(\d+)/100\] (.+?)\s+\((\d+\.\d)km\)\s+(.+?)\s+\|")
DRAFT = re.compile(r"^Draft summary: (.*)$")
REVIEW = re.compile(r"^Reviewer \(score (\d+)/100, verdict: (\w+)\): (.*)$")
WATER = re.compile(r"^water quality -> (.*)$")
ERRORS = (
    "(LLM call failed",
    "(reviewer call failed",
    "(--summarize needs",
    "(nothing to summarize",
    "No beaches found nearby",
)


def parse(stdout: str) -> dict:
    """The facts in one run's stdout; `None` where a line never appeared."""
    origin = None
    water = None
    ranked = []
    draft = None
    review = None
    errors = []
    for raw in stdout.splitlines():
        line = raw.rstrip()
        if m := ORIGIN.match(line):
            origin = {
                "lat": float(m.group(1)),
                "lon": float(m.group(2)),
                "radius_km": int(m.group(3)),
                "source": m.group(4),
            }
        elif m := WATER.match(line):
            water = m.group(1)
        elif m := RANKED.match(line):
            ranked.append(
                {
                    "rank": int(m.group(1)),
                    "score": int(m.group(2)),
                    "name": m.group(3),
                    "distance_km": float(m.group(4)),
                    "when": m.group(5),
                }
            )
        elif m := DRAFT.match(line):
            draft = m.group(1)
        elif m := REVIEW.match(line):
            review = {"score": int(m.group(1)), "verdict": m.group(2), "summary": m.group(3)}
        elif line.startswith(ERRORS):
            errors.append(line)
    return {
        "origin": origin,
        "water": water,
        "ranked": ranked[:10],
        "top_pick": ranked[0] if ranked else None,
        "draft": draft,
        "review": review,
        "errors": errors,
    }


def record(stdout: str, exit_code: int, meta: dict) -> dict:
    facts = parse(stdout)
    ok = exit_code == 0 and facts["top_pick"] is not None and facts["review"] is not None
    origin = facts.pop("origin") or {}
    return {
        "schema": SCHEMA,
        **meta,
        "lat": origin.get("lat"),
        "lon": origin.get("lon"),
        "radius_km": origin.get("radius_km"),
        "origin_source": origin.get("source"),
        "exit_code": exit_code,
        "ok": ok,
        **facts,
        "stdout": stdout[-STDOUT_CAP:],
    }


def compact(run: dict) -> dict:
    top = run.get("top_pick") or {}
    review = run.get("review") or {}
    return {
        "run_id": run["run_id"],
        "run_url": run["run_url"],
        "when": run["when"],
        "lat": run["lat"],
        "lon": run["lon"],
        "model": run["model"],
        "ok": run["ok"],
        "top_pick": {"name": top.get("name"), "score": top.get("score")} if top else None,
        "review": {"score": review.get("score"), "verdict": review.get("verdict")}
        if review
        else None,
    }


def write(out_dir: Path, run: dict) -> list[Path]:
    runs = out_dir / "runs"
    runs.mkdir(parents=True, exist_ok=True)
    history_path = out_dir / "history.json"
    history = {"schema": SCHEMA, "runs": []}
    if history_path.exists():
        try:
            history = json.loads(history_path.read_text())
        except json.JSONDecodeError:
            pass  # a corrupt history is rebuilt from this run on
    entries = [e for e in history.get("runs", []) if e.get("run_id") != run["run_id"]]
    history = {"schema": SCHEMA, "runs": ([compact(run)] + entries)[:HISTORY_CAP]}
    written = []
    for path, payload in (
        (runs / f"{run['run_id']}.json", run),
        (out_dir / "latest.json", run),
        (history_path, history),
    ):
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=1) + "\n")
        written.append(path)
    return written


def self_test() -> int:
    fixtures = sorted(Path(__file__).parent.glob("fixtures/smoke-stdout-*.txt"))
    assert fixtures, "no scripts/fixtures/smoke-stdout-*.txt"
    good = fixtures[-1].read_text()
    meta = {
        "run_id": "1",
        "run_url": "https://example.test/runs/1",
        "when": "2026-09-05T17:40:00Z",
        "model": "llama3.2:1b",
        "image": "ghcr.io/h0ffmann/marola:jvm",
    }
    r = record(good, 0, meta)
    assert r["ok"], r["errors"]
    assert r["lat"] == -27.6733 and r["lon"] == -48.47 and r["radius_km"] == 15, (
        r["lat"],
        r["lon"],
    )
    assert r["origin_source"] == "--lat/--lon flags"
    assert r["water"] == "IMA/SC"
    assert r["top_pick"]["name"] == "Praia da Joaquina", r["top_pick"]
    assert r["top_pick"]["score"] == 55 and r["top_pick"]["distance_km"] == 4.6
    assert r["top_pick"]["when"] == "Sun 6 Sep, 10:00"
    assert len(r["ranked"]) == 6 and r["ranked"][5]["name"] == "Praia do Campeche"
    assert r["draft"].startswith("Still looks good for a swim")
    assert r["review"] == {
        "score": 60,
        "verdict": "revise",
        "summary": "Still looks good for a swim in calm conditions and lower wind speeds.",
    }
    assert r["errors"] == []

    # The pipeline ran but the model call failed: numbers, no summary, not ok.
    llm_down = (
        good.split("Draft summary:")[0] + "(LLM call failed, showing numbers above only: x)\n"
    )
    r2 = record(llm_down, 0, meta)
    assert not r2["ok"] and r2["draft"] is None and r2["review"] is None
    assert r2["top_pick"]["name"] == "Praia da Joaquina" and len(r2["errors"]) == 1

    # Nothing nearby (or a crash): no top pick, not ok, the exit code kept.
    r3 = record(
        "marola :: best hour\nNo beaches found nearby, or no forecast data for tomorrow yet.\n",
        1,
        meta,
    )
    assert not r3["ok"] and r3["top_pick"] is None and r3["exit_code"] == 1 and r3["errors"]

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        for i in range(HISTORY_CAP + 2):
            write(
                out,
                record(
                    good, 0, {**meta, "run_id": str(i), "when": f"2026-09-05T{i % 24:02d}:00:00Z"}
                ),
            )
        history = json.loads((out / "history.json").read_text())
        assert len(history["runs"]) == HISTORY_CAP, len(history["runs"])
        assert history["runs"][0]["run_id"] == str(HISTORY_CAP + 1), "newest first"
        latest = json.loads((out / "latest.json").read_text())
        assert latest["run_id"] == str(HISTORY_CAP + 1) and latest["ok"]
        assert (out / "runs" / "0.json").exists()
        # Re-recording the same run id replaces its history entry instead of duplicating it.
        write(out, record(good, 0, {**meta, "run_id": str(HISTORY_CAP + 1)}))
        history = json.loads((out / "history.json").read_text())
        assert [e["run_id"] for e in history["runs"]].count(str(HISTORY_CAP + 1)) == 1
    print(f"smoke_record self-test: ok ({fixtures[-1].name})")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--self-test", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    rec = sub.add_parser("record")
    rec.add_argument("--stdout", required=True, type=Path)
    rec.add_argument("--exit-code", required=True, type=int)
    rec.add_argument("--out-dir", required=True, type=Path)
    rec.add_argument("--run-id", required=True)
    rec.add_argument("--run-url", required=True)
    rec.add_argument("--model", required=True)
    rec.add_argument("--image", required=True)
    rec.add_argument("--when", default=dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"))
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.cmd != "record":
        ap.print_help()
        return 2
    meta = {
        "run_id": args.run_id,
        "run_url": args.run_url,
        "when": args.when,
        "model": args.model,
        "image": args.image,
    }
    run = record(args.stdout.read_text(errors="replace"), args.exit_code, meta)
    for path in write(args.out_dir, run):
        print(path)
    top = run["top_pick"] or {}
    review = run["review"] or {}
    print(
        f"ok={run['ok']} top={top.get('name')} ({top.get('score')}) "
        f"review={review.get('score')}/{review.get('verdict')} errors={run['errors']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
