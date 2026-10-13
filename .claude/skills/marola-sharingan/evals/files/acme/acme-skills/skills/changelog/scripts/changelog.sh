#!/usr/bin/env bash
set -euo pipefail
last="$(git describe --tags --abbrev=0 2>/dev/null || git rev-list --max-parents=0 HEAD)"
git log --format='%s' "$last"..HEAD | sort | awk -F: '{ print $1 ": " substr($0, index($0, ":") + 2) }'
