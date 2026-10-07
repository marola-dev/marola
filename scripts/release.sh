#!/usr/bin/env bash
# release: tag main as vX.Y.Z and push the tag; release.yml publishes the GitHub release, and
# Zenodo's webhook archives it with .zenodo.json and mints the version DOI (MIP-0079). A DOI
# cannot be deleted, so this refuses anything but a clean main level with origin and valid
# citation metadata.
# Hard rule: a submodule pointer move never makes a Zenodo version. A release needs a change
# outside the gitlinks since the previous v* tag; release.yml runs the same --guard, so a tag
# pushed by hand around this script still gets no release.
#   just release 0.2.0 [--dry-run]
#   scripts/release.sh --guard <ref>     # exit 1 if <ref> only moves pointers since the last tag
#   scripts/release.sh --self-test
set -euo pipefail

valid_tag() { [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; }

# `git diff --raw` lines on stdin; prints those that are not a gitlink (mode 160000) on either side.
content_lines() { awk '{ src = substr($1, 2) } src != "160000" && $2 != "160000"'; }

# guard <ref>: the newest v* tag that is an ancestor of <ref> (other than <ref>'s own) is the base.
guard() {
  local ref="$1" base changes
  base="$(git describe --tags --abbrev=0 --match 'v*' --exclude "$(git describe --tags --exact-match "$ref" 2>/dev/null || echo none)" "$ref" 2>/dev/null || true)"
  if [ -z "$base" ]; then
    echo "release: no earlier v* tag before $ref; nothing to compare" >&2
    return 0
  fi
  changes="$(git diff --raw --no-renames "$base" "$ref" | content_lines)"
  if [ -z "$changes" ]; then
    echo "release: $base..$ref only moves submodule pointers; a pointer move never makes a Zenodo version" >&2
    return 1
  fi
  echo "release: $(wc -l <<<"$changes") file(s) changed outside the submodule pointers since $base" >&2
}

if [ "${1:-}" = --self-test ]; then
  for t in v0.2.0 v10.0.12; do valid_tag "$t" || { echo "release: rejected $t" >&2; exit 1; }; done
  for t in v0.2 0.2.0 v0.2.0-rc1; do
    if valid_tag "$t"; then echo "release: accepted $t" >&2; exit 1; fi
  done
  pointers=$':160000 160000 c62001b 3912182 M\tmarola-corpus\n:000000 160000 0000000 e0f5720 A\tmarola-ml\n:160000 000000 e0f5720 0000000 D\tmarola-old'
  [ -z "$(content_lines <<<"$pointers")" ] || { echo "release: pointer moves counted as content" >&2; exit 1; }
  mixed="$pointers"$'\n:100644 100644 94edc98 fdccf0a M\tdocs/PHASES.md'
  [ "$(content_lines <<<"$mixed")" = $':100644 100644 94edc98 fdccf0a M\tdocs/PHASES.md' ] \
    || { echo "release: a doc change was not counted" >&2; exit 1; }
  echo "release: self-test ok" >&2
  exit 0
fi

if [ "${1:-}" = --guard ]; then
  [ -n "${2:-}" ] || { echo "release: usage: scripts/release.sh --guard <ref>" >&2; exit 1; }
  guard "$2"
  exit
fi

dry_run=0 version=""
for a in "$@"; do
  case "$a" in
    --dry-run) dry_run=1 ;;
    *) version="$a" ;;
  esac
done
[ -n "$version" ] || { echo "release: usage: just release <X.Y.Z> [--dry-run]" >&2; exit 1; }
tag="v${version#v}"
valid_tag "$tag" || { echo "release: '$version' is not MAJOR.MINOR.PATCH" >&2; exit 1; }

cd "$(git rev-parse --show-toplevel)"
[ "$(git branch --show-current)" = main ] || { echo "release: check out main first" >&2; exit 1; }
[ -z "$(git status --porcelain --untracked-files=no --ignore-submodules=all)" ] \
  || { echo "release: the working tree is dirty; commit or stash first" >&2; exit 1; }
git fetch -q --tags origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] \
  || { echo "release: main is not level with origin/main; pull or push first" >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  echo "release: tag $tag already exists" >&2
  exit 1
fi
python3 scripts/citation.py --check
guard HEAD

prev="$(git tag -l 'v*' --sort=-v:refname | head -1)"
echo "release: $tag at $(git log -1 --format='%h %s') (previous: ${prev:-none})" >&2
if [ "$dry_run" -eq 1 ]; then
  echo "+ git tag -a $tag -m 'marola $tag'" >&2
  echo "+ git push origin $tag" >&2
  exit 0
fi
git tag -a "$tag" -m "marola $tag"
git push origin "$tag"
echo "release: pushed $tag; release.yml publishes it and Zenodo mints the DOI a few minutes later." >&2
