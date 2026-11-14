import java.math.{BigDecimal, RoundingMode}
import java.nio.file.{Files, Paths}
import mainargs.{arg, main, ParserForMethods}
import scala.collection.immutable.SortedMap

// The README's graphify figure: the routing-bundle graph.json (MIP-0084) drawn as one SVG, a dot
// per node and a line per link, coloured by repo. `marola-tools graph-svg --graph graph.json`.
// The layout is Fruchterman–Reingold pulled toward each repo's spot on a ring, seeded by a hash
// of each node id, so the same graph.json always draws the same bytes.
object GraphSvg:
  private val W = 1600.0
  private val H = 1000.0
  private val Iterations = 300
  private val Pull = 0.02

  /** Each repo's dot colour, in the order of the legend; anything else is grey. */
  val Repos: Seq[(String, String)] = Seq(
    "marola-app" -> "#DC322F",
    "marola-site" -> "#4CC9F0",
    "marola-ml" -> "#F2C14E",
    "marola" -> "#DEA584",
    "marola-research" -> "#B388FF",
    "marola-skills" -> "#7BD389",
    "marola-agents" -> "#F78FB3",
    "marola-oods" -> "#2EC4B6",
    "marola-corpus" -> "#FF8C42"
  )

  case class Node(id: String, repo: String)

  /** The repo a source file belongs to: its submodule, or the umbrella. */
  def repoOf(sourceFile: String): String =
    val top = sourceFile.split("/", -1).head
    Repos.map(_._1).find(_ == top).getOrElse("marola")

  private def fnv(s: String): Long =
    s.getBytes("UTF-8").foldLeft(0xcbf29ce484222325L)((h, b) => (h ^ (b & 0xffL)) * 0x100000001b3L)

  private def unit(h: Long): Double = (h >>> 11).toDouble / (1L << 53).toDouble

  private def str(v: ujson.Value, key: String): Option[String] =
    v.objOpt.flatMap(_.get(key)).flatMap(_.strOpt)

  private def array(v: ujson.Value, key: String): Seq[ujson.Value] =
    v.objOpt.flatMap(_.get(key)).flatMap(_.arrOpt).map(_.toSeq).getOrElse(Nil)

  /** The nodes (sorted by id, first of each id kept) and the links between them, as index pairs. */
  def load(graph: ujson.Value, keep: String => Boolean): (Vector[Node], Vector[(Int, Int)]) =
    val kept = array(graph, "nodes").flatMap { n =>
      val file = str(n, "source_file").getOrElse("")
      Option.when(keep(file))(Node(str(n, "id").getOrElse(""), repoOf(file)))
    }
    val nodes = kept.sortBy(_.id).foldLeft(Vector.empty[Node]) { (acc, n) =>
      if acc.lastOption.exists(_.id == n.id) then acc else acc :+ n
    }
    val index = nodes.map(_.id).zipWithIndex.toMap
    val links = array(graph, "links").flatMap { l =>
      for
        a <- str(l, "source").flatMap(index.get)
        b <- str(l, "target").flatMap(index.get)
        if a != b
      yield (a.min(b), a.max(b))
    }
    (nodes, links.distinct.sorted.toVector)

  private def anchor(repo: String): (Double, Double) =
    val i = Repos.indexWhere(_._1 == repo) match
      case -1 => Repos.length
      case n  => n
    val a = i.toDouble / Repos.length.toDouble * (2 * math.Pi)
    (W / 2.0 + W * 0.3 * math.cos(a), H / 2.0 + H * 0.3 * math.sin(a))

  // Trap: keep every operation in its order. Floating point is not associative, so a reordered sum
  // moves a coordinate's last digit, and the published SVG is compared byte for byte.
  def layout(nodes: Vector[Node], links: Vector[(Int, Int)]): Vector[(Double, Double)] =
    val n = nodes.length
    val k = math.sqrt(W * H / n.max(1).toDouble) * 0.6
    val x = Array.ofDim[Double](n)
    val y = Array.ofDim[Double](n)
    for (node, i) <- nodes.zipWithIndex do
      val (ax, ay) = anchor(node.repo)
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
      for (a, b) <- links do
        val (ddx, ddy) = (x(a) - x(b), y(a) - y(b))
        val f = math.sqrt(ddx * ddx + ddy * ddy).max(0.1) / k
        dx(a) -= ddx * f
        dy(a) -= ddy * f
        dx(b) += ddx * f
        dy(b) += ddy * f
      for (node, i) <- nodes.zipWithIndex do
        val (ax, ay) = anchor(node.repo)
        dx(i) += (ax - x(i)) * Pull * k
        dy(i) += (ay - y(i)) * Pull * k
        val d = math.sqrt(dx(i) * dx(i) + dy(i) * dy(i)).max(0.01)
        x(i) += dx(i) / d * d.min(t)
        y(i) += dy(i) / d * d.min(t)
    Vector.tabulate(n)(i => (x(i), y(i)))

  /** One decimal, from the double's exact binary value, rounded half to even (0.25 -> 0.2). */
  def fixed1(v: Double): String =
    val digits = BigDecimal(math.abs(v)).setScale(1, RoundingMode.HALF_EVEN).toPlainString
    if v < 0 || (v == 0 && 1 / v < 0) then s"-$digits" else digits

  /** No `.0` on a whole number. */
  private def plain(v: Double): String = Util.formatNumber(v)

  private def escapeText(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  def render(
      nodes: Vector[Node],
      links: Vector[(Int, Int)],
      pos: Vector[(Double, Double)],
      caption: String
  ): String =
    val (top, side, legendH) = (56.0, 24.0, 40.0)
    val x0 = pos.foldLeft(Double.MaxValue)((m, p) => m.min(p._1))
    val y0 = pos.foldLeft(Double.MaxValue)((m, p) => m.min(p._2))
    val x1 = pos.foldLeft(Double.MinValue)((m, p) => m.max(p._1))
    val y1 = pos.foldLeft(Double.MinValue)((m, p) => m.max(p._2))
    val s =
      ((W - 2.0 * side) / (x1 - x0).max(1.0)).min((H - top - legendH - side) / (y1 - y0).max(1.0))
    val ox = side + ((W - 2.0 * side) - (x1 - x0) * s) / 2.0
    def px(p: (Double, Double)) = ((p._1 - x0) * s + ox, (p._2 - y0) * s + top)
    def colour(repo: String) = Repos.find(_._1 == repo).map(_._2).getOrElse("#888888")

    val out = StringBuilder()
    def line(text: String): Unit =
      out ++= text
      out += '\n'
    val (w, h) = (plain(W), plain(H))
    line(
      s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h" font-family="Inter, Helvetica, Arial, sans-serif">"""
    )
    line(s"<title>${escapeText(caption)}</title>")
    line(s"""<rect width="$w" height="$h" fill="#0F1117"/>""")
    line(s"""<text x="${plain(
        side
      )}" y="34" fill="#E6E6E6" font-size="20" font-weight="600">${escapeText(caption)}</text>""")
    line("""<g stroke="#8A94A6" stroke-opacity="0.32" stroke-width="0.7">""")
    for (a, b) <- links do
      val ((xa, ya), (xb, yb)) = (px(pos(a)), px(pos(b)))
      line(
        s"""<line x1="${fixed1(xa)}" y1="${fixed1(ya)}" x2="${fixed1(xb)}" y2="${fixed1(yb)}"/>"""
      )
    line("</g>")
    val byRepo = nodes.indices.groupBy(i => nodes(i).repo).to(SortedMap)
    for (repo, members) <- byRepo do
      line(s"""<g fill="${colour(repo)}">""")
      for i <- members do
        val (cx, cy) = px(pos(i))
        line(s"""<circle cx="${fixed1(cx)}" cy="${fixed1(cy)}" r="2.4"/>""")
      line("</g>")
    var legendX = side
    val legendY = H - side
    for (repo, c) <- Repos; members <- byRepo.get(repo) do
      val label = s"$repo ${members.length}"
      line(
        s"""<circle cx="${fixed1(legendX + 6.0)}" cy="${fixed1(
            legendY - 5.0
          )}" r="6" fill="$c"/><text x="${fixed1(legendX + 18.0)}" y="${fixed1(
            legendY
          )}" fill="#C9CED6" font-size="15">$label</text>"""
      )
      legendX += 18.0 + label.getBytes("UTF-8").length.toDouble * 8.2 + 24.0
    line("</svg>")
    out.toString

  // A node whose source file is gone is left out, unless its repo is a submodule that is not
  // checked out (an empty directory), where nothing can be checked.
  private def keeper(root: String)(file: String): Boolean =
    val rootPath = Paths.get(root)
    def emptyDir(dir: java.nio.file.Path) =
      try
        val entries = Files.list(dir)
        try entries.findAny().isEmpty
        finally entries.close()
      catch case _: java.io.IOException => false
    file.isEmpty || Files.exists(rootPath.resolve(file)) ||
    (repoOf(file) != "marola" && emptyDir(rootPath.resolve(file.split("/", -1).head)))

  def draw(graphFile: String, outFile: String, root: String): Either[String, Unit] =
    val io = (e: java.io.IOException) => Util.osError(e)
    for
      text <- attempt(io)(os.read(os.Path(graphFile, os.pwd)))
      graph <-
        try Right(ujson.read(text, trace = false))
        catch case e: ujson.ParsingFailedException => Left(e.getMessage)
      (nodes, links) = load(graph, keeper(root))
      commit = str(graph, "built_at_commit").getOrElse("")
      caption =
        s"graphify: the marola code graph, ${nodes.length} nodes and ${links.length} links (marola@${commit.take(7)})"
      _ <- attempt(io)(
        os.write.over(os.Path(outFile, os.pwd), render(nodes, links, layout(nodes, links), caption))
      )
    yield println(s"graph-svg: $outFile (${nodes.length} nodes, ${links.length} links)")

  private def attempt[A](message: java.io.IOException => String)(io: => A): Either[String, A] =
    try Right(io)
    catch case e: java.io.IOException => Left(message(e))

  @main(doc = "Draw the routing-bundle graph.json as the README's graphify figure (an SVG)")
  def run(
      graph: String,
      out: String = "docs/img/graphify.svg",
      @arg(doc = "Checkout the source files are looked up in") root: String = "."
  ): Int = Cli.report(draw(graph, out, root))

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "graph-svg", args)
