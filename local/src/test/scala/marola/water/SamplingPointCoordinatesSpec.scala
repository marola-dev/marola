package marola.water

/**
 * `SamplingPointCoordinates` reads the real curated table checked into
 * `local/src/main/resources/sampling_points_ba.json` (MIP-0031 §5/§8 — every entry sourced, scoped
 * to points near `site/areas.json`'s `salvador` radius; see that file's own `source` field per
 * entry for how each coordinate was obtained).
 */
class SamplingPointCoordinatesSpec extends munit.FunSuite:

  test("a point code present in the curated table resolves to its real coordinate") {
    val porto = SamplingPointCoordinates.lookup("SSA PB 100")
    assert(porto.isDefined, "expected SSA PB 100 (Porto da Barra) to be in the curated table")
    val coords = porto.get
    // Porto da Barra sits just inside Salvador's bay-mouth peninsula.
    assert(coords.lat < -12.9 && coords.lat > -13.1)
    assert(coords.lon < -38.4 && coords.lon > -38.6)
  }

  test("a point code absent from the curated table is dropped, never defaulted") {
    assertEquals(SamplingPointCoordinates.lookup("SSA ZZ 999"), None)
  }

  test("two distinct monitoring points on the same real beach both resolve") {
    // SSA ON 100 / SSA ON 200 are INEMA's two Ondina points; the table intentionally shares one
    // real OSM beach coordinate between them (documented per-entry in the resource's `source`
    // field) rather than fabricating a second, unverified position.
    val on100 = SamplingPointCoordinates.lookup("SSA ON 100")
    val on200 = SamplingPointCoordinates.lookup("SSA ON 200")
    assert(on100.isDefined && on200.isDefined)
    assertEquals(on100, on200)
  }

  test("every entry in the curated resource is reachable through lookup") {
    val fixture = getClass.getClassLoader.getResourceAsStream("sampling_points_ba.json")
    val text =
      try scala.io.Source.fromInputStream(fixture, "UTF-8").mkString
      finally fixture.close()
    val codes = marola.json.JsonValue.parse(text).arr.flatMap(_("code").str)
    assert(codes.nonEmpty)
    assert(codes.forall(SamplingPointCoordinates.lookup(_).isDefined))
  }

end SamplingPointCoordinatesSpec
