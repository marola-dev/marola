#!/usr/bin/env bash
# uprd — update the current branch's pull-request description on GitHub from its commits.
#
#   just uprd                 # rewrite the PR body and print its URL
#   just uprd --dry-run       # print the body that would be written, change nothing
#   just uprd path/to/body.md # use that file as the body instead of generating one
#   BASE=main just uprd       # base branch (default: the PR's base, else main)
#
# Generated body = "What changed" (one entry per commit on the branch, subject + body, trailers
# stripped) + "Cost" (every `Cost:` trailer found in those commits, per AGENTS.md "Attribution and
# cost accounting"). Needs `gh` logged in (gh auth status) except for --dry-run.
set -euo pipefail

dry_run=0; body_file=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) body_file="$arg" ;;
  esac
done

branch="$(git branch --show-current)"
[ -n "$branch" ] || { echo "uprd: detached HEAD — check out the PR branch first" >&2; exit 1; }

pr_number=""; pr_url=""; base="${BASE:-}"
if pr_json="$(gh pr view "$branch" --json number,url,baseRefName 2>/dev/null)"; then
  pr_number="$(printf '%s' "$pr_json" | sed -n 's/.*"number":\([0-9]*\).*/\1/p')"
  pr_url="$(printf '%s' "$pr_json" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')"
  [ -n "$base" ] || base="$(printf '%s' "$pr_json" | sed -n 's/.*"baseRefName":"\([^"]*\)".*/\1/p')"
fi
[ -n "$base" ] || base=main
if [ -z "$pr_number" ] && [ "$dry_run" -eq 0 ]; then
  echo "uprd: no open PR found for '$branch' (or gh is not logged in — run: gh auth status)." >&2
  echo "       open one first: gh pr create --base $base --head $branch --fill" >&2
  exit 1
fi

generate_body() {
  local range="origin/$base..HEAD"
  git fetch -q origin "$base" 2>/dev/null || true
  echo "## What changed"
  echo
  # Oldest first; subject as a bullet, body indented, trailers dropped.
  git log --reverse --format='%h%x00%s%x00%b%x00' "$range" | awk -v RS='\0' '
    NR % 3 == 1 { sha = $0; gsub(/^\n+|\n+$/, "", sha) }
    NR % 3 == 2 { subj = $0 }
    NR % 3 == 0 {
      printf "- **%s** (`%s`)\n", subj, sha
      n = split($0, lines, "\n")
      for (i = 1; i <= n; i++) {
        l = lines[i]
        if (l ~ /^(Co-Authored-By|Cost|Claude-Session|Signed-off-by):/) continue
        if (l ~ /^[[:space:]]*$/) { printf "\n"; continue }
        printf "  %s\n", l
      }
      printf "\n"
    }'
  echo "## Cost"
  echo
  local costs
  costs="$(git log --reverse --format='%h%x00%b%x00' "$range" | awk -v RS='\0' '
    NR % 2 == 1 { sha = $0; gsub(/^\n+|\n+$/, "", sha) }
    NR % 2 == 0 { n = split($0, lines, "\n"); for (i = 1; i <= n; i++) if (lines[i] ~ /^Cost:/) printf "- %s (`%s`)\n", substr(lines[i], 6), sha }')"
  if [ -n "$costs" ]; then
    printf '%s\n' "$costs" | sed 's/^- \s*/- /'
  else
    echo "- not recorded in this branch's commits — add a \`Cost:\` trailer (AGENTS.md, \"Attribution and cost accounting\"); \`just claude-cost\` shows the sessions"
  fi
}

tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
if [ -n "$body_file" ]; then cp "$body_file" "$tmp"; else generate_body | cat -s > "$tmp"; fi

if [ "$dry_run" -eq 1 ]; then
  echo "----- uprd dry run: branch=$branch base=$base pr=${pr_number:-none} -----"
  cat "$tmp"
  exit 0
fi

gh pr edit "$pr_number" --body-file "$tmp" >/dev/null
echo "updated PR #$pr_number description: $pr_url"
