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
#   scripts/gh-billing.sh --dry-run              # print the `gh api` call, no network
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

# Both API facts below (path + the `user` scope requirement) are asserted directly by GitHub:
# the path from https://docs.github.com/en/rest/billing/usage?apiVersion=2022-11-28
# ("Get billing usage report for a user" — GET /users/{username}/settings/billing/usage,
# classic PATs only — https://docs.github.com/en/billing/tutorials/automate-usage-reporting:
# "The billing usage endpoints do not support fine-grained personal access tokens"), and the
# `user` scope from gh's own error text on a token that lacks it: `gh: This API operation needs
# the "user" scope.` GitHub returns HTTP 404 (not 403) for that case on this endpoint — a missing
# scope on a personal-account-scoped resource looks like "not found", not "forbidden" — so the
# scope-check below runs before any live call, and the error classifier still recognizes the
# phrase in case a call is made anyway (e.g. `gh auth status` parsing failed).

# Extracts the scope list from a `gh auth status` "Token scopes: ..." line and tests for exact
# membership of $2. Handles gh 2.99's quoted, comma-separated format:
#   - Token scopes: 'gist', 'read:org', 'repo'
# and the scopeless case `Token scopes: none`. Matches the full scope name only (`user`), not a
# prefixed sub-scope like `read:user` — the API's own error asks for the parent scope, and
# `read:user` does not grant it.
has_scope() {   # $1 = gh-auth-status text (one line or the whole block), $2 = scope name
  local text="$1" want="$2" line
  line="$(grep -m1 'Token scopes:' <<<"$text" || true)"
  [ -n "$line" ] || return 1
  line="${line#*Token scopes:}"
  line="$(tr -d '\r' <<<"$line")"
  [[ "$line" =~ ^[[:space:]]*none[[:space:]]*$ ]] && return 1
  tr ',' '\n' <<<"$line" | sed -E "s/^[[:space:]']+//; s/[[:space:]']+\$//" | grep -qx "$want"
}
has_user_scope() { has_scope "$1" user; }

# $1 = raw stderr text from a failed `gh api` call -> the one diagnostic paragraph to print, or
# nothing when the error doesn't match a known shape. Scope message wins over the generic 404
# paragraph (checked first) since GitHub returns 404 for both "endpoint closed" and "missing
# scope" and only the error text tells them apart.
classify_error() {
  local err="$1"
  if grep -qi 'needs the "user" scope' <<<"$err"; then
    printf '%s\n' 'gh-billing: your gh token lacks the "user" scope this endpoint needs — run: gh auth refresh -h github.com -s user'
  elif grep -qi '403\|resource not accessible\|must have\|forbidden' <<<"$err"; then
    printf '%s\n' "likely a permission problem: this endpoint needs a classic PAT (or gh's own token) with the 'user' scope — fine-grained PATs are not supported here at all (https://docs.github.com/en/billing/tutorials/automate-usage-reporting). Try: gh auth refresh -s user"
  elif grep -qi '404\|not found' <<<"$err"; then
    printf '%s\n' "404 here can mean the account isn't on the enhanced billing platform, or — more likely a year on — that this was one of the per-product endpoints GitHub closed down 2025-09-26 (https://github.blog/changelog/2025-09-26-product-specific-billing-apis-are-closing-down/). There is no live fallback for those anymore: check https://github.com/settings/billing by hand."
  fi
}

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
usage: gh-billing.sh [--year YYYY] [--month M] [--from-file FILE | --json-stdin] [--self-test] [--dry-run] [--help]

  --year YYYY       year to query (default: current year)
  --month M         month to query, 1-12 (default: current month)
  --from-file FILE  shape a previously saved JSON response instead of calling `gh api`
  --json-stdin      read the JSON response from stdin instead of calling `gh api`
  --self-test       shape scripts/fixtures/gh-billing-usage.json and assert its known total,
                    plus the scope-parser and error-classification pure-function checks
  --dry-run         print the `gh api` call this would make and exit, no network/gh required
  --help            this text

Live mode needs `gh auth status` with a classic PAT (or gh's own token) carrying the `user`
scope — fine-grained PATs are not supported by GitHub's billing-usage endpoint. The scope is
checked locally from `gh auth status` before any live call; a 404 mentioning the scope means
re-auth with `gh auth refresh -h github.com -s user`, not "grant more to the fine-grained token".
Not available inside ai-jail; run from the host. --from-file/--json-stdin/--dry-run need neither
`gh` login nor network (--dry-run does try `gh api user` to show your real login, but falls back
to a placeholder if that fails).
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
  local dir fixture raw shaped total expected failed=0 got scope_msg

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
    failed=1
  fi

  echo
  echo "-- scope-line parser (has_user_scope) --"
  if has_user_scope "  - Token scopes: 'gist', 'read:org', 'repo', 'user'"; then
    echo "ok: quoted 'user' among several scopes detected"
  else
    echo "FAILED: quoted 'user' scope not detected as present" >&2; failed=1
  fi
  if has_user_scope "  - Token scopes: 'gist', 'read:org', 'repo'"; then
    echo "FAILED: absent 'user' scope falsely detected as present" >&2; failed=1
  else
    echo "ok: absent 'user' scope detected as absent"
  fi
  if has_user_scope "  - Token scopes: none"; then
    echo "FAILED: 'Token scopes: none' falsely detected as having 'user'" >&2; failed=1
  else
    echo "ok: 'Token scopes: none' handled"
  fi
  if has_user_scope "  - Token scopes: 'read:user', 'repo'"; then
    echo "FAILED: sub-scope 'read:user' falsely matched the parent 'user' scope" >&2; failed=1
  else
    echo "ok: sub-scope 'read:user' does not falsely match the parent 'user' scope"
  fi

  echo
  echo "-- error classification (classify_error) --"
  scope_msg='gh-billing: your gh token lacks the "user" scope this endpoint needs — run: gh auth refresh -h github.com -s user'
  got="$(classify_error 'gh: Not Found (HTTP 404)
gh: This API operation needs the "user" scope. To request it, run:  gh auth refresh -h github.com -s user')"
  if [ "$got" = "$scope_msg" ]; then
    echo "ok: a 404 whose text names the missing scope is classified as the scope fix"
  else
    echo "FAILED: expected the scope-fix message, got: $got" >&2; failed=1
  fi
  got="$(classify_error 'gh: Not Found (HTTP 404)')"
  if grep -q 'closed down 2025-09-26' <<<"$got"; then
    echo "ok: a plain 404 (no scope mention) is classified as the endpoint-closed paragraph"
  else
    echo "FAILED: expected the endpoint-closed paragraph, got: $got" >&2; failed=1
  fi
  got="$(classify_error 'HTTP 403: Resource not accessible by integration')"
  if grep -q 'permission problem' <<<"$got"; then
    echo "ok: a 403 is classified as the permission-problem message"
  else
    echo "FAILED: expected the permission-problem message, got: $got" >&2; failed=1
  fi

  echo
  if [ "$failed" -eq 1 ]; then
    echo "gh-billing self-test: FAILED" >&2
    exit 1
  fi
  echo "gh-billing self-test: ok (total \$$total, $(jq '.rows | length' <<<"$shaped") product/SKU rows; scope-parser and error-classification checks passed)"
}

year="$(date +%Y)"
month="$(date +%-m)"
from_file=""
json_stdin=0
dry_run=0
while [ $# -gt 0 ]; do
  case "$1" in
    --year) year="$2"; shift 2 ;;
    --month) month="$2"; shift 2 ;;
    --from-file) from_file="$2"; shift 2 ;;
    --json-stdin) json_stdin=1; shift ;;
    --self-test) self_test; exit 0 ;;
    --dry-run) dry_run=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 1 ;;
  esac
done

if [ "$dry_run" -eq 1 ]; then
  # No login required: falls back to a placeholder login if `gh api user` fails (not logged in,
  # no network) so the call shape can still be eyeballed.
  dry_login="$(gh api user --jq .login 2>/dev/null || echo '{username}')"
  echo "gh-billing --dry-run: would call:"
  echo "  gh api \"/users/$dry_login/settings/billing/usage?year=$year&month=$month\""
  exit 0
fi

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
  auth_status="$(gh auth status 2>&1)" || { echo "gh is not logged in — run: gh auth login (needs the 'user' scope; fine-grained PATs are not supported by this endpoint)" >&2; exit 1; }
  if ! has_user_scope "$auth_status"; then
    echo 'gh-billing: your gh token lacks the "user" scope this endpoint needs — run: gh auth refresh -h github.com -s user' >&2
    exit 1
  fi
  login="$(gh api user --jq .login)"
  echo "GitHub billing usage — $login, $year-$(printf '%02d' "$month")"
  echo

  err_file="$(mktemp)"
  trap 'rm -f "$err_file"' EXIT
  if ! raw="$(gh api "/users/$login/settings/billing/usage?year=$year&month=$month" 2>"$err_file")"; then
    echo "consolidated usage report failed (raw error below):" >&2
    cat "$err_file" >&2
    echo >&2
    diagnosis="$(classify_error "$(cat "$err_file")")"
    [ -n "$diagnosis" ] && echo "$diagnosis" >&2
    exit 1
  fi
fi

shaped="$(shape "$raw")"
print_table "$shaped"
