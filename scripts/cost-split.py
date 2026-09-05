#!/usr/bin/env python3
"""cost-split — attribute a Claude Code session's real token usage to the commits it produced.

    scripts/cost-split.py                      # commits of the current branch (main..HEAD)
    scripts/cost-split.py mip-0005             # every commit of a MIP stack (mip-0005/1-*, /2-*, ...)
    scripts/cost-split.py --session 336ebf4f   # restrict to one session (prefix of its id)
    scripts/cost-split.py --json               # machine-readable

AGENTS.md wants a `Cost:` trailer per commit and a Cost section per PR. `/usage` and
`just claude-cost` (ccusage) give one figure per *session*; when one session produces several
stacked PRs that figure has to be split. This does it the only honest way available: every
assistant message in the session log (~/.claude/projects/<project>/<session>.jsonl) carries its
timestamp and token usage, so the usage between two commits is the cost of the second commit.
Messages before the first commit (planning) go to the first commit; messages after the last one
are reported as "uncommitted (so far)".

Prices: LiteLLM's public price table (the same source ccusage uses), cached one day under .tmp/.
Offline with no cache, the tokens are still split and the dollar column says n/a. On a
subscription the dollars are not a bill — they are the quota proxy AGENTS.md asks for.
"""

import argparse
import datetime as dt
import json
import subprocess
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path

PRICES_URL = (
    "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
)


def sh(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def repo_root():
    # The main checkout even from a worktree: Claude Code keys its logs on the directory the
    # session was started in, which for this repo's `.tmp/wt-*` worktrees is the main checkout.
    common = Path(sh("git", "rev-parse", "--path-format=absolute", "--git-common-dir").strip())
    return common.parent


def project_dir(root):
    # Claude Code's per-project log dir: the absolute path with every "/" (and ".") turned into "-".
    slug = "-" + str(root).strip("/").replace("/", "-").replace(".", "-")
    return Path.home() / ".claude" / "projects" / slug


def load_prices(root):
    cache = root / ".tmp" / "litellm-prices.json"
    try:
        if cache.exists() and (dt.datetime.now().timestamp() - cache.stat().st_mtime) < 86400:
            return json.loads(cache.read_text())
        with urllib.request.urlopen(PRICES_URL, timeout=15) as r:
            data = r.read()
        cache.parent.mkdir(exist_ok=True)
        cache.write_bytes(data)
        return json.loads(data)
    except Exception:
        return json.loads(cache.read_text()) if cache.exists() else {}


def price(prices, model, u):
    p = prices.get(model)
    if not p:
        return None
    cc = u.get("cache_creation") or {}
    write_5m = cc.get("ephemeral_5m_input_tokens", u.get("cache_creation_input_tokens", 0))
    write_1h = cc.get("ephemeral_1h_input_tokens", 0)
    if not cc:
        write_5m = u.get("cache_creation_input_tokens", 0)
    return (
        u.get("input_tokens", 0) * p.get("input_cost_per_token", 0)
        + u.get("output_tokens", 0) * p.get("output_cost_per_token", 0)
        + write_5m * p.get("cache_creation_input_token_cost", 0)
        + write_1h
        * p.get(
            "cache_creation_input_token_cost_above_1hr", p.get("cache_creation_input_token_cost", 0)
        )
        + u.get("cache_read_input_tokens", 0) * p.get("cache_read_input_token_cost", 0)
    )


def messages(pdir, session_prefix):
    """(timestamp, session, model, usage) per assistant message, deduplicated by message id."""
    seen = {}
    for f in sorted(pdir.glob("*.jsonl")):
        if session_prefix and not f.stem.startswith(session_prefix):
            continue
        with open(f, encoding="utf-8") as fh:
            for line in fh:
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    continue
                m = d.get("message")
                if not isinstance(m, dict) or not m.get("usage") or not d.get("timestamp"):
                    continue
                # Claude Code writes `<synthetic>` assistant messages (interrupts, tool-result
                # stand-ins) with a usage block of zeros and no real model — not a priced request.
                if m.get("model") == "<synthetic>":
                    continue
                key = m.get("id") or d.get("requestId") or d.get("uuid")
                ts = dt.datetime.fromisoformat(d["timestamp"].replace("Z", "+00:00"))
                seen[key] = (ts, f.stem, m.get("model", "?"), m["usage"])
    return sorted(seen.values(), key=lambda x: x[0])


def commits(root, stack):
    """[(time, branch, sha, subject)] — each commit once, on the first branch that carries it."""
    out, known = [], set()
    branches = []
    if stack:  # a MIP (mip-0005 → every mip-0005/k-* branch) or one plain branch name
        branches = [
            b.strip()
            for b in sh(
                "git", "branch", "--list", f"{stack}/*", "--format=%(refname:short)"
            ).splitlines()
            if b.strip()
        ]

        # mip-NNNN/k-slug sorts by k; a branch without a numeric task prefix (mip-0012/llm4s-adoption,
        # a one-PR MIP) sorts after the numbered ones, by name.
        def task_no(b):
            head = b.split("/")[1].split("-")[0]
            return (0, int(head), "") if head.isdigit() else (1, 0, b)

        branches.sort(key=task_no)
        if not branches:
            branches = [stack]
    else:
        branches = [sh("git", "branch", "--show-current").strip()]
    for b in branches:
        log = sh("git", "log", "--reverse", "--format=%H%x00%cI%x00%s", f"origin/main..{b}")
        for line in log.splitlines():
            sha, when, subject = line.split("\x00")
            if sha in known:
                continue
            known.add(sha)
            out.append((dt.datetime.fromisoformat(when), b, sha, subject))
    return sorted(out, key=lambda c: c[0])


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument(
        "stack", nargs="?", help="mip-NNNN: every branch of the stack (default: current branch)"
    )
    ap.add_argument("--session", help="session id prefix (default: every session of this project)")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    root = repo_root()
    pdir = project_dir(root)
    if not pdir.is_dir():
        sys.exit(f"no session logs at {pdir}")
    msgs = messages(pdir, a.session)
    cs = commits(root, a.stack.lower() if a.stack else None)
    if not cs:
        sys.exit("no commits ahead of origin/main on the selected branch(es)")
    prices = load_prices(root)

    # Only sessions that overlap the commit window are relevant: from the first message of any
    # session that produced a commit, up to now.
    buckets = defaultdict(
        lambda: {
            "in": 0,
            "out": 0,
            "cache_w": 0,
            "cache_r": 0,
            "usd": 0.0,
            "models": set(),
            "sessions": set(),
            "priced": True,
        }
    )
    order = [c[2] for c in cs] + ["uncommitted"]
    for ts, session, model, u in msgs:
        target = "uncommitted"
        for when, _b, sha, _s in cs:
            if ts <= when:
                target = sha
                break
        b = buckets[target]
        b["in"] += u.get("input_tokens", 0)
        b["out"] += u.get("output_tokens", 0)
        b["cache_w"] += u.get("cache_creation_input_tokens", 0)
        b["cache_r"] += u.get("cache_read_input_tokens", 0)
        b["models"].add(model)
        b["sessions"].add(session[:8])
        usd = price(prices, model, u)
        if usd is None:
            b["priced"] = False
        else:
            b["usd"] += usd

    rows = []
    for key in order:
        b = buckets.get(key)
        if not b:
            continue
        meta = next(
            ((br, subj) for _w, br, sha, subj in cs if sha == key), ("", "uncommitted (so far)")
        )
        rows.append(
            {
                "commit": key[:7] if key != "uncommitted" else "-",
                "branch": meta[0],
                "subject": meta[1],
                "input": b["in"],
                "output": b["out"],
                "cache_write": b["cache_w"],
                "cache_read": b["cache_r"],
                "tokens": b["in"] + b["out"] + b["cache_w"] + b["cache_r"],
                "usd": round(b["usd"], 2) if b["priced"] else None,
                "models": sorted(b["models"]),
                "sessions": sorted(b["sessions"]),
            }
        )
    if a.json:
        print(json.dumps(rows, indent=1))
        return
    today = dt.date.today().isoformat()
    print(
        f"{'commit':8} {'usd':>7} {'tokens':>9} {'in':>7} {'out':>7} {'cache_w':>8} {'cache_r':>9}  branch / subject"
    )
    for r in rows:
        usd = f"${r['usd']:.2f}" if r["usd"] is not None else "n/a"
        print(
            f"{r['commit']:8} {usd:>7} {r['tokens']:>9,} {r['input']:>7,} {r['output']:>7,} {r['cache_write']:>8,} {r['cache_read']:>9,}  {r['branch']} {r['subject'][:60]}"
        )
    per_branch = defaultdict(lambda: [0.0, 0, 0, True, set()])
    for r in rows:
        if r["commit"] == "-":
            continue
        pb = per_branch[r["branch"]]
        pb[0] += r["usd"] or 0
        pb[1] += r["tokens"]
        pb[2] += r["cache_read"]
        pb[3] = pb[3] and r["usd"] is not None
        pb[4].update(r["models"])
    print("\nCost: trailers per branch (paste into the commit / PR):")
    for br, (usd, tokens, cached, priced, models) in per_branch.items():
        dollars = f"~${usd:.2f}" if priced else "$n/a"
        share = f"{100 * cached / tokens:.0f}% cache reads" if tokens else "no tokens"
        print(
            f"  {br}: Cost: {dollars} · {tokens / 1e6:.1f}M tokens, {share} ({', '.join(sorted(models))}) · split by commit time, scripts/cost-split.py {today}"
        )
    total = sum(r["usd"] or 0 for r in rows)
    print(f"\nsession total ${total:.2f} for {len(rows)} buckets (list prices, LiteLLM table)")


if __name__ == "__main__":
    main()
