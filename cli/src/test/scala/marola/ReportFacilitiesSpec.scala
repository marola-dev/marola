package marola

import java.time.LocalDateTime

import marola.beaches.{Facilities, Facility}
import marola.model.{Beach, Coordinates, HourlyConditions, JellyfishRisk, WhaleSightingLikelihood}

/**
 * MIP-0021 §3/§7: `facilitiesLine` and the CLI line suffix it feeds, tested against synthetic
 * `Facilities` values (formatting logic, not measured data — the measured counts are covered by
 * `marola.beaches.AccessibilitySpec`'s real-fixture test).
 */
class ReportFacilitiesSpec extends munit.FunSuite:

  private val beach = Beach("Praia da Joaquina", Coordinates(-27.6344661, -48.4532013), 4.624)
  private val hour = HourlyConditions(
    time = LocalDateTime.of(2026, 9, 6, 10, 0),
    airTempC = Some(24.0),
    seaTempC = Some(22.0),
    waveHeightM = Some(1.0),
    windSpeedKmh = Some(10.0),
    windDirectionDeg = Some(90.0),
    currentVelocityKmh = Some(1.0),
    uvIndex = Some(6.0),
    precipitationProbabilityPct = Some(10.0),
    isDaylight = Some(true)
  )

  private def bestHour(facilities: Facilities): model.BestHour =
    model.BestHour(
      beach,
      hour,
      score = 62,
      jellyfishRisk = JellyfishRisk.Low,
      whaleSightingLikelihood = WhaleSightingLikelihood.Low,
      notes = Nil,
      facilities = facilities
    )

  test("facilitiesLine is None for Facilities.NoData — never a rendered 'none'") {
    assertEquals(Report.facilitiesLine(Facilities.NoData), None)
  }

  test("facilitiesLine: only amenities OSM returned are named, in Facility's declared order") {
    val f = Facilities(Map(Facility.Lifeguard -> 1, Facility.Parking -> 3, Facility.Toilets -> 1))
    assertEquals(
      Report.facilitiesLine(f),
      Some("parking nearby: 3 · toilets: 1 · lifeguard post: yes")
    )
  }

  test("facilitiesLine: lifeguard is 'yes', never a count — OSM posts carry no season") {
    assertEquals(
      Report.facilitiesLine(Facilities(Map(Facility.Lifeguard -> 3))),
      Some("lifeguard post: yes")
    )
  }

  test(
    "CLI line: 'facilities: no data' when Facilities.NoData, never 'no parking'/'no lifeguard'"
  ) {
    val line = Report.line(1, bestHour(Facilities.NoData))
    assert(line.contains("facilities: no data"), line)
    assert(!line.contains("no parking"), line)
    assert(!line.contains("no lifeguard"), line)
  }

  test("CLI line and brief line carry the facilities suffix when OSM has data") {
    val f = Facilities(Map(Facility.Parking -> 3, Facility.Toilets -> 1, Facility.Lifeguard -> 1))
    val best = bestHour(f)
    assert(Report.line(1, best).endsWith("parking nearby: 3 · toilets: 1 · lifeguard post: yes"))
    assert(
      Report.briefLine(1, best).endsWith("parking nearby: 3 · toilets: 1 · lifeguard post: yes")
    )
  }

end ReportFacilitiesSpec
