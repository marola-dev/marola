package marola.tools.publishing

import marola.tools.shared.Cli
import marola.tools.shared.{Invocation, Util}

import mainargs.{arg, main, Flag, ParserForMethods}

// .zenodo.json is the one hand-edited citation file; CITATION.cff and the READMEs' BibTeX, APA and
// ABNT block are generated from it, and --check fails when any of them drifts (MIP-0079).
//
//     marola-tools citation [--check] [add --name "Family, Given" [--orcid ID] [--affiliation TEXT] [--type T | --author]]
object Citation:
  import CitationText.ConceptDoi

  /** The files the command reads and writes, resolved once at the edge. */
  final case class Config(zenodo: os.Path, cff: os.Path, readmes: Seq[os.Path])

  object Config:
    def in(root: os.Path): Config =
      Config(
        root / CitationFiles.Zenodo,
        root / CitationFiles.Cff,
        CitationFiles.Readmes.map(root / _)
      )

  /** Every reason `meta` is not a valid .zenodo.json; empty when it is. */
  def validate(meta: ujson.Value): Vector[String] =
    ZenodoJson.decode(meta).left.getOrElse(Vector.empty)

  def writeJson(path: os.Path, meta: ujson.Value): Unit =
    os.write.over(path, Util.dumps(meta, Some(2), ensureAscii = false) + "\n")

  // The OS's wording (Util.osError), for the two errors a missing or locked file gives.
  private def readText(path: os.Path): Either[String, String] =
    try Right(os.read(path))
    catch
      case _: java.nio.file.NoSuchFileException   => Left("No such file or directory (os error 2)")
      case _: java.nio.file.AccessDeniedException => Left("Permission denied (os error 13)")
      case e: java.io.IOException                 => Left(e.getMessage)

  private def readJson(path: os.Path): Either[String, ujson.Value] =
    readText(path).flatMap { text =>
      try Right(ujson.read(text))
      catch
        // ujson wraps the parse error in a TraceException; the message worth printing is inside.
        case e: upickle.core.TraceVisitor.TraceException =>
          Left(Option(e.getCause).getOrElse(e).getMessage)
        case e: ujson.ParseException => Left(e.getMessage)
    }

  private def fail(message: String): Int =
    System.err.println(s"citation: $message")
    1

  /** Validates the metadata, then writes (or with `check`, compares) the CFF and each README's
    * block.
    */
  def run(config: Config, check: Boolean): Int =
    readJson(config.zenodo) match
      case Left(error) => fail(s"${config.zenodo}: $error")
      case Right(json) =>
        ZenodoJson.decode(json) match
          case Right(meta)  => generate(meta, config, check)
          case Left(errors) =>
            fail(s"${CitationFiles.Zenodo} is invalid:\n  ${errors.mkString("\n  ")}")

  /** Each generated file with the text it should hold, or the README that has no block. */
  private def wanted(meta: ZenodoMetadata, config: Config): Either[String, Seq[(os.Path, String)]] =
    val block = CitationText.renderRefs(meta, ConceptDoi)
    val readmes =
      config.readmes.foldLeft(Right(Vector.empty): Either[String, Vector[(os.Path, String)]]) {
        (acc, readme) =>
          for
            done <- acc
            text <- readText(readme)
              .flatMap(CitationText.splice(_, block))
              .left
              .map(e => s"${readme.last}: $e")
          yield done :+ (readme -> text)
      }
    readmes.map((config.cff -> CitationText.renderCff(meta, ConceptDoi)) +: _)

  private def generate(meta: ZenodoMetadata, config: Config, check: Boolean): Int =
    wanted(meta, config) match
      case Left(error)  => fail(error)
      case Right(files) =>
        val stale = files.filter((path, text) => !readText(path).contains(text))
        if check && stale.nonEmpty then
          fail(s"stale: ${stale.map(_._1.last).mkString(", ")}; run `just citation`")
        else if check then
          System.err.println(s"citation: ${CitationFiles.Zenodo} valid, generated text current")
          0
        else
          stale.foreach { (path, text) =>
            os.write.over(path, text)
            System.err.println(s"citation: wrote ${path.last}")
          }
          0

  private def add(zenodo: os.Path, person: PersonRequest): Either[String, Unit] =
    for
      meta <- readJson(zenodo)
      _ <- ZenodoJson.addPerson(meta, person)
      _ <- validate(meta) match
        case Vector() => Right(())
        case errors   => Left(s"not added:\n  ${errors.mkString("\n  ")}")
    yield writeJson(zenodo, meta)

  /** The whole command: with `person`, add them to .zenodo.json first, then regenerate or check. */
  def main(config: Config, check: Boolean, person: Option[PersonRequest]): Int =
    person.map(add(config.zenodo, _)) match
      case Some(Left(error)) => fail(error)
      case _                 => run(config, check)

  // ---- arguments: `citation [--check] [add ...]` ----------------------------------------------

  class Command(config: => Config, addArgs: Seq[String]):
    @main(doc =
      "Validate .zenodo.json and regenerate CITATION.cff and the READMEs' citation block (MIP-0079)"
    )
    def citation(
        @arg(doc = "Validate only; exit 1 if any generated text is stale") check: Flag
    ): Int =
      if addArgs.isEmpty then main(config, check.value, None)
      else Cli.run(ParserForMethods(AddCommand(config, check.value)), "citation add", addArgs.tail)

  class AddCommand(config: => Config, check: Boolean):
    @main(doc = "Add a contributor (or, with --author, a creator)")
    def add(
        @arg(doc = "\"Family, Given\"") name: String,
        @arg(doc = "Bare iD, 0000-0000-0000-0000") orcid: Option[String] = None,
        affiliation: Option[String] = None,
        @arg(name = "type", doc = "A Zenodo contributor type") contributorType: String =
          ContributorType.Default,
        @arg(doc = "List as a creator (cited author)") author: Flag
    ): Int =
      ContributorType.parse(contributorType) match
        case Right(kind) =>
          val role = if author.value then Role.Creator else Role.Contributor(kind)
          main(config, check, Some(PersonRequest(name, orcid, affiliation, role)))
        case Left(_) =>
          System.err.println(
            s"marola-tools citation add: invalid value '$contributorType' for '--type'" +
              s" [possible values: ${ContributorType.All.mkString(", ")}]"
          )
          2

  def cli(args: Seq[String]): Int =
    val (citationArgs, addArgs) = args.span(_ != "add")
    Cli.run(
      ParserForMethods(Command(Config.in(Invocation.current().repoRoot), addArgs)),
      "citation",
      citationArgs
    )
