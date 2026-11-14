package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Util

import mainargs.{main, ParserForMethods}

// CLAUDE_PROJECT.config.md has its sections and routines, and leaks no id.
//
//     marola-tools claude-project-check
object ClaudeProject:
  def check(text: String): Vector[String] = ProjectConfig.check(text).map(_.message)

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
