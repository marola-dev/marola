{
  description = "marola umbrella dev shell — docs, MIPs and the dev-flow tools (the code repos are submodules with their own shells); works on plain Ubuntu (not NixOS-specific)";

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
    # The shared dev-flow harness (MIP-0070 §5.1): stack, uprd, issues, cost-split, … on PATH, the
    # git hooks and the just module under .devkit. Bumped by hand, together with every `@v…` and
    # `devkit-ref:` in .github/workflows/ (dependabot ignores marola-devkit for that reason).
    marola-devkit = {
      url = "github:marola-dev/marola-devkit/v0.2.3";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.lint.follows = "lint";
      inputs.agentic.follows = "agentic";
    };
  };

  outputs = { self, nixpkgs, flake-utils, lint, agentic, marola-devkit }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        devkit = marola-devkit.lib.${system};

        # marola's own tools. Lint and the agent sandbox come from labs/lint and labs/agentic,
        # appended below.
        projectTools = [
          pkgs.just

          (pkgs.python3.withPackages (ps: with ps; [ pip ]))
          # `uvx` runs GitHub's spec-kit ephemerally (`just specify`); spec-kit is PyPI-only.
          pkgs.uv

          # `just context-mips`: repomix packs docs for a browser session, wl-copy/xclip copy them.
          pkgs.repomix
          pkgs.wl-clipboard
          pkgs.xclip
          # `just claude-cost`: npx runs ccusage.
          pkgs.nodejs

          pkgs.jq
          pkgs.git

          # The self-hosted Actions runner for marola-ml's marola-sea-publish.yml (`runs-on:
          # [self-hosted, marola-sea]`): gigabytes of weights, a training run and a Hugging Face
          # token do not belong on shared infrastructure. Register from ~/.marola-runner with config.sh
          # (--labels marola-sea,dependabot); `just ghar` / `just gha` / `just ghas` drive it.
          # `dependabot` is the label GitHub's own Dependabot needs once "Dependabot on
          # self-hosted runners" is enabled; its updater runs in containers, so the runner user
          # needs a reachable Docker.
          pkgs.github-runner

          # waydroid CLI only: the LXC container, binder modules and the waydroid-container
          # service are the host's.
          pkgs.waydroid
        ];
      in
      {
        devShells.default = pkgs.mkShell {
          name = "marola";
          packages = projectTools ++ lint.lib.${system}.tools ++ devkit.tools ++ agentic.lib.${system}.tools;

          inherit (agentic.lib.${system}.env) BWRAP_BIN;

          shellHook = devkit.shellHook + ''
            echo "marola dev shell"
            marola_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
            # hooks-path:start — core.hooksPath is shared by every worktree, so it is absolute and
            # points at the main checkout's link; left alone until that link exists.
            if marola_common="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null)"; then
              marola_main="$(dirname "$marola_common")"
              if [ -e "$marola_main/.devkit/.githooks/pre-push" ]; then
                git config core.hooksPath "$marola_main/.devkit/.githooks"
              else
                echo "marola: $marola_main has no .devkit yet — core.hooksPath left as is ('nix develop' there, then 'just install-hooks')"
              fi
            fi
            # hooks-path:end
            # Load the gitignored .env like direnv's `dotenv_if_exists` in .envrc does; plain
            # KEY=VALUE lines only.
            if [ -f "$marola_root/.env" ]; then
              set -a; . "$marola_root/.env"; set +a
              echo "loaded $marola_root/.env"
            fi
            # ff-only sync of a clean `main`; a no-op otherwise, `timeout` so offline never blocks.
            (cd "$marola_root" && timeout 10s just sync-main) || true
            # A `just worktree` mirror of origin/main looks identical at a prompt; say which this is.
            if [ -n "$marola_root" ] && [ -z "$(git -C "$marola_root" branch --show-current 2>/dev/null)" ]; then
              echo "marola: DETACHED mirror at $(git -C "$marola_root" rev-parse --short HEAD 2>/dev/null) — refresh it with 'just worktree' from the main checkout; commit there, not here"
            fi
            echo "Run 'just' to see available commands."
          '';
        };

        # The lint toolchain plus the devkit's tools (workflow-runners, agents-check): what ci.yml's
        # quality-other runs, from the same lock as `just quality`. labs/lint has no `just`.
        devShells.lint = pkgs.mkShell {
          name = "marola-lint";
          packages = lint.lib.${system}.tools ++ devkit.tools ++ [ pkgs.just ];
        };
      });
}
