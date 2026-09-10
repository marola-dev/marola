package marola.water

import java.time.LocalDate

import marola.json.JsonValue
import marola.model.Coordinates

/**
 * Parses a payload trimmed from the real `relatorio/mapa` feed of 2026-09-05 (the 14 points nearest
 * Campeche), with one deliberately malformed sample appended to the first point.
 */
class ImaScWaterQualityClientSpec extends munit.FunSuite:

  private val fixture =
    val stream = getClass.getClassLoader.getResourceAsStream("ima-mapa-sample.json")
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  private val points = ImaScWaterQualityClient.parse(JsonValue.parse(fixture))

  test("parses every point with string-typed coordinates and the last five samples") {
    assertEquals(points.size, 14)
    val p89 = points.find(_.pointName == "Ponto 89").getOrElse(fail("no Ponto 89"))
    assertEquals(p89.beachName, "PRAIA DO CAMPECHE")
    assert(p89.coordinates.distanceKm(Coordinates(-27.6733, -48.4700)) < 1.5)
    assert(p89.samples.size >= 5, s"expected >= 5 samples, got ${p89.samples.size}")
    assertEquals(p89.latest.map(_.sampledOn), Some(LocalDate.of(2026, 8, 25)))
    assertEquals(p89.latest.map(_.condition), Some(BathingCondition.Proper))
    assertEquals(p89.latest.flatMap(_.enterococciPer100ml), Some(10))
    assertEquals(p89.latest.flatMap(_.rain), Some("Ausente"))
    assertEquals(p89.latest.flatMap(_.waterTempC), Some(16.0))
  }

  test("the Riozinho do Campeche point is IMPRÓPRIA with its count, accents handled") {
    val p73 = points.find(_.pointName == "Ponto 73").getOrElse(fail("no Ponto 73"))
    assertEquals(p73.latest.map(_.condition), Some(BathingCondition.Improper))
    assertEquals(p73.latest.flatMap(_.enterococciPer100ml), Some(749))
    assert(p73.location.contains("Riozinho"))
  }

  test("a malformed sample date is dropped, not fatal — the point keeps its other samples") {
    val first = points.find(_.samples.exists(_.sampledOn.isEqual(LocalDate.of(2026, 8, 25)))).get
    assert(first.samples.forall(_.sampledOn.getYear == 2026))
  }

  test("a point with no coordinates is dropped; unknown CONDICAO maps to Unknown") {
    val json = JsonValue.parse(
      """[{"CODIGO":"1","BALNEARIO":"X","PONTO_NOME":"P","LOCALIZACAO":"","LATITUDE":"abc","LONGITUDE":"-48","ANALISES":[]},
         {"CODIGO":2,"BALNEARIO":"Y","PONTO_NOME":"Q","LOCALIZACAO":"","LATITUDE":-27.5,"LONGITUDE":-48.5,
          "ANALISES":[{"DATA":"01/09/2026","CONDICAO":"Indeterminado","CHUVA":null,"RESULTADO":"n/a","TEMP_AGUA":null}]}]"""
    )
    val parsed = ImaScWaterQualityClient.parse(json)
    assertEquals(parsed.map(_.id), List("2"))
    assertEquals(parsed.head.latest.map(_.condition), Some(BathingCondition.Unknown))
    assertEquals(parsed.head.latest.flatMap(_.enterococciPer100ml), None)
  }

  test("coversOrigin: Campeche yes, Rio no") {
    assert(ImaScWaterQualityClient.coversOrigin(Coordinates(-27.6733, -48.4700)))
    assert(!ImaScWaterQualityClient.coversOrigin(Coordinates(-22.9878, -43.1913)))
  }

end ImaScWaterQualityClientSpec

/**
 * The feed marola actually received on 2026-09-10: `ANALISES` is gone and every point carries a
 * single `CONDICAO` instead. The old shape produced 260 points with no samples at all, which read
 * downstream as "no data" on every Florianópolis beach while the board still named IMA/SC.
 */
class ImaScCondicaoOnlySpec extends munit.FunSuite:

  private val points =
    val stream = getClass.getClassLoader.getResourceAsStream("ima-mapa-condicao-only.json")
    val text =
      try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
      finally stream.close()
    ImaScWaterQualityClient.parse(JsonValue.parse(text), () => LocalDate.of(2026, 9, 10))

  test("a point with no ANALISES still yields a usable sample from CONDICAO") {
    assertEquals(points.size, 6)
    points.foreach(p => assert(p.latest.isDefined, s"${p.pointName} has no usable sample"))
  }

  test("CONDICAO's masculine labels map to the agency's classification") {
    val p73 = points.find(_.pointName == "Ponto 73").getOrElse(fail("no Ponto 73"))
    assertEquals(p73.latest.map(_.condition), Some(BathingCondition.Improper))
    val p35 = points.find(_.pointName == "Ponto 35").getOrElse(fail("no Ponto 35"))
    assertEquals(p35.latest.map(_.condition), Some(BathingCondition.Proper))
  }

  test("Campeche still has its five points, and they carry coordinates") {
    val campeche = points.filter(_.beachName.toLowerCase.contains("campeche"))
    assertEquals(campeche.size, 5)
    campeche.foreach(p => assert(p.coordinates.lat < -27.0 && p.coordinates.lon < -48.0))
  }

  test("a CONDICAO-only sample carries no count and no rain — the feed no longer sends them") {
    val p35 = points.find(_.pointName == "Ponto 35").getOrElse(fail("no Ponto 35"))
    assertEquals(p35.latest.flatMap(_.enterococciPer100ml), None)
    assertEquals(p35.latest.flatMap(_.rain), None)
  }
  test("the synthesised sample is dated by the injected clock, not the wall clock") {
    val p35 = points.find(_.pointName == "Ponto 35").getOrElse(fail("no Ponto 35"))
    assertEquals(p35.latest.map(_.sampledOn), Some(LocalDate.of(2026, 9, 10)))
  }
