#!/usr/bin/env bash
# The setup script proposed for the project's cloud environment ("Marola cloud"): paste it into
# Project settings > Cloud environment > Setup script. It installs the gate tools MIP-0087 §4.4
# found missing in a thread, so `just quality` runs there. `nix develop` cannot replace it: the
# session's GitHub scope refuses flake inputs outside the project's repositories (403 on flake-utils).
set -euo pipefail

# Nix comes with the image. Its nixpkgs is the registry's, not each repo's flake.lock, so a tool can
# be a release ahead of CI's.
nix profile install nixpkgs#just nixpkgs#shellcheck nixpkgs#actionlint nixpkgs#hadolint >&2
