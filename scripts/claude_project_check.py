#!/usr/bin/env python3
"""claude_project_check — .claude/project/ parses, its routine files exist, and it leaks no id.

scripts/claude_project_check.py              # check .claude/project/
scripts/claude_project_check.py --self-test
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SNAP = ROOT / ".claude" / "project"

# The service returns the owner's e-mail, account UUIDs and session ids; none is committed.
LEAKS = re.compile(
    r"[\w.+-]+@[\w-]+\.[\w.]+"
    r"|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    r"|\b(?:session|cse|cmsg|env|chan|user)_0[0-9A-Za-z]{20,}"
)


def check(snap: Path) -> list[str]:
    errors = []
    data = json.loads((snap / "project.json").read_text(encoding="utf-8"))
    for r in data["routines"]:
        if not (snap / r["prompt"]).is_file():
            errors.append(f"{r['name']}: no {r['prompt']}")
    for p in sorted(snap.rglob("*")):
        if p.is_file() and (m := LEAKS.search(p.read_text(encoding="utf-8"))):
            errors.append(f"{p.relative_to(snap)}: {m.group(0)!r} looks like an e-mail or an id")
    return errors


def self_test() -> None:
    with tempfile.TemporaryDirectory() as d:
        snap = Path(d)
        (snap / "routines").mkdir()
        (snap / "routines" / "a.txt").write_text("List `para:marola` issues.\n", encoding="utf-8")
        routines = [{"name": "a", "trigger_id": "trig_01AbC", "prompt": "routines/a.txt"}]
        (snap / "project.json").write_text(json.dumps({"routines": routines}), encoding="utf-8")
        assert check(snap) == [], check(snap)
        routines.append({"name": "b", "prompt": "routines/b.txt"})
        (snap / "project.json").write_text(json.dumps({"routines": routines}), encoding="utf-8")
        assert check(snap) == ["b: no routines/b.txt"], check(snap)
        (snap / "routines" / "b.txt").write_text("ping someone@example.org\n", encoding="utf-8")
        assert len(check(snap)) == 1 and "e-mail" in check(snap)[0], check(snap)
        (snap / "routines" / "b.txt").write_text(
            "wakes session_01ExampleExampleExample00\n", encoding="utf-8"
        )
        assert len(check(snap)) == 1, check(snap)
    print("claude_project_check self-test: ok")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    if ap.parse_args().self_test:
        self_test()
        return 0
    errors = check(SNAP)
    for e in errors:
        print(f"claude_project_check: {e}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
