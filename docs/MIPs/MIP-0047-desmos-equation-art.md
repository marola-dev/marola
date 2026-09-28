# MIP-0047: Equation-drawn art — Desmos as the sketchpad, the equation as the source, static SVG as the only thing shipped

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Opus 5, for M. Hoffmann (request of 2026-09-07, verbatim: "create a new MIP with opus to generate desmos images (draw by equation). get some desmos already made drawings related to the sea, create new ones and add this as a stylistic philosophy") |
| **Created** | 2026-09-07 |
| **Phase** | 3 — `ARCHITECTURE.md` §11 puts the static site under Phase 3 ("the first deploy artefact is already here and free: `site.yml` … publishes the static map"). Nothing from Phase 1 or 2 gates a presentation change. Same honest caveat MIP-0046 records: MIP-0009 and MIP-0037 label this same surface "Phase 1" — a pre-existing docs inconsistency, not resolved here |
| **Related** | MIP-0046 (the sprite mechanism this reuses verbatim — `<symbol>` in `index.html` + `<use href="#…">`; this MIP adds symbols to *that* block and does not invent a second one), MIP-0009 (`WAVE_PATH`, the marker glyph and the legend key — deliberately **not** replaced here, §5.5), MIP-0044 (its `/about/`, `/news/`, `/dev/` sections are where a large motif has room to be more than an icon; its §4 also found the generated scaladoc pages loading four third-party scripts, which is the same "nothing loads from a third party" claim this MIP's §9.1 refuses to break), MIP-0005 (the site's "plain files, no build step" constraint, which §5.4 keeps), `knowledge/waves-tides-glossary.md` and `knowledge/safety/rip-currents.md` (the domain the motifs draw), `PHILOSOPHY.md` (§11's follow-up) |
| **Effort** | S — one ~200-line stdlib Python script with a `--self-test` (the shape `scripts/*.py` already has), one equations file, a generated `<symbol>` block inside the sprite MIP-0046 introduces, and one `site_check.js` assertion. No new dependency, no CI workflow, no `site.yml` change, nothing added to `site/dist`. The *authoring* (tuning five drawings in Desmos until they look right) is hours of human eye-work, but it is not build effort and it produces no reviewable artefact except numbers |
| **Gain** | `user value` — the site gets a visual identity that is about the sea rather than about the framework, at the one moment a first-time visitor decides whether to trust its numbers; `infra/dev-loop` — an illustration whose source is a formula is reviewable in a diff and reproducible by re-running a script, which no hand-drawn or generated image is |
| **Effort vs Gain** | `do when MIP-0046 lands` — the mechanism is MIP-0046's sprite block. It is buildable first (this MIP would then introduce the block itself) but that duplicates ~25 lines of CSS and creates a mechanical conflict for whoever lands second, for no gain. MIP-0046 is the smaller, more urgent change; this one waits one merge |
| **Depends on** | MIP-0046 for the delivery mechanism (`<symbol>`/`<use>`, `.sprite`/`.ic` CSS) — coordination, not a hard gate: if this landed first it would have to create that block, and MIP-0046 would then extend it. MIP-0044 (Draft) is where the large motifs get room; without it, only the map page exists and §5.6 limits the rollout to two placements. No Phase-1 gate, no cloud resource, no paid tier, no API key — §4.1 verifies the free path and §4.2 verifies exactly where the paid/keyed one starts |
| **Blocked by** | none |
| **Risk** | Five equation-drawn illustrations are five chances to ship something that looks like a maths homework screenshot rather than a considered drawing. The mechanism is cheap and verifiable; the *taste* is not, and nothing in `just quality` can tell the difference. If the first motif does not survive a look at 1280 px, the honest outcome is to ship one motif, not five |
| **Cost so far** | — |

## 1. Summary

marola's visual vocabulary becomes **equation-drawn**: a small set of illustrative motifs, a
trochoidal swell, a right-whale silhouette, a jellyfish, a two-constituent tide curve, a rip-current
streamline pair, designed by tuning real equations in Desmos's free graphing calculator, then
**brought home as the equations, not as the picture**, and rendered by a small in-repo script into
the same inline SVG `<symbol>` sprite MIP-0046 introduces. Nothing from Desmos is fetched at runtime
and no Desmos-exported file is committed; the shipped asset is marola's own rendering of marola's
own formulae, MIT like the rest of the repo. §11 proposes one paragraph in `PHILOSOPHY.md`, with a
real call on what that paragraph may and may not claim.

## 2. Motivation

The site has exactly one piece of artwork: the two-stroke wave in `index.html`'s `<h1>` and the
filled `WAVE_PATH` marker it echoes (`app.js:172`). Everything else a visitor sees is data,
chrome, or (until MIP-0046 lands) a system emoji. There is no illustration, and there is no rule
about what an illustration here may be: a grep of `AGENTS.md`, `PHILOSOPHY.md`, `docs/*.md` and the
`site-frontend` skill for `AI-generated|generated image|stock photo|illustration` returns exactly
one hit, and it is the skill's own trigger phrase, not a policy (checked 2026-09-07).

So the first illustration this repo commits also decides the category. Three options were actually
open: a stock/AI-generated image, a hand-drawn vector, or a drawing that is a formula. The repo
already has a strong opinion about the analogous question for *text*: "no unsourced text reaches a
user", the `mip` skill's shortest rule, and no opinion at all about pictures. An equation-drawn
motif is the only one of the three whose review surface is a diff: `h_w = 0.42` changing to `0.31`
is a reviewable line, and a re-run of the generator reproduces the artwork byte-for-byte. That is
the *infra* argument, and it is the one that holds; §8 says plainly which stronger argument does not.

The aesthetic argument is separate and also real: marola is about the sea, and the shapes the sea
actually makes are the ones its physics writes down. A trochoid is not a decorative wave: it is
the wave (§5.1.1). Drawing the site's furniture from the same families the pipeline reasons about
is the visual version of what `scoring/` already is.

## 3. User-visible change

No text, no number, no colour, no threshold changes. What changes is that the page has drawings.

Today, `site/static/index.html`, the whole of the site's artwork:

```html
<h1><svg …><path d="M2 9c2.5 0 2.5-2.5 5-2.5S9.5 9 12 9…"/>…</svg>marola</h1>
```

After (the sprite MIP-0046 §5.1 introduces, extended, ids namespaced `art-` so the two sets never
collide with its 14 px functional marks):

```html
<svg class="sprite" aria-hidden="true">
  <!-- MIP-0046's functional marks: i-wind-0 … i-lifeguard -->
  <!-- equation-art:start  generated by scripts/equation_art.py — edit site/equations.json -->
  <symbol id="art-swell"  viewBox="0 0 240 60">…</symbol>
  <symbol id="art-whale"  viewBox="0 0 120 80">…</symbol>
  <symbol id="art-jelly"  viewBox="0 0 80 120">…</symbol>
  <symbol id="art-tide"   viewBox="0 0 240 60">…</symbol>
  <symbol id="art-rip"    viewBox="0 0 120 120">…</symbol>
  <!-- equation-art:end -->
</svg>
```

Placements in v1 (two, deliberately few, §5.6): the swell as a full-width rule under the header,
replacing nothing; the whale, jellyfish or rip as the illustration of the empty/loading state the
map shows before a board arrives, where there is currently blank panel. Everything else waits for
MIP-0044's sections to exist.

## 4. Data sources and dependencies reviewed

### 4.1 Desmos's free graphing calculator, as an authoring tool — **the pick**

`https://www.desmos.com/calculator` is free and needs no account to graph (an account is needed
only to *save* a graph; not verified independently, see "Not checked"). Its Export Image dialog
offers both PNG and SVG: the live app bundle
(`/assets/build/shared_calculator_desktop-bc7c26f4….js`, fetched 2026-09-07, 5.2 MB) contains the
localization strings `account-shell-button-download-png = Download PNG`,
`account-shell-button-download-svg = Download SVG`, `account-shell-heading-file-format = File
Format`. So a keyless, free SVG export genuinely exists.

**It is still not what gets committed**, and the reason is licensing, not convenience. Desmos's
Terms of Service (fetched 2026-09-07, the page is a JS shell, so the text was read out of
`/assets/build/frontpage/terms-967a1656….js`) says two things that matter, and they point in
opposite directions:

- **§6 User Submissions and General Materials:** "Desmos does not claim ownership of any materials
  … lessons, **formulae**, information, data, text or other materials you submit and create … As
  between Desmos and you, **you own all rights** to your User Submissions and Generated Materials."
- **§5 Personal, Non-Commercial Use Only for Desmos Tools:** "you are authorized to distribute that
  Content generated using the Desmos Tools from your User Submissions and Generated Content **under
  a Creative Commons 'Attribution-ShareAlike' License (CC-BY-SA-4.0)**, with attribution to include
  reference to 'Desmos', 'Desmos Studio PBC' or the Desmos logo."

Read together: **the equations come home under the author's own copyright; the exported picture
comes home under CC-BY-SA-4.0 with a Desmos attribution.** This repo is MIT (`LICENSE`, "MIT
License, Copyright (c) 2026 Matheus Hoffmann"). Committing a Desmos-exported SVG would put a
share-alike, attribution-bearing asset inside an MIT tree, a real obligation, not a formality, and
one that would have to be tracked in a NOTICE for the life of the file. Re-rendering the author's
own equations in-repo avoids it entirely and costs one small script (§5.4). §5 of the same terms
also restricts the Desmos *Tools* to "personal, non-commercial use"; marola is a free personal
project with no paid tier, so authoring there is inside that limit today, worth re-reading if that
ever changes.

### 4.2 The Desmos JS API (`calculator.js`) and `asyncScreenshot` — reviewed, rejected (§9.1)

- **It needs a key.** The docs (`https://www.desmos.com/api/v1.11/docs/index.html`, fetched
  2026-09-07) give the load URL as
  `https://www.desmos.com/api/v1.11/calculator.js?apiKey=[YOUR_API_KEY_HERE]`, "To obtain your own
  API key, visit desmos.com/my-api". Fetching that script **without** a key returns **HTTP 403**
  (checked 2026-09-07, 52 bytes of body).
- **`asyncScreenshot` really can emit SVG.** Same docs, verbatim: "callback will be called with the
  either a URI string or SVG string as its argument … the ability to output SVG in addition to PNG
  images. … `opts.format` String that determines the format of the generated image. May be either
  `'png'` or `'svg'`. Defaults to `'png'`." It operates on a *live calculator instance in a page*,
  so any programmatic use means a headless browser, a dependency this repo does not have and this
  MIP does not add.
- **The API's own terms are stricter than the calculator's.** `https://www.desmos.com/api-terms`
  (same JS-shell trick, fetched 2026-09-07): a free "Trial Tier" whose limit is "solely for (a)
  personal, non-commercial use or (b) a 90 day trial for internal testing", a paid "Commercial
  Tier" otherwise, and §5.b forbidding "(ii) use, reproduce, distribute or publicly display any
  Content other than through the Applications; (iii) remove, alter, or obscure any branding
  (including copyright and trademark notices) of Desmos Studio".

### 4.3 Existing public Desmos sea graphs, as prior art — cited, **not copied**

Four were verified by fetching each graph's thumbnail (`/calc_thumbs/production/<id>.png`) and
looking at it, and by fetching its saved state
(`https://saved-work.desmos.com/calc-states/production/<id>`, an **undocumented** endpoint found by
request, not from Desmos's docs) and reading the actual LaTeX:

| Graph | What it actually renders | Why it matters here |
|---|---|---|
| [Troichoidal Ocean Waves](https://www.desmos.com/calculator/rqvqvf6uy8) | A trochoid construction: rolling circle, generating point, the traced curve | Its state contains `p_x(t)=\frac{L_w}{2\pi}t-h_w\sin t`, `p_y(t)=h_w\cos t` with wavelength `L_w` and height `h_w` as sliders — the exact family §5.1.1 uses, and proof it reads as a sea surface |
| [The Ocean](https://www.desmos.com/calculator/koa2npwoir) | A full scene: layered wave bands, a boat, and a jellyfish — all equations, with an animation parameter `v` | The best evidence that a *scene*, not just a curve, is achievable this way |
| [Whale](https://www.desmos.com/calculator/gaeav8oi2q) | A whale under a wavy sea line, a sun, an octopus | Its state is ~40 domain-restricted conics and sinusoids (`9=(x+2)^2+(y+7)^2\{-9.2<y<1\}\{-5.1\le x\le0\}`) — an honest look at how much fiddling a recognisable animal costs |
| [Jellyfish](https://www.desmos.com/calculator/a83ylfdj0s) | A bell with oscillating tentacles streaming from it | Its tentacles are phase-shifted restricted sinusoids (`\frac{\sin(3x)}{2}\{-2<x<8.5\}`, `\|\sin(x+1)\|+1`), **not** polar roses — which is why §5.1.3 proposes damped sinusoids |

Two candidates from the same searches were checked and **failed**: `Jellyfish Graph`
(`2yttmwvhzv`) renders only a short line of red dots, and `Trig project- whale` (`omhbfyzodp`)
renders an empty grid. Both are named plausibly and neither draws anything; they are recorded here
because the failure mode of this section is citing a title.

**Licensing of the above: the finding that decides §9.3.** Desmos's ToS §6 assigns ownership of a
user's submissions to *that user*. The CC-BY-SA carve-out in §5 authorizes **you** to distribute
Content generated from **your** submissions; nothing in the terms grants one user a licence to
another user's graph, and "public" on Desmos means visible, not licensed. So these five graphs are
cited as prior art and inspection material only. The one thing taken from them is a *fact about
mathematics*, the trochoid parametrization, published by Gerstner in 1802 and in every coastal-
engineering text, which is not anyone's expression to license.

### 4.4 The renderer: Python 3 stdlib, already in the shell

`flake.nix` provides `python3`; `just quality-other` already runs `ruff check .`, `ruff format
--check .` and a `--self-test` on each of six `scripts/*.py` (`justfile:75-90`, read 2026-09-07). A
sixth-of-a-page sampler that turns `x(t), y(t)` into an SVG path needs `math` and `json` and
nothing else. No new dependency, no `flake.nix` change.

## 5. Design

### 5.1 The five motifs, and the actual mathematics behind each

Each is a real family a human can build in Desmos in an evening: parameters below are starting
points to tune, not results.

**5.1.1 `art-swell`: a trochoidal wave train.** Parametric, one curve:
`x(t) = (L/2π)·t − h·sin t`, `y(t) = h·cos t`, `t ∈ [0, 2πn]` for `n` crests. This is the trochoid
(Gerstner) surface: sharp crests, broad flat troughs, visibly *not* a sine, which is the whole
point, because a sine is what a generic wave graphic is. Two or three trains superposed at
different `(L, h)` and offset vertically give the layered-swell band; the sea is a spectrum, not one
wave, so the superposition is the honest drawing. Verified prior art: `rqvqvf6uy8` (§4.3). Deep
water relates `L` to period by `L = gT²/2π`, and the board already carries `sea.period_s` and
`wave_m` per beach, see §11 for why v1 does **not** make the drawing data-driven.

**5.1.2 `art-whale`: a southern right whale silhouette** (the species `knowledge/whales-santa-
catarina.md` is about). Body: a superellipse `|x/a|^n + |y/b|^n = 1` with `n ≈ 2.6`, blunter than
an ellipse, which is exactly the right whale's shape, and the one parameter that separates it from
a dolphin. Fluke: two lobes of the rose `r(θ) = c·sin 2θ` rotated onto the tail root. Head
callosity: a small circle differenced out of the outline near `x = −a`. Drawn as one closed
parametric outline so it fills as a silhouette. Honest: the fluke-as-rose-lobe is a design bet, and
`gaeav8oi2q` (§4.3) is evidence that whales cost more expressions than one expects.

**5.1.3 `art-jelly`: a jellyfish.** Bell: the upper half of a lobe-modulated circle,
`r(θ) = a(1 − ε·cos 4θ)`, `θ ∈ [0, π]`, `ε ≈ 0.12`, a dome with four soft scallops, not a rose;
a true rose (`r = a sin kθ`) makes a flower, which is the mistake this note exists to avoid.
Tentacles: `k` damped, phase-shifted sinusoids hanging from the rim,
`y_j(s) = −s`, `x_j(s) = x_j0 + A·e^(−λs)·sin(ω s + jφ)`, `s ∈ [0, S]`, with `A` and `S` varying per
strand so they do not read as a comb. Evidence: `a83ylfdj0s` uses restricted phase-shifted
sinusoids for exactly this and it works.

**5.1.4 `art-tide`: a real tide curve, not a sine.** `h(t) = A_M2·cos(2πt/T_M2 + φ₁) +
A_S2·cos(2πt/T_S2 + φ₂)` over `t ∈ [0, 30] h`, with `T_M2 ≈ 12.42 h` (principal lunar semidiurnal)
and `T_S2 = 12.00 h` (principal solar semidiurnal). NOAA's own page (fetched 2026-09-07) states M2
"has 2 peaks every 24-hours and 50 minutes" and S2 "2 peaks every 24-hours", which is where those
two periods come from. With `A_M2 ≈ 2·A_S2` the sum gives the unequal successive highs a real tide
has and a single sinusoid cannot: the drawing is the same harmonic model tide prediction uses, at
two terms.

**5.1.5 `art-rip`: a rip current, as streamlines.** Level curves of a stream function for uniform
onshore flow plus a sink at the rip neck: `ψ(x, y) = U·y − (m/2π)·arctan(y/x)`; plot two or three
level sets `ψ = c_k`. They converge into a narrow seaward jet and spread into the head: the shape
`knowledge/safety/rip-currents.md` describes in words. This is the one motif that could later carry
meaning rather than mood, and the one to draw most carefully, because a decorative safety diagram
that misleads is worse than none (§8).

### 5.2 What comes back from Desmos

Only numbers and formulae, in one checked-in file, `site/equations.json`:

```json
{ "art-swell": { "viewBox": [240, 60], "curves": [
    { "kind": "parametric", "x": "(L/(2*pi))*t - h*sin(t)", "y": "h*cos(t)",
      "t": [0, 12.566], "samples": 240, "params": { "L": 60, "h": 9 },
      "stroke": 2, "fill": null } ] } }
```

The expression strings are evaluated by the generator against a whitelisted `math`-only namespace:
no `eval` of arbitrary Python, which is the one security note this file carries (a `site/` data file
is not attacker-controlled here, but a generator that `eval`s is a bad habit to install).

### 5.3 What is *not* supported, stated before anyone assumes it

The generator handles **explicit parametric curves** `x(t), y(t)` and closed outlines built from
them. It does **not** handle Desmos's implicit/inequality forms (`4 ≥ (x−4)² + (y−2)²`,
`−y² + 2x ≥ −8 {x < −2}`), which is how much of the prior art in §4.3 is actually written, nor
Desmos's automatic domain restrictions. There is **no lossless equation-to-SVG-path pipeline**:
any claim of one would be wrong. A drawing sketched with inequalities in Desmos has to be
re-expressed parametrically by its author before it can come home. That is real authoring friction
and it is the reason §5.1 chose five motifs that are naturally parametric.

### 5.4 The generator: `scripts/equation_art.py`

Stdlib only, ~200 lines, three jobs: sample each curve at `samples` points; emit a `<path d="M…L…">`
(or `Q` smoothing) fitted into the `viewBox` with a stated margin; splice the resulting `<symbol>`
block into `site/static/index.html` between `<!-- equation-art:start -->` and
`<!-- equation-art:end -->`. That splice-between-markers pattern is not new here: `just mip-graph`
already regenerates a block inside `docs/MIPs/README.md` between markers, and `quality-other` fails
when it is stale (`AGENTS.md`, `scripts/mip_graph.py`). This follows it exactly:

- `just equation-art` regenerates the block.
- `python3 scripts/equation_art.py --check` fails if the committed block does not match what
  `site/equations.json` produces, added to `quality-other` beside the other six self-tests.
- `python3 scripts/equation_art.py --self-test` covers the sampler (a circle's path closes, a known
  trochoid's crest lands where the closed form says), the `--self-test` convention `quality-other`
  already enforces on every other `scripts/*.py`.

**No CI change and no build step.** The generated markup is committed inside `index.html`, which is
already on `site.yml`'s publish allowlist (`site.yml:162-170`: `index.html app.js style.css chat.js
chatbot-config.js CNAME` plus `vendor/ data/ smoke/ coverage/`, `exit 1` on anything else). Nothing
new lands in `site/dist`, so the allowlist is untouched: the same reason MIP-0046 §4.1 picked an
inline sprite over a separate `icons.svg`. The generator runs on a human's machine, like
`just mip-graph`, and `--check` is what keeps it honest.

### 5.5 What this does not touch

`WAVE_PATH` (`app.js:172`) and the legend key it is asserted equal to (`site_check.js:213-216`) stay
exactly as they are. MIP-0009 tuned that shape for legibility at 24 px over map tiles across eighty
overlapping markers, and MIP-0046 §5.2 already deliberately leaves the markers on inline paths. A
sampled trochoid is a *worse* 24 px marker than a hand-tuned filled path, and pretending otherwise
would trade a working glyph for a principle. MIP-0046's twelve 14 px functional marks are likewise
out of scope: at that size a hand-drawn stroke beats a sampled curve, and this MIP is about
illustration, not pictograms. The two sets share one sprite block and one CSS class family, and
nothing else.

### 5.6 Placement, v1

Two placements only, both on the existing page: the swell band under the header, and one motif in
the map's empty/loading state. MIP-0044's `/about/`, `/news/` and `/dev/` sections are the natural
home for the rest, a section header per motif, and that is where the remaining three go once it
lands. Shipping five illustrations onto a one-page map would be exactly the "decorated" look
MIP-0046 is removing.

## 6. Scoring / safety impact

None. No board field, no threshold, no note text, no `Swimability` call, no LLM. One caveat that is
about safety even though the code is not: `art-rip` (§5.1.5) draws a hazard. It must never sit next
to a beach's own numbers where it could read as "the rip is here": it is a motif for a knowledge
or about section, never a map overlay. Stated here so a later PR cannot quietly promote it.

## 7. Verification plan

- `python3 scripts/equation_art.py --self-test` and `--check`, both wired into `quality-other`.
- `node --check site/static/app.js`; `node scripts/site_check.js` green, plus one new assertion:
  every `art-*` id referenced by a `<use>` resolves to a `<symbol id>` present in `index.html` (the
  generalized form of the assertion MIP-0046 §7 adds for `i-*`).
- `ruff check .` / `ruff format --check .` on the new script.
- `just site-build floripa && just site-serve`, then **screenshots at 390 px and 1280 px**: the
  `site-frontend` step-6 rule and the only check that can see whether a motif is any good.
- Reproducibility check, which is the whole claim: `just equation-art` twice from a clean tree
  produces a byte-identical `index.html`.
- Done = the harness green, `--check` clean, two screenshots in the PR, and no Desmos-exported file
  anywhere in the tree (`git ls-files | grep -i desmos` empty).

## 8. Risks, limitations, and honest caveats

- **Taste is not a gate.** Everything in §7 verifies that the drawing is *reproducible*, not that it
  is *good*. Five motifs is an ambitious v1; the fallback is to ship `art-swell` alone and let the
  rest wait for a second look.
- **Nothing here is rendered yet.** Every claim about how these look is *written, not run*. The
  mechanism is verified against MDN via MIP-0046 §4.1 and against this repo's own workflow file; no
  curve has been sampled and no browser opened.
- **The philosophy claim has a ceiling, and here it is.** It is true that an equation-drawn asset is
  deterministic and reproducible, and true that its source is inspectable in a way a raster is not.
  It is **not** true that this makes it "sourced, never invented" in the sense `PHILOSOPHY.md` and
  the `mip` skill use those words. That rule is about *facts shown to a user*, a wave height, a
  water-quality verdict, a line of lore, and an illustration asserts no fact. Stretching Pillar 2
  ("the deterministic parts kept deterministic", which is about the scoring path) to cover a
  decorative whale would be the kind of inflation `PHILOSOPHY.md`'s own "What this is not" section
  exists to prevent. §11's proposed paragraph therefore claims the narrow thing: *the source of a
  picture can be a formula, and then review is a diff*, and not the wide one.
- **Authoring is manual and unattributed.** Desmos is a sketchpad, so the record of *how* a curve
  was tuned is the parameters in `site/equations.json` and nothing else; there is no saved graph the
  repo can point at unless the author chooses to publish one (and publishing it makes it a User
  Submission under §4.1's terms, which is fine, but it is a choice, not a requirement).
- **`art-rip` can mislead**, §6.
- **Sampling artefacts.** 240 points on a trochoid is smooth at 240 px and visibly polygonal at
  1200 px. The `samples` figure is per-motif and per-placement, and the swell band is the one most
  likely to be scaled up by MIP-0044.
- **One file, two generators.** After MIP-0046, `index.html` holds a hand-written sprite block; after
  this, a generated one too, inside the same `<svg class="sprite">`. Whoever edits by hand inside
  the `equation-art` markers will have it silently overwritten: the `--check` gate catches it in
  `quality-other`, before push, which is why it is a gate rather than a comment.

## 9. Alternatives considered

**9.1 Embed the live Desmos calculator (`calculator.js`) in the page: rejected, firmly.** It is a
third-party runtime script on a page whose CSP is `default-src 'self'` with no `script-src` opening
and whose own source comment says "script-src/object-src stay locked to same-origin: this page loads
no third-party script" (`index.html:13-14`, read 2026-09-07). It would need an API key in the page
(§4.2: 403 without one), it would put a personal-non-commercial Trial Tier term on a public site, it
forbids obscuring Desmos branding, and it would make every visitor download a calculator engine to
look at a picture, against a vendored Leaflet of 147,552 bytes for the entire map. MIP-0044 §4
found the generated scaladoc pages already breaking the "no third-party script" claim and treats
that as a bug to fix; adding a second violation on purpose, in the same session, would be
incoherent.

**9.2 Vendor Desmos's own SVG export (`asyncScreenshot`, or the UI's Download SVG): rejected.**
It works and it is free (§4.1, §4.2), but distributing that output is licensed to the author under
CC-BY-SA-4.0 with a Desmos attribution, inside an MIT repo. A share-alike asset with a permanent
NOTICE obligation, to avoid writing 200 lines of Python, is a bad trade. The programmatic path
additionally needs a headless browser and an API key.

**9.3 Adapt the equations from an existing public graph (e.g. `gaeav8oi2q`'s whale): rejected.**
Desmos's ToS §6 gives ownership of a submission to the user who made it, and grants no user-to-user
licence; public means visible, not reusable. The graphs in §4.3 are cited as prior art and as
evidence that the technique works. The trochoid parametrization is taken as mathematics, not as
anyone's artwork.

**9.4 Hand-draw the motifs in a vector editor: the real alternative, rejected on review surface.**
It is faster and probably prettier. It gives up the property this MIP is actually for: a change is a
number in a diff, and the artwork is reproducible from the repo. Note this is *not* a rejection of
hand-drawing in general: MIP-0046's twelve 14 px marks stay hand-drawn, and §5.5 says why.

**9.5 An AI-generated illustration: rejected.** It is the fastest option and it is the one thing
this repo's entire culture argues against for text: an artefact nobody can check, whose provenance
is a prompt, which cannot be reproduced or reviewed. The site-frontend skill exists because a page
"looks AI-generated" is already treated here as a defect.

**9.6 Do nothing: a serious option.** The page is disciplined and MIP-0046 makes it more so; a site
with no illustrations is not broken. Rejected because the request is explicit, and because §5.6's
v1 is two placements, which is close enough to "nearly nothing" that the do-nothing case is mostly
answered by scope.

## 11. Open questions

1. **Should the swell be data-driven?** The board carries `sea.period_s` and `wave_m` per beach, and
   deep water gives `L = gT²/2π`, so the header band *could* be drawn from today's actual swell.
   That is a genuinely good idea and a different MIP: it turns an illustration into a data display,
   which brings the honesty rules with it (what is drawn when there is no data?). v1 is static.
   Proposal: static now, revisit after MIP-0042's live-map work has an opinion.
2. **Five motifs or one?** §8's fallback. A human should look at the first one before the other four
   are drawn.
3. **Where does `art-rip` live**: a knowledge/safety page (MIP-0044 §5.5) or nowhere in v1?
   Proposal: nowhere in v1, per §6.
4. **`PHILOSOPHY.md`: the call, made.** Yes, this earns a paragraph, and no, it does not go in this
   change. The connection that is real is the one `PHILOSOPHY.md` already makes about everything
   else, *the gate is there by construction, not by discipline*, applied to pictures: an
   equation-drawn asset carries its own source, so reviewing it is reading a diff and reproducing it
   is running a script, whereas a raster (drawn, photographed or generated) can only be looked at
   and believed. The connection that is **not** real is the Pillar 2 / "sourced, never invented"
   one, for the reason in §8. Proposed wording, for a separate PR after this MIP is accepted, as a
   short section after "Why a `justfile`":

   > **Why the pictures are equations.** The site's drawings are generated from formulae checked
   > into the repo, not from a raster someone drew or a model produced. It is the same move as
   > everywhere else here: put the artefact somewhere it can be checked. A wave whose source is
   > `x(t) = (L/2π)t − h sin t` is reviewed by reading a diff and reproduced by running a script;
   > an image file is reviewed by looking at it and taking someone's word for where it came from.
   > This claims nothing about the *facts* marola shows — a drawing asserts none — only about where
   > the artwork's source lives.

   It is the author's voice and the author's file, so it needs his go-ahead; a separate PR keeps
   that decision separable from the mechanism, exactly as the `mip` skill's step 8 keeps design
   separable from implementation.
5. **Reciprocal cross-references.** This MIP's "see also" line landed in `MIP-0009` on this branch.
   MIP-0046 is still unmerged on `docs/mip-0046-remove-ai-slop-ui`, so its pointer back to this MIP
   has to be added on that branch (or after it merges), noted rather than done, to avoid editing
   another draft's branch.

## Appendix

### Checked live (all 2026-09-07)

- `https://www.desmos.com/calculator/rqvqvf6uy8` → HTTP 200, `<title>` "Troichoidal Ocean Waves |
  Desmos"; body is a JS shell. State via
  `https://saved-work.desmos.com/calc-states/production/rqvqvf6uy8` → 200, JSON containing
  `p_x(t)=\frac{L_w}{2\pi}t-h_w\sin t`, `p_y(t)=h_w\cos t`, plus the rolling-circle construction.
  Thumbnail `https://www.desmos.com/calc_thumbs/production/rqvqvf6uy8.png` → 200, 20,680 bytes,
  viewed: a circle with a generating point and a traced curve. Basis for §4.3, §5.1.1.
- `https://www.desmos.com/calculator/koa2npwoir` ("The Ocean") → thumbnail 200, 20,206 bytes,
  viewed: layered blue wave bands, a boat with a flag, a jellyfish, a green seabed. State 200,
  expressions include `4\ge(x-4)^2+(y-2+v)^2\{y\le2-v\}` and an animation parameter `v=-0.025`.
- `https://www.desmos.com/calculator/gaeav8oi2q` ("Whale") → thumbnail 200, 12,399 bytes, viewed:
  a whale silhouette below a `y=\sin(.5x)` sea line, a sun, an octopus. State 200, ~40
  domain-restricted conics/sinusoids.
- `https://www.desmos.com/calculator/a83ylfdj0s` ("Jellyfish") → thumbnail 200, 18,928 bytes,
  viewed: a filled bell at left with oscillating strands to the right. State 200, tentacles are
  `\frac{\sin(3x)}{2}\{-2<x<8.5\}`, `\cos(3x)\{-2<x<8.75\}`, `\|\sin(x+1)\|+1`, etc. Basis for
  §5.1.3's "damped sinusoids, not polar roses".
- `https://www.desmos.com/calculator/fhfmlynqtn` ("ocean-waves") → 200; thumbnail viewed: a
  low-amplitude periodic ripple on the axis, `y=\exp(\sin(x))`-family. Real but trivial; not cited
  as prior art in §4.3 because it draws a curve, not a scene.
- `https://www.desmos.com/calculator/2yttmwvhzv` ("Jellyfish Graph") → thumbnail 200, 3,731 bytes,
  viewed: **a short line of red dots, no jellyfish.** Negative result, recorded in §4.3.
- `https://www.desmos.com/calculator/omhbfyzodp` ("Trig project- whale") → thumbnail 200, 12,035
  bytes, viewed: **an empty grid.** Negative result.
- `https://www.desmos.com/api/v1.11/docs/index.html` → 200, 130,163 bytes. Quotes in §4.2 (the
  `?apiKey=` load URL, `asyncScreenshot`'s SVG output and `opts.format`) are verbatim from it.
- `https://www.desmos.com/api/v1.11/calculator.js` (no key) → **HTTP 403**, 52-byte body.
- `https://www.desmos.com/terms` → 200 but a JS shell; the terms text lives in
  `/assets/build/frontpage/terms-967a1656797971392b61a5acb62049b98e6ca524.js` (200, 760,940 bytes),
  from which §4.1's §5 and §6 quotes are taken verbatim.
- `https://www.desmos.com/api-terms` → 200, JS shell; text from
  `/assets/build/frontpage/api-terms-325f708586a27e144921977549c8a0b30512f7b6.js` (200, 754,732
  bytes). Trial-Tier and §5.b quotes in §4.2 are verbatim.
- `https://www.desmos.com/assets/build/shared_calculator_desktop-bc7c26f4a69c5a57ad227a86cf55617a542da234.js`
  → 200, 5,210,660 bytes; contains `account-shell-button-download-png = Download PNG`,
  `account-shell-button-download-svg = Download SVG`, `account-shell-heading-file-format = File
  Format`. Basis for §4.1's "the free UI exports SVG".
- `https://tidesandcurrents.noaa.gov/about_harmonic_constituents.html` → 200; M2 "the largest lunar
  constituent", "2 peaks every 24-hours and 50 minutes"; S2 "the largest solar constituent", "2
  peaks every 24-hours". Basis for §5.1.4's 12.42 h / 12.00 h.
- `https://help.desmos.com/hc/en-us/articles/4405901719309` (Export Image help article) → **HTTP
  403**, Cloudflare interstitial ("Enable JavaScript and cookies to continue"). Not readable; the
  export-format claim in §4.1 rests on the app bundle instead.
- `https://tidesandcurrents.noaa.gov/harmonic_constituents_defs.html` → **HTTP 404**.

### Checked in this repo (2026-09-07, `origin/main` at `07882ae`)

- `LICENSE` → "MIT License / Copyright (c) 2026 Matheus Hoffmann". Basis for §4.1's licence clash.
- `site/static/index.html:13-14` → the CSP comment "this page loads no third-party script" and
  `default-src 'self'; img-src 'self' https:; connect-src 'self' https:; object-src 'none'`.
  `index.html:21` → the `<h1>` two-path wave. `index.html:38` → the legend wave key.
- `site/static/app.js:172` → `WAVE_PATH = 'M3 10c2.6-7 6.4-7 9 0s6.4 5 9 0v10H3z'`; §5.5 leaves it.
- `.github/workflows/site.yml:162-170` → the publish allowlist and its `exit 1`. Nothing in §5.4
  adds a file to `site/dist`.
- `justfile:75-90` → `quality-other` runs `ruff check .`, `ruff format --check .` and six
  `python3 scripts/*.py --self-test` invocations. Basis for §4.4 and §5.4.
- `site/static/vendor/leaflet.js` → 147,552 bytes. The comparison in §9.1.
- `grep -rn 'AI-generated|generated image|stock photo|illustration' AGENTS.md PHILOSOPHY.md docs/*.md
  .claude/skills/site-frontend/SKILL.md` → one hit, the skill's own `description:` line. Basis for
  §2's "no policy about pictures exists".
- `knowledge/` → `whales-santa-catarina.md`, `jellyfish-and-man-o-war.md`,
  `waves-tides-glossary.md`, `safety/rip-currents.md`. The domain §5.1's motifs draw from.
- MIP-0046 read from the unmerged branch `origin/docs/mip-0046-remove-ai-slop-ui` (commit
  `2d4114f`); MIP-0044 from `origin/docs/mip-0044-site-sections` (commit `40e9e8a`).

### Not checked

- **No browser was opened and no curve was sampled.** Every visual claim in §3 and §5.1 is written,
  not run: including whether a 240-point trochoid path looks smooth, and whether a superellipse
  with `n = 2.6` reads as a right whale rather than a blob.
- Whether Desmos's own SVG export is true vector paths or an embedded raster: not verified, no file
  was produced. §9.2 rejects that path on licence, which does not depend on the answer.
- Whether graphing on desmos.com without an account is possible (as opposed to *saving*): assumed
  from the calculator page loading anonymously, not confirmed against a Desmos statement: the help
  centre is behind Cloudflare (above).
- `saved-work.desmos.com/calc-states/production/<id>` is **undocumented**; it returned the states
  quoted above today, and may change or disappear without notice. Nothing in §5 depends on it.
- The five graphs' authors were not contacted, and their expressions are not used (§9.3).
- MIP-0046's and MIP-0044's statuses are read from unmerged branches; if either draft changes before
  merging, this MIP's Depends-on and §5.4 marker placement may need revisiting.
