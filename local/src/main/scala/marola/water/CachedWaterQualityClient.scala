package marola.water

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.LocalDate

import scala.util.Try

import kyo.*

import marola.json.JsonValue
import marola.model.Coordinates

/**
 * Serves the last successful fetch when the agency cannot be reached.
 *
 * The agencies go down. IMA/SC spent 2026-09-08 serving a self-signed certificate on its production
 * host, and every floripa beach on marola.dev read "no data" for hours. Nothing about the *data*
 * had changed — only our ability to re-download it.
 *
 * Serving the cache is not serving stale data: `WaterQuality.fresh` already ages samples out at 45
 * days on the agency's own sample dates, which do not move because our fetch failed. A bulletin
 * downloaded yesterday is exactly as valid today as one downloaded a minute ago. When the cached
 * samples genuinely age out, the existing rule turns them into "no data" without help from here.
 *
 * Writes only on a non-empty fetch: an agency that answers with nothing must not erase a good
 * cache.
 */
final class CachedWaterQualityClient(inner: WaterQualityClient, cacheFile: Path)
    extends WaterQualityClient:

  def name: String = inner.name

  def samplingPoints: List[SamplingPoint] < Sync =
    Abort.run(Abort.catching[Throwable](inner.samplingPoints)).map {
      case Result.Success(points) if points.nonEmpty =>
        CachedWaterQualityClient.write(cacheFile, points)
        points
      case Result.Success(_) => fallback("returned no points")
      case other             => fallback(s"failed ($other)")
    }

  private def fallback(why: String): List[SamplingPoint] < Sync =
    Sync.defer {
      val cached = CachedWaterQualityClient.read(cacheFile)
      if cached.isEmpty then
        java.lang.System.err.println(s"water: $name $why and no cache at $cacheFile")
      else
        java.lang.System.err.println(
          s"water: $name $why — serving ${cached.size} cached points from $cacheFile " +
            "(sample dates unchanged; the 45-day freshness rule still applies)"
        )
      cached
    }

object CachedWaterQualityClient:

  /** One file per provider, named for it, so two providers never overwrite each other. */
  def fileFor(dir: Path, providerName: String): Path =
    dir.resolve(providerName.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase + ".json")

  def encode(points: List[SamplingPoint]): JsonValue =
    JsonValue.obj(
      "version" -> JsonValue.num(1),
      "points" -> JsonValue.arr(
        points.map(p =>
          JsonValue.obj(
            "id" -> JsonValue.str(p.id),
            "beach_name" -> JsonValue.str(p.beachName),
            "point_name" -> JsonValue.str(p.pointName),
            "location" -> JsonValue.str(p.location),
            "lat" -> JsonValue.num(p.coordinates.lat),
            "lon" -> JsonValue.num(p.coordinates.lon),
            "samples" -> JsonValue.arr(
              p.samples.map(s =>
                JsonValue.obj(
                  "sampled_on" -> JsonValue.str(s.sampledOn.toString),
                  "condition" -> JsonValue.str(s.condition.label),
                  "rain" -> s.rain.map(JsonValue.str).getOrElse(JsonValue.JNull),
                  "enterococci_per_100ml" -> s.enterococciPer100ml
                    .map(v => JsonValue.num(v.toDouble))
                    .getOrElse(JsonValue.JNull),
                  "water_temp_c" -> s.waterTempC.map(JsonValue.num).getOrElse(JsonValue.JNull)
                )
              )*
            )
          )
        )*
      )
    )

  /**
   * A malformed or partial cache yields the points it can read, never an exception: a broken cache
   * must degrade to "no data", exactly as a failed fetch does.
   */
  def decode(json: JsonValue): List[SamplingPoint] =
    json("points").arr.toList.flatMap { p =>
      for
        id <- p("id").str
        beach <- p("beach_name").str
        point <- p("point_name").str
        lat <- p("lat").num
        lon <- p("lon").num
      yield SamplingPoint(
        id,
        beach,
        point,
        p("location").str.getOrElse(""),
        Coordinates(lat, lon),
        p("samples").arr.toList.flatMap { s =>
          for
            on <- s("sampled_on").str.flatMap(d => Try(LocalDate.parse(d)).toOption)
            cond <- s("condition").str.flatMap(BathingCondition.fromLabel)
          yield WaterSample(
            on,
            cond,
            s("rain").str,
            s("enterococci_per_100ml").num.map(_.toInt),
            s("water_temp_c").num
          )
        }
      )
    }

  def read(file: Path): List[SamplingPoint] =
    if !Files.isRegularFile(file) then Nil
    else
      Try(decode(JsonValue.parse(Files.readString(file, StandardCharsets.UTF_8))))
        .getOrElse(Nil)

  def write(file: Path, points: List[SamplingPoint]): Unit =
    val _ = Try {
      Option(file.getParent).foreach(Files.createDirectories(_))
      Files.writeString(file, encode(points).render, StandardCharsets.UTF_8)
    }
