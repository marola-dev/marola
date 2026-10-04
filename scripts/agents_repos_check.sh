#!/usr/bin/env bash
# agents_repos_check — AGENTS.md links the routing table instead of restating it (MIP-0074 §7):
# docs/2-Building-marola/REPOS.md is its only copy.
#
#   scripts/agents_repos_check.sh [AGENTS.md]   # default: the repo root's
#   scripts/agents_repos_check.sh --self-test
set -euo pipefail

REPOS_PAGE="docs/2-Building-marola/REPOS.md"

check() {   # $1 = file; prints each finding, returns 1 if any
  local file="$1" found=0 hits
  [ -f "$file" ] || { echo "agents_repos_check: $file not found" >&2; return 2; }
  if hits="$(grep -nE '^\| \[?marola-(app|site|corpus|ml|oods|devkit)' "$file" | cut -d: -f1)"; then
    echo "agents_repos_check: $file:$(paste -sd, - <<<"$hits"): a repo table row; it belongs in $REPOS_PAGE"
    found=1
  fi
  if hits="$(grep -noE 'marola-image|corpus\.version|resources\.version' "$file")"; then
    echo "agents_repos_check: $file: names a pin file ($(paste -sd' ' - <<<"$hits")); it belongs in $REPOS_PAGE"
    found=1
  fi
  if ! grep -qF "]($REPOS_PAGE" "$file"; then
    echo "agents_repos_check: $file does not link $REPOS_PAGE"
    found=1
  fi
  return "$found"
}

self_test() {
  local fails=0 t rc
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails + 1)); fi; }
  t="$(mktemp -d)"
  local link="The repos, their artifacts and pins: [REPOS.md]($REPOS_PAGE)."

  printf '# AGENTS.md\n\n%s\n\n| Repo | Holds |\n|---|---|\n| [marola-app](https://github.com/marola-dev/marola-app) | the app |\n' "$link" >"$t/AGENTS.md"
  rc=0; check "$t/AGENTS.md" >/dev/null || rc=$?
  ok "$rc" "1" "table_row_fails: a table row naming a repo"

  printf '# AGENTS.md\n\n%s\n\nBump corpus.version after a corpus release.\n' "$link" >"$t/AGENTS.md"
  rc=0; check "$t/AGENTS.md" >/dev/null || rc=$?
  ok "$rc" "1" "pin_name_fails: a pin file named"

  printf '# AGENTS.md\n\n## The repos\n\n%s\n\nRead marola-app'"'"'s own AGENTS.md before changing it.\n' "$link" >"$t/AGENTS.md"
  rc=0; check "$t/AGENTS.md" >/dev/null || rc=$?
  ok "$rc" "0" "link_only_ok: one link, repos named in prose"

  printf '# AGENTS.md\n\nRead marola-app'"'"'s own AGENTS.md before changing it.\n' >"$t/AGENTS.md"
  rc=0; check "$t/AGENTS.md" >/dev/null || rc=$?
  ok "$rc" "1" "missing_link_fails: no link to the routing table"

  printf '# AGENTS.md\n\nThe routing table is %s, see there.\n' "$REPOS_PAGE" >"$t/AGENTS.md"
  rc=0; check "$t/AGENTS.md" >/dev/null || rc=$?
  ok "$rc" "1" "mention_only_fails: the path named, not linked"

  rm -rf "$t"
  echo
  if [ "$fails" -eq 0 ]; then echo "agents_repos_check self-test: ok"; return 0; fi
  echo "agents_repos_check self-test: $fails failure(s)" >&2
  return 1
}

case "${1:-}" in
  --self-test) self_test ;;
  -h|--help) sed -n '2,6p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  -*) echo "agents_repos_check: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  *) check "${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/AGENTS.md}" ;;
esac
