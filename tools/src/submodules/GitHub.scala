package marola.tools.submodules

import marola.tools.shared.{Conclusion, GitHubNames, Jq, Sha}

// gh's output decoded into the model, read as the jq pipelines it replaced read it (shared Jq).
object GitHub:
  /** The key of a check-runs page's list, and of a combined status page's. */
  val CheckRuns = "check_runs"
  val Statuses = "statuses"

  /** The first open PR of `gh pr list --json number,headRefOid,isCrossRepository,labels` that is
    * this repo's own branch (a fork's PR can carry the same branch name).
    */
  def openPr(prList: String): Option[Pr] =
    val own = Jq.values(prList).documents.flatMap { list =>
      Jq.items(list).find(pr => !Jq.truthy(Jq.field(pr, "isCrossRepository")))
    }
    own.collectFirst { case pr: ujson.Obj =>
      val labels = Jq.items(Jq.field(pr, "labels")).map(label => Jq.text(Jq.field(label, "name")))
      Pr(
        PrNumber(Jq.text(Jq.field(pr, "number"))),
        Sha(Jq.text(Jq.field(pr, "headRefOid"))),
        labels.toVector
      )
    }

  /** The rows of the check-runs pages. */
  def checkRuns(pages: Seq[ujson.Value]): Vector[Check] =
    for
      page <- pages.toVector
      run <- Jq.items(Jq.field(page, CheckRuns))
    yield Check(
      Jq.tsv(Jq.field(run, "name")),
      CheckStatus.parse(Jq.tsv(Jq.field(run, "status"))),
      Conclusion.parse(Jq.tsv(Jq.orElse(Jq.field(run, "conclusion"), ujson.Str(""))))
    )

  /** The commit statuses as check rows. */
  def statuses(pages: Seq[ujson.Value]): Vector[Check] =
    for
      page <- pages.toVector
      status <- Jq.items(Jq.field(page, Statuses))
    yield
      val state = Jq.field(status, "state")
      val progress =
        if state == ujson.Str(CheckStatus.PendingWord) then
          CheckStatus.Unfinished(CheckStatus.PendingWord)
        else CheckStatus.Completed
      Check(Jq.tsv(Jq.field(status, "context")), progress, Conclusion.parse(Jq.tsv(state)))

  /** ci.yml's aggregate job, from GitHub Actions only: its conclusion on each page that has it. */
  def ciOk(pages: Seq[ujson.Value]): Vector[Conclusion] =
    pages.toVector
      .flatMap { page =>
        Jq.items(Jq.field(page, CheckRuns)).find { run =>
          Jq.field(run, "name") == ujson.Str(Checks.CiOk) &&
          Jq.field(Jq.field(run, "app"), "id") == ujson.Num(GitHubNames.ActionsAppId) &&
          Jq.field(run, "status") == ujson.Str(CheckStatus.CompletedWord)
        }
      }
      .map(run => Jq.field(run, "conclusion"))
      .filter(Jq.truthy)
      .map(conclusion => Conclusion.parse(Jq.text(conclusion)))

  /** Whether `gh pr view --json comments` holds a comment with exactly this body. */
  def hasComment(view: String, body: String): Boolean =
    Jq.values(view).documents.lastOption.exists { view =>
      Jq.items(Jq.field(view, "comments")).exists(c => Jq.field(c, "body") == ujson.Str(body))
    }

  /** `jq -er .<field>` over `gh api`'s output: every page's value, or None when jq would fail. */
  def requiredField(exitCode: Int, out: String, field: String): Option[String] =
    val pages = Jq.values(out)
    val values = pages.documents.map(page => Jq.field(page, field))
    val ok = exitCode == 0 && pages.complete && values.lastOption.exists(Jq.truthy)
    Option.when(ok)(values.map(Jq.text).mkString("\n"))
