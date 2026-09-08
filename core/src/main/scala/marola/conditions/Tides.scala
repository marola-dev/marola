package marola.conditions

import java.time.LocalDateTime

import marola.model.HourlyConditions

/** A high or low water, read off Open-Meteo's hourly `sea_level_height_msl` series. */
final case class TideEvent(time: LocalDateTime, heightM: Double, isHigh: Boolean)

/**
 * Tide turns as local extrema of the hourly sea-level series — no tide-table API, no harmonic
 * model, just "this hour is higher than both neighbours".
 */
object Tides:

  /**
   * A turn has to move the water by at least this much from the previous kept turn; anything
   * smaller is hourly-sampling noise (a real run printed "high 21:00 (+0.4m), low 22:00 (+0.4m)").
   */
  val MinRangeM = 0.1

  def extrema(hours: List[HourlyConditions]): List[TideEvent] =
    val series = hours.flatMap(h => h.seaLevelM.map(level => (h.time, level)))
    val candidates = series
      .sliding(3)
      .collect {
        case List((_, before), (t, level), (_, after)) if level > before && level >= after =>
          TideEvent(t, level, isHigh = true)
        case List((_, before), (t, level), (_, after)) if level < before && level <= after =>
          TideEvent(t, level, isHigh = false)
      }
      .toList
    // Keep alternating highs/lows with a real range between them: a same-type candidate replaces
    // the kept one if more extreme; an opposite-type candidate too close in height is dropped.
    candidates
      .foldLeft(List.empty[TideEvent]) { (kept, c) =>
        kept match
          case Nil => List(c)
          case last :: rest if last.isHigh == c.isHigh =>
            val moreExtreme =
              if c.isHigh then c.heightM > last.heightM else c.heightM < last.heightM
            if moreExtreme then c :: rest else kept
          case last :: _ if math.abs(c.heightM - last.heightM) < MinRangeM => kept
          case _                                                           => c :: kept
      }
      .reverse
