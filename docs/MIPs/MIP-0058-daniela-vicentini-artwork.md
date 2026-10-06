# MIP-0058: Credited human artists — a local-artists list on marola.dev, Daniela Vicentini first

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-15: "add real arts from daniela vicentini (artist) [Instagram], who preagreed to conceive some designs and paintings for this website, add arte terapia link in the website as thanks"); revised 2026-10-06 for his request "start filling buckets for artists, first artist will be Dani Vicentini … 4 fields name, short descp, website (icon link - optional), instagram link (optional)" |
| **Created** | 2026-09-15 |
| **Phase** | 3 — `ARCHITECTURE.md` §11 puts the static site under Phase 3 ("the first deploy artefact is already here and free … publishes the static map"). Nothing from Phase 1 or 2 gates a presentation/credit change |
| **Related** | MIP-0005 (the static site this edits), marola-dev/marola-site#59 (the footer toggles, including the "artistas locais" placeholder this fills), MIP-0046 (Draft — hand-drawn chart marks for the map; different real estate, see §9.1) and MIP-0047 (Draft — equation-drawn illustration motifs; also different real estate, see §9.1), MIP-0033 §5.2 (the CSP) |
| **Effort** | S — the list is one JSON file, a footer panel in `app.js` and its checks (§5.1, marola-dev/marola-site#69). Embedding actual paintings once delivered is a second, separate S/M-sized change this MIP scopes but does not build (§5.2) |
| **Gain** | `user value` — real, attributed human work on a page whose design ethos (MIP-0046 §9.5, `site-frontend` skill) already refuses AI-generated visuals: named people, asked, who agreed |
| **Effort vs Gain** | `do next` for §5.1 (the list, no dependency); `park` for §5.2 (embedding artwork) until the artist delivers files |
| **Depends on** | None blocking in the MIP graph. Real-world dependency: §5.2 needs Daniela Vicentini to deliver artwork files and confirm usage terms (§11) |
| **Blocked by** | none |
| **Risk** | Hotlinking an artist's images from her own site makes the credit fragile; §5.1 publishes links only, and §5.2 self-hosts a copy (with her permission) |
| **Cost so far** | — |

## 1. Summary

marola.dev gets a list of credited local artists: the map footer's "artistas locais" toggle, a
placeholder since marola-dev/marola-site#59, opens one line per artist with a name, a short
description and optional links to the artist's website and Instagram. The first entry is Daniela
Vicentini (@_danielavicentini), a Florianópolis artist working in watercolour, felting, drawing and
writing, who has pre-agreed to conceive designs and paintings for the site. The list is a data file,
so adding the next artist is a one-entry PR. Embedding her actual artwork is designed separately
(§5.2) and not built, because there are no files to place yet.

## 2. Motivation

The site has one piece of artwork today (MIP-0047 §2: the two-stroke wave in `index.html`'s
`<h1>`), and two Draft MIPs (0046, 0047) argue against filling that gap with anything AI-generated
or unattributed; MIP-0047 §9.5 rejects an AI illustration because "it is an artefact nobody can
check, whose provenance is a prompt." Real artists, asked and credited, answer the same question.
A list rather than a one-off credit gives the thanks the request asked for and leaves room for the
local artists the footer toggle already promises.

## 3. User-visible change

Before: the footer's third toggle, "artistas locais", is muted, `aria-disabled`, titled "em breve",
and opens nothing.

After: it opens a panel like the other two toggles. Each artist is one line: the name, the
description in the page's language, then a globe icon linking the website and the Instagram icon
linking the profile, each with an accessible name ("site de Daniela Vicentini", "Daniela Vicentini
no Instagram"). Daniela Vicentini's line (pt-BR): "aquarelas, feltragens, desenhos e escritos que
partem da observação da natureza, em Florianópolis."

## 4. Data sources and dependencies reviewed

### 4.1 Her site — `https://www.danielavicentini.com.br/`

Checked 2026-10-06. Title "obras – daniela vicentini"; sections for her artistic production
(obras, exposições, textos), essays and curatorships, oficinas, the ateliê and "sobre"
(`/cv/`). The "sobre" page: "Daniela Vicentini realiza sua produção em aquarelas, feltragens,
desenhos e escritos. Pesquisa conceitos de natureza, num processo de repetidamente se colocar
diante de fenômenos da natureza"; a doctorate in Arts (UDESC, 2019–2023), a master's in History
(PUC-Rio), a bachelor's in Painting (EMBAP); based in Florianópolis. The list's description is
taken from this page and nothing else.

### 4.2 Instagram — `https://www.instagram.com/_danielavicentini/`

Checked 2026-09-15 (Instagram refuses automated fetches since): bio "Doutora em Artes /
Pesquisadora e terapeuta artística"; link-in-bio `linktr.ee/danielavicentini`. "Terapeuta
artística" is the art-therapy credential the original request's "arte terapia" link pointed at;
the Instagram link carries it.

### 4.3 Linktree and atelier

`linktr.ee/danielavicentini` (checked 2026-09-15): a WhatsApp contact (not published, §11) and
"Print Fine Art" → `atelie.danielavicentini.com.br`, a shop for her watercolours and fine-art
prints, Campeche beach scenes among them. Her main site links the atelier, so the list does not
link it separately.

**Pick:** the website (§4.1) and the Instagram profile (§4.2), the two links the 2026-10-06 request
names.

## 5. Design

### 5.1 The local-artists list (built in marola-dev/marola-site#69)

- `site/static/artists.json` holds `{"artists": [...]}`, in display order. Each entry: `name`;
  `description` with a `pt-BR` and an `en` string, one short line taken from what the artist
  publishes about their own work; optional `website` (`https://`); optional `instagram`
  (`https://www.instagram.com/<handle>/`). Nothing else: no images, no contact details.
- `app.js` fetches it once on load, independent of the boards. With at least one artist, the
  toggle becomes a fold like the lore and privacy ones, its panel kept open across re-renders and
  language flips; without the file, or with an empty list, the toggle stays the placeholder.
- The links are plain `<a target="_blank" rel="noopener">` around Lucide's `globe` and `instagram`
  icons (ISC, already vendored), each a 32 px target with an `aria-label` from the catalogs. No CSP
  change: no third-party request is made until a visitor follows a link.
- Names keep their own case (an exemption in the lowercase house style); descriptions follow it.
- `site.yml`'s publish allowlist gains `artists.json`; `scripts/site_check.js` validates every
  entry and the rendering. Adding an artist is a PR appending an entry, documented in
  marola-site's `docs/4-reference.md`.

### 5.2 Embedding actual paintings (designed, not built — blocked on real files)

Not implementable yet: no image file exists, and a placeholder would guess at what isn't there.
The mechanism for the eventual PR:

- **Self-host, don't hotlink.** `img-src 'self' https:` already permits an external image, but
  hotlinking ties marola.dev's appearance to a site marola doesn't control (§9.2). Once she
  delivers files, with permission to redistribute a copy, they go under `site/static/art/`,
  same-origin, no CSP change. Format: a `.webp` derivative sized to the placement, decided once
  files exist.
- **Placement:** first next to her entry in the artists panel or on `about.html`, not the map's
  own furniture (MIP-0009's markers, MIP-0046/0047's sprite). A hero image on `index.html` is a
  larger visual decision gated by the `site-frontend` skill's real-browser check at 390 and
  1280 px.

## 6. Scoring / safety impact

None. This touches no scoring, no data pipeline, no LLM output.

## 7. Verification plan

- §5.1: `scripts/site_check.js` checks `artists.json`'s shape (fields, `https`, the Instagram
  host), that it is on the publish allowlist, the placeholder without the file, the real fold with
  it, escaping, opening and closing, and the language flip; before/after screenshots at 390 and
  1280 px in both languages on marola-dev/marola-site#69.
- After deploy, `just site-live-check` (in a marola-site checkout) and a look at the footer on
  marola.dev.
- §5.2's own PR adds its assertions and screenshots when files exist.

## 8. Risks, limitations, and honest caveats

- **This MIP verifies the links and the description, not the collaboration.** "Pre-agreed to
  conceive designs and paintings" is the requester's account; its terms are §11's.
- **Descriptions are short paraphrases of each artist's own page.** A wording the artist would
  change is a one-line PR; nothing about an artist is written from memory or inferred.
- **§5.2 is a plan, not a delivery**, and licensing is unconfirmed: the repo's MIT licence does
  not extend to a third party's artwork.

## 9. Alternatives considered

**9.1 Fold this into MIP-0046 or MIP-0047, rejected.** Both scope a specific visual mechanism for
the map's markers and legend; credited artists and their paintings are different assets for
different real estate.

**9.2 Hotlink her images from her site, rejected.** Free, but ties marola.dev's appearance to a site
marola doesn't control, and leaves no reviewable artefact.

**9.3 A "Design & artwork" section on `about.html` only, rejected.** It credits one person in
prose, where the footer toggle already promised a list of local artists; the list is the same
credit, one click from the map, and takes the next artist without a page edit.

## 11. Open questions

- **Usage terms for her artwork** (§5.2): display licence, duration, exclusivity, cropping, the
  credit line she wants. Needs her answer.
- **Whether her WhatsApp should be published.** No, unless she asks for it; the list has no
  contact field.
- **Who counts as a local artist** for the next entries (near one of the board's areas, or any
  collaborator): the requester's call, entry by entry, until a rule is needed.

## Appendix

### Checked live

- `https://www.danielavicentini.com.br/` and `https://danielavicentini.com.br/cv/`, fetched
  2026-10-06 (§4.1).
- `https://www.instagram.com/_danielavicentini/`, fetched 2026-09-15 (§4.2); refused by the site's
  robots rules on 2026-10-06.
- `https://linktr.ee/danielavicentini` and `http://atelie.danielavicentini.com.br`, fetched
  2026-09-15 (§4.3).
- marola-site's `app.js` `renderFooter()` and the #59 placeholder, `style.css`'s lowercase
  exemptions and `site.yml`'s publish allowlist, read 2026-10-06 for §5.1.

### Not checked

- The terms of Daniela Vicentini's pre-agreement with M. Hoffmann (§11).
- Image formats and rights she would deliver: no files exist yet.
