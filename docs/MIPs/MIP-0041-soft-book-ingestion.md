# MIP-0041: Soft book ingestion — distilling agentic-behavior principles from a book

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (agent) |
| **Created** | 2026-09-07 |
| **Phase** | 0 (`ARCHITECTURE.md` §11) — a maintainer dev-tool, not a Telegram/product feature |
| **Related** | `FUTURE-WORK.md` §9.1 (the RAG corpus pattern this deliberately does *not* reuse), MIP-0031 (`InemaPdfParser.scala` — the PDFBox/`pdftotext` precedent this reuses) |
| **Effort** | M — one new Python dev-tool script with its own self-test, a `.gitignore` convention for local-only book files, a new `docs/book-digests/` directory convention, and one `flake.nix` addition (`poppler-utils`, confirmed present on this host but not yet declared). No new Scala module, no new paid dependency |
| **Gain** | `infra/dev-loop` (sources AGENTS.md/rule-file changes from a real, cited text instead of only ad hoc mid-session corrections) |
| **Effort vs Gain** | cheap win — self-contained `scripts/`-only tool, no cloud spend, no Phase gate, no dependency on marola's own pipeline; the only real cost is a maintainer's time reviewing candidates by hand, which is the point |
| **Depends on** | Nothing blocking. Reuses `InemaPdfParser.scala`'s already-verified `pdftotext -layout` cross-check (MIP-0031) as precedent, not as code (this is a Python script, not Scala) |
| **Blocked by** | none |
| **Risk** | An unchecked, model-extracted "principle" gets merged into `AGENTS.md`/a rule file without a human actually opening the book and checking the cited page — quietly degrading the rule file's own trustworthiness with confidently-worded, unverified claims. The whole design (§5) exists to make that mistake require a deliberate extra step, not to make it impossible |
| **Cost so far** | — |

## 1. Summary

marola's own dev-loop rules (`AGENTS.md`, `.claude/rules/*.md`, this session's own memory files) today
only change reactively, one mid-session correction at a time, with no citation trail beyond "the
maintainer said so." This MIP proposes a small, human-gated pipeline, **soft ingestion**, that
takes a book the maintainer already owns, extracts short, page-cited candidate principles with a
local LLM pass, and writes them to a plain-checklist file for review, never auto-applying anything
to a rule file. The first run is Gerald Weinberg's *The Psychology of Computer Programming*, chosen
because its actual subject (programmer psychology, code-review culture, "egoless programming") maps
directly onto how this repo's human+agent workflow should behave, not onto marola's ocean domain.

## 2. Motivation

There is no existing pipeline, in this repo or in `FUTURE-WORK.md`'s design sketches, that takes a
whole external corpus and proposes *dev-loop* rule changes: `FUTURE-WORK.md` §9.1's RAG pipeline
only feeds facts to the *product* (`ask_ocean_question`), and every AGENTS.md/rule-file edit to date
has come from a human noticing something after the fact, unsourced against any outside authority.
Weinberg's book is a 55-year-old, still-in-print study of exactly the failure modes an AI-agent-plus-
human coding culture can fall into (territorial code ownership, unreviewed code hiding bugs, the
"egoless programming" commandments), a real, citable source this repo currently draws on nothing
like it for.

## 3. User-visible change

"User" here is the maintainer, not a marola end-user: nothing about the Telegram bot, the CLI, or
any scored/ranked output changes.

Before: an insight from a book, if it happens to reach `AGENTS.md` at all, arrives as an ad hoc edit
with no citation trail back to where it came from.

After:

```
$ python3 scripts/book_digest.py --book books/psychology-of-computer-programming.pdf --slug pocp
book_digest: extracted 214 pages of text (pdftotext -layout)
book_digest: 500-word chunks -> 187 chunks
book_digest: local Ollama pass (llama3.2) classifying each chunk...
book_digest: wrote 41 candidate principles to docs/book-digests/pocp/candidates.md
  - 18 tagged [knowledge]         (a factual/historical claim)
  - 23 tagged [agentic-behavior]  (a normative claim about how a programmer/reviewer should act)
book_digest: nothing was written to AGENTS.md or any rule file — review docs/book-digests/pocp/candidates.md by hand
```

A human then reviews `candidates.md` line by line. For each accepted `[agentic-behavior]` line, they
open a normal, separate, human-reviewed PR to `AGENTS.md`/`.claude/rules/*.md` citing the candidate
file and its page, the same review discipline as any other change to those files, nothing skipped.

## 4. Data sources and dependencies reviewed

### 4.1 The book itself — verified 2026-09-07 (WebSearch)

*The Psychology of Computer Programming: Silver Anniversary Edition*, Gerald M. Weinberg, Dorset
House Publishing, ISBN 9780932633422 (1998; carries forward the 1971 original's copyright, with a
new preface/commentary added for the 1998 edition). **Still commercially sold today**: a Leanpub
PDF/ePub/Kindle edition is currently listed for sale, and the Internet Archive lists it as
"borrow" (controlled digital lending, not a free download). **Not public domain, not freely
licensed**, a hard constraint on the design in §5: the book file itself must never be committed to
this git repo, and every extracted candidate must stay a short, cited excerpt/paraphrase, never
bulk reproduction of the source text.

### 4.2 PDF text extraction — no new dependency, cross-checked live this session

Apache PDFBox 3.0.7 is already a `local/` dependency (`InemaPdfParser.scala`, MIP-0031), and that
same MIP's own research used `pdftotext -layout` (poppler-utils) to cross-check PDFBox's extraction,
confirmed still true by reading `InemaPdfParser.scala`'s own comment this session. For a
maintainer-only Python dev-tool (the same category as `scripts/arxiv_digest.py`/`scripts/
mip_graph.py`, not shipped product code), shelling out to `pdftotext -layout` avoids adding a Python
PDF dependency. **Verified live this session**, against the repo's own `local/src/test/resources/
inema-boletim-salvador-13-2025.pdf` fixture: `pdftotext -layout <pdf> -` emits a form-feed (`\f`)
character between pages (2 form-feeds for that 3-page fixture), real, working page-boundary
tracking, not assumed. **Also verified live:** `pdftotext` is present on this host
(`/usr/bin/pdftotext`, poppler `24.02.0`) but **`flake.nix` does not currently declare
`poppler-utils`** in the dev shell, a real gap to fix as part of this MIP's task 1, not a
pre-existing given.

### 4.3 Local LLM extraction pass — no new dependency

The same local Ollama HTTP endpoint `LocalLlmClient` already targets
(`/v1/chat/completions`, `docs/1-Using-marola/RUN-LOCALLY.md`); the Python script talks to it directly via
`urllib.request` (stdlib only, matching `scripts/cost-split.py`'s own no-new-dependency
convention). No cloud path for v1: a book-digest run is a one-off maintainer task, not a product
feature.

## 5. Design

New files only: no change to any Scala module, since this is a maintainer dev-tool (Phase 0), not
part of marola's Telegram/CLI product surface:

- **`scripts/book_digest.py`**: the extraction script.
  - `--book <path>` (a local file under `books/`, gitignored, see below), `--slug <name>`
    (names the output directory), `--model` (default `llama3.2`), `--chunk-words` (default 500).
  - Pipeline: `pdftotext -layout <book> -` → split on real page boundaries (`\f`) into ~500-word
    chunks, each carrying its real page number (§4.2's live-verified mechanism, not an estimate) →
    one local Ollama chat call per chunk with a fixed system prompt ("extract 0-3 short, ≤2-sentence
    actionable principles from this chunk; classify each as `knowledge` or `agentic-behavior`; reply
    a JSON list; `[]` if nothing actionable") → append every returned item, with its real page
    number, as one checklist line to `docs/book-digests/<slug>/candidates.md`:
    `- [ ] [agentic-behavior] (p.34) <principle text>`.
  - A length cap on any quoted span inside a candidate (e.g. reject/truncate past ~40 words),
    keeps the fair-use posture from §4.1 a property of the tool, not a hope.
  - Reuses `core/llm/Reviewer.scala`'s own lesson (`extractJsonObject`): small local models
    sometimes wrap requested JSON in a code fence or a sentence: the script extracts the first
    `[...]`/`{...}` block rather than requiring byte-perfect compliance, same defensive parsing,
    not assumed away.
  - `--self-test` (wired into `just quality-other`, matching every other script's self-test
    convention): a small fixture, a fake two-page text with one planted principle, run through
    the chunker and the checklist-line formatter only. **No live Ollama call in `--self-test`**.
    The classification step is stubbed with a fixed fixture response, keeping `quality-other`
    network-free like every other script's self-test.
- **`docs/book-digests/<slug>/candidates.md`**: committed. Short, page-cited, human-reviewed
  derivative text only, never the source book itself.
- **`.gitignore`**: `/books/*` + `!/books/.gitkeep`, mirroring the existing
  `/finetune/data/* + !/finetune/data/.gitkeep` pattern exactly (checked this session, no
  existing `books`/`.pdf` entry to conflict with).
- **`flake.nix`**: add `poppler-utils` to the dev shell (§4.2's confirmed gap).

**The hard gate, stated once and enforced by what the script does *not* do:** `book_digest.py`
never writes to `AGENTS.md`, `.claude/rules/*.md`, or any skill file: only to
`docs/book-digests/<slug>/candidates.md`. Turning one accepted `[agentic-behavior]` line into an
actual rule-file change is always a separate, ordinary, human-reviewed PR, the same "explicit
human-confirmation gate for autonomous/proactive behavior" this repo already applies. The
script proposes; a human disposes, one principle at a time, never in
bulk and never silently.

## 6. Scoring / safety impact

None. This never touches `Swimability`, `Recommender`, or any marola-user-facing output. It only
ever proposes changes to files a human already reviews before merge. The review gate described in
§5 *is* the safety mechanism here; there is no new runtime code path in the product at all.

## 7. Verification plan

- `python3 scripts/book_digest.py --self-test`: chunk/page-tracking correctness and the
  checklist-line format, against the fixture described in §5, no live model call.
- One real, live run against Weinberg's book (the maintainer supplies the file locally, outside
  git), producing a real `docs/book-digests/pocp/candidates.md`, spot-checked by the maintainer
  that at least a few `[agentic-behavior]` lines genuinely trace to the cited page.
- "Done" for this MIP standalone = the script exists, is tested, and one real run's
  `candidates.md` is committed with real page citations. "Done" for any *specific* resulting rule
  change is a separate, ordinary PR to `AGENTS.md`/`.claude/rules/*.md`, out of this MIP's own
  scope (see §11).

## 8. Risks, limitations, and honest caveats

- **Copyright**: mitigated by never committing the book and capping quoted-span length (§5), but
  "short enough" is ultimately a judgment call each run, not a guarantee; the length cap is a floor,
  not a legal opinion.
- **Extraction quality**: a small local model (`llama3.2` default) can under-extract, over-extract,
  or state something not actually in the chunk. Every candidate is unverified model output until a
  human reads the real page: `candidates.md` is a checklist to *verify*, never a trusted source on
  its own, and nothing downstream of it is automated.
- **Chunk boundaries cross chapter/argument boundaries**: a 500-word window can split one argument
  in half, producing an incomplete or misleading extraction. Real page citation at least lets a
  human find the actual surrounding context before trusting a line.
- **Scope creep**: "digest a book" trivially generalizes to any book a maintainer owns, the
  intended reusability, but also an easy way to spend LLM-call time on low-value books. No guard is
  proposed beyond the script being manually invoked, never scheduled.

## 9. Alternatives considered

- **Manual reading + manual `AGENTS.md` edits (status quo)**: keeps full human judgment, but this
  repo already has precedent for "assist, don't replace" tooling (`arxiv_digest.py`,
  `mip_graph.py`): an unchecked-by-default checklist draft is strictly better than a blank page,
  as long as it's clearly unchecked, which §5's design guarantees.
- **Auto-apply extracted `agentic-behavior` principles straight into `AGENTS.md`**: rejected
  outright: violates this repo's explicit human-confirmation gate for autonomous behavior,
  and the MIP culture's own "design before build."
- **Treat the book like another `knowledge/*.md` RAG corpus entry (`FUTURE-WORK.md` §9.1)**:
  rejected for this specific use: the value here is reshaping how the *dev-loop* agent behaves,
  not answering marine questions; folding it into the marine RAG pipeline would conflate two
  unrelated corpora with different review disciplines and different copyright postures (the marine
  corpus is freely licensed sources only, per §9.1 point 1).
- **A cloud LLM instead of local Ollama**: available later as an opt-in, not needed for a first,
  low-volume, maintainer-triggered tool.

## 11. Open questions

- What's the actual reliability of `pdftotext -layout`'s `\f` page-boundary signal across a real
  216-page book (only a 3-page fixture was checked live this session), worth a quick spot-check
  once the real book file is available locally.
- Should `[knowledge]`-tagged candidates ever feed a consumer, or is `[agentic-behavior]` the only
  bucket worth building for v1? Leaning toward building and reviewing only the agentic-behavior
  half first, since there's no consumer for `[knowledge]` yet, a real product-scope gap, not a
  design gap, so flagged here rather than silently assumed either way.
- **Follow-up MIP:** once 2-3 books have gone through this pipeline, a small index page
  (`docs/book-digests/README.md`) listing each book, its slug, and accepted-vs-total candidate
  counts would be worth its own tiny MIP, not needed for the first book.

## Appendix

### Checked live

- WebSearch, 2026-09-07: "The Psychology of Computer Programming" Silver Anniversary Edition:
  Dorset House Publishing, ISBN 9780932633422, 1998; still commercially sold via Leanpub (PDF/
  ePub/Kindle) and listed as "borrow" (not a free download) on the Internet Archive: confirms not
  public domain.
- `grep -n "pdfbox" local/src/main/scala/marola/water/InemaPdfParser.scala build.sbt`, this
  session: confirms Apache PDFBox 3.0.7 is already a `local/` dependency, and the file's own
  comment confirms `pdftotext -layout` was used to cross-check PDFBox's extraction during
  MIP-0031's research.
- `pdftotext -layout local/src/test/resources/inema-boletim-salvador-13-2025.pdf - | grep -c $'\f'`,
  this session: 2 form-feed characters for the fixture's 3 pages: confirms the page-boundary
  tracking mechanism §5 relies on actually works, against a real PDF in this repo.
- `which pdftotext` / `pdftotext -v`, this session: present on this host (poppler `24.02.0`,
  `/usr/bin/pdftotext`); `grep -n "poppler" flake.nix` returned nothing: confirms it is not
  currently declared in the Nix dev shell, a real gap, not assumed.
- `grep -n "finetune\|/data/\|pdf\|books" .gitignore`, this session: confirms the
  `/finetune/data/* + !/finetune/data/.gitkeep` pattern this MIP's `/books/*` entry mirrors, and
  that no existing `books`/`.pdf` entry would conflict with it.

### Not checked

- The exact JSON shape a local Ollama model reliably returns for the "classify this chunk" prompt
  no live extraction call was made against a real Ollama endpoint in this session for this
  specific prompt; `Reviewer.scala`'s existing "small models wrap JSON in a code fence" caveat
  should be assumed to apply and handled the same defensive way (§5), not assumed away.
- `pdftotext -layout`'s page-boundary reliability across a real, full-length (200+ page) book:
  only a 3-page fixture was checked live this session (see §11).
- Fair-use boundaries for how much of a copyrighted book's text can be quoted per candidate: a
  legal question, not a technical one; flagged as a real open question, not resolved here.
