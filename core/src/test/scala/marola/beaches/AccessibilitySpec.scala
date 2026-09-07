package marola.beaches

import java.net.http.HttpRequest

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.model.{Beach, Coordinates}

/**
 * MIP-0021 §7: replays the real Overpass response recorded live on 2026-09-07
 * (`core/src/test/resources/fixtures/overpass-facilities-campeche.json`) for the six beaches
 * `bestPerBeachTomorrow`'s defaults find near Campeche — same regression shape as
 * `cli.PipelineGoldenSpec`. That live query returned only two elements: one `amenity=parking` near
 * Praia do Campeche, one `emergency=lifeguard` near Praia do Rio Tavares — sparser than MIP-0021
 * §4's 40-beach/20km survey, and itself the proof of the absence rule this MIP exists to enforce:
 * four of the six nearest beaches get `Facilities.NoData`, truthfully.
 */
class AccessibilitySpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  // The six nearest beaches to Campeche (-27.6733,-48.4700), as `BeachFinder.nearby` returns them
  // with the pipeline's real defaults (radiusKm=15, limit=6) — measured live alongside the fixture
  // above, not re-derived from it.
  private val campeche = Beach("Praia do Campeche", Coordinates(-27.6859814, -48.4858158), 2.101)
  private val rioTavares =
    Beach("Praia do Rio Tavares", Coordinates(-27.6544005, -48.4677042), 2.114)
  private val joaquina = Beach("Praia da Joaquina", Coordinates(-27.6344661, -48.4532013), 4.624)
  private val morroDasPedras =
    Beach("Praia do Morro das Pedras", Coordinates(-27.7107038, -48.4994956), 5.073)
  private val gravata = Beach("Praia do Gravatá", Coordinates(-27.6134207, -48.4336057), 7.562)
  private val armacao = Beach("Praia da Armação", Coordinates(-27.7360218, -48.5066382), 7.852)
  private val sixNearest =
    List(campeche, rioTavares, joaquina, morroDasPedras, gravata, armacao)

  private def readFixture(name: String): String =
    val stream = getClass.getClassLoader.getResourceAsStream(s"fixtures/$name")
    if stream == null then throw new IllegalArgumentException(s"missing fixture $name")
    try scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    finally stream.close()

  /** Answers every request with `body`, whatever the URL; counts requests. */
  final class Fixed(body: String, status: Int = 200) extends Http.Transport:
    var sent: Int = 0
    def send(request: HttpRequest): Http.Response =
      sent += 1
      Http.Response(status, body)

  test(
    "golden: the real fixture — Campeche gets its parking, Rio Tavares its lifeguard post, the " +
      "other four beaches get NoData"
  ) {
    val transport = Fixed(readFixture("overpass-facilities-campeche.json"))
    val client = OverpassAccessibilityClient()
    val result = Http.withTransport(transport) {
      Sync.Unsafe.evalOrThrow(client.near(sixNearest))
    }
    assertEquals(transport.sent, 1, "must be exactly one Overpass call for the whole short list")
    assertEquals(result.keySet, sixNearest.map(_.name).toSet)
    assertEquals(result("Praia do Campeche"), Facilities(Map(Facility.Parking -> 1)))
    assertEquals(result("Praia do Rio Tavares"), Facilities(Map(Facility.Lifeguard -> 1)))
    List(joaquina, morroDasPedras, gravata, armacao).foreach { b =>
      assertEquals(result(b.name), Facilities.NoData, b.name)
    }
  }

  test("no beaches, no query, no call") {
    val transport = Fixed("""{"elements":[]}""")
    val result = Http.withTransport(transport) {
      Sync.Unsafe.evalOrThrow(OverpassAccessibilityClient().near(Nil))
    }
    assertEquals(result, Map.empty[String, Facilities])
    assertEquals(transport.sent, 0)
  }

  test("query: one `around` union pair per beach, the six facility tags MIP-0021 §4 measured") {
    val q = OverpassAccessibilityClient.query(List(campeche, rioTavares), radiusM = 300)
    assert(q.contains(s"(around:300,${campeche.coordinates.lat},${campeche.coordinates.lon})"), q)
    assert(
      q.contains(s"(around:300,${rioTavares.coordinates.lat},${rioTavares.coordinates.lon})"),
      q
    )
    assert(q.contains("""["amenity"~"^(parking|shower|toilets|lifeguard)$"]"""), q)
    assert(q.contains("""["emergency"~"^(lifeguard|lifeguard_base)$"]"""), q)
    assertEquals(q.linesIterator.count(_.trim.startsWith("nwr")), 4) // 2 beaches * 2 clauses each
  }

  test("attribute: a real place mapped as both a node and a way counts once, not twice") {
    // Synthetic — not from the live fixture — built only to exercise the dedup rule MIP-0021 §5
    // calls out: a node and a way for the same real parking lot have *different* OSM ids (separate
    // id spaces), so only coordinate-rounding dedup (not an id-based one) catches this case.
    val duplicated = JsonValue
      .parse("""
      {"elements": [
        {"type": "node", "id": 111, "lat": -27.6869608, "lon": -48.4852148, "tags": {"amenity": "parking"}},
        {"type": "way", "id": 222, "center": {"lat": -27.6869609, "lon": -48.4852149}, "tags": {"amenity": "parking"}}
      ]}
    """)("elements")
      .arr
    val result = OverpassAccessibilityClient.attribute(duplicated, List(campeche), radiusM = 300)
    assertEquals(result("Praia do Campeche"), Facilities(Map(Facility.Parking -> 1)))
  }

  test("attribute: two distinct amenities of the same kind, far enough apart, both count") {
    val distinct = JsonValue
      .parse("""
      {"elements": [
        {"type": "node", "id": 111, "lat": -27.6869814, "lon": -48.4858158, "tags": {"amenity": "toilets"}},
        {"type": "node", "id": 222, "lat": -27.6849814, "lon": -48.4838158, "tags": {"amenity": "toilets"}}
      ]}
    """)("elements")
      .arr
    val result = OverpassAccessibilityClient.attribute(distinct, List(campeche), radiusM = 300)
    assertEquals(result("Praia do Campeche"), Facilities(Map(Facility.Toilets -> 2)))
  }

  test("attribute: an amenity beyond radiusM is not attributed to the nearest beach") {
    // ~1.1km from Campeche, well outside the default 300m radius.
    val far = JsonValue
      .parse("""
      {"elements": [
        {"type": "node", "id": 222, "lat": -27.696, "lon": -48.486, "tags": {"amenity": "parking"}}
      ]}
    """)("elements")
      .arr
    val result = OverpassAccessibilityClient.attribute(far, List(campeche), radiusM = 300)
    assertEquals(result("Praia do Campeche"), Facilities.NoData)
  }

  test(
    "an Overpass error is not swallowed here — Recommender is the layer that maps it to NoData"
  ) {
    val transport = Fixed("Overpass Gateway Timeout", status = 504)
    intercept[Http.HttpError] {
      Http.withTransport(transport) {
        Sync.Unsafe.evalOrThrow(OverpassAccessibilityClient().near(sixNearest))
      }
    }
  }

  test("NoopAccessibilityClient: NoData for every beach, no network call") {
    val result = Sync.Unsafe.evalOrThrow(NoopAccessibilityClient().near(sixNearest))
    assertEquals(result, sixNearest.map(_.name -> Facilities.NoData).toMap)
  }

end AccessibilitySpec
