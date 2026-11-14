package marola.tools.submodules

import marola.tools.shared.Cli
import marola.tools.shared.Proc

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
  def push(dir: String, proc: Proc, sleepSeconds: Int => Unit = s => Thread.sleep(s * 1000L)): Int =
    Proc.exitCode {
      val tries = Tries(proc.variable("SITE_DATA_PUSH_TRIES").getOrElse(Tries.Default))
      val git = Seq("git", "-C", dir)
      def attempt(i: Int): Int =
        if proc.status(os.pwd, git ++ Seq("push", "-q", "origin", "HEAD:site-data")*) == 0 then 0
        else if tries.exhausted(i) then rejected(tries)
        else
          Proc.err(
            s"site-data-push: rejected (attempt $i/${tries.raw}), replaying onto the new tip"
          )
          sleepSeconds(tries.backoffSeconds(i))
          proc.must(os.pwd, git ++ Seq("fetch", "-q", "--depth=1", "origin", "site-data")*)
          // A failed rebase leaves HEAD on the other writer's commit, and pushing that "succeeds"
          // while dropping ours, so it has to stop here.
          val hasParent =
            proc.quiet(os.pwd, git ++ Seq("rev-parse", "-q", "--verify", "HEAD~1")*) == 0
          val base = if hasParent then ReplayBase.Parent else ReplayBase.Root
          if proc.status(os.pwd, git ++ Seq("rebase", "-q", "--onto", "FETCH_HEAD", base.arg)*) != 0
          then
            proc.call(os.pwd, git :+ "rebase" :+ "--abort", stderr = os.Pipe)
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
    else push(checkout, Proc())

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "site-data-push", args)
