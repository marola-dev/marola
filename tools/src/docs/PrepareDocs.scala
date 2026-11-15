package marola.tools.docs

import marola.tools.shared.Cli
import marola.tools.shared.Proc
import marola.tools.shared.{GitNames, Invocation, Sha, Util}

import mainargs.{arg, main, ParserForMethods}
import SiteModel.*

// Build docs.marola.dev's source tree (MIP-0074 §5.3): the umbrella at the root, every repo of
// mkdocs/repos.yml at 5-Repos/<name>/ with its api-docs branch, links rewritten by doc-links,
// build.json naming each commit. See docs/3-Ways-of-working/DOCS-SITE.md.
//
//     marola-tools prepare-docs [BUILD_DIR]   # default .tmp/docs-aggregated, rebuilt fresh
object PrepareDocs:
  type Step[A] = Either[Failure, A]

  private def said(message: String) = Failure.Said(message)

  /** mkdocs.yml's `exclude_docs: |` block, one pattern per line. */
  def excludeDocs(mkdocsYml: String): Vector[String] =
    val (_, patterns) = mkdocsYml.split("\n", -1).foldLeft((false, Vector.empty[String])) {
      case ((inBlock, patterns), line) =>
        if line.matches("""exclude_docs:[ \t]*\|.*""") then (true, patterns)
        else
          val still = inBlock && !line.matches("""[^ \t].*""")
          val first = line.trim.split("""\s+""").find(_.nonEmpty).filterNot(_.startsWith("#"))
          (still, patterns ++ first.filter(_ => still))
    }
    patterns

  /** `<name>` checked out at flake.lock's `nodes.<name>.locked.rev`, under `.tmp/docs-sources/`. */
  def fetchFlakeLockSource(root: os.Path, name: String, config: PrepareConfig): Step[os.Path] =
    val dir = root / Names.Tmp / Names.DocsSources / name
    val url = config.sourceRemote.url(name)
    def git(args: String*) =
      Proc.exact(config.gitEnv).call(root, "git" +: args, os.Pipe).exitCode == 0
    def step(ok: => Boolean, failed: String): Step[Unit] = Either.cond(ok, (), said(failed))
    for
      rev <- lockedRev(root / Names.FlakeLock, name)
        .toRight(said(s"prepare-docs: no nodes.$name.locked.rev in $root/${Names.FlakeLock}"))
      _ = os.remove.all(dir)
      _ <- step(git("init", "-q", dir.toString), s"prepare-docs: could not create $dir for $name")
      _ <- step(
        git("-C", dir.toString, "fetch", "-q", "--depth", "1", url, rev),
        s"prepare-docs: could not fetch $name at $rev from $url"
      )
      _ <- step(
        git(
          "-C",
          dir.toString,
          "-c",
          "advice.detachedHead=false",
          "checkout",
          "-q",
          GitNames.FetchHead
        ),
        s"prepare-docs: could not check out $name at $rev in $dir"
      )
    yield dir

  private def lockedRev(flakeLock: os.Path, name: String): Option[String] =
    import Util.field
    scala.util
      .Try(ujson.read(os.read(flakeLock)))
      .toOption
      .flatMap(field(_, "nodes"))
      .flatMap(field(_, name))
      .flatMap(field(_, "locked"))
      .flatMap(field(_, "rev"))
      .flatMap(_.strOpt)

  /** HEAD of the checkout at `dir`, never of a repo enclosing it (an uninitialised submodule is an
    * empty directory inside the umbrella).
    */
  def checkoutSha(dir: os.Path, gitEnv: Map[String, String]): Option[Sha] =
    val git = Proc.exact(gitEnv)
    def ownTop =
      val top = git.captureQuiet(dir, "git", "-C", dir.toString, "rev-parse", "--show-toplevel")
      top.exitCode == 0 && top.out == dir.toNIO.toRealPath().toString
    def head =
      val head = git.capture(dir, "git", "-C", dir.toString, "rev-parse", "HEAD")
      Option.when(head.exitCode == 0)(head.out).flatMap(Sha.parse(_).toOption)
    Option.when(os.isDir(dir) && ownTop)(()).flatMap(_ => head)

  def mountRepo(
      src: os.Path,
      name: String,
      sha: Sha,
      dest: os.Path,
      docLinksArgs: Seq[String]
  ): Step[Unit] =
    val landing =
      Landing.check(os.isFile(src / Names.Readme), os.exists(src / Names.Docs / Names.Index))
    val args = Seq(
      "--repo",
      src.toString,
      "--name",
      name,
      "--sha",
      sha.value,
      "--out",
      dest.toString
    ) ++ docLinksArgs
    for
      _ <- landing.map(problem => said(problem.problem(name))).toLeft(())
      _ <- Either.cond(DocLinks.cli(args) == 0, (), Failure.Reported)
    yield os.write.append(dest / Names.Index, builtFrom(name, sha))

  /** The relative link targets in `listing` (doc-links --links: FILE<TAB>link) that name no file.
    */
  def brokenLinks(listing: String, buildDir: os.Path): Vector[String] =
    listing.split("\n").toVector.map(ListedLink.parse).collect {
      case listed if listed.checked && !exists(listed.candidate) => listed.broken(buildDir.toString)
    }

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
    if listed.exists(_._1 != 0) then Left(Failure.Reported)
    else
      val broken = listed.flatMap((_, out) => brokenLinks(out, buildDir))
      if broken.isEmpty then Right(()) else Left(said(broken.mkString("\n")))

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
  def prepare(root: os.Path, buildDir: os.Path, config: PrepareConfig): Step[Unit] =
    val mkdocsYml = root / Names.Mkdocs / Names.MkdocsYml
    os.remove.all(buildDir)
    os.makeDir.all(buildDir)

    def checkout(repo: Repo): Step[Checkout] =
      val src = repo.source match
        case RepoSource.FlakeLock => fetchFlakeLockSource(root, repo.name, config)
        case RepoSource.Submodule => Right(root / repo.name)
      src.flatMap { dir =>
        checkoutSha(dir, config.gitEnv)
          .map(Checkout(repo, dir, _))
          .toRight(
            said(
              s"prepare-docs: ${repo.name} is not checked out at $dir — run `git submodule update --init` first"
            )
          )
      }

    def mountWithApiDocs(excludes: Seq[String])(c: Checkout): Step[Unit] =
      val dest = buildDir / Names.ReposMount / c.repo.name
      mountRepo(c.dir, c.repo.name, c.sha, dest, excludes).flatMap { _ =>
        val fetched = FetchApiDocs.fetch(c.repo.name, dest / Names.ApiDocs, config.apiDocs)
        Either.cond(fetched == 0, (), Failure.Reported)
      }

    for
      _ <- Either.cond(os.isFile(mkdocsYml), (), said(s"prepare-docs: no $mkdocsYml"))
      excludes = excludeDocs(os.read(mkdocsYml)).flatMap(p => Seq("--exclude", p))
      repos <- ReposManifest.parse(root / Names.Mkdocs / Names.ReposYml).left.map(said)
      checkouts <- eachOk(repos)(checkout)
      umbrellaSha <- checkoutSha(root, config.gitEnv)
        .toRight(said(s"prepare-docs: $root is not a git checkout"))
      // The devkit is fetched outside the umbrella, so umbrella links can't reach into it.
      submodules = checkouts
        .filter(_.repo.source == RepoSource.Submodule)
        .flatMap(c => Seq("--submodule", s"${c.repo.name}=${c.sha.value}"))
      _ <- mountRepo(
        root,
        UmbrellaRepo,
        umbrellaSha,
        buildDir,
        Seq("--umbrella") ++ excludes ++ submodules
      )
      // llmstxt.org: served at the site root, docs.marola.dev/llms.txt.
      _ =
        if os.isFile(root / Names.LlmsTxt) then
          os.copy.over(root / Names.LlmsTxt, buildDir / Names.LlmsTxt)
      _ <- eachOk(checkouts)(mountWithApiDocs(excludes))
      _ = os.write(buildDir / Names.BuildJson, buildJson(umbrellaSha, checkouts))
      _ <- checkLinks(buildDir)
    yield ()

  @main(doc = "Build docs.marola.dev's source tree (MIP-0074 §5.3)")
  def run(
      @arg(positional = true, doc = "default .tmp/docs-aggregated, rebuilt fresh every run")
      buildDir: Option[String] = None
  ): Int =
    val invocation = Invocation.current()
    val root = invocation.repoRoot
    val dir =
      buildDir.map(invocation.path).getOrElse(root / Names.Tmp / Names.DocsAggregated)
    prepare(root, dir, PrepareConfig.from(invocation.env)) match
      case Right(())                => 0
      case Left(Failure.Said(text)) => System.err.println(text); 1
      case Left(Failure.Reported)   => 1

  def cli(args: Seq[String]): Int = Cli.run(ParserForMethods(this), "prepare-docs", args)
