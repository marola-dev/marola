package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest

import marola.json.JsonValue

/** The disk side of MIP-0056 §5.1: raw bytes verbatim, written only when they differ. */
object RawStore:

  def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** True when the file was written; false when disk already held these exact bytes. */
  def write(file: Path, bytes: Array[Byte]): Boolean =
    if Files.isRegularFile(file) && java.util.Arrays.equals(Files.readAllBytes(file), bytes) then
      false
    else
      Option(file.getParent).foreach { dir =>
        val _ = Files.createDirectories(dir)
      }
      val tmp = Manifest.tempFile(file)
      val _ = Files.write(tmp, bytes)
      val _ =
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      true

  /**
   * `points.json`: sorted by point key, one point per line, §5.3's column names — the shape
   * `build.sql` reads, not the portal's own (`PointRow` is what the adapter hands over).
   */
  def renderPoints(points: List[PointRow]): Array[Byte] =
    points.sortBy(_.pointKey).map(point).mkString("[\n", ",\n", "\n]\n").getBytes(UTF_8)

  private def point(p: PointRow): String =
    Vector(
      "source_id" -> quote(p.sourceId),
      "point_key" -> quote(p.pointKey),
      "country" -> quote(p.country),
      "state" -> quote(p.state),
      "municipality" -> quote(p.municipality),
      "ibge_code" -> optional(p.ibgeCode),
      "beach_name" -> quote(p.beachName),
      "point_name" -> quote(p.pointName),
      "location_desc" -> optional(p.locationDesc),
      "lat" -> p.lat.fold("null")(_.toString),
      "lon" -> p.lon.fold("null")(_.toString),
      "geo_source" -> quote(p.geoSource.label),
      "first_seen" -> optional(p.firstSeen.map(_.toString)),
      "last_seen" -> optional(p.lastSeen.map(_.toString))
    ).map((k, v) => s"${quote(k)}:$v").mkString("{", ",", "}")

  private def optional(value: Option[String]): String = value.fold("null")(quote)

  private def quote(s: String): String = JsonValue.str(s).render
