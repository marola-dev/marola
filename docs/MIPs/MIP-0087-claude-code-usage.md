# MIP-0087: Claude Code usage — when to use the CLI, when a Claude Project, and what each one loads

| | |
|---|---|
| **Status** | Draft |
| **Author** | Hoffmann |
| **Created** | 2026-10-10 |
| **Phase** | None: dev-loop guidance, outside `docs/PHASES.md`'s sequence. No Phase 1 prerequisite, no paid resource |
| **Related** | MIP-0076 (graphify and `graph.sh`), MIP-0011 (the Claude Code practices this repo enforces through `.claude/settings.json`), MIP-0080 (`skills.lock`), MIP-0084 (vendoring the `routing` skill so sessions without the plugin reach it), `docs/3-Ways-of-working/AGENT-SKILLS.md` §2 (superpowers), `DEV-FLOW.md`, #731/#732 (the project snapshot) |
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
| **Tasks** | none needed — four rows in §5.4, each one PR or one setting |
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
`docs/3-Ways-of-working/CLAUDE-CODE.md`, with which repo's work a thread can finish (most of
marola-site and the umbrella; none of marola-app's builds) and how to run graphify in each surface,
corrects `AGENT-SKILLS.md`, and turns on the account-level plugins a Project can actually use.

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
| **graphify** (`graph build`/`query`, MIP-0076) | from `nix develop` (nixpkgs 0.9.66), cache under `~/.cache` | not on `PATH`, and no `nix develop`; a scratchpad venv with PyPI `graphifyy` runs the devkit's `graph.sh` (§5.5) |
| **Docker, GHCR, the app image** | yes | the daemon starts by hand, but GHCR answers `unauthorized` and Docker Hub 429: no pinned image |
| **Live data (Open-Meteo, Overpass, agencies)** | yes | Open-Meteo fails TLS at the proxy; treat every live data path as CLI work |

### 3.1 Per repository

What a Project thread can finish on its own, from the checks in §4.4. "Gates" means the gates ran
in a thread; CI still runs the full set on the PR.

| Repo | Good fit for a Project thread | Needs the CLI | Gates that run in a thread |
|---|---|---|---|
| marola-site | **most work**: copy, i18n catalogs, news posts, `style.css`, `app.js`/`flow.js` changes, the chat widget, `about`/`support`/`news` pages, the before/after screenshots its AGENTS.md requires (Playwright's Chromium is installed; the map on a stand-in style, as AGENTS.md allows). The `marola-site-*` folders in the project files are earlier threads' screenshots | `just site-build` (Docker and the pinned image), the Brazilian proxy node (`ops/br-proxy/`), anything that needs a real Mapbox token | `node scripts/site_check.js` (ok), `python3 scripts/i18n_bundle.py --check`, `python3 scripts/news_build.py --check` (all ok) |
| marola (umbrella) | **most work**: MIPs and `.tasks.md`, ways-of-working docs, research, issue drafting, pointer-sync review, cross-repo plans; the submodules initialise in seconds | `just release`, `just docs-serve` previews | `docs_lint.py` from the devkit checkout, `mip_graph.py --check` (ok); the strict docs build in CI |
| marola-corpus | adding or fixing a document (the `corpus-doc` skill): fetch the source, write the Markdown, update `docs/4-reference.md` | trying a document with the app (`just ask`, which needs marola-app and Ollama) | `scripts/corpus-check.sh` (ok) |
| marola-oods | docs and the tree check; data lands only from marola-app's ingest | nothing an agent should run here | `scripts/oods-tree-check.sh` (ok) |
| marola-devkit | scripts and their `--self-test`, plugin skills, reusable workflows, docs | anything that needs `nix build` or a self-hosted runner | `bash tests/self-tests.sh` (all ok); `shellcheck` is missing |
| agent-skills | the audit pages, `data/*.json`, reviewing the queue | `just refresh` with a `GH_TOKEN` of its own | `refresh.py --self-test` (ok) |
| marola-ml | `--self-test` changes, docs, the benchmark gate's logic | training, `just benchmark`, `compile-prompt` (Ollama, GPU, paid calls, the app image) | `benchmark_gate.py --self-test` (ok) |
| marola-app | **reading and design only**: tracing code (graphify, `git grep`), a MIP's §5, review answers, docs pages; small Scala edits only if CI is the test | anything that compiles: the thread has JDK 21 (Kyo needs 25), no `sbt`, Maven Central answers 429, no Ollama | none locally; CI |
| awesome-ocean-science, awesome-open-climate-science, open-sustainable-technology, `.github` | **all of it**: list entries, issue forms, org profile | nothing | their CI |
| ww3-gpu | the Wave Forecaster project's repo; here only for `[A2A]` messages | Fortran, Kokkos, CUDA, the regtests | — |

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

### 4.4 Toolchains and network in a Project thread (2026-10-10, 20:45–20:50 UTC)

- `java -version`: OpenJDK 21.0.12; no `sbt` on `PATH`; `https://repo1.maven.org/maven2/` → 429.
- `dockerd` starts when run by hand; `docker pull` of marola-site's pinned
  `ghcr.io/marola-dev/marola-app:jvm-0c1e050@sha256:3ddf…` → `unauthorized`; `alpine:3` from
  Docker Hub → 429.
- `curl` to `api.open-meteo.com` → TLS `SSL_ERROR_SYSCALL`; `api.mapbox.com` and `huggingface.co` → 200.
- Node 22, Python 3 and Playwright's Chromium (`/opt/pw-browsers`) are installed.
- Ran and passed: marola-site `node scripts/site_check.js`, `i18n_bundle.py --check`, `news_build.py --check`; marola-corpus `scripts/corpus-check.sh`;
  marola-oods `scripts/oods-tree-check.sh`; marola-devkit `bash tests/self-tests.sh`; agent-skills
  `scripts/refresh.py --self-test`; marola-ml `scripts/benchmark_gate.py --self-test`.
- graphify: `python3 -m venv` + `pip install graphifyy` → 0.9.84 (PyPI is reachable). The devkit's
  `scripts/graph.sh build` on marola-site took 30 s (7,294 nodes, most of them the vendored Mapbox
  GL JS); `graph query "where are the wind particles drawn on the map"` found `particles()` at
  `site/static/flow.js:206`. In the umbrella, `graph build` first refused (`uninitialised
  submodule(s)`); after `git submodule update --init` (7 s) it built in 6 s (1,760 nodes) and
  `graph query "where is the swimability score computed"` found `Swimability.score` in
  `marola-app/core/…/scoring/Swimability.scala:216`.
- After the container resumed at 20:45, `skill-creator:skill-creator` appeared in the skill list and
  under `~/.claude/plugins/cache/claude-plugins-official/`, while `ListPlugins` still returned
  nothing and superpowers did not appear. What installed it was not established.

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
5. **Per repository**: the §3.1 table, so a contributor (or the coordinator) knows before
   starting whether a thread can finish the job.
6. **graphify**: §5.5's setup for both surfaces and three example queries.
7. **Re-verify**: the commands of §7, with the date this page was last checked.

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
| 3 | the §3.1 table and §5.5 in `CLAUDE-CODE.md`; once MIP-0084's bundle lands, replace §5.5's Project recipe with `route` | umbrella | agent |
| 4 | enable Superpowers (and skill-creator, if listed) on the claude.ai account; replace the account's stale `mip` and `mip-tasks` skills with the devkit tag's copies, or remove them once MIP-0084 vendors the devkit skills | claude.ai settings | Hoffmann |

### 5.5 graphify in each surface

graphify answers "where is X" in code an agent has not read (MIP-0076); MIP-0084 will put a
prebuilt graph on a `routing-bundle` branch so no session needs it installed. Until then:

- **CLI**: `nix develop` in the repo (or the umbrella), `graph build` once, then
  `graph query "<question>"`, `graph path <a> <b>`, `graph explain <name>`. The cache lives under
  `~/.cache/marola-graph/<repo>`, never in the checkout. Rebuild after a large pull.
- **Project thread**: no Nix shell, so
  `python3 -m venv "$SCRATCH/g" && "$SCRATCH/g/bin/pip" install graphifyy`, then
  `PATH="$SCRATCH/g/bin:$PATH" XDG_CACHE_HOME="$SCRATCH/cache" bash ../marola-devkit/scripts/graph.sh build`
  from the repo's directory. In the umbrella, run `git submodule update --init` first. The
  container is reclaimed, so the graph is rebuilt per thread (30 s for marola-site, 6 s for the
  umbrella).
- **Concerns**: the PyPI version is unpinned (0.9.84 here, nixpkgs has 0.9.66, upstream releases
  about daily), which is why MIP-0076 chose nixpkgs and MIP-0084 a CI-built bundle; use the venv
  only for reading code, never in a gate. Vendored code dominates small repos (marola-site's graph is
  mostly Mapbox GL JS), so ask by the name of your own file or function. The default 400-token
  budget truncates broad questions; narrow the question or pass `--budget`. Results are a starting
  point to read from, not an answer; `git grep` stays first for a known keyword.

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
- Building marola-app in a thread after installing JDK 25 and sbt by hand (Maven Central's 429 makes
  it unlikely to work).
- Whether the GHCR `unauthorized` goes away with the Project's GitHub credentials passed to
  `docker login`.
