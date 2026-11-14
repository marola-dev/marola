package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Util

import mainargs.{arg, main, Flag, Leftover, ParserForMethods}
import SiteModel.*

// Make generated API docs obey the site's `script-src 'self'` CSP (MIP-0044 §5.6): drop every
// third-party `<script src="https://...">` scaladoc emits, and move its inline `pathToRoot` to a
// `data-path-to-root` attribute that a same-origin shim reads.
//
//     marola-tools strip-external-scripts out/scala out/python
//     marola-tools strip-external-scripts --check mkdocs/generated-docs
object StripScripts:
  /** Each html file under `dir`, named as walking the path dirArg on the command line names it. */
  private def htmlFiles(dirArg: String): Vector[(os.Path, String)] =
    val dir = os.Path(dirArg, os.pwd)
    os.walk(dir)
      .filter(p => os.isFile(p) && Util.hasExtension(p, "html"))
      .map(p => (p, java.nio.file.Path.of(dirArg).resolve(p.relativeTo(dir).toString).toString))
      .toVector

  private def readOrNone(path: os.Path): Option[String] =
    try Some(Util.readLossy(path))
    catch case _: java.io.IOException => None

  /** Report any real external <script src> left in the tree, using ExternalScript itself: a grep
    * for `src="http` also matches this tool's own documentation once rendered (escaped text about
    * script tags, not a script tag).
    */
  def check(dirs: Seq[String]): Int =
    val offenders = dirs.filter(d => os.isDir(os.Path(d, os.pwd))).flatMap(htmlFiles).collect {
      case (file, shown) if readOrNone(file).exists(ApiDocsPage.hasExternalScript) => shown
    }
    offenders.take(5).foreach(f => System.err.println(s"external script src survives in $f"))
    if offenders.nonEmpty then
      System.err.println(s"${offenders.length} file(s) would violate the site CSP")
      1
    else
      println("no external script src in the generated tree")
      0

  /** Strip every html file under one directory, or why it stopped. */
  private def processDir(dirArg: String): Either[String, StripTally] =
    val dir = os.Path(dirArg, os.pwd)
    val scripts = java.nio.file.Path.of(dirArg).resolve(ApiDocsPage.ShimDir)
    if !os.isDir(dir) then Left(s"strip_external_scripts: no such directory: $dirArg")
    else
      try
        os.makeDir.all(dir / ApiDocsPage.ShimDir)
        os.write.over(dir / ApiDocsPage.ShimDir / ApiDocsPage.ShimFile, ApiDocsPage.ShimJs)
        val results = for (file, shown) <- htmlFiles(dirArg); html <- readOrNone(file) yield
          val stripped = ApiDocsPage.strip(html)
          try
            if stripped.html != html then os.write.over(file, stripped.html)
            Right(stripped.removed)
          catch case _: java.io.IOException => Left(s"strip_external_scripts: cannot write $shown")
        results
          .collectFirst { case Left(error) => error }
          .toLeft(StripTally(results.length, results.collect { case Right(n) => n }.sum))
      catch case _: java.io.IOException => Left(s"strip_external_scripts: cannot write $scripts")

  def process(dirs: Seq[String]): Int =
    val outcome = dirs.foldLeft[Either[String, StripTally]](Right(StripTally.Zero)) {
      (sofar, dir) => for tally <- sofar; more <- processDir(dir) yield tally + more
    }
    outcome match
      case Left(error) =>
        System.err.println(error)
        1
      case Right(tally) =>
        println(tally.summary)
        0

  @main(doc = "Drop third-party <script src> from generated API docs (MIP-0044 §5.6)")
  def run(
      @arg(doc = "Report survivors, change nothing") check: Flag,
      dirs: Leftover[String]
  ): Int =
    if dirs.value.isEmpty then
      System.err.println("marola-tools strip-external-scripts: at least one DIR is required")
      2
    else if check.value then this.check(dirs.value)
    else process(dirs.value)

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "strip-external-scripts", args)
