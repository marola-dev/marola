package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{LocalDate, LocalTime}

import marola.water.BathingCondition

/**
 * MIP-0056 §7, entirely offline: the four Campeche exports, both empty shapes and the join to the
 * points feed, on the bytes the portal returned on 2026-09-14.
 */
class ImaScCsvParserSpec extends munit.FunSuite:

  private def fixture(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"ima-sc/$name")
    try String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  private val source = ImaScAdapter.DefaultSource

  private lazy val points: List[PointRow] =
    ImaScAdapter.parsePoints(source, fixture("points.json"))

  private def parse(name: String): List[SampleRow] =
    ImaScCsv.parse(source, name, fixture(name), points) match
      case Right(rows) => rows
      case Left(error) => fail(s"expected a parse, got $error")

  private val campeche =
    List("campeche-2003.csv", "campeche-2010.csv", "campeche-2025.csv", "campeche-2026.csv")

  test("Campeche 2003 and 2010 drop only their 'Sem registros' lines (31 → 27, 29 → 25)") {
    assertEquals(parse("campeche-2003.csv").size, 27)
    assertEquals(parse("campeche-2010.csv").size, 25)
  }

  test("Campeche 2025 and 2026 parse every row (145, 90)") {
    assertEquals(parse("campeche-2025.csv").size, 145)
    assertEquals(parse("campeche-2026.csv").size, 90)
  }

  test("a censored '<20' keeps its number and Below; the comma in Localização shifts nothing") {
    assertEquals(
      parse("campeche-2003.csv").head,
      SampleRow(
        sourceId = "ima-sc",
        pointKey = "58fbe2a9-d9ce-466f-94c3-5dbd46416ead",
        sampledOn = LocalDate.of(2003, 12, 15),
        sampledAt = Some(LocalTime.of(9, 37)),
        condition = BathingCondition.Proper,
        indicator = Indicator.EColi,
        indicatorValue = Some(20),
        qualifier = Qualifier.Below,
        rain = Some("Moderada"),
        wind = Some("Nordeste"),
        tide = Some("Vazante"),
        waterTempC = Some(21.0),
        airTempC = Some(27.0),
        channel = Channel.Csv,
        bulletinDate = None
      )
    )
  }

  test("'> 24196' is the method's ceiling, not a missing value") {
    val above = campeche.flatMap(parse).filter(_.qualifier == Qualifier.Above)
    assertEquals(above.map(_.indicatorValue), List(Some(24196), Some(24196)))
  }

  test("a blank temperature or tide is None, never 0") {
    val blankAir = parse("campeche-2003.csv").filter(_.airTempC.isEmpty)
    assertEquals(blankAir.size, 1)
    assertEquals(blankAir.head.waterTempC, Some(17.0))
    assertEquals(parse("campeche-2003.csv").count(_.tide.isEmpty), 3)
  }

  test("PRÓPRIA and IMPRÓPRIA map to BathingCondition, and every row carries E. coli") {
    val rows = parse("campeche-2026.csv")
    assertEquals(rows.count(_.condition == BathingCondition.Proper), 72)
    assertEquals(rows.count(_.condition == BathingCondition.Improper), 18)
    assert(rows.forall(_.indicator == Indicator.EColi))
    assert(rows.forall(_.channel == Channel.Csv))
  }

  test("a 'Sem registros' year and an unknown beach are empty exports, not errors") {
    assertEquals(
      ImaScCsv.parse(source, "sem", fixture("sem-registros-1999.csv"), points),
      Right(Nil)
    )
    assertEquals(ImaScCsv.parse(source, "hdr", fixture("header-only.csv"), points), Right(Nil))
  }

  test("a header that is not the portal's 13 columns is a ParseError") {
    ImaScCsv.parse(source, "raw/other.csv", "a,b,c\n1,2,3\n", points) match
      case Left(ParseError("raw/other.csv", _)) => ()
      case other                                => fail(s"expected a header ParseError, got $other")
  }

  test("an unparsable sample date is a ParseError naming its line") {
    val broken = fixture("campeche-2003.csv").replace("15/12/2003", "15-12-2003")
    ImaScCsv.parse(source, "raw/broken.csv", broken, points) match
      case Left(ParseError(_, detail)) => assert(detail.contains("15-12-2003"), detail)
      case other                       => fail(s"expected a date ParseError, got $other")
  }

  test("every Campeche sample resolves to one of the five feed UUIDs, each with a coordinate") {
    val known = points.filter(p => p.beachName == "Praia do Campeche").map(_.pointKey).toSet
    assertEquals(known.size, 5)
    val resolved = campeche.flatMap(parse).map(_.pointKey).toSet
    assertEquals(resolved, known)
    assert(
      points.filter(p => known.contains(p.pointKey)).forall(p => p.lat.isDefined && p.lon.isDefined)
    )
  }

  test("a point the feed does not know keeps a slug key and is not dropped") {
    val header = fixture("campeche-2003.csv").linesIterator.next()
    val row =
      """Florianópolis,"Praia do Campeche","Ponto 99","Em frente à praia, no fim da rua",""" +
        "15/12/2003,09:37,Nordeste,Vazante,Moderada,21,27,<20,PRÓPRIA"
    val parsed = ImaScCsv.parse(source, "raw/invented.csv", s"$header\n$row\n", points)
    assertEquals(
      parsed.map(_.map(_.pointKey)),
      Right(List("ima-sc:florianopolis/campeche/ponto-99"))
    )

    val placeholder = ImaScCsv.pointFor(
      source,
      ImaScCsv.index(points),
      "Florianópolis",
      "Praia do Campeche",
      "Ponto 99"
    )
    assertEquals(placeholder.geoSource, GeoSource.Missing)
    assertEquals(placeholder.lat, None)
    assertEquals(placeholder.pointKey, "ima-sc:florianopolis/campeche/ponto-99")
  }

  test("two feed points that normalise alike resolve to the greatest key, whatever the order") {
    def twin(key: String) = PointRow(
      sourceId = source.id,
      pointKey = key,
      country = "BR",
      state = "SC",
      municipality = "Florianópolis",
      beachName = "Praia do Campeche",
      pointName = "Ponto 35",
      ibgeCode = None,
      locationDesc = None,
      lat = None,
      lon = None,
      geoSource = GeoSource.Feed,
      firstSeen = None,
      lastSeen = None
    )
    // `build.sql` picks max(point_key) over the same triple; the two must agree or a point's
    // history splits between the SQL build and the ingest's own join.
    val triple = ("florianopolis", "campeche", "ponto 35")
    assertEquals(ImaScCsv.index(List(twin("bbb"), twin("aaa")))(triple).pointKey, "bbb")
    assertEquals(ImaScCsv.index(List(twin("aaa"), twin("bbb")))(triple).pointKey, "bbb")
  }
