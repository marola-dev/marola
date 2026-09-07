package marola.scoring

import java.time.LocalDate
import java.time.format.DateTimeFormatter

import marola.model.{HourlyConditions, JellyfishRisk, WhaleSightingLikelihood, WindLevel}
import marola.water.{BathingCondition, WaterQuality}

/**
 * The bathing-water contribution to a beach's score (MIP-0001 §6): `delta` is added like any other
 * deduction, `veto` forces the score to 0 regardless, `note` (if any) joins the notes list, and
 * `summary` is the always-present short text for the ranked list's water column.
 */
final case class WaterVerdict(delta: Int, veto: Boolean, note: Option[String], summary: String)

object WaterVerdict:
  val NoData: WaterVerdict = WaterVerdict(0, veto = false, None, "no data")

/**
 * Pure decision logic over one already-fetched hourly forecast slice — no I/O, no Kyo effect type,
 * trivially unit-testable (see `SwimabilitySpec`). Same split as `AGENTS.md`'s code-style rule:
 * fetch the data at the effect boundary, score it here.
 *
 * The jellyfish part is a heuristic, not a forecast: there is no free (or, as far as could be
 * found, any) public jellyfish-bloom API. The signals used below — warm sea surface temperature,
 * weak wind, calm seas, weak current — are the commonly cited ecological correlates for jellyfish
 * accumulating near shore, not a validated model. Treat `JellyfishRisk` as "worth a visual check
 * before wading in", not a guarantee either way. See docs/ARCHITECTURE.md's "Future work" section
 * for the plan to calibrate this against real user-reported sightings.
 */
object Swimability:

  private val ComfortableSeaTempMinC = 20.0
  private val ComfortableSeaTempMaxC = 27.0
  private val CalmWaveHeightM = 0.6
  private val RoughWaveHeightM = 1.5
  private val CalmWindKmh = 15.0
  private val StrongWindKmh = 30.0
  private val JellyfishWarmSeaTempC = 24.0
  private val JellyfishCalmCurrentKmh = 2.0
  private val HeavyRainChancePct = 60.0

  // Humpback whales migrate along the Brazilian coast to breed/calve in warmer water roughly
  // July-November (austral winter/spring), peaking around August-September — e.g. Instituto
  // Baleia Jubarte's Abrolhos-bank season runs July-November. This is a calendar fact, not
  // per-hour data, so it's the one signal below that doesn't come from Open-Meteo.
  private val WhaleSeasonMonths: Set[Int] = Set(7, 8, 9, 10, 11)
  private val WhaleCalmWaveHeightM = 1.0 // rougher than the swim-comfort threshold: you just
  private val WhaleCalmWindKmh = 20.0 // need to *spot* a blow/breach, not swim in it

  def isWhaleSeason(time: java.time.LocalDateTime): Boolean =
    WhaleSeasonMonths.contains(time.getMonthValue)

  def jellyfishRisk(hour: HourlyConditions): JellyfishRisk =
    val signals = List(
      hour.seaTempC.exists(_ >= JellyfishWarmSeaTempC),
      hour.windSpeedKmh.exists(_ <= CalmWindKmh),
      hour.waveHeightM.exists(_ <= CalmWaveHeightM),
      hour.currentVelocityKmh.exists(_ <= JellyfishCalmCurrentKmh)
    )
    signals.count(identity) match
      case n if n >= 3 => JellyfishRisk.High
      case 2           => JellyfishRisk.Moderate
      case _           => JellyfishRisk.Low

  /**
   * Informational only — unlike `jellyfishRisk`, this never feeds into `score`: whether you might
   * spot a whale doesn't make an hour more or less safe/pleasant to swim in. Daylight is a hard
   * requirement (you can't spot a blow/breach in the dark), then in-season + calm-enough seas
   * upgrade it from Low.
   */
  def whaleSightingLikelihood(hour: HourlyConditions): WhaleSightingLikelihood =
    val inSeason = WhaleSeasonMonths.contains(hour.time.getMonthValue)
    if !inSeason || !hour.isDaylight.contains(true) then WhaleSightingLikelihood.Low
    else
      val goodVisibility = List(
        hour.windSpeedKmh.exists(_ <= WhaleCalmWindKmh),
        hour.waveHeightM.exists(_ <= WhaleCalmWaveHeightM)
      ).count(identity)
      goodVisibility match
        case 2 => WhaleSightingLikelihood.High
        case 1 => WhaleSightingLikelihood.Moderate
        case _ => WhaleSightingLikelihood.Low

  private def waveDelta(hour: HourlyConditions): (Int, Option[String]) =
    hour.waveHeightM match
      case Some(h) if h >= RoughWaveHeightM => (-40, Some(f"rough seas ($h%.1fm waves)"))
      case Some(h) if h >= CalmWaveHeightM  => (-15, Some(f"choppy ($h%.1fm waves)"))
      case Some(_)                          => (0, None)
      case None                             => (-5, Some("no wave data"))

  /**
   * The one place the wind thresholds turn into a band. `windDelta` scores from it and the board
   * publishes it as `wind_level` (MIP-0009 §5), so the CLI's note and the map's word can't drift.
   */
  def windLevel(kmh: Option[Double]): Option[WindLevel] =
    kmh.map {
      case w if w >= StrongWindKmh => WindLevel.Strong
      case w if w >= CalmWindKmh   => WindLevel.Breezy
      case _                       => WindLevel.Calm
    }

  private def windDelta(hour: HourlyConditions): (Int, Option[String]) =
    (windLevel(hour.windSpeedKmh), hour.windSpeedKmh) match
      case (Some(WindLevel.Strong), Some(w)) => (-25, Some(f"strong wind (${w}%.0fkm/h)"))
      case (Some(WindLevel.Breezy), Some(w)) => (-10, Some(f"breezy (${w}%.0fkm/h)"))
      case (Some(WindLevel.Calm), _)         => (0, None)
      case _                                 => (-5, Some("no wind data"))

  private def seaTempDelta(hour: HourlyConditions): (Int, Option[String]) =
    hour.seaTempC match
      case Some(t) if t < ComfortableSeaTempMinC => (-20, Some(f"cold water (${t}%.1f°C)"))
      case Some(t) if t > ComfortableSeaTempMaxC => (-5, Some(f"warm water (${t}%.1f°C)"))
      case Some(_)                               => (0, None)
      case None                                  => (-5, Some("no sea temperature data"))

  private def precipitationDelta(hour: HourlyConditions): (Int, Option[String]) =
    hour.precipitationProbabilityPct match
      case Some(p) if p >= HeavyRainChancePct => (-10, Some(f"${p}%.0f%% chance of rain"))
      case _                                  => (0, None)

  /**
   * Night is not a swim slot: no lifeguards, no visibility, no way to check the shoreline for
   * jellyfish. A heavy penalty rather than a veto so a beach with data only for dark hours still
   * ranks somewhere; in practice any daylight hour beats any dark one. Before this every hour tied
   * on a flat day and `00:00` "won" by being first (RUN-LOCALLY.md's own sample output).
   */
  private val DarkPenalty = -60

  private def daylightDelta(hour: HourlyConditions): (Int, Option[String]) =
    hour.isDaylight match
      case Some(false) => (DarkPenalty, Some("dark"))
      case _           => (0, None)

  /**
   * Tie-breaker among equally scored hours: distance from 10:00. Mid-morning is when lifeguard
   * posts are staffed, the light is best and the sea is usually calmest before the afternoon wind —
   * so a flat forecast recommends 10:00, not 06:00 or 17:00. Lower is better.
   */
  def hourPreference(hour: HourlyConditions): Int =
    math.abs(hour.time.getHour - 10)

  private def jellyfishDelta(hour: HourlyConditions): (Int, Option[String]) =
    jellyfishRisk(hour) match
      case JellyfishRisk.High     => (-25, Some("elevated jellyfish likelihood"))
      case JellyfishRisk.Moderate => (-10, Some("some jellyfish likelihood"))
      case JellyfishRisk.Low      => (0, None)

  private val MixedWaterPenalty = -20
  private val sampleDateFormat = DateTimeFormatter.ofPattern("d MMM")

  /**
   * MIP-0001 §6, verbatim: every fresh matched point IMPRÓPRIA → veto (score 0); some IMPRÓPRIA →
   * −20 and name the spots to avoid; all PRÓPRIA → nothing; no match / provider `none` → nothing
   * (absence of data is not evidence of pollution); every sample older than 45 days → treated as no
   * data, but say so. Uses the agency's own classification (`condition`), never the raw count.
   */
  def waterVerdict(water: Option[WaterQuality], today: LocalDate): WaterVerdict =
    water match
      case None => WaterVerdict.NoData
      case Some(w) =>
        val fresh = w.fresh(today)
        if fresh.isEmpty then
          w.newestSampleDate match
            case Some(d) =>
              val when = d.format(sampleDateFormat)
              WaterVerdict(
                0,
                veto = false,
                Some(s"water quality data stale ($when)"),
                s"stale ($when)"
              )
            case None => WaterVerdict.NoData
        else
          val improper = fresh.filter { case (_, s) => s.condition == BathingCondition.Improper }
          val proper = fresh.filter { case (_, s) => s.condition == BathingCondition.Proper }
          val when = fresh.map(_._2.sampledOn).maxBy(_.toEpochDay).format(sampleDateFormat)
          if improper.nonEmpty && proper.isEmpty then
            val (point, sample) = improper.maxBy(_._2.enterococciPer100ml.getOrElse(0))
            val count =
              sample.enterococciPer100ml.map(n => s"$n enterococci/100mL").getOrElse("count n/a")
            WaterVerdict(
              0,
              veto = true,
              Some(
                s"water unfit for bathing — ${w.source} $when, ${point.pointName} (${point.location}), $count"
              ),
              s"IMPRÓPRIA (${improper.size}/${fresh.size} pts, $when)"
            )
          else if improper.nonEmpty then
            val avoid = improper.map { case (p, _) => p.location }.mkString("; ")
            WaterVerdict(
              MixedWaterPenalty,
              veto = false,
              Some(s"${proper.size}/${fresh.size} points PRÓPRIA — avoid $avoid"),
              s"${proper.size}/${fresh.size} PRÓPRIA — avoid ${improper.map(_._1.pointName).mkString(", ")} ($when)"
            )
          else
            WaterVerdict(0, veto = false, None, s"PRÓPRIA (${fresh.size}/${fresh.size} pts, $when)")

  /**
   * 0-100, higher = better conditions for open-water swimming, plus the reasons behind any
   * deduction (empty when conditions are simply good). `water` is per-beach, not per-hour
   * (MIP-0001): a veto zeroes the hour whatever the sea is doing.
   */
  def score(
      hour: HourlyConditions,
      water: WaterVerdict = WaterVerdict.NoData
  ): (Int, List[String]) =
    val deltas = List(
      waveDelta(hour),
      windDelta(hour),
      seaTempDelta(hour),
      precipitationDelta(hour),
      jellyfishDelta(hour),
      daylightDelta(hour),
      (water.delta, water.note)
    )
    val total = if water.veto then 0 else (100 + deltas.map(_._1).sum).max(0).min(100)
    (total, deltas.flatMap(_._2))
