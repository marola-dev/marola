package marola.bench

import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.util.Try

import kyo.*

import marola.ledger.RunLedger
import marola.ledger.RunLedger.RunHandle

/**
 * MIP-0010 §5, task 4: what an `OceanBenchmark.Report` looks like as a ledger run — experiment
 * `<prefix>/benchmark`, params (which model, embedder, threshold, corpus and commit produced it),
 * one metric per `ArmSummary` column, the Markdown report as the artifact.
 */
object BenchmarkLedger:

  /** The run's provenance, everything the report itself does not carry. */
  final case class Context(
      model: String,
      embedModel: String,
      minScore: Double,
      corpusSha: String,
      gitSha: String
  )

  def params(report: OceanBenchmark.Report, context: Context): Map[String, String] =
    Map(
      "model" -> context.model,
      "embed_model" -> context.embedModel,
      "min_score" -> context.minScore.toString,
      "corpus_sha" -> context.corpusSha,
      "git_sha" -> context.gitSha,
      "questions" -> report.results.map(_.q.id).distinct.size.toString
    )

  /**
   * `<arm>.<column>` for every `ArmSummary` column — the same numbers as the report's first table.
   */
  def metrics(report: OceanBenchmark.Report): Map[String, Double] =
    report.summaries.flatMap { a =>
      List(
        s"${a.arm}.coverage_in_corpus" -> a.coverageInCorpus,
        s"${a.arm}.coverage_general" -> a.coverageGeneral,
        s"${a.arm}.coverage_all" -> a.coverageAll,
        s"${a.arm}.cited_pct" -> a.citedPct,
        s"${a.arm}.abstained_pct" -> a.abstainedPct,
        s"${a.arm}.mean_ms" -> a.meanMs.toDouble
      )
    }.toMap

  /** `data/benchmark-20260905-1550.md` → `benchmark-20260905-1550`, the run's display name. */
  def runName(reportPath: Path): String =
    reportPath.getFileName.toString.stripSuffix(".md")

  /** One run, in order: `start` → `metrics` → `artifact` → `end(ok = true)`. */
  def log(
      ledger: RunLedger,
      experimentPrefix: String,
      report: OceanBenchmark.Report,
      context: Context,
      reportPath: Path
  ): Option[RunHandle] < Sync =
    val experiment = s"$experimentPrefix/benchmark"
    for
      started <- Abort.run(
        Abort.catching[Throwable](
          ledger.start(experiment, runName(reportPath), params(report, context))
        )
      )
      handle <- started match
        case Result.Success(run) =>
          for
            rest <- Abort.run(
              Abort.catching[Throwable](
                for
                  _ <- ledger.metrics(run, metrics(report))
                  _ <- ledger.artifact(run, reportPath)
                  _ <- ledger.end(run, ok = true)
                yield ()
              )
            )
            outcome <- rest match
              case Result.Success(_) => Sync.defer(Some(run))
              case _ =>
                Abort.run(Abort.catching[Throwable](ledger.end(run, ok = false))).map(_ => None)
          yield outcome
        case _ => Sync.defer(None)
    yield handle

  /**
   * First 12 hex chars of SHA-256 over every corpus file `marola.knowledge.Corpus.listFiles` sees
   * (every top-level `.md` file plus every `.md` file directly under `safety/`, MIP-0022), sorted,
   * name and content both hashed — so a renamed or edited document changes it and anything else in
   * the directory (README.txt, an editor's swap file) does not.
   */
  def corpusSha(dir: Path): String =
    if !Files.isDirectory(dir) then "none"
    else
      val digest = MessageDigest.getInstance("SHA-256")
      marola.knowledge.Corpus
        .listFiles(dir)
        .sortBy(dir.relativize(_).toString)
        .foreach { p =>
          digest.update(dir.relativize(p).toString.getBytes("UTF-8"))
          digest.update(0.toByte)
          digest.update(Files.readAllBytes(p))
          digest.update(0.toByte)
        }
      digest.digest().take(6).map(b => f"$b%02x").mkString

  /**
   * `git rev-parse --short HEAD` in `dir`, `"unknown"` when git or the repo is not there (the
   * Docker image, for one).
   */
  def gitSha(dir: Path = Path.of(".")): String =
    sys.env.get("GITHUB_SHA").map(_.take(7)).getOrElse {
      Try {
        val process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
          .directory(dir.toFile)
          .redirectErrorStream(true)
          .start()
        val out = new String(process.getInputStream.readAllBytes(), "UTF-8").trim
        if process.waitFor() == 0 && out.nonEmpty then out else "unknown"
      }.getOrElse("unknown")
    }
