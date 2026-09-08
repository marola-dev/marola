#!/usr/bin/env bash
# guard-azure — PreToolUse(Bash) hook: the AGENTS.md cost/deployment rule as a hook, not prose
# (MIP-0011 §5 item 2; docs/AI-500-MAPPING.md §4 human-confirmation gate).
set -euo pipefail

MESSAGE='Blocked by .claude/hooks/guard-azure.sh (AGENTS.md "Cost & deployment safety"): never provision or deploy a paid Azure resource — azd up/provision/deploy, az deployment … — without explicit human confirmation first. Propose the change, state the expected cost, and wait for a go-ahead; the human then runs it, or sets MAROLA_ALLOW_AZURE_DEPLOY=1 for that one command.'

# 0 = the command is an Azure provision/deploy, 1 = anything else.
is_azure_deploy() {
  local cmd="$1"
  local b='(^|[^[:alnum:]_-])' e='([^[:alnum:]_-]|$)'
  [[ "$cmd" =~ ${b}azd[[:space:]]+(up|provision|deploy)${e} ]] && return 0
  [[ "$cmd" =~ ${b}az[[:space:]]+deployment${e} ]] && return 0
  return 1
}

# The decision for one command under one environment: prints nothing, exits 0 (allow) or 2
# (block).
decide() {
  local cmd="$1" override="${2-}"
  if is_azure_deploy "$cmd" && [ "$override" != "1" ]; then
    printf '%s\n' "$MESSAGE" >&2
    return 2
  fi
  return 0
}

extract_command() {   # hook JSON on stdin → .tool_input.command (empty when absent)
  if command -v jq >/dev/null 2>&1; then
    jq -r '.tool_input.command // empty' 2>/dev/null || true
  else
    python3 -c 'import json,sys
try: print(json.load(sys.stdin).get("tool_input",{}).get("command",""))
except Exception: pass' 2>/dev/null || true
  fi
}

self_test() {
  local fails=0
  expect() {   # expect <exit code> <override value> <command…>
    local want="$1" override="$2"; shift 2
    local got=0
    decide "$*" "$override" 2>/dev/null || got=$?
    if [ "$got" -eq "$want" ]; then
      printf '  ok   exit %s  %s%s\n' "$got" "$*" "${override:+  (MAROLA_ALLOW_AZURE_DEPLOY set)}"
    else
      printf '  FAIL exit %s (wanted %s)  %s%s\n' "$got" "$want" "$*" "${override:+  (MAROLA_ALLOW_AZURE_DEPLOY set)}"
      fails=$((fails + 1))
    fi
  }
  echo "guard-azure self-test:"
  expect 2 "" azd up
  expect 2 "" azd provision
  expect 2 "" azd deploy --environment prod
  expect 2 "" az deployment group create --resource-group rg --template-file main.bicep
  expect 2 "" 'cd infra && azd up --no-prompt'
  expect 0 "" az account show
  expect 0 "" az group list
  # Conservative by design: a quoted mention still blocks.
  expect 2 "" 'echo "never run azd up unattended"'
  expect 0 "" just build
  expect 0 "1" azd up
  expect 0 "1" az deployment group create --resource-group rg
  # Shapes an ultrareview found failing open — these all exited 0 before the boundary fix.
  expect 2 "" ./azd up
  expect 2 "" /usr/bin/azd up
  expect 2 "" "bash -c 'azd up'"
  expect 2 "" 'sh -c "azd provision"'
  expect 2 "" '(azd deploy)'
  expect 2 "" 'nix develop -c azd up'
  # Word boundaries still hold: these are not the verb.
  expect 0 "" echo hazd up
  expect 0 "" gazdup
  expect 0 "" cat azd-up-notes.md
  expect 0 "" azd upgrade
  # The override must be exactly "1": "0"/"false" used to unblock (same review).
  expect 2 "0" azd up
  expect 2 "false" azd up
  expect 2 "yes" az deployment group create --resource-group rg
  # The message must carry the rule, not just "blocked".
  local msg; msg="$(decide 'azd up' 2>&1 >/dev/null || true)"
  if grep -q 'explicit human confirmation' <<<"$msg"; then echo "  ok   message quotes the AGENTS.md rule"; else echo "  FAIL message missing the rule"; fails=$((fails + 1)); fi
  # End-to-end through the JSON path Claude Code uses.
  local got=0
  printf '{"tool_name":"Bash","tool_input":{"command":"azd up"}}' | MAROLA_ALLOW_AZURE_DEPLOY= "$0" >/dev/null 2>&1 || got=$?
  if [ "$got" -eq 2 ]; then echo "  ok   exit 2 via hook JSON on stdin"; else echo "  FAIL exit $got via hook JSON (wanted 2)"; fails=$((fails + 1)); fi
  got=0
  printf '{"tool_name":"Bash","tool_input":{"command":"git status"}}' | "$0" >/dev/null 2>&1 || got=$?
  if [ "$got" -eq 0 ]; then echo "  ok   exit 0 via hook JSON for git status"; else echo "  FAIL exit $got via hook JSON (wanted 0)"; fails=$((fails + 1)); fi
  got=0
  printf 'not json' | "$0" >/dev/null 2>&1 || got=$?
  if [ "$got" -eq 0 ]; then echo "  ok   malformed input is allowed through (never blocks by accident)"; else echo "  FAIL exit $got on malformed input (wanted 0)"; fails=$((fails + 1)); fi
  [ "$fails" -eq 0 ] && { echo "guard-azure self-test: ok"; return 0; }
  echo "guard-azure self-test: $fails failure(s)" >&2; return 1
}

case "${1-}" in
  --self-test) self_test ;;
  *) decide "$(extract_command)" "${MAROLA_ALLOW_AZURE_DEPLOY-}" ;;
esac
