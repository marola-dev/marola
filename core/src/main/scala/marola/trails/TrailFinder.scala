package marola.trails

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.model.{Beach, Coordinates}

/**
 * MIP-0030: a named coastal/lakeside trail (OSM `highway=path`/`track`) near a beach or a named
 * lake — length, whatever `sac_scale`/`surface` OSM carries (verbatim, `None` when the tag is
 * absent, never guessed), and the geometry the map draws.
 */
final case class Trail(
    name: String,
    lengthKm: Double,
    difficulty: Option[String],
    surface: Option[String],
    geometry: List[Coordinates],
    nearBeach: Option[(String, Double)],
    nearLake: Option[(String, Double)]
)

/**
 * Finds named trails near a point via the same free, keyless Overpass API `BeachFinder` already
 * calls — MIP-0030 §4.2: OSM has global, uniformly-tagged trail coverage, unlike bathing-water
 * data, so this is `BeachFinder`'s pattern, not `ImaScWaterQualityClient`'s per-region-scraper one.
 */
object TrailFinder:

  private val OverpassEndpoint = "https://overpass-api.de/api/interpreter"

  // Same numbers as `BeachFinder`; share them once a third Overpass client needs them too.
  private val OverpassTimeoutSeconds = 45
  private val HttpTimeoutSeconds = 60L
  private val OverpassRetries = 2

  /**
   * How close a trail's geometry must come to a beach/lake anchor to count as "near" it — MIP-0030
   * §3/§8: matches MIP-0021's accessibility radius for consistency, not independently verified
   * against user expectation (§8, §11 open question).
   */
  val NearRadiusKm = 0.5

  /**
   * `nearby`, degraded to no trails when Overpass fails (429 is its documented back-pressure):
   * trails are enrichment on an already-complete result, so a failure must not discard the run.
   */
  def nearbyOrEmpty(
      origin: Coordinates,
      radiusKm: Double,
      beaches: List[Beach]
  ): List[Trail] < Sync =
    soften(nearby(origin, radiusKm, beaches))

  private[trails] def soften(effect: List[Trail] < Sync): List[Trail] < Sync =
    Abort.run(Abort.catching[Throwable](effect)).map {
      case Result.Success(trails) => trails
      case _                      => Nil
    }

  /**
   * Named `highway=path`/`track` ways within `NearRadiusKm` of a beach or a named lake, same-named
   * segments merged into one `Trail` (MIP-0030 §8: OSM splits one trail into many ways).
   */
  def nearby(origin: Coordinates, radiusKm: Double, beaches: List[Beach]): List[Trail] < Sync =
    val radiusM = (radiusKm * 1000).toInt
    val nearM = (NearRadiusKm * 1000).toInt
    // MIP-0030 §4.1's query plus `.lakes out center;`: `nearLake` needs the lake anchors' names.
    val query =
      s"""[out:json][timeout:$OverpassTimeoutSeconds];
         |way["natural"="beach"]["name"](around:$radiusM,${origin.lat},${origin.lon})->.beaches;
         |(
         |  way["natural"="water"]["water"~"^(lake|pond)$$"](around:$radiusM,${origin.lat},${origin.lon});
         |  relation["natural"="water"]["water"~"^(lake|pond)$$"](around:$radiusM,${origin.lat},${origin.lon});
         |)->.lakes;
         |(
         |  way(around.beaches:$nearM)["highway"~"^(path|track)$$"]["name"];
         |  way(around.lakes:$nearM)["highway"~"^(path|track)$$"]["name"];
         |);
         |out tags geom;
         |.lakes out center;""".stripMargin

    Http
      .postForm(
        OverpassEndpoint,
        Map("data" -> query),
        HttpTimeoutSeconds,
        retries = OverpassRetries
      )
      .map(body => parse(JsonValue.parse(body), beaches))

  /** One raw `way` before same-named segments are merged. */
  final private case class Segment(
      name: String,
      geometry: List[Coordinates],
      difficulty: Option[String],
      surface: Option[String]
  )

  private[trails] def parse(root: JsonValue, beaches: List[Beach]): List[Trail] =
    val elements = root("elements").arr.toList
    val lakes = elements.flatMap(parseLake)
    elements
      .flatMap(parseSegment)
      .groupBy(_.name)
      .values
      .map(merge(_, beaches, lakes))
      .toList
      .sortBy(_.name)

  private def parseSegment(el: JsonValue): Option[Segment] =
    val tags = el("tags")
    val isTrailWay = tags("highway").str.exists(h => h == "path" || h == "track")
    if !isTrailWay then None
    else
      tags("name").str.flatMap { name =>
        val geom = el("geometry").arr.toList.flatMap(g =>
          for
            lat <- g("lat").num
            lon <- g("lon").num
          yield Coordinates(lat, lon)
        )
        if geom.size >= 2 then Some(Segment(name, geom, tags("sac_scale").str, tags("surface").str))
        else None
      }

  /** A named lake/pond anchor — only named ones can be reported as `nearLake`. */
  private def parseLake(el: JsonValue): Option[(String, Coordinates)] =
    val tags = el("tags")
    if !tags("natural").str.contains("water") then None
    else
      for
        name <- tags("name").str
        lat <- el("lat").num.orElse(el("center")("lat").num)
        lon <- el("lon").num.orElse(el("center")("lon").num)
      yield (name, Coordinates(lat, lon))

  /**
   * Concatenates every segment's geometry (implementation-sensitive order: as grouped) and sums
   * each segment's *own* length rather than the length of the concatenation — two disjoint OSM ways
   * sharing a name are not necessarily endpoint-to-endpoint, so summing the concatenation would add
   * a spurious jump between them.
   */
  private def merge(
      segs: Iterable[Segment],
      beaches: List[Beach],
      lakes: List[(String, Coordinates)]
  ): Trail =
    val list = segs.toList
    val name = list.head.name
    val geometry = list.flatMap(_.geometry)
    val lengthKm = list.map(segmentLengthKm).sum
    val difficulty = list.flatMap(_.difficulty).headOption
    val surface = list.flatMap(_.surface).headOption
    Trail(
      name,
      lengthKm,
      difficulty,
      surface,
      geometry,
      nearest(geometry, beaches.map(b => (b.name, b.coordinates))),
      nearest(geometry, lakes)
    )

  private def segmentLengthKm(s: Segment): Double =
    s.geometry.sliding(2).collect { case List(a, b) => a.distanceKm(b) }.sum

  /** The nearest named anchor to any point of `geometry`, within `NearRadiusKm`, or `None`. */
  private def nearest(
      geometry: List[Coordinates],
      anchors: List[(String, Coordinates)]
  ): Option[(String, Double)] =
    if geometry.isEmpty || anchors.isEmpty then None
    else
      val distances = for
        (name, coord) <- anchors
        point <- geometry
      yield (name, coord.distanceKm(point))
      distances.minByOption(_._2).filter(_._2 <= NearRadiusKm)
