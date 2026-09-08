#!/usr/bin/env bash
# session-start — SessionStart hook: print branch, gh auth, uncommitted count, and the two ai-jail
# caveats from FABLE_REVIEW.md §3, once, as session context. MIP-0011.
set -euo pipefail

REPO_ROOT="${SESSION_START_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

branch_line() {
  local branch; branch="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "(no branch)")"
  local uncommitted; uncommitted="$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null | grep -c . || true)"
  local gh_status
  if gh auth status >/dev/null 2>&1; then
    local user; user="$(gh api user -q .login 2>/dev/null || echo "unknown user")"
    gh_status="logged in as $user"
  else
    gh_status="not logged in (push works, PRs by hand)"
  fi
  echo "▸ SessionStart: branch $branch · gh: $gh_status · $uncommitted uncommitted"
}

jail_caveat_lines() {
  echo "▸ jail caveat: .env.example and .ai-jail read empty inside \`just jail-claude\` — never \`git add -A\`, stage by name"
  echo "▸ jail caveat: gh has no ~/.config/gh inside the jail — push works over SSH, PRs need GH_TOKEN or by hand"
}

print_context() {
  branch_line
  jail_caveat_lines
}

self_test() {
  # Same leak as stop-gate.sh: under the harness's GIT_DIR, `git -C <tmp>` still targets the real
  # repo — its `config user.email test@example.com` overwrote .git/config on every push.
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_COMMON_DIR
  local fails=0
  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  local repo="$tmp/repo"
  git init -q -b feature/self-test "$repo"
  git -C "$repo" config user.email test@example.com
  git -C "$repo" config user.name Test
  printf 'placeholder\n' > "$repo/f.txt"
  git -C "$repo" add f.txt
  git -C "$repo" commit -q -m init
  REPO_ROOT="$repo"

  local out; out="$(branch_line)"
  if [[ "$out" == "▸ SessionStart: branch feature/self-test · gh: "* ]]; then
    echo "  ok   branch line names the current branch"
  else
    echo "  FAIL branch line missing/wrong branch: $out"
    fails=$((fails + 1))
  fi
  if [[ "$out" == *"· 0 uncommitted" ]]; then
    echo "  ok   clean tree reports 0 uncommitted"
  else
    echo "  FAIL clean tree did not report 0 uncommitted: $out"
    fails=$((fails + 1))
  fi

  printf 'changed\n' > "$repo/f.txt"
  printf 'new\n' > "$repo/g.txt"
  out="$(branch_line)"
  if [[ "$out" == *"· 2 uncommitted" ]]; then
    echo "  ok   one modified + one untracked file report 2 uncommitted"
  else
    echo "  FAIL dirty tree did not report 2 uncommitted: $out"
    fails=$((fails + 1))
  fi

  # gh auth status is whatever the real environment has — assert the two known-good shapes only.
  if [[ "$out" == *"gh: logged in as "* || "$out" == *"gh: not logged in (push works, PRs by hand)"* ]]; then
    echo "  ok   gh status line matches one of the two documented shapes"
  else
    echo "  FAIL gh status line matched neither documented shape: $out"
    fails=$((fails + 1))
  fi

  local jail_out; jail_out="$(jail_caveat_lines)"
  if [ "$(printf '%s\n' "$jail_out" | grep -c '^▸ jail caveat: ')" -eq 2 ]; then
    echo "  ok   exactly two jail-caveat lines are printed"
  else
    echo "  FAIL expected exactly two jail-caveat lines, got: $jail_out"
    fails=$((fails + 1))
  fi
  if printf '%s\n' "$jail_out" | grep -q 'env.example'; then
    echo "  ok   .env.example/.ai-jail masking caveat is present"
  else
    echo "  FAIL .env.example/.ai-jail masking caveat missing"
    fails=$((fails + 1))
  fi
  if printf '%s\n' "$jail_out" | grep -q 'GH_TOKEN'; then
    echo "  ok   gh-unauthenticated-in-jail caveat is present"
  else
    echo "  FAIL gh-unauthenticated-in-jail caveat missing"
    fails=$((fails + 1))
  fi

  # No branch (detached, empty repo edge case) never crashes the hook.
  local empty_repo="$tmp/empty"
  git init -q "$empty_repo"
  REPO_ROOT="$empty_repo"
  if branch_line >/dev/null 2>&1; then
    echo "  ok   an empty repo with no commits does not crash branch_line"
  else
    echo "  FAIL branch_line crashed on an empty repo"
    fails=$((fails + 1))
  fi

  [ "$fails" -eq 0 ] && { echo "session-start self-test: ok"; return 0; }
  echo "session-start self-test: $fails failure(s)" >&2; return 1
}

case "${1-}" in
  --self-test) self_test ;;
  *) print_context ;;
esac
