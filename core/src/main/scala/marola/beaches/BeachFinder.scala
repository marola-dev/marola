package marola.beaches

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.model.{Beach, Coordinates}

/**
 * Finds named beaches near a point via OpenStreetMap's Overpass API (overpass-api.de) — free, no
 * API key, no signup.
 */
object BeachFinder:

  private val OverpassEndpoint = "https://overpass-api.de/api/interpreter"

  /**
   * Upper bound on elements Overpass returns *before* marola sorts them by distance — Overpass's
   * `out N` truncation is in database order, not by distance, so this has to be comfortably larger
   * than the number of named beaches any plausible radius contains, or the nearest ones can be cut
   * arbitrarily.
   */
  private val MaxOverpassElements = 500

  /**
   * Overpass's server-side query budget, and the client-side HTTP timeout kept above it so a slow
   * query surfaces as Overpass's own error rather than a client abort.
   */
  private val OverpassTimeoutSeconds = 45
  private val HttpTimeoutSeconds = 60L

  /**
   * Extra attempts after a 429/5xx from the public instance (`Http.RetryableStatuses`): a 504 under
   * load is routine there and transient — one cost a scheduled site build its second area on 5 Sep
   * 2026.
   */
  private val OverpassRetries = 2

  def nearby(origin: Coordinates, radiusKm: Double = 15.0, limit: Int = 6): List[Beach] < Sync =
    val radiusM = (radiusKm * 1000).toInt
    // All three OSM element types: large beaches are very often mapped as multipolygon
    // *relations*, not ways — confirmed on real data: Praia do Campeche, Joaquina, Armação,
    // Matadeiro and ~40 others around Florianópolis are relations, and a node+way-only query
    // returned just two beaches within 20km of Campeche.
    val around = s"""["natural"="beach"]["name"](around:$radiusM,${origin.lat},${origin.lon})"""
    val query =
      s"""[out:json][timeout:$OverpassTimeoutSeconds];
         |(
         |  node$around;
         |  way$around;
         |  relation$around;
         |);
         |out center $MaxOverpassElements;""".stripMargin

    Http
      .postForm(
        OverpassEndpoint,
        Map("data" -> query),
        HttpTimeoutSeconds,
        retries = OverpassRetries
      )
      .map { body =>
        val elements = JsonValue.parse(body)("elements").arr
        elements
          .flatMap(parseElement(_, origin))
          .groupBy(_.name)
          .values
          .map(
            _.minBy(_.distanceKm)
          ) // dedupe node/way/relation for the same beach, keep the closer one
          .toList
          .sortBy(_.distanceKm)
          .take(limit)
      }

  private def parseElement(el: JsonValue, origin: Coordinates): Option[Beach] =
    val name = el("tags")("name").str.getOrElse("")
    if name.isEmpty then None
    else
      for
        lat <- el("lat").num.orElse(el("center")("lat").num)
        lon <- el("lon").num.orElse(el("center")("lon").num)
      yield
        val coords = Coordinates(lat, lon)
        Beach(name, coords, origin.distanceKm(coords))
