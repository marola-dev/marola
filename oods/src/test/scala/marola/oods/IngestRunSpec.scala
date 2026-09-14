package marola.oods

import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import kyo.*

import marola.http.Http
import marola.json.JsonValue

/**
 * The runner against a scripted portal (MIP-0056 §7): idempotency, the retry budget, the 429 abort
 * and byte-verbatim writes. Nothing here touches the network.
 */
class IngestRunSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger
  private def eval[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  private def fixture(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"ima-sc/$name")
    try String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  /** Florianópolis's real beaches, `take`n so a run is two or three partitions, not forty-three. */
  private def beachCodes(count: Int): List[String] =
    ImaScAdapter.parseCodes(fixture("locais-florianopolis.json")).take(count)

  private def beachesBody(count: Int): String =
    beachCodes(count).map(c => s"""{"CODIGO":${JsonValue.str(c).render}}""").mkString("[", ",", "]")

  /** `script`: statuses the next fetches of one `localID` get before the CSV is served. */
  final private class Portal(script: Map[String, List[Int]] = Map.empty, beaches: Int = 2):
    val exports: ListBuffer[Map[String, String]] = ListBuffer.empty
    private val urls = ImaScAdapter.DefaultSource.urls
    private val remaining = mutable.Map.from(script)

    def post(url: String, form: Map[String, String]): String < Sync = Sync.defer {
      if url == urls("years") then fixture("anos.json")
      else if url == urls("municipalities") then fixture("municipios.json")
      // Only Florianópolis lists beaches: one list for all 28 municipalities would read as 28
      // municipalities sharing every beach name, and the plan would key them by the first.
      else if url == urls("beaches") then
        if form("municipioID") == "Florianópolis" then beachesBody(beaches) else "[]"
      else if url == urls("points") then fixture("points.json")
      else
        val _ = exports.append(form)
        remaining.get(form("localID")) match
          case Some(status :: rest) =>
            val _ = remaining.update(form("localID"), rest)
            throw Http.HttpError(status, url, "scripted failure")
          case _ => fixture(s"campeche-${form("ano")}.csv")
    }

  final private class Pauses:
    val sleeps: ListBuffer[Long] = ListBuffer.empty
    val sleep: Long => Unit = ms =>
      val _ = sleeps.append(ms)

  private val today = LocalDate.parse("2026-09-14")

  private def adapterOf(portal: Portal): ImaScAdapter =
    ImaScAdapter(post = portal.post, now = () => Instant.parse("2026-09-14T12:00:00Z"))

  private def planFor(cities: Set[String] = Set("Florianópolis"), dryRun: Boolean = false): Plan =
    Plan(
      sources = Set("ima-sc"),
      states = Set.empty,
      cities = cities,
      mode = Mode.Incremental,
      fromYear = 2003,
      toYear = 2026,
      dryRun = dryRun,
      concurrency = 1
    )

  private def ingest(
      dataDir: Path,
      portal: Portal,
      pauses: Pauses,
      plan: Plan = planFor(),
      now: Instant = Instant.parse("2026-09-14T12:00:00Z")
  ): Either[IngestError, Outcome] =
    eval(Ingest.run(adapterOf(portal), plan, dataDir, today, () => now, pauses.sleep))

  private def tempDir(): Path = Files.createTempDirectory("oods-ingest")

  private def manifestBytes(dataDir: Path): String =
    Files.readString(dataDir.resolve("manifest/ima-sc.json"), UTF_8)

  private def outcomeOf(result: Either[IngestError, Outcome]): Outcome = result match
    case Right(outcome) => outcome
    case Left(error)    => fail(s"expected a run, got $error")

  private def rawFile(dataDir: Path, p: Partition): Path =
    dataDir.resolve(ImaScAdapter.rawPath(p))

  test("a first run writes the bytes verbatim, BOM intact, one manifest entry per partition") {
    val dir = tempDir()
    val portal = Portal()
    val pauses = Pauses()
    val outcome = outcomeOf(ingest(dir, portal, pauses))
    assertEquals(outcome.planned.size, 2)
    assertEquals(outcome.fetched, 2)
    assertEquals(outcome.written, 2)
    assertEquals(outcome.unchanged, 0)
    assertEquals(outcome.failed, Nil)
    assertEquals(outcome.aborted, None)
    assertEquals(Ingest.exitCode(Right(outcome)), 0)

    val onDisk = Files.readAllBytes(rawFile(dir, outcome.planned.head))
    assertEquals(String(onDisk, UTF_8), fixture("campeche-2026.csv"))
    assert(String(onDisk, UTF_8).startsWith("﻿"), "the export's BOM must survive the write")

    val manifest = Manifest.read(dir.resolve("manifest/ima-sc.json"))
    assertEquals(manifest.raw.size, 3)
    assert(manifest.raw.contains("raw/ima-sc/points.json"))
    assertEquals(
      manifest.raw(ImaScAdapter.rawPath(outcome.planned.head)).bytes,
      onDisk.length.toLong
    )

    val points = Files.readString(dir.resolve("raw/ima-sc/points.json"), UTF_8)
    val keys = points.linesIterator.toList
      .filter(_.startsWith("{"))
      .flatMap(l => JsonValue.parse(l.stripSuffix(","))("point_key").str)
    assertEquals(keys, keys.sorted)
    assertEquals(pauses.sleeps.count(_ == Ingest.PauseMs), 2)
  }

  test(
    "a second run over identical responses writes nothing and leaves the manifest byte-identical"
  ) {
    val dir = tempDir()
    val _ = outcomeOf(ingest(dir, Portal(), Pauses()))
    val before = manifestBytes(dir)
    val second = outcomeOf(ingest(dir, Portal(), Pauses()))
    assertEquals(second.written, 0)
    assertEquals(second.unchanged, 2)
    assertEquals(second.fetched, 2)
    assertEquals(manifestBytes(dir), before)
  }

  test("a 500 then a 200 succeeds, the second attempt recorded") {
    val dir = tempDir()
    val portal = Portal(Map(beachCodes(2).head -> List(500)))
    val pauses = Pauses()
    val outcome = outcomeOf(ingest(dir, portal, pauses))
    assertEquals(portal.exports.size, 3)
    assertEquals(outcome.written, 2)
    assertEquals(outcome.failed, Nil)
    assert(
      pauses.sleeps.contains(Ingest.BackoffMs),
      s"expected a backoff pause, got ${pauses.sleeps}"
    )
  }

  test("three 500s record the partition as failed, write the others, and exit non-zero") {
    val dir = tempDir()
    val portal = Portal(Map(beachCodes(2).head -> List(500, 500, 500)))
    val result = ingest(dir, portal, Pauses())
    val outcome = outcomeOf(result)
    assertEquals(portal.exports.size, 4)
    assertEquals(outcome.failed.map(_.partition.key), List(outcome.planned.head.key))
    assertEquals(outcome.written, 1)
    assertEquals(Ingest.exitCode(result), 1)
    assert(Files.exists(rawFile(dir, outcome.planned(1))), "the healthy partition is still written")
    assert(Manifest.read(dir.resolve("manifest/ima-sc.json")).raw.size == 2)
  }

  test("a mid-run 429 keeps what was already written and stops before the next request") {
    val dir = tempDir()
    val portal = Portal(Map(beachCodes(3)(1) -> List(429)), beaches = 3)
    val result = ingest(dir, portal, Pauses())
    val outcome = outcomeOf(result)
    assertEquals(outcome.planned.size, 3)
    assertEquals(portal.exports.size, 2, "the third partition was never requested")
    assertEquals(outcome.written, 1)
    assert(outcome.aborted.isDefined, "the run reports the abort")
    assertEquals(Ingest.exitCode(result), 1)

    val first = rawFile(dir, outcome.planned.head)
    assertEquals(Files.readString(first, UTF_8), fixture("campeche-2026.csv"))
    assert(!Files.exists(rawFile(dir, outcome.planned(2))), "the untouched partition has no file")
    val manifest = Manifest.read(dir.resolve("manifest/ima-sc.json"))
    assert(manifest.raw.contains(ImaScAdapter.rawPath(outcome.planned.head)))
    assertEquals(manifest.raw.size, 2)
  }

  test("a 429 aborts the run before the next request") {
    val dir = tempDir()
    val portal = Portal(Map(beachCodes(2).head -> List(429)))
    val result = ingest(dir, portal, Pauses())
    val outcome = outcomeOf(result)
    assertEquals(portal.exports.size, 1)
    assertEquals(
      outcome.aborted,
      Some(IngestError.AbortedByStatus(429, ImaScAdapter.DefaultSource.urls("export_csv")))
    )
    assertEquals(Ingest.exitCode(result), 1)
    assert(Files.exists(dir.resolve("manifest/ima-sc.json")), "what was fetched is still recorded")
  }

  test("--dry-run plans without a fetch and without a write") {
    val dir = tempDir()
    val portal = Portal()
    val outcome = outcomeOf(ingest(dir, portal, Pauses(), planFor(dryRun = true)))
    assertEquals(outcome.planned.size, 2)
    assertEquals(outcome.written, 0)
    assertEquals(portal.exports.toList, Nil)
    assert(!Files.exists(dir.resolve("raw")), "a dry run writes nothing")
    assert(!Files.exists(dir.resolve("manifest")), "a dry run writes nothing")
  }

  test("a city no adapter covers fails the run") {
    val dir = tempDir()
    assertEquals(
      ingest(dir, Portal(), Pauses(), planFor(cities = Set("Macapá"))),
      Left(IngestError.UnknownCity(Set("Macapá")))
    )
  }

  test("the live post carries the OODS User-Agent") {
    val seen = ListBuffer.empty[String]
    val recorder = new Http.Transport:
      def send(request: HttpRequest): Http.Response =
        val _ = seen.append(request.headers().firstValue("User-Agent").orElse(""))
        Http.Response(200, fixture("anos.json"))
    val _ = Http.withTransport(recorder)(eval(ImaScAdapter().years))
    assertEquals(seen.toList, List(Ingest.UserAgent))
  }

  test("a raw file restored from the dataset keeps its manifest entry, fetched_at and all") {
    val dir = tempDir()
    val _ = outcomeOf(ingest(dir, Portal(), Pauses()))
    val before = manifestBytes(dir)
    val partition =
      Manifest.read(dir.resolve("manifest/ima-sc.json")).raw.keys.find(_.endsWith(".csv")).get
    Files.delete(dir.resolve(partition))
    // A day later, the same bytes: only the raw file was missing (a pull that never ran), so
    // rewriting 3,331 fetched_at values would be a diff for nothing.
    val outcome =
      outcomeOf(ingest(dir, Portal(), Pauses(), now = Instant.parse("2026-09-15T12:00:00Z")))
    assertEquals(outcome.written, 1)
    assertEquals(manifestBytes(dir), before)
  }
