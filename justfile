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
# Git
# ---------------------------------------------------------------------

# Last 10 commits on the current branch, one line each (short hash, relative age, author, subject,
# refs). `just log 25` for more; `just log 10 --stat` to see the files each touched.
log n="10" *args:
    @git --no-pager log -n {{n}} --abbrev-commit --decorate --date=relative --format='%C(yellow)%h%C(reset) %C(dim)%ad%C(reset) %C(blue)%an%C(reset) %s%C(auto)%d%C(reset)' {{args}}

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
    if command -v ruff >/dev/null; then ruff check dspy finetune scripts/smoke_record.py scripts/benchmark_gate.py && ruff format --check dspy finetune scripts/smoke_record.py scripts/benchmark_gate.py; else echo "ruff not installed — skipping (pip install ruff)"; fi
    python3 scripts/smoke_record.py --self-test
    python3 scripts/benchmark_gate.py --self-test
    if command -v actionlint >/dev/null; then actionlint; else echo "actionlint not installed — skipping"; fi
    if command -v hadolint >/dev/null; then hadolint Dockerfile Dockerfile.local; else echo "hadolint not installed — skipping"; fi

quality-fix:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtAll scalafixAll
    if command -v ruff >/dev/null; then ruff check --fix dspy finetune scripts/smoke_record.py scripts/benchmark_gate.py && ruff format dspy finetune scripts/smoke_record.py scripts/benchmark_gate.py; fi

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
# The map — MIP-0005: precomputed boards on a static site (site/)
# ---------------------------------------------------------------------

# Build the static map's data: one pipeline run per area in site/areas.json (or just `area`),
# writing site/dist/data/<area>/{today,tomorrow}.json + latest.json and copying site/static/.
# One Overpass query per area, no LLM. `just site-build floripa`.
site-build area="":
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --site {{area}}"

# Serve site/dist at http://localhost:8000 (python3 is in the flake). Ctrl-C to stop.
site-serve port="8000":
    python3 -m http.server -d site/dist {{port}}

# Deploy the map. `github` (default) triggers .github/workflows/site.yml, which builds on the
# runner and publishes to GitHub Pages — the same workflow the 3-hourly schedule runs (one-time:
# repo Settings → Pages → Source = GitHub Actions). `cloudflare` pushes a local site/dist to
# Cloudflare Pages with wrangler (`npx wrangler login` first; project name from
# $MAROLA_SITE_PROJECT, default marola). Both free tiers; neither is an Azure resource.
site-deploy target="github":
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{target}}" in
        github)
            gh workflow run site.yml && echo "queued site.yml — watch it: gh run list --workflow site.yml" ;;
        cloudflare)
            [ -f site/dist/index.html ] || { echo "site/dist is empty — run: just site-build" >&2; exit 1; }
            npx --yes wrangler pages deploy site/dist --project-name "${MAROLA_SITE_PROJECT:-marola}" ;;
        *) echo "unknown target '{{target}}' — github | cloudflare" >&2; exit 1 ;;
    esac

# ---------------------------------------------------------------------
# Docker — MIP-0008: the CLI as an image (Dockerfile, docker-compose.yml)
# ---------------------------------------------------------------------

# Build one target of the Dockerfile locally as marola:<target> — `jvm` (default), `native`, `dev`,
# or `local` (Dockerfile.local: Ollama + marola-llama3.2, ~2 GB). Same targets CI pushes to
# ghcr.io/h0ffmann/marola (docker.yml, docker-local.yml).
docker-build target="jvm":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ "{{target}}" = local ]; then docker build -f Dockerfile.local -t marola:local .; else docker build --target {{target}} -t marola:{{target}} .; fi

# Run the locally built jvm image against the Ollama on this machine (`--network host`, so
# localhost:11434 is reachable from inside): `just docker-run -- --summarize --lat -27.6733 --lon -48.47`.
docker-run *args:
    docker run --rm --network host --env-file <(env | grep '^MAROLA_' || true) marola:jvm {{args}}

# GraalVM native-image of the CLI → cli/target/marola (MIP-0008 task 3). GraalVM (JDK 25 + native-
# image, ~700 MB) comes from nixpkgs for this one command rather than living in the flake; the
# arguments/metadata are in cli/src/main/resources/META-INF/native-image/. ~1 min on a big machine.
native-image:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p "$XDG_RUNTIME_DIR"
    nix shell nixpkgs#graalvmPackages.graalvm-ce --command bash -c '
        export GRAALVM_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v native-image)")")")"
        echo "native-image: $(native-image --version | head -1) at $GRAALVM_HOME"
        sbt -batch cli/nativeImage'
    ls -la cli/target/marola

# Run the native binary: `just native-run -- --brief --lat -27.6733 --lon -48.47` (same flags as `just run`).
native-run *args:
    ./cli/target/marola {{args}}

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
# `type -aP` lists every match on PATH; `command -v -a` (used before) is not valid bash, so the
# lookup silently failed and the PyPI port ran: "RepomixConfig.__init__() got an unexpected
# keyword argument '$schema'".
_repomix:
    #!/usr/bin/env bash
    set -euo pipefail
    bin="$(type -aP repomix 2>/dev/null | grep -m1 '^/nix/store/' || command -v repomix || true)"
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

# Update the current branch's PR description on GitHub from its commits — "What changed" (one
# entry per commit) + "Cost" (the Cost: trailers). `just uprd --dry-run` prints without editing;
# `just uprd body.md` uses a file. Needs `gh auth status` OK. See scripts/uprd.sh.
uprd *args:
    scripts/uprd.sh {{args}}

# Same for a whole MIP stack (scripts/uprds.sh): every `mip-NNNN/k-*` PR gets its regenerated
# body plus a shared "Stack" section — merge order, each PR's state, this one marked, the summed
# Cost — and a branch without a PR gets one opened on the right base. `just uprds MIP-0005`,
# `just uprds --dry-run` to preview without gh.
uprds *args:
    scripts/uprds.sh {{args}}

# scripts/stack.sh passthrough: `just stack start MIP-0005 2 site-build`, `just stack pr`,
# `just stack restack`, `just stack status` — the local, script-only view of a MIP stack.
stack *args:
    scripts/stack.sh {{args}}

# Delete every local branch whose PR gh confirms MERGED (local branch + remote ref, if still
# there) — never the current branch or main. Safe for mip-NNNN/k-slug branches too.
# `just branches-clean --dry-run` prints what would run. Needs `gh auth status` OK.
branches-clean *args:
    scripts/branches.sh clean {{args}}

# Open a base=main PR for every local *plain* branch (not a mip-NNNN/k-slug stack branch) that's
# ahead of origin/main and has no PR yet. Stack branches print a pointer to `scripts/stack.sh pr`
# / `just uprds MIP-NNNN` instead — they need the previous task's branch as base, not main.
# `just branches-open --dry-run` to preview. Needs `gh auth status` OK.
branches-open *args:
    scripts/branches.sh open {{args}}

# GitHub's native Stacks (the "Preview stack" box on a PR) via the official `gh stack` extension.
# One-time: needs a gh login; installs the extension and its agent skill (`gh skill install
# github/gh-stack`). Both live under ~/.local/share/gh, not in the flake — gh extensions are per-user.
stack-setup:
    gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
    gh extension list | grep -q 'github/gh-stack' || gh extension install github/gh-stack
    gh skill install github/gh-stack || echo "gh skill install failed (older gh?) — the extension works without the skill"

# Link a MIP's *open* PRs into one GitHub Stack, bottom to top (`scripts/stack.sh link`): merged
# and closed PRs are skipped, missing PRs are created on the right base, wrong bases are fixed.
# Safe to re-run; additive only. `just stack-link MIP-0005` — `just uprds` does this too.
stack-link mip="":
    scripts/stack.sh link {{mip}}

# The stack as GitHub sees it (PR numbers, states, bases). `just stack status MIP-0005` is the local view.
stack-view *args:
    gh stack view {{args}}

# After a bottom PR was squash-merged: adopt the stack from GitHub if it isn't tracked locally
# yet (`gh stack link` stores no local state), then fetch, rebase every remaining branch and
# force-push with lease — the whole-stack version of `scripts/stack.sh restack`. Interactive on
# conflicts (`gh stack rebase`). `just stack-sync MIP-0005`.
stack-sync mip="":
    #!/usr/bin/env bash
    set -euo pipefail
    bottom="$(scripts/stack.sh branches {{mip}} | head -1)"
    gh stack checkout "$bottom"
    gh stack sync

# Merge a whole stack (or everything up to one PR) in a single all-or-nothing operation — no
# restack between merges. `just stack-merge 23 --squash` (stack number, purely remote) or
# `just stack-merge 20 --squash` (up to and including PR #20); no argument = the locally tracked
# stack, interactive picker. Branch protection still applies; nothing is bypassed.
stack-merge *args:
    gh stack merge {{args}}

# ---------------------------------------------------------------------
# Claude Code cost accounting — AGENTS.md "Attribution and cost accounting"
# ---------------------------------------------------------------------

# Split a session's real token usage across the commits it produced (scripts/cost-split.py):
# the usage between two commits is the second commit's cost, priced from LiteLLM's table (what
# ccusage uses). Prints one `Cost:` trailer per branch. `just cost-split` for the current branch,
# `just cost-split MIP-0005` for a stack, `--session <id-prefix>` to pin one session, `--json`.
cost-split *args:
    python3 scripts/cost-split.py {{args}}

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

# Claude Code in the jail; extra args go to `claude` itself (`just jail-claude --model opus`, `--resume`)
jail-claude *args:
    ai-jail --no-save-config --rw-map ~/.claude --rw-map ~/.claude.json --map ~/.ssh --network --terminal-passthrough claude {{args}}

# The two below pin the model via Claude Code's own alias (always the latest of that line), and
# still forward any further args to `claude`, e.g. `just jcs --resume`.

# jail-claude with --model fable
jcf *args: (jail-claude "--model" "fable" args)

# jail-claude with --model sonnet
jcs *args: (jail-claude "--model" "sonnet" args)
