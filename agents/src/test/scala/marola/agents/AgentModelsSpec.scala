package marola.agents

class AgentModelsSpec extends munit.FunSuite:

  private def settings(env: (String, String)*) = AgentModels.settings(env.toMap.get)

  test("no environment at all is the local, keyless default") {
    val s = settings().toOption.get
    assertEquals(s.provider, AgentModels.Provider.Ollama)
    assertEquals(s.model, AgentModels.DefaultOllamaModel)
    assertEquals(s.ollamaBaseUrl, "http://127.0.0.1:11434")
    assertEquals(s.ollamaContextTokens, AgentModels.DefaultContextTokens)
  }

  test("OLLAMA_HOST without a scheme gets one; a tiny num_ctx is ignored") {
    val s = settings("OLLAMA_HOST" -> "gpu-box:11434", "MAROLA_AGENT_NUM_CTX" -> "512").toOption.get
    assertEquals(s.ollamaBaseUrl, "http://gpu-box:11434")
    assertEquals(s.ollamaContextTokens, AgentModels.DefaultContextTokens)
  }

  test("gemini without a key is refused, with the variable named") {
    val problem = settings("MAROLA_AGENT_PROVIDER" -> "gemini").left.toOption.get
    assert(problem.contains("GOOGLE_API_KEY"), problem)
  }

  test("gemini with a key, either variable, and its own default model") {
    val s = settings("MAROLA_AGENT_PROVIDER" -> "Gemini", "GEMINI_API_KEY" -> "k").toOption.get
    assertEquals(s.provider, AgentModels.Provider.Gemini)
    assertEquals(s.model, AgentModels.DefaultGeminiModel)
    assertEquals(s.geminiApiKey, Some("k"))
  }

  test("an unknown provider is an error, not a silent fallback to a paid one") {
    assert(settings("MAROLA_AGENT_PROVIDER" -> "vertex").isLeft)
  }

class SwimBriefMainSpec extends munit.FunSuite:

  test("arguments") {
    val parsed =
      SwimBriefMain.parse(List("--lat", "-27.6", "--lon", "-48.5", "--radius-km", "8", "--trace"))
    assertEquals(
      parsed,
      Right(SwimBriefMain.Args(Some(-27.6), Some(-48.5), 8.0, None, trace = true))
    )
    assert(SwimBriefMain.parse(List("--lat", "south")).isLeft)
    assert(SwimBriefMain.parse(List("--verbose")).isLeft)
  }

  test("the coordinate travels in the question; without one the agent has to ask") {
    val withPlace = SwimBriefMain.question(SwimBriefMain.Args(Some(-27.6), Some(-48.5)))
    assert(withPlace.startsWith("I am at latitude -27.6, longitude -48.5"), withPlace)
    assert(!SwimBriefMain.question(SwimBriefMain.Args()).contains("latitude"))
  }
