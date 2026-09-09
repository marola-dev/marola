package marola.water

import java.text.Normalizer

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.log.Log
import marola.model.Coordinates

/**
 * IMA/SC from the weekly bulletin PDF, for when the JSON feed is unreachable.
 *
 * Two sources, each useless alone. The PDF has a collection date and a verdict per point but no
 * coordinates; the HTTP JSON has coordinates for all 260 points but, unlike the HTTPS one, no
 * samples at all. Joined on the beach and point names they reconstruct what the HTTPS feed used to
 * return in one call.
 *
 * The bulletin URL is discovered from the portal's index rather than hardcoded — the mistake that
 * left Rio pinned to a June PDF until its samples aged past the 45-day rule and the area went dark
 * with nothing to say so.
 */
final class ImaScPdfWaterQualityClient(
    indexUrl: String = ImaScPdfWaterQualityClient.DefaultIndexUrl,
    pointsUrl: String = ImaScPdfWaterQualityClient.DefaultPointsUrl
) extends WaterQualityClient:

  import ImaScPdfWaterQualityClient.*

  def name: String = "IMA/SC"

  def samplingPoints: List[SamplingPoint] < Sync =
    for
      index <- Http.getString(indexUrl)
      bulletin <- latestBulletinUrl(index) match
        case Some(url) => Http.getBytes(url, timeoutSeconds = 60).map(Some(_))
        case None =>
          log.warn(s"no bulletin link found at $indexUrl — the portal's index may have changed")
          Sync.defer(None)
      pointsJson <- Http.postForm(pointsUrl, Map.empty, timeoutSeconds = 30)
    yield bulletin
      .map(b => join(ImaScPdfParser.parse(b), JsonValue.parse(pointsJson)))
      .getOrElse(Nil)

object ImaScPdfWaterQualityClient:

  private val log = Log.forName(getClass.getName)

  val DefaultIndexUrl = "http://balneabilidade.ima.sc.gov.br/"
  val DefaultPointsUrl = "http://balneabilidade.ima.sc.gov.br/relatorio/mapa"

  private val BulletinLink = """/relatorio/downloadPDF/(\d{4}-\d{2}-\d{2})""".r

  /** The newest dated bulletin the index links to. Dates sort lexicographically in ISO form. */
  def latestBulletinUrl(indexHtml: String): Option[String] =
    BulletinLink
      .findAllMatchIn(indexHtml)
      .map(_.group(1))
      .toList
      .distinct
      .sorted
      .lastOption
      .map(d => s"http://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/$d")

  /**
   * Accent- and case-insensitive, because the PDF shouts ("PRAIA DO CAMPECHE") and the JSON does
   * not ("Praia do Campeche").
   */
  private def fold(s: String): String =
    Normalizer
      .normalize(s, Normalizer.Form.NFD)
      .replaceAll("\\p{M}", "")
      .toUpperCase
      .replaceAll("\\s+", " ")
      .trim

  private def key(beach: String, point: String): String = s"${fold(beach)}|${fold(point)}"

  /**
   * PDF verdicts onto JSON coordinates. A point present in only one source is dropped rather than
   * guessed at: a verdict with no location cannot be shown on a map, and a location with no verdict
   * is what "no data" already means.
   */
  def join(rows: List[ImaScPdfParser.Row], pointsJson: JsonValue): List[SamplingPoint] =
    val byKey = rows.map(r => key(r.beachName, r.pointName) -> r).toMap
    val joined = pointsJson.arr.toList.flatMap { p =>
      for
        id <- p("CODIGO").str
        beach <- p("BALNEARIO").str
        point <- p("PONTO_NOME").str
        lat <- p("LATITUDE").str.flatMap(_.trim.toDoubleOption)
        lon <- p("LONGITUDE").str.flatMap(_.trim.toDoubleOption)
        row <- byKey.get(key(beach, point))
      yield SamplingPoint(
        id,
        beach,
        point,
        p("LOCALIZACAO").str.getOrElse(""),
        Coordinates(lat, lon),
        List(WaterSample(row.collectedOn, row.condition, None, None, None))
      )
    }
    if joined.isEmpty && rows.nonEmpty then
      log.warn(
        s"parsed ${rows.size} bulletin rows but matched none to a point — name formats may have diverged"
      )
    joined
