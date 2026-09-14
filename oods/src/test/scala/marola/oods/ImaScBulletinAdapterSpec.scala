package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.time.{Instant, LocalDate}

import kyo.*

import marola.oods.Channel
import marola.water.WaterQualityMatcher

/**
 * The bulletin channel against the captured portal (MIP-0056 §7): which dates each mode plans,
 * where a bulletin is fetched from, what it parses to, and how a row finds its point.
 */
class ImaScBulletinAdapterSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger
  private def eval[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  private def fixture(name: String): Array[Byte] =
    val stream = getClass.getClassLoader.getResourceAsStream(s"ima-sc/$name")
    try stream.readAllBytes()
    finally stream.close()

  /** `local`'s own fixture, read by path: the 2026 bulletin stays one file, not two copies. */
  private val bulletin2026 =
    Files.readAllBytes(Paths.get("local/src/test/resources/ima-boletim-sc-2026-08-28.pdf"))

  private val source = ImaScAdapter.DefaultSource

  private def served(url: String): Array[Byte] =
    if url == source.urls("index") then fixture("index-2026-09-14.html")
    else if url == ImaScBulletinAdapter.CdxUrl then fixture("wayback-cdx-downloadPDF.json")
    else if url.contains("2023-03-10") then fixture("ima-boletim-sc-2023-03-10.pdf")
    else bulletin2026

  private def adapter(
      get: String => Array[Byte] = served,
      feed: => String = String(fixture("points.json"), UTF_8)
  ): ImaScBulletinAdapter =
    ImaScBulletinAdapter(
      get = url => Sync.defer(get(url)),
      registry = ImaScAdapter(post = (_, _) => Sync.defer(feed)),
      now = () => Instant.parse("2026-09-14T12:00:00Z")
    )

  private def feedRow(code: String, municipality: String, beach: String, point: String): String =
    s"""{"CODIGO":"$code","MUNICIPIO":"$municipality","BALNEARIO":"$beach",
        |"PONTO_NOME":"$point","LATITUDE":"-27.0","LONGITUDE":"-48.0"}""".stripMargin

  private def planFor(mode: Mode): Plan =
    Plan(
      sources = Set("ima-sc"),
      states = Set.empty,
      cities = Set.empty,
      mode = mode,
      fromYear = 2003,
      toYear = 2026,
      dryRun = false,
      concurrency = 1
    )

  private def planned(mode: Mode, a: ImaScBulletinAdapter): List[Partition] =
    eval(a.partitions(planFor(mode)))

  private def rowsOf(a: ImaScBulletinAdapter, key: String): List[SampleRow] =
    val _ = eval(a.points)
    val partition = Partition(source.id, Channel.Pdf, key, key.take(4).toInt, immutable = true)
    val raw = eval(a.fetch(partition))
    a.rows(raw) match
      case Right(rows)  => rows
      case Left(reason) => fail(s"$key did not parse: $reason")

  private val uuid = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$".r

  test("the portal index plans one fetch-once partition per linked bulletin") {
    val partitions = planned(Mode.Incremental, adapter())
    assertEquals(partitions.size, 10)
    assertEquals(partitions.map(_.key).head, "2026-07-07")
    assertEquals(partitions.map(_.key).last, "2026-09-10")
    assert(partitions.forall(_.channel == Channel.Pdf), partitions.take(2).toString)
    assert(partitions.forall(_.immutable), "a dated bulletin never changes")
    assert(partitions.forall(_.year == 2026), partitions.map(_.year).distinct.toString)
  }

  test("a backfill adds every dated bulletin the Wayback index knows") {
    val partitions = planned(Mode.Backfill, adapter())
    // 189 dated captures (the CDX answer also holds an undated `/downloadPDF` and one `…-09No`),
    // and none of them overlaps the ten the portal still links.
    val cdx = String(fixture("wayback-cdx-downloadPDF.json"), UTF_8)
    assertEquals(ImaScBulletinAdapter.archived(cdx).size, 189)
    assertEquals(partitions.size, 199)
    assertEquals(partitions.map(_.key).head, "2023-03-10")
    assertEquals(partitions.map(_.year).distinct.sorted, List(2023, 2024, 2025, 2026))
  }

  test("an archived bulletin is fetched from the Wayback copy, an index one from the portal") {
    val a = adapter()
    val backfill = planned(Mode.Backfill, a).map(p => p.key -> p).toMap
    val archived = eval(a.fetch(backfill("2023-03-10")))
    assert(archived.url.startsWith("https://web.archive.org/web/"), archived.url)
    assert(
      archived.url.endsWith(
        "id_/https://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/2023-03-10"
      ),
      archived.url
    )
    val live = eval(a.fetch(backfill("2026-09-10")))
    assertEquals(live.url, s"${source.urls("bulletin")}/2026-09-10")
  }

  test("every point the 2026 bulletin reports becomes one sample of the pdf channel") {
    val rows = rowsOf(adapter(), "2026-08-28")
    assertEquals(rows.size, 259)
    assert(rows.forall(_.channel == Channel.Pdf), "the channel is the bulletin's")
    assert(rows.forall(_.indicator == Indicator.Unknown), "the bulletin prints no count")
    assert(rows.forall(_.indicatorValue.isEmpty), "the bulletin prints no count")
    assert(rows.forall(_.sampledAt.isEmpty), "the bulletin prints no collection time")
    assert(
      rows.forall(_.bulletinDate.contains(LocalDate.parse("2026-08-28"))),
      "every row carries the bulletin it came from"
    )
  }

  test("the oldest archived bulletin has the same layout and parses too") {
    val rows = rowsOf(adapter(), "2023-03-10")
    assertEquals(rows.size, 225)
    // 37 of its beaches are named or numbered differently in today's feed — 2023's "PRAIA DO
    // BALN. CAMBORIÚ" is the feed's "Praia Central" — and keep a slug key with no coordinates.
    assertEquals(rows.count(r => uuid.matches(r.pointKey)), 188)
    assert(rows.forall(!_.pointKey.contains("pagina")), "no page footer may reach a point key")
    assert(
      rows.forall(_.bulletinDate.contains(LocalDate.parse("2023-03-10"))),
      "every row carries the bulletin it came from"
    )
  }

  test("a bulletin row the feed lists keeps the portal's UUID; the rest fall back to a slug") {
    val rows = rowsOf(adapter(), "2026-08-28")
    val (keyed, fallback) = rows.partition(r => uuid.matches(r.pointKey))
    assertEquals(keyed.size, 255)
    // Four beaches the bulletin spells differently from the feed, and nothing else.
    assertEquals(
      fallback.map(_.pointKey).sorted,
      List(
        "ima-sc:arroio-da-praia-das-gaivotas/ponto-02",
        "ima-sc:itapoa/ponto-05",
        "ima-sc:lagoa-boca-da-barra-foz-do-canal-do-linguado/ponto-02",
        "ima-sc:saudade/ponto-06"
      )
    )
  }

  test("a heading the parser glued a page footer onto still reaches the feed's own point") {
    val rows = rowsOf(adapter(), "2026-08-28")
    val picarras = ImaScAdapter
      .parsePoints(source, String(fixture("points.json"), UTF_8))
      .find(p =>
        WaterQualityMatcher.normalise(p.beachName) == "picarras" && p.pointName == "Ponto 01"
      )
      .getOrElse(fail("no Praia de Piçarras Ponto 01 in the feed"))
    assertEquals(rows.count(_.pointKey == picarras.pointKey), 1)
    assert(rows.forall(!_.pointKey.contains("pagina")), "no page footer may reach a point key")
  }

  test("a page footer glued onto a heading keys to the same point as the clean heading") {
    assertEquals(
      ImaScBulletinAdapter.normBeach("Página: 3 de 18 PRAIA DE PIÇARRAS"),
      ImaScBulletinAdapter.normBeach("PRAIA DE PIÇARRAS")
    )
    assertEquals(
      ImaScBulletinAdapter.withoutPageFooter("Pagina: 12 de 18  LAGOA DA CONCEIÇÃO"),
      "LAGOA DA CONCEIÇÃO"
    )
    assertEquals(
      ImaScBulletinAdapter.withoutPageFooter("PRAIA DE PIÇARRAS"),
      "PRAIA DE PIÇARRAS",
      "a clean heading must survive untouched"
    )
  }

  /** What makes (beach, point) a safe key at all — checked against the feed, not assumed. */
  test("the committed points feed has one point per (beach, point) pair") {
    val feed = ImaScAdapter.parsePoints(source, String(fixture("points.json"), UTF_8))
    assertEquals(feed.size, 260)
    val pairs = feed.map(p =>
      (
        WaterQualityMatcher.normalise(p.beachName),
        WaterQualityMatcher.normalise(p.pointName)
      )
    )
    assertEquals(pairs.distinct.size, 260)
  }

  test("two feed points sharing a (beach, point) pair are refused, not silently resolved") {
    val clashing =
      List(
        feedRow("aaaaaaaa-0000-0000-0000-000000000001", "Itajaí", "Praia Brava", "Ponto 01"),
        feedRow("bbbbbbbb-0000-0000-0000-000000000002", "Florianópolis", "Brava", "Ponto 01")
      ).mkString("[", ",", "]")
    val a = adapter(feed = clashing)
    val _ = eval(a.points)
    val partition = Partition(source.id, Channel.Pdf, "2026-08-28", 2026, immutable = true)
    val raw = eval(a.fetch(partition))
    a.rows(raw) match
      case Left(ParseError(_, detail)) =>
        assert(detail.contains("(brava, ponto 01)"), detail)
        assert(detail.contains("aaaaaaaa-0000-0000-0000-000000000001"), detail)
        assert(detail.contains("bbbbbbbb-0000-0000-0000-000000000002"), detail)
      case other => fail(s"expected a ParseError, got $other")
  }

  test("a bulletin parsed before the registry loaded is a ParseError, not a slug-keyed store") {
    val a = adapter()
    val partition = Partition(source.id, Channel.Pdf, "2026-08-28", 2026, immutable = true)
    val raw = eval(a.fetch(partition))
    a.rows(raw) match
      case Left(ParseError(path, detail)) =>
        assertEquals(path, "raw/ima-sc/bulletins/2026-08-28.jsonl")
        assert(detail.contains("points registry not loaded"), detail)
      case other => fail(s"expected a ParseError, got $other")
  }

  test("a response that is not a bulletin is a ParseError, never an empty jsonl") {
    val a = adapter(_ => "<html>404</html>".getBytes(UTF_8))
    val _ = eval(a.points)
    val partition = Partition(source.id, Channel.Pdf, "2026-09-11", 2026, immutable = true)
    val raw = eval(a.fetch(partition))
    a.rows(raw) match
      case Left(ParseError(_, detail)) =>
        assert(detail.contains("unreadable bulletin"), detail)
      case other => fail(s"expected a ParseError, got $other")
  }

  test("what lands on disk is the parsed rows, sorted, and never the PDF") {
    val a = adapter()
    val rows = rowsOf(a, "2026-08-28")
    val partition = Partition(source.id, Channel.Pdf, "2026-08-28", 2026, immutable = true)
    val raw = eval(a.fetch(partition))
    val lines = String(a.storedBytes(raw, rows), UTF_8).linesIterator.toList
    assertEquals(lines.size, rows.size)
    assertEquals(lines.map(keyOf), lines.map(keyOf).sorted)
    assert(lines.head.startsWith("{\"source_id\":\"ima-sc\""), lines.head.take(80))
    assert(lines.head.contains("\"channel\":\"pdf\""), lines.head)
    assert(lines.head.contains("\"bulletin_date\":\"2026-08-28\""), lines.head)
  }

  test("the raw path of a bulletin is its date under bulletins/, not a year of a beach") {
    val partition = Partition(source.id, Channel.Pdf, "2026-08-28", 2026, immutable = true)
    assertEquals(adapter().rawPath(partition), "raw/ima-sc/bulletins/2026-08-28.jsonl")
  }

  private def keyOf(line: String): String =
    line.split("\"point_key\":\"", -1).lift(1).map(_.takeWhile(_ != '"')).getOrElse("")
end ImaScBulletinAdapterSpec
