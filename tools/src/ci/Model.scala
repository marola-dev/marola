package marola.tools.ci

import marola.tools.shared.{Conclusion, GitHubNames, Jq, RepoSlug, Util}

import java.math.{BigDecimal as JBigDecimal, RoundingMode}
import java.time.{Clock, Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

// The ci context's domain: workflow runs and the README badges (repo-stats), the billing usage
// report (gh-billing), and the research digests' candidates (arxiv-digest,
// awesome-agentic-digest). Decoding and files are the commands' business, not this file's.

// ---- workflow runs and badges ---------------------------------------------------------------

/** A null conclusion is a step still queued or running. */
final case class Step(conclusion: Option[Conclusion])

final case class Job(name: Option[String], conclusion: Option[Conclusion], steps: Seq[Step])

/** Steps that passed, out of the steps that ran. */
final case class StepTally(green: Int, ran: Int)

object StepTally:
  /** Every step of every job of one run, the reporting job left out. */
  def of(jobs: Seq[Job], excludeJob: Option[String]): StepTally =
    val ran =
      for
        job <- jobs
        if !excludeJob.exists(job.name.contains)
        step <- job.steps
        conclusion <- step.conclusion
        if conclusion.ran
      yield conclusion
    StepTally(ran.count(_ == Conclusion.Success), ran.length)

object BadgeLabel:
  val CiSteps = "ci steps"
  val Tests = "tests"
  val Scala = "scala"
  val ScalaLoc = "scala LOC"
  val Jdk = "jdk"

object BadgeColor:
  val Green = "brightgreen"
  val Yellow = "yellow"
  val Red = "red"
  val Grey = "lightgrey"
  val Scala = "DC322F" // = marola-app's README's hand-written Scala badge
  val Jdk = "007396"

/** The file each badge is published under on `site-data`; the README links these names. */
object BadgeFileName:
  val Ci = "ci.json"
  val Tests = "tests.json"
  val ScalaLoc = "scala-loc.json"
  val ToolingLoc = "tooling-loc.json"
  val ScalaVersion = "scala-version.json"
  val JdkVersion = "jdk-version.json"

/** One shields.io endpoint document (https://shields.io/badges/endpoint-badge). */
final case class Badge(label: String, message: String, color: String):
  def toJson: ujson.Obj =
    ujson.Obj("schemaVersion" -> 1, "label" -> label, "message" -> message, "color" -> color)

object Badge:
  val NoData = "no data"
  val Passing = "passing"
  val Failing = "failing"

  def noData(label: String): Badge = Badge(label, NoData, BadgeColor.Grey)

  /** Green only when every step that ran passed: a single red step is not a rounding error. */
  def ci(tally: StepTally): Badge =
    val StepTally(green, ran) = tally
    if ran == 0 then noData(BadgeLabel.CiSteps)
    else
      val color =
        if green == ran then BadgeColor.Green
        else if green.toDouble >= 0.9 * ran then BadgeColor.Yellow
        else BadgeColor.Red
      Badge(BadgeLabel.CiSteps, s"$green/$ran green", color)

  /** The munit job's verdict: passing only on success; a skipped or missing job is no data. */
  def tests(jobs: Seq[Job], job: String): Badge =
    jobs.find(_.name.contains(job)).flatMap(_.conclusion) match
      case Some(Conclusion.Success) => Badge(BadgeLabel.Tests, Passing, BadgeColor.Green)
      case Some(Conclusion.Failure | Conclusion.Cancelled | Conclusion.TimedOut) =>
        Badge(BadgeLabel.Tests, Failing, BadgeColor.Red)
      case Some(Conclusion.Skipped | Conclusion.Neutral | Conclusion.Unknown(_)) | None =>
        noData(BadgeLabel.Tests)

  def loc(label: String, code: Long, color: String): Badge =
    Badge(label, s"${thousands(code)} LOC", color)

  private def thousands(n: Long): String =
    n.toString.reverse.grouped(3).mkString(",").reverse

  /** The Scala and JDK versions a scala-cli project file pins. */
  def versions(project: String): Seq[BadgeFile] =
    Seq(
      (BadgeFileName.ScalaVersion, BadgeLabel.Scala, ProjectFile.ScalaKey, BadgeColor.Scala),
      (BadgeFileName.JdkVersion, BadgeLabel.Jdk, ProjectFile.JvmKey, BadgeColor.Jdk)
    ).map { (name, label, key, color) =>
      val value = ProjectFile.directive(project, key)
      BadgeFile(name, value.fold(noData(label))(Badge(label, _, color)))
    }

/** A badge and the file name it is published under. */
final case class BadgeFile(name: String, badge: Badge):
  /** The path as the command prints it: the --out-dir as given, then the name. */
  def shownIn(outDir: String): String =
    if outDir.endsWith("/") then outDir + name else s"$outDir/$name"

object ProjectFile:
  /** Where the umbrella's scala-cli project file sits, from the repo root. */
  val Path = Seq("tools", "project.scala")
  val ScalaKey = "scala"
  val JvmKey = "jvm"

  private val Using = raw"//> using\s+(\S+)\s+(\S+)\s*".r

  /** `//> using <key> <value>` in a scala-cli project file; the first one wins. */
  def directive(project: String, key: String): Option[String] =
    project.linesIterator.collectFirst { case Using(`key`, value) => value }

/** What repo-stats counts and reads. */
object RepoStatsSettings:
  // The app is the marola-app submodule (MIP-0070 task 15); ci.yml checks it out for the count.
  val ScalaPaths = Seq("marola-app/core", "marola-app/local", "marola-app/cli")
  // The umbrella's own tooling (#942).
  val ToolingPaths = Seq("tools/src")
  val ExcludeDirs = Seq("target", "node_modules")
  val Cloc = "cloc"
  val Language = "Scala" // cloc's name for it
  val TestsJob = "tools-ci" // ci.yml's munit job
  val DefaultRepo: RepoSlug = RepoSlug.inOrg("marola")
  val DefaultRoot = "."

/** One `repo-stats write` run, as the command line gave it. */
final case class RepoStatsConfig(
    outDir: String, // as given, for the printed paths
    outPath: os.Path,
    root: os.Path,
    cwd: os.Path, // where gh runs
    repo: RepoSlug,
    runId: Option[String],
    excludeJob: Option[String]
)

// ---- billing usage --------------------------------------------------------------------------

/** A product, SKU or unit as the API sent it: any JSON value, sorted and printed as jq does. */
final case class JqValue(json: ujson.Value):
  /** jq's `@tsv` of the value. */
  def tsv: String = json match
    case ujson.Str(s)  => Jq.tsvString(s)
    case ujson.Null    => ""
    case ujson.Num(n)  => Jq.number(n)
    case ujson.Bool(b) => b.toString
    case other         => other.render()

object JqValue:
  val Null: JqValue = JqValue(ujson.Null)

  /** jq's sort order: null < false < true < numbers < strings < arrays < objects. */
  given Ordering[JqValue] = (a, b) => compare(a.json, b.json)

  private def compare(a: ujson.Value, b: ujson.Value): Int =
    def rank(v: ujson.Value) = v match
      case ujson.Null   => 0
      case ujson.False  => 1
      case ujson.True   => 2
      case _: ujson.Num => 3
      case _: ujson.Str => 4
      case _: ujson.Arr => 5
      case _: ujson.Obj => 6
    (a, b) match
      case (ujson.Num(x), ujson.Num(y)) => x.compare(y)
      case (ujson.Str(x), ujson.Str(y)) => x.compareTo(y)
      case _ if rank(a) != rank(b)      => rank(a).compare(rank(b))
      case _                            => a.render().compareTo(b.render())

/** One item of the usage report; a missing or non-numeric amount is 0. */
final case class UsageItem(
    product: JqValue,
    sku: JqValue,
    unitType: JqValue,
    quantity: Double,
    grossAmount: Double,
    discountAmount: Double,
    netAmount: Double
)

/** One product+SKU: a month has several items per SKU (one per repository), summed here. */
final case class Row(
    product: JqValue,
    sku: JqValue,
    unit: JqValue,
    quantity: Double,
    gross: Double,
    discount: Double,
    net: Double
)

/** The account's plan, for its included Actions minutes. */
enum Plan:
  case Free, Pro
  case Other(raw: String)

  def name: String = this match
    case Free       => Plan.FreeName
    case Pro        => Plan.ProName
    case Other(raw) => raw

  // Personal-account plans only: the billing API does not expose them, so they are GitHub's
  // published allowances.
  def includedMinutes: Option[Int] = this match
    case Free     => Some(2000)
    case Pro      => Some(3000)
    case Other(_) => None

object Plan:
  val FreeName = "free"
  val ProName = "pro"

  def parse(name: String): Plan = Seq(Free, Pro).find(_.name == name).getOrElse(Other(name))

/** The names gh-billing looks for in gh's answers and in the environment. */
object BillingNames:
  val ActionsProduct = "actions"
  val MinutesUnit = "minutes"
  val Scope = "user" // the scope the billing endpoint needs
  val LoginPlaceholder = "{username}"

  // gh prefers either over its keyring, first match first.
  val TokenEnvVars = Seq(GitHubNames.GhTokenEnvVar, GitHubNames.GithubTokenEnvVar)

/** Where a report comes from: a saved response, stdin, the live API, or nowhere (--dry-run). */
enum BillingSource:
  case DryRun
  case File(path: String)
  case Stdin
  case Live

/** One gh-billing run, its defaults and environment already read. */
final case class BillingConfig(
    period: BillingPeriod,
    source: BillingSource,
    noScopeCheck: Boolean,
    env: Map[String, String],
    cwd: os.Path // what a relative --from-file is read against
)

/** The month a report covers, as the command line gave it. */
final case class BillingPeriod(year: String, month: String):
  def query: String = s"year=$year&month=$month"

  /** The consolidated usage endpoint for this period. */
  def usagePath(login: String): String = s"/users/$login/settings/billing/usage?$query"

  /** The month zero-padded when it is a number. */
  def shown: String = s"$year-${month.toIntOption.map(m => f"$m%02d").getOrElse(month)}"

enum Usage:
  case Empty
  case Report(rows: Seq[Row], total: Double)

  /** The table, its columns aligned as `column -t` aligns them, and its total. */
  def render(plan: Option[Plan]): String = this match
    case Empty               => "no usage recorded for this period yet\n"
    case Report(rows, total) =>
      val header = Seq("PRODUCT", "SKU", "QUANTITY", "UNIT", "GROSS_USD", "DISCOUNT_USD", "NET_USD")
      val cells = rows.map { r =>
        Seq(
          r.product.tsv,
          r.sku.tsv,
          Jq.number(r.quantity),
          r.unit.tsv,
          Jq.money(r.gross),
          Jq.money(r.discount),
          Jq.money(r.net)
        )
      }
      val lines = Seq.newBuilder[String]
      lines ++= Usage.columns(header +: cells)
      lines += ""
      lines += s"total: $$${Jq.money(total)} net (gross minus any plan-included allowance already applied — a $$0.00 row means the SKU is fully covered by the plan, not free by nature)"
      for p <- plan do
        val minutes = Jq.number(
          rows
            .filter(r =>
              r.product == JqValue(ujson.Str(BillingNames.ActionsProduct)) &&
                r.unit.tsv.toLowerCase == BillingNames.MinutesUnit
            )
            .map(_.quantity)
            .sum
        )
        p.includedMinutes match
          case Some(included) =>
            val percent = JBigDecimal(minutes.toDouble / included * 100)
              .setScale(0, RoundingMode.HALF_EVEN)
              .toPlainString
            lines += s"Actions minutes: $minutes of $included included this month ($percent%, plan: ${p.name}) — Linux-minute equivalents; github.com/settings/billing is the live authority"
          case None =>
            lines += s"Actions minutes used: $minutes (plan '${p.name}' isn't in this script's known table — see github.com/settings/billing for your included allowance)"
      lines += "GHCR storage: public packages are free, so nothing prunes old -<sha> tags (MIP-0065 §4.4; docs/1-Using-marola/DOCKER.md)."
      lines.result().map(_ + "\n").mkString

object Usage:
  /** Items grouped by product+SKU (in jq's `group_by` order), net descending. */
  def of(items: Seq[UsageItem]): Usage =
    if items.isEmpty then Empty
    else
      val rows = items
        .groupBy(i => (i.product, i.sku))
        .toSeq
        .sortBy(_._1)
        .map { case ((product, sku), group) =>
          Row(
            product,
            sku,
            group.head.unitType,
            group.map(_.quantity).sum,
            group.map(_.grossAmount).sum,
            group.map(_.discountAmount).sum,
            group.map(_.netAmount).sum
          )
        }
      Report(rows.sortBy(-_.net), items.map(_.netAmount).sum)

  /** `column -t`: every column but the last padded to its widest cell, two spaces between. */
  def columns(table: Seq[Seq[String]]): Seq[String] =
    def width(cell: String) = cell.codePointCount(0, cell.length)
    val widths = table.transpose.map(_.map(width).max)
    table.map { row =>
      row.zipWithIndex
        .map { (cell, i) =>
          if i == row.length - 1 then cell else cell + " " * (widths(i) - width(cell))
        }
        .mkString("  ")
    }

/** What `gh auth status` says the active token may do. */
enum TokenScopes:
  /** No "Token scopes:" line: a token type no `gh auth refresh` can fix. */
  case Absent

  /** "Token scopes: none". */
  case NoScopes
  case Listed(scopes: Seq[String])

  def has(scope: String): Boolean = this match
    case Listed(scopes)    => scopes.contains(scope)
    case Absent | NoScopes => false

  /** The list as the failure shows it: "a, b", "none", or "" when gh printed none. */
  def shown: String = this match
    case Absent         => ""
    case NoScopes       => TokenScopes.NoneWord
    case Listed(scopes) => scopes.mkString(", ")

object TokenScopes:
  val Prefix = "Token scopes:"
  val NoneWord = "none"

// ---- research digests -----------------------------------------------------------------------

/** One search: its label, the query string and the weight a match adds to an item's score. */
final case class Query(label: String, query: String, weight: Int)

object Query:
  /** Sum of the weights of every query that matched: two narrow hits outscore one broad hit. */
  def relevanceScore(matched: Seq[Query]): Int = matched.map(_.weight).sum

/** Something a digest found and caches: its id is its file name. */
sealed trait Candidate:
  def id: String

  /** The record's fields, in the order the cached file carries them. */
  def toJson: ujson.Obj

final case class Paper(
    arxivId: String,
    absUrl: String,
    title: String,
    summary: String,
    authors: Seq[String],
    categories: Seq[String],
    published: String
) extends Candidate:
  def id: String = arxivId
  def toJson: ujson.Obj = ujson.Obj(
    RecordField.ArxivId -> arxivId,
    RecordField.AbsUrl -> absUrl,
    RecordField.Title -> title,
    RecordField.Summary -> summary,
    RecordField.Authors -> authors,
    RecordField.Categories -> categories,
    RecordField.Published -> published
  )

object Paper:
  /** http://arxiv.org/abs/2609.03658v1 -> 2609.03658 (version-stripped, safe as a filename). */
  def idFromUrl(idUrl: String): String =
    val tail = idUrl.reverse.dropWhile(_ == '/').reverse.split('/').lastOption.getOrElse("")
    val withoutDigits = tail.reverse.dropWhile(_.isDigit).reverse
    if withoutDigits.length < tail.length && withoutDigits.endsWith("v") then
      withoutDigits.dropRight(1)
    else tail

final case class RepoCandidate(
    fullName: String,
    htmlUrl: String,
    description: String,
    stars: Long,
    pushedAt: String,
    topics: Seq[String]
) extends Candidate:
  def id: String = RepoCandidate.key(fullName)
  def toJson: ujson.Obj = ujson.Obj(
    RecordField.FullName -> fullName,
    RecordField.HtmlUrl -> htmlUrl,
    RecordField.Description -> description,
    RecordField.Stars -> ujson.Num(stars.toDouble),
    RecordField.PushedAt -> pushedAt,
    RecordField.Topics -> topics
  )

object RepoCandidate:
  /** 'owner/repo' -> 'owner__repo', safe as a filename. */
  def key(fullName: String): String = fullName.replace("/", "__")

/** The keys of a cached record and of its index row; readers grep the published files for them. */
object RecordField:
  val ArxivId = "arxiv_id"
  val AbsUrl = "abs_url"
  val Title = "title"
  val Summary = "summary"
  val Authors = "authors"
  val Categories = "categories"
  val Published = "published"
  val FullName = "full_name"
  val HtmlUrl = "html_url"
  val Description = "description"
  val Stars = "stars"
  val PushedAt = "pushed_at"
  val Topics = "topics"
  val MatchedQueries = "matched_queries"
  val RelevanceScore = "relevance_score"
  val FetchedAt = "fetched_at"

/** One query's answer: the item it returned under its id. */
final case class Match[+A](id: String, item: A, query: Query)

/** One found item: its id, its fields and every query that found it. */
final case class Hit[+A](id: String, item: A, matched: Seq[Query]):
  def map[B](f: A => B): Hit[B] = Hit(id, f(item), matched)
  def relevanceScore: Int = Query.relevanceScore(matched)

object Hit:
  /** Hits in first-seen order, one per id, with every query that found it. */
  def collect[A](found: Seq[Match[A]]): Seq[Hit[A]] =
    val byId = found.groupBy(_.id)
    found.map(_.id).distinct.map { id =>
      val group = byId(id)
      Hit(id, group.head.item, group.map(_.query))
    }

/** What a digest caches: its cache directory under `.tmp/`, its items' directory, the fields its
  * index keeps, and the key and words its summary uses for the new items.
  */
enum DigestKind(
    val cacheDir: String,
    val itemsDir: String,
    val indexFields: Seq[String],
    val newKey: String,
    val newLabel: String
):
  case Papers
      extends DigestKind(
        "arxiv_cache",
        "papers",
        Seq(
          RecordField.ArxivId,
          RecordField.Title,
          RecordField.Published,
          RecordField.RelevanceScore,
          RecordField.FetchedAt
        ),
        "new_papers",
        "new papers cached"
      )
  case Repos
      extends DigestKind(
        "awesome_agentic_cache",
        "repos",
        Seq(
          RecordField.FullName,
          RecordField.HtmlUrl,
          RecordField.Stars,
          RecordField.Description,
          RecordField.RelevanceScore,
          RecordField.FetchedAt
        ),
        "new_candidates",
        "new candidates cached"
      )

object DigestKind:
  val CacheRoot = ".tmp"
  val IndexFile = "index.jsonl"

/** The services the digests ask, and how they introduce themselves. */
object DigestSources:
  val AtomNs = "http://www.w3.org/2005/Atom"
  val ArxivApi = "https://export.arxiv.org/api/query"
  val GithubSearchApi = "https://api.github.com/search/repositories"
  val ArxivUserAgent = "marola-arxiv-digest/1"
  val GithubUserAgent = "marola-awesome-agentic-digest/1"

  // Verified against the live API on 2026-09-06; cross-domain noise is scored down, not filtered
  // out, per MIP-0019's honesty about query precision.
  val ArxivQueries = Seq(
    Query("ocean-physics", "cat:physics.ao-ph AND abs:ocean", 1),
    Query("sst-forecast", """abs:"sea surface temperature" AND abs:forecast""", 2),
    Query("llm-ocean", """abs:"large language model" AND abs:ocean""", 3),
    Query(
      "llm-weather-forecast",
      """abs:"large language model" AND abs:"weather forecasting"""",
      3
    ),
    Query("jellyfish-prediction", "abs:jellyfish AND abs:prediction", 2),
    Query("marine-heatwave", """abs:"marine heatwave"""", 2),
    Query("ao-ph-ml", """cat:physics.ao-ph AND abs:"machine learning"""", 1)
  )

  // Verified live on 2026-09-07 (MIP-0043 §4.2). Two sort orders per label feed one cache.
  val GithubQueries = Seq(
    Query("agents", "topic:agents", 1),
    Query("llm-agents", "topic:llm-agents", 2),
    Query("ai-agents", "topic:ai-agents", 1),
    Query("mcp", "topic:mcp", 2),
    Query("multi-agent-systems", "topic:multi-agent-systems", 2)
  )
  val GithubSorts = Seq("stars", "updated")

  val DefaultMaxResults = 5

/** One digest run: the repo root its cache lives under, the command line, and the clock each new
  * record's `fetched_at` is read from.
  */
final case class DigestConfig(root: os.Path, maxResults: Int, asJson: Boolean, clock: Clock):
  def now(): String = DigestConfig.IsoMicros.format(Instant.now(clock))

object DigestConfig:
  /** ISO 8601 in UTC with microseconds and `+00:00`, the format the cached records carry. */
  val IsoMicros: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'").withZone(ZoneOffset.UTC)

/** One digest run, as it reports itself. */
final case class DigestSummary(
    kind: DigestKind,
    queriesRun: Int,
    queriesTotal: Int,
    queriesFailed: Seq[String],
    fresh: Int,
    totalCached: Int,
    indexPath: String
):
  def toJson: ujson.Obj = ujson.Obj(
    "queries_run" -> queriesRun,
    "queries_failed" -> queriesFailed,
    kind.newKey -> fresh,
    "total_cached" -> totalCached,
    "index_path" -> indexPath
  )

  /** The summary as `--json` prints it, or as the plain lines. */
  def render(asJson: Boolean): String =
    if asJson then Util.dumps(toJson, indent = Some(2)) + "\n"
    else
      Seq(
        Some(s"queries run: $queriesRun/$queriesTotal"),
        Option.when(queriesFailed.nonEmpty)(
          s"queries failed: ${ujson.Arr.from(queriesFailed.map(ujson.Str(_))).render()}"
        ),
        Some(s"${kind.newLabel}: $fresh"),
        Some(s"total cached: $totalCached"),
        Some(s"index: $indexPath")
      ).flatten.map(_ + "\n").mkString
