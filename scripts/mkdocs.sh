#!/usr/bin/env bash
# mkdocs — build (or serve) marola's documentation site. MIP-0064.
#
#   scripts/mkdocs.sh              # build into mkdocs/generated-docs
#   scripts/mkdocs.sh --serve      # serve on http://localhost:8001; restart to pick up doc edits
#   scripts/mkdocs.sh --self-test  # the pure parts; no containers, no network
#
# Needs a working Docker or Podman daemon. That is the documented prerequisite rather than a
# nix-native mkdocs (MIP-0064 decision 4): the container stack is what pins Kroki too.
#
# The build is NOT strict yet — `--strict` arrives with task 2, once docs/README.md has become
# docs/index.md and the link-shaped prose strings are resolved. Until then strict mode fails on
# work this task deliberately leaves alone (MIP-0064 decision 5).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="marola/mkdocs"
container="marola-mkdocs"

# Ported from the reference (MIP-0064 §4.1), including the part that matters: a runtime counts only
# when its daemon answers. An installed `docker` whose current context points at a dead endpoint is
# the normal case on a machine that also runs podman, and picking it by binary alone fails late,
# after the image build, with an error about the context rather than about the daemon.
pick_runtime() {
  local rt
  for rt in podman docker; do
    command -v "$rt" >/dev/null 2>&1 && "$rt" info >/dev/null 2>&1 && { echo "$rt"; return 0; }
  done
  return 1
}

compose_files() {
  case "$1" in
    build) echo "-f docker-compose.yml -f docker-compose.build.yml" ;;
    serve) echo "-f docker-compose.yml -f docker-compose.serve.yml" ;;
    *)     return 1 ;;
  esac
}

# mkdocs reads one tree, so docs/ is copied in rather than symlinked — and then baked into the
# image, so `--serve`'s live reload watches neither this copy nor ../docs (MIP-0064 §8).
stage_docs() {
  local src="$1" dest="$2"
  rm -rf "$dest"
  mkdir -p "$dest"
  cp -R "$src/." "$dest/"
}

run() {
  local mode="$1" rt files compose
  rt="$(pick_runtime)" || {
    echo "mkdocs: no reachable docker or podman daemon — a container runtime is this build's prerequisite" >&2
    exit 1
  }
  read -r -a files <<<"$(compose_files "$mode")"
  compose=("$rt" compose)
  export PODMAN_COMPOSE_WARNING_LOGS=false

  cd "$repo_root/mkdocs"
  "${compose[@]}" "${files[@]}" down >/dev/null 2>&1 || true
  rm -rf generated-docs
  stage_docs "$repo_root/docs" docs
  "$rt" build --tag "$image" --rm .

  # -d then `logs -f`, not a foreground `up`: podman-compose does not enforce depends_on health
  # conditions in the foreground, so mkdocs would start before Kroki answers and every diagram
  # would render as a plugin error.
  "${compose[@]}" "${files[@]}" up -d --remove-orphans
  # On EXIT as well as the signals: `logs -f` also returns when the container dies on its own, and
  # without this the script would walk out leaving two Kroki containers running behind it.
  # shellcheck disable=SC2064  # $files/$compose are locals; bind them now, not at trap time
  trap "$(printf '%q ' "${compose[@]}" "${files[@]}") down >/dev/null 2>&1 || true" EXIT INT TERM

  [ "$mode" = serve ] && echo "docs on http://localhost:8001 — Ctrl+C to stop"
  "${compose[@]}" logs -f mkdocs

  # `logs -f` returns when the container stops, whatever it stopped with. Without reading the code
  # back, a failed build copies out a half-written tree and reports success — and task 2 turns on
  # --strict, whose entire job is to make that exit code non-zero. The fallbacks matter as much as
  # the check: under `set -e` a failing `inspect` would abort the script, and an empty result would
  # reach `exit ""`, which bash rejects with "numeric argument required".
  local code
  code="$("$rt" inspect -f '{{.State.ExitCode}}' "$container" 2>/dev/null || echo 1)"
  [ -n "$code" ] || code=1
  [ "$code" = "0" ] || { echo "mkdocs: $mode failed (exit $code) — see the log above" >&2; exit "$code"; }

  if [ "$mode" = build ]; then
    rm -rf docs generated-docs
    "$rt" cp "$container:/mkdocs/generated-docs/" .
    echo "built: mkdocs/generated-docs/index.html"
  fi
}

self_test() {
  local fails=0 tmp stub
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails + 1)); fi; }

  # A stub runtime whose `info` succeeds is reachable; one whose `info` fails is installed but dead.
  stub="$(mktemp -d)"
  printf '#!/bin/sh\nexit 0\n' >"$stub/podman"; chmod +x "$stub/podman"
  ok "$(PATH="$stub" pick_runtime)" "podman" "a reachable podman is picked"
  printf '#!/bin/sh\nexit 1\n' >"$stub/podman"
  printf '#!/bin/sh\nexit 0\n' >"$stub/docker"; chmod +x "$stub/docker"
  ok "$(PATH="$stub" pick_runtime)" "docker" "a podman whose daemon does not answer is skipped for a live docker"
  printf '#!/bin/sh\nexit 1\n' >"$stub/docker"
  ok "$(PATH="$stub" pick_runtime || echo none)" "none" "two installed-but-dead runtimes fail rather than being picked"
  ok "$(PATH="$stub/empty" pick_runtime || echo none)" "none" "neither installed fails too"
  rm -rf "$stub"

  ok "$(compose_files build)" "-f docker-compose.yml -f docker-compose.build.yml" "build mode layers the build override"
  ok "$(compose_files serve)" "-f docker-compose.yml -f docker-compose.serve.yml" "serve mode layers the serve override"
  ok "$(compose_files nonsense || echo rejected)" "rejected" "an unknown mode is rejected, not silently built"

  tmp="$(mktemp -d)"
  mkdir -p "$tmp/src/mips" "$tmp/dest"
  echo hello >"$tmp/src/README.md"
  echo mip >"$tmp/src/mips/MIP-0001.md"
  echo stale >"$tmp/dest/GONE.md"
  stage_docs "$tmp/src" "$tmp/dest"
  ok "$(cat "$tmp/dest/README.md")" "hello" "the docs tree is copied in"
  ok "$(cat "$tmp/dest/mips/MIP-0001.md")" "mip" "including subdirectories"
  ok "$([ -e "$tmp/dest/GONE.md" ] && echo present || echo gone)" "gone" "a deleted doc does not survive in the staged copy"
  rm -rf "$tmp"

  # The config keys the 1.7.0 pin exists for (MIP-0064 §4.2) — a copy-paste from the 0.9.0
  # reference would build green and emit localhost <img> tags into the deployed site.
  local cfg="$repo_root/mkdocs/mkdocs.yml"
  ok "$(grep -c '^ *server_url:' "$cfg")" "1" "mkdocs.yml uses the 1.7.0 snake_case server_url"
  ok "$(grep -c 'http_method: POST' "$cfg")" "1" "http_method is POST, so SVGs are written into the output"
  ok "$(grep -c 'fence_prefix: ""' "$cfg")" "1" 'fence_prefix is empty, so plain mermaid fences render'
  ok "$(grep -c '^nav:' "$cfg")" "0" "there is no hand-written nav to drift (decision 1)"

  if [ "$fails" -eq 0 ]; then echo "mkdocs self-test: ok"; return 0; fi
  echo "mkdocs self-test: $fails failure(s)" >&2
  return 1
}

case "${1:-}" in
  --self-test) self_test ;;
  --serve)     run serve ;;
  -h|--help)   sed -n '2,13p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  "")          run build ;;
  *)           echo "mkdocs: unknown argument '$1' (try --help)" >&2; exit 2 ;;
esac
