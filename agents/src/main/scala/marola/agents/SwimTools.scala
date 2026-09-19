package marola.agents

import kyo.*

import marola.agent.SwimConditionsMcpServer
import marola.beaches.BeachFinder
import marola.json.JsonValue
import marola.model.{Beach, BestHour, Coordinates, JellyfishRisk}
import marola.{AppConfig, Recommender}

/**
 * Where the tools get their data. A pair of functions rather than a trait with one live
 * implementation, so a test hands the tools canned beaches and hours and never touches Overpass,
 * Open-Meteo or a water-quality feed.
 */
final case class SwimData(
    beaches: (Coordinates, Double) => List[Beach] < Sync,
    scoredHours: (Coordinates, Double) => List[BestHour] < Sync
)

object SwimData:

  /** The same pipeline `Main` and the MCP server run — water quality and facilities included. */
  def live(config: AppConfig): SwimData =
    SwimData(
      beaches = (origin, radiusKm) => BeachFinder.nearby(origin, radiusKm),
      scoredHours = (origin, radiusKm) =>
        Recommender.bestHoursTomorrow(
          origin,
          radiusKm,
          distanceRefiner = config.distanceRefiner,
          waterQuality = config.waterQualityClient(origin),
          accessibility = Some(config.accessibilityClient)
        )
    )

/**
 * The swim-brief agent's tools (MIP-0061 §5.3). Every number a reply may contain comes out of one
 * of these — beaches from OpenStreetMap, hourly forecast and marine data from Open-Meteo, the
 * bathing-water verdict from the state agency, the score and hazard notes from `Swimability`, the
 * trip from [[TrafficProfile]]. The model chooses which to call and phrases the result.
 */
object SwimTools:

  private val DefaultRadiusKm = 15.0

  private val where = List(
    ToolParam("lat", ToolParam.Kind.Number, "Latitude of the user, decimal degrees."),
    ToolParam("lon", ToolParam.Kind.Number, "Longitude of the user, decimal degrees."),
    ToolParam(
      "radius_km",
      ToolParam.Kind.Number,
      "Search radius in kilometres. Defaults to 15.",
      required = false
    )
  )

  private def origin(args: ToolArgs): Option[Coordinates] =
    for
      lat <- args.number("lat") if lat >= -90.0 && lat <= 90.0
      lon <- args.number("lon") if lon >= -180.0 && lon <= 180.0
    yield Coordinates(lat, lon)

  private def radius(args: ToolArgs): Double =
    args.number("radius_km").filter(r => r > 0.0 && r <= 100.0).getOrElse(DefaultRadiusKm)

  private val badOrigin: JsonValue =
    JsonValue.obj("error" -> JsonValue.str("lat and lon are required, in decimal degrees"))

  /** Hazards as the scoring code names them — nothing here is model-written. */
  private[agents] def hazards(best: BestHour): List[String] =
    val jellyfish = best.jellyfishRisk match
      case JellyfishRisk.Low      => Nil
      case JellyfishRisk.Moderate => List("jellyfish risk moderate (heuristic, not a forecast)")
      case JellyfishRisk.High     => List("jellyfish risk high (heuristic, not a forecast)")
    best.notes ++ jellyfish

  private[agents] def conditionsJson(best: BestHour): JsonValue =
    SwimConditionsMcpServer.bestHourToJson(best) match
      case JsonValue.JObject(fields) =>
        JsonValue.JObject(
          fields + ("hazards" -> JsonValue.arr(hazards(best).map(JsonValue.str)*))
        )
      case other => other

  private[agents] def outingJson(outing: Outing): JsonValue =
    conditionsJson(outing.pick) match
      case JsonValue.JObject(fields) =>
        JsonValue.JObject(
          fields ++ Map(
            "target_hour_local" -> JsonValue.str(outing.pick.hour.time.toString),
            "leave_by_local" -> JsonValue.str(outing.leaveBy.toString),
            "travel_minutes" -> JsonValue.num(outing.travelMinutes.toDouble),
            "traffic" -> JsonValue.str(outing.traffic.toString.toLowerCase),
            "traffic_basis" -> JsonValue.str(TrafficProfile.Basis),
            "best_score_at_this_beach" -> JsonValue.num(outing.bestScoreAtBeach.toDouble)
          )
        )
      case other => other

  final class FindBeaches(data: SwimData)
      extends KyoTool(
        "find_nearby_beaches",
        "Named open-water swim beaches near a coordinate, from OpenStreetMap, nearest first."
      ):
    def params: List[ToolParam] = where
    def call(args: ToolArgs): JsonValue < Sync =
      origin(args) match
        case None => badOrigin
        case Some(o) =>
          data
            .beaches(o, radius(args))
            .map(bs =>
              JsonValue.obj(
                "beaches" -> JsonValue.arr(bs.map(SwimConditionsMcpServer.beachToJson)*)
              )
            )

  final class SwimConditions(data: SwimData)
      extends KyoTool(
        "get_swim_conditions",
        "Tomorrow's best hour at each nearby beach: swimability score 0-100, sea and air " +
          "forecast (sea temperature, wind, waves), hazards, the official bathing-water verdict, " +
          "and tides. A score of 0 means do not swim there."
      ):
    def params: List[ToolParam] = where
    def call(args: ToolArgs): JsonValue < Sync =
      origin(args) match
        case None => badOrigin
        case Some(o) =>
          data.scoredHours(o, radius(args)).map { hours =>
            val bestPerBeach = hours
              .groupBy(_.beach.name)
              .values
              .map(_.maxBy(_.score))
              .toList
              .sortBy(b => (-b.score, b.beach.distanceKm))
            JsonValue.obj("beaches" -> JsonValue.arr(bestPerBeach.map(conditionsJson)*))
          }

  final class PlanOuting(data: SwimData)
      extends KyoTool(
        "plan_swim_outing",
        "Where and when to swim tomorrow, already decided: per beach, the target hour chosen " +
          "from conditions and the drive, when to leave, and the trip length. The traffic figure " +
          "is an estimate from a typical pattern, not live data — say so when you use it."
      ):
    def params: List[ToolParam] = where
    def call(args: ToolArgs): JsonValue < Sync =
      origin(args) match
        case None => badOrigin
        case Some(o) =>
          data.scoredHours(o, radius(args)).map { hours =>
            JsonValue.obj(
              "outings" -> JsonValue.arr(OutingPlanner.plan(hours).map(outingJson)*),
              "traffic_basis" -> JsonValue.str(TrafficProfile.Basis)
            )
          }

  def all(data: SwimData): List[KyoTool] =
    List(FindBeaches(data), SwimConditions(data), PlanOuting(data))
