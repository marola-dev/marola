package marola.site

import java.nio.file.{Files, Path}
import java.time.{OffsetDateTime, ZoneId, ZoneOffset}

import kyo.*

import marola.Fixtures
import marola.http.Http
import marola.json.JsonValue
import marola.water.ImaScWaterQualityClient

/**
 * MIP-0005 §7 "static site smoke": a build from fixtures, no network, writes today's and tomorrow's
 * boards, `latest.json` and the areas index. Board data only (MIP-0070 §5.4): the static page is a
 * separate copy the caller (`site.yml`, `just site-build`) makes, not `SiteBuilder.build`.
 */
class SiteBuilderSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  // The fixture transport serves Campeche's Overpass answer for any origin; keep the golden
  // origin.
  private val floripa = SiteBuilder.Area(
    id = "floripa",
    name = "Florianópolis",
    lat = -27.6733,
    lon = -48.4700,
    radiusKm = 15.0,
    beachLimit = 6,
    zone = ZoneId.of("America/Sao_Paulo"),
    tiles = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    tilesAttribution = "© OpenStreetMap contributors"
  )
  // A second area outside Santa Catarina's water-quality coverage (`ImaScWaterQualityClient
  // .coversOrigin`), in a different IANA zone that shares today's UTC offset with `floripa` — the
  // same shape as `site/areas.json`'s real "salvador" entry (MIP-0005's "rio" proved one extra
  // area; this proves a second one alongside it, each keeping its own water-provider selection
  // and zone).
  private val salvador = SiteBuilder.Area(
    id = "salvador",
    name = "Salvador, BA",
    lat = -12.9777,
    lon = -38.5016,
    radiusKm = 25.0,
    beachLimit = 6,
    zone = ZoneId.of("America/Bahia"),
    tiles = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    tilesAttribution = "© OpenStreetMap contributors"
  )
  private val now = OffsetDateTime.of(2026, 9, 5, 18, 0, 0, 0, ZoneOffset.ofHours(-3))

  private def tmpDir(prefix: String): Path = Files.createTempDirectory(prefix)

  private def resource(name: String): String =
    val s = getClass.getClassLoader.getResourceAsStream(name)
    if s == null then throw new IllegalArgumentException(s"missing fixture $name")
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()

  /**
   * An agency that is reachable and has nothing to say — IMA/SC's shape while its production host
   * served a self-signed certificate, and INEA/RJ's once its hardcoded bulletin aged out.
   */
  private object SilentProvider extends marola.water.WaterQualityClient:
    def name: String = "IMA/SC"
    def samplingPoints: List[marola.water.SamplingPoint] < Sync = Nil

  private def buildWith(out: Path, client: marola.water.WaterQualityClient): List[Path] =
    Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        SiteBuilder.build(List(floripa), out, water = _ => Some(client), now = now)
      )
    }

  private def build(out: Path): List[Path] =
    Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        SiteBuilder.build(
          List(floripa),
          out,
          water = _ => Some(ImaScWaterQualityClient()),
          now = now
        )
      )
    }

  test("a provider that returns nothing is named as such, never claimed as a working source") {
    val out = tmpDir("marola-site-out")

    val _ = buildWith(out, SilentProvider)
    val board = JsonValue.parse(
      Files.readString(out.resolve("data").resolve("floripa").resolve("2026-09-06.json"))
    )
    assertEquals(board("sources")("water").str, Some("IMA/SC (no data returned)"))
    // The provider name still comes first, so app.js's SOURCE_LINKS lookup (split on the first
    // space) resolves the same link it always did.
    assert(board("sources")("water").str.exists(_.startsWith("IMA/SC")))
    assert(
      board("beaches").arr.forall(_("water")("summary").str.contains("no data")),
      "every beach should read 'no data' when the provider returned nothing"
    )
  }

  test("build writes both days, latest.json and the areas index") {
    val out = tmpDir("marola-site-out")

    val written = build(out)

    val data = out.resolve("data").resolve("floripa")
    List("2026-09-05.json", "2026-09-06.json", "latest.json").foreach(f =>
      assert(Files.exists(data.resolve(f)), s"missing $f in ${written.mkString(", ")}")
    )
    val latest = JsonValue.parse(Files.readString(data.resolve("latest.json")))
    assertEquals(latest("area").str, Some("floripa"))
    assertEquals(latest("today").str, Some("2026-09-05"))
    assertEquals(latest("generated_at").str, Some("2026-09-05T18:00:00-03:00"))
    assertEquals(latest("days").arr.flatMap(_("day").str), Vector("2026-09-05", "2026-09-06"))
    assertEquals(
      latest("days").arr.flatMap(_("file").str),
      Vector("2026-09-05.json", "2026-09-06.json")
    )

    val tomorrow = JsonValue.parse(Files.readString(data.resolve("2026-09-06.json")))
    assertEquals(tomorrow("day").str, Some("2026-09-06"))
    assertEquals(tomorrow("sources")("water").str, Some(ImaScWaterQualityClient().name))
    assert(tomorrow("beaches").arr.exists(_("name").str.contains("Praia do Campeche")))

    val areas = JsonValue.parse(Files.readString(out.resolve("data").resolve("areas.json")))
    val entry = areas("areas").arr.head
    assertEquals(entry("id").str, Some("floripa"))
    assertEquals(entry("name").str, Some("Florianópolis"))
    assertEquals(entry("tiles").str, Some(floripa.tiles))
    assertEquals(entry("latest").str, Some("data/floripa/latest.json"))
  }

  test("build with no water provider still writes a board that says 'no data'") {
    val out = tmpDir("marola-site-nowater")
    val written = Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        SiteBuilder.build(List(floripa), out, water = _ => None, now = now)
      )
    }
    assert(written.nonEmpty)
    val board = JsonValue.parse(Files.readString(out.resolve("data/floripa/2026-09-06.json")))
    assertEquals(board("sources")("water"), JsonValue.JNull)
    assert(board("beaches").arr.forall(_("water")("summary").str.contains("no data")))
  }

  test("build with two areas keeps each its own directory, zone and water-provider selection") {
    val out = tmpDir("marola-site-multi")
    // The same per-origin selection `AppConfig.waterQualityClient`'s Auto mode makes in
    // production: Santa Catarina (floripa) gets IMA/SC, everywhere else (salvador) gets none.
    val water = (origin: marola.model.Coordinates) =>
      if ImaScWaterQualityClient.coversOrigin(origin) then Some(ImaScWaterQualityClient()) else None
    val written = Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        SiteBuilder.build(List(floripa, salvador), out, water = water, now = now)
      )
    }
    assert(written.nonEmpty)

    val floripaBoard =
      JsonValue.parse(Files.readString(out.resolve("data/floripa/2026-09-06.json")))
    assertEquals(floripaBoard("sources")("water").str, Some(ImaScWaterQualityClient().name))

    val salvadorBoard =
      JsonValue.parse(Files.readString(out.resolve("data/salvador/2026-09-06.json")))
    assertEquals(salvadorBoard("sources")("water"), JsonValue.JNull)
    assert(salvadorBoard("beaches").arr.forall(_("water")("summary").str.contains("no data")))

    val areas = JsonValue.parse(Files.readString(out.resolve("data").resolve("areas.json")))
    assertEquals(areas("areas").arr.flatMap(_("id").str), Vector("floripa", "salvador"))
  }

  test("--site writes data only: no index.html or other static file in the output") {
    val out = tmpDir("marola-site-data-only")
    val written = build(out)
    assert(written.nonEmpty)
    assert(!Files.exists(out.resolve("index.html")))
    assert(!Files.exists(out.resolve("vendor")))
    assert(Files.isDirectory(out.resolve("data")))
  }

  test(
    "Areas.load reads the given path, not a site/areas.json found by walking up from the cwd"
  ) {
    // A checked-in copy (`cli/src/test/resources/site/areas.json`) written to an arbitrary path,
    // unrelated to any repo layout — proving the loader only ever reads the path it is given
    // (MIP-0070 §5.4: the site passes its own file, the app never walks the tree looking for one).
    val fixture = tmpDir("marola-site-areas-fixture").resolve("wherever.json")
    Files.writeString(fixture, resource("site/areas.json"))

    val areas = SiteBuilder.Areas.load(fixture)
    assert(areas.nonEmpty)
    areas.foreach { a =>
      assert(a.id.matches("[a-z0-9-]+"), a.id)
      assert(a.radiusKm > 0 && a.beachLimit > 0, a.toString)
      assert(a.tiles.contains("{z}"), a.tiles)
    }
    assertEquals(areas.head.id, "floripa")
    assertEquals(areas.head.zone, ZoneId.of("America/Sao_Paulo"))
    // Bahia/Rio (the user-visible ask this covers): one area per state's main beach-rich metro,
    // same one-point-plus-radius shape as floripa/rio — not a whole-state coastal scan, matching
    // the existing design (MIP-0005.tasks.md decision 2 already scoped "rio" to the city, not the
    // state).
    assertEquals(areas.map(_.id), List("floripa", "rio", "salvador"))
    val salvador = areas.find(_.id == "salvador").get
    assertEquals(salvador.name, "Salvador, BA")
    assertEquals(salvador.zone, ZoneId.of("America/Bahia"))
    assertEquals(SiteBuilder.Areas.parse("[]"), Nil)
    // an entry missing a required field is dropped, not a crash.
    assertEquals(SiteBuilder.Areas.parse("""[{"id": "x", "name": "X"}]"""), Nil)
  }

end SiteBuilderSpec
