package marola.ledger

import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, Flow}

import scala.collection.mutable.ListBuffer

import kyo.*

import marola.http.Http
import marola.json.JsonValue

/**
 * `MlflowRunLedger` against a scripted `Http.Transport` — same pattern as `HttpSpec`'s `Scripted`:
 * no network, deterministic responses fed in call order, every request recorded for assertion.
 */
class MlflowRunLedgerSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  private def run[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  /**
   * One recorded call: HTTP method, full URL, and the request body as text (empty for a body-less
   * GET).
   */
  final class Scripted(responses: Http.Response*) extends Http.Transport:
    val requests: ListBuffer[(String, String, String)] = ListBuffer.empty
    private var i = 0
    def send(request: HttpRequest): Http.Response =
      requests += ((request.method(), request.uri().toString, bodyOf(request)))
      val response = responses(math.min(i, responses.size - 1))
      i += 1
      response

  /**
   * `HttpRequest`'s body lives behind a `Flow.Publisher`; `BodyPublishers.ofString`/`ofByteArray`
   * publish everything to the first subscriber synchronously on `subscribe`, so a blocking
   * `CountDownLatch` is safe here — this never talks to a real, asynchronous server.
   */
  private def bodyOf(request: HttpRequest): String =
    if request.bodyPublisher().isEmpty then ""
    else
      val sb = new StringBuilder
      val latch = new CountDownLatch(1)
      request
        .bodyPublisher()
        .get()
        .subscribe(new Flow.Subscriber[ByteBuffer]:
          def onSubscribe(s: Flow.Subscription): Unit = s.request(Long.MaxValue)
          def onNext(item: ByteBuffer): Unit =
            val bytes = new Array[Byte](item.remaining())
            item.get(bytes)
            sb.append(new String(bytes, UTF_8))
          def onError(t: Throwable): Unit = latch.countDown()
          def onComplete(): Unit = latch.countDown()
        )
      latch.await()
      sb.toString

  private val trackingUri = "http://127.0.0.1:5000"
  private val fixedNowMs = 1_757_000_000_000L
  private def ledger: MlflowRunLedger = MlflowRunLedger(trackingUri, now = () => fixedNowMs)

  private def obj(body: String): JsonValue = JsonValue.parse(body)

  private val getByNameFound = Http.Response(
    200,
    JsonValue.obj("experiment" -> JsonValue.obj("experiment_id" -> JsonValue.str("7"))).render
  )
  private val getByNameMissing = Http.Response(404, """{"error_code":"RESOURCE_DOES_NOT_EXIST"}""")
  private val experimentCreated =
    Http.Response(200, JsonValue.obj("experiment_id" -> JsonValue.str("7")).render)
  private def runCreated(runId: String = "run-abc") = Http.Response(
    200,
    JsonValue
      .obj("run" -> JsonValue.obj("info" -> JsonValue.obj("run_id" -> JsonValue.str(runId))))
      .render
  )
  private val logBatchOk = Http.Response(200, "{}")
  private val handle = RunLedger.RunHandle(experimentId = "7", runId = "run-abc", url = None)

  test(
    "start: an existing experiment (200) goes straight to runs/create, params via one log-batch"
  ) {
    val t = Scripted(getByNameFound, runCreated(), logBatchOk)
    val result = Http.withTransport(t)(
      run(ledger.start("marola/benchmark", "run-1", Map("model" -> "llama3.2")))
    )

    assertEquals(
      result,
      RunLedger.RunHandle(
        "7",
        "run-abc",
        Some("http://127.0.0.1:5000/#/experiments/7/runs/run-abc")
      )
    )
    assertEquals(t.requests.size, 3)

    val (getMethod, getUrl, _) = t.requests(0)
    assertEquals(getMethod, "GET")
    assert(getUrl.startsWith("http://127.0.0.1:5000/api/2.0/mlflow/experiments/get-by-name?"))
    assert(getUrl.contains("experiment_name=marola%2Fbenchmark"))

    val (createMethod, createUrl, createBody) = t.requests(1)
    assertEquals(createMethod, "POST")
    assertEquals(createUrl, "http://127.0.0.1:5000/api/2.0/mlflow/runs/create")
    val created = obj(createBody)
    assertEquals(created("experiment_id").str, Some("7"))
    assertEquals(created("run_name").str, Some("run-1"))
    assertEquals(created("start_time").num, Some(fixedNowMs.toDouble))

    val (batchMethod, batchUrl, batchBody) = t.requests(2)
    assertEquals(batchMethod, "POST")
    assertEquals(batchUrl, "http://127.0.0.1:5000/api/2.0/mlflow/runs/log-batch")
    val batch = obj(batchBody)
    assertEquals(batch("run_id").str, Some("run-abc"))
    val params = batch("params").arr
    assertEquals(params.size, 1)
    assertEquals(params.head("key").str, Some("model"))
    assertEquals(params.head("value").str, Some("llama3.2"))
  }

  test("start: a missing experiment (404) is created first, then the run") {
    val t = Scripted(getByNameMissing, experimentCreated, runCreated())
    val result = Http.withTransport(t)(run(ledger.start("marola/benchmark", "run-1", Map.empty)))

    assertEquals(result.experimentId, "7")
    assertEquals(t.requests.size, 3)
    assertEquals(t.requests(0)._1, "GET")
    val (createExpMethod, createExpUrl, createExpBody) = t.requests(1)
    assertEquals(createExpMethod, "POST")
    assertEquals(createExpUrl, "http://127.0.0.1:5000/api/2.0/mlflow/experiments/create")
    assertEquals(obj(createExpBody)("name").str, Some("marola/benchmark"))
    assertEquals(t.requests(2)._1, "POST")
    assertEquals(t.requests(2)._2, "http://127.0.0.1:5000/api/2.0/mlflow/runs/create")
  }

  test("metrics: 1500 values chunk into two log-batch calls, 1000 then 500") {
    val t = Scripted(logBatchOk, logBatchOk)
    val values = (0 until 1500).map(i => s"m$i" -> i.toDouble).toMap
    Http.withTransport(t)(run(ledger.metrics(handle, values)))

    assertEquals(t.requests.size, 2)
    val sizes = t.requests.map { case (_, _, body) => obj(body)("metrics").arr.size }.toList
    assertEquals(sizes.sorted, List(500, 1000))
    assertEquals(sizes.sum, 1500)
    t.requests.foreach {
      case (method, url, _) =>
        assertEquals(method, "POST")
        assertEquals(url, "http://127.0.0.1:5000/api/2.0/mlflow/runs/log-batch")
    }
  }

  test("metrics: fewer than 1000 entries send exactly one call") {
    val t = Scripted(logBatchOk)
    Http.withTransport(t)(run(ledger.metrics(handle, Map("coverage" -> 0.84), step = 3)))

    assertEquals(t.requests.size, 1)
    val metric = obj(t.requests.head._3)("metrics").arr.head
    assertEquals(metric("key").str, Some("coverage"))
    assertEquals(metric("value").num, Some(0.84))
    assertEquals(metric("step").num, Some(3.0))
  }

  test("params: 150 entries chunk into two log-batch calls, 100 then 50") {
    val t = Scripted(getByNameFound, runCreated(), logBatchOk, logBatchOk)
    val params = (0 until 150).map(i => s"p$i" -> i.toString).toMap
    val _ = Http.withTransport(t)(run(ledger.start("marola/benchmark", "run-1", params)))

    val batchRequests = t.requests.drop(2)
    assertEquals(batchRequests.size, 2)
    val sizes = batchRequests.map { case (_, _, body) => obj(body)("params").arr.size }.toList
    assertEquals(sizes.sorted, List(50, 100))
  }

  test("a 251-character key is truncated to 250 in the outgoing request, with a stderr warning") {
    val longKey = "k" * 251
    val t = Scripted(logBatchOk)
    val previousErr = java.lang.System.err
    val captured = new java.io.ByteArrayOutputStream()
    java.lang.System.setErr(new java.io.PrintStream(captured))
    try Http.withTransport(t)(run(ledger.metrics(handle, Map(longKey -> 1.0))))
    finally java.lang.System.setErr(previousErr)

    val sentKey = obj(t.requests.head._3)("metrics").arr.head("key").str.getOrElse(fail("no key"))
    assertEquals(sentKey.length, 250)
    assertEquals(sentKey, longKey.take(250))
    assert(
      captured.toString(UTF_8).contains("warning"),
      s"expected a warning on stderr, got: ${captured.toString(UTF_8)}"
    )
  }

  test("end(ok = true) sends FINISHED") {
    val t = Scripted(Http.Response(200, "{}"))
    Http.withTransport(t)(run(ledger.end(handle, ok = true)))
    val (method, url, body) = t.requests.head
    assertEquals(method, "POST")
    assertEquals(url, "http://127.0.0.1:5000/api/2.0/mlflow/runs/update")
    assertEquals(obj(body)("run_id").str, Some("run-abc"))
    assertEquals(obj(body)("status").str, Some("FINISHED"))
  }

  test("end(ok = false) sends FAILED") {
    val t = Scripted(Http.Response(200, "{}"))
    Http.withTransport(t)(run(ledger.end(handle, ok = false)))
    assertEquals(obj(t.requests.head._3)("status").str, Some("FAILED"))
  }

  test("artifact: PUT to the mlflow-artifacts proxy path, raw file bytes as the body") {
    val file = Files.createTempFile("mlflow-run-ledger-spec", ".md")
    Files.writeString(file, "# report\n")
    try
      val t = Scripted(Http.Response(200, ""))
      Http.withTransport(t)(run(ledger.artifact(handle, file)))

      assertEquals(t.requests.size, 1)
      val (method, url, body) = t.requests.head
      assertEquals(method, "PUT")
      assertEquals(
        url,
        s"http://127.0.0.1:5000/api/2.0/mlflow-artifacts/artifacts/7/run-abc/artifacts/${file.getFileName}"
      )
      assertEquals(body, "# report\n")
    finally
      val _ = Files.deleteIfExists(file)
  }
