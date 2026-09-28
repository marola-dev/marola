#!/usr/bin/env bash
# uprd — update the current branch's pull-request description on GitHub from its commits. just
# uprd # rewrite the PR body and print its URL — creates the PR if none exists just uprd --dry-run
# # print the body that would be written, change nothing just uprd 84 # that PR by number (also
# `#84`): its head branch and base are # taken from GitHub, so it works from any checkout — needs
# gh just uprd path/to/body.md # use that file as the body instead of generating one BASE=main
# just uprd # base branch (default: the PR's base, else main) BRANCH=x EXTRA_FILE=f #
# (scripts/uprds.sh) another branch than the checked-out one, and a # file appended to the
# generated body — the stack section Generated body follows .github/PULL_REQUEST_TEMPLATE.md's
# shape (bold labels + a table, no `#` headings — see AGENTS.md "Attribution and cost
# accounting"): Summary — the first (oldest) commit's body paragraph, trimmed to ~2 sentences, or
# a `<!-- fill -->` placeholder when that commit has no body.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/uprd_title.sh.
source "$script_dir/lib/uprd_title.sh"
# shellcheck source=scripts/lib/mip_ref.sh.
source "$script_dir/lib/mip_ref.sh"

dry_run=0; body_file=""; pr_arg=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=1 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    [0-9]*|\#[0-9]*) pr_arg="${arg#\#}" ;;
    *) body_file="$arg" ;;
  esac
done

# A PR number resolves the branch and base from GitHub — the checkout can be on anything.
pr_number=""; pr_url=""; base="${BASE:-}"
if [ -n "$pr_arg" ]; then
  # gh's own --jq, not sed over the JSON: gh pretty-prints when stdout is a terminal and a `"key":
  # "value"` with a space silently matched nothing, which made a valid number fall through to the
  # "you are on main" guard.
  if ! pr_tsv="$(gh pr view "$pr_arg" --json number,url,baseRefName,headRefName,state \
        --jq '[.number, .url, .baseRefName, .headRefName, .state] | @tsv' 2>/dev/null)"; then
    echo "uprd: cannot resolve PR #$pr_arg — gh not logged in (gh auth status), or no such PR" >&2
    exit 1
  fi
  IFS=$'\t' read -r pr_number pr_url pr_base pr_head state <<<"$pr_tsv"
  [ "$state" = "OPEN" ] || { echo "uprd: PR #$pr_arg is ${state:-unknown} — only open PRs are rewritten" >&2; exit 1; }
  [ -n "$pr_head" ] || { echo "uprd: PR #$pr_arg has no head branch in gh's answer: $pr_tsv" >&2; exit 1; }
  [ -n "$base" ] || base="$pr_base"
  BRANCH="$pr_head"
  git fetch -q origin "$BRANCH" 2>/dev/null || true
fi

branch="${BRANCH:-$(git branch --show-current)}"
[ -n "$branch" ] || { echo "uprd: detached HEAD — check out the PR branch first" >&2; exit 1; }
# Refuse on the base branch itself, before any gh call — `gh pr create --head main --base main`
# fails confusingly ("head branch is the same as base branch"). ${BASE:-main} is only a guess here
# (the PR's real base is resolved below via `gh pr view`), but it catches the common case.
guard_base="${BASE:-main}"
[ "$branch" != "$guard_base" ] || { echo "uprd: you are on '$branch' — check out the PR branch first (git switch <branch>), or BRANCH=<branch> just uprd" >&2; exit 1; }
# The branch's tip: local if it exists here, else the remote-tracking one (uprds on a stack that
# was pushed from another machine).
if git rev-parse --verify -q "$branch" >/dev/null; then head_ref="$branch"; else head_ref="origin/$branch"; fi

if [ -z "$pr_number" ] && pr_tsv="$(gh pr view "$branch" --json number,url,baseRefName \
      --jq '[.number, .url, .baseRefName] | @tsv' 2>/dev/null)"; then
  IFS=$'\t' read -r pr_number pr_url pr_base <<<"$pr_tsv"
  [ -n "$base" ] || base="$pr_base"
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
first_subject="$(git log --reverse --format=%s "$range" 2>/dev/null | head -1 || true)"
title_mip_ref="$(detect_mip_ref "$branch" "$range")"
title=""
[ -n "$first_subject" ] && title="$(cap_title "$first_subject" "$title_mip_ref")"

generate_summary() {
  local first_sha body
  first_sha="$(git log --reverse --format=%H "$range" | head -1 || true)"  # see the SIGPIPE note above
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
  # Same detection already used for the PR title (scripts/lib/mip_ref.sh) — one source of truth,
  # so the title and this table cell can never disagree on which MIP a branch is scoped to.
  local mip_ref="$title_mip_ref" mip_path
  if [ -z "$mip_ref" ]; then
    echo "none — not MIP-scoped"
    return
  fi
  # The document as it exists on the branch's tip (a PR that adds the MIP has it there, not on the
  # checked-out main), linked by its blob URL — a relative path does not resolve in a PR body.
  mip_path="$(git ls-tree -r --name-only "$head_ref" -- docs/MIPs 2>/dev/null \
    | { grep -E "^docs/MIPs/${mip_ref}-[^/]*\.md$" || true; } | head -1)"
  if [ -n "$mip_path" ]; then
    echo "[$mip_ref]($(repo_web_url)/blob/$branch/$mip_path)"
  else
    echo "$mip_ref (no docs/MIPs/${mip_ref}-*.md on this branch)"
  fi
}

repo_web_url() {   # git@github.com:o/r.git | https://github.com/o/r(.git) → https://github.com/o/r
  git remote get-url origin 2>/dev/null \
    | sed -E 's#^git@([^:]+):#https://\1/#; s#^ssh://git@#https://#; s#\.git$##'
}

generate_tested() {
  # One line for the Tested table cell: the four gates as glyph + short name, then ` — ` and the
  # note(s) from every `Tested:` trailer on the branch (AGENTS.md).
  local trailers gate=⬜ e2e=⬜ live=⬜ ci=⬜ notes="" n_notes=0 line head note tok extra
  trailers="$(git log --format='%(trailers:key=Tested,valueonly,unfold)' "$range" 2>/dev/null | sed '/^[[:space:]]*$/d')"
  if [ -z "$trailers" ]; then
    echo "not recorded — add a \`Tested:\` trailer (AGENTS.md)"
    return
  fi
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    case "$line" in
      *" — "*) head="${line%% — *}"; note="${line#* — }" ;;
      *" -- "*) head="${line%% -- *}"; note="${line#* -- }" ;;
      *) head="$line"; note="" ;;
    esac
    extra=""
    for tok in $(printf '%s' "$head" | tr ',;' '  '); do
      case "$tok" in
        gates) gate=✅ ;; e2e) e2e=✅ ;; live) live=✅ ;; ci-only) ci=✅ ;;
        *) extra="${extra:+$extra }$tok" ;;
      esac
    done
    note="$(printf '%s' "${extra:+$extra — }$note" | sed -E 's/^[[:space:]—-]+//; s/[[:space:]]+$//')"
    # Newest commit's note only (git log order): a six-commit branch otherwise turns the cell into
    # a wall of text; the earlier notes are one click away in the commits.
    if [ -n "$note" ]; then n_notes=$((n_notes + 1)); [ -n "$notes" ] || notes="$note"; fi
  done <<<"$trailers"
  local cell="$gate gates · $e2e e2e · $live live · $ci ci-only"
  [ -n "$notes" ] && cell="$cell — $notes"
  [ "$n_notes" -gt 1 ] && cell="$cell (+$((n_notes - 1)) earlier notes in the commits)"
  echo "$cell"
}

generate_cost() {
  # One line per commit, oldest first, joined with `<br>` (a markdown table cell can't hold a raw
  # newline): the commit's own `Cost:` trailer when it has one; otherwise `scripts/cost-split.py
  # --estimate-commit` for that commit, labelled `est.` and never mistaken for a measurement; "not
  # recorded" only for a commit where even the estimator has nothing (an empty diff, or no
  # calibratable history) — never a blanket fallback for the whole branch.
  local sha short body line text out="" sep=""
  while IFS= read -r sha; do
    [ -n "$sha" ] || continue
    short="$(git rev-parse --short "$sha")"
    body="$(git log -1 --format=%B "$sha")"
    line="$(grep '^Cost:' <<<"$body" | head -1 || true)"  # see the SIGPIPE note near the top of this file
    if [ -n "$line" ]; then
      text="${line#Cost: }"
    else
      text="$(python3 "$script_dir/cost-split.py" --estimate-commit "$sha" 2>/dev/null || true)"
      text="${text#Cost: }"
    fi
    [ -n "$text" ] || text="not recorded — add a \`Cost:\` trailer"
    out="${out}${sep}${text} (\`${short}\`)"
    sep="<br>"
  done < <(git log --reverse --format=%H "$range" 2>/dev/null)
  printf '%s\n' "$out"
}

# The first line is a marker for .github/workflows/pr-body.yml: while it is present the workflow
# regenerates the body on every push to the PR (new commits change What changed / Cost / Tested);
# a human who rewrites the body by hand removes the line and the workflow leaves the PR alone.
generate_body() {
  echo "<!-- uprd: generated from the branch's commits — delete this line to stop pr-body.yml from regenerating it -->"
  echo "**Summary** — $(generate_summary)"
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| **MIP** | $(generate_mip) |"
  echo "| **Tested** | $(generate_tested) |"
  echo "| **Cost** | $(generate_cost) |"
  echo
  echo "**What changed**"
  # shellcheck disable=SC2016 # the backticks are literal markdown, not command substitution.
  git log --reverse --format='- %s (`%h`)' "$range" 2>/dev/null
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
  # Pass --title in the body-file mode to override by editing the PR afterwards.
  git rev-parse --verify -q "origin/$branch" >/dev/null || git push -q -u origin "$branch"
  pr_url="$(gh pr create --base "$base" --head "$branch" --title "$title" --body-file "$tmp")"
  echo "created PR: $pr_url"
else
  gh pr edit "$pr_number" --body-file "$tmp" >/dev/null
  echo "updated PR #$pr_number description: $pr_url"
fi
