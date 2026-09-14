package marola.oods

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalTime}

import scala.util.Try

import marola.water.{BathingCondition, WaterQualityMatcher}

/**
 * The pure half of the `ima-sc` adapter: `exportarCSV`'s 13-column export → `SampleRow`s, joined to
 * the points feed on (municipio, balneario, ponto) (MIP-0056 §4.1, §8).
 */
object ImaScCsv:

  /** The portal's own header, byte for byte — the unbalanced brace in `(ºC}` is theirs. */
  val Header: List[String] = List(
    "Municipio",
    "Balneario",
    "Ponto Coleta",
    "Localização",
    "Data",
    "Hora",
    "Vento",
    "Maré",
    "Chuva",
    "Água (ºC}",
    "Ar (ºC}",
    "E. coli NMP*/100ml",
    "Condição"
  )

  private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
  private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

  /** ASCII and path-safe, over `WaterQualityMatcher.normalise`'s NFD fold — never a second one. */
  def slug(name: String): String = WaterQualityMatcher.normalise(name).replace(' ', '-')

  /**
   * Beach slug → the portal's own `localID`. Florianópolis alone lists three pairs that differ only
   * by an article ("Praia da/do Cachoeira do Bom Jesus") or by a repeated space, and the fold
   * erases both: without a suffix two distinct beaches would share one partition and one would
   * overwrite the other's history.
   */
  def beachSlugs(beaches: List[String]): Map[String, String] =
    beaches
      .groupBy(slug)
      .toList
      .flatMap { (base, names) =>
        if names.sizeIs == 1 then names.map(base -> _)
        else
          names.sorted.zipWithIndex.map((name, i) =>
            (if i == 0 then base else s"$base-${i + 1}") -> name
          )
      }
      .toMap

  /**
   * The join key of MIP-0056 §4.1: unique across all 260 live points, unlike `PONTO_NOME` alone.
   */
  def index(points: List[PointRow]): Map[(String, String, String), PointRow] =
    points.map(p => (norm(p.municipality), norm(p.beachName), norm(p.pointName)) -> p).toMap

  /**
   * The registry entry for a CSV row, or a placeholder keyed by slug with `GeoSource.Missing`: a
   * renamed or renumbered point loses its coordinates, never its samples (MIP-0056 §8).
   */
  def pointFor(
      source: Source,
      index: Map[(String, String, String), PointRow],
      municipio: String,
      beach: String,
      point: String
  ): PointRow =
    index.getOrElse(
      (norm(municipio), norm(beach), norm(point)),
      PointRow(
        sourceId = source.id,
        pointKey = s"${source.id}:${slug(municipio)}/${slug(beach)}/${slug(point)}",
        country = source.country,
        state = source.state,
        municipality = municipio,
        ibgeCode = None,
        beachName = beach,
        pointName = point,
        locationDesc = None,
        lat = None,
        lon = None,
        geoSource = GeoSource.Missing,
        firstSeen = None,
        lastSeen = None
      )
    )

  def parse(
      source: Source,
      rawPath: String,
      text: String,
      points: List[PointRow]
  ): Either[ParseError, List[SampleRow]] =
    val lines =
      text.stripPrefix("\uFEFF").split("\n", -1).view.map(_.stripSuffix("\r")).filter(_.nonEmpty)
    lines.toList match
      case Nil => Left(ParseError(rawPath, "empty export"))
      case header :: _ if fields(header).map(_.trim) != Header =>
        Left(ParseError(rawPath, s"unexpected header: ${header.take(150)}"))
      case _ :: body =>
        val registry = index(points)
        collect(source, rawPath, registry, body.map(fields), Nil, line = 2)

  private enum Parsed derives CanEqual:
    case Sample(row: SampleRow)
    case NoRecords
    case Malformed(detail: String)

  @annotation.tailrec
  private def collect(
      source: Source,
      rawPath: String,
      registry: Map[(String, String, String), PointRow],
      rest: List[List[String]],
      acc: List[SampleRow],
      line: Int
  ): Either[ParseError, List[SampleRow]] =
    rest match
      case Nil => Right(acc.reverse)
      case cells :: tail =>
        row(source, registry, cells) match
          case Parsed.Sample(s)      => collect(source, rawPath, registry, tail, s :: acc, line + 1)
          case Parsed.NoRecords      => collect(source, rawPath, registry, tail, acc, line + 1)
          case Parsed.Malformed(why) => Left(ParseError(rawPath, s"line $line: $why"))

  private def row(
      source: Source,
      registry: Map[(String, String, String), PointRow],
      cells: List[String]
  ): Parsed =
    // A year with no samples for a point is `…,YYYY,"Sem registros"` — 200, five columns, no row.
    if cells.lastOption.exists(_.trim == "Sem registros") then Parsed.NoRecords
    else if cells.sizeIs != Header.size then
      Parsed.Malformed(s"${cells.size} columns, expected ${Header.size}")
    else
      val c = cells.map(_.trim)
      Try(LocalDate.parse(c(4), dateFormat)).toOption match
        case None => Parsed.Malformed(s"unparsable date '${c(4)}'")
        case Some(day) =>
          val (value, qualifier) = count(c(11))
          Parsed.Sample(
            SampleRow(
              sourceId = source.id,
              pointKey = pointFor(source, registry, c(0), c(1), c(2)).pointKey,
              sampledOn = day,
              sampledAt = Try(LocalTime.parse(c(5), timeFormat)).toOption,
              condition = condition(c(12)),
              indicator = Indicator.EColi,
              indicatorValue = value,
              qualifier = qualifier,
              rain = present(c(8)),
              wind = present(c(6)),
              tide = present(c(7)),
              waterTempC = c(9).toDoubleOption,
              airTempC = c(10).toDoubleOption,
              channel = Channel.Csv,
              bulletinDate = None
            )
          )

  /** `<20` and `> 24196` are the method's limits, not missing values (MIP-0056 §5.3). */
  private def count(raw: String): (Option[Int], Qualifier) =
    val (digits, qualifier) =
      if raw.startsWith("<") then (raw.drop(1), Qualifier.Below)
      else if raw.startsWith(">") then (raw.drop(1), Qualifier.Above)
      else (raw, Qualifier.Exact)
    digits.trim.toIntOption match
      case Some(n) => (Some(n), qualifier)
      case None    => (None, Qualifier.Exact)

  private def condition(raw: String): BathingCondition =
    norm(raw) match
      case s if s.startsWith("impr") => BathingCondition.Improper
      case s if s.startsWith("pr")   => BathingCondition.Proper
      case _                         => BathingCondition.Unknown

  private def present(raw: String): Option[String] = Option(raw).filter(_.nonEmpty)

  private def norm(s: String): String = WaterQualityMatcher.normalise(s)

  /**
   * RFC 4180 fields: a comma inside `"…"` is data and `""` is a literal quote. A quoted field
   * spanning a newline would split into two rows; the portal's export has none.
   */
  private def fields(line: String): List[String] =
    @annotation.tailrec
    def loop(i: Int, quoted: Boolean, current: StringBuilder, acc: List[String]): List[String] =
      if i >= line.length then (current.toString :: acc).reverse
      else
        line.charAt(i) match
          case '"' if quoted && i + 1 < line.length && line.charAt(i + 1) == '"' =>
            loop(i + 2, quoted, current.append('"'), acc)
          case '"'            => loop(i + 1, !quoted, current, acc)
          case ',' if !quoted => loop(i + 1, quoted, StringBuilder(), current.toString :: acc)
          case c              => loop(i + 1, quoted, current.append(c), acc)
    loop(0, quoted = false, StringBuilder(), Nil)
