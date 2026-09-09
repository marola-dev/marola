package marola.beaches

import java.nio.file.{Files, Path}

import kyo.*

import marola.http.Http
import marola.model.{Beach, Coordinates}

/**
 * The failure this exists for: `HttpConnectTimeoutException` from overpass-api.de fails the whole
 * site build, so no board updates at all — water, tides or otherwise. OSM beaches change on the
 * order of years and the site rebuilds every three hours.
 */
class BeachSnapshotSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private val campeche = Coordinates(-27.6733, -48.47)
  private val beaches = List(
    Beach("Praia do Campeche", Coordinates(-27.6733, -48.47), 0.0),
    Beach("Praia da Joaquina", Coordinates(-27.628, -48.446), 5.3)
  )

  private def tmpDir(): Path = Files.createTempDirectory("marola-beaches")

  test("a snapshot round-trips every field the pipeline uses") {
    val back = BeachSnapshot.decode(BeachSnapshot.encode(beaches))
    assertEquals(back.map(_.name), beaches.map(_.name))
    assertEquals(back.head.coordinates, campeche)
    assertEquals(back(1).distanceKm, 5.3)
  }

  test("the key separates different queries — radius and limit cannot collide") {
    val a = BeachSnapshot.key(campeche, 15.0, 6)
    assertNotEquals(a, BeachSnapshot.key(campeche, 30.0, 6))
    assertNotEquals(a, BeachSnapshot.key(campeche, 15.0, 12))
    assertNotEquals(a, BeachSnapshot.key(Coordinates(-22.97, -43.18), 15.0, 6))
    assertEquals(a, BeachSnapshot.key(campeche, 15.0, 6), "and is stable for the same query")
  }

  test("the key is a safe file name — no path separators from negative coordinates") {
    val k = BeachSnapshot.key(campeche, 15.0, 6)
    // Dots are fine in a file name; separators and traversal are not, and negative coordinates
    // are why the minus signs become `m`.
    assert(!k.contains("/") && !k.contains("\\") && !k.contains(".."), k)
    assert(!k.contains("-"), s"a negative coordinate must not leak a dash into the name: $k")
  }

  test("a missing file reads as no snapshot, not an error") {
    assertEquals(BeachSnapshot.read(tmpDir().resolve("absent.json")), Nil)
  }

  test("a corrupt snapshot falls through to Overpass rather than failing the build") {
    val f = tmpDir().resolve("broken.json")
    Files.writeString(f, "{ not json")
    assertEquals(BeachSnapshot.read(f), Nil)
  }

  test("a snapshot is used instead of Overpass — the transport is never called") {
    val dir = tmpDir()
    BeachSnapshot.write(BeachSnapshot.fileFor(dir, campeche, 15.0, 6), beaches)
    var called = 0
    val exploding = new Http.Transport:
      def send(request: java.net.http.HttpRequest): Http.Response =
        called += 1
        throw new java.net.http.HttpConnectTimeoutException("Overpass is down")

    val got = Http.withTransport(exploding) {
      Sync.Unsafe.evalOrThrow(BeachFinder.nearby(campeche, 15.0, 6, snapshots = Some(dir)))
    }
    assertEquals(called, 0, "a present snapshot must not touch the network")
    assertEquals(got.map(_.name), beaches.map(_.name))
  }

  test("with no snapshot the transport is used, and a good result is written back") {
    val dir = tmpDir()
    val body =
      """{"elements":[{"tags":{"name":"Praia do Campeche"},"center":{"lat":-27.6733,"lon":-48.47}}]}"""
    val ok = new Http.Transport:
      def send(request: java.net.http.HttpRequest): Http.Response = Http.Response(200, body)
    val got = Http.withTransport(ok) {
      Sync.Unsafe.evalOrThrow(BeachFinder.nearby(campeche, 15.0, 6, snapshots = Some(dir)))
    }
    assertEquals(got.map(_.name), List("Praia do Campeche"))
    assertEquals(
      BeachSnapshot.read(BeachSnapshot.fileFor(dir, campeche, 15.0, 6)).map(_.name),
      List("Praia do Campeche"),
      "the fetch should have been written back for the next build"
    )
  }

end BeachSnapshotSpec
