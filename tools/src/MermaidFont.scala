import java.util.Locale
import java.util.regex.Pattern
import mainargs.{main, Leftover, ParserForMethods}

// Pin the font Kroki measures Mermaid labels in (#511): every top-level ```mermaid fence gets an
// init line naming a font Kroki has. It rewrites the staged copy `marola-tools mkdocs` bakes into
// the image, before mkdocs and the kroki plugin read it.
//
//     marola-tools mermaid-font DIR...   # rewrite every .md under each DIR in place
object MermaidFont:
  val FontFamily = """"Open Sans", "trebuchet ms", verdana, arial, sans-serif"""

  val InitLine: String = s"%%{init: {\"fontFamily\": ${Util.dumps(ujson.Str(FontFamily))}}}%%"

  // Mirrors mkdocs_kroki_plugin's own kroki/parsing.py _FENCE_RE (1.7.0): requiring the closing
  // fence to repeat the opening's exact indent and backtick/tilde run is what keeps a ```mermaid```
  // example quoted inside a longer fence (DIAGRAMS.md's "Source" blocks) from being rewritten too.
  private val FenceRe = Pattern.compile(
    raw"(?ism)(?<fence>^(?<indent>[ ]*)(?:````*|~~~~*))[ ]*(?<lang>[\w#.+-]*)[^\n]*\n(?<code>.*?)(?<=\n)\k<fence>[ ]*$$",
    Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES
  )

  /** Prepend the font-pin init line to every top-level ```mermaid fence. */
  def pinMermaidFont(markdown: String): String =
    val out = StringBuilder()
    val m = FenceRe.matcher(markdown)
    var last = 0
    while m.find() do
      out ++= markdown.substring(last, m.start)
      val lang = m.group("lang").trim.toLowerCase(Locale.ROOT)
      val code = m.group("code")
      if lang == "mermaid" && !code.startsWith(InitLine) then
        out ++= markdown.substring(m.start, m.start("code"))
        out ++= m.group("indent") + InitLine + "\n" + code
        out ++= markdown.substring(m.end("code"), m.end)
      else out ++= m.group
      last = m.end
    out ++= markdown.substring(last)
    out.toString

  // An unreadable file is thrown, not reported: only bad UTF-8 is a message.
  private def readUtf8(path: os.Path, shown: String): Either[String, String] =
    Util.decodeUtf8(os.read.bytes(path)).toRight(s"$shown: ${Util.NotUtf8}")

  /** How many pages under `dirArg` it changed. */
  private def pinDir(dirArg: String): Either[String, Int] =
    val dir = os.Path(dirArg, os.pwd)
    if !os.isDir(dir) then Left(s"mermaid-font: no such directory: $dirArg")
    else
      val pages = os.walk(dir).filter(p => os.isFile(p) && Util.hasExtension(p, "md")).sorted
      pages.foldLeft[Either[String, Int]](Right(0)) { (sofar, page) =>
        val shown = java.nio.file.Path.of(dirArg).resolve(page.relativeTo(dir).toString).toString
        for
          changed <- sofar
          text <- readUtf8(page, shown)
        yield
          val pinned = pinMermaidFont(text)
          if pinned == text then changed
          else
            os.write.over(page, pinned)
            changed + 1
      }

  def pin(dirs: Seq[String]): Either[String, Unit] =
    dirs
      .foldLeft[Either[String, Int]](Right(0))((sofar, dir) =>
        sofar.flatMap(n => pinDir(dir).map(_ + n))
      )
      .map(changed => println(s"mermaid-font: pinned the Mermaid font in $changed page(s)"))

  @main(doc = "Pin the font Kroki measures Mermaid labels in, in every .md under each DIR (#511)")
  def run(dirs: Leftover[String]): Int =
    if dirs.value.isEmpty then
      System.err.println("marola-tools mermaid-font: at least one DIR is required")
      2
    else Cli.report(pin(dirs.value))

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "mermaid-font", args)
