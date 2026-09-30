package marola.scoring

import java.time.{LocalDate, LocalDateTime}

import marola.model.{Coordinates, HourlyConditions}
import marola.water.{BathingCondition, SamplingPoint, WaterQuality, WaterSample}

/** MIP-0001 §6, row by row. */
class WaterVerdictSpec extends munit.FunSuite:

  private val today = LocalDate.of(2026, 9, 5)

  private def point(name: String, cond: BathingCondition, sampled: LocalDate, count: Int = 10) =
    SamplingPoint(
      name,
      "PRAIA X",
      name,
      s"loc $name",
      Coordinates(0, 0),
      List(WaterSample(sampled, cond, None, Some(count), None))
    )

  private val goodHour = HourlyConditions(
    LocalDateTime.of(2026, 9, 6, 10, 0),
    Some(26.0),
    Some(23.0),
    Some(0.7),
    Some(10.0),
    Some(90.0),
    Some(3.0),
    Some(6.0),
    Some(5.0),
    Some(true)
  )

  test("no provider / no matched point: no penalty, 'no data' summary, no note") {
    val v = Swimability.waterVerdict(None, today)
    assertEquals(v, WaterVerdict.NoData)
    val (score, notes) = Swimability.score(goodHour, v)
    assertEquals((score, notes), Swimability.score(goodHour))
  }

  test(
    "all points IMPRÓPRIA and fresh: veto — score 0 whatever the sea does, note names the worst point"
  ) {
    val wq = WaterQuality(
      List(
        point("Ponto 1", BathingCondition.Improper, today.minusDays(3), 749),
        point("Ponto 2", BathingCondition.Improper, today.minusDays(3), 120)
      ),
      "IMA/SC"
    )
    val v = Swimability.waterVerdict(Some(wq), today)
    assert(v.veto)
    assert(
      v.note.exists(n => n.contains("unfit") && n.contains("Ponto 1") && n.contains("749")),
      v.note.toString
    )
    assertEquals(Swimability.score(goodHour, v)._1, 0)
    assert(v.summary.startsWith("IMPRÓPRIA"))
  }

  test("mixed: −20 and the IMPRÓPRIA locations are named; PRÓPRIA count in the summary") {
    val wq = WaterQuality(
      List(
        point("Ponto 89", BathingCondition.Proper, today.minusDays(11)),
        point("Ponto 73", BathingCondition.Improper, today.minusDays(11), 749),
        point("Ponto 35", BathingCondition.Proper, today.minusDays(11))
      ),
      "IMA/SC"
    )
    val v = Swimability.waterVerdict(Some(wq), today)
    assert(!v.veto)
    assertEquals(v.delta, -20)
    assert(v.note.exists(_.contains("avoid loc Ponto 73")), v.note.toString)
    assert(v.summary.startsWith("2/3 PRÓPRIA"), v.summary)
    val (base, _) = Swimability.score(goodHour)
    assertEquals(Swimability.score(goodHour, v)._1, base - 20)
  }

  test("all PRÓPRIA and fresh: no penalty, no note, PRÓPRIA summary with the sample date") {
    val wq =
      WaterQuality(List(point("Ponto 33", BathingCondition.Proper, today.minusDays(11))), "IMA/SC")
    val v = Swimability.waterVerdict(Some(wq), today)
    assertEquals((v.delta, v.veto, v.note), (0, false, None))
    assert(v.summary.startsWith("PRÓPRIA (1/1 pts, 25 Aug)"), v.summary)
  }

  test("an all-Unknown fresh sample reads no verdict, not PRÓPRIA") {
    val wq = WaterQuality(
      List("Ponto 1", "Ponto 2", "Ponto 3").map(
        point(_, BathingCondition.Unknown, today.minusDays(11))
      ),
      "IMA/SC"
    )
    val v = Swimability.waterVerdict(Some(wq), today)
    assertEquals((v.delta, v.veto, v.note), (0, false, None))
    assertEquals(v.summary, "no verdict (3 pts unknown, 25 Aug)")
  }

  test("a PRÓPRIA + Unknown sample counts only the PRÓPRIA points and names the unknowns") {
    val wq = WaterQuality(
      List(
        point("Ponto 1", BathingCondition.Proper, today.minusDays(11)),
        point("Ponto 2", BathingCondition.Proper, today.minusDays(11)),
        point("Ponto 3", BathingCondition.Unknown, today.minusDays(11))
      ),
      "IMA/SC"
    )
    val v = Swimability.waterVerdict(Some(wq), today)
    assertEquals((v.delta, v.veto, v.note), (0, false, None))
    assertEquals(v.summary, "PRÓPRIA (2/3 pts, 1 unknown, 25 Aug)")
  }

  test("stale (> 45 days): treated as no data, but says so — an old IMPRÓPRIA does not veto") {
    val wq = WaterQuality(
      List(point("Ponto 1", BathingCondition.Improper, today.minusDays(46), 900)),
      "IMA/SC"
    )
    val v = Swimability.waterVerdict(Some(wq), today)
    assertEquals((v.delta, v.veto), (0, false))
    assert(v.note.exists(_.contains("stale")), v.note.toString)
    assert(v.summary.startsWith("stale"))
    // exactly 45 days is still fresh.
    val edge = WaterQuality(
      List(point("Ponto 1", BathingCondition.Improper, today.minusDays(45), 900)),
      "IMA/SC"
    )
    assert(Swimability.waterVerdict(Some(edge), today).veto)
  }

end WaterVerdictSpec
