# MIP-0063: A GitHub-native tracking standard — issues as the unit of work, milestones as deliverables

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5), for B. Valério |
| **Created** | 2026-09-27 |
| **Phase** | 0 (dev-loop; no user-facing surface) |
| **Related** | `DEV-FLOW.md` §1–§3 (this MIP inserts an issue step ahead of the MIP step, and files each task row as an issue), `CONTRIBUTING.md`, `AGENTS.md` (phase discipline, the docs table), the `mip-tasks` skill, `brunogbv/cv` (prior art: GitHub spec-kit, `speckit-taskstoissues`) |
| **Effort** | M — one script family (`scripts/issues.sh`, `scripts/lib/tasks_issues.py`), four issue forms, a `labels.yml` manifest, one new skill and one extended skill, four doc edits. No Scala, no new module, no CI workflow (§11.1 defers those) |
| **Gain** | `infra/dev-loop` (the backlog stops being 58 Draft design docs and a prose ROADMAP, and becomes a queue an agent can query in one call); `infra/dev-loop` again on the intake side (a contributor, human or agent, has one standard way to propose work instead of four half-documented ones) |
| **Effort vs Gain** | `do next` — every later harness layer the maintainer wants (validation gates, graphify, autonomous triage) needs a machine-readable work queue to operate on, and there isn't one today |
| **Depends on** | No other MIP. Not gated by Phase 1: this is dev-loop work, which `ARCHITECTURE.md` §11 puts outside the phase sequence. Creates no paid resource, so `AGENTS.md`'s cost gate does not apply. Two actions only the maintainer can take: `gh auth refresh -s project` on the host (§4.3) and enabling Discussions (§4.5) |
| **Blocked by** | none |
| **Risk** | Taxonomy nobody maintains. Filing ~6 issues per accepted MIP turns a 58-MIP backlog into several hundred issues; if triage lapses, `agent-ready` rots and the queue becomes actively misleading — worse than the prose ROADMAP it replaced, because it looks authoritative |
| **Cost so far** | — |

## 1. Summary

marola already does spec-driven development: a MIP is the spec, `MIP-NNNN.tasks.md` is the plan,
a stacked PR is the increment. All three live in files, so the backlog is invisible in GitHub's UI
and unqueryable by anything that isn't already inside the repo. This MIP puts the *work* into
GitHub — issues as stories and tasks, milestones as deliverables, one public project board — and
defines a five-rule **Definition of Ready** that gates what an agent is allowed to pick up. The
spec layer does not move: MIPs stay files, and `tasks.md` stays the authored plan.

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

And the Definition of Ready is enforced, not documented:

```console
$ just issue-ready 435
✗ #435 is not agent-ready
  ✓ 1. acceptance criteria present
  ✗ 2. named test — no "### Named test" section in the body
  ✓ 3. area/* and layer/* labels set
  ✗ 4. size/* label missing
  ✓ 5. not blocked
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

**The trap, verified:** `POST /issues/{n}/sub_issues` takes `sub_issue_id`, documented verbatim as
*"The id of the sub-issue to add"* — the **database id**, not the issue number. Passing the number
attaches an arbitrary issue, silently and successfully. Gets a comment in the script (`AGENTS.md`'s
"a trap" category) and a self-test.

### 4.2 Issue types — **rejected, unavailable**

GitHub's typed issues (task / bug / feature, up to 25) would carry the story-vs-task distinction
better than labels, but they are an **organization** feature. Verified empirically:
`GET /orgs/h0ffmann/issue-types` → 404 (`h0ffmann` is a user account), and no
`/users/{user}/issue-types` endpoint exists. `issue.type` is present in the payload but reads
`null` and cannot be set here. Labels carry the roles instead. Revisit if marola moves to an org.

### 4.3 Projects v2 and token scopes — **adopted, with a human prerequisite**

`gh project list --owner h0ffmann` → `{"projects":[],"totalCount":0}`; the repo has
`has_projects: true`. Scopes verified against GitHub's OAuth table: **`project`** = *"read/write
access to user and organization projects"*, **`read:project`** = *"read only access"*. The host
token carries `read:project` only, so `just board-sync` fails until the maintainer runs
`gh auth refresh -s project` — a human action by design, since an agent should not widen its own
token's scope. Projects' **built-in workflows** (auto-add, closed → Done) are configured in the
project UI; this MIP uses them rather than reimplementing them as Actions.

### 4.4 Issue forms → parseable bodies — **adopted, verified on real data**

GitHub renders each form field into the body as a `### <Label>` heading plus the value. Verified
against this repo rather than the docs: issue **#412**'s body begins `### Problem` /
`### Proposed behaviour`, matching its form's field labels exactly. This is what makes the
Definition of Ready checkable by `grep` rather than by a model, and why §5.5 fixes the heading
spellings across all four forms.

### 4.5 Discussions — **adopted, off today**

`has_discussions: false`. Enabling it is a repository setting (maintainer action). Narrow purpose:
keep "how do I…" out of a tracker meant to be a work queue.

### 4.6 Prior art — `brunogbv/cv` and GitHub spec-kit — **borrowed, not vendored**

`brunogbv/cv` runs GitHub spec-kit (`.specify/` plus nine `speckit-*` skills). Its
`speckit-taskstoissues` is the closest existing thing to §5.4, and three of its decisions are
adopted directly: a **feature-scoped task id in the title** (`003-T001: …`) so per-feature ids
restarting at `T001` do not collide during dedup; **rewriting the checkbox into a link** rather
than keeping two states; and declaring that afterwards issues own status while the file owns the
plan. Its `harness` label is not adopted — `area/dev-tooling` covers it.

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
location`, `Water quality on the map`, `Trilingual marola — pt-BR, English, Japanese`. Ordering
between milestones is a board decision, never a property of the milestone, because priorities
change as the repo learns what must come first.

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

Six new labels: `epic`, `agent-ready`, `blocked`, `size/S`, `size/M`, `size/L`. Existing
`area/*` (12), `layer/*` (8), `kind/*`, `priority/high` and `mip` are untouched. **`layer/azure`
is orphaned** — 993b469 removed Azure — and is deleted here.

Size carries a rule, not a feeling: **S < 100 changed lines, M 100–400, L means split it.**
`DEV-FLOW.md` §3 caps a task at ~400 lines, so `size/L` is only ever legal on a parent issue.

`.github/labels.yml` becomes the versioned manifest — every label with colour and description in
one reviewable file rather than state that exists only in a settings UI. `just labels-sync`
reconciles and reports orphans; it never deletes without `--prune`.

The board is one user-level public project, `marola`, with **one custom field** (`Status`:
Triage / Spec / Ready / In progress / In review / Done) and four views: **Triage**
(`Status:Triage`, the public inbox and the one view a maintainer reads daily), **Now** (by Status,
filtered to the milestone currently being pushed), **Agent queue** (`label:agent-ready`, sorted
size then `priority/high`) and **Good first issues**.

**One piece of state is deliberately duplicated:** `agent-ready` is a label *and* Ready is a Status
value. The reason is `AGENTS.md`'s jail rule — a session inside ai-jail has no `gh` login of its
own and a narrow `GH_TOKEN`; `gh issue list --label agent-ready` is one REST call on `repo` scope,
while a Project field needs `project` scope and GraphQL. The `just` commands own keeping the two in
step; nothing else may write either.

### 5.3 The Definition of Ready

An issue is `agent-ready` when all five hold:

1. **Acceptance criteria** present, as testable checkboxes (`### Acceptance criteria`).
2. **A named test** — file plus test name (`### Named test`). This is MIP §7's discipline pulled
   forward onto the issue, so the test is decided before the work is claimed, not after.
3. `area/*` **and** `layer/*` set.
4. `size/*` set.
5. Not `blocked`.

**Phase discipline is not a sixth rule.** A `phase/2` issue that is not yet legal is simply
`blocked` with a named blocker in its body ("Phase 1, `ARCHITECTURE.md` §11, not done"), the same
as any other dependency. `phase/1..4` remain pure facet labels with no ordering power. This keeps
one mechanism for "you may not start this yet" instead of two.

**`AGENTS.md` gains one hard rule:** an agent may only begin implementation on an issue carrying
`agent-ready`. Everything else is a human's problem first.

### 5.4 The command surface

Six recipes over one `scripts/issues.sh`, each with the repo's usual `--dry-run`:

| Command | Does |
|---|---|
| `just tasks-to-issues MIP-NNNN --milestone "<name>"` | creates missing issues titled `0063-T3: …`, dedups on `\b0063-T\d+\b`, rewrites `- [ ] T3 …` → `- [T3](…/issues/415) …`; idempotent |
| `just issue-queue` | `agent-ready`, unassigned, sorted size then priority — the read an agent makes |
| `just issue-ready <n>` | runs the five rules, adds/removes `agent-ready`, prints which rule failed |
| `just issue-claim <n>` | assigns, drops `agent-ready`, sets board Status, prints the `scripts/stack.sh start` line |
| `just milestone-new "<name>" [--mip MIP-NNNN]` | thin, but keeps deliverables discoverable from `just` |
| `just board-sync` | adds un-added open issues to the project, sets Status from state |

Direction of truth: **`mip-tasks` authors `tasks.md`; `tasks-to-issues` projects it into GitHub;
after that, issues own status and the file owns the plan.** One-way, re-runnable. An agent in
ai-jail with no token can still read the whole plan from disk — which is why the file is not
replaced by the issues.

### 5.5 Forms and skills

Four forms, blank issues still disabled, all sharing heading spellings so §5.3's checks are
one `grep`:

| Form | Tier | Change |
|---|---|---|
| `bug_report.yml` | 1 | gains **Failing test** — the repo already requires reproducing a bug with a failing test, so the form asks |
| `task.yml` *(new)* | 1 | chore/refactor/docs: what, acceptance criteria, named test, size |
| `story.yml` *(replaces `feature_request.yml`)* | 2 | Problem · Proposed behaviour · Acceptance criteria · Named test · Out of scope · Deliverable |
| `mip_proposal.yml` | 3 | kept; now states what happens next (MIP PR → milestone → `tasks-to-issues`) |

`config.yml` points questions at Discussions (§4.5).

Skills: **`mip-tasks` gains a final step** that calls `tasks-to-issues` — no new skill, since it
already authors the file. **New `triage` skill**: takes a raw idea, voice-note fragment or bug
report, picks the tier, drafts the body in the matching form's heading shape, runs the DoR check,
files it. Human-invoked in v1 (§11.2).

## 6. Scoring / safety impact

None. No Scala changes, no `scoring/` changes, no change to any user-facing output. Nothing in
this MIP goes near `Swimability` or the safety footer.

## 7. Verification plan

In the order that proves the most for the least:

1. `scripts/lib/tasks_issues.py --self-test` — the dedup regex (`\b0063-T\d+\b` matches
   `0063-T001`, `[0063-T1]`, `0063-T1:`; does **not** match `S0063-T1` or `0063-T0010`) and the
   checkbox→link rewrite, including idempotence on an already-linked line and preservation of
   `[P]` / `[US#]` markers. No network, no GitHub. Runs under `just quality-other`, so
   `.githooks/pre-push` exercises it on every push.
2. `scripts/issues.sh --self-test` — argument handling and each of the five DoR rules, against
   fixture bodies.
3. `just labels-sync --dry-run` — must print exactly the six additions and flag `layer/azure` as
   orphaned. Nothing else moves.
4. `just tasks-to-issues MIP-0060 --dry-run` against the real existing `MIP-0060.tasks.md` —
   inspect titles and rewritten lines before anything is created.
5. **Live idempotence check**: one throwaway milestone, run for real, run again. Second run must
   create zero issues and rewrite zero lines. This is also where §4.1's `sub_issue_id` trap is
   confirmed against the live API or corrected.

Done looks like: the four forms in place, `labels.yml` synced, the board created with its four
views, `just issue-queue` returning a real list, and `docs/ISSUE-FLOW.md` describing exactly what
the scripts do.

## 8. Risks, limitations, and honest caveats

- **Taxonomy rot** (the metadata `Risk`). Mitigation is scope: v1 files issues only for MIPs as
  they are accepted, not for the 42 Draft MIPs (§11.3). The board fills as work arrives.
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

## 9. Alternatives considered

- **Do nothing.** ROADMAP prose and `tasks.md` keep working for one maintainer with one agent, and
  fail the moment the audience is public or the agents are concurrent. Blocks every later harness
  layer.
- **Adopt spec-kit wholesale**, as `brunogbv/cv` does — `.specify/`, nine skills, `taskstoissues`
  for free. marola's MIP pipeline already holds that role and five skills are built on it, so the
  migration is pure churn. Ideas borrowed instead (§4.6).
- **Milestones as phases** (this design's first draft). Rejected by the maintainer during
  brainstorming, correctly: it bakes sequence into milestone identity, so re-prioritising means
  renaming milestones. Phases became facet labels; deliverables became milestones.
- **Epic issues as a separate object**, milestones reserved for releases. The milestone *is* the
  epic; a parallel epic layer means two progress bars to keep honest.
- **Status as a label family** (`status/triage`, …) instead of a board field. That is what a board
  is for, and six more labels per issue makes the list unreadable. The one exception
  (`agent-ready`) is argued in §5.2.

## 11. Open questions

1. **CI gates — deferred, not dropped.** PR-must-link-an-issue, parent-can't-close-with-open-
   children, auto-add-to-project, and the `agent-ready`↔Status reconciliation of §8. Scoped out of
   v1 by the maintainer. §5.5's fixed heading spellings and `labels.yml` exist so these drop in
   later without re-templating anything.
2. **Automated triage.** Should a labelled issue trigger an agent that drafts the spec body?
   `AGENTS.md` requires a human-confirmation gate for proactive agent behaviour unless a MIP
   decides otherwise. This MIP does **not** decide it: the `triage` skill is human-invoked in v1.
3. **Backfill.** Do the 42 Draft MIPs get milestones and issues retroactively, or does the board
   start empty? Recommendation: start empty, backfill only MIPs as they are accepted. Needs a call.
4. **Does `docs/ROADMAP.md` survive?** Its §2a bug table is a tracker inside a file and should
   become issues. The rest (the narrative of where things stand) has no GitHub equivalent and
   should stay. Not resolved here.
5. **Sub-issues vs. the stack.** A MIP's tasks are *stacked PRs* with a dependency order that
   sub-issues do not express — GitHub has no "blocked by" edge between issues. v1 records order in
   `tasks.md` only. Whether the board needs a `Depends on` field is unanswered.
6. **Milestone naming is unvalidated.** The three examples in §5.1 are invented for this document.
   The first real milestones should be named by the maintainer, not by an agent reading MIP titles.
7. **Follow-up MIP:** `docs/ROADMAP.md`'s §2a lists six findings, five still open (`stop-gate.sh`
   untracked files, `stop-gate.sh` read-only exit code, `format.sh` offline hard-fail,
   `mip-reviewer` permission prompt, the §7→§8 pointer sweep). Found while researching, not asked
   for. These are Tier-1 issues, not a MIP — they should be filed as the first real use of this
   standard once it lands.

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
- `scripts/docs-mip-stack.sh list` → `docs/mip-0061-swim-brief-agent` is an unmerged draft holding
  MIP-0061; 0062 is in `main`. 0063 is the first free number.

### Not checked

- **`POST /issues/{n}/sub_issues` has not been executed.** The database-id semantics in §4.1 come
  from the parameter's documented description, not from a successful call. §7 step 5 is where that
  gets confirmed or corrected — treat it as documented-not-run until then.
- **Projects v2 write operations have not been exercised**, because the host token lacks `project`
  scope. The four views in §5.2 are specified, not built; whether all four filters are expressible
  in the Projects UI as written is unverified.
- **Projects' built-in workflows** (auto-add, closed → Done) are taken from general knowledge of
  the feature, not re-read from the docs this session.
- **Whether GitHub renders *every* issue-form field type as a `###` heading** — verified for
  `textarea` on #412 only. `dropdown` and `checkboxes` rendering is assumed, not confirmed.
- **`brunogbv/cv`'s `.specify/extensions.yml` hook mechanism** is described in the skill text that
  was read, but no extensions file was fetched; nothing in this MIP depends on it.
