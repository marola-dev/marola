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
0011 0013`. If empty, stop and ask which MIP(s) rather than guessing which one was meant.

## What to do

/goal Every row in each of docs/mips/MIP-$ARGUMENTS.tasks.md (space-separated, in the given
order) has an open PR, or is logged as blocked after failing twice. On stopping, print
GOAL_COMPLETE: <summary> or GOAL_FAILED: <reason> — grep-able, so a morning check is one command.

/loop every 15m until: goal met · Work through the first file starting at the first unmerged
task, then the next file, one task at a time.

**Rules, no exceptions:**
- One task = one branch = one PR via `scripts/stack.sh` (never bundle two tasks in one commit).
- Before every commit: `just build && just test && just quality` must be green.
- Every commit gets `Tested:`/`Cost:` trailers per `AGENTS.md`, plus the Co-Authored-By line.
- Push and open the PR with `just pr`. **Never** run `gh pr merge` or `gh pr close`, under any
  circumstances, even if a PR looks trivially safe — merging is the human's to do, on waking up.
- Never run `azd up`/`provision` or `az deployment` — the cost/deployment safety gate stays
  absolute, no exception for being unattended.
- If `gh` isn't authenticated, still push the branch and print the exact `gh pr create` command
  instead of guessing or skipping the PR step.

**Throttle — cheapest lever first, log which one was used in the PR body:**
1. Default: current model, default effort. Most tasks belong here.
2. One failure: `/effort high`, same model, before anything else — no context-reprocessing
   cost, and cache-friendly on Fable 5.1 specifically (recent fix). Cheaper than a model switch.
3. Two failures at raised effort: `/model fable` for that one task only. The moment its PR is
   open, `/model sonnet` (or whatever the default was) and default effort — don't carry the
   escalation into the next task "just in case."
4. Note in each PR body which of 1-3 was used. If the actual model used doesn't match what was
   requested, say so explicitly — silent model downgrading on hitting a cap is a known, reported
   Claude Code behavior, not something to assume didn't happen.

**Usage guard — two independent layers, more conservative one wins:**
- Primary: check `/usage` before starting each new task. Under 15% of the 5-hour window left →
  finish the current task's PR and stop, don't start another.
- Backup: if `heavy-usage`'s wind-down hook fires (WARN/WIND DOWN, via `/usage` or the
  statusline) before the primary check would have caught it, follow its instruction — commit,
  write its expected resume note, stop the loop cleanly.
- A 7-day/weekly warning from either layer is not an overnight wait — stop entirely and report,
  don't treat a multi-day cap like a five-hour one.

**Stop and report** once every row in every given task file has an open PR (or has failed
twice), the usage guard triggers, or a decision only the human can make comes up. Don't guess
past a real blocker — report it and stop.
