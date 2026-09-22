# MIP-0044: marola.dev becomes a site, not just a map — news, a markdown dev blog, generated API docs, a feeds page, about/contact, and a restrained support page

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Opus 5, for M. Hoffmann (request of 2026-09-07, verbatim: "create a MIP for 44 site sections (besides map, map is just a section in the website, explode section for now news rss about dev (markdown blog) contact donate)", read together with the same session's earlier "news, docs, donate, about, rss, contact sections should be added to the site (map in one of the sections) … it could be possible to have a blog style, and read directly posts from MD files, with direct code snippets" / "be careful for not doing too much promotion" and "there is a way to integrate my docs <> wiki <> docs website, would be good to have python docs and scala docs also added in this website, this area may be called dev") |
| **Created** | 2026-09-07 |
| **Phase** | 3 — `ARCHITECTURE.md` §11 item 4 puts `.github/workflows/site.yml` in Phase 3 as "the first deploy artefact … already here and free". Everything here rides that same workflow: no server, no cloud account, no per-visitor cost. No Phase 1 or Phase 2 prerequisite; §9 rejects the one idea (a contact form) that would have needed a backend and thus jumped the gate |
| **Related** | MIP-0005 (the static site and its "no templating engine, no build step" constraint — Implemented; this MIP is the first change to that constraint and argues it explicitly), MIP-0034 (RSS plumbing: §5.6 writes `feed.xml` into `site/dist` and adds one `<link rel="alternate">`, with **no page and no nav** — this MIP's `/feeds/` section is where that plumbing becomes something a human can read), MIP-0018 (self-documentation: its §5.3 exports posts to an *external* blog repo whose technology is its own §11 open question — this MIP supersedes that destination, see §5.3), MIP-0033 §5.1 (repo-public checklist — gates §5.5's `docs/` mirror only), MIP-0008 §5.5 + `ci.yml` (the `site-data` orphan-branch mechanic this MIP reuses for generated API docs), MIP-0022 (the safety footer, which §6 makes the generator append), MIP-0009 (`scripts/site_check.js`, the no-browser page harness this MIP extends), `docs/ROADMAP.md` §7 (no K-row covers this; checked), `docs/FUTURE-WORK.md` (no section covers this; checked) |
| **Effort** | XL — a page generator plus a nav plus a markdown pipeline plus **two** documentation toolchains (JVM `sbt doc`, a new Python `pdoc` in `flake.nix`) plus a new CI workflow, and it edits five load-bearing pieces of existing machinery at once: `site.yml`'s publish allowlist, `scripts/stamp_site_version.sh` (hard-coded to `index.html`), `scripts/site_check.js`, `SiteBuilder.copyStatic`, and `index.html`'s CSP. Also the first third-party JVM dependency the site build has ever had (`org.commonmark`, §4.1) |
| **Gain** | user value (a visitor can find out what marola is, who runs it, and how to reach them — today the site answers none of those); community/outreach (MIP-0018's weekly post needs a place to land, and MIP-0034's feed needs a page to be discoverable from); infra/dev-loop (generated Scala/Python API docs that stay honest because CI regenerates them, instead of a wiki that rots) |
| **Effort vs Gain** | Per §5 item, not one call. `do next` for §5.1–§5.2 (the generator, nav, `/about/` with contact, `/support/`) — small, self-contained, no new dependency except the layout code itself. `do next` for §5.3 (news + dev blog) once §5.1 lands. `do when MIP-0034 lands` for §5.4 (`/feeds/`) — it has literally nothing to link until then. `do when MIP-0033 §5.1 lands` for §5.5's `docs/` mirror. `cheap win` for §5.6's Scala API docs (`sbt doc` already works, verified §4.3); `expensive, defer` for §5.6's Python API docs — verified in §4.4 that the honest output is thin |
| **Depends on** | **MIP-0034 must merge before the `/feeds/` section is buildable** — its §5.6 is what writes `feed.xml`; a feeds page with no feed is not a smaller version of this, it is nothing, so the edge is declared below rather than hand-waved. **MIP-0033 §5.1** (the repo-public checklist: full-history secret scan, `SECURITY.md`, `.env.example` re-read) gates §5.5's mirror of `docs/*.md` onto the public site — publishing those files *is* the same disclosure decision that checklist exists for, even though the repo's visibility flag is technically separate; this is prose-only, because §5.1 is a checklist item inside a partially-implemented MIP, not a merge a graph edge can point at. **MIP-0018** is not a blocker in either direction, but this MIP answers its §11 open question ("what is the blog repo, technically?") with "marola.dev itself" and supersedes its §5.3 blog-repo destination — coordinate before either ships. No Phase 1 gate (this is not the Telegram bot), no paid cloud resource, no API key, no server |
| **Blocked by** | 0034 |
| **Risk** | The site stops being a thing that can't break. Today `site/dist` is four files plus JSON and the failure mode is "the board is stale"; after this it is a generated multi-page site whose build can fail in ways that publish a *half* site — and `site.yml`'s required-files check only knows about `index.html`. The second, quieter risk: a "donate" page is the one part of this that can make marola look like it wants something, and the request itself warned against that — §5.2 keeps it to one page with no banner, no popup, no third-party widget, and the ledger the repo already publishes as its content |
| **Cost so far** | — |

## 1. Summary

marola.dev is a map and nothing else: no page explains what marola is, who made it, how to reach
them, what changed last week, or where the code documentation lives. This MIP turns the map into
**one section of a site**, `map · news · dev · about · support`, plus a `/feeds/` page, built the
way the boards already are: a pure Scala function that takes data and returns a string, written
into `site/dist` by `SiteBuilder`, published by the workflow that already runs every three hours.
The blog is real markdown files in the repo, rendered **at build time** (not in the browser), with
a code-include directive so a snippet in a post is the repo's actual code and cannot drift from it.

## 2. Motivation

Three concrete gaps, each already written down somewhere in this repo:

1. **`site.yml`'s own comment says "Only the map is public."** Its publish allowlist is
   `index.html, app.js, style.css, chat.js, chatbot-config.js, CNAME, vendor/, data/, smoke/,
   coverage/ and nothing else`: enforced by a build step that fails on anything extra. A visitor
   who wants to know what marola is has the tagline and a `<meta description>`, and that is all.
2. **MIP-0034 §5.6 builds a feed nobody can find.** It writes `site/dist/feed.xml` and one
   `<link rel="alternate">` in the head. That is correct plumbing and a dead end for a human: there
   is no page that says what the feed contains, how often it updates, or that it exists.
3. **MIP-0018 §11 asks "What is the blog repo, technically?"** and blocks its own §5.3 on the
   answer. The answer this MIP proposes, marola.dev, is cheaper than any external repo, is
   already deployed, already has CI, and already has a review gate.

And one gap from the request that is not yet written down anywhere: the repo has 54 main Scala
files of which **53 carry a scaladoc comment** (counted 2026-09-07) and ~3,300 lines of Python, and
none of it is rendered anywhere. `sbt doc` is not wired into `build.sbt`, `just`, or CI.

## 3. User-visible change

Before: `https://marola.dev/` is the only URL; `site/dist` holds five files plus `data/`,
`smoke/`, `coverage/`, `vendor/`.

After:

```
marola.dev/                 the map (unchanged: same app.js, same leaflet, same board JSON)
marola.dev/news/            dated operational notes; newest first, one page each
marola.dev/dev/             the blog — long-form posts, markdown, real code snippets
marola.dev/dev/scala/       generated Scaladoc, one entry per module (core/local/cli)
marola.dev/dev/python/      generated pdoc for scripts/ (see §4.4 for what this honestly contains)
marola.dev/dev/docs/        docs/*.md rendered — gated on MIP-0033 §5.1
marola.dev/about/           what marola is, what it is not, sources, privacy, #contact
marola.dev/support/         one page: what this costs, one sponsor link, one wallet address
marola.dev/feeds/           what feeds exist and what each one carries (needs MIP-0034)
```

Every page gets the same header:

```html
<nav class="nav"><a href="/">map</a> <a href="/news/">news</a> <a href="/dev/">dev</a>
  <a href="/about/">about</a> <a href="/support/">support</a></nav>
```

and the same footer, which is where `/feeds/` and the licence live. A post looks like this on disk:

```markdown
---
title: Why the site build retries a connect timeout, not just a 5xx
date: 2026-09-06
tags: [ci, site]
safety: false
---
The 2026-09-06 scheduled deploy died on one `HTTP connect timed out`. Here is the retry:

<!-- include: core/src/main/scala/marola/http/Http.scala L58-L74 -->
```

The `include:` line is resolved at build time against the real file; a missing file or an
out-of-range span **fails the build** (§7). That is the "direct code snippets" half of the request,
made honest: a snippet in a post is the repo's code at that commit, never a copy that drifts.

## 4. Data sources and dependencies reviewed

### 4.1 Markdown → HTML on the JVM
- **`org.commonmark:commonmark`**: CommonMark parser/renderer for Java, AST-based. Verified
  2026-09-07 against `repo1.maven.org`'s `maven-metadata.xml`: latest **0.30.0**,
  `lastUpdated 20260806023351` (i.e. actively released ~a month ago). Core jar is **220,139 bytes**
  (HTTP `content-length`, verified). Licence **BSD-2-Clause** (Maven Central artifact page,
  verified). The extensions this MIP needs exist at the same version, verified individually:
  `commonmark-ext-gfm-tables`, `commonmark-ext-yaml-front-matter`, `commonmark-ext-heading-anchor`,
  all `<release>0.30.0</release>`.
- **`com.vladsch.flexmark:flexmark`**: richer, but verified 2026-09-07 that its Maven metadata's
  newest version is **0.64.8** with `lastUpdated 20230523183154`: three years stale. Rejected.
- **Pick: commonmark 0.30.0**, in `cli/` (where `SiteBuilder` already lives), not in `core/` or
  `local/`: keeping a rendering dependency out of `core/` keeps the pipeline module
  dependency-light, matching `build.sbt`'s existing shape.

### 4.2 Client-side markdown (the alternative, rejected — see §9)
`marked` **18.0.11** (MIT) and `markdown-it` **14.1.0** (MIT), both verified 2026-09-07 via
`api.cdnjs.com`. Both would have to be *vendored* like Leaflet already is
(`site/static/vendor/leaflet.js`, 147,552 bytes on disk, with `LICENSE.leaflet` beside it): the
page's CSP is `script-src` = `default-src 'self'`, so a CDN `<script>` is not an option.

### 4.3 Scaladoc — run live, not assumed
`sbt core/doc` **was actually run** on 2026-09-07 in this repo's `nix develop` shell: exit 0,
3 s, one warning (`Option -classpath was updated`). `local/doc`, `cli/doc` also exit 0.
No plugin needed: `build.sbt` and `project/plugins.sbt` have **no** `doc`/unidoc wiring today
(verified by reading both), so this is Scala 3's built-in scaladoc.

Real output, measured: **core 7.2 MB, local 4.4 MB, cli 4.4 MB, 16 MB total**;
core alone is 411 files / 103 HTML pages. Three *independent* sites, no cross-module links.

**The finding that changes the design:** every one of those 103 pages loads four third-party
scripts:

```
https://cdnjs.cloudflare.com/ajax/libs/dagre-d3/0.6.1/dagre-d3.min.js
https://cdn.jsdelivr.net/npm/graphlib-dot@0.6.2/dist/graphlib-dot.min.js
https://d3js.org/d3.v6.min.js
https://scastie.scala-lang.org/embedded.js
```

(verified by grep over the generated HTML: 103 of 103 pages). marola's map page states in its own
source that it "loads no third-party script", and `app.js` states "Nothing leaves the browser: no
analytics, no cookies". Publishing scaladoc as-is silently breaks both claims. Each page also
carries exactly one inline `<script>` whose entire body is `var pathToRoot = "";`, which a
`script-src 'self'` CSP would block. §5.6 handles both.

### 4.4 Python API docs — what would actually be generated
Measured 2026-09-07 with `ast` over every `.py` this repo owns (9 files, ~3,300 lines):

| File | lines | defs/classes | with docstring |
|---|---|---|---|
| `scripts/cost-split.py` | 968 | 27 | 13 |
| `scripts/arxiv_digest.py` | 357 | 11 | 3 |
| `scripts/awesome_agentic_digest.py` | 357 | 10 | 3 |
| `scripts/mip_graph.py` | 355 | 15 | 2 |
| `scripts/smoke_record.py` | 261 | 6 | 1 |
| `scripts/benchmark_gate.py` | 147 | 6 | 1 |
| `dspy/compile_recommendation_prompt.py` | 567 | 11 | 10 |
| `finetune/build_dataset.py` | 144 | 8 | 1 |
| `finetune/train_lora.py` | 141 | 2 | 1 |

Every file has a **module** docstring (9/9) and every file is `if __name__ == "__main__"` guarded
(9/9, verified), but only **36 of 96** functions/classes carry one, ~37%. There is no
`pyproject.toml`, no `setup.py`, and no `__init__.py` anywhere in the repo's own Python (verified);
these are CLI scripts, not a library. Imports are **stdlib-only** across all of `scripts/` and
`scripts/lib/` and `finetune/build_dataset.py`; only `dspy/compile_recommendation_prompt.py`
imports a third party (`dspy`).

- **pdoc**, verified 2026-09-07 at `pdoc.dev/docs/pdoc.html`: accepts file paths (`pdoc ./demo.py`)
  as well as module names, needs **no config file**, and "makes heavy use of dynamic analysis …
  your Python modules will be executed/imported when pdoc runs": so it works on the stdlib-only
  scripts and would need `dspy` installed to document `dspy/`.
- **Sphinx**: needs a `conf.py`, a theme, and `automodule` directives naming importable modules.
  For nine unpackaged scripts that is more scaffolding than content.
- **mkdocs**: needs `mkdocs.yml`, a nav, and `mkdocstrings` to read docstrings at all.
- **Pick: pdoc**, scoped to `scripts/` + `scripts/lib/` + `finetune/build_dataset.py` (the
  stdlib-only set), and **say on the page itself** that it is 37% documented and that these are
  scripts, not an API. Rendering `dspy/` needs the dspy install and is deferred.

### 4.5 Funding — what GitHub gives for free before anything is built
- **`.github/FUNDING.yml`**, verified 2026-09-07 on docs.github.com: must live in the `.github`
  folder **on the default branch**; supported keys are `community_bridge`, `github`, `issuehunt`,
  `ko_fi`, `liberapay`, `open_collective`, `patreon`, `tidelift`, `polar`, `buy_me_a_coffee`,
  `thanks_dev`, `custom`; one username per platform, **up to four `custom` URLs**, one org and up
  to four developers under `github`. The repo has no `FUNDING.yml` today (verified).
- **GitHub Sponsors, personal account**, verified 2026-09-07 on docs.github.com: requires an
  open-source contribution, residence in a supported region, and 2FA; setup is a profile, optional
  tiers (up to 10 monthly + 10 one-time), bank details via Stripe Connect, and a tax form (W-8BEN
  for non-US), then GitHub approval. **Brazil is in the supported-regions list** (verified on the
  About GitHub Sponsors page), and "GitHub Sponsors does not charge any fees for sponsorships from
  personal accounts, so 100% of these sponsorships go to the sponsored developer" (quoted verbatim
  from that page).
- **Pick: GitHub Sponsors + `.github/FUNDING.yml` first**: it is native, free, 0% fee, requires
  zero site code, and produces a Sponsor button without this MIP building anything. buy-me-a-coffee
  and a wallet address are *links on one page*, never an embedded widget (§5.2).

### 4.6 GitHub Pages limits, against a 16 MB docs tree
Verified 2026-09-07 on docs.github.com's Pages-limits page: published sites "may be no larger than
1 GB"; a **soft** bandwidth limit of 100 GB/month; a soft 10-builds-per-hour limit that "does not
apply if you build and publish your site with a custom GitHub Actions workflow": which is exactly
what `site.yml` is. So 16 MB of API docs is under 2% of the size budget and no build-rate
problem. What it *is* is 16 MB re-uploaded on all eight scheduled deploys a day for content that
only changes when Scala changes, which is why §5.6 puts it on the `site-data` branch instead.

## 5. Design

### 5.1 The nav architecture: separate static pages, generated at build time

**Decision: one real HTML file per section under `site/dist/<section>/index.html`, emitted by a
pure Scala function.** Not a client-side router, not a generator toolchain.

New file, next to `SiteBuilder`:

```scala
// cli/src/main/scala/marola/site/SitePages.scala
object SitePages:
  final case class Nav(label: String, href: String)
  final case class Page(path: String, title: String, description: String, bodyHtml: String,
                        feed: Option[String] = None)

  /** Pure: page in, complete HTML document out. Same contract as Board.build — no clock, no I/O. */
  def render(page: Page, nav: List[Nav], generatedAt: OffsetDateTime): String

  /** Every static (non-markdown) section: about, support, feeds. */
  def staticPages(areas: List[SiteBuilder.Area]): List[Page]
```

`SiteBuilder.build` gains one more step beside `copyStatic`, writing each `Page` to
`out/<path>/index.html`. Everything stays a pure string function over data, unit-testable without a
browser: the same shape as `Board.build` (JSON) and MIP-0034 §5.6's `SiteFeed.atom` (XML). MIP-0005
said "no templating engine" and this does not add one: there is no template language, no
`{{ }}`, no partials directory, no lockfile: one Scala function that concatenates strings and is
covered by a spec.

Why pages and not a hash router in `app.js`:

- **Weight.** `/about/` must not download `vendor/leaflet.js` (147,552 bytes, verified on disk) plus
  a 575-line `app.js` to show four paragraphs. One bundle for every section is the cost a router
  charges on every page.
- **Permalinks that resolve without JS.** MIP-0034's feed entries need `<link>` URLs that a feed
  reader and a crawler can fetch. `#/news/2026-09-07` is not that.
- **The `<noscript>` promise.** `index.html` today honestly says "This page needs JavaScript to read
  the board JSON": true, because the board *is* JSON. A blog post is not; rendering it at build
  time means the text is in the HTML and the page works with JS off. A router makes prose need JS.
- **Constraint check.** GitHub Pages serves these as plain files: still no server, still no
  per-visitor cost, still the same `actions/deploy-pages` artifact. `ARCHITECTURE.md`'s stated
  constraint is satisfied by *any* of these options; weight, permalinks and no-JS are what decide.

Machinery this touches, each of which must change in the same PR as the first new page:

| File | Change |
|---|---|
| `.github/workflows/site.yml` | the publish allowlist (line ~166) gains the section directories; the required-files check gains one `index.html` per section |
| `scripts/stamp_site_version.sh` | today it `sed`s exactly `site/dist/index.html`; it must stamp every generated page (or the CDN-skew bug it exists to prevent comes back on the new pages) |
| `scripts/site_check.js` | new assertions, §7 |
| `SiteBuilder.copyStatic` | unchanged; the generated pages are written beside the copy, not through it |
| `site/static/style.css` | one `.nav` block; the existing tokens (`--ink`, `--line`, `--accent`) and the 13/15/17/20/26 type scale are reused, not replaced |

### 5.2 `/about/` (with contact) and `/support/` — the two low-research sections

**`/about/`** is prose assembled from text this repo already owns and can source: what marola is
(`README.md`'s hero / `ARCHITECTURE.md` §1), what it is **not** (not a lifeguard, not an official
forecast, `ARCHITECTURE.md` §8's honesty language), the data sources with links (reuse `app.js`'s
existing `SOURCE_LINKS` map so the site never has two lists that disagree), the licence, and the
privacy statement, which is a verifiable fact here rather than a marketing line: no cookies, no
analytics, geolocation only on a button press, nothing leaves the browser.

**Contact is an anchored section of `/about/`, not a form.** There is no server, and a form needs a
POST endpoint. The only free endpoints are third-party (Formspree, Netlify Forms and friends), each
of which would add a third-party origin to the CSP, break the no-tracking property, and (the point
that actually settles it) introduce a hosted service dependency, which is exactly the class of
decision `AGENTS.md`'s cost-and-deployment rule and `ARCHITECTURE.md` §11's phase gate exist to
stop. So: a `mailto:` link, a GitHub-issues link (once MIP-0033 §5.1 makes the repo public), and,
once Phase 1 lands, the Telegram bot handle. Honest caveat printed on the page: a `mailto:` address
on a public page gets scraped.

**`/support/`**: the request said "be careful for not doing too much promotion", so the design
rule is stated as a rule, not a hope:

- **One page. No banner, no popup, no sticky footer, no ask on any other page.** The nav item is
  the entire promotion.
- **No third-party widget.** buy-me-a-coffee's embed is a third-party script that sets cookies;
  it is a plain `<a href>` here or it is not on the site. This keeps `script-src 'self'` intact.
- **The page's content is the ledger, not a pitch.** marola's hosting is free (Pages + Actions free
  tier, §4.6); the real cost is the agent spend this repo already publishes in every PR's `Cost:`
  trailer. The honest page says what it costs to build and links the evidence: that is more
  interesting than a tip jar and it cannot read as begging.
- **Order:** GitHub Sponsors (native, 0% fee, Brazil supported, §4.5), then buy-me-a-coffee, then
  one crypto address as plain reviewable text.
- `.github/FUNDING.yml` ships **first and separately**: it is one file, it needs none of this
  MIP's machinery, and it gives a Sponsor button on its own.

Crypto caveat, stated on the page and in §8: an address on a static page is a defacement target:
anyone who can push to the repo, or a leaked Actions token, can swap it and it will look correct.
It stays in `site/static/` source, changed only through a reviewed PR, shown as text (no runtime QR
generation, which would mean another vendored library).

### 5.3 `/news/` and `/dev/` — one markdown pipeline, two collections

Content lives in the repo and is reviewed like code:

```
site/content/news/2026-09-06-site-retries-connect-timeouts.md
site/content/posts/2026-09-07-why-kyo-needs-jdk-25.md
```

Same renderer, two index pages: `/news/` is short dated operational notes (what changed, a new
area, an outage); `/dev/` is long-form. New file:

```scala
// cli/src/main/scala/marola/site/Markdown.scala
object Markdown:
  final case class FrontMatter(title: String, date: LocalDate, tags: List[String], safety: Boolean)
  final case class Post(slug: String, front: FrontMatter, html: String)

  /** Pure: markdown text + a file resolver in, post out. Fails loudly on bad front matter. */
  def post(slug: String, text: String, include: (String, Int, Int) => Option[String]): Post
```

**Build-time rendering, not client-side.** The trade-off, honestly: client-side (vendor `marked`
18.0.11, §4.2, ~like Leaflet) is genuinely less code to write: no new JVM dependency, no build
step, posts become `fetch()` + `innerHTML`. It loses on four counts, and one of them is
disqualifying. (i) `innerHTML` of rendered markdown is the exact operation `app.js` has spent 575
lines avoiding: it escapes every interpolated string through `esc()`, and a markdown renderer's
whole job is to emit HTML, so the page's one clean XSS invariant would become "trust the pipeline
and the sanitiser". (ii) Feed items and crawlers need server-rendered permalinks (§5.1). (iii) A
post is prose, and prose that needs JS to appear is a regression from a page that today only needs
JS for a *JSON board*. (iv) It ships another vendored library to keep patched. Build-time rendering
runs commonmark once, in CI, over input that came through a reviewed PR: the trust boundary is a
code review, not a browser. That is the call; client-side is the alternative in §9(c).

**Code snippets.** Fenced blocks are rendered as plain `<pre><code>` and styled with the existing
CSS tokens. **No syntax-highlighting library in v1**: highlight.js 11.11.2 (BSD-3-Clause, verified
§4.2 via cdnjs) is the obvious vendored follow-up, but it is polish, and "the snippet is correct and
copy-pasteable" is the requirement. The `include:` directive (§3) is the part that earns its keep:
it resolves `path L<from>-L<to>` against the repo at build time, so a post cannot quote code that no
longer exists. This is the same discipline as `AGENTS.md`'s "no unsourced facts", applied to code.

**Relationship to MIP-0018.** MIP-0018 keeps its planner and its per-platform formatters. Its §5.3
blog destination, a sibling-cloned external repo of unknown technology, blocked on its own §11
open question, is **superseded** by this section: the exporter writes into `site/content/posts/`,
opens a PR, and CI publishes it. MIP-0018's status is not flipped by this MIP; the pointer is added
to its file so whoever implements it sees the change.

### 5.4 `/feeds/` — the page MIP-0034 does not build

A human-readable page listing every feed MIP-0034 §5.6 emits (`feed.xml` at the root, and
`data/<area>/feed.xml` per area), what each contains, how often it updates (the 3-hourly rebuild),
and how to subscribe. Every generated page gains the matching `<link rel="alternate"
type="application/atom+xml">` in its head. MIP-0034 adds it to `index.html` only. `/feeds/` is a
footer link plus this head link, not a sixth nav item (§11 flags the nav-size call for a human).
**Not buildable before MIP-0034 merges**: hence the `Blocked by` edge.

### 5.5 `/dev/docs/` — the docs ↔ wiki ↔ website question

`site.yml` states the current policy in its own comment: "docs/\*.md are not on the site by design:
they render on GitHub, for people with access." That policy exists because the repo is private.
Publishing `docs/*.md` at marola.dev is the same disclosure as making them public, so it waits for
MIP-0033 §5.1's secret scan, then it is nearly free, because §5.3's renderer already exists: walk
`docs/*.md`, render each with the same `Markdown.post` path, emit `/dev/docs/<name>/`.

**GitHub Wiki is rejected as the docs surface** (§9d): it is a separate git repository, it is not
covered by this repo's CI or PR review, `just quality` cannot gate it, and it cannot host the
generated Scaladoc. One source of truth (`docs/` in this repo) rendered two ways (GitHub for
contributors, marola.dev for readers) is strictly simpler than three surfaces to keep in sync.

### 5.6 `/dev/scala/` and `/dev/python/` — generated API docs, on the `site-data` branch

The mechanic is already in this repo and reused verbatim: `docker-smoke.yml` and `ci.yml` push
`smoke/` and `coverage/` to the orphan `site-data` branch, and `site.yml` copies whatever exists
there into `site/dist` (`git archive FETCH_HEAD "$dir" | tar -x -C site/dist`, guarded by
`git cat-file -e` so a missing directory is "no panel", never a failure). A new
`.github/workflows/api-docs.yml`, triggered on pushes that touch `**/*.scala`, `build.sbt` or
`scripts/**.py`, **not** on the 3-hourly schedule, runs:

```bash
sbt core/doc local/doc cli/doc               # verified working, §4.3
scripts/strip_external_scripts.py <dir>...    # new; see below
pdoc -o out/python scripts/*.py scripts/lib/*.py finetune/build_dataset.py
```

and pushes the result to `site-data` under `api/`. `site.yml`'s copy loop gains `api` beside
`smoke` and `coverage`; its publish allowlist gains the directory. Net effect: 16 MB is uploaded
when Scala changes, not eight times a day.

`scripts/strip_external_scripts.py` (new, with a `--self-test` like every other script here) removes
the four `<script src="https://…">` tags §4.3 found on all 103 pages, and rewrites the one-line
inline `var pathToRoot` into a `data-` attribute read by a tiny same-origin script, so the docs
pages can carry the same `script-src 'self'` CSP as the rest of the site instead of a weaker one.
CI then greps the generated tree for `src="http` and fails on a hit: the property is enforced, not
promised. Losing dagre/d3 loses scaladoc's inheritance diagrams and losing scastie loses "run this
snippet"; neither is used by this codebase's docs, and neither is worth a third-party request from
a site that advertises having none.

`/dev/python/` carries a plain sentence at the top saying what §4.4 measured: nine CLI scripts,
stdlib-only, 37% of functions documented, not a library API. That sentence is the difference
between an honest page and one that implies more than exists.

`flake.nix` gains `pdoc` (a new dev-shell tool; `just quality-other` already fails rather than skips
when a lint tool is missing, and this follows that rule).

## 6. Scoring / safety impact

`Swimability.score` is untouched: **none**.

One new safety rule, because this is the first time the site will carry prose that is not computed
from live data. A post or news item whose front matter sets `safety: true` gets MIP-0022's
lifeguard/193/SAMU 192 footer appended **by the generator, verbatim, after the author's text**:
the same "appended after the model, never by it" discipline MIP-0022 established, applied to a
human author instead of an LLM. And `AGENTS.md`'s "no unsourced facts reach a user" applies to
every page here: a claim about conditions, marine life or safety in a post carries its source
inline, exactly as `knowledge/*.md` corpus documents do.

## 7. Verification plan

Unit tests to add:

- `SitePagesSpec`: `render` emits one `<title>`, one `<meta name="description">`, the nav with
  exactly one `aria-current="page"`; the same `Page` renders byte-identically twice (pure).
- `MarkdownSpec`: front matter parses; a missing `title`/`date` fails loudly rather than
  defaulting; `safety: true` appends MIP-0022's footer verbatim and `safety: false` does not;
  fenced code survives with entities escaped.
- `MarkdownIncludeSpec`: an `include:` against a real repo file returns those exact lines; a
  missing file and an out-of-range span each fail, with the offending directive in the message.
- `SiteBuilderSpec` (extend the existing suite): every section writes `<section>/index.html` into
  `out`, and the existing "the real `site/static` lands in dist" assertion still holds.
- `scripts/site_check.js` (extend), for every generated page: parses, has a title, has the
  `<link rel="alternate">`, and **no `src="http`** anywhere. Runs in `just quality-other` already.
- `scripts/strip_external_scripts.py --self-test`: added to `quality-other`'s list.

Live checks (commands, to run and record):

```bash
just site-build && just site-serve      # then curl each URL in §3 for HTTP 200 and a <title>
sbt core/doc local/doc cli/doc && grep -rl 'src="http' */target/scala-*/api | wc -l  # 0
pdoc -o /tmp/pyapi scripts/*.py scripts/lib/*.py finetune/build_dataset.py
```

Done looks like: every URL in §3 returns 200 with real content and no JS required for the prose;
`site.yml`'s allowlist and required-files checks both pass and both *fail* when a section is
missing; the generated API tree contains zero third-party script tags; `just quality` green.

## 8. Risks, limitations, and honest caveats

- **A half-built site can deploy.** `site.yml`'s required-files check knows only `index.html` and
  one `latest.json` per area. Extending it is part of §5.1's first PR, not a follow-up.
- **The cache-buster is index.html-shaped.** `stamp_site_version.sh` `sed`s one file; the CDN skew
  it was written for (`fix/site-smoke-panel-null`, each Pages file cached independently at
  `max-age=600`) applies to every new page too.
- **16 MB of generated docs is real weight** even at under 2% of the 1 GB limit, and the bandwidth
  limit is a soft 100 GB/month (§4.6), which a crawler walking 400+ doc files repeatedly can move
  toward.
- **Scaladoc's four CDN scripts are a privacy regression if the strip step ever silently fails**:
  hence the grep gate, not just the script.
- **The Python docs page will look thin,** because it is (§4.4). The mitigation is saying so on the
  page rather than dressing it up.
- **A wallet address on a static page cannot be revoked** and has no chargeback. §5.2's mitigation
  is review-only changes and plain text; the residual risk is real and belongs to the maintainer.
- **Content is now a maintenance obligation.** An empty `/news/` that last updated in March is worse
  than no `/news/`: the honest fallback is that `/news/` renders "nothing yet" rather than a stale
  page pretending to be current.

## 9. Alternatives considered

- **(a) Do nothing.** The map stays the whole site. Loses: MIP-0018 stays blocked on its own §11
  question, MIP-0034's feed stays undiscoverable, and there is still no page saying what marola is.
- **(b) A static-site generator (Hugo / Jekyll / Astro / 11ty).** Real tools, and each brings a
  toolchain, a theme, a lockfile and a second build system into a repo whose site is deliberately
  dependency-free, and Jekyll specifically is ruled out by `site.yml`'s own comment, because using
  Pages' "Deploy from a branch" mode would publish the whole repository as HTML, which is what keeps
  this private repo private today.
- **(c) Client-side markdown rendering** (`marked`/`markdown-it`, vendored). Less code; loses on
  `innerHTML`-vs-`esc()`, permalinks, no-JS prose, and one more vendored library, §5.3.
- **(d) GitHub Wiki as the docs surface.** A separate repo, outside this repo's PR review and
  `just quality`, and it cannot host generated Scaladoc, §5.5.
- **(e) A second Pages site (or Read the Docs) for `/dev/`.** Another host, another privacy surface,
  another domain to explain; the `site-data` mechanic already solves the "big artefact, rare
  rebuild" problem inside the site we have.
- **(f) `sbt-unidoc` for one merged Scala API site** instead of four per-module sites. Nicer output,
  but another sbt plugin to keep working against Scala 3.9, and four sites cost nothing today
  because `sbt doc` already works unmodified (§4.3). Revisit if cross-module links are missed.
- **(g) A hosted contact form (Formspree/Netlify Forms).** A third-party origin in the CSP, a
  tracking surface, and a hosted dependency, §5.2.
- **(h) Skip the `/support/` page entirely** and ship only `.github/FUNDING.yml`. Genuinely
  defensible and the least promotional option available; rejected only because the request asked for
  the section. §11 offers it back as a decision.

## 11. Open questions

- **Nav size: the one call worth a human's veto.** The request named seven sections; this MIP ships
  a five-item nav (`map · news · dev · about · support`) with contact as `/about/#contact` and feeds
  in the footer, because the header already carries five controls and `style.css`'s own comment
  budgets "at most four controls". Say the word and each becomes a top-level item, with the
  generator it is a one-line change to the `Nav` list.
- **Does the `/support/` page ship at all?** §9(h) is a real option: `FUNDING.yml` alone gives a
  Sponsor button with zero site code and zero promotional surface. Maintainer's call.
- **Which crypto address, and on which chain?** Not guessed here. Also unverified: whether a
  `FUNDING.yml` Sponsor button renders at all on a **private** repository: GitHub's page does not
  say, and it was not tested.
- **Is `scripts/cost-split.py`'s hyphen a problem for pdoc?** `cost-split.py` is the only
  hyphenated Python file in the repo, and a hyphen is not a valid Python module name. pdoc accepts
  file paths, but this specific case was not tested. If it breaks, the fix is a rename plus updating
  `justfile` and `quality-other`, small, but a prerequisite, not a detail.
- **What goes in `/news/` on day one?** The pipeline is worth nothing without a first post. A
  reasonable seed is the last few merged PRs' own stories (the 2026-09-06 connect-timeout retry, the
  jail `--exec` fix), which is also exactly what MIP-0018's planner produces.
- **Should `/dev/docs/` render every `docs/*.md` or a curated subset?** `FABLE_REVIEW.md` is
  internal-facing; publishing all 17 unfiltered is a decision, not a default.
- **Follow-up MIP:** the site currently has no way to say "this page is stale". Every generated page
  will carry a `generatedAt` stamp, but a *content* freshness policy (when does a news item stop
  being news, what does `/news/` show after six quiet months) is a small design of its own and
  should take the next free MIP number rather than being folded in here.

## Appendix

### Checked live
- `repo1.maven.org/maven2/org/commonmark/commonmark/maven-metadata.xml`, 2026-09-07: latest
  `0.30.0`, `<lastUpdated>20260806023351</lastUpdated>`.
- `repo1.maven.org/maven2/org/commonmark/commonmark/0.30.0/commonmark-0.30.0.jar` (HEAD),
  2026-09-07: HTTP 200, `content-length: 220139`.
- `repo1.maven.org/maven2/org/commonmark/{commonmark-ext-gfm-tables,commonmark-ext-yaml-front-matter,commonmark-ext-heading-anchor}/maven-metadata.xml`,
  2026-09-07: all three `<release>0.30.0</release>`.
- `central.sonatype.com/artifact/org.commonmark/commonmark`, 2026-09-07: licence BSD-2-Clause.
- `repo1.maven.org/maven2/com/vladsch/flexmark/flexmark/maven-metadata.xml`, 2026-09-07: newest
  `0.64.8`, `<lastUpdated>20230523183154</lastUpdated>` (stale; rejected).
- `api.cdnjs.com/libraries/{highlight.js,marked,markdown-it}`, 2026-09-07: `11.11.2` BSD-3-Clause,
  `18.0.11` MIT, `14.1.0` MIT.
- `pdoc.dev/docs/pdoc.html`, 2026-09-07: accepts file paths (`pdoc ./demo.py`), "no configuration
  necessary", and "your Python modules will be executed/imported when pdoc runs".
- `docs.github.com/.../displaying-a-sponsor-button-in-your-repository`, 2026-09-07: `FUNDING.yml`
  in `.github` on the default branch; the twelve supported keys listed in §4.5; one entry per
  platform, up to four `custom` URLs.
- `docs.github.com/.../setting-up-github-sponsors-for-your-personal-account`, 2026-09-07:
  eligibility (OSS contribution, supported region, 2FA), Stripe Connect, W-8BEN for non-US.
- `docs.github.com/.../about-github-sponsors`, 2026-09-07: Brazil is in the supported-regions list;
  "GitHub Sponsors does not charge any fees for sponsorships from personal accounts, so 100% of
  these sponsorships go to the sponsored developer or organization."
- `docs.github.com/.../github-pages-limits`, 2026-09-07: 1 GB published size, soft 100 GB/month
  bandwidth, soft 10 builds/hour that "does not apply if you build and publish your site with a
  custom GitHub Actions workflow".
- **This repo, run locally 2026-09-07** (`nix develop`, JDK 25, sbt 1.10.7): `sbt core/doc` exit 0
  in 3 s with one warning; `local/doc`, `cli/doc` exit 0. Sizes `du -sh`: 7.2 M / 4.4 M
  / 4.4 M = 16 M. `core` API tree: 411 files, 103 HTML pages. `grep -rl` over those pages:
  **103 of 103** reference `cdnjs.cloudflare.com/…/dagre-d3`, `cdn.jsdelivr.net/…/graphlib-dot`,
  `d3js.org/d3.v6.min.js` and `scastie.scala-lang.org/embedded.js`; the single inline script body is
  `var pathToRoot = "";`.
- **This repo, measured 2026-09-07** with `ast`: the nine-file Python table in §4.4 (9/9 module
  docstrings, 9/9 `__main__`-guarded, 36/96 defs documented); no `pyproject.toml`, no `setup.py`, no
  `__init__.py` outside `finetune/.venv-convert`; stdlib-only imports except `dspy`.
- **This repo, read 2026-09-07**: `build.sbt` and `project/plugins.sbt` have no `doc`/unidoc wiring;
  `.github/FUNDING.yml` does not exist; `site.yml`'s publish allowlist and required-files check as
  quoted in §2/§5.1; `site/static/vendor/leaflet.js` is 147,552 bytes; 53 of 54 main `.scala` files
  carry a scaladoc comment; `docs/ROADMAP.md` §7's K1–K10 and `docs/FUTURE-WORK.md` contain no
  site-sections item.
- **MIP-0034 branch check, 2026-09-07**: two branches carry the same file:
  `origin/docs/mip-0034-rss-feeds` and `origin/mips/2026-09-07/1-mip-0034-rss-feeds`. A `git diff`
  between them shows **no difference in `docs/mips/MIP-0034-rss-feeds-and-content-syndication.md`**;
  the newer `mips/2026-09-07/1-…` is simply rebased onto a later `main`. Treat that one as canonical.

### Not checked
- Whether a `FUNDING.yml` Sponsor button renders on a **private** repository: GitHub's doc page
  does not address it and it was not tested.
- Whether `pdoc` is packaged in nixpkgs under that name, and its licence: `flake.nix` would need
  it, and neither was verified this session.
- Whether `pdoc` handles the hyphen in `scripts/cost-split.py` (§11).
- `pdoc` was never actually run: it is not in the current dev shell. §4.4's table is a static `ast`
  measurement of what *would* be documented, not pdoc output.
- Whether GitHub Pages serves `index.html` for a bare directory URL such as `/about/`: assumed from
  common practice, not verified against a live Pages response this session. If it does not, every
  section becomes `<section>.html` instead; nothing else in §5 changes.
- `sbt-unidoc`'s Scala 3.9 support (§9f): not investigated.
- MIP-0034's own §5.6 signature is quoted from its draft branch, not from merged code: it has not
  merged.
