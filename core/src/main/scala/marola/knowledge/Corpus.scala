package marola.knowledge

import kyo.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * One indexable unit: a paragraph-ish slice of a corpus document, with the document's provenance.
 */
final case class CorpusChunk(docTitle: String, source: String, text: String)

/**
 * The corpus is a directory of Markdown files (`knowledge/` at the repo root by default). Each file
 * starts with a `# Title` line and a `Source: <url>` line; the rest is prose. Chunking is by blank
 * line, merging consecutive paragraphs up to `MaxChunkChars` so a short heading doesn't become a
 * chunk on its own — good enough for a corpus of a few dozen paragraphs, which is what this is. The
 * chunker is pure and unit-tested (`CorpusSpec`); only `load` touches the filesystem.
 */
object Corpus:

  val MaxChunkChars = 700

  def load(dir: Path): List[CorpusChunk] < Sync =
    Sync.defer {
      if !Files.isDirectory(dir) then Nil
      else
        Files
          .list(dir)
          .iterator()
          .asScala
          .filter(p => p.toString.endsWith(".md"))
          .toList
          .sortBy(_.getFileName.toString)
          .flatMap(p => chunkDocument(Files.readString(p)))
    }

  def chunkDocument(markdown: String): List[CorpusChunk] =
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
      .map(text => CorpusChunk(title, source, text))

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
