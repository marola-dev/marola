#!/usr/bin/env bash
# agents-check — verify AGENTS.md's invariants block matches agents/invariants.md byte-for-byte
# (MIP-0070 §5.6: the org invariants travel as one versioned block; a repo's copy must not drift).
#
#   scripts/agents-check.sh                    # check ./AGENTS.md against agents/invariants.md
#   scripts/agents-check.sh path/to/AGENTS.md  # check a different AGENTS.md
#   scripts/agents-check.sh --block path       # compare against a different block (or
#                                               # MAROLA_INVARIANTS_BLOCK=path — task 7 points this
#                                               # at a pinned devkit copy once the block moves out)
#   scripts/agents-check.sh --self-test
set -euo pipefail

START_MARKER='<!-- invariants:start -->'
END_MARKER='<!-- invariants:end -->'

# agents_file block_file -> "ok" | "missing:<marker>" | "mismatch". Never fails itself, so callers
# (including self-test) can use plain command substitution without fighting `set -e`.
verdict() {
  local agents_file=$1 block_file=$2 tmp
  if ! grep -Fxq -- "$START_MARKER" "$agents_file"; then echo "missing:$START_MARKER"; return; fi
  if ! grep -Fxq -- "$END_MARKER" "$agents_file"; then echo "missing:$END_MARKER"; return; fi
  tmp="$(mktemp)"
  awk -v s="$START_MARKER" -v e="$END_MARKER" '$0==s{f=1;next} $0==e{f=0} f{print}' "$agents_file" >"$tmp"
  if cmp -s "$tmp" "$block_file"; then echo ok; else echo mismatch; fi
  rm -f "$tmp"
}

run() {
  local agents_file=$1 block_file=$2 v
  [ -f "$agents_file" ] || { echo "agents-check: $agents_file not found" >&2; return 1; }
  [ -f "$block_file" ] || { echo "agents-check: $block_file not found" >&2; return 1; }
  v="$(verdict "$agents_file" "$block_file")"
  case "$v" in
    ok) echo "agents-check: $agents_file matches $block_file"; return 0 ;;
    missing:*) echo "agents-check: $agents_file is missing the marker ${v#missing:}" >&2; return 1 ;;
    mismatch) echo "agents-check: $agents_file's invariants block (between $START_MARKER and $END_MARKER) does not match $block_file" >&2; return 1 ;;
  esac
}

self_test() {
  local tmp fails=0 out rc block ok_agents edited_agents missing_agents
  check() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else fails=$((fails + 1)); echo "  FAIL $1 — got [$2] want [$3]" >&2; fi; }
  has() { case "$2" in *"$3"*) echo "  ok   $1" ;; *) fails=$((fails + 1)); echo "  FAIL $1 — expected \"$3\" in: $2" >&2 ;; esac; }

  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN

  block="$tmp/invariants.md"
  printf '%s\n' "## Org invariants" "" "- one" "- two" >"$block"

  ok_agents="$tmp/AGENTS-ok.md"
  { printf '# AGENTS\n\n%s\n' "$START_MARKER"; cat "$block"; printf '%s\n' "$END_MARKER"; } >"$ok_agents"
  out=$(run "$ok_agents" "$block" 2>&1) && rc=0 || rc=$?
  check "an unchanged block passes" "$rc" "0"

  edited_agents="$tmp/AGENTS-edited.md"
  { printf '# AGENTS\n\n%s\n' "$START_MARKER"; printf '%s\n' "## Org invariants" "" "- one (edited)" "- two"; printf '%s\n' "$END_MARKER"; } >"$edited_agents"
  out=$(run "$edited_agents" "$block" 2>&1) && rc=0 || rc=$?
  check "an edited block fails" "$rc" "1"

  missing_agents="$tmp/AGENTS-missing.md"
  { printf '# AGENTS\n\n'; cat "$block"; } >"$missing_agents"
  out=$(run "$missing_agents" "$block" 2>&1) && rc=0 || rc=$?
  check "a missing marker fails" "$rc" "1"
  has "the failure names the missing marker" "$out" "$START_MARKER"

  if [ "$fails" -eq 0 ]; then echo "agents-check self-test: ok"; return 0; fi
  echo "agents-check self-test: $fails failure(s)" >&2; return 1
}

agents_file="./AGENTS.md"
block_file="${MAROLA_INVARIANTS_BLOCK:-agents/invariants.md}"
self_test_flag=0

while [ $# -gt 0 ]; do
  case "$1" in
    --block) block_file="${2:?--block needs a path}"; shift ;;
    --self-test) self_test_flag=1 ;;
    -h|--help) sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "agents-check: unknown argument: $1" >&2; exit 2 ;;
    *) agents_file="$1" ;;
  esac
  shift
done

if [ "$self_test_flag" -eq 1 ]; then self_test; exit $?; fi

run "$agents_file" "$block_file"
