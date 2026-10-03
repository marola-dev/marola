#!/usr/bin/env bash
# repos_manifest — scripts/lib: parse mkdocs/repos.yml's fixed shape (MIP-0070 §5.5), shared by
# scripts/prepare-docs.sh and scripts/fetch-api-docs.sh. No pyyaml on this host (AGENTS.md) and
# the repo avoids adding one — the same trade scripts/issues.sh's manifest_json makes for
# .github/labels.yml.

# repos_manifest <file> -> lines of "name<TAB>mount<TAB>source" (mount defaulted to repos/<name>/
# when omitted, always slash-terminated; source empty for a submodule, or flake-lock, MIP-0074
# §5.3). A file with only comments/blank lines yields nothing.
repos_manifest() {
  local file="$1"
  [ -f "$file" ] || { echo "repos_manifest: manifest not found: $file" >&2; return 1; }
  awk '
    function val(line) {
      sub(/\r$/, "", line)
      sub(/^[^:]*:[ \t]*/, "", line)
      sub(/[ \t]+$/, "", line)
      if (line ~ /^".*"$/) line = substr(line, 2, length(line) - 2)
      return line
    }
    function flush(   m) {
      if (name == "") return
      m = mount
      if (m == "") m = "repos/" name "/"
      if (m !~ /\/$/) m = m "/"
      printf "%s\t%s\t%s\n", name, m, source
      name = ""; mount = ""; source = ""
    }
    /^[ \t]*$/ || /^[ \t]*#/ { next }
    /^- name:/  { flush(); name = val($0); next }
    /^  mount:/ {
      if (name == "") { printf "repos_manifest: %s:%d: mount before any `- name:`\n", FILENAME, NR > "/dev/stderr"; _abort = 1; exit 1 }
      mount = val($0); next
    }
    /^  source:/ {
      if (name == "") { printf "repos_manifest: %s:%d: source before any `- name:`\n", FILENAME, NR > "/dev/stderr"; _abort = 1; exit 1 }
      source = val($0)
      if (source != "flake-lock") { printf "repos_manifest: %s:%d: source must be flake-lock, got: %s\n", FILENAME, NR, source > "/dev/stderr"; _abort = 1; exit 1 }
      next
    }
    { printf "repos_manifest: %s:%d: unrecognised line: %s\n", FILENAME, NR, $0 > "/dev/stderr"; _abort = 1; exit 1 }
    END { if (!_abort) flush() }
  ' "$file"
}
