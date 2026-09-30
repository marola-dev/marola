package marola.site

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.{OffsetDateTime, ZoneId}

import scala.util.Try

import kyo.*

import marola.Recommender
import marola.beaches.AccessibilityClient
import marola.json.JsonValue
import marola.lore.SeaLore
import marola.model.Coordinates
import marola.trails.TrailFinder
import marola.water.WaterQualityClient

/**
 * MIP-0005 §5.3: `just site-build` — run the pipeline once per *area* (not per user) and write the
 * boards the static map reads: `data/<area>/<day>.json` for today and tomorrow, `latest.json`
 * pointing at both, and `data/areas.json` listing every area for the page's area switch. Board data
 * only (MIP-0070 §5.4): the caller copies `site/static/` (the page itself) separately.
 */
object SiteBuilder:

  /** One entry of `site/areas.json`. */
  final case class Area(
      id: String,
      name: String,
      lat: Double,
      lon: Double,
      radiusKm: Double,
      beachLimit: Int,
      zone: ZoneId,
      tiles: String,
      tilesAttribution: String
  ):
    def origin: Coordinates = Coordinates(lat, lon)

  object Areas:
    val DefaultPath: Path = Path.of("site", "areas.json")

    def load(path: Path): List[Area] = parse(Files.readString(path, StandardCharsets.UTF_8))

    /** Entries missing a required field are dropped (the build prints what it built). */
    def parse(jsonText: String): List[Area] =
      JsonValue.parse(jsonText).arr.toList.flatMap { e =>
        for
          id <- e("id").str if id.matches("[a-z0-9-]+")
          name <- e("name").str
          lat <- e("lat").num
          lon <- e("lon").num
          radius <- e("radius_km").num
          limit <- e("beach_limit").num
          zone <- e("tz").str.flatMap(z => Try(ZoneId.of(z)).toOption)
          tiles <- e("tiles").str
        yield Area(
          id,
          name,
          lat,
          lon,
          radius,
          limit.toInt,
          zone,
          tiles,
          e("tiles_attribution").str.getOrElse("")
        )
      }

  val DefaultOut: Path = Path.of("site", "dist")

  /**
   * `gotData` comes from the scored beaches, not from which client was configured. Naming a
   * provider that returned nothing is what let marola.dev claim "IMA/SC" beside 80 beaches reading
   * "no data" — a visitor cannot tell that from an agency publishing nothing that week. The suffix
   * keeps the provider's name first, so app.js's SOURCE_LINKS lookup (which splits on the first
   * space) still resolves the link.
   */
  private val sources = (water: Option[WaterQualityClient], gotData: Boolean) =>
    Board.Sources(
      beaches = "OpenStreetMap/Overpass",
      forecast = "Open-Meteo",
      water = water.map(c => if gotData then c.name else s"${c.name} (no data returned)")
    )

  /**
   * Builds every area into `out` and returns the files written — board data only (MIP-0070 §5.4):
   * the static page is a separate copy the caller (`site.yml`, `just site-build`) makes itself.
   */
  def build(
      areas: List[Area],
      out: Path,
      water: Coordinates => Option[WaterQualityClient],
      now: OffsetDateTime,
      accessibility: Option[AccessibilityClient] = None
  ): List[Path] < Sync =
    for
      boards <- Kyo.foreach(areas)(a => buildArea(a, out, water(a.origin), now, accessibility))
      index <- Sync.defer(writeAreasIndex(areas, out))
    yield boards.flatten :+ index

  private def buildArea(
      area: Area,
      out: Path,
      water: Option[WaterQualityClient],
      now: OffsetDateTime,
      accessibility: Option[AccessibilityClient]
  ): List[Path] < Sync =
    val localNow = now.atZoneSameInstant(area.zone).toOffsetDateTime
    val today = localNow.toLocalDate
    val days = List(today, today.plusDays(1))
    for
      scored <- Recommender.scoreDays(
        area.origin,
        area.radiusKm,
        area.beachLimit,
        water,
        today = _ => today,
        days = days.size,
        accessibility = accessibility
      )
      // MIP-0030: one extra Overpass query per area per build, reusing the beaches `scoreDays`
      // already fetched (no second beach query) — the same trails feed both day files below, a
      // trail doesn't change per day.
      trails <- TrailFinder.nearbyOrEmpty(area.origin, area.radiusKm, scored.map(_.beach).distinct)
    yield
      val dir = out.resolve("data").resolve(area.id)
      Files.createDirectories(dir)
      val gotWater = scored.exists(_.waterQuality.exists(_.points.nonEmpty))
      val entries = SeaLore.loadDefault()
      val regions = SeaLore.regionTagsFor(area.origin)
      val dayFiles = days.map { day =>
        val lore = SeaLore.pick(entries, day, area.name, regions)
        val board =
          Board.build(area.id, day, today, localNow, scored, lore, sources(water, gotWater), trails)
        write(dir.resolve(s"$day.json"), board)
      }
      val latest = JsonValue.obj(
        "area" -> JsonValue.str(area.id),
        "today" -> JsonValue.str(today.toString),
        "generated_at" -> JsonValue.str(Board.stamp(localNow)),
        "days" -> JsonValue.arr(
          days.map(d =>
            JsonValue.obj("day" -> JsonValue.str(d.toString), "file" -> JsonValue.str(s"$d.json"))
          )*
        )
      )
      dayFiles :+ write(dir.resolve("latest.json"), latest)

  private def writeAreasIndex(areas: List[Area], out: Path): Path =
    Files.createDirectories(out.resolve("data"))
    write(
      out.resolve("data").resolve("areas.json"),
      JsonValue.obj(
        "areas" -> JsonValue.arr(
          areas.map(a =>
            JsonValue.obj(
              "id" -> JsonValue.str(a.id),
              "name" -> JsonValue.str(a.name),
              "lat" -> JsonValue.num(a.lat),
              "lon" -> JsonValue.num(a.lon),
              "radius_km" -> JsonValue.num(a.radiusKm),
              "tiles" -> JsonValue.str(a.tiles),
              "tiles_attribution" -> JsonValue.str(a.tilesAttribution),
              "latest" -> JsonValue.str(s"data/${a.id}/latest.json")
            )
          )*
        )
      )
    )

  private def write(path: Path, json: JsonValue): Path =
    Files.writeString(path, json.render, StandardCharsets.UTF_8)
