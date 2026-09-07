---
name: mip-solve-perpetual
description: Overnight/unattended MIP-tasks runner. Works through one or more MIP*.tasks.md files, one task at a time, opening a Draft PR per task, throttling model/effort to stretch usage, and stopping cleanly on a usage limit or a real blocker. Only ever invoked explicitly — never auto-triggered.
disable-model-invocation: true
allowed-tools: Bash(just build*) Bash(just test*) Bash(just quality*) Bash(just pr*) Bash(sbt *) Bash(scripts/stack.sh *) Bash(git *) Bash(gh pr create *) Bash(gh pr edit *) Bash(gh pr view *) Bash(gh project *) Bash(gh auth status*) Read Grep Glob
disallowed-tools: Bash(gh pr merge*) Bash(gh pr close*)
---

# /mip-solve-perpetual

**Before relying on this overnight:** confirm `.claude/settings.json` actually has
`permissions.deny` entries for `Bash(gh pr merge*)` and `Bash(gh pr close*)`. The
`disallowed-tools` line above is a redundant second layer, not the real boundary — it's
documented to clear after a message, and this command runs for hours across many internal
turns. The settings.json deny rule is what actually holds for the whole run. If it isn't there,
stop and add it before using this command unattended.

## Arguments

`$ARGUMENTS` — one or more MIP numbers to work through, in order, e.g. `/mip-solve-perpetual
0011 0013`.

**If empty: auto-pick the single easiest actionable MIP, don't ask.** This is a deliberate,
explicit mode — not a fallback to guess quietly — so state the pick out loud before doing
anything else, and again in every PR body this run produces (e.g. "auto-picked MIP-0017: S
effort, 'cheap win', no earlier-phase gap"). Selection, in order:
1. Read `docs/mips/README.md`'s index. Candidates are every row with `Status` = `Draft` (not
   `Implemented`/`Rejected`/`Superseded`, and not a MIP already fully merged whose README row is
   simply stale — check `git log --oneline main | grep -i "MIP-NNNN"` if a Draft row looks
   suspicious, per this doc's own "evidence-based status" convention).
2. Drop any candidate blocked by `AGENTS.md`'s phase discipline — a Phase 2+ MIP is not
   actionable before Phase 1 (the Telegram bot) ships; a Phase 0 (dev-tooling) MIP is always fair
   game regardless of product phase.
3. Rank the rest by `Effort` (S, then M, then L, then XL) and prefer a `Verdict` of "cheap win"
   over "do next"/"park"/"do when X lands"/"expensive, defer". Tie-break by lowest MIP number
   (oldest waiting first) — deterministic, not a coin flip.
4. If the picked MIP has no `docs/mips/MIP-NNNN.tasks.md` yet, run the `mip-tasks` skill on it
   first, in this same invocation, before starting task 1 — the loop below needs that file to
   exist. Note in the first PR's body that the tasks file was generated this run, not pre-existing.
5. Proceed exactly as below with the picked number substituted for `$ARGUMENTS`.

## What to do

/goal Every row in each of docs/mips/MIP-$ARGUMENTS.tasks.md (space-separated, in the given
order) has an open PR, or is logged as blocked after failing twice. On stopping, print
GOAL_COMPLETE: <summary> or GOAL_FAILED: <reason> — grep-able, so a morning check is one command.

/loop every 15m until: goal met · Work through the first file starting at the first unmerged
task, then the next file, one task at a time.

**What that loop can and cannot do (verified 2026-09-06):** it keeps *this human-started turn*
going; it cannot start the skill. A `/mip-solve-perpetual …` delivered by a cron/wakeup arrives
as plain text — the harness does not expand it and the Skill tool refuses it
(`disable-model-invocation`). So an overnight run is one typed invocation that lives as long as
the session and the usage guard allow; once it stops (`GOAL_COMPLETE`/`GOAL_FAILED`/wind-down),
the next start is a human keystroke again. Do not schedule this command for a later hour and
expect it to run — stage everything so the typed command works first try instead.

**Rules, no exceptions:**
- One task = one branch = one PR via `scripts/stack.sh` (never bundle two tasks in one commit).
- Before every commit: `just build && just test && just quality` must be green.
- Every commit gets `Tested:`/`Cost:` trailers per `AGENTS.md`, plus the Co-Authored-By line.
- Push and open the PR with `just pr`. **Never** run `gh pr merge` or `gh pr close`, under any
  circumstances, even if a PR looks trivially safe — merging is the human's to do, on waking up.
- Never run `azd up`/`provision` or `az deployment` — the cost/deployment safety gate stays
  absolute, no exception for being unattended.
- **If `gh` isn't authenticated, do not stop the loop and do not skip the PR step — degrade and
  keep going.** Check once per task with `gh auth status`, then:
  1. Still `git push -u origin <branch>` (and force-push with `--force-with-lease` if the branch
     needed a restack — `git merge-base --is-ancestor origin/main <branch>` before touching
     anything destructive, per the repo-wide git safety rules).
  2. Append a block to `GH_POST_MORTEM.md` at the repo root (if it doesn't exist yet, create it
     with a one-paragraph header explaining why it exists and how to use it — see the file's own
     history for the shape; never overwrite an existing one, always append) with the exact
     `gh pr create --base <base> --head <branch> --title ... --body-file <(git log -1
     --format=%B <branch>)` command for the task, headed by today's date and which MIP/task it's
     for. Every branch in the stack gets its own block, even ones from earlier tasks that were
     already pushed — the file is the one place a human reads to catch every pending `gh` call,
     not just the latest.
  3. Once every task in the current run is done, append one `scripts/stack.sh link MIP-NNNN`
     command to link the whole stack, after the human has created the PRs above.
  4. Treat the task as done for `/goal` purposes — "an open PR" is satisfied by "pushed + its
     exact `gh pr create` logged in `GH_POST_MORTEM.md`" when `gh` has no session auth. This is
     not a blocker on the human's decision (nothing risky is being skipped — merge/close stay
     denied regardless of `gh` auth via `.claude/settings.json`), so it must never end the run.
  5. `GH_POST_MORTEM.md` is gitignored — never add it to a commit; it's a local operator log, not
     a deliverable. If a task's own commit touches `.gitignore`, that's fine (the entry belongs in
     source), just don't stage the post-mortem file itself.

**Ground truth this section relies on (verify before trusting it — plugins and docs drift):**
Claude Code meters against two official windows — a rolling 5-hour session window and a fixed
weekly window — each with its own `used_percentage` (0-100) and `resets_at` (epoch seconds). That
`rate_limits` data is delivered **only to the statusLine command**, not to hooks, the API, or a
plain `/usage` call by itself — which is why the `heavy-usage` plugin exists: its statusLine
script captures the numbers to `~/.claude/heavy-usage/usage-live.json` so its own `/usage`
command (invoke it as `/heavy-usage:usage` — a bare `/usage` may collide with the built-in
command depending on what else is installed) and its `UserPromptSubmit` hook can read them. **If
`heavy-usage`'s statusLine was never wired (`/usage setup`), there is no data — check for this
once at the start of a run** (this session's own `SessionStart` hook already reported "Not wired
yet" once; don't assume a later run is different without checking). Both windows are metered in
aggregate across models — there is no separate per-model quota — so a heavier/pricier model burns
the *same* shared percentage faster per token, it doesn't get its own ceiling. "Fable" here is
Anthropic's flagship, highest-cost model tier (priced above Opus), not a cheap/fast one — treat an
escalation to it as buying *quality per token*, not *tokens per dollar*: it depletes both windows
faster, which is exactly the "will for sure hit the API limits" risk this section exists to guard
against, not a myth to dismiss.

**Model & effort routing — decide before starting each task, not only after it fails:**
1. Before every task, classify it as *mechanical* (single small file, wiring/config, no design
   judgment — e.g. adding a hook stanza to `.claude/settings.json`) or *substantive* (anything
   needing multi-file reasoning, a new algorithm, or judgment calls). Mechanical tasks start at
   `/effort low` on the default model — most of this MIP's early hook/config tasks qualify;
   spending default effort on them is the "Sonnet wastes tokens on basic things" failure mode the
   design is meant to avoid. Substantive tasks start at default effort. Never start a task above
   default effort speculatively — only a real failure earns that (rule 2).
2. One failure on a substantive task (or any failure on a mechanical one): raise to `/effort
   high` on the same model before anything else — no context-reprocessing cost, cache-friendly on
   Fable 5.1 specifically. Cheaper than a model switch.
3. Two failures at raised effort: only *then* is `/model fable` for that one task on the table —
   and only if the budget check below clears it. The moment its PR is open (or logged in
   `GH_POST_MORTEM.md` per the gh-fallback rule), `/model sonnet` (or whatever the default was)
   and default effort — don't carry the escalation into the next task "just in case."
4. **Budget-gate the Fable escalation explicitly** — this is the fix for the reactive-only
   design: before switching, estimate this task's likely cost at Fable rates (use the measured
   `Cost:` trailers already on this MIP's earlier task commits, e.g. `git log --grep "^Cost:" -F
   --all -- docs/mips/MIP-$ARGUMENTS.tasks.md`'s sibling branches, or `python3
   scripts/cost-split.py --estimate`, as a per-task baseline, then scale by Fable's list price
   vs. the default model's from the `claude-api` skill reference) and check it against the
   **projected remaining headroom** computed in the usage guard below for whichever window is
   more constraining. If Fable's estimated burn would push that window's projected end-of-window
   usage past its wind-down threshold, **stay on the default model even if slower** — a stranded
   Fable run mid-task with no clean stop is worse than a slow-but-complete Sonnet run. Log the
   budget numbers you computed in the PR body next to which throttle level was used.
5. Note in each PR body which of 1-3 was used and the budget check from rule 4. If the actual
   model used doesn't match what was requested, say so explicitly — silent model downgrading on
   hitting a cap is a known, reported Claude Code behavior, not something to assume didn't happen.

**Usage guard — real math before every task, not a single flat threshold:**
- Primary, run before *every* task (not just once): read the current `used_percentage` and
  `resets_at` for both windows (`/heavy-usage:usage --json` if wired; otherwise fall back to
  `python3 scripts/cost-split.py --estimate`'s running session total against a dollar budget
  stated when this run was started, and treat the 5-hour/weekly window math below as unavailable
  until `/usage setup` is run). For whichever window is more constraining, compute:
  - `elapsed_frac = (window_seconds - (resets_at - now)) / window_seconds` (5h = 18000s, weekly =
    604800s),
  - `projected_end_pct = used_pct / elapsed_frac` (linear extrapolation from current burn — this
    is the same formula `heavy-usage` itself uses internally; verify against its installed
    `scripts/usage-lib.js` if the numbers look off, since a plugin update can change it).
  - Add this task's estimated cost (rule 4's baseline) to `used_pct` before projecting, so the
    check answers "can *this* task finish inside budget," not just "was the window fine a moment
    ago."
- Decision, per window, most conservative wins:
  - `projected_end_pct < warn` (default 75% five-hour / 85% weekly) → proceed normally.
  - `warn ≤ projected_end_pct < wind-down` (90% five-hour / 95% weekly) → finish the current
    task's PR, then stop — don't start a new one, even a mechanical one.
  - `projected_end_pct ≥ wind-down`, or `hits 100% before resets_at` → stop **now**, before
    starting the in-progress task's remaining work, once the checkpoint contract below is met.
- Backup, always wins if more conservative: `heavy-usage`'s `UserPromptSubmit`-injected
  `[heavy-usage]` WIND DOWN line is live/fresher data — treat it as authoritative the instant it
  appears, regardless of what the primary math concluded a task ago.
- A weekly-window warning is not an overnight wait — stop entirely and report, don't treat a
  multi-day cap like a five-hour one it'll recover from by morning.

**Checkpointing contract — required before the loop is allowed to stop for a usage reason:**
The current task must land in exactly one of two states, never a third:
1. **Done**: `just build && just test && just quality` green, committed with `Tested:`/`Cost:`
   trailers, pushed, and `gh pr create` either run or — if `gh` has no session auth — its exact
   command appended to `GH_POST_MORTEM.md` at the repo root (gitignored; create it if absent) so
   a human can replay it. This satisfies the task's `/goal` row either way.
2. **Abandoned this run**: working tree returned to clean (stash or discard only what this task's
   attempt added — never touch unrelated uncommitted state) with one line in the stop report
   naming the task and why it didn't land, so the *next* run starts it fresh rather than
   inheriting a half-finished tree.
Never stop with uncommitted, half-built state and no note — that silently corrupts the next run's
starting point.

**Run tracker (MIP-0017 §5.1) — append one row per task attempt, including failed attempts.**
For each MIP being worked, append to `docs/mips/MIP-NNNN.run-log.csv` at the repo root (create
with a header row if absent; gitignored like `GH_POST_MORTEM.md` — an operator artifact, not a
deliverable, never staged or committed):

```
timestamp,task_k,branch,throttle_level,model,effort,outcome,cost_usd,pr_status
```

One row every time a task attempt ends, in any of the four states this section already produces:
succeeded, failed once (about to raise effort), failed twice (about to consider Fable, or logging
blocked), or abandoned this run (the checkpointing contract's state 2). `throttle_level` is which
of the "Model & effort routing" rules 1-3 applied; `pr_status` is `open`/`logged-in-post-mortem`/
`blocked`. This is strictly additive to the checkpointing contract above — that contract defines
*when* the loop may stop; this file records *what happened*, in one place a human (or the next
run) can read without reconstructing it from git log and `GH_POST_MORTEM.md` by hand.

**Stop and report** once every row in every given task file has an open PR (or has failed
twice), the usage guard triggers *and* the checkpointing contract above is satisfied, or a
decision only the human can make comes up. Don't guess past a real blocker — report it and stop.
