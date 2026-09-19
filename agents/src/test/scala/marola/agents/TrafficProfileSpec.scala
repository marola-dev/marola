package marola.agents

import java.time.LocalDateTime

class TrafficProfileSpec extends munit.FunSuite:

  private val wednesday = LocalDateTime.of(2026, 9, 23, 0, 0)
  private val saturday = LocalDateTime.of(2026, 9, 26, 0, 0)

  test("weekday rush hours are slower than mid-morning") {
    assertEquals(TrafficProfile.multiplier(wednesday.withHour(8)), 1.6)
    assertEquals(TrafficProfile.multiplier(wednesday.withHour(18)), 1.7)
    assertEquals(TrafficProfile.multiplier(wednesday.withHour(10)), 1.0)
  }

  test("a weekend has beach traffic instead of a commute") {
    assertEquals(TrafficProfile.multiplier(saturday.withHour(8)), 1.0)
    assertEquals(TrafficProfile.multiplier(saturday.withHour(10)), 1.4)
    assertEquals(TrafficProfile.multiplier(saturday.withHour(17)), 1.5)
  }

  test("travel minutes: 20 km at 40 km/h is 30 min free-flow, 48 in the morning rush") {
    assertEquals(TrafficProfile.travelMinutes(20.0, wednesday.withHour(10)), 30)
    assertEquals(TrafficProfile.travelMinutes(20.0, wednesday.withHour(8)), 48)
  }

  test("leaveBy uses the multiplier at departure, not at arrival") {
    // Arrive 10:00 on a weekday from 20 km: the free-flow guess departs 09:30, inside the rush.
    val (leave, minutes) = TrafficProfile.leaveBy(20.0, wednesday.withHour(10))
    assertEquals(minutes, 48)
    assertEquals(leave, wednesday.withHour(9).withMinute(12))
  }

  test("a negative distance is treated as zero, not as time travel") {
    assertEquals(TrafficProfile.travelMinutes(-3.0, wednesday.withHour(8)), 0)
  }

  test("levels") {
    assertEquals(TrafficProfile.level(1.0), TrafficProfile.Level.Light)
    assertEquals(TrafficProfile.level(1.2), TrafficProfile.Level.Moderate)
    assertEquals(TrafficProfile.level(1.6), TrafficProfile.Level.Heavy)
  }

  test("the basis says it is not live traffic") {
    assert(TrafficProfile.Basis.contains("not live traffic"))
  }
