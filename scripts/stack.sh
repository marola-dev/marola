#!/usr/bin/env bash
# stack — stacked-PR mechanics for MIP task branches (see .claude/skills/mip-tasks/SKILL.md).
# usage: scripts/stack.sh [--dry-run] [--onto-base <sha>] <subcommand> [args]
#   start MIP-0005 1 board-json   branch mip-0005/1-board-json off origin/main
#   start MIP-0005 2 map-page     branch off the previous task branch (mip-0005/1-*)
#   pr                            push + open/update the PR with the right base
#   restack                       after the base PR was squash-merged: rebase onto main
#   status [MIP-0005]             every branch of this MIP with its base and PR
#   branches [MIP-0005]           the MIP's task branches, local or remote, bottom to top
#   link [MIP-0005]               GitHub Stack (gh stack link) from the *open* PRs, bottom to top
#   --dry-run                     print the commands instead of running them
#   --onto-base <sha>             restack's fork point, when it refuses because it found none
#   --self-test                   run this script's own checks
set -euo pipefail
dry=0; onto_base=""; selftest=0; args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry=1; shift ;;
    --onto-base) onto_base="${2:?--onto-base needs a commit}"; shift 2 ;;
    --self-test) selftest=1; shift ;;
    *) args+=("$1"); shift ;;
  esac
done
set -- ${args[@]+"${args[@]}"}
run() { if [ "$dry" -eq 1 ]; then echo "+ $*"; else "$@"; fi; }

cur="${STACK_SELFTEST_BRANCH:-$(git branch --show-current)}"   # STACK_SELFTEST_BRANCH: self-test hook
mip_of() { sed -n 's#^\(mip-[0-9]\{4\}\)/.*#\1#p' <<<"$1"; }
task_of() { sed -n 's#^mip-[0-9]\{4\}/\([0-9]*\)-.*#\1#p' <<<"$1"; }
branch_for() { git branch --list "$1/$2-*" --format='%(refname:short)' | head -1; }
branches_of() {   # every task branch of a MIP, local or on origin, sorted by task number
  { git branch --list "$1/*" --format='%(refname:short)'
    git branch -r --list "origin/$1/*" --format='%(refname:short)' | sed 's#^origin/##'; } | sort -u | sort -t/ -k2 -n
}
mip_arg() {       # the MIP from $1 (MIP-0005 / mip-0005) or from the current branch
  local m; m="$(tr 'A-Z' 'a-z' <<<"${1:-$(mip_of "$cur")}")"
  [ -n "$m" ] || { echo "not on a mip-NNNN/* branch — pass the MIP: MIP-0005" >&2; exit 1; }
  echo "$m"
}
base_for() {   # base branch of a task branch, or "" once that branch is gone
  local mip k prev; mip="$(mip_of "$1")"; k="$(task_of "$1")"
  [ -n "$mip" ] || { echo "not a task branch: $1" >&2; exit 1; }
  if [ "$k" -le 1 ]; then echo main; return; fi
  prev="$(branch_for "$mip" $((k - 1)))"
  [ -n "$prev" ] || prev="$(git branch -r --list "origin/$mip/$((k-1))-*" --format='%(refname:short)' | head -1 | sed 's#^origin/##')"
  printf '%s\n' "$prev"
}

# `sed 1d` drops uprd's dry-run banner, and nothing else: the body's `Closes #N` (#512) must survive.
new_pr_body() {   # <base>
  echo "Stacked on \`$1\` — part of ${cur%%/*}. Merge order: base first."; echo
  "${STACK_SELFTEST_UPRD:-scripts/uprd.sh}" --dry-run 2>/dev/null | sed '1d'
}

pr_window=200
merged_head_sha() {   # head commit of the merged PR for a branch prefix; it outlives the branch GitHub deletes
  # gh orders by PR number, so .[0] is the newest-*opened* match; PREFIX goes via the env, unquotable otherwise.
  PREFIX="$1" gh pr list --state merged --limit "$pr_window" --json headRefName,headRefOid \
    --jq '[.[] | select(.headRefName | startswith(env.PREFIX))] | .[0].headRefOid // empty' 2>/dev/null || true
}

# Where `rebase --onto` must cut. The trap: a squash-merge rewrites the base's commits (so patch-id
# skipping cannot tell they are already upstream) and GitHub deletes the base branch, leaving
# merge-base nothing to answer with. Local `main` is never fetched, so origin/main stands in for it.
fork_point_for() {
  local cur="$1" base="$2" mip k
  if [ "$base" = main ]; then base=origin/main; fi
  if [ -n "$base" ] && git rev-parse --verify -q "$base^{commit}" >/dev/null; then
    git merge-base "$cur" "$base"; return
  fi
  mip="$(mip_of "$cur")"; k="$(task_of "$cur")"
  [ -n "$k" ] && [ "$k" -gt 1 ] || { git merge-base "$cur" origin/main; return; }
  merged_head_sha "$mip/$((k - 1))-"
}

self_test() {
  local failed=0 tmp self repo fork unrelated upstream got out rc
  local pr_origin pr_work cost_fill_stub local_head remote_head
  local fp_origin fp_work upstream_ref
  self="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  tmp="$(mktemp -d)"; trap "rm -rf $(printf %q "$tmp")" EXIT
  check() { if [ "$2" = "$3" ]; then echo "ok: $1"; else echo "FAILED: $1"; echo "  got:  $2"; echo "  want: $3"; failed=1; fi; }
  has()   { case "$2" in *"$3"*) echo "ok: $1" ;; *) echo "FAILED: $1 — expected \"$3\" in:"; sed 's/^/  /' <<<"$2"; failed=1 ;; esac; }
  lacks() { case "$2" in *"$3"*) echo "FAILED: $1 — did not expect \"$3\" in:"; sed 's/^/  /' <<<"$2"; failed=1 ;; *) echo "ok: $1" ;; esac; }

  echo "-- branch parsing --"
  check "mip from a task branch" "$(mip_of mip-0063/6-board-and-claim)" "mip-0063"
  check "task number from a task branch" "$(task_of mip-0063/6-board-and-claim)" "6"
  check "a non-task branch yields nothing" "$(mip_of chore/whatever)" ""
  check "task 1 bases on main" "$(base_for mip-0063/1-taxonomy)" "main"

  # A throwaway stack: main <- mip-9999/5-prev (merged, branch deleted) <- mip-9999/6-board. The other
  # branches are traps: 50- collides with the 5 prefix, 8- shares no history, origin/main outruns main.
  repo="$tmp/repo"
  { git init -q -b main "$repo"
    git -C "$repo" config user.email stack@example.invalid
    git -C "$repo" config user.name  stack-self-test
    git -C "$repo" commit -q --allow-empty -m "main"
    git -C "$repo" update-ref refs/remotes/origin/main "$(git -C "$repo" rev-parse HEAD)"
    git -C "$repo" checkout -q -b mip-9999/50-collision
    git -C "$repo" commit -q --allow-empty -m "an unrelated branch"
    unrelated="$(git -C "$repo" rev-parse HEAD)"
    git -C "$repo" checkout -q -b mip-9999/5-prev main
    git -C "$repo" commit -q --allow-empty -m "task 5"
    fork="$(git -C "$repo" rev-parse HEAD)"
    git -C "$repo" checkout -q -b mip-9999/6-board
    git -C "$repo" commit -q --allow-empty -m "task 6"
    git -C "$repo" checkout -q -b mip-9999/1-solo main
    git -C "$repo" commit -q --allow-empty -m "main moved on, and only origin/main knows"
    git -C "$repo" update-ref refs/remotes/origin/main "$(git -C "$repo" rev-parse HEAD)"
    upstream="$(git -C "$repo" rev-parse HEAD)"
    git -C "$repo" commit -q --allow-empty -m "task 1"
    git -C "$repo" checkout -q -b mip-9999/9-cur main
    git -C "$repo" commit -q --allow-empty -m "task 9"
    git -C "$repo" checkout -q --orphan mip-9999/8-unrelated-base
    git -C "$repo" commit -q --allow-empty -m "a base sharing no history"
    git -C "$repo" checkout -q main
    git -C "$repo" branch -D mip-9999/5-prev mip-9999/50-collision; } >/dev/null 2>&1

  mkdir -p "$tmp/bin"
  cat > "$tmp/bin/gh" <<GH
#!/usr/bin/env bash
# a stand-in for \`gh pr list\`: newest PR first, and one entry collides on a hyphen-less 5 prefix.
case "\$*" in
  *"pr list"*)
    while read -r b sha; do case "\$b" in "\$PREFIX"*) echo "\$sha"; exit 0 ;; esac; done <<'EOF'
mip-9999/50-collision $unrelated
mip-9999/5-prev $fork
EOF
    exit 0 ;;
  *) exit 1 ;;
esac
GH
  chmod +x "$tmp/bin/gh"
  drive_as() { local b="$1"; shift; ( cd "$repo" && PATH="$tmp/bin:$PATH" STACK_SELFTEST_BRANCH="$b" bash "$self" --dry-run restack "$@" 2>&1 ); }
  drive() { drive_as mip-9999/6-board "$@"; }

  echo
  echo "-- fork_point_for falls back to the merged PR head --"
  got="$(PATH="$tmp/bin:$PATH" merged_head_sha "mip-9999/5-")"
  check "merged_head_sha reads the PR that outlived its branch" "$got" "$fork"
  got="$(PATH="$tmp/bin:$PATH" fork_point_for mip-9999/6-board "")"
  check "fork_point_for uses it when the base branch is gone" "$got" "$fork"
  got="$(cd "$repo" && fork_point_for mip-9999/1-solo main)"
  check "task 1 cuts at origin/main, never at a stale local main" "$got" "$upstream"

  echo
  echo "-- restack cuts at the fork point, never at main --"
  rc=0; out="$(drive)" || rc=$?
  check "it succeeds once the merged PR names the fork point" "$rc" "0"
  check "and cuts exactly there" "$(grep -F -- '+ git rebase' <<<"$out" || true)" \
    "+ git rebase --onto origin/main $fork mip-9999/6-board"
  has "then force-pushes with a lease" "$out" "+ git push -q --force-with-lease origin mip-9999/6-board"

  { echo '#!/usr/bin/env bash'; echo 'exit 1'; } > "$tmp/bin/gh"   # from here on: no gh, no merged PR to find
  rc=0; out="$(drive --onto-base "$fork")" || rc=$?
  check "--onto-base overrides the lookup entirely" "$rc" "0"
  check "and is used verbatim as the cut" "$(grep -F -- '+ git rebase' <<<"$out" || true)" \
    "+ git rebase --onto origin/main $fork mip-9999/6-board"

  echo
  echo "-- restack refuses rather than replaying merged commits --"
  rc=0; out="$(drive)" || rc=$?
  check "it exits non-zero instead of rebasing blind" "$rc" "1"
  has "and names the override that unblocks it" "$out" "--onto-base"
  has "and says it could not find the fork point" "$out" "cannot tell where"
  lacks "no bare rebase onto main was planned" "$out" "git rebase origin/main"

  rc=0; out="$(drive --onto-base "$unrelated")" || rc=$?
  check "an --onto-base off another branch is refused" "$rc" "1"
  has "and is called out as not being an ancestor" "$out" "is not an ancestor of"
  lacks "with nothing rebased" "$out" "+ git rebase"

  rc=0; out="$(drive --onto-base 0000000000000000000000000000000000000000)" || rc=$?
  check "an --onto-base that is not a commit is refused" "$rc" "1"
  has "as an unfindable fork point, not a misplaced one" "$out" "cannot tell where"

  # A present base that shares no history: merge-base fails silently, which set -e made fatal before the refusal.
  rc=0; out="$(drive_as mip-9999/9-cur)" || rc=$?
  check "a base sharing no history is refused" "$rc" "1"
  has "out loud, rather than dying inside set -e" "$out" "cannot tell where"

  echo
  echo "-- new_pr_body --"
  printf '#!/bin/sh\necho "----- uprd dry run: banner"\necho "<!-- uprd: marker -->"\necho "Closes #512"\n' >"$tmp/uprd"
  chmod +x "$tmp/uprd"
  out="$(STACK_SELFTEST_UPRD="$tmp/uprd" new_pr_body main)"
  has "a new PR's body keeps uprd's Closes line after the Stacked-on prefix" "$out" "Closes #512"
  lacks "and drops only uprd's dry-run banner" "$out" "uprd dry run"

  echo
  echo "-- pr force-pushes when cost-fill rewrites an already-pushed commit (#524) --"
  # A stub stands in for cost-fill.sh (STACK_SELFTEST_COST_FILL): it deterministically amends
  # HEAD, so this proves the push survives a rewrite without needing the real cost-fill.sh's
  # git >= 2.45 cherry-pick flags (this host's git may be older).
  pr_origin="$tmp/pr-origin.git"; pr_work="$tmp/pr-work"
  { git init -q --bare "$pr_origin"
    git init -q -b main "$pr_work"
    git -C "$pr_work" config user.email pr@example.invalid
    git -C "$pr_work" config user.name pr-self-test
    git -C "$pr_work" remote add origin "$pr_origin"
    git -C "$pr_work" commit -q --allow-empty -m main
    git -C "$pr_work" push -q origin main
    git -C "$pr_work" checkout -q -b mip-9999/1-solo
    git -C "$pr_work" commit -q --allow-empty -m "task 1"
    git -C "$pr_work" push -q -u origin mip-9999/1-solo; } >/dev/null 2>&1
  cost_fill_stub="$tmp/cost-fill-stub"
  cat >"$cost_fill_stub" <<'SH'
#!/usr/bin/env bash
set -e
echo "$*" >>"${STUB_ARGS_LOG:-/dev/null}"
[ "${1:-}" = "--dry-run" ] && { echo "cost-fill --dry-run: would rewrite these commits on stub:"; exit 0; }
git commit -q --amend --allow-empty -m "$(git log -1 --format=%B)

stub-cost-fill-rewrote-this"
SH
  chmod +x "$cost_fill_stub"
  cat >"$tmp/bin/gh" <<'GH'
#!/usr/bin/env bash
case "$*" in
  *"pr view"*) exit 1 ;;
  *"pr create"*) echo "https://example.invalid/pull/1" ;;
  *) exit 1 ;;
esac
GH
  chmod +x "$tmp/bin/gh"
  rc=0
  out="$(cd "$pr_work" && PATH="$tmp/bin:$PATH" STACK_SELFTEST_COST_FILL="$cost_fill_stub" \
    STACK_SELFTEST_UPRD="$tmp/uprd" bash "$self" pr 2>&1)" || rc=$?
  check "the push exits 0 despite the already-pushed commit being rewritten" "$rc" "0"
  local_head="$(git -C "$pr_work" rev-parse mip-9999/1-solo)"
  remote_head="$(git --git-dir="$pr_origin" rev-parse mip-9999/1-solo)"
  check "origin ends up with the rewritten commit (force-with-lease, not a rejected push)" \
    "$remote_head" "$local_head"

  echo
  echo "-- pr still sets upstream on a brand-new branch's first push (#524) --"
  # The common case: a task branch's very first scripts/stack.sh pr, never pushed before, where
  # cost-fill almost always rewrites something (a bare Cost:/Tested: placeholder, at least). The
  # force-with-lease branch must keep -u too, or the branch ends up with no upstream configured.
  fp_origin="$tmp/fp-origin.git"; fp_work="$tmp/fp-work"
  { git init -q --bare "$fp_origin"
    git init -q -b main "$fp_work"
    git -C "$fp_work" config user.email fp@example.invalid
    git -C "$fp_work" config user.name fp-self-test
    git -C "$fp_work" remote add origin "$fp_origin"
    git -C "$fp_work" commit -q --allow-empty -m main
    git -C "$fp_work" push -q origin main
    git -C "$fp_work" checkout -q -b mip-9999/1-fresh
    git -C "$fp_work" commit -q --allow-empty -m "task 1"; } >/dev/null 2>&1
  rc=0
  out="$(cd "$fp_work" && PATH="$tmp/bin:$PATH" STACK_SELFTEST_COST_FILL="$cost_fill_stub" \
    STACK_SELFTEST_UPRD="$tmp/uprd" bash "$self" pr 2>&1)" || rc=$?
  check "the first-ever push still exits 0" "$rc" "0"
  rc=0
  upstream_ref="$(git -C "$fp_work" rev-parse --abbrev-ref mip-9999/1-fresh@\{upstream\} 2>&1)" || rc=$?
  check "and @{upstream} ends up set despite the force-with-lease branch" "$rc" "0"
  check "to origin's copy of the branch" "$upstream_ref" "origin/mip-9999/1-fresh"

  echo
  echo "-- pr hands cost-fill the task's own base, not origin/main (#524) --"
  { git -C "$pr_work" checkout -q -b mip-9999/2-next mip-9999/1-solo
    git -C "$pr_work" commit -q --allow-empty -m "task 2"
    git -C "$pr_work" fetch -q origin; } >/dev/null 2>&1
  out="$(cd "$pr_work" && PATH="$tmp/bin:$PATH" STACK_SELFTEST_COST_FILL="$cost_fill_stub" \
    STUB_ARGS_LOG="$tmp/stub-args" STACK_SELFTEST_UPRD="$tmp/uprd" bash "$self" --dry-run pr 2>&1)" || true
  check "task 2's fork point is task 1's pushed tip" "$(cat "$tmp/stub-args" 2>/dev/null)" \
    "--dry-run --base $(git -C "$pr_work" rev-parse origin/mip-9999/1-solo)"

  echo
  if [ "$failed" -eq 1 ]; then echo "stack self-test: FAILED" >&2; return 1; fi
  echo "stack self-test: ok"
}

[ "$selftest" -eq 1 ] && { self_test; exit 0; }

case "${1:-}" in
  start)
    mip="$(tr 'A-Z' 'a-z' <<<"${2:?MIP-NNNN}")"; k="${3:?task number}"; slug="${4:?slug}"
    new="$mip/$k-$slug"
    if [ "$k" -le 1 ]; then base=main; else base="$(base_for "$new")"; fi
    run git fetch -q origin
    if [ "$base" = main ]; then run git checkout -q -b "$new" origin/main; else run git checkout -q -b "$new" "$base"; fi
    echo "started $new (base: $base)"
    ;;
  pr)
    base="$(base_for "$cur")"
    # cost-fill.sh runs here, not in scripts/pr.sh (#524): this is the documented primary path
    # for a mip task branch (.claude/skills/mip-tasks/SKILL.md), and it decides on its own
    # whether anything needs a trailer upgrade or a Closes #N line. A rewritten HEAD needs
    # --force-with-lease, same as restack below, since the branch may already be pushed.
    cost_fill="${STACK_SELFTEST_COST_FILL:-scripts/cost-fill.sh}"
    # The parent's pushed tip, since that is what the PR diffs against; without it cost-fill's
    # origin/main range would re-amend the parent's commits and see the parent's Closes line.
    cf_ref="$base"; [ -z "$base" ] || [ "$base" = main ] || cf_ref="origin/$base"
    cf_base="$(fork_point_for "$cur" "$cf_ref")" || cf_base=""
    cf_args=(); [ -z "$cf_base" ] || cf_args=(--base "$cf_base")
    if [ "$dry" -eq 1 ]; then
      echo "+ $cost_fill ${cf_args[*]}"
      cf_out="$("$cost_fill" --dry-run "${cf_args[@]}")"
      echo "$cf_out"
      if grep -q '^cost-fill --dry-run: would rewrite' <<<"$cf_out"; then
        push=(git push -q -u --force-with-lease origin "$cur")
      else
        push=(git push -q -u origin "$cur")
      fi
    else
      before_head="$(git rev-parse HEAD)"
      "$cost_fill" "${cf_args[@]}"
      if [ "$before_head" != "$(git rev-parse HEAD)" ]; then
        push=(git push -q -u --force-with-lease origin "$cur")
      else
        push=(git push -q -u origin "$cur")
      fi
    fi
    run "${push[@]}"
    if gh pr view "$cur" --json number -q .number >/dev/null 2>&1; then
      run gh pr edit "$cur" --base "$base"
      run scripts/uprd.sh
    else
      title="$(git log --reverse --format=%s "origin/$base..HEAD" 2>/dev/null | head -1)"
      [ -n "$title" ] || title="$(git log -1 --format=%s)"
      body="$(mktemp)"; new_pr_body "$base" > "$body"
      run gh pr create --base "$base" --head "$cur" --title "$title" --body-file "$body"
    fi
    ;;
  restack)
    old_base="$(base_for "$cur")"
    run git fetch -q origin
    fork="${onto_base:-$(fork_point_for "$cur" "$old_base")}" || true   # a failed lookup must reach the guard, not set -e
    if [ -z "$fork" ] || ! git rev-parse --verify -q "$fork^{commit}" >/dev/null; then
      { echo "restack: cannot tell where $cur's own commits begin."
        echo "  Its base (${old_base:-the previous task branch}) yields no merge-base — GitHub deletes a branch when its PR merges —"
        echo "  and gh named no merged PR for $(mip_of "$cur")/$(( $(task_of "$cur") - 1 ))-* within the last $pr_window merged ones"
        echo "  (it may equally be unavailable or logged out here — a jail has no gh login)."
        echo "  Rebasing onto main without that point would replay the already-merged commits and conflict on each."
        echo "  Pass the previous task branch's last commit yourself:"
        echo "    scripts/stack.sh restack --onto-base <sha>"; } >&2
      exit 1
    fi
    if ! git merge-base --is-ancestor "$fork" "$cur" 2>/dev/null; then
      { echo "restack: ${fork:0:7} is not an ancestor of $cur, so it cannot be where its commits begin."
        echo "  Cutting there would replay unrelated history onto main and force-push the result."
        echo "  Pass the previous task branch's last commit instead:"
        echo "    scripts/stack.sh restack --onto-base <sha>"; } >&2
      exit 1
    fi
    run git rebase --onto origin/main "$fork" "$cur"
    run git push -q --force-with-lease origin "$cur"
    echo "restacked $cur onto main (cut at ${fork:0:7}${old_base:+, old base $old_base})"
    ;;
  status)
    mip="${2:-$(mip_of "$cur")}"; mip="$(tr 'A-Z' 'a-z' <<<"$mip")"
    for b in $(git branch --list "$mip/*" --format='%(refname:short)' | sort -t/ -k2 -n); do
      pr="$(gh pr view "$b" --json number,state -q '"#\(.number) \(.state)"' 2>/dev/null || echo "no PR")"
      printf '%-40s base=%-28s %s  (%s ahead of main)\n' "$b" "$(base_for "$b")" "$pr" "$(git rev-list --count origin/main.."$b")"
    done
    ;;
  branches)
    branches_of "$(mip_arg "${2:-}")"
    ;;
  link)
    # GitHub's native Stack (the "Preview stack" box on a PR) through the official extension.
    # stack_link (scripts/lib/stack_link.sh, shared with scripts/deps-stack.sh) does the open-PR
    # filtering and the actual `gh stack link` call.
    mip="$(mip_arg "${2:-}")"
    # shellcheck source=scripts/lib/stack_link.sh.
    source "$(dirname "${BASH_SOURCE[0]}")/lib/stack_link.sh"
    mapfile -t brs < <(branches_of "$mip")
    stack_link "$dry" "${brs[@]}"
    ;;
  *) awk 'NR > 1 { if (!/^#/) exit; print }' "$0"; exit 1 ;;
esac
