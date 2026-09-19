package marola.agents

import scala.jdk.CollectionConverters.*

import Fixtures.*

class BriefGuardSpec extends munit.FunSuite:

  private val mole = beach("Praia Mole", 20.0)

  /** A real tool payload, built by the real tool code, as ADK hands it back. */
  private def planned(): List[(String, java.util.Map[String, Object])] =
    val outing =
      OutingPlanner.plan(List(hour(mole, 11, 88, notes = List("cold water (19.4°C)")))).head
    val json = marola.json.JsonValue.obj(
      "outings" -> marola.json.JsonValue.arr(SwimTools.outingJson(outing))
    )
    List("plan_swim_outing" -> KyoTool.asResultMap(json))

  private def check(reply: String): Option[String] =
    val results = planned()
    BriefGuard.verify(reply, BriefGuard.outings(results), results.map(_._2.toString).mkString(" "))

  test("the facts block is built from the tool result, verdict and hazards included") {
    val facts = BriefGuard.facts(BriefGuard.outings(planned()))
    assert(facts.contains("1. Praia Mole — score 88/100"), facts)
    assert(facts.contains("in the water at 11:00, leave by 10:30 (~30 min, traffic light)"), facts)
    assert(facts.contains("not live traffic"), facts)
    assert(facts.contains("bathing water: no data"), facts)
    assert(facts.contains("hazards: cold water (19.4°C)"), facts)
  }

  test("the live failure: a bathing-water verdict the data never gave is withheld") {
    val why = check("Praia Mole is recommended.\n- Bathing-water verdict: Good")
    assert(why.exists(_.contains("bathing water")), why.toString)
  }

  test("quoting the verdict the tool gave is fine, in either language") {
    assertEquals(check("Praia Mole at 11:00. Bathing water: no data for this beach."), None)
    assertEquals(check("Balneabilidade: no data. Saia às 10:30."), None)
  }

  test("an invented number is withheld; a tool number, a clock time and honest rounding are not") {
    assert(check("The sea is 23.5°C at Praia Mole.").exists(_.contains("23.5")))
    assertEquals(
      check("Sea 21.5°C, waves 0.4 m, in the water at 11:00, leave by 10:30, about 30 min."),
      None
    )
    assertEquals(check("Score 88; the drive is 20 km."), None) // 20.0 km in the data
  }

  test("no plan, no facts: planned() is false and there are no outings") {
    val other =
      List("find_nearby_beaches" -> Map[String, Object]("beaches" -> java.util.List.of()).asJava)
    assert(!BriefGuard.planned(other))
    assertEquals(BriefGuard.outings(other), Nil)
    assertEquals(
      BriefGuard.facts(Nil),
      "No swimmable beach and hour found for tomorrow in this radius."
    )
  }
