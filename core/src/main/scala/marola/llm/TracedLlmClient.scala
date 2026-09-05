package marola.llm

import kyo.*

import marola.observability.Tracing

/**
 * MIP-0010 task 6: the decorator that gives every `LlmClient.complete` one span — `llm.<model>`
 * with the GenAI semantic-convention attributes MLflow ingests (`gen_ai.operation.name`,
 * `gen_ai.request.model`) plus marola's own sizes (message count, prompt/completion characters).
 * Latency is the span's own duration. Token counts are *not* recorded: `LlmClient.complete` returns
 * the text only, the `usage` block of the chat-completions response is discarded in
 * `LlmClient.extractContent` — surfacing it means widening the trait, deliberately not done here
 * (MIP §8: "token counts only when the response carries `usage`", which today it never does at this
 * layer).
 *
 * Prompt and completion text are attached only when `traceContent` is true
 * (`MAROLA_TRACE_CONTENT=1`): the prompt carries the swimmer's coordinates, which is personal data
 * that should not land in a trace store by default. Vendor-free — `core` sees only the `Tracing`
 * trait; wraps `LocalLlmClient` and `AzureFoundryLlmClient` alike in `AppConfig.llmClient`.
 */
final class TracedLlmClient(
    inner: LlmClient,
    model: String,
    tracing: Tracing,
    traceContent: Boolean
) extends LlmClient:

  def complete(messages: List[ChatMessage]): String < Sync =
    val start = Map(
      "gen_ai.operation.name" -> "chat",
      "gen_ai.request.model" -> model,
      "marola.llm.messages" -> messages.size.toString,
      "marola.llm.prompt_chars" -> messages.map(_.content.length).sum.toString
    ) ++ (if traceContent then Map("gen_ai.prompt" -> TracedLlmClient.renderPrompt(messages))
          else Map.empty)
    tracing.llmSpan(model, start)(inner.complete(messages)) { completion =>
      Map("marola.llm.completion_chars" -> completion.length.toString) ++
        (if traceContent then Map("gen_ai.completion" -> completion) else Map.empty)
    }

object TracedLlmClient:

  /** `role: content` per line — readable in a trace UI, no JSON escaping to fight. */
  def renderPrompt(messages: List[ChatMessage]): String =
    messages.map(m => s"${m.role}: ${m.content}").mkString("\n")

  /** `MAROLA_TRACE_CONTENT`: only `1` or `true` opt in; anything else (including unset) is off. */
  def contentFromEnv(value: Option[String]): Boolean =
    value.map(_.trim.toLowerCase).exists(v => v == "1" || v == "true")
