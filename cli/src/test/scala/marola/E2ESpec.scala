package marola

import kyo.*

import marola.llm.{CompiledPrompt, Reviewer}
import marola.model.Coordinates

/**
 * A real end-to-end check — "what's the best hour tomorrow to swim nearby?" run against live
 * Overpass/Open-Meteo (and, for the second test, a live local Ollama server), asserting the
 * pipeline's actual output is sane.
 */
class E2ESpec extends munit.FunSuite:
  import E2ESpec.E2E

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  // Arpoador, Rio de Janeiro — the same default location `Main` uses, so this test exercises
  // exactly the "just run" default path, not a special-cased test-only coordinate.
  private val Arpoador = Coordinates(lat = -22.9878, lon = -43.1913)

  test(
    "best swim hours tomorrow near Arpoador: live Overpass + Open-Meteo, sane results".tag(E2E)
  ) {
    val results =
      Sync.Unsafe.evalOrThrow(Recommender.bestPerBeachTomorrow(Arpoador, radiusKm = 15.0))

    assert(
      results.nonEmpty,
      "expected at least one real nearby beach with a forecast for tomorrow — got none; " +
        "either Overpass/Open-Meteo are down, or there's a real regression"
    )
    results.foreach { best =>
      assert(best.score >= 0 && best.score <= 100, s"score out of range 0-100: ${best.score}")
      assert(best.beach.name.nonEmpty, "beach with an empty name")
      assert(best.beach.distanceKm >= 0.0, s"negative distance: ${best.beach.distanceKm}")
    }
    assertEquals(results, results.sortBy(-_.score), "results must be ranked best-first")
  }

  test(
    "--summarize path: local Ollama produces a draft, Reviewer produces a scored verdict".tag(E2E)
  ) {
    assume(
      sys.env.get("MAROLA_E2E_SKIP_LLM").isEmpty,
      "MAROLA_E2E_SKIP_LLM set — skipping the LLM test"
    )
    val ollamaReachable =
      try
        val _ =
          Sync.Unsafe.evalOrThrow(marola.http.Http.getString("http://localhost:11434/api/tags"))
        true
      catch case _: Throwable => false
    assume(ollamaReachable, "no local Ollama server reachable at localhost:11434 — skipping")

    val client = marola.llm.LocalLlmClient(
      marola.llm.LocalLlmClient.DefaultBaseUrl,
      sys.env.getOrElse("MAROLA_LOCAL_LLM_MODEL", marola.llm.LocalLlmClient.DefaultModel)
    )

    val results =
      Sync.Unsafe.evalOrThrow(Recommender.bestPerBeachTomorrow(Arpoador, radiusKm = 15.0))
    assume(results.nonEmpty, "no results to summarize — the first E2E test already covers this gap")
    val best = results.head

    val factInputs = Map(
      "beach_name" -> best.beach.name,
      "hour_local" -> best.hour.time.toString,
      "sea_temp_c" -> best.hour.seaTempC.map(_.toString).getOrElse("unknown"),
      "wind_kmh" -> best.hour.windSpeedKmh.map(_.toString).getOrElse("unknown"),
      "wave_height_m" -> best.hour.waveHeightM.map(_.toString).getOrElse("unknown"),
      "jellyfish_risk" -> best.jellyfishRisk.toString,
      "whale_sighting_likelihood" -> best.whaleSightingLikelihood.toString,
      "score" -> best.score.toString
    )

    val summaryPrompt = loadResource("recommendation_prompt.json", outputField = "summary")
    val draft = Sync.Unsafe.evalOrThrow(client.complete(summaryPrompt.buildMessages(factInputs)))
    assert(draft.trim.nonEmpty, "summarizer returned an empty draft")

    val reviewPrompt = loadResource("review_prompt.json", outputField = "review_json")
    val result = Sync.Unsafe.evalOrThrow(Reviewer.review(client, reviewPrompt, factInputs, draft))
    assert(
      result.score >= 0 && result.score <= 100,
      s"reviewer score out of range: ${result.score}"
    )
    assert(
      result.verdict == "approve" || result.verdict == "revise",
      s"unexpected verdict: ${result.verdict}"
    )
    assert(result.finalSummary.trim.nonEmpty, "reviewer returned an empty final_summary")
  }

  test(
    "IMA/SC bathing-water feed: live, undocumented — shape still parses (MIP-0001 §8)".tag(E2E)
  ) {
    val points = Sync.Unsafe.evalOrThrow(marola.water.ImaScWaterQualityClient().samplingPoints)
    assert(points.size >= 200, s"expected ~260 sampling points, got ${points.size}")
    assert(points.forall(_.samples.nonEmpty), "a point with no samples")
    assert(points.exists(_.beachName == "PRAIA DO CAMPECHE"), "no Campeche points")
  }

  private def loadResource(name: String, outputField: String): CompiledPrompt =
    val stream = getClass.getClassLoader.getResourceAsStream(name)
    val json = scala.io.Source.fromInputStream(stream).mkString
    CompiledPrompt.loadFromString(json, outputField)

object E2ESpec:
  val E2E = new munit.Tag("E2E")
