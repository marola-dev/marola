package marola.oods

import java.nio.file.{Files, Path}
import java.sql.Connection

import scala.jdk.CollectionConverters.*

import kyo.*

enum CheckOutcome derives CanEqual:
  case NothingToCheck
  case Passed(files: Int, samples: Long, unmatchedPoints: Long)
  case Failed(violations: List[String])

/**
 * `just oods-check` (MIP-0056 §5.4): the gate between a build and a commit. It reads the committed
 * Parquet, not the tables `build.sql` just made, so it also catches a file that reached `main` from
 * somewhere else — a hand-edit, a bad merge, a future adapter's first partition.
 */
object Check:

  def run(dataDir: Path): CheckOutcome =
    val parquet = dataDir.resolve("parquet")
    if !Files.isDirectory(parquet) then CheckOutcome.NothingToCheck
    else
      val files = parquetFiles(parquet)
      if files.isEmpty then CheckOutcome.NothingToCheck
      else
        val connection = Duck.connect()
        try inspect(connection, dataDir, files)
        finally connection.close()

  def exitCode(outcome: CheckOutcome): Int = outcome match
    case CheckOutcome.NothingToCheck  => 0
    case CheckOutcome.Passed(_, _, _) => 0
    case CheckOutcome.Failed(_)       => 1

  def lines(outcome: CheckOutcome): List[String] = outcome match
    case CheckOutcome.NothingToCheck =>
      List("oods-check: no data/oods/parquet — nothing to check")
    case CheckOutcome.Passed(files, samples, unmatched) =>
      List(s"oods-check: $files files, $samples samples, $unmatched unmatched points — ok")
    case CheckOutcome.Failed(violations) =>
      s"oods-check: ${violations.size} violations" :: violations.map("  " + _)

  private def parquetFiles(parquet: Path): List[Path] =
    val walk = Files.walk(parquet)
    try
      walk
        .iterator()
        .asScala
        .filter(f => Files.isRegularFile(f) && f.getFileName.toString.endsWith(".parquet"))
        .toList
        .sorted
    finally walk.close()

  private def inspect(connection: Connection, dataDir: Path, files: List[Path]): CheckOutcome =
    Duck.exec(connection, Duck.script("schema.sql"))
    val expected =
      Map("point" -> columns(connection, "point"), "sample" -> columns(connection, "sample"))
    val samples = files.filterNot(_.getFileName.toString == "points.parquet")
    val points = files.filter(_.getFileName.toString == "points.parquet")
    val schemaViolations = files.flatMap { file =>
      val table = if points.contains(file) then "point" else "sample"
      // hive_partitioning off: `year=2025/` in the path is the view's column, not the file's.
      val actual = columns(
        connection,
        s"SELECT * FROM read_parquet(${Duck.lit(file.toString)}, hive_partitioning = false)"
      )
      Option.when(actual != expected(table))(
        s"${dataDir.relativize(file)}: schema is not $table's — ${diff(expected(table), actual)}"
      )
    }
    val keyViolations =
      duplicates(connection, points, "source_id, point_key", "point") ++
        duplicates(
          connection,
          samples,
          "source_id, point_key, sampled_on, sampled_at, channel",
          "sample"
        ) ++
        orphans(connection, points, samples)
    (schemaViolations ++ keyViolations) match
      case Nil =>
        CheckOutcome.Passed(
          files.size,
          samplesIn(connection, samples),
          unmatched(connection, points)
        )
      case violations => CheckOutcome.Failed(violations)

  /** `DESCRIBE` on schema.sql's own tables: the DDL stays the single statement of the truth. */
  private def columns(connection: Connection, target: String): List[(String, String)] =
    Duck.query(connection, s"DESCRIBE $target")(rs => (rs.getString(1), rs.getString(2)))

  private def diff(expected: List[(String, String)], actual: List[(String, String)]): String =
    val missing = expected.filterNot(actual.contains).map(_._1)
    val extra = actual.filterNot(expected.contains).map(_._1)
    s"missing ${missing.mkString("[", ", ", "]")}, unexpected ${extra.mkString("[", ", ", "]")}"

  private def read(files: List[Path]): String =
    s"read_parquet([${files.map(f => Duck.lit(f.toString)).mkString(", ")}], union_by_name = true)"

  private def duplicates(
      connection: Connection,
      files: List[Path],
      key: String,
      table: String
  ): List[String] =
    if files.isEmpty then Nil
    else
      val found = Duck
        .query(
          connection,
          s"SELECT count(*) FROM (SELECT $key FROM ${read(files)} GROUP BY ALL HAVING count(*) > 1)"
        )(_.getLong(1))
        .head
      Option.when(found > 0)(s"$found duplicate $table primary keys").toList

  private def orphans(
      connection: Connection,
      points: List[Path],
      samples: List[Path]
  ): List[String] =
    if points.isEmpty || samples.isEmpty then Nil
    else
      val found = Duck
        .query(
          connection,
          s"""SELECT count(*) FROM ${read(samples)} s
              WHERE NOT EXISTS (SELECT 1 FROM ${read(points)} p
                                WHERE p.source_id = s.source_id AND p.point_key = s.point_key)"""
        )(_.getLong(1))
        .head
      Option.when(found > 0)(s"$found samples with no point row").toList

  private def samplesIn(connection: Connection, files: List[Path]): Long =
    if files.isEmpty then 0L
    else Duck.query(connection, s"SELECT count(*) FROM ${read(files)}")(_.getLong(1)).head

  /** The price of MIP-0056 §8's name join, in rows: a point with samples and no coordinates. */
  private def unmatched(connection: Connection, points: List[Path]): Long =
    if points.isEmpty then 0L
    else
      Duck
        .query(connection, s"SELECT count(*) FROM ${read(points)} WHERE geo_source = 'none'")(
          _.getLong(1)
        )
        .head

/**
 * `just oods-check [--data-dir PATH]` — non-zero on any violation, quiet and free with no store.
 */
object CheckMain extends KyoApp:

  run {
    for
      outcome <- Sync.defer(Check.run(Cli.dataDir(args.toList)))
      _ <- Console.printLine(Check.lines(outcome).mkString("\n"))
      _ <- Sync.defer {
        import AllowUnsafe.embrace.danger
        if Check.exitCode(outcome) != 0 then exit(Check.exitCode(outcome))
      }
    yield ()
  }
