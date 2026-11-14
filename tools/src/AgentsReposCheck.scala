import mainargs.{arg, main, Leftover, ParserForMethods}

// AGENTS.md links the routing table instead of restating it (MIP-0074 §7):
// docs/2-Building-marola/REPOS.md is its only copy.
//
//     marola-tools agents-repos-check [AGENTS.md]   # default: the repo root's
object AgentsReposCheck:
  val ReposPage = "docs/2-Building-marola/REPOS.md"

  private val TableRow = """^\| \[?marola-(app|site|corpus|ml|oods|devkit)""".r
  private val PinFile = """marola-image|corpus\.version|resources\.version""".r

  /** Each finding about `text`, shown under the name `file`. */
  def findings(file: String, text: String): Seq[String] =
    val lines = text.split("\n", -1).toSeq.zipWithIndex.map((line, i) => (i + 1, line))
    val rows = lines.collect { case (n, line) if TableRow.findFirstIn(line).isDefined => n }
    val pins = lines.flatMap((n, line) => PinFile.findAllIn(line).map(m => s"$n:$m"))
    Seq(
      Option.when(rows.nonEmpty)(
        s"agents_repos_check: $file:${rows.mkString(",")}: a repo table row; it belongs in $ReposPage"
      ),
      Option.when(pins.nonEmpty)(
        s"agents_repos_check: $file: names a pin file (${pins.mkString(" ")}); it belongs in $ReposPage"
      ),
      Option.when(!text.contains(s"]($ReposPage"))(
        s"agents_repos_check: $file does not link $ReposPage"
      )
    ).flatten

  /** 0 clean, 1 with findings (printed on stdout), 2 when the file is missing. */
  def check(file: String): Int =
    val path = os.Path(file, os.pwd)
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
    file.value.headOption match
      case Some(flag) if flag.startsWith("-") =>
        System.err.println(s"agents_repos_check: unknown argument '$flag' (try --help)")
        2
      case Some(path) => check(path)
      case None       => check((Util.repoRoot / "AGENTS.md").toString)

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "agents-repos-check", args)
