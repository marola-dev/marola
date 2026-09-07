<h1 align="center">🌊 marola</h1>

<p align="center"><b>marola — the ocean intelligence layer.</b><br/>
The ocean near you: conditions, official bathing-water quality per sampling point, tides,
jellyfish and whale odds, and a grounded "ask the ocean" — first case, the best hour tomorrow to
swim, all on your own machine with a free model (Scala 3 / Kyo / Ollama), sourced or clearly
labelled, never invented.</p>

<p align="center">
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://github.com/h0ffmann/marola/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
<a href="https://github.com/h0ffmann/marola/actions/workflows/site.yml"><img src="https://github.com/h0ffmann/marola/actions/workflows/site.yml/badge.svg" alt="site (build + deploy)" /></a>
<!-- Aggregated statement coverage: ci.yml measures it (sbt-scoverage) on pushes to main and writes this shields.io endpoint JSON to Pages via the site-data branch. -->
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fcoverage%2Flatest.json" alt="coverage" /></a>
<a href="https://marola.dev/"><img src="https://img.shields.io/badge/live_map-marola.dev-0b6e99?logo=leaflet&logoColor=white" alt="live map" /></a>
<img src="https://img.shields.io/badge/Scala-3.9_LTS-DC322F?logo=scala&logoColor=white" alt="Scala 3.9" />
<img src="https://img.shields.io/badge/JDK-25-007396?logo=openjdk&logoColor=white" alt="JDK 25" />
<img src="https://img.shields.io/badge/runs_on-Ollama_%C2%B7_llama3.2-000000?logo=ollama&logoColor=white" alt="Ollama" />
<img src="https://img.shields.io/badge/agents-MCP_tools-000000?logo=modelcontextprotocol&logoColor=white" alt="MCP" />
<a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT" /></a>
</p>

**Live map:** [marola.dev](https://marola.dev/) — every beach around
Florianópolis and Rio, ranked for today and tomorrow, water quality, tides and the hour slider;
rebuilt every 3 hours and on every relevant merge to `main` ([`site.yml`](./.github/workflows/site.yml),
[MIP-0005](./docs/mips/MIP-0005-map-and-static-site.md)). Live data decides the numbers,
deterministic rules decide anything safety-related, a sourced corpus decides what the model may
say, and a second model reviews the first — the reasoning behind each choice: [`PHILOSOPHY.md`](./PHILOSOPHY.md).

## What you get

- **Best hour tomorrow, per beach** — OpenStreetMap beaches, Open-Meteo sea/weather/tide forecasts, a 0-100 swimability score with the reasons, never at night.
- **Official bathing-water quality, per sampling point** — Santa Catarina's IMA feed; unfit water zeroes the score in code, not a prompt.
- **Tides, swell, wind, UV, jellyfish and whale odds**, and a sourced "did you know?" about the sea in front of you.
- **Ask the ocean** — local RAG with `[n]` citations; off-corpus questions get an "unsourced" label instead of a refusal.

## Run it in five minutes

```bash
nix develop                                                        # JDK 25, sbt, just, ollama — see flake.nix
just run -- --brief --lat -27.6733 --lon -48.4700                  # fastest path: ranked list, no LLM
just ollama-up                                                     # starts `ollama serve`, pulls llama3.2 if missing
just run -- --summarize --lat -27.6733 --lon -48.4700              # ranked list + top-pick block + LLM summary + review
just ask "what should I do if I get caught in a rip current?"      # grounded answer with sources
just site-build floripa && just site-serve                         # the map, locally, at :8000
```

No Telegram token, no Azure account, no API key needed for any of the above. Full walkthrough with
real output: [`docs/RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md). Docker instead of Nix/sbt/Ollama
([MIP-0008](./docs/mips/MIP-0008-docker-images-and-smoke-test.md)):

```bash
docker compose --profile ollama run --rm marola --summarize --lat -27.6733 --lon -48.4700
```

## The six pluggable integrations: local default, Azure opt-in

Every integration is a trait with a free local implementation as the default and an Azure
implementation that's opt-in per env var — never a package deal. Full detail, including what's
verified live vs. written-not-run: [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) §5.

| Capability | Local default → Azure opt-in | Switch |
|---|---|---|
| Query synthesis | Ollama chat completion → Foundry/Azure OpenAI (`azure-identity`) | `MAROLA_LLM_PROVIDER=azure` |
| Beach distance | Haversine → Azure Maps real driving distance | `AZURE_MAPS_SUBSCRIPTION_KEY` |
| Agentic tool access | MCP server over stdio (no Azure variant) | n/a |
| Sighting reports | JSON-lines file → Cosmos DB container | `MAROLA_SIGHTING_STORE_PROVIDER=azure` |
| Photo analysis | Multimodal Ollama (`llava`) → Azure AI Vision | `MAROLA_VISION_PROVIDER=azure` |
| Observability | Off, or OTLP traces into local MLflow → Application Insights | `MAROLA_TRACES=off\|mlflow\|azure` |

**Next:** a Telegram bot, then the rest of the sea (surf, diving, fishing) as new scoring functions
over the same data — roadmap: [`docs/mips/README.md`](./docs/mips/README.md), [`docs/FUTURE-WORK.md`](./docs/FUTURE-WORK.md) §1.

## Documentation

**Start here:** [`docs/RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md). Everything else lives under `docs/`:

| Doc | What it covers |
|---|---|
| [`ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | The pipeline, the six pluggable local/Azure integrations, verified-live vs. written-not-run |
| [`RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md) / [`TELEGRAM-SETUP.md`](./docs/TELEGRAM-SETUP.md) | Run it now with Ollama; registering the bot and Azure-Foundry credentials |
| [`FUTURE-WORK.md`](./docs/FUTURE-WORK.md) / [`EFFECTS-MAP.md`](./docs/EFFECTS-MAP.md) | Design sketches, reviewed-not-adopted libraries; a Scala/FP-purity review |
| [`AI-103-MAPPING.md`](./docs/AI-103-MAPPING.md) / [`AI-500-MAPPING.md`](./docs/AI-500-MAPPING.md) | Exam domain coverage — AI-103 done, AI-500 (multi-agent) a design target |
| [`SKILLS.md`](./docs/SKILLS.md) / [`AGENT-SKILLS.md`](./docs/AGENT-SKILLS.md) | A skills roadmap for humans; which Claude Code skills to use here |
| [`AGENT-FRAMEWORKS-SURVEY.md`](./docs/AGENT-FRAMEWORKS-SURVEY.md) | Multi-agent frameworks: Python ideas, JVM/Scala libraries, where Apache Pekko fits |
| [`benchmarks/`](./docs/benchmarks/2026-09-05.md) / [`mips/`](./docs/mips/README.md) | Kept benchmark runs; numbered design docs written before a feature is built |
| [`FABLE_REVIEW.md`](./docs/FABLE_REVIEW.md) / [`DEV-FLOW.md`](./docs/DEV-FLOW.md) | Code review at the initial import; the dev loop end to end, MIP → PRs → merge |

If you're an AI coding agent picking this repo up: read [`AGENTS.md`](./AGENTS.md) first.

## Contributing

Small PRs, one topic each; non-trivial changes start as a MIP under `docs/mips/`; every commit
carries the `Co-Authored-By: Claude <noreply@anthropic.com>` trailer and every PR body ends with a
`Cost:` line. AI agents are first-class contributors here and follow `AGENTS.md` like anyone else.
Full guide: [`CONTRIBUTING.md`](./CONTRIBUTING.md). Please also read the
[Code of Conduct](./CODE_OF_CONDUCT.md) and, for a vulnerability, [`SECURITY.md`](./SECURITY.md).

## License

[MIT](./LICENSE) — © 2026 Matheus Hoffmann.
