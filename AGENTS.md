# AGENTS.md

Instructions for any AI coding agent working in this repository (Claude Code or otherwise). Read
this before writing, modifying, or deploying anything. Humans should read it too.

## What this repo is

**marola** — a Telegram assistant answering "what's the best hour tomorrow to swim nearby?" — real
nearby beach discovery (OpenStreetMap), live sea/weather conditions (Open-Meteo), a jellyfish/whale
heuristic, an LLM-generated summary reviewed by a second LLM pass, all runnable **entirely locally
with a free Ollama model, zero Azure account needed**, with Azure Maps/Foundry/Cosmos DB/Vision/
Application Insights as opt-in upgrades per integration, never a package deal. Also hands-on
coverage of every [AI-103](https://learn.microsoft.com/en-us/credentials/certifications/azure-ai-apps-and-agents-developer-associate/)
exam domain and a design target for [AI-500](https://learn.microsoft.com/en-us/credentials/certifications/)
(multi-agent, AI-103 is its prerequisite) — see `docs/AI-103-MAPPING.md`/`docs/AI-500-MAPPING.md`.

One sbt multi-project build (root `build.sbt`), split into four modules at the repo root so the
local-only path carries zero Azure SDK dependency:

- `core/` — pure pipeline logic, shared HTTP/JSON helpers, the traits (`LlmClient`, `VisionClient`,
  `SightingStore`) `local/`/`azure/` implement. No Azure reference anywhere in this module.
- `local/` — Ollama-backed implementations. Zero Azure SDK dependency, confirmed in `build.sbt`.
- `azure/` — every optional Azure integration (Foundry, Azure Maps, Cosmos DB, AI Vision, App
  Insights), all opt-in. `.claude/rules/azure.md` auto-loads the full cost/credential rules here.
- `cli/` — `Main`, `AppConfig` (picks a backend per integration from env vars), the MCP tool
  server. Depends on all three above; use `sbt cli/run`/`cli/runMain ...`, not `sbt run` at the
  root (a pure aggregate with no source of its own).
- `dspy/` — offline Python DSPy prompt-compile step; produces a JSON artifact the Scala side
  loads, never a runtime dependency.

`PHILOSOPHY.md` (repo root) holds the reasons behind the rules below — why Scala 3 on the JVM, Nix,
`just`, ai-jail, MIPs. Docs live under `docs/` — `docs/README.md` indexes them and marks which ideas
are MIP material; check these before assuming something is undecided or unbuilt (MIP status
vocabulary and template pointer: `.claude/rules/docs.md`):

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
| `docs/DEV-FLOW.md` | The loop end to end: idea → MIP → acceptance → tasks → stacked PRs (verified, costed) → review on request → merge/restack → Implemented; command reference |
| `docs/AGENT-SKILLS.md` | Which agent skills to use in this repo: `mip` (plan), `mip-tasks` (tasks → stacked PRs, `scripts/stack.sh`), superpowers walkthrough, candidates to write |
| `docs/benchmarks/` | Kept `just benchmark` runs — re-run and compare before changing prompt/corpus/embedder/model |
| `docs/mips/` | Marola Improvement Proposals — design a non-trivial change here first, via the `mip` skill (`.claude/skills/mip/SKILL.md`), before building it. `just context-mips` packs what a browser session needs to draft one from voice notes; `just context-mip MIP-NNNN` packs one already-written MIP for an independent, non-Claude reviewer |
| `docs/FABLE_REVIEW.md` | Code and documentation review at the initial import — open findings, ranked, with file:line references |

## Setup & commands

```bash
nix develop          # reproducible dev shell (JDK 25, sbt, scala-cli, coursier,
                      # just, python3, ruff, az, gh, hadolint, actionlint — see flake.nix; Docker itself is the host's)
just                  # list all available recipes
just build            # sbt compile
just test             # sbt test
just fmt              # scalafmtAll
just run              # marola CLI (just run -- --summarize forwards flags)
just mcp-server       # marola's MCP tool server
just e2e              # marola's live E2E test (Overpass/Open-Meteo/Ollama) — excluded from `just test`
just coverage         # sbt-scoverage: statement coverage across core/local/azure/cli (README badge, main only)
```

Always run `just build && just test && just quality` before considering a change done (`quality` =
`quality-scala`, scalafmt + scalafix, plus `quality-other`, ruff + actionlint + hadolint +
`scripts/*.py` self-tests — the same gates as `ci.yml`; a missing lint tool fails rather than
skips, so use `nix develop`). `.githooks/pre-push` runs `quality-other` before every push and
`quality-scala` too when Scala changed — `git push --no-verify` bypasses it, CI does not.
**JDK 25 is required, not just "17+"** — Kyo's artifacts won't load on an older JVM. See
`.claude/rules/scala.md` (loaded automatically while editing `.scala`/`build.sbt`) for the full
JDK/Kyo-versioning detail and the jar-verification approach for Kyo's pre-1.0 API surface.

## Phase discipline (hard rule)

Work **one phase at a time**, per `docs/ARCHITECTURE.md` §11: do not start Phase 2 (going live on
Azure — provisioning any of the six optional integrations for real, `docs/ARCHITECTURE.md` §5/§6)
before Phase 1 (Telegram bot actually working) is done — this exists to prevent an expensive
mistake, so don't skip it because a later phase looks more interesting. If asked to jump ahead,
implement the requested feature but flag which earlier-phase prerequisite is still missing.

## Cost & deployment safety (hard rule)

**Never provision or deploy a paid Azure resource without explicit human confirmation first** —
propose the change, state the expected cost, wait for a go-ahead. Enforced by a hook, not only
prose: `.claude/hooks/guard-azure.sh` (`PreToolUse` on `Bash`) blocks `azd up|provision|deploy`/
`az deployment …` with exit 2 unless `MAROLA_ALLOW_AZURE_DEPLOY=1` is set after a human go-ahead;
ai-jail is the second layer. Never hardcode a key/connection string/secret. Full detail (managed
identity, the `.env.example` placeholder rule) is in `.claude/rules/azure.md` — this rule matters
everywhere though, not only its auto-load paths, so the short version stays here too.

## Attribution and cost accounting (hard rule)

- **Commits carry three trailers and nothing else:** `Tested:`, `Cost:` (both below) and
  `Co-Authored-By: Claude <noreply@anthropic.com>`. No session links, no "Generated with" banners,
  no PR-body attribution — enforced by `.claude/settings.json` (`attribution.commit`,
  `attribution.pr: ""`, `attribution.sessionUrl: false`), the shared project settings. Don't add
  attribution text by hand.
- **The whole PR workflow is one command.** Write the commit — a body plus `Tested:`/`Cost:`
  trailers — then `just pr`: fills any missing trailer (`just cost-fill`), pushes, writes the PR
  from `.github/PULL_REQUEST_TEMPLATE.md` (`just uprd`, or `scripts/stack.sh pr` on a
  `mip-NNNN/k-*` branch); `Cost:` prefers `just cost-split`'s measured figure over
  `scripts/cost-split.py --estimate`'s diff-size fallback, labelled `est.`.
- **One feature, one session.** Start a feature with `/clear` and `/rename` it to the branch name
  so `/usage`'s session block and ccusage's per-session rows map to one PR. Re-runs of
  `just benchmark`/`just e2e` driven by the agent count toward the feature; note them. When one
  session produces several PRs (a MIP stack), `just cost-split MIP-NNNN` attributes usage to each
  commit by time and prints the per-branch `Cost:` trailer — measured, not estimated;
  `just uprds MIP-NNNN` refreshes every PR of the stack with its Cost section.
- **Heavier option, when it matters:** Claude Code exports `claude_code.cost.usage`/
  `claude_code.token.usage` over OpenTelemetry (`CLAUDE_CODE_ENABLE_TELEMETRY=1`,
  `OTEL_METRICS_EXPORTER=otlp`, `OTEL_EXPORTER_OTLP_ENDPOINT=...`) tagged by `session.id`/`model`/
  `skill.name`/`mcp_tool.name` — a future Phase 2 could land agent spend next to marola's own
  `Telemetry` traces in Application Insights.

## Code style

Scala style, the Kyo effect boundary, and testing discipline are in `.claude/rules/scala.md`,
auto-loaded while editing `.scala`/`build.sbt`. Short version for every language: prefer `enum` +
exhaustive matching over exceptions for expected failure modes, and reproduce a bug with a failing
test before fixing it.
Prefer running agent tools through [ai-jail](https://github.com/akitaonrails/ai-jail) —
`just jail-claude` (or `jcf`/`jcs`, pinned to fable/sonnet) — over bare. It sandboxes the process
(bubblewrap/Landlock/seccomp); it doesn't replace the rules above or stop bad code/overspend, only
out-of-sandbox access. `.env`/`*.pem`/`*.key` are masked regardless of disk content;
`just jail-dry-run <cmd>` previews a jailed command. No `gh` login of its own — `just jail-claude`
passes `GH_TOKEN` from the host shell (fine-grained, gitignored `.env`) so PRs work via
`just uprd`. `MAROLA_JAIL_CLIPBOARD=1` opts into a write-only clipboard bridge (`just clip`), off
by default. **Ubuntu 24.04:** `bwrap: setting up uid map: Permission denied` means unprivileged
user namespaces are blocked by default — fix via a scoped AppArmor profile, or disable the sysctl
(weakens the protection globally).

## Before implementing a feature

Check `docs/AI-103-MAPPING.md` and `docs/AI-500-MAPPING.md` — is there an existing gap this closes?
Building it is fine either way (a real product, not just exam prep), but note the mapping in the
PR description if it applies. For a proactive/autonomous agent behavior (e.g. the escalation-agent
idea in `docs/FUTURE-WORK.md`), see `docs/AI-500-MAPPING.md` §4 before removing a
human-confirmation gate — not optional polish.

## When something here turns out to be wrong

Update this file, `.claude/rules/*.md`, or the relevant `docs/*.md` in the same change — an Azure
API/limit/version changes, a library moves past the version pinned in `build.sbt`, etc. Rules
files are excerpts that link back here, not the only copy — this file is read by non-Claude agents
too, so a rule that matters everywhere stays stated here even if the full detail moved.
