# MIP-0019: arXiv/trending-repo survey — sea-forecasting and jellyfish-prediction techniques for marola

| | |
|---|---|
| **Status** | Draft |
| **Author** | Claude Sonnet 5, for M. Hoffmann (request of 2026-09-06: build an arXiv digest script, cache the results, and turn real findings + trending repos into a MIP) |
| **Created** | 2026-09-06 |
| **Phase** | 0 — research/design only; nothing here touches the running product. Every design in §5 below is Phase-1-or-later work: `AGENTS.md`'s phase-discipline rule says do not start Phase 2 provisioning before Phase 1 (the Telegram bot) is done, and even Phase-1-compatible items here (a scoring-heuristic change) are proposals to accept later, not to build now |
| **Related** | `docs/ARCHITECTURE.md` §5 (the local/Azure pluggable-integration pattern any new data source must follow), `docs/ARCHITECTURE.md`'s jellyfish/whale heuristic (what §5.2 below would replace or augment), `MIP-0017` (agentic-tooling survey — the dev-tooling counterpart of this MIP; that one surveyed how marola is *built*, this one surveys what marola's *forecasting/scoring logic* could learn from), `scripts/arxiv_digest.py` (the tool this MIP's findings came from) |
| **Effort** | S for §5.1 (a citations/notes doc, no code); M for §5.2 (a new heuristic input needs a data source review per `docs/ARCHITECTURE.md` §5 and new tests); L for §5.3 (a new pluggable integration — CMEMS/Copernicus Marine or a self-hosted ocean-forecast model — is a new trait + local/Azure-shaped backend, not a small change) |
| **Gain** | user value (better swim-safety and jellyfish-risk accuracy is the actual product); exam coverage (a new pluggable data source maps to AI-103's "integrate external data" domain the same way Open-Meteo/Overpass already do) |
| **Effort vs Gain** | §5.1 is a cheap win — do next, it's free and improves the honesty of `docs/ARCHITECTURE.md`'s existing heuristic. §5.2 is do-when-X-lands: worth a real MIP with real data-source verification (this MIP is not that — see §4's caveats), timed for whenever jellyfish-risk accuracy becomes a stated priority. §5.3 is park — worth revisiting once Phase 1 (Telegram bot) ships and there is real usage data showing marola's current jellyfish/SST heuristics are the accuracy bottleneck, not before |
| **Depends on** | Phase 1 (Telegram bot) shipping first for §5.2/§5.3 — this MIP itself has no dependency, it is pure research output |
| **Risk** | arXiv/GitHub search precision is genuinely poor for this domain — "ocean" and "jellyfish" collide with unrelated fields (galaxy astronomy, personality psychology) constantly; this MIP is explicit about which findings are real signal vs. noise in the raw digest, but a future implementer must re-verify any paper/repo cited here before building against it, not trust this MIP's summary as ground truth |
| **Cost so far** | — (nothing from this MIP has merged yet) |

## 1. Summary

`scripts/arxiv_digest.py` (new, this MIP's companion change) queried the live arXiv API across
seven oceanography/forecasting/jellyfish-prediction search terms and cached 40 papers. Filtering
out the (substantial) cross-domain noise, a handful of genuinely relevant results — a marine
stinger beaching predictor, a harmful-algal-bloom predictor, several sea-surface-temperature and
regional ocean-forecasting systems, and two LLM-for-forecast-report-generation papers — map
concretely onto marola's own jellyfish heuristic and LLM-summary pipeline. A parallel GitHub
search found a small number of real, moderately-used open-source ocean-forecasting and
harmful-algal-bloom-detection projects. None of this is "build it now" — Phase 1 isn't done — but
§5 below turns the strongest findings into three concretely scoped, honestly-risk-flagged future
proposals.

## 2. Motivation

`docs/ARCHITECTURE.md` describes marola's jellyfish/whale heuristic and sea-condition scoring as
built from live Open-Meteo data plus a hand-written heuristic (see `Swimability`'s scoring, per
`AGENTS.md`'s code-style section on keeping that logic plain, testable Scala). That heuristic was
never explicitly checked against the actual marine-biology/oceanography literature or against
what other open-source projects already do for the same problem (jellyfish/stinger risk
prediction, sea-surface-temperature forecasting). This MIP does that check, once, honestly, and
records what's actually out there — separating real transferable technique from noise — so a
future MIP that *does* propose changing the heuristic starts from evidence instead of a blank
page.

## 3. User-visible change

None from this MIP itself — it is research output (a script + this document). §5's proposals, if
later accepted and built, would eventually change what a user sees (jellyfish-risk confidence, an
SST-forecast note, a richer LLM summary) — that user-visible shape is sketched in each subsection
but is explicitly a future MIP's job to finalize, not this one's.

## 4. Data sources and dependencies reviewed

### 4.1 arXiv (`export.arxiv.org/api/query`, live, verified 2026-09-06)

Seven queries (see `scripts/arxiv_digest.py`'s `QUERIES` constant), all confirmed against the
real API to return results before being finalized — `abs:jellyfish AND abs:forecast` returned
zero hits and was dropped in favor of `abs:jellyfish AND abs:prediction`, which does return real
marine-biology results alongside astronomy noise ("jellyfish galaxies" is an established term in
ram-pressure-stripping literature, unrelated to the animal). 40 papers cached
(`.tmp/arxiv_cache/`, gitignored — re-run `scripts/arxiv_digest.py` to reproduce). Of those, the
genuinely on-topic, highest-signal results (relevance-scored by how many distinct queries matched
each paper — see the script's `relevance_score`):

- **"A Machine Learning Framework for Handling Unreliable Absence Label and Class Imbalance for
  Marine Stinger Beaching Prediction"** (arXiv 2501.11293) — directly analogous to marola's
  jellyfish-risk problem: predicting when a stinging marine organism will beach, from noisy/absent
  negative labels. The class-imbalance/unreliable-absence-label framing is exactly the shape of
  problem marola's own heuristic faces (jellyfish sightings are sparse and absence-of-report ≠
  absence-of-risk).
- **"Predicting Pseudo-nitzschia harmful algal blooms along the Portuguese Coast using
  satellite-derived predictors"** (found via the marine-heatwave/ao-ph-ml queries) — a real,
  regionally-specific harmful-bloom predictor built on satellite data, the same class of live data
  Open-Meteo/Overpass already give marola.
- **"Extending SST Anomaly Forecasts Through Simultaneous Decomposition of Seasonal and PDO
  Modes"** (2601.01864) and **"Deep Learning-Based Statistical Downscaling of Sea Surface
  Temperature Using a Residual Corrective Neural Network"** (2608.10022, this digest's
  highest-relevance-scored result) — two different SST-forecasting techniques, neither assessed
  here for whether they'd beat Open-Meteo's own SST field for marola's purposes (not checked).
- **"Marine Heatwaves in the Arabian Sea: Drivers and Impacts"** (2603.18319) and **"OceanCBM: A
  Concept Bottleneck Model for Mechanistic Interpretability in Ocean Forecasting"** (2605.12639) —
  the latter is notable for the same reason `AGENTS.md`'s code-style section insists on plain,
  interpretable Scala for `Swimability`: a concept-bottleneck approach to ocean forecasting is an
  interpretable-ML analogue of that same design principle, in case marola's heuristic is ever
  replaced with a learned model instead of hand-written rules.
- **"AFDBench: A Reasoning-First AI Scientist for National Weather Service Forecast Discussions"**
  (2608.24954) and **"WeatherSyn: An Instruction Tuning MLLM For Weather Forecasting Report
  Generation"** (2605.07522) — both about using an LLM to turn forecast data into a written
  discussion/report, which is structurally what marola's own LLM-summary-plus-reviewer-pass step
  already does; worth reading before any future change to that prompt, not adopted here.
- **"Borey: A High-Resolution Regional Atmosphere-Ocean-Sea Ice-Wave Forecasting System"**
  (2608.09957) and **"DLESyM-Ocean: A Deep Learning Probabilistic Global Model for Simulating
  Present-Day Upper Ocean and Sea Ice"** (2608.11545) — both are regional/global ocean-forecast
  *systems*, i.e. the kind of thing a future CMEMS-style pluggable integration (§5.3) would sit
  in front of, not something marola would run itself.

**What was not checked**: none of these papers' code (where it exists) was cloned or run; none
of their claimed accuracy numbers were independently verified; several (OceanCBM, DLESyM-Ocean)
may require compute or licensed data far beyond what a keyless-local-first tool like marola can
use. Cited as literature to be aware of, not as verified building blocks.

**Known false positives in the raw digest** (left in the cache, filtered out here, to be honest
about query precision): "OCEAN" as an acronym for the Big-Five personality-trait model matched
`abs:ocean` searches (e.g. "Fine-Tuned Multi-Agent Framework for Detecting OCEAN in Life
Narratives") — nothing to do with the sea. "Jellyfish galaxies" (ram-pressure-stripped galaxies in
astronomy) matched every jellyfish query. A `physics.ao-ph`-tagged exoplanet-atmosphere paper
matched on "ocean" appearing in "magma ocean." A future re-run of this script should expect this
same noise ratio and not assume every cached paper is on-topic without reading it.

### 4.2 GitHub (REST search API, live, verified 2026-09-06)

Searched `ocean forecasting`, `sea surface temperature forecast`, and `harmful algal bloom`
(sorted by stars). Most results are small (0-20 star) student/hackathon projects, not
production-grade tools — named here for completeness, not endorsement:

- **`Ocean-Intelligent-Forecasting/XiHe-GlobalOceanForecasting`** (64★) and
  **`huangqiusheng/FuXi-Ocean`** (22★) — global ocean-forecasting deep-learning models, likely
  research-lab releases (Fuxi is a known Huawei/Fudan weather-model lineage) — heavy, not a fit
  for marola's keyless-local-first constraint without independent verification of their
  compute/data requirements.
- **`deinal/seacast`** (22★, "Regional Ocean Forecasting with Hierarchical Graph Neural
  Networks") and **`iocaswolfteam/LangYa_v1_0`** ("Ocean Large Model v1.0 for Ocean State
  Variables Forecast") — both plausible research-grade regional models; not evaluated for
  license, data dependency, or inference cost here.
- **`WHOIGit/whoi-hab-hub`** (10★, "Harmful Algal Bloom data API and map project") — from Woods
  Hole Oceanographic Institution, a credible source; worth a real look if §5.2 is ever built,
  since a maintained HAB *data API* (rather than a model to run yourself) is a much better fit
  for marola's pattern (consume a live external API, per `docs/ARCHITECTURE.md` §5) than any of
  the DL models above.
- **`drivendataorg/tick-tick-bloom`** (33★, winning solutions to a DrivenData harmful-algal-bloom
  detection competition) — a source of technique, not a runnable service.

**What was not checked**: none of these repos' actual code was read beyond the search result's
own description; star count is not a proxy for correctness or maintenance status, and this MIP
does not claim these are "trending" in the sense of a curated trending page — they're simply the
highest-starred matches for these exact search terms on 2026-09-06.

## 5. Design

Three separately-scoped future proposals, none built by this MIP:

### 5.1 A "further reading" note in `docs/ARCHITECTURE.md` (cheap win, do next)

Add a short subsection to `docs/ARCHITECTURE.md` next to the jellyfish/whale heuristic's
description, linking to this MIP and naming the 2-3 most relevant papers from §4.1 (the marine
stinger beaching predictor and the harmful-algal-bloom predictor) as "literature the current
heuristic hasn't been checked against" — an honesty note, not a design change. Zero code.

### 5.2 Re-evaluate the jellyfish heuristic against §4.1's marine-stinger literature (do when X lands)

A future MIP (not this one) would read the marine-stinger beaching-prediction paper (2501.11293)
in full, check whether its unreliable-absence-label framing suggests a concrete change to how
marola currently treats "no jellyfish sighting reported" (per `docs/ARCHITECTURE.md`'s existing
heuristic), and — if warranted — propose a scoring change to `Swimability`. Per `AGENTS.md`'s code
style, any such change must stay plain, deterministic, testable Scala; an ML model is not on the
table unless a much later MIP argues for one on its own evidence. Not started here.

### 5.3 A pluggable ocean-forecast/HAB-data integration, à la Open-Meteo (park)

If a future MIP determines marola's own SST/jellyfish-risk accuracy is meaningfully limited by
Open-Meteo's own fields, `WHOIGit/whoi-hab-hub`-style live data APIs (real APIs to poll, not
models to host) are the shape that fits `docs/ARCHITECTURE.md` §5's existing pattern — a new
trait with a local/free default (if one exists) and an Azure-opt-in path only if a paid service is
genuinely the only option, per `AGENTS.md`'s cost rule. This is explicitly parked: it needs its
own MIP with real data-source verification (coverage, licence, update cadence, geographic
overlap with Santa Catarina, where marola actually operates) — none of which this survey MIP did.

## 6. Scoring / safety impact

None from this MIP — no code here changes `Swimability` or any user-facing safety text. §5.2, if
ever built, would need its own scoring/safety-impact analysis in that future MIP.

## 7. Verification plan

- `scripts/arxiv_digest.py --self-test` (no network, fixture-based) is green and wired into
  `just quality-other`.
- A live run (`python3 scripts/arxiv_digest.py`) reproduces roughly the same result set as §4.1
  describes — exact papers will drift over time as new arXiv submissions land, since queries are
  sorted by submission date descending; the *shape* of the noise (galaxy jellyfish, OCEAN
  personality model) should reproduce reliably since those are structural query collisions, not
  a one-time artifact.
- No new Scala/unit tests — nothing here touches `core`/`local`/`azure`/`cli`.

## 8. Risks, limitations, and honest caveats

- **Query precision is the core weakness of this whole approach.** "Ocean" and "jellyfish" are
  used across astronomy, psychology, and biology in ways that dominate naive keyword search. A
  future re-run of `scripts/arxiv_digest.py` will keep surfacing this noise; the script does not
  attempt semantic filtering (an LLM-based relevance re-ranker was considered and rejected for
  v1, see §9) — a human (or a future MIP) still has to read the index and judge relevance.
- **Nothing in §4 was independently reproduced.** No cited paper's code was run, no accuracy claim
  was checked against an independent source. This is a literature *pointer*, not a literature
  *review*.
- **GitHub star count is a weak trending signal**, and the search used (repository search API,
  sorted by stars, no time-window filter that reliably returned real "trending this week" data —
  an attempt at a `created:>2026-08-01` filter returned only 0-star throwaway repos, so this MIP
  fell back to all-time star-sorted results and says so plainly rather than presenting them as
  "trending").
- **This MIP proposes nothing that violates phase discipline** — §5.2/§5.3 are explicitly gated on
  Phase 1 shipping and a future MIP's own verification, per `AGENTS.md`.

## 9. Alternatives considered

- **Do nothing** (skip this survey) — loses a real, if imperfect, literature pointer at near-zero
  cost; the counterfactual is marola's jellyfish heuristic staying unchecked against the actual
  field indefinitely. Not chosen, since the script itself is cheap and reusable going forward.
- **An LLM-based relevance re-ranker** on top of the raw arXiv results (ask a model "is this
  paper actually about marine jellyfish, not personality psychology or astronomy") — considered,
  rejected for v1: adds an LLM call (cost, a new failure mode) to what is currently a deterministic,
  offline-testable script; the noise is honestly documented instead. Worth revisiting if the
  digest is run regularly enough that manual triage becomes the bottleneck.
- **Scraping GitHub's actual Trending page** instead of the search API — rejected: GitHub's
  trending page has no public API and scraping it is fragile/against the spirit of using
  documented APIs this repo otherwise follows (arXiv's own API, Open-Meteo, Overpass); the search
  API's star-sort is a documented, honest substitute, clearly labeled as such in §8.

## 10. Exam-coverage mapping

None directly claimed — this MIP is a research survey, not a shipped feature. If §5.3 is ever
built, a new pluggable data-source integration would map to AI-103's external-data-integration
domain the same way Open-Meteo/Overpass currently do (see `docs/AI-103-MAPPING.md`); that mapping
belongs in that future MIP, not claimed here.

## 11. Open questions

- Is `.tmp/arxiv_cache/`'s cache worth re-running on a schedule (e.g. weekly, alongside a future
  MIP-0018 post-planner digest) or is a one-time survey (this MIP) sufficient until someone
  decides to act on §5.2/§5.3? Not decided — no automation is proposed here.
- Should `scripts/arxiv_digest.py`'s query set be tuned further (e.g. add `abs:"beach closure"`,
  `abs:"rip current"` for other marola-relevant hazards beyond jellyfish/SST) before the next
  run, given how much noise the current jellyfish query alone produces? Left for whoever next
  runs it to decide based on what they're specifically looking for.
- Whether `WHOIGit/whoi-hab-hub`'s API (§4.2) is actually usable (rate limits, geographic
  coverage — Woods Hole is a US East Coast institution, marola operates in Santa Catarina, Brazil)
  was not checked. A prerequisite for §5.3 if that specific source is ever pursued.
