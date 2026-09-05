package marola.knowledge

import kyo.*
import marola.http.Http
import marola.json.JsonValue

/**
 * Embeddings from Ollama's native `/api/embed` (`{"model", "input": [...]}` → `{"embeddings":
 * [[...], ...]}`) — note *native* API, not the OpenAI-compatible `/v1` prefix `LocalLlmClient`
 * uses, hence `nativeBaseUrl`. Confirmed live on 2026-09-05 with `llama3.2` itself as the embedding
 * model (3072 dims), which is the default so RAG works with the one model the rest of marola
 * already needs; `MAROLA_LOCAL_EMBED_MODEL=nomic-embed-text` is the better retrieval quality option
 * at the cost of one more `ollama pull`. Embedding a chunk on CPU is a forward pass of a 3B model —
 * seconds each, minutes for the whole corpus the first time; the index is cached on disk by
 * `FileKnowledgeStore`, hence the long timeout.
 */
final class OllamaEmbedder(nativeBaseUrl: String, val model: String) extends Embedder:

  def embed(texts: List[String]): List[Vector[Double]] < Sync =
    if texts.isEmpty then Nil: List[Vector[Double]]
    else
      val body = JsonValue.obj(
        "model" -> JsonValue.str(model),
        "input" -> JsonValue.arr(texts.map(JsonValue.str)*)
      )
      Http.postJson(s"$nativeBaseUrl/api/embed", body.render, timeoutSeconds = 600).map { reply =>
        JsonValue.parse(reply)("embeddings").arr.toList.map(_.arr.flatMap(_.num))
      }

object OllamaEmbedder:
  val DefaultModel = "llama3.2"

  /** `http://localhost:11434/v1` (LocalLlmClient's base) → `http://localhost:11434`. */
  def nativeBaseUrl(openAiCompatibleBaseUrl: String): String =
    openAiCompatibleBaseUrl.stripSuffix("/").stripSuffix("/v1")
