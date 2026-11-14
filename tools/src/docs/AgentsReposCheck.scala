package marola.tools.docs

import marola.tools.shared.{Cli, Invocation, Util}

import mainargs.{arg, main, Leftover, ParserForMethods}

// AGENTS.md links the routing table instead of restating it (MIP-0074 §7):
// docs/2-Building-marola/REPOS.md is its only copy.
//
//     marola-tools agents-repos-check [AGENTS.md]   # default: the repo root's
object AgentsReposCheck:
  /** Each finding about `text`, shown under the name `file`. */
  def findings(file: String, text: String): Seq[String] =
    AgentsFinding.of(text).map(_.render(file))

  /** 0 clean, 1 with findings (printed on stdout), 2 when the file is missing; `file` is how the
    * command line named `path`.
    */
  def check(path: os.Path, file: String): Int =
    if !os.isFile(path) then
      System.err.println(s"agents_repos_check: $file not found")
      2
    else
      val found = findings(file, Util.readLossy(path))
      found.foreach(println)
      if found.isEmpty then 0 else 1

  @main(doc = "AGENTS.md links the routing table instead of restating it (MIP-0074 §7)")
  def run(
      @arg(doc = "The file to check (default: the repo root's AGENTS.md)") file: Leftover[String]
  ): Int =
    val invocation = Invocation.current()
    file.value.headOption match
      case Some(flag) if flag.startsWith("-") =>
        System.err.println(s"agents_repos_check: unknown argument '$flag' (try --help)")
        2
      case Some(path) => check(invocation.path(path), path)
      case None       =>
        val path = invocation.repoRoot / AgentsFinding.File
        check(path, path.toString)

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "agents-repos-check", args)
