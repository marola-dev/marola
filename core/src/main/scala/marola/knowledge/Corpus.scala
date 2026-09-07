package marola.knowledge

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import kyo.*

/**
 * One indexable unit: a paragraph-ish slice of a corpus document, with the document's provenance.
 * `safety = true` iff the document lives under `knowledge/safety/` (MIP-0022) — the directory is
 * the marker, not a front-matter flag, so a new safety topic is reviewable in a PR's file list and
 * needs no parser change.
 */
final case class CorpusChunk(
    docTitle: String,
    source: String,
    text: String,
    safety: Boolean = false
)

/**
 * The corpus is a directory of Markdown files (`knowledge/` at the repo root by default), plus a
 * `safety/` subdirectory (MIP-0022) for documents whose answers must always carry the emergency
 * footer. Each file starts with a `# Title` line and a `Source: <url>` line; the rest is prose.
 * Chunking is by blank line, merging consecutive paragraphs up to `MaxChunkChars` so a short
 * heading doesn't become a chunk on its own — good enough for a corpus of a few dozen paragraphs,
 * which is what this is. The chunker is pure and unit-tested (`CorpusSpec`); only
 * `load`/`listFiles` touch the filesystem.
 */
object Corpus:

  val MaxChunkChars = 700

  /**
   * Every `.md` file directly under `dir`, plus every `.md` file directly under `dir/safety`, one
   * level deep — not a general recursive walk, so a stray subdirectory doesn't silently join the
   * corpus.
   */
  def listFiles(dir: Path): List[Path] =
    if !Files.isDirectory(dir) then Nil
    else
      val top = Files.list(dir).iterator().asScala.toList
      val direct = top.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".md"))
      val safetyDir = dir.resolve("safety")
      val safety =
        if Files.isDirectory(safetyDir) then
          Files
            .list(safetyDir)
            .iterator()
            .asScala
            .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".md"))
            .toList
        else Nil
      (direct ++ safety).sortBy(_.toString)

  def load(dir: Path): List[CorpusChunk] < Sync =
    Sync.defer {
      listFiles(dir).flatMap { p =>
        val isSafety = p.getParent != null && p.getParent.getFileName.toString == "safety"
        chunkDocument(Files.readString(p), isSafety)
      }
    }

  def chunkDocument(markdown: String, safety: Boolean = false): List[CorpusChunk] =
    val lines = markdown.linesIterator.toList
    val title = lines.collectFirst { case l if l.startsWith("# ") => l.drop(2).trim }.getOrElse("")
    val source = lines
      .collectFirst { case l if l.toLowerCase.startsWith("source:") => l.drop(7).trim }
      .getOrElse("")
    val body = lines
      .filterNot(l => l.startsWith("# ") || l.toLowerCase.startsWith("source:"))
      .mkString("\n")
    paragraphs(body)
      .flatMap(mergeUpTo(_, MaxChunkChars))
      .map(text => CorpusChunk(title, source, text, safety))

  private def paragraphs(body: String): List[List[String]] =
    List(body.split("\\n\\s*\\n").toList.map(_.trim).filter(_.nonEmpty))

  private def mergeUpTo(paras: List[String], max: Int): List[String] =
    paras
      .foldLeft(List.empty[String]) {
        case (acc, para) =>
          acc match
            case last :: rest if last.length + para.length + 2 <= max =>
              (last + "\n\n" + para) :: rest
            case _ => para :: acc
      }
      .reverse

  /** Cosine similarity; 0 for a zero vector rather than NaN. */
  def cosine(a: Vector[Double], b: Vector[Double]): Double =
    val dot = a.lazyZip(b).map(_ * _).sum
    val na = math.sqrt(a.map(x => x * x).sum)
    val nb = math.sqrt(b.map(x => x * x).sum)
    if na == 0.0 || nb == 0.0 then 0.0 else dot / (na * nb)
