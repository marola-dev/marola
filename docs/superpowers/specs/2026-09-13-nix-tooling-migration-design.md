# Nix tooling out of marola, into nix-config labs

Design, 2026-09-13. Approved in session; issues: #356 (lint), #357 (agentic),
#358 (cuda).

## 1. What moves, what stays

marola's `flake.nix` is one 242-line devShell that mixes four responsibilities. Three of them are
not marola's and move to [h0ffmann/nix-config](https://github.com/h0ffmann/nix-config) as labs:

| Responsibility | Today in marola | Becomes |
|---|---|---|
| Lint toolchain | hadolint, actionlint, shellcheck, ruff, pyflakes, cloc, coverage, pdoc in `flake.nix`; `ci.yml` re-resolves four via `nix build nixpkgs#…` | `labs/lint` |
| Agent sandbox | ai-jail (+`doCheck = false`), bubblewrap, opencode, gh; `jail-*`/`gh-auth`/`clip` recipes; `scripts/gh-token.sh`, `clip.sh`, `clip-relay.sh` | `labs/agentic` |
| CUDA host setup | `scripts/setup-cuda-cache.sh`, `scripts/setup-ml-venv.sh` | `labs/cuda` |

Stays in marola: JDK 25, sbt on it, scala-cli, coursier, just, python with scikit-learn, uv,
repomix and the clipboard tools it needs, nodejs (ccusage), jq, git, ollama, waydroid,
the github-runner package and its scripts (`gha-runner.sh`, `runner-preflight.sh`,
`setup-runners.sh`), the shellHook, `.envrc`, the Docker `dev` stage.

## 2. Consumption: flake inputs, `follows`, composed lists

marola consumes each lab as a flake input, never as a submodule:

```nix
inputs = {
  nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  lint    = { url = "github:h0ffmann/nix-config?dir=labs/lint";    inputs.nixpkgs.follows = "nixpkgs"; };
  agentic = { url = "github:h0ffmann/nix-config?dir=labs/agentic"; inputs.nixpkgs.follows = "nixpkgs"; };
  cuda    = { url = "github:h0ffmann/nix-config?dir=labs/cuda";    inputs.nixpkgs.follows = "nixpkgs"; };
};
```

`follows` keeps one nixpkgs closure in the dev shell. The price: a lab's CI evaluates it on the
lab's own lock, marola on marola's; the two can differ by a nixpkgs revision. The lab READMEs say
so. A version bump is two steps: merge in nix-config, `nix flake update <lab>` in marola.

Each lab follows the shape `labs/publisher` already has, so the consumer composes lists rather
than importing a shell:

```nix
# lab side (labs/<lab>/flake.nix)
forAll   = f: lib.genAttrs systems (s: f nixpkgs.legacyPackages.${s});
toolsFor = pkgs: [ ... ];                       # the only place packages are named
lib      = forAll (pkgs: { tools = toolsFor pkgs; env = envFor pkgs; });
packages = forAll (pkgs: { <each script> = ...; });
devShells = forAll (pkgs: { default = pkgs.mkShell { packages = toolsFor pkgs; }; });
checks   = forAll (pkgs: { ... });

# consumer side (marola flake.nix)
packages = projectTools pkgs
        ++ lint.lib.${system}.tools
        ++ agentic.lib.${system}.tools
        ++ lib.optionals (system == "x86_64-linux") cuda.lib.${system}.tools;
```

Scripts become `pkgs.writeShellApplication` packages so they land on `PATH` with `runtimeInputs`
pinned, and a consumer's justfile recipe is one line calling the binary. Self-tests stay inside
each script (`--self-test`) and run twice: as the lab's `checks.<system>.<name>` in nix-config CI,
and never again in marola, whose `quality-other` list drops them.

## 3. The three labs

### 3.1 `labs/lint`

Packages only. `toolsFor` = hadolint, actionlint, shellcheck, ruff, pyflakes, cloc, coverage,
pdoc. `packages.<system>.<tool>` re-exports each. `checks.<system>.versions` is a derivation that
runs every tool's `--version` into `$out/versions.txt`, so `nix flake check` proves they build on
every declared system. `just shell`, `just versions`. No scripts.

### 3.2 `labs/agentic`

`toolsFor` = ai-jail with `overrideAttrs (_: { doCheck = false; })` (its test suite needs a
sandbox the Nix build sandbox does not give), bubblewrap (Linux only), opencode, gh, and four
script packages:

- `gh-token`: `scripts/gh-token.sh` as is (`--source`, `--self-test`).
- `clip`, `clip-relay`: the write-only clipboard bridge, as is. The fifo stays at
  `<project>/.tmp/clip.fifo`: the project directory is what ai-jail maps into the sandbox, so a
  path under `$XDG_RUNTIME_DIR` would be invisible from inside.
- `jail-run`: the `jail-claude` / `jail-opencode` / `jail-dry-run` recipe bodies as one script:
  `jail-run claude [args]`, `jail-run opencode [args]`, `jail-run --dry-run -- <cmd>`. Runs in
  the current directory, which must hold the project's `.ai-jail`. Opt-ins `JAIL_CLIPBOARD=1`
  and `JAIL_CLIPBOARD_PASTE=1` (the `MAROLA_`/`PRATICO_` prefixes go). Resolves `GH_TOKEN` on the
  host through `gh-token` and forwards it with `--env GH_TOKEN`, exactly as both copies do today.
  `--exec`, `--no-save-config`, the rw-maps and the display flags are unchanged.

`envFor` = `{ BWRAP_BIN = "${pkgs.bubblewrap}/bin/bwrap"; }`. `checks` = gh-token self-test,
`jail-run --dry-run -- true` (prints the bwrap line, runs nothing), shellcheck over the scripts.
`justfile`: `jail-claude`, `jcf`, `jcs`, `jco`, `jail-opencode`, `jo`, `jail-dry-run`,
`gh-auth`, `clip`. An `.ai-jail` example in the README. `labs/pratico` keeps its copies for now;
making it consume `labs/agentic` is a follow-up in nix-config.

### 3.3 `labs/cuda`

x86_64-linux only (`systems = [ "x86_64-linux" ]`). Two script packages:

- `setup-cuda-cache`: as today, with the conf marker `# nix-config/labs/cuda: CUDA binary
  cache`, still removing the stale Cachix block, and `--verify [expr-file]` defaulting to a
  `nix build --dry-run nixpkgs#python3Packages.torchWithCuda` instead of a file that no longer
  exists.
- `setup-ml-venv`: takes `REQUIREMENTS=<file>` (required; the script refuses without it),
  `VENV_ROOT` (default `~/.ml-venv`), `CUDA_INDEX`; writes `$VENV_ROOT/bin/python-cuda`. The
  libstdc++ and driver-library logic is unchanged. The self-test keeps the generic assertions and
  loses the three that check marola's requirements file.

`checks` = both self-tests. `just cache-setup`, `just venv`, `just shell`.

## 4. The marola side, per PR

Each marola PR lands after its lab has merged in nix-config, so `flake.lock` pins a real rev.

**PR lint.** `flake.nix`: input, `devShells.default` composes `lint.lib.${system}.tools`, new
`devShells.lint` with only those. Remove the eight packages and their comments. `ci.yml`: the
"cloc + coverage.py from nix" and "shellcheck + pyflakes from nix" steps become
`nix develop .#lint --command bash -c '... >> $GITHUB_PATH'`; the ruff version comment names
`labs/lint`. `PHILOSOPHY.md` "Why Nix", `docs/1-Using-marola/RUN-LOCALLY.md` §10 line about hadolint.

**PR agentic.** `flake.nix`: input, drop the `ai-jail` input and the four packages, compose
`tools` and `env`. `justfile`: `jail-dry-run` → `jail-run --dry-run -- {{cmd}}`; `jail-claude` →
`JAIL_CLIPBOARD=${MAROLA_JAIL_CLIPBOARD:-0} JAIL_CLIPBOARD_PASTE=${MAROLA_JAIL_CLIPBOARD_PASTE:-0} jail-run claude {{args}}`
(one release of compatibility for the old names); `jail-opencode` → `jail-run opencode`;
`gh-auth` → `gh-token --source`; `clip` → `clip`. Delete `scripts/gh-token.sh`, `clip.sh`,
`clip-relay.sh`; `quality-other` drops the gh-token self-test; `scripts/runner-preflight.sh`
calls `gh-token` from PATH. Docs: `AGENTS.md` jail paragraph (paths and env names),
`docs/3-Working-on-the-repo/DEV-FLOW.md` clipboard rows, `.claude/hooks/session-start.sh` jail caveat text.

**PR cuda.** `flake.nix`: input, composed on x86_64-linux only. `justfile`: `ml-venv` →
`REQUIREMENTS=finetune/requirements.txt VENV_ROOT=~/.marola-ml-venv setup-ml-venv {{args}}`;
`gpu-cache-setup` → `setup-cuda-cache {{args}}`. `marola-sea-publish.yml`: the setup step and
every `bin/marola-python` → `bin/python-cuda`. Delete both scripts; `quality-other` drops both
self-tests; `finetune/merge_export.py --self-test` gains the gguf / sentencepiece / protobuf
assertion against `finetune/requirements.txt`. `docs/1-Using-marola/RUN-LOCALLY.md` runner setup lines.

## 5. Order and verification

1. nix-config `labs/lint` → `nix flake check`, `just versions`, CI matrix green.
2. nix-config `labs/agentic` → `nix flake check`, `jail-run --dry-run -- true` on the
   workstation, `gh-token --self-test`, `just jco` from a scratch directory with an `.ai-jail`.
3. nix-config `labs/cuda` → `nix flake check`; on the 4090 box `setup-cuda-cache --dry-run`,
   `REQUIREMENTS=... setup-ml-venv` reporting `cuda_available=True`.
4. marola PR lint → `just build && just test && just quality`, fresh `nix develop`,
   `nix develop .#lint --command ruff --version`, CI on the self-hosted runner.
5. marola PR agentic → the same, plus `just jail-dry-run true` byte-equal to before and one
   `just jco` session that can `gh pr list`.
6. marola PR cuda → the same, plus one `just ml-venv` and one `marola-sea-publish` dispatch with
   preset `tiny`.

Each marola PR carries `Tested:` and `Cost:` trailers (`AGENTS.md`); each is independent of the
other two and can merge in any order once its lab exists.

## 6. Risks and known trade-offs

- **nix-config has no licence file.** marola pulls "all rights reserved" code by flake input.
  Fine for one owner; the nix-config README should say it before anyone else consumes a lab.
- **nixpkgs skew** between a lab's lock and marola's, from `follows`. The lab's `versions`
  check and marola's `just quality` will disagree on a patch version now and then; that is the
  agreed price of one closure.
- **The Docker `dev` stage** copies `flake.nix` and `flake.lock` and runs `nix develop`; it now
  fetches three more GitHub inputs at build time. Same network need it already has for nixpkgs.
- **`jail-run` changes the env-var names.** Anyone with
  `MAROLA_JAIL_CLIPBOARD=1` in a shell profile gets the compatibility mapping for one release,
  then the old name stops working.
- **ai-jail inside a jail.** These PRs are authored inside `just jail-claude`, where `.ai-jail`
  reads empty and nix may be restricted; `nix flake check` on the labs runs on the host if the
  jail refuses it. Said in each PR's `Tested:` line either way.
- **CI cannot test the CUDA lab for real.** nix-config CI is GitHub-hosted; the lab's checks are
  the scripts' self-tests only. The real run is a manual step on the workstation, recorded in the
  PR.
