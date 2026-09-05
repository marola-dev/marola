package marola.ledger

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

import kyo.*

import marola.http.Http
import marola.json.JsonValue

/**
 * The two MLflow REST calls shared by the run ledger (`MlflowRunLedger`) and the trace exporter
 * (`observability.MlflowTracing`, MIP-0010 task 6 — MLflow's OTLP endpoint wants an experiment *id*
 * in a header, so tracing needs the same name → id resolution the ledger does). Plain
 * `Http`/`JsonValue` over `java.net.http`, no MLflow client library (MIP §4.2).
 */
object MlflowApi:

  def restBase(trackingUri: String): String = s"${trackingUri.stripSuffix("/")}/api/2.0/mlflow"

  /**
   * `experiments/get-by-name`, falling back to `experiments/create` only on a 404 (not found) — any
   * other failure (network, 5xx) is surfaced rather than masked as "must not exist yet".
   */
  def getOrCreateExperiment(trackingUri: String, name: String): String < Sync =
    val base = restBase(trackingUri)
    for
      outcome <- Abort.run(
        Abort.catching[Throwable](
          Http.getString(s"$base/experiments/get-by-name?experiment_name=${encode(name)}")
        )
      )
      experimentId <- outcome match
        case Result.Success(body) =>
          Sync.defer(field(JsonValue.parse(body)("experiment"), "experiment_id"))
        case Result.Failure(e: Http.HttpError) if e.status == 404 => createExperiment(base, name)
        case Result.Failure(e)                                    => Sync.defer(throw e)
        case Result.Panic(e)                                      => Sync.defer(throw e)
    yield experimentId

  private def createExperiment(base: String, name: String): String < Sync =
    val body = JsonValue.obj("name" -> JsonValue.str(name))
    Http
      .postJson(s"$base/experiments/create", body.render)
      .map(resp => field(JsonValue.parse(resp), "experiment_id"))

  def encode(s: String): String = URLEncoder.encode(s, UTF_8)

  def field(json: JsonValue, key: String): String =
    json(key).str.getOrElse(
      throw new RuntimeException(s"mlflow response missing '$key': ${json.render}")
    )
