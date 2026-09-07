package marola.sightings

import java.nio.file.{Files, Path}
import java.time.Instant

import kyo.*

/**
 * The default `SightingStore` — append-only JSONL on disk, no account and no network. Every test
 * writes to its own temp file, so these are real round-trips through the real file format rather
 * than a mock of it.
 */
class LocalFileSightingStoreSpec extends munit.FunSuite:

  private given AllowUnsafe = AllowUnsafe.embrace.danger
  private def run[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempFile("sightings", ".jsonl"),
    teardown = p => { Files.deleteIfExists(p); () }
  )

  private def sighting(beach: String, kind: SightingKind, at: String, note: Option[String] = None) =
    Sighting(beach, kind, note, Instant.parse(at))

  tmp.test("a recorded sighting round-trips back out, note and all") { p =>
    val store = LocalFileSightingStore(p.toString)
    val s = sighting("Joaquina", SightingKind.Jellyfish, "2026-09-07T10:00:00Z", Some("many, small"))
    run(store.record(s))
    assertEquals(run(store.recentFor("Joaquina", 10)), List(s))
  }

  tmp.test("a sighting with no note round-trips as None, not as the string 'null'") { p =>
    val store = LocalFileSightingStore(p.toString)
    val s = sighting("Brava", SightingKind.Whale, "2026-09-07T11:00:00Z")
    run(store.record(s))
    assertEquals(run(store.recentFor("Brava", 10)).head.note, None)
  }

  tmp.test("recentFor returns only the asked-for beach") { p =>
    val store = LocalFileSightingStore(p.toString)
    run(store.record(sighting("Joaquina", SightingKind.Jellyfish, "2026-09-07T10:00:00Z")))
    run(store.record(sighting("Brava", SightingKind.Pollution, "2026-09-07T10:00:00Z")))
    assertEquals(run(store.recentFor("Joaquina", 10)).map(_.beachName), List("Joaquina"))
  }

  tmp.test("recentFor returns newest first and honours the limit") { p =>
    val store = LocalFileSightingStore(p.toString)
    val older = sighting("Joaquina", SightingKind.Jellyfish, "2026-09-05T10:00:00Z")
    val newest = sighting("Joaquina", SightingKind.Whale, "2026-09-07T10:00:00Z")
    val middle = sighting("Joaquina", SightingKind.Pollution, "2026-09-06T10:00:00Z")
    List(older, newest, middle).foreach(s => run(store.record(s)))
    assertEquals(run(store.recentFor("Joaquina", 10)), List(newest, middle, older))
    assertEquals(run(store.recentFor("Joaquina", 1)), List(newest))
  }

  tmp.test("appends rather than overwriting — a second record keeps the first") { p =>
    val store = LocalFileSightingStore(p.toString)
    run(store.record(sighting("Joaquina", SightingKind.Jellyfish, "2026-09-05T10:00:00Z")))
    run(store.record(sighting("Joaquina", SightingKind.Whale, "2026-09-06T10:00:00Z")))
    assertEquals(run(store.recentFor("Joaquina", 10)).size, 2)
  }

  tmp.test("one malformed line does not hide every other report") { p =>
    val store = LocalFileSightingStore(p.toString)
    run(store.record(sighting("Joaquina", SightingKind.Jellyfish, "2026-09-05T10:00:00Z")))
    Files.writeString(p, Files.readString(p) + "{not json at all\n\n", java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
    run(store.record(sighting("Joaquina", SightingKind.Whale, "2026-09-06T10:00:00Z")))
    assertEquals(run(store.recentFor("Joaquina", 10)).size, 2, "the good lines survive a bad one")
  }

  tmp.test("an unknown sighting kind is skipped, not guessed at") { p =>
    val store = LocalFileSightingStore(p.toString)
    Files.writeString(p, """{"beach_name":"Joaquina","kind":"Kraken","reported_at":"2026-09-06T10:00:00Z","note":null}""" + "\n")
    assertEquals(run(store.recentFor("Joaquina", 10)), Nil)
  }

  tmp.test("a record missing a required field is skipped") { p =>
    val store = LocalFileSightingStore(p.toString)
    Files.writeString(p, """{"kind":"Whale","reported_at":"2026-09-06T10:00:00Z"}""" + "\n")
    assertEquals(run(store.recentFor("Joaquina", 10)), Nil)
  }

  test("a store whose file does not exist yet reads as empty, never throws") {
    val store = LocalFileSightingStore("/tmp/marola-no-such-dir-xyz/sightings.jsonl")
    assertEquals(run(store.recentFor("Joaquina", 10)), Nil)
  }

  test("record creates the parent directory when it is missing") {
    val dir = Files.createTempDirectory("sightings-nested")
    val nested = dir.resolve("a/b/sightings.jsonl")
    val store = LocalFileSightingStore(nested.toString)
    run(store.record(sighting("Joaquina", SightingKind.Whale, "2026-09-07T10:00:00Z")))
    assert(Files.exists(nested))
    assertEquals(run(store.recentFor("Joaquina", 10)).size, 1)
  }

  test("DefaultPath is the documented local-first location") {
    assertEquals(LocalFileSightingStore.DefaultPath, "./data/sightings.jsonl")
  }
