package marola.tools.submodules

import marola.tools.shared.{Cli, GitNames, Invocation, Proc}

import mainargs.{arg, main, ParserForMethods}

// Push the one commit a workflow just made on its checkout of marola-site's `site-data` branch,
// retrying when another workflow pushed first.
//
//     marola-tools site-data-push <checkout-dir>   # SITE_DATA_PUSH_TRIES attempts (default 5)
//
// Each writer owns its own directory, so a rejected push never conflicts in content, only in ref.
// The checkouts are --depth=1, which is why this replays only HEAD (`--onto FETCH_HEAD HEAD~1`):
// a plain rebase would also try to replay the shallow root, i.e. the whole old tree.
object SiteDataPush:
  def push(
      run: PushRun,
      proc: Proc,
      sleepSeconds: Int => Unit = s => Thread.sleep(s * 1000L)
  ): Int =
    Proc.exitCode {
      val PushRun(dir, tries, cwd) = run
      def git(args: String*): Seq[String] = Seq("git", "-C", dir) ++ args
      def attempt(i: Int): Int =
        if proc.status(cwd, git("push", "-q", GitNames.Origin, s"HEAD:${SiteData.Branch}")*) == 0
        then 0
        else if tries.exhausted(i) then rejected(tries)
        else
          Proc.err(
            s"site-data-push: rejected (attempt $i/${tries.raw}), replaying onto the new tip"
          )
          sleepSeconds(tries.backoffSeconds(i))
          proc.must(cwd, git("fetch", "-q", "--depth=1", GitNames.Origin, SiteData.Branch)*)
          // A failed rebase leaves HEAD on the other writer's commit, and pushing that "succeeds"
          // while dropping ours, so it has to stop here.
          val hasParent =
            proc.quiet(cwd, git("rev-parse", "-q", "--verify", ReplayBase.Parent.arg)*) == 0
          val base = if hasParent then ReplayBase.Parent else ReplayBase.Root
          if proc.status(cwd, git("rebase", "-q", "--onto", GitNames.FetchHead, base.arg)*) != 0
          then
            proc.call(cwd, git("rebase", "--abort"), stderr = os.Pipe)
            Proc.err("site-data-push: could not replay onto the new tip")
            1
          else attempt(i + 1)
      if tries.limit < 1 then rejected(tries) else attempt(1)
    }

  private def rejected(tries: Tries): Int =
    Proc.err(s"site-data-push: still rejected after ${tries.raw} attempts")
    1

  @main(doc =
    "Push the commit a workflow made on marola-site's site-data branch, retrying on a race"
  )
  def run(@arg(positional = true, doc = "the site-data checkout") checkout: String = ""): Int =
    if checkout.isEmpty || checkout.startsWith("-") then
      Proc.err("usage: marola-tools site-data-push <site-data-worktree>")
      2
    else
      val invocation = Invocation.current()
      val tries = Tries.from(invocation.env.get(Tries.EnvVar))
      push(PushRun(checkout, tries, invocation.cwd), Proc())

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "site-data-push", args)
