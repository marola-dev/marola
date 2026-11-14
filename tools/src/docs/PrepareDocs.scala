package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Proc
import marola.tools.shared.Util

import mainargs.{arg, main, ParserForMethods}
import scala.util.boundary, boundary.break

// Build docs.marola.dev's source tree (MIP-0074 §5.3): the umbrella at the root, every repo of
// mkdocs/repos.yml at 5-Repos/<name>/ with its api-docs branch, links rewritten by doc-links,
// build.json naming each commit. See docs/3-Ways-of-working/DOCS-SITE.md.
//
//     marola-tools prepare-docs [BUILD_DIR]   # default .tmp/docs-aggregated, rebuilt fresh
object PrepareDocs:
  val DefaultSourceRemote = "https://github.com/marola-dev/<repo>"

  case class Checkout(repo: ReposManifest.Repo, dir: os.Path, sha: String)

  // A Left carries the message to print; an empty one means a tool already reported on stderr.
  type Step[A] = Either[String, A]

  /** mkdocs.yml's `exclude_docs: |` block, one pattern per line. */
  def excludeDocs(mkdocsYml: String): Vector[String] =
    var inBlock = false
    val patterns = Vector.newBuilder[String]
    for line <- mkdocsYml.split("\n", -1) do
      if line.matches("""exclude_docs:[ \t]*\|.*""") then inBlock = true
      else
        if inBlock && line.matches("""[^ \t].*""") then inBlock = false
        val fields = line.trim.split("""\s+""").filter(_.nonEmpty)
        if inBlock && fields.nonEmpty && !fields.head.startsWith("#") then patterns += fields.head
    patterns.result()

  /** `<name>` checked out at flake.lock's `nodes.<name>.locked.rev`, under `.tmp/docs-sources/`. */
  def fetchFlakeLockSource(root: os.Path, name: String, env: Map[String, String]): Step[os.Path] =
    val rev = lockedRev(root / "flake.lock", name)
      .toRight(s"prepare-docs: no nodes.$name.locked.rev in $root/flake.lock")
    rev.flatMap { rev =>
      val url = env
        .get("DOCS_SOURCE_REMOTE")
        .filter(_.nonEmpty)
        .getOrElse(DefaultSourceRemote)
        .replace("<repo>", name)
      val dir = root / ".tmp" / "docs-sources" / name
      os.remove.all(dir)
      def git(args: String*) = Proc.exact(env).call(os.pwd, "git" +: args, os.Pipe).exitCode == 0
      if !git("init", "-q", dir.toString) then
        Left(s"prepare-docs: could not create $dir for $name")
      else if !git("-C", dir.toString, "fetch", "-q", "--depth", "1", url, rev) then
        Left(s"prepare-docs: could not fetch $name at $rev from $url")
      else if !git(
          "-C",
          dir.toString,
          "-c",
          "advice.detachedHead=false",
          "checkout",
          "-q",
          "FETCH_HEAD"
        )
      then Left(s"prepare-docs: could not check out $name at $rev in $dir")
      else Right(dir)
    }

  private def lockedRev(flakeLock: os.Path, name: String): Option[String] =
    val lock = scala.util.Try(ujson.read(os.read(flakeLock))).toOption
    import Util.field
    lock
      .flatMap(field(_, "nodes"))
      .flatMap(field(_, name))
      .flatMap(field(_, "locked"))
      .flatMap(field(_, "rev"))
      .flatMap(_.strOpt)

  /** HEAD of the checkout at `dir`, never of a repo enclosing it (an uninitialised submodule is an
    * empty directory inside the umbrella).
    */
  def checkoutSha(dir: os.Path, env: Map[String, String]): Option[String] =
    if !os.isDir(dir) then None
    else
      val git = Proc.exact(env)
      val top = git.captureQuiet(os.pwd, "git", "-C", dir.toString, "rev-parse", "--show-toplevel")
      val ownTop = top.exitCode == 0 && top.out == dir.toNIO.toRealPath().toString
      if !ownTop then None
      else
        val head = git.capture(os.pwd, "git", "-C", dir.toString, "rev-parse", "HEAD")
        Option.when(head.exitCode == 0)(head.out)

  def mountRepo(
      src: os.Path,
      name: String,
      sha: String,
      dest: os.Path,
      docLinksArgs: Seq[String]
  ): Step[Unit] =
    if !os.isFile(src / "README.md") then
      Left(s"prepare-docs: $name has no README.md — it is the repo's landing page (MIP-0074 §5.2)")
    else if os.exists(src / "docs" / "index.md") then
      Left(
        s"prepare-docs: $name has both README.md and docs/index.md — the README is the landing; remove docs/index.md (MIP-0074 D1)"
      )
    else
      val args = Seq(
        "--repo",
        src.toString,
        "--name",
        name,
        "--sha",
        sha,
        "--out",
        dest.toString
      ) ++ docLinksArgs
      if DocLinks.cli(args) != 0 then Left("")
      else
        val built =
          s"\n_Built from [marola-dev/$name@${sha.take(7)}](https://github.com/marola-dev/$name/tree/$sha)._\n"
        os.write.append(dest / "index.md", built)
        Right(())

  /** The relative link targets in `listing` (doc-links --links: FILE<TAB>link) that name no file.
    */
  def brokenLinks(listing: String, buildDir: os.Path): Vector[String] =
    listing.split("\n").toVector.flatMap { line =>
      val (file, rest) = line.span(_ != '\t')
      val link = rest.dropWhile(_ == '\t').reverse.dropWhile(_ == '\t').reverse
      val target = link.takeWhile(_ != '#').takeWhile(_ != '?')
      val skipped = link.isEmpty || link.startsWith("/") || link.startsWith("#") || target.isEmpty
      Option.when(!skipped && !exists(s"${posixDirname(file)}/$target")) {
        s"prepare-docs: ${file.stripPrefix(s"$buildDir/")}: broken relative link: $link"
      }
    }

  private def posixDirname(file: String): String =
    val cut = file.lastIndexOf('/')
    if cut < 0 then "." else if cut == 0 then "/" else file.take(cut)

  // `[ -e ]`: a trailing slash must name a directory, which java.nio's Path would drop.
  private def exists(path: String): Boolean =
    scala.util
      .Try {
        val p = java.nio.file.Paths.get(path)
        java.nio.file.Files.exists(p) && (!path.endsWith("/") || java.nio.file.Files.isDirectory(p))
      }
      .getOrElse(false)

  /** Every relative link in every page resolves, reported here rather than deep in a
    * `mkdocs --strict` log. The links are doc-links's, so code is read as the rewrite reads it.
    */
  def checkLinks(buildDir: os.Path): Step[Unit] =
    val pages = os.walk(buildDir).filter(_.last.endsWith(".md")).sorted
    val listed = pages.map(page => captureStdout(DocLinks.cli(Seq("--links", page.toString))))
    if listed.exists(_._1 != 0) then Left("")
    else
      val broken = listed.flatMap((_, out) => brokenLinks(out, buildDir))
      if broken.isEmpty then Right(()) else Left(broken.mkString("\n"))

  // Trap: a command may print with Scala's println (Console.out) or System.out; catch both.
  private def captureStdout(body: => Int): (Int, String) =
    val buffer = java.io.ByteArrayOutputStream()
    val stream = java.io.PrintStream(buffer, true, "UTF-8")
    val saved = System.out
    System.setOut(stream)
    try
      val code = Console.withOut(stream)(body)
      stream.flush()
      (code, buffer.toString("UTF-8"))
    finally System.setOut(saved)

  private def eachOk[A, B](items: Seq[A])(step: A => Step[B]): Step[Vector[B]] =
    items.foldLeft[Step[Vector[B]]](Right(Vector.empty)) { (done, item) =>
      done.flatMap(results => step(item).map(results :+ _))
    }

  /** `root` is the umbrella checkout, with mkdocs/repos.yml and mkdocs/mkdocs.yml. */
  def prepare(root: os.Path, buildDir: os.Path, env: Map[String, String]): Step[Unit] =
    val gitEnv = FetchApiDocs.gitEnv(env)
    val mkdocsYml = root / "mkdocs" / "mkdocs.yml"
    os.remove.all(buildDir)
    os.makeDir.all(buildDir)

    def checkout(repo: ReposManifest.Repo): Step[Checkout] =
      val src = repo.source match
        case ReposManifest.Source.FlakeLock => fetchFlakeLockSource(root, repo.name, gitEnv)
        case ReposManifest.Source.Submodule => Right(root / repo.name)
      src.flatMap { dir =>
        checkoutSha(dir, gitEnv)
          .map(Checkout(repo, dir, _))
          .toRight(
            s"prepare-docs: ${repo.name} is not checked out at $dir — run `git submodule update --init` first"
          )
      }

    def mountWithApiDocs(excludes: Seq[String])(c: Checkout): Step[Unit] =
      val dest = buildDir / "5-Repos" / c.repo.name
      mountRepo(c.dir, c.repo.name, c.sha, dest, excludes).flatMap { _ =>
        if FetchApiDocs.fetch(c.repo.name, dest / "api-docs", gitEnv) == 0 then Right(())
        else Left("")
      }

    for
      _ <- Either.cond(os.isFile(mkdocsYml), (), s"prepare-docs: no $mkdocsYml")
      excludes = excludeDocs(os.read(mkdocsYml)).flatMap(p => Seq("--exclude", p))
      repos <- ReposManifest.parse(root / "mkdocs" / "repos.yml")
      checkouts <- eachOk(repos)(checkout)
      umbrellaSha <- checkoutSha(root, gitEnv).toRight(s"prepare-docs: $root is not a git checkout")
      // The devkit is fetched outside the umbrella, so umbrella links can't reach into it.
      submodules = checkouts
        .filter(_.repo.source == ReposManifest.Source.Submodule)
        .flatMap(c => Seq("--submodule", s"${c.repo.name}=${c.sha}"))
      _ <- mountRepo(
        root,
        "marola",
        umbrellaSha,
        buildDir,
        Seq("--umbrella") ++ excludes ++ submodules
      )
      // llmstxt.org: served at the site root, docs.marola.dev/llms.txt.
      _ =
        if os.isFile(root / "llms.txt") then os.copy.over(root / "llms.txt", buildDir / "llms.txt")
      _ <- eachOk(checkouts)(mountWithApiDocs(excludes))
      _ = os.write(buildDir / "build.json", buildJson(umbrellaSha, checkouts))
      _ <- checkLinks(buildDir)
    yield ()

  def buildJson(umbrellaSha: String, checkouts: Seq[Checkout]): String =
    val shas = ujson.Obj("marola" -> umbrellaSha)
    for c <- checkouts do shas(c.repo.name) = c.sha
    Util.dumps(shas, indent = Some(2)) + "\n"

  @main(doc = "Build docs.marola.dev's source tree (MIP-0074 §5.3)")
  def run(
      @arg(positional = true, doc = "default .tmp/docs-aggregated, rebuilt fresh every run")
      buildDir: Option[String] = None
  ): Int =
    val root = Util.repoRoot
    val dir = buildDir.map(os.Path(_, os.pwd)).getOrElse(root / ".tmp" / "docs-aggregated")
    prepare(root, dir, sys.env) match
      case Right(())     => 0
      case Left(message) =>
        if message.nonEmpty then System.err.println(message)
        1

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "prepare-docs", args)

// mkdocs/repos.yml's fixed shape (MIP-0070 §5.5), read without a YAML library.
object ReposManifest:
  enum Source:
    case Submodule, FlakeLock

  case class Repo(name: String, source: Source)

  def parse(file: os.Path): Either[String, Vector[Repo]] =
    if !os.isFile(file) then Left(s"repos_manifest: manifest not found: $file")
    else parseText(os.read(file), file.toString)

  private def value(line: String): String =
    val v =
      line.stripSuffix("\r").replaceFirst("""^[^:]*:[ \t]*""", "").replaceFirst("""[ \t]+$""", "")
    if v.length >= 2 && v.startsWith("\"") && v.endsWith("\"") then v.drop(1).dropRight(1) else v

  def parseText(text: String, fileName: String): Either[String, Vector[Repo]] =
    val repos = Vector.newBuilder[Repo]
    var name = ""
    var source = Source.Submodule
    def flush(): Unit =
      if name.nonEmpty then repos += Repo(name, source)
      name = ""
      source = Source.Submodule
    val lines = text.split("\n", -1).toVector.dropRight(if text.endsWith("\n") then 1 else 0)
    boundary:
      for (line, index) <- lines.zipWithIndex do
        val at = s"repos_manifest: $fileName:${index + 1}:"
        if line.matches("""[ \t]*""") || line.matches("""[ \t]*#.*""") then ()
        else if line.startsWith("- name:") then
          flush()
          name = value(line)
        else if line.startsWith("  mount:") then
          break(Left(s"$at mount: is gone, every repo mounts at 5-Repos/<name>/ (MIP-0074 §5.3)"))
        else if line.startsWith("  source:") then
          val written = value(line)
          if name.isEmpty then break(Left(s"$at source before any `- name:`"))
          if written != "flake-lock" then
            break(Left(s"$at source must be flake-lock, got: $written"))
          source = Source.FlakeLock
        else break(Left(s"$at unrecognised line: $line"))
      flush()
      Right(repos.result())
