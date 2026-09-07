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
# compile, and .githooks/pre-push, which runs the `just quality` gates
# before anything leaves the machine.
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

# Statement coverage across core/local/azure/cli (sbt-scoverage, project/plugins.sbt). `clean`
# first: an instrumented compile must not reuse a plain one's classfiles. Same recipe ci.yml runs
# on main to publish the README badge (see .github/workflows/ci.yml, site.yml).
coverage:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt clean coverage test coverageReport coverageAggregate

fmt:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtAll

lint:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtCheckAll

# All free code-quality checks, same as ci.yml: `quality-scala` (the lint steps of its build-test
# job) + `quality-other` (its quality-other job). `.githooks/pre-push` runs them before every push,
# so a lint failure surfaces here and not on GitHub after the merge. `just quality-fix` applies
# the auto-fixable ones.
quality: quality-scala quality-other

# scalafmt + scalafix (semantic lint). The compiler's unused-warnings-as-errors are part of `just build`.
quality-scala:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt scalafmtCheckAll "scalafixAll --check"

# The JVM-free gates: ruff on every .py in the repo (no file list — ci.yml's ruff-action lints the
# checkout root, and a list kept here once drifted from it), the scripts/*.py self-tests, the
# gh-billing.sh self-test, actionlint, hadolint. ruff/actionlint/hadolint are in flake.nix, so a
# missing one fails instead of skipping — a silent skip is how an unused import reached main. Only
# the compose check still skips without a Docker CLI (the agent sessions have none).
quality-other:
    #!/usr/bin/env bash
    set -euo pipefail
    for tool in ruff actionlint hadolint; do command -v "$tool" >/dev/null || { echo "quality-other: $tool not installed — run inside 'nix develop' (flake.nix has it)" >&2; exit 1; }; done
    # One command per line: under `set -e` a failing left side of `a && b` does not stop the
    # script (errexit exempts it), and the first pre-push run sailed past a ruff finding that way.
    ruff check .
    ruff format --check .
    python3 scripts/smoke_record.py --self-test
    python3 scripts/benchmark_gate.py --self-test
    python3 scripts/cost-split.py --self-test
    python3 scripts/arxiv_digest.py --self-test
    scripts/gh-billing.sh --self-test
    scripts/deps-stack.sh --self-test
    python3 scripts/lib/req_merge.py --self-test
    python3 scripts/lib/uses_merge.py --self-test
    scripts/mip-stack.sh --self-test
    python3 scripts/lib/mip_index_merge.py --self-test
    python3 scripts/mip_graph.py --self-test
    python3 scripts/mip_graph.py --check
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

# Runs marola's CLI (build.sbt's `cli` project; marola is split into
# core/local/azure/cli, docs/FUTURE-WORK.md §7.3). `*args` forwards CLI flags to the app
# itself, e.g. `just run -- --summarize`.
run *args:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run {{args}}"

# Runs marola's MCP tool server (cli/src/main/scala/marola/agent/SwimConditionsMcpServer.scala)
# — a separate main class from `run`'s (see build.sbt's Compile/run/mainClass note on why plain
# `sbt run` can't pick this one). `-error` silences sbt's own banner/task logging on stdout —
# required for stdio MCP transport, which allows nothing but JSON-RPC frames there; confirmed
# live that sbt's default log level otherwise writes "[info] welcome to sbt..." etc. straight
# into the same stream a client reads as protocol data. cli/src/main/resources/logback.xml
# handles the app's own logging the same way (routes it to stderr).
mcp-server:
    mkdir -p "$XDG_RUNTIME_DIR" && sbt -error "cli/runMain marola.agent.SwimConditionsMcpServer"

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

# Publish a trained .gguf to a Hugging Face model repo (MIP-0025 §5.1). Needs
# `pip install -r finetune/requirements.txt` and a prior `huggingface-cli login`.
# just finetune-publish repo=you/marola-sea-tiny-GGUF gguf=finetune/out/marola-tiny-adapter.gguf base=HuggingFaceTB/SmolLM2-360M-Instruct
finetune-publish repo gguf base *args:
    python3 finetune/publish_hf.py --repo {{repo}} --gguf {{gguf}} --base-model {{base}} {{args}}

# ---------------------------------------------------------------------
# The map — MIP-0005: precomputed boards on a static site (site/)
# ---------------------------------------------------------------------

# Build the static map's data: one pipeline run per area in site/areas.json (or just `area`),
# writing site/dist/data/<area>/{today,tomorrow}.json + latest.json and copying site/static/.
# One Overpass query per area, no LLM. `just site-build floripa`. Then stamps a `?v=<sha>` cache
# buster onto index.html's app.js/style.css tags in site/dist (never in the source under
# site/static/) so a CDN or browser can never mix index.html from one build with app.js from
# another — fix/site-smoke-panel-null, scripts/stamp_site_version.sh.
site-build area="":
    mkdir -p "$XDG_RUNTIME_DIR" && sbt "cli/run -- --site {{area}}"
    scripts/stamp_site_version.sh site/dist

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

# MIP-0010: the experiment ledger — `mlflow server` on SQLite with local artifacts, both under
# .tmp/mlflow/ (gitignored; `rm -rf .tmp/mlflow` resets it), bound to 127.0.0.1:5000 only (no auth).
# Opt in per command: `MAROLA_MLFLOW_TRACKING_URI=http://127.0.0.1:5000 just benchmark`. Unset, every
# marola command behaves as before (RunLedger.Noop) — see RUN-LOCALLY.md §11.
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

# Stop the ledger; the SQLite store and artifacts stay in .tmp/mlflow/.
mlflow-down:
    docker compose --profile mlflow down

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

# A second opinion on one already-written MIP, for a reviewer who is NOT this project's own coding
# agent (a different LLM, or a human) — the point is independence from same-model review bias.
# Packs README.md, AGENTS.md, PHILOSOPHY.md and docs/mips/MIP-NNNN-*.md (+ its .tasks.md if one
# exists) plus a "this is a MIP review request" framing (repomix-instruction-mip-review.md) into
# .tmp/marola-context-mip-MIP-NNNN.md and copies it to the clipboard. `just context-mip MIP-0010`.
# Deliberately narrow: it does NOT pull whatever the MIP's own "Related" row points at (other
# MIPs, ARCHITECTURE.md sections) — every existing MIP's Related row names at least one doc this
# pack omits, so for a MIP that leans heavily on one of those, ask the reviewer to flag the gap
# (the instruction file asks them to) rather than assuming three fixed docs are always enough.
context-mip mip:
    #!/usr/bin/env bash
    set -euo pipefail
    num="$(grep -oE '[0-9]{4}' <<<"{{mip}}" | head -1)"
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

# Add a missing Cost:/Tested: trailer to every commit of the current branch that lacks one
# (AGENTS.md), without git filter-branch — commits are replayed with `git cherry-pick`, author/
# committer dates preserved. Cost prefers a measured figure (scripts/cost-split.py's session
# logs, subagent transcripts included); nothing logged at all falls back to
# `scripts/cost-split.py --estimate-commit`, always labelled `est.`. Idempotent: a commit that
# already carries both trailers is untouched. `just cost-fill --dry-run` to preview.
# See scripts/cost-fill.sh.
cost-fill *args:
    scripts/cost-fill.sh {{args}}

# The whole agent PR workflow in one command (AGENTS.md "Attribution and cost accounting"):
# refuses on main or a dirty tree, `just cost-fill`s any commit missing a trailer, pushes (a
# mip-NNNN/k-* branch goes through `scripts/stack.sh pr` for the right base), then `just uprd`
# writes or updates the PR body and this prints its URL. `just pr --dry-run` prints every step
# and the generated body — no push, no `gh`. See scripts/pr.sh.
pr *args:
    scripts/pr.sh {{args}}

# Apply the deterministic label taxonomy (scripts/lib/pr_labels.sh) to one PR — the current
# branch's, or `just pr-label 168`. `--dry-run` prints without calling `gh pr edit`. No LLM, no
# cost: labels come from the PR's MIP number, changed top-level dirs, and author. See
# scripts/pr-label.sh.
pr-label *args:
    scripts/pr-label.sh {{args}}

# Backfill labels onto every merged/closed PR that has none yet (never touches an open PR, and
# never a PR that already has a label — re-running is a no-op scan). `--dry-run` to preview,
# `--limit N` to cap a first cautious run. See scripts/backfill-pr-labels.sh.
pr-labels-backfill *args:
    scripts/backfill-pr-labels.sh {{args}}

# scripts/stack.sh passthrough: `just stack start MIP-0005 2 site-build`, `just stack pr`,
# `just stack restack`, `just stack status` — the local, script-only view of a MIP stack.
stack *args:
    scripts/stack.sh {{args}}

# Stack every open dependency-update PR (dependabot; `--include-steward` adds scala-steward's)
# into one chain of `deps/<date>/k-slug` branches, the same shape a MIP's task branches get —
# github-actions PRs first, then pip, each group by PR number. Opens one new PR per chain branch
# stacked on the previous (dependabot's own branches are left untouched, and their PRs are closed
# with a pointer to the new ones — see scripts/deps-stack.sh's header for why, and the
# `--retarget-dependabot` non-goal), then links them into a GitHub Stack. The whole chain is built
# in a dedicated worktree (.tmp/wt-deps-stack) so this never switches your own checkout's branch;
# a requirements-file bump-vs-bump conflict (adjacent lines of the same *requirements*.txt)
# resolves itself to the higher lower bound before anything stops for a human.
#   just deps-stack                    # discover, build, publish, link
#   just deps-stack --dry-run          # print every git/gh command; no push, no gh mutation
#   just deps-stack --resume           # continue after a conflict (prints the resolve steps)
#   just deps-stack --skip 123         # drop PR #123 from the chain
#   just deps-stack status             # the local chain + each PR's state
#   just deps-stack clean              # delete deps/* branches whose stacked PR is MERGED
# Needs `gh auth status` OK for anything beyond --dry-run/--from-json/--self-test/status/clean's
# local listing. Not available inside ai-jail (AGENTS.md) — run from the host.
deps-stack *args:
    scripts/deps-stack.sh {{args}}

# Stack every open MIP *draft* PR (a `docs/mip-NNNN-*` branch, or any PR adding a
# `docs/mips/MIP-NNNN-*.md`; task branches `mip-NNNN/k-*` are excluded) into one chain of
# `mips/<date>/k-slug` branches ordered by MIP number — different proposals, one stack that merges
# bottom-up in one CI run. Every draft appends its own row to docs/mips/README.md at the same
# spot, so after the first one lands the rest conflict on that line: the chain build resolves that
# by itself (scripts/lib/mip_index_merge.py keeps both rows, in MIP order). Same shape as
# `just deps-stack`: a dedicated worktree (.tmp/wt-mip-stack), one new PR per chain branch, the
# original PR closed with a pointer, `gh stack link` at the end. See scripts/mip-stack.sh's header.
#   just mip-stack                     # discover, build, publish, link
#   just mip-stack --dry-run           # print every git/gh command; no push, no gh mutation
#   just mip-stack --resume            # continue after a conflict it could not resolve
#   just mip-stack --skip 123          # drop PR #123 from the chain
#   just mip-stack status              # the local chain + each PR's state
#   just mip-stack clean               # delete mips/* branches whose stacked PR is MERGED
# Needs `gh auth status` OK beyond --dry-run/--from-json/--self-test. Run from the host, not ai-jail.
mip-stack *args:
    scripts/mip-stack.sh {{args}}

# Regenerate the Mermaid dependency graph in docs/mips/README.md from every MIP's own **Blocked
# by** metadata row (comma-separated MIP numbers, or `none` — never the prose **Depends on**
# field, which legitimately mixes four relations in one cell a regex can't tell apart). Nodes are
# colored by Status; edges are blocker -> blocked, nothing else.
#   just mip-graph                     # regenerate and write docs/mips/README.md
#   just mip-graph --check             # exit 1 if the checked-in graph is stale (quality-other)
#   just mip-graph --parallel 30 31    # can these two MIPs be worked on at once? (dependency
#                                       # graph reachability AND a §5 source-path overlap check —
#                                       # the graph alone can't see two MIPs touching the same files)
mip-graph *args:
    python3 scripts/mip_graph.py {{args}}

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

# GitHub's own bill this month — Actions minutes, GHCR/Packages storage and transfer, Copilot —
# in one call to the consolidated billing-usage API (scripts/gh-billing.sh). This repo is private,
# so these are real cost, not just hygiene, unlike the "free for public repos" Actions/GHCR quotas
# docker.yml used to assume. `just gh-billing`, `just gh-billing --month 8`,
# `just gh-billing --year 2026 --month 8`. Needs `gh auth status` with a classic PAT carrying the
# `user` scope (not available inside ai-jail — run from the host); `scripts/gh-billing.sh
# --self-test` shapes a saved fixture with no `gh` call at all.
#
# This is a different bill from `claude-cost`/`cost-split` above: those two account for Claude
# Code's own token quota (what a feature cost to build), not anything GitHub charges for.
gh-billing *args:
    scripts/gh-billing.sh {{args}}

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
#
# `--env GH_TOKEN`: the sandbox gets a private home, so the host's `gh auth login` credentials
# never exist inside it and every `gh pr …` the agent runs fails — PR bodies then stay at the
# template's placeholders because `just uprd` never ran. ai-jail has no per-host network or
# per-subcommand allowlist (v1.20.2), and the committed .ai-jail can only tighten, so the
# credential goes in as an env var from the host shell: put `GH_TOKEN=github_pat_…` in the
# gitignored .env (flake.nix's shellHook exports it; the file itself stays masked in the jail).
# Make it a fine-grained token scoped to this one repo — Pull requests: read/write, Contents:
# read, Metadata: read — so what the agent may do on GitHub is bounded by the token, not by the
# sandbox: pr view/create/edit/list and read-only `gh api` work, merge/issues/workflows/secrets
# fail by permission. Unset in the host shell → nothing is passed, same as before.
#
# `--exec`: run `claude` directly under bwrap instead of ai-jail's default mode, which wraps the
# whole session in its own PTY proxy to draw the persistent status bar (see `-s/--status-bar` in
# `ai-jail --help`) — it re-parses the terminal stream through a VT parser to reserve a screen row
# and, on the input side, is one more layer between your keyboard and the sandboxed process. Two
# maintainer-reported symptoms traced to that layer: Ctrl+C never reaching `claude` (SIGINT is
# generated by the *outer* terminal's line discipline, but the proxy puts the terminal in raw mode
# and is responsible for relaying the interrupt byte itself — bare `claude` outside the jail, with
# no such proxy, does not have this problem) and multi-line/bracketed pastes arriving mangled or
# as one line (the proxy's parser is on the same input path). `--exec` removes the proxy entirely
# — `bwrap` execs `claude` with its stdio connected straight to the real terminal, the same as
# running any other command under `bwrap` directly — at the cost of the status bar (cosmetic only;
# no sandbox restriction changes: landlock/seccomp/rlimits/network are all set by the bwrap
# invocation below `--exec` doesn't touch). Confirmed via `ai-jail --help`: "`--exec` Direct
# execution mode (no PTY proxy, no status bar)".
#
# MAROLA_JAIL_CLIPBOARD=1 just jail-claude: opt-in, write-only clipboard bridge — default off, so
# a plain `just jail-claude` behaves exactly as before this existed. Before ai-jail starts, this
# creates a FIFO at .tmp/clip.fifo (the repo dir is already rw-mapped into the jail, so the FIFO
# is visible inside; .tmp/ is gitignored) and backgrounds scripts/clip-relay.sh, which reads that
# FIFO and pipes each payload into wl-copy or xclip — same host-tool detection as the `_clip`
# recipe below. Inside the jail, `just clip` / `scripts/clip.sh` write into the FIFO; there is no
# read/paste counterpart on either side. The relay is stopped (which removes the FIFO) when
# `claude` exits for any reason via this recipe's own EXIT/INT/TERM trap; see clip-relay.sh's
# header for why *it* additionally needs `set -m` and a process-group kill rather than a plain
# `kill $pid` (a plain kill leaves an orphaned reader blocked on the now-deleted FIFO — confirmed
# empirically while building this). If the host has no clipboard tool/display, clip-relay.sh says
# so on stderr and exits immediately; this recipe carries on without a relay either way.
#
# Security: write-only by construction — the jail can only push bytes into a host process that
# calls wl-copy, it has no path to wl-paste and so cannot read the clipboard. Off by default, and
# announces itself with one line when on, so it's never silently active. Residual risk: anything
# the agent copies replaces what you had in the clipboard, and a malicious payload could be a
# shell command you then paste — read before you paste.
#
# MAROLA_JAIL_CLIPBOARD_PASTE=1 just jail-claude: opt-in, real clipboard *read* — separate from
# the bridge above and off by default. Plain text Ctrl+V is unaffected by any of this (the
# terminal emulator injects pasted text as ordinary input bytes; `--exec` above is what keeps that
# path intact) — this flag is only for Claude Code's own image-paste feature, which shells out to
# xclip/wl-paste itself and needs a real X11/Wayland socket inside the sandbox to do it, which
# `--no-display` (the jail's default) denies. Setting it adds `--display` (ai-jail's X11/Wayland
# passthrough) plus forwards `DISPLAY`/`WAYLAND_DISPLAY`, and pins `XDG_RUNTIME_DIR` to the real
# `/run/user/<uid>` (not this file's own `XDG_RUNTIME_DIR` override two lines up, which points at
# a repo-local dir for sbt's boot socket and would otherwise make wl-paste look in the wrong
# place). Security: this is a materially bigger grant than the write-only bridge — a full display
# socket, not a one-way byte pipe. Wayland compositors isolate clients from each other reasonably
# well; X11 (this repo's dev host: `XDG_SESSION_TYPE=x11`) has no such isolation — any client on
# the socket can read other windows' contents and inject synthetic input, not just the clipboard.
# Off by default; only turn it on if you need image paste and accept that broader exposure for the
# session.
jail-claude *args:
    #!/usr/bin/env bash
    set -euo pipefail
    relay_pid=""
    cleanup() {
        if [ -n "$relay_pid" ]; then
            kill -TERM "$relay_pid" 2>/dev/null || true
            wait "$relay_pid" 2>/dev/null || true
        fi
    }
    trap cleanup EXIT INT TERM
    if [ "${MAROLA_JAIL_CLIPBOARD:-0}" = "1" ]; then
        mkdir -p .tmp
        fifo=".tmp/clip.fifo"
        rm -f "$fifo"
        mkfifo "$fifo"
        scripts/clip-relay.sh "$fifo" &
        relay_pid=$!
        sleep 0.2
        if ! kill -0 "$relay_pid" 2>/dev/null; then
            wait "$relay_pid" 2>/dev/null || true
            relay_pid=""
        fi
    fi
    paste_flags=()
    if [ "${MAROLA_JAIL_CLIPBOARD_PASTE:-0}" = "1" ]; then
        echo "jail-claude: MAROLA_JAIL_CLIPBOARD_PASTE=1 — real X11/Wayland display passthrough is on, the jail can read your clipboard (and, on X11, more)" >&2
        paste_flags=(--display --env DISPLAY --env WAYLAND_DISPLAY --env "XDG_RUNTIME_DIR=/run/user/$(id -u)")
    fi
    ai-jail --no-save-config --rw-map ~/.claude --rw-map ~/.claude.json --map ~/.ssh --network --terminal-passthrough --exec --env GH_TOKEN "${paste_flags[@]}" claude {{args}}

# The two below pin the model via Claude Code's own alias (always the latest of that line), and
# still forward any further args to `claude`, e.g. `just jcs --resume`.

# jail-claude with --model fable
jcf *args: (jail-claude "--model" "fable" args)

# jail-claude with --model sonnet
jcs *args: (jail-claude "--model" "sonnet" args)

# jail-claude with --model opus
jco *args: (jail-claude "--model" "opus" args)

# OpenCode in the jail (MIP-0013) — the same ai-jail policy as jail-claude, OpenCode's own three
# state directories mapped instead of Claude Code's: `~/.config/opencode` (opencode.json overrides,
# auth.json — never committed, stays host-side), `~/.local/share/opencode` (session/message
# storage `cost-split.py`'s OpenCode reader will read), `~/.cache/opencode` (Bun's plugin installs
# at startup, per MIP-0013 §4.6). `--network` because OpenCode needs it the same way Claude Code
# does (model calls, plugin installs); `--terminal-passthrough` for its TUI. Extra args go to
# `opencode` itself, e.g. `just jail-opencode run "..."`.
jail-opencode *args:
    ai-jail --no-save-config --rw-map ~/.config/opencode --rw-map ~/.local/share/opencode --rw-map ~/.cache/opencode --network --terminal-passthrough --exec opencode {{args}}

# jail-opencode, short alias
jo *args: (jail-opencode args)

# What OpenCode sessions consumed, from its local storage (~/.local/share/opencode), priced at
# list rates — ccusage's OpenCode support (MIP-0013 §4.5; experimental, unknown models show
# $0.00). `just opencode-cost session`, `just opencode-cost daily`.
opencode-cost *args="session":
    npx --yes ccusage@latest opencode {{args}}

# Push stdin (or --text "…") to the clipboard — write-only, no paste counterpart; see
# scripts/clip.sh's header and this file's `jail-claude` comment. Inside a session started with
# `MAROLA_JAIL_CLIPBOARD=1 just jail-claude`, goes through the host relay via .tmp/clip.fifo;
# outside the jail it falls back to calling wl-copy/xclip directly, so `just clip` is one command
# either way. `printf 'hello' | just clip`, `just clip --text hello`.
clip *args:
    scripts/clip.sh {{args}}
