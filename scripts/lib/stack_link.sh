#!/usr/bin/env bash
# stack_link — shared with scripts/stack.sh's `link` subcommand and scripts/deps-stack.sh.
stack_link() {
  local dry="$1"; shift
  gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; return 1; }
  gh extension list 2>/dev/null | grep -q 'github/gh-stack' || { echo "gh stack extension not installed — run: just stack-setup" >&2; return 1; }
  local open=() b state
  for b in "$@"; do
    state="$(gh pr view "$b" --json state -q .state 2>/dev/null || echo NONE)"
    case "$state" in
      MERGED) echo "skip $b (PR merged)" ;;
      CLOSED) echo "skip $b (PR closed)" ;;
      NONE) echo "skip $b (no PR)" ;;
      *) open+=("$b") ;;
    esac
  done
  [ "${#open[@]}" -ge 1 ] || { echo "nothing to link: no open PR on any given branch"; return 0; }
  if [ "$dry" -eq 1 ]; then
    echo "+ gh stack link ${open[*]}"
  else
    gh stack link "${open[@]}"
  fi
}
