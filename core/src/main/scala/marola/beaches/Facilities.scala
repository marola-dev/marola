package marola.beaches

import kyo.*

import marola.model.Beach

/**
 * OSM accessibility amenities near a beach (MIP-0021): `Parking`/`Toilets`/`Shower` come from OSM's
 * `amenity=*` tag, `Lifeguard` from `emergency=lifeguard`/`lifeguard_base` or the rarer
 * `amenity=lifeguard`. `wheelchair` is deliberately not a case — the beach elements around
 * Florianópolis carry zero `wheelchair` tags (MIP-0021 §4), so there is nothing to report yet.
 */
enum Facility derives CanEqual:
  case Parking, Toilets, Shower, Lifeguard

/**
 * Counts per facility near one beach. A facility absent from `counts` means OSM has *no data* for
 * it — not "there is none" (MIP-0021 §5, the absence rule): a facility mapped nowhere near a beach
 * is a coverage gap, not a fact about the beach. A present key is never `0` for the same reason;
 * `NoData` (the empty map) is the one value that means "OSM has nothing here."
 */
final case class Facilities(counts: Map[Facility, Int]) derives CanEqual

object Facilities:
  val NoData: Facilities = Facilities(Map.empty)

/**
 * Amenities OpenStreetMap knows within `radiusM` of each beach `BeachFinder` already found — one
 * extra Overpass query per run (MIP-0021). Keyed by beach name (the key `BeachFinder` itself
 * dedupes on), one entry per beach passed in — a beach with nothing nearby maps to
 * `Facilities.NoData`, never a missing key, so callers don't have to special-case "not in the map"
 * separately from "no data."
 */
trait AccessibilityClient:
  def near(beaches: List[Beach], radiusM: Int = 300): Map[String, Facilities] < Sync
