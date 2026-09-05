package marola.conditions

import java.time.LocalDateTime

import marola.model.HourlyConditions

/** A high or low water, read off Open-Meteo's hourly `sea_level_height_msl` series. */
final case class TideEvent(time: LocalDateTime, heightM: Double, isHigh: Boolean)

/**
 * Tide turns as local extrema of the hourly sea-level series — no tide-table API, no harmonic
 * model, just "this hour is higher than both neighbours". Hourly resolution means a turn is
 * reported to the nearest hour (real high water may be ±30 min off), which is what the detailed
 * block promises (MIP-0001 §3/§7). Pure; expects `hours` in chronological order, as
 * `OpenMeteoClient` returns them.
 */
object Tides:

  def extrema(hours: List[HourlyConditions]): List[TideEvent] =
    val series = hours.flatMap(h => h.seaLevelM.map(level => (h.time, level)))
    series
      .sliding(3)
      .collect {
        case List((_, before), (t, level), (_, after)) if level > before && level >= after =>
          TideEvent(t, level, isHigh = true)
        case List((_, before), (t, level), (_, after)) if level < before && level <= after =>
          TideEvent(t, level, isHigh = false)
      }
      .toList
