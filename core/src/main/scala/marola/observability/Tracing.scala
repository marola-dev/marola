package marola.observability

import kyo.*

/**
 * Optional observability seam for infra-level tracing (HTTP calls, latency, errors) around the
 * pipeline — see `docs/MIPs/MIP-0010-mlflow-experiment-tracking.md` §5.
 */
trait Tracing:
  /**
   * `S` carries whatever other effects the wrapped block has (`Async` for `Main`'s console output
   * around the pipeline); the backends only need `Sync` themselves.
   */
  def withSpan[A, S](name: String, attributes: Map[String, String] = Map.empty)(
      effect: A < (Sync & S)
  ): A < (Sync & S)

  /** One LLM call: a span named `llm.<model>`. */
  def llmSpan[A, S](model: String, attributes: Map[String, String])(effect: A < (Sync & S))(
      result: A => Map[String, String]
  ): A < (Sync & S) = withSpan(s"llm.$model", attributes)(effect)

object Tracing:

  /** `MAROLA_TRACES=off` (or unset with no backend configured): runs `effect` unchanged. */
  object Noop extends Tracing:
    def withSpan[A, S](name: String, attributes: Map[String, String])(
        effect: A < (Sync & S)
    ): A < (Sync & S) = effect
