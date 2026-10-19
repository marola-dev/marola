#!/usr/bin/env bash
# session-check — the SessionStart hook a repo's .claude/settings.json runs (MIP-0084 §5.4): at most
# three lines of context for the agent, on `source: startup` only. Local files only: no network, no
# build, no writes. Runs on every contributor's machine, so it changes only through the reviewed
# skills.lock update PR.
#
#   session-check.sh < hook-input.json
#   session-check.sh --self-test
#
# §11's first question: a project hook does see `env` from user settings (Claude Code 2.1.296,
# 2026-10-19: a SessionStart hook in a scratch project, run headless with an empty CLAUDE_CONFIG_DIR,
# printed the value its settings.json set). The settings file is still read too, for versions not
# checked.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN="marola-devkit@marola-devkit"

optin_line() {
  local ref settings=${1/#"$HOME"/\~}
  ref=$(grep -o '"repo": *"marola-dev/marola-devkit"[^}]*"ref": *"[^"]*"' .claude/settings.json 2>/dev/null |
    sed 's/.*"ref": *"//; s/"$//' || true)
  echo "▸ marola-devkit plugin not installed. Before starting the person's request, if AskUserQuestion is available, ask once: \"This repo offers the marola-devkit plugin. If installed it runs, on your machine, from marola-dev/marola-devkit@${ref:-its pinned tag}: a formatter after each edit, a gate when a turn ends, and a session-start summary; and it adds the MIP, triage and review skills. Routing works either way.\" Options: Install (they run \`! claude plugin install ${PLUGIN}\`), Not now (nothing; asked again next session), Never (show them the edit, then add \"env\": {\"MAROLA_PLUGIN_OPTOUT\": \"1\"} to $settings). Then carry on with the request. With no way to ask, say nothing about it."
}

# In bash, not route.py: Python's start-up alone eats most of the 100 ms this has.
bundle_line() {
  local ref="" r build head ct now age repo recorded mine line
  for r in refs/marola/routing-bundle refs/remotes/origin/routing-bundle; do
    git rev-parse -q --verify "$r^{commit}" >/dev/null && { ref=$r; break; }
  done
  [ -n "$ref" ] || { echo "▸ routing: no bundle yet; route fetch"; return; }
  build=$(git show "$ref:build.json" 2>/dev/null | head -c 65536 || true)
  head=$(grep -oE '"head": *"[0-9a-f]+"' <<<"$build" | grep -oE '[0-9a-f]{7,}' | head -1 || true)
  ct=$(git log -1 --format=%ct "$ref"); now=$(date +%s); age=$(( (now - ct) / 60 ))
  if [ "$age" -lt 60 ]; then age="$age min"; elif [ "$age" -lt 2880 ]; then age="$((age / 60)) h"; else age="$((age / 1440)) d"; fi
  line="▸ routing: bundle main@${head:0:7} ($age old)"
  # The bundle records the SHA it graphed for this repo; a newer origin/main means it lags.
  repo=$(git remote get-url origin 2>/dev/null || true); repo=${repo%.git}; repo=${repo##*/}
  if [ "$repo" = marola ]; then recorded=$head
  else recorded=$(grep -oE "[0-9a-f]{40} $repo([ \\\"]|$)" <<<"$build" | grep -oE '^[0-9a-f]{40}' | head -1 || true); fi
  mine=$(git rev-parse -q --verify refs/remotes/origin/main 2>/dev/null || true)
  [ -z "$recorded" ] || [ -z "$mine" ] || [ "$recorded" = "$mine" ] || line+=" · stale; route fetch refreshes it"
  echo "$line"
}

check() {
  local input source cfg
  input=$(cat 2>/dev/null || true)
  source=$(sed -n 's/.*"source" *: *"\([a-z]*\)".*/\1/p' <<<"$input" | head -1)
  [ "${source:-startup}" = startup ] || return 0
  git rev-parse --git-dir >/dev/null 2>&1 || return 0
  cd "$(git rev-parse --show-toplevel)"
  bundle_line
  cfg="${CLAUDE_CONFIG_DIR:-$HOME/.claude}"
  if [ "${CLAUDE_CODE_REMOTE:-}" != true ] && [ -z "${MAROLA_PLUGIN_OPTOUT:-}" ] &&
     ! grep -q '"MAROLA_PLUGIN_OPTOUT"' "$cfg/settings.json" 2>/dev/null &&
     ! grep -q "\"$PLUGIN\"" "$cfg/plugins/installed_plugins.json" 2>/dev/null; then
    optin_line "$cfg/settings.json"
  fi
  if [ -f .gitmodules ]; then
    local missing
    # Gitlinks without a .git inside: what `git submodule status` marks `-`, without its per-module spawns.
    missing=$(git ls-files -s 2>/dev/null | awk '$1 == "160000" {print $4}' |
      while read -r p; do [ -e "$p/.git" ] || printf '%s ' "$p"; done)
    [ -z "$missing" ] ||
      echo "▸ submodule(s) not checked out: ${missing% }. route answers from the bundle; git submodule update --init for local reads"
  fi
}

self_test() {
  local tmp fails=0 out home repo sub self t0 ms rc
  self="$here/$(basename "${BASH_SOURCE[0]}")"
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE CLAUDE_CODE_REMOTE MAROLA_PLUGIN_OPTOUT CLAUDE_CONFIG_DIR
  export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_COUNT=1 \
    GIT_CONFIG_KEY_0=protocol.file.allow GIT_CONFIG_VALUE_0=always \
    GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
  # No bundle here, and a fetch must never be tried: point the remote at a path that does not exist.
  export MAROLA_ROUTING_REMOTE=/nonexistent XDG_CACHE_HOME
  has() { case "$2" in *"$3"*) echo "  ok   $1" ;; *) fails=$((fails + 1)); echo "  FAIL $1 — expected \"$3\" in: $2" >&2 ;; esac; }
  hasnot() { case "$2" in *"$3"*) fails=$((fails + 1)); echo "  FAIL $1 — did not expect \"$3\" in: $2" >&2 ;; *) echo "  ok   $1" ;; esac; }

  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
  home="$tmp/home" repo="$tmp/marola" sub="$tmp/sub"
  XDG_CACHE_HOME="$tmp/cache"
  mkdir -p "$home/.claude/plugins"
  git init -q "$sub" && git -C "$sub" commit -q --allow-empty -m s
  git init -q "$repo" && mkdir -p "$repo/.claude"
  printf '{"extraKnownMarketplaces": {"marola-devkit": {"source": {"source": "github", "repo": "marola-dev/marola-devkit", "ref": "v0.9.1"}}}}\n' >"$repo/.claude/settings.json"
  git -C "$repo" submodule -q add "$sub" s 2>/dev/null
  git -C "$repo" add -A && git -C "$repo" commit -q -m init
  run() {
    local in='{"source":"startup"}'
    [ $# -eq 0 ] || in=$1
    (cd "$repo" && HOME="$home" bash "$self" <<<"$in" 2>&1)
  }

  echo "plugin_missing_asks"
  out=$(run)
  has "names the plugin and the ref it runs from" "$out" "marola-dev/marola-devkit@v0.9.1"
  has "three answers" "$out" "Install"
  has "the bundle line comes first" "$(head -1 <<<"$out")" "▸ routing: no bundle yet; route fetch"
  has "Never writes the settings file it reads" "$(CLAUDE_CONFIG_DIR="$tmp/cfg" run)" "to $tmp/cfg/settings.json"

  echo "plugin_installed_silent"
  echo '{"version": 2, "plugins": {"marola-devkit@marola-devkit": [{"scope": "user"}]}}' >"$home/.claude/plugins/installed_plugins.json"
  hasnot "no opt-in line" "$(run)" "AskUserQuestion"
  rm "$home/.claude/plugins/installed_plugins.json"

  echo "optout_env_silent"
  hasnot "MAROLA_PLUGIN_OPTOUT in the environment" "$(MAROLA_PLUGIN_OPTOUT=1 run)" "AskUserQuestion"
  echo '{"env": {"MAROLA_PLUGIN_OPTOUT": "1"}}' >"$home/.claude/settings.json"
  hasnot "or only in the user settings file" "$(run)" "AskUserQuestion"
  rm "$home/.claude/settings.json"

  echo "remote_session_silent"
  out=$(CLAUDE_CODE_REMOTE=true run)
  hasnot "no opt-in line in a cloud session" "$out" "AskUserQuestion"
  has "but the bundle line still" "$out" "▸ routing:"

  echo "resume_source_silent"
  for s in resume clear compact; do
    out=$(run "{\"session_id\":\"x\",\"source\":\"$s\"}")
    if [ -z "$out" ]; then echo "  ok   nothing on $s"; else fails=$((fails + 1)); echo "  FAIL $s printed: $out" >&2; fi
  done

  echo "stale_bundle_line"
  local b="$tmp/bundle" head
  git init -q "$b"
  head=$(git -C "$repo" rev-parse HEAD)
  printf '{"head": "%s", "submodules": ""}\n' "$head" >"$b/build.json"
  git -C "$b" add -A && git -C "$b" commit -q -m bundle
  git -C "$repo" fetch -q "$b" "+HEAD:refs/marola/routing-bundle"
  git -C "$repo" update-ref refs/remotes/origin/main "$head"
  git -C "$repo" remote add origin https://example.invalid/marola-dev/marola.git
  out=$(run)
  has "a current bundle is named" "$out" "▸ routing: bundle main@${head:0:7} ("
  hasnot "and not stale" "$out" "stale"
  git -C "$repo" commit -q --allow-empty -m next
  git -C "$repo" update-ref refs/remotes/origin/main HEAD
  has "origin/main moved past it: stale" "$(run)" "stale; route fetch refreshes it"
  git -C "$repo" remote set-url origin https://example.invalid/marola-dev/marola-app.git
  printf '{"head": "%s", "submodules": " %s marola-app (heads/main)\\n %s marola-site"}\n' "$head" "$(git -C "$repo" rev-parse HEAD)" "$head" >"$b/build.json"
  git -C "$b" commit -q -am again && git -C "$repo" fetch -q "$b" "+HEAD:refs/marola/routing-bundle"
  hasnot "in a submodule repo, its own recorded SHA is what counts" "$(run)" "stale"
  git -C "$repo" remote set-url origin https://example.invalid/marola-dev/marola-site.git
  has "and a lagging one is stale" "$(run)" "stale; route fetch refreshes it"

  echo "uninitialised_submodule_line"
  hasnot "all checked out: no line" "$(run)" "not checked out"
  git -C "$repo" submodule -q deinit -f s
  has "names the submodule" "$(run)" "submodule(s) not checked out: s."

  echo "no_network_calls"
  if command -v unshare >/dev/null && unshare -rn true 2>/dev/null; then
    rc=0; out=$(cd "$repo" && HOME="$home" unshare -rn bash "$self" <<<'{"source":"startup"}' 2>&1) || rc=$?
    if [ "$rc" -eq 0 ]; then echo "  ok   runs with no network namespace"; else fails=$((fails + 1)); echo "  FAIL without network: $out" >&2; fi
  else
    echo "  skip unshare -rn not permitted here"
  fi
  hasnot "the script itself names no fetch" "$(grep -v '^ *#' "$self" | sed -n '/^check()/,/^}/p')" "fetch"

  echo "under_100ms"
  t0=$(date +%s%N); run >/dev/null; ms=$(( ($(date +%s%N) - t0) / 1000000 ))
  if [ "$ms" -lt 100 ]; then echo "  ok   ${ms} ms"; else fails=$((fails + 1)); echo "  FAIL ${ms} ms" >&2; fi

  if [ "$fails" -eq 0 ]; then echo "session-check self-test: ok"; return 0; fi
  echo "session-check self-test: $fails failure(s)" >&2; return 1
}

case "${1:-}" in
  --self-test) self_test ;;
  -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  *) check || true ;;
esac
