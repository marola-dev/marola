#!/usr/bin/env bash
# marola-sea-pull — pull the published marola-sea model from Hugging Face into Ollama.
#
# publish_hf.py uploads standalone GGUFs to <owner>/marola-sea-<preset>-GGUF, and Ollama can pull
# a Hugging Face repo directly, so no Modelfile is involved: this is the trained model itself, not
# finetune/Modelfile's persona-on-a-stock-base (Tier 1) and not Modelfile.adapter's adapter that
# only works because Ollama already holds the base (Tier 2). Those two build locally; this one
# fetches what the publish workflow produced.
#
#   scripts/marola-sea-pull.sh                    # tiny, Q4_K_M, owner from the git remote
#   scripts/marola-sea-pull.sh small Q8_0         # another preset/quant
#   scripts/marola-sea-pull.sh tiny Q4_K_M someone-else
#
# It is copied to the local name `marola-sea` afterwards, because that is what you then put in
# MAROLA_LOCAL_LLM_MODEL — the full hf.co/... reference works too but is unpleasant to type into
# every command, and it changes with the preset.
set -euo pipefail

LOCAL_NAME="${MAROLA_SEA_LOCAL_NAME:-marola-sea}"

# github.com owner out of either remote form, so the default repo is the one you actually forked
# or created rather than a name hardcoded here.
owner_from_remote() {
  local url=$1
  case "$url" in
    *github.com[:/]*) ;;
    *) return 1 ;;
  esac
  url=${url#*github.com}
  url=${url#:}; url=${url#/}
  local owner=${url%%/*}
  [ -n "$owner" ] && [ "$owner" != "$url" ] && { echo "$owner"; return 0; }
  return 1
}

hf_ref() {   # owner preset quant -> the reference `ollama pull` takes
  printf 'hf.co/%s/marola-sea-%s-GGUF:%s' "$1" "$2" "$3"
}

self_test() {
  local fails=0
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails+1)); fi; }

  ok "$(owner_from_remote 'git@github.com:h0ffmann/marola.git')" "h0ffmann" \
     "the owner is read out of an SSH remote"
  ok "$(owner_from_remote 'https://github.com/h0ffmann/marola.git')" "h0ffmann" \
     "and out of an HTTPS remote"
  ok "$(owner_from_remote 'https://github.com/h0ffmann/marola')" "h0ffmann" \
     "with or without the .git suffix"
  ok "$(owner_from_remote 'ssh://git@github.com/some-org/marola.git')" "some-org" \
     "an ssh:// remote and an org owner both work"
  ok "$(owner_from_remote 'git@gitlab.com:h0ffmann/marola.git' || echo none)" "none" \
     "a non-GitHub remote is refused rather than parsed into nonsense"
  ok "$(owner_from_remote 'not-a-url' || echo none)" "none" \
     "and so is something that is not a remote at all"

  ok "$(hf_ref h0ffmann tiny Q4_K_M)" "hf.co/h0ffmann/marola-sea-tiny-GGUF:Q4_K_M" \
     "the pull reference matches what publish_hf.py uploads"
  ok "$(hf_ref h0ffmann small Q8_0)" "hf.co/h0ffmann/marola-sea-small-GGUF:Q8_0" \
     "preset and quant both flow into it"

  if [ "$fails" -eq 0 ]; then echo "marola-sea-pull self-test: ok"; return 0; fi
  echo "marola-sea-pull self-test: $fails failure(s)" >&2; return 1
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  --help|-h)   sed -n '2,17p' "$0"; exit 0 ;;
esac

preset=${1:-tiny}
quant=${2:-Q4_K_M}
owner=${3:-${GITHUB_REPOSITORY_OWNER:-}}
if [ -z "$owner" ]; then
  owner=$(owner_from_remote "$(git remote get-url origin 2>/dev/null || echo '')") || {
    echo "marola-sea-pull: could not work out the Hugging Face owner from the git remote." >&2
    echo "                 Pass it: scripts/marola-sea-pull.sh $preset $quant <owner>" >&2
    exit 1
  }
fi

ref=$(hf_ref "$owner" "$preset" "$quant")
echo "pulling $ref"
if ! ollama pull "$ref"; then
  echo >&2
  echo "marola-sea-pull: pull failed. The usual cause is that nothing has been published for" >&2
  echo "                 this preset yet — check https://huggingface.co/$owner/marola-sea-$preset-GGUF" >&2
  echo "                 and run the 'marola-sea publish' workflow if it is missing." >&2
  exit 1
fi

ollama cp "$ref" "$LOCAL_NAME"
echo
echo "pulled as '$LOCAL_NAME'. Use it as marola's local backend:"
echo "  MAROLA_LOCAL_LLM_MODEL=$LOCAL_NAME just run -- --summarize"
echo "  MAROLA_LOCAL_LLM_MODEL=$LOCAL_NAME just ask \"are there jellyfish at Joaquina?\""
