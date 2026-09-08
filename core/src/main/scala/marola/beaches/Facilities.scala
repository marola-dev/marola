package marola.beaches

import kyo.*

import marola.model.Beach

/**
 * OSM accessibility amenities near a beach (MIP-0021): `Parking`/`Toilets`/`Shower` come from OSM's
 * `amenity=*` tag, `Lifeguard` from `emergency=lifeguard`/`lifeguard_base` or the rarer
 * `amenity=lifeguard`.
 */
enum Facility derives CanEqual:
  case Parking, Toilets, Shower, Lifeguard

/** Counts per facility near one beach. */
final case class Facilities(counts: Map[Facility, Int]) derives CanEqual

object Facilities:
  val NoData: Facilities = Facilities(Map.empty)

/**
 * Amenities OpenStreetMap knows within `radiusM` of each beach `BeachFinder` already found — one
 * extra Overpass query per run (MIP-0021).
 */
trait AccessibilityClient:
  def near(beaches: List[Beach], radiusM: Int = 300): Map[String, Facilities] < Sync
