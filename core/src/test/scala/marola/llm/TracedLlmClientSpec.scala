package marola.llm

import scala.collection.mutable.ListBuffer

import kyo.*

import marola.observability.Tracing

/**
 * MIP-0010 task 6: `TracedLlmClient` wraps every `complete` in one `Tracing.llmSpan` with the GenAI
 * semantic-convention attributes MLflow ingests — and, by default, *no* prompt or completion text
 * (a swimmer's location is personal data; `MAROLA_TRACE_CONTENT=1` opts in). Asserted against a
 * recording `Tracing` double: vendor-free, no OpenTelemetry in `core`.
 */
class TracedLlmClientSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  private def run[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  /** Records `(span name, attributes at start, attributes added from the result)` per span. */
  final class Recording extends Tracing:
    val spans: ListBuffer[(String, Map[String, String], Map[String, String])] = ListBuffer.empty
    def withSpan[A, S](name: String, attributes: Map[String, String])(
        effect: A < (Sync & S)
    ): A < (Sync & S) =
      for
        result <- effect
        _ <- Sync.defer { spans += ((name, attributes, Map.empty)); () }
      yield result
    override def llmSpan[A, S](model: String, attributes: Map[String, String])(
        effect: A < (Sync & S)
    )(result: A => Map[String, String]): A < (Sync & S) =
      for
        value <- effect
        _ <- Sync.defer { spans += ((s"llm.$model", attributes, result(value))); () }
      yield value

  final class Inner extends LlmClient:
    var seen: List[List[ChatMessage]] = Nil
    def complete(messages: List[ChatMessage]): String < Sync =
      Sync.defer { seen = messages :: seen; "Swim at 10:00 at Campeche." }
  private val inner = Inner()

  private val messages = List(
    ChatMessage("system", "You are marola."),
    ChatMessage("user", "Best hour tomorrow near -27.67,-48.47?")
  )

  test("complete delegates to the inner client and returns its completion unchanged") {
    val tracing = Recording()
    val client = TracedLlmClient(inner, "llama3.2", tracing, traceContent = false)
    assertEquals(run(client.complete(messages)), "Swim at 10:00 at Campeche.")
    assertEquals(inner.seen.head, messages)
  }

  test("one llm.<model> span per call, with gen_ai.* attributes and no content by default") {
    val tracing = Recording()
    val client = TracedLlmClient(inner, "llama3.2", tracing, traceContent = false)
    val _ = run(client.complete(messages))
    assertEquals(tracing.spans.size, 1)
    val (name, start, end) = tracing.spans.head
    assertEquals(name, "llm.llama3.2")
    assertEquals(
      start,
      Map(
        "gen_ai.operation.name" -> "chat",
        "gen_ai.request.model" -> "llama3.2",
        "marola.llm.messages" -> "2",
        "marola.llm.prompt_chars" -> messages.map(_.content.length).sum.toString
      )
    )
    assertEquals(end, Map("marola.llm.completion_chars" -> "26"))
    assert(!start.contains("gen_ai.prompt"), "prompt text must not be traced by default")
    assert(!end.contains("gen_ai.completion"), "completion text must not be traced by default")
  }

  test("traceContent = true (MAROLA_TRACE_CONTENT=1) attaches the prompt and the completion") {
    val tracing = Recording()
    val client = TracedLlmClient(inner, "llama3.2", tracing, traceContent = true)
    val _ = run(client.complete(messages))
    val (_, start, end) = tracing.spans.head
    assertEquals(
      start("gen_ai.prompt"),
      "system: You are marola.\nuser: Best hour tomorrow near -27.67,-48.47?"
    )
    assertEquals(end("gen_ai.completion"), "Swim at 10:00 at Campeche.")
  }

  test("with Tracing.Noop the decorator is transparent — same answer, no span machinery") {
    val client = TracedLlmClient(inner, "llama3.2", Tracing.Noop, traceContent = false)
    assertEquals(run(client.complete(messages)), "Swim at 10:00 at Campeche.")
  }

  test("TracedLlmClient.fromEnv: only the literal '1'/'true' opt into content") {
    assertEquals(TracedLlmClient.contentFromEnv(None), false)
    assertEquals(TracedLlmClient.contentFromEnv(Some("0")), false)
    assertEquals(TracedLlmClient.contentFromEnv(Some("yes")), false)
    assertEquals(TracedLlmClient.contentFromEnv(Some("1")), true)
    assertEquals(TracedLlmClient.contentFromEnv(Some("true")), true)
  }
