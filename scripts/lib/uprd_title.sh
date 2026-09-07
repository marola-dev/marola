#!/usr/bin/env bash
# uprd_title — shared title-capping helper for scripts/uprd.sh, scripts/uprds.sh and
# .github/workflows/pr-body.yml's title step. Sourced, not run directly (no execute bit) — the
# shebang above is only so shellcheck knows the dialect.
# Source it, then: title="$(cap_title "$subject" "$mip_ref")"   # mip_ref from scripts/lib/mip_ref.sh, or ""
#
# GitHub PR titles generated from this repo's commit subjects can run past 100 chars (this repo's
# commit style favours one long, descriptive subject per task — see AGENTS.md). cap_title keeps
# the PR title itself skimmable: cut at the first natural break point that still fits 70 chars,
# preferring (in order) an em-dash separator, a colon after a "MIP-NNNN:" / "MIP-NNNN task K:" prefix, then a
# sentence/clause boundary; hard-truncate with an ellipsis only as a last resort. Prints a warning
# to stderr whenever a cut happened, so the author knows to sanity-check or retitle the PR.
#
# Second argument, `mip_ref` (e.g. "MIP-0025", the same value scripts/uprd.sh's PR-body table
# links): when given and the subject doesn't already start with it — case-insensitively, since
# this repo's actual commit-subject convention is overwhelmingly lowercase ("mip-0025 task 2: …",
# confirmed by `git log --oneline --all | grep -iE '^[a-f0-9]+ mip-[0-9]'`, 2026-09-07 — only one
# historical outlier used uppercase) — cap_title prepends `"$mip_ref: "` before capping, so an
# agent forgetting the prefix in the commit subject still produces a PR title that starts with the
# real MIP number, matching what the PR body's own MIP table row already links.
cap_title() {
  python3 - "$1" "${2:-}" <<'PY'
import re
import sys

s = sys.argv[1]
mip_ref = sys.argv[2] if len(sys.argv) > 2 else ""
cap = 70

# Track whether the subject already had a genuine "MIP-NNNN[ task K]: " prefix of its own *before*
# any injection below — that's what makes a second ": " a safe cut point (see try_cut below). An
# injected prefix glued onto an unrelated "scope: description" subject (e.g. "finetune: scale...")
# must not enable that same heuristic, or it cuts at the *scope's* colon instead — confirmed live,
# 2026-09-07: "MIP-0025: finetune: scale build_dataset.py's..." cut down to "MIP-0025: finetune".
had_own_prefix = bool(re.match(r"^MIP-\d{4}(?: task \d+)?: ", s, re.IGNORECASE))

if mip_ref and not re.match(rf"^{re.escape(mip_ref)}\b", s, re.IGNORECASE):
    s = f"{mip_ref}: {s}"

if len(s) <= cap:
    print(s)
    sys.exit(0)

m = re.match(r"^(MIP-\d{4}(?: task \d+)?: )", s, re.IGNORECASE)
prefix_len = len(m.group(1)) if m else 0
rest = s[prefix_len:]


def try_cut(sep):
    if sep in rest:
        candidate = rest.split(sep, 1)[0]
        if candidate.strip() and prefix_len + len(candidate) <= cap:
            return s[:prefix_len] + candidate
    return None


cut = try_cut(" — ")
# Only treat a second ": " as a break point when the subject *natively* started with a MIP-style
# prefix (had_own_prefix, computed before injection above) — otherwise an ordinary "scope:
# description" commit subject (very common outside MIPs, and left completely as-is when no
# mip_ref was injected either) would get chopped down to just its scope, e.g. "docs: fix broken
# links..." -> "docs", or an injected "MIP-0025: finetune: scale..." -> "MIP-0025: finetune".
if not cut and had_own_prefix:
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
