package marola.water

import java.time.LocalDate

/**
 * Against the real bulletin (`ima-boletim-sc-2026-08-28.pdf`, RELATÓRIO Nº 42, 260 points), because
 * the risk here is not "does it parse" but "does each verdict land on the right beach" — and a
 * verdict on the wrong beach is a swim-safety answer, not a formatting slip.
 */
class ImaScPdfParserSpec extends munit.FunSuite:

  private val bytes =
    val s = getClass.getClassLoader.getResourceAsStream("ima-boletim-sc-2026-08-28.pdf")
    require(s != null, "missing fixture ima-boletim-sc-2026-08-28.pdf")
    try s.readAllBytes()
    finally s.close()

  private lazy val rows = ImaScPdfParser.parse(bytes)

  test("every monitored point in the bulletin is parsed") {
    // The bulletin's own prose says 260 points; one (Ponto 96, Florianópolis) was not collected.
    assert(rows.size >= 250, s"only ${rows.size} rows")
    assert(rows.size <= 260, s"${rows.size} rows is more than the bulletin claims to hold")
  }

  test("the first record keeps its own verdict, not the next point's") {
    val first = rows.head
    assertEquals(first.beachName, "PRAIA DO MORRO DOS CONVENTOS")
    assertEquals(first.pointName, "Ponto 01")
    assertEquals(first.collectedOn, LocalDate.of(2026, 8, 26))
    assertEquals(first.condition, BathingCondition.Proper)
  }

  test("both verdicts appear, and accented PRÓPRIA/IMPRÓPRIA fold the same way") {
    val kinds = rows.map(_.condition).distinct.toSet
    assert(kinds.contains(BathingCondition.Proper), s"no PRÓPRIA in $kinds")
    assert(kinds.contains(BathingCondition.Improper), s"no IMPRÓPRIA in $kinds")
    assertEquals(
      rows.count(_.condition == BathingCondition.Unknown),
      0,
      "nothing should be Unknown"
    )
  }

  test("beach and point are separated, and the point number is never left in the name") {
    assert(
      rows.forall(!_.beachName.contains("Ponto")),
      rows.filter(_.beachName.contains("Ponto")).take(3).toString
    )
    assert(
      rows.forall(_.pointName.matches("Ponto \\d+")),
      rows.map(_.pointName).distinct.take(5).toString
    )
  }

  test("Florianópolis is covered — the area this was written for") {
    val floripa = rows.filter(_.beachName.toUpperCase.contains("CAMPECHE"))
    assert(floripa.nonEmpty, "no Campeche point in the bulletin")
    assert(floripa.forall(_.collectedOn.isAfter(LocalDate.of(2026, 8, 1))), floripa.toString)
  }

  test("dates sit in the bulletin's own week, except the point IMA says it could not collect") {
    val week = rows.filter(r => r.collectedOn.isAfter(LocalDate.of(2026, 8, 20)))
    assert(week.size >= rows.size - 1, s"${rows.size - week.size} rows outside the collection week")
    assert(
      rows.forall(_.collectedOn.isBefore(LocalDate.of(2026, 9, 1))),
      "no row may be dated after the bulletin itself"
    )
  }

  // The bulletin carries a point forward when it could not be resampled — its own footnote says
  // "Ponto 96 em Florianópolis não fori coletado por falta de acesso", and the row is dated
  // 26/05/2026. Parsing that verbatim is correct: marola's 45-day freshness rule ages it out on
  // its own, which is the behaviour we want and would lose if the parser "tidied" the date.
  test("a point IMA could not resample keeps its real, older collection date") {
    val stale = rows.filter(_.collectedOn.isBefore(LocalDate.of(2026, 7, 1)))
    assertEquals(stale.map(_.pointName), List("Ponto 96"))
    assertEquals(stale.head.beachName, "LAGOA DA CONCEIÇÃO")
    assertEquals(stale.head.collectedOn, LocalDate.of(2026, 5, 26))
  }

  test("a non-PDF input fails loudly rather than returning silent nonsense") {
    intercept[Exception](ImaScPdfParser.parse("not a pdf".getBytes("UTF-8")))
  }
end ImaScPdfParserSpec
