#!/usr/bin/env bash
# pr — the one-command agent path from a finished commit to a filled, pushed PR (AGENTS.md
# "Attribution and cost accounting" — "write the commit, run `just pr`, nothing else"): 1. refuse
# on main or with a dirty working tree (tracked changes; untracked files are ignored) — commit
# first 2. push — a mip-NNNN/k-* task branch goes through `scripts/stack.sh pr`, which runs
# scripts/cost-fill.sh itself (#524: this script used to run it too, which would have doubled the
# run for no reason — stack.sh is the one script that also knows the right base, so it owns
# cost-fill for a task branch); anything else runs scripts/cost-fill.sh here (idempotent, decides
# on its own whether a trailer needs upgrading) then a plain `git push`, with `--force-with-lease`
# when cost-fill just rewrote history (detected by comparing HEAD).
set -euo pipefail

dry_run=0
for a in "$@"; do [ "$a" = "--dry-run" ] && dry_run=1; done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
branch="$(git branch --show-current)"
[ -n "$branch" ] || { echo "pr: detached HEAD — check out a branch first" >&2; exit 1; }
[ "$branch" != main ] || { echo "pr: refusing to open a PR from main" >&2; exit 1; }
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then   # untracked scratch files are not a reason to block a PR
  echo "pr: working tree is dirty — commit or stash first" >&2
  exit 1
fi

git fetch -q origin main 2>/dev/null || true
mapfile -t shas < <(git log --reverse --format=%H origin/main..HEAD)
if [ "${#shas[@]}" -eq 0 ]; then
  echo "pr: no commits ahead of origin/main on $branch — nothing to open a PR for" >&2
  exit 1
fi

is_mip_task=0
[[ "$branch" =~ ^mip-[0-9]{4}/ ]] && is_mip_task=1

if [ "$dry_run" -eq 1 ]; then
  echo "pr --dry-run on $branch (mip task: $([ "$is_mip_task" -eq 1 ] && echo yes || echo no)):"
  echo
  if [ "$is_mip_task" -eq 1 ]; then
    echo "+ scripts/stack.sh pr   # runs cost-fill.sh itself — base = the previous task's branch, PR body via uprd.sh"
    scripts/stack.sh pr --dry-run
  else
    echo "+ just cost-fill"
    "$script_dir/cost-fill.sh" --dry-run
    echo
    echo "+ git push -u origin $branch   # --force-with-lease instead, if cost-fill rewrote history above"
    echo "+ just uprd"
    "$script_dir/uprd.sh" --dry-run
  fi
  exit 0
fi

if [ "$is_mip_task" -eq 1 ]; then
  scripts/stack.sh pr
else
  before_head="$(git rev-parse HEAD)"
  "$script_dir/cost-fill.sh"
  if [ "$before_head" != "$(git rev-parse HEAD)" ]; then
    git push --force-with-lease -u origin "$branch"
  else
    git push -u origin "$branch"
  fi
  "$script_dir/uprd.sh"
fi

pr_url="$(gh pr view "$branch" --json url -q .url 2>/dev/null || true)"
if [ -n "$pr_url" ]; then
  echo "pr: $pr_url"
else
  echo "pr: pushed and updated the PR, but couldn't read its URL back (gh not logged in?) — check GitHub" >&2
fi
