#!/usr/bin/env python3
"""mip_graph — generate a Mermaid dependency graph from every MIP's metadata table and write it
into docs/mips/README.md between two HTML-comment markers. Reads only a machine-readable
**Blocked by** row (comma-separated MIP numbers, or the literal `none`) — the human-readable
**Depends on** field stays prose-only and is never parsed, because it legitimately carries four
different relations (blocking, blocked-by, co-delivery, negation) in one cell that a regex cannot
tell apart (verified against all 28 real MIPs in this repo before this script was written; see
docs/mips/README.md's own history for the specific mis-parses that ruled it out).

    scripts/mip_graph.py                    # regenerate the graph block in docs/mips/README.md
    scripts/mip_graph.py --check             # exit 1 if the checked-in block is stale or missing
    scripts/mip_graph.py --parallel 30 31    # can these two MIPs be worked in parallel?
    scripts/mip_graph.py --self-test

A MIP with no **Blocked by** row (most of them, today — this is a new, opt-in field) is drawn as
an unconnected node. Nothing here invents an edge from prose.
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIPS_DIR = ROOT / "docs" / "mips"
README = MIPS_DIR / "README.md"

START_MARK = "<!-- mip-graph:start -->"
END_MARK = "<!-- mip-graph:end -->"

TITLE_RE = re.compile(r"^#\s*MIP-(\d{4}):\s*(.+?)\s*$")
ROW_RE = re.compile(r"^\|\s*\*\*(?P<key>[^*]+)\*\*\s*\|\s*(?P<val>.*?)\s*\|\s*$")
MIP_NUM_RE = re.compile(r"\b(\d{4})\b")

STATUS_CLASS = {
    "draft": "draft",
    "accepted": "accepted",
    "implemented": "implemented",
    "rejected": "rejected",
    "superseded": "rejected",
}


class Mip:
    def __init__(self, num, title, status, blocked_by):
        self.num = num
        self.title = title
        self.status = status
        self.blocked_by = blocked_by  # list[int]

    def node_id(self):
        return f"M{self.num:04d}"

    def status_class(self):
        s = self.status.lower()
        for key, cls in STATUS_CLASS.items():
            if s.startswith(key):
                return cls
        return "draft"


def parse_mip_file(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    num = None
    title = path.stem
    status = "Draft"
    blocked_by = []
    for line in lines[:3]:
        m = TITLE_RE.match(line)
        if m:
            num = int(m.group(1))
            title = m.group(2)
            break
    if num is None:
        return None
    for line in lines:
        m = ROW_RE.match(line)
        if not m:
            continue
        key = m.group("key").strip().lower()
        val = m.group("val").strip()
        if key == "status":
            # first word/clause only — "Implemented, ultrareview-verified..." -> "Implemented"
            status = re.split(r"[,(]", val, maxsplit=1)[0].strip() or "Draft"
        elif key == "blocked by":
            if val.lower() != "none":
                blocked_by = [int(n) for n in MIP_NUM_RE.findall(val)]
    return Mip(num, title, status, blocked_by)


def load_mips():
    mips = {}
    for path in sorted(MIPS_DIR.glob("MIP-*.md")):
        if path.name.endswith(".tasks.md"):
            continue
        mip = parse_mip_file(path)
        if mip:
            mips[mip.num] = mip
    return mips


NO_EDGES_PLACEHOLDER = (
    "_No MIP currently declares a **Blocked by** relationship, so there is nothing to graph yet "
    "— add that field to a MIP's metadata table and run `just mip-graph` again._"
)


def render_mermaid(mips):
    edges = []
    for num in sorted(mips):
        mip = mips[num]
        for blocker in mip.blocked_by:
            if blocker in mips:
                edges.append((blocker, num))
    edges = sorted(set(edges))
    if not edges:
        return NO_EDGES_PLACEHOLDER

    # Only the MIPs that actually participate in an edge get drawn — an orphan MIP (no declared
    # Blocked-by relationship in either direction) adds a disconnected box that tells the reader
    # nothing the index table above doesn't already say, and 20+ of them turned the graph into an
    # unreadable wall (verified against a real run: 29 MIPs, 0 edges, every node carrying its full
    # title — the exact complaint that got this rewritten).
    connected = {a for a, _ in edges} | {b for _, b in edges}

    lines = ["```mermaid", "flowchart TD"]
    lines.append("  classDef draft fill:#fff,stroke:#999,stroke-dasharray:3 3;")
    lines.append("  classDef accepted fill:#eef,stroke:#36c;")
    lines.append("  classDef implemented fill:#efe,stroke:#2a2;")
    lines.append("  classDef rejected fill:#f8f8f8,stroke:#bbb,color:#999;")
    for num in sorted(connected):
        mip = mips[num]
        # Bare "MIP-NNNN" only — the full title is already one line up in the index table, and a
        # long label per node is exactly what made the graph unreadable before this rewrite.
        lines.append(f'  {mip.node_id()}["MIP-{num:04d}"]:::{mip.status_class()}')
    for a, b in edges:
        lines.append(f"  {mips[a].node_id()} --> {mips[b].node_id()}")
    lines.append("```")
    orphans = sorted(set(mips) - connected)
    if orphans:
        orphan_list = ", ".join(f"MIP-{n:04d}" for n in orphans)
        lines.append("")
        lines.append(
            f"_{len(orphans)} MIP(s) with no declared Blocked-by relationship, not graphed: "
            f"{orphan_list}._"
        )
    return "\n".join(lines)


def graph_block(mips):
    return f"{START_MARK}\n{render_mermaid(mips)}\n{END_MARK}"


def reachable(mips, start, blocked_direction=True):
    """Every MIP transitively blocking (or blocked by, if blocked_direction=False) `start`."""
    seen = set()
    stack = [start]
    while stack:
        cur = stack.pop()
        if cur not in mips:
            continue
        neighbors = (
            mips[cur].blocked_by
            if blocked_direction
            else [m.num for m in mips.values() if cur in m.blocked_by]
        )
        for n in neighbors:
            if n not in seen:
                seen.add(n)
                stack.append(n)
    return seen


def source_paths(mip_path):
    """Backticked paths under a known top-level source dir, from a MIP's §5 Design section."""
    text = mip_path.read_text(encoding="utf-8")
    return set(
        re.findall(
            r"`((?:core|local|cli|site|scripts)/[A-Za-z0-9_./-]+)`",
            text,
        )
    )


def cmd_parallel(a, b):
    mips = load_mips()
    if a not in mips or b not in mips:
        missing = [n for n in (a, b) if n not in mips]
        print(f"mip_graph --parallel: MIP-{missing[0]:04d} not found")
        return 1
    blockers_of_a = reachable(mips, a)
    blockers_of_b = reachable(mips, b)
    dep_conflict = b in blockers_of_a or a in blockers_of_b
    candidates_a = list(MIPS_DIR.glob(f"MIP-{a:04d}-*.md"))
    candidates_b = list(MIPS_DIR.glob(f"MIP-{b:04d}-*.md"))
    paths_a = source_paths(candidates_a[0]) if candidates_a else set()
    paths_b = source_paths(candidates_b[0]) if candidates_b else set()
    overlap = paths_a & paths_b

    print(f"MIP-{a:04d} vs MIP-{b:04d}:")
    print(f"  dependency conflict (Blocked by graph): {'yes' if dep_conflict else 'no'}")
    if overlap:
        print(f"  §5 source-path overlap: yes — {', '.join(sorted(overlap))}")
    else:
        print("  §5 source-path overlap: no (or no backticked source paths found in one/both)")
    if not dep_conflict and not overlap:
        print("  -> parallel-safe on both checks")
        return 0
    print("  -> NOT confirmed parallel-safe")
    return 1


def cmd_generate(check):
    if not README.exists():
        print(f"mip_graph: {README} not found", file=sys.stderr)
        return 1
    mips = load_mips()
    new_block = graph_block(mips)
    text = README.read_text(encoding="utf-8")
    if START_MARK in text and END_MARK in text:
        pre, rest = text.split(START_MARK, 1)
        _, post = rest.split(END_MARK, 1)
        new_text = pre + new_block + post
    else:
        sep = "" if text.endswith("\n") else "\n"
        new_text = text + sep + "\n" + new_block + "\n"
    if check:
        if new_text != text:
            print(
                "mip_graph --check: docs/mips/README.md's graph block is stale or missing — "
                "run `just mip-graph` and commit the result"
            )
            return 1
        print("mip_graph --check: graph block is current")
        return 0
    if new_text != text:
        README.write_text(new_text, encoding="utf-8")
        print(f"mip_graph: wrote graph block to {README} ({len(mips)} MIPs)")
    else:
        print("mip_graph: graph block already current, nothing written")
    return 0


def self_test():
    fails = 0

    def ok(cond, label):
        nonlocal fails
        if cond:
            print("  ok   " + label)
        else:
            print("  FAIL " + label)
            fails += 1

    sample = """# MIP-0099: A sample title

| | |
|---|---|
| **Status** | Draft |
| **Blocked by** | MIP-0010, MIP-0025 |
"""
    tmp = Path("/tmp/mip_graph_selftest_0099.md")
    tmp.write_text(sample, encoding="utf-8")
    mip = parse_mip_file(tmp)
    ok(mip is not None, "parses a real MIP header")
    ok(mip.num == 99, "extracts the MIP number")
    ok(mip.title == "A sample title", "extracts the title")
    ok(mip.blocked_by == [10, 25], "parses a comma-separated Blocked by list")
    tmp.unlink()

    none_sample = "# MIP-0098: Another\n\n| | |\n|---|---|\n| **Blocked by** | none |\n"
    tmp2 = Path("/tmp/mip_graph_selftest_0098.md")
    tmp2.write_text(none_sample, encoding="utf-8")
    mip2 = parse_mip_file(tmp2)
    ok(mip2.blocked_by == [], "'none' produces an empty blocker list, not a parsed '0'")
    tmp2.unlink()

    no_row = "# MIP-0097: No blocked-by row at all\n\n| | |\n|---|---|\n| **Status** | Draft |\n"
    tmp3 = Path("/tmp/mip_graph_selftest_0097.md")
    tmp3.write_text(no_row, encoding="utf-8")
    mip3 = parse_mip_file(tmp3)
    ok(mip3.blocked_by == [], "a MIP with no Blocked by row at all is unconnected, not an error")
    tmp3.unlink()

    mips = {
        10: Mip(10, "Ledger", "Implemented", []),
        32: Mip(32, "Benchmark", "Draft", [10]),
    }
    block = graph_block(mips)
    ok(block.startswith(START_MARK) and block.endswith(END_MARK), "block is wrapped in markers")
    ok("M0010 --> M0032" in block, "an edge renders blocker --> blocked")
    ok(":::implemented" in block and ":::draft" in block, "status classes render per node")
    ok('"MIP-0010"' in block, "a connected node's label is bare MIP-NNNN, not its title")
    ok(
        "Ledger" not in block and "Benchmark" not in block,
        "a MIP's title never appears in the graph",
    )

    r = reachable(mips, 32)
    ok(r == {10}, "reachable() walks the Blocked-by chain")

    zero_edge_mips = {1: Mip(1, "A", "Draft", []), 2: Mip(2, "B", "Draft", [])}
    zero_block = graph_block(zero_edge_mips)
    ok("```mermaid" not in zero_block, "zero Blocked-by edges renders no mermaid block at all")
    ok("nothing to graph yet" in zero_block, "zero edges renders the placeholder text instead")

    orphan_mips = {
        1: Mip(1, "Orphan", "Draft", []),
        10: Mip(10, "Ledger", "Implemented", []),
        32: Mip(32, "Benchmark", "Draft", [10]),
    }
    orphan_block = graph_block(orphan_mips)
    ok("M0001" not in orphan_block, "a MIP with no edge in either direction is not drawn as a node")
    ok("MIP-0001" in orphan_block, "an excluded orphan is still named in the not-graphed list")

    stale_before = "before\n<!-- mip-graph:start -->\nold\n<!-- mip-graph:end -->\nafter\n"
    tmp_readme = Path("/tmp/mip_graph_selftest_readme.md")
    tmp_readme.write_text(stale_before, encoding="utf-8")
    global README
    real_readme = README
    README = tmp_readme
    try:
        rc = cmd_generate(check=True)
        ok(rc == 1, "--check reports stale when the block content differs")
        cmd_generate(check=False)
        rc2 = cmd_generate(check=True)
        ok(rc2 == 0, "--check passes immediately after a regenerate")
        ok(
            "before\n" in tmp_readme.read_text() and "after\n" in tmp_readme.read_text(),
            "content outside the markers is untouched",
        )
    finally:
        README = real_readme
        tmp_readme.unlink()

    print(f"mip_graph self-test: {'ok' if fails == 0 else f'{fails} FAILED'}")
    return 0 if fails == 0 else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--parallel", nargs=2, type=int, metavar=("MIP_A", "MIP_B"))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        sys.exit(self_test())
    if args.parallel:
        sys.exit(cmd_parallel(*args.parallel))
    sys.exit(cmd_generate(check=args.check))


if __name__ == "__main__":
    main()
