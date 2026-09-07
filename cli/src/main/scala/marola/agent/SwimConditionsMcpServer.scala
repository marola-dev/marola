package marola.agent

import kyo.*

import marola.beaches.BeachFinder
import marola.json.JsonValue
import marola.knowledge.OceanQa
import marola.model.{Beach, BestHour, Coordinates}
import marola.water.{BathingCondition, SamplingPoint, WaterQuality, WaterQualityMatcher}
import marola.{AppConfig, Recommender}

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.server.{McpServer, McpSyncServerExchange}
import io.modelcontextprotocol.spec.McpSchema
import tools.jackson.databind.json.JsonMapper

/**
 * Exposes marola's own pipeline (`BeachFinder`, `Recommender`) as MCP tools — the "Foundry Agent
 * Service + MCP tool-calling" item from `ARCHITECTURE.md` §5b: instead of `Recommender` hardcoding
 * the call order (BeachFinder → OpenMeteoClient → Swimability), an agent (Claude Desktop locally,
 * or an Azure AI Foundry agent once deployed) can decide when/how to call these tools itself.
 *
 * Runs over **stdio** (`StdioServerTransportProvider`) — the simplest MCP transport, and the one
 * that needs zero network exposure: point any local MCP client's config at this jar (`java -cp ...
 * marola.agent.SwimConditionsMcpServer`) and it "just works", no Azure account, no public URL. A
 * Foundry agent's *remote* MCP tool config needs an HTTP-reachable server instead (this SDK also
 * ships `HttpServletSseServerTransportProvider`/`HttpServletStreamableServerTransportProvider` for
 * that — not wired up here, since it needs an actual servlet container and a public endpoint, i.e.
 * real deployment, which nothing in this repo has yet per `AGENTS.md`'s cost-safety rule).
 *
 * Verified: this file compiles and, run manually, correctly registers with and responds to the MCP
 * `tools/list` and `tools/call` methods (checked by piping raw JSON-RPC requests to stdin — see
 * `ARCHITECTURE.md` §5b's Status note). NOT verified against Claude Desktop or a Foundry agent
 * directly — that requires configuring an actual MCP client to launch this process, which wasn't
 * available to test in this environment.
 */
object SwimConditionsMcpServer:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  private def runSync[A](effect: A < Sync): A = Sync.Unsafe.evalOrThrow(effect)

  private def numberArg(args: java.util.Map[String, Object], key: String): Option[Double] =
    Option(args.get(key)).flatMap {
      case n: java.lang.Number => Some(n.doubleValue())
      case other => other.toString.trim.toDoubleOption // bad input → default, not a crash
    }

  private def beachToJson(beach: Beach): JsonValue =
    JsonValue.obj(
      "name" -> JsonValue.str(beach.name),
      "lat" -> JsonValue.num(beach.coordinates.lat),
      "lon" -> JsonValue.num(beach.coordinates.lon),
      "distance_km" -> JsonValue.num(beach.distanceKm)
    )

  private def bestHourToJson(best: BestHour): JsonValue =
    JsonValue.obj(
      "beach_name" -> JsonValue.str(best.beach.name),
      "distance_km" -> JsonValue.num(best.beach.distanceKm),
      "hour_local" -> JsonValue.str(best.hour.time.toString),
      "score" -> JsonValue.num(best.score.toDouble),
      "sea_temp_c" -> best.hour.seaTempC.map(JsonValue.num).getOrElse(JsonValue.JNull),
      "wind_kmh" -> best.hour.windSpeedKmh.map(JsonValue.num).getOrElse(JsonValue.JNull),
      "wave_height_m" -> best.hour.waveHeightM.map(JsonValue.num).getOrElse(JsonValue.JNull),
      "jellyfish_risk" -> JsonValue.str(best.jellyfishRisk.toString),
      "whale_sighting_likelihood" -> JsonValue.str(best.whaleSightingLikelihood.toString),
      "notes" -> JsonValue.arr(best.notes.map(JsonValue.str)*),
      // MIP-0001
      "water_quality" -> best.waterQuality.map(waterQualityToJson).getOrElse(JsonValue.JNull),
      "water_quality_summary" -> JsonValue.str(marola.Report.waterSummary(best)),
      "tides" -> JsonValue.arr(
        best.dayTides.map(t =>
          JsonValue.obj(
            "time" -> JsonValue.str(t.time.toString),
            "height_m" -> JsonValue.num(t.heightM),
            "high" -> JsonValue.bool(t.isHigh)
          )
        )*
      )
    )

  private def samplingPointToJson(p: SamplingPoint): JsonValue =
    JsonValue.obj(
      "point" -> JsonValue.str(p.pointName),
      "beach" -> JsonValue.str(p.beachName),
      "location" -> JsonValue.str(p.location),
      "lat" -> JsonValue.num(p.coordinates.lat),
      "lon" -> JsonValue.num(p.coordinates.lon),
      "latest" -> p.latest
        .map(s =>
          JsonValue.obj(
            "sampled_on" -> JsonValue.str(s.sampledOn.toString),
            "condition" -> JsonValue.str(s.condition match
              case BathingCondition.Proper   => "proper"
              case BathingCondition.Improper => "improper"
              case BathingCondition.Unknown  => "unknown"
            ),
            "enterococci_per_100ml" -> s.enterococciPer100ml
              .map(n => JsonValue.num(n.toDouble))
              .getOrElse(JsonValue.JNull),
            "rain" -> s.rain.map(JsonValue.str).getOrElse(JsonValue.JNull),
            "water_temp_c" -> s.waterTempC.map(JsonValue.num).getOrElse(JsonValue.JNull)
          )
        )
        .getOrElse(JsonValue.JNull)
    )

  private def waterQualityToJson(wq: WaterQuality): JsonValue =
    JsonValue.obj(
      "source" -> JsonValue.str(wq.source),
      "points" -> JsonValue.arr(wq.points.map(samplingPointToJson)*)
    )

  private def numberProperty(description: String): java.util.Map[String, Object] =
    java.util.Map.of("type", "number", "description", description)

  private val latLonRadiusSchema: java.util.Map[String, Object] = java.util.Map.of(
    "type",
    "object",
    "properties",
    java.util.Map.of(
      "lat",
      numberProperty("Latitude"),
      "lon",
      numberProperty("Longitude"),
      "radius_km",
      numberProperty("Search radius in km, default 15")
    ),
    "required",
    java.util.List.of("lat", "lon")
  )

  private val findBeachesTool = McpSchema.Tool
    .builder("find_nearby_beaches", latLonRadiusSchema)
    .description("Find named open-water swim beaches near a coordinate, via OpenStreetMap.")
    .build()

  private val recommendationTool = McpSchema.Tool
    .builder("get_swim_recommendation", latLonRadiusSchema)
    .description(
      "Get tomorrow's best swim hour for each beach near a coordinate: sea temperature, wind, " +
        "wave height, a swimability score, jellyfish risk, whale sighting likelihood, tide turns, " +
        "and (where a regional agency publishes it) bathing-water quality per sampling point."
    )
    .build()

  private val waterQualityTool = McpSchema.Tool
    .builder("get_water_quality", latLonRadiusSchema)
    .description(
      "Official bathing-water quality (PRÓPRIA/IMPRÓPRIA, enterococci count, sample date) for every " +
        "monitored sampling point on beaches near a coordinate. Santa Catarina (IMA/SC) only so far."
    )
    .build()

  private val questionSchema: java.util.Map[String, Object] = java.util.Map.of(
    "type",
    "object",
    "properties",
    java.util.Map.of(
      "question",
      java.util.Map.of(
        "type",
        "string",
        "description",
        "A question about the sea, swimming safety, jellyfish, whales, tides or water quality"
      )
    ),
    "required",
    java.util.List.of("question")
  )

  private val askTool = McpSchema.Tool
    .builder("ask_ocean_question", questionSchema)
    .description(
      "Answer a marine-safety / ocean question from marola's curated, sourced knowledge corpus " +
        "(local RAG). Returns the answer with [n] citations and the passages' source URLs."
    )
    .build()

  // `exchange` is unused in both handlers below but required by the MCP SDK's BiFunction
  // signature — kept named (not `_`) so it stays self-documenting at the call site.
  private def findBeachesHandler(
      exchange: McpSyncServerExchange,
      request: McpSchema.CallToolRequest
  ): McpSchema.CallToolResult =
    val args = request.arguments()
    val lat = numberArg(args, "lat").getOrElse(0.0)
    val lon = numberArg(args, "lon").getOrElse(0.0)
    val radiusKm = numberArg(args, "radius_km").getOrElse(15.0)
    val beaches = runSync(BeachFinder.nearby(Coordinates(lat, lon), radiusKm))
    val json = JsonValue.arr(beaches.map(beachToJson)*).render
    McpSchema.CallToolResult.builder().addTextContent(json).build()

  private def recommendationHandler(
      exchange: McpSyncServerExchange,
      request: McpSchema.CallToolRequest
  ): McpSchema.CallToolResult =
    val args = request.arguments()
    val lat = numberArg(args, "lat").getOrElse(0.0)
    val lon = numberArg(args, "lon").getOrElse(0.0)
    val radiusKm = numberArg(args, "radius_km").getOrElse(15.0)
    val config = AppConfig.fromEnv
    val results = runSync(
      Recommender.bestPerBeachTomorrow(
        Coordinates(lat, lon),
        radiusKm,
        distanceRefiner = config.distanceRefiner
      )
    )
    val json = JsonValue.arr(results.map(bestHourToJson)*).render
    McpSchema.CallToolResult.builder().addTextContent(json).build()

  private def waterQualityHandler(
      exchange: McpSyncServerExchange,
      request: McpSchema.CallToolRequest
  ): McpSchema.CallToolResult =
    val args = request.arguments()
    val origin =
      Coordinates(numberArg(args, "lat").getOrElse(0.0), numberArg(args, "lon").getOrElse(0.0))
    val radiusKm = numberArg(args, "radius_km").getOrElse(15.0)
    val config = AppConfig.fromEnv
    val json = config.waterQualityClient(origin) match
      case None =>
        JsonValue.obj("error" -> JsonValue.str("no water-quality provider covers this origin"))
      case Some(client) =>
        val (beaches, points) = runSync(
          for
            bs <- BeachFinder.nearby(origin, radiusKm)
            ps <- client.samplingPoints
          yield (bs, ps)
        )
        val assigned = WaterQualityMatcher.assign(beaches, points, client.name)
        JsonValue.obj(
          "source" -> JsonValue.str(client.name),
          "beaches" -> JsonValue.arr(beaches.map { b =>
            JsonValue.obj(
              "beach" -> beachToJson(b),
              "water_quality" -> assigned
                .get(b.name)
                .map(waterQualityToJson)
                .getOrElse(JsonValue.JNull)
            )
          }*)
        )
    McpSchema.CallToolResult.builder().addTextContent(json.render).build()

  private def askHandler(
      exchange: McpSyncServerExchange,
      request: McpSchema.CallToolRequest
  ): McpSchema.CallToolResult =
    val question = Option(request.arguments().get("question")).map(_.toString).getOrElse("")
    val config = AppConfig.fromEnv
    val json = config.llmClient match
      case None => JsonValue.obj("error" -> JsonValue.str("no LLM configured"))
      case Some(llm) =>
        val answer = runSync(OceanQa.answer(question, config.knowledgeStore, llm))
        JsonValue.obj(
          "answer" -> JsonValue.str(
            marola.knowledge.SafetyFooter.append(answer.text, answer.safety)
          ),
          "safety" -> JsonValue.bool(answer.safety),
          "sources" -> JsonValue.arr(
            answer.passages.map(p =>
              JsonValue.obj(
                "title" -> JsonValue.str(p.docTitle),
                "source" -> JsonValue.str(p.source),
                "score" -> JsonValue.num(p.score)
              )
            )*
          )
        )
    McpSchema.CallToolResult.builder().addTextContent(json.render).build()

  def main(args: Array[String]): Unit =
    val jsonMapper = JacksonMcpJsonMapper(JsonMapper.builder().build())
    val transport = StdioServerTransportProvider(jsonMapper)
    val server = McpServer
      .sync(transport)
      .serverInfo("marola-swim-conditions", "0.1.0")
      .instructions(
        "Tools for finding nearby open-water swim beaches and tomorrow's best swim conditions " +
          "(sea temperature, wind, waves, jellyfish risk, whale sighting likelihood)."
      )
      .toolCall(findBeachesTool, findBeachesHandler(_, _))
      .toolCall(recommendationTool, recommendationHandler(_, _))
      .toolCall(waterQualityTool, waterQualityHandler(_, _))
      .toolCall(askTool, askHandler(_, _))
      .build()
    // `main` returns here. What keeps the process serving stdio is the SDK's non-daemon reader
    // thread, which ends on stdin EOF — so under plain `java` the JVM lives exactly as long as the
    // client's pipe. Under sbt's *in-process* run that is not enough: sbt treats `main` returning
    // as task completion and exits, which is why `build.sbt` forks `run` (`.mcp.json` launches
    // this via `just mcp-server`; MIP-0011 task 9 review, 2026-09-06). Launched by an MCP client
    // (Claude Code, Claude Desktop), never interactively.
    Runtime.getRuntime.addShutdownHook(Thread(() => server.closeGracefully()))
