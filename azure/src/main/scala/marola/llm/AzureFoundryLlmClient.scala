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
 *
 * Auth is `DefaultAzureCredential` (managed identity in production, `az login` locally), per
 * `AGENTS.md`'s "no API keys" rule — confirmed against the real `azure-identity:1.18.1` API
 * (`DefaultAzureCredentialBuilder().build().getTokenSync(...)`), not run against a live endpoint
 * (no Foundry project provisioned — see ARCHITECTURE.md §6).
 *
 * `endpoint` is the deployment's own base URL, e.g.
 * `https://<resource>.openai.azure.com/openai/deployments/<deployment>` — `apiVersion` is appended
 * as a query param, matching Azure OpenAI's REST chat-completions shape.
 */
final class AzureFoundryLlmClient(endpoint: String, apiVersion: String) extends LlmClient:

  private val credential = DefaultAzureCredentialBuilder().build()

  // Cognitive Services' own resource-manager scope — the standard audience for Azure OpenAI/Foundry
  // data-plane bearer tokens, independent of any one deployment.
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
