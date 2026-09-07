#!/usr/bin/env bash
# mip_ref — shared MIP-number detection for scripts/uprd.sh and .github/workflows/pr-body.yml's
# title step. Sourced, not run directly (no execute bit) — the shebang is only so shellcheck
# knows the dialect. Source it, then: mip_ref="$(detect_mip_ref "$branch" "$range")"
#
# Three tiers, same order everywhere a MIP gets auto-detected in this repo (scripts/uprd.sh's own
# `generate_mip` table cell used to duplicate this inline — now delegates here):
#   1. a `mip-NNNN` token anywhere in the branch name (`mip-0010/3-…`, `docs/mip-0014-…`)
#   2. a commit subject starting with `MIP-NNNN` (subjects only, not bodies — a body can mention
#      another MIP in passing without this commit being scoped to it)
#   3. the single `docs/mips/MIP-NNNN-*.md` file touched on the branch
# Echoes "MIP-NNNN" (always uppercase) or nothing if none of the three matched.
detect_mip_ref() {
  local branch="$1" range="$2" mip_ref=""
  if [[ "$branch" =~ [Mm][Ii][Pp]-([0-9]{4}) ]]; then
    mip_ref="MIP-${BASH_REMATCH[1]}"
  else
    mip_ref="$(git log --format='%s' "$range" 2>/dev/null \
      | { grep -ioE '^MIP-[0-9]{4}' || true; } | head -1 | tr '[:lower:]' '[:upper:]')"
  fi
  if [ -z "$mip_ref" ]; then
    mip_ref="$(git diff --name-only "$range" -- docs/mips 2>/dev/null \
      | { grep -oE 'MIP-[0-9]{4}' || true; } | sort -u | { [ "$(wc -l)" -eq 1 ] && cat || true; })"
  fi
  printf '%s' "$mip_ref"
}
