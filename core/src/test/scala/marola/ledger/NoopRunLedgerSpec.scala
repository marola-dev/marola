package marola.ledger

import kyo.*

/**
 * `RunLedger.Noop` is the default backend (MIP-0010 §5) — every method must be a pure in-memory
 * return with no transport call, so `just benchmark`/`just run` cost nothing extra when
 * `MAROLA_MLFLOW_TRACKING_URI` is unset.
 */
class NoopRunLedgerSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  private def run[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  test("start returns a handle carrying the experiment name, no url") {
    val handle = run(RunLedger.Noop.start("marola/benchmark", "run-1", Map("model" -> "llama3.2")))
    assertEquals(handle.experimentId, "marola/benchmark")
    assertEquals(handle.url, None)
  }

  test("metrics, artifact and end all no-op without touching the handle") {
    val handle = run(RunLedger.Noop.start("marola/benchmark", "run-1", Map.empty))
    run(RunLedger.Noop.metrics(handle, Map("coverage" -> 0.84)))
    run(RunLedger.Noop.artifact(handle, java.nio.file.Path.of("data/benchmark-20260905.md")))
    run(RunLedger.Noop.end(handle, ok = true))
  }

  test("end(ok = false) does not throw — the Noop backend has no FAILED status to set") {
    val handle = run(RunLedger.Noop.start("marola/benchmark", "run-1", Map.empty))
    run(RunLedger.Noop.end(handle, ok = false))
  }
