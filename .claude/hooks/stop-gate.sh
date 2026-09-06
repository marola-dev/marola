#!/usr/bin/env bash
# stop-gate — Stop hook: nudge once per session to run `just test` after a .scala edit.
# (MIP-0011 §5 item 4.)
#
# If any tracked .scala file differs from HEAD and this session hasn't been blocked by this hook
# yet, block the stop once with a message asking Claude to run `just test` (or say why not), and
# record a marker so the *same session* is never blocked a second time — a Stop hook that blocks
# repeatedly burns tokens and trust (MIP-0011 §8), and the harness already caps continuations at
# 8; this caps at 1 regardless. The marker does not check that `just test` actually ran — the
# block itself is the enforcement; a second nag in the same session would just be noise.
#
# Session scope comes from the hook's own JSON (`.session_id`), not `stop_hook_active` — that
# field only distinguishes "this Stop followed a block", not "this session already got one nag",
# and a later ordinary Stop later in the same session must not re-block.
#
# Marker location: $XDG_RUNTIME_DIR (falls back to /tmp), one file per session id — ephemeral,
# never committed, cleared whenever the runtime dir is (login session end / reboot).
#
#   .claude/hooks/stop-gate.sh --self-test   # run by `just quality`; exits non-zero on any miss
#
# Wired in .claude/settings.json -> hooks.Stop.
set -euo pipefail

REPO_ROOT="${STOP_GATE_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
MARKER_DIR="${XDG_RUNTIME_DIR:-/tmp}/marola-stop-gate"

extract_field() {   # hook JSON on stdin -> .$1 (empty when absent or input isn't valid JSON)
  local field="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -r --arg f "$field" '.[$f] // empty' 2>/dev/null || true
  else
    python3 -c "import json,sys
try: print(json.load(sys.stdin).get(sys.argv[1], ''))
except Exception: pass" "$field" 2>/dev/null || true
  fi
}

scala_changed_since_head() {
  git -C "$REPO_ROOT" diff --name-only HEAD -- '*.scala' 2>/dev/null | grep -q .
}

# check_stop <session_id>: 0 = allow, 2 = block (and writes the marker so the next call allows).
check_stop() {
  local session_id="$1"
  [ -n "$session_id" ] || return 0   # no session id on stdin — never block by accident
  mkdir -p "$MARKER_DIR"
  local marker="$MARKER_DIR/$session_id"
  [ -f "$marker" ] && return 0        # already nagged this session
  scala_changed_since_head || return 0   # nothing scala-shaped changed — nothing to gate
  touch "$marker"
  echo "stop-gate: .scala files changed since HEAD and this session hasn't run \`just test\` yet." >&2
  echo "Run \`just test\` (and \`just quality\`) before stopping, or say why this change doesn't need it. This nag only fires once per session." >&2
  return 2
}

self_test() {
  local fails=0
  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  export XDG_RUNTIME_DIR="$tmp/runtime"
  MARKER_DIR="$XDG_RUNTIME_DIR/marola-stop-gate"

  # A throwaway git repo with an uncommitted .scala change, so scala_changed_since_head is true.
  local repo="$tmp/repo"
  git init -q "$repo"
  git -C "$repo" config user.email test@example.com
  git -C "$repo" config user.name Test
  printf 'object A\n' > "$repo/A.scala"
  git -C "$repo" add A.scala
  git -C "$repo" commit -q -m init
  printf 'object A { val x = 1 }\n' > "$repo/A.scala"   # uncommitted change since HEAD
  REPO_ROOT="$repo"

  local session="self-test-session-$$"
  local first=0; check_stop "$session" || first=$?
  if [ "$first" -eq 2 ]; then
    echo "  ok   first call with a .scala change and no marker blocks"
  else
    echo "  FAIL first call did not block (exit $first)"
    fails=$((fails + 1))
  fi

  local second=0; check_stop "$session" || second=$?
  if [ "$second" -eq 0 ]; then
    echo "  ok   second call in the same session (marker present) allows"
  else
    echo "  FAIL second call blocked again (exit $second) — a second nag in one session"
    fails=$((fails + 1))
  fi

  # A different session id, same repo state, blocks again — the marker is per-session, not global.
  local other_session="self-test-session-other-$$"
  local third=0; check_stop "$other_session" || third=$?
  if [ "$third" -eq 2 ]; then
    echo "  ok   a different session id blocks independently of the first"
  else
    echo "  FAIL a fresh session id did not block (exit $third)"
    fails=$((fails + 1))
  fi

  # No .scala changes -> never blocks, even with no marker.
  git -C "$repo" checkout -q -- A.scala
  local clean_session="self-test-session-clean-$$"
  local fourth=0; check_stop "$clean_session" || fourth=$?
  if [ "$fourth" -eq 0 ]; then
    echo "  ok   no .scala changes since HEAD never blocks"
  else
    echo "  FAIL clean tree blocked anyway (exit $fourth)"
    fails=$((fails + 1))
  fi

  # Empty/missing session id never blocks, even with a .scala change.
  printf 'object A { val x = 2 }\n' > "$repo/A.scala"
  local empty_result=0; check_stop "" || empty_result=$?
  if [ "$empty_result" -eq 0 ]; then
    echo "  ok   missing session id never blocks by accident"
  else
    echo "  FAIL missing session id blocked (exit $empty_result)"
    fails=$((fails + 1))
  fi

  # End-to-end through the real hook JSON path (extract_field + check_stop as actually wired).
  local json_session="self-test-json-$$"
  local hook_json="{\"session_id\":\"$json_session\",\"hook_event_name\":\"Stop\"}"
  local got_session; got_session="$(printf '%s' "$hook_json" | extract_field session_id)"
  if [ "$got_session" = "$json_session" ]; then
    echo "  ok   extract_field reads .session_id from real hook JSON"
  else
    echo "  FAIL extract_field got '$got_session', expected '$json_session'"
    fails=$((fails + 1))
  fi
  if [ -z "$(printf 'not json' | extract_field session_id)" ]; then
    echo "  ok   malformed input yields an empty session id (never blocks by accident)"
  else
    echo "  FAIL malformed input did not yield an empty session id"
    fails=$((fails + 1))
  fi
  local fifth=0; check_stop "$got_session" || fifth=$?   # real path: fresh session, .scala changed again
  if [ "$fifth" -eq 2 ]; then
    echo "  ok   end-to-end JSON-derived session id blocks on a fresh session"
  else
    echo "  FAIL end-to-end JSON-derived session id did not block (exit $fifth)"
    fails=$((fails + 1))
  fi

  [ "$fails" -eq 0 ] && { echo "stop-gate self-test: ok"; return 0; }
  echo "stop-gate self-test: $fails failure(s)" >&2; return 1
}

case "${1-}" in
  --self-test) self_test ;;
  *) check_stop "$(extract_field session_id)" ;;
esac
