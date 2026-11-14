import java.nio.file.{AccessDeniedException, Files, InvalidPathException, NoSuchFileException}
import java.text.Normalizer
import java.util.Locale
import java.util.regex.{MatchResult, Pattern}
import mainargs.{arg, main, Flag, ParserForMethods}
import scala.collection.mutable
import Util.posix

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
  private val Fence = regex(raw"^\s*(`{3,}|~{3,})")
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
  private val AdrFile = regex(raw"^(\d{4})-[^/]+\.md\z")
  private val AdrStatus = regex(raw"(?m)^\|\s*\*\*Status\*\*\s*\|\s*(.*?)\s*\|\s*$$")
  private val Whitespace = regex(raw"\s")

  private val ImageSuffixes = Set(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".avif", ".ico")
  private val ImageOutsideDocs = "an image outside docs/; move it under docs/"

  /** The mounted repo: `repo` is checked out at `sha`. */
  case class Context(
      name: String,
      sha: String,
      umbrella: Boolean = false,
      // mkdocs.yml's patterns are relative to the site root; matched against the repo's docs/
      // they agree only while unanchored (benchmarks/), which is all mkdocs.yml has.
      excludeDocs: Seq[String] = Nil,
      // Umbrella only; each submodule must be checked out at its sha under repo/<name>, where the
      // existence checks read it.
      submoduleShas: Map[String, String] = Map.empty
  )

  // ---- regex helpers --------------------------------------------------------------------------

  extension (m: MatchResult)
    private def text(group: Int): String = Option(m.group(group)).getOrElse("")

  private def allMatches(pattern: Pattern, text: String): Vector[MatchResult] =
    val matcher = pattern.matcher(text)
    Iterator.continually(matcher).takeWhile(_.find()).map(_.toMatchResult).toVector

  private def firstMatch(pattern: Pattern, text: String): Option[MatchResult] =
    val matcher = pattern.matcher(text)
    Option.when(matcher.find())(matcher.toMatchResult)

  /** `re.sub(pattern, replace, text)`, the replacement taken literally. */
  private def sub(pattern: Pattern, text: String)(replace: MatchResult => String): String =
    val out = StringBuilder()
    var last = 0
    for m <- allMatches(pattern, text) do
      out ++= text.substring(last, m.start)
      out ++= replace(m)
      last = m.end
    out ++= text.substring(last)
    out.toString

  // ---- files ---------------------------------------------------------------------------------

  /** `base.join(rel)`. Trap: java.nio drops a trailing /, but on a file the OS refuses it. */
  private def onDisk(base: os.Path, rel: String): Option[java.nio.file.Path] =
    try
      val path = base.toNIO.resolve(rel)
      Option.when(!rel.endsWith("/") || Files.isDirectory(path))(path)
    catch case _: InvalidPathException => None

  private def existsAt(base: os.Path, rel: String) = onDisk(base, rel).exists(Files.exists(_))
  private def isDirAt(base: os.Path, rel: String) = onDisk(base, rel).exists(Files.isDirectory(_))
  private def isFileAt(base: os.Path, rel: String) =
    onDisk(base, rel).exists(Files.isRegularFile(_))

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

  enum Chunk:
    case Prose(text: String)
    case Code(text: String)

  def fenced(text: String): Vector[Chunk] =
    val out = Vector.newBuilder[Chunk]
    val prose = StringBuilder()
    var fence: Option[(Char, Int)] = None
    for line <- Util.splitlinesKeepends(text) do
      fence match
        case Some((char, length)) =>
          out += Chunk.Code(line)
          if closes(line, char, length) then fence = None
        case None =>
          firstMatch(Fence, line) match
            case Some(m) =>
              out += Chunk.Prose(prose.toString)
              prose.clear()
              fence = Some((m.group(1).head, m.group(1).length))
              out += Chunk.Code(line)
            case None => prose ++= line
    out += Chunk.Prose(prose.toString)
    out.result()

  private def isWhitespace(c: Char) = Whitespace.matcher(c.toString).matches()

  /** `^\s*{c}{n,}\s*$`: a bare fence line of at least the opening's length. */
  private def closes(line: String, char: Char, length: Int): Boolean =
    val rest = line.dropWhile(isWhitespace)
    val run = rest.takeWhile(_ == char).length
    run >= length && rest.drop(run).forall(isWhitespace)

  private def unique(slug: String, seen: mutable.Map[String, Int], separator: String): String =
    val n = seen.getOrElse(slug, 0)
    seen(slug) = n + 1
    if n > 0 then s"$slug$separator$n" else slug

  /** GitHub's anchor (and toc's own, for links already written for the site) -> toc's anchor. */
  def readmeAnchors(readme: String): Map[String, String] =
    val anchors = mutable.Map.empty[String, String]
    val githubSeen = mutable.Map.empty[String, Int]
    val tocSeen = mutable.Map.empty[String, Int]
    for
      case Chunk.Prose(text) <- fenced(readme)
      line <- Util.splitlines(text)
      heading <- firstMatch(Heading, line)
    do
      val linkless = HeadingLink.matcher(heading.text(1)).replaceAll("$1")
      val title = HeadingMarkup.matcher(linkless).replaceAll("")
      val github =
        GithubDrop.matcher(title.toLowerCase(Locale.ROOT)).replaceAll("").replace(' ', '-')
      // Python-Markdown toc's default slugify, which mkdocs uses (mkdocs.yml sets none).
      val ascii = Normalizer.normalize(title, Normalizer.Form.NFKD).filter(_ < 0x80)
      val dropped = TocDrop.matcher(ascii).replaceAll("")
      val slug = TocDash.matcher(dropped.trim.toLowerCase(Locale.ROOT)).replaceAll("-")
      val toc = unique(slug, tocSeen, "_")
      anchors(unique(github, githubSeen, "-")) = toc
      if !anchors.contains(toc) then anchors(toc) = toc
    anchors.toMap

  // ---- where a file lands ---------------------------------------------------------------------

  private def trimSlashes(s: String) = s.dropWhile(_ == '/').reverse.dropWhile(_ == '/').reverse

  /** A gitignore-style subset: `*` globs, a trailing / for directories, a / inside anchors. */
  def excluded(rel: String, patterns: Seq[String], isDir: Boolean): Boolean =
    val parts = rel.split("/", -1).toVector
    patterns.exists { written =>
      val anchored = written.reverse.dropWhile(_ == '/').reverse.contains('/')
      val dirOnly = written.endsWith("/")
      val pattern = trimSlashes(written)
      (1 to parts.length).exists { i =>
        val skip = pattern.isEmpty || (dirOnly && i == parts.length && !isDir)
        val candidate = if anchored then parts.take(i).mkString("/") else parts(i - 1)
        !skip && Util.fnmatchcase(candidate, pattern)
      }
    }

  /** Where docs/<rel> lands, relative to the mount. */
  private def docsMount(rel: String, ctx: Context): String =
    if ctx.umbrella && (rel == "MIPs" || rel.startsWith("MIPs/")) then s"6-$rel" else rel

  private def outPath(source: String, ctx: Context): String =
    if source == "README.md" then "index.md" else docsMount(source.stripPrefix("docs/"), ctx)

  // ---- the rewrite ----------------------------------------------------------------------------

  private class Rewriter(source: String, repo: os.Path, ctx: Context):
    val errors = mutable.ArrayBuffer.empty[String]
    private val ownGithub = regex(
      raw"^https://github\.com/marola-dev/" + Pattern.quote(ctx.name) +
        raw"/(?:blob|tree)/([^?#]+)(.*)\z"
    )

    private lazy val anchors: Map[String, String] =
      val readme = repo / "README.md"
      try if os.isFile(readme) then readmeAnchors(readUtf8(readme)) else Map.empty
      catch case _: java.io.IOException => Map.empty

    private def fail(link: String, reason: String): String =
      errors += s"$source: $link: $reason"
      link

    /** The site's anchor for a README heading; a missing one is an error and kept as written. */
    private def readmeAnchor(link: String, anchor: String): String =
      anchors.get(anchor) match
        case Some(toc) => toc
        case None      =>
          errors += s"$source: $link: no README heading has the anchor #$anchor"
          anchor

    private def github(
        link: String,
        name: String,
        sha: String,
        checkout: os.Path,
        path: String,
        suffix: String
    ): String =
      onDisk(checkout, Util.unquote(path)).filter(Files.exists(_)) match
        case None       => fail(link, s"missing at $sha")
        case Some(full) =>
          val kind = if Files.isDirectory(full) then "tree" else "blob"
          s"https://github.com/marola-dev/$name/$kind/$sha/$path$suffix"

    def target(link: String, image: Boolean): String =
      if link.isEmpty then ""
      else if firstMatch(Scheme, link).isDefined then pinOwnGithub(link)
      else relative(link, image)

    /** A link to this repo on a branch is pinned to ctx.sha; any other absolute link is kept. */
    private def pinOwnGithub(link: String): String = firstMatch(ownGithub, link) match
      case None    => link
      case Some(m) =>
        val parts = m.group(1).split("/", -1).toVector
        if firstMatch(PinnedRef, parts.head).isDefined then link
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
              link.substring(0, m.start(1)) + ctx.sha + link.substring(m.start(1) + ref.length)
            case None => fail(link, s"missing at ${ctx.sha}")

    private def relative(link: String, image: Boolean): String =
      val (beforeHash, hash, anchor) = link.indexOf('#') match
        case -1 => (link, "", "")
        case i  => (link.substring(0, i), "#", link.substring(i + 1))
      val (path, query) = beforeHash.indexOf('?') match
        case -1 => (beforeHash, "")
        case i  => (beforeHash.substring(0, i), beforeHash.substring(i))
      if path.startsWith("/") then fail(link, "root-absolute; link the page relatively")
      else if path.isEmpty then
        val siteAnchor =
          if hash.nonEmpty && source == "README.md" then readmeAnchor(link, anchor) else anchor
        s"$query$hash$siteAnchor"
      else
        val resolved = posix.normpath(posix.join(posix.dirname(source), path))
        if resolved == ".." || resolved.startsWith("../") then fail(link, "leaves the repo")
        else resolvedTarget(link, resolved, path.endsWith("/"), image, query, hash, anchor)

    private def resolvedTarget(
        link: String,
        resolved: String,
        endsWithSlash: Boolean,
        image: Boolean,
        query: String,
        hash: String,
        anchor: String
    ): String =
      val isDir = endsWithSlash || isDirAt(repo, Util.unquote(resolved))
      val isImage =
        image || ImageSuffixes.contains(posix.extension(resolved).toLowerCase(Locale.ROOT))
      val suffix = s"$query$hash$anchor"
      val (head, rest) = resolved.indexOf('/') match
        case -1 => (resolved, "")
        case i  => (resolved.substring(0, i), resolved.substring(i + 1))

      def onSite(mount: String, siteAnchor: String = anchor): String =
        val start = posix.dirname(outPath(source, ctx)) match
          case ""  => "."
          case dir => dir
        s"${posix.relpath(mount, start)}$query$hash$siteAnchor"

      def onGithub(): String = github(link, ctx.name, ctx.sha, repo, resolved, suffix)

      if ctx.umbrella && ctx.submoduleShas.contains(head) then
        val published =
          rest == "" || rest == "README.md" || rest == "docs" || rest.startsWith("docs/")
        if !published then
          if isImage then fail(link, ImageOutsideDocs)
          else github(link, head, ctx.submoduleShas(head), repo / head, rest, suffix)
        else
          val rel = (if rest == "README.md" then "" else rest.stripPrefix("docs")).stripPrefix("/")
          val page = if rel.isEmpty then "index.md" else if isDir then s"$rel/index.md" else rel
          onSite(s"5-Repos/$head/$page")
      else if resolved != "docs" && !resolved.startsWith("docs/") then
        if isImage then fail(link, ImageOutsideDocs)
        else if resolved != "README.md" then onGithub()
        else onSite("index.md", if hash.nonEmpty then readmeAnchor(link, anchor) else anchor)
      else
        val rel = resolved.stripPrefix("docs").stripPrefix("/")
        if rel.nonEmpty && excluded(rel, ctx.excludeDocs, isDir) then
          if isImage then
            fail(link, "an image under exclude_docs; move it to a published docs/ path")
          else onGithub()
        else if rel == "index.md" then
          fail(link, "docs/index.md is banned (MIP-0074 D1); link docs/ for the landing")
        else if rel.isEmpty then onSite("index.md")
        else if isDir then
          // adr/index.md is generated by prepare-docs, so it never exists in the repo.
          if rel != "adr" && !isFileAt(repo, s"$resolved/index.md") then
            fail(link, "a directory with no index.md")
          else onSite(s"${docsMount(rel, ctx)}/index.md")
        else onSite(docsMount(rel, ctx))
    end resolvedTarget

    private def bracketed(link: String, image: Boolean): String =
      if link.length >= 2 && link.startsWith("<") && link.endsWith(">") then
        s"<${target(link.substring(1, link.length - 1), image)}>"
      else target(link, image)

    private def inlineLinks(text: String): String =
      sub(Inline, text) { m =>
        val bang = m.text(1)
        val label = inlineLinks(m.text(2))
        s"$bang[$label](${bracketed(m.text(3), bang.nonEmpty)}${m.text(4)})"
      }

    def prose(chunk: String): String =
      // Masked, not split out: a code span can be a link's label, as in [`AGENTS.md`](…).
      val spans = mutable.ArrayBuffer.empty[String]
      val masked = sub(CodeSpan, chunk) { m =>
        spans += m.group
        s"\u0000${spans.length - 1}\u0000"
      }
      val refsDone = sub(RefDef, masked)(m => m.text(1) + bracketed(m.text(2), image = false))
      val linksDone = inlineLinks(refsDone)
      val htmlDone = sub(HtmlTag, linksDone) { tag =>
        sub(HtmlAttr, tag.group) { a =>
          val (name, equals, quote) = (a.text(1), a.text(2), a.text(3))
          s"$name$equals$quote${target(a.text(4), name.equalsIgnoreCase("src"))}$quote"
        }
      }
      sub(Mask, htmlDone)(m => spans(m.group(1).toInt))
  end Rewriter

  /** Rewrite `text`, the repo-relative file `source` (README.md or a page under docs/), for the
    * mount.
    *
    * `repo` is the checkout at `ctx.sha`. Returns the new text and one error per link Appendix A
    * refuses; a refused link is left as written.
    */
  def rewrite(text: String, source: String, repo: os.Path, ctx: Context): (String, Vector[String]) =
    val rewriter = Rewriter(source, repo, ctx)
    val out = fenced(text).map {
      case Chunk.Code(code)   => code
      case Chunk.Prose(prose) => rewriter.prose(prose)
    }
    (out.mkString, rewriter.errors.toVector)

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
    val docsDir = repo / "docs"
    val docs =
      if !os.isDir(docsDir) then Vector.empty
      else os.walk(docsDir).filter(os.isFile(_)).map(_.relativeTo(repo).toString).sorted.toVector
    val published =
      docs.filterNot(d => excluded(d.stripPrefix("docs/"), ctx.excludeDocs, isDir = false))
    val errors = Vector.newBuilder[String]
    for source <- "README.md" +: published do
      val from = repo / os.RelPath(source)
      val dest = out / os.RelPath(outPath(source, ctx))
      os.makeDir.all(dest / os.up)
      if source.endsWith(".md") then
        val (text, refused) = rewrite(readUtf8(from), source, repo, ctx)
        os.write.over(dest, text)
        errors ++= refused
      else
        os.copy.over(from, dest)
        os.perms.set(dest, os.perms(from))
    if os.isDir(docsDir / "adr") then
      errors ++= adrIndex(docsDir / "adr", out / os.RelPath(docsMount("adr", ctx)), ctx.excludeDocs)
    errors.result()

  /** Write out/index.md from the published ADRs' rewritten copies in out (their links are the
    * site's).
    */
  def adrIndex(src: os.Path, out: os.Path, excludeDocs: Seq[String]): Vector[String] =
    val errors = Vector.newBuilder[String]
    for name <- Seq("index.md", "README.md") if os.exists(src / name) do
      errors += s"docs/adr/$name: hand-written; prepare-docs generates adr/index.md"
    val pages = os.list(src).map(_.last).filter(_.endsWith(".md")).sorted.filterNot { page =>
      page == "index.md" || page == "README.md" || excluded(
        s"adr/$page",
        excludeDocs,
        isDir = false
      )
    }
    val rows = Vector.newBuilder[String]
    for page <- pages do
      firstMatch(AdrFile, page) match
        case None    => errors += s"docs/adr/$page: not NNNN-<slug>.md"
        case Some(m) =>
          val n = m.group(1)
          val text = readUtf8(out / page)
          val titleLine = regex(raw"(?m)^# ADR-" + n + raw":[ \t]*(\S.*?)[ \t]*$$")
          val title = firstMatch(titleLine, text).map(_.group(1))
          val status = firstMatch(AdrStatus, text).map(_.group(1))
          if title.isEmpty then errors += s"docs/adr/$page: no \"# ADR-$n: <decision>\" heading"
          if status.isEmpty then errors += s"docs/adr/$page: no | **Status** | row"
          for t <- title; s <- status do rows += s"| [$n]($page) | ${t.replace("|", "\\|")} | $s |"
    val table = rows.result()
    val body =
      if table.isEmpty then Vector("_None yet._")
      else Vector("| ADR | Decision | Status |", "|---|---|---|") ++ table
    os.makeDir.all(out)
    os.write.over(
      out / "index.md",
      (Vector("# Architecture decisions", "") ++ body).mkString("\n") + "\n"
    )
    errors.result()

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
