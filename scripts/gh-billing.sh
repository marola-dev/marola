#!/usr/bin/env bash
# gh-billing — one-shot view of this GitHub account's metered usage: Actions minutes, GHCR/
# Packages storage and transfer, Copilot, all of it. This repo is private (no free public-repo
# Actions minutes or GHCR storage), so this is real money, not just hygiene — see docker.yml's
# cost-model comment. Named gh-billing, not billing: this repo already has a Claude Code cost
# tool of the same shape (`just claude-cost` / `just cost-split`, AGENTS.md "Attribution and cost
# accounting") and a bare "billing" doesn't say which bill it's showing.
#
#   scripts/gh-billing.sh                        # current month to date
#   scripts/gh-billing.sh --month 8              # a past month this year
#   scripts/gh-billing.sh --year 2026 --month 8
#   scripts/gh-billing.sh --from-file resp.json  # shape a saved API response, no `gh` call
#   scripts/gh-billing.sh --json-stdin <resp.json
#   scripts/gh-billing.sh --self-test            # shapes scripts/fixtures/gh-billing-usage.json
#                                                 # and asserts its total (in `just quality-other`)
#
# Calls GET /users/{login}/settings/billing/usage — the "enhanced billing platform"'s consolidated
# usage report, one call covering Actions, Packages (GHCR storage/transfer) and a personal Copilot
# subscription, grouped by product+SKU below:
# https://docs.github.com/en/rest/billing/usage?apiVersion=2022-11-28#get-billing-usage-report-for-a-user
# Each usageItem carries date, product, sku, quantity, unitType, pricePerUnit, grossAmount,
# discountAmount, netAmount and (for repo-attributable usage) repositoryName — see that page for
# the exact field list this script's jq relies on.
#
# It replaces three product-specific endpoints (.../billing/actions, .../billing/packages,
# .../billing/shared-storage) that GitHub announced closing down on 2025-09-26:
# https://github.blog/changelog/2025-09-26-product-specific-billing-apis-are-closing-down/
# ("we're closing down the remaining product-specific billing APIs for Actions, Packages, and
# shared storage"). That's a year in the past as of this script, so there is no live fallback here
# — a 404 from the endpoint above prints a pointer to https://github.com/settings/billing instead
# of pretending the old endpoints are still a real path.
#
# Auth: needs a classic personal access token (or `gh`'s own OAuth token) with the `user` scope.
# Fine-grained PATs are explicitly NOT supported for this endpoint (not "need a wider grant" —
# the platform rejects them outright), per
# https://docs.github.com/en/billing/tutorials/automate-usage-reporting ("The billing usage
# endpoints do not support fine-grained personal access tokens"). If `gh` is already logged in
# with a narrower classic scope, `gh auth refresh -s user` adds it without a full re-login.
#
# Requires `gh auth status` (not available inside the ai-jail sandbox — run from the host), unless
# you're shaping a saved response with --from-file/--json-stdin, which needs neither `gh` nor
# network.
#
# Repo cost tools, so you reach for the right one (AGENTS.md "Attribution and cost accounting"):
#   gh-billing.sh (this script)   — GitHub's bill: Actions minutes, GHCR storage/transfer, Copilot.
#   just claude-cost / cost-split — Claude Code quota (tokens), not a GitHub cost at all.
set -euo pipefail

# Groups usageItems by product+SKU (a month can have several rows per SKU — e.g. one per
# repository, since repositoryName is per-item, not per-SKU) and sums quantity/gross/discount/net
# per group, sorted by net descending. `empty: true` is a sentinel the printer below checks for
# instead of shaping an empty .rows array, so a period with zero usage prints one clear line.
readonly SHAPE_FILTER='
  if (.usageItems | length) == 0 then
    {empty: true}
  else
    {
      rows: (.usageItems
        | group_by([.product, .sku])
        | map({
            product: .[0].product,
            sku: .[0].sku,
            unit: .[0].unitType,
            quantity: (map(.quantity) | add),
            gross: (map(.grossAmount) | add),
            discount: (map(.discountAmount) | add),
            net: (map(.netAmount) | add)
          })
        | sort_by(-.net)),
      total: (.usageItems | map(.netAmount) | add)
    }
  end
'

usage() {
  cat <<'EOF'
usage: gh-billing.sh [--year YYYY] [--month M] [--from-file FILE | --json-stdin] [--self-test] [--help]

  --year YYYY       year to query (default: current year)
  --month M         month to query, 1-12 (default: current month)
  --from-file FILE  shape a previously saved JSON response instead of calling `gh api`
  --json-stdin      read the JSON response from stdin instead of calling `gh api`
  --self-test       shape scripts/fixtures/gh-billing-usage.json and assert its known total
  --help            this text

Live mode needs `gh auth status` with a classic PAT (or gh's own token) carrying the `user`
scope — fine-grained PATs are not supported by GitHub's billing-usage endpoint, so a 403 here
means re-auth with a classic scope, not "grant more to the fine-grained token". Not available
inside ai-jail; run from the host. --from-file/--json-stdin need neither `gh` nor network.
EOF
}

# $1 = raw usage JSON (the API response, or a fixture/file/stdin with the same shape) -> shaped JSON
shape() {
  jq "$SHAPE_FILTER" <<<"$1"
}

# $1 = shaped JSON from shape() -> the table + total on stdout
print_table() {
  local shaped="$1"
  if jq -e '.empty' <<<"$shaped" >/dev/null 2>&1; then
    echo "no usage recorded for this period yet"
    return 0
  fi
  jq -r '
    ["PRODUCT","SKU","QUANTITY","UNIT","GROSS_USD","DISCOUNT_USD","NET_USD"],
    (.rows[] | [.product, .sku, (.quantity|tostring), .unit,
                (.gross|(.*100|round/100)|tostring),
                (.discount|(.*100|round/100)|tostring),
                (.net|(.*100|round/100)|tostring)])
    | @tsv
  ' <<<"$shaped" | column -t -s $'\t'
  local total
  total="$(jq -r '(.total*100|round/100)' <<<"$shaped")"
  echo
  echo "total: \$${total} net (gross minus any plan-included allowance already applied — a \$0.00 row means the SKU is fully covered by the plan, not free by nature)"
  echo "keep GHCR storage down: docs/RUN-LOCALLY.md §10 (container images) — ghcr-retention.yml prunes old -<sha> tags weekly."
}

self_test() {
  local dir fixture raw shaped total expected
  dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  fixture="$dir/fixtures/gh-billing-usage.json"
  [ -f "$fixture" ] || { echo "gh-billing self-test: fixture not found: $fixture" >&2; exit 1; }
  raw="$(cat "$fixture")"
  shaped="$(shape "$raw")"
  print_table "$shaped"
  total="$(jq -r '(.total*100|round/100)' <<<"$shaped")"
  expected="4.75"
  if [ "$total" != "$expected" ]; then
    echo "gh-billing self-test: FAILED — total \$$total, expected \$$expected" >&2
    exit 1
  fi
  echo
  echo "gh-billing self-test: ok (total \$$total, $(jq '.rows | length' <<<"$shaped") product/SKU rows)"
}

year="$(date +%Y)"
month="$(date +%-m)"
from_file=""
json_stdin=0
while [ $# -gt 0 ]; do
  case "$1" in
    --year) year="$2"; shift 2 ;;
    --month) month="$2"; shift 2 ;;
    --from-file) from_file="$2"; shift 2 ;;
    --json-stdin) json_stdin=1; shift ;;
    --self-test) self_test; exit 0 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 1 ;;
  esac
done

if [ -n "$from_file" ]; then
  [ -f "$from_file" ] || { echo "gh-billing: file not found: $from_file" >&2; exit 1; }
  echo "GitHub billing usage — from $from_file"
  echo
  raw="$(cat "$from_file")"
elif [ "$json_stdin" -eq 1 ]; then
  echo "GitHub billing usage — from stdin"
  echo
  raw="$(cat -)"
else
  gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login (needs the 'user' scope; fine-grained PATs are not supported by this endpoint)" >&2; exit 1; }
  login="$(gh api user --jq .login)"
  echo "GitHub billing usage — $login, $year-$(printf '%02d' "$month")"
  echo

  err_file="$(mktemp)"
  trap 'rm -f "$err_file"' EXIT
  if ! raw="$(gh api "/users/$login/settings/billing/usage?year=$year&month=$month" 2>"$err_file")"; then
    echo "consolidated usage report failed (raw error below):" >&2
    cat "$err_file" >&2
    echo >&2
    if grep -qi '403\|resource not accessible\|must have\|forbidden' "$err_file"; then
      echo "likely a permission problem: this endpoint needs a classic PAT (or gh's own token) with the 'user' scope — fine-grained PATs are not supported here at all (https://docs.github.com/en/billing/tutorials/automate-usage-reporting). Try: gh auth refresh -s user" >&2
    elif grep -qi '404\|not found' "$err_file"; then
      echo "404 here can mean the account isn't on the enhanced billing platform, or — more likely a year on — that this was one of the per-product endpoints GitHub closed down 2025-09-26 (https://github.blog/changelog/2025-09-26-product-specific-billing-apis-are-closing-down/). There is no live fallback for those anymore: check https://github.com/settings/billing by hand." >&2
    fi
    exit 1
  fi
fi

shaped="$(shape "$raw")"
print_table "$shaped"
