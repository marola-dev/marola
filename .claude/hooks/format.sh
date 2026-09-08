#!/usr/bin/env bash
# format — PostToolUse(Edit|Write) hook: format the one file just written, in place (MIP-0011 §5
# item 3).
set -euo pipefail

TIMEOUT_SECS=30
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

scalafmt_version() {
  sed -n 's/^version *= *"\(.*\)"/\1/p' "$REPO_ROOT/.scalafmt.conf" | head -1
}

extract_file_path() {   # hook JSON on stdin -> .tool_input.file_path (empty when absent)
  if command -v jq >/dev/null 2>&1; then
    jq -r '.tool_input.file_path // empty' 2>/dev/null || true
  else
    python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("tool_input",{}).get("file_path",""))
except Exception: pass' 2>/dev/null || true
  fi
}

# format_file <path>: best-effort, in place.
format_file() {
  local path="$1"
  [ -f "$path" ] || return 0
  case "$path" in
    *.scala)
      local ver; ver="$(scalafmt_version)"
      [ -n "$ver" ] || return 0
      timeout "${TIMEOUT_SECS}s" cs launch "org.scalameta:scalafmt-cli_2.13:$ver" -- \
        -c "$REPO_ROOT/.scalafmt.conf" -i "$path" >/dev/null 2>&1 || true
      ;;
    *.py)
      command -v ruff >/dev/null 2>&1 || return 0
      timeout "${TIMEOUT_SECS}s" ruff format -q "$path" >/dev/null 2>&1 || true
      ;;
  esac
  return 0
}

self_test() {
  local fails=0
  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  # An already-formatted .scala file comes out byte-equal (skipped if `cs` is absent — same guard
  # as the .py check below; a cold coursier cache/no network/air-gapped CI must not fail `just
  # quality-other` on a check that needs a coursier launch, an ultrareview finding, 2026-09-06 —
  # this one was the only .scala/.py asymmetry: the .py check already skipped). $messy is used
  # later too (the end-to-end JSON-path test below), regardless of whether cs is on PATH, so the
  # file itself is written unconditionally — only the scalafmt-dependent assertions are skipped
  # when cs is absent.
  local messy="$tmp/Messy.scala"
  printf 'package example\nfinal case class Messy(name:String,value:Int)\n' >"$messy"
  if command -v cs >/dev/null 2>&1; then
    local scala_file="$tmp/Formatted.scala"
    cat >"$scala_file" <<'EOF'
package example

final case class Formatted(name: String, value: Int)
EOF
    cp "$scala_file" "$tmp/Formatted.scala.orig"
    if format_file "$scala_file" && cmp -s "$scala_file" "$tmp/Formatted.scala.orig"; then
      echo "  ok   already-formatted .scala file is byte-equal after format_file"
    else
      echo "  FAIL already-formatted .scala file changed"
      diff -u "$tmp/Formatted.scala.orig" "$scala_file" || true
      fails=$((fails + 1))
    fi

    # A badly-formatted .scala file is actually reformatted.
    cp "$messy" "$tmp/Messy.scala.orig"
    format_file "$messy"
    if cmp -s "$messy" "$tmp/Messy.scala.orig"; then
      echo "  FAIL messy .scala file was not reformatted"
      fails=$((fails + 1))
    else
      echo "  ok   messy .scala file was reformatted"
    fi
  else
    echo "  skip .scala checks — cs (coursier) not on PATH"
  fi

  # An already-formatted .py file comes out byte-equal (skipped if ruff is absent).
  if command -v ruff >/dev/null 2>&1; then
    local py_file="$tmp/formatted.py"
    printf 'def f(x: int) -> int:\n    return x + 1\n' >"$py_file"
    cp "$py_file" "$tmp/formatted.py.orig"
    if format_file "$py_file" && cmp -s "$py_file" "$tmp/formatted.py.orig"; then
      echo "  ok   already-formatted .py file is byte-equal after format_file"
    else
      echo "  FAIL already-formatted .py file changed"
      diff -u "$tmp/formatted.py.orig" "$py_file" || true
      fails=$((fails + 1))
    fi
  else
    echo "  skip .py check — ruff not on PATH"
  fi

  # Non-matching extensions and missing files are no-ops, never a failure.
  local other="$tmp/README.md"
  printf '#   messy    md\n' >"$other"
  cp "$other" "$tmp/README.md.orig"
  format_file "$other"
  if cmp -s "$other" "$tmp/README.md.orig"; then
    echo "  ok   non-scala/py file left untouched"
  else
    echo "  FAIL non-scala/py file was modified"
    fails=$((fails + 1))
  fi
  if format_file "$tmp/does-not-exist.scala"; then
    echo "  ok   missing file is a no-op, not a failure"
  else
    echo "  FAIL missing file returned non-zero"
    fails=$((fails + 1))
  fi

  # End-to-end through the JSON path Claude Code uses (Edit and Write shapes alike).
  local edit_json="{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"$messy\"}}"
  printf '%s' "$edit_json" | format_file "$(printf '%s' "$edit_json" | extract_file_path)"
  if [ -n "$(extract_file_path <<<"$edit_json")" ]; then
    echo "  ok   extract_file_path reads .tool_input.file_path"
  else
    echo "  FAIL extract_file_path returned empty for a well-formed Edit payload"
    fails=$((fails + 1))
  fi
  local got=0
  printf 'not json' | extract_file_path >/dev/null 2>&1 || got=$?
  if [ -z "$(printf 'not json' | extract_file_path)" ]; then
    echo "  ok   malformed input yields an empty path (never blocks by accident)"
  else
    echo "  FAIL malformed input did not yield an empty path"
    fails=$((fails + 1))
  fi

  [ "$fails" -eq 0 ] && { echo "format self-test: ok"; return 0; }
  echo "format self-test: $fails failure(s)" >&2; return 1
}

case "${1-}" in
  --self-test) self_test ;;
  *) format_file "$(extract_file_path)" ;;
esac
