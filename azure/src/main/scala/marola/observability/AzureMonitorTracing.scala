package marola.observability

import kyo.*

import com.azure.monitor.opentelemetry.autoconfigure.AzureMonitorAutoConfigure
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

/**
 * `Tracing` backend for Application Insights via OpenTelemetry — `MAROLA_TRACES=azure` (or the
 * unset backward-compat default when `APPLICATIONINSIGHTS_CONNECTION_STRING` is set), off by
 * default (same local-vs-Azure pattern as every other integration in this module: `LlmClient`,
 * `SightingStore`, `VisionClient`).
 */
final class AzureMonitorTracing private (otel: OpenTelemetry) extends Tracing:
  import AzureMonitorTracing.TracerName

  def withSpan[A, S](name: String, attributes: Map[String, String])(
      effect: A < (Sync & S)
  ): A < (Sync & S) =
    for
      span <- Sync.defer {
        val builder = otel.getTracer(TracerName).spanBuilder(name)
        attributes.foreach((k, v) => builder.setAttribute(k, v))
        builder.startSpan()
      }
      result <- effect
      _ <- Sync.defer(span.end())
    yield result

object AzureMonitorTracing:
  private val TracerName = "marola"

  def apply(connectionString: String): AzureMonitorTracing =
    val builder = AutoConfiguredOpenTelemetrySdk.builder()
    AzureMonitorAutoConfigure.customize(builder, connectionString)
    new AzureMonitorTracing(builder.build().getOpenTelemetrySdk())
