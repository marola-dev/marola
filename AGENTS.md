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

`PHILOSOPHY.md` (repo root) holds the reasons behind the rules below — why Scala 3 on the JVM, Nix,
`just`, ai-jail, MIPs. Docs live under `docs/` — `docs/README.md` indexes them and marks which ideas
are MIP material; check these before assuming something is undecided or unbuilt:

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
| `docs/mips/` | Marola Improvement Proposals — design a non-trivial change here first, via the `mip` skill (`.claude/skills/mip/SKILL.md`), before building it. `just context-mips` packs what a browser session needs to draft one from voice notes |
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
`quality-scala`, scalafmt + scalafix, plus `quality-other`, ruff on every `.py` in the repo +
actionlint + hadolint on the Dockerfiles + the `scripts/*.py` self-tests — the same gates as
`ci.yml`; a missing lint tool fails the run rather than skipping, so use `nix develop`). The
`.githooks/pre-push` hook runs `quality-other` before every push and `quality-scala` too when a
pushed commit touches Scala, so the last push before a merge is linted — `git push --no-verify`
bypasses it, CI does not. Kyo is
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

## Attribution and cost accounting (hard rule)

- **Commits carry three trailers and nothing else:** `Tested:`, `Cost:` (both below) and
  `Co-Authored-By: Claude <noreply@anthropic.com>`.
  No session links, no "Generated with" banners, no PR-body attribution. This is enforced by
  `.claude/settings.json` (`attribution.commit`, `attribution.pr: ""`, `attribution.sessionUrl:
  false`) — the shared project settings, so it applies to every Claude Code session in this repo.
  Don't add attribution text by hand in commit messages or PR descriptions.
- **Every PR body ends with a `Cost` line** — what the work consumed, so the repo learns what a
  feature costs in quota, not just in lines:
  ```
  Cost: ~$4.10 · 1.9M tokens (llama-free) · 2 sessions · from `just claude-cost` 2026-09-05
  ```
  Get the figure from `/usage` (the Session block, current session) or `just claude-cost` (every
  session on this machine, via ccusage). Both price tokens at list rates; on a subscription that
  dollar figure is not a bill, it is the best available proxy for *how much of the plan's quota the
  feature used*, which is the point. Compare it with the PR's scope in one sentence if it's
  surprising ("mostly the benchmark reruns").
- **`just uprd` writes the PR description** from the branch's commits: a "What changed" bullet per
  commit and a Cost row built from the commits' `Cost:` trailers — so the trailer in each
  commit is the source of truth and the PR body never drifts from it. The body follows
  `.github/PULL_REQUEST_TEMPLATE.md`'s shape — bold labels and a compact table, no `#` headings —
  and the PR title is the first commit's subject capped at 70 characters. Run it after every push
  to a PR branch (`just uprd --dry-run` to preview).
- **Every commit carries a `Tested:` trailer** — one line, written once at commit time, so the
  PR's Tested row is filled without a second pass: tokens `gates` (= `just build && just
  test && just quality`), `e2e`, `live` (a `just run -- --brief`), `ci-only`, then free text
  for what was *not* run and why, e.g. `Tested: gates — no e2e, no data path touched`. `just
  uprd` turns the tokens into ✅/⬜ glyphs and quotes the text; it never guesses from prose.
- **One feature, one session.** Start a feature with `/clear` (or a new session) and `/rename` it
  to the branch name so `/usage`'s session block and ccusage's per-session rows map to one PR.
  Re-runs of `just benchmark`/`just e2e` driven by the agent count toward the feature; note them.
  When one session does produce several PRs (a MIP stack), `just cost-split MIP-NNNN` attributes
  the session's logged usage to each commit by time and prints the per-branch `Cost:` trailer —
  measured, not estimated; `just uprds MIP-NNNN` then refreshes every PR of the stack with its
  Cost section and a shared stack overview.
- **Heavier option, when it matters:** Claude Code exports `claude_code.cost.usage` and
  `claude_code.token.usage` over OpenTelemetry (`CLAUDE_CODE_ENABLE_TELEMETRY=1`,
  `OTEL_METRICS_EXPORTER=otlp`, `OTEL_EXPORTER_OTLP_ENDPOINT=...`) with `session.id`, `model`,
  `skill.name`, `mcp_tool.name` attributes — the same OTLP any collector accepts, so a future Phase
  2 could land agent spend next to marola's own `Telemetry` traces in Application Insights.

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
  [ai-jail](https://github.com/akitaonrails/ai-jail) — `just jail-claude`, or `just jcf` /
  `just jcs` for the same jail pinned to the fable / sonnet model — rather than bare. It sandboxes the agent process (bubblewrap/Landlock/
  seccomp on Linux); it does not replace the rules above: still no
  unattended `azd up`/`az deployment group create`, and it does not stop
  an agent from writing bad code or spending API budget, only from
  touching things outside the sandbox. Project policy is `.ai-jail`
  (committed, tightens only); `.env`/`*.pem`/`*.key` are masked from the
  sandbox regardless of what's on disk. Run `just jail-dry-run <cmd>`
  first if you're unsure what a jailed command would actually be allowed
  to do. The jail has no gh login of its own: `just jail-claude` passes `GH_TOKEN` from the host
  shell (a fine-grained token scoped to this repo — pull requests read/write, contents and
  metadata read; put it in the gitignored `.env`) so the agent opens and updates PRs itself with
  `just uprd` and the template never lands empty. What the agent may do on GitHub is bounded by
  that token's permissions, not by the sandbox (see the `jail-claude` recipe's comment).
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
