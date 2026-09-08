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
 * run ledger uses, `MlflowApi.getOrCreateExperiment`).
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
   * builds the OTLP/HTTP exporter.
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
