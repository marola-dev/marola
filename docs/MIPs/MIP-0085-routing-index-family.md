# MIP-0085: The routing index family — doc↔code, impact, decisions and a measured semantic search in the routing bundle

| | |
|---|---|
| **Status** | Implemented (2026-10-20, #783) — all 11 rows of [`MIP-0085.tasks.md`](./MIP-0085.tasks.md) done; on the bundle published from main@4148d45 the bench is 30/34 and §7's "Done means" holds ([live close](#live-close-task-11-2026-10-20)). Accepted 2026-10-10, review by Bruno Guilhermo de Barros Valério in the design session. **Amended 2026-10-20 (#903, §5.4):** `DOC-CODE.md` is not committed; doc↔code lives in the bundle only |
| **Author** | Bruno Guilhermo de Barros Valério |
| **Created** | 2026-10-10 |
| **Phase** | None: dev-loop tooling, outside `docs/PHASES.md`'s sequence. No Phase 1 prerequisite, no paid resource |
| **Related** | MIP-0084 (the bundle and `route` these plug into), MIP-0076 (the code graph and the wiring block; §5.2's revision dropped the Markdown pass), MIP-0080 (`skills.lock`) |
| **Effort** | L — three stdlib generators, four `route` subcommands, a 30-question bench, one committed generated file with a `--check` gate, and a time-boxed embedding spike |
| **Gain** | `infra/dev-loop` — an agent can ask which MIP explains a file, what a change would break, and why something was decided, each in one bounded call instead of reading MIPs and trees |
| **Effort vs Gain** | `do when MIP-0084 lands` — every index here is a file in MIP-0084's bundle and a subcommand of its `route` |
| **Depends on** | MIP-0084: the `routing-bundle` branch, `route.py`, `routing-bench.yaml` and the bench gate. No Phase 1 gate, no paid resource; the embedding model is MIT, downloaded in CI only |
| **Blocked by** | 0084 |
| **Risk** | The indexes are built but answer worse than `git grep` plus reading a MIP; the bench is the only thing that stops an index shipping on faith |
| **Cost so far** | — |

### Readiness

| | |
|---|---|
| **Manually reviewed** | yes — Bruno Guilhermo de Barros Valério, 2026-10-10 |
| **Written by** | Bruno Guilhermo de Barros Valério, with Claude Code (Opus 5.5) |
| **Tasks** | `MIP-0085.tasks.md` |
| **Tests** | `doc_code --self-test`, `decisions --self-test`, `route_test.py`'s new names, the 30-question `route bench`, the §5.6 spike report |
| **Spec-kit** | none |
| **Issues** | parent #780; #781 (1), marola-dev/marola-devkit#91, marola-dev/marola-devkit#92, marola-dev/marola-devkit#93, marola-dev/marola-devkit#94, marola-dev/marola-devkit#95, marola-dev/marola-devkit#96, marola-dev/marola-devkit#97, marola-dev/marola-devkit#98 (2–9), #782 (10), #783 (11) |

## 1. Summary

MIP-0084 gives every session a bundle and a `route` tool, holding only the code graph and the
wiring. This MIP adds an index per question type agents ask: which doc explains this code and the
reverse, what a change would break, and why something was decided. It also sharpens the symbol
query, and runs a time-boxed spike on search by meaning. Each index ships only if it raises the
right-first score on a shared 30-question bench.

## 2. Motivation

What `route` cannot answer after MIP-0084, measured on 2026-10-10:

- **Symbols by name only.** The pruned code graph answers 3 of 8 symbol questions right first, and
  only given the code's identifiers. "Entry point" ranks `SamplingPointCoordinates.Entry` above the
  Telegram bot's `Main`.
- **Doc↔code: nothing.** graphify's Markdown pass made such edges but buried code answers (53% noise,
  1 of 8 right first), so MIP-0076 §5.2's revision removed it. An exact scan finds the signal is
  there: the umbrella's 131 docs hold 19,466 backticked tokens, of which 2,126 name a graph node or
  file exactly (1,542 symbols, 584 paths), in 123 docs. 695 of those (33%) name something defined in
  more than one file.
- **Impact: only implicit.** 776 of the 1,598 nodes have an incoming `calls`, `imports` or
  `indirect_call` edge, but nothing walks them in reverse. Cross-repo contracts are in the wiring
  only as artifacts; the two `board.schema.json` copies are not linked.
- **Decisions: scattered.** All 71 MIPs have an "Alternatives considered" section, but only 3 carry
  a bold `**Decided YYYY-MM-DD:**` marker; the rest phrase decisions freely.

## 3. User-visible change

The shape, with illustrative rows:

```text
$ route docs Swimability
answers from main@e77c298 (bundle)
docs/MIPs/MIP-0031-…md §5  `Swimability.score`
docs/MIPs/MIP-0054-…md §5  `Swimability`
$ route code MIP-0083
marola-app experiment/src/main/scala/… · …
$ route impact board.schema.json
contract  marola-app/cli/src/main/resources/board.schema.json
  copied to  marola-site/site/board.schema.json
  read by    marola-site/scripts/board-schema.sh (wiring)
(lower bound: name-resolved calls only)
$ route why "graphify LLM pass"
MIP-0076 §4  graphify's LLM pass: Rejected, about 0.6–0.7M input tokens per full pass
```

## 4. Data sources and dependencies reviewed

- **The code graph** (MIP-0076, pruned): 1,598 nodes, 3,166 edges; relations `calls` 1,163,
  `contains` 657, `method` 509, `references` 506, `defines` 152, `rationale_for` 116.
- **The docs**, read by our own scan, not graphify (numbers in §2). Submodule docs were not in the
  measurement (submodules uninitialised in the measuring checkout); CI scans them.
- **The MIPs**: Status row, §1, "Alternatives considered", and `**Decided …**` markers (counts in §2).
- **model2vec** (PyPI, 0.9.0, MIT): static embeddings; requires `numpy`, `tokenizers`,
  `safetensors`, `joblib`, `jinja2`, `tqdm`, so it runs **in CI only**.
- **potion models** (Hugging Face, `minishlab`, MIT): `potion-base-2M` 7.6 MB and `potion-base-8M`
  30.2 MB of `model.safetensors`, plus a 0.7 MB `tokenizer.json`. `safetensors` is a header plus raw
  arrays; unlike a pickled model, loading it cannot run code.
- **BM25**: textbook ranking; about 40 lines of stdlib Python, no dependency.

**Pick**: deterministic generators over our own files for doc↔code, impact and decisions; for
meaning, a spike that pits BM25 against both potion sizes and ships the winner, or nothing.

## 5. Design

Every index is built by the bundle job (MIP-0084 §5.3) and answered by `route`. Generators live in
marola-devkit `scripts/`, stdlib only, each with `--self-test` and, where committed, `--check`.

### 5.1 The bench

`routing-bench.yaml` (umbrella, from MIP-0084 task 5) grows from 8 to about 30 questions, five or
more per type: symbol, wiring, doc↔code, impact, decision, meaning. Each names the expected repo and
path (or MIP and section). "Right first" means the expected answer is in the first three result
lines. The bundle job's gate (MIP-0084 §5.3) applies per type, so an index cannot trade one type's
score for another's.

### 5.2 Symbol, sharpened (`route query`)

- Identifiers split on case and separators at build and at query (`OpenMeteoClient` → open, meteo,
  client), matched as tokens rather than substrings.
- A stop list (`where`, `is`, `the`, `entry`, `point`, `how`, `what`, `does`, …) drops words that
  name nothing.
- Entry points tagged at build (`def main`, `@main`, `object … extends App`, `if __name__ ==`) and
  boosted when the question says entry, start or main.

### 5.3 Wiring contracts (`route wiring`, `route impact`)

`wiring.py` gains a contract table: a canonical file and its byte-identical or vendored copies
(today `board.schema.json`): files with the same basename and identical content in two repos, plus
explicit `contract` lines in `wiring.allow` for copies that drift on purpose. Emitted in
`wiring.json` and as a fifth table in REPOS.md's block.

### 5.4 Doc↔code (`route docs`, `route code`; bundle only)

`doc_code.py` scans `docs/**/*.md` and every README in the umbrella and its submodules for
backticked tokens. A token links when it equals a graph node's label (last segment, at least 4
characters) or a file's path or basename.

- **Ambiguity** (33% today): a name defined in several files links only if the same paragraph names
  the repo or a path segment of exactly one candidate; otherwise it is kept as `ambiguous` with every
  candidate listed, never guessed.
- **Bundle only**: symbol-level edges, both directions, in `doc_code.json`, built by the bundle job
  from the graph it has just built. **Decided 2026-10-20:** no committed `DOC-CODE.md`, since
  committing it meant `pointer-sync.sh` building the code graph on every pointer move, a `--check`
  against a bundle graph already behind the pointers, and one more file merged unread (§8).
  `doc-code --md` still writes the file-level table, grouped by MIP and page, for a local read.

### 5.5 Impact (`route impact X [--depth 2]`)

Reverse `calls`, `imports` and `indirect_call` edges from X, depth 2 by default, grouped by file;
then, if X's file is a contract or a published artifact in `wiring.json`, its copies, readers and
pinning repos. Output always ends with "lower bound: name-resolved calls only", because tree-sitter
resolves calls by name and misses Scala implicits, extension methods and Kyo's effect plumbing.

### 5.6 Decisions (`route why`; committed `DECISIONS.md`)

`decisions.py` writes `docs/MIPs/DECISIONS.md`, one row per item: MIP, section, status, and text,
taken from §1's first sentence, each "Alternatives considered" bullet's bold lead and first clause,
and every `**Decided YYYY-MM-DD:** …` line. `decisions --check` gates it in `quality-other`.
`TEMPLATE.md` adds the convention that a decision is written `**Decided YYYY-MM-DD:** <decision>`,
and `docs-lint` flags a MIP §11 bullet that says "decided" without it. `route why` ranks the rows by
BM25.

### 5.7 Meaning: a spike, then maybe `route query --meaning`

Time-boxed to one task. Candidates, each over the same text per node (identifiers split, the
docstring and leading comment, the file path):

| Candidate | Build (CI) | Query (anywhere) |
|---|---|---|
| BM25 | stdlib | stdlib |
| potion-base-2M | model2vec vectors | stdlib: `safetensors` read with `struct`, the tokenizer from `tokenizer.json`, a mean and a cosine |
| potion-base-8M | as above | as above |

**Rule**: an embedding ships only if it beats BM25 by at least 3 right-first answers on the bench's
meaning questions and adds at most 30 MB to the bundle. Otherwise BM25 ships if it beats the plain
query, and nothing ships if not. The model is pinned by Hugging Face revision and file hash; it and
the vectors go in `refs/marola/routing-bundle-large`, outside `refs/heads/`, so a plain clone does
not fetch them (MIP-0084 §5.3's 10 MB rule), and `route fetch --meaning` pulls them on demand. The
spike's code is throwaway; its report goes in this MIP's appendix.

**Decided 2026-10-20:** BM25 ships, inside the bundle; no embedding (the
[meaning spike](#meaning-spike-task-7-2026-10-20)).

## 6. Scoring / safety impact

None. No code path in marola-app changes.

## 7. Verification plan

- **`doc_code --self-test`**: `backtick_symbol_links`, `path_and_basename_link`,
  `short_label_ignored`, `ambiguous_kept_with_candidates`, `paragraph_repo_disambiguates`,
  `check_fails_on_stale_file`.
- **`decisions --self-test`**: `summary_first_sentence`, `alternative_bold_lead`,
  `decided_marker_row`, `check_fails_on_stale_file`; `docs-lint` adds `decided_without_marker`.
- **`wiring --self-test`** adds `contract_copy_linked`.
- **`route_test.py`** adds `query_splits_identifiers`, `stop_words_dropped`,
  `entry_point_boosted`, `impact_reverse_depth`, `impact_adds_contract_readers`,
  `impact_prints_lower_bound`, `docs_and_code_directions`, `why_ranks_bm25`,
  `meaning_absent_says_so`.
- **The spike** (if an embedding ships): `query_vector_parity`, the pure-Python query vector within
  1e-5 cosine of model2vec's for 50 strings.
- **Done means**: on the 30-question bench, every type at least 4 of 5 right first except meaning,
  which follows §5.7's rule, and no type below its MIP-0084 score; `quality-other` green with
  `decisions --check`; the bundle published from `main` carries `doc_code.json`, `decisions.json`
  and `meaning.json`.

## 8. Risks, limitations, and honest caveats

- **No auto-merge growth, and no table in the tree.** With `DOC-CODE.md` out of the commit (§5.4),
  `pointer-sync-merge.yml`'s allow list is unchanged. An agent asks `route docs` or `route code`; a
  person reading on GitHub has no doc↔code table, only `doc-code --md` run locally. A generator bug
  that emptied `doc_code.json` would fail the bench's doc-code questions, and the per-type gate
  (§5.1) would keep the previous bundle.
- **Ambiguity is a third of the links.** The paragraph rule resolves some; the rest are listed, not
  guessed, so an agent sees two or three candidates instead of one wrong one.
- **Impact is a lower bound**, and says so on every answer.
- **marola-devkit's code is not in the graph.** It is a flake input, not a submodule, so a MIP about
  devkit tooling links to nothing on the code side. Adding the devkit to the bundle build is a
  separate decision (§11).
- **Decisions are only as findable as they are written.** Free-form older MIPs give one row per
  alternative and §1; the `Decided` convention helps only from now on.
- **The model is a downloaded artifact.** It is pinned by revision and hash and read as
  `safetensors` only. A pickle-based model (`.bin`, `.pt`) would run code when loaded, and in CI that
  code would hold the job's token, which can push the bundle that every agent trusts. `route` never
  loads any other format.
- **The bench is ours.** Thirty questions written by the people building the indexes can flatter
  them; new questions come from real misses seen in `just route-usage`.

## 9. Alternatives considered

- **Do nothing**: `route` answers symbols and wiring; the other question types go back to grep and
  reading MIPs.
- **graphify's Markdown pass for doc↔code**: tried; 53% noise and 1 of 8 right first (MIP-0076 §5.2).
- **graphify's LLM pass**: about 0.6–0.7M input tokens per run, edges inferred; rejected in MIP-0076.
- **Ollama embeddings**: a daemon and a ~300 MB pull per machine, and impossible in cloud sessions.
- **A full embedding model through ONNX Runtime**: better vectors, but a large native dependency on
  every machine that queries.
- **mkdocs' search index** (already built for docs.marola.dev): full-text over docs only; no code
  side, so no doc↔code edges.
- **One combined graph**: docs in the code graph was exactly what MIP-0076's revision undid.

## 11. Open questions

- Should `DOC-CODE.md` be committed at all, given the auto-merge growth in §8? **Default:** yes, as
  agreed in design review on 2026-10-10, with the shrink guard; the maintainer may move it to the
  bundle only. **Decided 2026-10-20:** bundle only (§5.4), the maintainer's call in task 10, once
  committing it turned out to need a graph build in every pointer-sync run.
- Should the bundle job also graph marola-devkit at its locked rev? **Default:** no; MIP-0076 scoped
  the graph to the umbrella's tree, and the devkit's tools are documented on its own docs page.
- Who writes the 22 new bench questions? **Default:** the agent drafts them from real sessions and
  MIPs, and a person approves the file in task 1's PR.
- ~~Does `potion-base-2M`'s tokenizer need anything beyond WordPiece?~~ **Resolved 2026-10-20:**
  no. Both potion models ship the same `tokenizer.json`: a `BertNormalizer` (lowercase, accents
  stripped, CJK padded, control characters cleaned), a `BertPreTokenizer` and a `WordPiece` model
  (`##` prefix, `[UNK]`, 100 characters per word). The spike rebuilt all three in an 82-line
  stdlib class. It gave the same token ids as Hugging Face `tokenizers` on all 1,766 node texts, and a
  query vector within 1.1e-13 cosine of model2vec's on 50 strings, so the tokenizer does not rule
  embeddings out ([Meaning spike](#meaning-spike-task-7-2026-10-20)).

## Appendix

### Checked live

- `~/.cache/marola-graph/marola-clean/graph.json` and the umbrella's `docs/**/*.md`, 2026-10-10: §2's
  counts (131 docs, 19,466 backticked tokens, 2,126 exact matches, 695 ambiguous, 776 nodes with a
  caller).
- `docs/MIPs/MIP-0*.md`, 2026-10-10: 71 MIPs, 71 with "Alternatives considered", 3 bold `Decided`
  markers.
- `https://pypi.org/pypi/model2vec/json`, 2026-10-10: 0.9.0, MIT, requires `jinja2`, `joblib`,
  `numpy`, `safetensors`, `tokenizers>=0.20`, `tqdm`.
- `https://huggingface.co/api/models/minishlab/potion-base-2M` and `-8M` (`?blobs=true`),
  2026-10-10: `model.safetensors` 7.6 MB and 30.2 MB, `tokenizer.json` 0.7 MB, licence MIT.

### Not checked

- The submodules' own docs in the doc↔code counts.
- What the meaning spike did not check is listed at the end of its section below.

### Meaning spike (task 7), 2026-10-20

**Decided 2026-10-20:** BM25 ships as `route query --meaning`, and no embedding does. The
maintainer's call on this spike: read against devkit main's query, §5.7's rule gives "none", since
BM25 ties it at 3 of 5; read against the query that scored the live bundle (2 of 5), BM25 wins by
one; and it leads 12 to 7 on the extras below. Its index is 294 KB (79 KB compressed), well inside
MIP-0084 §5.3's 10 MB rule, so it goes in the bundle itself: no model, no
`refs/marola/routing-bundle-large` and no `route fetch --meaning`. No embedding beats BM25 by 3, so
none ships. The rule is applied in [Rule applied](#rule-applied) below.

**Setup.** The code is throwaway, on marola-dev/marola-devkit branch `mip-0085/7-meaning-spike`
(`4f83fe9`, `spike/meaning/`).

- **Graph**: the live bundle's `graph.json` (`origin/routing-bundle` at `32043a5`). It graphs umbrella
  `ccffd08` with the submodules at the commits in its `build.json`, and has 1,766 nodes in 183 files.
  The source text was read from a checkout at those same commits.
- **Text per node** (§5.7, `nodetext.py`): the label split into words, the docstring and leading
  comment at the node's line, and the file path split into words. 735 of the 1,766 nodes have a
  docstring or comment; the other 1,031 have only the label and the path. Every candidate got the same
  text, 293,968 B as JSON.
- **Questions**: the bench's meaning questions, ids 30–34, plus 15 extra plain-language
  questions (x1–x15, `questions.json`), each checked against the expected file's own doc comment.
  They were written before any candidate ran. They are **not part of the rule**; they are there
  because 5 questions cannot separate candidates whose scores differ by 1.
- **Right first**: `route bench`'s rule. An expected string must be in the file of one of the first
  three NODE lines. Counting the first three distinct files instead changes one cell (potion-8M on
  the extras, 10 to 11) and no verdict.
- **Candidates**:
  - *pinned*: `route query` as the umbrella vendors it today (devkit `c7ecce0`). This is the
    version that scored the live bundle's meaning type at 2 of 5 in `build.json`.
  - *plain*: `route query` on devkit `main` (`c5df0fb`), with task 2's identifier splitting and stop
    words.
  - *BM25*: k1 1.2, b 0.75, `route`'s tokens and stop list, no stemming. This is the only setting
    run; nothing was tuned.
  - *potion-2M* and *potion-8M*: node vectors from model2vec 0.9.0. Queries go through the stdlib
    path: `tokenizer.json`, then `model.safetensors` rows read with `struct`, then a mean, an L2
    norm and a cosine.
- **Model pins**: `minishlab/potion-base-2M` at revision `389b9f64be5a`, `model.safetensors`
  sha256 `f95ffde02ad0…`. `minishlab/potion-base-8M` at revision `bf8b056651a2`, sha256
  `f65d0f325faa…`. Both use the same `tokenizer.json`, sha256 `e67e803f624f…`. Both model hashes
  match the Hugging Face API's LFS sha256. The only weights downloaded were `model.safetensors`
  (plus `config.json` and `tokenizer.json`); no `.bin`, `.pt` or `.onnx`.

**Parity** (`parity.py`, run under model2vec 0.9.0, tokenizers 0.23.3 and numpy 2.5.4):

| Model | Strings | Worst 1 − cos, stdlib vs model2vec | Token-id mismatches | Node texts, token-id mismatches |
|---|---|---|---|---|
| potion-2M | 50 | 7.9e-14 | 0 | 0 of 1,766 |
| potion-8M | 50 | 1.1e-13 | 0 | 0 of 1,766 |

The 50 strings are the 20 questions, 13 edge cases and 17 node texts. The edge cases cover
Portuguese with accents, CJK, emoji, control characters and NUL, a 101-character word, code and a
URL, and the empty string.

**Results** (`bench.py`). Right first counts answers out of 5 bench questions and out of 15 extras.
Bytes added are the files the bundle would gain: raw, then zlib level 6 (a git object before
deltas). Latency is in ms. Warm is the median over the 20 questions of the median of 5 runs, in one
process. Cold is the median of 5 fresh `python3 -I` processes that each answer question 30, and it
includes Python's own start, 18.6 ms for `python3 -I -c pass`.

| Candidate | Bench meaning | Extras | Bytes added | Warm query | Cold process | Build (CI) |
|---|---|---|---|---|---|---|
| pinned `route query` | 2/5 | 7/15 | 0 | 12.4 ms | 63.9 ms | — |
| plain `route query` (devkit main) | 3/5 | 7/15 | 0 | 14.5 ms | 66.9 ms | — |
| BM25 | 3/5 | 12/15 | 293,968 B / 79,128 B (node texts) | 0.67 ms | 74.6 ms | node text 37–43 ms; the index is built at query time (47 ms) |
| potion-2M | 3/5 | 9/15 | 8,695,018 B / 7,634,275 B | 6.9 ms | 59.8 ms | node text, then model2vec import 78–94 ms, load 106–114 ms, encode 34–35 ms |
| potion-8M | 4/5 | 10/15 | 32,728,810 B / 29,992,434 B | 22.5 ms | 73.4 ms | node text, then model2vec import 81–110 ms, load 121–126 ms, encode 39–40 ms |

The embeddings' bytes break down as follows. potion-2M: `model.safetensors` 7,559,256 B,
`tokenizer.json` 683,666 B and the vectors 452,096 B (1,766 × 64 float32). potion-8M:
`model.safetensors` 30,236,760 B, the same `tokenizer.json` and the vectors 1,808,384 B
(1,766 × 256). Rebuilding potion-2M's vectors gave the same sha256 a second time.

The embeddings also cost CI a model2vec install: 3.79 s with `uv` and no cache, for a 94 MB venv.
Downloading the model took 1.5 s (2M) and 3.4 s (8M) on this connection. The build times are 3
runs each.

All timings come from one machine: an AMD Ryzen 7 7800X3D under WSL2. Queries ran on Python 3.14.6
and model2vec on 3.13.14, with the page cache warm. The baselines read `graph.json` from a file,
where `route` reads it through `git show`.

Per question, the rank of the first node line in an expected file ("–" means not in the first 50):

| Id | Question | pinned | plain | BM25 | 2M | 8M |
|---|---|---|---|---|---|---|
| 30 | where is the safety veto applied | – | – | 12 | 16 | 13 |
| 31 | how are the wind particles drawn on the map | 1 | 1 | 1 | 1 | 1 |
| 32 | which hours tomorrow are best for a swim | 5 | 1 | 1 | 1 | 1 |
| 33 | where are the nearby beaches found | 3 | 2 | 1 | 1 | 1 |
| 34 | what checks the summary the language model wrote | – | – | – | 4 | 3 |
| x1 | how are high and low tide times worked out | 3 | 3 | 1 | 1 | 1 |
| x2 | how does the app guess where I am when no coordinates are given | – | – | 1 | 12 | 4 |
| x3 | how are walking paths near a beach found | – | – | 16 | 1 | 1 |
| x4 | what emergency note is added to an answer about safety | 3 | 3 | 1 | 1 | 1 |
| x5 | how does the bot answer an ocean question only from retrieved passages | 2 | 2 | 1 | 1 | 1 |
| x6 | where are reported animal sightings saved | 1 | 1 | 1 | 1 | 1 |
| x7 | where does the fun fact about the sea at the end of a reply come from | – | – | 1 | – | 30 |
| x8 | how is the training data for fine-tuning the model assembled | 1 | 1 | 1 | 2 | 1 |
| x9 | where are the team members' photos downloaded for the website | – | – | 1 | 3 | 1 |
| x10 | how do requests to Brazilian government sites get past the timeouts in CI | – | – | 18 | – | 12 |
| x11 | which check stops a worse model image from being promoted | – | – | – | – | – |
| x12 | how much GitHub Actions usage is this account billed for | 1 | 1 | 1 | 1 | 1 |
| x13 | which sampling point belongs to which beach | – | – | 3 | 14 | 6 |
| x14 | where are the news posts on the website rendered | – | – | 1 | 7 | 1 |
| x15 | where are code review comments posted on a pull request | 1 | 1 | 1 | 1 | 1 |

The embeddings win where the question and the code use different words. For example, "walking
paths" finds `TrailFinder` (x3), and "checks the summary" finds `Reviewer` (34). BM25 wins where
the doc comment uses the question's own words: "at the end of a reply" for the sea fact (x7), and
"where am I" with "is given" for the location guess (x2). No candidate puts the veto question (30)
in the first three. For BM25, "safety" lifts `SafetyFooter`'s nodes to the top, and `WaterVerdict`,
whose comment says "veto", ranks 12th.

#### Rule applied

1. **An embedding ships only if it beats BM25 by at least 3** on the 5 meaning questions.
   potion-2M scores 3 − 3 = 0 and potion-8M scores 4 − 3 = 1, so neither ships.
   potion-8M would also fail the 30 MB limit on raw bytes (32.7 MB, 31.2 MiB). Its zlib size,
   29.99 MB, is just under the limit, but the first test has already decided.
2. **Otherwise BM25 ships if it beats the plain query.** BM25 scores 3 of 5, and the plain query on
   devkit main also scores 3 of 5. A tie is not a win, so **nothing ships**.

This reading takes the plain query to be devkit main's (`c5df0fb`), because that is the
`route query` a `--meaning` flag would ship beside, and task 2 already merged it. Against the
vendored copy that scored the live bundle (`c7ecce0`, 2 of 5), BM25 would win by one question and
ship. The maintainer chose to ship it (the decision at the top of this section).

These numbers are outside the rule, but they are what a later change would start from. On the
extras, BM25 answers 12 of 15 right first and the plain query 7. The embeddings answer 9 (2M) and
10 (8M). BM25's warm query takes 0.67 ms and adds 79 KB compressed to the bundle. Five meaning
questions are too few for a one-question margin to mean much in either direction.

#### What the spike did not check

- Any BM25 setting other than k1 1.2 and b 0.75 without stemming. Also any hybrid, such as BM25
  as a fallback when the plain query matches nothing, or BM25 combined with an embedding.
- Whether the extras flatter BM25: they were written after reading the expected files' doc
  comments, which are what BM25 matches on: §8's "the bench is ours" risk again.
- `refs/marola/routing-bundle-large`, `route fetch --meaning` and the CI job. Nothing ships, so
  none was built.
- Git's pack size with deltas (only zlib was measured), and the MB versus MiB reading of §5.7's
  30 MB.
- Latency on any other machine, on macOS, or on Python 3.12 (the devkit's ruff target), and with
  `route`'s `git show` read in the loop.
- The node-text heuristics beyond spot checks. One example: a Scala file node takes the file's
  first comment, which can belong to the first class in the file.

### Live close (task 11), 2026-10-20

`route fetch`, then `route bench routing-bench.yaml`, in a fresh worktree with no Nix, against the
bundle `bundle-publish` pushed from main@4148d45 (#903). The bundle holds `doc_code.json`,
`decisions.json` and `meaning.json` beside MIP-0084's files; its `build.json` records the same
score. MIP-0084's column is the same bench on main@2b78684's bundle, before this MIP's tasks (#889).

| Type | MIP-0084 | Now |
|---|---|---|
| symbol | 6/6 | 6/6 |
| wiring | 7/7 | 7/7 |
| doc-code | 0/6 | 5/6 |
| impact | 2/5 | 5/5 |
| decision | 0/5 | 4/5 |
| meaning | 2/5 | 3/5 |
| total | 17/34 | 30/34 |

Every type but meaning is at least 4 of 5 right first, and none is below its MIP-0084 score. Meaning
follows §5.7's rule: BM25 beats the plain query, 3 against 2. `quality-other` passed with
`decisions --check` on #903 and on its push to `main`. The misses: `route code MIP-0031` (18),
`route why "a PAT for cross-repo automation"` (29), and two meaning questions (30, 34).

### The misses left, 2026-10-20 (#907)

Two of the live close's four misses were the inputs, not `route`. Question 18 expected only
`WaterQualityMatcher.scala`, which MIP-0031 names once; it now also accepts the INEA and INEMA
clients the MIP adds, and `route code MIP-0031` lists the INEA client first. Question 29 found
MIP-0070's PAT decision first because nothing in MIP-0070 said MIP-0086 had replaced it; its §11 now
says so in a `**Decided 2026-10-10:**` line, which `route why` ranks first.

The two meaning misses stay. Questions 30 ("where is the safety veto applied") and 34 ("what checks
the summary the language model wrote") use words that are not in the expected nodes' names or
leading comments. Three changes to `meaning.json` or the ranking, each run on all 34 questions
against main@4148d45's bundle, gained nothing:

| Change | Meaning right first | Total |
|---|---|---|
| none (the live close) | 3/5 | 30/34 |
| plurals folded (`PATs` → `pat`) in both BM25 rankers | 3/5 | 30/34 |
| each MIP's whole §1 paragraph in its summary row | 3/5 | 30/34 |
| identifiers from each node's body, capped at 10 / 40 / 100 | 3/5 / 2/5 / 3/5 | 30 / 29 / 30 |

At cap 100 the body identifiers fix question 30 but lose question 33 ("where are the nearby beaches
found") to nodes that merely use a beach type; at cap 40 they lose 33 and fix nothing. What these two need is a match on meaning, not
on words, and §5.7's spike found no embedding that beats BM25 by 3 here.
