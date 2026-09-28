# MIP-0064: mkdocs for the docs — a real documentation site at marola.dev/docs

| | |
|---|---|
| **Status** | Accepted — `Tasks: docs/mips/MIP-0064.tasks.md` |
| **Author** | Claude (Opus 5), with Bruno |
| **Created** | 2026-09-28 |
| **Phase** | 3 — extends `site.yml`'s already-live Pages deploy; no Phase 1 or Phase 2 prerequisite, no cloud spend |
| **Related** | MIP-0044 §5.6 (the docs index this replaces), MIP-0005 (the site), MIP-0008 (Docker already in the repo) |
| **Effort** | L — a container-based build (`mkdocs/` + a compose stack), a rewired CI workflow, one `justfile` pair; no Scala, no new module |
| **Gain** | `infra/dev-loop` — 90 Markdown files become searchable instead of 90 links to GitHub; `user value` — the site's Docs tab stops landing on a bare list |
| **Effort vs Gain** | do next — the Docs tab is already shipped and already disappointing, and every later doc written pays off more once this exists |
| **Depends on** | Nothing must merge first. It consumes `api-docs.yml`'s scaladoc/pdoc output and replaces `scripts/build_docs_index.py`, both from MIP-0044; that MIP is already implemented, so this is a rewiring, not a dependency. No Phase 1 gate, no paid resource, so `AGENTS.md`'s cost rule does not bite |
| **Blocked by** | none |
| **Risk** | The generated `mkdocs/docs/` copy step. Every build copies `../docs/` into a throwaway tree, so links that escape `docs/` (`../AGENTS.md`, `../PHILOSOPHY.md`) break silently unless `--strict` catches them — and `--strict` turning red on an unrelated doc edit is the failure that makes people bypass the docs build |
| **Cost so far** | — |

## 1. Summary

marola.dev's Docs tab lands on a hand-built index page that links out to GitHub for every Markdown
file: no rendering, no search, no diagrams. This replaces that page with an
[mkdocs-material](https://squidfunk.github.io/mkdocs-material/) site covering the 18 guides under
`docs/` and all 59 MIPs, with Mermaid diagrams rendered server-side by a self-hosted
[Kroki](https://kroki.io) and the generated scaladoc/pdoc trees folded into the same output. The
setup is ported from `goflink/vrp-solver`, which runs exactly this stack in production.

## 2. Motivation

`scripts/build_docs_index.py` says in its own docstring what it is:

> Markdown docs are linked to the repository rather than mirrored into the site: publishing
> `docs/*.md` is the disclosure decision MIP-0044 §5.5 gates on MIP-0033 §5.1's repo-public
> checklist, which is not this script's call to make.

That gate has since resolved itself — `h0ffmann/marola` is **public** (verified 2026-09-28,
`gh repo view --json visibility` → `PUBLIC`), so every file the index links to is already
world-readable. What is left is the cost of the workaround: a reader who clicks Docs gets a list of
90 outbound links to raw-ish GitHub pages, with no search across them, no cross-linking, and no
rendering of the two Mermaid diagrams the docs already contain (`docs/ARCHITECTURE.md:221`,
`docs/mips/README.md:85` — GitHub renders those, the site does not).

The corpus is big enough that search is the whole point: 18 files at the root of `docs/`, 72 under
`docs/mips/` (59 MIPs, 12 `.tasks.md` companions, the index). "Which MIP decided X" is currently
answered with `grep`, which is fine for an agent in the repo and useless for anyone reading the
site.

MIP-0044 §4.4 did consider mkdocs and passed on it — but for a different job: generating **Python
API docs** from docstrings, where it would have needed `mkdocstrings` and a nav over nine
unpackaged scripts. pdoc won that and keeps winning it. Nothing in that comparison was about prose
documentation, which is what mkdocs is actually for.

## 3. User-visible change

**Before** — `marola.dev/docs/`: one page, a `<ul>` per section, each `<li>` an outbound link to
`github.com/h0ffmann/marola/blob/main/...`. No search box. The MIP section is 59 links titled by
de-kebabed filename.

**After** — `marola.dev/docs/`: mkdocs-material. `README.md` as the landing page, a left sidebar
built from the file tree (Guides, MIPs, API), a working search box over the full text of every
page, `docs/ARCHITECTURE.md`'s pipeline diagram rendered as an SVG, and `/docs/api/scala/core/` and
`/docs/api/python/` still exactly where they are today, now linked from the sidebar instead of
orphaned.

**Locally**, new in the `justfile`:

```console
$ just docs-serve
Docs available at http://localhost:8001
```

```console
$ just docs
...
INFO    -  Documentation built in 6.20 seconds
generated-docs/index.html
generated-docs/mips/MIP-0064-mkdocs-documentation-site/index.html
```

Port 8001, not 8000: `just site-serve` already owns 8000 (`justfile:248`).

## 4. Data sources and dependencies reviewed

### 4.1 The reference: `goflink/vrp-solver`

Read 2026-09-28 via `gh api`. It carries `mkdocs/{Dockerfile,mkdocs.yml,docker-compose.yml,
docker-compose.build.yml,docker-compose.serve.yml}` and `scripts/mkdocs.sh`. The Dockerfile is
`python:3.13-slim` plus four pinned pips (`mkdocs==1.6.1`, `mkdocs-material==9.5.42`,
`mkdocs-kroki-plugin==0.9.0`, `mkdocs-render-swagger-plugin==0.1.2`). `mkdocs.sh` detects podman or
docker, wipes `mkdocs/docs/` and `mkdocs/generated-docs/`, copies `../docs/` and `../README.md`
into place, builds the image, and either builds (compose up, then `cp` the output out of the
container) or serves on 8000. `mkdocs.yml` sets `site_dir: generated-docs/`, the material theme, and
plugins `search`, `offline`, `render_swagger`, `kroki`. The compose file runs Kroki plus
`kroki-mermaid` and `kroki-excalidraw` companions, healthchecked, with `KROKI_VERSION=0.25.0`.

**Taken:** the whole shape — the `mkdocs/` directory, the Dockerfile, the compose split, and
`scripts/mkdocs.sh` including its runtime detection and its `--serve` flag.
**Dropped:** `render_swagger` (marola publishes no OpenAPI spec), `kroki-excalidraw` (nothing uses
it), and `notify-docs-rebuild.yml` (no aggregator repo consumes marola's docs).

### 4.2 The pips

Verified on PyPI, 2026-09-28:

| Package | Latest | Licence | Notes |
|---|---|---|---|
| `mkdocs` | 1.6.1 (2024-08-30) | not stated in the API response | same version vrp-solver pins |
| `mkdocs-material` | 9.7.7 (2026-07-17) | MIT | vrp-solver is on 9.5.42, ~2 years behind |
| `mkdocs-kroki-plugin` | 1.7.0 (2026-09-10) | MIT | vrp-solver is on 0.9.0 |

We pin the current versions, not vrp-solver's. **This matters for the config keys:** the plugin's
README (read 2026-09-28) documents `server_url` / `http_method` / `fence_prefix` in snake_case,
whereas vrp-solver's `mkdocs.yml` uses `ServerURL` / `HttpMethod` — the 0.9.0 spelling. Copying
that file verbatim onto 1.7.0 would fail.

Two plugin settings are load-bearing, both from that README:

- **`fence_prefix: ""`** — the default is `kroki-`, meaning only ` ```kroki-mermaid ` fences render.
  Our two diagrams use plain ` ```mermaid ` because GitHub renders those. Setting the prefix empty
  makes Kroki claim every diagram fence, so **one fence renders in both places** and no existing doc
  is edited.
- **`http_method: POST`** — quoting the README: "On `POST` the retrieved images are stored next to
  the including page in the build directory". With the default `GET` the plugin emits `<img>` tags
  pointing at the Kroki server, which for a self-hosted container means the published page links to
  `localhost`. POST writes real `.svg` files into the output, so the deployed site is self-contained
  and keeps `script-src 'self'` — the property `scripts/strip_external_scripts.py` exists to defend
  (MIP-0044 §4.3).

### 4.3 Kroki

`yuzutech/kroki` on Docker Hub, checked 2026-09-28: latest tag `0.32.1` (vrp-solver pins 0.25.0).
Kroki is MIT-licensed and the images are published by the project itself. Mermaid support requires
the separate `yuzutech/kroki-mermaid` companion container, which is why the compose file has it.
We pin `0.32.1` and run only `kroki` + `kroki-mermaid`.

**Not verified:** whether 0.32.1's mermaid companion renders our two specific diagrams without
change. That is a §7 check, not an assumption.

### 4.4 mkdocs' automatic navigation

Verified 2026-09-28 against mkdocs' own `writing-your-docs.md` on GitHub:

> If not provided, the navigation will be automatically created by discovering all the Markdown
> files in the documentation directory. An automatically created navigation configuration will
> always be sorted alphanumerically by file name (except that index files will always be listed
> first within a sub-section).

So **no `nav:` key**: the sidebar is the file tree. `MIP-0001…MIP-0064` sort correctly as strings
because the numbers are zero-padded to four digits. Page titles come from each file's H1.

`strict` is documented in `configuration.md` as defaulting to `false`, settable in `mkdocs.yml` or
as `--strict`, and "halt[s] processing when a warning is raised" — which is how a broken internal
link becomes a failed build rather than a 404 on marola.dev.

## 5. Design

### 5.1 New files

```
mkdocs/
  Dockerfile                  python:3.13-slim + the three pips from §4.2, pinned
  mkdocs.yml                  site_name, theme material, plugins search/offline/kroki; no nav
  docker-compose.yml          kroki + kroki-mermaid, healthchecked
  docker-compose.build.yml    one-shot `mkdocs build --strict`
  docker-compose.serve.yml    `mkdocs serve --dev-addr=0.0.0.0:8000`, published on the host as 8001
scripts/mkdocs.sh             ported from vrp-solver; `--serve` flag
```

`mkdocs/docs/` and `mkdocs/generated-docs/` are build products and are gitignored.

### 5.2 What `scripts/mkdocs.sh` assembles

```sh
cp -R ../docs/ docs          # 18 guides + docs/mips/ (59 MIPs) + docs/img, docs/benchmarks
cp ../README.md docs/index.md
```

Two departures from the reference:

1. **`docs/README.md` is renamed to `docs/index.md` in the repo.** mkdocs lists index files first
   within a sub-section, so the docs-directory guide becomes that section's landing page rather than
   a page called "README" sorted among the rest. GitHub still renders `index.md`-free directories
   fine, and every in-repo reference to `docs/README.md` is updated in the same PR.
2. **Out-of-tree links are rewritten.** `docs/index.md` points at `../AGENTS.md`,
   `../PHILOSOPHY.md` and `finetune/README.md`; those files are out of scope for the site (they are
   repo-operating instructions, not reader documentation). `mkdocs.sh` rewrites `](../<file>.md` to
   the GitHub blob URL — the repo URL — note `build_docs_index.py`'s own `REPO` constant still says `h0ffmann/marola`,
   which only works because GitHub redirects the rename to `marola-dev/marola`; `mkdocs.sh` uses
   the current name — before building. `--strict` then guarantees nothing else dangles.

### 5.3 Where the API docs go

`api-docs.yml` already produces `out/docs/api/{scala,python}` and the site already serves it. mkdocs
cannot put generated HTML in a Markdown-driven nav, so:

- a checked-in `docs/API.md` describes the trees and links to `api/scala/core/`,
  `api/scala/local/`, `api/scala/cli/` and `api/python/` — this is the page the sidebar shows;
- after `mkdocs build`, the workflow copies `out/docs/api` into `generated-docs/api`, so the links
  resolve in the published tree.

`strip_external_scripts.py` keeps running over the merged output, unchanged.

### 5.4 The workflow

`api-docs.yml` grows one step and one trigger; `site.yml` is **not touched** — it already
`git archive`s `docs` off the `site-data` branch into `site/dist`.

```yaml
on:
  push:
    branches: [main]
    paths:
      - '**/*.scala'
      - 'build.sbt'
      - 'project/**'
      - 'scripts/**.py'
      - 'finetune/build_dataset.py'
      - 'docs/**'          # new — a docs-only edit must republish
      - 'README.md'        # new — it is the landing page
      - 'mkdocs/**'        # new
```

and, after the pdoc step and before the strip/push steps:

```yaml
- name: mkdocs (material + kroki), with the API trees folded in
  run: |
    set -euo pipefail
    scripts/mkdocs.sh
    cp -r out/docs/api mkdocs/generated-docs/api
    rm -rf out/docs && mv mkdocs/generated-docs out/docs
```

The runners are self-hosted with Docker on the host (`AGENTS.md`, "Docker itself is the host's"),
which is what `docker-smoke.yml` already relies on. The job's `timeout-minutes: 30` covers three
extra containers.

`scripts/build_docs_index.py` is deleted, along with its `--self-test` entry in `quality-other`.

### 5.5 `justfile`

```make
docs:        # build to mkdocs/generated-docs
    scripts/mkdocs.sh
docs-serve:  # live reload on 8001
    scripts/mkdocs.sh --serve
```

Nothing here is Scala, nothing goes through an LLM, and no marola code path changes.

## 6. Scoring / safety impact

None. No file under `core/scoring/` is touched and no user-facing recommendation text changes.

## 7. Verification plan

1. `just docs` completes with `mkdocs build --strict` green — this is the real test, because
   `--strict` fails on any unresolved internal link across all 90 pages.
2. `generated-docs/` contains `index.html`, `mips/MIP-0063-github-issue-tracking-standard/index.html`,
   `API/index.html` and `search/search_index.json`.
3. **Kroki, checked live, not assumed:** the built `architecture/index.html` contains an `<img>`
   pointing at a local `.svg` file (not a `localhost:8001` URL), and that SVG is a rendering of the
   §3 pipeline diagram. This is the §4.3 "not verified" item closing.
4. `python3 scripts/strip_external_scripts.py --check out/docs` passes over the merged tree.
5. `just docs-serve` serves on 8001 with live reload while `just site-serve` holds 8000.
6. After merge: `marola.dev/docs/` renders the sidebar and the search box, `marola.dev/docs/api/scala/core/`
   still resolves, and the site's Docs tab (`site/static/index.html:18`) needs no edit — it already
   points at `/docs/`.
7. `just quality` passes (`hadolint` now sees `mkdocs/Dockerfile`, `shellcheck`-via-`quality-other`
   sees `scripts/mkdocs.sh`).

## 8. Risks, limitations, and honest caveats

- **`--strict` is a shared tripwire.** Any doc edit anywhere can now break the docs build. That is
  the point, but it means a Scala PR that renames a doc fails in a workflow its author was not
  thinking about. Mitigated only by the failure being fast and the message naming the file.
- **The copy step is not a symlink.** `mkdocs/docs/` is a throwaway copy, so `mkdocs serve`'s live
  reload watches the copy, not `../docs/`. Editing a real doc during `just docs-serve` does not hot
  reload; the loop is edit → re-run. The reference has the same limitation.
- **Three containers per docs build.** CI gets slower and needs a working Docker daemon for a
  documentation change. This was the accepted trade in choosing the port over a nix-native build.
- **2.9 MB of `docs/` plus ~16 MB of API trees** on the `site-data` branch, republished whenever
  docs change rather than only when Scala changes. Still ~2% of GitHub Pages' 1 GB limit
  (MIP-0044 §4.6, verified there).
- **Nothing validates that the sidebar reads well.** Filesystem order is simple and driftless; it is
  not curated. If the MIP list becomes unreadable, the fix is renaming files, not adding a `nav:`.

## 9. Alternatives considered

- **Do nothing.** The index works and costs nothing. It also cannot search, cannot render a
  diagram, and sends every reader to GitHub — the docs are the main artefact of a project whose
  README is mostly about how it is built.
- **Nix-native mkdocs + public `kroki.io`.** No Docker, a three-line CI step, `just docs` in the dev
  shell. Rejected by the maintainer in favour of matching vrp-solver: the container stack is already
  proven there, and it keeps diagram rendering off a third-party service.
- **mkdocs-material's built-in Mermaid** (`pymdownx.superfences`, no Kroki at all). Simplest
  possible diagrams, zero containers — but it renders client-side via `mermaid.js`, which means a
  third-party script on marola.dev and the exact `script-src 'self'` violation MIP-0044 §4.3
  catalogued. Kroki rendering server-side to SVG is what keeps that claim true.
- **A hand-written `nav:`.** Curated ordering and MIP grouping by status. Rejected: 59 MIPs means
  every new MIP needs a nav edit nothing enforces, and drift in a nav is worse than alphabetical
  order.
- **Extending `build_docs_index.py` to render Markdown itself.** A search index, a theme and a
  Markdown renderer, hand-rolled. That is mkdocs.

## 11. Open questions

- **Which docs deserve a section, and named how?** Filesystem order gives `docs/*.md` flat at the
  top and `mips/` as one bucket. Grouping the guides (`concepts/`, `development/`, `reference/`,
  as vrp-solver does) means moving files in the repo and updating every inbound link. Worth doing,
  but as its own change after the site exists and the ordering is visible.
- **`AGENTS.md`, `PHILOSOPHY.md`, `CONTRIBUTING.md`** are scoped out and link-rewritten to GitHub.
  They are arguably the most-read documents in the repo. Revisit once the site is live.
- **`knowledge/`** — the sourced ocean corpus — is real reference content and would read well as a
  site section, but it is RAG input with its own provenance rules. Out of scope here; a candidate
  for its own MIP if the answer is anything other than "copy it in".
- **Should the dev shell also carry mkdocs?** `flake.nix` gains nothing today, but an agent without
  a Docker daemon cannot preview docs at all. Left out until someone hits it.

## Appendix

### Checked live

- `gh repo view h0ffmann/marola --json visibility` (2026-09-28) → `PUBLIC` (that name still
  resolves; the repo is now `marola-dev/marola`, also `PUBLIC`, per `gh repo view` on the remote). This is what retires
  `build_docs_index.py`'s disclosure caveat.
- `gh api repos/goflink/vrp-solver/git/trees/HEAD?recursive=1` (2026-09-28) → the `mkdocs/` layout
  and `scripts/mkdocs.sh` listed in §4.1; file contents read via `repos/.../contents/`.
- `pypi.org/pypi/{mkdocs,mkdocs-material,mkdocs-kroki-plugin}/json` (2026-09-28) → 1.6.1 /
  9.7.7 (MIT) / 1.7.0 (MIT), release dates as in §4.2.
- `hub.docker.com/v2/repositories/yuzutech/kroki/tags` (2026-09-28) → `latest, 0.32.1, 0.32.0,
  0.31.2, 0.31.1`.
- `raw.githubusercontent.com/AVATEAM-IT-SYSTEMHAUS/mkdocs-kroki-plugin/main/README.md` (2026-09-28)
  → the config table quoted in §4.2: `fence_prefix` default `kroki-`, `http_method` default `GET`
  with the POST note about images "stored next to the including page in the build directory",
  `file_types` default `[svg]`, `tag_format` default `img`.
- `raw.githubusercontent.com/mkdocs/mkdocs/master/docs/user-guide/writing-your-docs.md` and
  `configuration.md` (2026-09-28) → the automatic-nav paragraph and `strict` quoted in §4.4.
- In-repo, 2026-09-28: `docs/` holds 18 `.md` at its root and 72 under `mips/` (59 MIPs, 12 `.tasks.md`, the index); ````mermaid` fences
  at `docs/ARCHITECTURE.md:221` and `docs/mips/README.md:85`; the Docs tab at
  `site/static/index.html:18` → `/docs/`; `just site-serve` on 8000 at `justfile:248`.

### Not checked

- That Kroki 0.32.1's mermaid companion renders marola's two diagrams — §7 item 3 exists to check
  it, and 0.32.1 is seven minor versions past the one vrp-solver runs in production.
- That `mkdocs-material` 9.7.7 and `mkdocs-kroki-plugin` 1.7.0 are mutually compatible on
  `mkdocs` 1.6.1. Each declares support for mkdocs 1.6; the combination was not built here.
- `mkdocs`' licence: PyPI's JSON returns no `license` field for it, so §4.2 says so rather than
  repeating BSD-2-Clause from memory. `mkdocs-material` and `mkdocs-kroki-plugin` both report MIT.
- Build time and image size for the docs job. The 30-minute timeout is assumed sufficient from the
  existing job's headroom, not measured.
- GitHub Pages' 1 GB / 100 GB-per-month figures are quoted from MIP-0044 §4.6, verified there on
  2026-09-07, not re-fetched.
