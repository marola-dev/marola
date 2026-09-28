#!/usr/bin/env python3
"""repo_stats — the README's CI-health, LOC and Python-coverage badges, as shields.io endpoint JSON.

    scripts/repo_stats.py write --out-dir stats \
        --repo marola-dev/marola --run-id 123 --exclude-job repo-stats
    scripts/repo_stats.py write --out-dir stats     # LOC + Python coverage (no --run-id: no CI badge)
    scripts/repo_stats.py write --out-dir stats --no-python-coverage   # skip coverage.py
    scripts/repo_stats.py python-coverage           # just print the measured % (no files written)
    scripts/repo_stats.py --self-test               # shaping + counting rules (just quality-other)

Four files, the same shields.io "endpoint" shape ci.yml already writes for the Scala coverage
(`{"schemaVersion": 1, "label": ..., "message": ..., "color": ...}`), published to the orphan
`site-data` branch and copied into the site by site.yml, where the README reads them live:

    ci.json               "ci steps"        19/20 green
    scala-loc.json        "scala"           6,865 LOC
    python-loc.json       "python"          2,877 LOC
    python-coverage.json  "py-cov" 73%

CI health is *step*-level, not job-level: ci.yml has three jobs but ~20 named steps, and
"build-test passed" hides which of them actually ran. Only steps that ran count — a step whose
`conclusion` is `skipped` (most of them are gated on `needs.changes.outputs.*`) or still `null`
(this job's own later steps) is left out of both numerator and denominator, so a docs-only push
reports 8/8 rather than a misleading 8/20. `--exclude-job` drops the reporting job itself, whose
steps are by definition still running while it asks.

LOC is `cloc` (flake.nix ships it; ci.yml apt-installs it on the runner), counted over the four
Scala modules and the Python trees, code lines only — blanks and comments excluded by cloc, and
`target/`, `__pycache__/`, virtualenvs and `node_modules/` excluded by path.

Python coverage is measured, never estimated — but read the label narrowly. marola has no pytest
suite; every `scripts/**/*.py` is tested by its own `--self-test` flag, the list `just
quality-other` runs. So this badge is *statement coverage of `scripts/` while those self-tests
run*, and nothing more: a branch a self-test never bothers to call is uncovered by construction,
which is why the figure sits in the 70s rather than the 90s. `dspy/` and `finetune/` are out of
scope — no `--self-test` entry point, and importing them needs torch/DSPy — so they are neither
numerator nor denominator, while a `scripts/*.py` that grows without a self-test does count
(at 0%), which is the point. Mechanically: one `coverage run --parallel-mode` per self-test into
a temp data file, then `coverage combine` + `coverage json`.

Standard library only; `cloc` and `coverage` are the external tools, and only `write` needs them.
"""

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCHEMA = 1

SCALA_PATHS = ("core", "local", "cli")
PYTHON_PATHS = ("dspy", "finetune", "scripts")
EXCLUDE_DIRS = ("target", "__pycache__", ".venv", "venv", "node_modules")

SCALA_COLOR = "DC322F"  # = the README's hand-written Scala badge
PYTHON_COLOR = "3776AB"  # = python.org's brand blue, as used by shields' own python logo

# Every `python3 <script> --self-test` line of justfile's `quality-other`, in its order. Keeping
# the two lists equal is what makes the badge honest: the number below is exactly what that gate
# already runs, not a second, friendlier suite. (The `.sh` self-tests in the same recipe are not
# Python and cannot contribute statements.)
SELF_TEST_SCRIPTS = (
    "scripts/smoke_record.py",
    "scripts/benchmark_gate.py",
    "scripts/cost-split.py",
    "scripts/repo_stats.py",
    "scripts/arxiv_digest.py",
    "scripts/awesome_agentic_digest.py",
    "scripts/pr_label_nlp.py",
    "scripts/lib/req_merge.py",
    "scripts/lib/uses_merge.py",
    "scripts/lib/mip_index_merge.py",
    "scripts/ocr-post.py",
    "scripts/mip_graph.py",
    "scripts/lib/tasks_issues.py",
    "scripts/strip_external_scripts.py",
    "scripts/analyze_training.py",
    "scripts/site_live_check.py",
)
# Measured tree. `dspy/`/`finetune/` are excluded on purpose — see the module docstring.
COVERAGE_SOURCE = "scripts"

# A step that reached one of these actually executed; anything else (`skipped`, `neutral`, or a
# null conclusion for a step still queued/running) is not evidence either way and is not counted.
RAN = ("success", "failure", "cancelled", "timed_out")
GREEN = ("success",)


# ---------------------------------------------------------------------------------------------
# Pure shaping — everything below self-tests without a network call or a `cloc` on PATH.


def badge(label: str, message: str, color: str) -> dict:
    """One shields.io endpoint document (https://shields.io/badges/endpoint-badge)."""
    return {"schemaVersion": SCHEMA, "label": label, "message": message, "color": color}


def count_steps(jobs: list[dict], exclude_job: str | None = None) -> tuple[int, int]:
    """(green, ran) over every step of every job of one run — skipped/unfinished steps ignored."""
    green = ran = 0
    for job in jobs:
        if exclude_job and job.get("name") == exclude_job:
            continue
        for step in job.get("steps") or []:
            conclusion = step.get("conclusion")
            if conclusion not in RAN:
                continue
            ran += 1
            if conclusion in GREEN:
                green += 1
    return green, ran


def ci_badge(green: int, ran: int) -> dict:
    """Green only when every step that ran passed — a single red step is not a rounding error."""
    if ran == 0:
        return badge("ci steps", "no data", "lightgrey")
    color = "brightgreen" if green == ran else "yellow" if green >= 0.9 * ran else "red"
    return badge("ci steps", f"{green}/{ran} green", color)


def loc_badge(label: str, code: int, color: str) -> dict:
    return badge(label, f"{code:,} LOC", color)


def parse_cloc(payload: str, language: str) -> int:
    """Code lines for one language out of `cloc --json`; 0 when it found none of that language."""
    return int(json.loads(payload).get(language, {}).get("code", 0))


def coverage_badge(percent: float | None) -> dict:
    """Statement coverage of `scripts/` under its own self-tests. Same thresholds as ci.yml's
    Scala badge (red < 50 ≤ yellow < 80 ≤ green) so the two read on one scale. The label is
    `py-cov`, paired with ci.yml's `sc-cov` — short enough that the two badges sit side by side
    without wrapping, and still distinguishable at a glance, which is the only thing the label
    has to do."""
    if percent is None:
        return badge("py-cov", "no data", "lightgrey")
    color = "red" if percent < 50 else "yellow" if percent < 80 else "green"
    return badge("py-cov", f"{percent:.0f}%", color)


def parse_coverage_json(payload: str) -> float:
    """The overall statement percentage out of `coverage json` (`totals.percent_covered`)."""
    return float(json.loads(payload)["totals"]["percent_covered"])


def coverage_run_argv(exe: list[str], script: str, data_file: Path) -> list[str]:
    """One instrumented self-test run. `--parallel-mode` keeps the ten runs from overwriting each
    other's data file; `--source` fixes the measured tree so a `scripts/*.py` that no self-test
    imports still lands in the denominator at 0% instead of vanishing from the report."""
    return [
        *exe,
        "run",
        "--parallel-mode",
        f"--data-file={data_file}",
        f"--source={COVERAGE_SOURCE}",
        script,
        "--self-test",
    ]


# ---------------------------------------------------------------------------------------------
# The two effectful sources: this run's jobs (GitHub API, via `gh`) and `cloc`.


def fetch_jobs(repo: str, run_id: str) -> list[dict]:
    """Every job of one workflow run, steps included. Needs `actions: read` on the token."""
    jobs: list[dict] = []
    page = 1
    while True:
        url = f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100&page={page}"
        out = subprocess.run(
            ["gh", "api", "-H", "Accept: application/vnd.github+json", url],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
        batch = json.loads(out).get("jobs") or []
        jobs.extend(batch)
        if len(batch) < 100:
            return jobs
        page += 1


def cloc_code(paths: tuple[str, ...], language: str, root: Path) -> int:
    """Code lines of `language` under `paths`; a path that does not exist is simply skipped."""
    if not shutil.which("cloc"):
        raise SystemExit(
            "repo_stats: `cloc` is not on PATH (nix develop has it; CI apt-installs it)"
        )
    present = [p for p in paths if (root / p).exists()]
    if not present:
        return 0
    out = subprocess.run(
        [
            "cloc",
            "--json",
            "--quiet",
            f"--exclude-dir={','.join(EXCLUDE_DIRS)}",
            f"--include-lang={language}",
            *present,
        ],
        capture_output=True,
        text=True,
        check=True,
        cwd=root,
    ).stdout
    # cloc prints nothing at all when no file of that language survived the filters.
    return parse_cloc(out, language) if out.strip() else 0


def coverage_exe(which=shutil.which, has_module=None) -> list[str]:
    """How to invoke coverage.py here, as an argv prefix.

    Two shapes, because the two places this runs install it differently: nix's
    `python3Packages.coverage` puts a wrapped `coverage` on PATH but *not* on this interpreter's
    import path, while Ubuntu's `python3-coverage` (what ci.yml apt-installs, mirroring its `cloc`
    step) does the opposite. Prefer the executable, fall back to `-m`, fail loudly if neither.
    """
    if has_module is None:

        def has_module() -> bool:
            import importlib.util

            return importlib.util.find_spec("coverage") is not None

    if which("coverage"):
        return ["coverage"]
    if has_module():
        return [sys.executable, "-m", "coverage"]
    raise SystemExit(
        "repo_stats: coverage.py is not installed (nix develop has it; CI apt-installs "
        "python3-coverage) — or pass --no-python-coverage"
    )


def python_coverage(root: Path) -> float:
    """Run every self-test under coverage.py and return the combined statement percentage.

    The data files live in a temp directory, so a run leaves no `.coverage*` behind in the repo.
    A self-test that *fails* aborts the measurement rather than quietly reporting a smaller
    number — `just quality-other` is the gate for that, and a green badge over a red self-test
    would be worse than no badge.
    """
    exe = coverage_exe()
    with tempfile.TemporaryDirectory() as tmp:
        data_file = Path(tmp) / ".coverage"
        for script in SELF_TEST_SCRIPTS:
            subprocess.run(
                coverage_run_argv(exe, script, data_file),
                cwd=root,
                check=True,
                capture_output=True,
                text=True,
            )
        subprocess.run(
            [*exe, "combine", f"--data-file={data_file}", tmp],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        )
        report = Path(tmp) / "coverage.json"
        subprocess.run(
            [*exe, "json", f"--data-file={data_file}", "-o", str(report)],
            cwd=root,
            check=True,
            capture_output=True,
            text=True,
        )
        return parse_coverage_json(report.read_text())


def write(out_dir: Path, badges: dict[str, dict]) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for name, doc in badges.items():
        path = out_dir / name
        path.write_text(json.dumps(doc) + "\n")
        written.append(path)
    return written


def collect(args) -> dict[str, dict]:
    root = Path(args.root)
    badges = {
        "scala-loc.json": loc_badge("scala", cloc_code(SCALA_PATHS, "Scala", root), SCALA_COLOR),
        "python-loc.json": loc_badge(
            "python", cloc_code(PYTHON_PATHS, "Python", root), PYTHON_COLOR
        ),
    }
    if args.run_id:
        green, ran = count_steps(fetch_jobs(args.repo, args.run_id), args.exclude_job)
        badges["ci.json"] = ci_badge(green, ran)
    if not args.no_python_coverage:
        badges["python-coverage.json"] = coverage_badge(python_coverage(root))
    return badges


# ---------------------------------------------------------------------------------------------


def self_test() -> int:
    jobs = [
        {
            "name": "build-test",
            "steps": [
                {"name": "checkout", "conclusion": "success"},
                {"name": "compile+test", "conclusion": "success"},
                {"name": "coverage", "conclusion": "skipped"},  # main-only, on a PR
            ],
        },
        {
            "name": "quality-other",
            "steps": [
                {"name": "ruff", "conclusion": "success"},
                {"name": "actionlint", "conclusion": "failure"},
                {"name": "hadolint", "conclusion": "skipped"},
                {"name": "docker compose config", "conclusion": None},  # never reached
            ],
        },
        {
            "name": "repo-stats",  # the reporting job itself: excluded, steps still running
            "steps": [
                {"name": "checkout", "conclusion": "success"},
                {"name": "badges", "conclusion": None},
            ],
        },
    ]
    assert count_steps(jobs, "repo-stats") == (3, 4), count_steps(jobs, "repo-stats")
    # Without the exclusion the reporting job's own finished steps leak in.
    assert count_steps(jobs) == (4, 5), count_steps(jobs)
    # A job with no steps at all (queued, or `steps` absent) contributes nothing, never crashes.
    assert count_steps([{"name": "changes"}, {"name": "x", "steps": None}]) == (0, 0)

    # Skipped steps stay out of the denominator: a docs-only push is 2/2, not 2/9.
    docs_only = [
        {
            "name": "quality-other",
            "steps": [{"conclusion": "success"}] * 2 + [{"conclusion": "skipped"}] * 7,
        }
    ]
    assert count_steps(docs_only) == (2, 2)
    assert ci_badge(*count_steps(docs_only))["message"] == "2/2 green"

    assert ci_badge(20, 20) == {
        "schemaVersion": 1,
        "label": "ci steps",
        "message": "20/20 green",
        "color": "brightgreen",
    }
    assert ci_badge(19, 20)["color"] == "yellow", "one red step out of twenty: not green, not red"
    assert ci_badge(17, 20)["color"] == "red"
    assert ci_badge(0, 0) == {
        "schemaVersion": 1,
        "label": "ci steps",
        "message": "no data",
        "color": "lightgrey",
    }

    assert loc_badge("scala", 6865, SCALA_COLOR)["message"] == "6,865 LOC"
    assert loc_badge("python", 0, PYTHON_COLOR)["message"] == "0 LOC"

    cloc_json = json.dumps(
        {
            "header": {"cloc_version": "2.10"},
            "Scala": {"nFiles": 84, "blank": 1050, "comment": 1673, "code": 6865},
            "SUM": {"blank": 1050, "comment": 1673, "code": 6865, "nFiles": 84},
        }
    )
    assert parse_cloc(cloc_json, "Scala") == 6865
    assert parse_cloc(cloc_json, "Python") == 0, "a language cloc did not find is 0, not an error"

    # --- Python coverage: shaping, parsing, the argv builder and how coverage.py is located.
    assert coverage_badge(73.0) == {
        "schemaVersion": 1,
        "label": "py-cov",
        "message": "73%",
        "color": "yellow",
    }
    assert coverage_badge(80.0)["color"] == "green", "the ci.yml Scala badge's own boundary"
    assert coverage_badge(49.9)["color"] == "red"
    assert coverage_badge(100.0)["message"] == "100%", "no decimals on the badge"
    assert coverage_badge(None) == {
        "schemaVersion": 1,
        "label": "py-cov",
        "message": "no data",
        "color": "lightgrey",
    }
    # Both coverage badges must be distinguishable at a glance — this is the whole reason the
    # label is not just "coverage" like ci.yml's Scala one used to be. `py-cov` here pairs with
    # `sc-cov` in ci.yml; if one is renamed the other has to follow.
    assert coverage_badge(73.0)["label"] != ci_badge(1, 1)["label"]

    cov_json = json.dumps(
        {
            "meta": {"version": "7.15.4"},
            "files": {"scripts/repo_stats.py": {"summary": {"percent_covered": 75.0}}},
            "totals": {
                "covered_lines": 1328,
                "num_statements": 1820,
                "percent_covered": 72.96703296703296,
            },
        }
    )
    assert round(parse_coverage_json(cov_json), 2) == 72.97
    assert coverage_badge(parse_coverage_json(cov_json))["message"] == "73%"

    argv = coverage_run_argv(["coverage"], "scripts/mip_graph.py", Path("/tmp/x/.coverage"))
    assert argv[:2] == ["coverage", "run"]
    assert "--parallel-mode" in argv, "ten runs into one data file need parallel mode"
    assert "--data-file=/tmp/x/.coverage" in argv, "data files stay out of the repo"
    assert f"--source={COVERAGE_SOURCE}" in argv
    assert argv[-2:] == ["scripts/mip_graph.py", "--self-test"]
    assert coverage_run_argv([sys.executable, "-m", "coverage"], "s.py", Path("d"))[1] == "-m"

    assert coverage_exe(which=lambda _: "/usr/bin/coverage") == ["coverage"], "prefer the exe"
    assert coverage_exe(which=lambda _: None, has_module=lambda: True) == [
        sys.executable,
        "-m",
        "coverage",
    ], "nix's coverage is on PATH; Ubuntu's python3-coverage is only importable"
    try:
        coverage_exe(which=lambda _: None, has_module=lambda: False)
        raise AssertionError("a missing coverage.py must fail loudly, not report 0%")
    except SystemExit as exc:
        assert "--no-python-coverage" in str(exc), str(exc)

    # The badge is only honest while this list is exactly justfile's; drift is the failure mode.
    root = Path(__file__).resolve().parent.parent
    for script in SELF_TEST_SCRIPTS:
        assert (root / script).exists(), f"{script} is in SELF_TEST_SCRIPTS but not on disk"
    justfile = (root / "justfile").read_text()
    for script in SELF_TEST_SCRIPTS:
        assert f"python3 {script} --self-test" in justfile, f"{script} left quality-other"
    in_recipe = {
        line.split()[1]
        for line in justfile.splitlines()
        if line.strip().startswith("python3 scripts/") and line.strip().endswith("--self-test")
    }
    assert in_recipe == set(SELF_TEST_SCRIPTS), sorted(in_recipe ^ set(SELF_TEST_SCRIPTS))

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "stats"
        paths = write(out, {"ci.json": ci_badge(20, 20), "scala-loc.json": loc_badge("s", 1, "x")})
        assert [p.name for p in paths] == ["ci.json", "scala-loc.json"]
        assert json.loads((out / "ci.json").read_text())["message"] == "20/20 green"
        # Rewriting replaces rather than appends — every run publishes a whole document.
        write(out, {"ci.json": ci_badge(1, 2)})
        assert json.loads((out / "ci.json").read_text())["message"] == "1/2 green"

    write_args = build_parser().parse_args(["write", "--out-dir", "x"])
    assert write_args.repo == "marola-dev/marola", write_args.repo

    print(
        "repo_stats self-test: ok (step counting, badge shaping, cloc/coverage parsing, "
        "the coverage argv + exe resolution, SELF_TEST_SCRIPTS vs. justfile, write, "
        "the --repo default)"
    )
    return 0


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--self-test", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    w = sub.add_parser("write", help="write the badge JSONs into --out-dir")
    w.add_argument("--out-dir", required=True, type=Path)
    w.add_argument("--root", default=".", help="repo root the LOC paths are relative to")
    w.add_argument(
        "--repo", default="marola-dev/marola", help="owner/name, for the CI-health badge"
    )
    w.add_argument("--run-id", help="workflow run to report on; omitted = LOC badges only")
    w.add_argument("--exclude-job", help="job name to leave out (the reporting job itself)")
    w.add_argument(
        "--no-python-coverage",
        action="store_true",
        help="skip the coverage.py run (no coverage.py installed, or LOC/CI badges only)",
    )
    c = sub.add_parser(
        "python-coverage", help="print the measured statement %% of scripts/ and exit"
    )
    c.add_argument("--root", default=".", help="repo root the self-test paths are relative to")
    return ap


def main(argv: list[str]) -> int:
    ap = build_parser()
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.cmd == "python-coverage":
        percent = python_coverage(Path(args.root))
        print(f"{percent:.1f}% ({coverage_badge(percent)['message']} on the badge)")
        return 0
    if args.cmd != "write":
        ap.print_help()
        return 2
    badges = collect(args)
    for path in write(args.out_dir, badges):
        print(f"{path}: {path.read_text().strip()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
