#!/usr/bin/env python3
"""claude_project_check — CLAUDE_PROJECT_CONFIG.md has its sections and routines, and leaks no id.

scripts/claude_project_check.py              # check CLAUDE_PROJECT_CONFIG.md
scripts/claude_project_check.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "CLAUDE_PROJECT_CONFIG.md"

SECTIONS = (
    "1. Project",
    "2. Cloud environment",
    "3. Plugins, skills and connectors",
    "4. Routines",
    "5. Joining as a new member",
    "6. Restoring the project",
    "7. Not in this file",
    "8. Keeping it current",
)
# AGENTS.md points CLI agents away from this file; the banner says the same to whoever opens it.
BANNER = "must not read it"

# The service returns the owner's e-mail, account UUIDs and session ids; none is committed.
LEAKS = re.compile(
    r"[\w.+-]+@[\w-]+\.[\w.]+"
    r"|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    r"|\b(?:session|cse|cmsg|env|chan|user)_0[0-9A-Za-z]{20,}"
)


def check(text: str) -> list[str]:
    errors = []
    if BANNER not in text:
        errors.append(f"no banner saying a CLI agent {BANNER}")
    for s in SECTIONS:
        if f"\n## {s}\n" not in text:
            errors.append(f"no section '## {s}'")
    routines = text.split("\n## 4. Routines\n", 1)[-1].split("\n## 5.", 1)[0]
    for block in re.split(r"\n### ", routines)[1:]:
        name = block.splitlines()[0]
        if "**Schedule (cron)**" not in block:
            errors.append(f"routine '{name}': no cron")
        if "```text\n" not in block:
            errors.append(f"routine '{name}': no prompt block")
    if m := LEAKS.search(text):
        errors.append(f"{m.group(0)!r} looks like an e-mail or an id")
    return errors


def self_test() -> None:
    good = f"> An agent {BANNER}.\n" + "".join(f"\n## {s}\n\nx\n" for s in SECTIONS)
    good = good.replace(
        "## 4. Routines\n\nx\n",
        "## 4. Routines\n\n### A\n\n| **Schedule (cron)** | `5 * * * *` |\n\n```text\nhi\n```\n",
    )
    assert check(good) == [], check(good)
    assert check(good.replace(BANNER, "may read it")) == [f"no banner saying a CLI agent {BANNER}"]
    assert check(good.replace("## 6. Restoring the project", "## 6. Restore")) == [
        "no section '## 6. Restoring the project'"
    ]
    assert check(good.replace("```text\nhi\n```", "hi")) == ["routine 'A': no prompt block"]
    leaked = check(good + "ping someone@example.org\n")
    assert len(leaked) == 1 and "e-mail" in leaked[0], leaked
    assert len(check(good + "wakes session_01ExampleExampleExample00\n")) == 1
    print("claude_project_check self-test: ok")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    if ap.parse_args().self_test:
        self_test()
        return 0
    errors = check(CONFIG.read_text(encoding="utf-8"))
    for e in errors:
        print(f"claude_project_check: {e}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
