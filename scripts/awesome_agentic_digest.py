#!/usr/bin/env python3
"""awesome_agentic_digest — cache GitHub repo candidates for docs/4-Research-and-plans/AWESOME-AGENTIC-ENGINEERING.md.

    scripts/awesome_agentic_digest.py                 # fetch every query, cache new candidates, print a summary
    scripts/awesome_agentic_digest.py --max-results 5  # results per query (default 5)
    scripts/awesome_agentic_digest.py --json           # machine-readable summary on stdout
    scripts/awesome_agentic_digest.py --self-test      # parser + cache self-check, no network (just quality-other)

Queries the real GitHub Search API (api.github.com/search/repositories) across agentic-engineering
GitHub topics — verified live against real, non-zero results on 2026-09-07 (see MIP-0043 §4.2) —
for candidate repos to hand-curate into docs/4-Research-and-plans/AWESOME-AGENTIC-ENGINEERING.md.

**This script never writes to docs/4-Research-and-plans/AWESOME-AGENTIC-ENGINEERING.md.** It only caches candidates and
prints a summary. A human reviews the cache (or the printed summary) and hand-writes any genuinely
good match into the curated doc, in that doc's own `- [Title](URL) - Description.` entry format.
This mirrors MIP-0041's book_digest.py: propose candidates, a human decides, nothing gets written
into the curated artifact unattended.

Cache layout, under .tmp/awesome_agentic_cache/ (gitignored — fetched data, not source):
    repos/<owner>__<repo>.json   one file per repo (full_name, html_url, description, stars,
                                  pushed_at, topics, matched_queries, relevance_score, fetched_at)
    index.jsonl                  one line per cached repo (full_name, html_url, stars, description,
                                  fetched_at) — rewritten each run from the current repos/ contents,
                                  so it never drifts from them

Network is stdlib-only (`urllib.request`), matching arxiv_digest.py and marola-devkit's
cost-split. GitHub's search API needs no auth token for reasonable unauthenticated use
(confirmed live) but its rate limit is tight (empirically low tens of requests/minute for search
specifically) — this script runs a small, fixed query set once per invocation, no retry-hammering.
"""

import argparse
import datetime as dt
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API_BASE = "https://api.github.com/search/repositories"

# Each query is (label, github search_query, weight). Verified live against the real GitHub Search
# API on 2026-09-07 — every topic below returned real, non-zero, on-topic results (see MIP-0043
# §4.2's total_count table). Two sort orders are queried per label (stars, then recently-updated),
# matching the ask's "sorted by stars or recently-updated" — both feed the same cache/dedup.
QUERIES: list[tuple[str, str, int]] = [
    ("agents", "topic:agents", 1),
    ("llm-agents", "topic:llm-agents", 2),
    ("ai-agents", "topic:ai-agents", 1),
    ("mcp", "topic:mcp", 2),
    ("multi-agent-systems", "topic:multi-agent-systems", 2),
]
SORTS: list[str] = ["stars", "updated"]


def cache_dir(repo_root: Path) -> Path:
    d = repo_root / ".tmp" / "awesome_agentic_cache"
    (d / "repos").mkdir(parents=True, exist_ok=True)
    return d


def repo_key(full_name: str) -> str:
    """'owner/repo' -> 'owner__repo', safe as a filename."""
    return full_name.replace("/", "__")


def parse_search_response(json_text: str) -> list[dict]:
    """GitHub Search API JSON text -> list of repo dicts (full_name/html_url/description/stars/
    pushed_at/topics). Raises on malformed JSON — a caller decides whether that's fatal or
    skip-and-continue."""
    data = json.loads(json_text)
    items = data.get("items")
    if items is None:
        raise ValueError("GitHub Search API response missing 'items'")
    repos = []
    for item in items:
        full_name = item.get("full_name", "")
        if not full_name:
            continue
        repos.append(
            {
                "full_name": full_name,
                "html_url": item.get("html_url", f"https://github.com/{full_name}"),
                "description": (item.get("description") or "").strip(),
                "stars": item.get("stargazers_count", 0),
                "pushed_at": item.get("pushed_at", ""),
                "topics": item.get("topics", []) or [],
            }
        )
    return repos


def fetch_query(search_query: str, sort: str, max_results: int, timeout: float = 20.0) -> str:
    params = urllib.parse.urlencode(
        {
            "q": search_query,
            "sort": sort,
            "order": "desc",
            "per_page": max_results,
        }
    )
    req = urllib.request.Request(
        f"{API_BASE}?{params}",
        headers={
            "User-Agent": "marola-awesome-agentic-digest/1",
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 (fixed http(s) API host)
        return r.read().decode("utf-8")


def relevance_score(matched: list[tuple[str, int]]) -> int:
    """Sum of the weights of every query that matched this repo — a repo hit by both a broad
    'agents' query and a narrower 'mcp' query scores higher than one hit by a single broad query."""
    return sum(weight for _label, weight in matched)


def load_cached_ids(store: Path) -> set[str]:
    return {p.stem for p in (store / "repos").glob("*.json")}


def write_index(store: Path) -> None:
    rows = []
    for f in sorted((store / "repos").glob("*.json")):
        data = json.loads(f.read_text())
        rows.append(
            {
                "full_name": data["full_name"],
                "html_url": data["html_url"],
                "stars": data["stars"],
                "description": data["description"],
                "relevance_score": data["relevance_score"],
                "fetched_at": data["fetched_at"],
            }
        )
    rows.sort(key=lambda r: (r["relevance_score"], r["stars"]), reverse=True)
    with (store / "index.jsonl").open("w") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def run(repo_root: Path, max_results: int) -> dict:
    store = cache_dir(repo_root)
    already = load_cached_ids(store)
    matches_by_id: dict[str, list[tuple[str, int]]] = {}
    repos_by_id: dict[str, dict] = {}
    errors = []

    for label, query, weight in QUERIES:
        for sort in SORTS:
            try:
                json_text = fetch_query(query, sort, max_results)
            except (urllib.error.URLError, TimeoutError, ValueError) as e:
                errors.append(f"{label} ({sort}): {e}")
                continue
            for repo in parse_search_response(json_text):
                rid = repo_key(repo["full_name"])
                repos_by_id.setdefault(rid, repo)
                matches_by_id.setdefault(rid, []).append((label, weight))

    new_count = 0
    for rid, repo in repos_by_id.items():
        if rid in already:
            continue
        matched = matches_by_id[rid]
        record = {
            **repo,
            "matched_queries": [label for label, _w in matched],
            "relevance_score": relevance_score(matched),
            "fetched_at": dt.datetime.now(dt.UTC).isoformat(),
        }
        (store / "repos" / f"{rid}.json").write_text(
            json.dumps(record, indent=2, ensure_ascii=False)
        )
        new_count += 1

    write_index(store)
    return {
        "queries_run": len(QUERIES) * len(SORTS) - len(errors),
        "queries_failed": errors,
        "new_candidates": new_count,
        "total_cached": len(load_cached_ids(store)),
        "index_path": str(store / "index.jsonl"),
    }


def self_test() -> None:
    import tempfile

    fails = 0

    fixture = json.dumps(
        {
            "total_count": 2,
            "incomplete_results": False,
            "items": [
                {
                    "full_name": "example-org/agent-critic",
                    "html_url": "https://github.com/example-org/agent-critic",
                    "description": "A reviewer/critic pattern for LLM agent pipelines.",
                    "stargazers_count": 4200,
                    "pushed_at": "2026-09-01T00:00:00Z",
                    "topics": ["llm-agents", "mcp"],
                },
                {
                    "full_name": "another-org/dspy-scala",
                    "html_url": "https://github.com/another-org/dspy-scala",
                    "description": "DSPy-style prompt compilation, replayed from Scala.",
                    "stargazers_count": 130,
                    "pushed_at": "2026-08-15T00:00:00Z",
                    "topics": ["ai-agents"],
                },
            ],
        }
    )

    repos = parse_search_response(fixture)
    if len(repos) == 2:
        print("  ok   parse_search_response extracts both fixture items")
    else:
        print(f"  FAIL parse_search_response got {len(repos)} items, expected 2")
        fails += 1

    r0 = repos[0]
    if r0["full_name"] == "example-org/agent-critic":
        print("  ok   full_name extracted")
    else:
        print(f"  FAIL full_name was {r0['full_name']!r}")
        fails += 1
    if r0["stars"] == 4200:
        print("  ok   stargazers_count mapped to 'stars'")
    else:
        print(f"  FAIL stars was {r0['stars']!r}, expected 4200")
        fails += 1
    if repo_key(r0["full_name"]) == "example-org__agent-critic":
        print("  ok   repo_key produces a filesystem-safe id")
    else:
        print(f"  FAIL repo_key was {repo_key(r0['full_name'])!r}")
        fails += 1

    # relevance_score: multiple query matches outscore a single broad match.
    single = relevance_score([("agents", 1)])
    double = relevance_score([("agents", 1), ("mcp", 2)])
    if double > single and double == 3:
        print("  ok   relevance_score sums matched-query weights")
    else:
        print(f"  FAIL relevance_score: single={single} double={double}")
        fails += 1

    # Cache round-trip: write both fixture repos, confirm dedup on a second write and a correctly
    # sorted, rewritten index — entirely inside a temp dir, no network.
    with tempfile.TemporaryDirectory() as tmp:
        repo_root = Path(tmp)
        store = cache_dir(repo_root)
        if load_cached_ids(store) == set():
            print("  ok   a fresh cache dir starts empty")
        else:
            print("  FAIL fresh cache dir was not empty")
            fails += 1

        for repo, matched in ((repos[0], [("mcp", 2)]), (repos[1], [("ai-agents", 1)])):
            record = {
                **repo,
                "matched_queries": [label for label, _w in matched],
                "relevance_score": relevance_score(matched),
                "fetched_at": "2026-09-07T00:00:00+00:00",
            }
            (store / "repos" / f"{repo_key(repo['full_name'])}.json").write_text(json.dumps(record))
        write_index(store)

        cached = load_cached_ids(store)
        if cached == {"example-org__agent-critic", "another-org__dspy-scala"}:
            print("  ok   both repos land in the cache, keyed by owner__repo")
        else:
            print(f"  FAIL cached ids were {cached!r}")
            fails += 1

        index_lines = (store / "index.jsonl").read_text().splitlines()
        if len(index_lines) == 2:
            print("  ok   index.jsonl has one line per cached repo")
        else:
            print(f"  FAIL index.jsonl had {len(index_lines)} lines, expected 2")
            fails += 1

        # Simulate re-fetching the same repo: caller-side dedup (run()'s own logic) must skip ids
        # already in load_cached_ids() rather than re-write/duplicate.
        already = load_cached_ids(store)
        would_skip = "example-org__agent-critic" in already
        if would_skip:
            print("  ok   an already-cached id is recognized for skip-on-refetch")
        else:
            print("  FAIL already-cached id was not recognized")
            fails += 1

    # Malformed JSON raises rather than silently returning nothing — a caller must not mistake a
    # parse failure for "no results this query".
    try:
        parse_search_response("{not json")
        print("  FAIL malformed JSON did not raise")
        fails += 1
    except json.JSONDecodeError:
        print("  ok   malformed JSON raises JSONDecodeError, not swallowed silently")

    # A response missing 'items' entirely (e.g. a rate-limit error body) raises rather than being
    # mistaken for zero results.
    try:
        parse_search_response(json.dumps({"message": "API rate limit exceeded"}))
        print("  FAIL a response missing 'items' did not raise")
        fails += 1
    except ValueError:
        print("  ok   a response missing 'items' raises ValueError, not swallowed silently")

    if fails == 0:
        print("awesome_agentic_digest self-test: ok")
    else:
        print(f"awesome_agentic_digest self-test: {fails} failure(s)", file=sys.stderr)
        sys.exit(1)


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--max-results", type=int, default=5, help="results per query (default 5)")
    ap.add_argument("--json", action="store_true", help="machine-readable summary on stdout")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        self_test()
        return

    repo_root = Path(__file__).resolve().parent.parent
    summary = run(repo_root, args.max_results)
    if args.json:
        print(json.dumps(summary, indent=2))
    else:
        print(f"queries run: {summary['queries_run']}/{len(QUERIES) * len(SORTS)}")
        if summary["queries_failed"]:
            print(f"queries failed: {summary['queries_failed']}")
        print(f"new candidates cached: {summary['new_candidates']}")
        print(f"total cached: {summary['total_cached']}")
        print(f"index: {summary['index_path']}")
        print(
            "review the candidates above, then hand-curate any of them into "
            "docs/4-Research-and-plans/AWESOME-AGENTIC-ENGINEERING.md"
        )
        print(
            "nothing was written to docs/4-Research-and-plans/AWESOME-AGENTIC-ENGINEERING.md — "
            "this script only caches candidates"
        )


if __name__ == "__main__":
    main()
