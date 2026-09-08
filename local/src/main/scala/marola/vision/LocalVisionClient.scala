package marola.vision

import java.util.Base64

import kyo.*

import marola.http.Http
import marola.json.JsonValue
import marola.llm.LlmClient

/**
 * Talks to a multimodal Ollama model (`llava`, `moondream`, ...) over the same OpenAI-compatible
 * `/v1/chat/completions` endpoint `LocalLlmClient` uses — vision requests just add an `image_url`
 * content part alongside the text prompt, per the standard OpenAI vision message format.
 */
final class LocalVisionClient(baseUrl: String, model: String) extends VisionClient:

  private val defaultPrompt =
    "Describe this beach/ocean photo. Note water clarity, wave conditions, and anything that " +
      "looks like it could be jellyfish or marine life near the shore."

  def describe(imageBytes: Array[Byte]): String < Sync =
    val base64 = Base64.getEncoder.encodeToString(imageBytes)
    val body = JsonValue.obj(
      "model" -> JsonValue.str(model),
      "messages" -> JsonValue.arr(
        JsonValue.obj(
          "role" -> JsonValue.str("user"),
          "content" -> JsonValue.arr(
            JsonValue.obj("type" -> JsonValue.str("text"), "text" -> JsonValue.str(defaultPrompt)),
            JsonValue.obj(
              "type" -> JsonValue.str("image_url"),
              "image_url" -> JsonValue.obj(
                "url" -> JsonValue.str(s"data:image/jpeg;base64,$base64")
              )
            )
          )
        )
      )
    )
    Http
      .postJson(s"$baseUrl/chat/completions", body.render, timeoutSeconds = 180)
      .map(LlmClient.extractContent)

object LocalVisionClient:
  val DefaultModel = "llava"
