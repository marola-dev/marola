# MIP-0045: Classic NLP and parsing where an LLM call is currently paying for it — a survey of marola's product pipeline and its own dev loop

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus) |
| **Created** | 2026-09-07 |
| **Phase** | 0/1 (`ARCHITECTURE.md` §11) — no Azure resource, no paid API, nothing blocked on the Telegram bot |
| **Related** | `FUTURE-WORK.md` §4.1 (an evaluation harness), §9.1 (RAG grounding), `AI-103-MAPPING.md` §5 rows "Text analysis / entity extraction" (**Partial gap**) and "Azure AI Language service" (**Gap**), MIP-0022 (the safety footer, whose trigger runs through retrieval), MIP-0032 (the model × strategy benchmark matrix — a lexical retriever is one more strategy row), branch `feat/pr-labels-nlp-classifier` |
| **Effort** | M — one new `KnowledgeStore` implementation in `core` (pure Scala, no new dependency), one benchmark arm, one small change to `scripts/arxiv_digest.py`. No new module, no store, no CI workflow. Re-rated up from S after §4.1's probe showed the retrieval change needs a real benchmark re-run and a `SafetyFooter` interaction fixed (§6), not just a swapped class |
| **Gain** | `cost/ops` — removes one Ollama embed call per question asked and a ~3 MB regenerated index; `infra/dev-loop` — a classification/ranking job in the dev loop that a local script does instead of a Claude Code session's tokens; `exam coverage (AI-103 §5 "Text analysis / entity extraction")` — closes a row that is honestly marked a partial gap today |
| **Effort vs Gain** | `cheap win` for §5.1 (lexical retrieval) and §5.2 (merge the already-built PR-label classifier); `do next` for §5.4 (arXiv relevance); `park` for §5.3 (a deterministic query front door) until MIP-0002's bot gives it real free-text input to parse |
| **Depends on** | Nothing blocks this. Not gated by `AGENTS.md`'s Phase 1 (Telegram) gate — every call-site surveyed already exists and is exercised by `just run --ask`, `just benchmark` and `just mcp-server`. Not gated by the cost-and-deployment-safety gate either: no Azure resource, no paid API, no new network dependency. Non-blocking coordination: §5.2 is the branch `feat/pr-labels-nlp-classifier` (already written and pushed, someone else's deliverable — this MIP references it, does not restate or modify it); §5.1 touches `core/src/main/scala/marola/knowledge/`, which MIP-0025 and MIP-0033 also read from |
| **Blocked by** | none |
| **Risk** | §5.1 trades a semantically-aware retriever for a lexically-aware one. A question phrased entirely in words the corpus does not use ("is a bluebottle dangerous?" against a corpus that says "Portuguese man o' war") scores near zero under TF-IDF where an embedder might still have found it. The corpus is six documents today, so this is survivable; at ten times the corpus size it may invert, and the honest exit is a hybrid, not a rollback |
| **Cost so far** | — |

## 1. Summary

Six places in marola call (or are one step from calling) a language model for a job that is
classification, ranking, matching, or extraction rather than generation. This MIP surveys them
against five classic (non-LLM) techniques, verifies each technique's real cost and its real
nixpkgs installability, and recommends converting four while explicitly recommending **against**
converting two, including the one the reader would most expect this MIP to attack:
`llm/Reviewer.scala`. The headline finding is `FileKnowledgeStore`: marola's own benchmark record
already documents that the embedder it uses carries *inverted* relevance signal, and a TF-IDF
probe over the same corpus and the same 22-question set separates in-corpus from off-corpus
questions where the embedder could not, at zero model calls.

## 2. Motivation

Two concrete, already-documented gaps.

**The retrieval one is quoted from this repo's own code.** `OceanQa.DefaultMinScore` is `0.0`, and
the comment above it says why:

> `just benchmark` on 2026-09-05 showed `llama3.2`'s own embeddings put *irrelevant* passages at
> 0.49-0.50 and relevant ones at 0.36-0.40 — the score carries almost no signal with that
> embedder, so a threshold either never fires or always does.

`docs/benchmarks/2026-09-05.md` says the same thing in its Run 1 verdict. So marola pays for an
embedding call on every question (`FileKnowledgeStore.rank` → `embedder.embed(List(query))`),
stores a 3072-double vector per chunk on disk, and then throws the resulting score away by
comparing it against a threshold of zero. Relevance is decided downstream by asking the model a
second time (`NoAnswerSentinel`). That is an LLM call buying nothing.

**The dev-loop one already has a landed proof.** Earlier in this same session the PR-label
`area/*` classification, a prose-classification job the deterministic taxonomy explicitly
declines to guess at, was given a local TF-IDF classifier (`scripts/pr_label_nlp.py`, branch
`feat/pr-labels-nlp-classifier`). It runs offline, needs no key, no network and no GPU, and
classifies correctly on 8 of 8 marola-domain fixtures. §4.1 treats that as this survey's first
data point rather than a proposal.

## 3. User-visible change

None for §5.2/§5.4 (dev-loop only). For §5.1 the user-visible change is that `--ask` stops
answering from unrelated passages, and the citation list stops naming documents that have nothing
to do with the question. Today, on an off-corpus question, all four retrieved passages pass the
`0.0` threshold and are shown as sources even when the model correctly abstains:

```
$ just run -- --ask "What is the Gulf Stream?"
(From the model's general knowledge — unsourced; verify before relying on it.) The Gulf Stream is …
Sources: Jellyfish and man o' war (https://…), Sea foam and water colour (https://…)
```

After, with a lexical retriever and a threshold that means something (§5.1):

```
$ just run -- --ask "What is the Gulf Stream?"
(From the model's general knowledge — unsourced; verify before relying on it.) The Gulf Stream is …
Sources: (none — nothing in the ocean notes covers this)
```

## 4. Data sources and dependencies reviewed

Every version below is from a live `nix eval` on 2026-09-07 (Appendix: Checked live). No claim
here is from memory.

### 4.1 TF-IDF + cosine similarity (scikit-learn) — the landed proof point

`scikit-learn` 1.8.0 in nixpkgs, bundled into the dev shell's interpreter via
`python3.withPackages` on `feat/pr-labels-nlp-classifier` (a bare `python3Packages.scikit-learn`
entry would sit in its own store path and never be importable by plain `python3`: that branch's
flake comment documents the gotcha). Verified this session, not repeated from that branch's own
`Tested:` trailer: `scripts/pr_label_nlp.py --self-test` was run against a freshly-substituted
scikit-learn and printed `pr_label_nlp self-test: ok`, 8/8 classification cases plus 2 taxonomy
checks. **This is the first landed evidence that a local, free, marola-vocabulary-aware
statistical classifier is good enough for a real classification job in this repo**, and the shape
it landed in, suggest-only, never overriding a confident deterministic call, applying only into
an `area/unscoped` gap, is the shape §5 reuses everywhere.

Independently, a throwaway probe (not shipped; it exists only to inform this MIP) chunked
`knowledge/**.md` exactly as `Corpus.chunkDocument` does (blank-line paragraphs merged to 700
chars → 32 chunks, 951-term vocabulary) and scored all 22 questions of
`cli/src/main/resources/benchmark_questions.json` by top-1 TF-IDF cosine:

| | n | min | mean | max |
|---|---|---|---|---|
| in-corpus questions | 10 | 0.1248 | **0.2700** | 0.3958 |
| off-corpus questions | 12 | 0.1441 | **0.1785** | 0.2195 |

The separation runs the *right* way, unlike the embedder's. The best single threshold (0.2186)
keeps 8/10 in-corpus and rejects 11/12 off-corpus: 19/22, 0.86. Honest caveat, stated here rather
than buried: that threshold was chosen on the same 22 questions it is scored against, so 0.86 is
an in-sample optimum, not a held-out result, and the distributions still overlap (q05, an
in-corpus question about PRÓPRIA, scores 0.1248, below every off-corpus question but one).

### 4.2 spaCy and rule-based NER

`spacy` 3.8.14 in nixpkgs. The question this MIP had to answer before naming it, does a spaCy
model need a network call at first use, which would violate this repo's "no undeclared network
dependency" ethos, is **no**: `python3Packages.spacy-models` is a real attribute set in nixpkgs
carrying `en_core_web_sm/md/lg/trf` and `pt_core_news_sm/md/lg` (`pt_core_news_sm` 3.8.0), each a
fixed-output derivation fetching
`github.com/explosion/spacy-models/releases/download/<model>-<ver>/<model>-<ver>.tar.gz` at *build*
time. `python -m spacy download` is never needed. The Portuguese models matter here: everything
marola parses from an agency (IMA/SC, INEA, INEMA) is Portuguese.

Verdict: installable and honest, but **no current call-site needs it** (§5.5). Recorded so the
next person does not re-derive it.

### 4.3 Bag-of-words / naive Bayes over this repo's own history

The precondition is labeled data. The PR-label backfill has only just started producing it and
there was no `gh` auth available in this sandbox to count how many PRs now carry an `area/*` label,
so the training-set size is unknown: see Open questions. A naive Bayes classifier over commit
subjects is a plausible future upgrade path *from* TF-IDF, not a replacement for it: TF-IDF needs
no labels at all, which is exactly why §4.1's classifier could be built the day the idea came up.

### 4.4 A local sentence-embedding model (sentence-transformers)

`sentence-transformers` 5.7.0 exists in nixpkgs. Its propagated build inputs are
`huggingface-hub, numpy, scikit-learn, scipy, tokenizers, torch, tqdm, transformers,
typing-extensions`: i.e. adopting it pulls PyTorch (`python3Packages.torch` 2.12.0) into the dev
shell, and `huggingface-hub` means the *weights* still arrive over the network at first use, which
is the exact thing §4.2 checked and cleared spaCy of.

**Honest verdict: over-engineering, and the benchmark already says so.** Run 3 of
`docs/benchmarks/2026-09-05.md` swapped in `nomic-embed-text`, a genuinely better embedder,
already free through Ollama, needing *no* new dependency at all, and it was not clearly better
(in-corpus 0.85 vs 0.92; general 0.67 vs 0.78, at n=22 where ±0.1 is noise). If a better embedder
that costs nothing does not help at this corpus size, a heavier one that costs a PyTorch closure
will not either. Revisit only if the corpus grows ~10×, which that benchmark's own closing note
predicts as the crossover.

### 4.5 Prior art already in this repo (not a dependency — an existing pattern to extend)

This survey is not introducing the idea of non-LLM parsing to marola; it is naming a pattern the
repo already relies on in its most correctness-sensitive paths, and asking where else it fits.

- `scripts/lib/pr_labels.sh`: a deterministic label taxonomy whose header states the rule
  outright: every label is "derived from a fact already on the PR … never guessed from prose, and
  never an LLM call".
- `scripts/lib/mip_ref.sh`: three-tier MIP-number detection (branch name → commit subject →
  the single `docs/mips/MIP-NNNN-*.md` file touched). Pure regex; no model has ever been asked
  "which MIP is this PR about".
- `scripts/cost-split.py`: cost attribution by branch and author date, arithmetic over session
  logs, no model call.
- `core/…/water/WaterQualityMatcher.scala`: NFD accent stripping, a `Praia do/da/de` prefix rule,
  word-prefix matching, and an inland-water prefix list, with haversine distance only as the
  fallback. This is name-normalisation NLP in ~74 lines, and it decides which sampling point's
  verdict attaches to which beach.
- `local/…/water/IneaPdfParser.scala` / `InemaPdfParser.scala`: PDF table extraction with
  rowspan reconstruction from real glyph Y-positions, verified against a captured fixture of a
  real bulletin. Nobody proposed handing those PDFs to a multimodal model.
- `core/…/knowledge/OceanQa.saysNoAnswer`: a rule-based classifier over *model output*, matching
  the sentinel plus five free-text ways a small model says the same thing.

**The pick.** TF-IDF/cosine (§4.1) for everything recommended in §5, implemented in whichever
language the call-site already lives in: Python where it is a script, plain Scala where it is
`core` (a TF-IDF scorer is ~60 lines of `Map[String, Double]` arithmetic; adding a Python
dependency to `core` to avoid writing them would be the wrong trade, and `core` carries no such
dependency today). spaCy and sentence-transformers are recorded as verified-and-rejected.

## 5. Design

### 5.1 `LexicalKnowledgeStore` — recommended, the largest win

A second `KnowledgeStore` in `core/src/main/scala/marola/knowledge/`, no new dependency:

```scala
final class LexicalKnowledgeStore(corpusDir: String) extends KnowledgeStore:
  def search(query: String, k: Int = 4): List[Passage] < Sync
object Lexical:
  def tokenise(s: String): List[String]                       // NFD-fold, lowercase, split
  def idf(chunks: List[CorpusChunk]): Map[String, Double]     // pure, unit-testable
  def score(queryTerms: List[String], chunk: Vector[Double], idf: Map[String, Double]): Double
```

`AppConfig` picks it via `MAROLA_KNOWLEDGE_RETRIEVER` (`lexical` | `embedding`), same
env-var-per-integration shape as `MAROLA_LLM_PROVIDER` (`ARCHITECTURE.md` §5). Deterministic end
to end: no model call, no `./data/knowledge-index.json`, no fingerprint/re-embed cycle. The
embedding path stays and stays supported: this is a default change, not a deletion, and MIP-0032's
matrix gains a row rather than losing one.

The LLM keeps doing exactly what it does today after retrieval: answering from the passages,
citing them, and emitting `NO_ANSWER_IN_PASSAGES`. Nothing about the grounding contract changes.

### 5.2 PR-label classification — recommended; already built, on a branch

`feat/pr-labels-nlp-classifier` (`scripts/pr_label_nlp.py` + `--nlp` / `--nlp-apply-unscoped` on
`scripts/backfill-pr-labels.sh`). This MIP's recommendation is simply: **merge it as-is, in the
role it already has**: comparison by default, applying only into an `area/unscoped` gap, never
overriding the deterministic taxonomy. No change proposed. It is listed here because a survey that
omitted its own strongest evidence would be dishonest.

### 5.3 A deterministic front door for free-text questions — recommended, but park it

Verified before proposing: there is **no** free-text location parsing in marola today.
`Main`'s CLI takes typed flags, and `ChatServer.responseFor` hands the raw question straight to
`OceanQa.answer`. So this is not a conversion: it is a "build the cheap half first" note for
whoever implements MIP-0002's bot. A gazetteer matcher reusing `WaterQualityMatcher.normalise` and
`namesMatch` against the OSM beach list resolves the structured majority ("Joaquina amanhã",
"praia mole hoje") with zero model calls; anything unmatched falls through to the LLM unchanged.
Parked until there is real user text to measure against, because building it against imagined
phrasings is how you get a parser tuned to nobody.

### 5.4 `arxiv_digest.py` relevance — recommended, small

`relevance_score` is today the sum of the weights of the queries that matched: it cannot rank two
papers that matched the same query. Replacing it with TF-IDF cosine between the paper's abstract
and marola's own corpus vocabulary reuses §4.1's method and machinery exactly. Still no LLM, still
offline after the arXiv fetch.

### 5.5 Explicitly **not** converted

- **`llm/Reviewer.scala` stays an LLM call.** Its job is two-part: (a) does the draft mention
  jellyfish risk when Moderate/High and whale likelihood when Moderate/High, and (b) does it assert
  anything not present in the given facts. Part (a) is a keyword check and could short-circuit:
  worth doing as a pre-filter that skips the review call when every mention rule is already
  satisfied. Part (b) is a hallucination check, which requires deciding whether a sentence is
  *entailed* by a set of structured facts. Term overlap cannot do that: a draft that says "the sea
  will be warm" shares every content word with facts that say the sea will be 17 °C. This is the
  case where an LLM earns its cost, and the blast radius is already bounded: PHILOSOPHY.md's
  Pillar 2 keeps the reviewer able to rewrite prose and unable to overturn a veto.
- **The summarizer (`Main.summarizeTop`) stays an LLM call.** Turning `BestHour` into a sentence is
  generation. A template would work and would be worse; that is the whole reason the DSPy compile
  step exists.
- **Nothing about safety scoring changes.** `Swimability.score`, `WaterVerdict.veto`, and the notes
  they produce stay pure Scala, unit-tested, with no statistical component of any kind: not an
  LLM, and not a TF-IDF classifier either. "Use NLP more" is not a licence to make a swim/don't-swim
  decision probabilistic. `MIP-0022`'s safety footer is the one adjacent thing this MIP does touch,
  and only because retrieval feeds it, see §6.
- **spaCy / NER for the agency PDFs.** INEA/INEMA parsing is geometry and table structure, not
  language; §4.2 confirms spaCy is installable and this confirms it is the wrong tool.

## 6. Scoring / safety impact

`Swimability.score`: **none.** No threshold, deduction or veto in `scoring/` is touched.

One real safety-adjacent interaction, found while reading `OceanQa.answer` and worth fixing as part
of §5.1 rather than after it. Today:

```scala
relevant = passages.filter(_.score >= minScore)
…
yield Answer(reply, relevant, relevant.exists(_.safety))
```

The MIP-0022 footer trigger is computed from the *thresholded* list. With `DefaultMinScore = 0.0`
that filter is inert, so the bug is latent. §5.1's whole point is to make the threshold meaningful
, which would let a safety passage that scored below it drop out and silently take the emergency
footer (lifeguard / 193 / SAMU 192) with it. Fix: compute `safety` from the unfiltered top-k
before the threshold filter is applied, and add a unit test that a safety chunk scoring below
`minScore` still sets `Answer.safety = true`. Whichever way §5.1 is decided, this line should be
fixed.

## 7. Verification plan

- `LexicalSpec` (new, `core`): `tokenise` folds accents and lowercases; `idf` is higher for a term
  in one chunk than for one in all chunks; `score` ranks a chunk containing every query term above
  one containing none. Pure, no I/O.
- `LexicalKnowledgeStoreSpec` (new, `core`): over the existing `CorpusSpec` fixture, an in-corpus
  query returns its own document first; an off-corpus query's top score is below the proposed
  default threshold.
- `OceanQaSpec` (extend): a safety chunk scoring below `minScore` still yields
  `Answer.safety = true` (§6).
- Live: `just benchmark` with `MAROLA_KNOWLEDGE_RETRIEVER=lexical`, results appended to
  `docs/benchmarks/` as a fourth run and compared against 2026-09-05's three, per that file's own
  closing instruction to re-run before changing the embedder. **Done** = `rag-general` coverage no
  worse than run 2's 0.84 with the embed call removed, or a written explanation of the regression.
- `just build && just test && just quality`.

## 8. Risks, limitations, and honest caveats

- §4.1's 0.86 is in-sample on 22 questions. It is evidence that lexical scoring has the right sign,
  not a claim about accuracy on questions nobody has asked yet.
- Vocabulary mismatch is TF-IDF's real weakness and the corpus is bilingual-adjacent (Portuguese
  beach and bulletin terms inside English prose). A synonym list is the cheap mitigation; a hybrid
  (lexical filter, embedding re-rank) is the expensive one, and neither should be built until the
  benchmark shows it is needed.
- Removing the stored index removes a debugging artifact people may be using.
- Nothing here makes marola's answers *more* sourced. Retrieval quality changes which sourced
  passages are shown, not whether a claim needs a source.

## 9. Alternatives considered

- **Do nothing.** Defensible for §5.3/§5.4. Not for §5.1: the code itself documents that it is
  paying for a signal it then discards.
- **A better embedder instead** (`nomic-embed-text`, free via Ollama). Already tried: run 3 of
  the 2026-09-05 benchmark. Not clearly better, and still one model call per question.
- **sentence-transformers.** §4.4: rejected on a verified dependency footprint.
- **An LLM-as-judge relevance filter** (ask the model whether each passage is relevant). Strictly
  more expensive than the thing it would replace; it is the direction this MIP exists to argue
  against.
- **Delete the embedding path entirely.** Rejected: it would break `ARCHITECTURE.md` §5's
  local-default/opt-in-alternative shape and remove a MIP-0032 comparison arm.

## 10. Exam-coverage mapping

- **AI-103 §5, "Text analysis / entity extraction" — currently *Partial gap*.** §5.1 and §5.4 build
  real text-analysis (tokenisation, IDF weighting, cosine ranking) rather than the "narrow,
  single-purpose" `extractJsonObject` the mapping names as today's closest analog. That row now
  carries "proposed: MIP-0045" (this change); it stays a *Partial gap* until §5.1 actually merges.
- **AI-103 §5, "Azure AI Language service" — *Gap*.** Unchanged, and deliberately: this MIP's whole
  argument is that the local path is sufficient here. The row's own note ("classifying source
  documents before they go into a retrieval index") is precisely §5.1's job, done without the
  service.
- AI-500: none.

## 11. Open questions

- How many merged PRs now carry an `area/*` label? §4.3's naive-Bayes option needs that number and
  `gh` was not authenticated in this sandbox. A human running
  `gh pr list --state merged --limit 300 --json labels` settles it.
- Should the lexical retriever become the default, or ship behind the env var for one benchmark
  cycle first? A human call on how much the 22-question sample is trusted.
- What default `minScore` ships with §5.1? 0.2186 is this sample's optimum and therefore too tight
  to trust; something nearer 0.15 trades off toward recall. Needs the run-4 benchmark to decide.
- **Follow-up MIP:** `docs/benchmarks/` holds exactly one dated file and every claim in this MIP's
  §4.4 and §9 leans on it. A benchmark-history format that makes runs machine-comparable (and a
  `just benchmark --compare <file>` that fails on regression) is real, out of scope here, and wants
  the next free MIP number.

## Appendix

### Checked live

- `nix eval nixpkgs#python3Packages.scikit-learn.version`, 2026-09-07 → `1.8.0`.
- `nix eval nixpkgs#python3Packages.spacy.version`, 2026-09-07 → `3.8.14`.
- `nix eval nixpkgs#python3Packages.spacy-models` (attrNames), 2026-09-07 → includes
  `en_core_web_sm/md/lg/trf`, `pt_core_news_sm/md/lg`; `pt_core_news_sm.version` → `3.8.0`;
  `en_core_web_sm.src.url` →
  `https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0.tar.gz`
  (a build-time fixed-output fetch, no runtime `spacy download`).
- `nix eval nixpkgs#python3Packages.sentence-transformers.version`, 2026-09-07 → `5.7.0`;
  its `propagatedBuildInputs` → `huggingface-hub, numpy, scikit-learn, scipy, tokenizers, torch,
  tqdm, transformers, typing-extensions`. `nix path-info --derivation
  nixpkgs#python3Packages.torch` → `python3.14-torch-2.12.0.drv`.
- `nix eval nixpkgs#python3Packages.{numpy,nltk,rank-bm25,gensim}.version`, 2026-09-07 →
  `2.5.1`, `3.10.0`, `0.2.2`, `4.4.0` (recorded as available; none proposed).
- `scripts/pr_label_nlp.py --self-test` from `origin/feat/pr-labels-nlp-classifier`, run
  2026-09-07 under a `python3.withPackages [scikit-learn]` shell → `pr_label_nlp self-test: ok`,
  8/8 classification cases, 2/2 taxonomy checks.
- TF-IDF probe over `knowledge/**.md` (32 chunks, 951-term vocabulary) × all 22 questions of
  `cli/src/main/resources/benchmark_questions.json`, run 2026-09-07, numbers in §4.1. Throwaway
  script, scratchpad only, not committed.
- Read at `origin/main` 07882ae, 2026-09-07: `core/…/knowledge/{OceanQa,FileKnowledgeStore,
  KnowledgeStore,Corpus}.scala`, `core/…/llm/Reviewer.scala`, `core/…/water/WaterQualityMatcher.scala`,
  `core/…/scoring/Swimability.scala`, `cli/…/Main.scala` (`summarizeTop`/`reviewAndPrint`),
  `cli/…/AppConfig.scala`, `cli/…/agent/ChatServer.scala`, `cli/…/bench/OceanBenchmark.scala`,
  `local/…/water/IneaPdfParser.scala`, `scripts/{arxiv_digest.py,cost-split.py,lib/pr_labels.sh,
  lib/mip_ref.sh}`, `docs/benchmarks/2026-09-05.md`, `docs/ARCHITECTURE.md` §5a/§11,
  `docs/AI-103-MAPPING.md` §5, `docs/FUTURE-WORK.md` (no existing NLP/TF-IDF/BM25 section, grep
  over `docs/` and the root `*.md` returned nothing).

### Not checked

- Closure **sizes** for scikit-learn, spaCy or torch. `nix path-info -S --store
  https://cache.nixos.org` returned nothing usable for these paths, so §4.4's argument rests on the
  propagated-dependency list (verified) and not on a megabyte figure (not verified).
- Whether `huggingface-hub` can be pointed at a purely local model directory with no first-use
  network call. Assumed it would need one; not tested, because §4.4 is rejected on other grounds
  anyway.
- The number of merged PRs carrying an `area/*` label (§11): no `gh` auth in this sandbox.
- The `--nlp` / `--nlp-apply-unscoped` flags on `scripts/backfill-pr-labels.sh` against a real PR:
  same reason; that branch's own `Tested:` trailer says the same.
- Any BM25 variant's behaviour on this corpus. Only TF-IDF/cosine was probed; `rank-bm25`'s
  presence in nixpkgs was confirmed but its ranking was not measured.
