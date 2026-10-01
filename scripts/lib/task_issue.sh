#!/usr/bin/env bash
# task_issue — the issue line of a MIP task PR's body (#512): `Closes #N` for the issue its
# MIP-NNNN.tasks.md row links, `Part of #N` when the PR carries the `task-partial` label.
# A label, not a commit trailer, because AGENTS.md allows exactly three trailers. MIP-0070 §5.6:
# once the .tasks.md itself can come from the umbrella (scripts/lib/mip_ref.sh), its row may link
# an issue in a different repo than this PR's — written `owner/repo#N`, never bare, in that case.
task_issue_line() {   # <branch> <tasks file text> <PR labels, one per line> <owner/repo>
  local branch="$1" tasks="$2" labels="$3" repo="$4" k issue_repo n ref
  [[ "$branch" =~ ^mip-[0-9]{4}/([0-9]+)- ]] || return 0
  k="${BASH_REMATCH[1]}"
  IFS=$'\t' read -r issue_repo n < <(sed -nE \
    "s#^\| \[$k\]\(https://github\.com/([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)/issues/([0-9]+)\) \|.*#\1\t\2#p" \
    <<<"$tasks" | head -1)
  [ -n "$n" ] || return 0
  ref="#$n"; [ "$issue_repo" = "$repo" ] || ref="$issue_repo#$n"
  if grep -qx 'task-partial' <<<"$labels"; then echo "Part of $ref"; else echo "Closes $ref"; fi
}
