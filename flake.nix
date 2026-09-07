{
  description = "marola dev shell — Scala 3.9 / Kyo / Azure tooling, plus Python for the offline DSPy compile step; works on plain Ubuntu (not NixOS-specific)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    ai-jail = {
      url = "github:akitaonrails/ai-jail";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, ai-jail }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        jdk = pkgs.jdk25;
        # nixpkgs' `sbt` package is a wrapper script that hardcodes its own
        # JAVA_HOME at build time, pointing at whatever JRE nixpkgs built it
        # against (currently older than 25) — just having jdk25 on PATH
        # alongside it does NOT change what sbt's wrapper uses internally.
        # `.override { jre = ... }` is the standard nixpkgs pattern for
        # controlling this on JVM-tool derivations (sbt, gradle, maven,
        # ant, ...). If this override argument name has changed, run
        # `cat $(readlink -f $(command -v sbt))` after `nix develop` to see
        # what JAVA_HOME the wrapper actually hardcodes, and adjust.
        sbtOnJdk25 = pkgs.sbt.override { jre = jdk; };
      in
      {
        devShells.default = pkgs.mkShell {
          name = "marola";

          buildInputs = [
            jdk
            sbtOnJdk25
            pkgs.scala-cli
            pkgs.coursier

            # Task runner
            pkgs.just

            # marola's offline DSPy compile step (Python-only — DSPy has
            # no JVM port, see docs/ARCHITECTURE.md and
            # dspy/README.md). `python3 -m venv` + pip installs
            # DSPy itself; not vendored as a nixpkgs package here since it
            # moves fast and pins its own dependency versions.
            #
            # scikit-learn is bundled into THIS python3 via withPackages, not as a separate
            # pkgs.python3Packages.scikit-learn alongside a bare pkgs.python3 — a package listed
            # that way sits in its own nix store path and is never on plain `python3`'s import
            # path (the exact gotcha documented below for coverage.py, which sidesteps it by only
            # ever using coverage's own executable, never `import coverage`). scripts/pr_label_nlp.py
            # does `from sklearn... import ...` directly, so it needs the interpreter itself wired
            # up. Confirmed present in nixpkgs (`scikit-learn 1.8.0`, checked 2026-09-07 via
            # `nix eval nixpkgs#python3Packages.scikit-learn.version`).
            (pkgs.python3.withPackages (ps: with ps; [ pip scikit-learn ]))

            # coverage.py — the README's `python coverage` badge, measured (never estimated) by
            # running every `scripts/**/*.py --self-test` under it: `scripts/repo_stats.py
            # python-coverage`. Confirmed present in nixpkgs (`coverage 7.15.4`, built for this
            # shell's python3 3.14.7, checked 2026-09-07 via
            # `nix build nixpkgs#python3Packages.coverage`). It puts a `coverage` executable on
            # PATH but does not put the module on plain python3's import path — which is why
            # repo_stats.coverage_exe() prefers the executable and falls back to `python3 -m
            # coverage` for CI's apt `python3-coverage`, where it is the other way around.
            pkgs.python3Packages.coverage

            # uv (astral-sh) — a fast Python package/tool manager. Confirmed present in nixpkgs
            # (`uv 0.12.5`, checked 2026-09-07 via `nix run nixpkgs#uv -- --version`). Its `uvx`
            # subcommand runs a Python CLI tool ephemerally (no persistent install, nothing to
            # manage) — that's what `just specify` below uses to run GitHub's spec-kit without
            # vendoring it (spec-kit isn't a nixpkgs package: it ships only via `uv tool install`/
            # PyPI, confirmed against its own README, 2026-09-07).
            pkgs.uv

            # marola's default local LLM/vision backend (LocalLlmClient,
            # LocalVisionClient, and the DSPy compile step's default
            # MAROLA_DSPY_MODEL) — this is what lets marola run with zero
            # Azure account. Confirmed present in nixpkgs (`ollama-0.32.14`
            # at the time this was added). `ollama serve` still needs to be
            # started separately (see the shellHook note and
            # docs/RUN-LOCALLY.md) — this only puts the binary on PATH.
            pkgs.ollama

            # Azure CLI (well-established nixpkgs package)
            pkgs.azure-cli

            # GitHub
            pkgs.gh

            # `just context-mips`: packs the docs a browser Claude session needs into one
            # markdown file (repomix) and copies it to the clipboard (wl-copy on Wayland, xclip
            # on X11) — see repomix.config.json / repomix-instruction.md.
            pkgs.repomix
            pkgs.wl-clipboard
            pkgs.xclip

            # `just claude-cost`: npx runs ccusage (free, reads ~/.claude session logs) to show
            # what a Claude Code session/feature consumed — see AGENTS.md "Attribution and cost".
            pkgs.nodejs

            # General
            pkgs.jq
            pkgs.git

            # Line counter behind the README's Scala/Python LOC badges (scripts/repo_stats.py;
            # ci.yml's repo-stats job apt-installs it on the runner). Confirmed present in
            # nixpkgs (`cloc 2.10`, checked 2026-09-07 via `nix run nixpkgs#cloc -- --version`).
            pkgs.cloc

            # Dockerfile lint (`just quality`, docker.yml) — MIP-0008. Docker itself is not in the
            # flake: it needs a daemon the host runs.
            pkgs.hadolint

            # GitHub Actions workflow lint (`just quality`, .githooks/pre-commit) — was missing
            # here, so `command -v actionlint` silently skipped it locally and a bad workflow only
            # surfaced once CI ran it.
            pkgs.actionlint

            # actionlint shells out to shellcheck to lint the `run:` scripts inside workflow
            # steps — without it, actionlint still exits 0 locally and a shellcheck-only finding
            # (e.g. SC2015) only surfaces once CI runs it. Same failure mode as actionlint above.
            pkgs.shellcheck

            # Python lint/format (`just quality-other`, ci.yml's quality-other job,
            # .githooks/pre-push). Was missing here too, so `just quality` skipped ruff with a
            # one-line notice and an unused import in scripts/cost-split.py reached main — CI
            # lints every .py in the repo, and the local run had been linting a hand-kept list.
            pkgs.ruff

            # ai-jail — sandboxes AI coding agents (Claude Code, ...)
            # behind bubblewrap/Landlock/seccomp on Linux. Not a substitute
            # for the AGENTS.md cost/deploy rules, but a real containment
            # layer for whatever an agent runs locally. See `just jail-*`
            # and `.ai-jail` (project-level policy, committed) below.
            ai-jail.packages.${system}.default
            pkgs.bubblewrap

            # OpenCode (MIP-0013): a second coding-agent harness this repo is tried against,
            # additive only — nothing under .claude/ is removed. Confirmed present in nixpkgs
            # (`opencode` 1.18.21 on nixos-unstable, checked 2026-09-07 via `nix search`).
            # `opencode.json` (repo root) is its config; `just jail-opencode`/`just jo` runs it
            # under ai-jail the same way `just jail-claude` does for Claude Code.
            pkgs.opencode
          ];

          # Backup for anything else (coursier, plain `java`, scala-cli) that
          # respects JAVA_HOME directly rather than going through sbt's wrapper.
          JAVA_HOME = "${jdk}";

          # ai-jail's own flake sets this in its own devShell; it doesn't
          # propagate automatically when consumed as a package input like
          # here, so set it explicitly rather than rely on ai-jail finding
          # bwrap on PATH.
          BWRAP_BIN = "${pkgs.bubblewrap}/bin/bwrap";

          # NOTE: `azd` (Azure Developer CLI) is deliberately not listed
          # above — its nixpkgs packaging status changes; if `nix develop`
          # fails to find it and you're deploying one of marola's optional
          # Azure integrations, install it directly:
          #   curl -fsSL https://aka.ms/install-azd.sh | bash

          shellHook = ''
            echo "marola dev shell"
            git config core.hooksPath .githooks 2>/dev/null || true
            # Load the repo's gitignored .env (MAROLA_ORIGIN_LAT/LON, provider switches — see
            # .env.example) into this shell, so plain `nix develop` matches what direnv's
            # `dotenv_if_exists` in .envrc already does. `set -a` exports every assignment; the
            # file must be plain KEY=VALUE lines (shell syntax, no spaces around `=`).
            marola_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            if [ -f "$marola_root/.env" ]; then
              set -a; . "$marola_root/.env"; set +a
              echo "loaded $marola_root/.env"
            fi
            java -version
            curl -s -m 1 http://localhost:11434/api/tags >/dev/null 2>&1 \
              || echo "ollama not running — start it with 'ollama serve' (see docs/RUN-LOCALLY.md)"
            # `just sync-main` fast-forwards local `main` from origin, but only when you're
            # actually on `main` with a clean tree (git merge --ff-only) — a no-op otherwise, so
            # this is always safe to run on every shell entry. `timeout` keeps a slow/offline
            # network from delaying the prompt; failure here must never block entering the shell.
            (cd "$marola_root" && timeout 10s just sync-main) || true
            echo "Run 'just' to see available commands."
          '';
        };
      });
}