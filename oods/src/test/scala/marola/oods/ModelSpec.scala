package marola.oods

/**
 * The disk labels are the contract `data/oods/` and the SQL schema (MIP-0056 §5.3) are written
 * against, so they are asserted literally here: renaming a case must break this test, not a
 * committed Parquet partition.
 */
class ModelSpec extends munit.FunSuite:

  test("Channel labels match the schema's spellings and round-trip") {
    assertEquals(Channel.values.toList.map(_.label), List("csv", "pdf", "json"))
    Channel.values.foreach(c => assertEquals(Channel.fromLabel(c.label), Some(c)))
    assertEquals(Channel.fromLabel("CSV"), Option.empty[Channel])
  }

  test("Indicator labels match the schema's spellings and round-trip") {
    assertEquals(Indicator.values.toList.map(_.label), List("e_coli", "enterococci", "unknown"))
    Indicator.values.foreach(i => assertEquals(Indicator.fromLabel(i.label), Some(i)))
    assertEquals(Indicator.fromLabel("E. coli"), Option.empty[Indicator])
  }

  test("Qualifier labels match the schema's spellings and round-trip") {
    assertEquals(Qualifier.values.toList.map(_.label), List("exact", "below", "above"))
    Qualifier.values.foreach(q => assertEquals(Qualifier.fromLabel(q.label), Some(q)))
    assertEquals(Qualifier.fromLabel("<"), Option.empty[Qualifier])
  }

  test("GeoSource labels match the schema's spellings and round-trip") {
    assertEquals(GeoSource.values.toList.map(_.label), List("feed", "curated", "none"))
    GeoSource.values.foreach(g => assertEquals(GeoSource.fromLabel(g.label), Some(g)))
    assertEquals(GeoSource.fromLabel(""), Option.empty[GeoSource])
  }

  test("Mode labels match the workflow's dispatch inputs and round-trip") {
    assertEquals(Mode.values.toList.map(_.label), List("incremental", "backfill"))
    Mode.values.foreach(m => assertEquals(Mode.fromLabel(m.label), Some(m)))
    assertEquals(Mode.fromLabel("Backfill"), Option.empty[Mode])
  }
