#!/usr/bin/env bash
set -euo pipefail
for f in "$@"; do sed -i 's/[[:space:]]*$//' "$f"; done
