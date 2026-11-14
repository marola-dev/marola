#!/usr/bin/env bash
# figure.sh SRC.json OUT.png: one Archify source to one PNG in picasso's look (just figure).
# Archify validates and renders (deliver), docs/archify/picasso.css goes over the page, and headless
# Chrome measures the drawing (first pass) and takes its picture cropped to it (second pass).
# ARCHIFY is the pinned checkout (just figure-init), CHROME the browser, CHROME_FLAGS extra flags.
set -euo pipefail
src="$1" png="$2"
archify="${ARCHIFY:-.tmp/archify}" chrome="${CHROME:-google-chrome}"
out=".tmp/figure/$(basename "$png" .png)"
rm -rf "$out" && mkdir -p "$out" "$(dirname "$png")"
ARCHIFY_UPDATE_CHECK_DISABLED=1 node "$archify/archify/bin/archify.mjs" deliver "$(jq -r .diagram_type "$src")" "$src" "$out/page.html" --quality standard --json >"$out/deliver.json" ||
  { jq -r '.diagnostics[]?.message // .error' "$out/deliver.json" >&2; exit 1; }
read -r w h < <(grep -o '<svg viewBox="[^"]*"' "$out/page.html" | head -1 | sed -E 's/.*0 0 ([0-9.]+) ([0-9.]+)"/\1 \2/')

# The bounding box of what is visible, in viewBox units, written into the title.
measure='<script>addEventListener("load",async()=>{await document.fonts.ready;const s=document.querySelector(".diagram-container svg"),m=s.getScreenCTM().inverse();let a=[1e9,1e9,-1e9,-1e9];for(const e of s.querySelectorAll("rect,text,path,line,polyline,polygon,circle,ellipse,image")){if(e.closest("defs")||getComputedStyle(e).visibility!=="visible")continue;const r=e.getBoundingClientRect();if(r.width<1&&r.height<1||r.width>=innerWidth*.98&&r.height>=innerHeight*.98)continue;const p=new DOMPoint(r.left,r.top).matrixTransform(m),q=new DOMPoint(r.right,r.bottom).matrixTransform(m);a=[Math.min(a[0],p.x),Math.min(a[1],p.y),Math.max(a[2],q.x),Math.max(a[3],q.y)]}document.title="BBOX "+a.map(Math.round).join(" ")})</script>'
frame() { # frame VIEWBOX EXTRA > html
  FONTS='<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Fjalla+One&family=Inter:wght@400;600&display=block">' \
    CSS="$(cat docs/archify/picasso.css)" VB="$1" EXTRA="$2" \
    perl -0pe 's|<script\b.*?</script>||gs; s|</head>|$ENV{FONTS}<style>$ENV{CSS}</style>$ENV{EXTRA}</head>|; s|<svg viewBox="0 0 [0-9.]+ [0-9.]+"|<svg viewBox="$ENV{VB}"|' "$out/page.html"
}
shoot() { # shoot W H ARGS...
  local ww="$1" hh="$2"; shift 2
  # shellcheck disable=SC2086
  timeout 120 "$chrome" --headless --disable-gpu --hide-scrollbars --force-dark-mode --force-device-scale-factor=2 \
    --window-size="$ww,$hh" --virtual-time-budget=5000 ${CHROME_FLAGS:-} "$@" 2>/dev/null
}
size() { echo "<style>.diagram-container svg{width:$1px!important;height:$2px!important;left:0!important;top:0!important;margin:0!important;transform:none!important}</style>"; }
frame "0 0 $w $h" "$(size "${w%.*}" "${h%.*}")$measure" >"$out/measure.html"
read -r x0 y0 x1 y1 < <(shoot "${w%.*}" "${h%.*}" --dump-dom "file://$PWD/$out/measure.html" | grep -o 'BBOX [-0-9 ]*' | cut -d' ' -f2-)
pad=24 x0=$((x0 - 24)) y0=$((y0 - 24))
cw=$((x1 - x0 + pad)) ch=$((y1 - y0 + pad))
frame "$x0 $y0 $cw $ch" "$(size "$cw" "$ch")" >"$out/frame.html"
shoot "$cw" "$ch" --screenshot="$(realpath -m "$png")" "file://$PWD/$out/frame.html"
echo "figure: $png (${cw}x${ch})"
