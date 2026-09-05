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
    isDaylight: Option[Boolean]
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
    notes: List[String]
)
