# AGENTS.md

Instructions for any AI coding agent working in this repository (Claude Code or otherwise). Read
this before writing, modifying, or deploying anything. Humans should read it too.

<!-- invariants:start -->
## Org invariants

Non-negotiable in every marola repo; a repo may make these stricter, never looser (MIP-0070 §5.1).

- **Cost and deployment safety**: never provision or deploy a paid cloud resource without explicit human confirmation first ([AGENTS.md](AGENTS.md#cost--deployment-safety-hard-rule)).
- **No secrets in code**: never hardcode a key/connection string/secret; `.env.example` holds placeholders only ([AGENTS.md](AGENTS.md#cost--deployment-safety-hard-rule)).
- **The agent-ready gate**: an agent may only begin implementation on an issue carrying `agent-ready` ([AGENTS.md](AGENTS.md#issue-tracking-hard-rule)).
- **The three commit trailers**: commits carry three trailers and nothing else — `Tested:`, `Cost:`, and `Co-Authored-By: Claude <noreply@anthropic.com>` ([AGENTS.md](AGENTS.md#attribution-and-cost-accounting-hard-rule)).
- **Phase discipline**: work one phase at a time; never start a later phase before the current one is done ([AGENTS.md](AGENTS.md#phase-discipline-hard-rule)).
<!-- invariants:end -->

## What this repo is

**marola** is the ocean intelligence layer for a stretch of coast, reachable as a Telegram
assistant: real nearby beach discovery (OpenStreetMap), live sea/weather conditions (Open-Meteo), a
jellyfish/whale heuristic, an LLM-generated summary reviewed by a second LLM pass. Its first case
is "what's the best hour tomorrow to swim nearby?", all runnable **entirely locally
with a free Ollama model, no cloud account needed**. GCP (MIP-0057) is the opt-in cloud path.

One sbt multi-project build (root `build.sbt`), split into three modules at the repo root:

- `core/`: pure pipeline logic, shared HTTP/JSON helpers, the traits (`LlmClient`, `VisionClient`,
  `SightingStore`) `local/` implements.
- `local/`: Ollama-backed implementations (LLM, vision) and the local-file sighting store.
- `cli/`: `Main`, `AppConfig` (reads settings from env vars), the MCP tool server. Depends on
  both above; use `sbt cli/run`/`cli/runMain ...`, not `sbt run` at the root (a pure aggregate
  with no source of its own).
- `dspy/`: offline Python DSPy prompt-compile step; produces a JSON artifact the Scala side
  loads, never a runtime dependency.

`PHILOSOPHY.md` (repo root) holds the reasons behind the rules below: why Scala 3 on the JVM, Nix,
`just`, ai-jail, MIPs. Docs live under `docs/`, grouped into the four audience directories the
paths below show, and are published as a rendered, searchable site at <https://marola.dev/docs/>
(MIP-0064). `docs/index.md` is both that site's landing page and the index of what is MIP
material; check these before assuming something is undecided or unbuilt (MIP status vocabulary and
template pointer: `.claude/rules/docs.md`):

| Doc | Covers |
|---|---|
| `docs/2-Building-marola/ARCHITECTURE.md` | The pipeline, its integrations, what's verified live vs. written-not-run |
| `docs/4-Research-and-plans/FUTURE-WORK.md` | Design sketches, reviewed-but-not-adopted libraries, harness ideas |
| `docs/2-Building-marola/EFFECTS-MAP.md` | A Scala/FP-purity review: what's pure, what's effectful, what's hidden |
| `docs/1-Using-marola/RUN-LOCALLY.md` | Run it now, with Ollama, no Telegram or cloud account |
| `docs/1-Using-marola/TELEGRAM-SETUP.md` | Registering the bot and its local-dev credential path |
| `docs/4-Research-and-plans/SKILLS.md` | A skills roadmap: what to practice, in order, using marola as the vehicle |
| `docs/2-Building-marola/SCALA3-JDK-REVIEW.md` | Scala 3 / JDK 21-25 features reviewed against this code: adopt list and order |
| `docs/4-Research-and-plans/AGENT-FRAMEWORKS-SURVEY.md` | Multi-agent frameworks survey: Python ideas → Scala shapes, Pekko fit, reading list |
| `docs/4-Research-and-plans/AGENT-STACK-SURVEY.md` | agent4s / llm4s / ADK mapped to the MIPs, how they compose, the project Q&A agent |
| `docs/3-Working-on-the-repo/DEV-FLOW.md` | The loop end to end: idea → MIP → acceptance → tasks → stacked PRs (verified, costed) → review on request → merge/restack → Implemented; command reference |
| `docs/3-Working-on-the-repo/CI-CD.md` | Every workflow: trigger, runner, what it gates or deploys, secrets, how to run it by hand; the self-hosted rule and the maintainer's manual settings (MIP-0065) |
| `docs/3-Working-on-the-repo/ISSUE-FLOW.md` | The GitHub tracking standard in use: the object model, the three intake tiers, the Definition of Ready, and every `issues`/`just` command (MIP-0063) |
| `docs/3-Working-on-the-repo/AGENT-SKILLS.md` | Which agent skills to use in this repo: `/marola-devkit:mip` (plan), `/marola-devkit:mip-tasks` (tasks → stacked PRs, `stack`), superpowers walkthrough, candidates to write |
| `docs/benchmarks/` | Kept `just benchmark` runs: re-run and compare before changing prompt/corpus/embedder/model |
| `docs/MIPs/` | Marola Improvement Proposals: design a non-trivial change here first, via the `/marola-devkit:mip` skill, before building it. `just context-mips` packs what a browser session needs to draft one from voice notes; `just context-mip MIP-NNNN` packs one already-written MIP for an independent, non-Claude reviewer |
| `docs/4-Research-and-plans/FABLE_REVIEW.md` | Code and documentation review at the initial import: open findings, ranked, with file:line references |

**Writing a doc is a deploy.** A new or moved file under `docs/` needs no `nav:` entry: mkdocs
builds the sidebar from the file tree, which is why the directories carry `1-`…`4-` prefixes — they
are the only thing ordering the sections. The build is `--strict`, so a link that does not resolve
inside `docs/` fails it; that is why `AGENTS.md`, `PHILOSOPHY.md` and `docs/benchmarks/` are
referenced at GitHub rather than relatively. Preview with `just docs-serve` before pushing. On
`main`, a push touching `docs/**` or `mkdocs/**` runs `api-docs.yml`, which renders the site, folds
the scaladoc/pdoc trees in under `api/`, and pushes the result to the `site-data` branch for
`site.yml` to deploy.

## Setup & commands

```bash
nix develop          # reproducible dev shell (JDK 25, sbt, scala-cli, coursier,
                      # just, python3, ruff, gh, hadolint, actionlint — see flake.nix; Docker itself is the host's)
just                  # list all available recipes
just build            # sbt compile
just test             # sbt test
just fmt              # scalafmtAll
just run              # marola CLI (just run -- --summarize forwards flags)
just mcp-server       # marola's MCP tool server
just e2e              # marola's live E2E test (Overpass/Open-Meteo/Ollama) — excluded from `just test`
just coverage         # sbt-scoverage: statement coverage across core/local/cli (README badge, main only)
just docs             # build docs/ into mkdocs/generated-docs (needs a Docker or Podman daemon)
just docs-serve       # preview the docs on http://localhost:8001/docs/
```

Always run `just build && just test && just quality` before considering a change done (`quality` =
`quality-scala`, scalafmt + scalafix, plus `quality-other`, ruff + actionlint + hadolint +
`scripts/*.py` self-tests: the gates `ci.yml` runs, at the lint versions `flake.lock` pins, which
ci.yml passes to the devkit workflows by hand; CI runs a subset of the self-tests. A missing lint
tool fails rather than skips, so use `nix develop`). The pre-push hook runs `just prepush`: `quality-other` before every
push, `quality-scala` too when Scala changed. `git push --no-verify` bypasses it, CI does not.
The dev-flow harness is [marola-devkit](https://github.com/marola-dev/marola-devkit), a flake
input pinned to a tag: its tools on `PATH` (`stack`, `uprd`, `pr-flow`, `issues`, `cost-split`,
`cost-fill`, `agents-check`, …), their recipes (`import? '.devkit/devkit.just'`), the git hooks
(`.devkit/.githooks`, running this repo's `just precommit`/`just prepush`), the reusable CI
workflows, and the `marola-devkit` Claude Code plugin (`/marola-devkit:mip` and the other generic
skills, the MIP agents, the format/stop/session hooks). Bump the flake input and every
`@v…`/`devkit-ref` in `.github/workflows/` together (dependabot is told to leave it alone), plus
the marketplace `ref` in `.claude/settings.json`. The status line is the devkit's
(`.devkit/.claude/statusline.sh`). The shellHook links this worktree's `.devkit` and, once the
main checkout has one, points `core.hooksPath` at it (absolute; `just install-hooks` does the same
by hand). A worktree made outside `nix develop` and `just worktree` needs `just devkit-link` once,
or the devkit's recipes are missing there. Gates never link or configure anything. OpenCode does not load Claude
Code plugins: the generic skills are plain `SKILL.md` files under `.devkit/plugins/marola-devkit/skills/`
(or [on GitHub](https://github.com/marola-dev/marola-devkit/tree/v0.2.1/plugins/marola-devkit/skills)),
readable directly.
**JDK 25 is required, not just "17+".** Kyo's artifacts won't load on an older JVM. See
`.claude/rules/scala.md` (loaded automatically while editing `.scala`/`build.sbt`) for the full
JDK/Kyo-versioning detail and the jar-verification approach for Kyo's pre-1.0 API surface.

## Phase discipline (hard rule)

Work **one phase at a time**, per `docs/PHASES.md`: do not start Phase 2 (going live on
a cloud backend, GCP per MIP-0057) before Phase 1 (Telegram bot actually working) is done. This
exists to prevent an expensive mistake, so don't skip it because a later phase looks more interesting. If asked to jump ahead,
implement the requested feature but flag which earlier-phase prerequisite is still missing.

## Issue tracking (hard rule)

**An agent may only begin implementation on an issue carrying `agent-ready`.** That label is the
only statement that a human has decided what done means and what proves it; `just issue-ready <n>`
adds it when the five-rule Definition of Ready passes (`docs/3-Working-on-the-repo/ISSUE-FLOW.md`). Read the queue with
`just issue-queue`, take one with `just issue-claim <n>` — it re-checks the rules and prints the
branch command. An idea with no issue is not work yet, and **filing is a human's act**: the
`/marola-devkit:triage` skill drafts, a person files (MIP-0063 §5.6).

## Cost & deployment safety (hard rule)

**Never provision or deploy a paid cloud resource without explicit human confirmation first.**
Propose the change, state the expected cost, wait for a go-ahead. Never hardcode a key/connection
string/secret; `.env.example` holds placeholders only. ai-jail limits what a session can reach, but
it does not replace this rule.

## Attribution and cost accounting (hard rule)

- **Commits carry three trailers and nothing else:** `Tested:`, `Cost:` (both below) and
  `Co-Authored-By: Claude <noreply@anthropic.com>`. No session links, no "Generated with" banners,
  no PR-body attribution. This is enforced by `.claude/settings.json` (`attribution.commit`,
  `attribution.pr: ""`, `attribution.sessionUrl: false`), the shared project settings. Don't add
  attribution text by hand.
- **The whole PR workflow is one command.** Write the commit (a body plus `Tested:`/`Cost:`
  trailers), then `just pr`: it fills any missing trailer (`just cost-fill`), pushes, and writes
  the PR from `.github/PULL_REQUEST_TEMPLATE.md` (`just uprd`, or `stack pr` on a
  `mip-NNNN/k-*` branch). `Cost:` prefers `just cost-split`'s measured figure over
  `cost-split --estimate`'s diff-size fallback, labelled `est.`.
- **One feature, one session.** Start a feature with `/clear` and `/rename` it to the branch name
  so `/usage`'s session block and ccusage's per-session rows map to one PR. Re-runs of
  `just benchmark`/`just e2e` driven by the agent count toward the feature; note them. When one
  session produces several PRs (a MIP stack), `just cost-split MIP-NNNN` attributes usage to each
  commit by time and prints the per-branch `Cost:` trailer, measured, not estimated.
  `just uprds MIP-NNNN` refreshes every PR of the stack with its Cost section.
- **Heavier option, when it matters:** Claude Code exports `claude_code.cost.usage`/
  `claude_code.token.usage` over OpenTelemetry (`CLAUDE_CODE_ENABLE_TELEMETRY=1`,
  `OTEL_METRICS_EXPORTER=otlp`, `OTEL_EXPORTER_OTLP_ENDPOINT=...`) tagged by `session.id`/`model`/
  `skill.name`/`mcp_tool.name`; a future Phase 2 could land agent spend next to marola's own
  `Telemetry` traces.

## Code style

Scala style, the Kyo effect boundary, and testing discipline are in `.claude/rules/scala.md`,
auto-loaded while editing `.scala`/`build.sbt`. Short version for every language: prefer `enum` +
exhaustive matching over exceptions for expected failure modes, and reproduce a bug with a failing
test before fixing it.

**Comments: write few, and only what the code cannot say.** Agents overshoot here badly: #280,
#284, #286 and #287 were four separate passes cutting comment lines roughly in half (Scala 1882 to
793, shell 1071 to 565, the justfile 392 to 135, JavaScript 166 to 115), and the same verbosity
grows straight back unless it is refused in review. Before writing a comment, check it is one of
these:

- **why, not what**: a non-obvious decision, a rejected alternative, a constraint from outside the
  file (an API's behaviour, a licence, a version pin). `// increment i` is noise; "429 is Overpass's
  documented back-pressure, not an exception" is not.
- **a trap**: something that will look like a bug, or bite the next reader, and is invisible here.
- **a pointer**: the MIP or issue that explains the shape, in one reference, not a summary of it.

Everything else belongs in the commit message, the MIP, or nowhere. Specifically: do not restate
the code in prose, do not narrate the history of a fix in the file it fixed, do not re-explain in a
comment what a good name already says, and do not paste a paragraph where a clause works. A
docstring that is longer than the function it documents is a defect, not thoroughness. The commit
message is the right home for reasoning and evidence: it is versioned, it is read once, and it
does not have to be maintained forever alongside the code.

Prefer running agent tools through [ai-jail](https://github.com/akitaonrails/ai-jail), via
`just jail-claude` (or `jcf`/`jcs`, pinned to fable/sonnet), over bare. It sandboxes the process
(bubblewrap/Landlock/seccomp); it doesn't replace the rules above or stop bad code/overspend, only
out-of-sandbox access. `.env`/`*.pem`/`*.key` are masked regardless of disk content;
`just jail-dry-run <cmd>` previews a jailed command. **A jail never has a `gh` login of its own,
and cannot acquire one:** `~/.config/gh` is not mapped in, so `gh auth login` *inside* the jail
writes to an ephemeral HOME and is gone by the next session. That is the "reauth every time" loop,
not a bug in `gh`. `just jail-claude`/`just jail-opencode` therefore resolve a token on the host,
via labs/agentic's `gh-token` (h0ffmann/nix-config, a flake input), and forward the value with
`--env GH_TOKEN`: a fine-grained key in the gitignored `.env` first (the shellHook loads it, the
narrowest credential, so it wins), otherwise the host's own `gh auth token`. Authenticate **once on
the host**, never inside the jail. If neither exists, `jail-claude` says so at startup rather than
letting you discover it when `just uprd` fails. `jail-claude` runs `ai-jail --exec`, direct
execution with no PTY proxy/status bar, because the proxy is what broke Ctrl+C (it owns the raw
terminal and must relay the interrupt byte itself) and mangled multi-line/bracketed pastes; see the
justfile comment above `jail-claude` for the full diagnosis. `JAIL_CLIPBOARD=1` opts into a
write-only clipboard bridge (`just clip`), off by default. Plain text Ctrl+V paste needs nothing
extra (the terminal emulator injects it as ordinary input); Claude Code's own image-paste needs a
real X11/Wayland socket, which `JAIL_CLIPBOARD_PASTE=1` opts into. That is off by default, and a
bigger grant than the write-only bridge (a full display socket, not a one-way pipe; on X11
specifically, any client on that socket can read other windows and inject input, not just read the
clipboard). **Ubuntu 24.04:** `bwrap: setting up uid map: Permission denied` means unprivileged
user namespaces are blocked by default. Fix via a scoped AppArmor profile, or disable the sysctl
(weakens the protection globally).

## Before implementing a feature

Check `docs/MIPs/` and `docs/4-Research-and-plans/FUTURE-WORK.md` first: the idea may already be designed or decided.
For a proactive/autonomous agent behavior (e.g. the escalation-agent idea in
`docs/4-Research-and-plans/FUTURE-WORK.md`), keep the human-confirmation gate unless a MIP decides otherwise. This is not
optional polish.

## When something here turns out to be wrong

Update this file, `.claude/rules/*.md`, or the relevant `docs/*.md` in the same change (a cloud
API/limit/version changes, a library moves past the version pinned in `build.sbt`, etc.). Rules
files are excerpts that link back here, not the only copy. This file is read by non-Claude agents
too, so a rule that matters everywhere stays stated here even if the full detail moved.
</content>
