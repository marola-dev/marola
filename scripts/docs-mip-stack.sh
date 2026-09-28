#!/usr/bin/env bash
# docs-mip-stack — find pending, un-merged docs/mip-NNNN-* design-doc branches and chain the ones
# you pick into a base-linked stack of gh pr create commands, so several independent MIP drafts
# can be reviewed and merged together in one pass. scripts/docs-mip-stack.sh list # discover
# candidates, flag duplicates/staleness scripts/docs-mip-stack.sh plan B1 B2 B3 ... # print (and
# log) chained `gh pr create` commands # for exactly these branches, in this order
# scripts/docs-mip-stack.sh auto # default when run with no args — see below --dry-run has no
# effect on `list` (already read-only); on `plan`/`auto` it skips both pushing and the
# GH_POST_MORTEM.md append, so you can preview what would happen.
set -euo pipefail

usage() {
  cat <<'EOF'
usage: docs-mip-stack.sh [auto]
       docs-mip-stack.sh list
       docs-mip-stack.sh plan <branch> [<branch> ...]
       docs-mip-stack.sh --self-test
       (any subcommand accepts --dry-run)

  auto    the default (bare `docs-mip-stack.sh` == `docs-mip-stack.sh auto`). Finds every LOCAL
          docs/mip-NNNN-*/mips/*/K-mip-NNNN-* branch that has never been pushed (no upstream) and
          whose MIP number has exactly one candidate branch in the whole world (this one — no
          origin candidate, no other local unpushed one). Pushes each such branch
          (`git push -u origin`, never --force) and plans it exactly like `plan` below — locally
          chained branches (one a git ancestor of the next) as one stack, unrelated ones each
          against `main`. Any MIP number with more than one candidate anywhere is reported and
          left untouched, same judgment as `list` — auto never guesses which branch is canonical.
          Then REPORTS (read-only, never pushes or rebases) every already-pushed, un-merged,
          unambiguous candidate that shares a fork point with another one — parallel drafts off
          the same main commit, the shape that conflicts in docs/MIPs/README.md — with the exact
          `plan` command to chain them in MIP-number order.

  list    scan `origin` for un-merged docs/mip-NNNN-*  and mips/*/K-mip-NNNN-* branches, group by
          MIP number, flag any number with more than one candidate branch (pick one yourself —
          this script never guesses), and flag any candidate whose diff against origin/main
          touches a file outside docs/MIPs/** or docs/index.md as "stale — rebase onto main
          first" rather than including it in a stack blindly.

  plan    given an explicit, ordered list of branches (your own choice — usually list's output
          after you've resolved any duplicates), verify each is conflict-free against its base
          (branch 1 vs main, branch 2 vs branch 1, ...) via `git merge-tree` (read-only, nothing
          is rebased or pushed) and print the chained `gh pr create` commands, appending them to
          GH_POST_MORTEM.md at the repo root (gitignored, never overwritten) the same way every
          other push-only workflow this session logs commands for gh's benefit once authenticated.

  --self-test   the branch-name parsing / MIP-number grouping logic, no network, no git needed
EOF
}

# --- pure parsing, self-tested without git/network ------------------------------------------.
mip_of() { sed -n 's#^docs/\(mip-[0-9]\{4\}\)-.*#\1#p; s#.*/[0-9]\{1,\}-\(mip-[0-9]\{4\}\)-.*#\1#p' <<<"$1" | head -1; }
is_candidate_branch() {
  [[ "$1" =~ ^docs/mip-[0-9]{4}-.+$ ]] || [[ "$1" =~ ^mips/[0-9-]+/[0-9]+-mip-[0-9]{4}-.+$ ]]
}

# Order branch names by their MIP number (not lexicographically by branch name, which would sort
# `mips/2026-09-07/2-mip-0035-…` next to `docs/mip-0005-…` by its leading path segment).
sort_by_mip() {
  local b m
  while IFS= read -r b; do
    [ -n "$b" ] || continue
    m="$(mip_of "$b")"
    [ -n "$m" ] || continue
    printf '%s\t%s\n' "$m" "$b"
  done | sort -t$'\t' -k1,1 | cut -f2
}

# Reads "<count>\t<branch>" lines, writes the branches whose MIP number has exactly one candidate
# in the whole world, in MIP-number order.
single_candidates() {
  awk -F'\t' '$1 == 1 { print $2 }' | sort_by_mip
}

self_test() {
  local fails=0
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1', want '$2'"; fails=$((fails+1)); fi }
  ok "$(mip_of docs/mip-0034-rss-feeds)" "mip-0034" "mip_of parses a plain docs/mip-NNNN-* branch"
  ok "$(mip_of mips/2026-09-07/2-mip-0035-map-plugin-api)" "mip-0035" "mip_of parses a mips/YYYY-MM-DD/K-mip-NNNN-* branch"
  ok "$(is_candidate_branch docs/mip-0041-soft-book-ingestion && echo yes || echo no)" "yes" "a plain docs/mip-NNNN-* branch is a candidate"
  ok "$(is_candidate_branch mips/2026-09-06/1-mip-0020-instagram-bot && echo yes || echo no)" "yes" "a stacked mips/YYYY-MM-DD/K-mip-NNNN-* branch is a candidate"
  ok "$(is_candidate_branch mip-0025/3-dataset-scale-layer1 && echo yes || echo no)" "no" "an implementation task branch (mip-NNNN/k-*) is NOT a docs candidate"
  ok "$(is_candidate_branch main && echo yes || echo no)" "no" "main is never a candidate"
  ok "$(printf '%s\n' docs/mip-0046-remove-ai-slop-ui docs/mip-0044-site-sections docs/mip-0045-nlp-parsing-over-llm | sort_by_mip | tr '\n' ' ')" \
     "docs/mip-0044-site-sections docs/mip-0045-nlp-parsing-over-llm docs/mip-0046-remove-ai-slop-ui " \
     "sort_by_mip orders parallel drafts by MIP number, not by branch name"
  ok "$(printf '%s\n' mips/2026-09-07/2-mip-0035-map-plugin-api docs/mip-0005-map-site | sort_by_mip | tr '\n' ' ')" \
     "docs/mip-0005-map-site mips/2026-09-07/2-mip-0035-map-plugin-api " \
     "sort_by_mip sorts a stacked mips/… branch by its MIP number, not its path prefix"
  ok "$(printf '%s\n' main docs/mip-0044-site-sections | sort_by_mip | tr '\n' ' ')" \
     "docs/mip-0044-site-sections " "sort_by_mip drops a non-candidate branch name"
  ok "$(printf '1\tdocs/mip-0046-remove-ai-slop-ui\n2\tdocs/mip-0021-beach-accessibility\n1\tdocs/mip-0044-site-sections\n' | single_candidates | tr '\n' ' ')" \
     "docs/mip-0044-site-sections docs/mip-0046-remove-ai-slop-ui " \
     "single_candidates keeps only MIP numbers with exactly one candidate, in number order"
  ok "$(printf '3\tdocs/mip-0021-beach-accessibility\n' | single_candidates | tr '\n' ' ')" "" \
     "single_candidates keeps nothing when every candidate's MIP number is ambiguous"
  if [ "$fails" -eq 0 ]; then echo "docs-mip-stack self-test: ok"; else echo "docs-mip-stack self-test: $fails failure(s)" >&2; exit 1; fi
}

# --- list ------------------------------------------------------------------------------------- A
# branch's real content diff against origin/main is only its docs/MIPs files if `git diff
# --name-only origin/main..<branch>` touches nothing outside that allow-list — anything else means
# the branch forked from an older main and carries unrelated stale content (the exact failure mode
# found and fixed by hand for MIP-0034/35/36 earlier this session).
is_stale() {
  local branch="$1" f fork
  # The branch's OWN merge-base with current main, not main's current tip — main moves fast in
  # this repo (many unrelated PRs land daily), so diffing against its current tip would flag every
  # branch as "stale" purely because main grew new files after the branch forked.
  fork="$(git merge-base origin/main "origin/$branch" 2>/dev/null || true)"
  [ -n "$fork" ] || return 0
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in
      docs/MIPs/*|docs/index.md) ;;
      *) return 0 ;;
    esac
  done < <(git diff --name-only "$fork..origin/$branch" 2>/dev/null)
  return 1
}

list_cmd() {
  git fetch -q origin
  local branches
  branches="$(git branch -r --format='%(refname:short)' | sed 's#^origin/##' | while read -r b; do is_candidate_branch "$b" && echo "$b"; done || true)"
  local unmerged=()
  local b
  while IFS= read -r b; do
    [ -n "$b" ] || continue
    if git merge-base --is-ancestor "origin/$b" origin/main 2>/dev/null; then continue; fi
    unmerged+=("$b")
  done <<<"$branches"
  [ "${#unmerged[@]}" -ge 1 ] || { echo "no pending docs/mip-* branches"; return 0; }

  # Group by MIP number.
  declare -A by_mip
  for b in "${unmerged[@]}"; do
    local m; m="$(mip_of "$b")"
    [ -n "$m" ] || continue
    by_mip["$m"]="${by_mip[$m]:-}"$'\n'"$b"
  done

  echo "pending docs/mip-* branches, by MIP number:"
  for m in $(printf '%s\n' "${!by_mip[@]}" | sort -t- -k2 -n); do
    local list=() count=0
    while IFS= read -r b; do [ -n "$b" ] || continue; list+=("$b"); count=$((count+1)); done <<<"${by_mip[$m]}"
    if [ "$count" -gt 1 ]; then
      echo "  ${m^^}: ${count} candidate branches — pick one, do not stack more than one per MIP number"
    else
      echo "  ${m^^}: 1 candidate"
    fi
    for b in "${list[@]}"; do
      if is_stale "$b"; then
        echo "    - $b  [stale — diff vs main touches non-doc files, rebase onto main first]"
      else
        echo "    - $b  [clean — diff vs main is doc-only]"
      fi
    done
  done
  echo
  echo "next: scripts/docs-mip-stack.sh plan <branch1> <branch2> ... (your own chosen order, one per MIP)"
}

# --- plan -------------------------------------------------------------------------------------.
plan_cmd() {
  local dry="$1"; shift
  [ "$#" -ge 1 ] || { echo "plan needs at least one branch — see --help" >&2; exit 1; }
  git fetch -q origin
  local base="main" b out
  local -a log_lines=()
  for b in "$@"; do
    git rev-parse --verify -q "origin/$b" >/dev/null || { echo "no such branch on origin: $b" >&2; exit 1; }
    if out="$(git merge-tree --write-tree "origin/$base" "origin/$b" 2>&1)"; then
      if grep -qi 'CONFLICT' <<<"$out"; then
        echo "CONFLICT: $b vs $base — resolve by hand before including it in the stack" >&2
        exit 1
      fi
    else
      echo "CONFLICT: $b vs $base — resolve by hand before including it in the stack" >&2
      exit 1
    fi
    local title
    # First (oldest) commit's subject since the base, matching scripts/uprd.sh's own PR-title
    # convention — not the branch tip's, which can drift from the branch's own real topic across
    # several commits (e.g. a later "fix typo" commit).
    title="$(git log --reverse --format=%s "origin/$base..origin/$b" 2>/dev/null | head -1)"
    [ -n "$title" ] || title="$(git log -1 --format=%s "origin/$b" 2>/dev/null)"
    echo "clean: $b (base: $base)"
    local cmd="gh pr create --base $base --head $b \\
  --title \"$title\" \\
  --body-file <(git log -1 --format=%B $b)"
    echo "$cmd"
    echo
    log_lines+=("$cmd")
    base="$b"
  done
  if [ "$dry" -eq 0 ]; then
    local repo_root; repo_root="$(git rev-parse --show-toplevel)"
    {
      echo
      echo "## $(date +%F) — docs-mip-stack: $* "
      echo
      echo "Generated by \`scripts/docs-mip-stack.sh plan\`. \`gh\` still not authenticated in this sandbox."
      echo
      echo '```bash'
      local first=1
      for cmd in "${log_lines[@]}"; do
        [ "$first" -eq 1 ] || echo
        echo "$cmd"
        first=0
      done
      echo '```'
    } >> "$repo_root/GH_POST_MORTEM.md"
    echo "logged to $repo_root/GH_POST_MORTEM.md"
  fi
}

# --- auto -------------------------------------------------------------------------------------
# Pushes exactly one branch (never --force) and hands it to plan_cmd, unless dry, in which case
# nothing touches the network — just prints what auto would have done.
run_chain() {
  local dry="$1"; shift
  if [ "$dry" -eq 1 ]; then
    echo "auto (dry-run): would push and plan as one stack: $*"
    return 0
  fi
  local b
  for b in "$@"; do
    echo "auto: pushing $b"
    git push -u origin "$b"
  done
  plan_cmd 0 "$@"
}

# The second half of auto's job, and the read-only one: branches that are ALREADY pushed. MIP-0044.
report_pushed_groups() {
  local -n counts_ref="$1"   # MIP number -> total candidate count (origin + local)
  local -a singles=()
  local b m fork
  while IFS= read -r b; do [ -n "$b" ] && singles+=("$b"); done < <(
    while IFS= read -r b; do
      [ -n "$b" ] || continue
      is_candidate_branch "$b" || continue
      git merge-base --is-ancestor "origin/$b" origin/main 2>/dev/null && continue
      m="$(mip_of "$b")"
      [ -n "$m" ] || continue
      printf '%s\t%s\n' "${counts_ref[$m]:-0}" "$b"
    done < <(git branch -r --format='%(refname:short)' | sed 's#^origin/##') | single_candidates
  )
  [ "${#singles[@]}" -ge 1 ] || return 0

  # Group by fork point, keeping each group in MIP-number order (singles is already sorted).
  declare -A by_fork
  for b in "${singles[@]}"; do
    fork="$(git merge-base origin/main "origin/$b" 2>/dev/null || true)"
    [ -n "$fork" ] || continue
    by_fork["$fork"]="${by_fork[$fork]:-}${b}"$'\n'
  done

  for fork in "${!by_fork[@]}"; do
    local -a group=()
    while IFS= read -r b; do [ -n "$b" ] && group+=("$b"); done <<<"${by_fork[$fork]}"
    [ "${#group[@]}" -ge 2 ] || continue
    # Already chained?.
    local chained=1 i
    for (( i=1; i<${#group[@]}; i++ )); do
      git merge-base --is-ancestor "origin/${group[i-1]}" "origin/${group[i]}" 2>/dev/null || { chained=0; break; }
    done
    echo   # one blank line before every group, including the first (it follows the local half)
    if [ "$chained" -eq 1 ]; then
      echo "auto: ${#group[@]} already-chained pushed branches (fork point ${fork:0:8}) — ready to plan:"
    else
      echo "auto: ${#group[@]} ready-to-chain pushed branches (fork point ${fork:0:8}), MIP-number order recommended:"
    fi
    for b in "${group[@]}"; do echo "    - $b  ($(mip_of "$b" | tr '[:lower:]' '[:upper:]'))"; done
    echo "  run: scripts/docs-mip-stack.sh plan ${group[*]}"
    [ "$chained" -eq 1 ] || echo "  (plan is read-only and will report CONFLICT until each branch is rebased onto its predecessor — auto never rebases or force-pushes a published branch)"
  done
}

auto_cmd() {
  local dry="$1"
  git fetch -q origin

  # Every unmerged origin candidate, counted per MIP number.
  declare -A origin_count
  local ob m
  while IFS= read -r ob; do
    [ -n "$ob" ] || continue
    is_candidate_branch "$ob" || continue
    git merge-base --is-ancestor "origin/$ob" origin/main 2>/dev/null && continue
    m="$(mip_of "$ob")"
    [ -n "$m" ] || continue
    origin_count["$m"]=$(( ${origin_count[$m]:-0} + 1 ))
  done < <(git branch -r --format='%(refname:short)' | sed 's#^origin/##')

  # Local branches with no upstream at all — genuinely never pushed, not just "ahead of origin".
  local -a local_unpushed=()
  local lb up
  while IFS=$'\t' read -r lb up; do
    [ -n "$lb" ] || continue
    is_candidate_branch "$lb" || continue
    [ -z "$up" ] || continue
    local_unpushed+=("$lb")
  done < <(git for-each-ref --format=$'%(refname:short)\t%(upstream:short)' refs/heads/)

  declare -A local_count
  local b
  for b in "${local_unpushed[@]:-}"; do
    [ -n "$b" ] || continue
    m="$(mip_of "$b")"
    [ -n "$m" ] || continue
    local_count["$m"]=$(( ${local_count[$m]:-0} + 1 ))
  done

  # The one "is this MIP number unambiguous?" count, shared by the local-unpushed half below and
  # the already-pushed report — same rule, computed once.
  declare -A total_count
  for m in "${!origin_count[@]}"; do total_count["$m"]=$(( ${origin_count[$m]:-0} + ${local_count[$m]:-0} )); done
  for m in "${!local_count[@]}"; do total_count["$m"]=$(( ${origin_count[$m]:-0} + ${local_count[$m]:-0} )); done

  [ "${#local_unpushed[@]}" -ge 1 ] || {
    echo "auto: no local unpushed docs/mip-* branches"
    report_pushed_groups total_count
    return 0
  }

  local -a eligible=()
  for b in "${local_unpushed[@]}"; do
    m="$(mip_of "$b")"
    [ -n "$m" ] || continue
    local total=${total_count[$m]:-0}
    if [ "$total" -gt 1 ]; then
      echo "auto: skipping $b (${m^^}) — $total candidate(s) for this MIP number across origin+local, resolve by hand: scripts/docs-mip-stack.sh list"
    else
      eligible+=("$b")
    fi
  done
  [ "${#eligible[@]}" -ge 1 ] || {
    echo "auto: nothing unambiguous to stack locally — every local unpushed candidate's MIP number has another candidate elsewhere"
    report_pushed_groups total_count
    return 0
  }

  # Order by commit time so a real local stack (each branch built on the previous) chains
  # naturally via the ancestry check below; genuinely independent branches fall out as their own
  # one-branch chain, planned against main.
  local -a ordered=()
  while IFS= read -r b; do [ -n "$b" ] && ordered+=("$b"); done < <(
    for b in "${eligible[@]}"; do printf '%s\t%s\n' "$(git log -1 --format=%ct "$b")" "$b"; done | sort -n | cut -f2
  )

  local -a chain=()
  local prev=""
  for b in "${ordered[@]}"; do
    if [ -n "$prev" ] && git merge-base --is-ancestor "$prev" "$b" 2>/dev/null; then
      chain+=("$b")
    else
      [ "${#chain[@]}" -ge 1 ] && run_chain "$dry" "${chain[@]}"
      chain=("$b")
    fi
    prev="$b"
  done
  [ "${#chain[@]}" -ge 1 ] && run_chain "$dry" "${chain[@]}"
  report_pushed_groups total_count
}

dry=0; args=()
for a in "$@"; do [ "$a" = "--dry-run" ] && dry=1 || args+=("$a"); done
set -- "${args[@]}"

case "${1:-auto}" in
  auto) [ "${1:-}" = "auto" ] && shift; auto_cmd "$dry" ;;
  list) list_cmd ;;
  plan) shift; plan_cmd "$dry" "$@" ;;
  --self-test) self_test ;;
  --help|-h) usage ;;
  *) usage >&2; exit 1 ;;
esac
