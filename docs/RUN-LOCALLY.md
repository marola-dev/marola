# marola — run it locally, no Telegram, no Azure

A step-by-step guide to running marola's real pipeline end to end on your own machine — nearby
beach discovery, live sea conditions, and an LLM-generated summary reviewed by a second LLM pass —
with a small, fast Ollama model, so you can confirm the whole thing actually works before touching
Telegram or Azure at all. Every command below is real and was run against a live Ollama install
while building this (see `ARCHITECTURE.md` §5's "Status" notes) — the specific model recommended
here is deliberately smaller/faster than the one used to build/verify the rest of this repo
(`dolphin-mixtral:8x7b`, 26GB), chosen for this guide because it's cheap to download and quick to
try, not because it was the model marola was verified against everywhere else.

## 1. Prerequisites

- This repo cloned, with `nix develop` (or `direnv allow`) entered at least once — this puts JDK 25,
  sbt, `just`, and `ollama` itself on `PATH` (`flake.nix` was updated to include `pkgs.ollama` for
  exactly this guide).
- ~2GB free disk for the model below.

## 2. Start Ollama and pull a small model

```bash
# In one terminal, start the Ollama server (stays running in the foreground):
ollama serve
```

```bash
# In another terminal, pull a small, fast model — 1.3GB download, confirmed size:
ollama pull llama3.2:1b
```

Why this model specifically: `llama3.2:1b` is small enough to download in a couple of minutes on a
normal connection and fast enough on CPU alone to get a reply in seconds rather than the ~45
seconds a full-size model can take (confirmed against `dolphin-mixtral:8x7b`, a 26GB model, while
building this repo — see `ARCHITECTURE.md`). It's not the most capable model Ollama can run, but
"is the whole pipeline wired correctly end to end" doesn't need a capable model, just a working
one.

Verify the pull worked and Ollama is actually serving:

```bash
curl http://localhost:11434/api/tags
# {"models":[{"name":"llama3.2:1b", ...}]}
```

## 3. Point marola at it

`llama3.2:1b`'s Ollama tag is the `:1b` variant — marola's own default
(`LocalLlmClient.DefaultModel`) is the plain `llama3.2` tag (Ollama's 3B-parameter default), so
point at the small one explicitly for this guide:

```bash
export MAROLA_LOCAL_LLM_MODEL=llama3.2:1b
# Everything else can stay at its defaults — MAROLA_LLM_PROVIDER=local is already the default,
# and localhost:11434 is already LocalLlmClient's default base URL.
```

## 4. Run the pipeline — the actual E2E check

```bash
# Step 1: the deterministic pipeline alone — no LLM call yet, just Overpass + Open-Meteo +
# the scoring heuristic. This alone confirms network access and the core logic work.
just run

# Step 2: the full pipeline including both LLM passes — the actual "does the whole thing work
# end to end" check. This is the one command this whole guide is building up to.
just run -- --summarize
```

Expected shape of the output — this is a real run from Campeche, Florianópolis, with
`MAROLA_ORIGIN_LAT/LON` set in `.env` (see §4.1) and `llama3.2` as the model. Your beach names and
numbers will differ: it's live data.

```
$ just run -- --summarize
mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --summarize"
[info] welcome to sbt 1.10.7 (N/A Java 25.0.4.1)
...
[info] running marola.Main -- --summarize
marola :: best hour tomorrow to swim nearby (POC)
config -> telegram=unset llm=Local(http://localhost:11434/v1 llama3.2) embed=llama3.2 knowledge=./knowledge -> ./data/knowledge-index.json lore=on ask=General>=0.0 origin=-27.6733,-48.4700 radius=15km water=Auto maps=unset sightings=Local(./data/sightings.jsonl) vision=Local(llava) appinsights=unset
origin -> lat=-27.6733, lon=-48.4700 (radius 15km, source: MAROLA_ORIGIN_LAT/MAROLA_ORIGIN_LON)
water quality -> IMA/SC
 1. [ 55/100] Praia da Joaquina      (4.6km)  Sun 6 Sep, 10:00  |  water: PRÓPRIA (1/1 pts, 25 Aug)  |  19.0°C, 27km/h, 1.3m  |  jellyfish: Low  |  choppy (1.3m waves), breezy (27km/h), cold water (19.0°C)
 2. [ 55/100] Praia do Rio Tavares   (2.1km)  Sun 6 Sep, 10:00  |  water: no data  |  19.0°C, 27km/h, 1.3m  |  jellyfish: Low  |  choppy (1.3m waves), breezy (27km/h), cold water (19.0°C)
 3. [ 55/100] Praia do Morro das Pedras (5.1km)  Sun 6 Sep, 08:00  |  water: PRÓPRIA (1/1 pts, 25 Aug)  |  18.8°C, 27km/h, 1.4m  |  jellyfish: Low  |  choppy (1.4m waves), breezy (27km/h), cold water (18.8°C)
 4. [ 35/100] Praia da Armação       (7.9km)  Sun 6 Sep, 10:00  |  water: 2/6 PRÓPRIA — avoid Ponto 64, Ponto 01, Ponto 05, Ponto 11 (25 Aug)  |  19.0°C, 24km/h, 1.0m  |  jellyfish: Low  |  whales: Moderate  |  choppy (1.0m waves), breezy (24km/h), cold water (19.0°C)
 5. [ 35/100] Praia do Gravatá       (7.6km)  Sun 6 Sep, 10:00  |  water: 2/3 PRÓPRIA — avoid Ponto 04 (25 Aug)  |  19.0°C, 27km/h, 1.3m  |  jellyfish: Low  |  choppy (1.3m waves), breezy (27km/h), cold water (19.0°C)
 6. [ 35/100] Praia do Campeche      (2.1km)  Sun 6 Sep, 08:00  |  water: 4/5 PRÓPRIA — avoid Ponto 73 (25 Aug)  |  18.8°C, 28km/h, 1.4m  |  jellyfish: Low  |  choppy (1.4m waves), breezy (28km/h), cold water (18.8°C)

Top pick — Praia da Joaquina, Sun 6 Sep, 10:00-11:00
  Water quality   PRÓPRIA (1/1 pts, 25 Aug)
                  Ponto 33 (Em frente à Avenida Prefeito Acácio Garibaldi São Thiago, n°1416, ao lado do Posto de Guarda-Vidas): PRÓPRIA, 25 Aug, latest 10 enterococci/100mL, rain Ausente, water 15°C
                  Source: IMA/SC
  Sea             19.0°C, waves 1.3m every 6s from the S, swell 0.8m/6s, current 0.9 km/h
  Tide            low 05:00 (-0.1m), high 13:00 (+0.7m), low 18:00 (+0.3m) (hourly resolution, ±30 min)
  Air             14°C, wind 27 km/h from the S, UV 4, 0% chance of rain
  Jellyfish       Low — few of the warm-calm signals present
  Whales          Low at this hour; best daylight odds Low at 07:00 — humpback season

Asking Local LLM to summarize the top pick (this may take a while)...
Draft summary: Mild conditions all around, so Praia da Joaquina looks like a great spot for swimming today.
Reviewer (score 75/100, verdict: approve): Praia da Joaquina is a great spot for swimming with mild conditions and low jellyfish risk.

🐋 Sea life: Humpback whales (baleia-jubarte) travel up the Brazilian coast from Antarctic feeding grounds to breed in warmer water, passing Santa Catarina between about July and November. Calm mornings with little wind are when a blow or a breach is easiest to spot from shore. [source: https://en.wikipedia.org/wiki/Humpback_whale]
[success] Total time: 41 s, completed Sep 5, 2026, 9:12:57 AM
```

Things in that output worth knowing: Campeche, Armação and Gravatá lost 20 points because one or
more of IMA's sampling points on them was IMPRÓPRIA on 25 Aug — the column names the points, the
detail block (for the top pick) lists every point with its latest count — see `ARCHITECTURE.md`
§5g; best hours are daylight hours, and on a flat day ties resolve toward 10:00 (staffed lifeguard
posts, best light) — `Swimability.hourPreference`; "2.1km" for a beach 200m from the origin is the
distance to the beach polygon's centroid, not its shoreline (§9); most of the 41s is Overpass's
relation query, not the LLM; and the closing paragraph is one of the sourced entries in
`core/src/main/resources/sea_lore.json`, rotated daily, never touched by the LLM. `--brief` gives
the old one-line list; `--no-lore` drops the paragraph.

If you see a `Draft summary:` line followed by a `Reviewer (score .../100, ...)` line, the full
pipeline worked: beach discovery → conditions → scoring → summarization → review, all live, all
local, zero Azure.

Check the `origin ->` line too. With no `--lat/--lon` and no `MAROLA_ORIGIN_LAT/LON` set, marola
geolocates your public IP (three providers, majority vote — see `ARCHITECTURE.md` §3.1) and says so,
including how many providers agreed and that the radius was widened to 20km. If the city it names is
wrong (VPN, or an ISP whose block geolocates elsewhere — common in Brazil), pin it:

```bash
just run -- --lat -27.6733 --lon -48.4700 --summarize     # one-off
just run -- --location-url 'https://www.google.com/maps/@-27.6733,-48.47,15z'   # or paste a Google Maps pin
export MAROLA_ORIGIN_LAT=-27.6733 MAROLA_ORIGIN_LON=-48.4700   # once per shell
```

`--location-url` reads the `@lat,lon`, `q=lat,lon` or `!3dlat!4dlon` part of a Google Maps URL
(quote it — the URL has `!` and `&` in it). A `maps.app.goo.gl` short link needs expanding first:
`curl -sIL <short link> | grep -i '^location:' | tail -1`.

### 4.1 Pinning it permanently: `.env`

Put the two lines in `.env` at the repo root (gitignored, never committed — `.env.example` lists
every variable):

```
MAROLA_ORIGIN_LAT=-27.6733
MAROLA_ORIGIN_LON=-48.4700
```

Both ways into the dev shell load it: `flake.nix`'s `shellHook` sources it on `nix develop` (you'll
see `loaded .../.env`), and `.envrc`'s `dotenv_if_exists` does the same under direnv (run
`direnv allow` once after any `.envrc` change). Re-enter the shell after editing `.env`; the
`origin ->` line then reports `source: MAROLA_ORIGIN_LAT/MAROLA_ORIGIN_LON`. Lines must be plain
`KEY=VALUE` shell syntax — no spaces around `=`.

## 5. The other two CLI paths, same local setup

These don't need anything beyond what §2-3 already set up:

```bash
# Record a sighting report (writes to ./data/sightings.jsonl):
just run -- --report-sighting jellyfish Arpoador "spotted near shore"

# Analyze a photo — needs a MULTIMODAL model, which llama3.2:1b is not (it's text-only).
# Pull one first if you want to try this path:
ollama pull llava
export MAROLA_LOCAL_VISION_MODEL=llava
just run -- --analyze-photo ./some-beach-photo.jpg
```

## 5.1 Ask the ocean notes (local RAG) and the marola model variant

```bash
# Grounded Q&A over knowledge/*.md — first run embeds the corpus with llama3.2 (seconds on a GPU,
# a few minutes on CPU), later runs reuse ./data/knowledge-index.json:
just ask "what should I do if I get caught in a rip current?"

# Expected shape: an answer with [n] citations, then the passages' sources
#   If you are caught in a rip current, stay calm and float to conserve energy [3]. Swim parallel to
#   the shoreline ... [3].
#   Sources:
#     [1] Rip currents — https://www.weather.gov/safety/ripcurrent (score 0.40)
#     ...

# Tier-1 "fine-tune": llama3.2 with marola's persona/decoding baked in (finetune/Modelfile):
just finetune-model
MAROLA_LOCAL_LLM_MODEL=marola-llama3.2 just run -- --summarize
```

See `finetune/README.md` for the QLoRA (Tier 2) recipe, which is written but not run here.

`--ask` answers from the corpus when a passage scores above `MAROLA_ASK_MIN_SCORE` (default 0.3)
and otherwise, by default, from the model's general knowledge with a visible "(unsourced)" label —
`MAROLA_ASK_FALLBACK=strict` makes it abstain instead. Which is better, and by how much, is what
the benchmark measures:

```bash
just benchmark        # 22 ocean questions × {plain prompt, marola strict, marola general}
                      # → coverage / citations / abstentions / latency, verdict, data/benchmark-*.md
```

Compare with `docs/benchmarks/2026-09-05.md` — the kept reference run and what it taught.

## 6. Troubleshooting

- **`HTTP 404 ... model 'X' not found`** — the model named in `MAROLA_LOCAL_LLM_MODEL` (or
  `MAROLA_LOCAL_VISION_MODEL`) isn't pulled. Run `ollama list` to see what you actually have, or
  `ollama pull <name>` to get it.
- **Connection refused to `localhost:11434`** — `ollama serve` isn't running, or isn't running in
  this same environment (e.g. a container that can't reach the host's Ollama). `flake.nix`'s
  `shellHook` checks for this and prints a reminder every time you enter the dev shell.
- **It's slow** — CPU-only inference is genuinely slow for bigger models; that's exactly why this
  guide recommends `llama3.2:1b` instead of whatever larger model you might already have pulled for
  other purposes. If it's still too slow, an even smaller model exists (e.g. `qwen2.5:0.5b`), at
  the cost of noticeably worse instruction-following — the reviewer pass in particular depends on
  the model reliably producing well-formed JSON (see `llm/Reviewer.scala`), which smaller models
  are more likely to get wrong.
- **The reviewer's JSON parsing fails** (`MalformedReviewException` or similar in the output) — a
  known, if infrequent, failure mode with smaller/quantized models that ignore the "respond with
  ONLY JSON" instruction (see `Reviewer.scala`'s `extractJsonObject` — it already recovers from a
  JSON block wrapped in prose, but a model that doesn't produce JSON *at all* isn't recoverable).
  Confirms `llama3.2:1b`'s instruction-following limits, not a marola bug — try a larger model if
  this happens consistently.

## 7. Regression checks without the network (and how to re-record them)

`just test` runs a full-pipeline regression with **no** network and **no** Ollama:
`cli/src/test/scala/marola/PipelineGoldenSpec.scala` replays real responses recorded on 2026-09-05
(`cli/src/test/resources/fixtures/`: Overpass for Campeche, Open-Meteo for two beaches, IMA's
points) through the unchanged production code, and asserts the ranking, the water-quality verdicts,
the tide turns and the exact number of HTTP calls. `SummarizeFlowSpec` and `RagOfflineSpec` do the
same for the LLM and RAG plumbing with scripted models. This is what CI runs on every push.

When an upstream format changes, re-record — the fixture diff is the change report:

```bash
# Overpass (the exact query BeachFinder builds, 15km around Campeche)
q='[out:json][timeout:45];(node["natural"="beach"]["name"](around:15000,-27.6733,-48.47);way["natural"="beach"]["name"](around:15000,-27.6733,-48.47);relation["natural"="beach"]["name"](around:15000,-27.6733,-48.47););out center 500;'
curl -s --data-urlencode "data=$q" https://overpass-api.de/api/interpreter > cli/src/test/resources/fixtures/overpass-campeche.json
# Open-Meteo (same variables OpenMeteoClient asks for), one weather + one marine per fixture beach
# IMA: curl -s -X POST https://balneabilidade.ima.sc.gov.br/relatorio/mapa, trimmed to the points near Campeche
```

Then update the pinned date in `PipelineGoldenSpec` (`fixedToday`) to the day the forecasts cover.
The live equivalents run on demand only: `just e2e` locally, or the manual `marola-e2e.yml`
workflow (its network job needs no Ollama; the LLM job is opt-in and caches the model).

## 8. Writing MIPs from voice notes in a browser session

`just context-mips` packs the documents a MIP author needs (README, AGENTS.md, ARCHITECTURE,
FUTURE-WORK, the `mip` skill, every existing MIP — no code, ~35k tokens) with repomix into
`.tmp/marola-context-mips.md` and copies it to the clipboard. In a browser Claude chat: paste, attach
the WhatsApp voice notes (`.ogg`) or chat text, and say "convert the audios into MIP proposals".
The pack's own instruction section (`repomix-instruction.md`) fixes the template, numbering, the
transcript appendix and the rule that unverified claims go under "Open questions". Save the
returned files under `docs/mips/` and let the in-repo agent verify the sources.

## 9. The map — build the boards once, serve them as a static site (MIP-0005)

Everything above answers one person at a time. `just site-build` runs the same pipeline once per
*area* (`site/areas.json`: Florianópolis and Rio by default) and writes what a static map needs:

```bash
just site-build floripa        # ~70 s live: one Overpass query, two Open-Meteo calls per beach, one IMA download
just site-serve                # http://localhost:8000 — tap Praia do Campeche, see Ponto 73 flagged
```

`site/dist/` (git-ignored) then holds `index.html` + `app.js` + vendored Leaflet from
`site/static/`, and under `data/`: `areas.json`, and per area `<today>.json`, `<tomorrow>.json`
(the board — `site/board.schema.json` is the contract, checked by `BoardSpec`) and `latest.json`
pointing at both. The page shows every beach as a marker coloured by score, a card with the same
numbers the CLI prints, a day picker, an hour slider, the generated-at time and every source. No
cookies, no analytics; "near me" is the browser's own geolocation, on request, never sent anywhere.

Keep it fresh locally with a timer — a plain cron line (`crontab -e`):

```
15 */3 * * *  cd /path/to/marola && nix develop -c just site-build >> .tmp/site-build.log 2>&1
```

or a `systemd --user` timer with the same command. Publishing: `just site-deploy` triggers
`.github/workflows/site.yml` (build on the runner, deploy to GitHub Pages — the same workflow runs
every 3 h on its own and on every merge to `main` that touches `site/` or the pipeline; the result
is https://h0ffmann.github.io/marola/), `just site-deploy cloudflare` pushes a local `site/dist` with wrangler.
Tiles come from OpenStreetMap's public servers, which is fine for a link shared among friends and
not for a public launch — switch `tiles` in `site/areas.json` to a Protomaps/MapTiler source
before that (MIP-0005 §8).

## 10. What this guide deliberately doesn't cover

Telegram bot setup (there is no bot loop yet — see `TELEGRAM-SETUP.md` for credential setup ahead
of that Phase 1 work) and any Azure integration (`ARCHITECTURE.md` §5/§6, all optional, none needed
for anything above). This guide is specifically the "prove it works, cheaply, before touching
anything else" path.
