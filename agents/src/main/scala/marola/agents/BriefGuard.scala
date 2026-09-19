package marola.agents

import scala.jdk.CollectionConverters.*

/** The planner's decision for one beach, read back out of the `plan_swim_outing` tool result. */
final case class OutingFacts(
    beach: String,
    targetHour: String,
    leaveBy: String,
    travelMinutes: Int,
    traffic: String,
    score: Int,
    waterVerdict: String,
    hazards: List[String]
)

/**
 * What stands between a fluent model and the person reading the brief (MIP-0061 §5.6).
 *
 * The first live run is why this exists: the tool answered `water_quality_summary = "no data"` and
 * `llama3.2` wrote "Bathing-water verdict: Good". An instruction is a request; this is a check. So
 * the brief always opens with [[facts]] — rendered here, from the tool result, by plain code — and
 * the model's prose is printed under it only if [[verify]] finds nothing in it that the data does
 * not say. A withheld reply costs the reader some fluency and nothing else.
 *
 * Deliberately narrow, in the spirit of MIP-0039 (the general fact guard, still a draft): two rules
 * that are cheap to state and hard to argue with, not an attempt to parse prose.
 */
object BriefGuard:

  private val PlanTool = "plan_swim_outing"

  // A line is "about the water" when it uses one of these, in English or Portuguese.
  private val WaterWords =
    List(
      "water quality",
      "bathing",
      "verdict",
      "balneab",
      "qualidade da água",
      "própria",
      "impropria",
      "imprópria"
    )

  /** Whether the planner ran at all — without it there is no decision to print. */
  def planned(toolResults: List[(String, java.util.Map[String, Object])]): Boolean =
    toolResults.exists(_._1 == PlanTool)

  def outings(toolResults: List[(String, java.util.Map[String, Object])]): List[OutingFacts] =
    toolResults.collect { case (PlanTool, payload) => payload }.lastOption.toList.flatMap {
      payload =>
        payload.get("outings") match
          case items: java.util.List[?] =>
            items.asScala.toList.collect { case m: java.util.Map[?, ?] => fromMap(m.asScala.toMap) }
          case _ => Nil
    }

  private def fromMap(raw: Map[?, ?]): OutingFacts =
    val m: Map[String, Any] = raw.collect { case (k: String, v) => k -> v }
    def text(key: String): String = m.get(key).map(_.toString).getOrElse("")
    def int(key: String): Int = text(key).toDoubleOption.map(_.round.toInt).getOrElse(0)
    val hazards = m.get("hazards") match
      case Some(items: java.util.List[?]) => items.asScala.toList.map(_.toString)
      case _                              => Nil
    OutingFacts(
      beach = text("beach_name"),
      targetHour = text("target_hour_local"),
      leaveBy = text("leave_by_local"),
      travelMinutes = int("travel_minutes"),
      traffic = text("traffic"),
      score = int("score"),
      waterVerdict = text("water_quality_summary"),
      hazards = hazards
    )

  /** The authoritative part of the brief. No model output in it. */
  def facts(outings: List[OutingFacts]): String =
    if outings.isEmpty then "No swimmable beach and hour found for tomorrow in this radius."
    else
      outings.zipWithIndex
        .map { (o, i) =>
          val hazards = if o.hazards.isEmpty then "none flagged" else o.hazards.mkString("; ")
          s"""${i + 1}. ${o.beach} — score ${o.score}/100
             |   in the water at ${clock(o.targetHour)}, leave by ${clock(
              o.leaveBy
            )} (~${o.travelMinutes} min, traffic ${o.traffic})
             |   bathing water: ${o.waterVerdict}
             |   hazards: $hazards""".stripMargin
        }
        .mkString("\n") + s"\nTravel times: ${TrafficProfile.Basis}."

  private def clock(isoLocal: String): String = isoLocal.dropWhile(_ != 'T').drop(1).take(5)

  /** `None` when the prose may be shown; otherwise why not. */
  def verify(reply: String, outings: List[OutingFacts], toolText: String): Option[String] =
    unknownNumber(reply, outings, toolText).orElse(waterClaim(reply, outings))

  // Rule 1: every number the model writes must be one the tools returned (or a clock time built
  // from one). Catches an invented temperature, distance, score or hour.
  private val Number = """\d+(?:[.,:]\d+)?""".r

  private def unknownNumber(
      reply: String,
      outings: List[OutingFacts],
      toolText: String
  ): Option[String] =
    val known = Number.findAllIn(toolText).toSet ++
      outings.flatMap(o => List(clock(o.targetHour), clock(o.leaveBy))) ++
      outings.indices.map(i => (i + 1).toString) // list numbering
    Number
      .findAllIn(reply)
      .map(_.replace(',', '.'))
      .find(n => !known.contains(n) && !known.exists(k => sameValue(k, n)))
      .map(n => s"it states the number $n, which no tool returned")

  // "1.0" for a tool's 0.96 is rounding, not invention: equal at the precision the model wrote.
  private def sameValue(known: String, written: String): Boolean =
    (known.replace(',', '.').toDoubleOption, written.toDoubleOption) match
      case (Some(k), Some(w)) =>
        val decimals = written.dropWhile(_ != '.').drop(1).length
        val scale = math.pow(10.0, decimals.toDouble)
        math.round(k * scale) == math.round(w * scale)
      case _ => false

  // Rule 2: a line about the bathing water must quote a verdict the tools actually gave.
  private def waterClaim(reply: String, outings: List[OutingFacts]): Option[String] =
    val verdicts = outings.map(_.waterVerdict.toLowerCase).filter(_.nonEmpty).distinct
    reply.linesIterator
      .map(_.toLowerCase)
      .find(line => WaterWords.exists(line.contains) && !verdicts.exists(line.contains))
      .map(line =>
        s"it describes the bathing water in words the data does not use: \"${line.trim.take(80)}\""
      )
