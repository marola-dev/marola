#!/usr/bin/env bash
# pr-label — apply the deterministic label taxonomy (scripts/lib/pr_labels.sh) to one PR.
# Labels are derived, never guessed: the PR's MIP number (branch name / commit subject /
# docs/mips/MIP-NNNN-*.md touched — same detection scripts/uprd.sh uses), the top-level dirs it
# touched, and its author. No LLM call, no cost.
#
#   scripts/pr-label.sh              # the current branch's open PR
#   scripts/pr-label.sh 168          # that PR by number (also `#168`)
#   scripts/pr-label.sh --dry-run 168
#
# Only adds labels (gh --add-label is additive) — never removes one, so a manually-added label
# survives a re-run. Re-running after the taxonomy changes will not clear a label this script
# applied under an old mapping; edit it by hand if that happens.
# Needs `gh auth status` OK.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/pr_labels.sh
source "$script_dir/lib/pr_labels.sh"

dry_run=0; pr_arg=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=1 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    [0-9]*|\#[0-9]*) pr_arg="${arg#\#}" ;;
    *) echo "pr-label: unrecognized argument: $arg" >&2; exit 1 ;;
  esac
done

if [ -z "$pr_arg" ]; then
  pr_arg="$(gh pr view --json number --jq .number 2>/dev/null)" || {
    echo "pr-label: no PR number given and no PR found for the current branch" >&2
    exit 1
  }
fi

json="$(gh pr view "$pr_arg" --json number,url,headRefName,author,commits,files,labels)"
number="$(jq -r .number <<<"$json")"
url="$(jq -r .url <<<"$json")"
ref="$(jq -r .headRefName <<<"$json")"
author="$(jq -r .author.login <<<"$json")"
subjects="$(jq -r '.commits[].messageHeadline' <<<"$json")"
paths="$(jq -r '.files[].path' <<<"$json")"
existing="$(jq -r '.labels[].name' <<<"$json")"

mapfile -t new_labels < <(pr_label_classify "$ref" "$author" "$subjects" "$paths")

to_add=()
for l in "${new_labels[@]}"; do
  grep -qxF "$l" <<<"$existing" || to_add+=("$l")
done

if [ "${#to_add[@]}" -eq 0 ]; then
  echo "pr-label: #$number already has every label this run would add — nothing to do ($url)"
  exit 0
fi

echo "pr-label: #$number ($url) -> ${to_add[*]}"
[ "$dry_run" -eq 1 ] && { echo "pr-label: --dry-run, not applying"; exit 0; }

ensure_pr_labels
joined="$(IFS=,; echo "${to_add[*]}")"
gh pr edit "$number" --add-label "$joined" >/dev/null
echo "pr-label: labeled #$number"
