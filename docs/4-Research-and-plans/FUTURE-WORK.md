# Future work

Companion to [`ARCHITECTURE.md`](../2-Building-marola/ARCHITECTURE.md) (the pipeline, the integration pattern),
[`EFFECTS-MAP.md`](https://docs.marola.dev/5-Repos/marola-app/1-design_effects/) (what's pure vs. effectful vs. hidden), and
[`RUN-LOCALLY.md`](../1-Using-marola/RUN-LOCALLY.md)/[`TELEGRAM-SETUP.md`](../1-Using-marola/TELEGRAM-SETUP.md) (how to run any of
it): design sketches and reviewed-but-not-adopted ideas for where marola goes next. Most of this
file is *not* built: where a section reports a finding ("X is real and confirmed present"), that's
investigation done to inform a future decision. §4.2 is the one exception, marked as such: it moved
from proposal to shipped code partway through this file's life and is left in place with an updated
status rather than deleted, since the surrounding "why this exists" reasoning is still the right
context for it. The library reviews that used to be §2, §3, §5 and §6 here now live with the code
they judge, in marola-app's [`2-libraries.md`](https://docs.marola.dev/5-Repos/marola-app/2-libraries/);
the old §7 (splitting marola out of an earlier shared repo) is retired, its still-relevant part now
marola-app's [ADR-0001](https://docs.marola.dev/5-Repos/marola-app/adr/0001-three-sbt-modules/).
The remaining sections keep their original numbers.

## 1. Beyond swimming: diving, surfing, any sea-related activity

The biggest structural idea on the table: marola doesn't have to be swimming-only. Diving and
surfing are the obvious next two, and the architecture below is written to generalize to
"whatever sea-related activity someone wants conditions for," not just those three.

### 1.1 Why `Swimability` doesn't generalize as-is

`scoring/Swimability.scala` encodes swimming's preferences directly into its thresholds: calm seas
score well (`CalmWaveHeightM = 0.6`), rough seas score badly (`RoughWaveHeightM = 1.5`). **Surfing
wants the opposite**: bigger, well-formed swell is the point, calm seas make for boring surf. This
isn't a threshold-tuning problem, it's a "different activities want structurally different things
from the same data" problem. Diving is a third shape again: divers care about underwater visibility
(not something Open-Meteo exposes at all; see §1.4) and want *low* current, but wave height at the
surface matters far less than it does for swimming, since most of a dive happens below the surface
chop.

### 1.2 Proposed shape: one scoring function per activity, same input data

```scala
enum Activity derives CanEqual:
  case Swim, Surf, Dive

trait ActivityScoring:
  def activity: Activity
  def score(hour: HourlyConditions): (Int, List[String])

object SwimScoring extends ActivityScoring:            // today's Swimability, renamed/kept as-is
  val activity = Activity.Swim
  def score(hour: HourlyConditions) = Swimability.score(hour)

object SurfScoring extends ActivityScoring:
  val activity = Activity.Surf
  def score(hour: HourlyConditions) = ???               // see §1.3 for what it needs

object DiveScoring extends ActivityScoring:
  val activity = Activity.Dive
  def score(hour: HourlyConditions) = ???               // see §1.4 for what it needs
```

`Recommender` becomes parameterized over `ActivityScoring` instead of hardcoding
`Swimability.score`; `BestHour` gains an `activity: Activity` field. Jellyfish/whale heuristics
stay activity-agnostic (a jellyfish is a jellyfish whether you're swimming or diving near it) and
apply as a cross-cutting note regardless of which `ActivityScoring` produced the base score.

**Adding a new activity should mean writing one new `ActivityScoring` object, not touching
`Recommender`, `BeachFinder`, or any client code.** That's the actual design goal here: "any
sea-related activity" is a statement about extensibility, not a promise to build fishing/kayaking/
kitesurfing scoring today.

### 1.3 What surf scoring specifically needs

Open-Meteo's Marine API already exposes fields `OpenMeteoClient` doesn't currently fetch, which are
exactly what surf scoring needs:

- `wave_period` / `swell_wave_period`: a 1m wave at 4s period is mushy chop; the same height at
  12s period is a real, rideable swell. Wave *height alone* (what `HourlyConditions` has today) is
  close to useless for surf without period.
- `swell_wave_height`, `swell_wave_direction`: separates groundswell (what surfers want) from
  wind-driven chop (`wind_wave_height`), which Open-Meteo also reports separately.
- Wind direction *relative to the coastline's orientation*: offshore wind grooms waves clean,
  onshore wind blows them out. This needs a per-beach "faces" attribute (which direction the beach
  opens to the ocean) that doesn't exist anywhere yet. Overpass doesn't reliably tag this, so it'd
  likely need a small hand-curated lookup for known spots, not something auto-derived.

None of this is hard to fetch (add the field names to `OpenMeteoClient`'s query string, extend
`HourlyConditions`); the real design work is the per-beach orientation data, which is a genuinely
new kind of input this repo doesn't have a source for yet.

### 1.4 What dive scoring specifically needs

- **Underwater visibility**: no free API was found for this (checked while researching; same
  "doesn't exist" conclusion [LIMITATIONS §8](../1-Using-marola/LIMITATIONS.md#the-jellyfish-and-whale-heuristics-honest-limitations)
  reached for jellyfish forecasts). The closest real
  signal: [Copernicus Marine Service](https://marine.copernicus.eu/) publishes turbidity and
  chlorophyll concentration layers that correlate with visibility, but it's a much heavier
  integration (NetCDF/gridded data, not a simple REST JSON call like everything else this repo
  uses), a real candidate for "the next tier of integration effort," not a quick add. Copernicus is
  free registration, not requiring a cloud account or payment, consistent with the local-first
  ethos.
- **Current strength**: already fetched (`ocean_current_velocity`, used today for the whale/
  jellyfish heuristics); divers want it *low*, same direction of preference as jellyfish-avoidance,
  opposite of "doesn't matter much" for casual swimming.
- **Surface chop at entry/exit points** matters even though mid-dive conditions don't: a real
  nuance that a single wave-height threshold undersells; likely needs its own, gentler threshold
  than swimming's.

### 1.5 Multi-subscription: users track more than one activity

"Users can subscribe to more than one activity" is a per-user preference, which needs persisted
per-user state, something marola doesn't have yet in any form (the closest existing piece is
`SightingStore`, which is per-*beach*, not per-*user*). The natural extension, following the same
local-default pattern as everything else
([ARCHITECTURE](../2-Building-marola/ARCHITECTURE.md#local-first-integration-pattern)):

```scala
trait UserPreferencesStore:
  def subscribedActivities(userId: String): Set[Activity] < Sync
  def subscribe(userId: String, activity: Activity): Unit < Sync
  def unsubscribe(userId: String, activity: Activity): Unit < Sync
```

with `LocalFileUserPreferencesStore` (JSON-lines, same shape as `LocalFileSightingStore`) as the
default. The Telegram bot (once built; see `TELEGRAM-SETUP.md` and [PHASES](../PHASES.md) Phase 1)
would expose this via commands like `/subscribe surf`, `/unsubscribe dive`, and a daily digest that
only includes conditions for a user's subscribed activities.

## 4. Harness ideas: evaluation and a reviewer/critic pass

Two gaps worth naming explicitly, both about *checking marola's own output quality*, not new
product features:

### 4.1 An actual evaluation harness, not just a training set

> Partly built: `just benchmark`, in a marola-app checkout ([Ocean knowledge](https://docs.marola.dev/5-Repos/marola-app/1-design_integrations/#ocean-knowledge-retrieval)), is a
> deterministic held-out check for the *answering* path (RAG vs. plain prompt). The
> summarizer/reviewer path still has only its trainset.
> The model axis of that benchmark (closed API vs. open local vs. RAG vs. tuned, with latency and
> cost columns) is proposed as `docs/MIPs/MIP-0032-model-strategy-benchmark-matrix.md`.

marola-ml's [`dspy/compile_recommendation_prompt.py`](https://github.com/marola-dev/marola-ml/blob/main/dspy/compile_recommendation_prompt.py)'s
`TRAINSET` currently does double duty as both the
few-shot demo source *and* the only quality check (`jellyfish_and_whale_mentioned_when_relevant`,
a crude keyword-match metric (literally `"jelly" not in text.lower()`). DSPy has a dedicated
[`dspy.Evaluate`](https://dspy.ai) utility for scoring a compiled program against a **held-out**
dev set, separate from what it was optimized against, not currently used. Concretely:

- Split `TRAINSET` into a real train/dev split as it grows past its current 3 examples.
- Run `dspy.Evaluate` after every compile, and fail/warn if the score regresses: this is the
  natural place to add a CI-style check for "did the last prompt-source edit make the model worse."
- Consider upgrading the metric itself from keyword-matching to an **LLM-as-judge**: DSPy's own
  recommended pattern for metrics that keyword-matching can't capture well (e.g. "does this
  summary sound natural," "does it avoid asserting anything not in the input data"). This is a
  second, smaller DSPy signature+program, not a new library.
- Separately, the deterministic `Swimability`/future `ActivityScoring` heuristics (§1) have no
  regression harness beyond hand-written unit tests (`SwimabilitySpec`). A small "golden day"
  fixture set, real historical Open-Meteo responses for a handful of known-good/known-bad
  conditions, replayed through the scoring functions, would catch a threshold-tuning mistake
  before it ships, the same way `SwimabilitySpec` does today but against real historical data
  instead of hand-constructed `HourlyConditions` values.

### 4.2 A reviewer/critic pass on generated summaries — BUILT

This one moved from proposal to shipped code: `Main --summarize` no longer sends the summarizer's
raw output straight to a user. `llm/Reviewer.scala` replays a second DSPy-compiled signature
(`ReviewSwimSummary`, compiled alongside the summarizer in
`compile_recommendation_prompt.py`) against the draft summary, checking exactly the three things
originally proposed here (jellyfish/whale mention policy, no assertions beyond the given facts, no
overclaimed safety framing) and returning a `0-100` score, a `verdict` (`approve`/`revise`), and a
`final_summary`: the original text unchanged if approved, or the reviewer's own correction if not.
Verified live against a real local Ollama model, including one run where the reviewer's compiled
demos correctly caught a deliberately-planted flaw (a draft missing a required jellyfish mention)
and produced a corrected version; see marola-app's [Query synthesis](https://docs.marola.dev/5-Repos/marola-app/1-design_integrations/#query-synthesis) and
[`review_prompt.json`](https://github.com/marola-dev/marola-app/blob/main/core/src/main/resources/review_prompt.json)
(a real compiled artifact, landed by a bot PR from marola-ml, not a hand-written fixture).

**What's still open, not done:** the metric that scored the *compile-time* trainset
(`review_json_is_well_formed_and_sound`) only checks JSON structure and verdict match against 3
examples, nowhere near the `dspy.Evaluate`-based held-out eval loop §4.1 above describes.
Everything in §4.1 (a real dev/train split, `dspy.Evaluate`, an LLM-as-judge upgrade) applies to
*this* signature too, not just the summarizer. Treat the reviewer's existence as "the layer now
exists," not "the layer is rigorously evaluated": those are different bars, and only the first one
is cleared today.

## 8. Smaller items already flagged elsewhere

Not repeated in full here; see the cross-referenced section:

- Calibrating the jellyfish/whale heuristics against real `SightingStore` data:
  [LIMITATIONS §8](../1-Using-marola/LIMITATIONS.md#the-jellyfish-and-whale-heuristics-honest-limitations).
- Real per-beach travel time/distance instead of straight-line distance (driving/walking/transit
  modes); [Beach distance](https://docs.marola.dev/5-Repos/marola-app/1-design/#beach-distance),
  [LIMITATIONS §9](../1-Using-marola/LIMITATIONS.md#other-known-limitations-poc-stage-not-hidden).
- Caching and per-user rate limiting for the core pipeline:
  [LIMITATIONS §9](../1-Using-marola/LIMITATIONS.md#other-known-limitations-poc-stage-not-hidden),
  [PHASES](../PHASES.md) Phase 4.

## 9. Ocean-knowledge grounding: RAG and fine-tuning over marine science, plus catastrophe detection

Two connected product ideas, both aimed at the same theme: marola currently only reasons over live
*sensor* data (Overpass, Open-Meteo). It has no grounding in the *body of knowledge* about the
ocean (marine biology, oceanography, coastal-hazard research) and no concept of an
out-of-distribution event. Both ideas below close real gaps (RAG/fine-tuning, first-class text
analysis) with one coherent feature rather than two disconnected ones.

### 9.1 "marola knows the ocean": RAG and/or fine-tuning over marine literature

> Which base model, which checkpoint and which hardware that fine-tune should use — and why the
> corpus, not the parameter count, is the binding constraint — is designed in
> [`MIP-0048`](../MIPs/MIP-0048-scaling-marola-sea.md).

> **Built (first cut):** `docs/MIPs/MIP-0001-water-quality-and-sea-lore.md`: local RAG over
> marola-corpus's [`knowledge/`](https://github.com/marola-dev/marola-corpus/tree/main/knowledge)
> with citations ([Ocean knowledge](https://docs.marola.dev/5-Repos/marola-app/1-design_integrations/#ocean-knowledge-retrieval)), the sourced sea-lore paragraph, and marola-ml's
> [`finetune/`](https://github.com/marola-dev/marola-ml/tree/main/finetune) scaffold (Tier 1 built,
> Tier 2 written-not-run). Steps 1-3 below are
> now real; step 4 (fine-tuning) has its recipe but no evaluation yet. The *retrieval* half is
> revisited in `docs/MIPs/MIP-0045-nlp-and-parsing-over-llm.md` §5.1, which proposes a lexical
> (TF-IDF) `KnowledgeStore` as the local default in place of the per-question embedding call. The
> store and the chunker themselves (Lucene HNSW + BM25 locally, typed chunks with provenance, a
> golden-set recall@k) are designed in
> [`MIP-0055`](../MIPs/MIP-0055-vector-store-and-ocean-knowledge-chunking.md).

**The pitch:** today, if a user asks "what should I do if I get stung by a jellyfish here," marola
has nothing. It's not a question `Recommender`'s pipeline answers at all. A grounded knowledge base
turns marola from "a conditions calculator with a sentence generator on top" into something that can
actually answer open marine-safety/marine-biology questions, sourced, not hallucinated.

**Proposed shape (RAG first, fine-tuning as a later refinement, not the reverse):**

1. **Corpus.** Public, freely licensed sources: NOAA/oceanographic-agency public bulletins, open-
   access marine biology papers (PLOS ONE, open preprints), Wikipedia-tier species/hazard pages as a
   floor, and, since jellyfish/rip-current/species specifics are regionally different, locally
   relevant coastal-hazard guidance for wherever marola is actually deployed. Licensing has to be
   checked per source before ingesting anything; this is a real constraint, not a footnote.
2. **Retrieval.** A small local embedding model (Ollama serves embedding models too, e.g.
   `nomic-embed-text`, which keeps the local-first, no-cloud-account property this whole repo is
   built around) over chunked documents. Ingesting the corpus is itself a text-analysis/extraction
   task (pulling structured hazard facts out of prose bulletins).
3. **New module shape:** marola-app's
   [`core/knowledge/`](https://github.com/marola-dev/marola-app/tree/main/core/src/main/scala/marola/knowledge)
   package (`KnowledgeStore` trait, mirroring the existing
   `SightingStore`/`VisionClient` trait pattern) plus a new agent role: a
   "marine-knowledge agent" distinct from the summarizer/reviewer pair, callable as its own MCP tool
   (`ask_ocean_question`) so it's usable independently of the swim-hour pipeline, not bolted onto it.
4. **Fine-tuning** is a genuine later step, not a prerequisite: only worth it once RAG's retrieval
   quality on real user questions is measured and found wanting for something a fine-tune would
   actually fix (tone, domain vocabulary, a very narrow structured-output format). Fine-tuning a
   small open model (a `llama3.2` variant, via Ollama's `Modelfile` + a QLoRA-style adapter) on that
   specific gap is far cheaper and more honest than fine-tuning as a first move.

### 9.2 A fourth agent: catastrophe/hazard detection, competing with public alerts

**The pitch, stated directly since it's the more consequential idea here:** the same live conditions
data marola already fetches (wave height, wind, current, tide/weather trends over a time range, not
just tomorrow) is exactly the input a rip-current, storm-surge, or dangerous-sea-state detector would
need. Framed honestly: this is *not* about replacing official government emergency systems (Civil
Defense/meteorological-agency alerts). It's about being a **faster, hyper-local, opt-in companion
channel** for people who live near the sea, alongside those official channels, not instead of them.

**Why this is a genuinely different agent, not a variant of the existing summarizer:** the
summarizer answers "what's good," a single-hour, single-beach, best-case question. This agent has to
answer "is something bad happening or about to happen, across a time range, and is it bad enough to
interrupt someone unprompted": a monitoring/anomaly-detection question, not a recommendation
question. It needs:

- **A trend/anomaly view over the existing time-series data** (`OpenMeteoClient` already fetches
  hourly data: this needs looking at the *shape* of the next N hours, not just picking the best
  one), which is new logic, not a reuse of `Swimability.score`.
- **A conservative, false-positive-averse threshold design**: this is the first place marola would
  act *without being asked* (a proactive Telegram push), which is a materially different risk/trust
  profile than answering a query, and needs its own human- confirmation gate on the alerting
  behavior itself before it ever ships, not just on cloud spend.
- **Explicit, honest scoping against official sources**: cross-referencing (not replacing) whatever
  official public alert feed is available for the deployment region, and being clear in the product
  copy itself that this is a supplementary heads-up, never the authoritative source, particularly
  given the "competing with governance public announcements" framing carries real liability/trust
  implications if done carelessly.

**Where this lands architecturally:** this is the third agent after summarize and critique: the
escalation agent *is* this hazard detector. Building it is both a real safety feature and the
clearest path to a genuine multi-agent architecture (three agents, three distinct roles, one of them
with a different orchestration trigger: a schedule/poll, not a user query).

## 10. Scala/JVM gap in the prompt-engineering and LLMOps ecosystem — and `ds4s`

Surveyed (web search, September 2026) what the Python LLMOps/prompt-engineering ecosystem has that
Scala/the JVM doesn't, specifically because marola already leans on one of these tools (DSPy) via a
Python subprocess step rather than natively, and it's worth being explicit about why, and what
closing that gap would take.

**Python-only tools with no confirmed Scala/JVM equivalent today:**

- **[DSPy](https://dspy.ai)**: declarative LLM programming + optimizers (`BootstrapFewShot`,
  `MIPROv2`). marola already depends on it, via marola-ml's
  [`dspy/`](https://github.com/marola-dev/marola-ml/tree/main/dspy). No JVM port exists (confirmed
  by search, not just absence of prior knowledge.
- **[Langfuse](https://github.com/langfuse/langfuse)**: open-source LLM tracing/eval/prompt-
  management platform. Ships Python and TypeScript SDKs; no JVM/Scala SDK. marola-app's
  [`Tracing.scala`](https://github.com/marola-dev/marola-app/blob/main/core/src/main/scala/marola/observability/Tracing.scala)
  ([Observability](https://docs.marola.dev/5-Repos/marola-app/1-design_integrations/#observability)) covers general OpenTelemetry tracing but nothing LLM-call-shaped
  (prompt/completion pairs, token/cost tracking, eval scores attached to a trace).
- **[Promptfoo](https://github.com/promptfoo/promptfoo)**: prompt/model red-teaming and comparison,
  CLI + YAML config, Node-based; no Scala equivalent.
- **[DeepEval](https://github.com/confident-ai/deepeval)** / **[RAGAS](https://github.com/explodinggradients/ragas)**:
  pytest-native LLM/RAG quality metrics. Nothing pytest-shaped exists for munit because the
  underlying metric libraries (embedding-similarity judges, RAG-specific metrics) are Python-only
  dependencies themselves.

**What this means for marola specifically:** the DSPy compile step will likely stay a Python
subprocess indefinitely. Porting DSPy itself is a large undertaking, not a marola-sized task (see
below). The more immediately actionable gap is Langfuse-shaped tracing, since the MLflow tracing
path (MIP-0010) already has the OpenTelemetry plumbing in place; adding structured LLM-call spans
(prompt, completion, model, latency, token count) to the existing `LocalLlmClient` call sites is a
scoped, real improvement that doesn't require adopting a whole new platform.

**The larger idea, named and scoped honestly as a separate future project, not a marola subtask:
`ds4s` ("DSPy for Scala").** A from-scratch Scala port of DSPy's core ideas: `Signature`
(input/output field declarations with types and descriptions), a `Predict`-equivalent module, and at
least one optimizer (`BootstrapFewShot` is the simpler target; `MIPROv2`'s Bayesian search is a much
later stretch goal), built on Kyo for the effect boundary (LLM calls are `< Sync`/`< Async`,
consistent with everything else in this repo) and `kyo-schema`/Iron for typed signature fields
instead of DSPy's dynamic Python typing. This is explicitly **not scoped for this repo to build as
part of marola**: it's a separate library-shaped project, large enough to be its own repo, that
marola would become a *consumer* of once it existed (replacing marola-ml's
[`compile_recommendation_prompt.py`](https://github.com/marola-dev/marola-ml/blob/main/dspy/compile_recommendation_prompt.py)
with a Scala equivalent and eliminating the Python subprocess step entirely). Flagging it here as the
concrete, named future-work item it deserves to be, rather than leaving "someone should port DSPy to
Scala" as an unrecorded aside.

**Update (2026-09-05):** the "Langfuse-shaped tracing" half of this section is proposed as
`docs/MIPs/MIP-0010-mlflow-experiment-tracking.md`: MLflow's server ingests OpenTelemetry traces
over OTLP/HTTP from any language, so the JVM side needs no LLMOps SDK; `ds4s` stays a separate
project by its own definition above. `docs/MIPs/CANDIDATES.md` lists this file's MIP candidates.

**Update (2026-09-05, continued):** `MIP-0010.tasks.md` tasks 5-6 (tracing core split, marola-app's
[`MlflowTracing.scala`](https://github.com/marola-dev/marola-app/blob/main/local/src/main/scala/marola/observability/MlflowTracing.scala)
+ `TracedLlmClient`) are the JVM half that actually closes this gap, planned/in progress as of this
note, not confirmed merged. marola-ml's
[`compile_recommendation_prompt.py`](https://github.com/marola-dev/marola-ml/blob/main/dspy/compile_recommendation_prompt.py)
already logs its own compile runs to MLflow (task 7, the Python-only half, independent of tasks
5-6); see its
[`README.md`](https://github.com/marola-dev/marola-ml/blob/main/dspy/README.md)'s "Optional:
logging compile runs to MLflow" section.

**Update (2026-09-05, later):** the "DSPy stays a Python subprocess indefinitely" conclusion is
revisited by `docs/MIPs/MIP-0012-llm4s-adoption-and-dspy-deprecation.md`: marola's actual use of
DSPy (a three-example `BootstrapFewShot` with a deterministic metric) is small enough to own as a
Scala step alongside `CompiledPrompt`, over the existing `LlmClient`, with the held-out eval §4.1 asks for;
`ds4s` as a *general library* remains a non-marola idea. The same MIP checks llm4s against its jar
(agent loop, MCP client/server, guardrails, structured output, and no prompt optimiser).

## 11. Personal history input: Garmin data (FIT-file import first, not a live API integration)

**The pitch:** weight recommendations by a user's own swim history: "you've swum at Praia do
Diabo 12 times, always in the morning" is a real personalization signal none of marola's current
data sources (Overpass, Open-Meteo) can provide. Garmin devices/watches are a natural source for
swimmers specifically (many track open-water swim activities: GPS track, duration, pace, heart
rate). Raised as an idea, checked for feasibility, not built.

**Why this is an access-complexity problem, not an engineering one.** Garmin has no self-serve
public API comparable to Overpass/Open-Meteo:

- **Garmin Health API** (the official route) is B2B-only, requires applying to and being approved
  for Garmin's Connect Developer Program, with a business justification. Disproportionate for a
  personal project, and a real dependency on a third party's approval process that could just say
  no.
- **Unofficial clients** (e.g. `python-garminconnect`-style libraries that call Garmin Connect's
  undocumented mobile-app endpoints) work today but carry real ToS/breakage risk. This is exactly
  the class of "reverse-engineered workaround" this repo has deliberately avoided everywhere else
  (Overpass and Open-Meteo were chosen specifically because they're free *and* documented, not
  because free-but-fragile was acceptable).
- **FIT/TCX file export**: Garmin Connect lets a user export their own activity data as a
  standard file format (FIT is Garmin's own binary format; TCX is XML and easier to start with).
  This needs zero auth, zero partnership, zero ToS exposure: the user exports their own file and
  hands it to marola directly.

**Recommended shape, if this gets built: start with (c), never (b).**

1. A new package in `core` (mirroring the existing store-trait pattern where it makes sense) with
   a `SwimHistoryStore` reading manually-imported activity records: beach name (or nearest-match by
   GPS coordinates against `BeachFinder`'s results), timestamp, duration.
2. A small **TCX parser first** (XML, human-readable, easier to hand-write correctly than FIT's
   binary format, same "small hand-rolled parser for one specific external shape" pattern as
   `Json.scala`, and this time it should ship with real unit tests from day one, unlike `Json.scala`
   (see `SKILLS.md` Stage 6's test-coverage finding). FIT support is a reasonable later addition
   once TCX proves the shape is useful.
3. Feed accumulated history into `Recommender`/`Swimability` as a *tie-breaker or personalization
   note*, not a scoring input that could contradict live conditions data: "you usually swim here
   Sunday mornings" is context to surface alongside the numbers, not a reason to override a genuine
   safety-relevant heuristic (jellyfish/rough-seas deductions stay authoritative).
4. Revisit the official Health API or an unofficial client only if manual import proves the feature
   is actually worth the friction: cheap validation before an expensive/risky integration decision,
   same reasoning the [phase discipline](../PHASES.md) already applies elsewhere in this repo.
