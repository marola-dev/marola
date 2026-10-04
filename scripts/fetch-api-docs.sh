#!/usr/bin/env bash
# fetch-api-docs — unpack a repo's `api-docs` branch, the generated API docs its CI force-pushes
# (MIP-0074 §5.2), into a directory. scripts/prepare-docs.sh calls it for every mounted repo, with
# <mount>/api-docs/. The repos are public, so no token; a repo without the branch is a notice.
#
#   scripts/fetch-api-docs.sh REPO DEST   # e.g. marola-app .tmp/docs-aggregated/5-Repos/marola-app/api-docs
#   scripts/fetch-api-docs.sh --self-test # against local bare repos; no network
#
# API_DOCS_REMOTE=<url template> overrides the remote; <repo> is replaced with the repo name.
set -euo pipefail

API_DOCS_BRANCH="api-docs"
API_DOCS_REMOTE="${API_DOCS_REMOTE:-https://github.com/marola-dev/<repo>}"

# A subshell: a hook's GIT_DIR would point every git call at the caller's repo, and a missing or
# private repo must not stop a local build at a credential prompt.
fetch_branch_one() (
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX GIT_COMMON_DIR
  export GIT_TERMINAL_PROMPT=0
  local repo="$1" dest="$2" url tmp
  url="${API_DOCS_REMOTE//<repo>/$repo}"
  tmp="$(mktemp -d)"
  if git init -q "$tmp" && git -C "$tmp" fetch --depth 1 -q "$url" "$API_DOCS_BRANCH" >/dev/null 2>&1; then
    mkdir -p "$dest"
    git -C "$tmp" archive FETCH_HEAD | tar -x -C "$dest"
    # The subject is api-docs-push's `api-docs: <source sha>`.
    echo "fetch-api-docs: $repo -> $dest ($(git -C "$tmp" log -1 --format=%s FETCH_HEAD))"
  else
    echo "fetch-api-docs: $repo has no $API_DOCS_BRANCH branch yet — skipping"
  fi
  rm -rf "$tmp"
)

self_test() {
  # A pre-push hook exports GIT_DIR (and a worktree, GIT_COMMON_DIR); the fixtures below would
  # otherwise commit into the caller's own repo.
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX GIT_COMMON_DIR
  export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
  local fails=0 t out rc
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails + 1)); fi; }

  local remote; remote="$(mktemp -d)"
  git init -q --bare "$remote/with-branch.git"
  git init -q --bare "$remote/no-branch.git"
  local seed; seed="$(mktemp -d)"
  git init -q "$seed"
  git -C "$seed" checkout -q --orphan api-docs
  mkdir -p "$seed/scala"
  echo hi >"$seed/scala/index.html"
  git -C "$seed" add -A
  git -C "$seed" -c user.email=t@t -c user.name=t commit -q -m "api-docs: 1234567"
  git -C "$seed" push -q "$remote/with-branch.git" api-docs
  rm -rf "$seed"

  echo "-- the api-docs branch (MIP-0074 §5.3): present is unpacked, absent is a notice --"
  t="$(mktemp -d)"
  out="$(API_DOCS_REMOTE="$remote/<repo>.git" fetch_branch_one "with-branch" "$t/dest")"
  ok "$([ -f "$t/dest/scala/index.html" ] && echo yes || echo no)" "yes" "the branch tip is unpacked into the given dir"
  ok "$(grep -c '(api-docs: 1234567)' <<<"$out")" "1" "...and the notice names the commit it came from"
  rm -rf "$t"

  t="$(mktemp -d)"
  rc=0
  out="$(API_DOCS_REMOTE="$remote/<repo>.git" fetch_branch_one "no-branch" "$t/dest" 2>&1)" || rc=$?
  ok "$rc" "0" "a missing api-docs branch is not a failure"
  ok "$([ -d "$t/dest" ] && echo yes || echo no)" "no" "...and nothing is written"
  ok "$(grep -c 'no-branch has no api-docs branch' <<<"$out")" "1" "...with a one-line explanation"
  rm -rf "$t"

  echo
  echo "-- isolated from the caller's git environment --"
  t="$(mktemp -d)"
  out="$(GIT_DIR="$t/elsewhere" API_DOCS_REMOTE="$remote/<repo>.git" fetch_branch_one "with-branch" "$t/dest" 2>&1)"
  ok "$([ -f "$t/dest/scala/index.html" ] && echo yes || echo no)" "yes" "an inherited GIT_DIR (a hook's) does not redirect the fetch"
  ok "$([ -e "$t/elsewhere" ] && echo yes || echo no)" "no" "...nor gets written to"
  rm -rf "$t"

  echo
  echo "-- the CLI: REPO DEST --"
  t="$(mktemp -d)"
  rc=0
  out="$(API_DOCS_REMOTE="$remote/<repo>.git" bash "${BASH_SOURCE[0]}" with-branch "$t/dest" 2>&1)" || rc=$?
  ok "$rc" "0" "fetch-api-docs.sh REPO DEST runs"
  ok "$([ -f "$t/dest/scala/index.html" ] && echo yes || echo no)" "yes" "...and unpacks into DEST"
  rc=0
  bash "${BASH_SOURCE[0]}" with-branch >/dev/null 2>&1 || rc=$?
  ok "$rc" "2" "a missing DEST is a usage error"
  rm -rf "$t" "$remote"

  echo
  if [ "$fails" -eq 0 ]; then echo "fetch-api-docs self-test: ok"; return 0; fi
  echo "fetch-api-docs self-test: $fails failure(s)" >&2
  return 1
}

case "${1:-}" in
  --self-test) self_test ;;
  -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  -*) echo "fetch-api-docs: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  *)
    [ "$#" -eq 2 ] || { echo "usage: $0 REPO DEST | --self-test" >&2; exit 2; }
    fetch_branch_one "$1" "$2"
    ;;
esac
