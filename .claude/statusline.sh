#!/usr/bin/env bash
# Project-scoped status line for marola (shared via .claude/settings.json).
# JSON fields: https://code.claude.com/docs/en/statusline . statusLine.command
# does not support ${CLAUDE_PROJECT_DIR}, so the repo root is resolved at
# runtime via `git rev-parse --show-toplevel` instead.
set -u
input=$(cat)
MODEL=$(printf '%s' "$input" | jq -r '.model.display_name // empty')
EFFORT=$(printf '%s' "$input" | jq -r '.effort.level // empty')
CUR_DIR=$(printf '%s' "$input" | jq -r '.workspace.current_dir // empty')
PCT_RAW=$(printf '%s' "$input" | jq -r '.context_window.used_percentage // empty')
COST=$(printf '%s' "$input" | jq -r '.cost.total_cost_usd // empty')
LINES_ADDED=$(printf '%s' "$input" | jq -r '.cost.total_lines_added // empty')
LINES_REMOVED=$(printf '%s' "$input" | jq -r '.cost.total_lines_removed // empty')
HIT_RATIO=$(printf '%s' "$input" | jq -r '.prompt_cache.hit_ratio // empty')
FIVE_H_PCT=$(printf '%s' "$input" | jq -r '.rate_limits.five_hour.used_percentage // empty')
FIVE_H_RESET=$(printf '%s' "$input" | jq -r '.rate_limits.five_hour.resets_at // empty')
SEVEN_D_PCT=$(printf '%s' "$input" | jq -r '.rate_limits.seven_day.used_percentage // empty')
SEVEN_D_RESET=$(printf '%s' "$input" | jq -r '.rate_limits.seven_day.resets_at // empty')
DIM='\033[2m'; RESET='\033[0m'; GREEN='\033[32m'; YELLOW='\033[33m'; RED='\033[31m'
DIRNAME="${CUR_DIR##*/}"
join_by() { local sep="$1" out=""; shift; for a in "$@"; do [ -z "$out" ] && out="$a" || out="$out$sep$a"; done; printf '%s' "$out"; }
# Reset countdown from resets_at (epoch seconds or ISO-8601) -> "XhYYm" / "YYm".
format_reset() {
    local resets_at="$1" epoch now diff h m
    [ -z "$resets_at" ] && return 0
    case "$resets_at" in
        *[!0-9]*) epoch=$(date -d "$resets_at" +%s 2>/dev/null || date -j -f '%Y-%m-%dT%H:%M:%SZ' "$resets_at" +%s 2>/dev/null) ;;
        *) epoch="$resets_at" ;;
    esac
    [ -z "${epoch:-}" ] && return 0
    now=$(date +%s); diff=$((epoch - now)); [ "$diff" -lt 0 ] && diff=0
    h=$((diff / 3600)); m=$(((diff % 3600) / 60))
    [ "$h" -gt 0 ] && printf '%dh%02dm' "$h" "$m" || printf '%dm' "$m"
}
# ---- git branch/dirty/ahead-behind, cached a few seconds under the repo's .tmp/ ----
ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
BRANCH=""; DIRTY=""; AHEAD="0"; BEHIND="0"
if [ -n "$ROOT" ]; then
    CACHE_DIR="$ROOT/.tmp"
    mkdir -p "$CACHE_DIR" 2>/dev/null || true
    KEY=$(printf '%s' "$ROOT" | cksum | cut -d' ' -f1)
    CACHE_FILE="$CACHE_DIR/statusline-git-cache-$KEY"
    CACHE_MAX_AGE=5
    cache_is_stale() {
        [ ! -f "$CACHE_FILE" ] && return 0
        local age
        age=$(( $(date +%s) - $(stat -c %Y "$CACHE_FILE" 2>/dev/null || stat -f %m "$CACHE_FILE" 2>/dev/null || echo 0) ))
        [ "$age" -gt "$CACHE_MAX_AGE" ]
    }
    if cache_is_stale; then
        b=$(git -C "$ROOT" branch --show-current 2>/dev/null)
        d=$(git -C "$ROOT" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
        behind_c=0; ahead_c=0
        if git -C "$ROOT" rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then
            read -r behind_c ahead_c < <(git -C "$ROOT" rev-list --left-right --count '@{u}...HEAD' 2>/dev/null)
        fi
        printf '%s|%s|%s|%s\n' "$b" "$d" "${ahead_c:-0}" "${behind_c:-0}" > "$CACHE_FILE"
    fi
    IFS='|' read -r BRANCH DIRTY AHEAD BEHIND < "$CACHE_FILE"
fi
# ---- line 1: model/effort, dir, branch, dirty count, ahead/behind ----
MODEL_PART="$MODEL"
[ -n "$EFFORT" ] && MODEL_PART="${MODEL_PART:+$MODEL_PART/}$EFFORT"
HEAD=""
[ -n "$MODEL_PART" ] && HEAD="${DIM}[$MODEL_PART]${RESET} "
GIT_PART=""
if [ -n "$BRANCH" ]; then
    GIT_PART=" | ${BRANCH}"
    { [ -n "${DIRTY:-}" ] && [ "${DIRTY:-0}" -gt 0 ]; } 2>/dev/null && GIT_PART="$GIT_PART *${DIRTY}"
    { [ "${AHEAD:-0}" -gt 0 ] || [ "${BEHIND:-0}" -gt 0 ]; } 2>/dev/null &&
        GIT_PART="$GIT_PART ${DIM}(+${AHEAD:-0}/-${BEHIND:-0})${RESET}"
fi
LINE1="${HEAD}${DIRNAME}${GIT_PART}"
# ---- line 2: 10-char context window bar, color-coded ----
PCT=$(printf '%s' "$PCT_RAW" | cut -d. -f1)
case "$PCT" in ''|*[!0-9]*) PCT=0 ;; esac
FILLED=$((PCT * 10 / 100)); [ "$FILLED" -gt 10 ] && FILLED=10; [ "$FILLED" -lt 0 ] && FILLED=0
EMPTY=$((10 - FILLED))
BAR=""
[ "$FILLED" -gt 0 ] && printf -v FILL '%*s' "$FILLED" '' && BAR="${FILL// /█}"
[ "$EMPTY" -gt 0 ] && printf -v PAD '%*s' "$EMPTY" '' && BAR="${BAR}${PAD// /░}"
BAR_COLOR="$GREEN"
[ "$PCT" -ge 70 ] && BAR_COLOR="$YELLOW"
[ "$PCT" -ge 90 ] && BAR_COLOR="$RED"
LINE2="${BAR_COLOR}${BAR}${RESET} ${PCT}%"
# ---- line 3: cost, lines changed, cache hit ratio, rate limits ----
COST_FMT=""
[ -n "$COST" ] && COST_FMT=$(printf '$%.2f' "$COST")
LINES_PART=""
[ -n "$LINES_ADDED" ] || [ -n "$LINES_REMOVED" ] && LINES_PART="+${LINES_ADDED:-0}/-${LINES_REMOVED:-0}"
HIT_PART=""
if [ -n "$HIT_RATIO" ]; then
    HIT_PCT=$(awk -v r="$HIT_RATIO" 'BEGIN { printf "%.0f", r * 100 }' 2>/dev/null)
    [ -n "$HIT_PCT" ] && HIT_PART="cache ${HIT_PCT}%"
fi
RL_PART=""
if [ -n "$FIVE_H_PCT" ]; then
    r=$(printf '%.0f' "$FIVE_H_PCT"); rs=$(format_reset "$FIVE_H_RESET")
    RL_PART="5h ${r}%"; [ -n "$rs" ] && RL_PART="$RL_PART (${rs})"
fi
if [ -n "$SEVEN_D_PCT" ]; then
    r=$(printf '%.0f' "$SEVEN_D_PCT"); rs=$(format_reset "$SEVEN_D_RESET")
    part="7d ${r}%"; [ -n "$rs" ] && part="$part (${rs})"
    RL_PART=$(join_by " " "${RL_PART:+$RL_PART}" "$part")
fi
parts=()
[ -n "$COST_FMT" ] && parts+=("$COST_FMT")
[ -n "$LINES_PART" ] && parts+=("$LINES_PART")
[ -n "$HIT_PART" ] && parts+=("$HIT_PART")
[ -n "$RL_PART" ] && parts+=("$RL_PART")
LINE3=""
[ "${#parts[@]}" -gt 0 ] && LINE3="${DIM}$(join_by " | " "${parts[@]}")${RESET}"
printf '%b\n' "$LINE1"
printf '%b\n' "$LINE2"
[ -n "$LINE3" ] && printf '%b\n' "$LINE3"
