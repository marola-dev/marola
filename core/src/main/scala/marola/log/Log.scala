package marola.log

import org.slf4j.LoggerFactory

/**
 * Thin SLF4J wrapper, not scala-logging (whose only Scala 3 release is 4.0.0-RC1). `inline` plus
 * the level guard skips building the message when the level is off. Deliberately not effectful:
 * these are diagnostics after I/O that already happened.
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

object Log:
  /** Strips Scala's trailing `$` so an `object Foo`'s logger is named `pkg.Foo`, not `pkg.Foo$`. */
  def forName(name: String): Log = new Log(LoggerFactory.getLogger(name.stripSuffix("$")))
