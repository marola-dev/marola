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

# Where the NVIDIA *driver* library lives. libcuda.so.1 is installed by the driver, never shipped
# by pip, and a nix linker does not search /usr/lib — so torch imports fine and reports
# cuda_available=False, which looks like a driver problem and is not one.
libcuda_dir() {
  local d
  for d in /run/opengl-driver/lib /usr/lib/x86_64-linux-gnu /usr/lib64; do
    if [ -e "$d/libcuda.so.1" ]; then echo "$d"; return 0; fi
  done
  return 1
}

# PyTorch ships manylinux wheels that dlopen the system libstdc++. Under `nix develop` the nix
# Python's linker cannot see one, so `import torch` dies with
# "libstdc++.so.6: cannot open shared object file". Prefer nix's gcc lib, fall back to the distro's.
libstdcxx_dir() {
  local d
  if command -v nix >/dev/null 2>&1; then
    d="$(nix build --no-link --print-out-paths 'nixpkgs#stdenv.cc.cc.lib' 2>/dev/null | tail -1)/lib"
    if [ -e "$d/libstdc++.so.6" ]; then echo "$d"; return 0; fi
  fi
  for d in /usr/lib/x86_64-linux-gnu /usr/lib64 /lib/x86_64-linux-gnu; do
    if [ -e "$d/libstdc++.so.6" ]; then echo "$d"; return 0; fi
  done
  return 1
}

# The wrapper every caller uses instead of $VENV_ROOT/bin/python.
wrapper_body() {
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    '# Written by scripts/setup-ml-venv.sh. Runs the venv Python with libstdc++ and the NVIDIA' \
    '# driver library on the search path: the manylinux wheels need the first, and without the' \
    '# second torch imports but reports cuda_available=False. A nix Python finds neither.' \
    "export LD_LIBRARY_PATH=\"$2:$3\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\"" \
    "exec \"$1/bin/python\" \"\$@\""
}

self_test() {
  local fails=0 d
  ok() { if [ "$1" = "$2" ]; then echo "  ok   $3"; else echo "  FAIL $3 — got '$1' want '$2'"; fails=$((fails+1)); fi; }
  ok "$(echo "$CUDA_INDEX" | grep -c '^https://download.pytorch.org/whl/cu')" "1" "torch comes from PyTorch's CUDA wheel index, not PyPI"
  ok "$(pkgs_from_requirements | grep -c '^torch$')" "0" "torch is excluded from the second install pass"
  ok "$(pkgs_from_requirements | grep -c 'transformers')" "1" "transformers is installed from PyPI"
  ok "$(pkgs_from_requirements | grep -c ';')" "0" "environment markers are stripped, pip gets plain names"
  d="$(libstdcxx_dir || true)"
  ok "$([ -n "$d" ] && [ -e "$d/libstdc++.so.6" ] && echo found)" "found" "a libstdc++ is located for the manylinux wheels"
  ok "$(wrapper_body /venv /libs /drv | grep -c 'LD_LIBRARY_PATH')" "1" "the wrapper puts libstdc++ on the library path"
  ok "$(wrapper_body /venv /libs /drv | grep -c '^exec ')" "1" "the wrapper execs rather than adding a shell per call"
  ok "$(wrapper_body /venv /libs /drv | grep -c '/venv/bin/python')" "1" "the wrapper runs the venv's own interpreter"
  ok "$(wrapper_body /venv /libs /drv | grep -c '/libs:/drv')" "1" "both libstdc++ and the driver dir are on the path"
  d="$(libcuda_dir || true)"
  ok "$([ -n "$d" ] && [ -e "$d/libcuda.so.1" ] && echo found)" "found" "the NVIDIA driver library is located — without it cuda_available is False"
  if [ "$fails" -eq 0 ]; then echo "setup-ml-venv self-test: ok"; return 0; fi
  echo "setup-ml-venv self-test: $fails failure(s)" >&2; return 1
}

case "${1:-}" in
  --self-test) self_test; exit $? ;;
  --help|-h)   usage; exit 0 ;;
  --path)      echo "$VENV_ROOT/bin/marola-python"; exit 0 ;;
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

libs="$(libstdcxx_dir)" || {
  echo "setup-ml-venv: no libstdc++.so.6 found — torch's wheels cannot load without one" >&2
  exit 1
}
drv="$(libcuda_dir)" || {
  echo "setup-ml-venv: no libcuda.so.1 found — is the NVIDIA driver installed? (nvidia-smi)" >&2
  exit 1
}
wrapper_body "$VENV_ROOT" "$libs" "$drv" > "$VENV_ROOT/bin/marola-python"
chmod +x "$VENV_ROOT/bin/marola-python"
echo "wrapper: $VENV_ROOT/bin/marola-python (libstdc++ $libs, driver $drv)"

echo
"$VENV_ROOT/bin/marola-python" -c 'import torch; print(f"torch {torch.__version__}  cuda_available={torch.cuda.is_available()}"); print("device:", torch.cuda.get_device_name(0)) if torch.cuda.is_available() else exit("torch cannot see a CUDA device — check nvidia-smi on this host")'
echo "venv ready: $VENV_ROOT — call bin/marola-python, not bin/python"
