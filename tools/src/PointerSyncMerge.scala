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
  val ReposMd = PointerSync.ReposMd
  private val GitHubActions = 15368.0
  private val Passed = Set("success", "skipped", "neutral")

  final case class Pr(number: String, head: String, held: Boolean)
  final case class Check(name: String, status: String, conclusion: String)

  // ---- what gh returns, read as jq reads it (Json, below) ------------------------------------

  /** The first open PR of `gh pr list --json number,headRefOid,isCrossRepository,labels` that is
    * this repo's own branch (a fork's PR can carry the same branch name).
    */
  def openPr(prList: String): Option[Pr] =
    val own = Json.values(prList).documents.flatMap { list =>
      Json.items(list).find(pr => !Json.truthy(Json.field(pr, "isCrossRepository")))
    }
    own.collectFirst { case pr: ujson.Obj =>
      val labels = Json.items(Json.field(pr, "labels"))
      val held = labels.exists(label => Json.field(label, "name") == ujson.Str("hold"))
      Pr(Json.text(Json.field(pr, "number")), Json.text(Json.field(pr, "headRefOid")), held)
    }

  /** The rows of the check-runs pages: name, status, conclusion. */
  def checkRuns(pages: Seq[ujson.Value]): Vector[Check] =
    for
      page <- pages.toVector
      run <- Json.items(Json.field(page, "check_runs"))
    yield Check(
      Json.tsv(Json.field(run, "name")),
      Json.tsv(Json.field(run, "status")),
      Json.tsv(Json.orElse(Json.field(run, "conclusion"), ujson.Str("")))
    )

  /** The commit statuses as check rows. */
  def statuses(pages: Seq[ujson.Value]): Vector[Check] =
    for
      page <- pages.toVector
      status <- Json.items(Json.field(page, "statuses"))
    yield
      val state = Json.field(status, "state")
      val progress = if state == ujson.Str("pending") then "pending" else "completed"
      Check(Json.tsv(Json.field(status, "context")), progress, Json.tsv(state))

  /** ci.yml's aggregate job, from GitHub Actions only: its conclusion, one line per page that has
    * it.
    */
  def ciOk(pages: Seq[ujson.Value]): String =
    pages
      .flatMap { page =>
        Json.items(Json.field(page, "check_runs")).find { run =>
          Json.field(run, "name") == ujson.Str("ci-ok") &&
          Json.field(Json.field(run, "app"), "id") == ujson.Num(GitHubActions) &&
          Json.field(run, "status") == ujson.Str("completed")
        }
      }
      .map(run => Json.field(run, "conclusion"))
      .filter(Json.truthy)
      .map(Json.text)
      .mkString("\n")

  def failed(checks: Seq[Check]): String =
    checks
      .filter(c => c.status == "completed" && !Passed.contains(c.conclusion))
      .map(c => s"`${c.name}` (${c.conclusion})")
      .mkString(", ")

  def pending(checks: Seq[Check]): String =
    checks.filter(_.status != "completed").map(_.name).mkString(", ")

  def refusal(head: String, why: String, next: String = "Left for a person"): String =
    s"Not auto-merged at `${head.take(7)}`: $why. $next (#739)."

  // ---- the diff --------------------------------------------------------------------------------

  /** The lines of a file outside the wiring markers, each ending in \n. */
  def outsideMarkers(text: String): String =
    var inside = false
    val kept = Vector.newBuilder[String]
    for line <- Proc.lines(text) do
      if line.contains("<!-- wiring:end -->") then inside = false
      if !inside then kept += line
      if line.contains("<!-- wiring:start -->") then inside = true
    kept.result().map(_ + "\n").mkString

  /** Why the diff is more than gitlinks and REPOS.md's wiring table, or None. */
  def scopeProblem(base: String, head: String, cwd: os.Path, proc: Proc): Option[String] =
    val gitlinks = proc.submodulePaths(cwd, "--blob", s"$base:.gitmodules").map(_._2).toSet
    val diff = proc
      .call(cwd, Seq("git", "diff", "-z", "--no-renames", "--name-only", base, head), os.Pipe)
      .out
    val paths = diff.split('\u0000').filter(_.nonEmpty).toVector
    // A .gitmodules path that is no longer a gitlink (a file, or deleted) is not a pointer move.
    def pointerMove(path: String) = treeEntry(head, path, cwd, proc).map(_.mode) == Vector("160000")
    val notPointers = paths.filter { path =>
      if gitlinks.contains(path) then !pointerMove(path) else path != ReposMd
    }
    val reposMdEdited =
      !gitlinks.contains(ReposMd) && paths.contains(ReposMd) && !wiringOnly(base, head, cwd, proc)
    if reposMdEdited then
      Some(s"`$ReposMd` changes outside its `wiring:start`/`wiring:end` markers")
    else
      Option.when(notPointers.nonEmpty)(
        s"it changes more than submodule pointers: ${notPointers.map(p => s"`$p` ").mkString}"
      )

  private def wiringOnly(base: String, head: String, cwd: os.Path, proc: Proc): Boolean =
    def exists(rev: String) = proc.quiet(cwd, "git", "cat-file", "-e", s"$rev:$ReposMd") == 0
    def outside(rev: String) = outsideMarkers(
      proc.call(cwd, Seq("git", "show", s"$rev:$ReposMd"), os.Pipe).out
    )
    exists(base) && exists(head) && outside(base) == outside(head)

  private final case class TreeEntry(mode: String, sha: String)

  /** `git ls-tree <head> -- <path>`, one entry per line. */
  private def treeEntry(head: String, path: String, cwd: os.Path, proc: Proc): Vector[TreeEntry] =
    Proc.lines(proc.capture(cwd, "git", "ls-tree", head, "--", path).out).map { line =>
      val fields = line.trim.split("\\s+")
      TreeEntry(line.takeWhile(_ != ' '), fields.lift(2).getOrElse(""))
    }

  private val MarolaDevRepo = raw"https://github\.com/(marola-dev/[A-Za-z0-9._-]+)".r

  /** Why a moved gitlink is not on its marola-dev repo's default branch, or None. */
  def pointerProblem(base: String, head: String, cwd: os.Path, proc: Proc): Option[String] =
    val entries = proc.submodulePaths(cwd, "--blob", s"$base:.gitmodules")
    entries.iterator
      .flatMap { (key, path) =>
        val unchanged = proc.status(cwd, "git", "diff", "--quiet", base, head, "--", path) == 0
        if unchanged then None
        else
          val name = key.stripPrefix("submodule.").stripSuffix(".path")
          val url = proc
            .capture(cwd, "git", "config", "--blob", s"$base:.gitmodules", s"submodule.$name.url")
            .out
          url.stripSuffix(".git") match
            case MarolaDevRepo(repo) =>
              val sha = treeEntry(head, path, cwd, proc).map(_.sha).mkString("\n")
              val status = compareStatus(repo, sha, proc).getOrElse("unreadable")
              Option.when(status != "ahead" && status != "identical")(
                s"`$path` moves to `${sha.take(7)}`, which is not on $repo's default branch (compare: $status)"
              )
            case _ => Some(s"`$path` points at `$url`, not a marola-dev repo")
      }
      .nextOption()

  // GitHub serves a fork's commits through its parent repo, so a SHA that resolves there can still
  // be anyone's: only an ancestor of the default branch is a real pointer move. compare/<sha>...<branch>
  // describes the branch, so an ancestor is `ahead`; `behind` is a commit on top of it, never merged.
  private def compareStatus(repo: String, sha: String, proc: Proc): Option[String] =
    for
      branch <- readField(proc, s"repos/$repo", "default_branch")
      status <- readField(proc, s"repos/$repo/compare/$sha...$branch", "status")
    yield status

  /** `read_gh api <path> | jq -er .<field>`. The App token is installation-scoped to marola, so the
    * submodule repos are read with GH_READ_TOKEN (github.token in CI); unset, gh uses its own
    * login.
    */
  private def readField(proc: Proc, apiPath: String, field: String): Option[String] =
    val readToken = Map("GH_TOKEN" -> proc.variable("GH_READ_TOKEN").getOrElse(""))
    val result = proc.call(os.pwd, Seq("gh", "api", apiPath), os.Pipe, extraEnv = readToken)
    val pages = Json.values(result.out)
    val values = pages.documents.map(page => Json.field(page, field))
    val ok = result.exitCode == 0 && pages.complete && values.lastOption.exists(Json.truthy)
    Option.when(ok)(values.map(Json.text).mkString("\n"))

  // ---- the run ---------------------------------------------------------------------------------

  /** One pass over the rolling PR, from the clone `cwd`. The exit code. */
  def runIn(
      repo: String,
      want: String,
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
            repo,
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
      openPr(prList) match
        case None =>
          Proc.say(s"pointer-sync-merge: no open $Branch PR")
          0
        case Some(pr) if pr.held =>
          Proc.say(s"pointer-sync-merge: #${pr.number} carries hold; left for a person")
          0
        case Some(pr) => Merge(repo, pr, dryRun, cwd, proc, plan).checkAndMerge(want)
    }

  private final case class Merge(
      repo: String,
      pr: Pr,
      dryRun: Boolean,
      cwd: os.Path,
      proc: Proc,
      plan: Option[os.Path]
  ):
    private val n = pr.number
    private val head = pr.head

    // --plan: the keyless job decides and the keyed job only acts, so no third-party action or
    // download ever runs beside MERGE_BOT_KEY (MIP-0086 §5.6, the devkit's merge-key-check).
    private def writePlan(out: os.Path, fields: (String, String)*): Unit =
      os.write.append(out, fields.map((k, v) => s"$k=${v.replace('\n', ' ')}\n").mkString)

    def refuse(why: String, next: String = "Left for a person"): Int =
      val body = refusal(head, why, next)
      Proc.say(s"::warning::#$n $body")
      if alreadySaid(body) then Proc.say(s"pointer-sync-merge: #$n already says so")
      else
        plan.foreach(writePlan(_, "action" -> "comment", "pr" -> n, "head" -> head, "body" -> body))
        if dryRun || plan.isDefined then Proc.say(s"+ gh pr comment $n -R $repo --body '$body'")
        else proc.must(cwd, "gh", "pr", "comment", n, "-R", repo, "--body", body)
      0

    private def alreadySaid(body: String): Boolean =
      val view =
        proc.call(cwd, Seq("gh", "pr", "view", n, "-R", repo, "--json", "comments"), os.Pipe).out
      Json.values(view).documents.lastOption.exists { view =>
        Json
          .items(Json.field(view, "comments"))
          .exists(c => Json.field(c, "body") == ujson.Str(body))
      }

    def checkAndMerge(want: String): Int =
      if want.nonEmpty && want != head then
        refuse(
          s"the checks ran on `${want.take(7)}`, but the head has moved",
          "The new head's checks retry it"
        )
      else checksPassed()

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
      val runs = Json.values(runsText)
      val Proc.Result(statusCode, statusText) =
        proc.call(
          cwd,
          Seq("gh", "api", "--paginate", s"repos/$repo/commits/$head/status?per_page=100"),
          os.Pipe
        )
      // `gh | jq` under pipefail: jq's failure is the rightmost, then gh's; either ends the run.
      val statusPages = Json.values(statusText)
      if !statusPages.readable("statuses") then throw Proc.Abort(Json.JqError)
      if statusCode != 0 then throw Proc.Abort(statusCode)
      val checks = checkRuns(runs.documents) ++ statuses(statusPages.documents)
      val failedChecks = failed(checks)
      if failedChecks.nonEmpty then refuse(s"not every check passed: $failedChecks")
      else
        // The PR-body checks can finish before CI has created a single check run (#815 merged that
        // way), so "every run so far passed" is not "CI passed": wait for ci.yml's aggregate job.
        if !runs.readable("check_runs") then throw Proc.Abort(Json.JqError)
        val ok = ciOk(runs.documents)
        val waiting = pending(checks)
        if checks.isEmpty || waiting.nonEmpty || ok != "success" then
          Proc.say(s"::notice::#$n waits for ${
              if waiting.isEmpty then "ci-ok" else waiting
            }; a later run retries")
          0
        else inScope()

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
      if proc.capture(cwd, "git", "rev-parse", s"refs/remotes/origin/pull/$n").out != head then
        refuse("the head moved while this run read it", "The new head's checks retry it")
      else
        val base = proc.mustCapture(cwd, "git", "merge-base", "refs/remotes/origin/main", head)
        scopeProblem(base, head, cwd, proc).orElse(pointerProblem(base, head, cwd, proc)) match
          case Some(why) => refuse(why)
          case None      => merge()

    // --admin only skips gh's client-side refusal of a BLOCKED PR; GitHub still decides, and lets this
    // App through as main-rule's pull-request-only bypass actor. --match-head-commit aborts the merge
    // if anything was pushed since the checks ran.
    private def merge(): Int =
      val command =
        Seq("gh", "pr", "merge", n, "--squash", "--admin", "--match-head-commit", head, "-R", repo)
      plan.foreach(writePlan(_, "action" -> "merge", "pr" -> n, "head" -> head))
      if dryRun || plan.isDefined then Proc.say(s"+ ${command.mkString(" ")}")
      else
        proc.must(cwd, command*)
        Proc.say(s"pointer-sync-merge: merged #$n at ${head.take(7)}")
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
        runIn(name, sha.getOrElse(""), dryRun.value, os.pwd, Proc(), out)

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "pointer-sync-merge", args)

  // The jq behaviours these reads keep, exit code included: gh --paginate prints one JSON document
  // per page, `.a.b` of a missing key is null, `[]` of null is nothing, and `-r` prints strings bare.
  object Json:
    /** jq's exit code for a parse error, or for an error on its last input. */
    val JqError = 5

    /** The documents read before the end of the text, or before the first one that does not parse.
      */
    final case class Documents(documents: Vector[ujson.Value], complete: Boolean):
      /** `jq '.<key>[]'` exits 0: everything parsed and the last document has `key` to iterate. */
      def readable(key: String): Boolean =
        complete && documents.lastOption.forall(doc => iterable(field(doc, key)))

    /** Every JSON document in `text`, as `jq` reads a stream. */
    def values(text: String): Documents =
      val documents = Vector.newBuilder[ujson.Value]
      var start = skipBlanks(text, 0)
      var complete = true
      while start < text.length && complete do
        val end = documentEnd(text, start)
        scala.util.Try(ujson.read(text.substring(start, end))) match
          case scala.util.Success(document) => documents += document
          case scala.util.Failure(_)        => complete = false
        start = skipBlanks(text, end)
      Documents(documents.result(), complete)

    private def skipBlanks(text: String, from: Int): Int =
      var i = from
      while i < text.length && text(i).isWhitespace do i += 1
      i

    /** Where the document starting at `start` ends: its closing bracket, or the next blank for a
      * scalar.
      */
    private def documentEnd(text: String, start: Int): Int =
      if text(start) != '{' && text(start) != '[' then
        var i = start
        while i < text.length && !text(i).isWhitespace do i += 1
        i
      else
        var depth = 0
        var inString = false
        var i = start
        var end = -1
        while i < text.length && end < 0 do
          val c = text(i)
          if inString then
            if c == '\\' then i += 1
            else if c == '"' then inString = false
          else if c == '"' then inString = true
          else if c == '{' || c == '[' then depth += 1
          else if c == '}' || c == ']' then
            depth -= 1
            if depth == 0 then end = i + 1
          i += 1
        if end < 0 then text.length else end

    def field(value: ujson.Value, key: String): ujson.Value = value match
      case obj: ujson.Obj => obj.value.getOrElse(key, ujson.Null)
      case _              => ujson.Null

    def iterable(value: ujson.Value): Boolean =
      value.isInstanceOf[ujson.Arr] || value.isInstanceOf[ujson.Obj]

    /** `.[]`, and nothing for a value it would refuse. */
    def items(value: ujson.Value): Seq[ujson.Value] = value match
      case arr: ujson.Arr => arr.value.toSeq
      case obj: ujson.Obj => obj.value.values.toSeq
      case _              => Seq.empty

    def truthy(value: ujson.Value): Boolean = value != ujson.Null && value != ujson.False

    /** `a // b` */
    def orElse(value: ujson.Value, fallback: ujson.Value): ujson.Value =
      if truthy(value) then value else fallback

    /** A value as `jq -r` or string interpolation prints it. */
    def text(value: ujson.Value): String = value match
      case ujson.Str(s)                              => s
      case ujson.Num(d) if d.isWhole && d.abs < 1e17 => d.toLong.toString
      case other                                     => ujson.write(other)

    /** A value as one `@tsv` field. */
    def tsv(value: ujson.Value): String = value match
      case ujson.Null   => ""
      case ujson.Str(s) =>
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
      case other => text(other)
