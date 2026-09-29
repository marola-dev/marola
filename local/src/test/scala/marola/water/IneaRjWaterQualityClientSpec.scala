package marola.water

import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate

import kyo.*

import marola.http.Http
import marola.model.Coordinates

/** From the real saved city pages and bulletin PDFs to `List[SamplingPoint]` (#488). */
class IneaRjWaterQualityClientSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  private def resource(name: String): Array[Byte] =
    val stream = getClass.getClassLoader.getResourceAsStream(name)
    try stream.readAllBytes()
    finally stream.close()

  private val RioPage = "https://www.inea.rj.gov.br/rio-de-janeiro/"
  private val NiteroiPage = "https://www.inea.rj.gov.br/niteroi/"
  private val Uploads = "https://www.inea.rj.gov.br/wp-content/uploads/"
  private val SulBulletin = Uploads + "2026/09/Zona-sudoeste-e-Zona-sul-21-09-26.pdf"
  private val NiteroiBulletin = Uploads + "2026/09/Niteroi-24-09-26.pdf"
  private val Today = LocalDate.of(2026, 9, 29)

  private val rioHtml = String(resource("inea-page-rio-de-janeiro-2026-09-29.html"), UTF_8)
  private val niteroiHtml = String(resource("inea-page-niteroi-2026-09-29.html"), UTF_8)
  private val june = resource("inea-boletim-zona-sudoeste-sul-2026-06-17.pdf")
  private val september = resource("inea-boletim-zona-sudoeste-sul-2026-09-21.pdf")
  private val niteroi = resource("inea-boletim-niteroi-2026-09-24.pdf")

  private class Pages(pages: Map[String, String]) extends Http.Transport:
    def send(request: HttpRequest): Http.Response =
      pages.get(request.uri.toString).fold(Http.Response(404, ""))(Http.Response(200, _))

  private class Pdfs(pdfs: Map[String, Array[Byte]]) extends Http.BinaryTransport:
    var requested: List[String] = Nil
    def send(request: HttpRequest): Http.BytesResponse =
      requested = requested :+ request.uri.toString
      pdfs
        .get(request.uri.toString)
        .fold(Http.BytesResponse(404, Array.emptyByteArray))(Http.BytesResponse(200, _))

  private def fetch(
      pages: Map[String, String],
      pdfs: Pdfs,
      cityPages: List[String] = List(RioPage),
      today: LocalDate = Today
  ): List[SamplingPoint] =
    val client = IneaRjWaterQualityClient(cityPages, () => today)
    Http.withTransport(Pages(pages)) {
      Http.withBinaryTransport(pdfs)(Sync.Unsafe.evalOrThrow(client.samplingPoints))
    }

  test("the Rio page yields one dated bulletin per zone, and no historico or Qualificação PDF") {
    assertEquals(
      IneaRjWaterQualityClient.bulletins(rioHtml).map(b => (b.url.stripPrefix(Uploads), b.date)),
      List(
        "2026/09/Zona-sudoeste-e-Zona-sul-21-09-26.pdf" -> LocalDate.of(2026, 9, 21),
        "2024/11/Sepetiba-28-11-24.pdf" -> LocalDate.of(2024, 11, 28),
        "2026/09/Ilha-do-Governador-e-Ramos-14-09-26.pdf" -> LocalDate.of(2026, 9, 14),
        "2026/09/Paqueta-21-09-26.pdf" -> LocalDate.of(2026, 9, 21)
      )
    )
  }

  test("Niterói's bulletin is dated by its filename, not by its header's \"2025\"") {
    assertEquals(
      IneaRjWaterQualityClient.bulletins(niteroiHtml),
      List(IneaRjWaterQualityClient.Bulletin(NiteroiBulletin, LocalDate.of(2026, 9, 24)))
    )
  }

  test("selects the dated bulletin per zone from the city page and stamps its filename date") {
    // Ilha and Paquetá 404 here: each costs only its own points.
    val points = fetch(Map(RioPage -> rioHtml), Pdfs(Map(SulBulletin -> september)))
    assertEquals(points.size, 29, "39 rows, 29 of them with curated coordinates")
    assertEquals(
      points.flatMap(_.samples.map(_.sampledOn)).distinct,
      List(LocalDate.of(2026, 9, 21))
    )
  }

  test("Niterói's 24-09-26 bulletin yields all 29 points, each with its own verdict") {
    val points = fetch(
      Map(NiteroiPage -> niteroiHtml),
      Pdfs(Map(NiteroiBulletin -> niteroi)),
      cityPages = List(NiteroiPage)
    )
    assertEquals(points.size, 29)
    assertEquals(
      points.flatMap(_.samples.map(_.sampledOn)).distinct,
      List(LocalDate.of(2026, 9, 24))
    )
    val verdict = points.map(p => p.id -> p.samples.head.condition).toMap
    assertEquals(verdict("GR000"), BathingCondition.Proper)
    assertEquals(verdict("IC000"), BathingCondition.Improper)
    assertEquals(verdict("PR000"), BathingCondition.Proper)
    assertEquals(verdict("CH001"), BathingCondition.Improper)
    assertEquals(points.find(_.id == "CH001").map(_.beachName), Some("Charitas"))
  }

  test("the 45-day rule keeps a 21 Sep bulletin through 5 Nov and drops it on 6 Nov") {
    val w = WaterQuality(
      IneaRjWaterQualityClient.toSamplingPoints(september, LocalDate.of(2026, 9, 21)),
      "INEA/RJ"
    )
    assertEquals(w.fresh(LocalDate.of(2026, 11, 5)).size, 29)
    assertEquals(w.fresh(LocalDate.of(2026, 11, 6)), Nil)
  }

  test("Sepetiba's 28-11-24 bulletin is never downloaded, by the 45-day rule alone") {
    val pdfs = Pdfs(Map(SulBulletin -> september))
    val _ = fetch(Map(RioPage -> rioHtml), pdfs)
    assertEquals(
      pdfs.requested.map(_.stripPrefix(Uploads)),
      List(
        "2026/09/Zona-sudoeste-e-Zona-sul-21-09-26.pdf",
        "2026/09/Ilha-do-Governador-e-Ramos-14-09-26.pdf",
        "2026/09/Paqueta-21-09-26.pdf"
      )
    )
  }

  test("a zone INEA resumes is downloaded again: the skip is by date, not by name") {
    val pdfs = Pdfs(Map.empty)
    val _ = fetch(Map(RioPage -> rioHtml), pdfs, today = LocalDate.of(2024, 12, 1))
    assert(pdfs.requested.exists(_.endsWith("Sepetiba-28-11-24.pdf")), pdfs.requested)
  }

  test("a city page that cannot be fetched gives no points, never a pinned fallback PDF") {
    val pdfs = Pdfs(Map(SulBulletin -> september))
    assertEquals(fetch(Map.empty, pdfs), Nil)
    assertEquals(pdfs.requested, Nil)
  }

  test("a city page with no dated bulletin link gives no points") {
    val undated = """<a href="https://x/barra_e_zona_sul_historico_2019-6.pdf">2019</a>"""
    assertEquals(fetch(Map(RioPage -> undated), Pdfs(Map(SulBulletin -> september))), Nil)
  }

  test("a point absent from the curated coordinate table is dropped, not defaulted") {
    val points = IneaRjWaterQualityClient.toSamplingPoints(june, LocalDate.of(2026, 6, 17))
    assert(points.exists(_.id == "BD05"), "BD05 is curated")
    assertEquals(points.size, 29, "the June fixture parses 39 rows; 10 have no curated coordinate")
  }

  test("coversOrigin: Rio de Janeiro yes, Salvador no") {
    assert(IneaRjWaterQualityClient.coversOrigin(Coordinates(-22.9878, -43.1913)))
    assert(!IneaRjWaterQualityClient.coversOrigin(Coordinates(-12.9777, -38.5016)))
  }
