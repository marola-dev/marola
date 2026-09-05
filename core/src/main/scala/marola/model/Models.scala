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
    whalePeak: Option[HourlyConditions] = None
)
