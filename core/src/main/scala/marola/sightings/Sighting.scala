package marola.sightings

import java.time.Instant

enum SightingKind derives CanEqual:
  case Jellyfish, Whale, Pollution // Pollution: MIP-0001 §4.3 — oil, foam, sewage seen first-hand

final case class Sighting(
    beachName: String,
    kind: SightingKind,
    note: Option[String],
    reportedAt: Instant
)
