package marola.knowledge

import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters.*

import kyo.*

import marola.json.JsonValue

/**
 * Local-first RAG index: embeds every corpus chunk once, stores the vectors as JSON under
 * `./data/`, and re-embeds only when the corpus or the embedding model changes (a fingerprint of
 * file names, sizes, mtimes and the model name). Search embeds the query with the same `Embedder`
 * and ranks by cosine. Everything is `< Sync`; a missing corpus dir just yields no passages.
 *
 * Index format: `{"model": ..., "fingerprint": ..., "chunks": [{title, source, text, vector}]}` —
 * hand-rolled JSON like the rest of the repo (`json/Json.scala`). Vectors are 3072 doubles per
 * chunk with `llama3.2` as the embedder; for a corpus of ~50 chunks that's a ~3MB file, fine.
 */
final class FileKnowledgeStore(corpusDir: String, indexPath: String, embedder: Embedder)
    extends KnowledgeStore:

  final private case class Indexed(chunk: CorpusChunk, vector: Vector[Double])

  def search(query: String, k: Int = 4): List[Passage] < Sync =
    for
      index <- loadOrBuild
      result <- rank(index, query, k)
    yield result

  // Kyo needs an expected type where a pure branch meets an effectful one, hence the helper.
  private def rank(index: List[Indexed], query: String, k: Int): List[Passage] < Sync =
    if index.isEmpty then Nil
    else
      embedder.embed(List(query)).map { vectors =>
        val q = vectors.headOption.getOrElse(Vector.empty)
        index
          .map(i =>
            Passage(i.chunk.docTitle, i.chunk.source, i.chunk.text, Corpus.cosine(q, i.vector))
          )
          .sortBy(-_.score)
          .take(k)
      }

  /** Forces a re-embed; returns the number of chunks indexed. */
  def rebuild: Int < Sync =
    for
      chunks <- Corpus.load(Paths.get(corpusDir))
      fp <- fingerprint
      indexed <- embedAll(chunks)
      _ <- Sync.defer(writeIndex(fp, indexed))
    yield indexed.size

  private def loadOrBuild: List[Indexed] < Sync =
    for
      fp <- fingerprint
      existing <- Sync.defer(readIndex(fp))
      result <- buildIfMissing(existing, fp)
    yield result

  private def buildIfMissing(existing: Option[List[Indexed]], fp: String): List[Indexed] < Sync =
    existing match
      case Some(idx) => idx
      case None =>
        for
          chunks <- Corpus.load(Paths.get(corpusDir))
          indexed <- embedAll(chunks)
          _ <- Sync.defer(writeIndex(fp, indexed))
        yield indexed

  private val BatchSize = 8

  private def embedAll(chunks: List[CorpusChunk]): List[Indexed] < Sync =
    batches(chunks.grouped(BatchSize).toList)

  private def batches(groups: List[List[CorpusChunk]]): List[Indexed] < Sync =
    groups match
      case Nil => Nil
      case group :: rest =>
        for
          vectors <- embedder.embed(group.map(_.text))
          tail <- batches(rest)
        yield group.zip(vectors).map { case (c, v) => Indexed(c, v) } ++ tail

  private def fingerprint: String < Sync =
    Sync.defer {
      val dir = Paths.get(corpusDir)
      val files =
        if !Files.isDirectory(dir) then Nil
        else
          Files
            .list(dir)
            .iterator()
            .asScala
            .filter(_.toString.endsWith(".md"))
            .toList
            .sortBy(_.toString)
      val parts = files.map(p =>
        s"${p.getFileName}:${Files.size(p)}:${Files.getLastModifiedTime(p).toMillis}"
      )
      s"${embedder.model}|${parts.mkString(",")}"
    }

  private def readIndex(expectedFingerprint: String): Option[List[Indexed]] =
    val path = Paths.get(indexPath)
    if !Files.exists(path) then None
    else
      val json = JsonValue.parse(Files.readString(path))
      if json("fingerprint").str.contains(expectedFingerprint) then
        Some(json("chunks").arr.toList.flatMap { c =>
          for
            title <- c("title").str
            source <- c("source").str
            text <- c("text").str
          yield Indexed(CorpusChunk(title, source, text), c("vector").arr.flatMap(_.num))
        })
      else None

  private def writeIndex(fp: String, indexed: List[Indexed]): Unit =
    val path = Paths.get(indexPath)
    Option(path.getParent).foreach(Files.createDirectories(_))
    val json = JsonValue.obj(
      "model" -> JsonValue.str(embedder.model),
      "fingerprint" -> JsonValue.str(fp),
      "chunks" -> JsonValue.arr(indexed.map { i =>
        JsonValue.obj(
          "title" -> JsonValue.str(i.chunk.docTitle),
          "source" -> JsonValue.str(i.chunk.source),
          "text" -> JsonValue.str(i.chunk.text),
          "vector" -> JsonValue.arr(i.vector.map(JsonValue.num)*)
        )
      }*)
    )
    Files.writeString(path, json.render)
    ()

object FileKnowledgeStore:
  val DefaultCorpusDir = "./knowledge"
  val DefaultIndexPath = "./data/knowledge-index.json"
