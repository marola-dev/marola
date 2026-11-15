package marola.tools.publishing

import marola.tools.shared.Util

import Util.field
import Zenodo.Key

// .zenodo.json read and edited as JSON. It is read loosely, a missing key or a wrong type being
// just absent, so that every problem is reported at once in the words the checks always used; and
// it is edited in place, so a rewrite keeps the file's key order and the fields nothing here reads.
object ZenodoJson:
  private def str(v: ujson.Value, key: String): String = strOpt(v, key).getOrElse("")

  private def strOpt(v: ujson.Value, key: String): Option[String] = field(v, key).collect:
    case ujson.Str(s) => s

  private def list(v: ujson.Value, key: String): Seq[ujson.Value] = field(v, key) match
    case Some(ujson.Arr(items)) => items.toSeq
    case _                      => Seq.empty

  // Python's truthiness, as "missing <key>" has always meant it.
  private def truthy(v: Option[ujson.Value]): Boolean = v match
    case None | Some(ujson.Null) => false
    case Some(ujson.Bool(b))     => b
    case Some(ujson.Str(s))      => s.nonEmpty
    case Some(ujson.Arr(a))      => a.nonEmpty
    case Some(ujson.Obj(o))      => o.nonEmpty
    case Some(ujson.Num(n))      => n != 0.0

  private def errorsOf[A](results: Seq[Either[Seq[String], A]]): Seq[String] =
    results.flatMap(_.left.getOrElse(Nil))

  /** The metadata, or every reason it is invalid, in file order. */
  def decode(meta: ujson.Value): Either[Vector[String], ZenodoMetadata] =
    val uploadType = UploadType.fromId(str(meta, Key.UploadType))
    val license = strOpt(meta, Key.License).flatMap(License.fromZenodo)
    val language = LanguageCode.parse(str(meta, Key.Language)).toOption
    def people(key: String, contributor: Boolean) =
      list(meta, key).zipWithIndex.map((p, i) => person(p, s"$key[$i]", contributor))
    val creators = people(Key.Creators, contributor = false)
    val contributors = people(Key.Contributors, contributor = true)
    val related = list(meta, Key.RelatedIdentifiers).zipWithIndex.map(relatedIdentifier)

    val errors = Zenodo.Required.filterNot(k => truthy(field(meta, k))).map(k => s"missing $k") ++
      Option.when(uploadType.isEmpty)("upload_type must be software or dataset") ++
      Option.when(str(meta, Key.AccessRight) != Zenodo.OpenAccess)("access_right must be open") ++
      Option.when(license.isEmpty)(
        s"license ${Util.pyrepr(field(meta, Key.License))} has no SPDX mapping here"
      ) ++
      Option.when(language.isEmpty)("language must be an ISO 639-3 code such as eng") ++
      Zenodo.Forbidden
        .filter(field(meta, _).isDefined)
        .map(k => s"$k is set by Zenodo; remove it") ++
      errorsOf(creators) ++ errorsOf(contributors) ++ errorsOf(related)

    val decoded =
      for
        upload <- uploadType
        licence <- license
        lang <- language
        if errors.isEmpty
      yield ZenodoMetadata(
        uploadType = upload,
        title = str(meta, Key.Title),
        description = str(meta, Key.Description),
        creators = creators.flatMap(_.toOption),
        contributors = contributors.flatMap(_.toOption),
        license = licence,
        keywords = list(meta, Key.Keywords).map(_.strOpt.getOrElse("")),
        language = lang,
        relatedIdentifiers = related.flatMap(_.toOption)
      )
    decoded.toRight(errors.toVector)

  private def person(
      p: ujson.Value,
      at: String,
      contributor: Boolean
  ): Either[Seq[String], Person] =
    val name = PersonName
      .parse(str(p, Key.Name))
      .left
      .map(_ => s"$at: name ${Util.pyrepr(field(p, Key.Name))} is not 'Family, Given'")
    val orcid = field(p, Key.Orcid) match
      case None        => Right(None)
      case Some(value) =>
        value.strOpt
          .flatMap(Orcid.parse(_).toOption)
          .map(Some(_))
          .toRight(s"$at: orcid ${Util.pyrepr(Some(value))} is not a valid bare ORCID iD")
    val role =
      if !contributor then Right(Role.Creator)
      else
        strOpt(p, Key.Type)
          .flatMap(ContributorType.parse(_).toOption)
          .map(Role.Contributor(_))
          .toRight(s"$at: type ${Util.pyrepr(field(p, Key.Type))} is not Zenodo's")
    val affiliation = field(p, Key.Affiliation).map(_.strOpt.getOrElse(""))
    val built =
      for n <- name; o <- orcid; r <- role yield Person(n, o, affiliation, r)
    built.left.map(_ => Seq(name, orcid, role).flatMap(_.left.toOption))

  private def relatedIdentifier(r: ujson.Value, i: Int): Either[Seq[String], RelatedIdentifier] =
    val at = s"${Key.RelatedIdentifiers}[$i]"
    val relation = strOpt(r, Key.Relation)
      .flatMap(Relation.parse(_).toOption)
      .toRight(s"$at: relation ${Util.pyrepr(field(r, Key.Relation))} unknown")
    val identifier = str(r, Key.Identifier)
    val quotedId = Util.pyreprStr(identifier)
    val id = strOpt(r, Key.Scheme).flatMap(Zenodo.Scheme.fromId) match
      case Some(Zenodo.Scheme.Doi) =>
        Doi
          .parse(identifier)
          .map(Identifier.ByDoi(_))
          .left
          .map(_ => s"$at: $quotedId is not a bare DOI")
      case Some(Zenodo.Scheme.Url) =>
        Identifier.url(identifier).left.map(_ => s"$at: $quotedId is not an https URL")
      case None => Left(s"$at: scheme must be doi or url")
    val built = for rel <- relation; ident <- id yield RelatedIdentifier(ident, rel)
    built.left.map(_ => Seq(relation, id).flatMap(_.left.toOption))

  /** Adds `person` to `meta` in place: a contributor, or with Role.Creator a creator. */
  def addPerson(meta: ujson.Value, person: PersonRequest): Either[String, Unit] =
    val (key, kind) = person.role match
      case Role.Creator           => (Key.Creators, None)
      case Role.Contributor(kind) => (Key.Contributors, Some(kind.value))
    val fields = Seq(Key.Name -> person.name) ++
      person.affiliation.filter(_.nonEmpty).map(Key.Affiliation -> _) ++
      person.orcid.filter(_.nonEmpty).map(Key.Orcid -> _) ++
      kind.map(Key.Type -> _)
    val entry = ujson.Obj.from(fields.map((k, v) => k -> ujson.Str(v)))
    val listed = list(meta, Key.Creators) ++ list(meta, Key.Contributors)
    if listed.exists(str(_, Key.Name) == person.name) then Left(s"${person.name} is already listed")
    else
      meta match
        case ujson.Obj(map) =>
          map.getOrElseUpdate(key, ujson.Arr()) match
            case ujson.Arr(items) =>
              items += entry
              Right(())
            case _ => Left(s"$key is not a list")
        case _ => Left(s"${CitationFiles.Zenodo} is not an object")
