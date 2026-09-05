# Contributing to marola

marola is a one-person repo where AI coding agents (Claude Code and others) do most of the work
under human direction. Agents are first-class contributors here: read
[`AGENTS.md`](./AGENTS.md) before writing, modifying, or deploying anything — it's the actual
rulebook (phase discipline, cost/deployment safety, the commit trailer, code style, testing), not
a suggestion. Humans should read it too.

## Run it

```bash
nix develop && just ollama-up && just build && just test && just quality && just run -- --brief
```

Full walkthrough: [`docs/RUN-LOCALLY.md`](./docs/RUN-LOCALLY.md). No Telegram token, no Azure
account, no API key needed for any of the above.

## The dev loop

Idea → **MIP** (`docs/mips/`, via the `mip` skill) → acceptance → task list → stacked PRs (one
task, one branch, one PR) → review on request → merge/restack → done. The whole loop, with the
exact commands, is [`docs/DEV-FLOW.md`](./docs/DEV-FLOW.md). Skip the MIP for bug fixes, doc
corrections, and behaviour-free refactors — everything else that changes scoring, a data source,
or what a user sees goes through one first.

- **Small PRs, one topic.** `scripts/stack.sh` and `just uprds` exist so a MIP ships as several
  reviewable PRs instead of one large one.
- **Every commit ends with exactly one trailer:** `Co-Authored-By: Claude <noreply@anthropic.com>`
  — no session links, no "Generated with" banners (`AGENTS.md`, "Attribution and cost accounting").
- **Every PR body ends with a `Cost` line** — what the work consumed in quota, from `/usage` or
  `just claude-cost` (`just cost-split` for a multi-PR session). `.github/PULL_REQUEST_TEMPLATE.md`
  has the shape; `just uprd` / `just uprds` fill in "What changed" and "Cost" from the commits.
- **`just build && just test && just quality`** must be green before a PR is opened (`quality` =
  scalafmt + scalafixAll + ruff + actionlint + hadolint on the Dockerfiles + the Python scripts'
  self-tests — the same gates `ci.yml` runs). Dependency freshness: Scala/sbt deps are watched by
  `scala-steward.yml` (weekly PRs); GitHub Actions and the two Python requirements files by
  `.github/dependabot.yml`.

## Code style

Scala 3.9, direct style, deterministic scoring code kept out of the LLM's reach, `enum` +
exhaustive `match` over exceptions for expected failures — see `AGENTS.md`'s "Code style" section
for the full list and the reasoning in [`PHILOSOPHY.md`](./PHILOSOPHY.md).

## License

MIT — see [`LICENSE`](./LICENSE). By contributing you agree your changes are licensed under it.
