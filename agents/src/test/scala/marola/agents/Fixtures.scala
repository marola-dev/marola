package marola.agents

import java.time.LocalDateTime

import marola.model.{
  Beach,
  BestHour,
  Coordinates,
  HourlyConditions,
  JellyfishRisk,
  WhaleSightingLikelihood
}

object Fixtures:

  // A Wednesday: weekday rush hours apply (07–09 and 17–19).
  val Day: LocalDateTime = LocalDateTime.of(2026, 9, 23, 0, 0)

  def beach(name: String, distanceKm: Double): Beach =
    Beach(name, Coordinates(-27.6, -48.5), distanceKm)

  def hour(
      beach: Beach,
      at: Int,
      score: Int,
      notes: List[String] = Nil,
      jellyfish: JellyfishRisk = JellyfishRisk.Low
  ): BestHour =
    BestHour(
      beach = beach,
      hour = HourlyConditions(
        time = Day.withHour(at),
        airTempC = Some(24.0),
        seaTempC = Some(21.5),
        waveHeightM = Some(0.4),
        windSpeedKmh = Some(9.0),
        windDirectionDeg = Some(90.0),
        currentVelocityKmh = Some(1.0),
        uvIndex = Some(5.0),
        precipitationProbabilityPct = Some(10.0),
        isDaylight = Some(true)
      ),
      score = score,
      jellyfishRisk = jellyfish,
      whaleSightingLikelihood = WhaleSightingLikelihood.values.head,
      notes = notes
    )
