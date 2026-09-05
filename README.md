<h1 align="center">🌊 marola</h1>

<p align="center"><b>When LLMs meet the ocean.</b><br/>
Local-first ocean intelligence for open-water swimmers: the best hour to swim tomorrow, official
bathing-water quality per sampling point, tides, jellyfish and whale odds, and a grounded
"ask the ocean" — all on your own machine with a free model, sourced or clearly labelled, never invented.</p>

<p align="center">
<a href="https://github.com/h0ffmann/marola/actions/workflows/ci.yml"><img src="https://github.com/h0ffmann/marola/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
<img src="https://img.shields.io/badge/Scala-3.9_LTS-DC322F?logo=scala&logoColor=white" alt="Scala 3.9" />
<img src="https://img.shields.io/badge/JDK-25-007396?logo=openjdk&logoColor=white" alt="JDK 25" />
<img src="https://img.shields.io/badge/effects-Kyo-DC322F" alt="Kyo" />
<img src="https://img.shields.io/badge/runs_on-Ollama_%C2%B7_llama3.2-000000?logo=ollama&logoColor=white" alt="Ollama" />
<img src="https://img.shields.io/badge/agents-MCP_tools-000000?logo=modelcontextprotocol&logoColor=white" alt="MCP" />
<img src="https://img.shields.io/badge/prompts-DSPy--compiled-B5121B" alt="DSPy" />
<img src="https://img.shields.io/badge/license-MIT-green" alt="MIT" />
</p>

marola (Portuguese for a small, gentle wave) takes the two things an LLM is bad at on its own —
knowing what the sea is doing *right now* and not making things up about it — and fixes both.
Live data decides the numbers; deterministic rules decide anything safety-related; a curated,
sourced corpus decides what the model may say; a second model reviews the first. The LLM does the
one thing it is good at: turning all that into a sentence you'd actually read on the sand.

## What you get

- **Best hour tomorrow, per beach near you** — OpenStreetMap beaches (nodes, ways *and* the
  multipolygon relations most big beaches are), Open-Meteo sea/weather/tide forecasts, a 0-100
  swimability score with the reasons, never at night, ties resolved toward mid-morning.
- **Official bathing-water quality, per sampling point** — Santa Catarina's IMA feed (260 points,
  weekly), matched to each beach: a stream mouth can be IMPRÓPRIA while the sand 300 m away is
  fine, and marola says which is which. Unfit water zeroes the score; that rule is code, not a prompt.
- **Tides, swell, period, wind, UV, jellyfish likelihood, whale-spotting odds** in one detailed
  block for the top pick — and a one-line ranked list for the rest.
- **Ask the ocean** — local RAG over a sourced marine corpus (rip currents, stings, whales,
  water quality, waves, foam) with `[n]` citations; off-corpus questions get the model's general
  knowledge with a visible "unsourced" label instead of a refusal. Benchmarked: it beats the plain
  prompt 0.84 vs 0.75 on 22 ocean questions and cites on 41% of answers, the prompt on none.
- **A sourced "did you know?"** about the sea in front of you, rotated daily, never touched by the LLM.
- **Agent-ready** — the whole pipeline is exposed as MCP tools (`get_swim_recommendation`,
  `get_water_quality`, `ask_ocean_question`) for Claude Desktop or any MCP client.
- **Everything runs locally** — Ollama + `llama3.2`, no account, no key, no cloud; Azure AI
  Foundry / Maps / Cosmos DB / Vision / App Insights are per-integration opt-ins, never a package deal.

```
 1. [ 55/100] Praia da Joaquina      (4.6km)  Sun 6 Sep, 10:00  |  water: PRÓPRIA (1/1 pts, 25 Aug)  |  19.0°C, 27km/h, 1.3m  |  jellyfish: Low
 6. [ 35/100] Praia do Campeche      (2.1km)  Sun 6 Sep, 08:00  |  water: 4/5 PRÓPRIA — avoid Ponto 73 (25 Aug)  |  18.8°C, 28km/h, 1.4m  |  jellyfish: Low

Top pick — Praia da Joaquina, Sun 6 Sep, 10:00-11:00
  Water quality   PRÓPRIA (1/1 pts, 25 Aug) · Ponto 33 (…ao lado do Posto de Guarda-Vidas): latest 10 enterococci/100mL · Source: IMA/SC
  Sea             19.0°C, waves 1.3m every 6s from the S, swell 0.8m/6s, current 0.9 km/h
  Tide            low 05:00 (-0.1m), high 13:00 (+0.7m), low 18:00 (+0.3m)
  Whales          Low at this hour; best daylight odds Low at 07:00 — humpback season

Reviewer (score 75/100, verdict: approve): Praia da Joaquina is a great spot for swimming with mild conditions and low jellyfish risk.

🐋 Sea life: Humpback whales (baleia-jubarte) travel up the Brazilian coast … [source: https://en.wikipedia.org/wiki/Humpback_whale]
```

## Why it's built this way

| The usual LLM failure | What marola does instead |
|---|---|
| Doesn't know today's sea | Every number comes from a live call: Overpass, Open-Meteo, the IMA bathing-water feed. The model never guesses a wave height. |
| Confidently wrong about safety | Water vetoes, darkness, rough-sea deductions are deterministic Scala in `scoring/`, unit-tested, outside the prompt. |
| Invents facts | Answers are grounded on a sourced corpus with citations, or labelled "unsourced"; the lore paragraph is shown verbatim from a curated file. |
| One model grading itself | A second, DSPy-compiled reviewer pass scores and can rewrite the summary before you see it. |
| Needs a cloud account to try | `nix develop && just ollama-up && just run` — that's the whole setup. |
| Regressions only caught in production | The full pipeline replays recorded real API responses in CI, no network, no Ollama; the live E2E is manual and model-cached. |

## Quick start

```bash
# 1. Reproducible dev shell (JDK 25, sbt, just, ollama, repomix, az, gh — see flake.nix).
nix develop            # or `direnv allow` — both also load ./.env if present

# 2. Ollama up, model pulled (llama3.2 by default; it embeds the corpus too — no second model).
just ollama-up

# 3. Build, test, quality gates — the same ones CI runs, no network needed.
just build && just test && just quality

# 4. Where are you? Pin it once (else marola geolocates your IP, city-level, and says so).
export MAROLA_ORIGIN_LAT=-27.6733 MAROLA_ORIGIN_LON=-48.4700     # Praia do Campeche

# 5. The product.
just run -- --summarize                                            # ranked beaches, top-pick block, review, lore
just ask "what should I do if I get caught in a rip current?"      # grounded answer with sources
just benchmark                                                     # marola vs. a plain prompt, 22 questions
just mcp-server                                                    # the pipeline as MCP tools over stdio
```

No Telegram token, no Azure account, no API key is needed for any line above. Full walkthrough with
real output: [`docs/RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md).

## Where it's going

**Next: the product surface.** The CLI is the workbench; the product is a **Telegram bot** —
share a location, get the list; ask a question; send a photo of that thing on the sand
([MIP-0002](./docs/mips/MIP-0002-telegram-bot-phase-1.md)), with sub-three-second replies
([MIP-0003](./docs/mips/MIP-0003-fast-replies-caching-and-fan-out.md)) and a daily digest for the
beaches you care about ([MIP-0004](./docs/mips/MIP-0004-daily-digest-subscriptions-and-reach.md)).

**Then: the rest of the sea.** Swimming is the first activity, not the only one. The same live data,
water quality, tides and knowledge corpus serve every sea activity — each is one scoring function
and its own vocabulary, not a new app (design in [`FUTURE-WORK.md`](./docs/FUTURE-WORK.md) §1):

| Activity | What changes | Status |
|---|---|---|
| 🏄 **Surf** | Wants what swimmers avoid: swell height *and period*, groundswell vs. wind chop, offshore vs. onshore wind relative to the beach's orientation, tide stage per break. Open-Meteo already returns the swell fields marola fetches for the tide block. | Designed (§1.3); needs a per-beach orientation lookup |
| 🤿 **Diving & snorkelling** | Visibility above all: turbidity/chlorophyll (Copernicus Marine), low current, calm entry/exit, slack tide windows from the tide series; sea-life odds (turtles, rays) from the sightings feedback loop. | Designed (§1.4); visibility source is the open question |
| 🎣 **Fishing** | Tide stage and turn times (already computed), water temperature and upwelling, wind for shore vs. boat, moon phase; closed seasons (*defeso*) and protected areas as hard rules, not suggestions. | Planned; scoring and regulatory sources not yet designed |
| 🌊 **Natural disasters & hazards** | A different question: not "what's good" but "is something dangerous coming" — rip-current risk from swell/period/tide, storm surge, dangerous sea states, cold shock from upwelling, red tides, water-quality collapses after storms; cross-checked against official civil-defence alerts, never replacing them. | Designed as the escalation agent (§9.2, [AI-500 §4](./docs/AI-500-MAPPING.md)); proactive alerts are gated behind a human-confirmation design before anything ships |
| 🛶 Kayak, SUP, open-water events | Wind and chop thresholds of their own; group/event digests. | Extensibility target only — one `ActivityScoring` each |

The rule for all of them holds: anything safety-relevant is deterministic code with a named source;
the model only writes the sentence. A daily digest that you subscribed to is fine; an unrequested
"get out of the water" push is the one feature that needs a governance gate first, and it will get
one.

Non-trivial changes start as a numbered proposal under [`docs/mips/`](./docs/mips/README.md). The
repo also doubles as hands-on coverage of the Azure AI-103 exam domains and a design target for
AI-500 (see the last section).

## Documentation

**Start here:** [`docs/RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md) — the whole pipeline with a small,
free local model, step by step. Everything else lives under `docs/`:

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
| [`AGENT-FRAMEWORKS-SURVEY.md`](./docs/AGENT-FRAMEWORKS-SURVEY.md) | Multi-agent frameworks: Python ideas, JVM/Scala libraries, where Apache Pekko fits marola |
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
