# Run it locally

A step-by-step guide to running marola's real pipeline end to end on your own machine: nearby
beach discovery, live sea conditions, and an LLM-generated summary reviewed by a second LLM pass,
with a small, fast Ollama model, so you can confirm the whole thing actually works before touching
Telegram or any cloud. Every command below is real and was run against a live Ollama install
while building this (see `ARCHITECTURE.md` §5's "Status" notes). The specific model recommended
here is deliberately smaller/faster than the one used to build/verify the rest of this repo
(`dolphin-mixtral:8x7b`, 26GB), chosen for this guide because it's cheap to download and quick to
try, not because it was the model marola was verified against everywhere else.

## 1. Prerequisites

- This repo cloned, with `nix develop` (or `direnv allow`) entered at least once. This puts JDK 25,
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
building this repo; see `ARCHITECTURE.md`). It's not the most capable model Ollama can run, but
"is the whole pipeline wired correctly end to end" doesn't need a capable model, just a working
one.

Verify the pull worked and Ollama is actually serving:

```bash
curl http://localhost:11434/api/tags
# {"models":[{"name":"llama3.2:1b", ...}]}
```

## 3. Point marola at it

`llama3.2:1b`'s Ollama tag is the `:1b` variant. marola's own default
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

Expected shape of the output: this is a real run from Campeche, Florianópolis, with
`MAROLA_ORIGIN_LAT/LON` set in `.env` (see §4.1) and `llama3.2` as the model. Your beach names and
numbers will differ: it's live data.

```
$ just run -- --summarize
mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --summarize"
[info] welcome to sbt 1.10.7 (N/A Java 25.0.4.1)
...
[info] running marola.Main -- --summarize
marola :: best hour tomorrow to swim nearby (POC)
config -> telegram=unset llm=Local(http://localhost:11434/v1 llama3.2) embed=llama3.2 knowledge=./knowledge -> ./data/knowledge-index.json lore=on ask=General>=0.0 origin=-27.6733,-48.4700 radius=15km water=Auto sightings=Local(./data/sightings.jsonl) vision=Local(llava)
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
more of IMA's sampling points on them was IMPRÓPRIA on 25 Aug. The column names the points, the
detail block (for the top pick) lists every point with its latest count; see `ARCHITECTURE.md`
§5g; best hours are daylight hours, and on a flat day ties resolve toward 10:00 (staffed lifeguard
posts, best light), `Swimability.hourPreference`; "2.1km" for a beach 200m from the origin is the
distance to the beach polygon's centroid, not its shoreline (§9); most of the 41s is Overpass's
relation query, not the LLM; and the closing paragraph is one of the sourced entries in
`core/src/main/resources/sea_lore.json`, rotated daily, never touched by the LLM. `--brief` gives
the old one-line list; `--no-lore` drops the paragraph.

If you see a `Draft summary:` line followed by a `Reviewer (score .../100, ...)` line, the full
pipeline worked: beach discovery → conditions → scoring → summarization → review, all live, all
local, zero cloud.

Check the `origin ->` line too. With no `--lat/--lon` and no `MAROLA_ORIGIN_LAT/LON` set, marola
geolocates your public IP (three providers, majority vote; see `ARCHITECTURE.md` §3.1) and says so,
including how many providers agreed and that the radius was widened to 20km. If the city it names is
wrong (VPN, or an ISP whose block geolocates elsewhere, common in Brazil), pin it:

```bash
just run -- --lat -27.6733 --lon -48.4700 --summarize     # one-off
just run -- --location-url 'https://www.google.com/maps/@-27.6733,-48.47,15z'   # or paste a Google Maps pin
export MAROLA_ORIGIN_LAT=-27.6733 MAROLA_ORIGIN_LON=-48.4700   # once per shell
```

`--location-url` reads the `@lat,lon`, `q=lat,lon` or `!3dlat!4dlon` part of a Google Maps URL
(quote it: the URL has `!` and `&` in it). A `maps.app.goo.gl` short link needs expanding first:
`curl -sIL <short link> | grep -i '^location:' | tail -1`.

### 4.1 Pinning it permanently: `.env`

Put the two lines in `.env` at the repo root (gitignored, never committed; `.env.example` lists
every variable):

```
MAROLA_ORIGIN_LAT=-27.6733
MAROLA_ORIGIN_LON=-48.4700
```

Both ways into the dev shell load it: `flake.nix`'s `shellHook` sources it on `nix develop` (you'll
see `loaded .../.env`), and `.envrc`'s `dotenv_if_exists` does the same under direnv (run
`direnv allow` once after any `.envrc` change). Re-enter the shell after editing `.env`; the
`origin ->` line then reports `source: MAROLA_ORIGIN_LAT/MAROLA_ORIGIN_LON`. Lines must be plain
`KEY=VALUE` shell syntax, no spaces around `=`.

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
and otherwise, by default, from the model's general knowledge with a visible "(unsourced)" label;
`MAROLA_ASK_FALLBACK=strict` makes it abstain instead. Which is better, and by how much, is what
the benchmark measures:

```bash
just benchmark        # 22 ocean questions × {plain prompt, marola strict, marola general}
                      # → coverage / citations / abstentions / latency, verdict, data/benchmark-*.md
```

Compare with `docs/benchmarks/2026-09-05.md`: the kept reference run and what it taught.

## 5.2 Plug your local model into the public site's chat widget (MIP-0033)

`marola.dev` (or wherever `site/dist/` is served) ships a chat widget that stays hidden until it
finds a working endpoint, no server dependency by default. To turn it on, run marola's own tiny
HTTP server and expose it through a **named** Cloudflare Tunnel (a quick/ephemeral tunnel's URL
changes every restart, which would break the widget's saved config):

```bash
# 1. Run marola's chat server (wraps --ask's RAG + the MIP-0022 safety footer over HTTP).
#    Needs the same Ollama setup as §2-3; it blocks in the foreground, Ctrl+C to stop.
just run -- --serve-chat            # http://localhost:8787 — GET /health, POST /ask
# just run -- --serve-chat 9000     # a different port

# 2. In another terminal: a one-time cloudflared login, then a NAMED tunnel (free, no account
#    beyond a Cloudflare login, no port-forwarding on your router).
cloudflared tunnel login
cloudflared tunnel create marola-chat
cloudflared tunnel route dns marola-chat chat.<your-domain>   # or use the trycloudflare.com URL
                                                                # cloudflared prints for a quick test
cloudflared tunnel run --url http://localhost:8787 marola-chat
```

Then point the widget at that URL: edit `site/static/chatbot-config.js`:

```js
window.MAROLA_CHAT_ENDPOINT = "https://chat.<your-domain>"; // or the trycloudflare.com URL
```

`just site-build` copies `site/static/*` (including this file) into `site/dist/` as-is. Leave
`MAROLA_CHAT_ENDPOINT` empty to keep the widget hidden. That's the default, committed state, so a
fresh clone's site never shows a chat button pointing nowhere. The widget calls `/health` on page
load and only reveals its toggle button on a 200; a later failure while chatting shows an honest
"chatbot offline" message rather than hanging. This narrowly overrides MIP-0005 §9's "no server"
decision for the site: the maintainer's own machine becomes a real, if intermittent, origin;
uptime is whatever the maintainer's machine and tunnel happen to be, by design (MIP-0033 §6).

## 6. Troubleshooting

- **`HTTP 404 ... model 'X' not found`**: the model named in `MAROLA_LOCAL_LLM_MODEL` (or
  `MAROLA_LOCAL_VISION_MODEL`) isn't pulled. Run `ollama list` to see what you actually have, or
  `ollama pull <name>` to get it.
- **Connection refused to `localhost:11434`**: `ollama serve` isn't running, or isn't running in
  this same environment (e.g. a container that can't reach the host's Ollama). `flake.nix`'s
  `shellHook` checks for this and prints a reminder every time you enter the dev shell.
- **It's slow**: CPU-only inference is genuinely slow for bigger models; that's exactly why this
  guide recommends `llama3.2:1b` instead of whatever larger model you might already have pulled for
  other purposes. If it's still too slow, an even smaller model exists (e.g. `qwen2.5:0.5b`), at
  the cost of noticeably worse instruction-following. The reviewer pass in particular depends on
  the model reliably producing well-formed JSON (see `llm/Reviewer.scala`), which smaller models
  are more likely to get wrong.
- **The reviewer's JSON parsing fails** (`MalformedReviewException` or similar in the output): a
  known, if infrequent, failure mode with smaller/quantized models that ignore the "respond with
  ONLY JSON" instruction (see `Reviewer.scala`'s `extractJsonObject`; it already recovers from a
  JSON block wrapped in prose, but a model that doesn't produce JSON *at all* isn't recoverable).
  Confirms `llama3.2:1b`'s instruction-following limits, not a marola bug. Try a larger model if
  this happens consistently.

## 7. Regression checks without the network (and how to re-record them)

`just test` runs a full-pipeline regression with **no** network and **no** Ollama:
`cli/src/test/scala/marola/PipelineGoldenSpec.scala` replays real responses recorded on 2026-09-05
(`cli/src/test/resources/fixtures/`: Overpass for Campeche, Open-Meteo for two beaches, IMA's
points) through the unchanged production code, and asserts the ranking, the water-quality verdicts,
the tide turns and the exact number of HTTP calls. `SummarizeFlowSpec` and `RagOfflineSpec` do the
same for the LLM and RAG plumbing with scripted models. This is what CI runs on every push.

When an upstream format changes, re-record: the fixture diff is the change report:

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
FUTURE-WORK, the `mip` skill, every existing MIP, no code, ~35k tokens) with repomix into
`.tmp/marola-context-mips.md` and copies it to the clipboard. In a browser Claude chat: paste, attach
the WhatsApp voice notes (`.ogg`) or chat text, and say "convert the audios into MIP proposals".
The pack's own instruction section (`repomix-instruction.md`) fixes the template, numbering, the
transcript appendix and the rule that unverified claims go under "Open questions". Save the
returned files under `docs/MIPs/` and let the in-repo agent verify the sources.

## 9. The map — build the boards once, serve them as a static site (MIP-0005)

Everything above answers one person at a time. `just site-build` runs the same pipeline once per
*area* (`site/areas.json`: Florianópolis, Rio de Janeiro and Salvador by default) and writes what a static map needs:

```bash
just site-build floripa        # ~70 s live: one Overpass query, two Open-Meteo calls per beach, one IMA download
just site-serve                # http://localhost:8000 — tap Praia do Campeche, see Ponto 73 flagged
```

`site/dist/` (git-ignored) then holds `index.html` + `app.js` + vendored Leaflet from
`site/static/`, and under `data/`: `areas.json`, and per area `<today>.json`, `<tomorrow>.json`
(the board; `site/board.schema.json` is the contract, checked by `BoardSpec`) and `latest.json`
pointing at both. The page shows every beach as a wave marker coloured by score: hover it (tap, on a phone: the same row opens first in the card) for the six aspects at that hour: wind band with its emoji and km/h, water temperature, waves, jellyfish, whales, water verdict (MIP-0009), a card with the same
numbers the CLI prints, a day picker, an hour slider, the generated-at time and every source. No
cookies, no analytics; "near me" is the browser's own geolocation, on request, never sent anywhere.
If `site/dist/smoke/latest.json` exists (the docker smoke test's last run, §10; `site.yml` copies
it from the `site-data` branch; locally `git archive origin/site-data smoke | tar -x -C site/dist`,
or `python3 scripts/smoke_record.py record …` on any `--summarize` transcript) the footer adds a
"Last live run" panel: model, image, top pick, the reviewed sentence labelled as model text with
the reviewer's verdict (hidden on `reject`), the last ten runs, and a dashed marker at the run's
origin.

Keep it fresh locally with a timer, a plain cron line (`crontab -e`):

```
15 */3 * * *  cd /path/to/marola && nix develop -c just site-build >> .tmp/site-build.log 2>&1
```

or a `systemd --user` timer with the same command. Only `site/dist` is ever published, and
`site.yml` fails if anything outside its allowlist (the page, `vendor/`, `data/`, `smoke/`) is
in there. The repository is private, the map is public, and `docs/*.md` stay on GitHub rather
than becoming pages (Pages source must be "GitHub Actions", never "Deploy from a branch", which
would run Jekyll over the whole branch). Publishing: `just site-deploy` triggers
`.github/workflows/site.yml` (build on the runner, deploy to GitHub Pages; the same workflow runs
every 3 h on its own and on every merge to `main` that touches `site/` or the pipeline; the result
is https://marola.dev/, GitHub Pages' custom domain; see `.github/workflows/site.yml`'s header
comment for the CNAME/DNS setup), `just site-deploy cloudflare` pushes a local `site/dist` with wrangler.
Tiles come from OpenStreetMap's public servers, which is fine for a link shared among friends and
not for a public launch. Switch `tiles` in `site/areas.json` to a Protomaps/MapTiler source
before that (MIP-0005 §8).

## 10. Docker only — no Nix, no sbt, no Ollama install (MIP-0008)

**Published tags** (`ghcr.io/marola-dev/marola:<tag>`; until the package is made public (MIP-0065 §4.3), pulling
needs `docker login ghcr.io` first: a GitHub PAT with `read:packages`, or `gh auth token | docker login
ghcr.io -u <user> --password-stdin`):

| Tag | What it is | Built by | Platforms |
|---|---|---|---|
| `jvm` | CLI on a Temurin 25 JRE (Alpine) — moving, always the latest `main` | `docker.yml`, every merge touching the Dockerfile/build/`core`/`cli`/etc. | amd64 + arm64 |
| `jvm-<sha>` | same, pinned to one commit | same | amd64 + arm64 |
| `native` | the same CLI ahead-of-time compiled (GraalVM native-image), distroless, no JVM — moving | same workflow | amd64 |
| `native-<sha>` | same, pinned | same | amd64 |
| `local` | Ollama with the `marola-llama3.2` fine-tune baked in — moving, but only advances when `just benchmark` clears the gate | `docker-local.yml` | amd64 |
| `local-<sha>` | one benchmark candidate, kept whether or not it was promoted | same | amd64 |
| `dev` / `dev-<sha>` | the literal `nix develop` shell in a container, for reading/hacking without installing Nix | `docker.yml`, `workflow_dispatch` only | amd64 |

**Built with Llama.** `:local` redistributes Meta's Llama 3.2 weights under the [Llama 3.2
Community License](https://www.llama.com/llama3_2/license/); the agreement and the Acceptable Use
Policy ship inside the image (`ollama show marola-llama3.2 --license`).

The `-<sha>` tags accumulate on every qualifying push (`local-<sha>` even for rejected candidates,
which bundle the ~2 GB Ollama model) and nothing prunes them: a public package's storage is free
(MIP-0065 §4.4). Everything below and `docker-compose.yml`/`docker-smoke.yml` pull the moving tags
above. `just gh-billing` shows current GHCR/Actions usage against the account's plan.

The same pipeline from a machine that has Docker and nothing else. `docker-compose.yml` runs the
CLI image with an Ollama sidecar; the model is pulled once into a named volume:

```bash
docker compose run --rm marola --brief --lat -27.6733 --lon -48.47                         # no LLM
docker compose --profile ollama run --rm marola --summarize --lat -27.6733 --lon -48.47    # + draft + reviewer (llama3.2, 2 GB pulled once)
docker compose --profile local run --rm marola-local --summarize --lat -27.6733 --lon -48.47   # the marola-llama3.2 variant, built from finetune/Modelfile
```

`.env` is read if present (origin, provider switches; `.env.example`) and never copied into
the image; `MAROLA_LOCAL_LLM_MODEL=llama3.2:1b` picks the small model from §2. Without compose,
against an Ollama already running on the host:

```bash
docker run --rm --network host ghcr.io/marola-dev/marola:jvm --summarize --lat -27.6733 --lon -48.47
```

`:local` is `finetune/README.md`'s "As an image". `just docker-build` builds any target here and
`just docker-run -- …` runs it with `--network host`. The
`Dockerfile` is one multi-stage file: `builder` (sbt, Temurin 25) → `jvm` (Temurin 25 JRE on
Alpine, ~70 MB + the 55 MB jar), `native-build` → `native` (below), and `dev`: the literal
`nix develop` in an image, for reading or hacking on the code without installing Nix
(`docker run -it marola:dev bash`). Lint: `just quality` runs hadolint on it (from the lint lab).

**Native binary (GraalVM).** The same CLI compiled ahead of time: one 69 MB executable, no JVM,
~75 MB of RSS, on a distroless image (`ghcr.io/marola-dev/marola:native`, amd64). Everything
`just run` does works, `--summarize` and the reviewer included (verified live 2026-09-05 with
`llama3.2:1b`); the MCP server stays on the JVM image. Locally:

```bash
just native-image                                            # GraalVM from nixpkgs, sbt cli/nativeImage → cli/target/marola (~1 min)
just native-run -- --summarize --lat -27.6733 --lon -48.47   # the binary, same flags as `just run`
just docker-build native                                     # the distroless image, if you have a daemon
```

**The smoke test.** GitHub → Actions → "docker smoke test" → Run workflow (`lat`/`lon`, or a
Google Maps pin in `maps_url`, `model`, `image`) runs `--summarize` in the published image on a
runner with a cached `llama3.2:1b`, also every morning at 09:30 UTC. `scripts/smoke_record.py`
turns the transcript into `smoke/latest.json` + `smoke/history.json` on the orphan `site-data`
branch (never deployed by that workflow: `site.yml` copies it into the map, so two deploys never
race), the map's footer shows it as "Last live run" and the job fails when the pipeline, the
model or the reviewer did not answer. `python3 scripts/smoke_record.py --self-test` (in `just
quality`) parses a recorded transcript, `scripts/fixtures/smoke-stdout-2026-09-05.txt`.

The arguments (`--initialize-at-build-time` for slf4j/logback/Jackson, `-march=compatibility`)
and the reachability metadata (the `*.json` resources, `sun.misc.Signal` for Kyo's handler) live
in `cli/src/main/resources/META-INF/native-image/`, read from the classpath, so the sbt task and
the Dockerfile's `native-image -jar` build the same thing.

## 11. The MLflow ledger — every benchmark run on record (MIP-0010)

Optional, developer-only, off unless you ask for it. `just benchmark` writes a Markdown report under
`data/` and that stays the canonical result (`scripts/benchmark_gate.py` reads it); with a tracking
URI set, the same run is *also* logged to a local MLflow server (params, per-arm metrics, the
report as an artifact) so runs can be compared in a UI instead of by diffing tables. Needs Docker
(the server is a compose profile, never part of the Nix shell or the runtime image):

```bash
just mlflow-up                                   # ghcr.io/mlflow/mlflow, SQLite + artifacts under .tmp/mlflow/, http://127.0.0.1:5000
export MAROLA_MLFLOW_TRACKING_URI=http://127.0.0.1:5000
just benchmark                                   # the usual report under data/ — plus one run in experiment "marola/benchmark"
just mlflow-ui                                   # the run: params model/embed_model/min_score/corpus_sha/git_sha, metrics per arm, the .md attached
just mlflow-down                                 # stop it; .tmp/mlflow/ keeps the history, `rm -rf .tmp/mlflow` wipes it
```

**Traces too.** The same server ingests OpenTelemetry traces: with `MAROLA_TRACES=mlflow` every
recommendation is one trace: `marola.recommend` → `bestPerBeachTomorrow` + `llm.<model>` for the
draft and for the review, each LLM span with `gen_ai.request.model`, message count and prompt/
completion sizes (latency is the span itself). Prompt and completion *text* are attached only if
you also set `MAROLA_TRACE_CONTENT=1`: the prompt has your coordinates in it.

```bash
MAROLA_TRACES=mlflow MAROLA_MLFLOW_TRACKING_URI=http://127.0.0.1:5000 just run -- --summarize
# ... unchanged output; then in the UI: experiment "marola/traces", one trace, 4 spans
```

If the server is not up, marola prints `(traces disabled: …)` and carries on untraced.

`MAROLA_MLFLOW_EXPERIMENT` (default `marola`) is the experiment *prefix*: runs land in
`marola/benchmark`, the DSPy compile step's in `marola/prompt-compile`, traces (§5f of
`ARCHITECTURE.md`, `MAROLA_TRACES=mlflow`) in `marola/traces`. Unset `MAROLA_MLFLOW_TRACKING_URI`
and nothing changes: no network call, `RunLedger.Noop`, the report is still written. The server is
bound to `127.0.0.1` only and has no authentication. Do not expose the port. `docker compose run`
of the `marola` services passes the two variables through from your shell/`.env` when they are set
and omits them otherwise (the `mlflow` profile is independent: `--profile mlflow --profile ollama`
starts both, neither depends on the other).

## 12. What this guide deliberately doesn't cover

Telegram bot setup (there is no bot loop yet; see `TELEGRAM-SETUP.md` for credential setup ahead
of that Phase 1 work) and any cloud backend (`ARCHITECTURE.md` §6, all optional, none needed for
anything above). This guide is specifically the "prove it works, cheaply, before touching
anything else" path.
