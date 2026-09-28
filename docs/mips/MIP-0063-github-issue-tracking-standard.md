# MIP-0063: A GitHub-native tracking standard — issues as the unit of work, milestones as deliverables

| | |
|---|---|
| **Status** | Partially implemented (tasks 1–4 of 8 — #423 #429 #430 #435) — `Tasks: docs/mips/MIP-0063.tasks.md` |
| **Author** | Claude (Opus 5), for B. Valério |
| **Created** | 2026-09-27 |
| **Phase** | 0 (dev-loop; no user-facing surface) |
| **Related** | `DEV-FLOW.md` §1–§3 (this MIP inserts an issue step ahead of the MIP step, and files each task row as an issue), `CONTRIBUTING.md`, `AGENTS.md` (phase discipline, the docs table), the `mip-tasks` skill, `brunogbv/cv` (prior art: GitHub spec-kit, `speckit-taskstoissues`) |
| **Effort** | M — one script family (`scripts/issues.sh`, `scripts/lib/tasks_issues.py`), four issue forms, a `labels.yml` manifest, one new skill and one extended skill, four doc edits. No Scala, no new module, no CI workflow (§11.1 defers those) |
| **Gain** | `infra/dev-loop` (the backlog stops being 58 Draft design docs and a prose ROADMAP, and becomes a queue an agent can query in one call); `infra/dev-loop` again on the intake side (a contributor, human or agent, has one standard way to propose work instead of four half-documented ones) |
| **Effort vs Gain** | `do next` — every later harness layer the maintainer wants (validation gates, graphify, autonomous triage) needs a machine-readable work queue to operate on, and there isn't one today |
| **Depends on** | No other MIP. Not gated by Phase 1: this is dev-loop work, which `ARCHITECTURE.md` §11 puts outside the phase sequence. Creates no paid resource, so `AGENTS.md`'s cost gate does not apply. Two actions only the maintainer can take: `gh auth refresh -s project` on the host (§4.4) and enabling Discussions (§4.6) |
| **Blocked by** | none |
| **Risk** | Taxonomy nobody maintains. Filing ~6 issues per accepted MIP turns a 58-MIP backlog into several hundred issues; if triage lapses, `agent-ready` rots and the queue becomes actively misleading — worse than the prose ROADMAP it replaced, because it looks authoritative |
| **Cost so far** | — |

## 1. Summary

marola already does spec-driven development: a MIP is the spec, `MIP-NNNN.tasks.md` is the plan,
a stacked PR is the increment. All three live in files, so the backlog is invisible in GitHub's UI
and unqueryable by anything that isn't already inside the repo. This MIP puts the *work* into
GitHub — issues as stories and tasks, milestones as deliverables, one public project board,
native dependency edges so "ready" is computed rather than asserted — and defines a five-rule
**Definition of Ready** that gates what an agent is allowed to pick up. The spec layer does not
move: MIPs stay files, and `tasks.md` stays the authored plan.

## 2. Motivation

Three concrete gaps, all visible in the repo today.

**The backlog is not addressable.** 58 MIPs (42 Draft, 3 Accepted, 12 Implemented), six open
issues (#398–#412) none of which link to a MIP, zero milestones, zero projects. "What next" is
answered by reading `docs/ROADMAP.md`, a prose document dated 2026-09-06 whose §2a tracks six bugs
in a markdown table with a **State** column — a hand-maintained issue tracker inside a file, in a
repo whose issue tracker is empty.

**There is no intake standard.** `feature_request.yml` tells a contributor that non-trivial work
"goes through a MIP first" and stops there: nothing says what happens to the issue, who decides, or
what the bar is. For a public project that is a dead end at the front door.

**Nothing an agent can query.** `DEV-FLOW.md` §7's unattended runner works through a
`MIP-NNNN.tasks.md` because that is the only machine-readable queue there is. It cannot ask what
is ready and unclaimed, cannot avoid colliding with another session, and cannot be handed work
that did not come from a MIP. Every later harness layer — validation gates, dependency graphs,
autonomous triage — needs that queue to exist first.

## 3. User-visible change

The "user" here is a contributor, human or agent.

**Before**, a stranger who wants to help reads `CONTRIBUTING.md` → `AGENTS.md` (180 lines of hard
rules) → `DEV-FLOW.md` (idea → MIP → acceptance → tasks → stacked PRs), concludes that
contributing requires writing a design doc, and leaves. **After**, they open the board's "Good
first issues" view, pick one whose body already carries acceptance criteria and a named test, run
`just issue-claim 431`, and go red → green → `just pr`.

For an agent, the change is one queryable call replacing a file read:

```console
$ just issue-queue
#431  size/S  area/map-site   layer/site   Add hreflang tags to the generated board pages
#428  size/S  area/safety     layer/core   SafetyFooter drops the source line when notes is empty
#433  size/M  area/conditions layer/core   Cache Open-Meteo responses for the 3h board build
      3 ready · 1 blocked · 2 in triage   (milestone: Water quality on the map)
```

The footer's three counts partition the unassigned open issues, derived from the labels and the
dependency edges of §4.2 — not read from the board's `Status`, which needs the `project` scope
§4.4 leaves to a human.

And the Definition of Ready is enforced, not documented:

```console
$ just issue-ready 435
✗ #435 is not agent-ready
  ✓ 1. acceptance criteria present
  ✗ 2. named test — no "### Named test" section in the body
  ✓ 3. area/* and layer/* labels set
  ✗ 4. size/* label missing
  ✓ 5. no open blocked-by dependency
  label `agent-ready` not added
```

## 4. Data sources and dependencies reviewed

Every subsection below is a GitHub platform feature this design leans on. No third-party service,
no new runtime dependency, nothing paid.

### 4.1 Sub-issues (REST) — **adopted**

Native parent/child issues with a rollup progress field. Verified live on this repo:
`GET /repos/h0ffmann/marola/issues/412/sub_issues` returns `[]` (live, not 404), and the payload
carries `sub_issues_summary: {completed, percent_completed, total}`. Documented limits — **100
sub-issues per parent, 8 levels** — are comfortably above anything a MIP task list produces.

**The trap:** `POST /issues/{n}/sub_issues` takes `sub_issue_id`, documented verbatim as *"The id
of the sub-issue to add"* — the **database id**, not the issue number. The POST itself is executed
as of 2026-09-27 (Appendix) and ids on this repo are ten digits against three-digit issue numbers,
so a number passed here addresses an issue in some unrelated repository: refused or attached to
the wrong thing, with nothing in the response to say which. Gets a comment in the script
(`AGENTS.md`'s "a trap" category) and a self-test.

### 4.2 Issue dependencies — **adopted, and it replaces the `blocked` label**

Native blocked-by / blocking edges between issues, generally available and included on GitHub
Free. Verified live: `GET /repos/h0ffmann/marola/issues/412/dependencies/blocked_by` → `[]` (live,
not 404), and this host's `gh` 2.95.0 carries `gh issue create --blocked-by/--blocking` plus
`gh issue edit --add-blocked-by/--add-blocking/--remove-*`. Up to 50 issues per relationship type.
A blocked issue shows a **Blocked** marker on the board and the issues list with no label set.

**The same trap, a second time:** `POST .../dependencies/blocked_by` takes `issue_id` — the
database id again, not the issue number. `scripts/issues.sh` therefore resolves numbers to ids in
exactly one place, used by both this and §4.1's sub-issue call.

Neither the feature docs nor the REST reference places any restriction on *which* issues may carry
dependencies — nothing about hierarchy, parents or sub-issues — and the dependency response schema
itself carries `parent_issue_url` and `sub_issues_summary`, which is what one would expect if a
sub-issue is simply an issue. §5.3 relies on that; §7 step 7 proves it.

Consequence: §5.2 drops the `blocked` label, and §5.4's readiness rule is checked against the API
instead of against a label somebody has to remember to remove.

### 4.3 Issue types — **rejected, unavailable**

GitHub's typed issues (task / bug / feature, up to 25) would carry the story-vs-task distinction
better than labels, but they are an **organization** feature. Verified empirically:
`GET /orgs/h0ffmann/issue-types` → 404 (`h0ffmann` is a user account), and no
`/users/{user}/issue-types` endpoint exists. `issue.type` is present in the payload but reads
`null` and cannot be set here. Labels carry the roles instead. Revisit if marola moves to an org.

### 4.4 Projects v2 and token scopes — **adopted, with a human prerequisite**

`gh project list --owner h0ffmann` → `{"projects":[],"totalCount":0}`; the repo has
`has_projects: true`. Scopes verified against GitHub's OAuth table: **`project`** = *"read/write
access to user and organization projects"*, **`read:project`** = *"read only access"*. The host
token carries `read:project` only, so `just board-sync` fails until the maintainer runs
`gh auth refresh -s project` — a human action by design, since an agent should not widen its own
token's scope. Projects' **built-in workflows** (auto-add, closed → Done) are configured in the
project UI; this MIP uses them rather than reimplementing them as Actions.

### 4.5 Issue forms → parseable bodies — **adopted, verified on real data**

GitHub renders each form field into the body as a `### <Label>` heading plus the value. Verified
against this repo rather than the docs: issue **#412**'s body begins `### Problem` /
`### Proposed behaviour`, matching its form's field labels exactly. This is what makes the
Definition of Ready checkable by `grep` rather than by a model, and why §5.6 fixes the heading
spellings across all four forms.

### 4.6 Discussions — **adopted, off today**

`has_discussions: false`. Enabling it is a repository setting (maintainer action). Narrow purpose:
keep "how do I…" out of a tracker meant to be a work queue.

### 4.7 Prior art — `brunogbv/cv` and GitHub spec-kit — **borrowed, not vendored**

`brunogbv/cv` runs GitHub spec-kit (`.specify/` plus nine `speckit-*` skills). Its
`speckit-taskstoissues` is the closest existing thing to §5.4, and three of its decisions are
adopted directly: a **feature-scoped task id in the title** (`003-T001: …`) so per-feature ids
restarting at `T001` do not collide during dedup; **rewriting the row into a link** to its issue
rather than keeping two states; and declaring that afterwards issues own status while the file owns
the plan. **What does not transfer is the shape:** spec-kit's `tasks.md` is a checkbox list, so its
rewrite targets `- [ ] T001 …`. marola's twelve task files are all markdown *tables* with an
explicit `depends on` column, which is both a different rewrite target and a better dependency
source. An earlier draft of this MIP copied the checkbox mechanism unchecked; the 2026-09-27 run
caught it. Its `harness` label is not adopted — `area/dev-tooling` covers it.

Read 2026-09-27: the skill source, the label set, and 15 issues showing `003-T0NN:` titles in
practice. **Not adopted:** spec-kit itself. marola's MIP + `tasks.md` + `mip-tasks` pipeline holds
that role, and replacing it would invalidate five skills and `DEV-FLOW.md` for no gain.

## 5. Design

### 5.1 The object model

| Object | Means | Carried by |
|---|---|---|
| **Milestone** | one deliverable / use case, spanning many issues and possibly several MIPs | native milestone + its progress bar |
| **Issue** | a story or task — the claimable, PR-closable unit | labels, assignee, milestone |
| **Sub-issue** | a subtask, only when a story genuinely splits | native sub-issues (§4.1) |

Milestone names are descriptive and carry **no sequence** — `Telegram bot answers a real shared
location`, `Water quality on the map`, `Trilingual marola — pt-BR, English, Japanese`. Those three
are illustrations of the shape, not a proposal; the real ones are named by the maintainer when the
first deliverables are cut. Ordering between milestones is a board decision, never a property of
the milestone, because priorities change as the repo learns what must come first.

Milestone and MIP are **n:m**: a deliverable is a use case, a MIP is a design. `Water quality on
the map` spans MIP-0016 and MIP-0031; one MIP files its tasks into one milestone.

Three intake tiers (`AGENTS.md`'s MIP trigger list is unchanged; this names the two tiers below it):

- **Tier 1** — bug, chore, docs → one issue, no milestone, no spec.
- **Tier 2** — small enhancement (≤ ~2 tasks, no new dependency) → one issue **whose body is the
  spec**, sub-issues if it splits. No MIP file.
- **Tier 3** — initiative (new data source, scoring change, new integration, anything paid) → MIP
  proposal issue → MIP PR → Accepted → milestone → N issues. The proposal issue is **relabelled**,
  not replaced, so its design discussion stays attached.

### 5.2 Labels, milestones, the board

Four new labels: `agent-ready`, `size/S`, `size/M`, `size/L`. Existing `area/*` (12), `layer/*`
(8), `kind/*` and `priority/high` are untouched. **`layer/azure` is orphaned** — 993b469 removed
Azure — and is deleted here. **`mip` was named here as existing and is not** (`GET /labels/mip` →
404, 2026-09-28): `mip_proposal.yml` applies it and §5.4 dispatches on it, so `.github/labels.yml`
defines it and `just labels-sync` creates it.

Two labels an earlier draft of this design carried are gone: `blocked`, because §4.2's native
dependencies express it better and without hand-maintenance, and `epic`, because §5.1 makes the
*milestone* the epic and leaves no issue role for it to mark.

Size carries a rule, not a feeling: **S < 100 changed lines, M 100–400, L means split it.**
`DEV-FLOW.md` §3 caps a task at ~400 lines, so `size/L` is only ever legal on a parent issue.

`.github/labels.yml` becomes the versioned manifest — every label with colour and description in
one reviewable file rather than state that exists only in a settings UI. `just labels-sync`
reconciles and reports orphans; it never deletes without `--prune`.

The board is one **org-level** project, `Marola`
([`marola-dev/projects/1`](https://github.com/orgs/marola-dev/projects/1)), with **one custom
field** (`Status`: Triage / Spec / Ready / In progress / In review / Done) and four views:
**Triage** (`Status:Triage`, the public inbox and the one view a maintainer reads daily), **Now**
(by Status, filtered to the milestone currently being pushed), **Agent queue**
(`label:agent-ready`, sorted size then `priority/high`) and **Good first issues**.

An earlier draft said *user-level*, written before the repo moved from `h0ffmann` to the
`marola-dev` org. The board was read on 2026-09-28, and the GraphQL schema introspected with it;
what this section assumed differs from what is there in four ways, and `issues.sh board setup`
reconciles the first three:

- It **already exists**, with 21 items and GitHub's six template views (*Current iteration, Next
  iteration, Prioritized backlog, Roadmap, In review, My items*). So this section's four views are
  **added** to those six, never in place of them; nothing here deletes or renames a view.
- Its `Status` already has **Ready, In progress, In review and Done** — only **Triage** and
  **Spec** are missing, and it also carries **Backlog**, which this section does not name.
  `updateProjectV2Field` takes the *complete* option list, so the two are appended to what is
  there and `Backlog` stays: dropping it from the list would delete it and the Status of every
  item holding it.
- **The view mutations exist and are current**: `createProjectV2View`, `updateProjectV2View` and
  `deleteProjectV2View`, none deprecated. Two details shape the code. `CreateProjectV2ViewInput`
  carries only `projectId`, `name`, `layout` and `configuration` — **no `filter`** — so a view is
  created and then filtered by a second `updateProjectV2View`, which does take one. And **neither
  input type carries a sort**: `ProjectV2View.sortByFields` is readable but not writable, so this
  section's "Agent queue, sorted size then `priority/high`" is the one part of the view spec the
  API cannot express, and the ordering stays a UI action. So is **Now**'s milestone filter, for a
  different reason: which milestone is "currently being pushed" is not something a script knows.
- It is **private**; this section says public. Making it public is a UI action.

None of the three mutations above has been *called*. The host token has `read:project`, not
`project`, so every write fails on scope before it reaches the API and nothing here says whether
a PAT may perform them — only that the schema offers them. `board setup` is written against them
and refuses with the `gh auth refresh -s project` line until the scope lands; `--dry-run` prints
the plan regardless, since the reads it is computed from need only `read:project`.

**One piece of state is deliberately duplicated:** `agent-ready` is a label *and* Ready is a Status
value. The reason is `AGENTS.md`'s jail rule — a session inside ai-jail has no `gh` login of its
own and a narrow `GH_TOKEN`; `gh issue list --label agent-ready` is one REST call on `repo` scope,
while a Project field needs `project` scope and GraphQL. The `just` commands own keeping the two in
step; nothing else may write either.

**Which command writes which half, and which one wins.** The label is the half every command can
write; the Status half needs a scope a human grants (§4.4), so until they do, only the label
moves — and the board must be able to catch up afterwards without undoing anything.

| Command | Label | Status |
|---|---|---|
| `issues.sh ready` | adds on an all-pass, removes on a regression | — (it has no board to write, by design: it must work from a jail) |
| `issues.sh claim` | removes | sets **In progress** |
| `issues.sh board sync` | — | sets from the issue's own state (assigned → In progress, `agent-ready` → Ready, otherwise Triage) on an item with **no Status**, or with **`Backlog`** and only `Backlog` |

So **the label is authoritative and the board follows it**, once, when an issue first reaches the
board. After that the Status belongs to whoever moves the card: `sync` never overwrites a Status a
maintainer set by hand, because deriving *In review* or *Spec* from an issue's state is not
possible and guessing it back to Triage every run would undo their work. The consequence is that a
label and a Status can still disagree afterwards — that is the soft spot §8 names and the
reconciliation job §11.1 defers, and it is now stated rather than implied.

**Why `Backlog` is the one exception**, and it is not an arbitrary one: it is not a state anybody
chose. The project's built-in auto-add workflow (§4.4) writes it on every issue the moment it
reaches the board, so on this board it means *nobody has looked at this yet* — the same thing an
absent Status means. Treating it as unset is what makes an issue's first contact with the board
mean anything; without it every card the workflow touched would be frozen at `Backlog` forever and
`sync` would be inert by construction. The exception is **exactly that one literal value**. It is
not "any Status §5.2 does not name": `Ready`, `In progress`, `In review`, `Done`, `Triage` and
`Spec` are all somebody's decision and none of them is ever overwritten. And it is a *once*: after
one sync an adopted card carries a real Status, so the next run adopts nothing.

Two things follow that the command says out loud rather than leaving to be noticed. It **names
every card it moves** (`#421  Backlog → In progress`), because the first real run moves the whole
board at once. And it **runs after `board setup`, not before**: until the Status field has the
options §5.2 names, an issue whose state calls for `Triage` cannot be given one, so `sync` counts
those as *skipped* rather than failed, says so once instead of once per issue, and exits nonzero
naming the command to run first.

### 5.3 Dependencies

Dependency management exists so that whoever picks work up — a contributor, or an agent running
unattended — can tell what is ready without reading anything else. §4.2's native edges make that a
computed property, and they cost nothing to apply, so they are used at **every** level:

- **Between issues** — what makes "ready" computable rather than a guess, and what the board's
  Blocked marker reads.
- **Between sub-issues of the same parent** — the same mechanism, for the same reason. An earlier
  draft of this design left these untracked, on the reasoning that one owner drives a story and
  can hold the order in their head. That reasoning only holds while recording the order is
  expensive. It isn't: GitHub gives the edge away, so the ordering stops being something the owner
  has to remember and becomes something the tracker answers. **Proven live on 2026-09-27**
  (§7 step 7, Appendix): GitHub accepts the edge between two sub-issues of the same parent and
  reads it back from both ends.

Because the edges are free, **nothing asks a human to draw them by hand**: `tasks-to-issues`
derives each edge from the **`depends on` column** that `MIP-NNNN.tasks.md` already carries. That
column is a DAG, not a chain — MIP-0034 has `6 ← 1`, MIP-0031 has three roots and two parallel
branches, MIP-0056 has `7 ← 4`, and MIP-0011's task 11 says in words that it "can be built any time
relative to 1-10". A dependency graph marola has been writing by hand for twelve task lists becomes
machine-readable at no authoring cost, and it is a *truer* graph than the branch stack, which only
ever expresses merge order.

**Phase discipline falls out of the same mechanism.** Each of `ARCHITECTURE.md` §11's phases gets
one tracking issue ("Phase 1 — Telegram bot working"), and every `phase/2` issue is `blocked by`
it. Closing that one issue unblocks the whole phase at once, visibly and without editing anything
else. `phase/1..4` stay pure facet labels with no ordering power, and `AGENTS.md`'s hard rule
stops being a paragraph people skim. Five gate issues total; they hold no work, only the edge.

### 5.4 The Definition of Ready

The DoR governs **claimable** work, and it reads rules 1 and 2 from the headings the issue's own
form renders (§5.6). An issue is `agent-ready` when all five hold:

1. **Acceptance criteria** present, as testable checkboxes (`### Acceptance criteria`).
2. **A named test** — file plus test name (`### Named test`). This is MIP §7's discipline pulled
   forward onto the issue, so the test is decided before the work is claimed, not after.
3. `area/*` **and** `layer/*` set.
4. `size/*` set.
5. **No open `blocked by` dependency**, read from the API (§4.2) — not from a label.

A section counts as present only when it has **content**: GitHub renders an optional form field
that was left blank as its heading followed by `_No response_`, so "the heading is there" and "the
author answered" are different questions.

Two of §5.6's four forms carry neither heading, and they are not the same case:

- **`mip` (tier 3) is not claimable.** A MIP proposal is a design request, relabelled once the MIP
  PR opens (§5.1), so it can never be `agent-ready` and its lack of the two headings is correct.
  `issues.sh ready` says exactly that in one line rather than reporting rule failures whose
  obvious remedy — adding acceptance criteria to a proposal — is the wrong thing to do.
- **`bug` (tier 1) is claimable**, and refusing every bug forever would be a real gap.
  `bug_report.yml` asks for the same two things under the names that fit a bug, so for a `bug`
  rule 1 reads `### What you expected instead` (required by the form; a bug's statement of done)
  and rule 2 reads `### Failing test` (§5.6). A mapping, not a waiver: **Failing test** is an
  optional field, so a bug filed without one still does not pass, which is what makes `AGENTS.md`'s
  "reproduce a bug with a failing test before fixing it" checkable at claim time rather than at
  review time.

`task.yml` and `story.yml` — the two forms that spell both headings — are unaffected.

**`AGENTS.md` gains one hard rule:** an agent may only begin implementation on an issue carrying
`agent-ready`. Everything else is a human's problem first.

### 5.5 The command surface

Six recipes over one `scripts/issues.sh`, each with the repo's usual `--dry-run`:

| Command | Does |
|---|---|
| `just tasks-to-issues MIP-NNNN --milestone "<name>"` | creates missing issues titled `0063-T3: …`, dedups on `\b0063-T\d+\b`, rewrites each row's `#` cell into a link to its issue, wires each `blocked by` edge from the `depends on` column (§5.3); idempotent |
| `just issue-queue` | `agent-ready`, unassigned, sorted size then priority — the read an agent makes; the ready/blocked/in-triage counts under it are derived from labels and dependency edges, not from the board's `Status` |
| `just issue-ready <n>` | runs the five rules, adds/removes `agent-ready`, prints which rule failed |
| `just issue-claim <n>` | assigns, drops `agent-ready`, sets board Status, prints the `scripts/stack.sh start` line |
| `just milestone-new "<name>" [--mip MIP-NNNN]` | thin, but keeps deliverables discoverable from `just` |
| `just board-sync` | adds un-added open issues to the project, sets Status from state |

Two commands have no recipe on purpose, both one-time bootstraps rather than anything in the loop:
`scripts/issues.sh board setup` brings the project up to §5.2 (the Status options it lacks, the
four views), and `board gates` files §5.3's five phase gate issues. Filing issues is human-gated
in this repo (§5.6, Decision 2), and reshaping a shared board is the same kind of act, so both stay
something a person runs with `--dry-run` first rather than something `just` offers.

Direction of truth: **`mip-tasks` authors `tasks.md`; `tasks-to-issues` projects it into GitHub;
after that, issues own status and the file owns the plan.** One-way, re-runnable. An agent in
ai-jail with no token can still read the whole plan from disk — which is why the file is not
replaced by the issues.

### 5.6 Forms and skills

Four forms, blank issues still disabled. Each field label renders as a literal `### <Label>`
heading, and §5.4's checks are a `grep` for the spelling **that form** renders — `task.yml` and
`story.yml` share `### Acceptance criteria` / `### Named test`, a `bug` is read on the two below,
and a `mip` proposal carries neither because it is not claimable work:

| Form | Tier | Change |
|---|---|---|
| `bug_report.yml` | 1 | gains **Failing test** — the repo already requires reproducing a bug with a failing test, so the form asks |
| `task.yml` *(new)* | 1 | chore/refactor/docs: what, acceptance criteria, named test, size |
| `story.yml` *(replaces `feature_request.yml`)* | 2 | Problem · Proposed behaviour · Acceptance criteria · Named test · Out of scope · Deliverable |
| `mip_proposal.yml` | 3 | kept; now states what happens next (MIP PR → milestone → `tasks-to-issues`) |

`config.yml` points questions at Discussions (§4.6).

Skills: **`mip-tasks` gains a final step** that calls `tasks-to-issues` — no new skill, since it
already authors the file. **New `triage` skill**: takes a raw idea, voice-note fragment or bug
report, picks the tier, drafts the body in the matching form's heading shape, runs the DoR check,
files it.

**`triage` stays human-invoked — decided, not deferred.** No label, no webhook and no schedule may
start it. `AGENTS.md` requires a human-confirmation gate for proactive agent behaviour unless a MIP
decides otherwise, and this MIP declines to lift it: an agent that files its own issues, against a
standard whose whole purpose is to say what is real and ready, would pollute the queue faster than
anyone can triage it.

## 6. Scoring / safety impact

None. No Scala changes, no `scoring/` changes, no change to any user-facing output. Nothing in
this MIP goes near `Swimability` or the safety footer.

## 7. Verification plan

In the order that proves the most for the least:

1. `scripts/lib/tasks_issues.py --self-test` — the dedup regex (`\b0063-T\d+\b` matches
   `0063-T001`, `[0063-T1]`, `0063-T1:`; does **not** match `S0063-T1` or `0063-T0010`), the
   table-row parser, the `#`-cell→link rewrite including idempotence on an already-linked row, and
   the `depends on` column parser against a non-linear graph (`–`, `—`, `1`, `5, 6`). No network,
   no GitHub. Runs under `just quality-other`, so `.githooks/pre-push` exercises it every push.
2. `scripts/issues.sh --self-test` — argument handling and each of the five DoR rules against
   fixture bodies, including rule 5 reading a stubbed `blocked_by` list rather than a label.
3. `just labels-sync --dry-run` — must print exactly the four additions and flag `layer/azure` as
   orphaned. Nothing else moves.
4. `just tasks-to-issues MIP-0060 --dry-run` against the real existing `MIP-0060.tasks.md` —
   inspect titles and rewritten lines before anything is created.
5. **Live idempotence check**: re-run against this MIP's own task list, whose issues were filed by
   hand on 2026-09-27. The second run must create zero issues and rewrite zero rows — the strongest
   available fixture, since the data is real and already linked. This is also where §4.1's `sub_issue_id` trap is
   confirmed against the live API or corrected.
6. **The first real use, on real debt**: file `docs/ROADMAP.md` §2a's five still-open findings
   (`stop-gate.sh` untracked files, `stop-gate.sh` read-only exit code, `format.sh` offline
   hard-fail, `mip-reviewer`'s unlisted `git rev-parse`, the §7→§8 pointer sweep) as Tier-1 issues
   through `triage`, and the `cost-fill` git-version bug found while opening this MIP's own PR.
   Six real issues exercise the forms, the labels, the DoR check and the board in one pass —
   better evidence than a synthetic fixture, and it clears a backlog that has been sitting in a
   markdown table since 2026-09-06.
7. **The one assumption §5.3 rests on**, proven in the same pass: give one of those six a parent
   and a sibling sub-issue, add a `blocked by` edge between the two siblings, and read it back. If
   GitHub refuses a dependency between sub-issues, §5.3 falls back to the earlier position —
   edges between issues only, sub-issue ordering left to whoever owns the story — and nothing else
   in the design moves.

Done looks like: the four forms in place, `labels.yml` synced, the board created with its four
views, the five phase gate issues open with `phase/*` work blocked by them, `just issue-queue`
returning a real list, and `docs/ISSUE-FLOW.md` describing exactly what the scripts do.

## 8. Risks, limitations, and honest caveats

- **Taxonomy rot** (the metadata `Risk`). Mitigation is scope: **the board starts empty.** v1
  files issues only for MIPs accepted from here on, never retroactively for the 42 Draft MIPs.
  Consolidating the existing backlog is a deliberate later step, taken once this standard has been
  validated in use and not before — filing several hundred issues against an unvalidated taxonomy
  is precisely how it rots.
- **The duplicated `agent-ready`/Status pair** (§5.2) is the design's one soft spot. If anything
  other than `issues.sh` writes either, they drift. A CI reconciliation job is the fix, deferred
  to §11.1.
- **Two manual steps block parts of v1**: `gh auth refresh -s project` and enabling Discussions.
  Until the first is done, `just board-sync` fails; everything else works on `repo` scope.
- **Nothing here enforces itself yet.** With CI gates out of v1, a PR can still merge without
  linking an issue, and an issue can be labelled `agent-ready` by hand without passing the check.
  v1 makes the standard *checkable*; it does not make it *unavoidable*.
- **Public means public.** A project board and an open triage queue expose what is not being done
  as clearly as what is. That is the point, and it is worth stating before switching it on.
- **The edges are only as good as the `depends on` column.** §5.3 reads that column rather than
  inferring a chain, so parallelism survives — but a task list that overstates a dependency will
  serialise work that could have run at once, and one that understates it will show an issue as
  ready when it is not. Nothing checks the column against reality; `mip-tasks` authors it by hand
  and review is the only gate. That is the same trust the stacked-PR workflow already places in
  it, now with more consequence attached.
- **50 edges per relationship type** (§4.2). Far above a MIP's task count, but a milestone that
  accumulated dependencies across many MIPs could reach it. Nothing in v1 checks for the ceiling.

## 9. Alternatives considered

- **Do nothing.** ROADMAP prose and `tasks.md` keep working for one maintainer with one agent, and
  fail the moment the audience is public or the agents are concurrent. Blocks every later harness
  layer.
- **Adopt spec-kit wholesale**, as `brunogbv/cv` does — `.specify/`, nine skills, `taskstoissues`
  for free. marola's MIP pipeline already holds that role and five skills are built on it, so the
  migration is pure churn. Ideas borrowed instead (§4.7).
- **Milestones as phases** (this design's first draft). Rejected by the maintainer during
  brainstorming, correctly: it bakes sequence into milestone identity, so re-prioritising means
  renaming milestones. Phases became facet labels; deliverables became milestones.
- **Epic issues as a separate object**, milestones reserved for releases. The milestone *is* the
  epic; a parallel epic layer means two progress bars to keep honest.
- **Status as a label family** (`status/triage`, …) instead of a board field. That is what a board
  is for, and six more labels per issue makes the list unreadable. The one exception
  (`agent-ready`) is argued in §5.2.
- **A `blocked` label with the blocker named in prose**, which is what this design carried until
  the maintainer's review of PR #413 asked how a contributor is supposed to know what is ready.
  Rejected once §4.2's native dependencies turned out to be GA on Free: a label has to be added
  and removed by hand, says nothing about *what* blocks, and goes stale the moment the blocker
  closes. The native edge is computed, shows a Blocked marker on the board, and lets readiness be
  derived instead of asserted.

## 11. Open questions

Decisions taken during review are recorded where they belong — the board starts empty (§8),
`triage` stays human-invoked (§5.6), dependencies are tracked at every level and derived from the
`depends on` column rather than drawn by hand (§5.3), and §5.1's milestone names are illustrations
— the first real one, `Issue tracking standard live`, was named by the maintainer on 2026-09-27.
What is left genuinely open:

1. **CI gates — deferred, not dropped.** PR-must-link-an-issue, parent-can't-close-with-open-
   children, auto-add-to-project, and the `agent-ready`↔Status reconciliation of §8. Scoped out of
   v1 by the maintainer. §5.6's fixed heading spellings and `labels.yml` exist so these drop in
   later without re-templating anything. What is unanswered is the *trigger*: what evidence says
   the standard has earned enforcement — a count of issues filed, a stretch with no drift, or a
   second contributor arriving?
2. **`docs/ROADMAP.md`'s long-term role — deliberately not resolved here.** The maintainer's
   stated direction (PR #413 review): the roadmap's source of truth should become the project and
   its milestones in the long run, but nothing moves until this standard has been validated in
   use. Until then ROADMAP stays exactly as written, and the consolidation is a later decision
   with its own evidence.

## Appendix

### Checked live

All on 2026-09-27, from this checkout.

- `GET /repos/h0ffmann/marola/issues/412/sub_issues` → `[]`. Endpoint live on this repo.
- `GET /repos/h0ffmann/marola/issues/412` → `sub_issues_summary: {completed:0, percent_completed:0,
  total:0}`, `type: null`.
- `https://docs.github.com/en/rest/issues/sub-issues` → `sub_issue_id` documented verbatim as
  *"The id of the sub-issue to add. The sub-issue must belong to the same repository owner as the
  parent issue"*. No limits or token permissions stated on that page.
- `docs.github.com` "Adding sub-issues" (via search) → **100 sub-issues per parent, 8 levels of
  nesting**.
- `GET /repos/h0ffmann/marola/issues/412/dependencies/blocked_by` → `[]`. Issue-dependencies
  endpoint live on this repo.
- `gh issue create --help` → `--blocked-by`, `--blocking`; `gh issue edit --help` →
  `--add-blocked-by`, `--add-blocking`, `--remove-blocked-by`, `--remove-blocking`. Present in this
  host's `gh` 2.95.0.
- `docs.github.com` "Creating issue dependencies" + `github.blog` "Dependencies on issues"
  (2025-08-21) and "Manage sub-issues, types, and dependencies from GitHub CLI" (2026-06-10) →
  generally available, included on GitHub Free, up to 50 issues per relationship type, blocked
  issues marked on the board and the issues list.
- `docs.github.com` "Creating issue dependencies" and `.../rest/issues/issue-dependencies`, read
  for restrictions → **none stated** on which issues may carry dependencies (no mention of
  hierarchy, parents or sub-issues); the REST `POST .../dependencies/blocked_by` body takes
  `issue_id`, the database id; the dependency response schema includes `parent_issue_url` and
  `sub_issues_summary`.
- `GET /orgs/h0ffmann/issue-types` → 404 (`h0ffmann` is a user account). `GET
  /users/h0ffmann/issue-types` → 404, no such endpoint. Issue types unavailable here.
- `https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps` →
  `project` = *"Grants read/write access to user and organization projects"*; `read:project` =
  *"Grants read only access to user and organization projects"*.
- `gh auth status` → host token scopes include `read:project`, **not** `project`.
- `gh project list --owner h0ffmann` → `{"projects":[],"totalCount":0}`.
- `GET /repos/h0ffmann/marola` → `has_discussions: false`, `has_projects: true`, `has_issues: true`.
- `gh label list` → 35 labels; `layer/azure` still present after 993b469 removed Azure.
- `gh issue view 412 --json body` → body begins `### Problem`, `### Proposed behaviour` — form
  fields render as `###` headings, confirmed on real data.
- `github.com/brunogbv/cv` → `.specify/` tree, nine `speckit-*` skills,
  `.claude/hooks/review-gate.sh`; `speckit-taskstoissues/SKILL.md` read in full; label set includes
  `phase:*` and `harness`; 15 issues sampled showing `003-T0NN: …` titles. No milestones.
- `github.com/brunogbv/marilegal-frontend` → stock `.md` issue templates, default label set, no
  milestones, no `.specify/`. Nothing adopted from it.
- **Dogfood run, 2026-09-27** — this MIP's own task list filed through its own design, by hand,
  because `tasks-to-issues` is task 5. Created milestone #1 `Issue tracking standard live`, the
  four new labels, and issues #414–#421 (`0063-T1`…`T8`) with bodies in the `task.yml` heading
  shape. **Ten `blocked by` edges created and read back**: `415←414`, `416←414`, `417←415,416`,
  `418←415,417`, `419←417`, `420←418,419`, `421←420`. The graph round-tripped exactly as authored,
  and the computed ready set was `{#414}` alone — rule 5 of §5.4 working against the live API with
  no script in between. `#414` then passed all five rules by hand and was labelled `agent-ready`.
  The run also found the two defects corrected in §4.7, §5.3, §5.5, §7 and §8: marola's task files
  are tables, not checkbox lists, and their `depends on` column is a DAG rather than the `k ← k-1`
  chain this MIP first assumed.
- `scripts/docs-mip-stack.sh list` → `docs/mip-0061-swim-brief-agent` is an unmerged draft holding
  MIP-0061; 0062 is in `main`. 0063 is the first free number.
- **Task 1 run, 2026-09-27** (PR #423) — `.github/labels.yml` generated from the live repo, then
  `labels sync --dry-run` reported `layer/azure` as the only orphan with nothing to create or
  edit, and `labels sync --prune` deleted it. The repo went 40 labels → 39, matching the manifest
  exactly. `layer/azure` was carried by four closed PRs (#3, #47, #48, #286), which lost it.
  Recorded here because the check is not repeatable: once the orphan is pruned the same command
  prints `in sync`, so the manifest-vs-repo drift path lives on in `--self-test`'s fixtures only.
- **Label names are case-insensitive, label paths are not** — 2026-09-27, probed on this repo
  because neither the REST reference nor the labels docs say either way:

      gh label create zz-probe-case   -> created
      gh label create ZZ-PROBE-CASE   -> "already exists; use --force"
      gh label delete ZZ-PROBE-CASE   -> HTTP 404
      gh label delete zz-probe-case   -> deleted            (probe labels removed)

  Lookup on create folds case; the DELETE (and so PATCH) path does not. A sync that matched names
  case-sensitively would therefore plan a create that fails *and* a delete of the real label, so
  §5.2's manifest reconciliation has to fold case on the name and address every edit and delete by
  the spelling the repo currently holds. Nothing in the design moves; the constraint is on any
  script that writes labels.
- **A dependency between two sub-issues of the same parent is allowed** — 2026-09-27, §7 step 7,
  the one assumption §5.3 leans on. Three throwaway issues, since closed as `not_planned`: #426
  parent, #427 child A, #428 child B (database ids `5606620709`, `5606620805`, `5606620913`).

      POST /issues/426/sub_issues            {"sub_issue_id":5606620805}
        -> the *parent* #426, sub_issues_summary {total:1, completed:0}
      POST /issues/426/sub_issues            {"sub_issue_id":5606620913}    (issues.sh sub add)
        -> accepted
      POST /issues/428/dependencies/blocked_by {"issue_id":5606620805}      (issues.sh deps add)
        -> accepted: #428 blocked by #427, two sub-issues of one parent
      GET  /issues/428/dependencies/blocked_by
        -> [{"number":427, "state":"open", "parent_issue_url":".../issues/426",
             "sub_issues_summary":{"total":0,...}}]
      GET  /issues/427/dependencies/blocking  -> [{"number":428}]
      GET  /issues/427  and  GET /issues/428                               (checked separately)
        -> parent_issue_url .../issues/426 on both, so the edge above really is between two
           sub-issues of one parent and not between two loose issues

  §5.3 stands as written and Decision 8's fallback does not apply. Three things worth carrying:
  the POST to `sub_issues` answers with the **parent**, not the sub-issue; `parent_issue_id` is
  `null` in the `sub_issues` listing while `parent_issue_url` is set on the issue itself, so a
  reader of the wrong field sees an orphan; and the edge **survives closing both issues** and then
  reads `"state":"closed"`, which is exactly what §5.4's rule 5 needs to distinguish. The parent's
  rollup went to `{completed:2, total:2, percent_completed:100}` on closing the children.
- **`POST /issues/{n}/sub_issues` executed** — same probe. The `sub_issue_id` in each call above is
  a database id resolved from the issue number, and the attachment landed on the issue asked for.
  `scripts/issues.sh` resolves numbers to ids in one place and refuses anything under nine digits;
  live ids on this repo are ten (`5606620913` against issue number `428`).
- **Task 4 run, 2026-09-28** (`issues.sh ready`, `--dry-run`, no labels written). `GET
  /repos/marola-dev/marola/labels/mip` → **404**; the `mip` label §5.2 called existing does not
  exist, so §5.4's tier dispatch needed it in the manifest. `ready` against the eight live
  fixtures: #431–#434 and #417 pass all five; #418 fails rule 5 alone, naming `#417` and **not**
  `#415`, which is closed — the closed-blocker distinction of the 2026-09-27 probe, now read
  through the script; #412 fails 1, 2 and 4; #411 fails 1, 2, 3 and 4. `issues.sh queue` over 16
  open issues → `0 ready · 0 blocked · 10 in triage` (the other six are assigned), one
  `dependencies/blocked_by` call per unassigned issue, 6s.

### Not checked

- **The mis-attach half of §4.1's trap was not probed.** That passing an issue *number* as
  `sub_issue_id` silently attaches a different issue is still the documented reading, not an
  observed one: the ids that collide with marola's three-digit numbers belong to issues in
  unrelated repositories, and the probe would have written a sub-issue link onto a stranger's
  issue. `scripts/issues.sh`'s guard is therefore built on the parameter's documented semantics
  plus the order-of-magnitude gap between the two spaces, not on a reproduction.
- **Nothing was re-read for the board.** Projects v2 writes are still unexercised (no `project`
  scope on the host token), so §5.2's four views remain specified rather than built.
- **Projects v2 write operations have not been exercised**, because the host token lacks `project`
  scope. The four views in §5.2 are specified, not built; whether all four filters are expressible
  in the Projects UI as written is unverified.
- **Projects' built-in workflows** (auto-add, closed → Done) are taken from general knowledge of
  the feature, not re-read from the docs this session.
- **Whether GitHub renders *every* issue-form field type as a `###` heading** — verified for
  `textarea` on #412 only. `dropdown` and `checkboxes` rendering is assumed, not confirmed.
- **`brunogbv/cv`'s `.specify/extensions.yml` hook mechanism** is described in the skill text that
  was read, but no extensions file was fetched; nothing in this MIP depends on it.
