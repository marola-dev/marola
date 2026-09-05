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
 * (Application Insights via OpenTelemetry — the former `Telemetry.scala`, moved) and
 * `local.MlflowTracing` (OTLP/HTTP to an MLflow server, MIP-0010 task 6). `Noop` is the
 * zero-dependency default: no span, no vendor SDK, just the wrapped effect.
 *
 * Attributes are plain `Map[String, String]` here — the backends turn them into their SDK's typed
 * attributes — so `core` callers (`llm.TracedLlmClient`) can name GenAI semantic-convention keys
 * (`gen_ai.request.model`, …) without a vendor type. `llmSpan`'s second attribute set is derived
 * from the *result* (completion length, and the completion text when content tracing is on), which
 * is only known once the effect has run — hence a function, not a map.
 */
trait Tracing:
  /**
   * `S` carries whatever other effects the wrapped block has (`Async` for `Main`'s console output
   * around the pipeline); the backends only need `Sync` themselves.
   */
  def withSpan[A, S](name: String, attributes: Map[String, String] = Map.empty)(
      effect: A < (Sync & S)
  ): A < (Sync & S)

  /**
   * One LLM call: a span named `llm.<model>`. The default keeps backends that have no LLM-specific
   * notion (Azure Monitor today) correct: same span, start attributes kept, result attributes
   * dropped. `MlflowTracing` overrides it to record both.
   */
  def llmSpan[A, S](model: String, attributes: Map[String, String])(effect: A < (Sync & S))(
      result: A => Map[String, String]
  ): A < (Sync & S) = withSpan(s"llm.$model", attributes)(effect)

object Tracing:

  /** `MAROLA_TRACES=off` (or unset with no backend configured): runs `effect` unchanged. */
  object Noop extends Tracing:
    def withSpan[A, S](name: String, attributes: Map[String, String])(
        effect: A < (Sync & S)
    ): A < (Sync & S) = effect
