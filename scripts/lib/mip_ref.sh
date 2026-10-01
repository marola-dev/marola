#!/usr/bin/env bash
# mip_ref — shared MIP-number detection for scripts/uprd.sh and .github/workflows/pr-body.yml's
# title step, plus MIP-0070 §5.6's umbrella resolution for uprd.sh/issues.sh.
detect_mip_ref() {
  local branch="$1" range="$2" mip_ref=""
  if [[ "$branch" =~ [Mm][Ii][Pp]-([0-9]{4}) ]]; then
    mip_ref="MIP-${BASH_REMATCH[1]}"
  else
    mip_ref="$(git log --format='%s' "$range" 2>/dev/null \
      | { grep -ioE '^MIP-[0-9]{4}' || true; } | head -1 | tr '[:lower:]' '[:upper:]')"
  fi
  if [ -z "$mip_ref" ]; then
    mip_ref="$(git diff --name-only "$range" -- docs/MIPs 2>/dev/null \
      | { grep -oE 'MIP-[0-9]{4}' || true; } | sort -u | { [ "$(wc -l)" -eq 1 ] && cat || true; })"
  fi
  printf '%s' "$mip_ref"
}

# The umbrella's owner/repo — one devkit setting, never a literal elsewhere (§5.6).
MAROLA_UMBRELLA="${MAROLA_UMBRELLA:-marola-dev/marola}"

# _mip_glob_first <dir> <MIP-NNNN> <doc|tasks> -> docs/MIPs/<name> relative to <dir>, or nothing.
# Plain filesystem glob, not git: an umbrella sibling (../docs/MIPs) need not be a git repo for
# this to find it, and a MIP doc not yet committed still resolves for `issues.sh tasks-to-issues`.
_mip_glob_first() {
  local dir="$1" mip="$2" kind="$3" f
  [ -d "$dir/docs/MIPs" ] || return 1
  if [ "$kind" = tasks ]; then
    [ -f "$dir/docs/MIPs/$mip.tasks.md" ] && { printf 'docs/MIPs/%s.tasks.md\n' "$mip"; return 0; }
    return 1
  fi
  for f in "$dir"/docs/MIPs/"$mip"-*.md; do
    [ -f "$f" ] || continue
    printf 'docs/MIPs/%s\n' "$(basename "$f")"
    return 0
  done
  return 1
}

# resolve_mip_path <MIP-NNNN> [tasks] [git-ref] -> "local<TAB>path" or "$MAROLA_UMBRELLA<TAB>path".
# §5.6 "the devkit finds the umbrella": once a code repo is extracted it carries no docs/MIPs of
# its own. Tried in order, first hit wins:
#   1. this repo — git ls-tree at <git-ref> when given (a branch that may not be checked out
#      locally, as uprd.sh needs), otherwise the plain working tree (issues.sh's use, over $root).
#      Unchanged monorepo behaviour.
#   2. ../docs/MIPs/ — this checkout sits inside an umbrella clone.
#   3. gh api repos/$MAROLA_UMBRELLA/contents/docs/MIPs — no umbrella sibling on disk.
# Nothing on stdout, exit 1, when none of the three has it.
resolve_mip_path() {
  local mip="$1" kind="${2:-doc}" ref="${3:-}" path name pat

  # Each probe below may legitimately find nothing before the next one succeeds — `|| true`
  # throughout, or `set -e`/pipefail (both on in every caller) aborts the function on the first
  # miss instead of falling through.
  if [ -n "$ref" ]; then
    if [ "$kind" = tasks ]; then
      path="$(git ls-tree -r --name-only "$ref" -- "docs/MIPs/$mip.tasks.md" 2>/dev/null | head -1 || true)"
    else
      path="$(git ls-tree -r --name-only "$ref" -- docs/MIPs 2>/dev/null \
        | { grep -E "^docs/MIPs/${mip}-[^/]+\.md\$" || true; } | head -1)"
    fi
  else
    path="$(_mip_glob_first . "$mip" "$kind" || true)"
  fi
  [ -n "$path" ] && { printf 'local\t%s\n' "$path"; return 0; }

  path="$(_mip_glob_first .. "$mip" "$kind" || true)"
  [ -n "$path" ] && { printf '%s\t%s\n' "$MAROLA_UMBRELLA" "$path"; return 0; }

  [ "$kind" = tasks ] && pat="^${mip}\.tasks\.md\$" || pat="^${mip}-[^/]+\.md\$"
  name="$(gh api "repos/$MAROLA_UMBRELLA/contents/docs/MIPs" --jq '.[].name' </dev/null 2>/dev/null \
    | { grep -E "$pat" || true; } | head -1)"
  [ -n "$name" ] && { printf '%s\tdocs/MIPs/%s\n' "$MAROLA_UMBRELLA" "$name"; return 0; }

  return 1
}

# resolve_mip_file <MIP-NNNN> [tasks] [git-ref] -> the file's content on stdout, via
# resolve_mip_path's same three-step order. Exit 1, nothing on stdout, when unresolved everywhere.
resolve_mip_file() {
  local mip="$1" kind="${2:-doc}" ref="${3:-}" source path
  IFS=$'\t' read -r source path < <(resolve_mip_path "$mip" "$kind" "$ref") || return 1
  [ -n "$path" ] || return 1
  if [ "$source" = local ]; then
    if [ -n "$ref" ]; then git show "$ref:$path" 2>/dev/null; else cat "./$path" 2>/dev/null; fi
  elif [ -f "../$path" ]; then
    cat "../$path"
  else
    gh api "repos/$MAROLA_UMBRELLA/contents/$path" -H 'Accept: application/vnd.github.raw' </dev/null 2>/dev/null
  fi
}
