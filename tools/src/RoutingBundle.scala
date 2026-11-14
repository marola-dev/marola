import java.nio.file.{Files, StandardCopyOption}
import mainargs.{arg, main, ParserForMethods}
import scala.collection.immutable.SortedMap
import Util.field

// Assemble the files ci.yml's graph job publishes to `routing-bundle` (MIP-0084 §5.3): DIR's graph,
// wiring and indexes, build.json with the bench score, manifest.json and routing-digest.md. Exit 1,
// writing nothing, when a question type scores below --previous's (MIP-0085 §5.1).
object RoutingBundle:
  val Schema = 1
  private val MaxBytes = 20L << 20 // route refuses a bundle file over this
  private val WarnBytes = 10L << 20 // §5.3: past this the large files move out of refs/heads/
  val DigestChars = 100_000 // §5.3's 25k-token cap, at about 4 characters a token
  val Start = "<!-- wiring:start -->"
  val End = "<!-- wiring:end -->"
  // MIP-0085's indexes: route says a missing one is missing, so each is published only when built.
  private val Indexes = Seq("doc_code.json", "decisions.json", "meaning.json")

  // (?d): `^` and `$` see only \n, never a \r; (?U): \s and \d are Unicode.
  private val TotalLine = raw"(?mdU)^right first: \d+/\d+$$".r
  private val Verdict = raw"(?mdU)^(right|MISS)\s+(\S+)\s".r

  case class Score(right: Int, total: Int)
  case class Bench(right: Int, total: Int, byType: Seq[(String, Score)]):
    def toJson: ujson.Obj = ujson.Obj(
      "right" -> right,
      "total" -> total,
      "by_type" -> ujson.Obj.from(
        byType.map((t, s) => t -> ujson.Obj("right" -> s.right, "total" -> s.total))
      )
    )

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
      parsed.map { qs =>
        val list = qs match
          case ujson.Obj(o) => o.get("questions").flatMap(_.arrOpt).map(_.toSeq).getOrElse(Nil)
          case ujson.Arr(a) => a.toSeq
          case _            => Nil
        val kind = list.map(q => pyStr(field(q, "id")) -> questionType(q)).toMap
        val byType = scala.collection.mutable.LinkedHashMap.empty[String, Score]
        for m <- Verdict.findAllMatchIn(bench) if !m.group(2).startsWith("first:") do
          val t = kind.getOrElse(m.group(2), "symbol")
          val s = byType.getOrElse(t, Score(0, 0))
          byType(t) = Score(s.right + (if m.group(1) == "right" then 1 else 0), s.total + 1)
        Bench(byType.values.map(_.right).sum, byType.values.map(_.total).sum, byType.toSeq)
      }

  // A question without a type is one of MIP-0084's eight: wiring, or a symbol query.
  private def questionType(q: ujson.Value): String =
    nonEmptyStr(field(q, "type")).getOrElse:
      if field(q, "via").flatMap(_.strOpt).contains("wiring") then "wiring" else "symbol"

  /** The question types (or the total, for a previous score with no types) that scored lower. */
  def drops(current: ujson.Value, previous: ujson.Value): Seq[String] =
    field(previous, "by_type").flatMap(_.objOpt) match
      case None =>
        val previousRight = asCount(field(previous, "right"))
        if asCount(field(current, "right")) >= previousRight then Nil else Seq("total")
      case Some(previousByType) =>
        previousByType.toSeq.sortBy(_._1).flatMap { (t, p) =>
          val now =
            asCount(field(current, "by_type").flatMap(field(_, t)).flatMap(field(_, "right")))
          val before = asCount(field(p, "right"))
          Option.when(now < before)(s"$t $now < $before")
        }

  // ---- the digest -----------------------------------------------------------------------------

  private def repoOf(sourceFile: String): String =
    val top = sourceFile.split("/", -1).head
    if top.startsWith("marola-") && sourceFile.contains('/') then top else "marola"

  private def basename(path: String): String =
    val trimmed = path.reverse.dropWhile(_ == '/').reverse
    trimmed.substring(trimmed.lastIndexOf('/') + 1)

  private def sourceLine(location: Option[ujson.Value]): BigInt =
    val text = location match
      case None | Some(ujson.Null) | Some(ujson.Bool(false)) => ""
      case Some(ujson.Num(n)) if n == 0                      => ""
      case other                                             => pyStr(other)
    val digits = text.filter(c => c >= '0' && c <= '9')
    val line = if digits.isEmpty then BigInt(0) else BigInt(digits)
    if line.bitLength > 64 then 0 else line // u64's parse failed past its range

  private case class Entry(repo: String, source: String, line: BigInt, label: String)

  def digest(graph: ujson.Value, wiringBlock: String): String =
    val nodes = field(graph, "nodes").flatMap(_.arrOpt).map(_.toSeq).getOrElse(Nil)
    val entries = nodes.flatMap { n =>
      val source = nonEmptyStr(field(n, "source_file")).getOrElse("")
      val label = nonEmptyStr(field(n, "label")).getOrElse("")
      val isCode = field(n, "file_type").forall(_.strOpt.contains("code"))
      Option.when(source.nonEmpty && isCode && label != basename(source))(
        Entry(repoOf(source), source, sourceLine(field(n, "source_location")), label)
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
  private def capped(text: String): String =
    if text.codePointCount(0, text.length) <= DigestChars then text
    else
      val head = text.substring(0, text.offsetByCodePoints(0, DigestChars))
      val cut = head.lastIndexOf('\n').max(0)
      s"${text.substring(0, cut)}\n\n_Cut at the 25k-token cap: `route query` has the rest._"

  def wiringBlock(reposMd: os.Path): String =
    val text = if os.isFile(reposMd) then Util.readUtf8(reposMd).getOrElse("") else ""
    val start = text.indexOf(Start)
    val rest = if start < 0 then "" else text.substring(start + Start.length)
    rest.indexOf(End) match
      case -1  => "_No wiring block found._"
      case end => rest.substring(0, end)

  // ---- assembling -----------------------------------------------------------------------------

  /** A path as the caller wrote it, for messages, and where it is. */
  case class Given(shown: String):
    val path: os.Path = os.Path(shown, os.pwd)
    def /(name: String): Given = Given(
      if shown.isEmpty || shown.endsWith("/") then shown + name else s"$shown/$name"
    )

  case class Args(
      graphDir: Given,
      out: Given,
      bench: Given,
      questions: Given,
      previous: Option[Given],
      graphify: String,
      reposMd: Given
  )

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
          readJson(previous.path, previous.shown).map { prev =>
            drops(bench.toJson, field(prev, "bench").filter(_ != ujson.Null).getOrElse(ujson.Obj()))
          }
      code <-
        if lower.nonEmpty then
          println(
            s"routing_bundle: bench below the published one (${lower.mkString(", ")}); keeping it"
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
      _ = build.objOpt.foreach { obj =>
        obj("graphify") = args.graphify
        obj("bench") = bench.toJson
      }
      _ <- attempt(io)(os.write.over(out / "build.json", Util.dumps(build, Some(1)) + "\n"))
      graph <- readJson((src / "graph.json").path, (src / "graph.json").shown)
      _ <- attempt(io)(
        os.write.over(out / "routing-digest.md", digest(graph, wiringBlock(args.reposMd.path)))
      )
      sizes <- attempt(io) {
        os.list(out)
          .sortBy(_.last)
          .filter(p => os.isFile(p) && p.last != "manifest.json")
          .map(p => p.last -> os.size(p))
      }
      manifest = ujson.Obj(
        "schema" -> Schema,
        "files" -> ujson.Obj.from(sizes.map((n, s) => n -> ujson.Num(s.toDouble)))
      )
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
