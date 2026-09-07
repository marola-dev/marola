# marola — Future work

Companion to [`ARCHITECTURE.md`](./ARCHITECTURE.md) (the pipeline, the six pluggable integrations),
[`EFFECTS-MAP.md`](./EFFECTS-MAP.md) (what's pure vs. effectful vs. hidden), and
[`RUN-LOCALLY.md`](./RUN-LOCALLY.md)/[`TELEGRAM-SETUP.md`](./TELEGRAM-SETUP.md) (how to run any of
it): design sketches and reviewed-but-not-adopted ideas for where marola goes next. Most of this
file is *not* built — where a section reports a finding ("X is real and confirmed present"), that's
investigation done to inform a future decision. §4.2 is the one exception, marked as such: it moved
from proposal to shipped code partway through this file's life and is left in place with an updated
status rather than deleted, since the surrounding "why this exists" reasoning is still the right
context for it.

## 1. Beyond swimming: diving, surfing, any sea-related activity

The biggest structural idea on the table: marola doesn't have to be swimming-only. Diving and
surfing are the obvious next two, and the architecture below is written to generalize to
"whatever sea-related activity someone wants conditions for," not just those three.

### 1.1 Why `Swimability` doesn't generalize as-is

`scoring/Swimability.scala` encodes swimming's preferences directly into its thresholds: calm seas
score well (`CalmWaveHeightM = 0.6`), rough seas score badly (`RoughWaveHeightM = 1.5`). **Surfing
wants the opposite** — bigger, well-formed swell is the point, calm seas make for boring surf. This
isn't a threshold-tuning problem, it's a "different activities want structurally different things
from the same data" problem. Diving is a third shape again: divers care about underwater visibility
(not something Open-Meteo exposes at all — see §1.4) and want *low* current, but wave height at the
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
`Recommender`, `BeachFinder`, or any client code.** That's the actual design goal here — "any
sea-related activity" is a statement about extensibility, not a promise to build fishing/kayaking/
kitesurfing scoring today.

### 1.3 What surf scoring specifically needs

Open-Meteo's Marine API already exposes fields `OpenMeteoClient` doesn't currently fetch, which are
exactly what surf scoring needs:

- `wave_period` / `swell_wave_period` — a 1m wave at 4s period is mushy chop; the same height at
  12s period is a real, rideable swell. Wave *height alone* (what `HourlyConditions` has today) is
  close to useless for surf without period.
- `swell_wave_height`, `swell_wave_direction` — separates groundswell (what surfers want) from
  wind-driven chop (`wind_wave_height`), which Open-Meteo also reports separately.
- Wind direction *relative to the coastline's orientation* — offshore wind grooms waves clean,
  onshore wind blows them out. This needs a per-beach "faces" attribute (which direction the beach
  opens to the ocean) that doesn't exist anywhere yet — Overpass doesn't reliably tag this, so it'd
  likely need a small hand-curated lookup for known spots, not something auto-derived.

None of this is hard to fetch (add the field names to `OpenMeteoClient`'s query string, extend
`HourlyConditions`); the real design work is the per-beach orientation data, which is a genuinely
new kind of input this repo doesn't have a source for yet.

### 1.4 What dive scoring specifically needs

- **Underwater visibility** — no free API was found for this (checked while researching; same
  "doesn't exist" conclusion `ARCHITECTURE.md` §8 reached for jellyfish forecasts). The closest
  real signal: [Copernicus Marine Service](https://marine.copernicus.eu/) publishes turbidity and
  chlorophyll concentration layers that correlate with visibility, but it's a much heavier
  integration (NetCDF/gridded data, not a simple REST JSON call like everything else this repo
  uses) — a real candidate for "the next tier of integration effort," not a quick add.
  Copernicus is free registration, not requiring Azure or payment, consistent with the local-first
  ethos.
- **Current strength** — already fetched (`ocean_current_velocity`, used today for the whale/
  jellyfish heuristics); divers want it *low*, same direction of preference as jellyfish-avoidance,
  opposite of "doesn't matter much" for casual swimming.
- **Surface chop at entry/exit points** matters even though mid-dive conditions don't — a real
  nuance that a single wave-height threshold undersells; likely needs its own, gentler threshold
  than swimming's.

### 1.5 Multi-subscription: users track more than one activity

"Users can subscribe to more than one activity" is a per-user preference, which needs persisted
per-user state — something marola doesn't have yet in any form (the closest existing piece is
`SightingStore`, which is per-*beach*, not per-*user*). The natural extension, following the same
local-vs-Azure pattern as everything in `ARCHITECTURE.md` §5:

```scala
trait UserPreferencesStore:
  def subscribedActivities(userId: String): Set[Activity] < Sync
  def subscribe(userId: String, activity: Activity): Unit < Sync
  def unsubscribe(userId: String, activity: Activity): Unit < Sync
```

with `LocalFileUserPreferencesStore` (JSON-lines, same shape as `LocalFileSightingStore`) as the
default and a `CosmosDbUserPreferencesStore` option — genuinely the same container even, since
Cosmos DB doesn't need a schema up front; `sightings` and `user_preferences` could be two
containers in one database. The Telegram bot (once built — see `TELEGRAM-SETUP.md` and
`ARCHITECTURE.md` §11 Phase 1) would expose this via commands like `/subscribe surf`,
`/unsubscribe dive`, and a daily digest that only includes conditions for a user's subscribed
activities.

## 2. Reviewed: kyo-http + kyo-schema instead of the hand-rolled `Http`/`Json` modules

**Finding: the hand-rolled modules are not a "kyo doesn't have this" workaround — kyo-http's own
client (`getJson`, `postJson`, `getText`, `postBinary`, ...) is real and present at the exact
1.0.0-RC5 version this repo pins,** confirmed by decompiling the actual jar rather than relying on
getkyo.io's docs (which are only published for the *latest* version, RC6, and don't cover RC5
specifically — a documentation gap, not an API gap). Its JSON codec typeclass, `kyo.Schema[A]`,
lives in a separate `kyo-schema` artifact that's *already* being pulled in transitively via
`kyo-http`'s own dependency graph — no new dependency would be needed to use it.

**What migrating would mean:** dropping `http/Http.scala` (currently ~130 lines: 4 methods —
`getString`, `postForm`, `postJson`, `postBytes`) and `json/Json.scala` (~200 lines: a
recursive-descent parser plus a renderer) in favor of `kyo.HttpClient.getJson[A]`/`postJson[A, B]`
calls with `derives Schema` case classes for every shape this repo currently parses by hand
(Overpass responses, Open-Meteo responses, Ollama/Foundry chat completions, Azure Maps/Vision
responses, Telegram's Bot API shapes once the bot exists). That's a real, positive trade: less
hand-rolled parsing code, typed models instead of stringly-typed `JsonValue` navigation, and a
client that (per kyo-http's design) also targets JS/Native if marola's Scala code were ever reused
outside the JVM.

**Why not done in this pass:** every HTTP/JSON call site in this repo was *live-verified* against
real APIs while building it this session (Overpass, Open-Meteo, Ollama, and the graceful-failure
paths for Azure Maps/Vision/Foundry). Migrating now means re-doing that verification against a
library with no version-specific published docs (RC5's actual behavior was confirmed by reading
bytecode, not by reading a doc page) — real risk of a subtle behavioral difference (timeout
defaults, redirect handling, connection pooling) going unnoticed until it breaks something that
currently works. This is exactly the kind of change worth doing *deliberately*, in its own pass,
with its own round of live re-verification per integration — not bundled into unrelated feature
work.

**Recommendation:** migrate, but as a dedicated task: one integration at a time (start with
`OpenMeteoClient`, the simplest/most-called shape), re-verify each against live data before moving
to the next, and only remove `Http.scala`/`Json.scala` once every call site is off them.

## 3. Reviewed: "kyo-ai" / `kyo-llm` — not a good fit today

No package named `kyo-ai` exists on Maven Central (checked directly). The closest real thing is
**`kyo-llm`** (plus `kyo-llm-macros`, `kyo-llm-bench`) — an actual published Kyo module for LLM
integration. Checked its release history directly: **last published version is `0.9.0`, from
March 2024.** The `kyo-core` this repo pins is `1.0.0-RC5` — two major generations later, after
Kyo's own well-documented 0.x → 1.0 API redesign (the effect-suspension model itself changed
significantly across that gap, per Kyo's own release notes referenced elsewhere in this repo's
`AGENTS.md`/`build.sbt` comments).

**Recommendation: do not adopt kyo-llm.** A ~2-year-stale library built against a fundamentally
different, pre-redesign version of its own core dependency is a real risk (likely won't even
resolve/compile cleanly alongside `kyo-core:1.0.0-RC5`, and if it did, its API idioms would predate
the ones the rest of this codebase uses). The custom `llm/LlmClient` abstraction built this session
(local Ollama + Azure Foundry, both plain HTTP) is the right call for now — it's small, verified
live, and has zero dependency-compatibility risk. Revisit only if `kyo-llm` (or a genuine
`kyo-ai` successor) sees a real release against the Kyo 1.x line.

## 4. Harness ideas: evaluation and a reviewer/critic pass

Two gaps worth naming explicitly, both about *checking marola's own output quality*, not new
product features:

### 4.1 An actual evaluation harness, not just a training set

> Partly built: `just benchmark` (`ARCHITECTURE.md` §5h) is a deterministic held-out check for the
> *answering* path (RAG vs. plain prompt). The summarizer/reviewer path still has only its trainset.
> The model axis of that benchmark (closed API vs. open local vs. RAG vs. tuned, with latency and
> cost columns) is proposed as `docs/mips/MIP-0032-model-strategy-benchmark-matrix.md`.

`dspy/compile_recommendation_prompt.py`'s `TRAINSET` currently does double duty as both the
few-shot demo source *and* the only quality check (`jellyfish_and_whale_mentioned_when_relevant`,
a crude keyword-match metric — literally `"jelly" not in text.lower()`). DSPy has a dedicated
[`dspy.Evaluate`](https://dspy.ai) utility for scoring a compiled program against a **held-out**
dev set, separate from what it was optimized against — not currently used. Concretely:

- Split `TRAINSET` into a real train/dev split as it grows past its current 3 examples.
- Run `dspy.Evaluate` after every compile, and fail/warn if the score regresses — this is the
  natural place to add a CI-style check for "did the last prompt-source edit make the model worse."
- Consider upgrading the metric itself from keyword-matching to an **LLM-as-judge** — DSPy's own
  recommended pattern for metrics that keyword-matching can't capture well (e.g. "does this
  summary sound natural," "does it avoid asserting anything not in the input data"). This is a
  second, smaller DSPy signature+program, not a new library.
- Separately, the deterministic `Swimability`/future `ActivityScoring` heuristics (§1) have no
  regression harness beyond hand-written unit tests (`SwimabilitySpec`). A small "golden day"
  fixture set — real historical Open-Meteo responses for a handful of known-good/known-bad
  conditions, replayed through the scoring functions — would catch a threshold-tuning mistake
  before it ships, the same way `SwimabilitySpec` does today but against real historical data
  instead of hand-constructed `HourlyConditions` values.

### 4.2 A reviewer/critic pass on generated summaries — BUILT

This one moved from proposal to shipped code: `Main --summarize` no longer sends the summarizer's
raw output straight to a user. `llm/Reviewer.scala` replays a second DSPy-compiled signature
(`ReviewSwimSummary`, compiled alongside the summarizer in
`compile_recommendation_prompt.py`) against the draft summary, checking exactly the three things
originally proposed here (jellyfish/whale mention policy, no assertions beyond the given facts, no
overclaimed safety framing) and returning a `0-100` score, a `verdict` (`approve`/`revise`), and a
`final_summary` — the original text unchanged if approved, or the reviewer's own correction if not.
Verified live against a real local Ollama model, including one run where the reviewer's compiled
demos correctly caught a deliberately-planted flaw (a draft missing a required jellyfish mention)
and produced a corrected version — see `ARCHITECTURE.md` §5a and `dspy/review_prompt.json` (a real
compiled artifact, not a hand-written fixture).

**What's still open, not done:** the metric that scored the *compile-time* trainset
(`review_json_is_well_formed_and_sound`) only checks JSON structure and verdict match against 3
examples — nowhere near the `dspy.Evaluate`-based held-out eval loop §4.1 above describes.
Everything in §4.1 (a real dev/train split, `dspy.Evaluate`, an LLM-as-judge upgrade) applies to
*this* signature too, not just the summarizer. Treat the reviewer's existence as "the layer now
exists," not "the layer is rigorously evaluated" — those are different bars, and only the first one
is cleared today.

## 5. Reviewed: `workflows4s` (business4s) — not a fit yet, revisit if orchestration grows

[`workflows4s`](https://github.com/business4s/workflows4s) is a real, actively-developed Scala 3
library (checked directly: current version `0.6.2`) for composing long-running, stateful business
processes — approval chains, sagas, CI/CD-shaped pipelines — with event-sourcing semantics and
built-in BPMN diagram rendering. It's effect-system-agnostic (its `WorkflowContext` is pluggable;
the getting-started docs demo it with `cats-effect`, not Kyo, but nothing in its design is
cats-effect-specific).

**Why not now:** marola's actual pipeline (`Recommender.bestPerBeachTomorrow` → optionally
`summarize` → `Reviewer.review`) is a single-shot request/response, not a long-running stateful
process — there's no "state" to persist between steps, no need to survive a restart mid-flow, no
approval-style human-in-the-loop step. Reaching for a workflow orchestration library for a
three-step synchronous call chain would be solving a problem this codebase doesn't have.

**Where it could genuinely fit later:** if §1's multi-activity subscriptions grow into something
with real state machine shape — a sighting report going through a moderation/approval step before
it's trusted, a daily-digest scheduler that needs to track "have I already sent today's digest to
this user," or a `Reviewer`-triggered retry loop (draft → review → revise → re-review, bounded) —
that's the point where workflows4s' actual value proposition (typed state transitions, diagram
rendering, recoverable long-running state) starts to apply. Marked as "revisit if orchestration
complexity grows," not adopted now. `workflows4s`' own sibling project,
[`decisions4s`](https://github.com/business4s/decisions4s) (a business-rules/decision-table
engine), surfaced in the same research pass — not evaluated in depth, but worth a look if the
jellyfish/whale heuristics (`ARCHITECTURE.md` §8) ever grow past a handful of hand-coded thresholds
into something closer to a real rules table.

## 6. Reviewed: two more Scala 3 libraries

**`neotypes`** ([github.com/neotypes/neotypes](https://github.com/neotypes/neotypes)) — a real,
actively-maintained (last updated September 2025), type-safe, effect-agnostic Scala driver for
**Neo4j**. Not a fit: marola has no graph data model anywhere — no relationships-between-entities
problem that a graph database is the right tool for. If §1's multi-activity subscriptions or a
future "beaches near beaches" / social feature ever genuinely needs graph queries, Cosmos DB
(already an adopted dependency, §5d) has its own Gremlin/graph API — evaluate that first before
adding a whole second database technology.

**`Iron`** ([github.com/Iltotore/iron](https://github.com/Iltotore/iron)) — actively maintained
(current major version `3.x`), Scala 3 refined types: attach compile-time-or-runtime-checked
constraints to a type (`Double :| Interval.Closed[0, 100]`, `Double :| Positive`, ...) rather than
validating with plain runtime code. **A real, concrete fit**, and directly related to
`EFFECTS-MAP.md`'s own findings: `Swimability.score`'s `0-100` range is currently a runtime
`.max(0).min(100)` clamp with nothing stopping some other code path from constructing a `BestHour`
with an out-of-range score; `Coordinates(lat, lon)` accepts any `Double` today, not just valid
latitude/longitude ranges. Iron would make both illegal states unrepresentable at the type level
instead of relying on every caller remembering to clamp/validate. Not adopted here — a genuine
"worth doing," scoped small enough (a handful of type aliases in `model/Models.scala`, no
architecture change) that it's a reasonable first Scala-3-ergonomics task for whoever picks this
file up next, well before the larger `kyo-http`/`kyo-schema` migration (§2).

## 7. Splitting marola out of the ai-103 monorepo — superseded, see below

### 7.1 Status: superseded by a simpler outcome

This section originally described preparing marola to be `git subtree split` out of a shared
`ai-103` monorepo that also contained an unrelated project (nf-organizer, a nota-fiscal/expense
organizer). That plan involved marola/ carrying self-contained copies of every infra file a
standalone repo would need (`.ai-jail`, `.gitignore`, `.githooks/pre-commit`, `.scalafmt.conf`,
`flake.nix`, `justfile`, `project/`, and a `build.sbt.standalone` — a complete, working
single-project build kept under a non-`build.sbt` name specifically because sbt auto-merges a
per-subproject `build.sbt` into the enclosing multi-project build's settings, confirmed the hard
way when a file literally named `marola/build.sbt` got loaded twice by a root `sbt compile`).

**What actually happened instead, once the decision was made to make this repo marola's own repo
rather than extract marola from it:** nf-organizer's content was moved to an external backup
(outside this repo, not deleted) and removed here entirely; marola's four module directories
(`core/local/azure/cli`) and `dspy/` were hoisted from `marola/core|local|azure|cli|dspy` up to the
repo root; all the duplicate infra files listed above were deleted (root's copies already cover the
whole repo now — nothing else needs them); and `build.sbt` was simplified to one root aggregate
with no `nfOrganizer` project and no `marola` umbrella project — `core`, `local`, `azure`, `cli` are
now aggregated directly under root. No `git subtree split`, no history rewrite, no
`RootProject`/`.standalone`-file workaround needed — the entire reason for those was the
monorepo-with-two-projects shape, which no longer exists.

The `RootProject` investigation below is kept for its own sake — it's a real, independently useful
finding about sbt — but it no longer describes a decision this repo needs to make.

**Considered and rejected at the time: sbt's `RootProject`** as a cleaner alternative to the
`.standalone` naming workaround — actually tested live in a throwaway sandbox, not just read about.
`RootProject(file("marola"))` + `.aggregate()` genuinely does avoid the settings-merge problem
(confirmed: the referenced build stays fully independent, its own `build.sbt` can keep its ordinary
name, its own `scalaVersion`/settings never leak into or from the parent). But it has a real,
confirmed cost: a `RootProject`-referenced build is **not addressable via `sbt "name/task"` scoped
syntax** from the parent's session — `sbt "subBuild/compile"`, `sbt "sub/compile"`, and even
`project subBuild` all failed with "Not a valid project ID" in the test. The only way to run a task
in it is a separate `cd <dir> && sbt <task>` invocation — a real limitation to know about if a
similar monorepo-split situation comes up again elsewhere.

### 7.2 CI

`.github/workflows/ci.yml` now runs one job (`build-test`) doing unscoped `sbt scalafmtCheckAll` /
`sbt compile` / `sbt test` at the repo root — `.aggregate()` cascades these to all four modules
(`core`/`local`/`azure`/`cli`) by default, confirmed directly. (An earlier version of this repo,
back when it also contained nf-organizer, split this into two per-module jobs for clearer CI
reporting; with only one project in the repo now, that split no longer serves a purpose.)

**`.github/workflows/marola-e2e.yml`** — a separate, actually-new workflow: `E2ESpec`'s two tests, on
`workflow_dispatch` only (manual trigger from the Actions tab or `gh workflow run`), never on
push/PR. Installs Ollama via its official install script and pulls `llama3.2:1b` (1.3GB, the same
model `RUN-LOCALLY.md` recommends) so the run is genuinely free — no paid API, just CI minutes.
The YAML was validated (parses correctly, the install script URL resolves to Ollama's real GitHub
release asset) but **the workflow itself has not been run through an actual GitHub Actions
execution** — that requires pushing it and triggering it for real, which wasn't done here.

### 7.3 Splitting marola *itself* into multiple sbt modules — DONE

Executed. Root `build.sbt` defines four subprojects — sbt project IDs `core`, `local`, `azure`,
`cli` (artifact names `marola-core`/`marola-local`/`marola-azure`/`marola-cli`), aggregated
directly under the root project.

(Originally these lived nested one level down, under `marola/core|local|azure|cli/`, from when this
repo was a shared monorepo with a second, unrelated project. Once this repo became marola's own
repo, that nesting no longer served a purpose, so the four module directories — plus `dspy/` and
all the `.md` docs, now centralized under `docs/` — were hoisted up to the repo root. Any older text
below referencing `marola/core`-style paths or `core`-style sbt project IDs predates that
hoist; the directories are `core/`, `local/`, `azure/`, `cli/` and the sbt IDs are `core`, `local`,
`azure`, `cli` now.)

| Module | Contains | Depends on |
|---|---|---|
| `marola-core` | `model/`, `scoring/`, `beaches/BeachFinder`, `conditions/OpenMeteoClient`, `Recommender`, `http/`, `json/`, `llm/LlmClient` (trait + `CompiledPrompt` + `Reviewer`), `sightings/{SightingStore,Sighting}`, `vision/VisionClient` (trait) | nothing else in marola |
| `marola-local` | `llm/LocalLlmClient`, `vision/LocalVisionClient`, `sightings/LocalFileSightingStore` | `marola-core` — **zero Azure SDK dependency, confirmed**: `local`'s `libraryDependencies` in `build.sbt` adds nothing beyond `baseSettings` |
| `marola-azure` | `llm/AzureFoundryLlmClient`, `vision/AzureVisionClient`, `beaches/RouteFinder`, `sightings/CosmosDbSightingStore`, `observability/Telemetry` | `marola-core` |
| `marola-cli` | `Main`, `AppConfig`, `agent/SwimConditionsMcpServer` | `marola-core`, `marola-local`, `marola-azure` |

(`marola-bot`, the originally-proposed fifth module for the Telegram polling loop, wasn't created
since that code still doesn't exist — Phase 1 is still not built. Add it when that lands.)

**A real design problem surfaced immediately and needed a fix, not just a file move:**
`Recommender` (core) originally called `RouteFinder.travelDistanceKm` (azure) directly — a genuine
circular dependency once physically separated, not just an import-organization nuisance (`sbt
compile` failed with `Not Found Error: value RouteFinder is not a member of marola.beaches`).
Fixed via dependency inversion: `Recommender.bestPerBeachTomorrow`/`bestHoursTomorrow` now take a
`distanceRefiner: Option[(Coordinates, Coordinates) => Double < Sync]` parameter instead of an
`azureMapsKey: Option[String]` — `marola-core` never references Azure Maps at all anymore.
`AppConfig.distanceRefiner` (in `marola-cli`, which depends on both `core` and `azure`) is the one
place that closes over `RouteFinder` to build the actual function. This is a better design than
the original hardcoded-call version, not just a workaround forced by the module split — the
local-vs-Azure choice for distance refinement is now visible in `Recommender`'s own signature
instead of hidden behind a string key's presence/absence.

**Verified, not just compiled:** full `sbt compile`/`sbt test` (every unit test passed across the new
module boundaries), `sbt cli/run` and `sbt cli/run -- --summarize` against live
Overpass/Open-Meteo/Ollama (including the Reviewer pass), both `E2ESpec` tests, and
`sbt cli/assembly` producing a working fat jar (`java -jar
marola-cli-assembly-*.jar` runs correctly) — all after the split, not just before it.

**Not done as part of this:** `AppConfig` still eagerly references all three backends'
client-construction code in one file (`llmClient`/`sightingStore`/`visionClient`/`distanceRefiner`
factory methods) — the module *boundary* is real (local truly can't see azure-identity/etc.
at compile time), but `AppConfig` itself, living in `cli`, still has to know about all three
to wire up. That's inherent to having one config type pick a backend per integration; splitting
`AppConfig` itself further wasn't attempted and isn't obviously worth doing.

## 8. Smaller items already flagged elsewhere

Not repeated in full here — see the cross-referenced section:

- Calibrating the jellyfish/whale heuristics against real `SightingStore` data — `ARCHITECTURE.md`
  §8.
- Real per-beach travel time/distance beyond Azure Maps' driving-distance API (walking/transit
  modes) — `ARCHITECTURE.md` §5b, §9.
- Caching and per-user rate limiting for the core pipeline — `ARCHITECTURE.md` §9, §11 Phase 4.

## 9. Ocean-knowledge grounding: RAG and fine-tuning over marine science, plus catastrophe detection

Two connected product ideas, both aimed at the same theme: marola currently only reasons over live
*sensor* data (Overpass, Open-Meteo). It has no grounding in the *body of knowledge* about the
ocean — marine biology, oceanography, coastal-hazard research — and no concept of an
out-of-distribution event. Both ideas below close real gaps identified in `AI-103-MAPPING.md`
(RAG/fine-tuning, first-class text analysis) with one coherent feature rather than two disconnected
checkbox exercises.

### 9.1 "marola knows the ocean": RAG and/or fine-tuning over marine literature

> **Built (first cut):** `docs/mips/MIP-0001-water-quality-and-sea-lore.md` — local RAG over
> `knowledge/` with citations (`ARCHITECTURE.md` §5h), the sourced sea-lore paragraph, and a
> fine-tuning scaffold under `finetune/` (Tier 1 built, Tier 2 written-not-run). Steps 1-3 below
> are now real; step 4 (fine-tuning) has its recipe but no evaluation yet.

**The pitch:** today, if a user asks "what should I do if I get stung by a jellyfish here," marola
has nothing — it's not a question `Recommender`'s pipeline answers at all. A grounded knowledge base
turns marola from "a conditions calculator with a sentence generator on top" into something that can
actually answer open marine-safety/marine-biology questions, sourced, not hallucinated.

**Proposed shape (RAG first, fine-tuning as a later refinement — not the reverse):**

1. **Corpus.** Public, freely licensed sources: NOAA/oceanographic-agency public bulletins, open-
   access marine biology papers (PLOS ONE, open preprints), Wikipedia-tier species/hazard pages as a
   floor, and — since jellyfish/rip-current/species specifics are regionally different — locally
   relevant coastal-hazard guidance for wherever marola is actually deployed. Licensing has to be
   checked per source before ingesting anything; this is a real constraint, not a footnote.
2. **Retrieval.** A small local embedding model (Ollama serves embedding models too, e.g.
   `nomic-embed-text` — keeps the local-first, zero-Azure-account property this whole repo is built
   around) over chunked documents, with **Azure AI Search** as the opt-in Azure-native alternative
   (same local-default/Azure-opt-in pattern as every other integration in `ARCHITECTURE.md` §5) —
   this is also a clean way to close the "Azure AI Language / text analysis" gap flagged in
   `AI-103-MAPPING.md` §5, since ingesting the corpus is itself a text-analysis/extraction task
   (pulling structured hazard facts out of prose bulletins).
3. **New module shape:** a `core/knowledge/` package (`KnowledgeStore` trait, mirroring the existing
   `SightingStore`/`VisionClient` local-vs-Azure trait pattern) plus a new agent role — a
   "marine-knowledge agent" distinct from the summarizer/reviewer pair, callable as its own MCP tool
   (`ask_ocean_question`) so it's usable independently of the swim-hour pipeline, not bolted onto it.
4. **Fine-tuning** is a genuine later step, not a prerequisite: only worth it once RAG's retrieval
   quality on real user questions is measured and found wanting for something a fine-tune would
   actually fix (tone, domain vocabulary, a very narrow structured-output format) — fine-tuning a
   small open model (a `llama3.2` variant, via Ollama's `Modelfile` + a QLoRA-style adapter) on that
   specific gap is far cheaper and more honest than fine-tuning as a first move.

This is squarely AI-103's generative-AI-solutions domain (RAG, done for real) and closes the NLP
domain's text-analysis gap in the same feature — see `AI-103-MAPPING.md` for the exact mapping.

### 9.2 A fourth agent: catastrophe/hazard detection, competing with public alerts

**The pitch, stated directly since it's the more consequential idea here:** the same live conditions
data marola already fetches (wave height, wind, current, tide/weather trends over a time range, not
just tomorrow) is exactly the input a rip-current, storm-surge, or dangerous-sea-state detector would
need. Framed honestly: this is *not* about replacing official government emergency systems (Civil
Defense/meteorological-agency alerts) — it's about being a **faster, hyper-local, opt-in companion
channel** for people who live near the sea, alongside those official channels, not instead of them.

**Why this is a genuinely different agent, not a variant of the existing summarizer:** the
summarizer answers "what's good," a single-hour, single-beach, best-case question. This agent has to
answer "is something bad happening or about to happen, across a time range, and is it bad enough to
interrupt someone unprompted" — a monitoring/anomaly-detection question, not a recommendation
question. It needs:

- **A trend/anomaly view over the existing time-series data** (`OpenMeteoClient` already fetches
  hourly data — this needs looking at the *shape* of the next N hours, not just picking the best
  one), which is new logic, not a reuse of `Swimability.score`.
- **A conservative, false-positive-averse threshold design** — see `AI-500-MAPPING.md` §4: this is
  the first place marola would act *without being asked* (a proactive Telegram push), which is a
  materially different risk/trust profile than answering a query, and needs its own human-
  confirmation gate on the alerting behavior itself before it ever ships, not just on Azure spend.
- **Explicit, honest scoping against official sources** — cross-referencing (not replacing) whatever
  official public alert feed is available for the deployment region, and being clear in the product
  copy itself that this is a supplementary heads-up, never the authoritative source, particularly
  given the "competing with governance public announcements" framing carries real liability/trust
  implications if done carelessly.

**Where this lands architecturally:** this is the concrete third agent proposed in
`AI-500-MAPPING.md` §1 (summarize / critique / escalate) — the escalation agent *is* this hazard
detector. Building it is simultaneously a real safety feature, AI-103's generative-AI/agentic
coverage, and the clearest path to a genuine AI-500-shaped multi-agent architecture (three agents,
three distinct roles, one of them with a different orchestration trigger — a schedule/poll, not a
user query).

## 10. Scala/JVM gap in the prompt-engineering and LLMOps ecosystem — and `ds4s`

Surveyed (web search, September 2026) what the Python LLMOps/prompt-engineering ecosystem has that
Scala/the JVM doesn't, specifically because marola already leans on one of these tools (DSPy) via a
Python subprocess step rather than natively, and it's worth being explicit about why, and what
closing that gap would take.

**Python-only tools with no confirmed Scala/JVM equivalent today:**

- **[DSPy](https://dspy.ai)** — declarative LLM programming + optimizers (`BootstrapFewShot`,
  `MIPROv2`). This repo already depends on it (`dspy/`). No JVM port exists — confirmed by search,
  not just absence of prior knowledge.
- **[Langfuse](https://github.com/langfuse/langfuse)** — open-source LLM tracing/eval/prompt-
  management platform. Ships Python and TypeScript SDKs; no JVM/Scala SDK. `Telemetry.scala`
  (`ARCHITECTURE.md` §5f) covers general OpenTelemetry tracing but nothing LLM-call-shaped
  (prompt/completion pairs, token/cost tracking, eval scores attached to a trace).
- **[Promptfoo](https://github.com/promptfoo/promptfoo)** — prompt/model red-teaming and comparison,
  CLI + YAML config, Node-based; no Scala equivalent.
- **[DeepEval](https://github.com/confident-ai/deepeval)** / **[RAGAS](https://github.com/explodinggradients/ragas)**
  — pytest-native LLM/RAG quality metrics. Nothing pytest-shaped exists for munit because the
  underlying metric libraries (embedding-similarity judges, RAG-specific metrics) are Python-only
  dependencies themselves.

**What this means for marola specifically:** the DSPy compile step will likely stay a Python
subprocess indefinitely — porting DSPy itself is a large undertaking, not a marola-sized task (see
below). The more immediately actionable gap is Langfuse-shaped tracing, since `Telemetry.scala`
already has the OpenTelemetry plumbing in place; adding structured LLM-call spans (prompt,
completion, model, latency, token count) to the existing `AzureFoundryLlmClient`/`LocalLlmClient`
call sites is a scoped, real improvement that doesn't require adopting a whole new platform.

**The larger idea, named and scoped honestly as a separate future project, not a marola subtask:
`ds4s` ("DSPy for Scala").** A from-scratch Scala port of DSPy's core ideas — `Signature`
(input/output field declarations with types and descriptions), a `Predict`-equivalent module, and at
least one optimizer (`BootstrapFewShot` is the simpler target; `MIPROv2`'s Bayesian search is a much
later stretch goal) — built on Kyo for the effect boundary (LLM calls are `< Sync`/`< Async`,
consistent with everything else in this repo) and `kyo-schema`/Iron for typed signature fields
instead of DSPy's dynamic Python typing. This is explicitly **not scoped for this repo to build as
part of marola** — it's a separate library-shaped project, large enough to be its own repo, that
marola would become a *consumer* of once it existed (replacing `dspy/compile_recommendation_prompt.py`
with a Scala equivalent and eliminating the Python subprocess step entirely). Flagging it here as the
concrete, named future-work item it deserves to be, rather than leaving "someone should port DSPy to
Scala" as an unrecorded aside.

**Update (2026-09-05):** the "Langfuse-shaped tracing" half of this section is proposed as
`docs/mips/MIP-0010-mlflow-experiment-tracking.md` — MLflow's server ingests OpenTelemetry traces
over OTLP/HTTP from any language, so the JVM side needs no LLMOps SDK; `ds4s` stays a separate
project by its own definition above. `docs/README.md` classifies every section of this file.

**Update (2026-09-05, continued):** `MIP-0010.tasks.md` tasks 5-6 (tracing core split,
`local/MlflowTracing.scala` + `TracedLlmClient`) are the JVM half that actually closes this
gap — planned/in progress as of this note, not confirmed merged. `dspy/compile_recommendation_prompt.py`
already logs its own compile runs to MLflow (task 7, the Python-only half, independent of tasks
5-6) — see `dspy/README.md`'s "Optional: logging compile runs to MLflow" section.

**Update (2026-09-05, later):** the "DSPy stays a Python subprocess indefinitely" conclusion is
revisited by `docs/mips/MIP-0012-llm4s-adoption-and-dspy-deprecation.md` — marola's actual use of
DSPy (a three-example `BootstrapFewShot` with a deterministic metric) is small enough to own as a
Scala step in `core/prompt/` over the existing `LlmClient`, with the held-out eval §4.1 asks for;
`ds4s` as a *general library* remains a non-marola idea. The same MIP checks llm4s against its jar
(agent loop, MCP client/server, guardrails, structured output — and no prompt optimiser).

## 11. Personal history input: Garmin data (FIT-file import first, not a live API integration)

**The pitch:** weight recommendations by a user's own swim history — "you've swum at Praia do
Diabo 12 times, always in the morning" is a real personalization signal none of marola's current
data sources (Overpass, Open-Meteo) can provide. Garmin devices/watches are a natural source for
swimmers specifically (many track open-water swim activities: GPS track, duration, pace, heart
rate). Raised as an idea, checked for feasibility, not built.

**Why this is an access-complexity problem, not an engineering one.** Garmin has no self-serve
public API comparable to Overpass/Open-Meteo:

- **Garmin Health API** (the official route) is B2B-only — requires applying to and being approved
  for Garmin's Connect Developer Program, with a business justification. Disproportionate for a
  personal project, and a real dependency on a third party's approval process that could just say
  no.
- **Unofficial clients** (e.g. `python-garminconnect`-style libraries that call Garmin Connect's
  undocumented mobile-app endpoints) work today but carry real ToS/breakage risk — this is exactly
  the class of "reverse-engineered workaround" this repo has deliberately avoided everywhere else
  (Overpass and Open-Meteo were chosen specifically because they're free *and* documented, not
  because free-but-fragile was acceptable).
- **FIT/TCX file export** — Garmin Connect lets a user export their own activity data as a
  standard file format (FIT is Garmin's own binary format; TCX is XML and easier to start with).
  This needs zero auth, zero partnership, zero ToS exposure: the user exports their own file and
  hands it to marola directly.

**Recommended shape, if this gets built: start with (c), never (b).**

1. A `core/history/` package (mirroring the existing local-vs-Azure trait pattern where it makes
   sense) with a `SwimHistoryStore` reading manually-imported activity records — beach name (or
   nearest-match by GPS coordinates against `BeachFinder`'s results), timestamp, duration.
2. A small **TCX parser first** (XML, human-readable, easier to hand-write correctly than FIT's
   binary format — same "small hand-rolled parser for one specific external shape" pattern as
   `Json.scala`, and this time it should ship with real unit tests from day one, unlike `Json.scala`
   — see `SKILLS.md` Stage 6's test-coverage finding). FIT support is a reasonable later addition
   once TCX proves the shape is useful.
3. Feed accumulated history into `Recommender`/`Swimability` as a *tie-breaker or personalization
   note*, not a scoring input that could contradict live conditions data — "you usually swim here
   Sunday mornings" is context to surface alongside the numbers, not a reason to override a genuine
   safety-relevant heuristic (jellyfish/rough-seas deductions stay authoritative).
4. Revisit the official Health API or an unofficial client only if manual import proves the feature
   is actually worth the friction — cheap validation before an expensive/risky integration decision,
   same reasoning `ARCHITECTURE.md` §11's phase discipline already applies elsewhere in this repo.
