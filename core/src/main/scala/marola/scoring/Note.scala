package marola.scoring

import java.time.LocalDate
import java.time.format.DateTimeFormatter

import marola.json.JsonValue

/** MIP-0054 §5.1: a scoring reason as a code on the wire; the page and the CLI word it. */
enum NoteCode(val label: String) derives CanEqual:
  case RoughSeas extends NoteCode("rough_seas")
  case Choppy extends NoteCode("choppy")
  case NoWaveData extends NoteCode("no_wave_data")
  case StrongWind extends NoteCode("strong_wind")
  case Breezy extends NoteCode("breezy")
  case NoWindData extends NoteCode("no_wind_data")
  case ColdWater extends NoteCode("cold_water")
  case WarmWater extends NoteCode("warm_water")
  case NoSeaTempData extends NoteCode("no_sea_temp_data")
  case RainLikely extends NoteCode("rain_likely")
  case Dark extends NoteCode("dark")
  case JellyfishElevated extends NoteCode("jellyfish_elevated")
  case JellyfishSome extends NoteCode("jellyfish_some")
  case WaterStale extends NoteCode("water_stale")
  case WaterUnfit extends NoteCode("water_unfit")
  case WaterMixed extends NoteCode("water_mixed")

object NoteCode:
  def fromLabel(label: String): Option[NoteCode] = values.find(_.label == label)

/**
 * `args` are already JSON so the board writes them as-is: numbers stay numbers (the page formats
 * them per locale), dates are ISO strings, lists are arrays.
 */
final case class Note(code: NoteCode, args: Map[String, JsonValue] = Map.empty) derives CanEqual:

  def json: JsonValue =
    JsonValue.obj("code" -> JsonValue.str(code.label), "args" -> JsonValue.JObject(args))

  /** What `Report` prints and the board's `notes` carry; NoteSpec pins every case. */
  def english: String =
    def num(k: String) = args.get(k).flatMap(_.num).getOrElse(Double.NaN)
    def str(k: String) = args.get(k).flatMap(_.str).getOrElse("")
    def day(k: String) = LocalDate.parse(str(k)).format(Note.sampleDateFormat)
    code match
      case NoteCode.RoughSeas         => f"rough seas (${num("wave_m")}%.1fm waves)"
      case NoteCode.Choppy            => f"choppy (${num("wave_m")}%.1fm waves)"
      case NoteCode.NoWaveData        => "no wave data"
      case NoteCode.StrongWind        => f"strong wind (${num("wind_kmh")}%.0fkm/h)"
      case NoteCode.Breezy            => f"breezy (${num("wind_kmh")}%.0fkm/h)"
      case NoteCode.NoWindData        => "no wind data"
      case NoteCode.ColdWater         => f"cold water (${num("sea_temp_c")}%.1f°C)"
      case NoteCode.WarmWater         => f"warm water (${num("sea_temp_c")}%.1f°C)"
      case NoteCode.NoSeaTempData     => "no sea temperature data"
      case NoteCode.RainLikely        => f"${num("rain_pct")}%.0f%% chance of rain"
      case NoteCode.Dark              => "dark"
      case NoteCode.JellyfishElevated => "elevated jellyfish likelihood"
      case NoteCode.JellyfishSome     => "some jellyfish likelihood"
      case NoteCode.WaterStale        => s"water quality data stale (${day("sampled_on")})"
      case NoteCode.WaterUnfit =>
        val count = args
          .get("enterococci_per_100ml")
          .flatMap(_.num)
          .map(n => s"${n.toLong} enterococci/100mL")
          .getOrElse("count n/a")
        s"water unfit for bathing — ${str("source")} ${day("sampled_on")}, ${str("point")} (${str("location")}), $count"
      case NoteCode.WaterMixed =>
        val avoid = args.get("avoid").map(_.arr.flatMap(_.str)).getOrElse(Vector.empty)
        s"${num("proper").toLong}/${num("total").toLong} points PRÓPRIA — avoid ${avoid.mkString("; ")}"

object Note:
  // Default JVM locale, as the CLI prints it; the page formats sampled_on per language.
  private[scoring] val sampleDateFormat = DateTimeFormatter.ofPattern("d MMM")
