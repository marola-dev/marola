#!/usr/bin/env bash
# Cache-bust the built site so index.html and app.js/style.css can never be served from two
# different builds (fix/site-smoke-panel-null). GitHub Pages' CDN caches every file independently
# — confirmed live 2026-09-05, `curl -sI https://h0ffmann.github.io/marola/{,app.js}` both answer
# `cache-control: max-age=600` — so a visitor can get a freshly-deployed index.html paired with an
# app.js still served from a 10-minute-old edge/browser cache, or the reverse. That skew is how a
# now-fixed regression (PR #37 dropped the `#smoke` element `app.js` still expected) surfaced only
# "from time to time": whichever file's cache had not yet turned over.
#
# Runs against the *built* site/dist/index.html (SiteBuilder's verbatim copy of site/static/), never
# against the source in site/static/ — that file stays plain so it matches byte-for-byte what
# .claude/skills/site-frontend/site_check.js and any test fixture read. `just site-build` and
# .github/workflows/site.yml both call this right after the pipeline writes site/dist.
#
# Usage: scripts/stamp_site_version.sh [dist-dir]   (default: site/dist)
set -euo pipefail

dist="${1:-site/dist}"
index="$dist/index.html"
[ -f "$index" ] || { echo "stamp_site_version: no $index — build the site first" >&2; exit 1; }

version="$(git rev-parse --short HEAD 2>/dev/null || date -u +%Y%m%d%H%M%S)"

sed -i -E \
  -e "s#(href=\"style\.css)\"#\\1?v=${version}\"#" \
  -e "s#(src=\"app\.js)\"#\\1?v=${version}\"#" \
  "$index"

echo "stamped $index with ?v=$version"
