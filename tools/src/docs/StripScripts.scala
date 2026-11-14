package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Util

import java.util.regex.{Matcher, Pattern}
import mainargs.{arg, main, Flag, Leftover, ParserForMethods}

// Make generated API docs obey the site's `script-src 'self'` CSP (MIP-0044 §5.6): drop every
// third-party `<script src="https://...">` scaladoc emits, and move its inline `pathToRoot` to a
// `data-path-to-root` attribute that a same-origin shim reads.
//
//     marola-tools strip-external-scripts out/scala out/python
//     marola-tools strip-external-scripts --check mkdocs/generated-docs
object StripScripts:
  // Unicode \s and case folding.
  private def regex(pattern: String): Pattern =
    Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS)

  val ExternalScript = regex(
    raw"""(?i)<script[^>]*\ssrc=["']https?://[^"']*["'][^>]*>\s*</script>"""
  )
  private val PathToRoot =
    regex(raw"""(?i)<script[^>]*>\s*var\s+pathToRoot\s*=\s*["']([^"']*)["']\s*;?\s*</script>""")
  private val Body = regex("<body([^>]*)>")

  val ShimJs =
    """// Restores the `pathToRoot` global scaladoc's own scripts expect, without an inline
      |// <script> — read from <body data-path-to-root>, so the page keeps script-src 'self'. MIP-0044.
      |var pathToRoot = document.body.getAttribute("data-path-to-root") || "";
      |""".stripMargin

  private def replaceFirst(pattern: Pattern, text: String, replacement: String): String =
    pattern.matcher(text).replaceFirst(Matcher.quoteReplacement(replacement))

  /** Remove third-party scripts and inline pathToRoot; the new html and how many scripts went. */
  def strip(html: String): (String, Int) =
    val matcher = PathToRoot.matcher(html)
    val withShim =
      if !matcher.find() then html
      else
        val root = matcher.group(1)
        val withoutInline = replaceFirst(PathToRoot, html, "")
        val body = Body.matcher(withoutInline)
        val withData =
          if !body.find() then withoutInline
          else
            withoutInline.substring(0, body.start) +
              s"""<body${body.group(1)} data-path-to-root="$root">""" +
              withoutInline.substring(body.end)
        val shim = s"""<script src="${root}scripts/path-to-root.js"></script>"""
        withData.indexOf("</head>") match
          case -1 => withData
          case i  => withData.substring(0, i) + shim + withData.substring(i)
    val external = ExternalScript.matcher(withShim)
    val removed = Iterator.continually(external).takeWhile(_.find()).length
    (ExternalScript.matcher(withShim).replaceAll(""), removed)

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
      case (file, shown) if readOrNone(file).exists(ExternalScript.matcher(_).find()) => shown
    }
    offenders.take(5).foreach(f => System.err.println(s"external script src survives in $f"))
    if offenders.nonEmpty then
      System.err.println(s"${offenders.length} file(s) would violate the site CSP")
      1
    else
      println("no external script src in the generated tree")
      0

  /** Strip every html file under one directory; (files, scripts removed), or why it stopped. */
  private def processDir(dirArg: String): Either[String, (Int, Int)] =
    val dir = os.Path(dirArg, os.pwd)
    val scripts = java.nio.file.Path.of(dirArg).resolve("scripts")
    if !os.isDir(dir) then Left(s"strip_external_scripts: no such directory: $dirArg")
    else
      try
        os.makeDir.all(dir / "scripts")
        os.write.over(dir / "scripts" / "path-to-root.js", ShimJs)
        val results = for (file, shown) <- htmlFiles(dirArg); html <- readOrNone(file) yield
          val (stripped, removed) = strip(html)
          try
            if stripped != html then os.write.over(file, stripped)
            Right(removed)
          catch case _: java.io.IOException => Left(s"strip_external_scripts: cannot write $shown")
        results
          .collectFirst { case Left(error) => error }
          .toLeft((results.length, results.collect { case Right(n) => n }.sum))
      catch case _: java.io.IOException => Left(s"strip_external_scripts: cannot write $scripts")

  def process(dirs: Seq[String]): Int =
    val outcome = dirs.foldLeft[Either[String, (Int, Int)]](Right((0, 0))) { (sofar, dir) =>
      for (files, removed) <- sofar; (moreFiles, moreRemoved) <- processDir(dir)
      yield (files + moreFiles, removed + moreRemoved)
    }
    outcome match
      case Left(error) =>
        System.err.println(error)
        1
      case Right((files, removed)) =>
        println(s"stripped $removed external <script> tags across $files html files")
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
