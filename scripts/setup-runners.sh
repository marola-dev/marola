#!/usr/bin/env bash
# Register N self-hosted Actions runners on this machine, so CI runs in parallel instead of FIFO.
#
# One runner executes one job at a time. ci.yml alone has four jobs that run concurrently on
# GitHub-hosted runners, so a single self-hosted runner serialises what used to be parallel.
#
#   scripts/setup-runners.sh 3              # register 3, install them as user services
#   scripts/setup-runners.sh 3 --foreground # register 3, print the run.sh commands instead
#   scripts/setup-runners.sh --status
#   scripts/setup-runners.sh --remove
#
# Needs `gh` authenticated: the registration token is short-lived (~1 h) and is fetched per run.
set -euo pipefail

repo_default() { printf '%s' "${MAROLA_RUNNER_REPO:-marola-dev/marola}"; }
REPO="$(repo_default)"
ROOT="${MAROLA_RUNNER_ROOT:-$HOME/.marola-runners}"
LABELS="${MAROLA_RUNNER_LABELS:-marola-sea}"
PREFIX="${MAROLA_RUNNER_PREFIX:-marola}"
DEFAULT_COUNT=3

runner_name() { printf '%s-%s' "$PREFIX" "$1"; }
runner_dir()  { printf '%s/%s' "$ROOT" "$(runner_name "$1")"; }

# Every runner carries the same labels: they are one machine, so a job may take any free one. The
# GPU publish job is serialised by its own `concurrency:` group, not by having a private runner.
config_argv() {
  local name=$1 token=$2
  printf '%s\n' --unattended --replace \
    --url "https://github.com/$REPO" --token "$token" \
    --name "$name" --labels "$LABELS" --work _work
}

registration_token() {
  gh api -X POST "/repos/$REPO/actions/runners/registration-token" --jq .token 2>/dev/null
}

# The runner tarball, or an existing install to clone. nixpkgs' github-runner is read-only in the
# store and the runner writes into its own directory, so a copy is what works off NixOS.
source_dir() {
  local d
  for d in "${MAROLA_RUNNER_SOURCE:-}" "$HOME/code/actions-runner" "$HOME/actions-runner"; do
    if [ -n "$d" ] && [ -x "$d/config.sh" ]; then echo "$d"; return 0; fi
  done
  return 1
}

# The source may be a live runner. Its state is top-level dotfiles (.runner, .runner_migrated,
# .credentials*, .service, ...) plus _work/_diag; any one of them makes config.sh refuse.
copy_dist() {
  local from=$1 to=$2
  tar -C "$from" --anchored --exclude='./.*' --exclude=./_work --exclude=./_diag -cf - . \
    | tar -C "$to" -xf -
}

self_test() {
  local fails=0
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails+1)); fi; }

  ok "$(runner_name 1)" "marola-1" "runners are named by index, so each registration is unique"
  ok "$(runner_name 3)" "marola-3" "and the index reaches the requested count"
  ok "$(runner_dir 2)" "$ROOT/marola-2" "each runner gets its own directory — they cannot share one"
  ok "$(config_argv marola-1 TOK | grep -c -- --replace)" "1" \
     "--replace, so re-running re-registers instead of failing on a taken name"
  ok "$(config_argv marola-1 TOK | grep -c -- --unattended)" "1" "no prompts: this must be scriptable"
  ok "$(config_argv marola-1 TOK | grep -A1 -- --labels | tail -1)" "$LABELS" \
     "every runner carries the same labels, so any free one can take any job"
  ok "$(config_argv marola-1 TOK | grep -A1 -- --name | tail -1)" "marola-1" "the name reaches config.sh"
  ok "$(config_argv marola-1 TOK | grep -A1 -- --url | tail -1)" "https://github.com/$REPO" \
     "the repo reaches config.sh's --url"
  ok "$(MAROLA_RUNNER_REPO=other/repo repo_default)" "other/repo" "MAROLA_RUNNER_REPO honours an override"
  ok "$(unset MAROLA_RUNNER_REPO; repo_default)" "marola-dev/marola" \
     "MAROLA_RUNNER_REPO unset falls back to the marola-dev org"
  ok "$(config_argv marola-1 TOK | grep -c 'TOK')" "1" "the registration token is passed through"
  ok "$(HOME=/definitely/absent MAROLA_RUNNER_SOURCE=/definitely/absent source_dir || echo none)" "none" \
     "a missing runner install is reported, not guessed at"

  local t; t=$(mktemp -d)
  mkdir -p "$t/src/bin" "$t/src/_work/x" "$t/src/_diag" "$t/dst"
  touch "$t/src/bin/Runner.Listener" "$t/src/bin/.hidden-in-bin" "$t/src/run.sh" \
        "$t/src/.runner" "$t/src/.runner_migrated" "$t/src/.credentials_migrated" "$t/src/.service"
  copy_dist "$t/src" "$t/dst"
  ok "$(cd "$t/dst" && ls -A | sort | tr '\n' ' ')" "bin run.sh " \
     "the copy carries the distribution only, none of a live runner's state files"
  ok "$(ls -A "$t/dst/bin" | sort | tr '\n' ' ')" ".hidden-in-bin Runner.Listener " \
     "only top-level dotfiles are state; nested ones belong to the distribution"
  rm -rf "$t"

  if [ "$fails" -eq 0 ]; then echo "setup-runners self-test: ok"; return 0; fi
  echo "setup-runners self-test: $fails failure(s)" >&2; return 1
}

status() {
  local i d n
  [ -d "$ROOT" ] || { echo "no runners under $ROOT"; return 0; }
  for d in "$ROOT"/*/; do
    [ -d "$d" ] || continue
    n=$(basename "$d")
    if [ -f "$d/.runner" ]; then
      printf '%-16s configured' "$n"
    else
      printf '%-16s NOT configured' "$n"
    fi
    if systemctl --user is-active --quiet "actions.runner.$n.service" 2>/dev/null; then
      echo "  (service active)"
    else
      echo "  (service not active)"
    fi
  done
}

remove_all() {
  local d n token
  token=$(registration_token || true)
  for d in "$ROOT"/*/; do
    [ -d "$d" ] || continue
    n=$(basename "$d")
    echo "removing $n"
    if [ -x "$d/svc.sh" ]; then (cd "$d" && sudo ./svc.sh uninstall >/dev/null 2>&1) || true; fi
    if [ -n "$token" ] && [ -x "$d/config.sh" ]; then
      (cd "$d" && ./config.sh remove --token "$token" >/dev/null 2>&1) || true
    fi
    rm -rf "$d"
  done
  echo "removed everything under $ROOT"
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  --help|-h)   sed -n '2,12p' "$0"; exit 0 ;;
  --status)    status; exit 0 ;;
  --remove)    remove_all; exit 0 ;;
esac

count="${1:-$DEFAULT_COUNT}"
case "$count" in ''|*[!0-9]*) echo "setup-runners: count must be a number, got '$count'" >&2; exit 2 ;; esac
[ "$count" -ge 1 ] || { echo "setup-runners: count must be at least 1" >&2; exit 2; }
foreground=0
for a in "$@"; do [ "$a" = "--foreground" ] && foreground=1; done

src=$(source_dir) || {
  echo "setup-runners: no runner install found to copy." >&2
  echo "               Download one once, then re-run:" >&2
  echo "                 mkdir -p ~/actions-runner && cd ~/actions-runner" >&2
  echo "                 url=\$(gh api repos/actions/runner/releases/latest --jq '.assets[].browser_download_url' | grep 'linux-x64-[0-9.]*\\.tar\\.gz\$')" >&2
  echo "                 curl -sS -O -L \"\$url\" && tar xzf actions-runner-linux-x64-*.tar.gz" >&2
  echo "               Or point at an existing one: MAROLA_RUNNER_SOURCE=/path" >&2
  exit 1
}

token=$(registration_token) || true
if [ -z "${token:-}" ]; then
  echo "setup-runners: could not get a registration token — is gh logged in? (just gh-auth)" >&2
  exit 1
fi

echo "source:  $src"
echo "repo:    $REPO"
echo "labels:  $LABELS"
echo "runners: $count under $ROOT"
echo

mkdir -p "$ROOT"
for i in $(seq 1 "$count"); do
  name=$(runner_name "$i")
  dir=$(runner_dir "$i")
  if [ -f "$dir/.runner" ]; then
    echo "$name: already configured — skipping"
    continue
  fi
  rm -rf "$dir"
  mkdir -p "$dir"
  copy_dist "$src" "$dir"
  mapfile -t argv < <(config_argv "$name" "$token")
  (cd "$dir" && ./config.sh "${argv[@]}")
  echo "$name: configured"
done

echo
if [ "$foreground" -eq 1 ]; then
  echo "start them, one terminal each:"
  for i in $(seq 1 "$count"); do echo "  (cd $(runner_dir "$i") && ./run.sh)"; done
else
  echo "installing services (sudo):"
  for i in $(seq 1 "$count"); do
    dir=$(runner_dir "$i")
    (cd "$dir" && sudo ./svc.sh install "$USER" && sudo ./svc.sh start)
  done
  echo
  status
fi
