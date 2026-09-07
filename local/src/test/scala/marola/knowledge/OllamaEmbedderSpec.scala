package marola.knowledge

/**
 * `nativeBaseUrl` bridges the two URL shapes Ollama serves: `LocalLlmClient` talks to the
 * OpenAI-compatible `/v1` base, while the embeddings endpoint is on the native root. Getting this
 * wrong yields a 404 at index time, so it is worth pinning.
 */
class OllamaEmbedderSpec extends munit.FunSuite:

  test("nativeBaseUrl strips the OpenAI-compatible /v1 suffix") {
    assertEquals(
      OllamaEmbedder.nativeBaseUrl("http://localhost:11434/v1"),
      "http://localhost:11434"
    )
  }

  test("nativeBaseUrl strips a trailing slash before the /v1 suffix") {
    assertEquals(
      OllamaEmbedder.nativeBaseUrl("http://localhost:11434/v1/"),
      "http://localhost:11434"
    )
  }

  test("a base that is already native is left alone") {
    assertEquals(OllamaEmbedder.nativeBaseUrl("http://localhost:11434"), "http://localhost:11434")
    assertEquals(
      OllamaEmbedder.nativeBaseUrl("http://ollama.example:11434/"),
      "http://ollama.example:11434"
    )
  }

  test("a remote host keeps its host and port") {
    assertEquals(
      OllamaEmbedder.nativeBaseUrl("https://ollama.internal:8443/v1"),
      "https://ollama.internal:8443"
    )
  }

  test("the model name is exposed for the index's provenance") {
    assertEquals(OllamaEmbedder("http://localhost:11434", "all-minilm").model, "all-minilm")
  }
