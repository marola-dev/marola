package marola.water

import java.text.Normalizer

import kyo.*

import marola.http.Http
import marola.model.Coordinates

/**
 * Bahia's INEMA bathing-water bulletin (MIP-0031 §4.3/§5) — fetched as a live PDF (no JSON/HTML
 * feed exists for this institute, confirmed live 2026-09-07: INEMA's own search form just wraps a
 * plain `GET` to this same endpoint) and parsed by `InemaPdfParser`.
 */
final class InemaBaWaterQualityClient(
    pdfEndpoint: String = InemaBaWaterQualityClient.DefaultEndpoint
) extends WaterQualityClient:

  def name: String = "INEMA/BA"

  def samplingPoints: List[SamplingPoint] < Sync =
    Http.getBytes(pdfEndpoint, timeoutSeconds = 30).map(InemaBaWaterQualityClient.toSamplingPoints)

object InemaBaWaterQualityClient:

  // Bulletin N°13/2025, idcampanha=83453 — confirmed live 2026-09-07 (MIP-0031 §4.2).
  val DefaultEndpoint: String =
    "http://balneabilidade.inema.ba.gov.br/index.php/relatoriodebalneabilidade/geraBoletim?idcampanha=83453"

  /** Rough bounding box of Bahia's coast — used by `AppConfig` to auto-select this provider. */
  def coversOrigin(origin: Coordinates): Boolean =
    origin.lat >= -18.5 && origin.lat <= -8.5 && origin.lon >= -40.5 && origin.lon <= -37.0

  /** Pure; unit-tested against the real fixture PDF (`InemaBaWaterQualityClientSpec`). */
  def toSamplingPoints(pdfBytes: Array[Byte]): List[SamplingPoint] =
    InemaPdfParser.parseTable(pdfBytes).flatMap(toSamplingPoint)

  private def toSamplingPoint(row: InemaPdfParser.Row): Option[SamplingPoint] =
    SamplingPointCoordinates.Bahia.get(row.code).map { coord =>
      SamplingPoint(
        row.code,
        row.pointName,
        row.pointName,
        row.description,
        coord,
        List(sampleOf(row))
      )
    }

  // INEMA's bulletin carries only the current classification, not a sample date or an enterococci
  // count (unlike IMA/SC's feed) — `sampledOn` is "today" at fetch time, the honest reading of
  // "this is the bulletin's current verdict," not a real lab date the source doesn't provide.
  private def sampleOf(row: InemaPdfParser.Row): WaterSample =
    WaterSample(java.time.LocalDate.now(), condition(row.category), None, None, None)

  private def condition(raw: String): BathingCondition =
    Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase.trim match
      case s if s.startsWith("IMPR") => BathingCondition.Improper
      case s if s.startsWith("PR")   => BathingCondition.Proper
      case _                         => BathingCondition.Unknown
