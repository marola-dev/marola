# The split

Until 2026-10-02 marola was one repo, `marola-dev/marola`, holding the Scala app, the map, the
corpus, the Python tooling and the team's docs. [MIP-0070](../MIPs/MIP-0070-umbrella-and-polyrepo-split.md)
split it into single-purpose repos under an umbrella. This page is the record of why, what was
decided and what it cost; how the repos fit together today is [Repos](REPOS.md).

## Why

One tree forced one build and one set of gates on everything. `site.yml` ran `sbt` to build the
boards, so a CSS change to the map waited on a Scala compile. `docker-local.yml` fired on the
corpus, the fine-tune's `Modelfile` and the app's resources at once, and `ci.yml` path-filtered six
directories to avoid running everything. Parallel sessions each needed their own worktree (30+ at
the time) to stay out of each other's way, and the planned OODS data tree would have added daily
bot commits to the code's history.

The monorepo's one real advantage was that an agent saw everything at once. The umbrella keeps it:
checked out with submodules, the whole workspace is one directory again.

## The decisions and what they beat

| Decision | What it beat, and why |
|---|---|
| **An umbrella with every repo as a submodule**, keeping `marola-dev/marola` as the umbrella | Keeping `marola-dev/marola` as the Scala app with a new umbrella beside it: the issues, the MIP history and every `#N` reference would have had to move or keep pointing at a code repo |
| **OODS split into code and data**: the ingest code stays in marola-app, `data/oods/` goes to marola-oods | Moving the code with the data: the OODS module depends on the app's `local` module, and the Scala modules are not published (next row). Daily data commits stay out of the code's history |
| **Contracts instead of paths**: a producer publishes a versioned artifact, a consumer pins it | Reading a sibling's tree inside the umbrella checkout: no repo could then build or test on its own, and a break would surface far from its cause |
| **No published Scala libraries**: `core`, `local` and `cli` stay one sbt build | Full modularity with semver: every cross-module change would take two PRs and a release |
| **Aggregated docs**: one site at docs.marola.dev, built by the umbrella from every repo | Per-repo sites, or per-repo `mkdocs.yml` (MIP-0070 §5.5): seven theme configs to keep in step. MIP-0074 then made the umbrella README the landing and left each repo its low-level docs |
| **The devkit as a flake input, a plugin marketplace and reusable workflows, pinned to a tag** | Requiring the umbrella for shared tooling (a single-repo clone could not run `just pr`); vendoring the files with a sync bot (the copy-and-drift an earlier split out of a shared repo had already hit); the devkit as a submodule (a second pin besides `flake.lock`) |
| **Issues per repo, coordinated on the org Project** | Every issue in the umbrella: `Closes` would always cross repos, and a repo's own backlog would be invisible from it |
| **One fine-grained PAT** for dispatches, `site-data` pushes and pointer sync | A GitHub App: scoped per repo and with no personal owner, but more setup. Revisit if the user-tied token becomes a problem |

## What moved where

| Repo | Took |
|---|---|
| umbrella | `AGENTS.md`, `CLAUDE.md`, the READMEs, `PHILOSOPHY.md` and the health files, rewritten for the workspace; `docs/3-*`, `docs/4-*`, `docs/MIPs/`; `mkdocs/`; the docs aggregator and pointer sync; `repo_stats`, `arxiv_digest`, `awesome_agentic_digest`, `mip_graph`, `gh-billing` |
| marola-devkit | The dev-flow scripts (`stack`, `uprd`, `pr`, `cost-split`, `issues`, the label tools) and `scripts/lib/`; the git hooks and Claude Code hooks; the generic skills and agents; the PR template, labels and issue forms; the runner scripts; the base flake and `just` module |
| marola-app | The sbt build and its sources, the OODS module and its ingest workflow, the Dockerfile and compose file, the Scala CI, `docker.yml`, `docker-smoke.yml`, `marola-e2e.yml`, Scala Steward, the Scaladoc job, `docs/1-*` and `docs/2-*` (since split again by MIP-0074) |
| marola-site | The static map, `site.yml`, `site-health.yml`, the `site-data` branch, the site checks, the `site-frontend` skill |
| marola-corpus | The knowledge documents, the `corpus-doc` and `eli5` skills |
| marola-ml | The DSPy compile, the fine-tune, `Dockerfile.local`, `docker-local.yml`, `marola-sea-publish.yml`, the pdoc job, the benchmark gate and `docs/benchmarks/` |
| marola-oods | `data/oods/` |

Each extraction kept its history (`git filter-repo --path … --replace-message`, a bare `#N`
rewritten to `marola-dev/marola#N`), and the open issues for each area moved with
`gh issue transfer`.

## Timeline

| Date | PR | Step |
|---|---|---|
| 2026-09-30 | [#521](https://github.com/marola-dev/marola/pull/521) | MIP-0070 |
| 2026-09-30 | [#574](https://github.com/marola-dev/marola/pull/574), [#576](https://github.com/marola-dev/marola/pull/576), [#577](https://github.com/marola-dev/marola/pull/577) | Prep inside the one tree: `--site` writes board data only; the app reads knowledge from one directory; ml reads the resources tarball |
| 2026-09-30 | [#578](https://github.com/marola-dev/marola/pull/578), [#579](https://github.com/marola-dev/marola/pull/579) | The tooling resolves MIPs from the umbrella; the invariants block |
| 2026-10-01 | [#580](https://github.com/marola-dev/marola/pull/580) | Issues per repo |
| 2026-10-01 | marola-devkit `v0.1.0`, [#585](https://github.com/marola-dev/marola/pull/585) | The devkit extracted, then consumed as a flake, plugin and workflows |
| 2026-10-01 | [#586](https://github.com/marola-dev/marola/pull/586), [#589](https://github.com/marola-dev/marola/pull/589) | The docs aggregator; marola-site extracted, taking Pages and `marola.dev`, while docs move to docs.marola.dev |
| 2026-10-01 | [#591](https://github.com/marola-dev/marola/pull/591) | marola-corpus |
| 2026-10-02 | [#593](https://github.com/marola-dev/marola/pull/593) | marola-ml |
| 2026-10-02 | [#597](https://github.com/marola-dev/marola/pull/597), [#598](https://github.com/marola-dev/marola/pull/598) | marola-app; marola-oods joins, empty |
| 2026-10-02 | [#599](https://github.com/marola-dev/marola/pull/599) | The umbrella finished: only the team layer left, pointer sync on |

The docs followed in MIP-0074 ([#603](https://github.com/marola-dev/marola/pull/603)), which made
the umbrella the site's landing and moved each repo's internals into its own pages.

## What was given up

- **One PR for a cross-cutting change.** A board-schema change is now two PRs and an image bump.
  The Scala modules stayed together so a core change does not pay this.
- **Seeing a break where it is made.** A producer's change fails in its consumers, later, when they
  bump the pin; a renamed page fails only the umbrella's next docs build.
- **Old links into moved paths.** GitHub links such as `marola-dev/marola/blob/main/core/…` broke;
  redirects cover the docs site only.
- **One issue list.** Issues are spread over seven repos; the org Project is the one view, and an
  issue not on it is invisible to the queue.
- **Submodule ergonomics**: detached heads, pointer noise, a forgotten `--recurse-submodules`.
- **An owner-free credential.** The cross-repo PAT is tied to the person who created it.
