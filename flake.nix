{
  description = "marola dev shell — Scala 3.9 / Kyo / Azure tooling, plus Python for the offline DSPy compile step; works on plain Ubuntu (not NixOS-specific)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    # h0ffmann/nix-config labs, one nixpkgs closure via `follows`; bump with `nix flake update lint`.
    lint = {
      url = "github:h0ffmann/nix-config?dir=labs/lint";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    agentic = {
      url = "github:h0ffmann/nix-config/labs/agentic?dir=labs/agentic";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    cuda = {
      url = "github:h0ffmann/nix-config/labs/cuda?dir=labs/cuda";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, flake-utils, lint, agentic, cuda }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        jdk = pkgs.jdk25;
        # nixpkgs' `sbt` is a wrapper that hardcodes its own JAVA_HOME at build time; jdk25 on
        # PATH does not change it. `.override { jre = ... }` is the nixpkgs pattern for JVM-tool
        # wrappers. If the argument name changes, `cat $(readlink -f $(command -v sbt))` shows
        # what the wrapper actually hardcodes.
        sbtOnJdk25 = pkgs.sbt.override { jre = jdk; };

        # marola's own tools. Lint, the agent sandbox and the CUDA host scripts come from
        # labs/lint, labs/agentic and labs/cuda (x86_64-linux only), appended below.
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

          # `just context-mips`: repomix packs docs for a browser session, wl-copy/xclip copy them.
          pkgs.repomix
          pkgs.wl-clipboard
          pkgs.xclip
          # `just claude-cost`: npx runs ccusage.
          pkgs.nodejs

          pkgs.jq
          pkgs.git
          # `just oods-sql`: the human's half of MIP-0056's store. The build step uses
          # org.duckdb:duckdb_jdbc instead (build.sbt) and the two versions need not match —
          # only Parquet files cross the boundary, never a .duckdb file.
          pkgs.duckdb

          # The self-hosted Actions runner for marola-sea-publish.yml (`runs-on: [self-hosted,
          # marola-sea]`): gigabytes of weights, a training run and a Hugging Face token do not
          # belong on shared infrastructure. Register from ~/.marola-runner with config.sh
          # (--labels marola-sea,dependabot); `just ghar` / `just gha` / `just ghas` drive it.
          # `dependabot` is the label GitHub's own Dependabot needs once "Dependabot on
          # self-hosted runners" is enabled; its updater runs in containers, so the runner user
          # needs a reachable Docker.
          pkgs.github-runner

          # waydroid CLI only: the LXC container, binder modules and the waydroid-container
          # service are the host's, same shape as ollama above.
          pkgs.waydroid
        ];
      in
      {
        devShells.default = pkgs.mkShell {
          name = "marola";
          packages = projectTools ++ lint.lib.${system}.tools ++ agentic.lib.${system}.tools
            ++ pkgs.lib.optionals (system == "x86_64-linux") cuda.lib.${system}.tools;

          JAVA_HOME = "${jdk}";
          inherit (agentic.lib.${system}.env) BWRAP_BIN;

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
