---
name: mip-tasks
description: Turn an accepted MIP into an ordered task list and deliver it as small stacked PRs (one task = one branch = one PR, each based on the previous). Use when the user says "break down MIP-NNNN", "plan the tasks for", "stack the PRs", "restack", or wants a MIP implemented in reviewable pieces. Pairs with the mip skill (design) and superpowers' executing-plans / test-driven-development (doing each task).
---

# From a MIP to stacked PRs

A MIP says *what* and *why*. This skill produces the *how, in order*, and keeps delivery organised:
every task is a branch stacked on the previous one, with its own small PR, so review happens in
pieces and `main` only ever receives green, tested steps. Mechanics live in `scripts/stack.sh`.

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
- **Tests are named up front** — the MIP's §7 verification plan is the source; every task row
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
- Optional tooling: `git-spice` (in nixpkgs) manages stacks natively (`gs branch create`,
  `gs stack submit`, `gs repo sync`); adopt it if the manual script becomes the bottleneck. Not
  added yet — the script is enough for a two-to-five-task stack.
