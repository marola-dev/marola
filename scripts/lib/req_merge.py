#!/usr/bin/env python3
"""req_merge — auto-resolve one narrow class of `git cherry-pick` conflict: two dependency-bump
commits that touch a `*requirements*.txt` file on adjacent (or the same) lines, where every line
in the conflict hunk, on both sides, is a plain `name>=x[,<y]` pin. `scripts/deps-stack.sh` calls
this after a cherry-pick fails, on the file(s) `git diff --name-only --diff-filter=U` reports
conflicted, only when every one of them matches `*requirements*.txt` — anything else stops for a
human before this module ever runs (see that script's `auto_resolve_requirements`).

    scripts/lib/req_merge.py <conflicted-file> [<conflicted-file> ...]
    scripts/lib/req_merge.py --self-test

Resolution rule, per package name appearing in a hunk: keep the side with the higher lower bound
(`>=`, compared as a numeric version tuple, not a string), including that side's own `,<upper`
marker if it has one — the *other* side's upper bound (if any) is dropped, since it belongs to a
lower, superseded pin. All-or-nothing per invocation: if any given file's conflict markers don't
reduce to this shape (a different file, a non-requirements hunk, a package on only one side, a
duplicate name on one side), nothing is written to *any* of the given files, and this exits 1 —
`deps-stack.sh` then leaves every file conflicted and prints the human-resolve instructions.
"""

import argparse
import re
import sys
from pathlib import Path

# Matches one bare-lower-bound (optionally upper-bounded) pin, the only shape dependabot's own
# requirements.txt bumps produce: "name>=1.2.3" or "name>=1.2.3,<2.0.0". No `==`, no extras, no
# comments — a line outside this shape means "not this conflict class", so the caller refuses.
REQ_LINE = re.compile(
    r"^\s*([A-Za-z0-9][A-Za-z0-9_.-]*)\s*>=\s*([0-9][0-9A-Za-z.]*)(\s*,\s*<\s*[0-9][0-9A-Za-z.]*)?\s*$"
)

# One conflict hunk: `<<<<<<< ours\n...\n[||||||| base\n...\n]=======\n...\n>>>>>>> theirs`. The
# optional diff3 base section (`|||||||`) is matched but ignored — only ours/theirs decide the
# resolution.
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


def parse_version(v: str) -> tuple[int, ...]:
    """ "1.2.3rc1" -> (1, 2, 3, 0) — numeric-only, non-digit suffixes fall back to 0 for that
    segment so a comparison never raises; good enough for "which bump is newer", not a PEP 440
    parser."""
    parts = []
    for seg in v.split("."):
        m = re.match(r"\d+", seg)
        parts.append(int(m.group()) if m else 0)
    return tuple(parts)


def parse_requirement(line: str) -> tuple[str, str, str] | None:
    """ "numpy>=1.24.0,<2.0.0" -> ("numpy", "1.24.0", ",<2.0.0"); None if the line isn't this
    exact shape."""
    m = REQ_LINE.match(line)
    if not m:
        return None
    name, lower, upper = m.group(1), m.group(2), m.group(3) or ""
    upper = re.sub(r"\s+", "", upper)  # normalize "  ,  < 2.0.0" -> ",<2.0.0"
    return name, lower, upper


def resolve_hunk(ours_lines: list[str], theirs_lines: list[str]) -> tuple[list[str], list[str]]:
    """Merge one conflict hunk's ours/theirs line lists, or raise Unresolvable. Returns the
    merged lines (ours' order) and one log line per package whose bound actually differed."""
    ours = [line for line in ours_lines if line.strip()]
    theirs = [line for line in theirs_lines if line.strip()]

    def to_map(lines: list[str]) -> dict[str, tuple[str, str]]:
        out: dict[str, tuple[str, str]] = {}
        for line in lines:
            parsed = parse_requirement(line)
            if parsed is None:
                raise Unresolvable(f"not a plain name>=version pin: {line!r}")
            name, lower, upper = parsed
            if name in out:
                raise Unresolvable(f"{name} appears twice on one side of the conflict")
            out[name] = (lower, upper)
        return out

    ours_map = to_map(ours)
    theirs_map = to_map(theirs)
    if set(ours_map) != set(theirs_map):
        only = set(ours_map) ^ set(theirs_map)
        raise Unresolvable(
            f"package(s) on only one side of the conflict: {', '.join(sorted(only))}"
        )

    order = [parse_requirement(line)[0] for line in ours]  # type: ignore[index]
    merged: list[str] = []
    log: list[str] = []
    for name in order:
        o_lower, o_upper = ours_map[name]
        t_lower, t_upper = theirs_map[name]
        if parse_version(o_lower) >= parse_version(t_lower):
            kept_lower, kept_upper, other_lower = o_lower, o_upper, t_lower
        else:
            kept_lower, kept_upper, other_lower = t_lower, t_upper, o_lower
        merged.append(f"{name}>={kept_lower}{kept_upper}")
        if o_lower != t_lower:
            log.append(f"{name}: kept >={kept_lower}{kept_upper} over >={other_lower}")
    return merged, log


def merge_text(text: str) -> tuple[str, list[str]]:
    """The whole file's text -> (merged text, log lines), or raise Unresolvable. A no-op
    (already-clean) file returns unchanged with no log lines."""
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
    (prefixed with the file), in argument order. Raises Unresolvable (naming the offending file)
    on the first file that doesn't reduce to a pure requirements-bound conflict."""
    results: dict[Path, tuple[str, list[str]]] = {}
    for path in paths:
        text = path.read_text()
        try:
            merged, log = merge_text(text)
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
    # 1. Adjacent-line conflict: two *different* packages, each bumped by a different commit, on
    #    lines close enough that git's diff3 folds them into one hunk (the reported real-world
    #    case: two dependabot PRs touching adjacent lines of finetune/requirements.txt). Each
    #    package is "changed" on one side and merely present-as-context on the other, so it still
    #    appears on both sides of the hunk with the same package-name set.
    adjacent = (
        "flask>=2.0.0\n"
        "<<<<<<< HEAD\n"
        "numpy>=1.24.0\n"
        "pandas>=1.5.0\n"
        "=======\n"
        "numpy>=1.20.0\n"
        "pandas>=1.6.0\n"
        ">>>>>>> dependabot/pip/pandas-1.6.0\n"
        "requests>=2.28.0\n"
    )
    merged, log = merge_text(adjacent)
    assert "numpy>=1.24.0" in merged, merged  # ours' bump of numpy kept (1.24.0 > 1.20.0)
    assert "pandas>=1.6.0" in merged, merged  # theirs' bump of pandas kept (1.6.0 > 1.5.0)
    assert "<<<<<<<" not in merged and ">>>>>>>" not in merged, merged
    assert len(log) == 2, log

    # 2. Same package on both sides, different bounds -> take the higher lower bound.
    same_pkg = "<<<<<<< HEAD\nnumpy>=1.20.0\n=======\nnumpy>=1.26.0\n>>>>>>> theirs\n"
    merged2, log2 = merge_text(same_pkg)
    assert merged2.strip() == "numpy>=1.26.0", merged2
    assert len(log2) == 1 and "1.26.0" in log2[0], log2

    # 3. Upper bound preserved from whichever side has the higher lower bound, and only that side.
    upper_kept = "<<<<<<< HEAD\nnumpy>=1.28.0,<2.0.0\n=======\nnumpy>=1.26.0\n>>>>>>> theirs\n"
    merged3, _ = merge_text(upper_kept)
    assert merged3.strip() == "numpy>=1.28.0,<2.0.0", merged3

    upper_dropped = "<<<<<<< HEAD\nnumpy>=1.24.0,<2.0.0\n=======\nnumpy>=1.26.0\n>>>>>>> theirs\n"
    merged4, _ = merge_text(upper_dropped)
    assert merged4.strip() == "numpy>=1.26.0", merged4  # theirs wins, and theirs has no upper

    # 4. A package on only one side -> refuse (a real dependency add/remove, not a pure bump).
    only_one_side = (
        "<<<<<<< HEAD\nnumpy>=1.24.0\nscipy>=1.10.0\n=======\nnumpy>=1.26.0\n>>>>>>> theirs\n"
    )
    try:
        merge_text(only_one_side)
        raise AssertionError("expected Unresolvable for a package on only one side")
    except Unresolvable as exc:
        assert "scipy" in str(exc), exc

    # 5. Not a requirements-shaped hunk at all -> refuse.
    not_reqs = "<<<<<<< HEAD\nsome random text\n=======\nother text\n>>>>>>> theirs\n"
    try:
        merge_text(not_reqs)
        raise AssertionError("expected Unresolvable for a non-requirements hunk")
    except Unresolvable:
        pass

    # 6. resolve_files: all-or-nothing across two files, via the filesystem.
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        good = d / "requirements.txt"
        good.write_text(same_pkg)
        bad = d / "other-requirements.txt"
        bad.write_text(not_reqs)
        try:
            resolve_files([good, bad])
            raise AssertionError("expected Unresolvable when one of two files can't resolve")
        except Unresolvable:
            pass
        # neither file was touched — atomic, all-or-nothing
        assert good.read_text() == same_pkg
        assert bad.read_text() == not_reqs

        bad.write_text(adjacent)
        log_lines = resolve_files([good, bad])
        assert good.read_text().strip() == "numpy>=1.26.0"
        assert "<<<<<<<" not in bad.read_text()
        assert len(log_lines) == 3, log_lines  # 1 from `good`, 2 from `bad`

    print("req_merge self-test: PASSED")
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
        print(f"req_merge: cannot auto-resolve — {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
