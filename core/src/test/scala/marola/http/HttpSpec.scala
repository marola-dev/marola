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

  /**
   * Throws `failure` for the first `failures` sends (the transport never produced a response — a
   * connect timeout, a refused connection), then answers 200; counts the sends.
   */
  final class Flaky(failures: Int, failure: Throwable) extends Http.Transport:
    var sent: Int = 0
    def send(request: HttpRequest): Http.Response =
      sent += 1
      if sent <= failures then throw failure
      Http.Response(200, s"body-200-$sent")

  private def postFlaky(t: Flaky, retries: Int): Either[Throwable, String] =
    Http.withTransport(t) {
      try
        Right(
          Sync.Unsafe.evalOrThrow(
            Http.postForm("https://example.test/q", Map("data" -> "q"), 5, retries, backoffMs = 1)
          )
        )
      catch case e: Throwable => Left(e)
    }

  test("a connect timeout is retried like a 504 — the 2026-09-06 site-build failure") {
    val t = Flaky(1, new java.net.http.HttpConnectTimeoutException("HTTP connect timed out"))
    assertEquals(postFlaky(t, retries = 2), Right("body-200-2"))
    assertEquals(t.sent, 2)
  }

  test("a read timeout and a refused connection are retried too") {
    val timeout = Flaky(2, new java.net.http.HttpTimeoutException("request timed out"))
    assertEquals(postFlaky(timeout, retries = 2), Right("body-200-3"))
    val refused = Flaky(1, new java.net.ConnectException("Connection refused"))
    assertEquals(postFlaky(refused, retries = 1), Right("body-200-2"))
  }

  test("after `retries` extra attempts the last failure is rethrown as is") {
    val t = Flaky(3, new java.net.http.HttpConnectTimeoutException("HTTP connect timed out"))
    val result = postFlaky(t, retries = 2)
    assert(
      result.left.exists {
        case _: java.net.http.HttpConnectTimeoutException => true; case _ => false
      },
      result
    )
    assertEquals(t.sent, 3)
  }

  test("a failure that would repeat identically is never retried") {
    val t = Flaky(1, new IllegalArgumentException("bad URL"))
    val result = postFlaky(t, retries = 2)
    assert(result.left.exists { case _: IllegalArgumentException => true; case _ => false }, result)
    assertEquals(t.sent, 1)
  }

  test("getString retries a connect timeout when asked, and not by default") {
    val asked = Flaky(1, new java.net.http.HttpConnectTimeoutException("HTTP connect timed out"))
    val recovered = Http.withTransport(asked) {
      Sync.Unsafe.evalOrThrow(Http.getString("https://example.test/f", retries = 2, backoffMs = 1))
    }
    assertEquals(recovered, "body-200-2")
    val bare = Flaky(1, new java.net.http.HttpConnectTimeoutException("HTTP connect timed out"))
    val surfaced = Http.withTransport(bare) {
      try Right(Sync.Unsafe.evalOrThrow(Http.getString("https://example.test/f")))
      catch case e: Throwable => Left(e)
    }
    assert(
      surfaced.left.exists {
        case _: java.net.http.HttpConnectTimeoutException => true; case _ => false
      },
      surfaced
    )
    assertEquals(bare.sent, 1)
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
