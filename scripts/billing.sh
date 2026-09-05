#!/usr/bin/env bash
# billing — one-shot view of this GitHub account's metered usage: Actions minutes, GHCR/Packages
# storage, Copilot, all of it. This repo is private (no free public-repo Actions minutes or GHCR
# storage), so these quotas are real money, not just hygiene — see docker.yml's cost-model comment.
#
#   scripts/billing.sh                 # current month to date
#   scripts/billing.sh --month 8       # a past month this year
#   scripts/billing.sh --year 2026 --month 8
#
# Tries GitHub's consolidated usage report first (GET /users/{login}/settings/billing/usage — the
# "enhanced billing platform" endpoint that replaced the separate per-product Actions/Packages/
# shared-storage APIs, which GitHub is retiring: https://github.blog/changelog/2025-09-26-product-specific-billing-apis-are-closing-down/).
# It covers Actions, Packages (GHCR storage/bandwidth) and a personal Copilot subscription in one
# call, grouped by product below. If the account isn't on that platform yet, falls back to the
# three legacy per-product endpoints — Copilot then has no per-user API at all; check
# https://github.com/settings/copilot by hand.
#
# Requires `gh auth status` (not available inside the ai-jail sandbox — run from the host).
set -euo pipefail

gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }

year="$(date +%Y)"
month="$(date +%-m)"
while [ $# -gt 0 ]; do
  case "$1" in
    --year) year="$2"; shift 2 ;;
    --month) month="$2"; shift 2 ;;
    *) echo "usage: billing.sh [--year YYYY] [--month M]" >&2; exit 1 ;;
  esac
done

login="$(gh api user --jq .login)"
echo "GitHub billing usage — $login, $year-$(printf '%02d' "$month")"
echo

err_file="$(mktemp)"
trap 'rm -f "$err_file"' EXIT
usage_json="$(gh api "/users/$login/settings/billing/usage?year=$year&month=$month" 2>"$err_file")" && new_platform=1 || new_platform=0

if [ "$new_platform" -eq 1 ]; then
  count="$(echo "$usage_json" | jq '.usageItems | length')"
  if [ "$count" -eq 0 ]; then
    echo "no usage recorded for this period yet"
    exit 0
  fi
  echo "$usage_json" | jq -r '
    .usageItems
    | group_by(.product)
    | map({product: .[0].product, quantity: (map(.quantity) | add), unit: .[0].unitType, net: (map(.netAmount) | add)})
    | sort_by(-.net)
    | (["PRODUCT","QUANTITY","UNIT","NET_USD"], (.[] | [.product, (.quantity|tostring), .unit, (.net|(.*100|round/100)|tostring)]))
    | @tsv
  ' | column -t -s $'\t'
  total="$(echo "$usage_json" | jq '[.usageItems[].netAmount] | add | (.*100|round/100)')"
  echo
  echo "total: \$$total (list-price gross minus any plan-included allowance already applied — see the Product column above; each product's included free quota is not itemized separately, so \$0.00 rows mean fully covered by the plan, not free by nature)"
else
  echo "consolidated usage report not available for this account (raw error below) — falling back to the legacy per-product endpoints:"
  cat "$err_file" >&2
  echo
  echo "-- Actions minutes --"
  gh api "/users/$login/settings/billing/actions" 2>&1 | jq '.' || echo "(not available)"
  echo
  echo "-- Packages storage (GHCR) --"
  gh api "/users/$login/settings/billing/packages" 2>&1 | jq '.' || echo "(not available)"
  echo
  echo "-- Shared storage (Actions artifacts + Packages) --"
  gh api "/users/$login/settings/billing/shared-storage" 2>&1 | jq '.' || echo "(not available)"
  echo
  echo "-- Copilot --"
  echo "no per-user API for an individual Copilot subscription — check https://github.com/settings/copilot by hand."
fi
