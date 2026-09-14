package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NoStackTrace

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.oods.Channel

/**
 * IMA/SC — Santa Catarina's bathing-water programme (MIP-0056 §4.1). Enumeration and the CSV export
 * are `POST`s with no auth; `post` is injected because `Http` is an object with no seam of its own,
 * so the specs and task 3's ingest run replay captured bytes instead of the portal.
 */
final class ImaScAdapter(
    val source: Source = ImaScAdapter.DefaultSource,
    post: (String, Map[String, String]) => String < Sync = ImaScAdapter.livePost,
    now: () => Instant = () => Instant.now()
) extends SourceAdapter:

  private val cachedYears = AtomicReference[Option[List[Int]]](None)
  private val cachedMunicipalities = AtomicReference[Option[List[String]]](None)
  private val cachedBeaches = AtomicReference[Map[String, List[String]]](Map.empty)
  private val cachedPoints = AtomicReference[Option[List[PointRow]]](None)

  def years: List[Int] < Sync =
    memo(cachedYears)(post(url("years"), Map.empty).map(ImaScAdapter.parseYears))

  def municipalities: List[String] < Sync =
    memo(cachedMunicipalities)(post(url("municipalities"), Map.empty).map(ImaScAdapter.parseCodes))

  def beaches(municipio: String): List[String] < Sync =
    cachedBeaches.get.get(municipio) match
      case Some(known) => known
      case None =>
        post(url("beaches"), Map("municipioID" -> municipio))
          .map(ImaScAdapter.parseCodes)
          .map { found =>
            val _ = cachedBeaches.updateAndGet(_.updated(municipio, found))
            found
          }

  def partitions(plan: Plan): List[Partition] < Sync =
    if !serves(plan) then Nil
    else
      for
        available <- years
        names <- municipalities
        wanted = names.filter(m => plan.cities.isEmpty || plan.cities.exists(sameName(_, m)))
        keys <- traverse(wanted)(partitionKeys)
      yield
        val window = available.filter(y => y >= plan.fromYear && y <= plan.toYear).sorted
        keys.flatten.flatMap { key =>
          // `immutable` is the planner's call (MIP-0056 §5.2): the adapter has no refetch window.
          window.map(year => Partition(source.id, Channel.Csv, key, year, immutable = false))
        }

  def fetch(p: Partition): RawFile < Sync =
    for
      (municipio, beach) <- municipioAndLocalId(p.key)
      body <- post(
        url("export_csv"),
        Map("municipioID" -> municipio, "localID" -> beach, "ano" -> p.year.toString)
      )
      at <- Sync.defer(now())
    yield RawFile(p, url("export_csv"), body.getBytes(UTF_8), at)

  /**
   * Trap: the join needs the registry, so `points` must have run — every ingest fetches it once per
   * run (MIP-0056 §4.3). An empty registry would silently key every row by slug, so it is a
   * `ParseError` instead.
   */
  def rows(raw: RawFile): Either[ParseError, List[SampleRow]] =
    val path = ImaScAdapter.rawPath(raw.partition)
    cachedPoints.get match
      case None => Left(ParseError(path, "points registry not loaded — fetch `points` first"))
      case Some(registry) => ImaScCsv.parse(source, path, String(raw.bytes, UTF_8), registry)

  def rawPath(p: Partition): String = ImaScAdapter.rawPath(p)

  def points: List[PointRow] < Sync =
    memo(cachedPoints) {
      post(url("points"), Map.empty)
        .map(body => ImaScAdapter.parsePoints(source, body))
    }

  private def serves(plan: Plan): Boolean =
    (plan.sources.isEmpty || plan.sources.contains(source.id)) &&
      (plan.states.isEmpty || plan.states.exists(_.equalsIgnoreCase(source.state)))

  private def sameName(a: String, b: String): Boolean = ImaScCsv.slug(a) == ImaScCsv.slug(b)

  private def partitionKeys(municipio: String): List[String] < Sync =
    beachKeys(municipio).map(_.keys.toList.sorted)

  private def beachKeys(municipio: String): Map[String, String] < Sync =
    beaches(municipio).map { found =>
      val prefix = ImaScCsv.slug(municipio)
      ImaScCsv.beachSlugs(found).map((beachSlug, name) => s"$prefix/$beachSlug" -> name)
    }

  private def municipioAndLocalId(key: String): (String, String) < Sync =
    val municipioSlug = key.takeWhile(_ != '/')
    def unknown = throw ImaScAdapter.UnknownPartitionException(key)
    for
      names <- municipalities
      municipio = names.find(m => ImaScCsv.slug(m) == municipioSlug).getOrElse(unknown)
      keys <- beachKeys(municipio)
    yield (municipio, keys.getOrElse(key, unknown))

  private def url(name: String): String = source.urls(name)

  private def memo[A](ref: AtomicReference[Option[A]])(load: => A < Sync): A < Sync =
    ref.get match
      case Some(value) => value
      case None =>
        load.map { value =>
          ref.set(Some(value))
          value
        }

  /**
   * Same rationale as `Recommender.traverse`: built from `map`/`flatMap`, the confirmed Kyo API.
   */
  private def traverse[A, B](items: List[A])(f: A => List[B] < Sync): List[List[B]] < Sync =
    items match
      case Nil => Nil
      case head :: tail =>
        for
          b <- f(head)
          bs <- traverse(tail)(f)
        yield b :: bs

object ImaScAdapter:

  /** The enumeration moved under the run — a beach renamed between `partitions` and `fetch`. */
  final case class UnknownPartitionException(key: String)
      extends Exception(s"ima-sc: no portal beach for partition key '$key'")
      with NoStackTrace

  /**
   * The code's copy of `data/oods/sources.json`'s `ima-sc` entry; `SourcesRegistrySpec` keeps the
   * two from drifting.
   */
  val DefaultSource: Source = Source(
    id = "ima-sc",
    institute = "IMA — Instituto do Meio Ambiente de Santa Catarina",
    state = "SC",
    country = "BR",
    urls = Map(
      "export_csv" -> "https://balneabilidade.ima.sc.gov.br/relatorio/exportarCSV",
      "years" -> "https://balneabilidade.ima.sc.gov.br/registro/anosAnalisados",
      "municipalities" -> "https://balneabilidade.ima.sc.gov.br/municipio/getMunicipios",
      "beaches" -> "https://balneabilidade.ima.sc.gov.br/local/getLocaisByMunicipio",
      "points" -> "https://balneabilidade.ima.sc.gov.br/relatorio/mapa",
      "index" -> "https://balneabilidade.ima.sc.gov.br/",
      "bulletin" -> "https://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF"
    ),
    cadence = "weekly in season, monthly off season",
    licence = "none granted; public administrative information (LAI, Lei 12.527/2011)"
  )

  /** `data/oods/raw/…` of MIP-0056 §5.1 — the path a `ParseError` and the manifest name. */
  def rawPath(p: Partition): String =
    s"raw/${p.sourceId}/${p.channel.label}/${p.key}/${p.year}.csv"

  def parseYears(body: String): List[Int] =
    JsonValue.parse(body).arr.toList.flatMap(_("ANO").str.flatMap(_.trim.toIntOption))

  /**
   * Both `getMunicipios` and `getLocaisByMunicipio` key their rows by `CODIGO`, which is a name.
   */
  def parseCodes(body: String): List[String] =
    JsonValue.parse(body).arr.toList.flatMap(_("CODIGO").str)

  def parsePoints(source: Source, body: String): List[PointRow] =
    JsonValue.parse(body).arr.toList.flatMap(point(source, _))

  private def point(source: Source, p: JsonValue): Option[PointRow] =
    for
      code <- p("CODIGO").str
      municipality <- p("MUNICIPIO").str
      beach <- p("BALNEARIO").str
    yield PointRow(
      sourceId = source.id,
      pointKey = code,
      country = source.country,
      state = source.state,
      municipality = municipality,
      ibgeCode = p("MUNICIPIO_COD_IBGE").str.map(_.trim).filter(_.nonEmpty),
      beachName = beach,
      pointName = p("PONTO_NOME").str.getOrElse(""),
      locationDesc = p("LOCALIZACAO").str.map(_.trim).filter(_.nonEmpty),
      lat = p("LATITUDE").str.flatMap(_.trim.toDoubleOption),
      lon = p("LONGITUDE").str.flatMap(_.trim.toDoubleOption),
      geoSource = GeoSource.Feed,
      firstSeen = None,
      lastSeen = None
    )

  private val livePost: (String, Map[String, String]) => String < Sync =
    (url, form) =>
      Http.postForm(
        url,
        form,
        timeoutSeconds = 30,
        headers = Map("User-Agent" -> Ingest.UserAgent)
      )
