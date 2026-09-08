package marola.water

import kyo.*

import marola.http.Http
import marola.model.Coordinates

/**
 * Rio de Janeiro's INEA bathing-water bulletin (MIP-0031 §4.3/§5/§11) — fetched as a live PDF and
 * parsed by `IneaPdfParser`.
 */
final class IneaRjWaterQualityClient(
    pdfEndpoint: String = IneaRjWaterQualityClient.DefaultEndpoint
) extends WaterQualityClient:

  def name: String = "INEA/RJ"

  def samplingPoints: List[SamplingPoint] < Sync =
    Http.getBytes(pdfEndpoint, timeoutSeconds = 30).map(IneaRjWaterQualityClient.toSamplingPoints)

object IneaRjWaterQualityClient:

  // Boletim N°24, 17/06/2026, "Zonas Sudoeste e Sul" — confirmed live 2026-09-07 (MIP-0031 §11).
  val DefaultEndpoint: String =
    "https://www.inea.rj.gov.br/wp-content/uploads/2026/06/Zona-sudoeste-e-Zona-sul-17-06-26.pdf"

  /**
   * Rough bounding box of Rio de Janeiro state's coast — used by `AppConfig` to auto-select this
   * provider.
   */
  def coversOrigin(origin: Coordinates): Boolean =
    origin.lat >= -23.4 && origin.lat <= -21.0 && origin.lon >= -44.9 && origin.lon <= -40.9

  /** Pure; unit-tested against the real fixture PDF (`IneaRjWaterQualityClientSpec`). */
  def toSamplingPoints(pdfBytes: Array[Byte]): List[SamplingPoint] =
    IneaPdfParser.parseTable(pdfBytes).flatMap(toSamplingPoint)

  private def toSamplingPoint(row: IneaPdfParser.Row): Option[SamplingPoint] =
    SamplingPointCoordinates.Rio.get(row.pointCode).map { coord =>
      SamplingPoint(
        row.pointCode,
        row.beachName,
        row.beachName,
        row.location,
        coord,
        List(sampleOf(row))
      )
    }

  // Same honest reading as InemaBaWaterQualityClient.sampleOf: INEA's bulletin carries only the
  // current classification, no sample date or enterococci count.
  private def sampleOf(row: IneaPdfParser.Row): WaterSample =
    WaterSample(java.time.LocalDate.now(), row.category, None, None, None)
