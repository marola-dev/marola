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

marola is a workspace of single-purpose repos; this one ties them together as git submodules. The
app you run is [marola-app](https://github.com/marola-dev/marola-app):

```bash
git clone --recurse-submodules https://github.com/marola-dev/marola && cd marola/marola-app
nix develop                                                        # JDK 25, sbt, just, ollama — see its flake.nix
just run -- --brief --lat -27.6733 --lon -48.4700                  # fastest path: ranked list, no LLM
just ollama-up                                                     # starts `ollama serve`, pulls llama3.2 if missing
just run -- --summarize --lat -27.6733 --lon -48.4700              # ranked list + top-pick block + LLM summary + review
just ask "what should I do if I get caught in a rip current?"      # grounded answer with sources
```

No cloud account, no API key needed for any of the above. Full walkthrough with real output:
[RUN-LOCALLY](https://docs.marola.dev/1-Using-marola/RUN-LOCALLY/).

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

## The repos

| Repo | What it is |
|---|---|
| [marola](https://github.com/marola-dev/marola) (this one) | The workspace: ways of working, the design docs ([MIPs](./docs/MIPs/README.md)), the phase list, the docs site at [docs.marola.dev](https://docs.marola.dev/), and every repo below as a submodule |
| [marola-app](https://github.com/marola-dev/marola-app) | The product, in Scala 3 with [Kyo](https://getkyo.io/) on the JVM: the pipeline, the score and its safety veto, the CLI, the MCP tool server, the container image |
| [marola-site](https://github.com/marola-dev/marola-site) | The map at [marola.dev](https://marola.dev/), rebuilt every 3 hours from the app's image |
| [marola-corpus](https://github.com/marola-dev/marola-corpus) | The sourced ocean knowledge marola answers from |
| [marola-ml](https://github.com/marola-dev/marola-ml) | Offline Python: the DSPy prompt compile, the benchmark gate, and [marola-sea](https://huggingface.co/h0ffmann/marola-sea-tiny-GGUF), marola's own small model |
| [marola-oods](https://github.com/marola-dev/marola-oods) | The Open Ocean Data Store: an open, versioned archive of Brazil's bathing-water quality (starting empty) |
| [marola-devkit](https://github.com/marola-dev/marola-devkit) | The shared dev harness every repo pins: tools, hooks, Claude Code skills, CI workflows |

The score and its safety veto are deterministic Scala; the model only interprets and phrases, and
never overturns a veto. Why it's built this way: [`PHILOSOPHY.md`](./PHILOSOPHY.md). How it works:
[ARCHITECTURE](https://docs.marola.dev/2-Building-marola/ARCHITECTURE/). Everything else, from
every repo, is searchable at **[docs.marola.dev](https://docs.marola.dev/)**.

If you're an AI coding agent: read [`AGENTS.md`](./AGENTS.md) first, then the `AGENTS.md` of the
repo you're changing.

## Contributing

Small PRs, one topic each, in the repo the change belongs to; non-trivial changes start as a MIP
under `docs/MIPs/` here; every commit carries `Tested:`, `Cost:` and
`Co-Authored-By: Claude <noreply@anthropic.com>` trailers. AI agents are first-class contributors here and follow `AGENTS.md` like anyone else.
Full guide: [`CONTRIBUTING.md`](./CONTRIBUTING.md). Please also read the
[Code of Conduct](./CODE_OF_CONDUCT.md) and, for a vulnerability, [`SECURITY.md`](./SECURITY.md).

## Gemini review

Request the reviewer `marola-dev/gemini` on a pull request (sidebar → Reviewers, or
`gh pr edit <N> --add-reviewer marola-dev/gemini`). `marola-gemini-bot` posts one review with at
most 10 inline comments tagged `[high]`/`[medium]`/`[low]`, then pushes one commit with the fixes
it is sure of. Request it again after new commits for a fresh review. It runs only when
asked, skips pull requests from forks and never edits `.github/`. `.github/workflows/gemini.yml`
calls the devkit's [`gemini-review`](https://github.com/marola-dev/marola-devkit/blob/main/docs/4-reference_workflows.md#gemini-review)
workflow.

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
