package marola.tools.submodules

import marola.tools.shared.Cli
import marola.tools.shared.Proc

import mainargs.{arg, main, ParserForMethods}

// Move every submodule's pointer to its default branch's tip and commit the bump with REPOS.md's
// wiring block regenerated, for pointer-sync.yml's one rolling PR (MIP-0070 §5.6).
//
//     marola-tools pointer-sync --out DIR   # DIR/moved.tsv (empty when nothing moved); DIR/pr-body.md and one commit when it did
object PointerSync:
  val ReposMd = "docs/2-Building-marola/REPOS.md"

  val Trailers =
    "Tested: pointer bump only; each submodule's own CI gates its main\n" +
      "Co-Authored-By: Claude <noreply@anthropic.com>\n"

  final case class Moved(name: String, old: String, now: String, url: String)

  private val GitHubRepo = raw"https://github\.com/([^/]+/[^/]+)".r

  /** A GitHub compare link, or None for a non-GitHub remote. */
  def compareUrl(url: String, old: String, now: String): Option[String] =
    url.stripSuffix(".git") match
      case GitHubRepo(repo) => Some(s"https://github.com/$repo/compare/$old...$now")
      case _                => None

  def names(moved: Seq[Moved]): String = moved.map(_.name).mkString(",").replace(",", ", ")

  def commitMessage(moved: Seq[Moved]): String =
    val rows = moved.map(m => s"- ${m.name}: ${m.old.take(7)} -> ${m.now.take(7)}\n").mkString
    s"chore: move submodule pointers (${names(moved)})\n\n" +
      "Each submodule's default-branch tip, as git submodule update --remote found it:\n\n" +
      rows + "\n" + Trailers

  def prBody(moved: Seq[Moved]): String =
    val rows = moved.map { m =>
      val link = compareUrl(m.url, m.old, m.now).map(url => s"[compare]($url)").getOrElse("")
      s"| `${m.name}` | `${m.old.take(7)}` | `${m.now.take(7)}` | $link |\n"
    }
    "Moves each submodule to its default branch's tip (MIP-0070 §5.6). `pointer-sync.yml` opens this PR " +
      "and force-updates it on every run; merge it once CI is green.\n\n" +
      "| Submodule | From | To | Changes |\n|---|---|---|---|\n" + rows.mkString

  /** The bump, in the umbrella checkout `top`. The exit code. */
  def bump(top: os.Path, out: os.Path, proc: Proc): Int = Proc.exitCode {
    if proc.which("wiring", top).isEmpty then
      Proc.err("pointer-sync: wiring (marola-devkit) is not on PATH")
      1
    else
      os.makeDir.all(out)
      val movedTsv = out / "moved.tsv"
      os.write.over(movedTsv, "")
      val moved = Vector.newBuilder[Moved]
      for (key, path) <- proc.submodulePaths(top, "-f", ".gitmodules") do
        val name = key.stripPrefix("submodule.").stripSuffix(".path")
        val url =
          proc.mustCapture(top, "git", "config", "-f", ".gitmodules", s"submodule.$name.url")
        val old = proc.mustCapture(top, "git", "rev-parse", s"HEAD:$path")
        // No submodule.<name>.branch anywhere, so --remote follows each remote's HEAD: its default branch.
        proc.must(top, "git", "submodule", "update", "--quiet", "--init", "--remote", "--", path)
        val now = proc.mustCapture(top, "git", "-C", path, "rev-parse", "HEAD")
        if old != now then
          proc.must(top, "git", "add", "--", path)
          os.write.append(movedTsv, s"$name\t$old\t$now\t$url\n")
          moved += Moved(name, old, now, url)
      commit(top, out, moved.result(), proc)
  }

  private def commit(top: os.Path, out: os.Path, moved: Vector[Moved], proc: Proc): Int =
    if moved.isEmpty then
      Proc.say("pointer-sync: every submodule is already at its default branch's tip")
      0
    else
      // The block is read from the submodules' trees, so it is regenerated only now (MIP-0076 §5.4).
      proc.must(top, "wiring", ReposMd)
      proc.must(top, "git", "add", "--", ReposMd)
      os.write.over(out / "commit-msg.txt", commitMessage(moved))
      os.write.over(out / "pr-body.md", prBody(moved))
      proc.must(top, "git", "commit", "--quiet", "-F", (out / "commit-msg.txt").toString)
      Proc.say(s"pointer-sync: moved ${names(moved)}")
      0

  /** The command, run from `cwd`. */
  def runIn(cwd: os.Path, out: String, proc: Proc): Int =
    if out.isEmpty then
      Proc.err("usage: marola-tools pointer-sync --out DIR")
      2
    else
      val outDir = os.Path(out, cwd)
      os.makeDir.all(outDir)
      bump(proc.toplevel(cwd), outDir, proc)

  @main(doc =
    "Move every submodule pointer to its default branch's tip, for pointer-sync.yml (MIP-0070 §5.6)"
  )
  def run(
      @arg(doc = "where moved.tsv, commit-msg.txt and pr-body.md go") out: Option[String] = None
  ): Int =
    runIn(os.pwd, out.getOrElse(""), Proc())

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "pointer-sync", args)
