package marola.tools.shared

import java.io.IOException
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.ByteBuffer
import scala.annotation.tailrec

// Shared helpers: strict UTF-8 reads, JSON written byte for byte as Python's `json.dumps` writes it
// (the published files keep that exact form, and readers grep them), and the
// `posixpath`/`fnmatch` pieces the link rewriter needs.
object Util:

  /** A file as text, invalid UTF-8 dropped (Python's `errors="ignore"`). */
  def readLossy(path: os.Path): String =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.IGNORE)
      .onUnmappableCharacter(CodingErrorAction.IGNORE)
    decoder.decode(ByteBuffer.wrap(os.read.bytes(path))).toString

  val NotUtf8 = "stream did not contain valid UTF-8"

  /** `bytes` as strict UTF-8, or None. */
  def decodeUtf8(bytes: Array[Byte]): Option[String] =
    try Some(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString)
    catch case _: CharacterCodingException => None

  /** A file as strict UTF-8, or why it is not readable: `NotUtf8`, or `osError`'s text. */
  def readUtf8(path: os.Path): Either[String, String] =
    try decodeUtf8(os.read.bytes(path)).toRight(NotUtf8)
    catch case e: IOException => Left(osError(e))

  /** An I/O error as the OS words it, errno included ("No such file or directory (os error 2)"):
    * the messages these tools print are compared with the ones they printed before.
    */
  def osError(e: IOException): String = e match
    case _: java.nio.file.NoSuchFileException        => "No such file or directory (os error 2)"
    case _: java.nio.file.AccessDeniedException      => "Permission denied (os error 13)"
    case _: java.nio.file.NotDirectoryException      => "Not a directory (os error 20)"
    case _: java.nio.file.FileAlreadyExistsException => "File exists (os error 17)"
    case _: java.nio.file.DirectoryNotEmptyException => "Directory not empty (os error 39)"
    case e: java.nio.file.FileSystemException if e.getReason == "Is a directory" =>
      "Is a directory (os error 21)"
    case e: java.nio.file.FileSystemException if e.getReason != null => e.getReason
    case e if e.getMessage == "Is a directory" => "Is a directory (os error 21)"
    case e                                     => e.getMessage

  /** Whether `path`'s name ends in `.ext`; a leading dot starts a name, so `.md` has no extension.
    */
  def hasExtension(path: os.Path, ext: String): Boolean =
    val name = path.last
    name.lastIndexOf('.') match
      case i if i > 0 => name.substring(i + 1) == ext
      case _          => false

  /** Trimmed of Unicode White_Space, of which String.trim and strip each miss a little. */
  def trimUnicode(s: String): String = s.replaceAll("(?U)^\\s+|\\s+$", "")

  /** `text` as JSON, or the parser's message. Trap: ujson.read wraps a parse error in a
    * TraceException; the cause carries the message.
    */
  def readJson(text: String): Either[String, ujson.Value] =
    try Right(ujson.read(text))
    catch
      case e: upickle.core.TraceVisitor.TraceException =>
        Left(Option(e.getCause).getOrElse(e).getMessage)
      case e: ujson.ParsingFailedException => Left(e.getMessage)

  /** `value[key]` when `value` is an object holding `key`. */
  def field(value: ujson.Value, key: String): Option[ujson.Value] = value.objOpt.flatMap(_.get(key))

  // ---- JSON as Python wrote it ----------------------------------------------------------------

  /** `json.dumps(value, indent=..., ensure_ascii=...)`, separators and escapes included. Trap:
    * ujson keeps every number as a Double, so a whole one is written without `.0`.
    */
  def dumps(value: ujson.Value, indent: Option[Int] = None, ensureAscii: Boolean = true): String =
    render(value, indent, ensureAscii, 0)

  private def render(v: ujson.Value, indent: Option[Int], ascii: Boolean, depth: Int): String =
    def inner(item: ujson.Value) = render(item, indent, ascii, depth + 1)
    v match
      case ujson.Null       => "null"
      case ujson.Bool(b)    => if b then "true" else "false"
      case ujson.Num(n)     => formatNumber(n)
      case ujson.Str(s)     => quote(s, ascii)
      case ujson.Arr(items) =>
        if items.isEmpty then "[]" else container("[", "]", items.map(inner), indent, depth)
      case ujson.Obj(map) =>
        if map.isEmpty then "{}"
        else
          val members = map.map((k, item) => s"${quote(k, ascii)}: ${inner(item)}")
          container("{", "}", members, indent, depth)

  /** The members between their brackets: one a line at `indent`, or all on one line. */
  private def container(
      open: String,
      close: String,
      members: Iterable[String],
      indent: Option[Int],
      depth: Int
  ): String = indent match
    case Some(n) =>
      def newline(level: Int) = "\n" + " " * (n * level)
      members.map(newline(depth + 1) + _).mkString(open, ",", newline(depth) + close)
    case None => members.mkString(open, ", ", close)

  def formatNumber(n: Double): String =
    if n.isWhole && math.abs(n) < 1e16 then n.toLong.toString else n.toString

  private def quote(s: String, ascii: Boolean): String =
    s.map {
      case '"'                    => "\\\""
      case '\\'                   => "\\\\"
      case '\n'                   => "\\n"
      case '\r'                   => "\\r"
      case '\t'                   => "\\t"
      case '\b'                   => "\\b"
      case '\f'                   => "\\f"
      case c if c < 0x20          => f"\\u${c.toInt}%04x"
      case c if ascii && c > 0x7f => f"\\u${c.toInt}%04x" // UTF-16 units, as Python
      case c                      => c.toString
    }.mkString("\"", "", "\"")

  // ---- Python's repr, for messages that quoted a value with !r --------------------------------

  def pyrepr(v: Option[ujson.Value]): String = v match
    case None | Some(ujson.Null) => "None"
    case Some(ujson.Str(s))      => pyreprStr(s)
    case Some(other)             => other.render()

  def pyreprStr(s: String): String =
    val quote = if s.contains('\'') && !s.contains('"') then '"' else '\''
    val body = s.map {
      case '\\'            => "\\\\"
      case '\n'            => "\\n"
      case '\r'            => "\\r"
      case '\t'            => "\\t"
      case c if c == quote => s"\\$c"
      case c               => c.toString
    }
    body.mkString(quote.toString, "", quote.toString)

  // ---- str.splitlines -------------------------------------------------------------------------

  private val lineBreaks =
    Set('\n', '\u000b', '\u000c', '\u001c', '\u001d', '\u001e', '\u0085', ' ', ' ')

  /** `str.splitlines(keepends=True)`: every line boundary Python knows, not only `\n`. */
  def splitlinesKeepends(text: String): Vector[String] =
    // Where the line break at `i` ends, or None when `i` is not one.
    def breakEnd(i: Int): Option[Int] = text(i) match
      case '\r' => Some(if i + 1 < text.length && text(i + 1) == '\n' then i + 2 else i + 1)
      case c    => Option.when(lineBreaks(c))(i + 1)
    @tailrec def from(start: Int, i: Int, lines: Vector[String]): Vector[String] =
      if i >= text.length then if start < text.length then lines :+ text.substring(start) else lines
      else
        breakEnd(i) match
          case Some(end) => from(end, end, lines :+ text.substring(start, end))
          case None      => from(start, i + 1, lines)
    from(0, 0, Vector.empty)

  /** `str.splitlines()`: the same lines, boundaries dropped. */
  def splitlines(text: String): Vector[String] =
    splitlinesKeepends(text).map { l =>
      if l.endsWith("\r\n") then l.dropRight(2)
      else if l.nonEmpty && (l.last == '\r' || lineBreaks(l.last)) then l.dropRight(1)
      else l
    }

  // ---- URLs -----------------------------------------------------------------------------------

  /** `urllib.parse.unquote`: percent-decoded as UTF-8, a bad sequence replaced. */
  def unquote(s: String): String =
    def escape(i: Int): Option[Byte] =
      val hex = if s(i) == '%' && i + 3 <= s.length then s.substring(i + 1, i + 3) else ""
      Option.when(hex.length == 2 && hex.forall(Character.digit(_, 16) >= 0))(
        Integer.parseInt(hex, 16).toByte
      )
    @tailrec def from(i: Int, bytes: Vector[Byte]): Vector[Byte] =
      if i >= s.length then bytes
      else
        escape(i) match
          case Some(byte) => from(i + 3, bytes :+ byte)
          case None       => from(i + 1, bytes ++ s(i).toString.getBytes(StandardCharsets.UTF_8))
    String(from(0, Vector.empty).toArray, StandardCharsets.UTF_8)

  /** `urllib.parse.urlencode` over `quote_plus`. */
  def urlencode(pairs: Seq[(String, String)]): String =
    pairs.map((k, v) => s"${quotePlus(k)}=${quotePlus(v)}").mkString("&")

  private def quotePlus(s: String): String =
    s.getBytes(StandardCharsets.UTF_8)
      .map { b =>
        val c = (b & 0xff).toChar
        if c.isLetterOrDigit && c < 0x80 || "_.-~".contains(c) then c.toString
        else if c == ' ' then "+"
        else f"%%${b & 0xff}%02X"
      }
      .mkString

  /** The path part of an absolute URL, as `urlsplit(url).path` reads it. */
  def urlPath(url: String): String =
    def beforeQuery(s: String) = s.takeWhile(c => c != '?' && c != '#')
    url.indexOf("://") match
      case -1 => beforeQuery(url)
      case i  =>
        val rest = beforeQuery(url.substring(i + 3))
        rest.indexOf('/') match
          case -1 => ""
          case j  => rest.substring(j)

  // ---- posixpath ------------------------------------------------------------------------------

  object posix:
    /** `posixpath.normpath`. */
    def normpath(path: String): String =
      if path.isEmpty then "."
      else
        val initial =
          if path.startsWith("//") && !path.startsWith("///") then 2
          else if path.startsWith("/") then 1
          else 0
        val comps = path.split('/').filter(comp => comp.nonEmpty && comp != ".")
        val parts = comps.foldLeft(Vector.empty[String]) { (parts, comp) =>
          if comp != ".." || (initial == 0 && parts.isEmpty) || parts.lastOption.contains("..")
          then parts :+ comp
          else parts.dropRight(1)
        }
        val out = "/" * initial + parts.mkString("/")
        if out.isEmpty then "." else out

    /** `posixpath.dirname`. */
    def dirname(path: String): String = path.lastIndexOf('/') match
      case -1 => ""
      case i  =>
        val head = path.substring(0, i + 1)
        val trimmed = head.reverse.dropWhile(_ == '/').reverse
        if trimmed.isEmpty then head else trimmed

    /** `posixpath.join(a, b)`. */
    def join(a: String, b: String): String =
      if b.startsWith("/") || a.isEmpty then b
      else if a.endsWith("/") then a + b
      else s"$a/$b"

    /** `posixpath.splitext(path)[1]`. */
    def extension(path: String): String =
      val base = path.substring(path.lastIndexOf('/') + 1)
      val leading = base.takeWhile(_ == '.').length
      base.substring(leading).lastIndexOf('.') match
        case -1 => ""
        case i  => base.substring(leading + i)

    /** `posixpath.relpath(path, start)` for two relative paths under one directory. */
    def relpath(path: String, start: String): String =
      def comps(p: String) = normpath(p).split('/').filter(c => c.nonEmpty && c != ".").toVector
      val pc = comps(path)
      val sc = comps(start)
      val common = pc.zip(sc).takeWhile(_ == _).length
      val out = Vector.fill(sc.length - common)("..") ++ pc.drop(common)
      if out.isEmpty then "." else out.mkString("/")

  // ---- fnmatch --------------------------------------------------------------------------------

  /** `fnmatch.fnmatchcase`: `*`, `?`, `[seq]` and `[!seq]`; `*` crosses `/`. */
  def fnmatchcase(name: String, pattern: String): Boolean =
    java.util.regex.Pattern.matches(fnmatchRegex(pattern), name)

  /** `fnmatch.translate`, without its `(?s:...)\Z` wrapper. */
  private def fnmatchRegex(pattern: String): String =
    def at(i: Int, c: Char) = i < pattern.length && pattern(i) == c
    // The `]` closing the set opened at `open`: a leading `!`, then a leading `]`, are its members.
    def setEnd(open: Int): Option[Int] =
      val afterBang = if at(open + 1, '!') then open + 2 else open + 1
      val first = if at(afterBang, ']') then afterBang + 1 else afterBang
      Option(pattern.indexOf(']', first)).filter(_ >= 0)
    @tailrec def from(i: Int, out: Vector[String]): Vector[String] =
      if i >= pattern.length then out
      else
        pattern(i) match
          case '*' => from(i + 1, out :+ ".*")
          case '?' => from(i + 1, out :+ ".")
          case '[' =>
            setEnd(i) match
              case None    => from(i + 1, out :+ "\\[")
              case Some(j) =>
                val body = pattern.substring(i + 1, j).replace("\\", "\\\\")
                val cls = if body.startsWith("!") then "^" + body.drop(1) else body
                from(j + 1, out :+ s"[$cls]")
          case c => from(i + 1, out :+ java.util.regex.Pattern.quote(c.toString))
    "(?s)" + from(0, Vector.empty).mkString
