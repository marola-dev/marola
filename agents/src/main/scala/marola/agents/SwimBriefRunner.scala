package marola.agents

import scala.jdk.CollectionConverters.*

import kyo.*

import com.google.adk.agents.BaseAgent
import com.google.adk.events.Event
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.{Content, Part}

/**
 * What one turn produced: the reply, the tool calls that led to it, and what the tools answered —
 * kept so `--trace` can show the reply next to the data it was supposed to be built from.
 */
final case class BriefResult(
    reply: String,
    toolCalls: List[String],
    toolResults: List[(String, java.util.Map[String, Object])]
)

/**
 * Runs one user turn through ADK's runner and hands the outcome back as a Kyo effect.
 *
 * The runner speaks RxJava (`Flowable[Event]`); this collects it to a list inside `Sync.defer`,
 * which is all a single-turn CLI needs. Streaming replies into a Kyo `Stream` is what
 * `kyo-reactive-streams` is for (`fromPublisher` over `FlowAdapters.toFlowPublisher(flowable)`) and
 * belongs with the Telegram surface, not here.
 */
object SwimBriefRunner:

  private val AppName = "marola"
  private val UserId = "cli"

  def ask(agent: BaseAgent, question: String): BriefResult < Sync =
    Sync.defer {
      val runner = new InMemoryRunner(agent, AppName)
      val session = runner.sessionService().createSession(AppName, UserId).blockingGet()
      val message = Content.fromParts(Part.fromText(question))
      val events =
        runner.runAsync(UserId, session.id(), message).toList.blockingGet().asScala.toList
      BriefResult(finalReply(events), events.flatMap(calls), events.flatMap(results))
    }

  private def calls(event: Event): List[String] =
    event.functionCalls().asScala.toList.flatMap(c => Option(c.name().orElse(null)))

  private def results(event: Event): List[(String, java.util.Map[String, Object])] =
    event.functionResponses().asScala.toList.map { r =>
      r.name().orElse("?") -> r.response().orElse(java.util.Map.of())
    }

  private def finalReply(events: List[Event]): String =
    events
      .filter(e => e.finalResponse() && e.functionCalls().isEmpty)
      .map(_.stringifyContent().trim)
      .filter(_.nonEmpty)
      .lastOption
      .getOrElse("")
