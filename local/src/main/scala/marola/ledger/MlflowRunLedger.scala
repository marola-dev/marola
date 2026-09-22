package marola.ledger

import java.nio.file.{Files, Path}

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.ledger.MlflowApi.field
import marola.ledger.RunLedger.RunHandle
import marola.log.Log

/**
 * REST client for a local `mlflow server` (MIP-0010 §4.2 — REST over `org.mlflow:mlflow-client`:
 * four tracking endpoints plus one artifact-proxy call, no new dependency, no version lag against
 * whatever server tag is running).
 */
final class MlflowRunLedger(
    trackingUri: String,
    now: () => Long = () => java.lang.System.currentTimeMillis()
) extends RunLedger:

  import MlflowRunLedger.*

  private val root = trackingUri.stripSuffix("/")
  private val base = MlflowApi.restBase(trackingUri)

  def start(experiment: String, name: String, params: Map[String, String]): RunHandle < Sync =
    for
      experimentId <- MlflowApi.getOrCreateExperiment(trackingUri, experiment)
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
    Kyo.foreachDiscard(params.grouped(MaxParamsPerCall).toList)(chunk =>
      sendLogBatch(runId, params = chunk)
    )

  private def logMetrics(runId: String, metrics: Map[String, Double], step: Int): Unit < Sync =
    Kyo.foreachDiscard(metrics.grouped(MaxMetricsPerCall).toList)(chunk =>
      sendLogBatch(runId, metrics = chunk, step = step)
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

object MlflowRunLedger:

  private val log = Log.forName(getClass.getName)

  /**
   * `runs/log-batch`'s own caps (MIP §4.2, MLflow's REST reference, fetched 2026-09-05): "up to
   * 1000 metrics", "up to 100 params" per call.
   */
  private val MaxMetricsPerCall = 1000
  private val MaxParamsPerCall = 100

  /** "Metric, param, and tag keys can be up to 250 characters in length" (same source). */
  private val MaxKeyLength = 250

  /** An oversized key is truncated with a warning rather than failing the run. */
  private def truncateKey(key: String): String =
    if key.length <= MaxKeyLength then key
    else
      MlflowRunLedger.log.warn(
        s"mlflow key '$key' (${key.length} chars) truncated to $MaxKeyLength"
      )
      key.take(MaxKeyLength)
