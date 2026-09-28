# MIP-0066: Opt-in dev containers — the flake, in a container, for contributors without Nix

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5.5), with Bruno |
| **Created** | 2026-09-28 |
| **Phase** | 3 — contributor tooling; no Phase 1 or Phase 2 prerequisite, no cloud spend |
| **Related** | MIP-0065 (hosted CI, the Docker workflows back on), MIP-0064 (the docs site), MIP-0008 (the `Dockerfile` and its `dev` target) |
| **Effort** | L — a new flake output, a `.devcontainer/`, four `just` recipes, one CI job, one new guide; no Scala |
| **Gain** | `infra/dev-loop` — a contributor goes from clone to green gates with Docker and an editor, no Nix install; `user value` — a lower bar for FOSS contributors |
| **Effort vs Gain** | `do next` for tasks 1–3, which only share files with MIP-0064/0065's open tasks at a row or recipe level (`justfile` with 0065-T1, `AGENTS.md` and `docs/index.md` with 0064-T4 and 0065-T4); tasks 4–5 `do when MIP-0065 lands` |
| **Depends on** | Tasks 1–3 stand alone. Task 4 (the CI job) edits `ci.yml`, which MIP-0065 task 1 (#466) rewrites and moves to `ubuntu-latest`, so it waits for that. Task 5 (the prebuilt image) publishes through `docker.yml`, which MIP-0065 task 2 (#467) switches back on under `ghcr.io/marola-dev`. The guide is a new file under `docs/`, indexed in `docs/index.md` (MIP-0064 task 2), and must pass that build's `--strict`. No Phase 1 gate, no paid resource |
| **Blocked by** | none |
| **Risk** | The container works on the author's machine and nowhere else: Metals not seeing the Nix environment, or `/nix` permissions under a non-root user, makes the first outside contributor's "Reopen in Container" fail, and they never come back |
| **Cost so far** | — |

## 1. Summary

A contributor who has Docker and VS Code (or JetBrains, or the devcontainer CLI, or GitHub
Codespaces) opens the repo in a container and gets the toolchain `nix develop` gives today, from
the same `flake.lock`, without installing Nix. The container runs a slimmer shell,
`devShells.contrib`, with the maintainer-only tools left out; the full `default` shell is one
command away inside it, and the guide says when to use it. Everything is opt-in: nothing changes
for anyone who keeps using `nix develop` or direnv on the host.

## 2. Motivation

- `CONTRIBUTING.md` "Run it" starts with `nix develop`. Installing Nix is the biggest step between
  cloning marola and running `just test`, and a public repo now invites people who have never used it.
- `Dockerfile` already has a `dev` target (`FROM nixos/nix:2.35.2`, `nix develop --command true`),
  but it runs as root, has no editor integration, is amd64 only, and `docker.yml` builds it only on
  manual dispatch, while every job in that workflow is gated off (`DOCKER_CI` unset).
- The `default` shell carries tools a contributor never touches: `ollama`, `github-runner` (with
  its .NET runtime), `waydroid`, `wl-clipboard`/`xclip`, `repomix`, the CUDA lab on x86_64 and the
  agentic lab (ai-jail, bwrap). Its `shellHook` also runs `just sync-main`, a silent fast-forward of
  `main` that would surprise a contributor if it ran on container start.

## 3. User-visible change

```
before: install Nix → enable flakes → nix develop (full shell) → just build
after:  VS Code: "Reopen in Container" (or: npx @devcontainers/cli@0.89.0 up --workspace-folder .)
        marola dev container — shell: contrib (build, test, quality, run, pr)
          need ollama, e2e, fine-tuning or the context-* packers? run: nix develop
          (docs/DEV-CONTAINER.md, "When you need the full shell")
        $ just build && just test && just quality     # same versions as on a Nix host
```

A recipe that needs a tool only the full shell has fails with a message, not `command not found`:

```
$ just ollama-up
ollama is in the full dev shell, not in contrib. Run `nix develop` (or
MAROLA_DEVSHELL=default direnv reload) — see docs/DEV-CONTAINER.md.
```

## 4. Data sources and dependencies reviewed

### 4.1 The Nix Feature

`ghcr.io/devcontainers/features/nix`, version 1.4.0, with options `version`, `multiUser` (default
`true`), `packages`, `flakeUri`, `useAttributePath`, `extraNixConfig`. A multi-user install needs
the container to run as root or give `remoteUser` passwordless `sudo`, which the devcontainers
`base` images do. 1.4.0 dropped the Feature's own `/nix` volume cache (on 1.3.x it masked option
changes on rebuild). The README's documented workaround is our own volume on `/nix`, safe "only if
you use the default Feature… because no options ever change", and it has to be removed
(`docker volume rm`) when `version` is bumped. `flakeUri` "works best with a remote URI"; a flake
in the source tree is installed from `postCreateCommand`. **Pick:** the Feature pinned to `1.4.0`,
multi-user, one option (`version`, pinned), and a `/nix` volume. That is not the README's
"default Feature" case: changing the pinned `version` is masked by the volume until
`just devcontainer-reset` removes it, and the recipe's doc line says so.

### 4.2 The base image

`mcr.microsoft.com/devcontainers/base` publishes `ubuntu26.04`/`resolute`, `ubuntu24.04`/`noble`
and `ubuntu22.04`/`jammy`, all for x86-64 and arm64. Semver tags exist (`3.0.8-noble`,
`3.0.8-resolute`). **Pick:** `3.0.8-noble`, the same Ubuntu as MIP-0065's `ubuntu-latest` runner,
pinned so a rebuild does not silently change base.

### 4.3 Editor wiring and the CLI

- The spec's `userEnvProbe` defaults to `loginInteractiveShell`; `containerEnv` is fixed for the
  container's life, `remoteEnv` reaches tool processes only; `hostRequirements` takes minimum
  `cpus`, `memory`, `storage`.
- `scalameta.metals` 1.71.1 (2026-09-28); `mkhl.direnv` 0.17.0 (2024-03-12, dormant, §8);
  `@devcontainers/cli` 0.89.0, Node ≥ 20.

**Pick:** direnv + nix-direnv, installed by `onCreateCommand` from the flake's own locked nixpkgs,
and the existing `.envrc` made shell-selectable (§5.2). `mkhl.direnv` loads it for Metals; if that
fails verification, the fallback is a login-shell hook that `userEnvProbe` already picks up.

### 4.4 Codespaces cost

GitHub Free personal accounts include 120 hours of compute time and 15 GB-month of storage a
month, drawn down by an "included usage multiplier" of 2 on a 2-core machine and 4 on a 4-core one.
Compute "is charged to the account that owns the codespace", and if Codespaces is not enabled for
a user in the organization, "they can still create codespaces from public repositories in the
organization, but the user will pay for these codespaces". Prebuilds are "generated using actions
minutes" and billed storage. **Pick:** support Codespaces through the same `devcontainer.json`,
with no organization billing and no prebuilds. A contributor's codespace draws on their own free quota.

### 4.5 Reaching the host's Ollama

Docker's `--add-host` accepts `host-gateway`, "the internal IP address of the host", conventionally
named `host.docker.internal`; Docker Desktop resolves that name on its own. Ollama "binds
127.0.0.1 port 11434 by default" and is exposed with `OLLAMA_HOST`. So a container reaches a host
Ollama only if the host sets `OLLAMA_HOST=0.0.0.0`, and the guide says so.

## 5. Design

### 5.1 `flake.nix`: a `contrib` shell from the same list

`projectTools` splits into `coreTools` (jdk, `sbtOnJdk25`, scala-cli, coursier, just, the
python3-with-scikit-learn, uv, nodejs, jq, git) and `hostTools` (ollama, repomix, wl-clipboard,
xclip, github-runner, waydroid). `default` is unchanged in content:
`coreTools ++ hostTools ++ lint ++ agentic ++ cuda`. The new `devShells.contrib` is
`coreTools ++ [ pkgs.gh ] ++ lint.lib.${system}.tools`: `gh` is the one tool the agentic lab
carries that a contributor needs (`just pr`, the issue commands), and it follows the same nixpkgs,
so it is the same store path as in `default`. It sets `JAVA_HOME`; its `shellHook` sets
`core.hooksPath` and loads `.env`, but skips `sync-main`, the Ollama probe and the detached-mirror
warning. Both shells resolve the shared tools to the same store paths, so a version cannot differ
between them.

### 5.2 `.envrc`: which shell direnv loads

```
use flake ".#${MAROLA_DEVSHELL:-default}"
dotenv_if_exists
```

A host with no `MAROLA_DEVSHELL` loads `default`, same as today. The container sets
`MAROLA_DEVSHELL=contrib` in `containerEnv`, so every process in it sees the variable, the editor's
git included. `.githooks/pre-push` and `.githooks/pre-commit` fall back to `nix develop --command …`
outside a Nix shell, which is `default`; a push from VS Code's git panel would download the full
shell. Both become `nix develop ".#${MAROLA_DEVSHELL:-default}" --command …`. `context-*` also runs
`nix develop` to find `repomix`; that one should get the full shell, and does.

### 5.3 `.devcontainer/devcontainer.json`

- `image: mcr.microsoft.com/devcontainers/base:3.0.8-noble`, `remoteUser: vscode`.
- `features`: `ghcr.io/devcontainers/features/nix:1.4.0` with `version` pinned to one Nix release,
  so a rebuild never picks a new Nix by itself. Flakes are enabled through
  `containerEnv.NIX_CONFIG`, not the Feature's `extraNixConfig`, to keep Feature options to one.
- `mounts`: named volumes `marola-nix` → `/nix`, and `marola-coursier`, `marola-sbt`,
  `marola-ivy2` on the `vscode` user's cache directories. Docker creates missing mount points as
  root, so `onCreateCommand` starts with `sudo chown vscode:` on the three cache paths.
- `runArgs: ["--add-host=host.docker.internal:host-gateway"]`.
- `containerEnv: { MAROLA_DEVSHELL: contrib, NIX_CONFIG: "experimental-features = nix-command flakes" }`.
- `hostRequirements: { cpus: 2, memory: 8gb, storage: 32gb }`, read by Codespaces. With the
  multiplier (§4.4), 2 cores gives a Free account about 60 hours a month, 4 cores
  about 30. The guide says to pick 4 cores for heavy sbt work if the quota allows.
- `onCreateCommand`: `nix profile install --inputs-from . nixpkgs#direnv nixpkgs#nix-direnv`
  (locked nixpkgs, not the registry's), the bash/zsh direnv hook, `direnv allow`.
- `postCreateCommand`: `nix develop .#contrib --command just devcontainer-warm`.
- `customizations.vscode.extensions`: `scalameta.metals`, `mkhl.direnv`.

### 5.4 `just` recipes

| Recipe | Where | Does |
|---|---|---|
| `devcontainer-warm` | inside | `sbt update compile`, once, so the first `just test` is not a cold build |
| `devcontainer-up` | host | `npx @devcontainers/cli@0.89.0 up --workspace-folder .` |
| `devcontainer-check` | host | `up`, then `exec … nix develop .#contrib --command just build test quality`: the acceptance test, also CI's |
| `devcontainer-reset` | host | removes the four volumes, for a Feature bump or a broken store |

`gh auth login` inside the container is lost on a rebuild (no volume on `~/.config/gh`, on
purpose: a token should not outlive the container silently); Codespaces provides its own token.
A contributor without Nix has no `just` on the host either, so the guide gives the raw `npx`
commands first and names the recipes as wrappers. Recipes that need a full-shell tool
(`ollama-serve`, `ollama-up`, `e2e`, `context-*`, `jail-*`, `runner-*`, `finetune-*`,
`marola-sea-pull`, `gpu-cache-setup`) call a private `_needs <tool>` that prints §3's message
when the tool is missing, instead of letting bash say `command not found`.

### 5.5 `docs/DEV-CONTAINER.md`

1. **Which path is yours.** Nix installed, or willing to install it: `nix develop`, as today.
   Otherwise, the container. Both run the same versions.
2. **Open it**: VS Code, JetBrains Gateway, the CLI, Codespaces (your own quota, §4.4).
3. **What is inside `contrib`**: the tools, and what they are enough for — build, test, quality,
   `just run -- --brief`, the MCP server, `just pr`, the issue commands.
4. **When you need the full shell**: a table from task to tool to what to run.

   | You want to | Needs | In the container |
   |---|---|---|
   | `--summarize`, `--ask`, `just e2e` | an Ollama | the host's: `OLLAMA_HOST=0.0.0.0` there, `MAROLA_LOCAL_LLM_BASE_URL=http://host.docker.internal:11434/v1` here |
   | `just ollama-up`, a model inside the container | `ollama` | `nix develop` (full shell); CPU only |
   | `just context-mips` / `context-mip` | `repomix` | `nix develop` |
   | `finetune-*`, `gpu-cache-setup` | CUDA, a GPU | not in a container: a Nix host |
   | `jail-*`, `runner-*`, waydroid | bwrap, the runner, LXC | not in a container: a Nix host |

   Two ways in: `nix develop` for one terminal, or `MAROLA_DEVSHELL=default direnv reload` for a
   shell that stays full. The first costs the larger download once, then the `/nix` volume keeps it.
5. **Caches, reset, troubleshooting**: the four volumes and `just devcontainer-reset`; Metals
   without Java, a stopped Nix daemon (`sudo /usr/local/share/nix-entrypoint.sh`), an unreachable
   `host.docker.internal`.

Pointers of one line each in `CONTRIBUTING.md` "Run it", `docs/1-Using-marola/RUN-LOCALLY.md` §1, the doc table in
`AGENTS.md`, and `docs/index.md`, each saying the container is optional and Nix stays primary.

### 5.6 CI (after MIP-0065 task 1, #466)

A `devcontainer` job in `ci.yml`, `runs-on: ubuntu-latest` (MIP-0065's guard allows it),
path-filtered to `.devcontainer/**`, `flake.nix`, `flake.lock`, `.envrc` and `justfile`, running
the same two `npx @devcontainers/cli@0.89.0` lines `devcontainer-check` wraps (the runner has Node
but no `just`), with `timeout-minutes: 60`. Cold every time, since there is no `/nix`
volume on a runner; the path filter keeps that rare. Free on a public repo (MIP-0065 §4.1).

### 5.7 Prebuilt image (after MIP-0065 task 2, #467)

`docker.yml` builds `ghcr.io/marola-dev/marola:devcontainer` with `devcontainer build` for
linux/amd64 and linux/arm64, with the `contrib` closure in the image, and `devcontainer.json`
switches to it, keeping `build` as the fallback. The first open drops from a Nix fetch to an image
pull. The `/nix` volume is seeded from the image once, so a later image only helps a fresh volume;
an old one keeps working and fetches what a newer `flake.lock` adds. Whether the `Dockerfile` `dev` target is then deleted is decided in that task (§11).

### 5.8 Delivery

1. **contrib-shell**: §5.1, §5.2.
2. **devcontainer**: §5.3, §5.4 (with `_needs`).
3. **guide**: §5.5.
4. **ci-job**: §5.6. Depends on 3 and `0065-T1` (#466).
5. **prebuilt-image**: §5.7. Depends on 4 and `0065-T2` (#467).

## 6. Scoring / safety impact

None.

## 7. Verification plan

1. `nix flake check`; `nix develop .#contrib --command just build test quality` on a Nix host; for
   every shared tool, `nix develop .#contrib --command which <t>` equals the `default` path.
2. Fresh clone, empty Docker, no Nix: `npx @devcontainers/cli@0.89.0 up --workspace-folder .`,
   then the gates inside, green. First-open wall time and `/nix` volume size recorded in the PR,
   replacing §8's estimates.
3. The same on arm64 (Apple Silicon or an arm64 Linux box), and in a codespace from a fork.
4. VS Code: Metals imports the build and go-to-definition works in `core/`; the integrated terminal
   has `JAVA_HOME` from the store.
5. Inside the container, `just ollama-up` prints the `_needs` message; `nix develop` then
   `which ollama` succeeds; `MAROLA_DEVSHELL=default direnv reload` keeps it.
6. With `OLLAMA_HOST=0.0.0.0 ollama serve` on the host, `just run -- --summarize` inside answers.
7. A `git push` from VS Code's git panel in the container runs the pre-push gates in `contrib`:
   no `ollama` or `github-runner` path appears in the `/nix` volume afterwards.
8. On a Nix host with no `MAROLA_DEVSHELL`, direnv still loads `default` (unchanged behaviour).
9. Task 4: the job green on its PR, and red on a throwaway commit that breaks `flake.nix`.

## 8. Risks, limitations, and honest caveats

- **Sizes are estimates.** The shared core (JDK, Python with scikit-learn, Node, the lint lab) is
  guessed at 1.5–2.5 GB and the extras at as much again, CUDA dominating; no Nix was available to
  measure. §7 step 2 replaces the guess.
- **The first open is slow** until task 5: a full Nix fetch from cache.nixos.org, minutes not seconds.
- **`mkhl.direnv` is dormant** (no release since 2024-03). If it breaks, the §4.3 fallback applies;
  §7 step 4 is where that is found.
- **A `/nix` volume outlives Feature bumps.** A version change needs `just devcontainer-reset`,
  otherwise the old store masks the new install (§4.1). The recipe prints this.
- **Ollama in the container is CPU only**, and the host's needs a wider bind address. The guide
  says both; neither is made to look like it works when it does not.
- **The `.envrc` change re-prompts `direnv allow`** on every host that uses direnv, once.
- **Two ways in means two to keep working.** The CI job (task 4) is what keeps the container from
  quietly rotting behind `nix develop`.

## 9. Alternatives considered

- **Reuse the `Dockerfile` `dev` target.** One image already exists, but it runs as root, has no
  UID mapping, no editor wiring and no arm64; making it a good devcontainer means rebuilding what
  the `base` images already do.
- **A Dockerfile without Nix (apt, sdkman, pip).** Fastest to open, but every version is copied
  out of `flake.lock` and drifts; rejected against the "deterministic" requirement.
- **The full `default` shell in the container.** One shell to reason about, but a contributor
  downloads CUDA, .NET and waydroid to run `just test`. `default` stays one command away instead.
- **Codespaces prebuilds.** The fastest Codespaces start, billed to the organization in Actions
  minutes and storage; not proposed.
- **Do nothing.** Nix remains the only door, and a contributor's first task is installing a
  package manager.

## 11. Open questions

- Delete the `Dockerfile` `dev` target once task 5 ships, or keep it for plain `docker run`? Decided
  in task 5.
- Does JetBrains Gateway's devcontainer support pick up the direnv environment for its Scala plugin?
  Checked in task 2; if not, the guide says VS Code is the supported editor.
- **Follow-up issue:** `docs/1-Using-marola/RUN-LOCALLY.md` still says the repo is private and images come from
  `ghcr.io/h0ffmann/marola`; MIP-0065 task 2 changes the image names, the prose needs the same pass.

## Appendix

### Checked live

- 2026-09-28 `gh repo view marola-dev/marola` → `PUBLIC`; `gh api '/orgs/marola-dev/packages?package_type=container'` → `[]`.
- 2026-09-28 `raw.githubusercontent.com/devcontainers/features/main/src/nix/devcontainer-feature.json`
  → version 1.4.0, the six options above; its README → multi-user needs root or passwordless sudo,
  the 1.3.x `/nix` cache warning, the own-volume workaround, `flakeUri` prefers remote URIs,
  `sudo /usr/local/share/nix-entrypoint.sh`.
- 2026-09-28 `raw.githubusercontent.com/devcontainers/images/main/src/base-ubuntu/README.md` →
  `ubuntu26.04`/`ubuntu24.04`/`ubuntu22.04` variants, x86-64 and arm64; `mcr.microsoft.com/v2/devcontainers/base/tags/list` → `3.0.8-noble`, `3.0.8-resolute`.
- 2026-09-28 `devcontainers/images` `src/base-ubuntu/.devcontainer/devcontainer.json` → built with
  `features/common-utils:2`, user `vscode`; `common-utils/main.sh` writes
  `$USERNAME ALL=(root) NOPASSWD:ALL` to `/etc/sudoers.d` (the passwordless sudo §4.1 relies on).
- 2026-09-28 `registry.npmjs.org/@devcontainers/cli/latest` → 0.89.0, `node >=20.0.0`.
- 2026-09-28 VS Code Marketplace query → `scalameta.metals` 1.71.1 (2026-09-28), `mkhl.direnv` 0.17.0 (2024-03-12).
- 2026-09-28 containers.dev/implementors/json_reference → `hostRequirements`, `userEnvProbe`
  (default `loginInteractiveShell`), `containerEnv` vs `remoteEnv`, the lifecycle command order.
- 2026-09-28 docs.github.com …/github-codespaces billing → Free: 15 GB-month storage, 120 hrs compute
  time; compute charged to the codespace's owner; prebuilds use Actions minutes.
- 2026-09-28 docs.github.com …/choosing-who-owns-and-pays-for-codespaces-in-your-organization →
  a user without org-enabled Codespaces "will pay for these codespaces" on a public org repo.
- 2026-09-28 docs.docker.com/reference/cli/docker/container/run → `host-gateway`,
  `host.docker.internal` by convention.
- 2026-09-28 github.com/ollama/ollama docs/faq.mdx → binds 127.0.0.1:11434 by default; `OLLAMA_HOST`.
- 2026-09-28 `gh api repos/h0ffmann/nix-config/contents/labs/{lint,agentic}/flake.nix` at the revs
  in `flake.lock` → lint: hadolint, actionlint, shellcheck, ruff, pyflakes, cloc, coverage, pdoc;
  agentic: **`gh`**, opencode, gh-token, clip, ai-jail, bubblewrap, wl-clipboard, xclip.
- 2026-09-28 `.githooks/pre-push` and `pre-commit` → `nix develop --command just|sbt` outside a Nix
  shell, i.e. the `default` shell.
- 2026-09-28 the `quality-other` shell self-tests (`gh-billing`, `setup-runners`, `marola-sea-pull`,
  `temps`, `runner-preflight`, `gha-runner`, `issues`, `stack`) → all pass on a machine with no
  `ollama`, `repomix`, `waydroid` or runner installed; `gh` was present there.
- 2026-09-28 docs.github.com …/github-codespaces billing → 2/4/8/16/32-core machines with an
  "included usage multiplier" of 2/4/8/16/32 (raw page, `github/docs`
  `content/billing/concepts/product-billing/github-codespaces.md`).

### Not checked

- Closure sizes of `default` and `contrib` (no Nix on the drafting machine, §8).
- Whether `nix profile install --inputs-from .` works before the first `nix develop` in a fresh
  container; task 2.
- JetBrains Gateway's handling of direnv (§11).
- The RAM of each Codespaces machine type: whether `cpus: 2, memory: 8gb` lands on a 2-core
  machine or forces a 4-core one; task 2, from a real codespace.
- Free arm64 hosted runners for task 5's multi-arch build; if they are not free, QEMU on amd64
  instead, slower. Checked in task 5.
</content>
</invoke>
