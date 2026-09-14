package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.sql.{Connection, ResultSet}
import java.time.{Instant, LocalDate}

import scala.jdk.CollectionConverters.*

import marola.water.BathingCondition

/**
 * `build.sql` over task 2's fixtures (MIP-0056 §7): the value rules, the channel precedence, the
 * views, and the two properties the store's size depends on — a rebuild of unchanged input rewrites
 * nothing, and an edited year rewrites only that year.
 */
class OodsBuildSpec extends munit.FunSuite:

  private def fixture(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"ima-sc/$name")
    try String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  private val years = List(2003, 2010, 2025, 2026)

  private val source = ImaScAdapter.DefaultSource

  private lazy val registry: List[PointRow] =
    ImaScAdapter.parsePoints(source, fixture("points.json"))

  /** A point the feed does not know: task 2 keys it by slug, so the build must invent its row. */
  private val inventedRow =
    """Florianópolis,"Praia do Campeche","Ponto 999","Inventado",10/01/2024,09:00,Sul,""" +
      "Vazante,Forte,21,25,2400,IMPRÓPRIA"

  private val inventedKey = "ima-sc:florianopolis/campeche/ponto-999"

  private def csvFor(year: Int): String =
    if year != 2024 then fixture(s"campeche-$year.csv")
    else s"${fixture("campeche-2025.csv").linesIterator.next()}\n$inventedRow\n"

  private def rawPath(year: Int): String =
    s"raw/ima-sc/csv/florianopolis/campeche/$year.csv"

  private def write(dir: Path, relative: String, text: String): Unit =
    val file = dir.resolve(relative)
    val _ = Files.createDirectories(file.getParent)
    val _ = Files.write(file, text.getBytes(UTF_8))

  /**
   * A store as task 3's runner leaves it: raw CSVs, `points.json`, a manifest with `fetched_at`.
   */
  private def store(): Path =
    val dir = Files.createTempDirectory("oods-build")
    (years :+ 2024).foreach(year => write(dir, rawPath(year), csvFor(year)))
    val _ = Files.write(dir.resolve("raw/ima-sc/points.json"), RawStore.renderPoints(registry))
    Manifest.write(
      dir.resolve("manifest/ima-sc.json"),
      Manifest(
        raw = (years :+ 2024).map { year =>
          rawPath(year) -> RawEntry(
            source.urls("export_csv"),
            "sha",
            csvFor(year).length.toLong,
            Instant.parse(s"$year-01-01T00:00:00Z"),
            0
          )
        }.toMap,
        partitions = Map.empty
      )
    )
    dir

  private def built(dir: Path): BuildReport = Build.run(dir) match
    case BuildOutcome.Built(report)   => report
    case BuildOutcome.Failed(reasons) => fail(s"the build failed: ${reasons.mkString("; ")}")

  private def parsed(year: Int): List[SampleRow] =
    ImaScCsv.parse(source, rawPath(year), csvFor(year), registry) match
      case Right(rows) => rows
      case Left(error) => fail(s"fixture $year did not parse: $error")

  private def allParsed: List[SampleRow] = (years :+ 2024).flatMap(parsed)

  private def connect(dir: Path): Connection =
    val connection = Duck.connect()
    Duck.exec(connection, s"SET VARIABLE data_dir = '${dir.toAbsolutePath}'")
    Duck.exec(connection, Duck.script("views.sql"))
    connection

  private def one[A](dir: Path, sql: String)(read: ResultSet => A): A =
    val connection = connect(dir)
    try
      val statement = connection.createStatement()
      try
        val rs = statement.executeQuery(sql)
        assert(rs.next(), s"no rows for $sql")
        read(rs)
      finally statement.close()
    finally connection.close()

  private def hashes(dir: Path): Map[String, String] =
    val parquet = dir.resolve("parquet")
    if !Files.isDirectory(parquet) then Map.empty
    else
      val stream = Files.walk(parquet)
      try
        stream
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .map(f => parquet.relativize(f).toString -> RawStore.sha256(Files.readAllBytes(f)))
          .toMap
      finally stream.close()

  test("every parsed fixture row reaches the sample partitions") {
    val dir = store()
    val _ = built(dir)
    val rows = one(dir, "SELECT count(*) FROM br_bathing_water")(_.getLong(1))
    assertEquals(rows, allParsed.size.toLong)
  }

  test("the sample primary key is unique across the store") {
    val dir = store()
    val _ = built(dir)
    val duplicates = one(
      dir,
      """SELECT count(*) FROM (
         SELECT source_id, point_key, sampled_on, sampled_at, channel
         FROM br_bathing_water GROUP BY ALL HAVING count(*) > 1)"""
    )(_.getLong(1))
    assertEquals(duplicates, 0L)
  }

  test("a censored count keeps its number and its qualifier") {
    val dir = store()
    val _ = built(dir)
    val (value, qualifier) = one(
      dir,
      """SELECT indicator_value, indicator_qualifier FROM br_bathing_water
         WHERE sampled_on = DATE '2003-12-15' AND point_name = 'Ponto 35'"""
    )(rs => (rs.getInt(1), rs.getString(2)))
    assertEquals(value, 20)
    assertEquals(qualifier, "below")
  }

  test("a point the feed never listed is kept with geo_source none") {
    val dir = store()
    val _ = built(dir)
    val (key, geo) = one(
      dir,
      "SELECT point_key, geo_source FROM br_bathing_water WHERE point_name = 'Ponto 999'"
    )(rs => (rs.getString(1), rs.getString(2)))
    assertEquals(key, inventedKey)
    assertEquals(geo, "none")
  }

  test("br_bathing_water keeps the csv row when a pdf row shares its key") {
    val dir = store()
    val _ = built(dir)
    seedPdfRow(dir)
    val (rows, channel) = one(
      dir,
      """SELECT count(*), any_value(channel) FROM br_bathing_water
         WHERE sampled_on = DATE '2025-12-29' AND point_name = 'Ponto 35'"""
    )(rs => (rs.getLong(1), rs.getString(2)))
    assertEquals(rows, 1L)
    assertEquals(channel, "csv")
  }

  /** The pdf channel is task 7's; one row of it, written by hand, is all the view needs here. */
  private def seedPdfRow(dir: Path): Unit =
    val connection = Duck.connect()
    try
      val file = dir.resolve("parquet/ima-sc/samples/year=2025/pdf-seed.parquet")
      Duck.exec(
        connection,
        s"""COPY (SELECT * REPLACE ('pdf' AS channel)
              FROM read_parquet('${dir.toAbsolutePath}/parquet/ima-sc/samples/year=2025/samples.parquet')
              WHERE sampled_on = DATE '2025-12-29' LIMIT 1)
            TO '$file' (FORMAT PARQUET)"""
      )
    finally connection.close()

  /** A bulletin as task 7's runner leaves it: the rows it parsed, never the PDF. */
  private def bulletin(dir: Path, date: String, rows: List[SampleRow]): Unit =
    val file = dir.resolve(s"raw/ima-sc/bulletins/$date.jsonl")
    val _ = Files.createDirectories(file.getParent)
    val _ = Files.write(file, RawStore.renderSamples(rows))

  private def pdfRow(key: String, on: String, from: String): SampleRow =
    SampleRow(
      sourceId = "ima-sc",
      pointKey = key,
      sampledOn = LocalDate.parse(on),
      sampledAt = None,
      condition = BathingCondition.Improper,
      indicator = Indicator.Unknown,
      indicatorValue = None,
      qualifier = Qualifier.Exact,
      rain = None,
      wind = None,
      tide = None,
      waterTempC = None,
      airTempC = None,
      channel = Channel.Pdf,
      bulletinDate = Some(LocalDate.parse(from))
    )

  private def csvSample: SampleRow =
    parsed(2025)
      .find(_.sampledOn.isEqual(LocalDate.parse("2025-12-29")))
      .getOrElse(fail("the 2025 fixture has no 2025-12-29 sample"))

  test("the csv row wins where a bulletin repeats a day the export already carries") {
    val dir = store()
    val covered = csvSample
    bulletin(dir, "2026-01-07", List(pdfRow(covered.pointKey, "2025-12-29", "2026-01-07")))
    val _ = built(dir)
    val (rows, channel, condition) = one(
      dir,
      s"""SELECT count(*), any_value(channel), any_value(condition) FROM br_bathing_water
          WHERE point_key = '${covered.pointKey}' AND sampled_on = DATE '2025-12-29'"""
    )(rs => (rs.getLong(1), rs.getString(2), rs.getString(3)))
    assertEquals(rows, 1L)
    assertEquals(channel, "csv")
    assertEquals(condition, covered.condition.label)
  }

  test("a day only the bulletin saw survives, with its indicator unknown and its bulletin date") {
    val dir = store()
    val covered = csvSample
    bulletin(dir, "2026-01-07", List(pdfRow(covered.pointKey, "2026-01-05", "2026-01-07")))
    val _ = built(dir)
    val (channel, indicator, from) = one(
      dir,
      s"""SELECT channel, indicator, bulletin_date FROM br_bathing_water
          WHERE point_key = '${covered.pointKey}' AND sampled_on = DATE '2026-01-05'"""
    )(rs => (rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate))
    assertEquals(channel, "pdf")
    assertEquals(indicator, "unknown")
    assertEquals(from, LocalDate.parse("2026-01-07"))
  }

  test("a point only a bulletin names still gets a point row, so no sample is dropped") {
    val dir = store()
    val key = "ima-sc:praia-inventada/ponto-77"
    bulletin(dir, "2026-01-07", List(pdfRow(key, "2026-01-05", "2026-01-07")))
    val _ = built(dir)
    val (beach, point, geo) = one(
      dir,
      s"SELECT beach_name, point_name, geo_source FROM br_bathing_water WHERE point_key = '$key'"
    )(rs => (rs.getString(1), rs.getString(2), rs.getString(3)))
    assertEquals(beach, "PRAIA INVENTADA")
    assertEquals(point, "PONTO 77")
    assertEquals(geo, "none")
  }

  /**
   * A bulletin repeats a point it could not resample, so the same (point, day) arrives from several
   * weeks — once in the store, or the sample primary key has duplicates.
   */
  test("a sample two bulletins both report is stored once, under the first that published it") {
    val dir = store()
    val key = csvSample.pointKey
    bulletin(dir, "2026-01-07", List(pdfRow(key, "2026-01-05", "2026-01-07")))
    bulletin(dir, "2026-01-14", List(pdfRow(key, "2026-01-05", "2026-01-14")))
    val _ = built(dir)
    val (rows, from) = one(
      dir,
      s"""SELECT count(*), any_value(bulletin_date) FROM br_bathing_water
          WHERE point_key = '$key' AND sampled_on = DATE '2026-01-05'"""
    )(rs => (rs.getLong(1), rs.getDate(2).toLocalDate))
    assertEquals(rows, 1L)
    assertEquals(from, LocalDate.parse("2026-01-07"))
  }

  test("point_stats counts the improper-after-rain samples of a point") {
    val dir = store()
    val _ = built(dir)
    val key = allParsed.head.pointKey
    val mine = allParsed.filter(_.pointKey == key)
    val rained = mine.filter(_.rain.exists(_ != "Ausente"))
    val improperAfterRain = rained.count(_.condition == BathingCondition.Improper)
    assert(rained.nonEmpty, "the chosen point has no sample taken after rain")
    val (n, afterRain, share) = one(
      dir,
      s"""SELECT n, n_after_rain, share_impropria_after_rain
          FROM point_stats WHERE point_key = '$key'"""
    )(rs => (rs.getLong(1), rs.getLong(2), rs.getDouble(3)))
    assertEquals(n, mine.size.toLong)
    assertEquals(afterRain, rained.size.toLong)
    assertEqualsDouble(share, improperAfterRain.toDouble / rained.size, 1e-9)
  }

  test("latest_per_point holds exactly the newest sample of each point") {
    val dir = store()
    val _ = built(dir)
    val expected = allParsed.groupBy(_.pointKey).size
    val (points, newest) = one(
      dir,
      s"""SELECT count(*), max(sampled_on) FROM latest_per_point"""
    )(rs => (rs.getLong(1), rs.getDate(2).toLocalDate))
    assertEquals(points, expected.toLong)
    assertEquals(newest, allParsed.map(_.sampledOn).max)
  }

  test("a second build over unchanged input rewrites nothing") {
    val dir = store()
    val first = built(dir)
    val before = hashes(dir)
    val second = built(dir)
    assertEquals(second.written, Nil)
    assert(first.written.nonEmpty, "the first build wrote nothing")
    assertEquals(hashes(dir), before)
  }

  test("an edited year rewrites exactly that year's partition") {
    val dir = store()
    val _ = built(dir)
    val before = hashes(dir)
    val edited = csvFor(2025).linesIterator.toList
    val extra = edited(1).replace("29/12/2025", "02/01/2025")
    write(dir, rawPath(2025), (edited :+ extra).mkString("", "\n", "\n"))
    val report = built(dir)
    assertEquals(report.written, List("parquet/ima-sc/samples/year=2025/samples.parquet"))
    val after = hashes(dir)
    assertEquals(after.keySet, before.keySet)
    assertEquals(
      after.filter((path, hash) => before(path) != hash).keySet,
      Set("ima-sc/samples/year=2025/samples.parquet")
    )
  }

  test("a raw CSV whose header is not the portal's fails the build, writing nothing") {
    val dir = store()
    write(dir, rawPath(2022), "a,b,c\n1,2,3\n")
    Build.run(dir) match
      case BuildOutcome.Failed(reasons) =>
        assert(reasons.exists(_.contains("2022.csv")), reasons)
        assert(!Files.exists(dir.resolve("parquet")), "a failed build must write no partition")
      case other => fail(s"expected a failure, got $other")
  }

  test("raw CSVs that yield no sample at all fail the build") {
    val dir = store()
    (years :+ 2024).foreach(year => write(dir, rawPath(year), fixture("header-only.csv")))
    Build.run(dir) match
      case BuildOutcome.Failed(reasons) =>
        assert(reasons.exists(_.contains("no samples")), reasons)
      case other => fail(s"expected a failure, got $other")
  }
