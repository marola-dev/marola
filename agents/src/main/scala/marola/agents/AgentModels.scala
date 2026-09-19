package marola.agents

import java.time.Duration

import com.google.adk.models.langchain4j.LangChain4j
import com.google.adk.models.{BaseLlm, Gemini}
import dev.langchain4j.model.ollama.OllamaChatModel

/**
 * Which model drives the agent (MIP-0061 §5.5). Local first, as everywhere in marola:
 *
 *   - `ollama` (default) — no account, no key, no cost. Through adk-java's LangChain4j bridge.
 *   - `gemini` — the Gemini API with a key from AI Studio. Its free tier needs no billing account,
 *     and on it Google may use the content to improve its products (pricing page, fetched
 *     2026-09-19) — acceptable here because a swim brief is built from public data and a
 *     coordinate, and said in the MIP so that stays a choice.
 *
 * A Vertex AI / Model Garden backend is deliberately absent: it needs a billed project, which is
 * `AGENTS.md`'s cost gate, and gets its own task and its own go-ahead.
 */
object AgentModels:

  enum Provider derives CanEqual:
    case Ollama, Gemini

  final case class Settings(
      provider: Provider,
      model: String,
      ollamaBaseUrl: String,
      ollamaContextTokens: Int,
      geminiApiKey: Option[String]
  )

  // A tool-calling model: MIP-0060's go/no-go run (2026-09-19) showed `qwen2.5-coder:7b` writes
  // its tool calls as text, while `llama3.2` emits real ones on this host.
  val DefaultOllamaModel = "llama3.2"
  val DefaultGeminiModel = "gemini-2.5-flash"

  // The same run found this host's Ollama truncating every prompt to 4096 tokens. `num_ctx` is a
  // per-request option, so the agent asks for what it needs instead of relying on the service.
  val DefaultContextTokens = 16384

  def settings(env: String => Option[String]): Either[String, Settings] =
    val provider = env("MAROLA_AGENT_PROVIDER").map(_.trim.toLowerCase) match
      case None | Some("") | Some("ollama") => Right(Provider.Ollama)
      case Some("gemini")                   => Right(Provider.Gemini)
      case Some(other) => Left(s"MAROLA_AGENT_PROVIDER=$other — expected ollama or gemini")
    provider.flatMap { p =>
      val key = env("GOOGLE_API_KEY").orElse(env("GEMINI_API_KEY")).map(_.trim).filter(_.nonEmpty)
      val default = if p == Provider.Gemini then DefaultGeminiModel else DefaultOllamaModel
      val chosen = Settings(
        provider = p,
        model = env("MAROLA_AGENT_MODEL").map(_.trim).filter(_.nonEmpty).getOrElse(default),
        ollamaBaseUrl = env("OLLAMA_HOST")
          .map(_.trim)
          .filter(_.nonEmpty)
          .map(h => if h.startsWith("http") then h else s"http://$h")
          .getOrElse("http://127.0.0.1:11434"),
        ollamaContextTokens = env("MAROLA_AGENT_NUM_CTX")
          .flatMap(_.trim.toIntOption)
          .filter(_ >= 2048)
          .getOrElse(DefaultContextTokens),
        geminiApiKey = key
      )
      if p == Provider.Gemini && key.isEmpty then
        Left(
          "MAROLA_AGENT_PROVIDER=gemini needs GOOGLE_API_KEY (or GEMINI_API_KEY) — never commit it"
        )
      else Right(chosen)
    }

  def build(settings: Settings): BaseLlm = settings.provider match
    case Provider.Ollama =>
      val chat = OllamaChatModel
        .builder()
        .baseUrl(settings.ollamaBaseUrl)
        .modelName(settings.model)
        .numCtx(settings.ollamaContextTokens)
        .temperature(0.0)
        .timeout(Duration.ofMinutes(5))
        .build()
      LangChain4j.builder().chatModel(chat).modelName(settings.model).build()
    case Provider.Gemini =>
      // `settings` refuses a Gemini provider without a key, so the fallback is never sent.
      new Gemini(settings.model, settings.geminiApiKey.getOrElse(""))
