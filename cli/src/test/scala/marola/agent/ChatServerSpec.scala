package marola.agent

import kyo.*

import marola.knowledge.{KnowledgeStore, Passage}
import marola.llm.{ChatMessage, LlmClient}

/**
 * `ChatServer.responseFor` — the JSON shape MIP-0033's chat widget consumes. HTTP plumbing
 * (`start`/handlers) needs a live socket and isn't unit-tested here; this is the pure part.
 */
class ChatServerSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private val llm = new LlmClient:
    def complete(messages: List[ChatMessage]): String < Sync =
      Sync.defer("Swim parallel to the shore [1].")

  test("no LLM configured: an error field, not a crash") {
    val store = new KnowledgeStore:
      def search(q: String, k: Int): List[Passage] < Sync = Nil
    val json = Sync.Unsafe.evalOrThrow(ChatServer.responseFor("anything", store, None))
    assert(json("error").str.isDefined, json.render)
  }

  test("an ordinary question: answer + sources, safety = false") {
    val store = new KnowledgeStore:
      def search(q: String, k: Int): List[Passage] < Sync =
        List(Passage("Rip currents", "https://x", "swim parallel to the shore", 0.9))
    val json = Sync.Unsafe.evalOrThrow(ChatServer.responseFor("rip current?", store, Some(llm)))
    assertEquals(json("safety").bool, Some(false))
    assert(json("answer").str.exists(_.startsWith("Swim parallel")), json.render)
    assertEquals(json("sources").arr.size, 1)
  }

  test("a safety-grounded question: the footer is appended and safety = true") {
    val store = new KnowledgeStore:
      def search(q: String, k: Int): List[Passage] < Sync =
        List(Passage("Rip currents", "https://x", "swim parallel to the shore", 0.9, safety = true))
    val json = Sync.Unsafe.evalOrThrow(ChatServer.responseFor("rip current?", store, Some(llm)))
    assertEquals(json("safety").bool, Some(true))
    assert(json("answer").str.exists(_.contains("193")), json.render)
  }

end ChatServerSpec
