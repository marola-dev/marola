package marola.llm

import kyo.*

import marola.json.JsonValue

final case class ChatMessage(role: String, content: String)

/**
 * One backend for "turn a `BestHour` into a natural-language summary" (see `Recommender`'s query
 * synthesis step, `ARCHITECTURE.md` §5). Two implementations, chosen via `AppConfig.llmProvider`:
 * `LocalLlmClient` (default — an Ollama-compatible endpoint, zero Azure account needed) and
 * `AzureFoundryLlmClient` (a provisioned Foundry/Azure OpenAI deployment). Both take the same plain
 * OpenAI-style chat message list — `CompiledPrompt` builds that list from the DSPy-optimized
 * artifact, independent of which backend replays it.
 */
trait LlmClient:
  def complete(messages: List[ChatMessage]): String < Sync

object LlmClient:
  final case class NoCompletionException(message: String) extends Exception(message)

  /**
   * Shared response-shape extraction: every OpenAI-compatible chat-completions response (Ollama,
   * Azure OpenAI/Foundry) nests the reply at `choices[0].message.content`. Used by
   * `LocalLlmClient`, `AzureFoundryLlmClient`, and `marola.vision.LocalVisionClient` — pulled out
   * here instead of left as three copies of the same five lines.
   */
  def extractContent(responseBody: String): String =
    JsonValue
      .parse(responseBody)("choices")
      .arr
      .headOption
      .flatMap(_("message")("content").str)
      .getOrElse(
        throw NoCompletionException(s"no choices[0].message.content in response: $responseBody")
      )
