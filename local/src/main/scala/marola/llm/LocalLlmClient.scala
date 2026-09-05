package marola.llm

import kyo.*

import marola.http.Http
import marola.json.JsonValue

/**
 * Talks to any OpenAI-compatible local server — Ollama (`ollama serve`, default port 11434), LM
 * Studio, or a llama.cpp server. This is what makes marola runnable with zero Azure account:
 * confirmed end-to-end against a real local Ollama install (`dolphin-mixtral:8x7b`) while building
 * this — a trivial completion took ~46s on CPU, hence the generous default timeout below. No new
 * dependency: it's plain JSON over HTTP via the same `Http`/`JsonValue` marola already uses for
 * Open-Meteo/Overpass.
 */
final class LocalLlmClient(baseUrl: String, model: String) extends LlmClient:

  def complete(messages: List[ChatMessage]): String < Sync =
    val body = JsonValue.obj(
      "model" -> JsonValue.str(model),
      "messages" -> JsonValue.arr(
        messages.map(m =>
          JsonValue.obj("role" -> JsonValue.str(m.role), "content" -> JsonValue.str(m.content))
        )*
      )
    )
    Http
      .postJson(s"$baseUrl/chat/completions", body.render, timeoutSeconds = 180)
      .map(LlmClient.extractContent)

object LocalLlmClient:
  val DefaultBaseUrl = "http://localhost:11434/v1"
  val DefaultModel = "llama3.2"
