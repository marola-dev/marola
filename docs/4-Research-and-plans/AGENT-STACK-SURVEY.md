# Agent stack survey

A survey of three agent toolkits against marola's MIPs, plus a worked design for the first agent
worth building: a Q&A agent that answers questions about the marola project itself. It extends
`AGENT-FRAMEWORKS-SURVEY.md` (2026-09-05), which listed agent4s and llm4s in one table row each, and
`MIP-0012` (llm4s adoption), which remains the decision record for llm4s. Versions and numbers below
were checked on 2026-09-23 unless marked ⚠.

## 1. Short answer

- **llm4s** is the in-process Scala layer: an agent loop, tools, MCP, RAG, guardrails and tracing,
  in the language the rest of marola is written in. `MIP-0012` already plans it as an opt-in module
  behind `LlmClient`. Nothing here changes that plan.
- **ADK (Google Agent Development Kit)** is the multi-agent orchestration, evaluation and deployment
  layer. It runs on the JVM (ADK Java), talks to Ollama through LangChain4j, speaks MCP and A2A, and
  deploys to Cloud Run or Google's agent runtime, which is the natural home for `MIP-0057` (GCP).
  It is heavy: 207 jars for the core artifact.
- **agent4s** is a small Scala 3 toolkit with a LangGraph-style graph module. It has no release, no
  Ollama, no MCP, and it runs on cats-effect, a second effect system next to Kyo. Read its graph
  module for the shape; don't depend on it.
- **They compose, but at process boundaries, not inside one JVM.** MCP is the seam that already
  exists: `SwimConditionsMcpServer` exposes marola's tools, and both llm4s and ADK agents can call
  it. A2A is the seam between agents (ADK has it; llm4s does not advertise it ⚠). marola's own
  `LlmClient` / `KnowledgeStore` traits stay the contract inside the JVM.

## 2. The three, side by side

| | agent4s | llm4s | ADK (Java) |
|---|---|---|---|
| What it is | Scala 3 agent toolkit ([razeghi71/agent4s](https://github.com/razeghi71/agent4s)) | "Agentic and LLM programming in Scala" ([llm4s/llm4s](https://github.com/llm4s/llm4s)) | Google's Agent Development Kit ([adk.dev](https://adk.dev/)); Python, TypeScript, Go, Java, Kotlin |
| Version | no tags or releases; README says v0.1.0; last push 2026-02-05; 0 stars | `org.llm4s:llm4s-core_3:0.4.1` (2026-08-29), pre-1.0 | `com.google.adk:google-adk:1.10.1` (Maven Central, 2026-09-18) |
| Licence | MIT | MIT | Apache-2.0 ⚠ (per the repo; not re-read here) |
| Runtime | cats-effect 3, fs2, http4s, circe | plain Scala (`Either`, `Future`); Scala 3.7, JDK 21+ | Java 17+; RxJava 3 and Reactor in the API |
| Dependency closure | small, but a second effect stack | 154 jars, ~150 MB (`MIP-0012` §4) | 207 jars for `google-adk` alone (`cs resolve`, 2026-09-23); pulls Vertex AI, Cloud Storage, BigQuery, Anthropic and Docker clients |
| Ollama / local models | not listed (Claude, OpenAI, Gemini, DeepSeek, Perplexity) | native Ollama client, but it **drops tool messages and has no JSON mode** at 0.4.1 (`MIP-0012` §4) | via `google-adk-langchain4j` 1.10.1 + LangChain4j's Ollama module |
| Tool calling | case-class registry with derived JSON schema | typed tools, async tools, ScalaMeta codegen | function tools, OpenAPI tools, agent-as-tool, "action confirmations" |
| MCP | none | client (stdio/SSE/Streamable HTTP) and HTTP server | MCP tools (client) |
| Multi-agent | graph module: states, conditional routing (`.when` / `.otherwise`), no checkpoints | single or multi-agent workflows, handoffs, guardrails, memory | `LlmAgent` plus Sequential, Parallel and Loop workflow agents, custom agents, agent routing, A2A (`google-adk-a2a`) |
| RAG | none | vector stores, hybrid search, reranking, permission-aware RAG, RAGAS-style evaluation | through tools and memory services; no built-in retrieval stack in the core ⚠ |
| Evaluation | none | RAGAS-style RAG eval, benchmarking harness | criteria, user simulation, environment simulation, custom metrics ⚠ (documented for ADK; Java parity not checked) |
| Observability | none | Langfuse, console | OpenTelemetry (in the POM) |
| Deploy | library only | library only | Cloud Run, GKE, Google's agent runtime, or anywhere; dev UI; Firestore session service artifact |

## 3. What marola's constraints say about each

- **Local-first, Ollama by default** (`AGENTS.md`, `PHILOSOPHY.md`). llm4s has a native Ollama
  client, but its tool-message gap matters for any agent that calls tools: a local tool-using agent
  would need Ollama's OpenAI-compatible `/v1` endpoint through llm4s's OpenAI client, which
  `MIP-0012` found to be key-only (a dummy key may work ⚠ untested). ADK Java reaches Ollama through
  LangChain4j, one more layer but a maintained one. agent4s has no local path at all.
- **The Kyo effect boundary** (`.claude/rules/scala.md`). llm4s returns `Either`/`Future`, which
  wraps into Kyo at one adapter, as `MIP-0012` §5 designs. ADK Java returns RxJava `Flowable`s,
  which also wrap at one adapter, but it puts a reactive runtime inside the JVM. agent4s brings
  cats-effect `IO`; running two effect systems in one process is the cost `MIP-0012` already
  refused for less.
- **Dependency weight.** `core/` and `local/` stay free of any of the three. llm4s (154 jars) and ADK
  (207 jars) each belong in their own opt-in module, the way the removed Azure module used to sit.
  Putting both in one module invites Jackson/OkHttp/protobuf version fights; keep them apart.
- **Cloud is opt-in and GCP-shaped** (`MIP-0057`). ADK is the only one of the three with a deploy
  story, and it is Google's. That is an argument for ADK *when* a cloud agent exists, and an
  argument against it before then.

## 4. MIP by MIP

| MIP / idea | What it needs | llm4s | ADK | agent4s |
|---|---|---|---|---|
| `MIP-0012` llm4s layer, DSPy replacement | agent loop, MCP, structured output | **the plan** | | |
| `MIP-0033` self-hosted chatbot | one agent, tools, local model | agent + MCP client over `SwimConditionsMcpServer` | possible, heavier | |
| `MIP-0002` Telegram bot | a conversation with memory | agent memory | session and memory services | |
| `MIP-0055` vector store, chunking | retrieval quality, eval | vector stores, hybrid search, reranking, RAGAS eval to compare against the Lucene HNSW + BM25 plan | | |
| `MIP-0040` validating the reviewer | a judge measured against labels | RAGAS-style eval | eval criteria and custom metrics | |
| `ROADMAP.md` §5: escalation agent (MIP-0023 slot), roles as units (0024), eval harness (0026) | a third agent, explicit topology, cross-agent eval | handoffs | Sequential/Parallel/Loop agents, agent routing, eval with user simulation | graph shape to borrow |
| `MIP-0018` / `MIP-0024` content pipelines | a fixed multi-step pipeline | | Sequential workflow agent | graph with conditional routing |
| `MIP-0057` GCP backend | Gemini, deploy, sessions | Gemini/Vertex providers | Gemini native, Cloud Run / agent runtime, Firestore sessions | |
| `MIP-0059` typed decisions (Jev) | structured choice/score output | `ResponseFormat.JsonSchema` | output schemas ⚠ | |
| `MIP-0039` fact guard, `MIP-0022` safety footer | deterministic checks after the model | guardrails could *host* them | callbacks could host them | |
| `MIP-0010` MLflow ledger | traces next to benchmark runs | Langfuse tracing (a second store) | OpenTelemetry | |
| `MIP-0025` / `MIP-0048` marola-sea model | call a model served by Ollama | yes (with the tool caveat) | yes, via LangChain4j | no |
| `MIP-0050` Brazilian models | swap models per task | provider switch | model per agent | |

The deterministic guards (`MIP-0022`, `MIP-0039`) stay in `core/` whichever toolkit runs the agent.
Both llm4s guardrails and ADK callbacks are places to *call* them from, not reasons to move them.

## 5. The first agent: "ask marola about marola"

A Q&A agent over the project's own docs: "which MIPs depend on MIP-0002?", "why is DSPy being
deprecated?", "what does the reviewer do?". It is small, useful to the maintainer and to anyone
reading the repo, and it exercises every piece a bigger agent needs.

**v0: no new dependency.** marola already has the RAG pieces: `Corpus` (markdown in, chunks out),
`KnowledgeStore` (embed with Ollama, search), and `OceanQa` (grounded answer, "I don't know"
fallback). Three things stop it from answering project questions today:
- `Corpus.listFiles` reads only the top level of a directory (plus `safety/`), not `docs/MIPs/`.
- `OceanQa`'s prompt says it answers questions about the ocean.
- Answers cite a corpus title, where a project answer should cite a file path and section.

A `ProjectQa` beside `OceanQa`, with its own prompt, a recursive corpus over `docs/` and
`AGENTS.md`, and its own index file (`MAROLA_KNOWLEDGE_DIR` / `MAROLA_KNOWLEDGE_INDEX_PATH` already
exist), is a few dozen lines and runs entirely on Ollama. It also belongs as a fifth MCP tool
(`ask_project_question`) next to `ask_ocean_question`.

**v1: llm4s agent with tools.** Once `MIP-0012`'s module exists, the same question can go to an
llm4s agent that can also *act*: read a file, list MIPs by status from `docs/MIPs/README.md`, call
`mip_graph.py`, or call marola's own MCP tools for a live "what's the swim forecast" answer. This is
where the Ollama tool-message gap has to be solved first.

**v2: ADK multi-agent, on GCP, only if `MIP-0057` goes ahead.** A router `LlmAgent` in front of two
specialists:
- the project agent (v1, reached over A2A or as an MCP tool)
- an ocean agent (`ask_ocean_question`, the swim tools)

ADK's eval (user simulation) tests the routing, and Cloud Run hosts it. Before `MIP-0057`, v2 has no
reason to exist.

## 6. How they compose

```text
            ┌───────────── ADK (optional, GCP) ─────────────┐
            │ router LlmAgent → project agent / ocean agent │
            └───────────────┬─────────────────┬─────────────┘
                     A2A    │                 │  MCP tools
                            ▼                 ▼
┌──────────── marola JVM (cli) ────────────────────────────────────────┐
│ llm4s agent (opt-in module)  ──MCP client──►  SwimConditionsMcpServer│
│        │                                        │                    │
│        └────── LlmClient / KnowledgeStore (core traits) ◄──┘         │
│                         │                                            │
│                  local/: Ollama, file index                          │
└──────────────────────────────────────────────────────────────────────┘
```

- **Inside the JVM,** marola's traits are the contract. llm4s plugs in behind `LlmClient` (and, for
  RAG, behind `KnowledgeStore`) exactly as `MIP-0012` proposes. Nothing above the trait knows which
  library answered.
- **Between processes,** MCP carries tools (already built) and A2A carries agent-to-agent calls
  (ADK has it; for llm4s, check before designing on it ⚠).
- **Not recommended:** llm4s and ADK Java in the same sbt module (361 jars and overlapping
  Jackson/OkHttp/protobuf), or agent4s as a dependency (a second effect runtime for a graph module
  that is a few hundred lines to reimplement on Kyo if `ROADMAP.md` §5 ever needs one).

## 7. Suggested order

1. v0 project Q&A on the existing RAG, plus the `ask_project_question` MCP tool. No dependency, no
   MIP beyond a short one for the new tool and corpus.
2. `MIP-0012` as planned, with one added task: close the Ollama tool-message gap (upstream fix, or
   the `/v1` path) before any tool-using local agent.
3. Re-read agent4s's graph module when the escalation agent (`ROADMAP.md` §5) is designed; decide
   between Pekko (`AGENT-FRAMEWORKS-SURVEY.md` §3) and a small Kyo graph.
4. ADK only with `MIP-0057`, in its own module or its own service, reached over A2A/MCP.

## 8. Not verified here

- ⚠ ADK Java's parity with Python ADK for evaluation and output schemas.
- ⚠ Whether llm4s's OpenAI client accepts Ollama's `/v1` endpoint with a dummy key.
- ⚠ Whether llm4s supports A2A.
- ⚠ The ADK licence file was not re-read (Apache-2.0 is expected for Google OSS).

## Sources

- [agent4s (razeghi71)](https://github.com/razeghi71/agent4s); the GitHub API gave 0 stars, last push 2026-02-05 and no tags.
- [llm4s](https://github.com/llm4s/llm4s), [llm4s roadmap](https://llm4s.org/reference/roadmap.html),
  and [Maven Central `org.llm4s:llm4s-core_3`](https://repo1.maven.org/maven2/org/llm4s/llm4s-core_3/maven-metadata.xml)
  (latest 0.4.1).
- [ADK docs](https://adk.dev/), and [Maven Central `com.google.adk:google-adk`](https://repo1.maven.org/maven2/com/google/adk/google-adk/maven-metadata.xml)
  (latest 1.10.1) with its [POM](https://repo1.maven.org/maven2/com/google/adk/google-adk/1.10.1/google-adk-1.10.1.pom).
- [ADK for Java and LangChain4j (Google Developers Blog)](https://developers.googleblog.com/adk-for-java-opening-up-to-third-party-language-models-via-langchain4j-integration/)
  and [an ADK Java agent on Gemma 4 via Ollama (G. Laforge, 2026-04-02)](https://glaforge.dev/posts/2026/04/02/an-adk-java-agent-powered-by-gemma-4/).
- marola: `docs/MIPs/MIP-0012-llm4s-adoption-and-dspy-deprecation.md` §4 (llm4s closure and Ollama
  findings), `core/src/main/scala/marola/knowledge/{Corpus,OceanQa}.scala`,
  `cli/src/main/scala/marola/agent/SwimConditionsMcpServer.scala`.
