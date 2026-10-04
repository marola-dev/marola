# Run it locally

marola's real pipeline end to end on your own machine: nearby beaches, live sea and weather,
bathing-water quality, a score, and an LLM summary checked by a second LLM pass, on a small Ollama
model. No Telegram, no cloud account. Prove it works here, cheaply, before touching anything else.

Every flag is in marola-app's [CLI reference](https://docs.marola.dev/5-Repos/marola-app/4-reference_cli/)
and every variable in its
[configuration reference](https://docs.marola.dev/5-Repos/marola-app/4-reference_config/).

!!! note "Moved from this page"
    The corpus questions, the benchmark and the marola model variant are
    [Ask the ocean notes](ASK-THE-OCEAN-NOTES.md); the chat server and the MCP server,
    [Chat and MCP](CHAT-AND-MCP.md); the published image, [Docker](DOCKER.md). Tests, image builds
    and observability are developer material, on marola-app's
    [development page](https://docs.marola.dev/5-Repos/marola-app/3-development/). Each moved
    section keeps its heading here, pointing to its new home.

## 1. Prerequisites

- **A marola-app checkout**: `git clone https://github.com/marola-dev/marola-app`, or `marola-app/`
  in an umbrella clone after `git submodule update --init`. Every command below runs in it.
- **Its dev shell**: `nix develop` (or `direnv allow`) in the checkout puts JDK 25, sbt, `just` and
  `ollama` on `PATH`.
- About 2 GB of disk for the models, and network access: the pipeline asks Overpass, Open-Meteo and
  the water agencies live, and the first run downloads the corpus release.

## 2. Start Ollama and pull a small model

```bash
ollama serve                    # in its own terminal; it stays in the foreground
```

```bash
ollama pull llama3.2:1b         # 1.3 GB: the model that writes and reviews the summary
ollama pull nomic-embed-text    # 274 MB: the embedder, for the corpus questions
```

`llama3.2:1b` downloads in a couple of minutes and answers in seconds on a CPU. It is not the most
capable model, but checking that the pipeline is wired end to end needs a working model, not a good
one. The embedder is needed because the app's default, the chat model `llama3.2`, is refused by
current Ollama for embeddings (marola-dev/marola-app#12). Only the corpus paths embed; §4 does not.

Instead of the three commands, the app's recipe in a marola-app checkout, `just ollama-up
llama3.2:1b nomic-embed-text`, starts a server in the background when none answers and pulls what
is missing. Check that Ollama is serving:

```bash
curl http://localhost:11434/api/tags
# {"models":[{"name":"nomic-embed-text:latest", ...},{"name":"llama3.2:1b", ...}]}
```

## 3. Point marola at it

```bash
export MAROLA_LOCAL_LLM_MODEL=llama3.2:1b
export MAROLA_LOCAL_EMBED_MODEL=nomic-embed-text
```

The app's own default model is `llama3.2` (3B), and its default base URL,
`http://localhost:11434/v1`, is already Ollama's, so nothing else needs setting
([Models and the corpus](https://docs.marola.dev/5-Repos/marola-app/4-reference_config/#models-and-the-corpus)).

## 4. Run the pipeline — the actual E2E check

```bash
# in a marola-app checkout
just run                                              # no LLM: beaches, sea, weather, water, score
just run -- --lat -27.6733 --lon -48.4700 --summarize   # plus the LLM draft and its review
```

The first command alone proves network access and the deterministic core. The second is the one
this page builds up to. A real run from Campeche, Florianópolis, on 2026-10-04 (sbt's start-up
lines cut; your beaches and numbers will differ, it is live data):

```text
marola :: the ocean intelligence layer (POC) — first case: best hour tomorrow to swim nearby
config -> telegram=unset llm=http://localhost:11434/v1 llama3.2:1b embed=nomic-embed-text knowledge=.tmp/knowledge -> ./data/knowledge-index.json lore=on ask=General>=0.0 origin=auto radius=15km water=Auto facilities=Overpass sightings=./data/sightings.jsonl vision=llava mlflow=unset(experiment=marola) traces=Off
origin -> lat=-27.6733, lon=-48.4700 (radius 15km, source: --lat/--lon flags)
water quality -> IMA/SC
 1. [ 55/100] Praia da Armação       (7.9km)  Mon 5 Oct, 10:00  |  water: no data  |  19.4°C, 16km/h, 0.6m  |  jellyfish: Low  |  whales: High  |  choppy (0.6m waves), breezy (16km/h), cold water (19.4°C)  · facilities: no data
  trails: no data
 2. [ 55/100] Praia da Joaquina      (4.6km)  Mon 5 Oct, 10:00  |  water: no data  |  19.1°C, 18km/h, 0.8m  |  jellyfish: Low  |  whales: High  |  choppy (0.8m waves), breezy (18km/h), cold water (19.1°C)  · facilities: no data
  trails: no data
 3. [ 55/100] Praia do Rio Tavares   (2.1km)  Mon 5 Oct, 10:00  |  water: no data  |  19.1°C, 18km/h, 0.8m  |  jellyfish: Low  |  whales: High  |  choppy (0.8m waves), breezy (18km/h), cold water (19.1°C)  · lifeguard post: yes
  trails: no data
 4. [ 55/100] Praia do Morro das Pedras (5.1km)  Mon 5 Oct, 10:00  |  water: no data  |  18.8°C, 16km/h, 0.9m  |  jellyfish: Low  |  whales: High  |  choppy (0.9m waves), breezy (16km/h), cold water (18.8°C)  · facilities: no data
  trails: no data
 5. [ 55/100] Praia do Campeche      (2.1km)  Mon 5 Oct, 10:00  |  water: no data  |  18.8°C, 18km/h, 0.9m  |  jellyfish: Low  |  whales: High  |  choppy (0.9m waves), breezy (18km/h), cold water (18.8°C)  · parking nearby: 1
  trails: no data
 6. [ 55/100] Praia do Gravatá       (7.6km)  Mon 5 Oct, 10:00  |  water: no data  |  19.1°C, 18km/h, 0.8m  |  jellyfish: Low  |  whales: High  |  choppy (0.8m waves), breezy (18km/h), cold water (19.1°C)  · facilities: no data
  trails nearby: Caminho dos Pescadores (1.3km, no difficulty data)

Top pick — Praia da Armação, Mon 5 Oct, 10:00-11:00
  Water quality   no data
                  Source: IMA/SC
  Sea             19.4°C, waves 0.6m every 6s from the E, swell 0.5m/6s, current 0.4 km/h
  Tide            low 04:00 (-0.3m), high 12:00 (+0.4m), low 18:00 (+0.1m) (hourly resolution, ±30 min)
  Air             18°C, wind 16 km/h from the S, UV 4, 23% chance of rain
  Jellyfish       Low — few of the warm-calm signals present
  Whales          High at this hour; best daylight odds High at 06:00 — humpback season

Asking llama3.2:1b to summarize the top pick (this may take a while)...
Draft summary: Good winds, calm seas, and even whale sightings might make for a pleasant swim amidst the beach crowds.
Reviewer (score 70/100, verdict: approve): Good winds, calm seas, and even whale sightings might make for a pleasant swim amidst the beach crowds.

🌊 Did you know? The ocean has absorbed roughly a quarter to a third of the carbon dioxide humans have emitted since the industrial revolution, and it is measurably more acidic as a result — surface seawater pH has dropped by about 0.1, a 30% increase in acidity. [source: https://www.noaa.gov/education/resource-collections/ocean-coasts/ocean-acidification]
[success] Total time: 79 s (01:19), completed Oct 4, 2026, 12:51:00 PM
```

A `Draft summary:` line followed by a `Reviewer (score …)` line means the whole pipeline worked:
beach discovery, conditions, scoring, summary and review, all live and all local. What else the
output says:

- **The table is the source of truth, not the summary.** Here a 1B model called 0.6 m of chop
  "calm seas", and its own review approved it. A bigger model writes better summaries; the numbers
  do not depend on it.
- **`water: no data`** means the agency had no usable recent sample for that beach; with samples,
  the column names the points and their verdicts
  ([Bathing-water quality](https://docs.marola.dev/5-Repos/marola-app/4-reference/#bathing-water-quality)).
- **Best hours are daylight hours**, and ties go to the hour nearest 10:00 (staffed lifeguard posts,
  best light).
- **"2.1km" for Campeche**, a beach the origin sits next to, is the distance to the beach polygon's
  centre, not its shoreline ([Limitations](LIMITATIONS.md#other-known-limitations-poc-stage-not-hidden)).
- **The closing paragraph** is one of the sourced entries of the app's
  [`sea_lore.json`](https://github.com/marola-dev/marola-app/blob/main/core/src/main/resources/sea_lore.json),
  picked per day and beach; no LLM touches it. `--no-lore` drops it, and `--brief` prints one line
  per beach with no detail block.
- **`whales` and `jellyfish`** are heuristics, not forecasts ([Limitations](LIMITATIONS.md)).

In a marola-app checkout, `just run` first unpacks the corpus release pinned in `corpus.version`
into `.tmp/knowledge` and points the app at it. Running sbt directly does neither, and the app then
reads `./knowledge`, which does not exist, so corpus answers come back unsourced. For a direct sbt
run, in a marola-app checkout, run `just corpus-fetch` and set `MAROLA_KNOWLEDGE_DIR=.tmp/knowledge`.

**Check the `origin ->` line.** With no `--lat`/`--lon` and no `MAROLA_ORIGIN_LAT`/`LON`, marola
geolocates your public IP with three providers, says how many agreed, and widens the radius to
20 km because the answer is city-level. The first command above, run with nothing set, gave:

```text
origin -> lat=52.5244, lon=13.4105 (radius 20km, source: IP geolocation: Berlin, 3/3 providers agree (ipinfo.io, ipwho.is, ip-api.com); city-level accuracy, radius widened to 20km — pass --lat/--lon or set MAROLA_ORIGIN_LAT/LON to pin it)
```

If the city is wrong (a VPN, or an ISP whose block geolocates elsewhere, common in Brazil), pin it:

```bash
# in a marola-app checkout
just run -- --lat -27.6733 --lon -48.4700 --summarize                      # one run
just run -- --location-url 'https://www.google.com/maps/@-27.6733,-48.47,15z'   # or a Google Maps pin
export MAROLA_ORIGIN_LAT=-27.6733 MAROLA_ORIGIN_LON=-48.4700                # this shell
```

The `origin ->` line then names its source: `--lat/--lon flags`, `--location-url (Google Maps pin)`
or `MAROLA_ORIGIN_LAT/MAROLA_ORIGIN_LON`. Quote a Maps URL, which has `!` and `&` in it. A
`maps.app.goo.gl` short link needs expanding first:
`curl -sIL <short link> | grep -i '^location:' | tail -1`. The full origin order is in the
[CLI reference](https://docs.marola.dev/5-Repos/marola-app/4-reference_cli/#recommendation-the-default).

### 4.1 Pinning it permanently: `.env`

Put the two lines in `.env` at the checkout's root (gitignored, never committed;
[`.env.example`](https://github.com/marola-dev/marola-app/blob/main/.env.example) lists every
variable):

```text
MAROLA_ORIGIN_LAT=-27.6733
MAROLA_ORIGIN_LON=-48.4700
```

direnv loads it, through `.envrc`'s `dotenv_if_exists` (run `direnv allow` once after any `.envrc`
change), and so does Docker Compose, through `env_file`. `nix develop` alone does not, and neither
does a plain shell: with only `.env` set, a run still geolocates. Under direnv, leave and re-enter
the directory after editing `.env`. Lines are plain `KEY=VALUE`, with no spaces around `=`.

## 5. The other two CLI paths, same local setup

```bash
# in a marola-app checkout
just run -- --report-sighting jellyfish Arpoador near-shore   # appends to ./data/sightings.jsonl
ollama pull llava                                             # 4.7 GB: photos need a multimodal model
just run -- --analyze-photo ./some-beach-photo.jpg
```

```text
Recorded: Jellyfish sighting at Arpoador (near-shore)
```

In a marola-app checkout, `just run` keeps only a note's first word, because sbt splits the
arguments again: write it as one word. `llama3.2:1b` is text-only; `--analyze-photo` uses `MAROLA_LOCAL_VISION_MODEL`,
`llava` by default. Both are stand-ins for the Telegram bot's handlers (Phase 1).

## 5.1 Ask the ocean notes (local RAG) and the marola model variant

Moved to [Ask the ocean notes](ASK-THE-OCEAN-NOTES.md).

## 5.2 Plug your local model into the public site's chat widget (MIP-0033)

Moved to [Chat and MCP](CHAT-AND-MCP.md): the chat server and its tunnel there, the widget's
configuration on marola-site's
[chat widget page](https://docs.marola.dev/5-Repos/marola-site/1-design_chat-widget/#turning-it-on).

## 6. Troubleshooting

- **`HTTP 404 ... model 'X' not found`**: the model in `MAROLA_LOCAL_LLM_MODEL` (or
  `MAROLA_LOCAL_VISION_MODEL`) isn't pulled. `ollama list` shows what you have; `ollama pull <name>`
  gets it.
- **`HTTP 501 ... This server does not support embeddings`**: the embedder is a chat model. Set
  `MAROLA_LOCAL_EMBED_MODEL` to an embedding model (§3; marola-dev/marola-app#12).
- **Connection refused to `localhost:11434`**: `ollama serve` isn't running, or isn't reachable from
  where marola runs (a container cannot reach the host's `localhost`). In a marola-app checkout,
  `just ollama-serve` starts one in the background, logging to `.tmp/ollama.log`.
- **`Cannot fit name [...sbt-load.sock] in maximum unix domain socket length`**: the checkout's path
  is too long. sbt's boot socket lives under `<checkout>/.tmp/sbt-runtime/`, and a Unix socket path
  is limited to about 107 bytes; a checkout path of 46 characters already failed. Clone to a shorter
  path.
- **It's slow**: CPU-only inference is slow for bigger models, which is why this page uses
  `llama3.2:1b`. An even smaller model exists (`qwen2.5:0.5b`), at the cost of worse
  instruction-following. The reviewer pass needs the model to return well-formed JSON
  ([`Reviewer.scala`](https://github.com/marola-dev/marola-app/blob/main/core/src/main/scala/marola/llm/Reviewer.scala)),
  which smaller models get wrong more often.
- **The reviewer's JSON parsing fails** (`MalformedReviewException`): a small or quantized model
  ignored the "respond with ONLY JSON" instruction. The reviewer already recovers a JSON block
  wrapped in prose, but not a reply with no JSON at all. That is the model's limit, not a marola
  bug; try a larger model if it keeps happening.
- **Overpass returns 429**: its public instance allows two concurrent queries per IP; wait a minute
  between runs ([Limitations](LIMITATIONS.md#other-known-limitations-poc-stage-not-hidden)).

## 7. Regression checks without the network (and how to re-record them)

Moved to marola-app's [Testing](https://docs.marola.dev/5-Repos/marola-app/3-development/#testing):
the golden spec, re-recording the fixtures, E2E and coverage.

## 8. Writing MIPs from voice notes in a browser session

Moved to [Development flow](../3-Ways-of-working/DEV-FLOW.md#voice-notes-into-mips-in-a-browser-session)
§1.

## 9. The map — build the boards once, serve them as a static site (MIP-0005)

Moved to marola-app's
[Site boards](https://docs.marola.dev/5-Repos/marola-app/4-reference_cli/#site-boards) (the
`--site` mode); the map itself, its preview and its deploy are
[marola-site](https://docs.marola.dev/5-Repos/marola-site/3-development/)'s.

## 10. Docker only — no Nix, no sbt, no Ollama install (MIP-0008)

Moved to [Docker](DOCKER.md); the tags, compose profiles and native build are marola-app's
[Images](https://docs.marola.dev/5-Repos/marola-app/3-development/#images).

## 11. The MLflow ledger — every benchmark run on record (MIP-0010)

Moved to marola-app's
[Observability](https://docs.marola.dev/5-Repos/marola-app/3-development/#observability).

## 12. What this guide deliberately doesn't cover

The Telegram bot (there is no bot loop yet; [Telegram bot setup](TELEGRAM-SETUP.md) gets the
credentials ready ahead of Phase 1) and any cloud backend
([ARCHITECTURE](../2-Building-marola/ARCHITECTURE.md) §6: all optional, none needed for anything
above).
