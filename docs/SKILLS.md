# marola — skills roadmap (AI-103 → AI-500)

`AI-103-MAPPING.md` and `AI-500-MAPPING.md` map marola's **features** to exam **domains**. This
doc is the complementary view: a roadmap of **skills to actually practice**, in order, using this
repo as the vehicle — the thing to work through if the goal is "get good enough at this to pass
AI-103, then AI-500," not just "check which boxes marola happens to tick."

Each item names the concrete marola artifact that exercises it and whether it's practice-ready
today or needs something built first. Work top-to-bottom within a section; sections are ordered
AI-103 first (it's the prerequisite), then AI-500.

## Stage 1 — Azure AI solution planning (AI-103 domain 1)

| Skill | Practice it via | Ready now? |
|---|---|---|
| Choosing a model/service tier for a cost-sensitive scenario | Compare `local/llm/LocalLlmClient` (free) vs. `azure/llm/AzureFoundryLlmClient` (paid) for the same `LlmClient` trait — read `ARCHITECTURE.md` §5a's tradeoff writeup, then articulate it yourself before reading it | Yes |
| Managed identity end-to-end, no keys | `AzureFoundryLlmClient` is the one client on `DefaultAzureCredential`; the exercise is to migrate `CosmosDbSightingStore`/`AzureVisionClient`/`RouteFinder` to it (FABLE_REVIEW D1) | Yes — as a task, not a reading |
| Reasoning about scale-to-zero cost | `ARCHITECTURE.md` §6, §11 Phase 3 (Container App deployment, not yet done) | Design-only until Phase 3 |
| Responsible AI: stating limitations honestly | `ARCHITECTURE.md` §8/§9 — write your own one-paragraph "known limitations" section for a feature you add, in that style, before calling it done | Yes |

## Stage 2 — Generative AI implementation (AI-103 domain 2)

| Skill | Practice it via | Ready now? |
|---|---|---|
| Calling a model through an OpenAI-compatible endpoint | `LocalLlmClient.complete` (Ollama's `/v1/chat/completions`) | Yes |
| Calling a model through Azure AI Foundry's SDK | `AzureFoundryLlmClient` | Yes (code), needs a real Foundry deployment to run live |
| Structured/parsed output from a model | `CompiledPrompt.buildMessages` + `LlmClient.extractContent` (summary field), `Reviewer.extractJsonObject` (JSON-from-prose fallback) | Yes |
| Systematic prompt optimization (not hand-tuning) | `dspy/compile_recommendation_prompt.py` — run it yourself against `llama3.2:1b`, inspect the compiled `recommendation_prompt.json`, then hand-edit the trainset and re-run to see the artifact change | Yes — see `RUN-LOCALLY.md` |
| RAG | Not built. First real exercise: implement `core/knowledge/KnowledgeStore` per `FUTURE-WORK.md` §9.1 against a small local corpus | Design only — build it to practice this |
| Fine-tuning a small open model | `FUTURE-WORK.md` §9.1 step 4 (Ollama `Modelfile` + QLoRA-style adapter) | Design only |

## Stage 3 — Agentic solutions (AI-103 domain 3)

| Skill | Practice it via | Ready now? |
|---|---|---|
| Exposing app logic as MCP tools | `cli/agent/SwimConditionsMcpServer.scala` — read it, then add a new tool (e.g. `ask_ocean_question` once §9.1 exists) yourself | Yes |
| Testing an MCP server without a full agent client | Pipe raw JSON-RPC to the server's stdin yourself (`ARCHITECTURE.md` §5c's Status note describes how this was verified) — do this once by hand before trusting any higher-level client | Yes |
| Multi-step agent pipelines (plan → act → critique) | `Recommender.bestPerBeachTomorrow` → `Reviewer.review` — trace one real request through both LLM calls end to end with `just run -- --summarize` | Yes |
| Recognizing when orchestration frameworks are and aren't worth adopting | `FUTURE-WORK.md` §5 (`workflows4s` review) — do your own version of this exercise on a framework not yet reviewed here before adding one | Yes, as a practice exercise |

## Stage 4 — Computer vision (AI-103 domain 4)

| Skill | Practice it via | Ready now? |
|---|---|---|
| Local multimodal model calls | `local/vision/LocalVisionClient` via `just run -- --analyze-photo <path>` | Yes |
| Azure AI Vision SDK calls | `azure/vision/AzureVisionClient` | Yes (code), needs a live Azure resource to run |
| Closing the loop: vision output feeding a decision | Not built — `SightingStore` records vision-analyzed sightings but nothing yet feeds them back into `Swimability`'s heuristics (`ARCHITECTURE.md` §8). Build the calibration step to practice this | Design only |

## Stage 5 — NLP / text analysis (AI-103 domain 5)

| Skill | Practice it via | Ready now? |
|---|---|---|
| Structured extraction from unstructured model output | `Reviewer.extractJsonObject` | Yes |
| A first-class text-analysis use case (not just a parsing fallback) | Not built — the marine-literature ingestion step in `FUTURE-WORK.md` §9.1 (step 2: pulling structured hazard facts out of prose bulletins) is the concrete gap-closer named in `AI-103-MAPPING.md` §5 | Design only — build it to close this gap for real |

## Stage 6 — Scala/engineering craft (implementation quality, not a named exam domain, but exercised on every task above)

marola's own code was reviewed for this directly (September 2026) — treat these as the concrete
skill gaps to close by practicing on this codebase, not abstract advice:

- **Typed error channels over `throw`+catch-all.** Every I/O boundary (`Http.scala`, `Json.scala`,
  `Reviewer.scala`, vision/route clients) defines a real exception type but throws it and catches
  it as bare `Throwable` via `Abort.catching[Throwable]` at the call site — which discards the type
  information Kyo's `Abort[E]` effect exists to track. Practice: pick one client, change its
  signature to `... < (Sync & Abort[HttpError])`, and thread the typed error through instead.
- **Kyo's own bulk-effect combinators over hand-rolled recursion.** `Recommender.traverse`/
  `traverseSingle` reimplement what `kyo.Async.foreach`/`collectAll` already provide (confirmed
  present in the exact pinned `kyo-core`/`kyo-combinators` 1.0.0-RC5 jars via `javap`) — kept
  hand-rolled specifically because only `map`/`flatMap` on `< Sync` were confirmed at the time.
  Practice: verify live whether `Async.foreach` works over `< Sync` callers (does the effect type
  widen to `Sync & Abort[...]` correctly?) and replace the hand-rolled version if so — a real,
  scoped verification exercise, not a guess.
- **Property-based tests where they're an obvious fit.** `Swimability.score`'s 0-100 clamped range
  and monotonic threshold behavior is a textbook ScalaCheck property (`score` is never outside
  [0,100]; a strictly worse wave height never increases the score) — none exist yet; only
  example-based tests do (`SwimabilitySpec`).
- **Test coverage is thin outside the one pure module.** ~1900 lines of main source, ~280 lines of
  test source, and exactly one deterministic unit-test file (`SwimabilitySpec`) — the hand-rolled
  JSON parser (`Json.scala`, escape sequences and all), `Recommender`'s grouping/sorting, and
  `AppConfig.fromEnv`'s parsing have zero unit tests today. Practice: write `JsonSpec` first — it's
  the highest-value, lowest-effort gap (pure function, pure input/output, no mocking needed).
- **Scalafix, not just scalafmt.** Formatting is enforced (`scalafmtCheckAll` in CI); nothing
  enforces the code's own unwritten conventions (no stray `var`, no bare `throw` outside an
  `Abort.catching` boundary, no unused imports) mechanically. Practice: add `scalafix` with
  `DisableSyntax` rules for exactly the conventions this repo already tries to follow by hand.

**What's already solid, worth recognizing rather than only listing gaps:** pinned exact dependency
versions with reproducible Nix builds; a real, documented pattern of verifying library claims
against decompiled jars/live calls instead of trusting docs (`FUTURE-WORK.md` throughout,
`EFFECTS-MAP.md`); strict compiler flags most Scala 3 codebases skip (`-Wvalue-discard`,
`-Wnonunit-statement`, `-language:strictEquality`, promoted to errors); a genuinely clean
pure-core/effectful-shell split (`EFFECTS-MAP.md`); deliberate, reasoned dependency minimalism
(multiple libraries reviewed and explicitly not adopted, with the reasoning kept, not just silently
skipped).

## Stage 7 — Multi-agent architecture (AI-500 domain 1: architect)

| Skill | Practice it via | Ready now? |
|---|---|---|
| Recognizing an implicit multi-agent system inside a "simple" pipeline | Name the two roles already in `Recommender`/`Reviewer` as agents before building anything new — this recognition step is the actual AI-500 skill, not just adding code | Yes |
| Designing a third agent with a genuinely different role, not a variant of the first two | `FUTURE-WORK.md` §9.2 — the escalation/hazard-detection agent | Design only |
| Naming and documenting an orchestration topology | `AI-500-MAPPING.md` §1 — write the topology doc *before* building the third agent, not after | Yes, as a practice exercise |

## Stage 8 — Multi-agent development on Azure (AI-500 domain 2, the largest)

| Skill | Practice it via | Ready now? |
|---|---|---|
| MCP as an agent-to-agent capability-exposure mechanism, not just a chat-tool bridge | Extend `SwimConditionsMcpServer` with one tool per agent role (`AI-500-MAPPING.md` §2) | Design only |
| Azure AI Foundry Agent Service's actual multi-agent orchestration (not single-turn calls) | `azure/llm/AzureFoundryLlmClient` already depends on `azure-ai-agents` but only uses single-turn structured output — register real Foundry Agents instead | Needs a live Foundry account |
| Shared state/memory across agents | Extend `SightingStore`'s local-vs-Azure pattern to a `UserPreferencesStore`/conversation-state store (`FUTURE-WORK.md` §1.5) | Design only |

## Stage 9 — Evaluating and monitoring multi-agent systems (AI-500 domain 3)

| Skill | Practice it via | Ready now? |
|---|---|---|
| An agent evaluating another agent (generator/critic) | `Reviewer.review` — already built; study it as the AI-500-relevant pattern it is, not just a marola feature | Yes |
| A held-out eval set, not just a training set doing double duty | `FUTURE-WORK.md` §4.1's gap — build a real `dspy.Evaluate` loop over held-out examples | Design only |
| LLM-call-shaped tracing (prompt/completion/cost/eval-score per span), not just infra tracing | `FUTURE-WORK.md` §10 flags this as a real JVM/Scala tooling gap (no Langfuse-equivalent) — extend `Telemetry.scala`'s existing OpenTelemetry plumbing yourself | Design only, scoped and doable |

## Stage 10 — Securing, governing, and deploying multi-agent systems (AI-500 domain 4)

| Skill | Practice it via | Ready now? |
|---|---|---|
| A human-confirmation gate on autonomous (not just responsive) agent behavior | `AGENTS.md`'s existing cost-safety gate is the template — design the equivalent for the escalation agent's proactive alerts before building it (`AI-500-MAPPING.md` §4) | Design only |
| Content-safety review on agent output meant for the public | Same escalation agent — its alert text is the first marola output that isn't only shown to the person who asked for it | Design only |
| Audit trails across agent boundaries | Extend `Telemetry.scala` rather than building a new system | Design only |
