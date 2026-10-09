#!/usr/bin/env bash
# pointer-sync-merge — squash-merge pointer-sync.yml's rolling PR once it is green and in scope (#739).
#
#   scripts/pointer-sync-merge.sh --repo OWNER/NAME [--sha SHA] [--dry-run]
#       in a full clone of the default branch; --sha is the commit the triggering checks ran on
#   scripts/pointer-sync-merge.sh --self-test   # a fake gh on PATH and git fixtures, no network
#
# Merges only when every check run and status on the head finished success/skipped/neutral, the head
# is still --sha, and the diff is submodule gitlinks plus REPOS.md inside its wiring markers. Pending
# exits quietly for a later trigger; any other refusal leaves the PR for a person with one comment.
set -euo pipefail

BRANCH=chore/pointer-sync
REPOS_MD=docs/2-Building-marola/REPOS.md
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"

# outside_markers: stdin minus every line strictly between the wiring markers.
outside_markers() { awk '/<!-- wiring:end -->/ { inside = 0 } !inside { print } /<!-- wiring:start -->/ { inside = 1 }'; }

# scope_problem <base> <head>: why the diff is more than gitlinks and REPOS.md's wiring table, or nothing.
scope_problem() {
  local base="$1" head="$2" path
  local -A gitlink=()
  while read -r _ path; do gitlink[$path]=1; done \
    < <(git config --blob "$base:.gitmodules" --get-regexp '^submodule\..*\.path$' || true)
  local outside=()
  while IFS= read -r -d '' path; do
    if [ -n "${gitlink[$path]:-}" ]; then
      # A .gitmodules path that is no longer a gitlink (a file, or deleted) is not a pointer move.
      [ "$(git ls-tree "$head" -- "$path" | cut -d' ' -f1)" = 160000 ] || outside+=("$path")
    elif [ "$path" = "$REPOS_MD" ]; then
      if ! git cat-file -e "$base:$REPOS_MD" 2>/dev/null || ! git cat-file -e "$head:$REPOS_MD" 2>/dev/null ||
        ! cmp -s <(git show "$base:$REPOS_MD" | outside_markers) <(git show "$head:$REPOS_MD" | outside_markers); then
        echo "\`$REPOS_MD\` changes outside its \`wiring:start\`/\`wiring:end\` markers"
        return
      fi
    else
      outside+=("$path")
    fi
  done < <(git diff -z --no-renames --name-only "$base" "$head")
  [ "${#outside[@]}" -eq 0 ] || echo "it changes more than submodule pointers: $(printf "\`%s\` " "${outside[@]}")"
}

run() {
  local repo="$1" want="$2" dry="$3" n="" head=""
  # A fork's PR can carry the same branch name; only this repo's own branch is pointer-sync's.
  read -r n head < <(gh pr list -R "$repo" --head "$BRANCH" --base main --state open \
    --json number,headRefOid,isCrossRepository |
    jq -r '[.[] | select(.isCrossRepository | not)][0] // empty | "\(.number) \(.headRefOid)"') || true
  if [ -z "$n" ]; then
    echo "pointer-sync-merge: no open $BRANCH PR"
    return 0
  fi

  refuse() { # <why> [<what next>]
    local body="Not auto-merged at \`${head:0:7}\`: $1. ${2:-Left for a person} (#739)."
    echo "::warning::#$n $body"
    if gh pr view "$n" -R "$repo" --json comments | jq -e --arg b "$body" 'any(.comments[]; .body == $b)' >/dev/null; then
      echo "pointer-sync-merge: #$n already says so"
    elif [ "$dry" -eq 1 ]; then
      echo "+ gh pr comment $n -R $repo --body '$body'"
    else
      gh pr comment "$n" -R "$repo" --body "$body"
    fi
  }

  if [ -n "$want" ] && [ "$want" != "$head" ]; then
    refuse "the checks ran on \`${want:0:7}\`, but the head has moved" "The new head's checks retry it"
    return 0
  fi

  # check-runs is filter=latest by default, so a re-run replaces its failed attempt. The combined
  # status's own .state reads "pending" when there are no statuses at all, so only .statuses counts.
  local checks pending failed
  checks="$(
    gh api --paginate "repos/$repo/commits/$head/check-runs?per_page=100" |
      jq -r '.check_runs[] | [.name, .status, (.conclusion // "")] | @tsv'
    gh api --paginate "repos/$repo/commits/$head/status?per_page=100" |
      jq -r '.statuses[] | [.context, (if .state == "pending" then "pending" else "completed" end), .state] | @tsv'
  )"
  failed="$(awk -F'\t' '$2 == "completed" && $3 !~ /^(success|skipped|neutral)$/ { printf "%s`%s` (%s)", sep, $1, $3; sep = ", " }' <<<"$checks")"
  pending="$(awk -F'\t' '$2 != "completed" { printf "%s%s", sep, $1; sep = ", " }' <<<"$checks")"
  if [ -n "$failed" ]; then
    refuse "not every check passed: $failed"
    return 0
  fi
  if [ -z "$checks" ] || [ -n "$pending" ]; then
    echo "::notice::#$n waits for ${pending:-its checks to start}; a later run retries"
    return 0
  fi

  git fetch -q --no-tags --no-recurse-submodules origin \
    "+refs/heads/main:refs/remotes/origin/main" "+refs/pull/$n/head:refs/remotes/origin/pull/$n"
  if [ "$(git rev-parse "refs/remotes/origin/pull/$n")" != "$head" ]; then
    refuse "the head moved while this run read it" "The new head's checks retry it"
    return 0
  fi
  local why
  why="$(scope_problem "$(git merge-base refs/remotes/origin/main "$head")" "$head")"
  if [ -n "$why" ]; then
    refuse "$why"
    return 0
  fi

  # --admin only skips gh's client-side refusal of a BLOCKED PR; GitHub still decides, and lets this
  # App through as main-rule's pull-request-only bypass actor. --match-head-commit aborts the merge
  # if anything was pushed since the checks ran.
  local merge=(gh pr merge "$n" --squash --admin --match-head-commit "$head" -R "$repo")
  if [ "$dry" -eq 1 ]; then
    echo "+ ${merge[*]}"
  else
    "${merge[@]}"
    echo "pointer-sync-merge: merged #$n at ${head:0:7}"
  fi
}

self_test() {
  # A pre-push hook exports GIT_DIR (and a worktree, GIT_COMMON_DIR); the fixtures below would
  # otherwise commit into the caller's own repo.
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX GIT_COMMON_DIR
  local t fails=0
  t="$(mktemp -d)"
  trap 'rm -rf "$t"' RETURN
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails + 1)); fi; }
  printf '[user]\n\tname = t\n\temail = t@t\n' >"$t/gitconfig"
  export GIT_CONFIG_GLOBAL="$t/gitconfig" GIT_CONFIG_NOSYSTEM=1

  mkdir -p "$t/bin" "$t/gh"
  cat >"$t/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
d="$FAKE_GH"
case "$*" in
  "pr list "*) cat "$d/prs.json" ;;
  "pr view "*) cat "$d/comments.json" ;;
  "pr comment "*) printf '%s' "${*: -1}" >"$d/last-comment"; echo "comment $3" >>"$d/calls" ;;
  "pr merge "*) echo "merge ${*:3}" >>"$d/calls" ;;
  "api "*/check-runs*) cat "$d/check-runs.json" ;;
  "api "*/status*) cat "$d/status.json" ;;
  *) echo "fake gh: unexpected: $*" >&2; exit 1 ;;
esac
EOF
  chmod +x "$t/bin/gh"
  export PATH="$t/bin:$PATH" FAKE_GH="$t/gh"

  local o="$t/origin" w="$t/work"
  git init -q -b main "$o"
  (
    cd "$o"
    printf '[submodule "app"]\n\tpath = app\n\turl = https://github.com/x/app.git\n' >.gitmodules
    mkdir -p "$(dirname "$REPOS_MD")"
    printf 'intro\n<!-- wiring:start -->\n| a | b |\n<!-- wiring:end -->\noutro\n' >"$REPOS_MD"
    echo readme >README.md
    git add -A
    # An empty dir is an unpopulated submodule; without it, `commit -a` would delete the gitlink.
    mkdir app
    git update-index --add --cacheinfo "160000,$(printf '1%.0s' {1..40}),app"
    git commit -q -m base
  )
  # mkhead <name> <edit...>: a commit off main moving app's gitlink, plus the edit.
  mkhead() {
    local name="$1"; shift
    git -C "$o" checkout -q -b "$name" main
    git -C "$o" update-index --cacheinfo "160000,$(printf '2%.0s' {1..40}),app"
    (cd "$o" && "$@")
    git -C "$o" commit -qam "$name"
    git -C "$o" rev-parse HEAD
    git -C "$o" checkout -q main
  }
  local h_ok h_out h_md
  h_ok="$(mkhead ok sed -i 's/| a | b |/| a | c |/' "$REPOS_MD")"
  h_out="$(mkhead out sh -c 'echo more >>README.md')"
  h_md="$(mkhead md sed -i 's/outro/outro, edited/' "$REPOS_MD")"
  git clone -q "$o" "$w"

  # pr <head> <check-runs json>: open PR #7 at <head>, no statuses, no comments.
  pr() {
    git -C "$o" update-ref refs/pull/7/head "$1"
    printf '[{"number":7,"headRefOid":"%s","isCrossRepository":false}]' "$1" >"$t/gh/prs.json"
    printf '%s' "$2" >"$t/gh/check-runs.json"
    # Zero statuses still reads "pending", as GitHub's combined status does.
    printf '{"state":"pending","statuses":[]}' >"$t/gh/status.json"
    printf '{"comments":[]}' >"$t/gh/comments.json"
  }
  # go [args...]: the script in $w; calls() is what it did to the fake gh, plus a non-zero exit.
  go() {
    : >"$t/gh/calls"
    rm -f "$t/gh/last-comment"
    (cd "$w" && "$SELF" --repo marola-dev/marola "$@") >"$t/out" 2>&1 || echo "exit $?" >>"$t/gh/calls"
  }
  calls() { cat "$t/gh/calls"; }
  local green failed
  green='{"check_runs":[{"name":"changes","status":"completed","conclusion":"success"},{"name":"python-ci","status":"completed","conclusion":"skipped"},{"name":"gemini","status":"completed","conclusion":"neutral"}]}'
  failed='{"check_runs":[{"name":"changes","status":"completed","conclusion":"success"},{"name":"docs-build","status":"completed","conclusion":"failure"}]}'

  echo "-- merges_green_in_scope --"
  pr "$h_ok" "$green"
  go --sha "$h_ok"
  ok "$(calls)" "merge 7 --squash --admin --match-head-commit $h_ok -R marola-dev/marola" "squash-merges #7 pinned to the head"
  go --sha "$h_ok" --dry-run
  ok "$(calls)" "" "--dry-run calls nothing"
  ok "$(grep -c "merge 7 --squash" "$t/out")" "1" "--dry-run prints the merge command"

  echo "-- refuses_pending_check --"
  pr "$h_ok" '{"check_runs":[{"name":"changes","status":"completed","conclusion":"success"},{"name":"docs-build","status":"in_progress","conclusion":null}]}'
  go --sha "$h_ok"
  ok "$(calls)" "" "no merge, no comment"

  echo "-- refuses_failed_check --"
  pr "$h_ok" "$failed"
  go --sha "$h_ok"
  ok "$(calls)" "comment 7" "one comment, no merge"
  ok "$(grep -c docs-build "$t/gh/last-comment" 2>/dev/null)" "1" "the comment names the failed check"
  [ ! -f "$t/gh/last-comment" ] || jq -n --rawfile b "$t/gh/last-comment" '{comments: [{body: $b}]}' >"$t/gh/comments.json"
  go --sha "$h_ok"
  ok "$(calls)" "" "the same refusal is not commented twice"
  pr "$h_ok" "$green"
  printf '{"state":"failure","statuses":[{"context":"ext/lint","state":"failure"}]}' >"$t/gh/status.json"
  go
  ok "$(calls)" "comment 7" "a failed commit status refuses too"

  echo "-- refuses_moved_head --"
  pr "$h_ok" "$green"
  go --sha "$h_out"
  ok "$(calls)" "comment 7" "one comment, no merge"

  echo "-- refuses_file_outside_scope --"
  pr "$h_out" "$green"
  go --sha "$h_out"
  ok "$(calls)" "comment 7" "one comment, no merge"
  ok "$(grep -c README.md "$t/gh/last-comment" 2>/dev/null)" "1" "the comment names the file"

  echo "-- refuses_repos_md_outside_markers --"
  pr "$h_md" "$green"
  go --sha "$h_md"
  ok "$(calls)" "comment 7" "one comment, no merge"
  ok "$(grep -c wiring:start "$t/gh/last-comment" 2>/dev/null)" "1" "the comment names the markers"

  echo "-- no_open_pr_is_quiet --"
  printf '[]' >"$t/gh/prs.json"
  go
  ok "$(calls)" "" "nothing called, exit 0"

  echo "pointer-sync-merge self-test:" "$([ "$fails" -eq 0 ] && echo ok || echo "FAILED ($fails)")"
  [ "$fails" -eq 0 ]
}

usage() { echo "usage: $0 --repo OWNER/NAME [--sha SHA] [--dry-run] | --self-test" >&2; exit 2; }

repo="" want="" dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --self-test) self_test; exit $? ;;
    --repo) repo="${2:-}"; shift ;;
    --sha) want="${2:-}"; shift ;;
    --dry-run) dry=1 ;;
    *) usage ;;
  esac
  shift
done
[ -n "$repo" ] || usage
run "$repo" "$want" "$dry"
