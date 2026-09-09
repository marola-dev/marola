package marola.beaches

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.Try

import marola.json.JsonValue
import marola.model.{Beach, Coordinates}

/**
 * A committed list of beaches for one (origin, radius, limit), so the site build does not ask
 * Overpass to rediscover the same coastline eight times a day.
 *
 * OSM beach polygons change on the order of years; the site rebuilds every three hours. Querying a
 * free shared API for effectively static data is both wasteful and the single most common way the
 * build dies — `HttpConnectTimeoutException` from overpass-api.de fails the whole run, and then no
 * board updates at all, water or otherwise.
 *
 * So: fetch once, locally, and commit the result. CI reads the file and never calls Overpass.
 * Refresh deliberately (`just beaches-refresh`), not on every build.
 */
object BeachSnapshot:

  /**
   * Stable, human-readable, and unique per query — the file name is the cache key, so a changed
   * radius or limit cannot silently reuse the wrong list.
   */
  def key(origin: Coordinates, radiusKm: Double, limit: Int): String =
    f"${origin.lat}%.4f_${origin.lon}%.4f_r${radiusKm}%.1f_n$limit".replace('-', 'm')

  def fileFor(dir: Path, origin: Coordinates, radiusKm: Double, limit: Int): Path =
    dir.resolve(key(origin, radiusKm, limit) + ".json")

  def encode(beaches: List[Beach]): JsonValue =
    JsonValue.obj(
      "version" -> JsonValue.num(1),
      "beaches" -> JsonValue.arr(
        beaches.map(b =>
          JsonValue.obj(
            "name" -> JsonValue.str(b.name),
            "lat" -> JsonValue.num(b.coordinates.lat),
            "lon" -> JsonValue.num(b.coordinates.lon),
            "distance_km" -> JsonValue.num(b.distanceKm)
          )
        )*
      )
    )

  def decode(json: JsonValue): List[Beach] =
    json("beaches").arr.toList.flatMap { b =>
      for
        name <- b("name").str
        lat <- b("lat").num
        lon <- b("lon").num
      yield Beach(name, Coordinates(lat, lon), b("distance_km").num.getOrElse(0.0))
    }

  /**
   * Nil for a missing or unreadable file: a broken snapshot must fall through to Overpass, not fail
   * the build.
   */
  def read(file: Path): List[Beach] =
    if !Files.isRegularFile(file) then Nil
    else Try(decode(JsonValue.parse(Files.readString(file, StandardCharsets.UTF_8)))).getOrElse(Nil)

  def write(file: Path, beaches: List[Beach]): Unit =
    val _ = Try {
      Option(file.getParent).foreach(Files.createDirectories(_))
      Files.writeString(file, encode(beaches).render, StandardCharsets.UTF_8)
    }
