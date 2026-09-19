# MIP-0061: The swim-brief agent — Google's ADK for Java, driven from Scala and Kyo

| | |
|---|---|
| **Status** | Draft — `Tasks: docs/mips/MIP-0061.tasks.md` |
| **Author** | Claude (Fable 5.1), for M. Hoffmann |
| **Created** | 2026-09-19 |
| **Phase** | 0 (a CLI entry point; no Telegram, no deploy). The reply path to a user is Phase 1 and is not in this MIP |
| **Related** | MIP-0057 (GCP as an opt-in backend — this is its first concrete slice), MIP-0039 (the fact guard, Draft — §5.6 is a narrow instance of it), MIP-0012 (llm4s, Draft), MIP-0060 (its go/no-go run chose the default model here), `ARCHITECTURE.md` §5b (agentic tool access), `cli/.../SwimConditionsMcpServer.scala` |
| **Effort** | L — a new sbt module (`agents/`), one new dependency family (adk-java + LangChain4j), seven source files and their specs. Task 1 is built; tasks 2–4 are S/M each |
| **Gain** | `user value` (one answer to "where, when, and when do I leave", with water quality, forecast and hazards); `exam coverage` (PR000340 §3.1 ADK agents and tools, §3.2 MCP/registry, §3.3 A2A — the 33% section); `infra/dev-loop` (the first agent runtime in the repo that is not a hand-ordered pipeline) |
| **Effort vs Gain** | `do next` for tasks 1–2 ($0, local). `do when X lands` for registry/runtime: X = a GCP project with a budget alert and a human go-ahead |
| **Depends on** | No MIP must merge first. Tasks 3–4 are gated by `AGENTS.md`'s cost rule (a billed GCP project). Not gated by Phase 1 as long as it stays a CLI |
| **Blocked by** | none |
| **Risk** | A small local model states a safety fact the data never gave. It happened on the first live run (§8) — which is why §5.6 exists and why this MIP does not put the model's prose in front of a user unguarded |
| **Cost so far** | — |

## 1. Summary

`swim-brief` is an ADK `LlmAgent` that answers one question — *where should I swim tomorrow, at what
hour, and when do I leave?* — from marola's existing deterministic pipeline: OpenStreetMap beaches,
the Open-Meteo forecast, the state agency's bathing-water verdict, `Swimability`'s score and hazard
notes, tides, and a new typical-pattern travel-time estimate. It is written in Scala 3 on Kyo
against **adk-java 1.10.1**; there is no Scala ADK. It runs on local Ollama by default and on the
Gemini API with a key, both at $0. The model chooses tools and phrases; it decides nothing, and a
deterministic guard withholds its prose when it contradicts the data.

## 2. Motivation

`Recommender` hardcodes the call order and `Main` prints a report. There is no component that can
be *asked* something, and nothing in the repo exercises the agent-platform concepts MIP-0057 and
the PR000340 exam are about. The MCP server exposes the tools, but only to someone else's agent.
A first agent of marola's own closes that — provided it keeps the house rule that whether to swim
is never a model's call.

## 3. User-visible change

```
$ just swim-brief --lat -27.5954 --lon -48.5480 --radius-km 20        # real run, 2026-09-19
1. Ponta da Ilhota — score 55/100
   in the water at 13:00, leave by 12:55 (~5 min, traffic light)
   bathing water: no data
   hazards: choppy (1.0m waves); breezy (21km/h); cold water (19.4°C)
2. Praia da Saudade — score 55/100 …
Travel times: estimate from a typical weekday/weekend rush-hour pattern — not live traffic.

(model commentary withheld: it describes the bathing water in words the data does not use:
 "- bathing-water verdict: good")
```

The numbered block is plain code over the tool result. The model's paragraph appears under it only
when it passes §5.6. `--ask "…"` changes the question; `--trace` prints tool calls and results.

## 4. Data sources and dependencies reviewed

### 4.1 adk-java — checked 2026-09-19 against the source at tag `v1.10.1`
Apache-2.0, Java 17+, `com.google.adk:google-adk`. Confirmed in source: `BaseTool` is a plain
abstract class (`declaration()`, `runAsync(Map, ToolContext): Single<Map>`); `LlmAgent.builder()`;
`InMemoryRunner`; `Runner.runAsync(userId, sessionId, Content): Flowable<Event>`; `BaseLlm` has two
abstract methods, so a scripted fake is ~15 lines; `Gemini(String model, String apiKey)` exists;
`contrib/langchain4j` (`google-adk-langchain4j`) wraps LangChain4j 1.12.2 and documents
`OllamaChatModel`; its pom pins **MCP SDK 2.0.0 — the version `cli` already uses**. **Verified by
building:** resolves and compiles under Scala 3.9.0 / JDK 25 with Kyo 1.0.0-RC5; `OllamaChatModel`'s
`numCtx` is a per-request option. **Not in adk-java** (compared with adk-python 2.9.2 the same
day): an evaluation framework, Memory Bank, database sessions, LiteLLM. **Not checked:** the `a2a`
module's server API; native-image behaviour (moot — §5.1).

### 4.2 Kyo modules (getkyo/kyo, Maven Central, 2026-09-19)
`kyo-reactive-streams` (published at RC5): `fromPublisher` turns a `Flow.Publisher` into a Kyo
`Stream`; an RxJava `Flowable` needs `FlowAdapters.toFlowPublisher` first. Right for streaming
replies, unnecessary for a single-turn CLI — deferred to the Telegram surface. `kyo-ai` (RC5): an
LLM/tool layer of its own; it competes with the ADK rather than helping it — not both. `kyo-mcp`:
an MCP implementation; marola's server is on the Java SDK and the agent calls core in-process, so
no. `kyo-schema`: could derive tool parameter schemas from case classes — **not checked** at RC5;
three hand-written parameters do not justify it yet.

### 4.3 Models and cost
Ollama, local: $0. `llama3.2` is the default because MIP-0060's run showed `qwen2.5-coder:7b`
writing tool calls as text on this host, and the host truncating prompts to 4096 tokens — hence
the explicit `num_ctx` (default 16384). Gemini API: a free tier with no billing account exists and
on it content *is* used to improve Google's products (ai.google.dev pricing page, fetched
2026-09-19; rate limits not stated there). A swim brief is public data plus a coordinate; the
coordinate is the user's location, so this stays opt-in. Vertex AI / **Model Garden**: needs a
billed project. Two cost shapes — per-token model-as-a-service, and self-deployed open weights on a
GPU endpoint billed per hour while it exists. **Prices not verified today**; express mode's page
returned no content — **not checked**.

### 4.4 Agent Registry, and Besom
Manual registration, from docs.cloud.google.com/agent-registry/manual-registration (fetched
2026-09-19): `gcloud agent-registry services create NAME --project --location --display-name
--agent-spec-type=a2a-agent-card --agent-spec-content=@agent-card.json`; card ≤ 10 KB; role
`roles/agentregistry.editor`; not available in the `us`/`eu` multi-regions (a region or `global`).
**Not stated on that page:** price, GA/preview, whether the endpoint must be reachable.
IaC: Pulumi's GCP provider has `agentregistry` (`Service`, `Binding`, data sources) at v9.36.1;
Terraform's has `google_agent_registry_service`. **Besom** (Pulumi for Scala, v0.5.2, 2026-09-18)
publishes `besom-gcp` at `9.0.0-core.0.5` — provider 9.0.0, 36 minors older. **Not checked**
whether that build contains `agentregistry`, nor Besom's local codegen for a newer provider.

**Pick:** adk-java, in-process tools, Ollama default, Gemini API opt-in. One registry entry does not
need IaC: a documented `gcloud` command is the honest size. Besom earns its place when there is a
*stack* — runtime + IAM + registry + a **budget alert as the first resource** — with a local state
backend (`pulumi login --local`, $0).

## 5. Design

**5.1 Module.** `agents/` — `.dependsOn(core, local, cli)`, a leaf, aggregated by `root` so CI tests
it. Nothing depends on it: the ADK tree never reaches the CLI jar, the Docker image or the native
binary. Entry point `marola.agents.SwimBriefMain`, `just swim-brief`.

**5.2 `KyoTool`.** Subclasses `BaseTool` instead of `FunctionTool.create(Class, "method")`
(reflection over annotated static methods — awkward from Scala). A tool is `params: List[ToolParam]`
and `call(ToolArgs): JsonValue < Sync`; the declaration is built by hand; `runAsync` evaluates the
effect on RxJava's IO scheduler; a failure returns `{"error": …}` instead of throwing at the model.

**5.3 Tools** — all data, no judgement: `find_nearby_beaches`, `get_swim_conditions` (best hour per
beach: score, forecast, `hazards`, water verdict, tides), `plan_swim_outing` (§6). They reuse the
MCP server's JSON renderers (now `private[marola]`) and take a `SwimData` of two functions, so specs
run on canned beaches.

**5.4 Traffic.** marola has no free keyless live-traffic source. `TrafficProfile` is a fixed
weekday/weekend multiplier over a 40 km/h base — a *typical pattern*, labelled as such in every
payload and in the printed footer. Opt-in live backend (designed, not built): Google Routes API,
traffic-aware, behind a `TrafficEstimator` trait — a paid Maps SKU (MIP-0057 §4), so gated.

**5.5 Models.** `MAROLA_AGENT_PROVIDER` = `ollama` (default) | `gemini` (needs `GOOGLE_API_KEY`);
anything else is an error, never a silent fallback. `MAROLA_AGENT_MODEL`, `MAROLA_AGENT_NUM_CTX`.

**5.6 Guard.** `BriefGuard.facts` renders the decision from the tool result. `BriefGuard.verify`
withholds the model's prose if (1) it contains a number no tool returned (honest rounding allowed),
or (2) a line about the bathing water does not quote a verdict the tools gave. Policy, not prompt.

**5.7 GCP path (tasks 2–4).** A2A server + `agent-card.json` from adk-java's `a2a` module, local,
$0 → manual Agent Registry entry with the command in §4.4 → only then Agent Runtime / Model
Garden, each with its own stated cost and go-ahead. `.claude/rules/azure.md`'s guard gets a GCP
twin before any `gcloud … create` is run (MIP-0057 already asks for it).

## 6. Scoring / safety impact

`Swimability.score` is untouched. `OutingPlanner` picks each beach's target hour among hours within
**5 points** of that beach's best score (`ScoreTolerance`), by shortest drive; an hour scored 0 — a
water-quality veto — is never a candidate and a beach with only such hours is dropped. Traffic can
move a swim between near-equal hours; it cannot buy more than 5 points of swimability.

## 7. Verification plan

Task 1, done: `sbt agents/test` — **34 passed** (`TrafficProfileSpec`, `OutingPlannerSpec`,
`KyoToolSpec`, `BriefGuardSpec`, `AgentModelsSpec`, `SwimBriefMainSpec`, and `SwimBriefAgentSpec`,
which runs the **whole ADK loop offline** with a scripted `BaseLlm`: model → tool call → real tool →
result back to the model → reply). `scalafmtCheckAll`, `scalafixAll --check` and the full `sbt test`
(every module, 306 specs, 0 failed) green. Live: three runs against Ollama `llama3.2` near
Florianópolis (~90 s each including sbt), §3's output.
**Not run:** the Gemini path (no key in this session); `docker build` (the Dockerfile copies
`core local azure cli` by name and never `agents/`, so the image build should be unaffected — not
checked).

## 8. Risks, limitations, and honest caveats

- **The first live run fabricated a verdict**: tool said `no data`, `llama3.2` wrote "Good". The
  guard now catches exactly that, and the spec pins it. The guard is two rules, not a proof: prose
  can still mislead in ways neither rule sees. Until MIP-0039 exists, the facts block is the product
  and the prose is a courtesy.
- The traffic figure is a heuristic with no source behind its multipliers. It is labelled; it should
  not be tuned to look precise.
- adk-java is young and moves weekly; LangChain4j is a second fast-moving layer under it.
- "Tomorrow" only, because `Recommender` is; the agent inherits that.

## 9. Alternatives considered

- **`McpToolset` over `just mcp-server`** — the exam-shaped route, and a subprocess plus a stdio hop
  to reach code in the same JVM. Kept as a task-2 variant (`--via-mcp`) because it is worth seeing.
- **llm4s / `kyo-ai`** — Scala-native, no A2A, no registry story; a second agent framework.
- **adk-python** — has evaluation; means leaving the JVM and re-wrapping core over MCP.
- **Do nothing** — marola stays a pipeline with tools only other people's agents can use.

## 10. Exam-coverage mapping

PR000340 (not AI-103/AI-500): §3.1 ADK agent + tools, §3.2 MCP and Agent Registry, §3.3 A2A, §5
"policy beats prompt" (§5.6). `gcp-agentic-architect/marola/AGENTIC-ARCHITECT-MAPPING.md` rows 3.1
and 3.2 move from "small" to "built (task 1)".

## 11. Open questions

1. Which GCP project, and what budget alert, before task 3? (Exam-prep ceiling today: $25.)
2. Does a registry entry need a reachable endpoint, and what does the registry cost?
3. Is a larger local tool-calling model worth pulling so the prose survives the guard more often?
4. Should `TrafficProfile` move to `core/` once a second caller wants it?

## Appendix

Checked 2026-09-19: adk-java source files named in §4.1 at `v1.10.1`; `getkyo/kyo` module list and
Maven metadata for `kyo-reactive-streams`, `kyo-ai`, `kyo-mcp`; `VirtusLab/besom` release and
`besom-gcp` Maven metadata; `pulumi/pulumi-gcp` `sdk/nodejs/agentregistry/*`;
`hashicorp/terraform-provider-google` `agent_registry_*` docs; the three Google pages in §4.3–4.4.
