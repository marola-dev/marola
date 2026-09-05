package marola.observability

import kyo.*

/**
 * Optional observability seam for infra-level tracing (HTTP calls, latency, errors) around the
 * pipeline — see `docs/mips/MIP-0010-mlflow-experiment-tracking.md` §5. Lives in `core` (unlike its
 * backends) so the pipeline can call `withSpan`/`llmSpan` without pulling in any observability
 * vendor SDK: `core` has zero Azure reference by design (`AGENTS.md`), and that boundary extends
 * here too — no OpenTelemetry (or any other tracing) dependency in this file or this module.
 *
 * Two backends are chosen via `AppConfig`'s `MAROLA_TRACES` switch: `azure.AzureMonitorTracing`
 * (Application Insights via OpenTelemetry — today's `Telemetry.scala`, moved) and, later,
 * `local.MlflowTracing` (task 6 of the MIP-0010 tracing lane — OTLP/HTTP to an MLflow server).
 * `Noop` is the zero-dependency default: no span, no vendor SDK, just the wrapped effect.
 *
 * `llmSpan` exists as a distinct method (rather than callers reusing `withSpan` with a naming
 * convention) so a future backend can attach LLM-specific `gen_ai.*` semantic-convention attributes
 * (request model, token counts) without changing every call site again — task 6's job. The default
 * here just delegates to `withSpan`, which is a reasonable no-op-shaped default until a backend
 * actually implements the distinction.
 */
trait Tracing:
  def withSpan[A](name: String)(effect: A < Sync): A < Sync

  def llmSpan[A](model: String)(effect: A < Sync): A < Sync = withSpan(s"llm.$model")(effect)

object Tracing:

  /** `MAROLA_TRACES=off` (or unset with no backend configured): runs `effect` unchanged. */
  object Noop extends Tracing:
    def withSpan[A](name: String)(effect: A < Sync): A < Sync = effect
