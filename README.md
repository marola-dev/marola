<h1 align="center">🌊 marola</h1>

<p align="center"><b>marola — the ocean intelligence layer.</b><br/>
The ocean near you: conditions, official bathing-water quality per sampling point, tides,
jellyfish and whale odds, and a grounded "ask the ocean" — first case, the best hour tomorrow to
swim, all on your own machine with a free model (Scala 3 / Kyo / Ollama), sourced or clearly
labelled, never invented.<br/>
Not a weather or surf app with a chatbot bolted on: the score and its safety veto are deterministic
Scala, and the model is on judge duty over that — it interprets and phrases, it never overturns a
veto. The reasoning behind that split: <a href="./PHILOSOPHY.md"><code>PHILOSOPHY.md</code></a>,
"models reasoning over open water, with the deterministic parts kept deterministic."</p>

<p align="center">
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://github.com/h0ffmann/marola/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
<a href="https://github.com/h0ffmann/marola/actions/workflows/site.yml"><img src="https://github.com/h0ffmann/marola/actions/workflows/site.yml/badge.svg" alt="site (build + deploy)" /></a>
<!-- Aggregated statement coverage: ci.yml measures it (sbt-scoverage) on pushes to main and writes this shields.io endpoint JSON to Pages via the site-data branch. -->
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fcoverage%2Flatest.json" alt="Scala statement coverage (sbt-scoverage)" /></a>
<!-- The Python half, measured the only way marola tests Python: statement coverage of scripts/**/*.py while each script's own --self-test runs (there is no pytest suite). scripts/repo_stats.py, same repo-stats job and site-data branch as the badges below. -->
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fpython-coverage.json" alt="Python statement coverage under the scripts' own --self-tests" /></a>
<!-- Same mechanism, ci.yml's repo-stats job (scripts/repo_stats.py): how many of the last main run's steps went green out of the steps that actually ran (skipped ones excluded), and cloc's code-line counts for the four Scala modules and the Python trees. -->
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fci.json" alt="CI steps green on the last main run" /></a>
<img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fscala-loc.json" alt="Scala lines of code" />
<img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fpython-loc.json" alt="Python lines of code" />
<a href="https://marola.dev/"><img src="https://img.shields.io/badge/live_map-marola.dev-0b6e99?logo=leaflet&logoColor=white" alt="live map" /></a>
<img src="https://img.shields.io/badge/Scala-3.9_LTS-DC322F?logo=scala&logoColor=white" alt="Scala 3.9" />
<img src="https://img.shields.io/badge/JDK-25-007396?logo=openjdk&logoColor=white" alt="JDK 25" />
<img src="https://img.shields.io/badge/runs_on-Ollama_%C2%B7_llama3.2-000000?logo=ollama&logoColor=white" alt="Ollama" />
<img src="https://img.shields.io/badge/agents-MCP_tools-000000?logo=modelcontextprotocol&logoColor=white" alt="MCP" />
<a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT" /></a>
</p>

**Live map:** [marola.dev](https://marola.dev/) — every beach around
Florianópolis, Rio de Janeiro and Salvador, ranked for today and tomorrow, water quality, tides
and the hour slider;
rebuilt every 3 hours and on every relevant merge to `main` ([`site.yml`](./.github/workflows/site.yml),
[MIP-0005](./docs/mips/MIP-0005-map-and-static-site.md)). Live data decides the numbers,
deterministic rules decide anything safety-related, a sourced corpus decides what the model may
say, and a second model reviews the first — the reasoning behind each choice: [`PHILOSOPHY.md`](./PHILOSOPHY.md).

<p align="center"><a href="https://marola.dev/"><img src="./docs/img/marola-web-view.png" alt="marola.dev — best hour per beach, ranked, with the water-quality popup for a sampling point" width="720" /></a></p>

```console
$ just run -- --brief --lat -27.6733 --lon -48.4700   # real run, 7 Sep 2026; header lines and the last 2 of 6 beaches trimmed
origin -> lat=-27.6733, lon=-48.4700 (radius 15km, source: --lat/--lon flags)
water quality -> IMA/SC
 1. [ 55/100] Praia da Joaquina      (4.6km away)  best at Tue 8 Sep, 10:00  |  18.3°C sea, 11km/h wind  |  jellyfish: Moderate  |  whale sighting: High  |  choppy (0.6m waves), cold water (18.3°C), some jellyfish likelihood  · facilities: no data
 2. [ 55/100] Praia do Rio Tavares   (2.1km away)  best at Tue 8 Sep, 10:00  |  18.3°C sea, 11km/h wind  |  jellyfish: Moderate  |  whale sighting: High  |  choppy (0.6m waves), cold water (18.3°C), some jellyfish likelihood  · lifeguard post: yes
 3. [ 55/100] Praia do Morro das Pedras (5.1km away)  best at Tue 8 Sep, 10:00  |  17.9°C sea, 13km/h wind  |  jellyfish: Moderate  |  whale sighting: High  |  choppy (0.8m waves), cold water (17.9°C), some jellyfish likelihood  · facilities: no data
 4. [ 40/100] Praia da Armação       (7.9km away)  best at Tue 8 Sep, 11:00  |  18.2°C sea, 15km/h wind  |  jellyfish: Moderate  |  whale sighting: High  |  breezy (15km/h), cold water (18.2°C), some jellyfish likelihood, 2/6 points PRÓPRIA — avoid Em frente à Avenida Antônio Borges dos Santos, n°792, Foz do Rio Sangradouro; Em frente à Rua Francisco Fagundes; Em frente à Rua Maria Emília de Costa, n°62; Em frente à Rua Antônio Aniceto da Costa  · facilities: no data
```

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

No Azure account, no API key needed for any of the above. Full walkthrough with
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

**Next:** the rest of the sea (surf, diving, fishing) as new scoring functions over the same data —
roadmap: [`docs/mips/README.md`](./docs/mips/README.md), [`docs/FUTURE-WORK.md`](./docs/FUTURE-WORK.md) §1.

## Documentation

**Start here:** [`docs/RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md). Everything else lives under `docs/`:

| Doc | What it covers |
|---|---|
| [`PHILOSOPHY.md`](./PHILOSOPHY.md) | Why marola is built the way it is — the three pillars, why agents, why Scala/Nix/`just` |
| [`ARCHITECTURE.md`](./docs/ARCHITECTURE.md) | The pipeline, the six pluggable local/Azure integrations, verified-live vs. written-not-run |
| [`RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md) | Run it now with Ollama, no Azure account needed |
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

## Thanks

marola is a small project standing on a lot of other people's work. Named in rough order of how
much of it marola actually leans on:

**[Kyo](https://getkyo.io/)** — the effect system the whole Scala side is written in
(`kyo-core`, `kyo-direct`, `kyo-combinators`, `kyo-http`). Direct-style `.now`/`defer` is what
lets marola's pipeline read like ordinary code while keeping effects honest at the type level, and
`docs/EFFECTS-MAP.md` exists because Kyo made that boundary worth auditing at all. A pre-1.0
library whose authors have been shipping fast — this repo pins a version and verifies API shapes
against the jar rather than the docs, which is a compliment to the pace, not a complaint.

**[Scala 3](https://www.scala-lang.org/)** and the JVM, **[MUnit](https://scalameta.org/munit/)**
for every test in the repo, **[sbt](https://www.scala-sbt.org/)** and
**[scoverage](https://github.com/scoverage/sbt-scoverage)** for the build and the coverage badge.

**[Ollama](https://ollama.com/)** — the reason marola runs entirely locally with no account and no
key, and the default the whole "local-first, Azure opt-in" design is built around.
**[llama.cpp](https://github.com/ggml-org/llama.cpp)** and ggml for GGUF conversion and
quantization; **[Hugging Face](https://huggingface.co/)** for `transformers`, `peft`, `trl` and
the Hub, and **[SmolLM2](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct)** for the
Apache-2.0 base marola-sea is fine-tuned from. **[DSPy](https://dspy.ai/)** compiles the prompts
that the Scala side replays.

**[OpenStreetMap](https://www.openstreetmap.org/copyright)** contributors and the
**[Overpass API](https://overpass-api.de/)** — every beach, trail and facility on the map is
theirs, under ODbL. **[Open-Meteo](https://open-meteo.com/)** for sea temperature, wind and wave
forecasts, free and without a key. **[Leaflet](https://leafletjs.com/)** draws the map.

**[Nix](https://nixos.org/)** and **[just](https://github.com/casey/just)** make the dev shell
reproducible and the commands memorable; **[ruff](https://docs.astral.sh/ruff/)**,
**[actionlint](https://github.com/rhysd/actionlint)** and
**[hadolint](https://github.com/hadolint/hadolint)** keep the non-Scala half honest.
**[OpenTelemetry](https://opentelemetry.io/)** and **[MLflow](https://mlflow.org/)** carry the
traces and the run ledger, **[PDFBox](https://pdfbox.apache.org/)** parses the water-quality
bulletins, and the **[Model Context Protocol](https://modelcontextprotocol.io/)** SDK exposes
marola's tools to other agents. **[ai-jail](https://github.com/akitaonrails/ai-jail)** sandboxes
the agents that write most of this code.

And the public bodies whose data marola only reads and re-presents: **INEA** (Rio de Janeiro),
**INEMA** (Bahia) and **IMA/SC** (Santa Catarina) publish the bathing-water bulletins the map's
water quality comes from.

## License

[MIT](./LICENSE) — © 2026 Matheus Hoffmann.
