# Development phases

1. **Phase 0: POC pipeline + six pluggable integrations.** Beach discovery, live conditions,
   heuristic scoring, CLI entry point, and local backends for query synthesis, distance, agentic
   tool access, sighting storage, photo analysis, and observability. Zero cloud/Telegram setup
   required for any of it.

   *Status: done; it has no MIP of its own.*

2. **Phase 1: Telegram bot.** Long-polling loop, native location sharing,
   [`AppConfig`](https://github.com/marola-dev/marola-app/blob/main/cli/src/main/scala/marola/AppConfig.scala)'s
   `telegramBotToken` actually wired up, `--report-sighting`/`--analyze-photo`'s CLI stand-ins
   replaced by real Telegram message/photo handlers. Still zero cloud spend. See
   [`TELEGRAM-SETUP.md`](1-Using-marola/TELEGRAM-SETUP.md) for registering the bot and getting
   credentials ready ahead of this phase.

   *Status: not built; designed in [MIP-0002](MIPs/MIP-0002-telegram-bot-phase-1.md) (Draft), with
   [MIP-0003](MIPs/MIP-0003-fast-replies-caching-and-fan-out.md) (Draft) for the local cache and
   fan-out it needs.*

3. **Phase 2: Go live on a cloud backend, deliberately.** Opt into a cloud backend (GCP,
   MIP-0057) for whichever integrations you actually want (all optional, none required). First
   real cloud spend, entirely your choice which pieces.

   *Status: not started, and waits on Phase 1;
   [MIP-0057](MIPs/MIP-0057-gcp-as-opt-in-cloud-backend.md) (Draft) reviews GCP and commits to no
   build.*

4. **Phase 3: Deploy.** A hosted webhook. The first deploy artefact is already here and free:
   [marola-site](https://github.com/marola-dev/marola-site)'s `site.yml` builds MIP-0005's boards
   every 3 h and publishes the static map to a Cloudflare Worker's free static assets (GitHub Pages
   as the fallback): no server, no per-visitor cost. The second is the image a hosted service will run,
   [marola-app](https://github.com/marola-dev/marola-app)'s
   [`Dockerfile`](https://github.com/marola-dev/marola-app/blob/main/Dockerfile) (`jvm` = Temurin
   25 JRE + the fat jar, `native` = the GraalVM binary on distroless, `dev` = the Nix dev shell)
   and [`docker-compose.yml`](https://github.com/marola-dev/marola-app/blob/main/docker-compose.yml)
   (marola + an Ollama sidecar): MIP-0008, [`DOCKER.md`](1-Using-marola/DOCKER.md).

   *Status: both artefacts ship ([MIP-0005](MIPs/MIP-0005-map-and-static-site.md) and
   [MIP-0008](MIPs/MIP-0008-docker-images-and-smoke-test.md), Implemented); the hosted webhook is
   not built, and the bot's deploy is left to a MIP after Phase 1
   ([MIP-0065](MIPs/MIP-0065-ci-cd-on-github-hosted-runners.md) §5.7).*

5. **Phase 4: Harden & calibrate.** Caching, per-user rate limiting, feeding accumulated
   [`SightingStore`](https://github.com/marola-dev/marola-app/blob/main/core/src/main/scala/marola/sightings/SightingStore.scala)
   reports back into the jellyfish/whale heuristics
   ([`LIMITATIONS.md`](1-Using-marola/LIMITATIONS.md#the-jellyfish-and-whale-heuristics-honest-limitations)).

   *Status: not started; the shared cache and rate limiting are
   [MIP-0003](MIPs/MIP-0003-fast-replies-caching-and-fan-out.md) (Draft), calibrating the
   heuristics on reports [MIP-0007](MIPs/MIP-0007-time-series-foundation-models.md) (Draft).*

Do not skip Phase 1 to get to Phase 2 early; see `AGENTS.md`'s phase-discipline rule: a Telegram
bot that can't yet share a real location or photo has nothing meaningful to feed
the [integrations](2-Building-marola/ARCHITECTURE.md#local-first-integration-pattern) in production, even
though every one of them is independently testable today via
[`Main`](https://github.com/marola-dev/marola-app/blob/main/cli/src/main/scala/marola/Main.scala)'s
CLI flags.
