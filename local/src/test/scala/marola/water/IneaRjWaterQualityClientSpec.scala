package marola.water

import marola.model.Coordinates

/**
 * End-to-end from the real fixture PDF (`IneaPdfParserSpec`'s own fixture) to `List[SamplingPoint]`
 * — mirrors `ImaScWaterQualityClientSpec`'s shape.
 */
class IneaRjWaterQualityClientSpec extends munit.FunSuite:

  private val fixtureBytes: Array[Byte] =
    val stream = getClass.getClassLoader.getResourceAsStream(
      "inea-boletim-zona-sudoeste-sul-2026-06-17.pdf"
    )
    try stream.readAllBytes()
    finally stream.close()

  private val points = IneaRjWaterQualityClient.toSamplingPoints(fixtureBytes)

  test("a point present in the curated coordinate table resolves to a real SamplingPoint") {
    val p = points.find(_.id == "BD05").getOrElse(fail("no BD05"))
    assert(p.beachName.nonEmpty)
  }

  test("a point absent from the curated table is dropped, not defaulted") {
    // The fixture parses 39 rows (IneaPdfParserSpec); the curated table only covers 29 — some
    // real fixture rows must be missing from the output, not silently coordinate-guessed.
    assert(
      points.size < 39,
      s"expected fewer than 39 points (some codes uncurated), got ${points.size}"
    )
    assert(points.size > 0, "expected at least one real match")
  }

  test("coversOrigin: Rio de Janeiro yes, Salvador no") {
    assert(IneaRjWaterQualityClient.coversOrigin(Coordinates(-22.9878, -43.1913)))
    assert(!IneaRjWaterQualityClient.coversOrigin(Coordinates(-12.9777, -38.5016)))
  }
