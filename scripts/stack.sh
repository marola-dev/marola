#!/usr/bin/env bash
# stack — stacked-PR mechanics for MIP task branches (see .claude/skills/mip-tasks/SKILL.md).
# scripts/stack.sh start MIP-0005 1 board-json # branch mip-0005/1-board-json off main
# scripts/stack.sh start MIP-0005 2 map-page # off the previous task branch (mip-0005/1-*)
# scripts/stack.sh pr # push + open/update the PR with the right base scripts/stack.sh restack #
# after a base PR was squash-merged: rebase onto main scripts/stack.sh status # every branch of
# this MIP with its base and PR scripts/stack.sh branches [MIP-0005] # the MIP's task branches,
# local or remote, bottom to top scripts/stack.sh link [MIP-0005] # GitHub Stack (gh stack link)
# from the *open* PRs, bottom to top --dry-run on any subcommand prints what would run.
set -euo pipefail
dry=0; args=()
for a in "$@"; do [ "$a" = "--dry-run" ] && dry=1 || args+=("$a"); done
set -- "${args[@]}"
run() { if [ "$dry" -eq 1 ]; then echo "+ $*"; else "$@"; fi; }

cur="$(git branch --show-current)"
mip_of() { sed -n 's#^\(mip-[0-9]\{4\}\)/.*#\1#p' <<<"$1"; }
task_of() { sed -n 's#^mip-[0-9]\{4\}/\([0-9]*\)-.*#\1#p' <<<"$1"; }
branch_for() { git branch --list "$1/$2-*" --format='%(refname:short)' | head -1; }
branches_of() {   # every task branch of a MIP, local or on origin, sorted by task number
  { git branch --list "$1/*" --format='%(refname:short)'
    git branch -r --list "origin/$1/*" --format='%(refname:short)' | sed 's#^origin/##'; } | sort -u | sort -t/ -k2 -n
}
mip_arg() {       # the MIP from $1 (MIP-0005 / mip-0005) or from the current branch
  local m; m="$(tr 'A-Z' 'a-z' <<<"${1:-$(mip_of "$cur")}")"
  [ -n "$m" ] || { echo "not on a mip-NNNN/* branch — pass the MIP: MIP-0005" >&2; exit 1; }
  echo "$m"
}
base_for() {   # base branch of a task branch
  local mip k prev; mip="$(mip_of "$1")"; k="$(task_of "$1")"
  [ -n "$mip" ] || { echo "not a task branch: $1" >&2; exit 1; }
  if [ "$k" -le 1 ]; then echo main; return; fi
  prev="$(branch_for "$mip" $((k - 1)))"; [ -n "$prev" ] || prev="origin/$(git branch -r --list "origin/$mip/$((k-1))-*" --format='%(refname:short)' | head -1 | sed 's#^origin/##')"
  echo "${prev#origin/}"
}

case "${1:-}" in
  start)
    mip="$(tr 'A-Z' 'a-z' <<<"${2:?MIP-NNNN}")"; k="${3:?task number}"; slug="${4:?slug}"
    new="$mip/$k-$slug"
    if [ "$k" -le 1 ]; then base=main; else base="$(base_for "$new")"; fi
    run git fetch -q origin
    if [ "$base" = main ]; then run git checkout -q -b "$new" origin/main; else run git checkout -q -b "$new" "$base"; fi
    echo "started $new (base: $base)"
    ;;
  pr)
    base="$(base_for "$cur")"
    run git push -q -u origin "$cur"
    if gh pr view "$cur" --json number -q .number >/dev/null 2>&1; then
      run gh pr edit "$cur" --base "$base"
      run scripts/uprd.sh
    else
      title="$(git log --reverse --format=%s "origin/$base..HEAD" 2>/dev/null | head -1)"
      [ -n "$title" ] || title="$(git log -1 --format=%s)"
      body="$(mktemp)"; { echo "Stacked on \`$base\` — part of ${cur%%/*}. Merge order: base first."; echo; scripts/uprd.sh --dry-run 2>/dev/null | sed '1d'; } > "$body"
      run gh pr create --base "$base" --head "$cur" --title "$title" --body-file "$body"
    fi
    ;;
  restack)
    mip="$(mip_of "$cur")"; k="$(task_of "$cur")"
    old_base="$(base_for "$cur")"
    run git fetch -q origin
    # If the old base's PR was squash-merged, its commits are in main under a different hash:
    # rebase only this branch's own commits onto main.
    fork="$(git merge-base "$cur" "$old_base" 2>/dev/null || true)"
    if [ -n "$fork" ]; then run git rebase --onto origin/main "$fork" "$cur"; else run git rebase origin/main "$cur"; fi
    run git push -q --force-with-lease origin "$cur"
    echo "restacked $cur onto main (old base $old_base)"
    ;;
  status)
    mip="${2:-$(mip_of "$cur")}"; mip="$(tr 'A-Z' 'a-z' <<<"$mip")"
    for b in $(git branch --list "$mip/*" --format='%(refname:short)' | sort -t/ -k2 -n); do
      pr="$(gh pr view "$b" --json number,state -q '"#\(.number) \(.state)"' 2>/dev/null || echo "no PR")"
      printf '%-40s base=%-28s %s  (%s ahead of main)\n' "$b" "$(base_for "$b")" "$pr" "$(git rev-list --count origin/main.."$b")"
    done
    ;;
  branches)
    branches_of "$(mip_arg "${2:-}")"
    ;;
  link)
    # GitHub's native Stack (the "Preview stack" box on a PR) through the official extension.
    # stack_link (scripts/lib/stack_link.sh, shared with scripts/deps-stack.sh) does the open-PR
    # filtering and the actual `gh stack link` call.
    mip="$(mip_arg "${2:-}")"
    # shellcheck source=scripts/lib/stack_link.sh.
    source "$(dirname "${BASH_SOURCE[0]}")/lib/stack_link.sh"
    mapfile -t brs < <(branches_of "$mip")
    stack_link "$dry" "${brs[@]}"
    ;;
  *) sed -n '2,15p' "$0"; exit 1 ;;
esac
