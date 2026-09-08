#!/usr/bin/env bash
# cost-fill — add a missing `Cost:` and/or `Tested:` trailer to every commit of the current branch
# that lacks one (AGENTS.md "Attribution and cost accounting"), without git filter-branch: each
# commit is replayed with `git cherry-pick` onto a scratch branch, only commits missing a trailer
# are amended, and author/committer dates are preserved (`GIT_COMMITTER_DATE` set from the
# original commit before every cherry-pick and every amend — cherry-pick already keeps the author
# date on its own). just cost-fill # fill missing trailers on the current branch, in place just
# cost-fill --dry-run # print what would be added per commit, change nothing `Cost:` prefers a
# measured figure from `scripts/cost-split.py`'s session logs (the same numbers `just cost-split`
# would print); when nothing was logged for that commit at all — a subagent whose worktree session
# was never re-attached, a commit made on another machine — it falls back to
# `scripts/cost-split.py --estimate-commit`, always labelled `est.` so it can't be mistaken for a
# measurement.
set -euo pipefail

dry_run=0
for a in "$@"; do [ "$a" = "--dry-run" ] && dry_run=1; done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
branch="$(git branch --show-current)"
[ -n "$branch" ] || { echo "cost-fill: detached HEAD — check out a branch first" >&2; exit 1; }
[ "$branch" != main ] || { echo "cost-fill: refusing to rewrite main" >&2; exit 1; }
git fetch -q origin main 2>/dev/null || true

mapfile -t shas < <(git log --reverse --format=%H origin/main..HEAD)
if [ "${#shas[@]}" -eq 0 ]; then
  echo "cost-fill: no commits ahead of origin/main on $branch"
  exit 0
fi

TESTED_FALLBACK="Tested: ci-only — trailer added by just cost-fill, gates not re-run"

# Precompute what each commit needs before touching history at all — a diff's size (and hence its
# estimate) doesn't change when its message gains a trailer, so it's safe to ask cost-split.py
# about the *original* shas up front, once, rather than mid-rewrite.
json_file="$(mktemp)"
python3 "$script_dir/cost-split.py" --estimate --json >"$json_file" 2>/dev/null || echo '[]' >"$json_file"

measured_trailer() {   # $1 = short (7-char) sha, $2 = the cost-split.py --json file -> "Cost: ..." or empty
  python3 - "$1" "$2" <<'PY'
import json
import sys

sha, path = sys.argv[1], sys.argv[2]
try:
    with open(path, encoding="utf-8") as fh:
        rows = json.load(fh)
except (json.JSONDecodeError, OSError):
    rows = []
for r in rows:
    if r.get("commit") != sha or r.get("estimated") or not r.get("tokens"):
        continue
    usd = f"~${r['usd']:.2f}" if r.get("usd") is not None else "$n/a"
    tokens = r["tokens"]
    tok = f"{tokens / 1e6:.1f}M tokens" if tokens >= 1_000_000 else f"{tokens / 1e3:.0f}k tokens"
    cache_read = r.get("cache_read", 0)
    share = f"{100 * cache_read / tokens:.0f}% cache reads" if tokens else "no tokens"
    models = ", ".join(r.get("models", []))
    print(f"Cost: {usd} · {tok}, {share} ({models}) · scripts/cost-split.py, just cost-fill")
    break
PY
}

declare -A new_cost new_tested
any=0
for sha in "${shas[@]}"; do
  body="$(git log -1 --format=%B "$sha")"
  short="${sha:0:7}"
  if ! grep -q '^Cost:' <<<"$body"; then
    line="$(measured_trailer "$short" "$json_file")"
    [ -n "$line" ] || line="$(python3 "$script_dir/cost-split.py" --estimate-commit "$sha" 2>/dev/null || true)"
    if [ -n "$line" ]; then
      new_cost["$sha"]="$line"
      any=1
    fi
  elif ! grep -q '^Cost:.*\$' <<<"$body"; then
    # A Cost: line is present but carries no dollar figure at all — a bare "Cost: est."
    # placeholder typed by hand instead of run through this script or cost-split.py.
    line="$(measured_trailer "$short" "$json_file")"
    [ -n "$line" ] || line="$(python3 "$script_dir/cost-split.py" --estimate-commit "$sha" 2>/dev/null || true)"
    if [ -n "$line" ]; then
      new_cost["$sha"]="$line"
      any=1
    fi
  elif grep -q '^Cost:.* est\. ' <<<"$body"; then
    # An estimate written before the session log covered the commit (a subagent's own commit, say)
    # is upgraded to the measured figure once one exists — never the other way round.
    line="$(measured_trailer "$short" "$json_file")"
    if [ -n "$line" ]; then
      new_cost["$sha"]="$line"
      any=1
    fi
  fi
  if ! grep -q '^Tested:' <<<"$body"; then
    new_tested["$sha"]="$TESTED_FALLBACK"
    any=1
  fi
done
rm -f "$json_file"

if [ "$any" -eq 0 ]; then
  echo "cost-fill: every commit on $branch already carries Cost: and Tested: trailers"
  exit 0
fi

if [ "$dry_run" -eq 1 ]; then
  echo "cost-fill --dry-run: would rewrite these commits on $branch (author/committer dates preserved):"
  for sha in "${shas[@]}"; do
    [ -n "${new_cost[$sha]:-}${new_tested[$sha]:-}" ] || continue
    echo "  $(git log -1 --format='%h %s' "$sha")"
    [ -n "${new_tested[$sha]:-}" ] && echo "    + ${new_tested[$sha]}"
    [ -n "${new_cost[$sha]:-}" ] && echo "    + ${new_cost[$sha]}"
  done
  exit 0
fi

# Rebuilds the trailer block in AGENTS.md's fixed order (Tested, Cost, then whatever else was
# already there — Co-Authored-By included) instead of just appending: a commit that already has a
# Tested: trailer and is only missing Cost: must end up Tested/Cost/Co-Authored-By, not
# Tested/Co-Authored-By/Cost.
insert_trailers() {   # $1 = old message, $2 = new Tested line or "", $3 = new Cost line or ""
  python3 - "$1" "$2" "$3" <<'PY'
import re
import sys

msg, tested_new, cost_new = sys.argv[1], sys.argv[2], sys.argv[3]
lines = msg.rstrip("\n").split("\n")
trailer_re = re.compile(r"^[A-Za-z][A-Za-z0-9-]*:\s")
i = len(lines)
while i > 0 and trailer_re.match(lines[i - 1]):
    i -= 1
existing = lines[i:]
body = lines[:i]
while body and body[-1].strip() == "":
    body.pop()

tested_line = tested_new or next((line for line in existing if line.startswith("Tested:")), None)
cost_line = cost_new or next((line for line in existing if line.startswith("Cost:")), None)
others = [line for line in existing if not line.startswith("Tested:") and not line.startswith("Cost:")]
ordered = [line for line in (tested_line, cost_line) if line] + others

sys.stdout.write("\n".join(body) + "\n\n" + "\n".join(ordered) + "\n")
PY
}

# The rewrite replays the branch onto origin/main.
git fetch -q origin main 2>/dev/null || true
if ! git merge-base --is-ancestor origin/main "$branch"; then
  echo "cost-fill: $branch is behind origin/main — rebase it first (git rebase origin/main), then re-run" >&2
  exit 1
fi
tmp_branch="cost-fill-tmp-$$"
git branch -f "$tmp_branch" origin/main >/dev/null
git checkout -q "$tmp_branch"
# Whatever happens mid-replay (a conflict, Ctrl-C), leave the caller on their own branch with a
# clean tree, not on the temp branch in a half-done cherry-pick.
trap 'git cherry-pick --abort >/dev/null 2>&1 || true; git checkout -q -f "$branch" 2>/dev/null || git checkout -q "$branch" 2>/dev/null; git branch -D "$tmp_branch" >/dev/null 2>&1 || true' EXIT

for sha in "${shas[@]}"; do
  cdate="$(git log -1 --format=%cI "$sha")"
  prev_head="$(git rev-parse HEAD)"
  # --allow-empty preserves a commit that was already empty at authoring time; --empty=drop
  # instead skips one that becomes empty here because its content is already on this branch's new
  # base (e.g. the PR this branch was stacked on has since been squash-merged into main under a
  # different sha) — a stale-base symptom cost-fill should not crash on.
  GIT_COMMITTER_DATE="$cdate" git cherry-pick --allow-empty --empty=drop "$sha" >/dev/null
  if [ "$(git rev-parse HEAD)" = "$prev_head" ]; then
    echo "cost-fill: $sha's diff is already on $branch's base — skipped (rebase this branch onto origin/main)" >&2
    continue
  fi
  tested="${new_tested[$sha]:-}"
  cost="${new_cost[$sha]:-}"
  if [ -n "$tested$cost" ]; then
    old_msg="$(git log -1 --format=%B)"
    new_msg="$(insert_trailers "$old_msg" "$tested" "$cost")"
    GIT_COMMITTER_DATE="$cdate" git commit -q --amend -m "$new_msg"
  fi
done
git branch -f "$branch" "$tmp_branch"
trap - EXIT
git checkout -q "$branch"
git branch -D "$tmp_branch" >/dev/null

# `${#assoc[@]}` on an empty associative array trips `set -u` ("new_tested: unbound variable" —
# seen on bash 5.3 after a run that only added Cost: trailers), so count without expanding it.
n_tested=0; n_cost=0
for _ in "${!new_tested[@]}"; do n_tested=$((n_tested + 1)); done 2>/dev/null || true
for _ in "${!new_cost[@]}"; do n_cost=$((n_cost + 1)); done 2>/dev/null || true
echo "cost-fill: added $n_tested Tested: and $n_cost Cost: trailer(s) across ${#shas[@]} commit(s) on $branch"
echo "next: git push --force-with-lease origin $branch"
