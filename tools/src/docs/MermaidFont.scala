package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.{Invocation, Util}

import mainargs.{main, Leftover, ParserForMethods}

// Pin the font Kroki measures Mermaid labels in (#511): every top-level ```mermaid fence gets an
// init line naming a font Kroki has. It rewrites the staged copy `marola-tools mkdocs` bakes into
// the image, before mkdocs and the kroki plugin read it.
//
//     marola-tools mermaid-font DIR...   # rewrite every .md under each DIR in place
object MermaidFont:
  private val Markdown = "md"

  // An unreadable file is thrown, not reported: only bad UTF-8 is a message.
  private def readUtf8(path: os.Path, shown: String): Either[String, String] =
    Util.decodeUtf8(os.read.bytes(path)).toRight(s"$shown: ${Util.NotUtf8}")

  private def pinDir(cwd: os.Path)(dirArg: String): Either[String, PinnedPages] =
    val dir = os.Path(dirArg, cwd)
    if !os.isDir(dir) then Left(s"mermaid-font: no such directory: $dirArg")
    else
      val pages = os.walk(dir).filter(p => os.isFile(p) && Util.hasExtension(p, Markdown)).sorted
      pages.foldLeft[Either[String, PinnedPages]](Right(PinnedPages(0))) { (sofar, page) =>
        val shown = java.nio.file.Path.of(dirArg).resolve(page.relativeTo(dir).toString).toString
        for
          changed <- sofar
          text <- readUtf8(page, shown)
        yield
          val pinned = MermaidFence.pin(text)
          if pinned == text then changed
          else
            os.write.over(page, pinned)
            changed + PinnedPages(1)
      }

  def pin(dirs: Seq[String], cwd: os.Path): Either[String, Unit] =
    dirs
      .foldLeft[Either[String, PinnedPages]](Right(PinnedPages(0)))((sofar, dir) =>
        sofar.flatMap(n => pinDir(cwd)(dir).map(_ + n))
      )
      .map(changed => println(changed.summary))

  @main(doc = "Pin the font Kroki measures Mermaid labels in, in every .md under each DIR (#511)")
  def run(dirs: Leftover[String]): Int =
    if dirs.value.isEmpty then
      System.err.println("marola-tools mermaid-font: at least one DIR is required")
      2
    else Cli.report(pin(dirs.value, Invocation.current().cwd))

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "mermaid-font", args)
