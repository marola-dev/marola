# Awesome agentic engineering

[![Awesome](https://awesome.re/badge.svg)](https://awesome.re)

A curated list of tools, frameworks, and practices for building AI agents and agentic pipelines:
agent orchestration, the Model Context Protocol (MCP), prompt compilation, critic/reviewer
patterns, spec-driven agent development, local-first LLM tooling, and cost-tracked AI-agent
workflows.

This list follows the [`sindresorhus/awesome`](https://github.com/sindresorhus/awesome) convention
(badge, Contents, categorized entries, Contributing, License), verified live against that repo's
own README and `pull_request_template.md` on 2026-09-07. See `docs/MIPs/MIP-0043-awesome-
agentic-engineering-list.md` §4.1 for what was checked. It is maintained from
[marola](https://github.com/h0ffmann/marola), the ocean-intelligence Telegram assistant this repo
belongs to. See the [Top 10 repos most similar to marola](#top-10-github-repos-most-similar-to-marola)
section below for how marola itself fits this landscape.

## Contents

- [Agent Frameworks & Orchestration](#agent-frameworks--orchestration)
- [MCP Tooling](#mcp-tooling)
- [DSPy & Prompt Compilation](#dspy--prompt-compilation)
- [Critic / Reviewer-Pattern & Multi-Agent Pipelines](#critic--reviewer-pattern--multi-agent-pipelines)
- [Spec / RFC-Driven Agent Dev-Loops](#spec--rfc-driven-agent-dev-loops)
- [Local-First / Ollama-Based Agents](#local-first--ollama-based-agents)
- [Cost- and Usage-Tracked Agent Development](#cost--and-usage-tracked-agent-development)
- [Top 10 GitHub repos most similar to marola](#top-10-github-repos-most-similar-to-marola)

## Agent Frameworks & Orchestration

- [LangChain](https://github.com/langchain-ai/langchain) - A widely used framework for building
  LLM-powered applications, chains, and agents across many model providers.
- [LangGraph](https://github.com/langchain-ai/langgraph) - A graph-based runtime for building
  stateful, multi-step, multi-agent LLM applications with explicit control flow.
- [AutoGen](https://github.com/microsoft/autogen) - Microsoft's framework for building multi-agent
  conversational AI applications, including agent-to-agent collaboration patterns.
- [CrewAI](https://github.com/crewAIInc/crewAI) - A framework for orchestrating role-playing,
  autonomous AI agents that collaborate on tasks as a "crew."
- [OpenHands](https://github.com/All-Hands-AI/OpenHands) - An open-source platform for AI agents
  that can modify code, run commands, browse the web, and call APIs to perform software-engineering
  tasks.
- [llm4s](https://github.com/llm4s/llm4s) - A Scala framework for LLM applications emphasizing type
  safety and functional programming, with multi-provider clients, agent/tool-calling, RAG, and
  observability on the JVM: one of a small number of Scala/JVM-native (not Java-first) agent
  frameworks.

## MCP Tooling

- [Model Context Protocol servers](https://github.com/modelcontextprotocol/servers) - The official
  reference-implementation repo for MCP servers (Everything, Fetch, Filesystem, Git, Memory,
  Sequential Thinking, Time), the canonical pattern any app exposing its own capabilities as an
  MCP tool server follows.
- [mcp-client-for-ollama](https://github.com/jonigl/mcp-client-for-ollama) - A terminal UI
  connecting local Ollama models to MCP servers, with agent mode, multi-server support, and
  human-in-the-loop controls, entirely local.
- [open-meteo-mcp](https://github.com/cmer81/open-meteo-mcp) - An MCP server exposing Open-Meteo's
  weather APIs, including its Marine Weather endpoint (wave height/period/direction, sea surface
  temperature), to LLMs.

## DSPy & Prompt Compilation

- [DSPy](https://github.com/stanfordnlp/dspy) - Stanford NLP's framework for programming (not
  manually prompting) language models, with optimizers that compile modules into tuned prompts and
  few-shot demonstrations, decoupling program logic from prompt text.

## Critic / Reviewer-Pattern & Multi-Agent Pipelines

- [Self-Refine](https://github.com/madaan/self-refine) - The reference implementation of the
  "Self-Refine" paper: an LLM generates output, produces feedback/critique on its own output, then
  revises: an explicit generate → feedback → refine loop, evaluated across acronym generation,
  dialogue, code readability, and math.

## Spec / RFC-Driven Agent Dev-Loops

- [Spec Kit](https://github.com/github/spec-kit) - GitHub's open-source toolkit for spec-driven
  development with coding agents: specify → plan → tasks → implement. Writing what to build before
  building it, as a structured workflow rather than a single prompt.

## Local-First / Ollama-Based Agents

- [Ollama](https://github.com/ollama/ollama) - Run large language models locally, with a simple CLI
  and API: the local-model runtime underlying most "no cloud account required" agent stacks.
- [mcp-client-for-ollama](https://github.com/jonigl/mcp-client-for-ollama) - See
  [MCP Tooling](#mcp-tooling) above: Ollama models driving MCP tool calls, fully local.

## Cost- and Usage-Tracked Agent Development

- [ccusage](https://github.com/ryoppippi/ccusage) - A CLI that parses local Claude Code/Codex/
  OpenCode session logs into daily/weekly/session cost and token reports, making AI-agent
  development spend visible and attributable per unit of work rather than left implicit.

## Top 10 GitHub repos most similar to marola

Hand-researched and verified live on 2026-09-07 (each repo's real GitHub page fetched; star counts
approximate as of that date; see `docs/MIPs/MIP-0043-awesome-agentic-engineering-list.md` for the
MIP behind this doc). "Similar" is scored on three axes: **domain** (environmental/ocean/weather
data agents), **architecture** (local-first Ollama multi-agent pipelines, critic/reviewer-pattern
agents, DSPy-based prompt compilation, MCP-server-exposed apps), and **philosophy** (design-doc-
before-code culture, AI-agent-run/AI-native repos, cost-tracked agent development). Each entry
states explicitly which axis(es) it matches and why.

1. **[stanfordnlp/dspy](https://github.com/stanfordnlp/dspy)** (~37.8k★): Stanford NLP's framework
   for programming (not manually prompting) LMs, with optimizers that compile modules into tuned
   prompts/few-shot demos. **Architecture axis**: this is the exact upstream project marola's
   offline `dspy/` compile step is built on: the same "compile once, replay the artifact at
   runtime" idea marola applies by loading a JSON artifact from Scala instead of keeping a runtime
   DSPy dependency.
2. **[modelcontextprotocol/servers](https://github.com/modelcontextprotocol/servers)** (~90.1k★):
   the official reference-implementation repo for MCP servers. **Architecture axis**: marola's
   `cli/` module exposes its own capabilities as an MCP tool server (`just mcp-server`); this repo
   is the canonical pattern/spec marola's server follows.
3. **[getkyo/kyo](https://github.com/getkyo/kyo)** (~812★): a Scala 3 toolkit built on algebraic
   effects (the `A < S` pending type), where an unhandled error or undeclared effect fails to
   compile, targeting JVM/JS/Native/Wasm. **Architecture axis**: this is literally the pre-1.0
   effects library marola is built on (`core`/`cli` use Kyo directly), not just similar: it's the
   same dependency.
4. **[cmer81/open-meteo-mcp](https://github.com/cmer81/open-meteo-mcp)** (~68★): an MCP server
   exposing Open-Meteo's weather APIs, including its Marine Weather endpoint (wave height/period/
   direction, sea surface temperature). **Domain + architecture axes**: same data source marola
   consumes (Open-Meteo) for the same marine/sea-conditions purpose, wrapped in the same MCP-server
   pattern marola itself uses to expose its own tools.
5. **[github/spec-kit](https://github.com/github/spec-kit)** (~133.8k★): GitHub's own toolkit for
   spec-driven development with coding agents: specify → plan → tasks → implement. **Philosophy
   axis**: near-exact structural match to marola's MIP-driven, design-doc-before-code culture
   (`docs/MIPs/`, the `/marola-devkit:mip`/`mip-tasks` skills, `docs/3-Working-on-the-repo/DEV-FLOW.md`'s idea→MIP→tasks→PR loop); both
   make a written spec/plan a mandatory gate before implementation.
6. **[jonigl/mcp-client-for-ollama](https://github.com/jonigl/mcp-client-for-ollama)** (~816★): a
   terminal UI connecting local Ollama models to MCP servers, with agent mode, multi-server
   support, and human-in-the-loop controls, entirely local. **Architecture axis**: directly
   parallels marola's local-first design: Ollama as the model backend plus MCP as the tool-
   exposure layer, with zero required cloud account.
7. **[llm4s/llm4s](https://github.com/llm4s/llm4s)** (~257★): a Scala framework for LLM
   applications emphasizing type safety and FP, with multi-provider clients, agent/tool-calling,
   RAG, and observability on the JVM. **Architecture axis**: one of the very few Scala/JVM-native
   (not JVM-via-Java-first) LLM agent frameworks, the same ecosystem niche marola's Scala 3 + Kyo
   pipeline occupies (see `docs/4-Research-and-plans/AGENT-FRAMEWORKS-SURVEY.md` §1.2 and MIP-0012).
8. **[ryoppippi/ccusage](https://github.com/ryoppippi/ccusage)** (~18.4k★): a CLI that parses
   local Claude Code/Codex/OpenCode session logs into daily/weekly/session cost and token reports.
   **Philosophy axis**: matches marola's `Cost:` git-trailer discipline and `just cost-split`/
   `cost-fill` tooling; both are explicit, tool-enforced attempts to make AI-agent development
   spend visible and attributable per unit of work rather than left implicit.
9. **[madaan/self-refine](https://github.com/madaan/self-refine)** (~820★): the reference
   implementation of the "Self-Refine" paper: an LLM generates output, produces feedback/critique
   on its own output, then revises. **Architecture axis**: the closest published academic match to
   marola's summarizer-then-Reviewer two-pass pattern, where a second LLM pass explicitly grades/
   corrects the first.
10. **[open-meteo/open-meteo](https://github.com/open-meteo/open-meteo)** (~6.2k★): the
    open-source weather API/service itself (AGPLv3, free for non-commercial use), aggregating
    NOAA/DWD/ECMWF/JMA models, with a dedicated Marine Forecast API. **Domain axis**: the actual
    upstream data provider marola's sea/weather-conditions pipeline is built on, and its "free for
    non-commercial use, self-hostable, zero mandatory account" ethos mirrors marola's own
    "zero-cloud-account-required, everything opt-in" design stance.

Ruled out during research (kept here for honesty, not padding): `calimero-network/ai-code-reviewer`
(real, ~9★, but a parallel multi-model *voting* reviewer, not a sequential generate→critique
pattern; `self-refine` is the closer match); `Jellyfish-AI/jellyfish-mcp` (real, but an MCP server
for the Jellyfish.ai engineering-analytics SaaS: a naming coincidence, not a marine-jellyfish
domain match); no public GitHub repo was found combining "marine-jellyfish detection + Telegram bot
+ LLM agent" as one verifiable project, so none was forced into the list.

## How this list is kept updated

`scripts/awesome_agentic_digest.py` queries the real GitHub Search API
(`api.github.com/search/repositories`, no auth needed for reasonable unauthenticated use) across
verified agentic-engineering topics (`topic:agents`, `topic:llm-agents`, `topic:ai-agents`,
`topic:mcp`, `topic:multi-agent-systems`), sorted by stars and by recent activity, and caches
candidates under `.tmp/awesome_agentic_cache/` (gitignored). **It never writes to this file.** Run
it, review the printed/cached candidates by hand, open the ones that look real and genuinely
relevant, and hand-write an entry in the matching section above, in this doc's own
`- [Title](URL) - Description.` format, the same human-gated "propose, don't auto-merge" pattern
`docs/MIPs/MIP-0041-soft-book-ingestion.md` established for book-derived rule changes. See
`docs/MIPs/MIP-0043-awesome-agentic-engineering-list.md` for the full design and verification.

```
python3 scripts/awesome_agentic_digest.py            # fetch, cache, print a human-readable summary
python3 scripts/awesome_agentic_digest.py --json      # machine-readable summary
python3 scripts/awesome_agentic_digest.py --self-test # no network, run via `just quality-other`
```

## Contributing

This list is maintained inside the [marola](https://github.com/h0ffmann/marola) repository. To
propose an addition: run `scripts/awesome_agentic_digest.py` (above) to find candidates, or suggest
one you already know of directly: either way, a human confirms the repo genuinely exists and fits
a category before it's added, following the [`sindresorhus/awesome`](https://github.com/sindresorhus/awesome)
entry format: `- [Title](URL) - Description.`, description starting uppercase and ending with a
period, describing the project itself rather than the list, no marketing language.

## License

[![CC0](https://licensebuttons.net/p/zero/1.0/88x31.png)](https://creativecommons.org/publicdomain/zero/1.0/)

This list's own curated text (titles, descriptions, categorization) is released under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/), following the
[`sindresorhus/awesome`](https://github.com/sindresorhus/awesome) convention. The license applies
only to this document's curation text, not to the licenses of the linked projects themselves.
