package marola.tools.submodules

import marola.tools.shared.Cli
import marola.tools.shared.Proc

import mainargs.{arg, main, Flag, ParserForMethods}

// Squash-merge pointer-sync.yml's rolling PR once it is green and in scope (#739).
//
//     marola-tools pointer-sync-merge --repo OWNER/NAME [--sha SHA] [--dry-run] [--plan FILE]
//         in a full clone of the default branch; --sha is the commit the triggering checks ran on;
//         --plan writes the merge or the comment to FILE for pointer-sync-merge.yml's keyed job
//
// Skips a PR labelled `hold`. Merges only when ci-ok (from GitHub Actions) and every other check run
// and status on the head finished success/skipped/neutral, the head is still --sha, and the diff is
// submodule gitlinks (each on its marola-dev repo's default branch) plus REPOS.md inside its wiring
// markers. Pending, or no ci-ok yet, exits quietly for a later trigger; any other refusal leaves the
// PR for a person with one comment.
object PointerSyncMerge:
  val Branch = "chore/pointer-sync"
  val ReposMd = WiringDoc.Path

  // ---- the diff --------------------------------------------------------------------------------

  /** Why the diff is more than gitlinks and REPOS.md's wiring table, or None. */
  def scopeProblem(base: Sha, head: Sha, cwd: os.Path, proc: Proc): Option[ScopeProblem] =
    val gitlinks = proc.submodulePaths(cwd, "--blob", s"$base:.gitmodules").map(_._2).toSet
    val diff = Seq("git", "diff", "-z", "--no-renames", "--name-only", base.value, head.value)
    val paths = proc.call(cwd, diff, os.Pipe).out.split('\u0000').filter(_.nonEmpty).toVector
    ScopeProblem.of(
      gitlinks,
      paths,
      path => TreeEntry.isPointer(treeEntries(head, path, cwd, proc)),
      wiringOnly(base, head, cwd, proc)
    )

  private def wiringOnly(base: Sha, head: Sha, cwd: os.Path, proc: Proc): Boolean =
    def exists(rev: Sha) = proc.quiet(cwd, "git", "cat-file", "-e", s"$rev:$ReposMd") == 0
    def outside(rev: Sha) = WiringDoc.outsideMarkers(
      proc.call(cwd, Seq("git", "show", s"$rev:$ReposMd"), os.Pipe).out
    )
    exists(base) && exists(head) && outside(base) == outside(head)

  private def treeEntries(head: Sha, path: String, cwd: os.Path, proc: Proc): Vector[TreeEntry] =
    Proc.lines(proc.capture(cwd, "git", "ls-tree", head.value, "--", path).out).map(TreeEntry.parse)

  /** Why a moved gitlink is not on its marola-dev repo's default branch, or None. */
  def pointerProblem(base: Sha, head: Sha, cwd: os.Path, proc: Proc): Option[PointerProblem] =
    val gitmodules = s"$base:.gitmodules"
    proc
      .submodulePaths(cwd, "--blob", gitmodules)
      .iterator
      .map(Submodule.fromConfig)
      .flatMap { case Submodule(name, path) =>
        val unchanged =
          proc.status(cwd, "git", "diff", "--quiet", base.value, head.value, "--", path) == 0
        if unchanged then None
        else
          val url =
            proc.capture(cwd, "git", "config", "--blob", gitmodules, s"submodule.$name.url").out
          RepoSlug.marolaDev(url) match
            case Right(repo) =>
              val sha = Sha(treeEntries(head, path, cwd, proc).map(_.sha.value).mkString("\n"))
              val status = compareStatus(repo, sha, proc)
              Option.when(!status.onDefaultBranch)(
                PointerProblem.NotOnDefaultBranch(path, sha, repo, status)
              )
            case Left(_) => Some(PointerProblem.NotMarolaDev(path, url))
      }
      .nextOption()

  // GitHub serves a fork's commits through its parent repo, so a SHA that resolves there can still
  // be anyone's: only an ancestor of the default branch is a real pointer move.
  private def compareStatus(repo: RepoSlug, sha: Sha, proc: Proc): CompareStatus =
    CompareStatus.parse(
      for
        branch <- readField(proc, s"repos/$repo", "default_branch")
        status <- readField(proc, s"repos/$repo/compare/$sha...$branch", "status")
      yield status
    )

  /** `read_gh api <path> | jq -er .<field>`. The App token is installation-scoped to marola, so the
    * submodule repos are read with GH_READ_TOKEN (github.token in CI); unset, gh uses its own
    * login.
    */
  private def readField(proc: Proc, apiPath: String, field: String): Option[String] =
    val readToken = Map("GH_TOKEN" -> proc.variable("GH_READ_TOKEN").getOrElse(""))
    val result = proc.call(os.pwd, Seq("gh", "api", apiPath), os.Pipe, extraEnv = readToken)
    GitHub.requiredField(result.exitCode, result.out, field)

  // ---- the run ---------------------------------------------------------------------------------

  /** One pass over the rolling PR, from the clone `cwd`. The exit code. */
  def runIn(
      repo: RepoSlug,
      want: Option[Sha],
      dryRun: Boolean,
      cwd: os.Path,
      proc: Proc,
      plan: Option[os.Path] = None
  ): Int =
    Proc.exitCode {
      val prList = proc
        .call(
          cwd,
          Seq(
            "gh",
            "pr",
            "list",
            "-R",
            repo.value,
            "--head",
            Branch,
            "--base",
            "main",
            "--state",
            "open",
            "--json",
            "number,headRefOid,isCrossRepository,labels"
          ),
          os.Pipe
        )
        .out
      GitHub.openPr(prList) match
        case None =>
          Proc.say(s"pointer-sync-merge: no open $Branch PR")
          0
        case Some(pr) if pr.held =>
          Proc.say(s"pointer-sync-merge: #${pr.number} carries hold; left for a person")
          0
        case Some(pr) => Merge(repo, pr, dryRun, cwd, proc, plan).checkAndMerge(want)
    }

  private final case class Merge(
      repo: RepoSlug,
      pr: Pr,
      dryRun: Boolean,
      cwd: os.Path,
      proc: Proc,
      plan: Option[os.Path]
  ):
    private val n = pr.number.value
    private val head = pr.head

    // --plan: the keyless job decides and the keyed job only acts, so no third-party action or
    // download ever runs beside MERGE_BOT_KEY (MIP-0086 §5.6, the devkit's merge-key-check).
    private def writePlan(action: Action): Unit =
      plan.foreach(os.write.append(_, Plan(action, pr.number, head).render))

    def refuse(refusal: Refusal): Int =
      val body = refusal.body(head)
      Proc.say(s"::warning::#$n $body")
      if alreadySaid(body) then Proc.say(s"pointer-sync-merge: #$n already says so")
      else
        writePlan(Action.Comment(body))
        if dryRun || plan.isDefined then Proc.say(s"+ gh pr comment $n -R $repo --body '$body'")
        else proc.must(cwd, "gh", "pr", "comment", n, "-R", repo.value, "--body", body)
      0

    private def alreadySaid(body: String): Boolean =
      val view = Seq("gh", "pr", "view", n, "-R", repo.value, "--json", "comments")
      GitHub.hasComment(proc.call(cwd, view, os.Pipe).out, body)

    def checkAndMerge(want: Option[Sha]): Int =
      Refusal.headMoved(want, head) match
        case Some(refusal) => refuse(refusal)
        case None          => checksPassed()

    // check-runs is filter=latest by default, so a re-run replaces its failed attempt. The combined
    // status's own .state reads "pending" when there are no statuses at all, so only .statuses counts.
    private def checksPassed(): Int =
      val runsText = proc.mustCapture(
        cwd,
        "gh",
        "api",
        "--paginate",
        s"repos/$repo/commits/$head/check-runs?per_page=100"
      )
      val runs = Jq.values(runsText)
      val Proc.Result(statusCode, statusText) =
        proc.call(
          cwd,
          Seq("gh", "api", "--paginate", s"repos/$repo/commits/$head/status?per_page=100"),
          os.Pipe
        )
      // `gh | jq` under pipefail: jq's failure is the rightmost, then gh's; either ends the run.
      val statusPages = Jq.values(statusText)
      if !statusPages.readable("statuses") then throw Proc.Abort(Jq.JqError)
      if statusCode != 0 then throw Proc.Abort(statusCode)
      val checks = GitHub.checkRuns(runs.documents) ++ GitHub.statuses(statusPages.documents)
      val failedChecks = Checks.failed(checks)
      if failedChecks.nonEmpty then refuse(Refusal.checksFailed(failedChecks))
      else
        if !runs.readable("check_runs") then throw Proc.Abort(Jq.JqError)
        Checks.waitingOn(checks, GitHub.ciOk(runs.documents)) match
          case Some(waiting) =>
            Proc.say(s"::notice::#$n waits for $waiting; a later run retries")
            0
          case None => inScope()

    private def inScope(): Int =
      proc.must(
        cwd,
        "git",
        "fetch",
        "-q",
        "--no-tags",
        "--no-recurse-submodules",
        "origin",
        "+refs/heads/main:refs/remotes/origin/main",
        s"+refs/pull/$n/head:refs/remotes/origin/pull/$n"
      )
      if proc.capture(cwd, "git", "rev-parse", s"refs/remotes/origin/pull/$n").out != head.value
      then refuse(Refusal.MovedWhileRead)
      else
        val base =
          Sha(proc.mustCapture(cwd, "git", "merge-base", "refs/remotes/origin/main", head.value))
        val problem = scopeProblem(base, head, cwd, proc)
          .map(_.message)
          .orElse(pointerProblem(base, head, cwd, proc).map(_.message))
        problem match
          case Some(why) => refuse(Refusal(why))
          case None      => merge()

    // --admin only skips gh's client-side refusal of a BLOCKED PR; GitHub still decides, and lets this
    // App through as main-rule's pull-request-only bypass actor. --match-head-commit aborts the merge
    // if anything was pushed since the checks ran.
    private def merge(): Int =
      val command = Seq("gh", "pr", "merge", n, "--squash", "--admin") ++
        Seq("--match-head-commit", head.value, "-R", repo.value)
      writePlan(Action.Merge)
      if dryRun || plan.isDefined then Proc.say(s"+ ${command.mkString(" ")}")
      else
        proc.must(cwd, command*)
        Proc.say(s"pointer-sync-merge: merged #$n at ${head.short}")
      0

  @main(doc = "Squash-merge pointer-sync.yml's rolling PR once it is green and in scope (#739)")
  def run(
      @arg(doc = "OWNER/NAME") repo: Option[String] = None,
      @arg(doc = "the commit the triggering checks ran on") sha: Option[String] = None,
      @arg(doc = "print the comment and merge commands instead of running them") dryRun: Flag,
      @arg(doc = "append action, pr, head and body to this file ($GITHUB_OUTPUT) instead of acting")
      plan: Option[String] = None
  ): Int =
    repo.filter(_.nonEmpty) match
      case None =>
        Proc.err(
          "usage: marola-tools pointer-sync-merge --repo OWNER/NAME [--sha SHA] [--dry-run] [--plan FILE]"
        )
        2
      case Some(name) =>
        val out = plan.filter(_.nonEmpty).map(os.Path(_, os.pwd))
        val want = sha.filter(_.nonEmpty).map(Sha(_))
        runIn(RepoSlug(name), want, dryRun.value, os.pwd, Proc(), out)

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "pointer-sync-merge", args)
