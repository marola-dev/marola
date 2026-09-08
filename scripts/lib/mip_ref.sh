#!/usr/bin/env bash
# mip_ref — shared MIP-number detection for scripts/uprd.sh and .github/workflows/pr-body.yml's
# title step.
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
