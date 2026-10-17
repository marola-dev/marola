#!/usr/bin/env python3
"""ruff_exclude_check — ruff.toml's extend-exclude lists every submodule path in .gitmodules.

scripts/ruff_exclude_check.py              # check ruff.toml against .gitmodules
scripts/ruff_exclude_check.py --self-test
"""

from __future__ import annotations

import argparse
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# A submodule is linted in its own repo; ruff walks into it from here unless excluded (#870).
PATH = re.compile(r"^\s*path\s*=\s*(.+?)\s*$", re.MULTILINE)


def check(gitmodules: str, ruff_toml: str) -> list[str]:
    excluded = set(tomllib.loads(ruff_toml).get("extend-exclude", []))
    return [
        f"submodule '{p}' is in .gitmodules but not in ruff.toml's extend-exclude"
        for p in PATH.findall(gitmodules)
        if p not in excluded
    ]


def self_test() -> None:
    gm = '[submodule "a"]\n\tpath = a\n\turl = https://x/a\n[submodule "b"]\n\tpath = b\n'
    assert check(gm, 'extend-exclude = [".tmp", "a", "b"]\n') == []
    assert check(gm, 'extend-exclude = ["a"]\n') == [
        "submodule 'b' is in .gitmodules but not in ruff.toml's extend-exclude"
    ]
    assert len(check(gm, "line-length = 100\n")) == 2
    assert check("", "extend-exclude = []\n") == []
    print("ruff_exclude_check self-test: ok")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    if ap.parse_args().self_test:
        self_test()
        return 0
    errors = check(
        (ROOT / ".gitmodules").read_text(encoding="utf-8"),
        (ROOT / "ruff.toml").read_text(encoding="utf-8"),
    )
    for e in errors:
        print(f"ruff_exclude_check: {e}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
