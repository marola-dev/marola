#!/usr/bin/env bash
# corpus-fetch — resolve the pin in corpus.version into .tmp/knowledge.
#
# MIP-0070 §5.4: once knowledge/ moves to its own repo, `local` is what an in-tree checkout still
# uses and any other value is a real corpus release tag. Task 13 is the only later change that
# needs to touch: it teaches `local` a fetch, without RagOfflineSpec/`just ask`/the image build
# (which all read MAROLA_KNOWLEDGE_DIR, not this script) noticing the difference.
#
#   scripts/corpus-fetch.sh              # resolve the pin in the repo root's corpus.version
#   scripts/corpus-fetch.sh --self-test
set -euo pipefail

fetch() {
  local root="$1" pin
  pin="$(<"$root/corpus.version")"
  case "$pin" in
    local)
      rm -rf "$root/.tmp/knowledge"
      mkdir -p "$root/.tmp"
      cp -r "$root/knowledge" "$root/.tmp/knowledge"
      ;;
    *)
      echo "corpus-fetch: pin '$pin' not yet supported until MIP-0070 task 13" >&2
      return 1
      ;;
  esac
}

self_test() {
  local t f=0
  t="$(mktemp -d)"
  trap 'rm -rf "$t"' RETURN
  mkdir -p "$t/knowledge/safety"
  echo "# Doc" >"$t/knowledge/doc.md"
  echo "# Safety doc" >"$t/knowledge/safety/safe.md"
  echo local >"$t/corpus.version"

  fetch "$t" || { echo "FAIL: local fetch"; f=1; }
  [ -f "$t/.tmp/knowledge/doc.md" ] || { echo "FAIL: doc.md missing after fetch"; f=1; }
  [ -f "$t/.tmp/knowledge/safety/safe.md" ] || { echo "FAIL: safety/safe.md missing after fetch"; f=1; }

  # Idempotent re-run: a file that only exists in a stale .tmp/knowledge must not survive.
  echo stale >"$t/.tmp/knowledge/stale.md"
  fetch "$t" || { echo "FAIL: second fetch"; f=1; }
  [ -f "$t/.tmp/knowledge/stale.md" ] && { echo "FAIL: stale file survived a re-fetch (replace, not merge)"; f=1; }

  echo unreleased-tag >"$t/corpus.version"
  if fetch "$t" 2>/dev/null; then
    echo "FAIL: an unsupported pin should exit non-zero"; f=1
  fi

  echo "corpus-fetch self-test:" "$([ "$f" -eq 0 ] && echo ok || echo FAILED)"
  [ "$f" -eq 0 ]
}

case "${1:-}" in
  --self-test) self_test ;;
  "") fetch "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" ;;
  *) echo "usage: $0 [--self-test]" >&2; exit 2 ;;
esac
