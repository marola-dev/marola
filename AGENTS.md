# AGENTS.md

Instructions for any AI coding agent working in the marola umbrella (Claude Code or otherwise).
Read this before writing, modifying, or deploying anything. Humans should read it too.

<!-- invariants:start -->
## Org invariants

Non-negotiable in every marola repo; a repo may make these stricter, never looser (MIP-0070 §5.1).

- **Cost and deployment safety**: never provision or deploy a paid cloud resource without explicit human confirmation first ([AGENTS.md](AGENTS.md#cost--deployment-safety-hard-rule)).
- **No secrets in code**: never hardcode a key/connection string/secret; `.env.example` holds placeholders only ([AGENTS.md](AGENTS.md#cost--deployment-safety-hard-rule)).
- **The agent-ready gate**: an agent may only begin implementation on an issue carrying `agent-ready` ([AGENTS.md](AGENTS.md#issue-tracking-hard-rule)).
- **The three commit trailers**: commits carry three trailers and nothing else — `Tested:`, `Cost:`, and `Co-Authored-By: Claude <noreply@anthropic.com>` ([AGENTS.md](AGENTS.md#attribution-and-cost-accounting-hard-rule)).
- **Phase discipline**: work one phase at a time; never start a later phase before the current one is done ([AGENTS.md](AGENTS.md#phase-discipline-hard-rule)).
<!-- invariants:end -->

## What this umbrella is

**marola** is the ocean intelligence layer for a stretch of coast: real nearby beaches
(OpenStreetMap), live sea and weather (Open-Meteo), official bathing-water quality, a 0–100
swimability score with a deterministic safety veto, and an LLM summary reviewed by a second pass,
all runnable locally with a free Ollama model. GCP (MIP-0057) is the opt-in cloud path.

This repo, `marola-dev/marola`, is the **umbrella** (MIP-0070): the team layer, with every code
repo as a git submodule. It holds this file, the ways of working (`docs/3-Ways-of-working/`),
research and plans (`docs/4-Research-and-plans/`), the MIPs and their `.tasks.md`, the phase list
(`docs/PHASES.md`), MIP and cross-repo parent issues, the aggregated docs site at
<https://docs.marola.dev/>, and the submodule pointers. **It holds no code**: no build, no app
gates. [PHILOSOPHY](docs/3-Ways-of-working/PHILOSOPHY.md) holds the reasons behind the rules
here, and [CONTRIBUTING](docs/3-Ways-of-working/CONTRIBUTING.md) is the guide for every repo.

## The repos

Each repo has its own `AGENTS.md`, `docs/`, gates and issues. Read that repo's `AGENTS.md` before
changing anything in it; it says what it overrides. What each repo owns, publishes and pins is in
[`docs/2-Building-marola/REPOS.md`](docs/2-Building-marola/REPOS.md), the routing table's only copy
(`scripts/agents_repos_check.sh` fails if this file restates it). No repo reads another repo's
tree, in CI or in tests, and no consumer builds its producer from source: a consumer moves to a new
producer version by bumping its pin in a PR.

## Where a change belongs

- **One repo**: work in that repo, under its `AGENTS.md`, and run its gates there. Code, its docs,
  its workflows and its issues live together. A change to how the team works, a MIP, or the phase
  list belongs here.
- **Across repos** (a contract changes): plan the contract first, in a MIP if it is non-trivial.
  Use the same branch name in every repo, one PR per repo, each linked to the others. The producer
  merges and releases first, the consumers bump their pins after, and the umbrella's pointers move
  last, through the sync PR.
- **Issues**: one issue per PR, in the repo the PR lands in, so `Closes #N` stays local. Cross-repo
  work is an umbrella parent issue with a sub-issue in each target repo, and every issue sits on the
  org Project (`marola-dev` Project 1). A reference to an issue in another repo is always fully
  qualified (`marola-dev/marola-app#15`). [ISSUE-FLOW](docs/3-Ways-of-working/ISSUE-FLOW.md) has the
  tiers and the Definition of Ready.

[WORKING-ACROSS-REPOS](docs/3-Ways-of-working/WORKING-ACROSS-REPOS.md) has the whole of it, with
which checkout each recipe needs.

## Submodule mechanics

- Clone with `git clone --recurse-submodules https://github.com/marola-dev/marola`. In an existing
  clone, `git submodule update --init` checks every repo out at its pinned commit.
- A submodule is checked out on a detached HEAD at the pinned commit. To work in one, `cd` into
  it, `git switch -c <branch> origin/main`, and push and open the PR from there, against that repo.
- **Pointers move only through the sync PR.** `pointer-sync.yml` moves each submodule to its
  default branch's tip and keeps one rolling PR open on `chore/pointer-sync` (daily, and on a
  `submodule-updated` or `submodule-docs-updated` dispatch). Never commit a pointer change by hand,
  and never commit inside a submodule from the umbrella's own branch.
- `docs.yml` builds the docs from each submodule's latest `main` regardless of the pointers;
  `ci.yml`'s `docs-build` builds a PR on its pinned commits.
- Adding a repo is [NEW-REPO](docs/3-Ways-of-working/NEW-REPO.md)'s checklist.

## Setup & commands

```bash
git clone --recurse-submodules https://github.com/marola-dev/marola
nix develop      # just, python3, ruff, gh, hadolint, actionlint (flake.nix); Docker is the host's
just             # list the recipes
just quality     # this repo's gates
just docs        # the aggregated docs (docs/ + every submodule's) into mkdocs/generated-docs
just docs-serve  # preview on http://localhost:8001/
```

Run the gates of the repo you changed before calling a change done: here `just quality`
(`quality-other`: ruff, actionlint, hadolint, `docs-lint` and the `scripts/*` self-tests, the
gates `ci.yml` runs, at the versions `flake.lock` pins; a missing tool fails rather than skips, so
use `nix develop`); in a submodule, its own `AGENTS.md` names them. The pre-push hook runs
`just prepush`; `git push --no-verify` bypasses it, CI does not.

**Writing a doc is a deploy.** mkdocs builds the sidebar from the file tree (the `1-`…`6-`
prefixes order it), and the build is `--strict`: a link that does not resolve inside the
aggregated tree fails it. `scripts/prepare-docs.sh` puts this repo at the root, with `README.md` as
the landing page and `docs/MIPs/` at `6-MIPs/`, and every repo in `mkdocs/repos.yml` at
`5-Repos/<name>/` (marola-devkit fetched at `flake.lock`'s locked rev), each README its landing and
its `api-docs` branch under `api-docs/`. No repo has a `docs/index.md`. Links are rewritten for the
site (MIP-0074 Appendix A), so write them relative inside a repo and absolute across repos. A page
that moves gets a `redirect_maps` entry in `mkdocs/mkdocs.yml`. A push to `main` touching
`docs/**`, `mkdocs/**`, `README.md`, `flake.lock` or the docs scripts, a submodule's docs dispatch,
or the daily cron runs `docs.yml`, which deploys docs.marola.dev. `docs/MIPs/CANDIDATES.md` indexes
what is MIP material; `.claude/rules/docs.md` has the MIP status vocabulary, and
[DOCS-SITE](docs/3-Ways-of-working/DOCS-SITE.md) how the site is built.

The harness is [marola-devkit](https://github.com/marola-dev/marola-devkit), a flake input pinned
to a tag: its tools on `PATH` (`stack`, `uprd`, `pr-flow`, `issues`, `cost-split`, `cost-fill`,
`agents-check`, …), their recipes (`import? '.devkit/devkit.just'`), the git hooks, the reusable
workflows and the `marola-devkit` plugin (`/marola-devkit:mip` and the other generic skills). Bump
the flake input, every `@v…`/`devkit-ref` in `.github/workflows/` and the marketplace `ref` in
`.claude/settings.json` together. The shellHook links this worktree's `.devkit` and points
`core.hooksPath` at it; a worktree made outside `nix develop` and `just worktree` needs
`just devkit-link` once. OpenCode does not load Claude Code plugins: the skills are plain
`SKILL.md` files under `.devkit/plugins/marola-devkit/skills/`.

`just context-mips` packs what a browser session needs to draft a MIP from voice notes, and
`just context-mip MIP-NNNN` packs one MIP for a reviewer outside this project's coding agent.

## Phase discipline (hard rule)

Work **one phase at a time**, per `docs/PHASES.md`: do not start Phase 2 (going live on a cloud
backend, GCP per MIP-0057) before Phase 1 (the Telegram bot actually working) is done. This exists
to prevent an expensive mistake. If asked to jump ahead, implement the requested feature but flag
which earlier-phase prerequisite is still missing. Every repo follows this list; it lives here.

## Issue tracking (hard rule)

**An agent may only begin implementation on an issue carrying `agent-ready`.** That label is the
only statement that a human has decided what done means and what proves it; `just issue-ready <n>`
adds it when the five-rule Definition of Ready passes. Read the queue with `just issue-queue` (it
spans `org:marola-dev`), take one with `just issue-claim <n>`. An idea with no issue is not work
yet, and **filing is a human's act**: the `/marola-devkit:triage` skill drafts, a person files
(MIP-0063 §5.6).

## Cost & deployment safety (hard rule)

**Never provision or deploy a paid cloud resource without explicit human confirmation first.**
Propose the change, state the expected cost, wait for a go-ahead. Never hardcode a key/connection
string/secret; `.env.example` holds placeholders only. ai-jail limits what a session can reach, but
it does not replace this rule.

## Attribution and cost accounting (hard rule)

- **Commits carry three trailers and nothing else:** `Tested:`, `Cost:` and
  `Co-Authored-By: Claude <noreply@anthropic.com>`. No session links, no "Generated with" banners,
  no PR-body attribution; `.claude/settings.json` enforces this for Claude Code. Automation commits
  carry them too (`pointer-sync.yml`: `Cost: n/a (automation)`).
- **The PR workflow is one command.** Write the commit (a body plus `Tested:`/`Cost:`), then
  `just pr`: it fills a missing trailer (`just cost-fill`), pushes, and writes the PR from the
  template (`just uprd`, or `stack pr` on a `mip-NNNN/k-*` branch). `Cost:` prefers
  `just cost-split`'s measured figure over `cost-split --estimate`'s diff-size fallback (`est.`).
- **One feature, one session.** Start a feature with `/clear` and `/rename` it to the branch name
  so the usage logs map to one PR. For a MIP stack, `just cost-split MIP-NNNN` attributes usage to
  each commit by time, across repos, and `just uprds MIP-NNNN` refreshes each PR's Cost section.

## Code style

Every repo's style rules are its own (marola-app's Scala and Kyo rules are its
`.claude/rules/scala.md`). For every language: prefer `enum` + exhaustive matching over exceptions
for expected failure modes, and reproduce a bug with a failing test before fixing it.

**Comments: write few, and only what the code cannot say.** Agents overshoot here badly: #280,
#284, #286 and #287 were four passes cutting comment lines roughly in half, and the verbosity grows
straight back unless review refuses it. A comment is one of:

- **why, not what**: a non-obvious decision, a rejected alternative, a constraint from outside the
  file (an API's behaviour, a licence, a version pin).
- **a trap**: something that will look like a bug, or bite the next reader, and is invisible here.
- **a pointer**: the MIP or issue that explains the shape, in one reference, not a summary of it.

Everything else belongs in the commit message, the MIP, or nowhere: do not restate the code, do not
narrate a fix's history in the file it fixed, do not re-explain a good name. A docstring longer than
its function is a defect. The same goes for prose in docs.

Prefer running agent tools through [ai-jail](https://github.com/akitaonrails/ai-jail), via
`just jail-claude` (or `jcf`/`jcs`/`jco`), over bare. It sandboxes the process; it doesn't replace
the rules above. A jail has no `gh` login of its own and cannot keep one (`~/.config/gh` is not
mapped in), so `jail-claude` resolves a token on the host (a fine-grained key in the gitignored
`.env` first, else `gh auth token`) and forwards it as `GH_TOKEN`: authenticate once on the host,
never inside the jail. `JAIL_CLIPBOARD=1` opts into a write-only clipboard bridge (`just clip`);
`JAIL_CLIPBOARD_PASTE=1` maps a real display socket for image paste, a much bigger grant (on X11,
any client on it can read other windows). On Ubuntu 24.04, `bwrap: setting up uid map: Permission
denied` means unprivileged user namespaces are blocked: fix it with a scoped AppArmor profile.

## Before implementing a feature

Check `docs/MIPs/` and `docs/4-Research-and-plans/FUTURE-WORK.md` first: the idea may already be
designed or decided. For a proactive or autonomous agent behaviour, keep the human-confirmation
gate unless a MIP decides otherwise.

## When something here turns out to be wrong

Update this file, `.claude/rules/*.md`, or the relevant doc in the same change. Rules files are
excerpts that link back here, not the only copy. This file is read by non-Claude agents too, so a
rule that matters everywhere stays stated here even when its detail lives in a repo's own
`AGENTS.md`.
