---
name: site-frontend
description: Use when changing anything a visitor sees on marola's static map — site/static/index.html, app.js, style.css, the markers, panels, copy or legend — or when the page is called plain, unfinished, generic or "looks AI-generated"
---

# site-frontend — the map as a product people come back to

## Overview

The map is a data product: the score colours, the numbers and the sources *are* the design.
The visual layer exists to make them legible in five seconds on a phone, load instantly, and look
made by a person who swims here. One type scale, one accent, the data colours untouched, and every
change looked at before it is committed.

## When to use

- Any edit under `site/static/` (layout, typography, colour, markers, tooltips, copy, legend).
- A request like "make it modern / appealing / adoptable / a better first impression".
- Not for the board JSON or `SiteBuilder` (that is MIP-0005's Scala side).

## The recipe — do these in order

1. **Look first.** Build and open it: `just site-build floripa && just site-serve` (needs
   network; or copy `site/dist/` from another checkout), then `node
   .claude/skills/site-frontend/site_check.js site/dist`: the stub-DOM harness runs `app.js`
   and prints what rendered. Screenshot 390 px and 1280 px. Write down at most five concrete
   problems a first-time visitor has (what is this? where am I? which beach? why?).
2. **Type.** The system stack that is already there (`system-ui, -apple-system, "Segoe UI",
   Roboto`), no font download: the page promises no third-party requests. One scale:
   13 / 15 / 17 / 20 / 26 px, line-height 1.45, weights 400 and 600 only, `font-variant-numeric:
   tabular-nums` on scores, hours and distances, prose ≤ 65ch.
3. **Colour.** The five score tokens (`--c70 … --cna`) are data and stay. Chrome is the existing
   tokens: `--ink`, `--muted`, `--bg`, `--panel`, `--line`, one `--accent` (#0b6e99, also the
   "you" marker in `app.js`). Flat surfaces; one shadow per floating panel; contrast ≥ 4.5:1.
   A new colour needs a job no token does, and it goes in `:root` next to the others.
4. **Layout and copy.** Mobile first: the map fills the viewport, list and card are bottom
   sheets under 640 px, at most four controls in the header. Hierarchy by size and weight, not
   colour. The one-line description says what the page does in concrete nouns (sea, wind,
   tide, water quality, hour) in ≤ 12 words; no adjectives about the product itself.
5. **Adoption.** First five seconds: title, the one line, the map, one obvious tap. Share links
   keep working (`?area&day&beach`); "no cookies, no tracking" stays visible; a failed load says
   so in words (`fail()` in `app.js`). Budget: `style.css` < 15 KB, zero new network requests,
   no framework, no build step.
6. **Verify, then show.** `node --check site/static/app.js`, the harness again, `just quality`,
   and the before/after screenshots in the PR. A change of more than ~80 CSS lines without a
   screenshot is not done.

## Quick reference

| Want | Do |
|---|---|
| A "more polished" header | Bigger wordmark (26 px / 600), the one-line description, the same white panel, not a gradient |
| Floating panels that feel light | `--panel` background, `--line` border, one `0 6px 20px rgba(0,0,0,.15)` shadow, 10 px radius |
| Numbers that line up | `tabular-nums`, right-aligned, same size as the label |
| An icon | Inline SVG, `currentColor`, 16 px, `aria-hidden`, text label kept |
| Motion | Only what shows a state change, ≤ 150 ms, inside `@media (prefers-reduced-motion: no-preference)` |
| A marker that is not a dot | See MIP-0009 (wave `divIcon` filled with the score colour) |

## Red flags — the page starts looking generated

Gradient header or gradient logo tile · `backdrop-filter` / "frosted" / "glass" pills · hex codes
from Tailwind's default palette (`#14b8a6`, `#2dd4bf`, `#0ea5e9`, `#06213a`) instead of the
tokens · three navies · larger radii and deeper shadows as the meaning of "modern" · rise-in
animations on panels · a hint pill floating over the map · an emoji or "✨" in the `h1` · copy
with "smart", "AI-powered", "seamless" · `Inter` from a CDN · restyling 250 lines without opening
the page. Seeing one: go back to step 1.

## Common mistakes

| Mistake | Fix |
|---|---|
| Redesigning without rendering ("no board data in the worktree") | Build or copy `site/dist`, run the harness, screenshot, before any CSS |
| New palette for the chrome | Reuse the tokens; the accent already exists |
| Copy that describes the product instead of the sea | Nouns from the data: "best hour to swim, every beach, sea · wind · tide · water" |
| A grid on `.list li` | `app.js` emits bare text nodes next to spans: flex-wrap, or change the markup in `app.js` with the harness assertion updated |
| Changing `--c70 … --cna` to "nicer" greens | Those are the legend and the markers; a change there is a scoring-communication change and needs a MIP note |
</content>
