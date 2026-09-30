package marola.knowledge

import java.nio.file.{Files, Path, Paths}

import kyo.*

import marola.llm.{ChatMessage, LlmClient}

/**
 * The RAG mechanics — chunking the real `knowledge/` corpus, indexing, fingerprint caching,
 * retrieval, and grounded prompt building — with a deterministic bag-of-words "embedder" instead of
 * Ollama.
 */
class RagOfflineSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  /** Hashed bag of words → cosine similarity is lexical overlap. */
  final class BagOfWordsEmbedder extends Embedder:
    val model = "bag-of-words-test"
    var calls = 0
    def embed(texts: List[String]): List[Vector[Double]] < Sync =
      Sync.defer {
        calls += 1
        texts.map { t =>
          val v = Array.fill(256)(0.0)
          t.toLowerCase
            .split("[^a-z0-9]+")
            .filter(_.length > 2)
            .foreach(w => v(math.abs(w.hashCode) % 256) += 1.0)
          v.toVector
        }
      }

  // MIP-0070 §5.4: no directory walk — MAROLA_KNOWLEDGE_DIR is the one thing that says where the
  // corpus is, so a wrong or empty pin fails these tests loudly instead of silently finding the
  // real knowledge/ some levels up.
  private val knowledgeDir: Path =
    Paths.get(sys.env.getOrElse("MAROLA_KNOWLEDGE_DIR", "knowledge"))

  private def store(embedder: Embedder, tmp: Path) =
    FileKnowledgeStore(knowledgeDir.toString, tmp.resolve("index.json").toString, embedder)

  test("every corpus document chunks with a title and a source URL") {
    val chunks = Sync.Unsafe.evalOrThrow(Corpus.load(knowledgeDir))
    assert(chunks.size >= 15, s"only ${chunks.size} chunks")
    val sourced = chunks.filter(_.source.nonEmpty)
    assert(sourced.forall(_.source.startsWith("http")))
    assertEquals(
      sourced.map(_.docTitle).distinct.size,
      6,
      sourced.map(_.docTitle).distinct.toString
    )
  }

  test(
    "search retrieves the rip-current document for a rip-current question, and caches the index"
  ) {
    val tmp = Files.createTempDirectory("marola-rag")
    val embedder = BagOfWordsEmbedder()
    val s = store(embedder, tmp)
    val hits =
      Sync.Unsafe.evalOrThrow(s.search("caught in a rip current, swim parallel to the shore", 3))
    assertEquals(hits.size, 3)
    assertEquals(hits.head.docTitle, "Rip currents", hits.map(h => (h.docTitle, h.score)).toString)
    assert(hits.head.source.contains("weather.gov"))
    assert(Files.exists(tmp.resolve("index.json")))
    val callsAfterBuild = embedder.calls
    val again = Sync.Unsafe.evalOrThrow(s.search("rip current", 2))
    assertEquals(again.size, 2)
    assertEquals(embedder.calls, callsAfterBuild + 1, "second search should embed only the query")
  }

  test("OceanQa answers from the retrieved passages only, and abstains when there are none") {
    val tmp = Files.createTempDirectory("marola-rag")
    val s = store(BagOfWordsEmbedder(), tmp)
    var seen: List[ChatMessage] = Nil
    val llm = new LlmClient:
      def complete(messages: List[ChatMessage]): String < Sync =
        Sync.defer { seen = messages; "Swim parallel to the shore [1]." }
    // minScore = 0: the bag-of-words cosine is far below the real-embedder threshold.
    val a = Sync.Unsafe.evalOrThrow(
      OceanQa.answer(
        "caught in a rip current, swim parallel to the shore",
        s,
        llm,
        k = 2,
        minScore = 0.0
      )
    )
    assertEquals(a.passages.size, 2)
    assert(a.text.endsWith("[1]."))
    assert(seen.head.content.contains("ONLY the numbered passages"))
    assert(seen(1).content.contains("[1] (Rip currents"))

    val empty = new KnowledgeStore:
      def search(q: String, k: Int): List[Passage] < Sync = Nil
    val none = Sync.Unsafe.evalOrThrow(OceanQa.answer("anything", empty, llm))
    assertEquals(none.text, OceanQa.NoPassagesReply)
    assertEquals(none.passages, Nil)
    // general fallback: the model is asked without passages and the answer is labelled unsourced.
    val general = Sync.Unsafe.evalOrThrow(
      OceanQa.answer("anything", empty, llm, fallback = OceanQa.Fallback.General)
    )
    assert(general.text.startsWith(OceanQa.GeneralKnowledgeLabel), general.text)
    assertEquals(general.passages, Nil)
    assert(!seen.head.content.contains("numbered passages"))
  }

  test("Answer.safety is true iff a retained passage came from knowledge/safety/") {
    val tmp = Files.createTempDirectory("marola-rag")
    val s = store(BagOfWordsEmbedder(), tmp)
    val llm = new LlmClient:
      def complete(messages: List[ChatMessage]): String < Sync =
        Sync.defer("Swim parallel to the shore [1].")

    val ripQuestion = Sync.Unsafe.evalOrThrow(
      OceanQa.answer("caught in a rip current, swim parallel to the shore", s, llm, k = 2)
    )
    assert(ripQuestion.safety, ripQuestion.passages.map(_.docTitle).toString)

    val whaleQuestion =
      Sync.Unsafe.evalOrThrow(OceanQa.answer("humpback whale migration season", s, llm, k = 2))
    assert(!whaleQuestion.safety, whaleQuestion.passages.map(_.docTitle).toString)
  }

  test("Answer.safety is true on a strict abstention when a safety passage was retained") {
    val safePassage =
      Passage("Rip currents", "https://x", "swim parallel to the shore", 0.9, safety = true)
    val store = new KnowledgeStore:
      def search(q: String, k: Int): List[Passage] < Sync = List(safePassage)
    val llm = new LlmClient:
      def complete(messages: List[ChatMessage]): String < Sync =
        Sync.defer(OceanQa.NoAnswerSentinel)
    val a = Sync.Unsafe.evalOrThrow(OceanQa.answer("anything", store, llm))
    assertEquals(a.text, OceanQa.NoPassagesReply)
    assert(a.safety)
  }

end RagOfflineSpec
