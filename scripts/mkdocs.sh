#!/usr/bin/env bash
# mkdocs — build (or serve) marola's documentation site. MIP-0064.
#
#   scripts/mkdocs.sh              # build into mkdocs/generated-docs
#   scripts/mkdocs.sh --serve      # serve on http://localhost:8001; restart to pick up doc edits
#   scripts/mkdocs.sh --self-test  # the pure parts; no containers, no network
#
# Needs a working Docker or Podman daemon. That is the documented prerequisite rather than a
# nix-native mkdocs (MIP-0064 decision 4): the container stack is what pins Kroki too.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Compose names its project after the directory it runs in, which is `mkdocs` in every checkout of
# this repo. With fixed container_names on top of that, two builds on one host were the same stack:
# each one's `down` deleted the other's containers mid-run. That is not hypothetical — it turned a
# green docs-build red the first time two docs PRs built at once on the self-hosted runner. The
# checkout path makes the project unique; cksum keeps it short and shell-safe.
project_name() {
  printf 'marola-mkdocs-%s' "$(printf '%s' "$1" | cksum | cut -d" " -f1)"
}

# The docs are baked into the image (stage_docs below), so a shared `marola/mkdocs` tag has the
# same cross-checkout collision as the project name above (#526).
image_tag() {
  printf 'marola/mkdocs:%s' "$(printf '%s' "$1" | cksum | cut -d" " -f1)"
}

# Ported from the reference (MIP-0064 §4.1), including the part that matters: a runtime counts only
# when its daemon answers. An installed `docker` whose current context points at a dead endpoint is
# the normal case on a machine that also runs podman, and picking it by binary alone fails late,
# after the image build, with an error about the context rather than about the daemon.
# CONTAINER_RUNTIME=docker pins one: GitHub's hosted image has a podman whose `info` answers but
# no podman socket, so its compose provider fails to reach a daemon (MIP-0065).
pick_runtime() {
  local rt
  for rt in ${CONTAINER_RUNTIME:-podman docker}; do
    command -v "$rt" >/dev/null 2>&1 && "$rt" info >/dev/null 2>&1 && { echo "$rt"; return 0; }
  done
  return 1
}

# mkdocs serve mounts the site under site_url's path, so with a site_url of .../docs/ the dev
# server answers on /docs/ and redirects / to it. Read it back rather than hardcoding the banner.
serve_path() {
  local url
  url="$(sed -n 's|^site_url:[[:space:]]*|&|p' "$1" | sed 's|^site_url:[[:space:]]*||')"
  case "$url" in
    *://*/*) printf '/%s\n' "${url#*://*/}" ;;
    *)       printf '/\n' ;;
  esac
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
  local mode="$1" rt files compose image
  rt="$(pick_runtime)" || {
    echo "mkdocs: no reachable docker or podman daemon — a container runtime is this build's prerequisite" >&2
    exit 1
  }
  read -r -a files <<<"$(compose_files "$mode")"
  local project; project="$(project_name "$repo_root")"
  image="$(image_tag "$repo_root")"
  # Read by mkdocs/docker-compose.yml's `image:` line for every compose call below, not just build.
  export MKDOCS_IMAGE="$image"
  compose=("$rt" compose -p "$project")
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

  [ "$mode" = serve ] && echo "docs on http://localhost:8001$(serve_path mkdocs.yml) — Ctrl+C to stop"
  "${compose[@]}" logs -f mkdocs

  # `logs -f` returns when the container stops, whatever it stopped with. Without reading the code
  # back, a failed build copies out a half-written tree and reports success — and task 2 turns on
  # --strict, whose entire job is to make that exit code non-zero. The fallbacks matter as much as
  # the check: under `set -e` a failing `inspect` would abort the script, and an empty result would
  # reach `exit ""`, which bash rejects with "numeric argument required".
  # Find the container by compose's own labels, not by name: without container_name the two
  # runtimes spell it differently (`-mkdocs-1` vs `_mkdocs_1`), and their `compose ps` flags differ
  # too — podman-compose's takes neither `-a` nor a service argument. podman-compose sets the
  # `com.docker.compose.*` labels as well, so the runtime's own `ps --filter` reads both.
  local cid code
  cid="$("$rt" ps -aq --filter "label=com.docker.compose.project=$project" \
                      --filter "label=com.docker.compose.service=mkdocs" 2>/dev/null | head -1)"
  [ -n "$cid" ] || { echo "mkdocs: no mkdocs container found for compose project $project" >&2; exit 1; }
  code="$("$rt" inspect -f '{{.State.ExitCode}}' "$cid" 2>/dev/null || echo 1)"
  [ -n "$code" ] || code=1
  [ "$code" = "0" ] || { echo "mkdocs: $mode failed (exit $code) — see the log above" >&2; exit "$code"; }

  if [ "$mode" = build ]; then
    rm -rf docs generated-docs
    "$rt" cp "$cid:/mkdocs/generated-docs/" .
    # strict mode does not cover this: a site with no index.md builds green and simply has no
    # landing page, which reaches marola.dev/docs/ as a 404. Checked here instead.
    [ -f generated-docs/index.html ] || {
      echo "mkdocs: built no generated-docs/index.html — docs/index.md is missing or was renamed" >&2
      exit 1
    }
    echo "built: mkdocs/generated-docs/index.html"
  fi
}

self_test() {
  local fails=0 tmp stub tmpcfg
  tmpcfg="$(mktemp)"
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails + 1)); fi; }

  # A stub runtime whose `info` succeeds is reachable; one whose `info` fails is installed but dead.
  stub="$(mktemp -d)"
  printf '#!/bin/sh\nexit 0\n' >"$stub/podman"; chmod +x "$stub/podman"
  ok "$(PATH="$stub" pick_runtime)" "podman" "a reachable podman is picked"
  printf '#!/bin/sh\nexit 0\n' >"$stub/docker"; chmod +x "$stub/docker"
  ok "$(CONTAINER_RUNTIME=docker PATH="$stub" pick_runtime)" "docker" "CONTAINER_RUNTIME=docker skips a live podman"
  printf '#!/bin/sh\nexit 1\n' >"$stub/podman"
  printf '#!/bin/sh\nexit 0\n' >"$stub/docker"; chmod +x "$stub/docker"
  ok "$(PATH="$stub" pick_runtime)" "docker" "a podman whose daemon does not answer is skipped for a live docker"
  printf '#!/bin/sh\nexit 1\n' >"$stub/docker"
  ok "$(PATH="$stub" pick_runtime || echo none)" "none" "two installed-but-dead runtimes fail rather than being picked"
  ok "$(PATH="$stub/empty" pick_runtime || echo none)" "none" "neither installed fails too"
  rm -rf "$stub"

  ok "$(project_name /a/b/c)" "$(project_name /a/b/c)" "the project name is stable for one checkout"
  ok "$([ "$(project_name /a/b/c)" = "$(project_name /a/b/d)" ] && echo same || echo different)" "different" \
     "two checkouts get different projects, so their stacks cannot collide"
  ok "$(project_name /a/b/c | grep -cE '^marola-mkdocs-[0-9]+$')" "1" "and it is a shell-safe compose project name"

  ok "$(image_tag /a/b/c)" "$(image_tag /a/b/c)" "the image tag is stable for one checkout"
  ok "$([ "$(image_tag /a/b/c)" = "$(image_tag /a/b/d)" ] && echo same || echo different)" "different" \
     "two checkouts get different image tags, so a build in one cannot retag the other's image"
  ok "$(image_tag /a/b/c | grep -cE '^marola/mkdocs:[0-9]+$')" "1" "and it is a well-formed image tag"
  ok "$(grep -c 'image: \${MKDOCS_IMAGE:-marola/mkdocs}' "$repo_root/mkdocs/docker-compose.yml")" "1" \
     "compose receives the per-checkout image via MKDOCS_IMAGE, defaulting to the shared tag"

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
  # MIP-0068 §5.2: each defaults the other way in the 1.7.0 plugin.
  ok "$(grep -c '^ *fail_fast: true' "$cfg")" "1" "fail_fast is on, so a broken diagram fails the build"
  ok "$(grep -c '^ *enable_bpmn: false' "$cfg")" "1" "bpmn is off, so its fence stays a code block"
  ok "$(grep -c '^ *enable_diagramsnet: false' "$cfg")" "1" "diagramsnet stays off"
  ok "$(grep -c '^nav:' "$cfg")" "0" "there is no hand-written nav to drift (decision 1)"
  ok "$(grep -c '^ *- privacy' "$cfg")" "1" "the privacy plugin is on, so Material's webfont is served locally"
  ok "$(serve_path "$cfg")" "/docs/" "the serve banner follows site_url's path, which is where the dev server answers"
  ok "$(printf 'site_url: https://example.com\n' >"$tmpcfg"; serve_path "$tmpcfg")" "/" "a site_url with no path serves at the root"
  ok "$(grep -c '^strict: true' "$cfg")" "1" "the build is strict, so a broken internal link fails it"
  ok "$([ -f "$repo_root/docs/index.md" ] && echo yes || echo no)" "yes" "docs/index.md exists — strict does not check for it, and without it the site has no landing page"

  # #511: mkdocs/hooks/mermaid_font.py runs before the kroki plugin (event_priority) and pins
  # every top-level ```mermaid fence to a font Kroki actually has, so it never measures label
  # width in a fallback the plugin's own styles injection cannot reach (_inject_mermaid ignores
  # text.font-family). Exercised with plain python3, outside the container the hook normally
  # runs in — the hook module guards its `mkdocs.plugins` import for exactly this reason.
  ok "$(grep -c '^ *hooks:' "$cfg")" "1" "mkdocs.yml registers the hooks: key"
  ok "$(grep -c 'hooks/mermaid_font.py' "$cfg")" "1" "...pointing at the font-pin hook"
  tmp="$(mktemp -d)"
  cat >"$tmp/sample.md" <<'SAMPLE'
# Sample

```mermaid
flowchart TD
  a --> b
```

````markdown
```mermaid
flowchart TD
  a --> b
```
````

```mermaid
%%{init: {"themeVariables": {"git0": "#1ac5da"}}}%%
gitGraph
  commit
```
SAMPLE
  out="$(python3 -c "
import sys
sys.path.insert(0, '$repo_root/mkdocs/hooks')
import mermaid_font
print(mermaid_font.pin_mermaid_font(open('$tmp/sample.md').read()), end='')
")"
  ok "$(printf '%s' "$out" | grep -c fontFamily)" "2" \
     "a mermaid fence is given the pinned font (once per real fence, not the nested Source example)"
  ok "$(printf '%s' "$out" | grep -c '^```$')" "3" \
     "...and every fence's own closing delimiter survives the rewrite, not just its opening"
  ok "$(printf '%s' "$out" | sed -n '/^```mermaid$/{n;p;}' | grep -c 'Open Sans')" "2" \
     "the init line lands first, ahead of the diagram source"
  ok "$(printf '%s' "$out" | grep -c '````markdown')" "1" \
     "...but a mermaid fence nested inside a longer fence is left alone"
  ok "$(printf '%s' "$out" | grep -c 'git0')" "1" \
     "...and an existing fence-level init (gitGraph colours) still merges fine (MIP-0068 task 1, #510)"
  rm -rf "$tmp"

  # --help slices the header by line number, which silently starts printing code when the header
  # grows or shrinks. It shrank once already, when task 2 deleted the not-strict-yet caveat.
  ok "$(bash "${BASH_SOURCE[0]}" --help | grep -c '^set -euo')" "0" "--help prints the header only, not the code under it"
  ok "$(bash "${BASH_SOURCE[0]}" --help | grep -c 'self-test')" "1" "and it still reaches the last usage line"

  if [ "$fails" -eq 0 ]; then echo "mkdocs self-test: ok"; return 0; fi
  echo "mkdocs self-test: $fails failure(s)" >&2
  return 1
}

case "${1:-}" in
  --self-test) self_test ;;
  --serve)     run serve ;;
  -h|--help)   sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  "")          run build ;;
  *)           echo "mkdocs: unknown argument '$1' (try --help)" >&2; exit 2 ;;
esac
