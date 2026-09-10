package marola.water

import kyo.*

import marola.log.Log

/**
 * The primary source, then a backup when it gives nothing.
 *
 * Written for IMA/SC: its JSON feed went unreachable on 2026-09-08 and Florianópolis lost every
 * verdict, while the same agency's weekly bulletin PDF stayed up the whole time. One agency, two
 * publication channels, and no reason for an outage in one to be a blackout for the user.
 *
 * "Gives nothing" covers a thrown failure, an empty answer, and points that carry no samples,
 * because from the map's side all three are the same thing. Stacked under
 * `CachedWaterQualityClient`, the order is: live feed, backup channel, last good fetch.
 */
final class FallbackWaterQualityClient(primary: WaterQualityClient, backup: WaterQualityClient)
    extends WaterQualityClient:

  def name: String = primary.name

  def samplingPoints: List[SamplingPoint] < Sync =
    Abort.run(Abort.catching[Throwable](primary.samplingPoints)).map {
      case Result.Success(points) if SamplingPoint.anyUsable(points) => points
      case Result.Success(_) =>
        FallbackWaterQualityClient.log.warn(
          s"${primary.name}: primary source returned nothing — trying ${backup.getClass.getSimpleName}"
        )
        FallbackWaterQualityClient.attempt(backup)
      case other =>
        FallbackWaterQualityClient.log.warn(
          s"${primary.name}: primary source failed ($other) — trying ${backup.getClass.getSimpleName}"
        )
        FallbackWaterQualityClient.attempt(backup)
    }

object FallbackWaterQualityClient:

  private val log = Log.forName(getClass.getName)

  /**
   * The backup failing too is not an error to propagate: it means "no data", which every caller
   * already handles, and the cache below still gets its turn.
   */
  private def attempt(backup: WaterQualityClient): List[SamplingPoint] < Sync =
    Abort.run(Abort.catching[Throwable](backup.samplingPoints)).map {
      case Result.Success(points) =>
        if !SamplingPoint.anyUsable(points) then log.warn("backup source returned nothing either")
        else log.info(s"backup source supplied ${points.size} points")
        points
      case other =>
        log.warn(s"backup source failed too ($other)")
        Nil
    }
