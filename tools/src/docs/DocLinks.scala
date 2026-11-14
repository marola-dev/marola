package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Util

import java.nio.file.{AccessDeniedException, Files, InvalidPathException, NoSuchFileException}
import java.text.Normalizer
import java.util.Locale
import java.util.regex.{MatchResult, Pattern}
import mainargs.{arg, main, Flag, ParserForMethods}
import SiteModel.*

// Rewrite one repo's README and docs/** links for its mount on the docs site (MIP-0074 Appendix A).
//
//     marola-tools doc-links --repo DIR --name NAME --sha SHA --out DIR [--umbrella]
//         [--exclude PATTERN]... [--submodule NAME=SHA]...
//     marola-tools doc-links --links FILE...   # FILE<TAB>link per relative link outside code
object DocLinks:

  // Unicode \w \s \d and case folding, and only \n ends a line, so `^`/`$` never stop at a \r.
  // Trap: Java's `$` also matches before a final newline, so an end of input is written `\z`.
  private def regex(pattern: String): Pattern =
    Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES)

  // Fences as docs-lint (marola-devkit scripts/docs_lint.py) sees them, so the two never disagree
  // on what is code: any indent, and only a bare fence line closes.
  private val FenceOpen = regex(raw"^\s*(`{3,}|~{3,})")
  // A code span may wrap lines but never crosses a blank line (CommonMark), so a lone ` can't mask
  // later paragraphs.
  private val CodeSpan = regex(raw"(?s)(?<!`)(`+)(?!`)(?:(?!\n[ \t]*\n).)*?(?<!`)\1(?!`)")
  // Possessive: the label can only end at its first top-level ], so it matches the same as a plain
  // quantifier, and Java's backtracking does not recurse once per character (a stack overflow on a
  // long paragraph after a lone [).
  private val Inline = regex(
    raw"(!?)\[((?:[^\[\]]++|\[[^\[\]]*+\])*+)\]" +
      raw"""\(\s*(<[^>]*>|[^)\s]+)((?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*)\)"""
  )
  private val RefDef = regex(raw"(?m)^( {0,3}\[[^\]]+\]:[ \t]*)(<[^>]*>|\S+)")
  private val Mask = regex(raw"\x00(\d+)\x00")
  private val HtmlTag = regex(raw"<[A-Za-z][A-Za-z0-9-]*\s[^<>]*>")
  private val HtmlAttr = regex(raw"""(?i)(?<=\s)(href|src)(\s*=\s*)(["'])([^"']*)\3""")
  private val Scheme = regex(raw"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//)")
  private val Heading = regex(raw"^ {0,3}#{1,6}[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*\z")
  private val HeadingLink = regex(raw"!?\[([^\]]*)\]\([^)]*\)")
  private val HeadingMarkup = regex(raw"<[^>]+>|[`*]")
  private val GithubDrop = regex(raw"[^\w\- ]")
  private val TocDrop = regex(raw"[^\w\s-]")
  private val TocDash = regex(raw"[-\s]+")
  // A GitHub permalink sha (7-40 hex, abbreviated or full) or a version tag: already pinned, so
  // left exactly as written, #L anchors included, with no existence check against ctx.sha.
  private val PinnedRef = regex(raw"^(?:[0-9a-f]{7,40}|v\d[\w.\-]*)\z")

  // ---- regex helpers --------------------------------------------------------------------------

  extension (m: MatchResult)
    private def text(group: Int): String = Option(m.group(group)).getOrElse("")

  private def allMatches(pattern: Pattern, text: String): Vector[MatchResult] =
    val matcher = pattern.matcher(text)
    Iterator.continually(matcher).takeWhile(_.find()).map(_.toMatchResult).toVector

  private def firstMatch(pattern: Pattern, text: String): Option[MatchResult] =
    val matcher = pattern.matcher(text)
    Option.when(matcher.find())(matcher.toMatchResult)

  /** `re.sub(pattern, replace, text)`, each replacement (given its match and its index) taken
    * literally, the replacements' errors in match order.
    */
  private def subRewrite(pattern: Pattern, text: String)(
      replace: (MatchResult, Int) => Rewrite
  ): Rewrite =
    val matches = allMatches(pattern, text)
    val replaced = matches.zipWithIndex.map(replace.tupled)
    val starts = 0 +: matches.map(_.end)
    val pieces = matches.zip(replaced).zip(starts).flatMap { case ((m, r), from) =>
      Vector(Rewrite.clean(text.substring(from, m.start)), r)
    }
    Rewrite.concat(pieces :+ Rewrite.clean(text.substring(starts.last)))

  private def sub(pattern: Pattern, text: String)(replace: MatchResult => String): String =
    subRewrite(pattern, text)((m, _) => Rewrite.clean(replace(m))).text

  // ---- files ---------------------------------------------------------------------------------

  /** `base.join(rel)`. Trap: java.nio drops a trailing /, but on a file the OS refuses it. */
  private def onDisk(base: os.Path, rel: String): Option[java.nio.file.Path] =
    try
      val path = base.toNIO.resolve(rel)
      Option.when(!rel.endsWith("/") || Files.isDirectory(path))(path)
    catch case _: InvalidPathException => None

  private def existsAt(base: os.Path, rel: String) = onDisk(base, rel).exists(Files.exists(_))

  private def onDiskTree(repo: os.Path) = RepoTree(
    isDir = rel => onDisk(repo, rel).exists(Files.isDirectory(_)),
    isFile = rel => onDisk(repo, rel).exists(Files.isRegularFile(_))
  )

  /** A file as strict UTF-8; bad UTF-8 is thrown as an IOException, like a read error. */
  private def readUtf8(path: os.Path): String =
    Util.decodeUtf8(os.read.bytes(path)).getOrElse(throw java.io.IOException(Util.NotUtf8))

  /** An I/O error as the OS words it. Narrower than Util.osError, which also words ENOTDIR, EEXIST
    * and ENOTEMPTY: kept apart so doc-links' messages stay exactly as they are.
    */
  private def ioMessage(e: java.io.IOException): String = e match
    case _: NoSuchFileException                => "No such file or directory (os error 2)"
    case _: AccessDeniedException              => "Permission denied (os error 13)"
    case _ if e.getMessage == "Is a directory" => "Is a directory (os error 21)"
    case _                                     => e.getMessage

  // ---- fences and anchors ---------------------------------------------------------------------

  def fenced(text: String): Vector[Chunk] =
    // The chunks so far, the prose lines not yet closed into a chunk, and the open fence.
    type Reading = (Vector[Chunk], Vector[String], Option[Fence])
    def prose(lines: Vector[String]) = Chunk.Prose(lines.mkString)
    val (done, open, _) =
      Util.splitlinesKeepends(text).foldLeft[Reading]((Vector.empty, Vector.empty, None)) {
        case ((done, lines, Some(fence)), line) =>
          (done :+ Chunk.Code(line), lines, Option.unless(fence.closedBy(line))(fence))
        case ((done, lines, None), line) =>
          firstMatch(FenceOpen, line) match
            case Some(m) =>
              val fence = Fence(m.group(1).head, m.group(1).length)
              (done :+ prose(lines) :+ Chunk.Code(line), Vector.empty, Some(fence))
            case None => (done, lines :+ line, None)
      }
    done :+ prose(open)

  /** The heading's slug made unique against `seen`, as GitHub and toc number repeats. */
  private def unique(slug: String, seen: Map[String, Int], separator: String) =
    val n = seen.getOrElse(slug, 0)
    (if n > 0 then s"$slug$separator$n" else slug, seen.updated(slug, n + 1))

  /** GitHub's anchor (and toc's own, for links already written for the site) -> toc's anchor. */
  def readmeAnchors(readme: String): Map[String, String] =
    val titles = for
      case Chunk.Prose(text) <- fenced(readme)
      line <- Util.splitlines(text)
      heading <- firstMatch(Heading, line)
    yield HeadingMarkup
      .matcher(HeadingLink.matcher(heading.text(1)).replaceAll("$1"))
      .replaceAll("")
    val none = Map.empty[String, Int]
    val (anchors, _, _) = titles.foldLeft((Map.empty[String, String], none, none)) {
      case ((anchors, githubSeen, tocSeen), title) =>
        val github =
          GithubDrop.matcher(title.toLowerCase(Locale.ROOT)).replaceAll("").replace(' ', '-')
        // Python-Markdown toc's default slugify, which mkdocs uses (mkdocs.yml sets none).
        val ascii = Normalizer.normalize(title, Normalizer.Form.NFKD).filter(_ < 0x80)
        val dropped = TocDrop.matcher(ascii).replaceAll("")
        val slug = TocDash.matcher(dropped.trim.toLowerCase(Locale.ROOT)).replaceAll("-")
        val (toc, tocNext) = unique(slug, tocSeen, "_")
        val (anchor, githubNext) = unique(github, githubSeen, "-")
        val withGithub = anchors.updated(anchor, toc)
        (withGithub.updatedWith(toc)(_.orElse(Some(toc))), githubNext, tocNext)
    }
    anchors

  // ---- the rewrite ----------------------------------------------------------------------------

  private class Rewriter(source: String, repo: os.Path, ctx: Context):
    private val ownGithub = regex(
      "^" + Pattern.quote(s"$GitHub/${ctx.name}") +
        raw"/(?:blob|tree)/([^?#]+)(.*)\z"
    )

    private lazy val anchors: Map[String, String] =
      val readme = repo / Names.Readme
      try if os.isFile(readme) then readmeAnchors(readUtf8(readme)) else Map.empty
      catch case _: java.io.IOException => Map.empty

    private val tree = onDiskTree(repo)

    private def fail(link: String, error: LinkError): Rewrite =
      Rewrite(link, Vector(error.line(source, link)))

    /** The site's anchor for a README heading; a missing one is an error and kept as written. */
    private def readmeAnchor(link: String, anchor: String): Rewrite =
      anchors.get(anchor) match
        case Some(toc) => Rewrite.clean(toc)
        case None => Rewrite(anchor, Vector(LinkError.NoReadmeAnchor(anchor).line(source, link)))

    private def github(
        link: String,
        name: String,
        sha: String,
        checkout: os.Path,
        path: String,
        suffix: String
    ): Rewrite =
      onDisk(checkout, Util.unquote(path)).filter(Files.exists(_)) match
        case None       => fail(link, LinkError.MissingAt(sha))
        case Some(full) =>
          val kind = if Files.isDirectory(full) then "tree" else "blob"
          Rewrite.clean(s"$GitHub/$name/$kind/$sha/$path$suffix")

    def target(link: String, image: Boolean): Rewrite =
      if link.isEmpty then Rewrite.clean("")
      else if firstMatch(Scheme, link).isDefined then pinOwnGithub(link)
      else relative(link, image)

    /** A link to this repo on a branch is pinned to ctx.sha; any other absolute link is kept. */
    private def pinOwnGithub(link: String): Rewrite = firstMatch(ownGithub, link) match
      case None    => Rewrite.clean(link)
      case Some(m) =>
        val parts = m.group(1).split("/", -1).toVector
        if firstMatch(PinnedRef, parts.head).isDefined then Rewrite.clean(link)
        else
          // A branch may hold slashes (mip-NNNN/k-*): like GitHub, take the shortest ref whose
          // remaining path exists. A slashed ref with no path at all is refused as ambiguous.
          val refParts = (1 to parts.length).find { i =>
            val rest = parts.drop(i).mkString("/")
            (rest.nonEmpty || i == 1) && existsAt(repo, Util.unquote(rest))
          }
          refParts match
            case Some(i) =>
              val ref = parts.take(i).mkString("/")
              Rewrite.clean(
                link.substring(0, m.start(1)) + ctx.sha + link.substring(m.start(1) + ref.length)
              )
            case None => fail(link, LinkError.MissingAt(ctx.sha))

    private def relative(written: String, image: Boolean): Rewrite =
      val link = Link.parse(written)
      def anchor(readme: Boolean) =
        if readme then readmeAnchor(written, link.anchor) else Rewrite.clean(link.anchor)
      LinkTarget.of(link, source, image, ctx, tree) match
        case LinkTarget.Fragment(readme)    => anchor(readme).map(link.after)
        case LinkTarget.Site(mount, readme) =>
          anchor(readme).map(ctx.siteHref(source, mount) + link.after(_))
        case LinkTarget.GitHub(name, sha, submodule, path) =>
          github(written, name, sha, submodule.fold(repo)(repo / _), path, link.suffix)
        case LinkTarget.Refused(error) => fail(written, error)

    private def bracketed(link: String, image: Boolean): Rewrite =
      if link.length >= 2 && link.startsWith("<") && link.endsWith(">") then
        target(link.substring(1, link.length - 1), image).map(t => s"<$t>")
      else target(link, image)

    private def inlineLinks(text: String): Rewrite =
      subRewrite(Inline, text) { (m, _) =>
        val bang = m.text(1)
        for
          label <- inlineLinks(m.text(2))
          dest <- bracketed(m.text(3), bang.nonEmpty)
        yield s"$bang[$label]($dest${m.text(4)})"
      }

    private def htmlLinks(text: String): Rewrite =
      subRewrite(HtmlTag, text) { (tag, _) =>
        subRewrite(HtmlAttr, tag.group) { (a, _) =>
          val (name, equals, quote) = (a.text(1), a.text(2), a.text(3))
          target(a.text(4), name.equalsIgnoreCase("src")).map(t => s"$name$equals$quote$t$quote")
        }
      }

    def prose(chunk: String): Rewrite =
      // Masked, not split out: a code span can be a link's label, as in [`AGENTS.md`](…).
      val spans = allMatches(CodeSpan, chunk).map(_.group)
      val masked = subRewrite(CodeSpan, chunk)((_, i) => Rewrite.clean(s"\u0000$i\u0000")).text
      for
        refsDone <- subRewrite(RefDef, masked) { (m, _) =>
          bracketed(m.text(2), image = false).map(m.text(1) + _)
        }
        linksDone <- inlineLinks(refsDone)
        htmlDone <- htmlLinks(linksDone)
      yield sub(Mask, htmlDone)(m => spans(m.group(1).toInt))
  end Rewriter

  /** Rewrite `text`, the repo-relative file `source` (README.md or a page under docs/), for the
    * mount.
    *
    * `repo` is the checkout at `ctx.sha`. Returns the new text and one error per link Appendix A
    * refuses; a refused link is left as written.
    */
  def rewrite(text: String, source: String, repo: os.Path, ctx: Context): Rewrite =
    val rewriter = Rewriter(source, repo, ctx)
    Rewrite.concat(fenced(text).map {
      case Chunk.Code(code)   => Rewrite.clean(code)
      case Chunk.Prose(prose) => rewriter.prose(prose)
    })

  private def stripAngles(s: String): String = s.stripPrefix("<").stripSuffix(">")

  /** Every reference definition, inline link, then HTML href=/src= target outside code, as written.
    */
  def links(text: String): Vector[String] =
    def inline(chunk: String): Vector[String] =
      allMatches(Inline, chunk).flatMap(m => stripAngles(m.text(3)) +: inline(m.text(2)))
    fenced(text).flatMap {
      case Chunk.Code(_)      => Vector.empty
      case Chunk.Prose(prose) =>
        val chunk = sub(CodeSpan, prose)(_ => "")
        val refs = allMatches(RefDef, chunk).map(m => stripAngles(m.text(2)))
        val html =
          allMatches(HtmlTag, chunk).flatMap(tag => allMatches(HtmlAttr, tag.group).map(_.text(4)))
        refs ++ inline(chunk) ++ html
    }

  // ---- the mount ------------------------------------------------------------------------------

  /** Write README.md as out/index.md and every file under docs/ beside it, skipping exclude_docs;
    * the errors.
    */
  def mount(repo: os.Path, out: os.Path, ctx: Context): Vector[String] =
    val docsDir = repo / Names.Docs
    val adrDir = docsDir / Names.Adr
    val docs =
      if !os.isDir(docsDir) then Vector.empty
      else os.walk(docsDir).filter(os.isFile(_)).map(_.relativeTo(repo).toString).sorted.toVector
    val published = docs.filterNot { d =>
      excluded(d.stripPrefix(s"${Names.Docs}/"), ctx.excludeDocs, isDir = false)
    }
    val pageErrors = (Names.Readme +: published).flatMap(publish(repo, out, ctx))
    val adrErrors =
      if !os.isDir(adrDir) then Vector.empty
      else adrIndex(adrDir, out / os.RelPath(ctx.docsMount(Names.Adr)), ctx.excludeDocs)
    pageErrors ++ adrErrors

  /** Write one repo file at its place in the mount, a page rewritten; its errors. */
  private def publish(repo: os.Path, out: os.Path, ctx: Context)(source: String): Vector[String] =
    val from = repo / os.RelPath(source)
    val dest = out / os.RelPath(ctx.outPath(source))
    os.makeDir.all(dest / os.up)
    if source.endsWith(".md") then
      val rewritten = rewrite(readUtf8(from), source, repo, ctx)
      os.write.over(dest, rewritten.text)
      rewritten.errors
    else
      os.copy.over(from, dest)
      os.perms.set(dest, os.perms(from))
      Vector.empty

  /** Write out/index.md from the published ADRs' rewritten copies in out (their links are the
    * site's).
    */
  def adrIndex(src: os.Path, out: os.Path, excludeDocs: Seq[String]): Vector[String] =
    val reserved = Seq(Names.Index, Names.Readme)
    val handWritten = reserved.filter(name => os.exists(src / name)).map(AdrRow.handWritten)
    val pages = os.list(src).map(_.last).filter(_.endsWith(".md")).sorted.filterNot { page =>
      reserved.contains(page) || excluded(s"${Names.Adr}/$page", excludeDocs, isDir = false)
    }
    val read = pages.map { page =>
      AdrRow
        .number(page)
        .left
        .map(Vector(_))
        .flatMap(n => AdrRow.read(page, n, readUtf8(out / page)))
    }
    os.makeDir.all(out)
    os.write.over(out / Names.Index, adrIndexPage(read.collect { case Right(row) => row }))
    (handWritten ++ read.collect { case Left(errors) => errors }.flatten).toVector

  // ---- the command ----------------------------------------------------------------------------

  @main(doc =
    "Rewrite one repo's README and docs/** links for its mount on the docs site (MIP-0074)"
  )
  def run(
      repo: Option[String] = None,
      name: Option[String] = None,
      sha: Option[String] = None,
      out: Option[String] = None,
      umbrella: Flag,
      exclude: Seq[String] = Nil,
      @arg(doc = "NAME=SHA, repeated") submodule: Seq[String] = Nil,
      @arg(doc = "Print FILE<TAB>link for every relative link outside code") links: Seq[String] =
        Nil
  ): Int =
    (repo, name, sha, out) match
      case _ if links.nonEmpty                            => printLinks(links)
      case (Some(repo), Some(name), Some(sha), Some(out)) =>
        val submodules = submodule.map { s =>
          s.indexOf('=') match
            case -1 => s -> ""
            case i  => s.substring(0, i) -> s.substring(i + 1)
        }
        val ctx = Context(name, sha, umbrella.value, exclude, submodules.toMap)
        try
          val errors = mount(os.Path(repo, os.pwd), os.Path(out, os.pwd), ctx)
          errors.foreach(e => System.err.println(s"doc_links: $name: $e"))
          if errors.isEmpty then 0 else 1
        catch
          case e: java.io.IOException =>
            System.err.println(s"doc_links: $name: ${ioMessage(e)}")
            1
      case _ =>
        System.err.println(
          "doc_links: --repo, --name, --sha and --out are required (or --links FILE...)"
        )
        2

  /** Each file's relative links outside code; stops at the first file it cannot read. */
  private def printLinks(files: Seq[String]): Int =
    val unreadable = files.iterator
      .map { file =>
        try
          for link <- links(readUtf8(os.Path(file, os.pwd))) if firstMatch(Scheme, link).isEmpty do
            println(s"$file\t$link")
          None
        catch case e: java.io.IOException => Some(s"doc_links: $file: ${ioMessage(e)}")
      }
      .collectFirst { case Some(message) => message }
    unreadable.foreach(System.err.println)
    if unreadable.isEmpty then 0 else 1

  /** `--links A B C` as mainargs reads it, a repeated flag: `--links A --links B --links C`. */
  private def spreadLinks(args: Seq[String]): Seq[String] = args match
    case "--links" +: rest =>
      val (files, after) = rest.span(a => !a.startsWith("-") || a == "-")
      val spread = if files.isEmpty then Seq("--links") else files.flatMap(f => Seq("--links", f))
      spread ++ spreadLinks(after)
    case head +: rest => head +: spreadLinks(rest)
    case _            => Seq.empty

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "doc-links", spreadLinks(args))
