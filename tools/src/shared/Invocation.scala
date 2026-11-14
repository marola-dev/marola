package marola.tools.shared

/** What a command reads of the process that runs it, read once at its `@main` edge and passed in.
  */
final case class Invocation(env: Map[String, String], cwd: os.Path):
  /** The umbrella's checkout. `scripts/marola-tools` exports MAROLA_ROOT; otherwise the nearest
    * directory at or above `cwd` that holds `tools/project.scala`.
    */
  def repoRoot: os.Path =
    env.get(Invocation.RootEnvVar).filter(_.nonEmpty).map(os.Path(_, cwd)).getOrElse {
      Iterator
        .iterate(cwd)(_ / os.up)
        .take(cwd.segmentCount + 1)
        .find(dir => os.exists(dir / os.RelPath(Invocation.RootMarker)))
        .getOrElse(sys.error("marola-tools: run me inside the umbrella, or set MAROLA_ROOT"))
    }

  /** A path the command line gave, against the working directory. */
  def path(written: String): os.Path = os.Path(written, cwd)

object Invocation:
  val RootEnvVar = "MAROLA_ROOT"
  val RootMarker = "tools/project.scala"

  /** This process's environment and working directory. */
  def current(): Invocation = Invocation(sys.env, os.pwd)
