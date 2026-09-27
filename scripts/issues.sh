#!/usr/bin/env bash
# issues — the command surface for MIP-0063's GitHub tracking standard. One subcommand family
# per task of the stack; so far `labels sync`, `sub add` and `deps add|list`.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest_default="$root/.github/labels.yml"
nwo=""

usage() {
  cat <<'EOF'
usage: issues.sh [--dry-run] <command> [args]
       issues.sh --self-test | --help

commands:
  labels sync [--prune] [--force] [--manifest FILE]
      Reconcile GitHub's labels against .github/labels.yml: create what is missing, edit what
      differs, report what is on the repo but not in the manifest. Orphans are only deleted
      with --prune, and --prune refuses to delete more than half the repo's labels without
      --force. A manifest that defines no labels is refused outright.

  sub add <parent> <child>
      Make issue <child> a sub-issue of issue <parent>.

  deps add <issue> --blocked-by <n>
      Record that <issue> is blocked by issue <n>.

  deps list <issue>
      What <issue> is blocked by: number, state and title, one per line.

options:
  --dry-run     print the mutating `gh` calls instead of making them (the reads they are
                computed from still happen, so this needs a login)
  --self-test   run the pure-function checks (parser, diff, plan); no `gh`, no network
  --help        this text

Live mode needs `gh` logged in. Inside ai-jail there is no login and none can be acquired
(AGENTS.md): run it from the host, or use --dry-run.
EOF
}

# --- pure functions: no gh, no network, no filesystem beyond the manifest they are handed ---

# manifest_json <file> -> JSON array of {name,color,description}.
# The shape is fixed on purpose: `- name:`, then `color:`, then `description:`. Anything else is
# an error rather than a skipped line, because a typo'd key that parsed as "absent" would make
# `sync` quietly rewrite a label's colour to empty.
manifest_json() {
  local file="$1"
  [ -f "$file" ] || { echo "issues.sh: manifest not found: $file" >&2; return 1; }
  awk -v file="$file" '
    function esc(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); return s }
    function val(line) {
      sub(/\r$/, "", line)
      sub(/^[^:]*:[ \t]*/, "", line)
      sub(/[ \t]+$/, "", line)
      if (line ~ /^".*"$/) line = substr(line, 2, length(line) - 2)
      return line
    }
    function flush(   k) {
      if (name == "") return
      if (color == "" || !have_desc) {
        printf "issues.sh: %s:%d: label \"%s\" is missing color or description\n", file, nr_name, name > "/dev/stderr"
        _abort = 1; exit 1
      }
      # A raw control character would reach jq as invalid JSON and surface as its parse error
      # instead of the file:line diagnostic every other malformed line here gets.
      if (name ~ /[[:cntrl:]]/ || color ~ /[[:cntrl:]]/ || desc ~ /[[:cntrl:]]/) {
        printf "issues.sh: %s:%d: label \"%s\" has a control character in its name, colour or description\n", file, nr_name, name > "/dev/stderr"
        _abort = 1; exit 1
      }
      k = tolower(name)
      if (k in seen) {
        printf "issues.sh: %s:%d: label \"%s\" is already defined on line %d (GitHub label names are case-insensitive)\n", file, nr_name, name, seen[k] > "/dev/stderr"
        _abort = 1; exit 1
      }
      seen[k] = nr_name
      out = out sprintf("%s{\"name\":\"%s\",\"color\":\"%s\",\"description\":\"%s\"}", (n++ ? "," : ""), esc(name), esc(color), esc(desc))
      name = ""; color = ""; desc = ""; have_desc = 0
    }
    /^[ \t]*$/ || /^[ \t]*#/ { next }
    /^- name:/        { flush(); name = val($0); nr_name = NR; next }
    /^  color:/       { if (name == "") { printf "issues.sh: %s:%d: color before any `- name:`\n", file, NR > "/dev/stderr"; _abort = 1; exit 1 } color = val($0); next }
    /^  description:/ { if (name == "") { printf "issues.sh: %s:%d: description before any `- name:`\n", file, NR > "/dev/stderr"; _abort = 1; exit 1 } desc = val($0); have_desc = 1; next }
    { printf "issues.sh: %s:%d: unrecognised line: %s\n", file, NR, $0 > "/dev/stderr"; _abort = 1; exit 1 }
    # Buffered, not streamed: `exit` still runs END, so a parse that fails halfway must leave
    # stdout empty rather than a truncated array that reads as a shorter, valid manifest.
    END { if (!_abort) { flush(); printf "[%s]\n", out } }
  ' "$file"
}

# labels_diff <manifest-json> <repo-json> -> {add,update,orphan}, each an array of label objects.
# Matching is case-insensitive on both name and colour, because GitHub is:
#   - colour: it stores whatever case the label was created with, so 0E8A16 and 0e8a16 are one
#     colour, not a change to apply.
#   - name: `gh label create ZZ-PROBE-CASE` on a repo holding `zz-probe-case` is refused with
#     "already exists" (probed live, 2026-09-27). A case-sensitive match would put one label in
#     `add` *and* `orphan`: sync would abort on the failed create, and --prune would delete the
#     real label out from under every issue carrying it.
# `update` therefore carries `repo_name`, the spelling the label has today. That is the one the
# API can address: the same probe found DELETE case-*sensitive* on the path — deleting
# ZZ-PROBE-CASE 404'd where zz-probe-case worked.
labels_diff() {
  jq -n --argjson m "$1" --argjson r "$2" '
    def norm: {name, key: (.name | ascii_downcase), color: (.color // "" | ascii_downcase), description: (.description // "")};
    ($m | map(norm)) as $M | ($r | map(norm)) as $R |
    ($M | map({key: .key, value: .}) | from_entries) as $MB |
    ($R | map({key: .key, value: .}) | from_entries) as $RB |
    {
      add:    [ $M[] | select($RB[.key] == null) ],
      update: [ $M[] | . as $want | $RB[$want.key] // empty | . as $have
                     | select($have.color != $want.color
                           or $have.description != $want.description
                           or $have.name != $want.name)
                     | $want + {repo_name: $have.name} ],
      orphan: [ $R[] | select($MB[.key] == null) ]
    }'
}

# labels_plan <diff-json> <prune 0|1> -> one TSV action per line: create|edit|delete, the name to
# address the label by, the name it should end up with, colour, description. The two differ only
# when the manifest restyles an existing label's case, which is a rename (`gh label edit --name`)
# rather than a create. Without --prune an orphan yields no line at all, which makes "sync never
# deletes by accident" a property of the plan rather than of the caller remembering.
labels_plan() {
  jq -r --argjson prune "$2" '
    ( .add[]    | ["create", .name,      .name, .color, .description] ),
    ( .update[] | ["edit",   .repo_name, .name, .color, .description] ),
    ( if $prune == 1 then (.orphan[] | ["delete", .name, .name, "", ""]) else empty end )
    | @tsv' <<<"$1"
}

# --- number -> database id ---

# Both `POST /issues/{n}/sub_issues` (`sub_issue_id`) and `POST /issues/{n}/dependencies/blocked_by`
# (`issue_id`) want the **database id**, not the issue number. marola's numbers are three digits
# and this repo's ids are ten, so a number passed here addresses an issue in some unrelated
# repository: refused, or attached to the wrong thing, with nothing in the response to say which.
# MIP-0063 §4.1 — the POST is executed, the mis-attach is documented rather than reproduced.
id_min_digits=9

# guard_id <value> <what> — the refusal itself. issue_id_of applies it to everything it returns,
# which is where both POSTs get their id from.
guard_id() {
  local id="$1" what="$2"
  case "$id" in
    ''|*[!0-9]*) echo "issues.sh: $what: \"$id\" is not a database id" >&2; return 1 ;;
  esac
  [ "${#id}" -ge "$id_min_digits" ] || {
    echo "issues.sh: $what: $id has ${#id} digits — that is an issue number, not a database id. GitHub would accept it and attach a different issue." >&2
    return 1
  }
}

# issue_id_of <issue-json> <number> -> that issue's database id.
issue_id_of() {
  local payload="$1" number="$2" got id
  got="$(jq -r '.number // empty' <<<"$payload" 2>/dev/null)" || got=""
  # A lookup aimed at the wrong repo, or at an issue that was transferred, answers with a payload
  # for a different number — whose id is a real id, so the guard below would happily pass it.
  [ "$got" = "$number" ] || {
    echo "issues.sh: asked for issue #$number, payload is #${got:-<none>} — refusing to use its id" >&2
    return 1
  }
  # `GET /issues/{n}` answers for pull requests as well — they share one number sequence with
  # issues — and a PR's database id is a real id that guard_id would pass. `sub add 414 413` with
  # a PR number would attach the PR, which is the silent-wrong-object case this file exists for.
  if jq -e 'has("pull_request")' <<<"$payload" >/dev/null 2>&1; then
    echo "issues.sh: #$number is a pull request, not an issue — refusing to use its id" >&2
    return 1
  fi
  id="$(jq -r '.id // empty' <<<"$payload" 2>/dev/null)" || id=""
  guard_id "$id" "issue #$number" || return 1
  printf '%s\n' "$id"
}

# arg_number <value> <what> -> a bare issue number; "#415" and "415" both read.
arg_number() {
  local n="${1#\#}"
  case "$n" in
    ''|*[!0-9]*) echo "issues.sh: $2: \"$1\" is not an issue number" >&2; return 1 ;;
  esac
  printf '%s\n' "$n"
}

# --- live side ---

require_gh() {
  command -v gh >/dev/null || { echo "issues.sh: gh is not installed" >&2; exit 1; }
  gh auth status </dev/null >/dev/null 2>&1 || {
    echo "issues.sh: gh is not logged in. Inside ai-jail there is no login and none can be acquired (AGENTS.md) — run this from the host, or use --dry-run." >&2
    exit 1
  }
}

# gh picks its target repo from the current directory, but everything this script acts on comes
# from its own checkout. Run issues.sh from inside another clone and it would reconcile that repo
# against marola's taxonomy — and --prune would delete the difference. Resolve once, from $root;
# `gh api` has no --repo, so for those calls the pin is spelling $nwo into the path.
resolve_nwo() {
  [ -n "$nwo" ] || nwo="$(cd "$root" && gh repo view --json nameWithOwner -q .nameWithOwner </dev/null)"
}

# The one place a number becomes an id. Both POSTs below go through it.
resolve_issue_id() {
  local number="$1" payload err rc=0
  err="$(mktemp)"
  payload="$(gh api "repos/$nwo/issues/$number" </dev/null 2>"$err")" || rc=$?
  if [ "$rc" -ne 0 ]; then
    # A 401, a 403 rate-limit and a DNS failure are not "no such issue". Reporting all of them as
    # one sends an unattended run after the wrong cause, so anything but a 404 quotes gh.
    if grep -q 'HTTP 404' "$err"; then
      echo "issues.sh: issue #$number not found on $nwo" >&2
    else
      { echo "issues.sh: looking up issue #$number on $nwo failed:"; sed 's/^/  /' "$err"; } >&2
    fi
  fi
  rm -f "$err"
  [ "$rc" -eq 0 ] || return 1
  issue_id_of "$payload" "$number"
}

cmd_sub_add() {
  [ $# -eq 2 ] || { echo "issues.sh sub add: expects <parent> <child>" >&2; usage >&2; exit 1; }
  local parent child child_id
  parent="$(arg_number "$1" "sub add")" || exit 1
  child="$(arg_number "$2" "sub add")" || exit 1
  [ "$parent" != "$child" ] || { echo "issues.sh sub add: #$parent cannot be its own sub-issue" >&2; exit 1; }
  require_gh
  resolve_nwo
  child_id="$(resolve_issue_id "$child")" || exit 1
  # -F, not -f: sub_issue_id is an integer in the API schema and -f would send it quoted.
  run gh api --method POST "repos/$nwo/issues/$parent/sub_issues" -F "sub_issue_id=$child_id"
}

cmd_deps_add() {
  [ $# -ge 1 ] || { echo "issues.sh deps add: expects <issue> --blocked-by <n>" >&2; usage >&2; exit 1; }
  local issue blocker="" blocker_id
  issue="$(arg_number "$1" "deps add")" || exit 1
  shift
  while [ $# -gt 0 ]; do
    case "$1" in
      --blocked-by)
        [ $# -ge 2 ] || { echo "issues.sh deps add: --blocked-by needs an issue number" >&2; exit 1; }
        [ -z "$blocker" ] || { echo "issues.sh deps add: --blocked-by given twice (#$blocker, then $2) — one edge per call" >&2; exit 1; }
        blocker="$(arg_number "$2" "deps add --blocked-by")" || exit 1
        shift 2 ;;
      *) echo "issues.sh deps add: unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
  done
  [ -n "$blocker" ] || { echo "issues.sh deps add: --blocked-by <n> is required" >&2; exit 1; }
  [ "$issue" != "$blocker" ] || { echo "issues.sh deps add: #$issue cannot block itself" >&2; exit 1; }
  require_gh
  resolve_nwo
  blocker_id="$(resolve_issue_id "$blocker")" || exit 1
  run gh api --method POST "repos/$nwo/issues/$issue/dependencies/blocked_by" -F "issue_id=$blocker_id"
}

cmd_deps_list() {
  [ $# -eq 1 ] || { echo "issues.sh deps list: expects <issue>" >&2; usage >&2; exit 1; }
  local issue
  issue="$(arg_number "$1" "deps list")" || exit 1
  require_gh
  resolve_nwo
  # --paginate: the default page is 30 and §4.2 allows 50 edges per relationship type, so an
  # unpaginated read would silently drop the rest — and a dropped blocker reads as no blocker.
  # </dev/null on this and every other read: only run()'s mutating calls were protected, and a
  # `gh` that reads stdin eats the caller's — task 5 drives these commands from a read loop.
  gh api --paginate "repos/$nwo/issues/$issue/dependencies/blocked_by" </dev/null \
    --jq '.[] | "#\(.number)\t\(.state)\t\(.title)"'
}

cmd_labels_sync() {
  local prune=0 force=0 manifest="$manifest_default"
  while [ $# -gt 0 ]; do
    case "$1" in
      --prune) prune=1; shift ;;
      --force) force=1; shift ;;
      --manifest)
        [ $# -ge 2 ] || { echo "issues.sh labels sync: --manifest needs a file" >&2; usage >&2; exit 1; }
        manifest="$2"; shift 2 ;;
      *) echo "issues.sh labels sync: unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
  done

  local mjson n_manifest
  mjson="$(manifest_json "$manifest")"
  n_manifest="$(jq 'length' <<<"$mjson")"
  # A manifest that parses to nothing is the shape of an accident — --manifest aimed at the wrong
  # path, or labels.yml truncated by a bad merge. Every label on the repo would read as an orphan,
  # and with --prune that is the whole taxonomy gone in one unattended run.
  if [ "$n_manifest" -eq 0 ]; then
    echo "issues.sh: $manifest defines no labels — refusing to sync. An empty or truncated manifest makes every label on the repo an orphan." >&2
    exit 1
  fi

  require_gh
  resolve_nwo
  local limit=500 rjson n_repo
  rjson="$(gh label list --repo "$nwo" --limit "$limit" --json name,color,description </dev/null)"
  n_repo="$(jq 'length' <<<"$rjson")"
  # Past the limit gh stops silently, and an unseen label reads as "missing from the repo": sync
  # would try to create labels that already exist and, with --prune, never report the real orphans.
  if [ "$n_repo" -ge "$limit" ]; then
    echo "issues.sh: the repo has at least $limit labels, this script's page limit — raise it before trusting the diff." >&2
    exit 1
  fi

  local diff plan orphans n_orphan
  diff="$(labels_diff "$mjson" "$rjson")"
  plan="$(labels_plan "$diff" "$prune")"
  orphans="$(jq -r '.orphan[].name' <<<"$diff")"
  n_orphan="$(jq -r '.orphan | length' <<<"$diff")"

  # Printed whether or not --prune is set: the run that deletes them is the one that most needs
  # to say which labels it means, and --prune --force skips the guard below entirely.
  if [ "$n_orphan" -gt 0 ]; then
    if [ "$prune" -eq 1 ]; then
      echo "orphaned on the repo, not in $(basename "$manifest") — --prune will DELETE these $n_orphan:" >&2
    else
      echo "orphaned on the repo, not in $(basename "$manifest") — re-run with --prune to delete:" >&2
    fi
    sed 's/^/  /' <<<"$orphans" >&2
  fi
  # Pruning most of the repo is the same accident as the empty manifest, one step less obvious: a
  # manifest that parses fine but belongs to some other repo.
  if [ "$prune" -eq 1 ] && [ "$force" -eq 0 ] && [ $((n_orphan * 2)) -gt "$n_repo" ]; then
    echo "issues.sh: --prune would delete $n_orphan of the repo's $n_repo labels — most of the taxonomy. Check $manifest is the right manifest, then re-run with --force." >&2
    exit 1
  fi

  if [ -z "$plan" ]; then
    if [ "$n_orphan" -gt 0 ]; then
      echo "labels: nothing to create or edit ($n_manifest in the manifest); $n_orphan orphaned, listed above"
    else
      echo "labels: in sync ($n_manifest in the manifest)"
    fi
    return 0
  fi

  # Each action stands on its own: `set -e` would abort the loop on the first failure, having
  # already mutated whatever sorted before it, and the output would read like a clean run.
  local action addr name color desc rc=0 applied=0 failed=0
  while IFS=$'\t' read -r action addr name color desc; do
    rc=0
    case "$action" in
      create) run gh label create --repo "$nwo" "$addr" --color "$color" --description "$desc" || rc=$? ;;
      edit)
        if [ "$addr" != "$name" ]; then
          run gh label edit --repo "$nwo" "$addr" --name "$name" --color "$color" --description "$desc" || rc=$?
        else
          run gh label edit --repo "$nwo" "$addr" --color "$color" --description "$desc" || rc=$?
        fi ;;
      delete) run gh label delete --repo "$nwo" "$addr" --yes || rc=$? ;;
    esac
    if [ "$rc" -eq 0 ]; then applied=$((applied + 1)); else failed=$((failed + 1)); fi
  done <<<"$plan"

  if [ "$failed" -gt 0 ]; then
    echo "issues.sh: $failed of $((applied + failed)) label actions failed, $applied applied — the repo is part-way through the plan. Fix the cause and re-run; sync is idempotent." >&2
    exit 1
  fi
}

dry=0
# %q, not "$*": a label description always contains spaces, so an unquoted echo prints a line
# that looks copy-pasteable and isn't.
run() {
  if [ "$dry" -eq 1 ]; then printf '+'; printf ' %q' "$@"; printf '\n'; return 0; fi
  # rc is captured rather than relied on implicitly: the echo below is the function's last
  # command, so without this `run` would always return 0 and the caller's failure tally would
  # never see a failed `gh` call.
  local rc=0
  # </dev/null, not just >/dev/null: the apply loop feeds the plan in on stdin as a here-string,
  # which every child inherits. One `gh` that reads stdin swallows the rest of the plan and the
  # loop ends early having applied a fraction of it, with failed=0 and exit 0. Verified with a
  # stdin-draining stub: 1 of 39 edits ran and the script reported success.
  "$@" </dev/null >/dev/null || rc=$?
  if [ "$rc" -eq 0 ]; then { printf ' %q' "$@"; printf '\n'; }
  else { printf 'FAILED (exit %d):' "$rc"; printf ' %q' "$@"; printf '\n'; } >&2; fi
  return "$rc"
}

# --- self-test ---

self_test() {
  local failed=0 tmp got want
  tmp="$(mktemp -d)"
  # EXIT, not RETURN: bash fires a RETURN trap when a *sourced file* finishes as well as when a
  # function does, so `source scripts/lib/pr_labels.sh` below deleted $tmp half way through the
  # run. The path is expanded into the trap body now, because this local is out of scope by the
  # time EXIT fires.
  # shellcheck disable=SC2064 # expanding now is the point: $tmp is local and gone by EXIT.
  trap "rm -rf $(printf %q "$tmp")" EXIT

  check() {   # check <label> <got> <want>
    if [ "$2" = "$3" ]; then echo "ok: $1"; else echo "FAILED: $1" >&2; echo "  got:  $2" >&2; echo "  want: $3" >&2; failed=1; fi
  }

  echo "-- manifest parser --"
  cat > "$tmp/m.yml" <<'EOF'
# a comment, and a blank line, both ignored

- name: "area/conditions"
  color: "1d76db"
  description: "Live sea/weather/tide data"

- name: "size/S"
  color: "C5DEF5"
  description: "Under ~100 changed lines"
EOF
  got="$(manifest_json "$tmp/m.yml")"
  check "two labels parsed" "$(jq -r 'length' <<<"$got")" "2"
  check "quotes stripped from name" "$(jq -r '.[0].name' <<<"$got")" "area/conditions"
  check "description read whole" "$(jq -r '.[0].description' <<<"$got")" "Live sea/weather/tide data"

  printf -- '- name: "x"\n  colour: "abc123"\n  description: "d"\n' > "$tmp/typo.yml"
  if manifest_json "$tmp/typo.yml" >/dev/null 2>&1; then
    echo "FAILED: a typo'd key (colour) parsed instead of erroring" >&2; failed=1
  else
    echo "ok: a typo'd key is an error, not a silently absent field"
  fi

  printf -- '- name: "x"\n  color: "abc123"\n' > "$tmp/short.yml"
  if manifest_json "$tmp/short.yml" >/dev/null 2>&1; then
    echo "FAILED: a label with no description parsed" >&2; failed=1
  else
    echo "ok: a label missing its description is an error"
  fi

  # awk runs END even after `exit`, so a failed parse must emit nothing at all — half an array
  # would otherwise reach jq and read as a shorter, valid manifest.
  check "a failed parse writes no partial JSON" "$(manifest_json "$tmp/typo.yml" 2>/dev/null || true)" ""

  echo
  echo "-- diff --"
  local repo manifest diff
  repo='[{"name":"area/conditions","color":"1d76db","description":"Live sea/weather/tide data"},
         {"name":"layer/azure","color":"f9d0c4","description":"azure/ — opt-in Azure integrations"}]'
  manifest="$(manifest_json "$tmp/m.yml")"
  diff="$(labels_diff "$manifest" "$repo")"
  check "one add (size/S)" "$(jq -r '.add | map(.name) | join(",")' <<<"$diff")" "size/S"
  check "one orphan (layer/azure)" "$(jq -r '.orphan | map(.name) | join(",")' <<<"$diff")" "layer/azure"
  check "nothing to update" "$(jq -r '.update | length' <<<"$diff")" "0"

  diff="$(labels_diff "$repo" "$repo")"
  check "idempotence: a manifest matching the repo is an empty diff" \
    "$(jq -r '[.add, .update, .orphan] | map(length) | join(",")' <<<"$diff")" "0,0,0"

  # The trap that makes the live run noisy for nothing: `size/S` is C5DEF5 on the repo and
  # c5def5 in the manifest. Same label, same colour.
  diff="$(labels_diff \
    '[{"name":"size/S","color":"c5def5","description":"d"}]' \
    '[{"name":"size/S","color":"C5DEF5","description":"d"}]')"
  check "colour case alone is not a change" "$(jq -r '.update | length' <<<"$diff")" "0"

  diff="$(labels_diff \
    '[{"name":"size/S","color":"c5def5","description":"new text"}]' \
    '[{"name":"size/S","color":"c5def5","description":"old text"}]')"
  check "a changed description is an update" "$(jq -r '.update | map(.name) | join(",")' <<<"$diff")" "size/S"

  echo
  echo "-- plan --"
  diff="$(labels_diff "$manifest" "$repo")"
  got="$(labels_plan "$diff" 0)"
  if grep -q '^delete' <<<"$got"; then
    echo "FAILED: sync without --prune emitted a delete" >&2; failed=1
  else
    echo "ok: without --prune the plan contains no delete"
  fi
  check "without --prune the orphan yields no line at all" "$(grep -c 'layer/azure' <<<"$got" || true)" "0"
  check "the add is planned as a create" "$(cut -f1,2 <<<"$got" | tr '\t' ' ')" "create size/S"
  check "a create addresses and names the same label" "$(cut -f2,3 <<<"$got" | tr '\t' ' ')" "size/S size/S"
  got="$(labels_plan "$diff" 1)"
  check "with --prune the orphan is a delete" "$(grep '^delete' <<<"$got" | cut -f2)" "layer/azure"

  echo
  echo "-- GitHub treats label names case-insensitively (probed live 2026-09-27) --"
  # `gh label create ZZ-PROBE-CASE` on a repo holding `zz-probe-case` is refused as already
  # existing, so a case-sensitive match would plan a create that fails and a delete that
  # destroys the real label.
  diff="$(labels_diff \
    '[{"name":"bug","color":"d73a4a","description":"d"}]' \
    '[{"name":"Bug","color":"d73a4a","description":"d"}]')"
  check "a name differing only in case is not an add" "$(jq -r '.add | length' <<<"$diff")" "0"
  check "a name differing only in case is not an orphan" "$(jq -r '.orphan | length' <<<"$diff")" "0"
  check "it is an update that renames" "$(jq -r '.update | map("\(.repo_name)->\(.name)") | join(",")' <<<"$diff")" "Bug->bug"
  # DELETE and PATCH are case-*sensitive* on the path (ZZ-PROBE-CASE 404'd, zz-probe-case did
  # not), so the edit has to address the spelling the repo actually has.
  check "the edit addresses the repo's spelling, not the manifest's" \
    "$(labels_plan "$diff" 0 | cut -f2,3 | tr '\t' ' ')" "Bug bug"

  diff="$(labels_diff \
    '[{"name":"bug","color":"d73a4a","description":"d"}]' \
    '[{"name":"bug","color":"D73A4A","description":"d"}]')"
  check "same name, same colour, different case is no change at all" \
    "$(jq -r '[.add, .update, .orphan] | map(length) | join(",")' <<<"$diff")" "0,0,0"

  echo
  echo "-- the manifest parser rejects a duplicate name --"
  printf -- '- name: "dup"\n  color: "aaaaaa"\n  description: "first"\n\n- name: "DUP"\n  color: "bbbbbb"\n  description: "second"\n' > "$tmp/dup.yml"
  if manifest_json "$tmp/dup.yml" >/dev/null 2>&1; then
    echo "FAILED: two stanzas for the same name (differing in case) parsed" >&2; failed=1
  else
    echo "ok: a duplicate name is an error, case-insensitively"
  fi

  echo
  echo "-- the repo's own manifest --"
  got="$(manifest_json "$manifest_default")"
  check "$(basename "$manifest_default") parses" "$(jq -r 'length > 0' <<<"$got")" "true"
  check "layer/azure is not in the manifest" "$(jq -r '[.[] | select(.name == "layer/azure")] | length' <<<"$got")" "0"
  check "the four new labels are" \
    "$(jq -r '[.[] | select(.name | IN("agent-ready","size/S","size/M","size/L"))] | length' <<<"$got")" "4"
  check "every colour is a bare 6-digit hex" \
    "$(jq -r '[.[] | select(.color | test("^[0-9a-f]{6}$") | not)] | length' <<<"$got")" "0"
  check "no duplicate names" \
    "$(jq -r '(map(.name) | length) == (map(.name) | unique | length)' <<<"$got")" "true"

  echo
  echo "-- run() reports a failure to its caller --"
  # The apply loop counts failures from run()'s status. An earlier version ended with the echo,
  # so every action looked applied and a part-way sync printed a clean run.
  dry=0
  if run true >/dev/null; then echo "ok: a successful action returns 0"; else echo "FAILED: run true returned nonzero" >&2; failed=1; fi
  if run false 2>/dev/null; then echo "FAILED: run false returned 0 — failures would go uncounted" >&2; failed=1; else echo "ok: a failing action returns nonzero"; fi
  dry=1
  if run false >/dev/null; then echo "ok: --dry-run never executes, so it cannot fail"; else echo "FAILED: dry-run executed the command" >&2; failed=1; fi
  dry=0

  echo
  echo "-- the apply loop feeds the plan on stdin; run() must not pass it on --"
  # A `gh` that reads stdin would otherwise eat the rest of the plan: verified with a draining
  # stub, 1 of 39 edits ran and the script exited 0 reporting success.
  dry=0
  got="$(printf 'plan-line-2\nplan-line-3\n' | { run cat >/dev/null; cat; })"
  check "run leaves the caller's stdin untouched" "$got" "plan-line-2
plan-line-3"

  echo
  echo "-- the read calls must not eat the caller's stdin either --"
  # run() protects the mutating calls; the reads (auth status, repo view, GET) are called
  # directly. Task 5 drives `sub add`/`deps add` from a loop over the `depends on` column, so
  # the caller's stdin is the loop's input — one gh child that reads it ends the loop early.
  mkdir -p "$tmp/bin"
  cat > "$tmp/bin/gh" <<'STUB'
#!/usr/bin/env bash
cat >/dev/null          # a gh that reads stdin; `gh api --input -` really does
case "$*" in
  *"repo view"*)              echo "marola-dev/marola" ;;
  *"dependencies/blocked_by"*) printf '#414\tclosed\tstub\n' ;;
  *"issues/415")              printf '{"number":415,"id":5601728372}\n' ;;
esac
STUB
  chmod +x "$tmp/bin/gh"
  got="$(printf 'row-2\nrow-3\n' | { PATH="$tmp/bin:$PATH" nwo="" cmd_deps_list 415 >/dev/null; cat; })"
  check "deps list leaves the caller's stdin untouched (auth status, repo view and the GET)" "$got" "row-2
row-3"
  got="$(printf 'row-2\nrow-3\n' | { PATH="$tmp/bin:$PATH" nwo="marola-dev/marola" resolve_issue_id 415 >/dev/null; cat; })"
  check "resolve_issue_id leaves the caller's stdin untouched" "$got" "row-2
row-3"
  if ( cmd_deps_add 428 --blocked-by 427 --blocked-by 426 ) >/dev/null 2>&1; then
    echo "FAILED: a repeated --blocked-by was accepted; the last one silently won" >&2; failed=1
  else
    echo "ok: --blocked-by given twice is refused, not quietly overwritten"
  fi

  echo
  echo "-- the parser survives a CRLF manifest and names a control character --"
  printf -- '- name: "x"\r\n  color: "aaaaaa"\r\n  description: "d"\r\n' > "$tmp/crlf.yml"
  got="$(manifest_json "$tmp/crlf.yml" 2>/dev/null || true)"
  check "a CRLF manifest parses, quotes and all" "$(jq -r '.[0] | "\(.name)/\(.color)/\(.description)"' <<<"$got" 2>/dev/null)" "x/aaaaaa/d"
  printf -- '- name: "x"\n  color: "aaaaaa"\n  description: "has\ta tab"\n' > "$tmp/tab.yml"
  got="$(manifest_json "$tmp/tab.yml" 2>&1 >/dev/null || true)"
  case "$got" in
    *"control character"*) echo "ok: a control character is this script's own diagnostic, not a jq crash" ;;
    *) echo "FAILED: expected a control-character diagnostic, got: $got" >&2; failed=1 ;;
  esac

  echo
  echo "-- scripts/lib/pr_labels.sh agrees with the manifest --"
  # `just pr-label` creates its taxonomy with `gh label create --force` (ensure_pr_labels), so a
  # second copy of these colours exists. If the two disagree, pr-label and labels-sync overwrite
  # each other on every run and neither file is the source of truth any more.
  # shellcheck source=scripts/lib/pr_labels.sh
  source "$root/scripts/lib/pr_labels.sh"
  # Parsed again here rather than reusing $got from an earlier section: a shared scratch variable
  # across sections meant inserting a test in between silently broke this one.
  local mj entry n c d want_c want_d
  mj="$(manifest_json "$manifest_default")"
  for entry in "${PR_LABEL_TAXONOMY[@]}"; do
    n="${entry%%:*}"; c="${entry#*:}"; c="$(tr 'A-Z' 'a-z' <<<"${c%%:*}")"; d="${entry#*:*:}"
    want_c="$(jq -r --arg n "$n" '.[] | select(.name == $n) | .color' <<<"$mj")"
    want_d="$(jq -r --arg n "$n" '.[] | select(.name == $n) | .description' <<<"$mj")"
    if [ -z "$want_c" ]; then
      echo "FAILED: $n is in PR_LABEL_TAXONOMY but not in $(basename "$manifest_default")" >&2; failed=1
    elif [ "$c" != "$want_c" ] || [ "$d" != "$want_d" ]; then
      echo "FAILED: $n differs between PR_LABEL_TAXONOMY and the manifest" >&2
      echo "  pr_labels.sh: $c / $d" >&2
      echo "  labels.yml:   $want_c / $want_d" >&2
      failed=1
    fi
  done
  [ "$failed" -eq 1 ] || echo "ok: all ${#PR_LABEL_TAXONOMY[@]} PR_LABEL_TAXONOMY entries match the manifest"

  echo
  echo "-- the scratch dir survives the source above --"
  # Keep these two after the only `source` in this function. A RETURN trap fires when a sourced
  # file finishes, so cleaning up on RETURN silently removed $tmp here and the next section
  # appended below failed for a reason that looked nothing like its cause.
  check "no RETURN trap is installed; cleanup is on EXIT" "$(trap -p RETURN)" ""
  if [ -d "$tmp" ]; then
    echo "ok: the scratch dir is still there after sourcing a file"
  else
    echo "FAILED: the scratch dir was removed mid-run — something re-armed a RETURN trap" >&2; failed=1
  fi

  echo
  echo "-- number -> database id --"
  local payload='{"number":415,"id":3456789012,"title":"id-resolve-and-probe"}'
  check "the id comes from the payload, not the number" "$(issue_id_of "$payload" 415)" "3456789012"
  if issue_id_of "$payload" 414 >/dev/null 2>&1; then
    echo "FAILED: a payload for a different issue was accepted; its id would attach the wrong issue" >&2; failed=1
  else
    echo "ok: a payload whose number is not the one asked for is refused"
  fi
  if issue_id_of '{"number":415}' 415 >/dev/null 2>&1; then
    echo "FAILED: a payload carrying no id resolved to something" >&2; failed=1
  else
    echo "ok: a payload with no id is an error"
  fi
  if issue_id_of 'not json' 415 >/dev/null 2>&1; then
    echo "FAILED: an unparseable payload resolved to something" >&2; failed=1
  else
    echo "ok: an unparseable payload is an error"
  fi
  if issue_id_of '{"number":423,"id":5605746769,"pull_request":{"html_url":"..."}}' 423 >/dev/null 2>&1; then
    echo "FAILED: a pull request resolved to an id — GET /issues/{n} answers for PRs too, and they share the issue numbering" >&2; failed=1
  else
    echo "ok: a pull request is refused where an issue is required"
  fi
  check "an issue number reads with or without its #" "$(arg_number '#415' t)/$(arg_number 415 t)" "415/415"
  if arg_number "4a5" t >/dev/null 2>&1; then
    echo "FAILED: a non-numeric issue number was accepted" >&2; failed=1
  else
    echo "ok: a non-numeric issue number is an error"
  fi

  echo
  echo "-- the id guard: the regression test for the trap itself --"
  # An issue number where the API wants a database id addresses an unrelated repository's issue:
  # refused, or attached to the wrong thing, and nothing downstream can tell which. The guard is
  # the only thing standing there, so the guard is what gets tested.
  if guard_id 415 "issue #415" 2>/dev/null; then
    echo "FAILED: a 3-digit issue number passed the database-id guard" >&2; failed=1
  else
    echo "ok: a 3-digit value is refused where a database id is required"
  fi
  if guard_id 123456789 "issue #415" 2>/dev/null; then
    echo "ok: a 9-digit database id passes"
  else
    echo "FAILED: a 9-digit database id was refused" >&2; failed=1
  fi
  local bad bad_ok=1
  for bad in "" "12345678" "3456789012x" "#3456789012"; do
    if guard_id "$bad" "boundary" 2>/dev/null; then
      echo "FAILED: the guard accepted \"$bad\" as a database id" >&2; failed=1; bad_ok=0
    fi
  done
  if [ "$bad_ok" -eq 1 ]; then echo "ok: empty, 8-digit, trailing-garbage and #-prefixed values are refused too"; fi
  echo "-- issue forms parse, and their field labels are the exact headings §5.4 greps for --"
  # No pyyaml on this host (AGENTS.md) and the repo avoids adding one. This hand-parses the
  # indentation-based subset GitHub issue forms use — mappings, block/flow sequences, quoted and
  # literal-block scalars — and errors on anything left over rather than silently truncating, the
  # same trade manifest_json makes for labels.yml's fixed shape.
  # Own scratch file, not $tmp: bash's RETURN trap fires when `source` finishes too, not only
  # when self_test() itself returns, so the `source scripts/lib/pr_labels.sh` above already ran
  # the `rm -rf "$tmp"` trap early — $tmp is gone by the time a section placed after it looks.
  local form_dir="$root/.github/ISSUE_TEMPLATE" form_parser
  form_parser="$(mktemp)"
  cat > "$form_parser" <<'PYEOF'
import json, re, sys

def parse_yaml_subset(text):
    entries = []
    for raw in text.replace("\r\n", "\n").split("\n"):
        stripped = raw.strip()
        if stripped == "" or stripped.startswith("#"):
            continue
        entries.append([len(raw) - len(raw.lstrip(" ")), stripped])
    pos = 0

    def split_kv(s):
        m = re.match(r'^([A-Za-z0-9_.-]+):\s*(.*)$', s)
        if not m:
            raise ValueError(f"cannot parse line: {s!r}")
        return m.group(1), m.group(2).strip()

    def scalar(v):
        v = v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
            return v[1:-1]
        if v.startswith("[") and v.endswith("]"):
            return [scalar(x) for x in v[1:-1].split(",") if x.strip() != ""]
        return v

    def parse_block(min_indent):
        nonlocal pos
        if pos >= len(entries) or entries[pos][0] < min_indent:
            return {}
        return parse_sequence(entries[pos][0]) if entries[pos][1].startswith("- ") else parse_mapping(entries[pos][0])

    def parse_mapping(indent):
        nonlocal pos
        result = {}
        while pos < len(entries):
            ind, content = entries[pos]
            if ind != indent or content.startswith("- "):
                break
            key, val = split_kv(content)
            pos += 1
            if val == "":
                result[key] = parse_block(indent + 2)
            elif val in ("|", ">", "|-", ">-"):
                while pos < len(entries) and entries[pos][0] > indent:
                    pos += 1
                result[key] = None
            else:
                result[key] = scalar(val)
        return result

    def parse_sequence(indent):
        nonlocal pos
        result = []
        while pos < len(entries):
            ind, content = entries[pos]
            if ind != indent or not content.startswith("- "):
                break
            rest = content[2:]
            if rest == "":
                pos += 1
                result.append(parse_block(indent + 2))
            elif re.match(r'^[A-Za-z0-9_.-]+:(\s|$)', rest):
                entries[pos] = [indent + 2, rest]
                result.append(parse_mapping(indent + 2))
            else:
                pos += 1
                result.append(scalar(rest))
        return result

    doc = parse_mapping(0)
    if pos != len(entries):
        raise ValueError(f"unparsed content at indent {entries[pos][0]}: {entries[pos][1]!r}")
    return doc

if __name__ == "__main__":
    with open(sys.argv[1], encoding="utf-8") as f:
        print(json.dumps(parse_yaml_subset(f.read())))
PYEOF

  local form_doc form_labels form_rc
  for f in bug_report.yml task.yml story.yml mip_proposal.yml config.yml; do
    if form_doc="$(python3 "$form_parser" "$form_dir/$f" 2>&1)"; then form_rc=ok; else form_rc=fail; fi
    check "$f parses" "$form_rc" "ok"
  done

  form_doc="$(python3 "$form_parser" "$form_dir/bug_report.yml" 2>/dev/null || echo '{}')"
  form_labels="$(jq -r '[(.body // [])[].attributes.label] | map(select(. != null)) | join("|")' <<<"$form_doc")"
  check "bug_report.yml field labels" "$form_labels" \
    "What happened|What you expected instead|How to reproduce|Backend|Failing test|Relevant logs or output"

  form_doc="$(python3 "$form_parser" "$form_dir/task.yml" 2>/dev/null || echo '{}')"
  form_labels="$(jq -r '[(.body // [])[].attributes.label] | map(select(. != null)) | join("|")' <<<"$form_doc")"
  check "task.yml field labels" "$form_labels" "What|Acceptance criteria|Named test|Size"
  check "task.yml's acceptance-criteria field renders as the DoR rule 1 heading" \
    "### $(jq -r '(.body // [])[] | select(.id == "acceptance-criteria") | .attributes.label' <<<"$form_doc")" \
    "### Acceptance criteria"
  check "task.yml's named-test field renders as the DoR rule 2 heading" \
    "### $(jq -r '(.body // [])[] | select(.id == "named-test") | .attributes.label' <<<"$form_doc")" \
    "### Named test"

  form_doc="$(python3 "$form_parser" "$form_dir/story.yml" 2>/dev/null || echo '{}')"
  form_labels="$(jq -r '[(.body // [])[].attributes.label] | map(select(. != null)) | join("|")' <<<"$form_doc")"
  check "story.yml field labels" "$form_labels" \
    "Problem|Proposed behaviour|Acceptance criteria|Named test|Out of scope|Deliverable"
  check "story.yml's acceptance-criteria field renders as the DoR rule 1 heading" \
    "### $(jq -r '(.body // [])[] | select(.id == "acceptance-criteria") | .attributes.label' <<<"$form_doc")" \
    "### Acceptance criteria"
  check "story.yml's named-test field renders as the DoR rule 2 heading" \
    "### $(jq -r '(.body // [])[] | select(.id == "named-test") | .attributes.label' <<<"$form_doc")" \
    "### Named test"

  form_doc="$(python3 "$form_parser" "$form_dir/mip_proposal.yml" 2>/dev/null || echo '{}')"
  form_labels="$(jq -r '[(.body // [])[].attributes.label] | map(select(. != null)) | join("|")' <<<"$form_doc")"
  check "mip_proposal.yml field labels are unchanged" "$form_labels" "Title|Motivation|Sketch|Effort / gain guess"
  if grep -q "MIP PR" "$form_dir/mip_proposal.yml" && grep -q "milestone" "$form_dir/mip_proposal.yml" \
      && grep -q "tasks-to-issues" "$form_dir/mip_proposal.yml"; then
    echo "ok: mip_proposal.yml states what happens next (MIP PR, milestone, tasks-to-issues)"
  else
    echo "FAILED: mip_proposal.yml is missing the MIP PR / milestone / tasks-to-issues next-steps text" >&2
    failed=1
  fi

  form_doc="$(python3 "$form_parser" "$form_dir/config.yml" 2>/dev/null || echo '{}')"
  check "config.yml still disables blank issues" "$(jq -r '.blank_issues_enabled' <<<"$form_doc")" "false"
  check "config.yml points questions at Discussions" \
    "$(jq -r '.contact_links[0].url' <<<"$form_doc")" "https://github.com/marola-dev/marola/discussions"

  if [ -e "$form_dir/feature_request.yml" ]; then
    echo "FAILED: feature_request.yml still exists — story.yml was meant to replace it" >&2; failed=1
  else
    echo "ok: feature_request.yml is gone, replaced by story.yml"
  fi
  rm -f "$form_parser"

  echo
  if [ "$failed" -eq 1 ]; then echo "issues.sh self-test: FAILED" >&2; return 1; fi
  echo "issues.sh self-test: ok"
}

# --- arg parsing ---

args=()
for a in "$@"; do
  case "$a" in
    --dry-run) dry=1 ;;
    --self-test) self_test; exit 0 ;;
    --help|-h) usage; exit 0 ;;
    *) args+=("$a") ;;
  esac
done
set -- ${args[@]+"${args[@]}"}

case "${1:-}" in
  labels)
    case "${2:-}" in
      sync) shift 2; cmd_labels_sync "$@" ;;
      *) echo "issues.sh labels: unknown subcommand: ${2:-<none>}" >&2; usage >&2; exit 1 ;;
    esac
    ;;
  sub)
    case "${2:-}" in
      add) shift 2; cmd_sub_add "$@" ;;
      *) echo "issues.sh sub: unknown subcommand: ${2:-<none>}" >&2; usage >&2; exit 1 ;;
    esac
    ;;
  deps)
    case "${2:-}" in
      add) shift 2; cmd_deps_add "$@" ;;
      list) shift 2; cmd_deps_list "$@" ;;
      *) echo "issues.sh deps: unknown subcommand: ${2:-<none>}" >&2; usage >&2; exit 1 ;;
    esac
    ;;
  ""|*) usage >&2; exit 1 ;;
esac
