# Issue tracking

`MIP-0063` puts the backlog into GitHub issues and milestones instead of files. This doc is the
short version; the design and its verification are `docs/MIPs/MIP-0063-github-issue-tracking-standard.md`.

## The object model

| Object | Means | Carried by |
|---|---|---|
| **Milestone** | one deliverable / use case, spanning many issues and possibly several MIPs | native milestone + its progress bar |
| **Issue** | a story or task — the claimable, PR-closable unit | labels, assignee, milestone |
| **Sub-issue** | a subtask, only when a story genuinely splits | native sub-issues |

Milestones are named descriptively, carry no sequence, and order between them is a board decision.
A milestone and a MIP are **n:m** — one deliverable can span several MIPs, one MIP files its tasks
into one milestone.

```mermaid
erDiagram
  MILESTONE }o--o{ MIP : "n:m"
  MILESTONE ||--o{ ISSUE : "spans"
  ISSUE ||--o{ SUB_ISSUE : "splits into, only when it genuinely splits"
```

## The three tiers

Pick the tier, then the matching issue form:

- **Tier 1 — bug, chore, docs.** One issue, no milestone, no spec. Use **Bug report**
  (`bug_report.yml`) for something broken in what a user touches — the pipeline, the CLI, the
  Telegram bot, the site. Use **Task** (`task.yml`) for everything else, *including* something
  broken in the repo's own tooling: a script, a hook, CI. `bug_report.yml` asks which LLM backend
  was running and for the `just run` line that triggers it, neither of which a broken shell script
  has.
- **Tier 2 — small enhancement** (roughly two tasks or fewer, no new dependency). One issue
  **whose body is the spec**, sub-issues if it splits, no MIP file. Use **Story** (`story.yml`).
- **Tier 3 — initiative** (a new data source, a scoring change, a new integration, anything
  paid). MIP proposal issue → MIP PR → Accepted → milestone → N issues. Use **MIP proposal**
  (`mip_proposal.yml`); it is **relabelled**, not replaced, once the MIP PR opens, so the design
  discussion stays attached to it.

## The Definition of Ready

An issue becomes `agent-ready` only once all five rules hold:

1. **Acceptance criteria**, as testable checkboxes (`### Acceptance criteria`).
2. **A named test** — file plus test name (`### Named test`).
3. `area/*` **and** `layer/*` set.
4. `size/*` set — S < 100 changed lines, M 100–400, L means split it.
5. **No open `blocked by` dependency**, read from the API rather than from a label.

A heading whose field the author left blank renders as `_No response_` and does not count.
`AGENTS.md` carries the rule that an agent may only begin implementation on an issue holding
`agent-ready`.

The rules follow the tier, because the forms do. A **MIP proposal** is a design request rather
than claimable work, so it is never `agent-ready`. A **bug report** is claimed on its own two
fields: **What you expected instead** stands for the acceptance criteria, and **Failing test** for
the named test.

**A bug report with no failing test is filed, and is not yet claimable.** The field is optional on
purpose — someone who cannot write Scala should still be able to report a bug, and losing that
report costs more than the missing line. So `issue-ready` answering "rule 2: named test" on such an
issue is the checker working, not a contradiction to fix: rule 2 exists so that what proves the bug
fixed is decided *before* someone claims it, and nobody has decided it yet. A maintainer owes the
report a named test — the test that is red today — and the issue becomes `agent-ready` when they
add it.

**A merged task PR closes its issue.** The closing keyword that works here is the one in the
squash-merge commit that lands on `main`. In this repo a PR-body-only line did not close anything:
#514–#519 carried one and their issues #502–#507 stayed open past merge and were closed by hand,
while #511/#512 closed on a commit-body `Closes #N` (cause unknown, #524). `cost-fill`,
run by `stack pr` (itself run by `just pr`, or directly per
`/marola-devkit:mip-tasks`'s step 2), writes `Closes #N` into the branch's own commit body,
above the `Tested:`/`Cost:`/`Co-Authored-By:` trailers, for the issue that row `k` of
`MIP-NNNN.tasks.md` links; the merge into `main` then closes it, and that clears rule 5 for every
task blocked by it. `uprd` still copies the same line into the PR body so the link is visible on
GitHub, but that copy closes nothing by itself. Set `TASK_PARTIAL=1` before `just pr` for a task
that only delivers part of its row — cost-fill then writes no `Closes #N` at all, since the
`task-partial` label a PR would otherwise carry doesn't exist yet at first push; the issue stays
open. Once a `Closes #N` line is written, a later `task-partial` label does not stop the close:
amend the commit to remove the line. GitHub's built-in board workflow then moves a closed issue's
card to **Done** (configured in the project UI, MIP-0063 §4.4).

## The commands

`issues` is the whole surface, and every subcommand takes `--dry-run` (the reads it is
computed from still happen, so a login is needed either way). Seven of them have a `just` recipe:

| Command | Does |
|---|---|
| `just issue-queue [--milestone NAME]` | the unassigned open issues across the whole org (MIP-0070 §5.7: `gh search issues --owner`, not one repo), agent-ready ones printed as `owner/repo#N` outside this repo, sorted size then priority — the read an agent makes before claiming. The ready/blocked/in-triage counts come from the labels and the dependency edges, not from the board |
| `just issue-ready <n>` | runs the five rules, names the one that failed, adds `agent-ready` on an all-pass and removes it on a regression |
| `just issue-claim <n\|owner/repo#n>` | re-runs the rules, assigns the issue to you, drops the label, sets the board Status to In progress, prints the `stack start` line. Accepts a bare number for this repo or `owner/repo#N` for anywhere else on the org (MIP-0070 §5.7) |
| `just tasks-to-issues MIP-NNNN [--milestone NAME] [--deliverable NAME]` | files a MIP's task table (MIP-0070 §5.7): a parent issue in the umbrella titled `MIP-NNNN: <title>`, and one sub-issue per row in the repo its `delivers` cell names — or the umbrella when that repo does not exist yet or the row names none. Each row's `#` cell is rewritten into a link, one native `blocked by` edge is wired per `depends on` entry (same-repo or cross), and every issue goes onto Project 1. `--deliverable` sets the cross-repo `Deliverable` field (milestones are per repo); `--milestone` still works for issues filed in the umbrella. Idempotent, and a row already filed in the umbrella is never "moved" once its own repo appears |
| `just milestone-new "<name>" [--mip MIP-NNNN]` | creates a deliverable milestone; re-running with the same name changes nothing |
| `just labels-sync [--prune] [--force]` | reconciles the repo against the devkit's [`.github/labels.yml`](https://github.com/marola-dev/marola-devkit/blob/v0.2.3/.github/labels.yml), the versioned manifest; orphans are reported, and only deleted with `--prune` |
| `just board-sync` | puts every open issue on the board and sets its Status from the issue's own state — but only on a card carrying no Status, or `Backlog`, which is what the auto-add workflow writes rather than a state anyone chose. Any other Status is someone's decision and is left alone. Also moves a **closed** issue's card to **Done** whenever it isn't already: the fallback for GitHub's built-in "Item closed" workflow, which fired for one issue and missed the next eight on 2026-09-30 (MIP-0063 §4.4) |

The rest are run through the script. `issues board setup` (the Status options, the
views §5.2 names, and the `Deliverable` text field MIP-0070 §5.7 adds; needs `project` scope)
and `board gates` (the five phase gate issues) are one-time bootstraps, and reshaping a shared
board or filing issues is not something `just` should make easy. `sub add <parent> <child>`,
`deps add <issue> --blocked-by <n>` and `deps list <issue>` are the native edges themselves:
`tasks-to-issues` calls both `sub add`'s and `deps add`'s underlying mechanism for a whole task
table (a row's sub-issue link to its MIP's parent, and its `blocked by` edges), and all three are
called directly for a one-off edge.

`tasks-to-issues` sets no `area/*`, `layer/*` or `size/*`: which they are is a human's call, so a
freshly filed row is not `agent-ready` until someone labels it and `issue-ready` passes.

## Readiness and the board

The `agent-ready` label (Definition of Ready, above) and the board's `Status` field
(Triage / Spec / Ready / In progress / In review / Done, MIP-0063 §5.2) move together, driven by
the commands above. The mapping `board-sync` uses on a card's first contact with the board
(MIP-0063 §5.2): an assigned issue goes to In progress, an issue carrying `agent-ready` goes to
Ready, otherwise it goes to Triage.

```mermaid
stateDiagram-v2
  state "In progress" as InProgress
  state "In review" as InReview
  [*] --> Triage: board-sync (otherwise)
  [*] --> Ready: board-sync (agent-ready)
  [*] --> InProgress: board-sync (assigned)
  Triage --> Spec: someone's decision
  Spec --> Ready: someone's decision
  Ready --> InProgress: issue-claim
  InProgress --> InReview: someone's decision
  InProgress --> Done: issue closed (built-in workflow, or board-sync as fallback)
  InReview --> Done: issue closed (built-in workflow, or board-sync as fallback)
```

`board-sync` sets a new card's Status once, on first contact with the board, and never overwrites
it again — except Done, which it sets whenever an issue is closed and its card isn't there already.
From there, a move into Spec or into In review is "someone's decision"; a move into In progress is
`issue-claim`; and a move into Done is GitHub's built-in "Item closed" workflow first, with
`board-sync` as the fallback when that workflow doesn't fire — observed missing 8 of 9 closes on
2026-09-30 (MIP-0063 §4.4).

## What no command can do

- **Filing an issue is human-gated** (MIP §5.6, Decision 2). The `/marola-devkit:triage` skill drafts one and
  runs the readiness check; a person invokes it and a person files.
- **The board** — org project [Marola](https://github.com/orgs/marola-dev/projects/1) — **is
  private, and §5.2 wants it public.** There is no API for the switch.
- **The `Agent queue` view is not sorted by the script.** `ProjectV2View.sortByFields` is readable
  but not writable, so size-then-priority ordering is a UI action; `just issue-queue` sorts the
  same list from the terminal and needs no `project` scope.
