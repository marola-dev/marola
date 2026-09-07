package marola.beaches

import kyo.*

import marola.model.Beach

/**
 * `MAROLA_FACILITIES=off` (MIP-0021 §5) — every beach gets `Facilities.NoData`, no network call.
 * Also the shape `Recommender` falls back to when `OverpassAccessibilityClient` fails: facilities
 * never fail a run, the same stance MIP-0001 §5.2 takes for water quality.
 */
final class NoopAccessibilityClient extends AccessibilityClient:
  def near(beaches: List[Beach], radiusM: Int = 300): Map[String, Facilities] < Sync =
    beaches.map(b => b.name -> Facilities.NoData).toMap
