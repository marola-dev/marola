---
name: architecture-diagram
description: "Draw a polished architecture or repo-map diagram as a hand-written SVG a README can embed: dark grid card, colour per kind of component, arrows behind boxes, a legend outside every boundary. Use when asked for a README diagram, a prettier picture than GitHub's Mermaid, how the repos or services fit together, or to redraw docs/img/*.svg. Not for a diagram inside docs/ pages, which is a Kroki fence (DIAGRAMS.md), nor a hand-drawn sketch (excalidraw)."
---

# architecture-diagram

Pattern from Cocoon-AI/architecture-diagram-generator@4b9087d (MIT; adapted, `LICENSE` beside this file).

A README is read on GitHub, which renders no d2, PlantUML or Excalidraw and draws Mermaid
plainly. So a README diagram is one hand-written SVG under `docs/img/`, shown with
`<p align="center"><img src="./docs/img/<name>.svg" alt="…" width="860" /></p>`. The SVG is its
own source: no build step, no generator committed beside it. The reference is
`docs/img/umbrella.svg`; read it before drawing.

## Steps

1. **Read the prose the diagram sits beside.** Draw only what it already says (DIAGRAMS.md), at
   most ~12 boxes. Name every box and arrow in the reader's words; each arrow carries one label.
2. **Lay out on paper first.** Pick `viewBox` (1000 wide; height to fit). Columns left to right
   in the direction things flow; give every box its x, y, w, h and every arrow its path before
   writing markup. Leave ≥ 40 px between boxes and ≥ 110 px between columns that carry a labelled
   arrow (11 px monospace is ~6.7 px a character).
3. **Write the SVG in this order** (document order is paint order):
   1. `<style>` with the classes below, `<defs>` with the grid pattern and one arrow marker per
      arrow colour.
   2. The card: `<rect rx="18" fill="#020617"/>`, then the same rect filled with the grid.
   3. Title (a dot plus one line), then boundaries: dashed (`8,5`), faint fill, label inside top-left.
   4. **Arrows**, so boxes paint over their ends. Orthogonal `path`s (`H`/`V` only).
   5. **Boxes**: an opaque `#0b1222` rect first, then the tinted rect on it, so no arrow shows through.
   6. The legend, below every boundary.
4. **Two languages, two files.** When both READMEs show it, write `<name>.svg` and
   `<name>.pt-BR.svg` with the same geometry; translate, then shorten any label that no longer fits.
5. **Check it renders**, then look at it: Chromium on a page that holds only the `<img>`
   (`--headless --screenshot`), at 1000 px. Fix any text crossing a box edge or another label.
   Then `just quality`.

## Design system

| Kind | Fill | Stroke |
|---|---|---|
| the product, a backend | `rgba(6,78,59,0.55)` | `#34d399` |
| a page people visit | `rgba(8,51,68,0.55)` | `#22d3ee` |
| a store, a lake | `rgba(76,29,149,0.5)` | `#a78bfa` |
| models, compute | `rgba(120,53,15,0.45)` | `#fbbf24` |
| shared tooling | `rgba(124,45,18,0.45)` | `#fb923c` |
| a source, anything else | `rgba(30,41,59,0.7)` | `#94a3b8` |

- Boxes `rx="8"`, stroke 1.6. Name 15 px bold `#f8fafc`; sub-lines 11.5 px `#94a3b8`, 17 px apart.
- Arrows 1.8 px: `#7dd3fc` solid for what flows forward, `#c4b5fd` dashed `6,4` for what flows
  back. Edge labels 11 px with `paint-order: stroke; stroke: #020617; stroke-width: 5px`, so they
  read over the grid and over lines.
- Font: `"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono",
  monospace`. An SVG in `<img>` cannot load web fonts, so lay out for the widest fallback.
- Nothing external: no `<script>`, no `<image href>`, no font link. GitHub strips them.

## What was left out of the upstream skill

Its HTML page, summary cards, export toolbar and CDN scripts (html2canvas, jsPDF): a README needs
the bare SVG. Its AWS component palette is re-pointed to the kinds above.
