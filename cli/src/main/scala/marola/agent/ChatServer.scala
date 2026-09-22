package marola.agent

import java.net.InetSocketAddress

import scala.util.Try

import kyo.*

import marola.AppConfig
import marola.json.JsonValue
import marola.knowledge.{KnowledgeStore, OceanQa, SafetyFooter}
import marola.llm.LlmClient

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

/**
 * A minimal local HTTP server for marola's chat widget (MIP-0033 §5.2): wraps `OceanQa.answer` +
 * `SafetyFooter` behind two endpoints, so the static site — reached through a Cloudflare Tunnel to
 * this machine, MIP-0033's chosen path — gets a grounded, footer-carrying answer rather than
 * talking to Ollama's own API directly (which would bypass the corpus and the safety footer
 * entirely).
 */
object ChatServer:

  val DefaultPort = 8787

  /**
   * The one effect is `OceanQa.answer`'s own retrieval/LLM call — response builder, factored out of
   * the HTTP plumbing (and out of `AppConfig`) so it's unit-testable with a fake `LlmClient`/
   * `KnowledgeStore`, no real config or server needed.
   */
  def responseFor(question: String, store: KnowledgeStore, llm: Option[LlmClient]): JsonValue <
    Sync =
    llm match
      case None => Sync.defer(JsonValue.obj("error" -> JsonValue.str("no LLM configured")))
      case Some(client) =>
        OceanQa.answer(question, store, client).map { answer =>
          JsonValue.obj(
            "answer" -> JsonValue.str(SafetyFooter.append(answer.text, answer.safety)),
            "safety" -> JsonValue.bool(answer.safety),
            "sources" -> JsonValue.arr(
              answer.passages.map(p =>
                JsonValue.obj(
                  "title" -> JsonValue.str(p.docTitle),
                  "source" -> JsonValue.str(p.source)
                )
              )*
            )
          )
        }

  /** Starts the server; the caller owns its lifecycle (`.stop(0)`). */
  def start(config: AppConfig, port: Int = DefaultPort): HttpServer =
    given AllowUnsafe = AllowUnsafe.embrace.danger
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext("/health", healthHandler(config))
    server.createContext("/ask", askHandler(config))
    server.setExecutor(null)
    server.start()
    server

  private def cors(exchange: HttpExchange): Unit =
    exchange.getResponseHeaders.add("Access-Control-Allow-Origin", "*")
    exchange.getResponseHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
    exchange.getResponseHeaders.add("Access-Control-Allow-Headers", "Content-Type")

  private def writeJson(exchange: HttpExchange, status: Int, body: String): Unit =
    exchange.getResponseHeaders.add("Content-Type", "application/json; charset=utf-8")
    val bytes = body.getBytes("UTF-8")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val os = exchange.getResponseBody
    try os.write(bytes)
    finally os.close()

  private def healthHandler(config: AppConfig): HttpHandler = exchange =>
    try
      cors(exchange)
      if exchange.getRequestMethod == "OPTIONS" then exchange.sendResponseHeaders(204, -1)
      else writeJson(exchange, 200, """{"status":"ok"}""")
    finally exchange.close()

  private def askHandler(config: AppConfig)(using AllowUnsafe): HttpHandler = exchange =>
    try
      cors(exchange)
      if exchange.getRequestMethod == "OPTIONS" then exchange.sendResponseHeaders(204, -1)
      else if exchange.getRequestMethod != "POST" then
        writeJson(exchange, 405, """{"error":"POST only"}""")
      else
        val rawBody = new String(exchange.getRequestBody.readAllBytes(), "UTF-8")
        Try(JsonValue.parse(rawBody)("question").str).toOption.flatten match
          case None => writeJson(exchange, 400, """{"error":"missing question"}""")
          case Some(question) =>
            Try(
              Sync.Unsafe.evalOrThrow(
                Abort.run(
                  Abort.catching[Throwable](
                    responseFor(question, config.knowledgeStore, Some(config.llmClient))
                  )
                )
              )
            ) match
              case scala.util.Success(Result.Success(json)) => writeJson(exchange, 200, json.render)
              case scala.util.Success(failure) =>
                writeJson(
                  exchange,
                  500,
                  JsonValue.obj("error" -> JsonValue.str(failure.toString)).render
                )
              case scala.util.Failure(e) =>
                writeJson(exchange, 500, JsonValue.obj("error" -> JsonValue.str(e.toString)).render)
    finally exchange.close()
