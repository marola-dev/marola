package marola.beaches

import kyo.*

import marola.model.Beach

/**
 * `MAROLA_FACILITIES=off` (MIP-0021 §5) — every beach gets `Facilities.NoData`, no network call.
 */
final class NoopAccessibilityClient extends AccessibilityClient:
  def near(beaches: List[Beach], radiusM: Int = 300): Map[String, Facilities] < Sync =
    beaches.map(b => b.name -> Facilities.NoData).toMap
