package marola.agents

import java.time.{DayOfWeek, LocalDateTime}

/**
 * A typical-pattern travel-time estimate (MIP-0061 §5.4) — **not live traffic**.
 *
 * marola has no free, keyless source of live road traffic, so the local default is a fixed,
 * deterministic profile: a base speed and a congestion multiplier by weekday and hour. It exists to
 * answer "leave before or after the rush?", not to promise an arrival time, and every value it
 * produces is labelled with [[TrafficProfile.Basis]] so no reply can present it as measured. A live
 * backend (Google Routes API, traffic-aware) is the opt-in path the MIP designs and does not build.
 */
object TrafficProfile:

  val Basis = "estimate from a typical weekday/weekend rush-hour pattern — not live traffic"

  /** Door-to-door average on mixed urban/coastal roads with free flow. */
  val BaseSpeedKmh = 40.0

  enum Level derives CanEqual:
    case Light, Moderate, Heavy

  // ISO numbering (Monday = 1): `-language:strictEquality` has no `CanEqual` for a Java enum.
  private def isWeekend(day: DayOfWeek): Boolean = day.getValue >= 6

  /** How much longer than free flow a trip departing at `departure` takes. */
  def multiplier(departure: LocalDateTime): Double =
    val hour = departure.getHour
    if isWeekend(departure.getDayOfWeek) then
      // Beach traffic: out in the late morning, back in the late afternoon.
      if hour >= 9 && hour <= 11 then 1.4
      else if hour >= 16 && hour <= 18 then 1.5
      else 1.0
    else if hour >= 7 && hour <= 9 then 1.6
    else if hour >= 17 && hour <= 19 then 1.7
    else if hour >= 12 && hour <= 13 then 1.2
    else 1.0

  def level(multiplier: Double): Level =
    if multiplier >= 1.5 then Level.Heavy
    else if multiplier > 1.0 then Level.Moderate
    else Level.Light

  def travelMinutes(distanceKm: Double, departure: LocalDateTime): Int =
    math.ceil(distanceKm.max(0.0) / BaseSpeedKmh * 60.0 * multiplier(departure)).toInt

  /**
   * When to leave to be in the water at `arrival`. The multiplier depends on the departure time,
   * which depends on the trip length — one refinement from the free-flow guess is enough at this
   * resolution.
   */
  def leaveBy(distanceKm: Double, arrival: LocalDateTime): (LocalDateTime, Int) =
    val freeFlow = math.ceil(distanceKm.max(0.0) / BaseSpeedKmh * 60.0).toLong
    val minutes = travelMinutes(distanceKm, arrival.minusMinutes(freeFlow))
    (arrival.minusMinutes(minutes.toLong), minutes)
