set shell := ["bash", "-euo", "pipefail", "-c"]

# sbt's launcher needs a writable XDG_RUNTIME_DIR to create its boot server socket
# (sbt.internal.BootServerSocket).
export XDG_RUNTIME_DIR := justfile_directory() + "/.tmp/sbt-runtime"

# marola-devkit's shared recipes (uprd, pr, stack, issue-*, cost-*, runner-*, …), from the tree the
# flake's shellHook links at .devkit. Optional so the file still parses outside `nix develop`.
import? '.devkit/devkit.just'

default:
    @just --list

# One-time, only needed if you're not using `nix develop` (its shellHook does this automatically).
install-hooks:
    git config core.hooksPath .devkit/.githooks

# ---------------------------------------------------------------------
# Git
# ---------------------------------------------------------------------

# Last n commits, one line each.
log n="10" *args="":
    @git --no-pager log -n {{ n }} --abbrev-commit --decorate --date=relative --format='%C(yellow)%h%C(reset) %C(dim)%ad%C(reset) %C(blue)%an%C(reset) %s%C(auto)%d%C(reset)' {{ args }}

# ---------------------------------------------------------------------
# Build / test / lint
# ---------------------------------------------------------------------

build:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt compile

test:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt test

# Statement coverage across core/local/cli (sbt-scoverage, project/plugins.sbt).
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
# The devkit's own scripts are self-tested in its CI; here only marola's run.
quality-other:
    #!/usr/bin/env bash
    set -euo pipefail
    for tool in ruff actionlint hadolint agents-check workflow-runners; do command -v "$tool" >/dev/null || { echo "quality-other: $tool not installed — run inside 'nix develop' (flake.nix has it)" >&2; exit 1; }; done
    # Not `just --fmt --check`: its --unstable style differed between this machine and the CI
    # runner on the same file and version. --list only checks that the file parses.
    just --list >/dev/null
    # One command per line: `set -e` exempts the left side of `a && b`.
    ruff check .
    ruff format --check .
    python3 scripts/smoke_record.py --self-test
    python3 scripts/benchmark_gate.py --self-test
    python3 scripts/repo_stats.py --self-test
    python3 scripts/arxiv_digest.py --self-test
    python3 scripts/awesome_agentic_digest.py --self-test
    scripts/gh-billing.sh --self-test
    scripts/corpus-fetch.sh --self-test
    scripts/marola-sea-pull.sh --self-test
    python3 scripts/analyze_training.py --self-test
    python3 scripts/site_live_check.py --self-test
    scripts/site-data-push.sh --self-test
    scripts/build-resources-tarball.sh --self-test
    python3 scripts/ocr-post.py --self-test
    python3 scripts/mip_graph.py --self-test
    scripts/mkdocs.sh --self-test
    python3 scripts/strip_external_scripts.py --self-test
    workflow-runners
    python3 scripts/mip_graph.py --check
    agents-check
    python3 finetune/train_lora.py --self-test
    python3 finetune/build_dataset.py --self-test
    python3 finetune/build_dpo_dataset.py --self-test
    python3 finetune/preflight.py --self-test
    python3 finetune/merge_export.py --self-test
    node --check site/static/app.js
    node scripts/site_check.js
    actionlint
    hadolint Dockerfile Dockerfile.local mkdocs/Dockerfile
    if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then docker compose --profile mlflow --profile ollama --profile local config --quiet && docker compose -f mkdocs/docker-compose.yml -f mkdocs/docker-compose.build.yml config --quiet && docker compose -f mkdocs/docker-compose.yml -f mkdocs/docker-compose.serve.yml config --quiet && echo "docker compose config: ok"; else echo "docker compose not installed — skipping compose config check"; fi

quality-fix:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtAll scalafixAll
    ruff check --fix . && ruff format .

# Run by the devkit's pre-commit hook (.devkit/.githooks): staged Scala must compile, staged
# workflows must pass actionlint. The lint gates are prepush's.
precommit:
    #!/usr/bin/env bash
    set -euo pipefail
    staged="$(git diff --cached --name-only --diff-filter=ACM)"
    if grep -qE '\.scala$' <<<"$staged"; then
        echo "precommit: staged Scala changes, running sbt Test/compile..."
        mkdir -p "$XDG_RUNTIME_DIR"
        sbt Test/compile
    else
        echo "precommit: no staged .scala files, skipping sbt compile."
    fi
    if ! grep -qE '^\.github/workflows/.*\.ya?ml$' <<<"$staged"; then
        echo "precommit: no staged workflow files, skipping actionlint."
    elif command -v actionlint >/dev/null; then
        echo "precommit: staged workflow changes, running actionlint..."
        actionlint
    else
        echo "precommit: staged workflow changes but actionlint not installed — skipping (use 'nix develop')."
    fi

# Run by the devkit's pre-push hook, which does not pass the pushed refs on: this checks what HEAD
# has that its push target (else origin/main) lacks, so a push of another branch is checked as HEAD.
prepush:
    #!/usr/bin/env bash
    set -euo pipefail
    base="$(git rev-parse -q --verify '@{push}' 2>/dev/null || git merge-base HEAD origin/main 2>/dev/null || true)"
    # site/static/** belongs to a design: each commit touching it names one, or says why not.
    bad=()
    if [ -n "$base" ]; then
        exclude=()
        git rev-parse -q --verify origin/main >/dev/null && exclude=(--not origin/main)
        while read -r sha; do
            [ -n "$sha" ] || continue
            grep -qE '^site/static/' <<<"$(git diff --name-only "$sha^" "$sha" 2>/dev/null)" || continue
            grep -qE '^MIP: (MIP-[0-9]{4}|none — .+)$' <<<"$(git log -1 --format=%B "$sha")" || bad+=("$sha")
        done < <(git rev-list "$base..HEAD" ${exclude[@]+"${exclude[@]}"})
    fi
    if [ "${#bad[@]}" -gt 0 ]; then
        echo "prepush: commit(s) touching site/static/** with no 'MIP:' trailer:" >&2
        for sha in "${bad[@]}"; do echo "  $(git log -1 --format='%h %s' "$sha")" >&2; done
        echo "Add 'MIP: MIP-NNNN' (the design it's for) or 'MIP: none — <reason>' (a bug fix/typo/" >&2
        echo "refactor with no behaviour change — /marola-devkit:mip's 'Not for' list) to each commit's trailers." >&2
        exit 1
    fi
    echo "prepush: running 'just quality-other'..."
    just quality-other
    if [ -z "$base" ] || grep -qE '\.(scala|sbt)$|^project/|^\.scalafmt\.conf$|^\.scalafix\.conf$' <<<"$(git diff --name-only "$base" HEAD)"; then
        echo "prepush: Scala/sbt changes in the pushed commits, running 'just quality-scala'..."
        just quality-scala
    else
        echo "prepush: no Scala/sbt changes in the pushed commits, skipping quality-scala."
    fi

# Run marola's CLI (build.sbt's `cli` project).
run *args:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run {{ args }}"

# Run marola's MCP tool server — a separate main class from `run`'s (see build.sbt).
mcp-server:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt -error "cli/runMain marola.agent.SwimConditionsMcpServer"

watch:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "~compile"

# ---------------------------------------------------------------------
# Ollama — marola's default local LLM backend (LocalLlmClient, docs/1-Using-marola/RUN-LOCALLY.md)
# ---------------------------------------------------------------------

# Make sure an Ollama server is reachable, starting one if not.
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

# Pull the published marola-sea GGUF from Hugging Face into Ollama as `marola-sea`.
#   just marola-sea-pull small Q8_0
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

# marola's live E2E test (E2ESpec) against Overpass/Open-Meteo, plus Ollama if reachable.
e2e:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt \
        'set cli/Test/testOptions := Seq(Tests.Argument(new TestFramework("munit.Framework"), "--include-tags=E2E"))' \
        'cli/testOnly marola.E2ESpec'

# ---------------------------------------------------------------------
# Knowledge (local RAG) and fine-tuning — MIP-0001, docs/4-Research-and-plans/FUTURE-WORK.md §9.1
# ---------------------------------------------------------------------

# Resolve corpus.version's pin into .tmp/knowledge (MIP-0070 §5.4).
corpus-fetch:
    scripts/corpus-fetch.sh

# Ask knowledge/*.md a question — local RAG, Ollama embeds and answers. MIP-0001.
ask question:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --ask \"{{ question }}\""

# Force a re-embed of knowledge/ (normally automatic when a file or the embed model changes).
knowledge-index:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --reindex"

# Tier 2: a trained adapter on its own base, as the Ollama model `marola-sea-<preset>`.
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

# Merge a LoRA adapter into its base and export GGUFs. MIP-0025 §5.1.
finetune-merge preset="tiny" llama_cpp="" *args="":
    python3 finetune/merge_export.py --preset {{ preset }} {{ if llama_cpp != "" { "--llama-cpp " + llama_cpp } else { "--dry-run" } }} {{ args }}

# Publish a trained .gguf to a Hugging Face model repo (MIP-0025 §5.1).
finetune-publish repo gguf base *args:
    python3 finetune/publish_hf.py --repo {{ repo }} --gguf {{ gguf }} --base-model {{ base }} {{ args }}

# ---------------------------------------------------------------------
# The map — MIP-0005: precomputed boards on a static site (site/)
# ---------------------------------------------------------------------

# Build the static map's data into site/dist (MIP-0005; SiteBuilder: board data only, MIP-0070 §5.4).
site-build area="":
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --site {{ area }} --areas site/areas.json"
    mkdir -p site/dist && cp -r site/static/. site/dist/
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
# The docs site — MIP-0064: mkdocs-material + a self-hosted Kroki (mkdocs/)
# ---------------------------------------------------------------------

# Build docs/ into mkdocs/generated-docs. Needs a Docker or Podman daemon. Strict: a broken
# internal link anywhere in docs/ fails this.
docs:
    scripts/mkdocs.sh

# Serve the docs on http://localhost:8001/docs/ (8000 is `just site-serve`'s; the /docs/ path is
# site_url's). The docs are baked into the image, so a doc edit needs a restart — no live reload.
docs-serve:
    scripts/mkdocs.sh --serve

# ---------------------------------------------------------------------
# Docker — MIP-0008: the CLI as an image (Dockerfile, docker-compose.yml)
# ---------------------------------------------------------------------

# Build the CLI image. target=jvm (default), dev, or local (Dockerfile.local). MIP-0008.
docker-build target="jvm":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ "{{ target }}" = local ]; then docker build -f Dockerfile.local -t marola:local .; else docker build --target {{ target }} -t marola:{{ target }} .; fi

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

# Stop the MLflow server.
mlflow-down:
    docker compose --profile mlflow down

# ---------------------------------------------------------------------
# Browser-session context — repomix.config.json, repomix-instruction.md
# ---------------------------------------------------------------------

# Pack README, AGENTS.md, ARCHITECTURE, FUTURE-WORK, the MIP skill and all MIPs (~35k tokens, no
# code) into .tmp/marola-context-mips.md and copy it to the clipboard.
context-mips:
    mkdir -p .tmp && "$(just _repomix)" -c repomix.config.json
    # The MIP template is marola-devkit's mip skill, under .devkit, which repomix skips as gitignored.
    printf '\n# The MIP skill (marola-devkit: plugins/marola-devkit/skills/mip/SKILL.md)\n\n' >> .tmp/marola-context-mips.md
    cat .devkit/plugins/marola-devkit/skills/mip/SKILL.md >> .tmp/marola-context-mips.md
    @just _clip .tmp/marola-context-mips.md

# Pack one MIP for a reviewer outside this project's coding agent (another model, or a human).
context-mip mip:
    #!/usr/bin/env bash
    set -euo pipefail
    num="$(grep -oE '[0-9]{4}' <<<"{{ mip }}" | head -1)"
    if [ -z "$num" ]; then echo "usage: just context-mip MIP-NNNN" >&2; exit 1; fi
    mip_file="$(ls docs/MIPs/MIP-"$num"-*.md 2>/dev/null | head -1)"
    if [ -z "$mip_file" ]; then echo "no docs/MIPs/MIP-$num-*.md found" >&2; exit 1; fi
    include="\"README.md\", \"AGENTS.md\", \"PHILOSOPHY.md\", \"$mip_file\""
    tasks_file="docs/MIPs/MIP-$num.tasks.md"
    [ -f "$tasks_file" ] && include="$include, \"$tasks_file\""
    mkdir -p .tmp
    out=".tmp/marola-context-mip-MIP-$num.md"
    cfg=".tmp/repomix-mip-review-MIP-$num.config.json"
    header="marola — MIP-$num review request pack for a reviewer outside this project's own coding agent (a different model, or a human). README, AGENTS.md, PHILOSOPHY.md plus this one MIP — no other code or docs. See the instruction section for what is being asked."
    # A dedicated -c config: repomix auto-loads repomix.config.json (every MIP) and CLI --include
    # doesn't override it. printf, not a heredoc: an unindented heredoc body ends the recipe.
    printf '{\n  "$schema": "https://repomix.com/schemas/latest/schema.json",\n  "output": {\n    "filePath": "%s",\n    "style": "markdown",\n    "headerText": "%s",\n    "instructionFilePath": "repomix-instruction-mip-review.md"\n  },\n  "include": [%s],\n  "ignore": { "useGitignore": true, "useDefaultPatterns": true }\n}\n' "$out" "$header" "$include" > "$cfg"
    "$(just _repomix)" -c "$cfg"
    just _clip "$out"

# The whole repo, code included, comments stripped — big.
context-full:
    mkdir -p .tmp && "$(just _repomix)" --style markdown --compress --remove-comments -o .tmp/marola-context-full.md .
    @just _clip .tmp/marola-context-full.md

# The nixpkgs (Node) repomix, matched by store path: the unrelated PyPI "repomix" can shadow it
# on PATH and dies on repomix.config.json's "$schema" key. Falls back to the flake's devShell.
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

# Short forms of the devkit's runner-up / runner-status / runner-down: run, ask, stop. Recipes, not
# aliases: an alias to an `import?`ed recipe breaks the whole file when .devkit is absent.
ghar *args:
    gha-runner up {{ args }}

gha:
    gha-runner status

ghas *args:
    gha-runner down {{ args }}

# Regenerate docs/MIPs/README.md's dependency graph from each MIP's **Blocked by** row.
mip-graph *args:
    python3 scripts/mip_graph.py {{ args }}

# GitHub's native Stacks (the "Preview stack" box on a PR) via the official `gh stack` extension.
stack-setup:
    gh auth status >/dev/null 2>&1 || { echo "gh is not logged in — run: gh auth login" >&2; exit 1; }
    gh extension list | grep -q 'github/gh-stack' || gh extension install github/gh-stack
    gh skill install github/gh-stack || echo "gh skill install failed (older gh?) — the extension works without the skill"

# The stack as GitHub sees it (PR numbers, states, bases) — MIP-0005.
stack-view *args:
    gh stack view {{ args }}

# Restack after a bottom PR was squash-merged. MIP-0005.
stack-sync mip="":
    #!/usr/bin/env bash
    set -euo pipefail
    bottom="$(stack branches {{ mip }} | head -1)"
    gh stack checkout "$bottom"
    gh stack sync

# Merge a whole stack (or everything up to one PR) in a single all-or-nothing operation — no
# restack between merges.
stack-merge *args:
    gh stack merge {{ args }}

# ---------------------------------------------------------------------
# Claude Code cost accounting — AGENTS.md "Attribution and cost accounting"
# ---------------------------------------------------------------------

# What Claude Code sessions consumed, from the local session logs (~/.claude/projects), priced
# at list rates — the quota proxy to paste into a PR's "Cost" line.
claude-cost *args="session":
    npx --yes ccusage@latest {{ args }}

# GitHub's own bill this month — Actions minutes, GHCR/Packages storage and transfer, Copilot —
# in one call to the consolidated billing-usage API (scripts/gh-billing.sh).
gh-billing *args:
    scripts/gh-billing.sh {{ args }}

# ---------------------------------------------------------------------
# ai-jail — https://github.com/akitaonrails/ai-jail. Policy: `.ai-jail`.
# ---------------------------------------------------------------------

# Print the sandbox invocation ai-jail would run, without running it.
jail-dry-run *cmd:
    jail-run --dry-run -- {{ cmd }}

# Make sure the HOST has a GitHub credential the jail can borrow. Run on the host, never in a
# jail (a login there goes to an ephemeral HOME). --refresh adds scopes, --login forces a new one.
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

# Analyse a finished training run (HF trainer_state.json) and say what the next should change.
# Defaults to the local checkpoints; for a CI run, `just training-logs <run-id>` first.
analyze-training *args:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -n "{{ args }}" ]; then
        python3 scripts/analyze_training.py {{ args }}
    else
        root="${CKPT_ROOT:-../marola-checkpoints}/${PRESET:-tiny}"
        python3 scripts/analyze_training.py "$root/adapter" "$root/dpo-adapter"
    fi

# Download a marola-sea publish run's logs (default: the latest) into .tmp/training-logs/.
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

# A second checkout at origin/main (.tmp/wt-main), created or fast-forwarded. Detached on
# purpose: git refuses one branch in two worktrees, so holding `main` would break the main checkout.
worktree dir=".tmp/wt-main":
    #!/usr/bin/env bash
    set -euo pipefail
    root="{{ justfile_directory() }}"
    wt="$root/{{ dir }}"
    git -C "$root" fetch origin --quiet
    target="$(git -C "$root" rev-parse origin/main)"
    if git -C "$root" worktree list --porcelain | grep -qx "worktree $wt"; then
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

# Drop worktree registrations whose directories are gone. Never deletes a live worktree.
worktree-prune:
    git worktree prune -v

# Check what marola.dev actually serves (or --base http://localhost:8000).
site-live-check *args:
    python3 scripts/site_live_check.py {{ args }}

# Claude Code in the jail — labs/agentic's jail-run (h0ffmann/nix-config). MAROLA_JAIL_CLIPBOARD*
# still work for one release; the lab's names are JAIL_CLIPBOARD / JAIL_CLIPBOARD_PASTE.
jail-claude *args:
    JAIL_CLIPBOARD="${JAIL_CLIPBOARD:-${MAROLA_JAIL_CLIPBOARD:-0}}" JAIL_CLIPBOARD_PASTE="${JAIL_CLIPBOARD_PASTE:-${MAROLA_JAIL_CLIPBOARD_PASTE:-0}}" jail-run claude {{ args }}

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

# What OpenCode sessions consumed, priced at list rates. MIP-0013.
opencode-cost *args="session":
    npx --yes ccusage@latest opencode {{ args }}

# Push stdin (or --text "…") to the clipboard — write-only.
clip *args:
    clip {{ args }}

# Fetch origin and fast-forward local `main` when it is checked out and clean.
sync-main:
    #!/usr/bin/env bash
    set -euo pipefail
    git fetch origin --quiet
    branch="$(git branch --show-current)"
    if [ -z "$branch" ]; then
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
