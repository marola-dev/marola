# MIP-0087: Claude Code usage — when to use the CLI, when a Claude Project, and what each one loads

| | |
|---|---|
| **Status** | Draft |
| **Author** | Hoffmann |
| **Created** | 2026-10-10 |
| **Phase** | None: dev-loop guidance, outside `docs/PHASES.md`'s sequence. No Phase 1 prerequisite, no paid resource |
| **Related** | MIP-0011 (the Claude Code practices this repo enforces through `.claude/settings.json`), MIP-0080 (`skills.lock`), MIP-0084 (vendoring the `routing` skill so sessions without the plugin reach it), `docs/3-Ways-of-working/AGENT-SKILLS.md` §2 (superpowers), `DEV-FLOW.md`, #731/#732 (the project snapshot) |
| **Effort** | S — one new ways-of-working page, a correction to `AGENT-SKILLS.md`, and three settings a person changes on claude.ai |
| **Gain** | `infra/dev-loop` — contributors stop assuming a Project thread has the plugins, hooks and attribution the CLI has, and pick the surface that fits the job |
| **Effort vs Gain** | `cheap win` — the facts are already checked (§4); the work is writing them down where a contributor looks |
| **Depends on** | none. Task 3 is a person's act on claude.ai |
| **Blocked by** | none |
| **Risk** | Both surfaces change fast (Projects is a public beta); the page goes stale unless it carries its check date and the commands that re-verify it |
| **Cost so far** | — |

### Readiness

| | |
|---|---|
| **Manually reviewed** | no |
| **Written by** | Hoffmann, with Claude Code (a Claude Project thread) |
| **Tasks** | none needed — three rows in §5.4, each one PR or one setting |
| **Tests** | none — a docs page; `just quality`'s `docs-lint` and the strict docs build cover it |
| **Spec-kit** | none |
| **Issues** | not filed — Draft |

## 1. Summary

marola is worked on from two Claude Code surfaces: the **CLI** on a contributor's machine (in
ai-jail, inside `nix develop`, one repo per session) and a **Claude Project** on claude.ai/code (a
coordinator that starts one cloud thread per task, every marola repo cloned side by side). They do
not load the same things. A Project thread loads every repo's `CLAUDE.md` and `.claude/skills/`, but
not the plugins, hooks, permissions or attribution that each repo's `.claude/settings.json`
declares, and it cannot run `/ultrareview`. This MIP writes that down as
`docs/3-Ways-of-working/CLAUDE-CODE.md`, corrects `AGENT-SKILLS.md`, and turns on the account-level
plugins a Project can actually use.

## 2. Motivation

`AGENT-SKILLS.md` §2 says superpowers is "declared in the repo, not installed by hand", so "Claude
Code installs and enables it for whoever opens the umbrella". That holds for the CLI after the trust
dialog. It does not hold for a Project thread, and the docs say so: "A cloud session ... doesn't load
the plugins you installed on your own machine or the ones your repository's `.claude/settings.json`
turns on" (§4.2). The thread that wrote this MIP had no `superpowers:*` and no `marola-devkit:*`
skill in its list, although `marola/.claude/settings.json` enables both.

The gap goes further than plugins. A Project thread starts in `/home/user`, above thirteen cloned
repos, so no repo's `.claude/settings.json` is the project settings file:

- MIP-0011's hooks and permission allowlist do not run; the gates are only as good as the thread's
  memory of `AGENTS.md`.
- The repo's `attribution` block (`Co-Authored-By: Claude <noreply@anthropic.com>`, no session link)
  is replaced by the harness's own, which adds a model name and a `Claude-Session:` URL. The org
  invariant allows three trailers and nothing else.
- The account-level `mip` skill the Project syncs is an old copy: it points at `docs/mips/`,
  `ARCHITECTURE.md` and Azure, none of which exist after MIP-0070 (§4.3).

Contributors (and the coordinator) also ask which surface to use for a job. Today the answer is
in nobody's head but Hoffmann's.

## 3. User-visible change

None for a marola user. For a contributor, a new page in the docs site's *Ways of working*, whose
core is this table (every row checked in §4; "inferred" marks the ones that were not):

| | Claude Code CLI | Claude Project (claude.ai/code) |
|---|---|---|
| **Who it is for** | a contributor at a machine: long interactive work in one repo, anything that needs the jail, Docker, Ollama, a GPU or a local checkout | the maintainer away from a desk or running several tasks at once: asks from the phone, cross-repo planning, MIPs, PR babysitting, docs, research |
| **Where it runs** | the contributor's machine, ideally `just jail-claude` | an Anthropic cloud container per thread (or the contributor's machine through Remote Control) |
| **Repos per session** | one (start in the repo you change) | all thirteen configured repos, cloned under `/home/user` |
| **`CLAUDE.md` / `AGENTS.md`** | the repo's | every repo's, all at once |
| **Repo skills (`.claude/skills/`)** | the repo's | every repo's; on a name clash (`eli5` in the umbrella and ww3-gpu) one wins, unstated which |
| **Plugins from `.claude/settings.json`** (superpowers, skill-creator, marola-devkit) | yes, after the trust dialog and `claude plugin install` | **no** |
| **Plugins and skills enabled on the claude.ai account** | no | yes (synced into the container) |
| **Hooks, permissions, `attribution` from the repo** | yes | **no** (cwd is not a repo; hooks also need a single-repo session) |
| **`nix develop`, `just`, devkit tools** | yes | **no**: Nix is installed, but `nix develop` fails fetching flake inputs (the proxy answers 403 for GitHub repos not in the Project); run a gate's script directly (`python3 <devkit>/scripts/docs_lint.py`) |
| **ultrareview** (`/code-review ultra`) | yes, signed in with claude.ai; 3 free runs on Pro/Max, then usage credits | **no** slash command to type; the built-in `code-review` skill at `max` runs a local review instead (inferred: cloud fallback not tried) |
| **`/clear`, `/rename`, `/plugin`, `/resume`** | yes | no: a new thread replaces `/clear` |
| **Parallel work** | worktrees, one session each | one thread per task, started by the coordinator |
| **Watching a PR, fixing CI and review comments** | by hand | built in: a thread subscribes to its PR and wakes on CI and reviews |
| **Recurring work** | `/loop`, cron on the host | routines (triggers) |
| **Cost accounting (`cost-split`)** | reads `~/.claude/projects` on the machine | the logs live in a container that is reclaimed; `Cost:` is an estimate (inferred) |
| **Shared files and outputs** | the checkout | `/mnt/project-files`, artifacts, the thread |

## 4. Data sources and dependencies reviewed

### 4.1 This session (a Project thread, 2026-10-10)

- Skill list: every repo's `.claude/skills/` (`site-frontend`, `eli5`, `figure`, `corpus-doc`, …)
  and the claude.ai account skills (`mip`, `mip-tasks`, `mip-solve-perpetual`); no `superpowers:*`,
  no `marola-devkit:*`, no `skill-creator` plugin (verified: the session's own skill list).
- `ListPlugins`: `{"results":[]}`, no plugin enabled on the account (verified).
- `SearchPlugins "superpowers"`: Superpowers by Jesse Vincent (`obra`, partner tier) is in the
  claude.ai *Anthropic Directory*, `enabled: false`, 15 skills and a `SessionStart` hook (verified).
- `~/.claude/plugins/known_marketplaces.json` lists only `claude-plugins-official`; its
  `marketplace.json` at `b8e53f1` has `superpowers`, `skill-creator` and `code-review` entries
  (verified). No `marola-devkit` marketplace was added.
- The working directory is `/home/user`, which has no `.claude/`; the commit attribution the harness
  prescribes carries a model name and a `Claude-Session:` line, not the repo's setting (verified).
- On `PATH`: `nix`, `gh`, `ruff`; not `just`, `shellcheck`, `cost-split`, `ai-jail` (verified).
  `nix develop -c just quality` in the umbrella failed: `unable to download
  'https://github.com/numtide/flake-utils/archive/…tar.gz': HTTP error 403`, the proxy refusing a
  repo outside the Project (verified).

### 4.2 Claude Code documentation (fetched 2026-10-10)

- `code.claude.com/docs/en/ultrareview`: `/code-review ultra` (alias `/ultrareview`, or
  `claude ultrareview`) runs a fleet of reviewers in a cloud sandbox, 5 to 10 minutes; needs a
  claude.ai login; not on Bedrock, Vertex AI, Foundry, ZDR or HIPAA orgs, where it falls back to a
  local review; Pro and Max get 3 free runs per account, then usage credits, typically $5 to $25 a
  review; up to 500 files and 8,000 changed lines. No web or Project trigger is described.
- `…/plugins/install`, `…/plugins/loading`: plugin scopes user, project, local; a committed
  `enabledPlugins` "turns the plugin on for your collaborators but doesn't download it"; a cloud
  session "doesn't load ... the ones your repository's `.claude/settings.json` turns on", and does
  not add `extraKnownMarketplaces` (no trust dialog in the cloud).
- `…/cloud-environments`: repo `.claude/skills/`, `agents/`, `commands/`, `CLAUDE.md`, rules and
  `.mcp.json` load; repo plugins and marketplaces do not; `SessionStart` hooks run only in a session
  with one repository; `~/.claude/*` from a machine never loads.
- `…/skills`: skill locations and precedence; cloud sessions load the clone's `.claude/skills/` and
  the skills enabled on claude.ai.
- `…/claude-code-on-the-web`: `/plugin`, `/resume` and `/clear` are unavailable in the cloud.
- `…/claude-projects`: a Project is "one ongoing conversation where Claude coordinates a stream of
  related work", one thread per task, usually a cloud session, optionally on the user's machine
  through Remote Control; public beta on Pro and Max, not Team or Enterprise yet.

### 4.3 The repo (read 2026-10-10)

- `marola/.claude/settings.json` enables `superpowers@claude-plugins-official`,
  `skill-creator@claude-plugins-official` and `marola-devkit@marola-devkit` (`v0.8.3`), and sets
  `attribution`.
- `AGENT-SKILLS.md` §2 and `DEV-FLOW.md` §2–§5 lean on superpowers skills without saying they exist
  only in the CLI.
- The synced account `mip` skill tells the agent to number from `docs/mips/README.md` and read
  `docs/ARCHITECTURE.md` and Azure rules; the repo's `docs/MIPs/TEMPLATE.md` says "this file is the
  shape, and it wins", which is what this MIP followed.

**Pick:** no new dependency. Superpowers comes from the claude.ai directory for Projects and from
`claude-plugins-official` for the CLI, the same upstream (`obra/superpowers`).

## 5. Design

### 5.1 `docs/3-Ways-of-working/CLAUDE-CODE.md`

A page in the umbrella, H1 "Claude Code: CLI or Project", under 150 lines:

1. **Pick a surface**: the §3 table, its top two rows first. Rule of thumb: code that must run on a
   machine (Docker, Ollama, the GPU runner, `just e2e`, the jail) is CLI work; a MIP, a doc, a
   cross-repo plan, a PR to drive to green, or anything asked from a phone is a Project thread.
2. **What a Project thread does not have, and what it does instead**:
   - no repo hooks or permissions, and no `nix develop`: run the gates' scripts directly before a
     push (`python3 ../marola-devkit/scripts/docs_lint.py`, `python3 scripts/mip_graph.py --check`),
     and say in `Tested:` which gates could not run;
   - no repo `attribution`: write the three trailers the org requires; the harness's
     `Claude-Session:` line stays out (the same rule `AGENTS.md` states);
   - no repo plugins: superpowers and skill-creator come from the account (task 3); marola-devkit's
     skills come through MIP-0084's vendoring, or as account skills kept at the devkit tag;
   - no `/ultrareview`: ask the thread for `code-review` at `max`, or run `/code-review ultra` from
     the CLI on the PR's branch when a paid review is worth it;
   - branch names: `docs/mip-NNNN-<slug>` or `claude/<issue>-<slug>`, never `claude/project-thread-*`
     (the devkit's `branch` check).
3. **What a CLI session does not have**: the coordinator, thread-per-task, PR wake-ups and
   `/mnt/project-files`; use worktrees and `/loop` instead.
4. **Ultrareview**: when it is worth $5 to $25 (a MIP's whole stack before Accepted→Implemented, a
   safety-relevant scoring change), and that MIP-0011 used one free run.
5. **Re-verify**: the commands of §7, with the date this page was last checked.

### 5.2 `AGENT-SKILLS.md` and `DEV-FLOW.md`

§2's "declared in the repo" paragraph gains one sentence: in a Claude Project or any cloud session,
repo-declared plugins do not load; superpowers there is the account's (CLAUDE-CODE.md). `DEV-FLOW.md`
links CLAUDE-CODE.md once, where it first names superpowers.

### 5.3 What stays deterministic

Everything; there is no code. Which surface loads what is a fact from §4, re-checked by §7.

### 5.4 Rows

| # | What | Where | Who |
|---|---|---|---|
| 1 | `CLAUDE-CODE.md` as §5.1, linked from `CONTRIBUTING.md` and `AGENT-SKILLS.md` | umbrella | agent |
| 2 | the §5.2 corrections | umbrella | agent |
| 3 | enable Superpowers (and skill-creator, if listed) on the claude.ai account; replace the account's stale `mip` and `mip-tasks` skills with the devkit tag's copies, or remove them once MIP-0084 vendors the devkit skills | claude.ai settings | Hoffmann |

## 6. Scoring / safety impact

None.

## 7. Verification plan

- `just quality` in the umbrella (`docs-lint`, links) and the strict docs build on the PR.
- In a new Project thread after task 3: the skill list shows `superpowers:*` (verified by asking
  the thread to list its skills); `ListPlugins` returns Superpowers.
- In a CLI session in the umbrella: `/plugin` lists superpowers, skill-creator and marola-devkit as
  installed.
- Done: the page is on docs.marola.dev, `AGENT-SKILLS.md` no longer says a repo setting enables
  superpowers everywhere, and a new thread lists superpowers.

## 8. Risks, limitations, and honest caveats

- Projects is a beta; rows of the table will move. The page carries its check date and §7's
  commands, not a promise.
- "Inferred" rows (ultrareview from a thread, cost accounting)
  were not exercised in this session; the page keeps the mark until someone runs them.
- Enabling superpowers on the account loads its `SessionStart` hook and fifteen skills into every
  Project thread and every claude.ai/code session of that account, also for non-marola work.
- Account skills are a second copy of the devkit's; they drift (the `mip` skill already has). The
  lasting fix is MIP-0084's vendoring, not this page.

## 9. Alternatives considered

- **Do nothing.** Threads keep assuming the CLI's setup; the `attribution` and hook gaps show up
  only in review, if at all.
- **Fold it into `AGENT-SKILLS.md`.** That page is about skills; surfaces, hooks, attribution and
  ultrareview do not belong there, and it is already long.
- **Make Project threads start inside one repo** so its settings and hooks load. Not a setting a
  Project exposes today (the container clones every configured repo under `/home/user`); worth
  revisiting if it becomes one.
- **Vendor superpowers into each repo's `.claude/skills/`.** Fifteen skills times six repos, a
  weekly update PR each (MIP-0080); the account toggle does the same for Projects with no copy.

## 11. Open questions

- Should Projects be the default surface for MIPs? **Default:** yes for writing and revising a MIP
  and for driving its PRs; implementation tasks that need Docker or Ollama stay in the CLI.
  Hoffmann decides.
- Should the account carry marola-devkit's skills at all before MIP-0084 lands? **Default:** keep
  `mip` and `mip-tasks`, refreshed from the devkit tag, because threads write MIPs often; drop them
  when the vendored copies arrive. Hoffmann decides.
- Is a paid ultrareview part of any gate? **Default:** no, on request only, like the Gemini review
  (MIP-0072).

## Appendix

### Checked live

- `ListPlugins`, `ListSkills`, `SearchPlugins "superpowers"` in this thread, 2026-10-10: as §4.1.
- `~/.claude/plugins/marketplaces/claude-plugins-official/.claude-plugin/marketplace.json` at
  `b8e53f1`, 2026-10-10: `superpowers`, `skill-creator`, `code-review` present.
- https://code.claude.com/docs/en/ultrareview, …/plugins/install, …/plugins/loading,
  …/cloud-environments, …/skills, …/claude-code-on-the-web, …/claude-projects, 2026-10-10: as §4.2.

### Not checked

- Running `/code-review ultra` from a Project thread (it may fall back to a local review).
- Whether a plugin enabled on the claude.ai account loads in the CLI too.
- Whether adding the flake inputs' repos to the Project (or a Nix binary cache) makes `nix develop`
  work in a thread.
- Team and Enterprise terms for ultrareview.
