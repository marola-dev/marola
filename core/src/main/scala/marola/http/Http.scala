package marola.http

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

import kyo.*

/**
 * Thin `java.net.http.HttpClient` wrapper at the Kyo effect boundary, kept deliberately instead of
 * migrating to kyo-http's own client. That's no longer because kyo-http's API is unverified — it IS
 * real and present at this exact 1.0.0-RC5 version (confirmed by decompiling the jar; see
 * build.sbt's note) — it's because this hand-rolled version is already live-verified against every
 * real API this module calls (Overpass, Open-Meteo, Ollama, Telegram's shape, Azure Maps/Vision),
 * and migrating now would mean re-verifying all of that against a less-documented library API for a
 * marginal win. Tracked as real future work, not a "can't do it" — see `docs/FUTURE-WORK.md`.
 * `java.net.http.HttpClient` calls go through `Sync.defer` — a genuinely blocking call, but Kyo's
 * scheduler detects and reacts to CPU-blocked workers on its own (no explicit `blocking { }` marker
 * needed, per Kyo's docs).
 */
object Http:

  final case class HttpError(status: Int, url: String, bodySnippet: String)
      extends Exception(s"HTTP $status for $url: $bodySnippet")

  /** What a transport returns — just the two things every caller here reads. */
  final case class Response(status: Int, body: String)

  /**
   * The one seam between marola and the network. `Transport.Live` is `java.net.http`; tests install
   * a replay transport that serves recorded real responses by URL
   * (`cli/src/test/scala/marola/PipelineGoldenSpec.scala`), which is how the whole pipeline is
   * regression-tested offline — no Overpass, no Open-Meteo, no Ollama, no CI minutes.
   */
  trait Transport:
    def send(request: HttpRequest): Response

  object Transport:
    val Live: Transport = new Transport:
      private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
      def send(request: HttpRequest): Response =
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        Response(response.statusCode(), response.body())

  private val transport = new java.util.concurrent.atomic.AtomicReference[Transport](Transport.Live)

  /**
   * Test seam: runs `body` with `t` installed, restoring the previous transport afterwards. A
   * process-wide switch, so test suites that use it must not run concurrently with each other (sbt
   * runs a project's suites in parallel by default; `build.sbt` sets `Test / parallelExecution :=
   * false` for exactly this reason — keep it).
   */
  def withTransport[A](t: Transport)(body: => A): A =
    val previous = transport.getAndSet(t)
    try body
    finally
      transport.set(previous)
      ()

  private def check(url: String, response: Response, snippet: Int): String =
    if response.status / 100 == 2 then response.body
    else throw HttpError(response.status, url, response.body.take(snippet))

  /**
   * Statuses worth another attempt — a rate limit or an overloaded/absent upstream, never a client
   * error: 429, 502, 503, 504. Overpass's public instance answers 504 (gateway timeout) under load,
   * observed live on 5 Sep 2026; one such answer on the second area stopped a scheduled site build
   * after the first area had been written (`site.yml`, `Main.buildSite`).
   */
  val RetryableStatuses: Set[Int] = Set(429, 502, 503, 504)

  /** `baseMs · 2^attempt`: 1 s, 2 s, 4 s ... for the default base. */
  private[http] def backoffFor(baseMs: Long, attempt: Int): Long = baseMs << attempt

  /**
   * Failures worth another attempt before any response arrived: a connect timeout
   * (`HttpConnectTimeoutException`, 10 s in `Transport.Live`), a read timeout on the request's own
   * budget (`HttpTimeoutException`, its parent), or a refused/reset connection
   * (`ConnectException`). All transient by nature — the 2026-09-06 scheduled site build died on one
   * `HTTP connect timed out` from a GitHub runner with no retry at all (`site.yml`,
   * `Main.buildSite`). A malformed URL, a TLS failure or an `HttpError` from `check` are not in
   * this set: they repeat identically.
   */
  private[http] def isRetryableFailure(t: Throwable): Boolean = t match
    case _: java.net.http.HttpTimeoutException => true
    case _: java.net.ConnectException          => true
    case _                                     => false

  /**
   * One send, plus up to `retries` more after a retryable status or a retryable failure
   * (`isRetryableFailure`), sleeping `backoffFor` between them. Blocking, like the send itself (see
   * the class comment on why that is fine here). The last response is returned whatever its status
   * — `check` turns a non-2xx into `HttpError` — and the last failure is rethrown as is.
   */
  private def sendRetrying(request: HttpRequest, retries: Int, backoffMs: Long): Response =
    @annotation.tailrec
    def loop(attempt: Int): Response =
      val outcome: Either[Throwable, Response] =
        try Right(transport.get.send(request))
        catch case t: Throwable if isRetryableFailure(t) => Left(t)
      outcome match
        case Right(response) if attempt < retries && RetryableStatuses.contains(response.status) =>
          Thread.sleep(backoffFor(backoffMs, attempt))
          loop(attempt + 1)
        case Right(response) => response
        case Left(_) if attempt < retries =>
          Thread.sleep(backoffFor(backoffMs, attempt))
          loop(attempt + 1)
        case Left(failure) => throw failure
    loop(0)

  private def userAgent = "marola/0.1 (+https://github.com/h0ffmann/marola)"

  /**
   * `headers`: e.g. an API key that must not go in the URL (`RouteFinder`). `retries` extra
   * attempts after a `RetryableStatuses` answer or a retryable failure (none by default),
   * `backoffMs` doubling each time — `OpenMeteoClient` asks for two, so a connect timeout on one of
   * a board's ~160 forecast calls no longer ends the whole scheduled site build.
   */
  def getString(
      url: String,
      headers: Map[String, String] = Map.empty,
      retries: Int = 0,
      backoffMs: Long = 1000
  ): String < Sync =
    Sync.defer {
      val builder = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("User-Agent", userAgent)
        .GET()
      headers.foreach { case (k, v) => builder.header(k, v) }
      check(url, sendRetrying(builder.build(), retries, backoffMs), 300)
    }

  /**
   * `application/x-www-form-urlencoded` POST — Overpass's query API and Telegram's Bot API both
   * accept this for their respective single-field payloads. `timeoutSeconds` exists because
   * Overpass genuinely takes tens of seconds for relation-aware area queries under load (confirmed:
   * ~29s for a 15km beach query around Florianópolis), which the old fixed 15s cut off with
   * `HttpTimeoutException`; `BeachFinder` passes a value above Overpass's own server-side
   * `[timeout:...]` so the server's error, not a client abort, is what surfaces. `retries` extra
   * attempts are made after a `RetryableStatuses` answer (none by default), `backoffMs` doubling
   * each time.
   */
  def postForm(
      url: String,
      form: Map[String, String],
      timeoutSeconds: Long = 15,
      retries: Int = 0,
      backoffMs: Long = 1000
  ): String < Sync =
    Sync.defer {
      val encoded = form
        .map { case (k, v) => s"${URLEncoder.encode(k, UTF_8)}=${URLEncoder.encode(v, UTF_8)}" }
        .mkString("&")
      val request = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("User-Agent", userAgent)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(encoded))
        .build()
      check(url, sendRetrying(request, retries, backoffMs), 300)
    }

  /**
   * `application/json` POST with arbitrary extra headers (e.g. `Authorization: Bearer ...`) — used
   * by both `llm` clients. `timeoutSeconds` defaults far higher than the other two methods here: a
   * local CPU-served model (Ollama) genuinely takes tens of seconds per completion, confirmed
   * against a real local model (~46s for a trivial reply) while building `LocalLlmClient`.
   */
  def postJson(
      url: String,
      jsonBody: String,
      headers: Map[String, String] = Map.empty,
      timeoutSeconds: Long = 120
  ): String < Sync =
    Sync.defer {
      val builder = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("User-Agent", userAgent)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
      headers.foreach { case (k, v) => builder.header(k, v) }
      check(url, transport.get.send(builder.build()), 500)
    }

  /**
   * Raw-binary POST (`application/octet-stream` by default) — Azure AI Vision's Image Analysis API
   * takes the image bytes directly in the request body rather than as JSON (see
   * `AzureVisionClient`'s doc comment).
   */
  def postBytes(
      url: String,
      body: Array[Byte],
      contentType: String = "application/octet-stream",
      headers: Map[String, String] = Map.empty,
      timeoutSeconds: Long = 30
  ): String < Sync =
    Sync.defer {
      val builder = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("User-Agent", userAgent)
        .header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
      headers.foreach { case (k, v) => builder.header(k, v) }
      check(url, transport.get.send(builder.build()), 500)
    }

  /**
   * Raw-binary PUT — `MlflowRunLedger.artifact`'s one caller: MLflow's artifact-store proxy
   * (`mlflow/protos/mlflow_artifacts.proto`, `PUT .../mlflow-artifacts/artifacts/<path>`) takes the
   * file bytes directly and, unlike every other write in this module, uses `PUT` rather than
   * `POST`.
   */
  def putBytes(
      url: String,
      body: Array[Byte],
      contentType: String = "application/octet-stream",
      headers: Map[String, String] = Map.empty,
      timeoutSeconds: Long = 30
  ): String < Sync =
    Sync.defer {
      val builder = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("User-Agent", userAgent)
        .header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
      headers.foreach { case (k, v) => builder.header(k, v) }
      check(url, transport.get.send(builder.build()), 500)
    }

  /** What a binary response is — the raw bytes, never decoded as text. */
  final case class BytesResponse(status: Int, bytes: Array[Byte])

  final case class HttpBytesError(status: Int, url: String)
      extends Exception(s"HTTP $status for $url")

  /**
   * Binary-GET's own transport seam, deliberately separate from `Transport`/`Response` above rather
   * than adding a `bytes` field there: every existing caller of `getString`/`postForm`/etc. depends
   * on `Response.body` being decoded as UTF-8 text (IMA/SC's own "PRÓPRIA"/"IMPRÓPRIA" accented
   * strings, Overpass/Open-Meteo JSON) — switching the shared `HttpClient.send` call to
   * `BodyHandlers.ofByteArray()` and re-deriving `body` from an ISO-8859-1 round-trip would
   * silently corrupt every one of those already-verified text responses. A PDF bulletin
   * (INEA/INEMA, MIP-0031) is the first genuinely binary response this module fetches, so it gets
   * its own thin parallel seam instead of touching a working one.
   */
  trait BinaryTransport:
    def send(request: HttpRequest): BytesResponse

  object BinaryTransport:
    val Live: BinaryTransport = new BinaryTransport:
      private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
      def send(request: HttpRequest): BytesResponse =
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        BytesResponse(response.statusCode(), response.body())

  private val binaryTransport = new java.util.concurrent.atomic.AtomicReference[BinaryTransport](
    BinaryTransport.Live
  )

  /** Test seam for `getBytes`, mirroring `withTransport` — replay a fixture PDF/binary by URL. */
  def withBinaryTransport[A](t: BinaryTransport)(body: => A): A =
    val previous = binaryTransport.getAndSet(t)
    try body
    finally
      binaryTransport.set(previous)
      ()

  /**
   * Plain binary GET — a PDF bulletin today
   * (`InemaBaWaterQualityClient`/`IneaRjWaterQualityClient`, MIP-0031), never text-decoded. No
   * retry parameter (unlike `getString`): a malformed/partial PDF from a retry would fail
   * `InemaPdfParser`/`IneaPdfParser` cleanly rather than silently, and neither institute's endpoint
   * has shown the transient-503-under-load behaviour Overpass has.
   */
  def getBytes(url: String, timeoutSeconds: Long = 30): Array[Byte] < Sync =
    Sync.defer {
      val request = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("User-Agent", userAgent)
        .GET()
        .build()
      val response = binaryTransport.get.send(request)
      if response.status / 100 == 2 then response.bytes
      else throw HttpBytesError(response.status, url)
    }
