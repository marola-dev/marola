# MIP-0043: `docs/AWESOME-AGENTIC-ENGINEERING.md` — a curated awesome-list, plus a human-gated candidate digest

| | |
|---|---|
| **Status** | Implemented — merged as PR #222 (`docs/AWESOME-AGENTIC-ENGINEERING.md`, `scripts/awesome_agentic_digest.py`, wired into `quality-other`) |
| **Author** | Claude (agent), for M. Hoffmann (request: a public curated "awesome list" of agentic-engineering projects, kept current by a semi-automated routine, plus a real "top 10 repos similar to marola" section) |
| **Created** | 2026-09-07 |
| **Phase** | 0 (`ARCHITECTURE.md` §11) — a repo/community doc plus a maintainer dev-tool script, not a Telegram/product feature. No earlier-phase prerequisite |
| **Related** | `MIP-0017` (agentic-tooling survey — same dev-tooling category, Phase 0, S effort, `infra/dev-loop` gain), `MIP-0019` (`scripts/arxiv_digest.py` — the exact script conventions this MIP's script follows: stdlib-only `urllib.request`, `.tmp/`-cached store, `--self-test`, `--json`, wired into `quality-other`), `MIP-0041` (soft book ingestion — the "propose candidates, human curates, never auto-write" gate this MIP reuses as its own governing principle), `docs/AI-500-MAPPING.md` §4 (human-confirmation gate for autonomous/proactive behavior — the same reasoning applied here to "don't let a script silently edit a curated public doc") |
| **Effort** | S — one new Markdown doc (hand-curated, not generated), one new Python script matching an existing template almost line-for-line, one `justfile` wiring line. No new Scala module, no new runtime dependency, no paid API |
| **Gain** | `community/outreach` (a public reference doc useful to anyone evaluating agentic-engineering tooling, and a discoverability surface for marola itself via its own "similar repos" section); `infra/dev-loop` (a repeatable way to find new entries instead of ad hoc memory) |
| **Effort vs Gain** | cheap win — self-contained, file-based, no Azure spend, no Phase gate, no dependency on marola's own pipeline |
| **Depends on** | Nothing blocking. Reuses `scripts/arxiv_digest.py`'s cache/self-test/CLI shape as precedent (MIP-0019), not as code |
| **Blocked by** | none |
| **Risk** | An unreviewed GitHub Search API result gets treated as a good match by inertia — a repo that ranks by stars/recency can be off-topic (parked, abandoned, or keyword-matched but architecturally unrelated) and get curated in without a human actually opening its README. The whole design below (§5) exists to make that require a deliberate manual step, exactly as MIP-0041's book-digest gate does — the script never writes to the curated doc itself |
| **Cost so far** | — (nothing from this MIP has merged yet) |

## 1. Summary

This MIP adds `docs/AWESOME-AGENTIC-ENGINEERING.md`, a public curated list of agentic-engineering
projects (agent frameworks, MCP tooling, DSPy/prompt-compilation, critic/reviewer-pattern pipelines,
spec/RFC-driven agent dev-loops) in the `sindresorhus/awesome` "Awesome List" convention — verified
live against `github.com/sindresorhus/awesome` itself, not guessed from memory — plus a companion
script, `scripts/awesome_agentic_digest.py`, that queries the real GitHub Search API for candidate
repos and caches them for a human to manually curate into the doc. The script never writes to the
curated doc itself, following the same human-gated pattern MIP-0041 established for book-derived
rule changes. The doc also carries a "Top 10 GitHub repos most similar to marola" section, hand-
researched and verified this session (each repo's real GitHub page fetched, not invented).

## 2. Motivation

marola has no public-facing curated reference doc today — `docs/AGENT-FRAMEWORKS-SURVEY.md` and
`MIP-0017` survey tooling *for marola's own dev-loop*, a narrower, internal-facing scope. A public
awesome-list serves a different purpose: community discoverability (people finding marola via a
"repos like this" section) and a durable, browsable reference for agentic-engineering tooling in
general, kept current the same low-effort way `scripts/arxiv_digest.py` already keeps marola's own
research surface current — a script that surfaces candidates, never a script that decides.

## 3. User-visible change

"User" here is a reader of the public repo (community/outreach), and secondarily the maintainer
running the digest script (dev-loop). No change to the Telegram bot, the CLI, or any scored output.

Before: no such doc exists; finding new agentic-engineering projects to reference means ad hoc
memory or a fresh web search each time.

After:

```
$ python3 scripts/awesome_agentic_digest.py
queries run: 5/5
new candidates cached: 12
total cached: 12
index: .tmp/awesome_agentic_cache/index.jsonl
review the candidates above, then hand-curate any of them into docs/AWESOME-AGENTIC-ENGINEERING.md
nothing was written to docs/AWESOME-AGENTIC-ENGINEERING.md — this script only caches candidates
```

The maintainer reads the cached candidates (or `--json` output), opens the ones that look real and
relevant, and hand-writes an entry into the doc — same one-line-description format the doc's own
existing entries use, per the `sindresorhus/awesome` convention.

## 4. Data sources and dependencies reviewed

### 4.1 `sindresorhus/awesome` — the Awesome List convention (fetched 2026-09-07)

- `github.com/sindresorhus/awesome` (README, fetched live 2026-09-07): the canonical list is
  itself the reference implementation — a `# Awesome <Name>` title, a Contents/TOC section,
  categorized link sections, a Contributing pointer, and a License section (**CC0-1.0**, confirmed
  in the repo's own footer).
- `raw.githubusercontent.com/sindresorhus/awesome/main/pull_request_template.md` (fetched live
  2026-09-07): the authoritative structural checklist for a conformant awesome list —
  - The badge, right of (or centered under) the heading:
    `[![Awesome](https://awesome.re/badge.svg)](https://awesome.re)` (confirmed verbatim by
    fetching `raw.githubusercontent.com/sindresorhus/awesome/main/readme.md` the same session).
  - `# Awesome Name of List` as the title (title case).
  - A section literally titled **Contents** (not "Table of Contents"), placed first, excluding
    Contributing/Footnotes from its own listing.
  - A succinct, objective one-line description of the *subject*, not the list, at the top.
  - Each entry: `- [Title](URL) - Description.` — description starts uppercase, ends with a
    period, states what the project *is*, no marketing language.
  - A `contributing.md` (Contributing section, can point to it) and a License section/file — CC0
    "strongly recommended," any Creative Commons license acceptable; MIT/BSD/Apache/GPL/Unlicense
    explicitly **not** acceptable for the list's own text (this does not license the projects it
    links to, only the list's own curation text).
- `raw.githubusercontent.com/sindresorhus/awesome/main/create-list.md` and `contributing.md`
  (fetched live 2026-09-07): mostly process guidance (30-day minimum list maturity before
  submitting *to* the master `sindresorhus/awesome` list itself) — not applicable here, since this
  MIP does not propose submitting `docs/AWESOME-AGENTIC-ENGINEERING.md` to that master list, only
  following its README-shape convention for marola's own doc.

**Pick**: follow the structural convention (badge, Contents, categorized sections with
`- [Title](URL) - Description.` entries, Contributing, License/CC0) for `docs/AWESOME-AGENTIC-
ENGINEERING.md`'s own shape. Not submitting to the real `sindresorhus/awesome` list itself — out of
scope for this MIP, a possible future step once the list has enough content (their own 30-day
maturity guidance).

### 4.2 GitHub Search API (fetched 2026-09-07)

`api.github.com/search/repositories` — confirmed live, unauthenticated, returns real results with
no token (a `User-Agent` header is required by GitHub's own API etiquette, no `Authorization`
header needed for reasonable use). Verified topics, each queried live via
`?q=topic:<name>&per_page=1` and confirmed non-zero, on-topic `total_count`:

| Topic | `total_count` (2026-09-07) |
|---|---|
| `topic:agents` | 16,581 |
| `topic:llm-agents` | 5,079 |
| `topic:ai-agents` | 87,431 |
| `topic:mcp` | 72,890 |
| `topic:multi-agent-systems` | 5,302 |

What was **not** checked: GitHub's unauthenticated rate limit ceiling itself (documented elsewhere
as 10 requests/minute for search endpoints specifically, tighter than the general 60/hour
unauthenticated REST limit) — the script's own design (§5) assumes this and is conservative (few
queries, one run at a time, no retry-hammering), but the exact current numeric limit was not
independently re-confirmed against GitHub's docs this session; if a future run hits `403`/`422`
with a rate-limit message, that confirms it empirically and the script's error handling (mirroring
`arxiv_digest.py`'s `queries_failed` list) surfaces it rather than crashing.

## 5. Design

**New files:**

- `docs/AWESOME-AGENTIC-ENGINEERING.md` — hand-curated, following the `sindresorhus/awesome` shape
  from §4.1: title + badge, one-line scope description, Contents, categorized sections (agent
  frameworks/orchestration, MCP tooling, DSPy & prompt compilation, critic/reviewer-pattern &
  multi-agent pipelines, spec/RFC-driven agent dev-loops, local-first/Ollama-based agents, cost- and
  usage-tracked agent development), a **Top 10 GitHub repos most similar to marola** section (hand-
  researched, not digest-script output — each entry states which axis(es) — domain / architecture /
  philosophy — it matches marola on and why), a Contributing section (pointing at
  `scripts/awesome_agentic_digest.py`'s human-curation workflow, this section), and a License
  section (CC0-1.0, for the list's own curation text only — matching §4.1's finding).
- `scripts/awesome_agentic_digest.py` — a maintainer dev-tool, following `scripts/arxiv_digest.py`'s
  conventions (MIP-0019) near-exactly:
  - stdlib-only network (`urllib.request`), no third-party dependency.
  - `QUERIES: list[tuple[str, str, int]]` — one row per GitHub topic/keyword query from §4.2, each
    with a human label and a relevance weight, verified live the same way `arxiv_digest.py`'s
    `QUERIES` comment records its own verification date.
  - Queries `api.github.com/search/repositories?q=<query>&sort=stars&order=desc` (and a second pass
    `sort=updated` for "recently active," both parameterized, matching the ask's "sorted by stars or
    recently-updated").
  - A `.tmp/awesome_agentic_cache/` store (already covered by the existing blanket `/.tmp/*`
    `.gitignore` rule — no new gitignore entry needed), one JSON file per repo keyed by
    `owner__repo`, plus a rewritten `index.jsonl` — same two-tier cache shape as `arxiv_digest.py`'s
    `papers/`/`index.jsonl`, so a re-run never re-fetches or duplicates an already-cached repo.
  - `--max-results` (per query, default matching `arxiv_digest.py`'s spirit), `--json` (machine-
    readable summary), `--self-test` (fixture-based, **no live network call**, parses a canned
    GitHub Search API JSON response fixture and exercises the cache round-trip/dedup — the same
    shape as `arxiv_digest.py`'s `self_test()`).
  - **Never writes to `docs/AWESOME-AGENTIC-ENGINEERING.md`.** Its only output is the cache plus a
    stdout/`--json` summary — enforced by the script's own design (no code path opens that file for
    writing) and stated explicitly in its module docstring and its plain-text summary output, the
    same "review by hand" framing MIP-0041's `book_digest.py` uses for `AGENTS.md`.
- `justfile`: one new line in `quality-other`,
  `python3 scripts/awesome_agentic_digest.py --self-test`, in the same block as
  `scripts/arxiv_digest.py --self-test` (MIP-0019's own precedent for where a new digest script's
  self-test line goes).

**Not changed:** no Scala module, no `core`/`local`/`azure`/`cli` file, no `AppConfig` entry, no
Swimability/Recommender/MCP-server surface. This is a docs+`scripts/` change only.

## 6. Scoring / safety impact

None. No change to `Swimability.score`, thresholds, or any user-facing recommendation logic.

## 7. Verification plan

- `scripts/awesome_agentic_digest.py --self-test` — fixture-based (a canned GitHub Search API JSON
  response), no network, exercised via `just quality-other`; asserts: JSON-response parsing, the
  cache round-trip/dedup (a second write of the same repo id does not duplicate), `index.jsonl`
  correctly rewritten and sorted, and a malformed-JSON response raising rather than being silently
  swallowed (mirroring `arxiv_digest.py self_test()`'s malformed-XML case).
- A live run, `python3 scripts/awesome_agentic_digest.py`, reproduces real candidates against the
  five verified topics in §4.2 and writes nothing to `docs/AWESOME-AGENTIC-ENGINEERING.md`
  (confirmed by diffing that file before/after a live run).
- `just quality` green (ruff + the new self-test wired into `quality-other`).
- Manual read-through of `docs/AWESOME-AGENTIC-ENGINEERING.md` against the `sindresorhus/awesome`
  `pull_request_template.md` checklist from §4.1 (badge present, Contents section first, each entry
  `- [Title](URL) - Description.`, Contributing/License sections present).

## 8. Risks, limitations, and honest caveats

- GitHub Search API's topic/keyword search has real precision limits, same caveat MIP-0019 records
  for arXiv — a repo can carry `topic:ai-agents` while being unmaintained, off-topic in practice, or
  a fork with no original content. The digest script surfaces raw candidates; it does not vet them.
  A human must open a candidate's actual README before curating it in — the script's own printed
  output says this explicitly.
- Unauthenticated GitHub Search API rate limits are tight (empirically low tens of requests per
  minute per §4.2) — the script is designed for occasional manual runs, not a scheduled/CI job in
  this MIP's v1. A future MIP could add a GitHub token (`GH_TOKEN`, already used by `just uprd` per
  `AGENTS.md`) for a higher ceiling if this becomes a recurring pain point; out of scope here.
- The "Top 10 similar repos" section is a point-in-time, hand-verified snapshot (2026-09-07) — star
  counts and repo activity will drift; it is not re-generated by the digest script (the digest
  script's query set targets general agentic-engineering discovery, not "find repos like marola"
  specifically, a different and harder search problem left as a future refinement, not built here).

## 9. Alternatives considered

- **A GitHub Actions workflow that auto-opens a PR with digest results**: rejected — this MIP's
  entire design principle (§5, following MIP-0041) is that nothing gets written into the curated
  doc unattended; an auto-PR still requires the same human review step a local script run does, with
  more moving parts (CI credentials, a bot identity) for no real gain at this stage.
- **A `--write` flag that appends candidates directly to the doc**: rejected — this is exactly the
  failure mode §8's first bullet and MIP-0041's own risk section warn about: an unvetted GitHub
  Search API result landing in a public curated doc by inertia rather than by a human's judgment
  that it genuinely belongs.
- **Do nothing (no such doc)**: loses the community/outreach value and the discoverability the "top
  10 similar repos" section provides for marola itself; low effort to build, so not worth deferring.

## 10. Exam-coverage mapping

None directly — this is a community/outreach and dev-tooling doc, not an AI-103/AI-500 exam-domain
build. (Loosely touches AI-103's general theme of evaluating/comparing AI tooling ecosystems, but
not a scored mapping row.)

## 11. Open questions

- Should `docs/README.md`'s doc-index table get a row for this new file? (Decided in this MIP's own
  implementation: yes — it's a `docs/*.md` file like any other, and the doc index is meant to be
  complete. Kind: *reference*, no MIP material inside beyond this MIP itself.)
- Whether to eventually submit `docs/AWESOME-AGENTIC-ENGINEERING.md` to the real
  `sindresorhus/awesome` master list once it has enough content and has matured per their own 30-day
  guidance (§4.1) — not decided here, a future call once the list has grown past its initial seed.

## Appendix

### Checked live

- `github.com/sindresorhus/awesome` — fetched 2026-09-07: confirmed README structure (Contents
  section, categorized links, Contributing/License, CC0-1.0 license badge/footer).
- `raw.githubusercontent.com/sindresorhus/awesome/main/pull_request_template.md` — fetched
  2026-09-07: full structural checklist (badge markdown, `# Awesome Name of List` title, `Contents`
  section name/position, entry format `- [Title](URL) - Description.`, license rules: CC0 strongly
  recommended, MIT/BSD/Apache/GPL/Unlicense not acceptable).
- `raw.githubusercontent.com/sindresorhus/awesome/main/readme.md` — fetched 2026-09-07: confirmed
  the exact badge markdown, `[![Awesome](https://awesome.re/badge.svg)](https://awesome.re)`.
- `raw.githubusercontent.com/sindresorhus/awesome/main/create-list.md` and `.../contributing.md` —
  fetched 2026-09-07: process guidance for submitting *to* the master list (30-day maturity, PR
  checklist pointers) — confirmed not directly applicable to this MIP's scope (not submitting).
- `api.github.com/search/repositories?q=topic:<name>&per_page=1` — fetched live 2026-09-07 for
  `agents` (16,581), `llm-agents` (5,079), `ai-agents` (87,431), `mcp` (72,890),
  `multi-agent-systems` (5,302) — all real, non-zero, on-topic result counts.
- Ten candidate "similar to marola" repos — each fetched live via its real `github.com/<owner>/
  <repo>` page on 2026-09-07 and confirmed to exist with a description matching what the doc says;
  full list and per-repo verification notes in `docs/AWESOME-AGENTIC-ENGINEERING.md` itself (kept
  there, not duplicated here, since that section is this MIP's actual deliverable and needs to stay
  live-editable without re-opening this MIP).

### Not checked

- GitHub's exact current numeric unauthenticated Search API rate limit (documented as roughly 10
  requests/minute for search-specific endpoints elsewhere, not independently re-confirmed against
  GitHub's own current rate-limit docs page this session — see §4.2).
- Whether any of the five verified GitHub topics (§4.2) has changed its `total_count` materially
  since the fetch timestamp above — expected to drift naturally, not a concern for this MIP's design.
