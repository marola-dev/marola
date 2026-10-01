# Development phases

1. **Phase 0: POC pipeline + six pluggable integrations (done, this change).** Beach discovery,
   live conditions, heuristic scoring, CLI entry point, and local backends for query synthesis,
   distance, agentic tool access, sighting storage, photo analysis, and observability. Zero
   cloud/Telegram setup required for any of it.
2. **Phase 1: Telegram bot.** Long-polling loop, native location sharing, `AppConfig`'s
   `telegramBotToken` actually wired up, `--report-sighting`/`--analyze-photo`'s CLI stand-ins
   replaced by real Telegram message/photo handlers. Still zero cloud spend. See
   `TELEGRAM-SETUP.md` for registering the bot and getting credentials ready ahead of this phase.
3. **Phase 2: Go live on a cloud backend, deliberately.** Opt into a cloud backend (GCP,
   MIP-0057) for whichever integrations you actually want (all optional, none required). First
   real cloud spend, entirely your choice which pieces.
4. **Phase 3: Deploy.** A hosted webhook. The first deploy artefact is
   already here and free: [marola-site](https://github.com/marola-dev/marola-site)'s `site.yml` builds MIP-0005's boards every 3 h and
   publishes the static map to GitHub Pages: no cloud account, no server, no per-visitor cost. The
   second is the image a hosted service will run: `Dockerfile` (`jvm` = Temurin 25 JRE + the
   fat jar, `native` = the GraalVM binary on distroless, `dev` = the Nix dev shell) and
   `docker-compose.yml` (marola + an Ollama sidecar): MIP-0008, `RUN-LOCALLY.md` §10.
5. **Phase 4: Harden & calibrate.** Caching, per-user rate limiting, feeding accumulated
   `SightingStore` reports back into the jellyfish/whale heuristics (`ARCHITECTURE.md` §8).

Do not skip Phase 1 to get to Phase 2 early; see `AGENTS.md`'s phase-discipline rule: a Telegram
bot that can't yet share a real location or photo has nothing meaningful to feed `ARCHITECTURE.md`
§5's integrations in production, even though every one of them is independently testable today via
`Main`'s CLI flags.
