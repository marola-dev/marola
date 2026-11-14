package marola.tools.publishing

import marola.tools.shared.Cli
import marola.tools.shared.{Invocation, Util}

import java.util.Locale
import mainargs.{arg, main, Flag, ParserForMethods}
import scala.collection.immutable.{SortedMap, SortedSet}

// The MIP dependency graph, from each MIP's machine-readable **Blocked by** row (never the prose
// **Depends on**): the Archify source docs/archify/docs/mip-graph.json and the block in
// docs/MIPs/README.md that shows it. `marola-tools mip-graph [--check] [--parallel A B]`.
object MipGraph:
  private val TitleRe = raw"(?U)^#\s*MIP-(\d{4}):\s*(.+?)\s*$$".r
  private val RowRe = raw"(?U)^\|\s*\*\*([^*]+)\*\*\s*\|\s*(.*?)\s*\|\s*$$".r
  private val MipNumRe = raw"(?U)\b(\d{4})\b".r
  private val SourcePathRe = raw"`((?:core|local|cli|site|scripts)/[A-Za-z0-9_./-]+)`".r
  private val StatusRow = "status"
  private val BlockedByRow = "blocked by"
  private val NoBlocker = "none"
  private val MipPrefix = "MIP-"
  private val Markdown = ".md"
  private val TasksFile = ".tasks.md"

  /** The files the command reads and writes, resolved once at the edge. */
  final case class Config(dir: os.Path, readme: os.Path, figure: os.Path)

  object Config:
    def in(root: os.Path): Config =
      val dir = root / os.RelPath(MipReadme.MipsDir)
      Config(dir, dir / MipReadme.Readme, root / os.RelPath(MipReadme.Figure))

  private def lower(s: String) = s.toLowerCase(Locale.ROOT)

  def parseMip(text: String): Option[Mip] =
    val lines = Util.splitlines(text)
    val rows = lines.collect { case RowRe(key, value) =>
      lower(Util.trimUnicode(key)) -> Util.trimUnicode(value)
    }
    // A later row of the same key wins.
    val status = rows.collect { case (StatusRow, value) => statusOf(value) }.lastOption
    val blockedBy = rows.collect {
      case (BlockedByRow, value) if lower(value) != NoBlocker =>
        MipNumRe.findAllMatchIn(value).map(_.group(1).toInt).toSeq
    }.lastOption
    lines.take(3).collectFirst { case TitleRe(num, title) =>
      Mip(num.toInt, title, status.getOrElse(MipStatus.Draft), blockedBy.getOrElse(Nil))
    }

  // The first clause only: "Implemented, ultrareview-verified..." -> "Implemented".
  private def statusOf(value: String): MipStatus =
    val first = Util.trimUnicode(value.split("[,(]", -1).head)
    if first.isEmpty then MipStatus.Draft else MipStatus.parse(first)

  /** A file as strict UTF-8: None when it is unreadable or not UTF-8. */
  private def readUtf8(path: os.Path): Option[String] = Util.readUtf8(path).toOption

  private def filesIn(dir: os.Path, wanted: String => Boolean): Seq[os.Path] =
    if os.isDir(dir) then os.list(dir).filter(p => wanted(p.last)).sortBy(_.last) else Nil

  def loadMips(dir: os.Path): Mips =
    val files =
      filesIn(dir, n => n.startsWith(MipPrefix) && n.endsWith(Markdown) && !n.endsWith(TasksFile))
    SortedMap.from(files.flatMap(readUtf8).flatMap(parseMip).map(m => m.num -> m))

  /** The Archify source of the figure, or None when no MIP is blocked. */
  def renderArchify(mips: Mips): Option[String] =
    val placed = MipDependencies.layout(mips)
    if placed.isEmpty then None
    else
      val components = placed.map { node =>
        ujson.Obj(
          "id" -> node.mip.nodeId,
          "type" -> "backend",
          "label" -> node.mip.name,
          "sublabel" -> node.mip.statusWord,
          "pos" -> ujson.Arr(node.x, node.y),
          "size" -> ujson.Arr(190, 64)
        )
      }
      val connections = MipDependencies.edges(mips).toSeq.zipWithIndex.map { (edge, i) =>
        ujson.Obj(
          "id" -> s"e${i + 1}",
          "from" -> mips(edge.blocker).nodeId,
          "to" -> mips(edge.blocked).nodeId
        )
      }
      val doc = ujson.Obj(
        "schema_version" -> 1,
        "diagram_type" -> "architecture",
        "meta" -> ujson.Obj(
          "title" -> MipReadme.FigureAlt,
          "locale" -> "en",
          "quality_profile" -> "standard",
          "legend" -> ujson.Obj("mode" -> "hidden"),
          "output" -> MipReadme.FigureHtml
        ),
        "components" -> ujson.Arr.from(components),
        "connections" -> ujson.Arr.from(connections)
      )
      Some(Util.dumps(doc, indent = Some(2), ensureAscii = false) + "\n")

  private def sourcePaths(path: os.Path): SortedSet[String] =
    SortedSet.from(SourcePathRe.findAllMatchIn(readUtf8(path).getOrElse("")).map(_.group(1)))

  private def firstFile(dir: os.Path, num: Int): Option[os.Path] =
    filesIn(dir, n => n.startsWith(s"${Mip.name(num)}-") && n.endsWith(Markdown)).headOption

  def parallelCheck(dir: os.Path, a: Int, b: Int): Int =
    val mips = loadMips(dir)
    Seq(a, b).find(n => !mips.contains(n)) match
      case Some(missing) =>
        println(s"mip_graph --parallel: ${Mip.name(missing)} not found")
        1
      case None =>
        def paths(n: Int) = firstFile(dir, n).map(sourcePaths).getOrElse(SortedSet.empty[String])
        val verdict = ParallelVerdict.of(mips, a, b, paths(a), paths(b))
        verdict.lines.foreach(println)
        verdict.exitCode

  def generate(config: Config, check: Boolean): Int =
    val Config(dir, readme, figure) = config
    readUtf8(readme) match
      case None =>
        System.err.println(s"mip_graph: $readme not found")
        1
      case Some(text) =>
        val mips = loadMips(dir)
        val newText = MipReadme.withBlock(text, MipReadme.graphBlock(mips))
        val newFigure = renderArchify(mips)
        val oldFigure = readUtf8(figure)
        if !check then
          Cli.report(write(readme, text, newText, figure, oldFigure, newFigure, mips.size))
        else if newText != text || newFigure != oldFigure then
          println(
            s"mip_graph --check: ${MipReadme.MipsDir}/${MipReadme.Readme}'s graph block or ${MipReadme.Figure} is stale or missing — run `just mip-graph` and `just figure`, and commit the result"
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
    val config = Config.in(Invocation.current().repoRoot)
    parallel match
      case Seq()     => generate(config, check.value)
      case Seq(a, b) => parallelCheck(config.dir, a, b)
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
