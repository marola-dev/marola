package marola.agents

import Fixtures.*

class OutingPlannerSpec extends munit.FunSuite:

  private val near = beach("Praia Mole", 20.0)

  test("among near-equal hours, the one with the shorter drive wins") {
    // 09:00 scores 90 but means leaving in the rush; 11:00 scores 88 on an empty road.
    val plan = OutingPlanner.plan(List(hour(near, 9, 90), hour(near, 11, 88)))
    assertEquals(plan.map(_.pick.hour.time.getHour), List(11))
    assertEquals(plan.head.travelMinutes, 30)
    assertEquals(plan.head.bestScoreAtBeach, 90)
  }

  test("traffic never buys more than ScoreTolerance points of swimability") {
    val plan = OutingPlanner.plan(List(hour(near, 9, 90), hour(near, 11, 84)))
    assertEquals(plan.map(_.pick.hour.time.getHour), List(9))
  }

  test("an hour scored 0 is never a target, however empty the road") {
    val plan = OutingPlanner.plan(List(hour(near, 11, 0), hour(near, 8, 3)))
    assertEquals(plan.map(_.pick.score), List(3))
  }

  test("a beach with only vetoed hours is left out entirely") {
    val vetoed = beach("Lagoa", 5.0)
    val plan = OutingPlanner.plan(List(hour(vetoed, 10, 0), hour(near, 10, 70)))
    assertEquals(plan.map(_.pick.beach.name), List("Praia Mole"))
  }

  test("beaches are ranked by score first, the drive second") {
    val far = beach("Campeche", 35.0)
    val plan = OutingPlanner.plan(List(hour(near, 11, 70), hour(far, 11, 95)))
    assertEquals(plan.map(_.pick.beach.name), List("Campeche", "Praia Mole"))
  }

  test("no hours, no plan") {
    assertEquals(OutingPlanner.plan(Nil), Nil)
  }
