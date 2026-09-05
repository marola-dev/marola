# marola — AI-103 domain coverage

[AI-103: Developing AI Apps and Agents on Azure](https://learn.microsoft.com/en-us/credentials/certifications/azure-ai-apps-and-agents-developer-associate/)
is an Associate-level exam. It is also the mandatory prerequisite for
[AI-500: Designing and Implementing Multi-Agent AI Solutions](https://learn.microsoft.com/en-us/credentials/certifications/) —
see [`AI-500-MAPPING.md`](./AI-500-MAPPING.md) for how marola extends past this doc into that exam's
multi-agent domains.

This file maps AI-103's published skill areas to what marola actually builds — not a study guide by
itself, but a way to turn "I read about X" into "I ran X against a real API and it's in this repo."
Where a row says "not built yet," treat it as a to-do that closes a real coverage gap, not
decoration.

## 1. Plan and manage an Azure AI solution

| Skill | marola artifact | Status |
|---|---|---|
| Select the right Azure AI service for a scenario | `ARCHITECTURE.md` §5's per-integration writeups (Foundry vs. local Ollama, Azure Maps vs. free Overpass routing) each argue the tradeoff explicitly | Built, documented |
| Plan for a solution's resource requirements (compute, cost tiers) | `ARCHITECTURE.md` §6, `AGENTS.md`'s cost-safety rules | Built |
| Use Azure AI Foundry to explore/deploy models | `azure/llm/AzureFoundryLlmClient.scala` | Code complete, not yet run against a live Foundry account (no Azure resources provisioned — see AGENTS.md phase discipline) |
| Manage costs (budgets, scale-to-zero) | `ARCHITECTURE.md` §6 (Container App scale-to-zero pattern) | Documented, not yet deployed |
| Implement security for AI solutions (managed identity, no hardcoded keys) | Every Azure client in `azure/` authenticates via `azure-identity`'s `DefaultAzureCredential` | Built |
| Monitor an AI solution | `azure/observability/Telemetry.scala` — Application Insights via OpenTelemetry | Code complete, no-op by default |
| Responsible AI: transparency, content safety | `ARCHITECTURE.md` §8/§9 (jellyfish/whale heuristic honesty limitations, known limitations) | Documented; Azure AI Content Safety integration not yet built — real gap, see §4 below. Proposed: MIP-0001 (deterministic bathing-water veto with sample date/location printed) |

## 2. Implement generative AI solutions

| Skill | marola artifact | Status |
|---|---|---|
| Integrate a generative AI model into an app | `core/llm/LlmClient` trait + `local/llm/LocalLlmClient` (Ollama) + `azure/llm/AzureFoundryLlmClient` | Built, local path live-verified end-to-end |
| Prompt engineering | `dspy/compile_recommendation_prompt.py` — `dspy.Signature` + `BootstrapFewShot`-compiled few-shot prompt, not hand-tuned text | Built, compiled artifact checked into `dspy/` |
| Optimize prompts systematically (not just "try wording") | The whole DSPy step exists specifically to cover this — see §2 of `FUTURE-WORK.md` for what was reviewed (kyo-http/kyo-schema) while building it | Built |
| Structured output / function calling | `CompiledPrompt.replay` parses the model's structured summary field; `Reviewer` parses a compact JSON verdict from a second pass | Built, live-verified against Ollama |
| RAG (retrieval-augmented generation) | Not built — marola's factual grounding today is live API data (Overpass, Open-Meteo), not a vector-retrieved corpus | **Gap.** See "Ocean-knowledge grounding" idea below for the concrete way to close this |
| Fine-tuning a model | Not built | **Gap.** See "Ocean-knowledge grounding" idea below |

## 3. Implement agentic solutions

| Skill | marola artifact | Status |
|---|---|---|
| Build an agent that calls tools | `cli/agent/SwimConditionsMcpServer.scala` — exposes `BeachFinder`/`Recommender` as MCP tools over the official Java MCP SDK | Built, live-verified via raw JSON-RPC over stdio |
| Multi-step agentic reasoning (plan → act → observe) | `Recommender.bestPerBeachTomorrow` → `Reviewer.review` is a two-stage pipeline (summarize, then critique/revise) — a real if simple instance of the pattern | Built, live-verified end-to-end with Ollama |
| Agent orchestration frameworks | Not adopted — reviewed `workflows4s`/`decisions4s` (`FUTURE-WORK.md` §5), not a fit yet at this scale | Reviewed, deferred |
| Multi-agent collaboration | Out of scope for AI-103 itself; this is exactly where AI-500 picks up — see `AI-500-MAPPING.md` | Deferred to AI-500 scope by design |

## 4. Implement computer vision solutions

| Skill | marola artifact | Status |
|---|---|---|
| Analyze images with a vision model | `core/vision/VisionClient` trait + `local/vision/LocalVisionClient` (local multimodal Ollama) + `azure/vision/AzureVisionClient` | Code complete; local path exercisable via `Main`'s `--analyze-photo` flag |
| Azure AI Vision specifically | `azure/vision/AzureVisionClient.scala` | Code complete, not yet run against a live Azure account |
| Real use case, not a toy | Sighting reports (jellyfish/whale photos submitted by users) feed `SightingStore`, which is designed to eventually calibrate the heuristics in `Swimability.scala` (`ARCHITECTURE.md` §8) | Built (storage + client), the calibration feedback loop itself is future work |

## 5. Implement natural language processing solutions

| Skill | marola artifact | Status |
|---|---|---|
| Text analysis / entity extraction | Not built directly — the closest analog is `Reviewer`'s JSON-extraction-from-prose fallback (`extractJsonObject`), which is a narrow, single-purpose version of the same problem | **Partial gap.** See "Ocean-knowledge grounding" idea below for where a real text-analysis use case (extracting structured info from ocean-safety bulletins/papers) would live |
| Azure AI Language service | Not integrated | **Gap** — no current product need identified; would be a good fit for the RAG/grounding work below (summarizing/classifying source documents before they go into a retrieval index) |
| Translation | Not built, not currently a product need | Not planned |

## Where marola is honestly incomplete for full AI-103 coverage

Being direct about this rather than padding the tables above: the two real gaps are **RAG/fine-tuning**
(generative AI domain) and **a first-class text-analysis use case** (NLP domain). Both point at the
same idea, developed in `FUTURE-WORK.md` §9 ("Ocean-knowledge grounding: RAG and fine-tuning over
marine science") — grounding marola's summaries in real oceanographic/marine-safety literature
rather than only live sensor data. Building that closes both gaps with one coherent feature instead
of two disconnected checkbox exercises, and it's a genuine product improvement (a jellyfish-sting
first-aid answer sourced from an actual marine biology paper beats an LLM's unsourced guess).
