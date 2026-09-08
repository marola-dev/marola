package marola.conditions

import java.time.LocalDateTime

import marola.model.HourlyConditions

class TidesSpec extends munit.FunSuite:

  private def hour(h: Int, level: Option[Double]) =
    HourlyConditions(
      LocalDateTime.of(2026, 9, 6, h, 0),
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      seaLevelM = level
    )

  test("extrema finds the low and the high of a simple tidal curve") {
    // levels: falls to -0.3 at 02:00, rises to +0.9 at 08:00, falls again.
    val levels = List(0.1, -0.1, -0.3, -0.2, 0.2, 0.5, 0.8, 0.9, 0.7, 0.4)
    val hours = levels.zipWithIndex.map { case (l, i) => hour(i, Some(l)) }
    val events = Tides.extrema(hours)
    assertEquals(events.map(e => (e.time.getHour, e.isHigh)), List((2, false), (7, true)))
    assertEqualsDouble(events.head.heightM, -0.3, 1e-9)
  }

  test("hours without sea-level data are skipped, and fewer than three points give no events") {
    assertEquals(Tides.extrema(List(hour(0, Some(0.1)), hour(1, None), hour(2, Some(0.3)))), Nil)
    assertEquals(Tides.extrema(Nil), Nil)
  }

  test("a 2cm wobble at the end of the day is not a tide turn") {
    // real shape from a run: ... high 21:00 +0.42, 22:00 +0.40, 23:00 +0.41 → one high, no 22:00
    // low.
    val levels = List(-0.1, 0.2, 0.5, 0.7, 0.6, 0.4, 0.3, 0.35, 0.42, 0.40, 0.41)
    val hours = levels.zipWithIndex.map { case (l, i) => hour(i, Some(l)) }
    val events = Tides.extrema(hours)
    assertEquals(
      events.map(e => (e.time.getHour, e.isHigh)),
      List((3, true), (6, false), (8, true))
    )
  }

  test("two same-type candidates in a row keep the more extreme one") {
    val levels = List(0.0, 0.5, 0.45, 0.9, 0.2, -0.5, 0.1)
    val hours = levels.zipWithIndex.map { case (l, i) => hour(i, Some(l)) }
    val events = Tides.extrema(hours)
    // 01:00 +0.5 and 03:00 +0.9 are both highs with only a 0.05 wobble between → keep 03:00 only.
    assertEquals(
      events.map(e => (e.time.getHour, e.heightM, e.isHigh)),
      List((3, 0.9, true), (5, -0.5, false))
    )
  }

  test("a flat series has no turns") {
    val hours = (0 until 6).toList.map(i => hour(i, Some(0.5)))
    assertEquals(Tides.extrema(hours), Nil)
  }

end TidesSpec
