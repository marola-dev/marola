package marola.ledger

import kyo.*

/**
 * Where a benchmark/prompt-compile run's params, metrics and artifacts go — the experiment-tracking
 * counterpart to `LlmClient`/`SightingStore`'s local-vs-provisioned split (`ARCHITECTURE.md` §5,
 * MIP-0010). `RunLedger.Noop` is the default: nothing is logged and no transport exists until
 * `MAROLA_MLFLOW_TRACKING_URI` is set, so `just benchmark`/`just run` behave exactly as before this
 * MIP unless a caller opts in. `local.ledger.MlflowRunLedger` (task 2) is the one real
 * implementation for now — there is no Azure equivalent yet (MIP §4.5 is still Draft).
 */
trait RunLedger:
  def start(experiment: String, name: String, params: Map[String, String]): RunLedger.RunHandle <
    Sync
  def metrics(run: RunLedger.RunHandle, values: Map[String, Double], step: Int = 0): Unit < Sync
  def artifact(run: RunLedger.RunHandle, path: java.nio.file.Path): Unit < Sync
  def end(run: RunLedger.RunHandle, ok: Boolean): Unit < Sync

object RunLedger:
  /**
   * `experimentId`/`runId` are the backend's own identifiers; `url` is a link to the run in its UI,
   * when the backend has one.
   */
  final case class RunHandle(experimentId: String, runId: String, url: Option[String])

  val Noop: RunLedger = new RunLedger:
    def start(experiment: String, name: String, params: Map[String, String]): RunHandle < Sync =
      Sync.defer(RunHandle(experimentId = experiment, runId = "noop", url = None))
    def metrics(run: RunHandle, values: Map[String, Double], step: Int): Unit < Sync =
      Sync.defer(())
    def artifact(run: RunHandle, path: java.nio.file.Path): Unit < Sync = Sync.defer(())
    def end(run: RunHandle, ok: Boolean): Unit < Sync = Sync.defer(())
