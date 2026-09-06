# MIP-0014: A marola book — *The Compiler Pushes Back*, written in LaTeX, versioned in a repo, built in CI

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-05: "a MIP for a marola-related book, technical, in LaTeX, versioned in a repository, built in CI, in the tradition of self-published FP books") |
| **Created** | 2026-09-05 |
| **Phase** | 0 — a documentation/tooling project, nothing a swimmer sees; independent of the app's own phase gate, but see Effort vs Gain below for why it should still wait |
| **Related** | `PHILOSOPHY.md` (the book's own thesis — "the one decision" — is the book's spine); `docs/SKILLS.md` (the roadmap this book turns into a narrative); `docs/AI-103-MAPPING.md`, `docs/AI-500-MAPPING.md` (the exam framing, one audience option); `docs/DEV-FLOW.md` (the MIP/agent loop chapter); `docs/FUTURE-WORK.md` §10 ("the Scala/JVM gap in the prompt-engineering ecosystem" — a marola book is a small dent in the literature gap, not just the code gap); `docs/mips/MIP-0005` (map site), `MIP-0008` (Docker images), `MIP-0010` (MLflow), `MIP-0012` (llm4s/DSPy deprecation) — each is a chapter's grounding |
| **Effort** | XL — an 11-chapter first edition, a LaTeX/Nix/CI toolchain built from scratch, and a listing-extraction pipeline kept honest against a fast-moving codebase (see §7 estimate) |
| **Gain** | community/outreach (a citable, dated, verified account of a real local-first LLM product, in a genre — self-published FP books — that currently has zero Scala-native-LLM entries per `FUTURE-WORK.md` §10); exam-prep artifact (turns `docs/SKILLS.md`'s roadmap into a narrative); no direct product value for the swimmer |
| **Effort vs Gain** | expensive, defer — real value, but XL effort competing for the same author-hours as Phase 1 (the Telegram bot, MIP-0002, still Draft); write one chapter and the build pipeline first as a spike, not the whole outline, and don't let it displace Phase 1 |
| **Depends on** | Nothing technically (the book's toolchain is independent of the app); a stronger chapter 2 ("does it work for a real user") needs MIP-0002 (Telegram bot) shipped first — not a hard blocker, an honesty one |
| **Risk** | marola's own code changes fast (dozens of commits, several whole MIPs' worth of tasks, landed on `main` inside 2026-09-05 alone — confirmed via `git log`) — listings extracted today are stale by the next PR unless the extraction is pinned and CI-checked, not hand-copied once and forgotten |
| **Cost so far** | — (nothing merged yet; the writing itself won't fit the existing `Cost:` trailer cleanly — see §11 open question 6) |

## 1. Summary

marola has more written-down design and honesty than most personal projects — `PHILOSOPHY.md`,
eleven MIPs, an effects map, two exam mappings, a benchmarks ledger — but all of it is reference
material, answering "what did we build" one file at a time, never "how would you build this, and
why these specific choices, read start to finish." This MIP proposes turning that material into a
real technical book, **written in LaTeX, versioned in a git repository, built to PDF in CI on every
push and released on every tag** — the same production discipline this repo already applies to
code, aimed at prose instead. It follows the shape of self-published FP books that already do this
(Sandy Maguire's *Thinking with Types*, Bartosz Milewski's *Category Theory for Programmers*), not
a from-scratch toolchain design.

## 2. Motivation

- **The material exists; the narrative doesn't.** `docs/SKILLS.md` is explicit that it is "not just
  'check which boxes marola happens to tick'" but a roadmap to *practice* — yet it's a roadmap, not
  something a reader follows front-to-back with worked examples and their own compiler pushing
  back. A book is the form that roadmap wants to take.
- **A named literature gap.** `docs/FUTURE-WORK.md` §10 surveyed the Python LLMOps ecosystem
  (DSPy, Langfuse, Promptfoo, DeepEval/RAGAS) and found "no confirmed Scala/JVM equivalent today"
  for any of it — and no book either. Maguire and Volpe have both proven a self-published, code-
  grounded FP book sells to working Scala/Haskell developers (§4); nobody has written the
  "grounded, local-first LLM product on the JVM" version.
- **`PHILOSOPHY.md` already reads like a book's introduction.** "Put the model inside a platform
  that pushes back" is a thesis statement, not a README paragraph — quote it once, then spend
  eleven chapters proving it against real, dated, sourced code, the way the MIPs already insist on
  ("verified live," "written, not run," "not checked").
- **The house rule for what counts as done maps directly onto book-writing.** `AGENTS.md`'s honest
  status vocabulary and `mip` skill's "verify every external claim" are exactly the standard a
  technical book needs and most don't meet — this book gets that discipline for free from the repo
  it's about.

## 3. User-visible change

None for the swimmer — nothing here touches `Main`, the Telegram bot, or any pluggable integration.
For a reader/developer, once built:

```
$ git clone <marola-book repo> && cd marola-book
$ nix develop
$ just book-build                         # latexmk -xelatex, TeX Live via Nix, no local install
  extracting listings @ marola-src bd83a1f (12 files, 340 lines) ... ok
  chapter 05-review-pass.tex ... ok
  → dist/marola-book.pdf (187 pages)
$ just book-watch                         # latexmk -pvc, rebuild on save

# CI, on every push to main: the same `just book-build`, PDF attached as a workflow artifact
# CI, on a tag (v0.1.0): matrix build (screen + print variants) → draft GitHub Release with
#   marola-book--v0.1.0.pdf, marola-book-print--v0.1.0.pdf attached
```

## 4. Prior art reviewed — self-published FP books with public source

All fetched 2026-09-05.

### 4.1 Sandy Maguire — *Thinking with Types* and *Algebra-Driven Design*

- **[isovector/thinking-with-types](https://github.com/isovector/thinking-with-types)**
  ("📖 source material for Thinking with Types"): written in **LaTeX**. The author's own
  organizing principle, quoted from the repo: "make it as hard as possible to fuck up" — code
  samples are automatically tested and GHCi sessions automated, not pasted. Build: a `Makefile`,
  three custom build tools the author wrote, a `build-epub.sh` for the ebook. Sold on **Leanpub**;
  the README tells readers to just buy a copy rather than build from source — the repo is source-
  available, not a build-it-yourself invitation. **Lesson for marola:** tested/generated listings,
  not hand-pasted, is the one non-negotiable; a from-scratch build toolchain (three custom tools)
  is the cost of doing that in Haskell/LaTeX with no existing template — reason enough to reuse
  Milewski's toolchain (§4.3) instead of inventing one.
- **[isovector/algebra-driven-design](https://github.com/isovector/algebra-driven-design)**: the
  *opposite* choice from the same author — written entirely in **Markdown** with a custom Pandoc
  filter (a separate project, `design-tools`) for code inlining, annotations, and typography,
  explicitly because LaTeX-as-both-content-and-programming-language became "an eventual liability"
  for multi-format (ePub) output on the first book. CC BY-NC-ND. Also sold on Leanpub. **Lesson:**
  the same author changed his mind between books — LaTeX gives layout control at the cost of ePub/
  multi-format flexibility; Markdown+Pandoc trades the reverse. marola's book is PDF-first (§4.5
  decides), so this doesn't change the pick, but it's the right caveat to carry forward if an ePub
  edition is ever wanted.

### 4.2 Gabriel Volpe — *Practical FP in Scala* and *Functional Event-Driven Architecture*

Neither book's LaTeX/Markdown source is public — both are sold on Leanpub
([`leanpub.com/pfp-scala`](https://leanpub.com/pfp-scala), [`leanpub.com/feda`](https://leanpub.com/feda))
with no visible source repo for the manuscript itself. What *is* public and instructive:
**companion code, separate from the manuscript, with full CI**:
[`gvolpe/pfps-shopping-cart`](https://github.com/gvolpe/pfps-shopping-cart) (the shopping-cart app
built across the book, GitHub Actions CI, `shell.nix`, `.scalafmt.conf`/`.scalafix.conf`, Apache-2.0),
[`gvolpe/pfps-examples`](https://github.com/gvolpe/pfps-examples) (standalone snippets), and
[`gvolpe/trading`](https://github.com/gvolpe/trading) for FEDA. **Lesson for marola, and the
strongest single argument in §5.2's repo-shape call:** Volpe ships the *code* as a real, CI-green,
Nix-shelled, independently useful open-source project, and keeps the *manuscript* closed and sold
separately. marola inverts this — the "companion code" is the marola repo itself, already public
and MIT — so the book repo's job is narrower than Volpe's: prose plus a listing-extraction pipeline
pointed at code that already exists and is already tested, not a whole second codebase to maintain.

### 4.3 Bartosz Milewski — *Category Theory for Programmers* (community LaTeX port)

[`hmemcpy/milewski-ctfp-pdf`](https://github.com/hmemcpy/milewski-ctfp-pdf) ("Bartosz Milewski's
'Category Theory for Programmers' unofficial PDF and LaTeX source," reproduced with permission from
his blog series). This is the closest architectural match to what marola needs, and the pick this
MIP recommends copying almost wholesale:

- **Layout:** `src/content/` (chapters), `src/fig/` (figures), `preamble.tex` (style/config) — one
  `.tex` file per chapter, four language editions (Scala/Haskell/OCaml/Reason) built from the same
  content via conditionals.
- **Toolchain:** `xelatex` through `latexmk`, `-shell-escape` for `minted` (Pygments syntax
  highlighting), `tikz` for diagrams, `.latexindent.yaml` for consistent `.tex` formatting.
- **Nix:** a `flake.nix`/`flake.lock` builds a full TeX Live combination (including `minted`,
  `tikz`, `listings`) plus a custom Inconsolata LGC font derivation; `nix develop` gives a working
  shell, `nix build .#<edition>` builds one PDF variant. Directly reusable pattern: this repo
  already commits to Nix for the exact same reproducibility reason (`PHILOSOPHY.md` "Why Nix").
- **CI:** four GitHub Actions workflows — `nix-flake-check.yaml` (the flake evaluates and builds),
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
sibling formats — the Make-target-per-variant idea, not the specific six.

### 4.5 Toolchain pick: LaTeX via `latexmk`/TeX Live under Nix, not Tectonic, not Pandoc

- **Tectonic** (a self-contained, single-binary LaTeX engine) fits this repo's "one reproducible
  toolchain" instinct even better than a full TeX Live closure on paper, and gained `-Z shell-
  escape` support after years without it
  ([tectonic-typesetting/tectonic#708](https://github.com/tectonic-typesetting/tectonic/pull/708),
  merged). But `\inputminted` — the exact command needed to pull a real Scala file into the PDF
  verbatim — is filed as broken against it as of
  [issue #835](https://github.com/tectonic-typesetting/tectonic/issues/835) and
  [#1066](https://github.com/tectonic-typesetting/tectonic/issues/1066) (checked 2026-09-05, both
  open). Not viable today for a listings-heavy book.
- **Pandoc, Markdown → LaTeX/PDF**: lower authoring friction, matches Maguire's own second-book
  pivot (§4.1) for ePub flexibility marola's book doesn't need yet (PDF-first, per §11). Weaker at
  the things a reference-heavy technical book leans on hardest — cross-references (`\label`/`\ref`
  to a numbered listing three chapters later), a real index, and fine control of how a `minted`/
  `listings` block breaks across a page — without dropping into raw LaTeX blocks anyway, which
  gives up Markdown's main advantage.
- **Pick: `latexmk -xelatex -shell-escape` (or plain `pdflatex` if `minted` proves unnecessary —
  the `listings` package with a hand-rolled Scala language definition avoids the Pygments/shell-
  escape dependency entirely and is worth trying first, §11 OQ4) run inside a Nix-provided TeX Live
  combination, copying §4.3's flake almost directly.** It is the only option of the three that is
  both proven at this exact job (Milewski, Maguire, HoTT all ship this way) and compatible with
  `\inputminted`/`\lstinputlisting` pulling a real file path, verbatim, at build time.

## 5. Design

### 5.1 Working title and audience

**Working title:** *The Compiler Pushes Back* — subtitle *Building a Grounded, Local-First LLM
Product in Scala 3* — taken directly from `PHILOSOPHY.md`'s own thesis sentence ("tighten the
platform so the model's mistakes have somewhere to fail"), so the title is load-bearing, not
decorative. **Alternatives:** *Marola: A Field Guide to Grounded AI in Scala* (safer, more
literal); *No Unsourced Facts Reach a User* (the `mip` skill's own rule, punchier, riskier as a
cover title).

**Primary audience (picked, per this MIP's instructions): working Scala developers who want to
build a real LLM-backed product, not a chatbot demo** — people who already know Scala and want the
missing piece Volpe's and Maguire's books don't cover: an LLM sitting inside a product with live
data, a safety-relevant deterministic core, and a review pass, built with Kyo instead of
cats-effect. AI-103/AI-500 candidates and open-water swimmers are real secondary audiences (the
back cover can say so) but the chapter order below is written for the primary one — an AI-103
candidate reading straight through gets the exam mapping as a sidebar per chapter (§5.2), not the
spine.

### 5.2 Chapter outline, mapped to real files

| # | Chapter | Grounded in |
|---|---|---|
| 1 | The one decision — why constrain the model instead of trusting it | `PHILOSOPHY.md` |
| 2 | The pipeline: beaches, weather, and a ranked list before any LLM runs | `ARCHITECTURE.md` §3-4, `Recommender.scala`, `BeachFinder.scala`, `OpenMeteoClient.scala` |
| 3 | Pure scoring, effectful boundary — Kyo at the edge, not the core | `scoring/Swimability.scala`, `docs/EFFECTS-MAP.md`, `AGENTS.md`'s code-style section |
| 4 | Six pluggable integrations, one shape — trait, local default, Azure opt-in | `ARCHITECTURE.md` §5's table, `AppConfig.scala` |
| 5 | Turning a row of numbers into a sentence, twice — synthesis and review | `llm/LlmClient.scala`, `llm/CompiledPrompt.scala`, `llm/Reviewer.scala` (§5a) |
| 6 | Compiling the prompt instead of hand-tuning it — DSPy in, Scala out | `dspy/compile_recommendation_prompt.py`, MIP-0012 (the Scala-native successor) |
| 7 | Answering from a sourced corpus — RAG over the sea-lore knowledge base | `knowledge/`, `OllamaEmbedder`, `OceanQa`, `docs/benchmarks/2026-09-05.md` |
| 8 | Measuring the thing that measures itself — the benchmark harness | `cli/bench/OceanBenchmark.scala`, `scripts/benchmark_gate.py` |
| 9 | An experiment ledger for free — MLflow, traces, and the run history | MIP-0010, `RunLedger`, `Tracing` |
| 10 | Shipping it — Docker images, the static map, GitHub Pages | MIP-0008, MIP-0005, `Dockerfile`, `site/` |
| 11 | The loop that wrote this book's own code — MIPs, agents, and the cost of asking | `AGENTS.md`, `docs/DEV-FLOW.md`, the `mip` skill, the `Cost:` trailer |
| — | Conclusion — what's still open, honestly | `docs/FUTURE-WORK.md`, the AI-103/AI-500 gap tables |

Each chapter ends with a boxed "verify this yourself" sidebar naming the exact `just` recipe or
live check from the source doc, in the same honest-status vocabulary the repo already uses — a
chapter is not allowed to sound more certain than the code it describes.

### 5.3 Keeping listings true — extraction, not paste

Every code listing is `\lstinputlisting[linerange=...]{...}` (or `\inputminted`, if the Pygments
path is kept, §4.5) pointed at a real path inside a **pinned checkout of the marola repo** — never
retyped by hand. Mechanically: the book repo vendors marola as a `git submodule` pinned to a commit
SHA (printed on the book's copyright page, e.g. "listings verified against marola @ bd83a1f");
`just book-build` first runs `scripts/check_listings.py` (a new script, mirroring
`scripts/benchmark_gate.py`'s style) which greps every `\lstinputlisting`/`\inputminted` path
against the submodule checkout and fails the build if a path or a named line range no longer
exists — the same "give the mistake a wall to hit" idea `PHILOSOPHY.md` states for the app itself,
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
cadence, and a cost ledger that are the book's own — the shape Volpe's companion-code repos and
Milewski's book repo both already use (§4.2, §4.3), just with the public/private sides swapped from
Volpe's case since marola's "companion code" is already the public main repo.

### 5.5 CI

Copying §4.3's `milewski-ctfp-pdf` shape directly: a `nix-flake-check` workflow builds the PDF on
every push (fails the PR if `latexmk`/`check_listings.py` fails); a `release` workflow triggers on
`push: tags: v*`, matrix-builds a screen and a print variant, and attaches both to a draft GitHub
Release named after the tag. No self-hosted runner, no paid service — GitHub Actions' free minutes
cover a LaTeX build the same way they already cover `ci.yml` for the app (`AGENTS.md` cost rule is
about Azure resources, not CI minutes, but the same "free by default" instinct applies).

### 5.6 Licensing

Proposed as **options for a human decision, not settled here** (per this MIP's own instructions):
prose under **CC BY-NC-SA 4.0** (matches the *spirit* of Maguire's Algebra-Driven Design licence —
attribution required, no commercial reuse of the text, share-alike) if the book stays free/PDF-only,
or **all-rights-reserved** if it's sold (Leanpub's own default author terms; a paid book commonly
reserves rights while the platform handles distribution). **Code listings inside the book stay MIT**
regardless — they're excerpts of files already under this repo's `LICENSE` (confirmed: root
`LICENSE` is MIT, copyright M. Hoffmann, 2026), so the book cannot license them more restrictively
than the source already is.

### 5.7 Distribution — options, not a decision

- **Leanpub** (§4.1, §4.2's precedent): 80% royalty on sales ≥ $7.99, 80% minus $0.50 below that
  (confirmed, [Leanpub Help Center](https://help.leanpub.com/en/articles/5468013-what-is-leanpub-s-royalty-rate-are-there-any-restrictions-on-where-i-can-self-publish-my-book-and-what-price-i-can-charge),
  fetched 2026-09-05); supports iterative "publish early, publish often" — a real fit for a book
  about a POC that itself ships in small PRs.
- **Gumroad**: 10% + $0.50 per direct sale, no monthly fee, acts as merchant of record for tax
  since 2025-01-01 (confirmed via aggregator coverage, fetched 2026-09-05 — Gumroad's own fee page
  was not fetched directly, flagged in §11 OQ7); simpler checkout, no built-in book-specific
  tooling (versions, reader web app) the way Leanpub has.
- **Free PDF from the GitHub Release + an optional paid print-on-demand** (Lulu/similar, not
  researched here): matches this repo's existing "free by default, paid tier opt-in" pattern
  (`ARCHITECTURE.md` §5's local/Azure split) applied to distribution instead of infrastructure.

## 6. Scoring / safety impact

None. No product code, no scoring logic, no user-facing output changes.

## 7. Verification plan

- `scripts/check_listings.py` (new): every `\lstinputlisting`/`\inputminted` path+line-range in the
  `.tex` sources resolves inside the pinned submodule checkout; a `just quality`-equivalent gate in
  the book repo's own CI, not this repo's.
- Live check: `just book-build` produces a PDF with a non-zero, sane page count, no LaTeX
  `Overfull \hbox` past a threshold, no unresolved `\ref`/`\cite`.
- A human read-through of chapter 1 (the spike, §11 OQ1) before committing to the other ten — this
  MIP's own "Effort vs Gain" call is conditional on that spike actually reading well.
- **Done, for this MIP's own scope:** Draft accepted; **done, for the spike**: chapter 1 plus the
  Nix/CI toolchain build a real PDF in CI and a human confirms the chapter is worth continuing.
- **Effort estimate:** roughly 15-30 author-hours per chapter (draft, listing extraction, revision)
  across 11 chapters plus a conclusion ≈ 200-300 hours, plus ~40-60 hours of one-time tooling
  (flake, CI, `check_listings.py`, a cover, a first copyedit pass) — a rough order-of-magnitude
  estimate, not a schedule; most of these are human writing hours, not agent-session hours, which
  is exactly why the usual `Cost:` trailer doesn't map cleanly here (§11 OQ6).

## 8. Risks, limitations, and honest caveats

- **Code drift is the central risk** (see the metadata table). A submodule pin turns "drift" into a
  visible, reviewable diff instead of silent staleness, but only if `check_listings.py` actually
  runs in CI — an unenforced convention here would be worse than no automation at all.
- **This is a large, mostly-human writing effort competing with Phase 1.** `AGENTS.md`'s phase
  discipline exists to stop exactly this kind of "more interesting, ships later" distraction; this
  MIP's own Effort-vs-Gain verdict says defer, and that's a real constraint, not boilerplate.
- **The `Cost:` trailer convention doesn't fit prose well.** `just cost-split` prices Claude Code
  sessions against code-shaped PRs; a chapter written over several long human-editing sessions with
  small agent-assisted diffs will under- or over-count against that model — flagged, not solved,
  here (§11 OQ6).
- **A LaTeX/Nix toolchain is a new maintenance surface**, even reused from Milewski's proven flake —
  TeX Live closures are large and occasionally break on nixpkgs bumps; scoped to its own repo
  (§5.4) specifically so this risk never touches the app's CI.
- **Selling a book about an unfinished POC is a credibility risk if the honesty vocabulary slips.**
  The whole pitch is that this book is as verified as the MIPs it's based on — a chapter that
  overclaims Azure paths that are "written, not run" (most of them, per `ARCHITECTURE.md` §5) would
  undercut the book's own thesis.

## 9. Alternatives considered

- **Do nothing** — the docs already serve developers who read `docs/`; a book adds narrative and
  reach, not new information. Real, but the literature-gap argument (§2, `FUTURE-WORK.md` §10)
  says the *form* is worth the effort on its own.
- **mdBook / Docusaurus (Markdown-native, like *The Rust Programming Language*)** — lower authoring
  friction, free hosting as a website, no print-quality PDF story, no precedent in this specific
  genre (self-published FP books all skew LaTeX or Pandoc-from-Markdown, §4) — rejected because the
  user's request specifically named LaTeX and this tradition.
- **Pandoc, Markdown → LaTeX** — see §4.5; kept as the fallback if `listings`/`minted` prove more
  friction than expected during the chapter-1 spike (§11 OQ1).
- **A `book/` directory in this repo instead of a separate repo** — see §5.4's trade-off paragraph.
- **Blog-post series instead of a book** — lower effort, no PDF/CI story, doesn't test the
  "extraction not paste" listings discipline this MIP considers the actual point; also loses the
  Leanpub-style "publish early, publish often" cadence a real book format buys.

## 10. Exam-coverage mapping

None directly — this is a meta-project about marola, not a new integration or a new AI-103/AI-500
domain. Indirectly, each chapter's "verify this yourself" sidebar (§5.2) doubles as exam-prep
narrative for the `AI-103-MAPPING.md`/`AI-500-MAPPING.md` rows the chapter's code already closes,
and the book operationalizes `docs/SKILLS.md`'s stated goal of practicing skills "in order, using
this repo as the vehicle" for a reader who isn't the repo's own author.

## 11. Open questions

1. **Spike first.** Write chapter 1 plus the Nix/CI toolchain (§5.5) as a single bounded task before
   committing to all eleven chapters — the Effort-vs-Gain verdict above is conditional on that spike
   actually being worth continuing.
2. **Title and audience — human decision.** The working title/audience in §5.1 is this MIP's
   proposal, not a settled choice; confirm before the spike's cover page is drawn.
3. **`listings` (a hand-rolled Scala language definition) vs `minted`/Pygments** — try the former
   first (§4.5) to avoid the shell-escape/Pygments dependency entirely; fall back to `minted` only
   if Scala syntax highlighting via `listings` proves too weak for 3-syntax (Scala/Python/bash)
   listings.
4. **Licensing — human decision**, §5.6's two options need an actual choice before any public
   release, tied to the distribution choice in §5.7.
5. **Gumroad's own fee page was not fetched directly** (§5.7) — aggregator coverage only; confirm
   against `gumroad.com`'s own pricing page before quoting a number in the book itself.
6. **How does `AGENTS.md`'s `Cost:` trailer apply to a mostly-human writing project?** Worth a small
   follow-up note in `AGENTS.md` once the spike produces real numbers, rather than guessing here.
7. **Print-on-demand vendor** (§5.7's third option) — not researched; a later open question if the
   free-PDF-plus-paid-print path is chosen.
8. **Whether to freeze a marola commit per book "edition"** (like a Leanpub version) or track `main`
   continuously via the submodule bump — affects how often `check_listings.py` needs re-running and
   how a reader's PDF page numbers stay stable across printings.

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
Gumroad fees, aggregator coverage only (not the primary source — see §11 OQ5):
[roo.beehiiv.com/p/gumroad-fees-2026](https://roo.beehiiv.com/p/gumroad-fees-2026).
Not fetched/verified: Leanpub's or Gumroad's terms of service in full; any print-on-demand vendor;
whether `nixpkgs`' current `texlive.combine` closure size has grown materially since
`milewski-ctfp-pdf`'s flake was last updated; a live `nix build` of that flake was not run in this
session (no such toolchain installed here) — the flake's contents were read, not executed.
