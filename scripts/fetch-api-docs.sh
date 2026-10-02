#!/usr/bin/env bash
# fetch-api-docs — fold each submodule's pre-built API docs (scaladoc/pdoc) into the umbrella's
# generated site, from a release asset its own CI publishes. The umbrella never builds Scala or
# Python here (MIP-0070 §5.5): no `sbt doc`, no pdoc. The asset name is a cross-repo contract —
# api-docs.tar.gz, attached to GitHub's "latest" release — unpacked under <mount>/api/, a sibling
# of the mounted README/docs. A missing release or asset is not an error, just a skip.
#
#   scripts/fetch-api-docs.sh SITE_DIR [REPOS_FILE]   # default mkdocs/repos.yml
#   scripts/fetch-api-docs.sh --self-test              # stubs gh; no network
#
# API_DOCS_TAG=<tag> downloads that release instead of the latest one.
set -euo pipefail

ASSET_NAME="api-docs.tar.gz"
MAROLA_UMBRELLA="${MAROLA_UMBRELLA:-marola-dev/marola}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/repos_manifest.sh
source "$script_dir/lib/repos_manifest.sh"

fetch_one() {
  local repo="$1" dest="$2" tmp
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2086  # an unset API_DOCS_TAG must vanish: no tag means the latest release
  if gh release download ${API_DOCS_TAG:-} --repo "$repo" --pattern "$ASSET_NAME" --dir "$tmp" >/dev/null 2>&1; then
    mkdir -p "$dest"
    tar -xzf "$tmp/$ASSET_NAME" -C "$dest"
    echo "fetch-api-docs: $repo -> $dest"
  else
    echo "fetch-api-docs: $repo has no $ASSET_NAME on its latest release yet — skipping"
  fi
  rm -rf "$tmp"
}

fetch_all() {
  local site_dir="$1" manifest="$2" org="${MAROLA_UMBRELLA%%/*}"
  local name mount
  while IFS=$'\t' read -r name mount; do
    [ -n "$name" ] || continue
    fetch_one "$org/$name" "$site_dir/$mount/api"
  done < <(repos_manifest "$manifest")
}

self_test() {
  local fails=0
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails + 1)); fi; }

  local stub; stub="$(mktemp -d)"
  cat >"$stub/gh" <<'SH'
#!/bin/sh
repo=""; dir=""; tag=""
shift 2   # release download
while [ $# -gt 0 ]; do
  case "$1" in
    --repo) repo="$2"; shift 2 ;;
    --dir) dir="$2"; shift 2 ;;
    --pattern) shift 2 ;;
    *) tag="$1"; shift ;;
  esac
done
echo "${tag:-latest}" >>"${GH_TAGS:-/dev/null}"
case "$repo" in
  */has-asset)
    mkdir -p "$dir/payload"
    echo hi >"$dir/payload/index.html"
    tar -czf "$dir/api-docs.tar.gz" -C "$dir/payload" .
    rm -rf "$dir/payload"
    exit 0
    ;;
  *) exit 1 ;;
esac
SH
  chmod +x "$stub/gh"

  echo "-- an asset on the latest release is unpacked under <mount>/api/ --"
  local t; t="$(mktemp -d)"
  PATH="$stub:$PATH" fetch_one "marola-dev/has-asset" "$t/site/repos/has-asset/api"
  ok "$([ -f "$t/site/repos/has-asset/api/index.html" ] && echo yes || echo no)" "yes" "the tarball is extracted into dest"
  rm -rf "$t"

  echo
  echo "-- no release, or no asset on it — skip cleanly, no error --"
  t="$(mktemp -d)"
  local out rc=0
  out="$(PATH="$stub:$PATH" fetch_one "marola-dev/no-asset" "$t/site/repos/no-asset/api" 2>&1)" || rc=$?
  ok "$rc" "0" "a missing asset is not a failure"
  ok "$([ -d "$t/site/repos/no-asset" ] && echo yes || echo no)" "no" "...and nothing is written"
  ok "$(printf '%s' "$out" | grep -c 'skipping')" "1" "...with a one-line explanation"
  rm -rf "$t"

  echo
  echo "-- fetch_all reads mkdocs/repos.yml and keeps going past a skip --"
  t="$(mktemp -d)"
  printf -- '- name: has-asset\n- name: no-asset\n  mount: elsewhere/\n' >"$t/manifest.yml"
  out="$(PATH="$stub:$PATH" MAROLA_UMBRELLA="marola-dev/marola" fetch_all "$t/site" "$t/manifest.yml" 2>&1)"
  ok "$([ -f "$t/site/repos/has-asset/api/index.html" ] && echo yes || echo no)" "yes" "the default-mount submodule's asset lands under repos/<name>/api/"
  ok "$([ -d "$t/site/elsewhere/api" ] && echo yes || echo no)" "no" "the skipped submodule's custom mount gets nothing"
  ok "$(printf '%s' "$out" | grep -c 'marola-dev/has-asset ->')" "1" "...and the fetched one is logged"
  rm -rf "$t"

  echo
  echo "-- API_DOCS_TAG asks for that release, not \"latest\" --"
  t="$(mktemp -d)"
  PATH="$stub:$PATH" GH_TAGS="$t/tags" API_DOCS_TAG=api-docs fetch_one "marola-dev/has-asset" "$t/a" >/dev/null 2>&1
  PATH="$stub:$PATH" GH_TAGS="$t/tags" fetch_one "marola-dev/has-asset" "$t/b" >/dev/null 2>&1
  ok "$(tr '\n' ' ' <"$t/tags")" "api-docs latest " "the tag is passed when set, and omitted otherwise"
  rm -rf "$t"

  echo
  echo "-- mount ./ lands the asset at the site root's api/ (marola-app's) --"
  t="$(mktemp -d)"
  printf -- '- name: has-asset\n  mount: ./\n' >"$t/manifest.yml"
  PATH="$stub:$PATH" fetch_all "$t/site" "$t/manifest.yml" >/dev/null 2>&1
  ok "$([ -f "$t/site/api/index.html" ] && echo yes || echo no)" "yes" "the asset is unpacked under <site>/api/"
  rm -rf "$t"
  rm -rf "$stub"

  echo
  if [ "$fails" -eq 0 ]; then echo "fetch-api-docs self-test: ok"; return 0; fi
  echo "fetch-api-docs self-test: $fails failure(s)" >&2
  return 1
}

root_default="$(cd "$script_dir/.." && pwd)"
case "${1:-}" in
  --self-test) self_test ;;
  -h|--help) sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  "") echo "usage: $0 SITE_DIR [REPOS_FILE] | --self-test" >&2; exit 2 ;;
  -*) echo "fetch-api-docs: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  *) fetch_all "$1" "${2:-$root_default/mkdocs/repos.yml}" ;;
esac
