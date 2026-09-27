# marola — issue flow

`MIP-0063` puts the backlog into GitHub issues and milestones instead of files. This doc is the
short version; the design and its verification are `docs/mips/MIP-0063-github-issue-tracking-standard.md`.

**This is the intake half.** The command reference (claiming, the board, `issues.sh`'s
subcommands) lands with task 7 of `docs/mips/MIP-0063.tasks.md`.

## The object model

| Object | Means | Carried by |
|---|---|---|
| **Milestone** | one deliverable / use case, spanning many issues and possibly several MIPs | native milestone + its progress bar |
| **Issue** | a story or task — the claimable, PR-closable unit | labels, assignee, milestone |
| **Sub-issue** | a subtask, only when a story genuinely splits | native sub-issues |

Milestones are named descriptively, carry no sequence, and order between them is a board decision.
A milestone and a MIP are **n:m** — one deliverable can span several MIPs, one MIP files its tasks
into one milestone.

## The three tiers

Pick the tier, then the matching issue form:

- **Tier 1 — bug, chore, docs.** One issue, no milestone, no spec. Use **Bug report**
  (`bug_report.yml`) for something broken, or **Task** (`task.yml`) otherwise.
- **Tier 2 — small enhancement** (roughly two tasks or fewer, no new dependency). One issue
  **whose body is the spec**, sub-issues if it splits, no MIP file. Use **Story** (`story.yml`).
- **Tier 3 — initiative** (a new data source, a scoring change, a new integration, anything
  paid). MIP proposal issue → MIP PR → Accepted → milestone → N issues. Use **MIP proposal**
  (`mip_proposal.yml`); it is **relabelled**, not replaced, once the MIP PR opens, so the design
  discussion stays attached to it.

An issue becomes `agent-ready` only once it passes the five-rule Definition of Ready in §5.4 of
the MIP — acceptance criteria, a named test, `area/*` + `layer/*`, `size/*`, and no open
`blocked by` dependency. Task 7 adds the `AGENTS.md` rule that an agent must hold `agent-ready`
before starting work.
