# marola — the development flow, end to end

Idea → **MIP** (Draft) → **acceptance** → **task list** → **stacked PRs** (one per task, verified,
costed) → **review, when asked** → merge bottom-up, restack → **finish** (MIP → Implemented).
This page is the one place the whole loop is written down; the pieces live in the `mip` and
`mip-tasks` skills (`.claude/skills/`), `AGENTS.md` (the hard rules), `docs/AGENT-SKILLS.md`
(which superpowers skill does what) and the scripts under `scripts/`.

Sessions: one per MIP for planning, one per task for execution (`/clear`, `/rename
mip-nnnn/k-slug`), one per review. That is what makes `/usage` and `just claude-cost` map to PRs.

## 1. From an idea to a MIP (Draft)

1. **Refine the idea** — superpowers `brainstorming` (activates on "let's plan", "I have an idea"):
   Socratic questions until MIP §1-§3 (summary, motivation, user-visible change) have answers.
   Voice notes and chat pastes go through `just context-mips` + a browser session first
   (`RUN-LOCALLY.md` §8).
2. **Write the MIP** — the `mip` skill: next number from `docs/mips/README.md`, the template,
   every external claim fetched and dated, what was *not* checked said so, open questions listed.
   Add the index row. Link it from `FUTURE-WORK.md` / the exam mappings if it closes something.
3. **Open it as its own PR**, status **Draft**. A MIP is never built in the same change (`mip`
   skill, step 8). The PR body ends with a `Cost:` line like any other.

## 2. Acceptance

Acceptance is a human decision, recorded in the MIP's status table and the index. The reviewer
of a MIP PR reads, in this order:

- **§4 sources and dependencies** — was each claim verified (URL + date)? Anything unverified must
  sit in §11, not in §5.
- **§6 scoring / safety** — deterministic, in `scoring/`, unit-tested; never model output.
  **Phase** row against `AGENTS.md`'s phase discipline: a later-phase MIP is fine to accept, but
  the missing earlier-phase prerequisite must be named.
- **§8 caveats and §11 open questions** — answer what can be answered now; what cannot becomes a
  numbered decision in the tasks file (next section), so implementation is never blocked on it.
- **Cost** — the MIP's own cost model (§4/§8) and whether any paid resource is involved
  (`AGENTS.md`: a paid Azure resource needs an explicit go-ahead, in the MIP, before any task).

Outcome: **Accepted** (status row + `docs/mips/README.md`, in the MIP PR or a one-line follow-up
commit), **Rejected** (keep the file; the reasoning is the value) or **Superseded by MIP-NNNN**.
"Implement MIP-NNNN" from the human counts as acceptance; the first task's commit flips the
status to `Accepted` and links the tasks file.

## 3. The task list

`mip-tasks` step 1 (superpowers `writing-plans` is skipped — the MIP is the plan): read the MIP,
`AGENTS.md` and the code it touches, write `docs/mips/MIP-NNNN.tasks.md`:

- one row per task = one PR: ≤ ~400 changed lines, one concern, **its test named up front** (from
  MIP §7), green on `just build && just test && just quality` by itself;
- ordered by dependency, then risk; docs travel with the task that changes behaviour;
- a **Decisions** list answering the MIP's §11 for v1, so nothing is re-litigated per task;
- a restack note: no task rewrites a file an earlier task created.

The tasks file is committed on task 1's branch; the MIP gets a `Tasks:` row.

## 4. Stacked PRs — one task, one branch, one PR

Per task, in its own session (superpowers `executing-plans`: the task row is the plan; checkpoints
= first failing test, before push):

```bash
scripts/stack.sh start MIP-NNNN <k> <slug>        # branch mip-nnnn/k-slug off task k-1's branch (main for k = 1)
# red → green → refactor  (superpowers test-driven-development; systematic-debugging when green won't come)
just build && just test && just quality           # + a live check whenever a data path changed (superpowers verification-before-completion: evidence, then the claim)
git commit                                         # message ends with Tested: and Cost: trailers (AGENTS.md) — the PR's Tested/Cost sections come from them
scripts/stack.sh pr                                # push + PR with base = the previous task's branch
```

Every push — `scripts/stack.sh pr`, `just uprds`, a plain `git push` — goes through
`.githooks/pre-push`, which runs `just quality-other` (ruff on every `.py`, the script self-tests,
actionlint, hadolint) and, when a pushed commit touches Scala, `just quality-scala` (scalafmt +
scalafix). Code is linted before the last push, not found red in CI after the merge; `git push
--no-verify` skips the hook, CI does not.

Or, once every branch of the stack is pushed, all PRs at once with their shared "Stack" section:

```bash
just uprds MIP-NNNN         # regenerate every PR body (What changed + Cost + Stack), create the missing PRs on the right base
just stack-link MIP-NNNN    # link the open PRs into a GitHub Stack (gh stack link) — the "Preview stack" box in the PR UI, from the CLI
just stack-view             # the stack as GitHub sees it; just stack status MIP-NNNN for the local view
```

What GitHub shows: a stacked PR is a PR whose base is the previous branch; its page says "into
`mip-nnnn/1-…` from `mip-nnnn/2-…`" and its diff is that task alone. The Stack (native GitHub
feature, `gh stack`) adds the ordered list at the top of each PR; `just uprds` writes the same list
at the end of each body, with the summed Cost, for readers without the feature.

The generated body follows `.github/PULL_REQUEST_TEMPLATE.md`'s shape — bold labels, a compact
MIP/Tested/Cost table, no `#` headings, one screen for a typical two-commit PR; the PR title is
the first commit's subject on the branch, capped at 70 characters (`scripts/lib/uprd_title.sh`) so
it stays skimmable — `just uprd`/`just uprds` print a warning when a title had to be cut, worth a
manual retitle if the cut reads awkwardly.

Cost: `Cost:` is measured, not guessed. One session per task → `/usage` or `just claude-cost`.
One session for several tasks → `just cost-split MIP-NNNN` splits the session log by commit time
and prints the trailer per branch; amend with `GIT_COMMITTER_DATE` preserved so the split stays
stable, re-stack, force-push with lease, `just uprds`.

## 5. Final review — only when asked

Nothing reviews a PR automatically. Reviews start when the human says so ("review the stack",
"claude review #21", `/code-review`). Three ways, cheapest first; all of them review **one PR
against its own base**, bottom of the stack first, because that is the diff a reviewer sees.

1. **superpowers `requesting-code-review`** — in a fresh session, per PR: dispatch the reviewer
   subagent with `BASE_SHA = git rev-parse origin/<base branch>`, `HEAD_SHA = git rev-parse
   origin/<task branch>`, `PLAN_OR_REQUIREMENTS` = the task's row in `MIP-NNNN.tasks.md` plus the
   MIP's §6/§7, `DESCRIPTION` = the PR title. It returns Critical/Important/Minor findings; fix
   Critical and Important before merging, note Minor in the PR. This is the default for "final
   review using superpowers".
2. **`/code-review <PR#>`** (built-in; `--comment` posts the findings as inline PR comments) or the
   `code-review` plugin's `/code-review` (five parallel agents, ≥ 80-confidence findings only,
   one comment on the PR). Both look for `CLAUDE.md`; in this repo it imports `AGENTS.md` for
   Claude Code sessions and tells any tool reading it as plain text to open `AGENTS.md` — the
   plugin's confidence scorer only credits rules it can read, so keep that instruction there.
3. **`/code-review ultra <PR#>`** — the multi-agent cloud review, for the riskiest PR of a stack
   (scoring, safety text, a new data source). User-triggered and billed; never launched by the agent.

Author side: superpowers `receiving-code-review` — verify each finding before implementing it,
push back with reasoning when it is wrong, then fix → commit (`Cost:` trailer) → push → `just
uprds MIP-NNNN`. Pre-review checklist (superpowers `requesting-code-review` + this repo): Cost
line present and measured; docs updated in the same PR; the test named in the tasks row exists and
is green; MIP status right; `docs/FABLE_REVIEW.md` item closed if one applies.

## 6. Merge, restack, finish

- Approve per PR (GitHub reviews are per PR), then merge either one at a time or the whole stack
  at once: `just stack-merge <stack#> --squash` merges every PR of the stack bottom-up in one
  all-or-nothing operation (`gh stack merge`), no restack in between.
- One at a time: **bottom-up**, squash (the repo's habit). GitHub retargets the next PR to `main`
  when the merged branch is deleted; the commits still need a rebase:
  `scripts/stack.sh restack` on the next branch, or `just stack-sync MIP-NNNN` for the whole
  stack (it adopts the stack from GitHub first — `gh stack link` keeps no local state).
- `scripts/stack.sh status` / `just stack-view` until every PR is merged.
- Last merge: superpowers `finishing-a-development-branch` — full suite green, delete the task
  branches, flip the MIP to **Implemented** with the PR numbers and the summed Cost in its status
  row, update `docs/mips/README.md`. A follow-up after a merge is a new branch off `main`, never a
  child of the old one.

### Dependency PRs

dependabot (`.github/dependabot.yml`) and scala-steward (`.github/workflows/scala-steward.yml`)
each open their own one-off PR per bump. Left alone, ten open bumps cost ten separate CI runs to
land. `just deps-stack` chains the open **dependabot** PRs (`--include-steward` adds
scala-steward's, once its author identity on this repo is confirmed — see
`scripts/deps-stack.sh`'s header) into one `deps/<date>/k-slug` stack, github-actions PRs first
then pip, same shape as a MIP's task branches: run it weekly, or right before a release, rather
than merging bumps one at a time. A PR's head branch can't be moved after it's opened, so the
default (and only implemented) path opens one *new* PR per chain branch, stacked on the previous,
and closes each original dependabot PR with a pointer comment — dependabot's own branches are
never touched, so an abandoned stack doesn't stop dependabot from re-opening or updating them
normally. Two Actions bumps touching the same workflow line is the usual conflict: the script
stops with the branch left mid-cherry-pick and prints the exact `git status` / resolve / `git
cherry-pick --continue` / `just deps-stack --resume` steps. Once the chain is up, it's a normal
stack: `gh stack link` runs automatically, `just stack-merge <stack#> --squash` merges it
bottom-up in one CI run instead of one-per-bump, and `just deps-stack clean` deletes the chain
branches once every stacked PR shows MERGED.

## 7. Command reference

| Step | Command |
|---|---|
| Pack docs for a browser MIP session | `just context-mips` |
| New task branch | `scripts/stack.sh start MIP-NNNN k slug` |
| Gates | `just build && just test && just quality` (`quality` = `quality-scala` + `quality-other`; `just quality-fix` for the auto-fixable part) |
| Before every push | `.githooks/pre-push` runs `just quality-other`, plus `just quality-scala` when Scala changed — automatic, `--no-verify` to bypass |
| Statement coverage (aggregated core/local/azure/cli) | `just coverage`; published to the README badge by ci.yml on pushes to `main` |
| Live checks | `just run -- --brief`, `just e2e`; once MIP-0005 lands, `just site-build floripa && just site-serve` |
| One PR | `scripts/stack.sh pr` (`--dry-run` prints the gh commands) |
| gh inside the jail | `GH_TOKEN` in `.env` (fine-grained, this repo, PRs read/write) — `just jail-claude` passes it through, so the agent runs `just uprd` itself |
| Every PR of a stack | `just uprds MIP-NNNN` |
| PR body shape / title length | `.github/PULL_REQUEST_TEMPLATE.md`; title capped at 70 chars, cut point printed as a warning |
| Tested row | `Tested: gates, e2e, live, ci-only — <not run, why>` trailer per commit; `just uprd` sets the ✅/⬜ glyphs, never guesses |
| GitHub Stack | `just stack-setup` once, then `just stack-link MIP-NNNN`, `just stack-view`, `just stack-sync MIP-NNNN` |
| Local stack view | `scripts/stack.sh status [MIP-NNNN]`, `just stack status MIP-NNNN` |
| After a base merged | `scripts/stack.sh restack` (one branch) or `just stack-sync MIP-NNNN` (whole stack) |
| Merge the whole stack | `just stack-merge <stack#> --squash` (all-or-nothing, bottom-up) |
| Delete merged branches | `just branches-clean` (local + remote ref, skips current branch/main) |
| PR for a stray plain branch | `just branches-open` (base=main; stack branches point at `scripts/stack.sh pr`) |
| Stack the open dependency PRs | `just deps-stack` (`--dry-run`, `--resume`, `--skip <PR#>`, `--include-steward`); `just deps-stack status` / `just deps-stack clean` |
| Cost per PR | `just cost-split MIP-NNNN [--session <id>]`, `just claude-cost` |
| Review (on request) | superpowers `requesting-code-review`; `/code-review <PR#> [--comment]`; `/code-review ultra <PR#>` |
| Status line | `.claude/statusline.sh`, shared via `.claude/settings.json` |
| Push text to the clipboard (write-only) | `just clip` — needs `MAROLA_JAIL_CLIPBOARD=1 just jail-claude` inside the jail, works directly outside it |
