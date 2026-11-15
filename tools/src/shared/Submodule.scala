package marola.tools.shared

/** One `submodule.<name>.path` entry of a `.gitmodules`. */
final case class Submodule(name: String, path: String):
  /** The config key of its remote. */
  def urlKey: String = s"${Submodule.KeyPrefix}$name${Submodule.UrlSuffix}"

object Submodule:
  val Gitmodules = ".gitmodules"
  private val KeyPrefix = "submodule."
  private val PathSuffix = ".path"
  private val UrlSuffix = ".url"

  /** What `git config --get-regexp` is asked for to list them. */
  val PathKeyRegex: String = raw"^submodule\..*\.path$$"

  /** From `git config --get-regexp '^submodule\..*\.path$'`'s key and value. */
  def fromConfig(key: String, path: String): Submodule =
    Submodule(key.stripPrefix(KeyPrefix).stripSuffix(PathSuffix), path)
