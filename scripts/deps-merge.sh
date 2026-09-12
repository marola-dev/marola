#!/usr/bin/env bash
# deps-merge — merge every open dependency-update PR whose checks are green, in one command.
#
#   just deps-merge                # merge what is ready, report what is not and why
#   just deps-merge --dry-run      # print the gh commands, mutate nothing
#   just deps-merge --from-json f  # judge this file instead of calling gh (testing)
#   just deps-merge --self-test    # scripts/fixtures/deps-merge-prs.json, assert the verdicts
#
# The counterpart to deps-stack.sh: that one chains open dependency PRs into a stack when they
# arrive separately, this one closes them out. A PR is merged only when GitHub says MERGEABLE and
# every check has finished green — SKIPPED and NEUTRAL count as green because ci.yml's path
# filters skip most jobs on a requirements-only change, and a skipped job is not a failed one.
# Everything else is reported and left alone; nothing here overrides a red check.
#
# Order is deps-stack's: github-actions first, then pip, each by PR number ascending, so a run
# that merges several leaves the smallest rebase for whatever is left.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dry=0
from_json=""
self_test=0

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry=1 ;;
    --from-json) from_json="${2:?--from-json needs a file}"; shift ;;
    --self-test) self_test=1 ;;
    -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "deps-merge: unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

# stdin: gh-pr-list-shaped JSON -> stdout: same, ordered, each with .ecosystem/.ready/.reason
judge() {
  jq '
    def eco:
      if (.headRefName | test("^dependabot/github_actions/")) then "github-actions"
      elif (.headRefName | test("^dependabot/pip/")) then "pip"
      elif (.headRefName | test("^update/")) then "scala-steward"
      else "other" end;
    def rank:
      if eco == "github-actions" then 0 elif eco == "pip" then 1
      elif eco == "scala-steward" then 2 else 3 end;
    def checks: (.statusCheckRollup // []);
    # A CheckRun reports status+conclusion, a StatusContext only state — read both shapes.
    def results: checks | map(.conclusion // .state // "PENDING" | ascii_upcase);
    def running: checks | map(select((.status // "COMPLETED") != "COMPLETED")) | length;
    # $r, not `.`: inside the pipe `.` is the literal array, so `index(.)` would test the array
    # against itself and never match — which is how the first version merged a red PR.
    def red: results | map(select(. as $r | ["SUCCESS", "SKIPPED", "NEUTRAL"] | index($r) | not));
    def verdict:
      if .mergeable != "MERGEABLE" then "not mergeable (\(.mergeable))"
      elif (checks | length) == 0 then "no checks reported"
      elif running > 0 then "checks still running"
      elif (red | length) > 0 then "checks not green (\(red | unique | join(", ")))"
      else "" end;
    map(. + {ecosystem: eco, rank: rank, reason: verdict})
    | map(. + {ready: (.reason == "")})
    | sort_by([.rank, .number])
  '
}

discover() {
  if [ -n "$from_json" ]; then
    cat "$from_json"
    return 0
  fi
  gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
  gh pr list --state open --search "author:app/dependabot" \
    --json number,title,headRefName,mergeable,statusCheckRollup
}

self_test() {
  local fixture="$script_dir/fixtures/deps-merge-prs.json"
  [ -f "$fixture" ] || { echo "deps-merge self-test: fixture not found: $fixture" >&2; exit 1; }
  local judged got fails=0
  judged="$(judge <"$fixture")"

  check() {   # label, got, want
    if [ "$2" = "$3" ]; then
      echo "  ok   $1"
    else
      fails=$((fails + 1))
      echo "  FAIL $1 — got $2, want $3" >&2
    fi
  }

  got="$(jq -c '[.[].number]' <<<"$judged")"
  check "github-actions first, then pip, each by number" "$got" "[301,303,302,304,305]"
  got="$(jq -c '[.[] | select(.ready) | .number]' <<<"$judged")"
  check "only the green, mergeable PRs are merged" "$got" "[301,302]"
  got="$(jq -r '.[] | select(.number == 303) | .reason' <<<"$judged")"
  check "a failed check is reported, not merged" "$got" "checks not green (FAILURE)"
  got="$(jq -r '.[] | select(.number == 304) | .reason' <<<"$judged")"
  check "a still-running check waits" "$got" "checks still running"
  got="$(jq -r '.[] | select(.number == 305) | .reason' <<<"$judged")"
  check "a conflicting PR is left for a human" "$got" "not mergeable (CONFLICTING)"
  got="$(jq -r '.[] | select(.number == 302) | .ready' <<<"$judged")"
  check "SKIPPED jobs count as green (ci.yml's path filters skip most)" "$got" "true"

  echo "deps-merge self-test:" "$([ "$fails" -eq 0 ] && echo ok || echo "$fails FAILED")"
  [ "$fails" -eq 0 ]
}

if [ "$self_test" -eq 1 ]; then self_test; exit $?; fi

judged="$(discover | judge)"
if [ "$(jq 'length' <<<"$judged")" -eq 0 ]; then
  echo "deps-merge: no open dependency-update PRs"
  exit 0
fi

jq -r '.[] | "  #\(.number) \(.ecosystem)  \(if .ready then "READY" else "skip — \(.reason)" end)\n         \(.title)"' <<<"$judged"

merged=0
failed=0
while read -r n; do
  [ -n "$n" ] || continue
  if [ "$dry" -eq 1 ]; then
    echo "+ gh pr merge $n --squash --delete-branch"
    continue
  fi
  echo "merging #$n ..."
  if gh pr merge "$n" --squash --delete-branch; then
    merged=$((merged + 1))
  else
    failed=$((failed + 1))
    echo "deps-merge: #$n did not merge — left open" >&2
  fi
done < <(jq -r '.[] | select(.ready) | .number' <<<"$judged")

[ "$dry" -eq 1 ] && { echo "dry run: nothing was merged"; exit 0; }
echo "deps-merge: merged $merged, left $(jq '[.[] | select(.ready | not)] | length' <<<"$judged") not ready, $failed failed"
[ "$failed" -eq 0 ]
