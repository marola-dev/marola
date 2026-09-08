package marola.knowledge

import kyo.*

/** Turns text into vectors. */
trait Embedder:
  def model: String
  def embed(texts: List[String]): List[Vector[Double]] < Sync

/** A retrieved chunk with its provenance — `source` is the URL the corpus document cites. */
final case class Passage(
    docTitle: String,
    source: String,
    text: String,
    score: Double,
    safety: Boolean = false
)

/**
 * Retrieval over marola's curated marine-knowledge corpus (the Markdown files under `knowledge/`) —
 * the RAG half of `FUTURE-WORK.md` §9.1, local-first.
 */
trait KnowledgeStore:
  def search(query: String, k: Int = 4): List[Passage] < Sync
