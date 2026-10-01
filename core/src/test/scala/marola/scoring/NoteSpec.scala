package marola.scoring

import marola.json.JsonValue

/** MIP-0054 §5.1: each code's English is the exact string `Swimability` produced before codes. */
class NoteSpec extends munit.FunSuite:

  private def num(k: String, v: Double) = k -> JsonValue.num(v)
  private def str(k: String, v: String) = k -> JsonValue.str(v)

  private val unfit = Map(
    str("source", "IMA/SC"),
    str("sampled_on", "2026-09-02"),
    str("point", "Ponto 1"),
    str("location", "loc Ponto 1")
  )

  private val english: List[(Note, String)] = List(
    Note(NoteCode.RoughSeas, Map(num("wave_m", 2.35))) -> "rough seas (2.4m waves)",
    Note(NoteCode.Choppy, Map(num("wave_m", 0.84))) -> "choppy (0.8m waves)",
    Note(NoteCode.NoWaveData) -> "no wave data",
    Note(NoteCode.StrongWind, Map(num("wind_kmh", 41.7))) -> "strong wind (42km/h)",
    Note(NoteCode.Breezy, Map(num("wind_kmh", 22.5))) -> "breezy (23km/h)",
    Note(NoteCode.NoWindData) -> "no wind data",
    Note(NoteCode.ColdWater, Map(num("sea_temp_c", 15.3))) -> "cold water (15.3°C)",
    Note(NoteCode.WarmWater, Map(num("sea_temp_c", 28.46))) -> "warm water (28.5°C)",
    Note(NoteCode.NoSeaTempData) -> "no sea temperature data",
    Note(NoteCode.RainLikely, Map(num("rain_pct", 75.0))) -> "75% chance of rain",
    Note(NoteCode.Dark) -> "dark",
    Note(NoteCode.JellyfishElevated) -> "elevated jellyfish likelihood",
    Note(NoteCode.JellyfishSome) -> "some jellyfish likelihood",
    Note(NoteCode.WaterStale, Map(str("sampled_on", "2026-07-07"))) ->
      "water quality data stale (7 Jul)",
    Note(
      NoteCode.WaterUnfit,
      unfit + num("enterococci_per_100ml", 749)
    ) -> "water unfit for bathing — IMA/SC 2 Sep, Ponto 1 (loc Ponto 1), 749 enterococci/100mL",
    Note(
      NoteCode.WaterUnfit,
      unfit
    ) -> "water unfit for bathing — IMA/SC 2 Sep, Ponto 1 (loc Ponto 1), count n/a",
    Note(
      NoteCode.WaterMixed,
      Map(
        num("proper", 1),
        num("total", 3),
        "avoid" -> JsonValue.arr(JsonValue.str("loc P1"), JsonValue.str("loc P2"))
      )
    ) -> "1/3 points PRÓPRIA — avoid loc P1; loc P2"
  )

  test("english: every code renders its exact CLI wording") {
    english.foreach { case (note, text) => assertEquals(note.english, text, note.toString) }
    assertEquals(english.map(_._1.code).toSet, NoteCode.values.toSet, "a code has no English case")
  }

  test("label/fromLabel round-trip every code, and an unknown label is None") {
    NoteCode.values.foreach(c => assertEquals(NoteCode.fromLabel(c.label), Some(c)))
    assertEquals(NoteCode.fromLabel("gale"), None)
  }

  test("json: {code, args} with numbers kept as numbers") {
    val j = Note(NoteCode.Breezy, Map(num("wind_kmh", 22.5))).json
    assertEquals(j("code").str, Some("breezy"))
    assertEquals(j("args")("wind_kmh").num, Some(22.5))
    assertEquals(
      Note(NoteCode.Dark).json,
      JsonValue.obj("code" -> JsonValue.str("dark"), "args" -> JsonValue.obj())
    )
  }

end NoteSpec
