#!/usr/bin/env bash
# mip-stack — stack every open MIP *draft* PR (the `docs/mip-NNNN-*` branches that add or edit a
# `docs/mips/MIP-NNNN-*.md`) into one GitHub Stack, the same shape `scripts/deps-stack.sh` gives
# dependabot's bumps and `scripts/stack.sh` gives one MIP's task branches. Different proposals,
# one chain, merged bottom-up in one CI run — and, more to the point, one chain that *merges*:
# every draft appends its own row to `docs/mips/README.md` at the same spot, so after the first
# draft lands every other one conflicts on that line. This script resolves that shape by itself.
#
#   just mip-stack                    # discover, build the local chain, publish, link
#   just mip-stack --dry-run          # print every git/gh command; no push, no gh mutation
#   just mip-stack --resume           # continue after a conflict this script could not resolve
#   just mip-stack --skip 123         # drop PR #123 from the chain (repeatable)
#   just mip-stack --from-json f.json # use this file instead of `gh pr list` (testing, no gh)
#   just mip-stack status             # print the current chain + each PR's state
#   just mip-stack clean              # delete mips/* chain branches whose stacked PR is MERGED
#   just mip-stack --self-test        # parse scripts/fixtures/mip-stack-prs.json, assert order
#
# What counts as a MIP draft PR (discovery, 1. below): an open PR whose head is `docs/mip-*`, or
# whose changed files include `docs/mips/MIP-NNNN-<slug>.md`, and whose head is NOT a task branch
# (`mip-NNNN/<k>-*` — those are implementation stacks, `scripts/stack.sh`'s job, and they touch
# the MIP file to mark tasks done). Order: by MIP number ascending (from the file, else from the
# branch name), PR number as the tie-break — so the chain reads like the index.
#
# Isolation: the whole local chain is built in a dedicated worktree, `.tmp/wt-mip-stack`, never
# the caller's own checkout (same design as deps-stack.sh — see its header for the reasoning);
# `status`/`clean`/`--resume` never switch the caller's branch either.
#
# ---------------------------------------------------------------------------------------------
# 1. Discovery: `gh pr list --state open --limit 100 --json number,headRefName,title,baseRefName,
#    mergeable,files`, filtered and ordered by order_prs (pure jq; the self-test runs it on the
#    fixture with no gh). Task branches and PRs that merely mention a MIP in prose are excluded.
# 2. Local chain build, in the worktree: branch k is `mips/<YYYY-MM-DD>/<k>-<slug>` (slug = the
#    head branch minus a leading `docs/`), created from branch k-1 (k=1 from `origin/main`), then
#    the PR's *own* commits are cherry-picked onto it — `git rev-list --reverse --no-merges
#    origin/<head> --not origin/main <every earlier draft's head>`: merge commits are skipped (a
#    draft that merged main or another draft to stay mergeable — the 2026-09-06 shape), commits
#    that belong to an earlier draft are excluded by ancestry, a rebased *copy* of a commit the
#    chain already carries (same author date and subject under a new SHA) is skipped, and a
#    cherry-pick that turns out empty is dropped. A conflict in
#    `docs/mips/README.md` — the two-rows-at-the-same-place shape — auto-resolves through
#    scripts/lib/mip_index_merge.py (union of both sides' rows, one per MIP, in number order; a
#    row that differs on both sides is a real edit and stops for a human), and a conflicted file
#    whose content in the commit is byte-identical to origin/main's ("the same change already
#    landed": squash-merged under another PR, or made on main too) takes main's version. A PR
#    whose MIP file(s) are already identical on main is skipped altogether as superseded — the
#    draft was squash-merged and its PR left open — recorded in the plan, shown by `status`,
#    never published; close that PR by hand. Anything else stops
#    with the branch left mid-cherry-pick in the worktree and prints the resolve steps; the plan
#    is cached at .tmp/mip-stack/plan.json so `--resume` continues the same chain.
# 3. Publish — option (b) of deps-stack.sh, for the same reason (a PR's head branch cannot be
#    moved after creation): one *new* PR per chain branch, `--base` the previous chain branch
#    (main for k=1), body from `BRANCH=… BASE=… scripts/uprd.sh --dry-run` plus a pointer to the
#    original PR, then the original PR is closed with a pointer comment. The original `docs/mip-*`
#    branches are left untouched, so nothing is lost if the chain is abandoned; delete them by
#    hand once the chain has merged. Then `gh stack link` over the open chain PRs
#    (scripts/lib/stack_link.sh). Retargeting the original PRs in place (force-push each
#    `docs/mip-*` branch to its chain content + `gh pr edit --base`) would keep the PR numbers
#    and their review threads — a reasonable follow-up, not implemented: it rewrites branches
#    other sessions may have checked out.
#
# Needs `gh auth status` for anything that isn't --dry-run/--from-json/--self-test/status/clean's
# local listing. Not available inside ai-jail (AGENTS.md) — run from the host.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/stack_link.sh.
source "$script_dir/lib/stack_link.sh"
# shellcheck source=scripts/lib/uprd_title.sh.
source "$script_dir/lib/uprd_title.sh"

plan_dir="$repo_root/.tmp/mip-stack"
plan_file="$plan_dir/plan.json"
wt_rel=".tmp/wt-mip-stack"
wt_dir="$repo_root/$wt_rel"

worktree_registered() { git -C "$repo_root" worktree list --porcelain | grep -qx "worktree $wt_dir"; }
remove_worktree() {
  if worktree_registered; then git -C "$repo_root" worktree remove --force "$wt_dir" 2>/dev/null || true; fi
  rm -rf "$wt_dir"
  git -C "$repo_root" worktree prune 2>/dev/null || true
}
ensure_worktree() {   # $1 = fresh|resume
  mkdir -p "$repo_root/.tmp"
  if [ "$1" = fresh ]; then
    if [ -d "$wt_dir" ] || worktree_registered; then
      echo "mip-stack: removing worktree at $wt_rel from a previous run"
      remove_worktree
    fi
    run git -C "$repo_root" worktree add --detach "$wt_dir" origin/main
  elif ! worktree_registered; then
    echo "mip-stack: --resume needs the worktree from the previous run at $wt_rel — it's missing; run 'just mip-stack' without --resume to start over" >&2
    exit 1
  fi
}

print_conflict_instructions() {   # $1 = branch left mid-cherry-pick
  echo
  echo "mip-stack: CONFLICT cherry-picking onto $1 in $wt_rel — resolve it:"
  echo "  cd $wt_rel && git status"
  echo "  # fix conflicts, then:"
  echo "  git add <files>"
  echo "  git cherry-pick --continue"
  echo "  cd -"
  echo "  just mip-stack --resume"
}

# The one conflict class this script resolves: docs/mips/README.md and nothing else conflicted,
# and mip_index_merge.py reduces every hunk to "both rows, in order".
auto_resolve_index() {
  local conflicted
  conflicted="$(git -C "$wt_dir" diff --name-only --diff-filter=U)"
  [ "$conflicted" = "docs/mips/README.md" ] || return 1
  local out
  if ! out="$(python3 "$script_dir/lib/mip_index_merge.py" "$wt_dir/docs/mips/README.md" 2>&1)"; then
    [ -z "$out" ] || echo "$out" >&2
    return 1
  fi
  [ -z "$out" ] || echo "$out"
  git -C "$wt_dir" add docs/mips/README.md
}

# Two conflict shapes resolve by themselves; anything else stops for a human. $1 = the commit
# being cherry-picked.
auto_resolve_conflicts() {
  local c="$1" f other=0 readme=0
  while read -r f; do
    [ -n "$f" ] || continue
    if [ "$f" = "docs/mips/README.md" ]; then readme=1; continue; fi
    if git -C "$wt_dir" cat-file -e "origin/main:$f" 2>/dev/null && git -C "$wt_dir" diff --quiet "$c" origin/main -- "$f" 2>/dev/null; then
      git -C "$wt_dir" checkout -q origin/main -- "$f"
      echo "mip-stack: $f — this commit's version is already on main, keeping main's"
    else
      other=1
    fi
  done <<<"$(git -C "$wt_dir" diff --name-only --diff-filter=U)"
  [ "$other" -eq 0 ] || return 1
  if [ "$readme" -eq 1 ]; then auto_resolve_index || return 1; fi
  return 0
}
# `cherry-pick --continue` after a resolution that left nothing to commit (the whole change was
# already on main) refuses with "now empty" — that commit is simply skipped.
continue_or_skip() {
  if git -C "$wt_dir" cherry-pick --continue >/dev/null 2>&1; then return 0; fi
  if git -C "$wt_dir" diff --cached --quiet && git -C "$wt_dir" diff --quiet; then git -C "$wt_dir" cherry-pick --skip >/dev/null 2>&1; return $?; fi
  return 1
}

dry=0; resume=0; from_json=""; self_test=0
skip_nums=()
subcommand=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry=1 ;;
    --resume) resume=1 ;;
    --skip) shift; skip_nums+=("${1:?--skip needs a PR number}") ;;
    --from-json) shift; from_json="${1:?--from-json needs a file}" ;;
    --self-test) self_test=1 ;;
    status|clean) subcommand="$1" ;;
    *) echo "mip-stack: unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done
run() { if [ "$dry" -eq 1 ]; then echo "+ $*"; else "$@"; fi; }

# --- ordering (shared by discovery, --from-json, --self-test): pure jq ------------------------.
order_prs() {   # stdin: gh-pr-list-shaped JSON array -> stdout: MIP draft PRs, by MIP number
  local skip_json="[]"
  if [ "${#skip_nums[@]}" -gt 0 ]; then
    skip_json="$(printf '%s\n' "${skip_nums[@]}" | jq -R 'tonumber' | jq -s .)"
  fi
  jq --argjson skip "$skip_json" '
    # The number in the head branch name wins (docs/mip-0023-... is MIP-0023 whatever files it
    # carries); a PR whose head has no number is placed by the HIGHEST MIP file it touches — a
    # draft that merged the branch of an earlier draft to stay mergeable carries that file too,
    # and "first file in the list" put three drafts under MIP-0020 on 2026-09-06.
    def mip_from_files: [(.files // [])[] | .path | capture("^docs/mips/MIP-(?<n>[0-9]{4})-[^/]*\\.md$") | .n] | max // null;
    def mip_from_head: (.headRefName | capture("^(?:docs/)?mip-(?<n>[0-9]{4})") | .n) // null;
    def is_task_branch: .headRefName | test("^mip-[0-9]{4}/[0-9]+-");
    def mip: (mip_from_head // mip_from_files);
    map(select(.number as $n | ($skip | index($n)) == null))
    | map(select(is_task_branch | not))
    | map(select((.headRefName | startswith("docs/mip-")) or (mip_from_files != null)))
    | map(. + {mip: mip})
    | map(select(.mip != null))
    | sort_by([(.mip | tonumber), .number])
  '
}

slug_of() {   # headRefName -> branch-name-safe slug (a leading docs/ dropped)
  local s="${1#docs/}"
  s="$(tr '/_' '--' <<<"$s" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9-]+/-/g; s/-+/-/g; s/^-//; s/-$//')"
  echo "${s:0:48}" | sed -E 's/-$//'
}

discover() {
  local raw
  if [ -n "$from_json" ]; then
    raw="$(cat "$from_json")"
  elif [ "$dry" -eq 1 ]; then
    echo "+ gh pr list --state open --limit 100 --json number,headRefName,title,baseRefName,mergeable,files" >&2
    echo "[]"; return 0
  else
    gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
    raw="$(gh pr list --state open --limit 100 --json number,headRefName,title,baseRefName,mergeable,files)"
  fi
  order_prs <<<"$raw"
}

# --- self-test
# ----------------------------------------------------------------------------------.
self_test() {
  local fixture="$script_dir/fixtures/mip-stack-prs.json"
  [ -f "$fixture" ] || { echo "mip-stack self-test: fixture not found: $fixture" >&2; exit 1; }
  local skip_nums=() ordered got expected
  ordered="$(order_prs <"$fixture")"
  got="$(jq -c '[.[].number]' <<<"$ordered")"
  # 201 (MIP-0020, head docs/mip-…), 208 (MIP-0023 by file, head not docs/), 210 (MIP-0025), 211
  # (head docs/mip-0025-… although its files list MIP-0020's file first — the head wins); 205 is a
  # task branch (excluded), 212 touches no MIP file (excluded).
  expected="[201,208,210,211]"
  echo "order: $got"
  jq -r '.[] | "  #\(.number) MIP-\(.mip) \(.headRefName)"' <<<"$ordered"
  [ "$got" = "$expected" ] || { echo "mip-stack self-test: FAILED — order $got, expected $expected" >&2; exit 1; }
  local m211; m211="$(jq -r '.[] | select(.number == 211) | .mip' <<<"$ordered")"
  [ "$m211" = "0025" ] || { echo "mip-stack self-test: FAILED — #211 classified as MIP-$m211, expected 0025 (head branch number must win over a merged-in MIP-0020 file)" >&2; exit 1; }
  local slug; slug="$(slug_of "docs/mip-0020-instagram-pipeline")"
  [ "$slug" = "mip-0020-instagram-pipeline" ] || { echo "mip-stack self-test: FAILED — slug_of gave '$slug'" >&2; exit 1; }
  echo "mip-stack self-test: PASSED"
}

resolve_ref() {
  if git -C "$repo_root" rev-parse --verify -q "refs/remotes/origin/$1" >/dev/null; then echo "origin/$1"; else echo "$1"; fi
}

# --- plan + chain ------------------------------------------------------------------------------.
compute_plan() {   # $1 = ordered JSON -> {date, prs:[... + slug, k, branch]}
  local ordered="$1" date n i=0 slugged="[]"
  date="$(date +%Y-%m-%d)"
  n="$(jq 'length' <<<"$ordered")"
  while [ "$i" -lt "$n" ]; do
    local item slug
    item="$(jq -c ".[$i]" <<<"$ordered")"
    slug="$(slug_of "$(jq -r '.headRefName' <<<"$item")")"
    slugged="$(jq --argjson item "$item" --arg slug "$slug" '. + [$item + {slug: $slug}]' <<<"$slugged")"
    i=$((i + 1))
  done
  jq --arg date "$date" '
    to_entries | map((.key + 1) as $k | .value + {k: $k, branch: ("mips/" + $date + "/" + ($k | tostring) + "-" + .value.slug)}) as $e
    | {date: $date, prs: $e}' <<<"$slugged"
}
write_plan() { mkdir -p "$plan_dir"; compute_plan "$1" | tee "$plan_file"; }
base_branch_of() {   # $1 = plan, $2 = k -> the nearest earlier link that was actually built, else main
  jq -r --argjson k "$2" '[.prs[] | select(.k < $k and ((.superseded // false) | not))] | last | .branch // "main"' <<<"$1"
}
mark_superseded() {   # $1 = k — record it in the plan (variable + file) so publish/status skip the link
  plan="$(jq --argjson k "$1" '(.prs[] | select(.k == $k)) += {superseded: true}' <<<"$plan")"
  [ -f "$plan_file" ] && printf '%s\n' "$plan" > "$plan_file"
  return 0
}

build_chain() {
  local plan="$1" n k
  n="$(jq '.prs | length' <<<"$plan")"
  [ "$n" -gt 0 ] || { echo "mip-stack: nothing to build (no MIP draft PRs after discovery/--skip)"; return 0; }
  if [ "$dry" -eq 1 ]; then
    echo "+ git worktree add --detach $wt_rel origin/main"
    k=1
    while [ "$k" -le "$n" ]; do
      local entry branch base_name head_ref
      entry="$(jq -c --argjson k "$k" '.prs[] | select(.k == $k)' <<<"$plan")"
      branch="$(jq -r '.branch' <<<"$entry")"; base_name="$(base_branch_of "$plan" "$k")"
      head_ref="$(jq -r '.headRefName' <<<"$entry")"
      echo "building $branch (base: $base_name)"
      echo "+ git -C $wt_rel checkout -q -b $branch $base_name"
      echo "+ git -C $wt_rel cherry-pick \$(git rev-list --reverse --no-merges origin/$head_ref --not origin/main <earlier drafts' heads>)  # PR #$(jq -r '.number' <<<"$entry")'s own commits"
      k=$((k + 1))
    done
    return 0
  fi
  ensure_worktree "$([ "$resume" -eq 1 ] && echo resume || echo fresh)"
  git -C "$wt_dir" fetch -q origin || true
  if [ "$resume" -eq 0 ]; then
    # A fresh run starts the chain over: a same-named chain branch left by an earlier run today
    # that was never pushed (an abandoned or half-built chain) is deleted, so it cannot be taken
    # for "already built"; one that is on origin was published and is kept as is.
    while read -r stale; do
      [ -n "$stale" ] || continue
      if git -C "$repo_root" rev-parse --verify -q "refs/heads/$stale" >/dev/null && \
         ! git -C "$repo_root" rev-parse --verify -q "refs/remotes/origin/$stale" >/dev/null; then
        echo "mip-stack: deleting unpublished $stale from an earlier run today"
        git -C "$repo_root" branch -q -D "$stale"
      fi
    done <<<"$(chain_branches_of "$plan")"
  fi
  k=1
  while [ "$k" -le "$n" ]; do
    local entry branch base_name base_ref head_ref
    entry="$(jq -c --argjson k "$k" '.prs[] | select(.k == $k)' <<<"$plan")"
    branch="$(jq -r '.branch' <<<"$entry")"
    base_name="$(base_branch_of "$plan" "$k")"
    if git -C "$wt_dir" rev-parse --verify -q "refs/heads/$branch" >/dev/null; then
      if [ -e "$(git -C "$wt_dir" rev-parse --git-path CHERRY_PICK_HEAD)" ] && [ "$(git -C "$wt_dir" branch --show-current)" = "$branch" ]; then
        if auto_resolve_conflicts "$(git -C "$wt_dir" rev-parse CHERRY_PICK_HEAD)" && continue_or_skip; then
          echo "mip-stack: auto-resolved the conflict left over on $branch"
        else
          echo "mip-stack: $branch has an unresolved cherry-pick — finish it first:"
          print_conflict_instructions "$branch"; exit 1
        fi
      fi
      echo "have $branch (base: $base_name) — skipping build"
      k=$((k + 1)); continue
    fi
    base_ref="$(resolve_ref "$base_name")"
    head_ref="$(resolve_ref "$(jq -r '.headRefName' <<<"$entry")")"
    # The PR's *own* commits: reachable from its head, not from main and not from any earlier
    # draft's head — a draft that merged another draft's branch (to stay mergeable) carries that
    # draft's commits by SHA, and they are already on the chain from that earlier link.
    local -a not_refs=("origin/main")
    while read -r earlier; do
      [ -n "$earlier" ] || continue
      not_refs+=("$(resolve_ref "$earlier")")
    done <<<"$(jq -r --argjson k "$k" '.prs[] | select(.k < $k) | .headRefName' <<<"$plan")"
    local commits
    commits="$(git -C "$wt_dir" rev-list --reverse --no-merges "$head_ref" --not "${not_refs[@]}" 2>/dev/null || true)"
    if [ -z "$commits" ]; then
      echo "mip-stack: skipping $head_ref — no commit the chain does not already carry (PR #$(jq -r '.number' <<<"$entry") is superseded; close it by hand)"
      mark_superseded "$k"; k=$((k + 1)); continue
    fi
    # Already on main by content: every docs/mips/MIP-*.md the PR's own commits touch exists on
    # origin/main with the same blob as on the PR head — the draft was squash-merged under another
    # PR number (today: docs/mip-0021-beach-accessibility, merged as #134, still open as #135).
    local mip_paths landed=1
    mip_paths="$(while read -r cc; do [ -n "$cc" ] && git -C "$wt_dir" diff-tree --no-commit-id --name-only -r "$cc"; done <<<"$commits" 2>/dev/null | grep -E '^docs/mips/MIP-[0-9]{4}-[^/]*\.md$' | sort -u || true)"
    if [ -n "$mip_paths" ]; then
      while read -r mp; do
        [ -n "$mp" ] || continue
        git -C "$wt_dir" cat-file -e "origin/main:$mp" 2>/dev/null && git -C "$wt_dir" diff --quiet origin/main "$head_ref" -- "$mp" 2>/dev/null || landed=0
      done <<<"$mip_paths"
      if [ "$landed" -eq 1 ]; then
        echo "mip-stack: skipping $head_ref — its MIP file(s) are identical on main ($(tr '\n' ' ' <<<"$mip_paths")— squash-merged? close PR #$(jq -r '.number' <<<"$entry") by hand)"
        mark_superseded "$k"; k=$((k + 1)); continue
      fi
    fi
    echo "building $branch (base: $base_ref)"
    git -C "$wt_dir" checkout -q -b "$branch" "$base_ref"
    # Second filter, by identity rather than ancestry: a draft that was *rebased* onto another
    # draft (rather than merging it) carries copies of that draft's commits under new SHAs, with
    # the author date and subject intact — and once the copy's README row was resolved differently
    # its patch-id differs too, so only "same author date + same subject as a commit the chain
    # already has" identifies it.
    local have_keys
    have_keys="$(git -C "$wt_dir" log --format='%at %s' "$base_ref" --not origin/main 2>/dev/null || true)"
    while read -r c; do
      [ -n "$c" ] || continue
      local key
      key="$(git -C "$wt_dir" log -1 --format='%at %s' "$c")"
      if [ -n "$have_keys" ] && grep -qxF -- "$key" <<<"$have_keys"; then
        echo "mip-stack: skipping ${c:0:7} — the chain already carries this commit (same author date and subject, a rebased copy)"
        continue
      fi
      if ! git -C "$wt_dir" cherry-pick --allow-empty --empty=drop "$c" >/dev/null 2>&1; then
        if auto_resolve_conflicts "$c" && continue_or_skip; then
          echo "mip-stack: auto-resolved the conflict cherry-picking ${c:0:7} onto $branch"
        else
          print_conflict_instructions "$branch"; exit 1
        fi
      fi
    done <<<"$commits"
    k=$((k + 1))
  done
}

publish_chain() {
  local plan="$1" n k
  n="$(jq '.prs | length' <<<"$plan")"
  [ "$n" -gt 0 ] || return 0
  [ "$dry" -eq 1 ] || gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
  k=1
  while [ "$k" -le "$n" ]; do
    local entry branch base_name orig_num orig_title
    entry="$(jq -c --argjson k "$k" '.prs[] | select(.k == $k)' <<<"$plan")"
    branch="$(jq -r '.branch' <<<"$entry")"; base_name="$(base_branch_of "$plan" "$k")"
    orig_num="$(jq -r '.number' <<<"$entry")"; orig_title="$(jq -r '.title' <<<"$entry")"
    if [ "$(jq -r '.superseded // false' <<<"$entry")" = true ]; then
      echo "mip-stack: $branch — superseded, not published; close PR #$orig_num by hand (its content is on main)"
      k=$((k + 1)); continue
    fi
    if git -C "$repo_root" rev-parse --verify -q "refs/heads/$branch" >/dev/null; then
      if git -C "$repo_root" rev-parse --verify -q "refs/remotes/origin/$branch" >/dev/null; then
        run git -C "$repo_root" push -q --force-with-lease origin "$branch"
      else
        run git -C "$repo_root" push -q -u origin "$branch"
      fi
    elif [ "$dry" -eq 0 ]; then
      echo "mip-stack: $branch was not built (superseded) — no PR for it"; k=$((k + 1)); continue
    fi
    if [ "$dry" -eq 0 ] && gh pr view "$branch" --json number -q .number >/dev/null 2>&1; then
      echo "have PR for $branch already — leaving it"; k=$((k + 1)); continue
    fi
    local title body extra
    title="$(cap_title "$orig_title")"; body="$(mktemp)"; extra="$(mktemp)"
    {
      echo "Stacked MIP draft — originally PR #$orig_num, rebuilt here as part of a \`just mip-stack\`"
      echo "chain so the drafts merge bottom-up without fighting over their \`docs/mips/README.md\` row."
      echo "The original branch is untouched; #$orig_num is closed with a pointer to this one."
    } > "$extra"
    if [ "$dry" -eq 1 ]; then
      echo "===== $branch (base: $base_name, was PR #$orig_num) ====="
      BRANCH="$branch" BASE="$base_name" EXTRA_FILE="$extra" run "$script_dir/uprd.sh" --dry-run
      rm -f "$body" "$extra"; k=$((k + 1)); continue
    fi
    BRANCH="$branch" BASE="$base_name" EXTRA_FILE="$extra" "$script_dir/uprd.sh" --dry-run 2>/dev/null | sed '1d' > "$body"
    local new_url new_num
    new_url="$(gh pr create --base "$base_name" --head "$branch" --title "$title" --body-file "$body")"
    new_num="$(sed -n 's#.*/pull/\([0-9]*\)$#\1#p' <<<"$new_url")"
    echo "created $new_url (from #$orig_num)"
    gh pr close "$orig_num" --comment "stacked as #$new_num via \`just mip-stack\`" >/dev/null || \
      echo "mip-stack: could not close #$orig_num — close it by hand once #$new_num looks right" >&2
    rm -f "$body" "$extra"
    k=$((k + 1))
  done
}

chain_branches_of() { jq -r '.prs | sort_by(.k) | .[].branch' <<<"$1"; }

cmd_status() {
  [ -f "$plan_file" ] || { echo "mip-stack: no chain built yet — run 'just mip-stack' first"; return 0; }
  local plan; plan="$(cat "$plan_file")"
  echo "chain date: $(jq -r '.date' <<<"$plan")"
  worktree_registered && echo "worktree: $wt_rel" || echo "worktree: $wt_rel (not present)"
  local k n; n="$(jq '.prs | length' <<<"$plan")"; k=1
  while [ "$k" -le "$n" ]; do
    local entry branch pr
    entry="$(jq -c --argjson k "$k" '.prs[] | select(.k == $k)' <<<"$plan")"
    branch="$(jq -r '.branch' <<<"$entry")"
    pr="$(gh pr view "$branch" --json number,state -q '"#\(.number) \(.state)"' 2>/dev/null || echo "no PR")"
    if [ "$(jq -r '.superseded // false' <<<"$entry")" = true ]; then pr="superseded — content on main, close the original PR"; fi
    printf '%-44s base=%-30s %s  (MIP-%s, orig #%s)\n' "$branch" "$(base_branch_of "$plan" "$k")" "$pr" \
      "$(jq -r '.mip' <<<"$entry")" "$(jq -r '.number' <<<"$entry")"
    k=$((k + 1))
  done
}

cmd_clean() {
  [ -f "$plan_file" ] || { echo "mip-stack: no chain built yet — nothing to clean"; return 0; }
  gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
  local plan; plan="$(cat "$plan_file")"
  if [ "$dry" -eq 0 ] && worktree_registered; then git -C "$wt_dir" checkout -q --detach origin/main 2>/dev/null || true; fi
  local b state all_merged=1
  while read -r b; do
    [ -n "$b" ] || continue
    git -C "$repo_root" rev-parse --verify -q "refs/heads/$b" >/dev/null || continue
    state="$(gh pr view "$b" --json state -q .state 2>/dev/null || echo NONE)"
    if [ "$state" = MERGED ]; then
      echo "deleting $b (PR merged)"
      run git -C "$repo_root" branch -D "$b"
      if git -C "$repo_root" rev-parse --verify -q "origin/$b" >/dev/null 2>&1; then
        run git -C "$repo_root" push -q origin --delete "$b" || echo "  (already gone on origin)"
      fi
    else
      echo "keep $b (PR: $state)"; all_merged=0
    fi
  done <<<"$(chain_branches_of "$plan")"
  if [ "$dry" -eq 0 ]; then
    rm -f "$plan_file"
    if [ "$all_merged" -eq 1 ] && worktree_registered; then
      echo "mip-stack: every chain branch merged — removing worktree $wt_rel"; remove_worktree
    fi
  fi
}

# --- entry point ------------------------------------------------------------------------------.
if [ "$self_test" -eq 1 ]; then self_test; exit 0; fi
case "$subcommand" in
  status) cmd_status; exit 0 ;;
  clean) cmd_clean; exit 0 ;;
esac

if [ "$resume" -eq 1 ]; then
  [ -f "$plan_file" ] || { echo "mip-stack: --resume needs a previous run's plan — none at $plan_file" >&2; exit 1; }
  plan="$(cat "$plan_file")"
  echo "resuming chain from $plan_file (date $(jq -r '.date' <<<"$plan"))"
else
  ordered="$(discover)"
  n="$(jq 'length' <<<"$ordered")"
  if [ "$n" -eq 0 ]; then echo "mip-stack: no open MIP draft PRs found (after discovery/--skip)"; exit 0; fi
  echo "discovered $n MIP draft PR(s), in stack order:"
  jq -r '.[] | "  #\(.number) MIP-\(.mip) \(.headRefName) — \(.title)"' <<<"$ordered"
  if [ "$dry" -eq 1 ]; then
    plan="$(compute_plan "$ordered")"; echo "would write plan to $plan_file:"; jq . <<<"$plan"
  else
    plan="$(write_plan "$ordered")"
  fi
fi

build_chain "$plan"
[ -f "$plan_file" ] && plan="$(cat "$plan_file")"   # build_chain may have marked links superseded
if [ "$dry" -eq 1 ]; then echo "dry-run: stopping before publish (push/PR creation/close) and link — no gh mutation."; exit 0; fi
publish_chain "$plan"
mapfile -t chain_brs < <(chain_branches_of "$plan")
stack_link "$dry" "${chain_brs[@]}"
