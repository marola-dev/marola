#!/usr/bin/env bash
# issues — the command surface for MIP-0063's GitHub tracking standard. One subcommand family
# per task of the stack; this is `labels sync`.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest_default="$root/.github/labels.yml"

usage() {
  cat <<'EOF'
usage: issues.sh [--dry-run] <command> [args]
       issues.sh --self-test | --help

commands:
  labels sync [--prune] [--manifest FILE]
      Reconcile GitHub's labels against .github/labels.yml: create what is missing, edit what
      differs, report what is on the repo but not in the manifest. Orphans are only deleted
      with --prune.

options:
  --dry-run     print the mutating `gh` calls instead of making them (the repo's current
                labels are still read, so this needs a login)
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
      sub(/^[^:]*:[ \t]*/, "", line)
      sub(/[ \t]+$/, "", line)
      if (line ~ /^".*"$/) line = substr(line, 2, length(line) - 2)
      return line
    }
    function flush() {
      if (name == "") return
      if (color == "" || !have_desc) {
        printf "issues.sh: %s:%d: label \"%s\" is missing color or description\n", file, nr_name, name > "/dev/stderr"
        _abort = 1; exit 1
      }
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
# Colour is compared case-insensitively: GitHub stores whatever case it was created with, and a
# label added through the UI in uppercase is the same label, not a change to apply.
labels_diff() {
  jq -n --argjson m "$1" --argjson r "$2" '
    def norm: {name, color: (.color // "" | ascii_downcase), description: (.description // "")};
    ($m | map(norm)) as $M | ($r | map(norm)) as $R |
    ($M | map({key: .name, value: .}) | from_entries) as $MB |
    ($R | map({key: .name, value: .}) | from_entries) as $RB |
    {
      add:    [ $M[] | select($RB[.name] == null) ],
      update: [ $M[] | select($RB[.name] != null)
                     | select($RB[.name].color != .color or $RB[.name].description != .description) ],
      orphan: [ $R[] | select($MB[.name] == null) ]
    }'
}

# labels_plan <diff-json> <prune 0|1> -> one TSV action per line: create|edit|delete, name,
# colour, description. Without --prune an orphan yields no line at all, which is what makes
# "sync never deletes by accident" a property of the plan rather than of the caller.
labels_plan() {
  jq -r --argjson prune "$2" '
    ( .add[]    | ["create", .name, .color, .description] ),
    ( .update[] | ["edit",   .name, .color, .description] ),
    ( if $prune == 1 then (.orphan[] | ["delete", .name, "", ""]) else empty end )
    | @tsv' <<<"$1"
}

# --- live side ---

require_gh() {
  command -v gh >/dev/null || { echo "issues.sh: gh is not installed" >&2; exit 1; }
  gh auth status >/dev/null 2>&1 || {
    echo "issues.sh: gh is not logged in. Inside ai-jail there is no login and none can be acquired (AGENTS.md) — run this from the host, or use --dry-run." >&2
    exit 1
  }
}

cmd_labels_sync() {
  local prune=0 manifest="$manifest_default"
  while [ $# -gt 0 ]; do
    case "$1" in
      --prune) prune=1; shift ;;
      --manifest) manifest="$2"; shift 2 ;;
      *) echo "issues.sh labels sync: unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
  done

  local mjson rjson diff plan
  mjson="$(manifest_json "$manifest")"
  require_gh
  rjson="$(gh label list --limit 200 --json name,color,description)"
  diff="$(labels_diff "$mjson" "$rjson")"
  plan="$(labels_plan "$diff" "$prune")"

  local orphans
  orphans="$(jq -r '.orphan[].name' <<<"$diff")"
  if [ -n "$orphans" ] && [ "$prune" -eq 0 ]; then
    echo "orphaned on the repo, not in $(basename "$manifest") — re-run with --prune to delete:" >&2
    sed 's/^/  /' <<<"$orphans" >&2
  fi

  if [ -z "$plan" ]; then
    local n_orphan; n_orphan="$(jq -r '.orphan | length' <<<"$diff")"
    if [ "$n_orphan" -gt 0 ]; then
      echo "labels: nothing to create or edit ($(jq 'length' <<<"$mjson") in the manifest); $n_orphan orphaned, listed above"
    else
      echo "labels: in sync ($(jq 'length' <<<"$mjson") in the manifest)"
    fi
    return 0
  fi

  local action name color desc
  while IFS=$'\t' read -r action name color desc; do
    case "$action" in
      create) run gh label create "$name" --color "$color" --description "$desc" ;;
      edit)   run gh label edit   "$name" --color "$color" --description "$desc" ;;
      delete) run gh label delete "$name" --yes ;;
    esac
  done <<<"$plan"
}

dry=0
# %q, not "$*": a label description always contains spaces, so an unquoted echo prints a line
# that looks copy-pasteable and isn't.
run() {
  if [ "$dry" -eq 1 ]; then printf '+'; printf ' %q' "$@"; printf '\n'; return 0; fi
  "$@" >/dev/null
  printf ' %q' "$@"; printf '\n'
}

# --- self-test ---

self_test() {
  local failed=0 tmp got want
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN

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
  got="$(labels_plan "$diff" 1)"
  check "with --prune the orphan is a delete" "$(grep '^delete' <<<"$got" | cut -f2)" "layer/azure"

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
  echo "-- scripts/lib/pr_labels.sh agrees with the manifest --"
  # `just pr-label` creates its taxonomy with `gh label create --force` (ensure_pr_labels), so a
  # second copy of these colours exists. If the two disagree, pr-label and labels-sync overwrite
  # each other on every run and neither file is the source of truth any more.
  # shellcheck source=scripts/lib/pr_labels.sh
  source "$root/scripts/lib/pr_labels.sh"
  local entry n c d want_c want_d
  for entry in "${PR_LABEL_TAXONOMY[@]}"; do
    n="${entry%%:*}"; c="${entry#*:}"; c="$(tr 'A-Z' 'a-z' <<<"${c%%:*}")"; d="${entry#*:*:}"
    want_c="$(jq -r --arg n "$n" '.[] | select(.name == $n) | .color' <<<"$got")"
    want_d="$(jq -r --arg n "$n" '.[] | select(.name == $n) | .description' <<<"$got")"
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
  ""|*) usage >&2; exit 1 ;;
esac
