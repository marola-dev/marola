package marola.observability

import scala.jdk.CollectionConverters.*

import kyo.*

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter

/**
 * MIP-0010 task 6: `MlflowTracing` against OpenTelemetry's own in-memory exporter
 * (`opentelemetry-sdk-testing`) — the spans that would go to MLflow's `/v1/traces`, asserted
 * offline: names, `gen_ai.*` attributes, parent/child nesting (one trace, not three), and the OTLP
 * endpoint/header the real exporter is built with.
 */
class MlflowTracingSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  private def run[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  private def fixture(): (MlflowTracing, InMemorySpanExporter) =
    val exporter = InMemorySpanExporter.create()
    (MlflowTracing.withExporter(exporter), exporter)

  test("withSpan exports one span with the given name and attributes") {
    val (tracing, exporter) = fixture()
    assertEquals(
      run(tracing.withSpan("bestPerBeachTomorrow", Map("beaches" -> "6"))(Sync.defer(7))),
      7
    )
    val spans = exporter.getFinishedSpanItems.asScala.toList
    assertEquals(spans.map(_.getName), List("bestPerBeachTomorrow"))
    assertEquals(spans.head.getAttributes.get(AttributeKey.stringKey("beaches")), "6")
  }

  test(
    "llmSpan is named llm.<model>, carries start attributes and the ones derived from the result"
  ) {
    val (tracing, exporter) = fixture()
    val out = run(
      tracing.llmSpan("llama3.2", Map("gen_ai.request.model" -> "llama3.2"))(Sync.defer("done"))(
        s => Map("marola.llm.completion_chars" -> s.length.toString)
      )
    )
    assertEquals(out, "done")
    val span = exporter.getFinishedSpanItems.asScala.head
    assertEquals(span.getName, "llm.llama3.2")
    assertEquals(span.getAttributes.get(AttributeKey.stringKey("gen_ai.request.model")), "llama3.2")
    assertEquals(span.getAttributes.get(AttributeKey.stringKey("marola.llm.completion_chars")), "4")
  }

  test("a span opened inside withSpan is its child: one trace, summarize under recommend") {
    val (tracing, exporter) = fixture()
    val _ = run(
      tracing.withSpan("marola.recommend") {
        for
          a <- tracing.llmSpan("llama3.2", Map.empty)(Sync.defer("draft"))(_ => Map.empty)
          b <- tracing.llmSpan("llama3.2", Map.empty)(Sync.defer("review"))(_ => Map.empty)
        yield a + b
      }
    )
    val spans = exporter.getFinishedSpanItems.asScala.toList
    assertEquals(
      spans.map(_.getName).sorted,
      List("llm.llama3.2", "llm.llama3.2", "marola.recommend")
    )
    val root = spans.find(_.getName == "marola.recommend").get
    val children = spans.filter(_.getName != "marola.recommend")
    assertEquals(spans.map(_.getTraceId).distinct.size, 1)
    children.foreach(c => assertEquals(c.getParentSpanId, root.getSpanId))
    assert(!root.getParentSpanContext.isValid, "the outer span must be a root span")
  }

  test("after the outer span ends, the next span is a new root — no leaked parent") {
    val (tracing, exporter) = fixture()
    run(tracing.withSpan("first")(Sync.defer(())))
    run(tracing.withSpan("second")(Sync.defer(())))
    val spans = exporter.getFinishedSpanItems.asScala.toList
    assertEquals(spans.map(_.getTraceId).distinct.size, 2)
    spans.foreach(s => assert(!s.getParentSpanContext.isValid, s"${s.getName} should be a root"))
  }

  test("a failing effect still ends the span, marked as an error, and the failure propagates") {
    val (tracing, exporter) = fixture()
    val boom = new RuntimeException("ollama down")
    val outcome =
      run(Abort.run(Abort.catching[Throwable](tracing.withSpan("x")(Sync.defer(throw boom)))))
    assert(outcome.isFailure)
    val span = exporter.getFinishedSpanItems.asScala.head
    assertEquals(span.getName, "x")
    assertEquals(span.getStatus.getStatusCode, io.opentelemetry.api.trace.StatusCode.ERROR)
  }

  test(
    "endpoint and header for the real exporter: <tracking uri>/v1/traces, x-mlflow-experiment-id"
  ) {
    assertEquals(
      MlflowTracing.tracesEndpoint("http://127.0.0.1:5000/"),
      "http://127.0.0.1:5000/v1/traces"
    )
    assertEquals(MlflowTracing.headers("42"), Map("x-mlflow-experiment-id" -> "42"))
  }

  test("experiment name for traces is <prefix>/traces") {
    assertEquals(MlflowTracing.experimentName("marola"), "marola/traces")
  }
