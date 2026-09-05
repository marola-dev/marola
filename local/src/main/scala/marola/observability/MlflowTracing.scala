package marola.observability

import java.util.concurrent.atomic.AtomicReference

import kyo.*

import marola.ledger.MlflowApi

import io.opentelemetry.api.trace.{Span, StatusCode, Tracer}
import io.opentelemetry.context.Context
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.{SimpleSpanProcessor, SpanExporter}

/**
 * `Tracing` backend for a local MLflow server (`MAROLA_TRACES=mlflow`, MIP-0010 §4.3/§5): spans go
 * over OTLP/HTTP to `<tracking uri>/v1/traces` with the `x-mlflow-experiment-id` header MLflow
 * requires (its OTLP ingest endpoint, MLflow ≥ 3.6, takes an experiment *id*, not a name — so
 * `apply` resolves `<prefix>/traces` through the same `experiments/get-by-name` → `create` call the
 * run ledger uses, `MlflowApi.getOrCreateExperiment`). Zero Azure dependency: this module's
 * invariant holds, the one addition is the OpenTelemetry SDK + OTLP exporter (`build.sbt`).
 *
 * Export is synchronous per span (`SimpleSpanProcessor`): marola's CLI is a short-lived process and
 * a batching processor would need a shutdown hook to flush its last spans before exit — the span
 * per LLM call costs one HTTP POST, trivial next to the seconds the model itself takes.
 *
 * Parent/child nesting is explicit rather than via OpenTelemetry's thread-local `Context`: a Kyo
 * effect may resume on another thread after a suspension, so `makeCurrent()` scopes cannot be
 * trusted across `Sync.defer` boundaries. The current span is held in an `AtomicReference` and
 * restored when the outer span ends, which is exact for the CLI's one linear pipeline
 * (`marola.recommend` → `llm.<model>` × 2) and is documented as the limitation it is: concurrent
 * pipelines through one `MlflowTracing` would mis-parent spans (a `Local`-based parent is the
 * upgrade if that ever matters — MIP-0003's fan-out would be the trigger).
 *
 * A failing effect ends the span with `StatusCode.ERROR` and the exception's message, then rethrows
 * — unlike `AzureMonitorTracing`'s documented shallow gap, this one does close the span.
 */
final class MlflowTracing private (tracer: Tracer, current: AtomicReference[Option[Span]])
    extends Tracing:

  def withSpan[A, S](name: String, attributes: Map[String, String])(
      effect: A < (Sync & S)
  ): A < (Sync & S) =
    span(name, attributes)(effect)(_ => Map.empty)

  override def llmSpan[A, S](model: String, attributes: Map[String, String])(
      effect: A < (Sync & S)
  )(result: A => Map[String, String]): A < (Sync & S) =
    span(s"llm.$model", attributes)(effect)(result)

  private def span[A, S](name: String, attributes: Map[String, String])(effect: A < (Sync & S))(
      result: A => Map[String, String]
  ): A < (Sync & S) =
    for
      opened <- Sync.defer {
        val builder = tracer.spanBuilder(name)
        val parent = current.get()
        parent.foreach(p => builder.setParent(Context.root().`with`(p)))
        attributes.foreach((k, v) => builder.setAttribute(k, v))
        val span = builder.startSpan()
        current.set(Some(span))
        (span, parent)
      }
      (span, parent) = opened
      outcome <- Abort.run(Abort.catching[Throwable](effect))
      value <- Sync.defer {
        outcome match
          case Result.Success(a) =>
            result(a).foreach((k, v) => span.setAttribute(k, v))
            span.setStatus(StatusCode.OK)
            span.end()
            current.set(parent)
            a
          case Result.Failure(e) => fail(span, parent, e)
          case Result.Panic(e)   => fail(span, parent, e)
      }
    yield value

  private def fail(span: Span, parent: Option[Span], e: Throwable): Nothing =
    span.setStatus(StatusCode.ERROR, Option(e.getMessage).getOrElse(e.getClass.getName))
    span.recordException(e)
    span.end()
    current.set(parent)
    throw e

object MlflowTracing:
  private val TracerName = "marola"

  def tracesEndpoint(trackingUri: String): String = s"${trackingUri.stripSuffix("/")}/v1/traces"
  def headers(experimentId: String): Map[String, String] = Map(
    "x-mlflow-experiment-id" -> experimentId
  )
  def experimentName(prefix: String): String = s"$prefix/traces"

  /**
   * The real thing: resolves the experiment id over REST first (one call, before any span), then
   * builds the OTLP/HTTP exporter. Effectful because of that lookup — `AppConfig.tracing` runs it
   * once at startup.
   */
  def apply(trackingUri: String, experimentPrefix: String): MlflowTracing < Sync =
    for experimentId <- MlflowApi.getOrCreateExperiment(
        trackingUri,
        experimentName(experimentPrefix)
      )
    yield
      val builder = OtlpHttpSpanExporter.builder().setEndpoint(tracesEndpoint(trackingUri))
      headers(experimentId).foreach((k, v) => builder.addHeader(k, v))
      withExporter(builder.build())

  /** Any `SpanExporter` — `opentelemetry-sdk-testing`'s in-memory one in `MlflowTracingSpec`. */
  def withExporter(exporter: SpanExporter): MlflowTracing =
    val provider = SdkTracerProvider
      .builder()
      .addSpanProcessor(SimpleSpanProcessor.create(exporter))
      .build()
    new MlflowTracing(provider.get(TracerName), new AtomicReference(None))
