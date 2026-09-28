FROM python:3.13-slim

# Pinned, not floated: MIP-0064 §4.2 verified these on PyPI on 2026-09-28. The kroki plugin's
# version is load-bearing — 1.7.0 renamed the config keys (`server_url`, `http_method`) that the
# 0.9.0 spelling in the reference repo's mkdocs.yml still uses.
RUN pip install --no-cache-dir \
      mkdocs==1.6.1 \
      mkdocs-material==9.7.7 \
      mkdocs-kroki-plugin==1.7.0

WORKDIR /mkdocs

# The docs are baked in, not bind-mounted: a mount only works where the daemon can see the host
# path, which rules out every VM-backed runtime (podman machine, Docker Desktop). scripts/mkdocs.sh
# copies the output back out afterwards. The compose files supply the command.
COPY . /mkdocs

EXPOSE 8000
