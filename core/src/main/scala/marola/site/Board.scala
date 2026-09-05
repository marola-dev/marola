package marola.site

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{LocalDate, OffsetDateTime}

import marola.json.JsonValue
import marola.lore.{LoreEntry, LoreKind}
import marola.model.{BestHour, HourlyConditions}
import marola.scoring.Swimability
import marola.water.{BathingCondition, WaterQuality}

/**
 * MIP-0005 §5.2: the machine-readable board the static map renders — one document per area per day,
 * every beach with every *daylight* hour scored, plus the same water/tide/sea facts the CLI's
 * detailed block prints. Pure: a serializer over `BestHour`s (from `Recommender.scoreDays`), no
 * I/O, no clock — `generatedAt` and `today` are inputs so a build is reproducible from fixtures.
 * The contract for any consumer (the page's `app.js`, the bot, an MCP tool) is
 * `site/board.schema.json`, checked against this output in `BoardSpec`.
 *
 * Presentation rules carried over from MIP-0001/0005 §6: dark hours never appear (so a "best hour"
 * can't be one), unfit water is an explicit `unfit: true` with the agency's own note, the lore
 * paragraph is verbatim with its source, and no per-visitor data exists anywhere.
 */
object Board:

  /** Bumped when a consumer would need to change; the page refuses a board it doesn't know. */
  val SchemaVersion = 1

  final case class Sources(beaches: String, forecast: String, water: Option[String])

  private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

  /**
   * ISO-8601 with offset at second precision, e.g. `2026-09-05T18:00:00-03:00` — the page shows it
   * verbatim (a live `OffsetDateTime.now()` carries nanoseconds otherwise).
   */
  def stamp(t: OffsetDateTime): String =
    t.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

  def build(
      area: String,
      day: LocalDate,
      today: LocalDate,
      generatedAt: OffsetDateTime,
      scored: List[BestHour],
      lore: Option[LoreEntry],
      sources: Sources
  ): JsonValue =
    val perBeach = scored
      .filter(r => r.hour.time.toLocalDate.isEqual(day) && r.hour.isDaylight.contains(true))
      .groupBy(_.beach.name)
      .values
      .map(hours => beachJson(hours.sortBy(_.hour.time), today))
      .toList
      // `bestScore` is the sort key: best first, then name for a stable order between runs
      .sortBy { case (score, name, _) => (-score, name) }
      .map(_._3)

    JsonValue.obj(
      "schema" -> JsonValue.num(SchemaVersion.toDouble),
      "area" -> JsonValue.str(area),
      "day" -> JsonValue.str(day.toString),
      "today" -> JsonValue.str(today.toString),
      "generated_at" -> JsonValue.str(stamp(generatedAt)),
      "sources" -> JsonValue.obj(
        "beaches" -> JsonValue.str(sources.beaches),
        "forecast" -> JsonValue.str(sources.forecast),
        "water" -> optStr(sources.water)
      ),
      "lore" -> lore.map(loreJson).getOrElse(JsonValue.JNull),
      "beaches" -> JsonValue.arr(perBeach*)
    )

  /** (best score, name, json) for one beach's daylight hours of one day, chronological. */
  private def beachJson(hours: List[BestHour], today: LocalDate): (Int, String, JsonValue) =
    val best = hours.minBy(r => (-r.score, Swimability.hourPreference(r.hour)))
    val beach = best.beach
    val json = JsonValue.obj(
      "name" -> JsonValue.str(beach.name),
      "lat" -> JsonValue.num(beach.coordinates.lat),
      "lon" -> JsonValue.num(beach.coordinates.lon),
      "best" -> JsonValue.obj(
        "hour" -> JsonValue.str(best.hour.time.format(hhmm)),
        "score" -> JsonValue.num(best.score.toDouble),
        "notes" -> JsonValue.arr(best.notes.map(JsonValue.str)*)
      ),
      "hours" -> JsonValue.arr(hours.map(hourJson)*),
      "water" -> waterJson(best.waterQuality, today),
      "tides" -> JsonValue.arr(
        best.dayTides.map(t =>
          JsonValue.obj(
            "time" -> JsonValue.str(t.time.format(hhmm)),
            "m" -> JsonValue.num(t.heightM),
            "high" -> JsonValue.bool(t.isHigh)
          )
        )*
      ),
      "sea" -> seaJson(best.hour),
      "jellyfish" -> JsonValue.str(best.jellyfishRisk.toString),
      "whales" -> JsonValue.obj(
        "now" -> JsonValue.str(best.whaleSightingLikelihood.toString),
        "peak" -> optStr(best.whalePeak.map(_.time.format(hhmm))),
        "season" -> JsonValue.bool(Swimability.isWhaleSeason(best.hour.time))
      )
    )
    (best.score, beach.name, json)

  /** One slider stop: the score, why, and the few numbers the card shows for that hour. */
  private def hourJson(r: BestHour): JsonValue =
    JsonValue.obj(
      "h" -> JsonValue.str(r.hour.time.format(hhmm)),
      "score" -> JsonValue.num(r.score.toDouble),
      "notes" -> JsonValue.arr(r.notes.map(JsonValue.str)*),
      "sea_temp_c" -> optNum(r.hour.seaTempC),
      "wave_m" -> optNum(r.hour.waveHeightM),
      "wind_kmh" -> optNum(r.hour.windSpeedKmh),
      "jellyfish" -> JsonValue.str(r.jellyfishRisk.toString),
      "whales" -> JsonValue.str(r.whaleSightingLikelihood.toString)
    )

  private def seaJson(h: HourlyConditions): JsonValue =
    JsonValue.obj(
      "temp_c" -> optNum(h.seaTempC),
      "wave_m" -> optNum(h.waveHeightM),
      "period_s" -> optNum(h.wavePeriodS),
      "wave_dir_deg" -> optNum(h.waveDirectionDeg),
      "swell_m" -> optNum(h.swellWaveHeightM),
      "swell_period_s" -> optNum(h.swellWavePeriodS),
      "current_kmh" -> optNum(h.currentVelocityKmh),
      "wind_kmh" -> optNum(h.windSpeedKmh),
      "wind_dir_deg" -> optNum(h.windDirectionDeg),
      "air_temp_c" -> optNum(h.airTempC),
      "uv" -> optNum(h.uvIndex),
      "rain_pct" -> optNum(h.precipitationProbabilityPct)
    )

  /**
   * Always an object, even with no provider: the card's water line is never absent, it says "no
   * data" (absence of data is not evidence of cleanliness — MIP-0001 §6). `unfit` is the veto.
   */
  private def waterJson(water: Option[WaterQuality], today: LocalDate): JsonValue =
    val verdict = Swimability.waterVerdict(water, today)
    val points = water.toList.flatMap(_.latestSamples).sortBy { case (p, _) => p.pointName }.map {
      case (p, s) =>
        JsonValue.obj(
          "point" -> JsonValue.str(p.pointName),
          "location" -> JsonValue.str(p.location),
          "lat" -> JsonValue.num(p.coordinates.lat),
          "lon" -> JsonValue.num(p.coordinates.lon),
          "condition" -> JsonValue.str(s.condition match
            case BathingCondition.Proper   => "proper"
            case BathingCondition.Improper => "improper"
            case BathingCondition.Unknown  => "unknown"
          ),
          "sampled_on" -> JsonValue.str(s.sampledOn.toString),
          "enterococci_per_100ml" -> optNum(s.enterococciPer100ml.map(_.toDouble)),
          "rain" -> optStr(s.rain)
        )
    }
    JsonValue.obj(
      "summary" -> JsonValue.str(verdict.summary),
      "unfit" -> JsonValue.bool(verdict.veto),
      "note" -> optStr(verdict.note),
      "source" -> optStr(water.map(_.source)),
      "points" -> JsonValue.arr(points*)
    )

  private def loreJson(e: LoreEntry): JsonValue =
    JsonValue.obj(
      "kind" -> JsonValue.str(e.kind match
        case LoreKind.Secret   => "secret"
        case LoreKind.Creature => "creature"
      ),
      "text" -> JsonValue.str(e.text),
      "source" -> JsonValue.str(e.source),
      "lang" -> JsonValue.str(e.lang)
    )

  private def optNum(v: Option[Double]): JsonValue = v.map(JsonValue.num).getOrElse(JsonValue.JNull)
  private def optStr(v: Option[String]): JsonValue = v.map(JsonValue.str).getOrElse(JsonValue.JNull)
