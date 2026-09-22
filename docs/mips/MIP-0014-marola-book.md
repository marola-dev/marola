# MIP-0014: A marola book — *The Compiler Pushes Back*, written in LaTeX, versioned in a repo, built in CI

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-05: "a MIP for a marola-related book, technical, in LaTeX, versioned in a repository, built in CI, in the tradition of self-published FP books") |
| **Created** | 2026-09-05 |
| **Phase** | 0 — a documentation/tooling project, nothing a swimmer sees; independent of the app's own phase gate, but see Effort vs Gain below for why it should still wait |
| **Related** | `PHILOSOPHY.md` (the book's own thesis — "the one decision" — is the book's spine); `docs/SKILLS.md` (the roadmap this book turns into a narrative); `docs/DEV-FLOW.md` (the MIP/agent loop chapter); `docs/FUTURE-WORK.md` §10 ("the Scala/JVM gap in the prompt-engineering ecosystem" — a marola book is a small dent in the literature gap, not just the code gap); `docs/mips/MIP-0005` (map site), `MIP-0008` (Docker images), `MIP-0010` (MLflow), `MIP-0012` (llm4s/DSPy deprecation) — each is a chapter's grounding; [h0ffmann/forecast-energy-demand](https://github.com/h0ffmann/forecast-energy-demand) (the author's own LaTeX-thesis-with-CI-translation pipeline — reviewed in §4.6 as prior art and a token-efficiency case study) |
| **Effort** | XL — an 11-chapter first edition in English plus a pt-BR translation build, a LaTeX/Nix/CI toolchain built from scratch, a listing-extraction pipeline kept honest against a fast-moving codebase (see §7 estimate), and a segment-level translation pipeline with its own translation-memory store and CI gate (§5.8) |
| **Gain** | community/outreach (a citable, dated, verified account of a real local-first LLM product, in a genre — self-published FP books — that currently has zero Scala-native-LLM entries per `FUTURE-WORK.md` §10); learning artifact (turns `docs/SKILLS.md`'s roadmap into a narrative); reach (a pt-BR edition, §5.8, for the author's own first-language reviewers and a Brazilian Scala/AI audience); no direct product value for the swimmer |
| **Effort vs Gain** | expensive, defer — real value, but XL effort competing for the same author-hours as Phase 1 (the Telegram bot, MIP-0002, still Draft); write one chapter, the build pipeline, and a translated sample chapter first as a spike, not the whole outline or both full editions, and don't let it displace Phase 1 |
| **Depends on** | Nothing technically (the book's toolchain is independent of the app); a stronger chapter 2 ("does it work for a real user") needs MIP-0002 (Telegram bot) shipped first — not a hard blocker, an honesty one; the translation pipeline (§5.8) defaults to Ollama (this repo's own `local/` module) specifically so it depends on no paid or third-party-hosted service — see §8's GitHub-Models-retirement finding for why that default matters |
| **Risk** | marola's own code changes fast (dozens of commits, several whole MIPs' worth of tasks, landed on `main` inside 2026-09-05 alone — confirmed via `git log`) — listings extracted today are stale by the next PR unless the extraction is pinned and CI-checked, not hand-copied once and forgotten; translation adds a second copy of the same risk on the pt-BR side (see §8) |
| **Cost so far** | — (nothing merged yet; the writing itself won't fit the existing `Cost:` trailer cleanly — see §11 open question 6) |

## 1. Summary

marola has more written-down design and honesty than most personal projects (`PHILOSOPHY.md`,
eleven MIPs, an effects map, a benchmarks ledger), but all of it is reference
material, answering "what did we build" one file at a time, never "how would you build this, and
why these specific choices, read start to finish." This MIP proposes turning that material into a
real technical book, **written in LaTeX, versioned in a git repository, built to PDF in CI on every
push and released on every tag**, the same production discipline this repo already applies to
code, aimed at prose instead. It follows the shape of self-published FP books that already do this
(Sandy Maguire's *Thinking with Types*, Bartosz Milewski's *Category Theory for Programmers*), not
a from-scratch toolchain design.

## 2. Motivation

- **The material exists; the narrative doesn't.** `docs/SKILLS.md` is explicit that it is "not just
  'check which boxes marola happens to tick'" but a roadmap to *practice*; yet it's a roadmap, not
  something a reader follows front-to-back with worked examples and their own compiler pushing
  back. A book is the form that roadmap wants to take.
- **A named literature gap.** `docs/FUTURE-WORK.md` §10 surveyed the Python LLMOps ecosystem
  (DSPy, Langfuse, Promptfoo, DeepEval/RAGAS) and found "no confirmed Scala/JVM equivalent today"
  for any of it, and no book either. Maguire and Volpe have both proven a self-published, code-
  grounded FP book sells to working Scala/Haskell developers (§4); nobody has written the
  "grounded, local-first LLM product on the JVM" version.
- **`PHILOSOPHY.md` already reads like a book's introduction.** "Put the model inside a platform
  that pushes back" is a thesis statement, not a README paragraph; quote it once, then spend
  eleven chapters proving it against real, dated, sourced code, the way the MIPs already insist on
  ("verified live," "written, not run," "not checked").
- **The house rule for what counts as done maps directly onto book-writing.** `AGENTS.md`'s honest
  status vocabulary and `mip` skill's "verify every external claim" are exactly the standard a
  technical book needs and most don't meet; this book gets that discipline for free from the repo
  it's about.

## 3. User-visible change

None for the swimmer: nothing here touches `Main`, the Telegram bot, or any pluggable integration.
For a reader/developer, once built:

```
$ git clone <marola-book repo> && cd marola-book
$ nix develop
$ just book-build                         # latexmk -xelatex, TeX Live via Nix, no local install
  extracting listings @ marola-src bd83a1f (12 files, 340 lines) ... ok
  translating (pt-BR): 2 segment(s) changed, 214 unchanged (translation-memory hit) ... ok
  chapter 05-review-pass.tex ... ok
  → dist/en/marola-book.pdf (187 pages)
  → dist/pt-BR/marola-book.pdf (191 pages)
$ just book-watch                         # latexmk -pvc, rebuild on save

# CI, on every push to main: the same `just book-build`, both language PDFs attached as
#   workflow artifacts
# CI, on a tag (v0.1.0): matrix build (en × pt-BR, screen × print) → draft GitHub Release with
#   marola-book--v0.1.0.pdf, marola-book-print--v0.1.0.pdf, marola-book-pt-BR--v0.1.0.pdf,
#   marola-book-pt-BR-print--v0.1.0.pdf attached
```

## 4. Prior art reviewed — self-published FP books with public source

All fetched 2026-09-05.

### 4.1 Sandy Maguire — *Thinking with Types* and *Algebra-Driven Design*

- **[isovector/thinking-with-types](https://github.com/isovector/thinking-with-types)**
  ("📖 source material for Thinking with Types"): written in **LaTeX**. The author's own
  organizing principle, quoted from the repo: "make it as hard as possible to fuck up": code
  samples are automatically tested and GHCi sessions automated, not pasted. Build: a `Makefile`,
  three custom build tools the author wrote, a `build-epub.sh` for the ebook. Sold on **Leanpub**;
  the README tells readers to just buy a copy rather than build from source; the repo is source-
  available, not a build-it-yourself invitation. **Lesson for marola:** tested/generated listings,
  not hand-pasted, is the one non-negotiable; a from-scratch build toolchain (three custom tools)
  is the cost of doing that in Haskell/LaTeX with no existing template, reason enough to reuse
  Milewski's toolchain (§4.3) instead of inventing one.
- **[isovector/algebra-driven-design](https://github.com/isovector/algebra-driven-design)**: the
  *opposite* choice from the same author, written entirely in **Markdown** with a custom Pandoc
  filter (a separate project, `design-tools`) for code inlining, annotations, and typography,
  explicitly because LaTeX-as-both-content-and-programming-language became "an eventual liability"
  for multi-format (ePub) output on the first book. CC BY-NC-ND. Also sold on Leanpub. **Lesson:**
  the same author changed his mind between books: LaTeX gives layout control at the cost of ePub/
  multi-format flexibility; Markdown+Pandoc trades the reverse. marola's book is PDF-first (§4.5
  decides), so this doesn't change the pick, but it's the right caveat to carry forward if an ePub
  edition is ever wanted.

### 4.2 Gabriel Volpe — *Practical FP in Scala* and *Functional Event-Driven Architecture*

Neither book's LaTeX/Markdown source is public; both are sold on Leanpub
([`leanpub.com/pfp-scala`](https://leanpub.com/pfp-scala), [`leanpub.com/feda`](https://leanpub.com/feda))
with no visible source repo for the manuscript itself. What *is* public and instructive:
**companion code, separate from the manuscript, with full CI**:
[`gvolpe/pfps-shopping-cart`](https://github.com/gvolpe/pfps-shopping-cart) (the shopping-cart app
built across the book, GitHub Actions CI, `shell.nix`, `.scalafmt.conf`/`.scalafix.conf`, Apache-2.0),
[`gvolpe/pfps-examples`](https://github.com/gvolpe/pfps-examples) (standalone snippets), and
[`gvolpe/trading`](https://github.com/gvolpe/trading) for FEDA. **Lesson for marola, and the
strongest single argument in §5.2's repo-shape call:** Volpe ships the *code* as a real, CI-green,
Nix-shelled, independently useful open-source project, and keeps the *manuscript* closed and sold
separately. marola inverts this: the "companion code" is the marola repo itself, already public
and MIT, so the book repo's job is narrower than Volpe's: prose plus a listing-extraction pipeline
pointed at code that already exists and is already tested, not a whole second codebase to maintain.

### 4.3 Bartosz Milewski — *Category Theory for Programmers* (community LaTeX port)

[`hmemcpy/milewski-ctfp-pdf`](https://github.com/hmemcpy/milewski-ctfp-pdf) ("Bartosz Milewski's
'Category Theory for Programmers' unofficial PDF and LaTeX source," reproduced with permission from
his blog series). This is the closest architectural match to what marola needs, and the pick this
MIP recommends copying almost wholesale:

- **Layout:** `src/content/` (chapters), `src/fig/` (figures), `preamble.tex` (style/config): one
  `.tex` file per chapter, four language editions (Scala/Haskell/OCaml/Reason) built from the same
  content via conditionals.
- **Toolchain:** `xelatex` through `latexmk`, `-shell-escape` for `minted` (Pygments syntax
  highlighting), `tikz` for diagrams, `.latexindent.yaml` for consistent `.tex` formatting.
- **Nix:** a `flake.nix`/`flake.lock` builds a full TeX Live combination (including `minted`,
  `tikz`, `listings`) plus a custom Inconsolata LGC font derivation; `nix develop` gives a working
  shell, `nix build .#<edition>` builds one PDF variant. Directly reusable pattern: this repo
  already commits to Nix for the exact same reproducibility reason (`PHILOSOPHY.md` "Why Nix").
- **CI:** four GitHub Actions workflows: `nix-flake-check.yaml` (the flake evaluates and builds),
  `nix-fmt-checks.yaml`, `prettier-checks.yaml` (non-Nix files), and **`release.yaml`**: triggered
  on `push: tags: "**"`, matrix-builds every package (`nix build .#${{ matrix.packages }}`),
  uploads each as a workflow artifact, then creates a draft GitHub Release for the tag and attaches
  each PDF as `{package}--{tag}.pdf`. This is the exact CI shape §5.4 proposes for marola's book.
- **Licence:** CC BY-SA 4.0 for the PDF/`.tex`/figures; GPLv3 for the build scripts (`scraper.py`).

### 4.4 The HoTT Book — a second LaTeX-and-Make reference, academic register

[`HoTT/book`](https://github.com/HoTT/book) ("A textbook on informal homotopy type theory"), CC
BY-SA 3.0. One `.tex` file per chapter (`basics.tex`, `equivalences.tex`, `homotopy.tex`,
`logic.tex`, `induction.tex`, ...), a `Makefile` with targets for six output variants (`hott-
online.pdf`, `hott-ebook.pdf`, `hott-letter.pdf`, `hott-a4.pdf`, `hott-arxiv.pdf`, plus an
exercises/solutions split) built from one shared source via a generated `version.tex`, and a
`.travis.yml` for CI (pre-GitHub-Actions vintage, but the same idea: a scripted, non-manual build
gate). **Lesson:** multiple named output targets from one source (screen vs. print vs. arXiv) is a
pattern worth keeping even though marola's book needs fewer variants than an academic text with six
sibling formats; the Make-target-per-variant idea, not the specific six.

### 4.5 Toolchain pick: LaTeX via `latexmk`/TeX Live under Nix, not Tectonic, not Pandoc

- **Tectonic** (a self-contained, single-binary LaTeX engine) fits this repo's "one reproducible
  toolchain" instinct even better than a full TeX Live closure on paper, and gained `-Z shell-
  escape` support after years without it
  ([tectonic-typesetting/tectonic#708](https://github.com/tectonic-typesetting/tectonic/pull/708),
  merged). But `\inputminted` (the exact command needed to pull a real Scala file into the PDF
  verbatim) is filed as broken against it as of
  [issue #835](https://github.com/tectonic-typesetting/tectonic/issues/835) and
  [#1066](https://github.com/tectonic-typesetting/tectonic/issues/1066) (checked 2026-09-05, both
  open). Not viable today for a listings-heavy book.
- **Pandoc, Markdown → LaTeX/PDF**: lower authoring friction, matches Maguire's own second-book
  pivot (§4.1) for ePub flexibility marola's book doesn't need yet (PDF-first, per §11). Weaker at
  the things a reference-heavy technical book leans on hardest: cross-references (`\label`/`\ref`
  to a numbered listing three chapters later), a real index, and fine control of how a `minted`/
  `listings` block breaks across a page, without dropping into raw LaTeX blocks anyway, which
  gives up Markdown's main advantage.
- **Pick: `latexmk -xelatex -shell-escape` (or plain `pdflatex` if `minted` proves unnecessary:
  the `listings` package with a hand-rolled Scala language definition avoids the Pygments/shell-
  escape dependency entirely and is worth trying first, §11 OQ4) run inside a Nix-provided TeX Live
  combination, copying §4.3's flake almost directly.** It is the only option of the three that is
  both proven at this exact job (Milewski, Maguire, HoTT all ship this way) and compatible with
  `\inputminted`/`\lstinputlisting` pulling a real file path, verbatim, at build time.

### 4.6 The maintainer's own `forecast-energy-demand` repo — prior art and a token-efficiency case study

Fetched 2026-09-05. The repo meant is
[`h0ffmann/forecast-energy-demand`](https://github.com/h0ffmann/forecast-energy-demand):
"forecasting-energy-demand" (the name given in the request) 404s on the GitHub API; confirmed via
`api.github.com/users/h0ffmann/repos`. A UFRJ/Escola Politécnica undergraduate thesis that builds a
LaTeX thesis and machine-translates it PT-BR → EN-US in CI, the closest prior art to this MIP's own
multi-language ask (all paths below confirmed via the GitHub API and raw file fetches, `main`).

**Layout**: `docs/project/pt/` (source of truth: `cover.tex`, `project_main.tex`; `cap1.tex`…
`cap6.tex` planned per its `AGENTS.md`, not yet written), `docs/project/en/` (generated, never
hand-edited), `docs/project/shared/` (preamble/style/`refs.bib`, symlinked into both). **Translation**:
[`scripts/translate_latex.py`](https://github.com/h0ffmann/forecast-energy-demand/blob/main/scripts/translate_latex.py)
calls `meta/llama-3.3-70b-instruct` over the GitHub Models inference endpoint with the
Actions-provided `GITHUB_TOKEN`; one whole `.tex` file is one prompt, and a fixed system prompt
("never modify LaTeX commands... return only the translated LaTeX") is the *only*
markup-preservation mechanism, nothing strips markup first. Change detection: a SHA-256 hash of
each *whole file* against a committed
[`.translation-cache.json`](https://github.com/h0ffmann/forecast-energy-demand/blob/main/docs/project/.translation-cache.json)
(confirmed content: `cover.tex`, `project_main.tex`, and, because `ensure_symlinks()` links
`thesis_pack.tex` into `pt/` too and the glob is unconditional, `thesis_pack.tex`, a 25-line,
zero-prose preamble file "translated" anyway). **Build**: `.github/workflows/thesis-pdf.yml`
installs Tectonic via a GitHub Action (not Nix: no `flake.nix` anywhere here), runs the
translator, builds PT-BR/EN-US as two independent `tectonic` invocations, commits both PDFs plus
the cache back on `main`. **A confirmed gap**: the translation call sits in a bare
`except Exception` that prints `FAILED` and skips the cache update but does **not** exit non-zero;
the workflow builds `en/...` regardless, from a stale or missing file; nothing in CI fails when a
translation silently didn't happen. Not reviewed (out of scope): `packages/`, `main.py`, the
forecasting code itself.

#### 4.6.1 Token-efficiency review, and concrete improvements

Where the tokens go, worst first: whole-file granularity (a one-word fix re-sends the whole
chapter); markup protected by instruction, not removed (`\label{}`/`\cite{}`/`\ref{}`/comments
ride along as billed input *and* output); the symlinked, zero-prose `thesis_pack.tex` gets
"translated" on every hash change; no pinned glossary (a planned review agent catches term drift
after the fact instead); one call per file, so the fixed system-prompt cost never amortizes as
chapter count grows; no placeholder-survival check or stronger-model review pass; a human reading
the PDF is the only gate; no CI gate for a missing translation, per the confirmed gap above.
Fixes, each tied to the waste it targets: a **translation memory keyed by segment content hash**
(one JSON entry per paragraph, gettext-`.po` style) so a one-paragraph edit re-translates one
paragraph, not a chapter; **placeholder-protect markup before the model sees it**: swap
`\cite{}`/`\label{}`/`\ref{}`/math and, for marola, every `\lstinputlisting`/`\inputminted` line
(§5.3: these reference a real file path, so code text never needs to reach a prompt at all) for
short numbered placeholders, fewer tokens, and "model touched a label" is prevented structurally;
**pin a glossary once** instead of a post-hoc checker; **batch every changed segment per chapter
into one call**: not per-file (too coarse), not per-paragraph (the fixed prompt cost then
dominates); **Ollama by default, a stronger model only for reviewer-flagged segments**: this
repo's own local-first pattern (`ARCHITECTURE.md` §5) and not just an efficiency call: GitHub
Models, the free tier this sibling repo depends on, **was fully retired 2026-07-30** (§8 has the
citation and what it means for the script as committed); **a mechanical placeholder-survival
check** (count placeholders source vs. translation, diff, zero LLM calls) instead of re-translating
to verify correctness; **a CI gate failing on any segment hash with no translation-memory entry**,
the role `check_listings.py` (§5.3) already plays for code drift.

#### 4.6.2 Token estimate: naive whole-file vs. segment-level, for a book this size

Assumptions: **~5,000 words** English prose/chapter; **~1.33 tokens/word**; raw markup sent whole
adds **~30%** overhead (placeholders add **~8%**); translated output ≈ source token count;
**~3,600 tokens** stands in for markup/short-listing overhead the naive approach still pays even
though `\lstinputlisting` never embeds code inline (§5.3); 12 units (11 chapters + conclusion,
§5.2); **8 revisions/chapter** after the first pass (96 events book-wide).

| | Naive whole-file | Segment-level + placeholders |
|---|---|---|
| Per-chapter first pass | 5,000×1.33×1.30 + 3,600 ≈ 12,245 in, ≈12,245 out → **≈24,500** | 5,000×1.33×1.08 ≈ 7,182 in, ≈7,182 out → **≈14,364** |
| Book-wide first pass (×12) | **≈294,000** | **≈172,368** (**~41% less**, markup/listing cuts alone) |
| Per revision (96 total) | whole chapter resent: **≈24,500** | 1 paragraph (≈180 tok w/ placeholders) + ~800 fixed ≈ **≈1,000** |
| 96 revisions | **2,352,000** | **96,000** |
| **Lifecycle total** | **≈2.65M tokens** | **≈268,000 tokens (~10× less)** |

Most of the saving is segment-level caching turning a one-paragraph edit into a one-paragraph
re-send; markup stripping alone drives only the ~41% cold-start win.

## 5. Design

### 5.1 Working title and audience

**Working title:** *The Compiler Pushes Back* (subtitle *Building a Grounded, Local-First LLM
Product in Scala 3*), taken directly from `PHILOSOPHY.md`'s own thesis sentence ("tighten the
platform so the model's mistakes have somewhere to fail"), so the title is load-bearing, not
decorative. **Alternatives:** *Marola: A Field Guide to Grounded AI in Scala* (safer, more
literal); *No Unsourced Facts Reach a User* (the `mip` skill's own rule, punchier, riskier as a
cover title).

**Primary audience (picked, per this MIP's instructions): working Scala developers who want to
build a real LLM-backed product, not a chatbot demo** — people who already know Scala and want the
missing piece Volpe's and Maguire's books don't cover: an LLM sitting inside a product with live
data, a safety-relevant deterministic core, and a review pass, built with Kyo instead of
cats-effect. Open-water swimmers are a real secondary audience (the back cover can say so) but
the chapter order below is written for the primary one.

### 5.2 Chapter outline, mapped to real files

| # | Chapter | Grounded in |
|---|---|---|
| 1 | The one decision — why constrain the model instead of trusting it | `PHILOSOPHY.md` |
| 2 | The pipeline: beaches, weather, and a ranked list before any LLM runs | `ARCHITECTURE.md` §3-4, `Recommender.scala`, `BeachFinder.scala`, `OpenMeteoClient.scala` |
| 3 | Pure scoring, effectful boundary — Kyo at the edge, not the core | `scoring/Swimability.scala`, `docs/EFFECTS-MAP.md`, `AGENTS.md`'s code-style section |
| 4 | Six pluggable integrations, one shape — trait, local default, opt-in backend | `ARCHITECTURE.md` §5's table, `AppConfig.scala` |
| 5 | Turning a row of numbers into a sentence, twice — synthesis and review | `llm/LlmClient.scala`, `llm/CompiledPrompt.scala`, `llm/Reviewer.scala` (§5a) |
| 6 | Compiling the prompt instead of hand-tuning it — DSPy in, Scala out | `dspy/compile_recommendation_prompt.py`, MIP-0012 (the Scala-native successor) |
| 7 | Answering from a sourced corpus — RAG over the sea-lore knowledge base | `knowledge/`, `OllamaEmbedder`, `OceanQa`, `docs/benchmarks/2026-09-05.md` |
| 8 | Measuring the thing that measures itself — the benchmark harness | `cli/bench/OceanBenchmark.scala`, `scripts/benchmark_gate.py` |
| 9 | An experiment ledger for free — MLflow, traces, and the run history | MIP-0010, `RunLedger`, `Tracing` |
| 10 | Shipping it — Docker images, the static map, GitHub Pages | MIP-0008, MIP-0005, `Dockerfile`, `site/` |
| 11 | The loop that wrote this book's own code — MIPs, agents, and the cost of asking | `AGENTS.md`, `docs/DEV-FLOW.md`, the `mip` skill, the `Cost:` trailer |
| — | Conclusion — what's still open, honestly | `docs/FUTURE-WORK.md` |

Each chapter ends with a boxed "verify this yourself" sidebar naming the exact `just` recipe or
live check from the source doc, in the same honest-status vocabulary the repo already uses; a
chapter is not allowed to sound more certain than the code it describes.

### 5.3 Keeping listings true — extraction, not paste

Every code listing is `\lstinputlisting[linerange=...]{...}` (or `\inputminted`, if the Pygments
path is kept, §4.5) pointed at a real path inside a **pinned checkout of the marola repo**, never
retyped by hand. Mechanically: the book repo vendors marola as a `git submodule` pinned to a commit
SHA (printed on the book's copyright page, e.g. "listings verified against marola @ bd83a1f");
`just book-build` first runs `scripts/check_listings.py` (a new script, mirroring
`scripts/benchmark_gate.py`'s style) which greps every `\lstinputlisting`/`\inputminted` path
against the submodule checkout and fails the build if a path or a named line range no longer
exists: the same "give the mistake a wall to hit" idea `PHILOSOPHY.md` states for the app itself,
applied to the book. Bumping the submodule SHA is a normal PR, reviewed like any other, and a
failing `check_listings.py` run is exactly the signal that a chapter needs a rewrite, not silent
drift. Scala-cli–checked standalone snippets (for code that doesn't exist verbatim in the app,
e.g. a simplified teaching example) are a second, clearly-labeled category, each compiled by CI
before the book builds, never left uncompiled prose-code.

### 5.4 Repository shape: a separate `marola-book` repo — recommended

**Recommendation: a separate `marola-book` repository**, marola vendored in as a pinned submodule,
not a `book/` directory inside this repo. **The trade-off, in one paragraph:** a `book/` directory
keeps everything in one place and lets a single PR update code and the chapter describing it
together, but it pulls a multi-hundred-MB TeX Live closure into every contributor's `nix develop`
and CI cache for a concern most contributors (and CI runs) never touch, mixes book-writing commits
into the `Cost:`/PR cadence `AGENTS.md` built around code features (see §11 OQ6), and ties the
book's own release tags to the app's tag namespace; a separate repo costs exactly the submodule-pin
step §5.3 already needs regardless of where the book lives, and buys a toolchain, a release
cadence, and a cost ledger that are the book's own, the shape Volpe's companion-code repos and
Milewski's book repo both already use (§4.2, §4.3), just with the public/private sides swapped from
Volpe's case since marola's "companion code" is already the public main repo.

### 5.5 CI

Copying §4.3's `milewski-ctfp-pdf` shape directly: a `nix-flake-check` workflow builds the PDF on
every push (fails the PR if `latexmk`/`check_listings.py` fails); a `release` workflow triggers on
`push: tags: v*`, matrix-builds a screen and a print variant, and attaches both to a draft GitHub
Release named after the tag. No self-hosted runner, no paid service: GitHub Actions' free minutes
cover a LaTeX build the same way they already cover `ci.yml` for the app (`AGENTS.md` cost rule is
about paid cloud resources, not CI minutes, but the same "free by default" instinct applies).

### 5.6 Licensing

Proposed as **options for a human decision, not settled here** (per this MIP's own instructions):
prose under **CC BY-NC-SA 4.0** (matches the *spirit* of Maguire's Algebra-Driven Design licence:
attribution required, no commercial reuse of the text, share-alike) if the book stays free/PDF-only,
or **all-rights-reserved** if it's sold (Leanpub's own default author terms; a paid book commonly
reserves rights while the platform handles distribution). **Code listings inside the book stay MIT**
regardless: they're excerpts of files already under this repo's `LICENSE` (confirmed: root
`LICENSE` is MIT, copyright M. Hoffmann, 2026), so the book cannot license them more restrictively
than the source already is.

### 5.7 Distribution — options, not a decision

- **Leanpub** (§4.1, §4.2's precedent): 80% royalty on sales ≥ $7.99, 80% minus $0.50 below that
  (confirmed, [Leanpub Help Center](https://help.leanpub.com/en/articles/5468013-what-is-leanpub-s-royalty-rate-are-there-any-restrictions-on-where-i-can-self-publish-my-book-and-what-price-i-can-charge),
  fetched 2026-09-05); supports iterative "publish early, publish often", a real fit for a book
  about a POC that itself ships in small PRs.
- **Gumroad**: 10% + $0.50 per direct sale, no monthly fee, acts as merchant of record for tax
  since 2025-01-01 (confirmed via aggregator coverage, fetched 2026-09-05; Gumroad's own fee page
  was not fetched directly, flagged in §11 OQ7); simpler checkout, no built-in book-specific
  tooling (versions, reader web app) the way Leanpub has.
- **Free PDF from the GitHub Release + an optional paid print-on-demand** (Lulu/similar, not
  researched here): matches this repo's existing "free by default, paid tier opt-in" pattern
  (`ARCHITECTURE.md` §5's local-first split) applied to distribution instead of infrastructure.

### 5.8 Multi-language build

**Source: English; pt-BR the first target**, proposed. §5.1 already picked "working Scala
developers" globally and drafted every chapter title in English, so sourcing in English keeps that
consistent; the trade-off is Brazilian readers (a real secondary audience, and the author's own
first-language reviewers) waiting for a translated edition, the mirror of `forecast-energy-demand`'s
choice (pt-BR source, a UFRJ requirement, translated to EN-US for its cited literature).

**Layout**, replacing §5.2's implicit single-language assumption: `src/en/chNN-slug.tex` (source of
truth), `src/pt-BR/chNN-slug.tex` (generated only), `src/shared/` (preamble/figures/`refs.bib`,
never duplicated), `i18n/translation-memory.json` (segment hash → `{source_hash, translated,
reviewed}`), `i18n/glossary.json` (pinned EN→pt-BR terms). Mirrors `forecast-energy-demand`'s
`pt/`/`en/`/`shared/` split (§4.6) with source/target reversed and the whole-file cache replaced by
the segment-keyed memory (§4.6.1).

**Build matrix.** `just book-build` runs one `latexmk -xelatex` per language directory →
`dist/en/marola-book.pdf`, `dist/pt-BR/marola-book.pdf`; listing extraction and
`check_listings.py` (§5.3) run once, not per language. The CI release job (§5.5) becomes a
2-language × 2-variant matrix, four PDFs per tag (§3).

**Translation pipeline** (§4.6.1 applied): a chapter is segmented once stable (after English
review, so translating mid-draft doesn't churn the memory); each segment is placeholder-protected,
hashed, looked up in the memory; unmatched or `reviewed: false` segments are batched one call per
chapter to Ollama by default (zero-cost, no vendor free tier to retire out from under the build); a
stronger hosted model is opt-in, reserved for reviewer-flagged segments only.

**Hand-reviewed vs. automated.** Automated: segmentation, hashing, placeholder swap/restore, the
batched call, the placeholder-survival check, and a CI gate failing on any un-translated or
unreviewed hash. Always hand-reviewed: a `reviewed` flag starts `false` and is flipped only by a
human reading pt-BR against English: `mip`'s "no unsourced facts reach a user" rule, for prose.

**`Cost:` accounting.** A hosted-model flagged-segment pass gets the usual `Cost:` trailer
(`AGENTS.md`) on the commit updating the translation memory; an Ollama-only run reads
`Cost: $0 · Ollama local · N segments retranslated`.

## 6. Scoring / safety impact

None. No product code, no scoring logic, no user-facing output changes.

## 7. Verification plan

- `scripts/check_listings.py` (new): every `\lstinputlisting`/`\inputminted` path+line-range in the
  `.tex` sources resolves inside the pinned submodule checkout; a `just quality`-equivalent gate in
  the book repo's own CI, not this repo's.
- Live check: `just book-build` produces a PDF with a non-zero, sane page count, no LaTeX
  `Overfull \hbox` past a threshold, no unresolved `\ref`/`\cite`.
- A human read-through of chapter 1 (the spike, §11 OQ1) before committing to the other ten; this
  MIP's own "Effort vs Gain" call is conditional on that spike actually reading well.
- **Done, for this MIP's own scope:** Draft accepted; **done, for the spike**: chapter 1 plus the
  Nix/CI toolchain build a real PDF in CI and a human confirms the chapter is worth continuing.
- **Effort estimate:** roughly 15-30 author-hours per chapter (draft, listing extraction, revision)
  across 11 chapters plus a conclusion ≈ 200-300 hours, plus ~40-60 hours of one-time tooling
  (flake, CI, `check_listings.py`, a cover, a first copyedit pass), a rough order-of-magnitude
  estimate, not a schedule; most of these are human writing hours, not agent-session hours, which
  is exactly why the usual `Cost:` trailer doesn't map cleanly here (§11 OQ6).

## 8. Risks, limitations, and honest caveats

- **Code drift is the central risk** (see the metadata table). A submodule pin turns "drift" into a
  visible, reviewable diff instead of silent staleness, but only if `check_listings.py` actually
  runs in CI; an unenforced convention here would be worse than no automation at all.
- **This is a large, mostly-human writing effort competing with Phase 1.** `AGENTS.md`'s phase
  discipline exists to stop exactly this kind of "more interesting, ships later" distraction; this
  MIP's own Effort-vs-Gain verdict says defer, and that's a real constraint, not boilerplate.
- **The `Cost:` trailer convention doesn't fit prose well.** `just cost-split` prices Claude Code
  sessions against code-shaped PRs; a chapter written over several long human-editing sessions with
  small agent-assisted diffs will under- or over-count against that model, flagged, not solved,
  here (§11 OQ6).
- **A LaTeX/Nix toolchain is a new maintenance surface**, even reused from Milewski's proven flake:
  TeX Live closures are large and occasionally break on nixpkgs bumps; scoped to its own repo
  (§5.4) specifically so this risk never touches the app's CI.
- **Selling a book about an unfinished POC is a credibility risk if the honesty vocabulary slips.**
  The whole pitch is that this book is as verified as the MIPs it's based on; a chapter that
  overclaims paths that are "written, not run" would undercut the book's own thesis.
- **A stale or partial pt-BR PDF shipped silently is worse than no translation at all**: §5.8's CI
  gate (fail on any un-translated/unreviewed segment hash) exists to make that impossible, the same
  role `check_listings.py` (§5.3) plays for code drift. Not hypothetical: `forecast-energy-demand`'s
  own pipeline (§4.6) depends on GitHub Models, which GitHub **fully retired 2026-07-30**
  ([confirmed](https://docs.github.com/en/github-models/prototyping-with-ai-models), fetched
  2026-09-05); its translation script, last touched 2026-04-17, now calls a dead endpoint. A vendor
  free tier can vanish with no warning; §5.8's Ollama default exists so this path can't fail the
  same way (a repo-wide grep found marola references it nowhere today, not an existing exposure,
  just the argument for the default).

## 9. Alternatives considered

- **Do nothing**: the docs already serve developers who read `docs/`; a book adds narrative and
  reach, not new information. Real, but the literature-gap argument (§2, `FUTURE-WORK.md` §10)
  says the *form* is worth the effort on its own.
- **mdBook / Docusaurus (Markdown-native, like *The Rust Programming Language*)**: lower authoring
  friction, free hosting as a website, no print-quality PDF story, no precedent in this specific
  genre (self-published FP books all skew LaTeX or Pandoc-from-Markdown, §4); rejected because the
  user's request specifically named LaTeX and this tradition.
- **Pandoc, Markdown → LaTeX**: see §4.5; kept as the fallback if `listings`/`minted` prove more
  friction than expected during the chapter-1 spike (§11 OQ1).
- **A `book/` directory in this repo instead of a separate repo**: see §5.4's trade-off paragraph.
- **Blog-post series instead of a book**: lower effort, no PDF/CI story, doesn't test the
  "extraction not paste" listings discipline this MIP considers the actual point; also loses the
  Leanpub-style "publish early, publish often" cadence a real book format buys.

## 11. Open questions

1. **Spike first.** Write chapter 1 plus the Nix/CI toolchain (§5.5) as a single bounded task before
   committing to all eleven chapters; the Effort-vs-Gain verdict above is conditional on that spike
   actually being worth continuing. If the spike includes a translated sample (§5.8), keep it to one
   chapter, not the full outline.
2. **Title and audience, human decision.** The working title/audience in §5.1 is this MIP's
   proposal, not a settled choice; confirm before the spike's cover page is drawn.
3. **`listings` (a hand-rolled Scala language definition) vs `minted`/Pygments**: try the former
   first (§4.5) to avoid the shell-escape/Pygments dependency entirely; fall back to `minted` only
   if Scala syntax highlighting via `listings` proves too weak for 3-syntax (Scala/Python/bash)
   listings.
4. **Licensing, human decision**, §5.6's two options need an actual choice before any public
   release, tied to the distribution choice in §5.7.
5. **Gumroad's own fee page was not fetched directly** (§5.7); aggregator coverage only; confirm
   against `gumroad.com`'s own pricing page before quoting a number in the book itself.
6. **How does `AGENTS.md`'s `Cost:` trailer apply to a mostly-human writing project?** Worth a small
   follow-up note in `AGENTS.md` once the spike produces real numbers, rather than guessing here.
7. **Print-on-demand vendor** (§5.7's third option); not researched; a later open question if the
   free-PDF-plus-paid-print path is chosen.
8. **Whether to freeze a marola commit per book "edition"** (like a Leanpub version) or track `main`
   continuously via the submodule bump; affects how often `check_listings.py` needs re-running and
   how a reader's PDF page numbers stay stable across printings.
9. **When to start pt-BR translation** (§5.8): per-chapter as English lands, or only once the
   first edition is stable; affects how much the translation memory churns mid-draft; the spike
   (OQ1) should pick one. A second language beyond pt-BR is a scope decision, not a redesign,
   if it comes up later; `src/<lang>/` adds one by adding a directory.

## Appendix

Fetched 2026-09-05 unless noted:
[isovector/thinking-with-types](https://github.com/isovector/thinking-with-types);
[isovector/algebra-driven-design](https://github.com/isovector/algebra-driven-design) and its
[README](https://github.com/isovector/algebra-driven-design/blob/master/README.md);
[leanpub.com/algebra-driven-design](https://leanpub.com/algebra-driven-design);
[gvolpe/pfps-shopping-cart](https://github.com/gvolpe/pfps-shopping-cart),
[gvolpe/pfps-examples](https://github.com/gvolpe/pfps-examples), [gvolpe/trading](https://github.com/gvolpe/trading);
[leanpub.com/pfp-scala](https://leanpub.com/pfp-scala), [leanpub.com/feda](https://leanpub.com/feda);
[hmemcpy/milewski-ctfp-pdf](https://github.com/hmemcpy/milewski-ctfp-pdf) and its
`.github/workflows/{nix-flake-check,nix-fmt-checks,prettier-checks,release}.yaml`, `flake.nix`;
[HoTT/book](https://github.com/HoTT/book);
[tectonic-typesetting/tectonic#708](https://github.com/tectonic-typesetting/tectonic/pull/708)
(shell-escape merged), [#835](https://github.com/tectonic-typesetting/tectonic/issues/835) and
[#1066](https://github.com/tectonic-typesetting/tectonic/issues/1066) (`\inputminted` open bugs);
[Leanpub royalty terms](https://help.leanpub.com/en/articles/5468013-what-is-leanpub-s-royalty-rate-are-there-any-restrictions-on-where-i-can-self-publish-my-book-and-what-price-i-can-charge);
Gumroad fees, aggregator coverage only (not the primary source; see §11 OQ5):
[roo.beehiiv.com/p/gumroad-fees-2026](https://roo.beehiiv.com/p/gumroad-fees-2026).
Not fetched/verified: Leanpub's or Gumroad's terms of service in full; any print-on-demand vendor;
whether `nixpkgs`' current `texlive.combine` closure size has grown materially since
`milewski-ctfp-pdf`'s flake was last updated; a live `nix build` of that flake was not run in this
session (no such toolchain installed here); the flake's contents were read, not executed.
§4.6/§8 additionally fetched: [h0ffmann/forecast-energy-demand](https://github.com/h0ffmann/forecast-energy-demand)
(full tree, `AGENTS.md`, `README.md`, `justfile`, `.github/workflows/thesis-pdf.yml`,
`scripts/translate_latex.py`, `.translation-cache.json`, `cover.tex`, `project_main.tex`,
`thesis_pack.tex`); [GitHub Models retirement notice](https://docs.github.com/en/github-models/prototyping-with-ai-models).
