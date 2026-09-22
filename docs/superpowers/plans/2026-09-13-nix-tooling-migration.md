# Nix tooling migration — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move marola's lint toolchain, agent sandbox and CUDA host setup into three nix-config labs, then consume them from marola as flake inputs.

**Architecture:** Each lab is a self-contained flake under `labs/<lab>` in h0ffmann/nix-config, shaped like `labs/publisher`: `toolsFor = pkgs: [...]`, exported as `lib.<system>.tools`, scripts as `writeShellApplication` packages, self-tests as `checks`. marola adds each lab as an input with `inputs.nixpkgs.follows = "nixpkgs"` and appends `lab.lib.${system}.tools` to its devShell.

**Tech Stack:** Nix flakes (Determinate Nix 3.x), `writeShellApplication`, bash with `--self-test`, `just`, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-13-nix-tooling-migration-design.md` (PR #359). Issues: #356 lint, #357 agentic, #358 cuda.

## Global Constraints

- Every lab declares `systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ]` except `labs/cuda`, which is `[ "x86_64-linux" ]`.
- Every lab passes nix-config CI: `nix flake check --no-update-lock-file`, `nixpkgs-fmt --check`, `statix check`, `deadnix --fail`, shellcheck, `just --list`, the scripts' self-tests.
- Lab scripts live in `labs/<lab>/scripts/<name>` with **no `.sh` suffix**, executable, `#!/usr/bin/env bash` first line, `set -euo pipefail`, and a `--self-test` mode that exits 0. The same file is both run from source (CI) and wrapped by `writeShellApplication` (consumers), so `--help` text is a heredoc, never `sed -n 'N,Mp' "$0"`.
- A script's `runtimeInputs` never includes a tool its own self-test fakes on `PATH` (writeShellApplication prepends `runtimeInputs` to `PATH`, which would shadow the fake).
- marola: every commit carries `Tested:` and `Cost:` trailers; `just build && just test && just quality` before any PR; a marola PR is opened only after its lab has merged in nix-config `main`, so `flake.lock` pins a real revision.
- marola recipe names do not change: `jail-claude`, `jcf`, `jcs`, `jco`, `jail-opencode`, `jo`, `jail-dry-run`, `gh-auth`, `clip`, `ml-venv`, `gpu-cache-setup`.
- Env-var names in the labs carry no repo prefix: `JAIL_CLIPBOARD`, `JAIL_CLIPBOARD_PASTE`, `CLIP_SINK`, `REQUIREMENTS`, `VENV_ROOT`, `CUDA_INDEX`.
- The clipboard fifo stays at `<project>/.tmp/clip.fifo`: the project directory is what ai-jail maps into the sandbox. (Spec §3.2 said `$XDG_RUNTIME_DIR`; Task 3 corrects it.)

---

## File map

**nix-config** (clone at `~/code/nix-config`, branch per lab):

| Path | Responsibility |
|---|---|
| `labs/lint/flake.nix`, `flake.lock`, `justfile`, `README.md` | the lint toolchain; `checks.versions` |
| `labs/agentic/flake.nix`, `flake.lock`, `justfile`, `README.md`, `.gitignore` | ai-jail, opencode, gh + four scripts |
| `labs/agentic/scripts/{gh-token,clip,clip-relay,jail-run}` | host-side scripts, each with `--self-test` |
| `labs/cuda/flake.nix`, `flake.lock`, `justfile`, `README.md` | CUDA host setup |
| `labs/cuda/scripts/{setup-cuda-cache,setup-ml-venv}` | the two scripts, generalised |
| `.github/workflows/ci.yml` | matrix + generic shellcheck/self-test steps |
| `README.md` | one section per new lab, Structure, Flake outputs |

**marola** (three stacked branches `feat/nix-lint` → `feat/nix-agentic` → `feat/nix-cuda`):

| Path | Change |
|---|---|
| `flake.nix`, `flake.lock` | inputs, composed tool lists, `devShells.lint` |
| `justfile` | wrappers; `quality-other` self-test list |
| `.github/workflows/ci.yml` | `nix develop .#lint` instead of `nix build nixpkgs#…` |
| `.github/workflows/marola-sea-publish.yml` | `setup-ml-venv`, `bin/python-cuda` |
| `scripts/{gh-token.sh,clip.sh,clip-relay.sh,setup-cuda-cache.sh,setup-ml-venv.sh}` | deleted |
| `scripts/runner-preflight.sh` | `gh-token` from PATH |
| `finetune/merge_export.py` | requirements assertion in `--self-test` |
| `AGENTS.md`, `PHILOSOPHY.md`, `docs/RUN-LOCALLY.md`, `docs/DEV-FLOW.md`, `.claude/hooks/session-start.sh` | names and paths |

---

### Task 1: nix-config `labs/lint`

**Files:**
- Create: `labs/lint/flake.nix`, `labs/lint/justfile`, `labs/lint/README.md`
- Create (generated): `labs/lint/flake.lock`
- Modify: `.github/workflows/ci.yml:42` (matrix), `README.md` (a `## lint` section before `## Structure`, plus the Structure tree and Flake outputs)

**Interfaces:**
- Produces: `lib.<system>.tools : [derivation]`; `packages.<system>.{versions,hadolint,actionlint,shellcheck,ruff,pyflakes,cloc,coverage,pdoc}`; `checks.<system>.versions`; `devShells.<system>.default`.

- [ ] **Step 1: Branch**

```bash
cd ~/code/nix-config && git checkout -q main && git pull -q --ff-only && git checkout -b labs/lint
mkdir -p labs/lint
```

- [ ] **Step 2: Write `labs/lint/flake.nix`**

```nix
{
  description = "lint — the gate a repository runs before a PR: hadolint, actionlint, shellcheck, ruff, pyflakes, cloc, coverage.py, pdoc";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      inherit (nixpkgs) lib;
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forAll = f: lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});

      # The one place the tools are named. A consumer appends this to its own mkShell packages.
      toolsFor = pkgs: with pkgs; [
        hadolint
        actionlint
        shellcheck # actionlint shells out to it for `run:` blocks; without it findings vanish silently
        ruff
        python3Packages.pyflakes
        cloc
        python3Packages.coverage
        python3Packages.pdoc
      ];

      # `nix flake check` builds this: every tool answers --version in the sandbox, so a nixpkgs
      # bump that drops or breaks one fails here, not in a consumer's CI.
      versionsFor = pkgs: pkgs.runCommand "lint-versions" { nativeBuildInputs = toolsFor pkgs; } ''
        {
          echo "nixpkgs    ${nixpkgs.rev or "dirty"}"
          echo "hadolint   $(hadolint --version)"
          echo "actionlint $(actionlint -version)"
          echo "shellcheck $(shellcheck --version | sed -n 2p)"
          echo "ruff       $(ruff --version)"
          echo "pyflakes   $(pyflakes --version)"
          echo "cloc       $(cloc --version)"
          echo "coverage   $(coverage --version | head -1)"
          echo "pdoc       $(pdoc --version)"
        } | tee versions.txt
        mkdir -p "$out" && cp versions.txt "$out"/
      '';
    in
    {
      lib = forAll (pkgs: {
        tools = toolsFor pkgs;
      });

      packages = forAll (pkgs: {
        versions = versionsFor pkgs;
        inherit (pkgs) hadolint actionlint shellcheck ruff cloc;
        inherit (pkgs.python3Packages) pyflakes coverage pdoc;
      });

      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          name = "lint";
          packages = toolsFor pkgs;
          shellHook = ''echo "lint: ruff $(ruff --version | cut -d' ' -f2) | hadolint $(hadolint --version | cut -d' ' -f4) | actionlint $(actionlint -version)"'';
        };
      });

      checks = forAll (pkgs: {
        versions = self.packages.${pkgs.system}.versions;
      });
    };
}
```

- [ ] **Step 3: Write `labs/lint/justfile`**

```just
# lint — hadolint, actionlint, shellcheck, ruff, pyflakes, cloc, coverage.py, pdoc. `just` lists recipes.
set shell := ["bash", "-euo", "pipefail", "-c"]
here := justfile_directory()

default:
    @just --list

# Enter the shell: every lint tool on PATH, in your current directory.
shell:
    nix develop "{{here}}"

# Print the exact tool versions this lab pins (builds checks.versions in the sandbox).
versions:
    nix build "{{here}}#versions" --print-build-logs --out-link "{{here}}/result" && cat "{{here}}/result/versions.txt"
```

- [ ] **Step 4: Write `labs/lint/README.md`**

```markdown
# lint

The tools a repository runs before a PR, from one locked nixpkgs: **hadolint** (Dockerfiles),
**actionlint** + **shellcheck** (workflows and the `run:` blocks inside them), **ruff** and
**pyflakes** (Python), **cloc** (line counts), **coverage.py**, **pdoc**. No scripts; the lab
is a list.

```
just shell       # the tools on PATH, in your current directory
just versions    # every tool's --version, built in the Nix sandbox (what CI checks)
```

## Reusing it from another repository

```nix
{
  inputs.lint = { url = "github:h0ffmann/nix-config?dir=labs/lint"; inputs.nixpkgs.follows = "nixpkgs"; };
  outputs = { nixpkgs, lint, ... }: {
    devShells.x86_64-linux.default = nixpkgs.legacyPackages.x86_64-linux.mkShell {
      packages = [ /* the project's own tools */ ] ++ lint.lib.x86_64-linux.tools;
    };
  };
}
```

`follows` keeps one nixpkgs closure in the consumer's shell; the price is that this lab's CI
evaluates on its own lock and the consumer on its own, so a tool can differ by a patch version
between the two. `nix flake update lint` in the consumer picks up a new revision of this lab.

`packages.<system>.<tool>` re-exports each tool for `nix run`, and `checks.<system>.versions`
fails the flake check if a nixpkgs bump breaks any of them.

First consumer: [marola](https://github.com/h0ffmann/marola) (`flake.nix` and `ci.yml` there).
```

- [ ] **Step 5: Lock, check, lint**

```bash
cd ~/code/nix-config/labs/lint
nix flake lock
nix flake check --no-update-lock-file --print-build-logs .
nix run nixpkgs#nixpkgs-fmt -- --check . && nix run nixpkgs#statix -- check . && nix run nixpkgs#deadnix -- --fail .
nix run nixpkgs#just -- --list
just versions
```
Expected: check builds `versions`; `result/versions.txt` lists nine lines; lint tools clean.

- [ ] **Step 6: CI matrix and README**

`.github/workflows/ci.yml` line 42: `lab: [pratico, publisher, lint]`.

Root `README.md`: insert before `## Structure`:

```markdown
## lint

The gate a repository runs before a PR — hadolint, actionlint + shellcheck, ruff, pyflakes,
cloc, coverage.py, pdoc — exported as one list for a consumer's `mkShell`. See
[`labs/lint`](labs/lint).

```console
cd labs/lint
just shell            # the tools on PATH
just versions         # what is pinned, built in the sandbox (CI)
```
```

In the Structure tree add `│   ├── lint/           lint toolchain as one list, checks.versions   (flake, lock, justfile, README)`. In Flake outputs add a `nix flake show github:h0ffmann/nix-config?dir=labs/lint` block listing `checks.versions`, `devShells.default`, `lib`, `packages.{versions,hadolint,…}`. Under "Used by" name marola.
Under `## License` add: "marola consumes these labs as flake inputs under the same terms; the labs are used by one owner's repositories only until a licence file lands."

- [ ] **Step 7: Commit and PR**

```bash
cd ~/code/nix-config && git add labs/lint .github/workflows/ci.yml README.md
git commit -m "feat(lint): the lint toolchain as a lab — one list, checks.versions

Refs h0ffmann/marola#356"
git push -u origin labs/lint && gh pr create --fill --title "labs/lint: the lint toolchain as one list" --body "Refs h0ffmann/marola#356. nix flake check builds checks.versions on three systems."
```
Wait for CI green, merge.

---

### Task 2: marola consumes `labs/lint`

**Files:**
- Modify: `flake.nix` (inputs, packages list, new `devShells.lint`), `flake.lock` (generated)
- Modify: `.github/workflows/ci.yml:196-200` and `:322-327`, `:266,272` (ruff comment)
- Modify: `PHILOSOPHY.md` "Why Nix" paragraph; `docs/RUN-LOCALLY.md:377`

**Interfaces:**
- Consumes: `lint.lib.${system}.tools` from Task 1.
- Produces: `devShells.${system}.lint` (used by `ci.yml`), the `projectTools` list later tasks append to.

- [ ] **Step 1: Branch**

```bash
cd ~/code/marola && git checkout -q main && git pull -q --ff-only && git checkout -b feat/nix-lint
```

- [ ] **Step 2: Rewrite `flake.nix`**

Replace the whole file. The package list keeps every marola-specific tool and its comment where the comment says something the code cannot; the eight lint packages and their comments go.

```nix
{
  description = "marola dev shell — Scala 3.9 / Kyo / Azure tooling, plus Python for the offline DSPy compile step; works on plain Ubuntu (not NixOS-specific)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    ai-jail = {
      url = "github:akitaonrails/ai-jail";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    # h0ffmann/nix-config labs, one nixpkgs closure via `follows`; bump with `nix flake update lint`.
    lint = {
      url = "github:h0ffmann/nix-config?dir=labs/lint";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, ai-jail, lint }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        jdk = pkgs.jdk25;
        # nixpkgs' `sbt` is a wrapper that hardcodes its own JAVA_HOME at build time; jdk25 on
        # PATH does not change it. `.override { jre = ... }` is the nixpkgs pattern for JVM-tool
        # wrappers. If the argument name changes, `cat $(readlink -f $(command -v sbt))` shows
        # what the wrapper actually hardcodes.
        sbtOnJdk25 = pkgs.sbt.override { jre = jdk; };

        # marola's own tools. Lint comes from labs/lint, appended below.
        projectTools = [
          jdk
          sbtOnJdk25
          pkgs.scala-cli
          pkgs.coursier
          pkgs.just

          # scikit-learn must be inside THIS python3 (withPackages), not a sibling package: a
          # sibling sits in its own store path and is never on `python3`'s import path.
          # scripts/pr_label_nlp.py imports sklearn directly.
          (pkgs.python3.withPackages (ps: with ps; [ pip scikit-learn ]))
          # `uvx` runs GitHub's spec-kit ephemerally (`just specify`); spec-kit is PyPI-only.
          pkgs.uv

          # The default local LLM/vision backend — what lets marola run with zero Azure account.
          # `ollama serve` is started separately (docs/RUN-LOCALLY.md); this only puts it on PATH.
          pkgs.ollama
          pkgs.azure-cli
          pkgs.gh

          # `just context-mips`: repomix packs docs for a browser session, wl-copy/xclip copy them.
          pkgs.repomix
          pkgs.wl-clipboard
          pkgs.xclip
          # `just claude-cost`: npx runs ccusage.
          pkgs.nodejs

          pkgs.jq
          pkgs.git

          # The self-hosted Actions runner for marola-sea-publish.yml (`runs-on: [self-hosted,
          # marola-sea]`): gigabytes of weights, a training run and a Hugging Face token do not
          # belong on shared infrastructure. Register from ~/.marola-runner with config.sh
          # (--labels marola-sea,dependabot); `just ghar` / `just gha` / `just ghas` drive it.
          # `dependabot` is the label GitHub's own Dependabot needs once "Dependabot on
          # self-hosted runners" is enabled; its updater runs in containers, so the runner user
          # needs a reachable Docker.
          pkgs.github-runner

          # ai-jail — sandboxes coding agents behind bubblewrap/Landlock/seccomp. Its test suite
          # needs a sandbox the Nix build sandbox does not provide, hence doCheck = false.
          (ai-jail.packages.${system}.default.overrideAttrs (_: { doCheck = false; }))
          pkgs.bubblewrap

          # OpenCode (MIP-0013), a second agent harness; `opencode.json` is its config.
          pkgs.opencode

          # waydroid CLI only: the LXC container, binder modules and the waydroid-container
          # service are the host's, same shape as ollama above.
          pkgs.waydroid
        ];
      in
      {
        devShells.default = pkgs.mkShell {
          name = "marola";
          packages = projectTools ++ lint.lib.${system}.tools;

          JAVA_HOME = "${jdk}";
          # ai-jail's own devShell sets this; it does not propagate when consumed as a package.
          BWRAP_BIN = "${pkgs.bubblewrap}/bin/bwrap";

          # `azd` is deliberately absent — its nixpkgs packaging status changes. If a deploy needs
          # it: curl -fsSL https://aka.ms/install-azd.sh | bash

          shellHook = ''
            echo "marola dev shell"
            git config core.hooksPath .githooks 2>/dev/null || true
            # Load the gitignored .env like direnv's `dotenv_if_exists` in .envrc does; plain
            # KEY=VALUE lines only.
            marola_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            if [ -f "$marola_root/.env" ]; then
              set -a; . "$marola_root/.env"; set +a
              echo "loaded $marola_root/.env"
            fi
            java -version
            curl -s -m 1 http://localhost:11434/api/tags >/dev/null 2>&1 \
              || echo "ollama not running — start it with 'ollama serve' (see docs/RUN-LOCALLY.md)"
            # ff-only sync of a clean `main`; a no-op otherwise, `timeout` so offline never blocks.
            (cd "$marola_root" && timeout 10s just sync-main) || true
            # A `just worktree` mirror of origin/main looks identical at a prompt; say which this is.
            if [ -n "$marola_root" ] && [ -z "$(git -C "$marola_root" branch --show-current 2>/dev/null)" ]; then
              echo "marola: DETACHED mirror at $(git -C "$marola_root" rev-parse --short HEAD 2>/dev/null) — refresh it with 'just worktree' from the main checkout; commit there, not here"
            fi
            echo "Run 'just' to see available commands."
          '';
        };

        # Only the lint toolchain — what ci.yml uses on the self-hosted runner so CI and
        # `just quality` resolve the same binaries from the same lock.
        devShells.lint = pkgs.mkShell {
          name = "marola-lint";
          packages = lint.lib.${system}.tools;
        };
      });
}
```

- [ ] **Step 3: Lock and enter**

```bash
nix flake lock            # adds `lint` to flake.lock
nix develop --command bash -c 'ruff --version; hadolint --version; actionlint -version; shellcheck --version | sed -n 2p; cloc --version; coverage --version | head -1; pdoc --version; pyflakes --version'
nix develop .#lint --command ruff --version
```
Expected: every tool prints a version; the `lint` shell has ruff but no `sbt`.

- [ ] **Step 4: `ci.yml`**

Replace lines 192-200 (the "cloc + coverage.py from nix" step) with:

```yaml
      # Both tools come from labs/lint (h0ffmann/nix-config) via flake.nix's `lint` input — the
      # same lock `just quality` uses locally. On the self-hosted runner `sudo apt-get` has no
      # tty and hangs until the job times out (run 34709971906); nix does not.
      - name: cloc + coverage.py from the lint shell
        if: runner.environment == 'self-hosted'
        run: |
          nix develop .#lint --command bash -c 'dirname "$(command -v cloc)"; dirname "$(command -v coverage)"' | sort -u >> "$GITHUB_PATH"
```

Replace lines 322-327 (the "shellcheck + pyflakes from nix" step) with:

```yaml
      - name: shellcheck + pyflakes from the lint shell (self-hosted only — the action would apt-get them)
        if: needs.changes.outputs.workflows == 'true' && runner.environment == 'self-hosted'
        run: |
          nix develop .#lint --command bash -c 'dirname "$(command -v shellcheck)"; dirname "$(command -v pyflakes)"' | sort -u >> "$GITHUB_PATH"
```

Lines 266 and 272: change the comment to `# = labs/lint's ruff (nix develop .#lint --command ruff --version)`.

- [ ] **Step 5: Docs**

`PHILOSOPHY.md` "Why Nix": replace `` `az`, `gh`, hadolint. `` with `` `az`, `gh`; the lint toolchain (hadolint, actionlint, shellcheck, ruff, …) comes from `labs/lint` in h0ffmann/nix-config as one flake input. `` `docs/RUN-LOCALLY.md:377`: `(in the flake)` → `(from the lint lab)`.

- [ ] **Step 6: Gates**

```bash
just build && just test && just quality
```
Expected: all green; `quality-other` still finds ruff/actionlint/hadolint on PATH.

- [ ] **Step 7: Commit and PR**

```bash
git add flake.nix flake.lock .github/workflows/ci.yml PHILOSOPHY.md docs/RUN-LOCALLY.md
git commit -F - <<'EOF'
nix: lint toolchain from labs/lint (h0ffmann/nix-config), devShells.lint for CI

Eight packages and their comments leave flake.nix; the list now comes from
the lab as `lint.lib.${system}.tools`. ci.yml resolves cloc/coverage and
shellcheck/pyflakes through `nix develop .#lint` instead of a second
`nix build nixpkgs#...` path, so CI and `just quality` share one lock.

Closes #356

Tested: just build && just test && just quality; nix develop (fresh) and nix develop .#lint print the same versions as before
Co-Authored-By: Claude <noreply@anthropic.com>
EOF
just pr
```
(No `Cost:` line by hand: `just pr` measures it. Never write a placeholder.)

---

### Task 3: nix-config `labs/agentic`

**Files:**
- Create: `labs/agentic/flake.nix`, `justfile`, `README.md`, `.gitignore`, `scripts/gh-token`, `scripts/clip`, `scripts/clip-relay`, `scripts/jail-run`
- Modify: `.github/workflows/ci.yml` (matrix, shellcheck find, self-test loop), `README.md`
- Modify (marola, on the spec branch `docs/nix-tooling-migration-spec`): `docs/superpowers/specs/2026-09-13-nix-tooling-migration-design.md` §3.2 fifo sentence

**Interfaces:**
- Produces: `lib.<system>.tools`, `lib.<system>.env = { BWRAP_BIN }`, `packages.<system>.{gh-token,clip,clip-relay,jail-run}` (Linux: also the tools), `checks.<system>.{gh-token,jail-run}`.
- `jail-run claude|opencode [args]`, `jail-run --dry-run -- <cmd>`, `jail-run --self-test`; `gh-token [--source|--self-test]`; `clip [--text T]`; `clip-relay <fifo>`.

- [ ] **Step 1: Branch, copy the three scripts from marola**

```bash
cd ~/code/nix-config && git checkout -q main && git pull -q --ff-only && git checkout -b labs/agentic
mkdir -p labs/agentic/scripts
M=~/code/marola
git -C "$M" show 3f1e03d:scripts/gh-token.sh   > labs/agentic/scripts/gh-token
git -C "$M" show 3f1e03d:scripts/clip.sh       > labs/agentic/scripts/clip
git -C "$M" show 3f1e03d:scripts/clip-relay.sh > labs/agentic/scripts/clip-relay
chmod +x labs/agentic/scripts/*
printf '/.tmp/*\n/result\n!/.tmp/.gitkeep\n.env\n.direnv/\n' > labs/agentic/.gitignore
mkdir -p labs/agentic/.tmp && touch labs/agentic/.tmp/.gitkeep
```

- [ ] **Step 2: Edit `scripts/gh-token`**

Three edits. Header comment line 12: `prefer the fine-grained key AGENTS.md asks for` → `prefer a fine-grained key from the environment (.env, loaded by the consumer's shell)`. Lines 15-16 (`scripts/gh-token.sh …`) → `gh-token` / `gh-token --source`. The `--help` case: replace `sed -n '2,16p' "$0"` with a heredoc:

```bash
  --help|-h)
    cat <<'EOH'
gh-token — resolve the GitHub token a jailed agent should get, on the HOST side of the jail.
  gh-token            print the token, nothing else
  gh-token --source   print where it came from (env:GH_TOKEN | env:GITHUB_TOKEN | gh:host-login | none)
Order: GH_TOKEN, then GITHUB_TOKEN, then the host's `gh auth token`. Never a login inside the jail.
EOH
    exit 0 ;;
```

- [ ] **Step 3: Edit `scripts/clip` and `scripts/clip-relay`**

`clip`: header lines 2-6 become

```bash
# clip — write-only clipboard push, from inside the ai-jail sandbox or outside it.
#   printf 'hello' | clip        # copy stdin
#   clip --text "hello"          # copy an argument
# No paste/read counterpart exists here or in clip-relay, by design: the jail may write to the
# host clipboard, never read it. JAIL_CLIPBOARD_PASTE=1 on jail-run is the (bigger) grant for that.
```
Both `MAROLA_JAIL_CLIPBOARD=1 just jail-claude` messages → `JAIL_CLIPBOARD=1 jail-run claude`.

`clip-relay`: line 2-3 comment → `# clip-relay — host-side half of the write-only clipboard bridge jail-run starts with JAIL_CLIPBOARD=1.`; `MAROLA_CLIP_SINK` → `CLIP_SINK` (both occurrences).

- [ ] **Step 4: Write `scripts/jail-run`**

```bash
#!/usr/bin/env bash
# jail-run — run a coding agent inside ai-jail from the current directory (which holds the
# project's .ai-jail policy), with the host's GitHub token forwarded.
set -euo pipefail

usage() {
  cat <<'EOH'
jail-run — a coding agent inside ai-jail, from the current directory
  jail-run claude [args]        Claude Code; ~/.claude and ~/.claude.json mapped rw, ~/.ssh ro
  jail-run opencode [args]      OpenCode; its own ~/.config, ~/.local/share, ~/.cache dirs mapped rw
  jail-run --dry-run -- <cmd>   print the sandbox invocation ai-jail would use, run nothing
  JAIL_CLIPBOARD=1              write-only clipboard bridge — `clip` inside the jail reaches the host
  JAIL_CLIPBOARD_PASTE=1        real X11/Wayland display passthrough — the jail can READ your clipboard
The GitHub token is resolved on the host by gh-token and forwarded as GH_TOKEN: ~/.config/gh is
never mapped in, so a login inside the jail would die with it.
EOH
}

self_test() {
  local fails=0 tmp fake out
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails+1)); fi; }
  tmp="$(mktemp -d)"; fake="$tmp/bin"; mkdir -p "$fake"
  printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\n" "$@"' > "$fake/ai-jail"; chmod +x "$fake/ai-jail"
  # a gh-token stand-in so the test does not depend on the host's login state
  printf '%s\n' '#!/usr/bin/env bash' '[ "${1:-}" = --source ] && { echo env:GH_TOKEN; exit 0; }' 'echo tok' > "$fake/gh-token"; chmod +x "$fake/gh-token"

  ok "$(bash "$0" >/dev/null 2>&1; echo $?)" "2" "no mode is a usage error"
  ok "$(PATH="$fake:$PATH" bash "$0" --dry-run -- true | tr '\n' ' ')" "--no-save-config --dry-run -- true " "--dry-run forwards the command after -- and runs nothing else"
  out="$(cd "$tmp" && GH_TOKEN=t PATH="$fake:$PATH" bash "$0" claude --model x 2>/dev/null | tr '\n' ' ')"
  ok "$(echo "$out" | grep -c -- '--env GH_TOKEN')" "1" "claude: GH_TOKEN is forwarded by name"
  ok "$(echo "$out" | grep -c -- '--exec claude --model x')" "1" "claude: the agent and its args come last"
  ok "$(echo "$out" | grep -c -- '--display')" "0" "claude: no display passthrough unless asked"
  out="$(cd "$tmp" && GH_TOKEN=t JAIL_CLIPBOARD_PASTE=1 PATH="$fake:$PATH" bash "$0" claude 2>/dev/null | tr '\n' ' ')"
  ok "$(echo "$out" | grep -c -- '--display --env DISPLAY')" "1" "JAIL_CLIPBOARD_PASTE=1 adds the display flags"
  out="$(cd "$tmp" && GH_TOKEN=t PATH="$fake:$PATH" bash "$0" opencode 2>/dev/null | tr '\n' ' ')"
  ok "$(echo "$out" | grep -c -- '--rw-map ~/.config/opencode')" "1" "opencode: its own state dirs, not Claude's"
  ok "$(echo "$out" | grep -c -- '~/.claude')" "0" "opencode: Claude's dirs are not mapped"
  ok "$(echo "$out" | grep -c -- '--exec --env GH_TOKEN opencode')" "1" "opencode: token forwarded, agent last"
  ok "$(cd "$tmp" && GH_TOKEN=t PATH="$fake:$PATH" bash "$0" claude 2>&1 >/dev/null | grep -c 'GH_TOKEN from env:GH_TOKEN')" "1" "says where the token came from, never the token"
  ok "$(cd "$tmp" && GH_TOKEN=t PATH="$fake:$PATH" bash "$0" claude 2>&1 | grep -c '^t$')" "0" "the token value is never printed"
  out="$(cd "$tmp" && GH_TOKEN=t JAIL_CLIPBOARD=1 CLIP_SINK='cat >/dev/null' PATH="$fake:$PATH" bash "$0" claude 2>/dev/null | tr '\n' ' ')"
  ok "$(echo "$out" | grep -c -- '--exec claude')" "1" "JAIL_CLIPBOARD=1: the jail still starts with a relay"
  ok "$([ -p "$tmp/.tmp/clip.fifo" ] && echo present || echo gone)" "gone" "and the fifo is removed when the jail exits"
  rm -rf "$tmp"
  if [ "$fails" -eq 0 ]; then echo "jail-run self-test: ok"; return 0; fi
  echo "jail-run self-test: $fails failure(s)" >&2; return 1
}

mode="${1:-}"
case "$mode" in
  --self-test) self_test; exit $? ;;
  --help|-h)   usage; exit 0 ;;
  --dry-run)   shift; [ "${1:-}" = "--" ] && shift; ai-jail --no-save-config --dry-run -- "$@"; exit $? ;;
  claude|opencode) shift ;;
  *)           usage >&2; exit 2 ;;
esac

relay_pid=""
cleanup() {
  if [ -n "$relay_pid" ]; then
    kill -TERM "$relay_pid" 2>/dev/null || true
    wait "$relay_pid" 2>/dev/null || true
  fi
  rm -f .tmp/clip.fifo
}
trap cleanup EXIT INT TERM

# The fifo lives under the project's .tmp/: the project directory is what ai-jail maps into the
# sandbox, so a path under $XDG_RUNTIME_DIR would be invisible from inside.
if [ "$mode" = claude ] && [ "${JAIL_CLIPBOARD:-0}" = "1" ]; then
  mkdir -p .tmp
  fifo=".tmp/clip.fifo"
  rm -f "$fifo"
  mkfifo "$fifo"
  clip-relay "$fifo" &
  relay_pid=$!
  sleep 0.2
  if ! kill -0 "$relay_pid" 2>/dev/null; then
    wait "$relay_pid" 2>/dev/null || true
    relay_pid=""
  fi
fi

paste_flags=()
if [ "${JAIL_CLIPBOARD_PASTE:-0}" = "1" ]; then
  echo "jail-run: JAIL_CLIPBOARD_PASTE=1 — real X11/Wayland display passthrough is on, the jail can read your clipboard (and, on X11, more)" >&2
  paste_flags=(--display --env DISPLAY --env WAYLAND_DISPLAY --env "XDG_RUNTIME_DIR=/run/user/$(id -u)")
fi

if src="$(gh-token --source)"; then
  GH_TOKEN="$(gh-token)"
  export GH_TOKEN
  echo "jail-run: GH_TOKEN from $src — gh works inside the jail, no login needed" >&2
else
  echo "jail-run: no GitHub token (no GH_TOKEN in the environment, and gh is logged out on the host)." >&2
  echo "          gh will not work inside the jail. Fix once, on the host: gh auth login   (or export GH_TOKEN)" >&2
fi

case "$mode" in
  claude)
    ai-jail --no-save-config --rw-map ~/.claude --rw-map ~/.claude.json --map ~/.ssh --network --terminal-passthrough --exec --env GH_TOKEN "${paste_flags[@]}" claude "$@" ;;
  opencode)
    ai-jail --no-save-config --rw-map ~/.config/opencode --rw-map ~/.local/share/opencode --rw-map ~/.cache/opencode --network --terminal-passthrough --exec --env GH_TOKEN opencode "$@" ;;
esac
```

Note the empty `"${paste_flags[@]}"` under `set -u` is fine on bash ≥ 4.4. No `exec` before `ai-jail`, so the EXIT trap still stops the relay. The `--dry-run` self-test expects exactly `--no-save-config --dry-run -- true`.

- [ ] **Step 5: Run the self-tests from source**

```bash
cd ~/code/nix-config/labs/agentic
scripts/gh-token --self-test && PATH="$PWD/scripts:$PATH" scripts/jail-run --self-test
nix run nixpkgs#shellcheck -- scripts/*
```
Expected: `gh-token self-test: ok`, `jail-run self-test: ok`, shellcheck silent. (`scripts/clip` in the sandbox test above is never reached; `clip-relay` with `CLIP_SINK` set is.)

- [ ] **Step 6: Write `labs/agentic/flake.nix`**

```nix
{
  description = "agentic — ai-jail, OpenCode, gh, and the host-side scripts (gh-token, clip, clip-relay, jail-run) for running coding agents sandboxed";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    ai-jail = {
      url = "github:akitaonrails/ai-jail";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, ai-jail }:
    let
      inherit (nixpkgs) lib;
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forAll = f: lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});

      # One script file, run from source by CI and wrapped here for consumers. `runtimeInputs`
      # never lists a tool the script's own --self-test fakes on PATH (the wrapper prepends
      # runtimeInputs, which would shadow the fake): gh-token finds `gh` on the caller's PATH,
      # jail-run finds `ai-jail` there.
      script = pkgs: name: runtimeInputs: pkgs.writeShellApplication {
        inherit name runtimeInputs;
        text = builtins.readFile ./scripts/${name};
      };
      scriptsFor = pkgs: rec {
        gh-token = script pkgs "gh-token" [ ];
        clip = script pkgs "clip" [ pkgs.coreutils ];
        clip-relay = script pkgs "clip-relay" [ pkgs.coreutils ];
        jail-run = script pkgs "jail-run" [ gh-token clip-relay ];
      };

      # ai-jail's test suite needs a working sandbox at build time, which the Nix build sandbox
      # does not provide — same override marola and labs/pratico use.
      aiJailFor = pkgs: ai-jail.packages.${pkgs.system}.default.overrideAttrs (_: { doCheck = false; });

      toolsFor = pkgs:
        let s = scriptsFor pkgs; in
        [ pkgs.gh pkgs.opencode s.gh-token s.clip ]
        ++ lib.optionals pkgs.stdenv.hostPlatform.isLinux [
          (aiJailFor pkgs)
          pkgs.bubblewrap
          pkgs.wl-clipboard # clip / clip-relay sinks
          pkgs.xclip
          s.clip-relay
          s.jail-run
        ];

      envFor = pkgs: lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
        # ai-jail's own devShell sets this; it does not propagate when consumed as a package.
        BWRAP_BIN = "${pkgs.bubblewrap}/bin/bwrap";
      };

      selfTest = pkgs: drv: pkgs.runCommand "${drv.name}-self-test" { } ''
        ${drv}/bin/${drv.name} --self-test | tee "$out"
      '';
    in
    {
      lib = forAll (pkgs: {
        tools = toolsFor pkgs;
        env = envFor pkgs;
        scripts = scriptsFor pkgs;
      });

      packages = forAll (pkgs: scriptsFor pkgs);

      devShells = forAll (pkgs: {
        default = pkgs.mkShell ({
          name = "agentic";
          packages = toolsFor pkgs;
          shellHook = ''echo "agentic: gh $(gh --version | head -1 | cut -d' ' -f3) | opencode $(opencode --version 2>/dev/null || echo ?) | $(command -v ai-jail >/dev/null && echo ai-jail || echo 'ai-jail: Linux only')"'';
        } // envFor pkgs);
      });

      # Built by `nix flake check`: the scripts' own --self-test runs, in the sandbox, against
      # fakes on PATH — no gh login, no ai-jail, no display needed.
      checks = forAll (pkgs:
        let s = scriptsFor pkgs; in
        {
          gh-token = selfTest pkgs s.gh-token;
          jail-run = selfTest pkgs s.jail-run;
        });
    };
}
```

- [ ] **Step 7: `justfile`, README**

```just
# agentic — ai-jail, OpenCode, gh, and the host-side scripts for sandboxed coding agents.
# The jail recipes run in YOUR directory (`[no-cd]`), which must hold the project's `.ai-jail`:
#   just -f <nix-config>/labs/agentic/justfile jco
set shell := ["bash", "-euo", "pipefail", "-c"]
here := justfile_directory()

default:
    @just --list

# Enter the shell: ai-jail, opencode, gh, gh-token, clip, jail-run on PATH, in your current directory.
shell:
    nix develop "{{here}}"

# Run every script's --self-test from source (what CI runs before the flake check).
self-test:
    "{{here}}/scripts/gh-token" --self-test
    PATH="{{here}}/scripts:$PATH" "{{here}}/scripts/jail-run" --self-test

# Print the sandbox invocation ai-jail would use for <cmd>, without running it.
[no-cd]
jail-dry-run *cmd:
    jail-run --dry-run -- {{cmd}}

# Claude Code in the jail, from the current directory.
[no-cd]
jail-claude *args:
    jail-run claude {{args}}

# jail-claude with --model fable / sonnet / opus.
[no-cd]
jcf *args: (jail-claude "--model" "fable" args)
[no-cd]
jcs *args: (jail-claude "--model" "sonnet" args)
[no-cd]
jco *args: (jail-claude "--model" "opus" args)

# OpenCode in the jail, from the current directory.
[no-cd]
jail-opencode *args:
    jail-run opencode {{args}}
[no-cd]
jo *args: (jail-opencode args)

# Say which GitHub credential the host has for the jail to borrow (never log in inside a jail).
gh-auth:
    #!/usr/bin/env bash
    set -euo pipefail
    if src="$(gh-token --source)"; then echo "gh-auth: $src — jail-run forwards it as GH_TOKEN"; else echo "gh-auth: none — run: gh auth login   (on the host)"; exit 1; fi

# Push stdin (or --text "…") to the clipboard, write-only.
[no-cd]
clip *args:
    clip {{args}}
```

README: the lab's purpose, the four scripts (one line each), the `.ai-jail` example (copy `labs/pratico/.ai-jail`, it is the same policy), the consumer snippet:

```nix
inputs.agentic = { url = "github:h0ffmann/nix-config?dir=labs/agentic"; inputs.nixpkgs.follows = "nixpkgs"; };
# in the consumer's mkShell:
packages = projectTools ++ agentic.lib.${system}.tools;
BWRAP_BIN = agentic.lib.${system}.env.BWRAP_BIN or null;
```
and the consumer's recipes as one-liners (`jail-claude *args: jail-run claude {{args}}`). State the two env opt-ins and the fifo-under-`.tmp/` rule. Note: `labs/pratico` still carries its own copies; making it consume this lab is a follow-up.

- [ ] **Step 8: Lock, check, lint; CI and root README**

```bash
cd ~/code/nix-config/labs/agentic
nix flake lock && nix flake check --no-update-lock-file --print-build-logs .
nix run nixpkgs#nixpkgs-fmt -- --check . && nix run nixpkgs#statix -- check . && nix run nixpkgs#deadnix -- --fail .
just --list && just self-test
nix build .#jail-run && ./result/bin/jail-run --help
```
Expected: checks `gh-token` and `jail-run` build and print `... self-test: ok`.

`.github/workflows/ci.yml`: matrix `lab: [pratico, publisher, lint, agentic]`; the shellcheck step's `find` becomes `find . \( -name '*.sh' -o -path './scripts/*' -type f -perm -u+x \) -not -path './result*' | sort`; the self-test step becomes

```yaml
      - name: script self-tests (every executable under scripts/ that has a --self-test mode)
        run: |
          ran=0
          for s in scripts/*; do
            if [ -x "$s" ] && grep -q -- '--self-test)' "$s"; then PATH="$PWD/scripts:$PATH" "$s" --self-test; ran=1; fi
          done
          [ "$ran" -eq 1 ] || echo "no self-tests in this lab"
```
(pratico's `scripts/gh-token.sh` still matches.) Root README: a `## agentic` section (purpose, the `just` lines), Structure tree line, Flake outputs block, "Used by" marola.

- [ ] **Step 9: Commit, PR, and the spec correction**

```bash
cd ~/code/nix-config && git add labs/agentic .github/workflows/ci.yml README.md
git commit -m "feat(agentic): ai-jail, OpenCode, gh and the host-side scripts as a lab

gh-token, clip, clip-relay come from marola unchanged but for names;
jail-run is marola's jail-claude/jail-opencode recipe bodies as one
script with a --self-test against a fake ai-jail. Refs h0ffmann/marola#357"
git push -u origin labs/agentic && gh pr create --fill --title "labs/agentic: the agent sandbox as a lab" --body "Refs h0ffmann/marola#357. checks: gh-token and jail-run self-tests in the sandbox."
```
Then in marola, on branch `docs/nix-tooling-migration-spec`, replace the §3.2 sentence "the fifo path moves from `<repo>/.tmp/clip.fifo` to `$XDG_RUNTIME_DIR/ai-jail-clip.fifo` so it does not depend on a repo layout." with "the fifo stays at `<project>/.tmp/clip.fifo`: the project directory is what ai-jail maps into the sandbox, so anything under `$XDG_RUNTIME_DIR` would be invisible from inside." and drop the §6 bullet clause "`jail-run` changes the fifo path and". Commit `docs: spec — the clipboard fifo stays under the project's .tmp/`, push, PR #359 updates.

---

### Task 4: marola consumes `labs/agentic`

**Files:**
- Modify: `flake.nix` (input, drop ai-jail input + 4 packages + BWRAP_BIN literal), `flake.lock`
- Modify: `justfile` recipes `jail-dry-run`, `gh-auth`, `jail-claude`, `jail-opencode`, `clip`; `quality-other` list
- Delete: `scripts/gh-token.sh`, `scripts/clip.sh`, `scripts/clip-relay.sh`
- Modify: `scripts/runner-preflight.sh:64-67`, `AGENTS.md:155-175`, `docs/DEV-FLOW.md:283-284`, `.claude/hooks/session-start.sh:22-23`

**Interfaces:**
- Consumes: `agentic.lib.${system}.tools`, `.env.BWRAP_BIN`, binaries `jail-run`, `gh-token`, `clip`.

- [ ] **Step 1: Branch on top of Task 2**

```bash
cd ~/code/marola && git checkout feat/nix-lint && git checkout -b feat/nix-agentic
```

- [ ] **Step 2: `flake.nix`**

In `inputs`: delete the `ai-jail` block; add

```nix
    agentic = {
      url = "github:h0ffmann/nix-config?dir=labs/agentic";
      inputs.nixpkgs.follows = "nixpkgs";
    };
```
`outputs = { self, nixpkgs, flake-utils, lint, agentic }:`. In `projectTools` delete `pkgs.gh`, the ai-jail override line and its comment, `pkgs.bubblewrap`, the `pkgs.opencode` line and comment. `packages = projectTools ++ lint.lib.${system}.tools ++ agentic.lib.${system}.tools;`. Replace the `BWRAP_BIN = "${pkgs.bubblewrap}/bin/bwrap";` line and its comment with `inherit (agentic.lib.${system}.env) BWRAP_BIN;`. (`wl-clipboard`/`xclip` stay in `projectTools` for repomix; the duplicate from the lab is harmless.)

```bash
nix flake lock && nix develop --command bash -c 'command -v jail-run gh-token clip ai-jail opencode gh; echo "BWRAP_BIN=$BWRAP_BIN"'
```
Expected: six store paths and a non-empty `BWRAP_BIN`.

- [ ] **Step 3: `justfile`**

`jail-dry-run`:
```just
jail-dry-run *cmd:
    jail-run --dry-run -- {{cmd}}
```
`gh-auth`: keep the `--refresh`/`--login` cases; replace both `scripts/gh-token.sh` with `gh-token`.
`jail-claude` (whole body):
```just
# Claude Code in the jail — labs/agentic's jail-run (h0ffmann/nix-config). MAROLA_JAIL_CLIPBOARD*
# still work for one release; the lab's names are JAIL_CLIPBOARD / JAIL_CLIPBOARD_PASTE.
jail-claude *args:
    JAIL_CLIPBOARD="${JAIL_CLIPBOARD:-${MAROLA_JAIL_CLIPBOARD:-0}}" JAIL_CLIPBOARD_PASTE="${JAIL_CLIPBOARD_PASTE:-${MAROLA_JAIL_CLIPBOARD_PASTE:-0}}" jail-run claude {{args}}
```
`jail-opencode *args:` → `jail-run opencode {{args}}`. `clip *args:` → `clip {{args}}`. `quality-other`: delete the `scripts/gh-token.sh --self-test` line. Delete the long comment block above `jail-claude` that diagnoses the PTY proxy, keeping one line: `# --exec (no PTY proxy) is what makes Ctrl+C and bracketed paste work; the diagnosis is in labs/agentic's README.` Move the sentence AGENTS.md cites ("see the justfile comment above `jail-claude`") accordingly in Step 5.

- [ ] **Step 4: Delete scripts, fix the preflight**

```bash
git rm scripts/gh-token.sh scripts/clip.sh scripts/clip-relay.sh
```
`scripts/runner-preflight.sh` line 67: `"$script_dir/gh-token.sh" 2>/dev/null || true` → `gh-token 2>/dev/null || true`.

- [ ] **Step 5: Docs**

`AGENTS.md` jail paragraph: `via scripts/gh-token.sh` → `via labs/agentic's gh-token (h0ffmann/nix-config, a flake input)`; `MAROLA_JAIL_CLIPBOARD=1` → `JAIL_CLIPBOARD=1`; `MAROLA_JAIL_CLIPBOARD_PASTE=1` → `JAIL_CLIPBOARD_PASTE=1`; "see the justfile comment above `jail-claude` for the full diagnosis" → "see labs/agentic's README for the full diagnosis". `docs/DEV-FLOW.md:283-284`: the two env names likewise. `.claude/hooks/session-start.sh:23`: unchanged text is still true; line 22 unchanged.

- [ ] **Step 6: Gates and the jail itself**

```bash
just build && just test && just quality
just jail-dry-run true            # compare with `git stash; just jail-dry-run true; git stash pop` — identical bwrap line
just gh-auth
```
Then, on the host (not from inside a jail): `just jco` starts, prints `jail-run: GH_TOKEN from …`, and `gh pr list` works inside it.

- [ ] **Step 7: Commit and PR**

```bash
git add flake.nix flake.lock justfile scripts/runner-preflight.sh AGENTS.md docs/DEV-FLOW.md .claude/hooks/session-start.sh
git commit -F - <<'EOF'
nix: agent sandbox from labs/agentic (h0ffmann/nix-config)

ai-jail, bubblewrap, opencode and gh come from the lab; jail-claude,
jail-opencode, jail-dry-run, gh-auth and clip are one-line wrappers over
jail-run / gh-token / clip. scripts/gh-token.sh, clip.sh and clip-relay.sh
are deleted (the lab's copies carry the self-tests). MAROLA_JAIL_CLIPBOARD*
keep working for one release through the wrapper.

Closes #357

Tested: just build && just test && just quality; just jail-dry-run true byte-equal to before; one just jco session on the host with gh pr list working inside
Co-Authored-By: Claude <noreply@anthropic.com>
EOF
just pr
```

---

### Task 5: nix-config `labs/cuda`

**Files:**
- Create: `labs/cuda/flake.nix`, `justfile`, `README.md`, `scripts/setup-cuda-cache`, `scripts/setup-ml-venv`
- Modify: `.github/workflows/ci.yml` matrix, `README.md`

**Interfaces:**
- Produces: `lib.x86_64-linux.tools`, `packages.x86_64-linux.{setup-cuda-cache,setup-ml-venv}`, `checks.x86_64-linux.{setup-cuda-cache,setup-ml-venv}`.
- `setup-cuda-cache [--dry-run|--remove|--verify [expr-file]|--self-test]`; `REQUIREMENTS=<file> [VENV_ROOT=…] [CUDA_INDEX=…] setup-ml-venv [--path|--self-test]` writing `$VENV_ROOT/bin/python-cuda`.

- [ ] **Step 1: Branch, copy**

```bash
cd ~/code/nix-config && git checkout -q main && git pull -q --ff-only && git checkout -b labs/cuda
mkdir -p labs/cuda/scripts
git -C ~/code/marola show 3f1e03d:scripts/setup-cuda-cache.sh > labs/cuda/scripts/setup-cuda-cache
git -C ~/code/marola show 3f1e03d:scripts/setup-ml-venv.sh    > labs/cuda/scripts/setup-ml-venv
chmod +x labs/cuda/scripts/*
```

- [ ] **Step 2: Edit `scripts/setup-cuda-cache`**

- Header lines 2-7: drop "Without it a marola-sea GPU run… MIP-0025."; usage lines say `setup-cuda-cache --dry-run`, `sudo setup-cuda-cache`, `sudo setup-cuda-cache --remove`, `setup-cuda-cache --verify [expr-file]`.
- `usage()` → a heredoc with those four lines.
- `block()` marker line: `# marola: CUDA binary cache (scripts/setup-cuda-cache.sh).` → `# nix-config/labs/cuda: CUDA binary cache (setup-cuda-cache).`
- `remove_block`: the sed range start `/^# marola: CUDA binary cache/` → `/^# \(marola\|nix-config\/labs\/cuda\): CUDA binary cache/` so a block written by the old marola script is still removed.
- `verify()`:
```bash
verify() {
  # The check that matters: does CUDA torch resolve as a fetch rather than a build?
  local env_expr="${1:-}"
  if [ -n "$env_expr" ]; then
    [ -f "$env_expr" ] || { echo "verify: no such file $env_expr"; return 2; }
    nix build --dry-run --impure --expr "$(cat "$env_expr")" 2>&1
  else
    NIXPKGS_ALLOW_UNFREE=1 nix build --dry-run --impure 'nixpkgs#python3Packages.torchWithCuda' 2>&1
  fi | grep -E 'will be built|will be fetched' || echo "nothing to do — already realised"
}
```
- self-test line `ok "$(grep -c 'cachix\|trusted-users\|marola' "$stale")"` → `'cachix\|trusted-users\|CUDA binary cache'`; add after it: `printf '# nix-config/labs/cuda: CUDA binary cache (x)\ntrusted-users = root me\nextra-substituters = %s\nextra-trusted-public-keys = k\n' "$CACHE" > "$stale"; remove_block "$stale"; ok "$(wc -c < "$stale" | tr -d ' ')" "0" "remove_block also strips a block this lab wrote"`.
- The two `echo "verify with: scripts/setup-cuda-cache.sh --verify"` / `"  scripts/setup-cuda-cache.sh --verify"` → `setup-cuda-cache --verify`.

- [ ] **Step 3: Edit `scripts/setup-ml-venv`**

- Header lines 2-7:
```bash
# Create (or update) a Python venv with CUDA torch from PyTorch's own wheel index, not nixpkgs
# (nixpkgs' torchWithCuda has no binary cache for most pins and compiles torch, magma and triton).
#   REQUIREMENTS=finetune/requirements.txt setup-ml-venv          # create/update at ~/.ml-venv
#   REQUIREMENTS=... VENV_ROOT=/path setup-ml-venv
#   setup-ml-venv --path                                          # print the wrapper's path
# Call $VENV_ROOT/bin/python-cuda afterwards, never bin/python: the wrapper puts libstdc++ and the
# NVIDIA driver libraries on the search path.
```
- After `PY=…`: `REQUIREMENTS="${REQUIREMENTS:-}"`, `WRAPPER="python-cuda"`, `LIBSTDCXX_DIR="${LIBSTDCXX_DIR:-@libstdcxx@}"`.
- `VENV_ROOT` default `$HOME/.ml-venv`.
- `usage()` → heredoc of the header lines.
- `pkgs_from_requirements`: `finetune/requirements.txt` → `"$REQUIREMENTS"`.
- `libstdcxx_dir()`: first branch becomes `if [ -e "$LIBSTDCXX_DIR/libstdc++.so.6" ]; then echo "$LIBSTDCXX_DIR"; return 0; fi` (the wrapper bakes the nix path in at build; from source the literal `@libstdcxx@` misses and the nix/distro lookup follows, unchanged).
- Wrapper comment line `# Written by scripts/setup-ml-venv.sh.` → `# Written by setup-ml-venv (nix-config/labs/cuda).`
- Every `marola-python` → `$WRAPPER` (four places, the `--path` case included).
- Self-test: at the top `local req; req="$(mktemp)"; printf 'torch==2.8.0\ntransformers>=4.44\nnumpy; python_version >= "3.9"\n# a comment\n\n' > "$req"; REQUIREMENTS="$req"`; delete the three `for dep in gguf sentencepiece protobuf` lines and their comment (they assert a consumer's requirements, which moves to marola's `finetune/merge_export.py`); `rm -f "$req"` before the final verdict.
- Main body: after the `case`, add `[ -n "$REQUIREMENTS" ] && [ -f "$REQUIREMENTS" ] || { echo "setup-ml-venv: REQUIREMENTS=<file> is required" >&2; usage >&2; exit 2; }`.

- [ ] **Step 4: Run from source**

```bash
cd ~/code/nix-config/labs/cuda
scripts/setup-cuda-cache --self-test && scripts/setup-ml-venv --self-test && nix run nixpkgs#shellcheck -- scripts/*
```
Expected: both `self-test: ok` (the driver-library line prints `skip` on a host without a GPU).

- [ ] **Step 5: `flake.nix`**

```nix
{
  description = "cuda — host setup for a CUDA box: the nixos-cuda binary cache in nix.custom.conf, and a torch venv from PyTorch's wheel index with libstdc++ and the driver libraries on its path";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      inherit (nixpkgs) lib;
      systems = [ "x86_64-linux" ]; # CUDA wheels and the driver-library layout are x86_64 Linux only
      forAll = f: lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});

      setupCudaCacheFor = pkgs: pkgs.writeShellApplication {
        name = "setup-cuda-cache";
        runtimeInputs = [ pkgs.coreutils pkgs.gnused pkgs.gnugrep ];
        text = builtins.readFile ./scripts/setup-cuda-cache;
      };
      # The nix libstdc++ is baked in, so the wrapper never shells out to `nix build` at run time.
      setupMlVenvFor = pkgs: pkgs.writeShellApplication {
        name = "setup-ml-venv";
        runtimeInputs = [ pkgs.python3 pkgs.coreutils pkgs.gnugrep pkgs.gnused ];
        text = builtins.replaceStrings [ "@libstdcxx@" ] [ "${pkgs.stdenv.cc.cc.lib}/lib" ] (builtins.readFile ./scripts/setup-ml-venv);
      };
      toolsFor = pkgs: [ (setupCudaCacheFor pkgs) (setupMlVenvFor pkgs) ];

      selfTest = pkgs: drv: pkgs.runCommand "${drv.name}-self-test" { } ''
        ${drv}/bin/${drv.name} --self-test | tee "$out"
      '';
    in
    {
      lib = forAll (pkgs: { tools = toolsFor pkgs; });

      packages = forAll (pkgs: {
        setup-cuda-cache = setupCudaCacheFor pkgs;
        setup-ml-venv = setupMlVenvFor pkgs;
      });

      devShells = forAll (pkgs: {
        default = pkgs.mkShell {
          name = "cuda";
          packages = toolsFor pkgs;
          shellHook = ''echo "cuda: setup-cuda-cache --dry-run | REQUIREMENTS=<file> setup-ml-venv"'';
        };
      });

      # The generic halves only: no GPU, no root, no network in the sandbox.
      checks = forAll (pkgs: {
        setup-cuda-cache = selfTest pkgs (setupCudaCacheFor pkgs);
        setup-ml-venv = selfTest pkgs (setupMlVenvFor pkgs);
      });
    };
}
```

- [ ] **Step 6: `justfile`, README, lock, check, CI, root README**

```just
# cuda — host setup for a CUDA box. `just` lists recipes.
set shell := ["bash", "-euo", "pipefail", "-c"]
here := justfile_directory()

default:
    @just --list

shell:
    nix develop "{{here}}"

# Run both scripts' --self-test from source (what CI runs before the flake check).
self-test:
    "{{here}}/scripts/setup-cuda-cache" --self-test
    "{{here}}/scripts/setup-ml-venv" --self-test

# Show the nix.custom.conf block the cache setup would write; `sudo just cache-setup apply` writes it.
cache-setup mode="--dry-run":
    nix run "{{here}}#setup-cuda-cache" -- {{ if mode == "apply" { "" } else { mode } }}

# Build a CUDA torch venv for <requirements-file> at VENV_ROOT (default ~/.ml-venv).
[no-cd]
venv requirements:
    REQUIREMENTS="{{requirements}}" nix run "{{here}}#setup-ml-venv"
```

README: what the two scripts do, the `python-cuda` wrapper rule, the Determinate Nix `nix.custom.conf` note, the consumer snippet (`lib.optionals (system == "x86_64-linux") cuda.lib.${system}.tools`), and that CI runs only the self-tests. Then:

```bash
cd ~/code/nix-config/labs/cuda && nix flake lock && nix flake check --no-update-lock-file --print-build-logs .
nix run nixpkgs#nixpkgs-fmt -- --check . && nix run nixpkgs#statix -- check . && nix run nixpkgs#deadnix -- --fail . && just --list && just self-test
```
`ci.yml` matrix: `lab: [pratico, publisher, lint, agentic, cuda]`. Root README: `## cuda` section, Structure line, Flake outputs block.

- [ ] **Step 7: Commit, PR; then the one real run**

```bash
cd ~/code/nix-config && git add labs/cuda .github/workflows/ci.yml README.md
git commit -m "feat(cuda): CUDA host setup as a lab — binary cache and torch venv scripts

From marola's scripts/setup-cuda-cache.sh and setup-ml-venv.sh, with the
repo names removed: REQUIREMENTS=<file> is an argument, the wrapper is
bin/python-cuda, the nix libstdc++ path is baked in at build.
Refs h0ffmann/marola#358"
git push -u origin labs/cuda && gh pr create --fill --title "labs/cuda: CUDA host setup as a lab" --body "Refs h0ffmann/marola#358. checks: both self-tests in the sandbox; the real venv run is manual on the 4090 box."
```
On the workstation, before merging: `nix run ./labs/cuda#setup-cuda-cache -- --dry-run`, then `REQUIREMENTS=~/code/marola/finetune/requirements.txt VENV_ROOT=/tmp/ml-venv-check nix run ./labs/cuda#setup-ml-venv` must end with `cuda_available=True`. Paste both outputs into the PR.

---

### Task 6: marola consumes `labs/cuda`

**Files:**
- Modify: `flake.nix`, `flake.lock`, `justfile` (`ml-venv`, `gpu-cache-setup`, `quality-other`), `.github/workflows/marola-sea-publish.yml:3,80,123,180,200`, `finetune/merge_export.py:158-` (self-test)
- Delete: `scripts/setup-cuda-cache.sh`, `scripts/setup-ml-venv.sh`

**Interfaces:**
- Consumes: `cuda.lib.x86_64-linux.tools`; binaries `setup-cuda-cache`, `setup-ml-venv`.

- [ ] **Step 1: Branch on top of Task 4**

```bash
cd ~/code/marola && git checkout feat/nix-agentic && git checkout -b feat/nix-cuda
```

- [ ] **Step 2: `flake.nix`**

Add input `cuda` (same shape, `?dir=labs/cuda`); add `cuda` to the outputs args; `packages = projectTools ++ lint.lib.${system}.tools ++ agentic.lib.${system}.tools ++ pkgs.lib.optionals (system == "x86_64-linux") cuda.lib.${system}.tools;`. `nix flake lock`. (On aarch64 the lab has no `lib.<system>` attribute; the `optionals` guard is evaluated lazily, so `nix flake check --all-systems` still passes.)

- [ ] **Step 3: `justfile` and workflow**

```just
# Create/update the venv marola-sea trains in — labs/cuda's setup-ml-venv (h0ffmann/nix-config).
ml-venv *args:
    REQUIREMENTS=finetune/requirements.txt VENV_ROOT="${VENV_ROOT:-$HOME/.marola-ml-venv}" setup-ml-venv {{args}}

# One-time host setup: the CUDA binary cache, so torchWithCuda is fetched, not compiled.
gpu-cache-setup *args:
    setup-cuda-cache {{args}}
```
`quality-other`: delete the two `scripts/setup-*.sh --self-test` lines. `marola-sea-publish.yml`: line 3 `(scripts/setup-ml-venv.sh)` → `(labs/cuda's setup-ml-venv, via flake.nix)`; line 80 `scripts/setup-ml-venv.sh` → `REQUIREMENTS=finetune/requirements.txt nix develop --command setup-ml-venv`; lines 123, 180, 200 `bin/marola-python` → `bin/python-cuda`.

- [ ] **Step 4: Delete, and move the requirements assertion**

```bash
git rm scripts/setup-cuda-cache.sh scripts/setup-ml-venv.sh
```
In `finetune/merge_export.py`'s `self_test()`, add before its final verdict:

```python
    # merge_export shells out to llama.cpp's convert_hf_to_gguf.py, whose vocab probe catches
    # only FileNotFoundError: a missing sentencepiece surfaces as ModuleNotFoundError and kills
    # the run. These three must stay in the venv's requirements (setup-ml-venv installs the file
    # as given; it does not know what this script needs).
    req = (Path(__file__).parent / "requirements.txt").read_text()
    for dep in ("gguf", "sentencepiece", "protobuf"):
        ok(dep in req, f"{dep} is in finetune/requirements.txt — convert_hf_to_gguf.py needs it")
```
(`ok(cond, msg)` is the helper the function already uses; `Path` is already imported. Check the file's imports and add `from pathlib import Path` if not.)

- [ ] **Step 5: Gates**

```bash
just build && just test && just quality
just gpu-cache-setup --dry-run
```
On the 4090 box: `just ml-venv` ends with `cuda_available=True` and `venv ready: … — call bin/python-cuda`; then dispatch `marola-sea-publish` with preset `tiny` and confirm it passes the "python env with CUDA torch" and version steps.

- [ ] **Step 6: Commit and PR**

```bash
git add flake.nix flake.lock justfile .github/workflows/marola-sea-publish.yml finetune/merge_export.py
git commit -F - <<'EOF'
nix: CUDA host setup from labs/cuda (h0ffmann/nix-config)

setup-cuda-cache and setup-ml-venv come from the lab on x86_64-linux;
just ml-venv / gpu-cache-setup are wrappers, marola-sea-publish.yml calls
bin/python-cuda. The gguf/sentencepiece/protobuf assertion moves to
finetune/merge_export.py --self-test, the consumer that needs them.

Closes #358

Tested: just build && just test && just quality; just gpu-cache-setup --dry-run; just ml-venv on the 4090 box (cuda_available=True); marola-sea-publish preset=tiny dispatch
Co-Authored-By: Claude <noreply@anthropic.com>
EOF
just pr
```

---

## Self-review against the spec

- §1 table: lint → Task 1/2, agentic → Task 3/4, cuda → Task 5/6; the "stays" list is `projectTools` in Task 2. ✔
- §2 `follows`, composed lists, scripts as `writeShellApplication`, self-tests as checks and dropped from `quality-other`. ✔ (Tasks 1, 3, 5 flakes; Tasks 2, 4, 6 justfile edits.)
- §3.2 fifo sentence corrected in Task 3 Step 9; env names `JAIL_CLIPBOARD*`, `--exec`, rw-maps unchanged. ✔
- §3.3 marker, `--verify` default, `REQUIREMENTS` required, `python-cuda`, self-test loses the three deps. ✔
- §4 per-PR file lists match the Files blocks; ruff comment in `ci.yml` (Task 2 Step 4). ✔
- §5 order: each lab before its consumer; verification commands present in each task's gate step. ✔
- §6 licence note in the nix-config README: Task 1 Step 6 adds it under `## License`. ✔
- Names: `gh-token`, `clip`, `clip-relay`, `jail-run`, `setup-cuda-cache`, `setup-ml-venv`, `python-cuda`, `devShells.lint`, `lib.<system>.{tools,env,scripts}` used consistently across tasks. ✔
