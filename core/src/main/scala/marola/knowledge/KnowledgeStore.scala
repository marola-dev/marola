package marola.knowledge

import kyo.*

/**
 * Turns text into vectors. One implementation today, `OllamaEmbedder` (`marola-local`), which uses
 * the same local model as everything else (`llama3.2` via Ollama's `/api/embed` — confirmed to
 * return 3072-dim vectors) so RAG needs no extra download; a dedicated embedding model
 * (`nomic-embed-text`) is a one-env-var upgrade. An Azure-hosted embedding deployment would be a
 * sibling in `marola-azure`.
 */
trait Embedder:
  def model: String
  def embed(texts: List[String]): List[Vector[Double]] < Sync

/**
 * A retrieved chunk with its provenance — `source` is the URL the corpus document cites. `safety`
 * carries `CorpusChunk.safety` through retrieval (MIP-0022): `OceanQa.answer` uses it to decide
 * whether the reply must carry the emergency footer, independent of which document's text the model
 * actually quoted.
 */
final case class Passage(
    docTitle: String,
    source: String,
    text: String,
    score: Double,
    safety: Boolean = false
)

/**
 * Retrieval over marola's curated marine-knowledge corpus (the Markdown files under `knowledge/`) —
 * the RAG half of `FUTURE-WORK.md` §9.1, local-first. `FileKnowledgeStore` (this module) indexes
 * the corpus with an `Embedder` into a JSON file under `./data/` and answers by cosine similarity.
 */
trait KnowledgeStore:
  def search(query: String, k: Int = 4): List[Passage] < Sync
