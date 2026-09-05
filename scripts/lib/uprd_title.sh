#!/usr/bin/env bash
# uprd_title — shared title-capping helper for scripts/uprd.sh and scripts/uprds.sh. Sourced, not
# run directly (no execute bit) — the shebang above is only so shellcheck knows the dialect.
# Source it, then: title="$(cap_title "$subject")"
#
# GitHub PR titles generated from this repo's commit subjects can run past 100 chars (this repo's
# commit style favours one long, descriptive subject per task — see AGENTS.md). cap_title keeps
# the PR title itself skimmable: cut at the first natural break point that still fits 70 chars,
# preferring (in order) an em-dash separator, a colon after a "MIP-NNNN:" / "MIP-NNNN task K:" prefix, then a
# sentence/clause boundary; hard-truncate with an ellipsis only as a last resort. Prints a warning
# to stderr whenever a cut happened, so the author knows to sanity-check or retitle the PR.
cap_title() {
  python3 - "$1" <<'PY'
import re
import sys

s = sys.argv[1]
cap = 70

if len(s) <= cap:
    print(s)
    sys.exit(0)

m = re.match(r"^(MIP-\d{4}(?: task \d+)?: )", s)
prefix_len = len(m.group(1)) if m else 0
rest = s[prefix_len:]


def try_cut(sep):
    if sep in rest:
        candidate = rest.split(sep, 1)[0]
        if candidate.strip() and prefix_len + len(candidate) <= cap:
            return s[:prefix_len] + candidate
    return None


cut = try_cut(" — ")
# Only treat a second ": " as a break point when the subject actually starts with a MIP-style
# prefix — otherwise an ordinary "scope: description" commit subject (very common outside MIPs)
# would get chopped down to just its scope, e.g. "docs: fix broken links..." -> "docs".
if not cut and prefix_len:
    cut = try_cut(": ")
if not cut:
    for sep in (". ", "; ", ", "):
        cut = try_cut(sep)
        if cut:
            break

if not cut:
    budget = cap - 1  # room for the ellipsis
    truncated = s[:budget]
    if " " in truncated:
        truncated = truncated.rsplit(" ", 1)[0]
    cut = truncated.rstrip(".,;: ") + "…"

print(cut)
print(
    f"uprd: title cut from {len(s)} to {len(cut)} chars — check it reads well, retitle if not",
    file=sys.stderr,
)
PY
}
