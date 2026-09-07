package marola.water

/**
 * `SamplingPointCoordinates` reads the real curated tables checked into
 * `local/src/main/resources/sampling_points_ba.json` (INEMA/Bahia) and `sampling_points_rj.json`
 * (INEA/Rio) — MIP-0031 §5/§8, every entry sourced; see each file's own `source` field per entry
 * for how the coordinate was obtained.
 */
class SamplingPointCoordinatesSpec extends munit.FunSuite:

  test("Bahia: a point code present in the curated table resolves to its real coordinate") {
    val porto = SamplingPointCoordinates.Bahia.get("SSA PB 100")
    assert(porto.isDefined, "expected SSA PB 100 (Porto da Barra) to be in the curated table")
    val coords = porto.get
    // Porto da Barra sits just inside Salvador's bay-mouth peninsula.
    assert(coords.lat < -12.9 && coords.lat > -13.1)
    assert(coords.lon < -38.4 && coords.lon > -38.6)
  }

  test("Bahia: a point code absent from the curated table is dropped, never defaulted") {
    assertEquals(SamplingPointCoordinates.Bahia.get("SSA ZZ 999"), None)
  }

  test("Bahia: two distinct monitoring points on the same real beach both resolve") {
    // SSA ON 100 / SSA ON 200 are INEMA's two Ondina points; the table intentionally shares one
    // real OSM beach coordinate between them (documented per-entry in the resource's `source`
    // field) rather than fabricating a second, unverified position.
    val on100 = SamplingPointCoordinates.Bahia.get("SSA ON 100")
    val on200 = SamplingPointCoordinates.Bahia.get("SSA ON 200")
    assert(on100.isDefined && on200.isDefined)
    assertEquals(on100, on200)
  }

  test("Bahia: every entry in the curated resource is reachable through the table") {
    val fixture = getClass.getClassLoader.getResourceAsStream("sampling_points_ba.json")
    val text =
      try scala.io.Source.fromInputStream(fixture, "UTF-8").mkString
      finally fixture.close()
    val codes = marola.json.JsonValue.parse(text).arr.flatMap(_("code").str)
    assert(codes.nonEmpty)
    assert(codes.forall(SamplingPointCoordinates.Bahia.contains))
  }

  test("Rio: a point code present in the curated table resolves to its real coordinate") {
    val table = SamplingPointCoordinates.Rio
    val ip10 = table.getOrElse("IP10", fail("IP10 missing from sampling_points_rj.json"))
    // Real Ipanema coordinates (verified against OpenStreetMap, MIP-0031.tasks.md task 4).
    assert(math.abs(ip10.lat - -22.9873109) < 0.01)
    assert(math.abs(ip10.lon - -43.2021257) < 0.01)
  }

  test(
    "Rio: every INEA/Rio point in the fixture bulletin's Zona Sudoeste/Sul area is in the table"
  ) {
    val table = SamplingPointCoordinates.Rio
    for code <- List("BD05", "BD07", "BD09", "BD10", "IP03", "IP10", "IP06", "CP100", "FL008") do
      assert(table.contains(code), s"expected $code in sampling_points_rj.json")
  }

  test(
    "Rio: a point code absent from the table is dropped, not defaulted to a guessed coordinate"
  ) {
    val table = SamplingPointCoordinates.Rio
    assertEquals(table.get("NO-SUCH-CODE"), None)
    // Barra de Guaratiba's own points are outside the `rio` area radius (MIP-0031.tasks.md task 4)
    // and were deliberately left out rather than geocoded speculatively.
    assertEquals(table.get("BG00"), None)
  }

  test("parse: malformed entries (missing fields) are dropped, well-formed ones kept") {
    val json =
      """[{"code":"X1","beach_hint":"Beach","lat":-22.9,"lon":-43.1,"source":"test"},
         {"code":"X2","beach_hint":"Beach"},
         {"code":"X3","beach_hint":"Beach","lat":"not-a-number","lon":-43.1,"source":"test"}]"""
    val parsed = SamplingPointCoordinates.parse(json)
    assertEquals(parsed.map(_.code), List("X1"))
  }

end SamplingPointCoordinatesSpec
