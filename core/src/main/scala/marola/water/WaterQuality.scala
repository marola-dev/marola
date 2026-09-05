package marola.water

import java.time.LocalDate
import java.time.temporal.ChronoUnit

import kyo.*

import marola.model.Coordinates

/**
 * Bathing-water fitness as the monitoring agency classifies it — `Proper`/`Improper` are CONAMA
 * 274/2000's PRÓPRIA/IMPRÓPRIA (enterococci ≤ 100/100mL in 80% of the last five samples), applied
 * by the agency, not re-derived here (MIP-0001 §6/§9). `Unknown` is what a provider returns for a
 * point it lists but hasn't classified.
 */
enum BathingCondition derives CanEqual:
  case Proper, Improper, Unknown

final case class WaterSample(
    sampledOn: LocalDate,
    condition: BathingCondition,
    rain: Option[String],
    enterococciPer100ml: Option[Int],
    waterTempC: Option[Double]
)

/**
 * One monitored spot on a beach — a beach can have several (Praia do Campeche has five), and they
 * genuinely differ: a stream mouth 300m from clean water is routinely IMPRÓPRIA (MIP-0001 §2).
 * `beachName` is the provider's own name for the beach, matched to OSM's by `WaterQualityMatcher`.
 */
final case class SamplingPoint(
    id: String,
    beachName: String,
    pointName: String,
    location: String,
    coordinates: Coordinates,
    samples: List[WaterSample]
):
  def latest: Option[WaterSample] = samples.sortBy(_.sampledOn.toEpochDay).lastOption

/** Every sampling point matched to one beach, plus where the data came from. */
final case class WaterQuality(points: List[SamplingPoint], source: String):

  def latestSamples: List[(SamplingPoint, WaterSample)] =
    points.flatMap(p => p.latest.map(s => (p, s)))

  /**
   * Samples no older than `maxAgeDays` — off-season IMA samples monthly, hence the 45-day default.
   */
  def fresh(
      today: LocalDate,
      maxAgeDays: Long = WaterQuality.MaxSampleAgeDays
  ): List[(SamplingPoint, WaterSample)] =
    latestSamples.filter {
      case (_, s) => ChronoUnit.DAYS.between(s.sampledOn, today) <= maxAgeDays
    }

  def improper(today: LocalDate): List[(SamplingPoint, WaterSample)] =
    fresh(today).filter { case (_, s) => s.condition == BathingCondition.Improper }

  def proper(today: LocalDate): List[(SamplingPoint, WaterSample)] =
    fresh(today).filter { case (_, s) => s.condition == BathingCondition.Proper }

  def newestSampleDate: Option[LocalDate] =
    latestSamples.map(_._2.sampledOn).sortBy(_.toEpochDay).lastOption

object WaterQuality:
  val MaxSampleAgeDays = 45L

/**
 * A regional bathing-water data source. One implementation per agency/portal —
 * `ImaScWaterQualityClient` (Santa Catarina) in `marola-local` today; INEA/RJ, CETESB/SP would be
 * siblings. Returns the whole region in one call; `WaterQualityMatcher` does the per-beach
 * assignment.
 */
trait WaterQualityClient:
  def name: String
  def samplingPoints: List[SamplingPoint] < Sync
