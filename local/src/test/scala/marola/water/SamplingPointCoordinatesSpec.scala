package marola.water

class SamplingPointCoordinatesSpec extends munit.FunSuite:

  test("a point code present in the curated table resolves to its real coordinate") {
    val table = SamplingPointCoordinates.Rio
    val ip10 = table.getOrElse("IP10", fail("IP10 missing from sampling_points_rj.json"))
    // Real Ipanema coordinates (verified against OpenStreetMap, MIP-0031.tasks.md task 4).
    assert(math.abs(ip10.lat - -22.9873109) < 0.01)
    assert(math.abs(ip10.lon - -43.2021257) < 0.01)
  }

  test("every INEA/Rio point in the fixture bulletin's Zona Sudoeste/Sul area is in the table") {
    val table = SamplingPointCoordinates.Rio
    for code <- List("BD05", "BD07", "BD09", "BD10", "IP03", "IP10", "IP06", "CP100", "FL008") do
      assert(table.contains(code), s"expected $code in sampling_points_rj.json")
  }

  test("a point code absent from the table is dropped, not defaulted to a guessed coordinate") {
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
