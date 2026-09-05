#!/usr/bin/env python3
"""benchmark_gate — the promotion gate for the marola-local image (MIP-0008 §5.3, tasks decision 9).

    scripts/benchmark_gate.py check --new data --kept docs/benchmarks [--tolerance 0.05]
    scripts/benchmark_gate.py --self-test

`just benchmark` writes a Markdown report whose first table is the summary — one row per arm
(`baseline`, `rag-strict`, `rag-general`) with the coverage columns; the kept runs under
docs/benchmarks/ have the same table, several per file. The gate reads one number from each:
`rag-general` coverage (all), "the result that matters" in docs/benchmarks/2026-09-05.md.
It fails when the new run's number is more than `--tolerance` below the best kept one, or
below the new run's own `baseline` — marola must still beat the plain prompt. `--new`/`--kept`
accept a file or a directory (newest file by name). Standard library only.
"""

import argparse
import re
import sys
import tempfile
from pathlib import Path

HEADER = re.compile(r"^\|\s*arm\s*\|\s*coverage \(in-corpus\)\s*\|")
ROW = re.compile(r"^\|\s*([a-z-]+)\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)\s*\|")
METRIC_ARM = "rag-general"
PLAIN_ARM = "baseline"


def parse_tables(markdown: str) -> list[dict[str, dict[str, float]]]:
    """Every summary table in the text: arm -> {in_corpus, general, all}."""
    tables: list[dict[str, dict[str, float]]] = []
    current: dict[str, dict[str, float]] | None = None
    for line in markdown.splitlines():
        if HEADER.match(line):
            current = {}
            tables.append(current)
        elif current is not None and (m := ROW.match(line)):
            current[m.group(1)] = {
                "in_corpus": float(m.group(2)),
                "general": float(m.group(3)),
                "all": float(m.group(4)),
            }
        elif current is not None and not line.startswith("|"):
            current = None
    return [t for t in tables if t]


def newest(path: Path) -> Path:
    if path.is_dir():
        files = sorted(p for p in path.glob("*.md") if p.is_file())
        if not files:
            raise SystemExit(f"benchmark_gate: no *.md under {path}")
        return files[-1]
    return path


def metric(table: dict[str, dict[str, float]], arm: str) -> float | None:
    row = table.get(arm)
    return None if row is None else row["all"]


def check(new_md: str, kept_md: str, tolerance: float) -> tuple[bool, list[str]]:
    new_tables = parse_tables(new_md)
    kept_tables = parse_tables(kept_md)
    lines: list[str] = []
    if not new_tables:
        return False, ["no summary table in the new benchmark"]
    if not kept_tables:
        return False, ["no summary table in the kept benchmark"]
    new = new_tables[0]
    got = metric(new, METRIC_ARM)
    plain = metric(new, PLAIN_ARM)
    kept_best = max((metric(t, METRIC_ARM) or 0.0) for t in kept_tables)
    if got is None or plain is None:
        return False, [f"the new benchmark lacks a {METRIC_ARM} or {PLAIN_ARM} row"]
    ok = True
    lines.append(
        f"{METRIC_ARM} coverage (all): new {got:.2f}, best kept {kept_best:.2f}, tolerance {tolerance:.2f}"
    )
    if got < kept_best - tolerance:
        ok = False
        lines.append(f"FAIL: {got:.2f} is more than {tolerance:.2f} below the kept {kept_best:.2f}")
    lines.append(f"{PLAIN_ARM} coverage (all) in the new run: {plain:.2f}")
    if got < plain:
        ok = False
        lines.append(
            f"FAIL: {METRIC_ARM} {got:.2f} is below the plain prompt {plain:.2f} — marola no longer beats it"
        )
    lines.append("PASS" if ok else "the model is not promoted")
    return ok, lines


def self_test() -> int:
    kept_dir = Path(__file__).resolve().parent.parent / "docs" / "benchmarks"
    kept = newest(kept_dir).read_text()
    tables = parse_tables(kept)
    assert len(tables) >= 2, f"expected several runs in {kept_dir}, parsed {len(tables)}"
    best = max(metric(t, METRIC_ARM) for t in tables)
    assert abs(best - 0.84) < 1e-9, best  # run 2 of docs/benchmarks/2026-09-05.md

    good = "| arm | coverage (in-corpus) | coverage (general) | coverage (all) | cited | abstained | mean ms |\n|---|---|---|---|---|---|---|\n"
    good += "| baseline | 0.52 | 0.92 | 0.73 | 0% | 0% | 479 |\n| rag-strict | 0.67 | 0.03 | 0.32 | 41% | 55% | 322 |\n| rag-general | 0.88 | 0.79 | 0.83 | 36% | 0% | 545 |\n"
    ok, out = check(good, kept, 0.05)
    assert ok, out
    ok, out = check(good.replace("| 0.83 |", "| 0.70 |"), kept, 0.05)
    assert not ok and any("below the kept" in line for line in out), out
    ok, out = check(
        good.replace("| 0.73 |", "| 0.90 |").replace("| 0.83 |", "| 0.80 |"), kept, 0.05
    )
    assert not ok and any("plain prompt" in line for line in out), out
    ok, out = check("# nothing here\n", kept, 0.05)
    assert not ok and "no summary table" in out[0]
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        (d / "benchmark-20260901-0000.md").write_text(good.replace("| 0.83 |", "| 0.10 |"))
        (d / "benchmark-20260905-0950.md").write_text(good)
        assert newest(d).name == "benchmark-20260905-0950.md"
    print(f"benchmark_gate self-test: ok (kept best {METRIC_ARM} = {best:.2f})")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--self-test", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    chk = sub.add_parser("check")
    chk.add_argument(
        "--new", required=True, type=Path, help="a benchmark report, or the directory data/"
    )
    chk.add_argument("--kept", default=Path("docs/benchmarks"), type=Path)
    chk.add_argument("--tolerance", default=0.05, type=float)
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.cmd != "check":
        ap.print_help()
        return 2
    new_path, kept_path = newest(args.new), newest(args.kept)
    print(f"new: {new_path}\nkept: {kept_path}")
    ok, lines = check(new_path.read_text(), kept_path.read_text(), args.tolerance)
    print("\n".join(lines))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
