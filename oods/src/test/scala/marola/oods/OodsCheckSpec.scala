package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant

/**
 * `just oods-check` (MIP-0056 §5.4): the gate that runs in `quality-other` and in the ingest
 * workflow. It must be silent and free when there is no store, and loud about exactly which rule a
 * partition broke.
 */
class OodsCheckSpec extends munit.FunSuite:

  private def fixture(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"ima-sc/$name")
    try String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  private val source = ImaScAdapter.DefaultSource

  private def built(): Path =
    val dir = Files.createTempDirectory("oods-check")
    val csv = dir.resolve("raw/ima-sc/csv/florianopolis/campeche/2025.csv")
    val _ = Files.createDirectories(csv.getParent)
    val _ = Files.write(csv, fixture("campeche-2025.csv").getBytes(UTF_8))
    val points = ImaScAdapter.parsePoints(source, fixture("points.json"))
    val _ = Files.write(dir.resolve("raw/ima-sc/points.json"), RawStore.renderPoints(points))
    Manifest.write(
      dir.resolve("manifest/ima-sc.json"),
      Manifest(
        raw = Map(
          "raw/ima-sc/csv/florianopolis/campeche/2025.csv" ->
            RawEntry("u", "sha", 1L, Instant.parse("2026-01-01T00:00:00Z"), 0)
        ),
        partitions = Map.empty
      )
    )
    val _ = Build.run(dir)
    dir

  private def seed(dir: Path, file: String, select: String): Unit =
    val connection = Duck.connect()
    try
      val samples = s"${dir.toAbsolutePath}/parquet/ima-sc/samples/year=2025/samples.parquet"
      Duck.exec(
        connection,
        s"COPY ($select FROM read_parquet('$samples') LIMIT 1) " +
          s"TO '${dir.toAbsolutePath}/parquet/ima-sc/samples/year=2025/$file' (FORMAT PARQUET)"
      )
    finally connection.close()

  test("a store that was never built is nothing to check") {
    val dir = Files.createTempDirectory("oods-check-empty")
    val outcome = Check.run(dir)
    assertEquals(outcome, CheckOutcome.NothingToCheck)
    assertEquals(Check.exitCode(outcome), 0)
    assert(Check.lines(outcome).exists(_.contains("nothing to check")), Check.lines(outcome))
  }

  test("a freshly built store passes and reports its unmatched points") {
    val outcome = Check.run(built())
    outcome match
      case CheckOutcome.Passed(files, samples, unmatched) =>
        assertEquals(files, 2)
        assert(samples > 0, s"no samples counted: $samples")
        assertEquals(unmatched, 0L)
      case other => fail(s"expected a pass, got $other")
    assertEquals(Check.exitCode(outcome), 0)
  }

  test("a duplicated primary key fails the check") {
    val dir = built()
    seed(dir, "duplicate.parquet", "SELECT *")
    Check.run(dir) match
      case CheckOutcome.Failed(violations) =>
        assert(violations.exists(_.contains("duplicate")), violations)
        assertEquals(Check.exitCode(CheckOutcome.Failed(violations)), 1)
      case other => fail(s"expected a failure, got $other")
  }

  test("a sample whose point is not in the point table fails the check") {
    val dir = built()
    seed(dir, "orphan.parquet", "SELECT * REPLACE ('ima-sc:ghost' AS point_key)")
    Check.run(dir) match
      case CheckOutcome.Failed(violations) =>
        assert(violations.exists(_.contains("no point row")), violations)
      case other => fail(s"expected a failure, got $other")
  }

  test("a partition whose columns are not the schema's fails the check") {
    val dir = built()
    seed(dir, "wrong-schema.parquet", "SELECT * EXCLUDE (tide)")
    Check.run(dir) match
      case CheckOutcome.Failed(violations) =>
        assert(violations.exists(_.contains("schema")), violations)
      case other => fail(s"expected a failure, got $other")
  }

  test("a partition file the manifest does not know fails the check") {
    val dir = built()
    seed(dir, "stray.parquet", "SELECT *")
    Check.run(dir) match
      case CheckOutcome.Failed(violations) =>
        assert(violations.exists(_.contains("manifest")), violations)
      case other => fail(s"expected a failure, got $other")
  }
