package marola.model

/**
 * MIP-0008 §4/§5.6: `Coordinates.fromMapsUrl` reads a Google Maps pin out of the URL shapes Maps
 * hands out — the viewport `/@lat,lon,zoom`, the `q=`/`query=`/`ll=` query forms, and the
 * `!3dlat!4dlon` pin inside a place URL.
 */
class CoordinatesSpec extends munit.FunSuite:

  private val campeche = Coordinates(-27.6733, -48.47)

  test("viewport form: /maps/@lat,lon,zoomz") {
    assertEquals(
      Coordinates.fromMapsUrl("https://www.google.com/maps/@-27.6733,-48.47,15z"),
      Some(campeche)
    )
  }

  test("query form: ?q=lat,lon (also query= and ll=, and a URL-encoded comma)") {
    assertEquals(
      Coordinates.fromMapsUrl("https://maps.google.com/?q=-27.6733,-48.47"),
      Some(campeche)
    )
    assertEquals(
      Coordinates.fromMapsUrl("https://www.google.com/maps/search/?api=1&query=-27.6733%2C-48.47"),
      Some(campeche)
    )
    assertEquals(
      Coordinates.fromMapsUrl("https://maps.google.com/maps?ll=-27.6733,-48.47&z=15"),
      Some(campeche)
    )
  }

  test("place form: the !3dlat!4dlon pin wins over the viewport centre") {
    val url =
      "https://www.google.com/maps/place/Praia+do+Campeche/@-27.66,-48.45,13z/data=!3m1!4b1!4m6!3m5!1s0x9527:0x1!8m2!3d-27.6733!4d-48.47"
    assertEquals(Coordinates.fromMapsUrl(url), Some(campeche))
  }

  test("garbage, a non-Maps URL, and out-of-range numbers give None") {
    assertEquals(Coordinates.fromMapsUrl(""), None)
    assertEquals(Coordinates.fromMapsUrl("not a url at all"), None)
    assertEquals(Coordinates.fromMapsUrl("https://example.com/@1,2"), None)
    assertEquals(Coordinates.fromMapsUrl("https://www.google.com/maps/@-97.0,-48.47,15z"), None)
    assertEquals(Coordinates.fromMapsUrl("https://www.google.com/maps/@-27.67,-200.0,15z"), None)
    assertEquals(Coordinates.fromMapsUrl("https://www.google.com/maps/place/Campeche"), None)
  }

  test("a short link is not parsed here — the caller expands it first") {
    assertEquals(Coordinates.fromMapsUrl("https://maps.app.goo.gl/abc123"), None)
  }
