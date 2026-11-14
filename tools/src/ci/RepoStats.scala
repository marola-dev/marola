package marola.tools.ci

import marola.tools.shared.Cli
import marola.tools.shared.Util

import mainargs.{arg, main, ParserForMethods}
import Util.field

// The README's badges as shields.io endpoint JSON, published to marola-site's `site-data` branch:
// CI health, the tests (tools-ci), the Scala and JDK versions tools/project.scala pins, and the
// Scala LOC of marola-app and of this repo's tooling.
//
//     marola-tools repo-stats write --out-dir stats [--repo marola-dev/marola --run-id 123 --exclude-job repo-stats]
object RepoStats:
  // The app is the marola-app submodule (MIP-0070 task 15); ci.yml checks it out for the count.
  val ScalaPaths = Seq("marola-app/core", "marola-app/local", "marola-app/cli")
  val ScalaColor = "DC322F" // = marola-app's README's hand-written Scala badge
  // The umbrella's own tooling (#942).
  val ToolingPaths = Seq("tools/src")
  val TestsJob = "tools-ci" // ci.yml's munit job
  private val ExcludeDirs = Seq("target", "node_modules")

  // A step that reached one of these actually executed; `skipped`, `neutral` or a null conclusion
  // (a step still queued or running) is not evidence either way and is not counted.
  private val Ran = Set("success", "failure", "cancelled", "timed_out")

  /** One shields.io endpoint document (https://shields.io/badges/endpoint-badge). */
  def badge(label: String, message: String, color: String): ujson.Obj =
    ujson.Obj("schemaVersion" -> 1, "label" -> label, "message" -> message, "color" -> color)

  /** (green, ran) over every step of every job of one run. */
  def countSteps(jobs: Seq[ujson.Value], excludeJob: Option[String]): (Int, Int) =
    val conclusions =
      for
        job <- jobs
        if !excludeJob.exists(name => field(job, "name").flatMap(_.strOpt).contains(name))
        steps <- field(job, "steps").flatMap(_.arrOpt).toSeq
        step <- steps
        conclusion <- field(step, "conclusion").flatMap(_.strOpt)
        if Ran(conclusion)
      yield conclusion
    (conclusions.count(_ == "success"), conclusions.length)

  /** Green only when every step that ran passed: a single red step is not a rounding error. */
  def ciBadge(green: Int, ran: Int): ujson.Obj =
    if ran == 0 then badge("ci steps", "no data", "lightgrey")
    else
      val color =
        if green == ran then "brightgreen"
        else if green.toDouble >= 0.9 * ran then "yellow"
        else "red"
      badge("ci steps", s"$green/$ran green", color)

  /** The munit job's verdict: passing only on success; a skipped or missing job is no data. */
  def testsBadge(jobs: Seq[ujson.Value], job: String): ujson.Obj =
    jobs
      .find(j => field(j, "name").flatMap(_.strOpt).contains(job))
      .flatMap(j => field(j, "conclusion").flatMap(_.strOpt)) match
      case Some("success")                             => badge("tests", "passing", "brightgreen")
      case Some("failure" | "cancelled" | "timed_out") => badge("tests", "failing", "red")
      case _                                           => badge("tests", "no data", "lightgrey")

  /** `//> using <key> <value>` in a scala-cli project file; the first one wins. */
  def directive(project: String, key: String): Option[String] =
    val Using = raw"//> using\s+(\S+)\s+(\S+)\s*".r
    project.linesIterator.collectFirst { case Using(`key`, value) => value }

  def versionBadges(project: String): Seq[(String, ujson.Obj)] =
    Seq(
      "scala-version.json" -> ("scala", directive(project, "scala"), ScalaColor),
      "jdk-version.json" -> ("jdk", directive(project, "jvm"), "007396")
    ).map { case (name, (label, value, color)) =>
      name -> value.fold(badge(label, "no data", "lightgrey"))(badge(label, _, color))
    }

  def locBadge(label: String, code: Long, color: String): ujson.Obj =
    badge(label, s"${thousands(code)} LOC", color)

  private def thousands(n: Long): String =
    n.toString.reverse.grouped(3).mkString(",").reverse

  /** Code lines for one language out of `cloc --json`; 0 when it found none of that language. */
  def parseCloc(payload: String, language: String): Either[String, Long] =
    Util.readJson(payload).map { json =>
      field(json, language).flatMap(field(_, "code")).flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
    }

  /** Every job of one workflow run, steps included. Needs `actions: read` on the token. */
  private def fetchJobs(repo: String, runId: String): Either[String, Seq[ujson.Value]] =
    def fetchFrom(page: Int): Either[String, Seq[ujson.Value]] =
      val url = s"repos/$repo/actions/runs/$runId/jobs?per_page=100&page=$page"
      call(Seq("gh", "api", "-H", "Accept: application/vnd.github+json", url), os.pwd).flatMap {
        result =>
          if result.exitCode != 0 then Left(s"repo_stats: gh api $url: ${result.err.text()}")
          else
            Util.readJson(result.out.text()).flatMap { json =>
              val batch = field(json, "jobs").flatMap(_.arrOpt).toSeq.flatten
              if batch.length < 100 then Right(batch) else fetchFrom(page + 1).map(batch ++ _)
            }
      }
    fetchFrom(1)

  private def call(command: Seq[String], cwd: os.Path): Either[String, os.CommandResult] =
    try Right(os.proc(command).call(cwd = cwd, check = false, stderr = os.Pipe))
    catch
      case _: java.io.IOException if command.head == "cloc" =>
        Left(
          "repo_stats: `cloc` is not on PATH (nix develop has it, and ci.yml takes it from nix develop .#lint)"
        )
      case e: java.io.IOException => Left(s"repo_stats: ${command.head}: ${e.getMessage}")

  /** Code lines of `language` under `paths`; a path that does not exist is simply skipped. */
  private def clocCode(paths: Seq[String], language: String, root: os.Path): Either[String, Long] =
    val present = paths.filter(p => os.exists(root / os.RelPath(p)))
    if present.isEmpty then Right(0)
    else
      val excluded = s"--exclude-dir=${ExcludeDirs.mkString(",")}"
      call(Seq("cloc", "--json", "--quiet", excluded, s"--include-lang=$language") ++ present, root)
        .flatMap { result =>
          val text = result.out.text()
          if result.exitCode != 0 then Left(s"repo_stats: cloc: ${result.err.text()}")
          // cloc prints nothing at all when no file of that language survived the filters.
          else if text.trim.isEmpty then Right(0)
          else parseCloc(text, language)
        }

  /** A written file's path as the command prints it: the --out-dir as given, then the name. */
  def shownPath(outDir: String, name: String): String =
    if outDir.endsWith("/") then outDir + name else s"$outDir/$name"

  def write(outDir: os.Path, badges: Seq[(String, ujson.Value)]): Seq[os.Path] =
    os.makeDir.all(outDir)
    for (name, doc) <- badges yield
      os.write.over(outDir / name, Util.dumps(doc) + "\n")
      outDir / name

  object Write:
    @main(doc = "Write the badge JSONs into --out-dir")
    def run(
        @arg(doc = "Directory the badge JSONs go to") outDir: String,
        @arg(doc = "Repo root the LOC paths are relative to") root: String = ".",
        @arg(doc = "owner/name, for the CI-health badge") repo: String = "marola-dev/marola",
        @arg(doc = "Workflow run to report on; omitted = LOC badges only") runId: Option[String] =
          None,
        @arg(doc = "Job name to leave out (the reporting job itself)") excludeJob: Option[String] =
          None
    ): Int =
      val rootPath = os.Path(root, os.pwd)
      val badges =
        for
          app <- clocCode(ScalaPaths, "Scala", rootPath)
          tooling <- clocCode(ToolingPaths, "Scala", rootPath)
          ci <- runId match
            case None     => Right(Nil)
            case Some(id) =>
              fetchJobs(repo, id).map(jobs =>
                Seq(
                  "ci.json" -> ciBadge.tupled(countSteps(jobs, excludeJob)),
                  "tests.json" -> testsBadge(jobs, TestsJob)
                )
              )
          project = rootPath / "tools" / "project.scala"
          versions = if os.isFile(project) then versionBadges(os.read(project)) else Nil
        yield Seq(
          "scala-loc.json" -> locBadge("scala", app, ScalaColor),
          "tooling-loc.json" -> locBadge("scala LOC", tooling, ScalaColor)
        ) ++ ci ++ versions
      badges match
        case Left(error) =>
          System.err.println(error)
          1
        case Right(docs) =>
          val written = write(os.Path(outDir, os.pwd), docs)
          for ((name, _), path) <- docs.zip(written) do
            println(s"${shownPath(outDir, name)}: ${os.read(path).trim}")
          0

  def cli(args: Seq[String]): Int = args match
    case "write" +: rest           => Cli.run(ParserForMethods(Write), "repo-stats write", rest)
    case Seq("--help") | Seq("-h") =>
      System.err.println(
        "Usage: marola-tools repo-stats write --out-dir DIR [--root .] [--repo OWNER/NAME] [--run-id ID] [--exclude-job NAME]"
      )
      0
    case _ =>
      System.err.println("marola-tools repo-stats: expected the subcommand `write` (try --help)")
      2
