package marola.scoring

import java.time.LocalDateTime

import marola.json.JsonValue
import marola.model.{HourlyConditions, JellyfishRisk, WhaleSightingLikelihood, WindLevel}

class SwimabilitySpec extends munit.FunSuite:

  // September — in WhaleSeasonMonths, so whale tests below don't also need to override the month.
  private val baseTime = LocalDateTime.of(2026, 9, 5, 10, 0)

  private def hour(
      airTempC: Option[Double] = Some(26.0),
      seaTempC: Option[Double] = Some(23.0),
      waveHeightM: Option[Double] = Some(0.3),
      windSpeedKmh: Option[Double] = Some(8.0),
      windDirectionDeg: Option[Double] = Some(90.0),
      currentVelocityKmh: Option[Double] = Some(1.0),
      uvIndex: Option[Double] = Some(6.0),
      precipitationProbabilityPct: Option[Double] = Some(5.0),
      time: LocalDateTime = baseTime,
      isDaylight: Option[Boolean] = Some(true)
  ): HourlyConditions =
    HourlyConditions(
      time,
      airTempC,
      seaTempC,
      waveHeightM,
      windSpeedKmh,
      windDirectionDeg,
      currentVelocityKmh,
      uvIndex,
      precipitationProbabilityPct,
      isDaylight
    )

  test("good conditions score high when they don't also read as jellyfish-favorable") {
    // Calm wind + calm current alone are already 2 of the 4 jellyfish signals (see Swimability's
    // doc comment: the heuristic's "calm" correlates overlap with what also makes for pleasant
    // swimming), so a *merely* choppy sea here — not hot, not dead calm — is what isolates "good
    // swimming score" from "elevated jellyfish risk" for this assertion.
    val (score, notes) =
      Swimability.score(
        hour(waveHeightM = Some(0.7), windSpeedKmh = Some(10.0), currentVelocityKmh = Some(3.0))
      )
    assert(score >= 85, s"expected a high score, got $score with notes $notes")
    assertEquals(
      Swimability.jellyfishRisk(
        hour(waveHeightM = Some(0.7), windSpeedKmh = Some(10.0), currentVelocityKmh = Some(3.0))
      ),
      JellyfishRisk.Low
    )
  }

  test("rough seas drag the score down and are named in the notes") {
    val (score, notes) = Swimability.score(hour(waveHeightM = Some(2.0)))
    assert(score <= 60, s"expected a low score for rough seas, got $score")
    assertEquals(
      notes,
      List(
        Note(NoteCode.RoughSeas, Map("wave_m" -> JsonValue.num(2.0))),
        Note(NoteCode.JellyfishSome)
      )
    )
  }

  test("strong wind is penalized more than a light breeze") {
    val (breezyScore, _) = Swimability.score(hour(windSpeedKmh = Some(20.0)))
    val (strongScore, _) = Swimability.score(hour(windSpeedKmh = Some(40.0)))
    assert(strongScore < breezyScore, s"strong=$strongScore breezy=$breezyScore")
  }

  test(
    "windLevel: below calm is Calm, between is Breezy, at/above strong is Strong, no data is None"
  ) {
    assertEquals(Swimability.windLevel(Some(8.0)), Some(WindLevel.Calm))
    assertEquals(Swimability.windLevel(Some(14.9)), Some(WindLevel.Calm))
    assertEquals(
      Swimability.windLevel(Some(15.0)),
      Some(WindLevel.Breezy)
    ) // the calm threshold is inclusive
    assertEquals(Swimability.windLevel(Some(29.9)), Some(WindLevel.Breezy))
    assertEquals(Swimability.windLevel(Some(30.0)), Some(WindLevel.Strong)) // so is the strong one
    assertEquals(Swimability.windLevel(Some(40.0)), Some(WindLevel.Strong))
    assertEquals(Swimability.windLevel(None), None)
  }

  test("windLevel and windDelta's note agree — one threshold, two consumers (MIP-0009 §6)") {
    val windCodes = Set(NoteCode.Breezy, NoteCode.StrongWind, NoteCode.NoWindData)
    def noteFor(kmh: Option[Double]): Option[Note] =
      Swimability.score(hour(windSpeedKmh = kmh))._2.find(n => windCodes.contains(n.code))
    def kmh(v: Double) = Map("wind_kmh" -> JsonValue.num(v))
    assertEquals(noteFor(Some(8.0)), None) // Calm: no wind note at all
    assertEquals(noteFor(Some(20.0)), Some(Note(NoteCode.Breezy, kmh(20.0))))
    assertEquals(noteFor(Some(40.0)), Some(Note(NoteCode.StrongWind, kmh(40.0))))
    assertEquals(noteFor(None), Some(Note(NoteCode.NoWindData))) // "no data", never a band
  }

  test("cold water is penalized more than warm water") {
    val (coldScore, _) = Swimability.score(hour(seaTempC = Some(15.0)))
    val (warmScore, _) = Swimability.score(hour(seaTempC = Some(29.0)))
    assert(coldScore < warmScore, s"cold=$coldScore warm=$warmScore")
  }

  test("score is clamped at 0, never negative, for a worst-plausible-case combination") {
    // Note: rough seas and strong wind (the two biggest penalties) each also rule out their own
    // jellyfish-heuristic "calm" signal, so this doesn't actually stack every penalty at once —
    // the floor this scoring scheme can reach is low double digits, not exactly 0.
    val (score, _) = Swimability.score(
      hour(
        seaTempC = Some(30.0),
        waveHeightM = Some(3.0),
        windSpeedKmh = Some(50.0),
        currentVelocityKmh = Some(0.5),
        precipitationProbabilityPct = Some(90.0)
      )
    )
    assert(score >= 0 && score <= 15, s"expected a very low, non-negative score, got $score")
  }

  test("missing data costs a small penalty rather than crashing") {
    val (score, notes) =
      Swimability.score(hour(seaTempC = None, waveHeightM = None, windSpeedKmh = None))
    assert(score < 100, s"expected some penalty for missing data, got $score")
    assertEquals(
      notes.map(_.code),
      List(NoteCode.NoWaveData, NoteCode.NoWindData, NoteCode.NoSeaTempData)
    )
  }

  test(
    "jellyfish risk is High when sea is warm, calm water, weak wind and weak current all line up"
  ) {
    val risk = Swimability.jellyfishRisk(
      hour(
        seaTempC = Some(26.0),
        windSpeedKmh = Some(5.0),
        waveHeightM = Some(0.2),
        currentVelocityKmh = Some(0.5)
      )
    )
    assertEquals(risk, JellyfishRisk.High)
  }

  test("jellyfish risk is Low when seas are cold and rough") {
    val risk = Swimability.jellyfishRisk(
      hour(
        seaTempC = Some(18.0),
        windSpeedKmh = Some(35.0),
        waveHeightM = Some(2.0),
        currentVelocityKmh = Some(5.0)
      )
    )
    assertEquals(risk, JellyfishRisk.Low)
  }

  test("whale sighting likelihood is High in-season with daylight, calm wind and calm seas") {
    val risk = Swimability.whaleSightingLikelihood(
      hour(
        time = LocalDateTime.of(2026, 9, 5, 10, 0),
        isDaylight = Some(true),
        windSpeedKmh = Some(10.0),
        waveHeightM = Some(0.5)
      )
    )
    assertEquals(risk, WhaleSightingLikelihood.High)
  }

  test("whale sighting likelihood is Low outside the migration season regardless of conditions") {
    val risk = Swimability.whaleSightingLikelihood(
      hour(
        time = LocalDateTime.of(2026, 2, 5, 10, 0), // February — out of season
        isDaylight = Some(true),
        windSpeedKmh = Some(5.0),
        waveHeightM = Some(0.2)
      )
    )
    assertEquals(risk, WhaleSightingLikelihood.Low)
  }

  test("whale sighting likelihood is Low at night even in-season with calm seas") {
    val risk = Swimability.whaleSightingLikelihood(
      hour(
        time = LocalDateTime.of(2026, 9, 5, 22, 0),
        isDaylight = Some(false),
        windSpeedKmh = Some(5.0),
        waveHeightM = Some(0.2)
      )
    )
    assertEquals(risk, WhaleSightingLikelihood.Low)
  }

  test("whale sighting likelihood never affects the swimability score") {
    val calmInSeason =
      hour(time = LocalDateTime.of(2026, 9, 5, 10, 0), isDaylight = Some(true))
    val calmOutOfSeason =
      hour(time = LocalDateTime.of(2026, 2, 5, 10, 0), isDaylight = Some(true))
    assertEquals(Swimability.score(calmInSeason), Swimability.score(calmOutOfSeason))
  }

  test("a dark hour scores far below the same conditions in daylight, and says so") {
    val (day, dayNotes) = Swimability.score(hour(isDaylight = Some(true)))
    val (night, nightNotes) = Swimability.score(hour(isDaylight = Some(false)))
    assert(night <= day - 50, s"day=$day night=$night")
    assert(nightNotes.contains(Note(NoteCode.Dark)) && !dayNotes.contains(Note(NoteCode.Dark)))
    // unknown daylight is not penalised (Open-Meteo omitted is_day).
    assertEquals(Swimability.score(hour(isDaylight = None))._1, day)
  }

  test("each remaining penalty carries its code and the number behind it") {
    def codes(h: HourlyConditions) = Swimability.score(h)._2
    assertEquals(
      codes(hour(waveHeightM = Some(0.84), currentVelocityKmh = Some(4.0))),
      List(Note(NoteCode.Choppy, Map("wave_m" -> JsonValue.num(0.84))))
    )
    assertEquals(
      codes(hour(seaTempC = Some(15.3), currentVelocityKmh = Some(4.0))),
      List(
        Note(NoteCode.ColdWater, Map("sea_temp_c" -> JsonValue.num(15.3))),
        Note(NoteCode.JellyfishSome)
      )
    )
    assertEquals(
      codes(hour(seaTempC = Some(28.46), currentVelocityKmh = Some(4.0))),
      List(
        Note(NoteCode.WarmWater, Map("sea_temp_c" -> JsonValue.num(28.46))),
        Note(NoteCode.JellyfishElevated)
      )
    )
    assertEquals(
      codes(hour(precipitationProbabilityPct = Some(75.0), currentVelocityKmh = Some(4.0))),
      List(
        Note(NoteCode.RainLikely, Map("rain_pct" -> JsonValue.num(75.0))),
        Note(NoteCode.JellyfishSome)
      )
    )
  }

  test("hourPreference favours mid-morning: 10:00 beats 07:00 beats 00:00") {
    def at(h: Int) = hour(time = LocalDateTime.of(2026, 9, 6, h, 0))
    assert(Swimability.hourPreference(at(10)) < Swimability.hourPreference(at(7)))
    assert(Swimability.hourPreference(at(7)) < Swimability.hourPreference(at(0)))
    assertEquals(Swimability.hourPreference(at(10)), 0)
  }

end SwimabilitySpec
