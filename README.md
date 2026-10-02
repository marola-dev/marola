<h1 align="center">🌊 marola</h1>

<p align="center">
<a href="https://github.com/marola-dev/marola/actions/workflows/ci.yml"><img src="https://github.com/marola-dev/marola/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
<a href="https://github.com/marola-dev/marola-site/actions/workflows/site.yml"><img src="https://github.com/marola-dev/marola-site/actions/workflows/site.yml/badge.svg" alt="site (build + deploy)" /></a>
<!-- Aggregated statement coverage: marola-app's ci.yml measures it (sbt-scoverage) on pushes to main and writes this shields.io endpoint JSON to marola-site's site-data branch, which its Pages serves. -->
<a href="https://github.com/marola-dev/marola-app/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fcoverage%2Flatest.json" alt="Scala statement coverage (sbt-scoverage)" /></a>
<!-- The Python half, measured the only way marola tests Python: statement coverage of scripts/**/*.py while each script's own --self-test runs (there is no pytest suite). scripts/repo_stats.py, same repo-stats job and site-data branch as the badges below. -->
<a href="https://github.com/marola-dev/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fpython-coverage.json" alt="Python statement coverage under the scripts' own --self-tests" /></a>
<!-- Same mechanism, ci.yml's repo-stats job (scripts/repo_stats.py): how many of the last main run's steps went green out of the steps that actually ran (skipped ones excluded), and cloc's code-line counts for the three Scala modules and the Python trees. -->
<a href="https://github.com/marola-dev/marola/actions/workflows/ci.yml"><img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fci.json" alt="CI steps green on the last main run" /></a>
<img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fscala-loc.json" alt="Scala lines of code" />
<img src="https://img.shields.io/endpoint?url=https%3A%2F%2Fmarola.dev%2Fstats%2Fpython-loc.json" alt="Python lines of code" />
<a href="https://marola.dev/"><img src="https://img.shields.io/badge/live_map-marola.dev-0b6e99?logo=leaflet&logoColor=white" alt="live map" /></a>
<img src="https://img.shields.io/badge/Scala-3.9_LTS-DC322F?logo=scala&logoColor=white" alt="Scala 3.9" />
<img src="https://img.shields.io/badge/JDK-25-007396?logo=openjdk&logoColor=white" alt="JDK 25" />
<img src="https://img.shields.io/badge/runs_on-Ollama_%C2%B7_llama3.2-000000?logo=ollama&logoColor=white" alt="Ollama" />
<img src="https://img.shields.io/badge/agents-MCP_tools-000000?logo=modelcontextprotocol&logoColor=white" alt="MCP" />
<a href="./LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="MIT" /></a>
</p>

<p align="center"><b>A friendly guide to the sea near you, built in the open, as citizen science.</b></p>

<p align="center"><a href="./README.pt-BR.md">🇧🇷 Leia em português</a> · <a href="https://marola.dev/">🗺️ Open the live map</a></p>

## What is marola?

Imagine a friend who knows the sea really well. You ask *"can I swim tomorrow, and when?"*, and
they look at the waves, the wind, the water temperature, whether the water is clean enough, the
tide, even whether jellyfish or whales are around, and then they tell you the best hour, and
**why**.

That friend is marola. You can see it today at **[marola.dev](https://marola.dev/)**: a map of the
beaches around Florianópolis, Rio de Janeiro and Salvador, each one ranked for today and tomorrow.

<p align="center"><a href="https://marola.dev/"><img src="./docs/img/marola-web-view.png" alt="marola.dev — best hour per beach, ranked, with the water-quality popup for a sampling point" width="720" /></a></p>

A few promises marola keeps:

- **It never makes things up.** Every number comes from real, public data, and anything that could
  hurt you (dirty water, rough sea) is decided by plain rules a person can read, not by an AI's
  guess. The AI only helps explain it in words.
- **It's free, and it runs on your own computer.** No account, no paid service needed.
- **It says when it doesn't know.** "No data" is shown as "no data", never hidden.

## What you get

- **Best hour tomorrow, per beach**: OpenStreetMap beaches, Open-Meteo sea/weather/tide forecasts, a 0-100 swimability score with the reasons, never at night.
- **Official bathing-water quality, per sampling point**: IMA/SC, INEA and INEMA bulletins; unfit water zeroes the score in code, not a prompt.
- **Tides, swell, wind, UV, jellyfish and whale odds**, and a sourced "did you know?" about the sea in front of you.
- **Ask the ocean**: local RAG with `[n]` citations; off-corpus questions get an "unsourced" label instead of a refusal.

## Run it in five minutes

The app is [marola-app](https://github.com/marola-dev/marola-app), a submodule of this workspace:

```bash
git clone --recurse-submodules https://github.com/marola-dev/marola && cd marola/marola-app
nix develop                                                        # JDK 25, sbt, just, ollama — see its flake.nix
just run -- --brief --lat -27.6733 --lon -48.4700                  # fastest path: ranked list, no LLM
just ollama-up                                                     # starts `ollama serve`, pulls llama3.2 if missing
just run -- --summarize --lat -27.6733 --lon -48.4700              # ranked list + top-pick block + LLM summary + review
just ask "what should I do if I get caught in a rip current?"      # grounded answer with sources
just run -- --site floripa --areas cli/src/test/resources/site/areas.json   # the map's board data (the page: marola-site)
```

No cloud account, no API key needed for any of the above. Full walkthrough with
real output: [`docs/1-Using-marola/RUN-LOCALLY.md`](https://docs.marola.dev/1-Using-marola/RUN-LOCALLY/). Docker instead of Nix/sbt/Ollama
([MIP-0008](./docs/MIPs/MIP-0008-docker-images-and-smoke-test.md)):

```bash
docker compose --profile ollama run --rm marola --summarize --lat -27.6733 --lon -48.4700
```

## Why it's open source: citizen science 🔬

marola is a **citizen-science** project. The sea belongs to everyone, and so should
the knowledge about it. Public agencies already measure a lot (water quality, waves, weather), but
that data is scattered, technical and hard to use at the beach. marola gathers it, checks it,
explains it in plain language, and gives it back to the public. And the people who swim, surf,
fish and dive every day can give something back too: what they see in the water. That's why
everything here is open: the code, the data sources, the rules, and every design decision.
Anyone can read it, check it, and help improve it.

## Where it starts, and where it's going

**First use case: the swim agent.** *"What's the best hour tomorrow to swim nearby?"* It already
works: beaches from OpenStreetMap, sea and weather forecasts from Open-Meteo, official
bathing-water quality from the state environmental agencies, all turned into a score from 0 to
100 with the reasons spelled out.

**The bigger ambition: the ocean intelligence layer for the coast.** Swimming is just the first
question. The same data, and much more, can help protect people and places:

- 🌊 **Coastal hazard warnings.** Starting with *ressaca* (storm surf that erodes Brazil's
  beaches), with a hard "don't go in" when the sea gets dangerous
  ([MIP-0062](./docs/MIPs/MIP-0062-ressaca-hazard.md)).
- 🛰️ **Disaster forecasting with the same models the big agencies use.** marola's wave data
  already includes NOAA's **WAVEWATCH III** model (through NCEP's GFS-Wave). The plan is to show
  several models side by side, say openly when they disagree, and one day run a detailed wave
  model for our own bays ([MIP-0051](./docs/MIPs/MIP-0051-wave-model-ensemble.md),
  [MIP-0038](./docs/MIPs/MIP-0038-forecast-model-spread.md),
  [MIP-0052](./docs/MIPs/MIP-0052-wave-model-compute.md)).
- 🧪 **An open archive of Brazil's beach water quality**, versioned so anyone can study it
  ([MIP-0056](./docs/MIPs/MIP-0056-oods-open-ocean-data-store.md)).
- 🪼 **Reports from people at the beach.** Jellyfish, whales, and later photos of how the sea
  looks right now, so real observations can check and improve the forecasts.
- 🏄 **The rest of the sea.** Surfing, diving and fishing, as new questions over the same data.

These are ideas at different stages. Each one is written up as a public design doc (a "MIP") before
it's built, so you can see exactly what is done and what is still a plan: [`docs/MIPs/`](./docs/MIPs/README.md).

## How you can help (no coding needed)

- **Use the map** at [marola.dev](https://marola.dev/) and tell us when it's wrong. That's
  valuable data.
- **Tell us what you see in the water**, like jellyfish or whales, or a beach that's missing.
- **Share local knowledge**: sea safety, marine life, local conditions. Every fact marola explains
  comes from a sourced note in [marola-corpus](https://github.com/marola-dev/marola-corpus).
- **Open an issue**, in English or Portuguese: [github.com/marola-dev/marola/issues](https://github.com/marola-dev/marola/issues).

---

## For developers

marola is written in [Scala](https://www.scala-lang.org/), a programming language created at
[EPFL](https://www.epfl.ch/) (the Swiss Federal Institute of Technology in Lausanne) by Martin
Odersky's lab, and maintained today by EPFL's [Scala Center](https://scala.epfl.ch/) together with
VirtusLab and Akka (formerly Lightbend). marola uses Scala 3 with [Kyo](https://getkyo.io/) on the
JVM, and a free local model through [Ollama](https://ollama.com/). The score and its safety veto
are deterministic Scala; the model only interprets and phrases, and never overturns a veto. The reasoning behind that split:
[`PHILOSOPHY.md`](./PHILOSOPHY.md). The map is rebuilt every 3 hours and on every relevant merge
to `main` of [marola-site](https://github.com/marola-dev/marola-site) ([MIP-0005](./docs/MIPs/MIP-0005-map-and-static-site.md)).

```console
$ just run -- --brief --lat -27.6733 --lon -48.4700   # real run, 7 Sep 2026; header lines and the last 2 of 6 beaches trimmed
origin -> lat=-27.6733, lon=-48.4700 (radius 15km, source: --lat/--lon flags)
water quality -> IMA/SC
 1. [ 55/100] Praia da Joaquina      (4.6km away)  best at Tue 8 Sep, 10:00  |  18.3°C sea, 11km/h wind  |  jellyfish: Moderate  |  whale sighting: High  |  choppy (0.6m waves), cold water (18.3°C), some jellyfish likelihood  · facilities: no data
 2. [ 55/100] Praia do Rio Tavares   (2.1km away)  best at Tue 8 Sep, 10:00  |  18.3°C sea, 11km/h wind  |  jellyfish: Moderate  |  whale sighting: High  |  choppy (0.6m waves), cold water (18.3°C), some jellyfish likelihood  · lifeguard post: yes
 3. [ 55/100] Praia do Morro das Pedras (5.1km away)  best at Tue 8 Sep, 10:00  |  17.9°C sea, 13km/h wind  |  jellyfish: Moderate  |  whale sighting: High  |  choppy (0.8m waves), cold water (17.9°C), some jellyfish likelihood  · facilities: no data
 4. [ 40/100] Praia da Armação       (7.9km away)  best at Tue 8 Sep, 11:00  |  18.2°C sea, 15km/h wind  |  jellyfish: Moderate  |  whale sighting: High  |  breezy (15km/h), cold water (18.2°C), some jellyfish likelihood, 2/6 points PRÓPRIA — avoid Em frente à Avenida Antônio Borges dos Santos, n°792, Foz do Rio Sangradouro; Em frente à Rua Francisco Fagundes; Em frente à Rua Maria Emília de Costa, n°62; Em frente à Rua Antônio Aniceto da Costa  · facilities: no data
```

### The integrations: local and free

Every integration is a trait with a free local implementation. Full detail, including what's
verified live vs. written-not-run: [`docs/2-Building-marola/ARCHITECTURE.md`](https://docs.marola.dev/2-Building-marola/ARCHITECTURE/) §5.

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

### Documentation

**Start here:** [`docs/1-Using-marola/RUN-LOCALLY.md`](https://docs.marola.dev/1-Using-marola/RUN-LOCALLY/). Everything else lives under `docs/`:

| Doc | What it covers |
|---|---|
| [`PHILOSOPHY.md`](./PHILOSOPHY.md) | Why marola is built the way it is: the three pillars, why agents, why Scala/Nix/`just` |
| [`ARCHITECTURE.md`](https://docs.marola.dev/2-Building-marola/ARCHITECTURE/) | The pipeline, its local integrations, verified-live vs. written-not-run |
| [`RUN-LOCALLY.md`](https://docs.marola.dev/1-Using-marola/RUN-LOCALLY/) | Run it now with Ollama, no cloud account needed |
| [`FUTURE-WORK.md`](./docs/4-Research-and-plans/FUTURE-WORK.md) / [`EFFECTS-MAP.md`](https://docs.marola.dev/2-Building-marola/EFFECTS-MAP/) | Design sketches, reviewed-not-adopted libraries; a Scala/FP-purity review |
| [`SKILLS.md`](./docs/4-Research-and-plans/SKILLS.md) / [`AGENT-SKILLS.md`](./docs/3-Working-on-the-repo/AGENT-SKILLS.md) | A skills roadmap for humans; which Claude Code skills to use here |
| [`AGENT-FRAMEWORKS-SURVEY.md`](./docs/4-Research-and-plans/AGENT-FRAMEWORKS-SURVEY.md) | Multi-agent frameworks: Python ideas, JVM/Scala libraries, where Apache Pekko fits |
| [`benchmarks/`](https://github.com/marola-dev/marola-ml/blob/main/docs/benchmarks/2026-09-05.md) / [`mips/`](./docs/MIPs/README.md) | Kept benchmark runs (in marola-ml); numbered design docs written before a feature is built |
| [`FABLE_REVIEW.md`](./docs/4-Research-and-plans/FABLE_REVIEW.md) / [`DEV-FLOW.md`](./docs/3-Working-on-the-repo/DEV-FLOW.md) | Code review at the initial import; the dev loop end to end, MIP → PRs → merge |

If you're an AI coding agent picking this repo up: read [`AGENTS.md`](./AGENTS.md) first.

### marola-sea — the fine-tuned model

marola's own small model, trained on the repo's ocean corpus and published as GGUF:
**[h0ffmann/marola-sea-tiny-GGUF](https://huggingface.co/h0ffmann/marola-sea-tiny-GGUF)**.

```bash
just marola-sea-pull tiny Q8_0     # in a marola-ml checkout: pull it into Ollama as `marola-sea`
MAROLA_LOCAL_LLM_MODEL=marola-sea just run -- --summarize
```

The `tiny` preset is SmolLM2-360M, a **pipeline proof, not a quality bar**, exactly as
[`finetune/README.md`](https://github.com/marola-dev/marola-ml/blob/main/finetune/README.md) frames it. On a real swim summary it ignores the
facts it is given and invents its own; `marola-llama3.2` (Llama 3.2 with marola's persona, built
locally by marola-ml's `just finetune-model`) produces a usable answer from the same input. Scaling it is
[`MIP-0048`](./docs/MIPs/MIP-0048-scaling-marola-sea.md).

| Doc | What it covers |
|---|---|
| [`finetune/README.md`](https://github.com/marola-dev/marola-ml/blob/main/finetune/README.md) | The two tiers, what each costs, what is actually run ([marola-ml](https://github.com/marola-dev/marola-ml), the `marola-ml/` submodule, holds the fine-tune, the DSPy compile and the benchmark gate) |
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

## Contact

For any request about marola, write to [admin@marola.dev](mailto:admin@marola.dev).
Vulnerabilities go through the private channel in [`SECURITY.md`](./SECURITY.md).

## License

[MIT](./LICENSE), © 2026 marola contributors.
</content>
