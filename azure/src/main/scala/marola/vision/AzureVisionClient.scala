package marola.vision

import kyo.*

import marola.http.Http
import marola.json.JsonValue

/**
 * Azure AI Vision's Image Analysis 4.0 API — structured captioning, not a conversational model
 * (contrast `LocalVisionClient`'s free-form multimodal-LLM description).
 */
final class AzureVisionClient(endpoint: String, subscriptionKey: String) extends VisionClient:

  final case class NoCaptionException(message: String) extends Exception(message)

  def describe(imageBytes: Array[Byte]): String < Sync =
    val url =
      s"$endpoint/computervision/imageanalysis:analyze?api-version=2024-02-01&features=caption"
    Http
      .postBytes(url, imageBytes, headers = Map("Ocp-Apim-Subscription-Key" -> subscriptionKey))
      .map { responseBody =>
        val json = JsonValue.parse(responseBody)
        val caption = json("captionResult")("text").str
        val confidence = json("captionResult")("confidence").num
        caption
          .map(text => confidence.fold(text)(c => f"$text (${c * 100}%.0f%% confidence)"))
          .getOrElse(throw NoCaptionException(s"no captionResult.text in response: $responseBody"))
      }
