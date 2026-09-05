#!/usr/bin/env bash
# branches — local branch hygiene against GitHub PR state, for plain (non-stacked) branches.
#
#   scripts/branches.sh clean   # delete every local branch whose PR is MERGED (local + remote ref)
#   scripts/branches.sh open    # open a base=main PR for every local branch that's ahead of main,
#                                # has no PR yet, and isn't a mip-NNNN/k-slug stack branch
#   --dry-run on either prints what would run.
#
# Stacked MIP task branches (mip-NNNN/k-slug) are `open`'s business only via
# `scripts/stack.sh pr` / `just uprds MIP-NNNN` — those need the previous task's branch as base,
# not main. `clean` is safe for them too: it only ever acts on a branch gh confirms MERGED.
#
# Requires `gh auth status` (not available inside the ai-jail sandbox — run from the host, see
# AGENTS.md). Never touches the current branch or main.
set -euo pipefail
dry=0; args=()
for a in "$@"; do [ "$a" = "--dry-run" ] && dry=1 || args+=("$a"); done
set -- "${args[@]}"
run() { if [ "$dry" -eq 1 ]; then echo "+ $*"; else "$@"; fi; }

gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }

cur="$(git branch --show-current)"
run git fetch -q --prune origin

other_branches() {
  git branch --format='%(refname:short)' | grep -v -E '^(main)$' | grep -vxF "$cur"
}
is_stack_branch() { [[ "$1" =~ ^mip-[0-9]{4}/[0-9]+- ]]; }

case "${1:-}" in
  clean)
    for b in $(other_branches); do
      state="$(gh pr view "$b" --json state -q .state 2>/dev/null || echo NONE)"
      if [ "$state" = MERGED ]; then
        echo "deleting $b (PR merged)"
        run git branch -D "$b"
        if git rev-parse --verify -q "origin/$b" >/dev/null 2>&1; then
          run git push -q origin --delete "$b" || echo "  (already gone on origin)"
        fi
      else
        echo "keep $b (PR: $state)"
      fi
    done
    ;;
  open)
    for b in $(other_branches); do
      if is_stack_branch "$b"; then
        echo "skip $b (mip stack branch — use 'scripts/stack.sh pr' or 'just uprds ${b%%/*}')"
        continue
      fi
      ahead="$(git rev-list --count origin/main.."$b" 2>/dev/null || echo 0)"
      if [ "$ahead" -eq 0 ]; then
        echo "skip $b (no commits ahead of origin/main)"
        continue
      fi
      state="$(gh pr view "$b" --json state -q .state 2>/dev/null || echo NONE)"
      if [ "$state" != NONE ]; then
        echo "skip $b (PR: $state)"
        continue
      fi
      echo "opening PR for $b (base main)"
      run git push -q -u origin "$b"
      title="$(git log --reverse --format=%s "origin/main..$b" | head -1)"
      run gh pr create --base main --head "$b" --title "$title" --fill
    done
    ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
