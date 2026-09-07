#!/usr/bin/env python3
"""repo_stats — the README's CI-health and LOC badges, as shields.io endpoint JSON.

    scripts/repo_stats.py write --out-dir stats \
        --repo h0ffmann/marola --run-id 123 --exclude-job repo-stats
    scripts/repo_stats.py write --out-dir stats     # LOC only (no --run-id: no CI badge)
    scripts/repo_stats.py --self-test               # shaping + counting rules (just quality-other)

Three files, the same shields.io "endpoint" shape ci.yml already writes for coverage
(`{"schemaVersion": 1, "label": ..., "message": ..., "color": ...}`), published to the orphan
`site-data` branch and copied into the site by site.yml, where the README reads them live:

    ci.json          "ci steps"  19/20 green
    scala-loc.json   "scala"     6,865 LOC
    python-loc.json  "python"    2,877 LOC

CI health is *step*-level, not job-level: ci.yml has three jobs but ~20 named steps, and
"build-test passed" hides which of them actually ran. Only steps that ran count — a step whose
`conclusion` is `skipped` (most of them are gated on `needs.changes.outputs.*`) or still `null`
(this job's own later steps) is left out of both numerator and denominator, so a docs-only push
reports 8/8 rather than a misleading 8/20. `--exclude-job` drops the reporting job itself, whose
steps are by definition still running while it asks.

LOC is `cloc` (flake.nix ships it; ci.yml apt-installs it on the runner), counted over the four
Scala modules and the Python trees, code lines only — blanks and comments excluded by cloc, and
`target/`, `__pycache__/`, virtualenvs and `node_modules/` excluded by path. Standard library
only; `cloc` is the one external tool and only `write` needs it.
"""

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCHEMA = 1

SCALA_PATHS = ("core", "local", "azure", "cli")
PYTHON_PATHS = ("dspy", "finetune", "scripts")
EXCLUDE_DIRS = ("target", "__pycache__", ".venv", "venv", "node_modules")

SCALA_COLOR = "DC322F"  # = the README's hand-written Scala badge
PYTHON_COLOR = "3776AB"  # = python.org's brand blue, as used by shields' own python logo

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

    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "stats"
        paths = write(out, {"ci.json": ci_badge(20, 20), "scala-loc.json": loc_badge("s", 1, "x")})
        assert [p.name for p in paths] == ["ci.json", "scala-loc.json"]
        assert json.loads((out / "ci.json").read_text())["message"] == "20/20 green"
        # Rewriting replaces rather than appends — every run publishes a whole document.
        write(out, {"ci.json": ci_badge(1, 2)})
        assert json.loads((out / "ci.json").read_text())["message"] == "1/2 green"

    print("repo_stats self-test: ok (step counting, badge shaping, cloc parsing, write)")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--self-test", action="store_true")
    sub = ap.add_subparsers(dest="cmd")
    w = sub.add_parser("write", help="write the badge JSONs into --out-dir")
    w.add_argument("--out-dir", required=True, type=Path)
    w.add_argument("--root", default=".", help="repo root the LOC paths are relative to")
    w.add_argument("--repo", default="h0ffmann/marola", help="owner/name, for the CI-health badge")
    w.add_argument("--run-id", help="workflow run to report on; omitted = LOC badges only")
    w.add_argument("--exclude-job", help="job name to leave out (the reporting job itself)")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.cmd != "write":
        ap.print_help()
        return 2
    badges = collect(args)
    for path in write(args.out_dir, badges):
        print(f"{path}: {path.read_text().strip()}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
