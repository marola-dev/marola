package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Util

import java.nio.file.{Files, InvalidPathException}
import java.util.regex.{MatchResult, Pattern}
import mainargs.{arg, main, ParserForMethods}

// Every docs.marola.dev link in a built site resolves, and the previous live sitemap's URLs still
// land somewhere, so a page move can't silently drop a URL (MIP-0074 §5.3, §7). No network: the
// old sitemap is a file the caller fetched beforehand (docs.yml curls the live one).
//
//     marola-tools site-links-check mkdocs/generated-docs [--old-sitemap old-sitemap.xml]
object SiteLinks:
  val Site = "https://docs.marola.dev"

  // Unicode classes, and only \n ends a line.
  private def regex(pattern: String): Pattern =
    Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES)

  // The quote is captured once and matched again so the value must be exactly Site, optionally
  // followed by a `/...` path: not merely start with it, which would also take
  // https://docs.marola.dev.example/ as this site.
  private val Href =
    regex(
      raw"""(?i)(?<=\s)href\s*=\s*(?<q>["'])(?<url>""" + Pattern.quote(
        Site
      ) + raw"""(?:/[^"']*)?)\k<q>"""
    )
  private val Loc = regex(raw"(?i)<loc>\s*([^<\s]+)\s*</loc>")
  // mkdocs/overrides/404.html's inline array (MIP-0074 Appendix B): read, not owned.
  private val MovesBlock = regex(raw"(?s)\bmoves\s*=\s*\[(.*?)\]\s*\.\s*sort")
  private val MovePair = regex(raw"""\[\s*(['"])(.*?)\1\s*,\s*(['"])(.*?)\3\s*\]""")

  private def allMatches(pattern: Pattern, text: String): Vector[MatchResult] =
    val matcher = pattern.matcher(text)
    Iterator.continually(matcher).takeWhile(_.find()).map(_.toMatchResult).toVector

  /** The old->new URL pairs overrides/404.html forwards, longest old first: the tie-break its own
    * inline script uses, so a path under two prefixes matches the narrower one.
    */
  def forwardPrefixes(html: String): Vector[(String, String)] =
    val body = allMatches(MovesBlock, html).headOption.map(_.group(1)).getOrElse("")
    allMatches(MovePair, body)
      .map(m => (m.group(2), m.group(4)))
      .sortBy((old, _) => -old.codePointCount(0, old.length))

  /** The file in `siteDir` that `urlPath` (directory-url style) serves, if any. */
  def resolve(urlPath: String, siteDir: os.Path): Option[java.nio.file.Path] =
    val rel = urlPath.dropWhile(_ == '/')
    if rel.split('/').contains("..") then None
    else
      try
        val dir = siteDir.toNIO.resolve(rel)
        val candidates =
          if rel.isEmpty || rel.endsWith("/") then Seq(dir.resolve("index.html"))
          else Seq(dir, dir.resolve("index.html"))
        candidates.find(Files.isRegularFile(_))
      catch case _: InvalidPathException => None

  private def htmlPages(siteDir: os.Path): Vector[os.Path] =
    os.walk(siteDir)
      .filter(p => os.isFile(p) && Util.hasExtension(p, "html"))
      .sortBy(_.segments.toList)(using Ordering.Implicits.seqOrdering)
      .toVector

  private def readOrEmpty(path: os.Path): Option[String] =
    try Some(Util.readLossy(path))
    catch case _: java.io.IOException => None

  /** (how many docs.marola.dev hrefs are under `siteDir`, which of them don't resolve in it). */
  def checkHrefs(siteDir: os.Path): (Int, Vector[String]) =
    val hrefs = for
      page <- htmlPages(siteDir)
      html <- readOrEmpty(page).toVector
      m <- allMatches(Href, html)
    yield (page, m.group("url"))
    val broken = hrefs.collect {
      case (page, url) if resolve(Util.unquote(Util.urlPath(url)), siteDir).isEmpty =>
        s"${page.relativeTo(siteDir)}: $url"
    }
    (hrefs.length, broken)

  /** (how many <loc> the old sitemap had, which of them are now dropped: not a page, a redirect
    * stub, nor forwarded by a 404-forward prefix to a page that still exists). The count is the
    * caller's signal that the sitemap was real: zero must never read as "nothing dropped".
    */
  def checkOldSitemap(
      siteDir: os.Path,
      xml: String,
      moves: Seq[(String, String)]
  ): (Int, Vector[String]) =
    val locs = allMatches(Loc, xml).map(_.group(1))
    val dropped = locs.filter { loc =>
      val path = Util.unquote(Util.urlPath(loc))
      def forwarded = moves
        .find((old, _) => path.startsWith(old))
        .flatMap((old, replacement) => resolve(replacement + path.substring(old.length), siteDir))
      resolve(path, siteDir).isEmpty && forwarded.isEmpty
    }
    (locs.length, dropped)

  @main(doc =
    "Every docs.marola.dev link in a built site resolves, and no old sitemap URL was dropped"
  )
  def run(
      @arg(positional = true) siteDir: String,
      @arg(doc = "The live sitemap.xml, fetched before the build") oldSitemap: Option[String] = None
  ): Int =
    val site = os.Path(siteDir, os.pwd)
    if !os.isDir(site) then
      System.err.println(s"site_links_check: no such directory: $siteDir")
      1
    else
      val (hrefTotal, broken) = checkHrefs(site)
      val (oldTotal, dropped) = oldSitemap match
        case None          => (0, Vector.empty)
        case Some(sitemap) =>
          val overrides = Util.repoRoot / "mkdocs" / "overrides" / "404.html"
          val moves = readOrEmpty(overrides).map(forwardPrefixes).getOrElse(Vector.empty)
          val xml = readOrEmpty(os.Path(sitemap, os.pwd)).getOrElse("")
          checkOldSitemap(site, xml, moves)
      broken.foreach(b => System.err.println(s"broken href: $b"))
      dropped.foreach(d => System.err.println(s"dropped url: $d"))
      val sitemapEmpty = oldSitemap.isDefined && oldTotal == 0
      oldSitemap.filter(_ => sitemapEmpty).foreach { sitemap =>
        System.err.println(
          s"site_links_check: $sitemap has no <loc> at all — an empty fetch, an error page, or a sitemap format this check doesn't parse; not a clean sitemap"
        )
      }
      if broken.nonEmpty || dropped.nonEmpty || sitemapEmpty then
        System.err.println(
          s"site_links_check: ${broken.length} broken href(s), ${dropped.length} dropped url(s)"
        )
        1
      else
        if oldSitemap.isDefined then
          println(s"site_links_check: ok ($hrefTotal href(s), $oldTotal old url(s) checked)")
        else println(s"site_links_check: ok ($hrefTotal href(s) checked)")
        0

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "site-links-check", args)
