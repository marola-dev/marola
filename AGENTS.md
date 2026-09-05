# AGENTS.md

Instructions for any AI coding agent working in this repository (Claude Code or otherwise). Read
this before writing, modifying, or deploying anything. Humans should read it too.

## What this repo is

**marola** — a Telegram assistant answering "what's the best hour tomorrow to swim nearby?" — real
nearby beach discovery (OpenStreetMap), live sea/weather conditions (Open-Meteo), a jellyfish/whale
heuristic, an LLM-generated summary reviewed by a second LLM pass, all runnable **entirely locally
with a free Ollama model, zero Azure account needed**, with Azure Maps/Foundry/Cosmos DB/Vision/
Application Insights available as opt-in upgrades per integration, never a package deal. Also built
as hands-on coverage of every [AI-103](https://learn.microsoft.com/en-us/credentials/certifications/azure-ai-apps-and-agents-developer-associate/)
exam domain, and a design target for [AI-500](https://learn.microsoft.com/en-us/credentials/certifications/)
(multi-agent solutions, for which AI-103 is the mandatory prerequisite) — see `docs/AI-103-MAPPING.md` and
`docs/AI-500-MAPPING.md`.

One sbt multi-project build (root `build.sbt`), split into four modules at the repo root so the
local-only path carries zero Azure SDK dependency:

- `core/` — pure pipeline logic, shared HTTP/JSON helpers, the traits (`LlmClient`, `VisionClient`,
  `SightingStore`) that `local/` and `azure/` implement. No Azure reference anywhere in this module.
- `local/` — Ollama-backed implementations. Zero Azure SDK dependency, confirmed in `build.sbt`.
- `azure/` — every optional Azure integration (Foundry, Azure Maps, Cosmos DB, Azure AI Vision,
  Application Insights), all opt-in.
- `cli/` — `Main`, `AppConfig` (picks a backend per integration from env vars), the MCP tool server.
  Depends on all three of the above; use `sbt cli/run`/`cli/runMain ...` for anything that needs the
  assembled app, not `sbt run` at the root (the root project is a pure aggregate with no source of
  its own).
- `dspy/` — offline Python DSPy prompt-compile step; produces a JSON artifact the Scala side loads,
  never a runtime dependency.

Docs live under `docs/` — check these before assuming something is undecided or unbuilt:

| Doc | Covers |
|---|---|
| `docs/ARCHITECTURE.md` | The pipeline, the six pluggable integrations, what's verified live vs. written-not-run |
| `docs/FUTURE-WORK.md` | Design sketches, reviewed-but-not-adopted libraries, harness ideas, exam-coverage ideas |
| `docs/EFFECTS-MAP.md` | A Scala/FP-purity review — what's pure, what's effectful, what's hidden |
| `docs/RUN-LOCALLY.md` | Run it now, with Ollama, no Telegram/Azure |
| `docs/TELEGRAM-SETUP.md` | Registering the bot, local-dev and Azure-Foundry credential paths |
| `docs/AI-103-MAPPING.md` | AI-103 exam domain coverage, including honest gaps |
| `docs/AI-500-MAPPING.md` | AI-500 (multi-agent) domain coverage — a design target, not a build record |
| `docs/SKILLS.md` | A skills roadmap — what to practice, in order, using marola as the vehicle |
| `docs/SCALA3-JDK-REVIEW.md` | Scala 3 / JDK 21-25 features reviewed against this code — adopt list and order |
| `docs/AGENT-FRAMEWORKS-SURVEY.md` | Multi-agent frameworks survey — Python ideas → Scala shapes, Pekko fit, reading list |
| `docs/benchmarks/` | Kept `just benchmark` runs — re-run and compare before changing prompt/corpus/embedder/model |
| `docs/mips/` | Marola Improvement Proposals — design a non-trivial change here first, via the `mip` skill (`.claude/skills/mip/SKILL.md`), before building it. `just context-mips` packs what a browser session needs to draft one from voice notes |
| `docs/FABLE_REVIEW.md` | Code and documentation review at the initial import — open findings, ranked, with file:line references |

## Setup & commands

```bash
nix develop          # reproducible dev shell (JDK 25, sbt, scala-cli, coursier,
                      # just, python3, az, gh — see flake.nix)
just                  # list all available recipes
just build            # sbt compile
just test             # sbt test
just fmt              # scalafmtAll
just run              # marola CLI (just run -- --summarize forwards flags)
just mcp-server       # marola's MCP tool server
just e2e              # marola's live E2E test (Overpass/Open-Meteo/Ollama) — excluded from `just test`
```

Always run `just build && just test && just quality` before considering a change done (`quality` =
scalafmt + scalafix + ruff + actionlint, the same gates as `ci.yml`). Kyo is
pre-1.0 (currently `1.0.0-RC5`) with no version-specific published docs — when unsure of an API,
verify against the actual jar (`javap` on the decompiled class) rather than guessing from
`getkyo.io`'s latest-version docs, which can silently drift from what's pinned. See
`docs/FUTURE-WORK.md` §2-3 and `docs/EFFECTS-MAP.md` for examples of this verification approach.

**JDK 25 is required, not just "17+".** Scala 3.9 itself only needs JDK
17+, but Kyo 1.0.0-RC5 compiles with `-release 25` and its artifacts
won't load on an older JVM — this already broke a real build with
`UnsupportedClassVersionError` on a JDK-24 runtime. `flake.nix` pins JDK
25. If you're compiling from an IDE (IntelliJ, etc.) rather than a terminal, check the IDE's own
Project SDK setting separately — it does not automatically follow the Nix devShell's JDK, and a
mismatch there produces the same class-file-version error even when `nix develop` itself is
correctly configured.

## Phase discipline (hard rule)

Work **one phase at a time**, per `docs/ARCHITECTURE.md` §11: do not start Phase 2 (going live on
Azure — provisioning any of the six optional local/Azure integrations for real, see
`docs/ARCHITECTURE.md` §5/§6) before Phase 1 (Telegram bot actually working) is done. This ordering
exists specifically to prevent an expensive mistake — do not skip it because a later phase looks
more interesting or is what the human happened to ask about first. If asked to jump ahead, implement
the requested feature but flag which earlier-phase prerequisite is still missing.

## Cost & deployment safety (hard rule)

- **Never provision or deploy a paid Azure resource** (anything beyond a
  free tier: Azure OpenAI/Foundry model deployments, Container Apps usage
  beyond the free grant, etc.) **without explicit human confirmation
  first.** Propose the change, state the expected cost, and wait for a
  go-ahead. This applies to `azd up`, `azd provision`, and `az deployment
  group create` alike.
- Never hardcode an API key, connection string, or secret. Every Azure
  client *should* authenticate via `azure-identity`'s `DefaultAzureCredential` against a
  managed identity — today only `AzureFoundryLlmClient` does; Cosmos DB, AI Vision and Azure Maps
  still take a key from the environment (FABLE_REVIEW D1 — migrate before Phase 2). If a new
  credential is genuinely required, add it to `.env.example` as a
  placeholder — never commit a real value.

## Code style

- Scala 3.9, direct style preferred over deeply nested combinator chains.
  Kyo's own docs recommend enabling these compiler flags (already set in
  `build.sbt` — don't remove them): `-Wvalue-discard`,
  `-Wnonunit-statement`, the matching `-Wconf` promotion to error, and
  `-language:strictEquality`.
- Keep pure business logic (e.g. `Swimability`'s scoring) as plain
  functional Scala with no effect type. Reserve Kyo (`Sync`, `Abort`,
  `Env`) for the I/O boundary — HTTP calls to Foundry/Overpass/Open-Meteo,
  file/database reads, the Telegram polling loop. This keeps the decision
  logic trivially testable without a Kyo runtime.
- Prefer `enum` + exhaustive pattern matching over exceptions for
  expected failure modes. Reserve thrown exceptions for genuinely
  unexpected faults.
- **Testing discipline** (adapted from [Kyo's own `AGENTS.md`](https://github.com/getkyo/kyo) —
  worth matching since this repo depends on Kyo directly): reproduce a bug with a failing test
  before fixing it, and keep that test afterward as a regression guard; when a test and the code
  disagree, diagnose which one is actually wrong before changing either — don't reflexively "fix"
  the test to match broken code; assert concrete expected values on behavior, not on
  implementation details, and cover edge cases deterministically rather than relying on one happy
  path. (Unlike Kyo's guide, this repo does track deferred work explicitly, in
  `docs/FUTURE-WORK.md` — that's a real, load-bearing doc here, not a banned excuse.)
- Prefer running agent tools (Claude Code, etc.) through
  [ai-jail](https://github.com/akitaonrails/ai-jail) — `just jail-claude`
  — rather than bare. It sandboxes the agent process (bubblewrap/Landlock/
  seccomp on Linux); it does not replace the rules above: still no
  unattended `azd up`/`az deployment group create`, and it does not stop
  an agent from writing bad code or spending API budget, only from
  touching things outside the sandbox. Project policy is `.ai-jail`
  (committed, tightens only); `.env`/`*.pem`/`*.key` are masked from the
  sandbox regardless of what's on disk. Run `just jail-dry-run <cmd>`
  first if you're unsure what a jailed command would actually be allowed
  to do.
  **Ubuntu 24.04 caveat:** `just jail-claude` can fail with `bwrap: setting up uid map: Permission
  denied` — Ubuntu 23.10+ blocks unprivileged user namespaces by default
  (`kernel.apparmor_restrict_unprivileged_userns=1`), which bubblewrap needs. Confirmed as host
  policy, not an ai-jail bug (`unshare --user --map-root-user echo ok` fails identically). Fix via
  a scoped AppArmor profile for `bwrap` (preferred) or disabling the sysctl (simpler, weakens
  unprivileged-userns protection globally).

## Before implementing a feature

Check `docs/AI-103-MAPPING.md` and `docs/AI-500-MAPPING.md` — is there an existing gap this closes?
Building it is fine either way (this is a real product, not just exam prep), but note the mapping in
the PR description if it applies. For anything touching a proactive/autonomous agent behavior (e.g.
the escalation-agent idea in `docs/FUTURE-WORK.md`), see `docs/AI-500-MAPPING.md` §4 before removing
any human-confirmation gate — this is not optional polish.

## When something here turns out to be wrong

Update this file or the relevant `docs/*.md` in the same change — an
Azure API/limit/version changes, a library moves past the version pinned
in `build.sbt`, etc. Keep these as the current source of truth, not a
historical snapshot of what was true when they were written.
