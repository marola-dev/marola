package marola.water

import scala.collection.mutable.ArrayBuffer

import marola.log.Log

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

  private val log = Log.forName(getClass.getName)

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
    var nameColumnMaxX: Option[Float] = None

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
      columnSplit(line).foreach(x => nameColumnMaxX = Some(x))
      nameColumnMaxX.flatMap(classify(line, _)) match
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
    if nameColumnMaxX.isEmpty && lines.nonEmpty then
      log.warn("INEA bulletin: no PRAIAS/LOCALIZAÇÃO header row found — parsed no rows")
    out.result()

  /**
   * INEA shifts the whole table between bulletins (~55pt between June and September 2026, #487), so
   * the beach-name column is bounded by the header row, not by a fixed x.
   */
  private def columnSplit(line: Line): Option[Float] =
    for
      beaches <- line.chunks.find(_.text.trim.startsWith("PRAIAS"))
      location <- line.chunks.find(_.text.trim.startsWith("LOCALIZA"))
    yield (beaches.x + location.x) / 2

  private def nearestName(names: List[NameEvent], y: Float): String =
    // Ties (equal Y-distance to two candidates) break toward the later one — verified against the
    // fixture's BD09 row, equidistant between "Barra da Tijuca" and "Barra da Tijuca II", which
    // belongs with the latter.
    names.minBy(n => (math.abs(n.y - y), -n.y)).name

  private def codePrefix(code: String): String = code.takeWhile(_.isLetter)

  private enum Classified:
    case RowFound(
        code: String,
        location: String,
        category: BathingCondition,
        embeddedName: Option[String],
        y: Float
    )
    case NameFound(name: String, y: Float)

  private def classify(line: Line, nameColumnMaxX: Float): Option[Classified] =
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
        val (nameChunks, locChunks) = others.partition(_.x < nameColumnMaxX)
        val location =
          if locPrefix.nonEmpty then locPrefix
          else locChunks.headOption.map(_.text.trim).getOrElse("")
        val embeddedName = nameChunks.headOption.map(_.text.trim)
        Some(
          Classified.RowFound(
            code,
            location,
            ImaScWaterQualityClient.verdict(category.get),
            embeddedName,
            line.y
          )
        )
      case None if line.chunks.sizeIs == 1 =>
        val text = line.chunks.head.text.trim
        val looksLikeName =
          category.isEmpty &&
            BeachNameCandidate.matches(text) &&
            !CategoryLabels.exists(text.contains)
        if looksLikeName then Some(Classified.NameFound(text, line.y)) else None
      case _ => None
