package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.sql.{Connection, DriverManager, ResultSet}

import scala.jdk.CollectionConverters.*
import scala.util.control.NoStackTrace

import kyo.*

/** DuckDB JDBC in one place: an in-memory database, the scripts under `oods/sql`, a result list. */
object Duck:

  def connect(): Connection = DriverManager.getConnection("jdbc:duckdb:")

  /** `oods/sql/` is a resource directory (`build.sbt`), so a script is found from any cwd. */
  def script(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(name)
    if stream == null then throw MissingScriptException(name)
    try String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  final case class MissingScriptException(name: String)
      extends Exception(s"oods: $name is not on the classpath")
      with NoStackTrace

  /** DuckDB's JDBC driver runs a whole multi-statement script in one call — verified on 1.5.5.1. */
  def exec(connection: Connection, sql: String): Unit =
    val statement = connection.createStatement()
    try
      val _ = statement.execute(sql)
    finally statement.close()

  def query[A](connection: Connection, sql: String)(read: ResultSet => A): List[A] =
    val statement = connection.createStatement()
    try
      val rows = statement.executeQuery(sql)
      val buffer = List.newBuilder[A]
      while rows.next() do buffer += read(rows)
      buffer.result()
    finally statement.close()

  /** Single quotes doubled: a source id reaches the SQL below as a literal, never as syntax. */
  def lit(value: String): String = s"'${value.replace("'", "''")}'"

final case class BuildReport(written: List[String], unchanged: List[String]) derives CanEqual

/**
 * A build that read a raw file it does not understand writes nothing: a renamed upstream column
 * parses as zero samples, and a silent success would replace the manifest's partition map with an
 * empty store (MIP-0056 §8 — "a portal outage is not an error in the data", but a changed export
 * is).
 */
enum BuildOutcome derives CanEqual:
  case Built(report: BuildReport)
  case Failed(reasons: List[String])

/**
 * `build.sql` → one Parquet file per partition (MIP-0056 §5.3). A partition is rewritten only when
 * the content hash of its sorted rows differs from the manifest's, so a rebuild over unchanged raw
 * files touches nothing and `git status` stays empty.
 */
object Build:

  def run(dataDir: Path, prune: Boolean = false): BuildOutcome =
    (if prune then Nil else missingRaw(dataDir)) ++ foreignHeaders(dataDir) match
      case Nil =>
        val connection = Duck.connect()
        try
          Duck.exec(
            connection,
            s"SET VARIABLE data_dir = ${Duck.lit(dataDir.toAbsolutePath.toString)}"
          )
          Duck.exec(connection, Duck.script("build.sql"))
          val sources =
            Duck.query(connection, "SELECT DISTINCT source_id FROM point ORDER BY 1")(
              _.getString(1)
            )
          empty(connection, dataDir, sources) match
            case Nil =>
              BuildOutcome.Built(
                sources.foldLeft(BuildReport(Nil, Nil))(oneSource(connection, dataDir, prune, _, _))
              )
            case reasons => BuildOutcome.Failed(reasons)
        finally connection.close()
      case reasons => BuildOutcome.Failed(reasons)

  /** `raw/<source>/csv/<key>/<year>.csv` — `ImaScAdapter.rawPath`'s layout, read back. */
  private def rawCsvFiles(dataDir: Path): List[Path] =
    val raw = dataDir.resolve("raw")
    if !Files.isDirectory(raw) then Nil
    else
      val walk = Files.walk(raw)
      try
        walk
          .iterator()
          .asScala
          .filter(f => Files.isRegularFile(f) && f.getFileName.toString.endsWith(".csv"))
          .toList
          .sorted
      finally walk.close()

  private def manifestFiles(dataDir: Path): List[Path] =
    val dir = dataDir.resolve("manifest")
    if !Files.isDirectory(dir) then Nil
    else
      val list = Files.list(dir)
      try list.iterator().asScala.filter(_.getFileName.toString.endsWith(".json")).toList.sorted
      finally list.close()

  /**
   * The raw layer is not in git — it lives in a Hugging Face dataset (MIP-0056 §4.4), so a clone
   * has the manifest and no `raw/`, and an ingest that ran before the pull has only the year it
   * fetched. Building either would drop every absent file from the manifest (`Manifest.prune`
   * below) and the next run would refetch the lot, so the manifest and the disk must agree first.
   * `--prune` is the deliberate deletion: partitions removed by hand, as MIP-0056 task 10 did.
   */
  private def missingRaw(dataDir: Path): List[String] =
    manifestFiles(dataDir).flatMap { file =>
      val gone = Manifest
        .read(file)
        .raw
        .keys
        .toList
        .sorted
        .filterNot(path => Files.isRegularFile(dataDir.resolve(path)))
      Option.when(gone.nonEmpty)(
        s"${file.getFileName}: ${gone.size} raw files the manifest names are not on disk " +
          s"(${gone.take(3).mkString(", ")}${if gone.sizeIs > 3 then ", …" else ""}) — run " +
          "`just oods-raw-pull` first, or `just oods-build -- --prune` if you deleted them on purpose"
      )
    }

  /**
   * `build.sql` skips line 1 whatever it says, so a reordered or renamed upstream column would
   * parse as zero samples rather than as an error. The parser's own check, applied before any COPY.
   */
  private def foreignHeaders(dataDir: Path): List[String] =
    rawCsvFiles(dataDir).flatMap { file =>
      val first = Files.lines(file, UTF_8)
      val header =
        try first.findFirst().orElse("")
        finally first.close()
      Option.when(!ImaScCsv.hasKnownHeader(header))(
        s"${dataDir.relativize(file)}: not the portal's 13-column header"
      )
    }

  private def empty(connection: Connection, dataDir: Path, sources: List[String]): List[String] =
    val withRaw =
      rawCsvFiles(dataDir).flatMap(f => dataDir.relativize(f).toString.split("/").lift(1))
    (sources ++ withRaw).distinct.sorted.flatMap { sourceId =>
      val rows = Duck
        .query(connection, s"SELECT count(*) FROM sample WHERE source_id = ${Duck.lit(sourceId)}")(
          _.getLong(1)
        )
        .head
      Option.when(withRaw.contains(sourceId) && rows == 0)(s"$sourceId: raw CSVs but no samples")
    }

  /** The relative path of a partition is its manifest key — `raw/…`'s convention, one level up. */
  final private case class Target(path: String, select: String, order: String)

  private def targets(connection: Connection, sourceId: String): List[Target] =
    val id = Duck.lit(sourceId)
    val points = Target(
      s"parquet/$sourceId/points.parquet",
      s"SELECT * FROM point WHERE source_id = $id",
      "point_key"
    )
    val years =
      Duck.query(connection, s"SELECT DISTINCT year FROM sample WHERE source_id = $id ORDER BY 1")(
        _.getInt(1)
      )
    points :: years.map { year =>
      Target(
        s"parquet/$sourceId/samples/year=$year/samples.parquet",
        // `year` lives in the path, so writing it into the file too would collide with the
        // hive_partitioning column `br_bathing_water` reads.
        s"SELECT * EXCLUDE (year) FROM sample WHERE source_id = $id AND year = $year",
        "point_key, sampled_on, sampled_at, channel"
      )
    }

  private def oneSource(
      connection: Connection,
      dataDir: Path,
      prune: Boolean,
      report: BuildReport,
      sourceId: String
  ): BuildReport =
    val manifestFile = dataDir.resolve(s"manifest/$sourceId.json")
    val manifest = Manifest.read(manifestFile)
    val partitions = targets(connection, sourceId)
    val built = partitions.map(t => t.path -> hash(connection, t)).toMap
    val done = partitions.foldLeft(report) { (acc, target) =>
      val file = dataDir.resolve(target.path)
      val known = manifest.partitions.get(target.path).contains(built(target.path))
      if known && Files.isRegularFile(file) then acc.copy(unchanged = acc.unchanged :+ target.path)
      else
        copyTo(connection, target, file)
        acc.copy(written = acc.written :+ target.path)
    }
    // Replaced, not merged: a partition that no longer exists must leave the manifest with it.
    // `raw` is only ever pruned under `--prune`; without it `missingRaw` has already refused.
    val pruned =
      if prune then Manifest.prune(manifest, path => Files.isRegularFile(dataDir.resolve(path)))
      else manifest
    val next = pruned.copy(partitions = built)
    if next != manifest then Manifest.write(manifestFile, next)
    done

  private def hash(connection: Connection, target: Target): String =
    Duck
      .query(
        connection,
        s"""SELECT coalesce(sha256(string_agg(to_json(r), chr(10) ORDER BY ${target.order})), '')
            FROM (${target.select}) r"""
      )(_.getString(1))
      .head

  private def copyTo(connection: Connection, target: Target, file: Path): Unit =
    val _ = Files.createDirectories(file.getParent)
    val tmp = Manifest.tempFile(file)
    Duck.exec(
      connection,
      s"""COPY (${target.select} ORDER BY ${target.order})
          TO ${Duck.lit(tmp.toString)} (FORMAT PARQUET)"""
    )
    val _ =
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

/** `exit` is `KyoApp`'s own member, so a shared non-zero exit has to be a trait, not a helper. */
trait OodsApp extends KyoApp:
  protected def stop(code: Int): Unit < Async =
    if code == 0 then ()
    else
      Sync.defer {
        import AllowUnsafe.embrace.danger
        exit(code)
      }

/** `just oods-build [--data-dir PATH] [--prune]` — the transform half of MIP-0056 §5.2. */
object BuildMain extends OodsApp:

  private def report(outcome: BuildOutcome): String = outcome match
    case BuildOutcome.Built(built) =>
      val lines = built.written.map(path => s"  wrote $path")
      (lines :+ s"${built.written.size} written, ${built.unchanged.size} unchanged").mkString("\n")
    case BuildOutcome.Failed(reasons) =>
      (s"oods-build: ${reasons.size} refused" :: reasons.map("  " + _)).mkString("\n")

  run {
    for
      outcome <- Sync.defer(Build.run(Cli.dataDir(args.toList), args.contains("--prune")))
      _ <- Console.printLine(report(outcome))
      _ <- stop(outcome match
        case BuildOutcome.Built(_)  => 0
        case BuildOutcome.Failed(_) => 1
      )
    yield ()
  }

/** The one flag `oods-build` and `oods-check` share with `oods-ingest`. */
object Cli:
  def dataDir(args: List[String]): Path = args match
    case "--data-dir" :: value :: _ => Paths.get(value)
    case _ :: tail                  => dataDir(tail)
    case Nil                        => Paths.get("data", "oods")
