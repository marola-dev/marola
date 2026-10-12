# CGO study: Claude, graphify and Obsidian for marola

Study behind [MIP-0089](../MIP-0089-cgo-knowledge-map.md). Written 2026-10-12 for Hoffmann. Every
number below names the command that produced it; `(v)` marks a claim checked against a source
fetched while writing, `⚠` one that was not.

## 1. The answer in five lines

1. "Grafiphyy" is **graphify** (PyPI `graphifyy`, Apache-2.0), the tool MIP-0076 pinned. No other
   tool by that name was found (v, PyPI and GitHub, 2026-10-12).
2. graphify already writes an **Obsidian vault**; marola only needs to run it in MIP-0084's CI job
   and add the tags graphify leaves out: repo, MIP, status, area.
3. A code-only graph does **not** show how ideas match across repos: 16 of 641 communities span
   two or more repos. The idea links come from MIP-0085's doc↔code and decisions indexes.
4. **Order**: MIP-0084 first (MIP-0085 is declared blocked by it), then CGO step 1 beside
   MIP-0085, then CGO step 2 once MIP-0085's indexes exist.
5. A **marola-built viewer** costs about ten PRs (≈ $60 of agent time, ⚠ estimate) plus review, and
   $0 to host. Park it until people are seen using the Obsidian vault.

## 2. What CGO means here

| Letter | Tool | Role | Already in marola? |
|---|---|---|---|
| C | Claude Code, Claude Projects | asks the graph (`route`, the routing digest) | yes: MIP-0084 vendors `route` into every repo |
| G | graphify 0.9.86 | builds the graph from code and Markdown, offline | yes: pinned by MIP-0076, run in CI by MIP-0084 |
| O | Obsidian | shows the graph to a person as linked notes, tags and a graph view | no |

MIP-0084 and MIP-0085 are the two Accepted MIPs about the graph (v, `docs/MIPs/README.md` on
`main` at `6d0a186`): MIP-0084 makes the graph reachable by every agent with no setup; MIP-0085
adds the indexes (doc↔code, impact, decisions, meaning) to the same bundle.

## 3. The spike (2026-10-12)

**Setup.** graphify 0.9.86 in a scratch venv (`uv pip install graphifyy`), every call under
`unshare -rn env -i HOME=<scratch> PATH=<venv>/bin:/usr/bin:/bin OLLAMA_BASE_URL=http://127.0.0.1:9`:
no network, no keys, so no model call is possible. Repos at their `main` tips of that morning.
marola-research, marola-skills and marola-agents were not in this checkout and are not counted.

### Table 1: one graph per repo (`graphify update . --no-cluster`)

| Repo | Commit | Nodes | Edges | Code nodes | Doc nodes | Time |
|---|---|---:|---:|---:|---:|---:|
| marola (umbrella) | 6d0a186 | 2,380 | 2,855 | 223 | 2,088 | 3.3 s |
| marola-app | 8d3af82 | 2,383 | 5,511 | 2,171 | 177 | 2.2 s |
| marola-site | 239fbea | 8,257 | 20,908 | 7,288 | 939 | 13.1 s |
| marola-corpus | d39b0e1 | 60 | 53 | 8 | 52 | 0.7 s |
| marola-ml | 91ab55d | 307 | 560 | 150 | 93 | 1.1 s |
| marola-oods | 9f3809e | 35 | 36 | 9 | 26 | 0.6 s |
| marola-devkit | 0749822 | 870 | 1,745 | 579 | 222 | 1.4 s |

6,949 of marola-site's nodes are the vendored `mapbox-gl-csp.js`.

### Table 2: merged, clustered and exported

| Step | Command | Result |
|---|---|---|
| merge | `graphify global add out/<repo>/graph.json --as <repo>` ×7 | 14,200 nodes, each with a `repo` field; 19 MB JSON |
| cluster | `graphify cluster-only merged --no-label --no-viz` | 30,979 edges, 641 communities, 15 s, 0 tokens |
| vault | `graphify export obsidian --dir vault` | 14,841 notes, `graph.canvas` 3.5 MB, 64 MB in all, 6 s |
| browser view | `graphify export html --node-limit 5000` | `graph.html` aggregated to 641 community nodes, 0.6 MB, 1 s |
| pruned | drop `site/static/vendor/` and vendored `.claude/skills/` | 6,398 nodes, 11,253 edges |

### What the merged graph shows, and what it does not

- **Cross-repo edges are few and mostly unresolved.** 803 of 30,979 edges join nodes of different
  repos; most point at nodes with no repo (an import of an external name). The largest real pairs
  are marola-devkit→marola (47) and marola-app→marola (40), mostly same-name script matches.
- **Communities stay inside repos.** 16 of 641 span more than one repo.
- **The vault loses the repo.** Each note's tags are file type, edge confidence and community
  (`graphify/export.py`, `to_obsidian`: `node_tags = [ftype_tag, conf_tag, comm_tag]`); the `repo`
  field from the merge is not written. A note on `Swimability` reads:

```markdown
---
source_file: "core/src/main/scala/marola/scoring/Swimability.scala"
type: "code"
community: "Community 26"
tags:
  - graphify/code
  - graphify/EXTRACTED
  - community/Community_26
---
```

**Reading.** graphify gives a navigable code map for free, and Obsidian can open it today. It does
not by itself answer "how do ideas in marola-corpus relate to marola-oods": that needs the
doc↔code links MIP-0085 §5.4 measured (2,126 exact backtick matches in 123 of 131 umbrella docs)
turned into tags, which is CGO step 2.

## 4. MIP-0084, MIP-0085 and CGO in perspective

| | MIP-0084 | MIP-0085 | CGO (MIP-0089) |
|---|---|---|---|
| Audience | every agent session | agents | people, then agents via tags |
| Status | Accepted 2026-10-10, 12 task issues filed | Accepted 2026-10-10, 11 task issues filed | Draft |
| Builds | `route`, the `routing-bundle`, a SessionStart hook, the vendored skill in six repos | doc↔code, impact, decisions, a measured semantic spike, a 30-question bench | a vault ref with tags, `knowledge-graph.yml`, `kg-enroll` |
| Blocked by | MIP-0076 (Implemented) | MIP-0084 | MIP-0084 task 5 (step 1); MIP-0085 tasks 5–6 (step 2) |
| Effort | L | L | M |
| Value alone | high: zero sessions reach the graph today | none until MIP-0084 ships | a code map only; ideas need MIP-0085 |

**Which goes first: MIP-0084.** Three reasons, each checkable:

1. MIP-0085's header says `Blocked by: 0084`, and every row of `MIP-0085.tasks.md` names
   "MIP-0084 implemented" as its precondition.
2. CGO's vault is one more output of MIP-0084's bundle job (§5.3 there); without the job there is
   nowhere to build it.
3. MIP-0084 fixes a measured zero: no session outside its builders called the graph
   (MIP-0084 §2). MIP-0085 and CGO improve answers nobody receives yet.

**Then, in parallel:** MIP-0085's bench and indexes (agent quality) and CGO step 1 (a vault with
`repo/` and `kind/` tags; a few dozen lines in a job that exists). **Last:** CGO step 2, which reads
MIP-0085's `doc_code.json` and `DECISIONS.md`. The viewer waits for evidence.

## 5. What a marola-built visualization layer would cost

Calibration: MIP-0076 recorded about $151 of `Cost:` trailers over 24 PRs, about $6.3 per PR, almost
all `cost-split --estimate-commit` diff-size estimates (v, MIP-0076 header). Person time is ⚠ an
estimate, not measured.

| Tier | What it is | PRs | Agent cost (≈ $6.3/PR) | Person review | Hosting | Ongoing |
|---|---|---:|---:|---|---|---|
| A: CGO | vault + tags in the bundle, `graph.html` beside it, reusable workflow, `kg-enroll` | 5 | ≈ $30 | ≈ 4–6 h | $0 (a git ref) | one bump PR per graphify update |
| B: marola atlas | a docs.marola.dev page: vendored sigma.js + graphology (MIT), pruned graph JSON, filters by repo/MIP/area, search, GitHub deep links | +10 | ≈ +$60 | ≈ 2–3 days | $0 (GitHub Pages) | breaks when graphify's JSON changes; a monthly fix PR |
| C: semantic atlas | B plus graphify's LLM pass so docs, PDFs and the corpus get meaning edges | +5 | ≈ +$30 | ≈ 1 day | $0 | LLM tokens, below |

**The LLM pass in tier C.** MIP-0076 measured 0.6–0.7M input tokens per full pass. At 0.65M input
tokens, run daily (19.5M a month), on the Claude API's prices as cached on 2026-10-06 (v, the
`claude-api` skill's model table), input only, output not measured (⚠):

| Model | Input $/1M | Per pass | Per month (daily) |
|---|---:|---:|---:|
| Claude Haiku 5.5 | $0.10 | $0.07 | ≈ $2 |
| Claude Sonnet 5.5 | $2.00 | $1.30 | ≈ $39 |
| Claude Opus 5.5 | $4.00 | $2.60 | ≈ $78 |

A paid model call in CI is a paid resource: it needs a person's go-ahead first (umbrella
`AGENTS.md`, cost rule). A local Ollama model costs $0 in money and needs a GPU runner.

**Bought instead of built.** Obsidian: free, Sync $4/user/month and Publish $8/site/month billed
annually, commercial licence $50/user/year and optional (v, obsidian.md/pricing, 2026-10-12).
Graphify Cloud: 14-day trial, no published price (v, its README, 2026-10-12).

**Recommendation.** Tier A now; B only if four weeks of tier A show at least three people opening
the vault (MIP-0089 §7); C not at all while the semantic spike in MIP-0085 §5.7 has not shown that
meaning search beats BM25 on marola's own questions.

## 6. Adding other repositories "in one click"

- **Inside the umbrella** (marola-corpus, marola-oods, marola-research, marola-skills,
  marola-agents and the rest): nothing to do. The bundle job graphs every submodule and tags each
  note `repo/<name>`.
- **Outside** (ww3-gpu, the awesome lists, a partner's repo): `just kg-enroll <owner/repo>` opens
  two PRs, a ten-line caller of the devkit's `knowledge-graph.yml` in the target and a line in the
  umbrella's `knowledge-sources.yml`. The target's own CI builds its graph offline and pushes it to
  its `refs/marola/graph`; the umbrella's job fetches it.
- **Why a PR and not a button.** The graph steers agents and readers; MIP-0084 §8 names a
  rewritten bundle as the way to mislead them. A person merging a reviewed file is the click.

## 7. Tags: the index scheme

| Tag | Meaning | Source | Step |
|---|---|---|---|
| `repo/<name>` | which repo the note is from | `graphify global add --as` | 1 |
| `kind/code`, `kind/document`, `kind/rationale` | node type | graphify's `file_type` | 1 |
| `mip/MIP-NNNN` | the MIP that names this file or symbol | MIP-0085 `doc_code.json` | 2 |
| `status/draft` … `status/implemented` | that MIP's status | the MIP's Status row | 2 |
| `area/<a>` | GitHub label of the MIP's issues | `labels.yml` `area/*` | 2 |
| `decision` | a row of `DECISIONS.md` | MIP-0085 `decisions.py` | 2 |

graphify's own `graphify/*` and `community/*` tags stay. An ambiguous doc↔code link (a third of
them, MIP-0085 §2) is listed in the note, not tagged.

## 8. Risks

- Nobody opens the vault; the step-1 cost is small, the viewer stays parked.
- Code-only edges are not ideas; without step 2 the vault is a code map.
- graphify moved from 0.9.66 to 0.9.86 in ten days; the export is pinned with graphify.
- Obsidian is closed source; the vault is plain Markdown and `graph.html` needs no Obsidian.
- marola-research has no content yet (REPOS.md: "Science", no contract).

## 9. Reproducing the spike

```bash
uv venv gv && uv pip install --python gv/bin/python graphifyy==0.9.86
G="env -i HOME=$PWD/home PATH=$PWD/gv/bin:/usr/bin:/bin GRAPHIFY_NO_AUTO_REFRESH=1 \
   OLLAMA_BASE_URL=http://127.0.0.1:9 unshare -rn graphify"
for r in marola marola-app marola-site marola-corpus marola-ml marola-oods marola-devkit; do
  (cd "../$r" && GRAPHIFY_OUT="$OLDPWD/out/$r" $G update . --no-cluster)
  $G global add "out/$r/graph.json" --as "$r"
done
mkdir merged && cp "$($G global path)" merged/graph.json
$G cluster-only merged --graph merged/graph.json --no-label --no-viz
$G export obsidian --graph merged/graphify-out/graph.json --dir vault
$G export html --graph merged/graphify-out/graph.json --node-limit 5000
```

## Sources (fetched 2026-10-12)

- [PyPI: graphifyy](https://pypi.org/project/graphifyy/) and its JSON API (version, licence, upload date).
- [Graphify-Labs/graphify](https://github.com/Graphify-Labs/graphify) README (Cloud, LLM use).
- [Obsidian pricing](https://obsidian.md/pricing) and [licence](https://obsidian.md/license).
- npm registry: [sigma](https://www.npmjs.com/package/sigma), [graphology](https://www.npmjs.com/package/graphology), [cytoscape](https://www.npmjs.com/package/cytoscape).
- MIP-0076, MIP-0084, MIP-0085 on `main` at `6d0a186`.
