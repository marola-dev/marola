package marola.ledger

import java.nio.file.{Files, Path}

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.ledger.MlflowApi.field
import marola.ledger.RunLedger.RunHandle

/**
 * REST client for a local `mlflow server` (MIP-0010 §4.2 — REST over `org.mlflow:mlflow-client`:
 * four tracking endpoints plus one artifact-proxy call, no new dependency, no version lag against
 * whatever server tag is running). `trackingUri` is the bare server root, e.g.
 * `http://127.0.0.1:5000` (a trailing slash is tolerated — stripped before use); every tracking
 * call below appends `/api/2.0/mlflow/...` to it, confirmed against
 * `https://mlflow.org/docs/latest/api_reference/rest-api.html` (fetched 2026-09-05):
 *
 *   - `GET 2.0/mlflow/experiments/get-by-name` (fields as a query string, not a JSON body — this is
 *     the one GET endpoint used here)
 *   - `POST 2.0/mlflow/experiments/create`
 *   - `POST 2.0/mlflow/runs/create`
 *   - `POST 2.0/mlflow/runs/log-batch` — capped at 1000 metrics OR 100 params per call, 250-char
 *     keys; `logParams`/`logMetrics` chunk to those caps, `truncateKey` shortens an oversized key
 *     and warns rather than sending a request the server would reject.
 *   - `POST 2.0/mlflow/runs/update` — `RunStatus` `FINISHED`/`FAILED`.
 *
 * `now` is injected (defaults to the wall clock) purely for deterministic tests — the same shape as
 * `Recommender`'s injected `today`.
 *
 * Artifact upload (`artifact`) does not use `POST 2.0/mlflow/artifacts/presigned-upload-url` — that
 * RPC hands back a cloud-storage URL, meaningless for the filesystem-backed local server this MIP
 * targets. It instead goes through the server's own artifact-store proxy, started with
 * `--serve-artifacts` (MIP §4.1). That proxy's REST surface is not on the
 * `api_reference/rest-api.html` page — confirmed instead from
 * `mlflow/protos/mlflow_artifacts.proto` (fetched 2026-09-05 via GitHub) plus a matching MLflow
 * example (`examples/mlflow_artifacts/README.md`): `PUT
 * /api/2.0/mlflow-artifacts/artifacts/<path>`, raw bytes as the body. `<path>` here is built as
 * `<experimentId>/<runId>/artifacts/<fileName>`, which is the default `mlflow-artifacts:/` scheme's
 * HTTP mapping when the server is started with `--serve-artifacts` and no `--default-artifact-root`
 * override — exactly MIP §4.1's own command line, so this is what task 3's compose profile will
 * run. A server given a different `--default-artifact-root` would need the run's own `artifact_uri`
 * (from `runs/create`'s response) parsed instead of this constructed path; that's the concrete
 * trigger for MIP §11 OQ5 (fall back to `org.mlflow:mlflow-client`'s `logArtifact` for this one
 * call) if it turns out to matter in practice. `Http` gained one addition for this: `putBytes`,
 * mirroring `postBytes` but issuing `PUT` — the artifact proxy is the only marola caller that needs
 * that verb.
 */
final class MlflowRunLedger(
    trackingUri: String,
    now: () => Long = () => java.lang.System.currentTimeMillis()
) extends RunLedger:

  import MlflowRunLedger.*

  private val root = trackingUri.stripSuffix("/")
  private val base = s"$root/api/2.0/mlflow"

  def start(experiment: String, name: String, params: Map[String, String]): RunHandle < Sync =
    for
      experimentId <- getOrCreateExperiment(experiment)
      runId <- createRun(experimentId, name)
      _ <- if params.isEmpty then Sync.defer(()) else logParams(runId, params)
    yield RunHandle(experimentId, runId, Some(runUrl(experimentId, runId)))

  def metrics(run: RunHandle, values: Map[String, Double], step: Int = 0): Unit < Sync =
    logMetrics(run.runId, values, step)

  def artifact(run: RunHandle, path: Path): Unit < Sync =
    for
      bytes <- Sync.defer(Files.readAllBytes(path))
      url =
        s"$root/api/2.0/mlflow-artifacts/artifacts/${run.experimentId}/${run.runId}/artifacts/" +
          path.getFileName.toString
      _ <- Http.putBytes(url, bytes)
    yield ()

  def end(run: RunHandle, ok: Boolean): Unit < Sync =
    val body = JsonValue.obj(
      "run_id" -> JsonValue.str(run.runId),
      "status" -> JsonValue.str(if ok then "FINISHED" else "FAILED"),
      "end_time" -> JsonValue.num(now().toDouble)
    )
    Http.postJson(s"$base/runs/update", body.render).map(_ => ())

  private def runUrl(experimentId: String, runId: String): String =
    s"$root/#/experiments/$experimentId/runs/$runId"

  private def getOrCreateExperiment(name: String): String < Sync =
    MlflowApi.getOrCreateExperiment(trackingUri, name)

  private def createRun(experimentId: String, name: String): String < Sync =
    val body = JsonValue.obj(
      "experiment_id" -> JsonValue.str(experimentId),
      "run_name" -> JsonValue.str(name),
      "start_time" -> JsonValue.num(now().toDouble)
    )
    Http
      .postJson(s"$base/runs/create", body.render)
      .map(resp => field(JsonValue.parse(resp)("run")("info"), "run_id"))

  private def logParams(runId: String, params: Map[String, String]): Unit < Sync =
    sequence_(
      params.grouped(MaxParamsPerCall).toList.map(chunk => sendLogBatch(runId, params = chunk))
    )

  private def logMetrics(runId: String, metrics: Map[String, Double], step: Int): Unit < Sync =
    sequence_(
      metrics
        .grouped(MaxMetricsPerCall)
        .toList
        .map(chunk => sendLogBatch(runId, metrics = chunk, step = step))
    )

  private def sendLogBatch(
      runId: String,
      params: Map[String, String] = Map.empty,
      metrics: Map[String, Double] = Map.empty,
      step: Int = 0
  ): Unit < Sync =
    Sync
      .defer {
        val paramFields =
          if params.isEmpty then Nil
          else
            List(
              "params" -> JsonValue.arr(params.toSeq.map {
                case (k, v) =>
                  JsonValue.obj("key" -> JsonValue.str(truncateKey(k)), "value" -> JsonValue.str(v))
              }*)
            )
        val metricFields =
          if metrics.isEmpty then Nil
          else
            List(
              "metrics" -> JsonValue.arr(metrics.toSeq.map {
                case (k, v) =>
                  JsonValue.obj(
                    "key" -> JsonValue.str(truncateKey(k)),
                    "value" -> JsonValue.num(v),
                    "timestamp" -> JsonValue.num(now().toDouble),
                    "step" -> JsonValue.num(step.toDouble)
                  )
              }*)
            )
        JsonValue
          .obj((List("run_id" -> JsonValue.str(runId)) ++ metricFields ++ paramFields)*)
          .render
      }
      .map(body => Http.postJson(s"$base/runs/log-batch", body))
      .map(_ => ())

  /**
   * Sequential, order-preserving `Unit < Sync` runner — same recursive-for-comprehension shape as
   * `Recommender.traverseSingle`, specialised to "run each, keep none of the results".
   */
  private def sequence_(effects: List[Unit < Sync]): Unit < Sync =
    effects match
      case Nil => Sync.defer(())
      case head :: tail =>
        for
          _ <- head
          _ <- sequence_(tail)
        yield ()

object MlflowRunLedger:

  /**
   * `runs/log-batch`'s own caps (MIP §4.2, MLflow's REST reference, fetched 2026-09-05): "up to
   * 1000 metrics", "up to 100 params" per call.
   */
  private val MaxMetricsPerCall = 1000
  private val MaxParamsPerCall = 100

  /** "Metric, param, and tag keys can be up to 250 characters in length" (same source). */
  private val MaxKeyLength = 250

  /**
   * Truncates an oversized param/metric key to `MaxKeyLength` and warns on stderr — there is no
   * existing logger in `local/` to route through (`Console.printLine` in `cli/Main` is a
   * user-facing CLI message, not a warning sink other modules use), so this follows `Http.scala`'s
   * own precedent of a plain, undecorated stderr line for something the operator should notice but
   * that must not fail the run.
   */
  private def truncateKey(key: String): String =
    if key.length <= MaxKeyLength then key
    else
      java.lang.System.err.println(
        s"warning: mlflow key '$key' (${key.length} chars) truncated to $MaxKeyLength"
      )
      key.take(MaxKeyLength)
