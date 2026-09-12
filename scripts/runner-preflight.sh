#!/usr/bin/env bash
# runner-preflight — check this machine can actually run marola's workflows, before the runner
# starts taking jobs. `just runner-up` runs it and then execs the runner's own run.sh, so a
# misconfiguration is a line on your terminal at startup instead of a red job an hour later.
#
#   just runner-up                   # preflight, then start the runner
#   just runner-preflight            # the checks on their own
#   scripts/runner-preflight.sh --self-test
#
# FAIL means a workflow on this runner will break; warn means one path of one workflow will.
# Only FAIL is fatal — a warning must never stop the runner from starting, because most of what
# it warns about only bites the job that needs it.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="${MAROLA_REPO:-h0ffmann/marola}"
self_test=0
[ "${1:-}" = "--self-test" ] && self_test=1

fails=0
warns=0
fail() { printf '  FAIL  %s\n' "$*" >&2; fails=$((fails + 1)); }
warn() { printf '  warn  %s\n' "$*"; warns=$((warns + 1)); }
good() { printf '  ok    %s\n' "$*"; }

# --- pure verdicts (what --self-test covers) ---------------------------------------------------

# The create-PR endpoint, probed with a head branch that cannot exist: a token allowed to open PRs
# is refused by validation (422), a blocked one never gets that far (403). Verified against the
# live API on 2026-09-12 — 422 body is {"message":"Validation Failed", field "head" invalid}.
pr_create_verdict() {
  case "$1" in
    422 | 201) echo allowed ;;
    403) echo blocked ;;
    401) echo unauthorized ;;
    *) echo unknown ;;
  esac
}

disk_verdict() {   # free_gb min_gb -> ok | low
  awk -v f="$1" -v m="$2" 'BEGIN { print (f + 0 < m + 0) ? "low" : "ok" }'
}

# --- checks ------------------------------------------------------------------------------------

check_tools() {
  # ci.yml resolves shellcheck/pyflakes/cloc/coverage through `nix build` on this runner, runs
  # every self-test with python3, lints site/static/app.js with node, and deps-merge needs jq.
  local t
  for t in nix python3 node jq curl; do
    if command -v "$t" >/dev/null; then good "$t"; else fail "$t is not on the runner's PATH"; fi
  done
  if docker info >/dev/null 2>&1; then
    good "docker (the Dockerfile and compose steps can run)"
  else
    warn "no usable docker — ci.yml's hadolint and 'docker compose config' steps will fail"
  fi
  if sudo -n true 2>/dev/null; then
    warn "passwordless sudo is available — nothing here should need it; a workflow that calls sudo is a bug, not a feature"
  else
    good "no passwordless sudo (correct — a workflow that calls it hangs until the job times out)"
  fi
}

token() {   # the host's token, by the same rule jail-claude uses
  if [ -n "${GH_TOKEN:-}" ]; then printf '%s' "$GH_TOKEN"; return 0; fi
  "$script_dir/gh-token.sh" 2>/dev/null || true
}

check_pr_creation() {
  local tok status body verdict
  tok="$(token)"
  if [ -z "$tok" ]; then
    warn "no GitHub token on the host (GH_TOKEN or gh auth login) — cannot check whether scala-steward may open PRs"
    return 0
  fi
  body="$(mktemp)"
  status="$(curl -sS -o "$body" -w '%{http_code}' -X POST \
    -H "Authorization: Bearer $tok" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$repo/pulls" \
    -d '{"title":"runner preflight","head":"marola-runner-preflight-never-exists","base":"main"}' 2>/dev/null || echo 000)"
  verdict="$(pr_create_verdict "$status")"
  rm -f "$body"
  case "$verdict" in
    allowed) good "this host's token may open PRs (scala-steward's own token is separate — see below)" ;;
    unauthorized) warn "the host token is not valid for $repo (401)" ;;
    blocked) warn "this host's token may not open PRs (403)" ;;
    *) warn "PR-creation probe returned $status — inconclusive" ;;
  esac

  # The failure this check exists for: scala-steward.yml falls back to GITHUB_TOKEN, and GITHUB_TOKEN
  # cannot open a PR while the repo forbids it. Read the setting rather than infer it.
  local perms secrets
  perms="$(gh api "repos/$repo/actions/permissions/workflow" 2>/dev/null || true)"
  secrets="$(gh api "repos/$repo/actions/secrets" --jq '[.secrets[].name] | join(" ")' 2>/dev/null || true)"
  if [ -z "$perms" ]; then
    warn "could not read $repo's Actions workflow permissions (token lacks admin) — skipping the scala-steward check"
    return 0
  fi
  local can_create
  can_create="$(printf '%s' "$perms" | python3 -c 'import json,sys; print(json.load(sys.stdin)["can_approve_pull_request_reviews"])' 2>/dev/null || echo False)"
  case " $secrets " in
    *" STEWARD_GH_TOKEN "*) good "STEWARD_GH_TOKEN is set — scala-steward opens PRs as that PAT" ; return 0 ;;
  esac
  if [ "$can_create" = "True" ]; then
    warn "scala-steward will use GITHUB_TOKEN: allowed to open PRs, but PRs it opens trigger no workflows, so they arrive with no CI and 'just deps-merge' will skip them"
  else
    # A warning, not a failure, even though scala-steward fails 100% of the time without it: this
    # runner also serves CI, the publish job and the site build, and none of those care.
    warn "scala-steward cannot open PRs: no STEWARD_GH_TOKEN secret, and $repo forbids Actions from creating them.
        Fix A (recommended): a fine-grained PAT on $repo with Contents: read/write and Pull
          requests: read/write, saved as the STEWARD_GH_TOKEN secret —
          gh secret set STEWARD_GH_TOKEN --repo $repo
        Fix B: gh api --method PUT repos/$repo/actions/permissions/workflow \\
          -F default_workflow_permissions=write -F can_approve_pull_request_reviews=true
          (works, but those PRs run no workflows)"
  fi
}

check_runner_labels() {
  local labels
  labels="$(gh api "repos/$repo/actions/runners" --jq '[.runners[].labels[].name] | join(" ")' 2>/dev/null || true)"
  [ -n "$labels" ] || { warn "could not list $repo's self-hosted runners"; return 0; }
  case " $labels " in
    *" dependabot "*) good "a runner carries the 'dependabot' label" ;;
    *) warn "no runner carries the 'dependabot' label — harmless until 'Dependabot on self-hosted runners' is enabled, at which point update jobs queue forever" ;;
  esac
}

check_disk() {
  local work free
  work="${MAROLA_GHA_RUNNER_DIR:-/home/hoffmann/code/actions-runner}/_work"
  [ -d "$work" ] || work="$HOME"
  # statvfs on the directory itself, not `df` on a parent: finetune/README.md records the run where
  # `df /home` said 95 GB and statvfs on the repo said 7 TB, which is the whole answer here.
  free="$(python3 -c 'import os,sys; s=os.statvfs(sys.argv[1]); print(int(s.f_bavail*s.f_frsize/1e9))' "$work" 2>/dev/null || true)"
  [ -n "$free" ] || { warn "could not measure free disk on $work"; return 0; }
  if [ "$(disk_verdict "$free" 20)" = low ]; then
    fail "only ${free}G free on $work — a build-test run plus sbt/coursier caches will not fit"
  else
    good "${free}G free on $work"
  fi
}

self_test() {
  local f=0
  t() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 — got $2, want $3" >&2; f=$((f + 1)); fi; }
  t "422 means the token may open PRs (only the fake branch was refused)" "$(pr_create_verdict 422)" allowed
  t "403 is the blocked case this check exists for" "$(pr_create_verdict 403)" blocked
  t "401 is a bad token, not a policy" "$(pr_create_verdict 401)" unauthorized
  t "anything else is inconclusive, never fatal" "$(pr_create_verdict 500)" unknown
  t "a full disk is caught" "$(disk_verdict 3 20)" low
  t "a roomy one is not" "$(disk_verdict 900 20)" ok
  t "the boundary is not low" "$(disk_verdict 20 20)" ok
  echo "runner-preflight self-test:" "$([ "$f" -eq 0 ] && echo ok || echo "$f FAILED")"
  [ "$f" -eq 0 ]
}

if [ "$self_test" -eq 1 ]; then self_test; exit $?; fi

echo "runner-preflight: $repo"
check_tools
check_pr_creation
check_runner_labels
check_disk
echo "runner-preflight: $fails failing, $warns warning(s)"
[ "$fails" -eq 0 ]
