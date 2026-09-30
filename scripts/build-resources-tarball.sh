#!/usr/bin/env bash
# build-resources-tarball — the app -> ml contract (MIP-0070 §5.4): a tarball of the resource
# files finetune/build_dataset.py needs, so it can read an unpacked directory instead of reaching
# into ../core once marola-ml is a separate repo. ci.yml uploads this on every push to main.
#
#   scripts/build-resources-tarball.sh [out-tar.gz]   # default .tmp/ml-resources.tar.gz
#   scripts/build-resources-tarball.sh --self-test
set -euo pipefail

FILES=(
  core/src/main/resources/recommendation_prompt.json
  core/src/main/resources/review_prompt.json
  core/src/main/resources/sea_lore.json
  cli/src/main/resources/benchmark_questions.json
  cli/src/test/resources/site/board.json
)

build() {
  local out="$1" root
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  local file
  for file in "${FILES[@]}"; do
    [ -f "$root/$file" ] || { echo "build-resources-tarball: missing $file" >&2; return 1; }
  done
  mkdir -p "$(dirname "$out")"
  tar -czf "$out" -C "$root" "${FILES[@]}"
  echo "wrote $out:"
  tar -tzf "$out"
}

self_test() {
  local t f=0
  t="$(mktemp -d)"
  trap 'rm -rf "$t"' RETURN
  build "$t/resources.tar.gz" >"$t/log" || { cat "$t/log"; echo "FAIL: build"; return 1; }
  local got want
  got="$(tar -tzf "$t/resources.tar.gz" | sort)"
  want="$(printf '%s\n' "${FILES[@]}" | sort)"
  [ "$got" = "$want" ] || { echo "FAIL: tarball contents"; echo "got:  $got"; echo "want: $want"; f=1; }
  echo "build-resources-tarball self-test:" "$([ "$f" -eq 0 ] && echo ok || echo FAILED)"
  [ "$f" -eq 0 ]
}

case "${1:-}" in
  --self-test) self_test ;;
  -*) echo "usage: $0 [out-tar.gz] | --self-test" >&2; exit 2 ;;
  *) build "${1:-.tmp/ml-resources.tar.gz}" ;;
esac
