set shell := ["bash", "-euo", "pipefail", "-c"]

# marola-devkit's shared recipes (uprd, pr, stack, issue-*, cost-*, runner-*, …), from the tree the
# flake's shellHook links at .devkit. Optional so the file still parses outside `nix develop`.
import? '.devkit/devkit.just'

default:
    @[ -e .devkit/devkit.just ] || echo "no .devkit in this checkout: the devkit's recipes (pr, stack, issue-*, …) are missing — run 'just devkit-link'" >&2
    @just --list

# Link this worktree's .devkit to the main checkout's, for a worktree made outside `nix develop`
# and `just worktree`. Touches nothing but this worktree's link.
devkit-link:
    #!/usr/bin/env bash
    set -euo pipefail
    here="$(git rev-parse --show-toplevel)"
    main="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
    if [ -e "$here/.devkit/devkit.just" ]; then echo "devkit-link: $here/.devkit is already there"; exit 0; fi
    [ -e "$main/.devkit/devkit.just" ] || { echo "devkit-link: $main has no .devkit to link to — run 'nix develop' there, or here" >&2; exit 1; }
    ln -sfn "$(readlink -f "$main/.devkit")" "$here/.devkit"
    echo "devkit-link: linked $here/.devkit"

# This worktree's .devkit, then the hooks: an absolute core.hooksPath, because every worktree shares
# the setting and a relative one resolves per worktree. Points at the main checkout's link.
install-hooks: devkit-link
    #!/usr/bin/env bash
    set -euo pipefail
    main="$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")"
    [ -e "$main/.devkit/.githooks/pre-push" ] || { echo "install-hooks: $main has no .devkit — run 'nix develop' there first" >&2; exit 1; }
    git config core.hooksPath "$main/.devkit/.githooks"
    echo "install-hooks: core.hooksPath = $main/.devkit/.githooks"

# ---------------------------------------------------------------------
# Git
# ---------------------------------------------------------------------

# Last n commits, one line each.
log n="10" *args="":
    @git --no-pager log -n {{ n }} --abbrev-commit --decorate --date=relative --format='%C(yellow)%h%C(reset) %C(dim)%ad%C(reset) %C(blue)%an%C(reset) %s%C(auto)%d%C(reset)' {{ args }}

# ---------------------------------------------------------------------
# Build / test / lint
# ---------------------------------------------------------------------

# All free quality gates, same as ci.yml. `just quality-fix` applies the auto-fixable ones. The
# app's gates are marola-app's own (`cd marola-app && just quality`).
quality: quality-other

# rustfmt, clippy and the tests of tools/ (marola-tools), merge-key-check, the script self-tests, actionlint, hadolint, docs-lint, `wiring --check` (needs the
# submodules checked out), `decisions --check`. A missing tool fails, never skips.
# The devkit's own scripts are self-tested in its CI; here only marola's run.
quality-other:
    #!/usr/bin/env bash
    set -euo pipefail
    [ -e .devkit/devkit.just ] || echo "quality-other: no .devkit here — the devkit's recipes (pr, stack, issue-*) are missing; run 'nix develop', or 'just devkit-link'" >&2
    for tool in cargo actionlint hadolint agents-check workflow-runners docs-lint wiring decisions merge-key-check; do command -v "$tool" >/dev/null || { echo "quality-other: $tool not installed — run inside 'nix develop' (flake.nix has it)" >&2; exit 1; }; done
    # Not `just --fmt --check`: its --unstable style differed between this machine and the CI
    # runner on the same file and version. --list only checks that the file parses.
    just --list >/dev/null
    # One command per line: `set -e` exempts the left side of `a && b`.
    cargo fmt --manifest-path tools/Cargo.toml --check
    cargo clippy --manifest-path tools/Cargo.toml --locked --all-targets -- -D warnings
    cargo test --manifest-path tools/Cargo.toml --locked
    merge-key-check .
    scripts/gh-billing.sh --self-test
    scripts/site-data-push.sh --self-test
    scripts/pointer-sync.sh --self-test
    scripts/pointer-sync-merge.sh --self-test
    scripts/marola-tools claude-project-check
    scripts/release.sh --self-test
    scripts/mkdocs.sh --self-test
    scripts/prepare-docs.sh --self-test
    scripts/fetch-api-docs.sh --self-test
    node scripts/docs_redirect_check.js
    scripts/agents_repos_check.sh --self-test
    scripts/agents_repos_check.sh
    wiring --check docs/2-Building-marola/REPOS.md
    decisions --mips docs/MIPs --check
    workflow-runners
    scripts/marola-tools mip-graph --check
    scripts/marola-tools citation --check
    scripts/marola-tools reading-time --check
    just figure-check
    agents-check
    docs-lint
    actionlint
    hadolint mkdocs/Dockerfile
    if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then docker compose -f mkdocs/docker-compose.yml -f mkdocs/docker-compose.build.yml config --quiet && docker compose -f mkdocs/docker-compose.yml -f mkdocs/docker-compose.serve.yml config --quiet && echo "docker compose config: ok"; else echo "docker compose not installed — skipping compose config check"; fi

quality-fix:
    cargo fmt --manifest-path tools/Cargo.toml && cargo clippy --manifest-path tools/Cargo.toml --fix --allow-dirty --all-targets

# Run by the devkit's pre-commit hook (.devkit/.githooks): staged workflows must pass actionlint.
# The lint gates are prepush's.
precommit:
    #!/usr/bin/env bash
    set -euo pipefail
    staged="$(git diff --cached --name-only --diff-filter=ACM)"
    if ! grep -qE '^\.github/workflows/.*\.ya?ml$' <<<"$staged"; then
        echo "precommit: no staged workflow files, skipping actionlint."
    elif command -v actionlint >/dev/null; then
        echo "precommit: staged workflow changes, running actionlint..."
        actionlint
    else
        echo "precommit: staged workflow changes but actionlint not installed — skipping (use 'nix develop')."
    fi

# Run by the devkit's pre-push hook.
prepush:
    just quality-other

# ---------------------------------------------------------------------
# The docs site — MIP-0064: mkdocs-material + a self-hosted Kroki (mkdocs/)
# ---------------------------------------------------------------------

# Build the aggregated docs (docs/ plus every checked-out submodule's, as docs.yml does) into
# mkdocs/generated-docs. Needs a Docker or Podman daemon and `git submodule update --init`.
# Strict: a broken internal link anywhere fails this, including links into marola-app's pages.
docs:
    scripts/prepare-docs.sh
    DOCS_SRC=.tmp/docs-aggregated scripts/mkdocs.sh

# Serve the docs on http://localhost:8001/. The docs are baked into the image, so a doc edit needs
# a restart — no live reload.
docs-serve:
    scripts/prepare-docs.sh
    DOCS_SRC=.tmp/docs-aggregated scripts/mkdocs.sh --serve

# ---------------------------------------------------------------------
# Browser-session context — repomix.config.json, repomix-instruction.md
# ---------------------------------------------------------------------

# Pack README, AGENTS.md, marola-app's README, ARCHITECTURE, REPOS, PHASES, FUTURE-WORK, the MIP
# skill and all MIPs (no code) into .tmp/marola-context-mips.md and copy it to the clipboard. Reads
# the marola-app submodule: `git submodule update --init` first.
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
    include="\"README.md\", \"AGENTS.md\", \"docs/3-Ways-of-working/PHILOSOPHY.md\", \"$mip_file\""
    tasks_file="docs/MIPs/MIP-$num.tasks.md"
    [ -f "$tasks_file" ] && include="$include, \"$tasks_file\""
    mkdir -p .tmp
    out=".tmp/marola-context-mip-MIP-$num.md"
    cfg=".tmp/repomix-mip-review-MIP-$num.config.json"
    header="marola — MIP-$num review request pack for a reviewer outside this project's own coding agent (a different model, or a human). README, AGENTS.md, docs/3-Ways-of-working/PHILOSOPHY.md plus this one MIP — no other code or docs. See the instruction section for what is being asked."
    # A dedicated -c config: repomix auto-loads repomix.config.json (every MIP) and CLI --include
    # doesn't override it. printf, not a heredoc: an unindented heredoc body ends the recipe.
    printf '{\n  "$schema": "https://repomix.com/schemas/latest/schema.json",\n  "output": {\n    "filePath": "%s",\n    "style": "markdown",\n    "headerText": "%s",\n    "instructionFilePath": "repomix-instruction-mip-review.md"\n  },\n  "include": [%s],\n  "ignore": { "useGitignore": true, "useDefaultPatterns": true }\n}\n' "$out" "$header" "$include" > "$cfg"
    "$(just _repomix)" -c "$cfg"
    just _clip "$out"

# The workspace with every checked-out submodule's code, comments stripped — big. Its own -c config:
# a bare run auto-loads repomix.config.json and packs only the MIP set.
context-full:
    mkdir -p .tmp
    printf '{\n  "output": { "filePath": ".tmp/marola-context-full.md", "style": "markdown", "compress": true, "removeComments": true },\n  "ignore": { "useGitignore": true, "useDefaultPatterns": true }\n}\n' > .tmp/repomix-full.config.json
    "$(just _repomix)" -c .tmp/repomix-full.config.json
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
    scripts/marola-tools mip-graph {{ args }}

# Merge a whole stack (or everything up to one PR) in a single all-or-nothing operation — no
# restack between merges.
stack-merge *args:
    gh stack merge {{ args }}

# ---------------------------------------------------------------------
# The README's figures and reading time (AGENTS.md: figures from source, reading time computed)
# ---------------------------------------------------------------------

# The Archify that draws docs/archify/*.json, pinned (v3.0.1), the same one h0ffmann/ptbr-writer
# vendors. Fetched into .tmp/, never a submodule: pointer-sync moves every gitlink.
archify_rev := "2ab3cae7ac2c2a55d7386ca789d03c4fcd31816c"

figure-init:
    #!/usr/bin/env bash
    set -euo pipefail
    d=.tmp/archify
    if [ "$(git -C "$d" rev-parse HEAD 2>/dev/null)" != "{{ archify_rev }}" ]; then
        rm -rf "$d" && git init -q "$d"
        git -C "$d" fetch -q --depth 1 https://github.com/tt-a1i/archify.git "{{ archify_rev }}"
        git -C "$d" checkout -q FETCH_HEAD
    fi
    echo "figure-init: $(git -C "$d" rev-parse --short HEAD)"

# Draw every docs/archify/<slug>.json to docs/img/<slug>.png, and its English twin docs/archify/en/<slug>.json
# to docs/img/en/<slug>.png: Archify validates and checks it (finalize), picasso's look
# (docs/archify/picasso.css) goes over it, headless Chrome takes the picture. Needs node, Chrome
# (CHROME names another) and the Google Fonts; writes docs/archify/figures.lock.
figure: figure-init
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p .tmp/figure docs/img/en
    for src in docs/archify/*.json docs/archify/en/*.json; do
        slug="${src#docs/archify/}"; slug="${slug%.json}"; out=".tmp/figure/$slug"
        rm -rf "$out"; mkdir -p "$out"
        ARCHIFY_UPDATE_CHECK_DISABLED=1 node .tmp/archify/archify/bin/archify.mjs finalize "$(jq -r .diagram_type "$src")" "$src" "$out/page.html" --out-dir "$out/receipts" --quality showcase
        read -r w h < <(grep -o '<svg viewBox="[^"]*"' "$out/page.html" | head -1 | sed -E 's/.*0 0 ([0-9.]+) ([0-9.]+)"/\1 \2/')
        crop="$(awk -F'\t' -v s="$(basename "$slug")" '$1==s {print $2}' docs/archify/crop.tsv)"; h="${crop:-$h}"
        FONTS='<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Fjalla+One&family=Inter:wght@400;600&display=block">' CSS="$(cat docs/archify/picasso.css)" W="$w" H="$h" \
            perl -0pe 's|</head>|$ENV{FONTS}<style>$ENV{CSS}</style></head>|; s|<svg viewBox="0 0 [0-9.]+ [0-9.]+"|<svg viewBox="0 0 $ENV{W} $ENV{H}"|' "$out/page.html" > "$out/frame.html"
        "${CHROME:-google-chrome}" --headless --disable-gpu --hide-scrollbars --force-dark-mode --force-device-scale-factor=2 --user-data-dir="$PWD/$out/profile" --window-size="$w,$h" --virtual-time-budget=3000 --screenshot="$PWD/docs/img/$slug.png" "file://$PWD/$out/frame.html" 2>/dev/null
        echo "figure: docs/img/$slug.png"
    done
    just _figure-lock > docs/archify/figures.lock

# One line per figure: a hash of its source, the look and the crops, then the Archify pin.
_figure-lock:
    #!/usr/bin/env bash
    set -euo pipefail
    for src in docs/archify/*.json docs/archify/en/*.json; do
        slug="${src#docs/archify/}"
        printf '%s\t%s\n' "${slug%.json}" "$(cat "$src" docs/archify/picasso.css docs/archify/crop.tsv | sha256sum | cut -c1-16)-$(cut -c1-7 <<<"{{ archify_rev }}")"
    done

# Every figure has its PNG and an English twin that is the same drawing, each README shows its own
# language's PNG, and no source changed since the PNGs were drawn. No Chrome needed: CI runs it.
figure-check:
    #!/usr/bin/env bash
    set -euo pipefail
    shape='{d: .diagram_type, l: [.lanes[].id], m: .mainPath, n: [.nodes[] | del(.label, .sublabel, .tag)], e: [.edges[] | del(.label)]}'
    for src in docs/archify/*.json; do
        s="$(basename "$src" .json)"; en="docs/archify/en/$s.json"
        test -s "docs/img/$s.png" || { echo "figure-check: no PNG for $src; run just figure" >&2; exit 1; }
        test -s "$en" && test -s "docs/img/en/$s.png" || { echo "figure-check: no English twin $en or its PNG; write it and run just figure" >&2; exit 1; }
        test "$(jq -r .meta.locale "$en")" = en || { echo "figure-check: $en is not meta.locale en" >&2; exit 1; }
        cmp -s <(jq -S "$shape" "$src") <(jq -S "$shape" "$en") || { echo "figure-check: $en is not the same drawing as $src (lanes, nodes or edges differ)" >&2; exit 1; }
        ! grep -qF "docs/img/$s.png" README.en.md || { echo "figure-check: README.en.md embeds the pt-BR docs/img/$s.png; use docs/img/en/$s.png" >&2; exit 1; }
        ! grep -qF "docs/img/en/$s.png" README.md || { echo "figure-check: README.md embeds the English docs/img/en/$s.png; use docs/img/$s.png" >&2; exit 1; }
        ! grep -qF "docs/img/$s.png" README.md || grep -qF "docs/img/en/$s.png" README.en.md || { echo "figure-check: README.md shows $s but README.en.md does not show docs/img/en/$s.png" >&2; exit 1; }
    done
    for en in docs/archify/en/*.json; do test -s "docs/archify/$(basename "$en")" || { echo "figure-check: $en has no pt-BR source" >&2; exit 1; }; done
    just _figure-lock | cmp -s - docs/archify/figures.lock || { echo "figure-check: a source, the look or the Archify pin changed since the PNG; run just figure" >&2; exit 1; }
    echo "figure-check: ok"

# The graphify figure, docs/img/graphify.svg: the code graph of the last main build (the
# routing-bundle branch's graph.json, MIP-0084) drawn by marola-tools graph-svg.
graphify-figure:
    git fetch -q --depth 1 origin routing-bundle
    git show FETCH_HEAD:graph.json > .tmp/graph.json
    scripts/marola-tools graph-svg --graph .tmp/graph.json --out docs/img/graphify.svg

# Write each README's reading-time badge (marola-tools reading-time); quality-other checks it.
reading-time:
    scripts/marola-tools reading-time --write

# ---------------------------------------------------------------------
# Usage and billing
# ---------------------------------------------------------------------

# What Claude Code sessions consumed, from the local session logs (~/.claude/projects), priced
# at list rates.
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
    if [ -e "$root/.devkit/devkit.just" ]; then ln -sfn "$(readlink -f "$root/.devkit")" "$wt/.devkit"; fi
    echo "  cd {{ dir }} && nix develop"

# Drop worktree registrations whose directories are gone. Never deletes a live worktree.
worktree-prune:
    git worktree prune -v

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

# What OpenCode sessions consumed, priced at list rates. MIP-0013.
opencode-cost *args="session":
    npx --yes ccusage@latest opencode {{ args }}

# Push stdin (or --text "…") to the clipboard — write-only.
clip *args:
    clip {{ args }}

# Tag main as vX.Y.Z and push it; release.yml publishes the release and Zenodo mints its DOI
# (MIP-0079). Refuses unless main is clean, level with origin and the citation metadata is valid.
release *args:
    scripts/release.sh {{ args }}

# List the release tags, newest first.
releases:
    git fetch -q --tags origin && git tag -l 'v*' --sort=-v:refname --format='%(refname:short)  %(creatordate:short)  %(subject)'

# Validate .zenodo.json and regenerate CITATION.cff from it; `--check` only checks.
citation *args:
    scripts/marola-tools citation {{ args }}

# Add a person to the citation metadata: a contributor on Zenodo, or with --author a cited author.
#   just contributor-add "Family, Given" --orcid 0000-0000-0000-0000 --affiliation "…" --type Researcher
contributor-add name *args:
    scripts/marola-tools citation add --name "{{ name }}" {{ args }}

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
