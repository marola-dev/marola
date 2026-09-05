package marola

import java.net.http.HttpRequest
import java.time.{LocalDate, ZoneId}

import scala.collection.mutable.ListBuffer

import kyo.*

import marola.http.Http
import marola.model.Coordinates
import marola.water.{ImaScWaterQualityClient, WaterQualityMatcher}

/**
 * Serves recorded real responses by URL — the cheap regression mechanism: the whole pipeline runs
 * exactly as in production (same query strings, same parsers, same scoring) against fixtures
 * captured on 2026-09-05, with no network. Re-record a fixture (`docs/RUN-LOCALLY.md` §7) when an
 * upstream format changes; the diff in the fixture *is* the regression report.
 */
final class ReplayTransport(val routes: List[(String => Boolean, String)]) extends Http.Transport:
  val requests: ListBuffer[String] = ListBuffer.empty
  def send(request: HttpRequest): Http.Response =
    val url = request.uri().toString
    requests += url
    routes
      .collectFirst { case (matches, body) if matches(url) => Http.Response(200, body) }
      .getOrElse(Http.Response(404, s"no fixture for $url"))

object Fixtures:
  def read(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"fixtures/$name")
    if stream == null then throw new IllegalArgumentException(s"missing fixture $name")
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  /** Campeche, Florianópolis: Overpass (26 beaches), Open-Meteo for two beaches, IMA/SC points. */
  def campeche(): ReplayTransport =
    val joaquina = "latitude=-27\\.6[23]".r
    ReplayTransport(
      List(
        ((u: String) => u.startsWith("https://overpass-api.de"), read("overpass-campeche.json")),
        (
          (u: String) =>
            u.startsWith("https://api.open-meteo.com") && joaquina.findFirstIn(u).isDefined,
          read("weather-joaquina.json")
        ),
        ((u: String) => u.startsWith("https://api.open-meteo.com"), read("weather-campeche.json")),
        (
          (u: String) =>
            u.startsWith("https://marine-api.open-meteo.com") && joaquina.findFirstIn(u).isDefined,
          read("marine-joaquina.json")
        ),
        (
          (u: String) => u.startsWith("https://marine-api.open-meteo.com"),
          read("marine-campeche.json")
        ),
        (
          (u: String) => u.startsWith("https://balneabilidade.ima.sc.gov.br"),
          read("ima-mapa-sample.json")
        )
      )
    )

class PipelineGoldenSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private val origin = Coordinates(-27.6733, -48.4700)
  // The fixtures' forecasts cover 2026-09-05/06 and the IMA samples are dated 25/08/2026.
  private val fixedToday: ZoneId => LocalDate = _ => LocalDate.of(2026, 9, 5)

  private def run(transport: ReplayTransport) =
    Http.withTransport(transport) {
      Sync.Unsafe.evalOrThrow(
        Recommender.bestPerBeachTomorrow(
          origin,
          radiusKm = 15.0,
          waterQuality = Some(ImaScWaterQualityClient()),
          today = fixedToday
        )
      )
    }

  test("golden: the six nearest beaches, ranked best-first, every hour on 'tomorrow'") {
    val transport = Fixtures.campeche()
    val results = run(transport)
    assertEquals(
      results.map(_.beach.name).toSet,
      Set(
        "Praia do Campeche",
        "Praia do Rio Tavares",
        "Praia da Joaquina",
        "Praia do Morro das Pedras",
        "Praia do Gravatá",
        "Praia da Armação"
      )
    )
    assertEquals(results, results.sortBy(-_.score))
    results.foreach { r =>
      assert(r.hour.time.toLocalDate.isEqual(LocalDate.of(2026, 9, 6)), r.hour.time.toString)
      assert(r.score >= 0 && r.score <= 100)
    }
  }

  test("golden: call volume — one Overpass, one IMA, two Open-Meteo per beach, nothing else") {
    val transport = Fixtures.campeche()
    val results = run(transport)
    val byHost =
      transport.requests.groupBy(u => java.net.URI.create(u).getHost).view.mapValues(_.size).toMap
    assertEquals(byHost("overpass-api.de"), 1)
    assertEquals(byHost("balneabilidade.ima.sc.gov.br"), 1)
    assertEquals(byHost("api.open-meteo.com"), results.size)
    assertEquals(byHost("marine-api.open-meteo.com"), results.size)
    assertEquals(byHost.keySet.size, 4)
  }

  test("golden: Campeche gets its five IMA points, the Riozinho point is named, and it costs 20") {
    val results = run(Fixtures.campeche())
    val campeche = results.find(_.beach.name == "Praia do Campeche").getOrElse(fail("no Campeche"))
    val wq = campeche.waterQuality.getOrElse(fail("Campeche has no water quality"))
    assertEquals(
      wq.points.map(_.pointName).toSet,
      Set("Ponto 35", "Ponto 73", "Ponto 75", "Ponto 89", "Ponto 90")
    )
    assert(
      campeche.notes.exists(n => n.contains("avoid") && n.contains("Riozinho")),
      campeche.notes.toString
    )
    assert(Report.waterSummary(campeche).startsWith("4/5 PRÓPRIA"), Report.waterSummary(campeche))
    // Rio Tavares is served the same forecast fixture and has no IMA point → its score is the base.
    val rioTavares =
      results.find(_.beach.name == "Praia do Rio Tavares").getOrElse(fail("no Rio Tavares"))
    assertEquals(rioTavares.waterQuality, None)
    assertEquals(campeche.score, rioTavares.score - 20)
  }

  test("golden: no inland-water point (Lagoa da Conceição) is attached to any sea beach") {
    val results = run(Fixtures.campeche())
    results.flatMap(_.waterQuality).flatMap(_.points).foreach { p =>
      assert(!WaterQualityMatcher.isInlandWater(p.beachName), s"${p.beachName} ${p.pointName}")
    }
  }

  test("golden: tide turns and the detailed block render from the recorded sea-level series") {
    val results = run(Fixtures.campeche())
    val top = results.head
    assert(top.dayTides.nonEmpty, "no tide events")
    assert(top.dayTides.exists(_.isHigh) && top.dayTides.exists(!_.isHigh))
    val block = Report.detail(top)
    List("Top pick —", "Water quality", "Tide", "high ", "low ", "Sea", "waves", "Whales").foreach(
      s => assert(block.contains(s), s"detail block missing '$s':\n$block")
    )
    val line = Report.line(1, top)
    assert(line.contains("water:"), line)
  }

  test("golden: an IMA outage degrades to 'no data' for every beach, never a failure") {
    val broken = ReplayTransport(
      Fixtures.campeche().routes.filterNot {
        case (m, _) => m("https://balneabilidade.ima.sc.gov.br/x")
      }
    )
    val results = run(broken)
    assert(results.nonEmpty)
    assert(results.forall(_.waterQuality.isEmpty))
    results.foreach(r => assertEquals(Report.waterSummary(r), "no data"))
  }

  test("golden: a stale IMA feed (today far after the samples) is reported stale, not scored") {
    val results = Http.withTransport(Fixtures.campeche()) {
      Sync.Unsafe.evalOrThrow(
        Recommender.bestPerBeachTomorrow(
          origin,
          waterQuality = Some(ImaScWaterQualityClient()),
          today = _ => LocalDate.of(2026, 9, 5) // forecast day must still be 2026-09-06 …
        )
      )
    }
    // … so staleness is checked directly on the verdict with a later "today":
    val campeche = results.find(_.beach.name == "Praia do Campeche").get
    val verdict =
      marola.scoring.Swimability.waterVerdict(campeche.waterQuality, LocalDate.of(2026, 11, 1))
    assert(
      !verdict.veto && verdict.delta == 0 && verdict.summary.startsWith("stale"),
      verdict.toString
    )
  }

end PipelineGoldenSpec
