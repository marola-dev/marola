package marola.oods

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant

import scala.util.Try

import marola.json.JsonValue

final case class RawEntry(
    url: String,
    sha256: String,
    bytes: Long,
    fetchedAt: Instant,
    rows: Int
) derives CanEqual

/**
 * What a source has already fetched and built: `raw` keyed by the raw file's repo-relative path,
 * `partitions` by partition id → the content hash of its sorted rows (MIP-0056 §5.2/§5.3). An
 * immutable partition present here is skipped without a request, which is what makes a re-run cost
 * one HTTP call per mutable partition instead of 3,432.
 */
final case class Manifest(raw: Map[String, RawEntry], partitions: Map[String, String])
    derives CanEqual

object Manifest:

  val empty: Manifest = Manifest(Map.empty, Map.empty)

  private val version = 1

  /** `<file>.tmp` beside the file, so the rename below stays within one filesystem. */
  def tempFile(file: Path): Path = file.resolveSibling(s"${file.getFileName}.tmp")

  /**
   * Sorted keys, one raw entry per line, trailing newline: a run that changed nothing must produce
   * a byte-identical file, or every ingest would show up as a diff.
   */
  def render(m: Manifest): String =
    val partitions =
      m.partitions.toVector.sortBy(_._1).map((id, hash) => s"${quote(id)}: ${quote(hash)}")
    val raw = m.raw.toVector.sortBy(_._1).map((path, e) => s"${quote(path)}: ${entry(e)}")
    Vector(
      block("partitions", partitions),
      block("raw", raw),
      s"${quote("version")}: $version"
    ).mkString("{\n", ",\n", "\n}\n")

  /**
   * An entry missing a field is dropped, not half-trusted: the cost is a refetch, never a wrong
   * skip.
   */
  def parse(text: String): Manifest =
    val json = JsonValue.parse(text)
    Manifest(
      raw = fields(json("raw")).flatMap((k, v) => rawEntry(v).map(k -> _)),
      partitions = fields(json("partitions")).flatMap((k, v) => v.str.map(k -> _))
    )

  /** A source that has never run has no manifest; that is an empty one, not a failure. */
  def read(file: Path): Manifest =
    if !Files.isRegularFile(file) then empty
    else parse(Files.readString(file, StandardCharsets.UTF_8))

  /**
   * `publish` is the rename, injected so a test can stop the process between the two steps
   * (`.claude/rules/scala.md`: inject the seam, keep a real default).
   */
  def write(file: Path, manifest: Manifest, publish: (Path, Path) => Unit = atomicMove): Unit =
    Option(file.getParent).foreach { dir =>
      val _ = Files.createDirectories(dir)
    }
    val tmp = tempFile(file)
    val _ = Files.writeString(tmp, render(manifest), StandardCharsets.UTF_8)
    publish(tmp, file)

  private val atomicMove: (Path, Path) => Unit = (tmp, file) =>
    val _ =
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

  private def block(name: String, lines: Vector[String]): String =
    if lines.isEmpty then s"${quote(name)}: {}"
    else lines.mkString(s"${quote(name)}: {\n", ",\n", "\n}")

  private def entry(e: RawEntry): String =
    // Sorted field order, rendered by hand: JsonValue.render walks an unordered Map.
    Vector(
      s"${quote("bytes")}:${e.bytes}",
      s"${quote("fetched_at")}:${quote(e.fetchedAt.toString)}",
      s"${quote("rows")}:${e.rows}",
      s"${quote("sha256")}:${quote(e.sha256)}",
      s"${quote("url")}:${quote(e.url)}"
    ).mkString("{", ",", "}")

  private def quote(s: String): String = JsonValue.str(s).render

  private def fields(v: JsonValue): Map[String, JsonValue] = v match
    case JsonValue.JObject(fs) => fs
    case _                     => Map.empty

  private def rawEntry(v: JsonValue): Option[RawEntry] =
    for
      url <- v("url").str
      sha256 <- v("sha256").str
      bytes <- v("bytes").num
      fetchedAt <- v("fetched_at").str.flatMap(s => Try(Instant.parse(s)).toOption)
      rows <- v("rows").num
    yield RawEntry(url, sha256, bytes.toLong, fetchedAt, rows.toInt)
