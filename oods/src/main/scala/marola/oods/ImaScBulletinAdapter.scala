package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate}
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.oods.Channel
import marola.water.{ImaScPdfParser, ImaScPdfWaterQualityClient, WaterQualityMatcher}

/**
 * IMA/SC's weekly bulletin PDF as a second channel of the same source (MIP-0056 §4.2). It prints a
 * verdict and a collection date per point and no indicator count, so it fills the weeks the CSV
 * export has not caught up with; `bulletins.sql` drops a day the export already carries.
 *
 * `get` is injected for the reason `ImaScAdapter`'s `post` is: `Http` is an object with no seam.
 * `registry` is the CSV adapter's points feed, reused rather than parsed a second way.
 */
final class ImaScBulletinAdapter(
    val source: Source = ImaScAdapter.DefaultSource,
    get: String => Array[Byte] < Sync = ImaScBulletinAdapter.liveGet,
    registry: SourceAdapter = ImaScAdapter(),
    now: () => Instant = () => Instant.now()
) extends SourceAdapter:

  private val cachedIndex = AtomicReference[Option[Map[(String, String), PointRow]]](None)
  private val cachedArchive = AtomicReference[Map[String, String]](Map.empty)

  def partitions(plan: Plan): List[Partition] < Sync =
    // A bulletin covers the whole state, so `--city` cannot narrow it; `--state`/`--source` can.
    if !serves(plan) then Nil
    else
      for
        index <- get(source.urls("index")).map(String(_, UTF_8))
        archive <- archived(plan.mode)
        _ <- Sync.defer(cachedArchive.set(archive))
      yield
        val linked = ImaScPdfWaterQualityClient.bulletinDates(index).map(_.toString)
        (linked ++ archive.keys).distinct.sorted.map { date =>
          // A dated bulletin is a published document: fetched once, never revisited.
          Partition(source.id, Channel.Pdf, date, date.take(4).toInt, immutable = true)
        }

  def fetch(p: Partition): RawFile < Sync =
    val url = cachedArchive.get.getOrElse(p.key, s"${source.urls("bulletin")}/${p.key}")
    for
      bytes <- get(url)
      at <- Sync.defer(now())
    yield RawFile(p, url, bytes, at)

  /** Same contract as `ImaScAdapter.rows`: without the registry every row would key by slug. */
  def rows(raw: RawFile): Either[ParseError, List[SampleRow]] =
    val path = rawPath(raw.partition)
    cachedIndex.get match
      case None => Left(ParseError(path, "points registry not loaded — fetch `points` first"))
      case Some(index) =>
        for
          on <- Try(LocalDate.parse(raw.partition.key)).toEither.left.map(_ =>
            ParseError(path, s"'${raw.partition.key}' is not a bulletin date")
          )
          parsed <- Try(ImaScPdfParser.parse(raw.bytes)).toEither.left.map(t =>
            ParseError(path, s"unreadable bulletin: ${t.getClass.getSimpleName}")
          )
          // A 404 page and a changed layout both parse to nothing; neither may be stored as a
          // bulletin with no points, which a later build would read as "nobody was sampled".
          records <- Either.cond(parsed.nonEmpty, parsed, ParseError(path, "no bulletin records"))
        yield records.map(ImaScBulletinAdapter.sample(source, index, on, _))

  def points: List[PointRow] < Sync =
    registry.points.map { found =>
      cachedIndex.set(Some(ImaScBulletinAdapter.index(found)))
      found
    }

  def rawPath(p: Partition): String = s"raw/${p.sourceId}/bulletins/${p.key}.jsonl"

  override def storedBytes(raw: RawFile, rows: List[SampleRow]): Array[Byte] =
    RawStore.renderSamples(rows)

  private def archived(mode: Mode): Map[String, String] < Sync = mode match
    case Mode.Incremental => Map.empty
    case Mode.Backfill =>
      get(ImaScBulletinAdapter.CdxUrl).map(b => ImaScBulletinAdapter.archived(String(b, UTF_8)))

  private def serves(plan: Plan): Boolean =
    (plan.sources.isEmpty || plan.sources.contains(source.id)) &&
      (plan.states.isEmpty || plan.states.exists(_.equalsIgnoreCase(source.state)))

object ImaScBulletinAdapter:

  /** `collapse=original` gives one row per archived URL, `fl` trims the answer to two columns. */
  val CdxUrl: String =
    "https://web.archive.org/cdx/search/cdx" +
      "?url=balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/*" +
      "&output=json&fl=timestamp,original&collapse=original"

  private val DatedBulletin = """/downloadPDF/(\d{4}-\d{2}-\d{2})$""".r

  /**
   * Bulletin date → the archived copy. `id_` after the timestamp serves the captured bytes with no
   * Wayback banner; without it the PDF comes back wrapped in HTML. The CDX answer's own header row
   * and its two malformed captures carry no date and drop out here.
   */
  def archived(cdxJson: String): Map[String, String] =
    JsonValue
      .parse(cdxJson)
      .arr
      .toList
      .flatMap { row =>
        row.arr.toList.flatMap(_.str) match
          case timestamp :: original :: Nil =>
            DatedBulletin
              .findFirstMatchIn(original)
              .map(m => m.group(1) -> s"https://web.archive.org/web/${timestamp}id_/$original")
          case _ => None
      }
      .toMap

  /**
   * The bulletin names a beach and a point and no municipality, and (beach, point) is unique across
   * all 260 feed points — the CSV's (municipio, beach, point) triple would only lose matches here,
   * because the bulletin's spelling ("PRAIA DO BALN. CAMBORIÚ") is not the portal database's.
   */
  private def index(points: List[PointRow]): Map[(String, String), PointRow] =
    points
      .sortBy(_.pointKey)
      .map(p => (norm(p.beachName), norm(p.pointName)) -> p)
      .toMap

  /** Two segments, where the CSV's slug key has three: the two shapes can never collide. */
  private def pointKey(source: Source, beach: String, point: String): String =
    s"${source.id}:${ImaScCsv.slug(beach)}/${ImaScCsv.slug(point)}"

  private def sample(
      source: Source,
      index: Map[(String, String), PointRow],
      bulletin: LocalDate,
      row: ImaScPdfParser.Row
  ): SampleRow =
    SampleRow(
      sourceId = source.id,
      pointKey = index
        .get((norm(row.beachName), norm(row.pointName)))
        .map(_.pointKey)
        .getOrElse(pointKey(source, row.beachName, row.pointName)),
      sampledOn = row.collectedOn,
      sampledAt = None,
      condition = row.condition,
      indicator = Indicator.Unknown,
      indicatorValue = None,
      qualifier = Qualifier.Exact,
      rain = None,
      wind = None,
      tide = None,
      waterTempC = None,
      airTempC = None,
      channel = Channel.Pdf,
      bulletinDate = Some(bulletin)
    )

  private def norm(s: String): String = WaterQualityMatcher.normalise(s)

  private val liveGet: String => Array[Byte] < Sync =
    url => Http.getBytes(url, timeoutSeconds = 60, headers = Map("User-Agent" -> Ingest.UserAgent))
