package marola.tools.routing

import marola.tools.shared.Cli
import marola.tools.shared.Util

import java.nio.file.{Files, Paths}
import mainargs.{arg, main, ParserForMethods}

// The README's graphify figure: the routing-bundle graph.json (MIP-0084) drawn as one SVG, a dot
// per node and a line per link, coloured by repo. `marola-tools graph-svg --graph graph.json`.
object GraphSvg:
  private def str(v: ujson.Value, key: String): Option[String] =
    v.objOpt.flatMap(_.get(key)).flatMap(_.strOpt)

  private def array(v: ujson.Value, key: String): Seq[ujson.Value] =
    v.objOpt.flatMap(_.get(key)).flatMap(_.arrOpt).map(_.toSeq).getOrElse(Nil)

  def load(graph: ujson.Value, keep: String => Boolean): Graph =
    val nodes = array(graph, "nodes").map(n =>
      RawNode(str(n, "id").getOrElse(""), str(n, "source_file").getOrElse(""))
    )
    val links = array(graph, "links").map(l => RawLink(str(l, "source"), str(l, "target")))
    Graph.of(nodes, links, keep)

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
    (Repo.ofSource(file) != Repo.Umbrella && emptyDir(rootPath.resolve(file.split("/", -1).head)))

  def draw(graphFile: String, outFile: String, root: String): Either[String, Unit] =
    val io = (e: java.io.IOException) => Util.osError(e)
    for
      text <- attempt(io)(os.read(os.Path(graphFile, os.pwd)))
      graph <-
        try Right(ujson.read(text, trace = false))
        catch case e: ujson.ParsingFailedException => Left(e.getMessage)
      g = load(graph, keeper(root))
      caption = Figure.caption(g, str(graph, "built_at_commit").getOrElse(""))
      svg = Figure.render(g.nodes, g.edges, Figure.layout(g.nodes, g.edges), caption)
      _ <- attempt(io)(os.write.over(os.Path(outFile, os.pwd), svg))
    yield println(s"graph-svg: $outFile (${g.nodes.length} nodes, ${g.edges.length} links)")

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
