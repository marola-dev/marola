#!/usr/bin/env bash
# uprd — update the current branch's pull-request description on GitHub from its commits.
#
#   just uprd                 # rewrite the PR body and print its URL — creates the PR if none exists
#   just uprd --dry-run       # print the body that would be written, change nothing
#   just uprd path/to/body.md # use that file as the body instead of generating one
#   BASE=main just uprd       # base branch (default: the PR's base, else main)
#   BRANCH=x EXTRA_FILE=f     # (scripts/uprds.sh) another branch than the checked-out one, and a
#                             # file appended to the generated body — the stack section
#
# Generated body follows .github/PULL_REQUEST_TEMPLATE.md's shape (same headings, same order):
#   Summary   — the first (oldest) commit's body paragraph, trimmed to ~2 sentences, or a
#               `<!-- fill -->` placeholder when that commit has no body.
#   MIP       — auto-detected from the branch name (`mip-NNNN/...`) or a `MIP-NNNN` token
#               anywhere in the commits; "none — not MIP-scoped" otherwise.
#   What changed — one bullet per commit subject.
#   Tested    — a checklist of this repo's gates (AGENTS.md), boxes ticked only when a commit
#               body mentions running them; left for the author otherwise.
#   Cost      — every `Cost:` trailer found in those commits (AGENTS.md "Attribution and cost
#               accounting" — the trailer is the source of truth, this section never drifts from it).
# The PR title is the first commit's subject, capped at 70 chars (scripts/lib/uprd_title.sh) —
# a warning is printed to stderr when a title had to be cut.
# Needs `gh` logged in (gh auth status) except for --dry-run.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/uprd_title.sh
source "$script_dir/lib/uprd_title.sh"

dry_run=0; body_file=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) body_file="$arg" ;;
  esac
done

branch="${BRANCH:-$(git branch --show-current)}"
[ -n "$branch" ] || { echo "uprd: detached HEAD — check out the PR branch first" >&2; exit 1; }
# The branch's tip: local if it exists here, else the remote-tracking one (uprds on a stack that
# was pushed from another machine).
if git rev-parse --verify -q "$branch" >/dev/null; then head_ref="$branch"; else head_ref="origin/$branch"; fi

pr_number=""; pr_url=""; base="${BASE:-}"
if pr_json="$(gh pr view "$branch" --json number,url,baseRefName 2>/dev/null)"; then
  pr_number="$(printf '%s' "$pr_json" | sed -n 's/.*"number":\([0-9]*\).*/\1/p')"
  pr_url="$(printf '%s' "$pr_json" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')"
  [ -n "$base" ] || base="$(printf '%s' "$pr_json" | sed -n 's/.*"baseRefName":"\([^"]*\)".*/\1/p')"
fi
[ -n "$base" ] || base=main
if [ -z "$pr_number" ] && [ "$dry_run" -eq 0 ]; then
  if ! gh auth status >/dev/null 2>&1; then
    echo "uprd: gh is not logged in — run: gh auth login" >&2
    exit 1
  fi
  create_pr=1   # no open PR for this branch: create it with the generated body (see below)
fi
create_pr="${create_pr:-0}"

git fetch -q origin "$base" 2>/dev/null || true
range="origin/$base..$head_ref"

# Title: the first (oldest) commit's subject on the branch, capped at 70 chars.
first_subject="$(git log --reverse --format=%s "$range" 2>/dev/null | head -1)"
title=""
[ -n "$first_subject" ] && title="$(cap_title "$first_subject")"

generate_summary() {
  local first_sha body
  first_sha="$(git log --reverse --format=%H "$range" | head -1)"
  [ -n "$first_sha" ] || { echo "<!-- fill: one or two sentences — what changed and why -->"; return; }
  body="$(git log -1 --format=%b "$first_sha")"
  # First paragraph (lines up to the first blank line, trailers dropped), trimmed to ~2 sentences.
  python3 - "$body" <<'PY'
import re
import sys

body = sys.argv[1]
lines = []
for line in body.splitlines():
    if line.strip() == "":
        break
    if re.match(r"^(Co-Authored-By|Cost|Claude-Session|Signed-off-by):", line):
        continue
    lines.append(line)
para = " ".join(" ".join(lines).split())

if not para:
    print("<!-- fill: one or two sentences — what changed and why -->")
    sys.exit(0)

sentences = re.split(r"(?<=[.!?]) +", para)
summary = " ".join(sentences[:2]).strip()
if not summary.endswith((".", "!", "?")):
    summary += "."
print(summary)
PY
}

generate_mip() {
  local mip_ref="" mip_files
  if [[ "$branch" =~ ^[Mm][Ii][Pp]-([0-9]{4})/ ]]; then
    mip_ref="MIP-${BASH_REMATCH[1]}"
  else
    # Subjects only, not bodies: this repo's convention is that a MIP-scoped commit's *subject*
    # starts with "MIP-NNNN..." — a body can mention another MIP in passing (a cross-reference,
    # an example command) without this commit being scoped to it.
    mip_ref="$(git log --format='%s' "$range" 2>/dev/null \
      | { grep -ioE '^MIP-[0-9]{4}' || true; } | head -1 | tr '[:lower:]' '[:upper:]')"
  fi
  if [ -z "$mip_ref" ]; then
    echo "none — not MIP-scoped"
    return
  fi
  mip_files=(docs/mips/"${mip_ref}"-*.md)
  if [ -e "${mip_files[0]}" ]; then
    echo "[$mip_ref](${mip_files[0]})"
  else
    echo "$mip_ref (docs/mips/${mip_ref}-*.md)"
  fi
}

generate_tested() {
  local combined
  combined="$(git log --format='%s%n%b' "$range" 2>/dev/null | tr '[:upper:]' '[:lower:]')"
  has() { case "$combined" in *"$1"*) return 0 ;; *) return 1 ;; esac; }
  local gate=" " e2e=" " live=" " ci=" "
  if { has "just build" && has "just test" && has "just quality"; } \
    || has "quality gates" || has "gates green"; then gate=x; fi
  if has "just e2e" || has "e2e test"; then e2e=x; fi
  if has "just run -- --brief" || has "live run" || has "--brief"; then live=x; fi
  if has "ci only" || has "ci-only" || has "not tested locally" || has "no local run"; then ci=x; fi
  echo "- [$gate] \`just build && just test && just quality\`"
  echo "- [$e2e] \`just e2e\`"
  echo "- [$live] a live \`just run -- --brief\`"
  echo "- [$ci] CI only (not run locally)"
}

generate_body() {
  echo "## Summary"
  echo
  generate_summary
  echo
  echo "## MIP"
  echo
  generate_mip
  echo
  echo "## What changed"
  echo
  # shellcheck disable=SC2016  # the backticks are literal markdown, not command substitution
  git log --reverse --format='- %s (`%h`)' "$range" 2>/dev/null
  echo
  echo "## Tested"
  echo
  generate_tested
  echo "<!-- state plainly anything above that was NOT run -->"
  echo
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
  if [ -n "${EXTRA_FILE:-}" ] && [ -s "$EXTRA_FILE" ]; then echo; cat "$EXTRA_FILE"; fi
}

tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
if [ -n "$body_file" ]; then cp "$body_file" "$tmp"; else generate_body | cat -s > "$tmp"; fi

if [ "$dry_run" -eq 1 ]; then
  echo "----- uprd dry run: branch=$branch base=$base pr=${pr_number:-none} title=\"${title:-<none>}\" -----"
  cat "$tmp"
  exit 0
fi

if [ "$create_pr" -eq 1 ]; then
  # Pass --title in the body-file mode to override by editing the PR afterwards. The branch must
  # already be pushed.
  git rev-parse --verify -q "origin/$branch" >/dev/null || git push -q -u origin "$branch"
  pr_url="$(gh pr create --base "$base" --head "$branch" --title "$title" --body-file "$tmp")"
  echo "created PR: $pr_url"
else
  gh pr edit "$pr_number" --body-file "$tmp" >/dev/null
  echo "updated PR #$pr_number description: $pr_url"
fi
