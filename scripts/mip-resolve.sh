#!/usr/bin/env bash
# mip-resolve — CLI over scripts/lib/mip_ref.sh's resolve_mip_file/resolve_mip_path, the same
# lookup scripts/uprd.sh and scripts/issues.sh call directly (MIP-0070 §5.6): find a MIP's doc or
# task list from this repo's own tree, an umbrella checkout one level up, or MAROLA_UMBRELLA's
# GitHub API — the path a code repo needs once it carries no docs/MIPs of its own.
# scripts/mip-resolve.sh MIP-0070          # print the main doc's content
# scripts/mip-resolve.sh MIP-0070 tasks    # print the .tasks.md content
# scripts/mip-resolve.sh --path MIP-0070   # print "source<TAB>path" instead of content
# scripts/mip-resolve.sh --self-test
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/mip_ref.sh.
source "$script_dir/lib/mip_ref.sh"

self_test() {
  local failed=0 tmp
  check() { if [ "$2" = "$3" ]; then echo "ok: $1"; else echo "FAILED: $1"; echo "  got:  $2"; echo "  want: $3"; failed=1; fi; }

  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN

  echo "-- this repo's own tree (unchanged, local) --"
  mkdir -p "$tmp/local-repo/docs/MIPs"
  printf 'doc body\n' >"$tmp/local-repo/docs/MIPs/MIP-0070-umbrella.md"
  check "resolves from this repo's own docs/MIPs" \
    "$(cd "$tmp/local-repo" && bash "$script_dir/mip-resolve.sh" MIP-0070)" "doc body"

  echo
  echo "-- ../docs/MIPs (this checkout sits inside an umbrella clone) --"
  mkdir -p "$tmp/umbrella/docs/MIPs" "$tmp/umbrella/code-repo"
  printf 'umbrella doc body\n' >"$tmp/umbrella/docs/MIPs/MIP-0070-umbrella.md"
  check "resolves from ../docs/MIPs when this repo has none" \
    "$(cd "$tmp/umbrella/code-repo" && bash "$script_dir/mip-resolve.sh" MIP-0070)" "umbrella doc body"
  check "and reports the umbrella (not local) as its source" \
    "$(cd "$tmp/umbrella/code-repo" && bash "$script_dir/mip-resolve.sh" --path MIP-0070)" \
    "$(printf 'marola-dev/marola\tdocs/MIPs/MIP-0070-umbrella.md')"

  echo
  echo "-- gh api (no umbrella sibling on disk at all) --"
  mkdir -p "$tmp/bin" "$tmp/bare-code-repo"
  cat >"$tmp/bin/gh" <<'SH'
#!/bin/sh
case "$*" in
  *"repos/marola-dev/marola/contents/docs/MIPs --jq"*)
    printf '%s\n' MIP-0068.tasks.md MIP-0070-umbrella-and-polyrepo-split.md MIP-0070.tasks.md ;;
  *"repos/marola-dev/marola/contents/docs/MIPs/MIP-0070-umbrella-and-polyrepo-split.md"*)
    printf 'gh api doc body\n' ;;
  *"repos/marola-dev/marola/contents/docs/MIPs/MIP-0070.tasks.md"*)
    printf 'gh api tasks body\n' ;;
  *) exit 1 ;;
esac
SH
  chmod +x "$tmp/bin/gh"
  check "resolves MIP-0070 via gh api when nothing is local or in ../" \
    "$(cd "$tmp/bare-code-repo" && PATH="$tmp/bin:$PATH" bash "$script_dir/mip-resolve.sh" MIP-0070)" "gh api doc body"
  check "and the tasks file the same way" \
    "$(cd "$tmp/bare-code-repo" && PATH="$tmp/bin:$PATH" bash "$script_dir/mip-resolve.sh" MIP-0070 tasks)" "gh api tasks body"

  echo
  echo "-- MAROLA_UMBRELLA is a devkit setting, not a literal --"
  cat >"$tmp/bin/gh" <<'SH'
#!/bin/sh
case "$*" in
  *"repos/someone/else/contents/docs/MIPs --jq"*) printf '%s\n' MIP-0070-x.md ;;
  *"repos/someone/else/contents/docs/MIPs/MIP-0070-x.md"*) printf 'other umbrella\n' ;;
  *) exit 1 ;;
esac
SH
  check "MAROLA_UMBRELLA overrides the default owner/repo" \
    "$(cd "$tmp/bare-code-repo" && PATH="$tmp/bin:$PATH" MAROLA_UMBRELLA=someone/else bash "$script_dir/mip-resolve.sh" MIP-0070)" \
    "other umbrella"

  echo
  echo "-- a MIP found nowhere --"
  local rc=0
  (cd "$tmp/bare-code-repo" && PATH="$tmp/bin:$PATH" bash "$script_dir/mip-resolve.sh" MIP-9999 >/dev/null 2>&1) || rc=$?
  check "exits non-zero when unresolved everywhere" "$rc" "1"

  echo
  if [ "$failed" -eq 1 ]; then echo "mip-resolve self-test: FAILED" >&2; return 1; fi
  echo "mip-resolve self-test: ok"
}

path_only=0; mip=""; kind=doc
for arg in "$@"; do
  case "$arg" in
    --self-test) self_test; exit $? ;;
    --path) path_only=1 ;;
    tasks) kind=tasks ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    MIP-[0-9][0-9][0-9][0-9]) mip="$arg" ;;
    *) echo "mip-resolve: unrecognized argument: $arg" >&2; exit 1 ;;
  esac
done
[ -n "$mip" ] || { echo "mip-resolve: expects MIP-NNNN [tasks] [--path]" >&2; exit 1; }

if [ "$path_only" -eq 1 ]; then
  resolve_mip_path "$mip" "$kind" || { echo "mip-resolve: $mip not found locally, in ../docs/MIPs, or via gh api repos/\$MAROLA_UMBRELLA" >&2; exit 1; }
else
  resolve_mip_file "$mip" "$kind" || { echo "mip-resolve: $mip not found locally, in ../docs/MIPs, or via gh api repos/\$MAROLA_UMBRELLA" >&2; exit 1; }
fi
