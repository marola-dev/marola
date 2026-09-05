package marola.http

import kyo.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

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

  private val client: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  // TODO: point this at the real repo URL once marola is pushed to its own GitHub repo.
  private def userAgent = "marola-poc/0.1"

  def getString(url: String): String < Sync =
    Sync.defer {
      val request = HttpRequest
        .newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("User-Agent", userAgent)
        .GET()
        .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode() / 100 == 2 then response.body()
      else throw HttpError(response.statusCode(), url, response.body().take(300))
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
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode() / 100 == 2 then response.body()
      else throw HttpError(response.statusCode(), url, response.body().take(300))
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
      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      if response.statusCode() / 100 == 2 then response.body()
      else throw HttpError(response.statusCode(), url, response.body().take(500))
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
      val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      if response.statusCode() / 100 == 2 then response.body()
      else throw HttpError(response.statusCode(), url, response.body().take(500))
    }
