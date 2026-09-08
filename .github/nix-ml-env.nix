# The Python environment marola-sea's build needs, as one nix expression shared by every step of
# .github/workflows/marola-sea-publish.yml. Kept in a file rather than repeated inline so the
# training step and the merge step cannot drift into different versions of torch.
#
# Verified end to end on 2026-09-07: this set merged the DPO adapter into SmolLM2-360M-Instruct and
# the resulting Q4_K_M GGUF answered a jellyfish question through Ollama in marola's own trained
# "Source: <url>" format.
let
  # `import` rather than `legacyPackages`: legacyPackages is already evaluated and takes no
  # config, and CUDA is unfree, so torchWithCuda fails evaluation with the CUDA EULA error before
  # anything is built.
  pkgs = import (builtins.getFlake "nixpkgs") {
    system = builtins.currentSystem;
    config.allowUnfree = true;
  };
in
pkgs.python3.withPackages (ps: with ps; [
  # Needs the cuda-maintainers cache in /etc/nix/nix.custom.conf, or this compiles torch, magma
  # and triton from source — hours. `nix build --dry-run` should say "fetched", not "built".
  # CUDA build, not the plain `torch` this used to name. That default is CPU-only, so the publish
  # workflow trained a 360M model on 32 CPU threads while an idle RTX 4090 sat next to it — 46
  # minutes to reach 25% of a run that takes minutes on the GPU. The CPU build is still what an
  # agent session gets (no /dev/nvidia* inside the sandbox); this is for the self-hosted runner,
  # which has the real card.
  torchWithCuda
  transformers
  peft
  trl
  datasets
  accelerate
  gguf          # convert_hf_to_gguf.py
  numpy
  safetensors
  sentencepiece
  protobuf
])
