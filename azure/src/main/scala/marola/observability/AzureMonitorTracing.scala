package marola.observability

import kyo.*

import com.azure.monitor.opentelemetry.autoconfigure.AzureMonitorAutoConfigure
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

/**
 * `Tracing` backend for Application Insights via OpenTelemetry — `MAROLA_TRACES=azure` (or the
 * unset backward-compat default when `APPLICATIONINSIGHTS_CONNECTION_STRING` is set), off by
 * default (same local-vs-Azure pattern as every other integration in this module: `LlmClient`,
 * `SightingStore`, `VisionClient`). Complements Langfuse's *LLM-specific* tracing (`dspy`'s offline
 * compile step) with infra-level tracing of the whole request (HTTP calls, latency, errors) — a
 * different layer, not a replacement.
 *
 * This is `Telemetry.scala`'s original logic, moved verbatim behind the `core.observability.
 * Tracing` trait (MIP-0010 tracing-lane task 5) so the pipeline no longer needs an
 * `Option[OpenTelemetry]` threaded through every call site — construction (`apply`) and use
 * (`withSpan`) are now separate the same way `AppConfig.llmClient` builds a `LlmClient` once.
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
final class AzureMonitorTracing private (otel: OpenTelemetry) extends Tracing:
  import AzureMonitorTracing.TracerName

  def withSpan[A](name: String)(effect: A < Sync): A < Sync =
    for
      span <- Sync.defer(otel.getTracer(TracerName).spanBuilder(name).startSpan())
      result <- effect
      _ <- Sync.defer(span.end())
    yield result

object AzureMonitorTracing:
  private val TracerName = "marola"

  def apply(connectionString: String): AzureMonitorTracing =
    val builder = AutoConfiguredOpenTelemetrySdk.builder()
    AzureMonitorAutoConfigure.customize(builder, connectionString)
    new AzureMonitorTracing(builder.build().getOpenTelemetrySdk())
