#!/usr/bin/env python3
"""tasks_issues — the pure half of `issues.sh tasks-to-issues` (MIP-0063 §5.5, MIP-0070 §5.7): read
a `docs/MIPs/MIP-NNNN.tasks.md` task table, say which rows already have an issue (and in which
repo), and rewrite a row's `#` cell into a link once one exists. Every `gh` call stays in
`issues.sh`; nothing here touches the network — including the target repo's *existence*, which is
a read-only `gh repo view` issues.sh makes before calling `plan`, not this module's business.

    scripts/lib/tasks_issues.py repos <tasks.md>
    scripts/lib/tasks_issues.py plan <tasks.md> --umbrella <owner/name> --issues <issues.json>
        [--existing <json array>] [--mip-title <text>]
    scripts/lib/tasks_issues.py link <tasks.md> --map <json> [--dry-run]
    scripts/lib/tasks_issues.py --self-test

marola's task files are markdown *tables*, not the checkbox lists GitHub spec-kit rewrites
(MIP-0063 §4.7), and the `depends on` column is a DAG rather than a chain — MIP-0034 has `6 ← 1`,
MIP-0031 three roots, MIP-0060 two lettered roots. Edges come from that column and nowhere else:
inferring `k` from `k-1` would serialise work that runs in parallel.

MIP-0070 §5.7: a row's `delivers` cell may start with `**<repo>**` (optionally ` (new)`), naming
the repo its PR lands in; a row with no prefix means the umbrella (every pre-MIP-0070 row). Once a
row is *found* — filed by an earlier run — where it actually lives always wins over a fresh
`resolved_repo` guess: a repo created after a row was already filed as a fallback must not be
"moved" by treating the newer target as authoritative (§5.7 idempotency, #562).
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
# The `delivers` cell's leading `**<repo>**`, optionally followed by ` (new)` for a repo that does
# not exist yet (MIP-0070.tasks.md's own spelling). No match means the umbrella.
REPO_PREFIX_RE = re.compile(r"^\*\*(?P<name>[^*]+)\*\*(?:\s*\(new\))?")


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


def repo_of(delivers: str) -> str | None:
    """The bare repo name a `delivers` cell's leading `**<repo>**` names, or None for a row with
    no prefix — the umbrella (§5.7). Bare: the owner is always MAROLA_UMBRELLA's, never a literal
    here, so `resolved_repo` is what turns this into `owner/name`."""
    m = REPO_PREFIX_RE.match(delivers)
    return m.group("name") if m else None


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
                "repo": repo_of(cells[2]),
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
        return f"docs/MIPs/{path.name}"


def resolved_repo(row: dict, umbrella: str, existing: set[str]) -> str:
    """What a *fresh* filing of this row would target: `owner/<repo>` when its prefix names a repo
    confirmed to exist (§5.7), else the umbrella. `plan` only uses this for a row with no issue
    yet — an already-filed row keeps wherever it actually is (see `plan`'s docstring)."""
    name = row["repo"]
    if name is None:
        return umbrella
    owner = umbrella.split("/", 1)[0]
    return f"{owner}/{name}" if name in existing else umbrella


def dep_label(mip: str, dep: str, cross: dict[str, dict], own_repo: str) -> str:
    if dep not in cross:
        return f"`{mip}-T{dep}`"
    entry = cross[dep]
    ref = (
        f"#{entry['number']}" if entry["repo"] == own_repo else f"{entry['repo']}#{entry['number']}"
    )
    return f"`{dep}` ({ref})"


def body_of(
    mip: str, path: Path, umbrella: str, row: dict, total: int, cross: dict[str, dict]
) -> str:
    # The heading spellings are §5.4's, so a filed row is something `issues.sh ready` can read
    # rather than one that fails rules 1 and 2 on shape alone. What the row cannot supply —
    # area/layer/size — stays a human's, which is what keeps this command clear of §5.6's gate.
    # The blob always lives in the umbrella — docs/MIPs/ never moves with a row's own target repo.
    url = f"https://github.com/{umbrella}/blob/main/{repo_path(path)}"
    deps = ", ".join(dep_label(mip, d, cross, row["repo"]) for d in row["deps"]) or "nothing"
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


def parent_title(mip: str, mip_title: str) -> str:
    return f"MIP-{mip}: {mip_title}" if mip_title else f"MIP-{mip}"


def parent_body(path: Path, umbrella: str, mip: str, rows: list[dict]) -> str:
    url = f"https://github.com/{umbrella}/blob/main/{repo_path(path)}"
    lines = [
        f"[{path.name}]({url}) is MIP-{mip}'s task table — one sub-issue per row, filed in the "
        "repo its PR lands in (MIP-0070 §5.7). This issue owns the cross-repo status; each row "
        "owns its own.",
        "",
    ]
    for row in rows:
        note = (
            " — filed in the umbrella; its own repo does not exist yet" if row["fallback"] else ""
        )
        lines.append(f"- {title_of(mip, row)} (`{row['repo']}`){note}")
    lines.append("")
    return "\n".join(lines)


def find_issue(mip: str, task_id: str, issues: list[dict]) -> int | None:
    pattern = dedup_re(mip, task_id)
    hits = sorted({i["number"] for i in issues if pattern.search(i.get("title", ""))})
    if len(hits) > 1:
        raise TasksError(
            f"{mip}-T{task_id} is in the title of {len(hits)} issues ({hits}) — "
            "one of them is a duplicate filing; close it before re-running"
        )
    return hits[0] if hits else None


def find_issue_multi(
    mip: str, task_id: str, issues_by_repo: dict[str, list[dict]]
) -> tuple[str | None, int | None]:
    """The same dedup as `find_issue`, over every repo this run knows about — a row already filed
    in the umbrella must still be found there even once its own repo exists (#562: a target repo
    appearing later must never look like a reason to move an already-filed row)."""
    hits = [
        (repo, n)
        for repo, issues in issues_by_repo.items()
        if (n := find_issue(mip, task_id, issues)) is not None
    ]
    if len(hits) > 1:
        raise TasksError(
            f"{mip}-T{task_id} is filed in {len(hits)} repos ({[r for r, _ in hits]}) — "
            "one of them is a duplicate filing; close it before re-running"
        )
    return hits[0] if hits else (None, None)


def find_parent_issue(mip: str, issues: list[dict]) -> int | None:
    """The MIP's own parent issue, by title *prefix* (`MIP-NNNN:` or bare `MIP-NNNN`) rather than
    an exact match on `parent_title`'s current output — so a run whose H1 lookup degrades to ""
    once (`parent_title` then returns the bare form) still finds a parent already filed with the
    full `MIP-NNNN: <title>` form, instead of filing a second one under the bare title (#562)."""
    pattern = re.compile(rf"^MIP-{re.escape(mip)}(:|$)")
    hits = sorted({i["number"] for i in issues if pattern.match(i.get("title", ""))})
    if len(hits) > 1:
        raise TasksError(
            f"MIP-{mip}'s parent is the title of {len(hits)} issues ({hits}) — "
            "one of them is a duplicate filing; close it before re-running"
        )
    return hits[0] if hits else None


def match_cross(rows: list[dict], issues_by_repo: dict[str, list[dict]]) -> dict[str, dict]:
    """Every other-MIP token -> {repo, number}. Unfiled is an error, not a pending edge: this run
    cannot file it, so nothing is filed until it exists (the unknown-row rule, #462)."""
    out: dict[str, dict] = {}
    for row in rows:
        for dep in row["deps"]:
            m = CROSS_DEP_RE.match(dep)
            if not m or dep in out:
                continue
            repo, number = find_issue_multi(m.group("mip"), m.group("id"), issues_by_repo)
            if number is None:
                raise TasksError(f"`depends on` names {dep}, but no issue's title carries it")
            out[dep] = {"repo": repo, "number": number}
    return out


def plan(
    path: Path,
    umbrella: str,
    issues_by_repo: dict[str, list[dict]],
    existing: set[str],
    mip_title_text: str = "",
) -> dict:
    """One MIP's task table projected onto §5.7's shape: a parent issue in the umbrella, and one
    sub-issue per row in the repo it actually belongs to. A row's `repo` in the result is where its
    issue *is* — found first, `resolved_repo`'s fresh guess only when nothing was found — and
    `fallback` says whether that differs from what its own `delivers` prefix names — reported,
    never silently treated as the row's real target.
    """
    mip, _, rows = parse_table(path)
    owner = umbrella.split("/", 1)[0]
    out_rows = []
    for row in rows:
        target = resolved_repo(row, umbrella, existing)
        found_repo, found_number = find_issue_multi(mip, row["id"], issues_by_repo)
        repo = found_repo if found_number is not None else target
        # Compared against what the row's own prefix names, not against the umbrella: a `**marola**`
        # row whose prefix *is* the umbrella's own bare name is never a fallback, even though its
        # resolved repo equals the umbrella too (the only way it ever could).
        named = f"{owner}/{row['repo']}" if row["repo"] is not None else None
        out_rows.append(
            {
                **row,
                "repo": repo,
                "fallback": named is not None and repo != named,
                "issue": found_number,
            }
        )
    cross = match_cross(rows, issues_by_repo)
    for row, out in zip(rows, out_rows, strict=True):
        out["title"] = title_of(mip, row)
        out["body"] = body_of(mip, path, umbrella, out, len(rows), cross)

    ptitle = parent_title(mip, mip_title_text)
    return {
        "mip": mip,
        "path": path.as_posix(),
        "umbrella": umbrella,
        "cross": cross,
        "parent": {
            "title": ptitle,
            "body": parent_body(path, umbrella, mip, out_rows),
            "issue": find_parent_issue(mip, issues_by_repo.get(umbrella, [])),
            "repo": umbrella,
        },
        "rows": out_rows,
    }


def repos_referenced(path: Path) -> list[str]:
    """The bare repo names this table's rows name at least once, in first-appearance order. What
    `issues.sh` needs before it can even ask `gh repo view` whether each one exists yet."""
    _, _, rows = parse_table(path)
    seen: list[str] = []
    for row in rows:
        name = row["repo"]
        if name is not None and name not in seen:
            seen.append(name)
    return seen


def replace_first_cell(line: str, text: str) -> str:
    pipes = pipe_positions(line)
    if len(pipes) < 2:
        raise TasksError(f"cannot rewrite a row with fewer than two cells: {line[:60]!r}")
    return line[: pipes[0] + 1] + text + line[pipes[1] :]


def link(path: Path, mapping: dict[str, dict], write: bool) -> list[str]:
    """Rewrite each unlinked `#` cell into a link to its issue. A cell that is already a link is
    left exactly as it is — including one pointing at `h0ffmann/marola`, the pre-rename spelling
    GitHub still redirects — because "already linked" is what makes a re-run a no-op. `mapping` is
    `{task id: {"repo": owner/name, "number": n}}` — a row's own repo, not one shared flag, since
    §5.7 rows in the same table can land in different repos.
    """
    mip, lines, rows = parse_table(path)
    done = []
    for row in rows:
        entry = mapping.get(row["id"])
        if row["linked"] or not entry:
            continue
        cell = f" [{row['id']}](https://github.com/{entry['repo']}/issues/{entry['number']}) "
        lines[row["line"]] = replace_first_cell(lines[row["line"]], cell)
        done.append(f"{mip}-T{row['id']} -> {entry['repo']}#{entry['number']}")
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

        mapping = {str(k): {"repo": "marola-dev/marola", "number": 500 + k} for k in range(1, 8)}
        before = f.read_text()
        assert len(link(f, mapping, write=True)) == 7
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
        assert link(f, mapping, write=True) == []
        other_mapping = {str(k): {"repo": "other-owner/other", "number": 900} for k in range(1, 8)}
        assert link(f, other_mapping, write=True) == []
        assert f.read_text() == after
        # A row filed in a different repo than the umbrella links to that repo, not a shared one.
        h = d / "MIP-0100.tasks.md"
        h.write_text(FIXTURE_HEAD.replace("0034", "0100") + "| 1 | a | d | t | – |\n")
        link(h, {"1": {"repo": "marola-dev/marola-devkit", "number": 12}}, write=True)
        assert "[1](https://github.com/marola-dev/marola-devkit/issues/12)" in h.read_text()

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
        issues_by_repo = {"marola-dev/marola": issues}
        _, _, rows34 = parse_table(f)
        assert find_issue_multi("0034", "1", issues_by_repo) == ("marola-dev/marola", 414)
        assert find_issue_multi("0034", "2", issues_by_repo) == (None, None)
        try:
            find_issue_multi(
                "0034",
                "1",
                {**issues_by_repo, "other/x": [{"number": 500, "title": "0034-T1: again"}]},
            )
            raise AssertionError("two issues for one task id should be an error")
        except TasksError:
            pass
        # Found in the umbrella even though the table has since gained a repo prefix for it (#562):
        # existence appearing later must never look like grounds to move an already-filed row.
        assert find_issue_multi("0034", "1", {"marola-dev/marola": issues, "marola-dev/x": []}) == (
            "marola-dev/marola",
            414,
        )

        # 4b. Cross-MIP tokens (#462): resolved by title, named in the body, never guessed.
        c = d / "MIP-0065.tasks.md"
        c.write_text(FIXTURE_HEAD + "| 1 | a | d | t | 0064-T4 |\n| 2 | b | d | t | 1, 0064-T4 |\n")
        other = [
            {"number": 458, "title": "0064-T4: kroki"},
            {"number": 459, "title": "0064-T40: x"},
        ]
        other_by_repo = {"marola-dev/marola": other}
        pc = plan(c, "marola-dev/marola", other_by_repo, set())
        assert pc["cross"] == {"0064-T4": {"repo": "marola-dev/marola", "number": 458}}, pc["cross"]
        assert [r["deps"] for r in pc["rows"]] == [["0064-T4"], ["1", "0064-T4"]]
        # Same repo as the dependent row: a bare `#458`, not `owner/repo#458`.
        assert "Depends on `0065-T1`, `0064-T4` (#458)" in pc["rows"][1]["body"], pc["rows"][1][
            "body"
        ]
        for bad_issues in (
            {},
            {"marola-dev/marola": other + [{"number": 460, "title": "0064-T4: again"}]},
        ):
            try:
                plan(c, "marola-dev/marola", bad_issues, set())
                raise AssertionError(f"cross-MIP token against {bad_issues} should be an error")
            except TasksError as exc:
                assert "0064-T4" in str(exc), exc
        # A cross-MIP dependency filed in a *different* repo than the dependent row is qualified.
        pcx = plan(
            c,
            "marola-dev/marola",
            {"marola-dev/marola-devkit": other},
            {"marola-devkit"},
        )
        # Row 1 has no `**repo**` prefix, so it stays in the umbrella; its cross dep lives elsewhere.
        assert "Depends on `0064-T4` (marola-dev/marola-devkit#458)" in pcx["rows"][0]["body"]

        p = plan(f, "marola-dev/marola", issues_by_repo, set(), "GitHub tracking standard")
        assert p["rows"][0]["issue"] == 414 and p["rows"][1]["issue"] is None
        assert p["rows"][0]["repo"] == "marola-dev/marola"
        assert p["rows"][5]["title"] == "0034-T6: slug-6"
        for heading in ("### What", "### Acceptance criteria", "### Named test"):
            assert heading in p["rows"][5]["body"], heading
        # A tasks file outside the checkout must not put its absolute path in the blob URL.
        assert f"blob/main/docs/MIPs/{f.name}" in p["rows"][5]["body"], p["rows"][5]["body"]
        assert p["parent"]["title"] == "MIP-0034: GitHub tracking standard"
        assert p["parent"]["repo"] == "marola-dev/marola"
        assert p["parent"]["issue"] is None
        for row in p["rows"]:
            assert f"0034-T{row['id']}:" in p["parent"]["body"]
        # No mip-title given: the parent still gets a usable, if generic, title.
        assert plan(f, "marola-dev/marola", issues_by_repo, set())["parent"]["title"] == "MIP-0034"
        # #562: a run whose H1 lookup degrades to "" (bare "MIP-0034" title) must still find a
        # parent already filed under the full "MIP-0034: <title>" form — an exact-title dedup would
        # miss it and file a second parent, whose sub-issue POSTs then 422 against the first one.
        filed_parent = [{"number": 700, "title": "MIP-0034: GitHub tracking standard"}]
        p_degraded = plan(f, "marola-dev/marola", {"marola-dev/marola": filed_parent}, set())
        assert p_degraded["parent"]["title"] == "MIP-0034"
        assert p_degraded["parent"]["issue"] == 700, "the bare title must still find the full one"
        # And the reverse holds too: a full title is found even when the *previous* filing used the
        # bare one (a MIP with no H1 at all, ever).
        filed_bare = [{"number": 701, "title": "MIP-0034"}]
        p_full = plan(
            f,
            "marola-dev/marola",
            {"marola-dev/marola": filed_bare},
            set(),
            "GitHub tracking standard",
        )
        assert p_full["parent"]["issue"] == 701
        try:
            plan(
                f,
                "marola-dev/marola",
                {
                    "marola-dev/marola": [
                        {"number": 700, "title": "MIP-0034: one"},
                        {"number": 701, "title": "MIP-0034: two"},
                    ]
                },
                set(),
            )
            raise AssertionError("two issues both titled MIP-0034... should be an error")
        except TasksError as exc:
            assert "MIP-0034" in str(exc), exc

        # 4c. §5.7: the repo prefix, resolution against what `gh repo view` confirmed, and the
        #     fallback flag that reports rather than silently redirecting.
        assert repo_of("**marola-devkit** (new) — filter-repo of …") == "marola-devkit"
        assert repo_of("**marola** — §5.4 app → site") == "marola"
        assert repo_of("no prefix here") is None
        assert repo_of("some `**escaped**` text") is None  # not a leading bold span
        umbrella = "marola-dev/marola"
        exists_row = {"repo": "marola-site"}
        assert resolved_repo(exists_row, umbrella, {"marola-site"}) == "marola-dev/marola-site"
        assert resolved_repo(exists_row, umbrella, set()) == umbrella
        assert resolved_repo({"repo": None}, umbrella, {"marola-site"}) == umbrella

        k = d / "MIP-0097.tasks.md"
        k.write_text(
            FIXTURE_HEAD.replace("0034", "0097")
            + "| 1 | a | **marola-site** — x | t | – |\n"
            + "| 2 | b | **marola-devkit** (new) — y | t | 1 |\n"
        )
        # Row 1's repo exists; row 2's does not yet, so it falls back to the umbrella and says so.
        pk = plan(k, umbrella, {umbrella: []}, {"marola-site"})
        assert (
            pk["rows"][0]["repo"] == "marola-dev/marola-site" and pk["rows"][0]["fallback"] is False
        )
        assert pk["rows"][1]["repo"] == umbrella and pk["rows"][1]["fallback"] is True
        assert "filed in the umbrella; its own repo does not exist yet" in pk["parent"]["body"]
        assert repos_referenced(k) == ["marola-site", "marola-devkit"]
        # A row with no prefix at all is never reported as a fallback — there was nowhere else it
        # could have gone.
        assert plan(f, umbrella, issues_by_repo, set())["rows"][0]["fallback"] is False

        # A `**marola**` row — its prefix names the umbrella's own bare name — resolves to the
        # umbrella (the only place it could), and that is not a fallback: comparing against the
        # umbrella string alone would flag it, since its resolved repo equals the umbrella too.
        u = d / "MIP-0098.tasks.md"
        u.write_text(FIXTURE_HEAD.replace("0034", "0098") + "| 1 | a | **marola** — x | t | – |\n")
        pu = plan(u, umbrella, {umbrella: []}, set())
        assert pu["rows"][0]["repo"] == umbrella
        assert pu["rows"][0]["fallback"] is False, "a **marola** row is never a fallback"

        # Re-run once row 2's own row is already filed (in the umbrella, as a fallback) and its
        # repo now exists: it is found where it is, not moved (#562).
        already = {
            umbrella: [{"number": 900, "title": "0097-T2: y"}],
            "marola-dev/marola-devkit": [],
        }
        pk2 = plan(k, umbrella, already, {"marola-site", "marola-devkit"})
        assert pk2["rows"][1]["issue"] == 900
        assert pk2["rows"][1]["repo"] == umbrella, "an already-filed fallback must not be moved"
        assert pk2["rows"][1]["fallback"] is True

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

    # 6. §5.7 shapes, as fixtures rather than a live read of docs/MIPs/*.tasks.md: this module
    #    moves to marola-devkit, which carries no docs/MIPs of its own (§5.6), so a self-test that
    #    globbed the real tree would fail there on day one. Each individual dependency-column shape
    #    (non-linear, three roots, a lettered root, the 1-vs-11 collision) already has its own
    #    fixture above; what is left to check here is §5.7's own new shape.
    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        # A table with every row already linked and no `**repo**` prefix at all — MIP-0063's own
        # shape before §5.7 existed. Idempotent, and every row means the umbrella.
        m63 = d / "MIP-0063.tasks.md"
        m63.write_text(
            FIXTURE_HEAD.replace("0034", "0063")
            + "| [1](https://github.com/marola-dev/marola/issues/414) | taxonomy | d | t | – |\n"
        )
        _, _, rows63 = parse_table(m63)
        assert all(r["linked"] for r in rows63), "an already-linked row must round-trip unchanged"
        assert link(m63, {"1": {"repo": "marola-dev/marola", "number": 1}}, write=False) == []
        assert repos_referenced(m63) == []

        # MIP-0070's own shape: every row names a repo, one of them `(new)`, and a row in a repo
        # that does not exist yet depending on one in a repo that does (§5.7's cross-repo edge case).
        m70 = d / "MIP-0070.tasks.md"
        m70.write_text(
            FIXTURE_HEAD.replace("0034", "0070")
            + "| 1 | site-data-only | **marola** — x | t | – |\n"
            + "| 4 | umbrella-resolve | **marola** — y | t | – |\n"
            + "| 7 | devkit-repo | **marola-devkit** (new) — z | t | 4 |\n"
        )
        _, _, rows70 = parse_table(m70)
        assert rows70[0]["repo"] == "marola"
        assert rows70[2]["repo"] == "marola-devkit"
        assert "4" in rows70[2]["deps"]
        assert resolved_repo(rows70[2], "marola-dev/marola", set()) == "marola-dev/marola"

    print("tasks_issues self-test: PASSED")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("command", nargs="?", choices=["plan", "link", "repos"])
    ap.add_argument("file", nargs="?", type=Path)
    ap.add_argument("--umbrella", default="", help="owner/name of the umbrella repo (§5.7)")
    ap.add_argument(
        "--issues", type=Path, help="JSON object {repo: [{number,title}, ...]} to dedup against"
    )
    ap.add_argument(
        "--existing", default="[]", help="JSON array of bare repo names `gh repo view` confirmed"
    )
    ap.add_argument("--mip-title", default="", help="the MIP's own H1, for the parent issue")
    ap.add_argument("--map", default="{}", help="JSON object of task id -> {repo, number}")
    ap.add_argument("--dry-run", action="store_true", help="link: report, write nothing")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.command or not args.file:
        ap.print_help()
        return 2
    try:
        if args.command == "repos":
            for name in repos_referenced(args.file):
                print(name)
        elif args.command == "plan":
            if not args.umbrella:
                raise TasksError("--umbrella owner/name is required")
            issues_by_repo = json.loads(args.issues.read_text()) if args.issues else {}
            existing = set(json.loads(args.existing))
            result = plan(args.file, args.umbrella, issues_by_repo, existing, args.mip_title)
            json.dump(result, sys.stdout)
            print()
        else:
            for line in link(args.file, json.loads(args.map), not args.dry_run):
                print(line)
    except TasksError as exc:
        print(f"tasks_issues: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
