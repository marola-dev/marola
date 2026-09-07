package marola.water

import marola.model.Coordinates

/**
 * End-to-end from the real fixture PDF (`InemaPdfParserSpec`'s own fixture) to
 * `List[SamplingPoint]` — mirrors `ImaScWaterQualityClientSpec`'s shape.
 */
class InemaBaWaterQualityClientSpec extends munit.FunSuite:

  private val fixture: Array[Byte] =
    val stream = getClass.getClassLoader.getResourceAsStream("inema-boletim-salvador-13-2025.pdf")
    try stream.readAllBytes()
    finally stream.close()

  private val points = InemaBaWaterQualityClient.toSamplingPoints(fixture)

  test("a point present in the curated coordinate table resolves to a real SamplingPoint") {
    val p = points.find(_.id == "SSA IN 100").getOrElse(fail("no SSA IN 100"))
    assertEquals(p.beachName, "São Tomé de Paripe")
    assertEquals(p.latest.map(_.condition), Some(BathingCondition.Proper))
  }

  test("a point absent from the curated table is dropped, not defaulted") {
    // The fixture parses 38 rows (InemaPdfParserSpec); the curated table only covers 28 — some
    // real fixture rows must be missing from the output, not silently coordinate-guessed.
    assert(
      points.size < 38,
      s"expected fewer than 38 points (some codes uncurated), got ${points.size}"
    )
    assert(points.size > 0, "expected at least one real match")
  }

  test("coversOrigin: Salvador yes, Florianópolis no") {
    assert(InemaBaWaterQualityClient.coversOrigin(Coordinates(-12.9777, -38.5016)))
    assert(!InemaBaWaterQualityClient.coversOrigin(Coordinates(-27.6733, -48.4700)))
  }
