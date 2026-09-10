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
 * the portal's own map uses: `POST /relatorio/mapa`, empty body, no auth.
 *
 * The payload has two shapes and both are parsed. It used to carry each point's last five samples
 * in `ANALISES` (~207KB for 260 points); as of 2026-09-10 it sends one `CONDICAO` per point and no
 * history at all (~85KB), which is why `currentCondition` exists.
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

  /**
   * Pure given `today`, which only the `CONDICAO` fallback reads (`.claude/rules/scala.md`: inject
   * the clock, don't reach for a global). Unit-tested on trimmed real payloads of both feed shapes.
   */
  def parse(json: JsonValue, today: () => LocalDate = () => LocalDate.now()): List[SamplingPoint] =
    json.arr.toList.flatMap(parsePoint(_, today))

  // The feed sends numbers as strings ("-27.4261029", "197"); tolerate real numbers too.
  private def text(v: JsonValue): Option[String] =
    v.str.orElse(v.num.map(n => if n == n.toLong then n.toLong.toString else n.toString))

  private def parsePoint(p: JsonValue, today: () => LocalDate): Option[SamplingPoint] =
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
      p("ANALISES").arr.toList.flatMap(parseSample) match
        case Nil     => currentCondition(p, today).toList
        case samples => samples
    )

  /**
   * The feed stopped sending `ANALISES` between 2026-09-05 and 2026-09-10, leaving one `CONDICAO`
   * per point and no sample history. Without this, all 260 points parse with no samples at all and
   * every Florianópolis beach reads "no data" while the board still names IMA/SC.
   *
   * Same honest reading as `IneaRjWaterQualityClient.sampleOf`: the agency's current
   * classification, dated today because the feed no longer says when it sampled. The verdict is
   * still the agency's, shown verbatim (MIP-0001 §6/§9); only its date is ours.
   */
  private def currentCondition(p: JsonValue, today: () => LocalDate): Option[WaterSample] =
    p("CONDICAO").str
      .map(raw => WaterSample(today(), condition(Some(raw)), None, None, None))

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
