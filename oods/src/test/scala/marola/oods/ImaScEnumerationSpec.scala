package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

import scala.collection.mutable.ListBuffer

import kyo.*

import marola.json.JsonValue
import marola.oods.Channel

/**
 * The three enumeration endpoints and the points feed, replayed from the 2026-09-14 captures
 * (MIP-0056 §4.1, §7). `Portal` records what was asked for, so a test can assert that a filtered
 * plan does not walk the whole state.
 */
class ImaScEnumerationSpec extends munit.FunSuite:

  private def fixture(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"ima-sc/$name")
    try String(stream.readAllBytes(), UTF_8)
    finally stream.close()

  private given AllowUnsafe = AllowUnsafe.embrace.danger
  private def run[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  final private class Portal(beaches: String => List[String] = Portal.florianopolisOnly):
    val calls: ListBuffer[(String, Map[String, String])] = ListBuffer.empty
    private val urls = ImaScAdapter.DefaultSource.urls

    def post(url: String, form: Map[String, String]): String < Sync = Sync.defer {
      val _ = calls.append((url, form))
      if url == urls("years") then fixture("anos.json")
      else if url == urls("municipalities") then fixture("municipios.json")
      else if url == urls("beaches") then codes(beaches(form("municipioID")))
      else if url == urls("points") then fixture("points.json")
      else fixture("campeche-2025.csv")
    }

  private object Portal:
    /**
     * Trap: the portal lists a different set per municipality, so a stub answering all 28 with one
     * list would make every beach look shared and collapse the plan onto the first municipality.
     */
    val florianopolisOnly: String => List[String] = m =>
      if m == "Florianópolis" then ImaScAdapter.parseCodes(fixture("locais-florianopolis.json"))
      else Nil

  private def codes(names: List[String]): String =
    names.map(n => s"""{"CODIGO":${JsonValue.str(n).render}}""").mkString("[", ",", "]")

  private def adapterWith(portal: Portal): ImaScAdapter =
    ImaScAdapter(post = portal.post, now = () => Instant.parse("2026-09-14T12:00:00Z"))

  private def plan(cities: Set[String], from: Int = 2003, to: Int = 2026): Plan =
    Plan(
      sources = Set("ima-sc"),
      states = Set.empty,
      cities = cities,
      mode = Mode.Backfill,
      fromYear = from,
      toYear = to,
      dryRun = false,
      concurrency = 1
    )

  test("the portal enumerates 24 years, 28 municipalities and 43 Florianópolis beaches") {
    val adapter = adapterWith(Portal())
    assertEquals(run(adapter.years).size, 24)
    assertEquals(run(adapter.years).min, 2003)
    assertEquals(run(adapter.years).max, 2026)
    assertEquals(run(adapter.municipalities).size, 28)
    assert(run(adapter.municipalities).contains("Florianópolis"))
    assertEquals(run(adapter.beaches("Florianópolis")).size, 43)
  }

  test("a plan for Florianópolis yields 43 beaches × 24 years and nothing else") {
    val portal = Portal()
    val partitions = run(adapterWith(portal).partitions(plan(Set("Florianópolis"))))
    assertEquals(partitions.size, 43 * 24)
    assertEquals(partitions.distinct.size, 43 * 24)
    assertEquals(partitions.map(_.key).distinct.size, 43)
    assert(partitions.forall(_.key.startsWith("florianopolis/")))
    assert(partitions.forall(_.channel == Channel.Csv))
    assert(partitions.forall(_.sourceId == "ima-sc"))
    assertEquals(partitions.map(_.year).distinct.sorted, (2003 to 2026).toList)
    // Every municipality, even under `--city`: which municipality owns a shared beach's partition
    // is a property of the whole state's lists, and a filtered run must pick the same one.
    assertEquals(portal.calls.count((url, _) => url.endsWith("getLocaisByMunicipio")), 28)
  }

  test("beaches whose names differ only by an article or a doubled space keep distinct keys") {
    val keys = run(adapterWith(Portal()).partitions(plan(Set("Florianópolis")))).map(_.key).distinct
    assertEquals(keys.count(_.startsWith("florianopolis/armacao-do-pantano-do-sul")), 2)
    assertEquals(keys.count(_.startsWith("florianopolis/cachoeira-do-bom-jesus")), 2)
    assertEquals(keys.count(_.startsWith("florianopolis/pantano-do-sul")), 2)
  }

  test("the year window filters the plan, and a source it does not serve costs no request") {
    val portal = Portal()
    val adapter = adapterWith(portal)
    assertEquals(run(adapter.partitions(plan(Set("Florianópolis"), 2025, 2026))).size, 43 * 2)
    assertEquals(
      run(adapter.partitions(plan(Set("Florianópolis")).copy(sources = Set("inea-rj")))),
      Nil
    )
    assertEquals(run(adapter.partitions(plan(Set("Florianópolis")).copy(states = Set("RJ")))), Nil)
    assert(portal.calls.forall((_, form) => !form.contains("ano")))
  }

  test("fetch posts the portal's own three form fields and keeps the response bytes") {
    val portal = Portal()
    val adapter = adapterWith(portal)
    val partition =
      Partition("ima-sc", Channel.Csv, "florianopolis/campeche", 2025, immutable = false)
    val raw = run(adapter.fetch(partition))
    assertEquals(
      portal.calls.last,
      (
        ImaScAdapter.DefaultSource.urls("export_csv"),
        Map("municipioID" -> "Florianópolis", "localID" -> "Praia do Campeche", "ano" -> "2025")
      )
    )
    assertEquals(String(raw.bytes, UTF_8), fixture("campeche-2025.csv"))
    assertEquals(raw.fetchedAt, Instant.parse("2026-09-14T12:00:00Z"))
  }

  test("the points feed becomes 260 rows with feed coordinates and an IBGE code") {
    val points = run(adapterWith(Portal()).points)
    assertEquals(points.size, 260)
    assert(points.forall(_.geoSource == GeoSource.Feed))
    assert(points.forall(p => p.state == "SC" && p.country == "BR"))
    assert(points.forall(_.ibgeCode.isDefined))
    val campeche =
      points.filter(p => p.beachName == "Praia do Campeche" && p.pointName == "Ponto 35")
    assertEquals(campeche.map(_.pointKey), List("58fbe2a9-d9ce-466f-94c3-5dbd46416ead"))
    assertEquals(campeche.head.lat, Some(-27.6898635))
    assertEquals(campeche.head.ibgeCode, Some("4205407"))
  }

  test("rows joins against the registry, and says so rather than guessing when it is not loaded") {
    val adapter = adapterWith(Portal())
    val partition =
      Partition("ima-sc", Channel.Csv, "florianopolis/campeche", 2025, immutable = false)
    val raw = run(adapter.fetch(partition))
    adapter.rows(raw) match
      case Left(ParseError(path, _)) =>
        assertEquals(path, "raw/ima-sc/csv/florianopolis/campeche/2025.csv")
      case other => fail(s"expected the registry to be missing, got $other")
    val _ = run(adapter.points)
    assertEquals(adapter.rows(raw).map(_.size), Right(145))
  }

  private val twoMunicipalities: String => List[String] =
    case "Florianópolis" => List("Praia Brava", "Praia do Campeche")
    case "Itajaí"        => List("Praia Brava", "Praia de Cabeçudas")
    case _               => Nil

  test("a beach name two municipalities list is one partition, under the first of them") {
    val keys =
      run(adapterWith(Portal(twoMunicipalities)).partitions(plan(Set.empty))).map(_.key).distinct
    assertEquals(keys, List("florianopolis/brava", "florianopolis/campeche", "itajai/cabecudas"))
  }

  test("the shared beach keeps one partition per year, and only the first municipality's path") {
    val partitions = run(adapterWith(Portal(twoMunicipalities)).partitions(plan(Set.empty)))
    val brava = partitions.filter(_.key.endsWith("/brava"))
    assertEquals(brava.size, 24)
    assertEquals(brava.map(_.year).sorted, (2003 to 2026).toList)
    assertEquals(
      ImaScAdapter.rawPath(brava.head),
      s"raw/ima-sc/csv/florianopolis/brava/${brava.head.year}.csv"
    )
  }

  test("--city keeps a shared beach the other municipality owns, under that owner's key") {
    val itajai = run(adapterWith(Portal(twoMunicipalities)).partitions(plan(Set("Itajaí"))))
    assertEquals(itajai.map(_.key).distinct, List("florianopolis/brava", "itajai/cabecudas"))
  }

  test("a shared beach is fetched under the municipality its key names") {
    val portal = Portal(twoMunicipalities)
    val partition = Partition("ima-sc", Channel.Csv, "florianopolis/brava", 2025, immutable = false)
    val _ = run(adapterWith(portal).fetch(partition))
    assertEquals(
      portal.calls.last._2,
      Map("municipioID" -> "Florianópolis", "localID" -> "Praia Brava", "ano" -> "2025")
    )
  }

  test("two beaches whose names differ but normalise alike stay two partitions") {
    val nearly: String => List[String] =
      case "Florianópolis"        => List("Praia do Itaguaçu")
      case "São Francisco do Sul" => List("Praia de Itaguaçú")
      case _                      => Nil
    val keys = run(adapterWith(Portal(nearly)).partitions(plan(Set.empty))).map(_.key).distinct
    assertEquals(keys, List("florianopolis/itaguacu", "sao-francisco-do-sul/itaguacu"))
  }
