#!/usr/bin/env bash
# task_issue — the issue line of a MIP task PR's body (#512): `Closes #N` for the issue its
# MIP-NNNN.tasks.md row links, `Part of #N` when the PR carries the `task-partial` label.
# A label, not a commit trailer, because AGENTS.md allows exactly three trailers.
task_issue_line() {   # <branch> <tasks file text> <PR labels, one per line> <owner/repo>
  local branch="$1" tasks="$2" labels="$3" repo="$4" k n
  [[ "$branch" =~ ^mip-[0-9]{4}/([0-9]+)- ]] || return 0
  k="${BASH_REMATCH[1]}"
  # Only this repo's issues: `Closes #N` is resolved against the PR's repo, whatever the row links.
  n="$(sed -nE "s#^\| \[$k\]\(https://github\.com/${repo//./\\.}/issues/([0-9]+)\) \|.*#\1#p" <<<"$tasks" | head -1)"
  [ -n "$n" ] || return 0
  if grep -qx 'task-partial' <<<"$labels"; then echo "Part of #$n"; else echo "Closes #$n"; fi
}
