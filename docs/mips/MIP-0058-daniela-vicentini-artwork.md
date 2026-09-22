# MIP-0058: A credited human artist — Daniela Vicentini's paintings on marola.dev, an "arte terapia" thanks link

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-15: "add real arts from daniela vicentini (artist) [Instagram], who preagreed to conceive some designs and paintings for this website, add arte terapia link in the website as thanks") |
| **Created** | 2026-09-15 |
| **Phase** | 3 — `ARCHITECTURE.md` §11 puts the static site under Phase 3 ("the first deploy artefact is already here and free … publishes the static map"). Nothing from Phase 1 or 2 gates a presentation/credit change |
| **Related** | MIP-0005 (the static site this edits), MIP-0044 (`about.html`'s existing "Who builds this" section, where the credit lands; Draft, not a blocker), MIP-0046 (Draft — replaces the map's emoji with hand-drawn chart marks; different real estate, see §9.1) and MIP-0047 (Draft — equation-drawn illustration motifs for the map's own furniture; also different real estate, see §9.1), MIP-0033 §5.2 (the CSP this MIP's asset placement must respect) |
| **Effort** | S — the credit line + links is one `about.html` edit, no build step, no dependency (§5.1). Embedding the actual paintings once delivered is a second, separate S/M-sized change this MIP scopes but does not build (§5.2) — its size depends on how many images and where, which isn't known until the artist delivers files |
| **Gain** | `user value` — a real, attributed piece of human work on a page whose whole design ethos (MIP-0046 §9.5, `site-frontend` skill) already refuses AI-generated visuals; this is the strongest possible source for "art on the site": a named person, asked, who agreed |
| **Effort vs Gain** | `do next` for §5.1 (the credit + links — cheap, self-contained, zero dependency on anything outside this MIP); `park` for §5.2 (embedding actual artwork) until the artist delivers files — there is nothing to build yet, and guessing at a layout for images that don't exist would be exactly the kind of unverified claim this MIP process refuses elsewhere |
| **Depends on** | None blocking in the MIP graph. Real-world dependency, not a MIP: §5.2 needs Daniela Vicentini to actually deliver artwork files and confirm usage terms (§11) before it can be implemented — that is a human/business step this MIP cannot substitute for |
| **Blocked by** | none |
| **Risk** | Hotlinking her images from her own site instead of hosting a copy in-repo makes the credit fragile — if her site reorganizes or goes down, marola.dev shows a broken image. §5.2 recommends self-hosting a copy (with her permission) for exactly this reason |
| **Cost so far** | — |

## 1. Summary

marola.dev gets its first credited human artist: Daniela Vicentini (@_danielavicentini), a visual
artist and art therapist whose own portfolio already includes watercolors of Campeche, the same
Santa Catarina coast marola's water-quality data (MIP-0056) covers. She has pre-agreed to conceive
designs and paintings for the site. This MIP adds a real, verified credit and thanks link to
`about.html` now, and separately designs (but does not build) how actual artwork gets embedded once
she delivers it, keeping those two things apart because only the first has anything to verify yet.

## 2. Motivation

The site has exactly one piece of artwork today (MIP-0047 §2: the two-stroke wave in `index.html`'s
`<h1>`), and two Draft MIPs (0046, 0047) already argue, at length, against filling that gap with
anything AI-generated or unattributed, MIP-0047 §9.5 rejects an AI-generated illustration outright
because "it is an artefact nobody can check, whose provenance is a prompt." A real artist, asked and
credited, is the strongest possible answer to that same question, and one neither Draft MIP
considered because neither was written with a specific collaborator in mind.

Separately, the request already includes something to thank her with, an "arte terapia" link, and
that deserves the same verification standard as any other external claim this MIP process makes: not
invented, checked against what she actually publishes (§4).

## 3. User-visible change

**Now (this MIP's §5.1), on `about.html`:**

Before, the page ends with "Who builds this" naming the project as open-source, no mention of
design or artwork:

```html
<h2>Who builds this</h2>
<p>marola is an open, in-progress project — the code, design proposals and architecture notes
are public in the repository. See <a href="/docs/">the docs</a> for the full picture, ...</p>
```

After, a new subsection follows it:

```html
<h2>Design &amp; artwork</h2>
<p>Paintings and visual design for marola are by <a href="https://www.instagram.com/_danielavicentini/">Daniela
Vicentini</a>, a visual artist and art therapist (<i>Doutora em Artes / Pesquisadora e terapeuta
artística</i>) whose own work includes watercolors of Campeche, on the same coast this site covers.
Her atelier: <a href="http://atelie.danielavicentini.com.br">atelie.danielavicentini.com.br</a>.</p>
```

**Later (§5.2, once artwork is delivered):** one or more of her paintings placed on `about.html`
and/or `index.html`, mechanism and placement designed in §5.2, not built here.

## 4. Data sources and dependencies reviewed

### 4.1 Instagram — `https://www.instagram.com/_danielavicentini/`

The artist's own profile, named in the request. Checked 2026-09-15: bio reads "Doutora em Artes /
Pesquisadora e terapeuta artística" (Doctor in Arts / Researcher and artistic therapist); this is
the profile's own claim to the "arte terapia" (art therapy) connection the request asked for, not an
inference. The bio does not use the literal words "arte terapia," but "terapeuta artística" is the
same practice named differently. Link-in-bio: `linktr.ee/danielavicentini`.

### 4.2 Linktree — `https://linktr.ee/danielavicentini`

Checked 2026-09-15: two links, a WhatsApp contact (`wa.link/yfo04v`, not suitable to publish on a
public site without her separate consent, and out of scope here) and "Print Fine Art" pointing to
`atelie.danielavicentini.com.br`. **No dedicated "arte terapia" URL exists**, see §11.

### 4.3 Atelier site — `http://atelie.danielavicentini.com.br`

Checked 2026-09-15: an e-commerce art shop selling her watercolors and fine-art prints, themed
series including Campeche beach scenes and cloud studies, R$478–R$3,000. Meta description: "aquarelas,
prints fine art, pintura, natureza, observação, fenomenologia, goetheanismo." Does not mention "arte
terapia" anywhere on the page itself, her art-therapy practice and her fine-art sales are evidently
kept as separate offerings, only the Instagram bio ties them to the same person.

**Pick:** the Instagram profile and the atelier site are both real, verifiably hers, and already
public, they're what §5.1 links. No "arte terapia"-specific URL was found to exist; §11 asks the
human to confirm whether one should be created, or whether the Instagram bio (which already states
her art-therapy credential) is the intended "thanks" link.

## 5. Design

### 5.1 The credit (this MIP's only built change, when implemented)

One `about.html` edit: a new `<h2>Design &amp; artwork</h2>` subsection after "Who builds this,"
per §3, above. No JS, no CSP change (both links are plain `<a href>`, and `about.html`'s CSP already
has no `script-src` opening to worry about, MIP-0033 §5.2's note applies to `img-src`, not to plain
links). No new file, no `site.yml` change, no dependency. `scripts/site_check.js` gets one new
assertion: the "Design & artwork" heading exists and its Instagram link resolves to
`_danielavicentini` (a string match against the fixture HTML, the same pattern `site_check.js`
already uses for the other nav/footer assertions, MIP-0009 §5).

### 5.2 Embedding actual paintings (designed, not built — blocked on real files)

Not implementable yet: no image file exists in this repo, and inventing a placeholder would violate
the same "don't guess at what isn't there" norm this MIP's own §4 follows. What's designed instead is
the mechanism the eventual implementation PR should use:

- **Self-host, don't hotlink.** `img-src 'self' https:` (both `index.html` and `about.html`'s CSP)
  already permits an external `https:` image, but §9.2 explains why that's the wrong call here:
  hotlinking makes the credit fragile against changes to a site marola doesn't control. Once she
  delivers files (with her permission to redistribute a copy), commit them under
  `site/static/art/` and reference them same-origin, zero CSP change needed either way, since
  `img-src 'self'` alone covers it.
  - Format/size: no answer yet, depends on what she sends. A reasonable default once files exist:
    a `.webp` derivative alongside the original for weight (the site is currently four plain files
    plus a vendored Leaflet, MIP-0047 §9.1's "no build step" constraint), sized to the placement.
- **Placement:** the most natural first spot is `about.html`'s new "Design & artwork" section
  (§5.1), a small image next to the credit text, not the map's own furniture (MIP-0009's markers,
  MIP-0046/0047's icon sprite), which stay exactly what those Draft MIPs already specify. A second
  placement, e.g. a header/hero background on `index.html`, is a larger visual decision the
  `site-frontend` skill's step 6 (a real browser at 390px and 1280px, not the stub-DOM harness)
  should gate before it ships, same as MIP-0046 §8 already requires for its own hand-drawn marks.
- **Attribution stays with the art**, not folded into `renderFooter()`'s JS-templated string
  (MIP-0009's footer, overwritten wholesale on every board load, per `index.html`'s own comment);
  the credit belongs in static HTML that isn't re-rendered, matching how the nav/footer credit
  lines already work in both `index.html` and `about.html` today.

## 6. Scoring / safety impact

None. This touches no scoring, no data pipeline, no LLM output.

## 7. Verification plan

- §5.1: `just quality-other` runs `scripts/site_check.js`; add one assertion there that
  `about.html` has the "Design & artwork" heading and an `<a>` to `instagram.com/_danielavicentini`.
- A live check at `marola.dev/about.html` (or `site-serve` locally) confirming both links resolve:
  `just site-live-check` is the existing pattern (MIP-0009-era addition) for checking what's actually
  published, not just what the fixture says.
- §5.2 has no verification plan yet, it isn't implementable until files exist; its own
  implementation PR should add the equivalent `site_check.js` assertions and a `site-frontend`
  step-6 screenshot check at that time.

## 8. Risks, limitations, and honest caveats

- **This MIP verifies the links, not the collaboration.** "Pre-agreed to conceive designs and
  paintings" is the requester's own account of a real-world arrangement with the artist; nothing in
  this MIP independently confirms the terms of that arrangement (§11).
- **No "arte terapia" URL exists to link to** (§4.2, §4.3), §5.1 links her Instagram (whose bio
  states the credential) and her atelier instead. If a dedicated art-therapy page or link is wanted,
  it doesn't exist yet and this MIP does not invent one.
- **§5.2 is a plan, not a delivery.** Nothing about layout, image count, or placement can be
  verified until real files exist, writing more detail here now would be guessing, which is exactly
  what §4's sourcing standard exists to prevent.
- **Licensing/usage terms are unconfirmed** (§11), a real person's copyrighted work needs an
  explicit understanding of what marola may do with it (display only? modify? for how long?), not an
  assumption borrowed from the repo's own MIT licence, which does not and should not extend to a
  third party's artwork by default.

## 9. Alternatives considered

**9.1 Fold this into MIP-0046 or MIP-0047 instead of a new MIP, rejected.** Both are about a
specific, already-scoped visual mechanism (a hand-drawn icon sprite; equation-drawn map furniture)
for a specific piece of UI (the map's markers and legend). A named human artist's paintings are a
different kind of asset for different real estate (`about.html`'s credit section, a possible hero
image), conflating them would make either MIP harder to review on its own terms. §5.2 explicitly
leaves MIP-0009's marker and MIP-0046/0047's sprite untouched.

**9.2 Hotlink her images directly from her atelier site, rejected, see §5.2.** Free and requires no
file transfer, but ties marola.dev's uptime and appearance to a site marola doesn't control, and
doesn't produce a citable, reviewable artefact the way a committed file does.

**9.3 Do nothing until the artwork itself is ready, credit included, rejected.** The credit and
links are independently true and verifiable today (§4); shipping them now costs one small,
self-contained PR and gives the artist visible thanks immediately, rather than making her wait for
however long file delivery and layout design take.

## 11. Open questions

- **Which link is "the arte terapia link"?** No dedicated URL for that specific practice was found
  (§4.2, §4.3). §5.1 defaults to her Instagram (bio states the credential) plus her atelier site.
  If Daniela has (or wants) a separate art-therapy-specific link, the human requester needs to
  supply it, this MIP will not invent one.
- **Usage terms for her artwork.** Once files are delivered (§5.2): is marola granted a licence to
  display them, for how long, exclusively or not, may they be resized/cropped, and is a specific
  credit line required beyond "Daniela Vicentini"? This needs a real answer from her, not an
  assumption.
- **Whether WhatsApp contact should be published.** §4.2 found a WhatsApp link in her Linktree;
  this MIP deliberately does not publish it (a different, more personal channel than an Instagram/
  atelier credit) unless she explicitly says she wants it on the site too.

## Appendix

### Checked live

- `https://www.instagram.com/_danielavicentini/`, fetched 2026-09-15. Bio: "Doutora em Artes /
  Pesquisadora e terapeuta artística." Link-in-bio: `linktr.ee/danielavicentini`.
- `https://linktr.ee/danielavicentini`, fetched 2026-09-15. Two links: WhatsApp
  (`wa.link/yfo04v`) and "Print Fine Art" → `atelie.danielavicentini.com.br`. No separate
  "arte terapia" link found.
- `http://atelie.danielavicentini.com.br`, fetched 2026-09-15. E-commerce art shop; watercolors
  and fine-art prints, Campeche beach scenes and cloud studies among the series, R$478–R$3,000.
  No mention of "arte terapia" on the page.
- `site/static/index.html`, `site/static/about.html`, `site/static/app.js`, read 2026-09-15 for
  the current CSP (`img-src 'self' https:` on both pages), the nav structure, and the
  `renderFooter()`/"kept out of #footer" split that §5.2 relies on.
- `docs/mips/MIP-0046-remove-ai-slop-ui.md`, `docs/mips/MIP-0047-desmos-equation-art.md`,
  `docs/mips/MIP-0044-site-sections.md`, read 2026-09-15 for the existing visual-identity
  philosophy and to confirm this MIP's real estate doesn't overlap theirs.

### Not checked

- Whether Daniela Vicentini's pre-agreement with M. Hoffmann has specific terms beyond "conceive
  some designs and paintings", this is a real-world arrangement this session has no way to verify
  independently; see §11.
- Image formats/resolution/rights she would actually deliver, nothing to check yet, no files
  exist.
- Whether `atelie.danielavicentini.com.br`'s watercolors are for sale under a licence that would
  also govern a *donated* piece for marola, the shop's commercial terms were not reviewed in
  detail because §5.2 assumes any artwork for marola is a separate, direct arrangement with her,
  not a purchase from that shop.
