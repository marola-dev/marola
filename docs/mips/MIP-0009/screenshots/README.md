# MIP-0009 — screenshots of the wave markers and the hover aspects

The manual check MIP-0009 §7 / tasks decision #5 asks for, done on 2026-09-06 with headless
Chrome against the `site/dist` build of 2026-09-05 (80 Florianópolis beaches) plus this branch's
`index.html`/`app.js`/`style.css`. Two things were patched in a scratch copy, never in the repo:
that board predates task 1, so `wind_level` was added to every hour with the same 15/30 km/h
thresholds `Swimability` uses; and headless Chrome cannot hover, so a scratch-only hook opened
the tooltip programmatically (`marker.openTooltip()`, the same call a mouse-over makes).
Desktop is 1280×800, phone is 390×844. No phone-browser (Safari/Chrome on a device) check yet.

| File | Shows |
|---|---|
| `desk-hover.png` | Joaquina's tooltip at the best hour: head, the six cells, wind band + compass, period, "best 07:00" for whales |
| `desk-north.png` | Ingleses' tooltip, where the water verdict is long — the cell runs past the tooltip's edge |
| `desk-area.png` | The whole area at the fitted zoom: 80 waves, the north-west coast overlapping into one shape |
| `desk-today-past.png` | Today's board after 13:10: every past-hour wave at 50 % opacity over the sea tiles |
| `desk-card.png` | The card with the aspect row as its first block, before the score headline |
| `phone-card.png` | The same card at 390 px: the water cell is cut at the card's edge; the legend key is cut at the bar's edge |
| `phone-area.png` | The area at 390 px |

What they show that `scripts/site_check.js` cannot (it proves markup, not pixels) is the
subject of the review on the task 4 PR: the water cell overflowing the tooltip and the card,
the white stroke and shadow outweighing the score colour at area zoom, past-hour waves that
nearly vanish, and the 14 px legend glyph reading as two dots.
