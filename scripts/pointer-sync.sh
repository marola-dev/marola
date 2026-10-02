#!/usr/bin/env bash
# pointer-sync — move every submodule's pointer to its default branch's tip and commit the bump,
# for pointer-sync.yml's one rolling PR (MIP-0070 §5.6: pointers move only through that PR).
#
#   scripts/pointer-sync.sh --out DIR   # in the umbrella checkout; writes DIR/moved.tsv (empty when
#                                       # nothing moved), and DIR/pr-body.md + one commit when it did
#   scripts/pointer-sync.sh --self-test # hermetic: fake submodules made with `git init`, no network
set -euo pipefail

trailers() {
  printf 'Tested: pointer bump only; each submodule'"'"'s own CI gates its main\n'
  printf 'Cost: n/a (automation)\n'
  printf 'Co-Authored-By: Claude <noreply@anthropic.com>\n'
}

# compare_url <url> <old> <new> -> a GitHub compare link, or nothing for a non-GitHub remote.
compare_url() {
  local repo
  repo="$(sed -nE 's#^https://github\.com/([^/]+/[^/]+)$#\1#p' <<<"${1%.git}")"
  [ -z "$repo" ] || echo "https://github.com/$repo/compare/$2...$3"
}

bump() {
  local out="$1" name path url old new moved="$1/moved.tsv"
  mkdir -p "$out"
  : >"$moved"
  while read -r key path; do
    name="${key#submodule.}"
    name="${name%.path}"
    url="$(git config -f .gitmodules "submodule.$name.url")"
    old="$(git rev-parse "HEAD:$path")"
    # No submodule.<name>.branch anywhere, so --remote follows each remote's HEAD: its default branch.
    git submodule update --quiet --init --remote -- "$path"
    new="$(git -C "$path" rev-parse HEAD)"
    [ "$old" != "$new" ] || continue
    git add -- "$path"
    printf '%s\t%s\t%s\t%s\n' "$name" "$old" "$new" "$url" >>"$moved"
  done < <(git config -f .gitmodules --get-regexp '^submodule\..*\.path$' || true)

  if [ ! -s "$moved" ]; then
    echo "pointer-sync: every submodule is already at its default branch's tip"
    return 0
  fi
  local names link
  names="$(cut -f1 "$moved" | paste -sd, - | sed 's/,/, /g')"
  {
    echo "chore: move submodule pointers ($names)"
    echo
    echo "Each submodule's default-branch tip, as git submodule update --remote found it:"
    echo
    while IFS=$'\t' read -r name old new url; do echo "- $name: ${old:0:7} -> ${new:0:7}"; done <"$moved"
    echo
    trailers
  } >"$out/commit-msg.txt"
  {
    echo "Moves each submodule to its default branch's tip (MIP-0070 §5.6). \`pointer-sync.yml\` opens this PR and force-updates it on every run; merge it once CI is green."
    echo
    echo "| Submodule | From | To | Changes |"
    echo "|---|---|---|---|"
    while IFS=$'\t' read -r name old new url; do
      link="$(compare_url "$url" "$old" "$new")"
      echo "| \`$name\` | \`${old:0:7}\` | \`${new:0:7}\` | ${link:+[compare]($link)} |"
    done <"$moved"
  } >"$out/pr-body.md"
  git commit --quiet -F "$out/commit-msg.txt"
  echo "pointer-sync: moved $names"
}

self_test() {
  # A pre-push hook exports GIT_DIR (and a worktree, GIT_COMMON_DIR); the fixtures below would
  # otherwise commit into the caller's own repo.
  unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX GIT_COMMON_DIR
  local t fails=0
  t="$(mktemp -d)"
  trap 'rm -rf "$t"' RETURN
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails + 1)); fi; }
  # The fixtures are local paths, which git refuses for submodules by default (CVE-2022-39253).
  # A global config file, not GIT_CONFIG_COUNT: git strips that from a submodule's fetch.
  printf '[protocol "file"]\n\tallow = always\n[user]\n\tname = t\n\temail = t@t\n' >"$t/gitconfig"
  export GIT_CONFIG_GLOBAL="$t/gitconfig" GIT_CONFIG_NOSYSTEM=1
  local r
  for r in app site; do
    git init -q -b trunk "$t/$r"
    git -C "$t/$r" commit -q --allow-empty -m "$r 1"
  done
  git init -q -b main "$t/umbrella"
  (
    cd "$t/umbrella"
    git submodule add -q "$t/app" app
    git submodule add -q "$t/site" site
    git commit -q -m "two submodules"
  )
  local base app1
  base="$(git -C "$t/umbrella" rev-parse HEAD)"
  app1="$(git -C "$t/app" rev-parse HEAD)"

  echo "-- nothing upstream moved: no commit, empty moved.tsv --"
  (cd "$t/umbrella" && bump "$t/out0") >/dev/null
  ok "$(git -C "$t/umbrella" rev-parse HEAD)" "$base" "no commit"
  ok "$(wc -c <"$t/out0/moved.tsv" | tr -d ' ')" "0" "moved.tsv empty"

  echo "-- app's default branch (trunk, not master) moved twice, site not --"
  git -C "$t/app" commit -q --allow-empty -m "app 2"
  git -C "$t/app" commit -q --allow-empty -m "app 3"
  local app3
  app3="$(git -C "$t/app" rev-parse HEAD)"
  (cd "$t/umbrella" && bump "$t/out1") >/dev/null
  ok "$(git -C "$t/umbrella" rev-parse HEAD:app)" "$app3" "app's gitlink is its default branch's tip"
  ok "$(git -C "$t/umbrella" rev-parse HEAD:site)" "$(git -C "$t/site" rev-parse HEAD)" "site's gitlink unchanged"
  ok "$(git -C "$t/umbrella" rev-list --count "$base..HEAD")" "1" "one commit"
  ok "$(cut -f1-3 "$t/out1/moved.tsv")" "$(printf 'app\t%s\t%s' "$app1" "$app3")" "moved.tsv lists only app, old and new"
  ok "$(git -C "$t/umbrella" log -1 --format=%s)" "chore: move submodule pointers (app)" "commit subject names what moved"
  ok "$(git -C "$t/umbrella" log -1 --format='%(trailers:only,unfold)' | sed '/^$/d' | cut -d: -f1 | paste -sd' ' -)" \
    "Tested Cost Co-Authored-By" "the three trailers, nothing else"
  ok "$(grep -c "| \`app\` | \`${app1:0:7}\` | \`${app3:0:7}\` |" "$t/out1/pr-body.md")" "1" "PR body row with old -> new"
  ok "$(git -C "$t/umbrella" status --porcelain)" "" "worktree clean after the commit"

  echo "-- both moved: one commit names both --"
  git -C "$t/app" commit -q --allow-empty -m "app 4"
  git -C "$t/site" commit -q --allow-empty -m "site 2"
  (cd "$t/umbrella" && bump "$t/out2") >/dev/null
  ok "$(git -C "$t/umbrella" log -1 --format=%s)" "chore: move submodule pointers (app, site)" "both named"
  ok "$(wc -l <"$t/out2/moved.tsv" | tr -d ' ')" "2" "two moved rows"

  echo "-- compare links --"
  ok "$(compare_url https://github.com/marola-dev/marola-app.git aaa bbb)" \
    "https://github.com/marola-dev/marola-app/compare/aaa...bbb" "GitHub URL with .git"
  ok "$(compare_url https://github.com/marola-dev/marola-site aaa bbb)" \
    "https://github.com/marola-dev/marola-site/compare/aaa...bbb" "GitHub URL without .git"
  ok "$(compare_url "$t/app" aaa bbb)" "" "a local path gets no link"

  echo "pointer-sync self-test:" "$([ "$fails" -eq 0 ] && echo ok || echo "FAILED ($fails)")"
  [ "$fails" -eq 0 ]
}

case "${1:-}" in
  --self-test) self_test ;;
  --out)
    [ -n "${2:-}" ] || { echo "usage: $0 --out DIR | --self-test" >&2; exit 2; }
    mkdir -p "$2"
    out="$(cd "$2" && pwd)"
    cd "$(git rev-parse --show-toplevel)"
    bump "$out"
    ;;
  *) echo "usage: $0 --out DIR | --self-test" >&2; exit 2 ;;
esac
