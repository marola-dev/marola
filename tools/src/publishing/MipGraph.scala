package marola.tools.publishing

import marola.tools.shared.Cli
import marola.tools.shared.Util

import java.util.Locale
import mainargs.{arg, main, Flag, ParserForMethods}
import scala.collection.immutable.{SortedMap, SortedSet}

// The MIP dependency graph, from each MIP's machine-readable **Blocked by** row (never the prose
// **Depends on**): the Archify source docs/archify/docs/mip-graph.json and the block in
// docs/MIPs/README.md that shows it. `marola-tools mip-graph [--check] [--parallel A B]`.
object MipGraph:
  val StartMark = "<!-- mip-graph:start -->"
  val EndMark = "<!-- mip-graph:end -->"

  private val TitleRe = raw"(?U)^#\s*MIP-(\d{4}):\s*(.+?)\s*$$".r
  private val RowRe = raw"(?U)^\|\s*\*\*([^*]+)\*\*\s*\|\s*(.*?)\s*\|\s*$$".r
  private val MipNumRe = raw"(?U)\b(\d{4})\b".r
  private val SourcePathRe = raw"`((?:core|local|cli|site|scripts)/[A-Za-z0-9_./-]+)`".r

  private val FigureAlt = "MIP dependency graph: an arrow runs from a MIP to the MIP it blocks"
  private val BandCols = 4
  // The words a figure shows; a status row that starts with none of them (the template's own
  // "Proposed / Accepted / ..." included) is drawn as Draft.
  private val StatusWords =
    Seq("Partially implemented", "Implemented", "Accepted", "Rejected", "Superseded", "Draft")

  private val NoEdgesPlaceholder =
    "_No MIP currently declares a **Blocked by** relationship, so there is nothing to graph yet — add that field to a MIP's metadata table and run `just mip-graph` again._"

  // The title is parsed for the tests' sake: the graph names a MIP by number only.
  case class Mip(num: Int, title: String, status: String, blockedBy: Seq[Int]):
    def nodeId: String = f"M$num%04d"
    def statusWord: String =
      val s = lower(status)
      StatusWords.find(w => s.startsWith(lower(w))).getOrElse("Draft")

  type Mips = SortedMap[Int, Mip]

  private def lower(s: String) = s.toLowerCase(Locale.ROOT)
  private def mipName(num: Int) = f"MIP-$num%04d"

  def parseMip(text: String): Option[Mip] =
    val lines = Util.splitlines(text)
    lines.take(3).collectFirst { case TitleRe(num, title) => (num.toInt, title) }.map {
      (num, title) =>
        var status = "Draft"
        var blockedBy = Seq.empty[Int]
        for case RowRe(rawKey, rawValue) <- lines do
          val key = lower(Util.trimUnicode(rawKey))
          val value = Util.trimUnicode(rawValue)
          if key == "status" then
            // the first clause only: "Implemented, ultrareview-verified..." -> "Implemented"
            val first = Util.trimUnicode(value.split("[,(]", -1).head)
            status = if first.isEmpty then "Draft" else first
          else if key == "blocked by" && lower(value) != "none" then
            blockedBy = MipNumRe.findAllMatchIn(value).map(_.group(1).toInt).toSeq
        Mip(num, title, status, blockedBy)
    }

  /** A file as strict UTF-8: None when it is unreadable or not UTF-8. */
  private def readUtf8(path: os.Path): Option[String] = Util.readUtf8(path).toOption

  private def filesIn(dir: os.Path, wanted: String => Boolean): Seq[os.Path] =
    if os.isDir(dir) then os.list(dir).filter(p => wanted(p.last)).sortBy(_.last) else Nil

  def loadMips(dir: os.Path): Mips =
    val files =
      filesIn(dir, n => n.startsWith("MIP-") && n.endsWith(".md") && !n.endsWith(".tasks.md"))
    SortedMap.from(files.flatMap(readUtf8).flatMap(parseMip).map(m => m.num -> m))

  /** (blocker, blocked) for every Blocked-by row that names a known MIP. */
  private def edges(mips: Mips): SortedSet[(Int, Int)] =
    SortedSet.from(mips.values.flatMap(m => m.blockedBy.filter(mips.contains).map(b => (b, m.num))))

  // Only the MIPs in an edge are drawn: an orphan box says nothing the index table doesn't.
  private def connected(mips: Mips): SortedSet[Int] =
    edges(mips).flatMap((a, b) => Seq(a, b))

  /** The README block: the figure (or the placeholder), then the MIPs left out of it. */
  def renderBlock(mips: Mips): String =
    val nodes = connected(mips)
    if nodes.isEmpty then NoEdgesPlaceholder
    else
      val figure = s"![$FigureAlt](../img/archify/mip-graph.png)"
      val orphans = mips.keys.toSeq.filterNot(nodes.contains).map(mipName)
      if orphans.isEmpty then figure
      else
        s"$figure\n\n_${orphans.length} MIP(s) with no declared Blocked-by relationship, not graphed: ${orphans.mkString(", ")}._"

  def graphBlock(mips: Mips): String = s"$StartMark\n${renderBlock(mips)}\n$EndMark"

  /** The Archify source of the figure, or None when no MIP is blocked. Each connected group is its
    * own column band, layered top-down by its longest blocking chain; bands wrap after BandCols.
    */
  def renderArchify(mips: Mips): Option[String] =
    val edgeSet = edges(mips)
    val nodes = connected(mips)
    if nodes.isEmpty then None
    else
      val layer = scala.collection.mutable.Map.from(nodes.map(_ -> 0))
      for _ <- nodes; (a, b) <- edgeSet do layer(b) = layer(b).max(layer(a) + 1)
      val group = scala.collection.mutable.Map.from(nodes.map(n => n -> n))
      for _ <- nodes; (a, b) <- edgeSet do
        val g = group(a).min(group(b))
        group(a) = g
        group(b) = g
      val groups = nodes.toSeq.groupBy(group).to(SortedMap)

      var col0 = 0
      var row0 = 0
      var bandRows = 0
      val components = Seq.newBuilder[ujson.Value]
      for members <- groups.values do
        val byLayer = members.groupBy(layer).to(SortedMap)
        val width = byLayer.values.map(_.length).max
        if col0 > 0 && col0 + width > BandCols then
          row0 += bandRows
          col0 = 0
          bandRows = 0
        for (l, ns) <- byLayer; (n, i) <- ns.zipWithIndex do
          components += ujson.Obj(
            "id" -> mips(n).nodeId,
            "type" -> "backend",
            "label" -> mipName(n),
            "sublabel" -> mips(n).statusWord,
            "pos" -> ujson.Arr(40 + (col0 + i) * 260, 40 + (row0 + l) * 110),
            "size" -> ujson.Arr(190, 64)
          )
        col0 += width
        bandRows = bandRows.max(byLayer.size)

      val connections = edgeSet.toSeq.zipWithIndex.map { case ((a, b), i) =>
        ujson.Obj("id" -> s"e${i + 1}", "from" -> mips(a).nodeId, "to" -> mips(b).nodeId)
      }
      val doc = ujson.Obj(
        "schema_version" -> 1,
        "diagram_type" -> "architecture",
        "meta" -> ujson.Obj(
          "title" -> FigureAlt,
          "locale" -> "en",
          "quality_profile" -> "standard",
          "legend" -> ujson.Obj("mode" -> "hidden"),
          "output" -> "mip-graph.html"
        ),
        "components" -> ujson.Arr.from(components.result()),
        "connections" -> ujson.Arr.from(connections)
      )
      Some(Util.dumps(doc, indent = Some(2), ensureAscii = false) + "\n")

  /** Every MIP transitively blocking `start` (or blocked by it, when `blockers` is false). */
  def reachable(mips: Mips, start: Int, blockers: Boolean): SortedSet[Int] =
    val seen = scala.collection.mutable.SortedSet.empty[Int]
    var stack = List(start)
    while stack.nonEmpty do
      val current = stack.head
      stack = stack.tail
      mips.get(current).foreach { mip =>
        val neighbours =
          if blockers then mip.blockedBy
          else mips.values.filter(_.blockedBy.contains(current)).map(_.num)
        for n <- neighbours if seen.add(n) do stack = n :: stack
      }
    SortedSet.from(seen)

  private def sourcePaths(path: os.Path): SortedSet[String] =
    SortedSet.from(SourcePathRe.findAllMatchIn(readUtf8(path).getOrElse("")).map(_.group(1)))

  private def firstFile(dir: os.Path, num: Int): Option[os.Path] =
    filesIn(dir, n => n.startsWith(s"${mipName(num)}-") && n.endsWith(".md")).headOption

  def parallelCheck(dir: os.Path, a: Int, b: Int): Int =
    val mips = loadMips(dir)
    Seq(a, b).find(n => !mips.contains(n)) match
      case Some(missing) =>
        println(s"mip_graph --parallel: ${mipName(missing)} not found")
        1
      case None =>
        val conflict = reachable(mips, a, true).contains(b) || reachable(mips, b, true).contains(a)
        def paths(n: Int) = firstFile(dir, n).map(sourcePaths).getOrElse(SortedSet.empty[String])
        val overlap = paths(a).intersect(paths(b))
        println(s"${mipName(a)} vs ${mipName(b)}:")
        println(s"  dependency conflict (Blocked by graph): ${if conflict then "yes" else "no"}")
        if overlap.isEmpty then
          println("  §5 source-path overlap: no (or no backticked source paths found in one/both)")
        else println(s"  §5 source-path overlap: yes — ${overlap.mkString(", ")}")
        if !conflict && overlap.isEmpty then
          println("  -> parallel-safe on both checks")
          0
        else
          println("  -> NOT confirmed parallel-safe")
          1

  /** The README with its graph block replaced, or appended when the markers are missing. */
  def withBlock(text: String, block: String): String =
    val start = text.indexOf(StartMark)
    val end = if start < 0 then -1 else text.indexOf(EndMark, start + StartMark.length)
    if end < 0 then
      val separator = if text.endsWith("\n") then "" else "\n"
      s"$text$separator\n$block\n"
    else text.substring(0, start) + block + text.substring(end + EndMark.length)

  def generate(dir: os.Path, readme: os.Path, figure: os.Path, check: Boolean): Int =
    readUtf8(readme) match
      case None =>
        System.err.println(s"mip_graph: $readme not found")
        1
      case Some(text) =>
        val mips = loadMips(dir)
        val newText = withBlock(text, graphBlock(mips))
        val newFigure = renderArchify(mips)
        val oldFigure = readUtf8(figure)
        if !check then
          Cli.report(write(readme, text, newText, figure, oldFigure, newFigure, mips.size))
        else if newText != text || newFigure != oldFigure then
          println(
            "mip_graph --check: docs/MIPs/README.md's graph block or docs/archify/docs/mip-graph.json is stale or missing — run `just mip-graph` and `just figure`, and commit the result"
          )
          1
        else
          println("mip_graph --check: graph block is current")
          0

  private def write(
      readme: os.Path,
      text: String,
      newText: String,
      figure: os.Path,
      oldFigure: Option[String],
      newFigure: Option[String],
      mipCount: Int
  ): Either[String, Unit] =
    val figureWritten =
      if newFigure == oldFigure then Right(())
      else
        attempt(figure) {
          newFigure match
            case Some(json) => os.write.over(figure, json)
            case None       => java.nio.file.Files.delete(figure.toNIO)
        }.map(_ => println(s"mip_graph: wrote $figure; run just figure for its PNG"))
    figureWritten.flatMap { _ =>
      if newText == text then
        Right(println("mip_graph: graph block already current, nothing written"))
      else
        attempt(readme)(os.write.over(readme, newText))
          .map(_ => println(s"mip_graph: wrote graph block to $readme ($mipCount MIPs)"))
    }

  private def attempt(path: os.Path)(io: => Unit): Either[String, Unit] =
    try Right(io)
    catch case e: java.io.IOException => Left(s"mip_graph: $path: ${Util.osError(e)}")

  @main(doc = "Write the MIP dependency graph into docs/MIPs/README.md")
  def run(
      @arg(doc = "Exit 1 if the checked-in block is stale or missing") check: Flag,
      @arg(doc =
        "Can these two MIPs be worked in parallel? (--parallel MIP_A MIP_B)"
      ) parallel: Seq[Int]
  ): Int =
    val dir = Util.repoRoot / "docs" / "MIPs"
    parallel match
      case Seq() =>
        generate(
          dir,
          dir / "README.md",
          Util.repoRoot / "docs/archify/docs/mip-graph.json",
          check.value
        )
      case Seq(a, b) => parallelCheck(dir, a, b)
      case _         =>
        System.err.println("marola-tools mip-graph: --parallel takes two MIP numbers")
        2

  // `--parallel A B` as mainargs reads it, a repeated flag: `--parallel A --parallel B`.
  private def spreadParallel(args: List[String]): List[String] = args match
    case "--parallel" :: a :: b :: rest if !b.startsWith("-") =>
      "--parallel" :: a :: "--parallel" :: b :: spreadParallel(rest)
    case head :: rest => head :: spreadParallel(rest)
    case Nil          => Nil

  def cli(args: Seq[String]): Int =
    Cli.run(ParserForMethods(this), "mip-graph", spreadParallel(args.toList))
