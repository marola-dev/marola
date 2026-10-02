#!/usr/bin/env bash
# prepare-docs — build the umbrella's aggregated docs tree in a gitignored build directory: this
# repo's own docs/ plus every submodule's README.md + docs/ (MIP-0070 §5.5). The tracked docs/
# tree itself is never written to (ruling T10-8): scripts/mkdocs.sh stages and builds from the
# build directory instead, via its DOCS_SRC override. The build directory is wiped and rebuilt
# from scratch every run, so a submodule dropped from the manifest leaves nothing behind.
#
# Reads mkdocs/repos.yml (scripts/lib/repos_manifest.sh): submodule name -> mount point, default
# repos/<name>/, or ./ for the site root (mount_at_root). A submodule listed in the manifest but
# not checked out at the repo root is a hard error — docs.yml's `git submodule update` step is
# what keeps that from happening in CI.
#
# README links of the form `docs/X.md` or `./docs/X.md` become `X.md`: the README is mounted as
# <mount>/index.md, a sibling of the copied docs/** tree, not a parent of it. An absolute URL
# (GitHub code links, cross-repo docs.marola.dev links — MIP-0070 §5.5) is left alone. docs/**'s
# own relative links need no rewrite: copying preserves their tree shape.
#
#   scripts/prepare-docs.sh [BUILD_DIR]   # default .tmp/docs-aggregated, rebuilt fresh every run
#   scripts/prepare-docs.sh --self-test   # hermetic: a fake two-submodule tree, no network
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/repos_manifest.sh
source "$script_dir/lib/repos_manifest.sh"

mount_submodule() {
  local root="$1" name="$2" mount="$3" build_dir="$4"
  local src="$root/$name" dest="$build_dir/$mount"
  if [ ! -d "$src" ]; then
    echo "prepare-docs: $name is in mkdocs/repos.yml but not checked out at $src — run \`git submodule update --init\` first" >&2
    return 1
  fi
  [ "$mount" != "./" ] || { mount_at_root "$src" "$name" "$build_dir"; return; }
  rm -rf "$dest"
  mkdir -p "$dest"
  if [ -f "$src/README.md" ]; then
    sed -E 's#(\]\()(\./)?docs/#\1#g' "$src/README.md" >"$dest/index.md"
  fi
  [ -d "$src/docs" ] && cp -R "$src/docs/." "$dest/"
  return 0
}

# mount ./ (marola-app, so its pages keep their URLs, MIP-0070 §5.5): the root already holds the
# umbrella's pages and index.md, so the README is left out, docs/index.md becomes <name>.md, and a
# path both trees have fails instead of one silently replacing the other.
mount_at_root() {
  local src="$1" name="$2" build_dir="$3" rel dest clash=0
  [ -d "$src/docs" ] || return 0
  while IFS= read -r -d '' rel; do
    rel="${rel#./}"
    dest="$build_dir/$rel"
    [ "$rel" != index.md ] || dest="$build_dir/$name.md"
    if [ -e "$dest" ]; then
      echo "prepare-docs: $name/docs/$rel collides with ${dest#"$build_dir"/} in the umbrella's docs" >&2
      clash=1
      continue
    fi
    mkdir -p "$(dirname "$dest")"
    cp "$src/docs/$rel" "$dest"
  done < <(cd "$src/docs" && find . -type f -print0)
  [ "$clash" -eq 0 ]
}

# check_links <build_dir> <file>... -> every relative markdown link in each mounted page resolves
# to a real file, reported here rather than deep in a `mkdocs --strict` log. Covers both inline
# links (`[text](target)`) and reference-style definitions (`[label]: target`), outside code spans
# and fenced blocks. Absolute URLs (://), mailto:, in-page anchors (#...) and build_dir-absolute
# links (/...) are left alone — they are either external or mkdocs' own concern.
check_links() {
  local build_dir="$1"; shift
  local fails=0 f link target
  for f in "$@"; do
      while IFS= read -r link; do
        case "$link" in
          *://*|mailto:*|/*|'#'*) continue ;;
        esac
        target="${link%%#*}"
        [ -n "$target" ] || continue
        if [ ! -e "$(dirname "$f")/$target" ]; then
          echo "prepare-docs: ${f#"$build_dir"/}: broken relative link: $link" >&2
          fails=$((fails + 1))
        fi
      done < <(
        prose "$f" | grep -oE '\]\([^)]+\)' | sed -E 's/^\]\((.*)\)$/\1/'
        prose "$f" | grep -oE '^\[[^]]+\]:[ \t]+[^ \t]+' | sed -E 's/^\[[^]]+\]:[ \t]+//'
      )
  done
  [ "$fails" -eq 0 ]
}

# A page without its fenced blocks and inline code spans, where `](` is code, not a link.
prose() {
  # shellcheck disable=SC2016 # the backticks are sed's, not a shell expansion
  awk '/^[ \t]*(```|~~~)/ { fence = !fence; next } !fence' "$1" | sed -E 's/`[^`]*`//g'
}

prepare() {
  local root="$1" manifest="$2" docs_src="$3" build_dir="$4"
  rm -rf "$build_dir"
  mkdir -p "$build_dir"
  cp -R "$docs_src/." "$build_dir/"
  local name mount f
  local -a pages=()
  while IFS=$'\t' read -r name mount; do
    [ -n "$name" ] || continue
    # Explicit `|| return`, not bare `set -e`: a function called where its own exit status is
    # tested (as `prepare` is, by every caller below) runs with -e ignored throughout its body
    # (bash(1), "The -e option" — a well-known trap), so a failing mount_submodule would otherwise
    # be swallowed here instead of failing the whole run.
    mount_submodule "$root" "$name" "$mount" "$build_dir" || return 1
    # Only what this submodule brought: at ./ that is not everything under the mount.
    if [ "$mount" = "./" ] && [ -d "$root/$name/docs" ]; then
      while IFS= read -r -d '' f; do
        f="${f#./}"
        [ "$f" != index.md ] || f="$name.md"
        pages+=("$build_dir/$f")
      done < <(cd "$root/$name/docs" && find . -name '*.md' -print0)
    elif [ "$mount" != "./" ]; then
      while IFS= read -r -d '' f; do pages+=("$f"); done < <(find "$build_dir/$mount" -name '*.md' -print0)
    fi
  done < <(repos_manifest "$manifest")
  [ "${#pages[@]}" -gt 0 ] || return 0
  check_links "$build_dir" "${pages[@]}"
}

self_test() {
  local fails=0
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails + 1)); fi; }

  echo "-- mounts, the README -> index.md rewrite (both docs/X.md and ./docs/X.md), docs/** copy --"
  local t; t="$(mktemp -d)"
  mkdir -p "$t/root/fake-app/docs/sub"
  cat >"$t/root/fake-app/README.md" <<'EOF'
# fake-app

See [the architecture doc](docs/ARCHITECTURE.md) and [./docs/ prefixed](./docs/sub/notes.md).
Code: [Main.scala](https://github.com/marola-dev/fake-app/blob/main/Main.scala).
EOF
  echo "# architecture" >"$t/root/fake-app/docs/ARCHITECTURE.md"
  echo "# notes" >"$t/root/fake-app/docs/sub/notes.md"
  mkdir -p "$t/root/fake-site"
  echo "# fake-site, no docs/ of its own" >"$t/root/fake-site/README.md"
  mkdir -p "$t/docs-src/existing"
  echo "today's doc" >"$t/docs-src/existing/page.md"
  cat >"$t/manifest.yml" <<'EOF'
- name: fake-app
- name: fake-site
  mount: top/
EOF
  prepare "$t/root" "$t/manifest.yml" "$t/docs-src" "$t/build" || { echo "FAIL: prepare"; fails=$((fails + 1)); }

  ok "$([ -f "$t/build/repos/fake-app/index.md" ] && echo yes || echo no)" "yes" "default mount repos/<name>/ gets the README as index.md"
  ok "$([ -f "$t/build/repos/fake-app/ARCHITECTURE.md" ] && echo yes || echo no)" "yes" "docs/ARCHITECTURE.md copied flat under the mount"
  ok "$([ -f "$t/build/repos/fake-app/sub/notes.md" ] && echo yes || echo no)" "yes" "docs/sub/notes.md keeps its subdirectory"
  ok "$(grep -c '](ARCHITECTURE.md)' "$t/build/repos/fake-app/index.md")" "1" "a README link to docs/X.md is rewritten to X.md"
  ok "$(grep -c '](sub/notes.md)' "$t/build/repos/fake-app/index.md")" "1" "...and a ./docs/X.md link (marola's own README's actual style) is rewritten the same way"
  ok "$(grep -c 'github.com/marola-dev/fake-app' "$t/build/repos/fake-app/index.md")" "1" "an absolute GitHub code link is left untouched"
  ok "$([ -f "$t/build/top/index.md" ] && echo yes || echo no)" "yes" "an explicit mount overrides the repos/<name>/ default"
  ok "$([ -f "$t/build/existing/page.md" ] && echo yes || echo no)" "yes" "the umbrella's own docs_src is copied into the build dir too"
  ok "$([ -f "$t/docs-src/existing/page.md" ] && echo yes || echo no)" "yes" "...and docs_src itself is untouched (read-only input, T10-8)"
  ok "$([ -e "$t/docs-src/repos" ] && echo yes || echo no)" "no" "...nothing from the build gets written back into the tracked docs tree"
  rm -rf "$t"

  echo
  echo "-- empty manifest: build dir is an exact copy of docs_src, which stays untouched --"
  t="$(mktemp -d)"
  mkdir -p "$t/root" "$t/docs-src/existing"
  echo "today's doc" >"$t/docs-src/existing/page.md"
  printf '# no submodules yet\n' >"$t/manifest.yml"
  local before
  before="$(cd "$t/docs-src" && find . -type f | sort | xargs -I{} sh -c 'echo {}; cat {}')"
  prepare "$t/root" "$t/manifest.yml" "$t/docs-src" "$t/build" || { echo "FAIL: prepare on empty manifest"; fails=$((fails + 1)); }
  local after_src after_build
  after_src="$(cd "$t/docs-src" && find . -type f | sort | xargs -I{} sh -c 'echo {}; cat {}')"
  after_build="$(cd "$t/build" && find . -type f | sort | xargs -I{} sh -c 'echo {}; cat {}')"
  ok "$([ "$before" = "$after_src" ] && echo unchanged || echo changed)" "unchanged" "docs_src is never written to"
  ok "$([ "$before" = "$after_build" ] && echo samecontent || echo different)" "samecontent" "the build dir is just docs_src's content ('just docs' with zero submodules)"
  rm -rf "$t"

  echo
  echo "-- a removed mount does not survive a re-run (ruling T10-8) --"
  t="$(mktemp -d)"
  mkdir -p "$t/root/fake-one" "$t/root/fake-two" "$t/docs-src"
  echo "# one" >"$t/root/fake-one/README.md"
  echo "# two" >"$t/root/fake-two/README.md"
  printf -- '- name: fake-one\n- name: fake-two\n' >"$t/manifest.yml"
  prepare "$t/root" "$t/manifest.yml" "$t/docs-src" "$t/build" || { echo "FAIL: first prepare"; fails=$((fails + 1)); }
  ok "$([ -f "$t/build/repos/fake-one/index.md" ] && echo yes || echo no)" "yes" "both mounts exist after the first run"
  ok "$([ -f "$t/build/repos/fake-two/index.md" ] && echo yes || echo no)" "yes" "..."
  printf -- '- name: fake-one\n' >"$t/manifest.yml"
  prepare "$t/root" "$t/manifest.yml" "$t/docs-src" "$t/build" || { echo "FAIL: second prepare"; fails=$((fails + 1)); }
  ok "$([ -f "$t/build/repos/fake-one/index.md" ] && echo yes || echo no)" "yes" "the still-listed mount survives the re-run"
  ok "$([ -e "$t/build/repos/fake-two" ] && echo yes || echo no)" "no" "the dropped mount does not — the build dir is rebuilt from scratch"
  rm -rf "$t"

  echo
  echo "-- mount ./ puts a submodule's docs/ beside the umbrella's own pages --"
  t="$(mktemp -d)"
  mkdir -p "$t/root/fake-app/docs/1-Using" "$t/docs-src/existing"
  echo "# fake-app readme" >"$t/root/fake-app/README.md"
  echo "# fake-app docs index, see [run](1-Using/RUN.md)" >"$t/root/fake-app/docs/index.md"
  echo "# run, back to [the umbrella](../existing/page.md)" >"$t/root/fake-app/docs/1-Using/RUN.md"
  echo "# umbrella index, see [run](1-Using/RUN.md)" >"$t/docs-src/index.md"
  echo "today's doc, with [a link mkdocs checks](not-here.md)" >"$t/docs-src/existing/page.md"
  printf -- '- name: fake-app\n  mount: ./\n' >"$t/manifest.yml"
  rc=0
  out="$(prepare "$t/root" "$t/manifest.yml" "$t/docs-src" "$t/build" 2>&1)" || rc=$?
  ok "$rc" "0" "a root mount builds, checking the submodule's pages only (the umbrella's are mkdocs')"
  ok "$([ -f "$t/build/existing/page.md" ] && echo yes || echo no)" "yes" "the umbrella's own pages survive the mount"
  ok "$(cat "$t/build/index.md" 2>/dev/null)" "# umbrella index, see [run](1-Using/RUN.md)" "the site's index.md stays the umbrella's"
  ok "$([ -f "$t/build/1-Using/RUN.md" ] && echo yes || echo no)" "yes" "docs/1-Using/RUN.md lands at the site root's 1-Using/"
  ok "$(head -c 24 "$t/build/fake-app.md" 2>/dev/null)" "# fake-app docs index, s" "the submodule's docs/index.md becomes <name>.md"
  ok "$(grep -rl 'fake-app readme' "$t/build" 2>/dev/null | wc -l | tr -d ' ')" "0" "its README is not mounted (the root has an index already)"
  echo "# a second umbrella page" >"$t/root/fake-app/docs/1-Using/clash.md"
  mkdir -p "$t/docs-src/1-Using"
  echo "# umbrella's" >"$t/docs-src/1-Using/clash.md"
  rc=0
  out="$(prepare "$t/root" "$t/manifest.yml" "$t/docs-src" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "a page both trees have fails the run"
  ok "$(printf '%s' "$out" | grep -c 'fake-app/docs/1-Using/clash.md collides')" "1" "...naming the colliding path"
  ok "$(cat "$t/build/1-Using/clash.md" 2>/dev/null)" "# umbrella's" "...and the umbrella's page is not overwritten"
  rm -rf "$t"

  echo
  echo "-- a broken relative link is reported (inline and reference-style) --"
  t="$(mktemp -d)"
  mkdir -p "$t/root/fake-broken/docs" "$t/docs-src"
  echo "# fake-broken" >"$t/root/fake-broken/README.md"
  cat >"$t/root/fake-broken/docs/page.md" <<'EOF'
See [gone](missing.md).

[ref]: also-missing.md

Code is not a link: `f[A](effect: A)`, and

```text
q='node(around:15000,-27.6,-48.4)[x](in-a-fence.md)'
```
EOF
  printf -- '- name: fake-broken\n' >"$t/manifest.yml"
  local out rc=0
  out="$(prepare "$t/root" "$t/manifest.yml" "$t/docs-src" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "a broken relative link fails the run"
  ok "$(printf '%s' "$out" | grep -c 'broken relative link: missing.md')" "1" "...an inline link, naming the file and the link"
  ok "$(printf '%s' "$out" | grep -c 'broken relative link: also-missing.md')" "1" "...and a reference-style [label]: target definition too"
  ok "$(printf '%s' "$out" | grep -c 'broken relative link')" "2" "...but nothing inside a code span or a fenced block"
  rm -rf "$t"

  echo
  echo "-- a manifest entry with no checked-out submodule is a hard error --"
  t="$(mktemp -d)"
  mkdir -p "$t/root" "$t/docs-src"
  printf -- '- name: fake-ghost\n' >"$t/manifest.yml"
  rc=0
  out="$(prepare "$t/root" "$t/manifest.yml" "$t/docs-src" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "a listed-but-absent submodule fails rather than being skipped"
  ok "$(printf '%s' "$out" | grep -c 'not checked out')" "1" "...with a diagnosis, not a silent gap"
  rm -rf "$t"

  echo
  if [ "$fails" -eq 0 ]; then echo "prepare-docs self-test: ok"; return 0; fi
  echo "prepare-docs self-test: $fails failure(s)" >&2
  return 1
}

root_default="$(cd "$script_dir/.." && pwd)"
case "${1:-}" in
  --self-test) self_test ;;
  -h|--help) sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  -*) echo "prepare-docs: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  *) prepare "$root_default" "$root_default/mkdocs/repos.yml" "$root_default/docs" "${1:-$root_default/.tmp/docs-aggregated}" ;;
esac
