#!/usr/bin/env python3
"""cost-split — attribute a Claude Code session's real token usage to the commits it produced.

    scripts/cost-split.py                      # commits of the current branch (main..HEAD)
    scripts/cost-split.py mip-0005             # every commit of a MIP stack (mip-0005/1-*, /2-*, ...)
    scripts/cost-split.py --session 336ebf4f   # restrict to one session (prefix of its id)
    scripts/cost-split.py --json               # machine-readable
    scripts/cost-split.py --estimate           # also fill in commits with no logged usage
    scripts/cost-split.py --estimate --verbose #   ... and print the calibration fit
    scripts/cost-split.py --estimate-commit <sha>  # just one commit's estimated `Cost:` trailer
    scripts/cost-split.py --self-test          # parser + estimator self-check (just quality-other)

AGENTS.md wants a `Cost:` trailer per commit and a Cost section per PR. `/usage` and
`just claude-cost` (ccusage) give one figure per *session*; when one session produces several
stacked PRs that figure has to be split. This does it the only honest way available: every
assistant message in the session log (~/.claude/projects/<project>/<session>.jsonl) carries its
timestamp and token usage, so the usage between two commits is the cost of the second commit.
Messages before the first commit (planning) go to the first commit; messages after the last one
are reported as "uncommitted (so far)".

Subagent usage: an `Agent(...)` call (`.claude/skills`, `code-review`, a plain subagent) writes its
own transcript to `<session>/subagents/agent-<id>.jsonl` next to the parent session's own
`<session>.jsonl` — same shape (assistant messages with a `usage` block), same billing. The parent
log's own record of the call is a `tool_result` placeholder with no usage of its own (confirmed by
hand: no `isSidechain:true` line in a top-level `.jsonl` carries a `usage` block), so folding these
transcripts in adds real cost that was previously invisible without double-counting anything.

Some commits still have nothing logged at all — a subagent that ran in a worktree whose session
was never re-attached, work done on another machine, or a commit written outside a Claude Code
session. `--estimate` fills those in from the diff alone: lines changed, files touched, and a
tokens-per-changed-line coefficient calibrated from this repo's own history (every commit on
origin/main that already carries a measured `Cost:` trailer with a token figure, paired with its
own diff size). The result is always clearly labelled `est.` — never mistaken for a measurement,
and cache-read share (impossible to guess from a diff) is simply left out of the price.

Prices: LiteLLM's public price table (the same source ccusage uses), cached one day under .tmp/.
Offline with no cache, the tokens are still split and the dollar column says n/a. On a
subscription the dollars are not a bill — they are the quota proxy AGENTS.md asks for.
"""

import argparse
import datetime as dt
import json
import re
import statistics
import subprocess
import sys
import tempfile
import urllib.request
from collections import defaultdict
from pathlib import Path

PRICES_URL = (
    "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
)

# ---------------------------------------------------------------------------------------------
# Diff-size estimation constants — see calibrate() for where the coefficient itself comes from.
# ---------------------------------------------------------------------------------------------
# Fallback only: used when origin/main carries no measured `Cost:` trailer with a token figure to
# calibrate against (a fresh checkout of this repo, or a --self-test fixture). ~6,200 tokens per
# changed line is this repo's own median as of 2026-09-05 (30 calibration commits) — see
# `scripts/cost-split.py --estimate --verbose`.
DEFAULT_TOKENS_PER_LINE = 6200
# The model priced when a commit has no logged usage at all, so there's no model name to read off
# a real message — the model this repo's sessions run by default today.
DEFAULT_PRICE_MODEL = "claude-sonnet-5"
# A touched file costs roughly this many "changed lines" worth of tokens just to open, read for
# context and re-check, even when the diff itself is one line — so a wide, shallow diff is not
# free the way a deep, narrow one is.
FILE_OVERHEAD_LINES = 15

TOKEN_TRAILER_RE = re.compile(r"([\d][\d,]*\.?\d*)\s*(M|k)?\s*tokens")
USD_TRAILER_RE = re.compile(r"\$([\d.]+)")


def sh(*args, cwd=None):
    return subprocess.run(args, check=True, capture_output=True, text=True, cwd=cwd).stdout


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


def _session_message_files(pdir, session_prefix):
    """(session_id, path) for every jsonl to read usage from: each top-level session log, plus
    every subagent transcript Claude Code nests under <session>/subagents/*.jsonl for that
    session. `session_prefix` filters by the *parent* session id, same as before this existed —
    a subagent spawned inside session d802fa69 is included by `--session d802fa69`."""
    for f in sorted(pdir.glob("*.jsonl")):
        sid = f.stem
        if session_prefix and not sid.startswith(session_prefix):
            continue
        yield sid, f
        for sf in sorted((pdir / sid / "subagents").glob("*.jsonl")):
            yield sid, sf


def messages(pdir, session_prefix):
    """(timestamp, session, model, usage) per assistant message, deduplicated by message id."""
    seen = {}
    for sid, f in _session_message_files(pdir, session_prefix):
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
                # `gitBranch` is the checkout's branch when the message was made — a subagent
                # transcript carries its worktree's branch. It is what lets usage follow the
                # branch instead of the clock (see the attribution loop in main()).
                seen[key] = (ts, sid, m.get("model", "?"), m["usage"], d.get("gitBranch") or "")
    return sorted(seen.values(), key=lambda x: x[0])


_equivalents = None


def branches_with_equivalent(sha):
    """Branches holding a commit with the same author date and subject as `sha` — the same
    commit after a rebase, cherry-pick or `just cost-fill` rewrite gave it a new hash. Without
    this, the branch a subagent authored on stops "containing" its own commits the moment
    they are rewritten, and its usage would no longer match them."""
    global _equivalents
    if _equivalents is None:
        _equivalents = defaultdict(set)
        refs = sh(
            "git", "for-each-ref", "--format=%(refname:short)", "refs/heads", "refs/remotes/origin"
        )
        for ref in refs.splitlines():
            ref = ref.strip()
            if not ref or ref in ("main", "origin/main", "origin/HEAD"):
                continue
            name = ref[7:] if ref.startswith("origin/") else ref
            try:
                log = sh("git", "log", "--format=%aI%x00%s", f"origin/main..{ref}")
            except subprocess.CalledProcessError:
                continue
            for line in log.splitlines():
                if "\x00" in line:
                    _equivalents[tuple(line.split("\x00", 1))].add(name)
    when, subject = sh("git", "log", "-1", "--format=%aI%x00%s", sha).rstrip("\n").split("\x00", 1)
    return set(_equivalents.get((when, subject), set()))


def branches_containing(sha):
    """Local and origin branch names that contain `sha` (origin/ prefix stripped)."""
    out = set()
    for line in sh(
        "git", "branch", "-a", "--contains", sha, "--format=%(refname:short)"
    ).splitlines():
        b = line.strip()
        if not b or b == "origin/HEAD" or " -> " in b:
            continue
        out.add(b[7:] if b.startswith("origin/") else b)
    return out


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
        # Author date, not committer date: a rebase, cherry-pick or `just cost-fill` rewrite
        # restamps the committer date of every commit to the same second, and the time split
        # then hands the whole branch's usage to its first commit. Author dates survive all three.
        log = sh("git", "log", "--reverse", "--format=%H%x00%aI%x00%s", f"origin/main..{b}")
        for line in log.splitlines():
            sha, when, subject = line.split("\x00")
            if sha in known:
                continue
            known.add(sha)
            out.append((dt.datetime.fromisoformat(when), b, sha, subject))
    return sorted(out, key=lambda c: c[0])


# ---------------------------------------------------------------------------------------------
# Diff-size cost estimation — for a commit with no logged usage at all.
# ---------------------------------------------------------------------------------------------


def parse_token_count(cost_line):
    """Tokens named in one `Cost:` trailer line, or None if it doesn't carry a figure usable for
    calibration. Skips bucket-wide notes ('shared bucket', 'not split further') — the same token
    count copy-pasted onto several sibling commits, so pairing any one of them with its own diff
    size would just add noise — and the one commit that admits it never got a number
    ('unmeasured')."""
    if "shared bucket" in cost_line or "unmeasured" in cost_line:
        return None
    m = TOKEN_TRAILER_RE.search(cost_line)
    if not m:
        return None
    n = float(m.group(1).replace(",", ""))
    unit = m.group(2)
    return n * 1e6 if unit == "M" else n * 1e3 if unit == "k" else n


def diff_stats(sha, cwd=None):
    """(changed_lines, files_touched, paths) for one commit, from `git show --numstat`."""
    added = deleted = files = 0
    paths = []
    for line in sh("git", "show", "--numstat", "--format=", sha, cwd=cwd).splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        a, d, path = parts
        added += 0 if a == "-" else int(a)
        deleted += 0 if d == "-" else int(d)
        files += 1
        paths.append(path)
    return added + deleted, files, paths


def calibrate(ref="origin/main", cwd=None):
    """Median tokens-per-changed-line across every `ref` commit with a measured `Cost:` trailer
    carrying a token figure, paired with that commit's own diff size (`diff_stats`). The median
    (not a least-squares fit) is deliberate: a big-context, small-diff commit can be two orders of
    magnitude above a routine one (this repo's own spread is ~800-440,000 tokens/line), and a
    maintainer already eyeballs a `Cost:` trailer the same way — ignore the extremes, trust the
    middle. Returns (coefficient, stats); stats["n"] == 0 means DEFAULT_TOKENS_PER_LINE was used.
    """
    body = sh("git", "log", ref, "--format=%H%x00%B%x00END", cwd=cwd)
    ratios = []
    for chunk in body.split("\x00END\n"):
        if "\x00" not in chunk:
            continue
        sha, msg = chunk.split("\x00", 1)
        sha = sha.strip()
        tokens = None
        for line in msg.splitlines():
            if line.startswith("Cost:"):
                tokens = parse_token_count(line)
                if tokens:
                    break
        if not tokens:
            continue
        changed, _files, _paths = diff_stats(sha, cwd=cwd)
        if changed <= 0:
            continue
        ratios.append(tokens / changed)
    if not ratios:
        return DEFAULT_TOKENS_PER_LINE, {"n": 0}
    ratios.sort()
    stats = {
        "n": len(ratios),
        "min": ratios[0],
        "max": ratios[-1],
        "median": statistics.median(ratios),
    }
    if len(ratios) >= 4:
        q1, _, q3 = statistics.quantiles(ratios, n=4)
        stats["p25"], stats["p75"] = q1, q3
    else:
        stats["p25"], stats["p75"] = ratios[0], ratios[-1]
    return stats["median"], stats


def print_calibration(coeff, stats, stream=sys.stdout):
    if stats["n"] == 0:
        print(
            f"cost-split --estimate: no calibration history found — falling back to the "
            f"documented default of {coeff:.0f} tokens/changed-line",
            file=stream,
        )
        return
    print(
        f"cost-split --estimate: calibrated {coeff:.0f} tokens/changed-line from {stats['n']} "
        f"origin/main commit(s) with a measured Cost: trailer "
        f"(IQR {stats['p25']:.0f}-{stats['p75']:.0f}, range {stats['min']:.0f}-{stats['max']:.0f})",
        file=stream,
    )


def category_multiplier(paths):
    """A documented heuristic nudge, not a second regression — too few calibration commits per
    category (docs-only, test-heavy, ...) in this repo's history to fit each separately without
    overfitting on a handful of points."""
    if not paths:
        return 1.0
    docs_only = all(p.endswith(".md") or p.startswith("docs/") for p in paths)
    if docs_only:
        return 0.6  # prose from an outline is cheaper per line than code written from scratch
    scala = any(p.endswith((".scala", ".sbt")) for p in paths)
    tests = any("test" in p.lower() or "spec" in p.lower() for p in paths)
    if scala and tests:
        return 1.15  # red/green/refactor round-trips cost more than the final diff alone shows
    return 1.0


def estimate_tokens_for_diff(changed_lines, files, paths, coeff):
    effective = changed_lines + FILE_OVERHEAD_LINES * files
    return round(coeff * effective * category_multiplier(paths))


def estimate_usd(prices, tokens, model=DEFAULT_PRICE_MODEL):
    """Prices the estimated tokens as if every one of them were a fresh input token (LiteLLM's
    `input_cost_per_token`) — cache-read share cannot be estimated from a diff, so it is left out
    entirely rather than guessed; this repo's own measured $/token (see calibrate()'s sibling
    check in the self-test) runs about half of that rate, so the estimate leans conservative
    rather than optimistic."""
    p = prices.get(model)
    if not p:
        return None
    rate = p.get("input_cost_per_token")
    if not rate:
        return None
    return tokens * rate


def format_tokens(tokens):
    if tokens >= 100_000:
        return f"~{tokens / 1e6:.1f}M tokens est."
    if tokens >= 1_000:
        return f"~{tokens / 1e3:.0f}k tokens est."
    return f"~{tokens:.0f} tokens est."


def format_estimate_trailer(tokens, usd, changed_lines, today):
    usd_part = f"~${usd:.2f} est." if usd is not None else "$n/a est."
    return (
        f"Cost: {usd_part} · {format_tokens(tokens)} "
        f"(diff-size model, {changed_lines} lines, no session log) "
        f"· scripts/cost-split.py --estimate {today}"
    )


def estimate_commit(sha, prices, coeff, cwd=None):
    """(tokens, usd, changed_lines) for one commit's diff-size estimate, or None if the commit
    has no diff at all (an empty commit, or a merge commit numstat can't attribute)."""
    changed, files, paths = diff_stats(sha, cwd=cwd)
    if changed <= 0:
        return None
    tokens = estimate_tokens_for_diff(changed, files, paths, coeff)
    usd = estimate_usd(prices, tokens)
    return tokens, usd, changed


# ---------------------------------------------------------------------------------------------
# Self-test — scripts/*.py's usual shape (assert, print "ok", return 0/1); wired into
# `just quality-other` and ci.yml's quality-other job.
# ---------------------------------------------------------------------------------------------


def self_test():
    # --- parse_token_count: every shape actually seen in this repo's own `Cost:` trailers ---
    cases = [
        ("Cost: ~$8.98 · 8.1M tokens, 97% cache reads (claude-fable-5-1) · ...", 8_100_000),
        (
            "Cost: ~289k tokens (forked subagent, claude-fable-5-1; 40 tool calls, 8 min) · ...",
            289_000,
        ),
        (
            "Cost: shared bucket $7.03 · 10,517,273 tokens (claude-fable-5-1) — not split further",
            None,
        ),
        ("Cost: ~unmeasured in-agent · fill from just claude-cost before PR", None),
        (
            "Cost: ~$0 · 0 tokens booked (claude-fable-5-1) · ...",
            0.0,
        ),  # parses, but falsy: calibrate() skips it too
        ("Cost: ~$0.1", None),  # no token figure at all
        ("Cost: shared bucket $3.18 · 4,008,909 tokens (claude-fable-5-1) — ...", None),
    ]
    for line, expected in cases:
        got = parse_token_count(line)
        assert got == expected, f"{line!r} -> {got}, expected {expected}"

    # --- estimate_tokens_for_diff / category_multiplier ---
    coeff = 6000
    plain = estimate_tokens_for_diff(100, 2, ["core/src/main/Foo.scala"], coeff)
    assert plain == round(coeff * (100 + FILE_OVERHEAD_LINES * 2)), plain
    docs = estimate_tokens_for_diff(100, 1, ["docs/FOO.md"], coeff)
    assert docs < plain, (docs, plain)  # docs-only is cheaper per line
    tested = estimate_tokens_for_diff(
        100, 2, ["core/src/main/Foo.scala", "core/src/test/FooSpec.scala"], coeff
    )
    assert tested > plain, (tested, plain)  # scala + tests costs more than scala alone
    assert estimate_tokens_for_diff(0, 0, [], coeff) == 0

    # --- estimate_usd: LiteLLM-shaped fixture, no network ---
    prices = {"claude-sonnet-5": {"input_cost_per_token": 2e-06, "output_cost_per_token": 1e-05}}
    usd = estimate_usd(prices, 1_000_000, model="claude-sonnet-5")
    assert usd == 2.0, usd
    assert estimate_usd(prices, 1_000_000, model="no-such-model") is None
    assert estimate_usd({}, 1_000_000) is None

    # --- format_estimate_trailer: always carries "est." on both figures, never bare ---
    trailer = format_estimate_trailer(912_345, 1.20, 210, "2026-09-05")
    assert trailer.startswith("Cost: ~$1.20 est. ·"), trailer
    assert "tokens est." in trailer, trailer
    assert "210 lines, no session log" in trailer, trailer
    assert trailer.endswith("--estimate 2026-09-05"), trailer
    no_price = format_estimate_trailer(500, None, 3, "2026-09-05")
    assert no_price.startswith("Cost: $n/a est. ·"), no_price

    # --- calibrate() + diff_stats() + estimate_commit(): a synthetic repo, so the fit is a known
    # number instead of depending on this checkout's ever-growing real history ---
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp)
        sh("git", "init", "-q", "-b", "main", cwd=repo)
        sh("git", "config", "user.email", "test@example.com", cwd=repo)
        sh("git", "config", "user.name", "Test", cwd=repo)

        def commit(fname, content, message):
            (repo / fname).write_text(content)
            sh("git", "add", fname, cwd=repo)
            sh("git", "commit", "-q", "-m", message, cwd=repo)
            return sh("git", "rev-parse", "HEAD", cwd=repo).strip()

        # 10 changed lines (all additions), 1,000,000 tokens measured -> ratio 100,000/line.
        commit(
            "a.txt",
            "\n".join(f"line {i}" for i in range(10)) + "\n",
            "first\n\nCost: ~1.0M tokens\n",
        )
        # 100 changed lines, 5,000,000 tokens measured -> ratio 50,000/line.
        commit(
            "b.txt",
            "\n".join(f"line {i}" for i in range(100)) + "\n",
            "second\n\nCost: ~5.0M tokens\n",
        )
        # A "shared bucket" trailer must not enter the calibration set even though it parses.
        commit(
            "c.txt", "x\n", "third\n\nCost: shared bucket $1 · 9.0M tokens — not split further\n"
        )
        # An un-costed commit — the one we'll estimate against the calibration from the other two.
        target = commit(
            "d.txt", "\n".join(f"line {i}" for i in range(20)) + "\n", "fourth: no trailer"
        )

        coeff, stats = calibrate(ref="main", cwd=repo)
        assert stats["n"] == 2, stats  # the shared-bucket commit must be excluded
        assert coeff == statistics.median([100_000, 50_000]), coeff

        prices2 = {DEFAULT_PRICE_MODEL: {"input_cost_per_token": 2e-06}}
        tokens, usd, changed = estimate_commit(target, prices2, coeff, cwd=repo)
        assert changed == 20, changed
        expected_tokens = estimate_tokens_for_diff(20, 1, ["d.txt"], coeff)
        assert tokens == expected_tokens, (tokens, expected_tokens)
        assert (
            usd == round(expected_tokens * 2e-06, 10) or abs(usd - expected_tokens * 2e-06) < 1e-9
        )

        # A repo with zero calibratable commits falls back to the documented default.
        with tempfile.TemporaryDirectory() as tmp2:
            empty_repo = Path(tmp2)
            sh("git", "init", "-q", "-b", "main", cwd=empty_repo)
            sh("git", "config", "user.email", "test@example.com", cwd=empty_repo)
            sh("git", "config", "user.name", "Test", cwd=empty_repo)
            (empty_repo / "x.txt").write_text("x\n")
            sh("git", "add", "x.txt", cwd=empty_repo)
            sh("git", "commit", "-q", "-m", "no cost trailer here", cwd=empty_repo)
            fallback_coeff, fallback_stats = calibrate(ref="main", cwd=empty_repo)
            assert fallback_stats["n"] == 0
            assert fallback_coeff == DEFAULT_TOKENS_PER_LINE

    print(
        f"cost-split self-test: ok (parser {len(cases)} cases, synthetic calibration "
        f"median {coeff:.0f} tokens/line from {stats['n']} commits)"
    )
    return 0


# ---------------------------------------------------------------------------------------------


def build_rows(cs, buckets, order, prices, do_estimate, coeff):
    rows = []
    for key in order:
        b = buckets.get(key)
        meta = next(
            ((br, subj) for _w, br, sha, subj in cs if sha == key), ("", "uncommitted (so far)")
        )
        if not b:
            if key == "uncommitted" or not do_estimate:
                continue
            # No cwd override: `key` is a full sha (unambiguous from any worktree of this repo),
            # but if it were ever a symbolic ref like HEAD, resolving it against repo_root()'s
            # main-checkout path — right for session-log lookup, wrong here — would silently
            # answer for the wrong branch when run from a worktree.
            est = estimate_commit(key, prices, coeff)
            if est is None:
                continue
            tokens, usd, changed = est
            rows.append(
                {
                    "commit": key[:7],
                    "branch": meta[0],
                    "subject": meta[1],
                    "input": 0,
                    "output": 0,
                    "cache_write": 0,
                    "cache_read": 0,
                    "tokens": tokens,
                    "usd": round(usd, 2) if usd is not None else None,
                    "models": ["diff-size estimate"],
                    "sessions": [],
                    "estimated": True,
                    "changed_lines": changed,
                }
            )
            continue
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
                "estimated": False,
            }
        )
    return rows


def main():
    ap = argparse.ArgumentParser(
        description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "stack", nargs="?", help="mip-NNNN: every branch of the stack (default: current branch)"
    )
    ap.add_argument("--session", help="session id prefix (default: every session of this project)")
    ap.add_argument("--json", action="store_true")
    ap.add_argument(
        "--estimate",
        action="store_true",
        help="fill in commits with no logged usage from a diff-size estimate, clearly labelled",
    )
    ap.add_argument(
        "--estimate-commit",
        metavar="SHA",
        help="print just the estimated Cost: trailer for one commit — no session logs needed",
    )
    ap.add_argument(
        "--verbose", action="store_true", help="with --estimate, print the calibration fit"
    )
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()

    if a.self_test:
        sys.exit(self_test())

    root = repo_root()

    if a.estimate_commit:
        # No cwd override here either: calibrate()'s `origin/main` and estimate_commit()'s sha
        # argument both mean the same thing from any worktree, but running them pinned to
        # repo_root() would resolve a symbolic ref like `HEAD` against the *main* checkout's
        # branch, not the one this command was actually run from.
        coeff, stats = calibrate()
        if a.verbose:
            print_calibration(coeff, stats)
        prices = load_prices(root)
        est = estimate_commit(a.estimate_commit, prices, coeff)
        if est is None:
            sys.exit(
                f"cost-split --estimate-commit: {a.estimate_commit} has no diff to estimate from"
            )
        tokens, usd, changed = est
        print(format_estimate_trailer(tokens, usd, changed, dt.date.today().isoformat()))
        return

    pdir = project_dir(root)
    if not pdir.is_dir():
        sys.exit(f"no session logs at {pdir}")
    msgs = messages(pdir, a.session)
    cs = commits(root, a.stack.lower() if a.stack else None)
    if not cs:
        sys.exit("no commits ahead of origin/main on the selected branch(es)")
    prices = load_prices(root)

    coeff, calib_stats = (None, None)
    if a.estimate:
        coeff, calib_stats = calibrate()

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
    # A message counts toward a branch only if it was made *on* that branch (its `gitBranch`),
    # then falls into the first commit whose author date is after it. Without the branch test,
    # every session on the machine that ran before a branch's first commit — other agents,
    # other features — landed on that commit (seen: 335M tokens on a 500-line commit). A message
    # with no `gitBranch` (older logs) keeps the time-only rule.
    # "On that branch" means: the message's branch contains the commit — a commit authored on
    # feat/a and now priced from feat/b (stacked on a) is still paid for by the messages made on
    # feat/a. Computed once per commit from `git branch -a --contains`.
    contains = {
        sha: branches_containing(sha) | branches_with_equivalent(sha) for _w, _b, sha, _s in cs
    }
    ours = {c[1] for c in cs}.union(*contains.values()) if cs else set()
    for ts, session, model, u, mbranch in msgs:
        if mbranch and mbranch not in ours:
            continue
        target = "uncommitted"
        for when, _cbranch, sha, _s in cs:
            if mbranch and mbranch not in contains[sha]:
                continue
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

    rows = build_rows(cs, buckets, order, prices, a.estimate, coeff)

    if a.json:
        print(json.dumps(rows, indent=1))
        return

    if a.estimate and a.verbose:
        print_calibration(coeff, calib_stats)
        print()

    today = dt.date.today().isoformat()
    print(
        f"{'commit':8} {'usd':>9} {'tokens':>11} {'in':>7} {'out':>7} {'cache_w':>8} {'cache_r':>9}  branch / subject"
    )
    any_estimated = False
    for r in rows:
        mark = "*" if r.get("estimated") else ""
        any_estimated = any_estimated or bool(mark)
        usd = f"${r['usd']:.2f}{mark}" if r["usd"] is not None else f"n/a{mark}"
        tokens = f"{r['tokens']:,}{mark}"
        print(
            f"{r['commit']:8} {usd:>9} {tokens:>11} {r['input']:>7,} {r['output']:>7,} {r['cache_write']:>8,} {r['cache_read']:>9,}  {r['branch']} {r['subject'][:60]}"
        )
    if any_estimated:
        print("* diff-size estimate, no session log — scripts/cost-split.py --estimate")

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
            f"  {br}: Cost: {dollars} · {tokens / 1e6:.1f}M tokens, {share} ({', '.join(sorted(models))}) · split by branch and author date, scripts/cost-split.py {today}"
        )

    estimated_rows = [r for r in rows if r.get("estimated")]
    if estimated_rows:
        print("\nCost: trailers for commits with no session log (estimated — paste per commit):")
        for r in estimated_rows:
            trailer = format_estimate_trailer(r["tokens"], r["usd"], r["changed_lines"], today)
            print(f"  {r['commit']}: {trailer}")

    total = sum(r["usd"] or 0 for r in rows)
    print(f"\nsession total ${total:.2f} for {len(rows)} buckets (list prices, LiteLLM table)")


if __name__ == "__main__":
    main()
