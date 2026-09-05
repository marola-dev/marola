# marola

A Telegram assistant that answers "what's the best hour tomorrow to swim nearby?" — real nearby
beach discovery (OpenStreetMap), live sea/weather conditions (Open-Meteo), a jellyfish/whale
heuristic, an LLM-generated summary reviewed by a second LLM pass, all runnable **entirely locally
with a free Ollama model, zero Azure account needed** — with Azure Maps/Foundry/Cosmos DB/Vision/
Application Insights available as opt-in upgrades per integration, never a package deal. Also built
as hands-on coverage of every [AI-103: Developing AI Apps and Agents on Azure](https://learn.microsoft.com/en-us/credentials/certifications/azure-ai-apps-and-agents-developer-associate/)
exam domain, and a design target for [AI-500: Designing and Implementing Multi-Agent AI Solutions](https://learn.microsoft.com/en-us/credentials/certifications/)
(for which AI-103 is the mandatory prerequisite) — see [`docs/AI-103-MAPPING.md`](./docs/AI-103-MAPPING.md) and
[`docs/AI-500-MAPPING.md`](./docs/AI-500-MAPPING.md).

**Start here:** [`docs/RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md) — a step-by-step guide to running the
whole pipeline with a small, free local Ollama model. Full doc set, all centralized under `docs/`:

| Doc | What it covers |
|---|---|
| [`ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | The pipeline, the six pluggable local/Azure integrations, what's verified live vs. written-not-run |
| [`RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md) | Run it now, with Ollama, no Telegram/Azure |
| [`TELEGRAM-SETUP.md`](./docs/TELEGRAM-SETUP.md) | Registering the bot, local-dev and Azure-Foundry credential paths |
| [`FUTURE-WORK.md`](./docs/FUTURE-WORK.md) | Design sketches (multi-activity support, ocean-knowledge grounding), reviewed-not-adopted libraries, harness ideas |
| [`EFFECTS-MAP.md`](./docs/EFFECTS-MAP.md) | A Scala/FP-purity review — what's pure, what's effectful, what's hidden |
| [`AI-103-MAPPING.md`](./docs/AI-103-MAPPING.md) | AI-103 exam domain coverage, including honest gaps |
| [`AI-500-MAPPING.md`](./docs/AI-500-MAPPING.md) | AI-500 (multi-agent) domain coverage — a design target |
| [`SKILLS.md`](./docs/SKILLS.md) | A skills roadmap — what to practice, in order, using marola as the vehicle |
| [`SCALA3-JDK-REVIEW.md`](./docs/SCALA3-JDK-REVIEW.md) | Scala 3 / JDK 21-25 features reviewed against this code — what to adopt, in what order |
| [`benchmarks/`](./docs/benchmarks/2026-09-05.md) | Kept benchmark runs: marola vs. a plain prompt on ocean questions, and what moved the numbers |
| [`mips/`](./docs/mips/README.md) | Marola Improvement Proposals — numbered design docs written before a feature is built (`/mip` skill) |
| [`FABLE_REVIEW.md`](./docs/FABLE_REVIEW.md) | Code and documentation review at the initial import — ranked findings with file:line references |

If you're an AI coding agent (Claude Code, etc.) picking this repo up: read
[`AGENTS.md`](./AGENTS.md) first.

## Stack

- **Language:** Scala 3.9 (LTS), JDK 25 (required by Kyo 1.0.0-RC5, not just Scala 3.9 itself — see AGENTS.md)
- **Effects:** [Kyo](https://getkyo.io) (`kyo-core`, `kyo-direct`, `kyo-combinators`)
- **Local-first:** [Ollama](https://ollama.com) for the default LLM/vision backend — no Azure account needed for anything
- **Azure AI (all optional, per-integration):** Foundry Java SDK, Azure Maps, Cosmos DB, Azure AI Vision, Azure Monitor/Application Insights, `azure-identity` (managed identity, no API keys)
- **Agent tools:** official [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) — marola exposes its pipeline as MCP tools (`cli/src/main/scala/marola/agent/`)
- **Local RAG + fine-tuning scaffold:** `knowledge/` corpus embedded by Ollama, grounded Q&A with citations (`just ask`); `finetune/` Modelfile variant + QLoRA recipe — see `docs/ARCHITECTURE.md` §5h
- **Offline prompt optimization:** [DSPy](https://dspy.ai) (Python — see `dspy/`), compiled once into portable JSON artifacts the Scala service loads; never a runtime dependency
- **Dev environment:** Nix flake (works on Ubuntu, not NixOS-specific)
- **Task runner:** [`just`](https://github.com/casey/just)
- **Build:** sbt multi-project — `core`/`local`/`azure`/`cli` (see `docs/FUTURE-WORK.md` §7.3) so the local-only path carries zero Azure SDK dependency

## Quick start

```bash
# 1. Enter the reproducible dev shell (installs JDK 25, sbt, just, ollama, python3, az cli, gh — see flake.nix)
nix develop
# or, with direnv installed:
direnv allow

# 2. Pull a small, free local model and start Ollama (see docs/RUN-LOCALLY.md for the full guide)
ollama pull llama3.2:1b
ollama serve &

# 3. Build & test
just build
just test

# 4. Run marola locally — no Telegram, no Azure
export MAROLA_LOCAL_LLM_MODEL=llama3.2:1b
just run -- --summarize
```

## Repo layout

```
core/                          Pure pipeline logic, shared HTTP/JSON, the LlmClient/VisionClient/
                                SightingStore traits — no Azure reference anywhere in this module
local/                          Ollama-backed implementations — zero Azure SDK dependency
azure/                          Optional Azure integrations (Foundry, Maps, Cosmos DB, Vision,
                                Application Insights) — all opt-in, none required
cli/                            Main, AppConfig, the MCP tool server — depends on all three above
dspy/                           Offline DSPy compile step (Python) — see dspy/README.md
build.sbt                      sbt multi-project build for all four modules
.github/workflows/ci.yml           Compile/test/format checks on every push
.github/workflows/marola-e2e.yml   Live E2E test — manual trigger only, never automatic
docs/                           All project documentation — see the table above
```

## AI-103 / AI-500 coverage

[`docs/AI-103-MAPPING.md`](./docs/AI-103-MAPPING.md) maps every AI-103 skill area to what's actually
built, including an honest list of remaining gaps. [`docs/AI-500-MAPPING.md`](./docs/AI-500-MAPPING.md)
does the same for AI-500 (multi-agent solutions; AI-103 is its mandatory prerequisite) as a design target.
