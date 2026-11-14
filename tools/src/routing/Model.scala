package marola.tools.routing

import marola.tools.shared.{ReposDoc, Util}

import java.math.{BigDecimal, RoundingMode}
import scala.collection.immutable.SortedMap

// The routing context's domain: the bench score routing-bundle publishes and guards (MIP-0084,
// MIP-0085 §5.1), the digest and manifest it writes, and the graph graph-svg draws.

// ---- the bench ------------------------------------------------------------------------------------

/** A question's type, as routing-bench.yaml names it: an open set, two of which are implied. */
opaque type QuestionType = String

object QuestionType:
  val Symbol: QuestionType = "symbol"
  val Wiring: QuestionType = "wiring"

  def apply(name: String): QuestionType = name

  // A question without a type is one of MIP-0084's eight: wiring, or a symbol query.
  def of(declared: Option[String], via: Option[String]): QuestionType =
    declared.filter(_.nonEmpty).getOrElse(if via.contains("wiring") then Wiring else Symbol)

  extension (t: QuestionType) def name: String = t

/** A bench question: its id as `route bench` prints it, and its type. */
final case class Question(id: String, questionType: QuestionType)

enum Verdict:
  case Hit, Miss

object Verdict:
  def parse(word: String): Option[Verdict] = word match
    case "right" => Some(Verdict.Hit)
    case "MISS"  => Some(Verdict.Miss)
    case _       => None

/** One line of `route bench`'s output: a question and whether its first answer was right. */
final case class BenchLine(verdict: Verdict, questionId: String)

final case class Score(right: Int, total: Int):
  def add(verdict: Verdict): Score =
    Score(right + (if verdict == Verdict.Hit then 1 else 0), total + 1)

final case class TypeScore(questionType: QuestionType, score: Score)

/** A bench run: the total, and by each question's type in the order the types first appear. */
final case class Bench(right: Int, total: Int, byType: Seq[TypeScore]):
  def rightOf(t: QuestionType): Option[Int] = byType.find(_.questionType == t).map(_.score.right)

  def toJson: ujson.Obj = ujson.Obj(
    "right" -> right,
    "total" -> total,
    "by_type" -> ujson.Obj.from(
      byType.map(ts =>
        ts.questionType.name -> ujson.Obj("right" -> ts.score.right, "total" -> ts.score.total)
      )
    )
  )

object Bench:
  /** A line whose id is not a known question counts as a symbol query. */
  def tally(lines: Seq[BenchLine], questions: Seq[Question]): Bench =
    val kind = questions.map(q => q.id -> q.questionType).toMap
    val scores = lines.foldLeft(Vector.empty[TypeScore]) { (scores, line) =>
      val t = kind.getOrElse(line.questionId, QuestionType.Symbol)
      scores.indexWhere(_.questionType == t) match
        case -1 => scores :+ TypeScore(t, Score(0, 0).add(line.verdict))
        case i  => scores.updated(i, TypeScore(t, scores(i).score.add(line.verdict)))
    }
    Bench(scores.map(_.score.right).sum, scores.map(_.score.total).sum, scores)

/** The bench build.json last published, read loosely: a missing or malformed count is 0. */
final case class PublishedBench(right: Long, byType: Option[Seq[(String, Long)]])

/** Why a bench may not replace the published one. */
enum Drop:
  case Total
  case Type(questionType: String, now: Long, before: Long)

  def render: String = this match
    case Total                => "total"
    case Type(t, now, before) => s"$t $now < $before"

object Drop:
  /** The question types (or the total, for a previous score with no types) that scored lower. One
    * type rising never pays for another falling.
    */
  def between(current: Bench, previous: PublishedBench): Seq[Drop] =
    previous.byType match
      case None =>
        if current.right.toLong >= previous.right then Nil else Seq(Total)
      case Some(previousByType) =>
        previousByType.sortBy(_._1).flatMap { (t, before) =>
          val now = current.rightOf(QuestionType(t)).getOrElse(0).toLong
          Option.when(now < before)(Type(t, now, before))
        }

/** The build.json the graph job wrote, stamped with the graphify version and the bench score. */
final case class BuildInfo(json: ujson.Value):
  /** A build.json that is not an object passes through unchanged. */
  def stamped(graphify: String, bench: Bench): BuildInfo = json match
    case obj: ujson.Obj =>
      val copy = ujson.Obj.from(obj.value)
      copy("graphify") = graphify
      copy("bench") = bench.toJson
      BuildInfo(copy)
    case other => BuildInfo(other)

// ---- the digest -------------------------------------------------------------------------------

/** A graph node as the digest sees it; `line` is 0 when the node has none. */
final case class DigestNode(sourceFile: String, label: String, isCode: Boolean, line: BigInt)

object DigestNode:
  /** The digits of a source location ("L12" is 12), or 0 past u64's range, as `route` parses it. */
  def line(location: String): BigInt =
    val digits = location.filter(c => c >= '0' && c <= '9')
    val line = if digits.isEmpty then BigInt(0) else BigInt(digits)
    if line.bitLength > 64 then 0 else line

object Digest:
  val MaxChars = 100_000 // §5.3's 25k-token cap, at about 4 characters a token

  /** The submodule a source file is in (any `marola-*` top directory), or the umbrella. */
  def repoOf(sourceFile: String): String =
    val top = sourceFile.split("/", -1).head
    if top.startsWith("marola-") && sourceFile.contains('/') then top else "marola"

  def basename(path: String): String =
    val trimmed = path.reverse.dropWhile(_ == '/').reverse
    trimmed.substring(trimmed.lastIndexOf('/') + 1)

  private case class Entry(repo: String, source: String, line: BigInt, label: String)

  /** routing-digest.md: the wiring block, then each repo's code files with their symbols. A node
    * named after its own file says nothing and is left out.
    */
  def render(nodes: Seq[DigestNode], wiringBlock: String): String =
    val entries = nodes.flatMap { n =>
      Option.when(n.sourceFile.nonEmpty && n.isCode && n.label != basename(n.sourceFile))(
        Entry(repoOf(n.sourceFile), n.sourceFile, n.line, n.label)
      )
    }
    val byRepo = entries.groupBy(_.repo).to(SortedMap)
    val repoSections = byRepo.toSeq.flatMap { (repo, inRepo) =>
      val files = inRepo.groupBy(_.source).to(SortedMap).map { (source, inFile) =>
        val names = inFile.sortBy(e => (e.line, e.label)).map(_.label).distinct
        s"- `$source`: ${names.mkString(", ")}"
      }
      Seq(s"## $repo", "") ++ files ++ Seq("")
    }
    val lines = Seq(
      "# marola routing digest",
      "",
      "## Wiring",
      "",
      Util.trimUnicode(wiringBlock),
      ""
    ) ++ repoSections
    capped(lines.mkString("\n")) + "\n"

  // The cap counts code points, not UTF-16 units.
  def capped(text: String): String =
    if text.codePointCount(0, text.length) <= MaxChars then text
    else
      val head = text.substring(0, text.offsetByCodePoints(0, MaxChars))
      val cut = head.lastIndexOf('\n').max(0)
      s"${text.substring(0, cut)}\n\n_Cut at the 25k-token cap: `route query` has the rest._"

object WiringBlock:
  val Start = ReposDoc.WiringStart
  val End = ReposDoc.WiringEnd

  /** What REPOS.md holds between the markers. */
  def extract(reposMd: String): String =
    val start = reposMd.indexOf(Start)
    val rest = if start < 0 then "" else reposMd.substring(start + Start.length)
    rest.indexOf(End) match
      case -1  => "_No wiring block found._"
      case end => rest.substring(0, end)

// ---- the bundle -------------------------------------------------------------------------------

final case class FileSize(name: String, bytes: Long)

/** manifest.json: every file of the bundle but itself, with its size. */
final case class Manifest(files: Seq[FileSize]):
  def toJson: ujson.Obj = ujson.Obj(
    "schema" -> Manifest.Schema,
    "files" -> ujson.Obj.from(files.map(f => f.name -> ujson.Num(f.bytes.toDouble)))
  )

object Manifest:
  val Schema = 1

/** A path as the caller wrote it, kept for messages. */
final case class Given(shown: String):
  def /(name: String): Given = Given(
    if shown.isEmpty || shown.endsWith("/") then shown + name else s"$shown/$name"
  )

/** One routing-bundle run: the paths as the command line gave them, and what they are relative to.
  */
final case class Args(
    graphDir: Given,
    out: Given,
    bench: Given,
    questions: Given,
    previous: Option[Given],
    graphify: String,
    reposMd: Given,
    cwd: os.Path
):
  def path(file: Given): os.Path = os.Path(file.shown, cwd)

/** The files of the bundle, as `route` reads them. */
object BundleFile:
  val Graph = "graph.json"
  val Wiring = "wiring.json"
  val Build = "build.json"
  val Digest = "routing-digest.md"
  val Manifest = "manifest.json"

  /** What must be there before anything is written, and what is copied as it is. */
  val Checked = Seq(Graph, Wiring, Build)
  val Copied = Seq(Graph, Wiring)

  // MIP-0085's indexes: route says a missing one is missing, so each is published only when built.
  val Indexes = Seq("doc_code.json", "decisions.json", "meaning.json")

  /** The bench's questions, at the repo root. */
  val Questions = "routing-bench.yaml"

// ---- the graphify figure ----------------------------------------------------------------------

/** The repos graph-svg colours, in the order of the legend and of the layout's ring. A source file
  * outside every submodule here is the umbrella's.
  */
enum Repo(val slug: String, val colour: String):
  case App extends Repo("marola-app", "#DC322F")
  case Site extends Repo("marola-site", "#4CC9F0")
  case Ml extends Repo("marola-ml", "#F2C14E")
  case Umbrella extends Repo("marola", "#DEA584")
  case Research extends Repo("marola-research", "#B388FF")
  case Skills extends Repo("marola-skills", "#7BD389")
  case Agents extends Repo("marola-agents", "#F78FB3")
  case Oods extends Repo("marola-oods", "#2EC4B6")
  case Corpus extends Repo("marola-corpus", "#FF8C42")

object Repo:
  def ofSource(sourceFile: String): Repo =
    val top = sourceFile.split("/", -1).head
    values.find(_.slug == top).getOrElse(Umbrella)

final case class Node(id: String, repo: Repo)

/** A link between two nodes by index, smaller first. */
final case class Edge(a: Int, b: Int)

object Edge:
  given Ordering[Edge] = Ordering.by(e => (e.a, e.b))

  /** No edge from a node to itself. */
  def between(i: Int, j: Int): Option[Edge] = Option.when(i != j)(Edge(i.min(j), i.max(j)))

final case class Point(x: Double, y: Double)

/** graph.json's nodes and links as read: a missing id is empty, a missing end is None. */
final case class RawNode(id: String, sourceFile: String)
final case class RawLink(source: Option[String], target: Option[String])

/** The nodes sorted by id (the first of each id kept) and the links between them, once each. */
final case class Graph(nodes: Vector[Node], edges: Vector[Edge])

object Graph:
  def of(nodes: Seq[RawNode], links: Seq[RawLink], keep: String => Boolean): Graph =
    val kept =
      nodes.flatMap(n => Option.when(keep(n.sourceFile))(Node(n.id, Repo.ofSource(n.sourceFile))))
    val unique = kept.sortBy(_.id).foldLeft(Vector.empty[Node]) { (acc, n) =>
      if acc.lastOption.exists(_.id == n.id) then acc else acc :+ n
    }
    val index = unique.map(_.id).zipWithIndex.toMap
    val edges = links.flatMap { l =>
      for
        a <- l.source.flatMap(index.get)
        b <- l.target.flatMap(index.get)
        edge <- Edge.between(a, b)
      yield edge
    }
    Graph(unique, edges.distinct.sorted.toVector)

/** The figure: Fruchterman–Reingold pulled toward each repo's spot on a ring, seeded by a hash of
  * each node id, so the same graph always draws the same bytes.
  */
object Figure:
  private val W = 1600.0
  private val H = 1000.0
  private val Iterations = 300
  private val Pull = 0.02

  private def fnv(s: String): Long =
    s.getBytes("UTF-8").foldLeft(0xcbf29ce484222325L)((h, b) => (h ^ (b & 0xffL)) * 0x100000001b3L)

  private def unit(h: Long): Double = (h >>> 11).toDouble / (1L << 53).toDouble

  private def anchor(repo: Repo): Point =
    val a = repo.ordinal.toDouble / Repo.values.length.toDouble * (2 * math.Pi)
    Point(W / 2.0 + W * 0.3 * math.cos(a), H / 2.0 + H * 0.3 * math.sin(a))

  // Trap: keep every operation in its order. Floating point is not associative, so a reordered sum
  // moves a coordinate's last digit, and the published SVG is compared byte for byte. Arrays, not
  // vectors: the n² pass runs 300 times, and the mutation never leaves this method.
  def layout(nodes: Vector[Node], links: Vector[Edge]): Vector[Point] =
    val n = nodes.length
    val k = math.sqrt(W * H / n.max(1).toDouble) * 0.6
    val x = Array.ofDim[Double](n)
    val y = Array.ofDim[Double](n)
    for (node, i) <- nodes.zipWithIndex do
      val Point(ax, ay) = anchor(node.repo)
      val h = fnv(node.id)
      x(i) = ax + (unit(h) - 0.5) * 200.0
      y(i) = ay + (unit(java.lang.Long.rotateLeft(h, 29)) - 0.5) * 200.0
    val dx = Array.ofDim[Double](n)
    val dy = Array.ofDim[Double](n)
    for step <- 0 until Iterations do
      val t = W / 10.0 * (1.0 - step.toDouble / Iterations.toDouble) + 0.5
      java.util.Arrays.fill(dx, 0.0)
      java.util.Arrays.fill(dy, 0.0)
      for i <- 0 until n; j <- i + 1 until n do
        val (ddx, ddy) = (x(i) - x(j), y(i) - y(j))
        val f = k * k / (ddx * ddx + ddy * ddy).max(0.01)
        dx(i) += ddx * f
        dy(i) += ddy * f
        dx(j) -= ddx * f
        dy(j) -= ddy * f
      for Edge(a, b) <- links do
        val (ddx, ddy) = (x(a) - x(b), y(a) - y(b))
        val f = math.sqrt(ddx * ddx + ddy * ddy).max(0.1) / k
        dx(a) -= ddx * f
        dy(a) -= ddy * f
        dx(b) += ddx * f
        dy(b) += ddy * f
      for (node, i) <- nodes.zipWithIndex do
        val Point(ax, ay) = anchor(node.repo)
        dx(i) += (ax - x(i)) * Pull * k
        dy(i) += (ay - y(i)) * Pull * k
        val d = math.sqrt(dx(i) * dx(i) + dy(i) * dy(i)).max(0.01)
        x(i) += dx(i) / d * d.min(t)
        y(i) += dy(i) / d * d.min(t)
    Vector.tabulate(n)(i => Point(x(i), y(i)))

  /** One decimal, from the double's exact binary value, rounded half to even (0.25 -> 0.2). */
  def fixed1(v: Double): String =
    val digits = BigDecimal(math.abs(v)).setScale(1, RoundingMode.HALF_EVEN).toPlainString
    if v < 0 || (v == 0 && 1 / v < 0) then s"-$digits" else digits

  /** No `.0` on a whole number. */
  private def plain(v: Double): String = Util.formatNumber(v)

  private def escapeText(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  def caption(graph: Graph, commit: String): String =
    s"graphify: the marola code graph, ${graph.nodes.length} nodes and ${graph.edges.length} links (marola@${commit.take(7)})"

  def render(
      nodes: Vector[Node],
      links: Vector[Edge],
      pos: Vector[Point],
      caption: String
  ): String =
    val (top, side, legendH) = (56.0, 24.0, 40.0)
    val x0 = pos.foldLeft(Double.MaxValue)((m, p) => m.min(p.x))
    val y0 = pos.foldLeft(Double.MaxValue)((m, p) => m.min(p.y))
    val x1 = pos.foldLeft(Double.MinValue)((m, p) => m.max(p.x))
    val y1 = pos.foldLeft(Double.MinValue)((m, p) => m.max(p.y))
    val s =
      ((W - 2.0 * side) / (x1 - x0).max(1.0)).min((H - top - legendH - side) / (y1 - y0).max(1.0))
    val ox = side + ((W - 2.0 * side) - (x1 - x0) * s) / 2.0
    def px(p: Point) = Point((p.x - x0) * s + ox, (p.y - y0) * s + top)

    val (w, h) = (plain(W), plain(H))
    val head = Vector(
      s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h" font-family="Inter, Helvetica, Arial, sans-serif">""",
      s"<title>${escapeText(caption)}</title>",
      s"""<rect width="$w" height="$h" fill="#0F1117"/>""",
      s"""<text x="${plain(
          side
        )}" y="34" fill="#E6E6E6" font-size="20" font-weight="600">${escapeText(caption)}</text>"""
    )
    val lines = links.map { case Edge(a, b) =>
      val (pa, pb) = (px(pos(a)), px(pos(b)))
      s"""<line x1="${fixed1(pa.x)}" y1="${fixed1(pa.y)}" x2="${fixed1(pb.x)}" y2="${fixed1(
          pb.y
        )}"/>"""
    }
    val edges =
      """<g stroke="#8A94A6" stroke-opacity="0.32" stroke-width="0.7">""" +: lines :+ "</g>"
    // The groups go in the order of the repos' names, the legend in the palette's.
    val byRepo = nodes.indices.groupBy(i => nodes(i).repo).toSeq.sortBy(_._1.slug)
    val dots = byRepo.toVector.flatMap { (repo, members) =>
      val circles = members.map { i =>
        val c = px(pos(i))
        s"""<circle cx="${fixed1(c.x)}" cy="${fixed1(c.y)}" r="2.4"/>"""
      }
      s"""<g fill="${repo.colour}">""" +: circles.toVector :+ "</g>"
    }
    val legendY = H - side
    val counts = byRepo.toMap
    val labels = Repo.values.toVector.flatMap { repo =>
      counts.get(repo).map(members => repo -> s"${repo.slug} ${members.length}")
    }
    // Each entry starts where the one before it ended, the sum kept in its original order.
    val starts = labels.scanLeft(side) { case (x, (_, label)) =>
      x + (18.0 + label.getBytes("UTF-8").length.toDouble * 8.2 + 24.0)
    }
    val legend = labels.zip(starts).map { case ((repo, label), legendX) =>
      s"""<circle cx="${fixed1(legendX + 6.0)}" cy="${fixed1(
          legendY - 5.0
        )}" r="6" fill="${repo.colour}"/><text x="${fixed1(legendX + 18.0)}" y="${fixed1(
          legendY
        )}" fill="#C9CED6" font-size="15">$label</text>"""
    }
    (head ++ edges ++ dots ++ legend :+ "</svg>").map(_ + "\n").mkString
