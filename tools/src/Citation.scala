import mainargs.{arg, main, Flag, ParserForMethods}
import Util.field

// .zenodo.json is the one hand-edited citation file; CITATION.cff and the READMEs' BibTeX, APA and
// ABNT block are generated from it, and --check fails when any of them drifts (MIP-0079).
//
//     marola-tools citation [--check] [add --name "Family, Given" [--orcid ID] [--affiliation TEXT] [--type T | --author]]
object Citation:
  val Start = "<!-- citation:start -->"
  val End = "<!-- citation:end -->"

  // Filled in by the PR that follows the first archived release (MIP-0079 §5.5).
  val ConceptDoi = "10.5281/zenodo.23224155"
  private val Homepage = "https://marola.dev"
  private val Year = "2026"

  // developers.zenodo.org, deposit metadata, checked 2026-10-07.
  private val Relations = Set(
    "isCitedBy",
    "cites",
    "isSupplementTo",
    "isSupplementedBy",
    "isContinuedBy",
    "continues",
    "isDescribedBy",
    "describes",
    "hasMetadata",
    "isMetadataFor",
    "isNewVersionOf",
    "isPreviousVersionOf",
    "isPartOf",
    "hasPart",
    "isReferencedBy",
    "references",
    "isDocumentedBy",
    "documents",
    "isCompiledBy",
    "compiles",
    "isVariantFormOf",
    "isOriginalFormof",
    "isIdenticalTo",
    "isAlternateIdentifier",
    "isReviewedBy",
    "reviews",
    "isDerivedFrom",
    "isSourceOf",
    "requires",
    "isRequiredBy",
    "isObsoletedBy",
    "obsoletes"
  )
  val ContributorTypes = Seq(
    "ContactPerson",
    "DataCollector",
    "DataCurator",
    "DataManager",
    "Distributor",
    "Editor",
    "HostingInstitution",
    "Producer",
    "ProjectLeader",
    "ProjectManager",
    "ProjectMember",
    "RegistrationAgency",
    "RegistrationAuthority",
    "RelatedPerson",
    "Researcher",
    "ResearchGroup",
    "RightsHolder",
    "Supervisor",
    "Sponsor",
    "WorkPackageLeader",
    "Other"
  )
  private val Spdx = Map("mit" -> "MIT", "cc-by-4.0" -> "CC-BY-4.0")
  // Zenodo sets these itself (version from the tag, date from the release, DOI minted); a value
  // here would override or break that.
  private val Forbidden = Seq("version", "publication_date", "doi", "prereserve_doi")

  // `(?U)`: \d and \s match any Unicode digit and space, as Java's do not by default.
  private val NameRe = "[^,]+, [^,]+".r
  private val OrcidRe = raw"(?U)\d{4}-\d{4}-\d{4}-\d{3}[\dX]".r
  private val DoiRe = raw"(?U)10\.\d{4,9}/\S+".r
  private val LangRe = "[a-z]{3}".r
  private val Whitespace = raw"(?U)\s+".r

  // ---- reading the JSON loosely: a missing key or a wrong type is just absent -----------------

  private def str(v: ujson.Value, key: String): String = strOpt(v, key).getOrElse("")

  private def strOpt(v: ujson.Value, key: String): Option[String] = field(v, key).collect:
    case ujson.Str(s) => s

  private def list(v: ujson.Value, key: String): Seq[ujson.Value] = field(v, key) match
    case Some(ujson.Arr(items)) => items.toSeq
    case _                      => Seq.empty

  private def truthy(v: Option[ujson.Value]): Boolean = v match
    case None | Some(ujson.Null) => false
    case Some(ujson.Bool(b))     => b
    case Some(ujson.Str(s))      => s.nonEmpty
    case Some(ujson.Arr(a))      => a.nonEmpty
    case Some(ujson.Obj(o))      => o.nonEmpty
    case Some(ujson.Num(n))      => n != 0.0

  // ---- validation -----------------------------------------------------------------------------

  def orcidOk(orcid: String): Boolean =
    OrcidRe.matches(orcid) && {
      val digits = orcid.filter(_ != '-')
      val total = digits.init.foldLeft(0)((sum, ch) => (sum + Character.digit(ch, 10).max(0)) * 2)
      val check = (12 - total % 11) % 11
      val want = if check == 10 then 'X' else ('0' + check).toChar
      digits.last == want
    }

  private def personErrors(person: ujson.Value, at: String): Seq[String] =
    val name = Option.when(!NameRe.matches(str(person, "name")))(
      s"$at: name ${Util.pyrepr(field(person, "name"))} is not 'Family, Given'"
    )
    val orcid = field(person, "orcid")
      .filter(o => !o.strOpt.exists(orcidOk))
      .map(o => s"$at: orcid ${Util.pyrepr(Some(o))} is not a valid bare ORCID iD")
    name.toSeq ++ orcid

  def validate(meta: ujson.Value): Vector[String] =
    val errors = Vector.newBuilder[String]
    for key <- Seq("title", "description", "creators", "license", "keywords", "language") do
      if !truthy(field(meta, key)) then errors += s"missing $key"
    if !Set("software", "dataset").contains(str(meta, "upload_type")) then
      errors += "upload_type must be software or dataset"
    if str(meta, "access_right") != "open" then errors += "access_right must be open"
    if strOpt(meta, "license").flatMap(Spdx.get).isEmpty then
      errors += s"license ${Util.pyrepr(field(meta, "license"))} has no SPDX mapping here"
    if !LangRe.matches(str(meta, "language")) then
      errors += "language must be an ISO 639-3 code such as eng"
    for key <- Forbidden if field(meta, key).isDefined do
      errors += s"$key is set by Zenodo; remove it"
    for (person, i) <- list(meta, "creators").zipWithIndex do
      errors ++= personErrors(person, s"creators[$i]")
    for (person, i) <- list(meta, "contributors").zipWithIndex do
      errors ++= personErrors(person, s"contributors[$i]")
      if !strOpt(person, "type").exists(ContributorTypes.contains) then
        errors += s"contributors[$i]: type ${Util.pyrepr(field(person, "type"))} is not Zenodo's"
    for (related, i) <- list(meta, "related_identifiers").zipWithIndex do
      if !strOpt(related, "relation").exists(Relations.contains) then
        errors += s"related_identifiers[$i]: relation ${Util.pyrepr(field(related, "relation"))} unknown"
      val identifier = str(related, "identifier")
      strOpt(related, "scheme") match
        case Some("doi") if !DoiRe.matches(identifier) =>
          errors += s"related_identifiers[$i]: ${Util.pyreprStr(identifier)} is not a bare DOI"
        case Some("url") if !identifier.startsWith("https://") =>
          errors += s"related_identifiers[$i]: ${Util.pyreprStr(identifier)} is not an https URL"
        case Some("doi" | "url") => ()
        case _                   => errors += s"related_identifiers[$i]: scheme must be doi or url"
    errors.result()

  // ---- rendering ------------------------------------------------------------------------------

  /** A JSON string literal, non-ASCII kept as is. */
  private def quoted(s: String): String = Util.dumps(ujson.Str(s), ensureAscii = false)

  private def words(text: String): Seq[String] = Whitespace.split(text).toSeq.filter(_.nonEmpty)

  private val NamedEntities =
    Map("amp" -> "&", "lt" -> "<", "gt" -> ">", "quot" -> "\"", "apos" -> "'", "nbsp" -> " ")

  /** Numeric entities and the common named ones; any other is left as written. */
  def decodeEntities(text: String): String =
    raw"&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);".r.replaceAllIn(
      text,
      m =>
        val name = m.group(1)
        val decoded =
          if name.startsWith("#x") || name.startsWith("#X") then
            Some(String(Character.toChars(Integer.parseInt(name.drop(2), 16))))
          else if name.startsWith("#") then Some(String(Character.toChars(name.drop(1).toInt)))
          else NamedEntities.get(name)
        java.util.regex.Matcher.quoteReplacement(decoded.getOrElse(m.matched))
    )

  private def plain(description: String): String =
    val paragraphs = raw"(?U)</p>\s*<p>".r.replaceAllIn(description, "\n\n")
    val text = decodeEntities("<[^>]+>".r.replaceAllIn(paragraphs, ""))
    Util.trimUnicode(text)

  private def repoUrl(meta: ujson.Value): String =
    list(meta, "related_identifiers")
      .find(r =>
        str(r, "relation") == "isSupplementTo" && str(r, "identifier").startsWith(
          "https://github.com/"
        )
      )
      .map(str(_, "identifier"))
      .getOrElse(throw IllegalStateException("no GitHub isSupplementTo identifier in .zenodo.json"))

  private def length(s: String): Int = s.codePointCount(0, s.length)

  /** Greedy word wrap at `width` characters. */
  def wrap(text: String, width: Int): Vector[String] =
    val lines = Vector.newBuilder[String]
    var line = ""
    for word <- words(text) do
      if line.isEmpty then line = word
      else if length(line) + 1 + length(word) > width then
        lines += line
        line = word
      else line = s"$line $word"
    if line.nonEmpty then lines += line
    lines.result()

  /** `text` split at the first `sep`, or `(text, "")` when it has none. */
  private def splitOnce(text: String, sep: String): (String, String) = text.indexOf(sep) match
    case -1 => (text, "")
    case i  => (text.substring(0, i), text.substring(i + sep.length))

  def renderCff(meta: ujson.Value, conceptDoi: String): String =
    val out = Vector.newBuilder[String]
    out += "# Generated from .zenodo.json by scripts/marola-tools citation; edit that file, then `just citation`."
    out += "cff-version: 1.2.0"
    out += "message: \"If you use marola, please cite it using the metadata below.\""
    out += s"type: ${str(meta, "upload_type")}"
    out += s"title: ${quoted(str(meta, "title"))}"
    out += "abstract: >-"
    val paragraphs = plain(str(meta, "description")).split("\n\n", -1).toVector
    val wrapped = paragraphs.map(para => wrap(para, 96).map(l => s"  $l"))
    out ++= wrapped.flatMap(_ :+ "").dropRight(1) // a blank line between paragraphs
    out += "authors:"
    for person <- list(meta, "creators") do
      val (family, givenNames) = splitOnce(str(person, "name"), ", ")
      out += s"  - family-names: ${quoted(family)}"
      out += s"    given-names: ${quoted(givenNames)}"
      if field(person, "orcid").isDefined then
        out += s"    orcid: \"https://orcid.org/${str(person, "orcid")}\""
      if field(person, "affiliation").isDefined then
        out += s"    affiliation: ${quoted(str(person, "affiliation"))}"
    out += s"repository-code: \"${repoUrl(meta)}\""
    out += s"url: \"$Homepage\""
    if conceptDoi.nonEmpty then
      out += s"doi: $conceptDoi"
      out += "identifiers:"
      out += "  - type: doi"
      out += s"    value: $conceptDoi"
      out += "    description: Concept DOI, resolves to the latest release"
    out += s"license: ${Spdx.getOrElse(str(meta, "license"), "")}"
    out += "keywords:"
    for keyword <- list(meta, "keywords") do out += s"  - ${quoted(keyword.strOpt.getOrElse(""))}"
    out.result().mkString("\n") + "\n"

  /** The README block: BibTeX, APA and ABNT, between the citation markers. */
  def renderRefs(meta: ujson.Value, conceptDoi: String): String =
    val people = list(meta, "creators").map(p => splitOnce(str(p, "name"), ", "))
    val repo = repoUrl(meta)
    val title = str(meta, "title")
    val (short, sub) = splitOnce(title, ": ")
    val slug = short
      .toLowerCase(java.util.Locale.ROOT)
      .filter(c => c >= 'a' && c <= 'z' || c >= '0' && c <= '9')
    val firstFamily = people.head._1.toLowerCase(java.util.Locale.ROOT)

    val bib = Vector.newBuilder[String]
    bib += s"@software{${firstFamily}_${Year}_$slug,"
    bib += s"  author    = {${people.map((f, g) => s"$f, $g").mkString(" and ")}},"
    bib += s"  title     = {{$title}},"
    bib += s"  year      = {$Year},"
    if conceptDoi.nonEmpty then
      bib += "  publisher = {Zenodo},"
      bib += s"  doi       = {$conceptDoi},"
    bib += s"  url       = {$repo}"
    bib += "}"

    def initials(givenNames: String) =
      words(givenNames)
        .map(part => String(Character.toChars(part.codePointAt(0))) + ".")
        .mkString(" ")
    val apaNames = people.map((f, g) => s"$f, ${initials(g)}")
    val apaAuthors =
      if apaNames.length == 1 then apaNames.head
      else s"${apaNames.init.mkString(", ")}, & ${apaNames.last}"
    val place = if conceptDoi.isEmpty then repo else s"Zenodo. https://doi.org/$conceptDoi"
    val apa = s"$apaAuthors ($Year). *$title* [Computer software]. $place"

    val abntAuthors =
      people.map((f, g) => s"${f.toUpperCase(java.util.Locale.ROOT)}, $g").mkString("; ")
    val abntTitle = if sub.isEmpty then s"**$title**" else s"**$short**: $sub"
    val abnt =
      if conceptDoi.isEmpty then
        s"$abntAuthors. $abntTitle. [S. l.]: GitHub, $Year. Disponível em: $repo."
      else s"$abntAuthors. $abntTitle. [S. l.]: Zenodo, $Year. DOI $conceptDoi."

    val lines = Vector(Start, "", "BibTeX:", "", "```bibtex") ++ bib.result() ++
      Vector("```", "", "APA:", "", s"> $apa", "", "ABNT (NBR 6023):", "", s"> $abnt", "", End)
    lines.mkString("\n")

  /** `text` with the block between the citation markers replaced by `block`. */
  def splice(text: String, block: String): Either[String, String] =
    val start = text.indexOf(Start)
    val end = if start < 0 then -1 else text.indexOf(End, start + Start.length)
    if end < 0 then Left(s"no $Start ... $End block")
    else Right(text.substring(0, start) + block + text.substring(end + End.length))

  // ---- adding a person ------------------------------------------------------------------------

  /** Adds a contributor, or with `author` a creator, to `meta` in place. */
  def addPerson(
      meta: ujson.Value,
      name: String,
      orcid: Option[String],
      affiliation: Option[String],
      contributorType: String,
      author: Boolean
  ): Either[String, Unit] =
    val person = ujson.Obj("name" -> name)
    affiliation.filter(_.nonEmpty).foreach(a => person("affiliation") = a)
    orcid.filter(_.nonEmpty).foreach(o => person("orcid") = o)
    if !author then person("type") = contributorType
    val key = if author then "creators" else "contributors"
    if (list(meta, "creators") ++ list(meta, "contributors")).exists(str(_, "name") == name) then
      Left(s"$name is already listed")
    else
      meta match
        case ujson.Obj(map) =>
          map.getOrElseUpdate(key, ujson.Arr()) match
            case ujson.Arr(items) =>
              items += person
              Right(())
            case _ => Left(s"$key is not a list")
        case _ => Left(".zenodo.json is not an object")

  def writeJson(path: os.Path, meta: ujson.Value): Unit =
    os.write.over(path, Util.dumps(meta, Some(2), ensureAscii = false) + "\n")

  // ---- running --------------------------------------------------------------------------------

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

  /** Validates `zenodo`, then writes (or with `check`, compares) the CFF and each README's block.
    */
  def run(zenodo: os.Path, cff: os.Path, check: Boolean, readmes: Seq[os.Path]): Int =
    readJson(zenodo) match
      case Left(error) =>
        System.err.println(s"citation: $zenodo: $error")
        1
      case Right(meta) =>
        validate(meta) match
          case Vector() => generate(meta, cff, check, readmes)
          case errors   =>
            System.err.println(s"citation: .zenodo.json is invalid:\n  ${errors.mkString("\n  ")}")
            1

  private def generate(
      meta: ujson.Value,
      cff: os.Path,
      check: Boolean,
      readmes: Seq[os.Path]
  ): Int =
    val block = renderRefs(meta, ConceptDoi)
    val spliced = readmes.map(readme => readme -> readText(readme).flatMap(splice(_, block)))
    spliced.collectFirst { case (readme, Left(error)) => (readme, error) } match
      case Some((readme, error)) =>
        System.err.println(s"citation: ${readme.last}: $error")
        1
      case None =>
        val wanted = (cff -> renderCff(meta, ConceptDoi)) +: spliced.collect { case (p, Right(t)) =>
          p -> t
        }
        val stale = wanted.filter((path, text) => !readText(path).contains(text))
        if check then
          if stale.nonEmpty then
            System.err.println(
              s"citation: stale: ${stale.map(_._1.last).mkString(", ")}; run `just citation`"
            )
            1
          else
            System.err.println("citation: .zenodo.json valid, generated text current")
            0
        else
          for (path, text) <- stale do
            os.write.over(path, text)
            System.err.println(s"citation: wrote ${path.last}")
          0

  case class Person(
      name: String,
      orcid: Option[String],
      affiliation: Option[String],
      contributorType: String,
      author: Boolean
  )

  private def add(zenodo: os.Path, person: Person): Either[String, Unit] =
    for
      meta <- readJson(zenodo)
      _ <- addPerson(
        meta,
        person.name,
        person.orcid,
        person.affiliation,
        person.contributorType,
        person.author
      )
      _ <- validate(meta) match
        case Vector() => Right(())
        case errors   => Left(s"not added:\n  ${errors.mkString("\n  ")}")
    yield writeJson(zenodo, meta)

  /** The whole command: with `person`, add them to .zenodo.json first, then regenerate or check. */
  def main(check: Boolean, person: Option[Person]): Int =
    val root = Util.repoRoot
    person.map(add(root / ".zenodo.json", _)) match
      case Some(Left(error)) =>
        System.err.println(s"citation: $error")
        1
      case _ =>
        run(
          root / ".zenodo.json",
          root / "CITATION.cff",
          check,
          Seq(root / "README.md", root / "README.en.md")
        )

  // ---- arguments: `citation [--check] [add ...]` ----------------------------------------------

  class Command(addArgs: Seq[String]):
    @main(doc =
      "Validate .zenodo.json and regenerate CITATION.cff and the READMEs' citation block (MIP-0079)"
    )
    def citation(
        @arg(doc = "Validate only; exit 1 if any generated text is stale") check: Flag
    ): Int =
      if addArgs.isEmpty then main(check.value, None)
      else Cli.run(ParserForMethods(AddCommand(check.value)), "citation add", addArgs.tail)

  class AddCommand(check: Boolean):
    @main(doc = "Add a contributor (or, with --author, a creator)")
    def add(
        @arg(doc = "\"Family, Given\"") name: String,
        @arg(doc = "Bare iD, 0000-0000-0000-0000") orcid: Option[String] = None,
        affiliation: Option[String] = None,
        @arg(name = "type", doc = "A Zenodo contributor type") contributorType: String =
          "ProjectMember",
        @arg(doc = "List as a creator (cited author)") author: Flag
    ): Int =
      if ContributorTypes.contains(contributorType) then
        main(check, Some(Person(name, orcid, affiliation, contributorType, author.value)))
      else
        System.err.println(
          s"marola-tools citation add: invalid value '$contributorType' for '--type'" +
            s" [possible values: ${ContributorTypes.mkString(", ")}]"
        )
        2

  def cli(args: Seq[String]): Int =
    val (citationArgs, addArgs) = args.span(_ != "add")
    Cli.run(ParserForMethods(Command(addArgs)), "citation", citationArgs)
