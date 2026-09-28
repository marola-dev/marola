# MIP-0040: The reviewer has never been validated — measure the judge, then make it a small jury

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Opus 5, for M. Hoffmann (deep review of every MIP + a live LLMOps/competitor research pass, 2026-09-07) |
| **Created** | 2026-09-07 |
| **Phase** | 0 — developer tooling plus one runtime one-liner; nothing a swimmer sees changes except that the printed reviewer score stops moving. No earlier-phase prerequisite is missing |
| **Related** | `core/llm/Reviewer.scala` (the judge this measures); `ARCHITECTURE.md` §5a (its status notes, including the reviewer "fixated on whale visibility instead of the more important jellyfish risk"); `FUTURE-WORK.md` §4.1 (**"Consider upgrading the metric itself to an LLM-as-judge"** — this MIP answers that proposal with evidence, and qualifies it) and §4.2 ("the layer now exists" ≠ "the layer is rigorously evaluated" — this MIP is the second half); MIP-0010 (Implemented — the ledger these runs are logged to); MIP-0032 (the model × strategy matrix — §5.7 there keeps LLM-as-judge explicitly out of scope; this MIP is what would have to land before it could come in); MIP-0039 (the deterministic fact guard — the *other* half of "is this sentence any good", and the reason this MIP does not need the judge to check facts); `ROADMAP.md` §5's still-unwritten multi-agent eval harness (**this MIP is not that harness** — §9) |
| **Effort** | M — a hand-labelled set of ~100 reviews (real human hours, the honest bulk of it), one pure agreement/kappa module in `cli/bench`, a jury runner beside `OceanBenchmark`, and one `temperature` field threaded through `LlmClient`. No new dependency, no new module |
| **Gain** | `infra/dev-loop` (a shipped component is either doing something or it is not, and today nobody can say which) |
| **Effort vs Gain** | `do next` for §5.1 (the temperature fix and the measurement harness — a day, and §2's spread is already measured); `do when X lands` for §5.3's jury, where X = the labelled set existing and kappa saying the single judge is not good enough |
| **Depends on** | Nothing blocking. MIP-0010 is Implemented and the runs log to it unchanged. No Phase 1 gate, no paid resource, no API key — every judge in §5.3 is a local Ollama model. Coordinates with MIP-0032 (which would gain a judge column only *after* this) and with MIP-0039 (complementary, neither blocks the other) |
| **Blocked by** | none |
| **Risk** | The labelling never happens. §5.2 needs a human to sit down and judge ~100 summaries; without it this MIP degrades to a one-line temperature fix and a harness with nothing to correlate against. That is the realistic failure mode, and §8 says what to do about it rather than pretending otherwise |
| **Cost so far** | — |

## 1. Summary

marola's second LLM pass grades the first one and prints `Reviewer (score 72/100, verdict:
approve)` to the user. Nobody has ever checked whether that number means anything. Measured on this
machine on 2026-09-07, **the same judge given the same draft twelve times returned scores from 25 to
80**, because marola sends no `temperature`, so the reviewer runs at Ollama's default sampling.
This MIP does three things, in order: pin the judge's decoding so its score is *reproducible* (one
line); build a small hand-labelled set and report **Cohen's kappa**, not exact-match agreement, so
the score is *validated*; and, if the single judge fails that bar, replace it with a **jury of two
or three small local models from different families**, which the literature says beats one judge and
which marola can run for free.

## 2. Motivation

### 2.1 The score moves 55 points on an unchanged input

Probed on this machine, **2026-09-07**, against `llama3.2` (the family `LocalLlmClient.DefaultModel`
uses), with `Reviewer`'s own contract, the eight facts, a correct draft, one JSON object out, run
twelve times each:

| Configuration | Scores returned (12 runs) | Distinct | Valid JSON |
|---|---|---|---|
| **As marola sends it today** (no `temperature` in the body) | 25, 30, 30, 40, 40, 40, 40, 50, 60, 60, 70, 80 | **7** | 12/12 |
| Same, with `"temperature": 0` | 30 ×12 | **1** | 12/12 |

`grep -rn "tools\|temperature" core/…/llm local/…/llm` returns nothing:
`LocalLlmClient.complete` posts `model` + `messages` and no decoding options
(`LocalLlmClient.scala:18-28`), so the top row *is* the production configuration. The number marola
prints to a user is, on this evidence, dominated by sampling noise, and `Main.reviewAndPrint`
(`Main.scala:511-514`) prints it next to the reviewer's text as though it were a measurement.

The verdict, by contrast, was `revise` on **all 24 runs**, for a draft whose numbers all match the
facts and which correctly omits the `Low` jellyfish and whale mentions. That is a hint that the
verdict may be systematically pessimistic on plain prose, a hypothesis, not a finding, on one
draft. It is exactly what §5.2 exists to settle.

### 2.2 The 2026 literature says this is the normal failure, and names the right instrument

Four papers, all abstracts fetched 2026-09-07 (Appendix), converge on one methodological point and
one design point:

- **Exact-match agreement is not evidence.** "Reliability without Validity" (arXiv:2606.19544,
  Norman/Rivera/Hughes, 2026-06-17) is the largest study to date, 21 judges, 9 providers, 3
  benchmarks, 118 runs, **~541,000 judgments**, and opens: *"judge validation in practice relies on
  exact-match agreement, a metric that does not correct for chance and systematically overstates
  discriminative ability."* Kappa deflation is **33–41 percentage points** on MT-Bench; judge
  rankings shift **up to 14 positions** across benchmarks; and **position bias above 0.10 coexists
  with test–retest reliability above 0.95** in two production-deployed judges. A judge that always
  says the same thing is not thereby a correct one.
- **The bias to worry about is style, not verbosity or position.** "Judging the Judges"
  (arXiv:2604.23178, Soumik, 2026-04-25) measures **style bias at 0.10–0.76, markdown favoured over
  plain prose**, against position bias **≤0.04**. marola's reviewer judges *plain-prose beach
  summaries*. This is the axis it is most likely to be scoring instead of grounding, and §2.1's
  24-out-of-24 `revise` on a plain-prose draft is at least consistent with it.
- **A jury of small models beats one big judge.** PoLL (arXiv:2404.18796, Verga et al., 2024-04-29):
  *"using a PoLL composed of a larger number of smaller models outperforms a single large judge,
  exhibits less intra-model bias due to its composition of disjoint model families, and does so
  while being over seven times less expensive."* Cost is irrelevant locally; **disjoint families** is
  the part marola should copy.
- **But do not make them argue.** SLMJury (arXiv:2606.07810, Laddha/Pradhan/Srivastava, 2026-06-05)
  benchmarks **16 small judges, 0.6B–14B, four families, ten benchmarks** and finds *"multi-agent
  debate degrades accuracy across all tested configurations"* under Reflect-Critique-Refine. A
  reviewer-of-the-reviewer is the obvious next idea and the measured wrong one.

`FUTURE-WORK.md` §4.1 already proposes "upgrading the metric itself to an **LLM-as-judge**". This
MIP does not reject that: it says the repo already *has* one, running in production, unmeasured,
and that adding a second before measuring the first is how a project ends up with two components it
cannot reason about. §4.2 of the same file said it first: "the layer now exists" is not "the layer
is rigorously evaluated", and "only the first one is cleared today".

## 3. User-visible change

For the swimmer, one thing changes and it is the point: the printed score stops moving between
identical runs.

```
  before (three runs, same beach, same hour, same draft):
    Reviewer (score 40/100, verdict: revise): ...
    Reviewer (score 70/100, verdict: revise): ...
    Reviewer (score 25/100, verdict: revise): ...

  after:
    Reviewer (score 30/100, verdict: revise): ...      ← every time, on the same input
```

For the developer:

```
$ just judge-label                     # opens the next unlabelled draft, records a human verdict
marola :: judge labelling — 37 of 100 done  ·  data/judge_labels.jsonl

$ just judge-validate
marola :: judge validation — 100 labelled reviews, judge=llama3.2 @ temp 0
  exact-match agreement   0.71        ← the number not to report on its own
  Cohen's kappa           0.18        ← the number that matters
  verdict distribution    judge: revise 88 / approve 12   ·  human: revise 34 / approve 66
  style split (kappa)     plain prose 0.09   ·  markdown 0.41
  → single judge: weak agreement. Jury recommended (MIP-0040 §5.3).
mlflow: experiment "marola/judge-validation", run 9a1c… — metrics kappa=0.18 agreement=0.71 …
```

Nothing about the ranked list, the scores, the water verdict or the map changes.

## 4. Data sources and dependencies reviewed

**No new external data source.** The judged material is marola's own drafts; the labels are the
maintainer's. Everything below is about which *technique* to adopt.

### 4.1 The instrument: Cohen's kappa, not agreement — and simple aggregation for the jury

- Kappa is ~15 lines of arithmetic over a confusion matrix; no library, no dependency. The reason to
  use it is arXiv:2606.19544's finding above: raw agreement overstates by 33–41 pp.
- For combining several judges, "A Finite-Calibration Regime Map for LLM Judge Panels"
  (arXiv:2606.01034, Zhu/Xie/Rao, submitted 2026-05-31) reports that **scalar/reliability
  aggregation beat unrestricted joint-table calibration in 16 of 20 dataset–budget cells**. With
  ~100 labels, marola's regime exactly, plain averaging or reliability-weighting is the evidenced
  choice, and a learned calibrator is not. *Cited from a research pass's fetch of the abstract, not
  re-fetched by this author* (§Appendix "Not checked").

### 4.2 Judges available locally, free, today

Read from the running Ollama on 2026-09-07: `llama3.2`, `llama3.2:1b`, `marola-llama3.2`,
`dolphin-mixtral:8x7b`, `smollm2:360m`, `marola-sea-tiny`, `nomic-embed-text`.

**PoLL's benefit comes from disjoint model families, and marola's local zoo is Llama-heavy.**
`llama3.2`, `llama3.2:1b`, `marola-llama3.2` and `marola-sea-tiny` are all Llama-lineage or derived
from marola's own prompts: a "panel" of those is one judge with three hats, and would import
exactly the intra-model bias PoLL says a panel removes. The genuinely disjoint local options are
`dolphin-mixtral:8x7b` (Mistral lineage, ~26 GB, already used to compile today's prompt artifacts)
and `smollm2:360m` (too small to judge, on SLMJury's own size curve). A third family means pulling
one: `qwen3:8b` is 5.2 GB per MIP-0032 §4.5's fetched figure, and SafetyJudge-LLM reports it as the
one open judge with complete schema compliance under Ollama. **That pull is the jury's real cost**,
and it is why §5.3 is `do when X lands` rather than `do next`.

### 4.3 A search-only claim this MIP checked and could not reproduce

A research pass surfaced SafetyJudge-LLM (doi 10.3390/ai7080286) reporting **`llama3.2:3b` strict
JSON-schema compliance failing 52.06% of the time under Ollama**. On the probe in §2.1, 24 runs,
two temperatures, `Reviewer`'s real output shape, the raw body was valid JSON **24 out of 24**,
before `extractJsonObject`'s salvage was even needed. Either the paper's schema is stricter than
marola's three-field object, or the conditions differ. **Recorded as not reproduced here**, and
`Reviewer.extractJsonObject`'s salvage is *not* proposed for removal on the strength of 24 runs.

### 4.4 Harnesses reviewed and not adopted

- **promptfoo** (24,877 stars, MIT, actively pushed 2026-09-07) runs fully locally against Ollama as
  both provider *and* grader, and ships a GitHub Action with request caching. A real option, and the
  wrong one here: marola's arms are Scala (`OceanQa`, `Reviewer`), not prompt files, the same
  reason MIP-0032 §9 rejected it, and this MIP's whole output is ~15 lines of arithmetic.
- **Ragas**, the vocabulary is worth borrowing, the library is not: no commit on `main` since
  **2026-02-24**, last release 0.4.3. *Repo-vitality figures in this subsection come from a research
  pass's GitHub-API fetches, not this author's* (§Appendix "Not checked").

**Pick:** marola's own arithmetic in `cli/bench`, over marola's own labels, logging to MIP-0010's
ledger. No dependency, no service, no key.

## 5. Design

### 5.1 Pin the judge's decoding — one line, `do next`, independent of everything else

`LlmClient.complete` gains an optional `temperature: Option[Double] = None`, threaded into the
request body by `LocalLlmClient` and `TracedLlmClient` alike.
`Reviewer.review` passes `Some(0.0)`. The *summariser* keeps its current sampling: a summary is
prose and some variety is fine; a **grade must be a function of its input**.

This alone converts §2.1's 25–80 spread into a single value, and it is the precondition for every
measurement below: you cannot validate a judge whose output is a distribution.

### 5.2 The labelled set and the arithmetic — the actual deliverable

- **`data/judge_labels.jsonl`** (gitignored; the *aggregate* is what gets committed): one row per
  drafted summary: `{facts, draft, human_verdict: approve|revise, human_note, style: prose|markdown}`.
  Drafts come from the DSPy demos, from real `just run -- --summarize` output, and from
  deliberately-flawed variants (a contradicted jellyfish level, an invented compass bearing, the
  MIP-0039 §2 cases) so the set contains real positives.
- **`just judge-label`**: a tiny CLI loop that shows facts + draft and records one keypress.
  ~100 rows is the target; arXiv:2606.19544's protocol is what it is modelled on, not a novel design.
- **`cli/bench/JudgeAgreement.scala`**, pure:

```scala
final case class Labelled(facts: Map[String, String], draft: String,
                          human: Verdict, style: Style)
enum Verdict derives CanEqual { case Approve, Revise }
enum Style   derives CanEqual { case Prose, Markdown }

final case class Agreement(n: Int, exactMatch: Double, kappa: Double,
                           judgeDist: Map[Verdict, Int], humanDist: Map[Verdict, Int],
                           kappaByStyle: Map[Style, Double], scoreSpread: Option[(Int, Int)])

object JudgeAgreement:
  def cohensKappa(pairs: List[(Verdict, Verdict)]): Double   // (po - pe) / (1 - pe)
  def summarise(labels: List[Labelled], judged: List[Verdict]): Agreement
```

- **`just judge-validate`** runs the current `Reviewer` over every labelled row and prints §3's
  block; the run is logged to MIP-0010's ledger as experiment `marola/judge-validation` (params
  `judge_model`, `temperature`, `n_labels`, `git_sha`; metrics `kappa`, `exact_match`,
  `kappa_prose`, `kappa_markdown`). **Both agreement and kappa are always printed together**, with
  kappa first: the report format is itself the argument.
- **The style split is not optional.** Half the labelled drafts are plain prose and half the same
  content in markdown bullets, so `kappa_prose` vs `kappa_markdown` measures arXiv:2604.23178's
  dominant bias directly on marola's own judge rather than importing the paper's number.

### 5.3 The jury — only if §5.2 says the single judge fails

Trigger: kappa below a threshold the maintainer sets before seeing the number (proposal: 0.40,
"moderate agreement"), or a style gap wide enough to embarrass the score.

```scala
final class JuryReviewer(judges: List[(String, LlmClient)]) extends /* the Reviewer entry point */
  // Each judge reviews independently, at temperature 0, never seeing another's output.
  // Verdict  = majority; a tie is `revise` (the conservative direction for a safety product).
  // Score    = mean, with the min–max range carried alongside and printed when it is wide.
```

Two rules, both taken from §2.2's evidence rather than from taste:

- **Disjoint families or it is not a jury** (§4.2). The default panel is `llama3.2` +
  `dolphin-mixtral:8x7b`, with `qwen3:8b` as the third once pulled; three Llama variants is not a
  panel.
- **No debate, no critique round, no reviewer-of-reviewers.** SLMJury measured that it degrades
  accuracy *across all tested configurations* at this model size. Judges vote; they do not confer.
  Aggregation stays scalar (§4.1).

`MAROLA_REVIEW_JURY=llama3.2,dolphin-mixtral:8x7b` selects it; unset keeps today's single reviewer.
The jury is **off by default** until §5.2's numbers justify the latency (three sequential local
calls on the `--summarize` path).

### 5.4 What is deterministic and what goes through a model

Kappa, agreement, the style split, majority voting and the mean are pure Scala. The judges are
models, as today. **No scoring, ranking, water-quality or safety-text path is touched**, and
MIP-0039's fact guard, if built, sits downstream of whatever this produces, checking facts
deterministically regardless of what any judge thought of the prose.

## 6. Scoring / safety impact

None to `Swimability.score`, the notes, the water veto or MIP-0022's safety footer. `scoring/` is not
touched.

The one behavioural change reaching a user is that the printed reviewer score becomes reproducible
(§5.1). That is a strict improvement in honesty: today the same conditions can print 25 or 80, which
invites a reader to treat a sampling artefact as a judgement about their swim.

If §5.3's jury ships, a `revise` majority means the reviewer's corrected text is shown instead of
the draft, the same mechanism as today, decided by more than one model. A tie resolves to `revise`,
the conservative direction.

## 7. Verification plan

- `JudgeAgreementSpec` (pure): kappa on hand-computed confusion matrices, including the two
  degenerate cases that matter: **perfect agreement → 1.0**, and **a judge that always says
  `revise` against a human who says `revise` 88% of the time → kappa ≈ 0 while exact match ≈ 0.88**.
  That single test is the whole argument for reporting kappa, encoded.
- `JudgeAgreementSpec` (style): `kappaByStyle` splits correctly and errors on an empty stratum
  rather than reporting 0.
- `ReviewerSpec` (extend): `temperature = Some(0.0)` reaches the request body for all three clients
  (scripted `Http.Transport`, asserting exact JSON); the summariser's body is unchanged.
- `JuryReviewerSpec`: majority verdict from three scripted judges; a 1-1 tie resolves to `revise`;
  the score is the mean and the range is carried; one judge failing does not fail the review.
- **Live, and this is the deliverable**: `just judge-label` to ~100 rows, then `just judge-validate`
  twice, the second run must print **identical** numbers (proving §5.1 worked), with the run
  visible in the local MLflow UI.
- **Done** = a committed `docs/benchmarks/judge-validation-YYYY-MM-DD.md` holding n, exact-match,
  kappa, both style kappas and the verdict distributions; `ARCHITECTURE.md` §5a's reviewer paragraph
  citing that kappa instead of describing the reviewer only in prose; and `FUTURE-WORK.md` §4.1/§4.2
  updated with the answer.

## 8. Risks, limitations, and honest caveats

- **The labelling is the risk, and it is a human one.** ~100 judgements is a few hours nobody is
  obliged to spend. Mitigation: §5.1 ships alone and is worth having by itself; the labeller is
  resumable; and 50 rows is a usable floor, with the n printed next to every kappa so a small sample
  is never mistaken for a strong one.
- **One labeller is not inter-rater reliability.** The maintainer's labels are the ground truth by
  fiat. That is defensible for a personal project whose domain expert is its author, and it is not
  the same thing as a validated rubric: say so in the benchmark file, not just here.
- **Twelve runs on one draft is not a variance measurement.** §2.1 establishes that the score moves
  a lot on one input at production settings; it does not establish a distribution over inputs. The
  temperature fix is justified by the mechanism (sampling), not by the sample size.
- **kappa near zero may mean the judge is useless, or that the rubric is.** If the human and the
  judge are answering different questions, the fix is a sharper rubric before a new judge. §5.2's
  `human_note` field exists to make that visible.
- **A jury triples the `--summarize` latency** on a laptop and adds a multi-GB pull. That is why it
  is gated on a number rather than adopted upfront.
- **This measures the reviewer, not the summariser.** A validated judge tells you whether the grade
  is trustworthy; it does not by itself make the summaries better. The path from one to the other is
  §11's follow-up.
- **The style-bias hypothesis is imported, not yet local.** 0.10–0.76 is arXiv:2604.23178's range on
  their judges and benchmarks. §5.2 measures marola's own; until it runs, "the reviewer probably
  scores style" is a well-supported suspicion, not a marola finding.

## 9. Alternatives considered

- **Do nothing.** The reviewer keeps printing a number that moved 55 points on an unchanged input
  today, and `FUTURE-WORK.md` §4.1's proposal to add *another* LLM-as-judge stays open with no way
  to tell whether the first one works. Lost.
- **Set temperature 0 and stop there.** Cheap, real, and it buys *reliability without validity*:
  precisely the paper's title. A reproducible 30 that correlates with nothing is a stable illusion.
  Adopted as §5.1 and explicitly not as the whole MIP.
- **Delete the reviewer.** Defensible if kappa comes back at zero, and premature before it is
  measured. This MIP is how that decision gets made on evidence; §5.2's report is what would justify
  it.
- **Use a frontier model as the judge** (Claude/GPT via MIP-0032's paid arm). It would likely agree
  with a human more often, and it breaks the keyless-local-first default for a developer tool, costs
  per run, and per arXiv:2604.23178 a mid-tier judge with the right debiasing beat the frontier setup
  anyway (71.0% agreement, kappa 0.549, ~$0.001/eval vs 69.5% at ~$0.015). Kept as a *reference*
  measurement worth one gated run under MIP-0032's paid gate, not as the shipped judge.
- **Reviewer-of-the-reviewer / debate.** The intuitive scaling move, measured to *degrade* accuracy
  across all tested configurations at this model size (arXiv:2606.07810). Rejected on evidence.
- **Adopt promptfoo or DeepEval.** Both run locally against Ollama; both bring a toolchain for what
  is fifteen lines of arithmetic over data marola already produces (§4.4).
- **Fold this into `ROADMAP.md` §5's proposed multi-agent eval harness.** That item is about scoring
  *per-agent contribution* across summariser/reviewer/escalation with shared-run-id traces, and it
  presumes a trustworthy scorer. This MIP produces the trustworthy scorer. Keeping them separate
  means that harness, whenever it is written, inherits a validated instrument instead of assuming
  one, and this MIP deliberately does **not** claim that item's number or scope.

## 11. Open questions

1. **The kappa threshold must be set before the number is seen.** Proposal: 0.40. Choosing it
   afterwards is how a measurement becomes a rationalisation; whoever runs §5.2 writes the threshold
   into the PR description first.
2. **What is the reviewer actually for?** Two readings live in the repo: a *gate* (don't show bad
   summaries) and a *rewriter* (`final_summary` replaces the draft). Today it is silently the second,
   since `Main.reviewAndPrint` prints `final_summary` whatever the verdict. The rubric in §5.2 cannot
   be written until that is decided, and it is a human call.
3. **Should the printed score survive at all?** If kappa is weak, the honest interim move may be to
   show the *verdict* and drop the number, rather than to keep a figure nobody can defend.
4. **Third jury family.** `qwen3:8b` (5.2 GB) is the obvious pull; whether the maintainer wants a
   third multi-GB model on the laptop for a developer-only feature is a real cost question.
5. **Follow-up MIP: GEPA-style reflective prompt optimisation, which this MIP unblocks.** DSPy's
   current release is 3.3.1 and its documented optimiser set is now 14 classes including **GEPA**,
   **SIMBA** and **BetterTogether**, none of which existed in the `BootstrapFewShot` generation
   `dspy/compile_recommendation_prompt.py` uses. GEPA (arXiv:2507.19457, Agrawal et al., v1
   2025-07-25 / v2 2026-02-14, abstract fetched 2026-09-07) claims it *"outperforms GRPO by 6% on
   average and by up to 20%, while using up to 35x fewer rollouts"* and *"outperforms the leading
   prompt optimizer, MIPROv2, by over 10% (e.g., +12% accuracy on AIME-2025)"*, and its whole
   mechanism is that the metric returns **natural-language feedback**, not a scalar
   (`dspy.Prediction(score=…, feedback=…)`). marola is unusually well placed to supply that
   feedback: deterministic scoring plus a reviewer that already emits a critique. But a
   feedback-carrying metric built on an unvalidated judge optimises toward that judge's biases, so
   this MIP comes first. Two things need checking before that MIP is written: whether GEPA works
   with a *small local* reflection model (DSPy's advanced docs give no guidance and their example
   uses a frontier model, so the local-first default is genuinely at risk), and how it relates to
   MIP-0012 §5.1's plan to replace `dspy/` with a Scala `BootstrapFewShot` compiler, which would
   have to grow a GEPA-shaped loop or stay in Python. Needs the next free MIP number.

## Appendix

### Checked live

All fetched or executed by the author on **2026-09-07**.

- **Local Ollama probe, `Reviewer`'s contract, `llama3.2`, 12 runs, no `temperature`** (the
  production configuration) → valid JSON 12/12; scores **25, 30, 30, 40, 40, 40, 40, 50, 60, 60, 70,
  80** (7 distinct, range 25–80); verdict `revise` 12/12.
- **Same probe with `"options": {"temperature": 0}`, 12 runs** → valid JSON 12/12; score **30** on
  all twelve (1 distinct); verdict `revise` 12/12.
- **`ollama /api/tags`** → `marola-sea-tiny`, `smollm2:360m`, `nomic-embed-text`,
  `marola-llama3.2`, `llama3.2`, `llama3.2:1b`, `dolphin-mixtral:8x7b`.
- **Repo grep, not assumed**: `grep -rn "tools" core/…/llm local/…/llm` → no match
  (exit 1); `local/…/LocalLlmClient.scala:18-28` posts `model` + `messages` only: no `temperature`,
  no `tools`, no `response_format`. This is what makes §2.1's first row the real configuration.
- `https://arxiv.org/abs/2606.19544` → "Reliability without Validity: A Systematic, Large-Scale
  Evaluation of LLM-as-a-Judge Models Across Agreement, Consistency, and Bias", Justin D. Norman,
  Michael U. Rivera, D. Alex Hughes, submitted **2026-06-17**. 21 judges / 9 providers / 3 benchmarks
  (MT-Bench, JudgeBench, RewardBench) / 118 runs / **~541,000 judgments**. Abstract: *"judge
  validation in practice relies on exact-match agreement, a metric that does not correct for chance
  and systematically overstates discriminative ability."* Kappa deflation **33–41 pp** on MT-Bench;
  ranking shifts **up to 14 positions**; **position bias >0.10** alongside test–retest **>0.95**;
  **verbosity bias <0.011**. Proposes a "Minimum Viable Validation Protocol".
- `https://arxiv.org/abs/2604.23178` → "Judging the Judges: A Systematic Evaluation of Bias
  Mitigation Strategies in LLM-as-a-Judge Pipelines", Sadman Kabir Soumik, v1 **2026-04-25**, v2
  2026-06-24. Nine debiasing strategies × five judges × four provider families × three benchmarks
  (MT-Bench n=400, LLMBar n=200, custom n=375). **Style bias 0.10–0.76** (markdown over plain prose)
  vs **position bias ≤0.04**. Best config Gemini 2.5 Flash + Combined Budget: **71.0% agreement,
  Cohen's kappa 0.549, ~$0.001/eval**, beating Claude Sonnet 4 at 69.5% / ~$0.015 (~15× cheaper).
- `https://arxiv.org/abs/2404.18796` → "Replacing Judges with Juries: Evaluating LLM Generations
  with a Panel of Diverse Models", Verga, Hofstatter, Althammer, Su, Piktus, Arkhangorodsky, Xu,
  White, Lewis, submitted **2024-04-29**. *"using a PoLL composed of a larger number of smaller
  models outperforms a single large judge, exhibits less intra-model bias due to its composition of
  disjoint model families, and does so while being over seven times less expensive."* The abstract
  does not name the panel's members.
- `https://arxiv.org/abs/2606.07810` → "SLMJury: Can Small Language Models Judge as Well as Large
  Ones?", Anish Laddha, Nitesh Pradhan, Gaurav Srivastava, submitted **2026-06-05**. **16 SLM judges,
  0.6B–14B, four families, ten benchmarks**, N=64,824 judgments per configuration on the closed-ended
  half. *"Under the Reflect-Critique-Refine (RCR) debate protocol, multi-agent debate degrades
  accuracy across all tested configurations, whereas the top judges resist six adversarial personas
  with <=0.55% variance."*
- `https://arxiv.org/abs/2507.19457` (for §11.5) → "GEPA: Reflective Prompt Evolution Can Outperform
  Reinforcement Learning", Agrawal et al., v1 **2025-07-25**, v2 **2026-02-14**. *"GEPA outperforms
  GRPO by 6% on average and by up to 20%, while using up to 35x fewer rollouts. GEPA also outperforms
  the leading prompt optimizer, MIPROv2, by over 10% (e.g., +12% accuracy on AIME-2025)."*
- `https://dspy.ai/api/optimizers/GEPA/overview/` (for §11.5) → GEPA's metric returns either a float
  or a `dspy.Prediction` carrying `score`, `feedback` and optional `objective_scores`; *"If no
  feedback is returned, GEPA will use a simple text feedback consisting of just the score."*
  `reflection_lm` is mandatory, the documented example is `dspy.LM(model='gpt-5', temperature=1.0,
  max_tokens=32000)`, and exactly one of `auto` / `max_full_evals` / `max_metric_calls` sets the
  budget. **No guidance on small local reflection models appears on the page**: the basis for
  §11.5's stated risk.

### Not checked

- **The 24 probe runs use one draft, one beach and one fact set.** They show the score is unstable
  at production settings; they are not a variance study, and §8 says so.
- **The `revise` 24/24 result is not evidence of verdict bias** on its own: one draft, one rubric,
  one model. It is the hypothesis §5.2 tests.
- **SafetyJudge-LLM (doi 10.3390/ai7080286)**: its `llama3.2:3b` 52.06% schema-failure figure came
  to this author from a research pass's search-result extract, not a fetch of the paper, and **did
  not reproduce** on the probe above (§4.3). Neither the figure nor its non-reproduction should be
  treated as settled.
- **arXiv:2606.01034** (finite-calibration regime map, §4.1): abstract read by a research pass, not
  fetched by this author. The "16 of 20 cells" figure is repeated here on that basis.
- **promptfoo / Ragas / DeepEval repo-vitality figures** (§4.4): GitHub-API and PyPI results from a
  research pass, not re-fetched here. No design decision depends on the exact numbers, only on
  "Ragas is quiet, promptfoo is active".
- **`qwen3:8b`'s judging quality**: not pulled, not run. Its 5.2 GB size is MIP-0032 §4.5's fetched
  figure; its "complete schema compliance" is a search-only claim from the same SafetyJudge-LLM
  extract above and is explicitly not relied on.
- **Whether `dolphin-mixtral:8x7b` is a usable judge on this hardware**: present locally and used
  for prompt compilation, but never run as a judge, and a ~26 GB model on a laptop has a latency cost
  §8 flags but does not measure.
