package marola.oods

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.time.LocalDate

import marola.json.JsonValue

/**
 * `data/oods/sources.json` is what a reader of the store consults; `ImaScAdapter.DefaultSource` is
 * what the code runs on. Nothing else makes the two agree, so this does.
 */
class SourcesRegistrySpec extends munit.FunSuite:

  private def entry(id: String): JsonValue =
    val file = Paths.get("data", "oods", "sources.json")
    val registry = JsonValue.parse(Files.readString(file, UTF_8))
    registry("sources").arr.toList
      .find(_("id").str.contains(id))
      .getOrElse(fail(s"no '$id' entry in $file"))

  private def str(v: JsonValue, field: String): String =
    v(field).str.getOrElse(fail(s"missing string field '$field'"))

  private def brokenPartitions(v: JsonValue): List[BrokenPartition] =
    v("known_broken").arr.toList.map(b =>
      BrokenPartition(
        partition = str(b, "partition"),
        observed = LocalDate.parse(str(b, "observed")),
        reason = str(b, "reason")
      )
    )

  test("the registry's ima-sc entry is ImaScAdapter.DefaultSource, field for field") {
    val ima = entry("ima-sc")
    val urls = ima("urls") match
      case JsonValue.JObject(fields) => fields.flatMap((k, v) => v.str.map(k -> _))
      case other                     => fail(s"'urls' is not an object: $other")
    val fromDisk = Source(
      id = str(ima, "id"),
      institute = str(ima, "institute"),
      state = str(ima, "state"),
      country = str(ima, "country"),
      urls = urls,
      cadence = str(ima, "cadence"),
      licence = str(ima, "licence"),
      knownBroken = brokenPartitions(ima)
    )
    assertEquals(fromDisk, ImaScAdapter.DefaultSource)
  }

  test("the registry names the five beach-years the portal cannot export") {
    val listed = brokenPartitions(entry("ima-sc"))
    assertEquals(listed.size, 5)
    assertEquals(listed.map(_.observed).distinct, List(LocalDate.parse("2026-09-14")))
    assert(
      listed.map(_.partition).contains("florianopolis/balneario/2017"),
      listed.map(_.partition).toString
    )
    assert(listed.forall(_.reason.nonEmpty))
  }
