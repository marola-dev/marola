#!/usr/bin/env bash
# backfill-pr-labels — apply scripts/pr-label.sh's taxonomy to every finalized (merged or closed,
# never open) PR that currently has zero labels. Safe to re-run: a PR gets exactly one pass once
# it has any label (including area/unscoped), so a second run is a no-op scan, not a re-classify.
#
#   scripts/backfill-pr-labels.sh              # label every unlabeled merged/closed PR
#   scripts/backfill-pr-labels.sh --dry-run    # print what would be applied, change nothing
#   scripts/backfill-pr-labels.sh --limit 20   # cap how many PRs this run touches
#
# Needs `gh auth status` OK. One `gh pr edit` per PR that needs it — no batching, so this is
# gh-API-rate-bound, not slow for its own sake; `--limit` exists for a first cautious run.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

dry_run=0; limit=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) dry_run=1; shift ;;
    --limit) limit="$2"; shift 2 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "backfill-pr-labels: unrecognized argument: $1" >&2; exit 1 ;;
  esac
done

mapfile -t pr_numbers < <(
  gh pr list --state all --limit 1000 --json number,state,labels \
    --jq '.[] | select(.state != "OPEN") | select((.labels | length) == 0) | .number'
)

if [ "${#pr_numbers[@]}" -eq 0 ]; then
  echo "backfill-pr-labels: no finalized PR without a label — nothing to do"
  exit 0
fi

[ "$limit" -gt 0 ] && pr_numbers=("${pr_numbers[@]:0:$limit}")

echo "backfill-pr-labels: ${#pr_numbers[@]} finalized, unlabeled PR(s) to classify"
args=()
[ "$dry_run" -eq 1 ] && args+=(--dry-run)
for n in "${pr_numbers[@]}"; do
  "$script_dir/pr-label.sh" "${args[@]}" "$n"
done
