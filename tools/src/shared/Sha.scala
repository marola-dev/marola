package marola.tools.shared

/** A git commit id. */
opaque type Sha = String

object Sha:
  private val FullHex = "[0-9a-f]{40}|[0-9a-f]{64}".r

  /** As git or gh printed it, unchecked: a malformed one is refused downstream as a moved head or
    * an unknown commit, which is the message a person needs.
    */
  def apply(value: String): Sha = value

  /** A full object name, as `git rev-parse HEAD` prints it (SHA-1, or SHA-256). */
  def parse(written: String): Either[String, Sha] =
    Either.cond(FullHex.matches(written), written, s"not a full commit sha: $written")

  extension (sha: Sha)
    def value: String = sha
    def short: String = sha.take(7)
