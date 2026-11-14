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
  // The umbrella's own tooling (#942).
  val ToolingPaths = Seq("tools/src")
  val TestsJob = "tools-ci" // ci.yml's munit job
  private val ExcludeDirs = Seq("target", "node_modules")

  /** One job of the Actions API's jobs list; a field of the wrong type reads as absent. */
  def decodeJob(json: ujson.Value): Job =
    def conclusion(v: ujson.Value) = field(v, "conclusion").flatMap(_.strOpt).map(Conclusion.parse)
    Job(
      name = field(json, "name").flatMap(_.strOpt),
      conclusion = conclusion(json),
      steps = field(json, "steps").flatMap(_.arrOpt).toSeq.flatten.map(s => Step(conclusion(s)))
    )

  /** Code lines for one language out of `cloc --json`; 0 when it found none of that language. */
  def parseCloc(payload: String, language: String): Either[String, Long] =
    Util.readJson(payload).map { json =>
      field(json, language).flatMap(field(_, "code")).flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
    }

  /** Every job of one workflow run, steps included. Needs `actions: read` on the token. */
  private def fetchJobs(repo: String, runId: String): Either[String, Seq[Job]] =
    def fetchFrom(page: Int): Either[String, Seq[Job]] =
      val url = s"repos/$repo/actions/runs/$runId/jobs?per_page=100&page=$page"
      call(Seq("gh", "api", "-H", "Accept: application/vnd.github+json", url), os.pwd).flatMap {
        result =>
          if result.exitCode != 0 then Left(s"repo_stats: gh api $url: ${result.err.text()}")
          else
            Util.readJson(result.out.text()).flatMap { json =>
              val batch = field(json, "jobs").flatMap(_.arrOpt).toSeq.flatten.map(decodeJob)
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

  def write(outDir: os.Path, badges: Seq[BadgeFile]): Seq[os.Path] =
    os.makeDir.all(outDir)
    for file <- badges yield
      os.write.over(outDir / file.name, Util.dumps(file.badge.toJson) + "\n")
      outDir / file.name

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
                  BadgeFile("ci.json", Badge.ci(StepTally.of(jobs, excludeJob))),
                  BadgeFile("tests.json", Badge.tests(jobs, TestsJob))
                )
              )
          project = rootPath / "tools" / "project.scala"
          versions = if os.isFile(project) then Badge.versions(os.read(project)) else Nil
        yield Seq(
          BadgeFile("scala-loc.json", Badge.loc("scala", app, Badge.ScalaColor)),
          BadgeFile("tooling-loc.json", Badge.loc("scala LOC", tooling, Badge.ScalaColor))
        ) ++ ci ++ versions
      badges match
        case Left(error) =>
          System.err.println(error)
          1
        case Right(docs) =>
          val written = write(os.Path(outDir, os.pwd), docs)
          for (file, path) <- docs.zip(written) do
            println(s"${file.shownIn(outDir)}: ${os.read(path).trim}")
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
