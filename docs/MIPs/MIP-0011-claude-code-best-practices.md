# MIP-0011: Claude Code best practices in this repository — hooks as gates, a shared permission allowlist, path-scoped rules, subagents and skills the dev flow already implies

| | |
|---|---|
| **Status** | Implemented — all eleven tasks merged (`docs/MIPs/MIP-0011.tasks.md`); verified with `/code-review ultra` (ultrareview) against the full stack diff on 2026-09-06, see §7 |
| **Author** | Claude Fable 5.1, for M. Hoffmann (request of 5 Sep 2026: "MIP for implementing Claude Code best practices, by Anthropic and community") |
| **Created** | 2026-09-05 |
| **Tasks** | `docs/MIPs/MIP-0011.tasks.md` — stacked PRs, one per task |
| **Phase** | 0 — developer tooling; nothing a user of marola sees. No earlier-phase prerequisite |
| **Related** | `AGENTS.md` (the rules this turns from advisory into enforced), `docs/3-Working-on-the-repo/DEV-FLOW.md`, `docs/3-Working-on-the-repo/AGENT-SKILLS.md` §3 (skill candidates), `docs/4-Research-and-plans/FABLE_REVIEW.md` §3 (jail environment notes), `PHILOSOPHY.md` (why constraints, not prose), `.claude/settings.json`, `.claude/skills/`, `justfile` (`jail-claude`, `jcf`, `jcs`); MIP-0013 (an OpenCode tryout — most of tasks 1-5, 7 and 9 here have a one-config-key equivalent there); `docs/en/interactive-mode`, `docs/en/routines` (background/overnight execution, task 11) |
| **Effort** | M — ten small, independent config/hook PRs; each individually small, but ten of them |
| **Gain** | infra/dev-loop (turns advisory `AGENTS.md` rules into enforced config); cost/ops (fewer permission prompts, a shorter `AGENTS.md`) |
| **Effort vs Gain** | do next — cheap and overdue |
| **Depends on** | none blocking; Phase 0, developer tooling only; MIP-0013's tryout result may show which tasks are Claude-Code-specific |
| **Risk** | a shell-text `permissions.deny` is a guard rail, not a boundary — `bash -c "gh pr merge"` evades it; ai-jail and the human go-ahead remain the real boundary |
| **Cost so far** | ~$12.86 total across the eleven merged task PRs (`Cost:` trailers, mostly measured via `scripts/cost-split.py`; task 6's `$3.40` is a diff-size estimate, no session log matched) — not counting the design/MIP-writing session bundled into MIP-0010's shared total (commit 3fdcd05), or the follow-on ultrareview verification (see §7 — a free-tier request this time, at $0 to the user; note the real per-review price here once a paid one is spent, so this line stays honest about what future reviews cost) |

## 1. Summary

marola already follows the parts of Anthropic's guidance that are habits: a `CLAUDE.md` that imports
`AGENTS.md`, two in-repo skills, one session per feature, a cost line per PR, the agent in a
sandbox. What it does not have is the part that is **configuration the harness enforces**: no hooks
(every rule in `AGENTS.md` is text the model may or may not weigh), no shared permission allowlist
(every session re-approves `just test`), no path-scoped rules (`AGENTS.md` is 196 lines loaded into
every session, on the edge of the documented adherence cliff), no subagents (the reviewer
`DEV-FLOW.md` §5 describes is run by hand), no `.mcp.json` (marola's own MCP server is not offered
to the agent working on marola). This MIP adopts the documented practices in that order, each as one
small PR, with a measurable "done".

## 2. Motivation

Quoted from the docs fetched 2026-09-05 (`code.claude.com/docs/en/…`):

- *"Unlike CLAUDE.md instructions which are advisory, hooks are deterministic and guarantee the
  action happens."* (`best-practices`). `AGENTS.md`'s cost rule is the highest-stakes rule in the
  repo and it is advisory.
- *"target under 200 lines per CLAUDE.md file. Longer files consume more context and reduce
  adherence."* (`memory`). `AGENTS.md` is 196 lines and grows with every doc-table row.
- *"Commit `.claude/settings.json` so everyone who clones the repository gets the same permissions,
  hooks, telemetry, and plugins."* (`settings`). Ours holds attribution and one plugin.
- *"Use `disable-model-invocation: true` for workflows with side effects"* (`skills`). `mip-tasks`
  creates branches and PRs and can be auto-invoked.
- Local evidence: `docs/4-Research-and-plans/FABLE_REVIEW.md` §3 (`gh` unauthenticated inside the jail, `.env.example`
  reading as empty) is re-discovered per session; auto-memory currently carries it. A
  `SessionStart` hook can print it once.
- *"When a claude.ai usage limit stops Claude mid-task, Claude Code waits in the open session
  and continues the task on its own after the limit resets... automatic continue is on by
  default."* (`interactive-mode`, fetched 2026-09-05). This closes what was, until recently, a
  well-documented gap: `anthropics/claude-code` issues #35744, #26775, #18980, #36320, #38263,
  #62788 all requested exactly this, several as late as March 2026, with a small ecosystem of
  third-party wrappers (`claude-auto-retry`, `claude-auto-resume`, `claude-delayed-message`)
  built to work around its absence. What it does and doesn't cover matters before relying on it
  for an unattended overnight run; see the new risks below.

## 3. User-visible change

None for marola's users. For whoever runs `just jcf` / `just jcs`:

```
$ just jcf
▸ SessionStart: branch mip-0010/1-run-ledger · gh: not logged in (push works, PRs by hand) · 0 uncommitted
> …edits core/src/main/scala/marola/ledger/RunLedger.scala…
▸ PostToolUse(Edit *.scala): scalafmt ok
> (turn ends with .scala changes and no test run)
▸ Stop: 3 Scala files changed since HEAD and `just test` has not run this session — run it or say why.
```

Permission prompts for `just build|test|quality|fmt`, `sbt …`, `git status|diff|log`, `scripts/*.sh`
disappear; anything not on the allowlist still asks.

## 4. Data sources and dependencies reviewed

### 4.1 Anthropic — Claude Code documentation (fetched 2026-09-05)

- **Best practices** (`/docs/en/best-practices`; `anthropic.com/engineering/claude-code-best-practices`
  redirects there): give Claude a check it can run (tests, build, a Stop hook, a `/goal`, a
  verification subagent); explore → plan → code → commit, plan mode for multi-file or unfamiliar
  changes; specific prompts; CLAUDE.md short, "would removing this cause a mistake?", run `/doctor`
  to trim; permissions allowlist + sandbox; CLI tools (`gh`) over API calls; hooks "for actions
  that must happen every time with zero exceptions"; skills in `.claude/skills/`; subagents in
  `.claude/agents/`; `/clear` between tasks, `/rename` sessions; a fresh-context reviewer
  ("Writer/Reviewer"); an adversarial review subagent told to report gaps that affect correctness
  only.
- **Hooks reference** (`/docs/en/hooks`): events incl. `SessionStart`, `UserPromptSubmit`,
  `PreToolUse` (can block), `PostToolUse` (read-only), `Stop` (can block; the harness overrides
  after 8 consecutive blocks), `PreCompact`, `InstructionsLoaded`; matchers by tool name or
  permission-rule syntax (`"if": "Bash(git *)"`); exit code 2 blocks, JSON output carries
  `permissionDecision`/`systemMessage`; handler types command/http/mcp_tool/prompt/agent;
  `${CLAUDE_PROJECT_DIR}` substitution; documented examples: block destructive shell, auto-format
  after edits, watch `.env`.
- **Memory** (`/docs/en/memory`): `CLAUDE.md` load order; `@import`; `.claude/rules/*.md` with
  `paths:` frontmatter loading only when matching files are read; `CLAUDE.local.md` gitignored;
  auto-memory limits (200 lines / 25 KB index); hooks, not CLAUDE.md, for "must happen at a point".
- **Settings** (`/docs/en/settings`): precedence managed → CLI → `.claude/settings.local.json` →
  `.claude/settings.json` → `~/.claude/settings.json`; `permissions.allow/deny/ask`, `hooks`,
  `env`, `attribution`, `enabledPlugins`, `autoMemoryEnabled`; the local file is git-ignored by
  Claude Code when it creates it; add it to `.gitignore` when created by hand.
- **Skills** (`/docs/en/skills`): frontmatter `name`, `description`, `disable-model-invocation`,
  `allowed-tools`, `context: fork`, `paths`, `arguments`; dynamic context via `` !`cmd` ``;
  `/skill-doctor` for token cost and hit rate; the `skill-creator` plugin runs evals in
  `evals/evals.json`; keep SKILL.md under 500 lines.
- **Subagents** (`/docs/en/sub-agents`): `.claude/agents/*.md` with `name`, `description`,
  `tools`, `model`, `permissionMode`, `memory: project`, `maxTurns`; read-only tool sets for
  review; fresh context by design.

### 4.2 Community (fetched 2026-09-05)

- `hesreallyhim/awesome-claude-code`: 53.5k stars, pushed 2026-09-05, the index of hooks,
  skills, statuslines and CLAUDE.md examples; used as a directory, no single claim taken from it.
- `MuhammadUsmanGM/claude-code-best-practices` (wiki, "Last updated May 12, 2026 v1.6"):
  format-on-write and test-on-stop hooks, pre-commit secret blocking, a `PreToolUse` hook that
  validates edits to CLAUDE.md itself, worktrees for parallel tasks, cost budgeting per task type.
  Its model advice ("Claude 3.5 Sonnet") is stale and ignored.
- Several 2026 blog guides (DEV, SmartScope, mcp.directory; search results, not fetched) converge
  on the same list: lean CLAUDE.md, plan mode, subagents for noisy research, worktrees, hooks as
  guardrails, verification loop. Nothing beyond the official docs was adopted from them.

### 4.3 What is already here (read 2026-09-05)

`CLAUDE.md` = `@AGENTS.md` + two lines; `.claude/settings.json` = attribution + `superpowers`
plugin; `.claude/skills/mip`, `mip-tasks` (no `disable-model-invocation`); `.githooks/pre-commit`
(sbt compile of staged Scala, a git hook, not a Claude hook, keep it); `.ai-jail`; `just
jail-claude|jcf|jcs`; `scripts/uprd.sh`, `stack.sh`, `cost-split.py`; auto-memory notes about `gh`
and Docker in sessions. No `.claude/hooks/`, `.claude/rules/`, `.claude/agents/`, `.mcp.json`,
`CLAUDE.local.md`, statusline.

## 5. Design

Each bullet is one task/PR (`mip-tasks` order), all under `.claude/` or the root, each with a
self-test the `quality` recipe runs (`scripts/hooks/*.sh --self-test`, like the Python scripts).

1. **`.claude/settings.json`, permissions.** `allow`: `Bash(just build*)`, `Bash(just test*)`,
   `Bash(just quality*)`, `Bash(just fmt*)`, `Bash(sbt *)`, `Bash(git status*)`, `Bash(git diff*)`,
   `Bash(git log*)`, `Bash(scripts/stack.sh status*)`, `Read`, `Grep`, `Glob`; `deny`: `Read(.env)`,
   `Read(**/*.pem)`, `Read(**/*.key)`. Derive the allow list from real transcripts with the
   `fewer-permission-prompts` skill first; personal extras go to `settings.local.json` (add it to
   `.gitignore`).
2. **Hooks, cost gate.** A `PreToolUse` hook on `Bash` that blocked paid-deploy commands until a
   human go-ahead. It has since been removed, with the integrations it guarded. ai-jail and the
   human go-ahead remain.
3. **Hooks, format on write.** `PostToolUse` on `Edit|Write` matching `*.scala` → `scalafmt` on
   that file via the native binary (`coursier launch scalafmt` cached in the flake; **whether the
   flake already provides a native `scalafmt` was not checked**, §11). `*.py` → `ruff format`
   when present. A slow hook is worse than none: 30 s timeout, single file only.
4. **Hooks, test on stop, once.** `Stop` → `.claude/hooks/stop-gate.sh`: if `git diff --name-only
   HEAD` has `.scala` files and no marker from a `just test` run exists for this session, block
   once with "run `just test` or say why", write the marker, allow the next stop. Never loop (the
   harness stops blocking after 8; this stops after 1).
5. **Hooks, session start.** `SessionStart` prints branch, `gh auth status` in one line,
   uncommitted count, and the two jail caveats from `FABLE_REVIEW.md` §3. Replaces two auto-memory
   notes with a fact the harness states.
6. **`.claude/rules/`.** Move the path-specific halves of `AGENTS.md` into `rules/scala.md` (`paths:
   ["**/*.scala", "build.sbt"]`: Kyo boundary, strictEquality, enum-over-exceptions,
   verify-against-the-jar), `rules/docs.md` (`paths: ["docs/**"]`: status vocabulary, MIP template
   pointer). `AGENTS.md` keeps what every session needs (what the repo is, commands, phase
   discipline, attribution, cost) and links the rules; target ≤ 150 lines; run `/doctor` for the
   trim list. **Decision needed (§11):** `AGENTS.md` is read by non-Claude agents too; rules are
   excerpts that link back, never the only copy.
7. **Subagents.** `.claude/agents/mip-reviewer.md` (tools `Read, Grep, Glob, Bash`, `model:
   fable`, `memory: project`): reviews a task branch against its `MIP-NNNN.tasks.md` row, reports
   gaps that affect correctness or the stated requirement only, `DEV-FLOW.md` §5's prompt, made a
   file. `.claude/agents/jar-verifier.md` (tools `Bash`, `model: sonnet`): `javap` a pinned Kyo
   class and report the real signature; `AGENTS.md`'s rule as a cheap delegate.
8. **Skills.** `disable-model-invocation: true` on `mip-tasks`; the four candidates from
   `AGENT-SKILLS.md` §3 (`fixture-refresh`, `benchmark-compare`, `corpus-doc`, `water-provider`),
   one PR each, each with `evals/evals.json` and a `skill-creator` run before merge.
9. **`.mcp.json`.** `marola` → `just mcp-server` (stdio) so a session working on marola can call
   `find_nearby_beaches` / `get_swim_recommendation`, dogfooding `ARCHITECTURE.md` §5c.
10. **`CLAUDE.md` additions** under the import: `/clear` per feature and `/rename` to the branch
    (already in `DEV-FLOW.md`, restated once); compaction instruction "preserve the list of modified
    files, the test commands run, and the Cost figure"; a `CLAUDE.local.md` mention. Optional: a
    statusline showing context use and branch (`statusline` skill), personal, not committed.
11. **Background/overnight MIP execution.** `/goal` + `/loop` locally, or a cloud
    [routine](https://code.claude.com/docs/en/routines) for something that survives the laptop
    sleeping, to run a `MIP-NNNN.tasks.md` row-by-row unattended, stopping at a stated condition
    rather than running indefinitely. Same guard rails as a daytime session, not fewer: the
    branch/PR boundaries tasks 1-2 already set, and the `gh pr merge` deny rule from the earlier
    GitHub-hygiene work; an overnight run still never merges itself.

Not adopted: `claude -p` in CI (a paid run per PR with no reviewer, revisit with MIP-0010's
ledger measuring it); `/batch` fan-out (nothing here is a 2 000-file migration); agent teams
(experimental).

## 6. Scoring / safety impact

None. No product code changes.

## 7. Verification plan

- `scripts/hooks/*.sh --self-test` in `just quality`: `stop-gate.sh` blocks once then allows;
  `format.sh` leaves an already-formatted file unchanged (byte-equal).
- A session check per PR, recorded in the PR body: `/hooks` lists the hooks; `/context` shows
  `rules/scala.md` only after a `.scala` file is read; `/skill-doctor` before/after the skills PR.
- Measure: permission prompts per session (count from the transcript, before vs after task 1);
  `AGENTS.md` line count (196 → ≤ 150); Cost trailers on the next three task PRs vs the previous
  three (the hooks should not raise them; a Stop hook that loops would).
- **Done:** all eleven tasks merged (PRs #102, #108-110, #115-122), `FABLE_REVIEW.md` §3's jail
  caveats are printed by the `SessionStart` hook; `gh-not-logged-in-in-sessions.md` (the one
  matching auto-memory note that actually existed (task 5's PR found only one of the "two" this
  row originally expected) is deleted.
- **Ultrareview, 2026-09-06:** `/code-review ultra` run against the full stack diff
  (`mip-0011/11-overnight-run-guardrail` → `main`, 22 files, +1118/-134) after all eleven tasks
  merged, at the user's request. A free-tier request this session (`Free ultrareview 1 of 3`): $0
  to the user; log the real price here the first time a paid one is spent, so this line doesn't
  silently imply every future review is free. Findings tracked separately, not folded into this
  MIP's own scope (a design doc doesn't get rewritten post-hoc for review findings; those become
  new fix commits/PRs against `main`, referencing this MIP for context).
- **How to refresh the Cost so far figure above**, since all eleven PRs are already merged (the
  normal `just uprds MIP-0011` flow only updates *open* PR bodies, not this row):
  1. `git log --oneline main | grep 'mip-0011 task'`: the eleven merged commit SHAs.
  2. `for c in <shas>; do git show -s --format=%B "$c" | grep '^Cost:'; done`: pull each
     commit's own `Cost:` trailer (already measured via `scripts/cost-split.py` at PR time,
     except task 6, still the one diff-size `est.`) and sum the dollar figures by hand; there is
     no single command that re-sums an already-merged, cross-session stack's trailers today
     (`just cost-split MIP-0011` is for an *open*, unmerged stack in one session's own log).
  3. Edit this row and the matching one in `docs/MIPs/README.md` together; they must always
     agree; `docs/3-Working-on-the-repo/DEV-FLOW.md` has no automation for that agreement, so it's a manual pair-edit.
  4. If task 6's `est.` figure is ever superseded by a real measured one (e.g. a later session
     finds the original log), update this row's total (the merged commit's own `Cost:` trailer
     stays as originally written, commit messages are historical, not corrected in place), and
     note in the doc edit that the figure changed from estimate to measured, so a reader doesn't
     assume it was always precise.

## 8. Risks, limitations, and honest caveats

- Hooks run shell on every matching event inside the jail: a slow formatter or an sbt-backed hook
  would tax every edit; native binaries only, per-file, timeouts set.
- A `Stop` hook that blocks repeatedly burns tokens and trust; the once-per-session design and the
  self-test exist for that.
- `permissions.deny` matches command text; `bash -c "gh pr merge"` or a script can evade it. It is a
  guard rail, not a boundary; ai-jail and the human go-ahead remain the boundary.
- Auto mode's classifier and the deny list can disagree; deny wins for what it matches, and the
  rest stays the classifier's call.
- Splitting `AGENTS.md` risks two copies of one rule drifting; rules link back and CI can grep
  for the sentinel sentences.
- Docs quoted here are a moving target (the hooks page lists events that did not exist months
  ago); each task re-fetches the page it relies on and notes the date in its PR.
- **Auto-continue is reactive, not a throttle.** It waits out a hit limit and resumes; nothing
  in the docs describes pacing usage to avoid hitting the wall mid-task. A long unattended run
  can still burn the 5-hour window in one uncontrolled burst. (The community `heavy-usage`
  plugin claims to "stop safely before the wall", **not verified against its source**, treat as
  an unconfirmed community claim, not confirmed Claude Code behavior, until checked.)
- **It needs a session that stays alive.** The doc says it "waits in the *open* session"; a
  closed terminal or a sleeping laptop breaks this, the same gap the OS-level community
  schedulers exist to work around. A [routine](https://code.claude.com/docs/en/routines) doesn't
  depend on the local machine staying awake and is the safer default for a genuine walk-away
  run; check its own cost model first (§11).
- **A 7-day reset is not an overnight wait.** Same mechanism, different practical meaning: a
  task that exhausts the weekly window stops for up to a week. Check which window
  (`rate_limits.five_hour` vs. `.seven_day`, per the statusline schema) is actually at risk
  before planning an overnight run around it.

## 9. Alternatives considered

- **Do nothing**: the prose works most of the time; "most" is the problem for the cost rule.
- **Managed settings** (`managed-settings.json`): organisation-level; this is a personal repo.
- **ai-jail alone**: filesystem/process containment, no notion of a paid API call or a test gate.
- **One giant `settings.json` PR**: harder to attribute a regression (a hook slowing sessions) to
  a change; ten small PRs match `DEV-FLOW.md`.

## 11. Open questions

1. Does `flake.nix` already provide a native `scalafmt` (coursier launcher or `pkgs.scalafmt`)?
   If not, which is lighter for a per-edit hook?
2. `AGENTS.md` vs `.claude/rules/`: excerpts that link back (proposed) or move the text and leave
   a pointer for other agents? The former duplicates; the latter drops the rule for non-Claude
   tools.
3. `Stop` hook policy: block once (proposed) or advise only via `systemMessage`?
4. Which allowlist entries, from real transcripts (`fewer-permission-prompts`) rather than from
   the list above.
5. Statusline: worth committing a project default, or personal only?
6. Should `.mcp.json` also register a local MLflow (MIP-0010) or Overpass helper, or nothing
   beyond marola's own server?
7. **Resolved by task 11's spike, see `docs/3-Working-on-the-repo/DEV-FLOW.md` §7.** Routines vs. local `/goal`+`/loop`
   for overnight MIP runs: local `/goal`+`/loop` (via `CronCreate`) was run for real this session
   to work through this very MIP's own task stack (real pushed branches, real `GH_POST_MORTEM.md`
   entries), and is the chosen default: no extra environment setup, demonstrated working. A cloud
   routine survives the laptop closing but needs Claude Code on the web/a cloud environment, not
   confirmed available in every setup; adopt it once that's confirmed, not assumed as a
   prerequisite.
8. **Resolved by task 11's spike, see `docs/3-Working-on-the-repo/DEV-FLOW.md` §7.** `heavy-usage`'s "stop before the
   wall" claim, checked against its actual source (`~/.claude/plugins/cache/heavy-usage`): real,
   but soft: a `UserPromptSubmit` prompt injection at a linear-projection threshold (90%
   five-hour / 95% weekly), not a `PreToolUse` block, so compliance is advisory; its data source
   (`usage-live.json`) is populated only while the interactive statusLine renders and can go
   stale in a genuinely headless run (the plugin's own comment: "never suppress a wind-down" even
   on stale data). Treat it as `mip-solve-perpetual`'s own backup usage-guard layer, never the
   sole or primary stop condition, consistent with how the skill already used it before this
   check.

## Appendix

- `https://code.claude.com/docs/en/best-practices` (2026-09-05), the redirect target of
  `https://www.anthropic.com/engineering/claude-code-best-practices` (HTTP 308).
- `https://code.claude.com/docs/en/hooks`, `/memory`, `/settings`, `/skills`, `/sub-agents`
  (2026-09-05).
- `https://github.com/hesreallyhim/awesome-claude-code`: 53 559 stars, pushed 2026-09-05T18:10Z
  (GitHub API).
- `https://github.com/MuhammadUsmanGM/claude-code-best-practices`: v1.6, 2026-05-12.
- Repo state read on 2026-09-05: `AGENTS.md` 196 lines; `.claude/settings.json` keys
  `attribution`, `enabledPlugins`; skills `mip`, `mip-tasks`.
