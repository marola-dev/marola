package marola.log

import org.slf4j.LoggerFactory

/**
 * The logger marola uses. `System.err.println` was creeping in — unfilterable, unformatted, no
 * level, and invisible to any log collector — so this replaces it everywhere.
 *
 * A thin wrapper over SLF4J rather than scala-logging: logback is already a direct dependency, and
 * scala-logging's only Scala 3 release is 4.0.0-RC1. This repo already carries one pre-1.0
 * dependency (Kyo) and the cost of that is documented; a second one, for something this small, is
 * not worth it.
 *
 * `inline` plus the level guard is what scala-logging's macros buy: the interpolated string is
 * never built when the level is off.
 *
 * Deliberately not effectful. These are diagnostics at the edges of I/O that already happened, and
 * threading a `Log` effect through `Recommender`'s pipeline would change signatures across four
 * modules to describe something that cannot fail.
 */
final class Log(private val underlying: org.slf4j.Logger):
  inline def error(inline message: String): Unit =
    if underlying.isErrorEnabled then underlying.error(message)
  inline def warn(inline message: String): Unit =
    if underlying.isWarnEnabled then underlying.warn(message)
  inline def info(inline message: String): Unit =
    if underlying.isInfoEnabled then underlying.info(message)
  inline def debug(inline message: String): Unit =
    if underlying.isDebugEnabled then underlying.debug(message)

  def name: String = underlying.getName

object Log:
  def apply(owner: Class[?]): Log = new Log(LoggerFactory.getLogger(owner))

  /** Strips Scala's trailing `$` so an `object Foo`'s logger is named `pkg.Foo`, not `pkg.Foo$`. */
  def forName(name: String): Log = new Log(LoggerFactory.getLogger(name.stripSuffix("$")))
