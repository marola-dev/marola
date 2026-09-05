package marola.http

import java.net.http.HttpRequest

import kyo.*

/**
 * The retry at the transport seam (`Http.postForm(..., retries)`): Overpass's public instance
 * answers 504 under load — observed live on 5 Sep 2026, and the reason a scheduled site build
 * stopped after its first area (see `site.yml`). Deterministic: a scripted transport, no network,
 * millisecond backoff.
 */
class HttpSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  /** Answers the scripted statuses in order and repeats the last one; counts the sends. */
  final class Scripted(statuses: Int*) extends Http.Transport:
    var sent: Int = 0
    def send(request: HttpRequest): Http.Response =
      val status = statuses(math.min(sent, statuses.size - 1))
      sent += 1
      Http.Response(status, s"body-$status-$sent")

  private def post(t: Scripted, retries: Int): Either[Http.HttpError, String] =
    Http.withTransport(t) {
      try
        Right(
          Sync.Unsafe.evalOrThrow(
            Http.postForm("https://example.test/q", Map("data" -> "q"), 5, retries, backoffMs = 1)
          )
        )
      catch case e: Http.HttpError => Left(e)
    }

  test("a retryable status is retried and the first 2xx body is returned") {
    val t = Scripted(504, 200)
    assertEquals(post(t, retries = 2), Right("body-200-2"))
    assertEquals(t.sent, 2)
  }

  test(
    "429/502/503/504 are all retryable; after `retries` extra attempts the last status surfaces"
  ) {
    val t = Scripted(429, 502, 503, 504)
    val result = post(t, retries = 3)
    assertEquals(result.left.map(_.status), Left(504))
    assertEquals(t.sent, 4)
  }

  test("a client error is never retried") {
    val t = Scripted(404, 200)
    assertEquals(post(t, retries = 2).left.map(_.status), Left(404))
    assertEquals(t.sent, 1)
  }

  test("no retries by default: the first 504 surfaces at once") {
    val t = Scripted(504, 200)
    val result = Http.withTransport(t) {
      try
        Right(Sync.Unsafe.evalOrThrow(Http.postForm("https://example.test/q", Map("data" -> "q"))))
      catch case e: Http.HttpError => Left(e.status)
    }
    assertEquals(result, Left(504))
    assertEquals(t.sent, 1)
  }

  test("the backoff doubles per attempt from the base") {
    assertEquals(Http.backoffFor(baseMs = 1000, attempt = 0), 1000L)
    assertEquals(Http.backoffFor(baseMs = 1000, attempt = 1), 2000L)
    assertEquals(Http.backoffFor(baseMs = 1000, attempt = 2), 4000L)
  }
