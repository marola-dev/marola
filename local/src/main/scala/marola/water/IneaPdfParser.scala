package marola.water

import java.text.Normalizer

import scala.collection.mutable.ArrayBuffer

/**
 * INEA (Rio de Janeiro)'s bathing-water bulletin PDF layout — verified live 2026-09-07 against a
 * real, current bulletin (`https://www.inea.rj.gov.br/wp-content/uploads/2026/06/
 * Zona-sudoeste-e-Zona-sul-17-06-26.pdf`, Boletim N°24, 17/06/2026; captured as this parser's test
 * fixture, `local/src/test/resources/inea-boletim-zona-sudoeste-sul-2026-06-17.pdf`).
 */
object IneaPdfParser:

  final case class Row(
      pointCode: String,
      beachName: String,
      location: String,
      category: BathingCondition
  )

  private val CategoryLabels: Set[String] = Set("Própria", "Imprópria", "Indisponível")

  // e.g. "...BG00" / "BD10" / "...GV02" — an optional location prefix, then a 2-4 letter, 2-3
  // digit point code at the very end of the run.
  private val CodeSuffix = "^(.*?)\\s*([A-Z]{2,4}[0-9]{2,3})$".r

  // A standalone rowspan-label candidate: proper-noun-shaped, short, no sentence punctuation and
  // no accidental overlap with a CONAMA verdict — this is what keeps the footer prose
  // (`Observações:`, `* O referencial...`, `Balneabilidade Imprópria`) from being mistaken for a
  // beach name (verified against the real fixture's footer, MIP-0031 §11).
  private val BeachNameCandidate = "^\\p{Lu}[\\p{L}0-9'’/. -]{0,29}$".r

  // The `PRAIAS` column's own x-range in the verified fixture is 126-161pt; `LOCALIZAÇÃO` starts
  // no earlier than ~218pt (shared with footer prose, harmless since footer sits below the last
  // real row).
  private val BeachColumnMaxX = 200.0f

  /** One extracted text run and its real PDF position — the geometry the attribution rule needs. */
  // Geometry-aware extraction now lives in PdfLines, shared with ImaScPdfParser.
  import PdfLines.Line

  def parseTable(pdfBytes: Array[Byte]): List[Row] =
    parseLines(PdfLines.of(pdfBytes))

  final private case class RowEvent(
      code: String,
      location: String,
      category: BathingCondition,
      embeddedName: Option[String],
      y: Float
  )
  final private case class NameEvent(name: String, y: Float)

  private[water] def parseLines(lines: List[Line]): List[Row] =
    val out = List.newBuilder[Row]
    var currentPrefix: Option[String] = None
    var pendingRows = ArrayBuffer.empty[RowEvent]
    var pendingNames = ArrayBuffer.empty[NameEvent]

    def flush(): Unit =
      if pendingRows.nonEmpty then
        val distinctNames = pendingNames.map(_.name).distinct.toList
        distinctNames match
          case single :: Nil =>
            pendingRows.foreach(r => out += Row(r.code, single, r.location, r.category))
          case _ :: _ :: _ =>
            pendingRows.foreach { r =>
              val name = r.embeddedName.getOrElse(nearestName(pendingNames.toList, r.y))
              out += Row(r.code, name, r.location, r.category)
            }
          case Nil => () // no name ever seen for this run — drop, never guess a beach name
      pendingRows = ArrayBuffer.empty
      pendingNames = ArrayBuffer.empty

    for line <- lines do
      classify(line) match
        case Some(Classified.RowFound(code, location, category, embeddedName, y)) =>
          val prefix = codePrefix(code)
          if !currentPrefix.contains(prefix) then
            flush()
            currentPrefix = Some(prefix)
          pendingRows += RowEvent(code, location, category, embeddedName, y)
          embeddedName.foreach(n => pendingNames += NameEvent(n, y))
        case Some(Classified.NameFound(name, y)) =>
          pendingNames += NameEvent(name, y)
        case None => ()

    flush()
    out.result()

  private def nearestName(names: List[NameEvent], y: Float): String =
    // Ties (equal Y-distance to two candidates) break toward the later one — verified against the
    // fixture's BD09 row, equidistant between "Barra da Tijuca" and "Barra da Tijuca II", which
    // belongs with the latter.
    names.minBy(n => (math.abs(n.y - y), -n.y)).name

  private val PointCodePattern = "^[A-Z]{2,4}[0-9]{2,3}$".r

  private def codePrefix(code: String): String =
    PointCodePattern.findFirstMatchIn(code) match
      case Some(_) => code.takeWhile(_.isLetter)
      case None    => code

  private enum Classified:
    case RowFound(
        code: String,
        location: String,
        category: BathingCondition,
        embeddedName: Option[String],
        y: Float
    )
    case NameFound(name: String, y: Float)

  private def classify(line: Line): Option[Classified] =
    val category = line.chunks.map(_.text.trim).find(CategoryLabels.contains)
    val nonCategory = line.chunks.filterNot(c => CategoryLabels.contains(c.text.trim))
    val codeMatch = nonCategory
      .flatMap(c =>
        CodeSuffix.findFirstMatchIn(c.text.trim).map(m => (c, m.group(1).trim, m.group(2)))
      )
      .headOption
    codeMatch match
      case Some((codeChunk, locPrefix, code)) if category.isDefined =>
        val others = nonCategory.filterNot(_ == codeChunk)
        val (nameChunks, locChunks) = others.partition(_.x < BeachColumnMaxX)
        val location =
          if locPrefix.nonEmpty then locPrefix
          else locChunks.headOption.map(_.text.trim).getOrElse("")
        val embeddedName = nameChunks.headOption.map(_.text.trim)
        Some(Classified.RowFound(code, location, condition(category.get), embeddedName, line.y))
      case None if line.chunks.sizeIs == 1 =>
        val text = line.chunks.head.text.trim
        val looksLikeName =
          category.isEmpty &&
            BeachNameCandidate.matches(text) &&
            !CategoryLabels.exists(text.contains)
        if looksLikeName then Some(Classified.NameFound(text, line.y)) else None
      case _ => None

  private def condition(label: String): BathingCondition =
    Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase.trim match
      case s if s.startsWith("IMPR") => BathingCondition.Improper
      case s if s.startsWith("PR")   => BathingCondition.Proper
      case _                         => BathingCondition.Unknown
