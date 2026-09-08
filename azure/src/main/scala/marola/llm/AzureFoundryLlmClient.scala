package marola.llm

import kyo.*

import marola.http.Http
import marola.json.JsonValue

import com.azure.core.credential.TokenRequestContext
import com.azure.identity.DefaultAzureCredentialBuilder

/**
 * Azure OpenAI / Foundry chat completions via a plain REST call — deliberately NOT the full
 * `azure-ai-agents` SDK (sessions, toolboxes, agent definitions): that SDK is for actual agent
 * orchestration, reserved for the separate MCP-tool-calling agent (`agent/` package,
 * `ARCHITECTURE.md` §5b) — a one-shot "turn this BestHour into a sentence" call doesn't need it.
 */
final class AzureFoundryLlmClient(endpoint: String, apiVersion: String) extends LlmClient:

  private val credential = DefaultAzureCredentialBuilder().build()

  // Cognitive Services' own resource-manager scope — the standard audience for Azure
  // OpenAI/Foundry data-plane bearer tokens, independent of any one deployment.
  private val tokenScope = "https://cognitiveservices.azure.com/.default"

  private def bearerToken(): String < Sync =
    Sync.defer {
      val context = TokenRequestContext().addScopes(tokenScope)
      credential.getTokenSync(context).getToken
    }

  def complete(messages: List[ChatMessage]): String < Sync =
    val body = JsonValue.obj(
      "messages" -> JsonValue.arr(
        messages.map(m =>
          JsonValue.obj("role" -> JsonValue.str(m.role), "content" -> JsonValue.str(m.content))
        )*
      )
    )
    for
      token <- bearerToken()
      responseBody <- Http.postJson(
        s"$endpoint/chat/completions?api-version=$apiVersion",
        body.render,
        headers = Map("Authorization" -> s"Bearer $token")
      )
    yield LlmClient.extractContent(responseBody)
