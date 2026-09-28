# MIP-0012: llm4s as marola's Scala-native LLM/agent layer — and the deprecation of the Python DSPy step

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Fable 5.1, for M. Hoffmann (request of 5 Sep 2026: "implement llm4s in this repo, and deprecate DSPy — comprehensive, all llm4s capabilities, all marola ambitions, see PHILOSOPHY.md") |
| **Created** | 2026-09-05 |
| **Phase** | 0 for the Scala prompt compiler, the DSPy removal and the `llm4s/` module behind marola's own traits (developer tooling; the swimmer sees a re-compiled prompt, nothing else). The agent loop waits on Phase 1 (MIP-0002, still Draft); the HTTP MCP server for a hosted agent is Phase 2/3 and behind the cost gate |
| **Related** | `PHILOSOPHY.md` ("Why Scala 3 on the JVM" — the Python paragraph this MIP acts on), `FUTURE-WORK.md` §10 (`ds4s`, "DSPy stays a Python subprocess indefinitely" — revisited here) and §4.1 (held-out eval), `AGENT-FRAMEWORKS-SURVEY.md` §1.2 (llm4s row), `ARCHITECTURE.md` §5a (the DSPy step, `CompiledPrompt`) and §5c ("HTTP/SSE MCP transport — not built"), MIP-0010 (ledger + traces this step should write to; its task 7 becomes moot), MIP-0008 (native image — dependency weight), MIP-0002 (the first real agent surface) |
| **Effort** | XL — a new module, a from-scratch Scala prompt compiler replacing DSPy, a dependency-boundary CI check, an agent/MCP layer gated on Phase 1 |
| **Gain** | infra/dev-loop (removes the Python/DSPy toolchain and its training/serving skew) |
| **Effort vs Gain** | do when X lands — tasks 1-3 (the prompt compiler) are dependency-free and could go now; tasks 4-7 (the agent) wait on MIP-0002 |
| **Depends on** | MIP-0010 (the ledger the compiler logs to); MIP-0002/Phase 1 (the agent tasks); no cloud resource |
| **Risk** | llm4s is pre-1.0 with 154 transitive jars and one coordinate rename already — real churn for what would otherwise be a 30-line adapter |
| **Cost so far** | n/a — the merged commit's own `Cost:` line was left as an unfilled placeholder ("~unmeasured in-agent · fill from `just claude-cost` before PR") |

## 1. Summary

Two changes, deliberately separable. **First**, the offline DSPy step (`dspy/`, Python) is replaced by
a marola-owned Scala compiler in `core/prompt/`: the same metric-gated few-shot bootstrap marola
actually uses today (three examples, `max_bootstrapped_demos=3`, a deterministic metric), writing the
same JSON artifact `CompiledPrompt` already loads, so the prompt is compiled and replayed through
one code path, and the gate PHILOSOPHY.md wants ("there by construction") covers the last piece of
marola that only had it by discipline. This part needs **no new dependency**: it runs over marola's
existing `LlmClient`. **Second**, [llm4s](https://github.com/llm4s/llm4s) (`org.llm4s:llm4s-core`
0.4.1, MIT, Scala 3, verified consumable from marola's 3.9.0 build, one live completion against
the local Ollama) is adopted as an **opt-in fifth module `llm4s/`** behind marola's own traits, for
what marola lacks and llm4s has: an agent loop with tools, handoffs and guardrails,
structured output, an MCP *client*, and an HTTP/Streamable-HTTP MCP *server* (the §5c gap). It
cannot live in `local/`: `llm4s-core` drags 154 jars transitively (§4.1), and `local/`'s small
dependency set is load-bearing.

## 2. Motivation

- **PHILOSOPHY.md's own argument, applied to the one place it isn't.** "Marola's offline steps
  (`dspy/`, `finetune/`) are Python, chosen because the libraries only exist there." For `dspy/`
  that premise is now too strong: what marola uses of DSPy is `BootstrapFewShot` over three
  hand-written examples with a keyword metric (`compile_recommendation_prompt.py`). That is ~150
  lines of Scala, not a DSPy port. `finetune/` (peft/trl, a GPU) keeps the argument; `dspy/` no
  longer does.
- **Training/serving skew, admitted in the code.** `CompiledPrompt` is "a good-faith replication of
  DSPy's own `ChatAdapter` format … not a byte-identical replay" (`ARCHITECTURE.md` §5a). A Scala
  compiler that bootstraps demos through `CompiledPrompt.buildMessages` itself removes the skew by
  construction, not by care.
- **Two toolchains for one artifact.** A venv, `dspy>=3.3,<4`, `langfuse`, an `LD_LIBRARY_PATH`
  workaround for `tokenizers` (`dspy/README.md`), a Python lint lane in `ci.yml`, and MIP-0010 task
  7 planning a *second* logging client just for this script. The JVM side already has the client,
  the ledger (`RunLedger`, MIP-0010 tasks 1-2) and the tests.
- **`FUTURE-WORK.md` §4.1 never got built**: "run `dspy.Evaluate` after every compile" was the plan,
  but it lived on the Python side nobody ran. In Scala a held-out `Evaluate` is one function next to
  the compiler and one row in the ledger.
- **The agent ambitions have no substrate.** marola wants summarize / critique / escalate as
  addressable agents with a named topology; `ARCHITECTURE.md` §5c says a hosted agent needs an
  HTTP MCP transport marola doesn't have; `AGENT-FRAMEWORKS-SURVEY.md` found "nothing in
  this list is a Scala-native multi-agent framework" and listed llm4s as "pre-production per its
  own roadmap". Checked against the jar (§4), llm4s has an agent loop, handoffs, guardrails, an
  MCP client with three transports and an MCP server with two. It is the closest Scala-native fit.

## 3. User-visible change

None for the swimmer, except that both prompts get re-compiled against the runtime default model
(`llama3.2`) by the new step, so the summary wording may shift. For the developer:

```
$ just prompt-compile                      # Scala, over the configured LlmClient — no venv
marola :: prompt-compile — SummarizeSwimConditions, 3 examples, metric jellyfishAndWhale
  example Arpoador/07:00        metric 1.00  → demo (augmented)
  example Praia Vermelha/15:00  metric 1.00  → demo (augmented)
  example Praia do Leme/18:00   metric 1.00  → demo (augmented)
  held-out eval (2 examples): 1.00
  wrote core/src/main/resources/recommendation_prompt.json (3 demos)
marola :: prompt-compile — ReviewSwimSummary, 3 examples, metric reviewJsonWellFormed
  ...
mlflow: experiment "marola/prompt-compile", run 7c1a… — params model=llama3.2 optimiser=bootstrap-few-shot
        trainset=3 max_demos=3; metrics summarize.train=1.0 summarize.dev=1.0 review.train=0.9 …;
        artifacts recommendation_prompt.json review_prompt.json      (only with MAROLA_MLFLOW_TRACKING_URI)

$ MAROLA_LLM_PROVIDER=llm4s-ollama just run -- --summarize   # same output shape as today
```

`just run -- --summarize` with no provider set is unchanged (marola's own `LocalLlmClient`).

## 4. Data sources and dependencies reviewed

### 4.1 llm4s — what was verified (2026-09-05) and how

Everything below was checked against the published jar (`javap`, class listing, `cs resolve`) or the
source file named in Appendix C, not the README; the detailed notes are Appendix D. Marola use is
the ambition each capability serves, with the phase it lands in (`ARCHITECTURE.md` §11).

| llm4s capability (0.4.1) | Verified how | marola use | Phase |
|---|---|---|---|
| `LLMClient.complete/streamComplete`: `Either[LLMError, Completion]`, token usage | javap; **live**: Scala 3.9.0 probe, one completion on local Ollama (Appendix A) | `Llm4sLlmClient` adapter behind `marola.llm.LlmClient` (§5.2) | 0 |
| Providers: OpenAI (vendor Java SDK, **key only**), Ollama (native `/api/chat`, **drops tool messages, no JSON mode**), Anthropic, Gemini, DeepSeek, Cohere, Mistral, OpenRouter, Vertex, Z.ai | javap + `OpenAIClient.scala`/`OllamaClient.scala` | `llm4s-ollama`/`llm4s-openai` providers, opt-in | 0 |
| Structured output: `ResponseFormat.Json`/`JsonSchema(schema, name, strict)` — OpenAI path only | javap + `ResponseFormat.scala` | `Reviewer` verdict as a strict JSON schema (§5.3) | 0 |
| Tools: `ToolFunction`, `ToolBuilder`, `Schema` DSL, `ToolRegistry` (ujson) | jar + `tool-calling-api-design.md`; ScalaMeta code-gen claimed, not found | one `Tools.scala` source for MCP SDK + llm4s (§5.5) | 1 |
| Agent loop: `Agent.run(query, tools, guardrails, handoffs, maxSteps=50)`, `runStep`, events; `Handoff.to`; `AgentStatus` | javap + `/guide/agents/`, `/handoffs` | the bot's `/ask` agent; escalation agent via handoff (§5.4) | 1 → 4 |
| Guardrails: `InputGuardrail`/`OutputGuardrail`, `ValidationMode.Block/Warn/Log`, 18 built-ins | jar package + `/guardrails` | output checks on `/ask`; **never** the human gate (§5.4) | 1 |
| Orchestration: `Plan` (DAG, topological order, parallel batches), `TypedAgent[I,O]`, `PlanRunner` — `Future`-based | javap | candidate for the named summarize/critique/escalate topology; `Future` sits awkwardly next to Kyo | 4 |
| MCP client: stdio / SSE / Streamable HTTP, `getTools: Either[String, Seq[ToolFunction]]` | javap | consume external servers from the agent — noted, not scoped | 4 |
| MCP server: HTTP+SSE (2024-11-05) + Streamable HTTP (2025-06-18) on `com.sun.net.httpserver`, bearer auth, loopback-only without a key; **no stdio** | javap + `MCPServer.scala` | `just mcp-http`; the remote-tool transport a hosted agent needs (§5.5) | 2/3, cost gate |
| Tracing: `Tracing` trait, modes langfuse/otel/console/collector/noop; OTel module = **gRPC** exporter, `gen_ai.*` attrs | javap + `OpenTelemetryTracing.scala` | not adopted — MLflow is OTLP/HTTP; `TracedLlmClient` wraps the adapter (MIP-0010) | — |
| Embeddings (`OllamaEmbeddingProvider`), `rag` + sqlite/pgvector stores, reranker, RAGAS-style eval | jar packages | not needed: `knowledge/` + `OllamaEmbedder` already live-verified (MIP-0001) | — |
| `eval.dataset`: `Dataset`, `Example[I,O]`, `ExampleSelector`, `JsonlCodec` | javap | a dataset *model* only; `core/prompt/Example` stays marola's (§5.1) | — |
| Prompt optimisation / `Signature` / `BootstrapFewShot` / `MIPROv2` | jar class search: **absent**; README/roadmap silent | none — marola owns the compiler (§5.1) | — |
| `ReliableClient` (retry, circuit breaker), middleware, memory, context pruning, image, speech (vosk), knowledge graph, workspace runner | jar packages | not needed | — |
| Java/Kotlin/Spring interop | roadmap phase 3 | not needed | — |

**Coordinates and terms.** `org.llm4s:llm4s-core_3:0.4.1` (Maven Central via coursier; v0.4.0 and
v0.4.1 both 2026-08-29, the former "rename-only"; v0.3.0 2026-02-21 added OTel, `MCPServer`,
`Result`). MIT (LICENSE, Rory Graves, 2025). Only `llm4s-core_3`, `llm4s-observability-otel_3`, the
two `workspace` artifacts and `knowledgegraph-neo4j_3` are published; `llm4s-mcp`/`-rag`/`-memory`
ship inside `llm4s-core` ("the latest release (v0.4.1) still ships as a single artifact", 1.0 scope).
Built with Scala **3.7.1** ("Scala 3 only"; the homepage's "2.13 fully supported" contradicts the
roadmap: treat as stale); JDK 21 CI. **Verified live** from Scala 3.9.0 with marola's strict flags.
Pre-1.0: "API stabilizing", MiMa and "deprecate before removing" only after 1.0 and only for Frozen
modules; the pending module split will move coordinates again.

**Dependency closure, the decisive number.** `cs resolve llm4s-core_3:0.4.1`: **154 jars, ~150 MB**
versus ~32 MB for marola's Kyo + MCP SDK + logback today; includes a vendor OpenAI Java SDK
1.0.0-beta.16 (+ its core library, Netty, Reactor), `anthropic-java` (+ OkHttp, Kotlin), AWS S3/STS,
PDFBox, POI, Tika, PostgreSQL, sqlite-jdbc, HikariCP, `vosk`, Prometheus, cats-core, upickle/ujson,
Jackson 2.19 (coursier flags Jackson 2.17→2.19 and JNA 5.7→5.19 conflicts).

**Not checked:** the Streamable-HTTP server against a real MCP client; llm4s's `OpenAIClient`
pointed at Ollama's `/v1` (would give tools + `response_format` locally, plausible, not run); a
GraalVM native image with llm4s on the classpath (MIP-0008); llm4s's `MCPClient` against marola's
stdio server.

### 4.2 What marola uses of DSPy today (`dspy/compile_recommendation_prompt.py`)

Two `dspy.Signature`s (8 and 9 string fields), two three-example trainsets, two deterministic
metrics (keyword presence weighted 0.7/0.3; JSON well-formedness 0.3/0.7 + verdict match 0.3),
`dspy.teleprompt.BootstrapFewShot(metric, max_bootstrapped_demos=3)`, `compiled.save(path)`.
Artifact schema (confirmed against `dspy==3.3.1` output, `CompiledPrompt`'s doc comment): `demos[]`
(input fields + output field + `"augmented": true`), `signature.instructions`, `signature.fields[]`
(`prefix`, `description`). No `MIPROv2`, no `dspy.Evaluate`, no dev split. Optional Langfuse tracing
(never verified end to end, `dspy/README.md`).

### 4.3 Ollama's OpenAI-compatible endpoint — `response_format`

Verified live 2026-09-05: `POST /v1/chat/completions` with `"response_format":{"type":"json_object"}`
on `marola-llama3.2` returned `{"score":35,"verdict":"revise"}` and a `usage` block. Relevant because
marola's own `LocalLlmClient` can use it and llm4s's `OllamaClient` (native `/api/chat`) does not.

### 4.4 Alternatives for the client/agent layer (from `AGENT-FRAMEWORKS-SURVEY.md`, not re-verified)

sttp-ai (Ollama + OpenAI-compatible, structured output, tools; needs an fs2/ZIO/Ox backend), Kyo's
own AI modules (typed tools, MCP: "check what's in the pinned RC5 jar"), agent4s (cats-effect,
v0.1.0), LangChain4j (Java). None is Scala-native *and* carries an agent loop with handoffs.

### Pick

llm4s, **as an opt-in module behind marola's traits**, for the agent/MCP/guardrail/structured-output
layer; **marola's own code** for the prompt compiler (llm4s has nothing there).

## 5. Design

### 5.1 The prompt compiler — `core/prompt/`, no new dependency

Pure Scala over the existing `LlmClient`; only the `complete` calls are effects.

```scala
package marola.prompt
final case class Field(name: String, description: String)                 // snake_case names, as today
final case class Signature(instructions: String, inputs: List[Field], output: Field)
final case class Example(inputs: Map[String, String], output: String)
type Metric = (Example, String) => Double                                  // pure, in core/prompt/PromptMetrics

object BootstrapFewShot:
  /** Runs the zero-shot program on each trainset row, keeps the rows whose prediction scores
    * ≥ threshold as demos (marked augmented), stops at maxDemos: what DSPy's BootstrapFewShot does
    * for marola's trainset size; not a port of DSPy. */
  def compile(client: LlmClient, sig: Signature, trainset: List[Example], metric: Metric,
              maxDemos: Int = 3, threshold: Double = 1.0): CompiledPrompt < Sync

object Evaluate:
  def run(client: LlmClient, prompt: CompiledPrompt, devset: List[Example], metric: Metric): Double < Sync

extension (p: CompiledPrompt) def render(sig: Signature): String   // the JSON CompiledPrompt.loadFromString reads
```

`PromptMetrics.jellyfishAndWhale` and `PromptMetrics.reviewJsonWellFormed` port the two Python
metrics with the same weights. The two signatures and trainsets move to `core/prompt/Programs.scala`
(the instructions text verbatim). `cli/prompt/CompilePrompts` (`just prompt-compile`) wires
`AppConfig.llmClient`, runs both compiles plus `Evaluate` on a held-out split (`FUTURE-WORK.md`
§4.1: the dev rows are new examples, added in the same task), writes the two resources, and logs
params/metrics/artifacts to `AppConfig.runLedger` (MIP-0010) under `marola/prompt-compile`. The
Scala side already replays via `CompiledPrompt.buildMessages`; the compiler bootstraps through the
very same method, so compile and serve cannot drift.

### 5.2 The `llm4s/` module — `marola-llm4s`, depends on `core`, used by `cli`

`build.sbt` gains `lazy val llm4s = project.dependsOn(core)` with `"org.llm4s" %% "llm4s-core" %
"0.4.1"`; `cli` depends on it. `core/` and `local/` are untouched, and `just quality` gains a
dependency-boundary check (`sbt local/dependencyList core/dependencyList` must contain no
`org.llm4s`) so the rule is enforced, not remembered. Why a module and not a replacement of
`LlmClient`: (a) pre-1.0 coordinate churn stops at the adapter; (b) the local default keeps its
30-line client and its native image.

```scala
package marola.llm4s
enum LlmFailure:                                   // marola's view of org.llm4s.error.LLMError
  case Auth(msg: String); case RateLimited(msg: String); case Network(msg: String)
  case Service(msg: String); case Invalid(msg: String); case Other(msg: String)

final class Llm4sLlmClient(client: org.llm4s.llmconnect.LLMClient) extends LlmClient:
  def complete(messages: List[ChatMessage]): String < Sync =           // v1 keeps the trait's shape
    Sync.defer(client.complete(toConversation(messages))).map {
      case Right(c)  => c.message.content
      case Left(err) => throw Llm4sLlmClient.Failed(LlmFailure.from(err))   // OQ5: Abort[LlmFailure] instead
    }
object Llm4sLlmClient:
  def ollama(baseUrl: String, model: String): LlmClient                // OllamaConfig → LLMConnect.getClient
  def openAiCompatible(baseUrl: String, model: String, apiKey: String): LlmClient   // Ollama /v1 too (OQ2)
```

`AppConfig`: `MAROLA_LLM_PROVIDER=local | llm4s-ollama | llm4s-openai`; default stays
`local` until the parity gate in §7 passes (OQ1). `TracedLlmClient` (MIP-0010 task 6) wraps this
client like any other, so traces arrive without llm4s's tracing.

### 5.3 Structured output — `Reviewer`

When the provider path supports it (`llm4s-openai`), `Reviewer` asks for
`ResponseFormat.JsonSchema(score:int, verdict:enum, final_summary:string, strict = true)` and
drops the `extractJsonObject` fallback for that path; the fallback stays for `local` and
`llm4s-ollama`. Parsing still lands in the existing `ReviewResult`.

### 5.4 The agent — after MIP-0002, Phase 1

The summarize→review pipeline stays two plain calls: a single completion gains nothing from an agent
loop. Where the loop earns its place is the bot's free-form `/ask` (MIP-0002): one llm4s `Agent`
over marola's four tools as `ToolFunction`s (`find_nearby_beaches`, `get_swim_recommendation`,
`get_water_quality`, `ask_ocean_question`), `maxSteps = 4`, output guardrails `JSONValidator` /
`GroundingGuardrail` in `ValidationMode.Block`, and a marola budget on model calls per request
(survey §2). Handoffs (`Handoff.to(escalationAgent, …)`) are how the third agent becomes
addressable — **the human-confirmation gate before any proactive alert stays marola code; a
`Block` guardrail is not a human gate and never replaces one.** Caveat
that shapes this: through llm4s's `OllamaClient` tool messages are dropped, so on the local default
the loop must go through `openAiCompatible` at Ollama's `/v1` (OQ2) or wait.

### 5.5 MCP — the HTTP server, and a client

`org.llm4s.mcp.MCPServer` over the same tool definitions gives the HTTP+SSE / Streamable-HTTP
transport `ARCHITECTURE.md` §5c lacks, bound to loopback locally (`just mcp-http`), and the piece a
hosted agent's remote MCP tool needs once Phase 3 deploys it (cost gate, human go-ahead). The
stdio server stays on the official Java SDK (llm4s has none). Tool definitions get one source
(`cli/agent/Tools.scala`) rendering both the SDK's `McpSchema.Tool` and llm4s's `ToolFunction`
(OQ4). `MCPClient` is what a future marola agent would use to consume external servers, noted,
not scoped.

### 5.6 What is deterministic, what goes through a model

Unchanged: everything in `scoring/`, the water veto, lore. Through a model: the two compiled
prompts (as today), the compile step's bootstrap calls (offline), and, later, the `/ask` agent's
tool selection, whose *outputs* are the same deterministic tools the CLI runs.

### 5.7 Deprecating `dspy/` — the steps, as tasks (`mip-tasks` will number them)

1. `core/prompt/` compiler, metrics, `render`, round-trip golden test; no llm4s, no behaviour change.
2. `just prompt-compile`; re-compile both artifacts against `llama3.2`; `just e2e` and `just
   benchmark` before/after; commit the new artifacts with the ledger run id in the message.
3. Delete `dspy/` and its `requirements.txt`; `ci.yml`/`justfile` ruff paths drop `dspy`;
   `flake.nix` comments; README badge (`prompts-compiled in Scala`) and §"Offline prompt
   optimization"; `ARCHITECTURE.md` §5a rewritten around `core/prompt/`; `AGENTS.md` module list;
   `FUTURE-WORK.md` §10 (marola's need is met; `ds4s` as a *library* stays
   a non-marola idea) and §4.1 (built); `docs/index.md`; MIP-0010 task 7 marked superseded by task
   2 above. `finetune/build_dataset.py` reads the demos from the JSON artifacts; unchanged.
4. `llm4s/` module, `Llm4sLlmClient`, `AppConfig` providers, dependency-boundary check.
5. `Reviewer` JSON schema on the OpenAI-compatible path.
6. `Tools.scala` single source + HTTP MCP server behind `just mcp-http` (loopback only).
7. The `/ask` agent: blocked on MIP-0002.

Tasks 1-3 do not depend on 4-7 and can merge first; that is the honest shape of "deprecate DSPy".

## 6. Scoring / safety impact

None. `Swimability.score`, `waterVerdict`, thresholds and notes are untouched; the reviewer keeps its
role; guardrails and structured output only make the model's output *more* checkable, never a
substitute for the deterministic layer.

## 7. Verification plan

Unit (all deterministic, no network): `PromptMetricsSpec` (exact 0.0/0.3/0.7/1.0 values on the six
trainset rows, matching the Python metrics), `BootstrapFewShotSpec` (scripted `LlmClient`:
metric-gated selection, `maxDemos` cap, `augmented` flag, threshold), `CompiledPromptRoundTripSpec`
(`render` then `loadFromString` equals the loaded checked-in artifacts, both), `EvaluateSpec`,
`Llm4sLlmClientSpec` (message mapping; `Left` → failure), `AppConfigProviderSpec` (every
`MAROLA_LLM_PROVIDER` value), `ToolsSpec` (SDK and llm4s schemas from one definition agree),
`McpHttpServerSpec` (`tools/list` over a loopback client). Gate: the dependency-boundary check in
`just quality`. Live: `just prompt-compile` against Ollama with `MAROLA_MLFLOW_TRACKING_URI` set
shows one run with two artifacts; `just e2e`; `just benchmark` compared with `docs/benchmarks/`;
`MAROLA_LLM_PROVIDER=llm4s-ollama just run -- --summarize`; `just native-image` still builds and its
size delta is recorded in the PR. **Done** = `dspy/` gone, both artifacts regenerated by Scala with
a ledger run, all gates green, MIP flipped to Implemented with PR numbers and summed `Cost:`.

## 8. Risks, limitations, and honest caveats

- **Weight.** 154 jars / ~150 MB for a client marola uses 30 lines of; cloud SDKs, PDF, Office and
  speech libraries on the `cli` classpath; Jackson version conflicts; native-image reachability
  unknown (MIP-0008). Mitigation: the module boundary, the size measurement in §7, and OQ6.
- **Pre-1.0 churn.** One rename already (v0.4.0); the module split will move coordinates again;
  compatibility promises start after 1.0 and only for Frozen modules. The adapter is the blast wall.
- **Scala versions.** 3.7.1 TASTy reads fine from 3.9.0 today; the reverse never will: if llm4s
  moves to a Scala newer than marola's pin, marola must bump first.
- **The local provider is the weakest path in llm4s:** no tools, no JSON mode through `OllamaClient`.
  marola's own `LocalLlmClient` can do both via `/v1` (§4.3). llm4s is not an upgrade for the
  default path; it is the agent/MCP layer.
- **Tracing.** llm4s's OTel exporter is gRPC; MLflow wants HTTP. Use marola's `TracedLlmClient`.
- **No optimiser in llm4s.** marola owns the compiler; `MIPROv2` is not ported (never used);
  Langfuse's DSPy instrumentation goes away (never verified anyway); the ledger replaces it.
- **Re-compiled prompts change wording.** Small local models follow instructions imperfectly
  (`ARCHITECTURE.md` §5a); the reviewer and the benchmark are the check, as today.
- **Homepage vs roadmap disagree on 2.13**: rely on neither; marola is Scala 3 anyway.

## 9. Alternatives considered

- **Do nothing.** Keeps two toolchains and the admitted skew for a three-example bootstrap. Lost.
- **Build `ds4s` as a library first** (`FUTURE-WORK.md` §10). Right scope for a general port, wrong
  for marola's need; §5.1 is what marola needs and it is small. `ds4s` stays a non-marola idea.
- **Replace `LlmClient` with llm4s everywhere.** Lost on churn and weight on the local default.
- **Adopt llm4s only for the compile step.** Pointless: the compiler needs only `LlmClient`.
- **Kyo's AI modules / sttp-ai instead of llm4s.** Cheaper effect fit; neither has an agent loop
  with handoffs and guardrails. Revisit when marola bumps past RC5 (survey §1.2).
- **Make `llm4s-ollama` the default.** Rejected until tools/JSON mode reach the local path (OQ1/OQ2).

## 11. Open questions

1. **Default flip.** Criteria for `MAROLA_LLM_PROVIDER` defaulting to an llm4s path, if ever: `just
   benchmark` parity and `just e2e` green for N runs? Human decision.
2. **`OpenAIClient` → Ollama `/v1`** for tools + `response_format` on the local path: run it.
3. **Native image with llm4s** (MIP-0008): measure; if it breaks, `cli` splits into `cli` (native,
   no llm4s) and `cli-agent`.
4. **One tool-definition source** for the Java MCP SDK and llm4s: shape of `Tools.scala`.
5. **`LlmClient` error channel**: keep throwing, or `String < (Sync & Abort[LlmFailure])` across all
   three clients in task 4 (the `enum` rule says the latter; the fan-out is `Main`/`Reviewer`/MCP).
6. **Wait for llm4s's module split?** An `llm4s-ollama`/`llm4s-agent` without the cloud SDKs would
   cut most of §8's weight. Track the roadmap; do not block tasks 1-3 on it.
7. **Which model compiles the artifacts** in task 2: `llama3.2` (runtime default) or the 8x7b that
   produced today's demos. Human decision; the ledger records it either way.

## Appendix

### A. The probe (Scala 3.9.0, strict flags) — compiled and run 2026-09-05

```scala
//> using scala 3.9.0
//> using dep org.llm4s::llm4s-core:0.4.1
//> using options -language:strictEquality -Wvalue-discard -Wnonunit-statement -deprecation
import org.llm4s.llmconnect.{LLMClient, LLMConnect}
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
val result = for
  registry <- ModelRegistryService.default()
  client   <- { given ModelRegistryService = registry
                LLMConnect.getClient(OllamaConfig("marola-llama3.2", "http://localhost:11434", 8192, 1024)) }
  text     <- client.complete(Conversation(Seq(SystemMessage("Answer in one short sentence."),
                UserMessage("Is 0.4 m swell calm for swimming?")))).map(_.message.content)
yield text
// → OK via llm4s OllamaClient (marola-llama3.2): Yes, a 0.4 meter wave is generally considered very calm …
```

No warnings under the strict flags. `ModelRegistryService` is a `using` parameter of `getClient`.

### B. Signatures confirmed by `javap` on `llm4s-core_3-0.4.1.jar`

`LLMClient.complete(Conversation, CompletionOptions): Either[LLMError, Completion]` ·
`CompletionOptions(temperature, topP, maxTokens, presencePenalty, frequencyPenalty, tools:
Seq[ToolFunction[?, ?]], reasoning: Option[ReasoningEffort], budgetTokens, responseFormat:
Option[ResponseFormat])` · `OllamaConfig(model, baseUrl, contextWindow, reserveCompletion)` ·
`Agent(client).run(String, ToolRegistry,
Seq[InputGuardrail], Seq[OutputGuardrail], Seq[Handoff], Option[Int], Option[String],
CompletionOptions, AgentContext): Either[LLMError, AgentState]` · `MCPServer(MCPServerOptions(port,
path, name, version, apiKey: Option[String], host), Seq[ToolFunction[?, ?]]).start(): Either[
Exception, Unit]` · `MCPServerConfig.stdio(name, command, timeout) / sse / streamableHTTP` ·
`MCPClient.getTools: Either[String, Seq[ToolFunction[?, ?]]]` · `OpenTelemetryConfig(serviceName,
endpoint, headers: Map[String, String])` · `Tracing.traceCompletion(Completion, String):
Either[LLMError, Unit]` · `TypedAgent[I, O].execute(I)(using ExecutionContext): Future[Either[
LLMError, O]]` · `Plan.topologicalOrder`, `PlanRunner.execute(Plan, Map, CancellationToken)`.

### C. Sources fetched 2026-09-05

github.com/llm4s/llm4s (README, LICENSE, `build.sbt`, `project/Dependencies.scala`, `releases`,
`releases/tag/v0.3.0`, `modules/` tree, `modules/core/.../provider/OllamaClient.scala`,
`.../provider/OpenAIClient.scala`, `.../model/ResponseFormat.scala`, `.../http/Llm4sHttpClient.scala`,
`modules/mcp/.../MCPServer.scala`, `modules/trace-opentelemetry/.../OpenTelemetryTracing.scala`);
llm4s.org (`/`, `/reference/roadmap`, `/reference/v1-scope`, `/migrations/0x-to-1x`, `/guide/agents/`,
`/guide/agents/handoffs`, `/guide/agents/guardrails`, `/guide/providers`, `/guide/observability/`,
`/getting-started/ollama-quickstart`, `/getting-started/configuration`, `/llm4s-api-spec`,
`/tool-calling-api-design`, `/PRODUCTION_DEPLOYMENT`); Maven Central via coursier (`cs resolve`,
`cs complete-dep`, `cs fetch`); local Ollama `/api/tags` and `/v1/chat/completions`. Not fetched:
`/guide/tools`, `/guide/structured-output` (404 — do not exist under those names).


### D. llm4s 0.4.1 — detailed notes behind the §4.1 table

- **Coordinates, version, licence.** `org.llm4s:llm4s-core_3:0.4.1` on Maven Central (coursier
  `complete-dep`; releases page: v0.4.0 29 Aug "rename-only release … artifact prefix
  standardization", v0.4.1 29 Aug "CI/docs … Maven relocation POMs"; v0.3.0 21 Feb added the
  OpenTelemetry backend, `MCPServer`, Gemini/DeepSeek, `Result` migration). MIT (LICENSE, Rory
  Graves, 2025). Published at 0.4.1: `llm4s-core_3`, `llm4s-observability-otel_3`,
  `llm4s-workspace-client_3`, `llm4s-workspace-shared_3`, `llm4s-knowledgegraph-neo4j_3` — the
  `llm4s-mcp`/`llm4s-rag`/`llm4s-memory` modules named in its `build.sbt` and 1.0 scope are **not
  separately published**; their packages ship inside `llm4s-core` (jar inspection: `org.llm4s.mcp`
  32 classes, `org.llm4s.rag`, `org.llm4s.agent.memory`). The 1.0-scope page says so itself: "the
  latest release (v0.4.1) still ships as a single artifact".
- **Scala/JDK.** Built with Scala 3.7.1 ("Scala 3 only (3.7.1)", roadmap and 1.0 scope; CI on JDK
  21). The homepage's "Scala 2.13.x fully supported" contradicts both; treat as stale; the
  `_2.13` artifacts on Central carry the pre-rename names. **Verified live:** a probe compiled with
  Scala **3.9.0** and marola's flags (`-language:strictEquality -Wvalue-discard
  -Wnonunit-statement`) against `llm4s-core_3:0.4.1` and completed one chat turn against the local
  Ollama (`marola-llama3.2`) through llm4s's `OllamaClient` (Appendix A).
- **Dependency closure (the decisive number).** `cs resolve` of `llm4s-core_3:0.4.1`: **154 jars,
  ~150 MB** on disk, versus ~32 MB for marola's current Kyo + MCP SDK + logback set. It includes a
  vendor OpenAI Java SDK 1.0.0-beta.16 (+ its core library, Netty, Reactor),
  `com.anthropic: anthropic-java:2.42.0` (+ OkHttp, Kotlin stdlib), the AWS SDK (S3, STS), PDFBox,
  POI, Tika, PostgreSQL, sqlite-jdbc, HikariCP, `vosk` (speech), Prometheus, cats-core,
  upickle/ujson, Jackson 2.19. Coursier flags conflicts (Jackson 2.17.2→2.19.4 under that SDK's core
  library, JNA 5.7→5.19).
- **Client API** (javap): `LLMClient.complete(Conversation, CompletionOptions): Either[LLMError,
  Completion]`, `streamComplete`, `validate`, `close` (`AutoCloseable`); `type Result[+A] =
  Either[LLMError, A]`, a sealed `LLMError` hierarchy of 20+ cases; `LLMConnect.getClient(config)(
  using ModelRegistryService)`; `Completion(id, created, message, usage: Option[TokenUsage])`.
- **Providers** (javap + source): `OpenAIClient` wraps a vendor OpenAI Java SDK, **API key only**
  (`KeyCredential`, no `TokenCredential`);
  `OllamaClient` posts to Ollama's native **`/api/chat`** over `java.net.http`, streams, and
  **drops tool messages** ("Tool messages are not supported by Ollama chat API; drop them") and
  **sends no `responseFormat`**. Also Anthropic, Gemini, DeepSeek, Cohere, Mistral, OpenRouter,
  Vertex AI, Z.ai.
- **Structured output.** `CompletionOptions.responseFormat: Option[ResponseFormat]`; `ResponseFormat
  .Json | .JsonSchema(schema: ujson.Value, name, strict)`; `ResponseFormatMapper
  .toOpenAIResponseFormat`, OpenAI-path only. No case-class→schema derivation found in the jar.
- **Tools.** `ToolFunction[T, R: ReadWriter](name, description, schema, handler:
  SafeParameterExtractor => Either[String, R])`, `ToolBuilder`, a `Schema` DSL (`string`, `number`,
  `object`, `array`, `.withEnum`…), `ToolRegistry.execute: Either[ToolCallError, ujson.Value]`.
  ScalaMeta code-gen is claimed in the README; not found in the published jar.
- **Agent.** `new Agent(client)`; `run(query, tools, inputGuardrails, outputGuardrails, handoffs,
  maxSteps (default 50), …): Either[LLMError, AgentState]`, `runStep`, `runWithEvents`,
  `continueConversation`; `Handoff.to(agent, reason)`; `AgentStatus` InProgress / WaitingForTools /
  Complete / Failed / HandoffRequested. `agent.orchestration`: `Plan` (nodes, edges,
  `topologicalOrder`, `getParallelBatches`), `TypedAgent[I, O]` and `PlanRunner`, **`Future`-based**.
- **Guardrails.** `InputGuardrail`/`OutputGuardrail`, `ValidationMode.Block | Warn | Log`; 18
  built-ins (`JSONValidator`, `RegexValidator`, `GroundingGuardrail`, `SourceAttributionGuardrail`,
  `PromptInjectionDetector`, `LLMFactualityGuardrail`, …). No human-approval concept.
- **MCP.** Client: `MCPClient.getTools: Either[String, Seq[ToolFunction]]` over `StdioTransport(
  command)`, `SSETransport`, `StreamableHTTPTransport` (`MCPServerConfig.stdio/sse/streamableHTTP`).
  Server: `MCPServer(MCPServerOptions(port, path, name, version, apiKey, host), tools)` on
  `com.sun.net.httpserver`; HTTP+SSE (protocol 2024-11-05) and Streamable HTTP (2025-06-18);
  bearer auth with constant-time compare; "refuses to bind a non-loopback host" without a key.
  **No stdio server.**
- **Tracing.** `Tracing` trait (`traceEvent`, `traceCompletion`, `traceTokenUsage`, `traceCost`,
  `traceToolCall`, `traceAgentState`); modes `langfuse | opentelemetry | console | collector |
  noop`. The OTel module (`llm4s-observability-otel`) uses **`OtlpGrpcSpanExporter`**, one span
  named "LLM Completion" with `gen_ai.request.model` and `gen_ai.usage.*`. MLflow ingests
  **OTLP/HTTP only** (MIP-0010 §4.3), not compatible as-is.
- **Also present, not needed here:** embeddings (`OllamaEmbeddingProvider`), `rag` + vector stores
  (sqlite/pgvector) + RAGAS-style `rag.evaluation`, reranker, memory + consolidation, context-window
  pruning, `ReliableClient` (retry, circuit breaker), middleware, image generation, speech (vosk),
  knowledge graph, workspace runner. `eval.dataset` (`Dataset`, `Example[I, O]`, `ExampleSelector`,
  `JsonlCodec`) is a dataset *model*, no optimiser.
- **Prompt optimisation / a DSPy equivalent: none.** A class-name search of the jar for
  Prompt/Optim/FewShot/Template/Signature finds only `PromptInjectionDetector`,
  `ConsolidationPrompts` and an image `TemplateName`; README and roadmap never mention DSPy.
- **Roadmap.** "Pre-1.0, API stabilizing"; 2026 phases (module boundaries, provider capability
  matrix, Java/Kotlin/Spring interop, security hardening); "v1.0 date intentionally not fixed";
  binary compatibility (MiMa) and "deprecate before removing" apply to *Frozen* modules **after**
  1.0. The pending module split (`llm4s-core` / `llm4s-agent` / `llm4s-ollama` …) means at least
  one more coordinate change; v0.4.0 was already one.
- **Effect model.** Plain `Either`, `Future` for orchestration; no cats-effect/ZIO/Kyo. Fits
  marola's rule as a boundary call wrapped in `Sync.defer`, the `Left` lifted into `Abort`.

**Not checked:** the Streamable-HTTP server against a real MCP client; llm4s's `OpenAIClient`
pointed at Ollama's `/v1` (would give tools + `response_format` on the local path, plausible, not
run); a GraalVM native image with llm4s on the classpath (MIP-0008, Netty/Jackson/OkHttp
reachability); llm4s's `MCPClient` against marola's stdio server.
