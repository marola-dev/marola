# MIP-0046: The map without the generated look — a chart-derived icon set replacing every emoji

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Opus 5, for M. Hoffmann (request of 2026-09-07: "remove AI slop appearance from current UI, dont use emoji based formatting, use frontend design skill to build something beautiful still on the static site… be very specific and detailed in all needs to be refactored and relation to the existing mips") |
| **Created** | 2026-09-07 |
| **Phase** | 3 — `ARCHITECTURE.md` §11 puts the static map under Phase 3 ("Deploy… the first deploy artefact is already here and free: `site.yml` … publishes the static map"). It is already shipped and running; nothing from Phase 1 or 2 gates a presentation change. Honest caveat: MIP-0009 and MIP-0037 both label this same surface "Phase 1" — a pre-existing inconsistency in the docs, not resolved here |
| **Related** | MIP-0005 (the page and its board JSON), MIP-0009 (the aspect row this rewrites — its §5 emoji map is the thing being replaced), MIP-0021 (the facilities data whose labels carry 🅿️🚻🚿🛟), MIP-0037 (its `icon-192.png` is derived from `index.html`'s `<h1>` wave SVG — same asset), MIP-0042 (freezes today's client at `/v1/` and reuses it as v2's starting point), `.claude/skills/site-frontend/SKILL.md` (the red-flags list this MIP is an application of), `docs/AGENT-SKILLS.md` §1. **See also [MIP-0047](./MIP-0047-desmos-equation-art.md)** (Draft) — equation-drawn illustration motifs sharing this MIP's inline-SVG `<symbol>`/`<use href>` sprite mechanism, not a second delivery pipeline |
| **Effort** | M — three site files plus the test harness: ~10 hand-drawn SVG symbols, eight call sites in `app.js`, a sprite block and the legend key in `index.html`, ~25 lines of CSS, and every emoji assertion in `scripts/site_check.js` moving in lockstep. Not S: `just quality-other` runs the harness, so the assertions are a gate, and "done" needs a browser at 390 px and 1280 px (`site-frontend` step 6), which the stub DOM cannot substitute for |
| **Gain** | `user value` — the page stops reading as machine-assembled at the exact moment a first-time visitor decides whether to trust its numbers; `infra/dev-loop` — one icon definition replaces two divergent wave glyphs and removes MIP-0009's Emoji-14 font-coverage caveat entirely |
| **Effort vs Gain** | `do next` — cheap relative to any data work, self-contained in `site/static/` + one harness file, and it wants to land **before** MIP-0042 §5.1 freezes today's client at `/v1/`, or the frozen copy ships with the emoji in it |
| **Depends on** | MIP-0005 and MIP-0009 are merged and are what this edits — nothing to wait for. Coordination, not blocking: MIP-0037 (Draft) derives its PWA icon from the same `<h1>` SVG this MIP leaves alone deliberately; MIP-0042 (Draft) copies `site/static/` into `/v1/` and `/v2/`, so landing after it means doing this twice. No Phase-1 gate, no Azure resource, no cost |
| **Blocked by** | none |
| **Risk** | Hand-drawn 14 px marks read as mush where a system emoji read instantly — the stub-DOM harness cannot see this, only a screenshot can, so a rushed PR could trade "generated-looking" for "illegible" and call it done |
| **Cost so far** | — |

## 1. Summary

Every emoji on the map is replaced by a small monochrome mark drawn from **nautical- and
weather-chart vernacular** — a barb-derived wind shaft, the site's own wave, a sounding bulb, a
whale and a jellyfish silhouette, four plain facility pictograms — defined once as SVG `<symbol>`s
inside `index.html` and referenced by `<use href="#…">`. No new file, no new network request, no
font download, no build step, no data-colour change. The pattern is not new here: on 2026-09-07 the
water verdict already swapped 💧 for a `.wdot` colour dot reusing the score tokens (commit `131fdb9`,
PR #233); this MIP generalizes that one decision across the whole page.

## 2. Motivation

The visual chrome is already disciplined — the 2026-09-05 typography pass (commit `00c330c`, the
first application of the `site-frontend` skill) removed the classic generated-page tells, and a grep
of today's `site/static/` confirms **none of them came back**: no gradient, no `backdrop-filter`, no
Tailwind palette hex, no `@keyframes`, no uppercase eyebrow labels (checked 2026-09-07, appendix).

What is left is the emoji layer, and it is the layer a visitor reads first. `app.js` renders
🌊🌬️🍃💨🌡️〰️🪼🐋🅿️🚻🚿🛟 across the tooltip, the card, the facilities line, the footer lore and one
header button. Three specific problems, all visible in the source:

- **The page draws two different waves.** `WAVE_PATH` (`app.js:172`) is a real, deliberately tuned
  wave — one filled path, the score colour, matched by the legend key in `index.html:38` and
  asserted identical by `site_check.js:214-216`. Two lines below it, the *waves* aspect is `〰️`
  (U+3030 with VS16), an unrelated wavy dash. One page, one concept, two glyphs.
- **MIP-0009 shipped a workaround it documented as a workaround.** Its §5 fixes the emoji map and
  its §8 states: "Emoji fonts differ per platform and 🪼 is missing on older ones — hence the
  words." Every aspect is `emoji + word` because the emoji cannot be relied on. A `<symbol>` renders
  identically everywhere and carries no Emoji-14 dependency.
- **`.head` opens with an emoji.** `aspectsHtml` builds `<div class="head">🌊 <beach name></div>`
  (`app.js:210`) — the tooltip's heading, and the `site-frontend` red-flags list names "an emoji …
  in the `h1`" explicitly.

Two adjacent findings, both measured, neither strictly emoji (§5.6, §8): white text on the score
chip fails WCAG AA in four of five score bands, and the whole score legend is `aria-hidden="true"`.

## 3. User-visible change

The hovered/tapped aspect row, before (today, verbatim from `site_check.js`'s fixture assertions):

```
🌊 Praia da Joaquina · 55/100 at 10:00
🌬️ breezy, 27 km/h S          🌡️ water 19.0 °C
〰️ waves 1.3 m every 6 s      🪼 jellyfish Low
🐋 whales Low (best 07:00)    ● 1/1 PRÓPRIA (25 Aug)
🅿️ parking 3 · 🚻 toilets 1
```

After — same words, same numbers, same colour dot; the marks are chart glyphs in `currentColor`,
and the numbers align because the grid gains `tabular-nums` (it does not have it today):

```
Praia da Joaquina · 55/100 at 10:00
⌐‾  breezy, 27 km/h S         (bulb)  water   19.0 °C
(wave) waves 1.3 m every 6 s  (jelly) jellyfish  Low
(whale) whales Low (07:00)    ●  1/1 PRÓPRIA (25 Aug)
(P) parking 3   (WC) toilets 1
```

Header button: `🌊 sound` → a small ripple mark plus the word `sound`. Footer lore:
`🌊 Did you know? …` → `Ocean note — …`; `🐋 Sea life: …` → `Sea life — …`.

## 4. Data sources and dependencies reviewed

No data source changes. What was reviewed is the *delivery mechanism* for the icons, against this
repo's real constraints (no `package.json` anywhere in the tree; `site.yml` runs sbt for the boards,
`scripts/stamp_site_version.sh` for cache-busting, and nothing else — no bundler exists to add to).

### 4.1 Inline `<symbol>` + `<use href="#id">` in `index.html` — **the pick**

MDN (fetched 2026-09-07): `href` on `<use>` is the SVG2 attribute, `xlink:href` is deprecated in its
favour, the feature is "Baseline: Widely available… since July 2015", and a same-document fragment
(`href="#myCircle"`) is the documented example. Costs one hidden `<svg>` block in `index.html`
(~1 KB), zero requests, and passes `site.yml`'s publish allowlist untouched because it adds no file.
Works under the page's own CSP (`default-src 'self'`; inline SVG is markup, not script).

### 4.2 A separate `site/static/icons.svg` sprite — rejected

Adds one network request, and `site.yml`'s allowlist step (`… ! -name index.html ! -name app.js …
-print` then `exit 1`) fails the build on any unlisted file, so it needs a workflow edit for a
cosmetic gain. Cross-file `<use>` also needs a same-origin fetch, which breaks a `file://` open of
`site/dist` — a real local-dev path here.

### 4.3 An icon font (or any CDN font) — rejected

`style.css`'s own header says "no font download"; `index.html` promises no third-party requests and
its CSP has no `font-src` opening. An icon font is also a glyph-coverage bet, which is the exact
failure MIP-0009 §8 documents for 🪼.

### 4.4 A typographic / Unicode-symbol system — rejected

Still font-dependent, still platform-variable, and it cannot encode a reading the way a barb can.

### 4.5 The wind-barb convention (the one borrowed idea)

NWS (weather.gov/hfo/windbarbinfo, fetched 2026-09-07): a long barb is 10 knots, a short barb 5, a
pennant 50, and calm (0-2 kt) is drawn as a circle. marola has **km/h and a three-band level**, not
knots, so the proposal is explicitly *barb-derived, not a station-model barb*: one shaft with zero
barbs plus a small ring (calm), one barb (breezy), two barbs (strong). The word stays beside it, so
nothing depends on the reader knowing the convention — the shape only has to make three states
distinguishable at 14 px.

## 5. Design

**Design plan** (the `frontend-design` two-pass, compressed). *Subject*: a hydrographic reading of
one stretch of coast, for someone on a phone deciding within the hour whether to swim. *Colour*: no
new tokens — the five score colours are data and untouched, chrome stays `--ink / --muted / --bg /
--panel / --line / --accent`. *Type*: unchanged system stack and 13/15/17/20/26 scale; the one
addition is `tabular-nums` on the aspect grid. *Layout*: unchanged panels; the aspect grid gains a
fixed icon column so the marks form a vertical rail and the readings align. *Principle*: one mark
per fact, never a mark the word does not already say; colour means score and water, nothing else.
Reviewed against the skill's calibration list before committing to it — this is not cream+serif,
not near-black+acid, not a SaaS-card kit, and the one structural device (barb count) encodes a real
reading rather than decorating one.

### 5.1 The sprite — `site/static/index.html`

One `<svg class="sprite" aria-hidden="true">` immediately after `<body>`, holding `<symbol
id="i-*" viewBox="0 0 24 24">` for: `i-wind-0` `i-wind-1` `i-wind-2` `i-temp` `i-wave` `i-jelly`
`i-whale` `i-parking` `i-toilets` `i-shower` `i-lifeguard` `i-sound`. Strokes only,
`stroke="currentColor"`, no `fill` attribute (so a `<use>` can set one), `stroke-width="2"`,
`stroke-linecap="round"` — the same drawing style as the wordmark SVG already in `<h1>`.
`i-wave` **reuses `WAVE_PATH` verbatim** so the marker, the legend key and the waves aspect are
finally the same shape.

### 5.2 The call sites — `site/static/app.js`

One helper: `function icon(id) { return '<svg class="ic" aria-hidden="true"><use href="#' + id +
'"/></svg>'; }`. Then, exactly:

| # | Line / function | Today | Becomes |
|---|---|---|---|
| 1 | `161` `WIND_EMOJI` | `{calm:'🍃',breezy:'🌬️',strong:'💨'}` | `WIND_ICON = {calm:'i-wind-0',breezy:'i-wind-1',strong:'i-wind-2'}` |
| 2 | `210` `aspectsHtml` head | `'<div class="head">🌊 ' + name` | drop the emoji — the name alone (it is the heading, and the wave being hovered *is* the marker) |
| 3 | `214` wind cell | `WIND_EMOJI[level] + ' ' + level` / `'🌬️ wind '` | `icon(WIND_ICON[level]) + ' ' + level` / `icon('i-wind-1') + ' wind '` (band absent on an older board: mid mark, word "wind" — the fallback MIP-0009 task 1 established) |
| 4 | `216` waves | `'〰️ waves '` | `icon('i-wave') + ' waves '` |
| 5 | `217` whales | `'🐋 whales '` | `icon('i-whale') + ' whales '` |
| 6 | `228` water temp | `'🌡️ water '` | `icon('i-temp') + ' water '` |
| 7 | `230` jellyfish | `'🪼 jellyfish '` | `icon('i-jelly') + ' jellyfish '` |
| 8 | `221` water verdict | `<i class="wdot …">` | **unchanged** — the precedent this MIP generalizes |
| 9 | `237` `FACILITY_LABEL` + `240` `facilitiesHtml` | `'🅿️ parking'`… joined with `' · '` | `{parking:['i-parking','parking'], toilets:['i-toilets','toilets'], shower:['i-shower','shower'], lifeguard:['i-lifeguard','lifeguard']}`; emit `icon(id) + ' ' + word + ' ' + count` and **drop the `·` joins** for a flex gap — the marks are the separators, and `A · B · C` meta strings are themselves on the generated-look list |
| 10 | `402` `renderFooter` lore | `'🐋 Sea life: '` / `'🌊 Did you know? '` | `'Sea life — '` / `'Ocean note — '`; "Did you know?" is the generated-content voice and goes with the emoji |

`waveIcon()` (`173`) and `renderWaterPoints()` (`333`) are **not touched**: the marker's inline
filled path is tuned for legibility at area zoom and already emoji-free, and `<use>` inside 80
Leaflet `divIcon`s is an untested change with nothing to gain (§9).

### 5.3 `site/static/index.html`, outside the sprite

- `28` — `🌊 sound` → `icon('i-sound')` markup + `sound`.
- `38` — the legend's wave key stops inlining a duplicate `<path d="…">` and becomes
  `<use href="#i-wave">`, so one definition feeds marker, key and aspect.

### 5.4 `site/static/style.css` (~25 lines, budget below)

`.sprite { display: none; }`; `.ic { width: 14px; height: 14px; vertical-align: -2px; fill: none;
stroke: currentColor; flex: none; }`; add `.aspects .grid` to the existing `font-variant-numeric:
tabular-nums` rule (line 30); give `.aspects .grid span` a `display: flex; gap: .35em; align-items:
baseline` so the mark column is a rail rather than an inline blob; `.facilities { display: flex;
flex-wrap: wrap; gap: .15rem .8rem; }`. No new colour, no new radius, no new shadow.

### 5.5 `scripts/site_check.js` — the gate moves with the code

`just quality-other` runs this file, so these assertions are not optional documentation:

- `185-196`: the six emoji needles become `use href="#i-…"` needles plus the unchanged word/number;
  the `<span>`-count and `wide`-count assertions stay exactly as they are.
- `194`: `'🅿️ parking 3 · 🚻 toilets 1'` → the icon+word+count form **without** ` · `.
- `213-216`: the legend-key check compares the two `<use>` targets instead of two `d` strings — it
  must still fail if the key and the marker ever diverge, which is the point of that assertion.
- `269-270`: the no-`wind_level` fallback needle `'🌬️ wind 27 km/h'` → the icon form.
- `209-212` (no `title`, `keyboard: true`, `aria-label` on the marker) — **unchanged, and must stay
  passing**: this MIP adds no `title`, and every `<svg class="ic">` carries `aria-hidden="true"`
  with the word beside it, so nothing a screen reader reads today changes.

### 5.6 Two measured fixes that are not emoji

- **Score-chip contrast.** Computed 2026-09-07 (WCAG 2.x formula, appendix): white on `--c70`
  3.39:1, `--c40` 2.06:1, `--c1` 3.76:1, `--cna` 3.36:1, `--c0` 5.19:1 — four of five bands below
  4.5:1 for the 13 px bold chip. Commit `00c330c` measured 2.06:1 and deferred it to MIP-0009,
  which did not take it. **This MIP does not re-hue a token** — that would change what a score
  colour means and needs its own decision. The in-scope option is to stop using the band colour as
  a *text background*: render the score as `--ink` on `--panel` with the band colour as a 4 px rule
  beside it. Proposed, not decided — §11.
- **`index.html:36`** puts `aria-hidden="true"` on the entire `.legend`, so the score legend does
  not exist for a screen reader. The colour swatches should keep it; the words `≥70 / 40-69 / …`
  should not.

### 5.7 Out of `site/static/`, but named in the request

`README.md:1` is `<h1 align="center">🌊 marola</h1>`. One line, same change, same PR.

## 6. Scoring / safety impact

None. No threshold, no note text, no ranking, no board field, no `Swimability` call. Every number
and word rendered after this change is the same number and word rendered before it; only the glyph
beside it differs. The `.wdot` water signal and the five score colours are untouched.

## 7. Verification plan

- `node --check site/static/app.js` and `node scripts/site_check.js` (both already in
  `just quality-other`) — green, with the assertions of §5.5 rewritten, **not deleted**.
- The three accessibility assertions at `site_check.js:209-212` unchanged and passing.
- One new assertion: every `<use href="#i-…">` emitted by `app.js` resolves to a `<symbol id>`
  present in `index.html` — a broken reference renders nothing at all and is otherwise invisible.
- One new assertion: no character in `U+1F300–U+1FAFF` / `U+2600–U+27BF` / `U+FE0F` appears in
  `site/static/*.{html,js}` — the regression gate for this whole MIP.
- `just quality` (scalafmt/scalafix are untouched; `quality-other` is the real gate).
- `just site-build floripa && just site-serve`, then **screenshots at 390 px and 1280 px** — the
  `site-frontend` step-6 rule, and the only check that catches the §-8 legibility risk.
- Budget: `style.css` is 11,399 bytes today against the skill's 15 KB ceiling; the sprite lands in
  `index.html`, not the CSS. Zero new network requests, zero new files in `site/dist`.
- Done = the harness green, the two screenshots in the PR, and a Ctrl-F for any emoji in
  `site/static/` returning nothing.

## 8. Risks, limitations, and honest caveats

- **Legibility.** A system emoji is a full-colour, hinted, professionally drawn glyph. Twelve
  hand-drawn 14 px strokes can be worse, especially the three wind states, which differ by one
  barb. If the 390 px screenshot cannot tell calm from breezy, the barb idea fails and the fallback
  is band-as-word-only (no wind mark at all) — that is an acceptable outcome, not a failure to hide.
- **Nothing here is rendered yet.** Every claim about how this looks is *written, not run*; the
  sprite mechanism is verified against MDN, the drawing is not verified against a browser.
- **`<use>` and Leaflet.** The aspect row lives inside a Leaflet tooltip, which is in the same
  document, so a same-document fragment resolves — but this is reasoned from the DOM, not observed.
  It is the reason §5.2 deliberately leaves the 80 markers on inline paths.
- **Three drafts touch these same files this session** (MIP-0037's `index.html` shell,
  MIP-0042's `/v1/` copy, MIP-0030's trail layer). Whoever lands second rebases; the sprite block is
  additive, which keeps the conflict mechanical.
- **The harness's colour stub is already stale.** `site_check.js:140` asserts against
  `{'--c70':'#2a9d4b','--c40':'#e0a800',…}` while `style.css:5-9` defines `#1b9e77`, `#e6ab02`, … —
  the marker-colour assertions at `197`/`199` therefore test the stub, not the page. Found while
  reading the file, out of this MIP's own scope to fix properly, but noted because this MIP is
  editing the lines next to it (§11).

## 9. Alternatives considered

- **Do nothing.** The chrome is already clean and the emoji "work". Rejected: the user asked, and
  MIP-0009 §8 already recorded the font-coverage problem the emoji carry.
- **Emoji everywhere, but only "safe" ones (drop 🪼).** Keeps the platform-variance problem and the
  generated look; solves neither.
- **Sprite the markers too.** 80 `<use>` per render, an untested interaction with Leaflet's
  `divIcon` HTML, and it would rewrite four tuned marker assertions to gain nothing a reader sees.
- **A CSS-only icon set** (borders/masks). No extra request either, but unmaintainable for a whale,
  and `mask-image` is a bigger compatibility bet than a 2015-baseline `<use>`.
- **Redesign the page.** Out of scope and against the skill's own advice: the layout, type scale and
  tokens are the product of a deliberate pass (`00c330c`) that still holds up in a 2026-09-07 grep.

## 10. Exam-coverage mapping

None materially. It touches the same AI-103 §1 "Responsible AI: transparency" row MIP-0009 claims —
honest presentation of uncertain values, "no data" never shown as "none" — but this is a
presentation change and claiming a new row for it would be padding.

## 11. Open questions

- **The score chip.** Is §5.6's "band colour as a rule, not a text background" acceptable, or does
  the coloured chip carry meaning worth its 2.06:1? A human call — it borders the "the data colours
  are data" rule, so it does not get made inside an implementation PR.
- **Ordering against MIP-0042.** If the `/v1/` freeze lands first, this work is done twice or not at
  all. Recommend: this MIP first, it is smaller.
- **The wordmark.** `index.html`'s `<h1>` SVG is deliberately left alone. MIP-0037 §5 plans to render
  it to `icon-192.png`; if it is ever redrawn, those two must move together.
- **Follow-up (no MIP needed):** `site_check.js:140`'s stub palette has drifted from `style.css`
  (§8). A one-line fix, but it belongs to whoever next touches that file — it is not a design
  decision and does not need its own number.

## Appendix

### Checked live
- `https://developer.mozilla.org/en-US/docs/Web/SVG/Reference/Element/use` (2026-09-07) — `href` is
  the SVG2 attribute, `xlink:href` deprecated in its favour; "Baseline: Widely available… since July
  2015"; same-document `href="#myCircle"` is the documented example. Basis for §4.1.
- `https://www.weather.gov/hfo/windbarbinfo` (2026-09-07) — long barb 10 kt, short barb 5 kt,
  pennant 50 kt; calm (0-2 kt) drawn as a circle. Basis for §4.5, and for the statement that
  marola's three-band mark is *derived from*, not compliant with, the convention.

### Checked in this repo (2026-09-07, `origin/main` at `07882ae`)
- `grep -nP '[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{FE0F}]' site/static/*` → 13 hits: `index.html:28`
  and `app.js:161,206,210,214,216,217,220,228,230,237,402` (two are comments). The §5.2 table is that
  grep, line by line.
- `grep -niE 'gradient|backdrop-filter|14b8a6|2dd4bf|0ea5e9|06213a|@keyframes|text-transform: *uppercase'
  site/static/style.css site/static/index.html` → no true hit (only `cursor: pointer` matching
  `inter` case-insensitively). The `00c330c` cleanup held; §2's claim rests on this.
- `find . -name package.json -not -path './.git/*'` → none. `.github/workflows/site.yml` runs sbt,
  `scripts/stamp_site_version.sh`, a required-files check and a publish allowlist (`site.yml:162-170`,
  which `exit 1`s on any file outside `index.html app.js style.css chat.js chatbot-config.js CNAME`
  + `vendor/ data/ smoke/ coverage/`). Basis for §4.1/§4.2 and for "no bundler exists".
- `just quality-other` includes `node scripts/site_check.js` (`justfile:96`). Basis for §5.5.
- Contrast ratios in §5.6 computed locally with the WCAG 2.x relative-luminance formula over the
  literal token values in `style.css:5-9` against `#ffffff`. Not fetched from anywhere; reproducible
  from the hexes. The `--c40` result (2.06:1) matches commit `00c330c`'s own recorded figure.
- `git log --oneline -- site/static/app.js site/static/style.css` → `131fdb9` (PR #233) is the
  water-dot/`waterDotClass` precedent; `00c330c` (PR #37) is the typography pass.

### Not checked
- No browser was opened. Every rendering claim (legibility at 14 px, `<use>` inside a Leaflet
  tooltip, how the barb reads at 390 px) is written, not run — §7 exists to close exactly that gap.
- MIP-0042's status is read from the unmerged branch `origin/docs/mip-0042-map-v2-live-not-static`;
  if that draft changes before it merges, §11's ordering advice may need revisiting.
- The claim that no MIP has ever covered the site's overall look is from a grep of `docs/mips/`
  for `redesign|first impression|looks AI|visual design|site-frontend` — the history lives in
  `docs/AGENT-SKILLS.md` §1 and the `site-frontend` skill, not in a numbered proposal.
