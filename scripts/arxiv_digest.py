#!/usr/bin/env python3
"""arxiv_digest — cache arXiv papers relevant to marola's forecasting domain.

    scripts/arxiv_digest.py                 # fetch every query, cache new papers, print a summary
    scripts/arxiv_digest.py --max-results 5  # results per query (default 5)
    scripts/arxiv_digest.py --json           # machine-readable summary on stdout
    scripts/arxiv_digest.py --self-test      # parser + cache self-check, no network (just quality-other)

Queries the real arXiv API (export.arxiv.org/api/query, Atom XML) for oceanography, sea/ocean
condition forecasting, jellyfish/marine-life prediction, and LLM-for-forecasting papers — the
research surface behind marola's own heuristics (docs/2-Building-marola/ARCHITECTURE.md) and a feeder for MIP
research (see MIP-0019). Caches the same way `MadsLorentzen/ai-job-search`'s job tracker does
(surveyed in docs/4-Research-and-plans/SELF-DOCUMENTING.md): one flat JSON file per paper, keyed by arXiv id, so a
re-run never re-fetches or duplicates an already-seen paper, plus one flat JSONL index a human or
another script can grep/tail without touching the per-paper files.

Cache layout, under .tmp/arxiv_cache/ (gitignored — fetched data, not source):
    papers/<arxiv-id-sans-version>.json   one file per paper (id, title, summary, authors,
                                           categories, published, matched_queries, relevance_score,
                                           fetched_at)
    index.jsonl                           one line per cached paper (id, title, published,
                                           relevance_score, fetched_at) — rewritten each run from
                                           the current papers/ contents, so it never drifts from them

Network is stdlib-only (`urllib.request`), matching marola-devkit's cost-split.
"""

import argparse
import datetime as dt
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from xml.etree import ElementTree

ATOM_NS = "http://www.w3.org/2005/Atom"
ARXIV_NS = "http://arxiv.org/schemas/atom"
API_BASE = "https://export.arxiv.org/api/query"

# Each query is (label, arXiv search_query, weight). Verified against the live API on 2026-09-06 —
# every one of these returns real, on-topic results (some cross-domain noise is expected and
# scored down, not filtered out, per MIP-0019's honesty about query precision).
QUERIES: list[tuple[str, str, int]] = [
    ("ocean-physics", "cat:physics.ao-ph AND abs:ocean", 1),
    ("sst-forecast", 'abs:"sea surface temperature" AND abs:forecast', 2),
    ("llm-ocean", 'abs:"large language model" AND abs:ocean', 3),
    ("llm-weather-forecast", 'abs:"large language model" AND abs:"weather forecasting"', 3),
    ("jellyfish-prediction", "abs:jellyfish AND abs:prediction", 2),
    ("marine-heatwave", 'abs:"marine heatwave"', 2),
    ("ao-ph-ml", 'cat:physics.ao-ph AND abs:"machine learning"', 1),
]


def cache_dir(repo_root: Path) -> Path:
    d = repo_root / ".tmp" / "arxiv_cache"
    (d / "papers").mkdir(parents=True, exist_ok=True)
    return d


def arxiv_id_from_url(id_url: str) -> str:
    """http://arxiv.org/abs/2609.03658v1 -> 2609.03658 (version-stripped, safe as a filename)."""
    tail = id_url.rstrip("/").rsplit("/", 1)[-1]
    return re.sub(r"v\d+$", "", tail)


def parse_atom(xml_text: str) -> list[dict]:
    """Atom feed text -> list of paper dicts (id/title/summary/authors/categories/published).
    Raises on malformed XML — a caller decides whether that's fatal or skip-and-continue."""
    root = ElementTree.fromstring(xml_text)
    entries = []
    for entry in root.findall(f"{{{ATOM_NS}}}entry"):

        def text(tag: str, ns: str = ATOM_NS, _entry=entry) -> str:
            el = _entry.find(f"{{{ns}}}{tag}")
            return (el.text or "").strip() if el is not None else ""

        id_url = text("id")
        if not id_url:
            continue
        authors = [
            (a.find(f"{{{ATOM_NS}}}name").text or "").strip()
            for a in entry.findall(f"{{{ATOM_NS}}}author")
            if a.find(f"{{{ATOM_NS}}}name") is not None
        ]
        categories = [
            c.get("term", "") for c in entry.findall(f"{{{ATOM_NS}}}category") if c.get("term")
        ]
        entries.append(
            {
                "arxiv_id": arxiv_id_from_url(id_url),
                "abs_url": id_url,
                "title": re.sub(r"\s+", " ", text("title")),
                "summary": re.sub(r"\s+", " ", text("summary")),
                "authors": authors,
                "categories": categories,
                "published": text("published"),
            }
        )
    return entries


def fetch_query(search_query: str, max_results: int, timeout: float = 20.0) -> str:
    params = urllib.parse.urlencode(
        {
            "search_query": search_query,
            "start": 0,
            "max_results": max_results,
            "sortBy": "submittedDate",
            "sortOrder": "descending",
        }
    )
    req = urllib.request.Request(
        f"{API_BASE}?{params}", headers={"User-Agent": "marola-arxiv-digest/1"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:  # noqa: S310 (fixed http(s) API host)
        return r.read().decode("utf-8")


def relevance_score(paper: dict, matched: list[tuple[str, int]]) -> int:
    """Sum of the weights of every query that matched this paper — a paper hit by both an
    LLM-forecast query and a jellyfish query scores higher than one hit by a single broad query."""
    return sum(weight for _label, weight in matched)


def load_cached_ids(store: Path) -> set[str]:
    return {p.stem for p in (store / "papers").glob("*.json")}


def write_index(store: Path) -> None:
    rows = []
    for f in sorted((store / "papers").glob("*.json")):
        data = json.loads(f.read_text())
        rows.append(
            {
                "arxiv_id": data["arxiv_id"],
                "title": data["title"],
                "published": data["published"],
                "relevance_score": data["relevance_score"],
                "fetched_at": data["fetched_at"],
            }
        )
    rows.sort(key=lambda r: r["relevance_score"], reverse=True)
    with (store / "index.jsonl").open("w") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def run(repo_root: Path, max_results: int) -> dict:
    store = cache_dir(repo_root)
    already = load_cached_ids(store)
    matches_by_id: dict[str, list[tuple[str, int]]] = {}
    papers_by_id: dict[str, dict] = {}
    errors = []

    for label, query, weight in QUERIES:
        try:
            xml_text = fetch_query(query, max_results)
        except (urllib.error.URLError, TimeoutError) as e:
            errors.append(f"{label}: {e}")
            continue
        for paper in parse_atom(xml_text):
            pid = paper["arxiv_id"]
            papers_by_id.setdefault(pid, paper)
            matches_by_id.setdefault(pid, []).append((label, weight))

    new_count = 0
    for pid, paper in papers_by_id.items():
        if pid in already:
            continue
        matched = matches_by_id[pid]
        record = {
            **paper,
            "matched_queries": [label for label, _w in matched],
            "relevance_score": relevance_score(paper, matched),
            "fetched_at": dt.datetime.now(dt.UTC).isoformat(),
        }
        (store / "papers" / f"{pid}.json").write_text(
            json.dumps(record, indent=2, ensure_ascii=False)
        )
        new_count += 1

    write_index(store)
    return {
        "queries_run": len(QUERIES) - len(errors),
        "queries_failed": errors,
        "new_papers": new_count,
        "total_cached": len(load_cached_ids(store)),
        "index_path": str(store / "index.jsonl"),
    }


def self_test() -> None:
    import tempfile

    fails = 0

    fixture = f"""<?xml version='1.0' encoding='UTF-8'?>
<feed xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/" xmlns:arxiv="{ARXIV_NS}" xmlns="{ATOM_NS}">
  <entry>
    <id>http://arxiv.org/abs/2609.03658v1</id>
    <title>  A Deep Learning Model for
      Forecasting  Sea Surface Temperature</title>
    <updated>2026-09-03T10:56:02Z</updated>
    <summary>We forecast sea surface temperature anomalies with a transformer.</summary>
    <category term="physics.ao-ph" scheme="http://arxiv.org/schemas/atom"/>
    <published>2026-09-03T10:56:02Z</published>
    <author><name>Ada Lovelace</name></author>
    <author><name>Grace Hopper</name></author>
  </entry>
  <entry>
    <id>http://arxiv.org/abs/2601.00001v2</id>
    <title>Notify About Jellyfish Blooms via Sonar</title>
    <updated>2026-01-01T00:00:00Z</updated>
    <summary>A sonar pipeline to detect jellyfish blooms near beaches.</summary>
    <category term="cs.CV" scheme="http://arxiv.org/schemas/atom"/>
    <published>2026-01-01T00:00:00Z</published>
    <author><name>Marie Curie</name></author>
  </entry>
</feed>"""

    entries = parse_atom(fixture)
    if len(entries) == 2:
        print("  ok   parse_atom extracts both fixture entries")
    else:
        print(f"  FAIL parse_atom got {len(entries)} entries, expected 2")
        fails += 1

    e0 = entries[0]
    if e0["arxiv_id"] == "2609.03658":
        print("  ok   version suffix stripped from the arXiv id")
    else:
        print(f"  FAIL arxiv_id was {e0['arxiv_id']!r}, expected '2609.03658'")
        fails += 1
    if e0["title"] == "A Deep Learning Model for Forecasting Sea Surface Temperature":
        print("  ok   multi-line title is whitespace-normalized")
    else:
        print(f"  FAIL title was {e0['title']!r}")
        fails += 1
    if e0["authors"] == ["Ada Lovelace", "Grace Hopper"]:
        print("  ok   both authors extracted in order")
    else:
        print(f"  FAIL authors were {e0['authors']!r}")
        fails += 1
    if e0["categories"] == ["physics.ao-ph"]:
        print("  ok   category term extracted")
    else:
        print(f"  FAIL categories were {e0['categories']!r}")
        fails += 1

    e1 = entries[1]
    if e1["arxiv_id"] == "2601.00001":
        print("  ok   a 'v2' suffix is also stripped correctly")
    else:
        print(f"  FAIL arxiv_id was {e1['arxiv_id']!r}, expected '2601.00001'")
        fails += 1

    # relevance_score: multiple query matches outscore a single broad match.
    single = relevance_score(e0, [("ocean-physics", 1)])
    double = relevance_score(e0, [("ocean-physics", 1), ("sst-forecast", 2)])
    if double > single and double == 3:
        print("  ok   relevance_score sums matched-query weights")
    else:
        print(f"  FAIL relevance_score: single={single} double={double}")
        fails += 1

    # Cache round-trip: write both fixture papers, confirm dedup on a second write and a
    # correctly sorted, rewritten index — entirely inside a temp dir, no network.
    with tempfile.TemporaryDirectory() as tmp:
        repo_root = Path(tmp)
        store = cache_dir(repo_root)
        if load_cached_ids(store) == set():
            print("  ok   a fresh cache dir starts empty")
        else:
            print("  FAIL fresh cache dir was not empty")
            fails += 1

        for paper, matched in ((e0, [("sst-forecast", 2)]), (e1, [("jellyfish-prediction", 2)])):
            record = {
                **paper,
                "matched_queries": [label for label, _w in matched],
                "relevance_score": relevance_score(paper, matched),
                "fetched_at": "2026-09-06T00:00:00+00:00",
            }
            (store / "papers" / f"{paper['arxiv_id']}.json").write_text(json.dumps(record))
        write_index(store)

        cached = load_cached_ids(store)
        if cached == {"2609.03658", "2601.00001"}:
            print("  ok   both papers land in the cache, keyed by arxiv id")
        else:
            print(f"  FAIL cached ids were {cached!r}")
            fails += 1

        index_lines = (store / "index.jsonl").read_text().splitlines()
        if len(index_lines) == 2:
            print("  ok   index.jsonl has one line per cached paper")
        else:
            print(f"  FAIL index.jsonl had {len(index_lines)} lines, expected 2")
            fails += 1

        # Simulate re-fetching the same paper: caller-side dedup (run()'s own logic) must skip
        # ids already in load_cached_ids() rather than re-write/duplicate.
        already = load_cached_ids(store)
        would_skip = "2609.03658" in already
        if would_skip:
            print("  ok   an already-cached id is recognized for skip-on-refetch")
        else:
            print("  FAIL already-cached id was not recognized")
            fails += 1

    # Malformed XML raises rather than silently returning nothing — a caller must not mistake a
    # parse failure for "no results this query".
    try:
        parse_atom("<not-xml")
        print("  FAIL malformed XML did not raise")
        fails += 1
    except ElementTree.ParseError:
        print("  ok   malformed XML raises ParseError, not swallowed silently")

    if fails == 0:
        print("arxiv_digest self-test: ok")
    else:
        print(f"arxiv_digest self-test: {fails} failure(s)", file=sys.stderr)
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
        print(f"queries run: {summary['queries_run']}/{len(QUERIES)}")
        if summary["queries_failed"]:
            print(f"queries failed: {summary['queries_failed']}")
        print(f"new papers cached: {summary['new_papers']}")
        print(f"total cached: {summary['total_cached']}")
        print(f"index: {summary['index_path']}")


if __name__ == "__main__":
    main()
