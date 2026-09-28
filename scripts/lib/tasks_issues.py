#!/usr/bin/env python3
"""tasks_issues — the pure half of `issues.sh tasks-to-issues` (MIP-0063 §5.5): read a
`docs/mips/MIP-NNNN.tasks.md` task table, say which rows already have an issue, and rewrite a
row's `#` cell into a link once one exists. Every `gh` call stays in `issues.sh`; nothing here
touches the network.

    scripts/lib/tasks_issues.py plan <tasks.md> --repo <owner/name> [--issues <issues.json>]
    scripts/lib/tasks_issues.py link <tasks.md> --repo <owner/name> --map <json> [--dry-run]
    scripts/lib/tasks_issues.py --self-test

marola's twelve task files are markdown *tables*, not the checkbox lists GitHub spec-kit rewrites
(MIP-0063 §4.7), and the `depends on` column is a DAG rather than a chain — MIP-0034 has `6 ← 1`,
MIP-0031 three roots, MIP-0060 two lettered roots. Edges come from that column and nowhere else:
inferring `k` from `k-1` would serialise work that runs in parallel.
"""

import argparse
import json
import re
import sys
from pathlib import Path

HEADER_FIRST_CELLS = ("#", "slug")
COLUMNS = 5
# The `depends on` cell's "no dependency" spellings across the twelve files: en dash, em dash,
# and the ASCII hyphen nobody has used yet but will.
NO_DEPS = {"-", "–", "—"}
LINKED_CELL_RE = re.compile(r"^\[(?P<id>[^\]]+)\]\((?P<url>[^)]*)\)$")
TASK_ID_RE = re.compile(r"^[A-Za-z0-9]+$")
MIP_FILE_RE = re.compile(r"MIP-(?P<mip>\d{4})\.tasks\.md$")
# A `depends on` token naming another MIP's task (#462), resolved by title like a filed row.
CROSS_DEP_RE = re.compile(r"^(?P<mip>\d{4})-T(?P<id>[A-Za-z0-9]+)$")


class TasksError(Exception):
    pass


def pipe_positions(line: str) -> list[int]:
    """The offsets of the pipes that actually separate cells. Two kinds do not: `\\|`, which is how
    a cell spells a literal pipe (MIP-0063's own task 5 quotes the header it parses), and one
    inside a code span, which MIP-0010 task 5 writes bare as `MAROLA_TRACES=off|mlflow`.
    """
    out: list[int] = []
    i = 0
    fence = 0  # the length of the backtick run that opened the current code span; 0 = outside one
    while i < len(line):
        c = line[i]
        if c == "\\":
            i += 2
        elif c == "`":
            j = i
            while j < len(line) and line[j] == "`":
                j += 1
            run = j - i
            if fence == 0:
                fence = run
            elif fence == run:
                fence = 0
            i = j
        else:
            if c == "|" and fence == 0:
                out.append(i)
            i += 1
    return out


def split_cells(line: str) -> list[str]:
    line = line.rstrip()
    pipes = pipe_positions(line)
    if len(pipes) < 2 or line[: pipes[0]].strip() or line[pipes[-1] + 1 :].strip():
        raise TasksError(f"not a table row: {line.strip()[:60]!r}")
    return [
        line[a + 1 : b].strip().replace("\\|", "|") for a, b in zip(pipes, pipes[1:], strict=False)
    ]


def dedup_re(mip: str, task_id: str) -> re.Pattern[str]:
    """One task's title pattern. `\\b` both ends: it keeps task 1 out of task 10's issue (§4.7)."""
    return re.compile(rf"\b{re.escape(mip)}-T{re.escape(task_id)}\b")


def parse_deps(cell: str, known: set[str], where: str, mip: str = "") -> list[str]:
    # Parenthetical commentary is prose. MIP-0011 task 11's "– (… can be built any time relative
    # to 1-10 …)" depends on nothing, and any digit-scraping read of that cell says 1 and 10.
    cell = re.sub(r"\([^()]*\)", " ", cell)
    deps: list[str] = []
    for part in cell.split(","):
        words = part.split()
        if not words:
            continue
        # The first word only: MIP-0060 writes "N merged, 1", where "merged" is a note on N.
        token = words[0].strip("`*_.;:")
        if token in NO_DEPS:
            continue
        own = CROSS_DEP_RE.match(token)
        if own and own.group("mip") == mip:
            token = own.group("id")
        if token not in known and not CROSS_DEP_RE.match(token):
            raise TasksError(
                f"{where}: `depends on` names {token!r}, which is not a row of this table"
                " nor another MIP's `NNNN-TK`"
            )
        if token not in deps:
            deps.append(token)
    return deps


def parse_table(path: Path) -> tuple[str, list[str], list[dict]]:
    """-> (mip number, the file's lines, one dict per task row)."""
    m = MIP_FILE_RE.search(path.name)
    if not m:
        raise TasksError(f"{path.name}: not a MIP-NNNN.tasks.md file")
    mip = m.group("mip")
    # split("\n"), not splitlines(): splitlines() also breaks on U+2028, U+0085 and a form feed,
    # so rejoining with "\n" would rewrite a character the file never asked us to touch.
    lines = path.read_text().split("\n")

    start = None
    for i, line in enumerate(lines):
        if not line.startswith("|"):
            continue
        try:
            cells = split_cells(line)
        except TasksError:
            continue
        if tuple(c.lower() for c in cells[:2]) == HEADER_FIRST_CELLS:
            start = i
            break
    if start is None:
        raise TasksError(f"{path}: no `| # | slug | … |` task table")
    header = split_cells(lines[start])
    if len(header) != COLUMNS:
        raise TasksError(
            f"{path}:{start + 1}: task table has {len(header)} columns, expected {COLUMNS}"
        )
    if start + 1 >= len(lines) or set(lines[start + 1].strip()) - set("|-: "):
        raise TasksError(f"{path}:{start + 2}: expected the header's `|---|` separator")

    raw: list[tuple[int, list[str]]] = []
    for i in range(start + 2, len(lines)):
        if not lines[i].startswith("|"):
            break
        cells = split_cells(lines[i])
        if len(cells) != COLUMNS:
            raise TasksError(f"{path}:{i + 1}: {len(cells)} cells, expected {COLUMNS}")
        raw.append((i, cells))
    if not raw:
        raise TasksError(f"{path}:{start + 1}: the task table has no rows")

    ids: list[str] = []
    for i, cells in raw:
        linked = LINKED_CELL_RE.match(cells[0])
        task_id = linked.group("id").strip() if linked else cells[0]
        if not TASK_ID_RE.match(task_id):
            raise TasksError(f"{path}:{i + 1}: {task_id!r} is not a task id")
        if task_id in ids:
            raise TasksError(f"{path}:{i + 1}: task {task_id} appears twice")
        ids.append(task_id)

    known = set(ids)
    rows = []
    for (i, cells), task_id in zip(raw, ids, strict=True):
        rows.append(
            {
                "id": task_id,
                "line": i,
                "linked": bool(LINKED_CELL_RE.match(cells[0])),
                "slug": cells[1],
                "delivers": cells[2],
                "tests": cells[3],
                "deps": parse_deps(cells[4], known, f"{path}:{i + 1}", mip),
            }
        )
    return mip, lines, rows


def title_of(mip: str, row: dict) -> str:
    return f"{mip}-T{row['id']}: {row['slug']}"


def repo_path(path: Path) -> str:
    # issues.sh hands over an absolute path, and one of those in a blob URL is a 404 nobody clicks.
    root = Path(__file__).resolve().parents[2]
    try:
        return path.resolve().relative_to(root).as_posix()
    except ValueError:
        return f"docs/mips/{path.name}"


def dep_label(mip: str, dep: str, cross: dict[str, int]) -> str:
    return f"`{dep}` (#{cross[dep]})" if dep in cross else f"`{mip}-T{dep}`"


def body_of(mip: str, path: Path, repo: str, row: dict, total: int, cross: dict[str, int]) -> str:
    # The heading spellings are §5.4's, so a filed row is something `issues.sh ready` can read
    # rather than one that fails rules 1 and 2 on shape alone. What the row cannot supply —
    # area/layer/size — stays a human's, which is what keeps this command clear of §5.6's gate.
    url = f"https://github.com/{repo}/blob/main/{repo_path(path)}"
    deps = ", ".join(dep_label(mip, d, cross) for d in row["deps"]) or "nothing"
    return "\n".join(
        [
            "### What",
            "",
            f"{row['slug']} — task {row['id']} of {total} in MIP-{mip}.",
            "",
            "### Acceptance criteria",
            "",
            f"- [ ] {row['delivers']}",
            "",
            "### Named test",
            "",
            row["tests"],
            "",
            "---",
            "",
            f"[{path.name}]({url}) owns the plan; this issue owns the status (MIP-0063 §5.5). "
            f"Depends on {deps} — wired as native `blocked by` edges from that file's "
            "`depends on` column, never by hand.",
            "",
        ]
    )


def find_issue(mip: str, task_id: str, issues: list[dict]) -> int | None:
    pattern = dedup_re(mip, task_id)
    hits = sorted({i["number"] for i in issues if pattern.search(i.get("title", ""))})
    if len(hits) > 1:
        raise TasksError(
            f"{mip}-T{task_id} is in the title of {len(hits)} issues ({hits}) — "
            "one of them is a duplicate filing; close it before re-running"
        )
    return hits[0] if hits else None


def match_issues(mip: str, rows: list[dict], issues: list[dict]) -> dict[str, int]:
    found = {row["id"]: find_issue(mip, row["id"], issues) for row in rows}
    return {k: v for k, v in found.items() if v is not None}


def match_cross(rows: list[dict], issues: list[dict]) -> dict[str, int]:
    """Every other-MIP token -> its issue. Unfiled is an error, not a pending edge: this run
    cannot file it, so nothing is filed until it exists (the unknown-row rule, #462)."""
    out: dict[str, int] = {}
    for row in rows:
        for dep in row["deps"]:
            m = CROSS_DEP_RE.match(dep)
            if not m or dep in out:
                continue
            number = find_issue(m.group("mip"), m.group("id"), issues)
            if number is None:
                raise TasksError(f"`depends on` names {dep}, but no issue's title carries it")
            out[dep] = number
    return out


def plan(path: Path, repo: str, issues: list[dict]) -> dict:
    mip, _, rows = parse_table(path)
    filed = match_issues(mip, rows, issues)
    cross = match_cross(rows, issues)
    return {
        "mip": mip,
        "path": path.as_posix(),
        "cross": cross,
        "rows": [
            {
                **row,
                "title": title_of(mip, row),
                "body": body_of(mip, path, repo, row, len(rows), cross),
                "issue": filed.get(row["id"]),
            }
            for row in rows
        ],
    }


def replace_first_cell(line: str, text: str) -> str:
    pipes = pipe_positions(line)
    if len(pipes) < 2:
        raise TasksError(f"cannot rewrite a row with fewer than two cells: {line[:60]!r}")
    return line[: pipes[0] + 1] + text + line[pipes[1] :]


def link(path: Path, repo: str, mapping: dict[str, int], write: bool) -> list[str]:
    """Rewrite each unlinked `#` cell into a link to its issue. A cell that is already a link is
    left exactly as it is — including one pointing at `h0ffmann/marola`, the pre-rename spelling
    GitHub still redirects — because "already linked" is what makes a re-run a no-op.
    """
    mip, lines, rows = parse_table(path)
    done = []
    for row in rows:
        number = mapping.get(row["id"])
        if row["linked"] or number is None:
            continue
        cell = f" [{row['id']}](https://github.com/{repo}/issues/{number}) "
        lines[row["line"]] = replace_first_cell(lines[row["line"]], cell)
        done.append(f"{mip}-T{row['id']} -> #{number}")
    if done and write:
        path.write_text("\n".join(lines))
    return done


# --- self-test ----------------------------------------------------------------------------------


FIXTURE_HEAD = """# MIP-0034 tasks

Prose above the table.

| # | slug | delivers | tests (must exist before the PR) | depends on |
|---|---|---|---|---|
"""


def self_test() -> int:
    import tempfile

    # 0. Cell splitting. Both spellings of a pipe-inside-a-cell are in the real files, and either
    #    one read as a separator shifts every later column: `depends on` becomes prose.
    assert split_cells("| 1 | a | b | c | d |") == ["1", "a", "b", "c", "d"]
    assert split_cells(r"| 1 | a \| b | c | d | – |") == ["1", "a | b", "c", "d", "–"]
    assert split_cells("| 5 | s | `MAROLA_TRACES=off|mlflow` | t | – |") == [
        "5",
        "s",
        "`MAROLA_TRACES=off|mlflow`",
        "t",
        "–",
    ]
    assert split_cells("| 1 | ``a | b`` and `c|d` | x | y | – |")[1] == "``a | b`` and `c|d`"
    assert replace_first_cell("| 1 | `a|b` | c |", " [1](u) ") == "| [1](u) | `a|b` | c |"

    # 1. The dedup regex (§5.5). One pattern per task, `\b` at both ends.
    assert dedup_re("0063", "001").search("0063-T001")
    assert dedup_re("0063", "1").search("[0063-T1]")
    assert dedup_re("0063", "1").search("0063-T1: file a MIP's task table as issues")
    assert not dedup_re("0063", "1").search("S0063-T1")
    assert not dedup_re("0063", "001").search("0063-T0010")
    # The collision the trailing `\b` is actually for: MIP-0011 has tasks 1 and 11.
    assert not dedup_re("0011", "1").search("0011-T11: the eleventh")
    assert dedup_re("0011", "11").search("0011-T11: the eleventh")
    # And the leading one: a different MIP whose number ends in this one's.
    assert not dedup_re("0063", "1").search("10063-T1")

    # 2. The `depends on` column: dashes, one, many, and prose that must not read as numbers.
    known = {str(n) for n in range(1, 12)} | {"G", "N"}
    for cell, want in [
        ("–", []),
        ("—", []),
        ("-", []),
        ("1", ["1"]),
        ("5, 6", ["5", "6"]),
        ("2, 3", ["2", "3"]),
        ("1 (the fixture carries `wind_level`)", ["1"]),
        ("– (branches from `main`, tracing lane)", []),
        ("– (independent spike; any time relative to 1-10, numbered last)", []),
        ("G (fixture; soft — schema also read upstream)", ["G"]),
        ("N merged, 1", ["N", "1"]),
        ("1, 1", ["1"]),
        ("0064-T4", ["0064-T4"]),
        ("1, `0064-T4`", ["1", "0064-T4"]),
        ("0065-T1, 1", ["1"]),
    ]:
        got = parse_deps(cell, known, "fixture", "0065")
        assert got == want, (cell, got, want)
    try:
        parse_deps("0065-T99", known, "fixture", "0065")
        raise AssertionError("this MIP's own long form must name a row of the table")
    except TasksError:
        pass
    try:
        parse_deps("whatever comes first", known, "fixture")
        raise AssertionError("an unknown dependency should be an error, not a silent drop")
    except TasksError:
        pass

    # 3. A non-linear graph round-trips, and the row rewrite is idempotent on a linked `#` cell.
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        # MIP-0034's shape: 6 depends on 1, not on 5. Numbering is not the graph.
        f = d / "MIP-0034.tasks.md"
        f.write_text(
            FIXTURE_HEAD
            + "\n".join(
                f"| {k} | slug-{k} | delivers \\| with a pipe | test {k} | {dep} |"
                for k, dep in [
                    (1, "–"),
                    (2, "1"),
                    (3, "2"),
                    (4, "3"),
                    (5, "4"),
                    (6, "1"),
                    (7, "5, 6"),
                ]
            )
            + "\n\n## After\n"
        )
        mip, _, rows = parse_table(f)
        assert mip == "0034"
        assert [r["deps"] for r in rows] == [[], ["1"], ["2"], ["3"], ["4"], ["1"], ["5", "6"]]
        assert rows[0]["delivers"] == "delivers | with a pipe", rows[0]["delivers"]
        assert not any(r["linked"] for r in rows)

        mapping = {str(k): 500 + k for k in range(1, 8)}
        before = f.read_text()
        assert len(link(f, "marola-dev/marola", mapping, write=True)) == 7
        after = f.read_text()
        assert "| [6](https://github.com/marola-dev/marola/issues/506) |" in after, after
        # Everything outside the `#` cells is byte-for-byte what it was, trailing newline included.
        assert after.endswith("\n\n## After\n")
        assert len(after.split("\n")) == len(before.split("\n"))
        _, _, rows2 = parse_table(f)
        assert all(r["linked"] for r in rows2)
        assert [r["deps"] for r in rows2] == [r["deps"] for r in rows]
        assert [r["id"] for r in rows2] == [r["id"] for r in rows]
        # Idempotence: the second pass rewrites nothing and leaves the bytes alone.
        assert link(f, "marola-dev/marola", mapping, write=True) == []
        assert link(f, "other-owner/other", {str(k): 900 for k in range(1, 8)}, write=True) == []
        assert f.read_text() == after

        # MIP-0031's shape: three roots, two parallel branches, no row depending on its predecessor
        # by default.
        g = d / "MIP-0031.tasks.md"
        g.write_text(
            FIXTURE_HEAD.replace("0034", "0031")
            + "\n".join(
                f"| {k} | slug-{k} | d | t | {dep} |"
                for k, dep in [
                    (1, "—"),
                    (2, "1"),
                    (3, "—"),
                    (4, "—"),
                    (5, "1, 3"),
                    (6, "2, 4"),
                ]
            )
            + "\n"
        )
        _, _, grows = parse_table(g)
        assert [r["id"] for r in grows if not r["deps"]] == ["1", "3", "4"]
        assert grows[4]["deps"] == ["1", "3"] and grows[5]["deps"] == ["2", "4"]

        # 4. Dedup against a stubbed issue list: filed rows are found, the rest are None, and a
        #    double filing is refused rather than silently picking one.
        issues = [
            {"number": 414, "title": "0034-T1: slug-1"},
            {"number": 419, "title": "0034-T6: slug-6"},
            {"number": 431, "title": "CI mirrors `just quality-other` by hand"},
        ]
        _, _, rows34 = parse_table(f)
        assert match_issues("0034", rows34, issues) == {"1": 414, "6": 419}
        try:
            match_issues("0034", rows34, issues + [{"number": 500, "title": "0034-T1: again"}])
            raise AssertionError("two issues for one task id should be an error")
        except TasksError:
            pass

        # 4b. Cross-MIP tokens (#462): resolved by title, named in the body, never guessed.
        c = d / "MIP-0065.tasks.md"
        c.write_text(FIXTURE_HEAD + "| 1 | a | d | t | 0064-T4 |\n| 2 | b | d | t | 1, 0064-T4 |\n")
        other = [
            {"number": 458, "title": "0064-T4: kroki"},
            {"number": 459, "title": "0064-T40: x"},
        ]
        pc = plan(c, "marola-dev/marola", other)
        assert pc["cross"] == {"0064-T4": 458}, pc["cross"]
        assert [r["deps"] for r in pc["rows"]] == [["0064-T4"], ["1", "0064-T4"]]
        assert "Depends on `0065-T1`, `0064-T4` (#458)" in pc["rows"][1]["body"]
        for bad_issues in ([], other + [{"number": 460, "title": "0064-T4: again"}]):
            try:
                plan(c, "marola-dev/marola", bad_issues)
                raise AssertionError(f"cross-MIP token against {bad_issues} should be an error")
            except TasksError as exc:
                assert "0064-T4" in str(exc), exc

        p = plan(f, "marola-dev/marola", issues)
        assert p["rows"][0]["issue"] == 414 and p["rows"][1]["issue"] is None
        assert p["rows"][5]["title"] == "0034-T6: slug-6"
        for heading in ("### What", "### Acceptance criteria", "### Named test"):
            assert heading in p["rows"][5]["body"], heading
        # A tasks file outside the checkout must not put its absolute path in the blob URL.
        assert f"blob/main/docs/mips/{f.name}" in p["rows"][5]["body"], p["rows"][5]["body"]

        # 5. Shape errors are errors, not skipped rows.
        for name, text in [
            ("MIP-0099.tasks.md", "# no table here\n"),
            ("MIP-0099.tasks.md", FIXTURE_HEAD + "| 1 | s | d | t |\n"),
            ("MIP-0099.tasks.md", FIXTURE_HEAD + "| 1 | s | d | t | – |\n| 1 | s | d | t | – |\n"),
            ("MIP-0099.tasks.md", FIXTURE_HEAD + "| 1 2 | s | d | t | – |\n"),
            ("notes.md", FIXTURE_HEAD + "| 1 | s | d | t | – |\n"),
        ]:
            bad = d / name
            bad.write_text(text)
            try:
                parse_table(bad)
                raise AssertionError(f"expected TasksError for {name}: {text[-40:]!r}")
            except TasksError:
                pass

    # 6. The repo's own twelve task files parse, and the two graphs the MIP names are the ones
    #    that come back. A new task file in a shape this parser cannot read fails here, loudly,
    #    rather than at the first live run.
    tasks_dir = Path(__file__).resolve().parents[2] / "docs" / "mips"
    files = sorted(tasks_dir.glob("MIP-*.tasks.md"))
    assert len(files) >= 12, files
    graphs = {}
    for f in files:
        mip, _, rows = parse_table(f)
        graphs[mip] = {r["id"]: r["deps"] for r in rows}
    assert graphs["0034"]["6"] == ["1"], graphs["0034"]
    assert graphs["0034"]["7"] == ["5", "6"]
    assert [k for k, v in graphs["0031"].items() if not v] == ["1", "3", "4"]
    assert graphs["0056"]["7"] == ["4"]
    assert graphs["0063"]["4"] == ["2", "3"] and graphs["0063"]["6"] == ["4"]
    assert graphs["0011"]["11"] == [], "MIP-0011 task 11's prose names 1-10 and depends on none"
    assert graphs["0060"]["2"] == ["N", "1"], graphs["0060"]
    _, _, rows63 = parse_table(tasks_dir / "MIP-0063.tasks.md")
    assert all(r["linked"] for r in rows63), "MIP-0063's rows were linked by hand on 2026-09-27"
    assert link(tasks_dir / "MIP-0063.tasks.md", "marola-dev/marola", {"1": 1}, write=False) == []

    print("tasks_issues self-test: PASSED")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("command", nargs="?", choices=["plan", "link"])
    ap.add_argument("file", nargs="?", type=Path)
    ap.add_argument("--repo", default="", help="owner/name, for the links and the body footer")
    ap.add_argument("--issues", type=Path, help="JSON array of {number,title} to dedup against")
    ap.add_argument("--map", default="{}", help="JSON object of task id -> issue number")
    ap.add_argument("--dry-run", action="store_true", help="link: report, write nothing")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.command or not args.file:
        ap.print_help()
        return 2
    try:
        if not args.repo:
            raise TasksError("--repo owner/name is required")
        if args.command == "plan":
            issues = json.loads(args.issues.read_text()) if args.issues else []
            json.dump(plan(args.file, args.repo, issues), sys.stdout)
            print()
        else:
            for line in link(args.file, args.repo, json.loads(args.map), not args.dry_run):
                print(line)
    except TasksError as exc:
        print(f"tasks_issues: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
