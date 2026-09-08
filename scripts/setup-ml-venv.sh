#!/usr/bin/env bash
# Create (or update) the Python venv marola-sea trains in. CUDA torch comes from PyTorch's own
# wheel index, not nixpkgs: nixpkgs' torchWithCuda has no cache for this nixpkgs pin and compiles
# torch, magma and triton from source. MIP-0025.
#
#   scripts/setup-ml-venv.sh              # create/update at the default location
#   VENV_ROOT=/path scripts/setup-ml-venv.sh
set -euo pipefail

VENV_ROOT="${VENV_ROOT:-$HOME/.marola-ml-venv}"
CUDA_INDEX="${CUDA_INDEX:-https://download.pytorch.org/whl/cu129}"
PY="${PYTHON:-python3}"

usage() { sed -n '2,7p' "$0"; }

pkgs_from_requirements() {
  # everything except torch, which is installed first from the CUDA index
  grep -vE '^\s*(#|$)|^torch\b' finetune/requirements.txt | sed 's/;.*//'
}

self_test() {
  local fails=0
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails+1)); fi; }
  ok "$(echo "$CUDA_INDEX" | grep -c '^https://download.pytorch.org/whl/cu')" "1" "torch comes from PyTorch's CUDA wheel index, not PyPI"
  ok "$(pkgs_from_requirements | grep -c '^torch$')" "0" "torch is excluded from the second install pass"
  ok "$(pkgs_from_requirements | grep -c 'transformers')" "1" "transformers is installed from PyPI"
  ok "$(pkgs_from_requirements | grep -c 'bitsandbytes')" "1" "the CUDA-only extras are kept"
  ok "$(pkgs_from_requirements | grep -c ';')" "0" "environment markers are stripped, pip gets plain names"
  [ "$fails" -eq 0 ] && { echo "setup-ml-venv self-test: ok"; return 0; }
  echo "setup-ml-venv self-test: $fails failure(s)" >&2; return 1
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  --help|-h)   usage; exit 0 ;;
  --path)      echo "$VENV_ROOT"; exit 0 ;;
esac

if [ ! -x "$VENV_ROOT/bin/python" ]; then
  echo "creating venv at $VENV_ROOT ($("$PY" --version))"
  "$PY" -m venv "$VENV_ROOT"
fi

"$VENV_ROOT/bin/python" -m pip install --quiet --upgrade pip
echo "installing torch from $CUDA_INDEX"
"$VENV_ROOT/bin/python" -m pip install --index-url "$CUDA_INDEX" torch
echo "installing the rest from PyPI"
# shellcheck disable=SC2046  # word splitting is intended: one package per argument
"$VENV_ROOT/bin/python" -m pip install $(pkgs_from_requirements | tr '\n' ' ')

echo
"$VENV_ROOT/bin/python" - <<'PY'
import torch
print(f"torch {torch.__version__}  cuda_available={torch.cuda.is_available()}")
if torch.cuda.is_available():
    print(f"device: {torch.cuda.get_device_name(0)}")
else:
    raise SystemExit("torch cannot see a CUDA device — check nvidia-smi on this host")
PY
echo "venv ready: $VENV_ROOT"
