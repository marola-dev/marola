---
name: mip-tasks
description: Turn an accepted MIP into an ordered task list and deliver it as small stacked PRs (one task = one branch = one PR, each based on the previous). Use when the user says "break down MIP-NNNN", "plan the tasks for", "stack the PRs", "restack", or wants a MIP implemented in reviewable pieces. Pairs with the mip skill (design) and superpowers' executing-plans / test-driven-development (doing each task).
disable-model-invocation: true
---

# From a MIP to stacked PRs

A MIP says *what* and *why*. This skill produces the *how, in order*, and keeps delivery organised:
every task is a branch stacked on the previous one, with its own small PR, so review happens in
pieces and `main` only ever receives green, tested steps. Mechanics live in `scripts/stack.sh`.

## Arguments

`$ARGUMENTS`: **one** MIP number, the same shape `/mip-solve-perpetual` takes: `/mip-tasks 0009`.
Accept the sloppy forms too and normalise before anything else: `9`, `009`, `0009`, `MIP-009`,
`MIP-0009`, `mip-0009` all mean `MIP-0009` (digits only, zero-padded to four, `MIP-` prefix
restored). Say the normalised number out loud in the first line, then confirm
`docs/mips/MIP-0009-*.md` exists; if it doesn't, stop and say so; never pick a neighbour.

- **Empty → stop and ask** which MIP. Unlike `/mip-solve-perpetual`, this skill has no auto-pick:
  slicing a MIP into reviewable PRs is the human decision the perpetual loop defers to, so it does
  not guess which MIP to slice either.
- **More than one number → stop and ask** for one. A planning session produces one tasks file;
  stacking two MIPs' tasks into one chain is exactly the "never stack by accident" rule below.

## When to use it

- A MIP's status is Accepted (or the user says "implement MIP-NNNN") and the work is more than one
  PR's worth. For a single-PR change, skip this and just branch from `main`.
- The stack must be rebased after a base PR merged (`restack`).

## Step 1 — the task list (planning session)

Read the MIP, `AGENTS.md`, and the code it touches. Write `docs/mips/MIP-NNNN.tasks.md`:

```markdown
# MIP-NNNN tasks

| # | slug | delivers | tests (must exist before the PR) | depends on |
|---|---|---|---|---|
| 1 | board-json | `core/site/Board` serializer + schema | BoardSpec from golden fixtures | – |
| 2 | site-build | `--site` flag, `just site-build`, static assets copied | golden: dist/ has latest.json | 1 |
| 3 | map-page | index.html + app.js reading latest.json | manual: open, tap a beach | 2 |
| 4 | scheduling | site.yml workflow, docs | actionlint | 3 |
```

Rules for a good list:

- **Each task is one reviewable PR**: ≤ ~400 changed lines, one concern, its own tests, green on
  `just build && just test && just quality` by itself.
- **Order by dependency, then by risk**: the task most likely to change the design goes first.
- **Tests are named up front**: the MIP's §7 verification plan is the source; every task row
  says which test proves it. A task with no test is either docs or a smell.
- **Docs travel with the task that changes behaviour**, not in a final "docs" task (except
  workflows/deploy docs).
- Note anything that would force a restack later (a task that rewrites a file an earlier task
  touched) and reorder to avoid it.

Commit the task file on the first task's branch (it is part of task 1) and add a line to the
MIP: `Tasks: docs/mips/MIP-NNNN.tasks.md`.

## Step 2 — one task, one branch, one PR (execution sessions)

Start a fresh session per task (`/clear`, `/rename mip-NNNN/k-slug`), then:

```bash
scripts/stack.sh start MIP-NNNN <k> <slug>   # branch mip-nnnn/k-slug off the previous task's branch
# implement — test first (superpowers' test-driven-development), commit with a Cost: trailer
just build && just test && just quality        # + a live check if a data path changed
scripts/stack.sh pr                            # push, open/update the PR with base = previous task
```

The PR body is generated (`scripts/uprd.sh`) and starts with "Stacked on `<base>` — part of
MIP-NNNN". Review and merge **in order, bottom of the stack first**, squash-merging as this repo
does.

When several tasks came out of one session, `just cost-split MIP-NNNN` splits that session's
logged usage by commit time and prints the measured `Cost:` trailer per branch (amend before the
PR, or note the split in the body). `just uprds MIP-NNNN` refreshes every PR of the stack at once,
regenerated body plus a shared "Stack" section (merge order, states, summed Cost), and opens
any PR still missing on its right base.

## Step 3 — after a base PR merges: restack

Squash merges give the merged commits new hashes, so the next PR in the stack shows conflicts
until it is rebased onto `main`:

```bash
git checkout mip-nnnn/<k+1>-...
scripts/stack.sh restack        # rebase --onto origin/main <fork point>, force-push with lease
scripts/stack.sh status         # every branch of the MIP: base, PR number, state
```

GitHub retargets the child PR to `main` automatically when the merged base branch is deleted;
`restack` handles the commits. Repeat up the stack.

The fork point is the merged base's last commit. GitHub deletes that branch on merge, so `restack`
recovers it from the merged PR via `gh`; without `gh` (inside the jail, say) it **stops and refuses**
rather than rebasing onto `main` blind, which would replay every already-merged commit as a conflict.
Unblock it, and the rest of the stack, by naming the commit yourself:

```bash
scripts/stack.sh restack --onto-base <sha>   # <sha> = the merged base branch's head
```

## Step 4 — finish

When the last task merges: flip the MIP to `Implemented` (with the PR numbers), delete the task
branches, and put the summed `Cost:` figures from the PRs into the MIP's status row.

## House rules that still apply

- The `mip` skill's rules (local-first, deterministic safety logic, no unsourced text) and
  `AGENTS.md`'s `Cost:` trailer per commit.
- Never stack by accident: a follow-up to an *already merged* PR is a new branch off `main`, not a
  child of the old branch (memory: single PR per deliverable). Stacks are for planned task lists.
- `gh` needs a login (not available inside the ai-jail sandbox): `scripts/stack.sh pr --dry-run`
  prints the commands; run them from the host if needed.
- GitHub's native Stacks: `just stack-setup` once (installs the official `gh stack` extension),
  then `just stack-link MIP-NNNN` links the PRs bottom-to-top into a Stack; `just uprds` does
  it automatically when the extension is present (open PRs only; a merged bottom branch is
  skipped). `just stack-view` shows it; `just stack-sync MIP-NNNN` is the extension's `restack`
  for the whole stack (it adopts the stack locally first, since `link` keeps no local state). The script stays the source of truth for
  branch naming and bases; the extension is the UI.
- Review happens **only when the human asks**: per PR, bottom-up, against the PR's own base:
  superpowers `requesting-code-review` (reviewer subagent with BASE/HEAD SHAs and the task row as
  the plan), `/code-review <PR#>`, or `/code-review ultra`. The whole loop, with the acceptance
  step for a MIP, is in `docs/DEV-FLOW.md`.
</content>
