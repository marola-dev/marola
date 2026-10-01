#!/usr/bin/env bash
# corpus-fetch — unpack the marola-corpus release pinned in corpus.version into .tmp/knowledge
# (MIP-0070 §5.4), where `sbt test`, `just ask` and the image build read it. A pin already
# fetched is not downloaded again (.tmp/knowledge.version). A corpus checkout, such as the
# marola-corpus/ submodule, is used without this script: MAROLA_KNOWLEDGE_DIR=marola-corpus/knowledge.
#
#   scripts/corpus-fetch.sh              # resolve the pin in the repo root's corpus.version
#   scripts/corpus-fetch.sh --self-test
#
# MAROLA_CORPUS_URL overrides the release base (the self-test serves file:// fixtures).
set -euo pipefail

fetch() {
  local root="$1" pin base
  pin="$(<"$root/corpus.version")"
  [[ "$pin" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "corpus-fetch: corpus.version must be a marola-corpus tag (vX.Y.Z), got '$pin'" >&2; return 1; }
  if [ -d "$root/.tmp/knowledge" ] && [ "$(cat "$root/.tmp/knowledge.version" 2>/dev/null)" = "$pin" ]; then
    return 0
  fi
  base="${MAROLA_CORPUS_URL:-https://github.com/marola-dev/marola-corpus/releases/download}"
  mkdir -p "$root/.tmp"
  # A subshell, so the EXIT trap cleans up without touching the caller's traps.
  (
    tmp="$(mktemp -d "$root/.tmp/corpus-fetch.XXXXXX")"
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL --retry 3 -o "$tmp/corpus.tar.gz" "$base/$pin/marola-corpus-$pin.tar.gz" ||
      { echo "corpus-fetch: could not download marola-corpus $pin from $base" >&2; exit 1; }
    tar -xzf "$tmp/corpus.tar.gz" -C "$tmp"
    [ -d "$tmp/knowledge" ] || { echo "corpus-fetch: marola-corpus $pin's tarball has no knowledge/" >&2; exit 1; }
    rm -rf "$root/.tmp/knowledge"
    mv "$tmp/knowledge" "$root/.tmp/knowledge"
    echo "$pin" >"$root/.tmp/knowledge.version"
    echo "corpus-fetch: marola-corpus $pin -> .tmp/knowledge" >&2
  )
}

self_test() {
  local t f=0 out
  t="$(mktemp -d)"
  trap 'rm -rf "$t"' RETURN
  # Fake releases, laid out as GitHub serves them: <base>/<tag>/marola-corpus-<tag>.tar.gz.
  release() {
    local tag=$1 src="$t/src-$1"
    shift
    mkdir -p "$src/knowledge/safety" "$t/releases/$tag"
    for doc in "$@"; do echo "# $doc" >"$src/knowledge/$doc"; done
    tar -czf "$t/releases/$tag/marola-corpus-$tag.tar.gz" -C "$src" knowledge
  }
  release v0.1.0 doc.md safety/safe.md stale.md
  release v0.2.0 doc.md safety/safe.md
  mkdir -p "$t/releases/v0.3.0" "$t/releases/v0.4.0" "$t/empty"
  tar -czf "$t/releases/v0.3.0/marola-corpus-v0.3.0.tar.gz" -C "$t/empty" .
  head -c 40 "$t/releases/v0.1.0/marola-corpus-v0.1.0.tar.gz" >"$t/releases/v0.4.0/marola-corpus-v0.4.0.tar.gz"
  mkdir -p "$t/repo"
  export MAROLA_CORPUS_URL="file://$t/releases"
  # In a child shell with errexit on, as production runs it: a caller's `||` or `if` would turn
  # errexit off inside fetch and hide a failing step.
  run_fetch() { bash -euo pipefail -c "$(declare -f fetch); fetch \"\$1\"" _ "$t/repo" 2>/dev/null; }

  echo v0.1.0 >"$t/repo/corpus.version"
  out="$(run_fetch)" || { echo "FAIL: fetch v0.1.0"; f=1; }
  [ -z "$out" ] || { echo "FAIL: fetch wrote to stdout (an MCP stdio server runs after it): $out"; f=1; }
  [ -f "$t/repo/.tmp/knowledge/safety/safe.md" ] || { echo "FAIL: safety/safe.md missing after fetch"; f=1; }

  # Idempotent: the same pin again is a no-op, so it works offline once fetched.
  MAROLA_CORPUS_URL="file://$t/nowhere" run_fetch || { echo "FAIL: a re-run of the same pin downloaded again"; f=1; }

  # A bump replaces the tree, it does not merge: what v0.2.0 dropped is gone.
  echo v0.2.0 >"$t/repo/corpus.version"
  run_fetch || { echo "FAIL: fetch v0.2.0"; f=1; }
  [ -f "$t/repo/.tmp/knowledge/doc.md" ] || { echo "FAIL: doc.md missing after the bump"; f=1; }
  [ -f "$t/repo/.tmp/knowledge/stale.md" ] && { echo "FAIL: a file v0.2.0 dropped survived the bump"; f=1; }

  # A failed fetch (no release, no knowledge/, a truncated tarball, a bad pin) keeps the last good corpus.
  for pin in v9.9.9 v0.3.0 v0.4.0 local; do
    echo "$pin" >"$t/repo/corpus.version"
    if run_fetch; then echo "FAIL: pin $pin should exit non-zero"; f=1; fi
    [ -f "$t/repo/.tmp/knowledge/doc.md" ] || { echo "FAIL: pin $pin removed the last good corpus"; f=1; }
    [ "$(cat "$t/repo/.tmp/knowledge.version")" = v0.2.0 ] || { echo "FAIL: pin $pin moved the recorded version"; f=1; }
  done

  echo "corpus-fetch self-test:" "$([ "$f" -eq 0 ] && echo ok || echo FAILED)"
  [ "$f" -eq 0 ]
}

case "${1:-}" in
  --self-test) self_test ;;
  "") fetch "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" ;;
  *) echo "usage: $0 [--self-test]" >&2; exit 2 ;;
esac
