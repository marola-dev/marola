package marola.observability

import kyo.*

import com.azure.monitor.opentelemetry.autoconfigure.AzureMonitorAutoConfigure
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

/**
 * Optional observability backend — Application Insights via OpenTelemetry, off by default (same
 * local-vs-Azure pattern as every other integration in this module: `LlmClient`, `SightingStore`,
 * `VisionClient`). Complements Langfuse's *LLM-specific* tracing (`dspy`'s offline compile step)
 * with infra-level tracing of the whole request (HTTP calls, latency, errors) — a different layer,
 * not a replacement.
 *
 * API (`AutoConfiguredOpenTelemetrySdk.builder()`, `AzureMonitorAutoConfigure.customize(builder,
 * connectionString)`, `builder.build().getOpenTelemetrySdk()`) confirmed against real
 * `com.azure:azure-monitor-opentelemetry-autoconfigure:1.4.0` and
 * `io.opentelemetry:opentelemetry-sdk-extension-autoconfigure:1.49.0` jars
 * (`AutoConfiguredOpenTelemetrySdkBuilder implements AutoConfigurationCustomizer`, so it can be
 * passed straight into `customize`) — not exercised against a live Application Insights resource
 * (none provisioned; see `AGENTS.md`'s cost-safety rule).
 *
 * `withSpan` starts and ends a span around the two `Sync.defer` boundaries of a Kyo effect rather
 * than wrapping it in a true try/finally — a deliberately shallow integration for a demonstration
 * plug-in point, not production-grade tracing: a span is left unclosed if the wrapped effect
 * throws. Acceptable here because nothing in this CLI is a long-lived process where an orphaned
 * span actually accumulates or matters.
 */
object Telemetry:

  private val TracerName = "marola"

  def initialize(connectionString: Option[String]): Option[OpenTelemetry] =
    connectionString.map { cs =>
      val builder = AutoConfiguredOpenTelemetrySdk.builder()
      AzureMonitorAutoConfigure.customize(builder, cs)
      builder.build().getOpenTelemetrySdk()
    }

  def withSpan[A](otel: Option[OpenTelemetry], name: String)(effect: A < Sync): A < Sync =
    otel match
      case None => effect
      case Some(o) =>
        for
          span <- Sync.defer(o.getTracer(TracerName).spanBuilder(name).startSpan())
          result <- effect
          _ <- Sync.defer(span.end())
        yield result
