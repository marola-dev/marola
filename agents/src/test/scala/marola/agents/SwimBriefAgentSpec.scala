package marola.agents

import scala.jdk.CollectionConverters.*

import kyo.{AllowUnsafe, Sync}

import marola.model.JellyfishRisk

import com.google.adk.models.{BaseLlm, BaseLlmConnection, LlmRequest, LlmResponse}
import com.google.genai.types.{Content, FunctionCall, Part}
import io.reactivex.rxjava3.core.Flowable

import Fixtures.*

/**
 * The whole ADK loop, offline: a scripted model asks for a tool, the runner executes the real
 * `KyoTool` over canned data, the result goes back to the model, the model replies. No Ollama, no
 * network — what is under test is the wiring, not a model's judgement.
 */
class SwimBriefAgentSpec extends munit.FunSuite:

  private given unsafe: AllowUnsafe = AllowUnsafe.embrace.danger

  /** Replies with `script` in order and keeps every request it was sent. */
  final private class ScriptedLlm(script: List[LlmResponse]) extends BaseLlm("scripted"):
    private var remaining = script
    var requests: List[LlmRequest] = Nil

    override def generateContent(request: LlmRequest, stream: Boolean): Flowable[LlmResponse] =
      requests = requests :+ request
      remaining match
        case next :: rest =>
          remaining = rest
          Flowable.just(next)
        case Nil => Flowable.error(new IllegalStateException("the script ran out"))

    override def connect(request: LlmRequest): BaseLlmConnection =
      throw new UnsupportedOperationException("live connections are not used")

  private def callTool(name: String, args: Map[String, Object]): LlmResponse =
    val call = FunctionCall.builder().name(name).args(args.asJava).build()
    LlmResponse
      .builder()
      .content(
        Content.builder().role("model").parts(Part.builder().functionCall(call).build()).build()
      )
      .build()

  private def say(text: String): LlmResponse =
    LlmResponse
      .builder()
      .content(Content.builder().role("model").parts(Part.fromText(text)).build())
      .build()

  private val mole = beach("Praia Mole", 20.0)
  private val data = SwimData(
    beaches = (_, _) => List(mole),
    scoredHours = (_, _) =>
      List(
        hour(mole, 9, 90, notes = List("moderate wind")),
        hour(mole, 11, 88, notes = List("moderate wind"), jellyfish = JellyfishRisk.High)
      )
  )

  private val here = Map[String, Object](
    "lat" -> java.lang.Double.valueOf(-27.6),
    "lon" -> java.lang.Double.valueOf(-48.5)
  )

  test("model -> plan_swim_outing -> tool result -> reply") {
    val llm = ScriptedLlm(
      List(callTool("plan_swim_outing", here), say("Praia Mole at 11:00; leave by 10:30."))
    )
    val agent = SwimBriefAgent.build(llm, SwimTools.all(data))

    val result = Sync.Unsafe.evalOrThrow(SwimBriefRunner.ask(agent, "Where should I swim?"))

    assertEquals(result.toolCalls, List("plan_swim_outing"))
    assertEquals(result.reply, "Praia Mole at 11:00; leave by 10:30.")
    assertEquals(llm.requests.size, 2)
  }

  test("the model is offered exactly the three tools, and the instruction") {
    val llm = ScriptedLlm(List(say("ok")))
    val _ = Sync.Unsafe.evalOrThrow(
      SwimBriefRunner.ask(SwimBriefAgent.build(llm, SwimTools.all(data)), "hi")
    )
    val request = llm.requests.head
    assertEquals(
      request.tools().keySet().asScala.toSet,
      Set("find_nearby_beaches", "get_swim_conditions", "plan_swim_outing")
    )
    val system = request.config().get().systemInstruction().get().text()
    assert(system.contains("The swimability score and the bathing-water verdict are final"), system)
  }

  test("what goes back to the model is the planner's decision, labelled as an estimate") {
    val llm = ScriptedLlm(List(callTool("plan_swim_outing", here), say("done")))
    val _ = Sync.Unsafe.evalOrThrow(
      SwimBriefRunner.ask(SwimBriefAgent.build(llm, SwimTools.all(data)), "plan")
    )

    val responses = llm.requests.last
      .contents()
      .asScala
      .toList
      .flatMap(_.parts().orElse(java.util.List.of()).asScala)
      .flatMap(p => Option(p.functionResponse().orElse(null)))
    assertEquals(responses.map(_.name().get()), List("plan_swim_outing"))

    val payload = responses.head.response().get().asScala.toMap
    assert(payload("traffic_basis").toString.contains("not live traffic"))
    val outing: Map[String, Any] = payload("outings") match
      case items: java.util.List[?] =>
        items.get(0) match
          case m: java.util.Map[?, ?] => m.asScala.toMap.collect { case (k: String, v) => k -> v }
          case other                  => fail(s"not a map: $other")
      case other => fail(s"not a list: $other")
    // 11:00 (88) beats 09:00 (90): within tolerance, and it avoids leaving in the rush.
    assertEquals(outing("target_hour_local"), "2026-09-23T11:00")
    assertEquals(outing("leave_by_local"), "2026-09-23T10:30")
    assertEquals(outing("travel_minutes").toString, "30.0")
    assertEquals(
      outing("hazards").toString,
      "[moderate wind, jellyfish risk high (heuristic, not a forecast)]"
    )
  }

  test("a bad coordinate is an error the model can read, not a crash") {
    val llm = ScriptedLlm(
      List(callTool("get_swim_conditions", Map("lat" -> "north")), say("I need your location."))
    )
    val result = Sync.Unsafe.evalOrThrow(
      SwimBriefRunner.ask(SwimBriefAgent.build(llm, SwimTools.all(data)), "?")
    )
    assertEquals(result.reply, "I need your location.")
  }
