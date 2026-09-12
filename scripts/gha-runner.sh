#!/usr/bin/env bash
# gha-runner — run marola's self-hosted GitHub Actions runner in the background, after
# runner-preflight.sh says this machine can actually run the workflows.
#
#   just runner-up        # preflight, then start detached (logs to .tmp/gha-runner.log)
#   just runner-status    # local process + what GitHub thinks of the runner
#   just runner-logs      # tail -f the log
#   just runner-down      # graceful stop (SIGTERM; the runner finishes its current job)
#
# MAROLA_GHA_RUNNER_DIR (default /home/hoffmann/code/actions-runner) is where config.sh and run.sh
# live — the runner is registered there, not in this repo. flake.nix ships the runner itself and
# documents the one-time `config.sh --labels marola-sea,dependabot` registration.
#
# `up` refuses to start a second runner against the same directory: two processes sharing one
# registration take jobs out from under each other, and the second one usually dies with a
# confusing "already running" from the service side rather than anything about this script.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
resolve_runner_dir() { printf '%s' "${MAROLA_GHA_RUNNER_DIR:-/home/hoffmann/code/actions-runner}"; }
runner_dir="$(resolve_runner_dir)"
log="$repo_root/.tmp/gha-runner.log"
pidfile="$repo_root/.tmp/gha-runner.pid"

is_running() {   # pid -> 0 when that pid is alive
  [ -n "${1:-}" ] && kill -0 "$1" 2>/dev/null
}

# Discovery, not bookkeeping. The pidfile is a hint: a runner started by hand, by svc.sh, or by a
# previous shell that has since closed is just as real, and a stop command that cannot see those
# is how a machine ends up with two listeners fighting over one registration. Everything below
# asks the process table instead, and the pidfile is only ever written, never trusted.
# Machine-wide, not scoped to $runner_dir: scripts/setup-runners.sh clones the registration into
# marola-1..N so CI runs in parallel, and each clone is a listener of its own. A stop command that
# only knew about one directory would leave the others running — the exact "process nobody catches
# later" this script is meant to rule out.
listener_pids() { pgrep -f '(run\.sh|bin/Runner\.Listener)$|bin/Runner\.Listener ' 2>/dev/null || true; }
worker_pids() { pgrep -f 'bin/Runner\.Worker' 2>/dev/null || true; }

dir_of_pid() {   # the runner directory a pid is working out of
  readlink -f "/proc/$1/cwd" 2>/dev/null || echo "?"
}
count() {
  if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | grep -c .; fi
}

up() {
  local live
  live="$(listener_pids)"
  if [ -n "$live" ]; then
    echo "$(count "$live") runner(s) already listening on this machine — 'just gha' to see where, 'just ghas' to stop them" >&2
    exit 1
  fi
  [ -x "$runner_dir/run.sh" ] || {
    echo "no runner at $runner_dir/run.sh — set MAROLA_GHA_RUNNER_DIR, or register one there first (see flake.nix)" >&2
    exit 1
  }
  "$repo_root/scripts/runner-preflight.sh" || {
    echo "" >&2
    echo "preflight failed — fix the above, or 'just runner-up --force' to start anyway" >&2
    [ "${force:-0}" -eq 1 ] || exit 1
  }
  mkdir -p "$repo_root/.tmp"
  # setsid: survive this shell closing, which is the whole point of running it in the background.
  setsid nohup "$runner_dir/run.sh" >>"$log" 2>&1 &
  echo $! >"$pidfile"
  echo "runner up (pid $(cat "$pidfile")), logging to $log"
}

down() {
  local live workers pid waited
  live="$(listener_pids)"
  workers="$(worker_pids)"
  rm -f "$pidfile"
  if [ -z "$live" ]; then
    echo "no runner listening on this machine"
    [ -n "$workers" ] && echo "warning: $(count "$workers") orphaned worker process(es) still running: $(printf '%s' "$workers" | tr '\n' ' ')" >&2
    return 0
  fi
  if [ -n "$workers" ] && [ "${force:-0}" -eq 0 ]; then
    echo "$(count "$workers") job(s) are executing right now — stopping the listener cancels them." >&2
    echo "wait for them ('just gha' to watch), or 'just ghas --force'" >&2
    exit 1
  fi
  for pid in $live; do
    echo "stopping runner pid $pid (SIGTERM)"
    kill -TERM "$pid" 2>/dev/null || true
  done
  # Bounded wait, then SIGKILL: a listener that ignores TERM is exactly the process this script
  # must not leave behind.
  waited=0
  while [ "$waited" -lt 20 ] && [ -n "$(listener_pids)" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  live="$(listener_pids)"
  if [ -n "$live" ]; then
    for pid in $live; do
      echo "pid $pid ignored SIGTERM after ${waited}s — SIGKILL" >&2
      kill -KILL "$pid" 2>/dev/null || true
    done
  fi
  # A worker outlives the listener that spawned it, so --force has to clean up after itself or it
  # leaves behind precisely the untracked process this script exists to prevent.
  workers="$(worker_pids)"
  for pid in $workers; do
    echo "killing orphaned worker pid $pid (its job is already lost with the listener)" >&2
    kill -TERM "$pid" 2>/dev/null || true
  done
  echo "stopped ($(count "$(listener_pids)") listener(s), $(count "$(worker_pids)") worker(s) left)"
}

status() {
  local live workers
  live="$(listener_pids)"
  workers="$(worker_pids)"
  local pid
  echo "local: $(count "$live") runner(s) listening, $(count "$workers") job(s) executing"
  for pid in $live; do echo "  runner pid $pid  $(dir_of_pid "$pid")"; done
  for pid in $workers; do echo "  job    pid $pid  $(dir_of_pid "$pid")"; done
  local remote
  remote="$(gh api "repos/${MAROLA_REPO:-h0ffmann/marola}/actions/runners" \
    --jq '.runners[] | "github: \(.name) \(.status) busy=\(.busy) labels=\([.labels[].name] | join(","))"' 2>/dev/null || true)"
  if [ -n "$remote" ]; then echo "$remote"; else echo "github: could not read the runner list (no token?)"; fi
  # Worth saying out loud: inside ai-jail the process table is a different namespace, so a runner
  # that is plainly alive looks like zero here. Run this on the host, not in the sandbox.
  case "$remote" in
    *online*)
      if [ -z "$live" ]; then
        echo "note: GitHub says a runner is online but no local process matches $runner_dir —" \
          "either it runs from another directory, or you are inside a sandbox that cannot see it"
      fi
      ;;
  esac
  if [ -f "$log" ]; then
    echo "--- last 5 log lines ---"
    tail -5 "$log"
  fi
  return 0
}

self_test() {
  local f=0
  t() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 — got $2, want $3" >&2; f=$((f + 1)); fi; }
  t "counting nothing is zero, not one blank line" "$(count "")" 0
  t "counting one pid" "$(count "123")" 1
  t "counting three" "$(count "$(printf '1\n2\n3')")" 3
  t "our own pid is alive" "$(is_running $$ && echo yes || echo no)" yes
  t "an impossible pid is not" "$(is_running 999999999 && echo yes || echo no)" no
  t "an empty pid is not a process" "$(is_running "" && echo yes || echo no)" no
  t "the runner dir honours MAROLA_GHA_RUNNER_DIR" \
    "$(MAROLA_GHA_RUNNER_DIR=/tmp/x resolve_runner_dir)" /tmp/x
  t "and falls back to the registered location" \
    "$(unset MAROLA_GHA_RUNNER_DIR; resolve_runner_dir)" /home/hoffmann/code/actions-runner
  echo "gha-runner self-test:" "$([ "$f" -eq 0 ] && echo ok || echo "$f FAILED")"
  [ "$f" -eq 0 ]
}

force=0
cmd="${1:-status}"
# An `if`, not `[ ... ] && force=1`: under `set -e` a failing AND-list at top level is a trap the
# justfile already documents once, and this file should not re-learn it.
if [ "${2:-}" = "--force" ]; then force=1; fi
case "$cmd" in
  up) up ;;
  down) down ;;
  status) status ;;
  logs) tail -f "$log" ;;
  --self-test) self_test ;;
  *) echo "usage: gha-runner.sh {up|down|status|logs} [--force]" >&2; exit 2 ;;
esac
