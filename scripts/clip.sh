#!/usr/bin/env bash
# clip — write-only clipboard push, from inside the ai-jail sandbox or outside it.
#
#   printf 'hello' | scripts/clip.sh     # copy stdin
#   scripts/clip.sh --text "hello"       # copy an argument instead of stdin
#   just clip <<< "hello"                # same, via the just recipe
#
# There is deliberately no `paste`/read counterpart here or in the relay this talks to
# (justfile's `jail-claude` recipe) — see AGENTS.md's ai-jail paragraph. The jail can only push
# bytes out to a host process that calls wl-copy/xclip; it has no way to ask that host process for
# the clipboard's current contents. That's the whole point: an agent running inside the jail gets
# a way to hand you a value (a generated snippet, a URL) without also gaining read access to
# whatever else is on your clipboard.
#
# Inside the jail: writes to .tmp/clip.fifo (mapped in with the rest of the repo) with a 2-second
# timeout. That FIFO only exists when the host started `just jail-claude` with
# MAROLA_JAIL_CLIPBOARD=1 — the relay loop on the host reads it and calls wl-copy/xclip for real.
# No FIFO, or nothing reading it: this prints an error and exits 1, on purpose — silent failure
# here would just be a payload that vanishes into a pipe nobody drains.
#
# Outside the jail (no .tmp/clip.fifo, since jail-claude's cleanup trap removes it on exit): falls
# back to calling wl-copy/xclip directly, same detection as justfile's `_clip` recipe, so `just
# clip` is one command that works in both places.
#
# Payload cap: 64 KiB. This is a clipboard, not a file transfer — refuses larger input before
# writing anything, jail or no jail.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
fifo="$repo_root/.tmp/clip.fifo"
cap=65536 # 64 KiB

text=""
have_text=0
if [ "${1:-}" = "--text" ]; then
    [ $# -ge 2 ] || { echo "clip: --text needs an argument" >&2; exit 1; }
    text="$2"
    have_text=1
fi

payload_file="$(mktemp)"
trap 'rm -f "$payload_file"' EXIT

if [ "$have_text" -eq 1 ]; then
    printf '%s' "$text" >"$payload_file"
else
    cat >"$payload_file"
fi

size="$(wc -c <"$payload_file")"
if [ "$size" -gt "$cap" ]; then
    echo "clip: payload is $size bytes, over the ${cap}-byte cap — not a file transfer, refusing" >&2
    exit 1
fi

if [ -p "$fifo" ]; then
    # shellcheck disable=SC2016  # single-quoted on purpose: $1 expands inside the `sh -c`, not here
    if timeout 2 sh -c 'cat > "$1"' _ "$fifo" <"$payload_file"; then
        echo "clip: copied ($size bytes) via jail relay"
        exit 0
    else
        echo "clip: no relay running — start the session with MAROLA_JAIL_CLIPBOARD=1 just jail-claude" >&2
        exit 1
    fi
fi

# No FIFO: not in a jailed session (or the relay already exited) — try the host directly.
if command -v wl-copy >/dev/null 2>&1 && [ -n "${WAYLAND_DISPLAY:-}" ]; then
    wl-copy <"$payload_file"
    echo "clip: copied ($size bytes) via wl-copy"
elif command -v xclip >/dev/null 2>&1 && [ -n "${DISPLAY:-}" ]; then
    xclip -selection clipboard <"$payload_file"
    echo "clip: copied ($size bytes) via xclip"
elif command -v pbcopy >/dev/null 2>&1; then
    pbcopy <"$payload_file"
    echo "clip: copied ($size bytes) via pbcopy"
else
    echo "clip: no relay running — start the session with MAROLA_JAIL_CLIPBOARD=1 just jail-claude" >&2
    exit 1
fi
