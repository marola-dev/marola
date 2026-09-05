package marola.sightings

import java.io.{File, FileWriter, PrintWriter}
import java.nio.file.{Files, Paths}
import java.time.Instant

import kyo.*

import marola.json.JsonValue

/**
 * Default `SightingStore` — one JSON object per line, appended to a local file. No Azure account,
 * no setup: this is what makes the sighting-feedback loop (`ARCHITECTURE.md` §8) testable today,
 * with `CosmosDbSightingStore` as the opt-in upgrade for actually running the Telegram bot as a
 * shared, always-on service (a local file isn't a sensible store once more than one process/host
 * can receive reports).
 */
final class LocalFileSightingStore(path: String) extends SightingStore:

  def record(sighting: Sighting): Unit < Sync =
    Sync.defer {
      Option(Paths.get(path).getParent).foreach(Files.createDirectories(_))
      val writer = PrintWriter(FileWriter(path, true))
      try writer.println(LocalFileSightingStore.toJson(sighting).render)
      finally writer.close()
    }

  def recentFor(beachName: String, limit: Int): List[Sighting] < Sync =
    Sync.defer {
      val file = File(path)
      if !file.exists() then Nil
      else
        val lines = scala.io.Source.fromFile(file)
        try
          lines
            .getLines()
            .flatMap(LocalFileSightingStore.parseLine)
            .filter(_.beachName == beachName)
            .toList
            .sortBy(_.reportedAt)(using Ordering[Instant].reverse)
            .take(limit)
        finally lines.close()
    }

object LocalFileSightingStore:

  val DefaultPath = "./data/sightings.jsonl"

  private def toJson(sighting: Sighting): JsonValue =
    JsonValue.obj(
      "beach_name" -> JsonValue.str(sighting.beachName),
      "kind" -> JsonValue.str(sighting.kind.toString),
      "note" -> sighting.note.map(JsonValue.str).getOrElse(JsonValue.JNull),
      "reported_at" -> JsonValue.str(sighting.reportedAt.toString)
    )

  // One malformed line (a hand edit, a crash mid-write) must not hide every other report.
  private def parseLine(line: String): Option[Sighting] =
    if line.isBlank then None
    else scala.util.Try(JsonValue.parse(line)).toOption.flatMap(parseJson)

  private def parseJson(json: JsonValue): Option[Sighting] =
    for
      beachName <- json("beach_name").str
      kindStr <- json("kind").str
      kind <- SightingKind.values.find(_.toString == kindStr)
      reportedAtStr <- json("reported_at").str
    yield Sighting(beachName, kind, json("note").str, Instant.parse(reportedAtStr))
