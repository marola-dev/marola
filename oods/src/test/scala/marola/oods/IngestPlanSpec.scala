package marola.oods

import java.time.{Instant, LocalDate}

import marola.oods.Channel

/**
 * The planner's table from MIP-0056 §7: which partitions each mode selects on a given date, and
 * what a manifest hit does to each.
 */
class IngestPlanSpec extends munit.FunSuite:

  private def planFor(
      mode: Mode,
      cities: Set[String] = Set.empty,
      from: Int = 2003,
      to: Int = 2026
  ): Plan =
    Plan(
      sources = Set("ima-sc"),
      states = Set.empty,
      cities = cities,
      mode = mode,
      fromYear = from,
      toYear = to,
      dryRun = false,
      concurrency = 1
    )

  private def candidate(year: Int): Partition =
    Partition("ima-sc", Channel.Csv, "florianopolis/praia-do-campeche", year, immutable = false)

  private def candidates(years: Int*): List[Partition] = years.toList.map(candidate)

  private def manifestWith(years: Int*): Manifest =
    Manifest(
      raw = years.map { y =>
        ImaScAdapter.rawPath(candidate(y)) ->
          RawEntry("https://example.invalid", "abc", 10, Instant.parse("2026-09-14T12:00:00Z"), 3)
      }.toMap,
      partitions = Map.empty
    )

  private def years(s: Selection): List[Int] = s.fetch.map(_.year).sorted

  test("incremental in September keeps the current year only") {
    val selected = Ingest.plan(
      planFor(Mode.Incremental),
      LocalDate.parse("2026-09-14"),
      Manifest.empty,
      candidates(2024, 2025, 2026),
      Set.empty
    )
    assertEquals(years(selected), List(2026))
    assertEquals(selected.fetch.map(_.immutable), List(false))
  }

  test("incremental on 2027-01-10 keeps the previous year too — the January rule") {
    val selected = Ingest.plan(
      planFor(Mode.Incremental),
      LocalDate.parse("2027-01-10"),
      Manifest.empty,
      candidates(2025, 2026, 2027),
      Set.empty
    )
    assertEquals(years(selected), List(2026, 2027))
  }

  test("incremental on 2027-02-20 drops the previous year — the window has closed") {
    val selected = Ingest.plan(
      planFor(Mode.Incremental),
      LocalDate.parse("2027-02-20"),
      Manifest.empty,
      candidates(2025, 2026, 2027),
      Set.empty
    )
    assertEquals(years(selected), List(2027))
  }

  test("backfill keeps every year in [from, to] and marks the closed ones immutable") {
    val selected = Ingest.plan(
      planFor(Mode.Backfill, from = 2004, to = 2005),
      LocalDate.parse("2026-09-14"),
      Manifest.empty,
      candidates(2003, 2004, 2005, 2006),
      Set.empty
    )
    assertEquals(years(selected), List(2004, 2005))
    assertEquals(selected.fetch.map(_.immutable), List(true, true))
  }

  test("a manifest hit drops an immutable partition and never a mutable one") {
    val selected = Ingest.plan(
      planFor(Mode.Backfill),
      LocalDate.parse("2026-09-14"),
      manifestWith(2005, 2026),
      candidates(2004, 2005, 2026),
      Set.empty
    )
    assertEquals(years(selected), List(2004, 2026))
  }

  test("incremental refetches a mutable partition already in the manifest") {
    val selected = Ingest.plan(
      planFor(Mode.Incremental),
      LocalDate.parse("2026-09-14"),
      manifestWith(2026),
      candidates(2026),
      Set.empty
    )
    assertEquals(years(selected), List(2026))
  }

  test("a city no adapter covers is an error, not an empty success") {
    assertEquals(
      Ingest.unknownCity(planFor(Mode.Incremental, cities = Set("Macapá")), Nil),
      Some(IngestError.UnknownCity(Set("Macapá")))
    )
    assertEquals(Ingest.unknownCity(planFor(Mode.Incremental), Nil), None)
  }

  private val broken = "florianopolis/praia-do-campeche/2005"

  test("a known-broken partition is dropped from a backfill and counted, the others untouched") {
    val selected = Ingest.plan(
      planFor(Mode.Backfill, from = 2004, to = 2006),
      LocalDate.parse("2026-09-14"),
      Manifest.empty,
      candidates(2004, 2005, 2006),
      Set(broken)
    )
    assertEquals(years(selected), List(2004, 2006))
    assertEquals(selected.upstreamBroken.map(_.year), List(2005))
    assertEquals(selected.skipped, 0)
  }

  test("a known-broken partition is dropped from an incremental run too") {
    val selected = Ingest.plan(
      planFor(Mode.Incremental),
      LocalDate.parse("2026-09-14"),
      Manifest.empty,
      candidates(2026),
      Set("florianopolis/praia-do-campeche/2026")
    )
    assertEquals(years(selected), Nil)
    assertEquals(selected.upstreamBroken.map(_.year), List(2026))
  }

  test("only the named beach-year is broken — another beach's same year is planned") {
    val other = Partition("ima-sc", Channel.Csv, "itajai/praia-brava", 2005, immutable = false)
    val selected = Ingest.plan(
      planFor(Mode.Backfill, from = 2005, to = 2005),
      LocalDate.parse("2026-09-14"),
      Manifest.empty,
      candidate(2005) :: other :: Nil,
      Set(broken)
    )
    assertEquals(selected.fetch.map(_.key), List(other.key))
    assertEquals(selected.upstreamBroken.map(Ingest.partitionId), List(broken))
  }

  test("a partition outside the mode's window is skipped, never reported as upstream-broken") {
    val selected = Ingest.plan(
      planFor(Mode.Incremental),
      LocalDate.parse("2026-09-14"),
      Manifest.empty,
      candidates(2024, 2025, 2026),
      Set(broken)
    )
    assertEquals(selected.skipped, 2)
    assertEquals(selected.upstreamBroken, Nil)
  }

  test("the five known-broken beach-years of sources.json are the adapter's own") {
    val listed = ImaScAdapter.DefaultSource.knownBroken.map(_.partition)
    assertEquals(
      listed.sorted,
      List(
        "balneario-camboriu/central/2016",
        "balneario-camboriu/central/2024",
        "florianopolis/balneario/2017",
        "governador-celso-ramos/palmas/2024",
        "jaguaruna/lagoa-do-arroio-corrente/2018"
      )
    )
  }

  test("an immutable partition the manifest names but the disk has lost is planned again") {
    val onDisk = Ingest.plan(
      planFor(Mode.Backfill, from = 2024, to = 2024),
      LocalDate.parse("2026-09-14"),
      manifestWith(2024),
      candidates(2024),
      Set.empty,
      exists = _ => true
    )
    assertEquals(years(onDisk), Nil)
    val pulledAway = Ingest.plan(
      planFor(Mode.Backfill, from = 2024, to = 2024),
      LocalDate.parse("2026-09-14"),
      manifestWith(2024),
      candidates(2024),
      Set.empty,
      exists = _ => false
    )
    assertEquals(years(pulledAway), List(2024))
  }
