package marola

import java.time.LocalDate
import java.time.format.DateTimeFormatter

import marola.knowledge.OceanQa
import marola.lore.{LoreEntry, SeaLore}
import marola.model.{BestHour, Coordinates, WhaleSightingLikelihood}
import marola.scoring.Swimability
import marola.water.BathingCondition

/**
 * Pure text rendering for `Main` (MIP-0001 §3): the ranked list (with its water column), the
 * detailed block for the top pick, and the sea-lore paragraph. Kept out of `Main` so it's plain
 * functions over `BestHour` — no effects, no config — and so the MCP server can reuse the same
 * water-quality summary text.
 */
object Report:

  private val hourFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
  private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
  private val dateFormat = DateTimeFormatter.ofPattern("d MMM")

  /** "Today" in the beach's own zone: the forecast hour is tomorrow there by construction. */
  def todayFor(best: BestHour): LocalDate = best.hour.time.toLocalDate.minusDays(1)

  def waterSummary(best: BestHour): String =
    Swimability.waterVerdict(best.waterQuality, todayFor(best)).summary

  /** The pre-MIP one-liner (`--brief`). */
  def briefLine(rank: Int, best: BestHour): String =
    val when = best.hour.time.format(hourFormat)
    val dist = f"${best.beach.distanceKm}%.1fkm away"
    val temp = best.hour.seaTempC.map(t => f"$t%.1f°C sea").getOrElse("sea temp n/a")
    val wind = best.hour.windSpeedKmh.map(w => f"$w%.0fkm/h wind").getOrElse("wind n/a")
    val notes = if best.notes.isEmpty then "good conditions" else best.notes.mkString(", ")
    val whale =
      if best.whaleSightingLikelihood == WhaleSightingLikelihood.Low then ""
      else s"  |  whale sighting: ${best.whaleSightingLikelihood}"
    f"${rank}%2d. [${best.score}%3d/100] ${best.beach.name}%-22s ($dist)  best at $when  |  $temp, $wind  |  jellyfish: ${best.jellyfishRisk}$whale  |  $notes"

  /** Ranked-list line with the water column. */
  def line(rank: Int, best: BestHour): String =
    val when = best.hour.time.format(hourFormat)
    val temp = best.hour.seaTempC.map(t => f"$t%.1f°C").getOrElse("n/a")
    val wind = best.hour.windSpeedKmh.map(w => f"$w%.0fkm/h").getOrElse("n/a")
    val waves = best.hour.waveHeightM.map(h => f"$h%.1fm").getOrElse("n/a")
    val whale =
      if best.whaleSightingLikelihood == WhaleSightingLikelihood.Low then ""
      else s"  |  whales: ${best.whaleSightingLikelihood}"
    // The water column already carries the verdict; don't repeat its note in the inline notes
    // (`--brief`, the detail block and MCP still see it via `best.notes`).
    val verdict = Swimability.waterVerdict(best.waterQuality, todayFor(best))
    val inline = best.notes.filterNot(n => verdict.note.contains(n))
    val notes = if inline.isEmpty then "" else s"  |  ${inline.mkString(", ")}"
    f"${rank}%2d. [${best.score}%3d/100] ${best.beach.name}%-22s (${best.beach.distanceKm}%.1fkm)  $when  |  water: ${verdict.summary}  |  $temp, $wind, $waves  |  jellyfish: ${best.jellyfishRisk}$whale$notes"

  def detail(best: BestHour): String =
    val h = best.hour
    val today = todayFor(best)
    val header =
      s"Top pick — ${best.beach.name}, ${h.time.format(hourFormat)}-${h.time.plusHours(1).format(timeFormat)}"

    val water = best.waterQuality match
      case None => List(s"no data — ${Swimability.waterVerdict(None, today).summary}")
      case Some(wq) =>
        val verdict = Swimability.waterVerdict(best.waterQuality, today)
        val points = wq.latestSamples.sortBy { case (p, _) => p.pointName }.map {
          case (p, s) =>
            val cond = s.condition match
              case BathingCondition.Proper   => "PRÓPRIA"
              case BathingCondition.Improper => "IMPRÓPRIA"
              case BathingCondition.Unknown  => "unclassified"
            val count = s.enterococciPer100ml.map(n => s", $n enterococci/100mL").getOrElse("")
            val rain = s.rain.map(r => s", rain $r").getOrElse("")
            val temp = s.waterTempC.map(t => f", water $t%.0f°C").getOrElse("")
            s"${p.pointName} (${p.location}): $cond, ${s.sampledOn.format(dateFormat)}$count$rain$temp"
        }
        (verdict.summary +: points) :+ s"Source: ${wq.source}"

    val sea =
      val temp = h.seaTempC.map(t => f"$t%.1f°C").getOrElse("temp n/a")
      val waves = (h.waveHeightM, h.wavePeriodS) match
        case (Some(wh), Some(wp)) => f"waves $wh%.1fm every $wp%.0fs"
        case (Some(wh), None)     => f"waves $wh%.1fm"
        case _                    => "waves n/a"
      val from = h.waveDirectionDeg.map(d => s" from the ${compass(d)}").getOrElse("")
      val swell = (h.swellWaveHeightM, h.swellWavePeriodS) match
        case (Some(sh), Some(sp)) => f", swell $sh%.1fm/$sp%.0fs"
        case (Some(sh), None)     => f", swell $sh%.1fm"
        case _                    => ""
      val current = h.currentVelocityKmh.map(c => f", current $c%.1f km/h").getOrElse("")
      s"$temp, $waves$from$swell$current"

    val tide =
      if best.dayTides.isEmpty then "no sea-level data"
      else
        best.dayTides
          .map(t =>
            f"${if t.isHigh then "high" else "low"} ${t.time.format(timeFormat)} (${t.heightM}%+.1fm)"
          )
          .mkString(", ") + " (hourly resolution, ±30 min)"

    val air =
      val t = h.airTempC.map(t => f"$t%.0f°C").getOrElse("temp n/a")
      val w = h.windSpeedKmh.map(w => f"wind $w%.0f km/h").getOrElse("wind n/a")
      val wd = h.windDirectionDeg.map(d => s" from the ${compass(d)}").getOrElse("")
      val uv = h.uvIndex.map(u => f", UV $u%.0f").getOrElse("")
      val rain = h.precipitationProbabilityPct.map(p => f", $p%.0f%% chance of rain").getOrElse("")
      s"$t, $w$wd$uv$rain"

    val jelly = s"${best.jellyfishRisk} — " + (best.jellyfishRisk match
      case marola.model.JellyfishRisk.Low => "few of the warm-calm signals present"
      case marola.model.JellyfishRisk.Moderate =>
        "some warm-calm signals present; worth a look from the sand"
      case marola.model.JellyfishRisk.High =>
        "warm, calm, weak current — check the shoreline before wading in"
    )

    val whales =
      val peak = best.whalePeak
        .map(p =>
          s"; best daylight odds ${Swimability.whaleSightingLikelihood(p)} at ${p.time.format(timeFormat)}"
        )
        .getOrElse("")
      val season =
        if Swimability.isWhaleSeason(h.time) then " — humpback season"
        else " — outside July-November season"
      s"${best.whaleSightingLikelihood} at this hour$peak$season"

    val rows = List(
      "Water quality" -> water,
      "Sea" -> List(sea),
      "Tide" -> List(tide),
      "Air" -> List(air),
      "Jellyfish" -> List(jelly),
      "Whales" -> List(whales)
    )
    val body = rows.flatMap {
      case (label, lines) =>
        lines.zipWithIndex.map { case (l, i) => f"  ${if i == 0 then label else ""}%-15s $l" }
    }
    (header +: body).mkString("\n")

  def lore(today: LocalDate, beachName: String, origin: Coordinates): Option[String] =
    SeaLore
      .pick(SeaLore.loadDefault(), today, beachName, SeaLore.regionTagsFor(origin))
      .map(SeaLore.format)

  def answer(a: OceanQa.Answer): String =
    val sources = a.passages.zipWithIndex.map {
      case (p, i) =>
        f"  [${i + 1}] ${p.docTitle} — ${p.source} (score ${p.score}%.2f)"
    }
    (a.text.trim +: (if sources.isEmpty then Nil else "Sources:" +: sources)).mkString("\n")

  def compass(deg: Double): String =
    val dirs = Vector("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    dirs(((deg % 360 + 360) % 360 / 45.0).round.toInt % 8)
