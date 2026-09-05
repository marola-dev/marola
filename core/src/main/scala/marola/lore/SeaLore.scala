package marola.lore

import java.time.LocalDate

import scala.io.Source

import marola.json.JsonValue
import marola.model.Coordinates

enum LoreKind derives CanEqual:
  case Secret, Creature

/**
 * One curated, sourced paragraph. `regions` are tags like `global` or `BR-S` (south Brazil coast);
 * `months` restricts seasonal facts (a right-whale entry in February would be wrong). Every entry
 * carries a `source` URL — an entry without one must not be added (MIP-0001 §5.4).
 */
final case class LoreEntry(
    id: String,
    kind: LoreKind,
    text: String,
    source: String,
    regions: Set[String],
    months: Option[Set[Int]],
    lang: String
) derives CanEqual

/**
 * The "did you know?" paragraph at the end of a reply. Selection is pure and deterministic: the
 * same beach shows the same entry all day and a different one tomorrow, and neighbouring beaches
 * differ (seeded by date × beach name). The text is shown **verbatim** — it never goes through the
 * LLM, so it can't be paraphrased into something its source doesn't say.
 */
object SeaLore:

  val DefaultResource = "sea_lore.json"

  def loadDefault(): List[LoreEntry] =
    val stream = getClass.getClassLoader.getResourceAsStream(DefaultResource)
    if stream == null then Nil
    else
      try parse(Source.fromInputStream(stream, "UTF-8").mkString)
      finally stream.close()

  def parse(jsonText: String): List[LoreEntry] =
    JsonValue.parse(jsonText).arr.toList.flatMap { e =>
      for
        id <- e("id").str
        kindStr <- e("kind").str
        kind <- kindStr.toLowerCase match
          case "secret"   => Some(LoreKind.Secret)
          case "creature" => Some(LoreKind.Creature)
          case _          => None
        text <- e("text").str
        source <- e("source").str if source.nonEmpty
      yield LoreEntry(
        id,
        kind,
        text,
        source,
        e("regions").arr.flatMap(_.str).toSet,
        Option(e("months").arr.flatMap(_.num).map(_.toInt).toSet).filter(_.nonEmpty),
        e("lang").str.getOrElse("en")
      )
    }

  /** `global` everywhere; `BR-S` on the south/south-east Brazilian coast (rough bounding box). */
  def regionTagsFor(origin: Coordinates): Set[String] =
    val southBrazil =
      origin.lat >= -34.0 && origin.lat <= -22.0 && origin.lon >= -54.0 && origin.lon <= -40.0
    if southBrazil then Set("global", "BR-S") else Set("global")

  def pick(
      entries: List[LoreEntry],
      today: LocalDate,
      beachName: String,
      regions: Set[String]
  ): Option[LoreEntry] =
    val eligible = entries.filter { e =>
      e.regions.exists(regions.contains) && e.months.forall(_.contains(today.getMonthValue))
    }
    if eligible.isEmpty then None
    else
      val seed = today.toEpochDay * 31L + beachName.hashCode.toLong
      Some(eligible(new scala.util.Random(seed).nextInt(eligible.size)))

  def format(entry: LoreEntry): String =
    val lead = entry.kind match
      case LoreKind.Secret   => "🌊 Did you know?"
      case LoreKind.Creature => "🐋 Sea life:"
    s"$lead ${entry.text} [source: ${entry.source}]"
