# Contributing to marola

marola is a one-person repo where AI coding agents (Claude Code and others) do most of the work
under human direction. Agents are first-class contributors here: read
[`AGENTS.md`](./AGENTS.md) before writing, modifying, or deploying anything: it's the actual
rulebook (phase discipline, cost/deployment safety, the commit trailer, code style, testing), not
a suggestion. Humans should read it too.

## Run it

```bash
nix develop && just ollama-up && just build && just test && just quality && just run -- --brief
```

Full walkthrough: [`docs/1-Using-marola/RUN-LOCALLY.md`](./docs/1-Using-marola/RUN-LOCALLY.md). No Telegram token, no cloud
account, no API key needed for any of the above.

## Find something to work on

Everything claimable is a GitHub issue carrying **`agent-ready`**: someone has already decided what
done means for it and which test proves it, so you can start without asking anyone. In a browser,
that is <https://github.com/marola-dev/marola/issues?q=is:open+label:agent-ready>. From the
terminal, after `gh auth login` once (every command below reads the GitHub API and fails without
it):

```bash
just issue-queue      # the unassigned agent-ready issues, smallest first
just issue-claim <n>  # assigns it to you, drops the label, prints the branch command
```

The **Good first issues** view on the [Marola board](https://github.com/orgs/marola-dev/projects/1)
is the same list, narrower. If that link 404s for you, the board is not public yet; the issue URL
above always works.

Then branch. Names are `<type>/<slug>` — `fix/queue-sort-order`, `docs/issue-flow`, `feat/…`,
`chore/…`, `ci/…` — unless the issue came from a MIP's task list, in which case `issue-claim`
prints the `scripts/stack.sh start` line to use instead. Write the failing test first, then open
the PR:

```bash
just build && just test && just quality && just pr
```

## Opening an issue

Pick the tier; the form follows from it, and blank issues are off.

- **Tier 1 — bug, chore, docs.** One issue, no milestone, no spec: **Bug report** for something
  broken, **Task** otherwise.
- **Tier 2 — small enhancement** (≈ two tasks or fewer, no new dependency). One issue **whose body
  is the spec**: **Story**. No design doc.
- **Tier 3 — initiative** (a new data source, a scoring change, a new integration, anything paid).
  **MIP proposal** → MIP PR → accepted → milestone → issues.

Fill in the acceptance criteria and the named test — a bug report asks for the same two things
under **What you expected instead** and **Failing test**. Those two fields, plus an `area/*`, a
`layer/*` and a `size/*` label, are what a maintainer's `just issue-ready <n>` checks before the
issue becomes claimable; without them it stays in triage. The whole standard — the commands, the
board, the dependency edges — is [`docs/3-Working-on-the-repo/ISSUE-FLOW.md`](./docs/3-Working-on-the-repo/ISSUE-FLOW.md).

## The dev loop

Idea → **issue** → **MIP** (`docs/MIPs/`, via the `mip` skill) → acceptance → task list →
stacked PRs (one task, one branch, one PR) → review on request → merge/restack → done. The whole
loop, with the exact commands, is [`docs/3-Working-on-the-repo/DEV-FLOW.md`](./docs/3-Working-on-the-repo/DEV-FLOW.md). Skip the MIP for bug
fixes, doc corrections, and behaviour-free refactors; everything else that changes scoring, a data
source, or what a user sees goes through one first.

- **Small PRs, one topic.** `scripts/stack.sh` and `just uprds` exist so a MIP ships as several
  reviewable PRs instead of one large one.
- **Every commit ends with three trailers and nothing else:** `Tested: gates, e2e — <not run,
  why>`, `Cost: ~$… · … tokens · …` (from `just cost-split`) and
  `Co-Authored-By: Claude <noreply@anthropic.com>`; no session links, no "Generated with"
  banners (`AGENTS.md`, "Attribution and cost accounting").
- **The PR body is generated from those commits** by `just uprd` / `just uprds`
  (`.github/PULL_REQUEST_TEMPLATE.md` has the shape): Summary from the first commit's body,
  the Tested and Cost rows from the trailers. Write the commit right and there is nothing to fill.
- **`just build && just test && just quality`** must be green before a PR is opened (`quality` =
  scalafmt + scalafixAll + ruff + actionlint + hadolint on the Dockerfiles + the Python scripts'
  self-tests: the same gates `ci.yml` runs). Dependency freshness: Scala/sbt deps are watched by
  `scala-steward.yml` (weekly PRs); GitHub Actions and the two Python requirements files by
  `.github/dependabot.yml`: Mondays and Fridays at 09:00 America/Sao_Paulo, as a single PR
  covering all three (a `multi-ecosystem-group`, which is the only grouping that spans update
  entries). `just deps-stack` is still there for the case where several arrive separately
  (`docs/3-Working-on-the-repo/DEV-FLOW.md` §6/§8). There is no API to trigger a Dependabot run: the
  button is Insights → Dependency graph → Dependabot → Check for updates, and pushing any change
  to `.github/dependabot.yml` forces one.

## Code style

Scala 3.9, direct style, deterministic scoring code kept out of the LLM's reach, `enum` +
exhaustive `match` over exceptions for expected failures; see `AGENTS.md`'s "Code style" section
for the full list and the reasoning in [`PHILOSOPHY.md`](./PHILOSOPHY.md).

## License

MIT: see [`LICENSE`](./LICENSE). By contributing you agree your changes are licensed under it.
</content>
