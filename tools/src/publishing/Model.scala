package marola.tools.publishing

import marola.tools.shared.Util

import java.util.Locale
import scala.collection.immutable.{SortedMap, SortedSet}

// The publishing domain: the Zenodo deposit metadata and the citation text made from it
// (MIP-0079), a release's version and its pointer-move guard, and the MIP dependency graph.
// Decoding and file IO live in the commands and in ZenodoJson.

// ==== Citation ===================================================================================

/** The files of the citation, relative to the repo root. */
object CitationFiles:
  val Zenodo = ".zenodo.json"
  val Cff = "CITATION.cff"
  val Readmes: Seq[String] = Seq("README.md", "README.en.md")

/** "Family, Given": exactly one comma, as Zenodo and CFF both read a name. */
final case class PersonName(family: String, givenNames: String):
  def value: String = s"$family, $givenNames"

object PersonName:
  private val Pattern = "[^,]+, [^,]+".r
  val Separator = ", "

  def parse(name: String): Either[String, PersonName] =
    Option
      .when(Pattern.matches(name))(name.indexOf(Separator))
      .map(i => PersonName(name.substring(0, i), name.substring(i + Separator.length)))
      .toRight(s"'$name' is not 'Family, Given'")

/** A bare ORCID iD; `parse` verifies its ISO 7064 11-2 check digit. */
final case class Orcid(value: String):
  def url: String = s"${Orcid.Resolver}$value"

object Orcid:
  val Resolver = "https://orcid.org/"
  // `(?U)`: \d matches any Unicode digit, as Java's does not by default.
  private val Pattern = raw"(?U)\d{4}-\d{4}-\d{4}-\d{3}[\dX]".r

  def parse(orcid: String): Either[String, Orcid] =
    Either.cond(valid(orcid), Orcid(orcid), s"'$orcid' is not a valid bare ORCID iD")

  def valid(orcid: String): Boolean =
    Pattern.matches(orcid) && {
      val digits = orcid.filter(_ != '-')
      val total = digits.init.foldLeft(0)((sum, ch) => (sum + Character.digit(ch, 10).max(0)) * 2)
      val check = (12 - total % 11) % 11
      digits.last == (if check == 10 then 'X' else ('0' + check).toChar)
    }

/** A bare DOI, `10.<registrant>/<suffix>`. */
final case class Doi(value: String):
  def url: String = s"${Doi.Resolver}$value"

object Doi:
  val Resolver = "https://doi.org/"
  private val Pattern = raw"(?U)10\.\d{4,9}/\S+".r

  def parse(doi: String): Either[String, Doi] =
    Either.cond(Pattern.matches(doi), Doi(doi), s"'$doi' is not a bare DOI")

/** An ISO 639-3 language code such as `eng`. */
final case class LanguageCode(value: String)

object LanguageCode:
  private val Pattern = "[a-z]{3}".r

  def parse(code: String): Either[String, LanguageCode] =
    Either.cond(Pattern.matches(code), LanguageCode(code), s"'$code' is not an ISO 639-3 code")

/** The licences this repo publishes under, by Zenodo's id and their SPDX id. */
enum License(val zenodoId: String, val spdx: String):
  case Mit extends License("mit", "MIT")
  case CcBy40 extends License("cc-by-4.0", "CC-BY-4.0")

object License:
  def fromZenodo(id: String): Option[License] = values.find(_.zenodoId == id)

enum UploadType(val id: String):
  case Software extends UploadType("software")
  case Dataset extends UploadType("dataset")

object UploadType:
  def fromId(id: String): Option[UploadType] = values.find(_.id == id)

/** One of Zenodo's relation types for a related identifier. */
final case class Relation(value: String)

object Relation:
  // developers.zenodo.org, deposit metadata, checked 2026-10-07.
  val All: Set[String] = Set(
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
  val IsSupplementTo = Relation("isSupplementTo")

  def parse(relation: String): Either[String, Relation] =
    Either.cond(All.contains(relation), Relation(relation), s"relation '$relation' unknown")

/** One of Zenodo's contributor types; `All` keeps Zenodo's order, which `--type`'s help prints. */
final case class ContributorType(value: String)

object ContributorType:
  val All: Seq[String] = Seq(
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
  val Default = "ProjectMember"

  def parse(kind: String): Either[String, ContributorType] =
    Either.cond(All.contains(kind), ContributorType(kind), s"type '$kind' is not Zenodo's")

/** A creator is a cited author; a contributor is credited with a Zenodo type. */
enum Role:
  case Creator
  case Contributor(kind: ContributorType)

final case class Person(
    name: PersonName,
    orcid: Option[Orcid],
    // Trap: a present but non-string affiliation is "" here; validation never checked its type.
    affiliation: Option[String],
    role: Role
)

/** Who `citation add` is asked to add, as typed: the whole file is validated after the add. */
final case class PersonRequest(
    name: String,
    orcid: Option[String],
    affiliation: Option[String],
    role: Role
)

enum Identifier:
  case ByDoi(doi: Doi)
  case ByUrl(url: String)

  def value: String = this match
    case ByDoi(doi) => doi.value
    case ByUrl(url) => url

object Identifier:
  val Https = "https://"

  def url(url: String): Either[String, Identifier] =
    Either.cond(url.startsWith(Https), ByUrl(url), s"'$url' is not an https URL")

final case class RelatedIdentifier(identifier: Identifier, relation: Relation)

/** A valid .zenodo.json, as far as the citation text reads it. */
final case class ZenodoMetadata(
    uploadType: UploadType,
    title: String,
    description: String,
    creators: Seq[Person],
    contributors: Seq[Person],
    license: License,
    keywords: Seq[String],
    language: LanguageCode,
    relatedIdentifiers: Seq[RelatedIdentifier]
):
  /** The GitHub repository the deposit supplements. Validation does not require one, so a file
    * without it fails here, as it always has.
    */
  def repositoryCode: String =
    relatedIdentifiers
      .collectFirst {
        case RelatedIdentifier(id, Relation.IsSupplementTo) if id.value.startsWith(Zenodo.GitHub) =>
          id.value
      }
      .getOrElse(
        throw IllegalStateException(
          s"no GitHub isSupplementTo identifier in ${CitationFiles.Zenodo}"
        )
      )

object Zenodo:
  val GitHub = "https://github.com/"
  val OpenAccess = "open"

  /** The .zenodo.json keys this repo reads or writes. */
  object Key:
    val Title = "title"
    val Description = "description"
    val UploadType = "upload_type"
    val AccessRight = "access_right"
    val License = "license"
    val Language = "language"
    val Keywords = "keywords"
    val Creators = "creators"
    val Contributors = "contributors"
    val RelatedIdentifiers = "related_identifiers"
    val Name = "name"
    val Orcid = "orcid"
    val Affiliation = "affiliation"
    val Type = "type"
    val Relation = "relation"
    val Identifier = "identifier"
    val Scheme = "scheme"

  enum Scheme(val id: String):
    case Doi extends Scheme("doi")
    case Url extends Scheme("url")

  object Scheme:
    def fromId(id: String): Option[Scheme] = values.find(_.id == id)

  // Zenodo sets these itself (version from the tag, date from the release, DOI minted); a value
  // here would override or break that.
  val Forbidden: Seq[String] = Seq("version", "publication_date", "doi", "prereserve_doi")
  val Required: Seq[String] =
    Seq(Key.Title, Key.Description, Key.Creators, Key.License, Key.Keywords, Key.Language)

/** CITATION.cff and the READMEs' BibTeX, APA and ABNT block, rendered from the metadata. */
object CitationText:
  val Start = "<!-- citation:start -->"
  val End = "<!-- citation:end -->"

  // Filled in by the PR that follows the first archived release (MIP-0079 §5.5).
  val ConceptDoi: Option[Doi] = Some(Doi("10.5281/zenodo.23224155"))

  private val Homepage = "https://marola.dev"
  private val Year = "2026"
  private val Width = 96
  private val Whitespace = raw"(?U)\s+".r

  /** A JSON string literal, non-ASCII kept as is. */
  private def quoted(s: String): String = Util.dumps(ujson.Str(s), ensureAscii = false)

  private def words(text: String): Seq[String] = Whitespace.split(text).toSeq.filter(_.nonEmpty)

  private val NamedEntities =
    Map("amp" -> "&", "lt" -> "<", "gt" -> ">", "quot" -> "\"", "apos" -> "'", "nbsp" -> " ")

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

  /** The HTML description as plain text, paragraphs separated by a blank line. */
  def plain(description: String): String =
    val paragraphs = raw"(?U)</p>\s*<p>".r.replaceAllIn(description, "\n\n")
    Util.trimUnicode(decodeEntities("<[^>]+>".r.replaceAllIn(paragraphs, "")))

  private def length(s: String): Int = s.codePointCount(0, s.length)

  /** Greedy word wrap at `width` characters. */
  def wrap(text: String, width: Int): Vector[String] =
    val (lines, last) = words(text).foldLeft((Vector.empty[String], "")) {
      case ((lines, line), word) =>
        if line.isEmpty then (lines, word)
        else if length(line) + 1 + length(word) > width then (lines :+ line, word)
        else (lines, s"$line $word")
    }
    if last.isEmpty then lines else lines :+ last

  /** `text` split at the first `sep`, or `(text, "")` when it has none. */
  private def splitOnce(text: String, sep: String): (String, String) = text.indexOf(sep) match
    case -1 => (text, "")
    case i  => (text.substring(0, i), text.substring(i + sep.length))

  def renderCff(meta: ZenodoMetadata, conceptDoi: Option[Doi]): String =
    val header = Vector(
      s"# Generated from ${CitationFiles.Zenodo} by scripts/marola-tools citation; edit that file, then `just citation`.",
      "cff-version: 1.2.0",
      "message: \"If you use marola, please cite it using the metadata below.\"",
      s"type: ${meta.uploadType.id}",
      s"title: ${quoted(meta.title)}",
      "abstract: >-"
    )
    val paragraphs = plain(meta.description).split("\n\n", -1).toVector
    // a blank line between paragraphs
    val abstractLines =
      paragraphs.map(wrap(_, Width).map(l => s"  $l")).flatMap(_ :+ "").dropRight(1)
    val authors = meta.creators.toVector.flatMap { person =>
      Vector(
        s"  - family-names: ${quoted(person.name.family)}",
        s"    given-names: ${quoted(person.name.givenNames)}"
      ) ++ person.orcid.map(orcid => s"    orcid: \"${orcid.url}\"") ++
        person.affiliation.map(a => s"    affiliation: ${quoted(a)}")
    }
    val doiLines = conceptDoi.toVector.flatMap { doi =>
      Vector(
        s"doi: ${doi.value}",
        "identifiers:",
        "  - type: doi",
        s"    value: ${doi.value}",
        "    description: Concept DOI, resolves to the latest release"
      )
    }
    val lines = header ++ abstractLines ++ ("authors:" +: authors) ++
      Vector(s"repository-code: \"${meta.repositoryCode}\"", s"url: \"$Homepage\"") ++ doiLines ++
      Vector(s"license: ${meta.license.spdx}", "keywords:") ++
      meta.keywords.map(keyword => s"  - ${quoted(keyword)}")
    lines.mkString("\n") + "\n"

  private def initials(givenNames: String): String =
    words(givenNames)
      .map(part => String(Character.toChars(part.codePointAt(0))) + ".")
      .mkString(" ")

  /** The README block: BibTeX, APA and ABNT, between the citation markers. */
  def renderRefs(meta: ZenodoMetadata, conceptDoi: Option[Doi]): String =
    val people = meta.creators.map(_.name)
    val repo = meta.repositoryCode
    val title = meta.title
    val (short, sub) = splitOnce(title, ": ")
    val slug = short
      .toLowerCase(Locale.ROOT)
      .filter(c => c >= 'a' && c <= 'z' || c >= '0' && c <= '9')
    val firstFamily = people.head.family.toLowerCase(Locale.ROOT)

    val bib = Vector(
      s"@software{${firstFamily}_${Year}_$slug,",
      s"  author    = {${people.map(_.value).mkString(" and ")}},",
      s"  title     = {{$title}},",
      s"  year      = {$Year},"
    ) ++ conceptDoi.toVector.flatMap(doi =>
      Vector("  publisher = {Zenodo},", s"  doi       = {${doi.value}},")
    ) ++ Vector(s"  url       = {$repo}", "}")

    val apaNames = people.map(p => s"${p.family}, ${initials(p.givenNames)}")
    val apaAuthors =
      if apaNames.length == 1 then apaNames.head
      else s"${apaNames.init.mkString(", ")}, & ${apaNames.last}"
    val place = conceptDoi.fold(repo)(doi => s"Zenodo. ${doi.url}")
    val apa = s"$apaAuthors ($Year). *$title* [Computer software]. $place"

    val abntAuthors =
      people.map(p => s"${p.family.toUpperCase(Locale.ROOT)}, ${p.givenNames}").mkString("; ")
    val abntTitle = if sub.isEmpty then s"**$title**" else s"**$short**: $sub"
    val abnt = conceptDoi match
      case None      => s"$abntAuthors. $abntTitle. [S. l.]: GitHub, $Year. Disponível em: $repo."
      case Some(doi) => s"$abntAuthors. $abntTitle. [S. l.]: Zenodo, $Year. DOI ${doi.value}."

    val lines = Vector(Start, "", "BibTeX:", "", "```bibtex") ++ bib ++
      Vector("```", "", "APA:", "", s"> $apa", "", "ABNT (NBR 6023):", "", s"> $abnt", "", End)
    lines.mkString("\n")

  /** `text` with the block between the citation markers replaced by `block`. */
  def splice(text: String, block: String): Either[String, String] =
    val start = text.indexOf(Start)
    val end = if start < 0 then -1 else text.indexOf(End, start + Start.length)
    if end < 0 then Left(s"no $Start ... $End block")
    else Right(text.substring(0, start) + block + text.substring(end + End.length))

// ==== Release ====================================================================================

/** What a release is made from and with. */
object ReleaseRepo:
  val Branch = "main"
  val Remote = "origin"
  val TagGlob = "v*"
  val CitationCheck: Seq[String] = Seq("scripts/marola-tools", "citation", "--check")

/** MAJOR.MINOR.PATCH, given with or without its `v`; the tag is `v` and the version. */
final case class SemVer(value: String):
  def tag: String = s"${SemVer.TagPrefix}$value"

object SemVer:
  val TagPrefix = "v"

  def parse(version: String): Either[String, SemVer] =
    val bare = SemVer(version.stripPrefix(TagPrefix))
    Either.cond(validTag(bare.tag), bare, s"'$version' is not MAJOR.MINOR.PATCH")

  def validTag(tag: String): Boolean = tag.matches(raw"v[0-9]+\.[0-9]+\.[0-9]+")

/** One `git diff --raw` line's two modes; mode 160000 is a gitlink, a submodule pointer. */
final case class RawDiffEntry(sourceMode: String, destinationMode: String):
  def touchesGitlink: Boolean =
    sourceMode == RawDiffEntry.Gitlink || destinationMode == RawDiffEntry.Gitlink

object RawDiffEntry:
  val Gitlink = "160000"

  /** `:<src mode> <dst mode> <src sha> <dst sha> <status>\t<path>`. */
  def parse(line: String): RawDiffEntry =
    val fields = line.trim.split("\\s+")
    RawDiffEntry(fields.head.drop(1), fields.lift(1).getOrElse(""))

/** What `release --guard` found between `ref` and the newest earlier v* tag. Hard rule: a submodule
  * pointer move never makes a Zenodo version (MIP-0079).
  */
enum GuardVerdict:
  case NoBase(ref: String)
  case PointersOnly(base: String, ref: String)
  case Content(base: String, changedFiles: Int)

  def message: String = this match
    case NoBase(ref)             => s"no earlier v* tag before $ref; nothing to compare"
    case PointersOnly(base, ref) =>
      s"$base..$ref only moves submodule pointers; a pointer move never makes a Zenodo version"
    case Content(base, changed) =>
      s"$changed file(s) changed outside the submodule pointers since $base"

  def exitCode: Int = this match
    case PointersOnly(_, _)        => 1
    case NoBase(_) | Content(_, _) => 0

object GuardVerdict:
  def compared(base: String, ref: String, changedFiles: Int): GuardVerdict =
    if changedFiles == 0 then PointersOnly(base, ref) else Content(base, changedFiles)

// ==== MIP graph ==================================================================================

/** A MIP's status, from the first clause of its **Status** row. A row that starts with none of the
  * known words (the template's own "Proposed / Accepted / ..." included) is drawn as Draft.
  */
enum MipStatus(val word: String):
  case PartiallyImplemented extends MipStatus("Partially implemented")
  case Implemented extends MipStatus("Implemented")
  case Accepted extends MipStatus("Accepted")
  case Rejected extends MipStatus("Rejected")
  case Superseded extends MipStatus("Superseded")
  case Draft extends MipStatus("Draft")
  case Unknown(text: String) extends MipStatus("Draft")

object MipStatus:
  private val Known =
    Seq(PartiallyImplemented, Implemented, Accepted, Rejected, Superseded, Draft)

  def parse(text: String): MipStatus =
    val s = text.toLowerCase(Locale.ROOT)
    Known.find(k => s.startsWith(k.word.toLowerCase(Locale.ROOT))).getOrElse(Unknown(text))

// The title is parsed for the tests' sake: the graph names a MIP by number only.
final case class Mip(num: Int, title: String, status: MipStatus, blockedBy: Seq[Int]):
  def nodeId: String = f"M$num%04d"
  def name: String = Mip.name(num)
  def statusWord: String = status.word

object Mip:
  def name(num: Int): String = f"MIP-$num%04d"

type Mips = SortedMap[Int, Mip]

/** `blocker` must land before `blocked` (its **Blocked by** row names `blocker`). */
final case class Edge(blocker: Int, blocked: Int)

object Edge:
  given Ordering[Edge] = Ordering.by(e => (e.blocker, e.blocked))

/** A box of the figure, at a grid cell: `col` across, `row` down. */
final case class PlacedNode(mip: Mip, col: Int, row: Int):
  def x: Int = 40 + col * 260
  def y: Int = 40 + row * 110

object MipDependencies:
  private val BandCols = 4

  /** Every Blocked-by row that names a known MIP. */
  def edges(mips: Mips): SortedSet[Edge] =
    SortedSet.from(
      mips.values.flatMap(m => m.blockedBy.filter(mips.contains).map(b => Edge(b, m.num)))
    )

  // Only the MIPs in an edge are drawn: an orphan box says nothing the index table doesn't.
  def connected(mips: Mips): SortedSet[Int] =
    edges(mips).flatMap(e => Seq(e.blocker, e.blocked))

  private def blockersOf(mips: Mips, num: Int): Seq[Int] =
    mips.get(num).toSeq.flatMap(_.blockedBy)

  private def blockedBy(mips: Mips, num: Int): Seq[Int] =
    if mips.contains(num) then mips.values.filter(_.blockedBy.contains(num)).map(_.num).toSeq
    else Nil

  /** Every MIP transitively blocking `start` (or blocked by it, when `blockers` is false). */
  def reachable(mips: Mips, start: Int, blockers: Boolean): SortedSet[Int] =
    val next = if blockers then blockersOf(mips, _) else blockedBy(mips, _)
    @annotation.tailrec
    def visit(pending: List[Int], seen: SortedSet[Int]): SortedSet[Int] = pending match
      case Nil             => seen
      case current :: rest =>
        val fresh = next(current).distinct.filterNot(seen)
        visit(fresh.toList ++ rest, seen ++ fresh)
    visit(List(start), SortedSet.empty)

  /** `relax` applied over every edge, once per node: enough passes for any chain to settle. */
  private def settle(nodes: SortedSet[Int], edgeSet: SortedSet[Edge], init: Map[Int, Int])(
      relax: (Map[Int, Int], Edge) => Map[Int, Int]
  ): Map[Int, Int] =
    nodes.foldLeft(init)((acc, _) => edgeSet.foldLeft(acc)(relax))

  /** The connected MIPs placed on the grid. Each connected group is its own column band, layered
    * top-down by its longest blocking chain; bands wrap after BandCols.
    */
  def layout(mips: Mips): Seq[PlacedNode] =
    val edgeSet = edges(mips)
    val nodes = connected(mips)
    val layer = settle(nodes, edgeSet, nodes.map(_ -> 0).toMap) { (l, e) =>
      l.updated(e.blocked, l(e.blocked).max(l(e.blocker) + 1))
    }
    val group = settle(nodes, edgeSet, nodes.map(n => n -> n).toMap) { (g, e) =>
      val least = g(e.blocker).min(g(e.blocked))
      g.updated(e.blocker, least).updated(e.blocked, least)
    }
    val bands = nodes.toSeq.groupBy(group).to(SortedMap).values.map(_.groupBy(layer).to(SortedMap))

    // (next band's column, its row, the rows of the current line of bands, what is placed)
    val start = (0, 0, 0, Vector.empty[PlacedNode])
    val (_, _, _, placed) = bands.foldLeft(start) { case ((col0, row0, lineRows, done), byLayer) =>
      val width = byLayer.values.map(_.length).max
      val wraps = col0 > 0 && col0 + width > BandCols
      val (col, row, rows) = if wraps then (0, row0 + lineRows, 0) else (col0, row0, lineRows)
      val boxes = for
        (l, ns) <- byLayer.toVector
        (n, i) <- ns.zipWithIndex
      yield PlacedNode(mips(n), col + i, row + l)
      (col + width, row, rows.max(byLayer.size), done ++ boxes)
    }
    placed

/** Whether two MIPs can be worked at once: neither blocks the other, and their §5 source paths do
  * not overlap.
  */
final case class ParallelVerdict(a: Int, b: Int, conflict: Boolean, overlap: SortedSet[String]):
  def safe: Boolean = !conflict && overlap.isEmpty
  def exitCode: Int = if safe then 0 else 1

  def lines: Seq[String] =
    val overlapLine =
      if overlap.isEmpty then
        "  §5 source-path overlap: no (or no backticked source paths found in one/both)"
      else s"  §5 source-path overlap: yes — ${overlap.mkString(", ")}"
    Seq(
      s"${Mip.name(a)} vs ${Mip.name(b)}:",
      s"  dependency conflict (Blocked by graph): ${if conflict then "yes" else "no"}",
      overlapLine,
      if safe then "  -> parallel-safe on both checks" else "  -> NOT confirmed parallel-safe"
    )

object ParallelVerdict:
  def of(
      mips: Mips,
      a: Int,
      b: Int,
      pathsA: SortedSet[String],
      pathsB: SortedSet[String]
  ): ParallelVerdict =
    val conflict = MipDependencies.reachable(mips, a, true).contains(b) ||
      MipDependencies.reachable(mips, b, true).contains(a)
    ParallelVerdict(a, b, conflict, pathsA.intersect(pathsB))

/** The graph's block in docs/MIPs/README.md, and the files it is drawn into. */
object MipReadme:
  val MipsDir = "docs/MIPs"
  val Readme = "README.md"
  val Figure = "docs/archify/docs/mip-graph.json"
  val FigurePng = "../img/archify/mip-graph.png"
  val FigureHtml = "mip-graph.html"

  val StartMark = "<!-- mip-graph:start -->"
  val EndMark = "<!-- mip-graph:end -->"
  val FigureAlt = "MIP dependency graph: an arrow runs from a MIP to the MIP it blocks"

  private val NoEdgesPlaceholder =
    "_No MIP currently declares a **Blocked by** relationship, so there is nothing to graph yet — add that field to a MIP's metadata table and run `just mip-graph` again._"

  /** The figure (or the placeholder), then the MIPs left out of it. */
  def renderBlock(mips: Mips): String =
    val nodes = MipDependencies.connected(mips)
    val figure = s"![$FigureAlt]($FigurePng)"
    val orphans = mips.keys.toSeq.filterNot(nodes.contains).map(Mip.name)
    if nodes.isEmpty then NoEdgesPlaceholder
    else if orphans.isEmpty then figure
    else
      s"$figure\n\n_${orphans.length} MIP(s) with no declared Blocked-by relationship, not graphed: ${orphans.mkString(", ")}._"

  def graphBlock(mips: Mips): String = s"$StartMark\n${renderBlock(mips)}\n$EndMark"

  /** The README with its graph block replaced, or appended when the markers are missing. */
  def withBlock(text: String, block: String): String =
    val start = text.indexOf(StartMark)
    val end = if start < 0 then -1 else text.indexOf(EndMark, start + StartMark.length)
    if end < 0 then
      val separator = if text.endsWith("\n") then "" else "\n"
      s"$text$separator\n$block\n"
    else text.substring(0, start) + block + text.substring(end + EndMark.length)
