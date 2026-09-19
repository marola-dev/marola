package marola.agents

import java.time.LocalDateTime

import marola.model.BestHour
import marola.scoring.Swimability

/** One beach, one target hour, and when to leave for it. */
final case class Outing(
    pick: BestHour,
    leaveBy: LocalDateTime,
    travelMinutes: Int,
    traffic: TrafficProfile.Level,
    bestScoreAtBeach: Int
)

/**
 * Picks the target hour per beach from conditions *and* the trip (MIP-0061 §6) — plain code, no
 * model involved.
 *
 * The swimability score stays the authority. Traffic may only choose among hours whose score is
 * within [[ScoreTolerance]] of that beach's best, and never an hour scored 0 (a water-quality veto
 * or a fully penalised hour). So a calm road can move a swim from 08:00 to 10:00 when the sea is
 * equally good; it can never trade a safer hour for a faster drive.
 */
object OutingPlanner:

  val ScoreTolerance = 5

  def plan(hours: List[BestHour], beachLimit: Int = 5): List[Outing] =
    hours
      .groupBy(_.beach.name)
      .values
      .toList
      .flatMap(planBeach)
      .sortBy(o => (-o.pick.score, o.travelMinutes, o.pick.beach.name))
      .take(beachLimit)

  private def planBeach(hours: List[BestHour]): Option[Outing] =
    val swimmable = hours.filter(_.score > 0)
    swimmable.map(_.score).maxOption.map { best =>
      val candidates = swimmable.filter(_.score >= best - ScoreTolerance)
      candidates
        .map(h => outing(h, best))
        .minBy(o => (o.travelMinutes, -o.pick.score, Swimability.hourPreference(o.pick.hour)))
    }

  private def outing(hour: BestHour, best: Int): Outing =
    val (leave, minutes) = TrafficProfile.leaveBy(hour.beach.distanceKm, hour.hour.time)
    Outing(hour, leave, minutes, TrafficProfile.level(TrafficProfile.multiplier(leave)), best)
