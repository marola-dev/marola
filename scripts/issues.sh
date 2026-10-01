#!/usr/bin/env bash
# issues — the command surface for MIP-0063's GitHub tracking standard. One subcommand family
# per task of the stack; so far `labels sync`, `sub add`, `deps add|list`, `ready`/`queue`,
# `tasks-to-issues`, `claim`, `milestone new` and `board setup|sync|gates`.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest_default="$root/.github/labels.yml"
nwo=""

# shellcheck source=scripts/lib/mip_ref.sh
source "$root/scripts/lib/mip_ref.sh"

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

  ready <issue>
      Run the five-rule Definition of Ready (MIP-0063 §5.4), naming the rule that failed, then
      add `agent-ready` on an all-pass and remove it when a previously-ready issue regressed.
      A `mip` proposal is not claimable work and is refused outright; a `bug` reads rules 1 and 2
      from bug_report.yml's own two fields.

  queue [--milestone NAME]
      The unassigned open issues across the whole org (MIP-0070 §5.7: `gh search issues --owner`,
      the closest to `org:marola-dev is:issue is:open`), agent-ready ones printed as `owner/repo#N`
      when they are not in this repo, sorted size then priority, with a count of what is ready,
      blocked and still in triage. Those counts are derived from the labels and the dependency
      edges, not read from the board's Status field, which needs `project` scope.

  tasks-to-issues <MIP-NNNN|path> [--milestone NAME] [--deliverable NAME]
      File a MIP's task table as issues (MIP-0070 §5.7): a parent issue in the umbrella titled
      `MIP-NNNN: <title>`, and one sub-issue per row, filed in the repo its `delivers` cell names
      (`**<repo>**`, optionally ` (new)`) — or the umbrella when that repo does not exist yet
      (`gh api repos/<owner>/<name>`, read-only) or has none. Each row's `#` cell is rewritten into a link, and
      one native `blocked by` edge is wired per entry of the `depends on` column, same-repo or
      cross (a cross-repo attempt is reported as skipped, never as a failure, since GitHub's docs
      do not confirm it either way). Every issue — parent and rows — goes onto Project 1;
      `--deliverable NAME` also sets its `Deliverable` field, the cross-repo stand-in for a
      milestone (§5.7: milestones are per repo). `--milestone` still works for issues filed in the
      umbrella and must already exist there. Idempotent — a second run creates, rewrites, links
      and wires nothing that already exists, and a row already filed in the umbrella is never
      "moved" once its own repo appears. `area/*`, `layer/*` and `size/*` are a human's call and
      are not set here, so a filed row is not `agent-ready` until someone labels it.

  claim <issue>
      Take an `agent-ready` issue: re-run the Definition of Ready, assign it to you, drop the
      label, set the board's Status to In progress, and print the `scripts/stack.sh start` line.
      <issue> is a bare number (this repo) or `owner/repo#N` (anywhere else on the org, MIP-0070
      §5.7). Refuses an issue that is closed, assigned to someone else, or no longer ready.

  milestone new "<name>" [--mip MIP-NNNN]
      Create a deliverable milestone. Re-running with an existing name changes nothing.

  board sync
      Add every open issue to the project board, then set its Status from the issue's own state
      (assigned, `agent-ready`, or neither) on the items carrying no Status and on those still
      carrying `Backlog`, which is what the auto-add workflow writes rather than a state anyone
      chose. Any other Status is someone's decision and is never overwritten — except Done: a
      closed issue's card is moved to Done whenever it isn't already, the fallback for the
      project's built-in "Item closed" workflow (MIP-0063 §4.4). Run `board setup` first: an
      issue whose state calls for a Status option the field lacks is skipped, not set.

  board setup
      Bring the project itself up to MIP-0063 §5.2: the Status options it is missing and the four
      views, plus a `Deliverable` text field (MIP-0070 §5.7) if the board has none. Idempotent,
      and it never removes or rewrites an option, view or field that is already there. Needs
      `project` scope.

  board gates
      File the five phase gate issues of MIP-0063 §5.3, named from docs/PHASES.md and
      labelled `phase/*`. Idempotent; a phase PHASES.md marks done gets a closed gate. A one-time
      bootstrap — `--dry-run` it and get a human's go-ahead before filing anything.

options:
  --dry-run     print the mutating `gh` calls instead of making them (the reads they are
                computed from still happen, so this needs a login)
  --self-test   run the pure-function checks (parser, diff, plan, issue-form heading parse, the
                five DoR rules) plus `ready`, `queue`, `tasks-to-issues`, `claim` and
                `board sync|setup|gates` against a stubbed `gh`; no network, but needs python3
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

# --- the Definition of Ready (MIP-0063 §5.4) ---

# section_state <body> <heading> -> missing | empty | filled.
# An optional issue-form field left blank still renders its heading, with `_No response_` under it,
# so "the heading is there" is not the same question as "the author answered".
section_state() {
  awk -v h="### $2" '
    { sub(/[ \t\r]+$/, "") }
    $0 == h { found = 1; next }
    found && /^#+ / { exit }
    found && $0 != "" && $0 != "_No response_" { filled = 1; exit }
    END { print (found ? (filled ? "filled" : "empty") : "missing") }
  ' <<<"$1"
}

# dor_tier <labels> -> mip | bug | standard. Which tiers the DoR governs, and which headings rules
# 1 and 2 read for each: MIP-0063 §5.4.
dor_tier() {
  if grep -qx 'mip' <<<"$1"; then echo mip
  elif grep -qx 'bug' <<<"$1"; then echo bug
  else echo standard
  fi
}

# dor_section_rule <n> <name> <body> <heading> -> the rule's line; 0 iff the section carries content.
dor_section_rule() {
  case "$(section_state "$3" "$4")" in
    filled) echo "  ✓ $1. $2 present" ;;
    empty)  echo "  ✗ $1. $2 — \"### $4\" is empty"; return 1 ;;
    *)      echo "  ✗ $1. $2 — no \"### $4\" section in the body"; return 1 ;;
  esac
}

# dor_rules <body> <labels> <open-blockers> <tier> -> §3's one line per rule; 0 iff all five hold.
# Pure: the caller does the two reads. <labels> and <open-blockers> are newline-separated.
dor_rules() {
  local body="$1" labels="$2" blockers="$3" tier="$4"
  local h1="Acceptance criteria" h2="Named test" rc=0 missing=""
  if [ "$tier" = bug ]; then h1="What you expected instead"; h2="Failing test"; fi

  dor_section_rule 1 "acceptance criteria" "$body" "$h1" || rc=1
  dor_section_rule 2 "named test" "$body" "$h2" || rc=1

  grep -q '^area/' <<<"$labels" || missing="area/*"
  grep -q '^layer/' <<<"$labels" || missing="${missing:+$missing and }layer/*"
  if [ -z "$missing" ]; then echo "  ✓ 3. area/* and layer/* labels set"
  else echo "  ✗ 3. $missing not set"; rc=1
  fi

  if grep -q '^size/' <<<"$labels"; then echo "  ✓ 4. size/* label set"
  else echo "  ✗ 4. size/* label missing"; rc=1
  fi

  if [ -z "$blockers" ]; then echo "  ✓ 5. no open blocked-by dependency"
  else echo "  ✗ 5. blocked by ${blockers//$'\n'/ } (still open)"; rc=1
  fi
  return "$rc"
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

# Memoised like resolve_nwo's $nwo: `tasks-to-issues` reaches this once per edge through
# cmd_deps_add, and `gh auth status` is a network round trip.
gh_checked=0
require_gh() {
  [ "$gh_checked" -eq 0 ] || return 0
  command -v gh >/dev/null || { echo "issues.sh: gh is not installed" >&2; exit 1; }
  gh auth status </dev/null >/dev/null 2>&1 || {
    echo "issues.sh: gh is not logged in. Inside ai-jail there is no login and none can be acquired (AGENTS.md) — run this from the host, or use --dry-run." >&2
    exit 1
  }
  gh_checked=1
}

# gh picks its target repo from the current directory, but everything this script acts on comes
# from its own checkout. Run issues.sh from inside another clone and it would reconcile that repo
# against marola's taxonomy — and --prune would delete the difference. Resolve once, from $root;
# `gh api` has no --repo, so for those calls the pin is spelling $nwo into the path.
resolve_nwo() {
  [ -n "$nwo" ] || nwo="$(cd "$root" && gh repo view --json nameWithOwner -q .nameWithOwner </dev/null)"
}

# issue_payload <number> [repo] — [repo] defaults to $nwo; a caller acting on a MIP-0070 parent or
# a sub-issue in a different repo than "this one" passes it explicitly.
issue_payload() {
  local number="$1" repo="${2:-$nwo}" payload err rc=0
  err="$(mktemp)"
  payload="$(gh api "repos/$repo/issues/$number" </dev/null 2>"$err")" || rc=$?
  if [ "$rc" -ne 0 ]; then
    # A 401, a 403 rate-limit and a DNS failure are not "no such issue". Reporting all of them as
    # one sends an unattended run after the wrong cause, so anything but a 404 quotes gh.
    if grep -q 'HTTP 404' "$err"; then
      echo "issues.sh: issue #$number not found on $repo" >&2
    else
      { echo "issues.sh: looking up issue #$number on $repo failed:"; sed 's/^/  /' "$err"; } >&2
    fi
  fi
  rm -f "$err"
  [ "$rc" -eq 0 ] || return 1
  printf '%s\n' "$payload"
}

# The one place a number becomes an id. Both POSTs below go through it.
resolve_issue_id() {
  local payload
  payload="$(issue_payload "$1" "${2:-}")" || return 1
  issue_id_of "$payload" "$1"
}

# ref_display <repo> <number> -> "#N" when <repo> is this run's own $nwo, else "<repo>#N" — the
# org-wide commands (§5.7) print a bare number for "here" and a qualified one for anywhere else.
ref_display() {
  [ "$1" = "$nwo" ] && printf '#%s\n' "$2" || printf '%s#%s\n' "$1" "$2"
}

# parse_issue_ref <arg> -> "<repo>\t<number>". A bare number means $nwo (unchanged single-repo
# behaviour); "owner/repo#N" names another repo on the org (§5.7's `issue-claim` shape).
parse_issue_ref() {
  local arg="$1" repo number
  case "$arg" in
    */*'#'*)
      repo="${arg%%#*}"
      number="$(arg_number "${arg##*#}" "issue reference")" || return 1
      ;;
    *)
      repo="$nwo"
      number="$(arg_number "$arg" "issue reference")" || return 1
      ;;
  esac
  printf '%s\t%s\n' "$repo" "$number"
}

# sub_issue_link <parent-repo> <parent> <child-repo> <child> — the id-resolution + POST `sub add`
# and MIP-0070 §5.7's parent-issue wiring share. GitHub's "Add a sub-issue" docs: `sub_issue_id`
# (the child's database id) "must belong to the same repository owner as the parent issue" — same
# org, any repo, not the same repo — which is exactly this org's shape, so a cross-repo call here
# is documented to work, not a guess.
sub_issue_link() {
  local parent_repo="$1" parent="$2" child_repo="$3" child="$4" child_id
  child_id="$(resolve_issue_id "$child" "$child_repo")" || return 1
  # -F, not -f: sub_issue_id is an integer in the API schema and -f would send it quoted.
  run gh api --method POST "repos/$parent_repo/issues/$parent/sub_issues" -F "sub_issue_id=$child_id"
}

# sub_issues_of <repo> <parent> -> "<repo>\t<number>" one per line, its existing sub-issues — each
# carries its own `repository.full_name` (a cross-repo sub-issue is a real issue object, not a
# stub), which is what makes this idempotency check work across repos too.
sub_issues_of() {
  gh api --paginate "repos/$1/issues/$2/sub_issues" </dev/null \
    --jq '.[] | "\(.repository.full_name)\t\(.number)"'
}

cmd_sub_add() {
  [ $# -eq 2 ] || { echo "issues.sh sub add: expects <parent> <child>" >&2; usage >&2; exit 1; }
  local parent child
  parent="$(arg_number "$1" "sub add")" || exit 1
  child="$(arg_number "$2" "sub add")" || exit 1
  [ "$parent" != "$child" ] || { echo "issues.sh sub add: #$parent cannot be its own sub-issue" >&2; exit 1; }
  require_gh
  resolve_nwo
  sub_issue_link "$nwo" "$parent" "$nwo" "$child"
}

# dep_link <repo> <issue> <blocker-repo> <blocker> — the id-resolution + POST `deps add` and
# `tasks-to-issues`'s native edges share. Unlike sub-issues, GitHub's "Add a blocked-by dependency"
# docs give only `issue_id` (an integer) with no cross-repository note either way, so a cross-repo
# call here is attempted, not confirmed. Exit 2 means the blocker's id could not even be resolved —
# a real failure whichever repo it's in; exit 1 means the POST itself was rejected once the id was
# known, which a cross-repo caller reads as "this shape isn't supported" rather than a hard failure.
dep_link() {
  local repo="$1" issue="$2" blocker_repo="$3" blocker="$4" blocker_id
  blocker_id="$(resolve_issue_id "$blocker" "$blocker_repo")" || return 2
  run gh api --method POST "repos/$repo/issues/$issue/dependencies/blocked_by" -F "issue_id=$blocker_id"
}

# `return`, never `exit`: `tasks-to-issues` calls this once per edge and counts what failed, and an
# `exit` here would leave the shell from inside that `if` — no summary, half the edges unwired, and
# the accumulator unreachable. The standalone `deps add` path still exits non-zero, via set -e.
cmd_deps_add() {
  [ $# -ge 1 ] || { echo "issues.sh deps add: expects <issue> --blocked-by <n>" >&2; usage >&2; return 1; }
  local issue blocker="" blocker_id
  issue="$(arg_number "$1" "deps add")" || return 1
  shift
  while [ $# -gt 0 ]; do
    case "$1" in
      --blocked-by)
        [ $# -ge 2 ] || { echo "issues.sh deps add: --blocked-by needs an issue number" >&2; return 1; }
        [ -z "$blocker" ] || { echo "issues.sh deps add: --blocked-by given twice (#$blocker, then $2) — one edge per call" >&2; return 1; }
        blocker="$(arg_number "$2" "deps add --blocked-by")" || return 1
        shift 2 ;;
      *) echo "issues.sh deps add: unknown argument: $1" >&2; usage >&2; return 1 ;;
    esac
  done
  [ -n "$blocker" ] || { echo "issues.sh deps add: --blocked-by <n> is required" >&2; return 1; }
  [ "$issue" != "$blocker" ] || { echo "issues.sh deps add: #$issue cannot block itself" >&2; return 1; }
  require_gh
  resolve_nwo
  dep_link "$nwo" "$issue" "$nwo" "$blocker"
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

# §5.2: `agent-ready` is a label *and* a board Status value, and nothing but this script writes
# either. The removal is the half that rots if it is skipped — an issue that regressed keeps the
# label, and the agent queue quietly fills with work that no longer passes.
dor_apply_label() {
  local repo="$1" n="$2" rc="$3" had="$4"
  if [ "$rc" -eq 0 ]; then
    [ "$had" -eq 0 ] || { echo "  label \`agent-ready\` already set"; return 0; }
    run gh issue edit --repo "$repo" "$n" --add-label agent-ready || return 1
    echo "  label \`agent-ready\` added$(dry_tag)"
  else
    [ "$had" -eq 1 ] || { echo "  label \`agent-ready\` not added"; return 0; }
    run gh issue edit --repo "$repo" "$n" --remove-label agent-ready || return 1
    echo "  label \`agent-ready\` removed$(dry_tag)"
  fi
}

# <issue> is a bare number (this repo, unchanged) or `owner/repo#N` (MIP-0070 §5.7) — the same
# shape `claim` takes, since claim re-runs this rule set on whatever it was asked to claim.
cmd_ready() {
  [ $# -eq 1 ] || { echo "issues.sh ready: expects <issue>" >&2; usage >&2; return 1; }
  require_gh
  resolve_nwo
  local repo n
  IFS=$'\t' read -r repo n < <(parse_issue_ref "$1") || return 1
  local payload body labels tier blockers rules rc=0 had=0 ref
  ref="$(ref_display "$repo" "$n")"
  payload="$(issue_payload "$n" "$repo")" || return 1
  # For the payload-is-really-#n and not-a-pull-request guards; `ready` has no use for the id.
  # `return`, never `exit`: cmd_claim calls this inside a guard, and an exit here would leave the
  # shell from inside it — claim's own diagnostic never printed.
  issue_id_of "$payload" "$n" >/dev/null || return 1
  body="$(jq -r '.body // ""' <<<"$payload")"
  labels="$(jq -r '(.labels // [])[].name' <<<"$payload")"
  grep -qx 'agent-ready' <<<"$labels" && had=1 || true
  tier="$(dor_tier "$labels")"

  if [ "$tier" = mip ]; then
    rc=1
    echo "✗ $ref is not agent-ready — it is a MIP proposal, and the DoR does not apply (MIP-0063 §5.1)"
  else
    # --paginate and </dev/null for the same two reasons as `deps list`: 50 edges are allowed and a
    # dropped blocker reads as no blocker, and a gh that reads stdin eats the caller's.
    blockers="$(gh api --paginate "repos/$repo/issues/$n/dependencies/blocked_by" </dev/null \
      --jq '.[] | select(.state == "open") | "#\(.number)"')"
    rules="$(dor_rules "$body" "$labels" "$blockers" "$tier")" || rc=$?
    if [ "$rc" -eq 0 ]; then echo "✓ $ref is agent-ready"; else echo "✗ $ref is not agent-ready"; fi
    [ "$tier" != bug ] || echo "  bug: rules 1 and 2 read \"### What you expected instead\" and \"### Failing test\" (MIP-0063 §5.4)"
    printf '%s\n' "$rules"
  fi

  dor_apply_label "$repo" "$n" "$rc" "$had" || return 1
  return "$rc"
}

# issue-queue reads the org, not one repo (MIP-0070 §5.7): `gh issue list --repo` has no
# org-wide form, so this is `gh search issues --owner <org>`, the closest supported equivalent of
# `org:marola-dev is:issue is:open` (search excludes pull requests by default, matching `is:issue`).
cmd_queue() {
  local milestone=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --milestone)
        [ $# -ge 2 ] || { echo "issues.sh queue: --milestone needs a name" >&2; exit 1; }
        milestone="$2"; shift 2 ;;
      *) echo "issues.sh queue: unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
  done
  require_gh
  resolve_nwo
  local org="${MAROLA_UMBRELLA%%/*}"

  local limit=300 list n_open
  # shellcheck disable=SC2054 # one --json value, comma-separated per gh's own syntax, not a
  # second array element.
  local -a search_args=(--owner "$org" --state open --limit "$limit"
    --json number,title,labels,assignees,repository)
  # `gh search issues --json` has no milestone field to filter client-side the way a single repo's
  # `gh issue list` answer does, so this pushes the filter into the query instead (still one call).
  [ -z "$milestone" ] || search_args+=(--milestone "$milestone")
  list="$(gh search issues "${search_args[@]}" </dev/null)"
  n_open="$(jq 'length' <<<"$list")"
  # Past the limit gh stops silently, and a truncated queue is the one failure mode nobody notices:
  # the missing issues read as "nothing ready".
  if [ "$n_open" -ge "$limit" ]; then
    echo "issues.sh: $org has at least $limit open issues, this script's page limit — raise it before trusting this queue." >&2
    exit 1
  fi

  local pool rows others n_repo n_number open_blockers blocked=0 ready_n others_n
  pool="$(jq '
    [ .[]
      | select((.assignees | length) == 0)
      | { number, title, labels: [.labels[].name], repo: .repository.nameWithOwner } ]' <<<"$list")"

  # `ref`: a bare `#N` for this run's own repo, `owner/repo#N` for anywhere else — unchanged output
  # for a repo that has not split yet, since every issue's repo equals $nwo (§5.7).
  rows="$(jq -r --arg nwo "$nwo" '
    def first_label(p): ([ .labels[] | select(startswith(p)) ] | first) // "";
    # Ranks, not `index(first_label(…))`: jq evaluates an argument against the filter it is passed
    # to, so inside index() the input is the array being searched, not the issue.
    def size_rank: if (.labels | index("size/S")) then 0
                   elif (.labels | index("size/M")) then 1
                   elif (.labels | index("size/L")) then 2 else 9 end;
    def ref: if .repo == $nwo then (.number | tostring) else .repo + "#" + (.number | tostring) end;
    [ .[] | select(.labels | index("agent-ready")) ]
    | sort_by(size_rank, (if (.labels | index("priority/high")) then 0 else 1 end), .number)
    | .[] | [ref, first_label("size/"), first_label("area/"), first_label("layer/"), .title]
    | @tsv' <<<"$pool")"
  # An own-repo ref is a bare number here (no "#" yet) so this can restore the exact pre-org-wide
  # format for it (`#%-4s`); a foreign one already carries `owner/repo#`, so it gets its own,
  # wider column instead of a literal "#" jammed in front of it.
  [ -z "$rows" ] || awk -F'\t' '{
    if ($1 ~ /#/) printf "%-24s  %-6s  %-18s  %-12s  %s\n", $1, $2, $3, $4, $5
    else printf "#%-4s  %-6s  %-18s  %-12s  %s\n", $1, $2, $3, $4, $5
  }' <<<"$rows"

  ready_n="$(jq '[ .[] | select(.labels | index("agent-ready")) ] | length' <<<"$pool")"
  others="$(jq -r '.[] | select(.labels | index("agent-ready") | not) | "\(.repo)\t\(.number)"' <<<"$pool")"
  others_n="$(grep -c . <<<"$others" || true)"
  while IFS=$'\t' read -r n_repo n_number; do
    [ -n "$n_number" ] || continue
    # Assigned to a variable, not tested inline: a failed read inside `[ -z "$(…)" ]` is invisible
    # and would count a blocked issue as triage. </dev/null because this loop's stdin is $others.
    open_blockers="$(gh api --paginate "repos/$n_repo/issues/$n_number/dependencies/blocked_by" </dev/null \
      --jq '.[] | select(.state == "open") | .number')"
    [ -z "$open_blockers" ] || blocked=$((blocked + 1))
  done <<<"$others"
  # "in triage" is everything else unclaimed, not the board's Triage Status: reading that needs the
  # `project` scope §4.4 leaves to a human, and `queue` is the call an agent makes from a jail.
  printf '      %d ready · %d blocked · %d in triage%s\n' \
    "$ready_n" "$blocked" "$((others_n - blocked))" "${milestone:+   (milestone: $milestone)}"
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
      *) rc=1 ;;
    esac
    if [ "$rc" -eq 0 ]; then applied=$((applied + 1)); else failed=$((failed + 1)); fi
  done <<<"$plan"

  echo "labels: $applied of $((applied + failed)) actions applied, $n_orphan orphaned ($n_manifest in the manifest)$(dry_tag)"
  if [ "$failed" -gt 0 ]; then
    echo "issues.sh: $failed of $((applied + failed)) label actions failed, $applied applied — the repo is part-way through the plan. Fix the cause and re-run; sync is idempotent." >&2
    exit 1
  fi
}

# milestone_number [repo] <title> -> that milestone's number, or empty. [repo] defaults to $nwo.
# state=all: a closed milestone still owns its title, so creating over one 422s rather than doing
# nothing, and both callers have to read a no-op either way.
milestone_number() {
  local repo="$nwo" title="$1"
  [ $# -eq 1 ] || { repo="$1"; title="$2"; }
  gh api --paginate "repos/$repo/milestones?state=all&per_page=100" </dev/null \
    --jq '.[] | "\(.number)\t\(.title)"' | awk -F'\t' -v t="$title" '$2 == t { print $1; exit }'
}

# --- tasks-to-issues (MIP-0063 §5.5, MIP-0070 §5.7) ---

# t2i_fail <message> — the one exit point for everything below that can run after the remote-MIP
# fallback has written $resolved_dir (dynamically scoped from cmd_tasks_to_issues's `local`): a
# bare `exit` past that point would leak the temp dir, since a normal end-of-function `rm -rf`
# never runs when `set -e` unwinds through it instead of falling off the end.
t2i_fail() {
  [ -z "${resolved_dir:-}" ] || rm -rf "$resolved_dir"
  echo "issues.sh tasks-to-issues: $*" >&2
  exit 1
}

# One MIP's task table projected into GitHub (§5.7): a parent issue in the umbrella and one
# sub-issue per row, filed in the repo its `delivers` cell names or the umbrella as a fallback.
# scripts/lib/tasks_issues.py does the reading, resolving and rewriting; this does the gh calls.
# One-way and re-runnable: after this, issues own status and the file owns the plan.
cmd_tasks_to_issues() {
  local arg="" milestone="" deliverable="" file=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --milestone)
        [ $# -ge 2 ] || { echo "issues.sh tasks-to-issues: --milestone needs a name" >&2; exit 1; }
        milestone="$2"; shift 2 ;;
      --deliverable)
        [ $# -ge 2 ] || { echo "issues.sh tasks-to-issues: --deliverable needs a name" >&2; exit 1; }
        deliverable="$2"; shift 2 ;;
      -*) echo "issues.sh tasks-to-issues: unknown argument: $1" >&2; usage >&2; exit 1 ;;
      *)
        [ -z "$arg" ] || { echo "issues.sh tasks-to-issues: one task list per run (got \"$arg\" and \"$1\")" >&2; exit 1; }
        arg="$1"; shift ;;
    esac
  done
  [ -n "$arg" ] || { echo "issues.sh tasks-to-issues: expects <MIP-NNNN|path/to/MIP-NNNN.tasks.md>" >&2; usage >&2; exit 1; }
  case "$arg" in
    MIP-[0-9][0-9][0-9][0-9]) file="$root/docs/MIPs/$arg.tasks.md" ;;
    *) file="$arg" ;;
  esac

  require_gh
  resolve_nwo   # ref_display's "here or elsewhere" needs a real $nwo, not this function's own $umbrella
  local umbrella="$MAROLA_UMBRELLA" umbrella_owner="${MAROLA_UMBRELLA%%/*}"

  local -a ms_args=()
  if [ -n "$milestone" ]; then
    # Decision 6: this command never invents a milestone. Checked before anything is created, not
    # at the first `gh issue create`, which is the difference between "nothing happened" and
    # "three of eight rows are filed and the rest aborted". Scoped to the umbrella: §5.7 milestones
    # are per repo, so this only ever applies to a row that lands there (a fallback, or one with no
    # `**repo**` prefix at all) — a row filed in its own repo carries no milestone from this run.
    [ -n "$(milestone_number "$umbrella" "$milestone")" ] || {
      echo "issues.sh tasks-to-issues: $umbrella has no milestone named \"$milestone\" — create it first (MIP-0063 Decision 6)" >&2
      exit 1
    }
    ms_args=(--milestone "$milestone")
  fi

  # A code repo carries no docs/MIPs of its own post-split (§5.6) — fall back to the umbrella and
  # spool its content to a real path (named like the real file, not a random mktemp name, since
  # tasks_issues.py's parser reads the MIP number back out of the filename).
  local resolved_dir=""
  if [ ! -f "$file" ] && [[ "$arg" =~ ^MIP-[0-9]{4}$ ]]; then
    resolved_dir="$(mktemp -d)"
    local resolved_tmp="$resolved_dir/$arg.tasks.md"
    if (cd "$root" && resolve_mip_file "$arg" tasks) >"$resolved_tmp" 2>/dev/null && [ -s "$resolved_tmp" ]; then
      file="$resolved_tmp"
    else
      rm -rf "$resolved_dir"; resolved_dir=""
    fi
  fi
  [ -f "$file" ] || { echo "issues.sh tasks-to-issues: no such task list: $file" >&2; exit 1; }

  # Which repos this table's rows name (bare, umbrella excluded), then a read-only existence check
  # per name — §5.7: exists iff `gh api repos/<owner>/<name>` succeeds, a 404 means that row falls back.
  local repo_names name rc=0
  repo_names="$(python3 "$root/scripts/lib/tasks_issues.py" repos "$file")" || rc=$?
  [ "$rc" -eq 0 ] || t2i_fail "reading the table's repo column failed"

  local existing_json='[]' rv_err rv_rc
  while read -r name; do
    [ -n "$name" ] || continue
    # The umbrella's own bare name always resolves to the umbrella either way (owner + its own bare
    # name reconstructs the umbrella string) — no need to ask GitHub, and no existing_json entry:
    # the "+N repo(s)" count below is the repos *beyond* the umbrella.
    [ "$name" != "${umbrella#*/}" ] || continue
    rv_err="$(mktemp)"
    rv_rc=0
    # </dev/null: this loop's stdin is $repo_names, and a `gh` with no explicit redirection reads
    # whatever fd 0 is — it would otherwise eat the rest of the here-string on its first call.
    # REST, not `gh repo view`: that one reports a missing repo as a GraphQL error, never a 404.
    gh api "repos/$umbrella_owner/$name" </dev/null >/dev/null 2>"$rv_err" || rv_rc=$?
    if [ "$rv_rc" -eq 0 ]; then
      existing_json="$(jq -c --arg n "$name" '. + [$n]' <<<"$existing_json")"
    elif ! grep -q 'HTTP 404' "$rv_err"; then
      # Only a 404 means "does not exist yet" (§5.7's fallback case); anything else (401, a rate
      # limit, a DNS failure) is not that, and treating it as one would silently, permanently file
      # every later row in the umbrella instead of retrying once the real cause is fixed.
      sed 's/^/  /' "$rv_err" >&2
      rm -f "$rv_err"
      t2i_fail "checking whether $umbrella_owner/$name exists failed (not a 404 — see above)"
    fi
    rm -f "$rv_err"
  done <<<"$repo_names"

  # Every involved repo's issues (state=all: a closed task issue must still dedup) — the umbrella
  # always, plus every confirmed-existing target. The page limit is `queue`'s: past it gh stops
  # silently, and a truncated list reads as "not filed yet".
  local limit=500 issues_by_repo='{}' repo_nwo issues n_issues
  for repo_nwo in "$umbrella" $(jq -r --arg o "$umbrella_owner" '.[] | $o + "/" + .' <<<"$existing_json"); do
    jq -e --arg r "$repo_nwo" 'has($r)' <<<"$issues_by_repo" >/dev/null 2>&1 && continue
    issues="$(gh issue list --repo "$repo_nwo" --state all --limit "$limit" --json number,title </dev/null)" || rc=$?
    [ "$rc" -eq 0 ] || t2i_fail "listing issues on $repo_nwo failed"
    n_issues="$(jq 'length' <<<"$issues")"
    [ "$n_issues" -lt "$limit" ] || t2i_fail "$repo_nwo has at least $limit issues, this script's page limit — raise it before trusting the dedup."
    issues_by_repo="$(jq -c --arg r "$repo_nwo" --argjson v "$issues" '.[$r] = $v' <<<"$issues_by_repo")"
  done

  # The parent's title wants the MIP's own H1, not the tasks file's — and a stable one: the parent
  # is found by title prefix, so a run that silently fell back to a bare "MIP-NNNN" title here would
  # risk filing a second parent later, once the doc resolves again. Refused instead. The H1 itself
  # already starts with "MIP-NNNN: " — stripped here so `parent_title` doesn't double it up.
  local mip_from_file mip_doc mip_title
  mip_from_file="$(basename "$file" .tasks.md)"
  mip_doc="$(cd "$root" && resolve_mip_file "$mip_from_file" doc 2>/dev/null)" || mip_doc=""
  mip_title="$(sed -n 's/^# *//p' <<<"$mip_doc" | head -1 | sed -E 's/^MIP-[0-9]{4}: *//')"
  [ -n "$mip_title" ] || t2i_fail "could not resolve $mip_from_file's own H1 (needed for a stable parent-issue title) — is its MIP doc reachable?"

  local issues_file plan
  issues_file="$(mktemp)"
  printf '%s\n' "$issues_by_repo" > "$issues_file"
  plan="$(python3 "$root/scripts/lib/tasks_issues.py" plan "$file" --umbrella "$umbrella" \
    --issues "$issues_file" --existing "$existing_json" --mip-title "$mip_title")" || rc=$?
  rm -f "$issues_file"
  [ "$rc" -eq 0 ] || t2i_fail "planning the issue set failed (see above)"

  local mip n_rows n_fallback
  mip="$(jq -r '.mip' <<<"$plan")"
  n_rows="$(jq '.rows | length' <<<"$plan")"
  n_fallback="$(jq '[.rows[] | select(.fallback)] | length' <<<"$plan")"
  echo "tasks-to-issues: MIP-$mip, $n_rows rows → $umbrella + $(jq 'length' <<<"$existing_json") repo(s)${milestone:+   (milestone: $milestone)}${deliverable:+   (deliverable: $deliverable)}"
  jq -r --arg mip "$mip" '.rows[] | select(.fallback) | "  fallback: \($mip)-T\(.id) -> \(.repo) (its own repo is not there yet)"' <<<"$plan"

  # §5.1/§5.7 makes the milestone or Deliverable the thing an issue belongs to, so filing with
  # neither is a real choice, not a default. Said once, and only when this run would actually
  # create something.
  local n_new
  n_new="$(jq '([.rows[] | select(.issue == null)] | length) + (if .parent.issue == null then 1 else 0 end)' <<<"$plan")"
  if [ "$dry" -eq 0 ] && [ -z "$milestone" ] && [ -z "$deliverable" ] && [ "$n_new" -gt 0 ]; then
    echo "issues.sh tasks-to-issues: no --milestone or --deliverable — the $n_new new issue(s) will belong to no deliverable (MIP-0063 §5.1, MIP-0070 §5.7)." >&2
  fi

  local map='{}' i id title body number url row_repo created=0 existing_n=0 failed=0
  local parent_repo parent_number parent_title_s parent_body_s
  parent_repo="$(jq -r '.parent.repo' <<<"$plan")"
  parent_number="$(jq -r '.parent.issue // empty' <<<"$plan")"
  parent_title_s="$(jq -r '.parent.title' <<<"$plan")"
  if [ -n "$parent_number" ]; then
    printf '  parent      #%-6s already filed: %s\n' "$parent_number" "$parent_title_s"
  else
    parent_body_s="$(jq -r '.parent.body' <<<"$plan")"
    if [ "$dry" -eq 1 ]; then
      printf '  parent      would create: %s\n' "$parent_title_s"
      run gh issue create --repo "$parent_repo" --title "$parent_title_s" --body "$parent_body_s" ${ms_args[@]+"${ms_args[@]}"}
    else
      rc=0
      url="$(gh issue create --repo "$parent_repo" --title "$parent_title_s" --body "$parent_body_s" ${ms_args[@]+"${ms_args[@]}"} </dev/null)" || rc=$?
      if [ "$rc" -ne 0 ]; then
        echo "issues.sh tasks-to-issues: creating the MIP-$mip parent issue failed (exit $rc)" >&2
        failed=$((failed + 1))
      else
        parent_number="$(arg_number "${url##*/}" "the URL gh printed for the MIP-$mip parent")" || {
          echo "issues.sh: the MIP-$mip parent was created but gh printed an unparseable URL: $url — link it by hand" >&2
          failed=$((failed + 1))
        }
        [ -z "$parent_number" ] || printf '  parent      #%-6s created: %s\n' "$parent_number" "$parent_title_s"
      fi
    fi
  fi

  for ((i = 0; i < n_rows; i++)); do
    id="$(jq -r ".rows[$i].id" <<<"$plan")"
    row_repo="$(jq -r ".rows[$i].repo" <<<"$plan")"
    number="$(jq -r ".rows[$i].issue // empty" <<<"$plan")"
    title="$(jq -r ".rows[$i].title" <<<"$plan")"
    if [ -n "$number" ]; then
      printf '  %-10s #%-6s already filed (%s)\n' "$mip-T$id" "$number" "$row_repo"
      existing_n=$((existing_n + 1))
      map="$(jq -c --arg k "$id" --arg r "$row_repo" --argjson n "$number" '.[$k] = {repo: $r, number: $n}' <<<"$map")"
      continue
    fi
    body="$(jq -r ".rows[$i].body" <<<"$plan")"
    # §5.7: milestones are per repo, so a row landing outside the umbrella carries none from this
    # run — passing $ms_args there would ask the target repo for a milestone it was never told to
    # have (Decision 6 only ever checked the umbrella's own).
    local -a row_ms_args=()
    [ "$row_repo" != "$umbrella" ] || row_ms_args=(${ms_args[@]+"${ms_args[@]}"})
    if [ "$dry" -eq 1 ]; then
      printf '  %-10s would create in %s: %s\n' "$mip-T$id" "$row_repo" "$title"
      run gh issue create --repo "$row_repo" --title "$title" --body "$body" ${row_ms_args[@]+"${row_ms_args[@]}"}
      created=$((created + 1))
      continue
    fi
    # Not through run(): it sends the child's stdout to /dev/null, and the new issue's URL is the
    # one piece of output the rest of this command cannot do without.
    rc=0
    url="$(gh issue create --repo "$row_repo" --title "$title" --body "$body" ${row_ms_args[@]+"${row_ms_args[@]}"} </dev/null)" || rc=$?
    if [ "$rc" -ne 0 ]; then
      echo "issues.sh tasks-to-issues: creating $mip-T$id failed (exit $rc)" >&2
      failed=$((failed + 1))
      continue
    fi
    # The issue exists by now, so the diagnostic has to carry the URL itself: arg_number quotes
    # the last path segment, which in this very case is usually empty.
    number="$(arg_number "${url##*/}" "the URL gh printed for $mip-T$id")" || {
      echo "issues.sh: $mip-T$id was created but gh printed an unparseable URL: $url — link it by hand" >&2
      failed=$((failed + 1)); continue
    }
    printf '  %-10s #%-6s created in %s\n' "$mip-T$id" "$number" "$row_repo"
    created=$((created + 1))
    map="$(jq -c --arg k "$id" --arg r "$row_repo" --argjson n "$number" '.[$k] = {repo: $r, number: $n}' <<<"$map")"
  done

  local -a link_args=()
  [ "$dry" -eq 0 ] || link_args=(--dry-run)
  local linked n_linked
  linked="$(python3 "$root/scripts/lib/tasks_issues.py" link "$file" --map "$map" ${link_args[@]+"${link_args[@]}"})" \
    || { linked=""; failed=$((failed + 1)); }
  n_linked="$(grep -c . <<<"$linked" || true)"
  [ -z "$linked" ] || sed 's/^/  link: /' <<<"$linked"

  # Every row becomes the parent's sub-issue (§5.7) — only once the parent itself is filed.
  local sub_wired=0 sub_already=0 sub_pending=0 sub_present=""
  if [ -n "$parent_number" ]; then
    sub_present="$(sub_issues_of "$parent_repo" "$parent_number")"
    for ((i = 0; i < n_rows; i++)); do
      id="$(jq -r ".rows[$i].id" <<<"$plan")"
      number="$(jq -r --arg k "$id" '.[$k].number // empty' <<<"$map")"
      row_repo="$(jq -r --arg k "$id" '.[$k].repo // empty' <<<"$map")"
      if [ -z "$number" ]; then
        printf '  sub: %s — pending, not filed yet\n' "$mip-T$id"
        sub_pending=$((sub_pending + 1))
      elif grep -qxF "$(printf '%s\t%s' "$row_repo" "$number")" <<<"$sub_present"; then
        printf '  sub: %s already under the parent\n' "$mip-T$id"
        sub_already=$((sub_already + 1))
      elif printf '  sub: %s under the parent\n' "$mip-T$id" && sub_issue_link "$parent_repo" "$parent_number" "$row_repo" "$number"; then
        sub_wired=$((sub_wired + 1))
      else
        failed=$((failed + 1))
      fi
    done
  else
    sub_pending=$n_rows
  fi

  local deps dep dep_repo dep_number present wired=0 already=0 pending=0 skipped_cross=0 link_rc
  for ((i = 0; i < n_rows; i++)); do
    id="$(jq -r ".rows[$i].id" <<<"$plan")"
    deps="$(jq -r ".rows[$i].deps[]?" <<<"$plan")"
    [ -n "$deps" ] || continue
    number="$(jq -r --arg k "$id" '.[$k].number // empty' <<<"$map")"
    row_repo="$(jq -r --arg k "$id" '.[$k].repo // empty' <<<"$map")"
    present=""
    # No state filter: the 2026-09-27 probe found an edge survives closing both issues and reads
    # back as closed, so a closed blocker is still an edge and re-adding it would be a duplicate.
    # repo+number, not the number alone: two repos can share a number, and a match on the number
    # alone would read a genuinely-unwired blocker as already wired.
    [ -z "$number" ] || present="$(gh api --paginate "repos/$row_repo/issues/$number/dependencies/blocked_by" </dev/null \
      --jq '.[] | "\(.repository.full_name)\t\(.number)"')"
    while read -r dep; do
      [ -n "$dep" ] || continue
      case "$dep" in
        *-T*)
          dep_repo="$(jq -r --arg k "$dep" '.cross[$k].repo // empty' <<<"$plan")"
          dep_number="$(jq -r --arg k "$dep" '.cross[$k].number // empty' <<<"$plan")" ;;
        *)
          dep_repo="$(jq -r --arg k "$dep" '.[$k].repo // empty' <<<"$map")"
          dep_number="$(jq -r --arg k "$dep" '.[$k].number // empty' <<<"$map")" ;;
      esac
      if [ -z "$number" ] || [ -z "$dep_number" ]; then
        printf '  edge: %s blocked by %s — pending, one of the two is not filed yet\n' "$mip-T$id" "$(case "$dep" in *-T*) echo "$dep" ;; *) echo "$mip-T$dep" ;; esac)"
        pending=$((pending + 1))
      elif grep -qxF "$(printf '%s\t%s' "$dep_repo" "$dep_number")" <<<"$present"; then
        printf '  edge: %s blocked by %s — already wired\n' "$(ref_display "$row_repo" "$number")" "$(ref_display "$dep_repo" "$dep_number")"
        already=$((already + 1))
      elif [ "$row_repo" = "$dep_repo" ]; then
        if printf '  edge: %s blocked by %s\n' "$(ref_display "$row_repo" "$number")" "$(ref_display "$dep_repo" "$dep_number")" \
          && dep_link "$row_repo" "$number" "$dep_repo" "$dep_number"; then
          wired=$((wired + 1))
        else
          failed=$((failed + 1))
        fi
      else
        # Cross-repo: attempted with the same API (issue_id, same as sub-issues), never guessed.
        # GitHub's REST docs for this endpoint show only `issue_id`, with no cross-repository note
        # either way (unlike sub-issues' explicit same-owner allowance). dep_link's exit 2 means the
        # blocker's id itself couldn't be resolved — a real failure regardless of repo; exit 1 means
        # GitHub rejected the POST once the id was known, which is the "not confirmed" case §5.7
        # reports as skipped rather than failed.
        printf '  edge: %s blocked by %s (cross-repo)\n' "$(ref_display "$row_repo" "$number")" "$(ref_display "$dep_repo" "$dep_number")"
        link_rc=0
        dep_link "$row_repo" "$number" "$dep_repo" "$dep_number" || link_rc=$?
        if [ "$link_rc" -eq 0 ]; then
          wired=$((wired + 1))
        elif [ "$link_rc" -eq 2 ]; then
          failed=$((failed + 1))
        else
          echo "  edge: cross-repo blocked-by not confirmed by GitHub's docs — skipped, wire it by hand once confirmed" >&2
          skipped_cross=$((skipped_cross + 1))
        fi
      fi
    done <<<"$deps"
  done

  # §5.7: every issue this run knows about — the parent and every row — goes onto Project 1.
  # --deliverable additionally sets its Deliverable field; without it, a plain add (§5.7).
  # Reads/writes $board_ok/$board_failed/$deliverable from the enclosing scope, the same way
  # dor_apply_label reads $dry — one place for the if/else so a failure is never a `&&`/`||`
  # short-circuit ambiguity (`A && B || C` is not if/then/else: C also runs if B itself fails).
  local board_ok=0 board_failed=0
  board_file_one() {
    local repo="$1" number="$2" url="$3" label="$4"
    if [ -n "$deliverable" ]; then
      if board_set_deliverable "$repo" "$number" "$url" "$deliverable"; then board_ok=$((board_ok + 1))
      else board_failed=$((board_failed + 1)); echo "  board: $label not added/labelled (above)" >&2
      fi
    elif board_add_only "$repo" "$number" "$url"; then board_ok=$((board_ok + 1))
    else board_failed=$((board_failed + 1)); echo "  board: $label not added (above)" >&2
    fi
  }
  if [ -n "$parent_number" ]; then
    board_file_one "$parent_repo" "$parent_number" "https://github.com/$parent_repo/issues/$parent_number" parent
  fi
  for ((i = 0; i < n_rows; i++)); do
    id="$(jq -r ".rows[$i].id" <<<"$plan")"
    number="$(jq -r --arg k "$id" '.[$k].number // empty' <<<"$map")"
    row_repo="$(jq -r --arg k "$id" '.[$k].repo // empty' <<<"$map")"
    [ -n "$number" ] || continue
    board_file_one "$row_repo" "$number" "https://github.com/$row_repo/issues/$number" "$mip-T$id"
  done
  failed=$((failed + board_failed))

  [ -z "$resolved_dir" ] || rm -rf "$resolved_dir"

  printf 'summary: parent %s · %d created, %d already filed (%d fallback) · %d rows linked · %d sub-issues wired, %d already, %d pending · %d edges wired, %d already wired, %d pending, %d skipped (cross-repo) · board %d ok, %d failed%s\n' \
    "$([ -n "$parent_number" ] && echo "#$parent_number" || echo pending)" \
    "$created" "$existing_n" "$n_fallback" "$n_linked" \
    "$sub_wired" "$sub_already" "$sub_pending" \
    "$wired" "$already" "$pending" "$skipped_cross" \
    "$board_ok" "$board_failed" "$(dry_tag)"
  [ "$failed" -eq 0 ] || {
    echo "issues.sh tasks-to-issues: $failed action(s) failed — fix the cause and re-run; this command is idempotent." >&2
    exit 1
  }
}

# --- the board (MIP-0063 §5.2), claiming (§5.5) and the phase gates (§5.3) ---

# Projects v2 is GraphQL-only — no REST, no `--repo`, no page of the API that `gh api` reaches the
# way the rest of this file does. Everything that touches it goes through `gh project`, and the
# lookups that turn a title into a project number and a Status name into an option id live here
# and nowhere else.
board_title="Marola"
board_number=""
board_id=""

# has_scope <comma-separated> <scope> — split, not substring: `read:project` contains `project`.
has_scope() {
  tr ',' '\n' <<<"$1" | tr -d ' \r' | grep -qx "$2"
}

# token_scopes -> the token's scopes. A fine-grained PAT sends no X-OAuth-Scopes header, so under
# `pipefail` this exits nonzero with empty output and every caller has to absorb that.
token_scopes() {
  gh api -i user </dev/null 2>/dev/null | grep -i '^x-oauth-scopes:' | head -1 | cut -d: -f2- | tr -d ' \r'
}

# board_require_write — §4.4's human prerequisite, stated once.
board_require_write() {
  local scopes
  # || true: no header means the grep inside fails, and `pipefail` would take the script out with
  # no message at all — the opposite of what the branch below is for.
  scopes="$(token_scopes || true)"
  if [ -z "$scopes" ]; then
    echo "issues.sh: this token sends no X-OAuth-Scopes header (a fine-grained PAT does not) — attempting the board write anyway." >&2
    return 0
  fi
  has_scope "$scopes" project && return 0
  echo "issues.sh: this token has \`read:project\` but not \`project\`, so it cannot write the board (MIP-0063 §4.4). A human runs: gh auth refresh -s project" >&2
  # --dry-run writes nothing and the reads need only `read:project`, so the missing scope is not
  # yet a reason to refuse: seeing the plan is how anyone decides whether to ask for the scope.
  [ "$dry" -eq 0 ] || return 0
  return 1
}

# board_resolve [owner] -> $board_number and $board_id for $board_title, under [owner] (default:
# this repo's own owner — unchanged for every pre-existing caller).
board_resolve() {
  [ -z "$board_number" ] || return 0
  local owner="${1:-${nwo%%/*}}" projects n
  projects="$(gh project list --owner "$owner" --format json --limit 100 </dev/null)"
  n="$(jq --arg t "$board_title" '[ .projects[] | select((.title | ascii_downcase) == ($t | ascii_downcase)) ] | length' <<<"$projects")"
  # §5.2 names one board. Two projects sharing a title is a human decision this script must not
  # make for them, and zero means the board is not there to sync against.
  [ "$n" -eq 1 ] || {
    echo "issues.sh: expected exactly one project titled \"$board_title\" under $owner, found $n (MIP-0063 §5.2)." >&2
    return 1
  }
  board_number="$(jq -r --arg t "$board_title" '.projects[] | select((.title | ascii_downcase) == ($t | ascii_downcase)) | .number' <<<"$projects")"
  board_id="$(jq -r --arg t "$board_title" '.projects[] | select((.title | ascii_downcase) == ($t | ascii_downcase)) | .id' <<<"$projects")"
}

# board_items <owner> [query] -> the project's items as a JSON array. `query` is GitHub's Projects
# filter syntax (e.g. "is:closed") — a second server-side query, not a local filter: item-list's
# content carries only type/body/title/number/repository/url, never a state field to filter on.
board_items() {
  local limit=500 raw n query="${2-}"
  local -a extra=()
  [ -z "$query" ] || extra=(--query "$query")
  raw="$(gh project item-list "$board_number" --owner "$1" --format json --limit "$limit" "${extra[@]}" </dev/null)"
  n="$(jq '.items | length' <<<"$raw")"
  # Same trap as `queue`'s page limit: an unseen item reads as "not on the board", and the sync
  # would add a second copy of it.
  [ "$n" -lt "$limit" ] || {
    echo "issues.sh: the board holds at least $limit items, this script's page limit — raise it before trusting the sync." >&2
    return 1
  }
  jq '.items' <<<"$raw"
}

# board_status_option <fields-json> <option-name> -> "<field-id>\t<option-id>", empty when absent.
# Empty, not the nearest name: an option §5.2 defines but the board lacks must be reported, never
# silently swapped for one nobody is looking at.
board_status_option() {
  jq -r --arg name "$2" '
    .fields[] | select(.name == "Status" and .type == "ProjectV2SingleSelectField")
    | .id as $f | .options[] | select(.name == $name) | [$f, .id] | @tsv' <<<"$1"
}

# board_text_field_id <fields-json> <name> -> that plain (non-select) field's id, or empty. A
# custom TEXT field reports the same `type` as a built-in one (Title, Body): "ProjectV2Field".
board_text_field_id() {
  jq -r --arg name "$2" '
    [ .fields[] | select(.name == $name and .type == "ProjectV2Field") | .id ] | first // ""' <<<"$1"
}

# board_item_id <items-json> <nwo> <issue-number> -> that issue's project item id, or empty.
# Pinned to the repository as well as the number: one board can hold several repos, and issue
# numbers are only unique within one.
board_item_id() {
  jq -r --arg nwo "$2" --arg n "$3" '
    [ .[] | select((.content.repository // "") == $nwo and ((.content.number // -1) | tostring) == $n) | .id ]
    | first // ""' <<<"$1"
}

# The Status the project's built-in auto-add workflow writes when it puts an issue on the board
# (§4.4). It is the one value that means "nobody has looked at this yet" rather than a state
# someone chose, which is why `board_plan` may overwrite it and may overwrite nothing else.
board_autoadd_status="Backlog"

# board_plan <items-json> <issues-json> <nwo> -> one TSV action per line:
#   add   <issue-url> <status> <number>   — not on the board
#   set   <item-id>   <status> <number>   — on the board with no Status at all
#   adopt <item-id>   <status> <number>   — on the board carrying only the auto-add default
# Status follows the issue's state: assigned is In progress, `agent-ready` is Ready, anything else
# is still Triage. Every other Status is left alone — `sync` is not the `agent-ready`↔Status
# reconciliation job §8 defers to §11.1, and pulling a card a maintainer dragged to In review back
# to Triage every run would undo their work. `Backlog` is the exception because nobody dragged it
# there; the auto-add workflow wrote it, and adopting it once is what lets an issue's first
# contact with the board mean anything at all.
# `$1`/`$2` go through temp files, not `--argjson`: a real board's item-list JSON exceeds
# MAX_ARG_STRLEN (128 KiB) and `--argjson` fails there with "Argument list too long".
board_plan() {
  local items_file issues_file rc=0
  items_file="$(mktemp)"; issues_file="$(mktemp)"
  printf '%s' "$1" > "$items_file"
  printf '%s' "$2" > "$issues_file"
  jq -rn --slurpfile items_raw "$items_file" --slurpfile issues_raw "$issues_file" \
    --arg nwo "$3" --arg auto "$board_autoadd_status" '
    ($items_raw[0]) as $items | ($issues_raw[0]) as $issues |
    def want: if (((.assignees // []) | length) > 0) then "In progress"
              elif ([(.labels // [])[] | .name] | index("agent-ready")) then "Ready"
              else "Triage" end;
    ( [ $items[] | select((.content.repository // "") == $nwo and .content.number != null)
        | {key: (.content.number | tostring), value: .} ] | from_entries ) as $by
    | $issues[] | . as $i | ($i | want) as $w | $by[$i.number | tostring] as $it
    | if $it == null then ["add", $i.url, $w, ($i.number | tostring)]
      elif (($it.status // "") == "") then ["set", $it.id, $w, ($i.number | tostring)]
      elif ($it.status == $auto) then ["adopt", $it.id, $w, ($i.number | tostring)]
      else empty end
    | @tsv' || rc=$?
  rm -f "$items_file" "$issues_file"
  return "$rc"
}

# board_closed_plan <closed-items-json> <open-issues-json> <nwo> -> one TSV action per line,
# item-id/status/number, per closed issue not already Done (MIP-0063 §4.4's fallback). An absent
# Status prints as the literal "(no Status)": `read`'s IFS=tab collapse would otherwise shift
# $number into $status.
board_closed_plan() {
  local items_file issues_file rc=0
  items_file="$(mktemp)"; issues_file="$(mktemp)"
  printf '%s' "$1" > "$items_file"
  printf '%s' "$2" > "$issues_file"
  jq -rn --slurpfile items_raw "$items_file" --slurpfile issues_raw "$issues_file" --arg nwo "$3" '
    ($items_raw[0]) as $items | ($issues_raw[0]) as $issues |
    ([$issues[].number]) as $open |
    $items[] | select(.content.type == "Issue" and (.content.repository // "") == $nwo and .content.number != null)
    | select((.status // "") != "Done")
    | select(.content.number as $n | ($open | index($n)) == null)
    | [.id, (.status // "(no Status)"), (.content.number | tostring)] | @tsv' || rc=$?
  rm -f "$items_file" "$issues_file"
  return "$rc"
}

# board_status_missing <quoted option name(s)> [how many issues it left alone]
board_status_missing() {
  echo "issues.sh:${2:+ $2 issue(s) left alone —} the board's Status field has no $1 option — run \`scripts/issues.sh board setup\` first. §5.2's six are Triage / Spec / Ready / In progress / In review / Done; adding one is a \`project\`-scope action in the project UI." >&2
}

# board_set_status <repo> <number> <url> <status> — put one issue on the board and set its Status.
board_set_status() {
  # `owner` in its own statement: one `local` line evaluates every RHS against the pre-existing
  # scope, so `local repo=$1 owner=${repo%%/*}` would read the caller's `repo`, not this `$1`.
  local repo="$1" number="$2" url="$3" status="$4" fields items item pair fid oid owner
  owner="${repo%%/*}"
  board_require_write || return 1
  board_resolve "$owner" || return 1
  fields="$(gh project field-list "$board_number" --owner "$owner" --format json --limit 100 </dev/null)"
  pair="$(board_status_option "$fields" "$status")"
  [ -n "$pair" ] || { board_status_missing "\"$status\""; return 1; }
  items="$(board_items "$owner")" || return 1
  item="$(board_item_id "$items" "$repo" "$number")"
  if [ -z "$item" ]; then
    run gh project item-add "$board_number" --owner "$owner" --url "$url" || return 1
    # An item has no id until it is on the board, so the edit needs a second read. --dry-run
    # cannot do that read, and says so rather than printing an edit against an invented id.
    [ "$dry" -eq 0 ] || { echo "  #$number's Status is set on the real run, once it has an item id"; return 0; }
    items="$(board_items "$owner")" || return 1
    item="$(board_item_id "$items" "$repo" "$number")"
    [ -n "$item" ] || { echo "issues.sh: #$number is still not on the board after adding it" >&2; return 1; }
  fi
  fid="${pair%%$'\t'*}"; oid="${pair##*$'\t'}"
  run gh project item-edit --id "$item" --project-id "$board_id" --field-id "$fid" --single-select-option-id "$oid"
}

# board_add_only <repo> <number> <url> — put an issue on the board with no Status/field change,
# idempotently. MIP-0070 §5.7's default: every issue `tasks-to-issues` files goes onto Project 1,
# whether or not `--deliverable` names a value for it.
board_add_only() {
  # See board_set_status's comment: `owner` must be its own statement, after `repo` is bound.
  local repo="$1" number="$2" url="$3" items item owner
  owner="${repo%%/*}"
  board_require_write || return 1
  board_resolve "$owner" || return 1
  items="$(board_items "$owner")" || return 1
  item="$(board_item_id "$items" "$repo" "$number")"
  [ -n "$item" ] || run gh project item-add "$board_number" --owner "$owner" --url "$url"
}

# board_set_deliverable <repo> <number> <url> <name> — board_add_only, plus the `Deliverable` text
# field (§5.7: a cross-repo deliverable has no native milestone, so this is its stand-in).
board_set_deliverable() {
  # See board_set_status's comment: `owner` must be its own statement, after `repo` is bound.
  local repo="$1" number="$2" url="$3" name="$4" items item fields fid owner
  owner="${repo%%/*}"
  board_require_write || return 1
  board_resolve "$owner" || return 1
  items="$(board_items "$owner")" || return 1
  item="$(board_item_id "$items" "$repo" "$number")"
  if [ -z "$item" ]; then
    run gh project item-add "$board_number" --owner "$owner" --url "$url" || return 1
    [ "$dry" -eq 0 ] || { echo "  #$number's Deliverable is set on the real run, once it has an item id"; return 0; }
    items="$(board_items "$owner")" || return 1
    item="$(board_item_id "$items" "$repo" "$number")"
    [ -n "$item" ] || { echo "issues.sh: #$number is still not on the board after adding it" >&2; return 1; }
  fi
  fields="$(gh project field-list "$board_number" --owner "$owner" --format json --limit 100 </dev/null)"
  fid="$(board_text_field_id "$fields" Deliverable)"
  [ -n "$fid" ] || { echo "issues.sh: the board has no \"Deliverable\" field — run \`scripts/issues.sh board setup\` first." >&2; return 1; }
  run gh project item-edit --id "$item" --project-id "$board_id" --field-id "$fid" --text "$name"
}

# task_ref <title> -> "<mip-digits> <task-number>" for a `NNNN-Tk: …` title (§5.5's shape), else "".
task_ref() {
  sed -n 's/^\([0-9]\{4\}\)-T\([0-9]\{1,\}\):.*/\1 \2/p' <<<"$1"
}

# tasks_slug_of <tasks.md content> <task-number> -> that row's `slug` cell. Pure — the self-test
# feeds it a fixture instead of a real docs/MIPs/MIP-NNNN.tasks.md, which won't exist once this
# script moves to marola-devkit (MIP-0070 §5.6).
tasks_slug_of() {
  awk -F'|' -v k="$2" '
    /^\|/ {
      # The `#` cell is a markdown link whose URL also holds digits, so take the first run of
      # digits in the cell, not every digit in it.
      num = $2; sub(/^[^0-9]*/, "", num); sub(/[^0-9].*$/, "", num)
      if (num != "" && num == k) { slug = $3; gsub(/^[ \t]+|[ \t]+$/, "", slug); print slug; exit }
    }' <<<"$1"
}

# tasks_slug <mip-digits> <task-number> -> that row's `slug` cell in docs/MIPs/MIP-NNNN.tasks.md.
# The branch is `mip-NNNN/<k>-<slug>` and the slug exists only in that file, so reading it is what
# makes the printed line something to paste rather than something to go and look up.
tasks_slug() {
  local content
  content="$(cd "$root" && resolve_mip_file "MIP-$1" tasks)" || return 0
  [ -n "$content" ] || return 0
  tasks_slug_of "$content" "$2"
}

# stack_line_of <tasks.md content, or "" when none was found> <title> -> the `scripts/stack.sh
# start` line for a MIP task issue, else "". Pure — the self-test feeds it a fixture.
stack_line_of() {
  local content="$1" ref mip k slug
  ref="$(task_ref "$2")"
  [ -n "$ref" ] || return 0
  mip="${ref%% *}"; k="${ref##* }"
  slug=""
  [ -z "$content" ] || slug="$(tasks_slug_of "$content" "$k")"
  [ -n "$slug" ] || slug="<slug>"
  printf 'scripts/stack.sh start MIP-%s %s %s\n' "$mip" "$k" "$slug"
}

# stack_line <title> -> stack_line_of, with the tasks file resolved for real (§5.6).
stack_line() {
  local ref mip content=""
  ref="$(task_ref "$1")"
  if [ -n "$ref" ]; then
    mip="${ref%% *}"
    content="$(cd "$root" && resolve_mip_file "MIP-$mip" tasks)" || content=""
  fi
  stack_line_of "$content" "$1"
}

cmd_claim() {
  [ $# -eq 1 ] || { echo "issues.sh claim: expects <issue>" >&2; usage >&2; exit 1; }
  require_gh
  resolve_nwo
  local repo n ref
  IFS=$'\t' read -r repo n < <(parse_issue_ref "$1") || exit 1
  ref="$(ref_display "$repo" "$n")"

  local payload state labels assignees me url title line
  payload="$(issue_payload "$n" "$repo")" || exit 1
  # For the payload-is-really-#n and not-a-pull-request guards; `claim` has no use for the id.
  issue_id_of "$payload" "$n" >/dev/null || exit 1
  state="$(jq -r '.state // ""' <<<"$payload")"
  labels="$(jq -r '(.labels // [])[].name' <<<"$payload")"
  assignees="$(jq -r '(.assignees // [])[].login' <<<"$payload")"
  url="$(jq -r '.html_url // ""' <<<"$payload")"
  title="$(jq -r '.title // ""' <<<"$payload")"

  [ "$state" = open ] || { echo "issues.sh claim: $ref is $state, not open" >&2; exit 1; }
  grep -qx 'agent-ready' <<<"$labels" || {
    echo "issues.sh claim: $ref is not \`agent-ready\` — run \`just issue-ready $n\` and fix the rule it names (MIP-0063 §5.4)." >&2
    exit 1
  }
  me="$(gh api user </dev/null --jq .login)"
  if [ -n "$assignees" ] && ! grep -qx "$me" <<<"$assignees"; then
    echo "issues.sh claim: $ref is already assigned to ${assignees//$'\n'/, } — claiming it would take it off them." >&2
    exit 1
  fi

  # The label above is the cheap gate an agent in a jail can read; `ready` is the authority. §8:
  # an issue can be labelled `agent-ready` by hand without ever passing the check, so claiming
  # re-runs the five rules rather than trusting the label that stands for them — and `ready`
  # takes the label back off when they no longer hold. The original arg, not "$repo $n": `ready`
  # re-parses it itself, so a foreign-repo claim re-checks the same repo, not always $nwo.
  cmd_ready "$1" || {
    echo "issues.sh claim: $ref no longer passes the Definition of Ready (above) — not claimable." >&2
    exit 1
  }

  # One call, not two: an assignment that lands and a label removal that does not would leave the
  # issue claimed *and* still in the agent queue, which is exactly the drift §8 names as this
  # design's soft spot.
  run gh issue edit --repo "$repo" "$n" --add-assignee "$me" --remove-label agent-ready || exit 1
  echo "claimed $ref as @$me; \`agent-ready\` dropped$(dry_tag)"

  # Best-effort on purpose, and the one place in this file where a failed mutation is not fatal:
  # the assignment and the label are already applied, so exiting nonzero here would tell the
  # caller nothing happened. `board sync` is the command that fails outright without the scope.
  board_set_status "$repo" "$n" "$url" "In progress" \
    || echo "  board Status not set (above) — the assignee and the label are applied; §5.2's other half is still the board's" >&2

  # Not under --dry-run: the branch command is for an issue that is now assigned and out of the
  # agent queue, and neither happened.
  if [ "$dry" -eq 0 ]; then
    line="$(stack_line "$title")"
    if [ -n "$line" ]; then printf 'next:\n  %s\n' "$line"
    else echo "next: $ref is not a MIP task row, so there is no stack line — branch from main as usual"
    fi
  fi
}

# mip_reference_of <mip-digits> <path> -> the milestone description line. Pure — the self-test
# feeds it a fixture path instead of resolving a real docs/MIPs/MIP-NNNN-*.md.
mip_reference_of() {
  printf 'Design: MIP-%s — %s\n' "$1" "$2"
}

# mip_reference <MIP-NNNN> -> the milestone description that points at the MIP.
# A MIP number with no file is refused: a typo would otherwise leave a milestone whose only piece
# of provenance is a dangling reference.
mip_reference() {
  local n="${1#MIP-}" source path
  case "$n" in
    [0-9][0-9][0-9][0-9]) ;;
    *) echo "issues.sh milestone new: --mip wants MIP-NNNN, got \"$1\"" >&2; return 1 ;;
  esac
  IFS=$'\t' read -r source path < <(cd "$root" && resolve_mip_path "MIP-$n" doc) || {
    echo "issues.sh milestone new: no docs/MIPs/MIP-$n-*.md found locally or via \$MAROLA_UMBRELLA — is MIP-$n written?" >&2
    return 1
  }
  mip_reference_of "$n" "$path"
}

cmd_milestone_new() {
  [ $# -ge 1 ] || { echo "issues.sh milestone new: expects \"<name>\" [--mip MIP-NNNN]" >&2; usage >&2; exit 1; }
  local name="$1" mip="" desc="" existing
  local -a extra=()
  shift
  while [ $# -gt 0 ]; do
    case "$1" in
      --mip)
        [ $# -ge 2 ] || { echo "issues.sh milestone new: --mip needs MIP-NNNN" >&2; exit 1; }
        mip="$2"; shift 2 ;;
      *) echo "issues.sh milestone new: unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
  done
  [ -n "$name" ] || { echo "issues.sh milestone new: the name is empty" >&2; exit 1; }
  if [ -n "$mip" ]; then
    desc="$(mip_reference "$mip")" || exit 1
    extra=(-f "description=$desc")
  fi

  require_gh
  resolve_nwo
  existing="$(milestone_number "$name")"
  [ -z "$existing" ] || { echo "milestone \"$name\" already exists: #$existing"; return 0; }
  run gh api --method POST "repos/$nwo/milestones" -f "title=$name" ${extra[@]+"${extra[@]}"}
}

cmd_board_sync() {
  [ $# -eq 0 ] || { echo "issues.sh board sync: takes no arguments" >&2; usage >&2; exit 1; }
  require_gh
  resolve_nwo
  board_require_write || exit 1
  board_resolve || exit 1

  local owner="${nwo%%/*}" limit=300 fields issues items plan closed_items closed_plan n_open
  issues="$(gh issue list --repo "$nwo" --state open --limit "$limit" --json number,url,assignees,labels </dev/null)"
  n_open="$(jq 'length' <<<"$issues")"
  # Same page-limit trap as `queue`: past the limit gh stops silently, and the issues it did not
  # return read as already on the board.
  if [ "$n_open" -ge "$limit" ]; then
    echo "issues.sh: the repo has at least $limit open issues, this script's page limit — raise it before trusting this sync." >&2
    exit 1
  fi
  fields="$(gh project field-list "$board_number" --owner "$owner" --format json --limit 100 </dev/null)"
  items="$(board_items "$owner")" || exit 1
  plan="$(board_plan "$items" "$issues" "$nwo")"
  closed_items="$(board_items "$owner" "is:closed")" || exit 1
  closed_plan="$(board_closed_plan "$closed_items" "$issues" "$nwo")"
  if [ -z "$plan" ] && [ -z "$closed_plan" ]; then
    echo "board: in sync ($n_open open issues)"; return 0
  fi

  local action key status number rc=0 added=0 set_n=0 adopted=0 closed=0 skipped=0 failed=0 pair fid oid missing_opts=""
  while IFS=$'\t' read -r action key status number; do
    [ "$action" = add ] || continue
    rc=0
    run gh project item-add "$board_number" --owner "$owner" --url "$key" || rc=$?
    if [ "$rc" -eq 0 ]; then added=$((added + 1)); else failed=$((failed + 1)); fi
  done <<<"$plan"

  # A freshly added item has no id yet, so the Status pass works off a re-read rather than the
  # plan that produced the adds.
  if [ "$added" -gt 0 ] && [ "$dry" -eq 0 ]; then
    items="$(board_items "$owner")" || exit 1
    plan="$(board_plan "$items" "$issues" "$nwo")"
  fi

  while IFS=$'\t' read -r action key status number; do
    [ "$action" = set ] || [ "$action" = adopt ] || continue
    pair="$(board_status_option "$fields" "$status")"
    if [ -z "$pair" ]; then
      # Collected and reported once at the end, not per issue: on a board that has not had
      # `board setup` run yet this is every issue in the repo saying the same thing. It is a
      # missing prerequisite, not a failed write, and it is counted as its own thing.
      missing_opts="$missing_opts$status"$'\n'
      skipped=$((skipped + 1)); continue
    fi
    fid="${pair%%$'\t'*}"; oid="${pair##*$'\t'}"
    # Named per card, not just counted: the first real run moves every issue off the auto-add
    # default at once, and that is a lot of cards to discover after the fact.
    if [ "$action" = adopt ]; then echo "  #$number  $board_autoadd_status → $status"
    else echo "  #$number  (no Status) → $status"; fi
    rc=0
    run gh project item-edit --id "$key" --project-id "$board_id" --field-id "$fid" --single-select-option-id "$oid" || rc=$?
    if [ "$rc" -ne 0 ]; then failed=$((failed + 1))
    elif [ "$action" = adopt ]; then adopted=$((adopted + 1))
    else set_n=$((set_n + 1)); fi
  done <<<"$plan"

  if [ -n "$closed_plan" ]; then
    pair="$(board_status_option "$fields" "Done")"
    if [ -z "$pair" ]; then
      missing_opts="$missing_opts""Done"$'\n'
      while IFS=$'\t' read -r key status number; do
        [ -n "$key" ] || continue
        skipped=$((skipped + 1))
      done <<<"$closed_plan"
    else
      fid="${pair%%$'\t'*}"; oid="${pair##*$'\t'}"
      while IFS=$'\t' read -r key status number; do
        [ -n "$key" ] || continue
        echo "  #$number  $status → Done"
        rc=0
        run gh project item-edit --id "$key" --project-id "$board_id" --field-id "$fid" --single-select-option-id "$oid" || rc=$?
        if [ "$rc" -ne 0 ]; then failed=$((failed + 1)); else closed=$((closed + 1)); fi
      done <<<"$closed_plan"
    fi
  fi

  [ "$dry" -eq 0 ] || [ "$added" -eq 0 ] \
    || echo "  the Status of the $added issue(s) added above is set on the real run, once they have item ids"
  [ "$skipped" -eq 0 ] \
    || board_status_missing "$(sort -u <<<"$missing_opts" | grep . | sed 's/.*/"&"/' | paste -sd', ' -)" "$skipped"
  echo "board: $added added, $set_n set (no Status), $adopted adopted from $board_autoadd_status, $closed closed to Done, $skipped skipped, $failed failed ($n_open open issues)$(dry_tag)"
  [ "$failed" -eq 0 ] && [ "$skipped" -eq 0 ] || exit 1
}

# §5.2's Status values, in its order, `name:COLOR:description`. `Backlog` is not among them and is
# deliberately not removed: `updateProjectV2Field` takes the **complete** option list, so an option
# left out of it is deleted — and with it every item's Status. Four of these six already exist on
# the live board; only Triage and Spec are added.
board_status_wanted() {
  cat <<'EOF'
Triage:GRAY:Filed, not yet specified or sized (MIP-0063 §5.2)
Spec:PINK:Being specified — the story body or its MIP is still being written
Ready:BLUE:Passes the Definition of Ready
In progress:YELLOW:Claimed and being worked on
In review:PURPLE:A PR is open against it
Done:ORANGE:Merged or closed
EOF
}

# §5.2's four views: name, layout, filter. Two of that section's asks stay UI actions (introspected
# 2026-09-28): **Agent queue**'s sort, because `sortByFields` is readable but neither view input
# type carries a sort; and **Now**'s milestone filter, because no script knows which milestone is
# "currently being pushed" — hence the empty filter below.
board_views_wanted() {
  printf '%s\t%s\t%s\n' \
    "Triage"            TABLE_LAYOUT 'status:"Triage"' \
    "Now"               BOARD_LAYOUT '' \
    "Agent queue"       TABLE_LAYOUT 'label:"agent-ready"' \
    "Good first issues" TABLE_LAYOUT 'label:"good first issue"'
}

# status_options_plan <existing-options-json> <wanted> -> the complete option list to send.
# Every existing option is kept, with its id and its current colour and description; the missing
# ones are appended. Appended, not interleaved, because reordering is cosmetic and rewriting an
# option that is already there is not.
status_options_plan() {
  jq -n --argjson have "$1" --arg wanted "$2" '
    ($wanted | split("\n") | map(select(length > 0) | split(":")
      | {name: .[0], color: .[1], description: (.[2:] | join(":"))})) as $w
    | ($have | map(.name)) as $names
    | ($have | map({id, name, color, description} | if .id then . else del(.id) end))
      + ($w | map(select(.name as $n | $names | index($n) | not)) | map({name, color, description}))'
}

# board_views_plan <existing-views-json> <wanted-tsv> -> one TSV action per line. No emitted
# field is ever empty: `read` with IFS=tab collapses adjacent tabs, so an empty column in the
# middle silently shifts every later one into the wrong variable.
#   create  <name> <layout>
#   filter  <view-id> <filter> <name>   — the view is there, unfiltered
#   differs <name> <current> <wanted>   — reported, never applied
# `filter` is what makes the two-phase create self-healing. A view is created unfiltered and
# filtered by a second mutation (§5.2: `CreateProjectV2ViewInput` has no `filter`), so if that
# second call fails, or the process dies between them, the view reads back with none — which is
# this script's own unfinished work, not a maintainer's choice, and the next run finishes it.
# A *different* filter is a maintainer's and is only reported. A view §5.2 deliberately leaves
# unfiltered (Now) is never reported at all, whatever a human has since put on it.
board_views_plan() {
  jq -rn --argjson have "$1" --arg wanted "$2" '
    ($have | map({key: (.name | ascii_downcase), value: .}) | from_entries) as $by
    | $wanted | split("\n") | map(select(length > 0))[] | split("\t") as $w
    | $by[$w[0] | ascii_downcase] as $v
    | if $v == null then ["create", $w[0], $w[1]]
      elif $w[2] == "" then empty
      elif ($v.filter // "") == "" then ["filter", $v.id, $w[2], $w[0]]
      elif $v.filter != $w[2] then ["differs", $w[0], $v.filter, $w[2]]
      else empty end
    | @tsv'
}

# board_view_filter <view-id> <filter> — the second half of the two-phase create.
board_view_filter() {
  run gh api graphql -f v="$1" -f f="$2" -f query='
    mutation($v: ID!, $f: String!) { updateProjectV2View(input: {viewId: $v, filter: $f}) { projectV2View { id } } }'
}

# The two GraphQL reads `gh project` does not cover: field-list gives an option's id and name but
# not its colour or description, and there is no `gh project view-list` at all. Both are written
# against an **organization** owner, since §5.2's board moved to marola-dev with the repo; a
# user-owned project needs `user(login:)` instead, and says so rather than failing inside jq.
board_graphql_node() {   # <json> <jq-path> <owner> <what> -> the node, or a named error
  local node
  node="$(jq -c "$2 // empty" <<<"$1")"
  [ -n "$node" ] || {
    echo "issues.sh: could not read the board's $4 under the organization \"$3\" — this script queries organization(login:); a user-owned project needs user(login:) (MIP-0063 §5.2)." >&2
    return 1
  }
  printf '%s\n' "$node"
}

board_status_field_json() {
  local raw
  raw="$(gh api graphql </dev/null -F n="$board_number" -f o="$1" -f query='
    query($o: String!, $n: Int!) { organization(login: $o) { projectV2(number: $n) {
      field(name: "Status") { ... on ProjectV2SingleSelectField { id options { id name color description } } } } } }')"
  board_graphql_node "$raw" '.data.organization.projectV2.field' "$1" "Status field"
}

board_views_json() {
  local limit=50 raw nodes n
  raw="$(gh api graphql </dev/null -F n="$board_number" -F first="$limit" -f o="$1" -f query='
    query($o: String!, $n: Int!, $first: Int!) { organization(login: $o) { projectV2(number: $n) {
      views(first: $first) { nodes { id name filter } } } } }')"
  nodes="$(board_graphql_node "$raw" '.data.organization.projectV2.views.nodes' "$1" "views")" || return 1
  n="$(jq 'length' <<<"$nodes")"
  [ "$n" -lt "$limit" ] || {
    echo "issues.sh: the board has at least $limit views, this script's page limit — raise it before trusting the plan." >&2
    return 1
  }
  printf '%s\n' "$nodes"
}

cmd_board_setup() {
  [ $# -eq 0 ] || { echo "issues.sh board setup: takes no arguments" >&2; usage >&2; exit 1; }
  require_gh
  resolve_nwo
  board_require_write || exit 1
  board_resolve || exit 1

  local owner="${nwo%%/*}" field_json fid have plan_json payload body n_have n_plan rc=0 failed=0
  field_json="$(board_status_field_json "$owner")" || exit 1
  fid="$(jq -r '.id // ""' <<<"$field_json")"
  [ -n "$fid" ] || { echo "issues.sh board setup: the board's Status is not a single-select field (MIP-0063 §5.2)" >&2; exit 1; }
  have="$(jq '[.options[]? | {id, name, color, description}]' <<<"$field_json")"
  plan_json="$(status_options_plan "$have" "$(board_status_wanted)")"
  n_have="$(jq 'length' <<<"$have")"
  n_plan="$(jq 'length' <<<"$plan_json")"
  if [ "$n_plan" -eq "$n_have" ]; then
    echo "Status: all of §5.2's options are there ($n_have on the field)"
  else
    echo "Status: adding $(jq -r '.[] | select(has("id") | not) | .name' <<<"$plan_json" | paste -sd, -), keeping the $n_have already there"
    # --input, not -f: the option list is a JSON array and `gh api -f` would send it as a string.
    payload="$(jq -n --argjson o "$plan_json" --arg f "$fid" '{
      query: "mutation($f: ID!, $options: [ProjectV2SingleSelectFieldOptionInput!]) { updateProjectV2Field(input: {fieldId: $f, singleSelectOptions: $options}) { projectV2Field { __typename } } }",
      variables: {f: $f, options: $o}}')"
    # Printed, not just pointed at. This mutation replaces the field's whole option list, so what
    # is *in* the payload is the entire question — and `run` can only echo `--input <path>` for a
    # temp file that is deleted a line later, which is unreadable exactly when it matters most.
    jq . <<<"$payload" | sed 's/^/  /'
    body="$(mktemp)"
    printf '%s\n' "$payload" > "$body"
    run gh api graphql --input "$body" || rc=$?
    rm -f "$body"
    [ "$rc" -eq 0 ] || failed=$((failed + 1))
  fi

  local views wanted plan action f2 f3 f4 created=0 filtered=0
  wanted="$(board_views_wanted)"
  views="$(board_views_json "$owner")" || exit 1
  plan="$(board_views_plan "$views" "$wanted")"
  if [ -z "$plan" ]; then
    echo "views: §5.2's four are all there"
  else
    while IFS=$'\t' read -r action f2 f3 f4; do
      case "$action" in
        create)
          rc=0
          run gh api graphql -f p="$board_id" -f n="$f2" -f l="$f3" -f query='
            mutation($p: ID!, $n: String!, $l: ProjectV2ViewLayout!) {
              createProjectV2View(input: {projectId: $p, name: $n, layout: $l}) { projectV2View { id } } }' || rc=$?
          if [ "$rc" -eq 0 ]; then created=$((created + 1)); else failed=$((failed + 1)); fi ;;
        filter)
          rc=0
          echo "  view \"$f4\" has no filter — applying §5.2's [$f3]"
          board_view_filter "$f2" "$f3" || rc=$?
          if [ "$rc" -eq 0 ]; then filtered=$((filtered + 1)); else failed=$((failed + 1)); fi ;;
        differs)
          echo "  view \"$f2\" has the filter [$f3] — left alone; §5.2 wants [$f4]" >&2 ;;
      esac
    done <<<"$plan"

    # A view has no id until it exists, so the filters for what was just created come from a
    # re-read — which re-plans into the same `filter` action that heals a half-finished create
    # from an earlier run. One path, not two.
    if [ "$created" -gt 0 ] && [ "$dry" -eq 0 ]; then
      views="$(board_views_json "$owner")" || exit 1
      while IFS=$'\t' read -r action f2 f3 f4; do
        [ "$action" = filter ] || continue
        rc=0
        board_view_filter "$f2" "$f3" || rc=$?
        if [ "$rc" -eq 0 ]; then filtered=$((filtered + 1)); else failed=$((failed + 1)); fi
      done <<<"$(board_views_plan "$views" "$wanted")"
    elif [ "$created" -gt 0 ]; then
      echo "  each new view's filter is a second updateProjectV2View on the real run, once it has an id"
    fi
    echo "views: $created created, $filtered filtered$(dry_tag)"
  fi

  local text_fields deliverable_id
  text_fields="$(gh project field-list "$board_number" --owner "$owner" --format json --limit 100 </dev/null)"
  deliverable_id="$(board_text_field_id "$text_fields" Deliverable)"
  if [ -n "$deliverable_id" ]; then
    echo "Deliverable: already a field on the board"
  else
    rc=0
    run gh project field-create "$board_number" --owner "$owner" --name Deliverable --data-type TEXT || rc=$?
    if [ "$rc" -eq 0 ]; then echo "Deliverable: field created$(dry_tag)"; else failed=$((failed + 1)); fi
  fi

  echo "board setup: $failed failed$(dry_tag)"
  [ "$failed" -eq 0 ] || exit 1
}

# phase_titles_of <PHASES.md section text, "# Development phases" heading through the next "# ">
# -> one "N<TAB>Phase N — <name><TAB>done|open" per line. Pure, so the self-test feeds it a
# fixture instead of the real docs/PHASES.md, which won't exist once this script moves to
# marola-devkit.
phase_titles_of() {
  sed -n 's/^[0-9]\{1,\}\. \*\*Phase \([0-9]\): \(.*\)\.\*\*.*/\1\t\2/p' <<<"$1" \
    | awk -F'\t' '{ done_ = ($2 ~ /\(done/) ? "done" : "open"
                    name = $2; sub(/ *\([^)]*\)$/, "", name)
                    printf "%s\tPhase %s — %s\t%s\n", $1, $1, name, done_ }'
}

# phase_titles -> phase_titles_of, read from docs/PHASES.md rather than copied here (MIP-0070
# §5.6: phases are an org rule, so the umbrella keeps this file after the app leaves). The gate
# issues are named after the phases and deduped on that name, so a second copy of the names is a
# second thing to keep true; the self-test pins the five it must yield, which turns a rename in
# PHASES.md into a failed build rather than a sixth gate issue.
phase_titles() {
  local section
  section="$(awk '/^# Development phases/ { s = 1; next } s && /^# / { exit } s' "$root/docs/PHASES.md")"
  phase_titles_of "$section"
}

cmd_board_gates() {
  [ $# -eq 0 ] || { echo "issues.sh board gates: takes no arguments" >&2; usage >&2; exit 1; }
  require_gh
  resolve_nwo

  local titles n_titles
  titles="$(phase_titles)"
  n_titles="$(grep -c . <<<"$titles" || true)"
  [ "$n_titles" -eq 5 ] || {
    echo "issues.sh board gates: docs/PHASES.md yielded $n_titles phase titles, not 5 — refusing to file gate issues from a file this script no longer parses." >&2
    exit 1
  }

  local repo_labels existing gate_limit=300 n_seen
  repo_labels="$(gh label list --repo "$nwo" --limit 500 --json name </dev/null --jq '.[].name')"
  # state=all: a gate that was filed and closed must not be filed again.
  existing="$(gh issue list --repo "$nwo" --state all --limit "$gate_limit" --json title </dev/null --jq '.[].title')"
  n_seen="$(grep -c . <<<"$existing" || true)"
  # Past the limit gh stops silently, and an unseen gate reads as "never filed" — so a re-run
  # files all five again.
  [ "$n_seen" -lt "$gate_limit" ] || {
    echo "issues.sh: the repo has at least $gate_limit issues, this script's page limit — raise it before trusting this dedup." >&2
    exit 1
  }

  local num title marker body rc=0 created=0 skipped=0 failed=0
  while IFS=$'\t' read -r num title marker; do
    [ -n "$title" ] || continue
    # `gh issue create --label` fails on a label the repo does not have, and .github/labels.yml is
    # where these are defined (§5.2), so name the command that creates them rather than the error.
    # --dry-run previews anyway: the point of the preview is to read it *before* applying the
    # prerequisites, and the real run still refuses.
    grep -qx "phase/$num" <<<"$repo_labels" || {
      echo "issues.sh board gates: the repo has no \`phase/$num\` label — run \`just labels-sync\` first; it is in .github/labels.yml." >&2
      [ "$dry" -eq 1 ] || exit 1
    }
    if grep -qxF "$title" <<<"$existing"; then
      echo "gate already filed: $title"; skipped=$((skipped + 1)); continue
    fi
    body="$(printf 'Phase gate for phase %s of `docs/PHASES.md`. It holds no work: every `phase/%s` issue is `blocked by` it, so closing this one unblocks the phase at once (MIP-0063 §5.3).\n\nWire an issue to it with `scripts/issues.sh deps add <issue> --blocked-by <this issue>`.\n' "$num" "$num")"
    rc=0
    run gh issue create --repo "$nwo" --title "$title" --label "phase/$num" --body "$body" || rc=$?
    if [ "$rc" -eq 0 ]; then created=$((created + 1)); else failed=$((failed + 1)); fi
  done <<<"$titles"

  # A phase PHASES.md already marks done gets a gate that is closed, not open: an open gate for a
  # phase that finished before this script existed would block its issues forever. The number comes
  # from a re-read because `run` swallows the create's output — that is the channel it echoes on.
  if [ "$dry" -eq 0 ]; then
    local open_gates n_gate
    open_gates="$(gh issue list --repo "$nwo" --state open --limit "$gate_limit" --json number,title </dev/null \
      --jq '.[] | "\(.number)\t\(.title)"')"
    n_seen="$(grep -c . <<<"$open_gates" || true)"
    [ "$n_seen" -lt "$gate_limit" ] || {
      echo "issues.sh: the repo has at least $gate_limit open issues, this script's page limit — the gate to close may be past it." >&2
      exit 1
    }
    while IFS=$'\t' read -r num title marker; do
      [ "$marker" = done ] || continue
      n_gate="$(awk -F'\t' -v t="$title" '$2 == t { print $1; exit }' <<<"$open_gates")"
      [ -n "$n_gate" ] || continue
      run gh issue close --repo "$nwo" "$n_gate" --reason completed || failed=$((failed + 1))
    done <<<"$titles"
  fi

  echo "gates: $created filed, $skipped already there, $failed failed$(dry_tag)"
  [ "$failed" -eq 0 ] || exit 1
}

dry=0
# A summary line counts what *would* happen under --dry-run, and the past tense reads as if it had.
dry_tag() { [ "$dry" -eq 0 ] || printf ' (--dry-run: nothing was written)'; }

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

# write_gh_stub <dir> — the `gh` the command tests run against, installed at <dir>/bin, reading
# <dir>'s fixtures and logging every mutating call to $STUB_LOG. One stub, not one per section:
# four copies of this core drifted apart, and what a section needs that another does not is a
# fixture — `stateful` makes writes persist so run 2 sees run 1's work, `fail_id` names an issue
# whose lookup 404s, and `bad_url` makes a create land but print no issue number.
write_gh_stub() {
  mkdir -p "$1/bin"
  { echo "#!$BASH"; cat <<'STUB'
cat >/dev/null          # a gh that reads stdin; `gh api --input -` really does
jq_expr=""; path=""; prev=""; title=""; arg=""; method=""; issue_id=""; file=""; n=""
create_repo=""; milestone_arg=""; item_url=""
for a in "$@"; do
  case "$prev" in
    --jq) jq_expr="$a" ;; --title) title="$a" ;; --method) method="$a" ;;
    --repo) create_repo="$a" ;; --milestone) milestone_arg="$a" ;; --url) item_url="$a" ;;
  esac
  case "$a" in
    repos/*) path="$a" ;;
    issue_id=*) issue_id="${a#issue_id=}" ;;
    sub_issue_id=*) issue_id="${a#sub_issue_id=}" ;;
  esac
  prev="$a"
done
log() { [ -z "${STUB_LOG-}" ] || printf '%s\n' "$*" >> "$STUB_LOG"; }
# repo_of <number> -> the repo §5.7's multi-repo tests recorded it under, via $STUB_DIR/repo-of.json
# (a fixture writes an entry for a pre-seeded number; a stateful create writes one for a fresh id).
# Every single-repo test never touches this file, so it always falls back to marola-dev/marola.
repo_of() { jq -r --argjson n "$1" '.[$n | tostring] // "marola-dev/marola"' "$STUB_DIR/repo-of.json" 2>/dev/null || echo "marola-dev/marola"; }
remember_repo() {
  local f="$STUB_DIR/repo-of.json"
  [ -f "$f" ] || echo '{}' > "$f"
  jq --argjson n "$1" --arg r "$2" '.[$n | tostring] = $r' "$f" > "$STUB_DIR/w" && mv "$STUB_DIR/w" "$f"
}
# §5.7's existence probe: exactly `api repos/<owner>/<name>`, nothing after it. STUB_DRAIN_REPO_VIEW
# models a `gh` that reads all of its stdin, so a probe missing `</dev/null` starves the loop.
if [ "$#" -eq 2 ] && [ "$1" = api ] && [[ "$2" =~ ^repos/[^/]+/[^/]+$ ]]; then
  probe_target="${2#repos/}"
  [ -z "${STUB_DRAIN_REPO_VIEW-}" ] || cat >/dev/null
  printf '%s\n' "$probe_target" >> "$STUB_DIR/repo-view-log"
  grep -qxF "$probe_target" "${STUB_DIR}/repo-view-fail" 2>/dev/null && {
    echo "gh: Internal Server Error (HTTP 500)" >&2; exit 1
  }
  grep -qxF "$probe_target" "$STUB_DIR/existing-repos" 2>/dev/null && { echo '{}'; exit 0; }
  echo "gh: Not Found (HTTP 404)" >&2; exit 1
fi
case "$*" in
  *"auth status"*) echo auth >> "$STUB_DIR/authlog"; exit 0 ;;
  *"repo view"*)
    # A bare `repo view --json nameWithOwner` is resolve_nwo asking "what repo is this". The
    # existence probe is `gh api repos/<owner>/<name>`, handled above.
    view_target=""
    for a in "$@"; do case "$a" in */*) view_target="$a" ;; esac; done
    if [ -z "$view_target" ]; then echo "marola-dev/marola"; exit 0; fi
    grep -qxF "$view_target" "$STUB_DIR/existing-repos" 2>/dev/null && exit 0
    # What the real `gh repo view` prints for a missing repo: a GraphQL error, never "HTTP 404".
    echo "GraphQL: Could not resolve to a Repository with the name '$view_target'. (repository)" >&2; exit 1 ;;
  *"api -i user"*)
    # STUB_NOHDR models a fine-grained PAT: GitHub sends no X-OAuth-Scopes header at all.
    if [ -n "${STUB_NOHDR-}" ]; then printf 'HTTP/2.0 200 OK\r\nServer: github.com\r\n\r\n{}\n'
    else printf 'HTTP/2.0 200 OK\r\nX-Oauth-Scopes: repo, %s\r\n\r\n{}\n' "${STUB_SCOPES-}"; fi
    exit 0 ;;
  *"api user"*) echo "brunogbv"; exit 0 ;;
  *"search issues"*)
    file="$STUB_DIR/search-issues.json"
    # The real API has no milestone JSON field to filter on client-side, so the fixture carries one
    # anyway (a stub is not bound by the real response schema) and this filters server-side, the
    # way cmd_queue's own --milestone now works.
    if [ -n "$milestone_arg" ]; then
      jq --arg m "$milestone_arg" '[.[] | select(.milestone == $m)]' "$file" > "$STUB_DIR/.search-filtered.json"
      file="$STUB_DIR/.search-filtered.json"
    fi ;;
  *"issue create"*)
    log "$*"
    [ ! -f "$STUB_DIR/bad_url" ] || { echo "https://github.com/marola-dev/marola/issues/"; exit 0; }
    [ -f "$STUB_DIR/stateful" ] || exit 0
    target="$STUB_DIR/issues-all.$(printf '%s' "${create_repo:-marola-dev/marola}" | tr '/' '_').json"
    [ -f "$target" ] || target="$STUB_DIR/issues-all.json"
    n=$(( $(jq 'length' "$target") + 700 ))
    jq --argjson n "$n" --arg t "$title" '. + [{number:$n,title:$t}]' "$target" \
      > "$STUB_DIR/w" && mv "$STUB_DIR/w" "$target"
    remember_repo "$n" "${create_repo:-marola-dev/marola}"
    printf 'https://github.com/%s/issues/%s\n' "${create_repo:-marola-dev/marola}" "$n"; exit 0 ;;
  *"project item-add"*)
    log "$*"
    # Stateful only (§5.7's multi-repo tests): a re-read after this add must find the item, the
    # way board_set_status/board_add_only's own second `board_items` call expects the real API to.
    if [ -f "$STUB_DIR/stateful" ] && [ -n "$item_url" ]; then
      item_repo="${item_url#https://github.com/}"; item_repo="${item_repo%/issues/*}"
      item_num="${item_url##*/}"
      jq --arg id "I-$item_repo-$item_num" --arg repo "$item_repo" --argjson num "$item_num" \
        '.items += [{id:$id, content:{number:$num, repository:$repo}}]' "$STUB_DIR/items.json" \
        > "$STUB_DIR/w" && mv "$STUB_DIR/w" "$STUB_DIR/items.json"
    fi
    exit 0 ;;
  *"label create"*|*"label edit"*|*"label delete"* \
  |*"issue edit"*|*"issue close"*|*"project item-edit"*|*"project field-create"*) log "$*"; exit 0 ;;
  *"--input"*)
    for arg in "$@"; do case "$arg" in /*) file="$arg" ;; esac; done
    log "graphql-input $(jq -r '[.variables.options[].name] | join(",")' "$file")"; exit 0 ;;
  *createProjectV2View*)
    for arg in "$@"; do case "$arg" in n=*) title="${arg#n=}" ;; l=*) method="${arg#l=}" ;; esac; done
    log "create-view $title $method"; exit 0 ;;
  *updateProjectV2View*)
    for arg in "$@"; do case "$arg" in v=*) title="${arg#v=}" ;; f=*) method="${arg#f=}" ;; esac; done
    log "set-filter $title $method"; exit 0 ;;
  *"field(name:"*) file="$STUB_DIR/status-field${STUB_AFTER:+-after}.json" ;;
  *"views(first"*)
    # STUB_VIEWS pins the fixture; otherwise the re-read after a create has to see the new views,
    # the way the real API would.
    if [ -n "${STUB_VIEWS-}" ]; then file="$STUB_DIR/$STUB_VIEWS"
    elif grep -q '^create-view' "${STUB_LOG:-/dev/null}" 2>/dev/null; then file="$STUB_DIR/views-after.json"
    else file="$STUB_DIR/views.json"; fi ;;
  *"project list"*)       file="$STUB_DIR/projects.json" ;;
  *"project field-list"*) file="$STUB_DIR/${STUB_FIELDS:-fields.json}" ;;
  *"project item-list"*"--query"*)
                          file="$STUB_DIR/${STUB_CLOSED_ITEMS:-closed-items.json}" ;;
  *"project item-list"*)  file="$STUB_DIR/${STUB_ITEMS:-items.json}" ;;
  *"label list"*)         file="$STUB_DIR/labels.json" ;;
  *"issue list"*)
    repo_slug="$(printf '%s' "${create_repo:-marola-dev/marola}" | tr '/' '_')"
    case "$*" in
      *"--state all"*) file="$STUB_DIR/issues-all.$repo_slug.json"; [ -f "$file" ] || file="$STUB_DIR/issues-all.json" ;;
      *)               file="$STUB_DIR/issues-open.$repo_slug.json"; [ -f "$file" ] || file="$STUB_DIR/issues-open.json" ;;
    esac ;;
  *)
    case "$path" in
      */milestones*) file="$STUB_DIR/milestones.json" ;;
      */contents/docs/MIPs)
        file="$STUB_DIR/mip-list.json" ;;
      */contents/docs/MIPs/*)
        file="$STUB_DIR/mip-content/${path#*contents/docs/MIPs/}" ;;
      */sub_issues)
        n="${path%/sub_issues}"; n="${n##*/}"; file="$STUB_DIR/$n.subs.json"
        [ ! -f "$STUB_DIR/stateful" ] || [ -f "$file" ] || printf '[]\n' > "$file"
        if [ "$method" = POST ]; then
          child=$(( issue_id - 5600000000 ))
          log "sub $n <- $child"
          jq --argjson c "$child" --arg r "$(repo_of "$child")" \
            '. + [{number:$c, repository:{full_name:$r}}]' "$file" > "$STUB_DIR/w" && mv "$STUB_DIR/w" "$file"
          exit 0
        fi ;;
      */dependencies/blocked_by)
        n="${path%/dependencies/blocked_by}"; n="${n##*/}"; file="$STUB_DIR/$n.deps.json"
        [ ! -f "$STUB_DIR/stateful" ] || [ -f "$file" ] || printf '[]\n' > "$file"
        if [ "$method" = POST ]; then
          blocked_repo="${path#repos/}"; blocked_repo="${blocked_repo%%/issues/*}"
          child=$(( issue_id - 5600000000 ))
          blocker_repo="$(repo_of "$child")"
          # STUB_CROSS_UNSUPPORTED models §5.7's documented uncertainty: a cross-repo attempt
          # 4xxs, a same-repo one always succeeds — issues.sh must treat only the former as skipped.
          if [ -n "${STUB_CROSS_UNSUPPORTED-}" ] && [ "$blocked_repo" != "$blocker_repo" ]; then
            echo "gh: Unprocessable Entity (HTTP 422)" >&2; exit 1
          fi
          log "edge $n <- $child"
          jq --argjson b "$child" --arg r "$blocker_repo" \
            '. + [{number:$b,state:"closed",repository:{full_name:$r}}]' "$file" \
            > "$STUB_DIR/w" && mv "$STUB_DIR/w" "$file"
          exit 0
        fi ;;
      *)
        n="${path##*/}"
        [ "$n" != "$(cat "$STUB_DIR/fail_id" 2>/dev/null)" ] || { echo "gh: Not Found (HTTP 404)" >&2; exit 1; }
        file="$STUB_DIR/$n.issue.json"
        [ ! -f "$STUB_DIR/stateful" ] || [ -f "$file" ] \
          || printf '{"number":%s,"id":%d}\n' "$n" "$(( 5600000000 + n ))" > "$file" ;;
    esac ;;
esac
[ -f "$file" ] || { echo "stub: no fixture for ${path:-$*}" >&2; exit 1; }
if [ -n "$jq_expr" ]; then jq -r "$jq_expr" "$file"; else cat "$file"; fi
STUB
  } > "$1/bin/gh"
  chmod +x "$1/bin/gh"
  : > "$1/authlog"
}

self_test() {
  local failed=0 tmp got
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
  echo "-- labels sync ends on a summary, like every other subcommand --"
  local lbl_dir="$tmp/labels" lbl_out
  write_gh_stub "$lbl_dir"
  printf '[{"name":"area/conditions","color":"000000","description":"Live sea/weather/tide data"},
    {"name":"stale","color":"ffffff","description":"gone from the manifest"}]\n' > "$lbl_dir/labels.json"
  printf -- '- name: "area/conditions"\n  color: "1d76db"\n  description: "Live sea/weather/tide data"\n\n- name: "area/map-site"\n  color: "0e8a16"\n  description: "The static map site"\n' \
    > "$lbl_dir/m.yml"
  lbl_out="$(PATH="$lbl_dir/bin:$PATH" STUB_DIR="$lbl_dir" STUB_LOG="$lbl_dir/log" nwo="" \
    cmd_labels_sync --manifest "$lbl_dir/m.yml" 2>/dev/null)"
  check "an applied plan says what it applied, not nothing at all" "$(tail -1 <<<"$lbl_out")" \
    "labels: 2 of 2 actions applied, 1 orphaned (2 in the manifest)"
  dry=1
  lbl_out="$(PATH="$lbl_dir/bin:$PATH" STUB_DIR="$lbl_dir" STUB_LOG="$lbl_dir/log" nwo="" \
    cmd_labels_sync --manifest "$lbl_dir/m.yml" 2>/dev/null)"
  dry=0
  check "and --dry-run says it applied nothing" "$(tail -1 <<<"$lbl_out")" \
    "labels: 2 of 2 actions applied, 1 orphaned (2 in the manifest) (--dry-run: nothing was written)"

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
  local stdin_dir="$tmp/stdin"
  write_gh_stub "$stdin_dir"
  printf '{"number":415,"id":5601728372}\n' > "$stdin_dir/415.issue.json"
  printf '[{"number":414,"state":"closed","title":"stub"}]\n' > "$stdin_dir/415.deps.json"
  got="$(printf 'row-2\nrow-3\n' | { PATH="$stdin_dir/bin:$PATH" STUB_DIR="$stdin_dir" nwo="" cmd_deps_list 415 >/dev/null; cat; })"
  check "deps list leaves the caller's stdin untouched (auth status, repo view and the GET)" "$got" "row-2
row-3"
  got="$(printf 'row-2\nrow-3\n' | { PATH="$stdin_dir/bin:$PATH" STUB_DIR="$stdin_dir" nwo="marola-dev/marola" resolve_issue_id 415 >/dev/null; cat; })"
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
  local form_dir="$root/.github/ISSUE_TEMPLATE" form_parser
  form_parser="$tmp/parse_form.py"
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

  local form_doc form_labels form_rc f
  for f in bug_report.yml task.yml story.yml mip_proposal.yml config.yml; do
    if form_doc="$(python3 "$form_parser" "$form_dir/$f" 2>&1)"; then form_rc=ok; else form_rc=fail; fi
    check "$f parses" "$form_rc" "ok"
  done

  form_doc="$(python3 "$form_parser" "$form_dir/bug_report.yml" 2>/dev/null || echo '{}')"
  form_labels="$(jq -r '[(.body // [])[].attributes.label] | map(select(. != null)) | join("|")' <<<"$form_doc")"
  check "bug_report.yml field labels" "$form_labels" \
    "What happened|What you expected instead|How to reproduce|Backend|Failing test|Relevant logs or output"
  check "bug_report.yml's expected field renders as the DoR rule 1 heading for a bug" \
    "### $(jq -r '(.body // [])[] | select(.id == "expected") | .attributes.label' <<<"$form_doc")" \
    "### What you expected instead"
  check "bug_report.yml's failing-test field renders as the DoR rule 2 heading for a bug" \
    "### $(jq -r '(.body // [])[] | select(.id == "failing-test") | .attributes.label' <<<"$form_doc")" \
    "### Failing test"

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
    "$(jq -r '.contact_links[] | select(.name == "Ask a question") | .url' <<<"$form_doc")" \
    "https://github.com/marola-dev/marola/discussions"

  if [ -e "$form_dir/feature_request.yml" ]; then
    echo "FAILED: feature_request.yml still exists — story.yml was meant to replace it" >&2; failed=1
  else
    echo "ok: feature_request.yml is gone, replaced by story.yml"
  fi

  echo
  echo "-- the Definition of Ready: the five rules of §5.4 --"
  local dor_body dor_bug_body dor_labels dor_got
  dor_body="$(printf '### What\n\nx\n\n### Acceptance criteria\n\n- [ ] a\n\n### Named test\n\nFooSpec\n')"
  dor_labels="$(printf 'area/dev-tooling\nlayer/infra\nsize/M\n')"

  dor_case() {   # dor_case <label> <body> <labels> <blockers> <tier> <want-rc> <want-line>
    local out rc=0
    out="$(dor_rules "$2" "$3" "$4" "$5")" || rc=$?
    check "$1 (exit)" "$rc" "$6"
    case "$out" in
      *"$7"*) echo "ok: $1" ;;
      *) echo "FAILED: $1 — expected \"$7\" in:" >&2; sed 's/^/  /' <<<"$out" >&2; failed=1 ;;
    esac
  }

  dor_case "all five hold" "$dor_body" "$dor_labels" "" standard 0 "✓ 5. no open blocked-by dependency"
  dor_case "no acceptance criteria" "$(printf '### What\n\nx\n\n### Named test\n\nFooSpec\n')" \
    "$dor_labels" "" standard 1 '✗ 1. acceptance criteria — no "### Acceptance criteria" section in the body'
  dor_case "no named test" "$(printf '### Acceptance criteria\n\n- [ ] a\n')" \
    "$dor_labels" "" standard 1 '✗ 2. named test — no "### Named test" section in the body'
  dor_case "no area/* and no layer/*" "$dor_body" "$(printf 'size/M\n')" "" standard 1 \
    "✗ 3. area/* and layer/* not set"
  dor_case "no size/*" "$dor_body" "$(printf 'area/dev-tooling\nlayer/infra\n')" "" standard 1 \
    "✗ 4. size/* label missing"
  dor_case "an open blocked-by" "$dor_body" "$dor_labels" "#411" standard 1 \
    "✗ 5. blocked by #411 (still open)"
  dor_case "a field left blank is not an answered one" \
    "$(printf '### Acceptance criteria\n\n- [ ] a\n\n### Named test\n\n_No response_\n')" \
    "$dor_labels" "" standard 1 '✗ 2. named test — "### Named test" is empty'
  # GitHub serves issue bodies with CRLF line endings, so an exact heading match has to strip it.
  dor_case "a CRLF body still matches the headings" \
    "$(printf '### Acceptance criteria\r\n\r\n- [ ] a\r\n\r\n### Named test\r\n\r\nFooSpec\r\n')" \
    "$dor_labels" "" standard 0 "✓ 2. named test present"

  echo
  echo "-- the DoR is tier-aware (§5.4): a bug reads its own fields, a MIP proposal is not work --"
  check "dor_tier: mip wins, whatever else is set" "$(dor_tier "$(printf 'mip\nbug\n')")" "mip"
  check "dor_tier: bug" "$(dor_tier "$(printf 'bug\narea/safety\n')")" "bug"
  check "dor_tier: anything else" "$(dor_tier "$(printf 'enhancement\n')")" "standard"
  dor_bug_body="$(printf '### What happened\n\nx\n\n### What you expected instead\n\ny\n\n### Failing test\n\nFooSpec\n')"
  dor_case "a bug with a filled-in Failing test passes" "$dor_bug_body" "$dor_labels" "" bug 0 \
    "✓ 2. named test present"
  dor_case "a bug whose Failing test was left blank does not" \
    "$(printf '### What you expected instead\n\ny\n\n### Failing test\n\n_No response_\n')" \
    "$dor_labels" "" bug 1 '✗ 2. named test — "### Failing test" is empty'
  dor_case "the bug headings satisfy no other tier" "$dor_bug_body" "$dor_labels" "" standard 1 \
    '✗ 1. acceptance criteria — no "### Acceptance criteria" section in the body'
  # The contract the bug tier rests on, checked by construction rather than by two matching
  # literals: renaming a field in bug_report.yml has to fail here, not leave dor_rules' h1/h2
  # pointing at a heading that no longer renders while every literal in this file still agrees.
  local dor_form
  dor_form="$(python3 "$form_parser" "$form_dir/bug_report.yml" 2>/dev/null || echo '{}')"
  dor_case "a bug body built from bug_report.yml's own field labels passes" \
    "$(jq -r '[(.body // [])[] | select(.id == "expected" or .id == "failing-test")
              | "### " + .attributes.label, "answered"] | join("\n\n")' <<<"$dor_form")" \
    "$dor_labels" "" bug 0 "✓ 2. named test present"

  # §3's block: the substring checks above prove the failing rule is named, not that the other
  # four passed.
  check "the report is §3's shape, one line per rule" \
    "$(dor_rules "$dor_body" "$(printf 'area/dev-tooling\nlayer/infra\n')" "" standard || true)" \
    "  ✓ 1. acceptance criteria present
  ✓ 2. named test present
  ✓ 3. area/* and layer/* labels set
  ✗ 4. size/* label missing
  ✓ 5. no open blocked-by dependency"

  echo
  echo "-- ready writes the agent-ready label both ways, and queue partitions what is left --"
  local dor_dir="$tmp/dor" dor_log="$tmp/dor/edits.log"
  write_gh_stub "$dor_dir"

  cat > "$dor_dir/901.issue.json" <<'EOF'
{"number":901,"id":5600000901,
 "labels":[{"name":"area/map-site"},{"name":"layer/site"},{"name":"size/S"}],
 "body":"### What\n\nx\n\n### Acceptance criteria\n\n- [ ] a\n\n### Named test\n\nFooSpec\n"}
EOF
  for dor_got in 903 904; do sed "s/901/$dor_got/g" "$dor_dir/901.issue.json" > "$dor_dir/$dor_got.issue.json"; done
  cat > "$dor_dir/902.issue.json" <<'EOF'
{"number":902,"id":5600000902,
 "labels":[{"name":"agent-ready"},{"name":"area/dev-tooling"},{"name":"layer/infra"},{"name":"size/S"}],
 "body":"### What\n\nx\n\n### Acceptance criteria\n\n- [ ] a\n"}
EOF
  cat > "$dor_dir/905.issue.json" <<'EOF'
{"number":905,"id":5600000905,"labels":[{"name":"mip"}],"body":"### Motivation\n\nx\n"}
EOF
  cat > "$dor_dir/908.issue.json" <<'EOF'
{"number":908,"id":5600000908,"labels":[{"name":"mip"},{"name":"agent-ready"}],
 "body":"### Motivation\n\nx\n"}
EOF
  cat > "$dor_dir/906.issue.json" <<'EOF'
{"number":906,"id":5600000906,
 "labels":[{"name":"bug"},{"name":"area/safety"},{"name":"layer/core"},{"name":"size/S"}],
 "body":"### What happened\n\nx\n\n### What you expected instead\n\ny\n\n### Failing test\n\nSafetyFooterSpec\n"}
EOF
  cat > "$dor_dir/907.issue.json" <<'EOF'
{"number":907,"id":5600000907,
 "labels":[{"name":"agent-ready"},{"name":"area/dev-tooling"},{"name":"layer/infra"},{"name":"size/M"}],
 "body":"### Acceptance criteria\n\n- [ ] a\n\n### Named test\n\nFooSpec\n"}
EOF
  printf '[]\n' > "$dor_dir/901.deps.json"
  cp "$dor_dir/901.deps.json" "$dor_dir/902.deps.json"
  cp "$dor_dir/901.deps.json" "$dor_dir/906.deps.json"
  cp "$dor_dir/901.deps.json" "$dor_dir/907.deps.json"
  printf '[{"number":899,"state":"open"},{"number":898,"state":"closed"}]\n' > "$dor_dir/903.deps.json"
  printf '[{"number":898,"state":"closed"}]\n' > "$dor_dir/904.deps.json"

  dor_ready_case() {   # dor_ready_case <label> <issue> <want-rc> <want-line> <want-edit-call>
    local out rc=0
    : > "$dor_log"
    out="$(PATH="$dor_dir/bin:$PATH" STUB_DIR="$dor_dir" STUB_LOG="$dor_log" nwo="" cmd_ready "$2" 2>&1)" || rc=$?
    check "$1 (exit)" "$rc" "$3"
    case "$out" in
      *"$4"*) echo "ok: $1" ;;
      *) echo "FAILED: $1 — expected \"$4\" in:" >&2; sed 's/^/  /' <<<"$out" >&2; failed=1 ;;
    esac
    check "$1 (label call)" "$(cat "$dor_log")" "$5"
  }

  dor_ready_case "an all-pass issue gains agent-ready" 901 0 "✓ #901 is agent-ready" \
    "issue edit --repo marola-dev/marola 901 --add-label agent-ready"
  dor_ready_case "a previously-ready issue that regressed loses it" 902 1 'label `agent-ready` removed' \
    "issue edit --repo marola-dev/marola 902 --remove-label agent-ready"
  dor_ready_case "an open blocked-by blocks, and is named" 903 1 "✗ 5. blocked by #899 (still open)" ""
  dor_ready_case "a blocked-by that is closed does not block" 904 0 "✓ 5. no open blocked-by dependency" \
    "issue edit --repo marola-dev/marola 904 --add-label agent-ready"
  dor_ready_case "a MIP proposal is refused, and no dependency read is even made" 905 1 \
    "it is a MIP proposal, and the DoR does not apply" ""
  dor_ready_case "a hand-added agent-ready is taken off a MIP proposal too" 908 1 \
    'label `agent-ready` removed' "issue edit --repo marola-dev/marola 908 --remove-label agent-ready"
  dor_ready_case "a bug passes on its own two fields" 906 0 "✓ #906 is agent-ready" \
    "issue edit --repo marola-dev/marola 906 --add-label agent-ready"
  dor_ready_case "an already-ready issue is not re-labelled" 907 0 'label `agent-ready` already set' ""

  dor_got="$(printf 'row-2\nrow-3\n' | { PATH="$dor_dir/bin:$PATH" STUB_DIR="$dor_dir" STUB_LOG="$dor_log" \
    nwo="" cmd_ready 901 >/dev/null 2>&1; cat; })"
  check "ready leaves the caller's stdin untouched" "$dor_got" "row-2
row-3"

  # MIP-0070 §5.7: the pool is now org-wide (`gh search issues --owner`), so the fixture is
  # named for that call and every row carries its own repository — #950 is in a different repo,
  # to prove the queue prints it qualified while every same-repo row stays exactly as it was.
  cat > "$dor_dir/search-issues.json" <<'EOF'
[{"number":904,"title":"Cache Open-Meteo responses","assignees":[],"milestone":"",
  "repository":{"nameWithOwner":"marola-dev/marola"},
  "labels":[{"name":"agent-ready"},{"name":"area/conditions"},{"name":"layer/core"},{"name":"size/M"}]},
 {"number":901,"title":"Add hreflang tags","assignees":[],"milestone":"Water quality on the map",
  "repository":{"nameWithOwner":"marola-dev/marola"},
  "labels":[{"name":"agent-ready"},{"name":"area/map-site"},{"name":"layer/site"},{"name":"size/S"}]},
 {"number":903,"title":"Waiting on an open one","assignees":[],"milestone":"",
  "repository":{"nameWithOwner":"marola-dev/marola"},
  "labels":[{"name":"area/dev-tooling"},{"name":"layer/infra"},{"name":"size/S"}]},
 {"number":902,"title":"Still in triage","assignees":[],"milestone":"",
  "repository":{"nameWithOwner":"marola-dev/marola"},"labels":[{"name":"bug"}]},
 {"number":907,"title":"Someone is already on it","assignees":[{"login":"x"}],"milestone":"",
  "repository":{"nameWithOwner":"marola-dev/marola"},
  "labels":[{"name":"agent-ready"},{"name":"area/dev-tooling"},{"name":"layer/infra"},{"name":"size/S"}]},
 {"number":950,"title":"A row filed in another repo","assignees":[],"milestone":"",
  "repository":{"nameWithOwner":"marola-dev/marola-site"},
  "labels":[{"name":"agent-ready"},{"name":"area/map-site"},{"name":"layer/site"},{"name":"size/S"}]}]
EOF
  printf '[]\n' > "$dor_dir/950.deps.json"
  dor_got="$(PATH="$dor_dir/bin:$PATH" STUB_DIR="$dor_dir" STUB_LOG="$dor_log" nwo="" cmd_queue)"
  check "queue sorts size/S ahead of size/M" "$(head -1 <<<"$dor_got" | awk '{ print $1, $2 }')" "#901 size/S"
  # B5 (MIP-0070 §5.7): an own-repo row's line is byte-identical to the pre-org-wide format.
  check "an own-repo row's line is byte-for-byte what it always was" "$(head -1 <<<"$dor_got")" \
    "#901   size/S  area/map-site       layer/site    Add hreflang tags"
  check "an assigned agent-ready issue is not in the queue" "$(grep -c '^#907' <<<"$dor_got" || true)" "0"
  check "an issue in another repo is printed as owner/repo#N" \
    "$(grep -c '^marola-dev/marola-site#950' <<<"$dor_got" || true)" "1"
  check "the footer partitions the unassigned open issues, across every repo" "$(tail -1 <<<"$dor_got")" \
    "      3 ready · 1 blocked · 1 in triage"
  dor_got="$(PATH="$dor_dir/bin:$PATH" STUB_DIR="$dor_dir" STUB_LOG="$dor_log" nwo="" \
    cmd_queue --milestone "Water quality on the map")"
  check "--milestone narrows the queue and names itself" "$(tail -1 <<<"$dor_got")" \
    "      1 ready · 0 blocked · 0 in triage   (milestone: Water quality on the map)"

  echo
  echo "-- tasks-to-issues: a parent in the umbrella, one sub-issue per row, DAG edges (MIP-0070 §5.7) --"
  local t2i="$tmp/t2i" t2i_out t2i_file
  # `stateful`: run 2 sees exactly what run 1 left behind, which is the only way to test
  # idempotence — the property this command is for — without filing anything. Every row here has
  # no `**repo**` prefix, so every issue — parent and rows alike — lands in the umbrella; the
  # two-repo shape (§5.7) gets its own scenario below.
  write_gh_stub "$t2i"
  : > "$t2i/stateful"
  # The parent's title wants the MIP's own H1 — resolved via mip_ref.sh's gh-api tier here, since
  # none of MIP-0099/0098/0065 (used across this section) is a real MIP doc.
  printf '[{"name":"MIP-0099-demo.md"},{"name":"MIP-0098-demo.md"},{"name":"MIP-0065-demo.md"}]\n' \
    > "$t2i/mip-list.json"
  mkdir -p "$t2i/mip-content"
  printf '# MIP-0099: A demo MIP\n' > "$t2i/mip-content/MIP-0099-demo.md"
  printf '# MIP-0098: A demo MIP\n' > "$t2i/mip-content/MIP-0098-demo.md"
  printf '# MIP-0065: A demo MIP\n' > "$t2i/mip-content/MIP-0065-demo.md"
  printf '[{"number":3,"title":"Issue tracking standard live"}]\n' > "$t2i/milestones.json"
  printf '[]\n' > "$t2i/issues-all.json"
  printf '{"projects":[{"number":7,"id":"PVT_t2i","title":"Marola"}]}\n' > "$t2i/projects.json"
  printf '{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"}]}\n' > "$t2i/fields.json"
  printf '{"items":[]}\n' > "$t2i/items.json"
  : > "$t2i/log"
  t2i_file="$t2i/MIP-0099.tasks.md"
  # 6 depends on 1, not on 5, and 3 is a second root: the shape MIP-0034 and MIP-0031 have and a
  # k-depends-on-k-1 reading of the table would destroy.
  {
    echo '| # | slug | delivers | tests (must exist before the PR) | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | a | d | t | – |\n| 2 | b | d | t | 1 |\n| 3 | c | d | t | – |\n'
    printf '| 4 | d | d | t | 1, 3 |\n| 5 | e | d | t | 2 |\n| 6 | f | d | t | 1 |\n'
  } > "$t2i_file"

  dry=0
  : > "$t2i/authlog"
  gh_checked=0
  t2i_out="$(PATH="$t2i/bin:$PATH" STUB_DIR="$t2i" STUB_LOG="$t2i/log" STUB_SCOPES=project nwo="" \
    cmd_tasks_to_issues "$t2i_file" --milestone "Issue tracking standard live" 2>&1)" || failed=1
  # One `gh auth status` for the whole run, not one per edge: cmd_deps_add is re-entered five
  # times below and require_gh is what it re-enters.
  check "the login is checked once, not once per edge" "$(grep -c . "$t2i/authlog")" "1"
  # The parent is filed first (issue 700), so the six rows start from 701 — MIP-0099-T1..T6.
  check "run 1 files the parent, every row and wires every edge" "$(tail -1 <<<"$t2i_out")" \
    "summary: parent #700 · 6 created, 0 already filed (0 fallback) · 6 rows linked · 6 sub-issues wired, 0 already, 0 pending · 5 edges wired, 0 already wired, 0 pending, 0 skipped (cross-repo) · board 7 ok, 0 failed"
  check "the edges are the depends-on column's, not the row order's" "$(grep '^edge' "$t2i/log" | tr '\n' ' ')" \
    "edge 702 <- 701 edge 704 <- 701 edge 704 <- 703 edge 705 <- 702 edge 706 <- 701 "
  check "every row's # cell is now a link" \
    "$(grep -c '^| \[[1-6]\](https://github.com/marola-dev/marola/issues/70[1-6]) |' "$t2i_file")" "6"
  check "every row became the parent's sub-issue" "$(grep -c '^sub 700 <-' "$t2i/log")" "6"
  check "the parent and all six rows went onto the board" "$(grep -c '^project item-add' "$t2i/log")" "7"

  : > "$t2i/log"
  t2i_out="$(PATH="$t2i/bin:$PATH" STUB_DIR="$t2i" STUB_LOG="$t2i/log" STUB_SCOPES=project nwo="" \
    cmd_tasks_to_issues "$t2i_file" --milestone "Issue tracking standard live" 2>&1)" || failed=1
  check "run 2 creates nothing, rewrites nothing, re-posts no edge and no sub-issue" "$(tail -1 <<<"$t2i_out")" \
    "summary: parent #700 · 0 created, 6 already filed (0 fallback) · 0 rows linked · 0 sub-issues wired, 6 already, 0 pending · 0 edges wired, 5 already wired, 0 pending, 0 skipped (cross-repo) · board 7 ok, 0 failed"
  check "run 2 made no mutating gh call at all, beyond the idempotent board add" \
    "$(grep -vc '^project item-add' "$t2i/log")" "0"
  # The stub recorded each edge as closed on the way in: the 2026-09-27 probe found a dependency
  # survives closing both issues, so a closed blocker is still an edge and must not be re-posted.
  check "a closed blocker still counts as wired" "$(jq -r '.[0].state' "$t2i/702.deps.json")" "closed"

  printf '[]\n' > "$t2i/issues-all.json"
  rm -f "$t2i"/*.deps.json "$t2i"/*.issue.json "$t2i"/*.subs.json "$t2i/repo-of.json"
  printf '{"items":[]}\n' > "$t2i/items.json"
  : > "$t2i/log"
  t2i_out="$(PATH="$t2i/bin:$PATH" STUB_DIR="$t2i" STUB_LOG="$t2i/log" STUB_SCOPES=project nwo="" \
    dry=1 cmd_tasks_to_issues "$t2i_file" 2>&1)" || failed=1
  check "--dry-run on an already-linked file creates nothing and leaves the rows alone" \
    "$(tail -1 <<<"$t2i_out")" \
    "summary: parent pending · 6 created, 0 already filed (0 fallback) · 0 rows linked · 0 sub-issues wired, 0 already, 6 pending · 0 edges wired, 0 already wired, 5 pending, 0 skipped (cross-repo) · board 0 ok, 0 failed (--dry-run: nothing was written)"
  check "--dry-run made no mutating call" "$(cat "$t2i/log")" ""
  dry=0

  if (PATH="$t2i/bin:$PATH" STUB_DIR="$t2i" STUB_LOG="$t2i/log" STUB_SCOPES=project nwo="" \
    cmd_tasks_to_issues "$t2i_file" --milestone "No such milestone") >/dev/null 2>&1; then
    echo "FAILED: a milestone that does not exist was accepted; the first create would have aborted part-way" >&2; failed=1
  else
    echo "ok: an unknown milestone is refused before anything is created"
  fi

  # A blocker whose lookup 404s (transferred, deleted, or a PR) reaches cmd_deps_add's
  # resolve_issue_id. While that said `exit 1`, the first such edge left the shell from inside the
  # loop's `if`: no summary, the later edges never attempted, and the failure tally unreachable.
  # Row 1 (issue 701) is both a row of its own — its sub-issue link needs its id too — and every
  # other failing edge's blocker, so one unresolvable id costs 1 sub-issue + 3 edges here.
  local t2i_rc=0
  : > "$t2i/log"
  echo 701 > "$t2i/fail_id"
  t2i_out="$(PATH="$t2i/bin:$PATH" STUB_DIR="$t2i" STUB_LOG="$t2i/log" STUB_SCOPES=project nwo="" \
    cmd_tasks_to_issues "$t2i_file" 2>&1)" || t2i_rc=$?
  rm -f "$t2i/fail_id"
  check "an unresolvable blocker does not abort the loop — the other edges are still attempted" \
    "$(grep -c '^edge ' "$t2i/log")" "2"
  check "and the summary is still printed" "$(grep -c '^summary: ' <<<"$t2i_out")" "1"
  check "and the run exits non-zero, naming how many actions failed" "$t2i_rc" "1"
  case "$t2i_out" in
    *"4 action(s) failed"*) echo "ok: every failed edge (and the sub-issue link needing the same id) is counted" ;;
    *) echo "FAILED: the failure tally did not survive the loop:" >&2; sed 's/^/  /' <<<"$t2i_out" >&2; failed=1 ;;
  esac
  case "$t2i_out" in
    *"no --milestone"*) echo "ok: a real run with no --milestone says so" ;;
    *) echo "FAILED: a real run filed issues into no milestone silently" >&2; failed=1 ;;
  esac

  # The sibling failure: the create lands, the URL will not parse, and the issue now exists with
  # nothing in the tasks file pointing at it.
  local t2i_bad="$t2i/MIP-0098.tasks.md"
  {
    echo '| # | slug | delivers | tests (must exist before the PR) | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | a | d | t | – |\n| 2 | b | d | t | 1 |\n'
  } > "$t2i_bad"
  : > "$t2i/log"; : > "$t2i/bad_url"
  t2i_rc=0
  t2i_out="$(PATH="$t2i/bin:$PATH" STUB_DIR="$t2i" STUB_LOG="$t2i/log" STUB_SCOPES=project nwo="" \
    cmd_tasks_to_issues "$t2i_bad" 2>&1)" || t2i_rc=$?
  rm -f "$t2i/bad_url"
  check "a create whose URL will not parse is counted, and the summary still prints" \
    "$(grep -c '^summary: ' <<<"$t2i_out")" "1"
  check "and the run says so rather than exiting 0 half way" "$t2i_rc" "1"

  # #462: a `depends on` naming another MIP's task is an edge to that MIP's issue, found by title.
  local t2i_x="$t2i/MIP-0065.tasks.md"
  {
    echo '| # | slug | delivers | tests (must exist before the PR) | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | a | d | t | 0064-T4 |\n| 2 | b | d | t | 1, 0064-T4 |\n'
  } > "$t2i_x"
  rm -f "$t2i"/*.deps.json "$t2i"/*.issue.json "$t2i"/*.subs.json "$t2i/repo-of.json"
  printf '[{"number":458,"title":"0064-T4: kroki"}]\n' > "$t2i/issues-all.json"
  printf '{"items":[]}\n' > "$t2i/items.json"
  : > "$t2i/log"
  t2i_out="$(PATH="$t2i/bin:$PATH" STUB_DIR="$t2i" STUB_LOG="$t2i/log" STUB_SCOPES=project nwo="" \
    cmd_tasks_to_issues "$t2i_x" 2>&1)" || failed=1
  check "a cross-MIP token is wired to the issue whose title carries it, same repo as the row" \
    "$(grep '^edge' "$t2i/log" | tr '\n' ' ')" \
    "edge 702 <- 458 edge 703 <- 702 edge 703 <- 458 "
  : > "$t2i/log"
  t2i_out="$(PATH="$t2i/bin:$PATH" STUB_DIR="$t2i" STUB_LOG="$t2i/log" STUB_SCOPES=project nwo="" \
    cmd_tasks_to_issues "$t2i_x" 2>&1)" || failed=1
  check "and a re-run adds no issue and no edge" "$(tail -1 <<<"$t2i_out")" \
    "summary: parent #701 · 0 created, 2 already filed (0 fallback) · 0 rows linked · 0 sub-issues wired, 2 already, 0 pending · 0 edges wired, 3 already wired, 0 pending, 0 skipped (cross-repo) · board 3 ok, 0 failed"
  printf '[]\n' > "$t2i/issues-all.json"
  : > "$t2i/log"
  sed -i 's/^| \[\([12]\)\]([^)]*) |/| \1 |/' "$t2i_x"
  if (PATH="$t2i/bin:$PATH" STUB_DIR="$t2i" STUB_LOG="$t2i/log" STUB_SCOPES=project nwo="" \
    cmd_tasks_to_issues "$t2i_x") >/dev/null 2>&1; then
    echo "FAILED: a cross-MIP token with no issue was accepted" >&2; failed=1
  else
    check "an unfiled cross-MIP token files nothing" "$(cat "$t2i/log")" ""
  fi

  echo
  echo "-- tasks-to-issues MIP-NNNN: a code repo with no local docs/MIPs resolves it remotely (§5.6, #562) --"
  local t2u="$tmp/t2u" t2u_out
  write_gh_stub "$t2u"
  : > "$t2u/stateful"
  printf '[{"name":"MIP-0094.tasks.md"},{"name":"MIP-0094-demo.md"}]\n' > "$t2u/mip-list.json"
  mkdir -p "$t2u/mip-content"
  printf '# MIP-0094: A demo MIP\n' > "$t2u/mip-content/MIP-0094-demo.md"
  {
    echo '| # | slug | delivers | tests | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | a-thing | d | t | – |\n'
  } > "$t2u/mip-content/MIP-0094.tasks.md"
  printf '[]\n' > "$t2u/issues-all.json"
  printf '{"projects":[{"number":7,"id":"PVT_t2u","title":"Marola"}]}\n' > "$t2u/projects.json"
  printf '{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"}]}\n' > "$t2u/fields.json"
  printf '{"items":[]}\n' > "$t2u/items.json"
  : > "$t2u/log"
  # Captures the remote-fallback temp dir's path (issues.sh's own `mktemp -d`) without changing
  # anything else `mktemp` is used for in this run, so the test can assert it is gone afterwards —
  # the #562 carry-over: this temp dir used to leak on an early exit, with no self-test to catch it.
  # Written to a file, not a variable: `cmd_tasks_to_issues` runs inside `t2u_out`'s command
  # substitution, a forked subshell, so a plain variable assignment here would vanish with it.
  local t2u_capture_file="$t2u/captured_dir" t2u_captured=""
  mktemp() {
    if [ "${1:-}" = "-d" ]; then
      command mktemp -d | tee "$t2u_capture_file"
    else
      command mktemp "$@"
    fi
  }
  t2u_out="$(PATH="$t2u/bin:$PATH" STUB_DIR="$t2u" STUB_LOG="$t2u/log" STUB_SCOPES=project nwo="" \
    MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues MIP-0094 2>&1)" || failed=1
  unset -f mktemp
  [ ! -f "$t2u_capture_file" ] || t2u_captured="$(cat "$t2u_capture_file")"
  check "a bare MIP-NNNN with no local tasks file resolves it from the umbrella" \
    "$(grep -c '^  0094-T1 ' <<<"$t2u_out")" "1"
  check "the remote-fallback temp dir is created" "$([ -n "$t2u_captured" ] && echo yes || echo no)" "yes"
  check "and cleaned up once the run finishes" "$([ -d "$t2u_captured" ] && echo still-there || echo gone)" "gone"

  echo
  echo "-- tasks-to-issues: two repos — one exists, one is a fallback, and a re-run stays put (§5.7) --"
  local t2r="$tmp/t2r" t2r_out t2r_file
  write_gh_stub "$t2r"
  : > "$t2r/stateful"
  printf '[{"name":"MIP-0097-demo.md"}]\n' > "$t2r/mip-list.json"
  mkdir -p "$t2r/mip-content"
  printf '# MIP-0097: A demo MIP\n' > "$t2r/mip-content/MIP-0097-demo.md"
  printf 'marola-dev/marola-site\n' > "$t2r/existing-repos"
  printf '[]\n' > "$t2r/issues-all.json"
  printf '[]\n' > "$t2r/issues-all.marola-dev_marola-site.json"
  printf '{"projects":[{"number":7,"id":"PVT_t2r","title":"Marola"}]}\n' > "$t2r/projects.json"
  printf '{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"},{"id":"PVTF_d","name":"Deliverable","type":"ProjectV2Field"}]}\n' \
    > "$t2r/fields.json"
  printf '{"items":[]}\n' > "$t2r/items.json"
  t2r_file="$t2r/MIP-0097.tasks.md"
  # Row 1's repo exists — it lands there. Row 2 names one that does not exist yet (`(new)`) and
  # depends on row 1: a fallback (into the umbrella) and a genuinely cross-repo edge in one table.
  {
    echo '| # | slug | delivers | tests | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | site-thing | **marola-site** — x | t | – |\n'
    printf '| 2 | devkit-thing | **marola-devkit** (new) — y | t | 1 |\n'
  } > "$t2r_file"
  : > "$t2r/log"
  t2r_out="$(PATH="$t2r/bin:$PATH" STUB_DIR="$t2r" STUB_LOG="$t2r/log" STUB_SCOPES=project nwo="" \
    MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2r_file" --deliverable "Two-repo demo" 2>&1)" || failed=1
  check "row 1's repo exists, so it is filed there, not reported as a fallback" \
    "$(grep -c '0097-T1.*created in marola-dev/marola-site$' <<<"$t2r_out")" "1"
  check "row 2's repo does not exist yet, so it falls back to the umbrella, and the run says so" \
    "$(grep -c 'fallback: 0097-T2 -> marola-dev/marola (its own repo is not there yet)' <<<"$t2r_out")" "1"
  check "the parent is filed in the umbrella regardless" \
    "$(grep -c 'parent.*created: MIP-0097: A demo MIP$' <<<"$t2r_out")" "1"
  check "both rows became the parent's sub-issue, across repos" "$(grep -c '^sub ' "$t2r/log")" "2"
  # Row 1 (marola-site) and row 2 (umbrella, its fallback) are in different repos — the same
  # `dependencies/blocked_by` API is tried anyway (§5.7), and the stub lets it succeed here.
  check "a cross-repo depends-on edge is attempted with the same API as a same-repo one" \
    "$(grep -c '^edge ' "$t2r/log")" "1"
  check "--deliverable sets the Deliverable field on the parent and every row" \
    "$(grep -cF -- '--field-id PVTF_d --text Two-repo demo' "$t2r/log")" "3"
  check "no created issue is left unlinked" \
    "$(grep -c '^| \[[12]\](https://github.com/marola-dev/' "$t2r_file")" "2"
  local t2r_parent_num
  t2r_parent_num="$(grep -o 'parent      #[0-9]*' <<<"$t2r_out" | grep -o '[0-9]*')"

  : > "$t2r/log"
  t2r_out="$(PATH="$t2r/bin:$PATH" STUB_DIR="$t2r" STUB_LOG="$t2r/log" STUB_SCOPES=project nwo="" \
    MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2r_file" --deliverable "Two-repo demo" 2>&1)" || failed=1
  check "a re-run finds the parent and both rows, creates nothing" "$(tail -1 <<<"$t2r_out")" \
    "summary: parent #$t2r_parent_num · 0 created, 2 already filed (1 fallback) · 0 rows linked · 0 sub-issues wired, 2 already, 0 pending · 0 edges wired, 1 already wired, 0 pending, 0 skipped (cross-repo) · board 3 ok, 0 failed"
  check "re-run made no create/edge/sub-issue calls" \
    "$(grep -cE '^(edge|sub) ' "$t2r/log")" "0"
  # The row that fell back stays in the umbrella even though its own repo could exist by the time
  # of a re-run — §5.7: an existing filing is never treated as something to move.
  echo 'marola-dev/marola-devkit' >> "$t2r/existing-repos"
  printf '[]\n' > "$t2r/issues-all.marola-dev_marola-devkit.json"
  t2r_out="$(PATH="$t2r/bin:$PATH" STUB_DIR="$t2r" STUB_LOG="$t2r/log" STUB_SCOPES=project nwo="" \
    MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2r_file" 2>&1)" || failed=1
  check "row 2 is not moved once marola-devkit exists — it is still found in the umbrella" \
    "$(grep -c '0097-T2.*already filed (marola-dev/marola)$' <<<"$t2r_out")" "1"

  echo
  echo "-- tasks-to-issues: three target repos, every one probed despite sharing stdin with gh (§5.7) --"
  local t2p="$tmp/t2p" t2p_out t2p_file
  write_gh_stub "$t2p"
  : > "$t2p/stateful"
  printf '[{"name":"MIP-0093-demo.md"}]\n' > "$t2p/mip-list.json"
  mkdir -p "$t2p/mip-content"
  printf '# MIP-0093: A demo MIP\n' > "$t2p/mip-content/MIP-0093-demo.md"
  printf 'marola-dev/marola-a\nmarola-dev/marola-c\n' > "$t2p/existing-repos"
  printf '[]\n' > "$t2p/issues-all.json"
  printf '[]\n' > "$t2p/issues-all.marola-dev_marola-a.json"
  printf '[]\n' > "$t2p/issues-all.marola-dev_marola-c.json"
  printf '{"projects":[{"number":7,"id":"PVT_t2p","title":"Marola"}]}\n' > "$t2p/projects.json"
  printf '{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"}]}\n' > "$t2p/fields.json"
  printf '{"items":[]}\n' > "$t2p/items.json"
  t2p_file="$t2p/MIP-0093.tasks.md"
  {
    echo '| # | slug | delivers | tests | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | a-thing | **marola-a** — x | t | – |\n'
    printf '| 2 | b-thing | **marola-b** (new) — y | t | – |\n'
    printf '| 3 | c-thing | **marola-c** — z | t | – |\n'
  } > "$t2p_file"
  : > "$t2p/log"; rm -f "$t2p/repo-view-log"
  t2p_out="$(PATH="$t2p/bin:$PATH" STUB_DIR="$t2p" STUB_LOG="$t2p/log" STUB_SCOPES=project nwo="" \
    STUB_DRAIN_REPO_VIEW=1 MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2p_file" 2>&1)" || failed=1
  check "every one of the three target repos is probed, not just the first" \
    "$(sort -u "$t2p/repo-view-log" 2>/dev/null | wc -l | tr -d ' ')" "3"
  check "the one that doesn't exist still falls back" \
    "$(grep -c '^  0093-T2.*created in marola-dev/marola$' <<<"$t2p_out")" "1"
  check "the two that exist file in their own repo, not the umbrella" \
    "$(grep -cE '^  009[13]-T[13].*created in marola-dev/marola-[ac]$' <<<"$t2p_out")" "2"

  echo
  echo "-- tasks-to-issues: a non-404 repo-view failure aborts the run, not a silent fallback (§5.7, review round 1 #2) --"
  local t2p2="$tmp/t2p2" t2p2_out t2p2_file t2p2_rc=0
  write_gh_stub "$t2p2"
  : > "$t2p2/stateful"
  printf '[{"name":"MIP-0092-demo.md"}]\n' > "$t2p2/mip-list.json"
  mkdir -p "$t2p2/mip-content"
  printf '# MIP-0092: A demo MIP\n' > "$t2p2/mip-content/MIP-0092-demo.md"
  printf 'marola-dev/marola-ok\n' > "$t2p2/existing-repos"
  printf 'marola-dev/marola-broken\n' > "$t2p2/repo-view-fail"
  printf '[]\n' > "$t2p2/issues-all.json"
  printf '{"projects":[{"number":7,"id":"PVT_t2p2","title":"Marola"}]}\n' > "$t2p2/projects.json"
  printf '{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"}]}\n' > "$t2p2/fields.json"
  printf '{"items":[]}\n' > "$t2p2/items.json"
  t2p2_file="$t2p2/MIP-0092.tasks.md"
  {
    echo '| # | slug | delivers | tests | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | ok-thing | **marola-ok** — x | t | – |\n'
    printf '| 2 | broken-thing | **marola-broken** — y | t | – |\n'
  } > "$t2p2_file"
  : > "$t2p2/log"
  t2p2_out="$(PATH="$t2p2/bin:$PATH" STUB_DIR="$t2p2" STUB_LOG="$t2p2/log" STUB_SCOPES=project nwo="" \
    MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2p2_file" 2>&1)" || t2p2_rc=$?
  check "a non-404 failure aborts the run" "$t2p2_rc" "1"
  check "and says so, instead of silently treating it as missing" \
    "$(grep -c 'not a 404' <<<"$t2p2_out")" "1"
  check "nothing was created before the abort" "$(grep -c '^  009[12]-T[12] ' <<<"$t2p2_out")" "0"

  echo
  echo "-- tasks-to-issues: a cross-repo depends-on edge, GitHub's docs unconfirmed (§5.7) --"
  local t2x="$tmp/t2x" t2x_out t2x_file t2x_rc
  write_gh_stub "$t2x"
  : > "$t2x/stateful"
  printf '[{"name":"MIP-0096-demo.md"}]\n' > "$t2x/mip-list.json"
  mkdir -p "$t2x/mip-content"
  printf '# MIP-0096: A demo MIP\n' > "$t2x/mip-content/MIP-0096-demo.md"
  printf 'marola-dev/marola-b\n' > "$t2x/existing-repos"
  printf '[]\n' > "$t2x/issues-all.json"
  printf '[]\n' > "$t2x/issues-all.marola-dev_marola-b.json"
  printf '{"projects":[{"number":7,"id":"PVT_t2x","title":"Marola"}]}\n' > "$t2x/projects.json"
  printf '{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"}]}\n' > "$t2x/fields.json"
  printf '{"items":[]}\n' > "$t2x/items.json"
  t2x_file="$t2x/MIP-0096.tasks.md"
  {
    echo '| # | slug | delivers | tests | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | a-thing | **marola-b** — x | t | – |\n'
    printf '| 2 | b-thing | d | t | 1 |\n'
  } > "$t2x_file"
  : > "$t2x/log"
  t2x_rc=0
  t2x_out="$(PATH="$t2x/bin:$PATH" STUB_DIR="$t2x" STUB_LOG="$t2x/log" STUB_SCOPES=project \
    STUB_CROSS_UNSUPPORTED=1 nwo="" MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2x_file" 2>&1)" || t2x_rc=$?
  check "the run still succeeds when a cross-repo edge is unsupported" "$t2x_rc" "0"
  check "the cross-repo edge is reported skipped, not wired or failed" "$(tail -1 <<<"$t2x_out")" \
    "summary: parent #700 · 2 created, 0 already filed (0 fallback) · 2 rows linked · 2 sub-issues wired, 0 already, 0 pending · 0 edges wired, 0 already wired, 0 pending, 1 skipped (cross-repo) · board 3 ok, 0 failed"
  case "$t2x_out" in
    *"cross-repo blocked-by not confirmed"*) echo "ok: and it says why" ;;
    *) echo "FAILED: no skip message printed for the unsupported cross-repo attempt" >&2; failed=1 ;;
  esac

  # A blocker whose id can't even be resolved is a real failure, cross-repo or not — unlike a POST
  # GitHub rejects once the id is known, nothing here says this shape might just be unsupported.
  printf '[]\n' > "$t2x/issues-all.json"
  printf '[]\n' > "$t2x/issues-all.marola-dev_marola-b.json"
  rm -f "$t2x"/*.deps.json "$t2x"/*.issue.json "$t2x"/*.subs.json "$t2x/repo-of.json"
  printf '{"items":[]}\n' > "$t2x/items.json"
  : > "$t2x/log"
  echo 701 > "$t2x/fail_id"
  t2x_rc=0
  t2x_out="$(PATH="$t2x/bin:$PATH" STUB_DIR="$t2x" STUB_LOG="$t2x/log" STUB_SCOPES=project nwo="" \
    MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2x_file" 2>&1)" || t2x_rc=$?
  rm -f "$t2x/fail_id"
  check "an unresolvable cross-repo blocker is a real failure" "$t2x_rc" "1"
  check "and it is not counted as skipped" \
    "$(grep -o '[0-9]* skipped (cross-repo)' <<<"$t2x_out")" "0 skipped (cross-repo)"

  echo
  echo "-- tasks-to-issues: \"already wired\" matches the blocker's repo too, not the number alone (§5.7) --"
  local t2y="$tmp/t2y" t2y_out
  write_gh_stub "$t2y"
  : > "$t2y/stateful"
  printf '[{"name":"MIP-0095-demo.md"}]\n' > "$t2y/mip-list.json"
  mkdir -p "$t2y/mip-content"
  printf '# MIP-0095: A demo MIP\n' > "$t2y/mip-content/MIP-0095-demo.md"
  printf 'marola-dev/marola-c\n' > "$t2y/existing-repos"
  # Row 1 (marola-c, #500) is already filed; row 2 (the umbrella, #500 too — same number, a
  # different repo) is already filed as well. Row 1's own blocked-by list already names a #500,
  # but in a *third* repo — a number-only match would misread that as "row 2 is already wired".
  printf '[{"number":500,"title":"0095-T2: b-thing"}]\n' > "$t2y/issues-all.json"
  printf '[{"number":500,"title":"0095-T1: a-thing"}]\n' > "$t2y/issues-all.marola-dev_marola-c.json"
  printf '[{"number":500,"state":"closed","repository":{"full_name":"marola-dev/marola-other"}}]\n' \
    > "$t2y/500.deps.json"
  printf '{"projects":[{"number":7,"id":"PVT_t2y","title":"Marola"}]}\n' > "$t2y/projects.json"
  printf '{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"}]}\n' > "$t2y/fields.json"
  printf '{"items":[]}\n' > "$t2y/items.json"
  local t2y_file="$t2y/MIP-0095.tasks.md"
  {
    echo '| # | slug | delivers | tests | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | a-thing | **marola-c** — x | t | 2 |\n'
    printf '| 2 | b-thing | d | t | – |\n'
  } > "$t2y_file"
  : > "$t2y/log"
  t2y_out="$(PATH="$t2y/bin:$PATH" STUB_DIR="$t2y" STUB_LOG="$t2y/log" STUB_SCOPES=project nwo="" \
    MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2y_file" 2>&1)" || failed=1
  check "row 2's #500 is not mistaken for the different repo's #500 already in row 1's list" \
    "$(grep -c -- '— already wired' <<<"$t2y_out")" "0"
  check "it is wired fresh instead" "$(grep -c '^edge 500 <- 500' "$t2y/log")" "1"

  echo
  echo "-- tasks-to-issues: an unresolvable H1 refuses the run rather than filing under a bare title (§5.7, review round 2 #5) --"
  local t2h="$tmp/t2h" t2h_out t2h_file t2h_rc=0
  write_gh_stub "$t2h"
  : > "$t2h/stateful"
  printf '[]\n' > "$t2h/mip-list.json"
  printf '[]\n' > "$t2h/issues-all.json"
  printf '{"projects":[{"number":7,"id":"PVT_t2h","title":"Marola"}]}\n' > "$t2h/projects.json"
  printf '{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"}]}\n' > "$t2h/fields.json"
  printf '{"items":[]}\n' > "$t2h/items.json"
  t2h_file="$t2h/MIP-0091.tasks.md"
  {
    echo '| # | slug | delivers | tests | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | a-thing | d | t | – |\n'
  } > "$t2h_file"
  : > "$t2h/log"
  t2h_out="$(PATH="$t2h/bin:$PATH" STUB_DIR="$t2h" STUB_LOG="$t2h/log" STUB_SCOPES=project nwo="" \
    MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2h_file" 2>&1)" || t2h_rc=$?
  check "an unresolvable H1 refuses the run" "$t2h_rc" "1"
  check "with a clear message naming the MIP and the reason" \
    "$(grep -c "could not resolve MIP-0091's own H1" <<<"$t2h_out")" "1"
  check "nothing was created before the refusal" "$(cat "$t2h/log")" ""

  echo
  echo "-- tasks-to-issues: --milestone is scoped to the umbrella — a foreign-repo row gets none (§5.7 Decision 6, review round 2 #6) --"
  local t2m="$tmp/t2m" t2m_out t2m_file
  write_gh_stub "$t2m"
  : > "$t2m/stateful"
  printf '[{"name":"MIP-0090-demo.md"}]\n' > "$t2m/mip-list.json"
  mkdir -p "$t2m/mip-content"
  printf '# MIP-0090: A demo MIP\n' > "$t2m/mip-content/MIP-0090-demo.md"
  printf 'marola-dev/marola-m\n' > "$t2m/existing-repos"
  printf '[{"number":9,"title":"Launch week"}]\n' > "$t2m/milestones.json"
  printf '[]\n' > "$t2m/issues-all.json"
  printf '[]\n' > "$t2m/issues-all.marola-dev_marola-m.json"
  printf '{"projects":[{"number":7,"id":"PVT_t2m","title":"Marola"}]}\n' > "$t2m/projects.json"
  printf '{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"}]}\n' > "$t2m/fields.json"
  printf '{"items":[]}\n' > "$t2m/items.json"
  t2m_file="$t2m/MIP-0090.tasks.md"
  {
    echo '| # | slug | delivers | tests | depends on |'
    echo '|---|---|---|---|---|'
    printf '| 1 | own-thing | d | t | – |\n'
    printf '| 2 | foreign-thing | **marola-m** — x | t | – |\n'
  } > "$t2m_file"
  : > "$t2m/log"
  # --dry-run, not a real create: run()'s dry branch prints each `gh issue create` through `%q`,
  # which escapes the row body's own embedded newlines inline rather than emitting them literally
  # — the whole invocation stays on one grep-able line, parent and rows alike.
  t2m_out="$(PATH="$t2m/bin:$PATH" STUB_DIR="$t2m" STUB_LOG="$t2m/log" STUB_SCOPES=project nwo="" \
    dry=1 MAROLA_UMBRELLA=marola-dev/marola cmd_tasks_to_issues "$t2m_file" --milestone "Launch week" 2>&1)" || failed=1
  local t2m_parent_line t2m_own_line t2m_foreign_line
  t2m_parent_line="$(grep -- '--repo marola-dev/marola --title MIP-0090:' <<<"$t2m_out")"
  t2m_own_line="$(grep -- '--repo marola-dev/marola --title 0090-T1:' <<<"$t2m_out")"
  t2m_foreign_line="$(grep -- '--repo marola-dev/marola-m --title 0090-T2:' <<<"$t2m_out")"
  check "the parent's create call is captured" "$([ -n "$t2m_parent_line" ] && echo yes || echo no)" "yes"
  check "and it carries the milestone" "$(grep -c -- '--milestone' <<<"$t2m_parent_line")" "1"
  check "the umbrella row's create call is captured" "$([ -n "$t2m_own_line" ] && echo yes || echo no)" "yes"
  check "and it carries the milestone too" "$(grep -c -- '--milestone' <<<"$t2m_own_line")" "1"
  check "the foreign-repo row's create call is captured" "$([ -n "$t2m_foreign_line" ] && echo yes || echo no)" "yes"
  check "and it carries no milestone" "$(grep -c -- '--milestone' <<<"$t2m_foreign_line")" "0"

  echo "-- the board: scopes, the Status lookup and the sync plan (MIP-0063 §5.2) --"
  check "read:project is not project" "$(has_scope "repo, read:project, workflow" project && echo yes || echo no)" "no"
  check "project is" "$(has_scope "repo, project, workflow" project && echo yes || echo no)" "yes"

  local board_fields board_items_json board_issues_json board_got board_got_plan board_closed_items_json
  board_fields='{"fields":[{"id":"PVTF_t","name":"Title","type":"ProjectV2Field"},
    {"id":"PVTSSF_s","name":"Status","type":"ProjectV2SingleSelectField",
     "options":[{"id":"o-triage","name":"Triage"},{"id":"o-ready","name":"Ready"},{"id":"o-prog","name":"In progress"},
                {"id":"o-done","name":"Done"}]}]}'
  check "a Status option resolves to its field id and its own id" \
    "$(board_status_option "$board_fields" "In progress")" "$(printf 'PVTSSF_s\to-prog')"
  # The live board still carries GitHub's template options (Backlog, not Triage/Spec). Sync has to
  # name what is missing rather than fall back to the nearest, which would put issues somewhere
  # §5.2 never defined and nobody is looking.
  check "an option §5.2 names but the board does not have resolves to nothing" \
    "$(board_status_option "$board_fields" "Spec")" ""

  board_items_json='[
    {"id":"I-910","status":"Backlog","content":{"number":910,"repository":"marola-dev/marola"}},
    {"id":"I-951","content":{"number":951,"repository":"marola-dev/marola"}},
    {"id":"I-952","status":"In review","content":{"number":952,"repository":"marola-dev/marola"}},
    {"id":"I-903","content":{"number":903,"repository":"someone/else"}}]'
  board_issues_json='[
    {"number":910,"url":"u910","assignees":[],"labels":[{"name":"agent-ready"}]},
    {"number":951,"url":"u951","assignees":[{"login":"x"}],"labels":[]},
    {"number":952,"url":"u952","assignees":[],"labels":[]},
    {"number":903,"url":"u903","assignees":[],"labels":[{"name":"agent-ready"}]},
    {"number":950,"url":"u950","assignees":[],"labels":[]}]'
  board_got="$(board_plan "$board_items_json" "$board_issues_json" "marola-dev/marola")"
  board_got_plan="$board_got"
  # The three cases the maintainer decided between. `Backlog` is adoptable because the auto-add
  # workflow wrote it, not a person; every other value is someone's choice. #952 is the one that
  # would undo a maintainer's work: unassigned and unlabelled, so a Status derived from its state
  # would drag it from In review back to Triage.
  check "the auto-add default is adopted, once, from the issue's state" \
    "$(grep '^adopt' <<<"$board_got" | cut -f2,3 | tr '\t' ' ')" "I-910 Ready"
  check "an item with no Status at all is set, and counted apart from an adoption" \
    "$(grep '^set' <<<"$board_got" | cut -f2,3 | tr '\t' ' ')" "I-951 In progress"
  check "a Status a human chose is never touched" "$(grep -c 'I-952' <<<"$board_got" || true)" "0"
  check "an unassigned agent-ready issue is Ready, not In progress" \
    "$(grep 'u903' <<<"$board_got" | cut -f1,3 | tr '\t' ' ')" "add Ready"
  check "an issue that is not on the board at all is an add" \
    "$(grep 'u950' <<<"$board_got" | cut -f1,3 | tr '\t' ' ')" "add Triage"
  check "board_item_id finds this repo's item" "$(board_item_id "$board_items_json" "marola-dev/marola" 910)" "I-910"
  # One board can hold several repositories and issue numbers are only unique within one, so a
  # match on the number alone would edit another repo's card.
  check "board_item_id will not match another repository's item" \
    "$(board_item_id "$board_items_json" "marola-dev/marola" 903)" ""

  board_closed_items_json='[
    {"id":"I-960","status":"In progress","content":{"type":"Issue","number":960,"repository":"marola-dev/marola"}},
    {"id":"I-961","status":"Done","content":{"type":"Issue","number":961,"repository":"marola-dev/marola"}},
    {"id":"I-962","content":{"type":"Issue","number":962,"repository":"marola-dev/marola"}},
    {"id":"I-963","content":{"type":"PullRequest","number":963,"repository":"marola-dev/marola"}},
    {"id":"I-964","status":"In review","content":{"type":"Issue","number":964,"repository":"someone/else"}},
    {"id":"I-910c","status":"In progress","content":{"type":"Issue","number":910,"repository":"marola-dev/marola"}}]'
  board_got="$(board_closed_plan "$board_closed_items_json" "$board_issues_json" "marola-dev/marola")"
  check "a closed issue not already Done is planned, carrying its old Status" \
    "$(grep 'I-960' <<<"$board_got" | cut -f1,2,3)" "$(printf 'I-960\tIn progress\t960')"
  check "a closed issue with no Status at all is planned too" \
    "$(grep 'I-962' <<<"$board_got" | cut -f1,2,3)" "$(printf 'I-962\t(no Status)\t962')"
  check "a closed issue already Done is left alone" "$(grep -c 'I-961' <<<"$board_got" || true)" "0"
  check "a closed pull request's card is not touched — only issues" \
    "$(grep -c 'I-963' <<<"$board_got" || true)" "0"
  check "another repository's closed issue is not touched" \
    "$(grep -c 'I-964' <<<"$board_got" || true)" "0"
  # #910 is open in $board_issues_json too — is:closed's answer alone is never trusted.
  check "an item is:closed also returned that is still in the open-issues list is never touched" \
    "$(grep -c 'I-910c' <<<"$board_got" || true)" "0"

  echo "-- board_plan and board_closed_plan survive a payload bigger than MAX_ARG_STRLEN --"
  # Regression test for the confirmed-live --argjson/argv bug. Padding goes through --rawfile, not
  # --arg, or building the fixture here would hit the same bug.
  local pad_file big_items_json big_closed_json
  pad_file="$(mktemp)"
  head -c 140000 /dev/zero | tr '\0' x > "$pad_file"
  [ "$(wc -c <"$pad_file")" -gt 131072 ] || { echo "FAILED: the generated padding is not even over 128 KiB" >&2; failed=1; }
  big_items_json="$(jq -c --rawfile pad "$pad_file" '.[0] += {padding: $pad}' <<<"$board_items_json")"
  [ "${#big_items_json}" -gt 131072 ] || { echo "FAILED: the padded items fixture is not over 128 KiB" >&2; failed=1; }
  check "board_plan over a >128 KiB items payload still returns the same plan" \
    "$(board_plan "$big_items_json" "$board_issues_json" "marola-dev/marola")" "$board_got_plan"
  big_closed_json="$(jq -c --rawfile pad "$pad_file" '.[0] += {padding: $pad}' <<<"$board_closed_items_json")"
  check "board_closed_plan over a >128 KiB items payload still returns the same plan" \
    "$(board_closed_plan "$big_closed_json" "$board_issues_json" "marola-dev/marola")" "$board_got"
  rm -f "$pad_file"

  echo
  echo "-- the gate names come from a PHASES.md-shaped fixture, not a live read of docs/PHASES.md --"
  # phase_titles_of is pure; a fixture here means this test (and the parser it exercises) survives
  # wherever the phase list itself lives — this script moves to marola-devkit, which carries no
  # docs/PHASES.md of its own (MIP-0070 §5.6).
  local phases_fixture
  phases_fixture="$(cat <<'EOF'
1. **Phase 0: POC pipeline + six pluggable integrations (done, this change).** Beach discovery.
2. **Phase 1: Telegram bot.** Long-polling loop.
3. **Phase 2: Go live on a cloud backend, deliberately.** Opt into a cloud backend.
4. **Phase 3: Deploy.** A hosted webhook.
5. **Phase 4: Harden & calibrate.** Caching, per-user rate limiting.
EOF
)"
  check "five phases, with PHASES.md's own names" "$(phase_titles_of "$phases_fixture" | cut -f2 | tr '\n' '|')" \
    "Phase 0 — POC pipeline + six pluggable integrations|Phase 1 — Telegram bot|Phase 2 — Go live on a cloud backend, deliberately|Phase 3 — Deploy|Phase 4 — Harden & calibrate|"
  check "PHASES.md marks phase 0 done, so that gate is filed closed" \
    "$(phase_titles_of "$phases_fixture" | awk -F'\t' '$3 == "done" { print $1 }')" "0"
  # Row 6's own named test ("issues.sh --self-test parses PHASES.md"): the real file, when this
  # checkout has one — guarded, not required, since this script moves to marola-devkit, which
  # carries none.
  if [ -f "$root/docs/PHASES.md" ]; then
    check "the real docs/PHASES.md parses into five phase titles" "$(phase_titles | wc -l | tr -d ' ')" "5"
  else
    echo "ok: no docs/PHASES.md here (marola-devkit) — skipped"
  fi

  echo
  echo "-- claim prints a branch command carrying the slug the tasks file actually has --"
  # A fixture, not docs/MIPs/MIP-0063.tasks.md: this script moves to marola-devkit, which carries
  # no docs/MIPs of its own (MIP-0070 §5.6), and stack_line_of/tasks_slug_of are pure either way.
  local tasks_fixture
  tasks_fixture="$(cat <<'EOF'
| # | slug | delivers | tests | depends on |
|---|---|---|---|---|
| [1](https://github.com/marola-dev/marola/issues/1414) | taxonomy | d | t | – |
| 6 | board-and-claim | d | t | 1 |
EOF
)"
  check "a MIP task title yields its own stack line" \
    "$(stack_line_of "$tasks_fixture" '0063-T6: claiming an issue, and the board itself')" \
    "scripts/stack.sh start MIP-0063 6 board-and-claim"
  # The `#` cell is a markdown link whose URL holds the issue number, so a slug read by taking
  # every digit in the cell would match row 1 against "1414" and never find it.
  check "the row is found by its number, not by the digits in its issue link" \
    "$(tasks_slug_of "$tasks_fixture" 1)" "taxonomy"
  check "an ordinary issue title yields no stack line at all" \
    "$(stack_line_of "$tasks_fixture" 'Cache Open-Meteo responses')" ""
  check "no tasks file resolved still yields a line, with the slug left to fill in" \
    "$(stack_line_of "" '9999-T2: something')" "scripts/stack.sh start MIP-9999 2 <slug>"

  echo
  echo "-- milestone new: --mip points at a MIP that exists, or not at all --"
  # mip_reference_of is pure — a fixture path, not a live resolve_mip_path against real docs/MIPs.
  check "--mip resolves to the MIP's own file" \
    "$(mip_reference_of 0063 "docs/MIPs/MIP-0063-github-issue-tracking-standard.md")" \
    "Design: MIP-0063 — docs/MIPs/MIP-0063-github-issue-tracking-standard.md"
  # mip_reference itself still resolves for real (local docs/MIPs, then ../, then gh api) — MIP-9999
  # never exists locally or in ../ here either way, but the gh-api tier would otherwise be a live
  # network call; a `gh` that always fails keeps this refusal hermetic without touching resolve_mip_path.
  local mipref_dir="$tmp/mipref"
  mkdir -p "$mipref_dir/bin"
  cat > "$mipref_dir/bin/gh" <<'SH'
#!/bin/sh
cat >/dev/null
exit 1
SH
  chmod +x "$mipref_dir/bin/gh"
  if PATH="$mipref_dir/bin:$PATH" mip_reference MIP-9999 >/dev/null 2>&1; then
    echo "FAILED: a MIP number with no file was accepted — the milestone's one reference would dangle" >&2; failed=1
  else
    echo "ok: --mip with no matching docs/MIPs/MIP-NNNN-*.md is refused"
  fi
  if mip_reference 63 >/dev/null 2>&1; then
    echo "FAILED: \"63\" was accepted where MIP-NNNN is required" >&2; failed=1
  else
    echo "ok: --mip wants the MIP-NNNN spelling"
  fi

  echo
  echo "-- claim, board sync and the gates against a stubbed gh --"
  local claim_dir="$tmp/claim" claim_log="$tmp/claim/calls.log" claim_got
  write_gh_stub "$claim_dir"

  # `claim` on #910 prints a stack_line, which resolves MIP-0063's tasks table for real
  # (resolve_mip_file): locally in this checkout, but via these two gh-api fixtures once this
  # script has no docs/MIPs of its own (the extracted marola-devkit) — same row, same slug, so the
  # assertion below holds in both.
  printf '[{"name":"MIP-0063.tasks.md"}]\n' > "$claim_dir/mip-list.json"
  mkdir -p "$claim_dir/mip-content"
  printf '| # | slug | delivers | tests | depends on |\n|---|---|---|---|---|\n| [6](https://github.com/marola-dev/marola/issues/910) | board-and-claim | d | t | – |\n' \
    > "$claim_dir/mip-content/MIP-0063.tasks.md"

  cat > "$claim_dir/910.issue.json" <<'EOF'
{"number":910,"id":5600000910,"state":"open","html_url":"u910",
 "title":"0063-T6: claiming an issue, and the board itself","assignees":[],
 "labels":[{"name":"agent-ready"},{"name":"area/dev-tooling"},{"name":"layer/infra"},{"name":"size/M"}],
 "body":"### Acceptance criteria\n\n- [ ] a\n\n### Named test\n\nFooSpec\n"}
EOF
  cat > "$claim_dir/911.issue.json" <<'EOF'
{"number":911,"id":5600000911,"state":"open","html_url":"u911","title":"Not ready yet","assignees":[],
 "labels":[{"name":"area/dev-tooling"},{"name":"layer/infra"},{"name":"size/S"}],
 "body":"### Acceptance criteria\n\n- [ ] a\n\n### Named test\n\nFooSpec\n"}
EOF
  cat > "$claim_dir/912.issue.json" <<'EOF'
{"number":912,"id":5600000912,"state":"closed","html_url":"u912","title":"Already done","assignees":[],
 "labels":[{"name":"agent-ready"}],"body":"### Named test\n\nFooSpec\n"}
EOF
  cat > "$claim_dir/913.issue.json" <<'EOF'
{"number":913,"id":5600000913,"state":"open","html_url":"u913","title":"Someone is on it",
 "assignees":[{"login":"someone-else"}],"labels":[{"name":"agent-ready"}],
 "body":"### Acceptance criteria\n\n- [ ] a\n\n### Named test\n\nFooSpec\n"}
EOF
  cat > "$claim_dir/914.issue.json" <<'EOF'
{"number":914,"id":5600000914,"state":"open","html_url":"u914","title":"Labelled by hand","assignees":[],
 "labels":[{"name":"agent-ready"},{"name":"area/dev-tooling"},{"name":"layer/infra"},{"name":"size/S"}],
 "body":"### Acceptance criteria\n\n- [ ] a\n"}
EOF
  printf '[]\n' > "$claim_dir/910.deps.json"
  cp "$claim_dir/910.deps.json" "$claim_dir/913.deps.json"
  cp "$claim_dir/910.deps.json" "$claim_dir/914.deps.json"
  printf '{"projects":[{"number":7,"id":"PVT_test","title":"Marola"}]}\n' > "$claim_dir/projects.json"
  printf '%s\n' "$board_fields" > "$claim_dir/fields.json"
  # A board that has not had `board setup` run yet: no Triage option to adopt anything into.
  jq '.fields |= map(if .name == "Status" then .options |= map(select(.name != "Triage")) else . end)' \
    "$claim_dir/fields.json" > "$claim_dir/fields-sparse.json"
  cat > "$claim_dir/items.json" <<'EOF'
{"items":[{"id":"I-910","status":"Backlog","content":{"number":910,"repository":"marola-dev/marola"}},
          {"id":"I-951","content":{"number":951,"repository":"marola-dev/marola"}},
          {"id":"I-952","status":"In review","content":{"number":952,"repository":"marola-dev/marola"}},
          {"id":"I-953","status":"Backlog","content":{"number":953,"repository":"marola-dev/marola"}}]}
EOF
  # After one sync: the adopted and the set card hold a real Status and #950 is on the board.
  cat > "$claim_dir/items-synced.json" <<'EOF'
{"items":[{"id":"I-910","status":"Ready","content":{"number":910,"repository":"marola-dev/marola"}},
          {"id":"I-951","status":"In progress","content":{"number":951,"repository":"marola-dev/marola"}},
          {"id":"I-952","status":"In review","content":{"number":952,"repository":"marola-dev/marola"}},
          {"id":"I-953","status":"Triage","content":{"number":953,"repository":"marola-dev/marola"}},
          {"id":"I-950","status":"Triage","content":{"number":950,"repository":"marola-dev/marola"}}]}
EOF
  # The default: nothing closed, so a plain sync's closed-issue pass has nothing to do.
  printf '{"items":[]}\n' > "$claim_dir/closed-items.json"
  cat > "$claim_dir/closed-items-two.json" <<'EOF'
{"items":[{"id":"I-960","status":"In progress","content":{"type":"Issue","number":960,"repository":"marola-dev/marola"}},
          {"id":"I-961","status":"Done","content":{"type":"Issue","number":961,"repository":"marola-dev/marola"}},
          {"id":"I-962","content":{"type":"Issue","number":962,"repository":"marola-dev/marola"}},
          {"id":"I-910dup","status":"Ready","content":{"type":"Issue","number":910,"repository":"marola-dev/marola"}}]}
EOF
  # cmd_board_gates reads phase_titles() for real; shadowed with $phases_fixture (declared above)
  # so its self-test doesn't depend on a live docs/PHASES.md either. Redefines the function for the
  # rest of the process — safe, since nothing later calls the bare phase_titles.
  phase_titles() { phase_titles_of "$phases_fixture"; }
  printf '[{"name":"phase/0"},{"name":"phase/1"},{"name":"phase/2"},{"name":"phase/3"},{"name":"phase/4"}]\n' \
    > "$claim_dir/labels.json"
  printf '[{"title":"Phase 3 — Deploy"},{"title":"0063-T6: claiming an issue, and the board itself"}]\n' \
    > "$claim_dir/issues-all.json"
  cat > "$claim_dir/issues-open.json" <<'EOF'
[{"number":910,"url":"u910","title":"0063-T6: claiming an issue, and the board itself",
  "assignees":[],"labels":[{"name":"agent-ready"}]},
 {"number":950,"url":"u950","title":"Phase 0 — POC pipeline + six pluggable integrations",
  "assignees":[],"labels":[]},
 {"number":951,"url":"u951","title":"Someone is on it","assignees":[{"login":"x"}],"labels":[]},
 {"number":952,"url":"u952","title":"A maintainer moved this one","assignees":[],"labels":[]},
 {"number":953,"url":"u953","title":"Still in triage","assignees":[],"labels":[]}]
EOF

  claim_case() {   # claim_case <label> <issue> <scopes> <want-rc> <want-output> <want-calls>
    local out rc=0
    : > "$claim_log"
    out="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" STUB_SCOPES="$3" \
      nwo="" cmd_claim "$2" 2>&1)" || rc=$?
    check "$1 (exit)" "$rc" "$4"
    case "$out" in
      *"$5"*) echo "ok: $1" ;;
      *) echo "FAILED: $1 — expected \"$5\" in:" >&2; sed 's/^/  /' <<<"$out" >&2; failed=1 ;;
    esac
    check "$1 (calls)" "$(cat "$claim_log")" "$6"
  }

  claim_case "a ready issue is assigned and loses the label, in one call" 910 project 0 \
    "claimed #910 as @brunogbv" \
    "issue edit --repo marola-dev/marola 910 --add-assignee brunogbv --remove-label agent-ready
project item-edit --id I-910 --project-id PVT_test --field-id PVTSSF_s --single-select-option-id o-prog"
  claim_case "and the board's Status goes to In progress with it" 910 project 0 \
    "scripts/stack.sh start MIP-0063 6 board-and-claim" \
    "issue edit --repo marola-dev/marola 910 --add-assignee brunogbv --remove-label agent-ready
project item-edit --id I-910 --project-id PVT_test --field-id PVTSSF_s --single-select-option-id o-prog"
  claim_case "an issue without agent-ready is refused before anything is read or written" 911 project 1 \
    "is not \`agent-ready\`" ""
  claim_case "a closed issue is refused" 912 project 1 "is closed, not open" ""
  claim_case "an issue assigned to someone else is refused, not taken off them" 913 project 1 \
    "already assigned to someone-else" ""
  # §8: the label can be added by hand without the rules ever passing. `ready` is the authority,
  # and it takes the label back off on the way through.
  claim_case "a hand-added label does not get past the DoR, and is removed" 914 project 1 \
    "no longer passes the Definition of Ready" \
    "issue edit --repo marola-dev/marola 914 --remove-label agent-ready"
  # The scope is a human's `gh auth refresh -s project` (§4.4). Until it lands, the half of §5.2's
  # duplicated state that an agent can write must still be writable, or nothing is claimable.
  claim_case "without \`project\` scope the claim still lands, and says the board did not" 910 read:project 0 \
    "cannot write the board" \
    "issue edit --repo marola-dev/marola 910 --add-assignee brunogbv --remove-label agent-ready"

  claim_got="$(printf 'row-2\nrow-3\n' | { PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" \
    STUB_LOG="$claim_log" STUB_SCOPES=project nwo="" cmd_claim 910 >/dev/null 2>&1; cat; })"
  check "claim leaves the caller's stdin untouched" "$claim_got" "row-2
row-3"

  : > "$claim_log"
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_SCOPES=project nwo="" cmd_board_sync 2>&1)"
  check "sync adds, sets and adopts, counting each apart" "$(tail -1 <<<"$claim_got")" \
    "board: 1 added, 1 set (no Status), 2 adopted from Backlog, 0 closed to Done, 0 skipped, 0 failed (5 open issues)"
  check "sync's calls" "$(cat "$claim_log")" \
    "project item-add 7 --owner marola-dev --url u950
project item-edit --id I-910 --project-id PVT_test --field-id PVTSSF_s --single-select-option-id o-ready
project item-edit --id I-951 --project-id PVT_test --field-id PVTSSF_s --single-select-option-id o-prog
project item-edit --id I-953 --project-id PVT_test --field-id PVTSSF_s --single-select-option-id o-triage"
  check "the card a human moved is never in the calls" "$(grep -c 'I-952' "$claim_log" || true)" "0"
  # The first real run moves every card off the auto-add default at once, so it names each one.
  check "every change is named per issue, not just counted" \
    "$(grep -E '^  #(910|951)' <<<"$claim_got" | tr -s ' ' | sed 's/^ //' | tr '\n' '|')" \
    "#910 Backlog → Ready|#951 (no Status) → In progress|"
  : > "$claim_log"
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_ITEMS=items-synced.json STUB_SCOPES=project nwo="" cmd_board_sync 2>&1)"
  check "a second run adopts nothing — the cards hold a real Status now" "$(tail -1 <<<"$claim_got")" \
    "board: in sync (5 open issues)"
  check "and writes nothing at all" "$(cat "$claim_log")" ""

  echo
  echo "-- board sync also moves a closed issue's card to Done — the fallback for the built-in" \
       "\"Item closed\" workflow, which missed 8 issues on 2026-09-30 (MIP-0063 §4.4/§5.2) --"
  : > "$claim_log"
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_ITEMS=items-synced.json STUB_CLOSED_ITEMS=closed-items-two.json STUB_SCOPES=project nwo="" cmd_board_sync 2>&1)"
  check "board sync moves a closed issue's card to Done" "$(tail -1 <<<"$claim_got")" \
    "board: 0 added, 0 set (no Status), 0 adopted from Backlog, 2 closed to Done, 0 skipped, 0 failed (5 open issues)"
  check "it says so per issue, including one that carried no Status at all" \
    "$(grep -E '^  #(960|962)' <<<"$claim_got" | tr -s ' ' | sed 's/^ //' | tr '\n' '|')" \
    "#960 In progress → Done|#962 (no Status) → Done|"
  check "sync's calls carry the Done option id, for both and only those two" \
    "$(cat "$claim_log")" \
    "project item-edit --id I-960 --project-id PVT_test --field-id PVTSSF_s --single-select-option-id o-done
project item-edit --id I-962 --project-id PVT_test --field-id PVTSSF_s --single-select-option-id o-done"
  check "a closed card already Done is left alone" "$(grep -c 'I-961' "$claim_log" || true)" "0"
  check "an open issue's chosen Status is left alone" "$(grep -c 'I-952' "$claim_log" || true)" "0"
  # #910 is open but also appears in this run's is:closed result — still never written.
  check "an item is:closed answered with that is still open is never written, even under its own id" \
    "$(grep -c 'I-910dup' "$claim_log" || true)" "0"
  : > "$claim_log"
  dry=1
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_ITEMS=items-synced.json STUB_CLOSED_ITEMS=closed-items-two.json STUB_SCOPES=project nwo="" cmd_board_sync 2>&1)"
  dry=0
  check "--dry-run edits nothing" "$(cat "$claim_log")" ""
  check "and still reports the two it would move, saying it did not" "$(tail -1 <<<"$claim_got")" \
    "board: 0 added, 0 set (no Status), 0 adopted from Backlog, 2 closed to Done, 0 skipped, 0 failed (5 open issues) (--dry-run: nothing was written)"

  # Sync before setup: the option an issue's state calls for does not exist yet. That is a missing
  # prerequisite, not a failed write — counted apart, said once rather than once per issue, and
  # still a nonzero exit because the work did not happen.
  : > "$claim_log"
  claim_got=""
  claim_got="$( ( PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_FIELDS=fields-sparse.json STUB_SCOPES=project nwo="" cmd_board_sync ) 2>&1; echo "rc=$?")"
  check "an option the board lacks is skipped, not failed" \
    "$(grep '^board:' <<<"$claim_got")" \
    "board: 1 added, 1 set (no Status), 1 adopted from Backlog, 0 closed to Done, 1 skipped, 0 failed (5 open issues)"
  check "and skipping is a nonzero exit — the sync did not do its job" "$(tail -1 <<<"$claim_got")" "rc=1"
  check "the missing option is named once, not once per issue" \
    "$(grep -c 'has no "Triage" option' <<<"$claim_got" || true)" "1"
  # On stderr with the diagnostic, not only in the stdout summary: whoever is watching one is
  # usually not watching the other.
  check "and the diagnostic carries how many issues it left alone" \
    "$(grep -c '1 issue(s) left alone' <<<"$claim_got" || true)" "1"
  case "$claim_got" in
    *"board setup\` first"*) echo "ok: and it names the command that creates it" ;;
    *) echo "FAILED: the skip diagnostic does not point at board setup" >&2; failed=1 ;;
  esac
  : > "$claim_log"
  claim_got=""
  # A subshell, not just `|| …`: cmd_board_sync refuses with `exit`, which would take this
  # self-test down with it rather than being caught.
  ( PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" STUB_SCOPES=read:project \
    nwo="" cmd_board_sync ) >/dev/null 2>&1 || claim_got=refused
  check "sync fails outright without \`project\` scope, unlike claim" "$claim_got" "refused"
  check "and writes nothing on the way" "$(cat "$claim_log")" ""
  dry=1
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_SCOPES=read:project nwo="" cmd_board_sync 2>/dev/null | tail -1)"
  dry=0
  check "--dry-run still shows the plan without the scope — the reads only need read:project" \
    "$claim_got" "board: 1 added, 1 set (no Status), 2 adopted from Backlog, 0 closed to Done, 0 skipped, 0 failed (5 open issues) (--dry-run: nothing was written)"
  check "and still writes nothing" "$(cat "$claim_log")" ""

  : > "$claim_log"
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    nwo="" cmd_board_gates 2>&1)"
  check "the gate already filed is skipped, the other four are created" "$(tail -1 <<<"$claim_got")" \
    "gates: 4 filed, 1 already there, 0 failed"
  check "each gate carries its own phase label" "$(grep -c -- '--label phase/' "$claim_log" || true)" "4"
  check "the phase PHASES.md marks done is closed, not left blocking its own issues" \
    "$(grep -c 'issue close --repo marola-dev/marola 950 --reason completed' "$claim_log" || true)" "1"
  dry=1
  : > "$claim_log"
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    nwo="" cmd_board_gates 2>&1)"
  dry=0
  check "--dry-run files nothing at all" "$(cat "$claim_log")" ""
  check "and still reports what it would file, saying it did not" "$(tail -1 <<<"$claim_got")" \
    "gates: 4 filed, 1 already there, 0 failed (--dry-run: nothing was written)"

  echo
  echo "-- board setup: the Status options and the four views of §5.2 --"
  local setup_have setup_plan setup_views
  setup_have='[{"id":"o-backlog","name":"Backlog","color":"GREEN","description":"not started"},
    {"id":"o-ready","name":"Ready","color":"BLUE","description":"ready"},
    {"id":"o-prog","name":"In progress","color":"YELLOW","description":"wip"},
    {"id":"o-rev","name":"In review","color":"PURPLE","description":"in review"},
    {"id":"o-done","name":"Done","color":"ORANGE","description":"done"}]'
  setup_plan="$(status_options_plan "$setup_have" "$(board_status_wanted)")"
  # updateProjectV2Field replaces the whole option list, so an option missing from what is sent is
  # deleted — together with the Status of every item carrying it. `Backlog` is not in §5.2 and 21
  # items are already on this board.
  check "every existing option survives the plan, with its own id" \
    "$(jq -r '[.[] | select(.id != null) | .name] | join(",")' <<<"$setup_plan")" \
    "Backlog,Ready,In progress,In review,Done"
  check "only the two §5.2 options the board lacks are added, and with no id" \
    "$(jq -r '[.[] | select(has("id") | not) | .name] | join(",")' <<<"$setup_plan")" "Triage,Spec"
  check "an existing option is not added a second time" \
    "$(jq -r '[.[] | select(.name == "In progress")] | length' <<<"$setup_plan")" "1"
  check "a description containing a colon survives the parse" \
    "$(jq -r '.[] | select(.name == "Triage") | .description' <<<"$setup_plan")" \
    "Filed, not yet specified or sized (MIP-0063 §5.2)"
  # Compared whole: `{id}` on an option that has none yields `"id": null`, which a length check
  # never sees and updateProjectV2Field rejects.
  check "nothing to add leaves the list exactly as it was" \
    "$(status_options_plan "$setup_plan" "$(board_status_wanted)" | jq -S .)" "$(jq -S . <<<"$setup_plan")"

  setup_views='[{"id":"V1","name":"Current iteration","filter":"iteration:@current"},
    {"id":"V5","name":"In review","filter":"status:\"In review\""},
    {"id":"V6","name":"My items","filter":"assignee:@me"}]'
  check "all four of §5.2's views are missing from GitHub's template set" \
    "$(board_views_plan "$setup_views" "$(board_views_wanted)" | cut -f1,2 | tr '\t' ' ' | tr '\n' '|')" \
    "create Triage|create Now|create Agent queue|create Good first issues|"
  # By id, and case-insensitively: a name match that is not is a second copy of a view on the
  # live board. Grepping the plan for the template's *names* can never fail — it iterates $wanted.
  check "an existing view is matched whatever its case, and the template's ids stay out of the plan" \
    "$(board_views_plan '[{"id":"V1","name":"Current iteration","filter":"iteration:@current"},
        {"id":"V9","name":"AGENT QUEUE","filter":null}]' "$(board_views_wanted)" \
       | cut -f1,2 | tr '\t' ' ' | tr '\n' '|')" \
    "create Triage|create Now|filter V9|create Good first issues|"
  # A filter a maintainer narrowed by hand is theirs; setup reports the difference and stops there.
  check "a view that already exists with another filter is reported, not overwritten" \
    "$(board_views_plan '[{"id":"V9","name":"Agent queue","filter":"label:\"bug\""}]' \
       "$(printf 'Agent queue\tTABLE_LAYOUT\tlabel:"agent-ready"')")" \
    "$(printf 'differs\tAgent queue\tlabel:"bug"\tlabel:"agent-ready"')"
  check "a view that already matches yields no line at all" \
    "$(board_views_plan '[{"id":"V9","name":"Agent queue","filter":"label:\"agent-ready\""}]' \
       "$(printf 'Agent queue\tTABLE_LAYOUT\tlabel:"agent-ready"')")" ""

  # The half-finished two-phase create: the view exists because `createProjectV2View` landed, the
  # `updateProjectV2View` that filters it did not. That is this script's own unfinished work, not a
  # maintainer's choice, so the next run finishes it instead of reporting it forever.
  check "a view this script created but never filtered is finished, not reported" \
    "$(board_views_plan '[{"id":"V7","name":"Triage","filter":null}]' \
       "$(printf 'Triage\tTABLE_LAYOUT\tstatus:"Triage"')")" \
    "$(printf 'filter\tV7\tstatus:"Triage"\tTriage')"
  check "an empty-string filter reads the same as a null one" \
    "$(board_views_plan '[{"id":"V7","name":"Triage","filter":""}]' \
       "$(printf 'Triage\tTABLE_LAYOUT\tstatus:"Triage"')" | cut -f1)" "filter"
  # §5.2 leaves Now's milestone filter to a human, so whatever they put on it is right by
  # definition and must never be reported as a difference.
  check "the view §5.2 leaves unfiltered is never reported, whatever a human filtered it with" \
    "$(board_views_plan '[{"id":"V8","name":"Now","filter":"milestone:\"x\""}]' \
       "$(printf 'Now\tBOARD_LAYOUT\t')")" ""
  # `read` with IFS=tab collapses adjacent tabs, so an empty column in the middle of a line shifts
  # every later one — which is how a `differs` for an unfiltered view once printed the *wanted*
  # filter as the current one and `[]` as the wanted.
  check "no action line has an empty field for read to swallow" \
    "$(board_views_plan '[{"id":"V7","name":"Triage","filter":null},{"id":"V9","name":"Agent queue","filter":"label:\"bug\""}]' \
       "$(board_views_wanted)" | awk -F'\t' '{ for (i = 1; i <= NF; i++) if ($i == "") print "empty field " i " in: " $0 }')" ""

  cat > "$claim_dir/status-field.json" <<'EOF'
{"data":{"organization":{"projectV2":{"field":{"id":"PVTSSF_s","options":[
  {"id":"o-backlog","name":"Backlog","color":"GREEN","description":"not started"},
  {"id":"o-ready","name":"Ready","color":"BLUE","description":"ready"},
  {"id":"o-prog","name":"In progress","color":"YELLOW","description":"wip"},
  {"id":"o-rev","name":"In review","color":"PURPLE","description":"in review"},
  {"id":"o-done","name":"Done","color":"ORANGE","description":"done"}]}}}}}
EOF
  jq '.data.organization.projectV2.field.options += [
    {"id":"o-triage","name":"Triage","color":"GRAY","description":"t"},
    {"id":"o-spec","name":"Spec","color":"PINK","description":"s"}]' \
    "$claim_dir/status-field.json" > "$claim_dir/status-field-after.json"
  cat > "$claim_dir/views.json" <<'EOF'
{"data":{"organization":{"projectV2":{"views":{"nodes":[
  {"id":"V1","name":"Current iteration","filter":"iteration:@current"},
  {"id":"V5","name":"In review","filter":"status:\"In review\""},
  {"id":"V6","name":"My items","filter":"assignee:@me"}]}}}}}
EOF
  # What a re-read right after `createProjectV2View` returns: the view exists, unfiltered. The
  # filters arrive only with the second mutation, which is the window I1 is about.
  jq '.data.organization.projectV2.views.nodes += [
    {"id":"V7","name":"Triage","filter":null},
    {"id":"V8","name":"Now","filter":null},
    {"id":"V9","name":"Agent queue","filter":null},
    {"id":"V10","name":"Good first issues","filter":null}]' \
    "$claim_dir/views.json" > "$claim_dir/views-after.json"
  jq '.data.organization.projectV2.views.nodes |= map(
        if .name == "Triage" then .filter = "status:\"Triage\""
        elif .name == "Agent queue" then .filter = "label:\"agent-ready\""
        elif .name == "Good first issues" then .filter = "label:\"good first issue\""
        else . end)' "$claim_dir/views-after.json" > "$claim_dir/views-done.json"

  : > "$claim_log"
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_SCOPES=project nwo="" cmd_board_setup 2>&1)"
  check "setup sends the whole option list, existing ones first" "$(head -1 "$claim_log")" \
    "graphql-input Backlog,Ready,In progress,In review,Done,Triage,Spec"
  check "setup creates the four views with their layouts" \
    "$(grep '^create-view' "$claim_log" | tr '\n' '|')" \
    "create-view Triage TABLE_LAYOUT|create-view Now BOARD_LAYOUT|create-view Agent queue TABLE_LAYOUT|create-view Good first issues TABLE_LAYOUT|"
  # CreateProjectV2ViewInput has no `filter` field (introspected 2026-09-28), so each filter is a
  # second mutation against an id that only exists after the create.
  check "and sets each filter afterwards, skipping the one §5.2 leaves to a human" \
    "$(grep '^set-filter' "$claim_log" | tr '\n' '|')" \
    "set-filter V7 status:\"Triage\"|set-filter V9 label:\"agent-ready\"|set-filter V10 label:\"good first issue\"|"
  check "the whole option payload is printed, not just the path of a temp file that is deleted" \
    "$(jq -r '.variables.options[-1].name' <<<"$(sed -n '/^  {/,/^  }/p' <<<"$claim_got")")" "Spec"
  # §5.7: the board's `Deliverable` field, created the same idempotent way as the Status options.
  check "the board has no Deliverable field yet, so setup creates one" \
    "$(grep -c '^project field-create' "$claim_log" || true)" "1"
  check "with the right name and data type" "$(grep '^project field-create' "$claim_log")" \
    "project field-create 7 --owner marola-dev --name Deliverable --data-type TEXT"
  case "$claim_got" in
    *'Deliverable: field created'*) echo "ok: and says so" ;;
    *) echo "FAILED: setup said nothing about creating the Deliverable field" >&2; failed=1 ;;
  esac
  # The board now has it — every later run in this section reads a fixture reflecting that,
  # the same way status-field-after.json models Status's own options after setup ran once.
  jq '.fields += [{"id":"PVTF_deliverable","name":"Deliverable","type":"ProjectV2Field"}]' \
    "$claim_dir/fields.json" > "$claim_dir/w" && mv "$claim_dir/w" "$claim_dir/fields.json"
  # The half-finished two-phase create, as its own run: the four views are there because
  # `createProjectV2View` landed, unfiltered because `updateProjectV2View` did not. Nothing is
  # created; the three filters are finished. Before I1 this state was classified `differs` and
  # never healed.
  : > "$claim_log"
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_SCOPES=project STUB_AFTER=1 STUB_VIEWS=views-after.json nwo="" cmd_board_setup 2>&1)"
  check "a half-finished create is healed, not created again" "$(grep -c '^create-view' "$claim_log" || true)" "0"
  check "and its filter is applied on the next run" "$(grep '^set-filter' "$claim_log" | tr '\n' '|')" \
    "set-filter V7 status:\"Triage\"|set-filter V9 label:\"agent-ready\"|set-filter V10 label:\"good first issue\"|"
  case "$claim_got" in
    *'view "Triage" has no filter — applying §5.2'*) echo "ok: and it says which view it is finishing" ;;
    *) echo "FAILED: healing said nothing about the view it fixed:" >&2; sed 's/^/  /' <<<"$claim_got" >&2; failed=1 ;;
  esac

  : > "$claim_log"
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_SCOPES=project STUB_AFTER=1 STUB_VIEWS=views-done.json nwo="" cmd_board_setup 2>&1)"
  check "a second run changes nothing" "$(cat "$claim_log")" ""
  check "and says so" "$(grep -c -E 'all of §5.2.s options are there|four are all there' <<<"$claim_got" || true)" "2"
  case "$claim_got" in
    *'Deliverable: already a field on the board'*) echo "ok: and the Deliverable field is not recreated" ;;
    *) echo "FAILED: setup did not report Deliverable as already there" >&2; failed=1 ;;
  esac
  claim_got=""
  ( PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" STUB_SCOPES=read:project \
    nwo="" cmd_board_setup ) >/dev/null 2>&1 || claim_got=refused
  check "setup refuses without \`project\` scope, like sync" "$claim_got" "refused"

  # A fine-grained PAT sends no X-OAuth-Scopes header, so token_scopes greps for something that is
  # not there and exits nonzero with empty output. These cover that much and the branch it feeds.
  # They do **not** cover the `|| true` at its call site: errexit is suppressed through every
  # guarded caller, so the whole suite passes with that guard removed. Verified, not assumed.
  local scope_rc=0
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_NOHDR=1 token_scopes || true)"
  check "no scope header yields no scopes" "$claim_got" ""
  ( PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_NOHDR=1 token_scopes >/dev/null ) || scope_rc=$?
  check "and a nonzero status its callers must absorb" "$scope_rc" "1"
  claim_got="$(PATH="$claim_dir/bin:$PATH" STUB_DIR="$claim_dir" STUB_LOG="$claim_log" \
    STUB_NOHDR=1 "$root/scripts/issues.sh" --dry-run board setup 2>&1; echo "rc=$?")"
  check "and such a token is unknown, not refused: setup runs to the end" \
    "$(tail -1 <<<"$claim_got")" "rc=0"
  case "$claim_got" in
    *"no X-OAuth-Scopes header"*) echo "ok: and it says why it went ahead anyway" ;;
    *) echo "FAILED: expected the fine-grained-PAT warning, got:" >&2; sed 's/^/  /' <<<"$claim_got" >&2; failed=1 ;;
  esac

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
  ready) shift; cmd_ready "$@" ;;
  queue) shift; cmd_queue "$@" ;;
  tasks-to-issues) shift; cmd_tasks_to_issues "$@" ;;
  claim) shift; cmd_claim "$@" ;;
  milestone)
    case "${2:-}" in
      new) shift 2; cmd_milestone_new "$@" ;;
      *) echo "issues.sh milestone: unknown subcommand: ${2:-<none>}" >&2; usage >&2; exit 1 ;;
    esac
    ;;
  board)
    case "${2:-}" in
      sync) shift 2; cmd_board_sync "$@" ;;
      setup) shift 2; cmd_board_setup "$@" ;;
      gates) shift 2; cmd_board_gates "$@" ;;
      *) echo "issues.sh board: unknown subcommand: ${2:-<none>}" >&2; usage >&2; exit 1 ;;
    esac
    ;;
  ""|*) usage >&2; exit 1 ;;
esac
