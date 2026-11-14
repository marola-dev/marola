package marola.tools.routing

import marola.tools.shared.Cli
import marola.tools.shared.Util

import java.nio.file.{Files, StandardCopyOption}
import mainargs.{arg, main, ParserForMethods}
import Util.field

// Assemble the files ci.yml's graph job publishes to `routing-bundle` (MIP-0084 §5.3): DIR's graph,
// wiring and indexes, build.json with the bench score, manifest.json and routing-digest.md. Exit 1,
// writing nothing, when a question type scores below --previous's (MIP-0085 §5.1).
object RoutingBundle:
  val Schema = Manifest.Schema
  private val MaxBytes = 20L << 20 // route refuses a bundle file over this
  private val WarnBytes = 10L << 20 // §5.3: past this the large files move out of refs/heads/
  val DigestChars = Digest.MaxChars
  val Start = WiringBlock.Start
  val End = WiringBlock.End
  // MIP-0085's indexes: route says a missing one is missing, so each is published only when built.
  private val Indexes = Seq("doc_code.json", "decisions.json", "meaning.json")

  // (?d): `^` and `$` see only \n, never a \r; (?U): \s and \d are Unicode.
  private val TotalLine = raw"(?mdU)^right first: \d+/\d+$$".r
  private val VerdictLine = raw"(?mdU)^(right|MISS)\s+(\S+)\s".r

  extension (g: Given) private def path: os.Path = os.Path(g.shown, os.pwd)

  // ---- reading JSON loosely: a missing key or a wrong type is absent ---------------------------

  private def nonEmptyStr(v: Option[ujson.Value]): Option[String] =
    v.flatMap(_.strOpt).filter(_.nonEmpty)

  /** A whole, non-negative number, else 0. */
  private def asCount(v: Option[ujson.Value]): Long =
    v.flatMap(_.numOpt).filter(n => n.isWhole && n >= 0).map(_.toLong).getOrElse(0L)

  /** A question id as Python's `str()` prints a JSON value: None, True, 2, or the string bare. */
  private def pyStr(v: Option[ujson.Value]): String = v match
    case None | Some(ujson.Null) => "None"
    case Some(ujson.Str(s))      => s
    case Some(ujson.Bool(b))     => if b then "True" else "False"
    case Some(ujson.Num(n))      => Util.formatNumber(n)
    case Some(other)             => other.render()

  private def parseJson(text: String): Either[String, ujson.Value] =
    try Right(ujson.read(text, trace = false))
    catch case e: ujson.ParsingFailedException => Left(e.getMessage)

  private def readJson(path: os.Path, shown: String): Either[String, ujson.Value] =
    Util.readUtf8(path).flatMap(parseJson).left.map(e => s"routing_bundle: $shown: $e")

  private def questionsOf(file: ujson.Value): Seq[Question] =
    val list = file match
      case ujson.Obj(o) => o.get("questions").flatMap(_.arrOpt).map(_.toSeq).getOrElse(Nil)
      case ujson.Arr(a) => a.toSeq
      case _            => Nil
    list.map { q =>
      val declared = nonEmptyStr(field(q, "type"))
      Question(pyStr(field(q, "id")), QuestionType.of(declared, field(q, "via").flatMap(_.strOpt)))
    }

  private def benchLines(bench: String): Seq[BenchLine] =
    VerdictLine
      .findAllMatchIn(bench)
      .filterNot(_.group(2).startsWith("first:"))
      .flatMap(m => Verdict.parse(m.group(1)).map(BenchLine(_, m.group(2))))
      .toSeq

  /** build.json's `bench`; null or absent is a score with no types and nothing right. */
  private def publishedBench(build: ujson.Value): PublishedBench =
    val bench = field(build, "bench").filter(_ != ujson.Null).getOrElse(ujson.Obj())
    PublishedBench(
      asCount(field(bench, "right")),
      field(bench, "by_type")
        .flatMap(_.objOpt)
        .map(_.toSeq.map((t, p) => t -> asCount(field(p, "right"))))
    )

  // u64's parse of `route` sees a false, a null or a 0 as no location.
  private def locationText(location: Option[ujson.Value]): String = location match
    case None | Some(ujson.Null) | Some(ujson.Bool(false)) => ""
    case Some(ujson.Num(n)) if n == 0                      => ""
    case other                                             => pyStr(other)

  private def digestNodes(graph: ujson.Value): Seq[DigestNode] =
    field(graph, "nodes").flatMap(_.arrOpt).map(_.toSeq).getOrElse(Nil).map { n =>
      DigestNode(
        nonEmptyStr(field(n, "source_file")).getOrElse(""),
        nonEmptyStr(field(n, "label")).getOrElse(""),
        field(n, "file_type").forall(_.strOpt.contains("code")),
        DigestNode.line(locationText(field(n, "source_location")))
      )
    }

  // ---- the score ------------------------------------------------------------------------------

  /** `route bench`'s output: the total and, by each question's type, right first of total. */
  def scores(bench: String, questions: os.Path, shown: String): Either[String, Bench] =
    if TotalLine.findFirstIn(bench).isEmpty then
      Left("routing_bundle: no 'right first: N/T' line in the bench output")
    else
      val parsed =
        if !os.isFile(questions) then Right(ujson.Arr())
        else
          Util
            .readUtf8(questions)
            .flatMap(t => parseJson(t).left.map(e => s"routing_bundle: $shown: $e"))
      parsed.map(qs => Bench.tally(benchLines(bench), questionsOf(qs)))

  // ---- the digest -----------------------------------------------------------------------------

  def digest(graph: ujson.Value, wiringBlock: String): String =
    Digest.render(digestNodes(graph), wiringBlock)

  def wiringBlock(reposMd: os.Path): String =
    WiringBlock.extract(if os.isFile(reposMd) then Util.readUtf8(reposMd).getOrElse("") else "")

  // ---- assembling -----------------------------------------------------------------------------

  /** The exit code: 0 with the bundle written, 1 with it kept; Left is a fatal problem. */
  def assemble(args: Args): Either[String, Int] =
    for
      benchText <- Util
        .readUtf8(args.bench.path)
        .left
        .map(e => s"routing_bundle: ${args.bench.shown}: $e")
      bench <- scores(benchText, args.questions.path, args.questions.shown)
      lower <- args.previous.filter(p => os.isFile(p.path)) match
        case None           => Right(Nil)
        case Some(previous) =>
          readJson(previous.path, previous.shown).map(prev =>
            Drop.between(bench, publishedBench(prev))
          )
      code <-
        if lower.nonEmpty then
          println(
            s"routing_bundle: bench below the published one (${lower.map(_.render).mkString(", ")}); keeping it"
          )
          Right(1)
        else write(args, bench).map(_ => 0)
    yield code

  private def write(args: Args, bench: Bench): Either[String, Unit] =
    val src = args.graphDir
    val out = args.out.path
    val indexes = Indexes.filter(n => os.isFile((src / n).path))
    val io = (e: java.io.IOException) => s"routing_bundle: ${Util.osError(e)}"
    for
      _ <- (Seq("graph.json", "wiring.json", "build.json") ++ indexes)
        .foldLeft[Either[String, Unit]](Right(())) { (soFar, name) =>
          soFar.flatMap(_ => checkSize(src / name, name))
        }
      _ <- attempt(io) {
        os.makeDir.all(out)
        for name <- Seq("graph.json", "wiring.json") ++ indexes do
          Files.copy(
            (src / name).path.toNIO,
            (out / name).toNIO,
            StandardCopyOption.REPLACE_EXISTING
          )
      }
      build <- readJson((src / "build.json").path, (src / "build.json").shown)
      stamped = BuildInfo(build).stamped(args.graphify, bench)
      _ <- attempt(io)(
        os.write.over(out / "build.json", Util.dumps(stamped.json, Some(1)) + "\n")
      )
      graph <- readJson((src / "graph.json").path, (src / "graph.json").shown)
      _ <- attempt(io)(
        os.write.over(out / "routing-digest.md", digest(graph, wiringBlock(args.reposMd.path)))
      )
      sizes <- attempt(io) {
        os.list(out)
          .sortBy(_.last)
          .filter(p => os.isFile(p) && p.last != "manifest.json")
          .map(p => FileSize(p.last, os.size(p)))
      }
      manifest = Manifest(sizes).toJson
      _ <- attempt(io)(os.write.over(out / "manifest.json", Util.dumps(manifest, Some(1)) + "\n"))
    yield println(
      s"routing_bundle: bench ${bench.right}/${bench.total}, ${sizes.length + 1} files in ${args.out.shown}"
    )

  private def checkSize(file: Given, name: String): Either[String, Unit] =
    if !os.isFile(file.path) then Left(s"routing_bundle: ${file.shown} is missing")
    else
      val size = os.size(file.path)
      if size > MaxBytes then
        Left(s"routing_bundle: $name is over ${MaxBytes >> 20} MB; route would refuse it")
      else
        if size > WarnBytes then
          println(
            s"::warning::$name is over ${WarnBytes >> 20} MB: time to move it out of refs/heads/ (§5.3)"
          )
        Right(())

  private def attempt[A](message: java.io.IOException => String)(io: => A): Either[String, A] =
    try Right(io)
    catch case e: java.io.IOException => Left(message(e))

  @main(doc = "Assemble the files ci.yml's graph job publishes to `routing-bundle` (MIP-0084 §5.3)")
  def run(
      graphDir: String,
      out: String,
      @arg(doc = "route bench's output, a file") bench: String,
      questions: String = "routing-bench.yaml",
      previous: Option[String] = None,
      graphify: String = "",
      reposMd: String = "docs/2-Building-marola/REPOS.md"
  ): Int =
    val args = Args(
      Given(graphDir),
      Given(out),
      Given(bench),
      Given(questions),
      previous.map(Given(_)),
      graphify,
      Given(reposMd)
    )
    assemble(args) match
      case Right(code) => code
      case Left(error) =>
        System.err.println(error)
        1

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "routing-bundle", args)
