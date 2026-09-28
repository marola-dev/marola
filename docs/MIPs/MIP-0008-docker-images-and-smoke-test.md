# MIP-0008: Docker images — lightweight JVM, native (GraalVM), marola-ollama, a fine-tuned variant — built in CI, with a smoke test the map shows

| | |
|---|---|
| **Status** | Implemented — PRs #26 → #27 → #28 → #29 → #30 → #31 → #32 (stack merged 2026-09-05; tasks and v1 decisions: [`MIP-0008.tasks.md`](./MIP-0008.tasks.md)); cost ~$15.08 across the seven PRs (measured with `just cost-split MIP-0008 --session d802fa69`) on top of this document's own $0.90. The native-image spike (§11.1) succeeded: `:native` shipped |
| **Author** | Claude Fable 5.1, for M. Hoffmann (request of 5 Sep 2026, during the MIP-0005 implementation session) |
| **Created** | 2026-09-05 |
| **Tasks** | `docs/MIPs/MIP-0008.tasks.md` — stacked PRs, one per task |
| **Phase** | 3 (deploy artefacts) — no cloud resource; the Phase 1 prerequisite (the Telegram bot, MIP-0002) is still missing and is not needed for anything here |
| **Related** | `ARCHITECTURE.md` §11 (Phase 3), `RUN-LOCALLY.md` (what "everything I do with nix develop" means), `finetune/README.md` (the two fine-tuning tiers), MIP-0005 (the site that will show the smoke test), `marola-e2e.yml` (the existing Ollama-in-CI recipe) |
| **Effort** | XL — four Dockerfile targets incl. a GraalVM native-image spike, compose, 3 CI workflows, a benchmark gate; 7 stacked PRs |
| **Gain** | infra/dev-loop (anyone with Docker can run marola with no Nix); cost/ops (a versioned, gated `marola-local` artifact) |
| **Effort vs Gain** | cheap win, delivered — high leverage for a scoped, no-cloud-spend Phase 3 deploy artifact |
| **Depends on** | none blocking; Phase 3, explicitly does not need MIP-0002/Phase 1; no cloud resource (GHCR + Actions only) |
| **Risk** | the native-image path is one Kyo/GraalVM upgrade away from breaking silently — reachability metadata is hand-maintained |
| **Cost so far** | ~$15.98 total (~$15.08 across 7 PRs #26–#32, per the status row, + ~$0.90 for the MIP draft itself) |

## 1. Summary

Ship marola as container images, so anyone can run what `nix develop` + `just run` does without
Nix: a **lightweight JVM image** (`marola:jvm`), a **native binary image** (`marola:native`,
GraalVM native-image, JDK 25), a **`marola-ollama` compose stack** (marola + an Ollama sidecar
with the model pre-pulled, the `just run -- --summarize` path), and **`marola-local`**: the same
stack with the fine-tuned `marola-llama3.2` model baked in (`finetune/`), versioned like code.
All built and published to GHCR by CI. A manually triggered workflow runs a **smoke test**:
`--summarize` at a fixed or given location (lat/lon fields, or a Google Maps pin URL) with a small
model, and publishes its output where the map (MIP-0005) shows it as a "last live run" panel.

## 2. Motivation

- `docs/1-Using-marola/RUN-LOCALLY.md` needs Nix, sbt, JDK 25 and a running Ollama. A friend with Docker and
  nothing else cannot run marola today; `docker compose up` should be enough.
- Phase 3 (`ARCHITECTURE.md` §11) needs an image anyway; building it now, without a cloud
  resource, keeps the free-first rule and gives the deploy step a tested artefact.
- The `--summarize` path (LLM + reviewer) is only exercised by `just e2e` on a developer's
  machine or by the manual `marola-e2e.yml`. A scheduled/triggerable smoke test with a visible
  result is the cheapest continuous check that the whole thing (pipeline, prompt, reviewer,
  model) still produces a sane sentence.
- The fine-tune work (`finetune/`, Tier 1 verified, Tier 2 written-not-run) has no delivery path:
  a model that only exists on one laptop's Ollama is not an artefact. An image tag is.

## 3. User-visible change

```bash
docker run --rm ghcr.io/h0ffmann/marola:jvm --brief --lat -27.6733 --lon -48.47     # ranked list, ~60 MB image + JRE
docker run --rm ghcr.io/h0ffmann/marola:native --brief --lat -27.6733 --lon -48.47  # same, one static binary, sub-second start
docker compose --profile ollama run marola --summarize --lat -27.6733 --lon -48.47  # + Ollama sidecar, llama3.2 pulled once into a volume
docker compose --profile local run marola --summarize ...                            # marola-llama3.2 (finetune/Modelfile) instead of stock llama3.2
```

GitHub → Actions → "docker smoke test" → Run workflow: fields `lat`, `lon` **or** `maps_url`
(a Google Maps pin, e.g. `https://www.google.com/maps/@-27.6733,-48.47,15z` or a `maps.app.goo.gl`
short link), `model` (default `llama3.2:1b`), `image` (default the latest `:jvm`). The run's
stdout (the same text `just run -- --summarize` prints) is uploaded as an artifact **and** pushed
as JSON to the site: the map's footer gains a **"Last live run"** panel (when, where (a marker),
top pick, the model's summary, the reviewer's verdict, a link to the run), refreshed by the next
site build (see §5.5 for how the refresh works without two deploys racing).

## 4. Data sources and dependencies reviewed

All checked 2026-09-05 against the live registries/APIs; nothing below was run yet.

- **GraalVM.** `graalvm/graalvm-ce-builds` latest release `graal-25.3.4.1` (2026-08-25) ships
  `graalvm-community-jdk-25i3-25.0.4.1_linux-x64`: JDK 25 native-image exists. Container:
  `ghcr.io/graalvm/native-image-community` (tag for 25 **not checked**, GHCR needs a token to
  list). sbt plugin: `scalameta/sbt-native-image` v0.5.0 (2026-06-14). Feasibility with **Kyo
  1.0.0-RC5 + Scala 3.9** is the open question (§11): Kyo's `Frame` is compile-time, `java.net.http`
  is supported by native-image, but `logback` and the MCP SDK's Jackson need reachability metadata;
  the tracing agent (`-agentlib:native-image-agent`) over `PipelineGoldenSpec` generates it. The
  native target is the CLI `Main`; the MCP server stays JVM until proven.
- **JVM base.** `eclipse-temurin:25-jre` (Temurin publishes JRE tags per feature version;
  the exact `25-jre-alpine` tag **not checked**). Alternative: `jlink` a minimal runtime onto
  `gcr.io/distroless/java-base`. Fat jar via `sbt-assembly` (already in the build).
  `sbt/sbt-native-packager` v1.11.7 (2026-01-13) can produce the Dockerfile itself; a hand-written
  multi-stage Dockerfile is simpler to reason about and lint (`hadolint`), which is the pick.
- **Ollama.** Docker Hub `ollama/ollama`: `0.34.0-rc1` (2026-09-05) is **3.7 GB** (CUDA libs),
  the `-rocm` tag 1.4 GB; no CPU-only tag seen. Model sizes from the registry manifests:
  `llama3.2:1b` **1.32 GB**, `llama3.2` (3b) **2.02 GB**. For CI the existing `marola-e2e.yml`
  approach (the ~100 MB Linux binary + a cached `~/.ollama/models`) is far cheaper than the image;
  for local `docker compose` the official image is fine (pulled once).
- **Runner.** GitHub-hosted `ubuntu-latest` is documented as 4 vCPU / 16 GB RAM / 14 GB SSD
  (docs page is script-rendered; figures **as documented, not fetched here**). Enough for a
  native-image build (needs ~6-8 GB) and for `llama3.2:1b` on CPU (tens of seconds per call,
  as `marola-e2e.yml` already relies on).
- **Registry.** GHCR, free for public images; `docker/build-push-action` + `docker/metadata-action`
  with the repo's `GITHUB_TOKEN` (`packages: write`). No secret to add.
- **Google Maps URL formats** (for `maps_url`): `/maps/@LAT,LON,ZOOMz`, `?q=LAT,LON`,
  `!3dLAT!4dLON` in place URLs, `ll=LAT,LON`; short links `maps.app.goo.gl/...` redirect to one of
  those (`curl -sIL` follows). Parsed by a pure `Coordinates.fromMapsUrl` in `core` (unit-tested
  on those four shapes); the redirect follow stays in the workflow.

## 5. Design

### 5.1 One Dockerfile, four targets

```
Dockerfile
  builder   FROM sbtscala/scala-sbt:eclipse-temurin-25_…   sbt cli/assembly  → marola.jar
  jvm       FROM eclipse-temurin:25-jre  COPY marola.jar   ENTRYPOINT java -jar   (tag :jvm, ~60 MB + JRE)
  native-b  FROM ghcr.io/graalvm/native-image-community:25  sbt cli/nativeImage (reachability metadata under cli/src/main/resources/META-INF/native-image/)
  native    FROM gcr.io/distroless/base  COPY marola        ENTRYPOINT /marola   (tag :native)
  dev       FROM nixos/nix  COPY flake.* .  RUN nix develop --command true      (tag :dev — the literal `nix develop` for people without Nix)
```

`ENTRYPOINT` is `Main`; `docker run … --mcp` is not a thing: the MCP server is a second
`CMD` (`java -cp marola.jar marola.agent.SwimConditionsMcpServer`) documented in the compose file.
Env vars are the existing `MAROLA_*`; `.env` is mounted, never copied.

### 5.2 `docker-compose.yml`

Services: `marola` (image `:jvm`, `MAROLA_LOCAL_LLM_BASE_URL=http://ollama:11434/v1`),
`ollama` (official image, volume `ollama-models`), `ollama-pull` (one-shot: `ollama pull
${MAROLA_LOCAL_LLM_MODEL:-llama3.2}`), profiles `ollama` and `local`. Profile `local` swaps the
pull step for `ollama create marola-llama3.2 -f /finetune/Modelfile` (Tier 1) and, if
`finetune/out/adapter.gguf` exists, `Modelfile.adapter` (Tier 2), the same two paths
`finetune/README.md` documents, now reproducible from a clean machine.

### 5.3 `marola-local` as an artefact (LLMOps)

The model is versioned like the code: image tag `marola-local:<git-sha>` carries a model store
layer with `marola-llama3.2` created from `finetune/Modelfile` (Tier 1, ~2 GB layer) and, when a
`finetune-adapter-<version>.gguf` release asset exists, the Tier 2 adapter. Promotion gate: the
CI job runs `just benchmark` against the image's model and diffs the summary table against
`docs/benchmarks/`; a regression in the marola-vs-plain arms fails the job. What is *not*
promised: training Tier 2 in CI (no GPU; the adapter is built elsewhere and uploaded).

### 5.4 Workflows

- `docker.yml`: on PR → build `:jvm` only (cache-from GHCR), run `--brief` against the golden
  fixtures? No: images are runtime, the offline check is `sbt test` in `ci.yml`. The image job
  runs `docker run … --help`-level checks plus `hadolint`. On `main` and tags → build+push
  `:jvm`, `:native`, `:dev`, `marola-local`.
- `docker-smoke.yml` (`workflow_dispatch`, optionally `schedule` daily): inputs `lat`, `lon`,
  `maps_url`, `model`, `image`. Steps: resolve the location (`maps_url` → expand redirects → parse;
  else lat/lon; else the Campeche default), install the Ollama binary (cached like
  `marola-e2e.yml`), pull `model`, `docker run --network host <image> --summarize --lat … --lon …`,
  capture stdout, write `smoke/<run-id>.json` {when, lat, lon, model, image, top_pick, summary,
  reviewer, exit_code, stdout} and upload as an artifact.

### 5.5 Showing the smoke test on the map, and how the view refreshes

Two workflows must not both deploy Pages (the last one wins and would erase the other's files).
So the smoke workflow **does not deploy**: it commits its JSON to an orphan branch `site-data`
(`smoke/latest.json`, `smoke/history.json` capped at 50 runs, tiny files, a bot commit per run)
and then triggers `site.yml` (`workflow_dispatch` via `gh workflow run`, or `workflow_run` on
completion). `site.yml` gains one step: check out `site-data` and copy `smoke/` into
`site/dist/smoke/`. `app.js` fetches `smoke/latest.json` if present and renders the panel plus a
marker at the run's location; the history file feeds a small "last 10 runs" list. The next
scheduled site build (≤ 3 h) also picks it up, so the panel refreshes even if the trigger fails.

### 5.6 CLI additions

`--location-url <google-maps-url>` as a third way to give the origin (after `--lat/--lon`,
before env vars), via `Coordinates.fromMapsUrl`. Everything else is packaging.

## 6. Scoring / safety impact

None to scoring. The smoke panel shows model output on a public page, so it carries the same
labels the CLI prints: the summary is marked as LLM text, the reviewer's score/verdict is shown
next to it, and the deterministic list above it is the source of truth. No user data is involved.

## 7. Verification plan

- `CoordinatesSpec`: `fromMapsUrl` on the four URL shapes + garbage → `None`.
- `hadolint` on the Dockerfile in `just quality`; `docker build --target jvm` locally, then
  `docker run … --brief --lat -27.6733 --lon -48.47` (live) prints the ranked list.
- Native: `sbt cli/nativeImage` locally first (spike, task 1); the binary runs `--brief` live and
  `PipelineGoldenSpec`'s fixture path via a `--replay` flag is *not* planned; the golden suite
  stays on the JVM.
- `docker compose --profile ollama run marola --summarize` locally with `llama3.2:1b`.
- One manual `docker-smoke.yml` run; the panel appears on the map after the next site build.
- Benchmark gate for `marola-local`: `just benchmark` inside the image vs `docs/benchmarks/`.

## 8. Risks, limitations, and honest caveats

- **Native-image may not work with Kyo RC5** (fibers, `Unsafe`, reflection in Jackson/logback).
  If the spike fails, `:native` is dropped from v1 and the MIP says so; `:jvm` is the deliverable.
- **Image sizes**: `ollama/ollama` is 3.7 GB; compose users pull it once. CI never uses it.
- **Smoke test cost**: a `llama3.2:1b` summary + review on 4 vCPU is ~1-2 min, the pipeline ~1
  min, image pull ~30 s: ~5 min per run. Daily = trivial; per-PR would not be, so it stays manual /
  daily.
- **Public model text**: the map would show an LLM sentence to visitors. Keep the reviewer verdict
  visible and the panel clearly labelled; if the reviewer says `reject`, show the numbers only.
- **Tier 2 fine-tune** remains written-not-run until a GPU (or a paid job) trains the adapter;
  `marola-local` v1 is the Tier 1 Modelfile model, said plainly in the image's README.

## 9. Alternatives considered

- **Nix-built images (`dockerTools`)**: reproducible and small, but the friend without Nix
  cannot rebuild them, and the whole point is a Dockerfile they can read. Kept as `:dev` only.
- **A single fat `marola-ollama` image** (Ollama + JRE + jar in one): 4+ GB, rebuilt on every
  code change; a sidecar compose service is the normal shape. Rejected.
- **Smoke workflow deploying Pages itself**: races with `site.yml`; the `site-data` branch +
  trigger avoids it. Artifacts-only (no branch) expire after 90 days and need a token to read.
- **Do nothing**: `nix develop` works; but Phase 3 needs the image and the friend needs Docker.

## 11. Open questions

1. Native-image feasibility with Kyo 1.0.0-RC5: a two-hour spike before the task list.
2. `eclipse-temurin:25-jre-alpine` vs `jlink` + distroless for `:jvm` (size vs. simplicity).
3. Should the smoke test also run on a schedule (daily) or only on demand? (Proposal: daily.)
4. `site-data` branch vs. storing smoke results in the `site.yml` build itself by re-running the
   `--summarize` step there (couples the site build to Ollama, rejected above, but cheaper).
5. Where the Tier 2 adapter is trained and how it is uploaded (a `finetune-adapter` release?).
6. Whether the smoke panel belongs on the public map at all, or on a `/status.html` page.

## Appendix

Checked 2026-09-05: Docker Hub `ollama/ollama` tags `0.34.0-rc1` (3706 MB) / `-rocm` (1437 MB);
Ollama registry manifests `llama3.2:1b` 1321 MB, `llama3.2:3b` 2019 MB; GitHub releases
`graalvm/graalvm-ce-builds` `graal-25.3.4.1` with `graalvm-community-jdk-25i3-25.0.4.1_linux-x64`;
`scalameta/sbt-native-image` v0.5.0; `sbt/sbt-native-packager` v1.11.7. Not checked: GHCR
`native-image-community` tags, Temurin alpine JRE tag, the runner spec page (script-rendered).
