package marola.observability

import kyo.*

/**
 * `Tracing.Noop` is the zero-dependency default (`MAROLA_TRACES=off`, or unset with nothing
 * configured) — both methods must just run the wrapped effect, unchanged, no span.
 */
class TracingSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  test("Noop.withSpan runs the wrapped effect and returns its result unchanged") {
    val effect: Int < Sync = Sync.defer(41 + 1)
    assertEquals(Sync.Unsafe.evalOrThrow(Tracing.Noop.withSpan("span-name")(effect)), 42)
  }

  test("Noop.llmSpan runs the wrapped effect and returns its result unchanged") {
    val effect: String < Sync = Sync.defer("summary")
    assertEquals(
      Sync.Unsafe.evalOrThrow(
        Tracing.Noop.llmSpan("some-model", Map.empty)(effect)(_ => Map.empty)
      ),
      "summary"
    )
  }

  test("Noop does not evaluate the effect more than once") {
    var calls = 0
    val effect: Unit < Sync = Sync.defer(calls += 1)
    Sync.Unsafe.evalOrThrow(Tracing.Noop.withSpan("span-name")(effect))
    assertEquals(calls, 1)
  }
