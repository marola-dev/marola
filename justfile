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

# Runs marola's CLI (build.sbt's `cli` project; marola is split into
# core/local/azure/cli, docs/FUTURE-WORK.md §7.2). `*args` forwards CLI flags to the app
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
ollama-up model=env_var_or_default("MAROLA_LOCAL_LLM_MODEL", "llama3.2"):
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
    if ollama list | awk 'NR>1 {print $1}' | grep -qx "{{model}}"; then
        echo "ollama: serving, model '{{model}}' already pulled"
    else
        echo "ollama: pulling '{{model}}'..."
        ollama pull "{{model}}"
    fi

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
