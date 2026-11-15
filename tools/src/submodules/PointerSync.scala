package marola.tools.submodules

import marola.tools.shared.{Cli, Invocation, Proc, Sha, Submodule}

import mainargs.{arg, main, ParserForMethods}

// Move every submodule's pointer to its default branch's tip and commit the bump with REPOS.md's
// wiring block regenerated, for pointer-sync.yml's one rolling PR (MIP-0070 §5.6).
//
//     marola-tools pointer-sync --out DIR   # DIR/moved.tsv (empty when nothing moved); DIR/pr-body.md and one commit when it did
object PointerSync:
  val ReposMd = WiringDoc.Path

  /** The bump, in the umbrella checkout `top`. The exit code. */
  def bump(top: os.Path, out: os.Path, proc: Proc): Int = Proc.exitCode {
    if proc.which(WiringDoc.Tool, top).isEmpty then
      Proc.err("pointer-sync: wiring (marola-devkit) is not on PATH")
      1
    else
      os.makeDir.all(out)
      val movedTsv = out / BumpFile.Moved
      os.write.over(movedTsv, "")
      // Each row is appended as it moves, so a step that fails later leaves the rows so far.
      val moved = proc.submodules(top, "-f", Submodule.Gitmodules).flatMap { sub =>
        val moved = move(top, sub, proc)
        moved.foreach(m => os.write.append(movedTsv, m.tsvRow))
        moved
      }
      commit(top, out, Bump(moved), proc)
  }

  private def move(top: os.Path, sub: Submodule, proc: Proc): Option[Moved] =
    val url = proc.mustCapture(top, "git", "config", "-f", Submodule.Gitmodules, sub.urlKey)
    val old = Sha(proc.mustCapture(top, "git", "rev-parse", s"HEAD:${sub.path}"))
    // No submodule.<name>.branch anywhere, so --remote follows each remote's HEAD: its default branch.
    proc.must(top, "git", "submodule", "update", "--quiet", "--init", "--remote", "--", sub.path)
    val now = Sha(proc.mustCapture(top, "git", "-C", sub.path, "rev-parse", "HEAD"))
    Option.when(old != now) {
      proc.must(top, "git", "add", "--", sub.path)
      Moved(sub.name, old, now, url)
    }

  private def commit(top: os.Path, out: os.Path, bump: Bump, proc: Proc): Int =
    if bump.isEmpty then
      Proc.say("pointer-sync: every submodule is already at its default branch's tip")
      0
    else
      // The block is read from the submodules' trees, so it is regenerated only now (MIP-0076 §5.4).
      proc.must(top, WiringDoc.Tool, ReposMd)
      proc.must(top, "git", "add", "--", ReposMd)
      os.write.over(out / BumpFile.CommitMessage, bump.commitMessage)
      os.write.over(out / BumpFile.PrBody, bump.prBody)
      proc.must(top, "git", "commit", "--quiet", "-F", (out / BumpFile.CommitMessage).toString)
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
    runIn(Invocation.current().cwd, out.getOrElse(""), Proc())

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "pointer-sync", args)
