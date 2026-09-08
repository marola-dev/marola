#!/usr/bin/env bash
# gh-token — resolve the GitHub token a jailed agent should get, on the HOST side of the jail.
#
# `just jail-claude` passes `--env GH_TOKEN`, the name-only form: ai-jail forwards the variable
# only if the launching shell already has it. Two ways that comes up empty, and both end with
# `gh auth login` inside the sandbox:
#   - .env has no GH_TOKEN= line (the shellHook loads .env, but it cannot invent a key), or
#   - `just jco` was run outside `nix develop`, so .env was never loaded at all.
# Either way the login lands in the jail's ephemeral HOME — ~/.config/gh is not mapped in — and
# dies with the sandbox, so the next session starts logged out again.
#
# So: prefer the fine-grained key AGENTS.md asks for, and fall back to the host's own gh login
# rather than making the human re-authenticate into a directory that will not survive.
#
#   scripts/gh-token.sh            # print the token, nothing else
#   scripts/gh-token.sh --source   # print where it came from — never the token itself
set -euo pipefail

# Order matters: a fine-grained .env key is scoped to this repo, `gh auth token` is the full-scope
# OAuth token behind your interactive login. Prefer the narrower one when it exists.
token_source() {
  [ -n "${GH_TOKEN:-}" ]     && { echo "env:GH_TOKEN"; return 0; }
  [ -n "${GITHUB_TOKEN:-}" ] && { echo "env:GITHUB_TOKEN"; return 0; }
  command -v gh >/dev/null 2>&1 && gh auth token >/dev/null 2>&1 && { echo "gh:host-login"; return 0; }
  echo "none"; return 1
}

token_value() {
  case "$(token_source || true)" in
    env:GH_TOKEN)     printf '%s' "$GH_TOKEN" ;;
    env:GITHUB_TOKEN) printf '%s' "$GITHUB_TOKEN" ;;
    gh:host-login)    gh auth token ;;
    *)                return 1 ;;
  esac
}

self_test() {
  local fails=0 tmp fake
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails+1)); fi; }

  tmp="$(mktemp -d)"; fake="$tmp/bin"; mkdir -p "$fake"
  # shellcheck disable=SC2016  # this is a script body being written out, not an expansion
  printf '%s\n' '#!/usr/bin/env bash' '[ "$1" = auth ] && [ "$2" = token ] && { echo host-oauth-token; exit 0; }' 'exit 1' > "$fake/gh"
  chmod +x "$fake/gh"

  ok "$(GH_TOKEN=fine-grained GITHUB_TOKEN=other PATH="$fake:$PATH" bash "$0" --source)" "env:GH_TOKEN" \
     "a fine-grained GH_TOKEN wins over everything — it is the narrowest credential"
  ok "$(GH_TOKEN=fine-grained GITHUB_TOKEN=other PATH="$fake:$PATH" bash "$0")" "fine-grained" \
     "and its value is what gets printed"
  ok "$(GH_TOKEN='' GITHUB_TOKEN=ci-token PATH="$fake:$PATH" bash "$0" --source)" "env:GITHUB_TOKEN" \
     "an empty GH_TOKEN falls through rather than forwarding a blank token"
  ok "$(GH_TOKEN='' GITHUB_TOKEN='' PATH="$fake:$PATH" bash "$0" --source)" "gh:host-login" \
     "with no key in the environment, the host's own gh login is used"
  ok "$(GH_TOKEN='' GITHUB_TOKEN='' PATH="$fake:$PATH" bash "$0")" "host-oauth-token" \
     'and gh auth token supplies the value — no login prompt inside the jail'

  # A gh that is installed but logged out must not be mistaken for a usable source.
  printf '%s\n' '#!/usr/bin/env bash' 'exit 1' > "$fake/gh"; chmod +x "$fake/gh"
  ok "$(GH_TOKEN='' GITHUB_TOKEN='' PATH="$fake:$PATH" bash "$0" --source)" "none" \
     "a logged-out gh reports no source instead of an empty token"
  ok "$(GH_TOKEN='' GITHUB_TOKEN='' PATH="$fake:$PATH" bash "$0" >/dev/null 2>&1 && echo 0 || echo 1)" "1" \
     "and printing the token exits non-zero, so the caller can warn"
  ok "$(GH_TOKEN=secret PATH="$fake:$PATH" bash "$0" --source | grep -c secret)" "0" \
     "--source never leaks the token value itself"

  rm -rf "$tmp"
  if [ "$fails" -eq 0 ]; then echo "gh-token self-test: ok"; return 0; fi
  echo "gh-token self-test: $fails failure(s)" >&2; return 1
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  --source)    token_source; exit $? ;;
  --help|-h)   sed -n '2,16p' "$0"; exit 0 ;;
  "")          token_value; exit $? ;;
  *)           echo "gh-token: unknown argument '$1'" >&2; exit 2 ;;
esac
