set shell := ["bash", "-euo", "pipefail", "-c"]

# sbt's launcher needs a writable XDG_RUNTIME_DIR to create its boot server
# socket (sbt.internal.BootServerSocket). Outside a sandbox the inherited
# $XDG_RUNTIME_DIR (/run/user/<uid>) works fine, but ai-jail doesn't map
# /run/user into the sandbox, so sbt dies with
# `AccessDeniedException: /run/user` before it ever reaches user code —
# this bit `just run` under `just jail-claude`. Overriding to a repo-local
# tmp dir works in both cases and is harmless outside the jail.
export XDG_RUNTIME_DIR := justfile_directory() + "/.tmp/sbt-runtime"

default:
    @just --list

# One-time, only needed if you're not using `nix develop` (its shellHook
# does this automatically). Points git at the versioned .githooks/ dir —
# see .githooks/pre-commit, which blocks committing Scala that won't
# compile.
install-hooks:
    git config core.hooksPath .githooks

# ---------------------------------------------------------------------
# Build / test / lint
# ---------------------------------------------------------------------

build:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt compile

test:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt test

fmt:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtAll

lint:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtCheckAll

# All free code-quality checks, same as ci.yml: scalafmt, scalafix (semantic lint), compiler
# unused-warnings-as-errors (part of compile), ruff for the Python, actionlint for the workflows.
# `just quality-fix` applies the auto-fixable ones.
quality:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtCheckAll "scalafixAll --check"
    if command -v ruff >/dev/null; then ruff check dspy finetune && ruff format --check dspy finetune; else echo "ruff not installed — skipping (pip install ruff)"; fi
    if command -v actionlint >/dev/null; then actionlint; else echo "actionlint not installed — skipping"; fi

quality-fix:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtAll scalafixAll
    if command -v ruff >/dev/null; then ruff check --fix dspy finetune && ruff format dspy finetune; fi

# Runs marola's CLI (build.sbt's `cli` project; marola is split into
# core/local/azure/cli, docs/FUTURE-WORK.md §7.3). `*args` forwards CLI flags to the app
# itself, e.g. `just run -- --summarize`.
run *args:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run {{args}}"

# Runs marola's MCP tool server (cli/src/main/scala/marola/agent/SwimConditionsMcpServer.scala)
# — a separate main class from `run`'s (see build.sbt's Compile/run/mainClass note on why plain
# `sbt run` can't pick this one).
mcp-server:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/runMain marola.agent.SwimConditionsMcpServer"

watch:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "~compile"

# ---------------------------------------------------------------------
# Ollama — marola's default local LLM backend (LocalLlmClient, docs/RUN-LOCALLY.md)
# ---------------------------------------------------------------------

# Make sure an Ollama server is reachable and has `model` pulled. Starts `ollama serve` in the
# background (logging to .tmp/ollama.log) if nothing answers on localhost:11434, waits for it,
# then pulls the model only if `ollama list` doesn't already have it. Idempotent: safe to run
# before every `just run -- --summarize`. The model defaults to $MAROLA_LOCAL_LLM_MODEL if set,
# else `llama3.2` (LocalLlmClient.DefaultModel) — pass e.g. `just ollama-up llama3.2:1b` to
# override. Note Ollama treats `llama3.2` and `llama3.2:1b` as different models.
ollama-up model=env_var_or_default("MAROLA_LOCAL_LLM_MODEL", "llama3.2") embed=env_var_or_default("MAROLA_LOCAL_EMBED_MODEL", "llama3.2"):
    #!/usr/bin/env bash
    set -euo pipefail
    api=http://localhost:11434/api/tags
    if ! curl -sf -m 2 "$api" >/dev/null; then
        mkdir -p "{{justfile_directory()}}/.tmp"
        echo "ollama: not reachable on localhost:11434 — starting 'ollama serve' in the background"
        nohup ollama serve >"{{justfile_directory()}}/.tmp/ollama.log" 2>&1 &
        for _ in $(seq 1 30); do
            curl -sf -m 1 "$api" >/dev/null && break
            sleep 1
        done
        curl -sf -m 2 "$api" >/dev/null || { echo "ollama: server did not come up — see .tmp/ollama.log" >&2; exit 1; }
    fi
    for m in "{{model}}" "{{embed}}"; do
        if ollama list | awk 'NR>1 {print $1}' | grep -qx "$m"; then
            echo "ollama: serving, model '$m' already pulled"
        else
            echo "ollama: pulling '$m'..."
            ollama pull "$m"
        fi
    done

# Runs marola's E2E test (cli/src/test/scala/marola/E2ESpec.scala) against live
# Overpass/Open-Meteo, plus a local Ollama server if one's reachable (skipped gracefully
# otherwise — see that file's own `assume(...)` checks). Excluded from `just test`'s default run
# (see build.sbt's Test/testOptions) since it hits live network/services; this is how you run it
# on purpose. See docs/RUN-LOCALLY.md for getting a local Ollama model set up first.
#
# NOTE: `cli/testOnly -- --include-tags=E2E` does NOT work here — confirmed directly: sbt's
# accumulated Test/testOptions still carries build.sbt's default `--exclude-tags=E2E`, and munit
# applies an exclude over a same-tag include (0 tests ran when both flags were passed together),
# contrary to what munit's own filtering docs describe for the general case. Overriding
# Test/testOptions for this one invocation, rather than appending to it, is what actually works.
e2e:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt \
        'set cli/Test/testOptions := Seq(Tests.Argument(new TestFramework("munit.Framework"), "--include-tags=E2E"))' \
        'cli/testOnly marola.E2ESpec'

# ---------------------------------------------------------------------
# Knowledge (local RAG) and fine-tuning — MIP-0001, docs/FUTURE-WORK.md §9.1
# ---------------------------------------------------------------------

# Ask marola's curated ocean notes (knowledge/*.md) a question — local RAG: Ollama embeds the
# corpus (first run only, cached under data/), retrieves the best passages, the local LLM answers
# from them with [n] citations. e.g. `just ask "what should I do if I'm caught in a rip current?"`
ask question:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --ask \"{{question}}\""

# Force a re-embed of knowledge/ (normally automatic when a file or the embed model changes).
knowledge-index:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --reindex"

# Tier 1 "fine-tune": llama3.2 + marola's persona/decoding settings, as an Ollama model named
# marola-llama3.2 (finetune/Modelfile). Then: MAROLA_LOCAL_LLM_MODEL=marola-llama3.2 just run ...
finetune-model base="llama3.2":
    mkdir -p .tmp && sed 's/^FROM .*/FROM {{base}}/' finetune/Modelfile > .tmp/Modelfile && ollama create marola-llama3.2 -f .tmp/Modelfile

# Tier 2: QLoRA adapter (needs `pip install -r finetune/requirements.txt`; written, not run here —
# see finetune/README.md). preset=tiny trains on CPU in minutes; small|base need more.
finetune-train preset="tiny" *args:
    python3 finetune/train_lora.py --preset {{preset}} {{args}}

# marola vs. a plain prompt on 22 ocean questions, 3 arms, same local model — writes data/benchmark-*.md
benchmark:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --benchmark"

# Tier 2 prep: chat-format JSONL from the DSPy demos, sea lore and knowledge/ (stdlib only).
finetune-dataset:
    python3 finetune/build_dataset.py

# ---------------------------------------------------------------------
# Browser-session context — repomix.config.json, repomix-instruction.md
# ---------------------------------------------------------------------

# Pack README, AGENTS.md, ARCHITECTURE, FUTURE-WORK, the MIP skill and all MIPs (~35k tokens, no
# code) into .tmp/marola-context-mips.md and copy it to the clipboard. Paste it into a browser
# Claude chat, attach the voice notes (.ogg) or text, and say "convert the audios into MIP
# proposals" — the pack's instruction section tells the assistant the rest (template, numbering,
# transcript appendix, what not to assert).
context-mips:
    mkdir -p .tmp && "$(just _repomix)" -c repomix.config.json
    @just _clip .tmp/marola-context-mips.md

# Same idea for the whole repo (code included, comments stripped) — big; for code questions only.
context-full:
    mkdir -p .tmp && "$(just _repomix)" --style markdown --compress --remove-comments -o .tmp/marola-context-full.md .
    @just _clip .tmp/marola-context-full.md

# The Node repomix (nixpkgs, flake.nix) — not the unrelated PyPI "repomix" Python port, which a
# pip/pipx install can put earlier on PATH (it prints an argparse usage and ignores our config).
# Prefer the /nix/store one whatever the PATH order; fall back to whatever `repomix` is.
_repomix:
    #!/usr/bin/env bash
    set -euo pipefail
    bin="$(command -v -a repomix 2>/dev/null | grep -m1 '^/nix/store/' || command -v repomix || true)"
    [ -n "$bin" ] || { echo "repomix not found — enter 'nix develop' (flake.nix provides it)" >&2; exit 1; }
    if ! "$bin" --version 2>/dev/null | grep -qE '^[0-9]+\.[0-9]+'; then
        echo "warning: $bin does not look like the Node repomix; output may be wrong" >&2
    fi
    echo "$bin"

_clip file:
    #!/usr/bin/env bash
    set -euo pipefail
    size="$(wc -c < "{{file}}")"
    if command -v wl-copy >/dev/null 2>&1 && [ -n "${WAYLAND_DISPLAY:-}" ]; then
        wl-copy < "{{file}}"; echo "copied to clipboard via wl-copy: {{file}} ($size bytes)"
    elif command -v xclip >/dev/null 2>&1 && [ -n "${DISPLAY:-}" ]; then
        xclip -selection clipboard < "{{file}}"; echo "copied to clipboard via xclip: {{file}} ($size bytes)"
    elif command -v pbcopy >/dev/null 2>&1; then
        pbcopy < "{{file}}"; echo "copied to clipboard via pbcopy: {{file}} ($size bytes)"
    else
        echo "no clipboard tool/display found — open the file instead: {{file}} ($size bytes)"
    fi

# ---------------------------------------------------------------------
# Claude Code cost accounting — AGENTS.md "Attribution and cost accounting"
# ---------------------------------------------------------------------

# What Claude Code sessions consumed, from the local session logs (~/.claude/projects), priced at
# list rates — the quota proxy to paste into a PR's "Cost" line. Uses ccusage (free, npm) via npx.
#   just claude-cost                 # per-session table (this machine, all projects)
#   just claude-cost daily           # per-day
#   just claude-cost session --json  # machine-readable
# In-session, `/usage` shows the same numbers for the current session only.
claude-cost *args="session":
    npx --yes ccusage@latest {{args}}

# ---------------------------------------------------------------------
# ai-jail — sandbox AI coding agents (bubblewrap/Landlock/seccomp on
# Linux). https://github.com/akitaonrails/ai-jail
# Project policy lives in `.ai-jail` (committed, untrusted layer — can
# only tighten, never grant). Per-machine trust/capabilities go in
# ~/.ai-jail, not here. `--network` is on for these because Claude Code
# needs it (dependency resolution / API calls) — this is containment for
# the filesystem/process blast radius, not a network firewall; see
# AGENTS.md for the actual credential/cost rules.
# ---------------------------------------------------------------------

# Print the sandbox invocation ai-jail would run, without running it —
# use this to audit a command's jail before trusting it for real.
jail-dry-run *cmd:
    ai-jail --no-save-config --dry-run -- {{cmd}}

# --no-save-config on every recipe below: ai-jail defaults to rewriting
# the committed .ai-jail with the last-run command otherwise (see the
# warning at the top of that file).

jail-claude:
    ai-jail --no-save-config --rw-map ~/.claude --rw-map ~/.claude.json --map ~/.ssh --network --terminal-passthrough claude
