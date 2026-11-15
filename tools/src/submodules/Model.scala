package marola.tools.submodules

import marola.tools.shared.{Conclusion, Proc, ReposDoc, RepoSlug, Sha}

// The domain of the submodule pointers: moving them (pointer-sync), merging the PR that moves
// them (pointer-sync-merge), and pushing site-data. No IO here; the commands and GitHub.scala
// read git and gh into these types.

// ---- value objects -----------------------------------------------------------------------------

/** A pull request number as gh printed it. */
opaque type PrNumber = String
object PrNumber:
  def apply(value: String): PrNumber = value
  extension (n: PrNumber) def value: String = n

/** The REPOS.md whose wiring block pointer-sync regenerates and pointer-sync-merge lets change. */
object WiringDoc:
  val Path = ReposDoc.Path

  /** The devkit tool that regenerates the block. */
  val Tool = "wiring"

  /** The lines of a file outside the wiring markers, each ending in \n. */
  def outsideMarkers(text: String): String =
    val (kept, _) =
      Proc.lines(text).foldLeft((Vector.empty[String], false)) { case ((kept, inside), line) =>
        val stillInside = inside && !line.contains(ReposDoc.WiringEnd)
        (
          if stillInside then kept else kept :+ line,
          stillInside || line.contains(ReposDoc.WiringStart)
        )
      }
    kept.map(_ + "\n").mkString

// ---- pointer-sync ------------------------------------------------------------------------------

final case class Moved(name: String, old: Sha, now: Sha, url: String):
  /** A GitHub compare link, or None for a non-GitHub remote. */
  def compareUrl: Option[String] =
    RepoSlug.fromGitHubUrl(url).toOption.map(repo => s"${repo.web}/compare/$old...$now")

  def tsvRow: String = s"$name\t$old\t$now\t$url\n"

/** The pointers one pointer-sync run moved, in `.gitmodules` order. */
final case class Bump(moved: Vector[Moved]):
  def isEmpty: Boolean = moved.isEmpty

  def names: String = moved.map(_.name).mkString(",").replace(",", ", ")

  def commitMessage: String =
    val rows = moved.map(m => s"- ${m.name}: ${m.old.short} -> ${m.now.short}\n").mkString
    s"chore: move submodule pointers ($names)\n\n" +
      "Each submodule's default-branch tip, as git submodule update --remote found it:\n\n" +
      rows + "\n" + Bump.Trailers

  def prBody: String =
    val rows = moved.map { m =>
      val link = m.compareUrl.map(url => s"[compare]($url)").getOrElse("")
      s"| `${m.name}` | `${m.old.short}` | `${m.now.short}` | $link |\n"
    }
    "Moves each submodule to its default branch's tip (MIP-0070 §5.6). `pointer-sync.yml` opens this PR " +
      "and force-updates it on every run; merge it once CI is green.\n\n" +
      "| Submodule | From | To | Changes |\n|---|---|---|---|\n" + rows.mkString

object Bump:
  val Trailers =
    "Tested: pointer bump only; each submodule's own CI gates its main\n" +
      "Co-Authored-By: Claude <noreply@anthropic.com>\n"

/** The files pointer-sync writes into --out, which pointer-sync.yml reads. */
object BumpFile:
  val Moved = "moved.tsv"
  val CommitMessage = "commit-msg.txt"
  val PrBody = "pr-body.md"

// ---- pointer-sync-merge: the PR and its checks -------------------------------------------------

/** pointer-sync.yml's one rolling PR: its branch, its base, and what gh is asked of it. */
object RollingPr:
  val Branch = "chore/pointer-sync"
  val Base = "main"
  val Fields = "number,headRefOid,isCrossRepository,labels"

final case class Pr(number: PrNumber, head: Sha, labels: Vector[String]):
  def held: Boolean = labels.contains(Pr.Hold)

object Pr:
  val Hold = "hold"

/** One pointer-sync-merge pass, as the command line and the environment gave it. */
final case class MergeRun(
    repo: RepoSlug,
    want: Option[Sha], // the head the triggering checks ran on; None is any head
    dryRun: Boolean,
    cwd: os.Path, // a full clone of the default branch
    readToken: String, // GH_READ_TOKEN: the App token cannot see the submodule repos
    plan: Option[os.Path] = None
)

object MergeRun:
  val ReadTokenEnvVar = "GH_READ_TOKEN"

/** A check run's status; a commit status is `Completed` unless its state is pending. */
enum CheckStatus:
  case Completed
  case Unfinished(raw: String)

object CheckStatus:
  val CompletedWord = "completed"
  val PendingWord = "pending"

  def parse(raw: String): CheckStatus = if raw == CompletedWord then Completed else Unfinished(raw)

/** A check run or a commit status on the PR's head. */
final case class Check(name: String, status: CheckStatus, conclusion: Conclusion):
  def failed: Boolean = status == CheckStatus.Completed && !conclusion.passed

object Checks:
  /** ci.yml's aggregate job. */
  val CiOk = "ci-ok"

  def failed(checks: Seq[Check]): String =
    checks.filter(_.failed).map(c => s"`${c.name}` (${c.conclusion.wire})").mkString(", ")

  def pending(checks: Seq[Check]): String =
    checks.filter(_.status != CheckStatus.Completed).map(_.name).mkString(", ")

  /** Exactly one completed ci-ok, and it succeeded. */
  def ciPassed(ciOk: Seq[Conclusion]): Boolean = ciOk == Seq(Conclusion.Success)

  /** What the merge still waits for, or None when no check failed and every one is done. The
    * PR-body checks can finish before CI has created a single check run (#815 merged that way), so
    * "every run so far passed" is not "CI passed": it waits for ci.yml's aggregate job.
    */
  def waitingOn(checks: Seq[Check], ciOk: Seq[Conclusion]): Option[String] =
    val waiting = pending(checks)
    Option.when(checks.isEmpty || waiting.nonEmpty || !ciPassed(ciOk))(
      if waiting.isEmpty then CiOk else waiting
    )

// ---- pointer-sync-merge: scope ----------------------------------------------------------------

/** One line of `git ls-tree <rev> -- <path>`. */
final case class TreeEntry(mode: String, sha: Sha):
  def isGitlink: Boolean = mode == TreeEntry.GitlinkMode

object TreeEntry:
  val GitlinkMode = "160000"

  def parse(line: String): TreeEntry =
    val fields = line.trim.split("\\s+")
    TreeEntry(line.takeWhile(_ != ' '), Sha(fields.lift(2).getOrElse("")))

  /** A path that is still exactly one gitlink at the head: a `.gitmodules` path that became a file,
    * or was deleted, is not a pointer move.
    */
  def isPointer(entries: Seq[TreeEntry]): Boolean = entries.map(_.isGitlink) == Seq(true)

/** Why the diff is more than gitlinks and REPOS.md's wiring table. */
enum ScopeProblem:
  case WiringDocOutsideMarkers
  case NotPointers(paths: Vector[String])

  def message: String = this match
    case WiringDocOutsideMarkers =>
      s"`${WiringDoc.Path}` changes outside its `wiring:start`/`wiring:end` markers"
    case NotPointers(paths) =>
      s"it changes more than submodule pointers: ${paths.map(p => s"`$p` ").mkString}"

object ScopeProblem:
  /** `changed` is the diff's paths; `isPointer` reads a gitlink path at the head, and `wiringOnly`
    * compares REPOS.md outside its markers, each asked only when the verdict needs it.
    */
  def of(
      gitlinks: Set[String],
      changed: Vector[String],
      isPointer: String => Boolean,
      wiringOnly: => Boolean
  ): Option[ScopeProblem] =
    val notPointers = changed.filter { path =>
      if gitlinks.contains(path) then !isPointer(path) else path != WiringDoc.Path
    }
    val wiringDocEdited =
      !gitlinks.contains(WiringDoc.Path) && changed.contains(WiringDoc.Path) && !wiringOnly
    if wiringDocEdited then Some(WiringDocOutsideMarkers)
    else Option.when(notPointers.nonEmpty)(NotPointers(notPointers))

/** GitHub's compare/<sha>...<default branch> status. That compare describes the branch, so an
  * ancestor of it is `Ahead`; `Behind` is a commit on top of it, never merged.
  */
enum CompareStatus:
  case Ahead, Identical, Behind, Diverged
  case Other(raw: String)
  case Unreadable

  def onDefaultBranch: Boolean = this match
    case Ahead | Identical                         => true
    case Behind | Diverged | Other(_) | Unreadable => false

  def render: String = this match
    case Ahead      => "ahead"
    case Identical  => "identical"
    case Behind     => "behind"
    case Diverged   => "diverged"
    case Other(raw) => raw
    case Unreadable => "unreadable"

object CompareStatus:
  private val read = Seq(Ahead, Identical, Behind, Diverged)

  /** None is a compare gh could not read. */
  def parse(raw: Option[String]): CompareStatus = raw match
    case None        => Unreadable
    case Some(other) => read.find(_.render == other).getOrElse(Other(other))

/** Why a moved gitlink is not a pointer to merge unattended. */
enum PointerProblem:
  case NotOnDefaultBranch(path: String, sha: Sha, repo: RepoSlug, status: CompareStatus)
  case NotMarolaDev(path: String, url: String)

  def message: String = this match
    case NotOnDefaultBranch(path, sha, repo, status) =>
      s"`$path` moves to `${sha.short}`, which is not on $repo's default branch (compare: ${status.render})"
    case NotMarolaDev(path, url) => s"`$path` points at `$url`, not a marola-dev repo"

// ---- pointer-sync-merge: the outcome ----------------------------------------------------------

/** What a person reads when the merge leaves the PR alone. */
enum NextStep:
  case LeftForAPerson, RetriedOnNewHead

  def render: String = this match
    case LeftForAPerson   => "Left for a person"
    case RetriedOnNewHead => "The new head's checks retry it"

final case class Refusal(why: String, next: NextStep = NextStep.LeftForAPerson):
  def body(head: Sha): String = s"Not auto-merged at `${head.short}`: $why. ${next.render} (#739)."

object Refusal:
  /** The checks ran on `want`, which is no longer the head; an empty `want` means any head. */
  def headMoved(want: Option[Sha], head: Sha): Option[Refusal] =
    want.filter(_ != head).map { sha =>
      Refusal(
        s"the checks ran on `${sha.short}`, but the head has moved",
        NextStep.RetriedOnNewHead
      )
    }

  val MovedWhileRead = Refusal("the head moved while this run read it", NextStep.RetriedOnNewHead)

  def checksFailed(failed: String): Refusal = Refusal(s"not every check passed: $failed")

enum Action:
  case Merge
  case Comment(body: String)

/** What `--plan` hands pointer-sync-merge.yml's keyed job, which only acts on it. */
final case class Plan(action: Action, pr: PrNumber, head: Sha):
  def fields: Seq[(String, String)] =
    val common = Seq(Plan.PrKey -> pr.value, Plan.HeadKey -> head.value)
    action match
      case Action.Merge         => (Plan.ActionKey -> Plan.MergeWord) +: common
      case Action.Comment(body) =>
        ((Plan.ActionKey -> Plan.CommentWord) +: common) :+ (Plan.BodyKey -> body)

  /** `key=value` lines for $GITHUB_OUTPUT, a newline in a value flattened to a space. */
  def render: String = fields.map((k, v) => s"$k=${v.replace('\n', ' ')}\n").mkString

/** The output names pointer-sync-merge.yml's keyed job reads. */
object Plan:
  val ActionKey = "action"
  val PrKey = "pr"
  val HeadKey = "head"
  val BodyKey = "body"
  val MergeWord = "merge"
  val CommentWord = "comment"

// ---- site-data-push ----------------------------------------------------------------------------

/** marola-site's branch the badge and smoke data go to. */
object SiteData:
  val Branch = "site-data"

/** SITE_DATA_PUSH_TRIES as given (messages quote it) and as a count; not a number is zero tries. */
final case class Tries(raw: String):
  val limit: Int = raw.toIntOption.getOrElse(0)

  def exhausted(attempt: Int): Boolean = attempt == limit

  def backoffSeconds(attempt: Int): Int = attempt * 2

object Tries:
  val EnvVar = "SITE_DATA_PUSH_TRIES"
  val Default = "5"

  /** The variable as set, or the default when it is unset. */
  def from(written: Option[String]): Tries = Tries(written.getOrElse(Default))

/** One site-data-push run: the checkout as given, its tries, and where git runs. */
final case class PushRun(checkout: String, tries: Tries, cwd: os.Path)

/** What `git rebase --onto FETCH_HEAD` replays: our one commit, or the whole history when the
  * first-ever push was lost.
  */
enum ReplayBase:
  case Parent, Root

  def arg: String = this match
    case Parent => "HEAD~1"
    case Root   => "--root"
