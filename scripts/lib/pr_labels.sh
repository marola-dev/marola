#!/usr/bin/env bash
# pr_labels — shared taxonomy + classifier for scripts/pr-label.sh and
# scripts/backfill-pr-labels.sh. MIP-0001.

set -euo pipefail

# name:color:description — color is a GitHub label hex (no '#').
PR_LABEL_TAXONOMY=(
  "area/conditions:1d76db:Live sea/weather/tide data, forecasting, caching/latency of an answer"
  "area/water-quality:0e8a16:Bathing-water quality per sampling point"
  "area/sea-life:5319e7:Jellyfish/whale heuristics, sea-lore corpus"
  "area/safety:d93f0b:Safety-footer text, hazard/escalation behavior"
  "area/accessibility:c2e0c6:Parking/toilets/showers/lifeguard beach data"
  "area/map-site:006b75:The static map/site — markers, layout, copy"
  "area/telegram-bot:0052cc:The bot surface — replies, digest, subscriptions, matching"
  "area/outreach:e99695:Promotion — book, exporter, Instagram, waitlist/site copy"
  "area/ml-infra:fbca04:MLflow, llm4s/DSPy, fine-tuned model, forecasting/jellyfish research"
  "area/dev-tooling:bfd4f2:Claude Code / OpenCode / agentic-tooling dev workflow, not the product"
  "area/positioning:ededed:Product naming/positioning docs (MIP-0029)"
  "area/unscoped:eeeeee:No MIP and no path matched a known area — needs a human look"
  "layer/core:f9d0c4:core/ — pure pipeline logic"
  "layer/local:f9d0c4:local/ — Ollama-backed implementations"
  "layer/cli:f9d0c4:cli/ — Main, AppConfig, MCP server"
  "layer/dspy:f9d0c4:dspy/ — offline prompt-compile step"
  "layer/site:f9d0c4:site/ — the static map"
  "layer/docs:f9d0c4:docs/, README.md, AGENTS.md, PHILOSOPHY.md"
  "layer/infra:f9d0c4:.github/, scripts/, justfile, flake.nix, .githooks/, .claude/"
  "kind/deps:c5def5:Dependabot/scala-steward dependency bump"
  "kind/docs-only:c5def5:Every changed file is documentation"
)

# MIP number -> space-separated area labels, per MIP-0029 §4 (`git show
# origin/docs/mip-0029-ocean-layer-positioning:docs/MIPs/MIP-0029-ocean-layer-positioning.md`).
# 0023/0025 are contested numbers per that MIP's own header note (0023 also claimed by a
# ROADMAP.md §5 proposal, 0025 likewise) — mapped here to the open-draft branch's actual topic
# (waitlist-promotion, sea-model), not the ROADMAP proposal; revisit if that collision resolves
# differently.
declare -A PR_LABEL_MIP_AREA=(
  [1]="area/water-quality area/sea-life"
  [2]="area/telegram-bot"
  [3]="area/telegram-bot area/conditions"
  [4]="area/telegram-bot"
  [5]="area/map-site"
  [6]="area/conditions"
  [7]="area/conditions area/ml-infra"
  [8]="area/dev-tooling"
  [9]="area/map-site area/sea-life"
  [10]="area/ml-infra"
  [11]="area/dev-tooling"
  [12]="area/ml-infra"
  [13]="area/dev-tooling"
  [14]="area/outreach"
  [15]="area/telegram-bot"
  [16]="area/water-quality area/map-site"
  [17]="area/dev-tooling"
  [18]="area/outreach"
  [19]="area/ml-infra"
  [20]="area/outreach"
  [21]="area/accessibility area/map-site"
  [22]="area/safety"
  [23]="area/outreach"
  [24]="area/outreach"
  [25]="area/ml-infra"
  [29]="area/positioning"
)

# ensure_pr_labels — create/update every taxonomy label on the current `gh` repo.
ensure_pr_labels() {
  local entry name color desc
  for entry in "${PR_LABEL_TAXONOMY[@]}"; do
    name="${entry%%:*}"
    color="${entry#*:}"; color="${color%%:*}"
    desc="${entry#*:*:}"
    gh label create "$name" --color "$color" --description "$desc" --force >/dev/null
  done
}

# pr_label_mip_number <headRefName> <commit-subjects-newline-separated>
# <changed-paths-newline-separated> Same detection order as scripts/uprd.sh's MIP auto-detect:
# branch name token, then a commit subject starting with MIP-NNNN, then a docs/MIPs/MIP-NNNN-*.md
# file touched.
pr_label_mip_number() {
  local ref="$1" subjects="$2" paths="$3" n
  if [[ "$ref" =~ mip-([0-9]{4}) ]]; then
    n="${BASH_REMATCH[1]}"; echo "$((10#$n))"
    return
  fi
  n="$(grep -oE '^MIP-[0-9]{4}' <<<"$subjects" | head -1 | grep -oE '[0-9]{4}')"
  if [ -n "$n" ]; then
    echo "$((10#$n))"
    return
  fi
  n="$(grep -oE 'docs/MIPs/MIP-[0-9]{4}' <<<"$paths" | head -1 | grep -oE '[0-9]{4}')"
  [ -n "$n" ] && echo "$((10#$n))"
}

# pr_label_layers <changed-paths-newline-separated> — one layer/* label per top-level dir touched.
pr_label_layers() {
  local paths="$1" labels=()
  grep -q '^core/' <<<"$paths" && labels+=("layer/core")
  grep -q '^local/' <<<"$paths" && labels+=("layer/local")
  grep -q '^cli/' <<<"$paths" && labels+=("layer/cli")
  grep -q '^dspy/' <<<"$paths" && labels+=("layer/dspy")
  grep -q '^site/' <<<"$paths" && labels+=("layer/site")
  grep -qE '^(docs/|README\.md$|AGENTS\.md$|PHILOSOPHY\.md$|CLAUDE\.md$)' <<<"$paths" && labels+=("layer/docs")
  grep -qE '^(\.github/|scripts/|mkdocs/|justfile$|flake\.nix$|flake\.lock$|\.githooks/|\.claude/)' <<<"$paths" && labels+=("layer/infra")
  printf '%s\n' "${labels[@]}"
}

# pr_label_kind <author-login> <changed-paths-newline-separated> — best-effort, only when certain.
pr_label_kind() {
  local author="$1" paths="$2"
  case "$author" in
    dependabot\[bot\]|app/dependabot|scala-steward\[bot\]|app/scala-steward) echo "kind/deps"; return ;;
  esac
  if [ -n "$paths" ] && ! grep -qvE '\.md$|^docs/' <<<"$paths"; then
    echo "kind/docs-only"
  fi
}

# pr_label_classify <headRefName> <author-login> <commit-subjects> <changed-paths> — prints one
# label per line: MIP-mapped area labels (or area/unscoped if no MIP matched and no other area
# applies), every layer/* touched, and kind/* when confident.
pr_label_classify() {
  local ref="$1" author="$2" subjects="$3" paths="$4"
  local mip areas layers kind
  mip="$(pr_label_mip_number "$ref" "$subjects" "$paths" || true)"
  if [ -n "${mip:-}" ] && [ -n "${PR_LABEL_MIP_AREA[$mip]:-}" ]; then
    areas="${PR_LABEL_MIP_AREA[$mip]}"
  else
    areas="area/unscoped"
  fi
  # shellcheck disable=SC2086 # deliberately unquoted: PR_LABEL_MIP_AREA's space-separated values
  # (e.g. "area/map-site area/sea-life" for MIP-0009) must word-split into one printf arg/line per
  # label — quoting collapsed a multi-area MIP into a single, space-containing "label" that gh
  # then rejected as not found (PR #147, area/map-site area/sea-life).
  printf '%s\n' $areas
  layers="$(pr_label_layers "$paths")"
  [ -n "$layers" ] && printf '%s\n' "$layers"
  kind="$(pr_label_kind "$author" "$paths")"
  [ -n "$kind" ] && printf '%s\n' "$kind"
}
