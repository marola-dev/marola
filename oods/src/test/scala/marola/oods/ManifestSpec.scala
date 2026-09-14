package marola.oods

import java.nio.file.{Files, Path}
import java.time.Instant

/**
 * The manifest is what makes a re-run idempotent (MIP-0056 §5.2), so the properties under test are
 * byte-stability and surviving a crash, not just "it parses".
 */
class ManifestSpec extends munit.FunSuite:

  private val campeche = RawEntry(
    url = "https://balneabilidade.ima.sc.gov.br/relatorio/exportarCSV",
    sha256 = "a1b2c3",
    bytes = 12345L,
    fetchedAt = Instant.parse("2026-09-14T09:00:00Z"),
    rows = 145
  )

  private val points = RawEntry(
    url = "https://balneabilidade.ima.sc.gov.br/relatorio/mapa",
    sha256 = "d4e5f6",
    bytes = 204800L,
    fetchedAt = Instant.parse("2026-09-14T09:01:02Z"),
    rows = 260
  )

  private val manifest = Manifest(
    raw = Map(
      "data/oods/raw/ima-sc/points.json" -> points,
      "data/oods/raw/ima-sc/csv/florianopolis/praia-do-campeche/2025.csv" -> campeche
    ),
    partitions = Map(
      "ima-sc/csv/florianopolis/praia-do-campeche/2025" -> "9f9f",
      "ima-sc/csv/florianopolis/praia-do-campeche/2003" -> "0a0a"
    )
  )

  private def tmpFile(): Path =
    Files.createTempDirectory("marola-oods-manifest").resolve("ima-sc.json")

  test("a manifest round-trips through the file") {
    val file = tmpFile()
    Manifest.write(file, manifest)
    assertEquals(Manifest.read(file), manifest)
  }

  test("a manifest that was never written reads as empty") {
    assertEquals(Manifest.read(tmpFile()), Manifest.empty)
  }

  test("keys are sorted, every raw entry is one line, and the file ends with a newline") {
    val one = Manifest(
      raw = Map("data/oods/raw/ima-sc/points.json" -> points),
      partitions = Map("ima-sc/json/points" -> "9f9f")
    )
    assertEquals(
      Manifest.render(one),
      """{
"partitions": {
"ima-sc/json/points": "9f9f"
},
"raw": {
"data/oods/raw/ima-sc/points.json": {"bytes":204800,"fetched_at":"2026-09-14T09:01:02Z","rows":260,"sha256":"d4e5f6","url":"https://balneabilidade.ima.sc.gov.br/relatorio/mapa"}
},
"version": 1
}
"""
    )
  }

  test("an empty manifest renders without a dangling block") {
    assertEquals(
      Manifest.render(Manifest.empty),
      "{\n\"partitions\": {},\n\"raw\": {},\n\"version\": 1\n}\n"
    )
  }

  test("a rewrite of unchanged content is byte-identical") {
    val reordered = Manifest(
      raw = manifest.raw.toVector.reverse.toMap,
      partitions = manifest.partitions.toVector.reverse.toMap
    )
    assertEquals(Manifest.render(reordered), Manifest.render(manifest))

    val file = tmpFile()
    Manifest.write(file, manifest)
    val first = Files.readAllBytes(file).toVector
    Manifest.write(file, Manifest.read(file))
    assertEquals(Files.readAllBytes(file).toVector, first)
  }

  test("a crash between the write and the rename leaves the old manifest intact") {
    val file = tmpFile()
    Manifest.write(file, manifest)
    val before = Files.readAllBytes(file).toVector

    Manifest.write(file, Manifest.empty, (_, _) => ())

    assertEquals(Files.readAllBytes(file).toVector, before)
    assert(
      Files.isRegularFile(Manifest.tempFile(file)),
      "the partial write should be the temp file"
    )
  }
