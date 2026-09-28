<h1 align="center">🌊 marola</h1>

<p align="center"><b>marola: the ocean intelligence layer.</b><br/>
The ocean near you: conditions, official bathing-water quality per sampling point, tides,
jellyfish and whale odds, and a grounded "ask the ocean": first case, the best hour tomorrow to
swim, all on your own machine with a free model (Scala 3 / Kyo / Ollama), sourced or clearly
labelled, never invented.<br/>
Not a weather or surf app with a chatbot bolted on: the score and its safety veto are deterministic
Scala, and the model is on judge duty over that: it interprets and phrases; it never overturns a
veto. The reasoning behind that split: <a href="./PHILOSOPHY.md"><code>PHILOSOPHY.md</code></a>,
"models reasoning over open water, with the deterministic parts kept deterministic."</p>

<p align="center">
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://github.com/h0ffmann/marola/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
<a href="https://github.com/h0ffmann/marola/actions/workflows/site.yml"><img src="https://github.com/h0ffmann/marola/actions/workflows/site.yml/badge.svg" alt="site (build + deploy)" /></a>
<!-- Aggregated statement coverage: ci.yml measures it (sbt-scoverage) on pushes to main and writes this shields.io endpoint JSON to Pages via the site-data branch. -->
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fcoverage%2Flatest.json" alt="Scala statement coverage (sbt-scoverage)" /></a>
<!-- The Python half, measured the only way marola tests Python: statement coverage of scripts/**/*.py while each script's own --self-test runs (there is no pytest suite). scripts/repo_stats.py, same repo-stats job and site-data branch as the badges below. -->
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fpython-coverage.json" alt="Python statement coverage under the scripts' own --self-tests" /></a>
<!-- Same mechanism, ci.yml's repo-stats job (scripts/repo_stats.py): how many of the last main run's steps went green out of the steps that actually ran (skipped ones excluded), and cloc's code-line counts for the three Scala modules and the Python trees. -->
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

**Live map:** [marola.dev](https://marola.dev/): every beach around
Florianópolis, Rio de Janeiro and Salvador, ranked for today and tomorrow, water quality, tides
and the hour slider;
rebuilt every 3 hours and on every relevant merge to `main` ([`site.yml`](./.github/workflows/site.yml),
[MIP-0005](./docs/MIPs/MIP-0005-map-and-static-site.md)). Live data decides the numbers,
deterministic rules decide anything safety-related, a sourced corpus decides what the model may
say, and a second model reviews the first. The reasoning behind each choice: [`PHILOSOPHY.md`](./PHILOSOPHY.md).

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

- **Best hour tomorrow, per beach**: OpenStreetMap beaches, Open-Meteo sea/weather/tide forecasts, a 0-100 swimability score with the reasons, never at night.
- **Official bathing-water quality, per sampling point**: Santa Catarina's IMA feed; unfit water zeroes the score in code, not a prompt.
- **Tides, swell, wind, UV, jellyfish and whale odds**, and a sourced "did you know?" about the sea in front of you.
- **Ask the ocean**: local RAG with `[n]` citations; off-corpus questions get an "unsourced" label instead of a refusal.

## Run it in five minutes

```bash
nix develop                                                        # JDK 25, sbt, just, ollama — see flake.nix
just run -- --brief --lat -27.6733 --lon -48.4700                  # fastest path: ranked list, no LLM
just ollama-up                                                     # starts `ollama serve`, pulls llama3.2 if missing
just run -- --summarize --lat -27.6733 --lon -48.4700              # ranked list + top-pick block + LLM summary + review
just ask "what should I do if I get caught in a rip current?"      # grounded answer with sources
just site-build floripa && just site-serve                         # the map, locally, at :8000
```

No cloud account, no API key needed for any of the above. Full walkthrough with
real output: [`docs/1-Using-marola/RUN-LOCALLY.md`](./docs/1-Using-marola/RUN-LOCALLY.md). Docker instead of Nix/sbt/Ollama
([MIP-0008](./docs/MIPs/MIP-0008-docker-images-and-smoke-test.md)):

```bash
docker compose --profile ollama run --rm marola --summarize --lat -27.6733 --lon -48.4700
```

## The integrations: local and free

Every integration is a trait with a free local implementation. Full detail, including what's
verified live vs. written-not-run: [`docs/2-Building-marola/ARCHITECTURE.md`](./docs/2-Building-marola/ARCHITECTURE.md) §5.

| Capability | Implementation | Switch |
|---|---|---|
| Query synthesis | Ollama chat completion | n/a |
| Beach distance | Haversine (straight line) | n/a |
| Agentic tool access | MCP server over stdio | n/a |
| Sighting reports | JSON-lines file | n/a |
| Photo analysis | Multimodal Ollama (`llava`) | n/a |
| Observability | Off, or OTLP traces into local MLflow | `MAROLA_TRACES=off\|mlflow` |

**Next:** the rest of the sea (surf, diving, fishing) as new scoring functions over the same data;
roadmap: [`docs/MIPs/README.md`](./docs/MIPs/README.md), [`docs/4-Research-and-plans/FUTURE-WORK.md`](./docs/4-Research-and-plans/FUTURE-WORK.md) §1.

## Documentation

**Start here:** [`docs/1-Using-marola/RUN-LOCALLY.md`](./docs/1-Using-marola/RUN-LOCALLY.md). Everything else lives under `docs/`:

| Doc | What it covers |
|---|---|
| [`PHILOSOPHY.md`](./PHILOSOPHY.md) | Why marola is built the way it is: the three pillars, why agents, why Scala/Nix/`just` |
| [`ARCHITECTURE.md`](./docs/2-Building-marola/ARCHITECTURE.md) | The pipeline, its local integrations, verified-live vs. written-not-run |
| [`RUN-LOCALLY.md`](./docs/1-Using-marola/RUN-LOCALLY.md) | Run it now with Ollama, no cloud account needed |
| [`FUTURE-WORK.md`](./docs/4-Research-and-plans/FUTURE-WORK.md) / [`EFFECTS-MAP.md`](./docs/2-Building-marola/EFFECTS-MAP.md) | Design sketches, reviewed-not-adopted libraries; a Scala/FP-purity review |
| [`SKILLS.md`](./docs/4-Research-and-plans/SKILLS.md) / [`AGENT-SKILLS.md`](./docs/3-Working-on-the-repo/AGENT-SKILLS.md) | A skills roadmap for humans; which Claude Code skills to use here |
| [`AGENT-FRAMEWORKS-SURVEY.md`](./docs/4-Research-and-plans/AGENT-FRAMEWORKS-SURVEY.md) | Multi-agent frameworks: Python ideas, JVM/Scala libraries, where Apache Pekko fits |
| [`benchmarks/`](./docs/benchmarks/2026-09-05.md) / [`mips/`](./docs/MIPs/README.md) | Kept benchmark runs; numbered design docs written before a feature is built |
| [`FABLE_REVIEW.md`](./docs/4-Research-and-plans/FABLE_REVIEW.md) / [`DEV-FLOW.md`](./docs/3-Working-on-the-repo/DEV-FLOW.md) | Code review at the initial import; the dev loop end to end, MIP → PRs → merge |

If you're an AI coding agent picking this repo up: read [`AGENTS.md`](./AGENTS.md) first.

## marola-sea — the fine-tuned model

marola's own small model, trained on the repo's ocean corpus and published as GGUF:
**[h0ffmann/marola-sea-tiny-GGUF](https://huggingface.co/h0ffmann/marola-sea-tiny-GGUF)**.

```bash
just marola-sea-pull tiny Q8_0     # pull it into Ollama as `marola-sea`
MAROLA_LOCAL_LLM_MODEL=marola-sea just run -- --summarize
```

The `tiny` preset is SmolLM2-360M, a **pipeline proof, not a quality bar**, exactly as
[`finetune/README.md`](./finetune/README.md) frames it. On a real swim summary it ignores the
facts it is given and invents its own; `marola-llama3.2` (Llama 3.2 with marola's persona, built
locally by `just finetune-model`) produces a usable answer from the same input. Scaling it is
[`MIP-0048`](./docs/MIPs/MIP-0048-scaling-marola-sea.md).

| Doc | What it covers |
|---|---|
| [`finetune/README.md`](./finetune/README.md) | The two tiers, what each costs, what is actually run |
| [`MIP-0025`](./docs/MIPs/README.md) | The chain: dataset → SFT → DPO → merge → GGUF → publish |
| [`MIP-0048`](./docs/MIPs/MIP-0048-scaling-marola-sea.md) | Which model, which checkpoint, which hardware, the data ceiling |

## Contributing

Small PRs, one topic each; non-trivial changes start as a MIP under `docs/MIPs/`; every commit
carries the `Co-Authored-By: Claude <noreply@anthropic.com>` trailer and every PR body ends with a
`Cost:` line. AI agents are first-class contributors here and follow `AGENTS.md` like anyone else.
Full guide: [`CONTRIBUTING.md`](./CONTRIBUTING.md). Please also read the
[Code of Conduct](./CODE_OF_CONDUCT.md) and, for a vulnerability, [`SECURITY.md`](./SECURITY.md).

## Thanks

marola stands on other people's work. Five it could not exist without, alphabetically:

- **[Kyo](https://getkyo.io/)**: the effect system the entire Scala side is written in. Its
  direct-style `.now`/`defer` is what lets the pipeline read like ordinary code while keeping
  effects visible in the types.
- **[Leaflet](https://leafletjs.com/)**: draws the map, with no account, key or tracker.
- **[Ollama](https://ollama.com/)**: runs the models locally, which is what makes marola usable
  with no cloud account and no API key.
- **[Open-Meteo](https://open-meteo.com/)**: the sea temperature, wind and wave forecasts every
  score is computed from, free and keyless.
- **[OpenStreetMap](https://www.openstreetmap.org/copyright)** contributors: every beach, trail
  and facility on the map is theirs, under ODbL.

Also relied on daily: Scala 3, MUnit, sbt, Nix, just, DSPy, Hugging Face (`transformers`, `peft`,
`trl`) and SmolLM2, llama.cpp, PDFBox, OpenTelemetry, MLflow, the Model Context Protocol SDK, and
[ai-jail](https://github.com/akitaonrails/ai-jail). The map's water quality comes from bulletins
published by INEA (Rio de Janeiro), INEMA (Bahia) and IMA/SC (Santa Catarina).

## License

[MIT](./LICENSE), © 2026 marola contributors.
</content>
