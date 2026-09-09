package marola.water

import java.time.LocalDate

import marola.json.JsonValue

/** The join is the risky part: a verdict landing on the wrong beach is a swim-safety answer. */
class ImaScPdfWaterQualityClientSpec extends munit.FunSuite:

  private val points = JsonValue.parse("""[
    {"CODIGO":"a1","BALNEARIO":"Praia do Campeche","PONTO_NOME":"Ponto 06",
     "LOCALIZACAO":"Em frente a Rua X","LATITUDE":"-27.6733","LONGITUDE":"-48.4700"},
    {"CODIGO":"b2","BALNEARIO":"Praia da Joaquina","PONTO_NOME":"Ponto 11",
     "LOCALIZACAO":"Em frente a Rua Y","LATITUDE":"-27.6280","LONGITUDE":"-48.4460"},
    {"CODIGO":"c3","BALNEARIO":"Praia Sem Boletim","PONTO_NOME":"Ponto 99",
     "LOCALIZACAO":"","LATITUDE":"-27.5","LONGITUDE":"-48.5"}
  ]""")

  private def row(beach: String, point: String, day: Int, c: BathingCondition) =
    ImaScPdfParser.Row(beach, point, LocalDate.of(2026, 8, day), c)

  test("each verdict lands on its own point, not a neighbour's") {
    val joined = ImaScPdfWaterQualityClient.join(
      List(
        row("PRAIA DO CAMPECHE", "Ponto 06", 25, BathingCondition.Proper),
        row("PRAIA DA JOAQUINA", "Ponto 11", 24, BathingCondition.Improper)
      ),
      points
    )
    val byId = joined.map(p => p.id -> p).toMap
    assertEquals(byId("a1").samples.head.condition, BathingCondition.Proper)
    assertEquals(byId("a1").samples.head.sampledOn, LocalDate.of(2026, 8, 25))
    assertEquals(byId("b2").samples.head.condition, BathingCondition.Improper)
    assertEquals(byId("b2").samples.head.sampledOn, LocalDate.of(2026, 8, 24))
  }

  test("the PDF shouts and the JSON does not — accents and case must not prevent a match") {
    val joined = ImaScPdfWaterQualityClient.join(
      List(row("PRAIA DO CAMPECHE", "Ponto 06", 25, BathingCondition.Proper)),
      points
    )
    assertEquals(joined.map(_.id), List("a1"))
    assertEquals(joined.head.coordinates.lat, -27.6733, "coordinates come from the JSON side")
  }

  test("the same point number on a different beach never collides") {
    val joined = ImaScPdfWaterQualityClient.join(
      List(row("PRAIA DA JOAQUINA", "Ponto 06", 25, BathingCondition.Improper)),
      points
    )
    assertEquals(joined, Nil, "Ponto 06 belongs to Campeche, not Joaquina")
  }

  test("a point with no bulletin row is dropped, not invented") {
    val joined = ImaScPdfWaterQualityClient.join(
      List(row("PRAIA DO CAMPECHE", "Ponto 06", 25, BathingCondition.Proper)),
      points
    )
    assert(!joined.exists(_.id == "c3"), "a point with no verdict must not appear")
  }

  test("no rows yields no points rather than points with empty samples") {
    assertEquals(ImaScPdfWaterQualityClient.join(Nil, points), Nil)
  }

  test("the newest dated bulletin is picked out of the portal index") {
    val html = """
      <a href="http://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/2026-07-03">x</a>
      <a href="http://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/2026-08-28">y</a>
      <a href="http://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/2026-08-11">z</a>"""
    assertEquals(
      ImaScPdfWaterQualityClient.latestBulletinUrl(html),
      Some("http://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/2026-08-28"),
      "hardcoding one bulletin is what left Rio pinned to a June PDF until it aged out"
    )
  }

  test("an index with no bulletin link yields None rather than a guessed URL") {
    assertEquals(ImaScPdfWaterQualityClient.latestBulletinUrl("<html>nothing here</html>"), None)
  }
end ImaScPdfWaterQualityClientSpec
