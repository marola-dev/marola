package marola.water

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import scala.util.Try

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.model.Coordinates

/**
 * Santa Catarina's bathing-water programme (IMA — Instituto do Meio Ambiente), via the JSON feed
 * the portal's own map uses: `POST /relatorio/mapa`, empty body, no auth, ~207KB for all 260 points
 * with their last five samples. Verified live on 2026-09-05 (MIP-0001 §4.1); it is
 * **undocumented**, so every field read below is tolerant — a malformed point or sample is dropped,
 * never fatal — and the whole call is `Abort.catching`-wrapped by `Recommender`.
 *
 * Off-season (April-September) the full coast is sampled monthly, so
 * `WaterQuality.MaxSampleAgeDays` (45) is what keeps a two-month-old PRÓPRIA from looking current.
 */
final class ImaScWaterQualityClient(endpoint: String = ImaScWaterQualityClient.DefaultEndpoint)
    extends WaterQualityClient:

  def name: String = "IMA/SC"

  def samplingPoints: List[SamplingPoint] < Sync =
    Http
      .postForm(endpoint, Map.empty, timeoutSeconds = 30)
      .map(body => ImaScWaterQualityClient.parse(JsonValue.parse(body)))

object ImaScWaterQualityClient:

  val DefaultEndpoint = "https://balneabilidade.ima.sc.gov.br/relatorio/mapa"

  /**
   * Rough bounding box of Santa Catarina's coast — used by `AppConfig` to auto-select this
   * provider.
   */
  def coversOrigin(origin: Coordinates): Boolean =
    origin.lat >= -29.4 && origin.lat <= -25.9 && origin.lon >= -53.9 && origin.lon <= -48.3

  private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

  /** Pure; unit-tested on a trimmed real payload (`ImaScWaterQualityClientSpec`). */
  def parse(json: JsonValue): List[SamplingPoint] =
    json.arr.toList.flatMap(parsePoint)

  // The feed sends numbers as strings ("-27.4261029", "197"); tolerate real numbers too.
  private def text(v: JsonValue): Option[String] =
    v.str.orElse(v.num.map(n => if n == n.toLong then n.toLong.toString else n.toString))

  private def parsePoint(p: JsonValue): Option[SamplingPoint] =
    for
      id <- text(p("CODIGO"))
      beach <- p("BALNEARIO").str
      lat <- text(p("LATITUDE")).flatMap(_.trim.toDoubleOption)
      lon <- text(p("LONGITUDE")).flatMap(_.trim.toDoubleOption)
    yield SamplingPoint(
      id,
      beach,
      p("PONTO_NOME").str.getOrElse(""),
      p("LOCALIZACAO").str.getOrElse(""),
      Coordinates(lat, lon),
      p("ANALISES").arr.toList.flatMap(parseSample)
    )

  private def parseSample(a: JsonValue): Option[WaterSample] =
    for
      raw <- a("DATA").str
      date <- Try(LocalDate.parse(raw.trim, dateFormat)).toOption
    yield WaterSample(
      date,
      condition(a("CONDICAO").str),
      a("CHUVA").str,
      text(a("RESULTADO")).flatMap(_.trim.toIntOption),
      text(a("TEMP_AGUA")).flatMap(_.trim.toDoubleOption)
    )

  private def condition(raw: Option[String]): BathingCondition =
    raw.map(s =>
      Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase.trim
    ) match
      case Some(s) if s.startsWith("IMPR") => BathingCondition.Improper
      case Some(s) if s.startsWith("PR")   => BathingCondition.Proper
      case _                               => BathingCondition.Unknown
