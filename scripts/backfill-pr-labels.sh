#!/usr/bin/env bash
# backfill-pr-labels — apply scripts/pr-label.sh's taxonomy to every finalized (merged or closed,
# never open) PR that currently has zero labels.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/pr_labels.sh.
source "$script_dir/lib/pr_labels.sh"

NLP_MIN_SIMILARITY="${NLP_MIN_SIMILARITY:-0.15}"

dry_run=0; limit=0; use_nlp=0; nlp_apply_unscoped=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) dry_run=1; shift ;;
    --limit) limit="$2"; shift 2 ;;
    --nlp) use_nlp=1; shift ;;
    --nlp-apply-unscoped) use_nlp=1; nlp_apply_unscoped=1; shift ;;
    -h|--help) sed -n '2,29p' "$0"; exit 0 ;;
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

# nlp_compare <pr-number> — prints the deterministic vs. NLP area/* comparison, and (only with
# --nlp-apply-unscoped) applies the NLP suggestion when the deterministic side is bare
# area/unscoped and the NLP score clears the threshold.
nlp_compare() {
  local n="$1" json ref subjects paths mip areas nlp_out nlp_label nlp_score
  json="$(gh pr view "$n" --json title,body,headRefName,commits)"
  ref="$(jq -r .headRefName <<<"$json")"
  subjects="$(jq -r '.commits[].messageHeadline' <<<"$json")"
  paths="" # deterministic area lookup only needs the MIP number, not touched paths
  mip="$(pr_label_mip_number "$ref" "$subjects" "$paths" || true)"
  if [ -n "${mip:-}" ] && [ -n "${PR_LABEL_MIP_AREA[$mip]:-}" ]; then
    areas="${PR_LABEL_MIP_AREA[$mip]}"
  else
    areas="area/unscoped"
  fi

  local title body
  title="$(jq -r .title <<<"$json")"
  body="$(jq -r '.body // ""' <<<"$json")"
  nlp_out="$(python3 "$script_dir/pr_label_nlp.py" --title "$title" --body "$body $subjects" --top 1 --json 2>/dev/null || echo '[]')"
  nlp_label="$(jq -r '.[0].label // empty' <<<"$nlp_out")"
  nlp_score="$(jq -r '.[0].similarity // 0' <<<"$nlp_out")"

  if [ -n "$nlp_label" ]; then
    echo "backfill-pr-labels: #$n — deterministic: $areas | nlp: $nlp_label (similarity $nlp_score)"
  else
    echo "backfill-pr-labels: #$n — deterministic: $areas | nlp: no candidate above zero similarity"
  fi

  if [ "$nlp_apply_unscoped" -eq 1 ] && [ "$areas" = "area/unscoped" ] && [ -n "$nlp_label" ]; then
    if awk -v s="$nlp_score" -v t="$NLP_MIN_SIMILARITY" 'BEGIN { exit !(s >= t) }'; then
      if [ "$dry_run" -eq 1 ]; then
        echo "backfill-pr-labels: #$n — --dry-run, would additionally apply nlp-suggested $nlp_label (area/unscoped gap fill)"
      else
        ensure_pr_labels
        gh pr edit "$n" --add-label "$nlp_label" >/dev/null
        echo "backfill-pr-labels: #$n — applied nlp-suggested $nlp_label (area/unscoped gap fill, similarity $nlp_score >= $NLP_MIN_SIMILARITY)"
      fi
    else
      echo "backfill-pr-labels: #$n — nlp similarity $nlp_score below $NLP_MIN_SIMILARITY, not applying"
    fi
  fi
}

for n in "${pr_numbers[@]}"; do
  "$script_dir/pr-label.sh" "${args[@]}" "$n"
  [ "$use_nlp" -eq 1 ] && nlp_compare "$n"
done
