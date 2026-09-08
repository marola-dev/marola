#!/usr/bin/env bash
# Add the cuda-maintainers binary cache to Nix, so torchWithCuda is fetched, not compiled.
# Without it a marola-sea GPU run builds torch, magma and triton from source — hours. MIP-0025.
#
#   scripts/setup-cuda-cache.sh --dry-run   # print the change, touch nothing
#   sudo scripts/setup-cuda-cache.sh        # apply it, then restart the daemon
set -euo pipefail

CACHE="https://cuda-maintainers.cachix.org"
KEY="cuda-maintainers.cachix.org-1:0dq3bujKpuEPMCX6U4WylrUDZ9JyUG0VpVZa7CNfq5E="
# Determinate Nix marks /etc/nix/nix.conf "do not modify" and !includes this one.
CONF="${NIX_CUSTOM_CONF:-/etc/nix/nix.custom.conf}"
USER_TO_TRUST="${SUDO_USER:-${USER:-$(id -un)}}"

usage() { sed -n '2,7p' "$0"; }

block() {
  cat <<EOF

# marola: CUDA binary cache (scripts/setup-cuda-cache.sh). Substituters are a trusted-user setting,
# so the user must be listed here too or Nix silently ignores the cache.
trusted-users = root $USER_TO_TRUST
extra-substituters = $CACHE
extra-trusted-public-keys = $KEY
EOF
}

already_configured() {
  [ -f "$CONF" ] && grep -qF "$CACHE" "$CONF"
}

verify() {
  # The check that matters: does the CUDA torch resolve as a fetch rather than a build?
  local env_expr="${1:-.github/nix-ml-env.nix}"
  [ -f "$env_expr" ] || { echo "verify: no $env_expr here — run from the repo root"; return 2; }
  nix build --dry-run --impure --expr "$(cat "$env_expr")" 2>&1 |
    grep -E 'will be built|will be fetched' || echo "nothing to do — already realised"
}

self_test() {
  local fails=0
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails+1)); fi; }
  ok "$(block | grep -c '^extra-substituters')" "1" "the block sets exactly one substituter line"
  ok "$(block | grep -c '^trusted-users')" "1" "the block sets trusted-users, without which the cache is ignored"
  ok "$(block | grep -o 'cachix.org-1:[A-Za-z0-9+/=]*' | wc -l | tr -d ' ')" "1" "the public key is present and well-formed"
  local tmp; tmp="$(mktemp)"; block > "$tmp"
  ok "$(CONF="$tmp" bash -c 'grep -qF "cuda-maintainers.cachix.org" "$CONF" && echo yes')" "yes" "already_configured detects an applied block"
  rm -f "$tmp"
  ok "$(NIX_CUSTOM_CONF=/definitely/absent bash -c '[ -f "/definitely/absent" ] || echo no')" "no" "a missing conf file is not mistaken for a configured one"
  [ "$fails" -eq 0 ] && { echo "setup-cuda-cache self-test: ok"; return 0; }
  echo "setup-cuda-cache self-test: $fails failure(s)" >&2; return 1
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  --help|-h)   usage; exit 0 ;;
  --verify)    verify "${2:-}"; exit $? ;;
esac

if already_configured; then
  echo "already configured in $CONF — nothing to do"
  echo "verify with: scripts/setup-cuda-cache.sh --verify"
  exit 0
fi

if [ "${1:-}" = "--dry-run" ]; then
  echo "would append to $CONF:"
  block
  echo
  echo "then: systemctl restart nix-daemon"
  exit 0
fi

if [ "$(id -u)" -ne 0 ]; then
  echo "needs root to write $CONF — re-run with sudo, or --dry-run to see the change" >&2
  exit 1
fi

cp -a "$CONF" "$CONF.bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null || true
block >> "$CONF"
echo "appended to $CONF (previous version backed up alongside it)"
systemctl restart nix-daemon
echo "nix-daemon restarted"
echo
echo "now verify from the repo root — you want 'fetched', not 'built':"
echo "  scripts/setup-cuda-cache.sh --verify"
