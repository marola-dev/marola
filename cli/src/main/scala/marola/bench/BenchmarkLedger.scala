package marola.bench

import java.nio.file.{Files, Path}
import java.security.MessageDigest

import scala.jdk.CollectionConverters.*
import scala.util.Try

import kyo.*

import marola.ledger.RunLedger
import marola.ledger.RunLedger.RunHandle

/**
 * MIP-0010 §5, task 4: what an `OceanBenchmark.Report` looks like as a ledger run — experiment
 * `<prefix>/benchmark`, params (which model, embedder, threshold, corpus and commit produced it),
 * one metric per `ArmSummary` column, the Markdown report as the artifact. The Markdown file under
 * `data/` stays the canonical result (`scripts/benchmark_gate.py` reads it, not MLflow); this is
 * additive, and with `RunLedger.Noop` it costs nothing. `params`/`metrics` are pure so the exact
 * shape is unit-tested; `log` is the one effectful step and never fails the benchmark — a ledger
 * error is reported as `None` so the report is still printed and saved.
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

  /**
   * One run, in order: `start` → `metrics` → `artifact` → `end(ok = true)`. Returns the handle so
   * the caller can print the run's URL, or `None` when the ledger failed at any step — after
   * `start` succeeded the run is closed with `end(ok = false)` first (best effort) so the UI shows
   * it as FAILED rather than forever RUNNING. Nothing here throws: the benchmark report was already
   * computed and saved by the time this runs, and a ledger outage must not turn that into a
   * failure.
   */
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
   * First 12 hex chars of SHA-256 over every `*.md` in `dir`, sorted by file name, name and content
   * both hashed — so a renamed or edited document changes it and anything else in the directory
   * (README.txt, an editor's swap file) does not. `"none"` when the directory is missing: the
   * benchmark can still run with an empty corpus and the ledger should say so, not crash.
   */
  def corpusSha(dir: Path): String =
    if !Files.isDirectory(dir) then "none"
    else
      val digest = MessageDigest.getInstance("SHA-256")
      val files = Files.list(dir)
      try
        files
          .iterator()
          .asScala
          .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".md"))
          .toList
          .sortBy(_.getFileName.toString)
          .foreach { p =>
            digest.update(p.getFileName.toString.getBytes("UTF-8"))
            digest.update(0.toByte)
            digest.update(Files.readAllBytes(p))
            digest.update(0.toByte)
          }
      finally files.close()
      digest.digest().take(6).map(b => f"$b%02x").mkString

  /**
   * `git rev-parse --short HEAD` in `dir`, `"unknown"` when git or the repo is not there (the
   * Docker image, for one). `GITHUB_SHA` wins when set — Actions checkouts can be detached.
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
