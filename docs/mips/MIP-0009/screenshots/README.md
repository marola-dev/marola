# MIP-0009 — screenshots of the wave markers and the hover aspects

The manual check MIP-0009 §7 / tasks decision #5 asks for, done on 2026-09-06 with headless
Chrome against the `site/dist` build of 2026-09-05 (80 Florianópolis beaches) plus this branch's
`index.html`/`app.js`/`style.css`. Two things were patched in a scratch copy, never in the repo:
that board predates task 1, so `wind_level` was added to every hour with the same 15/30 km/h
thresholds `Swimability` uses; and headless Chrome cannot hover, so a scratch-only hook opened
the tooltip programmatically (`marker.openTooltip()`, the same call a mouse-over makes).
Desktop is 1280×800, phone is 390×844. No phone-browser (Safari/Chrome on a device) check yet.

These were re-shot after the frontend review of the first round (the files below replace it):
one filled wave path instead of two thin ribbons and a white halo, the water verdict spanning
both columns of the aspect grid, past hours fading only their fill, and an 18 px legend key in
`--ink` that is hidden under 640 px.

| File | Shows |
|---|---|
| `desk-hover.png` | Joaquina's tooltip at the best hour: head, the six cells, wind band + compass, period, "best 07:00" for whales |
| `desk-north.png` | Ingleses' tooltip, where the water verdict is long — it now has the full width of the grid and stays inside the tooltip |
| `desk-area.png` | The whole area at the fitted zoom: 80 waves, each still showing its score colour where the coast is crowded |
| `desk-today-past.png` | Today's board after 13:10: every past-hour wave with a faded fill and a dashed white outline, legible over the sea tiles |
| `desk-card.png` | The card with the aspect row as its first block, before the score headline; the selected wave at 32 px on the map |
| `phone-card.png` | The same card at 390 px: the water verdict spans the row and stays inside the card; no legend key in the hour bar |
| `phone-area.png` | The area at 390 px |

What they show that `scripts/site_check.js` cannot (it proves markup, not pixels) is that the
four review findings are gone: the water cell fits the tooltip and the card, the score colour
survives the area zoom, a past-hour wave is still visible, and the legend key reads as a wave.
Two things they also show are older than MIP-0009 and out of its scope: at 390 px the page
itself is wider than the viewport, so the card and the footer are cut at the right edge (visible
in the first round's shots too), and a water-quality point's full street address overflows the
card's `dd`.
