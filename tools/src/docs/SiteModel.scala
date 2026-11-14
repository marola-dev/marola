package marola.tools.docs

import marola.tools.shared.Util

import java.util.Locale
import java.util.regex.{Matcher, Pattern}
import Util.posix

// The docs site build's domain (MIP-0074): what a repo's mount is, how a link on it resolves,
// what the aggregated build records, and the verdicts its checks reach. No IO: the commands read
// the disk, git, containers and the environment, and hand the facts here.
object SiteModel:

  // Unicode \w \s \d and case folding, and only \n ends a line, as DocLinks reads Markdown.
  private def regex(pattern: String): Pattern =
    Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES)

  // ---- names ----------------------------------------------------------------------------------

  /** The files and directories the site build reads and writes. */
  object Names:
    val Readme = "README.md"
    val Index = "index.md"
    val Docs = "docs"
    val DocsIndex = s"$Docs/$Index"
    val Adr = "adr"
    val Mips = "MIPs"

    /** The umbrella's MIPs mount after 5-Repos/, so the site's sidebar orders them last. */
    val MipsMountPrefix = "6-"
    val ReposMount = "5-Repos"
    val ApiDocs = "api-docs"
    val Mkdocs = "mkdocs"
    val MkdocsYml = "mkdocs.yml"
    val ReposYml = "repos.yml"
    val Overrides = "overrides"
    val NotFoundPage = "404.html"
    val GeneratedDocs = "generated-docs"
    val IndexHtml = "index.html"
    val BuildJson = "build.json"
    val LlmsTxt = "llms.txt"
    val FlakeLock = "flake.lock"
    val Tmp = ".tmp"
    val DocsSources = "docs-sources"
    val DocsAggregated = "docs-aggregated"

  /** The environment variables the commands read, each once, at their edge. */
  object EnvVar:
    val DocsSourceRemote = "DOCS_SOURCE_REMOTE"
    val ApiDocsRemote = "API_DOCS_REMOTE"
    val ContainerRuntime = "CONTAINER_RUNTIME"
    val DocsSrc = "DOCS_SRC"
    val MkdocsImage = "MKDOCS_IMAGE"
    val PodmanComposeWarningLogs = "PODMAN_COMPOSE_WARNING_LOGS"
    val GitTerminalPrompt = "GIT_TERMINAL_PROMPT"

    /** A hook's GIT_DIR would point every git call at the caller's repo. */
    val GitRepoPointers =
      Seq("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_PREFIX", "GIT_COMMON_DIR")

  val Org = "marola-dev"
  val GitHub = s"https://github.com/$Org"
  val UmbrellaRepo = "marola"

  // ---- configuration --------------------------------------------------------------------------

  /** A clone URL with `<repo>` in place of the repo's name. */
  opaque type RemoteTemplate = String

  object RemoteTemplate:
    val Placeholder = "<repo>"
    val MarolaDev: RemoteTemplate = s"$GitHub/$Placeholder"

    /** The template a variable sets, or `default` when it is unset or empty. */
    def from(written: Option[String], default: RemoteTemplate): RemoteTemplate =
      written.filter(_.nonEmpty).getOrElse(default)

  extension (template: RemoteTemplate)
    def url(repo: String): String = template.replace(RemoteTemplate.Placeholder, repo)

  /** `env` for every git call: no caller's repo, and a missing or private repo must not stop a
    * local build at a credential prompt.
    */
  def isolatedGitEnv(env: Map[String, String]): Map[String, String] =
    env -- EnvVar.GitRepoPointers + (EnvVar.GitTerminalPrompt -> "0")

  /** Where fetch-api-docs fetches from, and the whole environment of its git calls. */
  case class ApiDocsConfig(remote: RemoteTemplate, gitEnv: Map[String, String])

  object ApiDocsConfig:
    def from(env: Map[String, String]): ApiDocsConfig =
      ApiDocsConfig(
        RemoteTemplate.from(env.get(EnvVar.ApiDocsRemote), RemoteTemplate.MarolaDev),
        isolatedGitEnv(env)
      )

  /** prepare-docs' git environment, where a flake-lock source is fetched from, and api-docs. */
  case class PrepareConfig(
      gitEnv: Map[String, String],
      sourceRemote: RemoteTemplate,
      apiDocs: ApiDocsConfig
  )

  object PrepareConfig:
    def from(env: Map[String, String]): PrepareConfig =
      val gitEnv = isolatedGitEnv(env)
      PrepareConfig(
        gitEnv,
        RemoteTemplate.from(gitEnv.get(EnvVar.DocsSourceRemote), RemoteTemplate.MarolaDev),
        ApiDocsConfig.from(gitEnv)
      )

  /** The container runtimes mkdocs tries in order, its docs tree relative to the root, and the
    * whole environment of the tools it runs.
    */
  case class MkdocsConfig(
      runtimes: Vector[String],
      docsSrc: Option[String],
      env: Map[String, String]
  )

  object MkdocsConfig:
    val DefaultRuntimes = "podman docker"

    def from(env: Map[String, String]): MkdocsConfig =
      val runtimes = env.get(EnvVar.ContainerRuntime).filter(_.nonEmpty).getOrElse(DefaultRuntimes)
      MkdocsConfig(
        runtimes.split("""\s+""").filter(_.nonEmpty).toVector,
        env.get(EnvVar.DocsSrc).filter(_.nonEmpty),
        env
      )

  // ---- commits --------------------------------------------------------------------------------

  /** A full git object name, as `git rev-parse HEAD` prints it (SHA-1, or SHA-256). */
  opaque type CommitSha = String

  object CommitSha:
    private val Hex = "[0-9a-f]{40}|[0-9a-f]{64}".r

    def parse(written: String): Either[String, CommitSha] =
      Either.cond(Hex.matches(written), written, s"not a full commit sha: $written")

  extension (sha: CommitSha)
    def hex: String = sha
    def short: String = sha.take(7)

  // ---- mkdocs/repos.yml and the checkouts -----------------------------------------------------

  /** Where a mounted repo's tree comes from (MIP-0070 §5.5). */
  enum RepoSource:
    case Submodule, FlakeLock

  object RepoSource:
    /** How repos.yml writes FlakeLock; a submodule writes no source. */
    val FlakeLockWord = "flake-lock"

  case class Repo(name: String, source: RepoSource)

  case class Checkout(repo: Repo, dir: os.Path, sha: CommitSha)

  /** Why a step of the build stopped. */
  enum Failure:
    /** A tool it ran already said why on stderr. */
    case Reported
    case Said(text: String)

    def message: String = this match
      case Reported   => ""
      case Said(text) => text

  /** Why a repo cannot be mounted: its README is the landing (MIP-0074 §5.2, D1). */
  enum Landing:
    case NoReadme, DocsIndexToo

    def problem(repo: String): String = this match
      case NoReadme =>
        s"prepare-docs: $repo has no ${Names.Readme} — it is the repo's landing page (MIP-0074 §5.2)"
      case DocsIndexToo =>
        s"prepare-docs: $repo has both ${Names.Readme} and ${Names.DocsIndex} — the README is the landing; remove ${Names.DocsIndex} (MIP-0074 D1)"

  object Landing:
    def check(hasReadme: Boolean, hasDocsIndex: Boolean): Option[Landing] =
      if !hasReadme then Some(NoReadme) else Option.when(hasDocsIndex)(DocsIndexToo)

  /** The line a mounted landing page ends with. */
  def builtFrom(repo: String, sha: CommitSha): String =
    s"\n_Built from [$Org/$repo@${sha.short}]($GitHub/$repo/tree/$sha)._\n"

  /** build.json: the commit of the umbrella and of every mounted repo. */
  def buildJson(umbrella: CommitSha, checkouts: Seq[Checkout]): String =
    val shas = ujson.Obj.from((UmbrellaRepo -> umbrella.hex) +: checkouts.map { c =>
      c.repo.name -> ujson.Str(c.sha.hex)
    })
    Util.dumps(shas, indent = Some(2)) + "\n"

  /** A `FILE<TAB>link` line of `doc-links --links`. */
  case class ListedLink(file: String, link: String):
    /** The path part: what must exist for the link to resolve. */
    def target: String = link.takeWhile(_ != '#').takeWhile(_ != '?')

    /** Anchors, root-absolute links and query-only targets are not files to look for. */
    def checked: Boolean =
      !(link.isEmpty || link.startsWith("/") || link.startsWith("#") || target.isEmpty)

    /** The target as a path from where `file` is. */
    def candidate: String =
      val cut = file.lastIndexOf('/')
      val dir = if cut < 0 then "." else if cut == 0 then "/" else file.take(cut)
      s"$dir/$target"

    def broken(buildDir: String): String =
      s"prepare-docs: ${file.stripPrefix(s"$buildDir/")}: broken relative link: $link"

  object ListedLink:
    def parse(line: String): ListedLink =
      val (file, rest) = line.span(_ != '\t')
      ListedLink(file, rest.dropWhile(_ == '\t').reverse.dropWhile(_ == '\t').reverse)

  // ---- a repo's mount (doc-links) -------------------------------------------------------------

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
  ):
    /** Where docs/<rel> lands, relative to the mount. */
    def docsMount(rel: String): String =
      if umbrella && (rel == Names.Mips || rel.startsWith(s"${Names.Mips}/")) then
        Names.MipsMountPrefix + rel
      else rel

    /** Where the repo-relative file `source` lands, relative to the mount. */
    def outPath(source: String): String =
      if source == Names.Readme then Names.Index
      else docsMount(source.stripPrefix(s"${Names.Docs}/"))

    /** `mount` as a link from the page `source` becomes. */
    def siteHref(source: String, mount: String): String =
      val start = posix.dirname(outPath(source)) match
        case ""  => "."
        case dir => dir
      posix.relpath(mount, start)

  enum Chunk:
    case Prose(text: String)
    case Code(text: String)

  private val Whitespace = regex(raw"\s")
  private def isWhitespace(c: Char) = Whitespace.matcher(c.toString).matches()

  /** An open code fence: its character and how long its opening run was. */
  case class Fence(char: Char, length: Int):
    /** `^\s*{c}{n,}\s*$`: a bare fence line of at least the opening's length. */
    def closedBy(line: String): Boolean =
      val rest = line.dropWhile(isWhitespace)
      val run = rest.takeWhile(_ == char).length
      run >= length && rest.drop(run).forall(isWhitespace)

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

  /** The result of rewriting one page: its new text and one error per link Appendix A refuses. */
  case class Rewrite(text: String, errors: Vector[String]):
    def map(f: String => String): Rewrite = copy(text = f(text))

    /** The next rewrite of this text, its errors after these. */
    def flatMap(f: String => Rewrite): Rewrite =
      val next = f(text)
      Rewrite(next.text, errors ++ next.errors)

  object Rewrite:
    def clean(text: String): Rewrite = Rewrite(text, Vector.empty)

    /** The pieces' texts joined, their errors in order. */
    def concat(pieces: Seq[Rewrite]): Rewrite =
      Rewrite(pieces.map(_.text).mkString, pieces.flatMap(_.errors).toVector)

  /** A relative link as written, split at its first `#` and then its first `?`. */
  case class Link(path: String, query: String, hasAnchor: Boolean, anchor: String):
    def hash: String = if hasAnchor then "#" else ""

    /** The query and fragment, with `anchor` for the written one. */
    def after(anchor: String): String = s"$query$hash$anchor"

    def suffix: String = after(anchor)

  object Link:
    def parse(written: String): Link =
      val (beforeHash, hasAnchor, anchor) = written.indexOf('#') match
        case -1 => (written, false, "")
        case i  => (written.substring(0, i), true, written.substring(i + 1))
      val (path, query) = beforeHash.indexOf('?') match
        case -1 => (beforeHash, "")
        case i  => (beforeHash.substring(0, i), beforeHash.substring(i))
      Link(path, query, hasAnchor, anchor)

  /** Why a link is an error (MIP-0074 Appendix A); the line reads `<source>: <link>: <reason>`. */
  enum LinkError:
    case RootAbsolute, LeavesRepo, ImageOutsideDocs, ImageExcluded, DocsIndexBanned
    case DirectoryWithoutIndex
    case MissingAt(sha: String)
    case NoReadmeAnchor(anchor: String)

    def reason: String = this match
      case RootAbsolute     => "root-absolute; link the page relatively"
      case LeavesRepo       => "leaves the repo"
      case ImageOutsideDocs => "an image outside docs/; move it under docs/"
      case ImageExcluded    => "an image under exclude_docs; move it to a published docs/ path"
      case DocsIndexBanned  =>
        s"${Names.DocsIndex} is banned (MIP-0074 D1); link docs/ for the landing"
      case DirectoryWithoutIndex => s"a directory with no ${Names.Index}"
      case MissingAt(sha)        => s"missing at $sha"
      case NoReadmeAnchor(a)     => s"no README heading has the anchor #$a"

    def line(source: String, link: String): String = s"$source: $link: $reason"

  /** What the mount's checks need of the checkout, paths relative to its root. */
  case class RepoTree(isDir: String => Boolean, isFile: String => Boolean)

  /** Where a relative link points once its repo is mounted. */
  enum LinkTarget:
    /** The query and fragment alone; `readmeAnchor` when the fragment names a README heading. */
    case Fragment(readmeAnchor: Boolean)

    /** A page of the site, `mount` from the site root. */
    case Site(mount: String, readmeAnchor: Boolean = false)

    /** A file the site does not publish, pinned on GitHub; `submodule` is the umbrella's checkout
      * of `repo`, None for the mounted repo itself.
      */
    case GitHub(repo: String, sha: String, submodule: Option[String], path: String)

    case Refused(error: LinkError)

  object LinkTarget:
    private val ImageSuffixes =
      Set(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".avif", ".ico")

    /** `link`, written in the repo-relative page `source`, under the rules of Appendix A. */
    def of(link: Link, source: String, image: Boolean, ctx: Context, tree: RepoTree): LinkTarget =
      if link.path.startsWith("/") then Refused(LinkError.RootAbsolute)
      else if link.path.isEmpty then Fragment(link.hasAnchor && source == Names.Readme)
      else
        val resolved = posix.normpath(posix.join(posix.dirname(source), link.path))
        if resolved == ".." || resolved.startsWith("../") then Refused(LinkError.LeavesRepo)
        else inRepo(resolved, link, image, ctx, tree)

    private def underDocs(path: String) =
      path == Names.Docs || path.startsWith(s"${Names.Docs}/")

    private def inRepo(
        resolved: String,
        link: Link,
        image: Boolean,
        ctx: Context,
        tree: RepoTree
    ): LinkTarget =
      val isDir = link.path.endsWith("/") || tree.isDir(Util.unquote(resolved))
      val isImage =
        image || ImageSuffixes.contains(posix.extension(resolved).toLowerCase(Locale.ROOT))
      val (head, rest) = resolved.indexOf('/') match
        case -1 => (resolved, "")
        case i  => (resolved.substring(0, i), resolved.substring(i + 1))
      def own = GitHub(ctx.name, ctx.sha, None, resolved)

      if ctx.umbrella && ctx.submoduleShas.contains(head) then
        val published = rest == "" || rest == Names.Readme || underDocs(rest)
        if !published then
          if isImage then Refused(LinkError.ImageOutsideDocs)
          else GitHub(head, ctx.submoduleShas(head), Some(head), rest)
        else
          val rel =
            (if rest == Names.Readme then "" else rest.stripPrefix(Names.Docs)).stripPrefix("/")
          val page =
            if rel.isEmpty then Names.Index else if isDir then s"$rel/${Names.Index}" else rel
          Site(s"${Names.ReposMount}/$head/$page")
      else if !underDocs(resolved) then
        if isImage then Refused(LinkError.ImageOutsideDocs)
        else if resolved != Names.Readme then own
        else Site(Names.Index, readmeAnchor = link.hasAnchor)
      else
        val rel = resolved.stripPrefix(Names.Docs).stripPrefix("/")
        if rel.nonEmpty && excluded(rel, ctx.excludeDocs, isDir) then
          if isImage then Refused(LinkError.ImageExcluded) else own
        else if rel == Names.Index then Refused(LinkError.DocsIndexBanned)
        else if rel.isEmpty then Site(Names.Index)
        else if isDir then
          // adr/index.md is generated by prepare-docs, so it never exists in the repo.
          if rel != Names.Adr && !tree.isFile(s"$resolved/${Names.Index}") then
            Refused(LinkError.DirectoryWithoutIndex)
          else Site(s"${ctx.docsMount(rel)}/${Names.Index}")
        else Site(ctx.docsMount(rel))
    end inRepo

  // ---- the generated ADR index ----------------------------------------------------------------

  /** One published ADR, a row of adr/index.md. */
  case class AdrRow(number: String, page: String, title: String, status: String):
    def line: String = s"| [$number]($page) | ${title.replace("|", "\\|")} | $status |"

  object AdrRow:
    private val File = regex(raw"^(\d{4})-[^/]+\.md\z")
    private val Status = regex(raw"(?m)^\|\s*\*\*Status\*\*\s*\|\s*(.*?)\s*\|\s*$$")

    private def first(pattern: Pattern, text: String): Option[String] =
      val matcher = pattern.matcher(text)
      Option.when(matcher.find())(matcher.group(1))

    /** The ADR number a page's name carries, or the error for a name that is not NNNN-<slug>.md. */
    def number(page: String): Either[String, String] =
      first(File, page).toRight(s"docs/adr/$page: not NNNN-<slug>.md")

    /** The row of the page `page` numbered `number`, from its text, or what it lacks. */
    def read(page: String, number: String, text: String): Either[Vector[String], AdrRow] =
      val titleLine = regex(raw"(?m)^# ADR-" + number + raw":[ \t]*(\S.*?)[ \t]*$$")
      val title = first(titleLine, text)
      val status = first(Status, text)
      val lacking =
        Option.when(title.isEmpty)(s"docs/adr/$page: no \"# ADR-$number: <decision>\" heading") ++
          Option.when(status.isEmpty)(s"docs/adr/$page: no | **Status** | row")
      title.zip(status).map((t, s) => AdrRow(number, page, t, s)).toRight(lacking.toVector)

    def handWritten(name: String): String =
      s"docs/adr/$name: hand-written; prepare-docs generates adr/${Names.Index}"

  /** adr/index.md's text. */
  def adrIndexPage(rows: Seq[AdrRow]): String =
    val body =
      if rows.isEmpty then Vector("_None yet._")
      else Vector("| ADR | Decision | Status |", "|---|---|---|") ++ rows.map(_.line)
    (Vector("# Architecture decisions", "") ++ body).mkString("\n") + "\n"

  // ---- the mkdocs container build -------------------------------------------------------------

  enum Mode:
    case Build, Serve
    def word: String = toString.toLowerCase
    def composeFiles: Seq[String] =
      Seq("-f", "docker-compose.yml", "-f", s"docker-compose.$word.yml")

  /** The compose service that runs mkdocs, beside Kroki. */
  val MkdocsService = "mkdocs"

  /** Where the container writes the built site. */
  val ContainerSite = s"/mkdocs/${Names.GeneratedDocs}/"

  /** A failed run: its exit code and what to say on stderr (nothing when a tool already did). */
  case class Stop(code: Int, message: String = "")

  object Stop:
    /** The mkdocs container's exit as `inspect` printed it; None when inspect failed. */
    def fromContainer(exit: Option[String], mode: Mode): Either[Stop, Unit] =
      val code = exit.filter(_.nonEmpty).getOrElse("1")
      Either.cond(
        code == "0",
        (),
        Stop(
          code.toIntOption.map(_ & 0xff).getOrElse(2),
          s"mkdocs: ${mode.word} failed (exit $code) — see the log above"
        )
      )

  /** POSIX `cksum`'s CRC of `text`'s bytes. */
  def cksum(text: String): Long =
    def bit(c: Long): Long =
      (if (c & 0x80000000L) != 0 then (c << 1) ^ 0x04c11db7L else c << 1) & 0xffffffffL
    def step(crc: Long, byte: Int): Long =
      (0 until 8).foldLeft(crc ^ (byte.toLong << 24))((c, _) => bit(c))
    val bytes = text.getBytes("UTF-8")
    val lengthBytes =
      Iterator.iterate(bytes.length.toLong)(_ >> 8).takeWhile(_ > 0).map(n => (n & 0xff).toInt)
    val crc = (bytes.iterator.map(_ & 0xff) ++ lengthBytes).foldLeft(0L)(step)
    ~crc & 0xffffffffL

  // Compose names its project after its directory, `mkdocs` in every checkout; two builds on one
  // host were then one stack and each one's `down` deleted the other's containers mid-run.
  def projectName(root: String): String = s"marola-mkdocs-${cksum(root)}"

  // The docs are baked into the image, so a shared tag has the same collision (#526).
  def imageTag(root: String): String = s"marola/mkdocs:${cksum(root)}"

  /** mkdocs serve mounts the site under site_url's path, so the banner reads it back. */
  def servePath(mkdocsYml: String): String =
    val url = mkdocsYml
      .split("\n", -1)
      .filter(_.startsWith("site_url:"))
      .map(_.replaceFirst("""^site_url:\s*""", ""))
      .mkString("\n")
      .reverse
      .dropWhile(_ == '\n')
      .reverse
    val scheme = url.indexOf("://")
    val slash = if scheme < 0 then -1 else url.indexOf('/', scheme + 3)
    if slash < 0 then "/" else "/" + url.drop(slash + 1)

  // ---- the api-docs branch --------------------------------------------------------------------

  /** What fetching a repo's api-docs branch came to. */
  enum ApiDocsFetch:
    case NoBranch
    case Unpacked(dest: os.Path, subject: String)
    case Failed(code: Int)

    def exitCode: Int = this match
      case Failed(code) => code
      case _            => 0

    /** The one line on stdout; a failed unpack leaves the tool's own message. */
    def notice(repo: String): Option[String] = this match
      case NoBranch =>
        Some(s"fetch-api-docs: $repo has no ${Names.ApiDocs} branch yet — skipping")
      case Unpacked(dest, subject) => Some(s"fetch-api-docs: $repo -> $dest ($subject)")
      case Failed(_)               => None

  // ---- the built site's links -----------------------------------------------------------------

  val Site = "https://docs.marola.dev"

  /** A URL prefix overrides/404.html forwards (MIP-0074 Appendix B). */
  case class Move(from: String, to: String):
    /** Where `path` is forwarded to, when it starts with `from`. */
    def forward(path: String): Option[String] =
      Option.when(path.startsWith(from))(to + path.substring(from.length))

  object Move:
    /** Longest `from` first: the tie-break 404.html's own script uses, so a path under two prefixes
      * matches the narrower one.
      */
    def longestFirst(moves: Seq[Move]): Vector[Move] =
      moves.toVector.sortBy(m => -m.from.codePointCount(0, m.from.length))

  /** How many links a check looked at, and the ones that failed it. */
  case class LinkCheck(checked: Int, failed: Vector[String])

  /** The previous live sitemap, `file` as the caller named it, and its check. */
  case class OldSitemap(file: String, check: LinkCheck):
    // The count is the signal that the sitemap was real: zero must never read as "nothing dropped".
    def empty: Boolean = check.checked == 0

  /** site-links-check's verdict. */
  case class SiteLinksVerdict(hrefs: LinkCheck, sitemap: Option[OldSitemap]):
    private def dropped = sitemap.map(_.check.failed).getOrElse(Vector.empty)
    private def emptySitemap = sitemap.filter(_.empty)

    def ok: Boolean = hrefs.failed.isEmpty && dropped.isEmpty && emptySitemap.isEmpty

    /** The lines for stderr, the failure count last when it failed. */
    def errors: Vector[String] =
      hrefs.failed.map(b => s"broken href: $b") ++
        dropped.map(d => s"dropped url: $d") ++
        emptySitemap.map { old =>
          s"site_links_check: ${old.file} has no <loc> at all — an empty fetch, an error page, or a sitemap format this check doesn't parse; not a clean sitemap"
        } ++
        Option.unless(ok)(
          s"site_links_check: ${hrefs.failed.length} broken href(s), ${dropped.length} dropped url(s)"
        )

    def summary: String = sitemap match
      case Some(old) =>
        s"site_links_check: ok (${hrefs.checked} href(s), ${old.check.checked} old url(s) checked)"
      case None => s"site_links_check: ok (${hrefs.checked} href(s) checked)"

  // ---- generated API docs under the site's CSP ------------------------------------------------

  /** A page with its third-party scripts gone, and how many went. */
  case class Stripped(html: String, removed: Int)

  /** Files stripped and scripts removed, summed over the directories. */
  case class StripTally(files: Int, removed: Int):
    def +(other: StripTally): StripTally = StripTally(files + other.files, removed + other.removed)
    def summary: String = s"stripped $removed external <script> tags across $files html files"

  object StripTally:
    val Zero: StripTally = StripTally(0, 0)

  /** A scaladoc page's scripts under `script-src 'self'` (MIP-0044 §5.6). */
  object ApiDocsPage:
    // Unicode \s and case folding.
    private def regex(pattern: String): Pattern =
      Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS)

    val ExternalScript: Pattern = regex(
      raw"""(?i)<script[^>]*\ssrc=["']https?://[^"']*["'][^>]*>\s*</script>"""
    )
    private val PathToRoot =
      regex(raw"""(?i)<script[^>]*>\s*var\s+pathToRoot\s*=\s*["']([^"']*)["']\s*;?\s*</script>""")
    private val Body = regex("<body([^>]*)>")

    val ShimDir = "scripts"
    val ShimFile = "path-to-root.js"

    val ShimJs: String =
      """// Restores the `pathToRoot` global scaladoc's own scripts expect, without an inline
        |// <script> — read from <body data-path-to-root>, so the page keeps script-src 'self'. MIP-0044.
        |var pathToRoot = document.body.getAttribute("data-path-to-root") || "";
        |""".stripMargin

    def hasExternalScript(html: String): Boolean = ExternalScript.matcher(html).find()

    private def replaceFirst(pattern: Pattern, text: String, replacement: String): String =
      pattern.matcher(text).replaceFirst(Matcher.quoteReplacement(replacement))

    private def insertBefore(text: String, marker: String, insert: String): String =
      text.indexOf(marker) match
        case -1 => text
        case i  => text.substring(0, i) + insert + text.substring(i)

    /** The inline pathToRoot moved to the body's data attribute, with the shim linked instead. */
    private def withShim(html: String): String =
      val matcher = PathToRoot.matcher(html)
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
        insertBefore(withData, "</head>", s"""<script src="$root$ShimDir/$ShimFile"></script>""")

    /** Remove third-party scripts and inline pathToRoot. */
    def strip(html: String): Stripped =
      val shimmed = withShim(html)
      val external = ExternalScript.matcher(shimmed)
      val removed = Iterator.continually(external).takeWhile(_.find()).length
      Stripped(ExternalScript.matcher(shimmed).replaceAll(""), removed)
