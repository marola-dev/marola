package marola.tools.shared

import java.math.{BigDecimal as JBigDecimal, RoundingMode}
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

// The jq behaviours the gh reads keep, exit code included: gh --paginate prints one JSON document
// per page, `.a.b` of a missing key is null, `[]` of null is nothing, `-r` prints strings bare, and
// numbers print as jq 1.7 computes them.
object Jq:
  /** jq's exit code for a parse error, or for an error on its last input. */
  val JqError = 5

  /** The documents read before the end of the text, or before the first one that does not parse.
    */
  final case class Documents(documents: Vector[ujson.Value], complete: Boolean):
    /** `jq '.<key>[]'` exits 0: everything parsed and the last document has `key` to iterate. */
    def readable(key: String): Boolean =
      complete && documents.lastOption.forall(doc => iterable(field(doc, key)))

  /** Every JSON document in `text`, as `jq` reads a stream. */
  def values(text: String): Documents =
    @tailrec def from(start: Int, read: Vector[ujson.Value]): Documents =
      if start >= text.length then Documents(read, complete = true)
      else
        val end = documentEnd(text, start)
        Try(ujson.read(text.substring(start, end))) match
          case Success(document) => from(skipBlanks(text, end), read :+ document)
          case Failure(_)        => Documents(read, complete = false)
    from(skipBlanks(text, 0), Vector.empty)

  private def skipBlanks(text: String, from: Int): Int =
    text.indexWhere(!_.isWhitespace, from) match
      case -1 => text.length
      case i  => i

  /** Where the document starting at `start` ends: its closing bracket, or the next blank for a
    * scalar.
    */
  private def documentEnd(text: String, start: Int): Int =
    @tailrec def close(i: Int, depth: Int, inString: Boolean): Int =
      if i >= text.length then text.length
      else
        val c = text(i)
        if inString then
          if c == '\\' then close(i + 2, depth, inString = true)
          else close(i + 1, depth, inString = c != '"')
        else if c == '"' then close(i + 1, depth, inString = true)
        else if c == '{' || c == '[' then close(i + 1, depth + 1, inString = false)
        else if (c == '}' || c == ']') && depth == 1 then i + 1
        else if c == '}' || c == ']' then close(i + 1, depth - 1, inString = false)
        else close(i + 1, depth, inString = false)
    if text(start) == '{' || text(start) == '[' then close(start, 0, inString = false)
    else
      text.indexWhere(_.isWhitespace, start) match
        case -1 => text.length
        case i  => i

  def field(value: ujson.Value, key: String): ujson.Value = value match
    case obj: ujson.Obj => obj.value.getOrElse(key, ujson.Null)
    case _              => ujson.Null

  def iterable(value: ujson.Value): Boolean =
    value.isInstanceOf[ujson.Arr] || value.isInstanceOf[ujson.Obj]

  /** `.[]`, and nothing for a value it would refuse. */
  def items(value: ujson.Value): Seq[ujson.Value] = value match
    case arr: ujson.Arr => arr.value.toSeq
    case obj: ujson.Obj => obj.value.values.toSeq
    case _              => Seq.empty

  def truthy(value: ujson.Value): Boolean = value != ujson.Null && value != ujson.False

  /** `a // b` */
  def orElse(value: ujson.Value, fallback: ujson.Value): ujson.Value =
    if truthy(value) then value else fallback

  /** A value as `jq -r` or string interpolation prints an id: a whole number without `.0`. */
  def text(value: ujson.Value): String = value match
    case ujson.Str(s)                              => s
    case ujson.Num(d) if d.isWhole && d.abs < 1e17 => d.toLong.toString
    case other                                     => ujson.write(other)

  /** A string as one `@tsv` field. */
  def tsvString(s: String): String =
    s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

  /** A value as one `@tsv` field, an id-like number as `text` prints it. */
  def tsv(value: ujson.Value): String = value match
    case ujson.Null   => ""
    case ujson.Str(s) => tsvString(s)
    case other        => text(other)

  /** `.*100|round/100|tostring`: round half away from zero, as C's round. */
  def money(amount: Double): String =
    number(JBigDecimal(amount * 100).setScale(0, RoundingMode.HALF_UP).doubleValue / 100)

  /** A computed number as jq 1.7 prints it: shortest digits, an exponent past 17 digits or below
    * 1e-4.
    */
  def number(n: Double): String =
    if n == 0 then (if 1 / n < 0 then "-0" else "0")
    else
      val exact = JBigDecimal(java.lang.Double.toString(math.abs(n))).stripTrailingZeros
      val digits = exact.unscaledValue.toString
      val pointAt = digits.length - exact.scale // digits before the decimal point
      val sign = if n < 0 then "-" else ""
      if pointAt <= -4 || pointAt > digits.length + 15 then
        val mantissa = if digits.length == 1 then digits else s"${digits.head}.${digits.tail}"
        val exponent = pointAt - 1
        sign + mantissa + f"e${if exponent < 0 then "-" else "+"}${math.abs(exponent)}%02d"
      else if pointAt <= 0 then s"${sign}0.${"0" * -pointAt}$digits"
      else if pointAt >= digits.length then sign + digits + "0" * (pointAt - digits.length)
      else s"$sign${digits.take(pointAt)}.${digits.drop(pointAt)}"
