#!/usr/bin/env bash
# clip-relay — host-side half of the write-only clipboard bridge for `just jail-claude
# MAROLA_JAIL_CLIPBOARD=1`.
set -euo pipefail
set -m

fifo="${1:?usage: clip-relay.sh <fifo-path>}"
cap=65536 # 64 KiB, independent of clip.sh's own cap — see header

if [ -n "${MAROLA_CLIP_SINK:-}" ]; then
    sink_desc="test sink (MAROLA_CLIP_SINK)"
    run_sink() { eval "$MAROLA_CLIP_SINK"; }
elif command -v wl-copy >/dev/null 2>&1 && [ -n "${WAYLAND_DISPLAY:-}" ]; then
    sink_desc="wl-copy"
    run_sink() { wl-copy; }
elif command -v xclip >/dev/null 2>&1 && [ -n "${DISPLAY:-}" ]; then
    sink_desc="xclip"
    run_sink() { xclip -selection clipboard; }
else
    echo "clip-relay: no clipboard tool/display found on the host — not starting" >&2
    exit 1
fi

echo "clipboard relay: write-only, via $sink_desc, $fifo"

pid=""
cleanup() {
    trap - EXIT INT TERM
    if [ -n "$pid" ]; then kill -TERM -- "-$pid" 2>/dev/null || true; fi
    rm -f "$fifo"
    exit 0
}
trap cleanup EXIT INT TERM

while true; do
    { head -c "$cap" <"$fifo" | run_sink; } &
    pid=$!
    wait "$pid" 2>/dev/null || true
done
