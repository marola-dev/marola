package marola.location

import marola.model.Coordinates

class IpGeolocationSpec extends munit.FunSuite:

  // Real answers three providers gave for one Florianópolis IP while building IpGeolocation, plus
  // São Paulo as the classic "ISP head office" outlier Brazilian IP databases produce.
  private val floripaCentro = Coordinates(-27.5967, -48.5492)
  private val floripaCentroB = Coordinates(-27.5969, -48.5468)
  private val floripaLagoa = Coordinates(-27.6168, -48.4997)
  private val saoPaulo = Coordinates(-23.5505, -46.6333)

  test("Coordinates.distanceKm is zero for the same point, symmetric, and roughly right") {
    assertEquals(floripaCentro.distanceKm(floripaCentro), 0.0)
    assertEqualsDouble(
      floripaCentro.distanceKm(saoPaulo),
      saoPaulo.distanceKm(floripaCentro),
      1e-9
    )
    val km = floripaCentro.distanceKm(saoPaulo) // ~490km by great circle
    assert(km > 470 && km < 510, s"unexpected Florianópolis-São Paulo distance: $km")
  }

  test("consensus picks the cluster and outvotes a single far-away provider") {
    val result = IpGeolocation.consensus(
      List(
        ("a", floripaCentro, Some("Florianópolis")),
        ("b", saoPaulo, Some("São Paulo")),
        ("c", floripaCentroB, Some("Florianópolis"))
      )
    )
    val loc = result.getOrElse(fail("expected a location"))
    assert(loc.coordinates.distanceKm(floripaCentro) < 1.0, s"picked ${loc.coordinates}")
    assertEquals(loc.agreeingSources.toSet, Set("a", "c"))
    assertEquals(loc.totalSources, 3)
    assertEquals(loc.city, Some("Florianópolis"))
  }

  test("consensus treats providers a few km apart in the same city as agreeing") {
    val loc = IpGeolocation
      .consensus(
        List(
          ("a", floripaCentro, None),
          ("b", floripaLagoa, Some("Florianópolis")),
          ("c", floripaCentroB, None)
        )
      )
      .getOrElse(fail("expected a location"))
    assertEquals(loc.agreeingSources.size, 3)
    // The medoid had no city of its own; it borrows one from an agreeing provider.
    assertEquals(loc.city, Some("Florianópolis"))
  }

  test("consensus with a single answer returns it, flagged 1/1") {
    val loc = IpGeolocation
      .consensus(List(("only", saoPaulo, Some("São Paulo"))))
      .getOrElse(fail("expected a location"))
    assertEquals(loc.coordinates, saoPaulo)
    assertEquals(loc.agreeingSources, List("only"))
    assertEquals(loc.totalSources, 1)
  }

  test("consensus with no answers is None") {
    assertEquals(IpGeolocation.consensus(Nil), None)
  }

end IpGeolocationSpec
