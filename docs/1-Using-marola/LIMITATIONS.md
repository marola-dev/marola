# Limitations

What marola cannot tell you, and where its numbers are rougher than they look. Moved from
[ARCHITECTURE](../2-Building-marola/ARCHITECTURE.md) §8–9 with its wording kept (MIP-0074 §6).

## The jellyfish and whale heuristics — honest limitations

**Jellyfish (safety-relevant, feeds into `score`):** there is no free (or, as far as could be
found, any) public jellyfish-bloom forecast API. `Swimability.jellyfishRisk` scores four commonly
cited ecological correlates instead (warm sea surface temperature, weak wind, calm seas, weak
current) and calls it "High" when at least three line up. This is a heuristic, not a validated
model, and it has a real quirk: three of its four signals are also exactly what makes for
*pleasant* swimming conditions, so a genuinely great, calm day is often also flagged as
jellyfish-elevated (confirmed in `SwimabilitySpec`). Treat the output as "worth a visual check
before wading in," not a guarantee either way.

**Whale sighting likelihood (informational only, never feeds into `score`):**
`Swimability.whaleSightingLikelihood` combines one calendar fact (humpback whales migrate along the
Brazilian coast roughly July-November, austral winter/spring) with two visibility signals from the
same Open-Meteo data: daylight (a hard requirement) and calm-enough wind/seas (rougher thresholds
than swim comfort: you only need to *see* a whale, not swim in those conditions). Same honesty
caveat as jellyfish: a heuristic, not a validated sighting-probability model. Deliberately excluded
from `score`: whether you might see a whale doesn't make an hour more or less safe or pleasant to
swim in.

**How [ARCHITECTURE §5d/§5e](../2-Building-marola/ARCHITECTURE.md#5d-sighting-reports-sightings)
actually close this loop, not just gesture at it:** `SightingStore` (§5d) and
`VisionClient` (§5e) are the concrete mechanism for "let users report sightings back... accumulate
that as real labeled data", not yet wired into either heuristic's thresholds, but the storage and
photo-analysis pieces now exist, which they didn't before this change. Feeding accumulated reports
back into marola-ml's
[`dspy/compile_recommendation_prompt.py`](https://github.com/marola-dev/marola-ml/blob/main/dspy/compile_recommendation_prompt.py)
trainset (LLM phrasing) or retraining the heuristics' thresholds/weights (the bigger lift) remains
future work.

## Other known limitations (POC-stage, not hidden)

- **Beach distance is haversine** ("as the crow flies",
  [ARCHITECTURE §5b](../2-Building-marola/ARCHITECTURE.md#5b-beach-distance-haversine)), confirmed
  on real data: beaches across Guanabara Bay from Arpoador show up within the 15km radius despite
  not being reachable without a boat or a long drive around the bay.
- **A beach's distance is measured to its OSM centroid, not its nearest shoreline.** Large beaches
  are multipolygon relations and Overpass's `out center` gives the polygon's centre, so a 4km-long
  beach you live 200m from can show as "2.1km away" (confirmed: Praia do Campeche). Ranking is
  unaffected in practice (it's the same beach), but the printed distance undersells how close it is.
  Nearest-edge distance would need the full geometry (`out geom`), a much bigger payload.
- **Overpass relation queries are slow**: ~30s observed for a 15km radius on the public instance,
  and it enforces a per-IP slot/rate limit (2 concurrent), so hammering `just run`
  (in a marola-app checkout) back-to-back can return 429s. `BeachFinder` allows 45s server-side /
  60s client-side; caching (Phase 4) is the real fix.
- **Nearby beaches often show near-identical numbers.** Open-Meteo's underlying weather models
  have finite grid resolution, so beaches a few km apart genuinely get the same or near-same
  forecast cell. Real, not a bug.
- **No caching, no persistence for the core pipeline, no rate limiting yet.** Every query re-fetches
  from Overpass and Open-Meteo live. Fine for a personal POC; a public bot needs both before real
  usage (Overpass's fair-use policy,
  [ARCHITECTURE §7](../2-Building-marola/ARCHITECTURE.md#7-third-party-apis-used-all-free-no-key-confirmed-live-against-real-data),
  is the more pressing one). Since then, two partial caches exist: water-quality fetches are kept
  per agency as an outage fallback, and `MAROLA_BEACHES_DIR` can serve beach lists instead of
  Overpass ([configuration](https://docs.marola.dev/5-Repos/marola-app/4-reference_config/#outside-appconfig));
  Open-Meteo is still fetched on every run.
- **No tests for any of the HTTP/JSON integration layer**: only the pure `Swimability` scoring
  logic is unit-tested (`SwimabilitySpec`), consistent with marola-app's "pure logic is where the
  tests are cheap" convention (its `AGENTS.md`'s code style section, which points to
  [`.claude/rules/scala.md`](https://github.com/marola-dev/marola-app/blob/main/.claude/rules/scala.md)). Every integration layer was
  instead verified by actually running it against live services/data; see each subsection of
  [ARCHITECTURE §5](../2-Building-marola/ARCHITECTURE.md#5-the-six-pluggable-integrations) for
  exactly what was and wasn't exercised. Since then, this no longer holds: recorded Overpass,
  Open-Meteo and agency responses replay through the unchanged integration code in
  `PipelineGoldenSpec` and the parser suites
  ([testing](https://docs.marola.dev/5-Repos/marola-app/3-development/#testing)).
- **`CompiledPrompt`'s chat-message replay is a good-faith approximation** of DSPy's own
  `ChatAdapter` formatting, not byte-identical; see
  [ARCHITECTURE §5a](../2-Building-marola/ARCHITECTURE.md#5a-query-synthesis-llm).
