package marola.llm

import kyo.*

/**
 * The summarize → review flow with a scripted `LlmClient` — regression for the compiled prompt
 * artifacts, message building, the OpenAI-shape response extraction and the reviewer's JSON
 * recovery, none of which need a model. This is what CI runs instead of the Ollama E2E test.
 */
class SummarizeFlowSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private def resource(name: String): String =
    val s = getClass.getClassLoader.getResourceAsStream(name)
    try scala.io.Source.fromInputStream(s, "UTF-8").mkString
    finally s.close()

  private val facts = Map(
    "beach_name" -> "Praia do Campeche",
    "hour_local" -> "Sun 6 Sep, 07:00",
    "sea_temp_c" -> "18.9",
    "wind_kmh" -> "20",
    "wave_height_m" -> "1.0",
    "jellyfish_risk" -> "Low",
    "whale_sighting_likelihood" -> "Moderate",
    "score" -> "35"
  )

  /** Replies in order; records every message list it was given. */
  final class ScriptedLlm(replies: String*) extends LlmClient:
    var calls: List[List[ChatMessage]] = Nil
    private val queue = scala.collection.mutable.Queue(replies*)
    def complete(messages: List[ChatMessage]): String < Sync =
      Sync.defer {
        calls = calls :+ messages
        queue.dequeue()
      }

  test("both compiled artifacts load and replay as system + demo pairs + user turn") {
    val summary = CompiledPrompt.loadFromString(resource("recommendation_prompt.json"), "summary")
    val review = CompiledPrompt.loadFromString(resource("review_prompt.json"), "review_json")
    assertEquals(summary.demos.size, 3)
    assertEquals(review.demos.size, 3)
    val msgs = summary.buildMessages(facts)
    assertEquals(msgs.size, 1 + 2 * 3 + 1)
    assertEquals(msgs.head.role, "system")
    assert(msgs.head.content.contains("jellyfish"))
    assertEquals(
      msgs.map(_.role).drop(1).dropRight(1),
      List.fill(3)(List("user", "assistant")).flatten
    )
    assert(msgs.last.content.contains("Beach Name: Praia do Campeche"))
    assert(msgs.last.content.contains("Whale Sighting Likelihood: Moderate"))
    // demos never leak the `augmented` marker as a field
    assert(!msgs.exists(_.content.contains("Augmented")))
  }

  test("reviewer parses a fenced/prosed JSON reply and keeps the draft when approved") {
    val draft = "Cold and choppy at Campeche, with a fair chance of spotting a whale."
    val llm = ScriptedLlm(
      draft,
      "Sure! Here is the review:\n```json\n{\"score\": 82, \"verdict\": \"approve\", \"final_summary\": \"" + draft + "\"}\n```"
    )
    val summary = CompiledPrompt.loadFromString(resource("recommendation_prompt.json"), "summary")
    val review = CompiledPrompt.loadFromString(resource("review_prompt.json"), "review_json")
    val got = Sync.Unsafe.evalOrThrow(llm.complete(summary.buildMessages(facts)))
    assertEquals(got, draft)
    val result = Sync.Unsafe.evalOrThrow(Reviewer.review(llm, review, facts, got))
    assertEquals(result.score, 82)
    assertEquals(result.verdict, "approve")
    assertEquals(result.finalSummary, draft)
    // the reviewer saw the draft as the `summary` input
    assert(llm.calls(1).last.content.contains(s"Summary: $draft"))
  }

  test("reviewer 'revise' replaces the summary; a reply with no numeric score is a typed failure") {
    val review = CompiledPrompt.loadFromString(resource("review_prompt.json"), "review_json")
    val ok = ScriptedLlm("""{"score": 30, "verdict": "revise", "final_summary": "Skip it."}""")
    val r = Sync.Unsafe.evalOrThrow(Reviewer.review(ok, review, facts, "draft"))
    assertEquals((r.verdict, r.finalSummary), ("revise", "Skip it."))
    val bad = ScriptedLlm("""{"verdict": "approve"}""")
    intercept[Reviewer.MalformedReviewException] {
      Sync.Unsafe.evalOrThrow(Reviewer.review(bad, review, facts, "draft"))
    }
  }

  test("extractContent reads choices[0].message.content from an OpenAI-shaped body") {
    val body =
      """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"hi there"}}]}"""
    assertEquals(LlmClient.extractContent(body), "hi there")
    intercept[LlmClient.NoCompletionException](LlmClient.extractContent("""{"choices":[]}"""))
  }

end SummarizeFlowSpec
