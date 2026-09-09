package marola.water

import java.util as ju

import scala.collection.mutable.ArrayBuffer

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.{PDFTextStripper, TextPosition}

/**
 * PDF text with the geometry kept.
 *
 * Every bathing-water bulletin is a table, and a table read in natural order is unusable: IMA's
 * puts each beach's name once and then streams `DATA DA COLETA / SITUAÇÃO / date / verdict` blocks,
 * so a line-based parse silently pairs a verdict with the wrong beach. For a swim-safety verdict
 * that is not a formatting bug, it is a wrong answer, so both agency parsers work from x/y
 * positions instead.
 *
 * Extracted from `IneaPdfParser`, which had this inline; `IneaPdfParserSpec` is the regression
 * guard that the extraction changed nothing.
 */
private[water] object PdfLines:

  final case class Chunk(x: Float, y: Float, text: String) derives CanEqual
  final case class Line(y: Float, chunks: List[Chunk])

  final private class LineCapturingStripper extends PDFTextStripper:
    private val current = ArrayBuffer.empty[Chunk]
    private val lines = ArrayBuffer.empty[Line]

    override def writeString(text: String, textPositions: ju.List[TextPosition]): Unit =
      if !textPositions.isEmpty then
        val first = textPositions.get(0)
        current += Chunk(first.getXDirAdj, first.getYDirAdj, text)

    override def writeLineSeparator(): Unit =
      if current.nonEmpty then
        lines += Line(current.head.y, current.toList)
        current.clear()
      ()

    def captured(): List[Line] = lines.toList

  def of(pdfBytes: Array[Byte]): List[Line] =
    val doc = Loader.loadPDF(pdfBytes)
    try
      val stripper = new LineCapturingStripper
      stripper.setSortByPosition(true)
      val _ = stripper.getText(doc)
      stripper.captured()
    finally doc.close()
