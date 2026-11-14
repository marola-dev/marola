package marola.tools.routing

import marola.tools.shared.{Cli, Invocation, Util}

import java.nio.file.Files
import mainargs.{arg, main, ParserForMethods}

// The README's graphify figure: the routing-bundle graph.json (MIP-0084) drawn as one SVG, a dot
// per node and a line per link, coloured by repo. `marola-tools graph-svg --graph graph.json`.
object GraphSvg:
  /** Where the README shows it. */
  val DefaultOut = "docs/img/graphify.svg"

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
  private def keeper(root: os.Path)(file: String): Boolean =
    val rootPath = root.toNIO
    def emptyDir(dir: java.nio.file.Path) =
      try
        val entries = Files.list(dir)
        try entries.findAny().isEmpty
        finally entries.close()
      catch case _: java.io.IOException => false
    file.isEmpty || Files.exists(rootPath.resolve(file)) ||
    (Repo.ofSource(file) != Repo.Umbrella && emptyDir(rootPath.resolve(file.split("/", -1).head)))

  /** Draw `graphFile` into `outFile`, both and the checkout `root` as given against `cwd`. */
  def draw(graphFile: String, outFile: String, root: String, cwd: os.Path): Either[String, Unit] =
    val io = (e: java.io.IOException) => Util.osError(e)
    for
      text <- attempt(io)(os.read(os.Path(graphFile, cwd)))
      graph <-
        try Right(ujson.read(text, trace = false))
        catch case e: ujson.ParsingFailedException => Left(e.getMessage)
      g = load(graph, keeper(os.Path(root, cwd)))
      caption = Figure.caption(g, str(graph, "built_at_commit").getOrElse(""))
      svg = Figure.render(g.nodes, g.edges, Figure.layout(g.nodes, g.edges), caption)
      _ <- attempt(io)(os.write.over(os.Path(outFile, cwd), svg))
    yield println(s"graph-svg: $outFile (${g.nodes.length} nodes, ${g.edges.length} links)")

  private def attempt[A](message: java.io.IOException => String)(io: => A): Either[String, A] =
    try Right(io)
    catch case e: java.io.IOException => Left(message(e))

  @main(doc = "Draw the routing-bundle graph.json as the README's graphify figure (an SVG)")
  def run(
      graph: String,
      out: String = DefaultOut,
      @arg(doc = "Checkout the source files are looked up in") root: String = "."
  ): Int = Cli.report(draw(graph, out, root, Invocation.current().cwd))

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "graph-svg", args)
