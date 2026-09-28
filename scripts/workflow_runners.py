#!/usr/bin/env python3
"""Only the GPU publish workflow may run on the self-hosted runner. MIP-0065 §5.2.

A public repo's self-hosted runner executes whatever a pull request asks it to, so this fails when
any `runs-on:` or reusable-workflow `runner:` outside marola-sea-publish.yml names `self-hosted`
or is an expression (a variable or matrix entry can be pointed at the desktop), or when that
workflow's `on:` gains a trigger a pull request can reach: pull_request(_target) or workflow_call.

    scripts/workflow_runners.py [.github/workflows]
    scripts/workflow_runners.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

GPU_WORKFLOW = "marola-sea-publish.yml"
RUNNER_KEY = re.compile(r"^(\s*)(?:-\s+)?(runs-on|runner):\s*(.*)$")
FORBIDDEN = re.compile(r"self-hosted|\$\{\{")
PR_TRIGGER = re.compile(r"\b(pull_request(?:_target)?|workflow_call)\b")
# A key or list item, so an input's `description:` that mentions pull_request is not a trigger.
PR_TRIGGER_KEY = re.compile(
    r"^\s+(?:-\s+)?['\"]?(pull_request(?:_target)?|workflow_call)['\"]?\s*(:|$)"
)


def _strip_comment(value: str) -> str:
    return value.split(" #", 1)[0].strip()


def runner_findings(name: str, text: str) -> list[str]:
    lines = text.splitlines()
    out = []
    for i, line in enumerate(lines):
        m = RUNNER_KEY.match(line)
        if not m:
            continue
        indent, value = len(m.group(1)), _strip_comment(m.group(3))
        # Block form (a list, or `group:`/`labels:`): every line indented under the key counts.
        j = i + 1
        while not m.group(3).strip() and j < len(lines):
            nxt = lines[j]
            if nxt.strip() and (len(nxt) - len(nxt.lstrip())) <= indent:
                break
            value += " " + _strip_comment(nxt)
            j += 1
        hit = FORBIDDEN.search(value)
        if hit:
            what = "an expression" if hit.group(0) == "${{" else hit.group(0)
            out.append(f"{name}:{i + 1} targets {what} — only {GPU_WORKFLOW} may be self-hosted")
    return out


def trigger_findings(name: str, text: str) -> list[str]:
    out = []
    in_on = False
    for i, line in enumerate(text.splitlines()):
        top = re.match(r"^['\"]?(\w+)['\"]?:\s*(.*)$", line)
        if top:
            in_on = top.group(1) in ("on", "true")  # YAML 1.1 reads a bare `on` as true
            hit = in_on and PR_TRIGGER.search(_strip_comment(top.group(2)))
        else:
            hit = in_on and PR_TRIGGER_KEY.match(line)
        if hit:
            out.append(f"{name}:{i + 1} adds {hit.group(1)} to the self-hosted workflow")
    return out


def scan(root: Path) -> list[str]:
    findings = []
    for f in sorted([*root.glob("*.yml"), *root.glob("*.yaml")]):
        text = f.read_text(encoding="utf-8")
        if f.name == GPU_WORKFLOW:
            findings += trigger_findings(f.name, text)
        else:
            findings += runner_findings(f.name, text)
    return findings


def check(root: Path) -> int:
    if not root.is_dir():
        print(f"workflow_runners: no such directory: {root}", file=sys.stderr)
        return 2
    findings = scan(root)
    for line in findings:
        print(f"workflow_runners: {line}", file=sys.stderr)
    if findings:
        return 1
    print(f"workflow_runners: only {GPU_WORKFLOW} is self-hosted")
    return 0


CLEAN_CI = """name: CI
on:
  push:
  pull_request:
jobs:
  changes:
    # the desktop runner is self-hosted; this job is not
    runs-on: ubuntu-latest
    steps: []
"""
GPU = """name: marola-sea publish
on:
  workflow_dispatch:
    inputs:
      preset:
        description: "not a pull_request"
jobs:
  publish:
    runs-on: [self-hosted, marola-sea]
"""


def self_test() -> int:
    import tempfile

    fails = 0

    def ok(got, want, label):
        nonlocal fails
        if got == want:
            print(f"  ok   {label}")
        else:
            fails += 1
            print(f"  FAIL {label} — got {got!r}, want {want!r}")

    def tree(ci: str, gpu: str = GPU) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "ci.yml").write_text(ci, encoding="utf-8")
            (Path(tmp) / GPU_WORKFLOW).write_text(gpu, encoding="utf-8")
            return scan(Path(tmp))

    ok(tree(CLEAN_CI), [], "a clean tree has no findings, comments and the GPU job included")
    stray = tree(CLEAN_CI.replace("ubuntu-latest", "self-hosted"))
    ok(len(stray), 1, "a stray self-hosted in ci.yml is caught")
    ok(stray[0].startswith("ci.yml:8 targets self-hosted"), True, "and named by file and line")
    ok(
        len(tree(CLEAN_CI.replace("ubuntu-latest", "${{ vars.CI_RUNNER || 'ubuntu-latest' }}"))),
        1,
        "a CI_RUNNER fallback is caught even when it falls back to a hosted label",
    )
    ok(
        len(tree(CLEAN_CI.replace("ubuntu-latest", "${{ matrix.os }}"))),
        1,
        "so is any other expression, a matrix entry included",
    )
    ok(
        len(tree(CLEAN_CI.replace("ubuntu-latest", "\n      - linux\n\n      - self-hosted"))),
        1,
        "the block-list form is read to its end, past a blank line",
    )
    ok(
        len(
            tree(
                CLEAN_CI.replace(
                    "ubuntu-latest", "\n      group: desktop\n      labels: self-hosted"
                )
            )
        ),
        1,
        "and so is the group/labels form",
    )
    reusable = "jobs:\n  ping:\n    uses: o/r/.github/workflows/p.yml@main\n    with:\n      runner: self-hosted\n"
    ok(len(tree(reusable)), 1, "a reusable workflow's runner: input counts as runs-on")
    ok(
        len(
            tree(
                CLEAN_CI,
                GPU.replace("  workflow_dispatch:", "  pull_request:\n  workflow_dispatch:"),
            )
        ),
        1,
        "pull_request added to the GPU workflow is caught",
    )
    ok(
        len(
            tree(
                CLEAN_CI,
                GPU.replace(
                    "on:\n  workflow_dispatch:", "on: [workflow_dispatch, pull_request_target]\nx:"
                ),
            )
        ),
        1,
        "and so is the inline form, pull_request_target included",
    )
    ok(
        len(
            tree(
                CLEAN_CI,
                GPU.replace("  workflow_dispatch:", "  workflow_call:\n  workflow_dispatch:"),
            )
        ),
        1,
        "workflow_call on the GPU workflow is caught: a PR workflow could `uses:` it",
    )

    if fails:
        print(f"workflow_runners self-test: {fails} failure(s)", file=sys.stderr)
        return 1
    print("workflow_runners self-test: ok")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("root", nargs="?", type=Path, default=Path(".github/workflows"))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    return self_test() if args.self_test else check(args.root)


if __name__ == "__main__":
    sys.exit(main())
