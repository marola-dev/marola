#!/usr/bin/env bash
# prepare-docs — build docs.marola.dev's source tree in a gitignored directory (MIP-0074 §5.3): the
# umbrella at the root (README.md as index.md, docs/** beside it, docs/MIPs/ at 6-MIPs/) and every
# repo in mkdocs/repos.yml at 5-Repos/<name>/ (its README.md as index.md, its docs/** beside it,
# its api-docs branch under api-docs/). scripts/lib/doc_links.py rewrites the links (Appendix A)
# and generates each adr/index.md; build.json and a line on each landing record the commit each
# repo was built from. A repo with no README.md, with both README.md and docs/index.md, or with a
# hand-written docs/adr/index.md fails the run. scripts/mkdocs.sh builds from this directory
# (DOCS_SRC); the tracked docs/ tree is never written to. See docs/3-Ways-of-working/DOCS-SITE.md.
#
#   scripts/prepare-docs.sh [BUILD_DIR]   # default .tmp/docs-aggregated, rebuilt fresh every run
#   scripts/prepare-docs.sh --self-test   # hermetic: fake repos and local remotes, no network
#
# DOCS_SOURCE_REMOTE (a flake-lock source) and API_DOCS_REMOTE (scripts/fetch-api-docs.sh) are URL
# templates; <repo> is replaced with the repo name.
set -euo pipefail

DOCS_SOURCE_REMOTE="${DOCS_SOURCE_REMOTE:-https://github.com/marola-dev/<repo>}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib/repos_manifest.sh
source "$script_dir/lib/repos_manifest.sh"

# A hook's GIT_DIR would point every git call below at the caller's repo, and a fetch must not stop
# a local build at a credential prompt.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX GIT_COMMON_DIR
export GIT_TERMINAL_PROMPT=0

# fetch_flake_lock_source <root> <name> -> the path of <name> checked out at
# flake.lock's nodes.<name>.locked.rev. python3, not nix: CI has no nix.
fetch_flake_lock_source() {
  local root="$1" name="$2" rev url dir
  rev="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["nodes"][sys.argv[2]]["locked"]["rev"])' "$root/flake.lock" "$name")" ||
    { echo "prepare-docs: no nodes.$name.locked.rev in $root/flake.lock" >&2; return 1; }
  url="${DOCS_SOURCE_REMOTE//<repo>/$name}"
  dir="$root/.tmp/docs-sources/$name"
  rm -rf "$dir"
  # Every step checks itself: callers run this in $(...), where errexit is off.
  git init -q "$dir" ||
    { echo "prepare-docs: could not create $dir for $name" >&2; return 1; }
  git -C "$dir" fetch -q --depth 1 "$url" "$rev" ||
    { echo "prepare-docs: could not fetch $name at $rev from $url" >&2; return 1; }
  git -C "$dir" -c advice.detachedHead=false checkout -q FETCH_HEAD ||
    { echo "prepare-docs: could not check out $name at $rev in $dir" >&2; return 1; }
  printf '%s\n' "$dir"
}

# checkout_sha <dir> -> HEAD of the checkout at <dir>, never of a repo enclosing it (an
# uninitialised submodule is an empty directory inside the umbrella).
checkout_sha() {
  local top
  top="$(git -C "$1" rev-parse --show-toplevel 2>/dev/null)" || return 1
  [ "$top" = "$(cd "$1" && pwd -P)" ] || return 1
  git -C "$1" rev-parse HEAD
}

# exclude_docs <mkdocs.yml> -> its exclude_docs block, one pattern per line.
exclude_docs() {
  awk '/^exclude_docs:[ \t]*\|/ { on = 1; next } on && /^[^ \t]/ { on = 0 } on && NF && $1 !~ /^#/ { print $1 }' "$1"
}

# mount_repo <src> <name> <sha> <dest> [doc_links args...]
mount_repo() {
  local src="$1" name="$2" sha="$3" dest="$4"; shift 4
  [ -f "$src/README.md" ] ||
    { echo "prepare-docs: $name has no README.md — it is the repo's landing page (MIP-0074 §5.2)" >&2; return 1; }
  [ ! -e "$src/docs/index.md" ] ||
    { echo "prepare-docs: $name has both README.md and docs/index.md — the README is the landing; remove docs/index.md (MIP-0074 D1)" >&2; return 1; }
  python3 "$script_dir/lib/doc_links.py" --repo "$src" --name "$name" --sha "$sha" --out "$dest" "$@" || return 1
  printf '\n_Built from [marola-dev/%s@%s](https://github.com/marola-dev/%s/tree/%s)._\n' \
    "$name" "${sha:0:7}" "$name" "$sha" >>"$dest/index.md"
}

# check_links <build_dir> <file>... -> every relative link in each page resolves to a real file,
# reported here rather than deep in a `mkdocs --strict` log. The links are doc_links.py's, so code
# spans and fences are read the way the rewrite (and docs-lint) read them. Absolute URLs, mailto:,
# anchors and /… are skipped (doc_links refuses /… already).
check_links() {
  local build_dir="$1"; shift
  local fails=0 f link target listed
  listed="$(python3 "$script_dir/lib/doc_links.py" --links "$@")" || return 1
  while IFS=$'\t' read -r f link; do
    case "$link" in
      ''|*://*|mailto:*|/*|'#'*) continue ;;
    esac
    target="${link%%#*}"
    target="${target%%\?*}"
    [ -n "$target" ] || continue
    if [ ! -e "$(dirname "$f")/$target" ]; then
      echo "prepare-docs: ${f#"$build_dir"/}: broken relative link: $link" >&2
      fails=$((fails + 1))
    fi
  done <<<"$listed"
  [ "$fails" -eq 0 ]
}

# prepare <root> <build_dir>: <root> is the umbrella checkout, with mkdocs/repos.yml and mkdocs.yml.
prepare() {
  local root="$1" build_dir="$2"
  local entries name source src sha umbrella_sha f
  local -a names=() shas=() srcs=() excludes=() submodules=() built=() pages=()
  rm -rf "$build_dir"
  mkdir -p "$build_dir"
  [ -f "$root/mkdocs/mkdocs.yml" ] || { echo "prepare-docs: no $root/mkdocs/mkdocs.yml" >&2; return 1; }
  while IFS= read -r f; do excludes+=(--exclude "$f"); done < <(exclude_docs "$root/mkdocs/mkdocs.yml")
  # Read up front: a parse error inside `< <(...)` would be swallowed, building a site without it.
  entries="$(repos_manifest "$root/mkdocs/repos.yml")" || return 1
  # Explicit `|| return` throughout: prepare runs where its status is tested, so set -e is off in
  # its body (bash(1), "The -e option").
  while IFS=$'\t' read -r name source; do
    [ -n "$name" ] || continue
    if [ "$source" = flake-lock ]; then
      src="$(fetch_flake_lock_source "$root" "$name")" || return 1
    else
      src="$root/$name"
    fi
    sha="$(checkout_sha "$src")" ||
      { echo "prepare-docs: $name is not checked out at $src — run \`git submodule update --init\` first" >&2; return 1; }
    # The devkit is fetched outside the umbrella, so umbrella links can't reach into it.
    [ "$source" = flake-lock ] || submodules+=(--submodule "$name=$sha")
    names+=("$name"); shas+=("$sha"); srcs+=("$src"); built+=("$name" "$sha")
  done <<<"$entries"
  umbrella_sha="$(checkout_sha "$root")" || { echo "prepare-docs: $root is not a git checkout" >&2; return 1; }

  mount_repo "$root" marola "$umbrella_sha" "$build_dir" --umbrella "${excludes[@]}" "${submodules[@]}" || return 1
  local i
  for i in "${!names[@]}"; do
    mount_repo "${srcs[$i]}" "${names[$i]}" "${shas[$i]}" "$build_dir/5-Repos/${names[$i]}" "${excludes[@]}" || return 1
    "$script_dir/fetch-api-docs.sh" "${names[$i]}" "$build_dir/5-Repos/${names[$i]}/api-docs" || return 1
  done
  python3 -c 'import json, sys; a = sys.argv[2:]; json.dump(dict(zip(a[::2], a[1::2])), open(sys.argv[1], "w"), indent=2)' \
    "$build_dir/build.json" marola "$umbrella_sha" "${built[@]}" || return 1

  while IFS= read -r -d '' f; do pages+=("$f"); done < <(find "$build_dir" -name '*.md' -print0)
  check_links "$build_dir" "${pages[@]}"
}

self_test() {
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX GIT_COMMON_DIR
  export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
  # No network: every api-docs fetch goes to an empty local directory unless a case says otherwise.
  local no_remote; no_remote="$(mktemp -d)"
  export API_DOCS_REMOTE="$no_remote/<repo>.git" DOCS_SOURCE_REMOTE="$no_remote/<repo>.git"
  local fails=0 t out rc
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails + 1)); fi; }
  commit_all() { git -C "$1" init -q && git -C "$1" -c advice.addEmbeddedRepo=false add -A 2>/dev/null && git -C "$1" -c user.email=t@t -c user.name=t commit -q -m fixture; }
  # umbrella <dir> <repos.yml body>: a fake umbrella checkout; its submodules are the caller's.
  umbrella() {
    mkdir -p "$1/docs/MIPs" "$1/mkdocs"
    printf '# umbrella\n\nSee [a MIP](docs/MIPs/MIP-0001.md) and [the app](fake-app/README.md).\n' >"$1/README.md"
    printf '# phases, see [MIP-0001](MIPs/MIP-0001.md)\n' >"$1/docs/PHASES.md"
    printf '# MIP-0001, back to [phases](../PHASES.md)\n' >"$1/docs/MIPs/MIP-0001.md"
    printf 'exclude_docs: |\n  benchmarks/\n\ntheme:\n  name: material\n' >"$1/mkdocs/mkdocs.yml"
    printf '%s' "$2" >"$1/mkdocs/repos.yml"
  }
  # app <dir>: a fake submodule with a README landing, a docs page, a code file and an off-site ledger.
  app() {
    mkdir -p "$1/docs/sub" "$1/docs/benchmarks" "$1/core"
    cat >"$1/README.md" <<'EOF'
# fake-app

See [design](docs/1-design.md), [./docs/ prefixed](./docs/sub/notes.md), [code](core/A.scala)
and [a run](docs/benchmarks/run.md).
EOF
    echo "# design, back to [the landing](../README.md)" >"$1/docs/1-design.md"
    echo "# notes" >"$1/docs/sub/notes.md"
    echo "# a kept run" >"$1/docs/benchmarks/run.md"
    echo "object A" >"$1/core/A.scala"
    commit_all "$1"
  }

  echo "-- umbrella_readme_is_root_index: the umbrella's README is the site's index.md, docs/MIPs/ is 6-MIPs/ --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n'
  app "$t/root/fake-app"
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$rc" "0" "a README-landing umbrella and submodule build"
  ok "$(sed -n 3p "$t/build/index.md" 2>/dev/null)" "See [a MIP](6-MIPs/MIP-0001.md) and [the app](5-Repos/fake-app/index.md)." "the README is index.md, its links rewritten for the root"
  ok "$([ -f "$t/build/6-MIPs/MIP-0001.md" ] && echo yes || echo no)" "yes" "docs/MIPs/ lands at 6-MIPs/"
  ok "$([ -e "$t/build/MIPs" ] && echo yes || echo no)" "no" "...and not at MIPs/"
  ok "$(cat "$t/build/PHASES.md" 2>/dev/null)" "# phases, see [MIP-0001](6-MIPs/MIP-0001.md)" "a docs page's link into MIPs/ follows it"
  ok "$(cat "$t/build/6-MIPs/MIP-0001.md" 2>/dev/null)" "# MIP-0001, back to [phases](../PHASES.md)" "a MIP's link out of MIPs/ still resolves"
  ok "$([ -e "$t/root/docs/6-MIPs" ] && echo yes || echo no)" "no" "the tracked docs/ tree is never written to"

  echo
  echo "-- every repo mounts at 5-Repos/<name>/, links rewritten (Appendix A) --"
  local app_sha; app_sha="$(git -C "$t/root/fake-app" rev-parse HEAD)"
  local blob="https://github.com/marola-dev/fake-app/blob/$app_sha"
  ok "$(sed -n 3,4p "$t/build/5-Repos/fake-app/index.md" 2>/dev/null)" "See [design](1-design.md), [./docs/ prefixed](sub/notes.md), [code]($blob/core/A.scala)
and [a run]($blob/docs/benchmarks/run.md)." "README links: docs/ and ./docs/ dropped, code and exclude_docs paths to GitHub at the sha"
  ok "$(cat "$t/build/5-Repos/fake-app/1-design.md" 2>/dev/null)" "# design, back to [the landing](index.md)" "a docs page's ../README.md is the landing"
  ok "$([ -f "$t/build/5-Repos/fake-app/sub/notes.md" ] && echo yes || echo no)" "yes" "docs/sub/ keeps its subdirectory"
  ok "$([ -e "$t/build/5-Repos/fake-app/benchmarks" ] && echo yes || echo no)" "no" "an exclude_docs path is not copied"
  rm -rf "$t"

  echo
  echo "-- build_json_records_shas: build.json and each landing name the commit each repo was built from --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n'
  app "$t/root/fake-app"
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$rc" "0" "the build succeeds"
  app_sha="$(git -C "$t/root/fake-app" rev-parse HEAD)"
  local root_sha; root_sha="$(git -C "$t/root" rev-parse HEAD)"
  ok "$(python3 -c 'import json, sys; d = json.load(open(sys.argv[1])); print(d["marola"], d["fake-app"])' "$t/build/build.json" 2>/dev/null)" "$root_sha $app_sha" "build.json maps each repo to its checkout's HEAD"
  ok "$(tail -1 "$t/build/5-Repos/fake-app/index.md")" "_Built from [marola-dev/fake-app@${app_sha:0:7}](https://github.com/marola-dev/fake-app/tree/$app_sha)._" "the repo landing's last line names its sha"
  ok "$(tail -1 "$t/build/index.md")" "_Built from [marola-dev/marola@${root_sha:0:7}](https://github.com/marola-dev/marola/tree/$root_sha)._" "...and so does the umbrella's"
  rm -rf "$t"

  echo
  echo "-- adr_index_generated: adr/index.md lists each ADR's number, title and status --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n'
  app "$t/root/fake-app"
  mkdir -p "$t/root/fake-app/docs/adr"
  printf '# ADR-0002: Drop the cache | for now\n\n| | |\n|---|---|\n| **Status** | Superseded by [ADR-0003](0003-y.md) |\n' >"$t/root/fake-app/docs/adr/0002-x.md"
  printf '# ADR-0003: Keep one cache\n\n| | |\n|---|---|\n| **Status** | Accepted |\n| **Date** | 2026-10-04 |\n' >"$t/root/fake-app/docs/adr/0003-y.md"
  echo "Decisions: [ADRs](docs/adr/)." >>"$t/root/fake-app/README.md"
  git -C "$t/root/fake-app" add -A
  git -C "$t/root/fake-app" -c user.email=t@t -c user.name=t commit -q -m adrs
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$rc" "0" "a repo with docs/adr/ builds, its README's docs/adr/ link resolving"
  ok "$(cat "$t/build/5-Repos/fake-app/adr/index.md" 2>/dev/null)" "# Architecture decisions

| ADR | Decision | Status |
|---|---|---|
| [0002](0002-x.md) | Drop the cache \| for now | Superseded by [ADR-0003](0003-y.md) |
| [0003](0003-y.md) | Keep one cache | Accepted |" "one row per ADR, in number order, from its H1 and Status row"
  ok "$([ -e "$t/build/adr" ] && echo yes || echo no)" "no" "a repo without docs/adr/ gets no index"
  printf '# ADR-0004 keep\n' >"$t/root/fake-app/docs/adr/0004-z.md"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "an ADR off the template fails the run"
  ok "$(grep -c 'docs/adr/0004-z.md: no "# ADR-0004: <decision>" heading' <<<"$out")" "1" "...naming the file and what is missing"
  rm -rf "$t"

  echo
  echo "-- handwritten_adr_index_fails: adr/index.md is generated, so a committed one is refused --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n'
  app "$t/root/fake-app"
  mkdir -p "$t/root/fake-app/docs/adr"
  printf '# ADR-0001: One\n\n| **Status** | Accepted |\n' >"$t/root/fake-app/docs/adr/0001-one.md"
  echo "# my ADRs" >"$t/root/fake-app/docs/adr/index.md"
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "a hand-written docs/adr/index.md fails the run"
  ok "$(grep -c 'fake-app: docs/adr/index.md: hand-written' <<<"$out")" "1" "...naming the repo and the file"
  mv "$t/root/fake-app/docs/adr/index.md" "$t/root/fake-app/docs/adr/README.md"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "...and so does docs/adr/README.md, which mkdocs would also serve as the index"
  rm -rf "$t"

  echo
  echo "-- readme_and_docs_index_fails: README.md plus docs/index.md is refused (D1) --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n'
  app "$t/root/fake-app"
  echo "# old index" >"$t/root/fake-app/docs/index.md"
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "a submodule with both fails the run"
  ok "$(grep -c 'fake-app has both README.md and docs/index.md' <<<"$out")" "1" "...naming the repo"
  echo "# umbrella's old index" >"$t/root/docs/index.md"
  rm "$t/root/fake-app/docs/index.md"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "the umbrella is held to the same rule"
  ok "$(grep -c 'marola has both README.md and docs/index.md' <<<"$out")" "1" "...naming it"
  rm -rf "$t"

  echo
  echo "-- missing_readme_fails: a repo with no README.md has no landing --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n'
  app "$t/root/fake-app"
  git -C "$t/root/fake-app" rm -q README.md
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "no README.md fails the run"
  ok "$(grep -c 'fake-app has no README.md' <<<"$out")" "1" "...naming the repo"
  rm -rf "$t"

  echo
  echo "-- mount_key_rejected: every repo is at 5-Repos/<name>/, so mount: is an error --"
  t="$(mktemp -d)"
  printf -- '- name: fake-app\n  mount: ./\n' >"$t/manifest.yml"
  rc=0
  out="$(repos_manifest "$t/manifest.yml" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "mount: fails the parser"
  ok "$(grep -c 'mount: is gone' <<<"$out")" "1" "...saying why"
  umbrella "$t/root" $'- name: fake-app\n  mount: ./\n'
  app "$t/root/fake-app"
  commit_all "$t/root"
  rc=0
  prepare "$t/root" "$t/build" >/dev/null 2>&1 || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "...and fails prepare"
  rm -rf "$t"

  echo
  echo "-- a link Appendix A refuses fails the run, naming the file and the link --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n'
  app "$t/root/fake-app"
  echo "# api, see [root-absolute](/api/x.html)" >"$t/root/fake-app/docs/4-reference.md"
  git -C "$t/root/fake-app" add -A
  git -C "$t/root/fake-app" -c user.email=t@t -c user.name=t commit -q -m more
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "a refused link fails the run"
  ok "$(grep -c 'docs/4-reference.md: /api/x.html: root-absolute' <<<"$out")" "1" "...naming the file and the link"
  rm -rf "$t"

  echo
  echo "-- a broken relative link is reported (inline, reference-style and HTML), not inside code --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n'
  app "$t/root/fake-app"
  cat >"$t/root/fake-app/docs/page.md" <<'EOF'
See [gone](missing.md).

[ref]: also-missing.md

<img src="gone.png" alt="x"> and <a href="https://example.com/x">absolute</a>

Code is not a link: `f[A](effect: A)`, nor in a span that wraps: `way["n"](around:R, lat, lon); out
geom;`, and

```text
q='node(around:15000,-27.6,-48.4)[x](in-a-fence.md)'
```

````markdown
```bash
[y](in-a-nested-fence.md)
```
````
EOF
  git -C "$t/root/fake-app" add -A
  git -C "$t/root/fake-app" -c user.email=t@t -c user.name=t commit -q -m more
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "a broken relative link fails the run"
  ok "$(grep -c 'broken relative link: missing.md' <<<"$out")" "1" "...an inline link, naming the file and the link"
  ok "$(grep -c 'broken relative link: also-missing.md' <<<"$out")" "1" "...and a reference-style [label]: target definition too"
  ok "$(grep -c 'broken relative link: gone.png' <<<"$out")" "1" "...and an HTML src= (or href=)"
  ok "$(grep -c 'broken relative link' <<<"$out")" "3" "...but nothing inside a code span (one that wraps a line too) or a fence, nested fences included"
  rm -rf "$t"

  echo
  echo "-- a removed mount does not survive a re-run (ruling T10-8) --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n- name: fake-two\n'
  app "$t/root/fake-app"
  mkdir -p "$t/root/fake-two"
  echo "# two" >"$t/root/fake-two/README.md"
  commit_all "$t/root/fake-two"
  commit_all "$t/root"
  prepare "$t/root" "$t/build" >/dev/null 2>&1 || { echo "  FAIL first prepare"; fails=$((fails + 1)); }
  ok "$([ -f "$t/build/5-Repos/fake-two/index.md" ] && echo yes || echo no)" "yes" "both mounts exist after the first run"
  printf -- '- name: fake-app\n' >"$t/root/mkdocs/repos.yml"
  prepare "$t/root" "$t/build" >/dev/null 2>&1 || { echo "  FAIL second prepare"; fails=$((fails + 1)); }
  ok "$([ -f "$t/build/5-Repos/fake-app/index.md" ] && echo yes || echo no)" "yes" "the still-listed mount survives the re-run"
  ok "$([ -e "$t/build/5-Repos/fake-two" ] && echo yes || echo no)" "no" "the dropped mount does not: the build dir is rebuilt from scratch"
  rm -rf "$t"

  echo
  echo "-- a manifest entry with no checked-out submodule is a hard error --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-ghost\n'
  mkdir -p "$t/root/fake-ghost"
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "a listed-but-absent submodule fails rather than being skipped"
  ok "$(grep -c 'fake-ghost is not checked out' <<<"$out")" "1" "...with a diagnosis, not a silent gap"
  rm -rf "$t"

  echo
  echo "-- the api-docs branch is unpacked under <mount>/api-docs/; a repo without one is a notice --"
  t="$(mktemp -d)"
  umbrella "$t/root" $'- name: fake-app\n'
  app "$t/root/fake-app"
  echo "# api, see [the tree](api-docs/scala/index.html)" >"$t/root/fake-app/docs/4-reference_api.md"
  git -C "$t/root/fake-app" add -A
  git -C "$t/root/fake-app" -c user.email=t@t -c user.name=t commit -q -m more
  commit_all "$t/root"
  rc=0
  out="$(prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "without the branch, a link into api-docs/ is broken"
  ok "$(grep -c 'fake-app has no api-docs branch' <<<"$out")" "1" "...after a notice, not a fetch error"
  git init -q --bare "$t/remote/fake-app.git"
  git init -q "$t/seed"
  git -C "$t/seed" checkout -q --orphan api-docs
  mkdir -p "$t/seed/scala"
  echo hi >"$t/seed/scala/index.html"
  git -C "$t/seed" add -A
  git -C "$t/seed" -c user.email=t@t -c user.name=t commit -q -m "api-docs: $app_sha"
  git -C "$t/seed" push -q "$t/remote/fake-app.git" api-docs
  rc=0
  out="$(API_DOCS_REMOTE="$t/remote/<repo>.git" prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$rc" "0" "with the branch, the build succeeds"
  ok "$([ -f "$t/build/5-Repos/fake-app/api-docs/scala/index.html" ] && echo yes || echo no)" "yes" "...its tree under 5-Repos/<name>/api-docs/"
  rm -rf "$t"

  echo
  echo "-- source_flake_lock_accepted: \`  source: flake-lock\` is the second column --"
  t="$(mktemp -d)"
  printf -- '- name: fake-devkit\n  source: flake-lock\n- name: fake-app\n' >"$t/manifest.yml"
  rc=0
  out="$(repos_manifest "$t/manifest.yml" 2>&1)" || rc=$?
  ok "$rc" "0" "source: flake-lock parses"
  ok "$out" "$(printf 'fake-devkit\tflake-lock\nfake-app\t')" "...as name, source; a submodule's source is empty"
  rm -rf "$t"

  echo
  echo "-- source_other_value_rejected: flake-lock is the only source --"
  t="$(mktemp -d)"
  printf -- '- name: fake-devkit\n  source: submodule\n' >"$t/manifest.yml"
  rc=0
  out="$(repos_manifest "$t/manifest.yml" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "source: submodule fails"
  ok "$(grep -c "source must be flake-lock" <<<"$out")" "1" "...naming the one allowed value"
  umbrella "$t/root" $'- name: fake-devkit\n  source: submodule\n'
  commit_all "$t/root"
  rc=0
  prepare "$t/root" "$t/build" >/dev/null 2>&1 || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "...and fails prepare, not just the parser"
  rm -rf "$t"

  echo
  echo "-- devkit_fetched_at_locked_rev: a flake-lock source mounts flake.lock's rev, not the tip --"
  t="$(mktemp -d)"
  git init -q --bare "$t/remote/fake-devkit.git"
  git init -q "$t/seed"
  mkdir -p "$t/seed/docs"
  echo "# locked, see [ref](docs/ref.md)" >"$t/seed/README.md"
  echo "# ref" >"$t/seed/docs/ref.md"
  git -C "$t/seed" add -A
  git -C "$t/seed" -c user.email=t@t -c user.name=t commit -q -m locked
  local locked; locked="$(git -C "$t/seed" rev-parse HEAD)"
  echo "# tip" >"$t/seed/README.md"
  git -C "$t/seed" -c user.email=t@t -c user.name=t commit -q -am tip
  git -C "$t/seed" push -q "$t/remote/fake-devkit.git" HEAD:refs/heads/main
  umbrella "$t/root" $'- name: fake-devkit\n  source: flake-lock\n'
  sed -i 's/ and \[the app\](fake-app\/README.md)//' "$t/root/README.md"
  printf '{"nodes":{"fake-devkit":{"locked":{"rev":"%s"}}},"root":"root","version":7}\n' "$locked" >"$t/root/flake.lock"
  commit_all "$t/root"
  rc=0
  out="$(DOCS_SOURCE_REMOTE="$t/remote/<repo>.git" prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$rc" "0" "a flake-lock source builds without a checkout at the repo root"
  ok "$(head -1 "$t/build/5-Repos/fake-devkit/index.md" 2>/dev/null)" "# locked, see [ref](ref.md)" "its README is the locked commit's, rewritten like a submodule's"
  ok "$([ -f "$t/build/5-Repos/fake-devkit/ref.md" ] && echo yes || echo no)" "yes" "...and its docs/** is copied beside it"
  ok "$(git -C "$t/root/.tmp/docs-sources/fake-devkit" rev-parse HEAD 2>/dev/null)" "$locked" "the fetch lands in .tmp/docs-sources/<name> at the locked rev"
  ok "$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["fake-devkit"])' "$t/build/build.json" 2>/dev/null)" "$locked" "build.json records the locked rev"
  mkdir -p "$t/bin"
  # shellcheck disable=SC2016 # $a and "$@" belong to the stub git, not to this shell
  printf '#!/bin/sh\nfor a; do [ "$a" = checkout ] && exit 1; done\nexec %s "$@"\n' "$(command -v git)" >"$t/bin/git"
  chmod +x "$t/bin/git"
  rc=0
  out="$(PATH="$t/bin:$PATH" DOCS_SOURCE_REMOTE="$t/remote/<repo>.git" prepare "$t/root" "$t/build" 2>&1)" || rc=$?
  ok "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" "nonzero" "a failing checkout fails the fetch"
  ok "$(grep -c 'could not check out fake-devkit' <<<"$out")" "1" "...naming the checkout"
  rm -rf "$t"

  rm -rf "$no_remote"
  echo
  if [ "$fails" -eq 0 ]; then echo "prepare-docs self-test: ok"; return 0; fi
  echo "prepare-docs self-test: $fails failure(s)" >&2
  return 1
}

root_default="$(cd "$script_dir/.." && pwd)"
case "${1:-}" in
  --self-test) self_test ;;
  -h|--help) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  -*) echo "prepare-docs: unknown argument '$1' (try --help)" >&2; exit 2 ;;
  *) prepare "$root_default" "${1:-$root_default/.tmp/docs-aggregated}" ;;
esac
