package marola.http

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

import kyo.*

/**
 * Thin `java.net.http.HttpClient` wrapper at the Kyo effect boundary, kept deliberately instead of
 * migrating to kyo-http's own client.
 */
object Http:

  final case class HttpError(status: Int, url: String, bodySnippet: String)
      extends Exception(s"HTTP $status for $url: $bodySnippet")

  /** What a transport returns — just the two things every caller here reads. */
  final case class Response(status: Int, body: String)

  /** The one seam between marola and the network. */
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

  /** Test seam: runs `body` with `t` installed, restoring the previous transport afterwards. */
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
   * error: 429, 502, 503, 504.
   */
  val RetryableStatuses: Set[Int] = Set(429, 502, 503, 504)

  /** `baseMs · 2^attempt`: 1 s, 2 s, 4 s ... for the default base. */
  private[http] def backoffFor(baseMs: Long, attempt: Int): Long = baseMs << attempt

  /**
   * Failures worth another attempt before any response arrived: a connect timeout
   * (`HttpConnectTimeoutException`, 10 s in `Transport.Live`), a read timeout on the request's own
   * budget (`HttpTimeoutException`, its parent), or a refused/reset connection
   * (`ConnectException`).
   */
  private[http] def isRetryableFailure(t: Throwable): Boolean = t match
    case _: java.net.http.HttpTimeoutException => true
    case _: java.net.ConnectException          => true
    case _                                     => false

  /**
   * One send, plus up to `retries` more after a retryable status or a retryable failure
   * (`isRetryableFailure`), sleeping `backoffFor` between them.
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

  private def request(
      url: String,
      timeoutSeconds: Long,
      headers: Map[String, String] = Map.empty
  ): HttpRequest.Builder =
    val builder = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(timeoutSeconds))
      .header("User-Agent", userAgent)
    headers.foreach { case (k, v) => builder.header(k, v) }
    builder

  /** `headers`: e.g. an API key that must not go in the URL (`RouteFinder`). */
  def getString(
      url: String,
      headers: Map[String, String] = Map.empty,
      retries: Int = 0,
      backoffMs: Long = 1000
  ): String < Sync =
    Sync.defer {
      check(url, sendRetrying(request(url, 15, headers).GET().build(), retries, backoffMs), 300)
    }

  /**
   * `application/x-www-form-urlencoded` POST — Overpass's query API and Telegram's Bot API both
   * accept this for their respective single-field payloads.
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
      val req = request(url, timeoutSeconds)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(encoded))
        .build()
      check(url, sendRetrying(req, retries, backoffMs), 300)
    }

  /**
   * `application/json` POST with arbitrary extra headers (e.g. `Authorization: Bearer ...`) — used
   * by both `llm` clients.
   */
  def postJson(
      url: String,
      jsonBody: String,
      headers: Map[String, String] = Map.empty,
      timeoutSeconds: Long = 120
  ): String < Sync =
    Sync.defer {
      val req = request(url, timeoutSeconds, headers)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
      check(url, transport.get.send(req.build()), 500)
    }

  /** Raw-binary POST — Azure AI Vision takes the image bytes as the body, not JSON. */
  def postBytes(
      url: String,
      body: Array[Byte],
      contentType: String = "application/octet-stream",
      headers: Map[String, String] = Map.empty,
      timeoutSeconds: Long = 30
  ): String < Sync =
    Sync.defer {
      val req = request(url, timeoutSeconds, headers)
        .header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
      check(url, transport.get.send(req.build()), 500)
    }

  /**
   * Raw-binary PUT — MLflow's artifact-store proxy (`PUT .../mlflow-artifacts/artifacts/<path>`).
   */
  def putBytes(
      url: String,
      body: Array[Byte],
      contentType: String = "application/octet-stream",
      headers: Map[String, String] = Map.empty,
      timeoutSeconds: Long = 30
  ): String < Sync =
    Sync.defer {
      val req = request(url, timeoutSeconds, headers)
        .header("Content-Type", contentType)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
      check(url, transport.get.send(req.build()), 500)
    }

  /** What a binary response is — the raw bytes, never decoded as text. */
  final case class BytesResponse(status: Int, bytes: Array[Byte])

  final case class HttpBytesError(status: Int, url: String)
      extends Exception(s"HTTP $status for $url")

  /**
   * Separate from `Transport` on purpose: every text caller relies on `Response.body` being decoded
   * as UTF-8 by `HttpClient`; re-deriving it from bytes would risk corrupting accented responses.
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

  /** Binary GET (PDF bulletins, MIP-0031), never text-decoded. */
  def getBytes(url: String, timeoutSeconds: Long = 30): Array[Byte] < Sync =
    Sync.defer {
      val response = binaryTransport.get.send(request(url, timeoutSeconds).GET().build())
      if response.status / 100 == 2 then response.bytes
      else throw HttpBytesError(response.status, url)
    }
