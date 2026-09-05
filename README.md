<h1 align="center">🌊 marola</h1>

<p align="center"><b>When LLMs meet the ocean.</b><br/>
Local-first ocean intelligence for open-water swimmers: the best hour to swim tomorrow, official
bathing-water quality per sampling point, tides, jellyfish and whale odds, and a grounded
"ask the ocean" — all on your own machine with a free model, sourced or clearly labelled, never invented.</p>

<p align="center">
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://github.com/h0ffmann/marola/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
<a href="https://github.com/h0ffmann/marola/actions/workflows/site.yml"><img src="https://github.com/h0ffmann/marola/actions/workflows/site.yml/badge.svg" alt="site (build + deploy)" /></a>
<a href="https://h0ffmann.github.io/marola/"><img src="https://img.shields.io/badge/live_map-h0ffmann.github.io%2Fmarola-0b6e99?logo=leaflet&logoColor=white" alt="live map" /></a>
<img src="https://img.shields.io/badge/Scala-3.9_LTS-DC322F?logo=scala&logoColor=white" alt="Scala 3.9" />
<img src="https://img.shields.io/badge/JDK-25-007396?logo=openjdk&logoColor=white" alt="JDK 25" />
<img src="https://img.shields.io/badge/runs_on-Ollama_%C2%B7_llama3.2-000000?logo=ollama&logoColor=white" alt="Ollama" />
<img src="https://img.shields.io/badge/agents-MCP_tools-000000?logo=modelcontextprotocol&logoColor=white" alt="MCP" />
<a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT" /></a>
</p>

**Live map:** [h0ffmann.github.io/marola](https://h0ffmann.github.io/marola/) — every beach around
Florianópolis and Rio, ranked for today and tomorrow, water quality per sampling point, tides and
the hour slider; rebuilt every 3 hours and on every merge to `main` that touches the page or the
pipeline ([`site.yml`](./.github/workflows/site.yml), [MIP-0005](./docs/mips/MIP-0005-map-and-static-site.md)).

marola (Portuguese for a small, gentle wave) takes the two things an LLM is bad at on its own —
knowing what the sea is doing *right now* and not making things up about it — and fixes both.
Live data decides the numbers; deterministic rules decide anything safety-related; a curated,
sourced corpus decides what the model may say; a second model reviews the first. The LLM does the
one thing it is good at: turning all that into a sentence you'd actually read on the sand.

## What you get

- **Best hour tomorrow, per beach near you** — OpenStreetMap beaches, Open-Meteo sea/weather/tide
  forecasts, a 0-100 swimability score with the reasons, never at night.
- **Official bathing-water quality, per sampling point** — Santa Catarina's IMA feed matched to
  each beach; unfit water zeroes the score, and that rule is code, not a prompt.
- **Tides, swell, period, wind, UV, jellyfish likelihood, whale-spotting odds** for the top pick,
  and a one-line ranked list for the rest.
- **Ask the ocean** — local RAG over a sourced marine corpus with `[n]` citations; off-corpus
  questions get a visible "unsourced" label instead of a refusal.
- **A sourced "did you know?"** about the sea in front of you, rotated daily, never touched by the LLM.
- **Agent-ready** — the pipeline is exposed as MCP tools for Claude Desktop or any MCP client.
- **Everything runs locally** — Ollama + `llama3.2`, no account, no key, no cloud.

```
 1. [ 55/100] Praia da Joaquina      (4.6km)  Sun 6 Sep, 10:00  |  water: PRÓPRIA (1/1 pts, 25 Aug)  |  19.0°C, 27km/h, 1.3m  |  jellyfish: Low
 6. [ 35/100] Praia do Campeche      (2.1km)  Sun 6 Sep, 08:00  |  water: 4/5 PRÓPRIA — avoid Ponto 73 (25 Aug)  |  18.8°C, 28km/h, 1.4m  |  jellyfish: Low

Top pick — Praia da Joaquina, Sun 6 Sep, 10:00-11:00
  Water quality   PRÓPRIA (1/1 pts, 25 Aug) · Ponto 33 · Source: IMA/SC
  Sea             19.0°C, waves 1.3m every 6s from the S, swell 0.8m/6s
  Tide            low 05:00 (-0.1m), high 13:00 (+0.7m), low 18:00 (+0.3m)

Reviewer (score 75/100, verdict: approve): Praia da Joaquina is a great spot for swimming with mild conditions and low jellyfish risk.
🐋 Sea life: Humpback whales (baleia-jubarte) travel up the Brazilian coast … [source: https://en.wikipedia.org/wiki/Humpback_whale]
```

## Why it's built this way

| The usual LLM failure | What marola does instead |
|---|---|
| Doesn't know today's sea | Every number comes from a live call: Overpass, Open-Meteo, the IMA bathing-water feed. |
| Confidently wrong about safety | Water vetoes, darkness, rough-sea deductions are deterministic Scala in `scoring/`, unit-tested. |
| Invents facts | Answers are grounded on a sourced corpus with citations, or labelled "unsourced". |
| One model grading itself | A second, DSPy-compiled reviewer pass scores and can rewrite the summary. |
| Needs a cloud account to try | `nix develop && just ollama-up && just run` — that's the whole setup. |
| Regressions only caught in production | The full pipeline replays recorded real API responses in CI, no network, no Ollama. |

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

| Capability | Local default | Azure opt-in | Switch |
|---|---|---|---|
| Query synthesis | Ollama-compatible chat completion | Foundry/Azure OpenAI via `azure-identity` | `MAROLA_LLM_PROVIDER=azure` |
| Beach distance | Haversine ("as the crow flies") | Azure Maps real driving distance | `AZURE_MAPS_SUBSCRIPTION_KEY` |
| Agentic tool access | MCP server over stdio | *(same server; HTTP/SSE variant not built)* | n/a |
| Sighting reports | JSON-lines file | Cosmos DB container | `MAROLA_SIGHTING_STORE_PROVIDER=azure` |
| Photo analysis | Multimodal Ollama model (`llava`) | Azure AI Vision Image Analysis | `MAROLA_VISION_PROVIDER=azure` |
| Observability | Off, or OTLP traces into local MLflow | Application Insights via OpenTelemetry | `MAROLA_TRACES=off\|mlflow\|azure` |

## Where it's going

**Next:** a **Telegram bot** ([MIP-0002](./docs/mips/MIP-0002-telegram-bot-phase-1.md)) with fast
replies ([MIP-0003](./docs/mips/MIP-0003-fast-replies-caching-and-fan-out.md)) and daily digests
([MIP-0004](./docs/mips/MIP-0004-daily-digest-subscriptions-and-reach.md)). **Then:** the rest of
the sea — surf, diving, fishing, hazard alerts — as new scoring functions over the same live data,
never a new app; see [`docs/FUTURE-WORK.md`](./docs/FUTURE-WORK.md) §1. Non-trivial changes start
as a numbered proposal under [`docs/mips/`](./docs/mips/README.md).

## Documentation

**Start here:** [`docs/RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md). Everything else lives under `docs/`:

| Doc | What it covers |
|---|---|
| [`ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | The pipeline, the six pluggable local/Azure integrations, what's verified live vs. written-not-run |
| [`RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md) | Run it now, with Ollama, no Telegram/Azure |
| [`TELEGRAM-SETUP.md`](./docs/TELEGRAM-SETUP.md) | Registering the bot, local-dev and Azure-Foundry credential paths |
| [`FUTURE-WORK.md`](./docs/FUTURE-WORK.md) | Design sketches, reviewed-not-adopted libraries, harness ideas |
| [`EFFECTS-MAP.md`](./docs/EFFECTS-MAP.md) | A Scala/FP-purity review — what's pure, what's effectful, what's hidden |
| [`AI-103-MAPPING.md`](./docs/AI-103-MAPPING.md) | AI-103 exam domain coverage, including honest gaps |
| [`AI-500-MAPPING.md`](./docs/AI-500-MAPPING.md) | AI-500 (multi-agent) domain coverage — a design target |
| [`SKILLS.md`](./docs/SKILLS.md) | A skills roadmap — what to practice, in order, using marola as the vehicle |
| [`AGENT-FRAMEWORKS-SURVEY.md`](./docs/AGENT-FRAMEWORKS-SURVEY.md) | Multi-agent frameworks: Python ideas, JVM/Scala libraries, where Apache Pekko fits |
| [`AGENT-SKILLS.md`](./docs/AGENT-SKILLS.md) | Which Claude Code skills to use here |
| [`benchmarks/`](./docs/benchmarks/2026-09-05.md) | Kept benchmark runs: marola vs. a plain prompt on ocean questions |
| [`mips/`](./docs/mips/README.md) | Marola Improvement Proposals — numbered design docs written before a feature is built |
| [`FABLE_REVIEW.md`](./docs/FABLE_REVIEW.md) | Code and documentation review at the initial import — ranked findings |
| [`DEV-FLOW.md`](./docs/DEV-FLOW.md) | The development loop end to end: MIP → tasks → stacked PRs → review → merge |

If you're an AI coding agent picking this repo up: read [`AGENTS.md`](./AGENTS.md) first.

## Stack

Scala 3.9 (LTS) on JDK 25, effects via [Kyo](https://getkyo.io), local-first on
[Ollama](https://ollama.com), Azure AI (Foundry, Maps, Cosmos DB, Vision, Monitor) as opt-in
per-integration upgrades, agent tools via the official
[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk), offline prompt optimization via
[DSPy](https://dspy.ai) (`dspy/`, never a runtime dependency), a Nix flake dev shell, and
[`just`](https://github.com/casey/just) as the task runner. sbt multi-project build —
`core`/`local`/`azure`/`cli` — so the local-only path carries zero Azure SDK dependency. Details
and the reasoning behind each choice: [`PHILOSOPHY.md`](./PHILOSOPHY.md).

## Contributing

Small PRs, one topic each; non-trivial changes start as a MIP under `docs/mips/`; every commit
carries the `Co-Authored-By: Claude <noreply@anthropic.com>` trailer and every PR body ends with a
`Cost:` line. AI agents are first-class contributors here and follow `AGENTS.md` like anyone else.
Full guide: [`CONTRIBUTING.md`](./CONTRIBUTING.md). Please also read the
[Code of Conduct](./CODE_OF_CONDUCT.md) and, for a vulnerability, [`SECURITY.md`](./SECURITY.md).

## License

[MIT](./LICENSE) — © 2026 Matheus Hoffmann.
