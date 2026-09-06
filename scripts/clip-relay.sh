#!/usr/bin/env bash
# clip-relay — host-side half of the write-only clipboard bridge for `just jail-claude
# MAROLA_JAIL_CLIPBOARD=1`. Not meant to be run by hand: the `jail-claude` recipe starts it in the
# background before launching ai-jail (after creating the FIFO it reads from) and stops it — which
# removes that FIFO — when `claude` exits. See scripts/clip.sh for the jail-side half that writes
# into this FIFO, and AGENTS.md's ai-jail paragraph for why the whole bridge is write-only by
# construction: this script only ever reads bytes out of the FIFO and hands them to a clipboard
# tool; it never reads the clipboard back into anything the jailed process can see.
#
#   scripts/clip-relay.sh .tmp/clip.fifo
#
# Loop, once per payload: block open()ing the FIFO for read (a clip.sh write is what unblocks it),
# read at most 64 KiB (clip.sh already enforces that cap before writing; this is a second,
# independent enforcement in case that ever changes) until the writer closes its end (EOF), hand
# those bytes to whichever clipboard tool the host has, repeat. Never echoes the payload to the
# terminal.
#
# Detection order matches justfile's `_clip` recipe (used by `just context-mips`): wl-copy under
# Wayland, else xclip under X11. `MAROLA_CLIP_SINK` overrides both with an arbitrary shell command
# that reads the payload from stdin — this is how the test transcript in this change's PR exercises
# the relay without a real display (there is none inside the jail this was written in).
#
# `set -m` (job control) puts each payload's reader in its own process group, so cleanup can kill
# that whole group at once — without it, a reader still blocked open()ing the FIFO (the common case:
# torn down between payloads, nobody writing) leaks as an orphan once this script exits, because
# killing this script's own PID alone never reaches its grandchild. Verified empirically: the naive
# version (trap without `set -m` + backgrounded `wait`) also hangs the whole script on a plain
# foreground `cat <fifo>` — bash only runs a trap between simple commands, not while blocked inside
# one, unless the blocking command is an explicitly awaited background job.
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
