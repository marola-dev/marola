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

  private def userAgent = "marola/0.1 (+https://github.com/h0ffmann/marola)"

  /** `headers`: e.g. an API key that must not go in the URL (`RouteFinder`). */
  def getString(url: String, headers: Map[String, String] = Map.empty): String < Sync =
    Sync.defer {
      val builder = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("User-Agent", userAgent)
        .GET()
      headers.foreach { case (k, v) => builder.header(k, v) }
      check(url, transport.get.send(builder.build()), 300)
    }

  /**
   * `application/x-www-form-urlencoded` POST — Overpass's query API and Telegram's Bot API both
   * accept this for their respective single-field payloads. `timeoutSeconds` exists because
   * Overpass genuinely takes tens of seconds for relation-aware area queries under load (confirmed:
   * ~29s for a 15km beach query around Florianópolis), which the old fixed 15s cut off with
   * `HttpTimeoutException`; `BeachFinder` passes a value above Overpass's own server-side
   * `[timeout:...]` so the server's error, not a client abort, is what surfaces.
   */
  def postForm(
      url: String,
      form: Map[String, String],
      timeoutSeconds: Long = 15
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
      check(url, transport.get.send(request), 300)
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
