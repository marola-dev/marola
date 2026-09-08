package marola.sightings

import kyo.*

/**
 * Where user-reported jellyfish/whale sightings go — the missing piece for the calibration feedback
 * loop `ARCHITECTURE.md` §8 describes ("let users report sightings back... accumulate that as real
 * labeled data").
 */
trait SightingStore:
  def record(sighting: Sighting): Unit < Sync
  def recentFor(beachName: String, limit: Int = 5): List[Sighting] < Sync
