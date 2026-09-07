package marola.water

import kyo.*

import marola.http.Http
import marola.model.Coordinates

/**
 * Rio de Janeiro's INEA bathing-water bulletin (MIP-0031 §4.3/§5/§11) — fetched as a live PDF and
 * parsed by `IneaPdfParser`. Coordinates come from the curated `SamplingPointCoordinates.Rio`
 * lookup, never the bulletin itself (same real blocker INEMA's client has — neither institute's PDF
 * carries coordinates). A point code absent from that table is dropped, not defaulted, same
 * discipline as `ImaScWaterQualityClient`/`InemaBaWaterQualityClient`.
 *
 * **Known, honest gap** (MIP-0031 §11, not resolved this session): INEA has no `idcampanha`-style
 * stable parameter — it publishes dated, per-zone PDFs as static uploads, discovered in principle
 * by checking INEA's own bulletin-listing page
 * (`inea.rj.gov.br/ar-agua-e-solo/balneabilidade-das-praias/`). That page was fetched live while
 * building this client and returned no server-rendered PDF links at all (a near-empty CMS template
 * — content is client-side rendered, or gated behind something this session's plain `curl` couldn't
 * see); the MIP's own research hit the same wall and found the one bulletin URL used below via a
 * web search, not by crawling the listing page. Building a scraper against page content that
 * couldn't be fetched or verified live would be exactly the kind of unverified claim this repo's
 * culture rejects — so `pdfEndpoint` is a fixed, currently-real bulletin URL instead (mirroring
 * `InemaBaWaterQualityClient`'s own honest "this will go stale" acknowledgment), and the
 * listing-page discovery step from MIP-0031 task 6's original spec remains real, unimplemented
 * future work, not faked.
 */
final class IneaRjWaterQualityClient(
    pdfEndpoint: String = IneaRjWaterQualityClient.DefaultEndpoint
) extends WaterQualityClient:

  def name: String = "INEA/RJ"

  def samplingPoints: List[SamplingPoint] < Sync =
    Http.getBytes(pdfEndpoint, timeoutSeconds = 30).map(IneaRjWaterQualityClient.toSamplingPoints)

object IneaRjWaterQualityClient:

  // Boletim N°24, 17/06/2026, "Zonas Sudoeste e Sul" — confirmed live 2026-09-07 (MIP-0031 §11).
  // Same file the fixture PDF (local/src/test/resources/
  // inea-boletim-zona-sudoeste-sul-2026-06-17.pdf) was captured from. Covers a subset of Rio's
  // zones only (INEA publishes separate PDFs per zone) — will go stale, see the class doc above.
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
