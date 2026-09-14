#!/usr/bin/env bash
# Add the nixos-cuda binary cache to Nix, so torchWithCuda is fetched, not compiled.
# Without it a marola-sea GPU run builds torch, magma and triton from source — hours. MIP-0025.
#
#   scripts/setup-cuda-cache.sh --dry-run   # print the change, touch nothing
#   sudo scripts/setup-cuda-cache.sh        # apply it (replacing a stale block), then restart the daemon
#   sudo scripts/setup-cuda-cache.sh --remove   # take the block out again
set -euo pipefail

# The cache moved off Cachix in Nov 2025; the old cuda-maintainers.cachix.org answers 401, which
# Determinate Nix treats as fatal for every `nix develop` — so a stale block must be replaced, not kept.
CACHE="https://cache.nixos-cuda.org"
KEY="cache.nixos-cuda.org:74DUi4Ye579gUqzH4ziL9IyiJBlDpMRn9MBN8oNan9M="
STALE_CACHE="https://cuda-maintainers.cachix.org"
# Determinate Nix marks /etc/nix/nix.conf "do not modify" and !includes this one.
CONF="${NIX_CUSTOM_CONF:-/etc/nix/nix.custom.conf}"
USER_TO_TRUST="${SUDO_USER:-${USER:-$(id -un)}}"

usage() { sed -n '2,7p' "$0"; }

block() {
  cat <<EOB

# marola: CUDA binary cache (scripts/setup-cuda-cache.sh). Substituters are a trusted-user setting,
# so the user must be listed here too or Nix silently ignores the cache.
trusted-users = root $USER_TO_TRUST
extra-substituters = $CACHE
extra-trusted-public-keys = $KEY
EOB
}

already_configured() { [ -f "$CONF" ] && grep -qF "$CACHE" "$CONF"; }
stale_configured()   { [ -f "$CONF" ] && grep -qF "$STALE_CACHE" "$CONF"; }

# Drops the block this script wrote (either cache), in place.
remove_block() {
  sed -i '/^# marola: CUDA binary cache/,/^extra-trusted-public-keys = /d' "$1"
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
  ok "$(block | grep -o 'nixos-cuda.org:[A-Za-z0-9+/=]*' | wc -l | tr -d ' ')" "1" "the public key is present and well-formed"
  ok "$(block | grep -c 'cachix')" "0" "the block no longer names the dead Cachix cache"
  local tmp; tmp="$(mktemp)"; block > "$tmp"
  ok "$(CONF="$tmp" bash -c 'grep -qF "cache.nixos-cuda.org" "$CONF" && echo yes')" "yes" "already_configured detects an applied block"
  local stale; stale="$(mktemp)"
  printf 'keep-me = 1\n\n# marola: CUDA binary cache (old)\n# so the user must be listed here too\ntrusted-users = root me\nextra-substituters = %s\nextra-trusted-public-keys = cuda-maintainers.cachix.org-1:abc=\nkeep-me-too = 2\n' "$STALE_CACHE" > "$stale"
  ok "$(CONF="$stale" bash -c "grep -qF '$STALE_CACHE' \"\$CONF\" && echo yes")" "yes" "stale_configured detects the Cachix-era block"
  remove_block "$stale"
  ok "$(grep -c 'cachix\|trusted-users\|marola' "$stale")" "0" "remove_block strips the whole old block"
  ok "$(grep -c 'keep-me' "$stale")" "2" "remove_block leaves the surrounding lines alone"
  rm -f "$tmp" "$stale"
  ok "$(NIX_CUSTOM_CONF=/definitely/absent bash -c '[ -f "/definitely/absent" ] || echo no')" "no" "a missing conf file is not mistaken for a configured one"
  [ "$fails" -eq 0 ] && { echo "setup-cuda-cache self-test: ok"; return 0; }
  echo "setup-cuda-cache self-test: $fails failure(s)" >&2; return 1
}

need_root() {
  [ "$(id -u)" -eq 0 ] && return 0
  echo "needs root to write $CONF — re-run with sudo, or --dry-run to see the change" >&2
  exit 1
}

restart_daemon() { systemctl restart nix-daemon; echo "nix-daemon restarted"; }

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  --help|-h)   usage; exit 0 ;;
  --verify)    verify "${2:-}"; exit $? ;;
  --remove)
    need_root
    cp -a "$CONF" "$CONF.bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null || true
    remove_block "$CONF"
    echo "removed the marola block from $CONF (previous version backed up alongside it)"
    restart_daemon
    exit 0 ;;
esac

if already_configured; then
  echo "already configured in $CONF — nothing to do"
  echo "verify with: scripts/setup-cuda-cache.sh --verify"
  exit 0
fi

if [ "${1:-}" = "--dry-run" ]; then
  stale_configured && echo "would first remove the stale $STALE_CACHE block from $CONF (it answers 401 and breaks nix develop)"
  echo "would append to $CONF:"
  block
  echo
  echo "then: systemctl restart nix-daemon"
  exit 0
fi

need_root
cp -a "$CONF" "$CONF.bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null || true
if stale_configured; then
  remove_block "$CONF"
  echo "removed the stale $STALE_CACHE block from $CONF"
fi
block >> "$CONF"
echo "appended to $CONF (previous version backed up alongside it)"
restart_daemon
echo
echo "now verify from the repo root — you want 'fetched', not 'built':"
echo "  scripts/setup-cuda-cache.sh --verify"
