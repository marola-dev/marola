# MIP-0039: The fact guard — a deterministic check that the model's sentence only says what the numbers say

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Opus 5, for M. Hoffmann (deep review of every MIP + a live LLMOps/competitor research pass, 2026-09-07) |
| **Created** | 2026-09-07 |
| **Phase** | 0 (`--summarize`, the MCP tool, the MIP-0008 smoke panel) → 1 (the bot reply reuses the same function). No earlier-phase prerequisite is missing |
| **Related** | `ARCHITECTURE.md` §5a (the "mentioned a whale despite `whaleSightingLikelihood=Low`" finding this MIP turns into a caught, reported failure); MIP-0022 (**the precedent this copies exactly** — safety text applied by the rendering code *after* the model, never by it); MIP-0008 §8 ("if the reviewer says `reject`, show the numbers only" — the fallback shape); MIP-0012 §4.1/§5.4 (llm4s's `GroundingGuardrail`/`SourceAttributionGuardrail`/`LLMFactualityGuardrail`, reviewed there and rejected on weight — §4.3 below says why a deterministic guard beats them *here* regardless); MIP-0025 §6 (DPO to make the model refuse better — the weights-side complement to this code-side check, not a substitute); MIP-0032 (the benchmark matrix, which gains a violation-rate column for free); `AI-103-MAPPING.md` §1 "Responsible AI" |
| **Effort** | M — one pure module in `core/llm`, one `enum`, three call sites re-pointed (`Main`, the MCP server, `SiteBuilder`'s smoke panel), one env var, specs. No new dependency, no new module, no prompt change |
| **Gain** | `user value` (a swimmer never reads a fabricated wind direction or a contradicted jellyfish risk); `exam coverage (AI-103 §1 Responsible AI)` (a provable, non-removable check on generated text, one step past MIP-0022's non-removable footer) |
| **Effort vs Gain** | `do next` — the failure it catches is reproduced live in §2 on today's default model, and the check is pure Scala over a `Map[String, String]` that already exists |
| **Depends on** | Nothing blocking. No Phase 1 gate, no paid resource, no new API. MIP-0022 (merged, #195) is the pattern, not a prerequisite. MIP-0032 would consume this MIP's output as a column; neither blocks the other |
| **Blocked by** | none |
| **Risk** | Over-blocking. A guard that suppresses a *correct* summary because its number matcher is naive is worse than the fabrication it prevents — it makes `--summarize` useless and teaches the maintainer to turn it off. §5's high-precision-only rule set and §7's false-positive budget exist for that, and §8 says plainly that the rule set is the whole risk |
| **Cost so far** | — |

## 1. Summary

Everything marola computes is deterministic, and then one 3B model writes a sentence over it, a
second 3B model checks that sentence, and whatever comes back is printed. This MIP adds a
**deterministic** check between the model and the reader: `FactGuard` compares the generated text
against the same eight-field fact map the prompt was built from, and flags a claim the facts do not
support — a number that matches no field, a categorical risk stated at a level the data contradicts,
an attribute (a compass bearing, a tide state) marola never supplied. On a hard violation the
sentence is withheld and the deterministic block is shown instead; nothing is silently rewritten.
Pure Scala, unit-tested, no new dependency, and — like MIP-0022's footer — applied *after* the model,
so no model can remove it.

## 2. Motivation

`ARCHITECTURE.md` §5a already records the failure honestly: "the model mentioned a whale despite
`whaleSightingLikelihood=Low` … a small/quantized local model's imperfect instruction-following."
It is not a one-off. Run on this machine on **2026-09-07** against the repo's own default local
model and its 1B sibling, with the same eight facts `Main.factInputsFor` builds today
(`Main.scala:453-463`) and the mention policy the compiled prompt states:

```
facts: beach_name=Praia da Joaquina  hour_local=09:00  sea_temp_c=19.0  wind_kmh=20
       wave_height_m=1.3  jellyfish_risk=Low  whale_sighting_likelihood=Low  score=55

llama3.2:1b → "...with a wind speed of 20 km/h from the northwest. The wave height is
               relatively low at 1.3 meters, and there are no reported jellyfish sightings..."

llama3.2    → "...swimmers should be aware that there is a moderate jellyfish risk present,
               and while whale sightings are unlikely, they cannot be entirely ruled out..."
```

Two distinct, reproducible failures, both invisible to every check marola has:

1. **A fabricated attribute.** "from the northwest" is a wind *direction*. `factInputsFor` has no
   wind-direction field — the model invented a compass bearing and attached it to a real wind speed.
   Nothing in the pipeline can even notice, because there is no field to compare it to.
2. **A contradicted category.** `jellyfish_risk` was `Low`; the 3B model wrote "moderate jellyfish
   risk present". That is not a policy slip, it is the opposite of the datum — and jellyfish risk is
   the one heuristic that feeds `Swimability.score` (`ARCHITECTURE.md` §8).

**Today the only guard against both is `Reviewer` — which is the same model class making the
mistake.** `Reviewer.review` (`Reviewer.scala:29-44`) sends the draft to an LLM, parses a
`{score, verdict, final_summary}`, and `Main.reviewAndPrint` (`Main.scala:511-514`) prints
`final_summary` **regardless of the verdict** — a `revise` verdict changes the text shown, not
whether text is shown. `ARCHITECTURE.md` §5a's own status note says the smaller model's reviewer
"fixated on whale visibility instead of the more important jellyfish risk in one live run". An LLM
grading an LLM is the state of the art for open-ended prose (§4.2); it is the *wrong* tool when the
ground truth is eight typed fields sitting in memory one function call away.

This is also where the competitive argument lands. A research pass on 2026-09-07 found that "an LLM
writes a summary" is table stakes — Google shipped WeatherNext 3 into Search, Gemini and Maps on
2026-09-03, and shipped surf products already ship AI scores. What a general assistant cannot do is
prove its sentence agrees with IMA bulletin 43/2026 and a specific `Swimability` field. The
deterministic layer is marola's asset; this MIP extends it over the one output that currently
escapes it.

## 3. User-visible change

Clean case — nothing changes at all:

```
Draft summary: Conditions at Praia da Joaquina at 09:00 are fair: 19.0°C water, 1.3 m waves and a
               20 km/h wind. Score 55/100.
Reviewer (score 80/100, verdict: approve): ...
```

Hard violation — the sentence is withheld, the numbers stand on their own:

```
Draft summary: (withheld — the summary contradicted the data)
  fact guard: "moderate jellyfish risk" contradicts jellyfish_risk=Low
  The ranked list above is computed and unchanged. Model text is not shown when it disagrees
  with the numbers it was given.
```

Soft violation — the sentence is shown, with the flag under it, never edited:

```
Reviewer (score 70/100, verdict: approve): ... a 20 km/h wind from the northwest ...
  fact guard: "from the northwest" — marola gave no wind direction for this hour (unsupported).
```

MCP `get_swim_recommendation` / `ask_ocean_question` gain a structured
`"fact_guard": {"verdict": "clean|flagged|withheld", "violations": [...]}` so an agent client can
refuse the text programmatically rather than parsing English. The MIP-0008 smoke panel on the map
shows `withheld` as "numbers only", which is what §8 of that MIP already asked for.
`--brief` and every non-`--summarize` path are untouched.

## 4. Data sources and dependencies reviewed

**No new external data source.** The "context" this checks against is `Main.factInputsFor`'s
`Map[String, String]` — eight fields, already built, already passed to both the summariser and the
reviewer (`Main.scala:484`, `:508`). That is the whole point: marola's grounding problem is
unusually easy because its ground truth is typed and tiny.

### 4.1 Ollama structured outputs — **verified live, adopted as v2, not v1**

- Ollama's `/api/chat` accepts a JSON Schema in the `format` parameter and constrains decoding to
  it; the OpenAI-compatible endpoint exposes the same through `response_format`. The feature was
  announced 2024-12-06 and the docs recommend also asking for JSON in the prompt and setting
  temperature 0 (page fetched 2026-09-07).
- **Probed live on this machine, 2026-09-07**, `llama3.2` (3B), `format` = a four-property object
  schema, `temperature: 0` → returned `{"headline": "...", "sea_temp_c": 19, "wave_height_m": 1.3,
  "mentions_jellyfish": false}`, schema-valid, 52 output tokens, 1.66 s wall (1.31 s of it model
  load). The numbers came back **exactly equal** to the input facts.
- That makes a different design possible — have the model emit *slots* and render the sentence
  deterministically, so a fabricated compass bearing has nowhere to live. **It is not obviously
  better, and the 2026 evidence says so.** "The Constraint Tax" (arXiv:2605.26128, Jaideep Ray,
  submitted 2026-05-20, abstract fetched 2026-09-07) measured exactly this trade on models the size
  marola runs — Qwen2.5-0.5B, Qwen2.5-1.5B, SmolLM2-1.7B: hard answer-only schema decoding raised
  schema validity **61.5% → 100.0%** while answer accuracy *fell* **19.7% → 11.0%** and
  wrong-but-schema-valid outputs rose **49.5% → 88.9%**. A constrained model produces well-formed
  wrong answers more often, not fewer. So constrained generation would trade one failure mode
  (fabricated prose) for another (confidently mis-slotted numbers) — which the guard in §5.1 would
  then have to catch anyway.
- It also does not help the Azure Foundry path or any provider without schema decoding.
  **v1 is therefore the guard, which works on any provider's plain text; slot generation is a
  measured experiment in §5.3, not a planned successor.**

### 4.2 How the field checks generated text in 2026 — reviewed, and why marola should not copy it

- **Ragas `Faithfulness`** (docs fetched 2026-09-07) is the reference metric: "measures how
  factually consistent a response is with the retrieved context", computed in three steps —
  "Identify all the claims in the response", "Check each claim to see if it can be inferred from the
  retrieved context", then supported-claims ÷ total-claims. **Both the decomposition and the
  verification are done by an LLM** (`Faithfulness(llm=llm)`); the one non-LLM variant substitutes
  a dedicated hallucination-detection model (`FaithfulnesswithHHEM`), not rules.
- That design is correct for its problem — verifying prose against retrieved *prose*, where no
  rule can enumerate the claims. marola's summariser has the opposite shape: the context is eight
  labelled scalars and three closed enums. Decomposing "1.3 meters" out of a sentence and comparing
  it to `wave_height_m=1.3` needs a regex, not a model — and a regex cannot itself hallucinate,
  cannot cost a token, and cannot be talked out of its verdict.
- **llm4s's guardrails** (`GroundingGuardrail`, `SourceAttributionGuardrail`,
  `LLMFactualityGuardrail`, plus `ValidationMode.Block|Warn|Log`) were already inspected against the
  published jar in MIP-0012 §4.1 and rejected there on weight (154 transitive jars, ~150 MB, Azure
  and AWS SDKs on the `cli` classpath). Independent of that verdict, the two named grounding
  guardrails are model-backed, so adopting them would replace one LLM checking an LLM with a
  different LLM checking an LLM. `ValidationMode.Block|Warn|Log` is a good *shape*, though, and §5
  borrows it verbatim as three words rather than as a dependency.

### 4.3 Pick

marola's own pure module, over the existing fact map, with a deliberately small, high-precision
rule set. Zero dependencies, runs in microseconds, deterministic under test, and applicable to every
`LlmClient` backend including Azure Foundry. `Reviewer` stays exactly where it is — the two are
complementary and §6 says which one owns what.

## 5. Design

### 5.1 `core/llm/FactGuard.scala` — pure, no effect type, no model

```scala
package marola.llm

enum Severity derives CanEqual { case Hard, Soft }

enum Violation derives CanEqual:
  /** A quantity with a unit that matches no fact value: "1.9 m" when wave_height_m=1.3. */
  case NumberMismatch(quoted: String, unit: Unit, field: String, factValue: String)
  /** A closed-enum risk stated at a level the fact contradicts: "moderate jellyfish" vs Low. */
  case CategoryContradicted(field: String, saidLevel: String, factValue: String)
  /** A mention the policy forbids at this level: whales named while the fact is Low. */
  case MentionPolicy(field: String, factValue: String)
  /** An attribute class marola supplied no field for at all: a compass bearing, a tide state. */
  case Unsupported(attribute: String, quoted: String)

  def severity: Severity =                      // NumberMismatch/CategoryContradicted = Hard
    ...                                         // MentionPolicy/Unsupported            = Soft

enum GuardVerdict derives CanEqual { case Clean, Flagged, Withheld }

final case class GuardResult(verdict: GuardVerdict, violations: List[Violation], shown: Option[String])

object FactGuard:
  /** `mode` mirrors llm4s's Block/Warn/Log vocabulary (§4.2) without the dependency. */
  enum Mode derives CanEqual { case Block, Warn, Off }

  def check(text: String, facts: Map[String, String], mode: Mode): GuardResult
```

**The rule set is deliberately four rules, all high precision** — every one anchored to a field that
exists, none of them a general entailment check:

| Rule | How | Fields |
|---|---|---|
| Quantity match | extract `(\d+([.,]\d+)?)\s*(m|metres?|meters?|°C|C|km/h|kph)` and require each to equal a fact value for that unit, within the fact's own printed precision (`f"$t%.1f"`, `Main.scala:457-459`) | `sea_temp_c`, `wind_kmh`, `wave_height_m` |
| Category contradiction | the closed vocabulary `low|moderate|high|baixo|moderado|alto` within N tokens of `jellyfish\|água-viva\|water quality\|whale\|baleia` must not name a level above the fact's | `jellyfish_risk`, `whale_sighting_likelihood` |
| Mention policy | the subject is named at all while its fact is `Low` — the exact policy the compiled prompt states and `ARCHITECTURE.md` §5a saw broken | same two |
| Unsupported attribute | a closed list of attribute classes marola has **no field for**: compass bearings (`north-west`, `NE`, `nordeste`…), tide words (`high tide`, `maré alta`), UV, rain chance, water-quality verdicts — flagged only when `factInputs` lacks that key | n/a (absence) |

Everything else the model writes is left alone. There is no sentiment rule, no style rule, and no
"does this sound right" rule — those are `Reviewer`'s job (§6).

Precision, not recall, is the design target: the guard must never flag a correct sentence, and it is
allowed to miss a wrong one. A missed fabrication leaves marola exactly where it is today; a false
positive makes `--summarize` unusable.

### 5.2 Wiring — after the model, like MIP-0022's footer

`Main.reviewAndPrint` calls `FactGuard.check(result.finalSummary, factInputs, config.factGuardMode)`
**after** the reviewer and **before** `Console.printLine`, and renders per §3. The same three lines
go into `SwimConditionsMcpServer`'s summary path and into MIP-0008's smoke JSON. The prompts are not
touched: the model is never told the guard exists, so it cannot be prompted around it, and a
`Withheld` verdict cannot be produced by the model's own text (`Reviewer` could be talked into
`approve`; a regex cannot).

`AppConfig`: `MAROLA_FACT_GUARD=block|warn|off`, default **`warn`** for one release — flags printed,
nothing withheld — then `block` once §7's false-positive budget is measured on real output. Shipping
straight to `block` would risk suppressing good summaries before anyone has counted how often the
rules misfire.

### 5.3 What is explicitly a later experiment, not this MIP

Slot-constrained generation (§4.1): a second compiled artifact whose output field is a JSON object
of slots, `format`-constrained on the Ollama path, rendered into a sentence by `Report`. It would
put three of the four rules out of reach of the model — and, per the constraint-tax numbers in
§4.1, would probably make the *contents* of those slots less accurate at 3B. It also changes every
summary's wording and needs its own `docs/benchmarks/` comparison. Named here so it is not
re-derived as an obvious win; if it is ever tried, the guard is the instrument that measures
whether it helped, and the report must separate **schema validity from answer accuracy**, which is
that paper's own recommendation.

**A second, sharper warning belongs with it, because marola's MCP server is on the same runtime.**
"Constraint Tax in Open-Weight LLMs" (arXiv:2606.25605, Li/Zhang/Lv, submitted 2026-06-24, abstract
fetched 2026-09-07) reports that "when Tool Calling and JSON Schema constraints are simultaneously
enabled, multiple open-weight models cease invoking tools despite maintaining high schema
compliance", because "JSON Schema constraints are compiled into grammar-based token masks, causing
tool-call tokens to become unreachable during decoding". marola does not combine the two today —
`SwimConditionsMcpServer` exposes tools to an *external* client and marola's own `LlmClient` never
sends a `tools` array — so this is not a live bug here, and it was checked rather than assumed
(§Appendix). It becomes one the moment a future MIP (MIP-0025's Layer 2 tool-call work, or an
agent loop) sets both on one call. Recorded here so that MIP inherits the warning instead of
rediscovering it.

**What goes through the LLM: nothing new.** The guard is pure; its output strings are fixed labels.

## 6. Scoring / safety impact

`Swimability.score`, its thresholds, its notes and the water veto are **unchanged** — this MIP does
not touch `scoring/` at all.

The safety property it adds is the same *monotonic* one MIP-0022 §6 established, in the other
direction: from this MIP on, **no code path may show model-written prose to a user without having
compared it against the facts that prose was generated from**. `MAROLA_FACT_GUARD=off` exists for
debugging and is not the default; the spec in §7 is the guard on the guard.

Division of labour with `Reviewer`, stated so neither is assumed redundant:

| | owns | mechanism |
|---|---|---|
| `FactGuard` (this MIP) | does the sentence contradict a datum, or assert one marola never had? | deterministic, provable, free, offline |
| `Reviewer` (built) | is the sentence useful, well-formed, and does it lead with what matters? | a model's judgement, fallible, already live |

`Reviewer` is **not** removed, weakened or bypassed. MIP-0025's DPO layer (§5, Layer 3) makes the
*model* less likely to offend; this makes the *pipeline* refuse to print it when it does. All three
are independent, and the deterministic one is the only one that can be proven by a unit test.

## 7. Verification plan

- **`FactGuardSpec` (pure, the bulk of the work).** The two §2 transcripts are checked in verbatim as
  fixtures: the `llama3.2:1b` sentence must produce exactly one `Unsupported("wind direction",
  "from the northwest")` and no `NumberMismatch` (it quoted 20 km/h and 1.3 m correctly); the
  `llama3.2` sentence must produce `CategoryContradicted("jellyfish_risk", "moderate", "Low")` as
  `Hard` plus `MentionPolicy("whale_sighting_likelihood", "Low")` as `Soft`.
- **A false-positive corpus is a required deliverable, not a nice-to-have.** At least 20 *correct*
  summaries — the DSPy demos in `recommendation_prompt.json`, plus real drafts captured from
  `just run -- --summarize` — must every one come back `Clean`. A rule that flags any of them does
  not ship.
- Rounding and locale cases: "19 °C" and "19.0°C" both match `sea_temp_c=19.0`; "19,0 °C" (pt-BR
  decimal comma) matches; "1.9 m" against `wave_height_m=1.3` is a `NumberMismatch`; a bare `55`
  matching `score` is not flagged as a stray number.
- `MainSummarizeSpec` / `SummarizeFlowSpec` (extend): `Block` mode with a hard violation prints the
  §3 withheld block and **not** the model text; `Warn` prints both; `Off` prints today's output
  byte-for-byte.
- MCP: `get_swim_recommendation` carries `fact_guard` and the CLI text and the JSON agree.
- Live: re-run the §2 probes through `just run -- --summarize` with `MAROLA_FACT_GUARD=warn` and
  confirm the flags appear on the real path; `just e2e`; `just benchmark` unchanged (this path is
  not benchmarked today — see §11.3).
- **Done** = the above green, `ARCHITECTURE.md` §5a's whale finding gains "caught by `FactGuard`
  since MIP-0039" instead of standing as an open caveat, and one release has run in `warn` with the
  violation counts recorded before the default flips to `block`.

## 8. Risks, limitations, and honest caveats

- **The rule set is the entire risk, and it is a rule set, not an understanding.** Every rule is a
  regex over English and Portuguese surface forms. "not a moderate risk", "less than moderate",
  "moderada" with an accent, a number spelled as a word ("one point three metres") — each is a way
  to be wrong in either direction. `warn`-by-default, the false-positive corpus, and the
  precision-over-recall rule are the mitigations; none of them makes the guard complete.
- **It cannot catch a fabrication that names nothing.** "The sea looks inviting today" asserts
  something unsupported and matches no rule. This guard bounds a class of error, it does not
  eliminate hallucination, and the docs must not claim it does.
- **`Withheld` costs the user something real.** A suppressed summary is a worse page than a correct
  summary. That is the trade this MIP makes deliberately — the deterministic block above it is
  always still there, which is why withholding is affordable here and would not be in a product
  whose only output was prose.
- **Portuguese doubles the surface area.** The bot (MIP-0002) replies in pt-BR; the vocabulary lists
  need a native check, not a translation, before `block` is the default on that surface.
- **Two probes are not a failure rate.** §2 shows the failure is real and reproducible on two models
  at temperature 0; it does not establish how often it happens. Counting that is §7's `warn` release.
- **The unsupported-attribute list is an argument from absence.** It flags a compass bearing because
  `factInputs` has no wind-direction key — but `HourlyConditions` *does* carry `windDirectionDeg`
  (`OpenMeteoClient.scala:61`), it is simply not passed to the prompt. If a later MIP adds it to
  `factInputsFor`, that rule must be dropped in the same PR or it will flag a now-supported fact.
  Noted in the code, and in §11.

## 9. Alternatives considered

- **Do nothing.** The `--summarize` path keeps printing a contradicted jellyfish level to a swimmer,
  with the repo's own architecture doc describing the failure and nothing catching it. Lost.
- **Make the prompt stricter.** Already tried — the compiled prompt *does* state the mention policy,
  and §2 shows both models breaking it anyway. Prompting is what failed; that is the motivation.
- **Trust `Reviewer` and gate on its verdict** (don't print when `verdict=revise`). Cheaper, and it
  makes a 3B model the sole arbiter of whether a 3B model lied — `ARCHITECTURE.md` §5a already
  records the reviewer fixating on the wrong issue. Worth doing *as well*, not instead; §11.2.
- **An LLM-as-judge grounding metric (Ragas-style, §4.2).** The right tool for prose-vs-prose, the
  wrong tool for eight scalars: it costs a model call per summary, it is non-deterministic, it
  cannot be unit-tested, and it can be wrong in exactly the way it is meant to detect.
- **Adopt llm4s's `GroundingGuardrail`.** 154 jars for a model-backed check (MIP-0012 §4.1/§8), on a
  path where a regex is strictly stronger. Rejected; its `Block|Warn|Log` vocabulary is borrowed.
- **Slot-constrained generation only** (§4.1/§5.3), skipping the guard. The intuitive answer, and
  the measured evidence is against it at this model size: hard schema decoding cut answer accuracy
  from 19.7% to 11.0% and nearly doubled wrong-but-schema-valid outputs on 0.5B–1.7B models
  (arXiv:2605.26128). It also does not exist on providers without schema decoding, and it changes
  every summary's wording before anyone has measured the problem. Rejected as a *replacement*;
  kept as an experiment the guard itself would referee.
- **A fine-tune that refuses better** (MIP-0025 Layer 3, DPO). A real complement — and by
  `finetune/README.md`'s own caveat, tuning changes tone and format reliability, not factual
  grounding. It reduces the rate; it cannot make a guarantee.

## 10. Exam-coverage mapping

`AI-103-MAPPING.md` §1 "Responsible AI" — marola's strongest claim on that row today is MIP-0022's
non-removable footer (safety text the model cannot delete). This is its converse and a strictly
harder guarantee: model text the pipeline can refuse to print, decided by code the model never sees.
Mark "proposed: MIP-0039". `AI-500-MAPPING.md` §3 — a per-agent output check with a machine-readable
verdict is the first concrete artefact for "evaluate" that is not an LLM grading an LLM; noted
there, not claimed as coverage of the eval-harness gap `ROADMAP.md` §5 still assigns to an unwritten
MIP.

## 11. Open questions

1. **Should `factInputsFor` grow?** `HourlyConditions` already has wind direction, UV, precipitation
   probability and tide extrema; the prompt gets eight fields. Giving the model more true facts
   would remove the *cause* of §2's first failure rather than catch it. But every added field is one
   more thing a 3B model can misstate, so it is not obviously an improvement — a real decision,
   deliberately not made here, and it interacts with the §8 caveat about the absence-based rule.
2. **Gate on `Reviewer`'s verdict too?** Proposal: yes, as a separate one-line change — `revise`
   with a score below some threshold shows numbers only. It is not this MIP's mechanism and should
   not hide behind it.
3. **Benchmark the summariser path at all.** `just benchmark` measures the *answering* path (22
   questions × 3 arms); the summariser/reviewer path has no held-out check, as `FUTURE-WORK.md`
   §4.1 and §4.2 both say. The guard's violation rate is the first automatic, deterministic quality
   number that path has ever had — MIP-0032's matrix should carry it as a column, which needs one
   line in its `ArmSummary` and that MIP's agreement, not this one's assumption.
4. **`warn` → `block` criteria.** Proposal: flip when a release's worth of real runs shows zero
   false positives on the §7 corpus and a hard-violation rate above zero. Whoever flips it records
   the counts in the PR.
5. **pt-BR vocabulary** needs a native speaker's review before the bot surface runs in `block`.

## Appendix

### Checked live

All fetched or executed by the author on **2026-09-07**.

- **Local Ollama probe, free-text summariser path** (`POST http://localhost:11434/api/chat`,
  `temperature: 0`, `stream: false`), the eight fields of `Main.factInputsFor` and the compiled
  prompt's own mention policy as the system message:
  - `llama3.2:1b` → *"…with a wind speed of 20 km/h **from the northwest**. The wave height is
    relatively low at 1.3 meters, and there are no reported jellyfish sightings or whale sighting
    likelihoods for this hour."* — a compass bearing marola never supplied.
  - `llama3.2` (3B, the repo's `LocalLlmClient.DefaultModel` family) → *"…swimmers should be aware
    that there is a **moderate jellyfish risk** present, and while whale sightings are unlikely,
    they cannot be entirely ruled out at this hour."* — with `jellyfish_risk: Low` and
    `whale_sighting_likelihood: Low` in the facts.
  Both are the §2 fixtures.
- **Local Ollama probe, structured output**: same endpoint, `llama3.2`, `format` set to a JSON
  Schema object (`headline`, `sea_temp_c`, `wave_height_m`, `mentions_jellyfish`; all required),
  `temperature: 0` → `{"headline":"Praia da Joaquina","sea_temp_c":19,"wave_height_m":1.3,
  "mentions_jellyfish":false}`, `done_reason: "stop"`, `eval_count: 52`,
  `total_duration: 1.657 s` (`load_duration: 1.305 s`). Schema honoured; numbers echoed exactly.
- `https://ollama.com/blog/structured-outputs` (post dated 2024-12-06) → the `format` parameter
  takes a JSON schema on `/api/chat`; OpenAI-compatible clients use `response_format`;
  recommendations: define the schema with Pydantic/Zod, "Add 'return as JSON' to the prompt", "Set
  the temperature to 0 for more deterministic output". The post does not name the introducing
  version.
- `https://arxiv.org/abs/2605.26128` → "The Constraint Tax: Measuring Validity-Correctness
  Tradeoffs in Structured Outputs for Small Language Models", Jaideep Ray, submitted **2026-05-20**.
  Models tested: Qwen2.5-0.5B, Qwen2.5-1.5B, SmolLM2-1.7B. Hard answer-only schema decoding: schema
  validity **61.5% → 100.0%**, answer accuracy **19.7% → 11.0%**, wrong-valid-schema outputs
  **49.5% → 88.9%**. Tool-calling task: Qwen2.5-1.5B **91.5% → 48.0%** executable accuracy under a
  hard tool-call schema, both modes 100% schema-valid. Recommends reporting schema validity and
  answer accuracy separately.
- `https://arxiv.org/abs/2606.25605` → "Constraint Tax in Open-Weight LLMs: An Empirical Study of
  Tool Calling Suppression Under Structured Output Constraints", Fangzheng Li, Aimin Zhang, Chen Lv,
  submitted **2026-06-24**. "when Tool Calling and JSON Schema constraints are simultaneously
  enabled, multiple open-weight models cease invoking tools despite maintaining high schema
  compliance"; mechanism — "JSON Schema constraints are compiled into grammar-based token masks,
  causing tool-call tokens to become unreachable during decoding"; the authors' "Constraint Priority
  Inversion (CPI) hypothesis". The abstract names no specific models.
- **Checked in this repo, not assumed** (`grep -rn "tools" core/…/llm local/…/llm azure/…/llm` →
  no match, exit 1; `local/…/LocalLlmClient.scala:18-28`): marola's request body is `model` +
  `messages` only — no `tools` array and no `format`/`response_format` on any client. So the
  tool-suppression interaction above is **not** a live bug in marola today, which is why §5.3 states
  it as an inherited warning rather than a finding.
- `https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/faithfulness/` → "The
  Faithfulness metric measures how factually consistent a response is with the retrieved context";
  three steps — "Identify all the claims in the response", "Check each claim to see if it can be
  inferred from the retrieved context", supported ÷ total; scorer constructed as
  `Faithfulness(llm=llm)`, i.e. **LLM-based decomposition and verification**; `FaithfulnesswithHHEM`
  substitutes Vectara's hallucination-detection model for the verification step.
- Repo files read directly (not fetched): `cli/src/main/scala/marola/Main.scala` (`factInputsFor`
  453-463, `summarizeTop` 472-497, `reviewAndPrint` 499-517),
  `core/src/main/scala/marola/llm/Reviewer.scala` (whole file, 55 lines),
  `core/src/main/scala/marola/conditions/OpenMeteoClient.scala:61` (`wind_direction_10m` is fetched
  but not passed to the prompt), `docs/ARCHITECTURE.md` §5a status notes and §8,
  `docs/mips/MIP-0022-safety-answer-footer.md` §5/§6, `docs/mips/MIP-0012-...md` §4.1 (the llm4s
  guardrail list and the 154-jar closure), `docs/mips/MIP-0025-...md` §5/§8.

### Not checked

- **How often** the §2 failures occur. Two single-shot probes at temperature 0 on two models; no
  repeat runs, no other models, no other beaches or fact combinations. The `warn` release in §7 is
  what would produce a rate, and §8 says so.
- Whether `dolphin-mixtral:8x7b` (present locally, the model that compiled today's artifacts) makes
  the same mistakes — not probed. A larger model failing less would not change the design, since the
  shipped default is the 3B.
- Ollama's `format` behaviour through marola's own `LocalLlmClient` (which posts to the
  OpenAI-compatible `/v1/chat/completions`, not `/api/chat`) — the probe used the native endpoint.
  §5.3 depends on `response_format` working on the `/v1` path; MIP-0012 §4.3 reports a live check of
  `response_format: {"type":"json_object"}` there, but **not** of a full `json_schema`. Verify
  before building §5.3.
- NVIDIA NeMo Guardrails, Guardrails AI, Llama Guard and constrained-decoding libraries (Outlines,
  XGrammar) were named in the research pass but not fetched or evaluated here — none is a candidate,
  since the design adds no dependency, but they are not dismissed on evidence gathered in this MIP.
- The claim that "an LLM writes a summary" is commoditised (§2's competitive paragraph) rests on a
  research pass's search-result summaries about Google's WeatherNext 3 launch of 2026-09-03; the
  launch was not confirmed against a primary Google page by this author, and no design decision here
  depends on it.
