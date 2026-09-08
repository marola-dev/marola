package marola.trails

import marola.json.JsonValue
import marola.model.{Beach, Coordinates}

/**
 * MIP-0030 §7: `TrailFinder.parse` against a real Overpass response, captured live 2026-09-07 by
 * replaying §4.1's exact third query — `[out:json][timeout:60]; way["natural"="beach"]["name"]
 * (around:20000,-27.6733,-48.4700)->.beaches; ...` (plus `.lakes out center;`, this
 * implementation's own addition — see `TrailFinder.nearby`'s doc comment) — against the real
 * `https://overpass-api.de/api/interpreter`, saved verbatim as
 * `core/src/test/resources/fixtures/overpass-trails-floripa.json`.
 */
class TrailFinderSpec extends munit.FunSuite:

  private def resource(name: String): String =
    val s = getClass.getClassLoader.getResourceAsStream(name)
    if s == null then throw new IllegalArgumentException(s"missing fixture $name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()

  private lazy val root: JsonValue =
    JsonValue.parse(resource("fixtures/overpass-trails-floripa.json"))

  private def trailsOf(beaches: List[Beach] = Nil): List[Trail] = TrailFinder.parse(root, beaches)

  private def find(ts: List[Trail], name: String): Trail =
    ts.find(_.name == name).getOrElse(fail(s"$name not in parsed trails: ${ts.map(_.name)}"))

  test("a real single-segment trail: name, length, geometry, no difficulty/surface (both absent)") {
    val t = find(trailsOf(), "Trilha da Lagoinha do Leste")
    assertEqualsDouble(t.lengthKm, 2.1149, 0.01)
    assertEquals(t.geometry.size, 92)
    assertEquals(t.difficulty, None)
    assertEquals(t.surface, None)
  }

  test("a real trail carries its OSM surface tag verbatim when present") {
    // This exact live capture never returned a sac_scale tag (§4.1's own combined-query run found
    // none either — real trail data quality varies, §8); surface is present here.
    val t = find(trailsOf(), "Caminho da Costa da Lagoa ao Canto dos Araçás")
    assertEquals(t.surface, Some("paving_stones"))
    assertEquals(t.difficulty, None)
  }

  test(
    "MIP-0030 §8: same-named segments merge into one Trail, geometry concatenated, length summed"
  ) {
    val ts = trailsOf()
    // 20 raw OSM ways, 10 unique names -> exactly 10 Trails, not 20 disconnected lines.
    assertEquals(ts.size, 10)
    val a = find(ts, "Caminho da Costa da Lagoa ao Canto dos Araçás")
    assertEquals(a.geometry.size, 353) // 8 segments' geometries concatenated
    assertEqualsDouble(a.lengthKm, 7.2564, 0.01) // sum of each segment's own length, not the
    // concatenation's (avoids a spurious jump between two segments that don't share an endpoint).
    val b = find(ts, "Trilha Parque Estadual do Rio Vermelho")
    assertEquals(b.geometry.size, 76)
    assertEqualsDouble(b.lengthKm, 3.8369, 0.01)
  }

  test(
    "nearBeach: a beach within 500m of the trail's real geometry is attached with its distance"
  ) {
    val lastPoint = find(trailsOf(), "Trilha da Lagoinha do Leste").geometry.last
    val beach = Beach("Test Beach", lastPoint, 0.0)
    val t = find(trailsOf(List(beach)), "Trilha da Lagoinha do Leste")
    assertEquals(t.nearBeach.map(_._1), Some("Test Beach"))
    assertEqualsDouble(t.nearBeach.map(_._2).getOrElse(-1.0), 0.0, 0.01)
  }

  test("nearBeach is None when no beach is within 500m") {
    val farBeach = Beach("Far Away", Coordinates(-10.0, -30.0), 0.0)
    val t = find(trailsOf(List(farBeach)), "Trilha da Lagoinha do Leste")
    assertEquals(t.nearBeach, None)
  }

  test(
    "this capture's own named lakes are all >500m from every trail: nearLake stays honestly None"
  ) {
    // Not a fabricated fixture edit — the real response names 10 lakes; the closest to any real
    // trail here is Lagoa da Conceição, ~0.56km from "Caminho da Costa da Lagoa ao Canto dos
    // Araçás" — still over TrailFinder.NearRadiusKm.
    trailsOf().foreach(t => assertEquals(t.nearLake, None, t.name))
  }

  // --- synthetic: the merge algorithm in isolation, separate from the live fixture above
  // ---------.

  test(
    "synthetic: merge concatenates geometry, sums length, keeps the first non-None tag, and finds a named lake anchor"
  ) {
    val syntheticRoot = JsonValue.parse(
      """{"elements": [
        |  {"type":"way","tags":{"highway":"path","name":"Synthetic Trail"},
        |   "geometry":[{"lat":-10.0,"lon":-20.0},{"lat":-10.001,"lon":-20.0}]},
        |  {"type":"way","tags":{"highway":"path","name":"Synthetic Trail","sac_scale":"hiking","surface":"dirt"},
        |   "geometry":[{"lat":-10.001,"lon":-20.0},{"lat":-10.002,"lon":-20.0}]},
        |  {"type":"way","tags":{"natural":"water","water":"lake","name":"Synthetic Lake"},
        |   "center":{"lat":-10.001,"lon":-20.0005}}
        |]}""".stripMargin
    )
    val ts = TrailFinder.parse(syntheticRoot, Nil)
    assertEquals(ts.size, 1)
    val t = ts.head
    assertEquals(t.name, "Synthetic Trail")
    assertEquals(t.geometry.size, 4) // both segments' points concatenated, group order
    assertEquals(t.difficulty, Some("hiking")) // first segment has none, second does
    assertEquals(t.surface, Some("dirt"))
    assert(t.lengthKm > 0.0)
    assertEquals(t.nearLake.map(_._1), Some("Synthetic Lake"))
  }

  test(
    "synthetic: an unnamed lake anchor can't be reported as nearLake, even if it's the closest"
  ) {
    val syntheticRoot = JsonValue.parse(
      """{"elements": [
        |  {"type":"way","tags":{"highway":"track","name":"Solo Trail"},
        |   "geometry":[{"lat":5.0,"lon":10.0},{"lat":5.001,"lon":10.0}]},
        |  {"type":"way","tags":{"natural":"water","water":"pond"},
        |   "center":{"lat":5.001,"lon":10.0005}}
        |]}""".stripMargin
    )
    val t = TrailFinder.parse(syntheticRoot, Nil).head
    assertEquals(t.nearLake, None)
    assertEquals(t.nearBeach, None)
  }

end TrailFinderSpec
