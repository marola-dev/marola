package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.{Invocation, Util}

import java.nio.file.{Files, InvalidPathException}
import java.util.regex.{MatchResult, Pattern}
import mainargs.{arg, main, ParserForMethods}
import SiteModel.*

// Every docs.marola.dev link in a built site resolves, and the previous live sitemap's URLs still
// land somewhere, so a page move can't silently drop a URL (MIP-0074 §5.3, §7). No network: the
// old sitemap is a file the caller fetched beforehand (docs.yml curls the live one).
//
//     marola-tools site-links-check mkdocs/generated-docs [--old-sitemap old-sitemap.xml]
object SiteLinks:
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

  /** The moves overrides/404.html forwards, longest old prefix first. */
  def forwardPrefixes(html: String): Vector[Move] =
    val body = allMatches(MovesBlock, html).headOption.map(_.group(1)).getOrElse("")
    Move.longestFirst(allMatches(MovePair, body).map(m => Move(m.group(2), m.group(4))))

  /** The file in `siteDir` that `urlPath` (directory-url style) serves, if any. */
  def resolve(urlPath: String, siteDir: os.Path): Option[java.nio.file.Path] =
    val rel = urlPath.dropWhile(_ == '/')
    if rel.split('/').contains("..") then None
    else
      try
        val dir = siteDir.toNIO.resolve(rel)
        val candidates =
          if rel.isEmpty || rel.endsWith("/") then Seq(dir.resolve(Names.IndexHtml))
          else Seq(dir, dir.resolve(Names.IndexHtml))
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

  /** The docs.marola.dev hrefs under `siteDir`, failed by the ones that don't resolve in it. */
  def checkHrefs(siteDir: os.Path): LinkCheck =
    val hrefs = for
      page <- htmlPages(siteDir)
      html <- readOrEmpty(page).toVector
      m <- allMatches(Href, html)
    yield (page, m.group("url"))
    val broken = hrefs.collect {
      case (page, url) if resolve(Util.unquote(Util.urlPath(url)), siteDir).isEmpty =>
        s"${page.relativeTo(siteDir)}: $url"
    }
    LinkCheck(hrefs.length, broken)

  /** The old sitemap's <loc>s, failed by the ones now dropped: not a page, a redirect stub, nor
    * forwarded by a 404-forward prefix to a page that still exists.
    */
  def checkOldSitemap(siteDir: os.Path, xml: String, moves: Seq[Move]): LinkCheck =
    val locs = allMatches(Loc, xml).map(_.group(1))
    val dropped = locs.filter { loc =>
      val path = Util.unquote(Util.urlPath(loc))
      def forwarded =
        moves.iterator.flatMap(_.forward(path)).nextOption().flatMap(resolve(_, siteDir))
      resolve(path, siteDir).isEmpty && forwarded.isEmpty
    }
    LinkCheck(locs.length, dropped)

  @main(doc =
    "Every docs.marola.dev link in a built site resolves, and no old sitemap URL was dropped"
  )
  def run(
      @arg(positional = true) siteDir: String,
      @arg(doc = "The live sitemap.xml, fetched before the build") oldSitemap: Option[String] = None
  ): Int =
    val invocation = Invocation.current()
    val site = invocation.path(siteDir)
    if !os.isDir(site) then
      System.err.println(s"site_links_check: no such directory: $siteDir")
      1
    else
      val hrefs = checkHrefs(site)
      val sitemap = oldSitemap.map { sitemap =>
        val overrides = invocation.repoRoot / Names.Mkdocs / Names.Overrides / Names.NotFoundPage
        val moves = readOrEmpty(overrides).map(forwardPrefixes).getOrElse(Vector.empty)
        val xml = readOrEmpty(invocation.path(sitemap)).getOrElse("")
        OldSitemap(sitemap, checkOldSitemap(site, xml, moves))
      }
      val verdict = SiteLinksVerdict(hrefs, sitemap)
      verdict.errors.foreach(System.err.println)
      if verdict.ok then
        println(verdict.summary)
        0
      else 1

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "site-links-check", args)
