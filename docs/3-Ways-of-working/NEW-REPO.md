# Adding a repo

The checklist for a new `marola-<name>` repo, in the order it is done. Each step names the file or
setting it changes. [marola-oods](https://docs.marola.dev/5-Repos/marola-oods/) is the smallest
example; it still lacks `flake.lock` and the `gh stack` denies (marola-dev/marola-oods#7). Creating
the repo on GitHub, and every setting under "GitHub settings", is a human's act.

## In the new repo

1. **`flake.nix`**: a `marola-devkit` input pinned to a tag
   (`github:marola-dev/marola-devkit/vX.Y.Z`, `inputs.nixpkgs.follows = "nixpkgs"`); the dev shell
   takes `marola-devkit.lib.${system}.tools` and its `shellHook`, plus
   `git config core.hooksPath .devkit/.githooks`. Commit **`flake.lock`**.
2. **`.gitignore`**: `.devkit`, `.tmp/`, `.claude/settings.local.json`. A
   **`.graphifyignore`** for vendored or generated code, which `just graph` would otherwise
   index ([DEV-FLOW](DEV-FLOW.md#the-code-graph-in-one-repo)).
3. **`justfile`**: `set allow-duplicate-recipes`, `import? '.devkit/devkit.just'`, and the three
   recipes the devkit's hooks call: `quality` (every gate CI runs, including `agents-check` and
   `docs-lint`), `precommit` (seconds) and `prepush` (the gates a push would fail on).
4. **`.claude/settings.json`**: `attribution` (the commit's `Co-Authored-By` line, an empty
   `pr`, `sessionUrl: false`); the `marola-devkit` marketplace with `ref` at the flake's tag and
   `enabledPlugins`; `permissions.deny` for `Read(.env)`, keys, `gh pr merge`, `gh pr close`,
   `gh stack merge`, `gh stack unstack` and `gh stack delete`.
5. **`CLAUDE.md`**: `@AGENTS.md`, and anything Claude Code-only.
6. **`AGENTS.md`**: the org invariants block between `<!-- invariants:start -->` and
   `<!-- invariants:end -->`, byte for byte the pinned devkit's `agents/invariants.md`
   (`agents-check`); what the repo is, its gates, what it overrides; a docs paragraph with the
   skeleton, link and recipe rules below.
7. **`.github/workflows/`**, each calling the devkit's reusable workflow at the same tag
   ([reference](https://docs.marola.dev/5-Repos/marola-devkit/4-reference_workflows/)):
   - `ci.yml`: `scala-ci`, `python-ci` or `static-ci` for the repo's gates and `docs-lint`, and
     `agents-check`; plus a `ci-ok` job that `needs:` every other job, runs `if: always()`, and
     passes when each one succeeded or was skipped. Step 13's `ci-rule` requires that check with no
     bypass, so the repo must post it on every PR before the ruleset lands, or no PR there merges;
   - `pr.yml`: `pr-body` and `ci-short-circuit`;
   - `labels.yml`: `labels-sync`, on dispatch;
   - `notify-umbrella.yml`: on every push to `main`, no `paths:`, with
     `pr_bot_key: ${{ secrets.PR_BOT_KEY }}`; it sends `submodule-updated`, plus
     `submodule-docs-updated` when `README.md` or `docs/**` changed;
   - `bump-ready.yml`: the `notify-devkit` caller, on the repo's CI ending green on `chore/devkit-v*`,
     with `pr_bot_key: ${{ secrets.PR_BOT_KEY }}`; it wakes the devkit's `bump-merge`.
8. **`.github/ISSUE_TEMPLATE/`** and **`.github/PULL_REQUEST_TEMPLATE.md`**: copied from the
   devkit at the pinned tag. A **`.github/dependabot.yml`**, if the repo has dependencies, sets
   `cooldown` (`default-days: 7`) on every ecosystem and ignores `marola-dev/marola-devkit*`,
   whose pins move together ([DEV-FLOW](DEV-FLOW.md#dependency-prs)).
9. **`README.md`**, the landing: what the repo is with a status line, try it, the repo map, its
   contracts (consumes, publishes, pinned by), the docs and `AGENTS.md` links.
10. **`docs/`**: numbered pages, at least `3-development.md`; no `docs/index.md`
    ([the skeleton](DOCS-SITE.md#the-skeleton)).
11. **`LICENSE`**.

**The devkit pins move together.** Every devkit pin (the devkit row of REPOS'
[wiring table](../2-Building-marola/REPOS.md#artifacts-pins-and-dispatches)) names the same tag, and
a bump changes them all in one PR.

**Links follow [DOCS-SITE](DOCS-SITE.md#links).** Relative inside the repo, written to work on
GitHub; absolute `https://docs.marola.dev/…` to another repo or the umbrella; a relative link that
leaves the repo fails. A recipe that is neither the repo's own nor the devkit's carries the
checkout marker.

## GitHub settings

12. **Labels**: run `labels.yml` (or `just labels-sync`), which applies the devkit's
    `.github/labels.yml`.
13. **Branch rulesets**: `just rulesets-apply marola-dev/marola-<name>` creates `main-rule` and
    `ci-rule` from the devkit's `.github/rulesets/`; `just rulesets-check` diffs them later. Only
    after step 7's `ci-ok` job has posted on a PR: `ci-rule` blocks every merge without it.
14. **Apps**: nothing for marola-pr-bot (installed on every repo, its key readable by every
    repo). A devkit consumer also needs marola-merge-bot: add the repo to its installation (org
    settings → GitHub Apps → marola-merge-bot → Configure) before step 15, or the next release's
    bump cannot reach the repo ([CI/CD](CI-CD.md#the-maintainers-manual-settings)).
15. **Devkit bumps**: add `marola-dev/marola-<name>` to the devkit's `.github/consumers.txt`, in
    a marola-devkit PR, so `bump-consumers` opens its pin-bump PR after each release. Nothing to
    grant beyond step 14: `bump-consumers` opens the bump PR as marola-merge-bot. Once that
    devkit PR merges, run `just rulesets-apply marola-dev/marola-<name>` again, which adds the
    merge bot's `pull_request` bypass to `main-rule` (the `@consumers` extra reaches only repos
    listed in `consumers.txt`). Being listed also means the repo's bump PRs merge themselves
    (the devkit's `bump-merge`), which is why that bypass matters.
16. **Org Project**: the repo's issues land on
    [Project 1](https://github.com/orgs/marola-dev/projects/1) (its auto-add workflow, or `just
    board-sync`).

## In the umbrella

17. **`.gitmodules`** and the gitlink: `git submodule add
    https://github.com/marola-dev/marola-<name>.git marola-<name>`, in a PR. Adding a submodule is
    the one pointer an umbrella PR commits; `pointer-sync.yml` moves it from then on. Add the
    name to the `&submodules` anchor in `ci.yml`'s `changes` job too: the `docs` and `graph`
    filters read it, and a repo left out is never docs-built or graphed on a pointer move, silently
    ([CI/CD](CI-CD.md#the-workflows)). (The umbrella holds no Python, so there is no lint
    exclude list to extend.)
18. **`mkdocs/repos.yml`**: a `- name: marola-<name>` entry, so the site mounts it at
    `5-Repos/marola-<name>/`.
19. **`docs/2-Building-marola/REPOS.md`**: a routing-table row, and
    `just wiring docs/2-Building-marola/REPOS.md` to regenerate the
    [wiring block](../2-Building-marola/REPOS.md#artifacts-pins-and-dispatches) from its workflows
    and pins. An artifact it publishes that nothing reads yet needs a `<artifact>: <reason>` line
    in **`wiring.allow`** until the reader lands, and a dispatch it sends or listens for needs its
    other side in the same change: otherwise `wiring --check` turns every umbrella PR red.
20. **`README.md`**: a row in the repo table, linking its `5-Repos/` page.
21. **`.graphifyignore`**: the new repo's vendored or generated paths, under `marola-<name>/`
    unless a `**/` line already matches them (vendored code and skills, tests, fixtures, JSON).
    marola-site's vendored mapbox-gl alone was 6,949 of 13,192 nodes.
22. **`.github/workflows/deps-merge.yml`**, if the repo's `dependabot.yml` has a `pip` ecosystem:
    append `,marola-<name>` to its `REPOS:` list, so Dependabot's green pip PRs there merge
    themselves ([DEV-FLOW](DEV-FLOW.md#dependency-prs)). Steps 14 and 15 already gave
    marola-merge-bot its installation and its bypass on the repo.
23. **Check once**, in a `--recurse-submodules` checkout inside `nix develop`:
    `just wiring docs/2-Building-marola/REPOS.md` leaves no diff,
    `wiring --check docs/2-Building-marola/REPOS.md` passes, `just graph build` lists the repo in
    its `build.json`, and `just graph query "<one of its symbols>"` finds it
    ([graph](https://docs.marola.dev/5-Repos/marola-devkit/4-reference_tools/#code-graph)).
