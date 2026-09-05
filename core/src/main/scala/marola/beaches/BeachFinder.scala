package marola.beaches

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.model.{Beach, Coordinates}

/**
 * Finds named beaches near a point via OpenStreetMap's Overpass API (overpass-api.de) — free, no
 * API key, no signup. Good enough for a POC; a production deployment calling this more than
 * occasionally should either self-host an Overpass instance or cache results, per Overpass's own
 * fair-use policy (https://wiki.openstreetmap.org/wiki/Overpass_API#Introduction).
 */
object BeachFinder:

  private val OverpassEndpoint = "https://overpass-api.de/api/interpreter"

  /**
   * Upper bound on elements Overpass returns *before* marola sorts them by distance — Overpass's
   * `out N` truncation is in database order, not by distance, so this has to be comfortably larger
   * than the number of named beaches any plausible radius contains, or the nearest ones can be cut
   * arbitrarily. An earlier version used `limit * 4` (24) and, around Florianópolis at 20km with
   * relations included (~40 named beaches), that silently dropped beaches 200m away.
   */
  private val MaxOverpassElements = 500

  /**
   * Overpass's server-side query budget, and the client-side HTTP timeout kept above it so a slow
   * query surfaces as Overpass's own error rather than a client abort. Relation-aware `around`
   * queries are slow on the public instance (~29s observed) — see `Http.postForm`.
   */
  private val OverpassTimeoutSeconds = 45
  private val HttpTimeoutSeconds = 60L

  def nearby(origin: Coordinates, radiusKm: Double = 15.0, limit: Int = 6): List[Beach] < Sync =
    val radiusM = (radiusKm * 1000).toInt
    // All three OSM element types: large beaches are very often mapped as multipolygon
    // *relations*, not ways — confirmed on real data: Praia do Campeche, Joaquina, Armação,
    // Matadeiro and ~40 others around Florianópolis are relations, and a node+way-only query
    // returned just two beaches within 20km of Campeche. `["name"]` drops unnamed fragments up
    // front (parseElement would discard them anyway) so the element cap isn't spent on them.
    val around = s"""["natural"="beach"]["name"](around:$radiusM,${origin.lat},${origin.lon})"""
    val query =
      s"""[out:json][timeout:$OverpassTimeoutSeconds];
         |(
         |  node$around;
         |  way$around;
         |  relation$around;
         |);
         |out center $MaxOverpassElements;""".stripMargin

    Http.postForm(OverpassEndpoint, Map("data" -> query), HttpTimeoutSeconds).map { body =>
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
