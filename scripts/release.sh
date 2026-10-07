#!/usr/bin/env bash
# release: tag main as vX.Y.Z and push the tag; release.yml publishes the GitHub release, and
# Zenodo's webhook archives it with .zenodo.json and mints the version DOI (MIP-0079). A DOI
# cannot be deleted, so this refuses anything but a clean main level with origin and valid
# citation metadata.
#   just release 0.2.0 [--dry-run]
#   scripts/release.sh --self-test
set -euo pipefail

valid_tag() { [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; }

if [ "${1:-}" = --self-test ]; then
  for t in v0.2.0 v10.0.12; do valid_tag "$t" || { echo "release: rejected $t" >&2; exit 1; }; done
  for t in v0.2 0.2.0 v0.2.0-rc1; do
    if valid_tag "$t"; then echo "release: accepted $t" >&2; exit 1; fi
  done
  echo "release: self-test ok" >&2
  exit 0
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
[ -z "$(git status --porcelain --untracked-files=no)" ] \
  || { echo "release: the working tree is dirty; commit or stash first" >&2; exit 1; }
git fetch -q --tags origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] \
  || { echo "release: main is not level with origin/main; pull or push first" >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  echo "release: tag $tag already exists" >&2
  exit 1
fi
python3 scripts/citation.py --check

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
