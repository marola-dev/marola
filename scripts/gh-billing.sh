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

# Extracts (and confines parsing to) the block of `gh auth status` output belonging to $2 (a
# hostname, e.g. "github.com") when the text has host headers at all: gh prints one unindented
# hostname line per host, then one indented account block per login on that host (active account
# first — confirmed against cli/cli's own status_test.go "multiple accounts on a host" case), so a
# multi-host or multi-account status block can carry more than one "Token scopes:" line and the
# wrong one must not be picked when the account we care about (github.com, always, since the
# billing endpoint only lives there) isn't first. Falls back to the whole text unchanged when no
# such header is found — keeps this working on the old single-line/no-header fixtures below and
# on any text a caller hands in directly.
host_block() {   # $1 = gh-auth-status text, $2 = hostname
  local text="$1" host="$2" block
  block="$(awk -v host="$host" '
    $0 == host { grab=1; next }
    grab && $0 !~ /^[[:space:]]/ { grab=0 }
    grab { print }
  ' <<<"$text")"
  if [ -n "$block" ]; then
    printf '%s\n' "$block"
  else
    printf '%s\n' "$text"
  fi
}

# Extracts the scope list from a `gh auth status` "Token scopes: ..." line and tests for exact
# membership of $2. Handles gh 2.99's quoted, comma-separated format:
#   - Token scopes: 'gist', 'read:org', 'repo'
# and the scopeless case `Token scopes: none`. Matches the full scope name only (`user`), not a
# prefixed sub-scope like `read:user` — the API's own error asks for the parent scope, and
# `read:user` does not grant it.
#
# gh 2.99's `pkg/cmd/auth/status/status.go` (verified against
# https://github.com/cli/cli/blob/v2.99.0/pkg/cmd/auth/status/status.go and its status_test.go —
# both identical to the trunk branch as of this check) only prints the "Token scopes:" line at all
# when `expectScopes(token)` is true, i.e. the token has a `ghp_` or `gho_` prefix. For every other
# prefix — `github_pat_` (fine-grained PAT), `ghs_` (server-to-server/installation), `ghu_`, `ghr_`
# — the line is omitted entirely, not printed as "none". So "no Token scopes: line found" and
# "Token scopes: none" are two different, both-real states, and only the caller (has_user_scope)
# knows which failure message fits which.
scope_line() {   # $1 = gh-auth-status text for one host -> the raw scope list, "none", or "" if absent
  local text="$1" line
  line="$(grep -m1 'Token scopes:' <<<"$text" || true)"
  [ -n "$line" ] || return 0
  line="${line#*Token scopes:}"
  line="$(tr -d '\r' <<<"$line")"
  line="$(sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' <<<"$line")"
  if [[ "$line" =~ ^none$ ]]; then
    printf 'none\n'
    return 0
  fi
  tr ',' '\n' <<<"$line" | sed -E "s/^[[:space:]']+//; s/[[:space:]']+\$//" | paste -sd, - | sed 's/,/, /g'
}
has_scope() {   # $1 = gh-auth-status text (one line or the whole block), $2 = scope name
  local text="$1" want="$2" scopes
  scopes="$(scope_line "$text")"
  [ -n "$scopes" ] || return 1
  [ "$scopes" = "none" ] && return 1
  tr ',' '\n' <<<"$scopes" | sed -E "s/^[[:space:]']+//; s/[[:space:]']+\$//" | grep -qx "$want"
}
has_user_scope() { has_scope "$(host_block "$1" github.com)" user; }
# The scopes the parser actually found for github.com, for display in the failure message so a
# user can see what was read rather than take the verdict on faith: the raw list, "none", or ""
# when gh printed no "Token scopes:" line at all for that host (see scope_line's comment above).
found_scopes_for_message() { scope_line "$(host_block "$1" github.com)"; }

# $1 = gh-auth-status text (or empty) -> the diagnostic to print when the local scope check fails,
# already tailored to whether gh printed a scope list at all (a real "missing scope" a refresh can
# fix) or printed no Token-scopes line (a token type — fine-grained PAT, installation token — that
# this endpoint never accepts, no matter what `gh auth refresh` does to it).
missing_user_scope_message() {
  local auth_status="$1" found
  found="$(found_scopes_for_message "$auth_status")"
  if [ -n "$found" ]; then
    printf '%s\n' 'gh-billing: your gh token lacks the "user" scope this endpoint needs — run: gh auth refresh -h github.com -s user'
    printf '%s\n' "(gh auth status reports: $found)"
  else
    printf '%s\n' 'gh-billing: gh auth status printed no "Token scopes:" line for github.com at all — that means a token type this endpoint never accepts regardless of scope (a fine-grained PAT or an installation/server-to-server token; see https://docs.github.com/en/billing/tutorials/automate-usage-reporting). Re-authenticate with a classic PAT or gh'"'"'s own OAuth token: gh auth login, then gh auth refresh -h github.com -s user'
  fi
}

# GH_TOKEN/GITHUB_TOKEN in the environment override whatever `gh auth login` stored in the
# keyring — gh always prefers the env var (see status.go's authTokenWriteable: a tokenSource
# ending "_TOKEN" is the env-var case) — so `gh auth refresh` never touches it: refresh rewrites
# the keyring credential, which gh won't even look at while the env var is set. This repo's own
# `just jail-claude` (see the justfile's jail-claude recipe comment) exports GH_TOKEN as a
# fine-grained PAT scoped to this one repo, which api.github.com's billing-usage endpoint rejects
# outright (not a scope gap — fine-grained PATs are "explicitly NOT supported", per
# https://docs.github.com/en/billing/tutorials/automate-usage-reporting), and the same variable
# may also be exported from the maintainer's own .env on the host. Either way the fix is the same:
# run without it, on the keyring token, which does need the `user` scope.
env_token_message() {   # -> the message text, or "" if neither var is set
  local var=""
  if [ -n "${GH_TOKEN:-}" ]; then
    var="GH_TOKEN"
  elif [ -n "${GITHUB_TOKEN:-}" ]; then
    var="GITHUB_TOKEN"
  else
    return 0
  fi
  printf '%s\n' "gh-billing: $var is set (a fine-grained PAT cannot call the billing endpoint) — run this with the env var unset: \`env -u $var just gh-billing\`, using your \`gh auth login\` token, which needs the \`user\` scope (\`gh auth refresh -h github.com -s user\`)"
}

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
usage: gh-billing.sh [--year YYYY] [--month M] [--from-file FILE | --json-stdin] [--self-test] [--dry-run] [--no-scope-check] [--help]

  --year YYYY         year to query (default: current year)
  --month M           month to query, 1-12 (default: current month)
  --from-file FILE    shape a previously saved JSON response instead of calling `gh api`
  --json-stdin        read the JSON response from stdin instead of calling `gh api`
  --self-test         shape scripts/fixtures/gh-billing-usage.json and assert its known total,
                      plus the scope-parser and error-classification pure-function checks
  --dry-run           print the `gh api` call this would make and exit, no network/gh required
  --no-scope-check    skip the local `gh auth status` scope check and call the API directly —
                      use this when the parser and gh disagree (e.g. an unrecognized status
                      format); the API's own error is still classified on failure
  --help              this text

Live mode also prints "Actions minutes: X of Y included this month" for personal Free/Pro plans
(GitHub's own published allowances — not exposed by the billing-usage API itself, so hardcoded
and flagged if this repo's account plan isn't in the known table; github.com/settings/billing is
the live authority if this ever looks wrong).

Live mode needs `gh auth status` with a classic PAT (or gh's own token) carrying the `user`
scope — fine-grained PATs are not supported by GitHub's billing-usage endpoint. The scope is
checked locally from `gh auth status` before any live call (skip with --no-scope-check); a 404
mentioning the scope means re-auth with `gh auth refresh -h github.com -s user`, not "grant more
to the fine-grained token". If GH_TOKEN or GITHUB_TOKEN is set in the environment, gh uses that
token unconditionally and `gh auth refresh` cannot touch it — this is checked first, before any
`gh auth status` parsing, and always fails fast with instructions to unset it (--no-scope-check
does not bypass this: it is not a scope question, the token itself is a different credential).
Not available inside ai-jail; run from the host. --from-file/--json-stdin/--dry-run need neither
`gh` login nor network (--dry-run does try `gh api user` to show your real login, but falls back
to a placeholder if that fails).
EOF
}

# $1 = raw usage JSON (the API response, or a fixture/file/stdin with the same shape) -> shaped JSON
shape() {
  jq "$SHAPE_FILTER" <<<"$1"
}

# Included Actions minutes per month, personal-account plans only (this script only ever queries
# GET /users/{username}/... — organization plans like Team/Enterprise are a different endpoint,
# out of scope here). Not exposed by the billing-usage API itself (checked live, 2026-09-07: the
# usageItem schema has date/product/sku/quantity/unitType/pricePerUnit/grossAmount/discountAmount/
# netAmount/repositoryName — no allowance/quota field) — these are GitHub's own published numbers
# (docs.github.com/billing/managing-billing-for-github-actions/about-billing-for-github-actions,
# checked live 2026-09-07) and GitHub can change them; github.com/settings/billing is the
# authoritative live number if this ever looks wrong. Counted in Linux-minute equivalents — a
# macOS/Windows runner minute costs more against this same pool (2x/10x multipliers), so this
# quota line only means what it says when every SKU below is "Actions Linux".
declare -A ACTIONS_INCLUDED_MINUTES=( [free]=2000 [pro]=3000 )

# $1 = shaped JSON from shape() -> the table + total on stdout
# $2 = plan name (gh api user --jq .plan.name), optional — omit to skip the quota line entirely
# (the --self-test/--from-file/--json-stdin/--dry-run paths have no live plan to look up)
print_table() {
  local shaped="$1" plan="${2:-}"
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
  if [ -n "$plan" ]; then
    local included minutes_used pct
    included="${ACTIONS_INCLUDED_MINUTES[$plan]:-}"
    minutes_used="$(jq -r '[.rows[] | select(.product=="actions" and (.unit|ascii_downcase)=="minutes") | .quantity] | add // 0' <<<"$shaped")"
    if [ -n "$included" ]; then
      pct="$(awk -v u="$minutes_used" -v i="$included" 'BEGIN{printf "%.0f", (i>0 ? u/i*100 : 0)}')"
      echo "Actions minutes: ${minutes_used} of ${included} included this month (${pct}%, plan: $plan) — Linux-minute equivalents; github.com/settings/billing is the live authority"
    else
      echo "Actions minutes used: ${minutes_used} (plan '$plan' isn't in this script's known table — see github.com/settings/billing for your included allowance)"
    fi
  fi
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
  echo "-- scope-line parser (has_user_scope), verbatim gh 2.99 \`gh auth status\` samples --"
  echo "   (confirmed against https://github.com/cli/cli/blob/v2.99.0/pkg/cmd/auth/status/status.go"
  echo "    and its status_test.go, both identical to the trunk branch as of this check)"

  # Verbatim: cli/cli status_test.go "all good" case, github.com block (lines ~254-259 of that
  # file) — a keyring (oauth_token) classic token with a real scope list. 'user' appended here
  # since none of gh's own fixtures happen to carry it; everything else, including the exact
  # wording/indentation/quoting, is copied character-for-character.
  sample_with_user="$(cat <<'EOF'
github.com
  ✓ Logged in to github.com account monalisa (/home/m/.config/gh/hosts.yml)
  - Active account: true
  - Git operations protocol: https
  - Token: ghp_******
  - Token scopes: 'gist', 'read:org', 'repo', 'user'
EOF
)"
  # Verbatim: same test, github.com block, unmodified (no 'user' scope).
  sample_without_user="$(cat <<'EOF'
github.com
  ✓ Logged in to github.com account monalisa (GH_CONFIG_DIR/hosts.yml)
  - Active account: true
  - Git operations protocol: https
  - Token: gho_******
  - Token scopes: 'repo', 'read:org'
EOF
)"
  # Verbatim: same test, ghe.io block — the scopeless case, printed as "none" (not omitted; see
  # scope_line's comment on when gh omits the line entirely instead).
  sample_none="$(cat <<'EOF'
ghe.io
  ✓ Logged in to ghe.io account monalisa-ghe (GH_CONFIG_DIR/hosts.yml)
  - Active account: true
  - Git operations protocol: ssh
  - Token: gho_******
  - Token scopes: none
EOF
)"
  # Verbatim: cli/cli status_test.go "token from env" case — GH_TOKEN holding a classic gho_
  # token still gets a real "Token scopes:" line (expectScopes matches on token prefix, not on
  # tokenSource), just with an empty scope set from the mocked response.
  sample_env_token="$(cat <<'EOF'
github.com
  ✓ Logged in to github.com account monalisa (GH_TOKEN)
  - Active account: true
  - Git operations protocol: https
  - Token: gho_******
  - Token scopes: none
EOF
)"
  # Verbatim: cli/cli status_test.go "PAT V2 token" case — a fine-grained PAT (github_pat_
  # prefix) gets NO "Token scopes:" line at all: expectScopes() only matches ghp_/gho_, so gh
  # skips the line entirely rather than printing "none". This is the shape that used to be
  # silently misread as "missing user scope" instead of "wrong token type, no scope will fix it".
  sample_fine_grained="$(cat <<'EOF'
github.com
  ✓ Logged in to github.com account monalisa (GH_CONFIG_DIR/hosts.yml)
  - Active account: true
  - Git operations protocol: https
  - Token: github_pat_**********
EOF
)"
  # Verbatim: cli/cli status_test.go "multiple accounts on a host" case (lines ~386-397) — two
  # accounts on github.com, active account's block first. 'user' appended to the active
  # (monalisa-2) account's scopes to confirm the parser reads the FIRST (active) block on the
  # right host, not whichever "Token scopes:" line happens to match first in raw text.
  sample_multi_account="$(cat <<'EOF'
github.com
  ✓ Logged in to github.com account monalisa-2 (GH_CONFIG_DIR/hosts.yml)
  - Active account: true
  - Git operations protocol: https
  - Token: gho_******
  - Token scopes: 'repo', 'read:org', 'user'

  ✓ Logged in to github.com account monalisa (GH_CONFIG_DIR/hosts.yml)
  - Active account: false
  - Git operations protocol: https
  - Token: gho_******
  - Token scopes: 'repo', 'read:org', 'project:read'
EOF
)"

  if has_user_scope "$sample_with_user"; then
    echo "ok: keyring token with 'user' among several quoted scopes detected"
  else
    echo "FAILED: quoted 'user' scope not detected as present" >&2; failed=1
  fi
  if has_user_scope "$sample_without_user"; then
    echo "FAILED: absent 'user' scope falsely detected as present" >&2; failed=1
  else
    echo "ok: keyring token without 'user' scope detected as absent"
  fi
  if has_user_scope "$sample_none"; then
    echo "FAILED: 'Token scopes: none' falsely detected as having 'user'" >&2; failed=1
  else
    echo "ok: 'Token scopes: none' handled"
  fi
  if has_user_scope "  - Token scopes: 'read:user', 'repo'"; then
    echo "FAILED: sub-scope 'read:user' falsely matched the parent 'user' scope" >&2; failed=1
  else
    echo "ok: sub-scope 'read:user' does not falsely match the parent 'user' scope"
  fi
  if has_user_scope "$sample_env_token"; then
    echo "FAILED: GH_TOKEN layout with an empty scope set falsely detected as having 'user'" >&2; failed=1
  else
    echo "ok: GH_TOKEN layout (classic token, tokenSource in parens) parsed for its scope line"
  fi
  if has_user_scope "$sample_fine_grained"; then
    echo "FAILED: fine-grained PAT (no Token scopes: line at all) falsely detected as having 'user'" >&2; failed=1
  else
    echo "ok: fine-grained PAT layout (no Token scopes: line) treated as not having 'user'"
  fi
  got="$(found_scopes_for_message "$sample_fine_grained")"
  if [ -z "$got" ]; then
    echo "ok: found_scopes_for_message reports nothing found for the fine-grained-PAT layout"
  else
    echo "FAILED: expected no scopes found for the fine-grained-PAT layout, got: $got" >&2; failed=1
  fi
  if grep -q 'never accepts' <<<"$(missing_user_scope_message "$sample_fine_grained")"; then
    echo "ok: missing_user_scope_message distinguishes 'no scope line at all' from 'missing scope'"
  else
    echo "FAILED: expected the no-scope-line wording for the fine-grained-PAT layout" >&2; failed=1
  fi
  got="$(missing_user_scope_message "$sample_without_user")"
  if grep -q "gh auth status reports: repo, read:org" <<<"$got"; then
    echo "ok: missing_user_scope_message echoes back the scopes the parser actually found"
  else
    echo "FAILED: expected the found-scopes line, got: $got" >&2; failed=1
  fi
  if has_user_scope "$sample_multi_account"; then
    echo "ok: multi-account layout reads the active (first) github.com account's scopes"
  else
    echo "FAILED: multi-account layout should have found 'user' on the active account" >&2; failed=1
  fi

  echo
  echo "-- GH_TOKEN/GITHUB_TOKEN environment detection (env_token_message) --"
  got="$(GH_TOKEN=github_pat_x GITHUB_TOKEN='' env_token_message)"
  if grep -q 'GH_TOKEN is set' <<<"$got" && grep -q 'env -u GH_TOKEN just gh-billing' <<<"$got"; then
    echo "ok: GH_TOKEN set is detected and named in the fix command"
  else
    echo "FAILED: expected the GH_TOKEN message, got: $got" >&2; failed=1
  fi
  got="$(GH_TOKEN='' GITHUB_TOKEN=github_pat_y env_token_message)"
  if grep -q 'GITHUB_TOKEN is set' <<<"$got" && grep -q 'env -u GITHUB_TOKEN just gh-billing' <<<"$got"; then
    echo "ok: GITHUB_TOKEN set (GH_TOKEN unset) is detected and named in the fix command"
  else
    echo "FAILED: expected the GITHUB_TOKEN message, got: $got" >&2; failed=1
  fi
  got="$(GH_TOKEN='' GITHUB_TOKEN='' env_token_message)"
  if [ -z "$got" ]; then
    echo "ok: neither var set produces no message"
  else
    echo "FAILED: expected no message with neither var set, got: $got" >&2; failed=1
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
no_scope_check=0
plan=""
while [ $# -gt 0 ]; do
  case "$1" in
    --year) year="$2"; shift 2 ;;
    --month) month="$2"; shift 2 ;;
    --from-file) from_file="$2"; shift 2 ;;
    --json-stdin) json_stdin=1; shift ;;
    --self-test) self_test; exit 0 ;;
    --dry-run) dry_run=1; shift ;;
    --no-scope-check) no_scope_check=1; shift ;;
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
  env_msg="$(env_token_message)"
  if [ -n "$env_msg" ]; then
    echo "$env_msg" >&2
    exit 1
  fi

  auth_status="$(gh auth status 2>&1)" || { echo "gh is not logged in — run: gh auth login (needs the 'user' scope; fine-grained PATs are not supported by this endpoint)" >&2; exit 1; }
  if [ "$no_scope_check" -eq 0 ] && ! has_user_scope "$auth_status"; then
    missing_user_scope_message "$auth_status" >&2
    exit 1
  fi
  login="$(gh api user --jq .login)"
  plan="$(gh api user --jq '.plan.name // empty' 2>/dev/null || echo '')"
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
print_table "$shaped" "$plan"
