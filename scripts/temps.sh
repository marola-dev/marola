#!/usr/bin/env bash
# temps — CPU and GPU temperature with a verdict, for watching a long marola-sea training run.
#
#   scripts/temps.sh              # one snapshot
#   scripts/temps.sh --watch      # refresh every 5s until Ctrl+C
#   scripts/temps.sh --watch 30   # ... every 30s
#   scripts/temps.sh --json       # one snapshot, machine-readable
#
# The verdict is NOT a number this script invented. Silicon publishes its own limits and they
# differ per part, so a hardcoded "85 is hot" is wrong on most machines: coretemp exposes
# tempN_max (the throttle point) and tempN_crit (thermal shutdown), and nvidia-smi reports the
# slowdown and shutdown points the driver enforces. We read those and say how close you are:
#
#   good      more than 10 C of headroom below the throttle point
#   warm      within 10 C of it — normal under sustained load, nothing to do
#   hot       at or past the throttle point: the part is clocking itself down, so the run is
#             now slower than it should be. Worth looking at, not an emergency.
#   critical  at or past the shutdown point — the machine protects itself from here.
#
# On this repo's box that puts the CPU throttle at 80 C and shutdown at 100 C, so the ~57-60 C a
# training run sits at is `good` with 20 C to spare. Sustained load in the 70s is unremarkable.
#
# Inside `just jail-claude` nvidia-smi is present but /dev/nvidia* is not mapped in, so the GPU
# section reports unavailable rather than failing — run it on the host for GPU numbers.
set -euo pipefail

# ---- verdict ------------------------------------------------------------------------------
# All temperatures are millidegrees C, the unit hwmon uses, so no float maths in bash.
WARM_BAND_mC=10000

classify() {
  local t=$1 max=$2 crit=$3
  if [ -n "$crit" ] && [ "$crit" -gt 0 ] && [ "$t" -ge "$crit" ]; then echo critical; return; fi
  if [ -n "$max" ] && [ "$max" -gt 0 ]; then
    [ "$t" -ge "$max" ] && { echo hot; return; }
    [ "$t" -ge $((max - WARM_BAND_mC)) ] && { echo warm; return; }
    echo good; return
  fi
  echo unknown   # no published limit: refuse to invent one
}

marker() {
  case "$1" in
    good)     echo "[ ok ]" ;;
    warm)     echo "[warm]" ;;
    hot)      echo "[HOT ] throttling — the run is slower than it should be" ;;
    critical) echo "[CRIT] at the shutdown point" ;;
    *)        echo "[ ?  ] no published limit to judge against" ;;
  esac
}

fmt_c() { printf '%d.%d C' $(( $1 / 1000 )) $(( ($1 % 1000) / 100 )); }

# ---- CPU ----------------------------------------------------------------------------------
# Find the package sensor rather than hardcoding hwmonN: the number depends on probe order and
# moves between boots. Intel is coretemp/"Package id 0", AMD is k10temp/"Tctl" or "Tdie".
cpu_hwmon() {
  local root=${1:-/sys/class/hwmon} d n
  for d in "$root"/hwmon*; do
    [ -r "$d/name" ] || continue
    n=$(cat "$d/name")
    case "$n" in coretemp|k10temp|zenpower) echo "$d"; return 0 ;; esac
  done
  return 1
}

# Emits "label<TAB>input<TAB>max<TAB>crit". Prefers the whole-package sensor; falls back to the
# hottest core, which is the one that throttles first anyway.
cpu_reading() {
  local d=$1 f base label input max crit
  local best_label="" best_input=-1 best_max="" best_crit=""
  for f in "$d"/temp*_input; do
    [ -r "$f" ] || continue
    base=${f%_input}
    label=$(cat "$base"_label 2>/dev/null || basename "$base")
    input=$(cat "$f")
    max=$(cat "$base"_max 2>/dev/null || echo "")
    crit=$(cat "$base"_crit 2>/dev/null || echo "")
    case "$label" in
      "Package id"*|Tctl|Tdie) printf '%s\t%s\t%s\t%s\n' "$label" "$input" "$max" "$crit"; return 0 ;;
    esac
    if [ "$input" -gt "$best_input" ]; then
      best_label=$label; best_input=$input; best_max=$max; best_crit=$crit
    fi
  done
  [ "$best_input" -ge 0 ] || return 1
  printf '%s\t%s\t%s\t%s\n' "$best_label (hottest core)" "$best_input" "$best_max" "$best_crit"
}

# ---- GPU ----------------------------------------------------------------------------------
# Limits come from `nvidia-smi -q -d TEMPERATURE`. Slowdown is where the driver starts clocking
# down, shutdown is where it cuts power — the same two roles as hwmon's max and crit.
parse_gpu_limits_from() {   # text on stdin -> "slowdown_mC<TAB>shutdown_mC", empty when N/A
  local text slow shut
  text=$(cat)
  slow=$(printf '%s\n' "$text" | sed -n 's/.*GPU Slowdown Temp *: *\([0-9][0-9]*\) C.*/\1/p' | head -1)
  shut=$(printf '%s\n' "$text" | sed -n 's/.*GPU Shutdown Temp *: *\([0-9][0-9]*\) C.*/\1/p' | head -1)
  printf '%s\t%s\n' "${slow:+$((slow * 1000))}" "${shut:+$((shut * 1000))}"
}

# One CSV row per GPU: name, temp C, utilisation %, memory used/total MiB, power W.
parse_gpu_rows() {          # nvidia-smi csv on stdin -> "name<TAB>temp_mC<TAB>util<TAB>mem<TAB>power"
  local line name temp util mem_u mem_t pwr
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    IFS=',' read -r name temp util mem_u mem_t pwr <<<"$line"
    name=$(echo "$name" | sed 's/^ *//;s/ *$//')
    temp=$(echo "$temp" | tr -dc '0-9')
    [ -n "$temp" ] || continue
    printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$((temp * 1000))" \
      "$(echo "$util" | tr -dc '0-9')" \
      "$(echo "$mem_u" | tr -dc '0-9')/$(echo "$mem_t" | tr -dc '0-9') MiB" \
      "$(echo "$pwr" | sed 's/^ *//;s/ *$//')"
  done
}

gpu_available() { command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1; }

# ---- report -------------------------------------------------------------------------------
snapshot() {
  local d row label t max crit verdict slow shut name util mem pwr limits

  echo "=== $(date '+%Y-%m-%d %H:%M:%S') ==="
  if d=$(cpu_hwmon) && row=$(cpu_reading "$d"); then
    IFS=$'\t' read -r label t max crit <<<"$row"
    verdict=$(classify "$t" "${max:-0}" "${crit:-0}")
    printf 'CPU  %-22s %8s  %s\n' "$label" "$(fmt_c "$t")" "$(marker "$verdict")"
    [ -n "$max" ] && [ "$max" -gt 0 ] && \
      printf '     throttles at %s, shuts down at %s\n' "$(fmt_c "$max")" "$(fmt_c "${crit:-0}")"
  else
    echo "CPU  no coretemp/k10temp sensor readable under /sys/class/hwmon"
  fi

  if gpu_available; then
    limits=$(nvidia-smi -q -d TEMPERATURE 2>/dev/null | parse_gpu_limits_from)
    IFS=$'\t' read -r slow shut <<<"$limits"
    while IFS=$'\t' read -r name t util mem pwr; do
      verdict=$(classify "$t" "${slow:-0}" "${shut:-0}")
      printf 'GPU  %-22s %8s  %s\n' "$name" "$(fmt_c "$t")" "$(marker "$verdict")"
      printf '     %s%% util, %s, %s\n' "$util" "$mem" "$pwr"
      # An idle GPU during a "GPU training run" is the useful alarm here, not the temperature.
      [ -n "$util" ] && [ "$util" -lt 5 ] && \
        echo "     note: near-idle — if a training run is meant to be on it, it is on the CPU"
      [ -n "$slow" ] && printf '     throttles at %s, shuts down at %s\n' \
        "$(fmt_c "$slow")" "$(fmt_c "${shut:-0}")"
    done < <(nvidia-smi --query-gpu=name,temperature.gpu,utilization.gpu,memory.used,memory.total,power.draw \
               --format=csv,noheader 2>/dev/null | parse_gpu_rows)
  elif command -v nvidia-smi >/dev/null 2>&1; then
    echo "GPU  nvidia-smi cannot reach the driver — inside a jail /dev/nvidia* is not mapped in;"
    echo "     run this on the host for GPU numbers"
  else
    echo "GPU  no nvidia-smi on PATH"
  fi
}

snapshot_json() {
  local d row label t max crit
  printf '{"time":"%s","cpu":' "$(date -Is)"
  if d=$(cpu_hwmon) && row=$(cpu_reading "$d"); then
    IFS=$'\t' read -r label t max crit <<<"$row"
    printf '{"label":"%s","celsius":%s.%s,"verdict":"%s"}' \
      "$label" "$((t / 1000))" "$(( (t % 1000) / 100 ))" "$(classify "$t" "${max:-0}" "${crit:-0}")"
  else printf 'null'; fi
  printf ',"gpu":'
  if gpu_available; then
    local slow shut name util mem pwr sep=""
    IFS=$'\t' read -r slow shut < <(nvidia-smi -q -d TEMPERATURE 2>/dev/null | parse_gpu_limits_from)
    printf '['
    while IFS=$'\t' read -r name t util mem pwr; do
      printf '%s{"name":"%s","celsius":%s.%s,"util":%s,"verdict":"%s"}' \
        "$sep" "$name" "$((t / 1000))" "$(( (t % 1000) / 100 ))" "${util:-0}" \
        "$(classify "$t" "${slow:-0}" "${shut:-0}")"
      sep=","
    done < <(nvidia-smi --query-gpu=name,temperature.gpu,utilization.gpu,memory.used,memory.total,power.draw \
               --format=csv,noheader 2>/dev/null | parse_gpu_rows)
    printf ']'
  else printf 'null'; fi
  printf '}\n'
}

# ---- self-test ----------------------------------------------------------------------------
self_test() {
  local fails=0 tmp
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails+1)); fi; }

  # Bands, against this machine's real published limits: max 80 C, crit 100 C.
  ok "$(classify 59000 80000 100000)"  "good"     "59 C with an 80 C throttle point is good — 20 C of headroom"
  ok "$(classify 57000 80000 100000)"  "good"     "57 C likewise: a normal load temperature, not a problem"
  ok "$(classify 70000 80000 100000)"  "warm"     "70 C is within 10 C of throttling — warm, still fine"
  ok "$(classify 79999 80000 100000)"  "warm"     "just under the throttle point is warm, not hot"
  ok "$(classify 80000 80000 100000)"  "hot"      "at the throttle point it is hot — clocks are being cut"
  ok "$(classify 99999 80000 100000)"  "hot"      "past throttling but below shutdown is still hot"
  ok "$(classify 100000 80000 100000)" "critical" "at the shutdown point it is critical"
  ok "$(classify 45000 0 0)"           "unknown"  "with no published limit the verdict is unknown, never invented"
  ok "$(marker hot | grep -c throttling)" "1"     "the hot marker says why it matters"

  # CPU sensor discovery against a fixture tree — package sensor wins over a hotter core.
  tmp=$(mktemp -d)
  mkdir -p "$tmp/hwmon0" "$tmp/hwmon1"
  echo nvme > "$tmp/hwmon0/name"; echo 47850 > "$tmp/hwmon0/temp1_input"
  echo coretemp > "$tmp/hwmon1/name"
  echo "Package id 0" > "$tmp/hwmon1/temp1_label"; echo 59000 > "$tmp/hwmon1/temp1_input"
  echo 80000 > "$tmp/hwmon1/temp1_max"; echo 100000 > "$tmp/hwmon1/temp1_crit"
  echo "Core 16" > "$tmp/hwmon1/temp18_label"; echo 71000 > "$tmp/hwmon1/temp18_input"
  ok "$(basename "$(cpu_hwmon "$tmp")")" "hwmon1" "the coretemp sensor is found by name, not by hwmon number"
  ok "$(cpu_reading "$tmp/hwmon1" | cut -f2)" "59000" "the package sensor is preferred over a hotter single core"
  ok "$(cpu_reading "$tmp/hwmon1" | cut -f3,4 | tr '\t' '/')" "80000/100000" "its published limits come along"

  # No package sensor: fall back to the hottest core, which throttles first.
  rm -f "$tmp/hwmon1/temp1_label"
  ok "$(cpu_reading "$tmp/hwmon1" | cut -f2)" "71000" "with no package sensor the hottest core is reported"
  ok "$(cpu_reading "$tmp/hwmon1" | cut -f1 | grep -c hottest)" "1" "and it is labelled as such, not passed off as the package"
  ok "$(cpu_hwmon "$tmp/empty" 2>/dev/null || echo none)" "none" "a tree with no CPU sensor fails rather than guessing"
  rm -rf "$tmp"

  # nvidia-smi parsing, against its real output shapes.
  ok "$(printf '        GPU Shutdown Temp                 : 98 C\n        GPU Slowdown Temp                 : 95 C\n' | parse_gpu_limits_from)" \
     "$(printf '95000\t98000')" "slowdown and shutdown are read from nvidia-smi -q"
  ok "$(printf '        GPU Slowdown Temp                 : N/A\n' | parse_gpu_limits_from)" \
     "$(printf '\t')" "an N/A limit yields empty, so classify() reports unknown instead of guessing"
  ok "$(printf 'NVIDIA GeForce RTX 4090, 61, 98 %%, 21504 MiB, 24564 MiB, 402.11 W\n' | parse_gpu_rows | cut -f2)" \
     "61000" "a GPU row's temperature is parsed into millidegrees"
  ok "$(printf 'NVIDIA GeForce RTX 4090, 61, 98 %%, 21504 MiB, 24564 MiB, 402.11 W\n' | parse_gpu_rows | cut -f1)" \
     "NVIDIA GeForce RTX 4090" "and its name survives the commas in the CSV"
  ok "$(printf 'NVIDIA A100, [N/A], [N/A], 0 MiB, 40960 MiB, [N/A]\n' | parse_gpu_rows | wc -l)" \
     "0" "a card reporting no temperature is skipped, not reported as 0 C"
  ok "$(printf '' | parse_gpu_rows | wc -l)" "0" "no GPUs is not an error"

  if [ "$fails" -eq 0 ]; then echo "temps self-test: ok"; return 0; fi
  echo "temps self-test: $fails failure(s)" >&2; return 1
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  --json)      snapshot_json; exit 0 ;;
  --help|-h)   sed -n '2,24p' "$0"; exit 0 ;;
  --watch)
    interval=${2:-5}
    while true; do snapshot; echo; sleep "$interval"; done ;;
  "")          snapshot ;;
  *)           echo "temps: unknown argument '$1' (try --help)" >&2; exit 2 ;;
esac
