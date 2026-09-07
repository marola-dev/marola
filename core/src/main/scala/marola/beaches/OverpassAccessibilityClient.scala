package marola.beaches

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.model.{Beach, Coordinates}

/**
 * Overpass amenities near beaches `BeachFinder` already found (MIP-0021) — same public endpoint,
 * same HTTP timeout/retry discipline as `BeachFinder`, one extra query per run. Unlike
 * `BeachFinder`, this doesn't search Overpass for beaches itself: it builds an `around` union from
 * the beach coordinates it's handed, so it costs exactly one HTTP call regardless of how many
 * facility tags are being looked up.
 *
 * Never fails a run on its own: an Overpass error propagates as `Http.HttpError`/a network
 * exception, which `Recommender` catches and maps to `Facilities.NoData` for every beach — the same
 * stance MIP-0001 §5.2 takes for water quality (`Recommender.fetchWaterQuality`).
 */
final class OverpassAccessibilityClient(
    endpoint: String = OverpassAccessibilityClient.OverpassEndpoint
) extends AccessibilityClient:

  def near(beaches: List[Beach], radiusM: Int = 300): Map[String, Facilities] < Sync =
    if beaches.isEmpty then Map.empty[String, Facilities]
    else
      Http
        .postForm(
          endpoint,
          Map("data" -> OverpassAccessibilityClient.query(beaches, radiusM)),
          OverpassAccessibilityClient.HttpTimeoutSeconds,
          retries = OverpassAccessibilityClient.OverpassRetries
        )
        .map { body =>
          val elements = JsonValue.parse(body)("elements").arr
          OverpassAccessibilityClient.attribute(elements, beaches, radiusM)
        }

object OverpassAccessibilityClient:

  val OverpassEndpoint = "https://overpass-api.de/api/interpreter"

  /**
   * Same budget as `BeachFinder`'s — see its doc comment for the ~29s observed on the public
   * instance and why the client timeout sits comfortably above the server-side one.
   */
  private val OverpassTimeoutSeconds = 45
  private val HttpTimeoutSeconds = 60L
  private val OverpassRetries = 2

  /**
   * One `around` union per beach coordinate, two tag filters each — a regex alternation over the
   * six tags MIP-0021 §4 measured (`amenity` for parking/shower/toilets/lifeguard, `emergency` for
   * the two lifeguard-post spellings) rather than one clause per tag, to keep the query's line
   * count linear in the beach count, not the beach count times six. Pure string building, tested
   * directly (`AccessibilitySpec`).
   */
  private[beaches] def query(beaches: List[Beach], radiusM: Int): String =
    val clauses = beaches.flatMap { b =>
      val around = s"(around:$radiusM,${b.coordinates.lat},${b.coordinates.lon})"
      List(
        s"""  nwr$around["amenity"~"^(parking|shower|toilets|lifeguard)$$"];""",
        s"""  nwr$around["emergency"~"^(lifeguard|lifeguard_base)$$"];"""
      )
    }
    s"""[out:json][timeout:$OverpassTimeoutSeconds];
       |(
       |${clauses.mkString("\n")}
       |);
       |out tags center;""".stripMargin

  /**
   * ~11m — coarse enough to merge a node and its enclosing way's centroid for the same small
   * amenity, fine enough that two distinct toilets a block apart still count separately.
   */
  private val DedupCoordDecimals = 4

  final private case class Located(facility: Facility, lat: Double, lon: Double)

  /**
   * Pure; unit-tested against the real fixture (`AccessibilitySpec`). Overpass's own union already
   * removes a literal repeated element (same type and id returned by two overlapping `around`
   * clauses), so the only duplication left is one real place mapped as *both* a node and a way —
   * different OSM ids, near-identical coordinates, which id-based dedup can't catch. Deduped by
   * rounded coordinate instead (MIP-0021 §5's open dedup question — no duplicate showed up in the
   * live fixture to force the choice either way, so this is the one that actually handles the
   * realistic case). Each surviving element is then attributed to the nearest beach within
   * `radiusM`; every beach nothing attributed to gets `Facilities.NoData`.
   */
  private[beaches] def attribute(
      elements: Vector[JsonValue],
      beaches: List[Beach],
      radiusM: Int
  ): Map[String, Facilities] =
    val radiusKm = radiusM / 1000.0
    val located = elements.flatMap { el =>
      for
        facility <- facilityOf(el)
        lat <- el("lat").num.orElse(el("center")("lat").num)
        lon <- el("lon").num.orElse(el("center")("lon").num)
      yield Located(facility, lat, lon)
    }
    val scale = math.pow(10, DedupCoordDecimals)
    val deduped = located
      .groupBy(l =>
        (l.facility, math.round(l.lat * scale) / scale, math.round(l.lon * scale) / scale)
      )
      .values
      .map(_.head)
    val counted = deduped.flatMap { l =>
      val coords = Coordinates(l.lat, l.lon)
      for
        nearest <- beaches.minByOption(b => b.coordinates.distanceKm(coords))
        if nearest.coordinates.distanceKm(coords) <= radiusKm
      yield nearest.name -> l.facility
    }
    val perBeach = counted
      .groupBy(_._1)
      .view
      .mapValues(pairs =>
        Facilities(pairs.map(_._2).groupBy(identity).view.mapValues(_.size).toMap)
      )
      .toMap
    beaches.map(b => b.name -> perBeach.getOrElse(b.name, Facilities.NoData)).toMap

  private def facilityOf(el: JsonValue): Option[Facility] =
    val tags = el("tags")
    tags("amenity").str match
      case Some("parking")   => Some(Facility.Parking)
      case Some("toilets")   => Some(Facility.Toilets)
      case Some("shower")    => Some(Facility.Shower)
      case Some("lifeguard") => Some(Facility.Lifeguard)
      case _ =>
        tags("emergency").str match
          case Some("lifeguard") | Some("lifeguard_base") => Some(Facility.Lifeguard)
          case _                                          => None
