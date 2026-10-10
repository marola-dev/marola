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
     `agents-check`;
   - `pr.yml`: `pr-body` and `ci-short-circuit`;
   - `labels.yml`: `labels-sync`, on dispatch;
   - `notify-umbrella.yml`: on every push to `main`, no `paths:`, with the `MAROLA_CROSS_REPO_PAT`
     secret; it sends `submodule-updated`, plus `submodule-docs-updated` when `README.md` or
     `docs/**` changed.
8. **`.github/ISSUE_TEMPLATE/`** and **`.github/PULL_REQUEST_TEMPLATE.md`**: copied from the
   devkit at the pinned tag.
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
13. **Branch ruleset**: `just rulesets-apply marola-dev/marola-<name>` creates `main-rule` from the
    devkit's `.github/rulesets/main-rule.json`; `just rulesets-check` diffs it later.
14. **`MAROLA_CROSS_REPO_PAT`**: grant the fine-grained token Contents read and write on the new
    repo, and add the repo to the org secret's repository access
    ([CI/CD](CI-CD.md#the-maintainers-manual-settings)).
15. **Devkit bumps**: add `marola-dev/marola-<name>` to the devkit's `.github/consumers.txt`, in
    a marola-devkit PR, so `bump-consumers` opens its pin-bump PR after each release. Nothing to
    grant: `MAROLA_BUMP_PAT` reaches every org repo, and only marola-devkit may read it.
16. **Org Project**: the repo's issues land on
    [Project 1](https://github.com/orgs/marola-dev/projects/1) (its auto-add workflow, or `just
    board-sync`).

## In the umbrella

17. **`.gitmodules`** and the gitlink: `git submodule add
    https://github.com/marola-dev/marola-<name>.git marola-<name>`, in a PR. Adding a submodule is
    the one pointer an umbrella PR commits; `pointer-sync.yml` moves it from then on. Add the
    name to the `&submodules` anchor in `ci.yml`'s `changes` job too: the `docs` and `graph`
    filters read it, and a repo left out is never docs-built or graphed on a pointer move, silently
    ([CI/CD](CI-CD.md#the-workflows)).
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
    unless `**/vendor/**` already matches them. marola-site's vendored mapbox-gl alone was 6,949
    of 13,192 nodes.
22. **Check once**, in a `--recurse-submodules` checkout inside `nix develop`:
    `just wiring docs/2-Building-marola/REPOS.md` leaves no diff,
    `wiring --check docs/2-Building-marola/REPOS.md` passes, `just graph build` lists the repo in
    its `build.json`, and `just graph query "<one of its symbols>"` finds it
    ([graph](https://docs.marola.dev/5-Repos/marola-devkit/4-reference_tools/#code-graph)).
