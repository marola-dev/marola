#!/usr/bin/env bash
# deps-stack — stack every open dependency-update PR into one GitHub Stack, the same shape
# `scripts/stack.sh`/`just uprds` give a MIP's task branches (see docs/DEV-FLOW.md §6-7).
#
#   just deps-stack                    # discover, build the local chain, publish, link
#   just deps-stack --dry-run          # print every git/gh command; no push, no gh mutation
#   just deps-stack --resume           # continue after a conflict (see below)
#   just deps-stack --skip 123         # drop PR #123 from the chain (repeatable)
#   just deps-stack --include-steward  # append scala-steward PRs after the dependabot ones
#   just deps-stack --from-json f.json # use this file instead of `gh pr list` (testing, no gh)
#   just deps-stack status             # print the current chain + each PR's state
#   just deps-stack clean              # delete deps/* chain branches whose stacked PR is MERGED
#   just deps-stack --self-test        # parse scripts/fixtures/deps-stack-prs.json, assert order
#
# Isolation: the whole local chain is built in a dedicated worktree, `.tmp/wt-deps-stack`
# (`.tmp/` is gitignored; same convention scripts/cost-split.py already documents for stack
# worktrees) — never in the caller's own checkout. Every `checkout -b`/cherry-pick/`branch
# --show-current`/CHERRY_PICK_HEAD lookup runs `git -C .tmp/wt-deps-stack ...` against it, so
# a run of this script never switches the caller's branch or touches its index, even mid-conflict
# or across `--resume`/`status`/`clean`. A first (non-`--resume`) run removes and recreates the
# worktree fresh off `origin/main` — including a stale one left over from an earlier date's run;
# `clean` removes it (`git worktree remove --force`) once every chain branch is confirmed MERGED
# and deleted. On a conflict, the printed instructions `cd` into that worktree, not the caller's
# tree. Two dependency-bump commits landing on adjacent lines of the same file are the conflict
# shapes here, and both auto-resolve before this script ever prints a human-resolve message:
# `*requirements*.txt` (`scripts/lib/req_merge.py`, kept lower-bound = the higher of the two, per
# package) and `.github/workflows/*.yml` `uses:` steps (`scripts/lib/uses_merge.py`, kept ref =
# the higher version per action — actions/checkout@v7 next to hadolint-action@v3.5.0, the
# 2026-09-06 case). Anything else still stops for a human.
#
# ---------------------------------------------------------------------------------------------
# 1. Discovery (scripts/fixtures/deps-stack-prs.json shapes the same fields):
#      gh pr list --state open --search "author:app/dependabot" \
#        --json number,headRefName,title,baseRefName,mergeable,files
#    `author:app/dependabot` is GitHub's documented search qualifier for issues/PRs opened by a
#    GitHub App (docs.github.com/en/search-github/searching-on-github/searching-issues-and-pull-requests,
#    "author:app/robot matches issues created by the integration account named robot", verified
#    2026-09-05); `gh pr list --app dependabot` is an equivalent, newer convenience flag
#    (cli.github.com/manual/gh_pr_list) — --search is used here since it works on older gh too.
#    Order: github-actions PRs first, then pip, each group by PR number ascending (see order_prs).
#    `--include-steward` runs a second discovery with `--search "author:app/scala-steward"` and
#    appends those after the dependabot groups, keyed on the docs' own convention
#    (scala-steward-org/scala-steward-action's README: a PAT makes PRs "look like it's you",
#    implying the default GITHUB_TOKEN path shows a generic bot identity instead) — *this repo's
#    scala-steward.yml uses the default GITHUB_TOKEN with no PAT (see that workflow's own header),
#    so which author string actually shows up (app/scala-steward vs. the generic
#    app/github-actions identity) is UNVERIFIED here: gh is not logged in in this environment, so
#    the live query was never run against a real scala-steward PR. Confirm with
#    `gh pr list --state open --json author --search "is:pr author:app/scala-steward"` before
#    relying on `--include-steward`; if it returns nothing, scala-steward's PRs are simply not
#    separable by author query with the default token, and this repo's own scala-steward.yml
#    header may need the same caveat added once you know.
#
# 2. Local chain build (like `scripts/stack.sh start` for MIP tasks), in the dedicated worktree
#    `.tmp/wt-deps-stack` (see "Isolation" above, not the caller's checkout): branch k is
#    `deps/<YYYY-MM-DD>/<k>-<slug>`, created from branch k-1 (k=1 from `origin/main`), then that
#    PR's own commits (`<base>..<head>` of the *original* dependabot branch) are cherry-picked
#    onto it. A bump-vs-bump conflict auto-resolves — requirements files via
#    scripts/lib/req_merge.py, workflow `uses:` lines via scripts/lib/uses_merge.py; anything
#    else stops the script with the branch left mid-cherry-pick in that worktree: resolve with `cd
#    .tmp/wt-deps-stack && git status`, fix, `git add`, `git cherry-pick --continue`, `cd -`, then
#    re-run with `--resume` to build the remaining branches. The plan (which PRs, in what order,
#    on what date) is cached at .tmp/deps-stack/plan.json so `--resume` continues the *same* chain
#    rather than re-discovering (and possibly reordering) from a fresh `gh pr list`; run from the
#    same working copy you started in.
#
# 3. Publish — design choice, see also docs/DEV-FLOW.md:
#    A PR's head branch cannot be changed after creation (`gh pr edit` has no `--head`; a PR is
#    permanently tied to the branch it was opened from — GitHub API/gh docs, and it's also not
#    dependabot's branch to move). That leaves two honest options:
#      (a) rebase dependabot's own branches onto each other and force-push the result back to
#          `dependabot/<...>` (dependabot allows force-pushes to its own branches), then
#          `gh pr edit --base` each PR onto the previous one. Keeps the *same* PR numbers, but
#          dependabot stops updating a PR once its branch no longer matches what it last pushed,
#          and `@dependabot rebase`/a new run of the workflow can silently undo the stacking by
#          re-pushing its own rebase over yours. This is a real, not theoretical, footgun — left
#          as a **documented non-goal**, not implemented (see --retarget-dependabot below).
#      (b) [DEFAULT, IMPLEMENTED] leave every dependabot branch untouched. Open one *new* PR per
#          chain branch (`gh pr create --base <prev-chain-branch-or-main> --title "<original
#          title>" --body-file <generated>`), with the body generated by
#          `BRANCH=... BASE=... scripts/uprd.sh --dry-run` (reused, not reimplemented) plus a
#          pointer back to the original PR. Then close the original dependabot PR with
#          `gh pr close <n> --comment "stacked as #<m> via just deps-stack"`. dependabot's branch
#          is never touched, so if the stack is abandoned dependabot can still update/re-open its
#          own PR normally.
#    `--retarget-dependabot` exists only to name option (a) and explain, on invocation, why it is
#    not implemented — see above.
#
# 4. Link: `scripts/lib/stack_link.sh`'s `stack_link` (shared with `scripts/stack.sh link`) runs
#    `gh stack link` over the chain's *open* PRs, bottom to top.
#
# 5. `just deps-stack clean`: deletes local+origin `deps/<date>/<k>-*` branches whose PR (the new
#    stacked one from step 3b) gh confirms MERGED — same MERGED-only check as
#    `scripts/branches.sh clean`, scoped to this chain's branches only.
#
# Needs `gh auth status` for anything that isn't --dry-run/--from-json/--self-test/status/clean's
# local listing. Not available inside ai-jail (AGENTS.md) — run from the host.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
# shellcheck source=scripts/lib/stack_link.sh
source "$script_dir/lib/stack_link.sh"
# shellcheck source=scripts/lib/uprd_title.sh
source "$script_dir/lib/uprd_title.sh"

plan_dir="$repo_root/.tmp/deps-stack"
plan_file="$plan_dir/plan.json"

# --- the dedicated build worktree — never the caller's own checkout (see header, "Isolation") --
wt_rel=".tmp/wt-deps-stack"
wt_dir="$repo_root/$wt_rel"

worktree_registered() {
  git -C "$repo_root" worktree list --porcelain | grep -qx "worktree $wt_dir"
}

remove_worktree() {   # best-effort: also cleans up a worktree whose directory was hand-deleted
  if worktree_registered; then
    git -C "$repo_root" worktree remove --force "$wt_dir" 2>/dev/null || true
  fi
  rm -rf "$wt_dir"
  git -C "$repo_root" worktree prune 2>/dev/null || true
}

# $1 = fresh|resume. A fresh (non-`--resume`) invocation always starts the chain over (discovery
# is re-run, plan.json is overwritten below), so the worktree is rebuilt from origin/main too —
# this is also what clears out a stale worktree left over from an earlier date's abandoned run.
# `--resume` reuses whatever the previous run left in place; it errors out rather than silently
# building a fresh (and therefore wrong) worktree if that one is gone.
ensure_worktree() {
  mkdir -p "$repo_root/.tmp"
  if [ "$1" = fresh ]; then
    if [ -d "$wt_dir" ] || worktree_registered; then
      echo "deps-stack: removing worktree at $wt_rel from a previous run"
      remove_worktree
    fi
    run git -C "$repo_root" worktree add --detach "$wt_dir" origin/main
  else
    if ! worktree_registered; then
      echo "deps-stack: --resume needs the worktree from the previous run at $wt_rel — it's missing (removed by hand? already cleaned?); run 'just deps-stack' without --resume to start over" >&2
      exit 1
    fi
  fi
}

# Printed on a cherry-pick conflict this script can't auto-resolve — names the worktree, never
# the caller's own checkout, so following these steps can't touch the caller's branch or index.
print_conflict_instructions() {   # $1 = branch left mid-cherry-pick
  echo
  echo "deps-stack: CONFLICT cherry-picking onto $1 in $wt_rel — resolve it:"
  echo "  cd $wt_rel && git status"
  echo "  # fix conflicts, then:"
  echo "  git add <files>"
  echo "  git cherry-pick --continue"
  echo "  cd -"
  echo "  just deps-stack --resume"
}

# Auto-resolve the two bump-vs-bump conflict classes: every file `git diff --name-only
# --diff-filter=U` reports in the worktree is either a `*requirements*.txt` (scripts/lib/
# req_merge.py: keep the higher lower bound per package) or a `.github/workflows/*.yml|yaml`
# (scripts/lib/uses_merge.py: keep the higher `uses: owner/action@vN` per step — the
# actions/checkout-bump-next-to-a-hadolint-bump shape). Each module's docstring/self-test covers
# the exact hunks it accepts and refuses. Returns 0 and leaves the resolved files `git add`ed
# (ready for `cherry-pick --continue`) only when *every* conflicted file qualified and resolved;
# otherwise returns 1 and stages nothing, so the ordinary conflict-stop path still applies. (Each
# module is itself all-or-nothing over the files it gets; when the requirements files resolve and
# a workflow file then refuses, the requirements files stay resolved on disk but unstaged — the
# human-resolve step that follows only has the workflow file left to fix.)
auto_resolve_bumps() {
  local conflicted f
  conflicted="$(git -C "$wt_dir" diff --name-only --diff-filter=U)"
  [ -n "$conflicted" ] || return 1
  local -a reqs=() flows=()
  while read -r f; do
    [ -n "$f" ] || continue
    case "$f" in
      *requirements*.txt) reqs+=("$wt_dir/$f") ;;
      .github/workflows/*.yml|.github/workflows/*.yaml) flows+=("$wt_dir/$f") ;;
      *) return 1 ;;
    esac
  done <<<"$conflicted"
  local out
  if [ "${#reqs[@]}" -gt 0 ]; then
    if ! out="$(python3 "$script_dir/lib/req_merge.py" "${reqs[@]}" 2>&1)"; then
      [ -z "$out" ] || echo "$out" >&2
      return 1
    fi
    [ -z "$out" ] || echo "$out"
  fi
  if [ "${#flows[@]}" -gt 0 ]; then
    if ! out="$(python3 "$script_dir/lib/uses_merge.py" "${flows[@]}" 2>&1)"; then
      [ -z "$out" ] || echo "$out" >&2
      return 1
    fi
    [ -z "$out" ] || echo "$out"
  fi
  while read -r f; do
    [ -n "$f" ] || continue
    git -C "$wt_dir" add "$f"
  done <<<"$conflicted"
  return 0
}

dry=0; resume=0; include_steward=0; from_json=""; self_test=0; retarget=0
skip_nums=()
subcommand=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry=1 ;;
    --resume) resume=1 ;;
    --include-steward) include_steward=1 ;;
    --from-json) shift; from_json="${1:?--from-json needs a file}" ;;
    --skip) shift; skip_nums+=("${1:?--skip needs a PR number}") ;;
    --self-test) self_test=1 ;;
    --retarget-dependabot) retarget=1 ;;
    status|clean) subcommand="$1" ;;
    -h|--help) sed -n '2,60p' "$0"; exit 0 ;;
    *) echo "deps-stack: unknown argument: $1" >&2; exit 1 ;;
  esac
  shift
done

if [ "$retarget" -eq 1 ]; then
  cat >&2 <<'EOF'
deps-stack: --retarget-dependabot (option (a): rebase dependabot's own branches onto each other
and force-push back to them) is a documented non-goal, not implemented — see this script's
header, point 3. dependabot stops updating a PR once its branch no longer matches what it last
pushed, and @dependabot rebase / its next scheduled run can silently undo the stacking. Run
without this flag for the default, implemented path: new PRs stacked on each other, the original
dependabot PRs closed with a pointer comment (option (b)).
EOF
  exit 1
fi

run() { if [ "$dry" -eq 1 ]; then echo "+ $*"; else "$@"; fi; }

# --- ordering (shared by discovery, --from-json, and --self-test) ---------------------------
# github-actions first, then pip, then scala-steward (only when asked for), each group by PR
# number ascending. Pure jq — no git/gh — so --self-test needs neither.
order_prs() {   # stdin: gh-pr-list-shaped JSON array -> stdout: same, filtered + sorted
  local skip_json="[]"
  if [ "${#skip_nums[@]}" -gt 0 ]; then
    skip_json="$(printf '%s\n' "${skip_nums[@]}" | jq -R 'tonumber' | jq -s .)"
  fi
  jq --argjson skip "$skip_json" --argjson include_steward "$include_steward" '
    def eco:
      if (.headRefName | test("^dependabot/github_actions/")) then "github-actions"
      elif (.headRefName | test("^dependabot/pip/")) then "pip"
      elif (.headRefName | test("^update/")) then "scala-steward"
      else "other" end;
    def rank:
      if eco == "github-actions" then 0
      elif eco == "pip" then 1
      elif eco == "scala-steward" then 2
      else 3 end;
    map(select(.number as $n | ($skip | index($n)) == null))
    | map(select($include_steward == 1 or eco != "scala-steward"))
    | map(. + {ecosystem: eco, rank: rank})
    | sort_by([.rank, .number])
  '
}

slug_of() {   # headRefName -> a short branch-name-safe slug
  local ref="$1" s
  s="${ref#dependabot/}"
  s="${s#update/}"
  s="${s#*/}"                                   # drop the ecosystem/directory segment
  [ -n "$s" ] || s="$ref"                        # fallback for shapes with no leading segment
  s="$(tr '/_' '--' <<<"$s" | tr '[:upper:]' '[:lower:]')"
  s="$(sed -E 's/[^a-z0-9-]+/-/g; s/-+/-/g; s/^-//; s/-$//' <<<"$s")"
  echo "${s:0:40}" | sed -E 's/-$//'
}

discover() {   # -> ordered JSON array on stdout
  local raw
  if [ -n "$from_json" ]; then
    raw="$(cat "$from_json")"
  elif [ "$dry" -eq 1 ]; then
    echo "+ gh pr list --state open --search \"author:app/dependabot\" --json number,headRefName,title,baseRefName,mergeable,files" >&2
    [ "$include_steward" -eq 1 ] && echo "+ gh pr list --state open --search \"author:app/scala-steward\" --json number,headRefName,title,baseRefName,mergeable,files" >&2
    echo "[]"
    return 0
  else
    gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
    raw="$(gh pr list --state open --search "author:app/dependabot" --json number,headRefName,title,baseRefName,mergeable,files)"
    if [ "$include_steward" -eq 1 ]; then
      local steward
      steward="$(gh pr list --state open --search "author:app/scala-steward" --json number,headRefName,title,baseRefName,mergeable,files)"
      raw="$(jq -s 'add' <<<"$raw"$'\n'"$steward")"
    fi
  fi
  order_prs <<<"$raw"
}

# --- self-test --------------------------------------------------------------------------------
self_test() {
  local fixture="$script_dir/fixtures/deps-stack-prs.json"
  [ -f "$fixture" ] || { echo "deps-stack self-test: fixture not found: $fixture" >&2; exit 1; }
  local include_steward=0 skip_nums=()   # the fixture has no steward/skip entries to exercise
  local ordered
  ordered="$(order_prs <"$fixture")"
  local got expected
  got="$(jq -c '[.[].number]' <<<"$ordered")"
  expected="[101,103,102]"   # github-actions (101, 103) by number, then pip (102)
  echo "order: $got"
  jq -r '.[] | "  #\(.number) \(.ecosystem) \(.headRefName)"' <<<"$ordered"
  if [ "$got" != "$expected" ]; then
    echo "deps-stack self-test: FAILED — order $got, expected $expected" >&2
    exit 1
  fi
  local slug
  slug="$(slug_of "dependabot/github_actions/actions/checkout-5")"
  [ "$slug" = "actions-checkout-5" ] || { echo "deps-stack self-test: FAILED — slug_of gave '$slug', expected 'actions-checkout-5'" >&2; exit 1; }
  echo "deps-stack self-test: PASSED"
}

# --- ref resolution: origin is the source of truth (a local "main"/task branch in a shared,
# multi-worktree checkout can be stale); fall back to a same-named local branch only when origin
# doesn't have the ref at all — the case for a not-yet-pushed chain branch, or a hand-made local
# stand-in for a dependabot branch (as in this script's own test setup).
resolve_ref() {
  if git -C "$repo_root" rev-parse --verify -q "refs/remotes/origin/$1" >/dev/null; then echo "origin/$1"; else echo "$1"; fi
}

# --- build the local branch chain -----------------------------------------------------------
today() { date +%Y-%m-%d; }

compute_plan() {   # $1 = ordered PR JSON array -> {date, prs:[...+slug+k+branch]} on stdout
  local ordered="$1" date
  date="$(today)"
  # slug_of is a bash function (jq can't call it), so the slug is attached entry by entry first.
  local n slugged="[]"
  n="$(jq 'length' <<<"$ordered")"
  local i=0
  while [ "$i" -lt "$n" ]; do
    local item ref slug
    item="$(jq -c ".[$i]" <<<"$ordered")"
    ref="$(jq -r '.headRefName' <<<"$item")"
    slug="$(slug_of "$ref")"
    slugged="$(jq --argjson item "$item" --arg slug "$slug" '. + [$item + {slug: $slug}]' <<<"$slugged")"
    i=$((i + 1))
  done
  jq --arg date "$date" '
    to_entries | map(
      (.key + 1) as $k
      | .value + {k: $k, branch: ("deps/" + $date + "/" + ($k | tostring) + "-" + .value.slug)}
    ) as $entries
    | {date: $date, prs: $entries}
  ' <<<"$slugged"
}

write_plan() {   # $1 = ordered PR JSON array -> writes plan_file, prints it
  mkdir -p "$plan_dir"
  compute_plan "$1" | tee "$plan_file"
}

base_branch_of() {   # $1 = plan JSON, $2 = k (1-based) -> the base branch name for k
  local plan="$1" k="$2"
  if [ "$k" -le 1 ]; then echo main; return; fi
  jq -r --argjson k "$((k - 1))" '.prs[] | select(.k == $k) | .branch' <<<"$plan"
}

build_chain() {   # $1 = plan JSON -> builds/continues the local branch chain
  local plan="$1" n k
  n="$(jq '.prs | length' <<<"$plan")"
  [ "$n" -gt 0 ] || { echo "deps-stack: nothing to build (no PRs after discovery/--skip)"; return 0; }

  if [ "$dry" -eq 1 ]; then
    # Simulated preview only: no real worktree/branch exists yet to resolve heads/bases against,
    # so this never touches git beyond the (printed, not run) commands below.
    echo "+ git worktree add --detach $wt_rel origin/main   # (removed and recreated first if stale)"
    echo "+ git -C $wt_rel fetch -q origin"
    k=1
    while [ "$k" -le "$n" ]; do
      local entry branch base_name head_ref pr_base
      entry="$(jq -c --argjson k "$k" '.prs[] | select(.k == $k)' <<<"$plan")"
      branch="$(jq -r '.branch' <<<"$entry")"
      base_name="$(base_branch_of "$plan" "$k")"
      head_ref="$(jq -r '.headRefName' <<<"$entry")"
      pr_base="$(jq -r '.baseRefName' <<<"$entry")"
      echo "building $branch (base: $base_name)"
      echo "+ git -C $wt_rel checkout -q -b $branch $base_name"
      echo "+ git -C $wt_rel cherry-pick \$(git rev-list --reverse origin/$pr_base..origin/$head_ref)  # PR #$(jq -r '.number' <<<"$entry")'s own commit(s)"
      k=$((k + 1))
    done
    return 0
  fi

  ensure_worktree "$([ "$resume" -eq 1 ] && echo resume || echo fresh)"
  run git -C "$wt_dir" fetch -q origin || true
  k=1
  while [ "$k" -le "$n" ]; do
    local entry branch base_name base_ref head_ref pr_base
    entry="$(jq -c --argjson k "$k" '.prs[] | select(.k == $k)' <<<"$plan")"
    branch="$(jq -r '.branch' <<<"$entry")"
    base_name="$(base_branch_of "$plan" "$k")"
    if git -C "$wt_dir" rev-parse --verify -q "refs/heads/$branch" >/dev/null; then
      if [ -e "$(git -C "$wt_dir" rev-parse --git-path CHERRY_PICK_HEAD)" ] && [ "$(git -C "$wt_dir" branch --show-current)" = "$branch" ]; then
        if auto_resolve_bumps && git -C "$wt_dir" cherry-pick --continue; then
          echo "deps-stack: auto-resolved a bump-vs-bump conflict left over on $branch"
        else
          echo "deps-stack: $branch has an unresolved cherry-pick — finish it first:"
          print_conflict_instructions "$branch"
          exit 1
        fi
      fi
      echo "have $branch (base: $base_name) — skipping build"
      k=$((k + 1))
      continue
    fi
    base_ref="$(resolve_ref "$base_name")"
    echo "building $branch (base: $base_ref)"
    run git -C "$wt_dir" checkout -q -b "$branch" "$base_ref"
    head_ref="$(resolve_ref "$(jq -r '.headRefName' <<<"$entry")")"
    pr_base="$(resolve_ref "$(jq -r '.baseRefName' <<<"$entry")")"
    local commits
    commits="$(git -C "$wt_dir" rev-list --reverse "$pr_base..$head_ref" 2>/dev/null || true)"
    if [ -z "$commits" ]; then
      echo "deps-stack: no commits found on $head_ref past $pr_base — is it fetched? (git -C $wt_rel fetch origin $branch)" >&2
      exit 1
    fi
    while read -r c; do
      [ -n "$c" ] || continue
      if ! git -C "$wt_dir" cherry-pick "$c"; then
        if auto_resolve_bumps && git -C "$wt_dir" cherry-pick --continue; then
          echo "deps-stack: auto-resolved a bump-vs-bump conflict cherry-picking $c onto $branch"
        else
          print_conflict_instructions "$branch"
          exit 1
        fi
      fi
    done <<<"$commits"
    k=$((k + 1))
  done
}

# --- publish: option (b) — new stacked PRs, original dependabot PRs closed -----------------
publish_chain() {
  local plan="$1" n k
  n="$(jq '.prs | length' <<<"$plan")"
  [ "$n" -gt 0 ] || return 0
  if [ "$dry" -eq 0 ]; then
    gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
  fi
  k=1
  while [ "$k" -le "$n" ]; do
    local entry branch base_name orig_num orig_title
    entry="$(jq -c --argjson k "$k" '.prs[] | select(.k == $k)' <<<"$plan")"
    branch="$(jq -r '.branch' <<<"$entry")"
    base_name="$(base_branch_of "$plan" "$k")"
    orig_num="$(jq -r '.number' <<<"$entry")"
    orig_title="$(jq -r '.title' <<<"$entry")"

    if git -C "$repo_root" rev-parse --verify -q "refs/heads/$branch" >/dev/null; then
      if git -C "$repo_root" rev-parse --verify -q "refs/remotes/origin/$branch" >/dev/null; then
        run git -C "$repo_root" push -q --force-with-lease origin "$branch"
      else
        run git -C "$repo_root" push -q -u origin "$branch"
      fi
    fi

    if gh pr view "$branch" --json number -q .number >/dev/null 2>&1; then
      echo "have PR for $branch already — leaving it (run 'just uprds' equivalent by hand if it needs a body refresh)"
      k=$((k + 1))
      continue
    fi

    local title body extra
    title="$(cap_title "$orig_title")"
    body="$(mktemp)"
    extra="$(mktemp)"
    {
      echo "Stacked dependency-update PR — originally dependabot PR #$orig_num, rebased here as"
      echo "part of a \`just deps-stack\` chain. dependabot's own branch is untouched; #$orig_num"
      echo "will be closed with a pointer to this one. Merge bottom-up (docs/DEV-FLOW.md §6-7)."
    } > "$extra"
    if [ "$dry" -eq 1 ]; then
      echo "===== $branch (base: $base_name, was PR #$orig_num) ====="
      BRANCH="$branch" BASE="$base_name" EXTRA_FILE="$extra" run "$script_dir/uprd.sh" --dry-run
      rm -f "$body" "$extra"
      k=$((k + 1))
      continue
    fi
    BRANCH="$branch" BASE="$base_name" EXTRA_FILE="$extra" "$script_dir/uprd.sh" --dry-run 2>/dev/null | sed '1d' > "$body"
    local new_url new_num
    new_url="$(gh pr create --base "$base_name" --head "$branch" --title "$title" --body-file "$body")"
    new_num="$(sed -n 's#.*/pull/\([0-9]*\)$#\1#p' <<<"$new_url")"
    echo "created $new_url (from #$orig_num)"
    gh pr close "$orig_num" --comment "stacked as #$new_num via \`just deps-stack\`" >/dev/null || \
      echo "deps-stack: could not close #$orig_num — close it by hand once #$new_num looks right" >&2
    rm -f "$body" "$extra"
    k=$((k + 1))
  done
}

chain_branches_of() {   # $1 = plan JSON -> branch names, bottom to top
  jq -r '.prs | sort_by(.k) | .[].branch' <<<"$1"
}

cmd_status() {   # read-only: never checks out anything, in this checkout or the worktree
  [ -f "$plan_file" ] || { echo "deps-stack: no chain built yet — run 'just deps-stack' first"; return 0; }
  local plan; plan="$(cat "$plan_file")"
  echo "chain date: $(jq -r '.date' <<<"$plan")"
  if worktree_registered; then
    echo "worktree: $wt_rel"
  else
    echo "worktree: $wt_rel (not present — removed by 'clean', or not built yet)"
  fi
  local k n
  n="$(jq '.prs | length' <<<"$plan")"
  k=1
  while [ "$k" -le "$n" ]; do
    local entry branch base_name pr
    entry="$(jq -c --argjson k "$k" '.prs[] | select(.k == $k)' <<<"$plan")"
    branch="$(jq -r '.branch' <<<"$entry")"
    base_name="$(base_branch_of "$plan" "$k")"
    pr="$(gh pr view "$branch" --json number,state -q '"#\(.number) \(.state)"' 2>/dev/null || echo "no PR")"
    printf '%-40s base=%-28s %s  (orig #%s, ahead of main: %s)\n' \
      "$branch" "$base_name" "$pr" \
      "$(jq -r '.number' <<<"$entry")" \
      "$(git -C "$repo_root" rev-list --count origin/main.."$branch" 2>/dev/null || echo '?')"
    k=$((k + 1))
  done
}

cmd_clean() {
  [ -f "$plan_file" ] || { echo "deps-stack: no chain built yet — nothing to clean"; return 0; }
  gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
  local plan; plan="$(cat "$plan_file")"
  # A chain branch is very likely checked out in the worktree right now (build_chain leaves it on
  # the tip) — `git branch -D` refuses to delete a branch checked out anywhere, so detach the
  # worktree first. This only ever touches $wt_dir, never the caller's own checkout.
  if [ "$dry" -eq 0 ] && worktree_registered; then
    git -C "$wt_dir" checkout -q --detach origin/main 2>/dev/null || true
  fi
  local b state all_merged=1
  while read -r b; do
    [ -n "$b" ] || continue
    state="$(gh pr view "$b" --json state -q .state 2>/dev/null || echo NONE)"
    if [ "$state" = MERGED ]; then
      echo "deleting $b (PR merged)"
      run git -C "$repo_root" branch -D "$b"
      if git -C "$repo_root" rev-parse --verify -q "origin/$b" >/dev/null 2>&1; then
        run git -C "$repo_root" push -q origin --delete "$b" || echo "  (already gone on origin)"
      fi
    else
      echo "keep $b (PR: $state)"
      all_merged=0
    fi
  done <<<"$(chain_branches_of "$plan")"
  if [ "$dry" -eq 0 ]; then
    rm -f "$plan_file"
    if [ "$all_merged" -eq 1 ] && worktree_registered; then
      echo "deps-stack: every chain branch merged — removing worktree $wt_rel"
      git -C "$repo_root" worktree remove --force "$wt_dir"
      git -C "$repo_root" worktree prune 2>/dev/null || true
    fi
  fi
}

# --- entry point ------------------------------------------------------------------------------
if [ "$self_test" -eq 1 ]; then self_test; exit 0; fi

case "$subcommand" in
  status) cmd_status; exit 0 ;;
  clean) cmd_clean; exit 0 ;;
esac

if [ "$resume" -eq 1 ]; then
  [ -f "$plan_file" ] || { echo "deps-stack: --resume needs a previous run's plan — none found at $plan_file; run 'just deps-stack' first" >&2; exit 1; }
  plan="$(cat "$plan_file")"
  echo "resuming chain from $plan_file (date $(jq -r '.date' <<<"$plan"))"
else
  ordered="$(discover)"
  n="$(jq 'length' <<<"$ordered")"
  if [ "$n" -eq 0 ]; then
    echo "deps-stack: no open dependency PRs found (after discovery/--skip)"
    exit 0
  fi
  echo "discovered $n PR(s), in stack order:"
  jq -r '.[] | "  #\(.number) [\(.ecosystem)] \(.title)"' <<<"$ordered"
  if [ "$dry" -eq 1 ]; then
    plan="$(compute_plan "$ordered")"
    echo "would write plan to $plan_file:"
    jq . <<<"$plan"
  else
    plan="$(write_plan "$ordered")"
  fi
fi

build_chain "$plan"

if [ "$dry" -eq 1 ]; then
  echo "dry-run: stopping before publish (push/PR creation/close) and link — no gh mutation."
  exit 0
fi

publish_chain "$plan"
mapfile -t chain_brs < <(chain_branches_of "$plan")
stack_link "$dry" "${chain_brs[@]}"
