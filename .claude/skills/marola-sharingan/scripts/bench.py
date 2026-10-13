#!/usr/bin/env python3
"""Benchmark marola-sharingan with the `claude` CLI: trigger evals and output evals.

    bench.py trigger [--runs 3] [--workers 6]   does the description make Claude load the skill?
    bench.py output  [--runs 2] [--ids 1,3]     each case with the skill and without it, graded
    bench.py all                                 both; writes trigger.json, output.json and benchmark.md
    bench.py --self-test                         eval files, fixtures and checks; no model calls

Results go to --out (default ~/.cache/marola-sharingan/bench/<UTC time>). Copy the summary
into evals/BENCHMARK.md by hand. No model or effort is pinned unless --model is given, so the
numbers describe the CLI's default (marola-skills review R7).

The trigger detection follows skill-creator's run_eval.py (anthropics/skills, Apache-2.0): the
first tool call decides. This is a re-implementation, not a copy: here the real skill directory
is installed in a throwaway project instead of a generated command file.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import select
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path

SKILL_DIR = Path(__file__).resolve().parent.parent
SKILL = SKILL_DIR.name
EVALS = SKILL_DIR / "evals"
TAG_LICENSE = "SPDX-" + "License-Identifier"
TAG_COPYRIGHT = "SPDX-" + "FileCopyrightText"
GIT_ENV = {
    "GIT_AUTHOR_NAME": "Eval",
    "GIT_AUTHOR_EMAIL": "eval@example.invalid",
    "GIT_COMMITTER_NAME": "Eval",
    "GIT_COMMITTER_EMAIL": "eval@example.invalid",
    "GIT_AUTHOR_DATE": "2026-01-01T00:00:00Z",
    "GIT_COMMITTER_DATE": "2026-01-01T00:00:00Z",
}
ALLOWED = ["Read", "Glob", "Grep", "Write", "Edit", "Skill"] + [
    f"Bash({c}:*)"
    for c in (
        "git",
        "python3",
        "ls",
        "cat",
        "head",
        "find",
        "mkdir",
        "cp",
        "chmod",
        "diff",
        "sha256sum",
        "wc",
        "grep",
    )
]


# CLAUDECODE guards an interactive terminal; a nested `claude -p` is fine. The session and
# messaging variables would tie the eval run to the calling session, so they are dropped too.
DROP_ENV = {
    "CLAUDECODE",
    "CLAUDE_CODE_SESSION_ID",
    "CLAUDE_CODE_REMOTE_SESSION_ID",
    "CLAUDE_CODE_PROJECTS_SESSION",
    "CLAUDE_CODE_MESSAGING_SOCKET",
    "CLAUDE_CODE_MESSAGING_TOKEN",
}


def claude_env() -> dict:
    return {k: v for k, v in os.environ.items() if k not in DROP_ENV}


def install_skill(project: Path) -> None:
    dst = project / ".claude" / "skills" / SKILL
    shutil.copytree(SKILL_DIR, dst, ignore=shutil.ignore_patterns("evals", "__pycache__"))


def git_commit_all(repo: Path, msg: str) -> str:
    env = {**os.environ, **GIT_ENV}
    if not (repo / ".git").exists():
        subprocess.run(["git", "init", "-q", "-b", "main", str(repo)], check=True, env=env)
    subprocess.run(["git", "-C", str(repo), "add", "-A"], check=True, env=env)
    subprocess.run(
        ["git", "-C", str(repo), "commit", "-q", "--allow-empty", "-m", msg], check=True, env=env
    )
    return subprocess.run(
        ["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True, check=True
    ).stdout.strip()


# --- the throwaway destination: a small marola ------------------------------------------------

CC_BY = (
    "Attribution 4.0 International\n\nCreative Commons Corporation (eval fixture: header only)\n"
)
AGPL = "GNU AFFERO GENERAL PUBLIC LICENSE\nVersion 3, 19 November 2007\n\n(eval fixture: header only)\n"
MIT = "MIT License\n\nCopyright (c) <year> <copyright holders>\n\n(eval fixture: header only)\n"


def _annotation(paths: list[str], holder: str, licence: str) -> str:
    return (
        f'[[annotations]]\npath = {json.dumps(paths)}\nprecedence = "closest"\n'
        f'{TAG_COPYRIGHT} = "{holder}"\n{TAG_LICENSE} = "{licence}"\n\n'
    )


DEST_FILES = {
    "LICENSE": CC_BY,
    "LICENSES/CC-BY-4.0.txt": CC_BY,
    "LICENSES/AGPL-3.0-only.txt": AGPL,
    "LICENSES/MIT.txt": MIT,
    "REUSE.toml": (
        "version = 1\n\n"
        + _annotation(["**"], "2026 Example Owner", "CC-BY-4.0")
        + _annotation(
            ["scripts/**", ".github/**", "justfile", "flake.nix", "flake.lock"],
            "2026 Example Owner",
            "AGPL-3.0-only",
        )
        + "# Vendored from someone/nix-skill (MIT): its notice stays with the copy.\n"
        + _annotation([".claude/skills/nix/**"], "2026 Someone", "MIT")
    ),
    "README.md": "# dest\n\nDocs are CC BY 4.0, code is AGPL-3.0-only (REUSE.toml).\n",
    "AGENTS.md": (
        "# AGENTS.md\n\nDocs are CC BY 4.0 and code is AGPL-3.0-only; REUSE.toml says which file is "
        "which. Third-party material keeps its own licence, through a REUSE.toml annotation with the "
        "licensor's own notice.\n\n## Hard rules\n\n"
        "- An agent may only begin implementation on an issue carrying `agent-ready`. Filing an "
        "issue is a human's act: draft it, a person files it.\n"
        "- Never provision or deploy a paid cloud resource without explicit human confirmation.\n"
        "- Commits carry two trailers and nothing else: `Tested:` and "
        "`Co-Authored-By: Claude <noreply@anthropic.com>`. No session links, no generated-by "
        "banners.\n"
        "- Decisions are MIPs under `docs/MIPs/`, tasks are `docs/MIPs/MIP-NNNN.tasks.md`. There is "
        "no ADR directory.\n\n## Skills\n\n"
        "Skills are listed in `docs/3-Ways-of-working/AGENT-SKILLS.md`. A skill vendored byte for "
        "byte has a row in `.claude/skills/skills.lock` (`skills-vendor`) and its upstream `LICENSE` "
        "beside it.\n"
    ),
    "CLAUDE.md": "@AGENTS.md\n",
    "docs/3-Ways-of-working/AGENT-SKILLS.md": (
        "# Agent skills\n\n## 1. Skills across the org\n\n| Where | Skills and agents | Declared in |\n"
        "|---|---|---|\n| this repo | `nix` (Nix reference, vendored unchanged from "
        "someone/nix-skill@c9a423b, MIT) | its `.claude/skills/` |\n"
    ),
    ".claude/skills/skills.lock": json.dumps(
        {
            "version": 1,
            "skills": {
                "nix": {
                    "upstream": "someone/nix-skill",
                    "path": "",
                    "commit": "c9a423b2af83" + "0" * 28,
                    "files": {
                        "LICENSE": "2a3b6a5ae5e4be5e3b84ce33bb0b9a04ffcc6ed0",
                        "SKILL.md": "ab0c4d8a7d7b3be3a4b62b2cf0b6ad1f0a0f8d0e",
                    },
                    "license": "MIT",
                    "local_edits": None,
                    "hold": None,
                }
            },
        },
        indent=2,
    )
    + "\n",
    ".claude/skills/nix/LICENSE": "MIT License\n\nCopyright (c) 2026 Someone\n",
    ".claude/skills/nix/SKILL.md": "---\nname: nix\ndescription: Nix reference.\n---\n\n# nix\n",
    "docs/MIPs/README.md": "# MIPs\n\nOne numbered design doc per non-trivial decision.\n",
    "justfile": "default:\n    @just --list\n",
    ".github/workflows/ci.yml": "name: ci\non: [push]\njobs:\n  ok:\n    runs-on: ubuntu-latest\n    steps:\n      - run: true\n",
}


def build_workspace(root: Path, with_skill: bool) -> tuple[Path, dict]:
    acme = root / "acme"
    # ruff takes acme-lint's ruff.toml for a project root and leaves its cache there.
    shutil.copytree(EVALS / "files" / "acme", acme, ignore=shutil.ignore_patterns(".ruff_cache"))
    shas = {r.name: git_commit_all(r, f"{r.name}: initial") for r in sorted(acme.iterdir())}
    dest = root / "dest"
    for rel, text in DEST_FILES.items():
        (dest / rel).parent.mkdir(parents=True, exist_ok=True)
        (dest / rel).write_text(text)
    if with_skill:
        install_skill(dest)
    git_commit_all(dest, "dest: initial")
    return dest, shas


def run_checks(dest: Path, checks: list[dict]) -> list[dict]:
    out = []
    status = subprocess.run(
        ["git", "-C", str(dest), "status", "--porcelain"], capture_output=True, text=True
    ).stdout.strip()
    for c in checks:
        kind, path = c["type"], c.get("path", "")
        if kind == "diff_empty":
            ok, why = status == "", status or "working tree clean"
        elif kind == "exists":
            ok, why = (dest / path).exists(), path
        elif kind == "unchanged":
            r = subprocess.run(["git", "-C", str(dest), "diff", "--quiet", "HEAD", "--", path])
            ok, why = r.returncode == 0, f"{path} {'unchanged' if r.returncode == 0 else 'changed'}"
        elif kind == "contains":
            ok = c["text"] in (
                (dest / path).read_text(errors="replace") if (dest / path).is_file() else ""
            )
            why = f"{path} {'has' if ok else 'lacks'} {c['text']!r}"
        elif kind == "not_identical":
            a, b = dest / path, (dest / c["to"]).resolve()
            ok = not a.exists() or a.read_bytes() != b.read_bytes()
            why = f"{path} {'absent' if not a.exists() else 'differs' if ok else 'is a verbatim copy'}"
        else:
            ok, why = False, f"unknown check {kind}"
        out.append({"text": f"check {kind} {path}".strip(), "passed": ok, "evidence": why})
    return out


# --- running claude --------------------------------------------------------------------------


def run_claude(
    prompt: str,
    cwd: Path,
    add_dirs: list[Path],
    model: str | None,
    timeout: int,
    tools: list[str] | None = None,
) -> dict:
    cmd = [
        "claude",
        "-p",
        prompt,
        "--output-format",
        "stream-json",
        "--verbose",
        "--no-session-persistence",
        "--strict-mcp-config",
        "--permission-mode",
        "acceptEdits",
        "--disallowedTools",
        "WebFetch",
        "WebSearch",
        "Agent",
    ]
    if tools is not None:
        cmd += ["--tools", ",".join(tools) if tools else ""]
    else:
        cmd += ["--allowedTools", *ALLOWED]
    for d in add_dirs:
        cmd += ["--add-dir", str(d)]
    if model:
        cmd += ["--model", model]
    t0 = time.time()
    try:
        p = subprocess.run(
            cmd,
            cwd=cwd,
            env=claude_env(),
            capture_output=True,
            text=True,
            timeout=timeout,
            stdin=subprocess.DEVNULL,
        )
        raw = p.stdout
    except subprocess.TimeoutExpired as e:
        raw = e.stdout.decode() if isinstance(e.stdout, bytes) else (e.stdout or "")
    seconds = round(time.time() - t0, 1)
    data, tools_used = {}, []
    for line in raw.splitlines():
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        if ev.get("type") == "result":
            data = ev
        elif ev.get("type") == "assistant":
            for c in ev.get("message", {}).get("content", []):
                if c.get("type") == "tool_use":
                    tools_used.append(
                        c["name"] + (f":{c['input'].get('skill')}" if c["name"] == "Skill" else "")
                    )
    usage = data.get("usage") or {}
    return {
        "result": data.get("result", "") or ("(no result) " + raw[-2000:]),
        "skill_loaded": f"Skill:{SKILL}" in tools_used,
        "tools": tools_used,
        "seconds": seconds,
        "cost_usd": data.get("total_cost_usd"),
        "turns": data.get("num_turns"),
        "models": sorted((data.get("modelUsage") or {}).keys()),
        "output_tokens": usage.get("output_tokens"),
        "input_tokens": sum(
            usage.get(k) or 0
            for k in ("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
        ),
    }


def trigger_once(query: str, model: str | None, timeout: int) -> bool:
    with tempfile.TemporaryDirectory(prefix="sharingan-trigger-") as tmp:
        project = Path(tmp)
        install_skill(project)
        cmd = [
            "claude",
            "-p",
            query,
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--no-session-persistence",
            "--strict-mcp-config",
        ]
        if model:
            cmd += ["--model", model]
        proc = subprocess.Popen(
            cmd,
            cwd=project,
            env=claude_env(),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
        )
        buf, pending, args, t0 = "", None, "", time.time()
        try:
            while time.time() - t0 < timeout:
                if not select.select([proc.stdout], [], [], 1.0)[0]:
                    if proc.poll() is not None:
                        return False
                    continue
                chunk = os.read(proc.stdout.fileno(), 65536)
                if not chunk:
                    return False
                buf += chunk.decode(errors="replace")
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    try:
                        ev = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if ev.get("type") == "stream_event":
                        se = ev.get("event", {})
                        if (
                            se.get("type") == "content_block_start"
                            and se.get("content_block", {}).get("type") == "tool_use"
                        ):
                            name = se["content_block"].get("name", "")
                            if name not in ("Skill", "Read"):
                                return False
                            pending, args = name, ""
                        elif se.get("type") == "content_block_delta" and pending:
                            args += se.get("delta", {}).get("partial_json", "")
                            if SKILL in args:
                                return True
                        elif se.get("type") == "content_block_stop" and pending:
                            return SKILL in args
                        elif se.get("type") == "message_stop" and not pending:
                            return False
                    elif ev.get("type") == "result":
                        return False
            return False
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()


GRADER = """You grade one run of an AI coding agent against a list of expectations.
Judge only from the evidence below: the agent's final answer and the git diff of the repository it
worked in. An expectation passes only if the evidence shows it clearly; a vague or partial answer
fails. Do not use tools. Reply with JSON only, no prose, in this shape:
{{"expectations": [{{"text": "<expectation>", "passed": true, "evidence": "<short quote or reason>"}}]}}

TASK GIVEN TO THE AGENT:
{prompt}

EXPECTATIONS:
{expectations}

AGENT'S FINAL ANSWER:
{answer}

GIT STATUS AND DIFF OF THE REPOSITORY AFTER THE RUN (empty means nothing changed):
{diff}
"""


def grade(case: dict, answer: str, diff: str, model: str | None, timeout: int) -> list[dict]:
    prompt = GRADER.format(
        prompt=case["prompt"],
        answer=answer[:40000],
        diff=diff[:30000] or "(empty)",
        expectations="\n".join(f"- {e}" for e in case["expectations"]),
    )
    with tempfile.TemporaryDirectory(prefix="sharingan-grade-") as tmp:
        r = run_claude(prompt, Path(tmp), [], model, timeout, tools=[])
    m = re.search(r"\{.*\}", r["result"], re.S)
    try:
        got = json.loads(m.group(0))["expectations"] if m else []
    except (json.JSONDecodeError, KeyError):
        got = []
    if len(got) != len(case["expectations"]):
        return [
            {"text": e, "passed": False, "evidence": "grader output unusable"}
            for e in case["expectations"]
        ]
    return [
        {"text": e, "passed": bool(g.get("passed")), "evidence": g.get("evidence", "")}
        for e, g in zip(case["expectations"], got, strict=True)
    ]


def output_once(
    case: dict, with_skill: bool, run_dir: Path, model: str | None, timeout: int
) -> dict:
    with tempfile.TemporaryDirectory(prefix="sharingan-output-") as tmp:
        dest, shas = build_workspace(Path(tmp), with_skill)
        r = run_claude(case["prompt"], dest, [Path(tmp) / "acme"], model, timeout)
        subprocess.run(["git", "-C", str(dest), "add", "-A"], check=True)
        diff = subprocess.run(
            [
                "git",
                "-C",
                str(dest),
                "diff",
                "--cached",
                "--stat",
                "-p",
                "--",
                ".",
                f":!.claude/skills/{SKILL}",
            ],
            capture_output=True,
            text=True,
        ).stdout
        subprocess.run(["git", "-C", str(dest), "reset", "-q"], check=True)
        checks = run_checks(dest, case.get("checks", []))
    graded = grade(case, r["result"], diff, model, timeout)
    items = graded + checks
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "response.md").write_text(r["result"])
    (run_dir / "diff.patch").write_text(diff)
    result = {
        "eval": case["name"],
        "config": "with_skill" if with_skill else "without_skill",
        "pass_rate": round(sum(i["passed"] for i in items) / max(len(items), 1), 3),
        "passed": sum(i["passed"] for i in items),
        "total": len(items),
        "items": items,
        "upstream_commits": shas,
        **{
            k: r[k]
            for k in (
                "skill_loaded",
                "tools",
                "seconds",
                "cost_usd",
                "turns",
                "models",
                "input_tokens",
                "output_tokens",
            )
        },
    }
    (run_dir / "grading.json").write_text(json.dumps(result, indent=1))
    return result


# --- commands --------------------------------------------------------------------------------


def stats(xs: list[float]) -> str:
    xs = [x for x in xs if x is not None]
    if not xs:
        return "–"
    return (
        f"{statistics.mean(xs):.2f} ± {statistics.stdev(xs):.2f}" if len(xs) > 1 else f"{xs[0]:.2f}"
    )


def cmd_trigger(a, out: Path) -> dict:
    cases = json.loads((EVALS / "trigger-evals.json").read_text())
    jobs = [(c, i) for c in cases for i in range(a.runs)]
    with ThreadPoolExecutor(a.workers) as ex:
        hits = list(ex.map(lambda j: trigger_once(j[0]["query"], a.model, a.timeout), jobs))
    rows = []
    for c in cases:
        got = [h for (cc, _), h in zip(jobs, hits, strict=True) if cc is c]
        rate = sum(got) / len(got)
        rows.append({**c, "trigger_rate": rate, "pass": (rate >= 0.5) == c["should_trigger"]})
    res = {
        "runs": a.runs,
        "cases": rows,
        "passed": sum(r["pass"] for r in rows),
        "total": len(rows),
        "precision_recall": {
            "true_positive": sum(r["pass"] and r["should_trigger"] for r in rows),
            "false_negative": sum(not r["pass"] and r["should_trigger"] for r in rows),
            "false_positive": sum(not r["pass"] and not r["should_trigger"] for r in rows),
        },
    }
    (out / "trigger.json").write_text(json.dumps(res, indent=1))
    print(f"trigger: {res['passed']}/{res['total']} ({res['precision_recall']})", file=sys.stderr)
    return res


def cmd_output(a, out: Path) -> dict:
    cases = json.loads((EVALS / "evals.json").read_text())["evals"]
    if a.ids:
        cases = [c for c in cases if str(c["id"]) in a.ids.split(",")]
    else:
        skipped = [c["name"] for c in cases if c.get("manual")]
        cases = [c for c in cases if not c.get("manual")]
        if skipped:
            print(
                f"  not run (manual, they need the network; --ids forces one): {', '.join(skipped)}",
                file=sys.stderr,
            )
    jobs = [(c, w, i) for c in cases for w in (True, False) for i in range(a.runs)]

    def one(j):
        c, w, i = j
        d = out / c["name"] / ("with_skill" if w else "without_skill") / f"run-{i + 1}"
        r = output_once(c, w, d, a.model, a.timeout)
        print(f"  {c['name']:<20} {r['config']:<14} {r['passed']}/{r['total']}", file=sys.stderr)
        return r

    with ThreadPoolExecutor(a.workers) as ex:
        results = list(ex.map(one, jobs))
    summary = {}
    for cfg in ("with_skill", "without_skill"):
        rs = [r for r in results if r["config"] == cfg]
        summary[cfg] = {
            "pass_rate": stats([r["pass_rate"] for r in rs]),
            "skill_loaded": f"{sum(r['skill_loaded'] for r in rs)}/{len(rs)}",
            "seconds": stats([r["seconds"] for r in rs]),
            "cost_usd": stats([r["cost_usd"] for r in rs]),
            "output_tokens": stats([r["output_tokens"] for r in rs]),
            "models": sorted({m for r in rs for m in r["models"]}),
        }
    res = {"runs": a.runs, "summary": summary, "results": results}
    (out / "output.json").write_text(json.dumps(res, indent=1))
    return res


def markdown(trig: dict | None, outp: dict | None) -> str:
    lines = [
        f"Run {datetime.now(UTC):%Y-%m-%d %H:%M} UTC with `claude` "
        f"{subprocess.run(['claude', '--version'], capture_output=True, text=True).stdout.strip()}."
    ]
    if trig:
        pr = trig["precision_recall"]
        lines += [
            "",
            f"**Trigger:** {trig['passed']}/{trig['total']} queries right "
            f"({trig['runs']} run(s) each; {pr['false_negative']} missed, {pr['false_positive']} false triggers).",
            "",
            "| Query | Should | Rate |",
            "|---|---|---|",
        ]
        lines += [
            f"| {r['query']} | {'yes' if r['should_trigger'] else 'no'} | {r['trigger_rate']:.2f}"
            f"{'' if r['pass'] else ' ✗'} |"
            for r in trig["cases"]
        ]
    if outp:
        s = outp["summary"]
        lines += [
            "",
            f"**Output** ({outp['runs']} run(s) per case and configuration; "
            f"models: {', '.join(sorted(set(s['with_skill']['models'] + s['without_skill']['models']))) or 'unknown'}):",
            "",
            "| Configuration | Pass rate | Skill loaded | Seconds | Cost (USD) | Output tokens |",
            "|---|---|---|---|---|---|",
        ]
        for cfg in ("with_skill", "without_skill"):
            x = s[cfg]
            lines.append(
                f"| {cfg} | {x['pass_rate']} | {x['skill_loaded']} | {x['seconds']} | {x['cost_usd']} "
                f"| {x['output_tokens']} |"
            )
        lines += ["", "| Case | With skill | Without skill |", "|---|---|---|"]
        for name in dict.fromkeys(r["eval"] for r in outp["results"]):
            cell = {
                cfg: ", ".join(
                    f"{r['passed']}/{r['total']}"
                    for r in outp["results"]
                    if r["eval"] == name and r["config"] == cfg
                )
                for cfg in ("with_skill", "without_skill")
            }
            lines.append(f"| {name} | {cell['with_skill']} | {cell['without_skill']} |")
        fails = [
            (r["eval"], i["text"])
            for r in outp["results"]
            if r["config"] == "with_skill"
            for i in r["items"]
            if not i["passed"]
        ]
        if fails:
            lines += ["", "Failed with the skill:"] + [f"- {n}: {t}" for n, t in fails]
    return "\n".join(lines) + "\n"


def self_test() -> None:
    fails = []
    ev = json.loads((EVALS / "evals.json").read_text())
    if ev.get("skill_name") != SKILL:
        fails.append("evals.json skill_name")
    ids = [e["id"] for e in ev["evals"]]
    if len(ids) != len(set(ids)):
        fails.append("duplicate eval ids")
    for e in ev["evals"]:
        for k in ("id", "name", "prompt", "expected_output", "expectations"):
            if k not in e:
                fails.append(f"eval {e.get('id')}: missing {k}")
        for f in e.get("files", []):
            if not (EVALS / f).exists():
                fails.append(f"eval {e['id']}: missing fixture {f}")
        for c in e.get("checks", []):
            if c["type"] not in ("diff_empty", "exists", "unchanged", "not_identical", "contains"):
                fails.append(f"eval {e['id']}: unknown check {c['type']}")
        if not e.get("manual") and not e.get("checks"):
            fails.append(f"eval {e['id']}: a case bench.py runs needs at least one code check")
    trig = json.loads((EVALS / "trigger-evals.json").read_text())
    pos = sum(t["should_trigger"] for t in trig)
    if not (pos and len(trig) - pos):
        fails.append("trigger set needs positive and negative queries")
    if len({t["query"] for t in trig}) != len(trig):
        fails.append("duplicate trigger queries")
    with tempfile.TemporaryDirectory() as tmp:
        dest, shas = build_workspace(Path(tmp), with_skill=True)
        if set(shas) != {p.name for p in (EVALS / "files" / "acme").iterdir()}:
            fails.append("every fixture repo gets a commit")
        if not (dest / ".claude/skills" / SKILL / "SKILL.md").exists():
            fails.append("skill installed in the destination")
        if (dest / ".claude/skills" / SKILL / "evals").exists():
            fails.append("evals must not be installed with the skill")
        into = subprocess.run(
            [
                sys.executable,
                str(SKILL_DIR / "scripts" / "licence_scan.py"),
                "--dest",
                str(dest),
                "--dest-path",
                ".github/workflows/release.yml",
            ],
            capture_output=True,
            text=True,
        ).stdout
        if '"AGPL-3.0-only"' not in into:
            fails.append(
                f"the scanner reads the destination's code licence from REUSE.toml: {into!r}"
            )
        base = run_checks(
            dest,
            [
                {"type": "diff_empty"},
                {"type": "unchanged", "path": "REUSE.toml"},
                {"type": "contains", "path": "REUSE.toml", "text": ".claude/skills/nix"},
                {
                    "type": "not_identical",
                    "path": ".github/workflows/release.yml",
                    "to": "../acme/acme-ci/.github/workflows/release.yml",
                },
            ],
        )
        if not all(c["passed"] for c in base):
            fails.append(f"clean workspace passes its checks: {base}")
        (dest / "REUSE.toml").write_text("changed\n")
        (dest / ".github/workflows/release.yml").write_bytes(
            (dest.parent / "acme/acme-ci/.github/workflows/release.yml").read_bytes()
        )
        after = run_checks(
            dest,
            [
                {"type": "diff_empty"},
                {"type": "unchanged", "path": "REUSE.toml"},
                {"type": "contains", "path": "REUSE.toml", "text": ".claude/skills/nix"},
                {
                    "type": "not_identical",
                    "path": ".github/workflows/release.yml",
                    "to": "../acme/acme-ci/.github/workflows/release.yml",
                },
            ],
        )
        if any(c["passed"] for c in after):
            fails.append(f"changed workspace fails its checks: {after}")
    if fails:
        print("self-test FAILED:\n  " + "\n  ".join(fails), file=sys.stderr)
        sys.exit(1)
    print("bench self-test: ok")


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("mode", nargs="?", choices=["trigger", "output", "all"])
    ap.add_argument("--runs", type=int, default=None)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--timeout", type=int, default=None, help="seconds per claude call")
    ap.add_argument("--ids", help="output eval ids, comma-separated")
    ap.add_argument("--model", help="default: the CLI's own default")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    if not a.mode:
        ap.error("give trigger, output or all")
    if not shutil.which("claude"):
        sys.exit("the claude CLI is not on PATH")
    out = a.out or Path.home() / ".cache" / SKILL / "bench" / datetime.now(UTC).strftime(
        "%Y%m%dT%H%M%SZ"
    )
    out.mkdir(parents=True, exist_ok=True)
    trig = outp = None
    if a.mode in ("trigger", "all"):
        b = argparse.Namespace(**{**vars(a), "runs": a.runs or 3, "timeout": a.timeout or 90})
        trig = cmd_trigger(b, out)
    if a.mode in ("output", "all"):
        b = argparse.Namespace(**{**vars(a), "runs": a.runs or 2, "timeout": a.timeout or 1200})
        outp = cmd_output(b, out)
    md = markdown(trig, outp)
    (out / "benchmark.md").write_text(md)
    print(md)
    print(f"results: {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
