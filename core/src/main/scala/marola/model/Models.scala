package marola.model

import java.time.LocalDateTime

final case class Coordinates(lat: Double, lon: Double):
  /**
   * Straight-line ("as the crow flies") great-circle distance in km, via the haversine formula —
   * cheap, no API call, but it can rank a beach across a bay/headland as "nearby" when it's
   * actually a long drive or not reachable at all without a boat (confirmed on real data:
   * Icaraí/Camboinhas in Niterói show up within 15km of Arpoador, straight across Guanabara Bay).
   * `RouteFinder` (Azure Maps, opt-in) is the real-routing fix — see `ARCHITECTURE.md` §5b/§9.
   */
  def distanceKm(other: Coordinates): Double =
    val earthRadiusKm = 6371.0
    val dLat = math.toRadians(other.lat - lat)
    val dLon = math.toRadians(other.lon - lon)
    val la1 = math.toRadians(lat)
    val la2 = math.toRadians(other.lat)
    val h = math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(la1) * math.cos(la2) * math.sin(dLon / 2) * math.sin(dLon / 2)
    2 * earthRadiusKm * math.asin(math.sqrt(h))

object Coordinates:

  /**
   * MIP-0008 §5.6: the pin inside a Google Maps URL, or `None`. Understands the shapes Maps hands
   * out (checked 2026-09-05): the viewport `/maps/@lat,lon,zoom`, the `q=`/`query=`/`ll=` query
   * parameters (a URL-encoded comma included), and the `!3dlat!4dlon` pin of a place URL — which
   * wins over the viewport centre, since the viewport is where the map was looking, not the pin.
   * Short links (`maps.app.goo.gl/...`) are not expanded here (that is a network call; the smoke
   * workflow follows the redirect with curl); only Google hosts are accepted, so a stray URL with a
   * `@1,2` in it does not become an origin. Pure, no I/O.
   */
  def fromMapsUrl(url: String): Option[Coordinates] =
    // Host from the raw URL: decoding first would turn a place name's `+` into a space and make
    // `URI.create` reject the whole thing. The decoded form is only for the number patterns.
    val host = scala.util.Try(java.net.URI.create(url.trim).getHost).toOption.flatMap(Option(_))
    val decoded = java.net.URLDecoder.decode(url.trim, "UTF-8")
    if !host.exists(h => h == "google.com" || h.endsWith(".google.com")) then None
    else
      val pin = MapsPin.findFirstMatchIn(decoded).map(m => (m.group(1), m.group(2)))
      val viewport = MapsViewport.findFirstMatchIn(decoded).map(m => (m.group(1), m.group(2)))
      val query = MapsQuery.findFirstMatchIn(decoded).map(m => (m.group(1), m.group(2)))
      pin.orElse(viewport).orElse(query).flatMap {
        case (lat, lon) =>
          for
            la <- lat.toDoubleOption if la >= -90 && la <= 90
            lo <- lon.toDoubleOption if lo >= -180 && lo <= 180
          yield Coordinates(la, lo)
      }

  private val Num = "(-?\\d{1,3}(?:\\.\\d+)?)"
  private val MapsPin = s"!3d$Num!4d$Num".r
  private val MapsViewport = s"/@$Num,$Num".r
  private val MapsQuery = s"[?&](?:q|query|ll)=(?:loc:)?$Num,$Num".r

final case class Beach(name: String, coordinates: Coordinates, distanceKm: Double)

/**
 * One hourly forecast slice, `time` in the beach's own local time (not UTC, not the JVM's default
 * zone) — see OpenMeteoClient. Every field is `Option` because Open-Meteo can and does omit a
 * variable for a given hour/location (e.g. marine variables near a coastline with no marine grid
 * cell); missing data degrades the score rather than failing the whole request.
 */
final case class HourlyConditions(
    time: LocalDateTime,
    airTempC: Option[Double],
    seaTempC: Option[Double],
    waveHeightM: Option[Double],
    windSpeedKmh: Option[Double],
    windDirectionDeg: Option[Double],
    currentVelocityKmh: Option[Double],
    uvIndex: Option[Double],
    precipitationProbabilityPct: Option[Double],
    isDaylight: Option[Boolean],
    // MIP-0001 additions — all from Open-Meteo's Marine API, all optional like everything above.
    wavePeriodS: Option[Double] = None,
    waveDirectionDeg: Option[Double] = None,
    swellWaveHeightM: Option[Double] = None,
    swellWavePeriodS: Option[Double] = None,
    seaLevelM: Option[Double] = None // tide: height above mean sea level, `Tides.extrema` reads it
)

enum JellyfishRisk derives CanEqual:
  case Low, Moderate, High

/**
 * The wind band `Swimability.windDelta` scores and names ("breezy", "strong wind"), exposed so the
 * map can show the same word without re-deriving a threshold in JavaScript (MIP-0009 §5). Absent
 * wind data is `None` at the call site, not a fourth case — "no wind data" is a different note.
 */
enum WindLevel derives CanEqual:
  case Calm, Breezy, Strong

/**
 * Humpback whales migrate along the Brazilian coast in austral winter/spring (roughly
 * July-November, see Swimability.whaleSightingLikelihood) — informational, not a safety/scoring
 * signal like `JellyfishRisk`, so it never affects `swimabilityScore`.
 */
enum WhaleSightingLikelihood derives CanEqual:
  case Low, Moderate, High

final case class BeachForecast(beach: Beach, timezoneId: String, hours: List[HourlyConditions])

final case class BestHour(
    beach: Beach,
    hour: HourlyConditions,
    score: Int,
    jellyfishRisk: JellyfishRisk,
    whaleSightingLikelihood: WhaleSightingLikelihood,
    notes: List[String],
    // MIP-0001: per-beach bathing-water verdict (None when no provider/no matched point), the
    // day's tide turns, and the daylight hour with the best whale-spotting odds — all computed
    // once per beach in `Recommender.scoreTomorrow`, carried here so `Main`'s detailed block and
    // the MCP server can print them without re-fetching.
    waterQuality: Option[marola.water.WaterQuality] = None,
    dayTides: List[marola.conditions.TideEvent] = Nil,
    whalePeak: Option[HourlyConditions] = None,
    // MIP-0021: OSM accessibility amenities within 300m of this beach, computed once per beach in
    // `Recommender.scoreDay`, `Facilities.NoData` (never absent) when no provider is configured or
    // OSM has nothing nearby.
    facilities: marola.beaches.Facilities = marola.beaches.Facilities.NoData
)
