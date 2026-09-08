package marola.water

import java.nio.file.{Files, Path}
import java.time.LocalDate

import kyo.*

import marola.model.Coordinates

/**
 * The outage this exists for: IMA/SC served a self-signed certificate on 2026-09-08 and every
 * floripa beach read "no data" while the agency's own sample dates had not moved at all.
 */
class CachedWaterQualityClientSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private val sample =
    WaterSample(
      LocalDate.of(2026, 8, 25),
      BathingCondition.Proper,
      Some("Ausente"),
      Some(20),
      Some(19.0)
    )
  private val point =
    SamplingPoint(
      "P06",
      "Praia do Campeche",
      "Ponto 06",
      "Em frente à Rua X",
      Coordinates(-27.67, -48.47),
      List(sample)
    )

  private def tmpFile(): Path =
    Files.createTempDirectory("marola-water-cache").resolve("ima-sc.json")

  private class Fixed(points: List[SamplingPoint]) extends WaterQualityClient:
    def name: String = "IMA/SC"
    def samplingPoints: List[SamplingPoint] < Sync = points

  private class Broken extends WaterQualityClient:
    def name: String = "IMA/SC"
    def samplingPoints: List[SamplingPoint] < Sync =
      Sync.defer(throw new javax.net.ssl.SSLHandshakeException("PKIX path building failed"))

  private def run(c: WaterQualityClient): List[SamplingPoint] =
    Sync.Unsafe.evalOrThrow(c.samplingPoints)

  test("a successful fetch is served and written to the cache") {
    val file = tmpFile()
    val got = run(CachedWaterQualityClient(Fixed(List(point)), file))
    assertEquals(got.map(_.id), List("P06"))
    assert(Files.isRegularFile(file), "the fetch should have been cached")
  }

  test("a TLS failure serves the cache instead of blanking every beach") {
    val file = tmpFile()
    val _ = run(CachedWaterQualityClient(Fixed(List(point)), file))
    val got = run(CachedWaterQualityClient(Broken(), file))
    assertEquals(got.map(_.id), List("P06"))
    assertEquals(got.head.samples.map(_.condition), List(BathingCondition.Proper))
    // The agency's sample date is what freshness is judged on, and it must survive the round trip
    // unchanged — otherwise the cache would quietly reset the 45-day clock.
    assertEquals(got.head.samples.map(_.sampledOn), List(LocalDate.of(2026, 8, 25)))
  }

  test("an empty fetch does not erase a good cache") {
    val file = tmpFile()
    val _ = run(CachedWaterQualityClient(Fixed(List(point)), file))
    val got = run(CachedWaterQualityClient(Fixed(Nil), file))
    assertEquals(got.map(_.id), List("P06"), "an agency answering with nothing must not wipe it")
  }

  test("a failure with no cache yields nothing, never an exception") {
    assertEquals(run(CachedWaterQualityClient(Broken(), tmpFile())), Nil)
  }

  test("a corrupt cache degrades to no data rather than throwing") {
    val file = tmpFile()
    Files.createDirectories(file.getParent)
    Files.writeString(file, "{ this is not json")
    assertEquals(run(CachedWaterQualityClient(Broken(), file)), Nil)
  }

  test("every field a verdict depends on survives encode/decode") {
    val back = CachedWaterQualityClient.decode(CachedWaterQualityClient.encode(List(point)))
    assertEquals(back.size, 1)
    val p = back.head
    assertEquals(
      (p.id, p.beachName, p.pointName, p.location),
      ("P06", "Praia do Campeche", "Ponto 06", "Em frente à Rua X")
    )
    assertEquals((p.coordinates.lat, p.coordinates.lon), (-27.67, -48.47))
    assertEquals(p.samples.head.rain, Some("Ausente"))
    assertEquals(p.samples.head.enterococciPer100ml, Some(20))
    assertEquals(p.samples.head.waterTempC, Some(19.0))
  }

  test("the condition is stored by explicit label, not by ordinal or toString") {
    val json = CachedWaterQualityClient.encode(List(point)).render
    assert(json.contains("\"propria\""), json)
    assert(!json.contains("\"Proper\""), "a renamed enum case must not orphan the cache")
    assertEquals(BathingCondition.fromLabel("impropria"), Some(BathingCondition.Improper))
    assertEquals(BathingCondition.fromLabel("nonsense"), None)
  }

  test("each provider caches to its own file") {
    val dir = Files.createTempDirectory("marola-water-multi")
    val ima = CachedWaterQualityClient.fileFor(dir, "IMA/SC")
    val inea = CachedWaterQualityClient.fileFor(dir, "INEA/RJ")
    assertNotEquals(ima, inea)
    assertEquals(ima.getFileName.toString, "ima-sc.json")
  }
end CachedWaterQualityClientSpec
