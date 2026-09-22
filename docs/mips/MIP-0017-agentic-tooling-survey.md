# MIP-0017: Agentic tooling ideas from `ai-job-search` and September 2026's trending agent repos

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: survey `ai-job-search`'s agentic tooling and this week's trending agent repos for ideas marola could use) |
| **Created** | 2026-09-06 |
| **Phase** | 0 — developer tooling; nothing a user of marola sees. No earlier-phase prerequisite |
| **Related** | `MIP-0011` (Claude Code best practices — this MIP extends the same axis, dev-tooling skills/hooks, not marola's product), `docs/AGENT-SKILLS.md` §3 (skill candidates already on record), `docs/DEV-FLOW.md` §5 (the reviewer subagent pattern §1 below tightens), `.claude/skills/mip-solve-perpetual/SKILL.md` (the checkpointing contract §2 below extends), `AI-500-MAPPING.md` §4 (governance/audit-trail gap — analogous pattern, but for the dev-tooling agent working on this repo, not marola's own product agents) |
| **Effort** | S — each accepted idea below is a single skill/doc/script change, no new runtime dependency, no new module |
| **Gain** | infra/dev-loop (cheaper, more auditable agent sessions on this repo) |
| **Effort vs Gain** | cheap win for §5.1/§5.2 (build next); §5.3 is a nice-to-have housekeeping item with no urgency — do whenever MIP-0011 task 8 is picked up |
| **Depends on** | none blocking; Phase 0, developer tooling only. §1 touches the same reviewer-subagent text `docs/DEV-FLOW.md` §5 already describes; §2 is additive to `mip-solve-perpetual`'s just-rewritten usage-guard section (this session's own work, still unmerged) |
| **Risk** | importing patterns from a single-tenant personal-automation repo (`ai-job-search`) or from repos optimized for managing *fleets* of agents (this week's trending crop) without checking they fit marola's one-repo, one-contributor, MIP-gated shape — several ideas from both sources are explicitly rejected below for exactly this reason |
| **Cost so far** | — (nothing from this MIP has merged yet) |

## 1. Summary

Two external sources were surveyed for agentic-workflow ideas transferable to marola's own
Claude Code setup (not marola-the-product): [`MadsLorentzen/ai-job-search`](https://github.com/MadsLorentzen/ai-job-search)
(a personal job-application automation framework built on Claude Code, ~13.8k★) and four repos
from GitHub's trending-agentic-tooling crop of the week of 2026-09-04. Four ideas transfer
concretely to marola's existing `.claude/skills/`/`docs/DEV-FLOW.md` structure at low effort; three
more are named and explicitly rejected as not fitting marola's shape, so this MIP doesn't read as
"adopt everything trending."

## 2. Motivation

This session's own work exposed two concrete gaps `ai-job-search`'s design happens to address:

- Rewriting `mip-solve-perpetual`'s usage-guard logic (this session, branch
  `perf/mip-perpetual-skill`) added a "checkpointing contract" in prose, but there is still no
  single file a human (or the next run) can read to see *which tasks in an overnight run actually
  landed, in what order, at what cost* without reconstructing it from git log and `GH_POST_MORTEM.md`
  by hand. `ai-job-search`'s `job_search_tracker.csv` is exactly this: one flat file, one row per
  unit of work, machine- and human-readable.
- `docs/DEV-FLOW.md` §5's reviewer-subagent invocation doesn't say whether the reviewer re-reads
  the diff itself or receives it inline. `ai-job-search`'s drafter-reviewer pattern says explicitly:
  *"The reviewer agent receives drafts inline rather than re-reading them, and the verification
  checklist runs once"*, a token-efficiency detail worth stating as a rule here too, especially
  given this session's own throttle rework was prompted by "Sonnet/medium wastes tokens on basic
  things."

## 3. User-visible change

None: Phase 0, developer tooling only. No CLI or Telegram-facing output changes.

## 4. Data sources and dependencies reviewed

### 4.1 `MadsLorentzen/ai-job-search` (fetched 2026-09-06)

What it is: a Claude Code-based framework that evaluates job postings, tailors CVs/cover letters,
and preps interviews, run entirely from a personal fork (`github.com/MadsLorentzen/ai-job-search`,
`AGENTS.md` and repo root fetched via WebFetch on 2026-09-06). Verified structure:

- `.claude/skills/job-application-assistant/` holds seven numbered instruction files
  (`01-candidate-profile.md` … `07-interview-prep.md`) rather than one monolithic `SKILL.md`:
  ordered sub-documents within one skill.
- `.claude/commands/` holds thirteen single-purpose slash commands (`apply.md`, `scrape.md`,
  `rank.md`, `outcome.md`, `interview.md`, `gmail-sync.md`, etc.), each a thin workflow trigger.
- The `/apply` command runs a **drafter-reviewer pattern**: a drafter agent writes the CV/cover
  letter, a **fresh** reviewer agent researches the company and critiques the draft *inline*
  (without re-reading it from disk), the drafter revises, then a verification pass compiles and
  visually checks the PDF output once.
- State lives in plain files, not a database: `job_search_tracker.csv` (one row per application:
  status, outcome, funnel stage), `gmail_sync/` (processed message IDs + last-sync timestamp),
  `upskill/` (one markdown gap-analysis report per run), `documents/applications/<company>_<role>/`
  (archived submitted artifacts).
- `AGENTS.md` itself is a **thin pointer**: it states a "unified thin-pointer design" so multiple
  agent frameworks (Claude Code, others) read one canonical set of files rather than duplicating
  config; the same shape marola's own `CLAUDE.md` → `@AGENTS.md` import already uses. This
  confirms marola's existing pattern rather than suggesting a new one.

What was **not** checked: the repo's actual commit history/quality, whether its LaTeX
compile-and-inspect verification loop has a documented failure mode, or its Notion-sync
implementation; none of those are candidates for marola, which has no CV/PDF or CRM surface.

### 4.2 GitHub trending agentic-tooling repos, week of 2026-09-04 (WebSearch, 2026-09-06)

Source: a devtools digest dated 2026-09-04 plus a general trending-agents search. Repos reviewed,
each at the level of "what is this repo's one idea," not a deep audit:

| Repo | What it does | Relevant idea for marola? |
|---|---|---|
| `anthropics/skills` | Anthropic's own public agent-skills repo | Yes: a reference to audit marola's `SKILL.md` frontmatter/structure conventions against, §5.3 |
| `pacifio/atlas` | "source control layer for tracking and querying changes made by multiple coding agents" | Partially: the *idea* (a queryable ledger of agent-authored changes) is worth having; the *tool* is not (assumes a fleet-of-agents SaaS shape marola doesn't have); see §9 |
| `Graphify-Labs/graphify` | Converts a codebase into a queryable knowledge graph for agent use | No: new heavy dependency for a problem `Explore`/`Grep` already solve at marola's size; rejected, §9 |
| `eneskirca/nodeterm` | Terminal manager showing parallel agent sessions as a pan/zoomable node canvas | No: a UI tool for managing *many concurrent* agent sessions; marola's own convention is one session per feature (`AGENTS.md`), so the problem it solves doesn't exist here; rejected, §9 |
| `garrytan/gstack` | 23 Claude Code tools spanning CEO/designer/engineering-manager/QA roles | Not adopted directly (role-per-tool is overkill for a one-repo project), but confirms `docs/AGENT-SKILLS.md` §3's existing direction (named, scoped subagents) is the right shape at marola's scale |

## 5. Design

Three concrete, additive changes, each a single file/skill edit, no new runtime dependency:

### 5.1 A flat-file run tracker for `mip-solve-perpetual`

Add `docs/mips/MIP-NNNN.run-log.csv` (one per MIP being worked overnight, gitignored like
`GH_POST_MORTEM.md`; it's an operator artifact, not a deliverable) with columns: `timestamp,
task_k, branch, throttle_level, model, effort, outcome, cost_usd, pr_status`. `mip-solve-perpetual`
appends one row per task attempt (including failed attempts, so a human sees the retry history,
not just the final state). This is strictly additive to the checkpointing contract this session
already wrote into the skill (`perf/mip-perpetual-skill`, unmerged): that contract defines *when*
the loop may stop; this tracker records *what happened*, in one grep-able/`csv`-parseable place,
the same shape as `ai-job-search`'s `job_search_tracker.csv`. A future `just cost-split` run could
cross-check its numbers against this file's `cost_usd` column as a sanity check.

### 5.2 State the "reviewer receives the diff inline" rule explicitly

Add one sentence to `docs/DEV-FLOW.md` §5's reviewer-subagent bullet: the reviewer subagent's
prompt should paste `git diff <BASE_SHA>..<HEAD_SHA>` inline rather than instructing the subagent
to re-run that diff itself, and the verification checklist (build/test/quality) is not something
the reviewer re-derives; it's already reported by the author's own PR body. This is a one-line
efficiency rule, not a new mechanism: `docs/AGENT-SKILLS.md` §2.1's existing `requesting-code-review`
sequence already does most of this; the addition is naming the "inline, not re-read" detail so it
survives a future rewrite of that section.

### 5.3 Audit marola's skill frontmatter against `anthropics/skills`

A one-time housekeeping task, not a new skill: read `anthropics/skills`' published skill
frontmatter conventions (`name`, `description`, `allowed-tools`/`disallowed-tools` shape) and
diff against marola's six in-repo skills (`.claude/skills/{mip,mip-tasks,mip-solve-perpetual,
site-frontend,voice-note-ingest,voice-to-feature}/SKILL.md`) for drift: e.g. whether
`disable-model-invocation` is used consistently everywhere it should be (already flagged as
task 8 of MIP-0011), or whether a `description` could be tightened to Anthropic's own phrasing
convention for better trigger-matching. Output: a short paragraph in `docs/AGENT-SKILLS.md`
recording what was checked and what (if anything) changed, not a new file.

## 6. Scoring / safety impact

None: this MIP touches only Claude Code dev-tooling (skills, docs, an operator-facing CSV). No
change to `Swimability`, ranking, or any user-facing safety text.

## 7. Verification plan

- §5.1: `mip-solve-perpetual`'s next real overnight run (already scheduled once MIP-0011's
  remaining tasks resume) produces a run-log CSV with one row per task attempt, reviewable by hand
  against the actual PRs opened.
- §5.2: the next reviewer-subagent invocation under `docs/DEV-FLOW.md` §5 is checked to confirm
  the diff was pasted inline in the dispatch prompt, not re-fetched by the subagent.
- §5.3: a diff of marola's six `SKILL.md` frontmatter blocks against `anthropics/skills`'
  convention, pasted into the implementing PR's description.
- No new unit tests: nothing here is Scala/Python runtime code. `just build && just test &&
  just quality` still run per `AGENTS.md`'s hard rule and are expected to be no-ops content-wise.

## 8. Risks, limitations, and honest caveats

- The `ai-job-search` structure was read via WebFetch (an AI summarization pass over the page),
  not by cloning the repo and reading raw file contents; the described directory layout and
  drafter-reviewer mechanics are reported *as WebFetch's model summarized them*, not verified
  byte-for-byte against the source. Treat §4.1 as "confirmed shape, not confirmed exact wording."
- The trending-repos list (§4.2) is a snapshot of one digest plus one search, not an exhaustive or
  reproducible ranking; GitHub's own trending page is unauthenticated and time-windowed
  differently than the digest queried; a different day would likely surface a different five.
- §5.1's CSV tracker only has value once `mip-solve-perpetual` actually runs unattended for a full
  MIP; it can't be verified end-to-end until then, which is why this MIP doesn't propose building
  it inside this same change (per the `mip` skill's own rule: design here, build in a separate PR).

## 9. Alternatives considered

- **Do nothing** (skip this survey's ideas entirely): loses two real efficiency/auditability
  ideas at zero-cost, and the counterfactual is another agent session re-doing this exact research
  because the "should marola pace/track its own dev-agent runs better" question resurfaces. Not
  chosen.
- **Adopt `pacifio/atlas`** (a real dependency) instead of §5.1's plain-CSV tracker; rejected:
  `atlas` is built for orchestrating *fleets* of concurrent coding agents across a team; marola's
  own convention is one session per feature (`AGENTS.md`), so the coordination problem it solves
  (whose agent touched what, concurrently) doesn't exist here. A flat CSV a human can open in a
  spreadsheet is proportionate to the actual problem.
- **Adopt `Graphify-Labs/graphify`** for codebase context; rejected: marola is a four-module sbt
  project small enough that `Explore`/`Grep`/reading `AGENTS.md`'s own doc-index table already
  gives an agent working context in seconds; a knowledge-graph dependency solves a scale problem
  marola doesn't have yet.
- **Adopt `eneskirca/nodeterm`**-style parallel-session tooling; rejected: it manages *many*
  concurrent agent sessions visually; marola's workflow is explicitly one feature, one session
  (`AGENTS.md` "Attribution and cost accounting"), so there is nothing for it to visualize here.
- **Mirror `ai-job-search`'s numbered sub-file skill layout** (`01-…md`, `02-…md` inside one skill
  directory) for marola's own skills; considered for `mip`'s multi-step template, but rejected for
  now: marola's `SKILL.md` files are each already short enough (under ~150 lines) that splitting
  them would add navigation overhead without a real length problem to solve. Worth revisiting only
  if a future skill grows past that.

## 10. Exam-coverage mapping

None directly — this is Claude Code dev-tooling, not marola's product agent architecture, so it
doesn't map to an AI-103 or AI-500 domain row the way MIP-0011 or the AI-500-MAPPING.md items do.
§5.1's run-tracker idea is a loose structural echo of `AI-500-MAPPING.md` §4's "governance
documentation for multi-agent audit trails" gap, but that gap is about marola's own future
product agents (summarizer/reviewer/escalation), not the Claude Code agent developing marola —
noted as a parallel, not claimed as coverage.

## 11. Open questions

- Should §5.1's run-log CSV live per-MIP (`docs/mips/MIP-NNNN.run-log.csv`) or as one running file
  across all overnight runs? Per-MIP mirrors the existing `MIP-NNNN.tasks.md` convention and keeps
  it easy to gitignore-and-forget once a MIP's stack merges; a single running file would need its
  own rotation/archival policy. Leaning per-MIP; not decided here.
- Is a CSV the right format, or would a JSON-lines file (one append-only write per row, no
  quoting/escaping edge cases with commit messages containing commas) be more robust for a script
  to append to reliably? `ai-job-search` uses CSV because its rows are simple; marola's rows would
  need to embed a PR title, which can contain commas. Leaning JSONL for that reason, but not
  decided; whichever implementation task builds §5.1 should pick and justify it.
- §5.3 (skill-frontmatter audit) has no owner or scheduled time yet; it's a nice-to-have
  housekeeping task, not blocking anything; it may be worth folding into MIP-0011's still-open
  task 8 (skills hardening) rather than becoming its own task, since both touch the same files.

## Appendix

Raw findings, for reference:

- `ai-job-search` AGENTS.md fetch (2026-09-06): described as a "thin-pointer design" workspace
  file, delegating to `CLAUDE.md` (candidate profile) and `.claude/skills/job-application-assistant/`
  (methodology), plus `.agents/skills/` for portable Agent-Skills-format portal tools. No explicit
  workflow-phase, memory, or cost-tracking mechanism documented in the file itself; those live in
  the skill/command files instead, per the pointer design.
- Repo root fetch (2026-09-06) confirmed directory layout: `.agents/skills/` (portal CLI tools),
  `cv/`, `cover_letters/`, `documents/`, `templates/`, `job_scraper/`, `job_search_tracker.csv`.
- Trending-repos source: `startupcorners.com/digest/devtools-digest-2026-09-04` plus a general
  WebSearch for "github trending agentic AI tools September 2026 claude code skills subagents"
  (2026-09-06). Also surfaced but not reviewed in depth (visual-builder platforms, out of scope
  for a CLI/Telegram project): Langflow, Dify, Flowise; multi-agent orchestration frameworks
  MetaGPT, LobeHub, CrewAI, AutoGen; none reviewed against marola's Scala/Kyo stack, noted only
  because they came up in the same search.
