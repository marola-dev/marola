# MIP-0049: Scala for the web layer — Tyrian, Laminar, ScalaTags, or better plain-JS discipline

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude (Opus 5), for M. Hoffmann (request: "consider adoption any scala framework for helping building this webpage, possible candidates like tyrian, purplegames, scalajs, or just a better syntax to deal with web stuff") |
| **Created** | 2026-09-08 |
| **Phase** | 0 — the static site is a Phase 0 surface; nothing here needs Phase 1 or an Azure resource |
| **Related** | MIP-0005 (the site, and its "no templating engine, no build step" constraint), MIP-0042 §4.4 (the Scala.js cross-build research this MIP builds on rather than repeats), MIP-0044 (the page generator — §5.1's per-section HTML files are the concrete consumer of any server-side answer here), MIP-0009 (`scripts/site_check.js`, the no-browser harness a client framework would invalidate), MIP-0046 (the icon work in flight in the same files) |
| **Effort** | Per option, not one number: S for §5.1 (a JVM HTML DSL behind the existing generator), L for §5.2 (a Scala.js view layer replacing `app.js` — a JS build target, a bundle, and a replacement for the harness), XL for §5.3 (both, plus MIP-0042's scorer cross-build) |
| **Gain** | `infra/dev-loop` — one language for the page and the pipeline, and types across the board/JSON boundary that is currently string-matched twice; `user value` only indirectly, and §6 argues it is close to zero today |
| **Effort vs Gain** | `cheap win` for §5.1 (server-side HTML, no visitor cost, no build step for readers); `do when MIP-0042's cross-build proves out` for §5.2 — it shares the same unresolved `java.time` question and should not be paid for twice; **reject for now** for §5.3 and for any game engine |
| **Depends on** | Nothing blocks §5.1. §5.2 should follow MIP-0042's Scala.js spike rather than run a second one — that MIP already rates the cross-build `do when it proves out`. Coordinate with MIP-0044 (owns the generator §5.1 would sit behind) and MIP-0046 (in flight in `site/static/`) |
| **Blocked by** | none |
| **Risk** | Replacing `app.js` deletes `scripts/site_check.js`'s reason to exist. That harness runs the real page against a real board in a stub DOM with no browser and no dependencies, and it is a `just quality-other` gate that has caught actual regressions. A Scala.js bundle cannot be checked that way, and "we will add browser tests later" is how a project loses its only page test |
| **Cost so far** | — |

## 1. Summary

The map page is 32 KB of hand-written ES5-style JavaScript plus 13 KB of CSS. The question is
whether Scala should own it. This splits that into two decisions that are usually conflated: HTML
*generated at build time* (where Scala is cheap and the visitor pays nothing) and the *interactive
client* (where Scala means Scala.js, a bundle, and losing the current test harness). The
recommendation is to take the first and defer the second behind MIP-0042's cross-build spike.

## 2. Motivation

Three real frictions, none of them "JavaScript is bad":

- **The board contract is string-matched on both sides.** `Board.scala` writes JSON field names as
  string literals and `app.js` reads them as string literals. `site/board.schema.json` plus
  `BoardSpec` and `site_check.js` exist to catch the drift a shared type would make impossible.
- **HTML is built by string concatenation in two languages.** `scripts/build_docs_index.py`
  concatenates HTML in Python; `app.js` concatenates it in JavaScript with a hand-rolled `esc()`.
  MIP-0044 §5.1 adds a third generator. Escaping is a per-site-author responsibility today.
- **`app.js` is deliberately old-fashioned** (`var`, no modules, no build step) because MIP-0005
  forbade a build step. That constraint bought real things: no toolchain, instant deploys, a page
  that works with a text editor, and it is worth keeping unless something pays for its removal.

What is *not* a motivation: the page being slow or broken. It is 32 KB, has no framework, and
renders on a phone. Any change here is for the developer, and §6 says so plainly.

## 3. User-visible change

For §5.1: **none.** Identical HTML, generated from typed Scala instead of string concatenation.
That is the point: a refactor a visitor cannot detect.

For §5.2, the honest before/after is a payload table, not a screenshot:

```
today      app.js 31,830 B + style.css 13,066 B + leaflet.js 147,553 B   (no build step)
Scala.js   bundle instead of app.js, fullOptJS, +sbt-scalajs, + a bundle step in site.yml
```

The bundle size is the number that decides §5.2 and it is **not measured here**; see §11.

## 4. Data sources and dependencies reviewed

Versions resolved live from Maven Central via `cs complete-dep`, 2026-09-08.

**4.1 ScalaTags: `com.lihaoyi:scalatags_3:0.13.1`. Stable. The pick for §5.1.**
A JVM-side HTML DSL: `html(body(h1("marola")))` produces a string. No browser runtime, no Scala.js,
no build step for the visitor, no bundle. Escaping is the library's job rather than a hand-rolled
`esc()`. It runs where `SiteBuilder` already runs. This is the only candidate that costs a reader
nothing.

**4.2 Tyrian: `io.indigoengine:tyrian_3:0.30.0-M6`. A milestone release.**
An Elm-architecture (model/update/view) framework for Scala.js by PurpleKingdomGames. The
architecture is a genuinely good fit for this page: `app.js`'s `state` object plus `render()` is
already a hand-rolled TEA loop. **But 0.30.0-M6 is a milestone, not a stable release.** marola
already carries one pre-1.0 dependency (Kyo 1.0.0-RC5) and `.claude/rules/scala.md` documents the
cost: verifying API against the jar because published docs drift. A second pre-1.0 dependency, in
the layer a visitor actually loads, is a different risk from one in the pipeline.

**4.3 Laminar: `com.raquo:laminar_sjs1_3:18.0.0-M5`. Also a milestone.**
Reactive-signal DOM library, no virtual DOM, widely used. Same Scala.js prerequisites as Tyrian and
the same pre-1.0 caveat at the version resolved. Would suit the map page's fine-grained updates
(one hour slider changing 80 markers) better than a full re-render.

**4.4 Indigo: `io.indigoengine:indigo_sjs1_3:0.30.0-M6`. Resolves; wrong tool.**
This is "purplegames": PurpleKingdomGames publish both Tyrian and Indigo. Indigo is a *game engine*:
a game loop, a scene graph, WebGL. marola's page is a Leaflet map with panels; a game engine
would replace Leaflet, not help it. Rejected on purpose, not availability.

**4.5 Scala.js itself: 1.22.0.** Not a framework, the substrate all of §4.2–§4.4 need.
**Deliberately not re-researched here**: MIP-0042 §4.4 already establishes the current version, the
`sbt-scalajs` 1.22.0 / `sbt-scalajs-crossproject` 1.3.2 plugin pair, and the one open unknown: a
`java.time` shim. That unknown is shared, and settling it twice would be waste.

**4.6 The status quo.** `app.js` is 31,830 B, dependency-free, and tested by `scripts/site_check.js`
in a stub DOM with no browser and no `node_modules`. Any client-side option must state what replaces
that harness. None of §4.2–§4.4 can be tested that way.

## 5. Design

### 5.1 Server-side HTML in Scala (the cheap win)
Put ScalaTags behind the generators that already exist rather than adding a new surface:
MIP-0044 §5.1's page generator emits typed HTML instead of concatenated strings, and
`SiteBuilder.copyStatic` is unchanged. `scripts/build_docs_index.py` stays Python for now; moving
it is a separate call, and the Python side has its own `--self-test`.

No visitor-facing change, no build step, no bundle, one stable dependency in `cli/` only:
`core/` and `local/` stay as they are.

### 5.2 A Scala.js view layer (deferred, not rejected)
If MIP-0042's cross-build lands, the scorer is already compiling to JS and the marginal cost of a
Scala.js *view* drops sharply. That is the moment to reconsider, and the reason this is `do when`
rather than `park`. The shape would be Laminar or Tyrian owning the panels while Leaflet keeps the
map, with the board decoded into the same case classes `Board.scala` writes.

Prerequisites, all of which must be true first: MIP-0042's `java.time` question settled; a measured
bundle size (§11); and a replacement for `site_check.js` that runs in CI without a browser.

### 5.3 Both, now
Rejected. It is the union of two open unknowns and it would land in `site/static/` while MIP-0046
is editing the same files.

## 6. Scoring / safety impact

**None, in every option.** `Swimability` stays where it is. This matters more than it sounds:
MIP-0042 §4.4's whole argument for a Scala.js cross-build is that the scorer must not be hand-ported
to JavaScript, because a second copy of safety-relevant logic is a second thing to get wrong. This
MIP inherits that rule and adds nothing to it: the view layer displays a score, it never computes
one.

## 7. Verification plan

For §5.1:
- `build_docs_index`-equivalent tests on the Scala side: generated HTML contains the expected
  links, and a value containing `<script>` is escaped, a property a hand-rolled `esc()` only
  has by inspection.
- Byte-comparison of the generated page before and after the ScalaTags rewrite, so "no
  user-visible change" is checked rather than asserted.
- `just build && just test && just quality` green.

For §5.2, before any commitment:
- A spike measuring `fullOptJS` bundle size for a page that renders one board: the number §3
  leaves open, against today's 31,830 B.
- A demonstrated CI-runnable page test with no browser, or an explicit decision to accept a
  browser in CI.

**Done** for this MIP = §5.1 merged and the §5.2 numbers recorded, not a framework adopted.

## 8. Risks, limitations, and honest caveats

- **Losing the harness** (the Risk field). It is the concrete cost and it is easy to under-weigh.
- **Two pre-1.0 dependencies.** Kyo is already one; Tyrian and Laminar both resolve to milestones.
- **A build step is a deploy risk.** Today `site/dist` is copyable files; site.yml has no bundler.
  Every added step is a way the 3-hourly build can fail, and this week alone the site quietly
  shipped a stale docs tree and two dead water providers, all through steps that failed silently.
- **This is developer ergonomics, not user value**, and should be argued as such rather than
  dressed up.

## 9. Alternatives considered

- **Do nothing.** Entirely defensible: the page works, is small and is tested. This MIP exists
  because the question was asked, and "no" is a legitimate outcome for §5.2.
- **Modernise the JavaScript instead**: `const`/`let`, ES modules, JSDoc types checked by `tsc`.
  Gets much of the type safety with no build step for the visitor and keeps `site_check.js`
  working. **The strongest alternative to §5.2** and the one to compare against when the time comes.
- **TypeScript.** Real types, but adds a build step and a second language toolchain: the cost of
  Scala.js without the one-language benefit that is the entire point of asking.
- **A game engine (Indigo).** §4.4.

## 10. Exam-coverage mapping

None. This is repo ergonomics, not an AI-103/AI-500 domain.

## 11. Open questions

- **What does a minimal Scala.js page actually weigh here?** Everything in §5.2 turns on it and it
  is unmeasured. A half-day spike answers it.
- **Does `site_check.js` have a successor, or does §5.2 mean accepting a browser in CI?** A human
  call about what "tested" means for this page.
- Should `scripts/build_docs_index.py` move to Scala with §5.1, or stay Python? Two generators in
  two languages is the status quo either way until MIP-0044 §5.1 lands.
- **Follow-up MIP:** `app.js` reads board fields as string literals while `Board.scala` writes them
  as string literals, with a JSON schema and two test harnesses guarding the gap. A generated
  shared contract would remove the class of bug without any framework, worth its own number,
  and it is the cheapest real win identified while writing this.

## Appendix

### Checked live
All 2026-09-08, `cs complete-dep` against Maven Central:

- `com.lihaoyi:scalatags_3` → latest **0.13.1** (stable).
- `io.indigoengine:tyrian_3` → latest **0.30.0-M6** (milestone).
- `com.raquo:laminar_sjs1_3` → latest **18.0.0-M5** (milestone).
- `io.indigoengine:indigo_sjs1_3` → latest **0.30.0-M6** (resolves; a game engine).
- Current payload, measured on disk: `site/static/app.js` **31,830 B**, `style.css` **13,066 B**,
  `vendor/leaflet.js` **147,553 B**.

### Not checked
- **No bundle size was measured for any Scala.js option**: the single number §5.2 depends on.
  Nothing in this MIP should be read as a claim about it.
- Scala.js 1.22.0 and the plugin versions are taken from MIP-0042 §4.4, not re-verified here.
- No Tyrian, Laminar or ScalaTags code was written or compiled against this repo. Their fitness is
  argued from their documented architectures and from `app.js`'s current shape, not from a spike.
- Whether Tyrian/Laminar milestone releases are API-stable in practice was not investigated; the
  caveat is drawn from the version numbers alone.
