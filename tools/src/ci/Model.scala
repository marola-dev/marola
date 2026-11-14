package marola.tools.ci

import marola.tools.shared.Util

import java.math.{BigDecimal as JBigDecimal, RoundingMode}

// The ci context's domain: workflow runs and the README badges (repo-stats), the billing usage
// report (gh-billing), and the research digests' candidates (arxiv-digest,
// awesome-agentic-digest). Decoding and files are the commands' business, not this file's.

// ---- workflow runs and badges ---------------------------------------------------------------

/** A job's or a step's conclusion, as the Actions API names it. */
enum Conclusion:
  case Success, Failure, Cancelled, TimedOut, Skipped, Neutral
  case Unknown(raw: String)

  /** A step that reached one of these actually executed; the rest are not evidence either way. */
  def ran: Boolean = this match
    case Success | Failure | Cancelled | TimedOut => true
    case Skipped | Neutral | Unknown(_)           => false

object Conclusion:
  def parse(raw: String): Conclusion = raw match
    case "success"   => Success
    case "failure"   => Failure
    case "cancelled" => Cancelled
    case "timed_out" => TimedOut
    case "skipped"   => Skipped
    case "neutral"   => Neutral
    case other       => Unknown(other)

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

/** One shields.io endpoint document (https://shields.io/badges/endpoint-badge). */
final case class Badge(label: String, message: String, color: String):
  def toJson: ujson.Obj =
    ujson.Obj("schemaVersion" -> 1, "label" -> label, "message" -> message, "color" -> color)

object Badge:
  val ScalaColor = "DC322F" // = marola-app's README's hand-written Scala badge

  def noData(label: String): Badge = Badge(label, "no data", "lightgrey")

  /** Green only when every step that ran passed: a single red step is not a rounding error. */
  def ci(tally: StepTally): Badge =
    val StepTally(green, ran) = tally
    if ran == 0 then noData("ci steps")
    else
      val color =
        if green == ran then "brightgreen"
        else if green.toDouble >= 0.9 * ran then "yellow"
        else "red"
      Badge("ci steps", s"$green/$ran green", color)

  /** The munit job's verdict: passing only on success; a skipped or missing job is no data. */
  def tests(jobs: Seq[Job], job: String): Badge =
    jobs.find(_.name.contains(job)).flatMap(_.conclusion) match
      case Some(Conclusion.Success) => Badge("tests", "passing", "brightgreen")
      case Some(Conclusion.Failure | Conclusion.Cancelled | Conclusion.TimedOut) =>
        Badge("tests", "failing", "red")
      case Some(Conclusion.Skipped | Conclusion.Neutral | Conclusion.Unknown(_)) | None =>
        noData("tests")

  def loc(label: String, code: Long, color: String): Badge =
    Badge(label, s"${thousands(code)} LOC", color)

  private def thousands(n: Long): String =
    n.toString.reverse.grouped(3).mkString(",").reverse

  /** The Scala and JDK versions a scala-cli project file pins. */
  def versions(project: String): Seq[BadgeFile] =
    Seq(
      ("scala-version.json", "scala", ProjectFile.directive(project, "scala"), ScalaColor),
      ("jdk-version.json", "jdk", ProjectFile.directive(project, "jvm"), "007396")
    ).map { (name, label, value, color) =>
      BadgeFile(name, value.fold(noData(label))(Badge(label, _, color)))
    }

/** A badge and the file name it is published under. */
final case class BadgeFile(name: String, badge: Badge):
  /** The path as the command prints it: the --out-dir as given, then the name. */
  def shownIn(outDir: String): String =
    if outDir.endsWith("/") then outDir + name else s"$outDir/$name"

object ProjectFile:
  private val Using = raw"//> using\s+(\S+)\s+(\S+)\s*".r

  /** `//> using <key> <value>` in a scala-cli project file; the first one wins. */
  def directive(project: String, key: String): Option[String] =
    project.linesIterator.collectFirst { case Using(`key`, value) => value }

// ---- billing usage --------------------------------------------------------------------------

/** A product, SKU or unit as the API sent it: any JSON value, sorted and printed as jq does. */
final case class JqValue(json: ujson.Value):
  /** jq's `@tsv` of the value. */
  def tsv: String = json match
    case ujson.Str(s) =>
      s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
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
    case Free       => "free"
    case Pro        => "pro"
    case Other(raw) => raw

  // Personal-account plans only: the billing API does not expose them, so they are GitHub's
  // published allowances.
  def includedMinutes: Option[Int] = this match
    case Free     => Some(2000)
    case Pro      => Some(3000)
    case Other(_) => None

object Plan:
  def parse(name: String): Plan = name match
    case "free" => Free
    case "pro"  => Pro
    case other  => Other(other)

/** The month a report covers, as the command line gave it. */
final case class BillingPeriod(year: String, month: String):
  def query: String = s"year=$year&month=$month"

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
              r.product == JqValue(ujson.Str("actions")) && r.unit.tsv.toLowerCase == "minutes"
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
    case NoScopes       => "none"
    case Listed(scopes) => scopes.mkString(", ")

/** Numbers as jq 1.7 computes and prints them. */
object Jq:
  /** `.*100|round/100|tostring`: round half away from zero, as C's round. */
  def money(amount: Double): String =
    number(JBigDecimal(amount * 100).setScale(0, RoundingMode.HALF_UP).doubleValue / 100)

  /** Shortest digits, exponent past 17 digits or below 1e-4. */
  def number(n: Double): String =
    if n == 0 then (if 1 / n < 0 then "-0" else "0")
    else
      val exact = JBigDecimal(java.lang.Double.toString(math.abs(n))).stripTrailingZeros
      val digits = exact.unscaledValue.toString
      val pointAt = digits.length - exact.scale // digits before the decimal point
      val sign = if n < 0 then "-" else ""
      if pointAt <= -4 || pointAt > digits.length + 15 then
        val mantissa = if digits.length == 1 then digits else s"${digits.head}.${digits.tail}"
        val exponent = pointAt - 1
        sign + mantissa + f"e${if exponent < 0 then "-" else "+"}${math.abs(exponent)}%02d"
      else if pointAt <= 0 then s"${sign}0.${"0" * -pointAt}$digits"
      else if pointAt >= digits.length then sign + digits + "0" * (pointAt - digits.length)
      else s"$sign${digits.take(pointAt)}.${digits.drop(pointAt)}"

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
    "arxiv_id" -> arxivId,
    "abs_url" -> absUrl,
    "title" -> title,
    "summary" -> summary,
    "authors" -> authors,
    "categories" -> categories,
    "published" -> published
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
    "full_name" -> fullName,
    "html_url" -> htmlUrl,
    "description" -> description,
    "stars" -> ujson.Num(stars.toDouble),
    "pushed_at" -> pushedAt,
    "topics" -> topics
  )

object RepoCandidate:
  /** 'owner/repo' -> 'owner__repo', safe as a filename. */
  def key(fullName: String): String = fullName.replace("/", "__")

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

/** What a digest caches, and the key and words its summary uses for the new items. */
enum DigestKind(val newKey: String, val newLabel: String):
  case Papers extends DigestKind("new_papers", "new papers cached")
  case Repos extends DigestKind("new_candidates", "new candidates cached")

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
