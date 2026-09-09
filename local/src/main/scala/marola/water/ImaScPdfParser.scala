package marola.water

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import scala.util.Try

/**
 * IMA's weekly bulletin PDF — the backup for when the JSON feed is unreachable, which it has been
 * since 2026-09-08 (a self-signed certificate on the production host, and then nothing on 443 at
 * all).
 *
 * The PDF carries what the reduced HTTP JSON does not: a collection date and a verdict per point.
 * It carries no coordinates, which is why `ImaScPdfWaterQualityClient` joins these rows against the
 * JSON feed's point list rather than using them alone.
 *
 * Read in natural order the file is unusable — each beach's name appears once and the verdicts
 * stream separately — so this works from `PdfLines`' geometry, where a record is:
 *
 * {{{
 * PRAIA DO ARROIO DO SILVA (Ponto 01)
 *                                       26/08/2026    PRÓPRIA
 * Em frente à Rua Apolônio Ireno Cardoso
 * }}}
 */
private[water] object ImaScPdfParser:

  final case class Row(
      beachName: String,
      pointName: String,
      collectedOn: LocalDate,
      condition: BathingCondition
  )

  private val PointHeading = """^(.+?)\s*\(\s*(Ponto\s+\d+)\s*\)\s*$""".r
  private val DateAndVerdict = """(\d{2}/\d{2}/\d{4})\s+(\p{L}+)""".r
  private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

  /**
   * How far after a heading the verdict may sit. It is the next line in every record observed in
   * the 2026-08-28 bulletin; the slack absorbs a wrapped beach name without letting a record borrow
   * the *following* point's verdict.
   */
  private val VerdictWithinLines = 2

  def parse(pdfBytes: Array[Byte]): List[Row] =
    val lines = PdfLines.of(pdfBytes).map(l => l.chunks.map(_.text).mkString(" ").trim)
    lines.zipWithIndex.flatMap {
      case (line, i) =>
        PointHeading.findFirstMatchIn(line).flatMap { m =>
          val beach = m.group(1).trim
          val point = m.group(2).replaceAll("\\s+", " ").trim
          lines
            .slice(i + 1, i + 1 + VerdictWithinLines)
            .flatMap(DateAndVerdict.findFirstMatchIn)
            .headOption
            .flatMap { dv =>
              Try(LocalDate.parse(dv.group(1), dateFormat)).toOption.map { date =>
                Row(beach, point, date, condition(dv.group(2)))
              }
            }
        }
    }

  /** Same accent-folding rule as the JSON client, so PRÓPRIA and PROPRIA read alike. */
  private def condition(raw: String): BathingCondition =
    Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase.trim match
      case s if s.startsWith("IMPR") => BathingCondition.Improper
      case s if s.startsWith("PR")   => BathingCondition.Proper
      case _                         => BathingCondition.Unknown
