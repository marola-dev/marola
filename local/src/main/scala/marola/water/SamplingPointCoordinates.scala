package marola.water

import marola.json.JsonValue
import marola.model.Coordinates

/**
 * Loads a hand-curated point-code → coordinate lookup table from a bundled JSON resource — the
 * mechanism MIP-0031 §5 introduces because neither INEA's nor INEMA's bulletin PDF carries
 * coordinates (verified live, MIP-0031 §4.3/§11). One resource per state: `sampling_points_rj.json`
 * (INEA/Rio, this task) is populated only for point codes whose beach falls inside
 * `site/areas.json`'s `rio` radius, geocoded from the bulletin's own beach name against
 * OpenStreetMap/Nominatim — every entry's `source` field says which OSM feature and query resolved
 * it and when. A point code absent from the table is **dropped by the caller, never defaulted to a
 * guessed coordinate** — the same tolerant-parsing discipline `ImaScWaterQualityClient`'s own doc
 * comment states.
 */
object SamplingPointCoordinates:

  final case class Entry(code: String, beachHint: String, coordinates: Coordinates, source: String)

  /** Loads and indexes a resource by point code; later duplicate codes overwrite earlier ones. */
  def load(resourceName: String): Map[String, Coordinates] =
    parse(readResource(resourceName)).map(e => e.code -> e.coordinates).toMap

  private def readResource(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(name)
    require(stream != null, s"missing sampling-point resource: $name")
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  /** Pure; unit-tested directly on JSON text (`SamplingPointCoordinatesSpec`). */
  private[water] def parse(json: String): List[Entry] =
    JsonValue.parse(json).arr.toList.flatMap(parseEntry)

  private def parseEntry(v: JsonValue): Option[Entry] =
    for
      code <- v("code").str
      beach <- v("beach_hint").str
      lat <- v("lat").num
      lon <- v("lon").num
      source <- v("source").str
    yield Entry(code, beach, Coordinates(lat, lon), source)

  /** INEA/Rio's curated table (`local/src/main/resources/sampling_points_rj.json`). */
  val Rio: Map[String, Coordinates] = load("sampling_points_rj.json")
