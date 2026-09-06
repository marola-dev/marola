#!/usr/bin/env python3
"""uses_merge — auto-resolve one narrow class of `git cherry-pick` conflict: two GitHub Actions
bump commits that touch `uses: owner/action@vN` lines of the same workflow file on adjacent (or
the same) lines, where every line in the conflict hunk, on both sides, is a plain `uses:` step
reference. The sibling of `req_merge.py` (requirements files): `scripts/deps-stack.sh` calls this
after a cherry-pick fails, on the conflicted `.github/workflows/*.yml` files, only when every
conflicted file is one this module or `req_merge.py` accepts — anything else stops for a human
(see that script's `auto_resolve_bumps`).

    scripts/lib/uses_merge.py <conflicted-file> [<conflicted-file> ...]
    scripts/lib/uses_merge.py --self-test

The real-world shape (2026-09-06, `just deps-stack` on #137 + #138): dependabot bumped
`actions/checkout@v4 → v7` in every workflow and `hadolint/hadolint-action@v3.1.0 → v3.5.0` on
the very next line of two of them; git folds the two adjacent one-line changes into one hunk:

    <<<<<<< HEAD
          - uses: actions/checkout@v7
          - uses: hadolint/hadolint-action@v3.1.0
    =======
          - uses: actions/checkout@v4
          - uses: hadolint/hadolint-action@v3.5.0
    >>>>>>> e4f6c8f (Bump hadolint/hadolint-action from 3.1.0 to 3.5.0)

Resolution rule, line by line (both sides must have the same number of `uses:` lines, naming the
same actions in the same order): keep the higher version of each action, compared as a numeric
tuple after stripping a leading `v` — not as a string. Ours' indentation/prefix and any trailing
comment are kept. A ref that is not a version (a commit SHA, a branch name) resolves only when
both sides agree on it; otherwise this refuses. All-or-nothing per invocation: if any given
file's conflict markers don't reduce to this shape, nothing is written to *any* of the given
files and this exits 1 — `deps-stack.sh` then prints the human-resolve instructions.
"""

import argparse
import re
import sys
from pathlib import Path

# One `uses:` step line: optional list dash, `uses:`, `owner/repo[/path]@ref`, optional comment.
USES_LINE = re.compile(
    r"^(?P<prefix>\s*(?:-\s+)?uses:\s*)(?P<action>[A-Za-z0-9_.\-/]+)@(?P<ref>[^\s#]+)(?P<suffix>\s*(?:#.*)?)$"
)
VERSION_RE = re.compile(r"^v?(\d+(?:\.\d+)*)$")

# Same hunk regex as req_merge.py: an optional diff3 base section is matched and ignored.
CONFLICT_RE = re.compile(
    r"<<<<<<<[^\n]*\n"
    r"(?P<ours>.*?)\n"
    r"(?:\|\|\|\|\|\|\|[^\n]*\n.*?\n)?"
    r"=======\n"
    r"(?P<theirs>.*?)\n"
    r">>>>>>>[^\n]*",
    re.DOTALL,
)


class Unresolvable(Exception):
    pass


def parse_version(ref: str) -> tuple[int, ...] | None:
    """ "v3.5.0" -> (3, 5, 0), "v7" -> (7,), a SHA or branch name -> None."""
    m = VERSION_RE.match(ref)
    return tuple(int(p) for p in m.group(1).split(".")) if m else None


def parse_uses(line: str) -> re.Match[str]:
    m = USES_LINE.match(line)
    if not m:
        raise Unresolvable(f"not a plain `uses: owner/action@ref` line: {line!r}")
    return m


def resolve_hunk(ours_lines: list[str], theirs_lines: list[str]) -> tuple[list[str], list[str]]:
    """Merge one hunk's ours/theirs line lists, or raise Unresolvable. Returns the merged lines
    (ours' prefix/suffix, the higher ref per action) and one log line per action that differed."""
    ours = [parse_uses(line) for line in ours_lines if line.strip()]
    theirs = [parse_uses(line) for line in theirs_lines if line.strip()]
    if len(ours) != len(theirs):
        raise Unresolvable(
            f"{len(ours)} `uses:` line(s) on ours vs {len(theirs)} on theirs — a step was added or removed, not bumped"
        )
    merged: list[str] = []
    log: list[str] = []
    for o, t in zip(ours, theirs, strict=True):
        action = o.group("action")
        if action != t.group("action"):
            raise Unresolvable(
                f"different actions on the same line: {action!r} (ours) vs {t.group('action')!r} (theirs)"
            )
        o_ref, t_ref = o.group("ref"), t.group("ref")
        if o_ref == t_ref:
            kept = o_ref
        else:
            o_v, t_v = parse_version(o_ref), parse_version(t_ref)
            if o_v is None or t_v is None:
                raise Unresolvable(
                    f"{action}: non-version ref(s) differ ({o_ref!r} vs {t_ref!r}) — a SHA or branch pin, not a bump"
                )
            kept, other = (o_ref, t_ref) if o_v >= t_v else (t_ref, o_ref)
            log.append(f"{action}: kept @{kept} over @{other}")
        merged.append(f"{o.group('prefix')}{action}@{kept}{o.group('suffix')}")
    return merged, log


def merge_text(text: str) -> tuple[str, list[str]]:
    """The whole file's text -> (merged text, log lines), or raise Unresolvable."""
    if "<<<<<<<" not in text:
        return text, []
    log: list[str] = []

    def repl(m: re.Match[str]) -> str:
        ours = m.group("ours").split("\n") if m.group("ours") else []
        theirs = m.group("theirs").split("\n") if m.group("theirs") else []
        lines, hunk_log = resolve_hunk(ours, theirs)
        log.extend(hunk_log)
        return "\n".join(lines)

    merged = CONFLICT_RE.sub(repl, text)
    if "<<<<<<<" in merged or ">>>>>>>" in merged:
        raise Unresolvable("leftover conflict marker after substitution — unexpected shape")
    return merged, log


def resolve_files(paths: list[Path]) -> list[str]:
    """All-or-nothing: every file must resolve, or nothing is written. Returns the log lines
    (prefixed with the file), in argument order."""
    results: dict[Path, tuple[str, list[str]]] = {}
    for path in paths:
        try:
            merged, log = merge_text(path.read_text())
        except Unresolvable as exc:
            raise Unresolvable(f"{path}: {exc}") from exc
        results[path] = (merged, log)
    all_log: list[str] = []
    for path, (merged, log) in results.items():
        path.write_text(merged)
        all_log.extend(f"{path}: {line}" for line in log)
    return all_log


# --- self-test ----------------------------------------------------------------------------------


def self_test() -> int:
    # 1. The recorded real-world hunk: two adjacent bumps by two commits, each side carrying the
    #    other's line as unchanged context. Ours' checkout bump and theirs' hadolint bump both win.
    adjacent = (
        "    steps:\n"
        "<<<<<<< HEAD\n"
        "      - uses: actions/checkout@v7\n"
        "      - uses: hadolint/hadolint-action@v3.1.0\n"
        "=======\n"
        "      - uses: actions/checkout@v4\n"
        "      - uses: hadolint/hadolint-action@v3.5.0\n"
        ">>>>>>> e4f6c8f (Bump hadolint/hadolint-action from 3.1.0 to 3.5.0)\n"
        "        with:\n"
        "          dockerfile: Dockerfile\n"
    )
    merged, log = merge_text(adjacent)
    assert "      - uses: actions/checkout@v7\n" in merged, merged
    assert "      - uses: hadolint/hadolint-action@v3.5.0\n" in merged, merged
    assert "<<<<<<<" not in merged and ">>>>>>>" not in merged, merged
    assert merged.endswith("        with:\n          dockerfile: Dockerfile\n"), merged
    assert len(log) == 2, log  # both lines differed: ours' checkout bump, theirs' hadolint bump
    assert "checkout: kept @v7" in log[0] and "hadolint-action: kept @v3.5.0" in log[1], log

    # 2. Numeric, not string, comparison: v10 beats v9; a trailing comment on ours is kept.
    numeric = "<<<<<<< HEAD\n  uses: a/b@v9  # pinned\n=======\n  uses: a/b@v10\n>>>>>>> t\n"
    merged2, log2 = merge_text(numeric)
    assert merged2.strip() == "uses: a/b@v10  # pinned", merged2
    assert len(log2) == 1, log2

    # 3. Same ref on both sides is a no-op line, and a step that only one side has is refused.
    merged3, log3 = merge_text("<<<<<<< HEAD\n- uses: a/b@v1\n=======\n- uses: a/b@v1\n>>>>>>> t\n")
    assert merged3.strip() == "- uses: a/b@v1" and log3 == [], (merged3, log3)
    for bad in (
        "<<<<<<< HEAD\n- uses: a/b@v1\n- uses: c/d@v1\n=======\n- uses: a/b@v2\n>>>>>>> t\n",
        "<<<<<<< HEAD\n- uses: a/b@v1\n=======\n- uses: c/d@v2\n>>>>>>> t\n",
        "<<<<<<< HEAD\n- uses: a/b@abc123\n=======\n- uses: a/b@def456\n>>>>>>> t\n",
        "<<<<<<< HEAD\n  run: echo hi\n=======\n  run: echo bye\n>>>>>>> t\n",
    ):
        try:
            merge_text(bad)
            raise AssertionError(f"expected Unresolvable for {bad!r}")
        except Unresolvable:
            pass

    # 4. resolve_files: all-or-nothing across two files.
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        good, bad_f = d / "docker.yml", d / "ci.yml"
        good.write_text(adjacent)
        bad_f.write_text("<<<<<<< HEAD\n  run: echo hi\n=======\n  run: echo bye\n>>>>>>> t\n")
        try:
            resolve_files([good, bad_f])
            raise AssertionError("expected Unresolvable when one of two files can't resolve")
        except Unresolvable:
            pass
        assert good.read_text() == adjacent  # untouched
        bad_f.write_text(numeric)
        lines = resolve_files([good, bad_f])
        assert "<<<<<<<" not in good.read_text() and "<<<<<<<" not in bad_f.read_text()
        assert len(lines) == 3, lines  # 2 from docker.yml, 1 from ci.yml

    print("uses_merge self-test: PASSED")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("files", nargs="*", type=Path)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.files:
        ap.print_help()
        return 2
    try:
        for line in resolve_files(args.files):
            print(line)
    except Unresolvable as exc:
        print(f"uses_merge: cannot auto-resolve — {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
