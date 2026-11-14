package marola.tools.submodules

import marola.tools.shared.Cli
import marola.tools.shared.Proc

import mainargs.{arg, main, ParserForMethods}

// Move every submodule's pointer to its default branch's tip and commit the bump with REPOS.md's
// wiring block regenerated, for pointer-sync.yml's one rolling PR (MIP-0070 §5.6).
//
//     marola-tools pointer-sync --out DIR   # DIR/moved.tsv (empty when nothing moved); DIR/pr-body.md and one commit when it did
object PointerSync:
  val ReposMd = WiringDoc.Path

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
      for case Submodule(name, path) <- proc
          .submodulePaths(top, "-f", ".gitmodules")
          .map(Submodule.fromConfig)
      do
        val url =
          proc.mustCapture(top, "git", "config", "-f", ".gitmodules", s"submodule.$name.url")
        val old = Sha(proc.mustCapture(top, "git", "rev-parse", s"HEAD:$path"))
        // No submodule.<name>.branch anywhere, so --remote follows each remote's HEAD: its default branch.
        proc.must(top, "git", "submodule", "update", "--quiet", "--init", "--remote", "--", path)
        val now = Sha(proc.mustCapture(top, "git", "-C", path, "rev-parse", "HEAD"))
        if old != now then
          proc.must(top, "git", "add", "--", path)
          val m = Moved(name, old, now, url)
          os.write.append(movedTsv, m.tsvRow)
          moved += m
      commit(top, out, Bump(moved.result()), proc)
  }

  private def commit(top: os.Path, out: os.Path, bump: Bump, proc: Proc): Int =
    if bump.isEmpty then
      Proc.say("pointer-sync: every submodule is already at its default branch's tip")
      0
    else
      // The block is read from the submodules' trees, so it is regenerated only now (MIP-0076 §5.4).
      proc.must(top, "wiring", ReposMd)
      proc.must(top, "git", "add", "--", ReposMd)
      os.write.over(out / "commit-msg.txt", bump.commitMessage)
      os.write.over(out / "pr-body.md", bump.prBody)
      proc.must(top, "git", "commit", "--quiet", "-F", (out / "commit-msg.txt").toString)
      Proc.say(s"pointer-sync: moved ${bump.names}")
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
