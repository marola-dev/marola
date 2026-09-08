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
 * boards, `latest.json`, the areas index, and copies the static page over.
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

  private def build(out: Path, static: Path): List[Path] =
    Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        SiteBuilder.build(
          List(floripa),
          out,
          static,
          water = _ => Some(ImaScWaterQualityClient()),
          now = now
        )
      )
    }

  test("build writes both days, latest.json and the areas index, and copies site/static") {
    val out = tmpDir("marola-site-out")
    val static = tmpDir("marola-site-static")
    Files.writeString(static.resolve("index.html"), "<html>marola</html>")
    Files.createDirectories(static.resolve("vendor"))
    Files.writeString(static.resolve("vendor").resolve("leaflet.js"), "// leaflet")

    val written = build(out, static)

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

    assertEquals(Files.readString(out.resolve("index.html")), "<html>marola</html>")
    assert(Files.exists(out.resolve("vendor").resolve("leaflet.js")))
  }

  test("build with no water provider still writes a board that says 'no data'") {
    val out = tmpDir("marola-site-nowater")
    val static = tmpDir("marola-site-static-empty")
    val written = Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        SiteBuilder.build(List(floripa), out, static, water = _ => None, now = now)
      )
    }
    assert(written.nonEmpty)
    val board = JsonValue.parse(Files.readString(out.resolve("data/floripa/2026-09-06.json")))
    assertEquals(board("sources")("water"), JsonValue.JNull)
    assert(board("beaches").arr.forall(_("water")("summary").str.contains("no data")))
  }

  test("build with two areas keeps each its own directory, zone and water-provider selection") {
    val out = tmpDir("marola-site-multi")
    val static = tmpDir("marola-site-static-multi")
    // The same per-origin selection `AppConfig.waterQualityClient`'s Auto mode makes in
    // production: Santa Catarina (floripa) gets IMA/SC, everywhere else (salvador) gets none.
    val water = (origin: marola.model.Coordinates) =>
      if ImaScWaterQualityClient.coversOrigin(origin) then Some(ImaScWaterQualityClient()) else None
    val written = Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        SiteBuilder.build(List(floripa, salvador), out, static, water = water, now = now)
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

  test("the real site/static (index.html, app.js, style.css, vendored Leaflet) lands in dist") {
    val out = tmpDir("marola-site-real-static")
    val written = build(out, SiteBuilderSpec.repoFile("site/static"))
    List("index.html", "app.js", "style.css", "vendor/leaflet.js", "vendor/leaflet.css").foreach(
      f => assert(Files.exists(out.resolve(f)), s"missing $f in ${written.mkString(", ")}")
    )
    val html = Files.readString(out.resolve("index.html"))
    assert(html.contains("vendor/leaflet.js") && html.contains("app.js"), html)
    // the page reads what the builder writes: the same relative paths.
    val js = Files.readString(out.resolve("app.js"))
    assert(js.contains("data/areas.json") && js.contains("latest.json"), "app.js data paths")
  }

  test("site/areas.json parses: slug ids, a zone, tiles, and Florianópolis first") {
    val areas = SiteBuilder.Areas.load(SiteBuilderSpec.repoFile("site/areas.json"))
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

object SiteBuilderSpec:
  def repoFile(relative: String): Path =
    Iterator
      .iterate(java.nio.file.Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(Files.exists(_))
      .getOrElse(throw new IllegalStateException(s"$relative not found above the cwd"))
