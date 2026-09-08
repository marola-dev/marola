package marola.site

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.{OffsetDateTime, ZoneId}

import scala.jdk.CollectionConverters.*
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
 * pointing at both, `data/areas.json` listing every area for the page's area switch, and a copy of
 * `site/static/` (the page itself).
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
  val DefaultStatic: Path = Path.of("site", "static")

  private val sources = (water: Option[WaterQualityClient]) =>
    Board.Sources(
      beaches = "OpenStreetMap/Overpass",
      forecast = "Open-Meteo",
      water = water.map(_.name)
    )

  /** Builds every area into `out` and returns the files written. */
  def build(
      areas: List[Area],
      out: Path,
      static: Path,
      water: Coordinates => Option[WaterQualityClient],
      now: OffsetDateTime,
      distanceRefiner: Option[(Coordinates, Coordinates) => Double < Sync] = None,
      accessibility: Option[AccessibilityClient] = None
  ): List[Path] < Sync =
    for
      boards <- traverse(areas)(a =>
        buildArea(a, out, water(a.origin), now, distanceRefiner, accessibility)
      )
      index <- Sync.defer(writeAreasIndex(areas, out))
      copied <- Sync.defer(copyStatic(static, out))
    yield boards.flatten ++ (index :: copied)

  private def buildArea(
      area: Area,
      out: Path,
      water: Option[WaterQualityClient],
      now: OffsetDateTime,
      distanceRefiner: Option[(Coordinates, Coordinates) => Double < Sync],
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
        distanceRefiner,
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
      val entries = SeaLore.loadDefault()
      val regions = SeaLore.regionTagsFor(area.origin)
      val dayFiles = days.map { day =>
        val lore = SeaLore.pick(entries, day, area.name, regions)
        val board =
          Board.build(area.id, day, today, localNow, scored, lore, sources(water), trails)
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

  /** Recursive copy of `site/static/` over `out/` (missing source = nothing to copy). */
  private def copyStatic(static: Path, out: Path): List[Path] =
    if !Files.isDirectory(static) then Nil
    else
      Files.createDirectories(out)
      val stream = Files.walk(static)
      try
        stream.iterator.asScala.toList.filter(Files.isRegularFile(_)).map { src =>
          val dest = out.resolve(static.relativize(src).toString)
          Files.createDirectories(dest.getParent)
          Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
        }
      finally stream.close()

  private def write(path: Path, json: JsonValue): Path =
    Files.writeString(path, json.render, StandardCharsets.UTF_8)

  private def traverse[A, B](items: List[A])(f: A => List[B] < Sync): List[List[B]] < Sync =
    items match
      case Nil => Nil
      case head :: tail =>
        for
          b <- f(head)
          bs <- traverse(tail)(f)
        yield b :: bs
