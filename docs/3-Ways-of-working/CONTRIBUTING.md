# Contributing

marola is built mostly by AI coding agents (Claude Code and others) under human direction, across
an umbrella and its repos ([REPOS](../2-Building-marola/REPOS.md)). This guide holds for every
one of them. Agents are first-class contributors: read the umbrella's
[`AGENTS.md`](../../AGENTS.md), then the `AGENTS.md` of the repo you are changing, before writing,
modifying or deploying anything. They are the rulebook (phase discipline, cost and deployment
safety, the commit trailers, code style, testing), and humans should read them too. The reasons
behind the rules are [PHILOSOPHY](PHILOSOPHY.md).

## Run it

[RUN-LOCALLY](../1-Using-marola/RUN-LOCALLY.md) runs marola on your machine from a marola-app
checkout. No Telegram token, cloud account or API key is needed. Each repo's `3-development` page
has its own build and gates, for example
[marola-app's](https://docs.marola.dev/5-Repos/marola-app/3-development/).

## Find something to work on

Everything claimable is a GitHub issue carrying **`agent-ready`**, in whichever repo the work
lands in: someone has already decided what done means and which test proves it, so you can start
without asking anyone. In a browser, that is
<https://github.com/search?q=org%3Amarola-dev+is%3Aissue+is%3Aopen+label%3Aagent-ready&type=issues>.
From the terminal, after `gh auth login` once:

```bash
just issue-queue      # the unassigned agent-ready issues across the org, smallest first
just issue-claim <n>  # assigns it to you, drops the label, prints the branch command
```

The **Good first issues** view on the [Marola board](https://github.com/orgs/marola-dev/projects/1)
is the same list, narrower. If that link 404s for you, the board is not public yet; the search
above always works.

Then branch, in the repo the issue is in ([WORKING-ACROSS-REPOS](WORKING-ACROSS-REPOS.md) has how,
inside a submodule). Names are `<type>/<slug>` (`fix/queue-sort-order`, `docs/issue-flow`,
`feat/…`, `chore/…`, `ci/…`), unless the issue came from a MIP's task list, in which case
`issue-claim` prints the `stack start` line to use instead. Write the failing test first, run that
repo's gates, then open the PR:

```bash
just quality && just pr
```

## Opening an issue

Open it in the repo the fix will land in, so the PR's `Closes #N` stays local; work that spans
repos is an umbrella parent issue with one sub-issue per repo. Pick the tier; the form follows from
it, and blank issues are off.

- **Tier 1 — bug, chore, docs.** One issue, no milestone, no spec: **Bug report** for something
  broken, **Task** otherwise.
- **Tier 2 — small enhancement** (≈ two tasks or fewer, no new dependency). One issue **whose body
  is the spec**: **Story**. No design doc.
- **Tier 3 — initiative** (a new data source, a scoring change, a new integration, anything paid).
  **MIP proposal** in the umbrella → MIP PR → accepted → issues in the repos it touches.

Fill in the acceptance criteria and the named test; a bug report asks for the same two things
under **What you expected instead** and **Failing test**. Those two fields, plus an `area/*`, a
`layer/*` and a `size/*` label, are what a maintainer's `just issue-ready <n>` checks before the
issue becomes claimable; without them it stays in triage. The whole standard is
[ISSUE-FLOW](ISSUE-FLOW.md).

## The dev loop

Idea → **issue** → **MIP** (the umbrella's `docs/MIPs/`, via the `/marola-devkit:mip` skill) →
acceptance → task list → stacked PRs (one task, one branch, one PR) → review on request →
merge/restack → done. The whole loop, with the exact commands, is [DEV-FLOW](DEV-FLOW.md). Skip the
MIP for bug fixes, doc corrections and behaviour-free refactors; everything else that changes
scoring, a data source, or what a user sees goes through one first.

- **Small PRs, one topic.** `stack` and `just uprds` exist so a MIP ships as several reviewable PRs
  instead of one large one.
- **Every commit ends with three trailers and nothing else:** `Tested: gates, e2e — <not run,
  why>`, `Cost: ~$… · … tokens · …` (from `just cost-split`) and
  `Co-Authored-By: Claude <noreply@anthropic.com>`; no session links, no "Generated with"
  banners.
- **The PR body is generated from those commits** by `just pr` (`.github/PULL_REQUEST_TEMPLATE.md`
  has the shape): the summary from the first commit's body, the Tested and Cost rows from the
  trailers. Write the commit right and there is nothing to fill.
- **The repo's gates** must be green before a PR is opened; its `AGENTS.md` names them, and they
  are the gates its `ci.yml` runs.
- **Dependency updates.** dependabot watches GitHub Actions in the umbrella and marola-app, and
  GitHub Actions and pip in marola-ml, each as one grouped PR on Mondays and Fridays at 09:00
  America/Sao_Paulo; scala-steward opens marola-app's Scala and sbt bumps weekly. The devkit's
  pins are bumped by hand, since they move together. `just deps-stack` chains bumps that arrive
  separately ([DEV-FLOW](DEV-FLOW.md#dependency-prs)).

## Code style

Each repo's style rules are its own, in its `AGENTS.md` and `.claude/rules/`; marola-app's Scala
and Kyo rules are its `.claude/rules/scala.md`. For every language: `enum` and exhaustive matching
over exceptions for expected failures, a failing test before a fix, and few comments, each saying
only what the code cannot.

## License

Every repo carries an MIT `LICENSE`. By contributing you agree your changes are licensed under
it.
