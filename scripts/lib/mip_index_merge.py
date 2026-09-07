#!/usr/bin/env python3
"""mip_index_merge — auto-resolve the one conflict two MIP draft branches produce between them:
both append a row to the table in `docs/mips/README.md` at the same place, so git folds the two
additions into one hunk. The sibling of `req_merge.py`/`uses_merge.py`: `scripts/mip-stack.sh`
calls this after a cherry-pick fails, on `docs/mips/README.md` only — any other conflicted file
stops for a human before this module runs.

    scripts/lib/mip_index_merge.py <conflicted-README> [<more> ...]
    scripts/lib/mip_index_merge.py --self-test

Resolution rule: every non-blank line on both sides of a hunk must be an index row
(`| [MIP-NNNN](./MIP-NNNN-….md) | … |`). The merged hunk is the union of both sides' rows, one
per MIP number, sorted by number. A MIP that appears on both sides with *different* text is a
real edit (a status change, a reworded title), not a row add — that refuses, all-or-nothing, and
the script prints the human-resolve steps. Identical rows on both sides collapse to one.
"""

import argparse
import re
import sys
from pathlib import Path

ROW_RE = re.compile(r"^\|\s*\[MIP-(?P<num>\d{4})\]\(")

# One conflict hunk. Either side may be EMPTY — the common shape here: the chain has rows the
# draft never saw, the draft adds its row after them, git shows ours as nothing and theirs as the
# row (or the reverse). Each side is captured with its trailing newlines and split afterwards.
CONFLICT_RE = re.compile(
    r"<<<<<<<[^\n]*\n"
    r"(?P<ours>(?:.*\n)*?)"
    r"(?:\|\|\|\|\|\|\|[^\n]*\n(?:.*\n)*?)?"
    r"=======\n"
    r"(?P<theirs>(?:.*\n)*?)"
    r">>>>>>>[^\n]*\n?",
)


class Unresolvable(Exception):
    pass


def rows_of(lines: list[str]) -> dict[int, str]:
    out: dict[int, str] = {}
    for line in lines:
        if not line.strip():
            continue
        m = ROW_RE.match(line)
        if not m:
            raise Unresolvable(f"not a MIP index row: {line[:60]!r}")
        num = int(m.group("num"))
        if num in out and out[num] != line:
            raise Unresolvable(f"MIP-{num:04d} appears twice on one side with different text")
        out[num] = line
    return out


def resolve_hunk(ours_lines: list[str], theirs_lines: list[str]) -> tuple[list[str], list[str]]:
    ours, theirs = rows_of(ours_lines), rows_of(theirs_lines)
    log: list[str] = []
    merged: dict[int, str] = {}
    for num in sorted(set(ours) | set(theirs)):
        o, t = ours.get(num), theirs.get(num)
        if o is not None and t is not None and o != t:
            raise Unresolvable(
                f"MIP-{num:04d} is on both sides with different text — an edit, not a row add"
            )
        merged[num] = o if o is not None else t  # type: ignore[assignment]
        if (o is None) != (t is None):
            log.append(f"MIP-{num:04d}: row kept from {'ours' if o is not None else 'theirs'}")
    return [merged[n] for n in sorted(merged)], log


def merge_text(text: str) -> tuple[str, list[str]]:
    if "<<<<<<<" not in text:
        return text, []
    log: list[str] = []

    def repl(m: re.Match[str]) -> str:
        lines, hunk_log = resolve_hunk(m.group("ours").split("\n"), m.group("theirs").split("\n"))
        log.extend(hunk_log)
        return "".join(line + "\n" for line in lines)

    merged = CONFLICT_RE.sub(repl, text)
    if "<<<<<<<" in merged or ">>>>>>>" in merged:
        raise Unresolvable("leftover conflict marker after substitution — unexpected shape")
    return merged, log


def resolve_files(paths: list[Path]) -> list[str]:
    """All-or-nothing: every file must resolve, or nothing is written."""
    results: dict[Path, tuple[str, list[str]]] = {}
    for path in paths:
        try:
            results[path] = merge_text(path.read_text())
        except Unresolvable as exc:
            raise Unresolvable(f"{path}: {exc}") from exc
    all_log: list[str] = []
    for path, (merged, log) in results.items():
        path.write_text(merged)
        all_log.extend(f"{path}: {line}" for line in log)
    return all_log


# --- self-test ----------------------------------------------------------------------------------


def self_test() -> int:
    row = "| [MIP-{n:04d}](./MIP-{n:04d}-x.md) | title {n} | Draft | 2026-09-06 | S | user value | do next | — |"
    # 1. The recorded shape (2026-09-06): two drafts each appended a row after the same last row;
    #    ours carries 0023, theirs 0020 — the merge keeps both, in number order.
    adjacent = (
        row.format(n=19) + "\n"
        "<<<<<<< HEAD\n" + row.format(n=23) + "\n"
        "=======\n" + row.format(n=20) + "\n"
        ">>>>>>> 3f29f31 (MIP-0020: an Instagram account)\n"
        "\nText after the table.\n"
    )
    merged, log = merge_text(adjacent)
    lines = [ln for ln in merged.split("\n") if ln.startswith("| [MIP-")]
    assert lines == [row.format(n=19), row.format(n=20), row.format(n=23)], lines
    assert "<<<<<<<" not in merged and merged.endswith("\nText after the table.\n"), merged
    assert len(log) == 2, log

    # 2. Both sides carry the same context rows plus one addition each: identical rows collapse.
    both = (
        "<<<<<<< HEAD\n"
        + row.format(n=21)
        + "\n"
        + row.format(n=22)
        + "\n"
        + row.format(n=23)
        + "\n"
        "=======\n" + row.format(n=20) + "\n" + row.format(n=21) + "\n" + row.format(n=22) + "\n"
        ">>>>>>> theirs\n"
    )
    merged2, log2 = merge_text(both)
    assert [ln for ln in merged2.split("\n") if ln] == [
        row.format(n=n) for n in (20, 21, 22, 23)
    ], merged2
    assert len(log2) == 2, log2

    # 2b. One side empty — the chain already had rows 0021–0023, the draft appends 0024 after a
    #     context the chain no longer has, so git shows ours as nothing and theirs as the row.
    empty_side = (
        row.format(n=23)
        + "\n<<<<<<< HEAD\n=======\n"
        + row.format(n=24)
        + "\n>>>>>>> theirs\n\nafter\n"
    )
    merged_e, log_e = merge_text(empty_side)
    assert merged_e == row.format(n=23) + "\n" + row.format(n=24) + "\n\nafter\n", merged_e
    assert log_e == ["MIP-0024: row kept from theirs"], log_e

    # 3. Same MIP on both sides with different text (a status edit) -> refuse; a non-row -> refuse.
    for bad in (
        "<<<<<<< HEAD\n"
        + row.format(n=20)
        + "\n=======\n"
        + row.format(n=20).replace("Draft", "Accepted")
        + "\n>>>>>>> t\n",
        "<<<<<<< HEAD\nsome prose\n=======\n" + row.format(n=20) + "\n>>>>>>> t\n",
    ):
        try:
            merge_text(bad)
            raise AssertionError(f"expected Unresolvable for {bad[:40]!r}")
        except Unresolvable:
            pass

    # 4. resolve_files is all-or-nothing.
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        good, bad_f = d / "README.md", d / "OTHER.md"
        good.write_text(adjacent)
        bad_f.write_text("<<<<<<< HEAD\nprose\n=======\nother\n>>>>>>> t\n")
        try:
            resolve_files([good, bad_f])
            raise AssertionError("expected Unresolvable")
        except Unresolvable:
            pass
        assert good.read_text() == adjacent
        assert len(resolve_files([good])) == 2 and "<<<<<<<" not in good.read_text()

    print("mip_index_merge self-test: PASSED")
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
        print(f"mip_index_merge: cannot auto-resolve — {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
