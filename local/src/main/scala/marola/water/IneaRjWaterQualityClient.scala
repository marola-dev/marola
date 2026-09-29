package marola.water

import java.time.{LocalDate, ZoneId}

import scala.util.Try

import kyo.*

import marola.http.Http
import marola.log.Log
import marola.model.Coordinates

/**
 * INEA's bathing-water bulletins for the state of Rio de Janeiro (MIP-0031, #488): each city page
 * links the latest dated PDF per zone, found there rather than pinned, parsed by `IneaPdfParser`.
 */
final class IneaRjWaterQualityClient(
    cityPages: List[String] = IneaRjWaterQualityClient.CityPages,
    today: () => LocalDate = () => LocalDate.now(ZoneId.of("America/Sao_Paulo"))
) extends WaterQualityClient:

  import IneaRjWaterQualityClient.*

  def name: String = "INEA/RJ"

  def samplingPoints: List[SamplingPoint] < Sync =
    Kyo.foreach(cityPages)(pagePoints).map(_.toList.flatten)

  private def pagePoints(page: String): List[SamplingPoint] < Sync =
    orNothing(page) {
      Http.getString(page).map { html =>
        val found = bulletins(html)
        if found.isEmpty then log.warn(s"INEA: no dated bulletin link on $page — no points from it")
        // A zone INEA stopped updating (Sepetiba, last bulletin 28-11-24) would only be thrown away
        // by the 45-day rule after download, so it is not downloaded; it returns when INEA resumes.
        val (current, stale) = found.partition(b => WaterQuality.isFresh(b.date, today()))
        stale.foreach(b => log.info(s"INEA: skipping ${b.url}, dated ${b.date}, past 45 days"))
        Kyo.foreach(current)(bulletinPoints).map(_.toList.flatten)
      }
    }

  private def bulletinPoints(b: Bulletin): List[SamplingPoint] < Sync =
    orNothing(b.url)(Http.getBytes(b.url, timeoutSeconds = 30).map(toSamplingPoints(_, b.date)))

  // One zone or city failing (a timeout, a 404, an unreadable PDF) costs only its own points; the
  // rest still count, and an all-empty result falls through to CachedWaterQualityClient.
  private def orNothing(what: String)(
      points: => List[SamplingPoint] < Sync
  ): List[SamplingPoint] < Sync =
    Abort.run(Abort.catching[Throwable](points)).map {
      case Result.Success(ps) => ps
      case other =>
        log.warn(s"INEA: $what failed ($other) — no points from it")
        Nil
    }

object IneaRjWaterQualityClient:

  private val log = Log.forName(getClass.getName)

  val CityPages: List[String] = List(
    "https://www.inea.rj.gov.br/rio-de-janeiro/",
    "https://www.inea.rj.gov.br/niteroi/"
  )

  final case class Bulletin(url: String, date: LocalDate)

  // `Zona-sudoeste-e-Zona-sul-21-09-26.pdf`: the "Último Boletim Divulgado" links are the only ones
  // named -DD-MM-YY; the historico and Qualificação PDFs next to them are not.
  private val DatedPdfLink = """href="([^"]+-(\d{2})-(\d{2})-(\d{2})\.pdf)"""".r

  /**
   * The filename date, not the PDF's own header: Niterói's 24-09-26 bulletin is headed "2025".
   */
  def bulletins(pageHtml: String): List[Bulletin] =
    DatedPdfLink
      .findAllMatchIn(pageHtml)
      .flatMap { m =>
        Try(LocalDate.of(2000 + m.group(4).toInt, m.group(3).toInt, m.group(2).toInt)).toOption
          .map(Bulletin(m.group(1), _))
      }
      .toList
      .distinct

  /**
   * Rough bounding box of Rio de Janeiro state's coast — used by `AppConfig` to auto-select this
   * provider.
   */
  def coversOrigin(origin: Coordinates): Boolean =
    origin.lat >= -23.4 && origin.lat <= -21.0 && origin.lon >= -44.9 && origin.lon <= -40.9

  /** Pure; unit-tested against the real fixture PDFs (`IneaRjWaterQualityClientSpec`). */
  def toSamplingPoints(pdfBytes: Array[Byte], bulletinDate: LocalDate): List[SamplingPoint] =
    IneaPdfParser.parseTable(pdfBytes).flatMap(toSamplingPoint(_, bulletinDate))

  private def toSamplingPoint(
      row: IneaPdfParser.Row,
      bulletinDate: LocalDate
  ): Option[SamplingPoint] =
    SamplingPointCoordinates.Rio.get(row.pointCode).map { coord =>
      SamplingPoint(
        row.pointCode,
        row.beachName,
        row.beachName,
        row.location,
        coord,
        List(WaterSample(bulletinDate, row.category, None, None, None))
      )
    }
