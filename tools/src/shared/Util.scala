package marola.tools.shared

import java.io.IOException
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.nio.ByteBuffer

// Shared helpers: the repo root, strict UTF-8 reads, JSON written byte for byte as Python's
// `json.dumps` writes it (the published files keep that exact form, and readers grep them), and
// the `posixpath`/`fnmatch` pieces the link rewriter needs.
object Util:

  /** The umbrella's checkout. `scripts/marola-tools` exports MAROLA_ROOT; otherwise the nearest
    * directory above the working directory that holds `tools/project.scala`.
    */
  lazy val repoRoot: os.Path =
    sys.env.get("MAROLA_ROOT").filter(_.nonEmpty).map(os.Path(_, os.pwd)).getOrElse {
      Iterator
        .iterate(os.pwd)(_ / os.up)
        .take(os.pwd.segmentCount + 1)
        .find(dir => os.exists(dir / "tools" / "project.scala"))
        .getOrElse(sys.error("marola-tools: run me inside the umbrella, or set MAROLA_ROOT"))
    }

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
    val out = StringBuilder()
    writeValue(out, value, indent, ensureAscii, 0)
    out.toString

  private def writeValue(
      out: StringBuilder,
      v: ujson.Value,
      indent: Option[Int],
      ascii: Boolean,
      depth: Int
  ): Unit = v match
    case ujson.Null       => out ++= "null"
    case ujson.Bool(b)    => out ++= (if b then "true" else "false")
    case ujson.Num(n)     => out ++= formatNumber(n)
    case ujson.Str(s)     => writeStr(out, s, ascii)
    case ujson.Arr(items) =>
      if items.isEmpty then out ++= "[]"
      else
        out += '['
        for (item, i) <- items.zipWithIndex do
          separator(out, i, indent, depth + 1)
          writeValue(out, item, indent, ascii, depth + 1)
        close(out, indent, depth)
        out += ']'
    case ujson.Obj(map) =>
      if map.isEmpty then out ++= "{}"
      else
        out += '{'
        for ((k, item), i) <- map.zipWithIndex do
          separator(out, i, indent, depth + 1)
          writeStr(out, k, ascii)
          out ++= ": "
          writeValue(out, item, indent, ascii, depth + 1)
        close(out, indent, depth)
        out += '}'

  def formatNumber(n: Double): String =
    if n.isWhole && math.abs(n) < 1e16 then n.toLong.toString else n.toString

  private def separator(out: StringBuilder, i: Int, indent: Option[Int], depth: Int): Unit =
    indent match
      case Some(n) =>
        if i > 0 then out += ','
        out += '\n'
        out ++= " " * (n * depth)
      case None => if i > 0 then out ++= ", "

  private def close(out: StringBuilder, indent: Option[Int], depth: Int): Unit =
    indent.foreach { n =>
      out += '\n'
      out ++= " " * (n * depth)
    }

  private def writeStr(out: StringBuilder, s: String, ascii: Boolean): Unit =
    out += '"'
    s.foreach {
      case '"'                    => out ++= "\\\""
      case '\\'                   => out ++= "\\\\"
      case '\n'                   => out ++= "\\n"
      case '\r'                   => out ++= "\\r"
      case '\t'                   => out ++= "\\t"
      case '\b'                   => out ++= "\\b"
      case '\f'                   => out ++= "\\f"
      case c if c < 0x20          => out ++= f"\\u${c.toInt}%04x"
      case c if ascii && c > 0x7f => out ++= f"\\u${c.toInt}%04x" // UTF-16 units, as Python
      case c                      => out += c
    }
    out += '"'

  // ---- Python's repr, for messages that quoted a value with !r --------------------------------

  def pyrepr(v: Option[ujson.Value]): String = v match
    case None | Some(ujson.Null) => "None"
    case Some(ujson.Str(s))      => pyreprStr(s)
    case Some(other)             => other.render()

  def pyreprStr(s: String): String =
    val quote = if s.contains('\'') && !s.contains('"') then '"' else '\''
    val out = StringBuilder()
    out += quote
    s.foreach {
      case '\\'            => out ++= "\\\\"
      case '\n'            => out ++= "\\n"
      case '\r'            => out ++= "\\r"
      case '\t'            => out ++= "\\t"
      case c if c == quote => out += '\\'; out += c
      case c               => out += c
    }
    out += quote
    out.toString

  // ---- str.splitlines -------------------------------------------------------------------------

  private val lineBreaks =
    Set('\n', '\u000b', '\u000c', '\u001c', '\u001d', '\u001e', '\u0085', '\u2028', '\u2029')

  /** `str.splitlines(keepends=True)`: every line boundary Python knows, not only `\n`. */
  def splitlinesKeepends(text: String): Vector[String] =
    val out = Vector.newBuilder[String]
    var start = 0
    var i = 0
    while i < text.length do
      val c = text(i)
      val end =
        if c == '\r' then if i + 1 < text.length && text(i + 1) == '\n' then i + 2 else i + 1
        else if lineBreaks(c) then i + 1
        else -1
      if end >= 0 then
        out += text.substring(start, end)
        start = end
        i = end
      else i += 1
    if start < text.length then out += text.substring(start)
    out.result()

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
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while i < s.length do
      val c = s(i)
      val hex = if c == '%' && i + 3 <= s.length then s.substring(i + 1, i + 3) else ""
      if c == '%' && hex.length == 2 && hex.forall(Character.digit(_, 16) >= 0) then
        bytes.write(Integer.parseInt(hex, 16))
        i += 3
      else
        bytes.writeBytes(c.toString.getBytes(StandardCharsets.UTF_8))
        i += 1
    String(bytes.toByteArray, StandardCharsets.UTF_8)

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
        val parts = scala.collection.mutable.ArrayBuffer.empty[String]
        for comp <- path.split('/') if comp.nonEmpty && comp != "." do
          if comp != ".." || (initial == 0 && parts.isEmpty) || parts.lastOption.contains("..")
          then parts += comp
          else if parts.nonEmpty then parts.dropRightInPlace(1)
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
    val out = StringBuilder()
    var i = 0
    while i < pattern.length do
      pattern(i) match
        case '*' => out ++= ".*"; i += 1
        case '?' => out += '.'; i += 1
        case '[' =>
          var j = i + 1
          if j < pattern.length && pattern(j) == '!' then j += 1
          if j < pattern.length && pattern(j) == ']' then j += 1
          while j < pattern.length && pattern(j) != ']' do j += 1
          if j >= pattern.length then
            out ++= "\\["
            i += 1
          else
            val body = pattern.substring(i + 1, j).replace("\\", "\\\\")
            val cls = if body.startsWith("!") then "^" + body.drop(1) else body
            out ++= s"[$cls]"
            i = j + 1
        case c => out ++= java.util.regex.Pattern.quote(c.toString); i += 1
    "(?s)" + out.toString
