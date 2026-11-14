import mainargs.{main, ParserForMethods}

// CLAUDE_PROJECT.config.md has its sections and routines, and leaks no id.
//
//     marola-tools claude-project-check
object ClaudeProject:
  val Sections = Seq(
    "1. Project",
    "2. Cloud environment",
    "3. Plugins, skills and connectors",
    "4. Routines",
    "5. Joining as a new member",
    "6. Restoring the project",
    "7. Not in this file",
    "8. Keeping it current"
  )
  // AGENTS.md points CLI agents away from this file; the banner says the same to whoever opens it.
  val Banner = "must not read it"

  // The service returns the owner's e-mail, account UUIDs and session ids; none is committed.
  private val Leaks = Seq(
    raw"[\w.+-]+@[\w-]+\.[\w.]+",
    raw"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    raw"\b(?:session|cse|cmsg|env|chan|user)_0[0-9A-Za-z]{20,}"
  ).mkString("|").r

  def check(text: String): Vector[String] =
    val banner = Option.when(!text.contains(Banner))(s"no banner saying a CLI agent $Banner")
    val sections =
      Sections.filterNot(s => text.contains(s"\n## $s\n")).map(s => s"no section '## $s'")
    val routines = text.split("\n## 4. Routines\n", 2).last.split("\n## 5.", 2).head
    val routineErrors = routines.split("\n### ").toVector.drop(1).flatMap { block =>
      val name = block.linesIterator.nextOption().getOrElse("")
      Option.when(!block.contains("**Schedule (cron)**"))(s"routine '$name': no cron") ++
        Option.when(!block.contains("```text\n"))(s"routine '$name': no prompt block")
    }
    val leak =
      Leaks.findFirstIn(text).map(m => s"${Util.pyreprStr(m)} looks like an e-mail or an id")
    (banner ++ sections ++ routineErrors ++ leak).toVector

  @main(doc = "CLAUDE_PROJECT.config.md has its sections and routines, and leaks no id")
  def run(): Int =
    val path = Util.repoRoot / "CLAUDE_PROJECT.config.md"
    if !os.exists(path) then
      System.err.println(s"claude_project_check: $path: No such file or directory")
      1
    else
      val errors = check(os.read(path))
      errors.foreach(e => System.err.println(s"claude_project_check: $e"))
      if errors.isEmpty then 0 else 1

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "claude-project-check", args)
