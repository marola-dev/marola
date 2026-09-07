package marola.water

import marola.json.JsonValue
import marola.model.Coordinates

/**
 * Hand-curated point-code → coordinate lookup for INEMA's (Bahia) sampling points near
 * `site/areas.json`'s `salvador` area (MIP-0031 §5) — neither INEMA nor INEA's bulletin PDF carries
 * a coordinate for any point (MIP-0031 §4.3), so this bundled resource, `sampling_points_ba.json`,
 * supplies them for the points marola's configured Salvador area actually needs, scoped to what a
 * real, cited geocoding method (OSM/Nominatim, matched against the bulletin's own point/beach names
 * — see the resource's per-entry `source` field) could confidently resolve, not the whole 134-point
 * Bahia coast.
 *
 * A code missing from the table — a bulletin row this pass couldn't confidently geocode, or a
 * genuinely new point INEMA adds later — is dropped by `lookup`, never defaulted to a guessed
 * coordinate: the same tolerant-parsing discipline `ImaScWaterQualityClient.parse` already follows
 * for a malformed point or sample.
 */
object SamplingPointCoordinates:

  private val Resource = "sampling_points_ba.json"

  private lazy val byCode: Map[String, Coordinates] = load(Resource)

  /** A code absent from the curated table returns `None` — never a guessed coordinate. */
  def lookup(code: String): Option[Coordinates] = byCode.get(code)

  private def load(resource: String): Map[String, Coordinates] =
    val stream = getClass.getClassLoader.getResourceAsStream(resource)
    if stream == null then Map.empty
    else
      val text =
        try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
        finally stream.close()
      JsonValue.parse(text).arr.flatMap(parseEntry).toMap

  private def parseEntry(entry: JsonValue): Option[(String, Coordinates)] =
    for
      code <- entry("code").str
      lat <- entry("lat").num
      lon <- entry("lon").num
    yield code -> Coordinates(lat, lon)
