#!/usr/bin/env bash
# site-data-push — push the one commit a workflow just made on its checkout of marola-site's
# `site-data` branch, retrying when another workflow pushed first.
#
#   scripts/site-data-push.sh <checkout-dir>
#   scripts/site-data-push.sh --self-test
#
# This repo's ci.yml (stats) and marola-app's ci.yml (coverage) and docker-smoke.yml (the
# workflows that keep a copy of this script) each write their own directory, so a
# rejected push never conflicts in content, only in ref. The checkouts are --depth=1, which is why
# this replays only HEAD (`--onto FETCH_HEAD HEAD~1`): a plain rebase would also try to replay the
# shallow root, i.e. the whole old tree.
set -euo pipefail

push() {
  local dir="$1" tries="${SITE_DATA_PUSH_TRIES:-5}" i
  for i in $(seq 1 "$tries"); do
    if git -C "$dir" push -q origin HEAD:site-data; then return 0; fi
    [ "$i" -eq "$tries" ] && break
    echo "site-data-push: rejected (attempt $i/$tries), replaying onto the new tip" >&2
    sleep $((i * 2))
    git -C "$dir" fetch -q --depth=1 origin site-data
    # A failed rebase leaves HEAD on the other writer's commit, and pushing that "succeeds" while
    # dropping ours, so it has to stop here.
    local base=HEAD~1
    git -C "$dir" rev-parse -q --verify HEAD~1 >/dev/null || base=--root # lost the first-ever push
    if ! git -C "$dir" rebase -q --onto FETCH_HEAD "$base"; then
      git -C "$dir" rebase --abort 2>/dev/null || true
      echo "site-data-push: could not replay onto the new tip" >&2
      return 1
    fi
  done
  echo "site-data-push: still rejected after $tries attempts" >&2
  return 1
}

self_test() {
  local t f=0
  t="$(mktemp -d)"
  trap 'rm -rf "$t"' RETURN
  git init -q --bare "$t/origin.git"
  git -C "$t/origin.git" symbolic-ref HEAD refs/heads/site-data
  git init -q -b site-data "$t/seed"
  git -C "$t/seed" -c user.name=t -c user.email=t@t commit -q --allow-empty -m root
  git -C "$t/seed" push -q "$t/origin.git" site-data
  for w in a b c; do
    git clone -q --depth=1 -b site-data "file://$t/origin.git" "$t/$w"
    mkdir -p "$t/$w/$w"
    echo "$w" >"$t/$w/$w/latest.json"
    git -C "$t/$w" add "$w"
    git -C "$t/$w" -c user.name=t -c user.email=t@t commit -q -m "$w"
  done
  git -C "$t/b" config user.name t
  git -C "$t/b" config user.email t@t
  push "$t/a" || { echo "FAIL: first push"; f=1; }
  SITE_DATA_PUSH_TRIES=3 push "$t/b" 2>/dev/null || { echo "FAIL: second push was not retried onto the new tip"; f=1; }
  # c has no committer identity (a bare CI runner), so its rebase fails: that must be an error.
  if (unset GIT_COMMITTER_NAME GIT_COMMITTER_EMAIL; HOME="$t" XDG_CONFIG_HOME="$t" GIT_CONFIG_NOSYSTEM=1 \
    GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=user.useConfigOnly GIT_CONFIG_VALUE_0=true \
    SITE_DATA_PUSH_TRIES=2 push "$t/c") 2>/dev/null; then
    echo "FAIL: a failed rebase was reported as a successful push"; f=1
  fi
  local tree
  tree="$(git -C "$t/origin.git" ls-tree -r --name-only site-data | tr '\n' ' ')"
  [ "$tree" = "a/latest.json b/latest.json " ] || { echo "FAIL: site-data holds '$tree', want both writers' files"; f=1; }
  [ "$(git -C "$t/origin.git" rev-list --count site-data)" = 3 ] || { echo "FAIL: history is not root + a + b"; f=1; }
  echo "site-data-push self-test:" "$([ "$f" -eq 0 ] && echo ok || echo FAILED)"
  [ "$f" -eq 0 ]
}

case "${1:-}" in
  --self-test) self_test ;;
  "" | -*) echo "usage: $0 <site-data-worktree> | --self-test" >&2; exit 2 ;;
  *) push "$1" ;;
esac
