# MIP-0069: A UI kit for marola.dev — shared tokens and components in plain CSS, before the next pages

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5.5), for Pablo Ribeiro Alves (request of 2026-09-29: "será que entra nessa MIP a gente criar uma lib UI para próximas páginas?") |
| **Created** | 2026-09-29 |
| **Phase** | 3 — the static site `site.yml` already publishes; no Phase 1 or Phase 2 prerequisite, no cloud spend, no dependency |
| **Related** | MIP-0005 (the site and its no-framework constraint), MIP-0044 (the pages this kit is for, and §5.7's menu), MIP-0046 (the icon sprite and the score chip), MIP-0054 (the catalog in `ui.js`), `.claude/skills/site-frontend/SKILL.md` (the budget and red flags this amends) |
| **Tasks** | [`MIP-0069.tasks.md`](./MIP-0069.tasks.md) — four stacked PRs; task 1 is built locally on the map-UX stack |
| **Effort** | M — one CSS file split in two, one sample page, harness checks and a skill edit; no Scala, no framework, no build step |
| **Gain** | `infra/dev-loop` — the next page starts from named components instead of copying rules out of the map's stylesheet; `user value` — every page looks and behaves like the map, in both languages |
| **Effort vs Gain** | `do next` — before MIP-0044 task 2 generates its first page, which would otherwise copy the map's CSS or grow it past its budget |
| **Depends on** | Builds on the map-UX stack (the map-behaviour story, MIP-0044 task 1, MIP-0046 tasks 1–2, MIP-0054 tasks 1–2), which is where the components it names were made; task 1 is on top of that stack. MIP-0044 task 2's page generator is the kit's first consumer and should emit its markup. Not a blocking edge in either direction |
| **Blocked by** | none |
| **Risk** | A second stylesheet is one more request on every page and one more file to keep in step with the first; if the split is not enforced by the harness, rules drift back into whichever file is open |
| **Cost so far** | — |

## 1. Summary

marola.dev has one stylesheet, `site/static/style.css`, and it is the map's: tokens, buttons, the
menu, the chip and the Leaflet overrides share one file capped at 15,000 bytes by the
`site-frontend` skill. It is at 14,677 bytes (LF). The next pages MIP-0044 plans would either copy
its rules or grow it past the cap. This MIP names the components that already exist, moves the
shared ones into `site/static/ui.css` beside the shared `ui.js`, keeps the map's own rules in
`style.css`, and adds a sample page the harness holds the kit to. Still plain CSS and ES5, no
framework, no build step, no third-party request.

## 2. Motivation

- **The budget is spent.** After the map-UX stack, `style.css` has 323 bytes left under the cap
  (14,677 of 15,000 as git stores it; 14,918 in a Windows checkout, whose CRLF adds a byte a line,
  which is the size the harness measures there). The menu-polish commit found its room by merging
  duplicate rules and shortening comments; that does not scale to a news page.
- **Half a kit exists with no name.** Colour and type tokens in `:root`; `ui.js` shared by both
  pages (menu, language, sheets); `.segmented`, `.notice`, `.sr`, `.ic`, the score chip, the menu.
  Nothing says which of these a new page may use, and `about.html` already carries its own
  `.about-body` rules inside the map's file.
- **Measured split** of today's `style.css`, top-level rules by selector: about 4,700 bytes are
  page-agnostic (tokens, base, buttons, segmented, notice, menu, icons, footer, prose), about 6,400
  are the map's (list, card, hour bar, legend, tooltip, markers, chat), about 2,100 are
  `@media` blocks mixing both, and about 1,500 are comments and blank lines.

## 3. User-visible change

None on its own: the map and the about page look the same before and after tasks 2–4. Task 1
(the menu polish) is the visible part and is already built: menu items read as tabs with a hover
background, the current page carries an accent rule, "soon" items read as inert, and a "map" item
marks where the visitor is.

A contributor sees `marola.dev/kit.html` (not linked from the menu, `noindex`): every component in
both languages, the tokens as swatches, and a line saying which file each lives in.

## 4. Data sources and dependencies reviewed

None: no data source, library or service. The constraints reviewed are the repo's own:

- `index.html`'s CSP is `default-src 'self'`: a same-origin stylesheet is allowed, a CDN one is not.
- `.github/workflows/site.yml` publishes only allowlisted file names and requires a named set; a
  new file goes into both lists in the PR that adds it.
- `scripts/stamp_site_version.sh` (after the map-UX story) stamps every same-directory `.js` and
  `.css` tag on `index.html` and `about.html`, so `ui.css` is cache-busted with no script change;
  `kit.html` needs adding to its page list.
- MIP-0046 §4.2 rejected a separate `icons.svg` because cross-file `<use>` needs a fetch; the sprite
  stays inline, and the generator emits it per page (§5.4).

## 5. Design

### 5.1 Two stylesheets, one rule each

- **`ui.css`**: tokens (`:root`: colours, `--shadow`, and new `--radius`, `--space` only if a second
  page needs them), base (`body`, links, focus, `[hidden]`, the lowercase house style and its
  exemptions), and components: button, `.segmented`, `.notice`, `.sr`, `.ic`, the menu
  (`.sitenav`, `.menu*`), the header (`.bar`, `.brand`, `.tagline`, `.controls`), the footer, the
  score chip, and `.prose` (today's `.about-body`, renamed so a news post can use it).
- **`style.css`**: the map only — hour bar, legend, list, card, tooltip, markers, water dots, chat,
  and the Leaflet overrides.
- **The rule**: a selector lives in exactly one file. A component a second page needs moves to
  `ui.css` in the PR that needs it; nothing is written twice.
- **Order**: every page links `ui.css`, then its own stylesheet if it has one. `about.html` loads
  only `ui.css`, so it stops downloading the map's rules.

### 5.2 Budgets, per file

The skill's single 15 KB cap becomes two: `ui.css` under 8,000 bytes and `style.css` under
10,000, measured on LF content so Windows and CI agree. The total a map visitor downloads rises by
a few hundred bytes of duplication-free headers, and one request: the same trade MIP-0044 §5.7
made for `ui.js`, stated there and here.

### 5.3 The kit page

`site/static/kit.html` loads `ui.css` and `ui.js` only and shows each component once, with the
language toggle working. It is published (allowlisted, required) and carries
`<meta name="robots" content="noindex">`. It is the documentation, so it is the thing the harness
checks.

### 5.4 How the generated pages use it

MIP-0044 task 2's `SitePages.render` emits `<link rel="stylesheet" href="ui.css">`, the menu
markup, the icon sprite when the page uses icons, and `ui.js`. A page-specific stylesheet is the
exception and needs its own line in the allowlist.

### 5.5 Naming

Existing class names stay (`.segmented`, `.notice`, `.menu`); renaming them buys nothing and
breaks the harness. New components get a plain noun (`.prose`, `.tabs`), no prefix scheme.

## 6. Scoring / safety impact

None. No number, note, colour meaning or safety text changes; the score colours stay data and stay
in `:root`.

## 7. Verification plan

- `scripts/site_check.js`:
  - no selector appears in both `ui.css` and `style.css`;
  - each file is under its budget, measured with CR stripped;
  - `index.html`, `about.html` and `kit.html` link `ui.css` before any other stylesheet, and
    `about.html` and `kit.html` link nothing else;
  - every class selector in `ui.css` is used in `kit.html`, so the kit cannot rot, and `kit.html`
    loads no script but `ui.js`.
- `scripts/stamp_site_version.sh --self-test` covers `kit.html` and `ui.css`.
- `site.yml` publishes and requires `ui.css` and `kit.html`; `actionlint` green.
- Screenshots of the map, about and kit pages at 390 and 1280 px, before and after the split: the
  map and about pages must be pixel-identical except where task 1 changed them.

## 8. Risks, limitations, and honest caveats

- **One more request per page.** Same-origin, cached, cache-busted; still a request the skill's
  "zero new requests" rule counted. This MIP amends that rule rather than excepting itself.
- **The split can drift.** The one-file-per-selector check catches duplication, not a map-only
  rule parked in `ui.css`; review still has to ask "does a second page use this?".
- **The kit page is public.** Harmless (it shows components), but it is one more URL to keep
  working; `noindex` keeps it out of search.
- **Task 1 is ahead of its MIP.** It was built on the local map-UX stack before this was written,
  at the user's request; its PR waits for this MIP's acceptance like the rest.

## 9. Alternatives considered

- **A CSS framework or utility library** (Tailwind, Bootstrap, Pico). Rejected by the site's own
  rules: no framework, no build step, no CDN under the CSP, a 15 KB budget; and a framework's
  defaults are what the `site-frontend` skill's red-flag list was written against.
- **Raise the cap and keep one file.** Cheapest today, and every future page pays for the map's
  rules on first load.
- **`@import` from `style.css`.** A serial request instead of a parallel one; worse than two
  `<link>`s.
- **Inline the shared CSS into each generated page.** No request, but the same bytes shipped per
  page with no caching, and the generator becomes the only place the kit exists.

## 11. Open questions

- **Budgets.** 8,000 and 10,000 bytes are proposed from §2's measurement; the maintainer may prefer
  a tighter total.
- **Is `kit.html` published or dev-only?** Proposed published and unlinked; an unpublished page
  would need `site.yml` to exclude it from the copy instead.
- **Dark mode.** Tokens make it a `:root` override later; not in scope, and `color-scheme` stays
  `light` until then.

## Appendix

### Checked in this repo (2026-09-29)
- `site/static/style.css` on the map-UX stack: 14,677 bytes as git stores it, 14,918 in the Windows
  checkout. The §2 split comes from grouping its top-level rules by selector.
- `index.html`'s CSP, `site.yml`'s required-files and allowlist steps, and the map-UX story's
  `stamp_site_version.sh` as quoted in §4.
- The site-frontend skill's step 5: "Budget: `style.css` < 15 KB, zero new network requests,
  no framework, no build step."

### Not checked
- Pixel-identity across the split is a plan (§7), not a result: nothing is split yet.
- Whether GitHub Pages serves `kit.html` with any header that changes `noindex` handling.
