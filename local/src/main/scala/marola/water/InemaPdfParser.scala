package marola.water

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Parses INEMA's (Bahia) real water-quality bulletin PDF — fetched via `geraBoletim?idcampanha=N`,
 * the only mechanism confirmed live for this institute (MIP-0031 §4.3/§11: no JSON/HTML alternative
 * exists, INEMA's own search form just wraps a `GET` to this same PDF endpoint) — into rows of
 * (point code, point name, description, category).
 */
object InemaPdfParser:

  final case class Row(code: String, pointName: String, description: String, category: String)

  // INEMA's own point-code convention (e.g. "SSA IN 100"): one or more uppercase acronym tokens
  // followed by a numeric suffix.
  private val rowStart = "^(.+?) - ([A-Z]{2,4}(?:\\s+[A-Z]{2,4})*\\s+\\d{2,4})\\s+(.*)$".r

  private val tableEndMarker = "Observações"

  /** Pure; unit-tested against the real captured fixture (`InemaPdfParserSpec`). */
  def parseTable(pdfBytes: Array[Byte]): List[Row] =
    val lines = tableLines(extractText(pdfBytes))
    mergeContinuations(lines).flatMap(toRow)

  private def extractText(pdfBytes: Array[Byte]): String =
    val document = Loader.loadPDF(pdfBytes)
    try new PDFTextStripper().getText(document)
    finally document.close()

  private def tableLines(text: String): List[String] =
    text.linesIterator.toList
      .dropWhile(line => !rowStart.matches(line.trim))
      .takeWhile(line => !line.trim.startsWith(tableEndMarker))

  // A line starting with "<name> - <code> " begins a new row; any other line is the wrapped tail
  // of the previous row's description and is folded into it with a single space.
  private def mergeContinuations(lines: List[String]): List[String] =
    lines
      .foldLeft(List.empty[String]) { (rows, line) =>
        if rowStart.matches(line.trim) then line.trim :: rows
        else
          rows match
            case head :: tail => s"$head ${line.trim}" :: tail
            case Nil          => List(line.trim)
      }
      .reverse

  private def toRow(row: String): Option[Row] =
    row match
      case rowStart(name, code, rest) =>
        val words = rest.trim.split("\\s+").toList
        words.lastOption
          .filter(_.nonEmpty)
          .map { category =>
            val description = words.dropRight(1).mkString(" ")
            Row(code.trim, name.trim, description, category.trim)
          }
      case _ => None
