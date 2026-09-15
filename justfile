set shell := ["bash", "-euo", "pipefail", "-c"]

# sbt's launcher needs a writable XDG_RUNTIME_DIR to create its boot server socket
# (sbt.internal.BootServerSocket).
export XDG_RUNTIME_DIR := justfile_directory() + "/.tmp/sbt-runtime"

default:
    @just --list

# One-time, only needed if you're not using `nix develop` (its shellHook does this automatically).
install-hooks:
    git config core.hooksPath .githooks

# ---------------------------------------------------------------------
# Git
# ---------------------------------------------------------------------

# Last 10 commits on the current branch, one line each (short hash, relative age, author,
# subject, refs).
log n="10" *args="":
    @git --no-pager log -n {{ n }} --abbrev-commit --decorate --date=relative --format='%C(yellow)%h%C(reset) %C(dim)%ad%C(reset) %C(blue)%an%C(reset) %s%C(auto)%d%C(reset)' {{ args }}

# ---------------------------------------------------------------------
# Build / test / lint
# ---------------------------------------------------------------------

build:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt compile

test:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt test

# Statement coverage across core/local/azure/cli (sbt-scoverage, project/plugins.sbt).
coverage:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt clean coverage test coverageReport coverageAggregate

# Statement % of scripts/**/*.py, measured while each script's own --self-test runs.
coverage-python:
    python3 scripts/repo_stats.py python-coverage

fmt:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtAll

lint:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtCheckAll

# All free quality gates, same as ci.yml. `just quality-fix` applies the auto-fixable ones.
quality: quality-scala quality-other

# scalafmt + scalafix (semantic lint).
quality-scala:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtCheckAll "scalafixAll --check"

# The JVM-free gates: ruff, the script self-tests, actionlint, hadolint. A missing tool fails, never skips.
quality-other:
    #!/usr/bin/env bash
    set -euo pipefail
    for tool in ruff actionlint hadolint; do command -v "$tool" >/dev/null || { echo "quality-other: $tool not installed — run inside 'nix develop' (flake.nix has it)" >&2; exit 1; }; done
    # `just --fmt` is still --unstable (just 1.58.0); the check is cheap and syntax-validates the
    # justfile as a side effect, so a typo'd recipe fails here instead of at whichever `just`
    # subcommand a human or agent happens to run next.
    just --unstable --fmt --check
    # One command per line: under `set -e` a failing left side of `a && b` does not stop the
    # script (errexit exempts it), and the first pre-push run sailed past a ruff finding that way.
    ruff check .
    ruff format --check .
    python3 scripts/smoke_record.py --self-test
    python3 scripts/benchmark_gate.py --self-test
    python3 scripts/cost-split.py --self-test
    python3 scripts/repo_stats.py --self-test
    python3 scripts/pr_label_nlp.py --self-test
    python3 scripts/arxiv_digest.py --self-test
    python3 scripts/awesome_agentic_digest.py --self-test
    scripts/gh-billing.sh --self-test
    scripts/setup-runners.sh --self-test
    scripts/marola-sea-pull.sh --self-test
    scripts/temps.sh --self-test
    python3 scripts/analyze_training.py --self-test
    python3 scripts/site_live_check.py --self-test
    scripts/deps-stack.sh --self-test
    scripts/deps-merge.sh --self-test
    scripts/runner-preflight.sh --self-test
    scripts/gha-runner.sh --self-test
    python3 scripts/lib/req_merge.py --self-test
    python3 scripts/lib/uses_merge.py --self-test
    scripts/mip-stack.sh --self-test
    scripts/docs-mip-stack.sh --self-test
    python3 scripts/lib/mip_index_merge.py --self-test
    python3 scripts/mip_graph.py --self-test
    python3 scripts/strip_external_scripts.py --self-test
    python3 scripts/build_docs_index.py --self-test
    python3 scripts/mip_graph.py --check
    python3 finetune/train_lora.py --self-test
    python3 finetune/build_dataset.py --self-test
    python3 finetune/build_dpo_dataset.py --self-test
    python3 finetune/preflight.py --self-test
    python3 finetune/merge_export.py --self-test
    .claude/hooks/guard-azure.sh --self-test
    .claude/hooks/format.sh --self-test
    .claude/hooks/stop-gate.sh --self-test
    .claude/hooks/session-start.sh --self-test
    node --check site/static/app.js
    node scripts/site_check.js
    actionlint
    hadolint Dockerfile Dockerfile.local
    if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then docker compose --profile mlflow --profile ollama --profile local config --quiet && echo "docker compose config: ok"; else echo "docker compose not installed — skipping compose config check"; fi

quality-fix:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtAll scalafixAll
    ruff check --fix . && ruff format .

# Runs marola's CLI (build.sbt's `cli` project;.
run *args:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run {{ args }}"

# Runs marola's MCP tool server (cli/src/main/scala/marola/agent/SwimConditionsMcpServer.scala)
# — a separate main class from `run`'s (see build.sbt's Compile/run/mainClass note on why plain
# `sbt run` can't pick this one).
mcp-server:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt -error "cli/runMain marola.agent.SwimConditionsMcpServer"

watch:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "~compile"

# ---------------------------------------------------------------------
# Ollama — marola's default local LLM backend (LocalLlmClient, docs/RUN-LOCALLY.md)
# ---------------------------------------------------------------------

# Make sure an Ollama server is reachable, starting one if not. Split out of ollama-up so
# marola-sea-pull can require a server without also pulling ollama-up's default models.
ollama-serve:
    #!/usr/bin/env bash
    set -euo pipefail
    api=http://localhost:11434/api/tags
    if ! curl -sf -m 2 "$api" >/dev/null; then
        mkdir -p "{{ justfile_directory() }}/.tmp"
        echo "ollama: not reachable on localhost:11434 — starting 'ollama serve' in the background"
        nohup ollama serve >"{{ justfile_directory() }}/.tmp/ollama.log" 2>&1 &
        for _ in $(seq 1 30); do
            curl -sf -m 1 "$api" >/dev/null && break
            sleep 1
        done
        curl -sf -m 2 "$api" >/dev/null || { echo "ollama: server did not come up — see .tmp/ollama.log" >&2; exit 1; }
    fi

# Pull the *published* marola-sea model (the trained weights from Hugging Face) into Ollama and
# name it `marola-sea`. Unlike finetune-model (Tier 1: persona on a stock base) and
# Modelfile.adapter (Tier 2: an adapter needing the base locally), this is the real model the
# publish workflow produced — standalone GGUFs, no Modelfile involved.
#   just marola-sea-pull                 # tiny, Q4_K_M, owner from the git remote
# just marola-sea-pull small Q8_0      # another preset/quant
marola-sea-pull preset="tiny" quant="Q4_K_M" owner="": ollama-serve
    scripts/marola-sea-pull.sh {{ preset }} {{ quant }} {{ owner }}

# Make sure an Ollama server is reachable and has `model` pulled.
ollama-up model=env_var_or_default("MAROLA_LOCAL_LLM_MODEL", "llama3.2") embed=env_var_or_default("MAROLA_LOCAL_EMBED_MODEL", "llama3.2"): ollama-serve
    #!/usr/bin/env bash
    set -euo pipefail
    for m in "{{ model }}" "{{ embed }}"; do
        if ollama list | awk 'NR>1 {print $1}' | grep -qx "$m"; then
            echo "ollama: serving, model '$m' already pulled"
        else
            echo "ollama: pulling '$m'..."
            ollama pull "$m"
        fi
    done

# Runs marola's E2E test (cli/src/test/scala/marola/E2ESpec.scala) against live
# Overpass/Open-Meteo, plus a local Ollama server if one's reachable (skipped gracefully
# otherwise — see that file's own `assume(...)` checks).
e2e:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt \
        'set cli/Test/testOptions := Seq(Tests.Argument(new TestFramework("munit.Framework"), "--include-tags=E2E"))' \
        'cli/testOnly marola.E2ESpec'

# ---------------------------------------------------------------------
# Knowledge (local RAG) and fine-tuning — MIP-0001, docs/FUTURE-WORK.md §9.1
# ---------------------------------------------------------------------

# Ask knowledge/*.md a question — local RAG, Ollama embeds and answers. MIP-0001.
ask question:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --ask \"{{ question }}\""

# Force a re-embed of knowledge/ (normally automatic when a file or the embed model changes).
knowledge-index:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --reindex"

# Tier 2: a trained adapter attached to its own base, as `marola-sea-<preset>`. FROM and ADAPTER
# come from the preset table, so switching base produces a second Ollama model rather than
# overwriting the first.
finetune-adapter-model preset="tiny":
    #!/usr/bin/env bash
    set -euo pipefail
    from="$(python3 -c "import sys; sys.path.insert(0, 'finetune'); from train_lora import PRESETS; print(PRESETS['{{ preset }}']['ollama'])")"
    gguf="$PWD/finetune/out/{{ preset }}/adapter.gguf"
    if [ ! -f "$gguf" ]; then
      echo "no adapter GGUF at $gguf — train it, then convert with llama.cpp's convert_lora_to_gguf.py" >&2
      exit 1
    fi
    mkdir -p .tmp
    sed -e "s|^FROM .*|FROM $from|" -e "s|^ADAPTER .*|ADAPTER $gguf|" finetune/Modelfile.adapter > .tmp/Modelfile.adapter
    ollama create marola-sea-{{ preset }} -f .tmp/Modelfile.adapter

# Tier 1: llama3.2 plus marola's persona/decoding as an Ollama model (finetune/Modelfile).
finetune-model base="llama3.2":
    mkdir -p .tmp && sed 's/^FROM .*/FROM {{ base }}/' finetune/Modelfile > .tmp/Modelfile && ollama create marola-llama3.2 -f .tmp/Modelfile

# Tier 2: QLoRA adapter. preset=tiny trains on CPU in minutes; small|base need more.
finetune-train preset="tiny" *args="":
    python3 finetune/train_lora.py --preset {{ preset }} {{ args }}

# marola vs a plain prompt on 22 ocean questions, 3 arms — writes data/benchmark-*.md.
benchmark:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --benchmark"

# Tier 2 prep: chat-format JSONL from the DSPy demos, sea lore and knowledge/.
finetune-dataset:
    python3 finetune/build_dataset.py

# VRAM, RAM, disk and a rough ETA for a fine-tune on this machine, before starting it. MIP-0048.
finetune-preflight preset="tiny" *args="":
    python3 finetune/preflight.py --preset {{ preset }} {{ args }}

# Layer 3 — DPO preference pairs from Reviewer.scala's reject/revise decisions. MIP-0025 §4.3.
finetune-dpo-dataset:
    python3 finetune/build_dpo_dataset.py

# Layer 3 training: DPO on top of an existing SFT adapter (`just finetune-train` first).
finetune-train-dpo preset="tiny" *args="":
    python3 finetune/train_dpo.py --preset {{ preset }} {{ args }}

# Create/update the venv marola-sea trains in — labs/cuda's setup-ml-venv (h0ffmann/nix-config);
# call its bin/python-cuda afterwards, never bin/python.
ml-venv *args:
    REQUIREMENTS=finetune/requirements.txt VENV_ROOT="${VENV_ROOT:-$HOME/.marola-ml-venv}" setup-ml-venv {{ args }}

# One-time host setup: the CUDA binary cache, so torchWithCuda is fetched, not compiled.
gpu-cache-setup *args:
    setup-cuda-cache {{ args }}

# Merge a LoRA adapter into its base and export runnable GGUFs (MIP-0025 §5.1) — the step
# between training and publishing.
finetune-merge preset="tiny" llama_cpp="" *args="":
    python3 finetune/merge_export.py --preset {{ preset }} {{ if llama_cpp != "" { "--llama-cpp " + llama_cpp } else { "--dry-run" } }} {{ args }}

# Publish a trained .gguf to a Hugging Face model repo (MIP-0025 §5.1).
finetune-publish repo gguf base *args:
    python3 finetune/publish_hf.py --repo {{ repo }} --gguf {{ gguf }} --base-model {{ base }} {{ args }}

# ---------------------------------------------------------------------
# The map — MIP-0005: precomputed boards on a static site (site/)
# ---------------------------------------------------------------------

# Build the static map's data into site/dist. MIP-0005.
site-build area="":
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --site {{ area }}"
    scripts/stamp_site_version.sh site/dist

# Serve site/dist at http://localhost:8000 (python3 is in the flake).
site-serve port="8000":
    python3 -m http.server -d site/dist {{ port }}

# Deploy the map to GitHub Pages.
site-deploy target="github":
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{ target }}" in
        github)
            gh workflow run site.yml && echo "queued site.yml — watch it: gh run list --workflow site.yml" ;;
        cloudflare)
            [ -f site/dist/index.html ] || { echo "site/dist is empty — run: just site-build" >&2; exit 1; }
            npx --yes wrangler pages deploy site/dist --project-name "${MAROLA_SITE_PROJECT:-marola}" ;;
        *) echo "unknown target '{{ target }}' — github | cloudflare" >&2; exit 1 ;;
    esac

# ---------------------------------------------------------------------
# Docker — MIP-0008: the CLI as an image (Dockerfile, docker-compose.yml)
# ---------------------------------------------------------------------

# Build one target of the Dockerfile locally as marola:<target> — `jvm` (default), `native`,
# Build the CLI image. target=jvm (default), dev, or local (Dockerfile.local). MIP-0008.
docker-build target="jvm":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ "{{ target }}" = local ]; then docker build -f Dockerfile.local -t marola:local .; else docker build --target {{ target }} -t marola:{{ target }} .; fi

# Run the locally built jvm image against the Ollama on this machine (`--network host`, so
# Run the CLI image with host networking, so a local Ollama on :11434 is reachable.
docker-run *args:
    docker run --rm --network host --env-file <(env | grep '^MAROLA_' || true) marola:jvm {{ args }}

# GraalVM native-image of the CLI → cli/target/marola (MIP-0008 task 3).
native-image:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p "$XDG_RUNTIME_DIR"
    nix shell nixpkgs#graalvmPackages.graalvm-ce --command bash -c '
        export GRAALVM_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v native-image)")")")"
        echo "native-image: $(native-image --version | head -1) at $GRAALVM_HOME"
        sbt -batch cli/nativeImage'
    ls -la cli/target/marola

# Run the GraalVM native binary.
native-run *args:
    ./cli/target/marola {{ args }}

# Start a local MLflow server for the run ledger. MIP-0010.
mlflow-up:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p .tmp/mlflow
    docker compose --profile mlflow up -d --wait mlflow
    echo "mlflow: http://127.0.0.1:5000 — export MAROLA_MLFLOW_TRACKING_URI=http://127.0.0.1:5000 to log runs"

# Open the MLflow UI (or print the URL when no opener is around).
mlflow-ui:
    #!/usr/bin/env bash
    set -euo pipefail
    url=http://127.0.0.1:5000
    curl -fsS "$url/health" >/dev/null 2>&1 || echo "mlflow is not answering at $url — run 'just mlflow-up' first" >&2
    if command -v xdg-open >/dev/null; then xdg-open "$url"; elif command -v open >/dev/null; then open "$url"; else echo "$url"; fi

# Stop the ledger;.
mlflow-down:
    docker compose --profile mlflow down

# ---------------------------------------------------------------------
# Browser-session context — repomix.config.json, repomix-instruction.md
# ---------------------------------------------------------------------

# Pack README, AGENTS.md, ARCHITECTURE, FUTURE-WORK, the MIP skill and all MIPs (~35k tokens, no
# code) into .tmp/marola-context-mips.md and copy it to the clipboard.
context-mips:
    mkdir -p .tmp && "$(just _repomix)" -c repomix.config.json
    @just _clip .tmp/marola-context-mips.md

# A second opinion on one already-written MIP, for a reviewer who is NOT this project's own
# coding agent (a different LLM, or a human) — the point is independence from same-model review
# bias — MIP-0010.
context-mip mip:
    #!/usr/bin/env bash
    set -euo pipefail
    num="$(grep -oE '[0-9]{4}' <<<"{{ mip }}" | head -1)"
    if [ -z "$num" ]; then echo "usage: just context-mip MIP-NNNN" >&2; exit 1; fi
    mip_file="$(ls docs/mips/MIP-"$num"-*.md 2>/dev/null | head -1)"
    if [ -z "$mip_file" ]; then echo "no docs/mips/MIP-$num-*.md found" >&2; exit 1; fi
    include="\"README.md\", \"AGENTS.md\", \"PHILOSOPHY.md\", \"$mip_file\""
    tasks_file="docs/mips/MIP-$num.tasks.md"
    [ -f "$tasks_file" ] && include="$include, \"$tasks_file\""
    mkdir -p .tmp
    out=".tmp/marola-context-mip-MIP-$num.md"
    cfg=".tmp/repomix-mip-review-MIP-$num.config.json"
    header="marola — MIP-$num review request pack for a reviewer outside this project's own coding agent (a different model, or a human). README, AGENTS.md, PHILOSOPHY.md plus this one MIP — no other code or docs. See the instruction section for what is being asked."
    # A dedicated config, not --include on the CLI: repomix auto-loads repomix.config.json from
    # the repo root regardless (its `include: docs/mips/**` pulls in every MIP), and CLI --include
    # does not override that — confirmed by testing, not assumed. -c fully replaces it. Built with
    # printf (one indented line), not a heredoc: an unindented heredoc body reads to `just` itself
    # as the recipe having ended, not as bash script content.
    printf '{\n  "$schema": "https://repomix.com/schemas/latest/schema.json",\n  "output": {\n    "filePath": "%s",\n    "style": "markdown",\n    "headerText": "%s",\n    "instructionFilePath": "repomix-instruction-mip-review.md"\n  },\n  "include": [%s],\n  "ignore": { "useGitignore": true, "useDefaultPatterns": true }\n}\n' "$out" "$header" "$include" > "$cfg"
    "$(just _repomix)" -c "$cfg"
    just _clip "$out"

# Same idea for the whole repo (code included, comments stripped) — big;.
context-full:
    mkdir -p .tmp && "$(just _repomix)" --style markdown --compress --remove-comments -o .tmp/marola-context-full.md .
    @just _clip .tmp/marola-context-full.md

# The Node repomix (nixpkgs, flake.nix) — not the unrelated PyPI "repomix" Python port, which a
# pip/pipx/uv install can shadow it with on PATH: same command name, but it parses our
# repomix.config.json's "$schema" key as a constructor kwarg and dies
# (`RepomixConfig.__init__() got an unexpected keyword argument '$schema'`) instead of the clear
# usage error the old comment here assumed. A bare `--version` number doesn't tell the two apart
# — the Python port prints one too — so require the resolved binary's own path to name the
# nixpkgs derivation (".../-repomix-<ver>/bin/repomix"), not just live under /nix/store: some
# other nix-packaged tool could otherwise squat there. If that's not on PATH at all, this may just
# mean the caller isn't inside `nix develop` yet (a fresh shell/tmux pane that skipped it) — retry
# once through the flake's own devShell before giving up.
_repomix:
    #!/usr/bin/env bash
    set -euo pipefail
    find_nix_repomix() {
        type -aP repomix 2>/dev/null | grep -m1 -E '^/nix/store/[^/]*-repomix-[^/]*/bin/repomix$' || true
    }
    bin="$(find_nix_repomix)"
    if [ -z "$bin" ]; then
        bin="$(nix develop --quiet --command bash -c 'type -aP repomix 2>/dev/null' 2>/dev/null \
            | grep -m1 -E '^/nix/store/[^/]*-repomix-[^/]*/bin/repomix$' || true)"
    fi
    if [ -z "$bin" ]; then
        echo "repomix not found — enter 'nix develop' (flake.nix provides it)" >&2
        exit 1
    fi
    echo "$bin"

_clip file:
    #!/usr/bin/env bash
    set -euo pipefail
    size="$(wc -c < "{{ file }}")"
    if command -v wl-copy >/dev/null 2>&1 && [ -n "${WAYLAND_DISPLAY:-}" ]; then
        wl-copy < "{{ file }}"; echo "copied to clipboard via wl-copy: {{ file }} ($size bytes)"
    elif command -v xclip >/dev/null 2>&1 && [ -n "${DISPLAY:-}" ]; then
        xclip -selection clipboard < "{{ file }}"; echo "copied to clipboard via xclip: {{ file }} ($size bytes)"
    elif command -v pbcopy >/dev/null 2>&1; then
        pbcopy < "{{ file }}"; echo "copied to clipboard via pbcopy: {{ file }} ($size bytes)"
    else
        echo "no clipboard tool/display found — open the file instead: {{ file }} ($size bytes)"
    fi

# Update the current branch's PR description on GitHub from its commits — "What changed" (one
# Write or refresh a PR body from the branch's commits — pr-body.yml runs the same generator.
uprd *args:
    scripts/uprd.sh {{ args }}

# Same for a whole MIP stack (scripts/uprds.sh): — MIP-0005.
uprds *args:
    scripts/uprds.sh {{ args }}

# Add a missing Cost:/Tested: trailer to a commit, measured from the session logs.
cost-fill *args:
    scripts/cost-fill.sh {{ args }}

# The whole agent PR workflow in one command: trailers, push, PR body. AGENTS.md.
pr *args:
    scripts/pr.sh {{ args }}

# Apply the deterministic label taxonomy (scripts/lib/pr_labels.sh) to one PR — the current
# branch's, or `just pr-label 168`.
pr-label *args:
    scripts/pr-label.sh {{ args }}

# Backfill labels onto every merged/closed PR that has none yet (never touches an open PR, and
# never a PR that already has a label — re-running is a no-op scan).
pr-labels-backfill *args:
    scripts/backfill-pr-labels.sh {{ args }}

# scripts/stack.sh passthrough: — MIP-0005.
stack *args:
    scripts/stack.sh {{ args }}

# scripts/docs-mip-stack.sh passthrough — chain several independent, un-merged docs/mip-NNNN-*
# design-doc branches into one base-linked stack for a single review pass.
docs-mip-stack *args:
    scripts/docs-mip-stack.sh {{ args }}

# Stack every open dependency-update PR (dependabot;.
deps-stack *args:
    scripts/deps-stack.sh {{ args }}

# Merge every open dependency-update PR whose checks are green (--dry-run to see what it would do).
deps-merge *args:
    scripts/deps-merge.sh {{ args }}

# Can this machine run the workflows? (tooling, scala-steward's PR permission, runner labels, disk)
runner-preflight:
    scripts/runner-preflight.sh

# Start the self-hosted Actions runner in the background, preflight first. MAROLA_GHA_RUNNER_DIR
# picks the registration directory (default /home/hoffmann/code/actions-runner).
runner-up *args:
    scripts/gha-runner.sh up {{ args }}

# Stop every runner listening on that directory — discovered from the process table, not from a
# pidfile, so one started by hand or by a shell that has since closed is stopped too. Refuses
# while a job is executing; --force stops it anyway and cleans up the worker.
runner-down *args:
    scripts/gha-runner.sh down {{ args }}

# Local process + what GitHub thinks of the runner + the tail of its log.
runner-status:
    scripts/gha-runner.sh status

# Follow the background runner's log.
runner-logs:
    scripts/gha-runner.sh logs

# The short forms, for the three you type: run, ask, stop.
alias ghar := runner-up
alias gha := runner-status
alias ghas := runner-down

# Stack every open MIP *draft* PR (a `docs/mip-NNNN-*` branch, or any PR adding a
# `docs/mips/MIP-NNNN-*.md`;.
mip-stack *args:
    scripts/mip-stack.sh {{ args }}

# Regenerate the Mermaid dependency graph in docs/mips/README.md from every MIP's own **Blocked
# by** metadata row (comma-separated MIP numbers, or `none` — never the prose **Depends on**
# field, which legitimately mixes four relations in one cell a regex can't tell apart).
mip-graph *args:
    python3 scripts/mip_graph.py {{ args }}

# Delete every local branch whose PR gh confirms MERGED (local branch + remote ref, if still
# there) — never the current branch or main.
branches-clean *args:
    scripts/branches.sh clean {{ args }}

# Open a base=main PR for every local *plain* branch (not a mip-NNNN/k-slug stack branch) that's
# ahead of origin/main and has no PR yet.
branches-open *args:
    scripts/branches.sh open {{ args }}

# GitHub's native Stacks (the "Preview stack" box on a PR) via the official `gh stack` extension.
stack-setup:
    gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
    gh extension list | grep -q 'github/gh-stack' || gh extension install github/gh-stack
    gh skill install github/gh-stack || echo "gh skill install failed (older gh?) — the extension works without the skill"

# Link a MIP's *open* PRs into one GitHub Stack, bottom to top (`scripts/stack.sh link`): —
# MIP-0005.
stack-link mip="":
    scripts/stack.sh link {{ mip }}

# The stack as GitHub sees it (PR numbers, states, bases) — MIP-0005.
stack-view *args:
    gh stack view {{ args }}

# After a bottom PR was squash-merged: — MIP-0005.
stack-sync mip="":
    #!/usr/bin/env bash
    set -euo pipefail
    bottom="$(scripts/stack.sh branches {{ mip }} | head -1)"
    gh stack checkout "$bottom"
    gh stack sync

# Merge a whole stack (or everything up to one PR) in a single all-or-nothing operation — no
# restack between merges.
stack-merge *args:
    gh stack merge {{ args }}

# ---------------------------------------------------------------------
# Claude Code cost accounting — AGENTS.md "Attribution and cost accounting"
# ---------------------------------------------------------------------

# Split a session's real token usage across the commits it produced (scripts/cost-split.py): —
# MIP-0005.
cost-split *args:
    python3 scripts/cost-split.py {{ args }}

# What Claude Code sessions consumed, from the local session logs (~/.claude/projects), priced
# at list rates — the quota proxy to paste into a PR's "Cost" line.
claude-cost *args="session":
    npx --yes ccusage@latest {{ args }}

# GitHub's own bill this month — Actions minutes, GHCR/Packages storage and transfer, Copilot —
# in one call to the consolidated billing-usage API (scripts/gh-billing.sh).
gh-billing *args:
    scripts/gh-billing.sh {{ args }}

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

# Print the sandbox invocation ai-jail would run, without running it — use this to audit a
# command's jail before trusting it for real.
jail-dry-run *cmd:
    jail-run --dry-run -- {{ cmd }}

# --no-save-config keeps a jailed run from writing the host's config.

# Make sure the HOST has a GitHub credential the jail can borrow, then say which one. Run this
# on the host before `just jco` — never inside a jail, where a login goes to an ephemeral HOME.
# Already logged in is the normal case and costs nothing: gh's OAuth token does not expire, so
# this just confirms it and exits. `just gh-auth --refresh` re-runs gh's flow to ADD scopes (that
# one does need a browser approval); `just gh-auth --login` forces a fresh login.
gh-auth *args:
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{ args }}" in
        *--refresh*) exec gh auth refresh ;;
        *--login*)   exec gh auth login ;;
    esac
    if src="$(gh-token --source)"; then
        echo "gh-auth: already authenticated — $src"
        if [ "$src" = "gh:host-login" ]; then gh auth status 2>&1 | sed 's/^/  /'; fi
        echo 'gh-auth: "just jco" will forward this token into the jail; no login needed in there.'
    else
        echo "gh-auth: no credential on this host yet — running gh auth login (one time)."
        gh auth login
        gh-token --source >/dev/null && echo "gh-auth: done — now run: just jco"
    fi
# CPU/GPU temperature with a verdict — for watching a long marola-sea training run.
# `just temps` for one snapshot, `just temps --watch` to follow it, `just temps --json` for a log.
# Thresholds are the hardware's own (coretemp max/crit, nvidia-smi slowdown/shutdown), not
# invented numbers. GPU readings need the host: /dev/nvidia* is not mapped into the jail.
temps *args:
    scripts/temps.sh {{ args }}

# Analyse a finished training run and say what the next one should change. Reads HF Trainer's
# trainer_state.json (both trainers set report_to=[], so there is no MLflow/W&B run to open).
#   just analyze-training                       # the local checkpoints, both stages
#   just analyze-training ../some/adapter       # a specific run
# For a run that happened in CI: `just training-logs <run-id>` first, then point this at it.
analyze-training *args:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -n "{{ args }}" ]; then
        python3 scripts/analyze_training.py {{ args }}
    else
        root="${CKPT_ROOT:-../marola-checkpoints}/${PRESET:-tiny}"
        python3 scripts/analyze_training.py "$root/adapter" "$root/dpo-adapter"
    fi

# Pull a marola-sea CI run's full logs and report down from GitHub, into .tmp/training-logs/.
# Works for the self-hosted runner too — the artifact is uploaded to GitHub either way.
#   just training-logs              # the most recent marola-sea publish run
# just training-logs 12345678     # a specific run id
training-logs run_id="":
    #!/usr/bin/env bash
    set -euo pipefail
    id="{{ run_id }}"
    if [ -z "$id" ]; then
        id="$(gh run list --workflow "marola-sea publish" --limit 1 --json databaseId \
              --jq '.[0].databaseId')"
        echo "training-logs: most recent run is $id"
    fi
    mkdir -p .tmp/training-logs
    gh run download "$id" --dir .tmp/training-logs
    echo "training-logs: downloaded to .tmp/training-logs — analyse with:"
    echo "  just analyze-training .tmp/training-logs/*/"

# A second checkout of this repo that tracks origin/main, so you can build and test what has
# just merged while an agent works on a branch in the main checkout — no marola2 clone, no
# second remote, one .git. Lives at .tmp/wt-main, which is already gitignored and already
# excluded from ruff/scoverage/cloc as a `.tmp/wt-*` path.
#
# DETACHED at origin/main on purpose, not `checkout main`: git refuses to check out one branch in
# two worktrees, so a worktree holding `main` breaks the moment the main checkout goes back to it.
# Detached sidesteps that entirely and is what "always synced on main" actually wants — you read
# and run here, you commit in the other checkout.
#
#   just worktree            # create it, or fast-forward it to the latest origin/main
# cd .tmp/wt-main && nix develop
worktree dir=".tmp/wt-main":
    #!/usr/bin/env bash
    set -euo pipefail
    root="{{ justfile_directory() }}"
    wt="$root/{{ dir }}"
    git -C "$root" fetch origin --quiet
    target="$(git -C "$root" rev-parse origin/main)"
    if git -C "$root" worktree list --porcelain | grep -qx "worktree $wt"; then
        # Never clobber work in progress — say so and stop, the same contract as sync-main.
        if [ -n "$(git -C "$wt" status --porcelain)" ]; then
            echo "worktree: $wt has uncommitted changes — not touching it" >&2
            echo "          commit/stash them there, or pass a different dir" >&2
            exit 1
        fi
        before="$(git -C "$wt" rev-parse HEAD)"
        git -C "$wt" checkout --quiet --detach "$target"
        if [ "$before" = "$target" ]; then
            echo "worktree: $wt already at origin/main ($(echo "$target" | cut -c1-7))"
        else
            echo "worktree: $wt $(echo "$before" | cut -c1-7) -> $(echo "$target" | cut -c1-7) (origin/main)"
        fi
    else
        mkdir -p "$(dirname "$wt")"
        git -C "$root" worktree add --detach "$wt" "$target"
        echo "worktree: created $wt at origin/main"
    fi
    echo "  cd {{ dir }} && nix develop"

# Drop worktree registrations whose directories are gone (this repo accumulates them from agent
# runs and /tmp experiments). Only removes bookkeeping for already-deleted directories — it never
# deletes a worktree that still exists, so it is safe to run any time.
worktree-prune:
    #!/usr/bin/env bash
    set -euo pipefail
    before=$(git worktree list | wc -l)
    git worktree prune -v
    after=$(git worktree list | wc -l)
    echo "worktree-prune: $before -> $after registered ($((before - after)) stale entries removed)"

# Register N self-hosted Actions runners so CI runs in parallel, not FIFO. One runner takes one
# job at a time, and ci.yml alone has four that used to run concurrently.
#   just runners            # 3, installed as services
#   just runners 4          # 4
# just runners --status   # what is registered and running
runners *args:
    scripts/setup-runners.sh {{ args }}

# Check what marola.dev actually serves — that the boards carry water sampling points, that the
# canary beach is present, that the schema is one the page understands. Every other gate checks
# inputs (fixtures, schemas, a stub DOM); this is the only one that looks at the published result.
#   just site-live-check                          # https://marola.dev
# just site-live-check --base http://localhost:8000
site-live-check *args:
    python3 scripts/site_live_check.py {{ args }}

# Claude Code in the jail — labs/agentic's jail-run (h0ffmann/nix-config). MAROLA_JAIL_CLIPBOARD*
# still work for one release; the lab's names are JAIL_CLIPBOARD / JAIL_CLIPBOARD_PASTE.
jail-claude *args:
    JAIL_CLIPBOARD="${JAIL_CLIPBOARD:-${MAROLA_JAIL_CLIPBOARD:-0}}" JAIL_CLIPBOARD_PASTE="${JAIL_CLIPBOARD_PASTE:-${MAROLA_JAIL_CLIPBOARD_PASTE:-0}}" jail-run claude {{ args }}

# The two below pin the model via Claude Code's own alias (always the latest of that line), and
# still forward any further args to `claude`, e.g.

# jail-claude with --model fable.
jcf *args: (jail-claude "--model" "fable" args)

# jail-claude with --model sonnet.
jcs *args: (jail-claude "--model" "sonnet" args)

# jail-claude with --model opus.
jco *args: (jail-claude "--model" "opus" args)

# OpenCode in the same jail, with its own state directories instead of Claude Code's. MIP-0013.
jail-opencode *args:
    jail-run opencode {{ args }}

# jail-opencode, short alias.
jo *args: (jail-opencode args)

# GitHub's spec-kit (github.com/github/spec-kit) — a spec-driven-development CLI, `specify`.
specify *args:
    #!/usr/bin/env bash
    set -euo pipefail
    args=({{ args }})
    if [ "${args[0]:-}" = "init" ] && ! printf '%s\n' "${args[@]:-}" | grep -qx -- --integration; then
        args+=(--integration claude)
    fi
    uvx --from specify-cli specify "${args[@]:-}"

# What OpenCode sessions consumed, from its local storage (~/.local/share/opencode), priced at
# list rates — ccusage's OpenCode support (MIP-0013 §4.5;.
opencode-cost *args="session":
    npx --yes ccusage@latest opencode {{ args }}

# Push stdin (or --text "…") to the clipboard — write-only, no paste counterpart;.
clip *args:
    clip {{ args }}

# Fast-forward local `main` from origin — always fetches (safe, no working-tree effect);.
sync-main:
    #!/usr/bin/env bash
    set -euo pipefail
    git fetch origin --quiet
    branch="$(git branch --show-current)"
    if [ -z "$branch" ]; then
        # A detached checkout — `just worktree`'s main mirror is the common case, and printing
        # "on ''" made it look broken. That mirror is fast-forwarded by `just worktree`, not here.
        echo "sync-main: detached HEAD (a worktree mirror?) — fetched origin only, no ref updated"
        exit 0
    fi
    if [ "$branch" != "main" ]; then
        echo "sync-main: on '$branch', not 'main' — fetched origin only, no ref updated"
        exit 0
    fi
    if [ -n "$(git status --porcelain)" ]; then
        echo "sync-main: local main has uncommitted changes — not touching it (stash first: git stash push -u)"
        exit 0
    fi
    before="$(git rev-parse HEAD)"
    git merge --ff-only origin/main --quiet
    after="$(git rev-parse HEAD)"
    if [ "$before" != "$after" ]; then
        echo "sync-main: fast-forwarded main $before -> $after"
    fi
