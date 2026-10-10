# MIP-0085: The routing index family — doc↔code, impact, decisions and a measured semantic search in the routing bundle

| | |
|---|---|
| **Status** | Accepted (2026-10-10, review by Bruno Guilhermo de Barros Valério in the design session) |
| **Author** | Bruno Guilhermo de Barros Valério |
| **Created** | 2026-10-10 |
| **Phase** | None: dev-loop tooling, outside `docs/PHASES.md`'s sequence. No Phase 1 prerequisite, no paid resource |
| **Related** | MIP-0084 (the bundle and `route` these plug into), MIP-0076 (the code graph and the wiring block; §5.2's revision dropped the Markdown pass), MIP-0080 (`skills.lock`) |
| **Effort** | L — three stdlib generators, four `route` subcommands, a 30-question bench, two committed generated files with `--check` gates, and a time-boxed embedding spike |
| **Gain** | `infra/dev-loop` — an agent can ask which MIP explains a file, what a change would break, and why something was decided, each in one bounded call instead of reading MIPs and trees |
| **Effort vs Gain** | `do when MIP-0084 lands` — every index here is a file in MIP-0084's bundle and a subcommand of its `route` |
| **Depends on** | MIP-0084: the `routing-bundle` branch, `route.py`, `routing-bench.yaml` and the bench gate. No Phase 1 gate, no paid resource; the embedding model is MIT, downloaded in CI only |
| **Blocked by** | 0084 |
| **Risk** | The indexes are built but answer worse than `git grep` plus reading a MIP; the bench is the only thing that stops an index shipping on faith |
| **Cost so far** | — |

### Readiness

| | |
|---|---|
| **Manually reviewed** | no |
| **Written by** | Bruno Guilhermo de Barros Valério, with Claude Code (Opus 5.5) |
| **Tasks** | `MIP-0085.tasks.md` |
| **Tests** | `doc_code --self-test`, `decisions --self-test`, `route_test.py`'s new names, the 30-question `route bench`, the §5.6 spike report |
| **Spec-kit** | none |
| **Issues** | not filed — Draft |

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

### 5.4 Doc↔code (`route docs`, `route code`; committed `DOC-CODE.md`)

`doc_code.py` scans `docs/**/*.md` and every README in the umbrella and its submodules for
backticked tokens. A token links when it equals a graph node's label (last segment, at least 4
characters) or a file's path or basename.

- **Ambiguity** (33% today): a name defined in several files links only if the same paragraph names
  the repo or a path segment of exactly one candidate; otherwise it is kept as `ambiguous` with every
  candidate listed, never guessed.
- **Committed**: `docs/2-Building-marola/DOC-CODE.md`, file level, grouped by MIP and page (doc →
  files it names). `doc_code --check` gates it in `quality-other`. Code moves with pointer moves, so
  `pointer-sync.sh` regenerates it in the pointer commit, as it does the wiring block, and
  `pointer-sync-merge.yml`'s allow list grows by exactly this file.
- **Bundle**: symbol-level edges, both directions, in `doc_code.json`.

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
  which follows §5.7's rule, and no type below its MIP-0084 score; `quality-other` green with both
  `--check` gates; one pointer-sync PR merged automatically with a regenerated `DOC-CODE.md`.

## 8. Risks, limitations, and honest caveats

- **Auto-merge grows.** Adding `DOC-CODE.md` to `pointer-sync-merge.yml`'s allow list means one more
  file changes on `main` with no person reading it. If `doc_code.py` had a bug that emptied the file,
  every pointer move would merge that loss, and agents in every repo would lose doc↔code answers at
  once. The `--check` gate cannot catch it, because it compares against the same generator. So the
  generator refuses to write a file under 50% of the previous one's rows, which fails the sync PR
  and leaves it for a person.
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
  bundle only.
- Should the bundle job also graph marola-devkit at its locked rev? **Default:** no; MIP-0076 scoped
  the graph to the umbrella's tree, and the devkit's tools are documented on its own docs page.
- Who writes the 22 new bench questions? **Default:** the agent drafts them from real sessions and
  MIPs, and a person approves the file in task 1's PR.
- Does `potion-base-2M`'s tokenizer need anything beyond WordPiece? **Default:** the spike checks
  `tokenizer.json`'s model type first; a tokenizer the stdlib cannot reproduce rules embeddings out.

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

- The potion tokenizer's model type and whether a stdlib reimplementation matches it (§11).
- How well any candidate does on meaning questions: that is the spike.
- The submodules' own docs in the doc↔code counts.
